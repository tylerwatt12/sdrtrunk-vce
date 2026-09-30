const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const application = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const modalFoundation = application.slice(application.indexOf('function closeReadOnlyModal('),
  application.indexOf('function statsLoggingState('));
const anchoredDropdown = application.slice(application.indexOf('function anchoredDropdownPlacement('),
  application.indexOf('function compareTableValues('));
const activityCellActions = application.slice(application.indexOf('function activityCellNavigation('),
  application.indexOf('function activityColumns('));

async function installModalHarness(page) {
  await page.goto('/design-system.html?theme=light&view=gallery');
  await page.evaluate((source) => {
    document.body.replaceChildren();
    const node = (tag, className = '', text = null) => {
      const element = document.createElement(tag);
      element.className = className;
      if (text !== null && text !== undefined) element.textContent = String(text);
      return element;
    };
    const valueNode = (value) => value instanceof Node ? value : document.createTextNode(String(value));
    const iconButton = (_iconId, label, className) => {
      const button = node('button', className);
      button.type = 'button';
      button.setAttribute('aria-label', label);
      return button;
    };
    const shared = new Function('node', 'valueNode', 'iconButton',
      `let activeReadOnlyModal = null; ${source}\nreturn { openReadOnlyModal };`)(node, valueNode, iconButton);
    const opener = node('button', 'ui-button ui-button-primary', 'Edit channel');
    opener.type = 'button';
    opener.addEventListener('click', () => {
      const content = node('div');
      content.append(node('p', '', 'Select this text and drag outside the dialog.'),
        node('button', 'ui-button ui-button-primary', 'Save'));
      shared.openReadOnlyModal('Edit Channel', content, { id: 'test-channel' });
    });
    document.body.append(opener);
  }, modalFoundation);
}

async function installStackHarness(page) {
  await page.goto('/design-system.html?theme=light&view=gallery');
  await page.evaluate((source) => {
    document.body.replaceChildren();
    const node = (tag, className = '', text = null) => {
      const element = document.createElement(tag);
      element.className = className;
      if (text !== null) element.textContent = String(text);
      return element;
    };
    const valueNode = (value) => value instanceof Node ? value : document.createTextNode(String(value));
    const iconButton = (_iconId, label, className) => {
      const button = node('button', className);
      button.type = 'button';
      button.setAttribute('aria-label', label);
      return button;
    };
    const shared = new Function('node', 'valueNode', 'iconButton',
      `let activeReadOnlyModal = null; ${source}\nreturn { openReadOnlyModal, closeReadOnlyModal };`)(
      node, valueNode, iconButton);
    const opener = node('button', 'ui-button', 'Edit channel');
    opener.type = 'button';
    opener.addEventListener('click', () => {
      const form = node('form');
      const name = node('input', 'ui-input');
      name.setAttribute('aria-label', 'Channel name');
      const childTrigger = node('button', 'ui-button', 'New list');
      childTrigger.type = 'button';
      const filler = node('div');
      filler.style.height = '1100px';
      form.append(name, childTrigger, filler);
      const parent = shared.openReadOnlyModal('Edit Channel', form, { id: 'parent' });
      name.addEventListener('input', () => parent.setDirty(true));
      childTrigger.addEventListener('click', () => {
        const childForm = node('form');
        const listName = node('input', 'ui-input');
        listName.setAttribute('aria-label', 'List name');
        childForm.append(listName);
        shared.openReadOnlyModal('Create Alias List', childForm,
          { id: 'child', className: 'modal-size-small', stack: 'child' });
        listName.focus();
      });
    });
    window.closeAllTestModals = () => shared.closeReadOnlyModal();
    window.parentConfirmCount = 0;
    window.confirm = () => { window.parentConfirmCount += 1; return false; };
    document.body.append(opener);
  }, modalFoundation);
}

async function installSourceRadioActionHarness(page, theme) {
  await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
  await page.evaluate(({ dropdownSource, actionSource }) => {
    const sprite = document.querySelector('body > svg[hidden]');
    const hint = document.querySelector('.ui-icon-hint');
    document.body.replaceChildren();
    if (sprite) document.body.append(sprite);
    if (hint) document.body.append(hint);
    const node = (tag, className = '', text = null) => {
      const element = document.createElement(tag);
      element.className = className;
      if (text !== null && text !== undefined) element.textContent = String(text);
      return element;
    };
    const valueNode = (value) => value instanceof Node ? value : document.createTextNode(String(value));
    const iconButton = (_iconId, label, className) => {
      const button = node('button', className);
      button.type = 'button';
      button.setAttribute('aria-label', label);
      return button;
    };
    const anchor = (content, target, className = '') => {
      const link = node('a', className);
      link.href = target;
      link.append(valueNode(content));
      return link;
    };
    const iconGlyph = (icon) => {
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
      svg.setAttribute('aria-hidden', 'true');
      use.setAttribute('href', `#visual-${icon}`);
      svg.append(use);
      return svg;
    };
    const harness = new Function('node', 'valueNode', 'iconButton', 'anchor', 'iconGlyph',
      'capabilityAllowed', 'ACCESS_CAPABILITIES', 'entityTarget',
      'activityCellFilterRouteOverrides', 'currentHref',
      `let activeRenderController = null; let activityCellActionSequence = 0;
      ${dropdownSource}\n${actionSource}\nreturn { activityCellValue };`);
    const { activityCellValue } = harness(node, valueNode, iconButton, anchor,
      iconGlyph, () => true, { RADIO: 'radio' }, () => '/radios/2808137',
      () => ({ sourceId: 2808137 }), () => '/activity?sourceId=2808137');
    document.body.append(activityCellValue('2808137',
      { id: 1, source_entity_ref: { id: 2808137 } }, 'source', {}, {}));
  }, { dropdownSource: anchoredDropdown, actionSource: activityCellActions });
  return {
    trigger: page.locator('.activity-cell-action-link'),
    tooltip: page.getByRole('group', { name: 'source radio 2808137 actions' })
  };
}

test('modal backdrop dismisses only when a primary pointer starts and ends on it', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 720 });
  await installModalHarness(page);
  const opener = page.getByRole('button', { name: 'Edit channel', exact: true });
  await opener.click();
  const dialog = page.getByRole('dialog', { name: 'Edit Channel' });
  await expect(dialog).toBeVisible();

  const dialogBounds = await dialog.boundingBox();
  await page.mouse.move(dialogBounds.x + 120, dialogBounds.y + 110);
  await page.mouse.down();
  await page.mouse.move(4, 4, { steps: 8 });
  await page.mouse.up();
  await expect(dialog).toBeVisible();

  await page.mouse.move(4, 4);
  await page.mouse.down();
  await page.mouse.move(dialogBounds.x + 120, dialogBounds.y + 110, { steps: 8 });
  await page.mouse.up();
  await expect(dialog).toBeVisible();

  await page.mouse.click(4, 4);
  await expect(dialog).toHaveCount(0);
  await expect(opener).toBeFocused();
});

test('a child dialog preserves the parent draft, scroll, focus, and body lock', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 720 });
  await installStackHarness(page);
  const opener = page.getByRole('button', { name: 'Edit channel' });
  await opener.click();
  const parent = page.locator('.read-only-modal').filter({ hasText: 'Edit Channel' });
  const parentContent = parent.locator('.modal-content');
  const draft = parent.getByLabel('Channel name');
  await draft.fill('Unfinished channel');
  const scroll = await parentContent.evaluate((element) => {
    element.scrollTop = 180;
    return element.scrollTop;
  });
  expect(scroll).toBeGreaterThan(0);
  const trigger = parent.getByRole('button', { name: 'New list' });
  await trigger.evaluate((element) => {
    element.focus({ preventScroll: true });
    element.click();
  });
  const child = page.getByRole('dialog', { name: 'Create Alias List' });
  await expect(child).toBeVisible();
  await expect(child.getByLabel('List name')).toBeFocused();
  await expect(parent).toHaveAttribute('aria-modal', 'false');
  await expect(parent.locator('..')).toHaveAttribute('inert', '');
  await expect(page.locator('body')).toHaveClass(/modal-open/);
  await expect(page.locator('.read-only-modal')).toHaveCount(2);

  await page.keyboard.press('Escape');
  await expect(child).toHaveCount(0);
  await expect(parent).toHaveAttribute('aria-modal', 'true');
  await expect(parent.locator('..')).not.toHaveAttribute('inert', '');
  await expect(trigger).toBeFocused();
  await expect(draft).toHaveValue('Unfinished channel');
  expect(await parentContent.evaluate((element) => element.scrollTop)).toBe(scroll);
  expect(await page.evaluate(() => window.parentConfirmCount)).toBe(0);
  await expect(page.locator('body')).toHaveClass(/modal-open/);

  await trigger.click();
  await page.mouse.click(4, 4);
  await expect(page.getByRole('dialog', { name: 'Create Alias List' })).toHaveCount(0);
  await expect(parent).toBeVisible();
  expect(await page.evaluate(() => window.parentConfirmCount)).toBe(0);

  await trigger.click();
  expect(await page.evaluate(() => window.closeAllTestModals())).toBe(false);
  await expect(page.locator('.read-only-modal')).toHaveCount(2);
  expect(await page.evaluate(() => window.parentConfirmCount)).toBe(1);
  await page.evaluate(() => { window.confirm = () => true; window.closeAllTestModals(); });
  await expect(page.locator('.read-only-modal')).toHaveCount(0);
  await expect(page.locator('body')).not.toHaveClass(/modal-open/);
  await expect(opener).toBeFocused();
});

for (const theme of ['light', 'dark']) {
  for (const viewport of [
    { name: 'desktop', width: 1100, height: 800 },
    { name: 'mobile', width: 390, height: 844 }
  ]) {
    test(`source radio action tooltip fits ${viewport.name} in ${theme} theme`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto(`/design-system.html?theme=${theme}&view=activity-action-tooltip`);
      const specimen = page.getByRole('group', { name: 'source radio 2808137 actions' });
      await expect(specimen).toBeVisible();
      await expect(page.locator('.visual-activity-action-tooltip-example'))
        .toHaveScreenshot(`activity-action-tooltip-${theme}-${viewport.name}.png`);

      const { trigger, tooltip } = await installSourceRadioActionHarness(page, theme);
      await trigger.click();
      await expect(tooltip).toBeVisible();
      await expect(trigger).toHaveAttribute('aria-expanded', 'true');
      await expect(trigger).toHaveAttribute('aria-controls', await tooltip.getAttribute('id'));
      await expect(tooltip).toHaveAttribute('popover', 'auto');
      const actions = tooltip.locator('a.activity-cell-action-icon.ui-icon-button');
      await expect(actions).toHaveCount(2);
      await expect(actions.nth(0)).toHaveAttribute('aria-label', 'Filter activity by this source radio');
      await expect(actions.nth(0)).toHaveAttribute('href', '/activity?sourceId=2808137');
      await expect(actions.nth(0).locator('svg use')).toHaveAttribute('href', '#visual-icon-filter');
      await expect(actions.nth(1)).toHaveAttribute('aria-label', 'Open source radio details');
      await expect(actions.nth(1)).toHaveAttribute('href', '/radios/2808137');
      await expect(actions.nth(1).locator('svg use')).toHaveAttribute('href', '#visual-icon-open-details');
      await expect(page.locator('.modal-backdrop:visible')).toHaveCount(0);
      await expect(page.locator('body')).not.toHaveClass(/modal-open/);

      const bounds = await tooltip.boundingBox();
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.y).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width);
      expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height);
    });
  }
}

test('activity icon tooltip follows hover and click, with shared icon hints', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 650 });
  const { trigger, tooltip } = await installSourceRadioActionHarness(page, 'light');
  await trigger.hover();
  await expect(tooltip).toBeVisible();
  const filter = tooltip.getByRole('link', { name: 'Filter activity by this source radio' });
  await filter.hover();
  await expect(tooltip).toBeVisible();
  const hint = page.locator('.ui-icon-hint');
  await expect(hint).toHaveText('Filter activity by this source radio');
  await expect(hint).toBeVisible();
  await page.mouse.move(1, 50);
  await expect(tooltip).toHaveCount(0);
  await expect(hint).toBeHidden();

  await trigger.click();
  await expect(tooltip).toBeVisible();
  await page.mouse.move(1, 50);
  await expect(tooltip).toBeVisible();
  await page.mouse.click(1, 50);
  await expect(tooltip).toHaveCount(0);
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
});

test('activity icon tooltip opens with keyboard and Escape returns focus', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 650 });
  const { trigger, tooltip } = await installSourceRadioActionHarness(page, 'light');
  await page.keyboard.press('Tab');
  await expect(trigger).toBeFocused();
  await expect(tooltip).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(tooltip.getByRole('link', { name: 'Filter activity by this source radio' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(tooltip.getByRole('link', { name: 'Open source radio details' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(tooltip).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
});

test('activity icon tooltip escapes a clipped table at the viewport edge', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 600 });
  const { trigger, tooltip } = await installSourceRadioActionHarness(page, 'light');
  await trigger.evaluate((element) => {
    const wrapper = document.createElement('div');
    wrapper.className = 'ui-table-wrap';
    Object.assign(wrapper.style, {
      position: 'fixed', right: '8px', bottom: '8px', width: '160px', height: '40px',
      overflow: 'hidden'
    });
    const table = document.createElement('table');
    table.className = 'ui-data-table';
    element.replaceWith(wrapper);
    table.insertRow().insertCell().append(element);
    wrapper.append(table);
  });
  const table = page.locator('.ui-table-wrap');
  await trigger.click();
  await expect(tooltip).toBeVisible();
  const [tableBounds, tooltipBounds] = await Promise.all([table.boundingBox(), tooltip.boundingBox()]);
  expect(tooltipBounds.y).toBeLessThan(tableBounds.y);
  expect(tooltipBounds.x).toBeGreaterThanOrEqual(0);
  expect(tooltipBounds.x + tooltipBounds.width).toBeLessThanOrEqual(390);
  expect(tooltipBounds.y + tooltipBounds.height).toBeLessThanOrEqual(600);
});

test.describe('touch activity cell actions', () => {
  test.use({ hasTouch: true });

  test('tap opens icon actions and outside tap dismisses them', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const { trigger, tooltip } = await installSourceRadioActionHarness(page, 'light');
    await page.evaluate(() => {
      window.activityActionHref = '';
      document.addEventListener('click', (event) => {
        const action = event.target.closest('.activity-cell-action-icon');
        if (!action) return;
        window.activityActionHref = action.getAttribute('href');
        event.preventDefault();
      }, true);
    });
    await trigger.tap();
    await expect(tooltip).toBeVisible();
    await tooltip.getByRole('link', { name: 'Filter activity by this source radio' }).tap();
    await expect.poll(() => page.evaluate(() => window.activityActionHref))
      .toBe('/activity?sourceId=2808137');
    await tooltip.getByRole('link', { name: 'Open source radio details' }).tap();
    await expect.poll(() => page.evaluate(() => window.activityActionHref))
      .toBe('/radios/2808137');
    await page.touchscreen.tap(350, 400);
    await expect(tooltip).toHaveCount(0);
    await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });
});
