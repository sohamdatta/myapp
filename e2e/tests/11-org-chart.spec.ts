import { test, expect, type Page } from '@playwright/test';
import { HR, PASSWORD, signIn, nav, expectNoSidewaysScroll, expectNoConsoleErrors, shot } from './helpers';

// The org chart: an employee finds their place and their manager, looks someone up, and cannot open
// records that are not theirs; HR opens a record from the chart; on a phone the chart reads top to bottom.

const MEERA = 'meera.iyer@mail.test';

test.describe.serial('org chart', () => {
  let meera: Page;
  let hr: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await meera?.context().close();
    await hr?.context().close();
  });

  test('an employee sees where they sit, and who they report to', async ({ browser }) => {
    meera = await signIn(browser, MEERA, PASSWORD);
    await nav(meera, 'Org chart');
    const me = meera.locator('.org-card.is-me');
    await expect(me).toContainText('Meera Iyer');
    await expect(me).toContainText('You');
    const panel = meera.getByTestId('org-person');
    await expect(panel.getByRole('heading', { name: /Meera Iyer/ })).toBeVisible();
    await expect(panel).toContainText('Reports to');
    await expect(panel.getByRole('button', { name: 'Ananya Sen' })).toBeVisible();
    await expect(panel.getByRole('button', { name: 'My profile' })).toBeVisible();
    await shot(meera, 'org-chart');

    // Her manager: who they are and how big the team is, but not their record.
    await panel.getByRole('button', { name: 'Ananya Sen' }).click();
    await expect(panel.getByRole('heading', { name: /Ananya Sen/ })).toBeVisible();
    await expect(panel).toContainText('direct report');
    await expect(panel.getByRole('button', { name: 'Open their record' })).toHaveCount(0);
    await shot(meera, 'org-chart-manager');

    // Looking someone up.
    await meera.fill('#org-search', 'Harish');
    await meera.getByRole('list', { name: 'People found' }).getByRole('button', { name: /Harish Rao/ }).click();
    await expect(panel.getByRole('heading', { name: /Harish Rao/ })).toBeVisible();
    await expect(meera.locator('.org-card.selected')).toContainText('Harish Rao');

    // Collapsing and expanding a team.
    await meera.getByRole('button', { name: 'Collapse all' }).click();
    await expect(meera.locator('.org-card', { hasText: 'Meera Iyer' })).toHaveCount(0);
    await meera.getByRole('button', { name: 'Show me' }).click();
    await expect(meera.locator('.org-card.is-me')).toBeVisible();

    // Her own card leads to her profile.
    await meera.locator('.org-card.is-me .org-who').click();
    await panel.getByRole('button', { name: 'My profile' }).click();
    await expect(meera.getByText('Your record as HR keeps it')).toBeVisible();
  });

  test('on a phone the chart reads top to bottom', async ({ browser }) => {
    const phone = await signIn(browser, MEERA, PASSWORD, 390);
    await nav(phone, 'Org chart');
    await expect(phone.locator('.org-card.is-me')).toBeVisible();
    await expectNoSidewaysScroll(phone);
    await shot(phone, 'org-chart-phone', true);
    await phone.context().close();
  });

  test('HR opens a record from the chart', async ({ browser }) => {
    hr = await signIn(browser, HR);
    await nav(hr, 'Org chart');
    await hr.fill('#org-search', 'Meera');
    await hr.keyboard.press('Enter');
    const panel = hr.getByTestId('org-person');
    await expect(panel.getByRole('heading', { name: /Meera Iyer/ })).toBeVisible();
    await panel.getByRole('button', { name: 'Open their record' }).click();
    await expect(hr.getByRole('heading', { name: /Meera Iyer/ })).toBeVisible();
    await expect(hr.getByRole('button', { name: '← All employees' })).toBeVisible();
  });
});
