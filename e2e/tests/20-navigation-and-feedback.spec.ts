import { test, expect } from '@playwright/test';
import { OWNER, HR, SUPER_ADMIN, signIn, nav, expectNoConsoleErrors, forgetConsoleErrors } from './helpers';

// Page addresses, the phone menu, confirmations, notes and a page that fails to download.
// Runs last, against everything the story before it has built.

const title = (page: import('@playwright/test').Page) => page.locator('.page-title');
const path = (page: import('@playwright/test').Page) => new URL(page.url()).pathname;

test.describe('page addresses', () => {
  test.afterAll(() => expectNoConsoleErrors());

  test('each page has its own address; reload, Back and Forward keep your place', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    const first = await title(owner).innerText();
    await nav(owner, 'People');
    expect(path(owner)).toBe('/people');
    await nav(owner, 'Payroll');
    expect(path(owner)).toBe('/payroll');

    await owner.reload();
    await expect(title(owner)).toHaveText('Payroll');
    expect(path(owner)).toBe('/payroll');

    await owner.goBack();
    await expect(title(owner)).toHaveText('People');
    await owner.goBack();
    await expect(title(owner)).toHaveText(first);
    await owner.goForward();
    await expect(title(owner)).toHaveText('People');
    expect(path(owner)).toBe('/people');

    // The tab names the page, so several tabs can be told apart.
    await expect(owner).toHaveTitle(/^People · /);
    await owner.context().close();
  });

  test('a link opens the page directly, and sidebar entries are real links', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    await expect(owner.locator('a.nav-item[href="/people"]')).toBeVisible();
    await owner.goto('/audit');
    await expect(title(owner)).toHaveText('Audit log');
    await owner.context().close();
  });

  test('an address the person may not open shows their first page instead', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    const first = await title(owner).innerText();
    for (const nowhere of ['/no-such-page', '/organizations', '/my-onboarding']) {
      await owner.goto(nowhere);
      await expect(title(owner)).toHaveText(first);
      expect(path(owner)).not.toBe(nowhere);
    }
    await owner.context().close();
  });

  test('signing out and in again starts on the first page, not where the last person left off', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    const first = await title(owner).innerText();
    await nav(owner, 'Audit log');
    await owner.getByRole('button', { name: 'Sign out' }).click();
    await expect(owner.locator('#email')).toBeVisible();
    expect(path(owner)).toBe('/');
    await owner.fill('#email', OWNER);
    await owner.fill('#password', 'correct-horse-battery');
    await owner.click('button.primary');
    await expect(title(owner)).toHaveText(first);
    await owner.context().close();
  });

  test('a page that cannot be downloaded says so and offers Reload', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    await owner.context().route(/\/(src\/pages\/Payroll\.jsx|assets\/Payroll-)/, (route) => route.abort());
    await owner.reload();
    await expect(title(owner)).not.toHaveText('Payroll');
    await nav(owner, 'Payroll').catch(() => {});
    await expect(owner.getByRole('alert')).toContainText('This page did not load');
    forgetConsoleErrors(/Failed to fetch dynamically imported module/); // caused on purpose, above
    await owner.context().unroute(/\/(src\/pages\/Payroll\.jsx|assets\/Payroll-)/);
    await owner.getByRole('button', { name: 'Reload' }).click();
    await expect(title(owner)).toHaveText('Payroll');
    await expect(owner.getByRole('alert')).toHaveCount(0);
    await owner.context().close();
  });
});

test.describe('on a phone', () => {
  test.afterAll(() => expectNoConsoleErrors());

  test('the menu is a drawer: closed by default, closed again by Escape, a tap outside or choosing a page', async ({ browser }) => {
    const owner = await signIn(browser, OWNER, undefined, 390);
    const menu = owner.locator('.menu-button');
    const sidebar = owner.locator('#main-nav');

    // A page starts with its own content, not the whole menu.
    await expect(sidebar).toBeHidden();
    await expect(menu).toHaveAttribute('aria-expanded', 'false');
    expect(await owner.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);

    await menu.click();
    await expect(sidebar).toBeVisible();
    await expect(menu).toHaveAttribute('aria-expanded', 'true');
    await owner.keyboard.press('Escape');
    await expect(sidebar).toBeHidden();

    await menu.click();
    await owner.locator('.drawer-backdrop').click({ position: { x: 370, y: 400 } });
    await expect(sidebar).toBeHidden();

    await menu.click();
    await owner.locator('a.nav-item', { hasText: /^People$/ }).click();
    await expect(sidebar).toBeHidden();
    await expect(title(owner)).toHaveText('People');
    // After moving to a page the focus is on its title, as after a page load.
    await expect(title(owner)).toBeFocused();
    await owner.context().close();
  });

  test('form fields use 16 px text so iPhones do not zoom in', async ({ browser }) => {
    const owner = await signIn(browser, OWNER, undefined, 390);
    await nav(owner, 'People');
    await owner.locator('#emp-search').waitFor();
    const sizes = await owner.evaluate(() =>
      [...document.querySelectorAll('input:not([type=checkbox]):not([type=radio]), select, textarea')].map((e) => parseFloat(getComputedStyle(e).fontSize)),
    );
    expect(sizes.length).toBeGreaterThan(0);
    for (const px of sizes) expect(px).toBeGreaterThanOrEqual(16);
    await owner.context().close();
  });
});

test.describe('confirmations and notes', () => {
  test.afterAll(() => expectNoConsoleErrors());

  test('closing an Organization asks first; the safe choice has the focus and Escape keeps it', async ({ browser }) => {
    const admin = await signIn(browser, SUPER_ADMIN.email, SUPER_ADMIN.password);
    const row = admin.locator('tr', { hasText: 'Globex Ltd' });
    await row.getByRole('button', { name: 'Close', exact: true }).click();
    const dialog = admin.getByRole('dialog');
    await expect(dialog).toContainText('Close Globex Ltd?');
    await expect(dialog).toContainText('final');
    await expect(dialog.getByRole('button', { name: 'Keep it' })).toBeFocused();
    await expect(dialog.getByRole('button', { name: 'Close Organization' })).toBeVisible();
    await admin.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(row).toContainText(/active/i); // nothing happened

    await row.getByRole('button', { name: 'Close', exact: true }).click();
    await admin.getByRole('dialog').getByRole('button', { name: 'Keep it' }).click();
    await expect(admin.getByRole('dialog')).toHaveCount(0);
    await expect(row).toContainText(/active/i);
    await admin.context().close();
  });

  test('ending a membership asks first, naming the person; keeping it changes nothing', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    await nav(owner, 'User management');
    const row = owner.locator('tr', { hasText: HR });
    await row.locator('summary').click();
    await row.getByRole('button', { name: 'End membership' }).click();
    const dialog = owner.getByRole('dialog');
    await expect(dialog).toContainText(`End ${HR}'s membership?`);
    await dialog.getByRole('button', { name: 'Keep it' }).click();
    await expect(owner.getByRole('dialog')).toHaveCount(0);
    await expect(owner.locator('tr', { hasText: HR })).toContainText(/active/i);
    await owner.context().close();
  });

  test('a punch is followed by a note that screen readers announce', async ({ browser }) => {
    const hr = await signIn(browser, HR);
    await nav(hr, 'My attendance');
    await expect(hr.locator('[aria-live="polite"]')).toHaveCount(1);
    await hr.locator('.punch-button').click();
    await expect(hr.locator('.toast')).toContainText(/^Punched (in|out) at /);
    await hr.locator('.toast-close').click();
    await expect(hr.locator('.toast')).toHaveCount(0);
    await hr.context().close();
  });
});
