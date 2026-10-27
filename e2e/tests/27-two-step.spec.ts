import { test, expect, type Page } from '@playwright/test';
import { createHmac } from 'node:crypto';
import { SUPER_ADMIN, newPage, acceptInvite, signIn, expectNoConsoleErrors, PASSWORD } from './helpers';

// Two-step sign-in, end to end: set it up in the Security dialog from the key on screen, sign in with a code, see a used code
// refused, sign in with a recovery code (once), and turn it off. The test works out the codes itself, as an authenticator does.

const STAMP = Date.now().toString(36);
const OWNER = `owner@umbrella-${STAMP}.test`;
const ORG = `Umbrella ${STAMP} Ltd`;

function base32Decode(text: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of text.replace(/[\s=-]/g, '').toUpperCase()) {
    value = (value << 5) | alphabet.indexOf(ch);
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

const stepNow = () => Math.floor(Date.now() / 30000);

/** The 6-digit code for a 30-second step. */
function totp(secret: string, step: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hash = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  const binary = ((hash[offset] & 0x7f) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
  return String(binary % 1_000_000).padStart(6, '0');
}

async function startSignIn(page: Page, password = PASSWORD) {
  await page.goto('/');
  await page.fill('#email', OWNER);
  await page.fill('#password', password);
  await page.click('button.primary');
}

async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.locator('#email')).toBeVisible();
}

test.describe.serial('two-step sign-in', () => {
  let secret = '';
  let enrolStep = 0;   // the step the set-up code was accepted for; later codes must be for a later step
  let recovery: string[] = [];
  let page: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await page?.context().close();
  });

  test('an Organization owner sets it up from the key on screen and is given ten recovery codes', async ({ browser }) => {
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
    const first = await acceptInvite(browser, link);
    await first.context().close();

    page = await signIn(browser, OWNER);
    await page.getByRole('button', { name: 'Security', exact: true }).click();
    await page.getByRole('button', { name: 'Set up two-step sign-in' }).click();
    await expect(page.getByAltText('QR code for the authenticator app')).toBeVisible();
    secret = (await page.locator('#mfa-secret').innerText()).trim();
    expect(secret).toMatch(/^[A-Z2-7]{32}$/);

    await page.fill('#mfa-confirm-password', PASSWORD);
    await page.fill('#mfa-confirm-code', '000000');
    await page.getByRole('button', { name: 'Turn on' }).click();
    await expect(page.getByText('That code is not right')).toBeVisible();
    enrolStep = stepNow();
    await page.fill('#mfa-confirm-code', totp(secret, enrolStep));
    await page.getByRole('button', { name: 'Turn on' }).click();
    await expect(page.locator('#recovery-codes li')).toHaveCount(10);
    recovery = await page.locator('#recovery-codes code').allInnerTexts();
    expect(recovery.every((c) => /^[A-Z2-7]{5}-[A-Z2-7]{5}$/.test(c))).toBe(true);
    await page.getByRole('button', { name: 'I have saved them' }).click();
    await expect(page.getByRole('status')).toContainText('Two-step sign-in is on');
    await page.keyboard.press('Escape');
  });

  test('a right password is not enough; the code completes the sign-in; the same code works only once', async ({ browser }) => {
    await signOut(page);
    await startSignIn(page);
    await expect(page.getByRole('heading', { name: 'Enter your code' })).toBeVisible();
    await expect(page.locator('.topbar')).toHaveCount(0);

    await page.fill('#mfa-code', '000000');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('That code is not right')).toBeVisible();
    const code = totp(secret, enrolStep + 1);   // the next step: allowed for a clock a little fast, and later than the one used to set up
    await page.fill('#mfa-code', code);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.locator('.topbar')).toBeVisible();

    await signOut(page);
    await startSignIn(page);
    await page.fill('#mfa-code', code);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('That code is not right')).toBeVisible();
    await expect(page.locator('.topbar')).toHaveCount(0);
  });

  test('a recovery code signs in once', async ({ browser }) => {
    await startSignIn(page);
    await page.fill('#mfa-code', recovery[0]);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.locator('.topbar')).toBeVisible();
    await signOut(page);
    await startSignIn(page);
    await page.fill('#mfa-code', recovery[0]);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('That code is not right')).toBeVisible();
    await page.fill('#mfa-code', recovery[1]);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.locator('.topbar')).toBeVisible();
    await page.getByRole('button', { name: 'Security', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'recovery codes left' })).toContainText('8 recovery codes left');
    await page.keyboard.press('Escape');
  });

  test('turning it off needs the password and a fresh code, and sign-in is one step again', async ({ browser }) => {
    // codes were accepted for enrolStep and enrolStep + 1; the next one the server takes is enrolStep + 2, once the clock is within a step of it
    while (stepNow() < enrolStep + 1) await page.waitForTimeout(1000);
    await page.getByRole('button', { name: 'Security', exact: true }).click();
    await page.getByRole('button', { name: 'Turn off' }).click();
    await page.fill('#mfa-off-password', 'not-my-password-at-all');
    await page.fill('#mfa-ask-code', totp(secret, enrolStep + 2));
    await page.getByRole('button', { name: 'Turn off two-step sign-in' }).click();
    await expect(page.getByText('The password or the code is not right')).toBeVisible();
    await page.getByRole('button', { name: 'Turn off' }).click();
    await page.fill('#mfa-off-password', PASSWORD);
    await page.fill('#mfa-ask-code', totp(secret, enrolStep + 2));
    await page.getByRole('button', { name: 'Turn off two-step sign-in' }).click();
    await expect(page.getByText('Set up two-step sign-in')).toBeVisible();
    await page.keyboard.press('Escape');
    await signOut(page);
    await startSignIn(page);
    await expect(page.locator('.topbar')).toBeVisible();
  });
});
