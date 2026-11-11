import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, currentYear } from './helpers';
import { join, tokenOf } from './join';

// Every leave setting and screen, case by case (docs/testing/leave-ui-test-cases.xlsx; the TC ids are its rows),
// in an Organization of its own, Leave Labs. Weekly offs fall on the weekday of two days ago, so a day off lies
// in the past (for comp-off) and five days ahead (for counting a range).

const RUN = process.env.E2E_RUN || '';   // a suffix, to run the file again on a database that already has Leave Labs
const D = `leave${RUN}.test`;
const U = (n: string) => `${n}@${D}`;
const W = new Date(`${isoDay(-2)}T12:00:00Z`).getUTCDay();
const P: Record<string, Page> = {};
let owner: Page;
let ids: Record<string, string> = {};
const types: Record<string, any> = {};
const tile = (p: Page, name: string) => p.locator('.tiles > *', { hasText: name });
const err = (p: Page) => p.locator('form .error').first();

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function refused(r: Promise<{ status: number; body: any }>, status: number, code?: string) {
  const done = await r;
  expect(done.status, JSON.stringify(done.body)).toBe(status);
  if (code) expect(done.body.code).toBe(code);
  return done.body;
}
async function setup(p: Page) { await p.reload(); await nav(p, 'Leave'); await tab(p, 'Setup'); }
async function openForm(p: Page) { await setup(p); await p.getByRole('button', { name: 'Add leave type' }).first().click(); }
async function saveType(p: Page) { await p.getByRole('button', { name: 'Add leave type', exact: true }).last().click(); }
const typeRow = (p: Page, name: string) => p.locator('tr', { has: p.locator('td', { hasText: new RegExp(`^${name}`) }) }).first();
const typeBody = (body: any) => ({ kind: 'regular', paid: true, accrual: 'yearly', yearlyQuota: 12, prorateJoiners: false,
    carryForwardCap: null, excessAtYearEnd: 'lapse', whenShort: 'refuse', countDaysOff: false, expiryDays: null, active: true, ...body });
async function mkType(body: any) {
  return ok(api(owner, 'POST', '/org/leave/types', typeBody(body)));
}
const apply = (who: string, type: string, from: string, to = from, extra: any = {}) =>
  api(P[who], 'POST', '/org/leave/me/requests', { leaveTypeId: types[type].id, fromDate: from, toDate: to, reason: 'Personal work at home', ...extra });
async function balance(who: string, type: string) {
  const b = (await ok(api(P[who], 'GET', '/org/leave/me/balances'))).find((x: any) => x.name === type);
  return Number(b.available);
}
async function myLeave(p: Page) { await p.reload(); await nav(p, 'My leave'); }

test.describe.serial('leave test cases', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: `Leave Labs${RUN}`, slug: `leave-labs${RUN}`, adminEmail: U('owner') }));
    await admin.context().close();
    owner = await join(browser, org.invitationToken, U('owner'));
    const roles: Record<string, string[]> = { hr: ['hr_admin', 'employee'], hr2: ['hr_admin'], aud: ['auditor'], mgr: ['employee', 'manager'],
      e1: ['employee'], e2: ['employee'], e3: ['employee'], e4: ['employee'], e5: ['employee'], e6: ['employee'], free: ['employee'] };
    for (const [n, r] of Object.entries(roles)) {
      const inv = await ok(api(owner, 'POST', '/org/invitations', { email: U(n), roles: r }));
      P[n] = await join(browser, inv.token, U(n));
    }
    const list = await ok(api(owner, 'GET', '/org/attendance/assignments'));
    ids = Object.fromEntries(list.map((p: any) => [p.email, p.membershipId]));
    const shift = await ok(api(owner, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
    const loc = await ok(api(owner, 'POST', '/org/attendance/locations', { name: 'Head office', timezone: 'Asia/Kolkata', stateCode: 'KA', active: true, punchCheck: 'none' }));
    const everyone = ['owner', 'hr', 'hr2', 'mgr', 'e1', 'e2', 'e3', 'e4', 'e5', 'e6'];
    await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: everyone.map((n) => ids[U(n)]), workLocationId: loc.id, shiftId: shift.id,
      weeklyOffs: [W], remoteDays: [], effectiveFrom: isoDay(-5) }));
    const m = await ok(api(owner, 'POST', '/org/people/employees', { legalName: 'Mira Manager', joiningDate: isoDay(-300), designation: 'Manager', membershipId: ids[U('mgr')] }));   // date-safe: joined long ago
    await ok(api(owner, 'POST', '/org/people/employees', { legalName: 'Esha One', joiningDate: isoDay(-200), designation: 'Analyst', managerId: m.id, membershipId: ids[U('e1')] }));   // date-safe: joined long ago
    types.casual = await mkType({ name: 'Casual leave', yearlyQuota: 12, carryForwardCap: 0 });
    types.earned = await mkType({ name: 'Earned leave', yearlyQuota: 3, whenShort: 'unpaid', carryForwardCap: 30 });
    types.lop = await mkType({ name: 'Loss of pay', paid: false, accrual: 'none', yearlyQuota: null });
    types.comp = await mkType({ name: 'Comp-off', kind: 'comp_off', accrual: 'none', yearlyQuota: null, expiryDays: 30 });
    types.tight = await mkType({ name: 'Short leave', yearlyQuota: 2 });
  });

  // ---------- leave types ----------
  test('TC-L001 TC-L013 add a paid type; cap shown in its rules', async () => {
    await openForm(owner);
    await owner.fill('#lt-name', 'Sick leave'); await owner.fill('#lt-quota', '12'); await owner.fill('#lt-cap', '5');
    await saveType(owner);
    await expect(typeRow(owner, 'Sick leave')).toContainText('12 days at the start of the year');
    await expect(typeRow(owner, 'Sick leave')).toContainText('carries forward up to 5, the rest lapses');
  });
  test('TC-L002 TC-L003 name required and at most 60 characters', async () => {
    await openForm(owner); await owner.fill('#lt-quota', '5'); await saveType(owner);
    await expect(err(owner)).toContainText('Give the leave type a name of up to 60 characters');
    await owner.fill('#lt-name', 'x'.repeat(61)); await saveType(owner);
    await expect(err(owner)).toContainText('Give the leave type a name of up to 60 characters');
  });
  test('TC-L004 duplicate name refused', async () => {
    await openForm(owner); await owner.fill('#lt-name', 'Casual leave'); await owner.fill('#lt-quota', '5'); await saveType(owner);
    await expect(err(owner)).toBeVisible();
  });
  test('TC-L005 unpaid type hides the balance fields', async () => {
    await openForm(owner); await owner.selectOption('#lt-kind', 'unpaid');
    for (const id of ['#lt-accrual', '#lt-quota', '#lt-cap', '#lt-short', '#lt-excess']) await expect(owner.locator(id)).toHaveCount(0);
    await owner.fill('#lt-name', 'Unpaid sabbatical'); await saveType(owner);
    await expect(typeRow(owner, 'Unpaid sabbatical')).toContainText('Unpaid · no balance');
  });
  test('TC-L006 TC-L017 TC-L018 comp-off shows only expiry, 1 to 366', async () => {
    await openForm(owner); await owner.selectOption('#lt-kind', 'comp_off'); await owner.fill('#lt-name', 'Holiday comp-off');
    await expect(owner.locator('#lt-quota')).toHaveCount(0);
    await owner.fill('#lt-expiry', '0'); await saveType(owner); await expect(err(owner)).toContainText('Comp-off must expire in 1 to 366 days');
    await owner.fill('#lt-expiry', '367'); await saveType(owner); await expect(err(owner)).toContainText('Comp-off must expire in 1 to 366 days');
    await owner.fill('#lt-expiry', '45'); await saveType(owner);
    await expect(typeRow(owner, 'Holiday comp-off')).toContainText('Earned by working a day off · expires after 45 days');
  });
  test('TC-L007 TC-L022 TC-L023 kind fixed after saving; change prefills and saves; cancel', async () => {
    await setup(owner);
    await typeRow(owner, 'Sick leave').getByRole('button', { name: 'Change' }).click();
    await expect(owner.locator('#lt-kind')).toBeDisabled();
    await expect(owner.locator('#lt-name')).toHaveValue('Sick leave');
    await owner.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(owner.locator('#lt-name')).toHaveCount(0);
    await typeRow(owner, 'Sick leave').getByRole('button', { name: 'Change' }).click();
    await owner.fill('#lt-quota', '15'); await owner.getByRole('button', { name: 'Save changes' }).click();
    await expect(typeRow(owner, 'Sick leave')).toContainText('15 days at the start of the year');
  });
  test('TC-L008 TC-L009 TC-L019 monthly credit; only when HR adds; prorate box', async () => {
    await openForm(owner); await owner.fill('#lt-name', 'Monthly leave'); await owner.selectOption('#lt-accrual', 'monthly'); await owner.fill('#lt-quota', '12');
    await saveType(owner); await expect(typeRow(owner, 'Monthly leave')).toContainText('12 days a year, credited monthly');
    await openForm(owner); await owner.fill('#lt-name', 'Special leave'); await owner.selectOption('#lt-accrual', 'none');
    await expect(owner.locator('#lt-quota')).toHaveCount(0);
    await expect(owner.getByLabel('Someone who joins mid-year gets a share for the months left')).toHaveCount(0);
    await saveType(owner); await expect(typeRow(owner, 'Special leave')).toContainText('Added by HR');
  });
  test('TC-L010 TC-L011 TC-L012 quota required, at most 366, whole or half days', async () => {
    await openForm(owner); await owner.fill('#lt-name', 'Quota check'); await saveType(owner);
    await expect(err(owner)).toContainText('Give the number of days a year, or set accrual to none');
    await owner.fill('#lt-quota', '367'); await saveType(owner); await expect(err(owner)).toContainText('The yearly quota must be between 0 and 366 days');
    await owner.fill('#lt-quota', '10.25'); await saveType(owner); await expect(err(owner)).toContainText('The yearly quota must be between 0 and 366 days');
  });
  test('TC-L014 TC-L015 negative cap refused; encash wording', async () => {
    await openForm(owner); await owner.fill('#lt-name', 'Encash leave'); await owner.fill('#lt-quota', '6'); await owner.fill('#lt-cap', '-1'); await saveType(owner);
    await expect(err(owner)).toContainText('The carry-forward cap must be zero or more');
    await owner.fill('#lt-cap', '0'); await owner.selectOption('#lt-excess', 'encash'); await saveType(owner);
    await expect(typeRow(owner, 'Encash leave')).toContainText('nothing carries forward; what is left is encashed at year end');
  });
  test('TC-L016 TC-L020 shortfall unpaid and days-off flags shown', async () => {
    await openForm(owner); await owner.fill('#lt-name', 'Flex leave'); await owner.fill('#lt-quota', '4'); await owner.selectOption('#lt-short', 'unpaid');
    await owner.getByLabel('Weekly offs and holidays inside a leave count as leave').check(); await saveType(owner);
    await expect(typeRow(owner, 'Flex leave')).toContainText('A shortfall is taken as unpaid');
    await expect(typeRow(owner, 'Flex leave')).toContainText('Days off inside a leave count');
  });
  test('TC-L021 inactive type is not offered and is refused', async () => {
    const t = await mkType({ name: 'Old leave', yearlyQuota: 5 });
    await ok(api(owner, 'PUT', `/org/leave/types/${t.id}`, typeBody({ name: 'Old leave', yearlyQuota: 5, active: false })));
    await myLeave(P.e2); await P.e2.getByRole('button', { name: 'Apply for leave' }).click();
    await expect(P.e2.locator('#apply-type option', { hasText: 'Old leave' })).toHaveCount(0);
    await refused(api(P.e2, 'POST', '/org/leave/me/requests', { leaveTypeId: t.id, fromDate: isoDay(1), toDate: isoDay(1), reason: 'Using an old type' }), 409, 'leave.type_inactive');
  });

  // ---------- leave year and year end ----------
  test('TC-L024 TC-L025 leave year switches to April and back; other months refused', async () => {
    await setup(owner);
    await owner.selectOption('#leave-year-start', '4');
    await expect(owner.locator('#leave-year-start')).toBeEnabled();
    await setup(owner); await expect(owner.locator('#leave-year-start')).toHaveValue('4');
    await ok(api(owner, 'PUT', '/org/leave/year', { startMonth: 1 }));
    await refused(api(owner, 'PUT', '/org/leave/year', { startMonth: 7 }), 400);
  });
  test('TC-L027 TC-L028 a year with nothing above the cap; this year cannot close', async () => {
    await setup(owner);
    await owner.fill('#ye-year', String(currentYear())); await owner.getByRole('button', { name: 'Preview' }).click();
    await expect(owner.getByText('This leave year has not ended yet')).toBeVisible();
    await expect(owner.getByRole('button', { name: /Close this leave year/ })).toHaveCount(0);
    await owner.fill('#ye-year', String(currentYear() - 2)); await owner.getByRole('button', { name: 'Preview' }).click();
    await expect(owner.getByText('Nobody holds more than the carry-forward limit, so closing the year changes no balance.')).toBeVisible();
  });
  test('TC-L026 TC-L031 TC-L029 TC-L030 preview, pending blocks, then close', async () => {
    const last = currentYear() - 1;
    await ok(api(owner, 'POST', `/org/leave/users/${ids[U('e3')]}/adjustments`, { leaveTypeId: types.casual.id, days: 4, onDate: `${last}-12-10`, reason: 'Balance from the old system' }));
    await ok(api(owner, 'POST', `/org/leave/users/${ids[U('hr2')]}/adjustments`, { leaveTypeId: types.earned.id, days: 2, onDate: `${last}-12-10`, reason: 'Balance from the old system' }));
    await setup(owner);
    await owner.fill('#ye-year', String(last)); await owner.getByRole('button', { name: 'Preview' }).click();
    await expect(owner.locator('.tile', { hasText: 'Days that lapse' })).toContainText('4');
    await expect(owner.locator('tr', { hasText: U('e3') }).first()).toContainText('Casual leave');
    await owner.getByRole('button', { name: /Close this leave year/ }).click();
    await owner.getByRole('button', { name: 'Yes, close the year' }).click();
    await expect(owner.getByText('This leave year has been closed.')).toBeVisible();
    await refused(api(owner, 'POST', '/org/leave/year-end', { year: last }), 409, 'leave.year_closed');
  });
  test('TC-L032 TC-L033 closed year locks the leave year and its dates', async () => {
    await refused(api(owner, 'PUT', '/org/leave/year', { startMonth: 4 }), 409, 'leave.year_closed');
    const r = await api(owner, 'POST', `/org/leave/users/${ids[U('e3')]}/adjustments`, { leaveTypeId: types.casual.id, days: 1, onDate: `${currentYear() - 1}-12-11`, reason: 'Late correction' });
    expect(r.status, JSON.stringify(r.body)).toBe(409); expect(r.body.code).toBe('leave.year_closed');
  });

  // ---------- window settings ----------
  test('TC-L034 TC-L035 TC-L036 the apply window and its settings', async () => {
    const put = (key: string, value: number) => api(owner, 'PUT', `/org/settings/${key}`, { scope: 'organization', value, reason: 'Testing the window' });
    expect((await put('leave.apply.back_days', 91)).status).toBeGreaterThanOrEqual(400);
    expect((await put('leave.apply.ahead_days', 29)).status).toBeGreaterThanOrEqual(400);
    const far = await apply('e2', 'lop', isoDay(-32));   // date-safe: beyond the 31-day window, whatever the month
    expect(far.status).toBe(400); expect(far.body.code).toBe('leave.window'); expect(far.body.message).toContain('31');
  });

  // ---------- my leave ----------
  test('TC-L037 TC-L039 tiles and how the balance was reached', async () => {
    await myLeave(P.e1);
    await expect(tile(P.e1, 'Casual leave')).toContainText('12');
    await expect(tile(P.e1, 'Loss of pay')).toContainText('Unpaid, no balance');
    await tile(P.e1, 'Casual leave').click();
    await expect(P.e1.getByRole('heading', { name: 'How my balance was reached' })).toBeVisible();
    await expect(P.e1.getByText('Credited').first()).toBeVisible();
  });
  test('TC-L042 TC-L045 TC-L041 TC-L040 preview, reason, then send', async () => {
    const p = P.e2; await myLeave(p); await p.getByRole('button', { name: 'Apply for leave' }).click();
    await expect(p.getByRole('button', { name: 'Send for approval' })).toBeDisabled();
    await p.selectOption('#apply-type', { label: 'Casual leave' });
    await p.fill('#apply-from', isoDay(1)); await p.fill('#apply-to', isoDay(3));
    await expect(p.locator('form [role=status]')).toContainText('This takes 3 days of the 12 days you have available.');
    await p.fill('#apply-reason', 'ok'); await p.getByRole('button', { name: 'Send for approval' }).click();
    await expect(err(p)).toContainText('Give a reason of at least 5 characters');
    await p.fill('#apply-reason', 'Family wedding out of town'); await p.getByRole('button', { name: 'Send for approval' }).click();
    await expect(p.getByText('Leave request sent for approval')).toBeVisible();
    await expect(p.locator('tr', { hasText: 'Family wedding' })).toContainText('pending');
  });
  test('TC-L043 TC-L044 half day on one date only', async () => {
    const half = await ok(apply('e4', 'casual', isoDay(1), isoDay(1), { half: 'first' }));
    expect(Number(half.days)).toBe(0.5);
    await refused(apply('e4', 'casual', isoDay(2), isoDay(3), { half: 'first' }), 400, 'request.invalid');
  });
  test('TC-L046 TC-L047 weekly off inside a range does not count; only days off refused', async () => {
    const p = P.e5; await myLeave(p); await p.getByRole('button', { name: 'Apply for leave' }).click();
    await p.selectOption('#apply-type', { label: 'Casual leave' });
    await p.fill('#apply-from', isoDay(4)); await p.fill('#apply-to', isoDay(6));   // date-safe: a range round the weekly off five days ahead; the month does not matter
    await expect(p.locator('form [role=status]')).toContainText('This takes 2 days');
    const off = await apply('e5', 'casual', isoDay(5));
    expect(off.status).toBe(400); expect(off.body.code).toBe('leave.no_days');
  });
  test('TC-L048 not enough balance refused', async () => {
    const r = await apply('e3', 'tight', isoDay(1), isoDay(4));
    expect(r.status).toBe(409); expect(r.body.code).toBe('leave.insufficient_balance');
  });
  test('TC-L049 TC-L050 shortfall becomes unpaid; unpaid type is all unpaid', async () => {
    const r = await ok(apply('e3', 'earned', isoDay(1), isoDay(4)));   // 4 working days on a balance of 3
    expect(Number(r.unpaidDays)).toBeGreaterThan(0);
    const lop = await ok(apply('e6', 'lop', isoDay(2)));
    expect(Number(lop.unpaidDays)).toBe(Number(lop.days));
    await myLeave(P.e6); await expect(P.e6.locator('tr', { hasText: 'Loss of pay' }).first()).toBeVisible();
  });
  test('TC-L051 TC-L052 TC-L053 overlap, reversed dates and not assigned refused', async () => {
    await refused(apply('e2', 'lop', isoDay(2)), 409, 'leave.overlap');
    await refused(apply('e6', 'casual', isoDay(4), isoDay(3)), 400, 'request.invalid');
    await refused(apply('free', 'lop', isoDay(1)), 409, 'attendance.not_assigned');
  });
  test('TC-L054 TC-L058 cancel a pending request; empty list', async () => {
    const p = P.e6; await myLeave(p);
    const row = p.locator('tbody tr', { hasText: 'Personal work at home' }).filter({ hasText: 'Loss of pay' });
    await row.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(p.getByText('Leave request cancelled')).toBeVisible();
    await expect(row).toContainText('cancelled');
    await myLeave(P.mgr); await expect(P.mgr.getByText('You have not applied for any leave.')).toBeVisible();
  });

  // ---------- requests ----------
  test('TC-L060 approve with a note; balance taken', async () => {
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Requests');
    const row = owner.locator('tbody tr', { hasText: U('e2') }).first();
    await owner.getByLabel(`Note for ${U('e2')}`).fill('Have a good trip');
    await row.getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(owner.getByText(`Leave approved for ${U('e2')}`)).toBeVisible();
    expect(await balance('e2', 'Casual leave')).toBe(9);
  });
  test('TC-L061 TC-L057 reject asks to confirm; the note reaches the employee', async () => {
    await ok(apply('e5', 'lop', isoDay(1)));
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Requests');
    await owner.getByLabel(`Note for ${U('e5')}`).fill('Busy week, please move it');
    await owner.locator('tbody tr', { hasText: U('e5') }).first().getByRole('button', { name: 'Reject' }).click();
    await owner.getByRole('button', { name: 'Reject leave' }).click();
    await expect(owner.getByText(`Leave rejected for ${U('e5')}`)).toBeVisible();
    await myLeave(P.e5); await expect(P.e5.getByText('Busy week, please move it')).toBeVisible();
  });
  test('TC-L062 own request cannot be decided', async () => {
    const mine = await ok(apply('hr', 'lop', isoDay(3)));
    await P.hr.reload(); await nav(P.hr, 'Leave'); await tab(P.hr, 'Requests');
    await expect(P.hr.locator('tbody tr', { hasText: U('hr') }).first()).toContainText('Your own request: someone else must decide it');
    await refused(api(P.hr, 'POST', `/org/leave/requests/${mine.id}/approve`, { note: '' }), 409, 'leave.own_request');
  });
  test('TC-L059 filters show each status', async () => {
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Requests');
    for (const [chip, text] of [['Approved', 'approved'], ['Rejected', 'rejected'], ['Cancelled', 'cancelled']]) {
      await owner.getByRole('button', { name: chip, exact: true }).click();
      await expect(owner.locator('tbody tr').first()).toContainText(text);
    }
  });
  test('TC-L063 TC-L064 not-enough badge and who else is away', async () => {
    // e4 holds half a day of casual leave tomorrow; e1 asks for the same day after HR takes most of e1's balance away.
    const pend = await ok(apply('e1', 'earned', isoDay(1), isoDay(2)));
    await ok(api(owner, 'POST', `/org/leave/users/${ids[U('e1')]}/adjustments`, { leaveTypeId: types.earned.id, days: -2.5, onDate: isoDay(0), reason: 'Correcting an old error' }));
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Requests');
    const row = owner.locator('tbody tr', { hasText: U('e1') }).first();
    await expect(row).toContainText('not enough');
    await expect(row).toContainText('Also away then');
    expect(pend.status).toBe('pending');
  });
  test('TC-L066 TC-L067 manager sees and decides only the team', async () => {
    await P.mgr.reload(); await nav(P.mgr, 'Leave');
    await expect(P.mgr.locator('tbody tr', { hasText: U('e1') }).first()).toBeVisible();
    await expect(P.mgr.locator('tbody tr', { hasText: U('hr') })).toHaveCount(0);
    await P.mgr.locator('tbody tr', { hasText: U('e1') }).first().getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(P.mgr.getByText('Leave approved for Esha One')).toBeVisible();   // the name from the employee record
  });
  test('TC-L055 cancel approved future leave returns the days', async () => {
    const p = P.e2; await myLeave(p);
    const row = p.locator('tr', { hasText: 'Family wedding' });
    await row.getByRole('button', { name: 'Cancel' }).click();
    await expect(row).toContainText('cancelled');
    expect(await balance('e2', 'Casual leave')).toBe(12);
  });
  test('TC-L056 TC-L065 started leave: employee refused, HR cancels', async () => {
    const started = await ok(api(owner, 'POST', `/org/leave/users/${ids[U('e4')]}/requests`, { leaveTypeId: types.lop.id, fromDate: isoDay(-1), toDate: isoDay(-1), reason: 'Was unwell yesterday' }));
    await refused(api(P.e4, 'POST', `/org/leave/me/requests/${started.id}/cancel`), 409, 'leave.started');
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Requests'); await owner.getByRole('button', { name: 'Approved', exact: true }).click();
    await owner.locator('tbody tr', { hasText: 'Was unwell yesterday' }).getByRole('button', { name: 'Cancel this leave' }).click();
    await owner.getByRole('button', { name: 'Cancel leave', exact: true }).click();
    await expect(owner.getByText(`Leave cancelled for ${U('e4')}`)).toBeVisible();
  });

  // ---------- who is away ----------
  test('TC-L068 TC-L069 TC-L070 who is away', async () => {
    await ok(api(owner, 'POST', `/org/leave/users/${ids[U('e6')]}/requests`, { leaveTypeId: types.casual.id, fromDate: isoDay(0), toDate: isoDay(0), reason: 'Doctor visit today' }));
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Who is away');
    await expect(owner.locator('tbody tr', { hasText: U('e6') }).first()).toContainText('Casual leave');
    await owner.fill('#away-month', `${currentYear() - 3}-02`);
    await expect(owner.getByText(/Nobody is away in/)).toBeVisible();
    await refused(api(owner, 'GET', `/org/leave/calendar?from=${currentYear()}-01-01&to=${currentYear()}-05-31`), 400);
  });

  // ---------- balances ----------
  test('TC-L071 TC-L072 TC-L073 table, search, pending hint and ledger', async () => {
    await ok(apply('e5', 'casual', isoDay(2)));
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Balances');
    await owner.fill('#bal-search', U('e5'));
    await expect(owner.locator('tbody tr', { hasText: '@' })).toHaveCount(1);
    await expect(owner.locator('tbody tr', { hasText: U('e5') })).toContainText('pending');
    await owner.locator('tbody tr', { hasText: U('e5') }).getByText('History').click();
    await expect(owner.locator('.detail-row').getByText('Credited').first()).toBeVisible();
  });
  test('TC-L074 TC-L075 adjust: opening balance and zero refused', async () => {
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Balances');
    const row = owner.locator('tr', { hasText: U('e6') }).first();
    await row.getByText('Adjust').click();
    await owner.selectOption('#adj-type', { label: 'Earned leave' }); await owner.fill('#adj-days', '0'); await owner.fill('#adj-reason', 'Opening balance');
    await owner.getByRole('button', { name: 'Save adjustment' }).click();
    await expect(err(owner)).toContainText('Give the number of days to add, or a negative number to take away');
    await owner.fill('#adj-days', '3.5'); await owner.getByRole('button', { name: 'Save adjustment' }).click();
    await expect(owner.locator('.detail-row')).toHaveCount(0);
    expect(await balance('e6', 'Earned leave')).toBe(6.5);
  });
  test('TC-L076 TC-L077 unpaid type and own balance cannot be adjusted', async () => {
    await refused(api(owner, 'POST', `/org/leave/users/${ids[U('e6')]}/adjustments`, { leaveTypeId: types.lop.id, days: 1, onDate: isoDay(0), reason: 'Not possible' }), 400);
    await P.hr.reload(); await nav(P.hr, 'Leave'); await tab(P.hr, 'Balances');
    await expect(P.hr.locator('tr', { hasText: U('hr') }).first()).toContainText('Your own: another HR changes it');
    await refused(api(P.hr, 'POST', `/org/leave/users/${ids[U('hr')]}/adjustments`, { leaveTypeId: types.casual.id, days: 1, onDate: isoDay(0), reason: 'Own change' }), 409, 'leave.own_request');
  });
  test('TC-L078 TC-L079 TC-L080 record leave on behalf', async () => {
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Balances');
    const row = owner.locator('tr', { hasText: U('e2') }).first();
    await row.getByText('Record leave').click();
    await owner.selectOption('#behalf-type', { label: 'Loss of pay' });
    await owner.fill('#behalf-from', isoDay(4)); await owner.fill('#behalf-to', isoDay(4));
    await owner.fill('#behalf-reason', 'Told HR by phone');
    await owner.getByRole('button', { name: `Record leave for ${U('e2')}` }).click();
    await expect(owner.locator('.detail-row')).toHaveCount(0);
    await refused(api(P.hr, 'POST', `/org/leave/users/${ids[U('hr')]}/requests`, { leaveTypeId: types.lop.id, fromDate: isoDay(4), toDate: isoDay(4), reason: 'For myself' }), 409, 'leave.own_request');
    await refused(api(owner, 'POST', `/org/leave/users/${ids[U('e3')]}/requests`, { leaveTypeId: types.lop.id, fromDate: isoDay(800), toDate: isoDay(800), reason: 'Far ahead' }), 400, 'leave.window');   // date-safe: over a year ahead
  });
  test('TC-L081 TC-L082 TC-L083 comp-off for a worked weekly off', async () => {
    const off = isoDay(-2);
    for (const [dir, t] of [['in', '09:30'], ['out', '18:30']]) {
      await ok(api(owner, 'POST', `/org/attendance/users/${ids[U('e4')]}/punches`, { at: `${off}T${t}`, direction: dir, reason: 'Came in on the weekly off' }));
    }
    await ok(api(owner, 'GET', `/org/attendance/days?date=${off}`));   // works the day out
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Balances');
    const row = owner.locator('tr', { hasText: U('e4') }).filter({ hasText: 'A weekly off' });
    await row.getByRole('button', { name: 'Grant' }).click();
    await expect(row).toHaveCount(0);
    expect(await balance('e4', 'Comp-off')).toBe(1);
    await refused(api(owner, 'POST', `/org/leave/users/${ids[U('e4')]}/comp-off`, { workDate: off, days: 1 }), 409);
    await refused(api(owner, 'POST', `/org/leave/users/${ids[U('e5')]}/comp-off`, { workDate: off, days: 1 }), 400);
  });

  // ---------- access ----------
  test('TC-L084 employee sees My leave but not Leave', async () => {
    await P.e2.reload();
    await expect(P.e2.getByRole('link', { name: 'My leave' })).toBeVisible();
    await expect(P.e2.getByRole('link', { name: 'Leave', exact: true })).toHaveCount(0);
    expect((await api(P.e2, 'GET', '/org/leave/requests')).status).toBe(403);
  });
  test('TC-L085 auditor reads without changing', async () => {
    const a = P.aud; await a.reload(); await nav(a, 'Leave');
    for (const t of ['Who is away', 'Balances', 'Setup']) {
      await tab(a, t);
      for (const b of ['Add leave type', 'Adjust', 'Record leave', 'Approve', 'Grant']) await expect(a.getByRole('button', { name: b, exact: true })).toHaveCount(0);
    }
  });
  test('TC-L086 module off hides leave', async ({ browser }) => {
    const admin = await newPage(browser); await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    await admin.fill('#org-name', `Payroll Only Leave Co${RUN}`); await admin.fill('#org-admin', U('payonly'));
    await admin.uncheck('#org-mod-attendance_leave'); await admin.click('text=Create and invite');
    await expect(admin.getByText(`Invitation for ${U('payonly')}.`)).toBeVisible();
    const p = await join(browser, tokenOf(await admin.locator('.copy-row input').inputValue()), U('payonly'));
    await expect(p.getByRole('link', { name: 'Leave', exact: true })).toHaveCount(0);
    // Leave types stay readable (exit settings name them); everything else under /org/leave is refused.
    const r = await api(p, 'GET', '/org/leave/me/balances'); expect(r.status).toBe(403); expect(r.body.code).toBe('module.off');
    await admin.context().close(); await p.context().close();
  });

  // ---------- day result ----------
  test('TC-L087 TC-L089 approved leave shows on the board; no work from home that day', async () => {
    await owner.reload(); await nav(owner, 'Attendance'); await tab(owner, 'Daily');
    await expect(owner.locator('tr', { hasText: U('e6') }).first()).toContainText(/On leave/i);
    await ok(api(owner, 'PUT', '/org/attendance/settings', { leaveYearStartMonth: 1, wfhEnabled: true, wfhRequiresPunch: false, wfhLimitPerWeek: null, wfhLimitPerMonth: null }));
    const r = await api(P.e6, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Home on my leave day' });
    expect(r.status).toBe(409); expect(r.body.code).toBe('attendance.leave_on_date');
  });
  test('TC-L088 cancelled leave turns the day back', async () => {
    await owner.reload(); await nav(owner, 'Leave'); await tab(owner, 'Requests'); await owner.getByRole('button', { name: 'Approved', exact: true }).click();
    await owner.locator('tbody tr', { hasText: 'Doctor visit today' }).getByRole('button', { name: 'Cancel this leave' }).click();
    await owner.getByRole('button', { name: 'Cancel leave', exact: true }).click();
    await expect(owner.getByText(`Leave cancelled for ${U('e6')}`)).toBeVisible();
    await nav(owner, 'Attendance'); await tab(owner, 'Daily');
    await expect(owner.locator('tr', { hasText: U('e6') }).first()).not.toContainText(/On leave/i);
  });

  // ---------- audit ----------
  test('TC-L090 leave actions in the audit log', async () => {
    await owner.reload(); await nav(owner, 'Audit log');
    for (const a of ['leave.type_created', 'leave.type_changed', 'leave.approved', 'leave.rejected', 'leave.cancelled', 'leave.adjusted', 'leave.applied_on_behalf', 'leave.comp_off_granted', 'leave.year_closed']) {
      await expect(owner.locator('code', { hasText: a }).first(), a).toBeVisible();
    }
  });
});
