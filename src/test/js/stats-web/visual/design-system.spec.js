const { expect, test } = require('@playwright/test');
const { expectMetricGridSpacing, expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectBoxedFacts, expectFlatFacts } = require('./fixtures/fact-geometry.cjs');

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

for (const [, theme, viewport] of galleryCases) {
  test(`dual range ${theme} ${viewport.width} shows normal disabled and focus states`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
    const ranges = page.locator('.visual-duration-range');
    const minimum = ranges.first().getByRole('slider', { name: 'Call length: Minimum', exact: true });
    const maximum = ranges.first().getByRole('slider', { name: 'Call length: Maximum', exact: true });
    await expect(maximum).toHaveAttribute('aria-valuetext', 'Any length');
    await expect(ranges.last().getByRole('slider').first()).toBeDisabled();
    await expect(ranges.last().getByRole('slider').last()).toBeDisabled();
    await minimum.focus();
    await minimum.press('ArrowRight');
    await expect(minimum).toHaveValue('0.5');
    await expect(minimum).toHaveAttribute('aria-valuetext', '0.5 sec');
    // A focused handle must stay transparent above the other handle and rail,
    // including when the range sits inside the shared administration form.
    for (const handle of [minimum, maximum]) {
      await expect(handle).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
      await expect(handle).toHaveCSS('border-width', '0px');
      await expect(handle).toHaveCSS('padding', '0px');
      await expect(handle).toHaveCSS('box-shadow', 'none');
    }
    await expect(ranges.first()).toHaveScreenshot(`dual-range-${theme}-${viewport.width}.png`);
  });
}

test('equal dual range handles remain ordered and each can move by keyboard or pointer', async ({ page }) => {
  await page.goto('/design-system.html?theme=light&view=gallery');
  const range = page.locator('.visual-duration-range').first();
  const minimum = range.getByRole('slider', { name: 'Call length: Minimum', exact: true });
  const maximum = range.getByRole('slider', { name: 'Call length: Maximum', exact: true });
  const equalHandles = async () => {
    await minimum.evaluate((input) => { input.value = '30'; input.dispatchEvent(new Event('input')); });
    await maximum.evaluate((input) => { input.value = '30'; input.dispatchEvent(new Event('input')); });
  };
  await equalHandles();
  await minimum.focus();
  await minimum.press('ArrowRight');
  await expect(minimum).toHaveValue('30');
  await minimum.press('ArrowLeft');
  await expect(minimum).toHaveValue('29.5');
  await maximum.focus();
  await maximum.press('ArrowRight');
  await expect(maximum).toHaveValue('30.5');
  await equalHandles();
  await minimum.scrollIntoViewIfNeeded();
  const rail = await range.locator('.ui-dual-range-rail').boundingBox();
  const middle = rail.x + rail.width / 4;
  await page.mouse.click(middle - 4, rail.y + rail.height / 2);
  expect(Number(await minimum.inputValue())).toBeLessThan(30);
  await expect(maximum).toHaveValue('30');
  await equalHandles();
  await page.mouse.click(middle + 4, rail.y + rail.height / 2);
  await expect(minimum).toHaveValue('30');
  expect(Number(await maximum.inputValue())).toBeGreaterThan(30);
});

for(const [name, theme, viewport] of [
  ['alias-list-create-light-desktop', 'light', { width: 1280, height: 900 }],
  ['alias-list-create-dark-mobile', 'dark', { width: 390, height: 844 }]
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=alias-list-create-modal`);
    await expect(page.getByRole('dialog', { name: 'Create Alias List' })).toBeVisible();
    await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}

test('interface guidance demonstrates the current composition rules', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=gallery');
  await expect(page.locator('.visual-guidance-grid article')).toHaveCount(4);
  const actionsGuidance = page.locator('.visual-section').filter({
    has: page.locator('h2:text-is("Actions")')
  });
  await expect(actionsGuidance.locator('.visual-section-copy')).toContainText(
    'Prefer compact icon-only actions when the symbol is familiar and space matters');
  await expect(actionsGuidance.locator('.visual-section-copy')).toContainText(
    'Keep visible text for ambiguous, primary, or high-consequence actions');
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
  await expect(page.locator('.ui-alias-list-create-trigger')).toHaveAttribute('aria-haspopup', 'dialog');
  await expect(page.getByLabel('Alias List', { exact: true })).toHaveValue('Default P25');
  const unlabeledIconControls = await page.locator([
    '.ui-icon-button', '.icon-button', '.ui-header-indicator', '.playback-command',
    '.playback-control-menu > summary', '.channels-tab-close'
  ].join(', ')).evaluateAll((controls) => controls.filter((control) =>
    !control.getAttribute('aria-label') && !control.getAttribute('aria-labelledby'))
    .map((control) => control.outerHTML));
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

for (const [, theme, viewport] of galleryCases) {
  test(`stat groups preserve section insets and card gaps in ${theme} at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=entity-details`);
    await expectMetricGridSpacing(page.locator('.visual-entity-details-example .ui-section > .ui-metric-grid'));
    await expectBoxedFacts(page.locator('.visual-entity-details-example .ui-section > dl.ui-facts'),
      page.locator('.visual-entity-details-example .ui-metric:not(.ui-metric-compact)').first());
    const systemInfo = page.locator('.visual-system-info-card');
    await expect(systemInfo.locator('dt')).toHaveText(['Radio System', 'Alias Lists', 'First Seen', 'Last Seen']);
    const aliasList = systemInfo.getByRole('link', {
      name: 'County P25 and Regional Emergency Services Shared Alias List', exact: true
    });
    await expect(aliasList).toHaveAttribute('href', '?view=aliases&list=7');
    await aliasList.focus();
    await expect(aliasList).toBeFocused();
    await expect(systemInfo).toHaveScreenshot(`system-info-${theme}-${viewport.width}.png`);
    const affiliation = page.locator('.visual-entity-details-example .ui-metric a');
    await expect(affiliation).toHaveText('9');
    await expect(affiliation).toHaveAttribute('href', '#');
    await affiliation.focus();
    await expect(affiliation).toBeFocused();
    await expectNoHorizontalOverflow(page);
  });

  test(`record and settings fact rows retain their flat composition in ${theme} at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=admin-navigation`);
    await expectFlatFacts(page.locator('.visual-admin-navigation-example :is(.ui-record-facts, .ui-admin-facts) > .ui-fact'));
    await expectNoHorizontalOverflow(page);
  });

  test(`scan list counts share metric cards in ${theme} at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=admin-scan-lists`);
    const summary = page.locator('.scan-list-detail-facts');
    await expect(summary.locator(':scope > .ui-metric')).toHaveCount(2);
    await expect(summary.locator('.ui-fact')).toHaveCount(0);
    await expect(summary.locator('.ui-metric-label')).toHaveText([
      'Assigned aliases', 'Alias Lists routing unmatched calls'
    ]);
    await expect(summary.locator('strong')).toHaveText(['124', '2']);
    await expectMetricGridSpacing(summary, { inset: 0, sectionInset: false });
    await page.getByRole('button', { name: /City Services.*38 aliases/ }).click();
    await expect(summary.locator('strong')).toHaveText(['38', '0']);
    await expect(page.getByRole('link', { name: 'Manage 38 aliases for City Services' })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });

  test(`embedded stat groups use the existing body inset in ${theme} at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=health`);
    const resources = page.locator('.receiver-health-resource-bars');
    await expect(resources).toBeVisible();
    await expectMetricGridSpacing(resources, { inset: 0, sectionInset: false });
    const spacing = await resources.evaluate((grid) => {
      const body = grid.closest('.ui-settings-panel-body');
      const bodyBounds = body.getBoundingClientRect();
      const cardBounds = grid.firstElementChild.getBoundingClientRect();
      const styles = getComputedStyle(body);
      return {
        expectedLeft: parseFloat(styles.paddingLeft),
        expectedTop: parseFloat(styles.paddingTop),
        left: cardBounds.left - bodyBounds.left,
        top: cardBounds.top - bodyBounds.top
      };
    });
    expect(spacing.expectedLeft).toBeGreaterThan(0);
    expect(spacing.expectedTop).toBeGreaterThan(0);
    expect(spacing.left).toBeCloseTo(spacing.expectedLeft, 0);
    expect(spacing.top).toBeCloseTo(spacing.expectedTop, 0);
    await expectNoHorizontalOverflow(page);
  });

  test(`numeric summaries share metric cards in ${theme} at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    for (const [view, selector, count] of [
      ['gallery', '.visual-summary-grid', 3],
      ['channels', '.visual-channels-example .channel-summary-grid', 3],
      ['radio-directory-coverage', '.visual-radio-directory-coverage-example .alias-coverage-summary', 4]
    ]) {
      await page.goto(`/design-system.html?theme=${theme}&view=${view}`);
      const summary = page.locator(selector);
      await expect(summary.locator(':scope > .ui-metric')).toHaveCount(count);
      await expect(summary.locator('.ui-summary-card')).toHaveCount(0);
      await expect(summary.locator('.ui-metric-label .ui-metric-icon svg')).toHaveCount(count);
      await expectMetricGridSpacing(summary, { inset: 0, sectionInset: false });
      if (view === 'gallery') {
        const quality = page.locator('.design-system-gallery .scanner-call-quality-values');
        await expect(quality.locator('.ui-metric-compact')).toHaveCount(6);
        await expectMetricGridSpacing(quality, { inset: 0, sectionInset: false });
      }
      await expectNoHorizontalOverflow(page);
    }
  });
}

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
  await action.scrollIntoViewIfNeeded();
  await page.mouse.move(0, 0);
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
  await page.goto('/design-system.html?theme=light&view=scanner');
  await expect(page.locator('.visual-scanner-example .playback-icon-command').first()).toHaveCSS('width', '40px');
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

test('scan list catalog keeps one selected detail in sync with search and availability filters', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=admin-scan-lists');
  const catalog = page.locator('.visual-admin-scan-lists-example .scan-list-catalog');
  const selectors = catalog.locator('button.scan-list-selector');
  const detail = catalog.locator('.scan-list-detail');
  await expect(catalog.locator('table')).toHaveCount(0);
  await expect(catalog.getByRole('button', { name: 'Choose table columns' })).toHaveCount(0);
  await expect(selectors).toHaveCount(3);
  await expect(selectors.first()).toHaveAttribute('data-scan-list-id', /\S+/);
  await expect(selectors.first()).toHaveAttribute('aria-pressed', 'true');
  await expect(selectors.nth(1)).toHaveAttribute('aria-pressed', 'false');
  await expect(detail.getByRole('heading', { level: 2 })).toHaveText('County Public Safety');
  await expect(detail.getByText('Default', { exact: true })).toBeVisible();
  await expect(detail.getByText('Available to listeners', { exact: true })).toBeVisible();
  await expect(detail.locator('.admin-scan-list-members')).toHaveText('Manage aliases');
  await expect(detail.locator('.admin-scan-list-members')).toHaveAttribute('aria-label',
    'Manage 124 aliases for County Public Safety');
  await expect(detail.getByRole('link', { name: 'Default P25' })).toBeVisible();
  await selectors.nth(1).focus();
  await page.keyboard.press('Enter');
  await expect(selectors.nth(1)).toHaveAttribute('aria-pressed', 'true');
  await expect(selectors.first()).toHaveAttribute('aria-pressed', 'false');
  await expect(detail.getByRole('heading', { level: 2 })).toHaveText('City Services');

  const search = catalog.getByLabel('Search scan lists');
  await search.fill('roads');
  await expect(catalog.locator('button.scan-list-selector:visible')).toHaveCount(1);
  await expect(detail.getByRole('heading', { level: 2 })).toHaveText('City Services');
  await expect(catalog.locator('.admin-scan-list-count')).toHaveText('1 of 3 scan lists');

  await search.fill('');
  await catalog.getByRole('button', { name: 'Hidden', exact: true }).click();
  await expect(catalog.getByRole('button', { name: 'Hidden', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(catalog.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await expect(catalog.locator('button.scan-list-selector:visible')).toHaveCount(1);
  await expect(detail.getByRole('heading', { level: 2 })).toHaveText('Training');
  await expect(detail.getByText('Hidden from listeners', { exact: true })).toBeVisible();
  await search.fill('county');
  await expect(catalog.locator('.admin-scan-list-filter-empty')).toBeVisible();
  await expect(catalog.locator('.admin-scan-list-count')).toHaveText('0 of 3 scan lists');
  await expect(catalog.locator('.scan-list-detail-host')).toBeHidden();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  const mobileCatalog = page.locator('.visual-admin-scan-lists-example .scan-list-catalog');
  const mobileSelectors = mobileCatalog.locator('.scan-list-selector');
  const [firstSelector, secondSelector, mobileDetail, mobileList] = await Promise.all([
    mobileSelectors.nth(0).boundingBox(), mobileSelectors.nth(1).boundingBox(),
    mobileCatalog.locator('.scan-list-detail').boundingBox(), mobileCatalog.locator('.scan-list-list').boundingBox()
  ]);
  expect(secondSelector.y).toBeGreaterThanOrEqual(firstSelector.y + firstSelector.height);
  expect(mobileDetail.y).toBeGreaterThanOrEqual(mobileList.y + mobileList.height);
  const listOverflow = await mobileCatalog.locator('.scan-list-list').evaluate((element) => {
    const style = getComputedStyle(element);
    return { overflowY: style.overflowY, maxHeight: Number.parseFloat(style.maxHeight) };
  });
  expect(listOverflow.overflowY).toBe('auto');
  expect(listOverflow.maxHeight).toBeGreaterThan(0);
  expect(mobileList.height).toBeLessThanOrEqual(listOverflow.maxHeight + 1);
  expect(await page.locator('body').evaluate((body) => body.scrollWidth <= body.clientWidth)).toBe(true);
});

test('scan list keyboard navigation uses one list tab stop and reaches selected actions', async ({ page }) => {
  await page.goto('/design-system.html?theme=light&view=admin-scan-lists');
  const catalog = page.locator('.visual-admin-scan-lists-example .scan-list-catalog');
  const selectors = catalog.locator('.scan-list-selector');
  const manage = catalog.locator('.admin-scan-list-members');
  await expect(selectors.first()).toHaveAttribute('tabindex', '0');
  await expect(selectors.nth(1)).toHaveAttribute('tabindex', '-1');
  await selectors.first().focus();
  await page.keyboard.press('ArrowDown');
  await expect(selectors.nth(1)).toBeFocused();
  await expect(selectors.nth(1)).toHaveAttribute('aria-pressed', 'true');
  await expect(selectors.nth(1)).toHaveAttribute('tabindex', '0');
  await expect(selectors.first()).toHaveAttribute('tabindex', '-1');
  await expect(catalog.locator('.scan-list-detail h2')).toHaveText('City Services');
  await page.keyboard.press('End');
  await expect(selectors.nth(2)).toBeFocused();
  await expect(catalog.locator('.scan-list-detail h2')).toHaveText('Training');
  await page.keyboard.press('Home');
  await expect(selectors.first()).toBeFocused();
  await expect(catalog.locator('.scan-list-detail h2')).toHaveText('County Public Safety');
  await page.keyboard.press('ArrowUp');
  await expect(selectors.nth(2)).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(selectors.first()).toBeFocused();
  for (let index = 0; index < 4; index += 1) {
    await page.keyboard.press('Tab');
    await expect(catalog.locator('.scan-list-selector:focus')).toHaveCount(0);
    if (await manage.evaluate((element) => element === document.activeElement)) break;
  }
  await expect(manage).toBeFocused();
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

test('Scanner transport controls share one compact surface language', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto('/design-system.html?theme=dark&view=scanner');
  const example = page.locator('.visual-scanner-example');
  const command = example.locator('.playback-command').first();
  const volume = example.locator('.playback-volume');
  const avoidGroup = example.locator('.playback-command-group');
  await expect(volume).toHaveCSS('height', '40px');
  await expect(volume).toHaveCSS('border-top-width', '1px');
  await expect(avoidGroup).toHaveCSS('border-top-width', '1px');
  const [commandBounds, volumeBounds] = await Promise.all([command.boundingBox(), volume.boundingBox()]);
  expect(Math.abs((commandBounds.y + commandBounds.height / 2) -
    (volumeBounds.y + volumeBounds.height / 2))).toBeLessThanOrEqual(2);
  await volume.evaluate((element) => {
    element.style.setProperty('--playback-volume-level', '0%');
  });
  await expect(volume.locator('.playback-volume-label')).toHaveCSS('background-clip', 'text');
  await expect(volume).toHaveScreenshot('scanner-volume-empty-dark-desktop.png');
  await volume.locator('input').focus();
  await expect(volume).toHaveCSS('outline-style', 'solid');
  await volume.evaluate((element) => {
    element.style.setProperty('--playback-volume-level', '75%');
  });

});

test('app chrome keeps desktop and mobile navigation reachable without a second audio player', async ({ page }) => {
  const navigation = page.locator('.visual-app-chrome-example .navigation-toggle');
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto('/design-system.html?theme=light&view=app-chrome');
  await expect(navigation).toBeHidden();
  const header = page.locator('.visual-app-chrome-example .app-header');
  await expect(header.locator('.playback-bar')).toHaveCount(0);
  const brand = header.getByRole('link', { name: 'VCE home' });
  await expect(brand.locator('img.brand-logo')).toHaveAttribute('src', '/assets/vce-wordmark.svg?v=3');
  await expect(header.getByText('RadioReference', { exact: true })).toBeAttached();
  await expect(header.getByText('Streaming', { exact: true })).toBeAttached();
  await expect(header.getByText('Hardware', { exact: true })).toBeAttached();
  await expect(header.getByText('Administration', { exact: true })).toBeAttached();
  await expect(header.getByText('About', { exact: true })).toBeAttached();
  await expect(header.getByRole('link', { name: 'Receiver status' })).toBeVisible();
  await expect(header.getByRole('link', { name: 'Report a problem' })).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(navigation).toBeVisible();
  const [navigationBox, brandBox] = await Promise.all([navigation.boundingBox(), brand.boundingBox()]);
  expect(brandBox.x - (navigationBox.x + navigationBox.width)).toBeGreaterThanOrEqual(9);
  await expect(header.locator('.playback-bar')).toHaveCount(0);
});

for (const [view, theme, viewport, name] of [
  ['access-landing', 'dark', { width: 1280, height: 900 }, 'access-landing-dark-desktop'],
  ['access-landing', 'light', { width: 390, height: 844 }, 'access-landing-light-mobile'],
  ['access-login-modal', 'dark', { width: 1280, height: 900 }, 'access-login-modal-dark-desktop'],
  ['access-login-modal', 'light', { width: 390, height: 844 }, 'access-login-modal-light-mobile'],
]) {
  test(`${name} gallery`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto(`/design-system.html?theme=${theme}&view=${view}`);
    const example = page.locator(view === 'access-landing' ?
      '.visual-access-landing-example' : '.visual-access-login-modal-example');
    await expect(example).toBeVisible();
    await expect(example).toHaveAttribute('data-scene-ready', 'true');
    await expect(example.locator('.access-scene-canvas')).toBeVisible();
    if (view === 'access-login-modal') {
      await expect.poll(() => example.locator('.access-scene-canvas').evaluate((canvas) => {
        const bounds = canvas.getBoundingClientRect();
        const scale = Math.min(window.devicePixelRatio || 1, 2);
        return bounds.width > 0 && bounds.height > 0 &&
          canvas.width === Math.round(bounds.width * scale) &&
          canvas.height === Math.round(bounds.height * scale);
      })).toBe(true);
      await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)));
    }
    await expect(example.locator('img[src="/assets/vce-wordmark.svg?v=3"]')).toBeVisible();
    await expect(example.getByLabel('Username')).toBeVisible();
    await expect(example.getByLabel('Password')).toBeVisible();
    await expect(example.getByRole('button', { name: 'Sign In', exact: true })).toBeVisible();
    await expect(page.locator('a:visible')).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth))
      .toBeLessThanOrEqual(viewport.width + 1);
    await expect(page).toHaveScreenshot(`${name}.png`, { fullPage: true });
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
  const example = page.locator('.visual-tuner-spectrum-example');
  await expect(example.locator(':scope > .page-header > .ui-button-primary')).toHaveCount(0);
  await expect(example.locator('.spectrum-browse-toolbar').getByRole('button',
    { name: 'Find Trunked Systems', exact: true })).toHaveCount(0);
  await expect(example.getByRole('checkbox', { name: 'Lock center', exact: true })).toHaveCount(0);
  await expect(example.getByText('Lock center', { exact: true })).toHaveCount(0);
  await expect(example.locator('.tuners-center-lock-field')).toHaveCount(0);
  await expect(example.locator('.spectrum-browse-message')).toHaveCount(0);
  await expect(example.locator('.tuner-spectrum-readouts .channel-diagnostic-readout small'))
    .toHaveText(['Visible span', 'Zoom', 'Peak', 'Best SNR']);
  for (const label of ['Stop channels to tune', 'Reset zoom', 'Pause',
    'Display options', 'More measurements']) {
    const control = example.locator(`[aria-label="${label}"]`).first();
    await expect(control).toHaveClass(/ui-icon-button/);
    await expect(control).toHaveText('');
  }
  const toolbarCenters = await example.locator('.spectrum-browse-toolbar').evaluate((toolbar) =>
    [...toolbar.children].map((child) => {
      const bounds = child.getBoundingClientRect();
      return bounds.top + bounds.height / 2;
    }));
  expect(Math.max(...toolbarCenters) - Math.min(...toolbarCenters)).toBeLessThanOrEqual(1);
  const frequencyBox = await example.locator('.tuners-center-value').boundingBox();
  expect(frequencyBox).not.toBeNull();
  const panelBox = await example.locator('.spectrum-browse-panel').boundingBox();
  const railBox = await example.locator('.spectrum-browse-control-rail').boundingBox();
  expect(panelBox).not.toBeNull();
  expect(railBox).not.toBeNull();
  expect(railBox.x).toBeGreaterThan(panelBox.x + panelBox.width);
  expect(Math.abs(railBox.y - panelBox.y)).toBeLessThanOrEqual(1);
  const waterfallBox = await example.locator('.tuner-spectrum-waterfall').boundingBox();
  const statusRailBox = await example.locator('.spectrum-browse-status-rail').boundingBox();
  const legendBox = await example.locator('.tuner-spectrum-display-controls').boundingBox();
  const measurementsBox = await example.locator('.tuner-spectrum-measurement-panel').boundingBox();
  expect(waterfallBox).not.toBeNull();
  expect(legendBox).not.toBeNull();
  expect(Math.abs(waterfallBox.y + waterfallBox.height - statusRailBox.y)).toBeLessThanOrEqual(1);
  expect(Math.abs(legendBox.y - measurementsBox.y)).toBeLessThanOrEqual(1);
  expect(Math.abs(legendBox.x + legendBox.width - measurementsBox.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(legendBox.y + legendBox.height -
    (measurementsBox.y + measurementsBox.height))).toBeLessThanOrEqual(1);
  await expect.poll(() => example.locator('.tuner-spectrum-display-controls')
    .evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  await expect(page.locator('body')).toHaveScreenshot('tuner-spectrum-dark-desktop.png', { fullPage: true });
});

test('tuner-spectrum-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=tuner-spectrum');
  const example = page.locator('.visual-tuner-spectrum-example');
  await expect(example.locator(':scope > .page-header > .ui-button-primary')).toHaveCount(0);
  await expect(example.getByRole('checkbox', { name: 'Lock center', exact: true })).toHaveCount(0);
  const tunerBox = await example.locator('.spectrum-browse-tuner').boundingBox();
  const frequencyBox = await example.locator('.tuners-center-frequency').boundingBox();
  const stateBox = await example.locator('.spectrum-browse-state').boundingBox();
  const actionsBox = await example.locator('.spectrum-browse-actions').boundingBox();
  expect(Math.abs((stateBox.y + stateBox.height / 2) -
    (tunerBox.y + tunerBox.height / 2))).toBeLessThanOrEqual(1);
  expect(frequencyBox.y).toBeGreaterThanOrEqual(tunerBox.y + tunerBox.height);
  expect(actionsBox.y).toBeGreaterThanOrEqual(frequencyBox.y + frequencyBox.height);
  const panelBox = await example.locator('.spectrum-browse-panel').boundingBox();
  const railBox = await example.locator('.spectrum-browse-control-rail').boundingBox();
  expect(panelBox).not.toBeNull();
  expect(railBox).not.toBeNull();
  expect(railBox.y).toBeGreaterThan(panelBox.y + panelBox.height);
  expect(Math.abs(railBox.x - panelBox.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(railBox.width - panelBox.width)).toBeLessThanOrEqual(1);
  const legendBox = await example.locator('.tuner-spectrum-display-controls').boundingBox();
  const measurementsBox = await example.locator('.tuner-spectrum-measurement-panel').boundingBox();
  expect(measurementsBox.y).toBeGreaterThanOrEqual(legendBox.y + legendBox.height);
  await expect(example.locator('.spectrum-browse-message')).toHaveCount(0);
  await expect(example.getByRole('button', { name: 'More spectrum actions', exact: true })).toBeVisible();
  await expect(example.getByRole('button', { name: 'Display options', exact: true })).toBeHidden();
  await expect(page.locator('body')).toHaveScreenshot('tuner-spectrum-light-mobile.png', { fullPage: true });
});

test('tuner-spectrum-toolbar-stays-one-row-at-desktop-narrow-width', async ({ page }) => {
  await page.setViewportSize({ width: 943, height: 750 });
  await page.goto('/design-system.html?theme=light&view=tuner-spectrum');
  const toolbar = page.locator('.visual-tuner-spectrum-example .spectrum-browse-toolbar');
  const boxes = await toolbar.evaluate((element) => [...element.children].map((child) => {
    const bounds = child.getBoundingClientRect();
    return { top: bounds.top, bottom: bounds.bottom, center: bounds.top + bounds.height / 2 };
  }));
  expect(Math.max(...boxes.map((box) => box.center)) -
    Math.min(...boxes.map((box) => box.center))).toBeLessThanOrEqual(1);
  expect(Math.max(...boxes.map((box) => box.bottom)) -
    Math.min(...boxes.map((box) => box.top))).toBeLessThanOrEqual(48);
  const legend = await page.locator('.visual-tuner-spectrum-example .tuner-spectrum-display-controls').boundingBox();
  const measurements = await page.locator(
    '.visual-tuner-spectrum-example .tuner-spectrum-measurement-panel').boundingBox();
  expect(measurements.y).toBeGreaterThanOrEqual(legend.y + legend.height);

  await page.setViewportSize({ width: 820, height: 750 });
  await page.goto('/design-system.html?theme=light&view=tuner-spectrum');
  const compact = page.locator('.visual-tuner-spectrum-example');
  const compactRows = await compact.locator('.spectrum-browse-toolbar').evaluate((toolbar) =>
    [...toolbar.children].map((child) => {
      const bounds = child.getBoundingClientRect();
      return bounds.top + bounds.height / 2;
    }));
  expect(Math.max(...compactRows) - Math.min(...compactRows)).toBeLessThanOrEqual(1);
  await expect(compact.locator('.tuners-center-value')).toBeVisible();
  await expect(compact.locator('.tuners-center-lock-field')).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
    document.documentElement.clientWidth)).toBe(true);

  await page.setViewportSize({ width: 360, height: 750 });
  await page.goto('/design-system.html?theme=light&view=tuner-spectrum');
  const phone = page.locator('.visual-tuner-spectrum-example');
  const phoneTuner = await phone.locator('.spectrum-browse-tuner').boundingBox();
  const phoneFrequency = await phone.locator('.tuners-center-frequency').boundingBox();
  const phoneState = await phone.locator('.spectrum-browse-state').boundingBox();
  const phoneActions = await phone.locator('.spectrum-browse-actions').boundingBox();
  expect(Math.abs((phoneState.y + phoneState.height / 2) -
    (phoneTuner.y + phoneTuner.height / 2))).toBeLessThanOrEqual(1);
  expect(phoneFrequency.y).toBeGreaterThanOrEqual(phoneTuner.y + phoneTuner.height);
  await expect(phone.locator('.tuners-center-lock-field')).toHaveCount(0);
  expect(phoneActions.y).toBeGreaterThanOrEqual(phoneFrequency.y + phoneFrequency.height);
  await expect(phone.locator('.spectrum-browse-state > .badge')).toBeVisible();
  await expect(phone.getByRole('button', { name: 'More spectrum actions', exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
    document.documentElement.clientWidth)).toBe(true);

  await page.setViewportSize({ width: 320, height: 750 });
  await page.goto('/design-system.html?theme=light&view=tuner-spectrum');
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
    document.documentElement.clientWidth)).toBe(true);
  await expect(page.getByRole('button', { name: 'More spectrum actions', exact: true })).toBeVisible();
});

for (const [name, theme, viewport] of [
  ['tuners-operator-light-desktop', 'light', { width: 1680, height: 1000 }],
  ['tuners-operator-dark-desktop', 'dark', { width: 1680, height: 1000 }],
  ['tuners-operator-light-mobile', 'light', { width: 390, height: 844 }],
  ['tuners-operator-dark-mobile', 'dark', { width: 390, height: 844 }]
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=tuners`);
    await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}

for(const [name, theme, viewport] of [
  ['tuner-more-measurements-light-desktop', 'light', { width: 1280, height: 900 }],
  ['tuner-more-measurements-dark-mobile', 'dark', { width: 390, height: 844 }],
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=tuner-spectrum`);
    await expect(page.locator('.visual-tuner-spectrum-example .tuner-spectrum-options'))
      .not.toHaveAttribute('open', '');
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

test('activity-filters-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/design-system.html?theme=light&view=activity-filters');
  const toolbar = page.locator('.visual-activity-filters-example .activity-filter-toolbar');
  await expect(toolbar.locator('.activity-filter-primary')).toBeVisible();
  await expect(toolbar.locator('.activity-filter-chips')).toBeVisible();
  const chipLabels = toolbar.locator('.activity-filter-chip-label');
  await expect(chipLabels).toHaveText(['Source:', 'Destination:']);
  expect(await chipLabels.evaluateAll((labels) => labels.every((label) =>
    label.scrollWidth <= label.clientWidth))).toBe(true);
  expect(await toolbar.locator('.activity-filter-chip-value').first().evaluate((value) =>
    value.scrollWidth > value.clientWidth)).toBe(true);
  await toolbar.locator('.activity-filter-picker-destination .activity-filter-picker-trigger').click();
  const picker = page.locator('#visual-activity-destination-popover');
  await expect(picker).toBeVisible();
  const geometry = await picker.boundingBox();
  expect(geometry).not.toBeNull();
  expect(geometry.x).toBeGreaterThanOrEqual(0);
  expect(geometry.x + geometry.width).toBeLessThanOrEqual(1440);
  await expect(page.locator('body')).toHaveScreenshot('activity-filters-light-desktop.png');
});

test('activity-filters-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=activity-filters');
  const toolbar = page.locator('.visual-activity-filters-example .activity-filter-toolbar');
  await expect(toolbar.locator('.activity-filter-picker-source')).toBeVisible();
  const tableWrap = page.locator('.visual-activity-filters-example .ui-table-wrap');
  const tableWidths = await tableWrap.evaluate((element) => ({
    client: element.clientWidth, scroll: element.scrollWidth
  }));
  expect(tableWidths.scroll).toBeGreaterThan(tableWidths.client);
  await toolbar.locator('.activity-filter-more-trigger').click();
  const panel = page.locator('#visual-activity-more-panel');
  await expect(panel).toBeVisible();
  const geometry = await panel.boundingBox();
  expect(geometry).not.toBeNull();
  expect(geometry.x).toBeGreaterThanOrEqual(0);
  expect(geometry.x + geometry.width).toBeLessThanOrEqual(390);
  expect(geometry.y).toBeGreaterThanOrEqual(0);
  expect(geometry.y + geometry.height).toBeLessThanOrEqual(844);
  await expect(panel.locator('.activity-filter-more-done')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await expect(page.locator('body')).toHaveScreenshot('activity-filters-dark-mobile.png');
});

test('scanner-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=scanner');
  const scanner = page.locator('.visual-scanner-example');
  await expect(scanner.locator('.scanner-audio-wave > i')).toHaveCount(64);
  expect(await scanner.locator('.scanner-console > *').evaluateAll((elements) =>
    elements.map((element) => element.className))).toEqual([
    'scanner-status-bar', 'scanner-player-host', 'scanner-console-main'
  ]);
  await expect(scanner.locator('.scanner-player-host > .playback-bar.scanner-expanded')).toHaveCount(1);
  await expect(scanner.locator('.scanner-controls, .scanner-utility-row')).toHaveCount(0);
  await expect(scanner.locator('.scanner-view-modes [data-mode]')).toHaveCount(4);
  await expect(scanner.locator('.scanner-view-modes [data-mode="normal"]')).toHaveAttribute('aria-pressed', 'true');
  await expect(scanner.locator('.scanner-scan-summary')).toHaveText('2 of 6 listening');
  await expect(scanner.locator('.scanner-scan-button[aria-pressed="true"]')).toHaveCount(2);
  await expect(scanner.locator('.scanner-coverage-action')).toHaveAttribute('aria-label', 'View scan-list coverage');
  const nowPlaying = await scanner.locator('.scanner-now-playing').boundingBox();
  const scanRail = await scanner.locator('.scanner-scan-rail').boundingBox();
  expect(nowPlaying).not.toBeNull();
  expect(scanRail).not.toBeNull();
  expect(scanRail.x).toBeGreaterThan(nowPlaying.x + nowPlaying.width - 1);
  await expect(page.locator('body')).toHaveScreenshot('scanner-light-desktop.png', { fullPage: true });
});

test('scanner-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=scanner');
  const scanner = page.locator('.visual-scanner-example');
  const nowPlaying = await scanner.locator('.scanner-now-playing').boundingBox();
  const scanRail = await scanner.locator('.scanner-scan-rail').boundingBox();
  expect(nowPlaying).not.toBeNull();
  expect(scanRail).not.toBeNull();
  expect(scanRail.y).toBeGreaterThanOrEqual(nowPlaying.y + nowPlaying.height - 1);
  await expect(scanner.locator('.scanner-scan-button')).toHaveCount(6);
  await expect(scanner.locator('.playback-command')).toHaveCount(8);
  const unnamedPlaybackControls = await scanner.locator('.playback-command').evaluateAll((buttons) =>
    buttons.filter((button) => !button.getAttribute('aria-label')).length);
  expect(unnamedPlaybackControls).toBe(0);
  await expect(page.locator('body')).toHaveScreenshot('scanner-dark-mobile.png', { fullPage: true });
});

for (const theme of ['light', 'dark']) {
  for (const width of [1001, 1180, 1280]) {
    test(`scanner quality metric labels fit beside the scan rail in ${theme} at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(`/design-system.html?theme=${theme}&view=scanner`);
      await page.evaluate(() => {
        const cards = document.querySelector('.design-system-gallery .scanner-call-quality-values').cloneNode(true);
        const engineer = document.createElement('div');
        engineer.className = 'scanner-engineer-grid';
        const quality = document.createElement('section');
        quality.className = 'scanner-call-quality';
        const heading = document.createElement('span');
        heading.className = 'scanner-call-quality-heading';
        heading.textContent = 'Call Quality';
        quality.append(heading, cards);
        engineer.append(quality);
        document.querySelector('.visual-scanner-example .scanner-display').append(engineer);
      });
      const scanner = page.locator('.visual-scanner-example');
      const quality = scanner.locator('.scanner-call-quality-values');
      await expect(quality.locator('.ui-metric-label')).toHaveText([
        'Decoded', 'Repeated', 'Concealed', 'Missing', 'FEC Errors', 'FEC Protected'
      ]);
      await expectMetricGridSpacing(quality, { inset: 0, sectionInset: false });
      const nowPlaying = await scanner.locator('.scanner-now-playing').boundingBox();
      const scanRail = await scanner.locator('.scanner-scan-rail').boundingBox();
      expect(scanRail.x).toBeGreaterThanOrEqual(nowPlaying.x + nowPlaying.width - 1);
      const measurements = await quality.locator('.ui-metric').evaluateAll((cards) => cards.map((card) => {
        const label = card.querySelector('.ui-metric-label');
        const bounds = card.getBoundingClientRect();
        const labelBounds = label.getBoundingClientRect();
        const text = document.createRange();
        text.selectNodeContents(label.lastChild);
        return {
          label: label.textContent.trim(),
          width: bounds.width,
          cardFits: card.scrollWidth <= card.clientWidth,
          labelFits: label.scrollWidth <= label.clientWidth && labelBounds.left >= bounds.left &&
            labelBounds.right <= bounds.right,
          textFits: [...text.getClientRects()].every((rect) => rect.left >= labelBounds.left - 1 &&
            rect.right <= labelBounds.right + 1 && rect.top >= labelBounds.top - 1 &&
            rect.bottom <= labelBounds.bottom + 1)
        };
      }));
      for (const measurement of measurements) {
        expect(measurement.width, measurement.label).toBeGreaterThanOrEqual(170);
        expect(measurement.cardFits, measurement.label).toBe(true);
        expect(measurement.labelFits, measurement.label).toBe(true);
        expect(measurement.textFits, measurement.label).toBe(true);
      }
      await expectNoHorizontalOverflow(page);
    });
  }
}

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

test('alias search keeps Filters behind an accessible disclosure', async ({ page }) => {
  for(const view of ['aliases', 'scan-list-members']) {
    await page.goto(`/design-system.html?theme=light&view=${view}`);
    const form = page.locator('.visual-feature-example:visible .alias-editor-filter-toolbar');
    await expect(form.getByRole('searchbox', { name: 'Search' })).toBeVisible();
    await expect(form.getByRole('button', { name: 'Search', exact: true })).toBeVisible();
    const toggle = form.getByRole('button', { name: 'Filters', exact: true });
    await expect(form).toHaveAttribute('data-shared-filters-ready', 'true');
    const panelId = await toggle.getAttribute('aria-controls');
    const panel = form.locator(`#${panelId}`);
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(panel).toBeHidden();
    const tableSection = page.locator('.visual-feature-example:visible .alias-editor-table-section');
    await expect(tableSection.locator('.alias-filter-result-count'))
      .toHaveText(view === 'aliases' ? '1,248 aliases' : '386 aliases');
    await expect(tableSection.locator('.alias-filter-result-count + .alias-editor-table-host'))
      .toHaveCount(1);
    await toggle.focus();
    await toggle.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(panel).toBeVisible();
    await expect(panel.locator('fieldset legend')).toHaveText([
      'Identity and matching', 'Call handling', 'Observed activity'
    ]);
    await toggle.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(panel).toBeHidden();
  }
});

for(const [name, theme, viewport] of [
  ['aliases-advanced-light-desktop', 'light', { width: 1440, height: 960 }],
  ['aliases-advanced-dark-mobile', 'dark', { width: 390, height: 844 }]
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=aliases`);
    const form = page.locator('.visual-aliases-example .alias-editor-filter-toolbar');
    await form.getByRole('button', { name: 'Filters', exact: true }).click();
    const filters = viewport.width <= 700 ? page.getByRole('dialog', { name: 'Filters', exact: true }) : form;
    await expect(filters.locator('.alias-filter-advanced')).toBeVisible();
    if (viewport.width <= 700) await expect(filters).toHaveScreenshot(`${name}.png`);
    else await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}

test('Alias and Scan List member phone Filters use the shared sheet and preserve draft controls on dismissal', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const view of ['aliases', 'scan-list-members']) {
    await page.goto(`/design-system.html?theme=dark&view=${view}`);
    const form = page.locator('.visual-feature-example:visible .alias-editor-filter-toolbar');
    await expect(form).toHaveAttribute('data-shared-filters-ready', 'true');
    const toggle = form.getByRole('button', { name: 'Filters', exact: true });
    await toggle.click();
    const sheet = page.getByRole('dialog', { name: 'Filters', exact: true });
    await expect(sheet).toHaveClass(/ui-filter-modal/);
    await expect(sheet.getByRole('button', { name: 'Apply filters', exact: true })).toHaveCount(1);
    await sheet.getByRole('textbox', { name: 'Group', exact: true }).fill('Fire');
    await sheet.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(sheet).toHaveCount(0);
    await expect(form.getByRole('button', { name: 'Filters (1)', exact: true })).toBeFocused();
    await expect(form.locator('.alias-filter-advanced')).toBeHidden();
    await expect(form.getByRole('textbox', { name: 'Group', exact: true, includeHidden: true })).toHaveValue('Fire');
    await form.getByRole('button', { name: 'Filters (1)', exact: true }).click();
    await sheet.getByRole('button', { name: 'Apply filters', exact: true }).click();
    await expect(sheet).toHaveCount(0);
    await expect(form.getByRole('button', { name: 'Filters (1)', exact: true })).toBeFocused();
    await form.getByRole('button', { name: 'Clear filters', exact: true }).click();
    await expect(form.getByRole('button', { name: 'Filters', exact: true })).toBeVisible();
    await expect(form.getByRole('textbox', { name: 'Group', exact: true, includeHidden: true })).toHaveValue('');
    await expect(page.locator('body')).not.toHaveClass(/modal-open/);
  }
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
  const rows = page.locator('.channel-catalog-table tbody tr').filter({
    has: page.locator('.channel-name-actions')
  });
  await expect(rows).toHaveCount(2);
  for (const row of await rows.all()) {
    const name = row.locator('td[data-label="Name"]');
    const summary = row.locator('.channel-name-summary');
    const navigation = row.locator('.channel-row-links');
    await expect(name).toHaveCSS('display', 'table-cell');
    await expect(row.locator('.channel-name-actions')).toHaveCSS('display', 'grid');
    const [nameBox, summaryBox, navigationBox] = await Promise.all([
      name.boundingBox(), summary.boundingBox(), navigation.boundingBox()
    ]);
    expect(navigationBox.y).toBeGreaterThanOrEqual(summaryBox.y + summaryBox.height);
    expect(navigationBox.x).toBeGreaterThanOrEqual(nameBox.x);
    expect(navigationBox.x + navigationBox.width).toBeLessThanOrEqual(nameBox.x + nameBox.width);
    expect(navigationBox.y + navigationBox.height).toBeLessThanOrEqual(nameBox.y + nameBox.height);
  }
  await expect(page.locator('body')).toHaveScreenshot('channels-dark-desktop.png', { fullPage: true });
});

async function expectChannelMobileActions(page) {
  const rows = page.locator('.channel-catalog-table tbody tr').filter({
    has: page.locator('.channel-name-actions')
  });
  await expect(rows).toHaveCount(2);
  for (const row of await rows.all()) {
    const alias = row.locator('td[data-label="Alias List"]');
    const edit = row.getByRole('button', { name: 'Edit', exact: true });
    const details = row.getByRole('link', { name: 'Details', exact: true });
    const live = row.getByRole('link', { name: 'Live', exact: true });
    const startup = row.locator('.channel-startup-order');
    await expect(edit).toBeVisible();
    await expect(details).toBeVisible();
    await expect(live).toBeVisible();
    await expect(startup).toHaveCSS('text-align', 'left');
    expect(await startup.evaluate((cell) => getComputedStyle(cell, '::before').textAlign)).toBe('left');
    const [aliasBox, editBox, detailsBox, liveBox, rowBox] = await Promise.all([
      alias.boundingBox(), edit.boundingBox(), details.boundingBox(), live.boundingBox(), row.boundingBox()
    ]);
    for (const actionBox of [editBox, detailsBox, liveBox]) {
      expect(actionBox.y).toBeGreaterThanOrEqual(aliasBox.y + aliasBox.height);
      expect(actionBox.height).toBeGreaterThanOrEqual(40);
      expect(actionBox.height).toBe(40);
      expect(actionBox.x).toBeGreaterThanOrEqual(rowBox.x);
      expect(actionBox.x + actionBox.width).toBeLessThanOrEqual(rowBox.x + rowBox.width);
    }
    expect(Math.abs(detailsBox.width - liveBox.width)).toBeLessThanOrEqual(1);
    const actionGap = liveBox.x - detailsBox.x - detailsBox.width;
    expect(Math.abs(editBox.width - detailsBox.width - actionGap - liveBox.width)).toBeLessThanOrEqual(1);
    expect(Math.abs(editBox.x - detailsBox.x)).toBeLessThanOrEqual(1);
    expect(detailsBox.y).toBeGreaterThan(editBox.y + editBox.height);
    expect(detailsBox.y).toBe(liveBox.y);
    expect(liveBox.x).toBeGreaterThan(detailsBox.x + detailsBox.width);
  }
  const startupControls = page.locator('.channel-order-controls');
  await expect(startupControls.getByRole('button', { name: 'Start earlier', exact: true })).toBeVisible();
  await expect(startupControls.locator('.channel-order-number')).toHaveText('1');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
    page.viewportSize().width);
}

test('channels-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=channels');
  await expectChannelMobileActions(page);
  await expect(page.locator('body')).toHaveScreenshot('channels-light-mobile.png', { fullPage: true });
});

test('channels actions remain usable on a narrow dark mobile screen', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=channels');
  await expectChannelMobileActions(page);
});

test('channel protocol groups expose an accessible full-width disclosure', async ({ page }) => {
  await page.setViewportSize({ width: 960, height: 720 });
  await page.goto('/design-system.html?theme=light&view=channels');
  const disclosure = page.locator('.table-row-group-disclosure[data-row-group="gallery-group-1"]');
  await expect(disclosure).toHaveAccessibleName('Collapse P25 Trunked');
  await expect(disclosure).toHaveAttribute('aria-expanded', 'true');
  await disclosure.click();
  await expect(disclosure).toHaveAccessibleName('Expand P25 Trunked');
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

test('radio directory exposes Live only for running channels', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=radio-directory');
  const running = page.getByRole('link', { name: 'Open Parma in Live; channel is running', exact: true });
  const stoppedRow = page.locator('.radio-directory-site-row').filter({
    has: page.getByRole('link', { name: 'Ottawa County', exact: true })
  });
  await expect(page.locator('.radio-directory-item-status')).toHaveCount(0);
  await expect(running).toHaveAttribute('title', 'Open Parma in Live · Running');
  await expect(running.locator('svg')).toHaveCSS('fill', /rgb\(/);
  await expect(stoppedRow.getByRole('link', { name: /Live/ })).toHaveCount(0);
  await expect(stoppedRow.locator('.radio-directory-live-state')).toHaveText('Stopped');
  await expect(stoppedRow.locator('.radio-directory-live-state')).toHaveClass(/muted/);
  await running.focus();
  await expect(running).toBeFocused();
  await expect(running).toHaveAccessibleName('Open Parma in Live; channel is running');
});

test('matching-status-light-desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1180, height: 900 });
  await page.goto('/design-system.html?theme=light&view=call-matching');
  await expect(page.locator('.visual-call-matching-example')).toHaveScreenshot('matching-status-light-desktop.png');
});

test('matching-status-dark-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=dark&view=call-matching');
  await expect(page.locator('.visual-call-matching-example')).toHaveScreenshot('matching-status-dark-mobile.png');
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

for (const [name, theme, viewport] of [
  ['receiver-health-dark-desktop', 'dark', { width: 1280, height: 900 }],
  ['receiver-health-light-mobile', 'light', { width: 390, height: 844 }]
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=health`);
    await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}

for (const [theme, viewport] of [
  ['light', { width: 1280, height: 900 }], ['dark', { width: 1280, height: 900 }],
  ['light', { width: 390, height: 844 }], ['dark', { width: 390, height: 844 }]
]) {
  const size = viewport.width > 900 ? 'desktop' : 'mobile';
  test(`status-primitives-${theme}-${size}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=status-primitives`);
    await expect(page.locator('body')).toHaveScreenshot(`status-primitives-${theme}-${size}.png`, { fullPage: true });
  });

  test(`compact records and status ${theme} ${size} preserve field order and keyboard controls`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=status-primitives`);
    const example = page.locator('.visual-status-primitives-example');
    const metrics = example.locator('.ui-metric');
    await expect(metrics).toHaveCount(4);
    await expect(example.getByRole('progressbar', { name: 'VCE processor use' })).toHaveAttribute('value', '10.3');
    await expect(example.getByRole('progressbar', { name: 'Free storage space' })).toHaveAttribute('value', '6');
    await expect(metrics.last()).toContainText('Unavailable');
    await expect(metrics.last()).toContainText('Waiting for the first sample');
    await expect(metrics.last().locator('progress')).toHaveCount(0);
    const defaultRecord = example.locator('.visual-records-default tbody tr').first();
    const customRecord = example.locator('.visual-records-custom tbody tr').first();
    expect(await defaultRecord.locator('td').evaluateAll((cells) => cells.map((cell) => cell.dataset.column)))
      .toEqual(['talkgroup', 'radio', 'site', 'action', 'time', 'copies', 'match', 'outputs']);
    expect(await customRecord.locator('td').evaluateAll((cells) => cells.map((cell) => cell.dataset.column)))
      .toEqual(['site', 'time', 'outputs', 'copies', 'talkgroup', 'match', 'action', 'radio']);
    await expect(example.getByRole('button', { name: 'Compare copies of County Fire Dispatch' })).toHaveCount(2);
    await expect(example.locator('.visual-records-default .identity-summary-primary').first()).toHaveAttribute('href', '#talkgroup-1201');
    for (const record of [defaultRecord, customRecord]) {
      expect(await record.evaluate((row) => {
        const boundary = row.getBoundingClientRect();
        const cells = [...row.querySelectorAll('td')].map((cell) => cell.getBoundingClientRect());
        return cells.every((cell) => cell.left >= boundary.left - 1 && cell.right <= boundary.right + 1) &&
          cells.every((cell, index) => cells.slice(index + 1).every((other) =>
            cell.right <= other.left + 1 || other.right <= cell.left + 1 ||
            cell.bottom <= other.top + 1 || other.bottom <= cell.top + 1));
      })).toBe(true);
      const bounds = await record.boundingBox();
      expect(bounds.height).toBeLessThan(size === 'desktop' ? 180 : 460);
    }
    const disclosure = example.locator('.ui-disclosure-toggle');
    await expect(disclosure).toHaveAccessibleName('Incoming radio data 8 measurements · 2 channels need attention Check soon');
    await expect(disclosure).toHaveAttribute('aria-expanded', 'false');
    await disclosure.focus();
    await expect(disclosure).toHaveCSS('outline-width', '2px');
    await disclosure.press('Enter');
    await expect(disclosure).toHaveAttribute('aria-expanded', 'true');
    await expect(example.locator('#visual-status-details')).toBeVisible();
    await expect(disclosure).toBeFocused();
    await disclosure.press('Space');
    await expect(disclosure).toHaveAttribute('aria-expanded', 'false');
    await expect(example.locator('#visual-status-details')).toBeHidden();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  });
}

test('receiver health gallery shares metric and disclosure structure', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?theme=light&view=health');
  const example = page.locator('.visual-health-example');
  await expect(example.locator('.receiver-health-overview-metrics .ui-metric')).toHaveCount(4);
  await expect(example.locator('.receiver-health-resource-bars .ui-metric')).toHaveCount(4);
  await expect(example.getByRole('progressbar', { name: 'Free storage space' })).toHaveAttribute('value', '6');
  await expect(example.getByRole('button', { name: 'Choose status icon issues' })).toBeVisible();
  await expect(example.locator('.receiver-health-diagnostics-grid .ui-disclosure-copy > small')).toHaveCount(8);
  const diagnostics = example.locator('.receiver-health-diagnostics-grid');
  const toggle = diagnostics.locator('.ui-disclosure-toggle').first();
  await expect(toggle).toHaveAccessibleName('Tuners 4 measurements Normal');
  await toggle.focus();
  await toggle.press('Enter');
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await expect(diagnostics.locator('.receiver-health-section-body').first()).toBeVisible();
  await expect(toggle).toBeFocused();
  const openBounds = await diagnostics.locator('.receiver-health-section').first().boundingBox();
  const gridBounds = await diagnostics.boundingBox();
  expect(Math.abs(openBounds.width - gridBounds.width)).toBeLessThan(2);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
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

test('admin records and permission rows retain their fields on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=admin-access');
  const example = page.locator('.visual-admin-access-example');
  await expect(example.locator('table')).toHaveCount(0);
  await expect(example.locator('.ui-record-card.admin-account-card')).toHaveCount(2);
  await expect(example.locator('.admin-account-primary')).toContainText('Admin access');
  await expect(example.locator('.admin-account-primary')).toContainText('Managed in the desktop application');
  await expect(example.getByRole('button', { name: 'Change password' })).toBeVisible();
  await expect(example.getByRole('button', { name: 'Delete', exact: true })).toBeVisible();
  await expect(example.locator('.ui-permission-row')).toHaveCount(5);
  await expect(example.getByLabel('Minimum access level for Tuner Spectrum')).toBeDisabled();
  await expect(example.getByLabel('Minimum access level for Personal settings')).toBeDisabled();
  expect(await example.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
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

test('Live selected-view actions use compact icons with shared hints', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/design-system.html?theme=light&view=live-notice');
  const header = page.locator('.visual-live-example .live-selected-view-header');
  for (const label of ['Channel details', 'Signal quality']) {
    const action = header.getByRole('link', { name: label });
    await expect(action).toBeVisible();
    await expect(action).toHaveClass(/ui-icon-button/);
    await expect(action).toHaveAttribute('title', label);
    await expect(action.locator('svg')).toHaveCount(1);
    await expect(action.locator('span')).toHaveCount(0);
    const box = await action.boundingBox();
    expect(Math.round(box.width)).toBe(40);
    expect(Math.round(box.height)).toBe(40);
  }
  const channelDetails = header.getByRole('link', { name: 'Channel details' });
  await channelDetails.hover();
  await expect(page.locator('.ui-icon-hint')).toHaveText('Channel details');
  await expect(page.locator('.ui-icon-hint')).toBeVisible();
  await expect(header.getByRole('button', { name: 'Live presentation settings' })).toBeVisible();

  const picker = page.locator('.visual-live-example .live-channel-picker');
  const pickerStates = picker.locator('.channels-tab-state-label');
  expect((await pickerStates.allTextContents()).every((value) => !value.includes('%'))).toBe(true);
  await expect(picker.locator('[data-table-id="county"] .channels-tab-quality'))
    .toHaveClass(/ui-quality-healthy.*ui-quality-level-4/);
  await expect(picker.locator('[data-table-id="city"] .channels-tab-quality'))
    .toHaveClass(/ui-quality-degraded.*ui-quality-level-3/);
  await expect(picker.locator('[data-table-id="regional"] .channels-tab-quality'))
    .toHaveClass(/ui-quality-poor.*ui-quality-level-1/);
  const decodeOnlyQuality = picker.locator('[data-table-id="regional"] .channels-tab-quality');
  await decodeOnlyQuality.evaluate((element) => {
    element.classList.remove('ui-quality-level-1');
    element.classList.add('ui-quality-level-0');
  });
  await expect(decodeOnlyQuality).toHaveCSS('outline-style', 'solid');
  await expect(picker.getByRole('tab', { name: /County Simulcast/ }))
    .toHaveAccessibleName(/-58\.2 dBFS signal strength, 97\.4% decode quality/);

  const rows = page.locator('.channels-live-table tbody > tr');
  const control = rows.nth(0);
  const labeled = rows.nth(1);
  const fallback = rows.nth(3);
  await expect(rows).toHaveCount(5);
  await expect(control.locator('[data-column="channel"]')).toHaveText('LCN 1-1772');
  await expect(control.locator('[data-column="channel"]')).toBeVisible();
  await expect(labeled.locator('[data-column="source"]')).toBeVisible();
  await expect(labeled.locator('[data-column="target"]')).toBeVisible();
  await expect(fallback).toBeVisible();
  const desktopQuality = labeled.locator('.live-decode-quality');
  await expect(desktopQuality).toHaveAccessibleName('Decode quality VC 92.4%');
  await expect(desktopQuality.locator('.live-decode-quality-text')).toBeVisible();
  await expect(desktopQuality.locator('.live-decode-quality-bars')).toBeHidden();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(header.getByRole('button', { name: 'Live presentation settings' })).toBeVisible();
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
    await expect(workspace).toHaveCSS('row-gap', '12px');
    const [primaryBox, detailsBox] = await Promise.all([
      workspace.locator('.live-channels-section').boundingBox(), details.boundingBox()
    ]);
    expect(Math.abs(detailsBox.y - (primaryBox.y + primaryBox.height) - 12)).toBeLessThanOrEqual(1);
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(page.locator('body')).toHaveScreenshot('live-details-collapsed-light-desktop.png');
    await details.getByRole('tab', { name: 'Messages' }).click();
    await expect(details).not.toHaveClass(/collapsed/);
    await expect(details.locator('.live-details-body')).toBeVisible();
    await expect(restoredDivider).toBeVisible();
    await controls.getByRole('button', { name: 'Collapse live details' }).click();
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
  await expect(rows).toHaveCount(5);
  await expect(rows.filter({ visible: true })).toHaveCount(4);
  await expect(rows.first()).toHaveAttribute('aria-selected', 'true');
  await expect(rows.first().locator('[data-column="source-alias"]')).toHaveCount(1);

  const control = rows.nth(0);
  const labeled = rows.nth(1);
  const otaLabeled = rows.nth(2);
  const fallback = rows.nth(3);
  await expect(control.locator('[data-column="channel"]')).toHaveText('LCN 1-1772');
  await expect(control.locator('[data-column="channel"]')).toBeHidden();
  await expect(labeled.locator('[data-column="source-alias"]')).toHaveText('Engine 4');
  await expect(labeled.locator('[data-column="target-alias"]')).toHaveText('Fire Dispatch');
  await expect(labeled.locator('[data-column="source"]')).toBeHidden();
  await expect(labeled.locator('[data-column="target"]')).toBeHidden();
  await expect(otaLabeled.locator('[data-column="source-alias"]')).toHaveText('Unit 312');
  await expect(otaLabeled.locator('[data-column="source"]')).toBeHidden();
  await expect(otaLabeled.locator('[data-column="target"]')).toBeVisible();
  await expect(fallback.locator('[data-column="source"]')).toHaveText('318');
  await expect(fallback.locator('[data-column="target"]')).toHaveText('12044');
  await expect(fallback.locator('[data-column="source"]')).toBeVisible();
  await expect(fallback.locator('[data-column="target"]')).toBeVisible();
  expect(await fallback.locator('[data-column="source"]').evaluate((cell) =>
    getComputedStyle(cell).gridColumnStart)).toBe('1');
  expect(await fallback.locator('[data-column="target"]').evaluate((cell) =>
    getComputedStyle(cell).gridColumnStart)).toBe('7');

  for (const quality of await table.locator('tbody > tr:not([hidden]) .live-decode-quality').all()) {
    await expect(quality).toHaveAccessibleName(/Decode quality (?:CC|VC) \d+\.\d%/);
    await expect(quality.locator('.live-decode-quality-text')).toBeHidden();
    await expect(quality.locator('.live-decode-quality-bars')).toBeVisible();
  }

  const horizontalOverflow = await table.locator('xpath=..').evaluate((element) =>
    element.scrollWidth - element.clientWidth);
  expect(horizontalOverflow).toBeLessThanOrEqual(1);

  const conventional = table.locator('tbody > tr.visual-live-conventional-probe');
  await conventional.evaluate((row) => row.removeAttribute('hidden'));
  await expect(conventional.locator('[data-column="channel"]')).toBeVisible();
  await expect(conventional.locator('[data-column="channel"]')).toHaveText('County Fire Dispatch');

  const details = page.locator('.live-details');
  await expect(page.getByRole('separator', {
    name: 'Resize live channels and details panels'
  })).toBeHidden();
  await expect(details).toHaveClass(/collapsed/);
  await expect(details.locator('.live-details-body')).toBeHidden();
  await details.getByRole('tab', { name: 'Messages' }).click();
  await expect(details).not.toHaveClass(/collapsed/);
  await expect(details.locator('.live-details-body')).toBeVisible();

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
  const tree = page.locator('.live-filter-tree');
  await expect(tree).toHaveAttribute('aria-label', 'Event types');
  await expect(tree).not.toHaveAttribute('role', 'tree');
  const disclosure = tree.locator('.live-filter-expand').first();
  await expect(disclosure).toHaveAccessibleName('Collapse Calls');
  await expect(disclosure).toHaveAttribute('aria-expanded', 'true');
  await expect(disclosure.locator('svg')).toHaveCount(1);
  expect((await disclosure.textContent()).trim()).toBe('');
  const checkbox = tree.locator('.live-filter-node-label input').first();
  const [disclosureBox, checkboxBox] = await Promise.all([
    disclosure.boundingBox(), checkbox.boundingBox()
  ]);
  expect(disclosureBox.x).toBeGreaterThan(checkboxBox.x + checkboxBox.width);
  await disclosure.hover();
  await expect(page.locator('.ui-icon-hint')).toHaveText('Collapse Calls');
  await disclosure.press('Enter');
  await expect(disclosure).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('.ui-icon-hint')).toHaveText('Expand Calls');
  await disclosure.press('Enter');
  await expect(disclosure).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('.ui-icon-hint')).toHaveText('Collapse Calls');
  await page.mouse.move(1, 1);
  await expect(page.locator('.ui-icon-hint')).toBeHidden();
  await expect(page.locator('body')).toHaveScreenshot('live-filter-dark-desktop.png');
});

test('live-filter-light-mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/design-system.html?theme=light&view=live-filter');
  await expect(page.locator('body')).toHaveScreenshot('live-filter-light-mobile.png');
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

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 320], ['dark', 320]]) {
  test(`workflow recipes ${theme} ${width} keep card geometry and failure drafts`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?view=workflows&theme=${theme}`);
    const main = page.locator('.visual-workflows-example');
    await expectMetricGridSpacing(main.locator('.ui-metric-grid'));
    await expectBoxedFacts(main.locator('.ui-facts'), main.locator('.ui-metric').first());
    await expectFlatFacts(main.locator('.ui-fact-list'));
    await expectNoHorizontalOverflow(page);
    const input = main.getByLabel('Display name', { exact: true });
    const save = main.getByRole('button', { name: 'Save Changes', exact: true });
    const cancel = main.getByRole('button', { name: 'Cancel', exact: true });
    await expect(save).toBeDisabled();
    await input.fill('MetropolitanEmergencyCommunicationsCountyPublicSafety');
    await save.click();
    await expect(main.locator('form')).toHaveAttribute('aria-busy', 'true');
    await expect(input).toBeDisabled();
    await expect(cancel).toBeDisabled();
    await expect(main.getByRole('status')).toHaveText('Saving…');
    await expect(main.getByRole('alert')).toHaveText('The changes could not be saved. Try again.');
    await expect(input).toHaveValue('MetropolitanEmergencyCommunicationsCountyPublicSafety');
    await expect(input).toBeEnabled();
    await expect(main.getByLabel('Locked default', { exact: true })).toBeDisabled();
    await expect(main).toHaveScreenshot(`workflow-recipes-${theme}-${width}-error.png`);
    await save.click();
    await expect(main.getByRole('status')).toHaveText('Saved.');
    await expect(save).toBeDisabled();
    await expect(cancel).toBeEnabled();
    const pager = main.getByRole('navigation', { name: 'Results pages' });
    await expect(pager).toContainText('Rows 1–10 of 30');
    await expect(pager.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await pager.getByRole('button', { name: 'Next' }).click();
    await expect(pager).toContainText('Rows 11–20 of 30');
    await pager.getByRole('button', { name: 'Next' }).click();
    await expect(pager).toContainText('Rows 21–30 of 30');
    await expect(pager.getByRole('button', { name: 'Next' })).toBeDisabled();
    await expect(main.locator('[data-workflow-availability] > .ui-pill')).toHaveText([
      'Available to listeners', 'Hidden from listeners'
    ]);
    await expect(main.locator('[data-workflow-availability] .ui-pill-warning')).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
  });
}
