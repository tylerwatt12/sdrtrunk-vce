const { expect, test } = require('@playwright/test');

async function installWorkspace(page, theme = 'light', large = false, slow = false) {
  await page.goto(`/design-system.html?theme=${theme}&view=gallery${large ? '&large=1' : ''}${slow ? '&slow=1' : ''}`);
  await page.evaluate(async () => {
    const { createRadioReferenceImportWorkspace } = await import(
      '/assets/features/radioreference-import.js?visual-test=1');
    document.body.replaceChildren();
    document.documentElement.dataset.theme = new URLSearchParams(location.search).get('theme') || 'light';
    const shell = document.createElement('main');
    shell.className = 'content';
    const heading = document.createElement('header');
    heading.className = 'page-header ui-page-header';
    heading.innerHTML = '<div><h1 class="page-title">RadioReference</h1>' +
      '<div class="page-subtitle">Browse and import channels and talkgroups</div></div>';
    const section = document.createElement('section');
    section.className = 'section ui-section';
    section.innerHTML = '<div class="section-title ui-section-title">Browse and import</div>';
    const body = document.createElement('div');
    body.className = 'admin-section-body';
    section.append(body);
    shell.append(heading, section);
    document.body.append(shell);

    const node = (tag, className = '', text = null) => {
      const element = document.createElement(tag);
      element.className = className;
      if (tag === 'select') element.classList.add('ui-select');
      if (text !== null && text !== undefined) element.textContent = String(text);
      return element;
    };
    const iconGlyph = () => {
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('viewBox', '0 0 24 24');
      icon.innerHTML = '<circle cx="12" cy="12" r="7"></circle>';
      return icon;
    };
    const formField = (labelText, control, detail = '') => {
      const field = node('label', 'admin-form-field ui-field');
      field.append(node('span', 'admin-form-label ui-field-label', labelText), control);
      if (detail) field.append(node('small', 'admin-form-help ui-field-detail', detail));
      return field;
    };
    const uiSelectFrame = (select) => {
      const frame = node('span', 'ui-select-frame');
      frame.append(select, iconGlyph());
      return frame;
    };
    const uiPill = (label, tone = 'neutral') => {
      const pill = node('span', `ui-pill ui-pill-${tone}`);
      pill.append(node('span', '', label));
      return pill;
    };
    const uiStatus = (label, tone = 'neutral') => node('span', `ui-status ui-status-${tone}`, label);
    const uiSegmentedControl = (entries, initial, onChange) => {
      const group = node('div', 'ui-segmented');
      entries.forEach((entry) => {
        const button = node('button', `ui-segmented-option${entry.value === initial ? ' active' : ''}`, entry.label);
        button.type = 'button';
        button.addEventListener('click', () => {
          [...group.children].forEach((candidate) => candidate.classList.toggle('active', candidate === button));
          onChange(entry.value);
        });
        group.append(button);
      });
      return group;
    };
    const table = (values, columns, emptyText, options = {}) => {
      const wrapper = node('div', `table-wrap ${options.wrapperClass || ''}`.trim());
      const element = node('table', 'data-table');
      element.dataset.tableType = options.type || 'generic';
      if (options.mobileCards) element.dataset.mobileCards = 'true';
      const head = node('thead');
      const header = node('tr');
      columns.forEach((column) => {
        const cell = node('th');
        if (column.renderHeader) cell.append(column.renderHeader());
        else cell.textContent = column.label;
        header.append(cell);
      });
      head.append(header);
      const tableBody = node('tbody');
      if (!values.length) {
        const row = node('tr');
        const cell = node('td', 'empty', emptyText);
        cell.colSpan = columns.length;
        row.append(cell);
        tableBody.append(row);
      } else {
        values.forEach((value) => {
          const row = node('tr');
          columns.forEach((column) => {
            const cell = node('td');
            cell.dataset.label = column.fullLabel || column.label || '';
            const rendered = column.render ? column.render(value) : value[column.id];
            cell.append(rendered instanceof Node ? rendered : document.createTextNode(String(rendered ?? '')));
            row.append(cell);
          });
          tableBody.append(row);
        });
      }
      element.append(head, tableBody);
      wrapper.append(element);
      return wrapper;
    };
    let activeModal = null;
    const closeReadOnlyModal = () => {
      activeModal?.remove();
      activeModal = null;
      return true;
    };
    const openReadOnlyModal = (title, modalBody, options = {}) => {
      closeReadOnlyModal();
      const backdrop = node('div', 'modal-backdrop');
      const dialog = node('section', `read-only-modal ${options.className || ''}`);
      dialog.setAttribute('role', 'dialog');
      dialog.setAttribute('aria-label', title);
      const header = node('header', 'modal-header');
      const close = node('button', 'ui-button ui-button-secondary modal-close', 'Close');
      close.type = 'button';
      header.append(node('h2', '', title), close);
      const modalContent = node('div', 'modal-content');
      modalContent.append(modalBody);
      dialog.append(header, modalContent);
      backdrop.append(dialog);
      document.body.append(backdrop);
      activeModal = backdrop;
      close.addEventListener('click', closeReadOnlyModal);
      return {
        dialog, content: modalContent, close: closeReadOnlyModal,
        setBusy(busy) { close.disabled = Boolean(busy); },
        setDirty() {}
      };
    };
    const calls = [];
    window.radioReferenceVisual = { calls };
    const requestJson = async (path, options = {}) => {
      calls.push([path, options]);
      if (path.startsWith('/api/v1/admin/alias-lists')) return { alias_lists: [
        { alias_list_id: 7, name: 'County Public Safety', family: 'P25' },
        { alias_list_id: 8, name: 'Regional Conventional', family: 'ANALOG' }
      ] };
      if (path.endsWith('/countries')) return { items: [{ id: 1, name: 'United States', abbreviation: 'US' }] };
      if (path.endsWith('/bookmarks')) {
        if (options.method === 'PUT') return [options.body];
        return [];
      }
      if (path.includes('/states?')) return { items: [{ id: 39, name: 'Ohio', abbreviation: 'OH' }] };
      if (path.includes('/counties?')) return { items: [{ id: 49, name: 'Franklin County' }] };
      if (path.includes('/browse?')) return { total_items: 2, items: [
        { type: 'TRUNKED_SYSTEM', name: 'Central County P25', secondary: 'Franklin County',
          updated: 'Today', detail: { id: 2001 } },
        { type: 'CONVENTIONAL_AGENCY', name: 'County Fire', secondary: 'Franklin County',
          detail: { id: 3001, kind: 'AGENCY' } }
      ] };
      if (path.includes('/systems/details?')) return { system: {
        id: 2001, name: 'Central County P25', type: 'Project 25 Phase II', city: 'Columbus'
      }, talkgroup_categories: [{ id: 9, name: 'Fire' }] };
      if (path.includes('/systems/sites/catalog?')) return { items: [{ id: 4001, name: 'Central Simulcast',
        number: 1, county_name: 'Franklin County', tdma_control_channel: false, channels: [
          { frequency_hz: 773081250, primary_control: true },
          { frequency_hz: 773331250, alternate_control: true }
        ] }] };
      if (path.includes('/systems/talkgroups/catalog?') &&
          new URLSearchParams(location.search).has('slow')) await new Promise((resolve) => setTimeout(resolve, 500));
      if (path.includes('/systems/talkgroups/catalog?') &&
          new URLSearchParams(location.search).has('large')) return {
        total_items: 10000, categories: [{ id: 9, name: 'Fire' }],
        items: Array.from({ length: 10000 }, (_, index) => ({
          talkgroup: { id: index + 1, value: index + 1, category_id: 9,
            alpha_tag: index === 9999 ? 'Rare Target' : `Talkgroup ${index + 1}`,
            description: 'Fire operations' },
          category: 'Fire', status: index === 9999 ? 'DIFFERENT' : 'IDENTICAL'
        }))
      };
      if (path.includes('/systems/talkgroups/catalog?')) return { total_items: 2, categories: [{ id: 9, name: 'Fire' }],
        items: [
          { talkgroup: { id: 101, value: 101, category_id: 9, alpha_tag: 'Fire Dispatch',
            description: 'Countywide fire' },
            category: 'Fire', status: 'IDENTICAL', existing_alias_id: 700 },
          { talkgroup: { id: 102, value: 102, category_id: 9, alpha_tag: 'Fireground 2',
            description: 'Fireground operations' },
            category: 'Fire', status: 'DIFFERENT', existing_alias_id: 701,
            changes: [{ field: 'name', before: 'Fireground Two', after: 'Fireground 2' }] }
        ] };
      if (path.includes('/conventional/categories?')) return { items: [
        { sub_category_id: 44, category_name: 'Fire', sub_category_name: 'Dispatch' }
      ] };
      if (path.includes('/conventional/frequencies?')) return { total_items: 1, items: [
        { id: 55, downlink_hz: 154430000, alpha_tag: 'Fire Dispatch', description: 'County fire dispatch',
          mode: 'FMN' }
      ] };
      if (path.endsWith('/imports/talkgroups/preview')) return { preview_id: 'tg-preview',
        counts: { added: 0, updated: 1, unchanged: 0 }, rows: [{
          talkgroup: { id: 102, value: 102, alpha_tag: 'Fireground 2' }, status: 'DIFFERENT',
          existing_alias_id: 701,
          changes: [{ field: 'name', before: 'Fireground Two', after: 'Fireground 2' }]
        }] };
      if (path.endsWith('/imports/site/preview')) return { preview_id: 'site-preview', action: 'CREATE',
        detected_modulation: 'CQPSK', channel: { name: options.body.channel_name, protocol_id: 'p25-phase2',
          source: { frequencies_hz: [773081250, 773331250] } } };
      if (path.endsWith('/imports/conventional/preview')) return { preview_id: 'frequency-preview', action: 'CREATE',
        channel: { name: options.body.channel_name, protocol_id: 'nbfm',
          source: { frequencies_hz: [154430000] } } };
      if (path.includes('/imports/') && path.endsWith('/apply')) return { added: 2, updated: 0, alias_list_id: 7 };
      if (path.endsWith('/location')) return { account: { state: 'VALID_PREMIUM' }, country_id: 1,
        state_id: 39, county_id: options.body.countyId || 0 };
      throw new Error(`Unexpected visual-test API path: ${path}`);
    };
    const anchor = (label, target, className = '') => {
      const link = node('a', className, label);
      link.href = target;
      return link;
    };
    const modalFooter = (...controls) => {
      const footer = node('footer', 'alias-modal-footer ui-action-row');
      footer.append(...controls);
      return footer;
    };
    const workspace = createRadioReferenceImportWorkspace({
      node, iconGlyph, formField, uiSelectFrame, uiPill, uiStatus, uiSegmentedControl, table,
      openReadOnlyModal, closeReadOnlyModal, requestJson,
      formatFrequency: (value) => (Number(value) / 1_000_000).toFixed(5),
      formatNumber: (value) => Number(value).toLocaleString('en-US'),
      href: (view, values = {}) => `/?view=${view}&${new URLSearchParams(values)}`,
      anchor, modalFooter
    });
    body.append(workspace.element);
    workspace.setConfiguration({ account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39, county_id: 49 });
  });
  await expect(page.getByRole('button', { name: 'Browse' })).toBeVisible();
}

async function openSystem(page) {
  await page.locator('.radioreference-result-open').first().click();
  await expect(page.getByText('Central Simulcast')).toBeVisible();
}

test('talkgroup selections persist through searches and clear explicitly', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByRole('link', { name: 'Open Alias' }).first())
    .toHaveAttribute('href', /view=aliases.*list=7.*aliasTab=configure.*alias=700/);
  const fire = page.getByRole('checkbox', { name: 'Select Fire Dispatch' });
  await fire.check();
  await expect(page.getByText('1 selected')).toBeVisible();
  await expect(page.getByLabel('Compare with Alias List')).toBeDisabled();
  await page.getByLabel('Filter talkgroup ID, name, or description').fill('fire');
  await expect(page.getByRole('checkbox', { name: 'Select Fire Dispatch' })).toBeChecked();
  await expect(page.getByText('1 selected')).toBeVisible();
  await page.getByRole('button', { name: 'Clear selection' }).click();
  await expect(page.getByText('0 selected')).toBeVisible();
  await expect(page.getByLabel('Compare with Alias List')).toBeEnabled();
});

test('single changed talkgroup preview shows the RadioReference-owned field changes', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await page.getByRole('checkbox', { name: 'Select Fireground 2' }).check();
  await page.getByRole('button', { name: 'Import Selected' }).click();
  const preview = page.getByRole('dialog', { name: 'Import 1 talkgroups' });
  await expect(preview.getByText('RadioReference fields changing')).toBeVisible();
  await expect(preview.getByText('Fireground Two')).toBeVisible();
  await expect(preview.getByText('Fireground 2', { exact: true })).toBeVisible();
});

test('large talkgroup catalogs filter locally without rendering thousands of rows', async ({ page }) => {
  await installWorkspace(page, 'light', true);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByText('10,000 loaded talkgroups', { exact: false })).toBeVisible();
  await expect(page.locator('.radioreference-talkgroup-table tbody tr')).toHaveCount(50);
  await page.getByLabel('Filter talkgroup ID, name, or description').fill('Rare Target');
  await expect(page.getByRole('checkbox', { name: 'Select Rare Target' })).toBeVisible();
  await expect(page.locator('.radioreference-talkgroup-table tbody tr')).toHaveCount(1);
  const calls = await page.evaluate(() => window.radioReferenceVisual.calls
    .filter(([path]) => path.includes('/systems/talkgroups/catalog?')).length);
  expect(calls).toBe(1);
});

test('sites filter instantly from the loaded catalog', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  const search = page.getByLabel('Filter site or channel name');
  await search.fill('missing');
  await expect(page.getByText('No sites match this filter.')).toBeVisible();
  await search.fill('simulcast');
  await expect(page.getByText('Central Simulcast')).toBeVisible();
  const calls = await page.evaluate(() => window.radioReferenceVisual.calls
    .filter(([path]) => path.includes('/systems/sites/catalog?')).length);
  expect(calls).toBe(1);
});

test('slow talkgroup loading shows a spinner until the catalog arrives', async ({ page }) => {
  await installWorkspace(page, 'light', false, true);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.locator('.radioreference-talkgroup-table .ui-feedback-loading')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'Select Fire Dispatch' })).toBeVisible();
});

for (const [name, theme, viewport] of [
  ['radioreference-sites-light-desktop', 'light', { width: 1280, height: 900 }],
  ['radioreference-sites-dark-mobile', 'dark', { width: 390, height: 844 }]
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installWorkspace(page, theme);
    await openSystem(page);
    await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}
