import { test, expect, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import { SUPER_ADMIN, OWNER, newPage, signIn, nav, expectRefused, expectNoConsoleErrors, expectNoSidewaysScroll, shot } from './helpers';

// Usage statistics, after the story has run: the Super Admin counts the Organizations by hand and sees Globex Ltd, where payroll
// was approved today, as active, then follows it to its 12 weeks and to one metric across Organizations, and exports a CSV. The
// platform team sees numbers only; the page shows no name, amount or anything else from inside an Organization.

test.describe.serial('usage statistics', () => {
  let admin: Page;

  test.afterAll(async () => {
    expectNoConsoleErrors();
    await admin?.context().close();
  });

  test('the Super Admin counts now and sees each Organization by module', async ({ browser }) => {
    admin = await newPage(browser);
    await admin.goto('/');
    await admin.fill('#email', SUPER_ADMIN.email);
    await admin.fill('#password', SUPER_ADMIN.password);
    await admin.click('button.primary');
    await expect(admin.getByText('Platform console')).toBeVisible();
    await nav(admin, 'Usage');
    const grid = admin.getByTestId('usage-grid');
    await expect(grid).toContainText('Counts only');

    await grid.getByRole('button', { name: 'Count again now' }).click();
    await expect(grid.locator('.notice')).toContainText(/Counted \d+ days for \d+ Organizations/);
    await expect(grid).toContainText('Counted up to');

    const globex = grid.locator('tr[data-organization="Globex Ltd"]');
    await expect(globex).toBeVisible();
    await expect(globex.locator('td[data-module="payroll"]')).toHaveAttribute('data-state', 'active');   // a run was approved in the story
    await expect(globex.locator('td[data-module="core"]')).toHaveAttribute('data-state', 'active');
    await expect(globex.locator('td[data-module="payroll"]')).toContainText('in 30 days');
    await expectNoSidewaysScroll(admin);
    await shot(admin, 'usage-01-grid', true);
  });

  test('one Organization over 12 weeks, one metric across Organizations, and a CSV of each', async () => {
    const grid = admin.getByTestId('usage-grid');
    await grid.locator('tr[data-organization="Globex Ltd"]').getByRole('button', { name: 'Globex Ltd' }).click();
    const org = admin.getByTestId('usage-organization');
    await expect(org).toContainText('Globex Ltd: the last 12 weeks');
    const approved = org.locator('tr[data-metric="payroll_runs_approved"]');
    await expect(approved).toBeVisible();
    await expect(approved.getByRole('img')).toHaveAttribute('aria-label', /Payroll runs approved: .*oldest week first/);
    await shot(admin, 'usage-02-organization', true);

    const [orgCsv] = await Promise.all([admin.waitForEvent('download'), org.getByRole('button', { name: 'Export CSV' }).click()]);
    expect(orgCsv.suggestedFilename()).toBe('usage-organization.csv');
    const orgText = fs.readFileSync((await orgCsv.path())!, 'utf8');
    expect(orgText).toContain('Metric,Area,How a week is worked out,');
    expect(orgText).toContain('Payroll runs approved');

    await approved.getByRole('button', { name: 'Payroll runs approved' }).click();
    const metric = admin.getByTestId('usage-metric');
    await expect(metric).toContainText('Payroll runs approved');
    await expect(metric.locator('tr[data-organization="Globex Ltd"]')).toBeVisible();
    const [metricCsv] = await Promise.all([admin.waitForEvent('download'), metric.getByRole('button', { name: 'Export CSV' }).click()]);
    expect(metricCsv.suggestedFilename()).toBe('usage-payroll_runs_approved.csv');
    expect(fs.readFileSync((await metricCsv.path())!, 'utf8')).toMatch(/^Organization,.*,Total\r?\n[\s\S]*Globex Ltd,/);

    await metric.getByRole('button', { name: /Back/ }).click();
    await admin.getByTestId('usage-organization').getByRole('button', { name: /All Organizations/ }).click();
    const [gridCsv] = await Promise.all([admin.waitForEvent('download'), admin.getByTestId('usage-grid').getByRole('button', { name: 'Export CSV' }).click()]);
    expect(gridCsv.suggestedFilename()).toBe('usage-organizations.csv');
    expect(fs.readFileSync((await gridCsv.path())!, 'utf8')).toContain('Globex Ltd');
  });

  test('an Organization sees nothing of it', async ({ browser }) => {
    const owner = await signIn(browser, OWNER);
    await expect(owner.locator('a.nav-item').filter({ hasText: /^Usage$/ })).toHaveCount(0);
    await expectRefused(owner, 'GET', '/platform/usage', [403]);
    await expectRefused(owner, 'GET', '/platform/usage/metrics/payroll_runs_approved', [403]);
    await expectRefused(owner, 'GET', '/platform/usage/grid.csv', [403]);
    await expectRefused(owner, 'POST', '/platform/usage/refresh', [403]);
    await owner.context().close();
  });
});
