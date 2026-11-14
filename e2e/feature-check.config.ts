import { defineConfig, devices } from '@playwright/test';

// The read-only feature check against any running copy of the app: `npm run check`.
// See feature-check/README.md for the settings it reads.

export default defineConfig({
  testDir: './feature-check',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  outputDir: './feature-check-results',
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'feature-check-report' }]],
  use: {
    ...devices['Desktop Chrome'],
    viewport: { width: 1280, height: 900 },
    baseURL: process.env.APP_URL,
    timezoneId: 'Asia/Kolkata',
    locale: 'en-IN',
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    ...(process.env.E2E_CHROMIUM_PATH
      ? { launchOptions: { executablePath: process.env.E2E_CHROMIUM_PATH, args: ['--no-sandbox', '--disable-dev-shm-usage'] } }
      : {}),
  },
});
