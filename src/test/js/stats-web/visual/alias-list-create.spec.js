const { expect, test } = require('@playwright/test');

test('inline Alias List creation stays isolated from its parent workflow', async ({ page }) => {
  await page.goto('/design-system.html?theme=light&view=gallery');
  await page.evaluate(async () => {
    const { createInlineAliasListCreator } = await import(
      '/assets/features/alias-list-create.js?visual-test=1');
    document.body.replaceChildren();
    const node = (tag, className = '', text = null) => {
      const element = document.createElement(tag);
      element.className = className;
      if (text !== null) element.textContent = String(text);
      return element;
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
    const form = node('form', 'inline-alias-test-form');
    const select = node('select', 'ui-select');
    select.setAttribute('aria-label', 'Alias List');
    const current = node('option', '', 'Default P25');
    current.value = '1';
    select.append(current);
    let failNext = false;
    const requestJson = async (path, options) => {
      window.inlineAliasRequest = { path, options };
      if (failNext) {
        failNext = false;
        throw new Error('Revision changed. Try again.');
      }
      return { alias_list_id: 88, revision: 6 };
    };
    const creator = createInlineAliasListCreator({ node, iconGlyph, uiPill, requestJson }, {
      select,
      family: 'P25',
      getRevision: async () => 5,
      onCreated: ({ aliasList }) => {
        const option = node('option', '', aliasList.name);
        option.value = String(aliasList.id);
        select.append(option);
        select.value = option.value;
      }
    });
    const save = node('button', 'ui-button ui-button-primary', 'Save workflow');
    save.type = 'submit';
    window.inlineAliasSubmitted = 0;
    window.failInlineAliasCreate = () => { failNext = true; };
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      window.inlineAliasSubmitted += 1;
    });
    form.append(select, creator, save);
    document.body.append(form);
  });

  await page.getByRole('button', { name: 'Save workflow' }).click();
  expect(await page.evaluate(() => window.inlineAliasSubmitted)).toBe(1);

  const trigger = page.getByRole('button', { name: 'New list' });
  await trigger.click();
  await page.getByLabel('Name').fill('County Public Safety');
  await page.getByLabel('Name').press('Enter');
  await expect(page.getByLabel('Alias List', { exact: true })).toHaveValue('88');
  await expect(page.getByText(/was created and selected/)).toBeVisible();
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(await page.evaluate(() => window.inlineAliasRequest)).toMatchObject({
    path: '/api/v1/admin/alias-lists',
    options: { method: 'POST', body: { revision: 5, name: 'County Public Safety', family: 'p25' } }
  });

  await page.evaluate(() => window.failInlineAliasCreate());
  await trigger.click();
  const name = page.getByLabel('Name');
  await name.fill('Retry List');
  await page.getByRole('button', { name: 'Create and use' }).click();
  await expect(page.getByRole('alert')).toContainText('Revision changed');
  await expect(name).toBeEnabled();
  await expect(name).toBeFocused();

  await page.getByRole('button', { name: 'Cancel' }).focus();
  await page.getByRole('button', { name: 'Cancel' }).press('Escape');
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  await expect(trigger).toBeFocused();
});
