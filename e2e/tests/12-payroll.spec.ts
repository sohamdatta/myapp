import { test, expect, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import { OWNER, HR, PASSWORD, signIn, nav, tab, isoDay, expectNoSidewaysScroll, expectNoConsoleErrors, shot } from './helpers';

// Payroll, from setup to a payslip on a phone. The Org Admin makes the HR Admin (Harish) the Payroll
// Operator and moves the team to a Bengaluru office. Harish sets up the employer, registrations, a
// meal card read from a table, the structure and the pay group, and enters salaries — except his own,
// which the Org Admin sets and then revises with a preview. Harish runs October: inputs, a hold,
// freeze, calculate, Explain. He cannot approve his own calculation; the Org Admin (Ananya) accepts
// the exceptions with reasons, approves, downloads the files and marks it paid; payslips go out.
// November follows, so Meera's payslip can say why her pay changed.

const MEERA = 'meera.iyer@mail.test';

/** "October 2026" for the month `offset` months from this one, in India time. */
function monthLabel(offset = 0): string {
  const [y, m] = isoDay(0).split('-').map(Number);
  const d = new Date(Date.UTC(y, m - 1 + offset, 1));
  return d.toLocaleString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });
}
const firstOfMonth = (offset = 0) => {
  const [y, m] = isoDay(0).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1 + offset, 1)).toISOString().slice(0, 10);
};

/** Clicks something that downloads a file; keeps a copy beside the screenshots and returns its text. */
async function download(page: Page, button: string, keepAs?: string): Promise<string> {
  const [file] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: button, exact: true }).click()]);
  const path = await file.path();
  if (keepAs && process.env.E2E_SHOTS) fs.copyFileSync(path, `${process.env.E2E_SHOTS}/${keepAs}`);
  return fs.readFileSync(path, 'latin1');
}

/** Opens an employee's record under People and its Pay tab. */
async function openPay(page: Page, name: string) {
  await nav(page, 'People');
  const back = page.getByRole('button', { name: '← All employees' });
  if (await back.isVisible()) await back.click();
  await page.locator('tr', { hasText: name }).getByRole('button', { name, exact: true }).click();
  await expect(page.getByRole('heading', { name: new RegExp(name) })).toBeVisible();
  await tab(page, 'Pay');
}

async function setPay(page: Page, name: string, amount: string, from: string, reason: string) {
  await openPay(page, name);
  await page.getByRole('button', { name: 'Set pay' }).click();
  await page.fill('#pay-from', from);
  await page.fill('#pay-amount', amount);
  await page.fill('#pay-reason', reason);
  await page.getByRole('button', { name: 'Preview' }).click();
  await expect(page.getByTestId('pay-preview')).toBeVisible();
  await page.getByRole('button', { name: /^Save from/ }).click();
  await expect(page.getByTestId('pay-history')).toContainText(reason);
}

/** Payroll → Runs, from wherever in Payroll the page is. */
async function runs(page: Page) {
  await nav(page, 'Payroll');
  for (const back of [/register$/, /^← All runs$/]) {
    const button = page.getByRole('button', { name: back });
    if (await button.isVisible()) await button.click();
  }
  await tab(page, 'Runs');
}

/** Accepts, as the approver, every open blocking exception that may be accepted. */
async function acceptAll(page: Page, reasons: Record<string, string>) {
  const exceptions = page.getByTestId('exceptions');
  await expect(exceptions).toBeVisible();
  const buttons = exceptions.getByRole('button', { name: 'Accept…' });
  for (let left = await buttons.count(); left > 0; left--) {
    const row = exceptions.locator('li.exception.blocking').filter({ has: page.getByRole('button', { name: 'Accept…' }) }).first();
    const code = (await row.getAttribute('data-code')) || '';
    await row.getByRole('button', { name: 'Accept…' }).click();
    await page.fill('#reason-Accept', reasons[code] || 'Checked with HR; fixing it next month');
    await page.locator('form:has(#reason-Accept)').getByRole('button', { name: 'Accept', exact: true }).click();
    await expect(page.locator('#reason-Accept')).toHaveCount(0);
    await expect(buttons).toHaveCount(left - 1);
  }
}

test.describe.serial('payroll', () => {
  let owner: Page;
  let hr: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
    await hr?.context().close();
  });

  test('the Org Admin makes Harish the Payroll Operator and moves the team to Bengaluru', async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    await nav(owner, 'User management');
    const hrRow = owner.locator('tr', { hasText: HR });
    await hrRow.getByText('Edit roles').click();
    await owner.check("#user-panel label:has-text('Payroll Operator') input");
    await owner.locator('#user-panel').getByRole('button', { name: 'Save roles' }).click();
    await expect(hrRow.locator('td').nth(1)).toContainText('Payroll Operator');

    // Professional tax and the labour welfare fund follow the state of the work location.
    await nav(owner, 'Attendance');
    await tab(owner, 'Setup');
    await owner.getByRole('button', { name: 'Add a work location' }).click();
    await owner.fill('#loc-name', 'Bengaluru office');
    await owner.fill('#loc-state', 'KA');
    await owner.click('text=Save location');
    await expect(owner.locator('td', { hasText: 'Bengaluru office' })).toBeVisible();
    await tab(owner, 'People');
    for (const who of ['Ananya Sen', 'Harish Rao', 'Meera Iyer', 'Nina Paul', 'Priya Shah']) {
      await owner.getByLabel(`Select ${who}`).check();
    }
    await owner.selectOption('#as-location', { label: 'Bengaluru office' });
    await owner.selectOption('#as-shift', { index: 1 });
    await owner.click('text=Assign to 5 selected');
    await expect(owner.locator('td', { hasText: 'Bengaluru office' })).toHaveCount(5);
  });

  test('Harish sets up the employer, its registrations and the statutory rules in force', async ({ browser }) => {
    hr = await signIn(browser, HR);
    await nav(hr, 'Payroll');
    await expect(hr.getByText('awaiting the payroll consultant’s sign-off')).toBeVisible();
    await tab(hr, 'Setup');

    await hr.fill('#pe-name', 'Globex Technologies Private Limited');
    await hr.fill('#pe-pan', 'AAACG1234K');
    await hr.fill('#pe-tan', 'BLRG12345E');
    await hr.fill('#pe-address', '4th floor, 80 Feet Road, Koramangala, Bengaluru 560034');
    await hr.fill('#pe-debit', '50200012345678');
    await hr.fill('#pe-ifsc', 'HDFC0000123');
    await hr.getByRole('checkbox', { name: /Bengaluru office/ }).check();
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
    await shot(hr, 'payroll-01-employer', true);

    const regs = hr.getByTestId('registrations');
    for (const [statute, number] of [
      ['pf', 'KNBNG0012345000'],
      ['esi', '53000123450001001'],
      ['pt', 'PTR-KA-1234567'],
      ['lwf', 'LWF-KA-7788'],
    ]) {
      await hr.selectOption('#reg-statute', statute);
      await hr.fill('#reg-number', number);
      await regs.getByRole('button', { name: 'Add', exact: true }).click();
      await expect(regs).toContainText(number);
    }
    await regs.scrollIntoViewIfNeeded();
    await shot(hr, 'payroll-02-registrations');

    await tab(hr, 'Statutory rules');
    await expect(hr.locator('tr[data-rule="pt-KA"]')).toContainText('Awaiting sign-off');
    await expect(hr.locator('tr[data-rule="pf-IN"]')).toContainText('12%');
    await shot(hr, 'payroll-03-rules', true);
  });

  test('a state-wise meal card from a table, added to the structure, and everyone in the pay group', async () => {
    await tab(hr, 'Tables');
    await hr.getByRole('button', { name: 'Add a table' }).click();
    await hr.fill('#vt-name', 'meal_card');
    await hr.fill('#vt-key', 'State');
    const rows = [
      ['KA', '2200'],
      ['MH', '2200'],
      ['GA', '1800'],
    ];
    for (let i = 0; i < rows.length; i++) {
      if (i > 0) await hr.getByRole('button', { name: 'Add a row' }).click();
      await hr.fill(`#vt-k-${i}`, rows[i][0]);
      await hr.fill(`#vt-v-${i}`, rows[i][1]);
    }
    await hr.getByRole('button', { name: 'Save table' }).click();
    await expect(hr.locator('code', { hasText: 'meal_card' })).toBeVisible();

    await tab(hr, 'Components');
    await shot(hr, 'payroll-04-components', true);
    await hr.getByRole('button', { name: 'Add a component' }).click();
    await hr.fill('#pc-name', 'Meal card');
    await hr.fill('#pc-code', 'MEAL_CARD');
    await hr.fill('#pc-from', firstOfMonth());
    await hr.fill('#pc-formula', 'table("meal_card", location.state');
    await hr.getByRole('button', { name: 'Check' }).click();
    await expect(hr.getByRole('status').filter({ hasText: 'at character' })).toBeVisible(); // the bracket is missing
    await hr.fill('#pc-formula', 'table("meal_card", location.state)');
    await hr.getByRole('button', { name: 'Check' }).click();
    await expect(hr.getByText('Looks right. Uses location.state.')).toBeVisible();
    await hr.getByLabel('Counts for ESI').uncheck();
    await hr.getByLabel('Taxable').uncheck();
    await hr.fill('#pc-ledger', '5140 Staff welfare');
    await hr.fill('#pc-position', '35');
    await shot(hr, 'payroll-05-component-form', true);
    await hr.getByRole('button', { name: 'Add component' }).click();
    await expect(hr.locator('tr[data-component="MEAL_CARD"]')).toBeVisible();

    // The structure gets the meal card from this month; Special allowance still balances to the CTC.
    await tab(hr, 'Structures');
    await hr.locator('[data-structure="Staff"]').getByRole('button', { name: 'Change' }).click();
    await hr.fill('#ss-from', firstOfMonth());
    await hr.getByRole('button', { name: 'Add a component' }).click();
    const last = hr.locator('.structure-line').last();
    await last.locator('select').selectOption('MEAL_CARD');
    await shot(hr, 'payroll-06-structure', true);
    await hr.getByRole('button', { name: /^Save from/ }).click();
    await expect(hr.locator('[data-structure="Staff"]')).toContainText('Meal card');
    await shot(hr, 'payroll-07-structures');

    await tab(hr, 'Pay groups');
    const group = hr.locator('[data-group="Monthly staff"]');
    await group.getByRole('button', { name: 'People' }).click();
    await group.getByRole('button', { name: /^Add them all to/ }).click();
    await expect(group.getByTestId('members')).toContainText('Rahul Bose');
    await shot(hr, 'payroll-08-pay-group', true);
  });

  test('Harish enters salaries with a preview, but cannot see or set his own', async () => {
    await setPay(hr, 'Ananya Sen', '3000000', firstOfMonth(), 'Board resolution 2026');
    await setPay(hr, 'Priya Shah', '252000', firstOfMonth(), 'Pay on record');
    await setPay(hr, 'Nina Paul', '600000', isoDay(0), 'Pay on joining');

    // Meera's offer: the preview shows the month before it is saved.
    await openPay(hr, 'Meera Iyer');
    await hr.getByRole('button', { name: 'Set pay' }).click();
    await hr.fill('#pay-from', isoDay(0));
    await hr.fill('#pay-amount', '1200000');
    await hr.getByRole('button', { name: 'Preview' }).click();
    await expect(hr.getByTestId('pay-after')).toContainText('Meal card');
    await shot(hr, 'payroll-09-set-pay-preview', true);
    await hr.getByRole('button', { name: /^Save from/ }).click();
    await expect(hr.getByTestId('pay-now')).toContainText('Basic');
    await hr.getByLabel('Income tax regime').selectOption('new');
    await shot(hr, 'payroll-10-employee-pay', true);

    // His own record has no Pay tab; nobody records their own pay.
    await nav(hr, 'My profile');
    await expect(hr.getByRole('tab', { name: 'Pay' })).toHaveCount(0);
  });

  test('the Org Admin sets Harish’s pay and revises it from next month, seeing the change first', async () => {
    await setPay(owner, 'Harish Rao', '720000', firstOfMonth(), 'Pay on record');
    await owner.getByRole('button', { name: 'Revise pay' }).click();
    await owner.fill('#pay-amount', '840000');
    await owner.fill('#pay-reason', 'Annual revision 2026');
    await owner.getByRole('button', { name: 'Preview' }).click();
    await expect(owner.getByTestId('pay-before')).toBeVisible();
    await expect(owner.getByTestId('pay-after')).toContainText('+₹');
    await shot(owner, 'payroll-11-revision-preview', true);
    await owner.getByRole('button', { name: /^Save from/ }).click();
    await expect(owner.getByTestId('pay-history').locator('tbody tr')).toHaveCount(2);
  });

  test('Harish runs the month: inputs, a hold, freeze, calculate and Explain', async () => {
    await nav(hr, 'Payroll');
    await tab(hr, 'Runs');
    await hr.getByRole('button', { name: `Start ${monthLabel()}` }).click();
    await expect(hr.getByTestId('inputs')).toBeVisible();

    await hr.selectOption('#in-employee', { label: 'Meera Iyer' });
    await hr.selectOption('#in-component', { label: 'Bonus' });
    await hr.fill('#in-amount', '50000');
    await hr.fill('#in-note', 'Joining bonus, offer letter clause 4');
    await hr.getByTestId('inputs').getByRole('button', { name: 'Add', exact: true }).click();
    await expect(hr.getByTestId('inputs')).toContainText('Joining bonus');
    await hr.selectOption('#in-employee', { label: 'Nina Paul' });
    await hr.selectOption('#in-component', { label: 'Incentive' });
    await hr.fill('#in-amount', '5000');
    await hr.fill('#in-note', 'Referral incentive');
    await hr.getByTestId('inputs').getByRole('button', { name: 'Add', exact: true }).click();
    await expect(hr.getByTestId('inputs')).toContainText('Referral incentive');

    // Rahul has no salary letter yet: his pay is held, so it cannot block everyone else's.
    await hr.selectOption('#hold-employee', { label: 'Rahul Bose' });
    await hr.fill('#hold-reason', 'Salary letter not signed yet');
    await hr.getByRole('button', { name: 'Hold pay' }).click();
    await expect(hr.getByTestId('holds')).toContainText('Salary letter not signed yet');
    await shot(hr, 'payroll-12-run-inputs', true);

    await hr.getByRole('button', { name: 'Freeze inputs' }).click();
    await expect(hr.getByText(/Inputs are frozen for/)).toBeVisible();
    await hr.getByRole('button', { name: 'Calculate', exact: true }).click();
    await expect(hr.getByTestId('run-net')).toBeVisible();
    await expect(hr.getByText('You calculated this run; someone else approves it')).toBeVisible();
    await expect(hr.getByRole('button', { name: 'Approve' })).toHaveCount(0);
    await expect(hr.locator('[data-code="large_one_time"]')).toContainText('Meera Iyer');
    await expect(hr.locator('[data-code="not_assigned"]')).toContainText('Pay held');
    await shot(hr, 'payroll-13-run-calculated', true);

    // Explain: every line says how it was worked out.
    await hr.locator('tr[data-employee="Meera Iyer"]').getByRole('button', { name: 'Meera Iyer' }).click();
    await expect(hr.getByTestId('payslip')).toBeVisible();
    await hr.locator('tr[data-line="BASIC"]').getByRole('button', { name: 'Explain' }).click();
    await expect(hr.getByTestId('explain-BASIC')).toContainText('CTC_MONTHLY');
    await hr.locator('tr[data-line="MEAL_CARD"]').getByRole('button', { name: 'Explain' }).click();
    await expect(hr.getByTestId('explain-MEAL_CARD')).toContainText('meal_card');
    await hr.locator('tr[data-line="PF_EE"]').getByRole('button', { name: 'Explain' }).click();
    await expect(hr.getByTestId('explain-PF_EE')).toBeVisible();
    await shot(hr, 'payroll-14-explain', true);
    await hr.getByRole('button', { name: /register$/ }).click();
  });

  test('the Org Admin accepts the exceptions with reasons, approves, and pays from the bank file', async () => {
    await nav(owner, 'Payroll');
    await owner.getByRole('button', { name: `Open ${monthLabel()}` }).click();
    await expect(owner.getByRole('button', { name: 'Approve' })).toHaveCount(0); // blocking exceptions first
    await acceptAll(owner, {
      missing_bank: 'Paid by cheque this month; account details to follow',
      missing_pan: 'PAN applied for; acknowledgement on file',
    });
    await shot(owner, 'payroll-15-exceptions-accepted', true);
    await owner.getByRole('button', { name: 'Approve' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Approve payroll' }).click();
    await expect(owner.getByText(/Approved, to pay/).first()).toBeVisible();

    const register = await download(owner, 'Salary register', 'payroll-register.csv');
    expect(register).toContain('Meera Iyer');
    const journal = await download(owner, 'Accounting journal', 'payroll-journal.csv');
    expect(journal).toContain('Salary payable');
    const bank = await download(owner, 'HDFC bank file', 'payroll-hdfc-bank-file.csv');
    expect(bank).toContain('HDFC0000123');
    expect(bank).not.toContain('Rahul Bose'); // held pay stays out of the bank file
    await shot(owner, 'payroll-16-approved', true);

    await owner.getByRole('button', { name: 'Mark paid…' }).click();
    await owner.fill('#paid-ref', 'HDFC-BULK-000781');
    await owner.getByRole('button', { name: 'Mark paid', exact: true }).click();
    await owner.getByRole('button', { name: 'Release payslips' }).click();
    await expect(owner.getByText('Done').first()).toBeVisible();
    await owner.locator('tr[data-employee="Meera Iyer"]').getByRole('button', { name: 'Meera Iyer' }).click();
    await download(owner, 'Payslip PDF', 'payroll-payslip-meera.pdf');
    await shot(owner, 'payroll-17-released', true);
  });

  test('November: a full month for Meera, and Harish’s revision', async () => {
    await runs(hr);
    await hr.getByRole('button', { name: `Start ${monthLabel(1)}` }).click();
    await hr.selectOption('#hold-employee', { label: 'Rahul Bose' });
    await hr.fill('#hold-reason', 'Salary letter not signed yet');
    await hr.getByRole('button', { name: 'Hold pay' }).click();
    await hr.getByRole('button', { name: 'Freeze inputs' }).click();
    await hr.getByRole('button', { name: 'Calculate', exact: true }).click();
    await expect(hr.getByTestId('run-net')).toBeVisible();

    await runs(owner);
    await owner.getByRole('button', { name: `Open ${monthLabel(1)}` }).click();
    await expect(owner.locator('tr[data-employee="Meera Iyer"]')).toBeVisible();
    // Nina's last day is ten days after the exit was started (10-exit): before next month, she is not in it; late in a
    // month it falls in next month, and she is rightly paid for those days.
    const nina = owner.locator('tr[data-employee="Nina Paul"]');
    if (isoDay(10) < firstOfMonth(1)) await expect(nina).toHaveCount(0);
    else await expect(nina).toBeVisible();
    await acceptAll(owner, {});
    await owner.getByRole('button', { name: 'Approve' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Approve payroll' }).click();
    await owner.getByRole('button', { name: 'Mark paid…' }).click();
    await owner.fill('#paid-ref', 'HDFC-BULK-000802');
    await owner.getByRole('button', { name: 'Mark paid', exact: true }).click();
    await owner.getByRole('button', { name: 'Release payslips' }).click();
    await expect(owner.getByText('Done').first()).toBeVisible();
    await runs(owner);
    await shot(owner, 'payroll-18-runs', true);
  });

  test('Meera reads her payslip and why her pay changed, on a phone', async ({ browser }) => {
    const phone = await signIn(browser, MEERA, PASSWORD, 390);
    await nav(phone, 'My pay');
    await expect(phone.getByTestId('my-payslip')).toHaveCount(2);
    await shot(phone, 'payroll-19-my-pay-phone', true);
    await phone.getByTestId('my-payslip').first().click();
    const why = phone.getByTestId('why-changed');
    await expect(why).toContainText('Why pay changed from');
    await expect(why).toContainText('Bonus');
    await expect(phone.getByTestId('net')).toBeVisible();
    await expectNoSidewaysScroll(phone);
    await shot(phone, 'payroll-20-payslip-phone', true);
    await phone.context().close();
  });
});
