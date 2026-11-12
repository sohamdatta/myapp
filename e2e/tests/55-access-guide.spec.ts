import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, shot as save } from './helpers';
import { join, tokenOf } from './join';

// The screenshots in docs/user-guide/administration.md, taken in a demo Organization of their own (Northwind Holdings),
// so they show tidy names. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.

const RUN = process.env.E2E_RUN || '';   // only to try the steps again on a database that already has the demo
const em = (n: string) => `${n}@holdings${RUN}.example`;
const LOGO = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAKAAAAAwCAYAAACWqXFuAAAAiElEQVR42u3asQkAIAxFwUzgkindv9YlAgnkDn4v+LAyAgAAAABY79x8nXMDAhQgAkSAAkSACFCACBABChABIkABIsAp57Xih0OAJkABClCAJkABClCAJkABClCAJkABClCAJkABClCAJkB8RhAgAkSAAkSACFCACBABChABIkABAgAAAADs8gHtZfLqPrj5egAAAABJRU5ErkJggg==', 'base64');   // 160 × 48, as in 25-branding

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}

test('the administration guide, screen by screen', async ({ browser }) => {
  test.setTimeout(300_000);
  const admin = await newPage(browser); await admin.goto('/');
  await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
  await expect(admin.getByText('Platform console')).toBeVisible();
  const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Northwind Holdings', slug: `northwind-holdings${RUN}`, adminEmail: em('owner') }));
  await admin.context().close();
  const owner = await join(browser, org.invitationToken, em('owner'));
  await owner.setViewportSize({ width: 1280, height: 1100 });
  const shot = async (p: Page, name: string, fn: () => Promise<void>) => { await fn(); await save(p, `admin-${name}`); };

  await shot(owner, '01-getting-started', async () => { await owner.reload(); await nav(owner, 'Getting started'); await expect(owner.getByRole('radio')).toHaveCount(3); });
  await owner.fill('#starter-place-name', 'Mumbai head office'); await owner.selectOption('#starter-place-state', 'MH');
  await owner.getByRole('button', { name: 'Set up with this' }).click();
  await owner.getByRole('dialog').getByRole('button', { name: 'Set up', exact: true }).click();
  await expect(owner.getByRole('progressbar', { name: 'Setup progress' })).toHaveAttribute('aria-valuenow', '3');

  // Users: the owner invites an HR Admin on screen; others through the API.
  await owner.reload(); await nav(owner, 'User management'); await tab(owner, 'Invitations');
  await owner.fill('#invite-email', em('hr'));
  await owner.check(".role-picker label:has-text('HR Admin') input");
  await owner.check(".role-picker label:has-text('Employee') input");
  await shot(owner, '02-invite', async () => {});
  await owner.click('text=Create invitation');
  const link = await owner.locator('.copy-row input').inputValue();
  const hr = await join(browser, tokenOf(link), em('hr'));
  for (const [n, roles] of [['manager', ['employee', 'manager']], ['payroll', ['payroll_operator', 'employee']], ['approver', ['payroll_approver']], ['staff', ['employee']]] as const) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles }));
    if (n !== 'staff') await (await join(browser, inv.token, em(n))).context().close();
  }
  await shot(owner, '03-users', async () => { await owner.reload(); await nav(owner, 'User management'); await expect(owner.locator('td', { hasText: em('hr') })).toBeVisible(); });
  await shot(owner, '04-edit-roles', async () => {
    await owner.locator('tr', { hasText: em('manager') }).getByText('Edit roles').click();
    await expect(owner.locator('#user-panel')).toBeVisible();
  });
  await shot(owner, '05-roles', async () => { await owner.reload(); await nav(owner, 'User management'); await tab(owner, 'Roles'); await expect(owner.locator('details.permissions').first()).toBeVisible(); });
  await shot(owner, '06-subscription', async () => { await tab(owner, 'Subscription'); await expect(owner.locator('main')).toContainText('Core HR'); });

  // Security: the dialog, and two-step sign-in being set up.
  await hr.setViewportSize({ width: 1280, height: 1100 });
  await shot(hr, '07-security', async () => { await hr.reload(); await hr.getByRole('button', { name: 'Security', exact: true }).click(); await expect(hr.locator('#cp-current')).toBeVisible(); });
  await shot(hr, '08-two-step', async () => {
    await hr.getByRole('button', { name: 'Set up two-step sign-in' }).click();
    await expect(hr.getByAltText('QR code for the authenticator app')).toBeVisible();
    await hr.getByAltText('QR code for the authenticator app').scrollIntoViewIfNeeded();
  });
  await hr.keyboard.press('Escape');

  // Settings: a safer change, then its history.
  await owner.reload(); await nav(owner, 'Settings');
  await shot(owner, '09-settings', async () => { await expect(owner.getByTestId('value-auth.invitation.valid_days')).toHaveText('7 days'); });
  await owner.locator('[data-setting="auth.invitation.valid_days"]').getByRole('button', { name: 'Change' }).click();
  await owner.fill('#set-auth-invitation-valid_days-value', '5'); await owner.fill('#set-auth-invitation-valid_days-reason', 'Shorter links for the ISO audit');
  await owner.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(owner.getByTestId('value-auth.invitation.valid_days')).toHaveText('5 days');
  await shot(owner, '10-settings-history', async () => { await tab(owner, 'History'); await expect(owner.getByTestId('settings-history').locator('tbody tr')).toHaveCount(1); });

  // Organization settings: the logo and the certificate layout.
  await owner.reload(); await nav(owner, 'Organization settings');
  await owner.setInputFiles('#logo-file', { name: 'northwind.png', mimeType: 'image/png', buffer: LOGO });
  await expect(owner.getByTestId('branding')).toContainText('Logo saved.');
  await owner.fill('#cl-header', 'Strictly confidential'); await owner.fill('#cl-name', 'A. Fernandes'); await owner.fill('#cl-designation', 'Head of Human Resources');
  await owner.getByTestId('certificate-layout').getByRole('button', { name: 'Save the layout' }).click();
  await expect(owner.getByTestId('layout-version')).toContainText('Version 1');
  await shot(owner, '11-org-settings', async () => { await owner.evaluate(() => window.scrollTo(0, 0)); });

  await shot(owner, '12-audit', async () => { await owner.reload(); await nav(owner, 'Audit log'); await expect(owner.locator('code', { hasText: 'setting.changed' }).first()).toBeVisible(); });
  await hr.context().close();
});
