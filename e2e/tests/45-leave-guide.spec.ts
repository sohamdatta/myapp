import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, currentYear, shot as save } from './helpers';
import { join } from './join';

// The screenshots in docs/user-guide/leave.md, taken in a demo Organization of their own (Northwind Traders),
// so they show tidy names. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.

const em = (n: string) => `${n}@traders.example`;
async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}

test('the leave guide, screen by screen', async ({ browser }) => {
  test.setTimeout(300_000);
  const admin = await newPage(browser); await admin.goto('/');
  await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
  await expect(admin.getByText('Platform console')).toBeVisible();
  const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Northwind Traders', slug: 'northwind-traders', adminEmail: em('owner') }));
  await admin.context().close();
  const owner = await join(browser, org.invitationToken, em('owner'));
  const people: [string, string, string[]][] = [['hr', 'Hema Rao', ['hr_admin']], ['priya', 'Priya Sharma', ['employee']], ['arjun', 'Arjun Mehta', ['employee']],
    ['kavya', 'Kavya Iyer', ['employee']], ['rahul', 'Rahul Verma', ['employee']]];
  const pg: Record<string, Page> = {};
  for (const [n, , roles] of people) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles }));
    pg[n] = await join(browser, inv.token, em(n));
  }
  const hr = pg.hr;
  const id = Object.fromEntries((await ok(api(hr, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
  for (const [n, name] of people) {
    await ok(api(owner, 'POST', '/org/people/employees', { legalName: name, joiningDate: isoDay(-200), designation: 'Associate', membershipId: id[em(n)] }));   // date-safe: joined long ago
  }
  const shift = await ok(api(hr, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
  const loc = await ok(api(hr, 'POST', '/org/attendance/locations', { name: 'Head office', timezone: 'Asia/Kolkata', stateCode: 'KA', active: true, punchCheck: 'none' }));
  await ok(api(hr, 'POST', '/org/attendance/assignments', { membershipIds: people.map(([n]) => id[em(n)]), workLocationId: loc.id, shiftId: shift.id,
    weeklyOffs: [0, 6], remoteDays: [], effectiveFrom: isoDay(-5) }));
  const type = (body: any) => ok(api(hr, 'POST', '/org/leave/types', { kind: 'regular', paid: true, accrual: 'yearly', yearlyQuota: 12, prorateJoiners: false,
    carryForwardCap: null, excessAtYearEnd: 'lapse', whenShort: 'refuse', countDaysOff: false, expiryDays: null, active: true, ...body }));
  const casual = await type({ name: 'Casual leave', yearlyQuota: 12, carryForwardCap: 0 });
  await type({ name: 'Sick leave', yearlyQuota: 10, carryForwardCap: 5 });
  await type({ name: 'Earned leave', accrual: 'monthly', yearlyQuota: 18, carryForwardCap: 30, whenShort: 'unpaid' });
  await type({ name: 'Loss of pay', paid: false, accrual: 'none', yearlyQuota: null });
  await type({ name: 'Comp-off', kind: 'comp_off', accrual: 'none', yearlyQuota: null, expiryDays: 30 });

  // A week's worth of requests: Arjun's approved, Kavya's waiting, Rahul's waiting on the same day as Arjun.
  const next = (n: number) => { let d = n; while ([0, 6].includes(new Date(`${isoDay(d)}T12:00:00Z`).getUTCDay())) d++; return isoDay(d); };
  const a = await ok(api(pg.arjun, 'POST', '/org/leave/me/requests', { leaveTypeId: casual.id, fromDate: next(1), toDate: next(1), reason: 'Sister’s wedding' }));
  await ok(api(hr, 'POST', `/org/leave/requests/${a.id}/approve`, { note: 'Congratulations' }));
  await ok(api(pg.kavya, 'POST', '/org/leave/me/requests', { leaveTypeId: casual.id, fromDate: next(2), toDate: next(2), reason: 'Moving house' }));
  await ok(api(pg.rahul, 'POST', '/org/leave/me/requests', { leaveTypeId: casual.id, fromDate: next(1), toDate: next(1), reason: 'Bank work in town' }));

  const shot = async (p: Page, name: string, fn: () => Promise<void>) => { await fn(); await save(p, `leave-${name}`); };
  await hr.setViewportSize({ width: 1280, height: 860 }); await pg.priya.setViewportSize({ width: 1280, height: 860 });
  await shot(hr, '01-types', async () => { await hr.reload(); await nav(hr, 'Leave'); await tab(hr, 'Setup'); });
  await shot(hr, '02-type-form', async () => {
    await hr.getByRole('button', { name: 'Add leave type' }).first().click();
    await hr.fill('#lt-name', 'Bereavement leave'); await hr.fill('#lt-quota', '3'); await hr.fill('#lt-cap', '0');
    await hr.locator('#lt-name').scrollIntoViewIfNeeded();
  });
  await shot(hr, '03-year-end', async () => {
    await hr.reload(); await nav(hr, 'Leave'); await tab(hr, 'Setup');
    await hr.fill('#ye-year', String(currentYear() - 1)); await hr.getByRole('button', { name: 'Preview' }).click();
    await hr.getByRole('heading', { name: 'Close a leave year' }).scrollIntoViewIfNeeded();
  });
  await shot(pg.priya, '04-my-leave', async () => { await pg.priya.reload(); await nav(pg.priya, 'My leave'); });
  await shot(pg.priya, '05-apply', async () => {
    await pg.priya.getByRole('button', { name: 'Apply for leave' }).click();
    await pg.priya.selectOption('#apply-type', { label: 'Casual leave' });
    await pg.priya.fill('#apply-from', next(3)); await pg.priya.fill('#apply-to', next(4));
    await pg.priya.fill('#apply-reason', 'Family trip to Mysuru');
    await expect(pg.priya.locator('form [role=status]')).toContainText('This takes');
  });
  await shot(hr, '06-requests', async () => { await hr.reload(); await nav(hr, 'Leave'); await tab(hr, 'Requests'); });
  await shot(hr, '07-who-is-away', async () => { await tab(hr, 'Who is away'); });
  await shot(hr, '08-balances', async () => { await tab(hr, 'Balances'); });
  await shot(hr, '09-adjust', async () => {
    await hr.locator('tr', { hasText: 'Priya Sharma' }).first().getByText('Adjust').click();
    await hr.selectOption('#adj-type', { label: 'Earned leave' }); await hr.fill('#adj-days', '4.5'); await hr.fill('#adj-reason', 'Opening balance from the old system');
  });
});
