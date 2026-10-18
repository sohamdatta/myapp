import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, acceptInvite, api, expectRefused, expectNoConsoleErrors, isoDay, istWeekday, shot } from './helpers';

// The two ways of recording attendance besides punching in the app, in an Organization of their own
// (Initech Ltd), so no approved payroll month stands in the way of starting them today:
//   Pune office    present by default: Kavya Nair reports to Ravi Kumar, who marks her day
//   Chennai plant  a biometric device pushes Dev Patel's punches; the test plays the device
//   Hosur warehouse a device whose office network the test is not on, so its calls are turned away

const OWNER = 'owner@initech.test';
const RAVI = 'ravi@initech.test';
const KAVYA = 'kavya@initech.test';
const DEV = 'dev@initech.test';
const GATE = 'E2EZK0001';
const WAREHOUSE = 'E2EZK0002';
const DEVICE_URL = 'http://127.0.0.1:8080/iclock';

/** "2026-10-18 09:31:00" for a moment in India time. */
function deviceTime(ms: number): string {
  return new Date(ms + 330 * 60_000).toISOString().slice(0, 19).replace('T', ' ');
}

/** Two punches earlier today, in and out, never before midnight. */
function todaysPunches(): [string, string] {
  const now = Date.now();
  const midnight = new Date(`${isoDay(0)}T00:00:00+05:30`).getTime();
  const inAt = Math.max(midnight + 30_000, now - 9 * 3600_000);
  const outAt = Math.max(inAt + 60_000, now - 5 * 60_000);
  return [deviceTime(inAt), deviceTime(outAt)];
}

async function invite(owner: Page, email: string, roles: string[]): Promise<string> {
  const r = await api(owner, 'POST', '/org/invitations', { email, roles });
  expect(r.status, JSON.stringify(r.body)).toBe(200);
  return `/?invite=${r.body.token}`;
}

async function people(owner: Page): Promise<Record<string, string>> {
  const r = await api(owner, 'GET', '/org/attendance/assignments');
  return Object.fromEntries(r.body.map((p: any) => [p.email, p.membershipId]));
}

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}

async function device(request: APIRequestContext, method: 'GET' | 'POST', path: string, body?: string) {
  const r = method === 'GET' ? await request.get(`${DEVICE_URL}${path}`) : await request.post(`${DEVICE_URL}${path}`, { data: body });
  return { status: r.status(), text: await r.text() };
}

test.describe.serial('attendance modes', () => {
  let owner: Page;
  let ravi: Page;
  let kavya: Page;
  let dev: Page;
  let ids: Record<string, string>;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [owner, ravi, kavya, dev]) await p?.context().close();
  });

  test('a new Organization with three locations, a manager and his report', async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email);
    await admin.fill('#password', SUPER_ADMIN.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Initech Ltd', slug: 'initech-ltd', plan: 'growth', adminEmail: OWNER }));
    await admin.context().close();
    owner = await acceptInvite(browser, `/?invite=${org.invitationToken}`);

    ravi = await acceptInvite(browser, await invite(owner, RAVI, ['employee', 'manager']));
    kavya = await acceptInvite(browser, await invite(owner, KAVYA, ['employee']));
    dev = await acceptInvite(browser, await invite(owner, DEV, ['employee']));
    ids = await people(owner);

    // Employee records give names and Ravi's reporting line.
    await ok(api(owner, 'POST', '/org/people/employees', { legalName: 'Sunita Rao', joiningDate: isoDay(-900), designation: 'Director', membershipId: ids[OWNER] }));
    const raviRecord = await ok(api(owner, 'POST', '/org/people/employees', { legalName: 'Ravi Kumar', joiningDate: isoDay(-400), designation: 'Office manager', membershipId: ids[RAVI] }));
    await ok(api(owner, 'POST', '/org/people/employees', { legalName: 'Kavya Nair', joiningDate: isoDay(-200), designation: 'Analyst', managerId: raviRecord.id, membershipId: ids[KAVYA] }));
    await ok(api(owner, 'POST', '/org/people/employees', { legalName: 'Dev Patel', joiningDate: isoDay(-300), designation: 'Technician', membershipId: ids[DEV] }));
    await ok(api(owner, 'PUT', `/org/attendance/users/${ids[DEV]}/employee-code`, { code: 'IN-007' }));

    const pune = await ok(api(owner, 'POST', '/org/attendance/locations', { name: 'Pune office', timezone: 'Asia/Kolkata', stateCode: 'MH', punchCheck: 'none', active: true }));
    const chennai = await ok(api(owner, 'POST', '/org/attendance/locations', {
      name: 'Chennai plant', timezone: 'Asia/Kolkata', stateCode: 'TN', punchCheck: 'ip', allowedIps: ['127.0.0.1/32', '::1/128'], active: true,
    }));
    await ok(api(owner, 'POST', '/org/attendance/locations', {
      name: 'Hosur warehouse', timezone: 'Asia/Kolkata', stateCode: 'TN', punchCheck: 'ip', allowedIps: ['203.0.113.0/24'], active: true,
    }));
    const shift = await ok(api(owner, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
    // A weekly off that is neither today nor yesterday, so today is a working day whatever day the tests run.
    const offs = [(istWeekday() + 3) % 7];
    await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: [ids[RAVI], ids[KAVYA]], workLocationId: pune.id, shiftId: shift.id, weeklyOffs: offs, remoteDays: [], effectiveFrom: isoDay(0) }));
    await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: [ids[DEV]], workLocationId: chennai.id, shiftId: shift.id, weeklyOffs: offs, remoteDays: [], effectiveFrom: isoDay(0) }));
  });

  test('the Org Admin makes the Pune office present by default, from a date payroll allows', async () => {
    await owner.reload();
    await nav(owner, 'Attendance');
    await tab(owner, 'Setup');
    const row = owner.locator('tr', { hasText: 'Pune office' });
    await expect(row).toContainText('Punch in the app');
    await row.getByRole('button', { name: 'Attendance mode' }).click();
    await owner.locator('label.choice', { hasText: 'Present by default' }).click();
    await shot(owner, 'mode-01-choose-present', true);

    // Not into a month that is over: the earliest start is the first of this month.
    await owner.fill('#mode-from', isoDay(-40));
    await owner.getByRole('button', { name: 'Change how attendance is recorded' }).click();
    await expect(owner.locator('form .error')).toContainText('at the earliest');
    await shot(owner, 'mode-02-start-too-early');

    await owner.fill('#mode-from', isoDay(0));
    await owner.getByRole('button', { name: 'Change how attendance is recorded' }).click();
    await expect(owner.locator('tr', { hasText: 'Pune office' })).toContainText('Present by default');

    // The plant goes over to its biometric device.
    await owner.locator('tr', { hasText: 'Chennai plant' }).getByRole('button', { name: 'Attendance mode' }).click();
    await owner.locator('label.choice', { hasText: 'Biometric device' }).click();
    await expect(owner.getByText('Next, register the device under the Devices tab')).toBeVisible();
    await shot(owner, 'mode-03-choose-device', true);
    await owner.getByRole('button', { name: 'Change how attendance is recorded' }).click();
    await expect(owner.locator('tr', { hasText: 'Chennai plant' })).toContainText('Biometric device');
    await shot(owner, 'mode-04-locations', true);

    // The same mode twice is refused, and the audit log has the change.
    await expectRefused(owner, 'GET', '/org/attendance/locations', [200]);
    const locations = (await api(owner, 'GET', '/org/attendance/locations')).body;
    const pune = locations.find((l: any) => l.name === 'Pune office');
    await expectRefused(owner, 'POST', `/org/attendance/locations/${pune.id}/mode`, [409], { mode: 'present', effectiveFrom: isoDay(0) }, 'attendance.mode_unchanged');
    await expectRefused(kavya, 'POST', `/org/attendance/locations/${pune.id}/mode`, [403], { mode: 'app', effectiveFrom: isoDay(0) });
  });

  test('Kavya has nothing to punch; the app says so and refuses a punch', async () => {
    await kavya.reload();
    await expect(kavya.getByText('No punching needed')).toBeVisible();
    await expect(kavya.getByRole('button', { name: 'Punch in' })).toHaveCount(0);
    await shot(kavya, 'mode-05-employee-present-by-default', true);
    await expectRefused(kavya, 'POST', '/org/attendance/me/punch', [409], {}, 'attendance.present_by_default');
    await expectRefused(kavya, 'POST', `/org/attendance/users/${ids[RAVI]}/marks`, [403], { date: isoDay(0), status: 'absent', reason: 'Not in today' });
  });

  test('Ravi, her manager, marks the exception with a reason', async () => {
    await ravi.reload();
    await nav(ravi, 'Attendance');
    const row = ravi.locator('tr', { hasText: 'Kavya Nair' });
    await expect(row).toContainText('present by default');
    await row.getByRole('button', { name: 'Details' }).click();
    await ravi.getByLabel('Reason').fill('Did not come in; no call or message');
    await shot(ravi, 'mode-06-mark-absent', true);
    await ravi.getByLabel('Reason').fill('no');
    await ravi.getByRole('button', { name: 'Save mark' }).click();
    await expect(ravi.locator('form .error')).toContainText('at least 5 characters');
    await shot(ravi, 'mode-07-mark-needs-reason');
    await ravi.getByLabel('Reason').fill('Did not come in; no call or message');
    await ravi.getByRole('button', { name: 'Save mark' }).click();
    await expect(ravi.locator('tr', { hasText: 'Kavya Nair' })).toContainText('marked by hand');
    await expect(ravi.locator('tr', { hasText: 'Kavya Nair' })).toContainText(/absent/i);
    await shot(ravi, 'mode-08-marked', true);

    // Nobody marks their own day, and a day ahead is for leave, not a mark.
    await expectRefused(ravi, 'POST', `/org/attendance/users/${ids[RAVI]}/marks`, [409], { date: isoDay(0), status: 'absent', reason: 'Testing my own' }, 'attendance.own_mark');
    await expectRefused(ravi, 'POST', `/org/attendance/users/${ids[KAVYA]}/marks`, [400], { date: isoDay(1), status: 'absent', reason: 'Will be away' });
    // Dev is not in Ravi's team.
    await expectRefused(ravi, 'POST', `/org/attendance/users/${ids[DEV]}/marks`, [404], { date: isoDay(0), status: 'absent', reason: 'Not my team' });
  });

  test('Kavya sees the mark and who made it', async () => {
    await kavya.reload();
    await expect(kavya.locator('.hero')).toContainText(/absent/i);
    const today = kavya.locator('tr', { hasText: /absent/i }).first();
    await today.getByRole('button', { name: 'Why' }).click();
    await expect(kavya.getByText(/Marked absent by Ravi Kumar: Did not come in/)).toBeVisible();
    await shot(kavya, 'mode-09-employee-sees-mark', true);
  });

  test('the Org Admin registers the plant device; a serial number is registered once', async () => {
    await nav(owner, 'Attendance');
    await tab(owner, 'Devices');
    await owner.fill('#dev-name', 'Plant gate');
    await owner.fill('#dev-serial', GATE);
    await owner.selectOption('#dev-location', { label: 'Chennai plant' });
    await owner.getByText('How to connect a device').click();
    await shot(owner, 'mode-10-register-device', true);
    await owner.getByRole('button', { name: 'Register device' }).click();
    await expect(owner.getByText('Plant gate is registered')).toBeVisible();

    await owner.fill('#dev-name', 'Second gate');
    await owner.fill('#dev-serial', GATE.toLowerCase());
    await owner.selectOption('#dev-location', { label: 'Chennai plant' });
    await owner.getByRole('button', { name: 'Register device' }).click();
    await expect(owner.locator('form .error')).toContainText('already registered');
    await shot(owner, 'mode-11-serial-taken');

    await owner.fill('#dev-serial', 'not a serial');
    await owner.getByRole('button', { name: 'Register device' }).click();
    await expect(owner.locator('form .error')).toContainText('4 to 40 letters');

    await owner.fill('#dev-name', 'Warehouse door');
    await owner.fill('#dev-serial', WAREHOUSE);
    await owner.selectOption('#dev-location', { label: 'Hosur warehouse' });
    await owner.getByRole('button', { name: 'Register device' }).click();
    await expect(owner.getByText('Warehouse door is registered')).toBeVisible();
  });

  test('the device calls in and sends its log; what it sends is matched, checked and kept', async ({ request }) => {
    // Start-up: the device asks for its settings.
    const hello = await device(request, 'GET', `/cdata?SN=${GATE}&options=all&pushver=2.4.1`);
    expect(hello.status).toBe(200);
    expect(hello.text).toContain(`GET OPTION FROM: ${GATE}`);
    expect(hello.text).toContain('ATTLOGStamp=None');
    expect((await device(request, 'GET', `/getrequest?SN=${GATE}`)).text).toBe('OK');

    // Its log: Dev in and out, a user id nobody has, a line it garbled and one from a clock running fast.
    const [inAt, outAt] = todaysPunches();
    const future = deviceTime(Date.now() + 3 * 3600_000);
    const log = [`IN-007\t${inAt}\t0\t1\t0\t0\t0`, `999\t${inAt}\t0\t1\t0\t0\t0`, `IN-007\t${outAt}\t0\t1\t0\t0\t0`, 'garbled', `IN-007\t${future}\t0\t1\t0\t0\t0`].join('\n');
    const sent = await device(request, 'POST', `/cdata?SN=${GATE}&table=ATTLOG&Stamp=9001`, log);
    expect(sent.text).toBe('OK: 5');
    // The same lines again add nothing; other tables are acknowledged and dropped.
    expect((await device(request, 'POST', `/cdata?SN=${GATE}&table=ATTLOG&Stamp=9001`, log)).text).toBe('OK: 5');
    expect((await device(request, 'POST', `/cdata?SN=${GATE}&table=OPERLOG&Stamp=1`, 'OPLOG 4\t0\t2026-01-01 10:00:00')).text).toBe('OK: 0');
    expect((await device(request, 'GET', `/cdata?SN=${GATE}`)).text).toContain('ATTLOGStamp=9001');

    // Strangers and devices off their office network are turned away.
    expect((await device(request, 'GET', '/cdata?SN=NOT-REGISTERED')).status).toBe(404);
    expect((await device(request, 'GET', '/cdata')).status).toBe(400);
    expect((await device(request, 'GET', `/cdata?SN=${WAREHOUSE}`)).status).toBe(403);

    await owner.reload();
    await nav(owner, 'Attendance');
    await tab(owner, 'Devices');
    const gate = owner.locator('tr', { hasText: 'Plant gate' });
    await expect(gate).toContainText('User ids nobody has: 999');
    await expect(gate.locator('td').nth(3)).toHaveText('2');
    await expect(owner.locator('tr', { hasText: 'Warehouse door' })).toContainText('turned away');
    await shot(owner, 'mode-12-devices', true);
    await gate.getByRole('button', { name: 'Log' }).click();
    await expect(owner.getByText('Nobody has this employee code').first()).toBeVisible();
    await expect(owner.getByText('In the future: check the device clock').first()).toBeVisible();
    await shot(owner, 'mode-13-device-log', true);
  });

  test('Dev punches at the device; the app shows the punches and does not take one', async () => {
    await dev.reload();
    await expect(dev.getByText('Punch at the attendance device')).toBeVisible();
    await expect(dev.getByText('from the attendance device').first()).toBeVisible();
    await shot(dev, 'mode-14-employee-device', true);
    await expectRefused(dev, 'POST', '/org/attendance/me/punch', [409], {}, 'attendance.device_location');

    await nav(owner, 'Attendance');
    await tab(owner, 'Daily');
    const row = owner.locator('tr', { hasText: 'Dev Patel' });
    await expect(row.locator('td').nth(2)).not.toHaveText('—');
    await expect(row.locator('td').nth(3)).not.toHaveText('—');
    await shot(owner, 'mode-15-daily-board', true);

    // The audit log has every change made here.
    await nav(owner, 'Audit log');
    for (const action of ['attendance.mode_changed', 'attendance.marked', 'attendance.device_registered']) {
      await expect(owner.locator('code', { hasText: action }).first()).toBeVisible();
    }
  });
});
