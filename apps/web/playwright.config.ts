import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', fullyParallel: false, workers: 1, retries: 0,
  use: { baseURL: 'http://localhost:8080', trace: 'off', screenshot: 'off' },
  reporter: 'list', timeout: 45000,
});
