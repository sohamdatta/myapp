import { test, expect, type Page } from '@playwright/test';
import { OWNER, HR, signIn, nav, tab, isoDay, expectNoConsoleErrors, shot } from './helpers';

// Employee records: departments, adding existing staff with identifiers that
// are shown as last four only, revealing a full value with a reason, a dated
// job change with its history, and recording an exit.

const PAN = 'ABCPE1234F';
const AADHAAR = '345678901238'; // valid Verhoeff check digit
const ACCOUNT = '123456789012';

test.describe.serial('people', () => {
  let owner: Page;

  test.beforeAll(async ({ browser }) => {
    owner = await signIn(browser, OWNER);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
  });

  test('set up departments, one inside another', async () => {
    await nav(owner, 'People');
    await tab(owner, 'Departments');
    await owner.fill('#dep-name', 'Engineering');
    await owner.click('text=Add department');
    await expect(owner.locator('td', { hasText: 'Engineering' })).toBeVisible();
    await owner.fill('#dep-name', 'Web');
    await owner.selectOption('#dep-parent', { label: 'Engineering' });
    await owner.click('text=Add department');
    await expect(owner.locator('tr', { hasText: 'Web' })).toContainText('Engineering');
    await shot(owner, '31-departments');

    // Engineering cannot be put inside its own sub-department.
    await owner.locator('tr', { hasText: 'Engineering' }).first().getByRole('button', { name: 'Change' }).click();
    await owner.locator('.detail-row select').selectOption({ label: 'Web' });
    await owner.locator('.detail-row').getByRole('button', { name: 'Save', exact: true }).click();
    await expect(owner.locator('.detail-row .error')).toContainText('cannot sit inside');
  });

  test('add the Org Admin, then the HR Admin with identifiers, linked to their accounts', async () => {
    await tab(owner, 'Employees');
    await expect(owner.getByText('No employee records yet')).toBeVisible();

    await owner.click('text=Add employee');
    await owner.fill('#ne-legal', 'Ananya Sen');
    await owner.fill('#ne-joining', isoDay(-400));
    await owner.fill('#ne-designation', 'Managing director');
    await owner.selectOption('#ne-user', { label: OWNER });
    await owner.click('button:has-text("Add employee")');
    await expect(owner.getByRole('heading', { name: /Ananya Sen/ })).toBeVisible();
    await owner.getByRole('button', { name: '← All employees' }).click();

    await owner.click('text=Add employee');
    await owner.fill('#ne-legal', 'Harish Rao');
    await owner.fill('#ne-dob', '1992-03-14');
    await owner.selectOption('#ne-gender', 'male');
    await owner.fill('#ne-mobile', '98765 43210');
    await owner.fill('#ne-joining', isoDay(-200));
    await owner.fill('#ne-designation', 'HR executive');
    await owner.selectOption('#ne-department', { label: 'Engineering' });
    await owner.selectOption('#ne-manager', { label: 'Ananya Sen, Managing director' });
    await owner.fill('#ne-code', 'E001'); // the code attendance already uses for this user
    await owner.selectOption('#ne-user', { label: HR });
    await owner.fill('#ne-pan', 'ABCCE1234F'); // a company's PAN, not a person's
    await owner.fill('#ne-aadhaar', AADHAAR);
    await owner.fill('#ne-uan', '100200300400');
    await owner.fill('#ne-account', ACCOUNT);
    await owner.fill('#ne-account-again', '123456789013');
    await owner.fill('#ne-ifsc', 'SBIN0001234');
    await owner.click('button:has-text("Add employee")');
    await expect(owner.locator('form .error')).toContainText('account numbers are different');

    await owner.fill('#ne-account-again', ACCOUNT);
    await owner.click('button:has-text("Add employee")');
    await expect(owner.locator('form .error')).toContainText('fourth letter');
    await owner.fill('#ne-pan', PAN);
    await shot(owner, '32-add-employee', true);
    await owner.click('button:has-text("Add employee")');
    await expect(owner.getByRole('heading', { name: /Harish Rao/ })).toBeVisible();
  });

  test('identifiers show as last four, and the full PAN needs a reason', async () => {
    const page = owner.locator('.panels');
    await expect(page).toContainText('•••• 234F');
    await expect(page).toContainText('•••• 1238'); // Aadhaar: last four only, and no Show button for it
    await expect(page).toContainText('•••• 9012');
    await expect(page).not.toContainText(PAN);
    await expect(page).not.toContainText(ACCOUNT);
    await expect(page).toContainText('Reports to');
    await expect(page).toContainText('Ananya Sen');

    const pan = owner.locator('.fact', { hasText: 'PAN' });
    await pan.getByRole('button', { name: 'Show' }).click();
    await owner.fill('#why-pan', 'x');
    await pan.getByRole('button', { name: 'Show' }).click();
    await expect(pan.locator('.error')).toBeVisible();
    await owner.fill('#why-pan', 'Checking the Form 16 details');
    await pan.getByRole('button', { name: 'Show' }).click();
    await expect(pan).toContainText(PAN);
    await shot(owner, '33-employee-profile', true);
    await pan.getByRole('button', { name: 'Hide' }).click();
    await expect(pan).not.toContainText(PAN);
  });

  test('a dated job change keeps the history', async () => {
    await owner.click('text=Change job');
    await owner.fill('#job-designation', 'HR manager');
    await owner.selectOption('#job-department', { label: 'Engineering › Web' });
    await owner.fill('#job-reason', 'Promotion');
    await shot(owner, '34-change-job');
    await owner.click('text=Save change');
    await expect(owner.locator('.section-head .muted')).toContainText('HR manager');

    await tab(owner, 'Job history');
    await expect(owner.locator('tbody tr')).toHaveCount(2);
    await expect(owner.locator('tbody tr').first()).toContainText('Promotion');
    await shot(owner, '35-job-history');
  });

  test('the same PAN cannot be given to a second person', async () => {
    await owner.getByRole('button', { name: '← All employees' }).click();
    await owner.click('text=Add employee');
    await owner.fill('#ne-legal', 'Kiran Pal');
    await owner.fill('#ne-joining', isoDay(-90));
    await owner.fill('#ne-designation', 'Developer');
    await owner.fill('#ne-pan', PAN);
    await owner.click('button:has-text("Add employee")');
    await expect(owner.locator('form .error')).toContainText('already recorded');
    await owner.fill('#ne-pan', '');
    await owner.fill('#ne-pan-pending', 'Applied for on 2 Oct');
    await owner.click('button:has-text("Add employee")');
    await expect(owner.getByRole('heading', { name: /Kiran Pal/ })).toBeVisible();
    await expect(owner.locator('.panels')).toContainText('Applied for on 2 Oct');
  });

  test('an exit started from the record moves the employee to Left', async () => {
    await owner.getByRole('button', { name: 'Start an exit…' }).click();
    // The record sends HR to Exits with the employee chosen.
    await expect(owner.locator('#se-employee option:checked')).toContainText('Kiran Pal');
    await owner.fill('#se-day', isoDay(-1));
    await shot(owner, '36-record-exit');
    await owner.getByRole('button', { name: 'Start the exit' }).click();
    await expect(owner.getByRole('heading', { name: /Kiran Pal/ })).toBeVisible();

    await nav(owner, 'People');
    await expect(owner.locator('tbody tr')).toHaveCount(2);
    await owner.getByRole('button', { name: 'Left', exact: true }).click();
    await expect(owner.locator('tbody tr')).toHaveCount(1);
    await expect(owner.locator('tbody tr')).toContainText('Kiran Pal');

    await nav(owner, 'Audit log');
    for (const action of ['department.created', 'employee.added', 'employee.revealed', 'employee.job_changed', 'exit.accepted']) {
      await expect(owner.locator('code', { hasText: action }).first()).toBeVisible();
    }
  });
});
