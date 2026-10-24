import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, PASSWORD, newPage, signIn, nav, tab, acceptInvite, api, expectRefused, expectNoConsoleErrors, expectNoSidewaysScroll, shot } from './helpers';

// The Settings screen, in an Organization of its own: the owner shortens invitation links (safer, so
// applied at once), lengthens them (weaker, so the password is asked for), sees both in the history and
// undoes the second; an employee has no Settings at all. The resolver, second-person approval, dated
// values and the platform's bounds are covered by SettingsTest against the database.

const ORG = 'Soylent Foods';
const OWNER = 'owner@soylent-foods.test';
const PETER = 'peter@soylent-foods.test';
const KEY = 'auth.invitation.valid_days';

function row(page: Page) {
  return page.locator(`[data-setting="${KEY}"]`);
}

test.describe.serial('settings', () => {
  let owner: Page;
  let peter: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [owner, peter]) await p?.context().close();
  });

  test('an Organization with an owner and an employee', async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email);
    await admin.fill('#password', SUPER_ADMIN.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    await admin.fill('#org-name', ORG);
    await admin.fill('#org-admin', OWNER);
    await admin.click('text=Create and invite');
    await expect(admin.getByText(`Invitation for ${OWNER}.`)).toBeVisible();
    owner = await acceptInvite(browser, await admin.locator('.copy-row input').inputValue());
    await admin.context().close();
    const invited = await api(owner, 'POST', '/org/invitations', { email: PETER, roles: ['employee'] });
    expect(invited.status).toBe(200);
    peter = await acceptInvite(browser, `/?invite=${encodeURIComponent(invited.body.token)}`);
  });

  test('the owner sees each setting with its value, source and range', async () => {
    await owner.reload();
    await nav(owner, 'Settings');
    await tab(owner, 'Security');
    await expect(owner.getByTestId(`value-${KEY}`)).toHaveText('7 days');
    await expect(owner.getByTestId(`source-${KEY}`)).toHaveText('Default');
    await expect(row(owner)).toContainText('Allowed 1 to 30 days');
    await expect(row(owner)).toContainText('asks for your password');
    await shot(owner, 'settings-01-security', true);
  });

  test('a safer value is applied at once; out of range is refused', async () => {
    await row(owner).getByRole('button', { name: 'Change' }).click();
    await owner.fill('#set-auth-invitation-valid_days-value', '31');
    await owner.fill('#set-auth-invitation-valid_days-reason', 'Tighter for the audit');
    await owner.getByRole('button', { name: 'Save', exact: true }).click();
    // The browser's own range check stops 31 first; the server would answer setting.out_of_range.
    await expect(owner.getByTestId(`value-${KEY}`)).toHaveText('7 days');
    await expectRefused(owner, 'PUT', `/org/settings/${KEY}`, [400], { value: 31 }, 'setting.out_of_range');
    await owner.fill('#set-auth-invitation-valid_days-value', '3');
    await owner.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(owner.getByTestId(`value-${KEY}`)).toHaveText('3 days');
    await expect(owner.getByTestId(`source-${KEY}`)).toHaveText('Set for the Organization');
    const invited = await api(owner, 'POST', '/org/invitations', { email: 'guest@soylent-foods.test', roles: ['employee'] });
    const days = (Date.parse(invited.body.expiresAt) - Date.now()) / 86_400_000;
    expect(days).toBeGreaterThan(2.9);
    expect(days).toBeLessThan(3.1);
  });

  test('a weaker value asks for the password, and checks it', async () => {
    await row(owner).getByRole('button', { name: 'Change' }).click();
    await owner.fill('#set-auth-invitation-valid_days-value', '14');
    await owner.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(owner.getByText('Confirm with your password')).toBeVisible();
    await owner.fill('#set-auth-invitation-valid_days-password', 'not-my-password');
    await owner.getByRole('button', { name: 'Confirm and save' }).click();
    await expect(row(owner).getByText('That password is not right')).toBeVisible();
    await shot(owner, 'settings-02-password');
    await owner.fill('#set-auth-invitation-valid_days-password', PASSWORD);
    await owner.getByRole('button', { name: 'Confirm and save' }).click();
    await expect(owner.getByTestId(`value-${KEY}`)).toHaveText('14 days');
  });

  test('the history shows both changes, and the weaker one is undone', async () => {
    await tab(owner, 'History');
    const history = owner.getByTestId('settings-history');
    await expect(history.locator('tbody tr')).toHaveCount(2);
    await expect(history.locator('tbody tr').first()).toContainText('3 → 14');
    await expect(history.locator('tbody tr').first()).toContainText('password confirmed');
    await expect(history.locator('tbody tr').nth(1)).toContainText('Tighter for the audit');
    await shot(owner, 'settings-03-history', true);
    await history.locator('tbody tr').first().getByRole('button', { name: 'Undo' }).click();
    await owner.getByRole('button', { name: 'Undo', exact: true }).last().click();
    await expect(history.locator('tbody tr')).toHaveCount(3);
    await expect(history.locator('tbody tr').nth(1)).toContainText('Undone');
    await tab(owner, 'Security');
    await expect(owner.getByTestId(`value-${KEY}`)).toHaveText('3 days');
  });

  test('on a phone the settings fit the screen', async ({ browser }) => {
    const phone = await signIn(browser, OWNER, PASSWORD, 390);
    await nav(phone, 'Settings');
    await tab(phone, 'Security');
    await expect(phone.getByTestId(`value-${KEY}`)).toHaveText('3 days');
    await expectNoSidewaysScroll(phone);
    await shot(phone, 'settings-04-phone', true);
    await phone.context().close();
  });

  test('an employee has no Settings', async () => {
    await peter.reload();
    await peter.locator('.topbar').waitFor();
    await expect(peter.locator('a.nav-item').filter({ hasText: /^Settings$/ })).toHaveCount(0);
    const mine = await api(peter, 'GET', '/org/settings');
    expect(mine.body.groups).toEqual([]);
    await expectRefused(peter, 'PUT', `/org/settings/${KEY}`, [403], { value: 2 });
    await expectRefused(peter, 'GET', '/platform/settings', [403, 401]);
  });
});
