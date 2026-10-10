import { expect, type Browser, type Page } from '@playwright/test';

export const SUPER_ADMIN = {
  email: process.env.E2E_SUPERADMIN_EMAIL || 'admin@example.com',
  password: process.env.E2E_SUPERADMIN_PASSWORD || 'ChangeMe-12345',
};
export const PASSWORD = 'correct-horse-battery';
export const ORG = 'Globex Ltd';
export const OWNER = 'owner@globex.test';
export const HR = 'hr@globex.test';

/** Console errors and uncaught exceptions seen on any page opened through these helpers. */
const consoleErrors: string[] = [];

export function watch(page: Page) {
  page.on('console', (m) => {
    // A refused API call (403, 409) logs "Failed to load resource"; the tests check those on screen.
    if (m.type() === 'error' && !m.text().includes('Failed to load resource')) consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));
}

export function expectNoConsoleErrors() {
  expect(consoleErrors, 'errors in the browser console').toEqual([]);
}

/** Opens a fresh browser session (its own cookies) at the given width. */
export async function newPage(browser: Browser, width = 1280): Promise<Page> {
  const context = await browser.newContext({
    viewport: { width, height: 900 },
    timezoneId: 'Asia/Kolkata',
    locale: 'en-IN',
    acceptDownloads: true,
  });
  const page = await context.newPage();
  watch(page);
  return page;
}

export async function signIn(browser: Browser, email: string, password = PASSWORD, width = 1280): Promise<Page> {
  const page = await newPage(browser, width);
  await page.goto('/');
  await page.fill('#email', email);
  await page.fill('#password', password);
  await page.click('button.primary');
  await expect(page.locator('.sidebar')).toBeVisible();
  return page;
}

/** Clicks a sidebar entry by its exact label. */
export async function nav(page: Page, name: string) {
  await page.locator('button.nav-item').filter({ hasText: new RegExp(`^${name}$`) }).click();
}

export async function tab(page: Page, name: string) {
  await page.getByRole('tab', { name, exact: true }).click();
}

export async function expectNoSidewaysScroll(page: Page) {
  const scrolls = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(scrolls, 'page scrolls sideways').toBe(false);
}

/** Dates in India time, as the app counts them. */
const IST_OFFSET_MS = 330 * 60_000;
function istToday(): Date {
  const now = new Date(Date.now() + IST_OFFSET_MS);
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
}
export function isoDay(offsetDays = 0, from: Date = istToday()): string {
  const d = new Date(from.getTime() + offsetDays * 86_400_000);
  return d.toISOString().slice(0, 10);
}
/** dd/mm/yyyy for today, the format biometric devices export. */
export function todayDmy(): string {
  const [y, m, d] = isoDay(0).split('-');
  return `${d}/${m}/${y}`;
}
/** Days from today to the next Monday (1 to 7), so a run of weekdays stays inside one week. */
export function daysToNextMonday(): number {
  const dow = istToday().getUTCDay(); // 0 = Sunday
  return ((8 - dow) % 7) || 7;
}
export function currentYear(): number {
  return istToday().getUTCFullYear();
}
