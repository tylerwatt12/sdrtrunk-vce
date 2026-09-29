const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const application = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const modalFoundation = application.slice(application.indexOf('function closeReadOnlyModal('),
  application.indexOf('function statsLoggingState('));

async function installCreatorHarness(page, selectionFailsOnce = false) {
  await page.goto('/design-system.html?theme=light&view=gallery');
  await page.evaluate(async ({ source, selectionFailsOnce }) => {
    const { createAliasListPopupTrigger } = await import(
      '/assets/features/alias-list-create.js?visual-test=2');
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
    const iconGlyph = () => {
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('aria-hidden', 'true');
      return icon;
    };
    const uiPill = (label, tone) => {
      const pill = node('span', `ui-pill ui-pill-${tone}`);
      pill.append(node('span', '', label));
      return pill;
    };
    const { openReadOnlyModal } = new Function('node', 'valueNode', 'iconButton',
      `let activeReadOnlyModal = null; ${source}\nreturn { openReadOnlyModal };`)(
      node, valueNode, iconButton);
    let failNextRequest = false;
    let failNextSelection = selectionFailsOnce;
    window.inlineAliasRequests = [];
    window.inlineAliasSubmitted = 0;
    window.failInlineAliasCreate = () => { failNextRequest = true; };
    const requestJson = async (path, options) => {
      window.inlineAliasRequests.push({ path, options });
      if (failNextRequest) {
        failNextRequest = false;
        throw new Error('Revision changed. Try again.');
      }
      return { alias_list_id: 88, revision: 6 };
    };
    const opener = node('button', 'ui-button ui-button-primary', 'Edit channel');
    opener.type = 'button';
    opener.addEventListener('click', () => {
      const form = node('form', 'inline-alias-test-form');
      const draft = node('input', 'ui-input');
      draft.setAttribute('aria-label', 'Channel draft');
      const select = node('select', 'ui-select');
      select.setAttribute('aria-label', 'Alias List');
      const current = node('option', '', 'Default P25');
      current.value = '1';
      select.append(current);
      const creator = createAliasListPopupTrigger({
        node, iconGlyph, uiPill, requestJson, openReadOnlyModal
      }, {
        select,
        family: 'P25',
        getRevision: async () => 5,
        onCreated: ({ aliasList }) => {
          if (failNextSelection) {
            failNextSelection = false;
            throw new Error('Selection could not be updated.');
          }
          const option = node('option', '', aliasList.name);
          option.value = String(aliasList.id);
          select.append(option);
          select.value = option.value;
          select.dispatchEvent(new Event('change', { bubbles: true }));
        }
      });
      const save = node('button', 'ui-button ui-button-primary', 'Save workflow');
      save.type = 'submit';
      form.addEventListener('submit', (event) => {
        event.preventDefault();
        window.inlineAliasSubmitted += 1;
      });
      form.append(draft, select, creator, save);
      openReadOnlyModal('Edit Channel', form, { id: 'test-channel' });
    });
    document.body.append(opener);
  }, { source: modalFoundation, selectionFailsOnce });
}

test('Alias List creation uses a child dialog and preserves the channel draft', async ({ page }) => {
  await installCreatorHarness(page);
  await page.getByRole('button', { name: 'Edit channel' }).click();
  const parent = page.locator('.read-only-modal').filter({ hasText: 'Edit Channel' });
  await parent.getByLabel('Channel draft').fill('Draft channel name');
  await parent.getByRole('button', { name: 'Save workflow' }).click();
  expect(await page.evaluate(() => window.inlineAliasSubmitted)).toBe(1);

  const trigger = parent.locator('.ui-alias-list-create-trigger');
  await trigger.click();
  const child = page.getByRole('dialog', { name: 'Create Alias List' });
  await expect(child).toBeVisible();
  await expect(child.getByLabel('Name')).toBeFocused();
  await expect(trigger).toHaveAttribute('aria-expanded', 'true');
  expect(await child.evaluate((element) => element.closest('.inline-alias-test-form'))).toBe(null);
  await child.getByLabel('Name').fill('County Public Safety');
  await child.getByLabel('Name').press('Enter');
  await expect(child).toHaveCount(0);
  await expect(parent.getByLabel('Alias List', { exact: true })).toHaveValue('88');
  await expect(parent.getByLabel('Channel draft')).toHaveValue('Draft channel name');
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  await expect(parent.getByLabel('Alias List', { exact: true })).toBeFocused();
  expect(await page.evaluate(() => window.inlineAliasSubmitted)).toBe(1);
  expect(await page.evaluate(() => window.inlineAliasRequests[0])).toMatchObject({
    path: '/api/v1/admin/alias-lists',
    options: { method: 'POST', body: { revision: 5, name: 'County Public Safety', family: 'p25' } }
  });

  await page.evaluate(() => window.failInlineAliasCreate());
  await trigger.click();
  const retryDialog = page.getByRole('dialog', { name: 'Create Alias List' });
  await retryDialog.getByLabel('Name').fill('Retry List');
  await retryDialog.getByRole('button', { name: 'Create and use' }).click();
  await expect(retryDialog.getByRole('alert')).toContainText('Revision changed');
  await expect(retryDialog.getByLabel('Name')).toBeEnabled();
  await expect(retryDialog.getByLabel('Name')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(retryDialog).toHaveCount(0);
  await expect(parent).toBeVisible();
  await expect(trigger).toBeFocused();
});

test('selection retry does not create a second Alias List after a successful POST', async ({ page }) => {
  await installCreatorHarness(page, true);
  await page.getByRole('button', { name: 'Edit channel' }).click();
  await page.getByRole('button', { name: 'New list' }).click();
  const child = page.getByRole('dialog', { name: 'Create Alias List' });
  await child.getByLabel('Name').fill('County Public Safety');
  await child.getByRole('button', { name: 'Create and use' }).click();
  await expect(child.getByRole('alert')).toContainText('was created, but could not be selected');
  await expect(child.getByLabel('Name')).toBeDisabled();
  await child.getByRole('button', { name: 'Use created list' }).click();
  await expect(child).toHaveCount(0);
  await expect(page.getByLabel('Alias List', { exact: true })).toHaveValue('88');
  expect(await page.evaluate(() => window.inlineAliasRequests.length)).toBe(1);
});
