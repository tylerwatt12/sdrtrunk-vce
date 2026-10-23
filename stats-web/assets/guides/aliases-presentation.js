// The Aliases tour renders fictional data through the same pure controls and
// value renderers as the real workspace. It never opens a live editor, registers
// table preferences, or attaches mutation/network handlers to sample controls.
export function createAliasesGuidePresentation(sample, ui) {
  const {
    node, badge, section, tabs, aliasListRail, aliasListChannelUsageCard,
    configurationColumns, activityColumns, aliasBehavior, aliasEditorModalTabs,
    aliasFormField, aliasTextInput, aliasSelect, aliasScanListChoices,
    aliasDefaultBehaviorPanel
  } = ui;
  const current = sample?.section || 'lists';
  const list = { alias_list_id: 900001, id: 900001, name: 'Example County', family: 'p25',
    alias_count: 4, assigned_channel_count: 1 };
  const scanLists = [
    { id: 900001, name: 'Public Safety', published: true },
    { id: 900002, name: 'Unidentified Traffic', published: false }
  ];
  const options = { scan_lists: scanLists, streams: [{ configuration_id: 'example-local', name: 'Example local feed' }] };
  // Stable fictional dates keep the preview independent from real receiver data.
  const latest = Date.UTC(2026, 0, 15, 14, 32);
  const rows = [
    { alias_id: 910001, name: 'Fire Dispatch', description: 'County fire dispatch', group: 'Fire',
      identifier_display: '1001', logical_call_count: 42, signaling_observation_count: 68,
      last_evidence_ms: latest, record_enabled: 1, scan_lists: ['Public Safety'] },
    { alias_id: 910002, name: 'EMS Dispatch', description: 'County EMS dispatch', group: 'EMS',
      identifier_display: '1002', logical_call_count: 16, signaling_observation_count: 24,
      last_evidence_ms: latest - 180000, record_enabled: 1, scan_lists: ['Public Safety'] },
    { alias_id: 910003, name: 'Public Works', description: 'Road and maintenance crews', group: 'Public Works',
      identifier_display: '2001', logical_call_count: 0, signaling_observation_count: 5,
      last_evidence_ms: latest - 3600000, record_enabled: 0, scan_lists: [] },
    { alias_id: 910004, name: 'Events', description: 'Event talkgroup range', group: 'Events',
      identifier_display: '3000–3099', matcher_label: 'Talkgroup range', logical_call_count: null,
      signaling_observation_count: null, last_evidence_ms: null, record_enabled: 0, scan_lists: [] }
  ].map((row) => ({ alias_list_id: list.id, alias_list_name: list.name, family: 'p25',
    matcher_label: 'Talkgroup', broadcast_channels: [], stream_as_talkgroup: null, ...row }));
  const target = (element, id) => {
    element.dataset.guideTarget = id;
    return element;
  };
  const button = (text, className = 'ui-button ui-button-secondary', id = null) => {
    const element = node('button', className, text);
    element.type = 'button';
    if (id) target(element, id);
    return element;
  };
  const valueNode = value => value instanceof Node ? value : document.createTextNode(String(value ?? ''));
  const choose = (label, checked) => {
    const input = node('input', 'ui-selection-check');
    input.type = 'checkbox';
    input.checked = checked;
    input.setAttribute('aria-label', label);
    return input;
  };
  // This compositor deliberately omits the production table controller: that
  // controller owns persistent layouts and interactive row selection.
  const previewTable = (entries, columns, id, { selected = false, activity = false } = {}) => {
    const wrap = target(node('div', 'table-wrap ui-table-wrap alias-guide-table'), id);
    const table = node('table', 'data-table ui-data-table');
    const head = node('thead');
    const heading = node('tr');
    columns.forEach((column) => {
      const cell = node('th', column.className || '');
      cell.scope = 'col';
      cell.append(column.id === 'select' ? choose('Select aliases on this page', false) : column.label);
      if (column.id === 'select') target(cell, 'aliases-selection');
      if (activity && ['calls', 'signaling', 'last-seen'].includes(column.id)) {
        target(cell, 'aliases-activity-columns');
      }
      heading.append(cell);
    });
    head.append(heading);
    const body = node('tbody');
    entries.forEach((entry, index) => {
      const row = node('tr', selected && index < 2 ? 'selected' : '');
      row.dataset.id = String(entry.alias_id || index);
      columns.forEach((column) => {
        const cell = node('td', column.className || '');
        cell.dataset.label = column.label;
        cell.append(column.id === 'select' ? choose(`Select ${entry.name}`, selected && index < 2) :
          valueNode(column.render ? column.render(entry) : entry[column.id]));
        row.append(cell);
      });
      body.append(row);
    });
    table.append(head, body);
    wrap.append(table);
    return wrap;
  };
  const fragment = document.createDocumentFragment();
  const usage = { canEditChannels: false, error: false,
    channelsByList: new Map([[list.id, [{ id: 'example-site-1', name: 'Example County Site 1' }]]]) };
  const rail = target(aliasListRail([list], list, usage).element, 'aliases-lists');
  target(rail.querySelector('.alias-list-create'), 'aliases-new-list');
  rail.inert = true;
  const isolatedPanel = current === 'alias-editor' || current.startsWith('defaults-');
  const main = node('div', `alias-editor-main${isolatedPanel ? ' alias-guide-preview-main' : ''}`);
  main.inert = true;
  const marker = node('p', 'ui-notice ui-notice-spaced', 'Example data · Nothing in this tour is saved.');
  marker.dataset.guideExample = 'aliases';
  main.append(marker);
  const summary = node('section', 'alias-list-summary ui-surface');
  const summaryCopy = node('div', 'alias-list-summary-copy');
  summaryCopy.append(node('h2', '', list.name), badge('P25', 'state-current'),
    node('span', 'muted alias-list-summary-metrics', '4 aliases · 1 assigned channel'));
  const actions = node('div', 'alias-list-summary-actions');
  actions.append(button('Add Alias', 'ui-button ui-button-primary alias-add-button', 'aliases-add'),
    button('Import aliases…', 'ui-button ui-button-secondary alias-transfer-import-button', 'aliases-import'),
    button('Export aliases…', 'ui-button ui-button-secondary alias-transfer-export-button', 'aliases-export'),
    button('Call Handling Defaults', 'ui-button ui-button-secondary alias-policy-button', 'aliases-defaults'));
  summary.append(summaryCopy, actions);
  const channelUsage = target(aliasListChannelUsageCard(list, usage).element, 'aliases-usage');
  main.append(summary, channelUsage);

  const view = ['activity', 'custom', 'discover'].includes(current) ? current : 'configure';
  const navigation = node('div', 'alias-editor-view-switcher');
  const viewTabs = tabs(['configure', 'activity', 'custom'].map(id => ({ id,
    label: id[0].toUpperCase() + id.slice(1), href: '#'})), view);
  viewTabs.classList.add('alias-editor-view-tabs');
  [...viewTabs.children].forEach((element, index) => target(element,
    ['aliases-configure', 'aliases-activity', 'aliases-custom'][index]));
  navigation.append(viewTabs, button('Discover', 'ui-button ui-button-secondary alias-discover-link', 'aliases-discover'));
  main.append(navigation);

  const filterToolbar = target(node('form',
    'toolbar ui-toolbar ui-catalog-toolbar alias-catalog-toolbar alias-editor-filter-toolbar data-workspace'), 'aliases-filters');
  const search = aliasTextInput('guideAliasSearch', '', 'search');
  search.placeholder = view === 'discover' ? 'Group, system, or channel' : 'Alias, description, group, or identifier';
  const searchField = aliasFormField('Search', search);
  searchField.classList.add('alias-filter', 'alias-search-filter');
  const primaryFilters = node('div', 'alias-filter-primary');
  primaryFilters.append(searchField, button('Search', 'ui-button ui-button-primary'), button('Filters'));
  filterToolbar.append(primaryFilters);
  main.append(filterToolbar);

  const configuredTable = (tableView = 'configure', selected = false) => {
    const selection = { id: 'select', label: '', className: 'alias-select-cell' };
    const base = configurationColumns().filter(column =>
      !['matcher', 'description'].includes(column.id) || tableView === 'configure');
    const columns = [selection, ...base, ...(tableView === 'configure' ?
      [{ id: 'behavior', label: 'Behavior', render: aliasBehavior }] : activityColumns()),
      ...(tableView === 'custom' ? [
        { id: 'scan-lists', label: 'Scan Lists', render: row => row.scan_lists.join(', ') || '—' },
        { id: 'record', label: 'Record', render: row => row.record_enabled ? 'Yes' : 'No' }
      ] : [])];
    const tools = node('div', 'section-title-actions ui-section-actions');
    tools.append(button('Select All Matching', 'ui-button ui-button-secondary alias-select-all', 'aliases-select-all'),
      button('Columns', 'ui-button ui-button-secondary', 'aliases-columns'));
    const block = section(tableView === 'configure' ? 'Alias Configuration' : tableView === 'activity' ? 'Activity' :
      'Custom View', previewTable(rows, columns, 'aliases-table', { selected, activity: tableView === 'activity' }), tools);
    block.classList.add('alias-editor-table-section');
    block.append(node('p', 'ui-section-note', tableView === 'configure' ?
      'Configuration controls what the alias matches and what happens to its calls.' :
      'Calls and signaling can overlap. An em dash means unavailable; 0 means monitored with none observed.'),
    node('p', 'ui-section-note', 'Aliases 1–4 of 4'));
    return block;
  };

  const previewPanel = (title, child) => {
    const body = node('div', 'alias-guide-preview-body');
    body.append(child);
    const panel = section(title, body);
    panel.classList.add('alias-guide-preview');
    main.replaceChildren(marker, panel);
  };
  if (current === 'alias-editor') {
    const basics = node('section', 'alias-editor-panel ui-form-section');
    const grid = node('div', 'alias-editor-grid');
    grid.append(aliasFormField('Alias list', aliasSelect('guideList', [{ value: list.id, label: list.name }], list.id)),
      aliasFormField('Alias name', aliasTextInput('guideName', 'Fire Dispatch')),
      aliasFormField('Group', aliasTextInput('guideGroup', 'Fire')),
      aliasFormField('Description', aliasTextInput('guideDescription', 'County fire dispatch')));
    basics.append(grid);
    const panels = { basics, identifier: node('section'), audio: node('section'), usage: node('section') };
    const form = node('form', 'alias-editor-form editor-workspace');
    form.append(target(aliasEditorModalTabs(panels), 'aliases-editor-tabs'), ...Object.values(panels));
    const footer = node('footer', 'ui-modal-footer ui-action-row');
    footer.append(button('Cancel'), button('Save Changes', 'ui-button ui-button-primary'));
    form.append(footer);
    previewPanel('Edit Alias · Fire Dispatch', form);
  } else if (current.startsWith('defaults-')) {
    const unmatched = current === 'defaults-unmatched';
    const defaultsTabs = node('nav', 'alias-modal-tabs alias-defaults-tabs ui-segmented');
    defaultsTabs.setAttribute('role', 'tablist');
    defaultsTabs.setAttribute('aria-label', 'Alias List Default behaviors');
    ['Unmatched Calls', 'New Aliases'].forEach((label, index) => {
      const active = unmatched ? index === 0 : index === 1;
      const tab = button(label, `ui-segmented-option${active ? ' active' : ''}`);
      tab.setAttribute('role', 'tab');
      tab.setAttribute('aria-selected', String(active));
      defaultsTabs.append(tab);
    });
    target(defaultsTabs, 'aliases-defaults-tabs');
    const warning = unmatched ? node('div', 'ui-notice ui-notice-danger ui-notice-spaced',
      'Warning: These settings act as a catch-all and can play, record, or stream traffic that has not been ' +
      'individually reviewed, including sensitive traffic. If a selected streaming destination sends to ' +
      'Broadcastify or another third-party provider, leave catch-all Streaming disabled and configure approved ' +
      'talkgroups individually.') : null;
    const behavior = aliasDefaultBehaviorPanel(options, unmatched ? 'unknown' : 'newAlias',
      { recordable: true, scan_list_ids: [unmatched ? 900002 : 900001], broadcast_configuration_ids: [] },
      unmatched ? 'Applies when a destination talkgroup or patch group has no exact Alias or covering talkgroup range in this list.' :
        'Applies only when a new Alias is manually created or imported from RadioReference. Changes here do not alter ' +
          'existing Aliases, and RadioReference updates preserve each existing Alias’s local handling settings.',
      unmatched ? 'Records completed calls whose talkgroup identity is not covered by an Alias.' :
        'New Aliases begin with recording enabled or disabled as selected here.',
      unmatched ? 'Routes unmatched calls to the selected Scan Lists for browser playback.' :
        'New Aliases begin in the selected scan lists.',
      unmatched ? 'Sends unknown calls to the selected external streaming destinations.' :
        'New Aliases begin with the selected external streaming destinations.', warning);
    behavior.panel.classList.add('alias-guide-defaults-panel');
    // The guide card explains this dependency. Keep the real setting shapes and
    // catch-all warning visible without repeating their supporting prose.
    behavior.panel.querySelectorAll(':scope > p, fieldset > p').forEach(copy => copy.remove());
    behavior.panel.querySelector('.alias-record-toggle .ui-toggle-copy small')?.remove();
    target(behavior.panel.querySelector('.alias-defaults-section'), 'aliases-default-record');
    target(behavior.panel.querySelector('.alias-scan-list-options'), 'aliases-default-scan');
    target(behavior.streams, 'aliases-default-stream');
    behavior.streams.classList.add('alias-guide-default-streaming');
    warning?.classList.add('alias-guide-default-warning');
    const form = node('form', 'alias-editor-form alias-policy-form editor-workspace');
    form.append(defaultsTabs, behavior.panel);
    const footer = node('footer', 'ui-modal-footer ui-action-row');
    footer.append(button('Cancel'), button('Save Call Handling Defaults', 'ui-button ui-button-primary'));
    form.append(footer);
    previewPanel('Call Handling Defaults · Example County', form);
  } else if (current === 'discover') {
    const observed = [
      { identity: '2501', coverage: 'No alias coverage', calls: 8, signaling: 13 },
      { identity: '3005', coverage: 'Covered by range · Events (3000–3099)', calls: 3, signaling: 7 }
    ];
    const columns = [
      { id: 'identity', label: 'Identity', render: entry => {
        const value = node('div', 'alias-guide-identity');
        value.append(node('strong', '', `Talkgroup ${entry.identity}`),
          node('small', 'muted', entry.coverage));
        return value;
      } },
      { id: 'source', label: 'System / Channel', render: () => 'Example County Site 1' },
      { id: 'calls', label: 'Calls' }, { id: 'signaling', label: 'Signaling' },
      { id: 'action', label: '', render: () => button('Create Alias',
        'ui-button ui-button-secondary observed-group-identity-create') }
    ];
    const block = section('Observed Groups', previewTable(observed, columns, 'aliases-discover-table'));
    block.classList.add('alias-editor-table-section', 'observed-group-identity-section');
    block.append(node('p', 'ui-section-note',
      'Talkgroups and patch groups observed on assigned channels without an exact alias. Existing range coverage appears beneath the identity.'));
    main.append(block);
  } else {
    if (current === 'bulk') {
      const bulk = target(node('div', 'alias-bulk-bar ui-selection-bar'), 'aliases-bulk');
      bulk.append(node('strong', 'alias-bulk-count', '2 selected'));
      ['Move', 'Group', 'Scan Lists', 'Record', 'Stream', 'Appearance', 'Delete'].forEach(label =>
        bulk.append(button(label, label === 'Delete' ? 'ui-button ui-button-danger-quiet' : 'ui-button ui-button-secondary')));
      bulk.append(button('Clear selection', 'ui-button ui-button-secondary alias-bulk-clear'));
      main.append(bulk);
    }
    if (current === 'listening') {
      const membership = target(aliasScanListChoices(options, [900001]), 'aliases-memberships');
      const body = node('div', 'alias-guide-preview-body');
      body.append(node('p', 'muted', 'Fire Dispatch · Call Handling'), membership);
      main.append(section('Scan List membership', body));
    }
    main.append(configuredTable(view, current === 'bulk'));
    if (current === 'custom') {
      const choices = node('fieldset', 'ui-form-section alias-guide-column-choices');
      choices.append(node('legend', '', 'Visible columns'));
      const columnLabels = ['Alias', 'Group', 'Identifier', 'Calls', 'Signaling', 'Last Seen', 'Scan Lists', 'Record'];
      columnLabels.forEach((label, index) => {
        const choice = node('div', 'table-layout-column');
        const earlier = button('←', 'ui-button ui-button-secondary table-layout-move');
        earlier.setAttribute('aria-label', `Move ${label} left`);
        earlier.disabled = index === 0;
        const later = button('→', 'ui-button ui-button-secondary table-layout-move');
        later.setAttribute('aria-label', `Move ${label} right`);
        later.disabled = index === columnLabels.length - 1;
        choice.append(choose(`Show ${label} column`, true), node('span', '', label), earlier, later);
        choices.append(choice);
      });
      const body = node('div', 'alias-guide-preview-body');
      body.append(choices, node('p', 'ui-field-detail', 'Use the arrows to arrange columns in the order you prefer.'));
      const layout = target(section('Columns', body), 'aliases-columns');
      // Show the opened layout controls without invoking the persistent table menu.
      main.querySelector('[data-guide-target="aliases-columns"]').removeAttribute('data-guide-target');
      main.insertBefore(layout, main.querySelector('.alias-editor-table-section'));
    }
  }
  if (!isolatedPanel) fragment.append(rail);
  fragment.append(main);
  return fragment;
}
