import { test, expect, type Page } from '@playwright/test';
import { OWNER, HR, signIn, nav, tab, isoDay, api, expectRefused, errorOn, expectNoConsoleErrors, shot } from './helpers';

// Negative cases for employee records, identifiers, departments, joiners, imports, setup lists
// and exits: each form's refusals, duplicates, impossible dates, and the rules about yourself.

const MEERA = 'meera.iyer@mail.test';

async function employees(page: Page): Promise<any[]> {
  return (await api(page, 'GET', '/org/people/employees?status=all')).body;
}
const idOf = (list: any[], name: string) => list.find((e) => e.legalName === name || e.preferredName === name || e.name === name)?.id;

async function openEmployee(page: Page, name: string) {
  await nav(page, 'People');
  const back = page.getByRole('button', { name: '← All employees' });
  if (await back.isVisible()) await back.click();
  await tab(page, 'Employees');
  await page.locator('tr', { hasText: name }).getByRole('button', { name, exact: true }).click();
  await expect(page.getByRole('heading', { name: new RegExp(name) })).toBeVisible();
}

test.describe.serial('negative: people, joiners and exits', () => {
  let owner: Page;
  let hr: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
    await hr?.context().close();
  });

  test('adding an employee refuses bad identifiers, impossible dates and duplicates', async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    await nav(owner, 'People');
    await tab(owner, 'Employees');
    await owner.click('text=Add employee');
    const submit = async (expected: string | RegExp) => {
      await owner.click('button:has-text("Add employee")');
      await expect(owner.locator('form .error')).toContainText(expected);
    };
    await submit(/name|Enter/i);
    await owner.fill('#ne-legal', 'Test Person');
    await submit(/joining|designation|Enter/i);
    await owner.fill('#ne-joining', isoDay(-10));
    await owner.fill('#ne-designation', 'Tester');
    await owner.fill('#ne-dob', isoDay(-365 * 10));
    await submit('under 14');
    await shot(owner, 'neg-30-employee-under-14', true);
    await owner.fill('#ne-dob', '1990-01-01');
    await owner.fill('#ne-pan', 'ABCDE12345');
    await submit(/PAN/);
    await owner.fill('#ne-pan', 'ABCPT1234Z');
    await owner.fill('#ne-aadhaar', '1234 5678 9012');
    await submit(/Aadhaar/i);
    await shot(owner, 'neg-31-bad-aadhaar', true);
    await owner.fill('#ne-aadhaar', '');
    await owner.fill('#ne-uan', '12345');
    await submit(/UAN/);
    await owner.fill('#ne-uan', '');
    await owner.fill('#ne-mobile', '12345');
    await submit(/mobile|phone|digits/i);
    await owner.fill('#ne-mobile', '');
    await owner.fill('#ne-account', '1234567890');
    await owner.fill('#ne-account-again', '1234567890');
    await owner.fill('#ne-ifsc', 'SBIN123');
    await submit(/IFSC/);
    await owner.fill('#ne-ifsc', '');
    await owner.fill('#ne-account-again', '');
    await owner.fill('#ne-account', '');
    await owner.selectOption('#ne-type', 'fixed_term');
    await submit('contract end date');
    await shot(owner, 'neg-32-fixed-term-no-end', true);
    await owner.selectOption('#ne-type', 'permanent');
    await owner.fill('#ne-code', 'E001'); // Harish's code
    await submit(/already|in use|exists/i);
    await owner.getByRole('button', { name: 'Cancel' }).first().click();
    expect((await employees(owner)).some((e) => e.legalName === 'Test Person')).toBe(false);

    // Sent anyway: a second record for a user who has one, an unknown gender and type, a joining date far ahead.
    const users = (await api(owner, 'GET', '/org/users')).body;
    const hrUser = users.find((u: any) => u.email === HR);
    const base = { legalName: 'Shadow Harish', joiningDate: isoDay(-5), designation: 'Copy' };
    await expectRefused(owner, 'POST', '/org/people/employees', [400, 409], { ...base, membershipId: hrUser.id });
    await expectRefused(owner, 'POST', '/org/people/employees', [400, 409], { ...base, gender: 'robot' });
    await expectRefused(owner, 'POST', '/org/people/employees', [400, 409], { ...base, employmentType: 'volunteer' });
    await expectRefused(owner, 'POST', '/org/people/employees', [400, 409], { ...base, joiningDate: 'next week' });
    await expectRefused(owner, 'POST', '/org/people/employees', [400, 409], { ...base, departmentId: 'not-an-id' });
  });

  test('a job change refuses dates before joining, reporting to oneself and changes after leaving', async () => {
    const list = await employees(owner);
    const harish = idOf(list, 'Harish Rao');
    const kiran = idOf(list, 'Kiran Pal');
    await openEmployee(owner, 'Harish Rao');
    await owner.click('text=Change job');
    await owner.fill('#job-from', '2000-01-01');
    await owner.fill('#job-reason', 'Back-dated change');
    await owner.click('text=Save change');
    await expect(owner.locator('form .error')).toContainText('before the joining date');
    await owner.getByRole('button', { name: 'Cancel' }).first().click();
    await expectRefused(owner, 'POST', `/org/people/employees/${harish}/job`, [400, 409],
      { effectiveFrom: isoDay(1), designation: 'HR manager', managerId: harish, reason: 'Loop' });
    if (kiran) {
      await expectRefused(owner, 'POST', `/org/people/employees/${kiran}/job`, [400, 409],
        { effectiveFrom: isoDay(1), designation: 'Back again', reason: 'After leaving' });
    }
  });

  test('identifiers, bank accounts and revealing refuse bad values and thin reasons', async () => {
    const harish = idOf(await employees(owner), 'Harish Rao');
    await openEmployee(owner, 'Harish Rao');
    await owner.getByRole('button', { name: 'Set or correct' }).click();
    for (const [kind, value, expected] of [
      ['pan', 'ABCD1234E', /PAN/],
      ['aadhaar', '0000 0000 0000', /Aadhaar/i],
      ['uan', 'ABCDEFGHIJKL', /UAN/],
    ]) {
      await owner.selectOption('#idf-kind', kind);
      await owner.fill('#idf-value', value);
      await owner.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(owner.locator('form .error')).toContainText(expected);
    }
    await owner.selectOption('#idf-kind', 'uan');
    await owner.fill('#idf-value', '');
    await owner.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(owner.locator('form .error')).toContainText(/Enter/);
    await owner.getByRole('button', { name: 'Cancel' }).first().click();

    await owner.getByRole('button', { name: 'New account' }).click();
    await owner.fill('#bk-account', '12345');
    await owner.fill('#bk-again', '12345');
    await owner.fill('#bk-ifsc', 'HDFC0000123');
    await owner.click('text=Save account');
    await expect(owner.locator('form .error')).toContainText(/account/i);
    await owner.fill('#bk-account', '50100200300400');
    await owner.fill('#bk-again', '50100200300401');
    await owner.click('text=Save account');
    await expect(owner.locator('form .error')).toContainText('different');
    await shot(owner, 'neg-33-bank-numbers-differ');
    await owner.fill('#bk-again', '50100200300400');
    await owner.fill('#bk-ifsc', 'HDFC123');
    await owner.click('text=Save account');
    await expect(owner.locator('form .error')).toContainText(/IFSC/);
    await owner.getByRole('button', { name: 'Cancel' }).first().click();

    await expectRefused(owner, 'POST', `/org/people/employees/${harish}/reveal`, [400, 409], { field: 'aadhaar', reason: 'Need the full number' });
    await expectRefused(owner, 'POST', `/org/people/employees/${harish}/reveal`, [400, 409], { field: 'pan', reason: 'why' });
    await expectRefused(owner, 'PUT', `/org/people/employees/${harish}/identifiers`, [400, 409], { kind: 'passport', value: 'K1234567' });
    await expectRefused(owner, 'PUT', `/org/people/employees/${harish}/bank`, [400, 409], { accountNumber: '50100200300400', ifsc: 'HDFC0000123', effectiveFrom: 'someday' });
  });

  test('departments refuse an empty or repeated name', async () => {
    await nav(owner, 'People');
    const back = owner.getByRole('button', { name: '← All employees' });
    if (await back.isVisible()) await back.click();
    await tab(owner, 'Departments');
    await owner.fill('#dep-name', '');
    await owner.click('text=Add department');
    await expect(errorOn(owner)).toContainText(/name|Enter/i);
    await owner.fill('#dep-name', 'engineering'); // same as Engineering, ignoring case
    await owner.click('text=Add department');
    await expect(errorOn(owner)).toContainText(/already|exists/i);
    await shot(owner, 'neg-34-department-exists');
    await owner.fill('#dep-name', 'D'.repeat(200));
    await owner.click('text=Add department');
    await expect(errorOn(owner)).toBeVisible();
  });

  test('a joiner refuses a missing or far-past joining date, a contract ending first, and a taken email', async ({ browser }) => {
    hr = await signIn(browser, HR);
    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Add joiner' }).click();
    const save = async (button: string, expected: string | RegExp) => {
      await hr.getByRole('button', { name: button }).click();
      await expect(hr.locator('form .error')).toContainText(expected);
    };
    await expect(hr.getByRole('button', { name: 'Save and invite' })).toBeDisabled(); // no email yet
    await hr.fill('#nj-name', 'Late Joiner');
    await hr.fill('#nj-designation', 'Analyst');
    await save('Save, invite later', /joining date/i);
    await hr.fill('#nj-joining', isoDay(-120));
    await save('Save, invite later', '90 days ago');
    await shot(hr, 'neg-35-joiner-too-far-back', true);
    await hr.fill('#nj-joining', isoDay(10));
    await hr.selectOption('#nj-type', 'fixed_term');
    await save('Save, invite later', /contract ends|contract/i);
    if (await hr.locator('#nj-contract').isVisible()) {
      await hr.fill('#nj-contract', isoDay(5));
      await save('Save, invite later', 'cannot end before the joining date');
    }
    await hr.selectOption('#nj-type', 'permanent');
    await hr.fill('#nj-email', 'not an email');
    await save('Save and invite', /email/i);
    await hr.fill('#nj-email', MEERA);
    await save('Save and invite', /already a user|already employed|joining/i);
    await shot(hr, 'neg-36-joiner-email-taken', true);
    await hr.getByRole('button', { name: 'Cancel' }).first().click();
    await expect(hr.getByText('Late Joiner')).toHaveCount(0);
  });

  test('import refuses a file in which no row can be taken', async () => {
    await nav(hr, 'People');
    await tab(hr, 'Import');
    await hr.setInputFiles('#import-file', { name: 'staff.csv', mimeType: 'text/csv', buffer: Buffer.from('Code,Name,Joined\n,,\n') });
    const next = hr.getByRole('button', { name: /Check|Preview|Next/ });
    if (await next.count()) await next.first().click();
    await expect(hr.getByText(/No row can be imported|Choose|refused|cannot/i).first()).toBeVisible();
    await hr.reload();
    await expect(hr.locator('.sidebar')).toBeVisible();
    await nav(hr, 'People');
    await tab(hr, 'Import');
    await hr.setInputFiles('#import-file', { name: 'photo.png', mimeType: 'image/png', buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 0, 0, 0]) });
    await expect(errorOn(hr)).toBeVisible();
  });

  test('setup lists refuse bad code series, short policies and empty letters', async () => {
    await nav(owner, 'People');
    const back = owner.getByRole('button', { name: '← All employees' });
    if (await back.isVisible()) await back.click();
    await tab(owner, 'Setup');
    const series = owner.getByRole('tab', { name: /Employee codes|Codes/ });
    await expect(series.first()).toBeVisible();
    {
      await series.first().click();
      await owner.getByRole('button', { name: /Add a series|Add series/ }).first().click();
      await owner.fill('#ser-name', 'Bad series');
      await owner.fill('#ser-prefix', 'AB#');
      await owner.getByRole('button', { name: /^Save|Add series$/ }).last().click();
      await expect(errorOn(owner)).toContainText(/letters, digits|token/i);
      await shot(owner, 'neg-38-code-series-bad-prefix', true);
      await owner.fill('#ser-prefix', 'AB-');
      await owner.fill('#ser-digits', '11');
      await owner.getByRole('button', { name: /^Save|Add series$/ }).last().click();
      await expect(errorOn(owner)).toContainText('1 to 10');
      await owner.getByRole('button', { name: 'Cancel' }).first().click();
    }
    const policies = owner.getByRole('tab', { name: /Policies/ });
    await expect(policies.first()).toBeVisible();
    {
      await policies.first().click();
      await owner.getByRole('button', { name: /Add a policy|Add policy/ }).first().click();
      await owner.fill('#pol-name', 'Tiny');
      await owner.fill('#pol-body', 'Too short.');
      await owner.getByRole('button', { name: /^Save|Add policy$/ }).last().click();
      await expect(errorOn(owner)).toContainText('20 to 50,000 characters');
      await shot(owner, 'neg-39-policy-too-short', true);
      await owner.getByRole('button', { name: 'Cancel' }).first().click();
    }
    const letters = owner.getByRole('tab', { name: /Letters/ });
    await expect(letters.first()).toBeVisible();
    {
      await letters.first().click();
      await owner.getByRole('button', { name: /Add a letter|Add letter/ }).first().click();
      await owner.fill('#lt-name', 'Empty letter');
      await owner.fill('#lt-body', '');
      await owner.getByRole('button', { name: /^Save|Add letter$/ }).last().click();
      await expect(errorOn(owner)).toContainText(/Write the letter/);
      await owner.getByRole('button', { name: 'Cancel' }).first().click();
    }
  });

  test('exits: no second exit, no exit for yourself, no day before joining, no resigning twice', async ({ browser }) => {
    const list = await employees(owner);
    const meera = idOf(list, 'Meera Iyer');
    const harish = idOf(list, 'Harish Rao');
    const ananya = idOf(list, 'Ananya Sen');
    const kiran = idOf(list, 'Kiran Pal');
    // Meera already has an accepted exit.
    await expectRefused(owner, 'POST', '/org/people/exits', [400, 409], { employmentId: meera, kind: 'termination', lastWorkingDay: isoDay(5), note: 'Again' });
    // Her own: resign from My exit instead.
    await expectRefused(owner, 'POST', '/org/people/exits', [400, 403, 409], { employmentId: ananya, kind: 'resignation', lastWorkingDay: isoDay(30) });
    await expectRefused(owner, 'POST', '/org/people/exits', [400, 409], { employmentId: harish, kind: 'sabbatical', lastWorkingDay: isoDay(30) });
    // A past last day is allowed (recording someone who has already gone), but not one before they joined.
    await expectRefused(owner, 'POST', '/org/people/exits', [400, 409], { employmentId: harish, kind: 'termination', lastWorkingDay: '2001-01-01', note: 'Before joining' });
    await expectRefused(owner, 'POST', '/org/people/exits', [400, 409], { employmentId: harish, kind: 'death', lastWorkingDay: isoDay(5), dateOfDeath: isoDay(3) });
    await expectRefused(owner, 'POST', '/org/people/exits', [400, 409], { employmentId: harish, kind: 'absconding', lastWorkingDay: isoDay(0) });
    if (kiran) await expectRefused(owner, 'POST', '/org/people/exits', [400, 409], { employmentId: kiran, kind: 'termination', lastWorkingDay: isoDay(5) });

    // On screen: Harish's exit form refuses a past last day.
    await openEmployee(owner, 'Harish Rao');
    await owner.getByRole('button', { name: 'Start an exit…' }).click();
    await owner.selectOption('#se-kind', 'termination');
    const day = owner.locator('#se-day, #exit-last-day').first();
    await day.fill('2001-01-01');
    if (await owner.locator('#se-reason').isVisible()) await owner.selectOption('#se-reason', { index: 1 });
    await owner.getByRole('button', { name: 'Start the exit' }).click();
    await expect(errorOn(owner)).toContainText('before the joining date');
    await shot(owner, 'neg-40-exit-before-joining', true);
    await owner.getByRole('button', { name: 'Cancel' }).first().click();

    // Meera, with her resignation accepted, cannot resign again or withdraw after the fact by request.
    const meeraPage = await signIn(browser, MEERA);
    await expectRefused(meeraPage, 'POST', '/org/me/exit', [400, 409], { reasonCategory: 'better_opportunity', note: 'Again', requestedLastDay: isoDay(20) });
    await expectRefused(meeraPage, 'POST', '/org/me/exit', [400, 409], { reasonCategory: 'personal', note: 'Past', requestedLastDay: isoDay(-2) });
    const exits = (await api(owner, 'GET', '/org/people/exits?status=all')).body;
    const meeraExit = exits.find((x: any) => x.employmentId === meera || x.name === 'Meera Iyer');
    if (meeraExit) {
      // Her own exit through HR's address shows only what My exit shows her.
      const seen = await api(meeraPage, 'GET', `/org/people/exits/${meeraExit.id}`);
      expect(seen.status).toBe(200);
      expect(seen.body.rehire).toBeUndefined();
      expect(seen.body.canAccept).toBe(false);
      expect(seen.body.canManageTasks).toBe(false);
      await expectRefused(meeraPage, 'POST', `/org/people/exits/${meeraExit.id}/accept`, [403, 404], { lastWorkingDay: isoDay(1) });
      await expectRefused(meeraPage, 'POST', `/org/people/exits/${meeraExit.id}/settlement/approve`, [403, 404]);
      // HR cannot accept an exit twice, nor close it before the last day.
      await expectRefused(owner, 'POST', `/org/people/exits/${meeraExit.id}/accept`, [400, 409], { lastWorkingDay: isoDay(1) });
      await expectRefused(owner, 'POST', `/org/people/exits/${meeraExit.id}/close`, [400, 409], { rehire: 'yes' });
      await expectRefused(owner, 'POST', `/org/people/exits/${meeraExit.id}/recommend`, [400, 403, 409], { lastWorkingDay: isoDay(1) });
      await expectRefused(owner, 'PUT', `/org/people/exits/${meeraExit.id}/legal-hold`, [400, 409], { hold: true, note: 'x' });
      await expectRefused(owner, 'POST', `/org/people/exits/${meeraExit.id}/tasks`, [400, 409], { title: 'T'.repeat(200) });
      await expectRefused(owner, 'POST', `/org/people/exits/${meeraExit.id}/settlement/lines`, [400, 409], { code: 'other', label: '', direction: 'pay', amount: 10 });
      await expectRefused(owner, 'POST', `/org/people/exits/${meeraExit.id}/settlement/lines`, [400, 409], { code: 'other', label: 'Gift', direction: 'sideways', amount: 10 });
      await expectRefused(owner, 'POST', `/org/people/exits/${meeraExit.id}/settlement/lines`, [400, 409], { code: 'other', label: 'Gift', direction: 'pay', amount: -10 });
    }
    await meeraPage.context().close();
  });
});
