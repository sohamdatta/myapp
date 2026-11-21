import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, shot as save } from './helpers';
import { join } from './join';

// The screenshots in docs/user-guide/expenses.md, taken in a demo Organization of their own (Northwind Freight),
// and the claim's journey from draft to paid. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.

const RUN = process.env.E2E_RUN || '';
const em = (n: string) => `${n}@freight${RUN}.example`;

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 201, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}

test('the expenses guide, screen by screen', async ({ browser }) => {
  test.setTimeout(360_000);
  const admin = await newPage(browser); await admin.goto('/');
  await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
  await expect(admin.getByText('Platform console')).toBeVisible();
  const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Northwind Freight', slug: `northwind-freight${RUN}`, adminEmail: em('owner') }));
  await admin.context().close();
  const owner = await join(browser, org.invitationToken, em('owner'));
  await ok(api(owner, 'POST', '/org/setup/starter', { starter: 'office', locationName: 'Pune office', stateCode: 'MH' }));
  const pg: Record<string, Page> = {};
  for (const [n, roles] of Object.entries({ fin: ['finance', 'employee'], mgr: ['employee', 'manager'], esha: ['employee'] })) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles }));
    pg[n] = await join(browser, inv.token, em(n));
  }
  const ids = Object.fromEntries((await ok(api(owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
  const add = (body: any) => ok(api(owner, 'POST', '/org/people/employees', { joiningDate: isoDay(-400), ...body }));   // date-safe: joined long ago
  await add({ legalName: 'Nisha Kulkarni', designation: 'Finance lead', membershipId: ids[em('fin')] });
  const vivek = await add({ legalName: 'Vivek Joshi', designation: 'Operations manager', membershipId: ids[em('mgr')] });
  const esha = await add({ legalName: 'Esha Patil', designation: 'Dispatcher', managerId: vivek.id, membershipId: ids[em('esha')] });
  await ok(api(owner, 'PUT', `/org/people/employees/${esha.id}/bank`, { holderName: 'Esha Patil', accountNumber: '50100234567891', ifsc: 'HDFC0000123',
    bankName: 'HDFC Bank', branch: 'Pune', effectiveFrom: isoDay(-300) }));   // date-safe: an account on record long before any claim
  for (const p of Object.values(pg)) await p.setViewportSize({ width: 1280, height: 1100 });
  const expenses = async (p: Page, t: string) => { await p.reload(); await nav(p, 'Expenses'); await tab(p, t); };

  for (const c of [{ name: 'Travel', limitPerExpense: 5000, limitPerMonth: 20000, receiptAbove: 500, ledger: 'Travel and conveyance' },
    { name: 'Meals', limitPerExpense: 1000, limitPerMonth: 6000, receiptAbove: 300, ledger: 'Staff welfare' },
    { name: 'Internet and phone', limitPerExpense: 2000, limitPerMonth: 2000, receiptAbove: 0, ledger: 'Communication' }]) {
    await ok(api(owner, 'POST', '/org/expenses/categories', { needsTravelProof: false, perKmRate: null, ...c }));
  }
  await expenses(owner, 'Categories');
  await expect(owner.locator('main')).toContainText('Travel');
  await save(owner, 'expenses-01-categories');
  await expenses(owner, 'Approvers');
  await save(owner, 'expenses-02-approvers');

  // Esha starts a claim and adds a line.
  const cats = await ok(api(pg.esha, 'GET', '/org/expenses/categories'));
  const travel = cats.find((c: any) => c.name === 'Travel');
  await expenses(pg.esha, 'My claims');
  await pg.esha.fill('#new-claim', 'Client visit, Nashik');
  await pg.esha.getByRole('button', { name: 'Start' }).click();
  await expect(pg.esha.locator('#line-cat')).toBeVisible();
  await pg.esha.selectOption('#line-cat', travel.id);
  await pg.esha.fill('#line-date', isoDay(-3));
  await pg.esha.fill('#line-amount', '420');
  await pg.esha.fill('#line-merchant', 'MSRTC bus, Pune to Nashik');
  await save(pg.esha, 'expenses-03-new-line');
  await pg.esha.getByRole('button', { name: 'Add line' }).click();
  await expect(pg.esha.locator('main')).toContainText('420');
  await save(pg.esha, 'expenses-04-claim-draft');
  const mine = await ok(api(pg.esha, 'GET', '/org/expenses/claims'));
  const detail = await ok(api(pg.esha, 'GET', `/org/expenses/claims/${mine[0].id}`));
  const lineId = detail.lines[0].id;
  // A receipt: the bytes of a tiny picture, sent as the screen does.
  const up = await pg.esha.evaluate(async ({ lineId }) => {
    const bytes = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='), (ch) => ch.charCodeAt(0));
    const r = await fetch(`/api/org/expenses/lines/${lineId}/files?kind=receipt&name=ticket.png`, {
      method: 'POST', headers: { Authorization: `Bearer ${sessionStorage.getItem('hrms-token')}`, 'Content-Type': 'image/png' }, body: bytes });
    return r.status;
  }, { lineId });
  expect([200, 201, 204]).toContain(up);
  await expenses(pg.esha, 'My claims');
  await pg.esha.getByRole('button', { name: /Client visit/ }).dispatchEvent('click');
  await save(pg.esha, 'expenses-05-claim-ready');
  await pg.esha.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(pg.esha.locator('main')).toContainText('With your manager');
  await save(pg.esha, 'expenses-06-submitted');

  // The manager decides.
  await expenses(pg.mgr, 'Approvals');
  await pg.mgr.getByRole('button', { name: /Client visit/ }).dispatchEvent('click');
  await save(pg.mgr, 'expenses-07-manager-claim');
  await pg.mgr.getByRole('button', { name: 'Approve', exact: true }).click();

  // Finance verifies, then pays through a batch.
  await expenses(pg.fin, 'Finance');
  await pg.fin.getByRole('button', { name: /Client visit/ }).dispatchEvent('click');
  await save(pg.fin, 'expenses-08-finance-claim');
  await pg.fin.getByRole('button', { name: 'Verify', exact: true }).click();
  await expenses(pg.fin, 'Payments');
  await save(pg.fin, 'expenses-09-payments');
  await pg.fin.reload(); await nav(pg.fin, 'Expenses'); await tab(pg.fin, 'Payments');
  await pg.fin.getByLabel(/Include EXP/).check();
  await pg.fin.getByRole('button', { name: /Make a batch of 1/ }).dispatchEvent('click');
  await expect(pg.fin.locator('main')).toContainText('Mark paid');
  await save(pg.fin, 'expenses-10-batch');
  await pg.fin.getByRole('button', { name: 'Mark paid', exact: true }).first().dispatchEvent('click');
  await pg.fin.fill('#bank-reference', 'UTR-HDFC-884210');
  await save(pg.fin, 'expenses-11-mark-paid');
  await pg.fin.getByRole('button', { name: 'Mark paid', exact: true }).last().click();
  await expect(pg.fin.locator('main')).toContainText('Paid');
  await expenses(pg.esha, 'My claims');
  await expect(pg.esha.locator('main')).toContainText('Paid');
  await save(pg.esha, 'expenses-12-paid');

  // Advances and loans: finance sets up a type, Esha asks, the manager and finance decide, finance records the payout.
  await expenses(pg.fin, 'Loan types');
  await pg.fin.fill('#lt-name', 'Salary advance');
  await pg.fin.selectOption('#lt-kind', 'salary_advance');
  await pg.fin.fill('#lt-amount', '30000');
  await pg.fin.fill('#lt-months', '3');
  await pg.fin.getByRole('button', { name: 'Add type' }).click();
  await expect(pg.fin.locator('main')).toContainText('Salary advance');
  await save(pg.fin, 'expenses-13-loan-types');

  await expenses(pg.esha, 'Advances and loans');
  await pg.esha.selectOption('#loan-type', { label: 'Salary advance' }).catch(async () => { await pg.esha.selectOption('#loan-type', { index: 1 }); });
  await pg.esha.fill('#loan-amount-ask', '15000');
  await pg.esha.fill('#loan-months', '3');
  await pg.esha.fill('#loan-purpose', 'Deposit for a rented flat');
  await pg.esha.getByRole('button', { name: 'Show the repayments' }).click();
  await expect(pg.esha.locator('main')).toContainText('5,000');
  await save(pg.esha, 'expenses-14-loan-request');
  await pg.esha.getByRole('button', { name: 'Send request' }).click();
  await expect(pg.esha.locator('main')).toContainText('LN-');

  await expenses(pg.mgr, 'Loan approvals');
  await pg.mgr.getByRole('button', { name: /LN-/ }).dispatchEvent('click');
  await save(pg.mgr, 'expenses-15-loan-manager');
  await pg.mgr.getByRole('button', { name: 'Approve', exact: true }).click();

  await expenses(pg.fin, 'Loans');
  await pg.fin.getByRole('button', { name: /LN-/ }).dispatchEvent('click');
  await pg.fin.getByRole('button', { name: 'Approve', exact: true }).click();
  await expenses(pg.fin, 'Loans');
  await pg.fin.getByRole('button', { name: /LN-/ }).dispatchEvent('click');
  await pg.fin.fill('#loan-ref', 'UTR-HDFC-884377');
  await save(pg.fin, 'expenses-16-loan-payout');
  await pg.fin.getByRole('button', { name: 'Record payout' }).click();
  await expect(pg.fin.locator('main')).toContainText('5,000');
  await save(pg.fin, 'expenses-17-loan-schedule');

  // An expense advance: money given before a trip, settled by the claims that follow.
  await ok(api(pg.fin, 'POST', '/org/loans/types', { name: 'Trip advance', kind: 'expense_advance', maxAmount: 20000, maxMonths: 2, annualRate: 0,
    minServiceMonths: 0, maxOpen: 1, active: true }));
  const adv = (await ok(api(pg.esha, 'GET', '/org/loans/types'))).find((t: any) => t.name === 'Trip advance');
  const ask = await ok(api(pg.esha, 'POST', '/org/loans', { typeId: adv.id, amount: 8000, months: 1, purpose: 'Site visit, Satara' }));
  let view = await ok(api(pg.mgr, 'GET', `/org/loans/${ask.id}`));
  await ok(api(pg.mgr, 'POST', `/org/loans/${ask.id}/manager`, { action: 'approve', note: null, version: view.version }));
  view = await ok(api(pg.fin, 'GET', `/org/loans/${ask.id}`));
  await ok(api(pg.fin, 'POST', `/org/loans/${ask.id}/finance`, { action: 'approve', note: null, version: view.version }));
  view = await ok(api(pg.fin, 'GET', `/org/loans/${ask.id}`));
  await ok(api(pg.fin, 'POST', `/org/loans/${ask.id}/disburse`, { paidOn: isoDay(0), firstMonth: null, reference: 'UTR-HDFC-884400', version: view.version }));
  await expenses(pg.esha, 'My claims');
  await pg.esha.fill('#new-claim', 'Site visit, Satara');
  await pg.esha.getByRole('button', { name: 'Start' }).click();
  await expect(pg.esha.locator('#claim-advance')).toBeVisible();
  await save(pg.esha, 'expenses-18-advance-choice');
  await pg.esha.selectOption('#claim-advance', { index: 1 });
  await expect(pg.esha.locator('main')).toContainText(/Set against advance LN-\d{4}-\d+/);   // it was 'Trip advance' once, which this screen has never shown; .catch hid that
  await save(pg.esha, 'expenses-19-advance-chosen');
});
