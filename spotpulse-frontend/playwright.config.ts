import { defineConfig, devices } from '@playwright/test';

// Deliberately no externally configurable baseURL. In particular, Render is never a test target.
const port = Number(process.env.SPOTPULSE_E2E_PORT || '18089');
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid isolated E2E port');
const origin = `http://localhost:${port}`;
export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, forbidOnly: !!process.env.CI,
  retries: 0, timeout: 45_000, expect: { timeout: 12_000 },
  outputDir: 'test-results', reporter: [['list'], ['html', { open: 'never' }]],
  globalTeardown: './e2e/teardown.ts',
  use: { baseURL: origin, screenshot: 'only-on-failure', trace: 'retain-on-failure',
    ...devices['Desktop Chrome'] },
  projects: [{ name: 'chromium' }],
  webServer: {
    command: 'python ../spotpulse-backend/tools/run_e2e.py', url: `${origin}/health`,
    timeout: 120_000, reuseExistingServer: false,
    env: { SPOTPULSE_E2E_PORT: String(port) },
    gracefulShutdown: { signal: 'SIGTERM', timeout: 30_000 },
  },
});
