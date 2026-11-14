import { defineConfig, devices } from '@playwright/test';

// The UI-driven Organization setup: `npm run setup-org`. See setup-org/README.md.

export default defineConfig({
  testDir: './setup-org',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30 * 60_000,
  expect: { timeout: 15_000 },
  outputDir: './setup-report/results',
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    viewport: { width: 1280, height: 1000 },
    baseURL: process.env.APP_URL || process.env.E2E_BASE_URL || 'http://localhost:5173',
    timezoneId: 'Asia/Kolkata',
    locale: 'en-IN',
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
    ...(process.env.E2E_CHROMIUM_PATH
      ? { launchOptions: { executablePath: process.env.E2E_CHROMIUM_PATH, args: ['--no-sandbox', '--disable-dev-shm-usage'] } }
      : {}),
  },
});
