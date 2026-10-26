import { defineConfig, devices } from '@playwright/test';

// Most suites are one story told in order: the Super Admin creates an Organization,
// its people set up attendance, then use leave and work from home. Each project in that
// chain depends on the one before, so they always run in this order, on a fresh database.
//
// Suites that create their own Organization (modes, modules, tax, settings, neg-tax-settings)
// are outside the chain and can run beside it on a second worker. E2E_WORKERS sets how many
// run at once (default 2). A chain is still one file at a time, so extra workers only take
// the independent suites. The fixed-date workflow sets 1, as libfaketime slows the JVM.
//
// The app must already be running (backend on 8080, frontend on 5173), or set
// E2E_BASE_URL to wherever it runs.

const desktop = { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } };

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: Number(process.env.E2E_WORKERS) || 2,   // a missing or non-numeric value means 2
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
    // A local Chromium can be named with E2E_CHROMIUM_PATH when Playwright's download is not available.
    ...(process.env.E2E_CHROMIUM_PATH
      ? { launchOptions: { executablePath: process.env.E2E_CHROMIUM_PATH, args: ['--no-sandbox', '--disable-dev-shm-usage'] } }
      : {}),
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
    { name: 'org-chart', testMatch: /11-org-chart\.spec\.ts/, use: desktop, dependencies: ['exit'] },
    { name: 'payroll', testMatch: /12-payroll\.spec\.ts/, use: desktop, dependencies: ['org-chart'] },
    // Negative cases run last, against everything the story above has built.
    { name: 'neg-access', testMatch: /13-negative-access\.spec\.ts/, use: desktop, dependencies: ['payroll'] },
    { name: 'neg-attendance-leave', testMatch: /14-negative-attendance-leave\.spec\.ts/, use: desktop, dependencies: ['neg-access'] },
    { name: 'neg-people', testMatch: /15-negative-people\.spec\.ts/, use: desktop, dependencies: ['neg-attendance-leave'] },
    { name: 'neg-payroll', testMatch: /16-negative-payroll\.spec\.ts/, use: desktop, dependencies: ['neg-people'] },
    // A tour of each role through its own login, for the guide's screenshots.
    { name: 'role-tour', testMatch: /17-role-tour\.spec\.ts/, use: desktop, dependencies: ['neg-payroll'] },
    // Attendance modes and module switches each create their own Organization: --project=modes, --project=modules.
    { name: 'modes', testMatch: /18-attendance-modes\.spec\.ts/, use: desktop },
    { name: 'modules', testMatch: /19-modules\.spec\.ts/, use: desktop },
    // Page addresses, the phone menu, confirmations and notes.
    { name: 'navigation', testMatch: /20-navigation-and-feedback\.spec\.ts/, use: desktop, dependencies: ['role-tour'] },
    // Performance: it adds a joiner on probation to the Organization the story has built.
    { name: 'performance', testMatch: /24-performance\.spec\.ts/, use: desktop, dependencies: ['navigation'] },
    // The Organization's own logo, after performance.
    { name: 'branding', testMatch: /25-branding\.spec\.ts/, use: desktop, dependencies: ['performance'] },
    // A page that crashes stays inside its region and is reported: --project=boundary.
    { name: 'boundary', testMatch: /31-error-boundary\.spec\.ts/, use: desktop },
    // Tax declarations bring their own Organization, so they run on their own as well: --project=tax.
    { name: 'tax', testMatch: /21-tax-declarations\.spec\.ts/, use: desktop },
    // Settings, likewise in an Organization of their own: --project=settings.
    { name: 'settings', testMatch: /22-settings\.spec\.ts/, use: desktop },
    // Negative cases for tax declarations, settings and devices, in an Organization of their own.
    { name: 'neg-tax-settings', testMatch: /23-negative-tax-settings\.spec\.ts/, use: desktop },
  ],
});
