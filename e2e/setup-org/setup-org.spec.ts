import { test, expect, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { newPage, nav, tab, signIn, acceptInvite } from '../tests/helpers';
import { defaults, type SetupConfig } from './config';

// Sets up a whole Organization through the screens, the way a person would, and writes a report of what it did.
//
//   APP_URL (or E2E_BASE_URL)   where the app runs                                  (default http://localhost:5173)
//   SETUP_CONFIG                a JSON file merged over setup-org/config.ts
//   SETUP_EXISTING=1            the Organization already exists: sign in as its Org Admin instead of creating it
//   SETUP_ONLY                  comma-separated sections to run, e.g. "attendance,leave"
//
// A section that fails is recorded and the next one still runs. The report is in setup-report/.

function merge<T>(a: T, b: any): T {
  if (b === undefined || b === null) return a;
  if (Array.isArray(a) || typeof a !== 'object' || typeof b !== 'object') return b as T;
  const out: any = { ...a };
  for (const k of Object.keys(b)) out[k] = merge((a as any)[k], b[k]);
  return out;
}
const cfg: SetupConfig = merge(defaults, process.env.SETUP_CONFIG ? JSON.parse(readFileSync(process.env.SETUP_CONFIG, 'utf8')) : {});
const ONLY = (process.env.SETUP_ONLY || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
const OUT = 'setup-report';
mkdirSync(`${OUT}/shots`, { recursive: true });

type Row = { section: string; status: 'done' | 'failed' | 'skipped'; note: string };
const rows: Row[] = [];
const invitations: { email: string; roles: string[]; link: string }[] = [];
let owner: Page;

async function section(name: string, fn: () => Promise<string | void>) {
  if (ONLY.length && name !== 'Organization' && !ONLY.includes(name.toLowerCase())) { rows.push({ section: name, status: 'skipped', note: 'not in SETUP_ONLY' }); return; }
  await test.step(name, async () => {
    try {
      const note = (await fn()) || '';
      rows.push({ section: name, status: 'done', note });
      if (owner && !owner.isClosed()) await owner.screenshot({ path: `${OUT}/shots/${String(rows.length).padStart(2, '0')}-${name.toLowerCase().replace(/\W+/g, '-')}.png`, fullPage: true });
    } catch (e: any) {
      rows.push({ section: name, status: 'failed', note: String(e.message || e).replace(/\u001b\[[0-9;]*m/g, '').split('\n').filter((l) => l.trim()).slice(0, 4).join(' ') });
      if (owner && !owner.isClosed()) await owner.screenshot({ path: `${OUT}/shots/${String(rows.length).padStart(2, '0')}-${name.toLowerCase().replace(/\W+/g, '-')}-FAILED.png`, fullPage: true }).catch(() => {});
      await owner?.reload().catch(() => {});   // leave no half-open form for the next section
    }
  });
}

const saved = (p: Page) => expect(p.getByText('Saved.').first()).toBeVisible();
const formError = (p: Page) => p.locator('.error[role="alert"]').first();
/** Throws if the screen shows an error. Returns true when it only said the item already exists (made earlier), and closes the form. */
async function noError(p: Page, what: string): Promise<boolean> {
  await p.waitForTimeout(400);
  const e = formError(p);
  if (await e.isVisible()) {
    const text = (await e.innerText()).trim();
    if (/already exists|already there/i.test(text)) {   // made earlier (or by the starting setup): leave it as it is
      await p.getByRole('button', { name: 'Cancel' }).first().click().catch(() => {});
      return true;
    }
    throw new Error(`${what}: ${text}`);
  }
  return false;
}
async function open(p: Page, page: string, ...tabs: string[]) {
  await p.reload();
  await nav(p, page);
  for (const t of tabs) await tab(p, t);
  await p.waitForLoadState('networkidle').catch(() => {});
}

test('set up an Organization through the screens', async ({ browser }) => {
  test.setTimeout(30 * 60_000);

  // ---------- 1. the Organization and its Org Admin ----------
  await section('Organization', async () => {
    if (process.env.SETUP_EXISTING) {
      owner = await signIn(browser, cfg.org.adminEmail, cfg.org.adminPassword);
      return `signed in to the existing Organization as ${cfg.org.adminEmail}`;
    }
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', cfg.superAdmin.email);
    await admin.fill('#password', cfg.superAdmin.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    await admin.fill('#org-name', cfg.org.name);
    await admin.fill('#org-admin', cfg.org.adminEmail);
    await admin.click('text=Create and invite');
    await expect(admin.getByText(`Invitation for ${cfg.org.adminEmail}.`)).toBeVisible();
    const link = await admin.locator('.copy-row input').inputValue();
    await admin.context().close();
    owner = await acceptInvite(browser, link, cfg.org.adminPassword);
    return `created ${cfg.org.name}; ${cfg.org.adminEmail} accepted the invitation`;
  });
  if (!owner!) {
    const why = rows.find((r) => r.section === 'Organization')?.note || 'unknown';
    writeFileSync(`${OUT}/report.md`, `# Organization setup stopped\n\nOrganization: ${why}\n`);
    throw new Error(`Could not get into the Organization, so nothing else can be set up. Reason: ${why}`);
  }

  // ---------- 2. Getting started: locations, shifts, leave types and holidays in one go ----------
  await section('Getting started', async () => {
    await nav(owner, 'Getting started');
    await expect(owner.getByRole('progressbar', { name: 'Setup progress' })).toBeVisible();
    await owner.waitForLoadState('networkidle').catch(() => {});
    if ((await owner.getByRole('radio').count()) === 0) return 'starting setup was already applied';
    const label = { office: /Office/, factory: /Factory with shifts/, retail: /Retail and hospitality/ }[cfg.starter.kind];
    await owner.getByRole('radio', { name: label }).check();
    await owner.fill('#starter-place-name', cfg.starter.locationName);
    await owner.selectOption('#starter-place-state', cfg.starter.state);
    await owner.getByRole('button', { name: 'Set up with this' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Set up', exact: true }).click();
    await expect(owner.getByRole('radio')).toHaveCount(0);
    return `"${cfg.starter.kind}" setup applied at ${cfg.starter.locationName} (${cfg.starter.state})`;
  });

  // ---------- 3. users ----------
  await section('Users', async () => {
    await open(owner, 'User management', 'Invitations');
    const done: string[] = [];
    for (const u of cfg.users) {
      await owner.fill('#invite-email', u.email);
      for (const role of u.roles) await owner.check(`.role-picker label:has-text('${role}') input`);
      await owner.click('text=Create invitation');
      await expect(owner.getByText(`Invitation for ${u.email}.`)).toBeVisible();
      invitations.push({ ...u, link: await owner.locator('.copy-row input').first().inputValue() });
      for (const role of u.roles) await owner.uncheck(`.role-picker label:has-text('${role}') input`);
      done.push(u.email);
    }
    return `${done.length} invitation(s) made; links are in invitations.md`;
  });

  // ---------- 4. attendance ----------
  await section('Attendance', async () => {
    const notes: string[] = [];
    await open(owner, 'Attendance', 'Setup');
    for (const l of cfg.attendance.locations) {
      await owner.getByRole('button', { name: 'Add a work location' }).click();
      await owner.fill('#loc-name', l.name);
      await owner.fill('#loc-state', l.state);
      if (l.check !== 'none') await owner.selectOption('#loc-check', l.check);
      await owner.getByRole('button', { name: 'Save location' }).click();
      const had = await noError(owner, `location ${l.name}`);
      notes.push(`location ${l.name}${had ? ' (already there)' : ''}`);
    }
    for (const s of cfg.attendance.shifts) {
      await owner.getByRole('button', { name: 'Add a shift' }).click();
      await owner.fill('#sh-name', s.name);
      await owner.fill('#sh-start', s.start);
      await owner.fill('#sh-end', s.end);
      await owner.fill('#sh-grace', s.grace);
      await owner.getByRole('button', { name: 'Save shift' }).click();
      const had = await noError(owner, `shift ${s.name}`);
      notes.push(`shift ${s.name}${had ? ' (already there)' : ''}`);
    }
    for (const h of cfg.attendance.holidays) {
      await owner.fill('#hol-date', h.date);
      await owner.fill('#hol-name', h.name);
      await owner.getByRole('button', { name: 'Add holiday' }).click();
      const had = await noError(owner, `holiday ${h.name}`);
      notes.push(`holiday ${h.name}${had ? ' (already there)' : ''}`);
    }
    const wfh = cfg.attendance.wfh;
    const on = owner.getByLabel('Users can work from home');
    if (wfh.enabled !== (await on.isChecked())) await on.setChecked(wfh.enabled);
    if (wfh.enabled) { await owner.fill('#wfh-week', wfh.perWeek); await owner.fill('#wfh-month', wfh.perMonth); }
    await owner.getByRole('button', { name: 'Save work-from-home rules' }).click();
    await saved(owner);
    notes.push('work-from-home rules');
    return notes.join(', ');
  });

  // ---------- 5. leave ----------
  await section('Leave', async () => {
    await open(owner, 'Leave', 'Setup');
    const notes: string[] = [];
    for (const t of cfg.leave.types) {
      await owner.getByRole('button', { name: 'Add leave type' }).first().click();
      await owner.fill('#lt-name', t.name);
      await owner.fill('#lt-quota', t.quota);
      await owner.fill('#lt-cap', t.cap);
      await owner.getByRole('button', { name: 'Add leave type', exact: true }).last().click();
      const had = await noError(owner, `leave type ${t.name}`);
      notes.push(`${t.name}${had ? ' (already there)' : ''}`);
    }
    return `leave type(s): ${notes.join(', ')}`;
  });

  // ---------- 6. people ----------
  await section('People', async () => {
    await open(owner, 'People', 'Departments');
    const notes: string[] = [];
    for (const d of cfg.people.departments) {
      if (await owner.locator('td').filter({ hasText: new RegExp(`^\\s*${d.name}\\s*$`) }).count()) { notes.push(`${d.name} (already there)`); continue; }
      await owner.fill('#dep-name', d.name);
      if ((d as any).parent) await owner.selectOption('#dep-parent', { label: (d as any).parent });
      await owner.getByRole('button', { name: 'Add department' }).click();
      await noError(owner, `department ${d.name}`);
      await expect(owner.locator('td').filter({ hasText: new RegExp(`^\\s*${d.name}\\s*$`) }).first()).toBeVisible();
      notes.push(d.name);
    }
    return `departments: ${notes.join(', ')}`;
  });

  // ---------- 7. payroll ----------
  await section('Payroll', async () => {
    const e = cfg.payroll.employer;
    await open(owner, 'Payroll', 'Setup');
    await owner.fill('#pe-name', e.name);
    await owner.fill('#pe-pan', e.pan);
    await owner.fill('#pe-tan', e.tan);
    await owner.fill('#pe-address', e.address);
    await owner.fill('#pe-debit', e.debitAccount);
    await owner.fill('#pe-ifsc', e.ifsc);
    await owner.getByRole('button', { name: 'Save', exact: true }).click();
    await saved(owner);
    const regs = owner.getByTestId('registrations');
    for (const r of cfg.payroll.registrations) {
      await owner.selectOption('#reg-statute', r.statute);
      await owner.fill('#reg-number', r.number);
      await regs.getByRole('button', { name: 'Add', exact: true }).click();
      await expect(regs).toContainText(r.number);
    }
    await tab(owner, 'Pay groups');
    const g = cfg.payroll.payGroup;
    await owner.getByRole('button', { name: 'Add a pay group' }).click();
    await owner.fill('#pg-name', g.name);
    await owner.fill('#pg-cutoff', g.cutoffDay);
    await owner.fill('#pg-pay', g.payDay);
    await owner.getByRole('button', { name: 'Save pay group' }).click();
    await noError(owner, `pay group ${g.name}`);
    return `employer ${e.name}, ${cfg.payroll.registrations.length} registration(s), pay group ${g.name}`;
  });

  // ---------- 8. performance ----------
  await section('Performance', async () => {
    await open(owner, 'Performance', 'Setup');
    const c = owner.getByTestId('competencies');
    for (const k of cfg.performance.competencies) {
      await c.getByRole('button', { name: 'Add a competency' }).click();
      await owner.fill('#comp-name', k.name);
      await owner.fill('#comp-description', k.description);
      await c.getByRole('button', { name: 'Save' }).click();
      await expect(c).toContainText(k.name);
    }
    await owner.fill('#perf-checkin-days', cfg.performance.checkinDays);
    await owner.fill('#perf-recommend-days', cfg.performance.recommendDays);
    await owner.getByTestId('save-probation').click();
    await saved(owner);
    return `${cfg.performance.competencies.length} competencies, probation check-ins`;
  });

  // ---------- 9. exits ----------
  await section('Exits', async () => {
    await open(owner, 'People', 'Setup', 'Exits');
    await owner.fill('#ex-notice-permanent', cfg.exits.noticeDaysPermanent);
    await owner.fill('#ex-response', cfg.exits.responseDays);
    await owner.fill('#ex-access', cfg.exits.formerAccessMonths);
    await owner.getByRole('button', { name: 'Save exit settings' }).click();
    await saved(owner);
    return `notice ${cfg.exits.noticeDaysPermanent} days for permanent staff`;
  });

  // ---------- the report ----------
  const icon = { done: 'DONE   ', failed: 'FAILED ', skipped: 'skipped' } as const;
  writeFileSync(`${OUT}/report.md`, `# Organization setup: ${cfg.org.name}\n\n| Section | Result | What happened |\n| --- | --- | --- |\n${rows.map((r) => `| ${r.section} | ${icon[r.status].trim()} | ${r.note.replace(/\|/g, '/')} |`).join('\n')}\n`);
  if (invitations.length) {
    writeFileSync(`${OUT}/invitations.md`, `# Invitation links for ${cfg.org.name}\n\nNo email is sent. Send each person their link; they choose a password when they open it.\n\n${invitations.map((i) => `- ${i.email} (${i.roles.join(', ')}): ${i.link}`).join('\n')}\n`);
  }
  console.log('\n' + rows.map((r) => `${icon[r.status]} ${r.section}: ${r.note}`).join('\n'));
  expect(rows.filter((r) => r.status === 'failed').map((r) => `${r.section}: ${r.note}`), 'sections that failed').toEqual([]);
});
