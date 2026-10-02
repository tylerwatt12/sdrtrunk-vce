import { applyThemeHue } from '/assets/core/theme.js?v=1';
import * as tableDefaults from '/assets/core/table-defaults.js';
import * as tableLayouts from '/assets/core/table-layout.js';
import { createDualRange } from '/assets/core/dual-range.js';
import { mountAudioDockGallery } from '/visual/audio-dock-gallery.js';
import { createFormWorkflow } from '/assets/core/form-workflows.js';
import { createBrowsingPager, createFilterDisclosure, pageRangeText, scanListAvailabilityPill } from '/assets/core/browsing-workflows.js';
import { createGalleryModalFoundation } from '/visual/modal-foundation-gallery.js';

async function initializeAliasFilterExamples() {
  const forms = [...document.querySelectorAll('.visual-aliases-example .alias-editor-filter-toolbar, ' +
    '.visual-scan-list-members-example .alias-editor-filter-toolbar')];
  if (!forms.length) return;
  const { openReadOnlyModal } = await createGalleryModalFoundation();
  const node = (tag, className = '', text = null) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== null) element.textContent = String(text);
    return element;
  };
  for (const form of forms) {
    const panel = form.querySelector('.alias-filter-advanced');
    const button = form.querySelector('.alias-filter-advanced-toggle');
    const clear = node('button', 'ui-button ui-button-secondary', 'Clear filters');
    clear.type = 'button';
    form.querySelector('.alias-filter-primary').append(clear);
    const inlineActions = form.querySelector('.alias-filter-actions');
    inlineActions.classList.add('ui-action-row', 'ui-filter-inline-actions');
    inlineActions.querySelector('button').type = 'submit';
    const fields = [...panel.querySelectorAll('input,select')];
    const defaults = fields.map(field => field.value);
    const updateCount = () => {
      const count = fields.filter((field, index) => field.value !== defaults[index]).length;
      button.textContent = count ? `Filters (${count})` : 'Filters';
      clear.hidden = count === 0;
    };
    fields.forEach(field => { field.addEventListener('input', updateCount); field.addEventListener('change', updateCount); });
    clear.addEventListener('click', () => { form.reset(); updateCount(); });
    form.addEventListener('submit', event => { event.preventDefault(); updateCount(); });
    form.querySelector('.alias-filter-primary > .ui-button-primary').addEventListener('click', () => form.requestSubmit());
    button.id = `${panel.id}-toggle`;
    createFilterDisclosure({ node, openReadOnlyModal, form, panel, button, clearAction: clear,
      id: panel.id, returnFocusSelector: `#${button.id}` });
    button.disabled = false;
    updateCount();
    form.dataset.sharedFiltersReady = 'true';
  }
}

void initializeAliasFilterExamples();

document.querySelectorAll('.visual-duration-range').forEach((host) => {
  const node = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text) element.textContent = text;
    return element;
  };
  const disabled = host.dataset.disabled === 'true';
  const control = createDualRange({ node, label: disabled ? 'Call length (disabled)' : 'Call length',
    min: 0, max: 120, step: 0.5, lower: disabled ? 10 : 0, upper: disabled ? 60 : 120,
    disabled, format: (value, endpoint) => endpoint === 'upper' && value === 120 ?
      'Any length' : `${value} sec` });
  host.append(control.field);
});

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
  const selectors = [...(catalog?.querySelectorAll('.scan-list-selector') || [])];
  const count = catalog?.querySelector('.admin-scan-list-count');
  const empty = catalog?.querySelector('.admin-scan-list-filter-empty');
  const detailHost = catalog?.querySelector('.scan-list-detail-host');
  const detail = catalog?.querySelector('.scan-list-detail');
  if(!search || !filters.length || !selectors.length || !count || !empty || !detailHost || !detail) return;
  const detailTitle = detail.querySelector('.scan-list-detail-title');
  const badges = detail.querySelector('.scan-list-detail-badges');
  const description = detail.querySelector('.scan-list-detail-description');
  const aliasCount = detail.querySelector('.scan-list-detail-alias-count');
  const routeCount = detail.querySelector('.scan-list-detail-route-count');
  const routeLinks = detail.querySelector('.scan-list-detail-route-links');
  const manageLink = detail.querySelector('.admin-scan-list-members');
  const editButton = detail.querySelector('.admin-scan-list-edit');
  const deleteButton = detail.querySelector('.admin-scan-list-delete');
  let activeFilter = 'all';
  let selectedId = 'county';
  const drawDetail = (selector) => {
    if(!selector) {
      detailHost.hidden = true;
      return;
    }
    detailHost.hidden = false;
    const name = selector.dataset.scanListName || '';
    const aliases = Number(selector.dataset.aliases || 0);
    const routes = (selector.dataset.routeNames || '').split('|').filter(Boolean);
    const isDefault = selector.dataset.default === 'true';
    detailTitle.textContent = name;
    badges.replaceChildren();
    if(isDefault) {
      const badge = document.createElement('span');
      badge.className = 'ui-pill state-current';
      badge.textContent = 'Default';
      badges.append(badge);
    }
    const status = document.createElement('span');
    status.className = selector.dataset.state === 'hidden' ? 'ui-pill state-stale' : 'ui-pill';
    status.textContent = selector.dataset.state === 'hidden' ? 'Hidden from listeners' : 'Available to listeners';
    badges.append(status);
    description.textContent = selector.dataset.description || '';
    description.hidden = !description.textContent;
    aliasCount.firstChild.textContent = String(aliases);
    routeCount.firstChild.textContent = String(routes.length);
    routeLinks.replaceChildren();
    if(routes.length) {
      routes.forEach((name) => {
        const link = document.createElement('a');
        link.className = 'badge ui-pill';
        link.href = '#';
        link.textContent = name;
        routeLinks.append(link);
      });
    } else {
      const none = document.createElement('span');
      none.className = 'muted';
      none.textContent = 'No Alias Lists';
      routeLinks.append(none);
    }
    manageLink.setAttribute('aria-label', `Manage ${aliases} aliases for ${name}`);
    editButton.setAttribute('aria-label', `Edit ${name} details`);
    deleteButton.setAttribute('aria-label', `Delete ${name}`);
    if(isDefault) {
      deleteButton.setAttribute('aria-disabled', 'true');
      deleteButton.title = 'Choose another default scan list before deleting this one';
    } else {
      deleteButton.removeAttribute('aria-disabled');
      deleteButton.removeAttribute('title');
    }
  };
  const draw = () => {
    const term = search.value.trim().toLocaleLowerCase();
    const visible = selectors.filter((selector) => {
      const stateMatches = activeFilter === 'all' || selector.dataset.state === activeFilter;
      const searchMatches = !term || (selector.dataset.search || '').toLocaleLowerCase().includes(term);
      selector.hidden = !(stateMatches && searchMatches);
      return !selector.hidden;
    });
    if(!visible.some((selector) => selector.dataset.scanListId === selectedId)) {
      selectedId = visible[0]?.dataset.scanListId || null;
    }
    selectors.forEach((selector) => {
      const selected = !selector.hidden && selector.dataset.scanListId === selectedId;
      selector.setAttribute('aria-pressed', String(selected));
      selector.tabIndex = selected ? 0 : -1;
    });
    count.textContent = visible.length === selectors.length ?
      `${selectors.length} scan lists` : `${visible.length} of ${selectors.length} scan lists`;
    empty.hidden = visible.length !== 0;
    drawDetail(visible.find((selector) => selector.dataset.scanListId === selectedId));
  };
  search.addEventListener('input', draw);
  filters.forEach((filter) => filter.addEventListener('click', () => {
    activeFilter = filter.dataset.filter || 'all';
    filters.forEach((candidate) => candidate.setAttribute('aria-pressed',
      String(candidate === filter)));
    draw();
  }));
  selectors.forEach((selector) => selector.addEventListener('click', () => {
    selectedId = selector.dataset.scanListId;
    draw();
  }));
  selectors.forEach((selector) => selector.addEventListener('keydown', (event) => {
    if(!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
    const visible = selectors.filter((candidate) => !candidate.hidden);
    const position = visible.indexOf(selector);
    if(position < 0) return;
    const target = event.key === 'Home' ? visible[0] : event.key === 'End' ? visible.at(-1) :
      visible[(position + (event.key === 'ArrowDown' ? 1 : -1) + visible.length) % visible.length];
    event.preventDefault();
    selectedId = target.dataset.scanListId;
    draw();
    target.focus();
  }));
  draw();
}

const parameters = new URLSearchParams(window.location.search);
const theme = parameters.get('theme') === 'dark' ? 'dark' : 'light';
const view = ['control-states', 'mobile-table', 'gallery', 'workflows', 'app-chrome', 'audio-dock', 'access-landing', 'access-login-modal', 'modal', 'modal-long', 'alias-list-create-modal', 'activity-action-tooltip', 'activity-filters', 'health-alert-modal', 'focus', 'settings', 'health', 'status-primitives', 'p25', 'admin-access',
  'admin-navigation', 'admin-receiver', 'admin-support', 'dashboard-health', 'dashboard-calls', 'dashboard-activity',
  'signal-quality-detail', 'radioreference-results',
  'radio-directory-coverage', 'radio-directory-panel', 'admin-scan-lists',
  'scanner', 'tuner-spectrum', 'tuners', 'aliases', 'alias-modal', 'alias-export', 'scan-list-members', 'channels',
  'radio-directory', 'entity-details', 'live-notice', 'live-filter', 'tuner-frequency-popover',
  'spectrum-discovery', 'spectrum-search'].includes(parameters.get('view')) ?
  parameters.get('view') : 'gallery';
document.documentElement.dataset.theme = theme;
const requestedHue = parameters.get('hue');
applyThemeHue(requestedHue === null ? null : Number(requestedHue));
document.body.dataset.galleryView = view;

document.querySelectorAll('[data-visual-disclosure]').forEach((toggle) => {
  const panel = document.getElementById(toggle.getAttribute('aria-controls'));
  toggle.addEventListener('click', () => {
    const expanded = toggle.getAttribute('aria-expanded') !== 'true';
    toggle.setAttribute('aria-expanded', String(expanded));
    panel.hidden = !expanded;
    toggle.closest('.receiver-health-section')?.classList.toggle('collapsed', !expanded);
  });
});

function initializeCompactRecords() {
  const node = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  };
  const fields = {
    talkgroup: ['Talkgroup', 'talkgroup'], radio: ['Radio', 'radio'], site: ['Selected site', 'site'],
    action: ['Compare', 'action'], time: ['Matched at', 'time'], copies: ['Copies', 'copies'],
    match: ['Why matched / selected', 'match'], outputs: ['Used for', 'outputs']
  };
  const rows = [
    { talkgroup: 'County Fire Dispatch', talkgroupId: '1201', radio: 'Engine 12', radioId: '1849156',
      site: 'North County Simulcast', siteId: 'RFSS 1 · Site 2', time: 'Today · 10:49:52', copies: '4',
      match: 'Matching voice frames', selected: 'Lowest FER voice copy', outputs: ['Stream', 'Browser audio'] },
    { talkgroup: 'Regional mutual aid and incident command coordination', talkgroupId: '2407',
      radio: 'Unknown radio', radioId: '410282', site: 'Lake County', siteId: 'RFSS 1 · Site 3',
      time: 'Today · 10:49:48', copies: '3', match: 'Matching encryption identity',
      selected: 'No measurable difference', outputs: ['Record'] }
  ];
  document.querySelectorAll('[data-visual-compact-records]').forEach((host) => {
    const custom = host.dataset.visualCompactRecords === 'custom';
    const order = custom ? ['site', 'time', 'outputs', 'copies', 'talkgroup', 'match', 'action', 'radio'] :
      ['talkgroup', 'radio', 'site', 'action', 'time', 'copies', 'match', 'outputs'];
    const table = node('table', `ui-data-table ui-record-list ui-record-list-compact call-matching-history-list${
      custom ? ' ui-record-list-custom-order has-custom-field-order' : ''}`);
    table.dataset.tableType = 'call-matching-history';
    const head = node('thead');
    const headings = node('tr');
    order.forEach((id) => headings.append(node('th', '', fields[id][0])));
    head.append(headings);
    const body = node('tbody');
    rows.forEach((record) => {
      const row = node('tr');
      order.forEach((id) => {
        const cell = node('td', `call-matching-field-${fields[id][1]}${id === 'talkgroup' ? ' ui-record-title' : ''}${
          id === 'action' ? ' ui-record-action' : ''}`);
        cell.dataset.column = id;
        cell.dataset.label = fields[id][0];
        if (['talkgroup', 'radio', 'site'].includes(id)) {
          const identity = node('div', 'identity-summary');
          const primary = node(id === 'site' ? 'strong' : 'a', 'identity-summary-primary', record[id]);
          if (primary.tagName === 'A') primary.href = `#${id}-${record[`${id}Id`]}`;
          identity.append(primary, node('small', 'identity-summary-secondary', record[`${id}Id`]));
          cell.append(identity);
        } else if (id === 'action') {
          const compare = node('button', 'ui-button ui-button-secondary', 'Compare');
          compare.type = 'button';
          compare.setAttribute('aria-label', `Compare copies of ${record.talkgroup}`);
          cell.append(compare);
        } else if (id === 'match') {
          cell.append(node('strong', '', record.match), node('div', 'ui-muted', record.selected));
        } else if (id === 'outputs') {
          const outputs = node('div', 'call-matching-output-tags');
          record.outputs.forEach((output) => outputs.append(node('span', 'ui-pill', output)));
          cell.append(outputs);
        } else cell.textContent = record[id];
        row.append(cell);
      });
      body.append(row);
    });
    table.append(head, body);
    host.append(table);
  });
}
initializeCompactRecords();

function initializeReceiverHealth() {
  const host = document.querySelector('[data-visual-receiver-health]');
  if (!host) return;
  const node = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  };
  const status = (label, tone = 'neutral') => node('span', `ui-status ui-status-${tone}`, label);
  const button = (label) => {
    const control = node('button', 'ui-button ui-button-secondary', label);
    control.type = 'button';
    return control;
  };
  const metric = (label, value, options = {}) => {
    const card = node('div', `metric ui-metric ui-metric-compact${options.tone ? ` ui-metric-${options.tone}` : ''}${
      options.text ? ' ui-metric-text' : ''}`);
    const copy = node('div', 'ui-metric-copy');
    const heading = node('div', 'ui-metric-heading');
    const caption = node('span', 'ui-metric-label');
    const landmark = node('span', 'ui-metric-icon');
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#visual-icon-${options.icon || 'health'}`);
    svg.append(use);
    landmark.append(svg);
    caption.append(landmark, label);
    heading.append(caption);
    if (options.status) heading.append(status(options.status, options.tone));
    const number = node('strong', '', value);
    if (options.unit) number.append(node('small', 'ui-metric-unit', options.unit));
    copy.append(heading, number);
    if (options.progress !== undefined) {
      const progress = node('progress', 'ui-metric-progress', `${options.progress}%`);
      progress.max = 100;
      progress.value = options.progress;
      progress.setAttribute('aria-label', label);
      copy.append(progress);
    }
    if (options.detail) copy.append(node('small', 'ui-metric-detail', options.detail));
    card.append(copy);
    return card;
  };
  const grid = (count, ...cards) => {
    const element = node('div', `summary-band ui-metric-grid ui-metric-grid-embedded ui-metric-grid-${count}`);
    element.append(...cards);
    return element;
  };
  const section = (key, title, content, options = {}) => {
    const element = node('section', `section ui-section ui-settings-panel ui-section-collapsible receiver-health-section${
      options.closed ? ' collapsed' : ''}`);
    element.dataset.receiverHealthSection = key;
    const heading = node('header', 'section-title ui-section-title');
    const toggle = node('button', 'ui-disclosure-toggle receiver-health-section-toggle');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', String(!options.closed));
    toggle.setAttribute('aria-controls', `visual-health-${key}`);
    toggle.dataset.receiverHealthFocus = `section-${key}`;
    const copy = node('span', 'ui-disclosure-copy');
    copy.append(node('span', '', title));
    if (options.summary) copy.append(node('small', '', options.summary));
    toggle.append(copy);
    if (options.status) toggle.append(status(options.status, options.tone));
    heading.append(toggle);
    if (options.action) heading.append(options.action);
    const body = node('div', 'ui-settings-panel-body receiver-health-section-body');
    body.id = `visual-health-${key}`;
    body.hidden = options.closed === true;
    body.append(content);
    toggle.addEventListener('click', () => {
      const expanded = toggle.getAttribute('aria-expanded') !== 'true';
      toggle.setAttribute('aria-expanded', String(expanded));
      element.classList.toggle('collapsed', !expanded);
      body.hidden = !expanded;
    });
    element.append(heading, body);
    return element;
  };
  const overview = node('div', 'receiver-health-overview');
  const state = metric('Receiver status', 'Action needed', { tone: 'danger', text: true });
  state.classList.add('receiver-health-overview-state', 'receiver-health-critical');
  const overviewMetrics = grid(4, state, metric('Current issues', '2'),
    metric('Need action', '1', { tone: 'danger', icon: 'alert' }),
    metric('Check soon', '1', { tone: 'warning', icon: 'alert' }));
  overviewMetrics.classList.add('ui-metric-grid-inline', 'receiver-health-overview-metrics');
  const timing = node('dl', 'ui-fact-list receiver-health-timing');
  timing.append(node('dt', '', 'Monitoring since'), node('dd', '', 'Today · 8:04 AM'),
    node('dt', '', 'Last update'), node('dd', '', 'Just now'));
  const footer = node('div', 'receiver-health-overview-footer');
  const preferences = node('aside', 'receiver-health-account-setting');
  preferences.append(button('Choose status icon issues'));
  footer.append(timing, preferences);
  overview.append(overviewMetrics, footer);
  const resources = grid(4,
    metric('VCE processor use', '10.3', { unit: '%', progress: 10.3, status: 'Normal', tone: 'success' }),
    metric('VCE memory use', '42', { unit: '%', progress: 42, detail: 'Used 860 MB of 2.0 GB', status: 'Normal', tone: 'success' }),
    metric('Time spent freeing memory', '78', { unit: 'ms in last sample', progress: 78,
      detail: 'Total since startup 2,925 ms', status: 'Check soon', tone: 'warning' }),
    metric('Free storage space', '6', { unit: '%', progress: 6, detail: 'Free 18 GB of 300 GB',
      status: 'Action needed', tone: 'danger', icon: 'recording' }));
  resources.classList.add('receiver-health-resource-bars');
  const activity = grid(3,
    metric('Activity summaries', 'On', { text: true, icon: 'activity' }),
    metric('Individual events', 'On', { text: true, icon: 'activity' }),
    metric('Activity storage', '9.6', { unit: 'GB', icon: 'activity' }));
  const incident = node('article', 'ui-record-card receiver-health-incident receiver-health-critical');
  const incidentHeading = node('div', 'ui-record-card-header receiver-health-incident-heading');
  const identity = node('div', 'ui-record-card-copy receiver-health-incident-identity');
  identity.append(node('h3', 'ui-record-card-title', 'Recording disk is nearly full'),
    node('p', 'muted receiver-health-incident-scope', 'Recording service'));
  incidentHeading.append(identity, node('span', 'ui-pill ui-pill-danger', 'Action needed'));
  const incidentFacts = node('dl', 'ui-fact-list receiver-health-incident-facts');
  incidentFacts.append(node('dt', '', 'Started'), node('dd', '', 'Today · 10:31 AM'),
    node('dt', '', 'Last detected'), node('dd', '', 'Just now'));
  const guidance = node('dl', 'ui-facts ui-record-facts receiver-health-incident-guidance');
  [
    ['What happened', '94% used · 18 GB available'],
    ['Possible cause', 'The recording location has less free space than the configured safety threshold.'],
    ['What this may affect', 'New recordings may stop when the remaining space is exhausted.'],
    ['What to do', 'Remove unneeded recordings or select a location with more space.']
  ].forEach(([label, description]) => {
    const item = node('div', 'ui-fact receiver-health-guidance-item');
    item.append(node('dt', '', label), node('dd', '', description));
    guidance.append(item);
  });
  const incidentBody = node('div', 'ui-record-card-body receiver-health-incident-body');
  incidentBody.append(incidentFacts, guidance);
  incident.append(incidentHeading, incidentBody);
  const incidentList = node('div', 'receiver-health-incident-list');
  const memoryIncident = node('article', 'ui-record-card receiver-health-incident receiver-health-warning');
  const memoryHeading = node('div', 'ui-record-card-header receiver-health-incident-heading');
  const memoryIdentity = node('div', 'ui-record-card-copy receiver-health-incident-identity');
  memoryIdentity.append(node('h3', 'ui-record-card-title', 'Memory cleanup is taking longer'),
    node('p', 'muted receiver-health-incident-scope', 'Computer resources'));
  memoryHeading.append(memoryIdentity, node('span', 'ui-pill ui-pill-warning', 'Check soon'));
  const memoryBody = node('div', 'ui-record-card-body muted receiver-health-incident-body',
    '78 ms in the last sample. Check memory use and close other applications if reception slows.');
  memoryIncident.append(memoryHeading, memoryBody);
  incidentList.append(incident, memoryIncident);
  const issues = node('div', 'ui-settings-page receiver-health-issues-grid receiver-health-issues-grid-active');
  issues.append(section('active', 'Issues needing attention', incidentList, { summary: '2 current issues' }),
    section('resolved', 'Recently cleared', node('div', 'ui-feedback receiver-health-empty',
      'No issues have cleared recently.'), { summary: 'No recently cleared issues', closed: true }));
  const diagnostics = node('section', 'receiver-health-diagnostics');
  diagnostics.setAttribute('aria-label', 'Receiver diagnostics');
  const diagnosticsHeading = node('div', 'ui-heading-group ui-heading-group-quiet receiver-health-diagnostics-heading');
  diagnosticsHeading.append(node('h2', '', 'Receiver diagnostics'), node('p', '', '8 groups · 32 measurements'));
  const groups = node('div', 'ui-settings-page receiver-health-diagnostics-grid');
  ['Tuners', 'USB tuner connection', 'Incoming radio data', 'Channel separation', 'Per-channel processing',
    'Control channel', 'Computer resources', 'Recordings, streams, and browser audio'].forEach((title, index) => {
    const rows = node('div', 'ui-record-card receiver-health-measurement-list');
    rows.setAttribute('role', 'list');
    const measurement = node('div', 'ui-record-section receiver-health-measurement-row receiver-health-healthy');
    measurement.setAttribute('role', 'listitem');
    const measurementHeading = node('div', 'ui-record-card-header receiver-health-measurement-heading');
    const measurementIdentity = node('div', 'ui-record-card-copy');
    measurementIdentity.append(node('p', 'muted receiver-health-measurement-scope', 'Receiver'),
      node('div', 'ui-record-title receiver-health-measurement-label', 'Dropped buffers'));
    const reading = node('div', 'receiver-health-measurement-value');
    reading.append(node('strong', '', '0'));
    measurementHeading.append(measurementIdentity, reading, status('Normal', 'success'));
    measurement.append(measurementHeading,
      node('div', 'ui-record-card-body muted receiver-health-measurement-detail', 'No dropped native buffers were observed.'));
    rows.append(measurement);
    [['Samples received', '12,408'], ['Invalid messages', '0'], ['Last sample', 'Just now']]
      .forEach(([label, value]) => {
        const sample = measurement.cloneNode(true);
        sample.querySelector('.receiver-health-measurement-label').textContent = label;
        sample.querySelector('.receiver-health-measurement-value strong').textContent = value;
        sample.querySelector('.receiver-health-measurement-detail').remove();
        rows.append(sample);
      });
    groups.append(section(`measurement-${index}`, title, rows, { summary: '4 measurements',
      status: index === 6 ? 'Check soon' : 'Normal', tone: index === 6 ? 'warning' : 'success', closed: true }));
  });
  diagnostics.append(diagnosticsHeading, groups);
  host.append(section('current', 'Receiver overview', overview, { action: button('Check again') }),
    section('resources', 'Computer resources', resources),
    section('activity', 'Saved activity', activity, { action: button('Open Activity settings') }), issues, diagnostics);
}
initializeReceiverHealth();
{
  const workspace = document.querySelector('.visual-admin-navigation-example');
  const navigation = workspace.querySelector('[data-visual-admin-navigation]');
  const picker = workspace.querySelector('[data-visual-admin-picker]');
  const groups = [
    ['Status & support', [['health', 'Receiver status', 'health'], ['matching', 'Call matching', 'call-matching'],
      ['application-log', 'Application log', 'activity'],
      ['support', 'Report a problem', 'bug']]],
    ['Audio & recordings', [['recording', 'Recording settings', 'recording-settings'],
      ['audio', 'Audio quality', 'audio-quality'], ['transcription', 'Transcription', 'transcription']]],
    ['Activity & storage', [['activity', 'Activity settings', 'activity'], ['cleanup', 'Saved data cleanup', 'cleanup']]],
    ['Receiver configuration', [['remote', 'Remote Links', 'network-visualizer'], ['bandplans', 'P25 band plans', 'radio-tower']]],
    ['Web interface', [['display', 'Display settings', 'display'], ['accounts', 'Web accounts', 'users'],
      ['access', 'Page access', 'admin']]]
  ];
  const setPage = (id) => {
    const group = groups.find((entry) => entry[1].some((page) => page[0] === id));
    const selected = group[1].find((page) => page[0] === id);
    workspace.querySelector('[data-visual-admin-title]').textContent = selected[1];
    workspace.querySelector('[data-visual-admin-group]').textContent = group[0];
    picker.value = id;
    navigation.querySelectorAll('.ui-settings-nav-link').forEach((link) => {
      if (link.dataset.page === id) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
  };
  groups.forEach(([label, pages]) => {
    const group = document.createElement('section');
    group.className = 'ui-settings-nav-group';
    const title = document.createElement('h2');
    title.className = 'ui-settings-nav-group-title';
    title.textContent = label;
    const items = document.createElement('div');
    items.className = 'ui-settings-nav-items';
    const options = document.createElement('optgroup');
    options.label = label;
    pages.forEach(([id, name, iconName]) => {
      const link = document.createElement('a');
      link.className = 'ui-settings-nav-link';
      link.href = `#${id}`;
      link.dataset.page = id;
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      icon.setAttribute('aria-hidden', 'true');
      const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
      use.setAttribute('href', `#visual-icon-${iconName}`);
      icon.append(use);
      const text = document.createElement('span');
      text.textContent = name;
      link.append(icon, text);
      link.addEventListener('click', (event) => { event.preventDefault(); setPage(id); });
      items.append(link);
      const option = document.createElement('option');
      option.value = id;
      option.textContent = name;
      options.append(option);
    });
    group.append(title, items);
    navigation.append(group);
    picker.append(options);
  });
  picker.addEventListener('change', () => setPage(picker.value));
  setPage('recording');
  document.querySelectorAll('[data-visual-admin-nav-mirror]').forEach((mirror) => {
    const active = mirror.dataset.visualAdminNavMirror;
    mirror.replaceChildren(...[...workspace.querySelector('.ui-settings-navigation').children]
      .map((child) => child.cloneNode(true)));
    mirror.querySelectorAll('.ui-settings-nav-link').forEach((link) => {
      if (link.dataset.page === active) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    mirror.querySelector('select').value = active;
  });
}
if (view === 'activity-action-tooltip') {
  const trigger = document.querySelector('#visual-activity-cell-trigger');
  const tooltip = document.querySelector('#visual-activity-cell-tooltip');
  tooltip.showPopover();
  const positionTooltip = () => {
    const anchor = trigger.getBoundingClientRect();
    const panel = tooltip.getBoundingClientRect();
    tooltip.style.left = `${Math.max(8, Math.min(anchor.right - panel.width,
      window.innerWidth - panel.width - 8))}px`;
    tooltip.style.top = `${anchor.bottom + 6}px`;
  };
  positionTooltip();
  window.addEventListener('resize', positionTooltip);
}
if (view === 'activity-filters') {
  const bindVisualActivityPopover = (triggerId, panelId) => {
    const trigger = document.querySelector(triggerId);
    const panel = document.querySelector(panelId);
    const position = () => {
      if (!panel.matches(':popover-open')) return;
      if (panel.classList.contains('activity-filter-more-panel') &&
          window.matchMedia('(max-width: 720px)').matches) {
        panel.style.removeProperty('left');
        panel.style.removeProperty('top');
        panel.style.removeProperty('max-height');
        return;
      }
      panel.style.maxHeight = '';
      const anchor = trigger.getBoundingClientRect();
      const bounds = panel.getBoundingClientRect();
      const gutter = 8;
      const gap = 6;
      const left = Math.max(gutter, Math.min(anchor.right - bounds.width,
        window.innerWidth - bounds.width - gutter));
      const availableBelow = window.innerHeight - anchor.bottom - gap - gutter;
      const availableAbove = anchor.top - gap - gutter;
      const openAbove = availableBelow < Math.min(96, bounds.height) && availableAbove > availableBelow;
      const maxHeight = Math.min(480, openAbove ? availableAbove : availableBelow);
      panel.style.left = `${Math.round(left)}px`;
      panel.style.top = `${Math.round(openAbove ?
        Math.max(gutter, anchor.top - gap - Math.min(bounds.height, maxHeight)) : anchor.bottom + gap)}px`;
      panel.style.maxHeight = `${Math.max(0, Math.floor(maxHeight))}px`;
    };
    panel.addEventListener('toggle', (event) => {
      trigger.setAttribute('aria-expanded', String(event.newState === 'open'));
      if (event.newState === 'open') position();
    });
    window.addEventListener('resize', position);
  };
  bindVisualActivityPopover('#visual-activity-destination-trigger', '#visual-activity-destination-popover');
  bindVisualActivityPopover('#visual-activity-more-trigger', '#visual-activity-more-panel');
}
if(view === 'access-landing' || view === 'access-login-modal') {
  const example = document.querySelector(view === 'access-landing' ?
    '.visual-access-landing-example' : '.visual-access-login-modal-example');
  const canvas = example.querySelector('.access-scene-canvas');
  import('/assets/features/access-wireframe.js?v=1').then(({ mountAccessWireframe }) => {
    mountAccessWireframe(canvas, { compact: view === 'access-login-modal' });
    example.dataset.sceneReady = 'true';
  });
  example.querySelector('form').addEventListener('submit', (event) => event.preventDefault());
}
if(view === 'gallery') initializeGuidanceTable();
if(view === 'audio-dock') await mountAudioDockGallery(parameters);
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
const label = document.getElementById('visual-theme-label');
if(label) label.textContent = `${theme[0].toUpperCase()}${theme.slice(1)} theme`;

if (view === 'workflows') {
  const node = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  };
  const host = document.querySelector('.visual-workflow-editor');
  const form = host.querySelector('form');
  const input = form.elements.display_name;
  const submit = form.querySelector('[type="submit"]');
  const cancel = form.querySelector('[data-workflow-cancel]');
  let saved = input.value;
  let attempts = 0;
  const workflow = createFormWorkflow({ form, submit,
    feedback: form.querySelector('[data-workflow-feedback]'),
    changed: () => input.value !== saved,
    modal: { setDirty: (dirty) => { host.dataset.dirty = String(dirty); },
      setBusy: (busy) => { cancel.disabled = busy; } }
  });
  cancel.addEventListener('click', () => {
    input.value = saved;
    workflow.refresh();
    workflow.showFeedback('status', '');
  });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    workflow.save(async () => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      attempts += 1;
      if (attempts === 1) throw new Error('The changes could not be saved. Try again.');
      saved = input.value;
    });
  });
  const availability = document.querySelector('[data-workflow-availability]');
  availability.append(scanListAvailabilityPill(node, { published: true }),
    scanListAvailabilityPill(node, { published: false }));
  let offset = 0;
  const update = () => pager.update({ countText: pageRangeText({ offset, visible: 10, total: 30 }),
    previous: { enabled: offset > 0, onClick: () => { offset -= 10; update(); } },
    next: { enabled: offset < 20, onClick: () => { offset += 10; update(); } } });
  const pager = createBrowsingPager({ node });
  update();
  document.querySelector('[data-workflow-pager]').append(pager);
}
