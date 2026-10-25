import { test, expect, type Page } from '@playwright/test';
import { HR, OWNER, expectNoConsoleErrors, nav, shot, signIn } from './helpers';

// Branding: the app shows the Nexvance brand until an Organization sets its own logo, which only
// the Org Admin can do. The logo then shows for everyone in the Organization; removing it brings
// the Nexvance logo back.

// A small wide PNG: a square and a bar, in the app's green.
const LOGO = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAKAAAAAwCAYAAACWqXFuAAAAiElEQVR42u3asQkAIAxFwUzgkindv9YlAgnkDn4v+LAyAgAAAABY79x8nXMDAhQgAkSAAkSACFCACBABChABIkABIsAp57Xih0OAJkABClCAJkABClCAJkABClCAJkABClCAJkABClCAJkB8RhAgAkSAAkSACFCACBABChABIkABAgAAAADs8gHtZfLqPrj5egAAAABJRU5ErkJggg==', 'base64');

test.describe.serial('branding', () => {
  let owner: Page;
  let hr: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [owner, hr]) await p?.context().close();
  });

  test('the sign-in page and the app show the Nexvance brand by default', async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto('/');
    await expect(page).toHaveTitle('Nexvance HRMS');
    await expect(page.getByRole('img', { name: 'Nexvance Solutions' })).toBeVisible();
    await expect(page.getByText("Advancing What's Next")).toBeVisible();
    await shot(page, 'brand-01-sign-in');
    await context.close();

    owner = await signIn(browser, OWNER);
    await expect(owner.getByRole('navigation', { name: 'Main' }).getByRole('img', { name: 'Nexvance Solutions' })).toBeVisible();
    hr = await signIn(browser, HR);
    await expect(hr.getByRole('navigation', { name: 'Main' }).getByRole('button', { name: 'Organization settings' })).toHaveCount(0);
  });

  test('the Org Admin uploads the Organization logo, and everyone sees it', async () => {
    await nav(owner, 'Organization settings');
    const branding = owner.getByTestId('branding');
    await expect(branding).toContainText('the Nexvance logo shows');
    await owner.setInputFiles('#logo-file', { name: 'globex.png', mimeType: 'image/png', buffer: LOGO });
    await expect(branding).toContainText('Logo saved.');
    await expect(branding).toContainText('160 × 48 pixels');
    const sidebar = owner.getByRole('navigation', { name: 'Main' });
    await expect(sidebar.getByRole('img', { name: 'Globex Ltd logo' })).toBeVisible();
    await expect(sidebar.getByText('Powered by Nexvance')).toBeVisible();
    await shot(owner, 'brand-02-org-logo', true);

    await hr.reload();
    await expect(hr.getByRole('navigation', { name: 'Main' }).getByRole('img', { name: 'Globex Ltd logo' })).toBeVisible();
  });

  test('a file that is not an image is refused, and removing the logo brings Nexvance back', async () => {
    const branding = owner.getByTestId('branding');
    await owner.setInputFiles('#logo-file', { name: 'logo.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>') });
    await expect(branding.getByRole('alert')).toContainText('PNG or JPEG');
    await branding.getByRole('button', { name: 'Remove it' }).click();
    await expect(branding).toContainText('the Nexvance logo shows again');
    await expect(owner.getByRole('navigation', { name: 'Main' }).getByRole('img', { name: 'Nexvance Solutions' })).toBeVisible();
  });
});
