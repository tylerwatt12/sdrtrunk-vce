import * as tableDefaults from '/assets/core/table-defaults.js';
import * as tableLayouts from '/assets/core/table-layout.js';

function initializeGuidanceTable() {
  const panel = document.querySelector('.visual-data-panel');
  const wrapper = panel?.querySelector('.ui-table-wrap');
  const menuHost = panel?.querySelector('.visual-data-layout-host');
  if (!wrapper || !menuHost) return;
  const tableType = 'interface-guidance-channels';
  const storageKey = 'sdrtrunk-vce-interface-guidance-table';
  const columns = [
    { id: 'channel', label: 'Channel', essential: true },
    { id: 'protocol', label: 'Protocol' },
    { id: 'state', label: 'State' }
  ];
  const rows = [
    { channel: 'County Dispatch', protocol: 'P25', state: 'Running' },
    { channel: 'Fireground 2', protocol: 'NBFM', state: 'Stopped' }
  ];
  tableLayouts.registerSchema(new Map(), tableType, columns);
  const defaultLayout = tableDefaults.layout(tableType, columns);
  let saved = null;
  try { saved = JSON.parse(localStorage.getItem(storageKey)); } catch (_error) { saved = null; }
  let layout = tableLayouts.normalize(columns, saved || defaultLayout);
  if (layout.reset) layout = tableLayouts.normalize(columns, defaultLayout);

  const icon = (name) => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#${name}`);
    svg.append(use);
    return svg;
  };
  const store = () => localStorage.setItem(storageKey, JSON.stringify(tableLayouts.persisted(layout)));
  const apply = (next) => {
    layout = tableLayouts.normalize(columns, tableLayouts.persisted(next));
    store();
    render();
  };
  const render = () => {
    const visible = layout.columns;
    const table = document.createElement('table');
    table.className = 'data-table resizable-table ui-data-table ui-data-table-quiet ui-mobile-cards';
    table.dataset.tableType = tableType;
    table.dataset.mobileCards = 'true';
    table.dataset.layoutReady = 'true';
    const colgroup = document.createElement('colgroup');
    const widths = visible.map((column) => layout.column_widths[column.id] ||
      tableDefaults.width(tableType, column));
    const colElements = visible.map((column, index) => {
      const col = document.createElement('col');
      col.dataset.column = column.id;
      col.style.width = `${widths[index]}px`;
      return col;
    });
    colgroup.append(...colElements);
    table.style.setProperty('--table-content-min-width', `${widths.reduce((sum, width) => sum + width, 0)}px`);
    const head = document.createElement('thead');
    const headRow = document.createElement('tr');
    visible.forEach((column, index) => {
      const heading = document.createElement('th');
      heading.dataset.column = column.id;
      const label = document.createElement('span');
      label.className = 'table-column-label';
      label.textContent = column.label;
      const handle = document.createElement('span');
      handle.className = 'column-resizer';
      handle.tabIndex = 0;
      handle.setAttribute('role', 'separator');
      handle.setAttribute('aria-orientation', 'vertical');
      handle.setAttribute('aria-label', `Resize ${column.label} column`);
      handle.setAttribute('aria-valuemin', String(tableLayouts.MINIMUM_WIDTH));
      handle.setAttribute('aria-valuemax', String(tableLayouts.MAXIMUM_WIDTH));
      handle.setAttribute('aria-valuenow', String(widths[index]));
      handle.title = 'Drag to resize.';
      const saveWidth = (width) => apply(tableLayouts.resize(layout, column.id, Math.round(width)));
      handle.addEventListener('keydown', (event) => {
        if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
        event.preventDefault();
        saveWidth(widths[index] + (event.key === 'ArrowRight' ? 12 : -12));
      });
      handle.addEventListener('pointerdown', (event) => {
        event.preventDefault();
        const startX = event.clientX;
        const startWidth = widths[index];
        let nextWidth = startWidth;
        const move = (moveEvent) => {
          nextWidth = Math.max(tableLayouts.MINIMUM_WIDTH,
            Math.min(tableLayouts.MAXIMUM_WIDTH, startWidth + moveEvent.clientX - startX));
          colElements[index].style.width = `${Math.round(nextWidth)}px`;
          handle.setAttribute('aria-valuenow', String(Math.round(nextWidth)));
        };
        const finish = () => {
          handle.removeEventListener('pointermove', move);
          handle.removeEventListener('pointerup', finish);
          handle.removeEventListener('pointercancel', finish);
          saveWidth(nextWidth);
        };
        handle.addEventListener('pointermove', move);
        handle.addEventListener('pointerup', finish);
        handle.addEventListener('pointercancel', finish);
        try { handle.setPointerCapture(event.pointerId); } catch (_error) { finish(); }
      });
      heading.append(label, handle);
      headRow.append(heading);
    });
    head.append(headRow);
    const body = document.createElement('tbody');
    rows.forEach((record) => {
      const row = document.createElement('tr');
      visible.forEach((column) => {
        const cell = document.createElement('td');
        cell.dataset.column = column.id;
        cell.dataset.label = column.label;
        if (column.id === 'channel') {
          const strong = document.createElement('strong');
          strong.textContent = record[column.id];
          cell.append(strong);
        } else if (column.id === 'protocol') {
          const pill = document.createElement('span');
          pill.className = `ui-pill ui-pill-${record.protocol === 'P25' ? 'blue' : 'neutral'}`;
          pill.textContent = record.protocol;
          cell.append(pill);
        } else {
          const status = document.createElement('span');
          status.className = `ui-status ui-status-${record.state === 'Running' ? 'success' : 'neutral'}`;
          status.textContent = record.state;
          cell.append(status);
        }
        row.append(cell);
      });
      body.append(row);
    });
    table.append(colgroup, head, body);
    wrapper.replaceChildren(table);

    menuHost.replaceChildren();
    const chooser = document.createElement('div');
    chooser.className = 'table-layout-menu';
    chooser.dataset.tableType = tableType;
    const trigger = document.createElement('button');
    trigger.className = 'ui-button ui-button-secondary ui-icon-button ui-icon-button-compact table-layout-trigger';
    trigger.type = 'button';
    trigger.setAttribute('aria-label', 'Choose table columns');
    trigger.title = 'Choose table columns';
    const panelId = 'visual-guidance-table-layout';
    trigger.setAttribute('popovertarget', panelId);
    trigger.setAttribute('aria-haspopup', 'dialog');
    trigger.setAttribute('aria-controls', panelId);
    trigger.append(icon('visual-icon-columns'));
    const popover = document.createElement('div');
    popover.className = 'ui-popover table-layout-panel';
    popover.id = panelId;
    popover.setAttribute('popover', 'auto');
    popover.setAttribute('role', 'dialog');
    popover.setAttribute('aria-label', 'Table columns');
    const panelHeader = document.createElement('div');
    panelHeader.className = 'table-layout-panel-header';
    const title = document.createElement('strong');
    title.textContent = 'Table columns';
    const summary = document.createElement('span');
    summary.textContent = 'Show and arrange this table.';
    panelHeader.append(title, summary);
    const options = document.createElement('div');
    options.className = 'table-layout-options';
    const byId = new Map(columns.map((column) => [column.id, column]));
    layout.column_order.forEach((id) => {
      const item = document.createElement('div');
      item.className = 'table-layout-column';
      const visibility = document.createElement('input');
      visibility.className = 'ui-selection-check';
      visibility.type = 'checkbox';
      visibility.checked = !layout.hidden_columns.includes(id);
      visibility.disabled = layout.essential_columns.includes(id) ||
        (visibility.checked && layout.column_order.length - layout.hidden_columns.length <= 1);
      visibility.setAttribute('aria-label', `Show ${byId.get(id).label} column`);
      visibility.addEventListener('change', () => apply(tableLayouts.setHidden(layout, id, !visibility.checked)));
      const label = document.createElement('span');
      label.textContent = byId.get(id).label;
      const position = layout.column_order.indexOf(id);
      const earlier = document.createElement('button');
      earlier.className = 'ui-button ui-button-secondary table-layout-move';
      earlier.type = 'button';
      earlier.textContent = '←';
      earlier.disabled = position === 0;
      earlier.setAttribute('aria-label', `Move ${label.textContent} left`);
      earlier.addEventListener('click', () => apply(tableLayouts.move(layout, id,
        layout.column_order[position - 1])));
      const later = document.createElement('button');
      later.className = 'ui-button ui-button-secondary table-layout-move';
      later.type = 'button';
      later.textContent = '→';
      later.disabled = position === layout.column_order.length - 1;
      later.setAttribute('aria-label', `Move ${label.textContent} right`);
      const after = layout.column_order[position + 2] || null;
      later.addEventListener('click', () => apply(tableLayouts.move(layout, id, after)));
      item.append(visibility, label, earlier, later);
      options.append(item);
    });
    const reset = document.createElement('button');
    reset.className = 'ui-button ui-button-secondary table-layout-reset';
    reset.type = 'button';
    reset.textContent = 'Reset this table';
    reset.addEventListener('click', () => {
      localStorage.removeItem(storageKey);
      layout = tableLayouts.normalize(columns, defaultLayout);
      render();
    });
    const footer = document.createElement('div');
    footer.className = 'table-layout-panel-footer';
    footer.append(reset);
    popover.append(panelHeader, options, footer);
    chooser.append(trigger, popover);
    menuHost.append(chooser);
  };
  render();
}

function initializeScanListCatalog() {
  const catalog = document.querySelector('.visual-admin-scan-lists-example .scan-list-catalog');
  const search = catalog?.querySelector('#admin-scan-list-search');
  const filters = [...(catalog?.querySelectorAll('.admin-scan-list-filter') || [])];
  const cards = [...(catalog?.querySelectorAll('.admin-scan-list-card') || [])];
  const count = catalog?.querySelector('.admin-scan-list-count');
  const empty = catalog?.querySelector('.admin-scan-list-filter-empty');
  if(!search || !filters.length || !cards.length || !count || !empty) return;
  let activeFilter = 'all';
  const draw = () => {
    const term = search.value.trim().toLocaleLowerCase();
    let visible = 0;
    cards.forEach((card) => {
      const stateMatches = activeFilter === 'all' || card.dataset.state === activeFilter;
      const searchMatches = !term || (card.dataset.search || '').toLocaleLowerCase().includes(term);
      card.hidden = !(stateMatches && searchMatches);
      if(!card.hidden) visible += 1;
    });
    count.textContent = visible === cards.length ?
      `${cards.length} scan lists` : `${visible} of ${cards.length} scan lists`;
    empty.hidden = visible !== 0;
  };
  search.addEventListener('input', draw);
  filters.forEach((filter) => filter.addEventListener('click', () => {
    activeFilter = filter.dataset.filter || 'all';
    filters.forEach((candidate) => candidate.setAttribute('aria-pressed',
      String(candidate === filter)));
    draw();
  }));
  draw();
}

const parameters = new URLSearchParams(window.location.search);
const theme = parameters.get('theme') === 'dark' ? 'dark' : 'light';
const view = ['mobile-table', 'gallery', 'app-chrome', 'modal', 'modal-long', 'health-alert-modal', 'focus', 'settings', 'health', 'p25', 'admin-access',
  'admin-receiver', 'admin-support', 'dashboard-health', 'dashboard-calls', 'dashboard-activity',
  'signal-quality-detail', 'radioreference-results',
  'radio-directory-coverage', 'radio-directory-panel', 'admin-scan-lists',
  'scanner', 'tuner-spectrum', 'rf-planner', 'aliases', 'alias-modal', 'alias-export', 'scan-list-members', 'channels',
  'radio-directory', 'entity-details', 'live-notice', 'live-filter', 'tuner-frequency-popover'].includes(parameters.get('view')) ?
  parameters.get('view') : 'gallery';
document.documentElement.dataset.theme = theme;
document.body.dataset.galleryView = view;
if(view === 'gallery') initializeGuidanceTable();
if(view === 'admin-scan-lists') initializeScanListCatalog();
document.querySelectorAll('.radioreference-detail-header').forEach((header) =>
  header.classList.add('ui-surface-header'));
document.querySelectorAll('.ui-table-row-group > th').forEach((heading, index) => {
  const groupRow = heading.parentElement;
  const button = document.createElement('button');
  const groupId = `gallery-group-${index + 1}`;
  button.type = 'button';
  button.className = 'table-row-group-disclosure';
  button.dataset.rowGroup = groupId;
  button.setAttribute('aria-expanded', 'true');
  button.setAttribute('aria-label', `Collapse ${heading.querySelector('.table-row-group-label')?.textContent || 'group'}`);
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  icon.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#visual-icon-chevron');
  icon.append(use);
  button.append(icon, ...heading.childNodes);
  heading.append(button);
  groupRow.dataset.rowGroup = groupId;
  const items = [];
  for(let row = groupRow.nextElementSibling; row && !row.classList.contains('ui-table-row-group');
      row = row.nextElementSibling) {
    row.classList.add('ui-table-row-group-item');
    row.dataset.rowGroup = groupId;
    items.push(row);
  }
  button.addEventListener('click', () => {
    const expanded = button.getAttribute('aria-expanded') === 'true';
    button.setAttribute('aria-expanded', String(!expanded));
    button.setAttribute('aria-label', `${expanded ? 'Expand' : 'Collapse'} ${
      button.querySelector('.table-row-group-label')?.textContent || 'group'}`);
    items.forEach((row) => { row.hidden = expanded; });
  });
});
document.querySelectorAll('.live-filter-expand').forEach((button) => {
  button.addEventListener('click', () => {
    const opening = button.getAttribute('aria-expanded') !== 'true';
    const noun = String(button.getAttribute('aria-label') || '').replace(/^(?:Expand|Collapse)\s+/, '');
    const label = `${opening ? 'Collapse' : 'Expand'} ${noun}`;
    button.setAttribute('aria-expanded', String(opening));
    button.setAttribute('aria-label', label);
    button.title = label;
    const children = document.getElementById(button.getAttribute('aria-controls'));
    if (children) children.hidden = !opening;
  });
});
if(view === 'radio-directory-panel') {
  const example = document.querySelector('.visual-radio-directory-coverage-example');
  const panel = document.createElement('section');
  panel.className = 'section ui-section';
  const title = document.createElement('div');
  title.className = 'section-title ui-section-title';
  title.textContent = 'Radio directory';
  const body = document.createElement('div');
  body.className = 'radio-directory-panel-body';
  body.append(example.querySelector('.radio-directory-perspective'), example.querySelector('.alias-coverage'));
  panel.append(title, body);
  example.replaceChildren(panel);
}
if(view === 'live-notice') {
  document.body.dataset.view = 'live';
  const split = document.querySelector('.visual-live-example .live-split');
  const picker = split?.querySelector('.live-channel-picker');
  const collapse = split?.querySelector('.live-picker-collapse');
  collapse?.addEventListener('click', () => {
    const collapsed = !split.classList.contains('picker-collapsed');
    split.classList.toggle('picker-collapsed', collapsed);
    picker?.classList.toggle('is-collapsed', collapsed);
    collapse.setAttribute('aria-expanded', String(!collapsed));
    const label = collapsed ? 'Expand live view picker' : 'Collapse live view picker';
    collapse.setAttribute('aria-label', label);
    collapse.title = label;
  });
  const workspace = split?.querySelector('.live-right-workspace');
  const resizer = workspace?.querySelector('.live-workspace-resizer');
  const clampDetailsPercent = (value) => Math.max(15, Math.min(65, Math.round(value)));
  let detailsPercent = 25;
  let activeResizeCleanup = null;
  try {
    const saved = Number(localStorage.getItem('details_panel_percent'));
    if(Number.isFinite(saved) && saved > 0) detailsPercent = clampDetailsPercent(saved);
  } catch(_error) {
    detailsPercent = 25;
  }
  const applyDetailsPercent = () => {
    workspace?.style.setProperty('--live-primary-pane-share', `${100 - detailsPercent}fr`);
    workspace?.style.setProperty('--live-details-pane-share', `${detailsPercent}fr`);
    resizer?.setAttribute('aria-valuenow', String(detailsPercent));
    resizer?.setAttribute('aria-valuetext', `${detailsPercent}% details panel`);
  };
  const storeDetailsPercent = () => {
    try { localStorage.setItem('details_panel_percent', String(detailsPercent)); } catch(_error) { /* no-op */ }
  };
  const updateDetailsPercentFromPointer = (clientY) => {
    if(!workspace || !resizer) return;
    const bounds = workspace.getBoundingClientRect();
    const availableHeight = Math.max(1, bounds.height - resizer.getBoundingClientRect().height);
    const primaryHeight = Math.max(0, Math.min(availableHeight, clientY - bounds.top));
    detailsPercent = clampDetailsPercent(100 - primaryHeight / availableHeight * 100);
    applyDetailsPercent();
  };
  resizer?.addEventListener('keydown', (event) => {
    if(!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    if(event.key === 'Home') detailsPercent = 15;
    else if(event.key === 'End') detailsPercent = 65;
    else detailsPercent = clampDetailsPercent(detailsPercent + (event.key === 'ArrowUp' ? 2 : -2));
    applyDetailsPercent();
    storeDetailsPercent();
  });
  resizer?.addEventListener('pointerdown', (event) => {
    if(activeResizeCleanup || event.button !== 0 || window.matchMedia('(max-width: 760px)').matches) return;
    event.preventDefault();
    const pointerId = event.pointerId;
    updateDetailsPercentFromPointer(event.clientY);
    const move = (moveEvent) => {
      if(moveEvent.pointerId !== pointerId) return;
      updateDetailsPercentFromPointer(moveEvent.clientY);
    };
    const finish = (upEvent = null) => {
      if(upEvent?.pointerId !== undefined && upEvent.pointerId !== pointerId) return;
      if(Number.isFinite(upEvent?.clientY)) updateDetailsPercentFromPointer(upEvent.clientY);
      resizer.removeEventListener('pointermove', move);
      resizer.removeEventListener('pointerup', finish);
      resizer.removeEventListener('pointercancel', finish);
      resizer.removeEventListener('lostpointercapture', finish);
      activeResizeCleanup = null;
      storeDetailsPercent();
    };
    activeResizeCleanup = () => finish();
    resizer.addEventListener('pointermove', move);
    resizer.addEventListener('pointerup', finish);
    resizer.addEventListener('pointercancel', finish);
    resizer.addEventListener('lostpointercapture', finish);
    resizer.setPointerCapture(event.pointerId);
  });
  resizer?.addEventListener('dblclick', () => {
    detailsPercent = 25;
    applyDetailsPercent();
    storeDetailsPercent();
  });
  applyDetailsPercent();
  const details = split?.querySelector('.live-details');
  const detailsCollapse = details?.querySelector('.live-details-collapse');
  const detailsCollapseIcon = detailsCollapse?.querySelector('use');
  const setDetailsCollapsed = (collapsed) => {
    details?.classList.toggle('collapsed', collapsed);
    split?.classList.toggle('details-collapsed', collapsed);
    if (detailsCollapse) {
      detailsCollapse.setAttribute('aria-expanded', String(!collapsed));
      const label = collapsed ? 'Expand live details' : 'Collapse live details';
      detailsCollapse.setAttribute('aria-label', label);
      detailsCollapse.title = label;
      detailsCollapseIcon?.setAttribute('href', collapsed ? '#visual-icon-arrow-up' :
        '#visual-icon-arrow-down');
    }
  };
  detailsCollapse?.addEventListener('click', () => setDetailsCollapsed(!details.classList.contains('collapsed')));
  details?.querySelectorAll('.live-details-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      details.querySelectorAll('.live-details-tab').forEach((candidate) => {
        const active = candidate === tab;
        candidate.classList.toggle('active', active);
        candidate.setAttribute('aria-selected', String(active));
        candidate.tabIndex = active ? 0 : -1;
      });
      setDetailsCollapsed(false);
    });
  });
  const detailsPause = details?.querySelector('.live-details-pause');
  const detailsPauseIcon = detailsPause?.querySelector('use');
  detailsPause?.addEventListener('click', () => {
    const paused = detailsPause.getAttribute('aria-pressed') !== 'true';
    detailsPause.setAttribute('aria-pressed', String(paused));
    const label = paused ? 'Resume live details' : 'Pause live details';
    detailsPause.setAttribute('aria-label', label);
    detailsPause.title = label;
    detailsPauseIcon?.setAttribute('href', paused ? '#visual-icon-play' : '#visual-icon-pause');
  });
  if(window.matchMedia('(max-width: 760px)').matches) setDetailsCollapsed(true);
}
if(view === 'scanner') document.body.dataset.view = 'scanner';
if(view === 'entity-details') document.body.dataset.view = 'group-identity';
if(view === 'app-chrome' && window.matchMedia('(max-width: 1180px)').matches) {
  document.querySelector('.visual-app-chrome-example .playback-control-menu').open = false;
}
const label = document.getElementById('visual-theme-label');
if(label) label.textContent = `${theme[0].toUpperCase()}${theme.slice(1)} theme`;
