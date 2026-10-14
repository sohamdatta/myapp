import { test, expect, type Page } from '@playwright/test';
import { ORG, OWNER, HR, PASSWORD, SUPER_ADMIN, newPage, signIn, nav, tab, expectNoConsoleErrors, shot } from './helpers';

// Users and access: the Super Admin creates an Organization, its Org Admin joins
// and invites an HR Admin, roles are changed, a member is suspended and
// reactivated, and platform support enters the Organization under a grant.

test.describe.serial('users and access', () => {
  let owner: Page;
  let hr: Page;
  let inviteLink = '';
  let hrLink = '';

  test.afterAll(() => expectNoConsoleErrors());

  test('Super Admin signs in and creates an Organization', async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email);
    await admin.fill('#password', 'wrong-password');
    await shot(admin, '01-sign-in');
    await admin.click('button.primary');
    await expect(admin.locator('.error')).toBeVisible();

    await admin.fill('#password', SUPER_ADMIN.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    await expect(admin.getByText('Create an Organization')).toBeVisible();

    await admin.fill('#org-name', ORG);
    await expect(admin.locator('#org-slug')).toHaveValue('globex-ltd');
    await admin.selectOption('#org-plan', 'growth');
    await admin.fill('#org-admin', OWNER);
    await shot(admin, '02-console-create');
    await admin.click('text=Create and invite');
    inviteLink = await admin.locator('.copy-row input').inputValue();
    expect(inviteLink).toContain('/?invite=');
    await expect(admin.locator('td', { hasText: ORG })).toBeVisible();
    await shot(admin, '03-console-created');
    await admin.click('text=Sign out');
    await expect(admin.locator('#email')).toBeVisible();
    await admin.context().close();
  });

  test('the first Org Admin accepts the invitation', async ({ browser }) => {
    owner = await newPage(browser);
    await owner.goto(inviteLink);
    await expect(owner.getByText(`Join ${ORG}`)).toBeVisible();
    await shot(owner, '04-accept-invite');
    await owner.fill('#new-password', 'short');
    await owner.fill('#confirm-password', 'short');
    await owner.click('button.primary');
    await expect(owner.locator('.error')).toBeVisible();

    await owner.fill('#new-password', PASSWORD);
    await owner.fill('#confirm-password', PASSWORD);
    await owner.click('button.primary');
    await expect(owner.locator('.sidebar')).toBeVisible();
    await nav(owner, 'User management');
    await expect(owner.locator('td', { hasText: OWNER })).toBeVisible();
    await shot(owner, '05-user-management');
  });

  test('the Org Admin invites an HR Admin; a second invitation is refused', async () => {
    await tab(owner, 'Invitations');
    await owner.fill('#invite-email', HR);
    await owner.check("label:has-text('HR Admin') input");
    await shot(owner, '06-invite-hr');
    await owner.click('text=Create invitation');
    hrLink = await owner.locator('.copy-row input').inputValue();
    expect(hrLink).toContain('/?invite=');

    await owner.fill('#invite-email', HR);
    await owner.click('text=Create invitation');
    await expect(owner.locator('.error')).toBeVisible();
  });

  test('the HR Admin joins and can grant only the roles they are allowed to', async ({ browser }) => {
    hr = await newPage(browser);
    await hr.goto(hrLink);
    await expect(hr.getByText(`Join ${ORG}`)).toBeVisible();
    await hr.fill('#new-password', PASSWORD);
    await hr.fill('#confirm-password', PASSWORD);
    await hr.click('button.primary');
    await expect(hr.locator('.sidebar')).toBeVisible();

    await nav(hr, 'User management');
    await tab(hr, 'Invitations');
    await expect(hr.locator('#invite-email')).toBeVisible();
    const role = (name: string) => hr.locator('.role-picker label', { hasText: name }).locator('input');
    await expect(role('Employee')).toBeEnabled();
    await expect(role('Manager')).toBeEnabled();
    await expect(role('Payroll Operator')).toBeDisabled();
    await expect(role('Payroll Approver')).toBeDisabled();
    await expect(role('Org Admin')).toBeDisabled();

    // An HR Admin may not suspend the Org Admin.
    await tab(hr, 'Users');
    const ownerRow = hr.locator('tr', { hasText: OWNER });
    await ownerRow.locator('summary').click();
    await ownerRow.getByText('Suspend').click();
    await expect(hr.locator('.error')).toBeVisible();
  });

  test('the Org Admin changes roles, suspends and reactivates the HR Admin', async () => {
    await tab(owner, 'Users');
    const hrRow = owner.locator('tr', { hasText: HR });
    await hrRow.getByText('Edit roles').click();
    await owner.check(".detail-row label:has-text('Manager') input");
    await shot(owner, '07-edit-roles');
    await owner.click('text=Save roles');
    await expect(hrRow.locator('td').nth(1)).toContainText('Manager');

    await hrRow.locator('summary').click();
    await hrRow.getByText('Suspend').click();
    await expect(owner.getByText('Suspended 1')).toBeVisible();

    // The suspended HR Admin is signed out on their next request.
    await hr.reload();
    await expect(hr.locator('#email')).toBeVisible();

    await owner.click('text=Suspended 1');
    await hrRow.locator('summary').click();
    await hrRow.getByText('Reactivate').click();
    await expect(owner.getByText('Suspended 0')).toBeVisible();

    await tab(owner, 'Roles');
    await expect(owner.locator('details.permissions').first()).toBeVisible();
    await shot(owner, '08-roles');
    await nav(owner, 'Audit log');
    await expect(owner.locator('code', { hasText: 'role.granted' }).first()).toBeVisible();
    await expect(owner.locator('code', { hasText: 'membership.suspended' }).first()).toBeVisible();
    await shot(owner, '09-audit-log');
    await hr.context().close();
  });

  test('platform support enters read-only, and the Organization sees it in its audit log', async ({ browser }) => {
    const support = await signIn(browser, SUPER_ADMIN.email, SUPER_ADMIN.password);
    await nav(support, 'Support access');
    await support.selectOption('#g-org', { label: ORG });
    await support.fill('#g-reason', 'Ticket 42: check the user list');
    await shot(support, '10-support-request');
    await support.click('text=Request access');
    await support.click('text=Start session');
    await expect(support.locator('.support-banner')).toBeVisible();

    await nav(support, 'User management');
    await expect(support.locator('td', { hasText: OWNER })).toBeVisible();
    await expect(support.locator('td.actions button')).toHaveCount(0);
    await shot(support, '11-support-session');
    await support.click('text=End session');
    await expect(support.getByText('Create an Organization')).toBeVisible();

    await owner.reload();
    await nav(owner, 'Audit log');
    await expect(owner.locator('tr', { hasText: 'support.session_started' })).toBeVisible();
    await support.context().close();
    await owner.context().close();
  });
});
