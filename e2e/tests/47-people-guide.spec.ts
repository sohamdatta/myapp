import { test, expect, type Page } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, api, isoDay, shot as save } from './helpers';
import { join } from './join';

// The screenshots in docs/user-guide/people.md, taken in a demo Organization of their own (Northwind Retail),
// so they show tidy names. Run with E2E_SHOTS=<folder>; without it the steps still run and nothing is saved.

const em = (n: string) => `${n}@retail.example`;
async function ok(r: Promise<{ status: number; body: any }>) {
  const done = await r;
  expect([200, 204], JSON.stringify(done.body)).toContain(done.status);
  return done.body;
}

test('the people guide, screen by screen', async ({ browser }) => {
  test.setTimeout(300_000);
  const admin = await newPage(browser); await admin.goto('/');
  await admin.fill('#email', SUPER_ADMIN.email); await admin.fill('#password', SUPER_ADMIN.password); await admin.click('button.primary');
  await expect(admin.getByText('Platform console')).toBeVisible();
  const org = await ok(api(admin, 'POST', '/platform/organizations', { name: 'Northwind Retail', slug: 'northwind-retail', adminEmail: em('owner'), licensedEmployees: 50 }));
  await admin.context().close();
  const owner = await join(browser, org.invitationToken, em('owner'));
  const pg: Record<string, Page> = {};
  for (const [n, roles] of [['hr', ['hr_admin']], ['priya', ['employee']], ['arjun', ['employee', 'manager']]] as const) {
    const inv = await ok(api(owner, 'POST', '/org/invitations', { email: em(n), roles }));
    pg[n] = await join(browser, inv.token, em(n));
  }
  const hr = pg.hr;
  const id = Object.fromEntries((await ok(api(hr, 'GET', '/org/attendance/assignments'))).map((p: any) => [p.email, p.membershipId]));
  const dep = async (name: string, parentId?: string) => ok(api(hr, 'POST', '/org/people/departments', { name, parentId }));
  const ops = await dep('Operations'); const stores = await dep('Stores', ops.id); const fin = await dep('Finance');
  const add = (body: any) => ok(api(hr, 'POST', '/org/people/employees', { joiningDate: isoDay(-500), ...body }));   // date-safe: joined long ago
  const md = await add({ legalName: 'Sunita Rao', designation: 'Managing director', membershipId: id[em('owner')] });
  const arjun = await add({ legalName: 'Arjun Mehta', designation: 'Operations head', departmentId: ops.id, managerId: md.id, membershipId: id[em('arjun')] });
  await add({ legalName: 'Priya Sharma', designation: 'Store manager', departmentId: stores.id, managerId: arjun.id, membershipId: id[em('priya')], employeeCode: 'NR-104' });
  await add({ legalName: 'Kabir Singh', designation: 'Cashier', departmentId: stores.id, managerId: arjun.id, employeeCode: 'NR-105' });
  await add({ legalName: 'Meena Pillai', designation: 'Accountant', departmentId: fin.id, managerId: md.id, membershipId: id[em('hr')], employeeCode: 'NR-102' });

  const shot = async (p: Page, name: string, fn: () => Promise<void>) => { await fn(); await save(p, `people-${name}`); };
  await hr.setViewportSize({ width: 1280, height: 860 }); await pg.priya.setViewportSize({ width: 1280, height: 860 });
  await shot(hr, '01-employees', async () => { await hr.reload(); await nav(hr, 'People'); });
  await shot(hr, '02-add', async () => {
    await hr.getByRole('button', { name: 'Add employee' }).first().click();
    await hr.fill('#ne-legal', 'Ravi Kumar'); await hr.fill('#ne-joining', isoDay(0)); await hr.fill('#ne-designation', 'Store assistant');
    await hr.selectOption('#ne-department', { label: 'Operations › Stores' }); await hr.selectOption('#ne-manager', { label: 'Priya Sharma, Store manager' });
    await hr.locator('#ne-legal').scrollIntoViewIfNeeded();
  });
  await shot(hr, '03-profile', async () => { await hr.reload(); await nav(hr, 'People'); await hr.getByRole('button', { name: 'Priya Sharma', exact: true }).click(); });
  await shot(hr, '04-change-job', async () => {
    await hr.getByRole('button', { name: 'Change job' }).click(); await hr.fill('#job-designation', 'Area manager'); await hr.fill('#job-reason', 'Promotion');
    await hr.locator('#job-from').scrollIntoViewIfNeeded();
  });
  await shot(hr, '05-departments', async () => { await hr.reload(); await nav(hr, 'People'); await tab(hr, 'Departments'); });
  await shot(hr, '06-import', async () => {
    await tab(hr, 'Import');
    const csv = ['Emp Code,Full Name,DOJ,Designation,Dept,Reports To,Email', 'NR-201,Asha Nair,01/04/2024,Cashier,Stores,NR-104,asha@mail.example',
      'NR-202,Dev Patel,2024-06-10,Store assistant,Stores,NR-104,dev@mail.example', 'NR-203,Old Record,31/02/2024,Cashier,Stores,,old@mail.example'].join('\n');
    await hr.locator('#import-file').setInputFiles({ name: 'staff.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await hr.getByRole('button', { name: 'Check every row' }).click();
    await expect(hr.getByRole('heading', { name: /ready to import/ })).toBeVisible();
  });
  await shot(pg.priya, '07-org-chart', async () => { await pg.priya.reload(); await nav(pg.priya, 'Org chart'); });
  await shot(pg.priya, '08-my-profile', async () => { await nav(pg.priya, 'My profile'); });
});
