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
/** dd/mm/yyyy for a day whose 00:10 to 00:40 is already past: today, or yesterday in the first hour after midnight. */
export function pastMidnightDmy(): string {
  const minutes = Math.floor((Date.now() + IST_OFFSET_MS) / 60_000) % 1440;
  const [y, m, d] = isoDay(minutes < 60 ? -1 : 0).split('-');
  return `${d}/${m}/${y}`;
}
/** Days from today to the next Monday (1 to 7), so a run of weekdays stays inside one week. */
export function daysToNextMonday(): number {
  const dow = istToday().getUTCDay(); // 0 = Sunday
  return ((8 - dow) % 7) || 7;
}
/** Today's day of the week in India, 0 = Sunday. */
export function istWeekday(): number {
  return istToday().getUTCDay();
}
export function currentYear(): number {
  return istToday().getUTCFullYear();
}

/**
 * Saves a screenshot for the setup and user guide when E2E_SHOTS names a folder;
 * does nothing otherwise. `full` captures the whole page, not just the window.
 */
export async function shot(page: Page, name: string, full = false) {
  const dir = process.env.E2E_SHOTS;
  if (!dir) return;
  await page.waitForTimeout(300); // let lists and transitions settle
  await page.screenshot({ path: `${dir}/${name}.png`, fullPage: full });
}

/** The result of calling the API directly as whoever is signed in on the page. */
export type ApiResult = { status: number; body: any };

/**
 * Calls the API with the page's own session, as a user tampering with requests would: the
 * negative tests use it to prove the server refuses what the screens never offer.
 */
export async function api(page: Page, method: string, path: string, body?: unknown, token?: string | null): Promise<ApiResult> {
  return page.evaluate(
    async ({ method, path, body, token }) => {
      const t = token === undefined ? sessionStorage.getItem('hrms-token') : token;
      const headers: Record<string, string> = {};
      if (t) headers.Authorization = `Bearer ${t}`;
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      const r = await fetch(`/api${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
      const text = await r.text();
      let parsed: any = text;
      try {
        parsed = JSON.parse(text);
      } catch {
        // not JSON: keep the text
      }
      return { status: r.status, body: parsed };
    },
    { method, path, body, token },
  );
}

/** Expects the server to refuse with one of the given statuses (and, if given, this error code). */
export async function expectRefused(page: Page, method: string, path: string, statuses: number[], body?: unknown, code?: string) {
  const r = await api(page, method, path, body);
  expect(statuses, `${method} ${path} answered ${r.status} ${JSON.stringify(r.body).slice(0, 200)}`).toContain(r.status);
  if (code) expect(r.body.code, `${method} ${path}`).toBe(code);
  return r;
}

/** The error shown on screen after an action. */
export function errorOn(page: Page) {
  return page.locator('.error').first();
}

/** Accepts an invitation link with a new password and returns the signed-in page. */
export async function acceptInvite(browser: Browser, link: string, password = PASSWORD, width = 1280): Promise<Page> {
  const page = await newPage(browser, width);
  await page.goto(link);
  await page.fill('#new-password', password);
  await page.fill('#confirm-password', password);
  await page.click('button.primary');
  await expect(page.locator('.sidebar')).toBeVisible();
  return page;
}
