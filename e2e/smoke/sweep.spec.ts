import { test, expect, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

// Opens every screen the signed-in person can reach, every tab on it, and the first employees' tabs (including
// Set pay → Preview, which saves nothing). Anything that shows an error note, calls the API and gets 4xx/5xx,
// throws in the browser or never finishes loading is written to smoke-report/findings.md and fails the run.
//
//   SMOKE_EMAIL=... SMOKE_PASSWORD=... SMOKE_BASE_URL=https://... npx playwright test -c playwright.smoke.config.ts
//
// SMOKE_ORG picks an Organization when the login has several; SMOKE_EMPLOYEES is how many employees to open (default 5).

const EMAIL = process.env.SMOKE_EMAIL;
const PASSWORD = process.env.SMOKE_PASSWORD;
const EMPLOYEES = Number(process.env.SMOKE_EMPLOYEES) || 5;

type Finding = { where: string; kind: string; detail: string };
const findings: Finding[] = [];
const visited: string[] = [];

/** What went wrong since the last call to `drain`. */
function listen(page: Page) {
  const seen: Finding[] = [];
  let where = '';
  page.on('response', async (r) => {
    const url = new URL(r.url());
    if (!url.pathname.startsWith('/api/') || r.status() < 400) return;
    let body = '';
    try {
      body = (await r.text()).slice(0, 200);
    } catch {
      /* a body that cannot be read adds nothing */
    }
    seen.push({ where, kind: `HTTP ${r.status()}`, detail: `${r.request().method()} ${url.pathname}${url.search ? '?…' : ''} ${body}` });
  });
  page.on('pageerror', (e) => seen.push({ where, kind: 'browser error', detail: String(e).slice(0, 300) }));
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('Failed to load resource')) seen.push({ where, kind: 'console error', detail: m.text().slice(0, 300) });
  });
  return {
    at: (w: string) => (where = w),
    drain: () => seen.splice(0, seen.length),
  };
}

async function settle(page: Page) {
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
  // A screen still saying "Loading…" after that has not finished.
  await page.waitForFunction(() => !Array.from(document.querySelectorAll('main p, .content p')).some((p) => p.textContent?.trim() === 'Loading…'), null, { timeout: 8_000 }).catch(() => {});
}

async function check(page: Page, log: ReturnType<typeof listen>, where: string) {
  await settle(page);
  visited.push(where);
  for (const f of log.drain()) findings.push({ ...f, where });
  for (const text of await page.locator('[role=alert]').allInnerTexts()) {
    if (text.trim()) findings.push({ where, kind: 'error shown on screen', detail: text.trim().slice(0, 200) });
  }
  if (await page.getByText('Something went wrong', { exact: false }).count()) {
    const already = findings.some((f) => f.where === where && f.kind === 'error shown on screen');
    if (!already) findings.push({ where, kind: 'error shown on screen', detail: 'Something went wrong' });
  }
  if (await page.getByText('This page did not load').count()) findings.push({ where, kind: 'page did not load', detail: 'This page did not load' });
  if (await page.getByText('Loading…', { exact: true }).count()) findings.push({ where, kind: 'still loading', detail: 'Loading… never went away' });
}

async function tabsOn(page: Page, log: ReturnType<typeof listen>, where: string) {
  const names = (await page.getByRole('tab').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
  for (const name of names) {
    log.at(`${where} › ${name}`);
    await page.getByRole('tab', { name, exact: true }).first().click().catch((e) => findings.push({ where: `${where} › ${name}`, kind: 'tab not clickable', detail: String(e).slice(0, 150) }));
    await check(page, log, `${where} › ${name}`);
  }
}

test('every screen, tab and the first employees open without errors', async ({ page }) => {
  test.skip(!EMAIL || !PASSWORD, 'Set SMOKE_EMAIL and SMOKE_PASSWORD');
  const log = listen(page);
  // SMOKE_SELFTEST=1 makes Set pay → Preview fail on purpose, to show the sweep notices (the run should then FAIL).
  if (process.env.SMOKE_SELFTEST) await page.route('**/pay/preview', (r) => r.fulfill({ status: 500, contentType: 'application/json', body: '{"status":500,"error":"Internal Server Error"}' }));

  log.at('sign in');
  await page.goto('/');
  await page.fill('#email', EMAIL!);
  await page.fill('#password', PASSWORD!);
  await page.click('button.primary');
  if (process.env.SMOKE_ORG) {
    const choose = page.getByRole('button', { name: new RegExp(process.env.SMOKE_ORG, 'i') });
    if (await choose.count()) await choose.first().click();
  }
  await expect(page.locator('.topbar')).toBeVisible({ timeout: 30_000 });
  await check(page, log, 'after sign in');

  // Every sidebar entry.
  const links = await page.locator('a.nav-item[href]').evaluateAll((as) => as.map((a) => ({ href: a.getAttribute('href')!, label: (a.textContent || '').trim() })));
  expect(links.length, 'the sidebar offers pages').toBeGreaterThan(0);
  for (const { href, label } of links) {
    log.at(label);
    await page.goto(href);
    await expect(page.locator('.page-title')).toBeVisible({ timeout: 20_000 }).catch(() => findings.push({ where: label, kind: 'no page title', detail: href }));
    await check(page, log, label);
    await tabsOn(page, log, label);
  }

  // The first employees: each tab, and Set pay / Revise pay → Preview (nothing is saved).
  if (links.some((l) => l.href === '/people')) {
    await page.goto('/people');
    await settle(page);
    const listed = (await page.locator('button.name-link').allInnerTexts()).map((t) => t.trim()).slice(0, EMPLOYEES);
    // SMOKE_PEOPLE="Sanjay,Diya" adds named employees to the first few.
    const named = (process.env.SMOKE_PEOPLE || '').split(',').map((n) => n.trim()).filter(Boolean);
    const names = [...new Set([...listed, ...named])];
    for (const name of names) {
      const where = `People › ${name}`;
      log.at(where);
      await page.goto('/people');
      await settle(page);
      await page.locator('button.name-link', { hasText: name }).first().click();
      await check(page, log, where);
      await tabsOn(page, log, where);
      const payTab = page.getByRole('tab', { name: 'Pay', exact: true });
      if (await payTab.count()) {
        await payTab.click();
        await settle(page);
        const open = page.getByRole('button', { name: /^(Set pay|Revise pay)$/ });
        if (await open.count()) {
          await open.click();
          const form = page.locator('form.panel').filter({ has: page.locator('#pay-from') });
          if (await form.count()) {
            log.at(`${where} › Pay › Preview`);
            // Preview needs an amount and a reason; fill what the form left empty (nothing is saved by Preview).
            if (!(await page.locator('#pay-amount').inputValue())) await page.fill('#pay-amount', '500000');
            if (!(await page.locator('#pay-reason').inputValue())) await page.fill('#pay-reason', 'Smoke check');
            await form.getByRole('button', { name: 'Preview' }).click();
            await check(page, log, `${where} › Pay › Preview`);
            await form.getByRole('button', { name: 'Cancel' }).click().catch(() => {});
          }
        }
      }
    }
  }

  const unique = new Map(findings.map((f) => [`${f.where}|${f.kind}|${f.detail}`, f]));
  findings.splice(0, findings.length, ...unique.values());
  mkdirSync('smoke-report', { recursive: true });
  const lines = [
    `# Smoke sweep`,
    ``,
    `${visited.length} screens, tabs and steps opened; ${findings.length} problem${findings.length === 1 ? '' : 's'}.`,
    ``,
    findings.length ? `| Where | What | Detail |\n| --- | --- | --- |\n${findings.map((f) => `| ${f.where} | ${f.kind} | ${f.detail.replace(/\|/g, '/').replace(/\n/g, ' ')} |`).join('\n')}` : 'No problems found.',
    ``,
    `Opened:`,
    ...visited.map((v) => `- ${v}`),
  ];
  writeFileSync('smoke-report/findings.md', lines.join('\n'));
  console.log(lines.slice(0, 4 + findings.length + 2).join('\n'));
  expect(findings, 'problems found (see smoke-report/findings.md)').toEqual([]);
});
