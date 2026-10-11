import { test, expect, type Page } from '@playwright/test';
import { HR, PASSWORD, ORG, signIn, newPage, nav, tab, isoDay, expectNoSidewaysScroll, expectNoConsoleErrors } from './helpers';

// The offer stage, policies, employee codes and the bell: HR sets up a code series
// and two policies, issues an offer letter, the joiner reads and accepts it on a
// phone and acknowledges the policies; once everything is done the joiner becomes
// an employee with the next code from the series, and HR's bell has kept up.

const JOINER = 'nina.paul@mail.test';
const PDF = Buffer.from('%PDF-1.4\n%scan\n1 0 obj <<>> endobj\n%%EOF\n');

/** Calls the API as the signed-in user of a page. */
async function api(page: Page, method: string, path: string, body?: unknown) {
  return page.evaluate(
    async ({ method, path, body }) => {
      const token = sessionStorage.getItem('hrms-token');
      const raw = body instanceof Array && body[0] === '__bytes__';
      const response = await fetch(`/api${path}`, {
        method,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': raw ? 'application/octet-stream' : 'application/json' },
        body: body === undefined ? undefined : raw ? new Uint8Array(body[1] as number[]) : JSON.stringify(body),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${JSON.stringify(data)}`);
      return data;
    },
    { method, path, body },
  );
}

test.describe.serial('offer, policies and employee codes', () => {
  let hr: Page;
  let joiner: Page;
  let link = '';
  let joinerId = '';

  test.beforeAll(async ({ browser }) => {
    hr = await signIn(browser, HR);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await hr?.context().close();
    await joiner?.context().close();
  });

  test('HR sets up a code series and two policies, and issues an offer', async () => {
    await api(hr, 'POST', '/org/people/setup/number-series', { name: 'Everyone', prefix: 'GLX-{YY}-', digits: 4, startAt: 1, reset: 'yearly' });
    for (const [name, body] of [
      ['Code of conduct', 'Be honest, be kind, and keep what you learn here confidential.'],
      ['IT use', 'Lock your laptop when you step away, and never share your password.'],
    ]) {
      await api(hr, 'POST', '/org/people/setup/policies', { name, body, due: 'before_activation' });
    }

    await nav(hr, 'People');
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Add joiner' }).click();
    await hr.fill('#nj-name', 'Nina Paul');
    await hr.fill('#nj-email', JOINER);
    await hr.fill('#nj-mobile', '91234 11111');
    await hr.fill('#nj-joining', isoDay(0));
    await hr.fill('#nj-designation', 'Analyst');
    await hr.selectOption('#nj-department', { label: 'Engineering' });
    await hr.selectOption('#nj-manager', { label: 'Ananya Sen, Managing director' });
    await hr.selectOption('#nj-location', { index: 1 });
    await hr.selectOption('#nj-shift', { index: 1 });
    // No employee code: the series gives one at activation.
    await hr.getByRole('button', { name: 'Save and invite' }).click();
    await expect(hr.getByRole('heading', { name: /Nina Paul/ })).toBeVisible();
    link = await hr.locator('.copy-row input').inputValue();
    joinerId = (await api(hr, 'GET', '/org/people/joiners?status=open')).joiners.find((j: { legalName: string }) => j.legalName === 'Nina Paul').id;

    await tab(hr, 'Letters and policies');
    await hr.selectOption('#letter-template', { label: 'Offer letter' });
    await hr.fill('#letter-days', '5');
    await hr.getByRole('button', { name: 'Preview' }).click();
    await expect(hr.locator('.letter-preview')).toContainText('Dear Nina Paul,');
    await expect(hr.locator('.letter-preview')).toContainText('position of Analyst, in the Engineering department');
    await hr.getByRole('button', { name: 'Issue letter' }).click();
    await expect(hr.locator('.letter-row[data-letter="Offer letter"]')).toContainText('Waiting for an answer');
    await expect(hr.locator('.section-head h2')).toContainText('offered');
    await expect(hr.locator('[data-policy="Code of conduct"]')).toContainText('Not acknowledged');
  });

  test('the joiner reads the offer on a phone and accepts it, then acknowledges the policies', async ({ browser }) => {
    joiner = await newPage(browser, 390);
    await joiner.goto(link);
    await expect(joiner.getByText(`Join ${ORG}`)).toBeVisible();
    await joiner.fill('#new-password', PASSWORD);
    await joiner.fill('#confirm-password', PASSWORD);
    await joiner.click('button.primary');

    // Only the offer, until it is answered.
    await expect(joiner.getByRole('heading', { name: `${ORG} has an offer for you, Nina` })).toBeVisible();
    await expect(joiner.locator('.section-card')).toHaveCount(0);
    const offer = joiner.locator('[data-letter="Offer letter"]');
    await offer.getByRole('button', { name: 'Read the letter' }).click();
    await expect(joiner.getByRole('dialog').locator('iframe')).toBeVisible();
    await joiner.getByRole('dialog').getByRole('button', { name: 'Close' }).click();
    await offer.getByRole('button', { name: 'Decline' }).click();
    await expect(offer).toContainText('ends your joining here');
    await offer.getByRole('button', { name: 'Keep it' }).click();
    await offer.getByRole('button', { name: 'Accept' }).click();
    await expect(joiner.getByRole('heading', { name: `Welcome to ${ORG}, Nina` })).toBeVisible();
    await expectNoSidewaysScroll(joiner);

    await joiner.getByRole('button', { name: 'I have read this' }).click();
    await expect(joiner.locator('[data-letter="Offer letter"]')).toContainText('You accepted this letter');
    for (const name of ['Code of conduct', 'IT use']) {
      const policy = joiner.locator(`[data-policy="${name}"]`);
      await policy.getByRole('button', { name: 'Read' }).click();
      await policy.getByRole('button', { name: 'I have read and accept this policy' }).click();
      await expect(policy).toContainText('Acknowledged');
    }
    await expectNoSidewaysScroll(joiner);
  });

  test('the bell brings HR to their tasks', async () => {
    await hr.reload();
    const bell = hr.locator('.bell-button');
    await expect(bell.locator('.bell-count')).toBeVisible();
    await bell.click();
    const item = hr.locator('.bell-item', { hasText: 'New task: Send the welcome note and invitation' }).filter({ hasText: 'Nina Paul' });
    await expect(item).toBeVisible();
    await item.click();
    await expect(hr.locator('.page-title')).toHaveText('My tasks');
    await expect(hr.locator('.task-row[data-task="Send the welcome note and invitation"]')).toContainText('Nina Paul');
  });

  test('with everything done, the joiner becomes GLX-yy-0001', async () => {
    // HR completes the rest through the API: the joiner's sections, documents and tasks.
    const id = joinerId;
    const sections: Record<string, unknown> = {
      personal: { 'person.date_of_birth': '1996-08-15', 'person.gender': 'female' },
      address: { 'address.current': { line1: '9 Hill Road', city: 'Pune', stateCode: 'MH', pin: '411001' } },
      emergency: { 'emergency.contacts': [{ name: 'Ravi Paul', relationship: 'Father', mobile: '9822000000' }] },
      bank: { 'bank.account_number': '123456789012', 'bank.ifsc': 'SBIN0001234' },
      statutory: { 'statutory.pan': 'ABCPP1234Q' },
      family: {},
      contact: {},
    };
    for (const [section, values] of Object.entries(sections)) {
      await api(hr, 'PUT', `/org/people/joiners/${id}/sections/${section}`, values);
      await api(hr, 'POST', `/org/people/joiners/${id}/sections/${section}/submit`);
    }
    const detail = await api(hr, 'GET', `/org/people/joiners/${id}`);
    for (const s of detail.sections) {
      if (s.section !== 'documents' && s.status === 'submitted') await api(hr, 'POST', `/org/people/joiners/${id}/sections/${s.section}/verify`, {});
    }
    const docs = await api(hr, 'GET', `/org/people/joiners/${id}/documents`);
    for (const t of docs.types.filter((t: { required: string }) => t.required === 'before_activation')) {
      const up = await api(hr, 'POST', `/org/people/joiners/${id}/documents?type=${t.id}&name=scan.pdf`, ['__bytes__', [...PDF]]);
      await api(hr, 'POST', `/org/people/joiners/${id}/documents/${up.id}/verify`);
    }
    const checklist = await api(hr, 'GET', `/org/people/joiners/${id}/tasks`);
    for (const t of checklist.tasks.filter((t: { doneAt: string | null }) => !t.doneAt)) {
      await api(hr, 'POST', `/org/people/tasks/${t.id}/done`);
    }

    // Reading the joiner activates them, since they join today and nothing is left.
    await nav(hr, 'People');
    const yy = isoDay(0).slice(2, 4);
    await tab(hr, 'Joiners');
    await hr.getByRole('button', { name: 'Joined', exact: true }).click();
    await expect(hr.locator('tbody tr', { hasText: 'Nina Paul' })).toContainText('active');
    await tab(hr, 'Employees');
    await expect(hr.locator('tbody tr', { hasText: 'Nina Paul' })).toContainText(`GLX-${yy}-0001`);

    // The joiner is told, under the bell.
    await joiner.reload();
    await joiner.locator('.bell-button').click();
    await expect(joiner.locator('.bell-item', { hasText: `Your employee code is GLX-${yy}-0001` })).toBeVisible();
  });
});
