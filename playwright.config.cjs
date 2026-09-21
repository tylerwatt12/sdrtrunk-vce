const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './src/test/js/stats-web/visual',
  outputDir: './build/playwright-results',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['line'], ['html', { outputFolder: 'build/playwright-report', open: 'never' }]] : 'line',
  snapshotPathTemplate: '{testDir}/__screenshots__/{testFilePath}/{arg}{ext}',
  expect: {
    toHaveScreenshot: {
      animations: 'disabled',
      caret: 'hide',
      scale: 'css',
      threshold: 0.2,
      maxDiffPixelRatio: 0.01,
    },
  },
  use: {
    baseURL: 'http://127.0.0.1:4173',
    browserName: 'chromium',
    colorScheme: 'light',
    deviceScaleFactor: 1,
    locale: 'en-US',
    timezoneId: 'UTC',
  },
  webServer: {
    command: 'node src/test/js/stats-web/visual-server.js',
    url: 'http://127.0.0.1:4173/design-system.html',
    reuseExistingServer: false,
    timeout: 15_000,
  },
});
