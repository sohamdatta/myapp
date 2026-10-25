import { test, expect, type Page } from '@playwright/test';
import { HR, OWNER, acceptInvite, api, expectNoConsoleErrors, expectNoSidewaysScroll, isoDay, nav, shot, signIn, tab } from './helpers';

// Performance: a new analyst, Ishita, joins on probation reporting to Priya. Priya writes a
// check-in and recommends; Ishita reads it and replies on her phone; HR confirms her and the
// confirmation letter is issued. Then HR changes the probation settings.

const PRIYA = 'priya.shah@mail.test';
const ISHITA = 'ishita.menon@mail.test';
const RAHUL = 'rahul.bose@mail.test';

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
  let rahul: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [owner, hr, priya, ishita, rahul]) await p?.context().close();
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
      employmentType: 'probation', joiningDate: isoDay(-80), probationEnd: isoDay(20), employeeCode: 'E090', // date-safe: probation is 30/60/90 days from joining, so the dates must be relative to today
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

  test('HR calibrates: the panel moves a rating, with a reason the manager is told', async () => {
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
    await tab(hr, 'Calibration');
    const calibration = hr.getByTestId('calibration');
    await expect(calibration.getByTestId('distribution').locator('tr[data-level="4"]')).toContainText('1');
    await hr.fill('#session-name', 'Analytics calibration');
    await calibration.getByRole('button', { name: 'Open a session' }).click();
    const session = hr.getByTestId('calibration-session');
    const row = session.locator('tr[data-calibrate="Ishita Menon"]');
    await expect(row).toContainText('4 · Exceeds expectations');
    await row.getByRole('button', { name: 'Change rating' }).click();
    await row.getByLabel('New rating').selectOption('5');
    await row.getByLabel(/Why/).fill('Short reason');
    await row.getByRole('button', { name: 'Change', exact: true }).click();
    await expect(row.getByRole('alert')).toContainText('at least 20 characters');
    await row.getByLabel(/Why/).fill('The dashboard replaced three reports across sales; the panel agreed it is beyond the role.');
    await row.getByRole('button', { name: 'Change', exact: true }).click();
    await expect(row.getByTestId('level-now')).toContainText('5 · Outstanding');
    await expect(row).toContainText('4 → 5');
    await shot(hr, 'perf-17-calibration-session', true);
    await hr.getByRole('button', { name: 'Release to everyone' }).click();
    await expect(hr.getByRole('alert')).toContainText('still open');
    await session.getByRole('button', { name: 'Close the session' }).click();
    await session.getByRole('button', { name: 'Close it' }).click();
    await expect(session).toContainText('Closed');

    // Priya, the manager, is told.
    const told = (await api(priya, 'GET', '/org/notifications')).body.items.map((x: any) => x.event);
    expect(told).toContain('review_calibrated');
  });

  test('HR releases the cycle and the employee reads and acknowledges her review', async () => {
    await hr.getByRole('button', { name: 'Release to everyone' }).click();
    // The cycle's own Reviews tab, under the page's tabs.
    await hr.getByRole('tab', { name: 'Reviews', exact: true }).last().click();
    await expect(hr.locator('tr[data-review="Ishita Menon"]')).toContainText('Released');
    await shot(hr, 'perf-18-cycle-released', true);

    await ishita.reload();
    await nav(ishita, 'My performance');
    const review = ishita.getByTestId('review');
    await expect(review.getByTestId('final-rating')).toContainText('5 · Outstanding');
    await expect(review).toContainText('Owns the dashboard end to end');
    await ishita.fill('#ack-comment', 'Thank you. I will take the monthly review from next month.');
    await review.getByRole('button', { name: 'Acknowledge' }).click();
    await expect(review).toContainText('Acknowledged');
    await shot(ishita, 'perf-19-review-released-phone', true);
  });

  // ── outcomes ─────────────────────────────────────────────────────────

  test('HR prepares the outcome from the merit table, adds a promotion and sends it', async () => {
    // Ishita works from the Bengaluru office and gets her joining salary on the structure Priya is
    // paid on, so there is pay to raise.
    const ishitaId = await employmentId(hr, 'Ishita Menon');
    const users = (await api(owner, 'GET', '/org/users')).body;
    const membership = (users.users || users).find((u: any) => u.email === ISHITA).id;
    const office = (await api(owner, 'GET', '/org/attendance/locations')).body.find((l: any) => l.name === 'Bengaluru office');
    const shift = (await api(owner, 'GET', '/org/attendance/shifts')).body[0];
    const assigned = await api(owner, 'POST', '/org/attendance/assignments', {
      membershipIds: [membership], workLocationId: office.id, shiftId: shift.id, weeklyOffs: [0], remoteDays: [], effectiveFrom: isoDay(-80), // date-safe: from the joining date above
    });
    expect(assigned.status, JSON.stringify(assigned.body)).toBeLessThan(300);
    const priyaPay = (await api(owner, 'GET', `/org/people/employees/${await employmentId(owner, 'Priya Shah')}/pay`)).body;
    const set = await api(owner, 'POST', `/org/people/employees/${ishitaId}/pay`, {
      validFrom: `${isoDay(0).slice(0, 7)}-01`, structureId: priyaPay.history[0].structureId, annualAmount: 600000, reason: 'Joining salary',
    });
    expect(set.status, JSON.stringify(set.body)).toBe(200);

    await hr.reload();
    await nav(hr, 'Performance');
    await tab(hr, 'Reviews');
    await hr.getByTestId('cycles').getByRole('button', { name: 'Annual review' }).click();
    await tab(hr, 'Outcomes');
    const outcomes = hr.getByTestId('outcomes');
    await outcomes.getByRole('button', { name: 'Prepare 1 outcome' }).click();
    const row = outcomes.locator('tr[data-outcome="Ishita Menon"]');
    await expect(row.getByTestId('increment')).toHaveText('12%');
    await expect(row).toContainText('₹6,00,000 → ₹6,72,000');
    await row.getByRole('button', { name: 'Change' }).click();
    await hr.getByLabel('Increment (%)').fill('15');
    await hr.getByLabel('Promote').check();
    await hr.getByLabel('New designation').fill('Senior Analyst');
    await hr.getByLabel('Bonus (₹)').fill('40000');
    await outcomes.getByRole('button', { name: 'Save' }).click();
    await expect(outcomes.getByRole('alert')).toContainText('write a note');
    await hr.getByLabel(/^Note/).fill('Promotion to Senior Analyst; brought in line with the band');
    await outcomes.getByRole('button', { name: 'Save' }).click();
    await expect(row).toContainText('₹6,00,000 → ₹6,90,000');
    await expect(row).toContainText('Senior Analyst');
    await shot(hr, 'perf-20-outcomes', true);
    await outcomes.getByRole('button', { name: 'Send drafts for approval' }).click();
    await expect(row).toContainText('Waiting for approval');
    // HR sent it, so HR does not approve it.
    await expect(outcomes.getByRole('button', { name: /^Approve/ })).toHaveCount(0);
  });

  test('the owner approves: pay and designation change, and the letters reach the employee', async () => {
    await owner.reload();
    await nav(owner, 'Performance');
    await tab(owner, 'Reviews');
    await owner.getByTestId('cycles').getByRole('button', { name: 'Annual review' }).click();
    await tab(owner, 'Outcomes');
    const outcomes = owner.getByTestId('outcomes');
    await outcomes.getByRole('button', { name: 'Approve 1' }).click();
    await expect(outcomes.locator('tr[data-outcome="Ishita Menon"]')).toContainText('Approved');

    const ishitaId = await employmentId(owner, 'Ishita Menon');
    const pay = (await api(owner, 'GET', `/org/people/employees/${ishitaId}/pay`)).body;
    expect(Number(pay.history[0].annualAmount)).toBe(690000);

    await ishita.reload();
    await nav(ishita, 'My performance');
    await tab(ishita, 'Reviews');
    const letters = ishita.getByTestId('outcome-letters');
    await expect(letters).toContainText('Increment letter');
    await expect(letters).toContainText('Promotion letter');
    await expectNoSidewaysScroll(ishita);
    await shot(ishita, 'perf-21-outcome-letters-phone', true);
  });

  // ── improvement plans ────────────────────────────────────────────────

  test('Priya drafts an improvement plan for Rahul and HR starts it', async ({ browser }) => {
    // Rahul, who reports to Priya, gets a login linked to his record.
    const invited = await api(owner, 'POST', '/org/invitations', { email: RAHUL, roles: ['employee'] });
    expect(invited.status, JSON.stringify(invited.body)).toBe(200);
    rahul = await acceptInvite(browser, `/?invite=${encodeURIComponent(invited.body.token)}`, undefined, 390);
    const users = (await api(owner, 'GET', '/org/users')).body;
    const membership = (users.users || users).find((u: any) => u.email === RAHUL).id;
    const linked = await api(owner, 'POST', `/org/people/employees/${await employmentId(owner, 'Rahul Bose')}/link`, { membershipId: membership });
    expect(linked.status, JSON.stringify(linked.body)).toBeLessThan(300);

    await priya.reload();
    await nav(priya, 'Performance');
    await tab(priya, 'Improvement plans');
    await priya.getByTestId('plans').getByRole('button', { name: 'New plan' }).click();
    await priya.getByLabel('For').selectOption({ label: 'Rahul Bose' });
    await priya.getByLabel('Check in').selectOption('7');
    await priya.fill('#plan-reason', 'The weekly stock report was late on five of the last eight Mondays, and twice had wrong totals.');
    await priya.fill('#obj-title-0', 'Send the weekly stock report on time');
    await priya.fill('#obj-measure-0', 'By 11 am every Monday');
    await priya.fill('#obj-title-1', 'Correct totals');
    await priya.fill('#obj-measure-1', 'No corrections needed after sending');
    await priya.fill('#plan-support', 'A session on the stock system with Meera, and a 15-minute check every Monday morning.');
    await shot(priya, 'perf-22-plan-draft', true);
    await priya.getByRole('button', { name: 'Save the draft' }).click();
    const plan = priya.getByTestId('plan');
    await expect(plan).toContainText('Draft, waiting for HR');
    await expect(plan.getByRole('button', { name: 'Start the plan' })).toHaveCount(0);

    await hr.reload();
    await nav(hr, 'Performance');
    await tab(hr, 'Improvement plans');
    await hr.getByTestId('plans').getByRole('button', { name: 'Rahul Bose' }).click();
    await hr.getByTestId('start-plan').getByRole('button', { name: 'Start the plan' }).click();
    const started = hr.getByTestId('plan');
    await expect(started).toContainText('Performance improvement plan');
    await expect(started.locator('[data-plan-checkin]')).toHaveCount(9);
  });

  test('Rahul reads his plan on his phone and acknowledges it with a response', async () => {
    await rahul.reload();
    await nav(rahul, 'My performance');
    const plan = rahul.getByTestId('plan');
    await expect(plan).toContainText('Your improvement plan');
    await expect(plan).toContainText('Send the weekly stock report on time');
    await rahul.fill('#plan-response', 'The Monday delay is the warehouse upload; I have asked for it by 9 am.');
    await plan.getByRole('button', { name: 'I have read it' }).click();
    await expect(plan).toContainText('Acknowledged');
    await expectNoSidewaysScroll(rahul);
    await shot(rahul, 'perf-23-plan-phone', true);
  });

  test('Priya writes the first check-in; Rahul replies; HR records the plan as completed', async () => {
    await priya.reload();
    await nav(priya, 'Performance');
    await tab(priya, 'Improvement plans');
    await priya.getByTestId('plans').getByRole('button', { name: 'Rahul Bose' }).click();
    const plan = priya.getByTestId('plan');
    await expect(plan).toContainText('The Monday delay is the warehouse upload');
    const first = plan.locator('[data-plan-checkin]').first();
    await first.getByRole('button', { name: 'Write the check-in' }).click();
    await first.getByRole('group', { name: 'Send the weekly stock report on time' }).getByLabel('On track').check();
    await first.getByRole('group', { name: 'Correct totals' }).getByLabel('At risk').check();
    await first.getByLabel(/^Note/).fill('On time this week; one total was off by a day of stock.');
    await first.getByRole('button', { name: 'Save the check-in' }).click();
    await expect(first).toContainText('At risk');

    await rahul.reload();
    await nav(rahul, 'My performance');
    const mine = rahul.getByTestId('plan').locator('[data-plan-checkin]').first();
    await mine.getByRole('button', { name: 'Reply' }).click();
    await mine.getByLabel(/Your reply/).fill("The stock total used Friday's count; fixed from next week.");
    await mine.getByRole('button', { name: 'Send' }).click();
    await expect(mine).toContainText("Friday's count");

    await hr.reload();
    await nav(hr, 'Performance');
    await tab(hr, 'Improvement plans');
    await hr.getByTestId('plans').getByRole('button', { name: 'Rahul Bose' }).click();
    const decide = hr.getByTestId('decide-plan');
    await decide.getByLabel(/Completed/).check();
    await decide.getByLabel('Note on the decision').fill('Both objectives met; closed early, agreed with Priya.');
    await decide.getByRole('button', { name: 'Record the decision' }).click();
    await expect(hr.getByTestId('plan')).toContainText('Completed');
    await shot(hr, 'perf-24-plan-completed', true);
  });

  // ── peers, skip-level sign-off, requested feedback ───────────────────

  test('a half-year review: Priya asks Ishita for a peer review, and Harish signs it off', async () => {
    // A cycle with one peer review a person, named, and skip-level sign-off; only Rahul is reviewed.
    const created = await api(hr, 'POST', '/org/performance/cycles', {
      name: 'Half-year check', selfReview: false, peerReviews: 1, peersAnonymous: false, skipLevel: true,
    });
    expect(created.status, JSON.stringify(created.body)).toBe(200);
    const started = (await api(hr, 'POST', `/org/performance/cycles/${created.body.id}/start`, {})).body;
    for (const r of started.reviews.filter((x: any) => x.name !== 'Rahul Bose')) {
      await api(hr, 'POST', `/org/performance/reviews/${r.id}/withdraw`, { reason: 'Not part of this test' });
    }
    const reviewId = started.reviews.find((x: any) => x.name === 'Rahul Bose').id;

    await priya.reload();
    await nav(priya, 'Performance');
    await tab(priya, 'Reviews');
    await priya.getByTestId('reviews-to-write').locator('li, tr').filter({ hasText: 'Half-year check' }).getByRole('button', { name: 'Rahul Bose' }).click();
    const peers = priya.getByTestId('peers');
    await peers.getByLabel('Ask a colleague').selectOption(await employmentId(owner, 'Ishita Menon'));
    await peers.getByRole('button', { name: 'Ask' }).click();
    await expect(peers.locator('[data-peer="Ishita Menon"]')).toContainText('Asked');

    await ishita.reload();
    await nav(ishita, 'My performance');
    await tab(ishita, 'Reviews');
    await ishita.getByTestId('peer-requests').getByRole('button', { name: 'Rahul Bose' }).click();
    const peer = ishita.getByTestId('peer-review');
    await expect(peer).toContainText('with your name');
    await ishita.fill('#peer-q-peer_strengths', 'Rahul always knows where the stock numbers come from.');
    await expectNoSidewaysScroll(ishita);
    await shot(ishita, 'perf-25-peer-review-phone', true);
    await peer.getByRole('button', { name: 'Send' }).click();
    await expect(peer).toContainText('Sent');

    // Priya writes her part (through the API: the review form itself is covered above) and it goes to Harish.
    const review = (await api(priya, 'GET', `/org/performance/reviews/${reviewId}`)).body;
    expect(review.peers[0].answers.peer_strengths).toContain('stock numbers');
    const rate = (list: any[]) => list.map((x: any) => ({ id: x.id, rating: 3 }));
    const sent = await api(priya, 'PUT', `/org/performance/reviews/${reviewId}/entries/manager`, {
      goalRatings: rate(review.goals), competencyRatings: rate(review.competencies), overall: 3, submit: true,
      answers: { strengths: 'Knows the stock system inside out.', develop: 'Send the Monday report without reminders.' },
    });
    expect(sent.status, JSON.stringify(sent.body)).toBe(200);
    expect(sent.body.status).toBe('skip_level');

    await hr.reload();
    await nav(hr, 'Performance');
    await tab(hr, 'Reviews');
    await hr.getByTestId('reviews-to-write').locator('li, tr').filter({ hasText: 'Half-year check' }).getByRole('button', { name: 'Rahul Bose' }).click();
    const signOff = hr.getByTestId('sign-off');
    await hr.fill('#sign-off-note', 'Agreed; the plan this autumn was completed well.');
    await shot(hr, 'perf-26-skip-level-sign-off', true);
    await signOff.getByRole('button', { name: 'Sign it off' }).click();
    await expect(hr.getByTestId('review')).toContainText('Signed off by Harish Rao');
  });

  test('Ishita asks Priya for feedback, and Priya answers the request', async () => {
    await ishita.reload();
    await nav(ishita, 'My performance');
    await tab(ishita, 'Feedback');
    const ask = ishita.getByTestId('ask-feedback');
    await ask.getByLabel('Colleagues').selectOption(await employmentId(owner, 'Priya Shah'));
    await ask.getByLabel(/What about/).fill('My first month as Senior Analyst');
    await ask.getByRole('button', { name: 'Ask' }).click();
    await expect(ishita.getByTestId('feedback-you-asked')).toContainText('Waiting');

    await priya.reload();
    await nav(priya, 'My performance');
    await tab(priya, 'Feedback');
    const request = priya.getByTestId('feedback-asked-of-me').locator('[data-feedback-request="Ishita Menon"]');
    await expect(request).toContainText('My first month as Senior Analyst');
    await request.getByRole('button', { name: 'Give feedback' }).click();
    await request.getByLabel('Feedback').fill('You took over the weekly review quickly and the team noticed.');
    await request.getByRole('button', { name: 'Send' }).click();
    await expect(priya.getByTestId('feedback-asked-of-me')).toHaveCount(0);

    await ishita.reload();
    await nav(ishita, 'My performance');
    await tab(ishita, 'Feedback');
    await expect(ishita.getByTestId('feedback-you-asked')).toContainText('Given');
    await expect(ishita.getByTestId('feedback-received')).toContainText('the team noticed');
  });
});
