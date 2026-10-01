const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const app = readFileSync(resolve(__dirname, '../../../../..', 'stats-web/assets/app.js'), 'utf8');
const metricHelpers = app.slice(app.indexOf('function number('), app.indexOf('function hex(')) +
  app.slice(app.indexOf('function valueNode('), app.indexOf('function tableColumnKey(')) +
  app.slice(app.indexOf('const METRIC_ICONS ='), app.indexOf('function searchBar('));

async function installWorkspace(page, theme = 'light', large = false, slow = false, scenario = '') {
  await page.goto(`/design-system.html?theme=${theme}&view=gallery${large ? '&large=1' : ''}` +
    `${slow ? '&slow=1' : ''}${scenario ? `&scenario=${scenario}` : ''}`);
  await page.evaluate(async (metricHelpers) => {
    const { createRadioReferenceImportWorkspace } = await import(
      '/assets/features/radioreference-import.js?visual-test=1');
    const tableDefaults = await import('/assets/core/table-defaults.js?visual-test=1');
    document.body.replaceChildren();
    document.documentElement.dataset.theme = new URLSearchParams(location.search).get('theme') || 'light';
    const shell = document.createElement('main');
    shell.className = 'content';
    const heading = document.createElement('header');
    heading.className = 'page-header ui-page-header';
    heading.innerHTML = '<div><h1 class="page-title">RadioReference</h1>' +
      '<div class="page-subtitle">Browse and import channels and talkgroups</div></div>';
    const body = document.createElement('div');
    body.className = 'radioreference-page';
    shell.append(heading, body);
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
    const metricCard = new Function('node', 'iconGlyph', `${metricHelpers}; return metricCard;`)(node, iconGlyph);
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
      options.controller?.layoutMenuCleanup?.();
      const wrapper = node('div', `table-wrap ui-table-wrap ${options.wrapperClass || ''}`.trim());
      const element = node('table', 'data-table resizable-table ui-data-table');
      element.dataset.tableType = options.type || 'generic';
      if (options.mobileCards) element.dataset.mobileCards = 'true';
      const head = node('thead');
      const colgroup = node('colgroup');
      let contentWidth = 0;
      columns.forEach((column) => {
        const col = node('col');
        const width = tableDefaults.width(options.type, column);
        contentWidth += width;
        col.style.width = `${width}px`;
        colgroup.append(col);
      });
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
      element.append(colgroup, head, tableBody);
      element.style.width = `${contentWidth}px`;
      element.style.setProperty('--table-content-min-width', `${contentWidth}px`);
      if (options.layoutMenuHost) {
        const layoutMenu = node('div', 'table-layout-menu');
        const columnsButton = node('button',
          'ui-button ui-button-secondary ui-icon-button ui-icon-button-compact table-layout-trigger');
        columnsButton.type = 'button';
        columnsButton.setAttribute('aria-label', 'Choose table columns');
        columnsButton.append(iconGlyph('icon-columns'));
        layoutMenu.append(columnsButton);
        options.layoutMenuHost.append(layoutMenu);
        if (options.controller) options.controller.layoutMenuCleanup = () => layoutMenu.remove();
      }
      wrapper.append(element);
      return wrapper;
    };
    const modalStack = [];
    const closeReadOnlyModal = () => {
      const active = modalStack.pop();
      active?.backdrop.remove();
      active?.onClose?.();
      return true;
    };
    const openReadOnlyModal = (title, modalBody, options = {}) => {
      if (options.stack !== 'child') while (modalStack.length) closeReadOnlyModal();
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
      const entry = { backdrop, onClose: options.onClose };
      modalStack.push(entry);
      const closeModal = () => {
        const index = modalStack.indexOf(entry);
        if (index < 0) return true;
        modalStack.splice(index, 1);
        backdrop.remove();
        options.onClose?.();
        return true;
      };
      close.addEventListener('click', closeModal);
      return {
        dialog, content: modalContent, close: closeModal,
        setBusy(busy) { close.disabled = Boolean(busy); },
        setDirty() {}
      };
    };
    const calls = [];
    const popupTriggers = [];
    const scenario = new URLSearchParams(location.search).get('scenario');
    const systemPreferences = new Map(scenario === 'saved-preference' ? [[2001, 10]] : []);
    let failPreferenceSave = scenario === 'preference-save-error';
    let preferenceGetCount = 0;
    window.radioReferenceVisual = { calls, popupTriggers, systemPreferences, openReadOnlyModal };
    const dmr = scenario === 'dmr';
    const nxdn = scenario === 'nxdn';
    let nextAliasListId = 100;
    let aliasRevision = 41;
    const createAliasListPopupTrigger = (options) => {
      const record = {
        family: options.family,
        triggerLabel: options.triggerLabel,
        submitLabel: options.submitLabel,
        helperText: options.helperText
      };
      popupTriggers.push(record);
      const host = node('div', 'ui-alias-list-create');
      host.hidden = scenario !== 'alias-create';
      const trigger = node('button', 'ui-button ui-button-secondary', options.triggerLabel);
      trigger.type = 'button';
      trigger.addEventListener('click', async () => {
        const beforeRevision = await options.getRevision();
        const family = String(options.family || '').toUpperCase();
        const aliasList = {
          id: nextAliasListId++, name: family === 'NBFM' ? 'New Analog List' : `New ${family} List`,
          family: family.toLowerCase()
        };
        await options.onCreated({ aliasList, revision: beforeRevision + 1 });
        aliasRevision = beforeRevision + 1;
        record.created = { aliasList, beforeRevision, afterRevision: aliasRevision };
      });
      host.append(trigger);
      return host;
    };
    const requestJson = async (path, options = {}) => {
      calls.push([path, options]);
      if (path.startsWith('/api/v1/admin/alias-lists')) return { revision: aliasRevision, alias_lists: [
        { alias_list_id: 7, name: 'County Public Safety', family: 'P25' },
        { alias_list_id: 10, name: 'Regional P25', family: 'P25' },
        { alias_list_id: 9, name: 'Plant DMR', family: 'DMR' },
        { alias_list_id: 11, name: 'Regional NXDN', family: 'NXDN' },
        { alias_list_id: 8, name: 'Regional Conventional', family: 'NBFM' },
        ...(scenario === 'alias-create' ? [
          { alias_list_id: 12, name: 'Default P25', family: 'P25' },
          { alias_list_id: 13, name: 'Default Analog', family: 'NBFM' }
        ] : [])
      ] };
      if (path.endsWith('/countries')) return { items: scenario === 'country-order' ? [
        { id: 9, name: 'Zimbabwe', abbreviation: 'ZW' },
        { id: 11, name: 'alpha', abbreviation: 'ZZ' },
        { id: 5, name: 'United Kingdom', abbreviation: 'GB' },
        { id: 4, name: 'United Kingdom', abbreviation: 'uk' },
        { id: 1, name: 'United States', abbreviation: 'us' },
        { id: 10, name: 'Alpha', abbreviation: 'XY' },
        { id: 3, name: 'Australia', code: 'AU' },
        { id: 2, name: 'Canada', abbreviation: ' ca ' },
        { id: 8, name: 'Brazil', abbreviation: 'BR' }
      ] : [{ id: 1, name: 'United States', abbreviation: 'US' }] };
      if (path.endsWith('/system-preferences')) {
        if (options.method === 'PUT') {
          if (failPreferenceSave) {
            failPreferenceSave = false;
            throw new Error('Preference service is temporarily unavailable.');
          }
          systemPreferences.set(options.body.system_id, options.body.preferred_alias_list_id);
          return options.body;
        }
        if (options.method === 'DELETE') {
          systemPreferences.set(options.body.system_id, null);
          return { system_id: options.body.system_id, preferred_alias_list_id: null };
        }
        if (scenario === 'slow-initialize' && ++preferenceGetCount === 1) {
          await new Promise((resolve) => { window.radioReferenceVisual.finishPreferenceLoad = resolve; });
          return { items: [{ system_id: 2001, preferred_alias_list_id: 7 }] };
        }
        if (scenario === 'slow-initialize')
          return { items: [{ system_id: 2001, preferred_alias_list_id: 10 }] };
        if (scenario === 'preference-load-error')
          throw new Error('Saved choices unavailable.');
        return { items: [...systemPreferences].map(([system_id, preferred_alias_list_id]) =>
          ({ system_id, preferred_alias_list_id })) };
      }
      if (path.endsWith('/bookmarks')) {
        if (options.method === 'PUT') return [options.body];
        return scenario === 'saved-preference' || scenario === 'preference-load-error' ?
          [{ kind: 'TRUNKED_SYSTEM', id: 2001,
          name: 'Central County P25', parent_name: 'Ohio > Franklin County',
          preferred_alias_list_id: 10 }] : [];
      }
      if (path.includes('/states?')) return { items: [{ id: 39, name: 'Ohio', abbreviation: 'OH' }] };
      if (path.includes('/counties?')) return { items: [{ id: 49, name: 'Franklin County' }] };
      if (path.includes('/browse/catalog?') && scenario === 'directory-loading') {
        await new Promise((resolve) => { window.radioReferenceVisual.finishDirectoryLoad = resolve; });
      }
      if (path.includes('/browse/catalog?') && scenario === 'directory-error') {
        throw new Error('RadioReference directory is temporarily unavailable.');
      }
      if (path.includes('/browse/catalog?')) return [
        { type: 'TRUNKED_SYSTEM', scope: 'COUNTY',
          name: dmr ? 'Ford Plant' : nxdn ? 'Regional NXDN' : 'Central County P25',
          secondary: 'Franklin County', system_type: dmr ? 'DMR' : nxdn ? 'NXDN' : 'Project 25',
          last_updated_epoch_millis: 1700000000000, detail: { id: 2001 } },
        { type: 'CONVENTIONAL_AGENCY', scope: 'COUNTY', name: 'County Fire', secondary: 'Franklin County',
          detail: { id: 3001, kind: 'AGENCY' } },
        ...(scenario === 'directory' ? [
          { type: 'TRUNKED_SYSTEM', scope: 'COUNTY', name: 'Alpha DMR', system_type: 'DMR',
            last_updated_epoch_millis: 1800000000000, detail: { id: 2002 } },
          { type: 'TRUNKED_SYSTEM', scope: 'COUNTY', name: 'Zeta NXDN', system_type: 'NXDN',
            last_updated_epoch_millis: 1600000000000, detail: { id: 2003 } },
          { type: 'TRUNKED_SYSTEM', scope: 'COUNTY', name: 'Unsupported LTR', system_type: 'LTR',
            last_updated_epoch_millis: 1900000000000, detail: { id: 2004 } }
        ] : [])
      ];
      if (path.includes('/systems/details?')) return { system: {
        id: 2001, system_id: '2000', name: dmr ? 'Ford Plant' : nxdn ? 'Regional NXDN' : 'Central County P25',
        type: dmr ? 'DMR' : nxdn ? 'NXDN' : 'Project 25',
        flavor: dmr ? 'Motorola Capacity Plus Single Site (TRBO)' : nxdn ? 'NEXEDGE 9600' : 'Phase II',
        voice: dmr ? 'DMR' : nxdn ? 'NXDN Digital' : 'Digital', city: 'Columbus'
      }, talkgroup_categories: [{ id: 9, name: 'Fire' }] };
      if (path.includes('/systems/sites/catalog?') &&
          new URLSearchParams(location.search).has('slow')) await new Promise((resolve) => setTimeout(resolve, 500));
      if (path.includes('/systems/sites/catalog?') && dmr) return { items: [{
        id: 4001, name: 'Ford Plant Primary', number: 1, county_id: 49, channels: [
          { frequency_hz: 861112500, channel_id: '01' }, { frequency_hz: 861512500, channel_id: '02' },
          { frequency_hz: 861887500, channel_id: '03' }, { frequency_hz: 861712500, channel_id: '05' },
          { frequency_hz: 861962500, channel_id: '06' }
        ] }] };
      if (path.includes('/systems/sites/catalog?')) return { items: [{ id: 4001, name: 'Central Simulcast',
        number: 2, county_id: 49, county_name: 'Franklin County', tdma_control_channel: false, channels: [
          { frequency_hz: 773081250, primary_control: true },
          { frequency_hz: 773331250, alternate_control: true }
        ] }, { id: 4002, name: 'Alpha Site', number: 1, county_id: 50, county_name: 'Outside County', channels: [
          { frequency_hz: 773581250, primary_control: true }
        ] }] };
      if (path.includes('/systems/talkgroups/catalog?') &&
          new URLSearchParams(location.search).has('slow')) await new Promise((resolve) => setTimeout(resolve, 500));
      const rawCatalog = path.includes('/systems/talkgroups/catalog?') &&
        !new URL(path, location.href).searchParams.has('alias_list_id');
      if (path.includes('/systems/talkgroups/catalog?') &&
          new URLSearchParams(location.search).has('large')) return {
        catalog_id: 'loaded-catalog', total_items: 10000, categories: [{ id: 9, name: 'Fire' }],
        items: Array.from({ length: 10000 }, (_, index) => ({
          talkgroup: { id: index + 1, value: index + 1, category_id: 9,
            alpha_tag: index === 9999 ? 'Rare Target' : `Talkgroup ${index + 1}`,
            description: 'Fire operations' },
          category: 'Fire', status: rawCatalog ? 'UNCOMPARED' :
            index === 9999 ? 'DIFFERENT' : 'IDENTICAL'
        }))
      };
      if (path.includes('/systems/talkgroups/catalog?')) return { catalog_id: 'loaded-catalog',
        total_items: 2, categories: [{ id: 9, name: 'Fire' }],
        items: [
          { talkgroup: { id: 101, value: 101, category_id: 9, alpha_tag: 'Fire Dispatch',
            description: 'Countywide fire' },
            category: 'Fire', status: rawCatalog ? 'UNCOMPARED' : 'IDENTICAL', existing_alias_id: 700 },
          { talkgroup: { id: 102, value: 102, category_id: 9, alpha_tag: 'Fireground 2',
            description: 'Fireground operations' },
            category: 'Fire', status: rawCatalog ? 'UNCOMPARED' : 'DIFFERENT', existing_alias_id: 701,
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
        detected_modulation: dmr ? null : 'CQPSK',
        channel: { name: options.body.channel_name, protocol_id: dmr ? 'dmr' : 'p25-phase2',
          source: { frequencies_hz: [773081250, 773331250] } } };
      if (path.endsWith('/imports/conventional/preview')) return { preview_id: 'frequency-preview', action: 'CREATE',
        channel: { name: options.body.channel_name, protocol_id: 'nbfm',
          source: { frequencies_hz: [154430000] } } };
      if (path.endsWith('/imports/site-preview/apply')) return { configuration_id: 'new-channel' };
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
      node, iconGlyph, metricCard, formField, uiSelectFrame, uiPill, uiStatus, uiSegmentedControl, table,
      openReadOnlyModal, closeReadOnlyModal, requestJson, createAliasListPopupTrigger,
      formatFrequency: (value) => (Number(value) / 1_000_000).toFixed(5),
      formatNumber: (value) => Number(value).toLocaleString('en-US'),
      href: (view, values = {}) => `/?view=${view}&${new URLSearchParams(values)}`,
      anchor, modalFooter
    });
    window.radioReferenceVisual.workspace = workspace;
    body.append(workspace.element);
    workspace.setConfiguration({ account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39, county_id: 49 });
  }, metricHelpers);
  if (scenario !== 'slow-initialize')
    await expect(page.getByRole('button', { name: 'Browse' })).toBeVisible();
}

async function openSystem(page) {
  await expect(page.locator('.radioreference-directory-branch').first()).toHaveAttribute('open', '');
  await page.locator('.radioreference-result-open').first().click();
  await expect(page.getByText('Central Simulcast')).toBeVisible();
}

async function installRealTalkgroupAliasCreator(page) {
  await page.evaluate(async () => {
    const { createAliasListPopupTrigger } = await import(
      '/assets/features/alias-list-create.js?visual-layout-test=1');
    const aliasField = document.querySelector('.radioreference-talkgroup-alias-field');
    const existing = aliasField?.querySelector('.ui-alias-list-create');
    const select = aliasField?.querySelector('select');
    if (!aliasField || !existing || !select) throw new Error('Talkgroup Alias List field is unavailable.');
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
    const creator = createAliasListPopupTrigger({
      node, iconGlyph, uiPill,
      openReadOnlyModal: window.radioReferenceVisual.openReadOnlyModal,
      requestJson: async () => ({ alias_list_id: 99, revision: 42 })
    }, {
      select, family: 'P25', getRevision: async () => 41, onCreated: async () => {},
      helperText: 'Creates a compatible Alias List immediately and selects it for this import.'
    });
    existing.replaceWith(creator);
  });
}

test('country picker follows desktop priority, name, and ID order', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'country-order');
  const country = page.getByRole('combobox', { name: 'Country', exact: true });
  await expect(country).toHaveValue('1');
  expect(await country.locator('option').evaluateAll(options => options.map(option => option.value)))
    .toEqual(['1', '2', '3', '4', '5', '10', '11', '8', '9']);
});

test('Alias List creation updates every import picker without losing talkgroup selections', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'alias-create');
  await openSystem(page);

  await page.getByRole('button', { name: 'Central Simulcast' }).click();
  const siteModal = page.getByRole('dialog', { name: /Import Central Simulcast/ });
  await expect(siteModal.getByLabel('Alias List')).toHaveValue('12');
  await expect(siteModal.getByRole('button', { name: 'New list' })).toBeVisible();
  await siteModal.getByRole('button', { name: 'Close' }).click();

  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  const talkgroupList = page.getByLabel('Compare with Alias List');
  await expect(talkgroupList).toHaveValue('12');
  const fire = page.getByRole('checkbox', { name: 'Select Fire Dispatch' });
  await fire.check();
  await page.getByRole('button', { name: 'New list' }).click();
  await expect(talkgroupList).toHaveValue('100');
  await expect(page.getByRole('checkbox', { name: 'Select Fire Dispatch' })).toBeChecked();
  await expect.poll(() => page.evaluate(() => window.radioReferenceVisual.calls.some(([path]) => {
    const url = new URL(path, location.href);
    return url.pathname.endsWith('/systems/talkgroups/catalog') && url.searchParams.get('alias_list_id') === '100';
  }))).toBe(true);

  await page.locator('.radioreference-result-open').filter({ hasText: 'County Fire' }).click();
  await page.locator('.radioreference-conventional-import').click();
  const conventionalModal = page.getByRole('dialog', { name: /Import Fire Dispatch/ });
  const conventionalList = conventionalModal.getByLabel('Alias List');
  await expect(conventionalList).toHaveValue('13');
  await conventionalModal.getByRole('button', { name: 'New list' }).click();
  await expect(conventionalList).toHaveValue('101');
  await conventionalModal.getByRole('button', { name: 'Review Channel' }).click();
  const previewModal = page.getByRole('dialog', { name: /Review Fire Dispatch/ });
  await previewModal.getByRole('button', { name: 'Apply Channel' }).click();

  const result = await page.evaluate(() => {
    const preview = window.radioReferenceVisual.calls.find(
      ([path]) => path.endsWith('/imports/conventional/preview'));
    const apply = window.radioReferenceVisual.calls.find(
      ([path]) => path.endsWith('/imports/frequency-preview/apply'));
    return {
      creators: window.radioReferenceVisual.popupTriggers,
      previewBody: preview?.[1]?.body,
      applyBody: apply?.[1]?.body
    };
  });
  expect(result.previewBody.alias_list_id).toBe(101);
  expect(result.applyBody).toEqual({});
  expect(result.creators.map(({ family, triggerLabel, submitLabel }) =>
    ({ family, triggerLabel, submitLabel }))).toEqual([
    { family: 'P25', triggerLabel: 'New list', submitLabel: 'Create and use' },
    { family: 'P25', triggerLabel: 'New list', submitLabel: 'Create and use' },
    { family: 'NBFM', triggerLabel: 'New list', submitLabel: 'Create and use' }
  ]);
  expect(result.creators.every(({ helperText }) =>
    helperText.includes('immediately') && helperText.includes('selects it'))).toBe(true);
  expect(result.creators.find((creator) => creator.created)?.created).toMatchObject({
    beforeRevision: 41, afterRevision: 42, aliasList: { id: 100, family: 'p25' }
  });
  expect(result.creators.findLast((creator) => creator.created)?.created).toMatchObject({
    beforeRevision: 42, afterRevision: 43, aliasList: { id: 101, family: 'nbfm' }
  });
});

test('talkgroup selections persist through searches and clear explicitly', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByRole('link', { name: 'Open Alias' })).toHaveCount(0);
  const fire = page.getByRole('checkbox', { name: 'Select Fire Dispatch' });
  await fire.check();
  await expect(page.getByText('1 selected')).toBeVisible();
  await expect(page.locator('.radioreference-talkgroup-actions')).toHaveCSS('position', 'sticky');
  await expect(page.getByText('Choose Alias List', { exact: true }).first()).toBeVisible();
  await expect(page.getByLabel('Compare with Alias List')).toBeEnabled();
  await page.getByLabel('Compare with Alias List').selectOption('7');
  await expect(page.getByRole('checkbox', { name: 'Select Fire Dispatch' })).toBeChecked();
  await expect(page.getByLabel('Compare with Alias List')).toBeDisabled();
  await page.getByLabel('Filter talkgroup ID, name, or description').fill('fire');
  await expect(page.getByRole('checkbox', { name: 'Select Fire Dispatch' })).toBeChecked();
  await expect(page.getByText('1 selected')).toBeVisible();
  await page.getByRole('button', { name: 'Clear selection' }).click();
  await expect(page.locator('.radioreference-talkgroup-actions')).toBeHidden();
  await expect(page.getByLabel('Compare with Alias List')).toBeEnabled();
});

test('talkgroup filters and import tools share one compact command row', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  const commandRow = page.locator('.radioreference-talkgroup-command-row');
  const filter = commandRow.locator('.radioreference-status-filter');
  const columns = commandRow.getByRole('button', { name: 'Choose table columns' });
  const importAll = commandRow.getByRole('button', { name: 'Import all system talkgroups' });
  await expect(filter).toBeVisible();
  await expect(columns).toBeVisible();
  await expect(importAll).toBeVisible();
  const [commandBox, filterBox, columnsBox, importBox] = await Promise.all([
    commandRow.boundingBox(), filter.boundingBox(), columns.boundingBox(), importAll.boundingBox()
  ]);
  expect(filterBox.width).toBeLessThan(commandBox.width * .7);
  const columnsCenter = columnsBox.y + (columnsBox.height / 2);
  const importCenter = importBox.y + (importBox.height / 2);
  expect(Math.abs(columnsCenter - importCenter)).toBeLessThanOrEqual(2);
  const [tableBox, tableHostBox] = await Promise.all([
    page.locator('.radioreference-talkgroup-table table').boundingBox(),
    page.locator('.radioreference-talkgroup-table').boundingBox()
  ]);
  expect(tableBox.width).toBeGreaterThanOrEqual(tableHostBox.width - 1);
});

test('talkgroup tools wrap to their pane and the Alias List creator does not distort desktop controls',
  async ({ page }) => {
    await page.setViewportSize({ width: 980, height: 900 });
    await installWorkspace(page);
    await openSystem(page);
    await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
    await installRealTalkgroupAliasCreator(page);

    const toolbar = page.locator('.radioreference-talkgroup-toolbar');
    const aliasField = toolbar.locator('.radioreference-talkgroup-alias-field');
    const categoryField = toolbar.locator('.radioreference-category-field');
    const searchField = toolbar.locator('.radioreference-talkgroup-search-field');
    const before = await Promise.all([
      toolbar.boundingBox(), aliasField.boundingBox(), categoryField.boundingBox(), searchField.boundingBox()
    ]);
    const [toolbarBox, aliasBox, categoryBox, searchBox] = before;
    for (const box of [aliasBox, categoryBox, searchBox]) {
      expect(box.x).toBeGreaterThanOrEqual(toolbarBox.x - 1);
      expect(box.x + box.width).toBeLessThanOrEqual(toolbarBox.x + toolbarBox.width + 1);
    }
    expect(Math.abs(aliasBox.y - categoryBox.y)).toBeLessThanOrEqual(1);
    expect(searchBox.y).toBeGreaterThanOrEqual(aliasBox.y);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

    await toolbar.getByRole('button', { name: 'New list' }).click();
    const popup = page.getByRole('dialog', { name: 'Create Alias List' });
    await expect(popup).toBeVisible();
    const [afterToolbarBox, afterCategoryBox, afterSearchBox, popupBox] = await Promise.all([
      toolbar.boundingBox(), categoryField.boundingBox(), searchField.boundingBox(), popup.boundingBox()
    ]);
    expect(Math.abs(afterToolbarBox.height - toolbarBox.height)).toBeLessThanOrEqual(1);
    expect(Math.abs(afterCategoryBox.y - categoryBox.y)).toBeLessThanOrEqual(1);
    expect(Math.abs(afterSearchBox.y - searchBox.y)).toBeLessThanOrEqual(1);
    expect(popupBox.x).toBeGreaterThanOrEqual(8);
    expect(popupBox.x + popupBox.width).toBeLessThanOrEqual(972);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.locator('body')).toHaveScreenshot('radioreference-talkgroups-alias-create-light-compact.png',
      { fullPage: true });
  });

test('talkgroup tools and the open Alias List creator stay inside a phone viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installWorkspace(page, 'dark');
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await installRealTalkgroupAliasCreator(page);
  const toolbar = page.locator('.radioreference-talkgroup-toolbar');
  const beforeHeight = (await toolbar.boundingBox()).height;
  await toolbar.getByRole('button', { name: 'New list' }).click();
  const popup = page.getByRole('dialog', { name: 'Create Alias List' });
  const [toolbarBox, popupBox] = await Promise.all([toolbar.boundingBox(), popup.boundingBox()]);
  expect(Math.abs(toolbarBox.height - beforeHeight)).toBeLessThanOrEqual(1);
  expect(popupBox.x).toBeGreaterThanOrEqual(0);
  expect(popupBox.x + popupBox.width).toBeLessThanOrEqual(390);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.locator('body')).toHaveScreenshot('radioreference-talkgroups-alias-create-dark-mobile.png',
    { fullPage: true });
});

for (const width of [768, 760]) {
  test(`talkgroup tools stay within the page at the ${width}px workspace boundary`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await installWorkspace(page);
    await openSystem(page);
    await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
    const toolbar = page.locator('.radioreference-talkgroup-toolbar');
    const toolbarBox = await toolbar.boundingBox();
    for (const selector of [
      '.radioreference-talkgroup-alias-field', '.radioreference-category-field',
      '.radioreference-talkgroup-search-field', '.radioreference-talkgroup-command-row'
    ]) {
      const box = await toolbar.locator(selector).boundingBox();
      expect(box.x).toBeGreaterThanOrEqual(toolbarBox.x - 1);
      expect(box.x + box.width).toBeLessThanOrEqual(toolbarBox.x + toolbarBox.width + 1);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

test('single changed talkgroup preview shows the RadioReference-owned field changes', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await page.getByLabel('Compare with Alias List').selectOption('7');
  await page.getByRole('checkbox', { name: 'Select Fireground 2' }).check();
  await page.getByRole('button', { name: 'Import selected' }).click();
  const preview = page.getByRole('dialog', { name: 'Import 1 talkgroup' });
  await expect(preview.getByText('RadioReference fields changing')).toBeVisible();
  await expect(preview.getByText('Fireground Two')).toBeVisible();
  await expect(preview.getByText('Fireground 2', { exact: true })).toBeVisible();
  const previewRequest = await page.evaluate(() => window.radioReferenceVisual.calls.find(
    ([path]) => path.endsWith('/imports/talkgroups/preview')));
  expect(previewRequest[1].body.catalog_id).toBe('loaded-catalog');
});

test('a talkgroup bookmark remembers its preferred Alias List', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await page.getByLabel('Compare with Alias List').selectOption('7');
  await page.locator('.radioreference-import-detail .radioreference-bookmark').first().click();
  const saved = await page.evaluate(() => window.radioReferenceVisual.calls.findLast(
    ([path, options]) => path.endsWith('/bookmarks') && options.method === 'PUT'));
  expect(saved[1].body.preferredAliasListId).toBe(7);
});

test('an unstarred system saves one Alias List choice for talkgroups and site imports', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await page.getByLabel('Compare with Alias List').selectOption('10');
  await expect.poll(() => page.evaluate(() => window.radioReferenceVisual.calls.some(
    ([path, options]) => path.endsWith('/system-preferences') && options.method === 'PUT' &&
      options.body.system_id === 2001 && options.body.preferred_alias_list_id === 10))).toBe(true);
  const importAll = page.getByRole('button', { name: 'Import all system talkgroups' });
  await expect(importAll).toHaveClass(/ui-button-primary/);
  await page.getByRole('checkbox', { name: 'Select Fire Dispatch' }).check();
  await expect(page.getByRole('button', { name: 'Import selected' }))
    .toHaveClass(/ui-button-primary/);
  await page.getByRole('button', { name: 'Sites & Channels' }).click();
  await page.getByRole('button', { name: 'Central Simulcast' }).click();
  const siteModal = page.getByRole('dialog', { name: /Import Central Simulcast/ });
  await expect(siteModal.getByLabel('Alias List')).toHaveValue('10');
  await siteModal.getByRole('button', { name: 'Close' }).click();
  await page.locator('.radioreference-result-open').first().click();
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('10');
  expect(await page.evaluate(() => window.radioReferenceVisual.calls.some(
    ([path, options]) => path.endsWith('/bookmarks') && options.method === 'PUT'))).toBe(false);
});

test('saved system preference restores from Browse and Bookmarks', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'saved-preference');
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('10');
  await page.getByRole('button', { name: /Bookmarks/ }).click();
  await expect(page.locator('.radioreference-directory-item').first())
    .toContainText('Import talkgroups to Regional P25');
  await page.locator('.radioreference-result-open').first().click();
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('10');
});

test('clearing a system preference stays cleared after tab switches and reload', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'saved-preference');
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('10');
  await page.getByLabel('Compare with Alias List').selectOption('');
  await expect.poll(() => page.evaluate(() => window.radioReferenceVisual.calls.some(
    ([path, options]) => path.endsWith('/system-preferences') && options.method === 'DELETE' &&
      options.body.system_id === 2001))).toBe(true);
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('');
  await page.getByRole('button', { name: 'Sites & Channels' }).click();
  await page.getByRole('button', { name: 'Central Simulcast' }).click();
  const siteModal = page.getByRole('dialog', { name: /Import Central Simulcast/ });
  await expect(siteModal.getByLabel('Alias List')).toHaveValue('');
  await siteModal.getByRole('button', { name: 'Close' }).click();
  await page.evaluate(() => window.radioReferenceVisual.workspace.reload());
  await expect(page.getByRole('button', { name: /Bookmarks/ })).toBeVisible();
  await page.getByRole('button', { name: /Bookmarks/ }).click();
  await expect(page.locator('.radioreference-directory-item').first())
    .not.toContainText('Import talkgroups to Regional P25');
  await page.locator('.radioreference-result-open').first().click();
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('');
});

test('late initialization cannot reopen the directory after disconnect', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'slow-initialize');
  await expect.poll(() => page.evaluate(() =>
    typeof window.radioReferenceVisual.finishPreferenceLoad)).toBe('function');
  await page.evaluate(() => {
    window.radioReferenceVisual.workspace.setConfiguration({ account: { state: 'INVALID' } });
    window.radioReferenceVisual.finishPreferenceLoad();
  });
  await expect(page.getByText('Connect RadioReference to import')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Browse' })).toHaveCount(0);
});

test('directory remains available when saved choices cannot load', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'preference-load-error');
  await expect(page.getByText('Saved Alias List choices could not be loaded.', { exact: false }))
    .toBeVisible();
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('10');
});

test('late initialization cannot replace a new account preference', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'slow-initialize');
  await expect.poll(() => page.evaluate(() =>
    typeof window.radioReferenceVisual.finishPreferenceLoad)).toBe('function');
  await page.evaluate(() => window.radioReferenceVisual.workspace.setConfiguration({
    account: { state: 'VALID_PREMIUM', user_name: 'new-account' },
    country_id: 1, state_id: 39, county_id: 49
  }));
  await expect(page.getByRole('button', { name: 'Browse' })).toBeVisible();
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('10');
  await page.evaluate(async () => {
    window.radioReferenceVisual.finishPreferenceLoad();
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('10');
});

test('creating a list from a site saves and shares it before any import is applied', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'alias-create');
  await openSystem(page);
  await page.getByRole('button', { name: 'Central Simulcast' }).click();
  const siteModal = page.getByRole('dialog', { name: /Import Central Simulcast/ });
  await siteModal.getByRole('button', { name: 'New list' }).click();
  await expect(siteModal.getByLabel('Alias List')).toHaveValue('100');
  await expect.poll(() => page.evaluate(() => window.radioReferenceVisual.systemPreferences.get(2001)))
    .toBe(100);
  await siteModal.getByRole('button', { name: 'Close' }).click();
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByLabel('Compare with Alias List')).toHaveValue('100');
  expect(await page.evaluate(() => window.radioReferenceVisual.calls.some(
    ([path]) => path.endsWith('/imports/site/preview')))).toBe(false);
});

test('reviewing a site saves its default Alias List for an unstarred system', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'alias-create');
  await openSystem(page);
  await page.getByRole('button', { name: 'Central Simulcast' }).click();
  const siteModal = page.getByRole('dialog', { name: /Import Central Simulcast/ });
  await expect(siteModal.getByLabel('Alias List')).toHaveValue('12');
  await siteModal.getByRole('button', { name: 'Review Channel' }).click();
  await expect.poll(() => page.evaluate(() => window.radioReferenceVisual.systemPreferences.get(2001)))
    .toBe(12);
  expect(await page.evaluate(() => window.radioReferenceVisual.calls.some(
    ([path, options]) => path.endsWith('/bookmarks') && options.method === 'PUT'))).toBe(false);
});

test('failed preference save remains visible and can be retried', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'preference-save-error');
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await page.getByLabel('Compare with Alias List').selectOption('10');
  await expect(page.getByText('Alias List preference could not be saved:', { exact: false }))
    .toBeVisible();
  await page.getByRole('button', { name: 'Retry saving' }).click();
  await expect.poll(() => page.evaluate(() => window.radioReferenceVisual.systemPreferences.get(2001)))
    .toBe(10);
  await expect(page.getByRole('button', { name: 'Retry saving' })).toHaveCount(0);
});

test('large talkgroup catalogs filter locally without rendering thousands of rows', async ({ page }) => {
  await installWorkspace(page, 'light', true);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByText('10,000 loaded talkgroups', { exact: false })).toBeVisible();
  await expect(page.locator('.radioreference-talkgroup-table tbody tr')).toHaveCount(50);
  await page.getByRole('checkbox', { name: 'Select Talkgroup 1', exact: true }).check();
  await page.evaluate(() => document.documentElement.style.setProperty('--app-header-offset', '64px'));
  await page.evaluate(() => window.scrollTo(0, 900));
  await expect.poll(() => page.locator('.radioreference-talkgroup-actions').evaluate(
    (element) => Math.round(element.getBoundingClientRect().top))).toBe(72);
  await page.getByLabel('Filter talkgroup ID, name, or description').fill('Rare Target');
  await expect(page.getByRole('checkbox', { name: 'Select Rare Target' })).toBeVisible();
  await expect(page.locator('.radioreference-talkgroup-table tbody tr')).toHaveCount(1);
  await expect(page.locator('.radioreference-talkgroup-table tbody tr td[data-label="Talkgroup ID"]'))
    .toHaveText('10000');
  const calls = await page.evaluate(() => window.radioReferenceVisual.calls
    .filter(([path]) => path.includes('/systems/talkgroups/catalog?')).length);
  expect(calls).toBe(1);
});

test('sites filter instantly from the loaded catalog', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  const [listBox, tableBox, pagerBox] = await Promise.all([
    page.locator('.radioreference-sites-list').boundingBox(),
    page.locator('.radioreference-sites-list table').boundingBox(),
    page.locator('.radioreference-sites-list .radioreference-pager').boundingBox()
  ]);
  expect(tableBox.width).toBeGreaterThanOrEqual(listBox.width - 1);
  expect(pagerBox.x).toBeGreaterThan(listBox.x);
  expect(pagerBox.x + pagerBox.width).toBeLessThan(listBox.x + listBox.width);
  const search = page.getByLabel('Filter site or channel name');
  await search.fill('missing');
  await expect(page.getByText('No sites match this filter.')).toBeVisible();
  await search.fill('simulcast');
  await expect(page.getByText('Central Simulcast')).toBeVisible();
  const calls = await page.evaluate(() => window.radioReferenceVisual.calls
    .filter(([path]) => path.includes('/systems/sites/catalog?')).length);
  expect(calls).toBe(1);
});

test('location results show their scope and bookmarks show their route', async ({ page }) => {
  await installWorkspace(page);
  await expect(page.locator('.radioreference-directory-pager')).toHaveCount(0);
  await expect(page.getByText('Franklin County results')).toBeVisible();
  await expect(page.locator('.radioreference-result-open')).toHaveCount(2);
  await expect(page.locator('.radioreference-result-open').first()).toContainText('P25 · Trunked');
  await page.getByRole('button', { name: 'Add bookmark: Central County P25' }).click();
  await page.getByRole('button', { name: /Bookmarks/ }).click();
  await expect(page.getByText('Ohio (OH) > Franklin County')).toBeVisible();
  await expect(page.getByText('Central County P25', { exact: true })).toBeVisible();
});

test('directory sorts supported systems and filters types without another API request', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'directory');
  const entries = page.locator('.radioreference-directory-item .radioreference-row-identity strong');
  await expect(entries).toHaveText(['Alpha DMR', 'Central County P25', 'Zeta NXDN', 'County Fire']);
  await expect(page.getByText('Unsupported LTR')).toHaveCount(0);
  await page.getByLabel('Sort directory results').selectOption('alphabetical');
  await expect(entries).toHaveText(['Alpha DMR', 'Central County P25', 'County Fire', 'Zeta NXDN']);
  await page.getByLabel('Filter system type').selectOption('DMR');
  await expect(entries).toHaveText(['Alpha DMR']);
  await page.getByLabel('Filter system type').selectOption('CONVENTIONAL');
  await expect(entries).toHaveText(['County Fire']);
  const calls = await page.evaluate(() => window.radioReferenceVisual.calls
    .filter(([path]) => path.includes('/browse/catalog?')).length);
  expect(calls).toBe(1);
});

for (const [name, theme, viewport] of [
  ['radioreference-directory-loading-light-desktop', 'light', { width: 1280, height: 900 }],
  ['radioreference-directory-loading-dark-mobile', 'dark', { width: 390, height: 844 }]
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installWorkspace(page, theme, false, false, 'directory-loading');
    const results = page.locator('.radioreference-directory-results');
    const stateHost = results.locator('.radioreference-directory-state');
    await expect(stateHost.locator('.ui-feedback-loading')).toContainText('Loading directory results');
    await expect(stateHost).toHaveCSS('padding', '12px');
    await expect(results).toHaveScreenshot(`${name}.png`);

    await page.evaluate(() => window.radioReferenceVisual.finishDirectoryLoad());
    const list = results.locator('.radioreference-directory-list');
    const tree = list.locator(':scope > .radioreference-directory-tree');
    await expect(tree).toBeVisible();
    await expect(list.locator(':scope > .radioreference-directory-state')).toHaveCount(0);
    const [listBox, treeBox] = await Promise.all([list.boundingBox(), tree.boundingBox()]);
    expect(Math.abs(listBox.x - treeBox.x)).toBeLessThanOrEqual(1);

    await page.getByLabel('Filter system type').selectOption('DMR');
    await expect(list.locator(':scope > .radioreference-directory-state .ui-empty-state')).toBeVisible();
    await expect(list.locator(':scope > .radioreference-directory-state')).toHaveCSS('padding', '12px');
  });
}

test('directory errors use the same inset feedback state', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'directory-error');
  const stateHost = page.locator('.radioreference-directory-list > .radioreference-directory-state');
  await expect(stateHost.locator('.ui-feedback-error')).toContainText('temporarily unavailable');
  await expect(stateHost).toHaveCSS('padding', '12px');
});

test('bookmarks keep breadcrumb and name readable on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installWorkspace(page);
  await page.getByRole('button', { name: 'Add bookmark: Central County P25' }).click();
  await page.getByRole('button', { name: /Bookmarks/ }).click();
  const row = page.locator('.radioreference-directory-item').first();
  await expect(row.locator('.radioreference-bookmark-path')).toHaveText('Ohio (OH) > Franklin County');
  await expect(row.locator('strong')).toHaveText('Central County P25');
  await expect(row.locator('.ui-pill')).toContainText('P25');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('site sorting and preview use the RadioReference database system ID', async ({ page }) => {
  await installWorkspace(page);
  await openSystem(page);
  await expect(page.locator('.radioreference-sites-list tbody tr').first()).toContainText('Central Simulcast');
  await page.getByLabel('Sort sites').selectOption('alphabetical');
  await expect(page.locator('.radioreference-sites-list tbody tr').first()).toContainText('Central Simulcast');
  await page.locator('.radioreference-browse-fields > label:nth-child(3) select').selectOption('');
  await openSystem(page);
  await expect(page.locator('.radioreference-sites-list tbody tr').first()).toContainText('Alpha Site');
  await page.getByRole('button', { name: 'Alpha Site' }).click();
  const importModal = page.getByRole('dialog', { name: /Import Alpha Site/ });
  await expect(importModal.locator('.radioreference-site-facts dt'))
    .toHaveText(['Protocol', 'System type', 'Voice', 'P25 modulation']);
  await expect(importModal.locator('.radioreference-site-facts')).toContainText('Phase II');
  await expect(importModal.locator('.radioreference-site-facts')).toContainText('C4FM');
  const frequencies = importModal.locator('.radioreference-detection .ui-metric');
  await expect(frequencies.locator('.ui-metric-label')).toHaveText('Frequencies');
  await expect(frequencies.locator('.ui-metric-copy > strong')).toHaveText('1');
  await expect(frequencies.locator('.ui-metric-detail')).toHaveText('Available');
  await expect(frequencies).toHaveCSS('padding', '16px');
  await expect(frequencies.locator('.ui-metric-copy > strong')).toHaveCSS('font-size', '29px');
  await expect(importModal.locator('.radioreference-frequency-choices input')).toHaveCount(1);
  await expect(importModal).toHaveScreenshot('radioreference-p25-import-light-desktop.png');
  await importModal.getByLabel('Alias List').selectOption('7');
  await importModal.getByRole('button', { name: 'Review Channel' }).click();
  await expect.poll(async () => page.evaluate(() => window.radioReferenceVisual.calls
    .find(([path]) => path.endsWith('/imports/site/preview'))?.[1]?.body?.system_id)).toBe(2001);
  await page.getByRole('dialog', { name: /Review Central County P25 · Alpha Site/ }).getByRole('button',
    { name: 'Apply Channel' }).click();
  const completed = page.locator('.radioreference-import-complete');
  await expect(completed.locator('.radioreference-completion-icon')).toBeVisible();
  await expect(completed).toContainText('The channel is ready in Channels.');
  const openChannel = completed.getByRole('link', { name: 'Open channel' });
  await expect(openChannel).toHaveClass(/ui-button-primary/);
  await expect(openChannel).toHaveAttribute('href', '/?view=channel-setup&channel=new-channel');
  await expect(page.locator('.radioreference-import-modal-complete'))
    .toHaveScreenshot('radioreference-import-complete-light-desktop.png');
});

test('Capacity Plus defaults to all frequencies without P25-only labels', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'dmr');
  await expect(page.locator('.radioreference-result-open').first()).toContainText('DMR · Trunked');
  await page.locator('.radioreference-result-open').first().click();
  await page.getByRole('button', { name: 'Ford Plant Primary' }).click();
  const modal = page.getByRole('dialog', { name: /Import Ford Plant Primary/ });
  await expect(modal.getByText('Motorola Capacity Plus Single Site (TRBO)')).toBeVisible();
  await expect(modal.getByText('P25 modulation')).toHaveCount(0);
  await expect(modal.locator('.radioreference-detection .ui-metric-copy > strong')).toHaveText('5');
  await expect(modal.locator('.radioreference-frequency-choices input')).toHaveCount(5);
  await expect(modal.locator('input[value="CONTROL_AND_ALTERNATES"]')).toBeDisabled();
  await expect(modal.locator('input[value="ALL"]')).toBeChecked();
  await modal.getByRole('button', { name: 'Review Channel' }).click();
  const request = await page.evaluate(() => window.radioReferenceVisual.calls.find(
    ([path]) => path.endsWith('/imports/site/preview')));
  expect(request[1].body.frequency_mode).toBe('ALL');
});

test('NXDN sites use marked controls without showing P25 modulation', async ({ page }) => {
  await installWorkspace(page, 'light', false, false, 'nxdn');
  await expect(page.locator('.radioreference-result-open').first()).toContainText('NXDN · Trunked');
  await page.locator('.radioreference-result-open').first().click();
  await page.getByRole('button', { name: 'Central Simulcast' }).click();
  const modal = page.getByRole('dialog', { name: /Import Central Simulcast/ });
  await expect(modal.getByText('NEXEDGE 9600')).toBeVisible();
  await expect(modal.getByText('P25 modulation')).toHaveCount(0);
  await expect(modal.locator('input[value="CONTROL_AND_ALTERNATES"]')).toBeChecked();
});

test('Capacity Plus import modal fits on a dark mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installWorkspace(page, 'dark', false, false, 'dmr');
  await page.locator('.radioreference-result-open').first().click();
  await page.getByRole('button', { name: 'Ford Plant Primary' }).click();
  await expect(page.getByRole('dialog', { name: /Import Ford Plant Primary/ }))
    .toHaveScreenshot('radioreference-capacity-plus-import-dark-mobile.png');
});

test('site loading retains the system heading and filter context', async ({ page }) => {
  await installWorkspace(page, 'light', false, true);
  await page.locator('.radioreference-result-open').first().click();
  await expect(page.getByRole('heading', { name: 'Central County P25' })).toBeVisible();
  await expect(page.getByLabel('Filter site or channel name')).toBeDisabled();
  await expect(page.locator('.radioreference-sites-list .ui-feedback-loading')).toContainText(
    'Large systems may take a minute');
  await expect(page.getByLabel('Filter site or channel name')).toBeEnabled();
});

test('slow talkgroup loading shows a spinner until the catalog arrives', async ({ page }) => {
  await installWorkspace(page, 'light', false, true);
  await openSystem(page);
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.locator('.radioreference-talkgroup-table .ui-feedback-loading')).toBeVisible();
  await expect(page.getByLabel('Compare with Alias List')).toBeDisabled();
  await page.getByRole('button', { name: 'Sites & Channels' }).click();
  await page.getByRole('button', { name: 'Talkgroups & Aliases' }).click();
  await expect(page.getByRole('checkbox', { name: 'Select Fire Dispatch' })).toBeVisible();
  const calls = await page.evaluate(() => window.radioReferenceVisual.calls
    .filter(([path]) => path.includes('/systems/talkgroups/catalog?')).length);
  expect(calls).toBe(1);
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

for (const [name, theme, viewport] of [
  ['radioreference-account-light-desktop', 'light', { width: 1280, height: 900 }],
  ['radioreference-account-dark-mobile', 'dark', { width: 390, height: 844 }]
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?theme=${theme}&view=gallery`);
    await page.evaluate(() => {
      document.documentElement.dataset.theme = new URLSearchParams(location.search).get('theme') || 'light';
      document.body.innerHTML = `<main class="content">
        <header class="page-header ui-page-header"><div><h1 class="page-title">RadioReference</h1>
          <p class="page-subtitle">Browse systems and agencies, compare changes, and import</p></div></header>
        <div class="radioreference-page"><div class="radioreference-login-gate ui-surface editor-workspace">
          <span class="radioreference-gate-eyebrow">RadioReference Premium</span>
          <h2>Connect your account</h2>
          <p class="muted">Sign in to browse systems, compare talkgroups, and import channels.</p>
          <form class="admin-form radioreference-account-form editor-workspace">
            <label class="admin-form-field ui-field"><span class="admin-form-label ui-field-label">Username</span>
              <input class="ui-input" value="radio-listener"></label>
            <label class="admin-form-field ui-field"><span class="admin-form-label ui-field-label">Password</span>
              <input class="ui-input" type="password"></label>
            <div class="admin-toggle-control ui-field-row"><span class="admin-toggle-copy">
              <strong>Remember credentials on this receiver</strong>
              <span>Stores the credentials in this receiver’s protected portable settings.</span></span>
              <label class="ui-toggle"><input type="checkbox" checked aria-label="Remember credentials">
              <span class="ui-toggle-track"><span class="ui-toggle-thumb"></span></span>
              <span class="ui-toggle-state">On</span></label></div>
            <div class="admin-form-message ui-notice ui-notice-danger" role="alert">
              RadioReference did not respond before the request deadline.</div>
            <div class="admin-form-actions"><button type="submit" class="ui-button ui-button-primary">
              Connect RadioReference</button></div>
          </form></div></div></main>`;
    });
    const toggle = page.locator('.radioreference-account-form .admin-toggle-copy');
    expect((await toggle.boundingBox()).width).toBeGreaterThan(viewport.width === 390 ? 180 : 360);
    await expect(page.locator('body')).toHaveScreenshot(`${name}.png`, { fullPage: true });
  });
}
