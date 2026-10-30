const { expect, test } = require('@playwright/test');
const { expectMetricGridSpacing, expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');
const { expectBoxedFacts, expectFlatFacts } = require('./fixtures/fact-geometry.cjs');
const { expectFormWorkflowGeometry, expectStableFormWorkflowInsets } = require('./fixtures/form-workflow-geometry.cjs');

const galleryCases = [
  ['components-light-desktop', 'light', { width: 1280, height: 900 }],
  ['components-dark-desktop', 'dark', { width: 1280, height: 900 }],
  ['components-light-mobile', 'light', { width: 390, height: 844 }],
  ['components-dark-mobile', 'dark', { width: 390, height: 844 }],
];

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

for (const theme of ['light']) {
  test(`Live column auto-fit ignores table minimum width and remains stable in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
    const measurements = await page.evaluate(async () => {
      const application = await fetch('/assets/app.js').then((response) => response.text());
      const source = application.slice(application.indexOf('function measureTableColumnContentWidth('),
        application.indexOf('function applyPreferredTableWidths('));
      const limits = application.match(/^const TABLE_WIDTH_(?:MINIMUM|MAXIMUM) = \d+;/gm).join('\n');
      const measure = new Function('node', `${limits}\n${source}\nreturn measureTableColumnContentWidth;`)(
        (tag, className = '') => {
          const element = document.createElement(tag);
          element.className = className;
          return element;
        });
      const table = document.createElement('table');
      table.className = 'data-table resizable-table ui-data-table channels-live-table '
        + 'ui-data-table-compact ui-mobile-cards';
      table.dataset.mobileCards = 'true';
      table.dataset.rowDensity = 'dense';
      table.innerHTML = '<colgroup><col><col><col></colgroup><thead><tr>'
        + '<th data-column="status"><button class="table-sort-control">Status</button></th>'
        + '<th data-column="tags"><button class="table-sort-control">Tags</button>'
        + '<span class="column-resizer"></span></th>'
        + '<th data-column="channel"><button class="table-sort-control">Channel</button></th>'
        + '</tr></thead><tbody>'
        + '<tr><td>IDLE</td><td data-column="tags" data-ui-hint="Current control channel">CC</td>'
        + '<td>Regional Emergency Services Dispatch Channel</td></tr>'
        + '<tr><td>ACTIVE</td><td data-column="tags">V + D</td><td>Dispatch</td></tr>'
        + '</tbody>';
      document.body.append(table);
      const sourceStyle = getComputedStyle(table);
      const headers = [...table.tHead.rows[0].cells];
      const columns = [...table.querySelectorAll('col')];
      const fittedWidths = [];
      for (const startingWidth of [72, 360, 1000]) {
        columns[1].style.width = `${startingWidth}px`;
        table.style.width = `${startingWidth + 600}px`;
        const tags = measure(table, headers[1], 1);
        columns[1].style.width = `${tags}px`;
        fittedWidths.push(tags, measure(table, headers[1], 1));
      }
      const result = {
        sourceMinimumWidth: sourceStyle.minWidth,
        sourceLayout: sourceStyle.tableLayout,
        fittedWidths,
        longValueWidth: measure(table, headers[2], 2),
        remainingClones: document.querySelectorAll('.table-column-autofit-measurement').length
      };
      table.remove();
      return result;
    });
    expect(measurements.sourceMinimumWidth).toBe('720px');
    expect(measurements.sourceLayout).toBe('fixed');
    expect(measurements.fittedWidths[0]).toBeGreaterThanOrEqual(48);
    expect(measurements.fittedWidths[0]).toBeLessThan(140);
    expect(new Set(measurements.fittedWidths).size).toBe(1);
    expect(measurements.longValueWidth).toBeGreaterThan(measurements.fittedWidths[0] * 2);
    expect(measurements.longValueWidth).toBeLessThan(720);
    expect(measurements.remainingClones).toBe(0);
  });
}

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
  await expect(page.locator('.visual-scanner-example .scanner-playback-controls > .ui-icon-button').first())
    .toHaveCSS('width', '42px');
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

for (const theme of ['light', 'dark']) {
  for (const width of [1280, 390]) {
    test(`shared text hints update and preserve multiline details ${theme} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 });
      await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
      const action = page.locator('[data-visual-text-hint]');
      const time = page.locator('[data-visual-time-hint]');
      const hint = page.locator('.ui-icon-hint');
      await expect(hint).toHaveCount(1);
      await action.scrollIntoViewIfNeeded();
      await page.mouse.move(0, 0);
      await action.evaluate((element) => element.setAttribute('aria-describedby', 'existing-help'));
      await action.hover();
      await expect(hint).toHaveText('Open details for the selected call');
      await expect(hint).toBeVisible();
      const hintId = await hint.getAttribute('id');
      await expect(action).toHaveAttribute('aria-describedby', `existing-help ${hintId}`);
      await expect(action).not.toHaveAttribute('title');
      await action.evaluate((element) => { element.dataset.uiHint = 'Details are being refreshed'; });
      await expect(hint).toHaveText('Details are being refreshed');
      await action.evaluate((element) => { element.dataset.uiHint = ''; });
      await expect(hint).toBeHidden();
      await expect(action).toHaveAttribute('aria-describedby', 'existing-help');
      await action.evaluate((element) => { element.dataset.uiHint = 'Open details for the selected call'; });
      await expect(hint).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(hint).toBeHidden();
      await expect(action).toHaveAttribute('aria-describedby', 'existing-help');

      await time.scrollIntoViewIfNeeded();
      await page.mouse.move(0, 0);
      await time.hover();
      await expect(hint).toHaveText('October 27, 2026, 10:08:00 AM\nCall length: 42 seconds');
      await expect(hint).toHaveCSS('white-space', 'pre-line');
      await expect(time).not.toHaveAttribute('title');
      await expect(hint).toHaveScreenshot(`text-hint-${theme}-${width}.png`);
      const bounds = await hint.boundingBox();
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(844);
      await hint.hover();
      await expect(hint).toBeVisible();
      await page.mouse.move(0, 0);
      await expect(hint).toBeHidden();

      // A hint on a wrapping label is also available from its focused input.
      await action.evaluate((element) => {
        const label = document.createElement('label');
        label.dataset.uiHint = 'Browser playback volume';
        label.dataset.visualWrappedHint = '';
        const input = document.createElement('input');
        input.type = 'range';
        input.setAttribute('aria-label', 'Example volume');
        label.append(input);
        element.after(label);
      });
      await action.focus();
      await page.keyboard.press('Tab');
      const volume = page.getByRole('slider', { name: 'Example volume' });
      await expect(volume).toBeFocused();
      await expect(hint).toHaveText('Browser playback volume');
      await expect(hint).toBeVisible();
      await expect(volume).toHaveAttribute('aria-describedby', hintId);
      await volume.evaluate((element) => {
        element.value = '50';
        element.parentElement.dataset.uiHint = Array(80).fill('Additional volume details').join('\n');
      });
      await expect.poll(() => hint.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
      await hint.evaluate((element) => { element.scrollTop = element.clientHeight; });
      await expect.poll(() => hint.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
      await expect(hint).toBeVisible();
      await volume.press('ArrowRight');
      await expect(volume).toHaveValue('51');
      await page.keyboard.press('Escape');
      await expect(hint).toBeHidden();
      await expect(volume).not.toHaveAttribute('aria-describedby');

      await action.focus();
      await page.keyboard.press('Tab');
      await expect(hint).toBeVisible();
      await page.evaluate(() => window.scrollBy(0, scrollY > 0 ? -1 : 1));
      await expect(hint).toBeHidden();
      await expect(volume).toBeFocused();
      await expect(volume).not.toHaveAttribute('aria-describedby');
    });
  }
}

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

async function expectChannelMobileActions(page) {
  const rows = page.locator('.channel-catalog-table tbody tr').filter({
    has: page.locator('.channel-name-actions')
  });
  await expect(rows).toHaveCount(2);
  for (const row of await rows.all()) {
    const alias = row.locator('td[data-label="Alias List"]');
    const name = row.locator('.channel-edit-name');
    const details = row.getByRole('link', { name: 'Details', exact: true });
    const live = row.getByRole('link', { name: 'Live', exact: true });
    const startup = row.locator('.channel-startup-order');
    await expect(startup).toHaveAttribute('data-label', 'Auto start');
    await expect(name).toBeVisible();
    await expect(row.getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0);
    await expect(details).toBeVisible();
    await expect(live).toBeVisible();
    await expect(startup).toHaveCSS('text-align', 'left');
    expect(await startup.evaluate((cell) => getComputedStyle(cell, '::before').textAlign)).toBe('left');
    const [aliasBox, nameBox, detailsBox, liveBox, rowBox] = await Promise.all([
      alias.boundingBox(), name.boundingBox(), details.boundingBox(), live.boundingBox(), row.boundingBox()
    ]);
    expect(nameBox.y).toBeLessThan(aliasBox.y);
    for (const actionBox of [detailsBox, liveBox]) {
      expect(actionBox.y).toBeGreaterThanOrEqual(aliasBox.y + aliasBox.height);
      expect(actionBox.height).toBeGreaterThan(0);
      expect(actionBox.x).toBeGreaterThanOrEqual(rowBox.x);
      expect(actionBox.x + actionBox.width).toBeLessThanOrEqual(rowBox.x + rowBox.width);
    }
    expect(detailsBox.y).toBe(liveBox.y);
    expect(liveBox.x).toBeGreaterThan(detailsBox.x + detailsBox.width);
    await expect(details).toHaveClass(/ui-link-text/);
    await expect(live).toHaveClass(/ui-link-text/);
  }
  const startupControls = page.locator('.channel-order-controls');
  await expect(startupControls.getByRole('button', { name: 'Start earlier', exact: true })).toBeVisible();
  await expect(startupControls.getByRole('button', { name: 'Start earlier', exact: true }))
    .toHaveCSS('height', '32px');
  await expect(startupControls.locator('.channel-order-number')).toHaveText('1');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
    page.viewportSize().width);
}

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

test('computer page details copy success and failure', async ({ page }) => {
  await page.addInitScript(() => {
    window.computerCopyText = '';
    window.computerCopyDenied = false;
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { async writeText(text) {
      if (window.computerCopyDenied) throw new Error('Clipboard denied');
      window.computerCopyText = text;
    } } });
  });
  await page.goto('/design-system.html?theme=light&view=computer');
  const computer = page.locator('[data-receiver-health-section="computer"]');
  await expect(computer.locator('.receiver-health-section-body')).toBeVisible();
  await computer.getByRole('button', { name: 'Copy details', exact: true }).click();
  await expect(computer.getByRole('status')).toHaveText('Computer and version details copied.');
  expect(await page.evaluate(() => window.computerCopyText)).toContain('Installed RAM: 32.0 GiB');
  expect(await page.evaluate(() => window.computerCopyText)).toContain('VCE memory limit: 2.0 GiB');
  await page.evaluate(() => { window.computerCopyDenied = true; });
  await computer.getByRole('button', { name: 'Copy details', exact: true }).click();
  await expect(computer.getByRole('status')).toHaveText(
    'The browser could not copy these details. Try again or select the text to copy it.');
  await expect(computer.getByRole('button', { name: 'Copy details', exact: true })).toBeEnabled();
  await expectNoHorizontalOverflow(page);
});

for (const theme of ['light']) {
  for (const width of [1280]) {
    test(`Live details icon tabs preserve shared states and hints ${theme} ${width}`, async ({ page }) => {
      await page.setViewportSize({ width, height: width === 1280 ? 800 : 844 });
      await page.goto(`/design-system.html?theme=${theme}&view=live-notice`);
      const tabs = page.getByRole('tablist', { name: 'Live details', exact: true });
      for (const [label, glyph] of [['Events', 'scan-lists'], ['Messages', 'messages'], ['Channel', 'channel']]) {
        const tab = tabs.getByRole('tab', { name: label, exact: true });
        await expect(tab).toBeVisible();
        await expect(tab).toHaveClass(/ui-segmented-option-icon/);
        await expect(tab).toHaveAttribute('data-ui-hint', label);
        await expect(tab).toHaveText('');
        await expect(tab.locator('svg')).toHaveCSS('width', '18px');
        await expect(tab.locator('use')).toHaveAttribute('href', `#visual-icon-${glyph}`);
        const box = await tab.boundingBox();
        expect(Math.round(box.width)).toBe(36);
        expect(Math.round(box.height)).toBe(36);
      }
      const events = tabs.getByRole('tab', { name: 'Events', exact: true });
      const messages = tabs.getByRole('tab', { name: 'Messages', exact: true });
      const hint = page.locator('.ui-icon-hint');
      await expect(events).toHaveAttribute('aria-selected', 'true');
      const selectedAppearance = await events.evaluate((element) => {
        const style = getComputedStyle(element);
        return { background: style.backgroundColor, color: style.color, shadow: style.boxShadow };
      });
      await messages.hover();
      await expect(hint).toBeVisible();
      await expect(hint).toHaveText('Messages');
      await expect(messages).toHaveAttribute('aria-selected', 'false');
      await page.mouse.move(0, 0);
      await expect(hint).toBeHidden();
      await events.click();
      await expect(hint).toBeHidden();
      await page.mouse.move(0, 0);
      await page.keyboard.press('Tab');
      await page.keyboard.press('Shift+Tab');
      await expect(events).toBeFocused();
      await expect(events).toHaveCSS('outline-style', 'solid');
      await expect(hint).toBeVisible();
      await expect(hint).toHaveText('Events');
      await page.keyboard.press('Escape');
      await expect(hint).toBeHidden();
      await messages.click();
      await expect(messages).toHaveAttribute('aria-selected', 'true');
      await expect(messages).toHaveAttribute('tabindex', '0');
      await expect(events).toHaveAttribute('aria-selected', 'false');
      await expect(events).toHaveAttribute('tabindex', '-1');
      await expect(messages).toHaveCSS('background-color', selectedAppearance.background);
      await expect(messages).toHaveCSS('color', selectedAppearance.color);
      await expect(messages).toHaveCSS('box-shadow', selectedAppearance.shadow);
    });
  }
}

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

async function expectDashboardIdentityStack(page) {
  const stack = page.locator('.visual-dashboard-calls-example .dashboard-identity-split');
  const panels = stack.locator(':scope > .ui-section');
  await expect(panels).toHaveCount(2);
  await expect(panels.locator('h2')).toHaveText([
    'Top Destinations · Last 24h', 'Top Sources · Last 24h'
  ]);
  await expect(panels.locator('.table-layout-trigger')).toHaveText(['Columns', 'Columns']);
  const [stackBox, destinationBox, sourceBox] = await Promise.all([
    stack.boundingBox(), panels.nth(0).boundingBox(), panels.nth(1).boundingBox()
  ]);
  expect(destinationBox.width).toBeCloseTo(stackBox.width, 1);
  expect(sourceBox.width).toBeCloseTo(stackBox.width, 1);
  expect(sourceBox.x).toBeCloseTo(destinationBox.x, 1);
  expect(sourceBox.y).toBeGreaterThanOrEqual(destinationBox.y + destinationBox.height);
  await expectNoHorizontalOverflow(page);
}

for (const [theme, width] of [['light', 1280]]) {
  test(`workflow recipes ${theme} ${width} keep card geometry and failure drafts`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?view=workflows&theme=${theme}`);
    const main = page.locator('.visual-workflows-example');
    await expectMetricGridSpacing(main.locator('.ui-metric-grid'));
    await expectBoxedFacts(main.locator('.ui-facts'), main.locator('.ui-metric').first());
    await expectFlatFacts(main.locator('.ui-fact-list'));
    await expectNoHorizontalOverflow(page);
    const input = main.getByLabel('Display name', { exact: true });
    const form = main.locator('form');
    const feedback = main.locator('[data-workflow-feedback]');
    const save = main.getByRole('button', { name: 'Save Changes', exact: true, includeHidden: true });
    const cancel = main.getByRole('button', { name: 'Cancel', exact: true });
    await expect(save).toBeDisabled();
    await expect(save).toBeHidden();
    await expect(form).not.toHaveClass(/is-dirty/);
    const cleanGeometry = await expectFormWorkflowGeometry(form, { modal: false });
    expect(cleanGeometry.wrapped).toBe(true);
    await input.fill('MetropolitanEmergencyCommunicationsCountyPublicSafety');
    await expect(form).toHaveClass(/ui-form-workflow/);
    await expect(form).toHaveClass(/is-dirty/);
    await expect(feedback).toHaveClass(/ui-form-state/);
    await expect(feedback).not.toHaveClass(/ui-feedback/);
    await expect(feedback).toHaveText('Unsaved changes');
    await expect(save).toBeVisible();
    await expectFormWorkflowGeometry(form, { modal: false });
    await expectStableFormWorkflowInsets(form, cleanGeometry);
    await expect(main).toHaveScreenshot(`workflow-recipes-${theme}-${width}-dirty.png`);
    await input.fill('County Dispatch');
    await expect(form).not.toHaveClass(/is-dirty/);
    await expect(feedback).toBeEmpty();
    await expect(feedback).toBeHidden();
    await expect(save).toBeHidden();
    await input.fill('MetropolitanEmergencyCommunicationsCountyPublicSafety');
    await save.click();
    await expect(form).toHaveAttribute('aria-busy', 'true');
    await expect(input).toBeDisabled();
    await expect(cancel).toBeDisabled();
    await expect(main.getByRole('status')).toHaveText('Saving…');
    await expect(main.getByRole('alert')).toHaveText('The changes could not be saved. Try again.');
    await expect(input).toHaveValue('MetropolitanEmergencyCommunicationsCountyPublicSafety');
    await expect(input).toBeEnabled();
    await expect(form).toHaveClass(/is-dirty/);
    await expect(main.getByLabel('Locked default', { exact: true })).toBeDisabled();
    await expectFormWorkflowGeometry(form, { modal: false });
    await expectStableFormWorkflowInsets(form, cleanGeometry);
    await expect(main).toHaveScreenshot(`workflow-recipes-${theme}-${width}-error.png`);
    await save.click();
    await expect(feedback).toBeEmpty();
    await expect(feedback).toBeHidden();
    await expect(form).not.toHaveClass(/is-dirty/);
    await expect(save).toBeDisabled();
    await expect(save).toBeHidden();
    await expect(cancel).toBeEnabled();
    await input.fill('Another draft');
    await expect(form).toHaveClass(/is-dirty/);
    await expect(feedback).toHaveText('Unsaved changes');
    await expect(save).toBeVisible();
    await input.fill('MetropolitanEmergencyCommunicationsCountyPublicSafety');
    await expect(form).not.toHaveClass(/is-dirty/);
    await expect(feedback).toBeEmpty();
    await expect(save).toBeHidden();
    await input.fill('Discard this draft');
    await cancel.click();
    await expect(input).toHaveValue('MetropolitanEmergencyCommunicationsCountyPublicSafety');
    await expect(form).not.toHaveClass(/is-dirty/);
    await expect(feedback).toBeEmpty();
    await expect(save).toBeHidden();
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
