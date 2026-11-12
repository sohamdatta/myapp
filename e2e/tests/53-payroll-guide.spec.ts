import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, taxYear, withinTaxYear, shot as save } from './helpers';
import { join } from './join';

// The screenshots in docs/user-guide/payroll.md, taken in a demo Organization of their own (Northwind Textiles),
// so they show tidy names. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.

const RUN = process.env.E2E_RUN || '';   // only to try the steps again on a database that already has the demo
const em = (n: string) => `${n}@textiles${RUN}.example`;
async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
const firstOf = (offset = 0) => { const [y, m] = isoDay(0).split('-').map(Number); return new Date(Date.UTC(y, m - 1 + offset, 1)).toISOString().slice(0, 10); };
const monthLabel = () => new Date(firstOf() + 'T00:00:00Z').toLocaleString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });

test('the payroll guide, screen by screen', async ({ browser }) => {
  test.setTimeout(420_000);
  const admin = await newPage(browser); await admin.goto('/');
  await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
  await expect(admin.getByText('Platform console')).toBeVisible();
  const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Northwind Textiles', slug: `northwind-textiles${RUN}`, adminEmail: em('owner') }));
  await admin.context().close();
  const owner = await join(browser, org.invitationToken, em('owner'));
  const pg: Record<string, Page> = {};
  const roles = { hr: ['hr_admin', 'payroll_operator', 'employee'], kavya: ['employee'], arjun: ['employee'], deepa: ['employee'] } as const;
  for (const [n, r] of Object.entries(roles)) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles: r }));
    pg[n] = await join(browser, inv.token, em(n));
  }
  const { hr, kavya } = pg;
  const id = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
  const shift = await ok(api(owner, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
  const loc = await ok(api(owner, 'POST', '/org/attendance/locations', { name: 'Pune mill', timezone: 'Asia/Kolkata', stateCode: 'MH', active: true, punchCheck: 'none' }));
  await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: Object.values(id), workLocationId: loc.id, shiftId: shift.id, weeklyOffs: [0], remoteDays: [], effectiveFrom: isoDay(-5) }));
  const add = (body: any) => ok(api(owner, 'POST', '/org/people/employees', { joiningDate: isoDay(-500), employmentType: 'permanent', dateOfBirth: '1990-05-12', ...body }));   // date-safe: joined long ago
  const people: Record<string, any> = {};
  people.hr = await add({ legalName: 'Sunita Joshi', designation: 'HR manager', gender: 'female', pan: 'ABCPJ1111A', bankAccount: '50100055550001', ifsc: 'HDFC0001234', membershipId: id[em('hr')] });
  people.kavya = await add({ legalName: 'Kavya Patil', designation: 'Merchandiser', gender: 'female', pan: 'ABCPP2222B', bankAccount: '50100055550002', ifsc: 'HDFC0001234', membershipId: id[em('kavya')] });
  people.arjun = await add({ legalName: 'Arjun Desai', designation: 'Supervisor', gender: 'male', pan: 'ABCPD3333C', bankAccount: '50100055550003', ifsc: 'HDFC0001234', membershipId: id[em('arjun')] });
  people.deepa = await add({ legalName: 'Deepa Kulkarni', designation: 'Designer', gender: 'female', membershipId: id[em('deepa')] });

  const shot = async (p: Page, name: string, fn: () => Promise<void>) => { await fn(); await save(p, `payroll-${name}`); };
  for (const p of [owner, hr, kavya]) await p.setViewportSize({ width: 1280, height: 860 });
  const payroll = async (p: Page, t?: string, sub?: string) => { await p.reload(); await nav(p, 'Payroll'); if (t) await tab(p, t); if (sub) await tab(p, sub); };

  // Employer and registrations.
  await payroll(hr, 'Setup');
  await hr.fill('#pe-name', 'Northwind Textiles Private Limited'); await hr.fill('#pe-pan', 'AAACN1234T'); await hr.fill('#pe-tan', 'PNEN12345T');
  await hr.fill('#pe-address', 'Plot 12, MIDC Bhosari, Pune 411026'); await hr.fill('#pe-debit', '50200098765432'); await hr.fill('#pe-ifsc', 'HDFC0001234');
  await hr.getByRole('checkbox', { name: /Pune mill/ }).check();
  await hr.getByRole('button', { name: 'Save', exact: true }).click(); await expect(hr.getByText('Saved.')).toBeVisible();
  const regs = hr.getByTestId('registrations');
  for (const [statute, number] of [['pf', 'PUPUN0054321000'], ['esi', '33000543210001001'], ['pt', 'PTR-MH-27123456789P'], ['lwf', 'LWF-MH-4455']]) {
    await hr.selectOption('#reg-statute', statute);
    if (statute === 'pt' || statute === 'lwf') await hr.selectOption('#reg-state', 'MH');   // registered in the state of the work location
    await hr.fill('#reg-number', number);
    await regs.getByRole('button', { name: 'Add', exact: true }).click(); await expect(regs).toContainText(number);
  }
  await shot(hr, '01-employer', async () => { await hr.evaluate(() => window.scrollTo(0, 0)); });
  await shot(hr, '02-registrations', async () => { await regs.scrollIntoViewIfNeeded(); });
  await shot(hr, '03-rules', async () => { await payroll(hr, 'Setup', 'Statutory rules'); await expect(hr.locator('tr[data-rule="pf-IN"]')).toBeVisible(); });

  // A conveyance allowance by designation, read from a table.
  await payroll(hr, 'Setup', 'Tables');
  await hr.getByRole('button', { name: 'Add a table' }).click();
  await hr.fill('#vt-name', 'conveyance'); await hr.fill('#vt-key', 'Designation');
  for (const [i, [k, v]] of [['Supervisor', '2400'], ['Merchandiser', '1800'], ['Designer', '1800'], ['HR manager', '1800']].entries()) {
    if (i > 0) await hr.getByRole('button', { name: 'Add a row' }).click();
    await hr.fill(`#vt-k-${i}`, k); await hr.fill(`#vt-v-${i}`, v);
  }
  await shot(hr, '04-table', async () => { await expect(hr.locator('#vt-k-3')).toHaveValue('HR manager'); });
  await hr.getByRole('button', { name: 'Save table' }).click(); await expect(hr.locator('code', { hasText: 'conveyance' })).toBeVisible();

  await payroll(hr, 'Setup', 'Components');
  await shot(hr, '05-components', async () => { await expect(hr.locator('tr[data-component="BASIC"]')).toBeVisible(); });
  await hr.getByRole('button', { name: 'Add a component' }).click();
  await hr.fill('#pc-name', 'Conveyance'); await hr.fill('#pc-code', 'CONVEYANCE'); await hr.fill('#pc-from', firstOf());
  await hr.fill('#pc-formula', 'table("conveyance", designation)');
  await hr.getByRole('button', { name: 'Check' }).click(); await expect(hr.getByText(/Looks right/)).toBeVisible();
  await hr.fill('#pc-ledger', '5150 Conveyance'); await hr.fill('#pc-position', '35');
  await shot(hr, '06-component-form', async () => { await hr.locator('#pc-formula').scrollIntoViewIfNeeded(); });
  await hr.getByRole('button', { name: 'Add component' }).click(); await expect(hr.locator('tr[data-component="CONVEYANCE"]')).toBeVisible();

  await payroll(hr, 'Setup', 'Structures');
  await hr.locator('[data-structure="Staff"]').getByRole('button', { name: 'Change' }).click();
  await hr.fill('#ss-from', firstOf());
  await hr.getByRole('button', { name: 'Add a component' }).click();
  await hr.locator('.structure-line').last().locator('select').selectOption('CONVEYANCE');
  await hr.getByRole('button', { name: /^Save from/ }).click(); await expect(hr.locator('[data-structure="Staff"]')).toContainText('Conveyance');
  await shot(hr, '07-structure', async () => { await hr.locator('[data-structure="Staff"]').scrollIntoViewIfNeeded(); });

  await payroll(hr, 'Setup', 'Pay groups');
  const group = hr.locator('[data-group="Monthly staff"]');
  await group.getByRole('button', { name: 'People' }).click();
  await group.getByRole('button', { name: /^Add them all to/ }).click(); await expect(group.getByTestId('members')).toContainText('Kavya Patil');
  await shot(hr, '08-pay-group', async () => {});

  // Pay: Kavya with the preview; the others by the API; Sunita's by the owner.
  const openPay = async (p: Page, name: string) => {
    await p.reload(); await nav(p, 'People');
    await p.locator('tr', { hasText: name }).getByRole('button', { name, exact: true }).click(); await tab(p, 'Pay');
  };
  await openPay(hr, 'Kavya Patil');
  await hr.getByRole('button', { name: 'Set pay' }).click();
  await hr.fill('#pay-from', firstOf()); await hr.fill('#pay-amount', '840000'); await hr.fill('#pay-reason', 'Pay on record');
  await hr.getByRole('button', { name: 'Preview' }).click(); await expect(hr.getByTestId('pay-after')).toContainText('Conveyance');
  await shot(hr, '09-set-pay', async () => {});
  await hr.getByRole('button', { name: /^Save from/ }).click(); await expect(hr.getByTestId('pay-now')).toContainText('Basic');
  await hr.getByLabel('Income tax regime').selectOption('old');
  await hr.getByTestId('pay-profile').getByRole('button', { name: 'Save profile' }).click();
  await shot(hr, '10-employee-pay', async () => { await expect(hr.getByTestId('pay-history')).toContainText('Pay on record'); });
  const staff = (await ok(api(hr, 'GET', '/org/payroll/setup'))).structures.find((s: any) => s.name === 'Staff').id;
  for (const [who, amount] of [['arjun', 600000], ['deepa', 540000]] as const) {
    await ok(api(hr, 'POST', `/org/people/employees/${people[who].id}/pay`, { validFrom: firstOf(), structureId: staff, annualAmount: amount, reason: 'Pay on record' }));
  }
  await ok(api(owner, 'POST', `/org/people/employees/${people.hr.id}/pay`, { validFrom: firstOf(), structureId: staff, annualAmount: 1080000, reason: 'Pay on record' }));

  // The month: inputs and a hold, freeze, calculate.
  await payroll(hr, 'Runs');
  await hr.getByRole('button', { name: `Start ${monthLabel()}` }).click();
  await hr.selectOption('#in-employee', { label: 'Arjun Desai' }); await hr.selectOption('#in-component', { label: 'Incentive' });
  await hr.fill('#in-amount', '6000'); await hr.fill('#in-note', 'Production incentive, October');
  await hr.getByTestId('inputs').getByRole('button', { name: 'Add', exact: true }).click(); await expect(hr.getByTestId('inputs')).toContainText('Production incentive');
  await hr.selectOption('#hold-employee', { label: 'Sunita Joshi' }); await hr.fill('#hold-reason', 'Revised pay letter not signed yet');
  await hr.getByRole('button', { name: 'Hold pay' }).click(); await expect(hr.getByTestId('holds')).toContainText('Revised pay letter');
  await shot(hr, '11-run-inputs', async () => {});
  await hr.getByRole('button', { name: 'Freeze inputs' }).click(); await expect(hr.getByText(/Inputs are frozen for/)).toBeVisible();
  await hr.getByRole('button', { name: 'Calculate', exact: true }).click(); await expect(hr.getByTestId('run-net')).toBeVisible();
  await shot(hr, '12-run-calculated', async () => {});
  await hr.locator('tr[data-employee="Kavya Patil"]').getByRole('button', { name: 'Kavya Patil' }).click();
  await hr.locator('tr[data-line="CONVEYANCE"]').getByRole('button', { name: 'Explain' }).click();
  await shot(hr, '13-explain', async () => { await expect(hr.getByTestId('explain-CONVEYANCE')).toContainText('conveyance'); });

  // The owner accepts Deepa's missing bank account and PAN, approves, pays and releases.
  await owner.reload(); await nav(owner, 'Payroll');
  await owner.getByRole('button', { name: `Open ${monthLabel()}` }).click();
  const exceptions = owner.getByTestId('exceptions');
  const buttons = exceptions.getByRole('button', { name: 'Accept…' });
  await expect(buttons).toHaveCount(2);   // Deepa: no bank account, no PAN
  for (let left = 2; left > 0; left--) {
    await exceptions.locator('li.exception.blocking').filter({ has: owner.getByRole('button', { name: 'Accept…' }) }).first().getByRole('button', { name: 'Accept…' }).click();
    await owner.fill('#reason-Accept', 'Paid by cheque this month; details follow');
    await owner.locator('form:has(#reason-Accept)').getByRole('button', { name: 'Accept', exact: true }).click();
    await expect(buttons).toHaveCount(left - 1);
  }
  await shot(owner, '14-exceptions', async () => { await exceptions.scrollIntoViewIfNeeded(); });
  await owner.getByRole('button', { name: 'Approve' }).click();
  await owner.getByRole('dialog').getByRole('button', { name: 'Approve payroll' }).click();
  await expect(owner.getByText(/Approved, to pay/).first()).toBeVisible();
  await shot(owner, '15-approved', async () => { await owner.evaluate(() => window.scrollTo(0, 0)); });
  await owner.getByRole('button', { name: 'Mark paid…' }).click(); await owner.fill('#paid-ref', 'HDFC-BULK-004417');
  await owner.getByRole('button', { name: 'Mark paid', exact: true }).click();
  await owner.getByRole('button', { name: 'Release payslips' }).click(); await expect(owner.getByText('Done').first()).toBeVisible();

  await shot(kavya, '16-my-payslip', async () => {
    await kavya.reload(); await nav(kavya, 'My pay'); await kavya.getByTestId('my-payslip').first().click();
    await kavya.locator('tr[data-line="BASIC"]').getByRole('button', { name: 'Why' }).click();
    await expect(kavya.getByTestId('explain-BASIC')).toBeVisible();
  });
  await shot(hr, '17-wages', async () => { await payroll(hr, 'Wages'); await expect(hr.locator('main')).toContainText('Kavya Patil'); });

  // Tax declarations: HR opens the window; Kavya declares and submits.
  await hr.reload(); await nav(hr, 'Tax declarations');
  await hr.getByRole('button', { name: 'Open declarations…' }).click();
  await hr.fill('#tw-declare-from', isoDay(-1)); await hr.fill('#tw-declare-until', withinTaxYear(20));
  await shot(hr, '18-tax-window', async () => {});
  await Promise.all([
    hr.waitForResponse((r) => r.url().includes('/org/payroll/tax/window') && r.request().method() === 'PUT' && r.ok()),
    hr.getByRole('button', { name: 'Save', exact: true }).click(),
  ]);
  await kavya.reload(); await nav(kavya, 'My pay'); await tab(kavya, 'Tax declaration');
  const rent = kavya.locator('section[data-head="rent"]');
  await rent.getByRole('button', { name: 'Add' }).click();
  await kavya.fill('#claim-rent-amount', '18000'); await kavya.selectOption('#claim-rent-city', 'Pune');
  await kavya.fill('#claim-rent-landlordName', 'S. Gokhale'); await kavya.fill('#claim-rent-landlordAddress', '7 Prabhat Road, Pune 411004');
  await kavya.fill('#claim-rent-landlordPan', 'ABCPG7777G');
  await rent.getByRole('button', { name: 'Add', exact: true }).last().click(); await expect(kavya.locator('[data-claim="Rent (HRA)"]')).toBeVisible();
  const group80c = kavya.locator('section[data-head="sec80c"]');
  await group80c.getByRole('button', { name: 'Add' }).click();
  await kavya.selectOption('#claim-sec80c-kind', 'ppf'); await kavya.fill('#claim-sec80c-amount', '100000');
  await group80c.getByRole('button', { name: 'Add', exact: true }).last().click();
  await kavya.getByRole('button', { name: 'Submit declaration' }).click();
  await kavya.getByRole('dialog').getByRole('button', { name: 'Submit declaration' }).click();
  await shot(kavya, '19-my-tax', async () => {
    await expect(kavya.getByRole('heading', { name: new RegExp(`Tax declaration ${taxYear().label}`) })).toContainText('Submitted');
  });
  await shot(hr, '20-tax-overview', async () => {
    await hr.reload(); await nav(hr, 'Tax declarations'); await expect(hr.locator('tr[data-employee="Kavya Patil"]')).toBeVisible();
  });
});
