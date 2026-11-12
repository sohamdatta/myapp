import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay } from './helpers';
import { join, tokenOf } from './join';

// Every onboarding setting and screen, case by case (docs/testing/onboarding-ui-test-cases.xlsx; the TC ids are its rows), in
// an Organization of its own, Join Labs. Hema (HR Admin) sets onboarding up and adds joiners; Mohan manages them; Arun audits.
// Meera joins today and becomes an employee; Nina gets an offer and accepts it; Rohan declines his; Kiran is withdrawn.

const RUN = process.env.E2E_RUN || '';   // a suffix, to run the file again on a database that already has Join Labs
const D = `join${RUN}.test`;
const U = (n: string) => `${n}@${D}`;
const ORG = `Join Labs${RUN}`;
const PAN = 'BQKPI4321M';
const ACCOUNT = '501002003004';
const PDF = Buffer.from('%PDF-1.4\n%address proof\n1 0 obj <<>> endobj\n%%EOF\n');
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const P: Record<string, Page> = {};
let owner: Page;
let hr: Page;
let meera: Page;
let nina: Page;
let meeraLink = '';
let ninaLink = '';

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function refused(r: Promise<{ status: number; body: any }>, status: number | number[], text?: string) {
  const done = await r;
  expect([status].flat(), JSON.stringify(done.body)).toContain(done.status);
  if (text) expect(String(done.body.message) + ' ' + String(done.body.code)).toContain(text);
  return done.body;
}
const sectionCard = (page: Page, title: string) =>
  page.locator('.section-card').filter({ has: page.locator('.section-title', { hasText: new RegExp(`^${title}$`) }) });
async function setup(p: Page, area?: string) { await p.reload(); await nav(p, 'People'); await tab(p, 'Setup'); if (area) await tab(p, area); }
async function joiners(p: Page, name?: string) {
  await p.reload(); await nav(p, 'People'); await tab(p, 'Joiners');
  if (name) { await p.getByRole('button', { name, exact: true }).click(); await expect(p.getByRole('heading', { name: new RegExp(name) })).toBeVisible(); }
}
async function addJoiner(p: Page, name: string, email: string, mobile: string, invite = true) {
  await joiners(p);
  await p.getByRole('button', { name: 'Add joiner' }).click();
  await p.fill('#nj-name', name); await p.fill('#nj-email', email); await p.fill('#nj-mobile', mobile);
  await p.fill('#nj-joining', isoDay(0)); await p.fill('#nj-designation', 'Sales executive');
  await p.selectOption('#nj-department', { label: 'Sales' });
  await p.selectOption('#nj-manager', { label: 'Mohan Das, Sales manager' });
  await p.selectOption('#nj-location', { index: 1 }); await p.selectOption('#nj-shift', { index: 1 });
  await p.getByRole('button', { name: invite ? 'Save and invite' : 'Save, invite later' }).click();
  await expect(p.getByRole('heading', { name: new RegExp(name) })).toBeVisible();
  return invite ? p.locator('.copy-row input').inputValue() : '';
}

test.describe.serial('onboarding test cases', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: ORG, slug: `join-labs${RUN}`, adminEmail: U('owner') }));
    await admin.context().close();
    owner = await join(browser, org.invitationToken, U('owner'));
    await ok(api(owner, 'POST', '/org/setup/starter', { starter: 'office', locationName: 'Pune office', stateCode: 'MH' }));
    for (const [n, roles] of Object.entries({ hr: ['hr_admin', 'employee'], mgr: ['employee', 'manager'], aud: ['auditor'], emp: ['employee'] })) {
      const inv = await ok(api(owner, 'POST', '/org/invitations', { email: U(n), roles }));
      P[n] = await join(browser, inv.token, U(n));
    }
    hr = P.hr;
    const ids = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
    await ok(api(owner, 'POST', '/org/people/departments', { name: 'Sales' }));
    const add = (body: any) => ok(api(owner, 'POST', '/org/people/employees', { joiningDate: isoDay(-400), ...body }));   // date-safe: joined long ago
    await add({ legalName: 'Hema Iyer', designation: 'HR manager', membershipId: ids[U('hr')] });
    await add({ legalName: 'Mohan Das', designation: 'Sales manager', membershipId: ids[U('mgr')] });
    await add({ legalName: 'Esha Kapoor', designation: 'Associate', membershipId: ids[U('emp')] });
  });
  test.afterAll(async () => { for (const p of [owner, meera, nina, ...Object.values(P)]) await p?.context().close(); });

  // ---------- setup ----------
  test('TC-J001 TC-J002 setup opens on the default flow; the auditor reads it and cannot change it', async () => {
    await setup(hr, 'Flows');
    await expect(hr.locator('main')).toContainText('Everyone');
    await setup(P.aud);
    await expect(P.aud.getByText('You can see how onboarding is set up; changing it needs the Org Admin or an HR Admin.')).toBeVisible();
  });
  test('TC-J003 TC-J004 an employee code series shows its next code; a bad token refused', async () => {
    await setup(hr, 'Employee codes');
    await hr.getByRole('button', { name: 'Add a series' }).click();
    await hr.fill('#ser-name', 'Everyone'); await hr.fill('#ser-prefix', 'JL-{YY}-'); await hr.fill('#ser-digits', '4');
    await expect(hr.getByTestId('series-example')).toHaveText(`JL-${isoDay(0).slice(2, 4)}-0001`);
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.locator('[data-item="Everyone"]')).toBeVisible();
    await refused(api(hr, 'POST', '/org/people/setup/number-series', { name: 'Odd', prefix: 'X-{CITY}-', digits: 4, startAt: 1, reset: 'never' }), 400);
  });
  test('TC-J005 a policy to acknowledge before joining', async () => {
    await setup(hr, 'Policies');
    await hr.getByRole('button', { name: 'Add a policy' }).click();
    await hr.fill('#pol-name', 'Code of conduct'); await hr.selectOption('#pol-due', 'before_activation');
    await hr.fill('#pol-body', 'Be honest, be kind, and keep what you learn here confidential.');
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.locator('[data-item="Code of conduct"]')).toBeVisible();
  });
  test('TC-J006 a document type of the Organization’s own', async () => {
    await setup(hr, 'Documents');
    await hr.getByRole('button', { name: 'Add a document' }).click();
    await hr.fill('#doc-name', 'Degree certificate'); await hr.selectOption('#doc-required', 'optional');
    await hr.fill('#doc-instructions', 'Your highest degree, both sides');
    await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.locator('[data-item="Degree certificate"]')).toBeVisible();
  });
  test('TC-J007 a field of the Organization’s own', async () => {
    await setup(hr, 'Form');
    await hr.getByRole('button', { name: 'Your own fields' }).click();
    await hr.getByRole('button', { name: 'Add a field' }).click();
    await hr.fill('#cf-label', 'T-shirt size'); await hr.fill('#cf-key', 'tshirt_size');
    await hr.selectOption('#cf-type', 'select'); await hr.fill('#cf-options', 'S, M, L, XL');
    await hr.getByRole('button', { name: 'Save field' }).click();
    await expect(hr.locator('main')).toContainText('T-shirt size');
  });
  test('TC-J008 TC-J009 joining settings: a link of 0 days refused; saved', async () => {
    await refused(api(hr, 'PUT', '/org/people/setup/settings', { inviteWhen: 'manual', linkValidDays: 0, activation: 'automatic', offerStage: false,
      offerExpiryDays: 7, withdrawnRetentionDays: 90, exitedRetentionYears: 5, allowActivationWithoutPan: true, rehireMatch: ['pan'] }), 400);
    await setup(hr, 'Settings');
    await hr.fill('#set-link', '10');
    await hr.getByRole('button', { name: 'Save settings' }).click();
    await expect(hr.getByRole('status').filter({ hasText: 'Settings saved.' })).toBeVisible();
  });

  // ---------- adding a joiner ----------
  test('TC-J010 TC-J011 a joiner needs a name; one joining more than 90 days ago refused', async () => {
    await joiners(hr);
    await hr.getByRole('button', { name: 'Add joiner' }).click();
    await hr.fill('#nj-joining', isoDay(0)); await hr.fill('#nj-designation', 'Sales executive');
    await hr.getByRole('button', { name: 'Save, invite later' }).click();
    await expect(hr.locator('.error').first()).toBeVisible();
    await refused(api(hr, 'POST', '/org/people/joiners', { legalName: 'Old Joiner', joiningDate: isoDay(-200), designation: 'Clerk' }), 400);   // date-safe: well past the 90-day limit
  });
  test('TC-J012 TC-J013 HR adds and invites Meera; employment waits to be checked, the rest for the joiner', async () => {
    meeraLink = await addJoiner(hr, 'Meera Iyer', U('meera'), '91234 56780');
    expect(meeraLink).toContain('/?invite=');
    await expect(sectionCard(hr, 'Employment')).toContainText('To check');
    await expect(hr.locator('.blockers')).toContainText('Personal details: waiting for the joiner');
  });

  // ---------- the joiner ----------
  test('TC-J014 TC-J015 the joiner sees only their onboarding; the notice comes first', async ({ browser }) => {
    meera = await join(browser, tokenOf(meeraLink), U('meera'));
    await expect(meera.getByRole('heading', { name: `Welcome to ${ORG}, Meera` })).toBeVisible();
    await expect(meera.locator('a.nav-item')).toHaveCount(1);
    await expect(meera.locator('.welcome')).toContainText('reporting to Mohan Das');
    await expect(meera.locator('.notice-body')).toContainText('Why we ask for this information');
    await meera.getByRole('button', { name: 'I have read this' }).click();
    await expect(meera.getByRole('progressbar')).toBeVisible();
  });
  test('TC-J016 TC-J017 personal details: what is required, and adults only', async () => {
    const card = sectionCard(meera, 'Personal details');
    await card.locator('.section-toggle').click();
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card.locator('.error')).toContainText('Still needed');
    await meera.fill('#f-person-date_of_birth', isoDay(-365 * 17));   // date-safe: seventeen years ago
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card.locator('.error')).toContainText('18 or older');
    await meera.fill('#f-person-date_of_birth', '1998-05-01'); await meera.selectOption('#f-person-gender', 'female');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card).toContainText('Sent for checking');
  });
  test('TC-J018 TC-J019 TC-J020 contact (prefilled), addresses and an emergency contact', async () => {
    await expect(meera.locator('#f-person-mobile')).toHaveValue('9123456780');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(sectionCard(meera, 'Contact')).toContainText('Sent for checking');
    await meera.fill('#f-address-current-line1', '12 Lake Road'); await meera.fill('#f-address-current-city', 'Pune');
    await meera.selectOption('#f-address-current-state', 'MH'); await meera.fill('#f-address-current-pin', '411001');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(sectionCard(meera, 'Addresses')).toContainText('Sent for checking');
    await meera.getByRole('button', { name: 'Add a contact' }).click();
    await meera.fill('#f-emergency-contacts-0-name', 'Ravi Iyer'); await meera.fill('#f-emergency-contacts-0-relationship', 'Father');
    await meera.fill('#f-emergency-contacts-0-mobile', '9988776655');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(sectionCard(meera, 'Emergency contact')).toContainText('Sent for checking');
  });
  test('TC-J021 nominee shares must add up to 100', async () => {
    const card = sectionCard(meera, 'Family and nominees');
    await meera.getByRole('button', { name: 'Add a family member' }).click();
    await meera.fill('#f-family-members-0-name', 'Ravi Iyer'); await meera.selectOption('#f-family-members-0-relationship', 'father');
    await meera.getByRole('button', { name: 'Add a nominee' }).click();
    await meera.fill('#f-family-nominations-0-sharePct', '60');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card.locator('.error')).toContainText('must add up to 100%');
    await meera.fill('#f-family-nominations-0-sharePct', '100');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card).toContainText('Sent for checking');
  });
  test('TC-J022 the account number is typed twice', async () => {
    const card = sectionCard(meera, 'Bank account');
    await meera.fill('#f-bank-holder_name', 'Meera Iyer'); await meera.fill('#f-bank-account_number', ACCOUNT);
    await meera.fill('#f-bank-account_number-again', '501002003005'); await meera.fill('#f-bank-ifsc', 'sbin0001234');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card.locator('.error')).toContainText('different');
    await meera.fill('#f-bank-account_number-again', ACCOUNT);
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card).toContainText('Sent for checking');
  });
  test('TC-J023 TC-J024 PAN sent; the joiner’s own field; identifiers shown as the last four', async () => {
    await meera.fill('#f-statutory-pan', PAN); await meera.selectOption('#f-statutory-tax_regime', 'old');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(sectionCard(meera, 'PAN, Aadhaar and PF')).toContainText('Sent for checking');
    await sectionCard(meera, 'PAN, Aadhaar and PF').locator('.section-toggle').click();
    await expect(sectionCard(meera, 'PAN, Aadhaar and PF')).toContainText('•••• 321M');
    await expect(meera.locator('body')).not.toContainText(PAN);
  });
  test('TC-J025 TC-J026 documents: a disguised file refused; a photo and a PDF accepted', async () => {
    const docs = sectionCard(meera, 'Documents');
    if ((await docs.locator('.section-toggle').getAttribute('aria-expanded')) !== 'true') await docs.locator('.section-toggle').click();
  await expect(docs.locator('.doc-row').first()).toBeVisible();
    const pan = docs.locator('.doc-row[data-type="PAN card"]');
    await pan.locator('input[type=file]').setInputFiles({ name: 'notes.pdf', mimeType: 'application/pdf', buffer: Buffer.from('<html>hello</html>') });
    await expect(pan.locator('.error')).toContainText('Only PDF, JPEG and PNG');
    await pan.locator('input[type=file]').setInputFiles({ name: 'IMG_0412.png', mimeType: 'image/png', buffer: PNG });
    await expect(pan).toContainText('Waiting to be checked');
    const address = docs.locator('.doc-row[data-type="Address proof"]');
    await address.locator('input[type=file]').setInputFiles({ name: 'electricity bill.pdf', mimeType: 'application/pdf', buffer: PDF });
    await expect(address).toContainText('Waiting to be checked');
    await expect(meera.locator('.progress-row')).toContainText(/\d of \d sent/);
  });

  // ---------- HR checks ----------
  test('TC-J027 TC-J028 HR returns the bank section with a note; the joiner fixes it', async () => {
    await joiners(hr);
    await expect(hr.locator('tbody tr', { hasText: 'Meera Iyer' })).toBeVisible();   // she still has the policy to acknowledge, so she waits on herself, not HR
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    const bank = sectionCard(hr, 'Bank account');
    await bank.locator('.section-toggle').click();
    await expect(bank).toContainText('•••• 3004');
    await bank.getByRole('button', { name: 'Return with a note' }).click();
    await bank.locator('textarea').fill('Please give the IFSC of your home branch');
    await bank.getByRole('button', { name: 'Return to joiner' }).click();
    await expect(bank.locator('.section-toggle')).toContainText('Returned to the joiner');
    await meera.reload();
    const card = sectionCard(meera, 'Bank account');
    await expect(card).toContainText('HR asks: Please give the IFSC of your home branch');
    await card.locator('.section-toggle').click();
    await meera.fill('#f-bank-ifsc', 'HDFC0000123');
    await meera.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card).toContainText('Sent for checking');
  });
  test('TC-J029 TC-J030 HR verifies one document and rejects another; the joiner sends it again', async () => {
    await joiners(hr, 'Meera Iyer'); await tab(hr, 'Documents');
    const pan = hr.locator('.doc-row[data-type="PAN card"]');
    await pan.getByRole('button', { name: 'View' }).click();
    await expect(hr.getByRole('dialog').locator('img')).toBeVisible();
    await hr.getByRole('dialog').getByRole('button', { name: 'Close' }).click();
    await pan.getByRole('button', { name: 'Verify' }).click();
    await expect(pan).toContainText('Verified');
    const address = hr.locator('.doc-row[data-type="Address proof"]');
    await address.getByRole('button', { name: 'Reject' }).click();
    await address.locator('input[id^="reject-"]').fill('The bill must be from the last three months');
    await address.getByRole('button', { name: 'Reject' }).click();
    await expect(address).toContainText('Rejected');
    await meera.reload();
    const docs = sectionCard(meera, 'Documents');
    await expect(docs.locator('.section-toggle')).toContainText('Needs a change');
    await docs.locator('.section-toggle').click();
    const mine = docs.locator('.doc-row[data-type="Address proof"]');
    await expect(mine).toContainText('HR says: The bill must be from the last three months');
    await mine.locator('input[type=file]').setInputFiles({ name: 'new bill.pdf', mimeType: 'application/pdf', buffer: PDF });
    await expect(mine).toContainText('Waiting to be checked');
    await expect(docs.locator('.doc-row[data-type="PAN card"] input[type=file]')).toHaveCount(0);
    await joiners(hr, 'Meera Iyer'); await tab(hr, 'Documents');
    await hr.locator('.doc-row[data-type="Address proof"]').getByRole('button', { name: 'Verify' }).click();
    await expect(hr.locator('.doc-row[data-type="Address proof"]')).toContainText('Verified');
  });
  test('TC-J031 TC-J032 the checklist: a task given to someone shows in their tasks; one skipped with a reason', async () => {
    await tab(hr, 'Checklist');
    const laptop = hr.locator('.task-row[data-task="Prepare laptop and accounts"]');
    await expect(laptop).toContainText('nobody assigned');
    await laptop.getByRole('button', { name: 'Reassign' }).click();
    await laptop.locator('select').selectOption({ label: U('owner') });
    await laptop.getByRole('button', { name: 'Reassign' }).click();
    await expect(laptop.locator('select')).toHaveCount(0);
    await owner.reload(); await nav(owner, 'My tasks');
    const task = owner.locator('.task-group', { hasText: 'Meera Iyer, joining' }).locator('.task-row[data-task="Prepare laptop and accounts"]');
    await task.getByRole('button', { name: 'Mark done' }).click();
    await expect(task).toHaveCount(0);
    await joiners(hr, 'Meera Iyer'); await tab(hr, 'Checklist');
    const email = hr.locator('.task-row[data-task="Create the official email"]');
    await email.getByRole('button', { name: 'Skip' }).click();
    await email.locator('input').fill('Uses the shared team mailbox');
    await email.getByRole('button', { name: 'Skip task' }).click();
    await expect(email).toContainText('Skipped');
  });
  test('TC-J033 the manager sees the joiner’s tasks for them', async () => {
    const m = P.mgr; await m.reload(); await nav(m, 'My tasks');
    const row = m.locator('.task-group', { hasText: 'Meera Iyer, joining' }).locator('.task-row[data-task="Plan the first week"]');
    await row.getByRole('button', { name: 'Mark done' }).click();
    await expect(row).toHaveCount(0);
  });
  test('TC-J034 TC-J035 the policy acknowledged; every section verified, Meera joins with the next code', async () => {
    await meera.reload();
    const policy = meera.locator('[data-policy="Code of conduct"]');
    await policy.getByRole('button', { name: 'Read' }).click();
    await policy.getByRole('button', { name: 'I have read and accept this policy' }).click();
    await expect(policy).toContainText('Acknowledged');
    await joiners(hr, 'Meera Iyer'); await tab(hr, 'Checklist');
    for (const title of ['Send the welcome note and invitation', 'Issue the appointment letter']) {
      const row = hr.locator(`.task-row[data-task="${title}"]`);
      await row.getByRole('button', { name: 'Mark done' }).click();
      await expect(row).toContainText('Done');
    }
    await joiners(hr, 'Meera Iyer');
    for (const title of ['Employment', 'Personal details', 'Contact', 'Addresses', 'Emergency contact', 'Family and nominees', 'Bank account', 'PAN, Aadhaar and PF']) {
      const card = sectionCard(hr, title);
      await card.locator('.section-toggle').click();
      await card.getByRole('button', { name: 'Verify' }).click();
      await expect(card.locator('.section-toggle')).toContainText('Checked');
      await card.locator('.section-toggle').click();
    }
    await joiners(hr);
    await hr.getByRole('button', { name: 'Joined', exact: true }).click();
    await expect(hr.locator('tbody tr', { hasText: 'Meera Iyer' })).toContainText('Joined');
    await tab(hr, 'Employees');
    await expect(hr.locator('tbody tr', { hasText: 'Meera Iyer' })).toContainText(`JL-${isoDay(0).slice(2, 4)}-0001`);
  });
  test('TC-J036 the new employee has an employee’s menu', async () => {
    await meera.reload();
    await expect(meera.locator('a.nav-item', { hasText: 'My attendance' })).toBeVisible();
    await expect(meera.locator('a.nav-item', { hasText: 'My onboarding' })).toHaveCount(0);
  });

  // ---------- the offer stage ----------
  test('TC-J037 TC-J038 with the offer stage on, HR previews and issues the offer', async () => {
    await setup(hr, 'Settings');
    await hr.getByLabel('Joiners get an offer letter to accept first').check();
    await hr.getByRole('button', { name: 'Save settings' }).click();
    await expect(hr.getByRole('status').filter({ hasText: 'Settings saved.' })).toBeVisible();
    ninaLink = await addJoiner(hr, 'Nina Paul', U('nina'), '91234 11111');
    await tab(hr, 'Letters and policies');
    await hr.selectOption('#letter-template', { label: 'Offer letter' }); await hr.fill('#letter-days', '5');
    await hr.getByRole('button', { name: 'Preview' }).click();
    await expect(hr.locator('.letter-preview')).toContainText('Dear Nina Paul,');
    await hr.getByRole('button', { name: 'Issue letter' }).click();
    await expect(hr.locator('.letter-row[data-letter="Offer letter"]')).toContainText('Waiting for an answer');
    await expect(hr.locator('.section-head h2')).toContainText('offered');
  });
  test('TC-J039 TC-J040 the joiner sees only the offer; declining asks first; accepting opens the welcome', async ({ browser }) => {
    nina = await join(browser, tokenOf(ninaLink), U('nina'));
    await expect(nina.getByRole('heading', { name: `${ORG} has an offer for you, Nina` })).toBeVisible();
    await expect(nina.locator('.section-card')).toHaveCount(0);
    const offer = nina.locator('[data-letter="Offer letter"]');
    await offer.getByRole('button', { name: 'Decline' }).click();
    await expect(offer).toContainText('ends your joining here');
    await offer.getByRole('button', { name: 'Keep it' }).click();
    await offer.getByRole('button', { name: 'Accept' }).click();
    await expect(nina.getByRole('heading', { name: `Welcome to ${ORG}, Nina` })).toBeVisible();
  });
  test('TC-J041 a declined offer closes the joining', async ({ browser }) => {
    const link = await addJoiner(hr, 'Rohan Das', U('rohan'), '91234 22222');
    await tab(hr, 'Letters and policies');
    await hr.selectOption('#letter-template', { label: 'Offer letter' }); await hr.fill('#letter-days', '5');
    await hr.getByRole('button', { name: 'Issue letter' }).click();
    await expect(hr.locator('.letter-row[data-letter="Offer letter"]')).toContainText('Waiting for an answer');
    const rohan = await join(browser, tokenOf(link), U('rohan'));
    const offer = rohan.locator('[data-letter="Offer letter"]');
    await offer.getByRole('button', { name: 'Decline' }).click();
    await offer.getByRole('button', { name: 'Yes, decline' }).click();
    await joiners(hr);
    await expect(hr.locator('tbody tr', { hasText: 'Rohan Das' })).toHaveCount(0);   // no longer joining
    await hr.getByRole('button', { name: 'Withdrawn', exact: true }).click();   // declined offers are listed with the withdrawn
    await expect(hr.locator('tbody tr', { hasText: 'Rohan Das' })).toContainText(/offer declined/i);
    await rohan.context().close();
  });

  // ---------- withdrawing, access, audit ----------
  test('TC-J042 TC-J043 a joiner without an email cannot be invited; a withdrawn joiner leaves the list', async () => {
    await joiners(hr);
    await hr.getByRole('button', { name: 'Add joiner' }).click();
    await hr.fill('#nj-name', 'Kiran Rao'); await hr.fill('#nj-joining', isoDay(5)); await hr.fill('#nj-designation', 'Designer');
    await hr.getByRole('button', { name: 'Save, invite later' }).click();
    await expect(hr.getByRole('heading', { name: /Kiran Rao/ })).toBeVisible();
    await expect(hr.getByRole('button', { name: 'Invite' })).toHaveCount(0);
    await hr.getByRole('button', { name: 'Withdraw' }).click();
    await hr.fill('#withdraw-reason', 'Took another offer');
    await hr.locator('form').getByRole('button', { name: 'Withdraw' }).click();
    await expect(hr.locator('.section-head h2')).toContainText('withdrawn');
    await joiners(hr);
    await expect(hr.locator('tbody tr', { hasText: 'Kiran Rao' })).toHaveCount(0);
  });
  test('TC-J044 an employee has no People page and cannot read joiners', async () => {
    const e = P.emp; await e.reload();
    await expect(e.getByRole('link', { name: 'People', exact: true })).toHaveCount(0);
    await refused(api(e, 'GET', '/org/people/joiners?status=open'), 403);
  });
  test('TC-J045 onboarding actions in the audit log', async () => {
    await owner.reload(); await nav(owner, 'Audit log');
    for (const a of ['joiner.added', 'joiner.invited', 'joiner.section_returned', 'joiner.section_verified', 'joiner.activated', 'joiner.withdrawn']) {
      await expect(owner.locator('code', { hasText: a }).first()).toBeVisible();
    }
  });
});
