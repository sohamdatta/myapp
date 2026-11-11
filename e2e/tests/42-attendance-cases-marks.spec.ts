import { test, expect, type Page } from '@playwright/test';
import { readState } from './attendance-cases';
import { signIn, nav, tab, api, isoDay } from './helpers';
let ids: Record<string, string> = {};
let hr: Page, n2: Page;
test.describe('attendance test cases, part 3', () => {
  test.beforeAll(async ({ browser }) => { ids = readState('b').ids; hr = await signIn(browser, 'hr@acme.test'); n2 = await signIn(browser, 'n2@acme.test'); });
  const open = async () => {
    await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'Daily');
    const row = hr.locator('tr', { hasText: 'Pia' }).first(); await row.getByRole('button', { name: /Details|Hide/ }).first().click();
    return hr.locator('tr.detail-row');
  };
  test('TC-198 TC-197 current mark excluded; half day then take back', async () => {
    let d = await open();
    // whatever is marked now must not be offered
    const cur = await api(hr, 'GET', `/org/attendance/days?date=${isoDay(0)}`); const mark = (cur.body as any[]).find((x) => x.membershipId === ids['e6@acme.test']).mark;
    if (mark) await expect(d.locator('#' + (await d.locator('select').first().getAttribute('id'))).locator('option', { hasText: new RegExp(`^${{ absent: 'Absent', half_day: 'Half day' }[mark as 'absent'] ?? 'zzz'}$`) })).toHaveCount(0);
    await d.getByLabel('Mark the day').selectOption({ label: 'Half day' }).catch(async () => d.getByLabel('Mark the day').selectOption('half_day'));
    await d.getByLabel('Reason').nth(await d.getByLabel('Reason').count() - 1).fill('Left at noon'); await d.getByRole('button', { name: 'Save mark' }).click();
    await expect(hr.locator('tr', { hasText: 'Pia' }).first()).toContainText(/half day|marked by hand/i);
    d = await open();
    await d.getByLabel('Mark the day').selectOption({ label: 'Present (take back the mark)' }); await d.getByLabel('Reason').nth(await d.getByLabel('Reason').count() - 1).fill('Marked in error'); await d.getByRole('button', { name: 'Save mark' }).click();
    const after = await api(hr, 'GET', `/org/attendance/days?date=${isoDay(0)}`); const r = (after.body as any[]).find((x) => x.membershipId === ids['e6@acme.test']);
    expect(['present', null], JSON.stringify(r)).toContain(r.mark);
  });
  test('TC-202 take back with nothing marked is refused or inert', async () => {
    const r = await api(hr, 'POST', `/org/attendance/users/${ids['e6@acme.test']}/marks`, { date: isoDay(0), status: 'present', reason: 'Nothing to take back' });
    expect(r.status, JSON.stringify(r.body)).toBe(409); expect(r.body.code).toBe('attendance.nothing_to_undo');
  });
  test('TC-219 first half work from home approved', async () => {
    const q = await api(n2, 'POST', '/org/attendance/me/requests', { kind: 'work_from_home', fromDate: isoDay(0), toDate: isoDay(0), half: 'first', reason: 'Home in the morning' });
    expect([200, 409], JSON.stringify(q.body)).toContain(q.status);
    await hr.reload(); await nav(hr, 'Attendance'); await tab(hr, 'Requests');
    const row = hr.locator('tr', { hasText: 'n2@acme.test' }).first(); await expect(row).toContainText(/first half/i);
    await row.getByRole('button', { name: 'Approve' }).click(); await expect(hr.getByText(/Request approved/)).toBeVisible();
  });
});
