const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const application = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const modalFoundation = application.slice(application.indexOf('function closeReadOnlyModal('),
  application.indexOf('function statsLoggingState('));
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
  await page.evaluate(({ modalSource, actionSource }) => {
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
    const anchor = (content, target, className = '') => {
      const link = node('a', className);
      link.href = target;
      link.append(valueNode(content));
      return link;
    };
    const harness = new Function('node', 'valueNode', 'iconButton', 'anchor', 'iconGlyph',
      'capabilityAllowed', 'ACCESS_CAPABILITIES', 'entityTarget',
      'activityCellFilterRouteOverrides', 'currentHref',
      `let activeReadOnlyModal = null; let activityCellActionSequence = 0;
      ${modalSource}\n${actionSource}\nreturn { activityCellValue };`);
    const { activityCellValue } = harness(node, valueNode, iconButton, anchor,
      () => node('span'), () => true, { RADIO: 'radio' }, () => '/radios/2808137',
      () => ({ sourceId: 2808137 }), () => '/activity?sourceId=2808137');
    document.body.append(activityCellValue('2808137',
      { id: 1, source_entity_ref: { id: 2808137 } }, 'source', {}, {}));
  }, { modalSource: modalFoundation, actionSource: activityCellActions });
  await page.locator('.activity-cell-action-link').click();
  return page.getByRole('dialog', { name: '2808137 actions' });
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
    test(`source radio actions align at ${viewport.name} size in ${theme} theme`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto(`/design-system.html?theme=${theme}&view=activity-action-modal`);
      const specimen = page.getByRole('dialog', { name: '2808137 actions' });
      await expect(specimen).toHaveScreenshot(`activity-action-modal-${theme}-${viewport.name}.png`);

      const dialog = await installSourceRadioActionHarness(page, theme);
      await expect(dialog).toBeVisible();
      const links = dialog.locator('.tuner-frequency-action');
      await expect(links).toHaveCount(2);
      await expect(links.first()).toContainText('Filter activity');
      await expect(links.last()).toContainText('Open source details');

      const layout = await dialog.evaluate((element) => {
        const bounds = (node) => {
          const { left, right, top, bottom, width } = node.getBoundingClientRect();
          return { left, right, top, bottom, width };
        };
        const actions = [...element.querySelectorAll('.tuner-frequency-action')];
        return {
          dialog: bounds(element),
          overflow: element.scrollWidth - element.clientWidth,
          links: actions.map((action) => ({
            bounds: bounds(action),
            overflow: action.scrollWidth - action.clientWidth,
            title: bounds(action.querySelector('strong')),
            description: bounds(action.querySelector('small'))
          }))
        };
      });
      const [filter, details] = layout.links;
      expect(Math.abs(filter.bounds.left - details.bounds.left)).toBeLessThanOrEqual(1);
      expect(Math.abs(filter.bounds.width - details.bounds.width)).toBeLessThanOrEqual(1);
      expect(Math.abs(filter.title.left - details.title.left)).toBeLessThanOrEqual(1);
      expect(layout.overflow).toBeLessThanOrEqual(1);
      for (const action of layout.links) {
        expect(action.overflow).toBeLessThanOrEqual(1);
        expect(action.title.right).toBeLessThanOrEqual(action.bounds.right);
        expect(action.description.right).toBeLessThanOrEqual(action.bounds.right);
      }

      if (viewport.name === 'desktop') {
        expect(layout.dialog.width).toBeLessThan(viewport.width);
        expect(Math.abs(filter.description.left - details.description.left)).toBeLessThanOrEqual(1);
        for (const action of layout.links) {
          expect(action.description.left).toBeGreaterThan(action.title.right);
        }
      } else {
        expect(layout.dialog.left).toBeGreaterThanOrEqual(0);
        expect(layout.dialog.right).toBeLessThanOrEqual(viewport.width);
        for (const action of layout.links) {
          expect(Math.abs(action.title.left - action.description.left)).toBeLessThanOrEqual(1);
          expect(action.description.top).toBeGreaterThanOrEqual(action.title.bottom);
        }
      }
    });
  }
}
