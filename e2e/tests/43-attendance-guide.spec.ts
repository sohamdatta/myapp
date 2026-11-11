import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, signIn, newPage, nav, tab, acceptInvite, api, isoDay, shot as save } from './helpers';

// The screenshots in docs/user-guide/attendance.md, taken in a demo Organization of their own (Northwind Foods)
// so they show tidy names. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.
const em = (n: string) => `${n}@demo.example`;
async function ok(p: Promise<{ status: number; body: any }>) { const r = await p; expect([200, 204], JSON.stringify(r.body)).toContain(r.status); return r.body; }
async function shot(p: Page, name: string, fn: () => Promise<void>) {
  await fn();
  await save(p, `attendance-${name}`);
}
test('the attendance guide, screen by screen', async ({ browser }) => {
  test.setTimeout(420_000);
  let owner: Page;
  {
    const admin = await newPage(browser); await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    await admin.fill('#org-name', 'Northwind Foods'); await admin.fill('#org-admin', em('owner')); await admin.click('text=Create and invite');
    await expect(admin.getByText(`Invitation for ${em('owner')}.`)).toBeVisible();
    owner = await acceptInvite(browser, await admin.locator('.copy-row input').inputValue());
  }
  const people: [string, string[]][] = [['hr', ['hr_admin']], ['priya', ['employee']], ['arjun', ['employee']], ['kavya', ['employee']], ['rahul', ['employee']], ['neha', ['employee']], ['vikram', ['employee']], ['meera', ['employee', 'manager']]];
  const pg: Record<string, Page> = {};
  const existing = new Set<string>((await api(owner, 'GET', '/org/attendance/assignments')).body.map((p: any) => p.email));
  for (const [n, roles] of people) {
    if (existing.has(em(n))) { pg[n] = await signIn(browser, em(n)); continue; }
    const b = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles }));
    pg[n] = await acceptInvite(browser, `/?invite=${b.token}`);
  }
  const hr = pg.hr;
  const list = (await api(hr, 'GET', '/org/attendance/assignments')).body as any[];
  const id = Object.fromEntries(list.map((p) => [p.email, p.membershipId]));
  const locs = (await api(hr, 'GET', '/org/attendance/locations')).body as any[];
  const mk = async (name: string, body: any) => locs.find((l) => l.name === name) ?? ok(api(hr, 'POST', '/org/attendance/locations', { name, timezone: 'Asia/Kolkata', stateCode: 'KA', active: true, punchCheck: 'none', ...body }));
  const head = await mk('Head office', { punchCheck: 'either', allowedIps: ['203.0.113.0/24'], latitude: 12.9716, longitude: 77.5946, radiusM: 200 });
  const branch = await mk('Pune branch', { stateCode: 'MH' });
  const plant = await mk('Hosur plant', { stateCode: 'TN', punchCheck: 'ip', allowedIps: ['198.51.100.0/24'] });
  if (!locs.find((l) => l.name === 'Hosur plant')) await ok(api(hr, 'POST', `/org/attendance/locations/${plant.id}/mode`, { mode: 'device', effectiveFrom: isoDay(0) }));
  const shifts = (await api(hr, 'GET', '/org/attendance/shifts')).body as any[];
  const shift = shifts.find((s) => s.name === 'General') ?? await ok(api(hr, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
  if (!shifts.find((s) => s.name === 'Night')) await api(hr, 'POST', '/org/attendance/shifts', { name: 'Night', startTime: '22:00', endTime: '06:00', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true });
  const yr = new Date().getFullYear();
  for (const [d, n] of [[`${yr}-01-26`, 'Republic Day'], [`${yr}-08-15`, 'Independence Day'], [`${yr}-10-02`, 'Gandhi Jayanti'], [`${yr}-11-08`, 'Diwali'], [`${yr}-12-25`, 'Christmas']]) await api(hr, 'POST', '/org/attendance/holidays', { date: d, name: n });
  const asg = async (names: string[], l: any, extra: any = {}) => api(hr, 'POST', '/org/attendance/assignments', { membershipIds: names.map((n) => id[em(n)]), workLocationId: l.id, shiftId: shift.id, weeklyOffs: [0, 6], remoteDays: [], effectiveFrom: isoDay(-5), ...extra });
  if (!list.find((p) => p.email === em('priya') && (p.workLocationId || p.assignment))) {
    await asg(['priya', 'arjun', 'kavya', 'meera'], head); await asg(['rahul', 'neha'], branch, { remoteDays: [3] }); await asg(['vikram'], plant);
    const pun = async (n: string, day: string, dir: string, t: string) => api(hr, 'POST', `/org/attendance/users/${id[em(n)]}/punches`, { at: `${day}T${t}`, direction: dir, reason: 'Sample data' });
    for (const [n, i, o] of [['priya', '09:25', '18:40'], ['arjun', '09:52', '18:35'], ['kavya', '09:30', '14:10'], ['meera', '09:28', '19:30']] as const) { await pun(n, isoDay(-1), 'in', i); await pun(n, isoDay(-1), 'out', o); }
    await pun('priya', isoDay(0), 'in', '09:27'); await pun('meera', isoDay(0), 'in', '09:31');
    await api(pg.rahul, 'POST', '/org/attendance/me/requests', { kind: 'regularization', workDate: isoDay(-1), inTime: '09:40', outTime: '18:40', reason: 'Forgot to punch in at the gate' });
    await api(pg.kavya, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(1), toDate: isoDay(1), reason: 'Plumber visit at home' });
    await api(pg.neha, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Supplier meeting in Mumbai' });
  }
  await api(pg.rahul, 'POST', '/org/attendance/me/requests', { kind: 'regularization', workDate: isoDay(-1), inTime: '09:40', outTime: '18:40', reason: 'Forgot to punch in at the gate' });
  await api(pg.kavya, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(1), toDate: isoDay(1), reason: 'Plumber visit at home' });
  await api(pg.neha, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Supplier meeting in Mumbai' });
  await api(hr, 'POST', '/org/attendance/devices', { name: 'Plant gate', serialNumber: 'NWFOODS0001', workLocationId: plant.id });
  await hr.setViewportSize({ width: 1280, height: 860 }); await pg.priya.setViewportSize({ width: 1280, height: 860 });
  await shot(hr, '01-setup', async () => { await nav(hr, 'Attendance'); await tab(hr, 'Setup'); });
  await shot(hr, '02-location-form', async () => { await hr.getByRole('button', { name: 'Add a work location' }).click(); await hr.fill('#loc-name', 'Chennai office'); await hr.selectOption('#loc-check', 'either'); await hr.fill('#loc-ips', '203.0.113.0/24'); await hr.fill('#loc-lat', '13.0827'); await hr.fill('#loc-lon', '80.2707'); await hr.fill('#loc-radius', '200'); await hr.locator('#loc-name').scrollIntoViewIfNeeded(); });
  await shot(hr, '03-shift-form', async () => { await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'Setup'); await hr.getByRole('button', { name: 'Add a shift' }).click(); await hr.fill('#sh-name', 'Early'); await hr.fill('#sh-start', '07:00'); await hr.fill('#sh-end', '15:30'); await hr.fill('#sh-grace', '10'); await hr.locator('#sh-name').scrollIntoViewIfNeeded(); });
  await shot(hr, '04-mode', async () => { await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'Setup'); await hr.locator('tr', { hasText: 'Pune branch' }).getByRole('button', { name: 'Attendance mode' }).click(); await hr.locator('#mode-from').scrollIntoViewIfNeeded(); });
  await shot(hr, '05-people', async () => { await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'People'); const b = hr.getByLabel(/Employee code for .*priya/); await b.fill('NW-1001'); await b.press('Enter'); });
  await shot(hr, '06-wfh-rules', async () => { await tab(hr, 'Setup'); await hr.getByRole('button', { name: 'Save work-from-home rules' }).scrollIntoViewIfNeeded(); });
  await shot(hr, '07-devices', async () => { await tab(hr, 'Devices'); });
  await shot(hr, '08-import', async () => { await hr.fill('#imp-device', 'Plant gate export'); await hr.setInputFiles('#imp-file', { name: 'punches.csv', mimeType: 'text/csv', buffer: Buffer.from(`Code,Date,Time,IO\nNW-1001,${isoDay(-3).split('-').reverse().join('/')},09:31,In\nNW-1001,${isoDay(-3).split('-').reverse().join('/')},18:32,Out\n`) }); await hr.locator('#map-code').scrollIntoViewIfNeeded(); });
  await shot(hr, '09-board', async () => { await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'Daily'); await hr.getByRole('button', { name: 'Previous day' }).click(); });
  await shot(hr, '10-board-details', async () => { await hr.locator('tr', { hasText: 'arjun' }).first().getByRole('button', { name: /Details/ }).first().click(); });
  await shot(hr, '11-requests', async () => { await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'Requests'); });
  await shot(pg.priya, '12-my-attendance', async () => { await pg.priya.reload(); await nav(pg.priya, 'My attendance'); });
  await shot(pg.priya, '13-new-request', async () => { await pg.priya.getByRole('button', { name: 'New request' }).click(); await pg.priya.selectOption('#req-kind', 'work_from_home'); await pg.priya.locator('#req-kind').scrollIntoViewIfNeeded(); });
});
