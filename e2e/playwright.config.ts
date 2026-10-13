import { defineConfig, devices } from '@playwright/test';

// The suites are one story told in order: the Super Admin creates an Organization,
// its people set up attendance, then use leave and work from home. Each project
// depends on the one before, so they always run in this order, on a fresh database.
//
// The app must already be running (backend on 8080, frontend on 5173), or set
// E2E_BASE_URL to wherever it runs.

const desktop = { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } };

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['github']]
    : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173',
    timezoneId: 'Asia/Kolkata',
    locale: 'en-IN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
  },
  projects: [
    { name: 'users', testMatch: /01-users\.spec\.ts/, use: desktop },
    { name: 'attendance', testMatch: /02-attendance\.spec\.ts/, use: desktop, dependencies: ['users'] },
    { name: 'leave', testMatch: /03-leave\.spec\.ts/, use: desktop, dependencies: ['attendance'] },
    { name: 'wfh', testMatch: /04-work-from-home\.spec\.ts/, use: desktop, dependencies: ['leave'] },
    { name: 'people', testMatch: /05-people\.spec\.ts/, use: desktop, dependencies: ['wfh'] },
    { name: 'phone', testMatch: /06-phone\.spec\.ts/, use: desktop, dependencies: ['people'] },
    { name: 'onboarding', testMatch: /07-onboarding\.spec\.ts/, use: desktop, dependencies: ['phone'] },
    { name: 'offer', testMatch: /08-offer-and-codes\.spec\.ts/, use: desktop, dependencies: ['onboarding'] },
    { name: 'setup', testMatch: /09-setup-and-import\.spec\.ts/, use: desktop, dependencies: ['offer'] },
    { name: 'exit', testMatch: /10-exit\.spec\.ts/, use: desktop, dependencies: ['setup'] },
  ],
});
