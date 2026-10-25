import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { SUPER_ADMIN, newPage, nav, tab, acceptInvite, api, expectRefused, expectNoConsoleErrors, isoDay, taxYear, withinTaxYear } from './helpers';

// What the tax and settings screens never offer, tried directly against the server, in an Organization
// of its own (Pied Piper): another employee's claims and files, bad claims and files, HR screens without
// salary access, settings outside their range or layer, and text that looks like code shown as text.
// Also the device endpoints with bad addresses and oversized bodies.

const ORG = 'Pied Piper Inc';
const OWNER = 'owner@piedpiper-inc.test';
const HR = 'hr@piedpiper-inc.test';
const MANAGER = 'manager@piedpiper-inc.test';
const MEERA = 'meera@piedpiper-inc.test';
const RAVI = 'ravi@piedpiper-inc.test';
const PDF = '%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n';
const XSS = '<img src=x onerror="window.__xss=1">';
const DEVICE_URL = 'http://127.0.0.1:8080/iclock';

/** Uploads raw bytes as a proof, the way the screen does, with whatever name and content the test chooses. */
async function upload(page: Page, itemId: string, name: string, content: string) {
  return page.evaluate(
    async ({ itemId, name, content }) => {
      const r = await fetch(`/api/org/me/tax/items/${itemId}/proofs?name=${encodeURIComponent(name)}`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${sessionStorage.getItem('hrms-token')}`, 'Content-Type': 'application/octet-stream' },
        body: new TextEncoder().encode(content),
      });
      const text = await r.text();
      let body: any = text;
      try {
        body = JSON.parse(text);
      } catch {
        // not JSON
      }
      return { status: r.status, body };
    },
    { itemId, name, content },
  );
}

async function device(request: APIRequestContext, method: 'GET' | 'POST', path: string, body?: string) {
  const r = method === 'GET' ? await request.get(`${DEVICE_URL}${path}`) : await request.post(`${DEVICE_URL}${path}`, { data: body });
  return r.status();
}

test.describe.serial('negative: tax declarations, settings and devices', () => {
  let owner: Page;
  let hr: Page;
  let manager: Page;
  let meera: Page;
  let ravi: Page;
  let meeraId: string;
  let rentId: string;
  let ppfId: string;
  let proofId: string;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    for (const p of [owner, hr, manager, meera, ravi]) await p?.context().close();
  });

  test('an Organization with HR, a manager and two employees, Meera on the old regime', async ({ browser }) => {
    const admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email);
    await admin.fill('#password', SUPER_ADMIN.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    await admin.fill('#org-name', ORG);
    await admin.fill('#org-admin', OWNER);
    await admin.click('text=Create and invite');
    await expect(admin.getByText(`Invitation for ${OWNER}.`)).toBeVisible();
    owner = await acceptInvite(browser, await admin.locator('.copy-row input').inputValue());
    await admin.context().close();

    const join = async (email: string, roles: string[]) => {
      const invited = await api(owner, 'POST', '/org/invitations', { email, roles });
      expect(invited.status, JSON.stringify(invited.body)).toBe(200);
      return acceptInvite(browser, `/?invite=${encodeURIComponent(invited.body.token)}`);
    };
    hr = await join(HR, ['hr_admin', 'employee']);
    manager = await join(MANAGER, ['manager', 'employee']);
    meera = await join(MEERA, ['employee']);
    ravi = await join(RAVI, ['employee']);

    const setup = (await api(owner, 'GET', '/org/payroll/setup')).body;
    const employ = async (page: Page, name: string, mobile: string) => {
      const membershipId = (await api(page, 'GET', '/auth/me')).body.membershipId;
      const added = await api(owner, 'POST', '/org/people/employees', {
        legalName: name, dateOfBirth: '1994-06-02', gender: 'female', mobile, employmentType: 'permanent',
        joiningDate: isoDay(-200), designation: 'Analyst', membershipId,
      });
      expect(added.status, JSON.stringify(added.body)).toBe(200);
      return added.body.id as string;
    };
    meeraId = await employ(meera, 'Meera Iyer', '9123456780');
    await employ(ravi, 'Ravi Kumar', '9123456781');
    expect((await api(owner, 'POST', `/org/people/employees/${meeraId}/pay`, {
      validFrom: isoDay(-200), structureId: setup.structures[0].id, annualAmount: 1500000, reason: 'Joining salary',
    })).status).toBe(200);
    expect((await api(owner, 'PUT', `/org/people/employees/${meeraId}/pay/profile`, { taxRegime: 'old' })).status).toBe(200);
    expect((await api(owner, 'PUT', '/org/payroll/tax/window', {
      taxYear: taxYear().label, declareFrom: isoDay(-1), declareUntil: withinTaxYear(20),
    })).status).toBe(200);
  });

  test('HR screens need salary access: employees and managers are refused', async () => {
    for (const page of [ravi, manager]) {
      await expectRefused(page, 'GET', '/org/payroll/tax', [403]);
      await expectRefused(page, 'GET', `/org/payroll/tax/employees/${meeraId}`, [403]);
      await expectRefused(page, 'PUT', '/org/payroll/tax/window', [403], { taxYear: taxYear().label, declareFrom: isoDay(0), declareUntil: isoDay(0) });
    }
    await manager.reload();
    await expect(manager.locator('a.nav-item').filter({ hasText: /^Tax declarations$/ })).toHaveCount(0);
    // Signed out, nothing at all.
    expect((await api(ravi, 'GET', '/org/me/tax', undefined, null)).status).toBe(401);
  });

  test('bad claims are refused, whatever the screen would have sent', async () => {
    const bad = [
      { head: 'lottery', amount: 1000, details: {} },
      { head: 'nps', amount: -5, details: {} },
      { head: 'nps', amount: 0, details: {} },
      { head: 'nps', amount: 10.555, details: {} },
      { head: 'nps', amount: 100000000, details: {} },
      { head: 'nps', amount: 'lots', details: {} },
      { head: 'sec80c', kind: 'crypto', amount: 1000, details: {} },
      { head: 'rent', amount: 30000, details: { from: `${taxYear().start}-04`, to: `${taxYear().start + 1}-03`, city: 'Pune', landlordName: 'A', landlordAddress: 'B' } },
      { head: 'rent', amount: 5000, details: { from: `${taxYear().start - 1}-04`, to: `${taxYear().start}-03`, city: 'Pune', landlordName: 'A', landlordAddress: 'B' } },
      { head: 'rent', amount: 5000, details: { from: `${taxYear().start}-04`, to: `${taxYear().start}-05`, city: 'Pune', landlordName: 'A', landlordAddress: 'B', landlordPan: 'NOTAPAN' } },
      { head: 'home_loan', amount: 100000, details: {} },
      { head: 'nps', amount: 1000, details: { note: 'x'.repeat(201) } },
    ];
    for (const body of bad) await expectRefused(meera, 'POST', '/org/me/tax/items', [400], body);
    expect((await api(meera, 'GET', '/org/me/tax')).body.items).toEqual([]);
  });

  test('a landlord name that looks like code is shown as text and never runs', async () => {
    const rent = await api(meera, 'POST', '/org/me/tax/items', {
      head: 'rent', amount: 8000,
      details: { from: `${taxYear().start}-04`, to: `${taxYear().start + 1}-03`, city: 'Pune', landlordName: XSS, landlordAddress: "1 O'Brien Road; DROP TABLE app.person; --" },
    });
    expect(rent.status, JSON.stringify(rent.body)).toBe(200);
    rentId = rent.body.items[0].id;
    const ppf = await api(meera, 'POST', '/org/me/tax/items', { head: 'sec80c', kind: 'ppf', amount: 50000, details: {} });
    ppfId = ppf.body.items.find((i: any) => i.kind === 'ppf').id;

    await meera.reload();
    await nav(meera, 'My pay');
    await tab(meera, 'Tax declaration');
    await expect(meera.locator('[data-claim="Rent (HRA)"]')).toContainText(`landlord ${XSS}`);
    expect(await meera.evaluate(() => (window as any).__xss)).toBeUndefined();
    expect(await meera.locator('[data-claim="Rent (HRA)"] img').count()).toBe(0);
  });

  test('another employee cannot read, change or remove Meera’s claims', async () => {
    await expectRefused(ravi, 'PUT', `/org/me/tax/items/${ppfId}`, [404], { head: 'sec80c', kind: 'ppf', amount: 1, details: {} });
    await expectRefused(ravi, 'DELETE', `/org/me/tax/items/${ppfId}`, [404]);
    await expectRefused(meera, 'PUT', `/org/me/tax/items/${ppfId}`, [400], { head: 'nps', amount: 1000, details: {} });
    await expectRefused(meera, 'DELETE', '/org/me/tax/items/00000000-0000-0000-0000-000000000000', [404]);
    await expectRefused(meera, 'DELETE', '/org/me/tax/items/not-an-id', [400]);
    expect((await api(ravi, 'GET', '/org/me/tax')).body.items).toEqual([]);
  });

  test('proofs: only after submitting, only real PDF, JPEG or PNG files, only your own', async () => {
    expect((await api(owner, 'PUT', '/org/payroll/tax/window', {
      taxYear: taxYear().label, declareFrom: isoDay(-1), declareUntil: withinTaxYear(20), proofsFrom: isoDay(-1), proofsUntil: withinTaxYear(20),
    })).status).toBe(200);
    expect((await upload(meera, ppfId, 'ppf.pdf', PDF)).body.code).toBe('tax.not_submitted');
    expect((await api(meera, 'POST', '/org/me/tax/submit')).status).toBe(200);

    // A web page renamed to .pdf, a script, an empty file: refused by what they contain, not their names.
    const page = await upload(meera, ppfId, 'passbook.pdf', `<html><script>alert(1)</script></html>`);
    expect([page.status, page.body.code]).toEqual([415, 'document.type']);
    expect((await upload(meera, ppfId, 'run.exe', 'MZ\u0000\u0001this program')).status).toBe(415);
    expect((await upload(meera, ppfId, 'empty.pdf', '')).status).toBe(400);
    expect((await upload(ravi, ppfId, 'ppf.pdf', PDF)).status).toBe(409); // Ravi has not even submitted

    const ok = await upload(meera, ppfId, `${XSS}.pdf`, PDF);
    expect(ok.status, JSON.stringify(ok.body)).toBe(200);
    proofId = ok.body.items.find((i: any) => i.id === ppfId).proofs[0].id;
    await expectRefused(ravi, 'GET', `/org/tax/proofs/${proofId}`, [404]);
    await expectRefused(ravi, 'DELETE', `/org/me/tax/items/${ppfId}/proofs/${proofId}`, [404]);
    await expectRefused(meera, 'DELETE', `/org/me/tax/items/${ppfId}/proofs/00000000-0000-0000-0000-000000000000`, [404]);
    await expectRefused(manager, 'GET', `/org/tax/proofs/${proofId}`, [404, 403]);
  });

  test('HR checks: a note to return, no more than declared, nothing already decided, never their own', async () => {
    await expectRefused(hr, 'POST', `/org/payroll/tax/items/${ppfId}/return`, [400], { note: ' ' });
    await expectRefused(hr, 'POST', `/org/payroll/tax/items/${ppfId}/accept`, [400], { amount: 60000 });
    await expectRefused(hr, 'POST', `/org/payroll/tax/items/${ppfId}/accept`, [400], {});
    await expectRefused(hr, 'POST', `/org/payroll/tax/items/${rentId}/accept`, [409], { amount: 1000 }, 'tax.not_waiting');
    await expectRefused(ravi, 'POST', `/org/payroll/tax/items/${ppfId}/accept`, [403], { amount: 1000 });
    expect((await api(hr, 'POST', `/org/payroll/tax/items/${ppfId}/accept`, { amount: 50000 })).status).toBe(200);
    await expectRefused(hr, 'POST', `/org/payroll/tax/items/${ppfId}/accept`, [409], { amount: 50000 }, 'tax.not_waiting');
    await expectRefused(meera, 'DELETE', `/org/me/tax/items/${ppfId}`, [409], undefined, 'tax.accepted');
    await expectRefused(meera, 'DELETE', `/org/me/tax/items/${ppfId}/proofs/${proofId}`, [409], undefined, 'tax.accepted');
    expect((await upload(meera, ppfId, 'more.pdf', PDF)).body.code).toBe('tax.accepted');
  });

  test('settings: employees and managers have none; nothing outside the range or the layer is taken', async () => {
    const key = 'auth.invitation.valid_days';
    for (const page of [ravi, manager]) {
      expect((await api(page, 'GET', '/org/settings')).body.groups).toEqual([]);
      await expectRefused(page, 'PUT', `/org/settings/${key}`, [403], { value: 3 });
      await expectRefused(page, 'GET', `/org/settings/${key}`, [404]);
    }
    for (const value of [0, 31, 7.5, 'seven', null, true, [5], { v: 5 }]) {
      await expectRefused(owner, 'PUT', `/org/settings/${key}`, [400], { value }, 'setting.out_of_range');
    }
    await expectRefused(owner, 'PUT', `/org/settings/${key}`, [400], { value: 5, scope: 'location', scopeId: '00000000-0000-0000-0000-000000000000' });
    await expectRefused(owner, 'PUT', `/org/settings/${key}`, [400], { value: 5, validFrom: isoDay(0) });
    await expectRefused(owner, 'PUT', `/org/settings/${key}`, [400], { value: 5, reason: 'x'.repeat(401) });
    await expectRefused(owner, 'PUT', `/org/settings/${key}`, [403], { value: 30 }, 'setting.password_required');
    await expectRefused(owner, 'PUT', `/org/settings/${key}`, [403], { value: 30, password: 'guess' }, 'setting.password_wrong');
    await expectRefused(owner, 'PUT', '/org/settings/auth.lockout.minutes', [403, 404], { value: 30 });
    await expectRefused(owner, 'PUT', '/org/settings/no.such.key', [404], { value: 1 }, 'setting.unknown');
    await expectRefused(owner, 'POST', '/org/settings/changes/00000000-0000-0000-0000-000000000000/approve', [404]);
    await expectRefused(owner, 'POST', '/org/settings/changes/00000000-0000-0000-0000-000000000000/undo', [404]);
    // The platform's settings belong to the console.
    await expectRefused(owner, 'GET', '/platform/settings', [401, 403]);
    await expectRefused(owner, 'PUT', '/platform/settings/auth.lockout.minutes', [401, 403], { value: 5 });
    await expectRefused(owner, 'PUT', `/platform/settings/${key}/bound`, [401, 403], { max: 2 });
    expect((await api(owner, 'GET', '/org/settings/changes')).body).toEqual([]);
  });

  test('settings: HR reads the audit trail, so sees Security but cannot change it', async () => {
    await hr.reload();
    await nav(hr, 'Settings');
    await tab(hr, 'Security');
    const row = hr.locator('[data-setting="auth.invitation.valid_days"]');
    await expect(row).toContainText('7 days');
    await expect(row.getByRole('button', { name: 'Change' })).toHaveCount(0);
    await expectRefused(hr, 'PUT', '/org/settings/auth.invitation.valid_days', [403], { value: 3 });
  });

  test('a reason that looks like code is kept as text in the history', async () => {
    expect((await api(owner, 'PUT', '/org/settings/auth.invitation.valid_days', { value: 5, reason: XSS })).status).toBe(200);
    await owner.reload();
    await nav(owner, 'Settings');
    await tab(owner, 'History');
    await expect(owner.getByTestId('settings-history')).toContainText(XSS);
    expect(await owner.evaluate(() => (window as any).__xss)).toBeUndefined();
  });

  test('setup lists answer HR and the owner, and nobody else', async () => {
    for (const path of ['/org/people/setup/changes', '/org/people/setup/document-types', '/org/people/setup/letters']) {
      expect((await api(owner, 'GET', path)).status, path).toBe(200);
      await expectRefused(ravi, 'GET', path, [403]);
      expect((await api(ravi, 'GET', path, undefined, null)).status, path).toBe(401);
    }
    const states = await api(meera, 'GET', '/org/people/states');
    expect(states.status).toBe(200);
    expect(JSON.stringify(states.body)).toContain('Maharashtra');
  });

  test('devices: bad addresses, strangers and oversized logs are turned away', async ({ request }) => {
    expect(await device(request, 'GET', '/getrequest?SN=NOT-REGISTERED')).toBe(404);
    expect(await device(request, 'POST', '/devicecmd?SN=NOT-REGISTERED')).toBe(404);
    expect(await device(request, 'GET', '/cdata?SN=%ZZ')).toBe(400);
    expect(await device(request, 'GET', `/cdata?SN=${encodeURIComponent("'; drop table app.device; --")}`)).toBe(400);
    expect(await device(request, 'POST', '/cdata?SN=NOT-REGISTERED&table=ATTLOG', 'x'.repeat(2 * 1024 * 1024 + 10))).toBe(413);
  });
});
