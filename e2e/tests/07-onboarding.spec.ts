import { test, expect, type Page } from '@playwright/test';
import { HR, OWNER, PASSWORD, ORG, signIn, newPage, nav, tab, isoDay, expectNoSidewaysScroll, expectNoConsoleErrors, shot } from './helpers';

// Onboarding a joiner: HR adds and invites them, the joiner accepts on a phone,
// reads the notice and fills each section, HR returns one section with a note,
// the joiner fixes it, HR verifies everything, and the joiner becomes an employee
// on the joining date (today), with an employee's menu the next time they look.

const JOINER = 'meera.iyer@mail.test';

/** The card of one section, by its exact title ("Contact" is not "Emergency contact"). */
const sectionCard = (page: Page, title: string) =>
  page.locator('.section-card').filter({ has: page.locator('.section-title', { hasText: new RegExp(`^${title}$`) }) });
const PAN = 'BQKPI4321M';
const ACCOUNT = '501002003004';
const PDF = Buffer.from('%PDF-1.4\n%address proof\n1 0 obj <<>> endobj\n%%EOF\n');
// A 1 x 1 PNG, standing in for a phone photo of a PAN card.
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

test.describe.serial('onboarding', () => {
  let hr: Page;
  let joiner: Page;
  let link = '';

  test.beforeAll(async ({ browser }) => {
    hr = await signIn(browser, HR);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await hr?.context().close();
    await joiner?.context().close();
  });

  test('HR adds a joiner and invites them', async () => {
    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await expect(hr.getByText('Nobody is joining')).toBeVisible();

    await hr.getByRole('button', { name: 'Add joiner' }).click();
    await hr.fill('#nj-name', 'Meera Iyer');
    await hr.fill('#nj-email', JOINER);
    await hr.fill('#nj-mobile', '91234 56780');
    await hr.fill('#nj-joining', isoDay(0));
    await hr.fill('#nj-designation', 'Analyst');
    await hr.selectOption('#nj-department', { label: 'Engineering' });
    await hr.selectOption('#nj-manager', { label: 'Ananya Sen, Managing director' });
    await hr.fill('#nj-code', 'E050');
    await hr.selectOption('#nj-location', { index: 1 });
    await hr.selectOption('#nj-shift', { index: 1 });
    await shot(hr, '39-add-joiner', true);
    await hr.getByRole('button', { name: 'Save and invite' }).click();

    await expect(hr.getByRole('heading', { name: /Meera Iyer/ })).toBeVisible();
    link = await hr.locator('.copy-row input').inputValue();
    expect(link).toContain('/?invite=');
    // HR gave everything the Employment section needs, so it waits to be checked; the rest waits for the joiner.
    await expect(sectionCard(hr, 'Employment')).toContainText('To check');
    await expect(hr.locator('.blockers')).toContainText('Personal details: waiting for the joiner');
    await shot(hr, '40-joiner-invited', true);
  });

  test('the joiner accepts on a phone and sees only their onboarding', async ({ browser }) => {
    joiner = await newPage(browser, 390);
    await joiner.goto(link);
    await expect(joiner.getByText(`Join ${ORG}`)).toBeVisible();
    await joiner.fill('#new-password', PASSWORD);
    await joiner.fill('#confirm-password', PASSWORD);
    await shot(joiner, '41-joiner-accept');
    await joiner.click('button.primary');
    await expect(joiner.getByRole('heading', { name: `Welcome to ${ORG}, Meera` })).toBeVisible();
    await expect(joiner.locator('button.nav-item')).toHaveCount(1);
    await expect(joiner.locator('.welcome')).toContainText('reporting to Ananya Sen');

    // The notice comes first.
    await expect(joiner.locator('.notice-body')).toContainText('Why we ask for this information');
    await shot(joiner, '42-joiner-notice');
    await joiner.getByRole('button', { name: 'I have read this' }).click();
    await expect(joiner.getByRole('progressbar')).toBeVisible();
    await shot(joiner, '43-joiner-home', true);
    await expectNoSidewaysScroll(joiner);
  });

  test('the joiner fills each section and sends it for checking', async () => {
    const card = (title: string) => sectionCard(joiner, title);

    // Personal: what is required is checked on sending; adults only.
    await card('Personal details').locator('.section-toggle').click();
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Personal details').locator('.error')).toContainText('Still needed');
    await joiner.fill('#f-person-date_of_birth', isoDay(-365 * 17));
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Personal details').locator('.error')).toContainText('18 or older');
    await joiner.fill('#f-person-date_of_birth', '1998-05-01');
    await joiner.selectOption('#f-person-gender', 'female');
    await card('Personal details').scrollIntoViewIfNeeded();
    await joiner.locator('#f-person-gender').scrollIntoViewIfNeeded();
    await shot(joiner, '44-joiner-personal');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Personal details')).toContainText('Sent for checking');

    // The next section opens by itself: Contact already has the mobile HR entered.
    await expect(joiner.locator('#f-person-mobile')).toHaveValue('9123456780');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Contact')).toContainText('Sent for checking');

    await joiner.fill('#f-address-current-line1', '12 Lake Road');
    await joiner.fill('#f-address-current-city', 'Bengaluru');
    await joiner.selectOption('#f-address-current-state', 'KA');
    await joiner.fill('#f-address-current-pin', '560001');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Addresses')).toContainText('Sent for checking');

    await joiner.getByRole('button', { name: 'Add a contact' }).click();
    await joiner.fill('#f-emergency-contacts-0-name', 'Ravi Iyer');
    await joiner.fill('#f-emergency-contacts-0-relationship', 'Father');
    await joiner.fill('#f-emergency-contacts-0-mobile', '9988776655');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Emergency contact')).toContainText('Sent for checking');

    // Family: nominees come from the members listed, and shares must add up to 100.
    await joiner.getByRole('button', { name: 'Add a family member' }).click();
    await joiner.fill('#f-family-members-0-name', 'Ravi Iyer');
    await joiner.selectOption('#f-family-members-0-relationship', 'father');
    await joiner.getByRole('button', { name: 'Add a nominee' }).click();
    await joiner.fill('#f-family-nominations-0-sharePct', '60');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Family and nominees').locator('.error')).toContainText('must add up to 100%');
    await joiner.fill('#f-family-nominations-0-sharePct', '100');
    await card('Family and nominees').scrollIntoViewIfNeeded();
    await shot(joiner, '45-joiner-family');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Family and nominees')).toContainText('Sent for checking');

    // Bank: the account number is typed twice.
    await joiner.fill('#f-bank-holder_name', 'Meera Iyer');
    await joiner.fill('#f-bank-account_number', ACCOUNT);
    await joiner.fill('#f-bank-account_number-again', '501002003005');
    await joiner.fill('#f-bank-ifsc', 'sbin0001234');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Bank account').locator('.error')).toContainText('different');
    await joiner.fill('#f-bank-account_number-again', ACCOUNT);
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('Bank account')).toContainText('Sent for checking');

    await joiner.fill('#f-statutory-pan', PAN);
    await joiner.selectOption('#f-statutory-tax_regime', 'old');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card('PAN, Aadhaar and PF')).toContainText('Sent for checking');

    // Documents open next: a photo of the PAN card and a PDF of an address proof. Anything else is refused.
    const docs = card('Documents');
    const pan = docs.locator('.doc-row[data-type="PAN card"]');
    await pan.locator('input[type=file]').setInputFiles({ name: 'notes.pdf', mimeType: 'application/pdf', buffer: Buffer.from('<html>hello</html>') });
    await expect(pan.locator('.error')).toContainText('Only PDF, JPEG and PNG');
    await pan.locator('input[type=file]').setInputFiles({ name: 'IMG_0412.png', mimeType: 'image/png', buffer: PNG });
    await expect(pan).toContainText('Waiting to be checked');
    await expect(pan).toContainText('IMG_0412.png');
    const address = docs.locator('.doc-row[data-type="Address proof"]');
    await address.locator('input[type=file]').setInputFiles({ name: 'electricity bill.pdf', mimeType: 'application/pdf', buffer: PDF });
    await expect(address).toContainText('Waiting to be checked');
    await expect(docs.locator('.section-toggle')).toContainText('Sent for checking');
    await expect(joiner.locator('.progress-row')).toContainText('8 of 8 sent');
    await docs.scrollIntoViewIfNeeded();
    await shot(joiner, '46-joiner-documents');

    // Sent sections are read-only and show identifiers as the last four only.
    await card('PAN, Aadhaar and PF').locator('.section-toggle').click();
    await expect(card('PAN, Aadhaar and PF')).toContainText('•••• 321M');
    await expect(joiner.locator('body')).not.toContainText(PAN);
    await expectNoSidewaysScroll(joiner);
  });

  test('HR returns the bank section with a note, and the joiner fixes it', async () => {
    await hr.reload();
    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await expect(hr.locator('.tile', { hasText: 'Waiting on HR' })).toContainText('1');
    await shot(hr, '47-joiners-list');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();

    const bank = sectionCard(hr, 'Bank account');
    await bank.locator('.section-toggle').click();
    await expect(bank).toContainText('•••• 3004');
    await bank.getByRole('button', { name: 'Return with a note' }).click();
    await bank.locator('textarea').fill('Please give the IFSC of your home branch');
    await shot(hr, '48-return-section');
    await bank.getByRole('button', { name: 'Return to joiner' }).click();
    await expect(bank.locator('.section-toggle')).toContainText('Returned to the joiner');

    await joiner.reload();
    const card = sectionCard(joiner, 'Bank account');
    await expect(card).toContainText('HR asks: Please give the IFSC of your home branch');
    await card.scrollIntoViewIfNeeded();
    await shot(joiner, '49-joiner-returned');
    await card.locator('.section-toggle').click();
    await joiner.fill('#f-bank-ifsc', 'HDFC0000123');
    await joiner.getByRole('button', { name: 'Send for checking' }).click();
    await expect(card).toContainText('Sent for checking');
  });

  test('HR checks the documents, rejecting one, which the joiner sends again', async () => {
    await hr.reload();
    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await tab(hr, 'Documents');
    const pan = hr.locator('.doc-row[data-type="PAN card"]');
    await pan.getByRole('button', { name: 'View' }).click();
    await expect(hr.getByRole('dialog')).toBeVisible();
    await expect(hr.getByRole('dialog').locator('img')).toBeVisible();
    await hr.getByRole('dialog').getByRole('button', { name: 'Close' }).click();
    await pan.getByRole('button', { name: 'Verify' }).click();
    await expect(pan).toContainText('Verified');

    const address = hr.locator('.doc-row[data-type="Address proof"]');
    await address.getByRole('button', { name: 'Reject' }).click();
    await address.locator('input[id^="reject-"]').fill('The bill must be from the last three months');
    await shot(hr, '51-documents-tab');
    await address.getByRole('button', { name: 'Reject' }).click();
    await expect(address).toContainText('Rejected');

    await joiner.reload();
    const docs = sectionCard(joiner, 'Documents');
    await expect(docs.locator('.section-toggle')).toContainText('Needs a change');
    await docs.locator('.section-toggle').click();
    const mine = docs.locator('.doc-row[data-type="Address proof"]');
    await expect(mine).toContainText('HR says: The bill must be from the last three months');
    await mine.locator('input[type=file]').setInputFiles({ name: 'new bill.pdf', mimeType: 'application/pdf', buffer: PDF });
    await expect(mine).toContainText('Waiting to be checked');
    await expect(docs.locator('.doc-row[data-type="PAN card"] input[type=file]')).toHaveCount(0); // verified: no replacing it
    await expectNoSidewaysScroll(joiner);

    await hr.reload();
    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await tab(hr, 'Documents');
    await hr.locator('.doc-row[data-type="Address proof"]').getByRole('button', { name: 'Verify' }).click();
    await expect(hr.locator('.doc-row[data-type="Address proof"]')).toContainText('Verified');
  });

  test('the checklist: tasks before joining block activation; a task reassigned shows in My tasks', async ({ browser }) => {
    await tab(hr, 'Checklist');
    await expect(hr.locator('.task-group')).toHaveCount(4); // HR, IT, Manager, Joiner
    await expect(hr.locator('.blockers')).toContainText('Task: Prepare laptop and accounts');
    await shot(hr, '52-checklist', true);

    // IT has nobody yet; HR gives the laptop task to the Org Admin, who finds it under My tasks.
    const laptop = hr.locator('.task-row[data-task="Prepare laptop and accounts"]');
    await expect(laptop).toContainText('nobody assigned');
    await laptop.getByRole('button', { name: 'Reassign' }).click();
    await laptop.locator('select').selectOption({ label: OWNER });
    await laptop.getByRole('button', { name: 'Reassign' }).click();
    await expect(laptop).toContainText(OWNER);

    const owner = await signIn(browser, OWNER);
    await nav(owner, 'My tasks');
    const group = owner.locator('.task-group', { hasText: 'Meera Iyer, joining' });
    const task = group.locator('.task-row[data-task="Prepare laptop and accounts"]');
    await expect(task).toBeVisible();
    await shot(owner, '53-my-tasks');
    await task.getByRole('button', { name: 'Mark done' }).click();
    await expect(task).toHaveCount(0);
    // The Org Admin is also Meera's manager, so the manager's tasks are here too.
    await expect(owner.locator('.task-row[data-task="Plan the first week"]')).toBeVisible();
    await owner.context().close();
    await hr.reload();
    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await tab(hr, 'Checklist');
    await expect(laptop).toContainText('Done');

    // HR skips one with a reason and ticks the rest that fall before the joining date.
    const email = hr.locator('.task-row[data-task="Create the official email"]');
    await email.getByRole('button', { name: 'Skip' }).click();
    await email.locator('input').fill('Uses the shared team mailbox');
    await email.getByRole('button', { name: 'Skip task' }).click();
    await expect(email).toContainText('Skipped');
    for (const title of ['Send the welcome note and invitation', 'Issue the appointment letter', 'Plan the first week']) {
      const row = hr.locator(`.task-row[data-task="${title}"]`);
      await row.getByRole('button', { name: 'Mark done' }).click();
      await expect(row).toContainText('Done');
    }
  });

  test('HR verifies every section and the joiner becomes an employee', async () => {
    await hr.reload();
    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    const titles = ['Employment', 'Personal details', 'Contact', 'Addresses', 'Emergency contact', 'Family and nominees', 'Bank account', 'PAN, Aadhaar and PF'];
    for (const title of titles) {
      const card = sectionCard(hr, title);
      await card.locator('.section-toggle').click();
      if (title === 'Personal details') await shot(hr, '54-verify-section');
      await card.getByRole('button', { name: 'Verify' }).click();
      await expect(card.locator('.section-toggle')).toContainText('Checked');
      await card.locator('.section-toggle').click();
    }
    // Joining today with nothing left, so the joiner became an employee at once.
    await hr.reload();
    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Joined', exact: true }).click();
    await expect(hr.locator('tbody tr', { hasText: 'Meera Iyer' })).toContainText('Joined');
    await shot(hr, '55-joined');
    await tab(hr, 'Employees');
    await expect(hr.locator('tbody tr', { hasText: 'Meera Iyer' })).toContainText('E050');

    await joiner.reload();
    await expect(joiner.locator('button.nav-item', { hasText: 'My attendance' })).toBeVisible();
    await expect(joiner.locator('button.nav-item', { hasText: 'My onboarding' })).toHaveCount(0);
    await shot(joiner, '56-joiner-now-employee');

    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await expect(hr.locator('.panels')).toContainText('•••• 321M');
  });

  test('a withdrawn joiner leaves the list', async () => {
    await hr.getByRole('button', { name: '← All employees' }).click();
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Add joiner' }).click();
    await hr.fill('#nj-name', 'Rohan Das');
    await hr.fill('#nj-joining', isoDay(20));
    await hr.fill('#nj-designation', 'Designer');
    await hr.getByRole('button', { name: 'Save, invite later' }).click();
    await expect(hr.getByRole('heading', { name: /Rohan Das/ })).toBeVisible();
    await expect(hr.getByRole('button', { name: 'Invite' })).toHaveCount(0); // no personal email to send it to

    await hr.getByRole('button', { name: 'Withdraw' }).click();
    await hr.fill('#withdraw-reason', 'Took another offer');
    await shot(hr, '57-withdraw');
    await hr.locator('form').getByRole('button', { name: 'Withdraw' }).click();
    await expect(hr.locator('.section-head h2')).toContainText('withdrawn');
    await hr.getByRole('button', { name: '← All joiners' }).click();
    await expect(hr.getByText('Nobody is joining')).toBeVisible();

    await nav(hr, 'Audit log');
    for (const action of ['joiner.added', 'joiner.invited', 'joiner.section_returned', 'joiner.section_verified', 'joiner.activated', 'joiner.withdrawn']) {
      await expect(hr.locator('code', { hasText: action }).first()).toBeVisible();
    }
  });
});
