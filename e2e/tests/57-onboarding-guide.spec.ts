import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, shot as save } from './helpers';
import { join, tokenOf } from './join';

// The screenshots in docs/user-guide/onboarding.md, taken in a demo Organization of their own (Northwind Pharma),
// so they show tidy names. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.

const RUN = process.env.E2E_RUN || '';   // only to try the steps again on a database that already has the demo
const em = (n: string) => `${n}@pharma${RUN}.example`;
const PDF = Buffer.from('%PDF-1.4\n%scan\n1 0 obj <<>> endobj\n%%EOF\n');
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
const sectionCard = (page: Page, title: string) =>
  page.locator('.section-card').filter({ has: page.locator('.section-title', { hasText: new RegExp(`^${title}$`) }) });

test('the onboarding guide, screen by screen', async ({ browser }) => {
  test.setTimeout(360_000);
  const admin = await newPage(browser); await admin.goto('/');
  await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
  await expect(admin.getByText('Platform console')).toBeVisible();
  const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Northwind Pharma', slug: `northwind-pharma${RUN}`, adminEmail: em('owner') }));
  await admin.context().close();
  const owner = await join(browser, org.invitationToken, em('owner'));
  await ok(api(owner, 'POST', '/org/setup/starter', { starter: 'office', locationName: 'Hyderabad office', stateCode: 'TG' }));
  const pg: Record<string, Page> = {};
  for (const [n, roles] of Object.entries({ hr: ['hr_admin', 'employee'], mgr: ['employee', 'manager'] })) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles }));
    pg[n] = await join(browser, inv.token, em(n));
  }
  const hr = pg.hr;
  const ids = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
  await ok(api(owner, 'POST', '/org/people/departments', { name: 'Quality' }));
  const add = (body: any) => ok(api(owner, 'POST', '/org/people/employees', { joiningDate: isoDay(-400), ...body }));   // date-safe: joined long ago
  await add({ legalName: 'Lakshmi Reddy', designation: 'HR manager', membershipId: ids[em('hr')] });
  await add({ legalName: 'Vivek Rao', designation: 'Quality manager', membershipId: ids[em('mgr')] });
  await ok(api(hr, 'POST', '/org/people/setup/number-series', { name: 'Everyone', prefix: 'NWP-', digits: 4, startAt: 101, reset: 'never' }));
  await ok(api(hr, 'POST', '/org/people/setup/policies', { name: 'Code of conduct', body: 'Be honest, be kind, and keep what you learn here confidential.', due: 'before_activation' }));

  const shot = async (p: Page, name: string, fn: () => Promise<void>) => { await fn(); await save(p, `onboarding-${name}`); };
  for (const p of [hr, pg.mgr]) await p.setViewportSize({ width: 1280, height: 1100 });
  const setup = async (area: string) => { await hr.reload(); await nav(hr, 'People'); await tab(hr, 'Setup'); await tab(hr, area); };
  const joiners = async (name?: string) => {
    await hr.reload(); await nav(hr, 'People'); await tab(hr, 'Joiners');
    if (name) await hr.getByRole('button', { name, exact: true }).click();
  };

  await shot(hr, '01-setup-flows', async () => { await setup('Flows'); await expect(hr.locator('main')).toContainText('Everyone'); });
  await shot(hr, '02-setup-form', async () => { await setup('Form'); });
  await shot(hr, '03-setup-documents', async () => { await setup('Documents'); });
  await shot(hr, '04-setup-checklists', async () => { await setup('Checklists'); });
  await shot(hr, '05-setup-codes', async () => { await setup('Employee codes'); await expect(hr.locator('[data-item="Everyone"]')).toBeVisible(); });
  await shot(hr, '06-setup-settings', async () => { await setup('Settings'); await expect(hr.locator('#set-link')).toBeVisible(); });

  // HR adds Priya and invites her.
  await joiners();
  await hr.getByRole('button', { name: 'Add joiner' }).click();
  await hr.fill('#nj-name', 'Priya Menon'); await hr.fill('#nj-email', em('priya')); await hr.fill('#nj-mobile', '98480 12345');
  await hr.fill('#nj-joining', isoDay(0)); await hr.fill('#nj-designation', 'Quality analyst');
  await hr.selectOption('#nj-department', { label: 'Quality' });
  await hr.selectOption('#nj-manager', { label: 'Vivek Rao, Quality manager' });
  await hr.selectOption('#nj-location', { index: 1 }); await hr.selectOption('#nj-shift', { index: 1 });
  await shot(hr, '07-add-joiner', async () => {});
  await hr.getByRole('button', { name: 'Save and invite' }).click();
  await expect(hr.getByRole('heading', { name: /Priya Menon/ })).toBeVisible();
  const link = await hr.locator('.copy-row input').inputValue();
  await shot(hr, '08-joiner-invited', async () => {});

  // Priya on her phone.
  const priya = await join(browser, tokenOf(link), em('priya'));
  await priya.setViewportSize({ width: 390, height: 844 });
  await shot(priya, '09-welcome', async () => { await priya.reload(); await expect(priya.locator('.notice-body')).toBeVisible(); });
  await priya.getByRole('button', { name: 'I have read this' }).click();
  await sectionCard(priya, 'Personal details').locator('.section-toggle').click();
  await priya.fill('#f-person-date_of_birth', '1997-03-14'); await priya.selectOption('#f-person-gender', 'female');
  await shot(priya, '10-section', async () => { await priya.locator('#f-person-date_of_birth').scrollIntoViewIfNeeded(); });
  await priya.getByRole('button', { name: 'Send for checking' }).click();
  await expect(sectionCard(priya, 'Personal details')).toContainText('Sent for checking');
  await priya.getByRole('button', { name: 'Send for checking' }).click();   // contact, already filled by HR
  await priya.fill('#f-address-current-line1', '4-1-22 Banjara Hills'); await priya.fill('#f-address-current-city', 'Hyderabad');
  await priya.selectOption('#f-address-current-state', 'TG'); await priya.fill('#f-address-current-pin', '500034');
  await priya.getByRole('button', { name: 'Send for checking' }).click();
  await priya.getByRole('button', { name: 'Add a contact' }).click();
  await priya.fill('#f-emergency-contacts-0-name', 'Anil Menon'); await priya.fill('#f-emergency-contacts-0-relationship', 'Father');
  await priya.fill('#f-emergency-contacts-0-mobile', '9848011111');
  await priya.getByRole('button', { name: 'Send for checking' }).click();
  await priya.getByRole('button', { name: 'Send for checking' }).click();   // family: nothing yet
  await priya.fill('#f-bank-holder_name', 'Priya Menon'); await priya.fill('#f-bank-account_number', '123456789012');
  await priya.fill('#f-bank-account_number-again', '123456789012'); await priya.fill('#f-bank-ifsc', 'HDFC0001234');
  await priya.getByRole('button', { name: 'Send for checking' }).click();
  await priya.fill('#f-statutory-pan', 'ABCPM1234Q');
  await priya.getByRole('button', { name: 'Send for checking' }).click();
  await expect(sectionCard(priya, 'PAN, Aadhaar and PF')).toContainText('Sent for checking');
  const docs = sectionCard(priya, 'Documents');
  await expect(docs.locator('.section-toggle')).toHaveAttribute('aria-expanded', 'true');   // documents open by themselves after the last section
  await expect(docs.locator('.doc-row').first()).toBeVisible();
  await docs.locator('.doc-row[data-type="PAN card"] input[type=file]').setInputFiles({ name: 'PAN.png', mimeType: 'image/png', buffer: PNG });
  await docs.locator('.doc-row[data-type="Address proof"] input[type=file]').setInputFiles({ name: 'electricity bill.pdf', mimeType: 'application/pdf', buffer: PDF });
  await expect(docs.locator('.doc-row[data-type="Address proof"]')).toContainText('Waiting to be checked');
  await shot(priya, '11-documents', async () => { await docs.scrollIntoViewIfNeeded(); });
  await shot(priya, '12-progress', async () => { await priya.reload(); await expect(priya.locator('.progress-row')).toBeVisible(); });

  // HR checks.
  await joiners();
  await shot(hr, '13-joiners', async () => { await expect(hr.locator('tbody tr', { hasText: 'Priya Menon' })).toBeVisible(); });
  await hr.getByRole('button', { name: 'Priya Menon', exact: true }).click();
  const bank = sectionCard(hr, 'Bank account');
  await bank.locator('.section-toggle').click();
  await bank.getByRole('button', { name: 'Return with a note' }).click();
  await bank.locator('textarea').fill('Please add a photo of a cancelled cheque');
  await shot(hr, '14-return', async () => {});
  await bank.getByRole('button', { name: 'Return to joiner' }).click();
  await expect(bank.locator('.section-toggle')).toContainText('Returned to the joiner');
  await tab(hr, 'Documents');
  await shot(hr, '15-check-documents', async () => { await expect(hr.locator('.doc-row[data-type="PAN card"]')).toBeVisible(); });
  await tab(hr, 'Checklist');
  await shot(hr, '16-checklist', async () => { await expect(hr.locator('.task-group').first()).toBeVisible(); });
  await tab(hr, 'Letters and policies');
  await hr.selectOption('#letter-template', { label: 'Appointment letter' });
  await hr.getByRole('button', { name: 'Preview' }).click();
  await shot(hr, '17-letters', async () => { await expect(hr.locator('.letter-preview')).toContainText('Priya Menon'); });

  await shot(pg.mgr, '18-my-tasks', async () => {
    await pg.mgr.reload(); await nav(pg.mgr, 'My tasks');
    await expect(pg.mgr.locator('.task-group', { hasText: 'Priya Menon, joining' })).toBeVisible();
  });
  await priya.context().close();
});
