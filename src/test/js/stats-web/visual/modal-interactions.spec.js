const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const application = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const modalFoundation = application.slice(application.indexOf('function closeReadOnlyModal('),
  application.indexOf('function statsLoggingState('));

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
