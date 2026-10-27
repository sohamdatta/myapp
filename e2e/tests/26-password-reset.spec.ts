import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, acceptInvite, signIn, expectNoConsoleErrors, PASSWORD } from './helpers';

// Forgot password, end to end: ask for a link on the sign-in page, read the email from the test mail server, choose a new
// password, find the old one and every old session gone, then change the password from inside. Needs the mail server
// (node mail-sink.mjs) and the app started against it; without E2E_MAIL_URL the suite is skipped.

const MAIL = process.env.E2E_MAIL_URL;
const STAMP = Date.now().toString(36);   // a fresh Organization each run, so a rerun on the same database works
const OWNER = `owner@initech-${STAMP}.test`;
const ORG = `Initech ${STAMP} Ltd`;
const NEW_PASSWORD = 'a-brand-new-long-password';
const THIRD_PASSWORD = 'yet-another-long-password-3';

async function mailTo(email: string, subject: string): Promise<string> {
  for (let i = 0; i < 40; i++) {
    const r = await fetch(`${MAIL}/messages?to=${encodeURIComponent(email)}`);
    const found = ((await r.json()) as { subject: string; text: string }[]).filter((m) => m.subject.includes(subject));
    if (found.length) return found[found.length - 1].text;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`no "${subject}" mail for ${email}`);
}

test.describe.serial('password reset', () => {
  test.skip(!MAIL, 'needs the test mail server (E2E_MAIL_URL)');
  let oldSession: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await oldSession?.context().close();
  });

  test('an Organization owner exists and is signed in somewhere', async ({ browser }) => {
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
    const link = await admin.locator('.copy-row input').inputValue();
    await admin.context().close();
    const owner = await acceptInvite(browser, link);
    await owner.context().close();
    oldSession = await signIn(browser, OWNER);
  });

  test('asking for a link says the same for an address with no account', async ({ browser }) => {
    const page = await newPage(browser);
    await page.goto('/');
    await page.getByRole('button', { name: 'Forgot your password?' }).click();
    await page.fill('#forgot-email', 'nobody@nowhere.test');
    await page.getByRole('button', { name: 'Send the link' }).click();
    await expect(page.getByRole('status')).toContainText('has an account, a reset link is on its way');
    await page.context().close();
  });

  test('the owner asks, reads the email, chooses a new password, and is signed out everywhere', async ({ browser }) => {
    await fetch(`${MAIL}/messages`, { method: 'DELETE' });
    const page = await newPage(browser);
    await page.goto('/');
    await page.getByRole('button', { name: 'Forgot your password?' }).click();
    await page.fill('#forgot-email', OWNER);
    await page.getByRole('button', { name: 'Send the link' }).click();
    await expect(page.getByRole('status')).toContainText('a reset link is on its way');

    const body = await mailTo(OWNER, 'Reset your password');
    const link = body.match(/https?:\/\/\S+\?reset=[A-Za-z0-9_-]+/)![0];
    const token = link.split('?reset=')[1];
    expect((await fetch(`${MAIL}/messages?to=nobody@nowhere.test`).then((r) => r.json())).length).toBe(0);

    await page.goto(`/?reset=${token}`);
    await page.fill('#new-password', 'short');
    await page.fill('#confirm-password', 'short');
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByRole('alert')).toContainText('at least 12 characters');
    await page.fill('#new-password', NEW_PASSWORD);
    await page.fill('#confirm-password', NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByRole('status')).toContainText('Your password is changed');

    // the link works once
    await page.goto(`/?reset=${token}`);
    await page.fill('#new-password', THIRD_PASSWORD);
    await page.fill('#confirm-password', THIRD_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText('expired or was already used')).toBeVisible();
    await page.context().close();

    // the session from before is ended; the old password no longer works; the new one does
    await oldSession.reload();
    await expect(oldSession.locator('#email')).toBeVisible();
    const again = await newPage(browser);
    await again.goto('/');
    await again.fill('#email', OWNER);
    await again.fill('#password', PASSWORD);
    await again.click('button.primary');
    await expect(again.getByText('Incorrect email or password')).toBeVisible();
    await again.context().close();
    const fresh = await signIn(browser, OWNER, NEW_PASSWORD);
    await fresh.context().close();
  });

  test('changing the password from inside needs the current one and tells the person', async ({ browser }) => {
    await fetch(`${MAIL}/messages`, { method: 'DELETE' });
    const page = await signIn(browser, OWNER, NEW_PASSWORD);
    await page.getByRole('button', { name: 'Security', exact: true }).click();
    await page.fill('#cp-current', 'not-my-password-at-all');
    await page.fill('#cp-new', THIRD_PASSWORD);
    await page.fill('#cp-confirm', THIRD_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText('The current password is not correct')).toBeVisible();
    await page.fill('#cp-current', NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByRole('status')).toContainText('Your password is changed');
    expect(await mailTo(OWNER, 'password was changed')).toContain('signed out everywhere else');
    await page.context().close();
    const fresh = await signIn(browser, OWNER, THIRD_PASSWORD);
    await fresh.context().close();
  });
});
