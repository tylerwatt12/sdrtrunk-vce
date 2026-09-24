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

test('stat counters share a rounded tile with a decorative landmark icon', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=entity-details');
  const tile = page.locator('.visual-entity-details-example .ui-metric').first();
  await expect(tile).toHaveCSS('border-left-width', '1px');
  const tileRadius = await tile.evaluate((element) => getComputedStyle(element).borderRadius);
  const controlRadius = await page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue('--radius-control').trim());
  expect(tileRadius).toBe(controlRadius);
  await expect(tile.locator('.ui-metric-icon svg')).toHaveAttribute('aria-hidden', 'true');
  await expect(tile.locator('.ui-metric-label')).toHaveText('Logical Calls');
  await expect(page.locator('.visual-entity-details-example .ui-metric a')).toHaveText('9');
  const signaling = page.locator('.visual-entity-details-example .ui-section')
    .filter({ has: page.locator('.ui-section-title:text-is("Retained Signaling Totals")') });
  await expect(signaling.locator('.ui-metric-compact')).toHaveCount(6);
  await expect(signaling.locator('.ui-metric-label')).toHaveText([
    'Continue', 'Active', 'Join', 'Register', 'Emergency', 'Status'
  ]);
});

test('workspace density changes spacing without changing visual identity', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=gallery');
  const styles = await page.evaluate(() => {
    const sample = (density) => {
      const host = document.createElement('div');
      host.className = density;
      host.innerHTML = '<button class="ui-button">Action</button><div class="ui-surface">Surface</div>';
      document.body.append(host);
      const button = getComputedStyle(host.querySelector('.ui-button'));
      const surface = getComputedStyle(host.querySelector('.ui-surface'));
      const result = {
        buttonHeight: parseFloat(button.height),
        buttonRadius: button.borderRadius,
        buttonFontSize: button.fontSize,
        buttonFontWeight: button.fontWeight,
        buttonShadow: button.boxShadow,
        surfaceRadius: surface.borderRadius,
        surfaceShadow: surface.boxShadow,
      };
      host.remove();
      return result;
    };
    return { compact: sample('data-workspace'), comfortable: sample('editor-workspace') };
  });

  expect(styles.compact.buttonHeight).toBeLessThan(styles.comfortable.buttonHeight);
  expect(styles.compact.buttonRadius).toBe(styles.comfortable.buttonRadius);
  expect(styles.compact.buttonFontSize).toBe(styles.comfortable.buttonFontSize);
  expect(styles.compact.buttonFontWeight).toBe(styles.comfortable.buttonFontWeight);
  expect(styles.compact.buttonShadow).toBe(styles.comfortable.buttonShadow);
  expect(styles.compact.surfaceRadius).toBe(styles.comfortable.surfaceRadius);
  expect(styles.compact.surfaceShadow).not.toBe('none');
  expect(styles.comfortable.surfaceShadow).not.toBe('none');
});

test('channel frequencies and band plans share a flush responsive layout', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=entity-details');
  await page.evaluate(() => {
    document.body.dataset.view = 'channel';
    document.body.innerHTML = `<main class="content"><div class="channel-frequency-layout two-columns">
      <div class="channel-frequency-column"><section class="section ui-section"><div class="section-title ui-section-title">Frequencies</div><div class="async-section-content ui-table-content"><div class="table-wrap ui-table-wrap"><table class="data-table ui-data-table ui-data-table-calm"><thead><tr><th>Channel</th><th>Down MHz</th><th>Seen</th></tr></thead><tbody><tr><td>01-01</td><td>851.0125</td><td><time class="ui-time-stacked" datetime="2026-09-23T20:09:05"><span>2026-09-23</span><span>20:09:05</span></time></td></tr></tbody></table></div></div></section></div>
      <div class="channel-band-plan-column"><div class="async-section-content"><section class="section ui-section"><div class="section-title ui-section-title">Home System Band Plan</div><div class="table-wrap ui-table-wrap"><table class="data-table ui-data-table ui-data-table-calm"><thead><tr><th>Band</th><th>Base</th></tr></thead><tbody><tr><td>0</td><td>851.00625</td></tr></tbody></table></div></section></div></div>
    </div></main>`;
  });
  const layout = page.locator('.channel-frequency-layout');
  const frequency = page.locator('.channel-frequency-column');
  const bandPlan = page.locator('.channel-band-plan-column');
  await expect(page.locator('.channel-frequency-column .async-section-content')).toHaveCSS('padding', '0px');
  await expect(page.locator('.channel-frequency-column th').first()).toHaveCSS('border-right-width', '0px');
  await expect(page.locator('.ui-time-stacked')).toHaveCSS('display', 'inline-grid');
  expect((await frequency.boundingBox()).y).toBe((await bandPlan.boundingBox()).y);
  expect((await layout.boundingBox()).width).toBeGreaterThan((await frequency.boundingBox()).width * 1.9);
  await page.setViewportSize({ width: 390, height: 844 });
  expect((await bandPlan.boundingBox()).y).toBeGreaterThan((await frequency.boundingBox()).y);
});

test('async tables meet section edges while non-table content keeps padding', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 600 });
  await page.goto('/design-system.html?theme=light&view=entity-details');
  await page.evaluate(() => {
    document.body.innerHTML = `<main class="content">
      <section class="section ui-section">
        <div class="section-title ui-section-title">Neighbors</div>
        <div class="async-section-content ui-table-content">
          <div class="table-wrap ui-table-wrap"><table class="data-table ui-data-table">
            <thead><tr><th>State</th><th>Name / Site</th><th>Seen</th></tr></thead>
            <tbody><tr><td colspan="3" class="empty ui-table-empty">No neighbors recorded</td></tr></tbody>
          </table></div>
          <nav class="pager ui-pager pager-bottom">Neighbors 0-0</nav>
        </div>
      </section>
      <section class="section ui-section"><div class="section-title ui-section-title">Loading</div>
        <div class="async-section-content"><div>Loading rows…</div></div></section>
    </main>`;
  });
  const sections = page.locator('.ui-section');
  const tableBody = sections.first().locator('.async-section-content');
  await expect(tableBody).toHaveCSS('padding', '0px');
  await expect(sections.last().locator('.async-section-content')).toHaveCSS('padding', '20px');
  const card = await sections.first().boundingBox();
  const table = await tableBody.locator('.ui-table-wrap').boundingBox();
  expect(Math.abs(table.x - card.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(table.x + table.width - card.x - card.width)).toBeLessThanOrEqual(1);
  await expect(sections.first()).toHaveScreenshot('async-table-flush-light-desktop.png');
});

test('icon actions share a size and show one hint on hover and focus', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=gallery');
  const action = page.locator('.visual-icon-button');
  await expect(action).toHaveCSS('width', '40px');
  await expect(action).toHaveCSS('height', '40px');
  await action.hover();
  const hint = page.locator('.ui-icon-hint');
  await expect(hint).toBeVisible();
  await expect(hint).toHaveText('Receiver health');
  await page.mouse.move(0, 0);
  await expect(hint).toBeHidden();
  await action.focus();
  await expect(hint).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(hint).toBeHidden();

  await page.goto('/design-system.html?theme=light&view=app-chrome');
  await expect(page.locator('.visual-app-chrome-example .theme-toggle')).toHaveCSS('width', '40px');
  await expect(page.locator('.visual-app-chrome-example .playback-icon-command').first()).toHaveCSS('width', '40px');
});

test('disabled icon hint stays visible outside a table', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=admin-scan-lists');
  const hint = page.locator('.ui-icon-hint');
  await expect(hint).toHaveCount(1);
  const deleteDefault = page.getByRole('button', { name: 'Delete County Public Safety' });
  await deleteDefault.scrollIntoViewIfNeeded();
  await page.mouse.move(0, 0);
  await deleteDefault.hover();
  await expect(hint).toBeVisible();
  await expect(hint).toHaveText(/Choose another default scan list before deleting this one/);
  await expect(deleteDefault).not.toHaveAttribute('title');
  const bounds = await hint.boundingBox();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390);
  await deleteDefault.evaluate((element) => { element.disabled = true; });
  await page.mouse.move(0, 0);
  await deleteDefault.hover();
  await expect(hint).toBeVisible();
});

test('app-chrome-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto('/design-system.html?theme=light&view=app-chrome');
  await expect(page.locator('body')).toHaveScreenshot('app-chrome-light-desktop.png');
});

test('app-chrome-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=app-chrome');
  await expect(page.locator('body')).toHaveScreenshot('app-chrome-dark-mobile.png');
});

test('app chrome keeps desktop navigation and mobile playback controls distinct', async ({ page }) => {
  const navigation = page.locator('.visual-app-chrome-example .navigation-toggle');
  const playbackMenu = page.locator('.visual-app-chrome-example .playback-control-menu');
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto('/design-system.html?theme=light&view=app-chrome');
  await expect(navigation).toBeHidden();
  await expect(playbackMenu).toHaveAttribute('open', '');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(navigation).toBeVisible();
  await expect(playbackMenu).not.toHaveAttribute('open', '');
  await expect(page.locator('.visual-app-chrome-example .playback-control-menu > summary')).toBeVisible();
});

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

for(const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`radio-directory-panel-${theme}-${width === 390 ? 'mobile' : 'desktop'}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?theme=${theme}&view=radio-directory-panel`);
    const panel = page.locator('.visual-radio-directory-coverage-example .ui-section');
    const body = panel.locator('.radio-directory-panel-body');
    await expect(body).toHaveCSS('padding', '12px');
    await expect(panel).toHaveScreenshot(
      `radio-directory-panel-${theme}-${width === 390 ? 'mobile' : 'desktop'}.png`,
      { fullPage: true });
  });
}

for(const [view, theme, width] of [
  ['signal-quality-detail', 'light', 1280],
  ['signal-quality-detail', 'dark', 390],
  ['radioreference-results', 'light', 1280],
  ['radioreference-results', 'dark', 390],
]) {
  test(`${view}-${theme}-${width === 390 ? 'mobile' : 'desktop'}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?theme=${theme}&view=${view}`);
    await expect(page.locator('body')).toHaveScreenshot(
      `${view}-${theme}-${width === 390 ? 'mobile' : 'desktop'}.png`, { fullPage: true });
  });
}

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

for(const [name, theme, viewport] of [
  ['tuner-more-measurements-light-desktop', 'light', { width: 1280, height: 900 }],
  ['tuner-more-measurements-dark-mobile', 'dark', { width: 390, height: 844 }],
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=tuner-spectrum`);
    await page.locator('.tuner-spectrum-options > summary').click();
    await page.locator('.tuner-spectrum-more-measurements > summary').click();
    await expect(page.locator('.tuner-spectrum-more-measurements')).toHaveAttribute('open', '');
    await expect(page.locator('.tuner-spectrum-more-readouts')).toBeVisible();
    await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}

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
  await expect(page.locator('.scanner-audio-wave > i')).toHaveCount(64);
  expect(await page.locator('.scanner-workspace > *').evaluateAll((elements) => elements.map((element) => element.className)))
    .toEqual(['scanner-status-bar', 'scanner-controls', 'scanner-utility-row', 'scanner-display-shell']);
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

test('selected channel actions remain below the app header while scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto('/design-system.html?theme=light&view=channels');
  await page.evaluate(() => {
    document.documentElement.style.setProperty('--app-header-offset', '64px');
    const workspace = document.querySelector('.visual-channels-example');
    workspace.classList.add('ui-section', 'channel-catalog-section');
    const spacer = document.createElement('div');
    spacer.style.height = '1200px';
    workspace.querySelector('.channel-admin-catalog').append(spacer);
  });
  await page.evaluate(() => window.scrollTo(0, 600));
  await expect.poll(() => page.locator('.channel-selection-bar').evaluate(
    (element) => Math.round(element.getBoundingClientRect().top))).toBe(72);
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

test('page section navigation wraps instead of hiding destinations on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?view=entity-details&theme=dark');
  const navigation = page.locator('.visual-entity-details-example .ui-page-nav');
  await navigation.evaluate((element) => {
    ['Frequencies', 'Quality', 'Neighbors', 'Band Plan', 'Activity'].forEach((label) => {
      const link = document.createElement('a');
      link.className = 'ui-segmented-option';
      link.href = '#';
      link.textContent = label;
      element.append(link);
    });
  });
  expect(await navigation.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
  expect(await navigation.evaluate((element) => element.getBoundingClientRect().height)).toBeGreaterThan(60);
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
