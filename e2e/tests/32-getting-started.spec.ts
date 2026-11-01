import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, signIn, acceptInvite, api, expectRefused, expectNoConsoleErrors, expectNoSidewaysScroll, shot } from './helpers';

// A new customer's first minutes. Kaveri Mills is created empty: its Org Admin lands on Getting started,
// picks a starting setup, and is left with locations, shifts, leave types and holidays to change. Nobody
// but the Org Admin sees the page, and a second try is refused.

const ORG = 'Kaveri Mills';
const OWNER = 'owner@kaveri.test';
const HR = 'hr@kaveri.test';
const EMPLOYEE = 'emp@kaveri.test';

async function invite(owner: Page, email: string, roles: string[]): Promise<string> {
  await nav(owner, 'User management');
  await tab(owner, 'Invitations');
  await owner.fill('#invite-email', email);
  for (const role of roles) await owner.check(`.role-picker label:has-text('${role}') input`);
  await owner.click('text=Create invitation');
  await expect(owner.getByText(`Invitation for ${email}.`)).toBeVisible();
  return owner.locator('.copy-row input').inputValue();
}

test.describe.serial('getting started', () => {
  let admin: Page;
  let owner: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [admin, owner]) await p?.context().close();
  });

  test('a new Org Admin lands on Getting started, with nothing set up yet', async ({ browser }) => {
    admin = await newPage(browser);
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

    owner = await acceptInvite(browser, link);
    await expect(owner.locator('.page-title')).toHaveText('Getting started');
    await expect(owner.getByRole('progressbar', { name: 'Setup progress' })).toHaveAttribute('aria-valuenow', '1');
    await expect(owner.locator('.setup-step-current')).toContainText('Starting setup');
    await expect(owner.getByRole('radio')).toHaveCount(3);
    // Payroll's readiness is there from the start: the rules still awaiting sign-off, and no people to check yet.
    await expect(owner.locator('[data-check=draft]')).toContainText('drafts');
    await expect(owner.locator('.run-checks')).toContainText('Import your people first');
    await shot(owner, 'start-01-empty', true);

    const phone = await signIn(browser, OWNER, undefined, 390);
    await expect(phone.locator('.page-title')).toHaveText('Getting started');
    await expectNoSidewaysScroll(phone);
    await phone.context().close();
  });

  test('choosing a setup asks first, then fills the Organization', async () => {
    // Without a location name and a state, the person is told what is missing and nothing is added.
    await owner.getByRole('button', { name: 'Set up with this' }).click();
    await expect(owner.locator('.starter .error')).toContainText('name and choose its state');
    expect((await api(owner, 'GET', '/org/attendance/locations')).body).toEqual([]);

    await owner.getByRole('radio', { name: /Factory with shifts/ }).check();
    await owner.fill('#starter-place-name', 'Coimbatore unit 1');
    await owner.selectOption('#starter-place-state', 'TN');
    await owner.fill('#leave-days-casual', '10');
    await owner.selectOption('select[aria-label="Casual leave, what happens to unused days"]', 'UP_TO_15');
    await shot(owner, 'start-02-filled', true);

    await owner.getByRole('button', { name: 'Set up with this' }).click();
    const dialog = owner.getByRole('dialog');
    await expect(dialog).toContainText('Set up with “Factory with shifts”?');
    await dialog.getByRole('button', { name: 'Not yet' }).click();
    expect((await api(owner, 'GET', '/org/attendance/shifts')).body).toEqual([]);

    await owner.getByRole('button', { name: 'Set up with this' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Set up', exact: true }).click();
    await expect(owner.locator('.setup-step').filter({ has: owner.getByRole('heading', { name: 'Starting setup' }) })).toContainText('1 work location, 3 shifts and 3 leave types are set up');
    await expect(owner.getByRole('radio')).toHaveCount(0);
    await expect(owner.getByRole('progressbar', { name: 'Setup progress' })).toHaveAttribute('aria-valuenow', '3');
    await shot(owner, 'start-03-done', true);

    const shifts = (await api(owner, 'GET', '/org/attendance/shifts')).body.map((s: any) => s.name).sort();
    expect(shifts).toEqual(['Early', 'General', 'Late']);
    const casual = (await api(owner, 'GET', '/org/leave/types')).body.find((t: any) => t.name === 'Casual leave');
    expect(Number(casual.yearlyQuota)).toBe(10);
    expect(Number(casual.carryForwardCap)).toBe(15);
    expect((await api(owner, 'GET', '/org/attendance/locations')).body[0]).toMatchObject({ name: 'Coimbatore unit 1', stateCode: 'TN' });
  });

  test('payroll readiness names the state without a professional tax rule, and the steps point on', async () => {
    const states = owner.locator('[data-check=states]');
    await expect(states).toContainText('Tamil Nadu');
    await expect(states).toContainText('Blocking');
    await expect(states).toContainText('We have to fix this one');

    await owner.getByRole('link', { name: 'Open People' }).click();
    await expect(owner.locator('.page-title')).toHaveText('People');
    await nav(owner, 'Getting started');
  });

  test('a second try is refused, and the Org Admin no longer starts here', async () => {
    await expectRefused(owner, 'POST', '/org/setup/starter', [409], { starter: 'office', locationName: 'Head office', stateCode: 'KA' }, 'setup.already_started');
    expect((await api(owner, 'GET', '/org/attendance/shifts')).body).toHaveLength(3);
    await owner.goto('/');
    await expect(owner.locator('.topbar')).toBeVisible();
    await expect(owner.locator('.page-title')).not.toHaveText('Getting started');
  });

  test('only the Org Admin sees Getting started, and the server refuses the rest', async ({ browser }) => {
    const hrLink = await invite(owner, HR, ['HR Admin']);
    const empLink = await invite(owner, EMPLOYEE, ['Employee']);
    for (const link of [hrLink, empLink]) {
      const person = await acceptInvite(browser, link);
      expect(await person.locator('a.nav-item').allInnerTexts()).not.toContain('Getting started');
      await expectRefused(person, 'GET', '/org/setup', [403], undefined, 'permission.denied');
      await expectRefused(person, 'POST', '/org/setup/starter', [403], { starter: 'office', locationName: 'Mine', stateCode: 'KA' }, 'permission.denied');
      await person.goto('/getting-started');
      await expect(person.locator('.page-title')).not.toHaveText('Getting started');
      await person.context().close();
    }
    expect((await api(owner, 'GET', '/org/attendance/locations')).body).toHaveLength(1);
  });
});
