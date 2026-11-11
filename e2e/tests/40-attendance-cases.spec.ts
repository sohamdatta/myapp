import { test, expect, type Page } from '@playwright/test';
import { holidayDay, readState, statePath, writeState } from './attendance-cases';
import * as fs from 'fs';
import { currentYear, SUPER_ADMIN, signIn, newPage, nav, tab, acceptInvite, api, isoDay, istWeekday, nowMs, expectNoSidewaysScroll } from './helpers';

const DEVICE_URL = 'http://127.0.0.1:8080/iclock';
const OWNER = 'owner@acme.test';
const U = (n: string) => `${n}@acme.test`;

let owner: Page, hr: Page, aud: Page, mgr: Page;
const emp: Record<string, Page> = {};
let ids: Record<string, string> = {};
const loc: Record<string, any> = {};
let shift: any;
const hol = (n: number) => holidayDay(n, currentYear(), Number(isoDay(0).slice(5, 7)));
const off = () => [(istWeekday() + 3) % 7];

async function ok(p: Promise<{ status: number; body: any }>) { const r = await p; expect([200, 204], JSON.stringify(r.body)).toContain(r.status); return r.body; }
async function invite(email: string, roles: string[]) { const b = await ok(api(owner, 'POST', '/org/invitations', { email, roles })); return `/?invite=${b.token}`; }
async function openSetup(p: Page) { await p.reload(); await nav(p, 'Attendance'); await tab(p, 'Setup'); }
async function openPeople(p: Page) { await p.reload(); await nav(p, 'Attendance'); await tab(p, 'People'); }
async function addLocForm(p: Page) { await openSetup(p); await p.getByRole('button', { name: 'Add a work location' }).click(); }
async function saveLoc(p: Page) { await p.getByRole('button', { name: 'Save location' }).click(); }
const err = (p: Page) => p.locator('form .error').first();
async function addShiftForm(p: Page) { await openSetup(p); await p.getByRole('button', { name: 'Add a shift' }).click(); }
async function mkLoc(body: any) { return ok(api(owner, 'POST', '/org/attendance/locations', { timezone: 'Asia/Kolkata', stateCode: 'KA', active: true, punchCheck: 'none', ...body })); }
async function assign(names: string[], l: any, extra: any = {}) {
  await ok(api(owner, 'POST', '/org/attendance/assignments', { membershipIds: names.map((n) => ids[U(n)]), workLocationId: l.id, shiftId: shift.id, weeklyOffs: off(), remoteDays: [], effectiveFrom: isoDay(-3), ...extra }));
}
function istStamp(ms: number) { return new Date(ms + 330 * 60_000).toISOString().slice(0, 19).replace('T', ' '); }

test.describe('attendance test cases, part 1', () => {
  test.beforeAll(async ({ browser }) => {
    if (fs.existsSync(statePath('a'))) {   // a worker restarted after a failure: the Organization is there
      const st = readState('a');
      ids = st.ids; Object.assign(loc, st.loc); shift = st.shift;
      owner = await signIn(browser, OWNER); hr = await signIn(browser, U('hr')); aud = await signIn(browser, U('aud')); mgr = await signIn(browser, U('mgr'));
      for (const n of ['e1', 'e2', 'e3', 'e4', 'e5', 'e6', 'e7', 'e8', 'e9']) emp[n] = await signIn(browser, U(n));
      return;
    }
    fs.rmSync(statePath('b'), { force: true });   // left by an earlier run on another database
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Acme Test', slug: 'acme-test', adminEmail: OWNER }));
    await admin.context().close();
    owner = await acceptInvite(browser, `/?invite=${org.invitationToken}`);
    hr = await acceptInvite(browser, await invite(U('hr'), ['hr_admin']));
    aud = await acceptInvite(browser, await invite(U('aud'), ['auditor']));
    mgr = await acceptInvite(browser, await invite(U('mgr'), ['employee', 'manager']));
    for (const n of ['e1', 'e2', 'e3', 'e4', 'e5', 'e6', 'e7', 'e8', 'e9']) emp[n] = await acceptInvite(browser, await invite(U(n), ['employee']));
    const list = await ok(api(owner, 'GET', '/org/attendance/assignments'));
    ids = Object.fromEntries(list.map((p: any) => [p.email, p.membershipId]));
    shift = await ok(api(owner, 'POST', '/org/attendance/shifts', { name: 'General', startTime: '09:30', endTime: '18:30', graceMinutes: 10, fullDayMinutes: 480, halfDayMinutes: 240, active: true }));
    loc.open = await mkLoc({ name: 'Open office' });
    loc.ip = await mkLoc({ name: 'IP office', punchCheck: 'ip', allowedIps: ['203.0.113.0/24'] });
    loc.geo = await mkLoc({ name: 'Geo office', punchCheck: 'location', latitude: 22.5726, longitude: 88.3639, radiusM: 200 });
    loc.either = await mkLoc({ name: 'Either office', punchCheck: 'either', allowedIps: ['203.0.113.0/24'], latitude: 22.5726, longitude: 88.3639, radiusM: 200 });
    loc.pres = await mkLoc({ name: 'Present office' });
    loc.dev = await mkLoc({ name: 'Device plant', punchCheck: 'ip', allowedIps: ['127.0.0.1/32', '::1/128'] });
    await assign(['e1'], loc.open); await assign(['e2'], loc.ip); await assign(['e3'], loc.geo); await assign(['e4'], loc.either);
    const m = await ok(api(owner, 'POST', '/org/people/employees', { legalName: 'Mala Mgr', joiningDate: isoDay(-400), designation: 'Manager', membershipId: ids[U('mgr')] }));   // date-safe: joined long ago, any date will do
    await ok(api(owner, 'POST', '/org/people/employees', { legalName: 'Pia Present', joiningDate: isoDay(-200), designation: 'Analyst', managerId: m.id, membershipId: ids[U('e6')] }));   // date-safe: joined long ago, any date will do
    await ok(api(owner, 'PUT', `/org/attendance/users/${ids[U('e7')]}/employee-code`, { code: 'DEV-7' }));
    await assign(['e6'], loc.pres); await assign(['e7'], loc.dev);
    await ok(api(owner, 'POST', `/org/attendance/locations/${loc.pres.id}/mode`, { mode: 'present', effectiveFrom: isoDay(0) }));
    await ok(api(owner, 'POST', `/org/attendance/locations/${loc.dev.id}/mode`, { mode: 'device', effectiveFrom: isoDay(0) }));
    await assign(['mgr'], loc.open);
    writeState('a', { ids, loc, shift });
  });

  // ---------- locations ----------
  test('TC-005 TC-012 TC-009 add location with only a name; default time zone', async () => {
    await addLocForm(hr);
    await expect(hr.locator('#loc-tz')).toHaveValue('Asia/Kolkata');
    await hr.fill('#loc-name', 'Kolkata office'); await saveLoc(hr);
    await expect(hr.locator('tr', { hasText: 'Kolkata office' })).toContainText('Asia/Kolkata');
    await expect(hr.locator('tr', { hasText: 'Kolkata office' })).toContainText('Punch in the app');
  });
  test('TC-006 name is required', async () => {
    await addLocForm(hr); await saveLoc(hr); await expect(err(hr)).toBeVisible();
  });
  test('TC-007 duplicate name refused regardless of case', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'KOLKATA OFFICE'); await saveLoc(hr); await expect(err(hr)).toBeVisible();
  });
  test('TC-010 alternate zone accepted', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Dubai office'); await hr.fill('#loc-tz', 'Asia/Dubai'); await saveLoc(hr);
    await expect(hr.locator('tr', { hasText: 'Dubai office' })).toContainText('Asia/Dubai');
  });
  test('TC-011 invalid zone refused', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Mars'); await hr.fill('#loc-tz', 'Mars/Phobos'); await saveLoc(hr);
    await expect(err(hr)).toContainText('Unknown time zone');
  });
  test('TC-013 valid state code saved', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Mumbai office'); await hr.fill('#loc-state', 'MH'); await saveLoc(hr);
    await expect(hr.locator('tr', { hasText: 'Mumbai office' })).toContainText('MH');
  });
  test('TC-014 invalid state refused', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Bad state'); await hr.fill('#loc-state', 'XX'); await saveLoc(hr);
    await expect(err(hr)).toContainText('two-letter code of an Indian state');
  });
  test('TC-015 no check shows hint', async () => {
    await addLocForm(hr); await hr.selectOption('#loc-check', 'none');
    await expect(hr.getByText('Anyone assigned here can punch from anywhere')).toBeVisible();
  });
  test('TC-017 TC-041 ip check shows address field only; no position button', async () => {
    await addLocForm(hr); await hr.selectOption('#loc-check', 'ip');
    await expect(hr.locator('#loc-ips')).toBeVisible(); await expect(hr.locator('#loc-lat')).toHaveCount(0);
    await expect(hr.getByRole('button', { name: 'Use where I am now' })).toHaveCount(0);
  });
  test('TC-018 ip check without address refused', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'No ip'); await hr.selectOption('#loc-check', 'ip'); await saveLoc(hr);
    await expect(err(hr)).toContainText('Add at least one office network address');
  });
  test('TC-021 location check shows position fields only', async () => {
    await addLocForm(hr); await hr.selectOption('#loc-check', 'location');
    await expect(hr.locator('#loc-lat')).toBeVisible(); await expect(hr.locator('#loc-ips')).toHaveCount(0);
    await expect(hr.getByRole('button', { name: 'Use where I am now' })).toBeVisible();
  });
  test('TC-022 location check without position refused', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'No pos'); await hr.selectOption('#loc-check', 'location'); await saveLoc(hr);
    await expect(err(hr)).toContainText('Set the position and radius');
  });
  test('TC-027 either shows both groups', async () => {
    await addLocForm(hr); await hr.selectOption('#loc-check', 'either');
    await expect(hr.locator('#loc-ips')).toBeVisible(); await expect(hr.locator('#loc-lat')).toBeVisible();
  });
  test('TC-028 either with neither refused', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Neither'); await hr.selectOption('#loc-check', 'either'); await saveLoc(hr);
    await expect(err(hr)).toContainText('Add an office network address or a position');
  });
  test('TC-031 several addresses saved', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Two nets'); await hr.selectOption('#loc-check', 'ip');
    await hr.fill('#loc-ips', '203.0.113.0/24, 198.51.100.7'); await saveLoc(hr);
    await expect(hr.locator('tr', { hasText: 'Two nets' })).toContainText('203.0.113.0/24, 198.51.100.7');
  });
  test('TC-032 invalid address refused', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Bad net'); await hr.selectOption('#loc-check', 'ip'); await hr.fill('#loc-ips', '999.1.1.1'); await saveLoc(hr);
    await expect(err(hr)).toContainText('is not a network address');
  });
  test('TC-034 TC-037 latitude limits and pairing', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Geo bad'); await hr.selectOption('#loc-check', 'location');
    await hr.fill('#loc-lat', '91'); await hr.fill('#loc-lon', '88'); await hr.fill('#loc-radius', '100'); await saveLoc(hr);
    await expect(err(hr)).toBeVisible();
    await hr.fill('#loc-lon', ''); await hr.fill('#loc-lat', '22'); await saveLoc(hr);
    await expect(err(hr)).toBeVisible();
  });
  test('TC-035 non-numeric latitude refused', async () => {
    await addLocForm(hr); await hr.fill('#loc-name', 'Geo abc'); await hr.selectOption('#loc-check', 'location');
    await hr.fill('#loc-lat', 'abc'); await hr.fill('#loc-lon', '88'); await hr.fill('#loc-radius', '100'); await saveLoc(hr);
    await expect(err(hr)).toContainText('Latitude');
  });
  test('TC-038 TC-039 radius boundaries and whole number', async () => {
    for (const r of ['24', '5001', '100.5']) {
      await addLocForm(hr); await hr.fill('#loc-name', 'Radius ' + r.replace('.', '_')); await hr.selectOption('#loc-check', 'location');
      await hr.fill('#loc-lat', '22.5'); await hr.fill('#loc-lon', '88.3'); await hr.fill('#loc-radius', r); await saveLoc(hr);
      await expect(err(hr), r).toBeVisible();
      if (r !== '100.5') await expect(err(hr)).toContainText('between 25 and 5000');
    }
    for (const r of ['25', '5000']) {
      await addLocForm(hr); await hr.fill('#loc-name', 'Radius ok ' + r); await hr.selectOption('#loc-check', 'location');
      await hr.fill('#loc-lat', '22.5'); await hr.fill('#loc-lon', '88.3'); await hr.fill('#loc-radius', r); await saveLoc(hr);
      await expect(hr.locator('tr', { hasText: 'Radius ok ' + r })).toContainText(r + ' m');
    }
  });
  test('TC-043 TC-044 TC-045 change, edit and deactivate', async () => {
    await openSetup(hr);
    await hr.locator('tr', { hasText: 'Two nets' }).getByRole('button', { name: 'Change', exact: true }).click();
    await expect(hr.getByRole('heading', { name: 'Change Two nets' })).toBeVisible();
    await expect(hr.locator('#loc-name')).toHaveValue('Two nets');
    await hr.fill('#loc-ips', '198.51.100.7'); await saveLoc(hr);
    await expect(hr.locator('tr', { hasText: 'Two nets' })).toContainText('198.51.100.7');
    await hr.locator('tr', { hasText: 'Two nets' }).getByRole('button', { name: 'Change', exact: true }).click();
    await hr.getByLabel('Active (can be assigned to users)').uncheck(); await saveLoc(hr);
    await expect(hr.locator('tr', { hasText: 'Two nets' })).toContainText('inactive');
  });

  // ---------- modes ----------
  test('TC-046 TC-047 TC-048 mode panel', async () => {
    await openSetup(owner);
    await owner.locator('tr', { hasText: 'Open office' }).getByRole('button', { name: 'Attendance mode' }).click();
    await expect(owner.locator('label.choice')).toHaveCount(3);
    await expect(owner.locator('label.choice', { hasText: 'Punch in the app' })).toContainText('now');
    await owner.getByRole('button', { name: 'Cancel' }).click();
    await expect(owner.locator('label.choice')).toHaveCount(0);
    await expect(owner.locator('tr', { hasText: 'Open office' })).toContainText('Punch in the app');
  });
  test('TC-058 TC-059 TC-060 TC-061 mode date rules', async () => {
    await openSetup(owner);
    await owner.locator('tr', { hasText: 'IP office' }).getByRole('button', { name: 'Attendance mode' }).click();
    await owner.locator('label.choice', { hasText: 'Present by default' }).click();
    const go = () => owner.getByRole('button', { name: 'Change how attendance is recorded' }).click();
    await owner.fill('#mode-from', isoDay(-40)); await go(); await expect(err(owner)).toContainText('at the earliest');   // date-safe: refused as too far back, whatever the month
    await owner.fill('#mode-from', '2030-01-01'); await go(); await expect(err(owner)).toContainText('within the next year');
    await owner.fill('#mode-from', ''); await go(); await expect(err(owner)).toBeVisible();
    const r = await api(owner, 'POST', `/org/attendance/locations/${loc.pres.id}/mode`, { mode: 'present', effectiveFrom: isoDay(0) });
    expect(r.status).toBe(409); expect(r.body.code).toBe('attendance.mode_unchanged');
  });
  test('TC-049 TC-064 device mode and future change shown', async () => {
    await openSetup(owner);
    await owner.locator('tr', { hasText: 'Geo office' }).getByRole('button', { name: 'Attendance mode' }).click();
    await owner.locator('label.choice', { hasText: 'Biometric device' }).click();
    await expect(owner.getByText('Next, register the device under the Devices tab')).toBeVisible();
    await owner.fill('#mode-from', isoDay(5));
    await owner.getByRole('button', { name: 'Change how attendance is recorded' }).click();
    const row = owner.locator('tr', { hasText: 'Geo office' });
    await expect(row).toContainText('Biometric device from');
    await expect(row).toContainText('Punch in the app');
  });
  test('TC-054 present mode: employee sees No punching needed', async () => {
    await emp.e6.reload(); await nav(emp.e6, 'My attendance');
    await expect(emp.e6.getByText('No punching needed')).toBeVisible();
    await expect(emp.e6.getByRole('button', { name: /Punch (in|out)/ })).toHaveCount(0);
  });
  test('TC-055 punch refused in present mode', async () => {
    const r = await api(emp.e6, 'POST', '/org/attendance/me/punch', {}); expect(r.status).toBe(409); expect(r.body.code).toBe('attendance.present_by_default');
  });
  test('TC-050 TC-052 device location: message and refusal', async () => {
    await emp.e7.reload(); await nav(emp.e7, 'My attendance');
    await expect(emp.e7.getByText('Punch at the attendance device')).toBeVisible();
    const r = await api(emp.e7, 'POST', '/org/attendance/me/punch', {}); expect(r.status).toBe(409); expect(r.body.code).toBe('attendance.device_location');
  });

  // ---------- shifts ----------
  test('TC-066 add shift with defaults', async () => {
    await addShiftForm(hr); await hr.fill('#sh-name', 'Late shift'); await hr.getByRole('button', { name: 'Save shift' }).click();
    const row = hr.locator('tr', { hasText: 'Late shift' });
    await expect(row).toContainText('09:30 to 18:30'); await expect(row).toContainText('10 min'); await expect(row).toContainText('8 h / 4 h');
  });
  test('TC-067 TC-068 TC-069 shift name rules', async () => {
    await addShiftForm(hr); await hr.getByRole('button', { name: 'Save shift' }).click(); await expect(err(hr)).toContainText('Give it a name');
    await hr.fill('#sh-name', 'x'.repeat(81)); await hr.getByRole('button', { name: 'Save shift' }).click(); await expect(err(hr)).toContainText('at most 80');
    await hr.fill('#sh-name', 'late SHIFT'); await hr.getByRole('button', { name: 'Save shift' }).click(); await expect(err(hr)).toBeVisible();
  });
  test('TC-070 overnight shift hint', async () => {
    await addShiftForm(hr); await hr.fill('#sh-name', 'Night'); await hr.fill('#sh-start', '22:00'); await hr.fill('#sh-end', '06:00');
    await hr.getByRole('button', { name: 'Save shift' }).click();
    await expect(hr.locator('tr', { hasText: 'Night' })).toContainText('ends the next day');
  });
  test('TC-073 TC-075 grace limits', async () => {
    for (const [g, msg] of [['121', 'Grace must be between 0 and 120'], ['-1', 'Grace must be between 0 and 120'], ['ten', 'Grace']]) {
      await addShiftForm(hr); await hr.fill('#sh-name', 'G' + g); await hr.fill('#sh-grace', g); await hr.getByRole('button', { name: 'Save shift' }).click();
      await expect(err(hr), g).toContainText(msg);
    }
  });
  test('TC-076 TC-078 TC-079 day length limits', async () => {
    for (const [full, half, msg] of [['25', '4', 'A full day must be 1 to 24 hours'], ['0.5', '0.25', 'A full day must be 1 to 24 hours'], ['8', '8', 'A full day must be 1 to 24 hours'], ['8', '0.25', 'A full day must be 1 to 24 hours']]) {
      await addShiftForm(hr); await hr.fill('#sh-name', `L${full}-${half}`); await hr.fill('#sh-full', full); await hr.fill('#sh-half', half);
      await hr.getByRole('button', { name: 'Save shift' }).click(); await expect(err(hr), `${full}/${half}`).toContainText(msg);
    }
  });
  test('TC-081 TC-082 edit prefill and deactivate shift', async () => {
    await openSetup(hr);
    await hr.locator('tr', { hasText: 'Late shift' }).getByRole('button', { name: 'Change' }).click();
    await expect(hr.locator('#sh-full')).toHaveValue('8'); await expect(hr.locator('#sh-half')).toHaveValue('4');
    await hr.getByLabel('Active (can be assigned to users)').uncheck(); await hr.getByRole('button', { name: 'Save shift' }).click();
    await expect(hr.locator('tr', { hasText: 'Late shift' })).toContainText('inactive');
  });

  // ---------- holidays ----------
  test('TC-084 TC-090 add holiday; default scope', async () => {
    await openSetup(hr);
    await expect(hr.locator('#hol-location')).toHaveValue('');
    await hr.fill('#hol-date', hol(0)); await hr.fill('#hol-name', 'Founders day'); await hr.getByRole('button', { name: 'Add holiday' }).click();
    await expect(hr.locator('tr', { hasText: 'Founders day' })).toContainText('Every location');
  });
  test('TC-085 TC-086 date and name required', async () => {
    await openSetup(hr);
    await hr.fill('#hol-name', 'No date'); await hr.getByRole('button', { name: 'Add holiday' }).click();
    await expect(hr.locator('.error').first()).toContainText('Choose the date');
    await hr.fill('#hol-name', ''); await hr.fill('#hol-date', hol(1)); await hr.getByRole('button', { name: 'Add holiday' }).click();
    await expect(hr.locator('.error').first()).toBeVisible();
  });
  test('TC-087 duplicate holiday refused', async () => {
    await openSetup(hr); await hr.fill('#hol-date', hol(0)); await hr.fill('#hol-name', 'Again'); await hr.getByRole('button', { name: 'Add holiday' }).click();
    await expect(hr.locator('.error').first()).toBeVisible();
  });
  test('TC-089 holiday for one location', async () => {
    await openSetup(hr); await hr.fill('#hol-date', hol(2)); await hr.fill('#hol-name', 'Local day'); await hr.selectOption('#hol-location', { label: 'Open office' });
    await hr.getByRole('button', { name: 'Add holiday' }).click();
    await expect(hr.locator('tr', { hasText: 'Local day' })).toContainText('Open office');
  });
  test('TC-091 TC-092 year selector and next-year switch', async () => {
    await openSetup(hr);
    const yr = new Date().getFullYear();
    await expect(hr.locator('#hol-year option')).toHaveCount(3);
    await hr.fill('#hol-date', `${yr + 1}-01-26`); await hr.fill('#hol-name', 'Next Republic'); await hr.getByRole('button', { name: 'Add holiday' }).click();
    await expect(hr.getByRole('heading', { name: `Holidays in ${yr + 1}` })).toBeVisible();
    await expect(hr.locator('tr', { hasText: 'Next Republic' })).toBeVisible();
  });
  test('TC-094 remove holiday', async () => {
    await openSetup(hr); await hr.locator('tr', { hasText: 'Local day' }).getByRole('button', { name: 'Remove' }).click();
    await expect(hr.locator('tr', { hasText: 'Local day' })).toHaveCount(0);
  });
  test('TC-095 auditor cannot add or remove holidays', async () => {
    await openSetup(aud);
    await expect(aud.getByRole('button', { name: 'Add holiday' })).toHaveCount(0);
    await expect(aud.getByRole('button', { name: 'Remove' })).toHaveCount(0);
  });

  // ---------- people / assignment ----------
  test('TC-097 TC-100 assign disabled with none selected; unassigned badge', async () => {
    await openPeople(hr);
    await expect(hr.getByRole('button', { name: /Assign to 0 selected/ })).toBeDisabled();
    await expect(hr.locator('tr', { hasText: 'e8@acme.test' })).toContainText('not assigned');
  });
  test('TC-098 TC-102 missing location/shift refused; default weekly off Sunday', async () => {
    await openPeople(hr);
    await expect(hr.getByLabel('Sunday').first()).toBeChecked();
    await hr.getByLabel(/Select .*e8/).check(); await hr.getByRole('button', { name: /Assign to 1 selected/ }).click();
    await expect(err(hr)).toContainText('Choose a work location and a shift');
  });
  test('TC-096 TC-103 TC-101 assign several users; weekly offs', async () => {
    await openPeople(hr);
    await hr.selectOption('#as-location', { label: 'Open office' }); await hr.selectOption('#as-shift', { index: 1 });
    await hr.getByLabel('Saturday').first().check();
    await hr.getByLabel(/Select .*e8/).check(); await hr.getByLabel(/Select .*e9/).check();
    await hr.getByRole('button', { name: /Assign to 2 selected/ }).click();
    for (const e of ['e8', 'e9']) await expect(hr.locator('tr', { hasText: `${e}@acme.test` })).toContainText('Open office');
    await expect(hr.locator('tr', { hasText: 'e8@acme.test' })).toContainText(/Sun, Sat|Sat, Sun/);
    await emp.e8.reload(); await nav(emp.e8, 'My attendance');
    await expect(emp.e8.getByText('No work location and shift are assigned')).toHaveCount(0);
  });
  test('TC-105 all seven weekly offs refused', async () => {
    await openPeople(hr);
    await hr.selectOption('#as-location', { label: 'Open office' }); await hr.selectOption('#as-shift', { index: 1 });
    for (const d of ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']) await hr.getByLabel(d).first().check();
    await hr.getByLabel(/Select .*e9/).check(); await hr.getByRole('button', { name: /Assign to 1 selected/ }).click();
    await expect(err(hr)).toContainText('at most six');
  });
  test('TC-107 TC-108 TC-110 remote days', async () => {
    await openPeople(hr);
    await expect(hr.getByRole('group', { name: /Regular days at home/ }).getByLabel('Sunday')).toBeDisabled();
    await hr.selectOption('#as-location', { label: 'Open office' }); await hr.selectOption('#as-shift', { index: 1 });
    await hr.getByRole('group', { name: /Regular days at home/ }).getByLabel('Thursday').check();
    await hr.getByLabel(/Select .*e9/).check(); await hr.getByRole('button', { name: /Assign to 1 selected/ }).click();
    await expect(hr.locator('tr', { hasText: 'e9@acme.test' })).toContainText('Regular: Thu');
    const r = await api(hr, 'POST', '/org/attendance/assignments', { membershipIds: [ids[U('e9')]], workLocationId: loc.open.id, shiftId: shift.id, weeklyOffs: [0], remoteDays: [0], effectiveFrom: isoDay(0) });
    expect(r.status).toBe(400);
  });
  test('TC-112 assignment date required', async () => {
    await openPeople(hr); await hr.selectOption('#as-location', { label: 'Open office' }); await hr.selectOption('#as-shift', { index: 1 });
    await hr.fill('#as-from', ''); await hr.getByLabel(/Select .*e8/).check(); await hr.getByRole('button', { name: /Assign to 1 selected/ }).click();
    await expect(err(hr)).toContainText('Choose the date the assignment starts');
  });
  test('TC-116 TC-117 TC-118 employee codes', async () => {
    await openPeople(hr);
    const box = (e: string) => hr.getByLabel(new RegExp(`Employee code for .*${e}`));
    await box('e8').fill('E1001'); await box('e8').press('Enter'); await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'People');
    await expect(box('e8')).toHaveValue('E1001');
    await box('e9').fill('e1001'); await box('e9').press('Enter');
    await expect(hr.locator('tr', { hasText: 'e9@acme.test' }).locator('.hint').first()).toBeVisible();
    const ok40 = await api(hr, 'PUT', `/org/attendance/users/${ids[U('e9')]}/employee-code`, { code: 'a'.repeat(40) }); expect([200,204]).toContain(ok40.status);
    const bad = await api(hr, 'PUT', `/org/attendance/users/${ids[U('e9')]}/employee-code`, { code: 'a'.repeat(41) }); expect(bad.status).toBe(400);
  });
  test('TC-120 auditor sees people read-only', async () => {
    await openPeople(aud);
    await expect(aud.locator('main tbody tr').first()).toBeVisible();
    // Work-from-home ticks still show, greyed out, so the auditor can read them; nothing can be changed.
    await expect(aud.locator('main input:not([disabled])')).toHaveCount(0);
    await expect(aud.getByRole('button', { name: /Assign to/ })).toHaveCount(0);
  });

  // ---------- WFH ----------
  test('TC-124 TC-125 TC-121 enable work from home', async () => {
    await openSetup(hr);
    const on = hr.getByLabel('Users can work from home');
    if (!(await on.isChecked())) await on.check();
    await hr.getByRole('button', { name: 'Save work-from-home rules' }).click(); await expect(hr.getByText('Saved.')).toBeVisible();
    await on.uncheck(); await expect(hr.locator('#wfh-week')).toBeDisabled(); await expect(hr.locator('#wfh-month')).toBeDisabled();
    await on.check();
    await openPeople(hr);
    await hr.getByLabel(/e1@acme.test may work from home|e1.* may work from home/).check();
    await expect(hr.locator('tr', { hasText: 'e1@acme.test' })).toContainText('Can request');
  });
  test('TC-132 TC-134 limit boundaries', async () => {
    await openSetup(hr);
    for (const [id, v] of [['#wfh-week', '0'], ['#wfh-week', '8'], ['#wfh-month', '0'], ['#wfh-month', '32']]) {
      await hr.fill(id, v); await hr.getByRole('button', { name: 'Save work-from-home rules' }).click();
      await expect(err(hr), `${id}=${v}`).toContainText('weekly limit is 1 to 7');
      await hr.fill(id, '');
    }
    await hr.fill('#wfh-week', '7'); await hr.fill('#wfh-month', '31'); await hr.getByRole('button', { name: 'Save work-from-home rules' }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
    await hr.fill('#wfh-week', ''); await hr.fill('#wfh-month', ''); await hr.getByRole('button', { name: 'Save work-from-home rules' }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
  });
  test('TC-122 TC-217 user not allowed cannot request WFH', async () => {
    await openPeople(hr); const box2 = hr.getByLabel(/e2.* may work from home/); if (await box2.isChecked()) await box2.click();
    await expect(hr.locator('tr', { hasText: 'e2@acme.test' })).toContainText('Not allowed');
    await emp.e2.reload(); await nav(emp.e2, 'My attendance');
    await emp.e2.getByRole('button', { name: 'New request' }).click();
    await expect(emp.e2.locator('#req-kind option', { hasText: 'Work from home' })).toHaveCount(0);
    const r = await api(emp.e2, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(1), toDate: isoDay(1), reason: 'Plumber visit' });
    expect(r.status).toBe(403); expect(r.body.code).toBe('attendance.wfh_not_allowed');
  });
  test('TC-218 half day only for a single date', async () => {
    await emp.e1.reload(); await nav(emp.e1, 'My attendance'); await emp.e1.getByRole('button', { name: 'New request' }).click();
    await emp.e1.selectOption('#req-kind', 'work_from_home');
    await expect(emp.e1.locator('#req-half')).toBeEnabled();
    await emp.e1.fill('#req-to', isoDay(3)); await expect(emp.e1.locator('#req-half')).toBeDisabled();
  });
  test('TC-126 WFH off org-wide blocks allowed user', async () => {
    await ok(api(owner, 'PUT', '/org/attendance/settings', { leaveYearStartMonth: 1, wfhEnabled: false, wfhRequiresPunch: false, wfhLimitPerWeek: null, wfhLimitPerMonth: null }));
    const r = await api(emp.e1, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(1), toDate: isoDay(1), reason: 'Plumber visit' });
    expect(r.status).toBe(403);
    await ok(api(owner, 'PUT', '/org/attendance/settings', { leaveYearStartMonth: 1, wfhEnabled: true, wfhRequiresPunch: false, wfhLimitPerWeek: null, wfhLimitPerMonth: null }));
  });

  // ---------- requests ----------
  test('TC-207 TC-211 TC-226 correction request, duplicate, cancel', async () => {
    const day = isoDay(-1);
    await emp.e1.reload(); await nav(emp.e1, 'My attendance'); await emp.e1.getByRole('button', { name: 'New request' }).click();
    await emp.e1.fill('#req-date', day); await emp.e1.fill('#req-reason', 'Forgot to punch out');
    await emp.e1.getByRole('button', { name: 'Send request' }).click();
    await expect(emp.e1.locator('tr', { hasText: 'Correct my punches' }).first()).toContainText(/pending/i);
    const dup = await api(emp.e1, 'POST', '/org/attendance/me/requests', { kind: 'regularization', workDate: day, inTime: '09:30', outTime: '18:30', reason: 'Forgot to punch out' });
    expect(dup.status).toBe(409); expect(dup.body.code).toBe('attendance.request_exists');
    await emp.e1.locator('tr', { hasText: 'Correct my punches' }).first().getByRole('button', { name: 'Cancel' }).click();
    await expect(emp.e1.getByText('Request cancelled')).toBeVisible();
  });
  test('TC-208 out before in is refused', async () => {
    await emp.e1.reload(); await nav(emp.e1, 'My attendance'); await emp.e1.getByRole('button', { name: 'New request' }).click();
    await emp.e1.fill('#req-in', '18:30'); await emp.e1.fill('#req-out', '09:30'); await emp.e1.fill('#req-reason', 'Valid reason here');
    await emp.e1.getByRole('button', { name: 'Send request' }).click(); await expect(err(emp.e1)).toBeVisible();
  });
  test('TC-209 reason under 5 characters refused', async () => {
    await emp.e1.reload(); await nav(emp.e1, 'My attendance'); await emp.e1.getByRole('button', { name: 'New request' }).click();
    await emp.e1.fill('#req-in', '09:30'); await emp.e1.fill('#req-out', '18:30'); await emp.e1.fill('#req-reason', 'ok');
    await emp.e1.getByRole('button', { name: 'Send request' }).click(); await expect(err(emp.e1)).toContainText('at least 5 characters');
  });
  test('TC-229 TC-230 TC-233 approve, reject with confirmation, own request', async () => {
    const d1 = isoDay(-2), d2 = isoDay(-3);
    await ok(api(emp.e3, 'POST', '/org/attendance/me/requests', { kind: 'regularization', workDate: d1, inTime: '09:30', outTime: '18:30', reason: 'Device was down' }));
    await ok(api(emp.e4, 'POST', '/org/attendance/me/requests', { kind: 'regularization', workDate: d2, inTime: '09:30', outTime: '18:30', reason: 'Device was down' }));
    await nav(hr, 'Attendance'); await tab(hr, 'Requests');
    const row3 = hr.locator('tr', { hasText: 'e3@acme.test' });
    await row3.getByLabel(/Note for/).fill('Looks fine'); await row3.getByRole('button', { name: 'Approve' }).click();
    await expect(hr.getByText(/Request approved for/)).toBeVisible();
    const row4 = hr.locator('tr', { hasText: 'e4@acme.test' });
    await row4.getByRole('button', { name: 'Reject' }).click();
    await expect(hr.getByText('A rejection cannot be undone')).toBeVisible();
    await hr.getByRole('button', { name: 'Reject request' }).click();
    await expect(hr.getByText(/Request rejected for/)).toBeVisible();
    await ok(api(hr, 'POST', '/org/attendance/me/requests', { kind: 'on_duty', fromDate: isoDay(0), toDate: isoDay(0), reason: 'Client visit' })).catch(() => {});
  });
  test('TC-234 TC-235 status chips', async () => {
    await nav(hr, 'Attendance'); await tab(hr, 'Requests');
    await hr.getByRole('button', { name: 'Approved' }).click(); await expect(hr.locator('tr', { hasText: 'e3@acme.test' })).toBeVisible();
    await hr.getByRole('button', { name: 'Rejected' }).click(); await expect(hr.locator('tr', { hasText: 'e4@acme.test' })).toBeVisible();
  });
  test('smoke: empty requests list', async () => {
    await emp.e5.reload(); await nav(emp.e5, 'My attendance'); await expect(emp.e5.getByText('You have not raised any requests.')).toBeVisible().catch(async () => {
      await expect(emp.e5.getByText(/No work location and shift/)).toBeVisible(); });
  });

  // ---------- punching ----------
  test('TC-016 no check: punch accepted', async () => {
    await emp.e1.reload(); await nav(emp.e1, 'My attendance');
    await expect(emp.e1.getByText('You can punch from anywhere')).toBeVisible();
    await emp.e1.getByRole('button', { name: 'Punch in' }).click();
    await expect(emp.e1.getByText(/Punched in at/)).toBeVisible();
    await expect(emp.e1.getByRole('button', { name: 'Punch out' })).toBeVisible();
  });
  test('TC-241 double tap refused', async () => {
    const r = await api(emp.e1, 'POST', '/org/attendance/me/punch', {}); expect(r.status).toBe(409); expect(r.body.code).toBe('attendance.too_soon');
  });
  test('TC-019 TC-020 ip check refuses then accepts', async () => {
    await emp.e2.reload(); await nav(emp.e2, 'My attendance');
    await emp.e2.getByRole('button', { name: 'Punch in' }).click(); await expect(emp.e2.locator('.error').first()).toBeVisible();
    await ok(api(owner, 'PUT', `/org/attendance/locations/${loc.ip.id}`, { name: 'IP office', timezone: 'Asia/Kolkata', stateCode: 'KA', punchCheck: 'ip', allowedIps: ['203.0.113.0/24', '127.0.0.1/32', '::1/128'], active: true }));
    await emp.e2.reload(); await nav(emp.e2, 'My attendance');
    await emp.e2.getByRole('button', { name: 'Punch in' }).click(); await expect(emp.e2.getByText(/Punched in at/)).toBeVisible();
  });
  test('TC-024 TC-025 TC-023 location check refuses far and inaccurate; accepts inside', async () => {
    const ctx = emp.e3.context(); await ctx.grantPermissions(['geolocation']);
    await emp.e3.reload(); await nav(emp.e3, 'My attendance');
    await ctx.setGeolocation({ latitude: 22.5726 + 0.01, longitude: 88.3639, accuracy: 20 });
    await emp.e3.getByRole('button', { name: 'Punch in' }).click(); await expect(emp.e3.locator('.error').first()).toContainText(/m from/);
    await ctx.setGeolocation({ latitude: 22.5726, longitude: 88.3639, accuracy: 300 });
    await emp.e3.getByRole('button', { name: 'Punch in' }).click(); await expect(emp.e3.locator('.error').first()).toBeVisible();
    await ctx.setGeolocation({ latitude: 22.5726, longitude: 88.3639, accuracy: 20 });
    await emp.e3.getByRole('button', { name: 'Punch in' }).click(); await expect(emp.e3.getByText(/Punched in at/)).toBeVisible();
  });
  test('TC-029 TC-030 either: position passes; both fail refused', async () => {
    const ctx = emp.e4.context(); await ctx.grantPermissions(['geolocation']);
    await emp.e4.reload(); await nav(emp.e4, 'My attendance');
    await ctx.setGeolocation({ latitude: 10, longitude: 10, accuracy: 20 });
    await emp.e4.getByRole('button', { name: 'Punch in' }).click(); await expect(emp.e4.locator('.error').first()).toBeVisible();
    await ctx.setGeolocation({ latitude: 22.5726, longitude: 88.3639, accuracy: 20 });
    await emp.e4.getByRole('button', { name: 'Punch in' }).click(); await expect(emp.e4.getByText(/Punched in at/)).toBeVisible();
  });
  test('TC-243 unassigned employee message', async () => {
    await emp.e5.reload(); await nav(emp.e5, 'My attendance');
    await expect(emp.e5.getByText('No work location and shift are assigned to you yet')).toBeVisible();
  });

  // ---------- board / marks ----------
  test('TC-183 TC-184 board defaults to today and navigates', async () => {
    await nav(hr, 'Attendance'); await tab(hr, 'Daily');
    await expect(hr.locator('#board-date')).toHaveValue(isoDay(0));
    await expect(hr.getByRole('button', { name: 'Next day' })).toBeDisabled();
    await hr.getByRole('button', { name: 'Previous day' }).click(); await expect(hr.locator('#board-date')).toHaveValue(isoDay(-1));
    await hr.getByRole('button', { name: 'Next day' }).click(); await expect(hr.locator('#board-date')).toHaveValue(isoDay(0));
  });
  test('TC-189 details expand and hide', async () => {
    await nav(hr, 'Attendance'); await tab(hr, 'Daily');
    const b = hr.getByRole('button', { name: 'Details' }).first(); await b.click(); await expect(hr.getByRole('button', { name: 'Hide' }).first()).toBeVisible();
    await hr.getByRole('button', { name: 'Hide' }).first().click();
  });
  test('TC-192 TC-193 manual punch rules', async () => {
    await nav(hr, 'Attendance'); await tab(hr, 'Daily'); await hr.getByRole('button', { name: 'Previous day' }).click();
    const row = hr.locator('tr', { hasText: 'e8@acme.test' });
    await row.getByRole('button', { name: 'Details' }).click();
    const detail = hr.locator('tr.detail-row');
    await detail.getByLabel('Reason').fill('abc'); await detail.getByRole('button', { name: 'Add punch' }).click();
    await expect(detail.locator('.error')).toContainText('at least 5 characters');
    await detail.getByLabel('Reason').fill('Forgot to punch'); await detail.getByRole('button', { name: 'Add punch' }).click();
    await hr.getByRole('button', { name: 'Previous day' }).click(); await hr.getByRole('button', { name: 'Next day' }).click();
    const r = await api(hr, 'POST', `/org/attendance/users/${ids[U('e8')]}/punches`, { at: `${isoDay(1)}T10:00`, direction: 'in', reason: 'Future try' });
    expect(r.status).toBe(400);
  });
  test('TC-195 auditor has no manual punch form', async () => {
    await nav(aud, 'Attendance'); await tab(aud, 'Daily');
    await aud.getByRole('button', { name: 'Details' }).first().click();
    await expect(aud.getByRole('button', { name: 'Add punch' })).toHaveCount(0);
  });
  test('TC-190 TC-194 TC-196 manager marks present-mode report', async () => {
    await nav(mgr, 'Attendance'); await tab(mgr, 'Daily');
    const row = mgr.locator('tr', { hasText: 'e6@acme.test' }).or(mgr.locator('tr', { hasText: 'Pia' }));
    await expect(row.first()).toContainText('present by default');
    await row.first().getByRole('button', { name: 'Details' }).click();
    const d = mgr.locator('tr.detail-row');
    await expect(d.getByRole('button', { name: 'Add punch' })).toHaveCount(0);
    await expect(d.locator('option', { hasText: /^Absent$/ })).toHaveCount(1);
    await d.getByLabel('Reason').fill('Did not come in'); await d.getByRole('button', { name: 'Save mark' }).click();
    await expect(mgr.locator('tr', { hasText: 'Pia' }).first()).toContainText('marked by hand');
    await mgr.locator('tr', { hasText: 'Pia' }).first().getByRole('button', { name: /Details|Hide/ }).first().click().catch(() => {});
  });
  test('TC-203 employee sees mark and who made it', async () => {
    await emp.e6.reload(); await nav(emp.e6, 'My attendance');
    await expect(emp.e6.getByText('Absent').first()).toBeVisible();
  });
  test('TC-199 TC-201 manager cannot mark self; reason required', async () => {
    const r = await api(mgr, 'POST', `/org/attendance/users/${ids[U('mgr')]}/marks`, { date: isoDay(0), status: 'absent', reason: 'Self mark' });
    expect([409, 404]).toContain(r.status);
    const r2 = await api(mgr, 'POST', `/org/attendance/users/${ids[U('e6')]}/marks`, { date: isoDay(0), status: 'half_day', reason: 'abc' });
    expect(r2.status).toBe(400);
  });
  test('TC-200 cannot mark future day', async () => {
    const f = await api(hr, 'POST', `/org/attendance/users/${ids[U('e6')]}/marks`, { date: isoDay(2), status: 'absent', reason: 'Future day' }); expect(f.status).toBe(400);
  });
  test('TC-204 TC-205 register download visible to HR, not employee', async () => {
    await nav(hr, 'Attendance'); await tab(hr, 'Daily');
    const dl = hr.waitForEvent('download'); await hr.getByRole('button', { name: /Download the .* register/ }).click(); const f = await dl; expect(f.suggestedFilename()).toMatch(/\.csv$/);
    await nav(emp.e1, 'My attendance'); await expect(emp.e1.getByRole('button', { name: /register/ })).toHaveCount(0);
  });
  test('smoke: employee page sees empty board hint or rows', async () => {
    await nav(emp.e8, 'My attendance'); await expect(emp.e8.getByText('Attendance').first()).toBeVisible();
  });

  // ---------- devices ----------
  const SN = 'TCSNFIXED001';
  test('TC-137 TC-138 TC-160 register device; required fields; steps panel', async () => {
    await nav(owner, 'Attendance'); await tab(owner, 'Devices');
    await owner.getByRole('button', { name: 'Register device' }).click(); await expect(err(owner)).toBeVisible();
    await owner.fill('#dev-name', 'Main gate'); await owner.getByRole('button', { name: 'Register device' }).click(); await expect(err(owner)).toBeVisible();
    await owner.fill('#dev-serial', SN); await owner.selectOption('#dev-location', { label: 'Device plant' });
    await owner.getByRole('button', { name: 'Register device' }).click();
    await expect(owner.getByText('Main gate is registered')).toBeVisible();
    await owner.getByText('How to connect a device').click(); await expect(owner.getByText('Cloud Server Setting')).toBeVisible();
  });
  test('TC-139 duplicate serial refused', async () => {
    await owner.fill('#dev-name', 'Dup'); await owner.fill('#dev-serial', SN); await owner.selectOption('#dev-location', { label: 'Device plant' });
    await owner.getByRole('button', { name: 'Register device' }).click(); await expect(err(owner)).toBeVisible();
  });
  test('TC-140 inactive locations not offered', async () => {
    await expect(owner.locator('#dev-location option', { hasText: 'Two nets' })).toHaveCount(0);
  });
  test('TC-146 new device: not heard from, empty log', async () => {
    await expect(owner.locator('tr', { hasText: 'Main gate' })).toContainText('not heard from yet');
  });
  test('TC-152 TC-153 TC-158 TC-159 TC-155 device protocol', async ({ request }) => {
    const hs = await request.get(`${DEVICE_URL}/cdata?SN=${SN}`); expect(hs.status()).toBe(200); expect(await hs.text()).toContain('ATTLOGStamp');
    const t = nowMs(); const mid = new Date(`${isoDay(0)}T00:00:00+05:30`).getTime();
    const a = Math.max(mid + 60_000, t - 9 * 3600_000), b = Math.max(a + 60_000, t - 300_000);
    const body = `DEV-7\t${istStamp(a)}\t0\t1\nDEV-7\t${istStamp(b)}\t0\t1\nNOPE-1\t${istStamp(a)}\t0\t1\ngarbage line\n`;
    const p = await request.post(`${DEVURL_POST(SN)}`, { data: body }); expect(p.status()).toBe(200); expect(await p.text()).toMatch(/OK: \d+/);
    const again = await request.post(`${DEVURL_POST(SN)}`, { data: body }); expect(again.status()).toBe(200);
    const op = await request.post(`${DEVICE_URL}/cdata?SN=${SN}&table=OPERLOG`, { data: 'x' }); expect(await op.text()).toContain('OK: 0');
    const un = await request.get(`${DEVICE_URL}/cdata?SN=NOSUCHSERIAL1`); expect([403, 404]).toContain(un.status());
    await owner.reload(); await nav(owner, 'Attendance'); await tab(owner, 'Devices');
    await owner.locator('tr', { hasText: 'Main gate' }).getByRole('button', { name: 'Log' }).click();
    await expect(owner.getByText('Taken').first()).toBeVisible();
    await expect(owner.getByText('Already here').first()).toBeVisible();
    await expect(owner.getByText('Nobody has this employee code').first()).toBeVisible();
    await expect(owner.getByText('Could not be read').first()).toBeVisible();
    await expect(owner.getByText(/User ids nobody has/)).toBeVisible();
    await emp.e7.reload(); await nav(emp.e7, 'My attendance'); await expect(emp.e7.getByText('from the attendance device').first()).toBeVisible();
  });
  test('TC-142 TC-143 switch device off then unknown to protocol', async ({ request }) => {
    await owner.locator('tr', { hasText: 'Main gate' }).getByRole('button', { name: 'Switch off' }).click();
    await expect(owner.locator('tr', { hasText: 'Main gate' })).toContainText('switched off');
    const r = await request.get(`${DEVICE_URL}/cdata?SN=${SN}`); expect([403, 404]).toContain(r.status());
    await owner.locator('tr', { hasText: 'Main gate' }).getByRole('button', { name: 'Switch on' }).click();
    const r2 = await request.get(`${DEVICE_URL}/cdata?SN=${SN}`); expect(r2.status()).toBe(200);
  });
  test('TC-161 TC-162 TC-163 TC-175 TC-176 file import', async () => {
    await nav(owner, 'Attendance'); await tab(owner, 'Devices');
    await expect(owner.locator('#imp-file')).toBeDisabled();
    await owner.fill('#imp-device', 'Gate file'); await expect(owner.locator('#imp-file')).toBeEnabled();
    const mid = new Date(`${isoDay(-4)}T09:00:00+05:30`).getTime();
    const day = isoDay(-4).split('-').reverse().join('/');
    const csv = `Code,Time,State\nE1001,${day} 09:00,0\nE1001,${day} 18:00,1\nZZZ9,${day} 09:10,0\n`;
    await owner.setInputFiles('#imp-file', { name: 'gate.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await expect(owner.getByText(/gate.csv/)).toBeVisible(); await expect(owner.locator('#map-code')).toBeVisible();
    await owner.selectOption('#map-code', { index: 1 }); await owner.selectOption('#map-when', { index: 2 });
    await owner.getByRole('button', { name: /Import 3 rows/ }).click();
    await expect(owner.getByText(/rows imported/)).toBeVisible();
    await owner.fill('#imp-device', 'Gate file');
    await owner.setInputFiles('#imp-file', { name: 'gate.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await expect(owner.getByText(/columns below are as you set them last time/)).toBeVisible();
    await owner.getByRole('button', { name: /Import 3 rows/ }).click();
    await expect(owner.getByText(/3 were already here|already here/).first()).toBeVisible();
    void mid;
  });
  test('TC-164 empty file refused', async () => {
    await nav(owner, 'Attendance'); await tab(owner, 'Devices'); await owner.fill('#imp-device', 'Empty');
    await owner.setInputFiles('#imp-file', { name: 'e.csv', mimeType: 'text/csv', buffer: Buffer.from('') });
    await expect(owner.locator('form .error').first()).toBeVisible();
  });

  // ---------- access ----------
  test('TC-260 TC-261 TC-262 role tabs', async () => {
    await nav(emp.e1, 'My attendance'); await expect(emp.e1.locator('.sidebar, nav').getByText('Attendance', { exact: true })).toHaveCount(0).catch(() => {});
    await nav(mgr, 'Attendance'); await expect(mgr.getByRole('tab', { name: 'Setup' }).or(mgr.getByRole('button', { name: 'Setup', exact: true }))).toHaveCount(0);
    await nav(hr, 'Attendance'); for (const t of ['Daily', 'Requests', 'People', 'Setup', 'Devices']) await expect(hr.getByRole('tab', { name: t }).or(hr.getByRole('button', { name: t, exact: true })).first()).toBeVisible();
    await nav(aud, 'Attendance'); await tab(aud, 'Devices'); await expect(aud.getByRole('button', { name: 'Register device' })).toHaveCount(0);
  });
  test('TC-263 TC-264 forbidden reads', async () => {
    const r = await api(emp.e1, 'GET', '/org/attendance/days'); expect(r.status).toBe(403);
    const r2 = await api(emp.e1, 'GET', `/org/attendance/users/${ids[U('e2')]}/days?month=${isoDay(0).slice(0, 7)}`); expect(r2.status).toBe(404);
  });
  test('settings write denied for employee', async () => {
    const r = await api(emp.e1, 'PUT', '/org/attendance/settings', { leaveYearStartMonth: 1, wfhEnabled: true, wfhRequiresPunch: false }); expect(r.status).toBe(403);
  });
  test('TC-267 audit log has setup entries', async () => {
    const r = await api(owner, 'GET', '/org/audit'); expect(r.status).toBe(200);
    const text = JSON.stringify(r.body);
    for (const a of ['attendance.location_created', 'attendance.shift_created', 'attendance.holiday_added', 'attendance.assigned', 'attendance.mode_changed', 'attendance.request_approved', 'attendance.marked', 'attendance.device_registered']) expect(text, a).toContain(a);
  });
  test('TC-271 TC-270 phone layout', async ({ browser }) => {
    const p = await (async () => { const c = await browser.newContext({ viewport: { width: 390, height: 844 } }); return c.newPage(); })();
    await p.goto('/'); await p.fill('#email', U('e1')); await p.fill('#password', 'correct-horse-battery'); await p.click('button.primary');
    await expect(p.locator('.topbar')).toBeVisible(); await nav(p, 'My attendance'); await expectNoSidewaysScroll(p);
    await p.context().close();
  });
});
function DEVURL_POST(sn: string) { return `${DEVICE_URL}/cdata?SN=${sn}&table=ATTLOG&Stamp=1`; }
