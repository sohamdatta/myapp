import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, acceptInvite, api, expectRefused, expectNoConsoleErrors, expectNoSidewaysScroll, isoDay, taxYear, withinTaxYear, shot } from './helpers';

// Income tax declarations (Form 124) and proofs, in an Organization of its own so the suite can run
// by itself: Vandelay Ltd's owner opens the window, Meera declares rent and investments on the old
// regime and submits, sends proofs, and the owner accepts one and returns another. Payroll counting
// declared and verified amounts is covered by TaxDeclarationsTest against the database.

const ORG = 'Vandelay Ltd';
const OWNER = 'owner@vandelay.test';
const MEERA = 'meera@vandelay.test';
const PDF = Buffer.from('%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n');

async function claim(page: Page, label: string) {
  const row = page.locator(`[data-claim="${label}"]`);
  await expect(row).toBeVisible();
  return row;
}

test.describe.serial('tax declarations', () => {
  let owner: Page;
  let meera: Page;
  let meeraId: string;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [owner, meera]) await p?.context().close();
  });

  test('an Organization with Meera employed on the old regime', async ({ browser }) => {
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
    owner = await acceptInvite(browser, await admin.locator('.copy-row input').inputValue());
    await admin.context().close();

    const invited = await api(owner, 'POST', '/org/invitations', { email: MEERA, roles: ['employee'] });
    expect(invited.status).toBe(200);
    meera = await acceptInvite(browser, `/?invite=${encodeURIComponent(invited.body.token)}`);
    const membershipId = (await api(meera, 'GET', '/auth/me')).body.membershipId;
    const added = await api(owner, 'POST', '/org/people/employees', {
      legalName: 'Meera Iyer', dateOfBirth: '1994-06-02', gender: 'female', mobile: '9123456780', employmentType: 'permanent',
      joiningDate: isoDay(-200), designation: 'Analyst', membershipId,
    });
    expect(added.status, JSON.stringify(added.body)).toBe(200);
    meeraId = added.body.id;
    const setup = (await api(owner, 'GET', '/org/payroll/setup')).body;
    expect((await api(owner, 'POST', `/org/people/employees/${meeraId}/pay`, {
      validFrom: isoDay(-200), structureId: setup.structures[0].id, annualAmount: 1500000, reason: 'Joining salary',
    })).status).toBe(200);
    expect((await api(owner, 'PUT', `/org/people/employees/${meeraId}/pay/profile`, { taxRegime: 'old' })).status).toBe(200);
  });

  test('before the window opens, Meera sees why she cannot declare yet', async () => {
    await meera.reload();
    await nav(meera, 'My pay');
    await tab(meera, 'Tax declaration');
    await expect(meera.getByText('Declarations are not open yet')).toBeVisible();
    await expect(meera.getByRole('button', { name: 'Add' })).toHaveCount(0);
    await expectRefused(meera, 'POST', '/org/me/tax/items', [409], { head: 'nps', amount: 50000, details: {} }, 'tax.declarations_closed');
    // Nor may she open the window, or see anyone else's.
    await expectRefused(meera, 'PUT', '/org/payroll/tax/window', [403], { taxYear: taxYear().label, declareFrom: isoDay(0), declareUntil: isoDay(0) });
    await expectRefused(meera, 'GET', '/org/payroll/tax', [403]);
  });

  test('the owner opens declarations; Meera is told', async () => {
    await owner.reload();
    await nav(owner, 'Tax declarations');
    await expect(owner.getByRole('heading', { name: `Tax declarations ${taxYear().label}` })).toBeVisible();
    await owner.getByRole('button', { name: 'Open declarations…' }).click();
    await owner.fill('#tw-declare-from', isoDay(-1));
    await owner.fill('#tw-declare-until', withinTaxYear(20));
    await shot(owner, 'tax-01-open-window', true);
    // Wait for the save itself: the form's live preview already reads "Declarations open".
    await Promise.all([
      owner.waitForResponse((r) => r.url().includes('/org/payroll/tax/window') && r.request().method() === 'PUT' && r.ok()),
      owner.getByRole('button', { name: 'Save', exact: true }).click(),
    ]);
    await expect(owner.getByTestId('tax-window')).toContainText('Declarations open');
    await expect(owner.locator('tr[data-employee="Meera Iyer"]')).toContainText('Not started');

    const bell = await api(meera, 'GET', '/org/notifications');
    expect(JSON.stringify(bell.body)).toContain(`Tax declarations for ${taxYear().label} are open`);
  });

  test('Meera declares rent and an investment, and submits', async () => {
    await meera.reload();
    await nav(meera, 'My pay');
    await tab(meera, 'Tax declaration');
    await expect(meera.getByText('Declare until')).toBeVisible();

    // Rent above ₹1 lakh a year needs the landlord's PAN.
    const rent = meera.locator('section[data-head="rent"]');
    await rent.getByRole('button', { name: 'Add' }).click();
    await meera.fill('#claim-rent-amount', '25000');
    await meera.selectOption('#claim-rent-city', 'Bengaluru');
    await meera.fill('#claim-rent-landlordName', 'R. Shetty');
    await meera.fill('#claim-rent-landlordAddress', '14 Indiranagar 2nd Stage, Bengaluru');
    await rent.getByRole('button', { name: 'Add', exact: true }).last().click();
    await expect(meera.locator('.error').first()).toContainText('needs the landlord');
    await meera.fill('#claim-rent-landlordPan', 'abcps1234k');
    await shot(meera, 'tax-02-rent', true);
    await rent.getByRole('button', { name: 'Add', exact: true }).last().click();
    const rentRow = await claim(meera, 'Rent (HRA)');
    await expect(rentRow).toContainText('₹25,000');
    await expect(rentRow).toContainText('12 months: ₹3,00,000 in the year');
    await expect(rentRow).toContainText('PAN ABCPS1234K');

    const group = meera.locator('section[data-head="sec80c"]');
    await group.getByRole('button', { name: 'Add' }).click();
    await meera.selectOption('#claim-sec80c-kind', 'elss');
    await meera.fill('#claim-sec80c-amount', '90000');
    await group.getByRole('button', { name: 'Add', exact: true }).last().click();
    await expect(await claim(meera, 'Tax-saving mutual fund (ELSS)')).toContainText('₹90,000');

    const nps = meera.locator('section[data-head="nps"]');
    await nps.getByRole('button', { name: 'Add' }).click();
    await meera.fill('#claim-nps-amount', '50000');
    await nps.getByRole('button', { name: 'Add', exact: true }).last().click();
    await expect(await claim(meera, 'NPS, your own contribution')).toContainText('₹50,000');

    // Submitting certifies the declaration; payroll counts it from then on.
    await meera.getByRole('button', { name: 'Submit declaration' }).click();
    await expect(meera.getByRole('dialog')).toContainText('You declare that what you have entered is true');
    await meera.getByRole('dialog').getByRole('button', { name: 'Submit declaration' }).click();
    await expect(meera.getByRole('heading', { name: new RegExp(`Tax declaration ${taxYear().label}`) })).toContainText('Submitted');
    await shot(meera, 'tax-03-declared', true);
    await expect(meera.getByRole('button', { name: 'Upload proof' })).toHaveCount(0);
  });

  test('a phone shows the declaration without scrolling sideways', async ({ browser }) => {
    const phone = await newPage(browser, 390);
    await phone.goto('/');
    await phone.fill('#email', MEERA);
    await phone.fill('#password', 'correct-horse-battery');
    await phone.click('button.primary');
    await nav(phone, 'My pay');
    await tab(phone, 'Tax declaration');
    await expect(phone.locator('[data-claim="Rent (HRA)"]')).toBeVisible();
    await expectNoSidewaysScroll(phone);
    await shot(phone, 'tax-04-phone', true);
    await phone.context().close();
  });

  test('proofs: Meera uploads, the owner accepts one at less and returns another', async () => {
    // The owner opens proofs too.
    await nav(owner, 'Tax declarations');
    await owner.getByRole('button', { name: 'Change' }).click();
    await owner.fill('#tw-proofs-from', isoDay(0));
    await owner.fill('#tw-proofs-until', withinTaxYear(20));
    // Wait for the save itself: the form's live preview already reads "Proofs open".
    await Promise.all([
      owner.waitForResponse((r) => r.url().includes('/org/payroll/tax/window') && r.request().method() === 'PUT' && r.ok()),
      owner.getByRole('button', { name: 'Save', exact: true }).click(),
    ]);
    await expect(owner.getByTestId('tax-window')).toContainText('Proofs open');

    await meera.reload();
    await nav(meera, 'My pay');
    await tab(meera, 'Tax declaration');
    for (const [label, file] of [['Tax-saving mutual fund (ELSS)', 'ELSS statement.pdf'], ['Rent (HRA)', 'Rent receipts.pdf']]) {
      const row = await claim(meera, label);
      await row.locator('input[type=file]').setInputFiles({ name: file, mimeType: 'application/pdf', buffer: PDF });
      await expect(row).toContainText(file);
      await expect(row).toContainText('Proof sent, waiting');
    }
    // Only PDF, JPEG and PNG files.
    const nps = await claim(meera, 'NPS, your own contribution');
    await nps.locator('input[type=file]').setInputFiles({ name: 'statement.txt', mimeType: 'text/plain', buffer: Buffer.from('hello') });
    await expect(nps.locator('.error')).toContainText('Only PDF, JPEG and PNG');
    await shot(meera, 'tax-05-proofs-sent', true);

    // The owner sees Meera first, with two to check.
    await owner.reload();
    await nav(owner, 'Tax declarations');
    const row = owner.locator('tr[data-employee="Meera Iyer"]');
    await expect(row).toContainText('Proofs to check');
    await expect(owner.getByTestId('tax-count-to_check')).toHaveText('1');
    await shot(owner, 'tax-06-overview', true);
    await row.getByRole('button', { name: 'Meera Iyer' }).click();

    const elss = await claim(owner, 'Tax-saving mutual fund (ELSS)');
    await elss.getByRole('button', { name: 'Open' }).click();
    await expect(owner.locator('.viewer')).toBeVisible();
    await owner.locator('.viewer').getByRole('button').last().click();
    await elss.getByRole('button', { name: 'Accept…' }).click();
    await elss.locator('input').fill('85000');
    await shot(owner, 'tax-07-accept', true);
    await elss.getByRole('button', { name: 'Accept', exact: true }).click();
    await expect(elss).toContainText('Accepted at ₹85,000');

    const rentRow = await claim(owner, 'Rent (HRA)');
    await rentRow.getByRole('button', { name: 'Return…' }).click();
    await rentRow.locator('input').fill('Receipts for May and June are missing');
    await rentRow.getByRole('button', { name: 'Return', exact: true }).click();
    await expect(rentRow).toContainText('Returned');
    await expect(rentRow).toContainText('Receipts for May and June are missing');
    await shot(owner, 'tax-08-reviewed', true);

    // Meera sees both decisions; an accepted proof is closed to her.
    await meera.reload();
    await nav(meera, 'My pay');
    await tab(meera, 'Tax declaration');
    await expect(await claim(meera, 'Tax-saving mutual fund (ELSS)')).toContainText('Proof accepted');
    await expect((await claim(meera, 'Tax-saving mutual fund (ELSS)')).getByRole('button', { name: /Add another file|Upload proof/ })).toHaveCount(0);
    const returned = await claim(meera, 'Rent (HRA)');
    await expect(returned).toContainText('Proof returned');
    await expect(returned).toContainText('Receipts for May and June are missing');
    await expect(returned.getByRole('button', { name: 'Add another file' })).toBeVisible();
    await shot(meera, 'tax-09-decisions', true);
  });
});
