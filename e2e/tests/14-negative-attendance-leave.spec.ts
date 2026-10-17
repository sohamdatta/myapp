import { test, expect, type Page } from '@playwright/test';
import { OWNER, HR, PASSWORD, signIn, newPage, nav, tab, isoDay, api, expectRefused, errorOn, expectNoSidewaysScroll, expectNoConsoleErrors } from './helpers';

// Negative cases for attendance, leave and work from home: what each form refuses, the limits,
// and the rules no screen offers to break (deciding your own request, punching twice, the past).

const MEERA = 'meera.iyer@mail.test';

/** The next date, from tomorrow on, that falls on the given weekday (0 = Sunday). */
function next(weekday: number, after = 1): string {
  for (let i = after; i < after + 8; i++) {
    const d = isoDay(i);
    if (new Date(`${d}T00:00:00Z`).getUTCDay() === weekday) return d;
  }
  throw new Error('no such day');
}

test.describe.serial('negative: attendance, leave and work from home', () => {
  let owner: Page;
  let meera: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await owner?.context().close();
    await meera?.context().close();
  });

  test('a work location refuses a missing name, half a position, a bad radius, state or network', async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    await nav(owner, 'Attendance');
    await tab(owner, 'Setup');
    const save = async (expected: string | RegExp) => {
      await owner.click('text=Save location');
      await expect(errorOn(owner)).toContainText(expected);
    };
    await owner.getByRole('button', { name: 'Add a work location' }).click();
    await save('Give it a name');
    await owner.fill('#loc-name', 'x'.repeat(81));
    await save('at most 80 characters');
    await owner.fill('#loc-name', 'Pune office');
    await owner.fill('#loc-tz', 'Mars/Olympus');
    await save('Unknown time zone');
    await owner.fill('#loc-tz', 'Asia/Kolkata');
    await owner.selectOption('#loc-check', 'location');
    await save('Set the position and radius');
    await owner.fill('#loc-lat', '18.52');
    await save('together');
    await owner.fill('#loc-lon', '73.85');
    await owner.fill('#loc-radius', '10');
    await save('between 25 and 5000');
    await owner.fill('#loc-radius', '9000');
    await save('between 25 and 5000');
    await owner.fill('#loc-lat', '');
    await owner.fill('#loc-lon', '');
    await owner.fill('#loc-radius', '');
    await owner.selectOption('#loc-check', 'either');
    await save('or set the punch check to none');
    await owner.selectOption('#loc-check', 'ip');
    await owner.fill('#loc-ips', 'not-a-network');
    await save(/network address|not valid/i);
    await owner.fill('#loc-ips', '10.0.0.0/33');
    await save(/network address|not valid/i);
    await owner.fill('#loc-ips', '');
    await owner.selectOption('#loc-check', 'none');
    // Payroll follows the state: only an Indian state or union territory code is taken.
    await owner.fill('#loc-state', 'Maharashtra');
    await save(/state/i);
    await owner.fill('#loc-state', 'ZZ');
    await save(/state/i);
    await owner.getByRole('button', { name: 'Cancel' }).first().click();
    await expect(owner.locator('td', { hasText: 'Pune office' })).toHaveCount(0);
    // A duplicate name is refused.
    await owner.getByRole('button', { name: 'Add a work location' }).click();
    await owner.fill('#loc-name', 'Bengaluru office');
    await owner.click('text=Save location');
    await expect(errorOn(owner)).toContainText(/already|exists/i);
    await owner.getByRole('button', { name: 'Cancel' }).first().click();
  });

  test('a shift refuses impossible hours and grace; a holiday needs a date and a name', async () => {
    await owner.getByRole('button', { name: 'Add a shift' }).click();
    const save = async (expected: string | RegExp) => {
      await owner.click('text=Save shift');
      await expect(errorOn(owner)).toContainText(expected);
    };
    await save('Give it a name');
    await owner.fill('#sh-name', 'Night');
    await owner.fill('#sh-grace', '121');
    await save('Grace must be between 0 and 120');
    await owner.fill('#sh-grace', '-5');
    await save('Grace must be between 0 and 120');
    await owner.fill('#sh-grace', '10');
    await owner.fill('#sh-full', '25');
    await save('1 to 24 hours');
    await owner.fill('#sh-full', '8');
    await owner.fill('#sh-half', '8');
    await save('shorter than a full day');
    await owner.fill('#sh-half', '0.25');
    await save('at least 30 minutes');
    await owner.fill('#sh-half', 'four');
    await save(/hours|number|valid/i);
    await owner.getByRole('button', { name: 'Cancel' }).first().click();

    await owner.fill('#hol-name', 'No date day');
    await owner.click('text=Add holiday');
    await expect(errorOn(owner)).toContainText('Choose the date of the holiday');
    await owner.fill('#hol-date', isoDay(40));
    await owner.fill('#hol-name', '');
    await owner.click('text=Add holiday');
    await expect(errorOn(owner)).toContainText(/name/i);
    await owner.fill('#hol-name', 'Founders day again');
    await owner.fill('#hol-date', isoDay(30)); // Founders day is already there
    await owner.click('text=Add holiday');
    await expect(errorOn(owner)).toContainText(/already/i);
  });

  test('work-from-home limits and assignments refuse out-of-range values', async () => {
    await owner.reload();
    await nav(owner, 'Attendance');
    await tab(owner, 'Setup');
    for (const [field, value] of [['#wfh-week', '8'], ['#wfh-week', '0'], ['#wfh-month', '32'], ['#wfh-week', '-1'], ['#wfh-week', '2.5']]) {
      await owner.fill('#wfh-week', '2');
      await owner.fill('#wfh-month', '');
      await owner.fill(field, value);
      await owner.click('text=Save work-from-home rules');
      await expect(errorOn(owner)).toContainText(/weekly limit is 1 to 7|monthly limit|number|could not be read/i);
    }
    await owner.fill('#wfh-week', '2');
    await owner.fill('#wfh-month', '');
    await owner.click('text=Save work-from-home rules');

    await tab(owner, 'People');
    await expect(owner.getByRole('button', { name: /^Assign to 0 selected/ })).toBeDisabled();
    await owner.getByLabel('Select Meera Iyer').check();
    await owner.selectOption('#as-location', '');
    await owner.click('text=Assign to 1 selected');
    await expect(errorOn(owner)).toContainText('Choose a work location and a shift');
    await owner.selectOption('#as-location', { label: 'Bengaluru office' });
    await owner.selectOption('#as-shift', { index: 1 });
    for (const day of ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']) {
      await owner.getByLabel(day).first().check();
    }
    await owner.click('text=Assign to 1 selected');
    await expect(errorOn(owner)).toContainText('at most six');
    await owner.fill('#as-from', '');
    await owner.getByLabel('Monday').first().uncheck();
    await owner.click('text=Assign to 1 selected');
    await expect(errorOn(owner)).toContainText('Choose the date the assignment starts');
    await owner.reload();
    await expect(owner.locator('.sidebar')).toBeVisible();

    const code = owner.getByLabel('Employee code for Meera Iyer');
    if (await code.count()) {
      await code.fill('X'.repeat(41));
      await code.press('Enter');
      await expect(errorOn(owner)).toContainText('at most 40 characters');
    }
  });

  test('punching twice in a minute, and requests with bad dates or reasons, are refused', async ({ browser }) => {
    meera = await signIn(browser, MEERA);
    await nav(meera, 'My attendance');
    const punch = meera.locator('.punch-button');
    if (await punch.isVisible()) {
      await punch.click();
      await expect(meera.locator('.punch-button')).toBeEnabled();
      await meera.locator('.punch-button').click();
      await expect(errorOn(meera)).toContainText('Wait a minute before punching again');
      await meera.reload();
      await expect(meera.locator('.sidebar')).toBeVisible();
      await nav(meera, 'My attendance');
    }

    const request = async (kind: string, from: string, to: string | null, reason: string, expected: string | RegExp) => {
      await meera.click('text=New request');
      await meera.selectOption('#req-kind', kind);
      await meera.fill('#req-date', from);
      if (to !== null && (await meera.locator('#req-to').isVisible())) await meera.fill('#req-to', to);
      await meera.fill('#req-reason', reason);
      await meera.click('text=Send request');
      await expect(meera.locator('form .error')).toContainText(expected);
      await meera.getByRole('button', { name: 'Cancel', exact: true }).first().click();
    };
    const mon = next(1);
    await request('work_from_home', mon, mon, 'Hm', 'at least 5 characters');
    await request('work_from_home', mon, isoDay(-30), 'Waiting for a delivery', 'before the first');
    await request('work_from_home', isoDay(1), isoDay(40), 'Long stretch at home', /at most 31 days|limit/);
    // Sent anyway: a correction over two days, a half day over two days, a punch in the future.
    await expectRefused(meera, 'POST', '/org/attendance/me/requests', [400],
      { kind: 'regularization', fromDate: isoDay(-3), toDate: isoDay(-2), reason: 'Forgot both days', inTime: '09:30', outTime: '18:30' });
    await expectRefused(meera, 'POST', '/org/attendance/me/requests', [400],
      { kind: 'work_from_home', fromDate: mon, toDate: next(2), half: 'first', reason: 'Half of two days' });
    await expectRefused(meera, 'POST', '/org/attendance/me/requests', [400], { kind: 'holiday', fromDate: mon, toDate: mon, reason: 'A kind that is not one' });
  });

  test('nobody decides their own request, or enters a punch in the future', async ({ browser }) => {
    // The Org Admin asks to work from home, then tries to approve it herself.
    await nav(owner, 'My attendance');
    await owner.click('text=New request');
    await owner.selectOption('#req-kind', 'work_from_home');
    const day = next(3);
    await owner.fill('#req-date', day);
    if (await owner.locator('#req-to').isVisible()) await owner.fill('#req-to', day);
    await owner.fill('#req-reason', 'Electrician at home');
    await owner.click('text=Send request');
    await expect(owner.getByText('Electrician at home')).toBeVisible();
    const mine = (await api(owner, 'GET', '/org/attendance/me/requests')).body;
    const req = (Array.isArray(mine) ? mine : mine.requests).find((r: any) => r.reason === 'Electrician at home');
    await nav(owner, 'Attendance');
    await tab(owner, 'Requests');
    const row = owner.locator('tr, li', { hasText: 'Electrician at home' }).first();
    await expect(row.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0);
    await expectRefused(owner, 'POST', `/org/attendance/requests/${req.id}/approve`, [403, 409], { note: 'Fine by me' });

    // A manual punch in the future, and one without a reason.
    const users = (await api(owner, 'GET', '/org/users')).body;
    const hr = users.find((u: any) => u.email === HR);
    await expectRefused(owner, 'POST', `/org/attendance/users/${hr.id}/punches`, [400],
      { direction: 'in', at: `${isoDay(2)}T09:30`, reason: 'Came in early' });
    await expectRefused(owner, 'POST', `/org/attendance/users/${hr.id}/punches`, [400],
      { direction: 'in', at: `${isoDay(-1)}T09:30`, reason: 'x' });
    await expectRefused(owner, 'POST', `/org/attendance/users/${hr.id}/punches`, [400],
      { direction: 'in', at: 'yesterday morning', reason: 'Turnstile broken' });
    await expectRefused(owner, 'GET', '/org/attendance/register?month=October', [400]);
    await expectRefused(owner, 'GET', '/org/attendance/days?date=17-10-2026', [400]);
  });

  test('a device file refuses no device name, an empty file and a bad date format', async () => {
    await tab(owner, 'Device import');
    await owner.setInputFiles('#imp-file', { name: 'empty.csv', mimeType: 'text/csv', buffer: Buffer.from('') });
    await expect(errorOn(owner)).toContainText(/empty|no rows/i);
    await owner.setInputFiles('#imp-file', { name: 'punches.csv', mimeType: 'text/csv', buffer: Buffer.from('code,when\nE001,yesterday\nNOPE,2026-10-01 09:00\n') });
    await owner.fill('#imp-device', '');
    await owner.click('text=Import 2 rows');
    await expect(errorOn(owner)).toContainText('Name the device');
    await owner.fill('#imp-device', 'Side gate');
    await owner.selectOption('#map-when', { index: 0 });
    await owner.click('text=Import 2 rows');
    await expect(errorOn(owner)).toContainText('Choose the employee code column and the date and time column');
    await owner.selectOption('#map-when', 'when');
    await owner.click('text=Import 2 rows');
    // Neither row is taken: one date cannot be read, the other code matches nobody.
    await expect(owner.getByText('0 of 2 rows imported')).toBeVisible();
    await expectRefused(owner, 'POST', '/org/attendance/imports/preview', [400],
      { device: 'Gate', csv: 'code,when\nE001,2026-10-01 09:00\n', codeColumn: 'code', timeColumn: 'when', format: 'not a %format', zone: 'Asia/Kolkata' });
  });

  test('a leave type refuses no name, an impossible quota and a bad carry-forward', async () => {
    await nav(owner, 'Leave');
    await tab(owner, 'Setup');
    const add = async (fill: () => Promise<void>, expected: string | RegExp) => {
      await owner.click('text=Add leave type');
      await fill();
      await owner.getByRole('button', { name: 'Add leave type', exact: true }).last().click();
      await expect(errorOn(owner)).toContainText(expected);
      await owner.getByRole('button', { name: 'Cancel' }).first().click();
    };
    await add(async () => {}, 'name of up to 60 characters');
    await add(async () => owner.fill('#lt-name', 'L'.repeat(61)), 'name of up to 60 characters');
    await add(async () => {
      await owner.fill('#lt-name', 'Study leave');
      await owner.selectOption('#lt-accrual', 'yearly');
      await owner.fill('#lt-quota', '400');
    }, 'between 0 and 366');
    await add(async () => {
      await owner.fill('#lt-name', 'Study leave');
      await owner.selectOption('#lt-accrual', 'yearly');
      await owner.fill('#lt-quota', '2.3');
    }, 'whole or half days');
    await add(async () => {
      await owner.fill('#lt-name', 'Study leave');
      await owner.selectOption('#lt-accrual', 'yearly');
      await owner.fill('#lt-quota', '');
    }, 'number of days a year');
    await add(async () => {
      await owner.fill('#lt-name', 'Study leave');
      await owner.selectOption('#lt-accrual', 'yearly');
      await owner.fill('#lt-quota', '6');
      await owner.fill('#lt-cap', '-1');
    }, 'zero or more');
    await add(async () => {
      await owner.fill('#lt-name', 'Casual leave');
      if (await owner.locator('#lt-quota').isVisible()) await owner.fill('#lt-quota', '12');
    }, /already/i);
    await expect(owner.getByText('Study leave')).toHaveCount(0);
  });

  test('applying for leave refuses reversed dates, a half day over two days, days off and the far past', async () => {
    await nav(meera, 'My leave');
    const apply = async (from: string, to: string, reason: string, expected: string | RegExp, half?: string) => {
      await meera.click('text=Apply for leave');
      await meera.selectOption('#apply-type', { index: 1 });
      await meera.fill('#apply-from', from);
      await meera.fill('#apply-to', to);
      if (half) await meera.selectOption('#apply-half', half);
      await meera.fill('#apply-reason', reason);
      await meera.click('text=Send for approval');
      await expect(errorOn(meera)).toContainText(expected);
      await meera.getByRole('button', { name: 'Cancel', exact: true }).first().click();
    };
    const tue = next(2);
    await apply(tue, isoDay(-5), 'Family function', /before the first/);
    await apply(next(0), next(0), 'A Sunday off', /days off/); // Sunday is her weekly off
    await apply(isoDay(-200), isoDay(-199), 'Forgot to apply', /last|year|past/i);
    await apply(isoDay(1), isoDay(500), 'A very long break', /at most a year|three months|leave year|next 365/i);
    await expectRefused(meera, 'POST', '/org/leave/me/requests', [400], {
      leaveTypeId: (await api(meera, 'GET', '/org/leave/types')).body[0].id, fromDate: tue, toDate: next(3), half: 'first', reason: 'Half of two days',
    });
    await expectRefused(meera, 'POST', '/org/leave/me/requests', [400, 404], {
      leaveTypeId: '00000000-0000-0000-0000-000000000000', fromDate: tue, toDate: tue, reason: 'A type that does not exist',
    });
  });

  test('leave beyond the balance, overlapping leave and deciding your own are refused', async () => {
    const types = (await api(meera, 'GET', '/org/leave/me/balances')).body;
    const list = Array.isArray(types) ? types : types.balances || [];
    const limited = list.find((b: any) => b.shortfall === 'refuse' || b.onShortfall === 'refuse');
    if (limited) {
      const r = await api(meera, 'POST', '/org/leave/me/requests', {
        leaveTypeId: limited.leaveTypeId || limited.id, fromDate: isoDay(1), toDate: isoDay(60), reason: 'More than I have',
      });
      expect(r.status, JSON.stringify(r.body)).toBeGreaterThanOrEqual(400);
    }

    // Two requests over the same day: the second is refused.
    const tue = next(2, 3);
    const type = (await api(meera, 'GET', '/org/leave/types')).body.find((t: any) => /loss of pay/i.test(t.name)) ||
      (await api(meera, 'GET', '/org/leave/types')).body[0];
    const first = await api(meera, 'POST', '/org/leave/me/requests', { leaveTypeId: type.id, fromDate: tue, toDate: tue, reason: 'Bank work in town' });
    expect(first.status, JSON.stringify(first.body)).toBe(200);
    await expectRefused(meera, 'POST', '/org/leave/me/requests', [400, 409], { leaveTypeId: type.id, fromDate: tue, toDate: tue, reason: 'Same day again' });
    // And no work from home on a day of leave.
    await expectRefused(meera, 'POST', '/org/attendance/me/requests', [400, 409], { kind: 'work_from_home', fromDate: tue, toDate: tue, reason: 'At home that day' });

    // The Org Admin applies, then tries to approve her own.
    const ownerType = (await api(owner, 'GET', '/org/leave/types')).body.find((t: any) => t.id === type.id) || type;
    const mine = await api(owner, 'POST', '/org/leave/me/requests', { leaveTypeId: ownerType.id, fromDate: next(4, 3), toDate: next(4, 3), reason: 'School meeting' });
    expect(mine.status, JSON.stringify(mine.body)).toBe(200);
    await nav(owner, 'Leave');
    await tab(owner, 'Requests');
    await expect(owner.getByText('Your own request: someone else must decide it').first()).toBeVisible();
    await expectRefused(owner, 'POST', `/org/leave/requests/${mine.body.id}/approve`, [403, 409], { note: 'Approved' });
    // Meera cannot approve anyone's leave.
    await expectRefused(meera, 'POST', `/org/leave/requests/${mine.body.id}/approve`, [403, 404], { note: 'Sure' });
    await api(owner, 'POST', `/org/leave/me/requests/${mine.body.id}/cancel`, {});
    await api(meera, 'POST', `/org/leave/me/requests/${first.body.id}/cancel`, {});
  });

  test('balances: adjustments need days and a reason, and nobody adjusts or records their own', async () => {
    await tab(owner, 'Balances');
    const row = owner.locator('tr', { hasText: HR }).first();
    await row.getByText('Adjust').click();
    await owner.selectOption('#adj-type', { index: 1 });
    await owner.fill('#adj-days', '0');
    await owner.fill('#adj-reason', 'Correction');
    await owner.click('text=Save adjustment');
    await expect(errorOn(owner)).toContainText('number of days');
    await owner.fill('#adj-days', '400');
    await owner.click('text=Save adjustment');
    await expect(errorOn(owner)).toContainText('number of days');
    await owner.fill('#adj-days', '1');
    await owner.fill('#adj-reason', '');
    await owner.click('text=Save adjustment');
    await expect(errorOn(owner)).toContainText(/reason/i);
    await owner.reload();
    await expect(owner.locator('.sidebar')).toBeVisible();

    await nav(owner, 'Leave');
    await tab(owner, 'Balances');
    const own = owner.locator('tr', { hasText: OWNER }).first();
    await expect(own.getByText('Adjust')).toHaveCount(0);
    const users = (await api(owner, 'GET', '/org/users')).body;
    const me = users.find((u: any) => u.email === OWNER);
    const type = (await api(owner, 'GET', '/org/leave/types')).body[0];
    await expectRefused(owner, 'POST', `/org/leave/users/${me.id}/adjustments`, [403, 409], { leaveTypeId: type.id, days: 10, reason: 'A gift to myself' });
    await expectRefused(owner, 'POST', `/org/leave/users/${me.id}/requests`, [403, 409], { leaveTypeId: type.id, fromDate: isoDay(5), toDate: isoDay(5), reason: 'Recording my own' });
    const hr = users.find((u: any) => u.email === HR);
    await expectRefused(owner, 'POST', `/org/leave/users/${hr.id}/adjustments`, [400], { leaveTypeId: type.id, days: 1, reason: 'Dated ahead', onDate: isoDay(10) });
    await expectRefused(owner, 'POST', `/org/leave/users/${hr.id}/requests`, [400], { leaveTypeId: type.id, fromDate: isoDay(400), toDate: isoDay(400), reason: 'Far ahead' });
  });

  test('the leave year cannot change once closed, and a year cannot be closed twice', async () => {
    await tab(owner, 'Setup');
    const select = owner.locator('#leave-year-start');
    if (await select.isEnabled()) {
      const current = await select.inputValue();
      await select.selectOption(current === '1' ? '4' : '1');
      await expect(errorOn(owner)).toContainText('cannot be changed once a year has been closed');
    }
    await expectRefused(owner, 'POST', '/org/leave/year-end', [400, 409], { year: new Date().getFullYear() - 1 });
    await expectRefused(owner, 'POST', '/org/leave/year-end', [400, 409], { year: new Date().getFullYear() + 1 });
  });

  test('on a phone, refusals are shown in full without sideways scrolling', async ({ browser }) => {
    const page = await newPage(browser, 390);
    await page.goto('/');
    await page.fill('#email', MEERA);
    await page.fill('#password', 'wrong-password-on-a-phone');
    await page.click('button.primary');
    await expect(errorOn(page)).toHaveText('Incorrect email or password');
    await expectNoSidewaysScroll(page);
    await page.fill('#password', PASSWORD);
    await page.click('button.primary');
    await expect(page.locator('.sidebar')).toBeVisible();
    await nav(page, 'My leave');
    await page.click('text=Apply for leave');
    await page.selectOption('#apply-type', { index: 1 });
    await page.fill('#apply-from', next(2));
    await page.fill('#apply-to', isoDay(-5));
    await page.fill('#apply-reason', 'Family function');
    await page.click('text=Send for approval');
    await expect(errorOn(page)).toContainText('before the first');
    await expect(errorOn(page)).toBeInViewport();
    await expectNoSidewaysScroll(page);
    await nav(page, 'My attendance');
    await page.click('text=New request');
    await page.selectOption('#req-kind', 'work_from_home');
    await page.fill('#req-reason', 'Hm');
    await page.click('text=Send request');
    await expect(errorOn(page)).toContainText('at least 5 characters');
    await expectNoSidewaysScroll(page);
    await page.context().close();
  });
});
