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
    await expect(page.getByText('People operations, made simple for India.')).toBeVisible();
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

  test('the Org Admin sets how the tax certificate looks; it is kept as a version, and text the certificate cannot print is refused', async () => {
    await nav(owner, 'Organization settings');
    const panel = owner.getByTestId('certificate-layout');
    await expect(panel).toContainText('Not set yet');
    await expect(owner.locator('#cl-logo')).toBeDisabled();   // the logo was removed above, so there is none to show
    await owner.fill('#cl-header', 'Strictly confidential');
    await owner.fill('#cl-name', 'R. Menon');
    await owner.fill('#cl-designation', 'Head of Human Resources');

    // A sample opens in a new tab with the typed layout, and nothing is saved by looking.
    const [sample] = await Promise.all([owner.context().waitForEvent('page'), panel.getByRole('button', { name: 'Preview a sample' }).click()]);
    await sample.close();
    await expect(owner.getByTestId('layout-version')).toContainText('Not set yet');

    await panel.getByRole('button', { name: 'Save the layout' }).click();
    await expect(panel).toContainText('Saved as version 1');
    await expect(owner.getByTestId('layout-version')).toContainText('Version 1');
    await expect(panel.getByRole('button', { name: 'Save the layout' })).toBeDisabled();   // nothing left to save

    await owner.fill('#cl-header', 'प्रमाणपत्र');   // Devanagari: the certificate's standard font has no glyphs for it
    await panel.getByRole('button', { name: 'Save the layout' }).click();
    await expect(panel.getByRole('alert')).toContainText('cannot print');
    await expect(owner.getByTestId('layout-version')).toContainText('Version 1');
    await shot(owner, 'brand-03-certificate-layout', true);
  });
});
