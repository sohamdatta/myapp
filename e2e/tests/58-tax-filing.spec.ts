import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, errorOn, taxYear } from './helpers';
import { join } from './join';

// The screens for the filing work the backend already had (docs/payroll/tds-filings.md, statutory-filings.md):
// Form 122 other employers, TDS deposits, the PF and ESI files and the Form 130 certificates, in an Organization
// of its own, Filing Labs. Hema (Payroll Operator) prepares; the owner approves; Asha is the employee. One run is
// approved for the month, by API, since the payroll suites cover the run itself.

const RUN = process.env.E2E_RUN || '';   // a suffix, to run the file again on a database that already has Filing Labs
const D = `filing${RUN}.test`;
const U = (n: string) => `${n}@${D}`;
const P: Record<string, Page> = {};
let owner: Page;
let hema: Page;
let asha: Page;
let ashaEmployment = '';
let period = '';   // the run's month, yyyy-mm-01

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function refused(r: Promise<{ status: number; body: any }>, status: number | number[], text?: string) {
  const done = await r;
  expect([status].flat(), JSON.stringify(done.body)).toContain(done.status);
  if (text) expect(String(done.body.message) + ' ' + String(done.body.code)).toContain(text);
}
const firstOf = (offset = 0) => { const [y, m] = isoDay(0).split('-').map(Number); return new Date(Date.UTC(y, m - 1 + offset, 1)).toISOString().slice(0, 10); };
async function payroll(p: Page, name: string) { await p.reload(); await nav(p, 'Payroll'); await tab(p, name); }

test.describe.serial('tax filing screens', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: `Filing Labs${RUN}`, slug: `filing-labs${RUN}`, adminEmail: U('owner') }));
    await admin.context().close();
    owner = await join(browser, org.invitationToken, U('owner'));
    const roles: Record<string, string[]> = { hema: ['hr_admin', 'payroll_operator', 'employee'], asha: ['employee'] };
    for (const [n, r] of Object.entries(roles)) {
      const inv = await ok(api(owner, 'POST', '/org/invitations', { email: U(n), roles: r }));
      P[n] = await join(browser, inv.token, U(n));
    }
    hema = P.hema; asha = P.asha;

    const ids = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
    const shift = await ok(api(owner, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
    const loc = await ok(api(owner, 'POST', '/org/attendance/locations', { name: 'Bengaluru office', timezone: 'Asia/Kolkata', stateCode: 'KA', active: true, punchCheck: 'none' }));
    await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: Object.values(ids), workLocationId: loc.id, shiftId: shift.id, weeklyOffs: [0], remoteDays: [], effectiveFrom: isoDay(-5) }));
    const added = await ok(api(owner, 'POST', '/org/people/employees', {
      legalName: 'Asha Rao', joiningDate: isoDay(-400),   // date-safe: joined long ago
      designation: 'Associate', employmentType: 'permanent', gender: 'female', dateOfBirth: '1992-04-10',
      membershipId: ids[U('asha')], pan: 'ABCPR5678L', bankAccount: '50100033334444', ifsc: 'HDFC0000123',
    }));
    ashaEmployment = added.id;

    // The employer, its registrations, Asha's pay, and one approved month with tax deducted.
    const setup = await ok(api(hema, 'GET', '/org/payroll/setup'));
    await ok(api(hema, 'PUT', '/org/payroll/settings', {
      ...setup.settings, entityName: 'Filing Labs Private Limited', pan: 'AAACF1234K', tan: 'BLRF12345E', address: '100 Feet Road, Indiranagar, Bengaluru 560038',
      debitAccount: '50200012345678', debitIfsc: 'HDFC0000123', metroLocations: [loc.id],
    }));
    for (const [statute, number, state] of [['pf', 'KNBNG0088888000', null], ['esi', '53000888880001001', null], ['pt', 'PTR-KA-1234567', 'KA'], ['lwf', 'LWF-KA-1234', 'KA']]) {
      await ok(api(hema, 'POST', '/org/payroll/registrations', { statute, state, number }));
    }
    const staff = setup.structures.find((s: any) => s.name === 'Staff').id;
    await ok(api(hema, 'POST', `/org/people/employees/${ashaEmployment}/pay`, { validFrom: firstOf(), structureId: staff, annualAmount: 36000000, reason: 'Pay on record' }));
    const group = (await ok(api(hema, 'GET', '/org/payroll/runs'))).groups[0];
    await ok(api(hema, 'POST', `/org/payroll/groups/${group.id}/members`, { employmentIds: [ashaEmployment], validFrom: firstOf() }));
    period = firstOf();
    const run = await ok(api(hema, 'POST', '/org/payroll/runs', { groupId: group.id, period }));
    await ok(api(hema, 'POST', `/org/payroll/runs/${run.id}/freeze`));
    await ok(api(hema, 'POST', `/org/payroll/runs/${run.id}/calculate`));
    const calculated = await ok(api(owner, 'GET', `/org/payroll/runs/${run.id}`));
    for (const x of calculated.exceptions.filter((e: any) => e.level === 'blocking' && e.acceptable && !e.acceptedAt)) {
      await ok(api(owner, 'POST', `/org/payroll/runs/${run.id}/exceptions/${x.id}/accept`, { reason: 'Checked for the filing tests' }));
    }
    await ok(api(owner, 'POST', `/org/payroll/runs/${run.id}/approve`));
  });

  test.afterAll(async () => {
    for (const p of [owner, hema, asha]) await p?.context().close();
  });

  // ---------- Form 122: other employers ----------
  test('Asha declares an earlier employer; a bad TAN is refused on the form', async () => {
    await asha.reload(); await nav(asha, 'My pay'); await tab(asha, 'Tax declaration');
    const panel = asha.getByTestId('other-employers');
    await expect(panel).toContainText('No earlier employer declared');
    await panel.getByRole('button', { name: 'Declare an employer' }).click();
    await asha.getByLabel('Employer', { exact: true }).fill('Initech Ltd');
    await asha.getByLabel(/^TAN/).fill('BADTAN');
    await asha.getByLabel('Months worked there').selectOption('3');
    await asha.getByLabel('Taxable salary').fill('300000');
    await asha.getByLabel('PF', { exact: true }).fill('21600');
    await asha.getByLabel('Professional tax').fill('600');
    await asha.getByLabel('Tax deducted (TDS)').fill('10000');
    await panel.getByRole('button', { name: 'Add employer' }).click();
    await expect(errorOn(asha)).toContainText('A TAN is four letters');
    await asha.getByLabel(/^TAN/).fill('blri12345b');
    await panel.getByRole('button', { name: 'Add employer' }).click();
    const row = panel.locator('[data-employer="Initech Ltd"]');
    await expect(row).toContainText('Waiting for HR');
    await expect(row).toContainText('BLRI12345B');
  });
  test('HR sends it back with a note; Asha sees the note and changes it', async () => {
    await owner.reload(); await nav(owner, 'Tax declarations');
    await owner.getByRole('button', { name: 'Asha Rao', exact: true }).click();
    const review = owner.getByTestId('other-employer-review');
    await expect(review).toContainText('Initech Ltd');
    await review.getByRole('button', { name: 'Return…' }).click();
    await owner.getByLabel('What needs to change').fill('Attach the relieving statement and check the TDS');
    await review.getByRole('button', { name: 'Send back' }).click();
    await expect(review).toContainText('Sent back: Attach the relieving statement');

    await asha.reload(); await nav(asha, 'My pay'); await tab(asha, 'Tax declaration');
    const row = asha.getByTestId('other-employers').locator('[data-employer="Initech Ltd"]');
    await expect(row).toContainText('HR returned this: Attach the relieving statement');
    await row.getByRole('button', { name: 'Change' }).click();
    await asha.getByLabel('Tax deducted (TDS)').fill('11000');
    await asha.getByRole('button', { name: 'Save changes' }).click();
    await expect(row).toContainText('Waiting for HR');
    await expect(row).toContainText('₹11,000');
  });
  test('HR accepts it, and Asha can no longer change or remove it', async () => {
    await owner.reload(); await nav(owner, 'Tax declarations');
    await owner.getByRole('button', { name: 'Asha Rao', exact: true }).click();
    const review = owner.getByTestId('other-employer-review');
    await review.getByRole('button', { name: 'Accept…' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Accept', exact: true }).click();
    await expect(review).toContainText('Accepted');
    await asha.reload(); await nav(asha, 'My pay'); await tab(asha, 'Tax declaration');
    const row = asha.getByTestId('other-employers').locator('[data-employer="Initech Ltd"]');
    await expect(row).toContainText('Accepted by HR');
    await expect(row.getByRole('button', { name: 'Change' })).toHaveCount(0);
    await expect(row.getByRole('button', { name: 'Remove' })).toHaveCount(0);
  });

  // ---------- TDS deposits ----------
  test('Hema records a deposit for the month; a wrong amount is refused with the server’s sum', async () => {
    await payroll(hema, 'Tax filing');
    const row = hema.locator(`tr[data-month="${period.slice(0, 7)}"]`);
    await expect(row).toContainText('Not deposited');
    await row.getByRole('button', { name: 'Record deposit…' }).click();
    const form = hema.getByRole('form', { name: /^Record deposit/ });
    await expect(form.getByRole('checkbox')).toBeChecked();
    await form.getByLabel('BSR code').fill('1234567');
    await form.getByLabel('Challan serial number').fill('00001');
    await form.getByLabel('Deposited on').fill(isoDay(0));
    const right = await form.getByLabel('Tax', { exact: true }).inputValue();
    await form.getByLabel('Tax', { exact: true }).fill(String(Number(right) - 1));
    await form.getByRole('button', { name: 'Record deposit' }).click();
    await expect(errorOn(hema)).toContainText('add up to');
    await form.getByLabel('Tax', { exact: true }).fill(right);
    await form.getByRole('button', { name: 'Record deposit' }).click();
    const challan = hema.locator('[data-challan="00001"]');
    await expect(challan).toContainText('Recorded, to approve');
    await expect(challan.getByRole('button', { name: 'Approve' })).toHaveCount(0);
  });
  test('the operator can only remove it; the owner approves it', async () => {
    // The operator cannot approve at all, so there is no Approve button, only Remove to correct a mistake.
    await expect(hema.locator('[data-challan="00001"]').getByRole('button', { name: 'Remove' })).toBeVisible();
    await payroll(owner, 'Tax filing');
    const row = owner.locator(`tr[data-month="${period.slice(0, 7)}"]`);
    await row.getByRole('button', { name: /^\w+ \d{4}$/ }).click();
    await owner.locator('[data-challan="00001"]').getByRole('button', { name: 'Approve' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(owner.locator(`tr[data-month="${period.slice(0, 7)}"]`)).toContainText('Deposited');
    await expect(owner.locator('[data-challan="00001"]')).toContainText('Approved');
  });
  test('a challan can be removed before approval, and a second deposit for the same run is refused', async () => {
    await refused(api(hema, 'POST', '/org/payroll/tds/challans', {
      month: period.slice(0, 7), bsrCode: '1234567', serial: '00002', depositedOn: isoDay(0), tax: 1, runIds: [],
    }), 400);
  });

  // ---------- Form 138 statements ----------
  test('Hema builds a statement for the quarter; checks show, and filing waits on them', async () => {
    await payroll(hema, 'Tax filing');
    await hema.getByRole('tab', { name: 'Statements' }).click();
    const [m] = period.split('-').slice(1).map(Number);
    const quarter = m >= 4 && m <= 6 ? 1 : m >= 7 && m <= 9 ? 2 : m >= 10 ? 3 : 4;
    await hema.getByRole('tab', { name: new RegExp(`^Q${quarter} `) }).click();
    const [taxYearLabel] = [taxYear().label];
    await expect(hema.getByLabel('Tax year')).toHaveValue(taxYearLabel);
    await hema.getByRole('button', { name: 'Build statement' }).click();
    const statement = hema.locator('[data-version="1"]');
    await expect(statement).toContainText('Version 1');
    await expect(statement).toContainText('TAN BLRF12345E');
    // Whatever the checks are, nothing can be downloaded while one is unresolved.
    const download = statement.getByRole('button', { name: 'Download review spreadsheet' });
    if (await statement.getByText(/to resolve before the file can be downloaded/).count()) await expect(download).toBeDisabled();
  });

  // ---------- PF and ESI ----------
  test('Hema builds the PF file for the month and sees the checks; a blocking one stops the download', async () => {
    await payroll(hema, 'Statutory filing');
    await hema.getByLabel('Month of the return').fill(period.slice(0, 7));
    const pf = hema.locator('[data-kind="pf_ecr"]');
    await pf.getByRole('button', { name: 'Build and check' }).click();
    const version = pf.locator('[data-version="1"]');
    await expect(version).toContainText('Version 1');
    // Asha has no UAN yet, so the file cannot be downloaded until one is recorded.
    await expect(version).toContainText(/UAN/);
    await expect(version.getByRole('button', { name: 'Download file' })).toBeDisabled();
    await expect(version).toContainText('Fix the blocking checks and build again.');
  });
  test('the ESI return builds too, and its reason codes can be changed', async () => {
    const esi = hema.locator('[data-kind="esi"]');
    await esi.getByRole('button', { name: 'Build and check' }).click();
    await expect(esi.locator('[data-version="1"]')).toContainText('Version 1');
    const reasons = hema.getByTestId('esi-reasons');
    const code = reasons.getByLabel('ESIC code for Retirement');
    await code.fill('4');
    await reasons.locator('tr[data-kind="retirement"]').getByRole('button', { name: 'Save' }).click();
    await expect(hema.getByText('Saved')).toBeVisible();
    await hema.reload(); await nav(hema, 'Payroll'); await tab(hema, 'Statutory filing');
    await expect(hema.getByTestId('esi-reasons').getByLabel('ESIC code for Retirement')).toHaveValue('4');
    await refused(api(hema, 'PUT', '/org/payroll/filings/esi-reasons/nonsense', { code: 4 }), 400);
  });
  test('an employee has neither tab', async () => {
    await asha.reload();
    await expect(asha.getByRole('link', { name: 'Payroll', exact: true })).toHaveCount(0);
    await refused(api(asha, 'GET', `/org/payroll/filings?month=${period.slice(0, 7)}`), 403);
    await refused(api(asha, 'GET', '/org/payroll/tds/challans'), 403);
    await refused(api(asha, 'GET', '/org/payroll/tax-certificates'), 403);
  });

  // ---------- Form 130 certificates ----------
  // A certificate is built only once the year's March pay is approved, so a month in the middle of the year builds none and says why.
  // Building, releasing by a second person and the employee's download are covered against a March clock by TaxCertificatesTest.
  test('Hema builds the certificates mid-year; nobody is ready, and the screen says who was left out and why', async () => {
    await payroll(hema, 'Tax filing');
    await hema.getByRole('tab', { name: 'Certificates' }).click();
    await expect(hema.getByTestId('tax-certificates')).toContainText('No certificates for');
    await hema.getByRole('button', { name: 'Build certificates' }).click();
    await expect(hema.getByRole('status').filter({ hasText: /^Built 0; 0 already released/ })).toBeVisible();
    await expect(hema.getByTestId('tax-certificates').locator('tbody tr', { hasText: 'Asha Rao' })).toBeVisible();
    await expect(hema.getByTestId('tax-certificates')).toContainText('No certificates for');
    await refused(api(hema, 'POST', `/org/payroll/tax-certificates/00000000-0000-0000-0000-000000000000/release`, { acknowledgeDraftRules: true }), [403, 404]);
  });
  test('Asha has no certificate yet, and says so in My pay', async () => {
    await asha.reload(); await nav(asha, 'My pay'); await tab(asha, 'Tax certificates');
    await expect(asha.getByTestId('my-tax-certificates')).toContainText('No certificates yet');
  });
});
