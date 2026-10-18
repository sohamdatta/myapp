import { test, expect, type Page } from '@playwright/test';
import { HR, OWNER, acceptInvite, api, expectNoConsoleErrors, expectNoSidewaysScroll, isoDay, nav, shot, signIn, tab } from './helpers';

// Performance: a new analyst, Ishita, joins on probation reporting to Priya. Priya writes a
// check-in and recommends; Ishita reads it and replies on her phone; HR confirms her and the
// confirmation letter is issued. Then HR changes the probation settings.

const PRIYA = 'priya.shah@mail.test';
const ISHITA = 'ishita.menon@mail.test';

async function employmentId(page: Page, name: string): Promise<string> {
  const r = await api(page, 'GET', '/org/people/employees?status=all');
  const e = r.body.find((x: any) => (x.preferredName || x.legalName || x.name) === name || x.legalName === name);
  expect(e, `employee ${name}`).toBeTruthy();
  return e.id;
}

test.describe.serial('performance', () => {
  let owner: Page;
  let hr: Page;
  let priya: Page;
  let ishita: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [owner, hr, priya, ishita]) await p?.context().close();
  });

  test('a joiner on probation appears for their manager and for HR', async ({ browser }) => {
    owner = await signIn(browser, OWNER);
    const invited = await api(owner, 'POST', '/org/invitations', { email: ISHITA, roles: ['employee'] });
    expect(invited.status, JSON.stringify(invited.body)).toBe(200);
    ishita = await acceptInvite(browser, `/?invite=${encodeURIComponent(invited.body.token)}`, undefined, 390);
    const users = (await api(owner, 'GET', '/org/users')).body;
    const membership = (users.users || users).find((u: any) => u.email === ISHITA).id;
    const manager = await employmentId(owner, 'Priya Shah');
    const added = await api(owner, 'POST', '/org/people/employees', {
      legalName: 'Ishita Menon', dateOfBirth: '1999-04-02', gender: 'female', mobile: '9123400090',
      employmentType: 'probation', joiningDate: isoDay(-80), probationEnd: isoDay(20), employeeCode: 'E090',
      designation: 'Analyst', managerId: manager, membershipId: membership, panPendingReason: 'Applied for, not yet received',
    });
    expect(added.status, JSON.stringify(added.body)).toBe(200);

    priya = await signIn(browser, PRIYA);
    await nav(priya, 'Performance');
    const row = priya.locator('tr[data-probation="Ishita Menon"]');
    await expect(row).toContainText('0 of 3');
    await expect(row).toContainText('Recommendation due');
    await shot(priya, 'perf-01-probation-list', true);
  });

  test('the manager writes a check-in and recommends confirming', async () => {
    await priya.getByRole('button', { name: 'Ishita Menon' }).click();
    const day30 = priya.locator('[data-checkin="30"]');
    await expect(day30).toContainText('Overdue');
    await day30.getByRole('button', { name: 'Write the check-in' }).click();
    await day30.getByLabel('On track', { exact: true }).check();
    await day30.locator('textarea').fill('Picked up the reporting tools quickly and asks good questions in reviews.');
    await day30.getByRole('button', { name: 'Save the check-in' }).click();
    await expect(day30).toContainText('Picked up the reporting tools quickly');
    await expect(day30.getByRole('button', { name: 'Change' })).toBeVisible();

    await priya.getByRole('button', { name: 'Recommend' }).click();
    await priya.getByLabel('Confirm', { exact: true }).check();
    await priya.fill('#rec-note', 'Ready to be confirmed.');
    await shot(priya, 'perf-02-recommend', true);
    await priya.getByRole('button', { name: 'Send to HR' }).click();
    await expect(priya.getByTestId('recommendation')).toContainText('Confirm');
    await expect(priya.getByRole('button', { name: 'Decide' })).toHaveCount(0);   // managers recommend; HR decides
    await shot(priya, 'perf-03-recommended', true);
  });

  test('the employee reads the check-in and replies, on a phone', async () => {
    await ishita.reload();   // her record was linked after she signed in
    await nav(ishita, 'My performance');
    const mine = ishita.getByTestId('my-probation');
    await expect(mine).toContainText('Picked up the reporting tools quickly');
    await expect(mine).not.toContainText('Ready to be confirmed');   // the recommendation is for HR
    await mine.getByRole('button', { name: 'Reply' }).click();
    await mine.locator('textarea').fill('Thank you. I would like to sit with the data team for a week.');
    await mine.getByRole('button', { name: 'Send' }).click();
    await expect(mine).toContainText('Your reply');
    await expect(mine.getByRole('button', { name: 'Reply' })).toHaveCount(0);
    await expectNoSidewaysScroll(ishita);
    await shot(ishita, 'perf-04-my-probation-phone', true);
  });

  test('HR confirms, the letter is issued, and the employee sees it', async ({ browser }) => {
    hr = await signIn(browser, HR);
    await nav(hr, 'Performance');
    await hr.getByRole('button', { name: 'Ishita Menon' }).click();
    await expect(hr.locator('[data-checkin="30"]')).toContainText('sit with the data team');
    await hr.getByRole('button', { name: 'Decide' }).click();
    await expect(hr.getByLabel('Confirm', { exact: true })).toBeChecked();   // the recommendation, chosen to start with
    await shot(hr, 'perf-05-decide', true);
    await hr.locator('form').getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(hr.getByTestId('decision')).toContainText('Confirmed from');
    await expect(hr.getByRole('button', { name: 'Confirmation letter' })).toBeVisible();
    await shot(hr, 'perf-06-confirmed', true);

    await ishita.reload();
    await nav(ishita, 'My performance');
    await expect(ishita.getByTestId('my-probation')).toContainText('Your employment is confirmed');
    await expect(ishita.locator('[data-letter="Confirmation letter"]')).toBeVisible();
  });

  test('HR changes when check-ins are asked for', async () => {
    await nav(hr, 'Performance');
    await tab(hr, 'Setup');
    await hr.fill('#perf-checkin-days', '45, 90');
    await hr.fill('#perf-recommend-days', '30');
    await hr.getByRole('button', { name: 'Save' }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
    await shot(hr, 'perf-07-setup', true);
    const saved = (await api(hr, 'GET', '/org/performance/setup/probation')).body;
    expect(saved.checkinDays).toEqual([45, 90]);
    expect(saved.recommendDays).toBe(30);
  });
});
