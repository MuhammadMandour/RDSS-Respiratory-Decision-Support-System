import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests run against an already running stack (see e2e/README.md):
 *   docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
 * They use the synthetic demo accounts, so SEED_DEMO_DATA must be enabled.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1, // tests share one database; keep them sequential and deterministic
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1366, height: 900 },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 900 } } }],
});
