import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, acceptInvite, api, expectRefused, expectNoConsoleErrors, isoDay, shot } from './helpers';

// What a customer pays for. Core HR is always included; Attendance, Leave and Payroll are switched
// on per Organization by the platform, optionally for a trial. Hooli Ltd starts with Payroll only,
// tries Attendance, is given Leave on its own, and has Payroll switched off and on again.

const OWNER = 'owner@hooli.test';
const ORG = 'Hooli Ltd';

async function menu(page: Page): Promise<string[]> {
  await expect(page.locator('a.nav-item').first()).toBeVisible();
  return page.locator('a.nav-item').allInnerTexts();
}

async function modulesOf(admin: Page): Promise<Page> {
  await admin.reload();
  const row = admin.locator('tr', { hasText: ORG }).first();
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: 'Modules', exact: true }).click();
  await expect(admin.locator('.module-editor')).toBeVisible();
  return admin;
}

test.describe.serial('modules', () => {
  let admin: Page;
  let owner: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [admin, owner]) await p?.context().close();
  });

  test('the Super Admin creates an Organization with Payroll only', async ({ browser }) => {
    admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email);
    await admin.fill('#password', SUPER_ADMIN.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();

    await admin.fill('#org-name', ORG);
    await admin.fill('#org-admin', OWNER);
    await expect(admin.locator('#org-mod-attendance')).toBeChecked();
    await expect(admin.locator('#org-mod-leave')).toBeChecked();
    await expect(admin.locator('#org-mod-payroll')).toBeChecked();
    await admin.uncheck('#org-mod-attendance');
    await admin.uncheck('#org-mod-leave');
    await shot(admin, 'mods-01-create-payroll-only', true);
    await admin.click('text=Create and invite');
    await expect(admin.getByText(`Invitation for ${OWNER}.`)).toBeVisible();
    const link = await admin.locator('.copy-row input').inputValue();

    const row = admin.locator('tr', { hasText: ORG });
    await expect(row).toContainText('Core HR');
    await expect(row).toContainText('Payroll');
    await expect(row.locator('.module-badges')).not.toContainText('Attendance');
    await expect(row.locator('.module-badges')).not.toContainText('Leave');
    await shot(admin, 'mods-02-console-list', true);

    owner = await acceptInvite(browser, link);
  });

  test('a Payroll-only Organization: no attendance or leave anywhere, the work calendar kept', async () => {
    const items = await menu(owner);
    expect(items).toContain('Payroll');
    expect(items).toContain('Work locations');
    for (const missing of ['Attendance', 'Leave', 'My attendance', 'My leave']) expect(items).not.toContain(missing);
    await shot(owner, 'mods-03-payroll-only-menu', true);

    // The server refuses Attendance outright, whatever the screens show.
    await expectRefused(owner, 'GET', '/org/attendance/me/today', [403], undefined, 'module.off');
    await expectRefused(owner, 'GET', '/org/attendance/days', [403], undefined, 'module.off');
    await expectRefused(owner, 'GET', '/org/leave/me/balances', [403], undefined, 'module.off');
    await expectRefused(owner, 'PUT', '/org/leave/year', [403], { startMonth: 4 }, 'module.off');
    const noLeave = await api(owner, 'GET', '/org/leave/requests');
    expect(noLeave.body.message).toContain(`Leave is not part of ${ORG}`);
    await expectRefused(owner, 'GET', '/org/attendance/devices', [403], undefined, 'module.off');
    const refused = await api(owner, 'POST', '/org/attendance/me/punch', {});
    expect(refused.body.message).toContain(`Attendance is not part of ${ORG}`);
    // The shared work calendar, and leave types for exit settings, stay.
    expect((await api(owner, 'GET', '/org/attendance/locations')).status).toBe(200);
    expect((await api(owner, 'GET', '/org/attendance/assignments')).status).toBe(200);
    expect((await api(owner, 'GET', '/org/leave/types')).status).toBe(200);
    expect((await api(owner, 'GET', '/org/payroll/runs')).status).toBe(200);

    await nav(owner, 'Work locations');
    await tab(owner, 'Locations and shifts');
    await expect(owner.locator('#loc-name')).toBeVisible();
    await expect(owner.locator('#loc-check')).toHaveCount(0);   // no punching here
    await owner.fill('#loc-name', 'Pune office');
    await owner.fill('#loc-state', 'MH');
    await shot(owner, 'mods-04-work-locations', true);
    await owner.getByRole('button', { name: 'Save location' }).click();
    await expect(owner.locator('td', { hasText: 'Pune office' })).toBeVisible();
    await expect(owner.getByRole('button', { name: 'Attendance mode' })).toHaveCount(0);

    await nav(owner, 'User management');
    await tab(owner, 'Subscription');
    await expect(owner.locator('tr', { hasText: 'Attendance' })).toContainText('not included');
    await expect(owner.locator('tr', { hasText: 'Balances, leave requests' })).toContainText('not included');
    await expect(owner.locator('tr', { hasText: 'Payroll' })).toContainText('on');
    await shot(owner, 'mods-05-subscription', true);
  });

  test('the Super Admin starts an Attendance trial; it appears at once', async () => {
    await modulesOf(admin);
    await admin.fill(`[id$="-attendance"][type=date]`, isoDay(14));
    await shot(admin, 'mods-06-start-trial', true);
    await admin.getByRole('button', { name: 'Switch on Attendance' }).click();
    const row = admin.locator('tr', { hasText: ORG }).first();
    await expect(row).toContainText('Attendance trial until');

    // A trial ending in the past, or a year and more away, is refused.
    const org = (await api(admin, 'GET', '/platform/organizations')).body.find((o: any) => o.name === ORG);
    await expectRefused(admin, 'POST', `/platform/organizations/${org.id}/modules`, [400], { module: 'attendance', on: true, trialEndsOn: isoDay(-1) });
    await expectRefused(admin, 'POST', `/platform/organizations/${org.id}/modules`, [400], { module: 'attendance', on: true, trialEndsOn: isoDay(800) });
    await expectRefused(admin, 'POST', `/platform/organizations/${org.id}/modules`, [400], { module: 'chess', on: true });
    // Only the platform changes what an Organization has.
    await expectRefused(owner, 'POST', `/platform/organizations/${org.id}/modules`, [401, 403], { module: 'attendance', on: true });

    await owner.reload();
    const items = await menu(owner);
    expect(items).toContain('Attendance');
    expect(items).toContain('My attendance');
    expect(items).not.toContain('Work locations');
    // Attendance does not bring Leave with it: the two are sold apart.
    expect(items).not.toContain('Leave');
    expect(items).not.toContain('My leave');
    await expectRefused(owner, 'GET', '/org/leave/me/balances', [403], undefined, 'module.off');
    await nav(owner, 'User management');
    await tab(owner, 'Subscription');
    await expect(owner.locator('tr', { hasText: 'Attendance' })).toContainText('Trial until');
    await shot(owner, 'mods-07-trial-on', true);
  });

  test('Leave switched on by itself: leave screens and requests, and the leave year, without Attendance', async () => {
    // Attendance's trial ends early, so the Organization has Leave without Attendance, as on the free plan.
    await modulesOf(admin);
    await admin.locator('.module-row', { hasText: 'Attendance' }).getByRole('button', { name: 'Switch off' }).click();
    await expect(admin.locator('tr', { hasText: ORG }).first().locator('.module-badges')).not.toContainText('Attendance');
    await admin.locator('.module-row', { hasText: 'Balances, leave requests' }).getByRole('button', { name: 'Switch on Leave' }).click();
    await expect(admin.locator('tr', { hasText: ORG }).first().locator('.module-badges')).toContainText('Leave');
    await shot(admin, 'mods-08-leave-on', true);

    await owner.reload();
    const items = await menu(owner);
    expect(items).toContain('Leave');
    expect(items).toContain('My leave');
    expect(items).toContain('Work locations');
    expect(items).not.toContain('Attendance');
    expect(items).not.toContain('My attendance');
    expect((await api(owner, 'GET', '/org/leave/me/balances')).status).toBe(200);
    expect((await api(owner, 'GET', '/org/leave/requests')).status).toBe(200);
    await expectRefused(owner, 'GET', '/org/attendance/me/today', [403], undefined, 'module.off');

    // The leave year is set under Leave, so it works without Attendance; an odd month is refused.
    await nav(owner, 'Leave');
    await tab(owner, 'Setup');
    await owner.selectOption('#leave-year-start', '4');
    await expect(owner.locator('#leave-year-start')).toHaveValue('4');
    expect((await api(owner, 'GET', '/org/attendance/settings')).body.leaveYearStartMonth).toBe(4);
    await expectRefused(owner, 'PUT', '/org/leave/year', [400], { startMonth: 7 });
    await owner.selectOption('#leave-year-start', '1');
    await expect(owner.locator('#leave-year-start')).toHaveValue('1');
    await shot(owner, 'mods-09-leave-year', true);

    await nav(owner, 'User management');
    await tab(owner, 'Subscription');
    await expect(owner.locator('tr', { hasText: 'Balances, leave requests' })).not.toContainText('not included');
    await expect(owner.locator('tr', { hasText: 'Attendance' })).toContainText('not included');
  });

  test('Payroll switched off: its screens, roles and requests go; switched on, they come back', async () => {
    await modulesOf(admin);
    const payroll = admin.locator('.module-row', { hasText: 'Payroll' });
    await payroll.getByRole('button', { name: 'Switch off' }).click();
    await expect(admin.locator('tr', { hasText: ORG }).first().locator('.module-badges')).not.toContainText('Payroll');
    await shot(admin, 'mods-10-payroll-off', true);

    await owner.reload();
    expect(await menu(owner)).not.toContain('Payroll');
    await expectRefused(owner, 'GET', '/org/payroll/runs', [403], undefined, 'module.off');
    await expectRefused(owner, 'GET', '/org/payroll/setup', [403], undefined, 'module.off');
    // Payroll's roles are not offered while Payroll is off.
    await nav(owner, 'User management');
    await tab(owner, 'Invitations');
    await expect(owner.locator('.role-picker')).toContainText('HR Admin');
    await expect(owner.locator('.role-picker')).not.toContainText('Payroll Operator');
    await shot(owner, 'mods-11-roles-without-payroll', true);

    await modulesOf(admin);
    await admin.locator('.module-row', { hasText: 'Payroll' }).getByRole('button', { name: 'Switch on Payroll' }).click();
    await expect(admin.locator('tr', { hasText: ORG }).first().locator('.module-badges')).toContainText('Payroll');
    await owner.reload();
    expect(await menu(owner)).toContain('Payroll');
    expect((await api(owner, 'GET', '/org/payroll/runs')).status).toBe(200);
  });
});
