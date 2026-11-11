import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import { dbAdmin, readState, statePath, writeState } from './attendance-cases';
import AxeBuilder from '@axe-core/playwright';
import { SUPER_ADMIN, signIn, newPage, nav, tab, api, isoDay, istWeekday, daysToNextMonday, nowMs } from './helpers';
import { join, tokenOf } from './join';

const DEVICE_URL = 'http://127.0.0.1:8080/iclock';
const U = (n: string) => `${n}@acme.test`;
const ST = statePath('b');
let A: any;
const NEW = ['d1', 'd2', 'd3', 'd4', 'd5', 'd6', 'nt', 'wk', 'n1', 'n2', 'n3', 'mgr2', 'w1', 'w2', 'w3', 'w4', 'w5', 'w6', 'w7', 'p1', 'p2', 'g1'];
const P: Record<string, Page> = {};
let owner: Page, hr: Page, aud: Page, mgr: Page;
const E: Record<string, Page> = {};
let ids: Record<string, string> = {};
const L: Record<string, any> = {};
let shift: any; let night: any;
const err = (p: Page) => p.locator('form .error').first();
const month = (d: string) => d.slice(0, 7);

async function ok(p: Promise<{ status: number; body: any }>) { const r = await p; expect([200, 204], JSON.stringify(r.body)).toContain(r.status); return r.body; }
async function reopen(p: Page, tabName: string) { await p.reload(); await nav(p, 'Attendance'); await tab(p, tabName); }
async function mkLoc(body: any) { return ok(api(owner, 'POST', '/org/attendance/locations', { timezone: 'Asia/Kolkata', stateCode: 'KA', active: true, punchCheck: 'none', ...body })); }
async function assign(names: string[], l: any, extra: any = {}, sh = shift) {
  await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: names.map((n) => ids[U(n)]), workLocationId: l.id, shiftId: sh.id, weeklyOffs: [], remoteDays: [], effectiveFrom: isoDay(-30), ...extra }));   // date-safe: starts well before every day worked out here
}
async function manual(name: string, day: string, dir: 'in' | 'out', time: string, dayOut = day) {
  const r = await api(hr, 'POST', `/org/attendance/users/${ids[U(name)]}/punches`, { at: `${dir === 'in' ? day : dayOut}T${time}`, direction: dir, reason: 'Test entry' });
  expect([200, 204], JSON.stringify(r.body)).toContain(r.status);
}
async function dayRow(name: string, day: string) {
  const r = await api(hr, 'GET', `/org/attendance/users/${ids[U(name)]}/days?month=${month(day)}`);
  expect(r.status, JSON.stringify(r.body)).toBe(200);
  const hit = (r.body as any[]).find((d) => d.workDate === day);
  if (hit) return hit;
  const b = await api(hr, 'GET', `/org/attendance/days?date=${day}`);
  return (b.body as any[]).find((d) => d.membershipId === ids[U(name)]);
}
function stamp(ms: number) { return new Date(ms + 330 * 60_000).toISOString().slice(0, 19).replace('T', ' '); }
async function dev(request: any, method: 'GET' | 'POST', path: string, body?: string) {
  const r = method === 'GET' ? await request.get(`${DEVICE_URL}${path}`) : await request.post(`${DEVICE_URL}${path}`, { data: body });
  return { status: r.status() as number, text: (await r.text()) as string };
}
const sql = dbAdmin;
const settingsPut = (body: any) => api(owner, 'PUT', '/org/attendance/settings', { leaveYearStartMonth: 1, wfhEnabled: true, wfhRequiresPunch: false, wfhLimitPerWeek: null, wfhLimitPerMonth: null, ...body });

test.describe('attendance test cases, part 2', () => {
  test.beforeAll(async ({ browser }) => {
    A = readState('a'); ids = { ...A.ids }; Object.assign(L, A.loc); shift = A.shift;
    owner = await signIn(browser, 'owner@acme.test'); hr = await signIn(browser, U('hr')); aud = await signIn(browser, U('aud')); mgr = await signIn(browser, U('mgr'));
    for (const n of ['e1', 'e2', 'e3', 'e4', 'e5', 'e6', 'e7', 'e8', 'e9']) E[n] = await signIn(browser, U(n));
    if (fs.existsSync(ST)) {
      const st = readState('b'); ids = st.ids; Object.assign(L, st.L); night = st.night;
      for (const n of NEW) P[n] = await signIn(browser, U(n));
      return;
    }
    const have0 = await ok(api(owner, 'GET', '/org/attendance/assignments'));
    const emails = new Set(have0.map((p: any) => p.email));
    for (const n of NEW) {
      if (emails.has(U(n))) { P[n] = await signIn(browser, U(n)); continue; }
      const b = await ok(api(owner, 'POST', '/org/invitations', { email: U(n), roles: n === 'mgr2' ? ['employee', 'manager'] : ['employee'] }));
      P[n] = await join(browser, b.token, U(n));
    }
    const list = await ok(api(owner, 'GET', '/org/attendance/assignments'));
    ids = Object.fromEntries(list.map((p: any) => [p.email, p.membershipId]));
    const shifts = await ok(api(owner, 'GET', '/org/attendance/shifts'));
    night = shifts.find((x: any) => x.name === 'Night') ?? await ok(api(owner, 'POST', '/org/attendance/shifts', { name: 'Night', startTime: '22:00', endTime: '06:00', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
    const locs = await ok(api(owner, 'GET', '/org/attendance/locations'));
    const getLoc = async (name: string, body: any, mode = false) => {
      const f = locs.find((x: any) => x.name === name); if (f) return f;
      const l = await mkLoc({ name, ...body });
      if (mode) await ok(api(owner, 'POST', `/org/attendance/locations/${l.id}/mode`, { mode: 'device', effectiveFrom: isoDay(0) }));
      return l;
    };
    L.eng = await getLoc('Engine office', {});
    L.ipx = await getLoc('IP only office', { punchCheck: 'ip', allowedIps: ['203.0.113.0/24'] });
    L.hos = await getLoc('Hosur gate', { punchCheck: 'ip', allowedIps: ['203.0.113.0/24'] }, true);
    L.sw = await getLoc('Switchable', {});
    const wd = new Date(isoDay(-5) + 'T12:00:00Z').getUTCDay();
    const done = new Set(have0.filter((p: any) => p.workLocationId || p.assignment).map((p: any) => p.email));
    const todo = (names: string[]) => names.filter((n) => !done.has(U(n)));
    const A1 = todo(['d1', 'd2', 'd3', 'd4', 'd5', 'd6', 'n1', 'p1', 'p2']); if (A1.length) await assign(A1, L.eng);
    if (todo(['nt']).length) await assign(['nt'], L.eng, {}, night);
    if (todo(['wk']).length) await assign(['wk'], L.eng, { weeklyOffs: [wd] });
    if (todo(['g1']).length) await assign(['g1'], L.geo ?? L.eng);
    const A2 = todo(['w1', 'w2', 'w3', 'w4']); if (A2.length) await assign(A2, L.eng);
    if (todo(['w5']).length) await assign(['w5'], L.ipx);
    if (todo(['w6']).length) await assign(['w6'], L.dev);
    if (todo(['w7']).length) await assign(['w7'], L.eng, { remoteDays: [istWeekday()] });
    if (todo(['mgr2']).length) await assign(['mgr2'], L.eng);
    writeState('b', { ids, L, night });
  });

  // ================= engine =================
  test('TC-250 TC-257 TC-077 TC-251 TC-252 TC-256 punches become day results', async () => {
    const d1 = isoDay(-1), d2 = isoDay(-2), d3 = isoDay(-3);
    await manual('d1', d1, 'in', '09:30'); await manual('d1', d1, 'out', '18:30');
    await manual('d2', d2, 'in', '09:30'); await manual('d2', d2, 'out', '14:30');
    await manual('d4', d1, 'in', '09:30');
    await manual('d5', d2, 'in', '09:30'); await manual('d5', d2, 'out', '17:30');
    await manual('d6', d3, 'in', '09:30'); await manual('d6', d3, 'out', '19:30');
    expect((await dayRow('d1', d1)).status).toBe('present');
    expect((await dayRow('d2', d2)).status).toBe('half_day');
    expect(String((await dayRow('d3', d1))?.status ?? 'absent')).toMatch(/absent|weekly_off|holiday/);
    expect((await dayRow('d4', d1)).incomplete).toBe(true);
    const early = await dayRow('d5', d2); expect(early.earlyMinutes, JSON.stringify(early)).toBeGreaterThan(0);
    const ot = await dayRow('d6', d3); expect(ot.status).toBe('present'); expect(ot.overtimeMinutes, JSON.stringify(ot)).toBeGreaterThan(0);
  });
  test('TC-074 TC-258 grace boundary and late formatting', async () => {
    const a = isoDay(-4), b = isoDay(-5), c = isoDay(-9);   // date-safe: the month view is opened at its month
    await manual('d1', a, 'in', '09:40'); await manual('d1', a, 'out', '18:30');
    await manual('d2', b, 'in', '09:41'); await manual('d2', b, 'out', '18:30');
    await manual('d3', c, 'in', '10:45'); await manual('d3', c, 'out', '19:30');
    expect((await dayRow('d1', a)).lateMinutes).toBe(0);
    expect((await dayRow('d2', b)).lateMinutes).toBeGreaterThan(0);
    expect((await dayRow('d3', c)).lateMinutes).toBeGreaterThanOrEqual(60);
    await P.d3.reload(); await nav(P.d3, 'My attendance');
    await P.d3.locator('#my-month').fill(c.slice(0, 7));
    await expect(P.d3.locator('tr', { hasText: /10:45|10.45/ }).first()).toContainText(/\d h \d+ min/);
  });
  test('TC-080 hours between half and full give half; below half absent', async () => {
    const x = isoDay(-4), y = isoDay(-5);
    await manual('d5', x, 'in', '09:30'); await manual('d5', x, 'out', '14:30');
    await manual('d6', y, 'in', '09:30'); await manual('d6', y, 'out', '13:29');
    expect((await dayRow('d5', x)).status).toBe('half_day');
    expect((await dayRow('d6', y)).status).toBe('absent');
  });
  test('TC-072 night punch belongs to shift start day', async () => {
    const s = isoDay(-3), n = isoDay(-2);
    await manual('nt', s, 'in', '22:05'); await manual('nt', s, 'out', '06:10', n);
    const r = await dayRow('nt', s); expect(r.status).toBe('present'); expect(r.workedMinutes).toBeGreaterThan(450);
  });
  test('TC-088 TC-253 holiday and TC-106 weekly off results', async () => {
    const hol = await mkLoc({ name: 'Holiday site' }).catch(async () => (await ok(api(owner, 'GET', '/org/attendance/locations'))).find((x: any) => x.name === 'Holiday site'));
    await assign(['wk'], hol, { effectiveFrom: isoDay(0) });
    await api(owner, 'POST', '/org/attendance/holidays', { date: isoDay(0), name: 'Site holiday', workLocationId: hol.id });
    await assign(['nt'], L.eng, { effectiveFrom: isoDay(0), weeklyOffs: [new Date(isoDay(0) + 'T12:00:00Z').getUTCDay()] }, night);
    const h = await dayRow('wk', isoDay(0)); expect(h.status, JSON.stringify(h)).toBe('holiday');
    const w = await dayRow('nt', isoDay(0)); expect(w.status, JSON.stringify(w)).toBe('weekly_off');
  });
  test('TC-255 not assigned before first assignment', async () => {
    const r = await api(hr, 'GET', `/org/attendance/users/${ids[U('e8')]}/days?month=${month(isoDay(-1))}`);
    const row = (r.body as any[]).find((d) => d.workDate === isoDay(-1));
    expect(row?.status ?? 'not_assigned').toBe('not_assigned');
  });
  test('TC-056 present mode does not measure late or overtime', async () => {
    const r = await api(hr, 'GET', `/org/attendance/days?date=${isoDay(0)}`);
    const row = (r.body as any[]).find((d) => d.membershipId === ids[U('e6')]);
    expect(row.mode).toBe('present'); expect(row.lateMinutes ?? 0).toBe(0); expect(row.overtimeMinutes ?? 0).toBe(0);
  });
  test('TC-062 past days keep their old mode', async () => {
    const r = await api(hr, 'GET', `/org/attendance/days?date=${isoDay(-1)}`);
    const row = (r.body as any[]).find((d) => d.membershipId === ids[U('e6')]);
    expect(row.mode).toBe('app');
  });
  test('TC-187 TC-188 tiles match rows; today shows Working / Not in yet', async () => {
    await reopen(hr, 'Daily');
    await expect(hr.locator('.tiles')).toBeVisible();
    const rows = await hr.locator('tbody tr:not(.detail-row)').count();
    const sum = (await hr.locator('.tile').evaluateAll((els) => els.map((e) => ({ l: e.querySelector('.tile-label')?.textContent, v: Number(e.querySelector('.tile-value')?.textContent) })))).filter((t) => t.l !== 'Late').reduce((a, t) => a + t.v, 0);
    expect(sum).toBe(rows);
    await expect(hr.getByText('Not in yet').first()).toBeVisible();
    await expect(hr.getByText('Working', { exact: false }).first()).toBeVisible();
  });
  test('TC-191 add manual punch from the board', async () => {
    const d = isoDay(-1);
    await reopen(hr, 'Daily'); await hr.getByRole('button', { name: 'Previous day' }).click();
    const row = hr.locator('tr', { hasText: U('d4') }); await row.getByRole('button', { name: 'Details' }).click();
    const det = hr.locator('tr.detail-row'); await det.getByLabel('Reason').fill('Forgot to punch out'); await det.getByRole('button', { name: 'Add punch' }).click();
    await expect.poll(async () => (await dayRow('d4', d)).incomplete).toBe(false);
  });
  test('TC-212 approval fixes the day', async () => {
    const d = isoDay(0);
    await ok(api(P.d3, 'POST', '/org/attendance/me/requests', { kind: 'regularization', workDate: d, inTime: '09:30', outTime: '10:30', reason: 'Device was down' }));
    await reopen(hr, 'Requests');
    const row = hr.locator('tr', { hasText: U('d3') }); await row.getByRole('button', { name: 'Approve' }).click();
    await expect(hr.getByText(/Request approved/)).toBeVisible();
    const fixed = await dayRow('d3', d); expect(fixed.firstIn, JSON.stringify(fixed)).toBeTruthy(); expect(fixed.lastOut).toBeTruthy(); expect(fixed.incomplete).toBe(false);
  });
  test('TC-236 request row shows the day so far', async () => {
    await ok(api(P.d2, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Client visit off site' }));
    await reopen(hr, 'Requests'); await expect(hr.getByText(/Recorded that day so far/).first()).toBeVisible();
  });
  test('TC-216 approved on duty counts as a full day', async () => {
    await reopen(hr, 'Requests'); await hr.locator('tr', { hasText: U('d2') }).getByRole('button', { name: 'Approve' }).click();
    await expect(hr.getByText(/Request approved/)).toBeVisible();
    const od = await dayRow('d2', isoDay(0)); expect(od.status, JSON.stringify(od)).toBe('on_duty');
  });
  test('TC-245 TC-246 TC-247 TC-248 TC-249 employee month view', async () => {
    await P.d1.reload(); await nav(P.d1, 'My attendance');
    for (const l of ['Present', 'Half days', 'Leave', 'Absent', 'Late arrivals']) await expect(P.d1.locator('.tile-label', { hasText: l })).toBeVisible();
    await P.d1.getByRole('button', { name: 'Why' }).first().click(); await expect(P.d1.locator('tr.detail-row')).toBeVisible();
    await expect(P.d1.locator('tr.detail-row')).toContainText(/Result|Shift|Worked/);
    await P.d1.locator('#my-month').fill(isoDay(-45).slice(0, 7)); await expect(P.d1.locator('h2').first()).toBeVisible();   // date-safe: any earlier month will do
    await P.d1.locator('#my-month').fill(isoDay(-400).slice(0, 7));   // date-safe: a month with no days at all
    await expect(P.d1.getByText('No days to show for this month.')).toBeVisible();
  });
  test('TC-259 employee sees only own attendance page', async () => {
    await expect(P.d1.getByRole('link', { name: 'My attendance' }).first()).toBeVisible();
    await expect(P.d1.getByRole('link', { name: 'Attendance', exact: true })).toHaveCount(0);
  });

  // ================= locations & modes (UI) =================
  test('TC-033 CIDR and IPv6 accepted', async () => {
    await reopen(hr, 'Setup'); await hr.getByRole('button', { name: 'Add a work location' }).click();
    await hr.fill('#loc-name', 'Cidr office'); await hr.selectOption('#loc-check', 'ip'); await hr.fill('#loc-ips', '203.0.113.0/32, 2001:db8::/32');
    await hr.getByRole('button', { name: 'Save location' }).click();
    await expect(hr.locator('tr', { hasText: 'Cidr office' })).toContainText('2001:db8');
  });
  test('TC-036 longitude limits', async () => {
    for (const [lon, good] of [['181', false], ['-181', false], ['180', true]] as const) {
      await reopen(hr, 'Setup'); await hr.getByRole('button', { name: 'Add a work location' }).click();
      await hr.fill('#loc-name', 'Lon ' + lon); await hr.selectOption('#loc-check', 'location');
      await hr.fill('#loc-lat', '10'); await hr.fill('#loc-lon', lon); await hr.fill('#loc-radius', '100'); await hr.getByRole('button', { name: 'Save location' }).click();
      if (good) await expect(hr.locator('tr', { hasText: 'Lon ' + lon })).toBeVisible(); else await expect(err(hr)).toBeVisible();
    }
  });
  test('TC-040 use where I am now fills fields', async () => {
    await hr.context().grantPermissions(['geolocation']); await hr.context().setGeolocation({ latitude: 12.971599, longitude: 77.594566, accuracy: 10 });
    await reopen(hr, 'Setup'); await hr.getByRole('button', { name: 'Add a work location' }).click(); await hr.selectOption('#loc-check', 'location');
    await hr.getByRole('button', { name: 'Use where I am now' }).click();
    await expect(hr.locator('#loc-lat')).toHaveValue('12.971599'); await expect(hr.locator('#loc-lon')).toHaveValue('77.594566'); await expect(hr.locator('#loc-radius')).toHaveValue('200');
  });
  test('TC-042 geolocation unavailable does nothing', async () => {
    await reopen(hr, 'Setup'); await hr.getByRole('button', { name: 'Add a work location' }).click(); await hr.selectOption('#loc-check', 'location');
    await hr.evaluate(() => Object.defineProperty(navigator, 'geolocation', { value: undefined, configurable: true }));
    await hr.getByRole('button', { name: 'Use where I am now' }).click(); await expect(hr.locator('#loc-lat')).toHaveValue('');
  });
  test('TC-053 TC-057 TC-063 switch to present by default via the screen', async () => {
    await reopen(owner, 'Setup');
    await owner.locator('tr', { hasText: 'Switchable' }).getByRole('button', { name: 'Attendance mode' }).click();
    await expect(owner.locator('#mode-from')).toHaveValue(isoDay(0));
    await owner.locator('label.choice', { hasText: 'Present by default' }).click();
    await owner.getByRole('button', { name: 'Change how attendance is recorded' }).click();
    await expect(owner.locator('tr', { hasText: 'Switchable' })).toContainText('Present by default');
  });
  test('TC-065 device count wording', async () => {
    await reopen(owner, 'Devices');
    for (const [n, sn] of [['Hosur one', 'TCSNHOS0001'], ['Hosur two', 'TCSNHOS0002']]) {
      if (!(await owner.locator('tr', { hasText: n }).count())) {
        await owner.fill('#dev-name', n); await owner.fill('#dev-serial', sn); await owner.selectOption('#dev-location', { label: 'Hosur gate' });
        await owner.getByRole('button', { name: 'Register device' }).click(); await expect(owner.getByText(`${n} is registered`)).toBeVisible();
      }
      await tab(owner, 'Setup'); await expect(owner.locator('tr', { hasText: 'Hosur gate' })).toContainText(n === 'Hosur one' ? '1 device' : '2 devices'); await tab(owner, 'Devices');
    }
  });
  test('TC-071 end equal to start means next day', async () => {
    await reopen(hr, 'Setup'); await hr.getByRole('button', { name: 'Add a shift' }).click();
    await hr.fill('#sh-name', 'Round clock'); await hr.fill('#sh-start', '09:00'); await hr.fill('#sh-end', '09:00'); await hr.getByRole('button', { name: 'Save shift' }).click();
    await expect(hr.locator('tr', { hasText: 'Round clock' })).toContainText('ends the next day');
  });
  test('TC-104 TC-113 TC-114 weekly off none, future change, same-date correction', async () => {
    await reopen(hr, 'People');
    const pick = async (offs: string[], from: string) => {
      await hr.selectOption('#as-location', { label: 'Engine office' }); await hr.selectOption('#as-shift', { index: 1 }); await hr.fill('#as-from', from);
      const g = hr.getByRole('group', { name: 'Weekly offs' });
      for (const d of ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']) { const b = g.getByLabel(d); if ((await b.isChecked()) !== offs.includes(d)) await b.click(); }
      await hr.getByLabel(/Select .*n2/).check(); await hr.getByRole('button', { name: /Assign to 1 selected/ }).click();
    };
    await pick([], isoDay(0)); await expect(hr.locator('tr', { hasText: U('n2') })).toContainText('None');
    await pick(['Saturday'], isoDay(7)); await expect(hr.locator('tr', { hasText: U('n2') })).toContainText('Changes on');   // date-safe: a change ahead, whatever the month
    await pick(['Friday'], isoDay(0)); await expect(hr.locator('tr', { hasText: U('n2') })).toContainText('Fri');
  });
  test('TC-119 clearing an employee code', async () => {
    await reopen(hr, 'People'); const box = hr.getByLabel(/Employee code for .*e9/);
    await box.fill('X1'); await box.press('Enter'); await box.fill(''); await box.press('Enter'); await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'People');
    await expect(hr.getByLabel(/Employee code for .*e9/)).toHaveValue('');
  });
  test('TC-127 auditor sees WFH settings read-only', async () => {
    await reopen(aud, 'Setup'); await expect(aud.getByLabel('Users can work from home')).toBeDisabled(); await expect(aud.getByRole('button', { name: 'Save work-from-home rules' })).toHaveCount(0);
  });

  // ================= requests =================
  test('TC-228 empty requests list for an assigned user', async () => {
    await P.n1.reload(); await nav(P.n1, 'My attendance'); await expect(P.n1.getByText('You have not raised any requests.')).toBeVisible();
  });
  test('TC-210 TC-214 TC-215 request form limits', async () => {
    await P.n1.getByRole('button', { name: 'New request' }).click();
    await expect(P.n1.locator('#req-date')).toHaveAttribute('max', isoDay(0));
    await P.n1.selectOption('#req-kind', 'on_duty');
    await expect(P.n1.locator('#req-to')).toHaveAttribute('min', /\d{4}-\d\d-\d\d/);
    await P.n1.fill('#req-date', isoDay(1)); await P.n1.fill('#req-to', isoDay(33)); await P.n1.fill('#req-reason', 'Long project onsite');   // date-safe: over 31 days is refused whatever the month
    await P.n1.getByRole('button', { name: 'Send request' }).click(); await expect(err(P.n1)).toContainText('at most 31');
    const r = await api(P.n1, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(5), toDate: isoDay(3), reason: 'Reversed range' }); expect(r.status).toBe(400);
  });
  test('TC-213 on-duty range creates requests', async () => {
    await P.n1.reload(); await nav(P.n1, 'My attendance'); await P.n1.getByRole('button', { name: 'New request' }).click();
    await P.n1.selectOption('#req-kind', 'on_duty'); await P.n1.fill('#req-date', isoDay(1)); await P.n1.fill('#req-to', isoDay(3)); await P.n1.fill('#req-reason', 'Customer site visit');
    await P.n1.getByRole('button', { name: 'Send request' }).click();
    await expect(P.n1.locator('tr', { hasText: 'On duty away from the office' })).toHaveCount(3);
  });
  test('TC-220 ahead window boundary', async () => {
    const good = await api(P.n2, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(60), toDate: isoDay(60), reason: 'Planned travel day' });   // date-safe: the 60-day window, whatever the month
    const bad = await api(P.n2, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(61), toDate: isoDay(61), reason: 'Too far ahead day' });   // date-safe: the 60-day window, whatever the month
    expect(bad.status).toBe(400); expect(bad.body.code).toBe('attendance.request_window'); expect([200, 409]).toContain(good.status);
  });
  test('TC-222 TC-223 TC-224 TC-225 request window settings', async () => {
    await api(owner, 'POST', '/org/attendance/assignments', { membershipIds: [ids[U('n3')]], workLocationId: L.eng.id, shiftId: shift.id, weeklyOffs: [], remoteDays: [], effectiveFrom: isoDay(-30) });   // date-safe: starts well before every day worked out here
    const put = (key: string, value: number, scope = 'organization', scopeId?: string) => api(owner, 'PUT', `/org/settings/${key}`, { scope, scopeId, value, reason: 'Test window' });
    const rst = (scope: string, scopeId?: string) => api(owner, 'PUT', '/org/settings/attendance.request.back_days', { scope, scopeId, reset: true, reason: 'Reset window' });
    await rst('location', L.eng.id); await rst('organization');
    const bad = [await put('attendance.request.back_days', 91), await put('attendance.request.back_days', -1), await put('attendance.request.ahead_days', 366)];
    for (const b of bad) expect(b.status).toBeGreaterThanOrEqual(400);
    const good = await put('attendance.request.back_days', 7); expect([200, 204, 409], JSON.stringify(good.body)).toContain(good.status);
    const old = await api(P.n3, 'POST', '/org/attendance/me/requests', { kind: 'regularization', workDate: isoDay(-8), inTime: '09:30', outTime: '18:30', reason: 'Too old to correct' });   // date-safe: beyond the 7-day window, whatever the month
    expect(old.status, JSON.stringify(old.body)).toBe(400); expect(old.body.code).toBe('attendance.request_window');
    const loc = await put('attendance.request.back_days', 3, 'location', L.eng.id); expect([200, 204, 409], JSON.stringify(loc.body)).toContain(loc.status);
    const tight = await api(P.n3, 'POST', '/org/attendance/me/requests', { kind: 'regularization', workDate: isoDay(-5), inTime: '09:30', outTime: '18:30', reason: 'Beyond location window' });
    expect(tight.status, JSON.stringify(tight.body)).toBe(400);
    const ex = await api(owner, 'GET', '/org/settings?group=attendance'); expect(ex.status).toBe(200);
    await rst('location', L.eng.id); await rst('organization');
  });
  test('TC-227 decided requests cannot be cancelled', async () => {
    await E.e3.reload(); await nav(E.e3, 'My attendance');
    await expect(E.e3.locator('tr', { hasText: /approved/i }).getByRole('button', { name: 'Cancel' })).toHaveCount(0);
  });
  test('TC-231 rejection note reaches the employee', async () => {
    await ok(api(P.n3, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(-1), toDate: isoDay(-1), reason: 'Visit to supplier' }));
    await reopen(hr, 'Requests'); const row = hr.locator('tr', { hasText: U('n3') });
    await row.getByLabel(/Note for/).fill('Not an official visit'); await row.getByRole('button', { name: 'Reject' }).click(); await hr.getByRole('button', { name: 'Reject request' }).click();
    await expect(hr.getByText(/Request rejected for/)).toBeVisible();
    await P.n3.reload(); await nav(P.n3, 'My attendance'); await expect(P.n3.getByText('Not an official visit')).toBeVisible();
  });
  test('TC-232 manager sees only team requests', async () => {
    await api(E.e6, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(-1), toDate: isoDay(-1), reason: 'Team member visit' });
    await api(E.e1, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(-1), toDate: isoDay(-1), reason: 'Other team visit' });
    await reopen(mgr, 'Requests'); await expect(mgr.locator('tr', { hasText: U('e6') })).toBeVisible(); await expect(mgr.locator('tr', { hasText: U('e1') })).toHaveCount(0);
  });
  test('TC-186 manager with no team sees empty board', async () => {
    await P.mgr2.reload(); await nav(P.mgr2, 'Attendance'); await expect(P.mgr2.getByText(/Nobody to show/)).toBeVisible();
  });
  test('TC-185 TC-206 board date max and register busy label', async () => {
    await reopen(hr, 'Daily'); await expect(hr.locator('#board-date')).toHaveAttribute('max', isoDay(0));
    await hr.route('**/attendance/register*', async (r) => { await new Promise((x) => setTimeout(x, 1500)); try { await r.continue(); } catch {} });
    await hr.getByRole('button', { name: /Download the .* register/ }).click(); await expect(hr.getByRole('button', { name: 'Preparing…' })).toBeVisible();
    await hr.unrouteAll({ behavior: 'ignoreErrors' });
  });

  // ================= WFH =================
  test('TC-131 TC-136 weekly limit blocks and cancel frees', async () => {
    await ok(settingsPut({ wfhLimitPerWeek: 2 }));
    const m = daysToNextMonday(); const ask = (i: number) => api(P.w1, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(m + i), toDate: isoDay(m + i), reason: 'Home day planned' });
    const a = await ask(0), b = await ask(1), c = await ask(2);
    expect([a.status, b.status]).toEqual([200, 200]); expect(c.status).toBe(409); expect(c.body.code).toBe('attendance.wfh_limit');
    const mine = (await api(P.w1, 'GET', '/org/attendance/me/requests')).body as any[];
    await ok(api(P.w1, 'POST', `/org/attendance/me/requests/${mine.find((r) => r.workDate === isoDay(m)).id}/cancel`));
    const d = await ask(2); expect(d.status, JSON.stringify(d.body)).toBe(200);
  });
  test('TC-133 usage shown in the request form', async () => {
    await ok(api(P.w3, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Home today please' }));
    await P.w3.reload(); await nav(P.w3, 'My attendance'); await P.w3.getByRole('button', { name: 'New request' }).click(); await P.w3.selectOption('#req-kind', 'work_from_home');
    await expect(P.w3.getByText(/\d of 2 used this week/)).toBeVisible();
  });
  test('TC-135 monthly limit blocks the extra day', async () => {
    await ok(settingsPut({ wfhLimitPerMonth: 3 }));
    let s = 5; while (month(isoDay(s)) !== month(isoDay(s + 3))) s++;
    const res: number[] = [];
    for (let i = 0; i < 4; i++) res.push((await api(P.w2, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(s + i), toDate: isoDay(s + i), reason: 'Home day this month' })).status);
    expect(res.slice(0, 3)).toEqual([200, 200, 200]); expect(res[3]).toBe(409);
  });
  test('TC-129 TC-244 approved WFH today counts as full day with banner', async () => {
    await ok(settingsPut({}));
    await ok(api(P.w4, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Home today please' }));
    await reopen(hr, 'Requests'); await hr.locator('tr', { hasText: U('w4') }).getByRole('button', { name: 'Approve' }).click(); await expect(hr.getByText(/Request approved/)).toBeVisible();
    await P.w4.reload(); await nav(P.w4, 'My attendance'); await expect(P.w4.getByText('Working from home').first()).toBeVisible();
    const t = await api(P.w4, 'GET', '/org/attendance/me/today'); expect(t.body.day?.status).toBe('work_from_home');
  });
  test('TC-128 punch required shows in the request hint', async () => {
    await ok(settingsPut({ wfhRequiresPunch: true }));
    await P.w3.reload(); await nav(P.w3, 'My attendance'); await P.w3.getByRole('button', { name: 'New request' }).click(); await P.w3.selectOption('#req-kind', 'work_from_home');
    await expect(P.w3.getByText('You still punch in and out on a day at home')).toBeVisible();
  });
  test('TC-130 remote punch skips the office-network check', async () => {
    await ok(api(P.w5, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Home today please' }));
    await reopen(hr, 'Requests'); await hr.locator('tr', { hasText: U('w5') }).getByRole('button', { name: 'Approve' }).click(); await expect(hr.getByText(/Request approved/)).toBeVisible();
    const r = await api(P.w5, 'POST', '/org/attendance/me/punch', {}); expect(r.status, JSON.stringify(r.body)).toBe(200);
  });
  test('TC-051 WFH day at a device location can punch in the app', async () => {
    await ok(api(P.w6, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Home today please' }));
    await reopen(hr, 'Requests'); await hr.locator('tr', { hasText: U('w6') }).getByRole('button', { name: 'Approve' }).click(); await expect(hr.getByText(/Request approved/)).toBeVisible();
    const r = await api(P.w6, 'POST', '/org/attendance/me/punch', {}); expect(r.status, JSON.stringify(r.body)).toBe(200);
    await ok(settingsPut({}));
  });
  test('TC-109 regular remote day needs no request and uses no limit', async () => {
    await P.w7.reload(); await nav(P.w7, 'My attendance'); await expect(P.w7.getByText(/Your regular days at home/)).toBeVisible();
    const h = await api(P.w7, 'GET', '/org/attendance/me/wfh'); expect(Number(h.body.usedThisWeek)).toBe(0);
    const t = await api(P.w7, 'GET', '/org/attendance/me/today'); expect(t.body.remote).toBe(true);
  });

  // ================= punching =================
  test('TC-237 TC-242 punch in, wait over 60 s, punch out', async () => {
    await P.p1.reload(); await nav(P.p1, 'My attendance'); await P.p1.getByRole('button', { name: 'Punch in' }).click(); await expect(P.p1.getByText(/Punched in at/)).toBeVisible();
    // A second punch within a minute is refused; the button works again once the minute has passed.
    await P.p1.getByRole('button', { name: 'Punch out' }).click(); await expect(P.p1.getByText('You punched a moment ago. Wait a minute before punching again')).toBeVisible();
    await expect(async () => {
      await P.p1.getByRole('button', { name: 'Punch out' }).click();
      await expect(P.p1.getByText(/Punched out at/)).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 90_000, intervals: [10_000] });
  });
  test('TC-238 busy label while checking', async () => {
    await P.p2.reload(); await nav(P.p2, 'My attendance');
    await P.p2.route('**/me/punch', async (r) => { await new Promise((x) => setTimeout(x, 1500)); try { await r.continue(); } catch {} });
    await P.p2.getByRole('button', { name: 'Punch in' }).click(); await expect(P.p2.getByRole('button', { name: 'Checking…' })).toBeVisible();
    await P.p2.unrouteAll({ behavior: 'ignoreErrors' });
  });
  test('TC-239 hint matches the punch check', async () => {
    for (const [p, t] of [[E.e1, 'You can punch from anywhere'], [E.e2, 'You must be on the office network'], [E.e3, 'Your browser will ask for your location'], [E.e4, 'Office network, or your location at the office']] as const) {
      await p.reload(); await nav(p, 'My attendance'); await expect(p.getByText(t)).toBeVisible();
    }
  });
  test('TC-026 location permission denied', async () => {
    const g = P.g1; await g.reload(); await nav(g, 'My attendance');
    const btn = g.getByRole('button', { name: 'Punch in' }); await btn.click(); await expect(g.locator('.error').first()).toBeVisible();
  });

  // ================= devices =================
  const SN = 'TCSNFIXED001', SN3 = 'TCSNOPEN0003';
  test('TC-148 TC-150 device off the office network is turned away', async ({ request }) => {
    const r = await dev(request, 'GET', '/cdata?SN=TCSNHOS0001'); expect(r.status).toBe(403);
    await reopen(owner, 'Devices'); await expect(owner.locator('tr', { hasText: 'Hosur one' })).toContainText('turned away');
  });
  test('TC-151 TC-145 TC-141 open-network device and empty log', async ({ request }) => {
    await reopen(owner, 'Devices');
    if (!(await owner.locator('tr', { hasText: 'Open gate' }).count())) {
      await owner.fill('#dev-name', 'Open gate'); await owner.fill('#dev-serial', SN3); await owner.selectOption('#dev-location', { label: 'Open office' });
      await owner.getByRole('button', { name: 'Register device' }).click(); await expect(owner.getByText('Open gate is registered')).toBeVisible();
    }
    await expect(owner.locator('tr', { hasText: 'Open gate' })).toContainText('any network');
    await owner.locator('tr', { hasText: 'Open gate' }).getByRole('button', { name: 'Log' }).click(); await expect(owner.getByText('Nothing received yet.')).toBeVisible();
    void request;
  });
  test('TC-147 silent for over a day', async ({ request }) => {
    expect((await dev(request, 'GET', `/cdata?SN=${SN3}`)).status).toBe(200);
    sql(`update app.attendance_device set last_seen_at = now() - interval '25 hours' where upper(serial_number) = '${SN3}'`);
    await reopen(owner, 'Devices'); await expect(owner.locator('tr', { hasText: 'Open gate' })).toContainText('silent for over a day');
  });
  test('TC-144 TC-149 log outcomes and unknown ids', async () => {
    await reopen(owner, 'Devices'); await owner.locator('tr', { hasText: 'Main gate' }).getByRole('button', { name: 'Log' }).click();
    for (const t of ['Taken', 'Already here', 'Nobody has this employee code', 'Could not be read']) await expect(owner.getByText(t).first()).toBeVisible();
    await expect(owner.getByText(/User ids nobody has/)).toBeVisible();
  });
  test('TC-154 TC-157 order decides in/out; future line flagged', async ({ request }) => {
    const t = nowMs(); const mid = new Date(`${isoDay(0)}T00:00:00+05:30`).getTime();
    const base = Math.max(mid + 120_000, t - 8 * 3600_000);
    const lines = [0, 1, 2, 3].map((i) => `DEV-7\t${stamp(base + (i + 1) * 20_000)}\t${i % 2 ? 0 : 1}\t1`).join('\n') + `\nDEV-7\t${stamp(t + 30 * 60_000)}\t0\t1\n`;
    const r = await dev(request, 'POST', `/cdata?SN=${SN}&table=ATTLOG&Stamp=2`, lines); expect(r.status).toBe(200);
    const today = await api(E.e7, 'GET', '/org/attendance/me/today'); const dirs = (today.body.punches as any[]).map((p) => p.direction);
    for (let i = 1; i < dirs.length; i++) expect(dirs[i]).not.toBe(dirs[i - 1]);
    await reopen(owner, 'Devices'); await owner.locator('tr', { hasText: 'Main gate' }).getByRole('button', { name: 'Log' }).click(); await expect(owner.getByText(/In the future/).first()).toBeVisible();
  });
  test('TC-156 body limits', async ({ request }) => {
    const many = Array.from({ length: 20_001 }, () => 'DEV-7\t2026-01-01 09:00:00\t0\t1').join('\n');
    const a = await dev(request, 'POST', `/cdata?SN=${SN}&table=ATTLOG&Stamp=3`, many); expect(a.status).toBeGreaterThanOrEqual(400);
    const big = await dev(request, 'POST', `/cdata?SN=${SN}&table=ATTLOG&Stamp=4`, 'x'.repeat(2 * 1024 * 1024 + 10)); expect(big.status).toBeGreaterThanOrEqual(400);
  });

  // ================= file import =================
  const upload = async (label: string, name: string, buf: Buffer) => {
    await reopen(owner, 'Devices'); await owner.fill('#imp-device', label);
    await owner.setInputFiles('#imp-file', { name, mimeType: 'text/csv', buffer: buf });
  };
  const day = (n: number) => isoDay(n).split('-').reverse().join('/');
  const mm = String(Date.now() % 50 + 10);
  test('TC-165 binary file refused', async () => {
    await upload('Binary', 'a.csv', Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 0, 1, 2, 3, 0, 0, 255])); await expect(owner.locator('form .error').first()).toBeVisible();
  });
  test('TC-166 file over 5 MB refused', async () => {
    await upload('Huge', 'h.csv', Buffer.from('code,time\n' + 'E1001,01/01/2026 09:00\n'.repeat(260_000))); await expect(owner.locator('form .error').first()).toContainText(/too large|5 MB/);
  });
  test('TC-167 more than 50,000 rows refused', async () => {
    await upload('Rows', 'r.csv', Buffer.from('code,time\n' + 'E1001,01/01/2026 09:00\n'.repeat(50_001))); await expect(owner.locator('form .error').first()).toContainText(/50000|50,000/);
  });
  test('TC-168 TC-169 TC-170 mapping rules and separate time column', async () => {
    await upload('Split', 's.csv', Buffer.from(`Code,Date,Time,IO\nDEV-7,${day(-6)},09:${mm},In\nDEV-7,${day(-6)},18:${mm},Out\n`));
    await expect(owner.locator('#map-code')).toBeVisible();
    await owner.selectOption('#map-code', ''); await owner.getByRole('button', { name: /Import 2 rows/ }).click(); await expect(owner.locator('form .error').first()).toContainText('Choose the employee code column');
    await owner.selectOption('#map-code', { index: 1 }); await owner.selectOption('#map-when', { index: 2 }); await owner.selectOption('#map-time', { index: 3 }); await owner.selectOption('#map-direction', { index: 4 });
    await owner.getByRole('button', { name: /Import 2 rows/ }).click(); await expect(owner.getByText(/2 of 2 rows imported/)).toBeVisible();
  });
  test('TC-171 mapping remembered for the device', async () => {
    await upload('Split', 's2.csv', Buffer.from(`Code,Date,Time,IO\nDEV-7,${day(-7)},09:${mm},In\n`)); await expect(owner.getByText(/as you set them last time/)).toBeVisible();
  });
  test('TC-173 day-first dates read correctly', async () => {
    await upload('Dayfirst', 'd.csv', Buffer.from(`Code,When\nDEV-7,${day(-8)} 09:${mm}\nDEV-7,${day(-8)} 18:${mm}\n`));
    await owner.selectOption('#map-code', { index: 1 }); await owner.selectOption('#map-when', { index: 2 }); await owner.getByRole('button', { name: /Import 2 rows/ }).click();
    await expect(owner.getByText(/2 of 2 rows imported/)).toBeVisible();
  });
  test('TC-172 wrong date format does not import', async () => {
    await upload('Badfmt', 'f.csv', Buffer.from(`Code,When\nE1001,${day(-9)} 09:00\n`));
    await owner.selectOption('#map-code', { index: 1 }); await owner.selectOption('#map-when', { index: 2 });
    const opts = await owner.locator('#map-format option').allTextContents(); const iso = opts.find((o) => /yyyy-MM-dd/.test(o) && !/dd.MM/.test(o.split('yyyy')[0])) ?? opts[opts.length - 1];
    await owner.selectOption('#map-format', { label: iso }); await owner.getByRole('button', { name: /Import 1 row/ }).click();
    await expect(owner.getByText(/0 of 1 rows imported/).or(owner.locator('form .error').first())).toBeVisible();
  });
  test('TC-174 invalid device time zone refused', async () => {
    await upload('Zone', 'z.csv', Buffer.from(`Code,When\nE1001,${day(-10)} 09:00\n`));
    await owner.selectOption('#map-code', { index: 1 }); await owner.selectOption('#map-when', { index: 2 }); await owner.fill('#map-zone', 'Nowhere/Zone');
    await owner.getByRole('button', { name: /Import 1 row/ }).click(); await expect(owner.locator('form .error').first()).toContainText('Unknown time zone');
  });
  test('TC-182 TC-177 long reject lists are cut and reasons shown', async () => {
    const rows = Array.from({ length: 60 }, (_, i) => `ZZ${i},${day(-11)} 09:00`).join('\n');
    await upload('Many rejects', 'm.csv', Buffer.from(`Code,When\n${rows}\n`));
    await owner.selectOption('#map-code', { index: 1 }); await owner.selectOption('#map-when', { index: 2 }); await owner.getByRole('button', { name: /Import 60 rows/ }).click();
    await expect(owner.getByText(/0 of 60 rows imported/)).toBeVisible();
    await owner.locator('details').evaluateAll((els: any[]) => els.forEach((e) => { e.open = true; })); await expect(owner.getByText(/Only the first 20 are kept/).first()).toBeVisible();
    await expect(owner.locator('tr', { hasText: 'm.csv' }).first().locator('li').first()).toBeVisible();
  });
  test('TC-179 TC-180 health tables and days-behind badge', async () => {
    await reopen(owner, 'Devices'); await expect(owner.getByRole('heading', { name: 'Device files' })).toBeVisible(); await expect(owner.getByRole('heading', { name: 'Recent files' })).toBeVisible();
    await expect(owner.locator('tr', { hasText: 'days behind' }).first()).toBeVisible();
  });

  // ================= access, audit, quality =================
  test('TC-267b TC-063 TC-083 TC-115 TC-123 TC-141 TC-178 TC-268 TC-269 audit trail', async () => {
    const text = sql("select distinct action from app.audit_event where action like 'attendance.%'");
    for (const a of ['attendance.mode_changed', 'attendance.shift_changed', 'attendance.assigned', 'attendance.wfh_allowed_set', 'attendance.device_registered', 'attendance.imported', 'attendance.request_approved', 'attendance.request_rejected', 'attendance.marked', 'attendance.manual_punch', 'attendance.holiday_added', 'attendance.employee_code_set', 'attendance.settings_changed']) expect(text, a).toContain(a);
    expect(text).not.toContain('attendance.punched');
  });
  test('TC-272 no serious accessibility violations on attendance tabs', async () => {
    for (const t of ['Daily', 'Requests', 'People', 'Setup', 'Devices']) {
      await reopen(hr, t); await expect(hr.getByText('Loading…')).toHaveCount(0);
      const res = await new AxeBuilder({ page: hr }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      const bad = res.violations.filter((v) => ['serious', 'critical'].includes(v.impact || ''));
      expect(bad.map((v) => `${t}: ${v.id}`), t).toEqual([]);
    }
  });
  test('TC-273 keyboard reaches and operates the punch button', async () => {
    const p = P.n1; await p.reload(); await nav(p, 'My attendance');
    let hit = false;
    for (let i = 0; i < 60 && !hit; i++) { await p.keyboard.press('Tab'); hit = await p.evaluate(() => /Punch (in|out)/.test(document.activeElement?.textContent || '')); }
    expect(hit).toBe(true);
  });
  test('TC-274 saved notice is announced as status', async () => {
    await reopen(hr, 'Setup'); await hr.getByRole('button', { name: 'Save work-from-home rules' }).click(); await expect(hr.getByRole('status').filter({ hasText: 'Saved.' })).toBeVisible();
  });
});

// ============ other Organizations ============
test.describe('other organizations', () => {
  let admin: Page, b: Page, c: Page;
  test.beforeAll(async ({ browser }) => {
    try { b = await signIn(browser, 'b-owner@beta.test'); c = await signIn(browser, 'c-owner@empty.test'); return; } catch {}
    admin = await newPage(browser); await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const mk = async (name: string, email: string, noAtt: boolean) => {
      await admin.fill('#org-name', name); await admin.fill('#org-admin', email);
      if (noAtt) await admin.uncheck('#org-mod-attendance_leave');
      await admin.click('text=Create and invite'); await expect(admin.getByText(`Invitation for ${email}.`)).toBeVisible();
      return admin.locator('.copy-row input').inputValue();
    };
    b = await join(browser, tokenOf(await mk('Beta Payroll Co', 'b-owner@beta.test', true)), 'b-owner@beta.test');
    await admin.reload();
    c = await join(browser, tokenOf(await mk('Empty Co', 'c-owner@empty.test', false)), 'c-owner@empty.test');
  });
  test('TC-001 attendance menu is present when the module is on', async () => {
    await expect(c.getByRole('link', { name: 'Attendance', exact: true })).toBeVisible();
  });
  test('TC-002 TC-266 module off hides pages and refuses the API', async () => {
    for (const p of ['/org/attendance/me/today', '/org/attendance/days', '/org/attendance/devices']) { const r = await api(b, 'GET', p); expect(r.status).toBe(403); expect(r.body.code).toBe('module.off'); }
    const punch = await api(b, 'POST', '/org/attendance/me/punch', {}); expect(punch.body.message).toContain('Attendance is not part of');
  });
  test('TC-004 TC-111 work calendar stays without punch controls', async () => {
    await b.reload(); await nav(b, 'Work locations'); await tab(b, 'Locations and shifts');
    await expect(b.locator('#loc-name')).toBeVisible(); await expect(b.locator('#loc-check')).toHaveCount(0);
    await tab(b, 'People').catch(() => {}); await expect(b.getByText('Regular days at home')).toHaveCount(0);
  });
  test('TC-008 TC-099 TC-093 empty Organization shows first-time guidance', async () => {
    await c.reload(); await nav(c, 'Attendance'); await tab(c, 'Setup');
    await expect(c.locator('#loc-name').or(c.getByRole('button', { name: 'Add a work location' })).first()).toBeVisible(); await expect(c.getByText(/No holidays yet for/)).toBeVisible();
    await tab(c, 'People'); await expect(c.getByText('Add a work location and a shift first')).toBeVisible();
  });
  test('TC-181 empty import health state', async () => {
    await c.reload(); await nav(c, 'Attendance'); await tab(c, 'Devices'); await expect(c.getByText('No device files have been uploaded yet.')).toBeVisible();
  });
  test('TC-265 organizations cannot see each other', async () => {
    const own = await api(c, 'POST', '/org/attendance/locations', { name: 'Empty Co office', timezone: 'Asia/Kolkata', punchCheck: 'none', active: true }); expect([200, 409]).toContain(own.status);
    const a = await api(c, 'GET', '/org/attendance/locations'); expect(JSON.stringify(a.body)).not.toContain('Open office');
    const A2 = readState('a');
    const x = await api(c, 'PUT', `/org/attendance/locations/${A2.loc.open.id}`, { name: 'Hijack', timezone: 'Asia/Kolkata', punchCheck: 'none', active: true }); expect([403, 404]).toContain(x.status);
  });
});
