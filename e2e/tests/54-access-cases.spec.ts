import { test, expect, type Page } from '@playwright/test';
import { createHmac } from 'node:crypto';
import { SUPER_ADMIN, PASSWORD, newPage, signIn, nav, tab, api } from './helpers';
import { join, tokenOf } from './join';

// Every setting and screen of getting started, users and roles, security, settings, Organization settings, the audit log and
// the platform console's modules, case by case (docs/testing/access-ui-test-cases.xlsx; the TC ids are its rows), in an
// Organization of its own, Access Labs. The owner invites Hema (HR Admin), Ravi (employee) and Pallavi (payroll).

const RUN = process.env.E2E_RUN || '';   // a suffix, to run the file again on a database that already has Access Labs
const D = `access${RUN}.test`;
const U = (n: string) => `${n}@${D}`;
const ORG = `Access Labs${RUN}`;
const LOGO = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAKAAAAAwCAYAAACWqXFuAAAAiElEQVR42u3asQkAIAxFwUzgkindv9YlAgnkDn4v+LAyAgAAAABY79x8nXMDAhQgAkSAAkSACFCACBABChABIkABIsAp57Xih0OAJkABClCAJkABClCAJkABClCAJkABClCAJkABClCAJkB8RhAgAkSAAkSACFCACBABChABIkABAgAAAADs8gHtZfLqPrj5egAAAABJRU5ErkJggg==', 'base64');   // 160 × 48, as in 25-branding
const NEW_PASSWORD = 'a-much-longer-pass-phrase-2026';
let owner: Page;
let hr: Page;
let ravi: Page;
let admin: Page;
let orgId = '';
let secret = '';
let enrolStep = 0;
let recovery: string[] = [];

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function refused(r: Promise<{ status: number; body: any }>, status: number | number[], text?: string) {
  const done = await r;
  expect([status].flat(), JSON.stringify(done.body)).toContain(done.status);
  if (text) expect(String(done.body.message) + ' ' + String(done.body.code)).toContain(text);
  return done.body;
}
function base32Decode(text: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0;
  const out: number[] = [];
  for (const ch of text.replace(/[\s=-]/g, '').toUpperCase()) {
    value = (value << 5) | alphabet.indexOf(ch); bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}
const stepNow = () => Math.floor(Date.now() / 30000);
function totp(key: string, step: number): string {
  const counter = Buffer.alloc(8); counter.writeBigUInt64BE(BigInt(step));
  const hash = createHmac('sha1', base32Decode(key)).update(counter).digest();
  const o = hash[hash.length - 1] & 0x0f;
  const bin = ((hash[o] & 0x7f) << 24) | (hash[o + 1] << 16) | (hash[o + 2] << 8) | hash[o + 3];
  return String(bin % 1_000_000).padStart(6, '0');
}
async function users(p: Page, t?: string) { await p.reload(); await nav(p, 'User management'); if (t) await tab(p, t); }
const setting = (p: Page) => p.locator('[data-setting="auth.invitation.valid_days"]');

test.describe.serial('access test cases', () => {
  test.beforeAll(async ({ browser }) => {
    admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: ORG, slug: `access-labs${RUN}`, adminEmail: U('owner') }));
    orgId = org.id;
    owner = await join(browser, org.invitationToken, U('owner'));
  });
  test.afterAll(async () => { for (const p of [owner, hr, ravi, admin]) await p?.context().close(); });

  // ---------- getting started ----------
  test('TC-U001 TC-U002 a new Organization starts at 1 of 6 with three starting setups', async () => {
    await owner.reload(); await nav(owner, 'Getting started');
    await expect(owner.getByRole('progressbar', { name: 'Setup progress' })).toHaveAttribute('aria-valuenow', '1');
    await expect(owner.getByRole('radio')).toHaveCount(3);
  });
  test('TC-U003 TC-U004 "Not yet" changes nothing; Set up adds the location, shifts and leave types', async () => {
    await owner.getByRole('radio', { name: /Factory with shifts/ }).check();
    await owner.fill('#starter-place-name', 'Coimbatore unit 1'); await owner.selectOption('#starter-place-state', 'TN');
    await owner.getByRole('button', { name: 'Set up with this' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Not yet' }).click();
    expect(await ok(api(owner, 'GET', '/org/attendance/shifts'))).toEqual([]);
    await owner.getByRole('button', { name: 'Set up with this' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Set up', exact: true }).click();
    await expect(owner.locator('.setup-step').filter({ has: owner.getByRole('heading', { name: 'Starting setup' }) })).toContainText('1 work location, 3 shifts and 3 leave types are set up');
    await expect(owner.getByRole('progressbar', { name: 'Setup progress' })).toHaveAttribute('aria-valuenow', '3');
  });
  test('TC-U005 a second starting setup is refused', async () => {
    await refused(api(owner, 'POST', '/org/setup/starter', { starter: 'office', locationName: 'Head office', stateCode: 'KA' }), 409, 'setup.already_started');
  });

  // ---------- users and roles ----------
  test('TC-U006 TC-U007 the owner invites an HR Admin; a second invitation for the address refused', async ({ browser }) => {
    await users(owner, 'Invitations');
    await owner.fill('#invite-email', U('hr'));
    await owner.check(".role-picker label:has-text('HR Admin') input");
    await owner.click('text=Create invitation');
    const link = await owner.locator('.copy-row input').inputValue();
    expect(link).toContain('/?invite=');
    await owner.fill('#invite-email', U('hr'));
    await owner.click('text=Create invitation');
    await expect(owner.locator('.error')).toBeVisible();
    hr = await join(browser, tokenOf(link), U('hr'));
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: U('ravi'), roles: ['employee'] }));
    ravi = await join(browser, inv.token, U('ravi'));
  });
  test('TC-U008 an invitation is revoked', async () => {
    await ok(api(owner, 'POST', '/org/invitations', { email: U('temp'), roles: ['employee'] }));
    await users(owner, 'Invitations');
    const row = owner.locator('tr', { hasText: U('temp') });
    await row.getByRole('button', { name: 'Revoke' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Revoke invitation' }).click();
    await expect(owner.locator('tr', { hasText: U('temp') })).toHaveCount(0);
  });
  test('TC-U009 TC-U010 the HR Admin cannot grant payroll or Org Admin roles, nor suspend the owner', async () => {
    await users(hr, 'Invitations');
    const role = (name: string) => hr.locator('.role-picker label', { hasText: name }).locator('input');
    await expect(role('Employee')).toBeEnabled();
    await expect(role('Payroll Operator')).toBeDisabled();
    await expect(role('Org Admin')).toBeDisabled();
    await tab(hr, 'Users');
    await hr.locator('tr', { hasText: U('owner') }).getByText('Edit roles').click();
    await hr.locator('#user-panel').getByRole('button', { name: 'Suspend', exact: true }).click();
    await expect(hr.locator('#user-panel .error')).toBeVisible();
  });
  test('TC-U011 TC-U012 the owner adds a role; Payroll Operator and Approver on one person refused', async () => {
    await users(owner, 'Users');
    const row = owner.locator('tr', { hasText: U('hr') });
    await row.getByText('Edit roles').click();
    const panel = owner.locator('#user-panel');
    await panel.locator("label:has-text('Manager') input").check();
    await panel.getByRole('button', { name: 'Save roles' }).click();
    await expect(row.locator('td').nth(1)).toContainText('Manager');
    const members = await ok(api(owner, 'GET', '/org/users'));
    const ravis = (members.users || members).find((m: any) => m.email === U('ravi'));
    await refused(api(owner, 'PUT', `/org/users/${ravis.membershipId ?? ravis.id}/roles`, { roles: ['employee', 'payroll_operator', 'payroll_approver'] }), [400, 409], 'Payroll Operator and Payroll Approver');
  });
  test('TC-U013 TC-U014 a suspended user is signed out; reactivated', async () => {
    const panel = owner.locator('#user-panel');
    await users(owner, 'Users');
    await owner.locator('tr', { hasText: U('ravi') }).getByText('Edit roles').click();
    await panel.getByRole('button', { name: 'Suspend', exact: true }).click();
    await expect(owner.getByText('Suspended 1')).toBeVisible();
    await ravi.reload();
    await expect(ravi.locator('#email')).toBeVisible();
    await owner.click('text=Suspended 1');
    await panel.getByRole('button', { name: 'Reactivate' }).click();
    await expect(owner.getByText('Suspended 0')).toBeVisible();
  });
  test('TC-U015 TC-U016 roles list their permissions; the subscription shows the modules', async ({ browser }) => {
    await users(owner, 'Roles');
    await expect(owner.locator('details.permissions').first()).toBeVisible();
    await tab(owner, 'Subscription');
    for (const m of ['Core HR', 'Attendance', 'Leave', 'Payroll']) await expect(owner.locator('main')).toContainText(m);
    ravi = await signIn(browser, U('ravi'));
  });

  // ---------- security ----------
  test('TC-U017 TC-U018 TC-U019 a password change needs the current password and 12 characters', async () => {
    await hr.reload();
    await hr.getByRole('button', { name: 'Security', exact: true }).click();
    await hr.fill('#cp-current', 'not-my-password-at-all'); await hr.fill('#cp-new', NEW_PASSWORD); await hr.fill('#cp-confirm', NEW_PASSWORD);
    await hr.getByRole('button', { name: 'Change password' }).click();
    await expect(hr.getByText('The current password is not correct')).toBeVisible();
    await hr.fill('#cp-current', PASSWORD); await hr.fill('#cp-new', 'short'); await hr.fill('#cp-confirm', 'short');
    await hr.getByRole('button', { name: 'Change password' }).click();
    await expect(hr.getByText(/at least 12 characters/)).toBeVisible();
    await hr.fill('#cp-new', NEW_PASSWORD); await hr.fill('#cp-confirm', NEW_PASSWORD);
    await hr.getByRole('button', { name: 'Change password' }).click();
    await expect(hr.getByRole('status')).toContainText('Your password is changed');
    await hr.keyboard.press('Escape');
  });
  test('TC-U020 TC-U021 two-step sign-in: a wrong code refused, then it is on with ten recovery codes', async () => {
    await hr.getByRole('button', { name: 'Security', exact: true }).click();
    await hr.getByRole('button', { name: 'Set up two-step sign-in' }).click();
    await expect(hr.getByAltText('QR code for the authenticator app')).toBeVisible();
    secret = (await hr.locator('#mfa-secret').innerText()).trim();
    await hr.fill('#mfa-confirm-password', NEW_PASSWORD); await hr.fill('#mfa-confirm-code', '000000');
    await hr.getByRole('button', { name: 'Turn on' }).click();
    await expect(hr.getByText('That code is not right')).toBeVisible();
    enrolStep = stepNow();
    await hr.fill('#mfa-confirm-code', totp(secret, enrolStep));
    await hr.getByRole('button', { name: 'Turn on' }).click();
    await expect(hr.locator('#recovery-codes li')).toHaveCount(10);
    recovery = await hr.locator('#recovery-codes code').allInnerTexts();
    await hr.getByRole('button', { name: 'I have saved them' }).click();
    await expect(hr.getByRole('status')).toContainText('Two-step sign-in is on');
    await hr.keyboard.press('Escape');
  });
  test('TC-U022 TC-U023 TC-U024 signing in asks for the code; a wrong one refused; a recovery code works once', async () => {
    await hr.getByRole('button', { name: 'Sign out' }).click();
    const signInWith = async () => { await hr.goto('/'); await hr.fill('#email', U('hr')); await hr.fill('#password', NEW_PASSWORD); await hr.click('button.primary'); };
    await signInWith();
    await expect(hr.getByRole('heading', { name: 'Enter your code' })).toBeVisible();
    await hr.fill('#mfa-code', '000000'); await hr.getByRole('button', { name: 'Sign in' }).click();
    await expect(hr.getByText('That code is not right')).toBeVisible();
    await hr.fill('#mfa-code', recovery[0]); await hr.getByRole('button', { name: 'Sign in' }).click();
    await expect(hr.locator('.sidebar')).toBeVisible();
    await hr.getByRole('button', { name: 'Sign out' }).click();
    await signInWith();
    await hr.fill('#mfa-code', recovery[0]); await hr.getByRole('button', { name: 'Sign in' }).click();
    await expect(hr.getByText('That code is not right')).toBeVisible();
    await hr.fill('#mfa-code', recovery[1]); await hr.getByRole('button', { name: 'Sign in' }).click();
    await expect(hr.locator('.sidebar')).toBeVisible();
  });
  test('TC-U025 turning two-step off needs the password and a code', async () => {
    await hr.getByRole('button', { name: 'Security', exact: true }).click();
    await hr.getByRole('button', { name: 'Turn off' }).click();
    await hr.fill('#mfa-off-password', 'not-my-password-at-all');
    await hr.fill('#mfa-ask-code', totp(secret, Math.max(stepNow(), enrolStep + 1)));
    const code = totp(secret, Math.max(stepNow(), enrolStep + 1));
    await hr.getByRole('button', { name: 'Turn off two-step sign-in' }).click();
    await expect(hr.getByText('The password or the code is not right')).toBeVisible();
    await hr.getByRole('button', { name: 'Turn off' }).click();
    await hr.fill('#mfa-off-password', NEW_PASSWORD); await hr.fill('#mfa-ask-code', code);
    await hr.getByRole('button', { name: 'Turn off two-step sign-in' }).click();
    await expect(hr.getByText('Set up two-step sign-in')).toBeVisible();
    await hr.keyboard.press('Escape');
  });

  // ---------- settings ----------
  test('TC-U026 TC-U027 a setting shows its default; a value out of range refused', async () => {
    await owner.reload(); await nav(owner, 'Settings');
    await expect(owner.getByTestId('value-auth.invitation.valid_days')).toHaveText('7 days');
    await expect(owner.getByTestId('source-auth.invitation.valid_days')).toHaveText('Default');
    await refused(api(owner, 'PUT', '/org/settings/auth.invitation.valid_days', { value: 31 }), 400, 'setting.out_of_range');
  });
  test('TC-U028 a safer change applies at once', async () => {
    await setting(owner).getByRole('button', { name: 'Change' }).click();
    await owner.fill('#set-auth-invitation-valid_days-value', '3'); await owner.fill('#set-auth-invitation-valid_days-reason', 'Tighter for the audit');
    await owner.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(owner.getByTestId('value-auth.invitation.valid_days')).toHaveText('3 days');
    await expect(owner.getByTestId('source-auth.invitation.valid_days')).toHaveText('Set for the Organization');
  });
  test('TC-U029 TC-U030 a weaker change asks for the password; a wrong one refused', async () => {
    await setting(owner).getByRole('button', { name: 'Change' }).click();
    await owner.fill('#set-auth-invitation-valid_days-value', '14');
    await owner.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(owner.getByText('Confirm with your password')).toBeVisible();
    await owner.fill('#set-auth-invitation-valid_days-password', 'not-my-password');
    await owner.getByRole('button', { name: 'Confirm and save' }).click();
    await expect(setting(owner).getByText('That password is not right')).toBeVisible();
    await owner.fill('#set-auth-invitation-valid_days-password', PASSWORD);
    await owner.getByRole('button', { name: 'Confirm and save' }).click();
    await expect(owner.getByTestId('value-auth.invitation.valid_days')).toHaveText('14 days');
  });
  test('TC-U031 the history lists changes; the last is undone', async () => {
    await tab(owner, 'History');
    const history = owner.getByTestId('settings-history');
    await expect(history.locator('tbody tr')).toHaveCount(2);
    await expect(history.locator('tbody tr').first()).toContainText('3 → 14');
    await expect(history.locator('tbody tr').first()).toContainText('password confirmed');
    await history.locator('tbody tr').first().getByRole('button', { name: 'Undo' }).click();
    await owner.getByRole('button', { name: 'Undo', exact: true }).last().click();
    await expect(history.locator('tbody tr')).toHaveCount(3);
    await expect(history.locator('tbody tr').nth(1)).toContainText('Undone');
    await tab(owner, 'Security');
    await expect(owner.getByTestId('value-auth.invitation.valid_days')).toHaveText('3 days');
  });
  test('TC-U032 an employee cannot see or change settings', async () => {
    await ravi.reload();
    await expect(ravi.getByRole('link', { name: 'Settings', exact: true })).toHaveCount(0);
    await refused(api(ravi, 'PUT', '/org/settings/auth.invitation.valid_days', { value: 5, reason: 'Mine' }), 403);
  });

  // ---------- Organization settings ----------
  test('TC-U033 the HR Admin has no Organization settings', async () => {
    await hr.reload();
    await expect(hr.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Organization settings' })).toHaveCount(0);
  });
  test('TC-U034 TC-U035 TC-U036 the logo: a PNG is shown to everyone, an SVG refused, removing brings Nexvance back', async () => {
    await owner.reload(); await nav(owner, 'Organization settings');
    const branding = owner.getByTestId('branding');
    await owner.setInputFiles('#logo-file', { name: 'access.png', mimeType: 'image/png', buffer: LOGO });
    await expect(branding).toContainText('Logo saved.');
    await expect(owner.getByRole('navigation', { name: 'Main' }).getByRole('img', { name: `${ORG} logo` })).toBeVisible();
    await ravi.reload();
    await expect(ravi.getByRole('navigation', { name: 'Main' }).getByRole('img', { name: `${ORG} logo` })).toBeVisible();
    await owner.setInputFiles('#logo-file', { name: 'logo.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>') });
    await expect(branding.getByRole('alert')).toContainText('PNG or JPEG');
    await branding.getByRole('button', { name: 'Remove it' }).click();
    await expect(owner.getByRole('navigation', { name: 'Main' }).getByRole('img', { name: 'Nexvance Solutions' })).toBeVisible();
  });
  test('TC-U037 TC-U038 the certificate layout is kept as a version; text it cannot print refused', async () => {
    const panel = owner.getByTestId('certificate-layout');
    await owner.fill('#cl-header', 'Strictly confidential'); await owner.fill('#cl-name', 'R. Menon'); await owner.fill('#cl-designation', 'Head of Human Resources');
    await panel.getByRole('button', { name: 'Save the layout' }).click();
    await expect(owner.getByTestId('layout-version')).toContainText('Version 1');
    await owner.fill('#cl-header', 'प्रमाणपत्र');
    await panel.getByRole('button', { name: 'Save the layout' }).click();
    await expect(panel.getByRole('alert')).toContainText('cannot print');
    await expect(owner.getByTestId('layout-version')).toContainText('Version 1');
  });

  // ---------- audit ----------
  test('TC-U039 TC-U040 the audit log records the changes and filters them', async () => {
    await owner.reload(); await nav(owner, 'Audit log');
    for (const a of ['role.granted', 'membership.suspended', 'setting.changed']) await expect(owner.locator('code', { hasText: a }).first()).toBeVisible();
    await owner.fill('#audit-filter', 'suspended');
    await expect(owner.locator('tbody tr').first()).toContainText('suspended');
    await expect(owner.locator('code', { hasText: 'role.granted' })).toHaveCount(0);
  });
  test('TC-U041 an employee has no audit log', async () => {
    await ravi.reload();
    await expect(ravi.getByRole('link', { name: 'Audit log' })).toHaveCount(0);
    await refused(api(ravi, 'GET', '/org/audit'), 403);
  });

  // ---------- platform console: modules and support ----------
  test('TC-U042 TC-U043 switching Leave off hides and refuses it; switching it on brings it back', async () => {
    await refused(api(admin, 'POST', `/platform/organizations/${orgId}/modules`, { module: 'chess', on: true }), 400);
    await ok(api(admin, 'POST', `/platform/organizations/${orgId}/modules`, { module: 'leave', on: false }));
    await ravi.reload();
    await expect(ravi.getByRole('link', { name: 'My leave' })).toHaveCount(0);
    await refused(api(ravi, 'GET', '/org/leave/me/balances'), 403, 'module.off');
    await ok(api(admin, 'POST', `/platform/organizations/${orgId}/modules`, { module: 'leave', on: true }));
    await ravi.reload();
    await expect(ravi.getByRole('link', { name: 'My leave' })).toBeVisible();
  });
  test('TC-U044 TC-U045 platform support enters read-only, and the Organization sees it', async () => {
    await admin.reload(); await nav(admin, 'Support access');
    await admin.selectOption('#g-org', { label: ORG });
    await admin.fill('#g-reason', 'Ticket 77: check the user list');
    await admin.click('text=Request access'); await admin.click('text=Start session');
    await expect(admin.locator('.support-banner')).toBeVisible();
    await nav(admin, 'User management');
    await expect(admin.locator('td', { hasText: U('owner') })).toBeVisible();
    await expect(admin.locator('td.actions button')).toHaveCount(0);
    await admin.click('text=End session');
    await owner.reload(); await nav(owner, 'Audit log');
    await expect(owner.locator('tr', { hasText: 'support.session_started' })).toBeVisible();
  });
});
