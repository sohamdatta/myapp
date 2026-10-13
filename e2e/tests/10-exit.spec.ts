import { test, expect, type Page } from '@playwright/test';
import { HR, OWNER, PASSWORD, signIn, nav, tab, isoDay, expectNoSidewaysScroll, expectNoConsoleErrors, shot } from './helpers';

// Exits: an employee resigns on a phone, their manager recommends a last day, HR accepts it with
// the notice not served set against leave, the employee asks to withdraw and HR refuses, and HR
// starts an exit for someone else and changes the exit settings.

const MEERA = 'meera.iyer@mail.test';

test.describe.serial('exit', () => {
  let hr: Page;
  let owner: Page;
  let meera: Page;

  test.beforeAll(async ({ browser }) => {
    hr = await signIn(browser, HR);
  });
  test.afterAll(async () => {
    expectNoConsoleErrors();
    await hr?.context().close();
    await owner?.context().close();
    await meera?.context().close();
  });

  test('an employee resigns on a phone and sees their notice', async ({ browser }) => {
    meera = await signIn(browser, MEERA, PASSWORD, 390);
    await nav(meera, 'My exit');
    await expect(meera.getByText('Your notice period is')).toContainText('30 days');
    await meera.getByRole('button', { name: 'Resign…' }).click();
    await meera.selectOption('#resign-reason', 'career');
    await meera.fill('#resign-last-day', isoDay(25));
    await meera.fill('#resign-note', 'A role closer to my family');
    await shot(meera, '81-resign', true);
    await meera.getByRole('button', { name: 'Send my resignation' }).click();
    await expect(meera.getByRole('heading', { name: /Your resignation/ })).toContainText('Waiting for your manager');
    await expectNoSidewaysScroll(meera);
    await shot(meera, '82-resigned', true);
  });

  test('the manager recommends a last day, and HR accepts it with the shortfall set against leave', async ({ browser }) => {
    owner = await signIn(browser, OWNER);   // Meera reports to the Org Admin
    await nav(owner, 'Exits');
    await owner.getByRole('button', { name: 'Meera Iyer' }).click();
    await owner.getByRole('button', { name: 'Recommend' }).click();
    await owner.fill('#rec-day', isoDay(20));
    await expect(owner.getByText('days before the notice ends')).toBeVisible();
    await owner.fill('#rec-note', 'Handover in two weeks is enough');
    await owner.getByRole('button', { name: 'Send to HR' }).click();
    await expect(owner.locator('.section-head h2')).toContainText('Waiting for HR');

    await nav(hr, 'Exits');
    await expect(hr.locator('tr[data-exit="Meera Iyer"]')).toContainText('Waiting for HR');
    await shot(hr, '83-exits-list');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await expect(hr.locator('.panels')).toContainText('A role closer to my family');   // HR reads the employee's own words
    await hr.getByRole('button', { name: 'Accept' }).click();
    await expect(hr.locator('label[for="acc-shortfall"]')).toHaveText('10 days of notice not served');
    await hr.selectOption('#acc-shortfall', 'leave');
    await shot(hr, '84-accept', true);
    await hr.locator('form').getByRole('button', { name: 'Accept' }).click();
    await expect(hr.locator('.section-head h2')).toContainText('Serving notice');
    await expect(hr.locator('.panels')).toContainText('10 days · Set against earned leave');
    await shot(hr, '85-accepted', true);

    await meera.reload();
    await nav(meera, 'My exit');
    await expect(meera.getByRole('heading', { name: /Your resignation/ })).toContainText('Serving notice');
    await expect(meera.getByTestId('my-last-day')).not.toHaveText('—');
  });

  test('clearance: tasks go to their owners, and the leaver records a claim owed to them', async () => {
    await expect(hr.locator('.clearance')).toContainText('0 of 9 done');
    await expect(hr.locator('.clearance .task-row[data-task="Plan the handover of work"]')).toContainText('Ananya Sen');
    await shot(hr, '88-clearance', true);

    await owner.reload();
    await nav(owner, 'My tasks');
    // Kiran Pal's exit, recorded earlier, has its own clearance; Meera's handover is the one to do here.
    const handover = owner.locator('.task-row[data-task="Plan the handover of work"]', { hasText: 'Meera Iyer, leaving' });
    await expect(handover).toBeVisible();
    await handover.getByRole('button', { name: 'Mark done' }).click();
    await expect(handover).toHaveCount(0);

    await meera.reload();
    await nav(meera, 'My exit');
    const claims = meera.locator('.task-row[data-task="Submit pending expense claims"]');
    await claims.getByRole('button', { name: 'Done, with an amount…' }).click();
    await meera.fill(`[id^="amount-"]:not([id^="amount-note"])`, '1500');
    await meera.locator('[id^="direction-"]').selectOption('owed_to_employee');
    await meera.locator('[id^="amount-note-"]').fill('Travel to the client in September');
    await shot(meera, '89-leaver-claim', true);
    await claims.getByRole('button', { name: 'Mark done' }).click();
    await expect(claims).toContainText('₹1,500 owed to the leaver');
    await expectNoSidewaysScroll(meera);
  });

  test('the employee asks to withdraw, and HR refuses', async () => {
    await meera.getByRole('button', { name: 'Ask to withdraw my resignation' }).click();
    await meera.fill('#withdraw-note', 'I would like to stay after all');
    await meera.getByRole('button', { name: 'Ask to withdraw', exact: true }).click();
    await expect(meera.getByText('You asked to withdraw your resignation')).toBeVisible();

    await hr.reload();
    await nav(hr, 'Exits');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await expect(hr.getByText('I would like to stay after all')).toBeVisible();
    await hr.fill('#wd-note', 'The role is already being filled');
    await hr.getByRole('button', { name: 'Refuse' }).click();
    await expect(hr.locator('.section-head h2')).toContainText('Serving notice');
  });

  test('HR starts an exit for another employee, and sets the notice periods', async () => {
    await hr.getByRole('button', { name: '← All exits' }).click();
    await hr.getByRole('button', { name: 'Start an exit' }).click();
    const nina = await hr.locator('#se-employee option', { hasText: 'Nina Paul' }).getAttribute('value');
    await hr.selectOption('#se-employee', nina!);
    await hr.selectOption('#se-kind', 'termination');
    await hr.selectOption('#se-reason', 'performance');
    await hr.fill('#se-note', 'Agreed after the review');
    await hr.fill('#se-day', isoDay(10));
    await hr.selectOption('#se-shortfall', 'paid_in_lieu');
    await shot(hr, '86-start-exit', true);
    await hr.getByRole('button', { name: 'Start the exit' }).click();
    await expect(hr.getByRole('heading', { name: /Nina Paul/ })).toContainText('Serving notice');
    await expect(hr.locator('.panels')).toContainText('Notice paid in lieu');

    await nav(hr, 'People');
    await expect(hr.locator('tbody tr', { hasText: 'Nina Paul' })).toContainText('Leaving');
    await tab(hr, 'Setup');
    await tab(hr, 'Exits');
    await hr.fill('#ex-notice-permanent', '45');
    await hr.getByLabel('Encash Earned leave').check();
    await hr.getByRole('button', { name: 'Save exit settings' }).click();
    await expect(hr.getByText('Saved.')).toBeVisible();
    await shot(hr, '87-exit-setup', true);

    await nav(hr, 'Audit log');
    for (const action of ['exit.submitted', 'exit.recommended', 'exit.accepted', 'exit.withdrawal_refused', 'exit.started']) {
      await expect(hr.locator('code', { hasText: action }).first()).toBeVisible();
    }
  });

  test('the settlement is worked out, approved by a second person, paid, and sent to the employee', async () => {
    await nav(hr, 'Exits');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    const panel = hr.getByTestId('settlement');
    await panel.getByRole('button', { name: 'Work out the settlement' }).click();
    await expect(panel).toContainText('Draft');
    await expect(panel.getByTestId('line-salary')).toBeVisible();
    await expect(panel.getByTestId('line-leave_encashment')).toBeVisible();
    await expect(panel.getByTestId('line-reimbursement').getByRole('spinbutton', { name: /Amount/ })).toHaveValue('1500');
    const amounts: Record<string, string> = { salary: '42000', leave_encashment: '8000', tax: '2000' };
    const rows = panel.locator('tbody tr[data-testid^="line-"]');
    const count = await rows.count();
    for (let i = 0; i < count; i++) {
      const row = rows.nth(i);
      const code = (await row.getAttribute('data-testid'))!.slice(5);
      const amount = row.getByRole('spinbutton', { name: /Amount/ });
      if (amounts[code] || (await amount.inputValue()) === '') {
        await amount.fill(amounts[code] ?? '0');
        await row.getByRole('button', { name: 'Save' }).click();
        await expect(row.getByRole('button', { name: 'Save' })).toHaveCount(0);
      }
    }
    await expect(panel.getByTestId('settlement-net')).toHaveText('₹49,500.00');
    await expect(panel.getByRole('button', { name: 'Approve and send the statement' })).toHaveCount(0);   // not by who prepared it
    await shot(hr, '90-settlement-draft', true);

    await owner.reload();
    await nav(owner, 'Exits');
    await owner.getByRole('button', { name: 'Meera Iyer' }).click();
    await owner.getByTestId('settlement').getByRole('button', { name: 'Approve and send the statement' }).click();
    await expect(owner.getByTestId('settlement')).toContainText('Approved, being paid');

    await hr.reload();
    await nav(hr, 'Exits');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    const salary = hr.getByTestId('settlement').getByTestId('line-salary');
    await salary.getByRole('button', { name: 'Mark paid…' }).click();
    await hr.locator('[id^="paid-ref-"]').fill('UTR20261031001');
    await hr.getByRole('button', { name: 'Record' }).click();
    await expect(salary).toContainText('UTR20261031001');
    await shot(hr, '91-settlement-paid', true);

    await meera.reload();
    await nav(meera, 'My exit');
    const mine = meera.getByTestId('my-settlement');
    await expect(mine).toContainText('₹49,500.00');
    const download = meera.waitForEvent('download');
    await mine.getByRole('button', { name: 'Download the statement' }).click();
    expect((await download).suggestedFilename()).toBe('Full and final settlement.pdf');
    await expectNoSidewaysScroll(meera);
    await shot(meera, '92-my-settlement', true);
  });

  test('letters wait for their moment, and the exit interview is answered and read only by HR', async () => {
    await hr.reload();
    await nav(hr, 'Exits');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    const letters = hr.getByTestId('exit-letters');
    await expect(letters.locator('li[data-letter="Resignation acceptance"]')).toBeVisible();   // issued on acceptance
    const relieving = letters.locator('[data-template="Relieving letter"]');
    await expect(relieving).toContainText('Waits for the last working day');
    await expect(relieving.getByRole('button', { name: 'Issue' })).toBeDisabled();
    await relieving.getByRole('button', { name: 'Preview' }).click();
    await expect(letters.locator('.letter-preview')).toContainText('Meera Iyer');

    const interview = hr.getByTestId('exit-interview');
    await interview.getByRole('button', { name: 'Send the interview' }).click();
    await expect(interview).toContainText('Sent, waiting for answers');
    await shot(hr, '93-letters-interview', true);

    await meera.reload();
    await nav(meera, 'My exit');
    await expect(meera.getByTestId('my-letters')).toContainText('Resignation acceptance');
    const mine = meera.getByTestId('my-interview');
    await mine.getByRole('radiogroup', { name: 'How well did your manager support you?' }).getByLabel('4').check();
    await mine.getByRole('combobox', { name: 'What is the main reason you are leaving?' }).selectOption('Career growth');
    await mine.getByRole('textbox', { name: 'What one thing should we change?' }).fill('More time for learning');
    await expectNoSidewaysScroll(meera);
    await shot(meera, '94-my-interview', true);
    await mine.getByRole('button', { name: 'Send my answers' }).click();
    await expect(mine).toContainText('Thank you for your answers');

    await hr.reload();
    await nav(hr, 'Exits');
    await hr.getByRole('button', { name: 'Meera Iyer' }).click();
    await hr.getByTestId('exit-interview').getByRole('button', { name: 'Read it' }).click();
    await expect(hr.getByTestId('exit-interview')).toContainText('More time for learning');
    await expect(hr.getByTestId('exit-interview')).toContainText('4 of 5');
  });

  test('absence without leave waits for the show-cause notice before it can be confirmed', async () => {
    await hr.reload();
    await nav(hr, 'Exits');
    await hr.getByRole('button', { name: 'Start an exit' }).click();
    const rahul = await hr.locator('#se-employee option', { hasText: 'Rahul Bose' }).getAttribute('value');
    await hr.selectOption('#se-employee', rahul!);
    await hr.selectOption('#se-kind', 'absconding');
    await expect(hr.locator('label[for="se-day"]')).toHaveText('Last day at work');
    await hr.fill('#se-day', isoDay(-10));
    await hr.getByRole('button', { name: 'Start the exit' }).click();
    await expect(hr.getByRole('heading', { name: /Rahul Bose/ })).toContainText('Absent without leave');
    await expect(hr.getByTestId('absconding')).toContainText('Send the show-cause notice first');
    const notice = hr.getByTestId('exit-letters').locator('[data-template="Show-cause notice"]');
    await notice.getByRole('button', { name: 'Issue' }).click();
    await expect(hr.getByTestId('exit-letters').locator('li[data-letter="Show-cause notice"]')).toBeVisible();
    await expect(hr.getByTestId('absconding')).toContainText('The show-cause notice can be answered until');
    await shot(hr, '95-absconding', true);
  });
});
