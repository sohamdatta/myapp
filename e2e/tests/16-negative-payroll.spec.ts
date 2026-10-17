import { test, expect, type Page } from '@playwright/test';
import { OWNER, HR, signIn, nav, tab, isoDay, api, expectRefused, errorOn, expectNoConsoleErrors, shot } from './helpers';

// Negative cases for payroll: setup values, formulas, tables, structures and pay groups; pay
// revisions; and the run's state machine, maker-checker, locks and what employees may see.

const MEERA = 'meera.iyer@mail.test';

async function runs(page: Page) {
  await nav(page, 'Payroll');
  for (const back of [/register$/, /^← All runs$/]) {
    const button = page.getByRole('button', { name: back });
    if (await button.isVisible()) await button.click();
  }
  await tab(page, 'Runs');
}

function firstOf(offset: number): string {
  const [y, m] = isoDay(0).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1 + offset, 1)).toISOString().slice(0, 10);
}

test.describe.serial('negative: payroll', () => {
  let owner: Page;
  let hr: Page;
  let december: string;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
    await hr?.context().close();
  });

  test('employer settings refuse a bad PAN, TAN, IFSC, account and numbers', async ({ browser }) => {
    hr = await signIn(browser, HR);
    await nav(hr, 'Payroll');
    await tab(hr, 'Setup');
    const save = async (expected: string | RegExp) => {
      await hr.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(errorOn(hr)).toContainText(expected);
    };
    await hr.fill('#pe-pan', 'AAACG1234');
    await save('The PAN should look like ABCDE1234F');
    await hr.fill('#pe-pan', 'AAACG1234K');
    await hr.fill('#pe-tan', 'BLR12345E');
    await save('The TAN should look like ABCD12345E');
    await shot(hr, 'neg-50-bad-tan');
    await hr.fill('#pe-tan', 'BLRG12345E');
    await hr.fill('#pe-ifsc', 'HDFC000123');
    await save('IFSC should be 11 characters');
    await hr.fill('#pe-ifsc', 'HDFC0000123');
    await hr.fill('#pe-debit', '5020-0012');
    await save('6 to 20 digits');
    await hr.fill('#pe-debit', '50200012345678');
    await hr.fill('#pe-variance', 'ten');
    await save('must be a number');
    await shot(hr, 'neg-51-not-a-number', true);
    await hr.fill('#pe-variance', '10');
    await hr.fill('#pe-large', '25,000.5.0');
    await save('must be a number');
    await hr.reload();
    await expect(hr.locator('.sidebar')).toBeVisible();

    await expectRefused(hr, 'PUT', '/org/payroll/settings', [400], { proration: 'lunar' });
    await expectRefused(hr, 'POST', '/org/payroll/registrations', [400], { statute: 'gst', state: null, number: '29ABCDE1234F1Z5' });
    await expectRefused(hr, 'POST', '/org/payroll/registrations', [400], { statute: 'pt', state: null, number: 'PT-1' });
    await expectRefused(hr, 'POST', '/org/payroll/registrations', [400], { statute: 'pf', state: null, number: '  ' });
    await expectRefused(hr, 'POST', '/org/payroll/registrations', [409], { statute: 'pf', state: null, number: 'KNBNG0012345000' });
  });

  test('components refuse bad codes, duplicates, mid-month dates, months already paid and bad formulas', async () => {
    await nav(hr, 'Payroll');
    await tab(hr, 'Setup');
    await tab(hr, 'Components');
    await hr.getByRole('button', { name: 'Add a component' }).click();
    const add = async (expected: string | RegExp) => {
      await hr.getByRole('button', { name: 'Add component' }).click();
      await expect(errorOn(hr)).toContainText(expected);
    };
    await add(/name of up to 60 characters/);
    await hr.fill('#pc-name', 'Night allowance');
    await hr.fill('#pc-formula', 'BASIC * 0.1');
    await add(/code is 2 to 30 capital letters/);
    await hr.fill('#pc-code', 'BASIC');
    await add(/already|exists/i);
    await hr.fill('#pc-code', 'NIGHT');
    await hr.fill('#pc-from', `${firstOf(1).slice(0, 8)}15`);
    await add('A change applies from the first of a month');
    await hr.fill('#pc-from', firstOf(-1)); // October is paid
    await add(/approved up to|already paid|approved/i);
    await shot(hr, 'neg-52-component-into-paid-month', true);
    await hr.fill('#pc-from', firstOf(2));
    await hr.fill('#pc-formula', '');
    await add('Type the formula');
    await hr.fill('#pc-formula', 'NIGHTS * 200');
    await add(/No component called NIGHTS|Unknown/);
    await shot(hr, 'neg-53-unknown-component', true);
    await hr.selectOption('#pc-type', 'fixed');
    await hr.fill('#pc-fixed', '-500');
    await add('cannot be negative');
    await hr.fill('#pc-fixed', 'five hundred');
    await add(/as a number/);
    await hr.getByRole('button', { name: 'Cancel' }).first().click();
    await expect(hr.locator('tr[data-component="NIGHT"]')).toHaveCount(0);

    // Check, on screen: the position of a mistake is shown.
    await hr.getByRole('button', { name: 'Add a component' }).click();
    await hr.fill('#pc-formula', 'BASIC * (0.1');
    await hr.getByRole('button', { name: 'Check' }).click();
    await expect(hr.getByRole('status')).toContainText(/bracket is not closed/i);
    await shot(hr, 'neg-54-formula-bracket', true);
    await hr.fill('#pc-formula', 'basic * 0.1');
    await hr.getByRole('button', { name: 'Check' }).click();
    await expect(hr.getByRole('status')).toContainText(/capitals|Unknown name/);
    await hr.getByRole('button', { name: 'Cancel' }).first().click();

    // Many more formulas, straight to the checker.
    const bad: [string, RegExp][] = [
      ['', /empty/],
      ['BASIC +', /ends too soon|Expected/],
      ['BASIC ** 2', /Unexpected|Expected/],
      ['sqrt(BASIC)', /Unknown function/],
      ['if(BASIC, 1, 2)', /yes-or-no|yes or no|Expected/],
      ['if(location.metro, 1, "x")', /same kind/],
      ['location.metro', /must give an amount/],
      ['designation', /must give an amount/],
      ['table(meal_card, location.state)', /table needs the table's name in quotes/],
      ['ytd(2)', /ytd needs a component/],
      ['location.', /cannot end with a dot/],
      ['"open quote', /not closed/],
      ['BASIC > "five"', /Cannot compare|compares numbers only/],
      ['1,000 * 2', /Unexpected|Expected/],
      ['x'.repeat(2001), /at most 2,000 characters/],
      ['('.repeat(25) + '1' + ')'.repeat(25), /nested too deeply/],
      ['case(location.metro, 1)', /case needs pairs/],
      ['UNKNOWN_THING * 2', /No component called/],
    ];
    const wrong: string[] = [];
    for (const [formula, words] of bad) {
      const r = await api(hr, 'POST', '/org/payroll/formula/check', { formula });
      if (r.body.ok !== false || !words.test(String(r.body.message))) wrong.push(`${formula.slice(0, 40)} → ${JSON.stringify(r.body).slice(0, 160)}`);
    }
    expect(wrong, 'formulas not refused as expected').toEqual([]);
    // Statutory components keep their working.
    const setup = (await api(hr, 'GET', '/org/payroll/setup')).body;
    const pf = setup.components.find((c: any) => c.code === 'PF_EE');
    await expectRefused(hr, 'DELETE', `/org/payroll/components/${pf.id}`, [409]);
  });

  test('tables, structures and pay groups refuse bad names, keys, loops and days', async () => {
    const t = async (body: unknown, words: RegExp) => {
      const r = await expectRefused(hr, 'POST', '/org/payroll/tables', [400, 409], body);
      expect(r.body.message).toMatch(words);
    };
    await t({ name: 'Meal Card', keyLabel: 'State', rows: [{ key: 'KA', value: 1 }] }, /lower-case/);
    await t({ name: 'meal_card', keyLabel: 'State', rows: [{ key: 'KA', value: 1 }] }, /already|called/);
    await t({ name: 'shift_pay', keyLabel: 'Shift', rows: [{ key: 'Night', value: 1 }, { key: 'night', value: 2 }] }, /twice/);
    await t({ name: 'shift_pay', keyLabel: 'Shift', rows: [{ key: 'Night', value: 'lots' }] }, /not a number/);
    await t({ name: 'shift_pay', keyLabel: 'Shift', rows: [{ key: '', value: 1 }] }, /needs a key/);

    // On screen: a structure whose formulas loop is refused, naming the loop.
    await nav(hr, 'Payroll');
    await tab(hr, 'Setup');
    await tab(hr, 'Structures');
    await hr.getByRole('button', { name: 'Add a structure' }).click();
    await hr.getByRole('button', { name: 'Add structure' }).click();
    await expect(errorOn(hr)).toContainText(/name of up to 60/);
    await hr.fill('#ss-name', 'Loop');
    await hr.fill('#sl-formula-0', 'HRA * 2');
    await hr.getByRole('button', { name: 'Add a component' }).click();
    await hr.locator('#sl-code-1').selectOption('HRA');
    await hr.fill('#sl-formula-1', 'BASIC / 2');
    await hr.getByRole('button', { name: 'Add structure' }).click();
    await expect(errorOn(hr)).toContainText(/loop/i);
    await shot(hr, 'neg-55-structure-loop', true);
    await hr.getByRole('button', { name: 'Remove' }).first().click();
    await hr.getByRole('button', { name: 'Remove' }).first().click();
    await hr.getByRole('button', { name: 'Add structure' }).click();
    await expect(errorOn(hr)).toContainText('at least one component');
    await hr.getByRole('button', { name: 'Cancel' }).first().click();

    const staff = (await api(hr, 'GET', '/org/payroll/setup')).body.structures.find((s: any) => s.name === 'Staff');
    await expectRefused(hr, 'PUT', `/org/payroll/structures/${staff.id}`, [400, 409],
      { name: 'Staff', basis: 'ctc', validFrom: firstOf(-1), balancing: 'SPECIAL', lines: staff.lines });
    await expectRefused(hr, 'PUT', `/org/payroll/structures/${staff.id}`, [400],
      { name: 'Staff', basis: 'ctc', validFrom: firstOf(2), balancing: 'BONUS', lines: staff.lines });
    await expectRefused(hr, 'PUT', `/org/payroll/structures/${staff.id}`, [400],
      { name: 'Staff', basis: 'ctc', validFrom: firstOf(2), balancing: 'SPECIAL', lines: [...staff.lines, { code: 'GROSS_STUFF' }] });
    await expectRefused(hr, 'PUT', `/org/payroll/structures/${staff.id}`, [400],
      { name: 'Staff', basis: 'gross', validFrom: firstOf(2), balancing: 'SPECIAL', lines: staff.lines }); // CTC_MONTHLY in a gross structure
    await expectRefused(hr, 'POST', '/org/payroll/structures', [400], { name: 'Barter', basis: 'goats', lines: [{ code: 'BASIC' }] });

    await tab(hr, 'Pay groups');
    await hr.getByRole('button', { name: 'Add a pay group' }).click();
    const save = async (expected: string | RegExp) => {
      await hr.getByRole('button', { name: 'Save pay group' }).click();
      await expect(errorOn(hr)).toContainText(expected);
    };
    await save(/name of up to 60/);
    await hr.fill('#pg-name', 'Weekly');
    await hr.fill('#pg-cutoff', '29');
    await save('cut-off is a day from 1 to 28');
    await shot(hr, 'neg-56-pay-group-cutoff');
    await hr.fill('#pg-cutoff', '25');
    await hr.fill('#pg-pay', '32');
    await save('pay day from 1 to 31');
    await hr.fill('#pg-pay', '30.5');
    await save('whole number');
    await hr.fill('#pg-pay', '30');
    await hr.fill('#pg-name', 'Monthly staff');
    await save(/already|exists/i);
    await hr.getByRole('button', { name: 'Cancel' }).first().click();
  });

  test('pay revisions refuse no amount, absurd amounts, dates before joining or in a paid month', async () => {
    const list = (await api(hr, 'GET', '/org/people/employees?status=all')).body;
    const priya = list.find((e: any) => e.legalName === 'Priya Shah').id;
    const nina = list.find((e: any) => e.legalName === 'Nina Paul')?.id;
    const staff = (await api(hr, 'GET', '/org/payroll/setup')).body.structures.find((s: any) => s.name === 'Staff').id;
    await nav(hr, 'People');
    await tab(hr, 'Employees');
    await hr.locator('tr', { hasText: 'Priya Shah' }).getByRole('button', { name: 'Priya Shah', exact: true }).click();
    await tab(hr, 'Pay');
    await hr.getByRole('button', { name: 'Revise pay' }).click();
    await hr.fill('#pay-amount', '');
    await hr.fill('#pay-reason', 'Annual revision');
    await expect(hr.getByRole('button', { name: 'Preview' })).toBeDisabled();
    await hr.fill('#pay-amount', '999999999999');
    await hr.getByRole('button', { name: 'Preview' }).click();
    await expect(errorOn(hr)).toContainText('looks too large');
    await shot(hr, 'neg-57-pay-too-large', true);
    await hr.fill('#pay-amount', '300000');
    await hr.fill('#pay-from', firstOf(-1)); // October, approved and paid
    await hr.getByRole('button', { name: 'Preview' }).click();
    await expect(hr.getByTestId('pay-preview')).toBeVisible(); // the preview itself is allowed
    await hr.getByRole('button', { name: /^Save from/ }).click();
    await expect(errorOn(hr)).toContainText(/approved up to|arrears/i);
    await shot(hr, 'neg-58-pay-into-paid-month', true);
    await hr.getByRole('button', { name: 'Cancel' }).first().click();

    await expectRefused(hr, 'POST', `/org/people/employees/${priya}/pay`, [400], { validFrom: '2001-01-01', structureId: staff, annualAmount: 300000, reason: 'Before joining' });
    await expectRefused(hr, 'POST', `/org/people/employees/${priya}/pay`, [400], { validFrom: firstOf(2), structureId: staff, annualAmount: -5, reason: 'Negative' });
    await expectRefused(hr, 'POST', `/org/people/employees/${priya}/pay`, [400], { validFrom: firstOf(2), structureId: staff, annualAmount: 300000, reason: '' });
    await expectRefused(hr, 'POST', `/org/people/employees/${priya}/pay`, [400], { validFrom: firstOf(2), structureId: '00000000-0000-0000-0000-000000000000', annualAmount: 300000, reason: 'Ghost structure' });
    await expectRefused(hr, 'POST', `/org/people/employees/${priya}/pay/overrides`, [400], { componentCode: 'HRA', monthlyAmount: null, validFrom: firstOf(1), reason: 'x' });
    await expectRefused(hr, 'POST', `/org/people/employees/${priya}/pay/overrides`, [400], { componentCode: 'HRA', monthlyAmount: 1000, validFrom: firstOf(1), reason: '' });
    await expectRefused(hr, 'POST', `/org/people/employees/${priya}/pay/overrides`, [400], { componentCode: 'HRA', monthlyAmount: 1000, validFrom: firstOf(2), validTo: firstOf(1), reason: 'Backwards' });
    await expectRefused(hr, 'POST', `/org/people/employees/${priya}/pay/overrides`, [400], { componentCode: 'PF_EE', monthlyAmount: 1, validFrom: firstOf(1), reason: 'Less PF please' });
    await expectRefused(hr, 'PUT', `/org/people/employees/${priya}/pay/profile`, [400], { taxRegime: 'flat' });
    if (nina) await expectRefused(hr, 'POST', `/org/people/employees/${nina}/pay`, [400, 409], { validFrom: firstOf(2), structureId: staff, annualAmount: 700000, reason: 'After leaving' });
  });

  test('a run refuses the wrong order of steps, inputs after freezing and holds without reasons', async () => {
    const group = (await api(hr, 'GET', '/org/payroll/runs')).body.groups[0];
    await expectRefused(hr, 'POST', '/org/payroll/runs', [400], { groupId: group.id, period: `${firstOf(1).slice(0, 8)}15` });
    await runs(hr);
    await hr.getByRole('button', { name: `Start ${group.nextLabel}` }).click();
    await expect(hr.getByTestId('inputs')).toBeVisible();
    december = (await api(hr, 'GET', '/org/payroll/runs')).body.groups[0].open.id;
    // A later month cannot open while this one is open; the same month cannot open twice.
    await expectRefused(hr, 'POST', '/org/payroll/runs', [409], { groupId: group.id, period: firstOf(3) }, 'payroll.earlier_open');
    await expectRefused(hr, 'POST', '/org/payroll/runs', [409], { groupId: group.id, period: group.nextPeriod });
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/calculate`, [409], undefined, 'payroll.state');
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/approve`, [403, 409]);
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/release`, [409]);

    // Inputs: amount, component and person checked.
    const people = (await api(hr, 'GET', `/org/payroll/runs/${december}`)).body.people;
    const meera = people.find((p: any) => p.name === 'Meera Iyer');
    const harish = people.find((p: any) => p.name === 'Harish Rao');
    await hr.selectOption('#in-employee', { label: 'Harish Rao' });
    await hr.fill('#in-amount', '0');
    await hr.getByTestId('inputs').getByRole('button', { name: 'Add', exact: true }).click();
    await expect(errorOn(hr)).toContainText('above zero');
    await hr.fill('#in-amount', 'a lot');
    await hr.getByTestId('inputs').getByRole('button', { name: 'Add', exact: true }).click();
    await expect(errorOn(hr)).toContainText('must be a number');
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/inputs`, [400], { employmentId: harish.employmentId, componentCode: 'BASIC', amount: 100 });
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/inputs`, [400], { employmentId: '00000000-0000-0000-0000-000000000000', componentCode: 'BONUS', amount: 100 });
    if (!meera) {
      // Meera's last day was before December: she is not in the group's run.
      const gone = (await api(hr, 'GET', '/org/people/employees?status=all')).body.find((e: any) => e.legalName === 'Meera Iyer');
      await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/inputs`, [400], { employmentId: gone.id, componentCode: 'BONUS', amount: 100 });
    }
    // Holds need a reason.
    await hr.selectOption('#hold-employee', { label: 'Rahul Bose' });
    await hr.fill('#hold-reason', 'no');
    await expect(hr.getByRole('button', { name: 'Hold pay' })).toBeDisabled();
    await shot(hr, 'neg-59-hold-needs-reason', true);
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/hold`, [400], { employmentId: harish.employmentId, reason: '' });

    await hr.getByRole('button', { name: 'Freeze inputs' }).click();
    await expect(hr.getByText(/Inputs are frozen for/)).toBeVisible();
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/freeze`, [409], undefined, 'payroll.state');
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/inputs`, [409], { employmentId: harish.employmentId, componentCode: 'BONUS', amount: 100 }, 'payroll.not_draft');
    await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/reopen`, [400], { reason: '' });
  });

  test('maker-checker: whoever calculates cannot approve, even the Org Admin; blocking exceptions stop approval', async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    // The Org Admin calculates December herself, so she may not approve it.
    const calc = await api(owner, 'POST', `/org/payroll/runs/${december}/calculate`);
    expect(calc.status, JSON.stringify(calc.body)).toBe(200);
    await runs(owner);
    await owner.getByRole('button', { name: /^Open / }).first().click();
    await expect(owner.getByText('You calculated this run; someone else approves it')).toBeVisible();
    await expect(owner.getByRole('button', { name: 'Approve' })).toHaveCount(0);
    await expectRefused(owner, 'POST', `/org/payroll/runs/${december}/approve`, [409]);
    await shot(owner, 'neg-60-own-calculation-no-approve', true);

    // Harish recalculates; now the Org Admin may approve, once the blocking exceptions are dealt with.
    const re = await api(hr, 'POST', `/org/payroll/runs/${december}/calculate`);
    expect(re.status, JSON.stringify(re.body)).toBe(200);
    const run = (await api(owner, 'GET', `/org/payroll/runs/${december}`)).body;
    const blocking = run.exceptions.filter((x: any) => x.level === 'blocking' && !x.acceptedAt);
    if (blocking.length > 0) await expectRefused(owner, 'POST', `/org/payroll/runs/${december}/approve`, [409], undefined, 'payroll.blocked');
    const acceptable = blocking.find((x: any) => x.acceptable);
    if (acceptable) {
      await expectRefused(owner, 'POST', `/org/payroll/runs/${december}/exceptions/${acceptable.id}/accept`, [400], { reason: '' });
      await expectRefused(hr, 'POST', `/org/payroll/runs/${december}/exceptions/${acceptable.id}/accept`, [403], { reason: 'I prepared it, I accept it' });
    }
    const fixable = run.exceptions.find((x: any) => !x.acceptable && x.level === 'blocking');
    if (fixable) await expectRefused(owner, 'POST', `/org/payroll/runs/${december}/exceptions/${fixable.id}/accept`, [409], { reason: 'Let it through' }, 'payroll.not_acceptable');
    // Not paid, not released, no bank file before approval.
    await expectRefused(owner, 'POST', `/org/payroll/runs/${december}/paid`, [409], { paidOn: isoDay(0), reference: 'UTR1' }, 'payroll.state');
    await expectRefused(owner, 'POST', `/org/payroll/runs/${december}/release`, [409]);
    await expectRefused(owner, 'GET', `/org/payroll/runs/${december}/outputs/bank`, [409]);
    await expectRefused(owner, 'POST', `/org/payroll/runs/${december}/send-back`, [400], { reason: '' });
  });

  test('approved months are locked, and employees see only released payslips', async ({ browser }) => {
    const all = (await api(owner, 'GET', '/org/payroll/runs')).body.runs;
    const october = all[all.length - 1].id;
    await expectRefused(owner, 'POST', `/org/payroll/runs/${october}/reopen`, [409], { reason: 'Change October' });
    await expectRefused(owner, 'POST', `/org/payroll/runs/${october}/calculate`, [409]);
    await expectRefused(owner, 'POST', `/org/payroll/runs/${october}/hold`, [409], { employmentId: (await api(owner, 'GET', `/org/payroll/runs/${october}`)).body.people[0].employmentId, reason: 'Too late now' }, 'payroll.locked');
    await expectRefused(owner, 'POST', `/org/payroll/runs/${october}/paid`, [409], { paidOn: isoDay(0), reference: 'AGAIN' });
    await expectRefused(owner, 'POST', `/org/payroll/runs/${october}/approve`, [409]);

    // December is calculated but not released: nobody sees it in My pay.
    const decSlip = (await api(owner, 'GET', `/org/payroll/runs/${december}`)).body.people.find((p: any) => p.name === 'Priya Shah');
    const priya = await signIn(browser, 'priya.shah@mail.test');
    const mine = (await api(priya, 'GET', '/org/me/pay')).body.payslips;
    expect(mine.some((s: any) => s.id === decSlip.id)).toBe(false);
    await expectRefused(priya, 'GET', `/org/me/pay/${decSlip.id}`, [404]);
    await expectRefused(priya, 'GET', `/org/me/pay/${decSlip.id}/pdf`, [404]);
    await expectRefused(priya, 'GET', '/org/me/pay/not-a-uuid', [400, 404]);
    await nav(priya, 'My pay');
    await expect(priya.getByText('December')).toHaveCount(0);
    await shot(priya, 'neg-61-unreleased-payslip-hidden');
    await priya.context().close();
  });
});
