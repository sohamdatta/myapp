import { test, expect, type Page } from '@playwright/test';
import { OWNER, HR, signIn, nav, tab, isoDay, currentYear, daysToNextMonday, expectNoConsoleErrors } from './helpers';

// Leave: the Org Admin sets up leave types, the HR Admin applies (with a live
// preview of the days), the Org Admin approves, adjusts a balance, records leave
// on the HR Admin's behalf, the HR Admin cancels, and last year is closed.

const tile = (page: Page, name: string) => page.locator('.tiles > *', { hasText: name });

test.describe.serial('leave', () => {
  let owner: Page;
  let hr: Page;
  let casualBefore = '';

  test.beforeAll(async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    hr = await signIn(browser, HR);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
    await hr?.context().close();
  });

  async function addType(name: string, opts: { kind?: string; accrual?: string; quota?: string; cap?: string; short?: string } = {}) {
    await owner.click('text=Add leave type');
    await owner.fill('#lt-name', name);
    if (opts.kind) await owner.selectOption('#lt-kind', opts.kind);
    if (opts.accrual) await owner.selectOption('#lt-accrual', opts.accrual);
    if (opts.quota) await owner.fill('#lt-quota', opts.quota);
    if (opts.cap !== undefined) await owner.fill('#lt-cap', opts.cap);
    if (opts.short) await owner.selectOption('#lt-short', opts.short);
    await owner.getByRole('button', { name: 'Add leave type', exact: true }).last().click();
    await expect(owner.locator('td', { hasText: name })).toBeVisible();
  }

  test('set up leave types', async () => {
    await nav(owner, 'Leave');
    await tab(owner, 'Setup');
    await expect(owner.getByText('No leave types yet')).toBeVisible();

    await owner.click('text=Add leave type');
    await owner.fill('#lt-name', 'Casual leave');
    await owner.getByRole('button', { name: 'Add leave type', exact: true }).last().click();
    await expect(owner.locator('form .error')).toBeVisible(); // a yearly quota is needed

    await owner.fill('#lt-quota', '12');
    await owner.fill('#lt-cap', '0');
    await owner.getByRole('button', { name: 'Add leave type', exact: true }).last().click();
    await expect(owner.locator('td', { hasText: 'Casual leave' })).toBeVisible();

    await addType('Earned leave', { accrual: 'monthly', quota: '18', cap: '30', short: 'unpaid' });
    await addType('Loss of pay', { kind: 'unpaid' });
    await addType('Comp-off', { kind: 'comp_off' });
  });

  test('apply for leave with a live preview of the days', async () => {
    await nav(hr, 'My leave');
    await expect(tile(hr, 'Casual leave')).toBeVisible();
    casualBefore = ((await tile(hr, 'Casual leave').textContent()) ?? '').replace(/\s+/g, ' ').trim();

    await hr.click('text=Apply for leave');
    await hr.selectOption('#apply-type', { label: 'Casual leave' });
    await hr.fill('#apply-from', isoDay(1));
    await hr.fill('#apply-to', isoDay(2));
    await expect(hr.locator('form [role=status]')).toContainText('This takes 2 days');

    await hr.click('text=Send for approval');
    await expect(hr.locator('form .error')).toBeVisible(); // a reason is needed

    await hr.fill('#apply-reason', 'Family function in Durgapur');
    await hr.click('text=Send for approval');
    await expect(hr.locator('tr', { hasText: 'Family function' })).toContainText('pending');

    // The HR Admin can see the request in the queue but not decide it.
    await nav(hr, 'Leave');
    const own = hr.locator('tbody tr', { hasText: 'Family function' });
    await expect(own).toBeVisible();
    await expect(own.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0);
  });

  test('the Org Admin approves, and the request shows on the who-is-away calendar', async () => {
    await nav(owner, 'Leave');
    await tab(owner, 'Requests');
    await expect(owner.locator('td', { hasText: 'Family function' })).toBeVisible();
    await owner.getByLabel(`Note for ${HR}`).fill('Enjoy');
    await owner.getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(owner.getByText('No pending leave requests.')).toBeVisible();

    await tab(owner, 'Who is away');
    await expect(owner.locator('tbody tr', { hasText: HR }).first()).toBeVisible();
  });

  test('HR adjusts a balance and records leave on someone else’s behalf', async () => {
    await tab(owner, 'Balances');
    const hrRow = owner.locator('tr', { hasText: HR });
    await expect(hrRow).toBeVisible();

    await hrRow.getByText('Adjust').click();
    await owner.selectOption('#adj-type', { label: 'Earned leave' });
    await owner.fill('#adj-days', '4.5');
    await owner.fill('#adj-reason', 'Opening balance from the old system');
    await owner.click('text=Save adjustment');
    await expect(owner.locator('.detail-row')).toHaveCount(0);

    const lopDay = isoDay(daysToNextMonday() + 14); // clear of the work-from-home week tested next
    await hrRow.getByText('Record leave').click();
    await owner.selectOption('#behalf-type', { label: 'Loss of pay' });
    await owner.fill('#behalf-from', lopDay);
    await owner.fill('#behalf-to', lopDay);
    await owner.fill('#behalf-reason', 'Was away, told by phone');
    await owner.click(`text=Record leave for ${HR}`);
    await expect(owner.locator('.detail-row')).toHaveCount(0);

    await hrRow.getByText('History').click();
    await owner.locator('.detail-row .chip', { hasText: 'Earned leave' }).click();
    await expect(owner.locator('.detail-row').getByRole('cell', { name: /^Adjusted by HR/ })).toBeVisible();

    // Nobody adjusts their own balance.
    await expect(owner.locator('tr', { hasText: OWNER }).locator('td.actions').getByText('Adjust')).toHaveCount(0);
  });

  test('the employee cancels an approved leave that has not started', async () => {
    await nav(hr, 'My leave');
    const row = hr.locator('tr', { hasText: 'Family function' });
    await expect(row).toContainText('approved');
    await expect(tile(hr, 'Casual leave')).not.toHaveText(casualBefore); // two days taken
    await row.getByText('Cancel').click();
    await expect(row).toContainText('cancelled');
    await expect(tile(hr, 'Casual leave')).toHaveText(casualBefore); // and given back
  });

  test('closing last year is previewed first and then locked', async () => {
    await nav(owner, 'Leave');
    await tab(owner, 'Setup');
    await owner.fill('#ye-year', String(currentYear() - 1));
    await owner.click('text=Preview');
    await expect(owner.locator('.panel .tiles')).toBeVisible();
    await owner.click('text=Close this leave year…');
    await owner.click('text=Yes, close the year');
    await expect(owner.getByText('This leave year has been closed.')).toBeVisible();

    await nav(owner, 'Audit log');
    for (const action of ['leave.type_created', 'leave.approved', 'leave.adjusted', 'leave.applied_on_behalf', 'leave.cancelled', 'leave.year_closed']) {
      await expect(owner.locator('code', { hasText: action }).first()).toBeVisible();
    }
  });
});
