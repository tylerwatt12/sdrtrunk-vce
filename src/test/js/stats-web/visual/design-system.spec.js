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

test('interface guidance demonstrates the current composition rules', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=gallery');
  await expect(page.locator('.visual-guidance-grid article')).toHaveCount(4);
  const dataWorkspace = page.locator('.visual-section').filter({
    has: page.locator('h2:text-is("Data workspace")')
  });
  await expect(dataWorkspace.locator('.ui-section-title [aria-label="Choose table columns"]')).toBeVisible();
  await expect(dataWorkspace.getByRole('button', { name: 'Columns', exact: true })).toHaveCount(0);
  const exampleTable = dataWorkspace.locator('.ui-table-content table');
  await expect(exampleTable).toBeVisible();
  await expect(exampleTable).toHaveClass(/resizable-table/);
  await expect(exampleTable).toHaveAttribute('data-table-type', 'interface-guidance-channels');
  await expect(exampleTable).toHaveAttribute('data-mobile-cards', 'true');
  await expect(exampleTable.locator('colgroup col')).toHaveCount(3);
  await expect(exampleTable.locator('.column-resizer')).toHaveCount(3);
  const channelResize = exampleTable.locator('[data-column="channel"] .column-resizer');
  const initialWidth = Number(await channelResize.getAttribute('aria-valuenow'));
  await channelResize.focus();
  await channelResize.press('ArrowRight');
  await expect(exampleTable.locator('[data-column="channel"] .column-resizer'))
    .toHaveAttribute('aria-valuenow', String(initialWidth + 12));
  await dataWorkspace.getByRole('button', { name: 'Choose table columns' }).click();
  await expect(page.getByRole('dialog', { name: 'Table columns' })).toBeVisible();
  await page.getByRole('button', { name: 'Move State left' }).click();
  await expect(exampleTable.locator('thead th')).toHaveText(['Channel', 'State', 'Protocol']);
  await dataWorkspace.getByRole('button', { name: 'Choose table columns' }).click();
  await page.getByLabel('Show Protocol column').uncheck();
  await expect(exampleTable.locator('thead th')).toHaveText(['Channel', 'State']);
  await page.reload();
  const restoredTable = dataWorkspace.locator('.ui-table-content table');
  await expect(restoredTable.locator('thead th')).toHaveText(['Channel', 'State']);
  await expect(restoredTable.locator('[data-column="channel"] .column-resizer'))
    .toHaveAttribute('aria-valuenow', String(initialWidth + 12));
  await expect(page.locator('.ui-inline-create-trigger')).toHaveAttribute('aria-controls',
    'visual-inline-alias-create');
  await expect(page.getByLabel('Alias List', { exact: true })).toHaveValue('Default P25');
  const unlabeledIconControls = await page.locator([
    '.ui-icon-button', '.icon-button', '.ui-header-indicator', '.playback-command',
    '.playback-control-menu > summary', '.channels-tab-close'
  ].join(', ')).evaluateAll((controls) => controls.filter((control) =>
    !control.getAttribute('aria-label') && !control.getAttribute('title')).map((control) => control.outerHTML));
  expect(unlabeledIconControls).toEqual([]);
});

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

test('column auto-fit measures intrinsic table content off screen', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=gallery');
  const measurement = await page.evaluate(() => {
    const table = document.createElement('table');
    table.className = 'ui-data-table resizable-table table-column-autofit-measurement';
    table.innerHTML = '<tbody><tr><th><span class="table-column-label">Auto fit</span></th></tr>'
      + '<tr><td>Compact value</td></tr></tbody>';
    document.body.append(table);
    const tableStyle = getComputedStyle(table);
    const cellStyle = getComputedStyle(table.querySelector('td'));
    const labelStyle = getComputedStyle(table.querySelector('.table-column-label'));
    const result = {
      width: table.getBoundingClientRect().width,
      position: tableStyle.position,
      tableLayout: tableStyle.tableLayout,
      visibility: tableStyle.visibility,
      whiteSpace: cellStyle.whiteSpace,
      labelOverflow: labelStyle.overflow,
    };
    table.remove();
    return result;
  });
  expect(measurement.width).toBeGreaterThan(48);
  expect(measurement.width).toBeLessThan(300);
  expect(measurement).toMatchObject({
    position: 'fixed', tableLayout: 'auto', visibility: 'hidden',
    whiteSpace: 'nowrap', labelOverflow: 'visible'
  });
});

test('icon actions show hints on hover and keyboard-visible focus only', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=gallery');
  const action = page.getByRole('button', { name: 'Receiver health' });
  await expect(action).toHaveCSS('width', '40px');
  await expect(action).toHaveCSS('height', '40px');
  await action.hover();
  const hint = page.locator('.ui-icon-hint');
  await expect(hint).toBeVisible();
  await expect(hint).toHaveText('Receiver health');
  await page.mouse.move(0, 0);
  await expect(hint).toBeHidden();

  await page.getByRole('button', { name: 'Cancel', exact: true }).first().click();
  await action.evaluate((element) => element.focus());
  await expect(action).toBeFocused();
  await expect(hint).toBeHidden();

  await page.getByRole('button', { name: 'Remove', exact: true }).first().click();
  await page.keyboard.press('Tab');
  await expect(action).toBeFocused();
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

test('scan list catalog uses semantic cards with useful search and availability filters', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=admin-scan-lists');
  const catalog = page.locator('.visual-admin-scan-lists-example .scan-list-catalog');
  const cards = catalog.locator('article.admin-scan-list-card');
  await expect(catalog.locator('table')).toHaveCount(0);
  await expect(catalog.getByRole('button', { name: 'Choose table columns' })).toHaveCount(0);
  await expect(cards).toHaveCount(3);
  await expect(cards.locator('h2')).toHaveText(['County Public Safety', 'City Services', 'Training']);
  await expect(cards.first().getByRole('link', {
    name: 'Manage 124 assigned aliases for County Public Safety'
  })).toHaveText('124');
  expect(await cards.evaluateAll((elements) => elements.every((element) => {
    const labelledBy = element.getAttribute('aria-labelledby');
    return element.tagName === 'ARTICLE' && labelledBy && element.querySelector(`#${labelledBy}`);
  }))).toBe(true);
  await expect(catalog.getByText('Default', { exact: true })).toHaveCount(1);
  await expect(catalog.getByText('Available to listeners', { exact: true })).toHaveCount(2);
  await expect(catalog.getByText('Hidden from listeners', { exact: true })).toHaveCount(1);
  await expect(page.locator('.scan-list-overview .ui-summary-card > span')).toHaveText([
    'Scan lists', 'Alias assignments', 'Alias List default routes'
  ]);

  const search = catalog.getByLabel('Search scan lists');
  await search.fill('roads');
  await expect(catalog.locator('article.admin-scan-list-card:visible')).toHaveCount(1);
  await expect(catalog.locator('article.admin-scan-list-card:visible h2')).toHaveText('City Services');
  await expect(catalog.locator('.admin-scan-list-count')).toHaveText('1 of 3 scan lists');

  await search.fill('');
  await catalog.getByRole('button', { name: 'Hidden', exact: true }).click();
  await expect(catalog.getByRole('button', { name: 'Hidden', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(catalog.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await expect(catalog.locator('article.admin-scan-list-card:visible h2')).toHaveText('Training');
  await search.fill('county');
  await expect(catalog.locator('.admin-scan-list-filter-empty')).toBeVisible();
  await expect(catalog.locator('.admin-scan-list-count')).toHaveText('0 of 3 scan lists');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  const mobileCatalog = page.locator('.visual-admin-scan-lists-example .scan-list-catalog');
  const mobileCards = mobileCatalog.locator('.admin-scan-list-card');
  const [firstCard, secondCard] = await Promise.all([
    mobileCards.nth(0).boundingBox(), mobileCards.nth(1).boundingBox()
  ]);
  expect(secondCard.y).toBeGreaterThan(firstCard.y + firstCard.height);
  const firstActions = mobileCards.first().locator('.scan-list-card-actions');
  const [manage, edit] = await Promise.all([
    firstActions.getByRole('link', { name: 'Manage Members' }).boundingBox(),
    firstActions.getByRole('button', { name: 'Edit County Public Safety details' }).boundingBox()
  ]);
  expect(edit.y).toBeGreaterThan(manage.y);
  expect(await page.locator('body').evaluate((body) => body.scrollWidth <= body.clientWidth)).toBe(true);
});

test('table columns action is compact and stays in the owning title bar', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=radio-directory-coverage');
  const coverage = page.locator('.visual-radio-directory-coverage-example .alias-coverage-table-section');
  await expect(coverage.locator('.alias-coverage-table-header .table-layout-trigger')).toHaveCount(1);
  expect(await coverage.locator('.ui-table-wrap').evaluate((element) =>
    element.previousElementSibling?.classList.contains('alias-coverage-table-toolbar'))).toBe(true);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  const mobileActions = page.locator(
    '.visual-radio-directory-coverage-example .alias-coverage-table-header .ui-section-actions');
  expect(await mobileActions.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  const mobileHeading = page.locator(
    '.visual-radio-directory-coverage-example .alias-coverage-table-heading');
  const [headingBox, triggerBox] = await Promise.all([mobileHeading.boundingBox(),
    mobileActions.getByRole('button', { name: 'Choose table columns' }).boundingBox()]);
  expect(Math.abs((headingBox.y + headingBox.height / 2) -
    (triggerBox.y + triggerBox.height / 2))).toBeLessThan(4);
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
  const header = page.locator('.visual-app-chrome-example .app-header');
  await expect(header.getByText('RadioReference', { exact: true })).toBeAttached();
  await expect(header.getByText('Streaming', { exact: true })).toBeAttached();
  await expect(header.getByText('Hardware', { exact: true })).toBeAttached();
  await expect(header.getByText('Administration', { exact: true })).toBeAttached();
  await expect(header.getByText('About', { exact: true })).toBeAttached();
  await expect(header.getByRole('button', { name: 'Replay last call' })).toBeAttached();
  await expect(header.getByRole('button', { name: 'Clear queued calls' })).toBeAttached();

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

test('channel protocol groups expose an accessible full-width disclosure', async ({ page }) => {
  await page.setViewportSize({ width: 960, height: 720 });
  await page.goto('/design-system.html?theme=light&view=channels');
  const disclosure = page.locator('.table-row-group-disclosure[data-row-group="gallery-group-1"]');
  await expect(disclosure).toHaveAccessibleName('Collapse NBFM');
  await expect(disclosure).toHaveAttribute('aria-expanded', 'true');
  await disclosure.click();
  await expect(disclosure).toHaveAccessibleName('Expand NBFM');
  await expect(disclosure).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('tr[data-row-group="gallery-group-1"].ui-table-row-group-item')).toBeHidden();
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

test('live-picker-collapsed-dark-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/design-system.html?theme=dark&view=live-notice');
  const split = page.locator('.visual-live-example .live-split');
  const picker = split.locator('.live-channel-picker');
  const selectedHeader = split.locator('.live-selected-view-header');
  const collapse = selectedHeader.locator('.live-picker-collapse');
  await expect(collapse).toBeVisible();
  await collapse.click();
  await expect(split).toHaveClass(/picker-collapsed/);
  await expect(picker).toBeHidden();
  const [splitBox, workspaceBox] = await Promise.all([
    split.boundingBox(), split.locator('.live-right-workspace').boundingBox()
  ]);
  expect(Math.abs(workspaceBox.x - splitBox.x)).toBeLessThanOrEqual(1);
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page.locator('body')).toHaveScreenshot('live-picker-collapsed-dark-desktop.png');
});

test('Live desktop workspace persists its resizable details ratio and keeps one compact toolbar',
  async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/design-system.html?theme=light&view=live-notice');
    const workspace = page.locator('.live-right-workspace');
    const divider = workspace.getByRole('separator', {
      name: 'Resize live channels and details panels'
    });
    await expect(divider).toBeVisible();
    await expect(divider.locator('.live-workspace-resizer-grip > span')).toHaveCount(3);
    await expect(divider).toHaveAttribute('aria-valuenow', '25');
    const dividerBox = await divider.boundingBox();
    await page.mouse.move(dividerBox.x + dividerBox.width / 2, dividerBox.y + dividerBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(dividerBox.x + dividerBox.width / 2, dividerBox.y - 48);
    await page.mouse.up();
    const draggedPercent = Number(await divider.getAttribute('aria-valuenow'));
    expect(draggedPercent).toBeGreaterThan(25);
    expect(await page.evaluate(() => localStorage.getItem('details_panel_percent')))
      .toBe(String(draggedPercent));
    await divider.focus();
    await divider.press('ArrowUp');
    const keyboardPercent = Math.min(65, draggedPercent + 2);
    await expect(divider).toHaveAttribute('aria-valuenow', String(keyboardPercent));
    expect(await page.evaluate(() => localStorage.getItem('details_panel_percent')))
      .toBe(String(keyboardPercent));
    await page.reload();
    const restoredDivider = page.getByRole('separator', {
      name: 'Resize live channels and details panels'
    });
    await expect(restoredDivider).toHaveAttribute('aria-valuenow', String(keyboardPercent));

    const details = page.locator('.live-details');
    const controls = details.locator('.live-details-controls');
    await expect(details.locator('.live-details-summary, .live-event-selection')).toHaveCount(0);
    await expect(controls.getByRole('button', { name: 'Choose table columns' })).toBeVisible();
    await expect(controls.getByRole('button', { name: 'Filter events' })).toBeVisible();
    await expect(controls.locator('.live-detail-filter-state')).toHaveText('All Events');
    await expect(controls.getByRole('button', { name: 'Pause live details' })).toBeVisible();
    const detailsCollapse = controls.getByRole('button', { name: 'Collapse live details' });
    await detailsCollapse.click();
    await expect(restoredDivider).toBeHidden();
    await expect(details.locator('.live-details-body')).toBeHidden();
    await controls.getByRole('button', { name: 'Expand live details' }).click();
    await expect(restoredDivider).toBeVisible();
    await expect(restoredDivider).toHaveAttribute('aria-valuenow', String(keyboardPercent));
  });

test('live-notice-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=live-notice');
  await expect(page.locator('body')).toHaveScreenshot('live-notice-light-mobile.png');
});

test('mobile Live activity uses compact cards and a collapsible details tray', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=live-notice');
  const table = page.locator('.channels-live-table');
  const rows = table.locator('tbody > tr');
  await expect(rows).toHaveCount(3);
  await expect(rows.first()).toHaveAttribute('aria-selected', 'true');
  await expect(rows.first().locator('[data-column="source-alias"]')).toHaveCount(1);
  const horizontalOverflow = await table.locator('xpath=..').evaluate((element) =>
    element.scrollWidth - element.clientWidth);
  expect(horizontalOverflow).toBeLessThanOrEqual(1);

  const details = page.locator('.live-details');
  await expect(page.getByRole('separator', {
    name: 'Resize live channels and details panels'
  })).toBeHidden();
  await expect(details).toHaveClass(/collapsed/);
  await expect(details.locator('.live-details-body')).toBeHidden();
  await details.locator('.live-details-collapse').click();
  await expect(details).not.toHaveClass(/collapsed/);
  await expect(details.locator('.live-details-body')).toBeVisible();

  const sparse = rows.nth(1);
  await sparse.locator('[data-column="source-alias"], [data-column="target-alias"]')
    .evaluateAll((cells) => cells.forEach((cell) => { cell.textContent = ''; }));
  expect(await sparse.locator('[data-column="source"]').evaluate((cell) =>
    getComputedStyle(cell).gridColumnStart)).toBe('5');
  expect(await sparse.locator('[data-column="target"]').evaluate((cell) =>
    getComputedStyle(cell).gridColumnStart)).toBe('11');

  await table.locator('tbody').evaluate((body) => {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.className = 'empty';
    cell.colSpan = 11;
    cell.textContent = 'No channels observed';
    row.append(cell);
    body.replaceChildren(row);
  });
  const emptyRow = table.locator('tbody > tr');
  const emptyCell = emptyRow.locator('.empty');
  expect(await emptyCell.evaluate((cell) => getComputedStyle(cell).gridColumnEnd)).toBe('-1');
  expect(await emptyRow.evaluate((row) => getComputedStyle(row).cursor)).toBe('default');
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
