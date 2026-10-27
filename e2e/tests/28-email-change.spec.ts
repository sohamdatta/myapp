import { test, expect } from '@playwright/test';
import { SUPER_ADMIN, newPage, acceptInvite, signIn, expectNoConsoleErrors, PASSWORD } from './helpers';

// Changing the sign-in address, end to end: ask from Security, confirm from the link sent to the new address, find the old
// address and the old session gone, then undo from the link sent to the old address. Needs the test mail server (E2E_MAIL_URL).

const MAIL = process.env.E2E_MAIL_URL;
const STAMP = Date.now().toString(36);
const OWNER = `owner@umbrella-${STAMP}.test`;
const NEW_ADDRESS = `moved-${STAMP}@elsewhere.test`;
const ORG = `Umbrella ${STAMP} Ltd`;

async function mailTo(email: string, subject: string): Promise<string> {
  for (let i = 0; i < 40; i++) {
    const r = await fetch(`${MAIL}/messages?to=${encodeURIComponent(email)}`);
    const found = ((await r.json()) as { subject: string; text: string }[]).filter((m) => m.subject.includes(subject));
    if (found.length) return found[found.length - 1].text;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`no "${subject}" mail for ${email}`);
}

test.describe.serial('email change', () => {
  test.skip(!MAIL, 'needs the test mail server (E2E_MAIL_URL)');

  test.afterAll(() => expectNoConsoleErrors());

  test('an owner changes the address, the old session ends, and the old address can undo it', async ({ browser }) => {
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
    const invite = await admin.locator('.copy-row input').inputValue();
    await admin.context().close();
    await (await acceptInvite(browser, invite)).context().close();

    await fetch(`${MAIL}/messages`, { method: 'DELETE' });
    const old = await signIn(browser, OWNER);
    await old.getByRole('button', { name: 'Security', exact: true }).click();
    await old.fill('#ce-address', NEW_ADDRESS);
    await old.fill('#ce-password', 'not-my-password-at-all');
    await old.getByRole('button', { name: 'Send confirmation link' }).click();
    await expect(old.getByText('The password is not correct')).toBeVisible();
    await old.fill('#ce-password', PASSWORD);
    await old.getByRole('button', { name: 'Send confirmation link' }).click();
    await expect(old.getByRole('status')).toContainText('a confirmation link is on its way');

    const confirmBody = await mailTo(NEW_ADDRESS, 'Confirm your new sign-in address');
    const token = confirmBody.match(/\?confirm-email=([A-Za-z0-9_-]+)/)![1];
    const page = await newPage(browser);
    await page.goto(`/?confirm-email=${token}`);
    await page.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('Your sign-in address is changed');
    await page.context().close();

    await old.reload();
    await expect(old.locator('#email')).toBeVisible();
    await old.context().close();
    await (await signIn(browser, NEW_ADDRESS)).context().close();

    const undoBody = await mailTo(OWNER, 'sign-in address was changed');
    const undo = undoBody.match(/\?undo-email=([A-Za-z0-9_-]+)/)![1];
    const back = await newPage(browser);
    await back.goto(`/?undo-email=${undo}`);
    await back.getByRole('button', { name: 'Undo the change' }).click();
    await expect(back.getByRole('status')).toContainText('The earlier address is back');
    await back.context().close();
    await (await signIn(browser, OWNER)).context().close();
  });
});
