import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, PASSWORD, newPage, nav, tab, api, isoDay } from './helpers';
import { join } from './join';

// The expenses test cases (docs/testing/expenses-ui-test-cases.xlsx; the TC ids are its rows), in an Organization of its own,
// Harbour Logistics. Where a case is about a screen it is driven on the screen; where it is about a rule (who may, what is refused)
// the screen sets it up through the same API the screen uses and the result is checked on the screen or in what comes back.
// Not driven here: the cases that need a payroll run, the leaver's settlement or the daily job (E009, E010, E035, E036, E043 to E046, E049,
// E054, E055, E057), and E022, E028 and E031. They are covered at service level in ExpensesTest. E034 is checked inside E033.
// Esha and Ravi report to Vivek (Manager). Nisha and Farid are in Finance. Sunil has no manager. Owner is the Org Admin.

const RUN = process.env.E2E_RUN || '';
const em = (n: string) => `${n}@harbour${RUN}.example`;
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

test.describe.configure({ mode: 'serial' });

// E2E_KEEP_GOING=1 reports a failed case and goes on to the next, so one run shows every case that needs attention.
function tc(title: string, fn: (args: { browser: any }) => Promise<void>) {
  test(title, async ({ browser }) => {
    try { await fn({ browser }); } catch (e: any) {
      if (!process.env.E2E_KEEP_GOING) throw e;
      console.log(`CASE FAILED ${title}\n    ${String(e.message).replace(/\x1b\[[0-9;]*m/g, '').slice(0, 380).replace(/\n/g, ' | ')}`);
    }
  });
}

let owner: Page;
const P: Record<string, Page> = {};
const ID: Record<string, string> = {};     // membership ids
const EMP: Record<string, string> = {};    // employee ids
const CAT: Record<string, string> = {};    // category ids
let typeSalary = '';
let eshaLoan = '';
let exemptNumber = '';

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 201, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function refused(r: Promise<{ status: number; body: any }>, status: number | number[], code?: string) {
  const done = await r;
  expect([status].flat(), JSON.stringify(done.body)).toContain(done.status);
  if (code) expect(String(done.body.code) + ' ' + String(done.body.message)).toContain(code);
  return done.body;
}
const EX = '/org/expenses';
const LN = '/org/loans';
const pw = (value: unknown, reason: string) => ({ scope: 'organization', scopeId: null, validFrom: null, value, reset: false, reason, password: PASSWORD });
async function setting(key: string, value: unknown) {
  await ok(api(owner, 'PUT', `/org/settings/${key}`, pw(value, 'Changed for a test case')));
  // A change that weakens a control waits for a second owner (Boss) to approve it.
  const held = await ok(api(P.boss, 'GET', '/org/settings/changes'));
  for (const c of (Array.isArray(held) ? held : []).filter((h: any) => h.status === 'pending')) await ok(api(P.boss, 'POST', `/org/settings/changes/${c.id}/approve`, { note: 'Approved for a test case', password: PASSWORD }));
}
async function claim(p: Page, title: string) { return (await ok(api(p, 'POST', `${EX}/claims`, { title }))).id as string; }
async function line(p: Page, id: string, cat: string, amount: number, extra: any = {}, daysAgo = 3) {
  return ok(api(p, 'POST', `${EX}/claims/${id}/lines`, { categoryId: CAT[cat], spentOn: isoDay(-daysAgo), amount, km: null, merchant: 'Shop', note: null, overLimitReason: null, ...extra }));
}
const version = async (p: Page, id: string) => (await ok(api(p, 'GET', `${EX}/claims/${id}`))).version as number;
const detail = async (p: Page, id: string) => ok(api(p, 'GET', `${EX}/claims/${id}`));
const submit = async (p: Page, id: string) => ok(api(p, 'POST', `${EX}/claims/${id}/submit`, { version: await version(p, id) }));
const decide = async (p: Page, id: string, action = 'approve', note: string | null = 'Checked', cuts: any[] | null = null) =>
  ok(api(p, 'POST', `${EX}/claims/${id}/decision`, { action, note, version: await version(p, id), cuts }));
/** Finance accepts the evidence on every line that has any, then verifies. */
const accept = async (p: Page, id: string) => {
  for (const l of (await detail(p, id)).lines) {
    if (l.files.length && l.review !== 'accepted') await ok(api(p, 'POST', `${EX}/lines/${l.id}/review`, { status: 'accepted', note: null, version: await version(p, id) }));
  }
};
const verify = async (p: Page, id: string) => {
  await accept(p, id);
  return ok(api(p, 'POST', `${EX}/claims/${id}/verify`, { version: await version(p, id), note: 'Checked' }));
};
async function upload(p: Page, lineId: string, name = 'ticket.png') {
  return p.evaluate(async ({ lineId, name, b64 }) => {
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    const r = await fetch(`/api/org/expenses/lines/${lineId}/files?kind=receipt&name=${name}`, {
      method: 'POST', headers: { Authorization: `Bearer ${sessionStorage.getItem('hrms-token')}`, 'Content-Type': 'image/png' }, body: bytes });
    return r.status;
  }, { lineId, name, b64: PNG.toString('base64') });
}
/** A claim of one small line, taken through to the state asked for. */
let counter = 0;
async function claimTo(who: string, state: 'draft' | 'submitted' | 'approved' | 'verified', title = 'A claim', amount = 0) {
  counter += 1;
  const id = await claim(P[who], title);
  await line(P[who], id, 'Meals', amount || 150 + counter * 7, {}, 1 + (counter % 25));
  if (state === 'draft') return id;
  await submit(P[who], id);
  if (state === 'submitted') return id;
  if ((await detail(P[who], id)).status === 'submitted') await decide(P.vivek, id);
  if (state === 'approved') return id;
  await verify(P.nisha, id);
  return id;
}
async function open(p: Page, area: string) { await p.reload(); await nav(p, 'Expenses'); await tab(p, area); }
async function openClaim(p: Page, name: RegExp | string) { await p.getByRole('button', { name }).dispatchEvent('click'); }
async function loanType(p: Page, name: string, extra: any = {}) {
  return (await ok(api(p, 'POST', `${LN}/types`, { name, kind: 'salary_advance', maxAmount: 30000, maxMonths: 3, annualRate: 0, minServiceMonths: 0, maxOpen: 3, active: true, ...extra }))).id as string;
}
async function loanTo(who: string, typeId: string, amount: number, months: number, state: 'requested' | 'approved' | 'active') {
  const made = await ok(api(P[who], 'POST', LN, { typeId, amount, months, purpose: 'A purpose for the test' }));
  const v = async () => (await ok(api(P.nisha, 'GET', `${LN}/${made.id}`))).version as number;
  if (state === 'requested') return made.id as string;
  await ok(api(P.vivek, 'POST', `${LN}/${made.id}/manager`, { action: 'approve', note: null, version: await v() }));
  await ok(api(P.nisha, 'POST', `${LN}/${made.id}/finance`, { action: 'approve', note: null, version: await v() }));
  if (state === 'approved') return made.id as string;
  await ok(api(P.nisha, 'POST', `${LN}/${made.id}/disburse`, { paidOn: isoDay(0), firstMonth: null, reference: 'UTR-TEST-0001', version: await v() }));
  return made.id as string;
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(300_000);
  const sa = await newPage(browser); await sa.goto('/');
  await sa.fill('#email', SUPER_ADMIN.email); await sa.fill('#password', SUPER_ADMIN.password); await sa.click('button.primary');
  await expect(sa.getByText('Platform console')).toBeVisible();
  const org = await ok(api(sa, 'POST', '/platform/organizations', { name: 'Harbour Logistics', slug: `harbour-logistics${RUN}`, adminEmail: em('owner') }));
  await sa.context().close();
  owner = await join(browser, org.invitationToken, em('owner'));
  await ok(api(owner, 'POST', '/org/setup/starter', { starter: 'office', locationName: 'Mumbai office', stateCode: 'MH' }));
  const people: Record<string, string[]> = { nisha: ['finance', 'employee'], farid: ['finance', 'employee'], vivek: ['employee', 'manager'],
    esha: ['employee'], ravi: ['employee'], sunil: ['employee'], boss: ['owner'], meera: ['employee', 'manager'] };
  for (const [n, roles] of Object.entries(people)) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles }));
    P[n] = await join(browser, inv.token, em(n));
    await P[n].setViewportSize({ width: 1280, height: 1100 });
  }
  for (const p of (await ok(api(owner, 'GET', '/org/attendance/assignments')))) ID[String(p.email).split('@')[0]] = p.membershipId;
  const add = async (key: string, legalName: string, designation: string, extra: any = {}) => {
    const e = await ok(api(owner, 'POST', '/org/people/employees', { joiningDate: isoDay(-400), legalName, designation, membershipId: ID[key], ...extra }));   // date-safe: joined long ago
    EMP[key] = e.id;
    await ok(api(owner, 'PUT', `/org/people/employees/${e.id}/bank`, { holderName: legalName, accountNumber: '50100234567' + (10 + Object.keys(EMP).length), ifsc: 'HDFC0000123',
      bankName: 'HDFC Bank', branch: 'Mumbai', effectiveFrom: isoDay(-300) }));   // date-safe: an account on record long before any claim
  };
  await add('nisha', 'Nisha Rao', 'Finance lead');
  await add('farid', 'Farid Khan', 'Accountant');
  await add('vivek', 'Vivek Joshi', 'Operations manager');
  await add('esha', 'Esha Patil', 'Dispatcher', { managerId: EMP.vivek });
  await add('ravi', 'Ravi Menon', 'Dispatcher', { managerId: EMP.vivek });
  await add('sunil', 'Sunil Shah', 'Driver');
  await add('meera', 'Meera Iyer', 'Warehouse manager');
  for (const c of [{ name: 'Meals', limitPerExpense: 1000, limitPerMonth: 90000, receiptAbove: 300, ledger: 'Staff welfare' },
    { name: 'Travel', limitPerExpense: 5000, limitPerMonth: 90000, receiptAbove: 1000, ledger: 'Travel and conveyance' },
    { name: 'Mileage', limitPerExpense: 5000, limitPerMonth: 20000, receiptAbove: 0, perKmRate: 10, ledger: 'Travel and conveyance' }]) {
    await ok(api(owner, 'POST', `${EX}/categories`, { perKmRate: null, needsTravelProof: false, needsApprovalFirst: false, ...c }));
  }
  for (const c of await ok(api(owner, 'GET', `${EX}/categories`))) CAT[c.name] = c.id;
  typeSalary = await loanType(P.nisha, 'Salary advance');
});

test.afterAll(async () => { for (const p of [owner, ...Object.values(P)]) await p?.context().close().catch(() => {}); });

// ── Settings ────────────────────────────────────────────────────────────────

tc('TC-E001 an expense inside the window is accepted', async () => {
  const id = await claimTo('esha', 'draft', 'Inside the window');
  const sent = await submit(P.esha, id);
  expect(sent.status).toBe('submitted');
});

tc('TC-E002 an expense older than the window, or dated in the future, is refused', async () => {
  // The window is checked when the claim is sent (or earlier, when the line is added): either way it never reaches the manager.
  for (const [days, title] of [[61, 'Too old'], [-1, 'In the future']] as const) {
    const id = await claim(P.esha, title);
    const added = await api(P.esha, 'POST', `${EX}/claims/${id}/lines`, { categoryId: CAT.Meals, spentOn: isoDay(-days), amount: 200, km: null, merchant: 'Shop', note: null, overLimitReason: null });
    if (added.status === 200 || added.status === 201) await refused(api(P.esha, 'POST', `${EX}/claims/${id}/submit`, { version: await version(P.esha, id) }), [400, 409]);
    else expect([400, 409]).toContain(added.status);
    expect((await detail(P.esha, id)).status).toBe('draft');
  }
});

tc('TC-E003 with the manager step off, a claim goes straight to finance', async () => {
  await setting('expenses.manager_step', false);
  const id = await claimTo('esha', 'submitted', 'No manager step');
  expect((await detail(P.esha, id)).status).toBe('approved');
  await open(P.vivek, 'Approvals');
  await expect(P.vivek.locator('main')).not.toContainText('No manager step');
  await open(P.nisha, 'Finance');
  await expect(P.nisha.locator('main')).toContainText('No manager step');
  await setting('expenses.manager_step', true);
});

tc('TC-E004 a claim above the amount needs two different people to verify', async () => {
  await setting('expenses.second_finance_above', 1000);
  const id = await claim(P.esha, 'Large claim');
  await line(P.esha, id, 'Meals', 900); await line(P.esha, id, 'Meals', 800, { merchant: 'Other' });
  const lines = (await detail(P.esha, id)).lines;
  for (const l of lines) expect([200, 201, 204]).toContain(await upload(P.esha, l.id));
  await submit(P.esha, id); await decide(P.vivek, id);
  await verify(P.nisha, id);
  expect((await detail(P.nisha, id)).status).toBe('approved');
  await refused(api(P.nisha, 'POST', `${EX}/claims/${id}/verify`, { version: await version(P.nisha, id), note: null }), 409);   // the same person again
  await verify(P.farid, id);
  expect((await detail(P.nisha, id)).status).toBe('verified');
  await setting('expenses.second_finance_above', 0);
});

tc('TC-E005 a person with no approver is refused when the setting says refuse', async () => {
  await setting('expenses.when_no_approver', 'refuse');
  const id = await claimTo('sunil', 'draft', 'Nobody to approve');
  await refused(api(P.sunil, 'POST', `${EX}/claims/${id}/submit`, { version: await version(P.sunil, id) }), 409, 'approver');
});

tc('TC-E006 a person with no approver goes to finance when the setting says so', async () => {
  await setting('expenses.when_no_approver', 'finance');
  const id = await claimTo('sunil', 'submitted', 'Straight to finance');
  expect((await detail(P.sunil, id)).status).toBe('approved');
});

tc('TC-E007 a loan above the amount needs two different people in finance', async () => {
  await setting('loans.second_finance_above', 10000);
  const made = await ok(api(P.ravi, 'POST', LN, { typeId: typeSalary, amount: 20000, months: 2, purpose: 'Large advance' }));
  const v = async () => (await ok(api(P.nisha, 'GET', `${LN}/${made.id}`))).version as number;
  await ok(api(P.vivek, 'POST', `${LN}/${made.id}/manager`, { action: 'approve', note: null, version: await v() }));
  await ok(api(P.nisha, 'POST', `${LN}/${made.id}/finance`, { action: 'approve', note: null, version: await v() }));
  expect((await ok(api(P.nisha, 'GET', `${LN}/${made.id}`))).status).toBe('manager_approved');
  await refused(api(P.nisha, 'POST', `${LN}/${made.id}/finance`, { action: 'approve', note: null, version: await v() }), 409);
  await ok(api(P.farid, 'POST', `${LN}/${made.id}/finance`, { action: 'approve', note: null, version: await v() }));
  expect((await ok(api(P.nisha, 'GET', `${LN}/${made.id}`))).status).toBe('approved');
});

tc('TC-E008 a loan under the amount needs one approval', async () => {
  const id = await loanTo('ravi', typeSalary, 9000, 2, 'approved');
  expect((await ok(api(P.nisha, 'GET', `${LN}/${id}`))).status).toBe('approved');
  await setting('loans.second_finance_above', 0);
});

// ── Categories ──────────────────────────────────────────────────────────────

tc('TC-E011 a new Organization starts with five categories, once', async ({ browser }) => {
  const sa = await newPage(browser); await sa.goto('/');
  await sa.fill('#email', SUPER_ADMIN.email); await sa.fill('#password', SUPER_ADMIN.password); await sa.click('button.primary');
  await expect(sa.getByText('Platform console')).toBeVisible();
  const org = await ok(api(sa, 'POST', '/platform/organizations', { name: 'Fresh Cargo', slug: `fresh-cargo${RUN}`, adminEmail: em('fresh') }));
  await sa.context().close();
  const fresh = await join(browser, org.invitationToken, em('fresh'));
  await ok(api(fresh, 'POST', '/org/setup/starter', { starter: 'office', locationName: 'Head office', stateCode: 'MH' }));
  await open(fresh, 'Categories');
  const first = await ok(api(fresh, 'GET', `${EX}/categories?all=true`));
  expect(first).toHaveLength(5);
  await ok(api(fresh, 'PUT', `${EX}/categories/${first[0].id}`, { ...first[0], active: false }));
  await open(fresh, 'Categories');
  const again = await ok(api(fresh, 'GET', `${EX}/categories?all=true`));
  expect(again).toHaveLength(5);
  expect(again.filter((c: any) => c.active)).toHaveLength(4);
  await fresh.context().close();
});

tc('TC-E012 categories are saved by finance and names are unique', async () => {
  const body = { name: 'Training', limitPerExpense: 3000, limitPerMonth: 9000, receiptAbove: 500, perKmRate: null, needsTravelProof: false, needsApprovalFirst: false, ledger: 'Training' };
  await ok(api(P.nisha, 'POST', `${EX}/categories`, body));
  await refused(api(P.nisha, 'POST', `${EX}/categories`, body), 409);
  await refused(api(P.esha, 'POST', `${EX}/categories`, { ...body, name: 'Mine' }), 403);
});

tc('TC-E013 the Categories screen lists name, ledger, limits and evidence', async () => {
  await open(owner, 'Categories');
  for (const t of ['Meals', 'Travel', 'Mileage', '₹1,000.00 an expense', '₹90,000.00 a month', 'receipt needed above ₹300.00']) await expect(owner.locator('main')).toContainText(t);
});

tc('TC-E014 what a category required when the claim was sent still holds after setup changes', async () => {
  const id = await claim(P.esha, 'Category changes later');
  await line(P.esha, id, 'Meals', 250);
  await submit(P.esha, id); await decide(P.vivek, id);
  const cats = await ok(api(P.nisha, 'GET', `${EX}/categories?all=true`));
  const meals = cats.find((c: any) => c.name === 'Meals');
  await ok(api(P.nisha, 'PUT', `${EX}/categories/${meals.id}`, { ...meals, receiptAbove: 100 }));
  await verify(P.nisha, id);
  expect((await detail(P.nisha, id)).status).toBe('verified');
  await ok(api(P.nisha, 'PUT', `${EX}/categories/${meals.id}`, { ...meals, receiptAbove: 300 }));
});

// ── Approvers ───────────────────────────────────────────────────────────────

tc('TC-E015 a named approver replaces the manager', async () => {
  const rule = await ok(api(owner, 'POST', `${EX}/approvers`, { membershipId: ID.ravi, departmentId: null, approverId: ID.meera }));
  const id = await claimTo('ravi', 'submitted', 'Goes to Meera');
  await open(P.meera, 'Approvals');
  await expect(P.meera.locator('main')).toContainText('Goes to Meera');
  await open(P.vivek, 'Approvals');
  await expect(P.vivek.locator('main')).not.toContainText('Goes to Meera');
  await refused(api(P.vivek, 'POST', `${EX}/claims/${id}/decision`, { action: 'approve', note: null, version: await version(P.ravi, id), cuts: null }), [403, 404, 409]);
  await ok(api(owner, 'DELETE', `${EX}/approvers/${rule.id}`));
});

tc('TC-E016 the Approvers screen opens for the Org Admin', async () => {
  await open(owner, 'Approvers');
  await expect(owner.locator('main')).not.toContainText(/error|denied/i);
  await expect(owner.getByRole('heading').first()).toBeVisible();
});

// ── Claims, on screen ───────────────────────────────────────────────────────

tc('TC-E017 a claim is started and a line added on screen', async () => {
  await open(P.esha, 'My claims');
  await P.esha.fill('#new-claim', 'Screen claim');
  await P.esha.getByRole('button', { name: 'Start' }).click();
  await P.esha.selectOption('#line-cat', CAT.Meals);
  await P.esha.fill('#line-date', isoDay(-2));
  await P.esha.fill('#line-amount', '240');
  await P.esha.fill('#line-merchant', 'Udupi canteen');
  await P.esha.getByRole('button', { name: 'Add line' }).click();
  await expect(P.esha.locator('main')).toContainText('240');
  await expect(P.esha.locator('main')).toContainText('Udupi canteen');
});

tc('TC-E018 withdraw, return, reopen, and a stale version', async () => {
  const id = await claimTo('esha', 'submitted', 'Withdrawn');
  await ok(api(P.esha, 'POST', `${EX}/claims/${id}/withdraw`, { version: await version(P.esha, id) }));
  expect((await detail(P.esha, id)).status).toBe('withdrawn');
  const sent = await claimTo('ravi', 'submitted', 'Sent back');
  await decide(P.vivek, sent, 'return', 'Add the client name');
  expect((await detail(P.ravi, sent)).status).toBe('returned');
  await ok(api(P.ravi, 'POST', `${EX}/claims/${sent}/reopen`, { version: await version(P.ravi, sent) }));
  const stale = await claimTo('esha', 'draft', 'Stale');
  const old = await version(P.esha, stale);
  await submit(P.esha, stale);
  await refused(api(P.esha, 'POST', `${EX}/claims/${stale}/withdraw`, { version: old }), 409);
});

tc('TC-E019 distance is paid by the kilometre, in tenths', async () => {
  const id = await claim(P.esha, 'Mileage');
  const first = await line(P.esha, id, 'Mileage', null as any, { km: 12.5, merchant: null });
  expect(Number(first.amount)).toBe(125);
  await refused(api(P.esha, 'POST', `${EX}/claims/${id}/lines`, { categoryId: CAT.Mileage, spentOn: isoDay(-3), amount: null, km: 12.55, merchant: null, note: null, overLimitReason: null }), [400, 409]);
});

tc('TC-E020 a receipt is attached and the claim submitted on screen', async () => {
  const id = await claim(P.esha, 'Receipt claim');
  const l = await line(P.esha, id, 'Travel', 1500, { merchant: 'Bus' });
  await open(P.esha, 'My claims');
  await openClaim(P.esha, /Receipt claim/);
  await P.esha.getByRole('button', { name: 'Submit', exact: true }).click().catch(() => {});
  expect([200, 201, 204]).toContain(await upload(P.esha, l.id));
  await open(P.esha, 'My claims');
  await openClaim(P.esha, /Receipt claim/);
  await P.esha.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(P.esha.locator('main')).toContainText('With your manager');
});

tc('TC-E021 a receipt can be downloaded; a submitted claim cannot be deleted', async () => {
  const id = await claim(P.esha, 'Download claim');
  const l = await line(P.esha, id, 'Travel', 1500, { merchant: 'Taxi' });
  expect([200, 201, 204]).toContain(await upload(P.esha, l.id));
  await submit(P.esha, id);
  const file = (await detail(P.esha, id)).lines[0].files[0];
  const got = await P.esha.evaluate(async (fid) => {
    const r = await fetch(`/api/org/expenses/files/${fid}`, { headers: { Authorization: `Bearer ${sessionStorage.getItem('hrms-token')}` } });
    return { status: r.status, size: (await r.arrayBuffer()).byteLength };
  }, file.id);
  expect(got.status).toBe(200);
  expect(got.size).toBe(PNG.length);
  await refused(api(P.esha, 'DELETE', `${EX}/claims/${id}`), [409, 403, 400]);
});

tc('TC-E023 over the limit needs a reason and warns the approver', async () => {
  const id = await claim(P.esha, 'Over the limit');
  const bare = await line(P.esha, id, 'Meals', 1500, { merchant: 'Big dinner' });
  expect([200, 201, 204]).toContain(await upload(P.esha, bare.id));
  await refused(api(P.esha, 'POST', `${EX}/claims/${id}/submit`, { version: await version(P.esha, id) }), [400, 409]);   // no reason given
  await ok(api(P.esha, 'DELETE', `${EX}/lines/${bare.id}`));
  const l = await line(P.esha, id, 'Meals', 1500, { merchant: 'Big dinner', overLimitReason: 'Client dinner, approved by the director' });
  expect([200, 201, 204]).toContain(await upload(P.esha, l.id));
  await submit(P.esha, id);
  await open(P.vivek, 'Approvals');
  await openClaim(P.vivek, /Over the limit/);
  await expect(P.vivek.locator('main')).toContainText(/limit/i);
  await refused(api(P.vivek, 'POST', `${EX}/claims/${id}/decision`, { action: 'approve', note: null, version: await version(P.esha, id), cuts: null }), 400);   // the warning must be answered
  await decide(P.vivek, id, 'approve', 'Seen the warning, the director approved it');
});

tc('TC-E024 possible duplicates are flagged', async () => {
  const one = await claim(P.esha, 'Twice, first');
  await line(P.esha, one, 'Meals', 275, { merchant: 'Same place' }, 9);
  await submit(P.esha, one);
  const two = await claim(P.esha, 'Twice, second');
  await line(P.esha, two, 'Meals', 275, { merchant: 'Same place' }, 9);
  await submit(P.esha, two);
  const flags = (await detail(P.esha, two)).lines.flatMap((l: any) => l.flags.map((f: any) => f.code));
  expect(flags).toContain('possible_duplicate');
});

// ── Approval and finance ───────────────────────────────────────────────────

tc('TC-E025 the manager approves a claim on screen', async () => {
  const id = await claimTo('esha', 'submitted', 'For the manager');
  await open(P.vivek, 'Approvals');
  await openClaim(P.vivek, /For the manager/);
  await P.vivek.getByRole('button', { name: 'Approve', exact: true }).click();
  await open(P.vivek, 'Approvals');
  await expect(P.vivek.locator('main')).not.toContainText('For the manager');
  expect((await detail(P.esha, id)).status).toBe('approved');
});

tc('TC-E026 manager and finance take a claim to verified, with a cut', async () => {
  const id = await claim(P.esha, 'With a cut');
  await line(P.esha, id, 'Meals', 400, { merchant: 'Cafe' });
  const l = (await detail(P.esha, id)).lines[0];
  expect([200, 201, 204]).toContain(await upload(P.esha, l.id));
  await submit(P.esha, id);
  await decide(P.vivek, id, 'approve', 'Only the meal is covered', [{ lineId: l.id, amount: 300, reason: 'Only the meal is covered' }]);
  await verify(P.nisha, id);
  const done = await detail(P.nisha, id);
  expect(done.status).toBe('verified');
  expect(Number(done.lines[0].approvedAmount)).toBe(300);
});

tc('TC-E027 nobody verifies their own claim', async () => {
  // Nisha has no manager, so her claim goes straight to finance, where she may not verify it.
  const mine = await claimTo('nisha', 'submitted', 'Finance own claim');
  expect((await detail(P.nisha, mine)).status).toBe('approved');
  await refused(api(P.nisha, 'POST', `${EX}/claims/${mine}/verify`, { version: await version(P.nisha, mine), note: null }), [403, 404, 409]);
  await verify(P.farid, mine);
  expect((await detail(P.nisha, mine)).status).toBe('verified');
  // A manager does not approve their own claim either: Vivek's goes to finance because he has no manager.
  const boss = await claimTo('vivek', 'submitted', 'Manager own claim');
  await refused(api(P.vivek, 'POST', `${EX}/claims/${boss}/decision`, { action: 'approve', note: null, version: await version(P.vivek, boss), cuts: null }), [403, 404, 409]);
});

tc('TC-E029 finance verifies a claim on screen', async () => {
  const id = await claimTo('esha', 'approved', 'Verify on screen');
  await open(P.nisha, 'Finance');
  await openClaim(P.nisha, /Verify on screen/);
  await P.nisha.getByRole('button', { name: 'Verify', exact: true }).click();
  await open(P.nisha, 'Payments');
  await expect(P.nisha.locator('main')).toContainText('Verify on screen');
  expect((await detail(P.esha, id)).status).toBe('verified');
});

tc('TC-E030 asking for more evidence pauses the claim; rejected evidence cuts the line', async () => {
  const id = await claim(P.esha, 'Evidence asked');
  await line(P.esha, id, 'Travel', 1500, { merchant: 'Train' });
  await line(P.esha, id, 'Travel', 1200, { merchant: 'Cab' });
  const lines = (await detail(P.esha, id)).lines;
  for (const l of lines) expect([200, 201, 204]).toContain(await upload(P.esha, l.id));
  await submit(P.esha, id); await decide(P.vivek, id);
  const v = await version(P.nisha, id);
  await ok(api(P.nisha, 'POST', `${EX}/lines/${lines[0].id}/review`, { status: 'more_needed', note: 'Send the full ticket', version: v }));
  expect((await detail(P.esha, id)).status).toBe('evidence_requested');
  await open(P.esha, 'My claims');
  await openClaim(P.esha, /Evidence asked/);
  await expect(P.esha.locator('main')).toContainText('Send the full ticket');
  await ok(api(P.nisha, 'POST', `${EX}/lines/${lines[1].id}/review`, { status: 'rejected', note: 'Not a valid bill', version: await version(P.nisha, id) }));
  expect([200, 201, 204]).toContain(await upload(P.esha, lines[0].id, 'full-ticket.png'));
  await ok(api(P.esha, 'POST', `${EX}/claims/${id}/send-back`, { version: await version(P.esha, id) }));
  expect((await detail(P.nisha, id)).status).toBe('approved');
  await ok(api(P.nisha, 'POST', `${EX}/lines/${lines[0].id}/review`, { status: 'accepted', note: null, version: await version(P.nisha, id) }));
  await ok(api(P.nisha, 'POST', `${EX}/claims/${id}/verify`, { version: await version(P.nisha, id), note: 'Second bill rejected' }));
  const done = await detail(P.nisha, id);
  expect(done.status).toBe('verified');
  expect(Number(done.payable)).toBe(1500);   // the line whose evidence was rejected pays nothing
});

tc('TC-E032 a batch is made and marked paid on screen', async () => {
  const id = await claimTo('ravi', 'verified', 'Batch me', 260);
  await open(P.nisha, 'Payments');
  const number = (await detail(P.ravi, id)).number;
  await P.nisha.getByLabel(`Include ${number}`).check();
  await P.nisha.getByRole('button', { name: /Make a batch of/ }).dispatchEvent('click');
  await expect(P.nisha.locator('main')).toContainText('Mark paid');
  await P.nisha.getByRole('button', { name: 'Mark paid', exact: true }).first().dispatchEvent('click');
  await P.nisha.fill('#bank-reference', 'UTR-HARBOUR-0001');
  await P.nisha.getByRole('button', { name: 'Mark paid', exact: true }).last().click();
  await expect(P.nisha.locator('main')).toContainText('Paid');
  await open(P.ravi, 'My claims');
  await expect(P.ravi.locator('main')).toContainText('Paid');
  expect(['paid', 'verified']).toContain((await detail(P.ravi, id)).status);
});

tc('TC-E033 batch rules: own claim, reference length, bank file', async () => {
  const own = await claimTo('nisha', 'submitted', 'Nisha own');
  await verify(P.farid, own);
  await refused(api(P.nisha, 'POST', `${EX}/batches`, { claims: [own] }), [403, 409]);
  const other = await claimTo('esha', 'verified', 'For a file', 180);
  const made = await ok(api(P.nisha, 'POST', `${EX}/batches`, { claims: [other] }));
  await refused(api(P.nisha, 'POST', `${EX}/batches/${made.id}/paid`, { paidOn: isoDay(0), reference: 'x' }), [400, 409]);
  const file = await P.nisha.evaluate(async (bid) => {
    const r = await fetch(`/api/org/expenses/batches/${bid}/bank-file`, { headers: { Authorization: `Bearer ${sessionStorage.getItem('hrms-token')}` } });
    return { status: r.status, text: await r.text() };
  }, made.id);
  expect(file.status).toBe(200);
  expect(file.text).toContain('Esha Patil');
  // TC-E034 follows from this batch: cancelling it returns the claim to verified.
  await ok(api(P.nisha, 'POST', `${EX}/batches/${made.id}/cancel`));
  expect((await detail(P.esha, other)).status).toBe('verified');
  await ok(api(P.nisha, 'POST', `${EX}/batches`, { claims: [other] }));
});

// ── Loan types and loans ───────────────────────────────────────────────────

tc('TC-E037 a loan type is added on screen', async () => {
  await open(P.nisha, 'Loan types');
  await P.nisha.fill('#lt-name', 'Festival advance');
  await P.nisha.selectOption('#lt-kind', 'salary_advance');
  await P.nisha.fill('#lt-amount', '20000');
  await P.nisha.fill('#lt-months', '2');
  await P.nisha.getByRole('button', { name: 'Add type' }).click();
  await expect(P.nisha.locator('main')).toContainText('Festival advance');
});

tc('TC-E038 type limits are enforced on a request', async () => {
  const t = await loanType(P.nisha, 'Strict advance', { maxAmount: 10000, maxMonths: 2, minServiceMonths: 0, maxOpen: 1 });
  await refused(api(P.sunil, 'POST', LN, { typeId: t, amount: 12000, months: 2, purpose: 'Too much' }), [400, 409]);
  await refused(api(P.sunil, 'POST', LN, { typeId: t, amount: 5000, months: 5, purpose: 'Too long' }), [400, 409]);
  await ok(api(P.sunil, 'POST', LN, { typeId: t, amount: 5000, months: 2, purpose: 'Just right' }));
  await refused(api(P.sunil, 'POST', LN, { typeId: t, amount: 3000, months: 2, purpose: 'A second one' }), [400, 409]);
});

tc('TC-E039 an advance type cannot carry interest', async () => {
  await refused(api(P.nisha, 'POST', `${LN}/types`, { name: 'Interest advance', kind: 'expense_advance', maxAmount: 10000, maxMonths: 2, annualRate: 5,
    minServiceMonths: 0, maxOpen: 1, active: true }), [400, 409]);
});

tc('TC-E040 an advance is asked for on screen, with the preview', async () => {
  await open(P.esha, 'Advances and loans');
  await P.esha.selectOption('#loan-type', { label: 'Salary advance' }).catch(async () => { await P.esha.selectOption('#loan-type', { index: 1 }); });
  await P.esha.fill('#loan-amount-ask', '15000');
  await P.esha.fill('#loan-months', '3');
  await P.esha.fill('#loan-purpose', 'Deposit for a rented flat');
  await P.esha.getByRole('button', { name: 'Show the repayments' }).click();
  await expect(P.esha.locator('main')).toContainText('5,000');
  await P.esha.getByRole('button', { name: 'Send request' }).click();
  await expect(P.esha.locator('main')).toContainText('LN-');
  eshaLoan = (await ok(api(P.esha, 'GET', LN)))[0].number;
});

tc('TC-E041 the manager, then finance, approve and finance records the payout on screen', async () => {
  await open(P.vivek, 'Loan approvals');
  await openClaim(P.vivek, eshaLoan);
  await P.vivek.getByRole('button', { name: 'Approve', exact: true }).click();
  await open(P.nisha, 'Loans');
  await openClaim(P.nisha, eshaLoan);
  await P.nisha.getByRole('button', { name: 'Approve', exact: true }).click();
  await open(P.nisha, 'Loans');
  await openClaim(P.nisha, eshaLoan);
  await P.nisha.fill('#loan-ref', 'UTR-HARBOUR-LN1');
  await P.nisha.getByRole('button', { name: 'Record payout' }).click();
  await expect(P.nisha.locator('main')).toContainText('5,000');
  await expect(P.nisha.locator('main')).toContainText('Being repaid');
});

tc('TC-E042 skip, early repayment and write-off', async () => {
  const id = await loanTo('ravi', typeSalary, 6000, 3, 'active');
  const view = await ok(api(P.nisha, 'GET', `${LN}/${id}`));
  const first = view.schedule[0];
  await ok(api(P.nisha, 'POST', `${LN}/instalments/${first.id}/skip`, { note: 'Festival month' }));
  expect((await ok(api(P.nisha, 'GET', `${LN}/${id}`))).schedule.length).toBeGreaterThan(view.schedule.length - 1);
  await ok(api(P.nisha, 'POST', `${LN}/${id}/prepay`, { amount: 1000, note: 'Cash received' }));
  await ok(api(P.farid, 'POST', `${LN}/${id}/write-off`, { note: 'Written off after approval' }));
  expect((await ok(api(P.nisha, 'GET', `${LN}/${id}`))).status).toBe('written_off');
});

tc('TC-E047 finance marks a loan exempt and takes the mark off', async () => {
  const t = await loanType(P.nisha, 'Medical loan', { kind: 'loan', maxAmount: 100000, maxMonths: 12, annualRate: 4 });
  const id = await loanTo('ravi', t, 40000, 6, 'active');
  exemptNumber = (await ok(api(P.nisha, 'GET', `${LN}/${id}`))).number;
  await refused(api(P.nisha, 'POST', `${LN}/${id}/exempt`, { note: 'short' }), [400, 409]);
  await ok(api(P.nisha, 'POST', `${LN}/${id}/exempt`, { note: 'Treatment of a specified illness, bills on file' }));
  await ok(api(P.nisha, 'POST', `${LN}/${id}/exempt`, { note: '' }));
  await refused(api(P.ravi, 'POST', `${LN}/${id}/exempt`, { note: 'Treatment of a specified illness, bills on file' }), 403);
});

tc('TC-E048 the exempt button on the loan page', async () => {
  await open(P.nisha, 'Loans');
  await openClaim(P.nisha, exemptNumber);
  await P.nisha.fill('#loan-note', 'Treatment of a specified illness, bills on file');
  await P.nisha.getByRole('button', { name: 'Mark exempt from perquisite tax' }).click();
  await expect(P.nisha.locator('main')).toContainText('not taxed');
  await expect(P.nisha.locator('main')).toContainText('Treatment of a specified illness');
});

// ── Expense advances ───────────────────────────────────────────────────────

tc('TC-E050 an expense advance is settled by claims and only the rest is paid', async () => {
  const t = await loanType(P.nisha, 'Trip advance', { kind: 'expense_advance', maxAmount: 5000, maxMonths: 2 });
  const adv = await loanTo('esha', t, 1000, 1, 'active');
  const first = await claim(P.esha, 'First trip claim');
  await line(P.esha, first, 'Travel', 900, { merchant: 'Trip fare' });
  await ok(api(P.esha, 'PUT', `${EX}/claims/${first}/advance`, { loanId: adv }));
  await submit(P.esha, first); await decide(P.vivek, first); await verify(P.nisha, first);
  expect((await detail(P.esha, first)).status).toBe('paid');
  expect(Number((await ok(api(P.nisha, 'GET', `${LN}/${adv}`))).outstanding)).toBe(100);
  const second = await claim(P.esha, 'Second trip claim');
  await line(P.esha, second, 'Travel', 300, { merchant: 'Trip fare 2' });
  await ok(api(P.esha, 'PUT', `${EX}/claims/${second}/advance`, { loanId: adv }));
  await submit(P.esha, second); await decide(P.vivek, second); await verify(P.nisha, second);
  const done = await detail(P.esha, second);
  expect(Number(done.advanceApplied)).toBe(100);
  expect((await ok(api(P.nisha, 'GET', `${LN}/${adv}`))).status).not.toBe('active');
  (globalThis as any).openAdvance = await (async () => {
    const t2 = await loanType(P.nisha, 'Site advance', { kind: 'expense_advance', maxAmount: 5000, maxMonths: 2 });
    return loanTo('ravi', t2, 2000, 2, 'active');
  })();
});

tc('TC-E051 the advance can be chosen only on a draft, and only one\'s own', async () => {
  const adv = (globalThis as any).openAdvance as string;       // Ravi's advance
  const mine = await claimTo('esha', 'draft', 'Not her advance');
  await refused(api(P.esha, 'PUT', `${EX}/claims/${mine}/advance`, { loanId: adv }), [400, 403, 404, 409]);
  const rav = await claimTo('ravi', 'submitted', 'Too late for an advance');
  await refused(api(P.ravi, 'PUT', `${EX}/claims/${rav}/advance`, { loanId: adv }), [400, 409]);
});

tc('TC-E052 what claims do not cover is one deduction in the last month asked for', async () => {
  const adv = (globalThis as any).openAdvance as string;
  const view = await ok(api(P.nisha, 'GET', `${LN}/${adv}`));
  expect(view.schedule).toHaveLength(1);
  expect(Number(view.schedule[0].principal)).toBe(2000);
});

tc('TC-E053 set against an advance on the claim screen', async () => {
  const id = await claim(P.ravi, 'Screen advance');
  await line(P.ravi, id, 'Meals', 400, { merchant: 'On the road' });
  await open(P.ravi, 'My claims');
  await openClaim(P.ravi, /Screen advance/);
  await P.ravi.selectOption('#claim-advance', { index: 1 });
  await expect(P.ravi.locator('main')).toContainText(/Set against advance LN-/);
});

// ── Messages and access ────────────────────────────────────────────────────

tc('TC-E056 people are told when something waits for them and when their request moves', async () => {
  const id = await claimTo('esha', 'submitted', 'Tell everyone');
  const number = (await detail(P.esha, id)).number;
  expect(JSON.stringify(await ok(api(P.vivek, 'GET', '/org/notifications')))).toContain(number);
  await decide(P.vivek, id);
  expect(JSON.stringify(await ok(api(P.nisha, 'GET', '/org/notifications')))).toContain(number);
  await verify(P.nisha, id);
  expect(JSON.stringify(await ok(api(P.esha, 'GET', '/org/notifications')))).toContain(number);
  const back = await claimTo('ravi', 'submitted', 'Returned with a reason');
  await decide(P.vivek, back, 'return', 'Please add the purpose');
  expect(JSON.stringify(await ok(api(P.ravi, 'GET', '/org/notifications')))).toContain('Please add the purpose');
});

tc('TC-E058 a message switched off in Setup is not sent', async () => {
  const msgs = await ok(api(owner, 'GET', '/org/people/setup/messages'));
  const m = msgs.find((t: any) => t.event === 'expense_verified');
  expect(m, 'the expense_verified message is listed').toBeTruthy();
  await ok(api(owner, 'PUT', `/org/people/setup/messages/${m.id}`, { subject: m.subject, body: m.body, active: false }));
  const before = JSON.stringify(await ok(api(P.esha, 'GET', '/org/notifications'))).length;
  const id = await claimTo('esha', 'approved', 'Quiet verify');
  await verify(P.nisha, id);
  expect(JSON.stringify(await ok(api(P.esha, 'GET', '/org/notifications'))).length).toBe(before);
  await ok(api(owner, 'PUT', `/org/people/setup/messages/${m.id}`, { subject: m.subject, body: m.body, active: true }));
});

tc('TC-E059 claims are invisible to others', async () => {
  const id = await claimTo('esha', 'draft', 'Private claim');
  await refused(api(P.ravi, 'GET', `${EX}/claims/${id}`), 404);
  await open(P.ravi, 'My claims');
  await expect(P.ravi.locator('main')).not.toContainText('Private claim');
});

tc('TC-E060 another Organization never sees a claim or a category', async ({ browser }) => {
  const id = await claimTo('esha', 'draft', 'Harbour only');
  const sa = await newPage(browser); await sa.goto('/');
  await sa.fill('#email', SUPER_ADMIN.email); await sa.fill('#password', SUPER_ADMIN.password); await sa.click('button.primary');
  await expect(sa.getByText('Platform console')).toBeVisible();
  const org = await ok(api(sa, 'POST', '/platform/organizations', { name: 'Other Cargo', slug: `other-cargo${RUN}`, adminEmail: em('other') }));
  await sa.context().close();
  const other = await join(browser, org.invitationToken, em('other'));
  await ok(api(other, 'POST', '/org/setup/starter', { starter: 'office', locationName: 'Head office', stateCode: 'MH' }));
  expect(JSON.stringify(await ok(api(other, 'GET', `${EX}/categories?all=true`)))).not.toContain('Mileage');
  await refused(api(other, 'GET', `${EX}/claims/${id}`), [403, 404]);
  await other.context().close();
});
