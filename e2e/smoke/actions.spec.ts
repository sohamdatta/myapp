import { test, expect, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

// The sweep that WRITES. On a demo Organization only: it sets pay for one employee, saves their statutory profile,
// and starts a payroll run up to Calculate (it never approves or pays). Every step is checked for error notes,
// failing API calls and browser errors, as in sweep.spec.ts.
//
//   SMOKE_ALLOW_WRITES=1 SMOKE_EMPLOYEE="Nora Nopay" SMOKE_EMAIL=... SMOKE_PASSWORD=... SMOKE_BASE_URL=... \
//     npx playwright test -c playwright.smoke.config.ts actions
//
// Without SMOKE_ALLOW_WRITES=1 it does nothing, so it cannot be run against production by accident.

const EMAIL = process.env.SMOKE_EMAIL;
const PASSWORD = process.env.SMOKE_PASSWORD;
const EMPLOYEE = process.env.SMOKE_EMPLOYEE || '';

type Finding = { where: string; kind: string; detail: string };
const findings: Finding[] = [];
const done: string[] = [];
const seen: Finding[] = [];
let where = 'sign in';

function listen(page: Page) {
  page.on('response', async (r) => {
    const u = new URL(r.url());
    if (!u.pathname.startsWith('/api/') || r.status() < 400) return;
    const body = await r.text().catch(() => '');
    seen.push({ where, kind: `HTTP ${r.status()}`, detail: `${r.request().method()} ${u.pathname} ${body.slice(0, 200)}` });
  });
  page.on('pageerror', (e) => seen.push({ where, kind: 'browser error', detail: String(e).slice(0, 300) }));
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('Failed to load resource')) seen.push({ where, kind: 'console error', detail: m.text().slice(0, 300) });
  });
}

async function step(page: Page, name: string, fn: () => Promise<void>) {
  where = name;
  try {
    await fn();
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
    done.push(name);
  } catch (e) {
    findings.push({ where: name, kind: 'step failed', detail: String(e).split('\n')[0].slice(0, 250) });
  }
  findings.push(...seen.splice(0, seen.length).map((f) => ({ ...f, where: name })));
  for (const t of await page.locator('[role=alert]').allInnerTexts()) if (t.trim()) findings.push({ where: name, kind: 'error shown on screen', detail: t.trim().slice(0, 200) });
}

test('saving pay, the statutory profile and calculating a payroll run work', async ({ page }) => {
  test.skip(!EMAIL || !PASSWORD || process.env.SMOKE_ALLOW_WRITES !== '1', 'Set SMOKE_ALLOW_WRITES=1, SMOKE_EMAIL and SMOKE_PASSWORD (demo Organization only)');
  listen(page);

  await step(page, 'sign in', async () => {
    await page.goto('/');
    await page.fill('#email', EMAIL!);
    await page.fill('#password', PASSWORD!);
    await page.click('button.primary');
    await expect(page.locator('.topbar')).toBeVisible({ timeout: 30_000 });
  });

  // 1. Set pay → Preview → Save, then the statutory profile.
  await step(page, 'People › open the employee', async () => {
    await page.goto('/people');
    const link = EMPLOYEE ? page.locator('button.name-link', { hasText: EMPLOYEE }).first() : page.locator('button.name-link').first();
    await link.click();
    await page.getByRole('tab', { name: 'Pay', exact: true }).click();
  });
  await step(page, 'Pay › Set pay › Preview', async () => {
    await page.getByRole('button', { name: /^(Set pay|Revise pay)$/ }).click();
    await page.fill('#pay-amount', '600000');
    await page.fill('#pay-reason', 'Smoke check');
    await page.getByRole('button', { name: 'Preview' }).click();
    await expect(page.getByRole('button', { name: /^Save from/ })).toBeVisible();
  });
  await step(page, 'Pay › Set pay › Save', async () => {
    await page.getByRole('button', { name: /^Save from/ }).click();
    await expect(page.getByRole('button', { name: /^Save from/ })).toHaveCount(0);
    await expect(page.getByText('6,00,000').first()).toBeVisible();
  });
  await step(page, 'Pay › Statutory profile › Save', async () => {
    const box = page.getByLabel('Exempt from professional tax');
    const before = await box.isChecked();
    await box.setChecked(!before);
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('button', { name: 'Save profile' })).toBeEnabled();
    await box.setChecked(before); // put it back
    await page.getByRole('button', { name: 'Save profile' }).click();
    await expect(page.getByRole('button', { name: 'Save profile' })).toBeEnabled();
  });

  // 2. Payroll: start the next month if nothing is open, then Freeze → Calculate. Never Approve.
  await step(page, 'Payroll › open or start a run', async () => {
    await page.goto('/payroll');
    const open = page.getByRole('button', { name: /^Open / }).first();
    const start = page.getByRole('button', { name: /^Start / }).first();
    await open.or(start).waitFor({ timeout: 60_000 }); // the page may still be drawing
    if (await open.count()) await open.click();
    else await start.click();
    await expect(page.locator('.run-steps')).toBeVisible();
  });
  await step(page, 'Payroll › Freeze inputs', async () => {
    const freeze = page.getByRole('button', { name: 'Freeze inputs' });
    if (await freeze.count()) {
      await freeze.click();
      await expect(freeze).toHaveCount(0);
    }
  });
  await step(page, 'Payroll › Calculate', async () => {
    const calc = page.getByRole('button', { name: 'Calculate' });
    if (await calc.count()) {
      await calc.click();
      await expect(page.getByRole('heading', { name: 'Register' })).toBeVisible({ timeout: 120_000 });
    }
  });

  mkdirSync('smoke-report', { recursive: true });
  const unique = [...new Map(findings.map((f) => [`${f.where}|${f.kind}|${f.detail}`, f])).values()];
  const out = [
    `# Smoke actions`,
    ``,
    `${done.length} steps done; ${unique.length} problem${unique.length === 1 ? '' : 's'}.`,
    ``,
    unique.length ? `| Where | What | Detail |\n| --- | --- | --- |\n${unique.map((f) => `| ${f.where} | ${f.kind} | ${f.detail.replace(/\|/g, '/').replace(/\n/g, ' ')} |`).join('\n')}` : 'No problems found.',
    ``,
    `Done:`,
    ...done.map((d) => `- ${d}`),
  ];
  writeFileSync('smoke-report/actions.md', out.join('\n'));
  console.log(out.join('\n'));
  expect(unique, 'problems found (see smoke-report/actions.md)').toEqual([]);
});
