import { applyThemeHue, APPEARANCE_SCHEMES } from '/assets/core/theme.js?v=2';
import { receiverHealthAlertGroups } from '/assets/core/receiver-health-alerts.js?v=4';
import { setIconHintDetails } from '/assets/core/icon-hints.js';
import { formatPagingToneSummary } from '/assets/core/call-audio-details.js';
import * as tableDefaults from '/assets/core/table-defaults.js';
import * as tableLayouts from '/assets/core/table-layout.js';
import { createTableOverflow } from '/assets/core/table-overflow.js?v=1';
import { createDualRange } from '/assets/core/dual-range.js';
import { mountAudioDockGallery } from '/visual/audio-dock-gallery.js';
import { createFormWorkflow } from '/assets/core/form-workflows.js';
import { createBrowsingPager, createFilterDisclosure, pageRangeText, scanListAvailabilityPill } from '/assets/core/browsing-workflows.js';
import { createGalleryModalFoundation } from '/visual/modal-foundation-gallery.js';
import { openSetupGuide } from '/assets/core/setup-guide.js?v=11';
import { createGuideDefinition } from '/assets/guides/catalog.js?v=16';
import { enrollmentQrCanvas } from '/assets/features/alerts-qr.js?v=2';
import { openRadioReferenceConnect } from '/assets/features/radioreference-connect.js?v=3';
import { createRemoteSupportWorkspace, remoteSupportDescription } from '/assets/features/remote-support.js?v=6';

document.querySelector('[data-qr-example]').append(enrollmentQrCanvas(document,
  'https://radioresolve.example.net/enroll?code=demo-id.demo-pairing-code-22'));

const toneHint = document.querySelector('[data-tone-hint]');
const toneSummary = formatPagingToneSummary({ source: 'encoded', tones: Array.from({ length: 13 }, (_, index) => ({
  frequencies_hz: [index % 2 ? 343.75 : 625], duration_milliseconds: 260,
  offset_milliseconds: 900 + index * 280
})) });
toneHint.dataset.uiHint = `Tones · ${toneSummary.source} · ${toneSummary.tones.join('; ')}`;
toneHint.setAttribute('aria-description', toneHint.dataset.uiHint);
setIconHintDetails(toneHint, { heading: `Tones · ${toneSummary.source}`,
  columns: ['Tone', 'Duration', 'Start'], rows: toneSummary.rows });

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
      'Any length' : `${value}s` });
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
    trigger.className = 'ui-button ui-button-secondary ui-button-compact table-layout-trigger';
    trigger.type = 'button';
    trigger.setAttribute('aria-label', 'Choose table columns');
    trigger.title = 'Choose table columns';
    const panelId = 'visual-guidance-table-layout';
    trigger.setAttribute('popovertarget', panelId);
    trigger.setAttribute('aria-haspopup', 'dialog');
    trigger.setAttribute('aria-controls', panelId);
    const triggerLabel = document.createElement('span');
    triggerLabel.textContent = 'Columns';
    trigger.append(icon('visual-icon-columns'), triggerLabel);
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
const view = ['awaiting-apply', 'control-states', 'table-overflow', 'mobile-table', 'gallery', 'workflows', 'qr-code', 'section-body', 'app-chrome', 'audio-dock', 'access-landing', 'access-login-modal', 'modal', 'modal-long', 'alias-list-create-modal', 'activity-action-tooltip', 'activity-filters', 'health-alert-modal', 'focus', 'settings', 'health', 'status-primitives', 'p25', 'admin-access',
  'admin-navigation', 'focused-settings', 'admin-receiver', 'admin-support', 'dashboard-health', 'dashboard-calls', 'dashboard-activity',
  'signal-quality-detail', 'radioreference-results', 'radioreference-connect', 'comparison-table',
  'radio-directory-coverage', 'radio-directory-panel', 'admin-scan-lists',
  'scanner', 'tuner-spectrum', 'tuners', 'aliases', 'alerts', 'alias-modal', 'alias-export', 'scan-list-members', 'channels',
  'radio-directory', 'entity-details', 'call-matching', 'receiving-windows', 'live-notice', 'live-filter', 'tuner-frequency-popover',
  'spectrum-discovery', 'spectrum-search', 'setup-guide'].includes(parameters.get('view')) ?
  parameters.get('view') : 'gallery';
document.documentElement.dataset.theme = theme;
const requestedHue = parameters.get('hue');
applyThemeHue(requestedHue === null ? null : Number(requestedHue));
document.body.dataset.galleryView = view;

if (view === 'admin-support' || view === 'gallery') {
  const node = (tag, className = '', text = null) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== null) element.textContent = String(text);
    return element;
  };
  const iconGlyph = (name) => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#visual-${name === 'icon-chevron-down' ? 'icon-chevron' : name}`);
    svg.append(use);
    return svg;
  };
  const source = await fetch('/assets/app.js').then(response => response.text());
  const names = ['formField', 'uiSelectFrame', 'metricCard', 'valueNode', 'uiActionButton'];
  const functions = names.map((name) => {
    const match = source.match(new RegExp(`^function ${name}\\([\\s\\S]*?^\\}`, 'm'));
    if (!match) throw new Error(`Missing production control ${name}`);
    return match[0];
  });
  const controls = new Function('node', 'iconGlyph', 'METRIC_ICONS', 'number',
    `${functions.join('\n')}\nreturn { ${names.join(', ')} };`)(node, iconGlyph, {}, String);
  let snapshot = { enabled: false, connected: false, code: '', expires_at_ms: 0,
    unlimited: false, can_start: true };
  const workspace = createRemoteSupportWorkspace({ node, iconGlyph, ...controls,
    heading: 'Remote support (optional)',
    description: remoteSupportDescription,
    fetchDocument: async (_path, options) => {
      if (options.method === 'POST') {
        const change = JSON.parse(options.body);
        snapshot = change.action === 'stop' ? { ...snapshot, enabled: false } :
          { ...snapshot, enabled: true, code: '482719356',
            expires_at_ms: change.duration_seconds ? Date.now() + change.duration_seconds * 1000 : 0,
            unlimited: change.duration_seconds === 0 };
      }
      return new Response(JSON.stringify({ data: snapshot }), { headers: { 'Content-Type': 'application/json' } });
    }
  });
  workspace.element.querySelector('.settings-card-header').classList.add('ui-settings-panel-header');
  document.querySelector('[data-support-remote-workspace]').replaceWith(workspace.element);
  await workspace.load({ initial: true });
}

if (view === 'focused-settings') {
  const { createMySettingsWorkspace } = await import('/assets/features/my-settings.js?v=1');
  const { confirmAction } = await createGalleryModalFoundation();
  const host = document.querySelector('[data-focused-settings-gallery]');
  const defaults = () => ({ appearance: { theme, hue: null },
    presentation: { source_name_display: 'talker_alias' }, health_alerts: { disabled_codes: [] } });
  let preferences = defaults();
  const node = (tag, className = '', text = null) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== null) element.textContent = String(text);
    return element;
  };
  const iconGlyph = (name) => {
    const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    icon.setAttribute('aria-hidden', 'true');
    use.setAttribute('href', `#visual-${name}`);
    icon.append(use);
    return icon;
  };
  const uiToggle = (checked, label) => {
    const control = node('label', 'ui-toggle');
    const input = node('input');
    input.type = 'checkbox';
    input.checked = checked;
    input.setAttribute('aria-label', label);
    const track = node('span', 'ui-toggle-track');
    track.append(node('span', 'ui-toggle-thumb'));
    const state = node('span', 'ui-toggle-state', checked ? 'On' : 'Off');
    input.addEventListener('change', () => { state.textContent = input.checked ? 'On' : 'Off'; });
    control.append(input, track, state);
    return control;
  };
  const readSnapshot = () => ({ identity: 'focused-gallery', loaded: true, preferences });
  const applyAppearance = (preview) => {
    const appearance = preview || preferences.appearance;
    document.documentElement.dataset.theme = appearance.theme;
    applyThemeHue(appearance.hue);
  };
  const workspace = createMySettingsWorkspace({ node, iconGlyph, uiToggle, createFormWorkflow,
    schemes: APPEARANCE_SCHEMES, alertGroups: receiverHealthAlertGroups, readSnapshot,
    updatePreferences: async (mutator) => {
      if (parameters.get('state') === 'error') throw new Error('Gallery save failed.');
      const next = structuredClone(preferences);
      mutator(next);
      preferences = next;
      return preferences;
    },
    setAppearancePreview: applyAppearance, confirmAction,
    openReset: async (returnFocusSelector) => {
      const accepted = await confirmAction('Reset every personal preference for this account to its default value?', {
        title: 'Reset personal preferences', confirmLabel: 'Reset personal preferences',
        returnFocusSelector, tone: 'danger'
      });
      if (!accepted) return;
      preferences = defaults();
      applyAppearance(null);
      workspace.onSnapshot(readSnapshot());
    },
    initialSection: parameters.get('section') || 'appearance'
  });
  host.append(workspace.element);
  host.dataset.focusedSettingsReady = 'true';
}

if (view === 'app-chrome') {
  const example = document.querySelector('.visual-app-chrome-example');
  const navigation = example.querySelector('.primary-nav');
  const toggle = example.querySelector('.navigation-toggle');
  const backdrop = example.querySelector('.navigation-backdrop');
  const groups = [...navigation.querySelectorAll('.nav-group')];
  const drawerMedia = window.matchMedia('(max-width: 1180px)');
  const selected = groups.find(group => group.dataset.navGroup === parameters.get('menu')) || groups[0];
  function setDrawerOpen(open) {
    const next = drawerMedia.matches && open;
    document.body.classList.toggle('navigation-open', next);
    toggle.setAttribute('aria-expanded', String(next));
    toggle.setAttribute('aria-label', next ? 'Close navigation' : 'Open navigation');
    backdrop.hidden = !next;
    navigation.toggleAttribute('inert', drawerMedia.matches && !next);
  }
  groups.forEach(group => group.addEventListener('toggle', () => {
    if (group.open) groups.forEach(other => { if (other !== group) other.open = false; });
  }));
  selected.open = true;
  setDrawerOpen(true);
  toggle.addEventListener('click', () => setDrawerOpen(!document.body.classList.contains('navigation-open')));
  backdrop.addEventListener('click', () => setDrawerOpen(false));
  navigation.addEventListener('click', event => {
    if (event.target.closest('a')) event.preventDefault();
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    if (document.body.classList.contains('navigation-open')) {
      setDrawerOpen(false);
      toggle.focus();
    } else {
      const group = groups.find(item => item.open);
      if (group) { group.querySelector('summary').focus(); group.open = false; }
    }
  });
  drawerMedia.addEventListener('change', () => setDrawerOpen(true));
}
const modalSize = parameters.get('modal-size');
if (['small', 'medium'].includes(modalSize)) {
  document.querySelector('.visual-modal-example .read-only-modal')?.classList.add(`modal-size-${modalSize}`);
}

if (view === 'awaiting-apply') {
  const example = document.createElement('main');
  example.className = 'visual-feature-example visual-awaiting-apply-example';
  example.innerHTML = `<section class="ui-section">
    <header class="ui-section-title"><h1>Search reminder</h1></header>
    <div class="ui-section-body editor-workspace">
      <p class="ui-muted" role="status">Changes ready to search</p>
      <div class="ui-action-row ui-action-row-start">
        <button type="button" class="ui-button ui-button-primary ui-button-awaiting-apply" data-awaiting-search>
          <svg aria-hidden="true"><use href="#visual-icon-search"></use></svg>Search recordings
        </button>
        <button type="button" class="ui-button ui-button-secondary" data-edit-search>Change filters</button>
        <button type="button" class="ui-button ui-button-primary" disabled>Finding calls…</button>
      </div>
    </div>
  </section>`;
  const search = example.querySelector('[data-awaiting-search]');
  const feedback = example.querySelector('[role="status"]');
  search.addEventListener('click', () => {
    search.classList.remove('ui-button-awaiting-apply');
    feedback.textContent = 'Results updated';
  });
  example.querySelector('[data-edit-search]').addEventListener('click', () => {
    search.classList.add('ui-button-awaiting-apply');
    feedback.textContent = 'Changes ready to search';
  });
  document.body.append(example);
}

if (view === 'comparison-table') {
  const form = document.querySelector('#visual-comparison-table-template').content.firstElementChild.cloneNode(true);
  const cancel = form.querySelector('[data-comparison-cancel]');
  const retry = form.querySelector('[data-comparison-retry]');
  const details = form.querySelector('[data-comparison-details]');
  const feedback = form.querySelector('[data-comparison-feedback]');
  const submit = form.querySelector('[type="submit"]');
  if (parameters.get('comparison-content') === 'long') {
    form.querySelector('[data-comparison-current-name]').textContent =
      'NorthCountyRegionalEmergencyServicesPrimarySimulcast';
    form.querySelector('[data-comparison-suggested-name]').textContent =
      'North County Regional Emergency Services Communications Network (P25) · Greater Metropolitan Simulcast';
  }
  const foundation = await createGalleryModalFoundation();
  const modal = foundation.openReadOnlyModal('Review RadioReference names', form, {
    id: 'gallery-comparison-table', className: 'modal-size-medium'
  });
  await modal.ready;
  const workflow = createFormWorkflow({ form, submit, feedback,
    modal: { setBusy: modal.setBusy, setDirty: () => {} } });
  cancel.addEventListener('click', () => modal.close());
  retry.addEventListener('click', () => { window.location.search =
    new URLSearchParams({ theme, view: 'comparison-table' }).toString(); });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    void workflow.save(() => parameters.get('comparison-state') === 'busy' ? new Promise(() => {}) :
      Promise.resolve(), { saving: 'Saving RadioReference names…', onSuccess: () => modal.close() });
  });
  const state = parameters.get('comparison-state');
  if (state === 'empty' || state === 'loading') {
    const message = document.createElement('p');
    message.className = `ui-feedback ui-feedback-${state}`;
    message.textContent = state === 'empty' ? 'Names are already up to date.' :
      'Looking up this channel in RadioReference…';
    details.replaceChildren(message);
    workflow.setReady(false);
  } else if (state === 'error') {
    workflow.showFeedback('error',
      'The names could not be saved. Look up this channel again to review a fresh comparison.');
    workflow.setReady(false);
    retry.hidden = false;
  }
  modal.focus(cancel);
  if (state === 'busy') form.requestSubmit();
}

if (view === 'radioreference-connect') {
  const node = (tag, className = '', text = null) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== null) element.textContent = String(text);
    return element;
  };
  const foundation = await createGalleryModalFoundation();
  const response = await fetch('/assets/app.js');
  if (!response.ok) throw new Error('The production account controls could not be loaded.');
  const source = await response.text();
  const fieldsStart = source.indexOf('function formField(');
  const fieldsEnd = source.indexOf('function authenticationFailureMessage(', fieldsStart);
  const togglesStart = source.indexOf('function uiToggle(');
  const togglesEnd = source.indexOf('function uiPill(', togglesStart);
  if ([fieldsStart, fieldsEnd, togglesStart, togglesEnd].some(index => index < 0)) {
    throw new Error('The production account controls were not found.');
  }
  const controls = new Function('node', `${source.slice(fieldsStart, fieldsEnd)}
    ${source.slice(togglesStart, togglesEnd)}
    return { formField, uiToggleField };`)(node);
  const requestJson = (_path, options) => {
    if (parameters.get('connection-state') === 'busy') {
      return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () =>
        reject(new DOMException('Example closed', 'AbortError')), { once: true }));
    }
    return Promise.resolve({ account: { state: parameters.get('connection-state') === 'error' ?
      'INVALID_CREDENTIALS' : 'VALID_PREMIUM' } });
  };
  void openRadioReferenceConnect({ node, ...foundation, ...controls, requestJson }, {
    introduction: 'Connect RadioReference to add system and site names.'
  });
}

if (view === 'setup-guide') {
  const example = document.querySelector('.visual-channels-example');
  example.classList.add('visual-setup-guide-example');
  example.querySelectorAll('.ui-metric strong').forEach(value => { value.textContent = '0'; });
  example.querySelector('.channel-selection-bar').remove();
  const rows = example.querySelector('tbody');
  rows.replaceChildren();
  const empty = document.createElement('tr');
  const cell = document.createElement('td');
  cell.colSpan = 6;
  cell.textContent = 'No channels match this view';
  empty.append(cell);
  rows.append(empty);
  const node = (tag, className = '', text = null) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== null) element.textContent = String(text);
    return element;
  };
  const creationActions = example.querySelector('.channel-creation-actions');
  const [newChannel, findSystems] = creationActions.querySelectorAll('button');
  newChannel.dataset.guideTarget = 'channel-new';
  findSystems.dataset.guideTarget = 'channel-discover';
  const { openReadOnlyModal } = await createGalleryModalFoundation();
  const definition = createGuideDefinition(parameters.get('diagram') === 'scan-lists' ? 'scan-lists' : 'channel-setup');
  const introduction = openSetupGuide({ node, openReadOnlyModal }, definition);
  await introduction.ready;
  example.dataset.ready = 'true';
}

if (view === 'live-notice') {
  document.querySelector('.visual-live-example .channels-live-table').dataset.rowDensity =
    parameters.get('row-density') === 'dense' ? 'dense' : 'normal';
}

if (view === 'spectrum-discovery') {
  const example = document.querySelector('.visual-spectrum-discovery-example');
  const workspace = example.querySelector('.spectrum-discovery-workspace');
  const button = workspace.querySelector('[data-visual-discovery-details]');
  const node = (tag, className = '', text = null) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== null) element.textContent = String(text);
    return element;
  };
  let foundation = null;
  let parent = null;
  button.addEventListener('click', async () => {
    foundation ||= createGalleryModalFoundation();
    const { openReadOnlyModal } = await foundation;
    if (!parent) {
      example.querySelector('.modal-backdrop').remove();
      parent = openReadOnlyModal('Add a channel', workspace, {
        id: 'visual-spectrum-discovery', className: 'channel-editor-modal spectrum-discovery-modal'
      });
    }
    parent.focus(button);
    const content = node('div', 'ui-editor-sections');
    const section = (label, entries) => {
      const panel = node('fieldset', 'ui-form-section');
      const body = node('div');
      const facts = node('dl', 'ui-fact-list spectrum-discovery-summary');
      entries.forEach(([key, value]) => facts.append(node('dt', '', key), node('dd', '', value)));
      body.append(facts);
      panel.append(node('legend', '', label), body);
      content.append(panel);
    };
    section('Channel', [['System', 'Ohio MARCS-IP: Multi-Agency Radio Communications'],
      ['Site', 'Cuyahoga County / Greater Cleveland Simulcast'], ['Frequency', '851.012500 MHz'],
      ['Protocol', 'P25 Phase 1']]);
    section('On-air identity', [['WACN', 'BEE00'], ['System ID', '348'], ['RFSS', '1'], ['Site ID', '27']]);
    section('Signal health', [['Modulation', 'CQPSK'], ['Decoder quality', '99%'],
      ['Valid control messages', '41'], ['Invalid control messages', '1']]);
    const comparison = node('fieldset', 'ui-form-section spectrum-discovery-comparison');
    const wrap = node('div', 'ui-table-wrap');
    const table = node('table', 'ui-data-table ui-data-table-quiet spectrum-discovery-probe-table');
    const head = node('thead');
    const header = node('tr');
    ['Setting', 'Valid', 'Control', 'Rejected', 'Decode score'].forEach(label => {
      const cell = node('th', '', label);
      cell.scope = 'col';
      header.append(cell);
    });
    head.append(header);
    const body = node('tbody');
    [['C4FM', '18', '12', '4', '81%'], ['CQPSK', '52', '41', '1', '99%']].forEach(([label, ...counts]) => {
      const row = node('tr');
      const name = node('th', '', label);
      name.scope = 'row';
      if (label === 'CQPSK') name.append(' ', node('span', 'ui-pill ui-pill-success', 'Selected'));
      row.append(name, ...counts.map(value => node('td', '', value)));
      body.append(row);
    });
    table.append(head, body);
    wrap.append(table);
    comparison.append(node('legend', '', 'Signal setting comparison'), wrap,
      node('p', 'ui-field-hint', 'Decode score compares valid control messages, rejected messages, lost synchronization and corrected bits. A low score can still confirm a system when its identity repeats consistently.'));
    content.append(comparison);
    const footer = node('footer', 'ui-modal-footer ui-action-row');
    const back = node('button', 'ui-button ui-button-secondary', 'Back to channel');
    back.type = 'button';
    footer.append(back);
    content.append(footer);
    const details = openReadOnlyModal('Signal details · 851.012500 MHz', content, {
      id: 'visual-spectrum-discovery-signal', className: 'spectrum-search-detail-modal spectrum-discovery-detail-modal', stack: 'child'
    });
    back.addEventListener('click', () => details.close());
  });
}

if (view === 'spectrum-search') {
  const example = document.querySelector('.visual-spectrum-search-example');
  const workspace = example.querySelector('.spectrum-search-workspace');
  const rows = [...workspace.querySelectorAll('tbody tr')];
  const groups = [...workspace.querySelectorAll('.spectrum-search-system-group')];
  const count = workspace.querySelector('[data-visual-search-count]');
  const review = workspace.querySelector('[data-visual-search-review]');
  const rowCheck = row => row.querySelector('.spectrum-search-channel-check');
  const updateSelection = () => {
    const selected = rows.filter(row => rowCheck(row).checked).length;
    count.textContent = `${selected} selected`;
    review.textContent = selected ? `Review ${selected} ${selected === 1 ? 'channel' : 'channels'}` : 'Review selected';
    review.disabled = selected === 0;
    groups.forEach(group => {
      const available = [...group.querySelectorAll('.spectrum-search-channel-check')].filter(check => !check.disabled);
      const chosen = available.filter(check => check.checked).length;
      const check = group.querySelector('.spectrum-search-system-header > input');
      check.checked = available.length > 0 && chosen === available.length;
      check.indeterminate = chosen > 0 && chosen < available.length;
      check.disabled = available.length === 0;
    });
  };
  rows.forEach(row => rowCheck(row).addEventListener('change', updateSelection));
  groups.forEach(group => {
    const check = group.querySelector('.spectrum-search-system-header > input');
    check.addEventListener('change', () => {
      group.querySelectorAll('.spectrum-search-channel-check').forEach(rowCheck => {
        if (!rowCheck.disabled) rowCheck.checked = check.checked;
      });
      updateSelection();
    });
  });
  workspace.querySelector('[data-visual-search-select-all]').addEventListener('click', () => {
    rows.forEach(row => { if (!rowCheck(row).disabled) rowCheck(row).checked = true; });
    updateSelection();
  });
  updateSelection();

  const node = (tag, className = '', text = null) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== null) element.textContent = String(text);
    return element;
  };
  let foundation = null;
  let parent = null;
  rows.forEach(row => row.querySelector('[data-label="Details"] button').addEventListener('click', async event => {
    const button = event.currentTarget;
    foundation ||= createGalleryModalFoundation();
    const { openReadOnlyModal } = await foundation;
    if (!parent) {
      example.querySelector('.modal-backdrop').remove();
      parent = openReadOnlyModal('Find Trunked Systems', workspace, {
        id: 'visual-spectrum-search', className: 'channel-editor-modal spectrum-search-modal'
      });
    }
    parent.focus(button);
    const cells = [...row.querySelectorAll('td')];
    const group = row.closest('.spectrum-search-system-group');
    const frequency = row.querySelector('.identity-summary-primary').textContent;
    const [protocol, modulation] = row.querySelector('.identity-summary > .muted').textContent.split(' · ');
    const identity = group.querySelector('.spectrum-search-system-identity > .muted').textContent;
    const content = node('div', 'ui-editor-sections');
    const section = (label, entries) => {
      const panel = node('fieldset', 'ui-form-section');
      const body = node('div');
      const facts = node('dl', 'ui-fact-list');
      entries.forEach(([key, value]) => facts.append(node('dt', '', key), node('dd', '', value)));
      body.append(facts);
      panel.append(node('legend', '', label), body);
      content.append(panel);
    };
    section('Channel', [['System', group.querySelector('.spectrum-search-system-identity > strong').textContent],
      ['Site', cells[2].textContent], ['Protocol', protocol]]);
    section('On-air identity', [['WACN', identity.match(/WACN (\w+)/)?.[1] || '—'],
      ['System ID', identity.match(/SysID (\w+)/)?.[1] || '—']]);
    section('Signal health', [['Signal', cells[3].textContent], ['Modulation', modulation],
      ['Decoder quality', cells[4].textContent], ['Valid control messages', '41'],
      ['Invalid control messages', '1'], ['Last checked', 'During this search']]);
    const footer = node('footer', 'ui-modal-footer ui-action-row');
    const back = node('button', 'ui-button ui-button-secondary', 'Back to channels');
    back.type = 'button';
    footer.append(back);
    content.append(footer);
    const details = openReadOnlyModal(`Signal details · ${frequency}`, content, {
      id: 'visual-spectrum-search-signal', className: 'spectrum-search-detail-modal', stack: 'child'
    });
    back.addEventListener('click', () => details.close());
  }));
}

if (view === 'table-overflow') {
  const iconButton = (iconId, text, className) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = className;
    button.setAttribute('aria-label', text);
    button.title = text;
    const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    icon.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#visual-${iconId}`);
    icon.append(use);
    button.append(icon);
    return button;
  };
  for (const section of document.querySelectorAll('.visual-table-overflow-example .ui-section')) {
    const table = section.querySelector('table');
    if (table.dataset.exampleWidth) {
      const width = Number(table.dataset.exampleWidth);
      const phone = window.matchMedia('(max-width: 760px)');
      const setWidth = () => {
        const cards = table.dataset.mobileCards === 'true' && phone.matches;
        table.style.width = cards ? '' : `${width}px`;
        table.style.minWidth = cards ? '' : `${width}px`;
        table.style.setProperty('--table-content-min-width', cards ? '0px' : `${width}px`);
      };
      setWidth();
      phone.addEventListener('change', setWidth);
    }
    const controller = createTableOverflow({ wrapper: table.parentElement, table,
      actionsHost: section.querySelector('.ui-section-actions'), iconButton,
      label: section.querySelector('h2').textContent });
    if (section.dataset.examplePosition === 'middle') {
      requestAnimationFrame(() => {
        table.parentElement.scrollLeft = (table.parentElement.scrollWidth - table.parentElement.clientWidth) / 2;
        controller.refresh();
      });
    }
  }
}

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
    rows.forEach((record, index) => {
      const row = node('tr', parameters.get('highlight') === '1' ?
        `activity-row-new${index === 0 ? ' selected' : ''}` : '');
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

async function initializeReceiverHealth() {
  const host = document.querySelector('[data-visual-receiver-health]');
  if (!host) return;
  const node = (tag, className = '', text) => {
    const element = document.createElement(tag);
    element.className = className;
    if (text !== undefined) element.textContent = String(text);
    return element;
  };
  const iconGlyph = (id) => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    const name = ({ 'icon-dashboard': 'activity', 'icon-warning': 'alert',
      'icon-refresh': 'replay' })[id] || id.replace(/^icon-/, '');
    use.setAttribute('href', `#visual-icon-${name}`);
    svg.append(use);
    return svg;
  };
  const source = await fetch('/assets/app.js').then(response => response.text());
  const functionSource = (name) => {
    const signature = source.match(new RegExp(`^function ${name}\\([^\\n]*\\) \\{`, 'm'))?.[0];
    if (!signature) throw new Error(`Missing function ${name}`);
    const start = source.indexOf(signature);
    const openingBrace = start + signature.length - 1;
    let depth = 0;
    for (let index = openingBrace; index < source.length; index += 1) {
      if (source[index] === '{') depth += 1;
      else if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
    }
    throw new Error(`Unterminated function ${name}`);
  };
  // Use the production page and shared builders so this executable example stays aligned with real layouts and states.
  const pageSource = source.slice(source.indexOf('function receiverHealthText('),
    source.indexOf('async function renderAdminHealth('));
  const healthFunctions = [...pageSource.matchAll(/^function (\w+)\(/gm)].map(match => match[1]);
  const sharedFunctions = ['receiverHealthSeverity', 'receiverHealthCount', 'normalizeReceiverHealthSnapshot',
    'receiverHealthAccountAlertSummary', 'keyValues', 'metricCard', 'metrics', 'uiActionButton',
    'uiPill', 'uiStatus', 'valueNode', 'section', 'sectionActionHost', 'adminStatusBytes', 'adminDatabaseDisplay',
    'receiverHealthSavedActivitySection', 'livePickerNavigationIndex', 'exactDateTime', 'dateTime', 'elapsedLabel'];
  const now = Date.UTC(2026, 9, 20, 14, 26, 37);
  const attention = new URLSearchParams(location.search).get('health') === 'attention';
  const incident = { code: 'control-stopped', severity: 'critical', title: 'Control channel stopped decoding',
    configuration_id: 'county-dispatch', scope: 'County Dispatch · Primary site',
    opened_at_ms: now - 15 * 60_000, last_seen_ms: now - 14 * 60_000,
    observed: 'The control channel stopped reporting decoded messages.',
    likely_cause: 'The signal or tuner connection may have changed.',
    impact: 'New calls may be missed.', check_next: 'Check the channel signal and tuner connection.' };
  const computer = {
    application: 'VCE', version: '0.6.2-nightly', update_track: 'nightly', update_build: '123456',
    build_timestamp: '2026-10-09T12:00:00.000+0000', operating_system: 'Windows 11',
    operating_system_version: '10.0', architecture: 'amd64', cpu_model: 'Intel Core i7-12700',
    processors_available: 16, installed_memory_bytes: 34359738368, jvm_memory_limit_bytes: 2147483648,
    java_version: '25.0.1', java_vendor: 'Eclipse Adoptium', application_uptime_ms: 93780000
  };
  const resources = [
    { label: 'VCE processor use', value: 18.6, unit: '%' },
    { label: 'VCE memory use', value: 29.9, unit: '%', detail: 'Used 612 MB of 2.0 GB' },
    { label: 'Time spent freeing memory', value: 8, unit: 'ms in last sample',
      detail: 'Total since startup 35495 ms' },
    { label: 'Free storage space', value: 35.1, unit: '%', detail: 'Free 80 GB of 228 GB' }
  ].map(row => ({ severity: 'healthy', scope: 'Computer', ...row }));
  const measurements = [
    ['tuners', 'Tuners'], ['usb', 'USB tuner connection'], ['receiver', 'Incoming radio data'],
    ['channelization', 'Channel separation'], ['processing', 'Per-channel processing'],
    ['control', 'Control channel'], ['host', 'Computer resources'],
    ['audio', 'Recordings, streams, and browser audio']
  ].map(([id, title]) => ({ id, title, rows: id === 'host' ? resources : [
    ['Dropped buffers', '0'], ['Samples received', '12,408'], ['Invalid messages', '0'], ['Last sample', 'Just now']
  ].map(([label, value]) => ({ severity: 'healthy', scope: 'County Dispatch · Primary site', label, value })) }));
  const snapshot = { started_at_ms: now - 90 * 60_000, generated_at_ms: now, computer,
    summary: { severity: attention ? 'critical' : 'healthy', active_count: attention ? 2 : 0,
      critical_count: attention ? 1 : 0, warning_count: attention ? 1 : 0 },
    active: attention ? [incident, { ...incident, code: 'memory-cleanup', severity: 'warning',
      title: 'Memory cleanup is taking longer', configuration_id: '', scope: 'Computer resources' }] : [],
    resolved: Array.from({ length: 12 }, (_, index) => ({ ...incident, occurrence_id: `resolved-${index}`,
      configuration_id: index < 8 ? 'county-dispatch' : 'north-dispatch',
      scope: index < 8 ? 'County Dispatch · Primary site' : 'North Dispatch · West site',
      resolved_at_ms: now - (index + 1) * 60_000 })), measurements };
  const receiverHealthController = { authorized: () => true, pageHost: host, healthView: 'overview',
    openHealthSections: new Set(['current', 'active', 'host-overview', 'resolved', 'saved-activity']),
    expandedResolvedIncidents: new Set(), resolvedSort: 'recent', resolvedPage: 0,
    computerCopyState: '', computerCopyBusy: false, refresh: async () => receiverHealthController.updatePage() };
  const environment = {
    node, iconGlyph, host, receiverHealthController, number: value => new Intl.NumberFormat('en-US').format(value),
    Date: class extends Date { static now() { return now; } },
    radioSystemPrimaryName: row => row.system_name || '', viewAllowed: () => false,
    anchor: (label, target, className) => Object.assign(node('a', className, label), { href: target }),
    href: (view, values = {}) => `#${view}${new URLSearchParams(values).size ? `?${new URLSearchParams(values)}` : ''}`,
    activeUserPreferences: () => ({}), isReceiverHealthAlertEnabled: () => true,
    openStatusIconSettings: () => {}, loadStatus: async () => {},
    browsingWorkflows: { createBrowsingPager, pageRangeText },
    serviceStatus: { database: { database_exists: true, database_bytes: 9.6 * 1073741824 } },
    statsLoggingState: () => ({ available: true, state: 'RUNNING', summaryActive: true, historyActive: true }),
    semanticLabel: value => value.charAt(0) + value.slice(1).toLowerCase(),
    capabilityAllowed: () => true, ACCESS_CAPABILITIES: { ADMIN_SETTINGS: 'admin-settings' },
    route: new URLSearchParams('view=admin&tab=health'),
    METRIC_ICONS: { Processor: 'icon-health', Memory: 'icon-dashboard', 'Free storage': 'icon-recording',
      'VCE processor use': 'icon-health', 'VCE memory use': 'icon-dashboard',
      'Free storage space': 'icon-recording', 'Activity summaries': 'icon-live',
      'Individual events': 'icon-scan-lists', 'Activity storage': 'icon-dashboard' }
  };
  const render = new Function('environment', `
    const { ${Object.keys(environment).join(', ')} } = environment;
    const RECEIVER_HEALTH_RESOLVED_PAGE_SIZE = 5;
    const RECEIVER_HEALTH_GC_BAR_MAXIMUM_MILLISECONDS = 1000;
    let receiverHealthSectionSequence = 0;
    ${[...sharedFunctions, ...healthFunctions].map(functionSource).join('\n')}
    return snapshot => renderReceiverHealthPage(host, normalizeReceiverHealthSnapshot(snapshot), false, '');
  `)({ ...environment, host });
  receiverHealthController.updatePage = () => render(snapshot);
  receiverHealthController.updatePage();
}
void initializeReceiverHealth();
{
  const workspace = document.querySelector('.visual-admin-navigation-example');
  const navigation = workspace.querySelector('[data-visual-admin-navigation]');
  const picker = workspace.querySelector('[data-visual-admin-picker]');
  const groups = [
    ['Status & support', [['health', 'Receiver status', 'health'],
      ['application-log', 'Application log', 'activity'],
      ['support', 'Report a problem', 'bug']]],
    ['Decisions engine', [['matching', 'Call matching', 'call-matching'], ['allocation', 'Tuner allocation', 'tuner']]],
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
  const table = document.querySelector('.visual-activity-action-tooltip-example table');
  const columns = [...table.querySelectorAll('thead th')].map((heading) => ({
    id: heading.dataset.column, label: heading.textContent
  }));
  const columnGroup = document.createElement('colgroup');
  const columnElements = columns.map(() => document.createElement('col'));
  columnGroup.append(...columnElements);
  table.prepend(columnGroup);
  const applyWidths = () => {
    const widths = tableDefaults.fittedWidths('activity', columns,
      columns.map((column) => tableDefaults.width('activity', column)), {}, table.parentElement.clientWidth);
    columnElements.forEach((column, index) => { column.style.width = `${widths[index]}px`; });
    const total = widths.reduce((sum, width) => sum + width, 0);
    table.style.width = `${total}px`;
    table.style.minWidth = `${total}px`;
  };
  applyWidths();
  tooltip.showPopover();
  const positionTooltip = () => {
    const anchor = trigger.getBoundingClientRect();
    const panel = tooltip.getBoundingClientRect();
    tooltip.style.left = `${Math.max(8, Math.min(anchor.right - panel.width,
      window.innerWidth - panel.width - 8))}px`;
    tooltip.style.top = `${anchor.bottom + 6}px`;
  };
  positionTooltip();
  window.addEventListener('resize', () => { applyWidths(); positionTooltip(); });
}
if (view === 'activity-filters' || view === 'scanner') {
  const bindVisualAnchoredPopover = (triggerId, panelId) => {
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
  if (view === 'activity-filters') {
    bindVisualAnchoredPopover('#visual-activity-destination-trigger', '#visual-activity-destination-popover');
    bindVisualAnchoredPopover('#visual-activity-more-trigger', '#visual-activity-more-panel');
  } else {
    bindVisualAnchoredPopover('.scanner-scan-trigger', '#visual-scanner-scan-lists');
    const scannerPage = document.querySelector('.visual-scanner-example .scanner-page');
    const queueDisclosure = scannerPage.querySelector('.scanner-queue-disclosure');
    const queueViewport = window.matchMedia('(max-width: 680px), (max-height: 500px) and (pointer: coarse)');
    let phoneQueueOpen = false;
    const updateQueueDisclosure = () => { queueDisclosure.open = queueViewport.matches ? phoneQueueOpen : true; };
    queueDisclosure.addEventListener('toggle', () => {
      if (queueViewport.matches) phoneQueueOpen = queueDisclosure.open;
    });
    queueViewport.addEventListener('change', updateQueueDisclosure);
    updateQueueDisclosure();
    const scannerBar = scannerPage.querySelector('#scanner-playback-bar');
    const volume = scannerBar.querySelector('.ui-audio-range');
    const volumeOutput = scannerBar.querySelector('.ui-audio-volume-output');
    volume.addEventListener('input', () => {
      const percent = Math.round(Number(volume.value) * 100);
      volume.setAttribute('aria-valuetext', `${percent} percent`);
      volumeOutput.textContent = `${percent}%`;
    });
    if (parameters.get('state') === 'idle') {
      const display = scannerPage.querySelector('.scanner-display');
      const height = display.getBoundingClientRect().height;
      display.classList.add('is-idle', 'ui-content-idle');
      const values = [...display.querySelectorAll('.scanner-call-kind, .scanner-call-title, .scanner-call-subtitle, ' +
        '.scanner-field-value, .scanner-field-link, .scanner-quality-value, .scanner-group-value')];
      [...display.querySelectorAll('.scanner-call-intro, .scanner-participant-grid, .scanner-participant, ' +
        '.scanner-participant-fields, .scanner-source-row, .scanner-field-grid, .scanner-field'), ...values]
        .map(element => ({ element, height: element.getBoundingClientRect().height }))
        .forEach(({ element, height: elementHeight }) => { element.style.minHeight = `${elementHeight}px`; });
      values.forEach(value => {
        value.removeAttribute('title');
        if (value.classList.contains('scanner-group-value')) value.removeAttribute('aria-label');
        value.textContent = value.classList.contains('scanner-call-title') ? 'Waiting for a call' : '—';
      });
      display.querySelectorAll('button').forEach(button => { button.disabled = true; });
      display.querySelectorAll('.scanner-quality-bars i').forEach(bar => bar.classList.remove('active'));
      display.querySelector('.scanner-quality-bars').setAttribute('aria-label', 'Voice quality not measured');
      display.querySelector('.scanner-audio-wave').classList.add('paused');
      display.querySelector('.scanner-audio-wave').setAttribute('aria-label', 'Voice spectrum idle');
      display.querySelectorAll('.scanner-audio-wave i').forEach(bar => { bar.style.height = '3px'; });
      display.style.minHeight = `${height}px`;
      scannerPage.querySelector('.scanner-relative-age').textContent = 'Waiting for a call';
    }
  }
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
  const directory = example.querySelector('.alias-coverage');
  const header = directory.querySelector('.alias-coverage-header');
  const description = document.createElement('p');
  description.textContent = 'Find heard aliases and activity without a match.';
  header.querySelector('.ui-heading-group').append(description);
  if (new URLSearchParams(location.search).get('state') === 'empty') {
    const toolbar = header.querySelector('.alias-coverage-toolbar');
    toolbar.classList.add('alias-coverage-awaiting-selection');
    toolbar.querySelectorAll('.alias-coverage-field')[1].remove();
    const select = toolbar.querySelector('select');
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = 'Select an alias list';
    placeholder.selected = true;
    select.prepend(placeholder);
    const prompt = document.createElement('p');
    prompt.className = 'alias-coverage-selection-prompt ui-muted';
    prompt.textContent = 'Choose a list to get started';
    directory.replaceChildren(header, prompt);
  }
  example.replaceChildren(directory);
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
if(view === 'tuner-spectrum') {
  const spectrumOptions = document.querySelector('.visual-tuner-spectrum-example .tuner-spectrum-options-summary');
  const compactSpectrum = window.matchMedia('(max-width: 760px)');
  const syncSpectrumOptionsLabel = () => {
    const text = compactSpectrum.matches ? 'More spectrum actions' : 'Display options';
    spectrumOptions?.setAttribute('aria-label', text);
    if(spectrumOptions) spectrumOptions.title = text;
  };
  syncSpectrumOptionsLabel();
  compactSpectrum.addEventListener('change', syncSpectrumOptionsLabel);
}
if(view === 'tuners') {
  const toolbar = document.querySelector('.visual-tuners-example .tuner-spectrum-toolbar');
  const options = document.querySelector('.visual-tuner-spectrum-example .tuner-spectrum-options').cloneNode(true);
  options.querySelector('.spectrum-browse-mobile-options-actions').remove();
  const header = options.querySelector('.tuner-spectrum-options-header');
  header.querySelector('strong').textContent = 'Signal display';
  header.querySelector('span:last-child').remove();
  options.querySelector('.settings-card:last-child').remove();
  options.querySelector('.settings-card:first-child .admin-toggle-control').remove();
  const fft = options.querySelectorAll('.settings-card')[1];
  fft.querySelector('.admin-toggle-control:last-child').remove();
  const actions = document.createElement('div');
  actions.className = 'tuner-spectrum-toolbar-actions';
  actions.append(options);
  toolbar.append(actions);
  const summary = options.querySelector('summary');
  options.addEventListener('toggle', () => summary.setAttribute('aria-expanded', String(options.open)));
  options.addEventListener('keydown', (event) => {
    if(event.key !== 'Escape' || !options.open) return;
    event.preventDefault();
    options.open = false;
    summary.focus();
  });
  options.querySelectorAll('.ui-toggle input').forEach((input) => input.addEventListener('change', () => {
    input.closest('.ui-toggle').querySelector('.ui-toggle-state').textContent = input.checked ? 'On' : 'Off';
  }));
}
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
    workflow.setDirty(false);
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
  pager.querySelectorAll('button').forEach(control => control.classList.add('ui-button-compact'));
  update();
  document.querySelector('[data-workflow-pager]').append(pager);
}
