import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay } from './helpers';
import { join } from './join';

// Every employee-record setting and screen, case by case (docs/testing/people-ui-test-cases.xlsx; the TC ids are its
// rows), in an Organization of its own, People Labs.

const RUN = process.env.E2E_RUN || '';   // a suffix, to run the file again on a database that already has People Labs
const D = `people${RUN}.test`;
const U = (n: string) => `${n}@${D}`;
const P: Record<string, Page> = {};
let hr: Page;
let ids: Record<string, string> = {};
const emp: Record<string, string> = {};   // employee ids
const err = (p: Page) => p.locator('form .error').first();
const PAN = 'BQKPE4321K';
const AADHAAR = '345678901238';   // a valid Verhoeff check digit

async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}
async function byName(p: Page, name: string) {
  const list = await ok(api(p, 'GET', '/org/people/employees?status=all'));
  return list.find((e: any) => e.legalName === name);
}
async function people(p: Page) { await p.reload(); await nav(p, 'People'); }
async function addForm() { await people(hr); await tab(hr, 'Employees'); await hr.getByRole('button', { name: 'Add employee' }).first().click(); }
async function basics(name: string, designation = 'Analyst') {
  await hr.fill('#ne-legal', name); await hr.fill('#ne-joining', isoDay(-90)); await hr.fill('#ne-designation', designation);   // date-safe: joined months ago
}
const submit = () => hr.locator('button:has-text("Add employee")').last().click();
async function open(name: string) {
  await people(hr); await tab(hr, 'Employees'); await hr.getByRole('button', { name, exact: true }).click();
  await expect(hr.getByRole('heading', { name: new RegExp(name) })).toBeVisible();
}

test.describe.serial('people test cases', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    const org = await ok(api(admin, 'POST', '/platform/organizations', { name: `People Labs${RUN}`, slug: `people-labs${RUN}`, adminEmail: U('owner'), licensedEmployees: 40 }));
    await admin.context().close();
    P.owner = await join(browser, org.invitationToken, U('owner'));
    const roles: Record<string, string[]> = { hr: ['hr_admin'], mgr: ['employee', 'manager'], emp: ['employee'], aud: ['auditor'], late: ['employee'], nobody: ['employee'] };
    for (const [n, r] of Object.entries(roles)) {
      const inv = await ok(api(P.owner, 'POST', '/org/invitations', { email: U(n), roles: r }));
      P[n] = await join(browser, inv.token, U(n));
    }
    hr = P.hr;
    ids = Object.fromEntries((await ok(api(P.owner, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
  });

  test('TC-P002 a new Organization has no records yet', async () => {
    await people(hr);
    await expect(hr.getByText('No employee records yet. Add your existing staff here, starting with yourself.')).toBeVisible();
    const m = await ok(api(P.owner, 'POST', '/org/people/employees', { legalName: 'Mohan Das', joiningDate: isoDay(-400), designation: 'Team lead', membershipId: ids[U('mgr')] }));   // date-safe: joined long ago
    emp.mgr = m.id;
    emp.emp = (await ok(api(P.owner, 'POST', '/org/people/employees', { legalName: 'Esha Kapoor', joiningDate: isoDay(-200), designation: 'Analyst', managerId: m.id, membershipId: ids[U('emp')] }))).id;   // date-safe: joined long ago
  });

  // ---------- list ----------
  test('TC-P005 licences are counted', async () => {
    await people(hr); await expect(hr.getByText(/\d+ of 40 licences used/)).toBeVisible();
  });

  // ---------- add employee ----------
  test('TC-P007 name required', async () => {
    await addForm(); await hr.fill('#ne-joining', isoDay(-1)); await hr.fill('#ne-designation', 'Analyst'); await submit();
    await expect(err(hr)).toBeVisible();
  });
  test('TC-P008 under 14 on joining refused', async () => {
    await addForm(); await basics('Young Person'); await hr.fill('#ne-dob', isoDay(-3650)); await submit();   // date-safe: ten years old, any month
    await expect(err(hr)).toContainText('under 14 on the joining date');
  });
  test('TC-P009 fixed term needs a contract end', async () => {
    await addForm(); await basics('Fixed Term'); await hr.selectOption('#ne-type', 'fixed_term'); await submit();
    await expect(err(hr)).toContainText('A fixed-term employment needs a contract end date');
  });
  test('TC-P012 TC-P013 TC-P014 TC-P018 TC-P019 identifier checks', async () => {
    await addForm(); await basics('Check Person');
    await hr.fill('#ne-account', '123456789012'); await hr.fill('#ne-account-again', '123456789013'); await hr.fill('#ne-ifsc', 'SBIN0001234');
    await submit(); await expect(err(hr)).toContainText('account numbers are different');
    await hr.fill('#ne-account-again', '123456789012');
    await hr.fill('#ne-pan', 'BQKCE4321K'); await submit(); await expect(err(hr)).toContainText('fourth letter');
    await hr.fill('#ne-pan', 'ABC123'); await submit(); await expect(err(hr)).toContainText('A PAN has five letters, four digits and a letter');
    await hr.fill('#ne-pan', ''); await hr.fill('#ne-uan', '12345'); await submit(); await expect(err(hr)).toContainText('A UAN has 12 digits');
    await hr.fill('#ne-uan', ''); await hr.fill('#ne-account', '1234'); await hr.fill('#ne-account-again', '1234'); await submit();
    await expect(err(hr)).toContainText('A bank account number has 9 to 18 digits');
  });
  test('TC-P017 wrong Aadhaar check digit refused', async () => {
    await addForm(); await basics('Aadhaar Person'); await hr.fill('#ne-aadhaar', '345678901234'); await submit();
    await expect(err(hr)).toBeVisible();
  });
  test('TC-P006 TC-P010 TC-P011 add with manager and user account, identifiers and bank', async () => {
    await addForm(); await basics('Lata Menon', 'Engineer');
    await hr.selectOption('#ne-manager', { label: 'Mohan Das, Team lead' });
    await hr.selectOption('#ne-user', { label: U('late') });
    await hr.fill('#ne-pan', PAN); await hr.fill('#ne-aadhaar', AADHAAR); await hr.fill('#ne-uan', '100200300400');
    await hr.fill('#ne-account', '998877665544'); await hr.fill('#ne-account-again', '998877665544'); await hr.fill('#ne-ifsc', 'HDFC0001234');
    await submit();
    await expect(hr.getByRole('heading', { name: /Lata Menon/ })).toBeVisible();
    await expect(hr.locator('.panels')).toContainText('Mohan Das');
    await expect(hr.locator('.panels')).toContainText(U('late'));
  });
  test('TC-P020 TC-P021 TC-P022 identifiers as last four; Show needs a reason; no Show for Aadhaar', async () => {
    const page = hr.locator('.panels');
    await expect(page).toContainText('•••• 321K'); await expect(page).toContainText('•••• 1238'); await expect(page).toContainText('•••• 5544');
    await expect(page).not.toContainText(PAN);
    await expect(hr.locator('.fact', { hasText: 'Aadhaar' }).getByRole('button', { name: 'Show' })).toHaveCount(0);
    const pan = hr.locator('.fact', { hasText: 'PAN' });
    await pan.getByRole('button', { name: 'Show' }).click(); await hr.fill('#why-pan', 'x'); await pan.getByRole('button', { name: 'Show' }).click();
    await expect(pan.locator('.error')).toBeVisible();
    await hr.fill('#why-pan', 'Checking the Form 16'); await pan.getByRole('button', { name: 'Show' }).click();
    await expect(pan).toContainText(PAN);
    await pan.getByRole('button', { name: 'Hide' }).click(); await expect(pan).not.toContainText(PAN);
  });
  test('TC-P023 TC-P024 correct the UAN; an empty PAN is refused', async () => {
    await hr.getByRole('button', { name: 'Set or correct' }).click();
    await hr.selectOption('#idf-kind', 'pan'); await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(err(hr)).toContainText('Enter the PAN, or say why it is pending');
    await hr.selectOption('#idf-kind', 'uan'); await hr.fill('#idf-value', '100200300499'); await hr.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.locator('.panels')).toContainText('•••• 0499');
  });
  test('TC-P025 a new bank account', async () => {
    await hr.getByRole('button', { name: 'New account' }).click();
    await hr.fill('#bk-account', '112233445566'); await hr.fill('#bk-again', '112233445566'); await hr.fill('#bk-ifsc', 'ICIC0001234'); await hr.fill('#bk-from', isoDay(0));
    await hr.getByRole('button', { name: 'Save account' }).click();
    await expect(hr.locator('.panels')).toContainText('•••• 5566');
  });
  test('TC-P026 TC-P027 a dated job change keeps history; not before joining', async () => {
    await hr.getByRole('button', { name: 'Change job' }).click();
    await hr.fill('#job-from', isoDay(-400));   // date-safe: before the joining date, whatever the month
    await hr.fill('#job-designation', 'Senior engineer'); await hr.getByRole('button', { name: 'Save change' }).click();
    await expect(err(hr)).toContainText('A job change cannot take effect before the joining date');
    await hr.fill('#job-from', isoDay(0)); await hr.fill('#job-reason', 'Promotion'); await hr.getByRole('button', { name: 'Save change' }).click();
    await expect(hr.locator('.section-head .muted')).toContainText('Senior engineer');
    await tab(hr, 'Job history');
    await expect(hr.locator('tbody tr')).toHaveCount(2); await expect(hr.locator('tbody tr').first()).toContainText('Promotion');
  });
  test('TC-P028 reporting loop refused', async () => {
    const lata = await byName(hr, 'Lata Menon');
    const r = await api(hr, 'POST', `/org/people/employees/${emp.mgr}/job`, { effectiveFrom: isoDay(0), designation: 'Team lead', managerId: lata.id, reason: 'Loop' });
    expect(r.status).toBe(409); expect(r.body.code).toBe('employee.manager_loop');
  });
  test('TC-P015 TC-P016 same PAN refused; PAN pending with a reason', async () => {
    await addForm(); await basics('Kiran Pal'); await hr.fill('#ne-pan', PAN); await submit();
    await expect(err(hr)).toContainText('already recorded');
    await hr.fill('#ne-pan', ''); await hr.fill('#ne-pan-pending', 'Applied for on 2 Oct'); await submit();
    await expect(hr.getByRole('heading', { name: /Kiran Pal/ })).toBeVisible();
    await expect(hr.locator('.panels')).toContainText('Applied for on 2 Oct');
  });
  test('TC-P029 TC-P030 link a user account later; not twice', async () => {
    await hr.getByRole('button', { name: 'Link user account' }).click();
    await hr.selectOption('#link-user', { label: U('nobody') }); await hr.getByRole('button', { name: /^Link/ }).last().click();
    await expect(hr.locator('.panels')).toContainText(U('nobody'));
    const kiran = await byName(hr, 'Kiran Pal');
    const r = await api(hr, 'POST', `/org/people/employees/${emp.emp}/link`, { membershipId: kiran.membershipId });
    expect(r.status).toBe(409); expect(r.body.code).toBe('employee.user_linked');
  });
  test('TC-P031 start an exit opens Exits with the employee chosen', async () => {
    await hr.getByRole('button', { name: 'Start an exit…' }).click();
    await expect(hr.locator('#se-employee option:checked')).toContainText('Kiran Pal');
  });

  // ---------- departments ----------
  test('TC-P032 TC-P035 add a department and a sub-department; name required', async () => {
    await people(hr); await tab(hr, 'Departments');
    await hr.getByRole('button', { name: 'Add department' }).click(); await expect(err(hr)).toBeVisible();
    await hr.fill('#dep-name', 'Sales'); await hr.getByRole('button', { name: 'Add department' }).click();
    await expect(hr.locator('tbody tr').filter({ hasText: 'Sales' }).first()).toBeVisible();
    await hr.fill('#dep-name', 'Inside sales'); await hr.selectOption('#dep-parent', { label: 'Sales' }); await hr.getByRole('button', { name: 'Add department' }).click();
    await expect(hr.locator('tr', { hasText: 'Inside sales' })).toContainText('Sales');
  });
  test('TC-P033 TC-P034 department loop refused; rename', async () => {
    const sales = hr.locator('tbody tr').filter({ hasText: 'Sales' }).filter({ hasNotText: 'Inside' }).first();
    await sales.getByRole('button', { name: 'Change' }).click();
    await hr.locator('.detail-row select').selectOption({ label: 'Inside sales' });
    await hr.locator('.detail-row').getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.locator('.detail-row .error')).toContainText('cannot sit inside');
    await hr.locator('.detail-row select').selectOption({ label: 'Nothing (top level)' });
    await hr.locator('.detail-row input').first().fill('Sales and marketing');
    await hr.locator('.detail-row').getByRole('button', { name: 'Save', exact: true }).click();
    await expect(hr.locator('td', { hasText: 'Sales and marketing' }).first()).toBeVisible();
  });
  test('TC-P001 TC-P003 TC-P004 lists, search, department filter and empty matches', async () => {
    const kiran = await byName(hr, 'Kiran Pal');
    const deps = await ok(api(hr, 'GET', '/org/people/departments'));
    const sales = deps.find((d: any) => d.name === 'Sales and marketing');
    await ok(api(hr, 'POST', `/org/people/employees/${emp.emp}/job`, { effectiveFrom: isoDay(0), designation: 'Analyst', departmentId: sales.id, managerId: emp.mgr, reason: 'Moved to sales' }));
    await ok(api(hr, 'POST', `/org/people/employees/${kiran.id}/exit`, { lastWorkingDay: isoDay(-1), reason: 'Resigned' }));
    await people(hr); await tab(hr, 'Employees');
    await expect(hr.getByRole('button', { name: 'Kiran Pal', exact: true })).toHaveCount(0);
    await hr.getByRole('button', { name: 'Left', exact: true }).click();
    await expect(hr.getByRole('button', { name: 'Kiran Pal', exact: true })).toBeVisible();
    await hr.getByRole('button', { name: 'All', exact: true }).click();
    await expect(hr.getByRole('button', { name: 'Esha Kapoor', exact: true })).toBeVisible();
    await hr.getByRole('button', { name: 'Active', exact: true }).click();
    await hr.fill('#emp-search', 'esha'); await expect(hr.locator('tbody tr')).toHaveCount(1);
    await hr.fill('#emp-search', 'zzzz'); await expect(hr.getByText('Nobody matches that search.')).toBeVisible();
    await hr.fill('#emp-search', ''); await hr.selectOption('#emp-dept', { label: 'Sales and marketing' });
    await expect(hr.locator('tbody tr')).toHaveCount(1); await expect(hr.locator('tbody tr')).toContainText('Esha Kapoor');
  });

  // ---------- import ----------
  const CSV = ['Emp Code,Full Name,DOJ,Designation,Dept,Reports To,Email',
    'N100,Nita Joshi,01/05/2023,Analyst,Sales and marketing,,nita@mail.test',
    'N101,Omar Khan,2023-07-10,Designer,Design,N100,omar@mail.test',
    'N102,Bad Date,31/02/2024,Analyst,Sales and marketing,,bad@mail.test'].join('\n');
  test('TC-P036 TC-P037 import maps columns, explains rows, adds the ready ones', async () => {
    await people(hr); await tab(hr, 'Import');
    await hr.locator('#import-file').setInputFiles({ name: 'staff.csv', mimeType: 'text/csv', buffer: Buffer.from(CSV) });
    await expect(hr.locator('#map-legal_name')).toHaveValue('1');
    await hr.getByRole('button', { name: 'Check every row' }).click();
    await expect(hr.getByRole('heading', { name: '2 ready to import · 1 cannot be used' })).toBeVisible();
    await expect(hr.locator('tr[data-line="4"]')).toContainText('is not a date');
    await hr.getByRole('button', { name: 'Import 2 employees' }).click();
    await expect(hr.getByRole('heading', { name: 'Imported 2 employees; 1 row left out' })).toBeVisible();
  });
  test('TC-P038 importing again skips the rows already here', async () => {
    await people(hr); await tab(hr, 'Import');
    await hr.locator('#import-file').setInputFiles({ name: 'staff.csv', mimeType: 'text/csv', buffer: Buffer.from(CSV) });
    await expect(hr.getByText('These are the columns you chose last time')).toBeVisible();
    await hr.getByRole('button', { name: 'Check every row' }).click();
    await expect(hr.getByRole('heading', { name: '0 ready to import · 2 already here · 1 cannot be used' })).toBeVisible();
  });
  test('TC-P040 invite imported people', async () => {
    const row = hr.locator('tr[data-invitee="Nita Joshi"]');
    await row.getByRole('checkbox').check(); await hr.getByRole('button', { name: 'Invite 1 chosen' }).click();
    expect(await hr.locator('.invite-links .copy-row input').inputValue()).toContain('/?invite=');
    await expect(hr.locator('tr[data-invitee="Nita Joshi"]')).toHaveCount(0);
  });
  test('TC-P039 an empty file is refused', async () => {
    await people(hr); await tab(hr, 'Import');
    await hr.locator('#import-file').setInputFiles({ name: 'empty.csv', mimeType: 'text/csv', buffer: Buffer.from('') });
    await expect(hr.getByText('The file is empty')).toBeVisible();
  });

  // ---------- org chart, my profile ----------
  test('TC-P041 TC-P042 org chart shows reporting lines; search with no match', async () => {
    const p = P.emp; await p.reload(); await nav(p, 'Org chart');
    const panel = p.getByTestId('org-person');
    await expect(panel.getByRole('heading', { name: /Esha Kapoor/ })).toBeVisible();
    await expect(panel).toContainText('Reports to'); await expect(panel.getByRole('button', { name: 'Mohan Das' })).toBeVisible();
    await p.fill('#org-search', 'zzzz'); await expect(p.getByText('Nobody matches.')).toBeVisible();
  });
  test('TC-P043 my profile is read only', async () => {
    const p = P.emp; await p.reload(); await nav(p, 'My profile');
    await expect(p.getByText('Your record as HR keeps it. To correct something, ask HR.')).toBeVisible();
    for (const b of ['Change job', 'Set or correct', 'New account', 'Add']) await expect(p.getByRole('button', { name: b, exact: true })).toHaveCount(0);
  });
  test('TC-P044 no My profile without a record', async () => {
    await P.mgr.reload(); await expect(P.mgr.getByRole('link', { name: 'My profile' })).toBeVisible();
    await P.aud.reload(); await expect(P.aud.getByRole('link', { name: 'People', exact: true })).toBeVisible();
    await expect(P.aud.getByRole('link', { name: 'My profile' })).toHaveCount(0);   // the auditor has no record of their own
  });

  // ---------- access ----------
  test('TC-P045 manager sees My team only', async () => {
    const p = P.mgr; await p.reload(); await nav(p, 'People');
    await expect(p.getByRole('tab', { name: 'My team' })).toBeVisible();
    await expect(p.getByRole('button', { name: 'Esha Kapoor', exact: true })).toBeVisible();
    await expect(p.getByRole('button', { name: 'Kiran Pal', exact: true })).toHaveCount(0);
    await expect(p.getByRole('button', { name: 'Add employee' })).toHaveCount(0);
  });
  test('TC-P046 employee has no People page', async () => {
    await P.emp.reload();
    await expect(P.emp.getByRole('link', { name: 'People', exact: true })).toHaveCount(0);
    const own = await api(P.emp, 'GET', '/org/people/employees');   // self scope: only their own record
    expect(own.status).toBe(200); expect(own.body.map((e: any) => e.legalName)).toEqual(['Esha Kapoor']);
  });
  test('TC-P047 TC-P048 auditor reads without changing; no full PAN', async () => {
    const a = P.aud; await a.reload(); await nav(a, 'People');
    await expect(a.getByRole('button', { name: 'Add employee' })).toHaveCount(0);
    await a.getByRole('button', { name: 'Lata Menon', exact: true }).click();
    for (const b of ['Change job', 'Set or correct', 'New account']) await expect(a.getByRole('button', { name: b, exact: true })).toHaveCount(0);
    const lata = await byName(hr, 'Lata Menon');
    const r = await api(a, 'POST', `/org/people/employees/${lata.id}/reveal`, { field: 'pan', reason: 'Just looking at it' });
    expect(r.status).toBe(403);
  });
  test('TC-P049 people actions in the audit log', async () => {
    const o = P.owner; await o.reload(); await nav(o, 'Audit log');
    for (const a of ['employee.added', 'employee.revealed', 'employee.job_changed', 'department.created']) {
      await expect(o.locator('code', { hasText: a }).first(), a).toBeVisible();
    }
  });
});
