import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { SUPER_ADMIN, PASSWORD, newPage, signIn, acceptInvite, api } from './helpers';

// An axe scan of every page each role can reach, in an Organization of its own.
//
// The standard (docs/engineering-standards.md, section 6) is no serious or critical violations on any
// page. Some exist today, so they are recorded in a11y-baseline.json (page, rule, number of elements) and the
// test fails only when something gets WORSE: a serious or critical rule appears on a page that did not
// have it, or touches more elements than before. Fixing a violation lowers the number; the test says so,
// and E2E_A11Y_UPDATE=1 rewrites the file. Moderate and minor findings are attached to the report only.
//
// Pages are found from the sidebar, so a new page is scanned without anyone adding it here. What is
// scanned is each page as first drawn, not every tab, dialog or state inside it.

const BASELINE = join(__dirname, '..', 'a11y-baseline.json');
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
const RUN = Date.now().toString(36);
const ORG = `Initech ${RUN}`;
const OWNER = `owner@initech-${RUN}.test`;
const EMPLOYEE = `employee@initech-${RUN}.test`;

type Found = Record<string, Record<string, { impact: string; nodes: number }>>;
const found: Found = {};
const minor: Record<string, string[]> = {};
const scanned = new Set<string>();
const where: Record<string, Record<string, string[]>> = {};   // selectors, for the report

async function scan(page: Page, name: string) {
  scanned.add(name);
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  for (const v of results.violations) {
    if (v.impact === 'serious' || v.impact === 'critical') {
      (found[name] ??= {})[v.id] = { impact: v.impact, nodes: v.nodes.length };
      (where[name] ??= {})[v.id] = v.nodes.map((n) => `${n.target.join(' ')}  ${n.html.slice(0, 120)}`);
    } else {
      (minor[name] ??= []).push(`${v.id} (${v.impact}, ${v.nodes.length})`);
    }
  }
}

/** Every sidebar entry the signed-in user has, each opened and scanned. */
async function scanAllPages(page: Page, role: string) {
  await page.locator('.topbar').waitFor();
  const phone = await page.locator('.menu-button').isVisible();
  const open = async () => {
    const menu = page.locator('.menu-button');
    if (phone && (await menu.getAttribute('aria-expanded')) !== 'true') await menu.click();
  };
  await open();
  const labels = (await page.locator('a.nav-item').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
  expect(labels.length, `${role} should have a sidebar`).toBeGreaterThan(0);
  for (const label of labels) {
    await open();
    await page.locator('a.nav-item').filter({ hasText: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }).click();
    await expect(page.locator('.page-title')).toHaveText(label);
    await page.waitForLoadState('networkidle');
    // a page still showing its loading placeholder is not the page: wait for it to go
    await expect(page.locator('.skeleton')).toHaveCount(0);
    await scan(page, `${role} / ${label}`);
  }
}

test.describe.serial('accessibility @a11y', () => {
  test('sign-in page, signed out', async ({ browser }) => {
    const page = await newPage(browser);
    await page.goto('/');
    await expect(page.locator('#email')).toBeVisible();
    await scan(page, 'signed out / sign-in');
    await page.context().close();
  });

  test('the platform console, and an Organization with an owner and an employee', async ({ browser }) => {
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
    await scanAllPages(admin, 'super admin');
    await admin.context().close();

    const invitation = await newPage(browser);
    await invitation.goto(link);
    await expect(invitation.locator('input[type="password"]').first()).toBeVisible();
    await scan(invitation, 'signed out / accept invitation');
    await invitation.context().close();

    const owner = await acceptInvite(browser, link);
    const invited = await api(owner, 'POST', '/org/invitations', { email: EMPLOYEE, roles: ['employee'] });
    expect(invited.status).toBe(200);
    const employee = await acceptInvite(browser, `/?invite=${encodeURIComponent(invited.body.token)}`);
    await scanAllPages(owner, 'org admin');
    await scanAllPages(employee, 'employee');
    await owner.context().close();
    await employee.context().close();
  });

  test('the same on a phone', async ({ browser }) => {
    const owner = await signIn(browser, OWNER, PASSWORD, 390);
    await scanAllPages(owner, 'org admin on a phone');
    await owner.context().close();
  });

  test('nothing serious or critical is new or worse than the baseline', async ({}, testInfo) => {
    await testInfo.attach('minor-and-moderate.json', { body: JSON.stringify(minor, null, 2), contentType: 'application/json' });
    await testInfo.attach('where.json', { body: JSON.stringify(where, null, 2), contentType: 'application/json' });
    if (Object.keys(where).length) console.log('Serious or critical findings:\n' + JSON.stringify(where, null, 2));
    await testInfo.attach('serious-and-critical.json', { body: JSON.stringify(found, null, 2), contentType: 'application/json' });
    // 1 sign-in + 1 invitation + 3 roles' sidebars + a phone's: far fewer means pages went missing from the sidebar.
    expect(scanned.size, 'pages scanned').toBeGreaterThanOrEqual(30);

    if (process.env.E2E_A11Y_UPDATE) {
      writeFileSync(BASELINE, JSON.stringify(sorted(found), null, 2) + '\n');
      return;
    }
    const baseline: Found = existsSync(BASELINE) ? JSON.parse(readFileSync(BASELINE, 'utf8')) : {};
    const worse: string[] = [];
    const better: string[] = [];
    for (const [name, rules] of Object.entries(found)) {
      for (const [rule, now] of Object.entries(rules)) {
        const before = baseline[name]?.[rule]?.nodes ?? 0;
        if (now.nodes > before) worse.push(`${name}: ${rule} (${now.impact}) touches ${now.nodes} elements, baseline ${before}`);
      }
    }
    for (const [name, rules] of Object.entries(baseline)) {
      for (const [rule, was] of Object.entries(rules)) {
        const now = found[name]?.[rule]?.nodes ?? 0;
        if (now < was.nodes) better.push(`${name}: ${rule} is down from ${was.nodes} to ${now}`);
      }
    }
    if (better.length) console.log(`Accessibility has improved; lower the baseline with E2E_A11Y_UPDATE=1:\n  ${better.join('\n  ')}`);
    expect(worse, 'serious or critical accessibility violations that are new or worse').toEqual([]);
  });
});

function sorted(value: Found): Found {
  const out: Found = {};
  for (const name of Object.keys(value).sort()) {
    out[name] = {};
    for (const rule of Object.keys(value[name]).sort()) out[name][rule] = value[name][rule];
  }
  return out;
}
