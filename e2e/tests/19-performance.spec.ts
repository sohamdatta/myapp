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
    await tab(priya, 'Probation');
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
    await tab(hr, 'Probation');
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
    await tab(ishita, 'Probation');
    await expect(ishita.getByTestId('my-probation')).toContainText('Your employment is confirmed');
    await expect(ishita.locator('[data-letter="Confirmation letter"]')).toBeVisible();
  });

  test('HR changes when check-ins are asked for', async () => {
    await nav(hr, 'Performance');
    await tab(hr, 'Setup');
    await hr.fill('#perf-checkin-days', '45, 90');
    await hr.fill('#perf-recommend-days', '30');
    await hr.getByTestId('save-probation').click();
    await expect(hr.getByText('Saved.')).toBeVisible();
    await shot(hr, 'perf-07-setup', true);
    const saved = (await api(hr, 'GET', '/org/performance/setup/probation')).body;
    expect(saved.checkinDays).toEqual([45, 90]);
    expect(saved.recommendDays).toBe(30);
  });

  // ── goals, one-to-ones and feedback ──────────────────────────────────

  test('the employee writes weighted goals and sends them to her manager', async () => {
    await nav(ishita, 'My performance');
    await tab(ishita, 'Goals');
    const goals = ishita.getByTestId('goals');
    await expect(goals).toContainText('No goals yet');
    await goals.getByRole('button', { name: 'Add a goal' }).click();
    await ishita.fill('#goal-new-title', 'Publish the weekly sales dashboard every Monday');
    await ishita.selectOption('#goal-new-weight', '60');
    await ishita.selectOption('#goal-new-measure', 'done');
    await goals.getByRole('button', { name: 'Add the goal' }).click();
    await expect(goals.getByTestId('goal-weights')).toContainText('60 of 100');
    await goals.getByRole('button', { name: 'Add a goal' }).click();
    await ishita.fill('#goal-new-title', 'Automate 12 manual reports');
    await ishita.selectOption('#goal-new-weight', '40');
    await ishita.selectOption('#goal-new-measure', 'number');
    await ishita.fill('#goal-new-target', '12');
    await ishita.fill('#goal-new-unit', 'reports');
    await goals.getByRole('button', { name: 'Add the goal' }).click();
    await expect(goals.getByTestId('goal-weights')).toContainText('100 of 100');
    await goals.getByRole('button', { name: 'Send to my manager' }).click();
    await expect(goals).toContainText('Waiting to be agreed');
    await expectNoSidewaysScroll(ishita);
    await shot(ishita, 'perf-08-my-goals-phone', true);
  });

  test('the manager agrees the goals from the Team tab, and progress is recorded', async () => {
    await nav(priya, 'Performance');
    await tab(priya, 'Team');
    await expect(priya.locator('tr[data-person="Ishita Menon"]')).toContainText('Waiting to be agreed');
    await shot(priya, 'perf-09-team', true);
    await priya.getByRole('button', { name: 'Ishita Menon' }).click();
    const goals = priya.getByTestId('goals');
    await goals.getByRole('button', { name: 'Agree the goals' }).click();
    await expect(goals.locator('h3')).toContainText('Agreed');
    await shot(priya, 'perf-10-goals-agreed', true);

    await ishita.reload();
    await nav(ishita, 'My performance');
    await tab(ishita, 'Goals');
    const reports = ishita.locator('[data-goal="Automate 12 manual reports"]');
    await reports.getByRole('button', { name: 'Record progress' }).click();
    await reports.getByLabel(/Reached so far/).fill('3');
    await reports.getByRole('button', { name: 'Record' }).click();
    await expect(reports).toContainText('25%');
  });

  test('a one-to-one: shared notes both read, private notes stay with their writer', async () => {
    await tab(priya, 'One-to-ones');
    const panel = priya.getByTestId('one-to-ones');
    await panel.getByRole('button', { name: 'New one-to-one' }).click();
    await panel.locator('textarea').first().fill('Agenda: the dashboard, and the reporting course');
    await panel.locator('textarea').nth(1).fill('Ready for more ownership');
    await panel.getByRole('button', { name: 'Save' }).click();
    await panel.getByLabel('Add an action').fill('Book the reporting course');
    await panel.getByLabel('Whose').selectOption('report');
    await panel.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(panel).toContainText('Open actions');
    await shot(priya, 'perf-11-one-to-one', true);

    await ishita.reload();
    await nav(ishita, 'My performance');
    await tab(ishita, 'One-to-ones');
    const mine = ishita.getByTestId('one-to-ones');
    await expect(mine).toContainText('Agenda: the dashboard');
    await expect(mine).not.toContainText('Ready for more ownership');
    await mine.locator('.open-items').getByLabel(/Book the reporting course/).click();
    await expect(mine.locator('.open-items')).toHaveCount(0);
  });

  test('praise reaches the person; private feedback reaches only the manager', async () => {
    await nav(hr, 'Performance');
    await tab(hr, 'Team');
    await hr.getByRole('button', { name: 'Ishita Menon' }).click();
    await tab(hr, 'Feedback');
    const give = hr.getByTestId('give-feedback');
    await give.locator('textarea').fill('The Monday dashboard has saved the sales team hours. Thank you.');
    await give.getByRole('button', { name: 'Send' }).click();
    await expect(give.getByText('Sent.')).toBeVisible();
    await give.getByLabel(/^Private/).check();
    await give.locator('textarea').fill('Could speak up more in the weekly review');
    await give.getByRole('button', { name: 'Send' }).click();
    await expect(hr.locator('.feedback-list')).toContainText('Could speak up more');

    await ishita.reload();
    await nav(ishita, 'My performance');
    await tab(ishita, 'Feedback');
    const received = ishita.getByTestId('feedback-received');
    await expect(received).toContainText('saved the sales team hours');
    await expect(received).not.toContainText('Could speak up more');
    await shot(ishita, 'perf-12-feedback-phone', true);
  });

  test('HR keeps the rating scale and adds a competency', async () => {
    await nav(hr, 'Performance');
    await tab(hr, 'Setup');
    await expect(hr.getByTestId('rating-scale')).toContainText('Meets expectations');
    const competencies = hr.getByTestId('competencies');
    await competencies.getByRole('button', { name: 'Add a competency' }).click();
    await hr.fill('#comp-name', 'Ownership');
    await hr.fill('#comp-description', 'Sees work through to the end, and says early when it will slip');
    await competencies.getByRole('button', { name: 'Save' }).click();
    await expect(competencies.locator('[data-competency="Ownership"]')).toBeVisible();
    await shot(hr, 'perf-13-setup', true);
  });

  // ── review cycles ────────────────────────────────────────────────────

  test('HR sets up the annual review and starts it', async () => {
    await nav(hr, 'Performance');
    await tab(hr, 'Reviews');
    await hr.getByTestId('cycles').getByRole('button', { name: 'New cycle' }).click();
    await hr.fill('#cy-name', 'Annual review');
    await hr.getByRole('button', { name: 'Save' }).click();
    await expect(hr.getByTestId('eligible')).toContainText('Starting creates a review for');
    await shot(hr, 'perf-14-cycle-draft', true);
    await hr.getByRole('button', { name: 'Start the cycle' }).click();
    await expect(hr.locator('tr[data-review="Ishita Menon"]')).toContainText('Self review');
  });

  test('the employee writes her self review on her phone', async () => {
    await ishita.reload();
    await nav(ishita, 'My performance');
    const review = ishita.getByTestId('review');
    await expect(review).toContainText('Your self review');
    for (const goal of ['Publish the weekly sales dashboard every Monday', 'Automate 12 manual reports']) {
      await review.locator(`[data-review-goal="${goal}"]`).getByText('Exceeds expectations').click();
    }
    await ishita.fill('#self-q-went_well', 'The Monday dashboard is now used by the whole sales team.');
    await expectNoSidewaysScroll(ishita);
    await shot(ishita, 'perf-15-self-review-phone', true);
    await review.getByRole('button', { name: 'Send to my manager' }).click();
    await expect(review).toContainText('With your manager');
  });

  test('the manager rates the goals, sees the worked-out rating, and sends the review', async () => {
    await nav(priya, 'Performance');
    await tab(priya, 'Reviews');
    await priya.getByTestId('reviews-to-write').getByRole('button', { name: 'Ishita Menon' }).click();
    const review = priya.getByTestId('review');
    await expect(review.locator('[data-review-goal="Automate 12 manual reports"]')).toContainText('Their rating: 4');
    await review.locator('[data-review-goal="Publish the weekly sales dashboard every Monday"]').getByText('Exceeds expectations').click();
    await review.locator('[data-review-goal="Automate 12 manual reports"]').getByText('Meets expectations', { exact: true }).click();
    await expect(review.getByTestId('worked-out')).toContainText('3.60, rounded to 4');
    await priya.fill('#manager-q-strengths', 'Owns the dashboard end to end; the sales team relies on it.');
    await priya.fill('#manager-q-develop', 'Present the numbers in the monthly review herself.');
    await shot(priya, 'perf-16-manager-review', true);
    await review.getByRole('button', { name: 'Send the review' }).click();
    await expect(review).toContainText('Calibration');
    await expect(review).toContainText('Worked out: 3.60, rounded to 4');
  });

  test('HR releases the cycle and the employee reads and acknowledges her review', async () => {
    // Everyone else's review is taken out of this cycle, so it can be released in the test.
    const cycles = (await api(hr, 'GET', '/org/performance/cycles')).body;
    const cycle = (await api(hr, 'GET', `/org/performance/cycles/${cycles.find((c: any) => c.name === 'Annual review').id}`)).body;
    for (const r of cycle.reviews.filter((x: any) => x.name !== 'Ishita Menon' && x.status !== 'withdrawn')) {
      const w = await api(hr, 'POST', `/org/performance/reviews/${r.id}/withdraw`, { reason: 'Not part of this test' });
      expect(w.status, JSON.stringify(w.body)).toBe(200);
    }
    await hr.reload();
    await nav(hr, 'Performance');
    await tab(hr, 'Reviews');
    await hr.getByTestId('cycles').getByRole('button', { name: 'Annual review' }).click();
    await hr.getByRole('button', { name: 'Start calibration' }).click();
    await hr.getByRole('button', { name: 'Release to everyone' }).click();
    await expect(hr.locator('tr[data-review="Ishita Menon"]')).toContainText('Released');
    await shot(hr, 'perf-17-cycle-released', true);

    await ishita.reload();
    await nav(ishita, 'My performance');
    const review = ishita.getByTestId('review');
    await expect(review.getByTestId('final-rating')).toContainText('4 · Exceeds expectations');
    await expect(review).toContainText('Owns the dashboard end to end');
    await ishita.fill('#ack-comment', 'Thank you. I will take the monthly review from next month.');
    await review.getByRole('button', { name: 'Acknowledge' }).click();
    await expect(review).toContainText('Acknowledged');
    await shot(ishita, 'perf-18-review-released-phone', true);
  });
});
