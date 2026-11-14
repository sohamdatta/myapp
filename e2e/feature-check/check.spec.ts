import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { createHmac } from 'node:crypto';

// Feature check: signs in to the app at APP_URL with an existing account and opens every feature
// that account can see, one test per feature. It only navigates and switches tabs; it never fills in
// a form or presses a button that saves, so it is safe to run against a live deployment.
//
//   APP_URL           where the app runs, e.g. https://hrms.example.com   (required)
//   APP_EMAIL         the account to sign in with                         (required)
//   APP_PASSWORD      its password                                        (required)
//   APP_TOTP_SECRET   the authenticator key, if the account uses two-step sign-in
//   APP_ORG           the Organization to open, if the account belongs to more than one
//   APP_FEATURES      comma-separated feature names to check only those, e.g. "People,Leave"

const EMAIL = process.env.APP_EMAIL || '';
const PASSWORD = process.env.APP_PASSWORD || '';
const TOTP_SECRET = process.env.APP_TOTP_SECRET || '';
const ORG = process.env.APP_ORG || '';
const ONLY = (process.env.APP_FEATURES || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);

/** Every page the sidebar can offer (frontend/src/nav.js). Each test is skipped when the account does not see it. */
const FEATURES = [
  // An employee's own work
  'My onboarding', 'My attendance', 'My leave', 'My tasks', 'My pay', 'My performance', 'My profile', 'My exit',
  // The Organization
  'Getting started', 'Org chart', 'People', 'Attendance', 'Work locations', 'Leave', 'Exits', 'Performance',
  'Payroll', 'Tax declarations', 'User management', 'Settings', 'Audit log', 'Organization settings',
  // The platform console (Super Admin and support)
  'Organizations', 'Usage', 'Platform users', 'Payroll rules', 'Support access', 'Email',
];

// ---------- two-step sign-in codes, as an authenticator app works them out ----------

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

function totp(secret: string): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const hash = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  const binary = ((hash[offset] & 0x7f) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
  return String(binary % 1_000_000).padStart(6, '0');
}

// ---------- what a page did wrong while it was open ----------

type Problems = { console: string[]; serverErrors: string[]; refused: string[] };
const problems: Problems = { console: [], serverErrors: [], refused: [] };

function resetProblems() {
  problems.console.length = 0;
  problems.serverErrors.length = 0;
  problems.refused.length = 0;
}

function watch(page: Page) {
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('Failed to load resource')) problems.console.push(m.text());
  });
  page.on('pageerror', (e) => problems.console.push(String(e)));
  page.on('response', (r) => {
    if (!r.url().includes('/api/')) return;
    const line = `${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`;
    if (r.status() >= 500) problems.serverErrors.push(line);
    else if (r.status() >= 400 && r.status() !== 401) problems.refused.push(line);
  });
}

/** Waits for the page's requests to finish, without hanging on anything that keeps polling. */
async function settle(page: Page) {
  await page.waitForLoadState('networkidle', { timeout: 8_000 }).catch(() => {});
  await page.waitForTimeout(400);
}

/** Fails if the page shows a crash, an error message, or the server failed while it loaded. */
async function expectPageHealthy(page: Page, where: string) {
  // Soft checks: a broken tab is reported, and the rest of the feature is still checked.
  await expect.soft(page.getByText(/Something went wrong on this page|This page did not load/), `${where}: page crashed`).toHaveCount(0);
  const errors = await page.locator('.error[role="alert"]').allInnerTexts();
  expect.soft(errors.filter((t) => t.trim()), `${where}: error shown on screen`).toEqual([]);
  expect.soft([...problems.serverErrors], `${where}: server errors (5xx)`).toEqual([]);
  expect.soft([...problems.console], `${where}: errors in the browser console`).toEqual([]);
}

async function attachShot(page: Page, info: TestInfo, name: string) {
  await info.attach(name, { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
}

async function signIn(page: Page) {
  await page.goto('/');
  await page.fill('#email', EMAIL);
  await page.fill('#password', PASSWORD);
  await page.click('button.primary');

  const topbar = page.locator('.topbar');
  const mfa = page.locator('#mfa-code');
  const chooser = page.locator('.choices');
  const refused = page.locator('.error[role="alert"]');
  await expect(topbar.or(mfa).or(chooser).or(refused).first()).toBeVisible({ timeout: 30_000 });

  if (await refused.isVisible()) throw new Error(`Sign-in refused: ${await refused.innerText()}`);

  if (await mfa.isVisible()) {
    if (!TOTP_SECRET) throw new Error('This account uses two-step sign-in. Set APP_TOTP_SECRET to its authenticator key.');
    await mfa.fill(totp(TOTP_SECRET));
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(topbar.or(chooser).first()).toBeVisible({ timeout: 30_000 });
  }

  if (await chooser.isVisible()) {
    const choice = ORG ? chooser.locator('.choice', { hasText: ORG }) : chooser.locator('.choice').first();
    await choice.click();
  }
  await expect(topbar).toBeVisible({ timeout: 30_000 });
}

/** The sidebar entry for a feature, opening the menu first on a narrow screen. */
async function sidebarItem(page: Page, name: string) {
  const menu = page.locator('.menu-button');
  if ((await menu.isVisible()) && (await menu.getAttribute('aria-expanded')) !== 'true') await menu.click();
  return page.locator('a.nav-item').filter({ hasText: new RegExp(`^${name}$`) });
}

// ---------- the tests ----------

// Tests run in order in one browser. After a failure Playwright starts a fresh worker for the rest,
// which runs beforeAll again and so signs in again; the remaining features are still checked.
test.describe('feature check', () => {
  let page: Page;

  test.beforeAll(async ({ browser }) => {
    if (!process.env.APP_URL || !EMAIL || !PASSWORD) {
      throw new Error('Set APP_URL, APP_EMAIL and APP_PASSWORD (see e2e/feature-check/README.md).');
    }
    page = await (await browser.newContext({ acceptDownloads: false })).newPage();
    watch(page);
    await signIn(page);
    await settle(page);
  });

  test.afterAll(async () => {
    await page?.context().close();
  });

  test('sign-in refuses an unknown account', async ({ browser }) => {
    const p = await (await browser.newContext()).newPage();
    await p.goto('/');
    await p.fill('#email', `nobody-${Date.now()}@example.invalid`);
    await p.fill('#password', 'not-a-real-password');
    await p.click('button.primary');
    await expect(p.locator('.error[role="alert"]')).toBeVisible();
    await expect(p.locator('.topbar')).toHaveCount(0);
    await p.context().close();
  });

  test('sign in and landing page', async ({}, info) => {
    const offered = await page.locator('a.nav-item').allInnerTexts();
    info.annotations.push({ type: 'features offered', description: offered.map((t) => t.trim()).join(', ') });
    await attachShot(page, info, 'after sign-in');
    await expectPageHealthy(page, 'landing page');
  });

  for (const feature of FEATURES) {
    test(feature, async ({}, info) => {
      test.skip(ONLY.length > 0 && !ONLY.includes(feature.toLowerCase()), 'not in APP_FEATURES');
      const item = await sidebarItem(page, feature);
      test.skip((await item.count()) === 0, 'not offered to this account');

      resetProblems();
      await item.click();
      await expect(page.locator('.page-title')).toHaveText(feature);
      await settle(page);
      await attachShot(page, info, feature);
      await expectPageHealthy(page, feature);

      // Each tab on the page, by name, so a tab that changes what is shown does not upset the count.
      const tabs = (await page.locator('[role="tab"]').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
      for (const name of [...new Set(tabs)]) {
        await test.step(`tab: ${name}`, async () => {
          resetProblems();
          const tab = page.getByRole('tab', { name, exact: true }).first();
          if (!(await tab.isVisible())) return;
          await tab.click();
          await settle(page);
          await attachShot(page, info, `${feature} › ${name}`);
          await expectPageHealthy(page, `${feature} › ${name}`);
        });
      }

      if (problems.refused.length) {
        info.annotations.push({ type: 'refused requests (4xx)', description: problems.refused.join('; ') });
      }
    });
  }

  test('sign out', async () => {
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.locator('#email')).toBeVisible();
  });
});
