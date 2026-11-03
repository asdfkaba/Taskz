import { availableParallelism } from 'node:os'
import { defineConfig } from '@playwright/test'

// Browser tests against the built frontend (dist/) and the mock API in e2e/mock-server.mjs.
// Run with: npm run test:e2e
// The mock keeps state across a test file, so each worker gets its own mock server (port 4999 + its
// index, see e2e/support.mjs) and runs its files one after another, each from a fresh reset.
const PORT = 4999
// Like Playwright's default (half the cores), at most 4: on a small CI runner more workers would
// crowd the tests' short fixed waits.
const WORKERS = Math.max(1, Math.min(4, Math.floor(availableParallelism() / 2)))

export default defineConfig({
  testDir: 'e2e',
  testMatch: '*.spec.mjs',
  fullyParallel: false,
  workers: WORKERS,
  // A spec file is one test with many steps; together they need more than the default 30 s.
  timeout: 180_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    browserName: 'chromium',
    trace: 'retain-on-failure',
    // Locally a system Chromium can be used instead of `npx playwright install chromium`.
    launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {},
  },
  webServer: Array.from({ length: WORKERS }, (_, index) => ({
    command: `node e2e/mock-server.mjs dist ${PORT + index}`,
    url: `http://localhost:${PORT + index}/api/__state`,
    reuseExistingServer: false,
  })),
})
