import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, shot as save } from './helpers';
import { join } from './join';

// The screenshots in docs/user-guide/exits.md, taken in a demo Organization of their own (Northwind Logistics),
// so they show tidy names. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.

const em = (n: string) => `${n}@logistics${process.env.E2E_RUN ?? ''}.example`;
async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}

test('the exits guide, screen by screen', async ({ browser }) => {
  test.setTimeout(300_000);
  const admin = await newPage(browser); await admin.goto('/');
  await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
  await expect(admin.getByText('Platform console')).toBeVisible();
  const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Northwind Logistics', slug: `northwind-logistics${process.env.E2E_RUN ?? ''}`, adminEmail: em('owner') }));
  await admin.context().close();
  const owner = await join(browser, org.invitationToken, em('owner'));
  const pg: Record<string, Page> = {};
  for (const [n, roles] of [['hr', ['hr_admin']], ['neha', ['employee']], ['vikram', ['employee', 'manager']], ['sam', ['employee']]] as const) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles }));
    pg[n] = await join(browser, inv.token, em(n));
  }
  const hr = pg.hr;
  const id = Object.fromEntries((await ok(api(hr, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
  const shift = await ok(api(hr, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
  const loc = await ok(api(hr, 'POST', '/org/attendance/locations', { name: 'Pune depot', timezone: 'Asia/Kolkata', stateCode: 'MH', active: true, punchCheck: 'none' }));
  await ok(api(hr, 'POST', '/org/attendance/assignments', { membershipIds: Object.values(id), workLocationId: loc.id, shiftId: shift.id, weeklyOffs: [0], remoteDays: [], effectiveFrom: isoDay(-5) }));
  const add = (body: any) => ok(api(owner, 'POST', '/org/people/employees', { joiningDate: isoDay(-700), ...body }));   // date-safe: joined long ago
  const vikram = await add({ legalName: 'Vikram Singh', designation: 'Depot manager', membershipId: id[em('vikram')] });
  await add({ legalName: 'Neha Gupta', designation: 'Dispatcher', managerId: vikram.id, membershipId: id[em('neha')] });
  const sam = await add({ legalName: 'Sameer Khan', designation: 'Driver', managerId: vikram.id, membershipId: id[em('sam')] });
  await ok(api(owner, 'POST', '/org/leave/types', { name: 'Earned leave', kind: 'regular', paid: true, accrual: 'yearly', yearlyQuota: 18, prorateJoiners: false,
    carryForwardCap: null, excessAtYearEnd: 'lapse', whenShort: 'refuse', countDaysOff: false, expiryDays: null, active: true }));

  // Neha resigns; Vikram recommends a day; HR accepts. Sameer's contract is ended by HR.
  await ok(api(pg.neha, 'POST', '/org/me/exit', { reasonCategory: 'relocation', requestedLastDay: isoDay(5), note: 'Moving to Bengaluru with my family' }));
  const neha = (await ok(api(hr, 'GET', '/org/people/exits'))).find((x: any) => x.legalName === 'Neha Gupta');
  await ok(api(pg.vikram, 'POST', `/org/people/exits/${neha.id}/recommend`, { lastWorkingDay: isoDay(5), note: 'Handover to Ravi is planned' }));
  await ok(api(hr, 'POST', `/org/people/exits/${neha.id}/accept`, { lastWorkingDay: isoDay(5), shortfallAction: 'leave' }));
  await ok(api(hr, 'POST', '/org/people/exits', { employmentId: sam.id, kind: 'termination', reasonCategory: 'conduct', note: 'After the inquiry', lastWorkingDay: isoDay(3), shortfallAction: 'paid_in_lieu' }));

  const shot = async (p: Page, name: string, fn: () => Promise<void>) => { await fn(); await save(p, `exits-${name}`); };
  for (const p of [hr, pg.neha]) await p.setViewportSize({ width: 1280, height: 1100 });
  await shot(pg.neha, '01-my-exit', async () => { await pg.neha.reload(); await nav(pg.neha, 'My exit'); });
  await shot(hr, '02-list', async () => { await hr.reload(); await nav(hr, 'Exits'); });
  await shot(hr, '03-exit', async () => { await hr.getByRole('button', { name: 'Neha Gupta', exact: true }).click(); await expect(hr.locator('.clearance')).toBeVisible(); });
  await shot(hr, '04-settlement', async () => {
    const panel = hr.getByTestId('settlement');
    await panel.getByRole('button', { name: 'Work out the settlement' }).click(); await expect(panel).toContainText('Draft');
    await panel.scrollIntoViewIfNeeded();
  });
  await shot(hr, '05-letters', async () => { await hr.getByTestId('exit-letters').scrollIntoViewIfNeeded(); });
  await shot(hr, '06-start', async () => {
    await hr.getByRole('button', { name: '← All exits' }).click(); await hr.getByRole('button', { name: 'Start an exit' }).click();
    await hr.selectOption('#se-kind', 'termination');
  });
  await shot(hr, '07-setup', async () => { await hr.reload(); await nav(hr, 'People'); await tab(hr, 'Setup'); await tab(hr, 'Exits'); });
});
