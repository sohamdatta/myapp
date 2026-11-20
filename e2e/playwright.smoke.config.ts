import { defineConfig, devices } from '@playwright/test';

// The smoke sweep: sign in to an app that is already running and already holds data (local, UAT or a demo
// Organization), open every screen, tab and the first few employees, and report anything that errors.
// It reads only. See smoke/README.md.
export default defineConfig({
  testDir: './smoke',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 15 * 60_000,
  reporter: [['list']],
  use: {
    baseURL: process.env.SMOKE_BASE_URL || process.env.E2E_BASE_URL || 'http://localhost:5173',
    ...devices['Desktop Chrome'],
    viewport: { width: 1280, height: 900 },
    timezoneId: 'Asia/Kolkata',
    locale: 'en-IN',
    screenshot: 'off',
    actionTimeout: 15_000,
    ...(process.env.E2E_CHROMIUM_PATH
      ? { launchOptions: { executablePath: process.env.E2E_CHROMIUM_PATH, args: ['--no-sandbox', '--disable-dev-shm-usage'] } }
      : {}),
  },
});
