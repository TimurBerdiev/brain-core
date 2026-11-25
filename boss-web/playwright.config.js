import { defineConfig, devices } from '@playwright/test';

const baseURL = 'http://127.0.0.1:4173';

export default defineConfig({
  testDir: './tests',
  testMatch: '*.spec.js',
  projects: [{ name: 'iPhone 15 WebKit', use: { ...devices['iPhone 15'], baseURL } }],
  webServer: {
    command: 'python3 -m http.server 4173 --bind 127.0.0.1',
    url: `${baseURL}/boss-web/`,
    cwd: '..',
    reuseExistingServer: !process.env.CI,
  },
});
