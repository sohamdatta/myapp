import { test, expect, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, errorOn, taxYear, withinTaxYear } from './helpers';
import { join } from './join';

// Every payroll and tax-declaration setting and screen, case by case (docs/testing/payroll-ui-test-cases.xlsx; the TC ids
// are its rows), in an Organization of its own, Pay Labs. Hema (HR Admin and Payroll Operator) sets up and runs the
// month; the owner approves. Asha and Ravi work in the Bengaluru office; Ravi has no bank account or PAN yet.

const RUN = process.env.E2E_RUN || '';   // a suffix, to run the file again on a database that already has Pay Labs
const D = `pay${RUN}.test`;
const U = (n: string) => `${n}@${D}`;
const NAMES: Record<string, string> = { hr: 'Hema Iyer', asha: 'Asha Rao', ravi: 'Ravi Kumar' };
const PDF = Buffer.from('%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n');
const P: Record<string, Page> = {};
let hr: Page;
let owner: Page;
const emp: Record<string, string> = {};
let runId = '';

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function refused(r: Promise<{ status: number; body: any }>, status: number | number[], text?: string) {
  const done = await r;
  expect([status].flat(), JSON.stringify(done.body)).toContain(done.status);
  if (text) expect(String(done.body.message) + ' ' + String(done.body.code)).toContain(text);
  return done.body;
}
const firstOf = (offset = 0) => { const [y, m] = isoDay(0).split('-').map(Number); return new Date(Date.UTC(y, m - 1 + offset, 1)).toISOString().slice(0, 10); };
const monthLabel = (offset = 0) => new Date(firstOf(offset) + 'T00:00:00Z').toLocaleString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });
async function payroll(p: Page, t?: string, sub?: string) { await p.reload(); await nav(p, 'Payroll'); if (t) await tab(p, t); if (sub) await tab(p, sub); }
async function openPay(p: Page, name: string) {
  await p.reload(); await nav(p, 'People');
  await p.locator('tr', { hasText: name }).getByRole('button', { name, exact: true }).click();
  await expect(p.getByRole('heading', { name: new RegExp(name) })).toBeVisible();
  await tab(p, 'Pay');
}
async function download(p: Page, button: string): Promise<string> {
  const [file] = await Promise.all([p.waitForEvent('download'), p.getByRole('button', { name: button, exact: true }).click()]);
  return fs.readFileSync(await file.path(), 'latin1');
}
async function myTax(p: Page) { await p.reload(); await nav(p, 'My pay'); await tab(p, 'Tax declaration'); }
const claim = (p: Page, label: string) => p.locator(`[data-claim="${label}"]`);

test.describe.serial('payroll test cases', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: `Pay Labs${RUN}`, slug: `pay-labs${RUN}`, adminEmail: U('owner') }));
    await admin.context().close();
    owner = await join(browser, org.invitationToken, U('owner'));
    const roles: Record<string, string[]> = { hr: ['hr_admin', 'payroll_operator', 'employee'], asha: ['employee'], ravi: ['employee'] };
    for (const [n, r] of Object.entries(roles)) {
      const inv = await ok(api(owner, 'POST', '/org/invitations', { email: U(n), roles: r }));
      P[n] = await join(browser, inv.token, U(n));
    }
    hr = P.hr;
    const ids = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
    const shift = await ok(api(owner, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
    const loc = await ok(api(owner, 'POST', '/org/attendance/locations', { name: 'Bengaluru office', timezone: 'Asia/Kolkata', stateCode: 'KA', active: true, punchCheck: 'none' }));
    await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: Object.values(ids), workLocationId: loc.id, shiftId: shift.id, weeklyOffs: [0], remoteDays: [], effectiveFrom: isoDay(-5) }));
    const add = (n: string, body: any) => ok(api(owner, 'POST', '/org/people/employees', { legalName: NAMES[n], joiningDate: isoDay(-400), designation: 'Associate', employmentType: 'permanent',   // date-safe: joined long ago
      gender: 'female', dateOfBirth: '1992-04-10', membershipId: ids[U(n)], ...body }));
    emp.hr = (await add('hr', { designation: 'HR manager', pan: 'ABCPI1234K', bankAccount: '50100011112222', ifsc: 'HDFC0000123' })).id;
    emp.asha = (await add('asha', { pan: 'ABCPR5678L', bankAccount: '50100033334444', ifsc: 'HDFC0000123' })).id;
    emp.ravi = (await add('ravi', { gender: 'male' })).id;
  });

  // ---------- employer ----------
  test('TC-Y001 TC-Y002 TC-Y003 the employer refuses a bad PAN, TAN and IFSC', async () => {
    await payroll(hr, 'Setup');
    const save = async (words: string) => { await hr.getByRole('button', { name: 'Save', exact: true }).click(); await expect(errorOn(hr)).toContainText(words); };
    await hr.fill('#pe-name', 'Pay Labs Private Limited');
    await hr.fill('#pe-pan', 'AAACP1234'); await save('The PAN should look like ABCDE1234F');
    await hr.fill('#pe-pan', 'AAACP1234K'); await hr.fill('#pe-tan', 'BLR12345E'); await save('The TAN should look like ABCD12345E');
    await hr.fill('#pe-tan', 'BLRP12345E'); await hr.fill('#pe-ifsc', 'HDFC000123'); await save('IFSC should be 11 characters');
  });
  test('TC-Y004 the employer is saved, with Bengaluru as a metro', async () => {
    await hr.fill('#pe-ifsc', 'HDFC0000123'); await hr.fill('#pe-debit', '50200012345678');
    await hr.fill('#pe-address', '2nd floor, 100 Feet Road, Indiranagar, Bengaluru 560038');
    await hr.getByRole('checkbox', { name: /Bengaluru office/ }).check();
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
  });
  test('TC-Y005 TC-Y006 TC-Y007 registrations: added; a repeat and a PT without a state refused', async () => {
    const regs = hr.getByTestId('registrations');
    for (const [statute, number] of [['pf', 'KNBNG0099999000'], ['esi', '53000999990001001'], ['pt', 'PTR-KA-7654321'], ['lwf', 'LWF-KA-1122']]) {
      await hr.selectOption('#reg-statute', statute); await hr.fill('#reg-number', number);
      await regs.getByRole('button', { name: 'Add', exact: true }).click();
      await expect(regs).toContainText(number);
    }
    await refused(api(hr, 'POST', '/org/payroll/registrations', { statute: 'pf', state: null, number: 'KNBNG0099999000' }), 409);
    await refused(api(hr, 'POST', '/org/payroll/registrations', { statute: 'pt', state: null, number: 'PT-1' }), 400);
  });
  test('TC-Y008 TC-Y009 the statutory rules are shown, awaiting sign-off', async () => {
    await payroll(hr, 'Runs');
    await expect(hr.getByText('awaiting the payroll consultant’s sign-off')).toBeVisible();
    await payroll(hr, 'Setup', 'Statutory rules');
    await expect(hr.locator('tr[data-rule="pf-IN"]')).toContainText('12%');
    await expect(hr.locator('tr[data-rule="pt-KA"]')).toContainText('Awaiting sign-off');
  });

  // ---------- tables, components, structures, pay groups ----------
  test('TC-Y010 TC-Y011 TC-Y012 a table is added; capitals and a repeated key refused', async () => {
    await payroll(hr, 'Setup', 'Tables');
    await hr.getByRole('button', { name: 'Add a table' }).click();
    await hr.fill('#vt-name', 'meal_card'); await hr.fill('#vt-key', 'State');
    await hr.fill('#vt-k-0', 'KA'); await hr.fill('#vt-v-0', '2200');
    await hr.getByRole('button', { name: 'Save table' }).click();
    await expect(hr.locator('code', { hasText: 'meal_card' })).toBeVisible();
    await refused(api(hr, 'POST', '/org/payroll/tables', { name: 'Shift Pay', keyLabel: 'Shift', rows: [{ key: 'Night', value: 1 }] }), 400, 'lower-case');
    await refused(api(hr, 'POST', '/org/payroll/tables', { name: 'shift_pay', keyLabel: 'Shift', rows: [{ key: 'Night', value: 1 }, { key: 'night', value: 2 }] }), [400, 409], 'twice');
  });
  test('TC-Y013 TC-Y014 TC-Y015 a formula is checked, then the component is added', async () => {
    await payroll(hr, 'Setup', 'Components');
    await hr.getByRole('button', { name: 'Add a component' }).click();
    await hr.fill('#pc-name', 'Meal card'); await hr.fill('#pc-code', 'MEAL_CARD'); await hr.fill('#pc-from', firstOf());
    await hr.fill('#pc-formula', 'table("meal_card", location.state');
    await hr.getByRole('button', { name: 'Check' }).click();
    await expect(hr.getByRole('status').filter({ hasText: 'at character' })).toBeVisible();
    await hr.fill('#pc-formula', 'table("meal_card", location.state)');
    await hr.getByRole('button', { name: 'Check' }).click();
    await expect(hr.getByText('Looks right. Uses location.state.')).toBeVisible();
    await hr.getByLabel('Counts for ESI').uncheck(); await hr.getByLabel('Taxable').uncheck();
    await hr.getByRole('button', { name: 'Add component' }).click();
    await expect(hr.locator('tr[data-component="MEAL_CARD"]')).toBeVisible();
  });
  test('TC-Y016 TC-Y017 TC-Y018 a mid-month date and a repeated code refused; a statutory component cannot be removed', async () => {
    await hr.getByRole('button', { name: 'Add a component' }).click();
    await hr.fill('#pc-name', 'Night allowance'); await hr.fill('#pc-code', 'NIGHT'); await hr.fill('#pc-formula', 'BASIC * 0.1');
    await hr.fill('#pc-from', `${firstOf(1).slice(0, 8)}15`);
    await hr.getByRole('button', { name: 'Add component' }).click();
    await expect(errorOn(hr)).toContainText('A change applies from the first of a month');
    await hr.fill('#pc-from', firstOf(1)); await hr.fill('#pc-code', 'BASIC');
    await hr.getByRole('button', { name: 'Add component' }).click();
    await expect(errorOn(hr)).toContainText(/already|exists/i);
    await hr.getByRole('button', { name: 'Cancel' }).first().click();
    const pf = (await ok(api(hr, 'GET', '/org/payroll/setup'))).components.find((c: any) => c.code === 'PF_EE');
    await refused(api(hr, 'DELETE', `/org/payroll/components/${pf.id}`), 409);
  });
  test('TC-Y019 TC-Y020 the meal card joins the Staff structure; a loop is refused', async () => {
    await payroll(hr, 'Setup', 'Structures');
    await hr.locator('[data-structure="Staff"]').getByRole('button', { name: 'Change' }).click();
    await hr.fill('#ss-from', firstOf());
    await hr.getByRole('button', { name: 'Add a component' }).click();
    await hr.locator('.structure-line').last().locator('select').selectOption('MEAL_CARD');
    await hr.getByRole('button', { name: /^Save from/ }).click();
    await expect(hr.locator('[data-structure="Staff"]')).toContainText('Meal card');
    await hr.getByRole('button', { name: 'Add a structure' }).click();
    await hr.fill('#ss-name', 'Loop'); await hr.fill('#sl-formula-0', 'HRA * 2');
    await hr.getByRole('button', { name: 'Add a component' }).click();
    await hr.locator('#sl-code-1').selectOption('HRA'); await hr.fill('#sl-formula-1', 'BASIC / 2');
    await hr.getByRole('button', { name: 'Add structure' }).click();
    await expect(errorOn(hr)).toContainText(/loop/i);
    await hr.getByRole('button', { name: 'Cancel' }).first().click();
  });
  test('TC-Y021 TC-Y022 a pay group cut-off past 28 refused; everyone joins Monthly staff', async () => {
    await payroll(hr, 'Setup', 'Pay groups');
    await hr.getByRole('button', { name: 'Add a pay group' }).click();
    await hr.fill('#pg-name', 'Weekly'); await hr.fill('#pg-cutoff', '29');
    await hr.getByRole('button', { name: 'Save pay group' }).click();
    await expect(errorOn(hr)).toContainText('cut-off is a day from 1 to 28');
    await hr.getByRole('button', { name: 'Cancel' }).first().click();
    const group = hr.locator('[data-group="Monthly staff"]');
    await group.getByRole('button', { name: 'People' }).click();
    await group.getByRole('button', { name: /^Add them all to/ }).click();
    await expect(group.getByTestId('members')).toContainText(NAMES.asha);
  });

  // ---------- employee pay ----------
  test('TC-Y023 TC-Y024 pay is set with a preview; an absurd amount refused', async () => {
    await openPay(hr, NAMES.asha);
    await hr.getByRole('button', { name: 'Set pay' }).click();
    await hr.fill('#pay-from', firstOf()); await hr.fill('#pay-amount', '999999999999'); await hr.fill('#pay-reason', 'Pay on record');
    await hr.getByRole('button', { name: 'Preview' }).click();
    await expect(errorOn(hr)).toContainText('looks too large');
    await hr.fill('#pay-amount', '900000');
    await hr.getByRole('button', { name: 'Preview' }).click();
    await expect(hr.getByTestId('pay-after')).toContainText('Meal card');
    await hr.getByRole('button', { name: /^Save from/ }).click();
    await expect(hr.getByTestId('pay-history')).toContainText('Pay on record');
    await expect(hr.getByTestId('pay-now')).toContainText('Basic');
  });
  test('TC-Y025 TC-Y026 the tax regime is set; Ravi gets pay too', async () => {
    await hr.getByLabel('Income tax regime').selectOption('old');
    await hr.getByTestId('pay-profile').getByRole('button', { name: 'Save profile' }).click();
    await expect.poll(async () => (await ok(api(hr, 'GET', `/org/people/employees/${emp.asha}/pay`))).profile?.taxRegime).toBe('old');
    const staff = (await ok(api(hr, 'GET', '/org/payroll/setup'))).structures.find((s: any) => s.name === 'Staff').id;
    await ok(api(hr, 'POST', `/org/people/employees/${emp.ravi}/pay`, { validFrom: firstOf(), structureId: staff, annualAmount: 480000, reason: 'Pay on record' }));
  });
  test('TC-Y027 nobody sees or sets their own pay', async () => {
    await hr.reload(); await nav(hr, 'My profile');
    await expect(hr.getByRole('tab', { name: 'Pay' })).toHaveCount(0);
    const staff = (await ok(api(hr, 'GET', '/org/payroll/setup'))).structures.find((s: any) => s.name === 'Staff').id;
    await refused(api(hr, 'POST', `/org/people/employees/${emp.hr}/pay`, { validFrom: firstOf(), structureId: staff, annualAmount: 2000000, reason: 'My own raise' }), [403, 404, 409]);   // their own pay record is not found for them
  });
  test('TC-Y028 TC-Y029 the owner sets Hema’s pay and previews a revision', async () => {
    await openPay(owner, NAMES.hr);
    await owner.getByRole('button', { name: 'Set pay' }).click();
    await owner.fill('#pay-from', firstOf()); await owner.fill('#pay-amount', '1200000'); await owner.fill('#pay-reason', 'Pay on record');
    await owner.getByRole('button', { name: 'Preview' }).click(); await owner.getByRole('button', { name: /^Save from/ }).click();
    await expect(owner.getByTestId('pay-history')).toContainText('Pay on record');
    await owner.getByRole('button', { name: 'Revise pay' }).click();
    await owner.fill('#pay-from', firstOf(1)); await owner.fill('#pay-amount', '1320000'); await owner.fill('#pay-reason', 'Annual revision');
    await owner.getByRole('button', { name: 'Preview' }).click();
    await expect(owner.getByTestId('pay-before')).toBeVisible();
    await expect(owner.getByTestId('pay-after')).toContainText('+₹');
    await owner.getByRole('button', { name: /^Save from/ }).click();
    await expect(owner.getByTestId('pay-history').locator('tbody tr')).toHaveCount(2);
  });
  test('TC-Y030 a one-component override needs a reason; PF cannot be overridden', async () => {
    await refused(api(hr, 'POST', `/org/people/employees/${emp.asha}/pay/overrides`, { componentCode: 'HRA', monthlyAmount: 1000, validFrom: firstOf(1), reason: '' }), 400);
    await refused(api(hr, 'POST', `/org/people/employees/${emp.asha}/pay/overrides`, { componentCode: 'PF_EE', monthlyAmount: 1, validFrom: firstOf(1), reason: 'Less PF please' }), 400);
  });

  // ---------- the monthly run ----------
  test('TC-Y031 TC-Y032 a run starts for this month; a mid-month period refused', async () => {
    const group = (await ok(api(hr, 'GET', '/org/payroll/runs'))).groups[0];
    await refused(api(hr, 'POST', '/org/payroll/runs', { groupId: group.id, period: `${firstOf().slice(0, 8)}15` }), 400);
    await payroll(hr, 'Runs');
    await hr.getByRole('button', { name: `Start ${monthLabel()}` }).click();
    await expect(hr.getByTestId('inputs')).toBeVisible();
    runId = (await ok(api(hr, 'GET', '/org/payroll/runs'))).groups[0].open.id;
  });
  test('TC-Y033 TC-Y034 an input of zero refused; a bonus is added', async () => {
    await hr.selectOption('#in-employee', { label: NAMES.asha });
    await hr.selectOption('#in-component', { label: 'Bonus' });
    await hr.fill('#in-amount', '0');
    await hr.getByTestId('inputs').getByRole('button', { name: 'Add', exact: true }).click();
    await expect(errorOn(hr)).toContainText('above zero');
    await hr.fill('#in-amount', '20000'); await hr.fill('#in-note', 'Festival bonus');
    await hr.getByTestId('inputs').getByRole('button', { name: 'Add', exact: true }).click();
    await expect(hr.getByTestId('inputs')).toContainText('Festival bonus');
  });
  test('TC-Y035 TC-Y036 a hold needs a reason; Hema’s pay is held', async () => {
    await hr.selectOption('#hold-employee', { label: NAMES.hr });
    await hr.fill('#hold-reason', 'no');
    await expect(hr.getByRole('button', { name: 'Hold pay' })).toBeDisabled();
    await hr.fill('#hold-reason', 'Revised pay letter not signed yet');
    await hr.getByRole('button', { name: 'Hold pay' }).click();
    await expect(hr.getByTestId('holds')).toContainText('Revised pay letter not signed yet');
  });
  test('TC-Y037 TC-Y038 inputs are frozen; an input after that refused', async () => {
    await hr.getByRole('button', { name: 'Freeze inputs' }).click();
    await expect(hr.getByText(/Inputs are frozen for/)).toBeVisible();
    await refused(api(hr, 'POST', `/org/payroll/runs/${runId}/inputs`, { employmentId: emp.asha, componentCode: 'BONUS', amount: 100 }), 409, 'payroll.not_draft');
  });
  test('TC-Y039 TC-Y040 the run is calculated; whoever calculated cannot approve', async () => {
    await hr.getByRole('button', { name: 'Calculate', exact: true }).click();
    await expect(hr.getByTestId('run-net')).toBeVisible();
    await expect(hr.getByText('You calculated this run; someone else approves it')).toBeVisible();
    await expect(hr.getByRole('button', { name: 'Approve' })).toHaveCount(0);
    await refused(api(hr, 'POST', `/org/payroll/runs/${runId}/approve`), [403, 409]);
  });
  test('TC-Y041 Explain says how each line was worked out', async () => {
    await hr.locator(`tr[data-employee="${NAMES.asha}"]`).getByRole('button', { name: NAMES.asha }).click();
    await expect(hr.getByTestId('payslip')).toBeVisible();
    await hr.locator('tr[data-line="BASIC"]').getByRole('button', { name: 'Explain' }).click();
    await expect(hr.getByTestId('explain-BASIC')).toContainText('CTC_MONTHLY');
    await hr.locator('tr[data-line="MEAL_CARD"]').getByRole('button', { name: 'Explain' }).click();
    await expect(hr.getByTestId('explain-MEAL_CARD')).toContainText('meal_card');
    await hr.getByRole('button', { name: /register$/ }).click();
  });
  test('TC-Y042 TC-Y043 no bank account or PAN blocks; blocking exceptions stop approval', async () => {
    const run = await ok(api(owner, 'GET', `/org/payroll/runs/${runId}`));
    const ravi = run.exceptions.filter((x: any) => x.level === 'blocking' && JSON.stringify(x).includes(NAMES.ravi));
    expect(ravi.map((x: any) => x.code).join(' '), JSON.stringify(run.exceptions)).toMatch(/bank/);
    await expect(hr.locator('li.exception.blocking', { hasText: NAMES.ravi }).first()).toBeVisible();
    await refused(api(owner, 'POST', `/org/payroll/runs/${runId}/approve`), 409, 'payroll.blocked');
  });
  test('TC-Y044 TC-Y045 the preparer cannot accept an exception; the approver accepts with a reason', async () => {
    const run = await ok(api(owner, 'GET', `/org/payroll/runs/${runId}`));
    const acceptable = run.exceptions.find((x: any) => x.level === 'blocking' && x.acceptable && !x.acceptedAt);
    expect(acceptable, JSON.stringify(run.exceptions)).toBeTruthy();
    await refused(api(hr, 'POST', `/org/payroll/runs/${runId}/exceptions/${acceptable.id}/accept`, { reason: 'I prepared it, I accept it' }), 403);
    await refused(api(owner, 'POST', `/org/payroll/runs/${runId}/exceptions/${acceptable.id}/accept`, { reason: '' }), 400);
    await owner.reload(); await nav(owner, 'Payroll');
    await owner.getByRole('button', { name: `Open ${monthLabel()}` }).click();
    const exceptions = owner.getByTestId('exceptions');
    const buttons = exceptions.getByRole('button', { name: 'Accept…' });
    await expect(buttons).toHaveCount(2);   // Ravi: no bank account, no PAN
    for (let left = 2; left > 0; left--) {
      const row = exceptions.locator('li.exception.blocking').filter({ has: owner.getByRole('button', { name: 'Accept…' }) }).first();
      await row.getByRole('button', { name: 'Accept…' }).click();
      await owner.fill('#reason-Accept', 'Checked with HR; fixing it next month');
      await owner.locator('form:has(#reason-Accept)').getByRole('button', { name: 'Accept', exact: true }).click();
      await expect(buttons).toHaveCount(left - 1);
    }
    await expect(owner.getByTestId('exceptions')).toContainText('Checked with HR; fixing it next month');
  });
  test('TC-Y046 the owner approves', async () => {
    await owner.getByRole('button', { name: 'Approve' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Approve payroll' }).click();
    await expect(owner.getByText(/Approved, to pay/).first()).toBeVisible();
  });
  test('TC-Y047 TC-Y048 TC-Y049 the register, the journal and the bank file', async () => {
    expect(await download(owner, 'Salary register')).toContain(NAMES.asha);
    expect(await download(owner, 'Accounting journal')).toContain('Salary payable');
    const bank = await download(owner, 'HDFC bank file');
    expect(bank).toContain('50100033334444');
    expect(bank).not.toContain(NAMES.hr);     // held pay stays out
    expect(bank).not.toContain(NAMES.ravi);   // and so does someone with no account
  });
  test('TC-Y050 TC-Y051 marked paid with the bank reference; payslips released', async () => {
    await owner.getByRole('button', { name: 'Mark paid…' }).click();
    await owner.fill('#paid-ref', 'HDFC-BULK-120001');
    await owner.getByRole('button', { name: 'Mark paid', exact: true }).click();
    await owner.getByRole('button', { name: 'Release payslips' }).click();
    await expect(owner.getByText('Done').first()).toBeVisible();
  });
  test('TC-Y052 an approved month cannot be reopened or recalculated', async () => {
    await refused(api(owner, 'POST', `/org/payroll/runs/${runId}/reopen`, { reason: 'Change this month' }), 409);
    await refused(api(owner, 'POST', `/org/payroll/runs/${runId}/calculate`), 409);
  });

  // ---------- my pay ----------
  test('TC-Y053 TC-Y054 the employee reads the payslip and why each line is what it is', async () => {
    const p = P.asha; await p.reload(); await nav(p, 'My pay');
    await expect(p.getByTestId('my-payslip')).toHaveCount(1);
    await p.getByTestId('my-payslip').first().click();
    await expect(p.getByTestId('net')).toBeVisible();
    await p.locator('tr[data-line="BASIC"]').getByRole('button', { name: 'Why' }).click();
    await expect(p.getByTestId('explain-BASIC')).toContainText('CTC_MONTHLY');
  });
  test('TC-Y055 the payslip PDF downloads', async () => {
    const p = P.asha;
    const [file] = await Promise.all([p.waitForEvent('download'), p.getByRole('button', { name: /PDF/ }).first().click()]);
    expect(fs.readFileSync(await file.path(), 'latin1').startsWith('%PDF')).toBe(true);
  });
  test('TC-Y056 the wage check reads the approved month', async () => {
    await payroll(hr, 'Wages');
    await expect(hr.locator('main')).toContainText(NAMES.asha);
  });

  // ---------- tax declarations ----------
  test('TC-Y057 before the window opens the employee cannot declare', async () => {
    const p = P.asha; await myTax(p);
    await expect(p.getByText('Declarations are not open yet')).toBeVisible();
    await refused(api(p, 'POST', '/org/me/tax/items', { head: 'nps', amount: 50000, details: {} }), 409, 'tax.declarations_closed');
  });
  test('TC-Y058 HR opens declarations; the employee is told', async () => {
    await hr.reload(); await nav(hr, 'Tax declarations');
    await hr.getByRole('button', { name: 'Open declarations…' }).click();
    await hr.fill('#tw-declare-from', isoDay(-1)); await hr.fill('#tw-declare-until', withinTaxYear(20));
    await Promise.all([
      hr.waitForResponse((r) => r.url().includes('/org/payroll/tax/window') && r.request().method() === 'PUT' && r.ok()),
      hr.getByRole('button', { name: 'Save', exact: true }).click(),
    ]);
    await expect(hr.getByTestId('tax-window')).toContainText('Declarations open');
    expect(JSON.stringify((await ok(api(P.asha, 'GET', '/org/notifications'))))).toContain(`Tax declarations for ${taxYear().label} are open`);
  });
  test('TC-Y059 TC-Y060 rent above ₹1 lakh needs the landlord’s PAN; then it is added', async () => {
    const p = P.asha; await myTax(p);
    const rent = p.locator('section[data-head="rent"]');
    await rent.getByRole('button', { name: 'Add' }).click();
    await p.fill('#claim-rent-amount', '20000'); await p.selectOption('#claim-rent-city', 'Bengaluru');
    await p.fill('#claim-rent-landlordName', 'K. Murthy'); await p.fill('#claim-rent-landlordAddress', '22 HAL 2nd Stage, Bengaluru');
    await rent.getByRole('button', { name: 'Add', exact: true }).last().click();
    await expect(p.locator('.error').first()).toContainText('needs the landlord');
    await p.fill('#claim-rent-landlordPan', 'abcpm4321q');
    await rent.getByRole('button', { name: 'Add', exact: true }).last().click();
    await expect(claim(p, 'Rent (HRA)')).toContainText('₹20,000');
  });
  test('TC-Y061 TC-Y062 the ₹1.5 lakh group and NPS are declared', async () => {
    const p = P.asha;
    const group = p.locator('section[data-head="sec80c"]');
    await group.getByRole('button', { name: 'Add' }).click();
    await p.selectOption('#claim-sec80c-kind', 'elss'); await p.fill('#claim-sec80c-amount', '90000');
    await group.getByRole('button', { name: 'Add', exact: true }).last().click();
    await expect(claim(p, 'Tax-saving mutual fund (ELSS)')).toContainText('₹90,000');
    const nps = p.locator('section[data-head="nps"]');
    await nps.getByRole('button', { name: 'Add' }).click();
    await p.fill('#claim-nps-amount', '50000');
    await nps.getByRole('button', { name: 'Add', exact: true }).last().click();
    await expect(claim(p, 'NPS, your own contribution')).toContainText('₹50,000');
  });
  test('TC-Y063 the declaration is submitted with the certificate', async () => {
    const p = P.asha;
    await p.getByRole('button', { name: 'Submit declaration' }).click();
    await expect(p.getByRole('dialog')).toContainText('You declare that what you have entered is true');
    await p.getByRole('dialog').getByRole('button', { name: 'Submit declaration' }).click();
    await expect(p.getByRole('heading', { name: new RegExp(`Tax declaration ${taxYear().label}`) })).toContainText('Submitted');
  });
  test('TC-Y064 TC-Y065 proofs open; a PDF is sent, a text file refused', async () => {
    await hr.reload(); await nav(hr, 'Tax declarations');
    await hr.getByRole('button', { name: 'Change' }).click();
    await hr.fill('#tw-proofs-from', isoDay(0)); await hr.fill('#tw-proofs-until', withinTaxYear(20));
    await Promise.all([
      hr.waitForResponse((r) => r.url().includes('/org/payroll/tax/window') && r.request().method() === 'PUT' && r.ok()),
      hr.getByRole('button', { name: 'Save', exact: true }).click(),
    ]);
    await expect(hr.getByTestId('tax-window')).toContainText('Proofs open');
    const p = P.asha; await myTax(p);
    for (const [label, file] of [['Tax-saving mutual fund (ELSS)', 'ELSS statement.pdf'], ['Rent (HRA)', 'Rent receipts.pdf']]) {
      const row = claim(p, label);
      await row.locator('input[type=file]').setInputFiles({ name: file, mimeType: 'application/pdf', buffer: PDF });
      await expect(row).toContainText('Proof sent, waiting');
    }
    const nps = claim(p, 'NPS, your own contribution');
    await nps.locator('input[type=file]').setInputFiles({ name: 'statement.txt', mimeType: 'text/plain', buffer: Buffer.from('hello') });
    await expect(nps.locator('.error')).toContainText('Only PDF, JPEG and PNG');
  });
  test('TC-Y066 TC-Y067 HR accepts a proof at less, not more, than declared', async () => {
    await hr.reload(); await nav(hr, 'Tax declarations');
    const row = hr.locator(`tr[data-employee="${NAMES.asha}"]`);
    await expect(row).toContainText('Proofs to check');
    await row.getByRole('button', { name: NAMES.asha }).click();
    const elss = claim(hr, 'Tax-saving mutual fund (ELSS)');
    await elss.getByRole('button', { name: 'Accept…' }).click();
    await elss.locator('input').fill('95000');
    await elss.getByRole('button', { name: 'Accept', exact: true }).click();
    await expect(elss.locator('.error')).toBeVisible();
    await elss.locator('input').fill('85000');
    await elss.getByRole('button', { name: 'Accept', exact: true }).click();
    await expect(elss).toContainText('Accepted at ₹85,000');
  });
  test('TC-Y068 TC-Y069 HR returns a proof with a reason; the employee sees both decisions', async () => {
    const rent = claim(hr, 'Rent (HRA)');
    await rent.getByRole('button', { name: 'Return…' }).click();
    await rent.locator('input').fill('Receipts for May and June are missing');
    await rent.getByRole('button', { name: 'Return', exact: true }).click();
    await expect(rent).toContainText('Returned');
    const p = P.asha; await myTax(p);
    await expect(claim(p, 'Tax-saving mutual fund (ELSS)')).toContainText('Proof accepted');
    await expect(claim(p, 'Rent (HRA)')).toContainText('Receipts for May and June are missing');
    await expect(claim(p, 'Rent (HRA)').getByRole('button', { name: 'Add another file' })).toBeVisible();
  });

  // ---------- access and audit ----------
  test('TC-Y070 an employee has My pay only, and cannot open the window or see others', async () => {
    const p = P.ravi; await p.reload();
    await expect(p.getByRole('link', { name: 'My pay' })).toBeVisible();
    await expect(p.getByRole('link', { name: 'Payroll', exact: true })).toHaveCount(0);
    await expect(p.getByRole('link', { name: 'Tax declarations', exact: true })).toHaveCount(0);
    await refused(api(p, 'GET', '/org/payroll/runs'), 403);
    await refused(api(p, 'GET', '/org/payroll/tax'), 403);
    await refused(api(p, 'PUT', '/org/payroll/tax/window', { taxYear: taxYear().label, declareFrom: isoDay(0), declareUntil: isoDay(0) }), 403);
  });
  test('TC-Y071 payroll actions in the audit log', async () => {
    await owner.reload(); await nav(owner, 'Audit log');
    await expect(owner.locator('code', { hasText: /^payroll\.|^tax\./ }).first()).toBeVisible();
  });
});
