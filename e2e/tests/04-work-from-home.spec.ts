import { test, expect, type Page } from '@playwright/test';
import { OWNER, HR, signIn, nav, tab, isoDay, daysToNextMonday, expectNoConsoleErrors } from './helpers';

// Work from home: a request for a range of future days, a weekly limit set by
// the Org Admin, approval, and cancelling an approved day to free the limit.
// All dates fall Monday to Wednesday of next week, so they share one week.

test.describe.serial('work from home', () => {
  let owner: Page;
  let hr: Page;
  const monday = daysToNextMonday();

  test.beforeAll(async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    hr = await signIn(browser, HR);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
    await hr?.context().close();
  });

  async function requestWfh(from: string, to: string, reason: string) {
    await hr.click('text=New request');
    await hr.selectOption('#req-kind', 'work_from_home');
    await hr.fill('#req-date', from);
    await hr.fill('#req-to', to);
    await hr.fill('#req-reason', reason);
  }

  test('request a range of future days', async () => {
    await nav(hr, 'My attendance');
    await requestWfh(isoDay(monday), isoDay(monday + 1), 'Painters at the flat');
    await expect(hr.locator('form p.hint')).toBeVisible(); // shows how many days are left
    await hr.click('text=Send request');
    await expect(hr.locator('tbody tr', { hasText: 'Painters' })).toHaveCount(2); // one request per day
  });

  test('a weekly limit refuses a third day in the same week', async () => {
    await nav(owner, 'Attendance');
    await tab(owner, 'Setup');
    await owner.fill('#wfh-week', '2');
    await owner.click('text=Save work-from-home rules');
    await expect(owner.getByText('Saved.')).toBeVisible();

    await hr.reload();
    await nav(hr, 'My attendance');
    await requestWfh(isoDay(monday + 2), isoDay(monday + 2), 'One more day at home');
    await hr.click('text=Send request');
    await expect(hr.locator('form .error')).toBeVisible();
  });

  test('an approved day can be cancelled, which frees the limit', async () => {
    await tab(owner, 'Requests');
    const first = owner.locator('tbody tr', { hasText: 'Painters' }).first();
    await first.getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(owner.locator('tbody tr', { hasText: 'Painters' })).toHaveCount(1);

    await hr.reload();
    await nav(hr, 'My attendance');
    const approved = hr.locator('tbody tr', { hasText: 'Painters' }).filter({ hasText: 'approved' });
    await expect(approved).toHaveCount(1);
    await approved.getByText('Cancel').click();
    await expect(hr.locator('tbody tr', { hasText: 'Painters' }).filter({ hasText: 'cancelled' })).toHaveCount(1);

    await requestWfh(isoDay(monday + 2), isoDay(monday + 2), 'One more day at home');
    await hr.click('text=Send request');
    await expect(hr.locator('tbody tr', { hasText: 'One more day at home' })).toBeVisible();

    await nav(owner, 'Audit log');
    await expect(owner.locator('code', { hasText: 'attendance.settings_changed' }).first()).toBeVisible();
    await expect(owner.locator('code', { hasText: 'attendance.request_cancelled' }).first()).toBeVisible();
  });
});
