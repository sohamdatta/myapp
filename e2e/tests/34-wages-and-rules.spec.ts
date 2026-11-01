import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, OWNER, signIn, nav, tab, expectRefused, expectNoConsoleErrors, expectNoSidewaysScroll, shot } from './helpers';

// The wage check and its what-if, and the state payroll rules, after the story has run. The Org Admin reads the Labour Codes wage
// test for the latest approved month and tries other amounts for one person (nothing is saved). The Super Admin reads the state
// rules, edits a draft, is told a new version cannot be added while the latest is still a draft, and sees the sign-off form ask
// for a name and a date. Nothing is signed off here, so the drafts the other tests rely on stay drafts.

test.describe.serial('wages and payroll rules', () => {
  test.afterAll(async () => {
    expectNoConsoleErrors();
  });

  test('the Org Admin reads the wage check and tries other amounts for one person', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    await nav(owner, 'Payroll');
    await tab(owner, 'Wages');
    await expect(owner.getByRole('heading', { name: 'Wages under the Labour Codes' })).toBeVisible();
    const rows = owner.locator('tr[data-person]');
    await expect(rows.first()).toBeVisible();
    await expect(owner.getByText(/added back to reach half/)).toBeVisible();
    // The share is a percentage of pay, between 0 and 100.
    for (const text of await rows.locator('td:nth-child(4)').allTextContents()) {
      const n = Number(text.replace('%', ''));
      expect(n, `wage share ${text}`).toBeGreaterThan(0);
      expect(n, `wage share ${text}`).toBeLessThanOrEqual(100);
    }
    const payBefore = await rows.filter({ hasNotText: 'Ananya Sen' }).first().locator('td:nth-child(2)').innerText();
    await expectNoSidewaysScroll(owner);
    await shot(owner, 'wages-01-check', true);

    // Her own pay is not hers to try other amounts on.
    await rows.filter({ hasText: 'Ananya Sen' }).getByRole('button', { name: 'What if' }).click();
    await owner.getByLabel('Earning 1').selectOption({ label: 'Basic' });
    await owner.getByLabel('Amount 1').fill('40000');
    await owner.getByRole('button', { name: 'Show the difference' }).click();
    await expect(owner.getByTestId('what-if-result')).toHaveCount(0);
    await expect(owner.getByText('The employee was not found')).toBeVisible();
    await owner.getByRole('button', { name: '← Wages' }).click();

    await rows.filter({ hasNotText: 'Ananya Sen' }).first().getByRole('button', { name: 'What if' }).click();
    await expect(owner.getByRole('heading', { name: /^What if for / })).toBeVisible();
    const submit = owner.getByRole('button', { name: 'Show the difference' });
    await expect(submit).toBeDisabled();   // nothing chosen yet
    await owner.getByLabel('Earning 1').selectOption({ label: 'Basic' });
    await owner.getByLabel('Amount 1').fill('40000');
    await submit.click();
    const result = owner.getByTestId('what-if-result');
    await expect(result).toContainText('Total pay');
    await expect(result).toContainText('Wages for the 50% test');
    await expect(result).toContainText('Employer cost, a year');
    await shot(owner, 'wages-02-what-if', true);

    // Nothing was saved: back on the check, the person's figures are as the run left them.
    await owner.getByRole('button', { name: '← Wages' }).click();
    await expect(rows.filter({ hasNotText: 'Ananya Sen' }).first().locator('td:nth-child(2)')).toHaveText(payBefore);
    await owner.context().close();
  });

  test('a month with no approved run says so, and an Organization user cannot reach the rules', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    await nav(owner, 'Payroll');
    await tab(owner, 'Wages');
    await owner.getByLabel('Month to check').fill('2020-01');
    await expect(owner.getByText(/nothing to check/)).toBeVisible();
    await expect(owner.locator('a.nav-item').filter({ hasText: /^Payroll rules$/ })).toHaveCount(0);
    await expectRefused(owner, 'GET', '/platform/payroll-rules', [403]);
    await owner.context().close();
  });

  test('the Super Admin reads the state rules, edits a draft, and cannot add a version over a draft', async ({ browser }) => {
    const admin = await signIn(browser, SUPER_ADMIN.email, SUPER_ADMIN.password);
    await expect(admin.getByText('Platform console')).toBeVisible();
    await nav(admin, 'Payroll rules');
    const page = admin.getByTestId('payroll-rules');
    await expect(page).toContainText('State payroll rules');
    const ka = page.locator('tr[data-rule^="lwf:KA:"]');
    await expect(ka).toContainText('Karnataka');
    await expect(ka).toContainText('Draft');
    await expect(page.locator('tr[data-rule^="pt:MH:"]')).toContainText('slab');
    await expectNoSidewaysScroll(admin);
    await shot(admin, 'rules-01-list', true);

    // Edit a draft: the source changes, and the figures stay.
    await ka.getByRole('button', { name: 'Edit' }).click();
    const form = admin.getByRole('form', { name: 'Edit rule' });
    await expect(form).toBeVisible();
    const original = await form.getByLabel('Where the figures come from').inputValue();
    await form.getByLabel('Where the figures come from').fill('Karnataka LWF notification, checked against the consultant’s note');
    await form.getByRole('button', { name: 'Save the draft' }).click();
    await expect(form).toHaveCount(0);
    await expect(ka).toContainText('Draft');
    // Opening another rule while one is open shows that rule, not the one before; and the source is put back as it was.
    await ka.getByRole('button', { name: 'Edit' }).click();
    await expect(form.getByLabel('Where the figures come from')).toHaveValue('Karnataka LWF notification, checked against the consultant’s note');
    await page.locator('tr[data-rule^="lwf:MH:"]').getByRole('button', { name: 'Edit' }).click();
    await expect(form.getByLabel('State')).toHaveValue('MH');
    await page.locator('tr[data-rule^="lwf:KA:"]').getByRole('button', { name: 'Edit' }).click();
    await form.getByLabel('Where the figures come from').fill(original);
    await form.getByRole('button', { name: 'Save the draft' }).click();
    await expect(form).toHaveCount(0);

    // A new version cannot be added while the latest is a draft.
    await page.getByRole('button', { name: 'Add a rule' }).click();
    const add = admin.getByRole('form', { name: 'Add a rule' });
    await add.getByLabel('State').selectOption('KA');
    await add.getByLabel('Rule').selectOption('lwf');
    await add.getByLabel(/Applies from/).fill('2031-01');
    await add.getByLabel('Where the figures come from').fill('A later notification');
    await add.getByLabel('Employee pays (₹)').fill('20');
    await add.getByLabel('Employer pays (₹)').fill('40');
    await add.getByLabel('Dec').check();
    await add.getByRole('button', { name: 'Add as a draft' }).click();
    await expect(add).toContainText('still a draft');
    await add.getByRole('button', { name: 'Cancel' }).click();

    // The sign-off form asks for a name and a date; it is not submitted here.
    await ka.getByRole('button', { name: 'Record sign-off' }).click();
    const sign = admin.getByRole('form', { name: 'Record sign-off' });
    await expect(sign.getByRole('button', { name: 'Record sign-off' })).toBeDisabled();
    await sign.getByLabel('Consultant’s name').fill('R. Menon');
    await expect(sign.getByRole('button', { name: 'Record sign-off' })).toBeDisabled();
    await sign.getByLabel('Date they signed it off').fill('2026-10-30');
    await expect(sign.getByRole('button', { name: 'Record sign-off' })).toBeEnabled();
    await shot(admin, 'rules-02-sign-off', true);
    await sign.getByRole('button', { name: 'Cancel' }).click();
    await expect(ka).toContainText('Draft');
    await admin.context().close();
  });
});
