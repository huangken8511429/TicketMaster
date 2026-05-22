import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config — Phase 7 E2E.
 *
 * Strategy:
 *  - Spin up the Vite dev server (with MSW enabled) as a webServer dependency.
 *    MSW supplies a deterministic 3-event seed, including the SOLD_OUT branch
 *    needed for the edge-cases spec.
 *  - Default to chromium for CI / local fast feedback. Firefox + webkit
 *    projects are pre-registered for the verify stage to enable
 *    cross-browser sweeps via `npx playwright test --project=firefox`.
 *  - Default base URL is http://127.0.0.1:5173. Override with
 *    `PLAYWRIGHT_BASE_URL` to point at a deployed preview.
 *
 * Note about the test harness sandbox:
 *  - The current build environment denies Playwright browser downloads. The
 *    config + specs ARE ready to run; once browsers are cached
 *    (`npx playwright install chromium`) the entire suite executes locally.
 */
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : 2,
  reporter: process.env.CI ? [['line'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
  ],
  webServer: process.env.PLAYWRIGHT_NO_SERVER
    ? undefined
    : {
        command: 'npm run dev',
        url: BASE_URL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        env: {
          VITE_USE_MSW: 'true',
        },
      },
});
