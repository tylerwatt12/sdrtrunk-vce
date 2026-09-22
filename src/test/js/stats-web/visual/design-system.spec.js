const { expect, test } = require('@playwright/test');

const galleryCases = [
  ['components-light-desktop', 'light', { width: 1280, height: 900 }],
  ['components-dark-desktop', 'dark', { width: 1280, height: 900 }],
  ['components-light-mobile', 'light', { width: 390, height: 844 }],
  ['components-dark-mobile', 'dark', { width: 390, height: 844 }],
];

for(const [name, theme, viewport] of galleryCases) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
    await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}

for(const theme of ['light', 'dark']) {
  test(`modal-${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 900, height: 720 });
    await page.goto(`/design-system.html?theme=${theme}&view=modal`);
    await expect(page.locator('body')).toHaveScreenshot(`modal-${theme}.png`);
  });
}

test('radio-directory-coverage-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=radio-directory-coverage');
  await expect(page.locator('body')).toHaveScreenshot('radio-directory-coverage-light-desktop.png', { fullPage: true });
});

test('radio-directory-coverage-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=radio-directory-coverage');
  await expect(page.locator('body')).toHaveScreenshot('radio-directory-coverage-dark-mobile.png', { fullPage: true });
});

test('admin-scan-lists-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=admin-scan-lists');
  await expect(page.locator('body')).toHaveScreenshot('admin-scan-lists-light-desktop.png', { fullPage: true });
});

test('admin-scan-lists-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=admin-scan-lists');
  await expect(page.locator('body')).toHaveScreenshot('admin-scan-lists-dark-mobile.png', { fullPage: true });
});

test('admin-scan-lists-columns-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=admin-scan-lists-columns');
  await expect(page.locator('body')).toHaveScreenshot('admin-scan-lists-columns-dark-mobile.png', { fullPage: true });
});

test('older administration toggles keep shared switch behavior', async ({ page }) => {
  await page.goto('/design-system.html?theme=light&view=health-alert-modal');
  const toggle = page.locator('.visual-health-alert-modal-example .admin-toggle-control .ui-toggle input').first();
  const dimensions = await toggle.evaluate((input) => {
    const style = getComputedStyle(input);
    return { width: style.width, height: style.height, opacity: style.opacity };
  });
  expect(dimensions).toEqual({ width: '1px', height: '1px', opacity: '0' });
  const before = await toggle.isChecked();
  await toggle.locator('..').click();
  expect(await toggle.isChecked()).toBe(!before);
});

test('tuner-spectrum-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=dark&view=tuner-spectrum');
  await expect(page.locator('body')).toHaveScreenshot('tuner-spectrum-dark-desktop.png', { fullPage: true });
});

test('tuner-spectrum-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=tuner-spectrum');
  await expect(page.locator('body')).toHaveScreenshot('tuner-spectrum-light-mobile.png', { fullPage: true });
});

test('tuner-frequency-popover-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 720 });
  await page.goto('/design-system.html?theme=light&view=tuner-frequency-popover');
  await expect(page.locator('body')).toHaveScreenshot('tuner-frequency-popover-light-desktop.png');
});

test('tuner-frequency-popover-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=tuner-frequency-popover');
  await expect(page.locator('body')).toHaveScreenshot('tuner-frequency-popover-dark-mobile.png');
});

test('rf-planner-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=dark&view=rf-planner');
  await expect(page.locator('body')).toHaveScreenshot('rf-planner-dark-desktop.png', { fullPage: true });
});

test('rf-planner-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=rf-planner');
  await expect(page.locator('body')).toHaveScreenshot('rf-planner-light-mobile.png', { fullPage: true });
});

test('scanner-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=scanner');
  await expect(page.locator('body')).toHaveScreenshot('scanner-light-desktop.png', { fullPage: true });
});

test('scanner-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=scanner');
  await expect(page.locator('body')).toHaveScreenshot('scanner-dark-mobile.png', { fullPage: true });
});

test('aliases-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('/design-system.html?theme=light&view=aliases');
  await expect(page.locator('body')).toHaveScreenshot('aliases-light-desktop.png', { fullPage: true });
});

test('aliases-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=aliases');
  await expect(page.locator('body')).toHaveScreenshot('aliases-dark-mobile.png', { fullPage: true });
});

test('scan-list-members-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/design-system.html?theme=dark&view=scan-list-members');
  await expect(page.locator('body')).toHaveScreenshot('scan-list-members-dark-desktop.png', { fullPage: true });
});

test('scan-list-members-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=scan-list-members');
  await expect(page.locator('body')).toHaveScreenshot('scan-list-members-light-mobile.png', { fullPage: true });
});

test('alias-modal-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=alias-modal');
  await expect(page.locator('body')).toHaveScreenshot('alias-modal-light-desktop.png');
});

test('alias-modal-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=alias-modal');
  await expect(page.locator('body')).toHaveScreenshot('alias-modal-dark-mobile.png');
});

test('alias-export-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=alias-export');
  await expect(page.locator('body')).toHaveScreenshot('alias-export-light-desktop.png');
});

test('alias-export-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=alias-export');
  await expect(page.locator('body')).toHaveScreenshot('alias-export-dark-mobile.png');
});

test('channels-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/design-system.html?theme=dark&view=channels');
  await expect(page.locator('body')).toHaveScreenshot('channels-dark-desktop.png', { fullPage: true });
});

test('channels-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=channels');
  await expect(page.locator('body')).toHaveScreenshot('channels-light-mobile.png', { fullPage: true });
});

test('radio-directory-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/design-system.html?theme=light&view=radio-directory');
  await expect(page.locator('body')).toHaveScreenshot('radio-directory-light-desktop.png', { fullPage: true });
});

test('radio-directory-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=radio-directory');
  await expect(page.locator('body')).toHaveScreenshot('radio-directory-dark-mobile.png', { fullPage: true });
});

test('entity-details-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=entity-details');
  await expect(page.locator('body')).toHaveScreenshot('entity-details-light-desktop.png', { fullPage: true });
});

test('entity-details-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=entity-details');
  await expect(page.locator('body')).toHaveScreenshot('entity-details-dark-mobile.png', { fullPage: true });
});

test('modal-mobile-light', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=modal');
  await expect(page.locator('body')).toHaveScreenshot('modal-mobile-light.png');
});

test('modal-long-dark', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=modal-long');
  const content = page.locator('.visual-modal-example .modal-content');
  await expect.poll(() => content.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await expect(page.locator('body')).toHaveScreenshot('modal-long-dark.png');
});

test('health-alert-modal-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 800 });
  await page.goto('/design-system.html?theme=dark&view=health-alert-modal');
  await expect(page.locator('body')).toHaveScreenshot('health-alert-modal-dark-desktop.png');
});

test('health-alert-modal-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=health-alert-modal');
  await expect(page.locator('body')).toHaveScreenshot('health-alert-modal-light-mobile.png');
});

test('keyboard-focus', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 500 });
  await page.goto('/design-system.html?theme=light&view=focus');
  await page.locator('#visual-focus-target').focus();
  await expect(page.locator('.visual-focus-example')).toHaveScreenshot('keyboard-focus.png');
});

test('settings-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1180, height: 800 });
  await page.goto('/design-system.html?theme=light&view=settings');
  await expect(page.locator('body')).toHaveScreenshot('settings-light-desktop.png', { fullPage: true });
});

test('settings-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=settings');
  await expect(page.locator('body')).toHaveScreenshot('settings-dark-mobile.png', { fullPage: true });
});

test('receiver-health-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=health');
  await expect(page.locator('body')).toHaveScreenshot('receiver-health-light-desktop.png', { fullPage: true });
});

test('receiver-health-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=health');
  await expect(page.locator('body')).toHaveScreenshot('receiver-health-dark-mobile.png', { fullPage: true });
});

test('p25-settings-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/design-system.html?theme=dark&view=p25');
  await expect(page.locator('body')).toHaveScreenshot('p25-settings-dark-desktop.png', { fullPage: true });
});

test('p25-settings-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=p25');
  await expect(page.locator('body')).toHaveScreenshot('p25-settings-light-mobile.png', { fullPage: true });
});

test('admin-access-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=admin-access');
  await expect(page.locator('body')).toHaveScreenshot('admin-access-light-desktop.png', { fullPage: true });
});

test('admin-access-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=admin-access');
  await expect(page.locator('body')).toHaveScreenshot('admin-access-dark-mobile.png', { fullPage: true });
});

test('admin-receiver-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=dark&view=admin-receiver');
  await expect(page.locator('body')).toHaveScreenshot('admin-receiver-dark-desktop.png', { fullPage: true });
});

test('admin-receiver-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=admin-receiver');
  await expect(page.locator('body')).toHaveScreenshot('admin-receiver-light-mobile.png', { fullPage: true });
});

test('admin-support-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=admin-support');
  await expect(page.locator('body')).toHaveScreenshot('admin-support-light-desktop.png', { fullPage: true });
});

test('admin-support-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=admin-support');
  await expect(page.locator('body')).toHaveScreenshot('admin-support-dark-mobile.png', { fullPage: true });
});

test('live-notice-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/design-system.html?theme=dark&view=live-notice');
  await expect(page.locator('body')).toHaveScreenshot('live-notice-dark-desktop.png');
});

test('live-notice-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=live-notice');
  await expect(page.locator('body')).toHaveScreenshot('live-notice-light-mobile.png');
});

test('live-filter-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 980, height: 760 });
  await page.goto('/design-system.html?theme=dark&view=live-filter');
  await expect(page.locator('body')).toHaveScreenshot('live-filter-dark-desktop.png');
});

test('dashboard-health-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=dashboard-health');
  await expect(page.locator('body')).toHaveScreenshot('dashboard-health-light-desktop.png', { fullPage: true });
});

test('dashboard-calls-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=dark&view=dashboard-calls');
  await expect(page.locator('body')).toHaveScreenshot('dashboard-calls-dark-desktop.png', { fullPage: true });
});

test('dashboard-calls-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=dashboard-calls');
  await expect(page.locator('body')).toHaveScreenshot('dashboard-calls-light-mobile.png', { fullPage: true });
});

for(const theme of ['light', 'dark']) {
  test(`dashboard-activity-${theme}-mobile`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`/design-system.html?theme=${theme}&view=dashboard-activity`);
    await expect(page.locator('body')).toHaveScreenshot(`dashboard-activity-${theme}-mobile.png`, { fullPage: true });
  });
}
