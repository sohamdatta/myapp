import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay } from './helpers';
import { join } from './join';

// Every exit setting and screen, case by case (docs/testing/exit-ui-test-cases.xlsx; the TC ids are its rows),
// in an Organization of its own, Exit Labs.

const RUN = process.env.E2E_RUN || '';   // a suffix, to run the file again on a database that already has Exit Labs
const D = `exit${RUN}.test`;
const U = (n: string) => `${n}@${D}`;
const P: Record<string, Page> = {};
let hr: Page;
let owner: Page;
let ids: Record<string, string> = {};
const emp: Record<string, string> = {};   // employee ids
const NAMES: Record<string, string> = { mgr: 'Mohan Das', e1: 'Esha Kapoor', e2: 'Farhan Ali', e3: 'Gita Rao', e4: 'Hari Nair', e5: 'Isha Jain', e6: 'Jai Shah', e7: 'Kiran Bose', hr: 'Hema Rao' };
const err = (p: Page) => p.locator('form .error').first();

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function refused(r: Promise<{ status: number; body: any }>, status: number, text?: string) {
  const done = await r;
  expect(done.status, JSON.stringify(done.body)).toBe(status);
  if (text) expect(done.body.message).toContain(text);
  return done.body;
}
async function exits(p: Page) { await p.reload(); await nav(p, 'Exits'); }
async function openExit(p: Page, who: string) { await exits(p); await p.getByRole('button', { name: NAMES[who], exact: true }).click(); await expect(p.getByRole('heading', { name: new RegExp(NAMES[who]) }).first()).toBeVisible(); }
async function exitId(who: string) { return (await ok(api(hr, 'GET', '/org/people/exits?status=all'))).find((x: any) => x.employmentId === emp[who] || x.legalName === NAMES[who])?.id; }
async function settings() { await hr.reload(); await nav(hr, 'People'); await tab(hr, 'Setup'); await tab(hr, 'Exits'); }

test.describe.serial('exit test cases', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: `Exit Labs${RUN}`, slug: `exit-labs${RUN}`, adminEmail: U('owner') }));
    await admin.context().close();
    owner = await join(browser, org.invitationToken, U('owner'));
    const roles: Record<string, string[]> = { hr: ['hr_admin', 'employee'], aud: ['auditor'], mgr: ['employee', 'manager'],
      e1: ['employee'], e2: ['employee'], e3: ['employee'], e4: ['employee'], e5: ['employee'], e6: ['employee'], e7: ['employee'] };
    for (const [n, r] of Object.entries(roles)) {
      const inv = await ok(api(owner, 'POST', '/org/invitations', { email: U(n), roles: r }));
      P[n] = await join(browser, inv.token, U(n));
    }
    hr = P.hr;
    ids = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
    const shift = await ok(api(owner, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
    const loc = await ok(api(owner, 'POST', '/org/attendance/locations', { name: 'Head office', timezone: 'Asia/Kolkata', stateCode: 'KA', active: true, punchCheck: 'none' }));
    await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: Object.values(ids), workLocationId: loc.id, shiftId: shift.id, weeklyOffs: [0], remoteDays: [], effectiveFrom: isoDay(-5) }));
    const add = (n: string, body: any) => ok(api(owner, 'POST', '/org/people/employees', { legalName: NAMES[n], joiningDate: isoDay(-400), designation: 'Associate', membershipId: ids[U(n)], ...body }));   // date-safe: joined long ago
    emp.mgr = (await add('mgr', { designation: 'Team lead' })).id;
    emp.hr = (await add('hr', { designation: 'HR manager' })).id;
    for (const n of ['e1', 'e2', 'e3', 'e4', 'e5', 'e6', 'e7']) emp[n] = (await add(n, { managerId: emp.mgr })).id;
    const type = (body: any) => ok(api(owner, 'POST', '/org/leave/types', { kind: 'regular', paid: true, accrual: 'yearly', yearlyQuota: 12, prorateJoiners: false,
      carryForwardCap: null, excessAtYearEnd: 'lapse', whenShort: 'refuse', countDaysOff: false, expiryDays: null, active: true, ...body }));
    await type({ name: 'Earned leave', yearlyQuota: 18 });
    await type({ name: 'Casual leave', yearlyQuota: 12 });
  });

  // ---------- settings ----------
  test('TC-X002 notice over 180 days refused', async () => {
    await settings(); await hr.fill('#ex-notice-permanent', '181'); await hr.getByRole('button', { name: 'Save exit settings' }).click();
    // The field allows 0 to 180, so the browser stops the form before it is sent.
    expect(await hr.locator('#ex-notice-permanent').evaluate((e: HTMLInputElement) => e.validity.rangeOverflow)).toBe(true);
    await expect(hr.getByText('Saved.')).toHaveCount(0);
  });
  test('TC-X001 TC-X003 TC-X006 notice, default shortfall and other numbers saved', async () => {
    await settings();
    await hr.fill('#ex-notice-permanent', '45'); await hr.selectOption('#ex-shortfall', 'waived');
    await hr.fill('#ex-retire', '60'); await hr.fill('#ex-abscond', '3'); await hr.fill('#ex-response', '5'); await hr.fill('#ex-access', '12');
    await hr.getByLabel('Any leave can be taken during the notice period').uncheck();
    await hr.getByRole('group', { name: 'Leave allowed during notice' }).getByLabel('Earned leave').check();
    await hr.getByLabel('Encash Earned leave').check();
    await hr.getByRole('button', { name: 'Save exit settings' }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
  });
  test('TC-X007 add an interview question with choices', async () => {
    await settings();
    await hr.getByRole('button', { name: 'Add a question' }).click();
    const i = (await hr.locator('[id^="iq-text-"]').count()) - 1;
    await hr.fill(`#iq-text-${i}`, 'Would you recommend us to a friend?'); await hr.selectOption(`#iq-type-${i}`, 'choice');
    await hr.fill(`#iq-choices-${i}`, 'Yes\nNo\nNot sure');
    await hr.getByRole('button', { name: 'Save exit settings' }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
  });

  // ---------- resigning ----------
  test('TC-X009 reason required to resign', async () => {
    const p = P.e1; await p.reload(); await nav(p, 'My exit');
    await expect(p.getByText('Your notice period is')).toContainText('45 days');
    await p.getByRole('button', { name: 'Resign…' }).click();
    await p.getByRole('button', { name: 'Send my resignation' }).click();
    await expect(err(p)).toContainText('Choose a reason');
  });
  test('TC-X008 employee resigns', async () => {
    const p = P.e1;
    await p.selectOption('#resign-reason', 'career'); await p.fill('#resign-last-day', isoDay(5)); await p.fill('#resign-note', 'A role closer to home');
    await p.getByRole('button', { name: 'Send my resignation' }).click();
    await expect(p.getByRole('heading', { name: /Your resignation/ })).toContainText('Waiting for your manager');
  });
  test('TC-X010 withdraw before acceptance', async () => {
    const p = P.e5; await p.reload(); await nav(p, 'My exit');
    await p.getByRole('button', { name: 'Resign…' }).click(); await p.selectOption('#resign-reason', 'pay'); await p.getByRole('button', { name: 'Send my resignation' }).click();
    await p.getByRole('button', { name: 'Withdraw my resignation' }).click();
    await p.getByRole('button', { name: 'Withdraw', exact: true }).click();
    await expect(p.getByText('You withdrew your earlier resignation.')).toBeVisible();
  });
  test('TC-X014 TC-X015 only the manager recommends; HR waits for the manager', async () => {
    const id = await exitId('e1');
    await refused(api(hr, 'POST', `/org/people/exits/${id}/recommend`, { lastWorkingDay: isoDay(3), note: '' }), 403, "Only the employee's manager can recommend");
    await refused(api(hr, 'POST', `/org/people/exits/${id}/accept`, { lastWorkingDay: isoDay(3), shortfallAction: 'waived' }), 409, 'waits for the manager');
  });
  test('TC-X013 manager recommends a last day', async () => {
    await openExit(P.mgr, 'e1');
    await P.mgr.getByRole('button', { name: 'Recommend' }).click();
    await P.mgr.fill('#rec-day', isoDay(3)); await expect(P.mgr.getByText('days before the notice ends')).toBeVisible();
    await P.mgr.fill('#rec-note', 'Handover is short'); await P.mgr.getByRole('button', { name: 'Send to HR' }).click();
    await expect(P.mgr.locator('.section-head h2')).toContainText('Waiting for HR');
  });
  test('TC-X016 TC-X003 HR accepts; default shortfall applied', async () => {
    await openExit(hr, 'e1');
    await hr.getByRole('button', { name: 'Accept' }).click();
    // Left empty, the shortfall follows the Organization's default (Waived, saved above).
    await expect(hr.locator('#acc-shortfall option:checked')).toHaveText('The Organization’s default');
    await hr.selectOption('#acc-shortfall', 'leave');
    await hr.locator('form').getByRole('button', { name: 'Accept' }).click();
    await expect(hr.locator('.section-head h2')).toContainText('Serving notice');
    await expect(hr.locator('.panels')).toContainText('Set against earned leave');
  });
  test('TC-X011 TC-X012 ask to withdraw after acceptance; not twice', async () => {
    const p = P.e1; await p.reload(); await nav(p, 'My exit');
    await p.getByRole('button', { name: 'Ask to withdraw my resignation' }).click();
    await p.fill('#withdraw-note', 'I would like to stay after all'); await p.getByRole('button', { name: 'Ask to withdraw', exact: true }).click();
    await expect(p.getByText('You asked to withdraw your resignation')).toBeVisible();
    await refused(api(p, 'POST', '/org/me/exit/withdraw', { note: 'Again please' }), 409, 'already asked');
  });
  test('TC-X023 HR refuses the withdrawal', async () => {
    await openExit(hr, 'e1');
    await hr.fill('#wd-note', 'The role is being filled'); await hr.getByRole('button', { name: 'Refuse' }).click();
    await expect(hr.locator('.section-head h2')).toContainText('Serving notice');
  });
  test('TC-X004 leave not allowed during notice', async () => {
    const types = await ok(api(P.e1, 'GET', '/org/leave/types'));
    const casual = types.find((t: any) => t.name === 'Casual leave');
    await refused(api(P.e1, 'POST', '/org/leave/me/requests', { leaveTypeId: casual.id, fromDate: isoDay(1), toDate: isoDay(1), reason: 'Errand in town' }), 409, 'cannot be taken during your notice period');
  });

  // ---------- HR-started exits ----------
  test('TC-X017 HR starts a termination with notice paid in lieu', async () => {
    await exits(hr); await hr.getByRole('button', { name: 'Start an exit' }).click();
    await hr.selectOption('#se-employee', { label: new RegExp(NAMES.e2) as any }).catch(async () => {
      const v = await hr.locator('#se-employee option', { hasText: NAMES.e2 }).getAttribute('value'); await hr.selectOption('#se-employee', v!);
    });
    await hr.selectOption('#se-kind', 'termination'); await hr.selectOption('#se-reason', 'performance');
    await hr.fill('#se-note', 'Agreed after the review'); await hr.fill('#se-day', isoDay(4)); await hr.selectOption('#se-shortfall', 'paid_in_lieu');
    await hr.getByRole('button', { name: 'Start the exit' }).click();
    await expect(hr.getByRole('heading', { name: new RegExp(NAMES.e2) })).toContainText('Serving notice');
    await expect(hr.locator('.panels')).toContainText('Notice paid in lieu');
  });
  test('TC-X018 TC-X019 one open exit per person; not one\'s own', async () => {
    await refused(api(hr, 'POST', '/org/people/exits', { employmentId: emp.e2, kind: 'termination', reasonCategory: 'conduct', lastWorkingDay: isoDay(4), shortfallAction: 'waived' }), 409, 'already has an exit in progress');
    await refused(api(hr, 'POST', '/org/people/exits', { employmentId: emp.hr, kind: 'termination', reasonCategory: 'conduct', lastWorkingDay: isoDay(4), shortfallAction: 'waived' }), 409, 'resign from My exit instead');
  });
  test('TC-X020 death ends the employment at once', async () => {
    await ok(api(hr, 'POST', '/org/people/exits', { employmentId: emp.e3, kind: 'death', reasonCategory: 'death', lastWorkingDay: isoDay(-1) }));
    await openExit(hr, 'e3');
    await expect(hr.getByTestId('exit-interview').getByRole('button', { name: 'Send the interview' })).toHaveCount(0);
  });
  test('TC-X021 absconding too soon refused', async () => {
    await refused(api(hr, 'POST', '/org/people/exits', { employmentId: emp.e4, kind: 'absconding', reasonCategory: 'absconding', lastWorkingDay: isoDay(-1) }), 409, 'Absconding can be recorded once');
  });
  test('TC-X022 absconding waits for the show-cause notice', async () => {
    await ok(api(hr, 'POST', '/org/people/exits', { employmentId: emp.e4, kind: 'absconding', reasonCategory: 'absconding', lastWorkingDay: isoDay(-4) }));
    await openExit(hr, 'e4');
    await expect(hr.getByRole('heading', { name: new RegExp(NAMES.e4) })).toContainText('Absent without leave');
    await expect(hr.getByTestId('absconding')).toContainText('Send the show-cause notice first');
    await hr.getByTestId('exit-letters').locator('[data-template="Show-cause notice"]').getByRole('button', { name: 'Issue' }).click();
    await expect(hr.getByTestId('absconding')).toContainText('The show-cause notice can be answered until');
  });
  test('TC-X025 TC-X024 cancel needs a reason, then cancels', async () => {
    await ok(api(hr, 'POST', '/org/people/exits', { employmentId: emp.e6, kind: 'termination', reasonCategory: 'conduct', lastWorkingDay: isoDay(5), shortfallAction: 'waived' }));
    await openExit(hr, 'e6');
    await hr.getByRole('button', { name: 'Cancel exit', exact: true }).click();
    await hr.fill('#cancel-note', 'no'); await hr.getByRole('button', { name: 'Cancel the exit' }).click();
    await expect(hr.locator('.error').first()).toContainText('Give a reason of at least 5 characters');
    await hr.fill('#cancel-note', 'Agreed to stay after talks'); await hr.getByRole('button', { name: 'Cancel the exit' }).click();
    await expect.poll(async () => (await ok(api(hr, 'GET', '/org/people/exits?status=all'))).find((x: any) => x.legalName === NAMES.e6)?.status).toBe('cancelled');
    const x = await ok(api(hr, 'GET', `/org/people/exits/${await exitId('e6')}`));
    expect(JSON.stringify(x)).toContain('Agreed to stay after talks');
  });

  // ---------- clearance ----------
  test('TC-X026 TC-X027 TC-X028 clearance tasks; add one; title limit', async () => {
    await openExit(hr, 'e1');
    await expect(hr.locator('.clearance')).toContainText(/0 of \d+ done/);
    await hr.getByRole('button', { name: 'Add a task' }).click();
    await hr.fill('#xt-title', 'x'.repeat(121)); await hr.getByRole('button', { name: 'Add task' }).click();
    await expect(hr.locator('.error').first()).toContainText('at most 120 characters');
    await hr.fill('#xt-title', 'Return the parking pass'); await hr.getByRole('button', { name: 'Add task' }).click();
    await expect(hr.locator('.clearance')).toContainText('Return the parking pass');
  });
  test('TC-X029 leaver records an amount owed to them', async () => {
    const p = P.e1; await p.reload(); await nav(p, 'My exit');
    const claims = p.locator('.task-row[data-task="Submit pending expense claims"]');
    await claims.getByRole('button', { name: 'Done, with an amount…' }).click();
    await p.fill('[id^="amount-"]:not([id^="amount-note"])', '1500'); await p.locator('[id^="direction-"]').selectOption('owed_to_employee');
    await p.locator('[id^="amount-note-"]').fill('Travel to the client');
    await claims.getByRole('button', { name: 'Mark done' }).click();
    await expect(claims).toContainText('₹1,500 owed to you');
  });

  // ---------- settlement ----------
  test('TC-X031 settlement before acceptance refused', async () => {
    await ok(api(P.e7, 'POST', '/org/me/exit', { reasonCategory: 'studies', requestedLastDay: isoDay(5), note: '' }));
    await refused(api(hr, 'POST', `/org/people/exits/${await exitId('e7')}/settlement/prepare`), 409, 'once the exit is accepted');
  });
  test('TC-X030 TC-X005 settlement worked out with lines, leave encashed', async () => {
    await openExit(hr, 'e1');
    const panel = hr.getByTestId('settlement');
    await panel.getByRole('button', { name: 'Work out the settlement' }).click();
    await expect(panel).toContainText('Draft');
    await expect(panel.getByTestId('line-salary')).toBeVisible();
    await expect(panel.getByTestId('line-leave_encashment')).toBeVisible();
  });
  test('TC-X032 TC-X033 add an extra line; not twice', async () => {
    const panel = hr.getByTestId('settlement');
    await panel.getByRole('button', { name: 'Add a line' }).click();
    await hr.fill('#line-label', 'Joining bonus clawback waived'); await hr.selectOption('#line-kind', 'bonus'); await hr.selectOption('#line-direction', 'earning'); await hr.fill('#line-amount', '2500');
    await panel.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(panel.getByTestId('line-bonus')).toBeVisible();
    await refused(api(hr, 'POST', `/org/people/exits/${await exitId('e1')}/settlement/lines`, { label: 'Another bonus', code: 'bonus', kind: 'bonus', direction: 'earning', amount: 100 }), 409);
  });
  test('TC-X035 TC-X034 missing amount blocks approval; the preparer cannot approve', async () => {
    const id = await exitId('e1');
    await refused(api(owner, 'POST', `/org/people/exits/${id}/settlement/approve`), 409, 'Give an amount');
    const panel = hr.getByTestId('settlement');
    const rows = panel.locator('tbody tr[data-testid^="line-"]');
    for (let i = 0; i < await rows.count(); i++) {
      const row = rows.nth(i); const amount = row.getByRole('spinbutton', { name: /Amount/ });
      if (await amount.count() && (await amount.inputValue()) === '') { await amount.fill('1000'); await row.getByRole('button', { name: 'Save' }).click(); await expect(row.getByRole('button', { name: 'Save' })).toHaveCount(0); }
    }
    await expect(panel.getByRole('button', { name: 'Approve and send the statement' })).toHaveCount(0);
    await refused(api(hr, 'POST', `/org/people/exits/${id}/settlement/approve`), 409, 'Someone other than the person who prepared it');
  });
  test('TC-X036 TC-X037 second person approves; line paid with UTR; not twice', async () => {
    await openExit(owner, 'e1');
    await owner.getByTestId('settlement').getByRole('button', { name: 'Approve and send the statement' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Approve and send' }).click();
    await expect(owner.getByTestId('settlement')).toContainText('Approved, being paid');
    await openExit(hr, 'e1');
    const salary = hr.getByTestId('settlement').getByTestId('line-salary');
    await salary.getByRole('button', { name: 'Mark paid…' }).click();
    await hr.locator('[id^="paid-ref-"]').fill('UTR20261112001'); await hr.getByRole('button', { name: 'Record' }).click();
    await expect(salary).toContainText('UTR20261112001');
    const s = await ok(api(hr, 'GET', `/org/people/exits/${await exitId('e1')}/settlement`));
    const line = s.lines.find((l: any) => l.code === 'salary');
    await refused(api(hr, 'POST', `/org/people/exits/${await exitId('e1')}/settlement/lines/${line.id}/paid`, { paidOn: isoDay(0), reference: 'UTR2' }), 409, 'already paid');
  });

  // ---------- letters, interview, hold, close ----------
  test('TC-X038 acceptance letter issued; relieving waits', async () => {
    const letters = hr.getByTestId('exit-letters');
    await expect(letters.locator('[data-letter="Resignation acceptance"]')).toBeVisible();
    const relieving = letters.locator('[data-template="Relieving letter"]');
    await expect(relieving).toContainText('Waits for the last working day');
    await expect(relieving.getByRole('button', { name: 'Issue' })).toHaveCount(0);
    await relieving.getByRole('button', { name: 'Preview' }).click();
    await expect(letters.locator('.letter-preview')).toContainText(NAMES.e1);
  });
  test('TC-X039 TC-X040 interview sent, answered, read only by HR', async () => {
    await hr.getByTestId('exit-interview').getByRole('button', { name: 'Send the interview' }).click();
    await expect(hr.getByTestId('exit-interview')).toContainText('Sent, waiting for answers');
    const p = P.e1; await p.reload(); await nav(p, 'My exit');
    const mine = p.getByTestId('my-interview');
    await mine.getByRole('combobox', { name: 'Would you recommend us to a friend?' }).selectOption('Yes');
    await mine.getByRole('button', { name: 'Send my answers' }).click();
    await expect(mine).toContainText('Thank you for your answers');
    await openExit(P.mgr, 'e1');
    await expect(P.mgr.getByTestId('exit-interview').getByRole('button', { name: 'Read it' })).toHaveCount(0);
    await openExit(hr, 'e1');
    await hr.getByTestId('exit-interview').getByRole('button', { name: 'Read it' }).click();
    await expect(hr.getByTestId('exit-interview')).toContainText('Yes');
  });
  test('TC-X042 TC-X041 legal hold reason checked; then on hold', async () => {
    await openExit(hr, 'e2');
    await hr.getByRole('button', { name: 'Put on hold…' }).click();
    await hr.fill('#hold-note', 'x'); await hr.getByRole('button', { name: 'Hold', exact: true }).click();
    await expect(hr.locator('.error').first()).toContainText('5 to 300 characters');
    await hr.fill('#hold-note', 'Claim before the labour court'); await hr.getByRole('button', { name: 'Hold', exact: true }).click();
    await expect(hr.locator('.panels')).toContainText('On hold');
  });
  test('TC-X043 TC-X044 close before the last day refused; then what is unfinished', async () => {
    await refused(api(hr, 'POST', `/org/people/exits/${await exitId('e1')}/close`, { rehire: 'yes', note: '' }), 409, 'once the last working day has passed');
    await refused(api(hr, 'POST', `/org/people/exits/${await exitId('e3')}/close`, { rehire: 'no', note: '' }), 409, 'Finish these first');
  });
  test('TC-X045 exits list shows stage and last day', async () => {
    await exits(hr);
    const row = hr.locator(`tr[data-exit="${NAMES.e2}"]`);
    await expect(row).toContainText('Termination'); await expect(row).toContainText('Serving notice');
  });

  // ---------- access ----------
  test('TC-X046 employee has My exit only', async () => {
    await P.e5.reload();
    await expect(P.e5.getByRole('link', { name: 'My exit' })).toBeVisible();
    await expect(P.e5.getByRole('link', { name: 'Exits', exact: true })).toHaveCount(0);
  });
  test('TC-X047 manager sees only the team', async () => {
    await ok(api(hr, 'POST', '/org/people/exits', { employmentId: emp.hr, kind: 'termination', reasonCategory: 'conduct', lastWorkingDay: isoDay(5), shortfallAction: 'waived' })).catch(() => {});
    await exits(P.mgr);
    await expect(P.mgr.getByRole('button', { name: NAMES.e1, exact: true })).toBeVisible();
    await expect(P.mgr.getByRole('button', { name: NAMES.hr, exact: true })).toHaveCount(0);
  });
  test('TC-X048 nobody decides their own exit', async () => {
    await ok(api(P.hr, 'POST', '/org/me/exit', { reasonCategory: 'career', requestedLastDay: isoDay(5), note: '' }));
    const mine = (await ok(api(owner, 'GET', '/org/people/exits'))).find((x: any) => x.legalName === NAMES.hr);
    await refused(api(P.hr, 'POST', `/org/people/exits/${mine.id}/accept`, { lastWorkingDay: isoDay(5), shortfallAction: 'waived' }), 409, 'Nobody can decide their own exit');
  });
  test('TC-X049 exit actions in the audit log', async () => {
    await owner.reload(); await nav(owner, 'Audit log');
    for (const a of ['exit.submitted', 'exit.recommended', 'exit.accepted', 'exit.started', 'exit.withdrawn', 'exit.legal_hold_on']) {
      await expect(owner.locator('code', { hasText: a }).first(), a).toBeVisible();
    }
  });
});
