import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, acceptInvite, forgetConsoleErrors, expectNoConsoleErrors } from './helpers';

// A page that crashes while drawing stays inside its own region: the menu still works, the person is told,
// and the crash is reported to the backend without who it happened to. The crash is made by answering one
// request with data the page does not expect. In an Organization of its own: --project=boundary.

const RUN = Date.now().toString(36);
const ORG = `Umbrella ${RUN}`;
const OWNER = `owner@umbrella-${RUN}.test`;

test.describe.serial('error boundary', () => {
  let owner: Page;

  test.afterAll(async () => {
    // React logs the error it caught; that is expected here, and is not a console error to fail on.
    forgetConsoleErrors(/users\.map|is not a function|is not iterable|Cannot read|The above error occurred|Error boundary/i);
    expectNoConsoleErrors();
    await owner?.context().close();
  });

  test('an Organization with an owner', async ({ browser }) => {
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
  });

  test('a page that crashes is contained, offers a way on, and is reported without personal data', async () => {
    await owner.route('**/api/org/users', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"unexpected":true}' }));
    const reported = owner.waitForRequest((r) => r.url().endsWith('/api/client-errors') && r.method() === 'POST');
    await nav(owner, 'User management');

    await expect(owner.getByRole('alert')).toContainText('Something went wrong on this page');
    // the menu and the top bar are outside the page's region, so they still work
    await expect(owner.locator('.topbar')).toBeVisible();
    const report = (await reported).postDataJSON();
    expect(report.page).toBe('users');
    expect(report.region).toBe('page');
    expect(String(report.message).length).toBeGreaterThan(0);
    expect(JSON.stringify(report)).not.toContain(OWNER);

    await owner.unroute('**/api/org/users');
    await owner.getByRole('button', { name: 'Try again' }).click();
    await expect(owner.getByRole('alert')).toHaveCount(0);
    await expect(owner.getByText(OWNER).first()).toBeVisible();

    // and another page opens normally after a crash
    await nav(owner, 'Settings');
    await expect(owner.getByRole('alert')).toHaveCount(0);
  });

  test('the same crash twice in a minute is reported once', async () => {
    let reports = 0;
    owner.on('request', (r) => {
      if (r.url().endsWith('/api/client-errors') && r.method() === 'POST') reports++;
    });
    await owner.route('**/api/org/users', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"unexpected":true}' }));
    await nav(owner, 'User management');
    await expect(owner.getByRole('alert')).toContainText('Something went wrong on this page');
    await owner.getByRole('button', { name: 'Try again' }).click();
    await expect(owner.getByRole('alert')).toContainText('Something went wrong on this page');
    expect(reports).toBe(0);   // the first crash, a minute ago in this same page, was already reported
    await owner.unroute('**/api/org/users');
  });
});
