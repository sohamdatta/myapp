import { test, expect } from '@playwright/test';
import { OWNER, HR, signIn, nav, tab, expectNoSidewaysScroll, expectNoConsoleErrors } from './helpers';

// The main screens at phone width (390 px): they must load, show their data and
// never scroll sideways.

const PHONE = 390;

test.describe('phone width', () => {
  test.afterAll(() => expectNoConsoleErrors());

  test('employee screens', async ({ browser }) => {
    const hr = await signIn(browser, HR, undefined, PHONE);
    await nav(hr, 'My attendance');
    await expect(hr.locator('.punch-button')).toBeVisible();
    await expect(hr.locator('tbody tr').first()).toBeVisible();
    await expectNoSidewaysScroll(hr);

    await nav(hr, 'My leave');
    await expect(hr.locator('.tiles')).toBeVisible();
    await expect(hr.locator('tbody tr').first()).toBeVisible();
    await expectNoSidewaysScroll(hr);
    await hr.context().close();
  });

  test('Org Admin screens', async ({ browser }) => {
    const owner = await signIn(browser, OWNER, undefined, PHONE);
    await nav(owner, 'User management');
    await expect(owner.locator('td', { hasText: OWNER })).toBeVisible();
    await expectNoSidewaysScroll(owner);

    await nav(owner, 'Attendance');
    await expect(owner.locator('td', { hasText: HR })).toBeVisible();
    await expectNoSidewaysScroll(owner);

    await nav(owner, 'People');
    await expect(owner.locator('td', { hasText: 'Harish Rao' })).toBeVisible();
    await expectNoSidewaysScroll(owner);
    await owner.getByRole('button', { name: 'Harish Rao' }).click();
    await expect(owner.locator('.panels')).toContainText('•••• 234F');
    await expectNoSidewaysScroll(owner);

    await nav(owner, 'Leave');
    await tab(owner, 'Balances');
    await expect(owner.locator('tr', { hasText: HR })).toBeVisible();
    await expectNoSidewaysScroll(owner);
    await owner.context().close();
  });
});
