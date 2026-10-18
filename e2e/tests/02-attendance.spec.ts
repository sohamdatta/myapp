import { test, expect, type Page } from '@playwright/test';
import { OWNER, HR, istWeekday, signIn, nav, tab, isoDay, pastMidnightDmy, expectNoConsoleErrors, shot } from './helpers';

// Attendance: the Org Admin sets up an office, shift and holiday, assigns people,
// the HR Admin punches (refused off the office network, accepted on it), raises a
// work-from-home request that the Org Admin approves, HR adds a manual punch,
// downloads the register and imports a biometric device file twice.

test.describe.serial('attendance', () => {
  let owner: Page;
  let hr: Page;

  test.beforeAll(async ({ browser }) => {
    owner = await signIn(browser, OWNER);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
    await hr?.context().close();
  });

  test('set up a work location, a shift and a holiday', async () => {
    await nav(owner, 'Attendance');
    await expect(owner.locator('#board-date')).toBeVisible();
    await tab(owner, 'Setup');

    await owner.fill('#loc-name', 'Kolkata office');
    await owner.selectOption('#loc-check', 'ip');
    await owner.click('text=Save location');
    await expect(owner.locator('.error')).toBeVisible(); // an IP check needs at least one network

    await owner.fill('#loc-ips', '203.0.113.0/24');
    await owner.click('text=Save location');
    await expect(owner.locator('td', { hasText: 'Kolkata office' })).toBeVisible();

    await owner.fill('#sh-name', 'General');
    await owner.click('text=Save shift');
    await expect(owner.locator('td', { hasText: 'General' })).toBeVisible();

    await owner.fill('#hol-date', isoDay(30));
    await owner.fill('#hol-name', 'Founders day');
    await owner.click('text=Add holiday');
    await expect(owner.locator('td', { hasText: 'Founders day' })).toBeVisible();
    await shot(owner, '12-attendance-setup', true);
  });

  test('assign people, set an employee code and allow work from home', async () => {
    await tab(owner, 'People');
    const code = owner.getByLabel(`Employee code for ${HR}`);
    await code.fill('E001');
    await code.press('Enter');
    await owner.waitForTimeout(600);

    await owner.getByLabel(`Select ${HR}`).check();
    await owner.getByLabel(`Select ${OWNER}`).check();
    await owner.getByLabel('Sunday').first().uncheck(); // no weekly off, so every day of the run is a working day
    // Friday is a regular day at home, or Saturday when today is a Friday: the next test punches off the office
    // network, which a day at home allows.
    const remote = istWeekday() === 5 ? 'Saturday' : 'Friday';
    await owner.getByLabel(remote).nth(1).check();
    await owner.selectOption('#as-location', { label: 'Kolkata office' });
    await owner.selectOption('#as-shift', { index: 1 });
    await owner.click('text=Assign to 2 selected');
    await expect(owner.locator('td', { hasText: 'Kolkata office' }).first()).toBeVisible();

    await owner.getByLabel(`${HR} may work from home`).check();
    await expect(owner.getByText('Can request').first()).toBeVisible();
    await shot(owner, '13-attendance-people', true);
  });

  test('a punch off the office network is refused, and accepted once the network is added', async ({ browser }) => {
    hr = await signIn(browser, HR);
    await nav(hr, 'My attendance');
    await hr.click('.punch-button');
    await expect(hr.locator('.error')).toBeVisible();

    // Add this machine (the frontend's proxy talks to the backend from localhost).
    await tab(owner, 'Setup');
    await owner.locator('tr', { hasText: 'Kolkata office' }).getByText('Change').click();
    await owner.fill('#loc-ips', '203.0.113.0/24, 127.0.0.1, ::1');
    await owner.click('text=Save location');
    await expect(owner.locator('td', { hasText: '127.0.0.1' })).toBeVisible();

    await hr.click('.punch-button');
    await expect(hr.locator('.punch-list')).toBeVisible();
    await expect(hr.locator('.punch-button')).toContainText(/out/i);

    // Every day explains itself.
    await hr.locator('tbody tr').first().getByText('Why').click();
    await expect(hr.locator('.trail')).toBeVisible();
    await shot(hr, '14-my-attendance', true);
  });

  test('a work-from-home request is checked, sent and approved by someone else', async () => {
    await hr.click('text=New request');
    await hr.selectOption('#req-kind', 'work_from_home');
    await hr.fill('#req-reason', 'Plumber visiting at home');
    await shot(hr, '15-attendance-request');
    await hr.fill('#req-reason', 'Hm');
    await hr.click('text=Send request');
    await expect(hr.locator('form .error')).toBeVisible(); // reason too short

    await hr.fill('#req-reason', 'Plumber visiting at home');
    await hr.click('text=Send request');
    await expect(hr.locator('td', { hasText: 'Plumber visiting' })).toBeVisible();

    // The HR Admin sees the queue but cannot decide their own request.
    await nav(hr, 'Attendance');
    await tab(hr, 'Requests');
    const own = hr.locator('tbody tr', { hasText: 'Plumber visiting' });
    await expect(own).toBeVisible();
    await expect(own.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0);

    await tab(owner, 'Requests');
    await expect(owner.locator('td', { hasText: 'Plumber visiting' })).toBeVisible();
    await owner.getByLabel(`Note for ${HR}`).fill('Fine');
    await shot(owner, '16-attendance-approve');
    await owner.getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(owner.getByText('No pending requests.')).toBeVisible();
  });

  test('the daily board, a manual punch and the register download', async () => {
    await tab(owner, 'Daily');
    await expect(owner.locator('td', { hasText: HR })).toBeVisible();

    await owner.locator('tr', { hasText: OWNER }).getByText('Details').click();
    await owner.fill('.detail-row input[type=time]', '00:00'); // never in the future, even just after midnight
    await owner.selectOption('.detail-row select', 'in');
    await owner.fill('.detail-row input[id^=mp-reason]', 'Turnstile was broken');
    await shot(owner, '17-daily-board');
    await owner.click('text=Add punch');
    await expect(owner.locator('tr', { hasText: OWNER }).locator('td', { hasText: 'working' })).toBeVisible();

    const download = owner.waitForEvent('download');
    await owner.click('text=Download the');
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/\.csv$/);
    const fs = await import('node:fs');
    const csv = fs.readFileSync(await file.path(), 'utf8');
    expect(csv.split('\n')[0]).toMatch(/email/i);
    expect(csv).toContain(HR);
  });

  test('a device file imports once; unknown codes are reported; the same file again adds nothing', async () => {
    await tab(owner, 'Devices');
    await owner.fill('#imp-device', 'Main gate');
    const dmy = pastMidnightDmy(); // the punches below must not be in the future
    const csv = `Emp Code,Punch Time,Direction\nE001,${dmy} 00:10:00,IN\nE001,${dmy} 00:40:00,OUT\nE777,${dmy} 00:15:00,IN\n`;
    const file = { name: 'gate.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) };

    await owner.setInputFiles('#imp-file', file);
    await expect(owner.locator('#map-code')).toBeVisible();
    await shot(owner, '18-device-import', true);
    await owner.click('text=Import 3 rows');
    const first = owner.locator('.notice');
    await expect(first).toBeVisible();
    await expect(first).toContainText('2 of 3 rows imported.');
    await expect(first).toContainText('0 were already here and 1 could not be used');
    await expect(owner.locator('td', { hasText: 'gate.csv' }).first()).toBeVisible();
    await shot(owner, '19-device-imported');

    await owner.setInputFiles('#imp-file', file);
    await expect(owner.locator('#map-code')).toBeVisible();
    await owner.click('text=Import 3 rows');
    await expect(owner.locator('.notice')).toContainText('0 of 3 rows imported.');
    await expect(owner.locator('.notice')).toContainText('2 were already here');

    await nav(owner, 'Audit log');
    for (const action of ['attendance.location_created', 'attendance.assigned', 'attendance.request_approved', 'attendance.manual_punch', 'attendance.imported']) {
      await expect(owner.locator('code', { hasText: action }).first()).toBeVisible();
    }
  });
});
