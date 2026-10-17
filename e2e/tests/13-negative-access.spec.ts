import { test, expect, type Page } from '@playwright/test';
import {
  OWNER, HR, PASSWORD, SUPER_ADMIN, newPage, signIn, nav, tab, api, expectRefused, errorOn, acceptInvite, expectNoConsoleErrors,
} from './helpers';

// Negative cases for signing in, invitations, user management and who may reach what. Every
// refusal is checked twice where it matters: the screen does not offer it, and the server refuses
// the request when it is sent anyway.

const MEERA = 'meera.iyer@mail.test';

async function invite(owner: Page, email: string, roles: string[] = []): Promise<string> {
  await nav(owner, 'User management');
  await tab(owner, 'Invitations');
  await owner.fill('#invite-email', email);
  for (const role of roles) await owner.check(`.role-picker label:has-text('${role}') input`);
  await owner.click('text=Create invitation');
  await expect(owner.getByText(`Invitation for ${email}.`)).toBeVisible();
  const link = await owner.locator('.copy-row input').inputValue();
  expect(link).toContain('/?invite=');
  return link;
}

/** Employment id by name, as the Org Admin sees the list. */
async function employmentId(owner: Page, name: string): Promise<string> {
  const r = await api(owner, 'GET', '/org/people/employees?status=all');
  const e = r.body.find((x: any) => (x.preferredName || x.legalName || x.name) === name || x.legalName === name);
  expect(e, `employee ${name}`).toBeTruthy();
  return e.id;
}

test.describe.serial('negative: signing in, invitations, access', () => {
  let owner: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
  });

  test('sign-in refuses empty fields, unknown emails and wrong passwords with one message', async ({ browser }) => {
    const page = await newPage(browser);
    await page.goto('/');
    await page.click('button.primary');
    await expect(errorOn(page)).toContainText('Enter your email and password');

    await page.fill('#email', 'nobody@nowhere.test');
    await page.fill('#password', 'some-password-123');
    await page.click('button.primary');
    await expect(errorOn(page)).toHaveText('Incorrect email or password');

    // A real email with a wrong password reads exactly the same: no hint which emails exist.
    await page.fill('#email', OWNER);
    await page.fill('#password', 'not-the-password');
    await page.click('button.primary');
    await expect(errorOn(page)).toHaveText('Incorrect email or password');

    await page.fill('#email', `  ${OWNER.toUpperCase()}  `);
    await page.fill('#password', `${PASSWORD} `); // a trailing space is part of the password
    await page.click('button.primary');
    await expect(errorOn(page)).toHaveText('Incorrect email or password');
    await expect(page.locator('.sidebar')).toHaveCount(0);
    await page.context().close();
  });

  test('a broken, used or revoked invitation link offers nothing', async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    const page = await newPage(browser);
    await page.goto('/?invite=not-a-real-token');
    await expect(page.getByRole('heading', { name: 'Invitation not available' })).toBeVisible();
    await expect(page.locator('#new-password')).toHaveCount(0);

    // Revoked: the link stops working at once.
    const revoked = await invite(owner, 'revoked.user@globex.test');
    await owner.locator('tr', { hasText: 'revoked.user@globex.test' }).getByRole('button', { name: 'Revoke' }).click();
    await expect(owner.locator('tr', { hasText: 'revoked.user@globex.test' })).toHaveCount(0);
    await page.goto(revoked);
    await expect(page.getByRole('heading', { name: 'Invitation not available' })).toBeVisible();

    // Replaced by a new link: the old one is dead.
    const first = await invite(owner, 'relinked.user@globex.test');
    await owner.locator('tr', { hasText: 'relinked.user@globex.test' }).getByRole('button', { name: 'New link' }).click();
    await expect(owner.locator('.copy-row input')).not.toHaveValue(first);
    await page.goto(first);
    await expect(page.getByRole('heading', { name: 'Invitation not available' })).toBeVisible();

    // Used: accepted once, then refused.
    const once = await invite(owner, 'used.once@globex.test');
    const joined = await acceptInvite(browser, once);
    await joined.context().close();
    await page.goto(once);
    await expect(page.getByRole('heading', { name: 'Invitation not available' })).toBeVisible();
    await page.context().close();
  });

  test('accepting refuses short and mismatched passwords, and a wrong password for an existing login', async ({ browser }) => {
    const link = await invite(owner, 'weak.password@globex.test');
    const page = await newPage(browser);
    await page.goto(link);
    await page.fill('#new-password', 'elevenchars');
    await page.fill('#confirm-password', 'elevenchars');
    await page.click('button.primary');
    await expect(errorOn(page)).toContainText('at least 12 characters');
    await page.fill('#new-password', 'twelve-chars-one');
    await page.fill('#confirm-password', 'twelve-chars-two');
    await page.click('button.primary');
    await expect(errorOn(page)).toContainText('do not match');
    await expect(page.locator('.sidebar')).toHaveCount(0);
    // The server holds the same rule when the screen is bypassed.
    const token = new URL(link).searchParams.get('invite');
    const r = await api(page, 'POST', '/invitations/accept', { token, password: 'short' }, null);
    expect(r.status).toBe(400);
    expect(r.body.code).toBe('auth.weak_password');
    await page.context().close();

    // An email that already has a login must prove it with that login's password.
    const existing = await invite(owner, SUPER_ADMIN.email);
    const other = await newPage(browser);
    await other.goto(existing);
    await other.fill('#existing-password', 'not-their-password');
    await other.click('button.primary');
    await expect(errorOn(other)).toContainText(`Enter the password for ${SUPER_ADMIN.email}`);
    await other.context().close();
    await owner.locator('tr', { hasText: SUPER_ADMIN.email }).getByRole('button', { name: 'Revoke' }).click();
  });

  test('inviting refuses a bad email, no roles, both payroll roles and an existing user', async () => {
    await nav(owner, 'User management');
    await tab(owner, 'Invitations');
    for (const bad of ['', 'not-an-email', 'two@@globex.test', 'spaces in@globex.test']) {
      await owner.fill('#invite-email', bad);
      await owner.click('text=Create invitation');
      await expect(errorOn(owner)).toContainText('Enter a valid email address');
    }
    await owner.fill('#invite-email', 'no.roles@globex.test');
    await owner.uncheck(".role-picker label:has-text('Employee') input");
    await owner.click('text=Create invitation');
    await expect(errorOn(owner)).toContainText('at least one role');

    await owner.check(".role-picker label:has-text('Payroll Operator') input");
    await owner.check(".role-picker label:has-text('Payroll Approver') input");
    await owner.click('text=Create invitation');
    await expect(errorOn(owner)).toContainText('cannot be both Payroll Operator and Payroll Approver');
    await owner.uncheck(".role-picker label:has-text('Payroll Operator') input");
    await owner.uncheck(".role-picker label:has-text('Payroll Approver') input");
    await owner.check(".role-picker label:has-text('Employee') input");

    await owner.fill('#invite-email', MEERA.toUpperCase());
    await owner.click('text=Create invitation');
    await expect(errorOn(owner)).toContainText('already a user of this Organization');
    await expect(owner.locator('tr', { hasText: 'no.roles@globex.test' })).toHaveCount(0);

    // Sent anyway: an unknown role and a role list that is not a list.
    await expectRefused(owner, 'POST', '/org/invitations', [400], { email: 'x.y@globex.test', roles: ['emperor'] });
    await expectRefused(owner, 'POST', '/org/invitations', [400], { email: 'x.y@globex.test', roles: [] });
  });

  test('nobody changes their own roles or status, and the payroll roles stay apart', async () => {
    const users = (await api(owner, 'GET', '/org/users')).body;
    const me = users.find((u: any) => u.email === OWNER);
    const hr = users.find((u: any) => u.email === HR);
    await expectRefused(owner, 'PUT', `/org/users/${me.id}/roles`, [409], { roles: ['employee'] }, 'request.refused');
    await expectRefused(owner, 'POST', `/org/users/${me.id}/suspend`, [409], undefined, 'request.refused');
    // Harish is the Payroll Operator: adding Payroll Approver to him is refused.
    await expectRefused(owner, 'PUT', `/org/users/${hr.id}/roles`, [409], { roles: [...hr.roleCodes, 'payroll_approver'] }, 'role.maker_checker');
    await expectRefused(owner, 'PUT', `/org/users/${hr.id}/roles`, [400], { roles: [] });
    await expectRefused(owner, 'POST', `/org/users/${hr.id}/promote`, [400, 404]);
  });

  test('too many wrong passwords lock the login, even against the right one', async ({ browser }) => {
    const link = await invite(owner, 'lock.me@globex.test');
    (await acceptInvite(browser, link)).context().close();
    const page = await newPage(browser);
    await page.goto('/');
    await page.fill('#email', 'lock.me@globex.test');
    for (let i = 0; i < 5; i++) {
      await page.fill('#password', `wrong-password-${i}`);
      await page.click('button.primary');
      await expect(errorOn(page)).toBeVisible();
    }
    await page.fill('#password', PASSWORD);
    await page.click('button.primary');
    await expect(errorOn(page)).toContainText('Too many failed sign-ins');
    await expect(page.locator('.sidebar')).toHaveCount(0);
    await page.context().close();
  });

  test('a suspended user cannot get in, and a signed-out token is dead', async ({ browser }) => {
    const link = await invite(owner, 'suspend.me@globex.test');
    const victim = await acceptInvite(browser, link);
    const token = await victim.evaluate(() => sessionStorage.getItem('hrms-token'));
    await nav(owner, 'User management');
    await tab(owner, 'Users');
    const row = owner.locator('tr', { hasText: 'suspend.me@globex.test' });
    await row.locator('summary').click();
    await row.getByRole('button', { name: 'Suspend', exact: true }).click();
    // The open session stops working at once.
    const r = await api(victim, 'GET', '/org/leave/me/balances', undefined, token);
    expect([401, 403]).toContain(r.status);
    await victim.context().close();
    const again = await newPage(browser);
    await again.goto('/');
    await again.fill('#email', 'suspend.me@globex.test');
    await again.fill('#password', PASSWORD);
    await again.click('button.primary');
    await expect(again.locator('.sidebar')).toHaveCount(0);
    await expect(again.getByText(/suspended|no access|not available|no Organization/i).first()).toBeVisible();
    await again.context().close();

    // Sign out, then reuse the old token.
    const meera = await signIn(browser, MEERA);
    const old = await meera.evaluate(() => sessionStorage.getItem('hrms-token'));
    await meera.click('text=Sign out');
    await expect(meera.locator('#email')).toBeVisible();
    const dead = await api(meera, 'GET', '/org/leave/me/balances', undefined, old);
    expect(dead.status).toBe(401);
    const none = await api(meera, 'GET', '/org/people/employees', undefined, null);
    expect(none.status).toBe(401);
    const forged = await api(meera, 'GET', '/org/people/employees', undefined, 'forged-token-0000');
    expect(forged.status).toBe(401);
    await meera.context().close();
  });

  test('an employee sees only their own menu, and the server refuses the rest', async ({ browser }) => {
    const harish = await employmentId(owner, 'Harish Rao');
    const runs = (await api(owner, 'GET', '/org/payroll/runs')).body.runs;
    const october = runs[runs.length - 1].id;
    const harishSlip = (await api(owner, 'GET', `/org/payroll/runs/${october}`)).body.people.find((p: any) => p.name === 'Harish Rao').id;

    const meera = await signIn(browser, MEERA);
    const items = await meera.locator('button.nav-item').allInnerTexts();
    for (const hidden of ['People', 'Payroll', 'User management', 'Audit log', 'Attendance', 'Leave']) {
      expect(items, `menu shows ${hidden}`).not.toContain(hidden);
    }
    for (const [method, path, body] of [
      ['GET', '/org/payroll/setup'],
      ['GET', '/org/payroll/runs'],
      ['POST', '/org/payroll/runs', { groupId: '00000000-0000-0000-0000-000000000000', period: '2026-12-01' }],
      ['PUT', '/org/payroll/settings', {}],
      ['GET', `/org/payroll/runs/${october}`],
      ['GET', `/org/payroll/runs/${october}/outputs/bank`],
      ['GET', '/org/users'],
      ['GET', '/org/audit'],
      ['POST', '/org/invitations', { email: 'friend@globex.test', roles: ['owner'] }],
      ['POST', '/org/leave/types', { name: 'Free days' }],
      ['POST', '/org/attendance/locations', { name: 'Home' }],
      ['POST', '/org/people/departments', { name: 'Secret' }],
      ['POST', '/org/people/exits', {}],
      ['GET', `/org/people/employees/${harish}/pay`],
      ['POST', `/org/people/employees/${harish}/pay`, { validFrom: '2026-11-01', annualAmount: 1 }],
      ['POST', `/org/people/employees/${harish}/reveal`, { field: 'pan', reason: 'Just curious about it' }],
    ] as [string, string, unknown?][]) {
      await expectRefused(meera, method, path, [403, 404], body);
    }
    // Her own record only, and someone else's payslip is not found.
    const list = await api(meera, 'GET', '/org/people/employees?status=all');
    if (list.status === 200) expect(list.body.every((e: any) => e.email === MEERA || e.legalName === 'Meera Iyer')).toBe(true);
    await expectRefused(meera, 'GET', `/org/me/pay/${harishSlip}`, [404]);
    await expectRefused(meera, 'GET', `/org/me/pay/${harishSlip}/pdf`, [404]);
    await expectRefused(meera, 'GET', `/org/people/employees/${harish}`, [403, 404]);
    await meera.context().close();
  });

  test('the Payroll Operator cannot approve, pay or see their own pay; HR cannot grant admin', async ({ browser }) => {
    const harish = await employmentId(owner, 'Harish Rao');
    const users = (await api(owner, 'GET', '/org/users')).body;
    const ownerMembership = users.find((u: any) => u.email === OWNER).id;
    const runs = (await api(owner, 'GET', '/org/payroll/runs')).body.runs;
    const october = runs[runs.length - 1].id;

    const hr = await signIn(browser, HR);
    await expectRefused(hr, 'POST', `/org/payroll/runs/${october}/approve`, [403, 409]);
    await expectRefused(hr, 'POST', `/org/payroll/runs/${october}/paid`, [403, 409], { paidOn: '2026-10-30', reference: 'X' });
    await expectRefused(hr, 'GET', `/org/payroll/runs/${october}/outputs/bank`, [403]);
    await expectRefused(hr, 'GET', `/org/people/employees/${harish}/pay`, [404]);
    await expectRefused(hr, 'POST', `/org/people/employees/${harish}/pay`, [404], { validFrom: '2026-12-01', structureId: null, annualAmount: 9999999, reason: 'Raise' });
    await expectRefused(hr, 'PUT', `/org/users/${ownerMembership}/roles`, [403], { roles: ['employee'] }, 'grant.not_allowed');
    await expectRefused(hr, 'POST', `/org/users/${ownerMembership}/suspend`, [403], undefined, 'grant.not_allowed');
    await expectRefused(hr, 'POST', '/org/invitations', [403], { email: 'admin2@globex.test', roles: ['owner'] }, 'grant.not_allowed');
    // On screen: his own record has no Pay tab.
    await nav(hr, 'My profile');
    await expect(hr.getByRole('tab', { name: 'Pay' })).toHaveCount(0);
    await hr.context().close();
  });

  test('another Organization sees nothing of this one', async ({ browser }) => {
    const meeraId = await employmentId(owner, 'Meera Iyer');
    const runs = (await api(owner, 'GET', '/org/payroll/runs')).body.runs;
    const october = runs[runs.length - 1].id;

    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email);
    await admin.fill('#password', SUPER_ADMIN.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Create an Organization')).toBeVisible();
    // Refusals in the console first: no name, a bad slug.
    await admin.fill('#org-name', '');
    await admin.fill('#org-admin', 'boss@initech.test');
    await admin.click('text=Create and invite');
    await expect(errorOn(admin)).toBeVisible();
    await admin.fill('#org-name', 'Initech');
    await admin.fill('#org-slug', 'X!');
    await admin.click('text=Create and invite');
    await expect(errorOn(admin)).toContainText('slug');
    await admin.fill('#org-slug', 'initech');
    await admin.click('text=Create and invite');
    const link = await admin.locator('.copy-row input').inputValue();
    await admin.context().close();

    const boss = await acceptInvite(browser, link);
    await expectRefused(boss, 'GET', `/org/people/employees/${meeraId}`, [404]);
    await expectRefused(boss, 'GET', `/org/people/employees/${meeraId}/pay`, [404]);
    await expectRefused(boss, 'GET', `/org/payroll/runs/${october}`, [404]);
    await expectRefused(boss, 'POST', `/org/payroll/runs/${october}/approve`, [404]);
    const list = await api(boss, 'GET', '/org/people/employees?status=all');
    expect(JSON.stringify(list.body)).not.toContain('Meera');
    await nav(boss, 'Org chart');
    await expect(boss.getByText('Meera Iyer')).toHaveCount(0);
    await boss.context().close();
  });
});
