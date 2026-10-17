import { test, expect, type Page } from '@playwright/test';
import { OWNER, HR, SUPER_ADMIN, newPage, signIn, nav, tab, acceptInvite, expectNoConsoleErrors, shot } from './helpers';

// A tour of each role, for the guide: what the menu offers, the main screens, and what is not
// there. It adds the roles the story has not used yet (a separate Payroll Approver, an Auditor,
// an IT Admin) and makes Priya a Manager of Rahul, so each role is seen through its own login.

const MEERA = 'meera.iyer@mail.test';
const PRIYA = 'priya.shah@mail.test';
const APPROVER = 'approver@globex.test';
const AUDITOR = 'auditor@globex.test';
const IT = 'it@globex.test';

async function menu(page: Page): Promise<string[]> {
  return page.locator('button.nav-item').allInnerTexts();
}

async function invite(owner: Page, email: string, role: string): Promise<string> {
  await nav(owner, 'User management');
  await tab(owner, 'Invitations');
  await owner.fill('#invite-email', email);
  await owner.uncheck(".role-picker label:has-text('Employee') input");
  await owner.check(`.role-picker label:has-text('${role}') input`);
  await owner.click('text=Create invitation');
  await expect(owner.getByText(`Invitation for ${email}.`)).toBeVisible();
  // The roles reset, so the next invitation does not carry this one's by accident.
  await expect(owner.locator(".role-picker label:has-text('Employee') input")).toBeChecked();
  await expect(owner.locator(`.role-picker label:has-text('${role}') input`)).not.toBeChecked();
  return owner.locator('.copy-row input').inputValue();
}

test.describe.serial('role tour', () => {
  let owner: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
  });

  test('Super Admin: the platform console, not inside any Organization', async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email);
    await admin.fill('#password', SUPER_ADMIN.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    await shot(admin, 'role-super-01-organizations', true);
    const items = await menu(admin);
    expect(items).not.toContain('People');
    await nav(admin, 'Platform users');
    await shot(admin, 'role-super-02-platform-users', true);
    await nav(admin, 'Support access');
    await shot(admin, 'role-super-03-support-access', true);
    await admin.context().close();
  });

  test('Org Admin: users, roles, the audit log, and the new roles handed out', async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    await shot(owner, 'role-owner-01-menu', true);
    await nav(owner, 'User management');
    await tab(owner, 'Users');
    await shot(owner, 'role-owner-02-users', true);
    await tab(owner, 'Roles');
    await shot(owner, 'role-owner-03-roles', true);
    await nav(owner, 'Audit log');
    await shot(owner, 'role-owner-04-audit-log');

    // Priya becomes a Manager: Rahul reports to her.
    await nav(owner, 'User management');
    await tab(owner, 'Users');
    const row = owner.locator('tr', { hasText: PRIYA });
    await row.getByText('Edit roles').click();
    await owner.check(".detail-row label:has-text('Manager') input");
    await owner.click('text=Save roles');
    await expect(row.locator('td').nth(1)).toContainText('Manager');
  });

  test('Payroll Approver: approves and pays, reads the records behind pay, prepares nothing', async ({ browser }) => {
    const link = await invite(owner, APPROVER, 'Payroll Approver');
    const approver = await acceptInvite(browser, link);
    const items = await menu(approver);
    expect(items).toContain('Payroll');
    expect(items).not.toContain('User management');
    await shot(approver, 'role-approver-01-menu', true);
    // Reads people, to check pay against them, but cannot add or change anyone.
    await nav(approver, 'People');
    await expect(approver.getByRole('button', { name: 'Add employee' })).toHaveCount(0);
    await shot(approver, 'role-approver-01b-people-read-only', true);
    await nav(approver, 'Payroll');
    await expect(approver.getByRole('button', { name: /^Start / })).toHaveCount(0);
    await shot(approver, 'role-approver-02-runs', true);
    await approver.getByRole('button', { name: /^Open / }).first().click();
    await expect(approver.getByText(/Calculated by Harish Rao/)).toBeVisible();
    await shot(approver, 'role-approver-03-run-to-approve', true);
    await approver.getByRole('button', { name: '← All runs' }).click();
    await tab(approver, 'Setup');
    await expect(approver.locator('#pe-name')).toBeDisabled();
    await shot(approver, 'role-approver-04-setup-read-only');
    await approver.context().close();
  });

  test('Auditor: reads everything, changes nothing', async ({ browser }) => {
    const link = await invite(owner, AUDITOR, 'Auditor');
    const auditor = await acceptInvite(browser, link);
    await shot(auditor, 'role-auditor-01-menu', true);
    const items = await menu(auditor);
    if (items.includes('People')) {
      await nav(auditor, 'People');
      await expect(auditor.getByRole('button', { name: 'Add employee' })).toHaveCount(0);
      await shot(auditor, 'role-auditor-02-people-read-only', true);
    }
    await nav(auditor, 'Audit log');
    await shot(auditor, 'role-auditor-03-audit-log');
    if (items.includes('Payroll')) {
      await nav(auditor, 'Payroll');
      await expect(auditor.getByRole('button', { name: /^Start / })).toHaveCount(0);
      await shot(auditor, 'role-auditor-04-payroll-read-only', true);
    }
    await auditor.context().close();
  });

  test('IT Admin: only the tasks of joining and leaving that are theirs', async ({ browser }) => {
    const link = await invite(owner, IT, 'IT Admin');
    const it = await acceptInvite(browser, link);
    const items = await menu(it);
    expect(items).not.toContain('Payroll');
    await shot(it, 'role-it-01-menu', true);
    await it.context().close();
  });

  test('HR Admin and Payroll Operator: people, joiners, exits, leave and the run they prepare', async ({ browser }) => {
    const hr = await signIn(browser, HR);
    await shot(hr, 'role-hr-01-menu', true);
    await nav(hr, 'People');
    await tab(hr, 'Employees');
    await shot(hr, 'role-hr-02-employees', true);
    await tab(hr, 'Joiners');
    await shot(hr, 'role-hr-03-joiners', true);
    await nav(hr, 'Exits');
    await shot(hr, 'role-hr-04-exits', true);
    await nav(hr, 'Leave');
    await shot(hr, 'role-hr-05-leave', true);
    await nav(hr, 'Attendance');
    await shot(hr, 'role-hr-06-attendance-today', true);
    await nav(hr, 'User management');
    await tab(hr, 'Invitations');
    await shot(hr, 'role-hr-07-roles-they-can-grant', true);
    await nav(hr, 'Payroll');
    await hr.getByRole('button', { name: /^Open / }).first().click();
    await expect(hr.getByText('You calculated this run; someone else approves it')).toBeVisible();
    await shot(hr, 'role-operator-01-own-run-waits-for-approver', true);
    await hr.context().close();
  });

  test('Manager: their team’s day, requests and records, nobody else’s', async ({ browser }) => {
    const priya = await signIn(browser, PRIYA);
    await shot(priya, 'role-manager-01-menu', true);
    const items = await menu(priya);
    if (items.includes('Attendance')) {
      await nav(priya, 'Attendance');
      await shot(priya, 'role-manager-02-team-attendance', true);
    }
    if (items.includes('Leave')) {
      await nav(priya, 'Leave');
      await shot(priya, 'role-manager-03-team-leave', true);
    }
    if (items.includes('People')) {
      await nav(priya, 'People');
      await expect(priya.getByText('Rahul Bose')).toBeVisible();
      await expect(priya.getByText('Meera Iyer')).toHaveCount(0);
      await shot(priya, 'role-manager-04-team-records', true);
    }
    await nav(priya, 'Org chart');
    await shot(priya, 'role-manager-05-org-chart');
    await priya.context().close();
  });

  test('Employee: their own day, leave, pay, profile and exit', async ({ browser }) => {
    const meera = await signIn(browser, MEERA);
    await shot(meera, 'role-employee-01-menu', true);
    await nav(meera, 'My leave');
    await shot(meera, 'role-employee-02-my-leave', true);
    await nav(meera, 'My pay');
    await shot(meera, 'role-employee-03-my-pay', true);
    await nav(meera, 'My profile');
    await shot(meera, 'role-employee-04-my-profile', true);
    await nav(meera, 'My exit');
    await shot(meera, 'role-employee-05-my-exit', true);
    await meera.context().close();
  });
});
