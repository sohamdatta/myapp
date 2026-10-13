import { test, expect, type Page } from '@playwright/test';
import { HR, OWNER, PASSWORD, signIn, nav, tab, isoDay, expectNoSidewaysScroll, expectNoConsoleErrors, shot } from './helpers';

// Exits: an employee resigns on a phone, their manager recommends a last day, HR accepts it with
// the notice not served set against leave, the employee asks to withdraw and HR refuses, and HR
// starts an exit for someone else and changes the exit settings.

const MEERA = 'meera.iyer@mail.test';

test.describe.serial('exit', () => {
  let hr: Page;
  let owner: Page;
  let meera: Page;

  test.beforeAll(async ({ browser }) => {
    hr = await signIn(browser, HR);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await hr?.context().close();
    await owner?.context().close();
    await meera?.context().close();
  });

  test('an employee resigns on a phone and sees their notice', async ({ browser }) => {
    meera = await signIn(browser, MEERA, PASSWORD, 390);
    await nav(meera, 'My exit');
    await expect(meera.getByText('Your notice period is')).toContainText('30 days');
    await meera.getByRole('button', { name: 'Resign…' }).click();
    await meera.selectOption('#resign-reason', 'career');
    await meera.fill('#resign-last-day', isoDay(25));
    await meera.fill('#resign-note', 'A role closer to my family');
    await shot(meera, '81-resign', true);
    await meera.getByRole('button', { name: 'Send my resignation' }).click();
    await expect(meera.getByRole('heading', { name: /Your resignation/ })).toContainText('Waiting for your manager');
    await expectNoSidewaysScroll(meera);
    await shot(meera, '82-resigned', true);
  });

  test('the manager recommends a last day, and HR accepts it with the shortfall set against leave', async ({ browser }) => {
    owner = await signIn(browser, OWNER);   // Meera reports to the Org Admin
    await nav(owner, 'Exits');
    await owner.getByRole('button', { name: 'Meera Iyer' }).click();
    await owner.getByRole('button', { name: 'Recommend' }).click();
    await owner.fill('#rec-day', isoDay(20));
    await expect(owner.getByText('days before the notice ends')).toBeVisible();
    await owner.fill('#rec-note', 'Handover in two weeks is enough');
    await owner.getByRole('button', { name: 'Send to HR' }).click();
    await expect(owner.locator('.section-head h2')).toContainText('Waiting for HR');

    await nav(hr, 'Exits');
    await expect(hr.locator('tr[data-exit="Meera Iyer"]')).toContainText('Waiting for HR');
    await shot(hr, '83-exits-list');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await expect(hr.locator('.panels')).toContainText('A role closer to my family');   // HR reads the employee's own words
    await hr.getByRole('button', { name: 'Accept' }).click();
    await expect(hr.locator('label[for="acc-shortfall"]')).toHaveText('10 days of notice not served');
    await hr.selectOption('#acc-shortfall', 'leave');
    await shot(hr, '84-accept', true);
    await hr.locator('form').getByRole('button', { name: 'Accept' }).click();
    await expect(hr.locator('.section-head h2')).toContainText('Serving notice');
    await expect(hr.locator('.panels')).toContainText('10 days · Set against earned leave');
    await shot(hr, '85-accepted', true);

    await meera.reload();
    await nav(meera, 'My exit');
    await expect(meera.getByRole('heading', { name: /Your resignation/ })).toContainText('Serving notice');
    await expect(meera.getByTestId('my-last-day')).not.toHaveText('—');
  });

  test('the employee asks to withdraw, and HR refuses', async () => {
    await meera.getByRole('button', { name: 'Ask to withdraw my resignation' }).click();
    await meera.fill('#withdraw-note', 'I would like to stay after all');
    await meera.getByRole('button', { name: 'Ask to withdraw', exact: true }).click();
    await expect(meera.getByText('You asked to withdraw your resignation')).toBeVisible();

    await hr.reload();
    await nav(hr, 'Exits');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await expect(hr.getByText('I would like to stay after all')).toBeVisible();
    await hr.fill('#wd-note', 'The role is already being filled');
    await hr.getByRole('button', { name: 'Refuse' }).click();
    await expect(hr.locator('.section-head h2')).toContainText('Serving notice');
  });

  test('HR starts an exit for another employee, and sets the notice periods', async () => {
    await hr.getByRole('button', { name: '← All exits' }).click();
    await hr.getByRole('button', { name: 'Start an exit' }).click();
    const nina = await hr.locator('#se-employee option', { hasText: 'Nina Paul' }).getAttribute('value');
    await hr.selectOption('#se-employee', nina!);
    await hr.selectOption('#se-kind', 'termination');
    await hr.selectOption('#se-reason', 'performance');
    await hr.fill('#se-note', 'Agreed after the review');
    await hr.fill('#se-day', isoDay(10));
    await hr.selectOption('#se-shortfall', 'paid_in_lieu');
    await shot(hr, '86-start-exit', true);
    await hr.getByRole('button', { name: 'Start the exit' }).click();
    await expect(hr.getByRole('heading', { name: /Nina Paul/ })).toContainText('Serving notice');
    await expect(hr.locator('.panels')).toContainText('Notice paid in lieu');

    await nav(hr, 'People');
    await expect(hr.locator('tbody tr', { hasText: 'Nina Paul' })).toContainText('Leaving');
    await tab(hr, 'Setup');
    await tab(hr, 'Exits');
    await hr.fill('#ex-notice-permanent', '45');
    await hr.getByLabel('Encash Earned leave').check();
    await hr.getByRole('button', { name: 'Save exit settings' }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
    await shot(hr, '87-exit-setup', true);

    await nav(hr, 'Audit log');
    for (const action of ['exit.submitted', 'exit.recommended', 'exit.accepted', 'exit.withdrawal_refused', 'exit.started']) {
      await expect(hr.locator('code', { hasText: action }).first()).toBeVisible();
    }
  });
});
