import { test, expect, type Page } from '@playwright/test';
import { HR, PASSWORD, ORG, signIn, newPage, nav, tab, isoDay, expectNoSidewaysScroll, expectNoConsoleErrors } from './helpers';

// Setup on screen and the employee import: HR configures a flow for interns
// entirely from People → Setup (a field made required, a field of their own,
// a code series, a message), previews the intern's form, sees it on a new
// intern, then imports existing staff from a CSV with every refused row
// explained and invites one of them to sign in.

const CSV = [
  'Emp Code,Full Name,DOJ,Designation,Dept,Reports To,Email',
  'P100,Priya Shah,01/05/2023,Analyst,Engineering,E001,priya.shah@mail.test',
  'P101,Rahul Bose,2023-07-10,Designer,Design,P100,rahul.bose@mail.test',
  'E001,Someone Else,2023-07-10,Analyst,Engineering,,someone@mail.test',
  'P103,Tanvi Gill,31/02/2024,Analyst,Engineering,,tanvi@mail.test',
].join('\n');

const card = (page: Page, title: string) =>
  page.locator('.section-card').filter({ has: page.locator('.section-title', { hasText: new RegExp(`^${title}$`) }) });

test.describe.serial('setup and import', () => {
  let hr: Page;

  test.beforeAll(async ({ browser }) => {
    hr = await signIn(browser, HR);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await hr?.context().close();
  });

  test('a flow for interns, set up on screen', async () => {
    await nav(hr, 'People');
    await tab(hr, 'Setup');
    await expect(hr.getByText('Not changed yet')).toBeVisible();
    await hr.getByRole('button', { name: 'Add a flow' }).click();
    await hr.fill('#fl-name', 'Interns');
    await hr.locator('.rule-editor').first().getByLabel('Intern', { exact: true }).check();
    await hr.getByRole('button', { name: 'Save flow' }).click();
    // A flow with rules goes before the flow for everyone, so interns get it.
    await expect(hr.locator('.setup-item').first()).toContainText('1. Interns');
    await expect(hr.locator('.setup-item').first()).toContainText('Intern');
    await expect(hr.locator('.setup-item').nth(1)).toContainText('2. Everyone');
    await expect(hr.locator('.hint', { hasText: 'Last changed by hr@globex.test' })).toBeVisible();
  });

  test('a field made required for interns, and a field of their own', async () => {
    await tab(hr, 'Form');
    await hr.selectOption('#form-scope', { label: 'Only the flow Interns' });
    const blood = hr.locator('[data-field="person.blood_group"]');
    await blood.locator('label', { hasText: /^Required$/ }).click();
    // Legal name cannot be anything but required.
    await expect(hr.locator('[data-field="person.legal_name"] input[value="hidden"]')).toBeDisabled();
    await hr.getByRole('button', { name: 'Save 1 change' }).click();
    await expect(hr.getByText('Saved. Joiners added from now on get the new form.')).toBeVisible();

    await hr.getByRole('button', { name: 'Your own fields' }).click();
    await hr.getByRole('button', { name: 'Add a field' }).click();
    await hr.fill('#cf-label', 'College');
    await expect(hr.locator('#cf-key')).toHaveValue('college');
    await hr.selectOption('#cf-section', 'personal');
    await hr.selectOption('#cf-visibility', 'required');
    await hr.locator('.setup-editor .rule-editor').getByLabel('Intern', { exact: true }).check();
    await hr.getByRole('button', { name: 'Save field' }).click();
    await expect(hr.locator('[data-item="College"]')).toContainText('Short text in Personal · required · Employment type intern');

    // The preview shows an intern's form as they will see it.
    await hr.getByRole('button', { name: 'Preview as joiner' }).click();
    await hr.selectOption('#pv-type', 'intern');
    await expect(hr.locator('.phone-frame')).toContainText('Flow: Interns');
    const personal = hr.locator('[data-preview="personal"]');
    await expect(personal).toBeVisible();
    await expect(personal.locator('label', { hasText: /^College$/ })).toBeVisible();
    await expect(personal.locator('label', { hasText: /^Blood group$/ })).toBeVisible();
    await hr.selectOption('#pv-type', 'permanent');
    await expect(hr.locator('.phone-frame')).toContainText('Flow: Everyone');
    await expect(hr.locator('[data-preview="personal"] label', { hasText: /^College/ })).toHaveCount(0);
  });

  test('a code series for interns, and a message reworded', async () => {
    await tab(hr, 'Employee codes');
    await hr.getByRole('button', { name: 'Add a series' }).click();
    await hr.fill('#ser-name', 'Interns');
    await hr.fill('#ser-prefix', 'INT-{YY}-');
    await hr.fill('#ser-digits', '3');
    const yy = isoDay(0).slice(2, 4);
    await expect(hr.getByTestId('series-example')).toHaveText(`INT-${yy}-001`);
    await hr.locator('.setup-editor .rule-editor').getByLabel('Intern', { exact: true }).check();
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.locator('[data-item="Interns"]')).toContainText(`Next code: INT-${yy}-001`);

    await tab(hr, 'Messages');
    const assigned = hr.locator('[data-item="task_assigned"]');
    await assigned.getByRole('button', { name: 'Edit' }).click();
    await hr.fill('#msg-body', '{{task.name}} for {{joiner.name}}');
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.locator('.setup-editor .error')).toContainText('Unknown placeholder: {{task.name}}');
    await hr.fill('#msg-body', '');
    await hr.getByText('Placeholders you can use').click();
    await hr.getByRole('button', { name: '{{task.title}}' }).click();
    await expect(hr.locator('#msg-body')).toHaveValue('{{task.title}}');
    await hr.fill('#msg-body', '{{task.title}} for {{joiner.name}}, please');
    await expect(hr.locator('.setup-editor')).toContainText('Prepare laptop and accounts for Asha Rao, please');
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(assigned).toContainText('for Asha Rao, please');
  });

  test('a new intern follows the interns flow and gets the new field', async () => {
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Add joiner' }).click();
    await hr.fill('#nj-name', 'Uma Iyer');
    await hr.fill('#nj-joining', isoDay(15));
    await hr.selectOption('#nj-type', 'intern');
    await hr.fill('#nj-designation', 'Intern');
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.getByRole('heading', { name: /Uma Iyer/ })).toBeVisible();
    await expect(hr.locator('.panels')).toContainText('Interns');
    const personal = card(hr, 'Personal details');
    await personal.locator('.section-toggle').click();
    await personal.getByRole('button', { name: 'Edit for the joiner' }).click();
    await expect(personal.locator('label', { hasText: /^College$/ })).toBeVisible();
    await expect(personal.locator('label', { hasText: /^Blood group$/ })).toBeVisible();
    await hr.getByRole('button', { name: '← All joiners' }).click();
  });

  test('existing staff imported from a CSV, refused rows explained', async () => {
    await tab(hr, 'Import');
    await hr.locator('#import-file').setInputFiles({ name: 'staff.csv', mimeType: 'text/csv', buffer: Buffer.from(CSV) });
    await expect(hr.locator('#map-legal_name')).toHaveValue('1');
    await expect(hr.locator('#map-manager')).toHaveValue('5');
    await hr.getByRole('button', { name: 'Check every row' }).click();
    await expect(hr.getByRole('heading', { name: '2 ready to import · 2 cannot be used' })).toBeVisible();
    await expect(hr.locator('tr[data-line="4"]')).toContainText('The employee code E001 is already in use');
    await expect(hr.locator('tr[data-line="5"]')).toContainText('is not a date');
    await expect(hr.locator('tr[data-line="3"]')).toContainText('Adds the department Design');
    await hr.getByRole('button', { name: 'Import 2 employees' }).click();
    await expect(hr.getByRole('heading', { name: 'Imported 2 employees; 2 rows left out' })).toBeVisible();

    await tab(hr, 'Employees');
    const row = (name: string) => hr.locator('tbody tr').filter({ has: hr.getByRole('button', { name, exact: true }) });
    await expect(row('Rahul Bose')).toContainText('Priya Shah'); // reports to someone from the same file
    await expect(row('Priya Shah')).toContainText('Harish Rao'); // and to someone already here
    await tab(hr, 'Import');
    await hr.locator('#import-file').setInputFiles({ name: 'staff.csv', mimeType: 'text/csv', buffer: Buffer.from(CSV) });
    await expect(hr.getByText('These are the columns you chose last time')).toBeVisible();
    await hr.getByRole('button', { name: 'Check every row' }).click();
    await expect(hr.getByRole('heading', { name: '0 ready to import · 4 cannot be used' })).toBeVisible();
  });

  test('an imported employee is invited and signs in', async ({ browser }) => {
    const row = hr.locator('tr[data-invitee="Priya Shah"]');
    await row.getByRole('checkbox').check();
    await hr.getByRole('button', { name: 'Invite 1 chosen' }).click();
    const link = await hr.locator('.invite-links .copy-row input').inputValue();
    expect(link).toContain('/?invite=');
    await expect(hr.locator('tr[data-invitee="Priya Shah"]')).toHaveCount(0);

    const priya = await newPage(browser, 390);
    await priya.goto(link);
    await expect(priya.getByText(`Join ${ORG}`)).toBeVisible();
    await priya.fill('#new-password', PASSWORD);
    await priya.fill('#confirm-password', PASSWORD);
    await priya.click('button.primary');
    await expect(priya.locator('button.nav-item', { hasText: 'My leave' })).toBeVisible();
    await expectNoSidewaysScroll(priya);
    await priya.context().close();

    await tab(hr, 'Employees');
    await hr.getByRole('button', { name: 'Priya Shah', exact: true }).click();
    await expect(hr.locator('.panels')).toContainText('priya.shah@mail.test');
  });
});
