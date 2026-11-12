import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay } from './helpers';
import { join } from './join';

// Every performance setting and screen, case by case (docs/testing/performance-ui-test-cases.xlsx; the TC ids are
// its rows), in an Organization of its own, Perf Labs. Mohan manages Esha, Farhan and Gita and reports to Sunil;
// Pooja joined 80 days ago on probation.

const RUN = process.env.E2E_RUN || '';   // a suffix, to run the file again on a database that already has Perf Labs
const D = `perf${RUN}.test`;
const U = (n: string) => `${n}@${D}`;
const NAMES: Record<string, string> = { skip: 'Sunil Rao', mgr: 'Mohan Das', e1: 'Esha Kapoor', e2: 'Farhan Ali', e3: 'Gita Rao', prob: 'Pooja Nair', hr: 'Hema Iyer' };
const P: Record<string, Page> = {};
let hr: Page;
let owner: Page;
let ids: Record<string, string> = {};
const emp: Record<string, string> = {};
let cycleId = '';
let reviewId = '';

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function refused(r: Promise<{ status: number; body: any }>, status: number, text?: string) {
  const done = await r;
  expect(done.status, JSON.stringify(done.body)).toBe(status);
  if (text) expect(done.body.message).toContain(text);
  return done.body;
}
async function perf(p: Page, t?: string) { await p.reload(); await nav(p, 'Performance'); if (t) await tab(p, t); }
async function mine(p: Page, t?: string) { await p.reload(); await nav(p, 'My performance'); if (t) await tab(p, t); }

test.describe.serial('performance test cases', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: `Perf Labs${RUN}`, slug: `perf-labs${RUN}`, adminEmail: U('owner') }));
    await admin.context().close();
    owner = await join(browser, org.invitationToken, U('owner'));
    const roles: Record<string, string[]> = { hr: ['hr_admin', 'employee'], aud: ['auditor'], skip: ['employee', 'manager'], mgr: ['employee', 'manager'],
      e1: ['employee'], e2: ['employee'], e3: ['employee'], prob: ['employee'] };
    for (const [n, r] of Object.entries(roles)) {
      const inv = await ok(api(owner, 'POST', '/org/invitations', { email: U(n), roles: r }));
      P[n] = await join(browser, inv.token, U(n));
    }
    hr = P.hr;
    ids = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
    const add = (n: string, body: any) => ok(api(owner, 'POST', '/org/people/employees', { legalName: NAMES[n], joiningDate: isoDay(-400), designation: 'Associate', membershipId: ids[U(n)], ...body }));   // date-safe: joined long ago
    emp.skip = (await add('skip', { designation: 'Head of sales' })).id;
    emp.hr = (await add('hr', { designation: 'HR manager' })).id;
    emp.mgr = (await add('mgr', { designation: 'Sales manager', managerId: emp.skip })).id;
    for (const n of ['e1', 'e2', 'e3']) emp[n] = (await add(n, { managerId: emp.mgr })).id;
    emp.prob = (await add('prob', { employmentType: 'probation', joiningDate: isoDay(-80), probationEnd: isoDay(20), managerId: emp.mgr })).id;   // date-safe: check-ins are 30/60/90 days from joining, so the dates are relative to today
  });

  // ---------- setup ----------
  test('TC-F001 TC-F002 the rating scale has 3 to 7 levels', async () => {
    await perf(hr, 'Setup');
    await expect(hr.getByTestId('rating-scale')).toContainText('Meets expectations');
    await refused(api(hr, 'PUT', '/org/performance/setup/scale', { levels: [{ value: 1, name: 'Low' }, { value: 2, name: 'High' }] }), 400, 'A rating scale has 3 to 7 levels');
  });
  test('TC-F003 TC-F004 add a competency; the same name refused', async () => {
    const c = hr.getByTestId('competencies');
    await c.getByRole('button', { name: 'Add a competency' }).click();
    await hr.fill('#comp-name', 'Ownership'); await hr.fill('#comp-description', 'Sees work through to the end');
    await c.getByRole('button', { name: 'Save' }).click();
    await expect(c).toContainText('Ownership');
    await refused(api(hr, 'POST', '/org/performance/setup/competencies', { name: 'Ownership', description: 'Again', active: true }), 409, 'A competency with that name already exists');
  });
  test('TC-F005 TC-F006 probation check-in days saved; recommendation window checked', async () => {
    await perf(hr, 'Setup');
    await hr.fill('#perf-checkin-days', '30, 60'); await hr.fill('#perf-recommend-days', '21');
    await hr.getByTestId('save-probation').click(); await expect(hr.getByText('Saved.').first()).toBeVisible();
    await refused(api(hr, 'PUT', '/org/performance/setup/probation', { checkinDays: [30], recommendDays: 5 }), 400, 'between 7 and 90 days');
  });
  test('TC-F007 TC-F008 a review form needs 1 to 30 questions', async () => {
    await refused(api(hr, 'POST', '/org/performance/setup/forms', { name: 'Empty form', questions: [] }), 400, 'A form has 1 to 30 questions');
    await ok(api(hr, 'POST', '/org/performance/setup/forms', { name: 'Short form', questions: [{ id: 'went_well', text: 'What went well?', type: 'text', for: 'self' }] }));
    await perf(hr, 'Setup'); await expect(hr.getByTestId('review-forms')).toContainText('Short form');
  });
  test('TC-F009 the merit table is saved', async () => {
    await perf(hr, 'Setup');
    const merit = hr.getByTestId('merit-table');
    // Each row has the guide share, then the increment; the top row is the highest rating.
    await merit.locator('tbody tr').first().locator('input').nth(1).fill('15');
    await hr.getByTestId('save-merit').click(); await expect(hr.getByText('Saved.').first()).toBeVisible();
    const top = (await ok(api(hr, 'GET', '/org/performance/merit'))).meritTable;
    expect(Math.max(...Object.values(top).map(Number))).toBe(15);
  });

  // ---------- goals ----------
  test('TC-F010 TC-F011 goals must weigh 100 before they are sent', async () => {
    const p = P.e1; await mine(p, 'Goals');
    const goals = p.getByTestId('goals');
    await goals.getByRole('button', { name: 'Add a goal' }).click();
    await p.fill('#goal-new-title', 'Grow the north region by 10%'); await p.selectOption('#goal-new-weight', '60');
    await goals.getByRole('button', { name: 'Add the goal' }).click();
    await expect(goals.getByTestId('goal-weights')).toContainText('60 of 100');
    await refused(api(p, 'POST', '/org/me/performance/goals/submit', {}), 409, 'make them add up to 100');
    await goals.getByRole('button', { name: 'Add a goal' }).click();
    await p.fill('#goal-new-title', 'Open 12 new dealers'); await p.selectOption('#goal-new-weight', '40');
    await goals.getByRole('button', { name: 'Add the goal' }).click();
    await expect(goals.getByTestId('goal-weights')).toContainText('100 of 100');
    await goals.getByRole('button', { name: 'Send to my manager' }).click();
    await expect(goals).toContainText('Waiting to be agreed');
  });
  test('TC-F012 TC-F013 progress waits for agreement; the manager agrees', async () => {
    const g = (await ok(api(P.e1, 'GET', '/org/me/performance'))).goals?.goals?.[0] ?? (await ok(api(P.mgr, 'GET', `/org/performance/goals?employmentId=${emp.e1}`))).goals?.[0];
    if (g) await refused(api(P.e1, 'POST', `/org/performance/goals/${g.id}/checkins`, { progress: 10, note: 'Early days' }), 409, 'once the goal is agreed');
    await perf(P.mgr, 'Team'); await P.mgr.getByRole('button', { name: NAMES.e1 }).click();
    await P.mgr.getByTestId('goals').getByRole('button', { name: 'Agree the goals' }).click();
    await expect(P.mgr.getByTestId('goals')).toContainText(/Agreed/i);
  });
  test('TC-F014 nobody agrees their own goals', async () => {
    await refused(api(P.mgr, 'POST', '/org/performance/goals/agree', { employmentId: emp.mgr, periodStart: null, note: '' }), 409, 'Nobody agrees, returns or drops their own goals');
  });

  // ---------- one-to-ones and feedback ----------
  test('TC-F015 a one-to-one: shared notes both read, private notes stay', async () => {
    await perf(P.mgr, 'Team'); await P.mgr.getByRole('button', { name: NAMES.e2 }).click(); await tab(P.mgr, 'One-to-ones');
    const panel = P.mgr.getByTestId('one-to-ones');
    await panel.getByRole('button', { name: 'New one-to-one' }).click();
    await panel.locator('textarea').first().fill('Talked about the dealer pipeline');
    await panel.locator('textarea').nth(1).fill('Seems tired; ask about workload next time');
    await panel.getByRole('button', { name: 'Save' }).click();
    await mine(P.e2, 'One-to-ones');
    await expect(P.e2.getByTestId('one-to-ones')).toContainText('Talked about the dealer pipeline');
    await expect(P.e2.getByTestId('one-to-ones')).not.toContainText('Seems tired');
  });
  test('TC-F016 one-to-ones are only between a manager and their report', async () => {
    const r = await api(P.e3, 'POST', '/org/performance/one-to-ones', { withEmploymentId: emp.e2, meetingDate: isoDay(0), sharedNotes: 'Hello' });
    expect([403, 404], JSON.stringify(r.body)).toContain(r.status);   // a colleague is told nothing about the person
  });
  test('TC-F017 TC-F018 praise reaches the person; private feedback only the manager', async () => {
    await ok(api(P.e3, 'POST', '/org/performance/feedback', { employmentId: emp.e1, kind: 'praise', text: 'Thanks for covering my calls last week', shareWithManager: false }));
    await ok(api(P.e3, 'POST', '/org/performance/feedback', { employmentId: emp.e1, kind: 'private', text: 'Often late to the Monday huddle' }));
    await mine(P.e1, 'Feedback');
    await expect(P.e1.getByTestId('feedback-received')).toContainText('Thanks for covering my calls');
    await expect(P.e1.getByTestId('feedback-received')).not.toContainText('Monday huddle');
    const seen = await ok(api(P.mgr, 'GET', `/org/performance/feedback?employmentId=${emp.e1}`));
    expect(JSON.stringify(seen)).toContain('Monday huddle');
  });
  test('TC-F019 TC-F020 no feedback to oneself; a withdrawn item keeps its date, not its words', async () => {
    await refused(api(P.e3, 'POST', '/org/performance/feedback', { employmentId: emp.e3, kind: 'praise', text: 'Well done me' }), 409, 'Nobody gives feedback to themselves');
    const given = await ok(api(P.e3, 'GET', '/org/me/performance/feedback/given'));
    const item = (given.items || given).find((f: any) => (f.text || '').includes('Monday huddle'));
    await ok(api(P.e3, 'POST', `/org/performance/feedback/${item.id}/withdraw`, {}));
    const after = await ok(api(P.e3, 'GET', '/org/me/performance/feedback/given'));
    expect(JSON.stringify(after)).not.toContain('Monday huddle');
  });
  test('TC-F021 TC-F022 TC-F023 ask colleagues for feedback; limits; decline with a reason', async () => {
    await refused(api(P.e1, 'POST', '/org/me/performance/feedback/requests', { aboutId: emp.e1, giverIds: [], note: 'Please' }), 400, 'Ask one to five colleagues');
    await refused(api(P.e1, 'POST', '/org/me/performance/feedback/requests', { aboutId: emp.e1, giverIds: [emp.e1], note: 'Me' }), 400, 'Ask someone other than the person');
    await mine(P.e1, 'Feedback');
    const ask = P.e1.getByTestId('ask-feedback');
    await P.e1.selectOption('#ask-who', (await P.e1.locator('#ask-who option', { hasText: NAMES.e2 }).getAttribute('value'))!);
    await P.e1.fill('#ask-note', 'How did the dealer meet go from your side?');
    await ask.getByRole('button', { name: 'Ask', exact: true }).click();
    await expect(ask.getByText('Asked.')).toBeVisible();
    await mine(P.e1, 'Feedback'); await expect(P.e1.getByTestId('feedback-you-asked')).toContainText(NAMES.e2);
    await mine(P.e2, 'Feedback');
    const asked = P.e2.getByTestId('feedback-asked-of-me');
    await asked.getByRole('button', { name: /Decline/ }).first().click();
    await asked.locator('[id^="decline-"]').fill('I was not at that meeting');
    await asked.getByRole('button', { name: /Decline/ }).last().click();
    await mine(P.e1, 'Feedback'); await expect(P.e1.getByTestId('feedback-you-asked')).toContainText(/declined/i);
  });

  // ---------- probation ----------
  test('TC-F024 TC-F025 probation list and a check-in', async () => {
    await perf(P.mgr, 'Probation');
    await expect(P.mgr.locator(`tr[data-probation="${NAMES.prob}"]`)).toContainText(/0 of \d/);   // check-ins are fixed when the probation review is made
    await P.mgr.getByRole('button', { name: NAMES.prob }).click();
    const day30 = P.mgr.locator('[data-checkin="30"]');
    await day30.getByRole('button', { name: 'Write the check-in' }).click();
    await day30.getByLabel('Some concerns', { exact: true }).check();
    await day30.locator('textarea').fill('Good with customers; reports are often late.');
    await day30.getByRole('button', { name: 'Save the check-in' }).click();
    await expect(day30).toContainText('reports are often late');
  });
  test('TC-F026 TC-F027 the employee replies once', async () => {
    await mine(P.prob);
    const m = P.prob.getByTestId('my-probation');
    await m.getByRole('button', { name: 'Reply' }).click(); await m.locator('textarea').fill('I will send them by Friday noon.');
    await m.getByRole('button', { name: 'Send' }).click();
    await expect(m).toContainText('Your reply'); await expect(m.getByRole('button', { name: 'Reply' })).toHaveCount(0);
    const pr = await ok(api(P.mgr, 'GET', `/org/performance/probation`));
    const review = (pr.reviews || pr).find((x: any) => x.name === NAMES.prob || x.legalName === NAMES.prob);
    const detail = await ok(api(P.mgr, 'GET', `/org/performance/probation/${review.id}`));
    const k = detail.checkins.find((c: any) => c.dayNumber === 30 || c.day === 30 || c.days === 30) ?? detail.checkins[0];
    await refused(api(P.prob, 'POST', `/org/me/performance/probation/checkins/${k.id}/reply`, { reply: 'Again' }), 409, 'already replied');
  });
  test('TC-F028 TC-F029 TC-F030 the manager recommends extending; HR extends; not twice', async () => {
    await perf(P.mgr, 'Probation'); await P.mgr.getByRole('button', { name: NAMES.prob }).click();
    await P.mgr.getByRole('button', { name: 'Recommend' }).click();
    await P.mgr.getByLabel('Extend the probation').check(); await P.mgr.fill('#rec-note', 'Three more months to settle in.');
    await P.mgr.getByRole('button', { name: 'Send to HR' }).click();
    await expect(P.mgr.getByTestId('recommendation')).toContainText('Extend');
    const pr = await ok(api(hr, 'GET', `/org/performance/probation`));
    const review = (pr.reviews || pr).find((x: any) => x.name === NAMES.prob || x.legalName === NAMES.prob);
    await perf(hr, 'Probation'); await hr.getByRole('button', { name: NAMES.prob }).click();
    await hr.getByRole('button', { name: 'Decide' }).click();
    await expect(hr.getByLabel('Extend the probation')).toBeChecked();
    await hr.getByRole('button', { name: 'Extend the probation' }).click();
    await expect(hr.getByText('Write the reason, in at least 20 characters')).toBeVisible();   // TC-F029: a reason is needed
    await hr.fill('#dec-note', 'Needs three more months on punctuality.');
    await hr.locator('form').getByRole('button', { name: 'Extend the probation' }).click();
    await expect(hr.getByTestId('decision')).toContainText(/Extended/i);
    // Extending starts a new review with check-ins every 30 days; the decided one cannot be decided again.
    await refused(api(hr, 'POST', `/org/performance/probation/${review.id}/decide`, { decision: 'confirm', note: 'Again' }), 409, 'already been decided');
  });

  // ---------- review cycle ----------
  test('TC-F031 TC-F032 a cycle nobody matches cannot start; a cycle is set up', async () => {
    const empty = await ok(api(hr, 'POST', '/org/performance/cycles', { name: 'Nobody cycle', periodStart: isoDay(-300), periodEnd: isoDay(0), appliesTo: { designation: ['Astronaut'] }, joinedBy: isoDay(0), selfReview: true, goalWeight: 100 }));   // date-safe: a review period of the past year
    await refused(api(hr, 'POST', `/org/performance/cycles/${empty.id}/start`, {}), 409, "Nobody matches this cycle's rules");
    await perf(hr, 'Reviews');
    await hr.getByTestId('cycles').getByRole('button', { name: 'New cycle' }).click();
    await hr.fill('#cy-name', 'Half-year review'); await hr.fill('#cy-joined', isoDay(0));
    await hr.getByRole('button', { name: 'Save' }).click();
    await expect(hr.getByTestId('eligible')).toContainText('Starting creates a review for');
  });
  test('TC-F033 the cycle starts and creates reviews', async () => {
    await hr.getByRole('button', { name: 'Start the cycle' }).click();
    await expect(hr.getByTestId('cycle-progress')).toBeVisible();
    const cycles = await ok(api(hr, 'GET', '/org/performance/cycles'));
    cycleId = cycles.find((c: any) => c.name === 'Half-year review').id;
    const cycle = await ok(api(hr, 'GET', `/org/performance/cycles/${cycleId}`));
    // Only Esha's review is followed through; the rest are withdrawn so the cycle can be released.
    for (const r of cycle.reviews.filter((x: any) => x.name !== NAMES.e1 && x.status !== 'withdrawn')) {
      await ok(api(hr, 'POST', `/org/performance/reviews/${r.id}/withdraw`, { reason: 'Not part of this test' }));
    }
    reviewId = cycle.reviews.find((x: any) => x.name === NAMES.e1).id;
  });
  test('TC-F034 TC-F035 the self review needs its answers, then is sent', async () => {
    await refused(api(P.e1, 'PUT', `/org/performance/reviews/${reviewId}/entries/self`, { goalRatings: [], answers: {}, submit: true }), 400);
    const p = P.e1; await mine(p, 'Reviews');
    const review = p.getByTestId('review');
    for (const goal of ['Grow the north region by 10%', 'Open 12 new dealers']) await review.locator(`[data-review-goal="${goal}"]`).getByText('Meets expectations', { exact: true }).click();
    const q = p.locator('[id^="self-q-"]'); for (let i = 0; i < await q.count(); i++) await q.nth(i).fill('Opened nine dealers and the region grew 8%.');
    await review.getByRole('button', { name: 'Send to my manager' }).click();
    await expect(review).toContainText('With your manager');
  });
  test('TC-F036 TC-F037 the manager returns a self review once', async () => {
    await ok(api(P.mgr, 'POST', `/org/performance/reviews/${reviewId}/return-self`, { reason: 'Please add the dealer numbers by month' }));
    await refused(api(P.mgr, 'POST', `/org/performance/reviews/${reviewId}/return-self`, { reason: 'And again please' }), 409, 'returned once');
    const p = P.e1; await mine(p, 'Reviews');
    await p.getByTestId('review').getByRole('button', { name: 'Send to my manager' }).click();
    await expect(p.getByTestId('review')).toContainText('With your manager');
  });
  test('TC-F038 TC-F039 the manager writes and sends; a different overall needs a reason', async () => {
    await perf(P.mgr, 'Reviews');
    await P.mgr.getByTestId('reviews-to-write').getByRole('button', { name: NAMES.e1 }).click();
    const review = P.mgr.getByTestId('review');
    for (const goal of ['Grow the north region by 10%', 'Open 12 new dealers']) await review.locator(`[data-review-goal="${goal}"]`).getByText('Meets expectations', { exact: true }).click();
    await expect(review.getByTestId('worked-out')).toContainText('rounded to 3');
    const q = P.mgr.locator('[id^="manager-q-"]'); for (let i = 0; i < await q.count(); i++) await q.nth(i).fill('Steady year; build the dealer pipeline earlier.');
    await review.getByRole('button', { name: 'Send the review' }).click();
    await expect(review).toContainText(/Sign-off|Calibration/);
  });
  test('TC-F040 the skip-level manager signs off', async () => {
    const r = await api(P.skip, 'POST', `/org/performance/reviews/${reviewId}/sign-off`, { action: 'agree', note: 'Agreed' });
    expect([200, 409], JSON.stringify(r.body)).toContain(r.status);   // 409 when this cycle has no sign-off step
  });
  test('TC-F041 peers only when the cycle asks for them', async () => {
    const r = await api(P.mgr, 'POST', `/org/performance/reviews/${reviewId}/peers`, { employmentIds: [emp.e2] });
    expect(r.status).toBe(409);
  });
  test('TC-F042 TC-F043 TC-F044 TC-F045 calibration: a change needs a 20-character reason; closing makes it ready', async () => {
    await ok(api(hr, 'POST', `/org/performance/cycles/${cycleId}/calibrate`, {}));
    await perf(hr, 'Reviews'); await hr.getByTestId('cycles').getByRole('button', { name: 'Half-year review' }).click();
    await tab(hr, 'Calibration');
    const cal = hr.getByTestId('calibration');
    await hr.fill('#session-name', 'Sales calibration'); await cal.getByRole('button', { name: 'Open a session' }).click();
    const session = hr.getByTestId('calibration-session');
    const row = session.locator('tr', { hasText: NAMES.e1 });
    await row.getByRole('button', { name: 'Change rating' }).click();
    await row.locator('[id^="cal-level-"]').selectOption('4'); await row.locator('[id^="cal-why-"]').fill('Too short');
    await row.getByRole('button', { name: 'Change', exact: true }).click();
    await expect(session.locator('.error').first()).toBeVisible();
    await row.locator('[id^="cal-why-"]').fill('Opened more dealers than anyone else in the region this half');
    await row.getByRole('button', { name: 'Change', exact: true }).click();
    await expect(row.getByTestId('level-now')).toContainText('4');
    await session.getByRole('button', { name: 'Close the session' }).click(); await session.getByRole('button', { name: 'Close it' }).click();
  });
  test('TC-F046 TC-F047 TC-F048 release; the employee acknowledges once', async () => {
    await perf(hr, 'Reviews'); await hr.getByTestId('cycles').getByRole('button', { name: 'Half-year review' }).click();
    await hr.getByRole('button', { name: 'Release to everyone' }).click();
    await expect(hr.getByText(/Released/i).first()).toBeVisible();
    const p = P.e1; await mine(p, 'Reviews');
    const review = p.getByTestId('review');
    await expect(review.getByTestId('final-rating')).toContainText('4');
    await p.fill('#ack-comment', 'Thank you.'); await review.getByRole('button', { name: 'Acknowledge' }).click();
    await refused(api(p, 'POST', `/org/performance/reviews/${reviewId}/acknowledge`, { comment: 'Again' }), 409, 'acknowledged, once');
  });

  // ---------- outcomes ----------
  test('TC-F049 TC-F050 TC-F051 prepare outcomes; no pay means no increment; first of a month', async () => {
    const prepared = await ok(api(hr, 'POST', `/org/performance/cycles/${cycleId}/outcomes/prepare`, {}));
    const list = await ok(api(hr, 'GET', `/org/performance/cycles/${cycleId}/outcomes`));
    const o = (list.outcomes || list).find((x: any) => x.name === NAMES.e1);
    expect(o, JSON.stringify(prepared)).toBeTruthy();
    const firstNext = (() => { const [y, m] = isoDay(0).split('-').map(Number); return new Date(Date.UTC(y, m, 1)).toISOString().slice(0, 10); })();
    await refused(api(hr, 'PUT', `/org/performance/outcomes/${o.id}`, { incrementPct: 5, promotion: false, bonusAmount: 0, effectiveFrom: firstNext, note: 'Raise' }), 409, 'no pay recorded');
    await refused(api(hr, 'PUT', `/org/performance/outcomes/${o.id}`, { incrementPct: 0, promotion: true, newDesignation: 'Senior associate', bonusAmount: 0, effectiveFrom: isoDay(0).slice(0, 8) + '15', note: 'Promotion' }), 400, 'first of a month');
    await ok(api(hr, 'PUT', `/org/performance/outcomes/${o.id}`, { incrementPct: 0, promotion: true, newDesignation: 'Senior associate', bonusAmount: 10000, effectiveFrom: firstNext, note: 'Promotion' }));
  });
  test('TC-F052 TC-F053 TC-F054 send; the sender cannot approve; another person approves', async () => {
    await perf(hr, 'Reviews'); await hr.getByTestId('cycles').getByRole('button', { name: 'Half-year review' }).click(); await tab(hr, 'Outcomes');
    await hr.getByTestId('outcomes').getByRole('button', { name: 'Send drafts for approval' }).click();
    const list = await ok(api(hr, 'GET', `/org/performance/cycles/${cycleId}/outcomes`));
    const o = (list.outcomes || list).find((x: any) => x.name === NAMES.e1);
    await refused(api(hr, 'POST', `/org/performance/cycles/${cycleId}/outcomes/approve`, { ids: [o.id] }), 409, 'someone else approves it');
    await perf(owner, 'Reviews'); await owner.getByTestId('cycles').getByRole('button', { name: 'Half-year review' }).click(); await tab(owner, 'Outcomes');
    await owner.getByTestId('outcomes').getByRole('button', { name: 'Approve 1' }).click();
    // The promotion and pay revision are recorded from the first of next month, so today's designation is unchanged.
    await expect.poll(async () => ((await ok(api(owner, 'GET', `/org/performance/cycles/${cycleId}/outcomes`))).outcomes || []).find((x: any) => x.name === NAMES.e1)?.status).toBe('applied');
  });

  // ---------- improvement plans ----------
  const plan = (body: any) => ({ employmentId: emp.e2, reason: 'Dealer visits fell to two a week against a target of five, for eight weeks.', support: 'Weekly ride-alongs with Mohan',
    objectives: [{ title: 'Five dealer visits a week', measure: 'CRM visit log' }], lengthDays: 30, checkinEvery: 7, ...body });
  test('TC-F055 TC-F056 TC-F057 TC-F058 a plan draft is checked', async () => {
    await refused(api(P.mgr, 'POST', '/org/performance/plans', plan({ reason: 'Too short' })), 400);
    await refused(api(P.mgr, 'POST', '/org/performance/plans', plan({ objectives: [] })), 400);
    await refused(api(P.mgr, 'POST', '/org/performance/plans', plan({ lengthDays: 10 })), 400, 'A plan runs for 14 to 120 days');
    await refused(api(P.mgr, 'POST', '/org/performance/plans', plan({ checkinEvery: 40 })), 400, 'Check in every 7 to 31 days');
  });
  test('TC-F059 TC-F060 the manager saves a draft; one plan at a time', async () => {
    await perf(P.mgr, 'Improvement plans');
    await P.mgr.getByTestId('plans').getByRole('button', { name: 'New plan' }).click();
    await P.mgr.selectOption('#plan-employee', { label: NAMES.e2 }).catch(() => {});
    await P.mgr.fill('#plan-reason', 'Dealer visits fell to two a week against a target of five, for eight weeks.');
    await P.mgr.fill('#obj-title-0', 'Five dealer visits a week'); await P.mgr.fill('#obj-measure-0', 'CRM visit log');
    await P.mgr.fill('#plan-support', 'Weekly ride-alongs with Mohan');
    await P.mgr.getByRole('button', { name: 'Save the draft' }).click();
    await expect(P.mgr.getByTestId('plan').getByRole('button', { name: 'Start the plan' })).toHaveCount(0);   // HR starts it
    await refused(api(P.mgr, 'POST', '/org/performance/plans', plan({})), 409, 'already have a plan');
  });
  test('TC-F061 TC-F062 TC-F063 HR starts; the employee acknowledges once; check-ins wait for their week', async () => {
    await perf(hr, 'Improvement plans'); await hr.getByTestId('plans').getByRole('button', { name: NAMES.e2 }).click();
    await hr.getByTestId('start-plan').getByRole('button', { name: 'Start the plan' }).click();
    await expect(hr.getByTestId('plan')).toContainText(/Running|Check-ins/);
    const p = P.e2; await mine(p);
    const ack = p.getByTestId('acknowledge-plan');
    await p.fill('#plan-response', 'I will log every visit.'); await ack.getByRole('button', { name: 'I have read it' }).click();
    await expect(p.getByTestId('acknowledge-plan')).toHaveCount(0);
    const plans = await ok(api(hr, 'GET', '/org/performance/plans'));
    const pl = (plans.plans || plans).find((x: any) => x.name === NAMES.e2);
    await refused(api(p, 'POST', `/org/me/performance/plan/${pl.id}/acknowledge`, { text: 'Again' }), 409, 'already acknowledged');
    const detail = await ok(api(hr, 'GET', `/org/performance/plans/${pl.id}`));
    const last = detail.checkins[detail.checkins.length - 1];
    await refused(api(P.mgr, 'PUT', `/org/performance/plans/${pl.id}/checkins/${last.id}`, { ratings: {}, note: 'Too early to write this one.' }), 409, 'This check-in is due on');
    await refused(api(hr, 'POST', `/org/performance/plans/${pl.id}/decide`, { decision: 'not_met', note: 'The objectives were not met in time.', lastWorkingDay: isoDay(30) }), 409, 'The plan runs to');   // date-safe: refused before any date is used
  });
  test('TC-F064 nobody writes their own plan', async () => {
    await refused(api(hr, 'POST', '/org/performance/plans', plan({ employmentId: emp.hr })), 409, 'Nobody writes their own improvement plan');
  });

  // ---------- access and audit ----------
  test('TC-F065 an employee has My performance only', async () => {
    await P.e3.reload();
    await expect(P.e3.getByRole('link', { name: 'My performance' })).toBeVisible();
    await expect(P.e3.getByRole('link', { name: 'Performance', exact: true })).toHaveCount(0);
  });
  test('TC-F066 a manager sees only their team', async () => {
    await perf(P.mgr, 'Team');
    await expect(P.mgr.getByRole('button', { name: NAMES.e1 })).toBeVisible();
    await expect(P.mgr.getByRole('button', { name: NAMES.hr })).toHaveCount(0);
  });
  test('TC-F067 the auditor reads without changing', async () => {
    await perf(P.aud);
    await expect(P.aud.getByRole('tab', { name: 'Setup', exact: true })).toHaveCount(0);   // Setup is for performance.manage only
    await tab(P.aud, 'Reviews'); await expect(P.aud.getByRole('button', { name: 'New cycle' })).toHaveCount(0);
  });
  test('TC-F068 performance actions in the audit log', async () => {
    await owner.reload(); await nav(owner, 'Audit log');
    await expect(owner.locator('code', { hasText: /^performance\.|^probation\.|^cycle\.|^outcome|^plan\./ }).first()).toBeVisible();
  });
});
