import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, shot as save } from './helpers';
import { join } from './join';

// The screenshots in docs/user-guide/performance.md, taken in a demo Organization of their own (Northwind Sales),
// so they show tidy names. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.

const em = (n: string) => `${n}@sales.example`;
async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}

test('the performance guide, screen by screen', async ({ browser }) => {
  test.setTimeout(420_000);
  const admin = await newPage(browser); await admin.goto('/');
  await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
  await expect(admin.getByText('Platform console')).toBeVisible();
  const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Northwind Sales', slug: `northwind-sales${process.env.E2E_RUN ?? ''}`, adminEmail: em('owner') }));
  await admin.context().close();
  const owner = await join(browser, org.invitationToken, em('owner'));
  const pg: Record<string, Page> = {};
  const roles = { hr: ['hr_admin', 'employee'], anil: ['employee', 'manager'], riya: ['employee'], kabir: ['employee'], meera: ['employee'] } as const;
  for (const [n, r] of Object.entries(roles)) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles: r }));
    pg[n] = await join(browser, inv.token, em(n));
  }
  const { hr, anil, riya, kabir, meera } = pg;
  const id = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
  const add = (body: any) => ok(api(owner, 'POST', '/org/people/employees', { joiningDate: isoDay(-400), ...body }));   // date-safe: joined long ago
  await add({ legalName: 'Farah Siddiqui', designation: 'HR manager', membershipId: id[em('hr')] });
  const a = await add({ legalName: 'Anil Kapoor', designation: 'Area sales manager', membershipId: id[em('anil')] });
  await add({ legalName: 'Riya Sharma', designation: 'Sales executive', managerId: a.id, membershipId: id[em('riya')] });
  const k = await add({ legalName: 'Kabir Mehta', designation: 'Sales executive', managerId: a.id, membershipId: id[em('kabir')] });
  await add({ legalName: 'Meera Iyer', designation: 'Sales trainee', employmentType: 'probation', joiningDate: isoDay(-80), probationEnd: isoDay(20), managerId: a.id, membershipId: id[em('meera')] });   // date-safe: probation check-ins count from joining

  const shot = async (p: Page, name: string, fn: () => Promise<void>) => { await fn(); await save(p, `performance-${name}`); };
  for (const p of [hr, anil, riya, meera]) await p.setViewportSize({ width: 1280, height: 860 });
  const perf = async (p: Page, t?: string) => { await p.reload(); await nav(p, 'Performance'); if (t) await tab(p, t); };
  const mine = async (p: Page, t?: string) => { await p.reload(); await nav(p, 'My performance'); if (t) await tab(p, t); };

  await shot(hr, '01-setup', async () => { await perf(hr, 'Setup'); await expect(hr.getByTestId('rating-scale')).toBeVisible(); });

  // Riya sets her goals; Anil agrees them.
  await mine(riya, 'Goals');
  const goals = riya.getByTestId('goals');
  for (const [title, w] of [['Grow Pune sales by 12%', '60'], ['Sign 10 new retailers', '40']]) {
    await goals.getByRole('button', { name: 'Add a goal' }).click();
    await riya.fill('#goal-new-title', title); await riya.selectOption('#goal-new-weight', w);
    await goals.getByRole('button', { name: 'Add the goal' }).click();
  }
  await goals.getByRole('button', { name: 'Send to my manager' }).click(); await expect(goals).toContainText('Waiting to be agreed');
  await shot(anil, '03-team', async () => {
    await perf(anil, 'Team'); await anil.getByRole('button', { name: 'Riya Sharma' }).click();
    await anil.getByTestId('goals').getByRole('button', { name: 'Agree the goals' }).click();
    await expect(anil.getByTestId('goals')).toContainText(/Agreed/i);
  });
  await shot(riya, '02-my-goals', async () => { await mine(riya, 'Goals'); await expect(riya.getByTestId('goals')).toContainText(/Agreed/i); });

  await ok(api(kabir, 'POST', '/org/performance/feedback', { employmentId: (await ok(api(hr, 'GET', '/org/people/employees?status=all'))).find((e: any) => e.legalName === 'Riya Sharma').id,
    kind: 'praise', text: 'Thanks for sharing your retailer list with me', shareWithManager: true }));
  await shot(riya, '04-feedback', async () => { await mine(riya, 'Feedback'); await expect(riya.getByTestId('feedback-received')).toContainText('retailer list'); });

  // Anil writes Meera's day-30 probation check-in.
  await shot(anil, '05-probation', async () => {
    await perf(anil, 'Probation'); await anil.getByRole('button', { name: 'Meera Iyer' }).click();
    const day30 = anil.locator('[data-checkin="30"]');
    await day30.getByRole('button', { name: 'Write the check-in' }).click();
    await day30.getByLabel('On track', { exact: true }).check();
    await day30.locator('textarea').fill('Picks up the product range quickly; good with retailers.');
    await day30.getByRole('button', { name: 'Save the check-in' }).click();
    await expect(day30).toContainText('good with retailers');
  });
  await shot(meera, '06-my-probation', async () => { await meera.setViewportSize({ width: 1280, height: 860 }); await mine(meera); await expect(meera.getByTestId('my-probation')).toBeVisible(); });

  // HR sets up and starts a review cycle.
  await perf(hr, 'Reviews');
  await hr.getByTestId('cycles').getByRole('button', { name: 'New cycle' }).click();
  await hr.fill('#cy-name', 'Annual review 2026'); await hr.fill('#cy-joined', isoDay(0));
  await shot(hr, '07-new-cycle', async () => { await expect(hr.locator('#cy-name')).toBeVisible(); });
  await hr.getByRole('button', { name: 'Save' }).click(); await expect(hr.getByTestId('eligible')).toContainText('Starting creates a review for');
  await hr.getByRole('button', { name: 'Start the cycle' }).click(); await expect(hr.getByTestId('cycle-progress')).toBeVisible();
  const cycle0 = (await ok(api(hr, 'GET', '/org/performance/cycles'))).find((c: any) => c.name === 'Annual review 2026');
  const cycle = await ok(api(hr, 'GET', `/org/performance/cycles/${cycle0.id}`));
  for (const r of cycle.reviews.filter((x: any) => x.name !== 'Riya Sharma' && x.status !== 'withdrawn')) {
    await ok(api(hr, 'POST', `/org/performance/reviews/${r.id}/withdraw`, { reason: 'Only Riya is shown in the guide' }));
  }

  // Riya's self review, then Anil's.
  await mine(riya, 'Reviews');
  const self = riya.getByTestId('review');
  for (const g of ['Grow Pune sales by 12%', 'Sign 10 new retailers']) await self.locator(`[data-review-goal="${g}"]`).getByText('Meets expectations', { exact: true }).click();
  const sq = riya.locator('[id^="self-q-"]'); const selfAnswers = ['Pune grew 11% and I signed nine retailers.', 'Start retailer visits earlier in the quarter.', 'A second demo kit for the north side.'];
  for (let i = 0; i < await sq.count(); i++) await sq.nth(i).fill(selfAnswers[i % 3]);
  await shot(riya, '08-self-review', async () => { await self.scrollIntoViewIfNeeded(); });
  await self.getByRole('button', { name: 'Send to my manager' }).click(); await expect(self).toContainText('With your manager');

  await perf(anil, 'Reviews'); await anil.getByTestId('reviews-to-write').getByRole('button', { name: 'Riya Sharma' }).click();
  const mr = anil.getByTestId('review');
  for (const g of ['Grow Pune sales by 12%', 'Sign 10 new retailers']) await mr.locator(`[data-review-goal="${g}"]`).getByText('Meets expectations', { exact: true }).click();
  const mq = anil.locator('[id^="manager-q-"]'); const mgrAnswers = ['Opened nine retailers and kept the top ten buying every month.', 'Plan visits a month ahead and build the retailer base outside Pune.'];
  for (let i = 0; i < await mq.count(); i++) await mq.nth(i).fill(mgrAnswers[i % 2]);
  await shot(anil, '09-manager-review', async () => { await mr.getByTestId('worked-out').scrollIntoViewIfNeeded(); });
  await mr.getByRole('button', { name: 'Send the review' }).click(); await expect(mr).toContainText(/Sign-off|Calibration/);

  // Calibration, release and outcomes.
  await ok(api(hr, 'POST', `/org/performance/cycles/${cycle0.id}/calibrate`, {}));
  await perf(hr, 'Reviews'); await hr.getByTestId('cycles').getByRole('button', { name: 'Annual review 2026' }).click(); await tab(hr, 'Calibration');
  await hr.fill('#session-name', 'Sales calibration'); await hr.getByTestId('calibration').getByRole('button', { name: 'Open a session' }).click();
  const session = hr.getByTestId('calibration-session');
  await shot(hr, '10-calibration', async () => { await expect(session.locator('tr', { hasText: 'Riya Sharma' })).toBeVisible(); });
  await session.getByRole('button', { name: 'Close the session' }).click(); await session.getByRole('button', { name: 'Close it' }).click();
  await perf(hr, 'Reviews'); await hr.getByTestId('cycles').getByRole('button', { name: 'Annual review 2026' }).click();
  await hr.getByRole('button', { name: 'Release to everyone' }).click(); await expect(hr.getByText(/Released/i).first()).toBeVisible();
  await ok(api(hr, 'POST', `/org/performance/cycles/${cycle0.id}/outcomes/prepare`, {}));
  await shot(hr, '11-outcomes', async () => {
    await perf(hr, 'Reviews'); await hr.getByTestId('cycles').getByRole('button', { name: 'Annual review 2026' }).click(); await tab(hr, 'Outcomes');
    await expect(hr.getByTestId('outcomes')).toContainText('Riya Sharma');
  });

  // An improvement plan for Kabir: Anil drafts it, HR starts it.
  await ok(api(anil, 'POST', '/org/performance/plans', { employmentId: k.id, reason: 'Retailer visits fell to two a week against a target of five, for eight weeks.',
    support: 'Weekly ride-alongs with Anil', objectives: [{ title: 'Five retailer visits a week', measure: 'CRM visit log' }], lengthDays: 60, checkinEvery: 14 }));
  await perf(hr, 'Improvement plans'); await hr.getByTestId('plans').getByRole('button', { name: 'Kabir Mehta' }).click();
  await hr.getByTestId('start-plan').getByRole('button', { name: 'Start the plan' }).click();
  await shot(hr, '12-plan', async () => { await expect(hr.getByTestId('plan')).toContainText(/Running|Check-ins/); });
});
