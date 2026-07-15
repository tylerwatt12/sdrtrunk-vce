let route = new URLSearchParams(window.location.search);
const content = document.getElementById('content');
const tableOnly = route.get('layout') === 'table';
const TABLE_WIDTH_COOKIE = 'sdrtrunk_table_widths_v1';
const TABLE_WIDTH_MINIMUM = 48;
const TABLE_WIDTH_MAXIMUM = 1200;
const SIGNAL_OFFLINE_MILLISECONDS = 45_000;
const SIGNAL_RANGES = Object.freeze([
  ['1h', '1 hour'], ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days']
]);
const CHANNEL_TAG_DISPLAY = Object.freeze({
  CONVENTIONAL: { abbreviation: 'CONV', description: 'Conventional channel' },
  CONFIGURED: { abbreviation: 'CFG', description: 'Configured frequency' },
  CONTROL: { abbreviation: 'CC', description: 'Observed control channel', className: 'role-primary' },
  CURRENT_CONTROL: { abbreviation: 'CC', description: 'Current control channel', className: 'role-primary' },
  ALTERNATE_CONTROL: { abbreviation: 'ACC', description: 'Alternate control channel', className: 'role-secondary' },
  VOICE: { abbreviation: 'VC', description: 'Observed voice traffic', className: 'role-voice' },
  DATA: { abbreviation: 'DAT', description: 'Observed data traffic', className: 'role-data' },
  DATA_ANNOUNCED: { abbreviation: 'DAT-A', description: 'Announced data channel', className: 'role-data-announced' }
});
const TABLE_COLUMN_DEFAULT_WIDTHS = {
  'action': 82,
  'affiliated': 70,
  'alias': 170,
  'band': 54,
  'calls': 66,
  'control-frequency': 94,
  'count': 66,
  'decoder': 78,
  'encrypted': 52,
  'encryption': 92,
  'event': 115,
  'first-seen': 142,
  'frequency': 94,
  'group': 135,
  'last-seen': 142,
  'lcn': 68,
  'name': 175,
  'radio': 82,
  'radio-alias': 165,
  'rfss': 66,
  'signal': 82,
  'decode-health': 72,
  'site': 66,
  'source': 82,
  'source-alias': 165,
  'state': 82,
  'status': 116,
  'system': 106,
  'talker-alias': 160,
  'talkgroup-id': 76,
  'talkgroup-name': 175,
  'target': 82,
  'target-alias': 165,
  'time': 142,
  'wacn': 108
};
const SERVER_TABLE_DEFAULT_SORTS = {
  systems: 'last_seen',
  sites: 'last_seen',
  talkgroups: 'calls',
  radios: 'calls',
  'talker-aliases': 'talker_alias',
  'talkgroup-radios': 'last_seen',
  'radio-talkgroups': 'last_seen',
  conventional: 'frequency'
};
let serviceStatus = null;
let tableWidthPreferences = readTableWidthPreferences();

if (tableOnly) {
  document.body.classList.add('table-only');
}

function node(tag, className, textValue) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (textValue !== undefined && textValue !== null) element.textContent = String(textValue);
  return element;
}

function readCookie(name) {
  const prefix = `${name}=`;
  const value = document.cookie.split(';').map((entry) => entry.trim())
    .find((entry) => entry.startsWith(prefix));
  return value ? decodeURIComponent(value.slice(prefix.length)) : null;
}

function readTableWidthPreferences() {
  try {
    const value = JSON.parse(readCookie(TABLE_WIDTH_COOKIE) || '{}');
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch (error) {
    return {};
  }
}

function writeTableWidthPreferences() {
  const encoded = encodeURIComponent(JSON.stringify(tableWidthPreferences));
  if (encoded.length > 3800) return;
  document.cookie = `${TABLE_WIDTH_COOKIE}=${encoded}; Max-Age=31536000; Path=/; SameSite=Lax`;
}

function fragment(...children) {
  const result = document.createDocumentFragment();
  children.flat().filter(Boolean).forEach((child) => result.append(child));
  return result;
}

function number(value) {
  return new Intl.NumberFormat().format(Number(value || 0));
}

function hex(value, width = 0) {
  if (value === null || value === undefined || value === '') return '';
  return Number(value).toString(16).toUpperCase().padStart(width, '0');
}

function hexDecimal(value, width = 0) {
  if (value === null || value === undefined || value === '') return '';
  return `${hex(value, width)} (${Number(value)})`;
}

function frequency(value) {
  return value ? (Number(value) / 1000000).toFixed(5) : '';
}

function dateTime(value) {
  return value ? new Date(Number(value)).toLocaleString() : '';
}

function yesNo(value) {
  return Number(value) ? 'Yes' : '';
}

function yesNoKnown(value) {
  return value === null || value === undefined || value === '' ? '' : (Number(value) ? 'Yes' : 'No');
}

function checkbox(checked) {
  const input = node('input', 'status-checkbox');
  input.type = 'checkbox';
  input.checked = Boolean(checked);
  input.disabled = true;
  return input;
}

function protocol(value) {
  const values = { 1: 'P25 Phase 1', 2: 'P25 Phase 2', 3: 'DMR', 4: 'NXDN', 10: 'NBFM', 11: 'AM' };
  return values[Number(value)] || value || '';
}

function aliasLabel(row, prefix = 'alias_') {
  return row[`${prefix}name`] || '';
}

function systemLabel(row) {
  const wacn = hex(row.wacn, 5);
  const system = hex(row.system_id, 3);
  return wacn && system ? `${wacn}-${system}` : wacn || system;
}

function siteLabel(row) {
  const identity = `${hexDecimal(row.rfss, 2)}-${hexDecimal(row.site, 2)}`;
  return row.channel_name || `${systemLabel(row)} ${identity}`;
}

function badge(label, className = '', title = '') {
  const element = node('span', `badge ${className}`.trim(), label);
  if (title) element.title = title;
  return element;
}

function stateBadge(value) {
  const state = String(value || '').toUpperCase();
  return badge(state ? state[0] + state.slice(1).toLowerCase() : '', `state-${state.toLowerCase()}`);
}

function neighborStatus(value) {
  const status = String(value || '').toUpperCase();
  const labels = [];
  if (status.includes('VALID')) labels.push(['Valid', 'state-current']);
  if (status.includes('ACTIVE RFSS')) labels.push(['RFSS Linked', 'state-current']);
  if (status.includes('FAILURE')) labels.push(['Failure', 'state-stale']);
  if (status.includes('CONVENTIONAL')) labels.push(['Conventional', '']);
  if (!labels.length && status) labels.push([value, '']);
  return fragment(...labels.map(([label, className]) => badge(label, className)));
}

function scope(row) {
  return { wacn: hex(row.wacn, 5), system: hex(row.system_id, 3) };
}

function href(view, values = {}) {
  const parameters = new URLSearchParams();
  parameters.set('view', view);
  Object.entries(values).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') parameters.set(key, String(value));
  });
  return `/?${parameters.toString()}`;
}

function currentHref(overrides = {}) {
  const parameters = new URLSearchParams(route);
  Object.entries(overrides).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '') parameters.delete(key);
    else parameters.set(key, String(value));
  });
  return `/?${parameters.toString()}`;
}

function anchor(label, target, className) {
  const element = node('a', className, label);
  element.href = target;
  return element;
}

function externalAnchor(label, target) {
  const element = anchor(label, target);
  element.target = '_blank';
  element.rel = 'noopener noreferrer';
  return element;
}

function systemLink(row, label = systemLabel(row)) {
  return anchor(label, href('system', { ...scope(row), tab: 'info' }));
}

function siteLink(row, label = siteLabel(row)) {
  return anchor(label, href('site', { guid: row.guid, tab: 'info' }));
}

function talkgroupLink(row, id = row.talkgroup_id, label) {
  if (id === null || id === undefined) return '';
  return anchor(label || String(id), href('talkgroup', { ...scope(row), id, tab: 'info' }));
}

function radioLink(row, id = row.radio_id, label) {
  if (id === null || id === undefined) return '';
  return anchor(label || String(id), href('radio', { ...scope(row), id, tab: 'info' }));
}

function talkgroupAliasLink(row, id, prefix = 'alias_') {
  if (id === null || id === undefined) return '';
  const name = row[`${prefix}name`];
  return name ? talkgroupLink(row, id, name) : '';
}

function channelTagSet(...values) {
  const tags = new Set();
  values.flat().forEach((value) => String(value || '').split(',').forEach((tag) => {
    const normalized = tag.trim().toUpperCase();
    if (normalized) tags.add(normalized);
  }));
  return tags;
}

function channelTagBadge(tag) {
  const display = CHANNEL_TAG_DISPLAY[tag];
  return badge(display.abbreviation, display.className, display.description);
}

function channelTags(row) {
  const observed = channelTagSet(row.tags);
  const current = channelTagSet(row.current_tags);
  const tags = [];
  if (current.has('CURRENT_CONTROL')) tags.push(channelTagBadge('CURRENT_CONTROL'));
  else if (observed.has('CONTROL')) tags.push(channelTagBadge('CONTROL'));
  if (observed.has('ALTERNATE_CONTROL') || current.has('ALTERNATE_CONTROL')) {
    tags.push(channelTagBadge('ALTERNATE_CONTROL'));
  }
  if (observed.has('VOICE')) tags.push(channelTagBadge('VOICE'));
  if (observed.has('DATA')) tags.push(channelTagBadge('DATA'));
  if (observed.has('DATA_ANNOUNCED') && !observed.has('DATA')) tags.push(channelTagBadge('DATA_ANNOUNCED'));
  return tags.length ? fragment(...tags) : badge('Unknown', 'state-historical');
}

function visibleLiveChannelTags(row) {
  const tags = channelTagSet(row.tags);
  const visible = ['CURRENT_CONTROL', 'ALTERNATE_CONTROL', 'VOICE', 'DATA', 'DATA_ANNOUNCED']
    .filter((tag) => tags.has(tag) && (tag !== 'DATA_ANNOUNCED' || !tags.has('DATA')));
  if (tags.has('CONVENTIONAL')) visible.unshift('CONVENTIONAL');
  if (tags.has('CONFIGURED') && visible.length === 0) visible.push('CONFIGURED');
  return visible;
}

function channelTagText(row) {
  return visibleLiveChannelTags(row).map((tag) => CHANNEL_TAG_DISPLAY[tag].abbreviation).join(' + ');
}

function channelTagTitle(row) {
  return visibleLiveChannelTags(row).map((tag) => CHANNEL_TAG_DISPLAY[tag].description).join(' + ');
}

function pageHeader(title, subtitle) {
  const wrapper = node('div', 'page-header');
  const labels = node('div');
  labels.append(node('h1', 'page-title', title));
  if (subtitle) labels.append(node('div', 'page-subtitle', subtitle));
  wrapper.append(labels);
  return wrapper;
}

function statsLoggingState() {
  const current = serviceStatus?.statsLogging;
  const database = serviceStatus?.database || {};
  const logger = new Map((database.logger || []).map((row) => [row.key, row.value]));
  const persistedLastWrite = Number(logger.get('last_successful_write_ms') || 0);
  if (current) {
    return {
      available: true,
      summaryConfigured: Boolean(current.summaryConfigured),
      historyConfigured: Boolean(current.detailedHistoryConfigured),
      summaryActive: Boolean(current.summaryActive),
      historyActive: Boolean(current.detailedHistoryActive),
      historyRetained: Boolean(database.detailedHistoryAvailable),
      lastHistoryMs: Number(database.lastDetailedHistoryMs || 0),
      lastSuccessfulWriteMs: Math.max(Number(current.lastSuccessfulWriteMs || 0), persistedLastWrite),
      state: String(current.state || ''),
      lastError: current.lastError || ''
    };
  }
  if (serviceStatus) {
    const summary = Boolean(database.statsLoggingEnabled);
    const history = Boolean(database.detailedHistoryEnabled);
    return { available: true, summaryConfigured: summary, historyConfigured: history,
      summaryActive: summary, historyActive: summary && history,
      historyRetained: Boolean(database.detailedHistoryAvailable),
      lastHistoryMs: Number(database.lastDetailedHistoryMs || 0), lastSuccessfulWriteMs: persistedLastWrite,
      state: summary ? 'RUNNING' : 'DISABLED', lastError: '' };
  }
  return { available: false, summaryConfigured: false, historyConfigured: false,
    summaryActive: false, historyActive: false, historyRetained: false, lastHistoryMs: 0,
    lastSuccessfulWriteMs: 0, state: '', lastError: '' };
}

function detailedHistoryAvailable() {
  const logging = statsLoggingState();
  return !logging.available || logging.historyActive || logging.historyRetained;
}

function databaseLoggingNotice(view) {
  if (tableOnly || ['live', 'credits'].includes(view)) return null;
  const logging = statsLoggingState();
  if (!logging.available) return node('div', 'logging-notice warning',
    'Logging status is unavailable. Database-backed views may not be current.');
  if (!logging.summaryActive) {
    const state = logging.summaryConfigured && logging.state ? ` (${logging.state.toLowerCase()})` : '';
    const message = logging.summaryConfigured ?
      `Summary logging is not running${state}. Database-backed views remain available but are not updating.` :
      'Summary logging is off. Database-backed views remain available but are not updating.';
    const detail = logging.summaryConfigured && logging.lastError ? ` ${logging.lastError}` : '';
    const lastWrite = logging.lastSuccessfulWriteMs ?
      ` Last successful summary write: ${dateTime(logging.lastSuccessfulWriteMs)}.` : '';
    const activityState = logging.historyRetained ?
      ` Activity pages show retained history${logging.lastHistoryMs ?
        ` through ${dateTime(logging.lastHistoryMs)}` : ''} and are not updating.` :
      ' Activity pages require detailed history and are unavailable.';
    return node('div', 'logging-notice warning',
      `${message}${detail}${lastWrite}${activityState} ` +
      'Live Systems and audio playback do not require logging and remain available.');
  }
  if (!logging.historyActive) {
    const reason = logging.historyConfigured ? 'not currently running' : 'off';
    if (logging.historyRetained) {
      const through = logging.lastHistoryMs ? ` through ${dateTime(logging.lastHistoryMs)}` : '';
      return node('div', 'logging-notice',
        `Summary logging is running. Detailed history is ${reason}; Activity pages show retained data${through} ` +
        'and are not updating.');
    }
    return node('div', 'logging-notice',
      `Summary logging is running. Detailed history is ${reason}; Activity tabs are unavailable.`);
  }
  return null;
}

function tabs(items, active) {
  const bar = node('nav', 'tabs');
  bar.setAttribute('aria-label', 'Section navigation');
  items.forEach((item) => {
    if (item.disabled) {
      const disabled = node('span', `disabled ${item.id === active ? 'active' : ''}`.trim(), item.label);
      disabled.setAttribute('aria-disabled', 'true');
      disabled.title = item.disabledReason || 'Detailed history is not running';
      bar.append(disabled);
    } else {
      const link = anchor(item.label, item.href, item.id === active ? 'active' : '');
      bar.append(link);
    }
  });
  return bar;
}

function section(title, child) {
  const wrapper = node('section', 'section');
  wrapper.append(node('div', 'section-title', title));
  if (child) wrapper.append(child);
  return wrapper;
}

function valueNode(value) {
  return value instanceof Node ? value : document.createTextNode(value === null || value === undefined ? '' : String(value));
}

function tableColumnKey(column, index) {
  return column.id || column.key || column.sort || String(column.label || `column-${index}`)
    .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

function tableSortValue(row, column) {
  if (column.sortValue) return column.sortValue(row);
  if (column.key) return row[column.key];
  const rendered = column.render ? column.render(row) : '';
  if (rendered instanceof HTMLInputElement && rendered.type === 'checkbox') return rendered.checked;
  return rendered instanceof Node ? rendered.textContent : rendered;
}

function compareTableValues(left, right) {
  const leftEmpty = left === null || left === undefined || left === '';
  const rightEmpty = right === null || right === undefined || right === '';
  if (leftEmpty || rightEmpty) return leftEmpty === rightEmpty ? 0 : (leftEmpty ? 1 : -1);
  if (typeof left === 'number' && typeof right === 'number' && Number.isFinite(left) && Number.isFinite(right)) {
    return left - right;
  }
  if (typeof left === 'boolean' && typeof right === 'boolean') return Number(left) - Number(right);
  return String(left).localeCompare(String(right), undefined, { numeric: true, sensitivity: 'base' });
}

function renderTableRow(data, columns, rowKey) {
  const row = node('tr');
  if (rowKey) {
    const value = rowKey(data);
    if (value !== null && value !== undefined) row.dataset.id = String(value);
  }
  columns.forEach((column) => {
    const cell = node('td', column.className || '');
    const value = column.render ? column.render(data) : data[column.key];
    cell.append(valueNode(value));
    row.append(cell);
  });
  return row;
}

function defaultTableColumnWidth(column) {
  if (Number.isFinite(Number(column.width))) return Number(column.width);
  const semanticWidth = TABLE_COLUMN_DEFAULT_WIDTHS[tableColumnKey(column, 0)];
  if (semanticWidth) return semanticWidth;
  if (String(column.className || '').includes('alias-cell')) return 190;
  if (String(column.className || '').includes('numeric')) return 100;
  return Math.max(90, Math.min(220, String(column.label || '').length * 9 + 34));
}

function setTableColumnWidths(columnElements, widths) {
  const total = widths.reduce((sum, width) => sum + width, 0) || 1;
  widths.forEach((width, index) => {
    columnElements[index].style.width = `${(width / total * 100).toFixed(4)}%`;
  });
}

function applyPreferredTableWidths(element, columns, columnElements, tableType) {
  const preferences = tableWidthPreferences[tableType];
  const widths = columns.map((column, index) => {
    const savedWidth = preferences && typeof preferences === 'object' ?
      preferences[tableColumnKey(column, index)] : null;
    const width = Number(savedWidth || defaultTableColumnWidth(column));
    return Number.isFinite(width) && width >= TABLE_WIDTH_MINIMUM && width <= TABLE_WIDTH_MAXIMUM ?
      Math.round(width) : defaultTableColumnWidth(column);
  });
  setTableColumnWidths(columnElements, widths);
  element.style.width = '100%';
  element.style.minWidth = '0';
}

function addColumnResizers(element, columns, columnElements, headers, tableType) {
  const saveWidths = (widths) => {
    const saved = {};
    columns.forEach((column, index) => {
      saved[tableColumnKey(column, index)] = Math.round(widths[index]);
    });
    tableWidthPreferences[tableType] = saved;
    writeTableWidthPreferences();
  };
  const resizeColumns = (index, startingWidths, requestedDelta) => {
    const widths = [...startingWidths];
    const original = startingWidths[index];
    const adjacentIndex = index < widths.length - 1 ? index + 1 : index - 1;
    if (adjacentIndex >= 0) {
      const adjacent = startingWidths[adjacentIndex];
      const minimumWidth = Math.min(TABLE_WIDTH_MINIMUM, (original + adjacent) / 2);
      const minimumDelta = Math.max(minimumWidth - original, adjacent - TABLE_WIDTH_MAXIMUM);
      const maximumDelta = Math.min(TABLE_WIDTH_MAXIMUM - original, adjacent - minimumWidth);
      const appliedDelta = Math.max(minimumDelta, Math.min(maximumDelta, requestedDelta));
      widths[index] = original + appliedDelta;
      widths[adjacentIndex] = adjacent - appliedDelta;
    }
    setTableColumnWidths(columnElements, widths);
    return widths;
  };
  headers.forEach((header, index) => {
    const handle = node('span', 'column-resizer');
    handle.setAttribute('role', 'separator');
    handle.setAttribute('aria-orientation', 'vertical');
    handle.setAttribute('aria-label', `Resize ${columns[index].label} column`);
    handle.setAttribute('aria-valuemin', String(TABLE_WIDTH_MINIMUM));
    handle.setAttribute('aria-valuemax', String(TABLE_WIDTH_MAXIMUM));
    handle.setAttribute('aria-valuenow', String(Math.round(Number(columns[index].width) || TABLE_WIDTH_MINIMUM)));
    handle.tabIndex = 0;
    handle.addEventListener('focus', () => handle.setAttribute('aria-valuenow',
      String(Math.round(header.getBoundingClientRect().width))));
    handle.addEventListener('keydown', (event) => {
      if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      event.preventDefault();
      event.stopPropagation();
      const startingWidths = headers.map((candidate) => Math.round(candidate.getBoundingClientRect().width));
      const widths = resizeColumns(index, startingWidths, event.key === 'ArrowLeft' ? -10 : 10);
      handle.setAttribute('aria-valuenow', String(Math.round(widths[index])));
      saveWidths(widths);
    });
    handle.addEventListener('pointerdown', (event) => {
      if (event.button !== 0) return;
      event.preventDefault();
      event.stopPropagation();
      const startingWidths = headers.map((candidate) => Math.round(candidate.getBoundingClientRect().width));
      const startingX = event.clientX;
      let resizedWidths = startingWidths;
      const updateWidth = (clientX) => {
        resizedWidths = resizeColumns(index, startingWidths, clientX - startingX);
        handle.setAttribute('aria-valuenow', String(Math.round(resizedWidths[index])));
      };
      const pointerMove = (moveEvent) => updateWidth(moveEvent.clientX);
      const pointerUp = (upEvent) => {
        if (Number.isFinite(upEvent.clientX)) updateWidth(upEvent.clientX);
        handle.removeEventListener('pointermove', pointerMove);
        handle.removeEventListener('pointerup', pointerUp);
        handle.removeEventListener('pointercancel', pointerUp);
        saveWidths(resizedWidths);
      };
      handle.setPointerCapture(event.pointerId);
      handle.addEventListener('pointermove', pointerMove);
      handle.addEventListener('pointerup', pointerUp);
      handle.addEventListener('pointercancel', pointerUp);
    });
    header.append(handle);
  });
}

function table(rows, columns, emptyText = 'No rows', options = {}) {
  const tableType = options.type;
  if (!tableType) throw new Error('A stable table type is required');
  const wrapper = node('div', 'table-wrap');
  const element = node('table', 'data-table resizable-table');
  element.dataset.tableType = tableType;
  const columnGroup = node('colgroup');
  const columnElements = columns.map(() => node('col'));
  columnGroup.append(...columnElements);
  const head = node('thead');
  const headRow = node('tr');
  const headers = [];
  const body = node('tbody');
  let dataRows = [...(rows || [])];
  let clientSort = null;

  const renderBody = () => {
    body.replaceChildren();
    const orderedRows = clientSort ? [...dataRows].sort((left, right) => {
      const result = compareTableValues(tableSortValue(left, clientSort.column),
        tableSortValue(right, clientSort.column));
      return clientSort.direction === 'asc' ? result : -result;
    }) : dataRows;
    if (!orderedRows.length) {
      const row = node('tr');
      const cell = node('td', 'empty', emptyText);
      cell.colSpan = columns.length;
      row.append(cell);
      body.append(row);
      return;
    }
    orderedRows.forEach((data) => body.append(renderTableRow(data, columns, options.rowKey)));
  };

  const updateSortIndicators = () => {
    const effectiveServerSort = route.get('sort') || options.defaultSort;
    const effectiveServerDirection = route.get('direction') || options.defaultDirection || 'desc';
    headers.forEach((header, index) => {
      const column = columns[index];
      let direction = 'none';
      if (options.serverSort && column.sort && effectiveServerSort === column.sort) {
        direction = effectiveServerDirection === 'asc' ? 'ascending' : 'descending';
      } else if (clientSort?.column === column) {
        direction = clientSort.direction === 'asc' ? 'ascending' : 'descending';
      }
      header.setAttribute('aria-sort', direction);
    });
  };

  columns.forEach((column, index) => {
    const header = node('th', column.className || '');
    const serverSortable = options.serverSort && column.sort;
    if (serverSortable) {
      const currentSort = route.get('sort') || options.defaultSort;
      const currentDirection = route.get('direction') || options.defaultDirection || 'desc';
      const direction = currentSort === column.sort && currentDirection === 'desc' ? 'asc' : 'desc';
      header.append(anchor(column.label, currentHref({ sort: column.sort, direction, offset: null }),
        'table-sort-control'));
    } else {
      const control = node('button', 'table-sort-control', column.label);
      control.type = 'button';
      control.addEventListener('click', () => {
        clientSort = clientSort?.column === column ?
          { column, direction: clientSort.direction === 'asc' ? 'desc' : 'asc' } :
          { column, direction: 'asc' };
        updateSortIndicators();
        renderBody();
      });
      header.append(control);
    }
    headers.push(header);
    headRow.append(header);
  });

  head.append(headRow);
  element.append(columnGroup, head, body);
  wrapper.append(element);
  applyPreferredTableWidths(element, columns, columnElements, tableType);
  addColumnResizers(element, columns, columnElements, headers, tableType);
  updateSortIndicators();
  renderBody();
  wrapper.tableController = {
    addRow(data, { prepend = true, limit = null } = {}) {
      if (prepend) dataRows.unshift(data);
      else dataRows.push(data);
      if (limit && dataRows.length > limit) dataRows = dataRows.slice(0, limit);
      if (clientSort) {
        renderBody();
        return;
      }
      const rendered = renderTableRow(data, columns, options.rowKey);
      if (body.querySelector('.empty')) body.replaceChildren(rendered);
      else if (prepend) body.prepend(rendered);
      else body.append(rendered);
      while (body.children.length > dataRows.length) {
        if (prepend) body.lastElementChild.remove();
        else body.firstElementChild.remove();
      }
    },
    rows: () => dataRows,
    render: renderBody
  };
  return wrapper;
}

function keyValues(entries) {
  const list = node('dl', 'key-values');
  entries.forEach(([label, value]) => {
    list.append(node('dt', '', label));
    const detail = node('dd');
    detail.append(valueNode(value));
    list.append(detail);
  });
  return list;
}

function metrics(values) {
  const band = node('section', 'summary-band');
  values.forEach(([label, value]) => {
    const metric = node('div', 'metric');
    metric.append(node('span', '', label), node('strong', '', number(value)));
    band.append(metric);
  });
  return band;
}

function searchBar(placeholder = 'Search') {
  const form = node('form', 'toolbar');
  form.method = 'get';
  for (const [key, value] of route.entries()) {
    if (key === 'q' || key === 'offset') continue;
    const hidden = node('input');
    hidden.type = 'hidden';
    hidden.name = key;
    hidden.value = value;
    form.append(hidden);
  }
  const input = node('input');
  input.type = 'search';
  input.name = 'q';
  input.value = route.get('q') || '';
  input.placeholder = placeholder;
  input.setAttribute('aria-label', placeholder);
  form.append(input, node('button', '', 'Search'));
  if (route.get('q')) form.append(anchor('Clear', currentHref({ q: null, offset: null }), 'button secondary'));
  return form;
}

function pager(page) {
  const bar = node('div', 'pager');
  const offset = Number(page.offset || 0);
  const limit = Number(page.limit || 100);
  bar.append(node('span', 'muted', `Rows ${offset + (page.rows.length ? 1 : 0)}-${offset + page.rows.length}`));
  bar.append(offset > 0 ? anchor('Previous', currentHref({ offset: Math.max(0, offset - limit) }), 'button secondary') :
    node('span', 'button disabled', 'Previous'));
  bar.append(page.hasMore ? anchor('Next', currentHref({ offset: page.nextOffset }), 'button secondary') :
    node('span', 'button disabled', 'Next'));
  return bar;
}

function pagedSection(title, page, columns, searchPlaceholder, tableType) {
  return fragment(searchPlaceholder ? searchBar(searchPlaceholder) : null,
    (() => {
      const block = section(title, table(page.rows, columns, 'No rows', {
        type: tableType,
        serverSort: true,
        defaultSort: SERVER_TABLE_DEFAULT_SORTS[tableType],
        defaultDirection: 'desc'
      }));
      block.append(pager(page));
      return block;
    })());
}

function actionCounts(row) {
  return Object.entries(row)
    .filter(([key, value]) => key.endsWith('_count') && key !== 'grant_count' && Number(value) > 0)
    .map(([key, value]) => [key.replace(/_count$/, '').replaceAll('_', ' '), Number(value)]);
}

function withoutGrantActions(rows) {
  return (rows || []).filter((row) => String(row.action || '').toUpperCase() !== 'GRANT');
}

const actionColors = [
  '#0b7168', '#2f6da5', '#cc7a00', '#9d174d', '#6b4fa3', '#3c7a3c', '#a65a3a', '#526778',
  '#b33b5e', '#0085a1', '#7c6b2f', '#4d7f7b', '#865d9c', '#8b5d2e', '#556b2f', '#737c86'
];

function actionPie(rows) {
  const actions = withoutGrantActions(rows).filter((row) => Number(row.count) > 0);
  const total = actions.reduce((sum, row) => sum + Number(row.count), 0);
  if (!total) return node('div', 'empty', 'No actions recorded in the last 24 hours');

  let start = 0;
  const segments = actions.map((row, index) => {
    const end = start + (Number(row.count) / total * 100);
    const segment = `${actionColors[index % actionColors.length]} ${start}% ${end}%`;
    start = end;
    return segment;
  });

  const chart = node('div', 'action-pie');
  chart.style.backgroundImage = `conic-gradient(${segments.join(', ')})`;
  chart.setAttribute('role', 'img');
  chart.setAttribute('aria-label', `24 hour actions, ${number(total)} total`);

  const legend = node('div', 'action-legend');
  actions.forEach((row, index) => {
    const item = node('div', 'action-legend-item');
    const swatch = node('span', 'action-swatch');
    swatch.style.backgroundColor = actionColors[index % actionColors.length];
    const label = node('span', 'action-label', String(row.action).replaceAll('_', ' '));
    const percentage = Number(row.count) / total * 100;
    item.append(swatch, label, node('span', 'action-value', `${number(row.count)} · ${percentage.toFixed(1)}%`));
    legend.append(item);
  });

  return fragment(chart, legend);
}

function hourlyLineGraph(rows) {
  const values = (rows || []).map((row) => ({
    hour: Number(row.hour_ms),
    calls: Number(row.call_count || 0)
  }));
  if (!values.length) return node('div', 'empty', 'No hourly activity data');

  const width = 960;
  const height = 270;
  const margin = { top: 18, right: 20, bottom: 42, left: 55 };
  const plotWidth = width - margin.left - margin.right;
  const plotHeight = height - margin.top - margin.bottom;
  const maximum = Math.max(1, ...values.map((value) => value.calls));
  const roundedMaximum = Math.max(4, Math.ceil(maximum / 4) * 4);
  const svgNamespace = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNamespace, 'svg');
  svg.setAttribute('class', 'activity-line-svg');
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', 'Calls per hour for the last 24 hours');

  const svgNode = (tag, attributes = {}, textValue) => {
    const element = document.createElementNS(svgNamespace, tag);
    Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, String(value)));
    if (textValue !== undefined) element.textContent = String(textValue);
    return element;
  };

  for (let index = 0; index <= 4; index++) {
    const y = margin.top + plotHeight - (plotHeight * index / 4);
    const value = roundedMaximum * index / 4;
    svg.append(svgNode('line', { x1: margin.left, y1: y, x2: width - margin.right, y2: y,
      class: 'chart-grid-line' }));
    svg.append(svgNode('text', { x: margin.left - 10, y: y + 4, class: 'chart-axis-label',
      'text-anchor': 'end' }, number(value)));
  }

  const xFor = (index) => margin.left + (values.length === 1 ? plotWidth / 2 :
    plotWidth * index / (values.length - 1));
  [['calls', 'Calls']].forEach(([field, label]) => {
    const points = values.map((value, index) => ({ ...value, x: xFor(index),
      y: margin.top + plotHeight - (plotHeight * value[field] / roundedMaximum) }));
    const path = points.map((point, index) =>
      `${index ? 'L' : 'M'} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ');
    svg.append(svgNode('path', { d: path, class: `activity-line-path ${field}` }));
    points.forEach((point) => {
      const circle = svgNode('circle', { cx: point.x, cy: point.y, r: 3,
        class: `activity-line-point ${field}` });
      circle.append(svgNode('title', {},
        `${new Date(point.hour).toLocaleString()}: ${number(point[field])} ${label.toLowerCase()}`));
      svg.append(circle);
    });
  });
  values.forEach((value, index) => {
    if (index % 4 === 0 || index === values.length - 1) svg.append(svgNode('text', {
      x: xFor(index), y: height - 15, class: 'chart-axis-label', 'text-anchor': 'middle'
    }, new Date(value.hour).toLocaleTimeString([], { hour: 'numeric' })));
  });

  const wrapper = node('div', 'activity-line-chart');
  wrapper.append(svg);
  return wrapper;
}

function optionalNumber(value) {
  if (value === null || value === undefined || value === '') return Number.NaN;
  return Number(value);
}

function signalNumber(value) {
  const numeric = optionalNumber(value);
  return Number.isFinite(numeric) ? `${numeric.toFixed(1)} dBFS` : '—';
}

function percentNumber(value) {
  const numeric = optionalNumber(value);
  return Number.isFinite(numeric) ? `${numeric.toFixed(1)}%` : '—';
}

function elapsedLabel(timestamp, now = Date.now()) {
  const elapsed = Math.max(0, now - Number(timestamp || 0));
  if (!timestamp) return 'No samples';
  if (elapsed < 60_000) return `${Math.max(1, Math.round(elapsed / 1000))} sec ago`;
  if (elapsed < 3_600_000) return `${Math.round(elapsed / 60_000)} min ago`;
  if (elapsed < 86_400_000) return `${Math.round(elapsed / 3_600_000)} hr ago`;
  return `${Math.round(elapsed / 86_400_000)} days ago`;
}

function signalSiteState(site, now = Date.now()) {
  const observed = Number(site.last_observed_ms || 0);
  if (!observed || now - observed > SIGNAL_OFFLINE_MILLISECONDS) {
    return { label: 'Offline', className: 'offline', rank: 0 };
  }
  const decode = optionalNumber(site.decode_health_pct);
  if (!Number.isFinite(optionalNumber(site.average_signal_dbfs))) {
    return { label: 'No signal', className: 'poor', rank: 1 };
  }
  if (!Number.isFinite(decode)) return { label: 'Monitoring', className: 'unknown', rank: 2 };
  if (decode >= 95) return { label: 'Healthy', className: 'healthy', rank: 4 };
  if (decode >= 80) return { label: 'Degraded', className: 'degraded', rank: 3 };
  return { label: 'Poor', className: 'poor', rank: 1 };
}

function sharedSignalDomain(sites) {
  const values = [];
  (sites || []).forEach((site) => {
    (site.series || []).forEach((point) => {
      [point.minimum_signal_dbfs, point.maximum_signal_dbfs, point.average_signal_dbfs].forEach((value) => {
        const numeric = optionalNumber(value);
        if (Number.isFinite(numeric)) values.push(numeric);
      });
    });
  });
  if (!values.length) return { minimum: -100, maximum: -20 };
  let minimum = Math.floor((Math.min(...values) - 3) / 10) * 10;
  let maximum = Math.ceil((Math.max(...values) + 3) / 10) * 10;
  if (maximum - minimum < 20) {
    minimum -= 10;
    maximum += 10;
  }
  return { minimum, maximum: Math.min(0, maximum) };
}

function qualityHistoryChart(site, response, metric, domain) {
  const signal = metric === 'signal';
  const nominalWidth = 520;
  const maximumHeight = 190;
  const from = Number(response.from_ms);
  const to = Number(response.to_ms);
  const range = Math.max(1, to - from);
  const bucket = Number(response.bucket_ms || 10_000);
  const svgNamespace = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNamespace, 'svg');
  svg.setAttribute('class', `quality-chart-svg ${signal ? 'signal-chart-svg' : 'decode-chart-svg'}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', `${siteLabel(site)} ${signal ? 'signal strength' : 'decode quality'} history`);
  const svgNode = (tag, attributes = {}, textValue) => {
    const element = document.createElementNS(svgNamespace, tag);
    Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, String(value)));
    if (textValue !== undefined) element.textContent = String(textValue);
    return element;
  };
  const points = (site.series || []).map((point) => ({
    ...point,
    timestamp: Number(point.time_ms),
    average: optionalNumber(point.average_signal_dbfs),
    minimum: optionalNumber(point.minimum_signal_dbfs),
    maximum: optionalNumber(point.maximum_signal_dbfs),
    decode: optionalNumber(point.decode_health_pct)
  }));
  points.forEach((point) => { point.value = signal ? point.average : point.decode; });
  const segments = [];
  let segment = [];
  points.forEach((point) => {
    const previous = segment.at(-1);
    if (!Number.isFinite(point.value) || previous && point.timestamp - previous.timestamp > bucket * 2.5) {
      if (segment.length) segments.push(segment);
      segment = [];
    }
    if (Number.isFinite(point.value)) segment.push(point);
  });
  if (segment.length) segments.push(segment);
  const wrapper = node('div', `quality-chart ${signal ? 'signal-chart' : 'decode-chart'}`);
  if (!segments.length) wrapper.append(node('div', 'quality-chart-empty',
    `No ${signal ? 'signal' : 'decode'} samples in this range`));
  wrapper.append(svg);

  let drawnWidth = 0;
  const draw = (availableWidth = nominalWidth) => {
    const width = Math.max(280, Math.round(availableWidth));
    if (width === drawnWidth) return;
    drawnWidth = width;
    const height = Math.round(Math.max(132, Math.min(maximumHeight,
      width * maximumHeight / nominalWidth)));
    const margin = { top: 12, right: 12, bottom: 31, left: 48 };
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;
    const xFor = (timestamp) => margin.left + plotWidth *
      Math.max(0, Math.min(1, (timestamp - from) / range));
    const yFor = (value) => margin.top + plotHeight *
      (domain.maximum - value) / Math.max(1, domain.maximum - domain.minimum);

    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.replaceChildren();

    if (!signal) {
      [[0, 80, 'poor'], [80, 95, 'degraded'], [95, 100, 'healthy']].forEach(([minimum, maximum, state]) => {
        svg.append(svgNode('rect', { x: margin.left, y: yFor(maximum), width: plotWidth,
          height: Math.max(0, yFor(minimum) - yFor(maximum)), class: `decode-quality-band ${state}` }));
      });
    }

    for (let index = 0; index <= 4; index += 1) {
      const value = domain.minimum + (domain.maximum - domain.minimum) * index / 4;
      const y = yFor(value);
      svg.append(svgNode('line', { x1: margin.left, y1: y, x2: width - margin.right, y2: y,
        class: 'quality-grid-line' }));
      svg.append(svgNode('text', { x: margin.left - 8, y: y + 4, class: 'quality-axis-label',
        'text-anchor': 'end' }, value.toFixed(0)));
    }

    if (!signal) {
      [80, 95].forEach((value) => svg.append(svgNode('line', { x1: margin.left, y1: yFor(value),
        x2: width - margin.right, y2: yFor(value), class: 'decode-threshold-line' })));
    }

    segments.forEach((values) => {
      if (signal) {
        const upper = values.map((point) => [xFor(point.timestamp),
          yFor(Number.isFinite(point.maximum) ? point.maximum : point.average)]);
        const lower = [...values].reverse().map((point) => [xFor(point.timestamp),
          yFor(Number.isFinite(point.minimum) ? point.minimum : point.average)]);
        const area = [...upper, ...lower].map(([x, y], index) =>
          `${index ? 'L' : 'M'} ${x.toFixed(2)} ${y.toFixed(2)}`).join(' ') + ' Z';
        svg.append(svgNode('path', { d: area, class: 'signal-range-path' }));
      }
      const line = values.map((point, index) =>
        `${index ? 'L' : 'M'} ${xFor(point.timestamp).toFixed(2)} ${yFor(point.value).toFixed(2)}`).join(' ');
      svg.append(svgNode('path', { d: line, class: signal ? 'signal-average-path' : 'decode-health-path' }));
    });

    points.filter((point) => Number.isFinite(point.value)).forEach((point) => {
      const target = svgNode('circle', { cx: xFor(point.timestamp), cy: yFor(point.value), r: 7,
        class: 'quality-hover-target' });
      const frequencyText = Number(point.frequency_hz) ? `${frequency(point.frequency_hz)} MHz` :
        (Number(point.frequency_count) > 1 ? `${number(point.frequency_count)} frequencies` :
          'Frequency unavailable');
      let detail;
      if (signal) {
        const rangeText = Number.isFinite(point.minimum) && Number.isFinite(point.maximum) ?
          `${point.minimum.toFixed(1)} to ${point.maximum.toFixed(1)} dBFS` : 'Unavailable';
        detail = `30s average: ${point.average.toFixed(1)} dBFS\nRange: ${rangeText}\n` +
          `Decode health: ${percentNumber(point.decode)}`;
      } else {
        detail = `Decode health: ${point.decode.toFixed(1)}%\n` +
          `30s signal average: ${signalNumber(point.average)}`;
      }
      target.append(svgNode('title', {}, `${dateTime(point.last_observed_ms || point.timestamp)}\n${detail}\n` +
        `${frequencyText}\n${number(point.sample_count)} retained sample` +
        `${Number(point.sample_count) === 1 ? '' : 's'}`));
      svg.append(target);
    });

    [from, from + range / 2, to].forEach((timestamp, index) => {
      const longRange = range > 86_400_000;
      const label = new Date(timestamp).toLocaleString([], longRange ?
        { month: 'short', day: 'numeric', hour: 'numeric' } : { hour: 'numeric', minute: '2-digit' });
      svg.append(svgNode('text', { x: xFor(timestamp), y: height - 9, class: 'quality-axis-label',
        'text-anchor': index === 0 ? 'start' : (index === 2 ? 'end' : 'middle') }, label));
    });
  };

  draw();
  requestAnimationFrame(() => {
    draw(wrapper.getBoundingClientRect().width || nominalWidth);
    if ('ResizeObserver' in window) {
      const observer = new ResizeObserver((entries) => {
        draw(entries[0]?.contentRect.width || wrapper.getBoundingClientRect().width || nominalWidth);
      });
      observer.observe(wrapper);
      pageObservers.set(observer, wrapper);
    }
  });
  return wrapper;
}

function qualityChartPanel(title, description, chart) {
  const panel = node('div', 'quality-chart-panel');
  const heading = node('div', 'quality-chart-heading');
  heading.append(node('strong', '', title), node('span', '', description));
  panel.append(heading, chart);
  return panel;
}

function updateSignalCurrentTile(tile, site) {
  const state = signalSiteState(site);
  const header = node('div', 'signal-current-header');
  const labels = node('div', 'signal-current-labels');
  labels.append(siteLink(site, siteLabel(site)));
  labels.append(node('div', 'signal-current-system', systemLabel(site)));
  header.append(labels, badge(state.label, `signal-state ${state.className}`));
  const power = node('div', 'signal-current-power');
  power.append(node('strong', '', signalNumber(site.signal_dbfs)),
    node('span', '', `30s avg ${signalNumber(site.average_signal_dbfs)}`));
  const details = node('div', 'signal-current-details');
  const qualityFrequency = site.quality_frequency_hz || site.current_control_hz;
  const decode = optionalNumber(site.decode_health_pct);
  const decodeClass = !Number.isFinite(decode) ? '' :
    (decode >= 95 ? 'quality-good' : (decode >= 80 ? 'quality-warn' : 'quality-bad'));
  details.append(node('span', decodeClass,
  `Decode ${percentNumber(site.decode_health_pct)}`),
  node('span', '', Number(qualityFrequency) ? `${frequency(qualityFrequency)} MHz` : 'Frequency unavailable'),
  node('span', '', elapsedLabel(site.last_observed_ms)));
  tile.dataset.guid = site.guid || '';
  tile.replaceChildren(header, power, details);
  return tile;
}

function signalCurrentTile(site) {
  return updateSignalCurrentTile(node('article', 'signal-current-tile'), site);
}

function sortSignalSites(sites, selectedSort) {
  const now = Date.now();
  return [...sites].sort((left, right) => {
    if (selectedSort === 'name') return siteLabel(left).localeCompare(siteLabel(right));
    const leftState = signalSiteState(left, now);
    const rightState = signalSiteState(right, now);
    if (leftState.rank !== rightState.rank && (leftState.rank === 0 || rightState.rank === 0)) {
      return leftState.rank - rightState.rank;
    }
    if (selectedSort === 'signal') {
      const leftSignal = optionalNumber(left.average_signal_dbfs);
      const rightSignal = optionalNumber(right.average_signal_dbfs);
      return (Number.isFinite(leftSignal) ? leftSignal : -Infinity) -
        (Number.isFinite(rightSignal) ? rightSignal : -Infinity);
    }
    const leftDecode = optionalNumber(left.decode_health_pct);
    const rightDecode = optionalNumber(right.decode_health_pct);
    return (Number.isFinite(leftDecode) ? leftDecode : -1) -
      (Number.isFinite(rightDecode) ? rightDecode : -1);
  });
}

function signalOverview(site, includeName = true) {
  const overview = node('div', 'signal-history-overview');
  overview.classList.toggle('without-identity', !includeName);
  if (includeName) {
    const identity = node('div', 'signal-history-identity');
    identity.append(siteLink(site, siteLabel(site)), node('span', '', systemLabel(site)));
    overview.append(identity);
  }
  [['Current', signalNumber(site.signal_dbfs)], ['30s average', signalNumber(site.average_signal_dbfs)],
    ['Decode', percentNumber(site.decode_health_pct)],
    ['Last sample', elapsedLabel(site.last_observed_ms)]].forEach(([label, value]) => {
    const metric = node('div', 'signal-history-metric');
    metric.append(node('span', '', label), node('strong', '', value));
    overview.append(metric);
  });
  return overview;
}

function signalRangeControls(selectedRange, onChange) {
  const controls = node('div', 'signal-range-controls');
  const buttons = new Map();
  SIGNAL_RANGES.forEach(([value, label]) => {
    const button = node('button', 'signal-range-button secondary', label);
    button.type = 'button';
    button.setAttribute('aria-pressed', String(value === selectedRange));
    button.classList.toggle('active', value === selectedRange);
    button.addEventListener('click', () => {
      if (value === selectedRange) return;
      selectedRange = value;
      buttons.forEach((candidate, candidateValue) => {
        candidate.classList.toggle('active', candidateValue === selectedRange);
        candidate.setAttribute('aria-pressed', String(candidateValue === selectedRange));
      });
      onChange(value, buttons);
    });
    buttons.set(value, button);
    controls.append(button);
  });
  return { controls, buttons };
}

async function signalHealthSection() {
  const host = node('div', 'signal-health');
  const currentPanel = node('div', 'signal-current-panel');
  const currentToolbar = node('div', 'signal-current-toolbar');
  const summary = node('div', 'signal-health-summary');
  const sortLabel = node('label', 'signal-sort-label', 'Sort');
  const sort = node('select', 'signal-sort');
  [['decode', 'Lowest decode'], ['signal', 'Weakest signal'], ['name', 'Name']].forEach(([value, label]) => {
    const option = node('option', '', label);
    option.value = value;
    sort.append(option);
  });
  let selectedSort = 'decode';
  sort.value = selectedSort;
  sortLabel.append(sort);
  currentToolbar.append(summary, sortLabel);
  const tiles = node('div', 'signal-current-grid');
  currentPanel.append(currentToolbar, tiles);
  host.append(currentPanel);
  const block = section('Signal Health', host);
  let currentResponse = null;
  const tileNodes = new Map();
  let loading = false;

  const renderCurrent = () => {
    const now = Date.now();
    const sites = sortSignalSites((currentResponse?.sites || []).filter((site) =>
      now - Number(site.last_observed_ms || 0) <= SIGNAL_OFFLINE_MILLISECONDS), selectedSort);
    const healthy = sites.filter((site) => optionalNumber(site.decode_health_pct) >= 95).length;
    const degraded = sites.filter((site) => {
      const decode = optionalNumber(site.decode_health_pct);
      return Number.isFinite(decode) && decode < 95;
    }).length;
    const unknown = sites.length - healthy - degraded;
    summary.textContent = `${number(sites.length)} reporting · ${number(healthy)} healthy · ` +
      `${number(degraded)} degraded${unknown ? ` · ${number(unknown)} unknown` : ''}`;

    const activeKeys = new Set();
    const orderedTiles = sites.map((site) => {
      const key = site.guid || siteLabel(site);
      activeKeys.add(key);
      const existing = tileNodes.get(key);
      const tile = existing ? updateSignalCurrentTile(existing, site) : signalCurrentTile(site);
      tileNodes.set(key, tile);
      return tile;
    });
    [...tileNodes.keys()].filter((key) => !activeKeys.has(key)).forEach((key) => tileNodes.delete(key));
    tiles.replaceChildren(...orderedTiles);
    if (!sites.length) tiles.append(node('div', 'empty', 'No P25 receivers are currently reporting'));
  };

  sort.addEventListener('change', () => {
    selectedSort = sort.value;
    if (currentResponse) renderCurrent();
  });

  const logging = statsLoggingState();
  if (logging.available && !logging.summaryActive) {
    currentToolbar.hidden = true;
    const message = node('div', 'empty signal-disabled');
    message.append('Signal health requires Stats Logging. ', anchor('Live signal levels remain available',
      href('live')), '.');
    tiles.append(message);
  } else {
    const loadCurrent = async (initial = false) => {
      if (loading) return;
      loading = true;
      if (initial) summary.textContent = 'Loading current signal health…';
      try {
        currentResponse = await api('/api/quality', { range: '1h', points: 60, include_history: false });
        renderCurrent();
      } catch (error) {
        summary.textContent = currentResponse ? `Signal health update failed: ${error.message}` : '';
        if (!currentResponse) tiles.replaceChildren(node('div', 'error', error.message));
      } finally {
        loading = false;
      }
    };
    await loadCurrent(true);
    pageInterval(loadCurrent, 10_000);
  }
  return block;
}

async function siteSignalHistorySection(site) {
  const host = node('div', 'site-signal-history');
  const block = section('Control Channel Quality History', host);
  block.classList.add('site-signal-history-section');
  let selectedRange = '24h';
  let loadingSequence = 0;
  let loading = false;
  const rangeControl = signalRangeControls(selectedRange, async (value, buttons) => {
    selectedRange = value;
    await load(buttons, true);
  });
  block.querySelector('.section-title').append(rangeControl.controls);
  const load = async (buttons = rangeControl.buttons, interactive = false) => {
    if (loading && !interactive) return;
    const sequence = ++loadingSequence;
    loading = true;
    if (interactive) {
      buttons.forEach((button) => { button.disabled = true; });
      disconnectPageObserversWithin(host);
      host.replaceChildren(node('div', 'loading', 'Loading control channel quality history'));
    }
    try {
      const response = await api('/api/quality', { guid: site.guid, range: selectedRange, points: 300 });
      if (sequence !== loadingSequence) return;
      const qualitySite = (response.sites || [])[0];
      disconnectPageObserversWithin(host);
      host.replaceChildren();
      if (!qualitySite) {
        host.append(node('div', 'empty', 'No control channel quality history is available for this site'));
        return;
      }
      const charts = node('div', 'quality-chart-stack');
      charts.append(
        qualityChartPanel('Signal Strength', '30-second average and observed range · dBFS',
          qualityHistoryChart(qualitySite, response, 'signal', sharedSignalDomain([qualitySite]))),
        qualityChartPanel('Decode Quality', '30-second rolling successful-frame rate · percent',
          qualityHistoryChart(qualitySite, response, 'decode', { minimum: 0, maximum: 100 }))
      );
      host.append(signalOverview(qualitySite, false), charts);
      host.removeAttribute('title');
    } catch (error) {
      if (sequence === loadingSequence) {
        if (interactive) host.replaceChildren(node('div', 'error', error.message));
        else host.title = `Quality history update failed: ${error.message}`;
      }
    } finally {
      if (sequence === loadingSequence) {
        loading = false;
        if (interactive) buttons.forEach((button) => { button.disabled = false; });
      }
    }
  };
  const logging = statsLoggingState();
  if (logging.available && !logging.summaryActive) {
    rangeControl.controls.hidden = true;
    host.append(node('div', 'empty', 'Control channel quality history requires Stats Logging.'));
  } else {
    await load(rangeControl.buttons, true);
    pageInterval(load, 30_000);
  }
  return block;
}

async function api(path, parameters = {}) {
  const query = new URLSearchParams();
  Object.entries(parameters).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') query.set(key, String(value));
  });
  const response = await fetch(`${path}${query.size ? `?${query}` : ''}`, { cache: 'no-store' });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || `${path} returned ${response.status}`);
  return result;
}

const liveConnections = new Set();
const pageConnections = new Set();
const pageObservers = new Map();
const pageTimers = new Set();

function pageInterval(callback, interval) {
  const timer = window.setInterval(() => {
    if (!document.hidden) Promise.resolve(callback()).catch(() => {});
  }, interval);
  pageTimers.add(timer);
  return timer;
}

function disconnectPageObserversWithin(root) {
  pageObservers.forEach((target, observer) => {
    if (root?.contains(target)) {
      observer.disconnect();
      pageObservers.delete(observer);
    }
  });
}

function liveConnection(path, parameters = {}) {
  const query = new URLSearchParams();
  Object.entries(parameters).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') query.set(key, String(value));
  });
  const source = new EventSource(`${path}${query.size ? `?${query}` : ''}`);
  liveConnections.add(source);
  pageConnections.add(source);
  return source;
}

function closePageConnections() {
  pageConnections.forEach((source) => {
    source.close();
    liveConnections.delete(source);
  });
  pageConnections.clear();
  pageObservers.forEach((target, observer) => observer.disconnect());
  pageObservers.clear();
  pageTimers.forEach((timer) => window.clearInterval(timer));
  pageTimers.clear();
}

window.addEventListener('beforeunload', () => {
  liveConnections.forEach((source) => source.close());
  liveConnections.clear();
});

function initializePlaybackHeader() {
  if (tableOnly) return;
  window.sdrtrunkWebPlayer = new WebCallPlayer({
    mute: 'playback-mute',
    hold: 'playback-hold',
    avoid: 'playback-avoid',
    clear: 'playback-clear',
    skip: 'playback-skip',
    current: 'playback-current',
    queued: 'playback-queued',
    dropped: 'playback-dropped',
    queueList: 'playback-queue-list',
    maximumQueued: 'playback-max-queued',
    status: 'playback-status'
  });
  const source = window.sdrtrunkWebPlayer.connect('/live/web-calls');
  liveConnections.add(source);
}

function pageParameters(extra = {}) {
  return {
    q: route.get('q'),
    sort: route.get('sort'),
    direction: route.get('direction'),
    offset: route.get('offset'),
    limit: 100,
    ...extra
  };
}

function requiredSystemScope() {
  const wacn = parseInt(route.get('wacn'), 16);
  const systemId = parseInt(route.get('system'), 16);
  if (Number.isNaN(wacn) || Number.isNaN(systemId)) throw new Error('System identity is missing from the URL');
  return { wacn, system_id: systemId };
}

function requiredId() {
  const id = Number(route.get('id'));
  if (!Number.isInteger(id) || id < 0) throw new Error('Identifier is missing from the URL');
  return id;
}

function systemTabs(system, active) {
  const values = scope(system);
  return tabs([
    { id: 'info', label: 'Info', href: href('system', { ...values, tab: 'info' }) },
    { id: 'sites', label: 'Sites', href: href('system', { ...values, tab: 'sites' }) },
    { id: 'talkgroups', label: 'Talkgroups', href: href('system', { ...values, tab: 'talkgroups' }) },
    { id: 'radios', label: 'Radios', href: href('system', { ...values, tab: 'radios' }) },
    { id: 'talker-aliases', label: 'Talker Aliases', href: href('system', { ...values, tab: 'talker-aliases' }) }
  ], active);
}

function entityTabs(view, system, id, active, radio) {
  const values = { ...scope(system), id };
  const activity = { id: 'activity', label: 'Activity', href: href(view, { ...values, tab: 'activity' }),
    disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' };
  return tabs(radio ? [
    { id: 'info', label: 'Info', href: href(view, { ...values, tab: 'info' }) },
    { id: 'talkgroups', label: 'Talkgroups', href: href(view, { ...values, tab: 'talkgroups' }) },
    activity
  ] : [
    { id: 'info', label: 'Info', href: href(view, { ...values, tab: 'info' }) },
    { id: 'radios', label: 'Radios', href: href(view, { ...values, tab: 'radios' }) },
    activity
  ], active);
}

function siteTabs(site, active) {
  const values = { guid: site.guid };
  return tabs([
    { id: 'info', label: 'Info', href: href('site', { ...values, tab: 'info' }) },
    { id: 'channels', label: 'Channels', href: href('site', { ...values, tab: 'channels' }) },
    { id: 'quality', label: 'Quality', href: href('site', { ...values, tab: 'quality' }) },
    { id: 'neighbors', label: 'Neighbors', href: href('site', { ...values, tab: 'neighbors' }) },
    { id: 'band-plan', label: 'Band Plan', href: href('site', { ...values, tab: 'band-plan' }) },
    { id: 'patches', label: 'Patches', href: href('site', { ...values, tab: 'patches' }) },
    { id: 'activity', label: 'Activity', href: href('site', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' }
  ], active);
}

const siteColumns = [
  { id: 'system', label: 'System', render: systemLink, sort: 'system', sortValue: systemLabel },
  { id: 'rfss', label: 'RFSS', key: 'rfss', render: (row) => hexDecimal(row.rfss, 2), className: 'numeric', sort: 'rfss' },
  { id: 'site', label: 'Site', key: 'site', render: (row) => hexDecimal(row.site, 2), className: 'numeric', sort: 'site' },
  { id: 'name', label: 'Name', render: (row) => siteLink(row), className: 'alias-cell', sort: 'name', sortValue: siteLabel },
  { id: 'control-frequency', label: 'Control MHz', render: (row) => frequency(row.current_control_hz), className: 'numeric', sort: 'control', sortValue: (row) => Number(row.current_control_hz || 0) },
  { label: 'Channels', key: 'channels', className: 'numeric', sort: 'channels' },
  { label: 'Neighbors', key: 'neighbors', className: 'numeric', sort: 'neighbors' },
  { label: 'Bands', key: 'bands', className: 'numeric', sort: 'bands' },
  { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

const talkgroupColumns = [
  { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sort: 'talkgroup', sortValue: (row) => Number(row.talkgroup_id) },
  { id: 'talkgroup-name', label: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row, row.talkgroup_id), className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
  { label: 'Group', key: 'alias_group', className: 'alias-cell', sort: 'group' },
  { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
  { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
  { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

const radioColumns = [
  { id: 'radio', label: 'ID', render: (row) => radioLink(row), className: 'numeric', sort: 'id', sortValue: (row) => Number(row.radio_id) },
  { id: 'alias', label: 'Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
  { label: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
  { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
  { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
  { id: 'talkgroup-id', label: 'Affiliated TGID', render: (row) => talkgroupLink(row, row.affiliated_talkgroup_id), className: 'numeric', sort: 'affiliated_talkgroup', sortValue: (row) => Number(row.affiliated_talkgroup_id) },
  { id: 'talkgroup-name', label: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row,
    row.affiliated_talkgroup_id, 'affiliated_talkgroup_alias_'), className: 'alias-cell', sortValue: (row) => row.affiliated_talkgroup_alias_name || '' },
  { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

async function renderDashboard() {
  const dashboard = await api('/api/dashboard');
  const counts = dashboard.counts || {};
  content.append(pageHeader('Dashboard', `Last activity ${dateTime(dashboard.lastSeenMs) || 'not recorded'}`));
  content.append(metrics([
    ['Systems', counts.systems], ['Sites', counts.sites], ['Talkgroups', counts.talkgroups],
    ['Radios', counts.radios], ['Frequencies', counts.frequencies], ['Conventional', counts.conventional]
  ]));
  content.append(await signalHealthSection());
  content.append(section('Calls Per Hour', hourlyLineGraph(dashboard.activityPerHour || [])));
  const sites = section('Recent Sites', table(dashboard.recentSites || [], siteColumns, 'No rows', { type: 'sites' }));
  const actions = section('24 Hour Actions', node('div', 'action-chart'));
  actions.lastChild.append(actionPie(dashboard.actionMix || []));
  content.append(node('div', 'split'));
  content.lastChild.append(sites, actions);
  const talkgroups = section('Top Talkgroups', table(dashboard.topTalkgroups || [], talkgroupColumns, 'No rows', { type: 'talkgroups' }));
  const radios = section('Top Radios', table(dashboard.topRadios || [], radioColumns, 'No rows', { type: 'radios' }));
  content.append(node('div', 'split'));
  content.lastChild.append(talkgroups, radios);
}

function liveSystemsSection() {
  const tables = new Map();
  const tabNodes = new Map();
  const rowNodes = new Map();
  const columns = [
    { id: 'status', label: 'Status', width: 145, sortValue: (row) => row.status || '' },
    { id: 'tags', label: 'Tags', width: 180, sortValue: channelTagText },
    { id: 'channel-lcn', label: 'LCN', width: 130, sortValue: (row) =>
      channelTagSet(row.tags).has('CONVENTIONAL') ? (row.channel_name || '') : (row.lcn || '') },
    { id: 'frequency', label: 'Frequency', width: 100, sortValue: (row) => Number(row.frequency_hz || 0) },
    { id: 'signal', label: 'Signal', width: 90, sortValue: (row) => Number(row.signal_dbfs ?? -999) },
    { id: 'decode-health', label: 'Decode', width: 80, sortValue: (row) => Number(row.decode_health_pct ?? -1) },
    { id: 'source-alias', label: 'Source Alias', width: 220, sortValue: (row) => row.source_alias_display || row.source_alias || row.talker_alias || '' },
    { id: 'source', label: 'Source', width: 105, sortValue: (row) => Number(row.source_id || 0) },
    { id: 'target-alias', label: 'Target Alias', width: 220, sortValue: (row) => row.target_alias || '' },
    { id: 'target', label: 'Target', width: 105, sortValue: (row) => Number(row.target_id || 0) },
    { id: 'decoder', label: 'Decoder', width: 80, sortValue: (row) => row.decoder || '' }
  ];
  const tabBar = node('div', 'systems-live-tabs');
  const connection = badge('Connecting', 'state-stale');
  const tableElement = node('table', 'data-table systems-live-table resizable-table');
  tableElement.dataset.tableType = 'live-systems';
  const columnGroup = node('colgroup');
  const columnElements = columns.map(() => node('col'));
  columnGroup.append(...columnElements);
  const head = node('thead');
  const headerRow = node('tr');
  const headers = [];
  let liveSort = null;
  columns.forEach((column) => {
    const header = node('th');
    const control = node('button', 'table-sort-control', column.label);
    control.type = 'button';
    control.addEventListener('click', () => {
      liveSort = liveSort?.column === column ?
        { column, direction: liveSort.direction === 'asc' ? 'desc' : 'asc' } :
        { column, direction: 'asc' };
      headers.forEach((candidate, index) => candidate.setAttribute('aria-sort',
        columns[index] === liveSort.column ? (liveSort.direction === 'asc' ? 'ascending' : 'descending') : 'none'));
      const value = tables.get(activeTableId);
      if (value) reorderVisibleRows(value.rows || []);
    });
    header.setAttribute('aria-sort', 'none');
    header.append(control);
    headers.push(header);
    headerRow.append(header);
  });
  head.append(headerRow);
  const body = node('tbody');
  tableElement.append(columnGroup, head, body);
  applyPreferredTableWidths(tableElement, columns, columnElements, 'live-systems');
  addColumnResizers(tableElement, columns, columnElements, headers, 'live-systems');
  const tableScroll = node('div', 'table-scroll');
  tableScroll.append(tableElement);
  const host = node('div', 'systems-live');
  host.append(tabBar, tableScroll);
  const block = section('Live Systems', host);
  block.querySelector('.section-title').append(connection);
  let activeTableId = null;
  let selectedRowKey = null;

  const cellText = (cell, value) => {
    const text = value === null || value === undefined ? '' : String(value);
    if (cell.textContent !== text) cell.textContent = text;
  };

  const updateRow = (element, row) => {
    const cells = element.children;
    const conventional = channelTagSet(row.tags).has('CONVENTIONAL');
    const statusText = row.status === 'ENCRYPTED' && row.encryption_details ? row.encryption_details : row.status;
    cellText(cells[0], statusText);
    cellText(cells[1], channelTagText(row));
    cellText(cells[2], conventional ? row.channel_name : row.lcn);
    cellText(cells[3], frequency(row.frequency_hz));
    cellText(cells[4], row.signal_dbfs == null ? '' : `${Number(row.signal_dbfs).toFixed(1)} dBFS`);
    cellText(cells[5], row.decode_health_pct == null ? '' : `${Number(row.decode_health_pct).toFixed(1)}%`);
    cellText(cells[6], row.source_alias_display || row.source_alias ||
      (row.talker_alias ? `TA: ${row.talker_alias}` : ''));
    cellText(cells[7], row.source_id);
    cellText(cells[8], row.target_alias);
    cellText(cells[9], row.target_id);
    cellText(cells[10], row.decoder);
    cells[1].title = channelTagTitle(row);
    cells[2].title = conventional ? (row.channel_name || '') : '';
    cells[0].className = `activity-status state-${String(row.status || 'idle').toLowerCase()}`;
    cells[1].className = '';
    const tags = channelTagSet(row.tags);
    cells[2].className = tags.has('CURRENT_CONTROL') ? 'control-current' :
      (tags.has('ALTERNATE_CONTROL') ? 'control-alternate' : '');
    cells[3].className = cells[2].className;
    cells[4].className = cells[2].className;
    cells[5].className = row.decode_health_pct == null ? '' :
      (Number(row.decode_health_pct) >= 95 ? 'quality-good' :
        (Number(row.decode_health_pct) >= 80 ? 'quality-warn' : 'quality-bad'));
    element.classList.toggle('selected', selectedRowKey === row.key);
  };

  const createRow = (row) => {
    const element = node('tr');
    element.dataset.key = row.key;
    for (let index = 0; index < 11; index += 1) element.append(node('td'));
    element.addEventListener('click', () => {
      selectedRowKey = row.key;
      rowNodes.forEach((candidate, key) => candidate.classList.toggle('selected', key === selectedRowKey));
    });
    updateRow(element, row);
    return element;
  };

  const orderedLiveRows = (rows) => liveSort ? [...rows].sort((left, right) => {
    const result = compareTableValues(liveSort.column.sortValue(left), liveSort.column.sortValue(right));
    return liveSort.direction === 'asc' ? result : -result;
  }) : rows;

  const reorderVisibleRows = (rows) => {
    orderedLiveRows(rows).forEach((row) => {
      const element = rowNodes.get(row.key);
      if (element) body.append(element);
    });
  };

  const showTable = (tableId) => {
    const value = tables.get(tableId);
    if (!value) return;
    activeTableId = tableId;
    selectedRowKey = null;
    rowNodes.clear();
    body.replaceChildren();
    headers[2].querySelector('.table-sort-control').textContent =
      tableId === 'conventional' ? 'Channel' : 'LCN';
    orderedLiveRows(value.rows || []).forEach((row) => {
      const element = createRow(row);
      rowNodes.set(row.key, element);
      body.append(element);
    });
    if (!value.rows?.length) {
      const empty = node('tr', 'empty');
      const message = node('td', '', 'No channels observed');
      message.colSpan = columns.length;
      empty.append(message);
      body.append(empty);
    }
    tabNodes.forEach((tab, id) => tab.classList.toggle('active', id === activeTableId));
  };

  const updateVisibleRows = (value) => {
    if (value.table_id !== activeTableId) return;
    const incoming = new Map((value.rows || []).map((row) => [row.key, row]));
    body.querySelector('.empty')?.remove();
    rowNodes.forEach((element, key) => {
      if (!incoming.has(key)) {
        element.remove();
        rowNodes.delete(key);
      }
    });
    (value.rows || []).forEach((row) => {
      let element = rowNodes.get(row.key);
      if (!element) {
        element = createRow(row);
        rowNodes.set(row.key, element);
        body.append(element);
      } else {
        updateRow(element, row);
      }
    });
    if (liveSort) reorderVisibleRows(value.rows || []);
    if (!rowNodes.size) {
      const empty = node('tr', 'empty');
      const message = node('td', '', 'No channels observed');
      message.colSpan = columns.length;
      empty.append(message);
      body.append(empty);
    }
  };

  const upsertTable = (value) => {
    if (!value?.table_id) return;
    tables.set(value.table_id, value);
    let tab = tabNodes.get(value.table_id);
    if (!tab) {
      tab = node('button', 'systems-live-tab');
      tab.type = 'button';
      tab.append(node('span', 'systems-tab-dot'), node('span', 'systems-tab-label'));
      tab.addEventListener('click', () => showTable(value.table_id));
      tabNodes.set(value.table_id, tab);
      tabBar.append(tab);
    }
    tab.querySelector('.systems-tab-label').textContent = value.title || value.channel_name || value.table_id;
    tab.querySelector('.systems-tab-dot').className =
      `systems-tab-dot ${value.table_id === 'conventional' ? 'neutral' : (value.control_active ? 'active' : 'stale')}`;
    if (!activeTableId) showTable(tables.has('conventional') ? 'conventional' : value.table_id);
    else updateVisibleRows(value);
  };

  const removeTable = (tableId) => {
    tables.delete(tableId);
    tabNodes.get(tableId)?.remove();
    tabNodes.delete(tableId);
    if (activeTableId === tableId) {
      activeTableId = null;
      const next = tables.has('conventional') ? 'conventional' : tables.keys().next().value;
      if (next) showTable(next);
      else body.replaceChildren();
    }
  };

  const source = liveConnection('/live/systems');
  source.addEventListener('snapshot', (event) => {
    const snapshot = JSON.parse(event.data);
    (snapshot.tables || []).forEach(upsertTable);
  });
  source.addEventListener('activity_table', (event) => {
    const update = JSON.parse(event.data);
    if (update.operation === 'remove') removeTable(update.table_id);
    else upsertTable(update.table);
  });
  source.onopen = () => {
    connection.textContent = 'Live';
    connection.className = 'badge state-current';
  };
  source.onerror = () => {
    connection.textContent = 'Reconnecting';
    connection.className = 'badge state-stale';
  };
  return block;
}

async function renderLive() {
  content.append(liveSystemsSection());
}

async function renderSystems() {
  const page = await api('/api/systems', pageParameters());
  content.append(pageHeader('Systems', 'P25 systems are grouped by WACN and System ID'));
  const columns = [
    { id: 'wacn', label: 'WACN', render: (row) => systemLink(row, hexDecimal(row.wacn, 5)), sort: 'wacn', sortValue: (row) => Number(row.wacn) },
    { id: 'system', label: 'System', render: (row) => systemLink(row, hexDecimal(row.system_id, 3)), sort: 'system_id', sortValue: (row) => Number(row.system_id) },
    { label: 'Site Names', key: 'site_names', className: 'alias-cell', sort: 'site_names' },
    { label: 'Sites', key: 'sites', className: 'numeric', sort: 'sites' },
    { label: 'Talkgroups', key: 'talkgroups', className: 'numeric', sort: 'talkgroups' },
    { label: 'Radios', key: 'radios', className: 'numeric', sort: 'radios' },
    { label: 'Affiliated', key: 'affiliations', className: 'numeric', sort: 'affiliations' },
    { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
  content.append(pagedSection('System Directory', page, columns, 'Search system or site name', 'systems'));
}

async function renderSites() {
  const page = await api('/api/sites', pageParameters());
  content.append(pageHeader('Sites', 'All observed trunked sites with their parent system'));
  content.append(pagedSection('Sites', page, siteColumns, 'Search site name or GUID', 'sites'));
}

async function renderSystem() {
  const systemScope = requiredSystemScope();
  const response = await api('/api/system', systemScope);
  const system = response.system;
  const tab = route.get('tab') || 'info';
  content.append(pageHeader(systemLabel(system), system.site_names || 'P25 trunked system'), systemTabs(system, tab));

  if (tab === 'sites') {
    const page = await api('/api/system/sites', pageParameters(systemScope));
    content.append(pagedSection('Sites', page, siteColumns, 'Search site name or GUID', 'sites'));
  } else if (tab === 'talkgroups') {
    const page = await api('/api/system/talkgroups', pageParameters(systemScope));
    content.append(pagedSection('Talkgroups', page, talkgroupColumns, 'Search talkgroup ID', 'talkgroups'));
  } else if (tab === 'radios') {
    const page = await api('/api/system/radios', pageParameters(systemScope));
    content.append(pagedSection('Radios', page, radioColumns, 'Search radio ID', 'radios'));
  } else if (tab === 'talker-aliases') {
    const page = await api('/api/system/talker-aliases', pageParameters(systemScope));
    const columns = [
      { id: 'radio', label: 'Radio ID', render: (row) => radioLink(row), className: 'numeric', sort: 'radio', sortValue: (row) => Number(row.radio_id) },
      { id: 'talker-alias', label: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
      { id: 'radio-alias', label: 'Configured Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
      { id: 'talkgroup-id', label: 'Last TGID', render: (row) => talkgroupLink(row, row.last_talkgroup_id), className: 'numeric', sort: 'last_talkgroup', sortValue: (row) => Number(row.last_talkgroup_id) },
      { id: 'talkgroup-name', label: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row, row.last_talkgroup_id, 'talkgroup_alias_'), className: 'alias-cell', sort: 'last_talkgroup_name', sortValue: (row) => row.talkgroup_alias_name || '' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Alias Last Seen', render: (row) => dateTime(row.last_talker_alias_seen_ms), sort: 'talker_alias_seen', sortValue: (row) => Number(row.last_talker_alias_seen_ms || 0) }
    ];
    const block = pagedSection('Talker Alias Summary', page, columns,
      'Search radio ID or talker alias', 'talker-aliases');
    if (!page.rows.length) block.querySelector('.empty').textContent = 'No talker aliases recorded for this system';
    content.append(block);
  } else {
    content.append(metrics([
      ['Sites', system.sites], ['Talkgroups', system.talkgroups], ['Radios', system.radios],
      ['Affiliated', system.affiliations], ['Calls', system.activity_calls]
    ]));
    content.append(section('System Info', keyValues([
      ['System', systemLabel(system)],
      ['First Seen', dateTime(system.first_seen_ms)], ['Last Seen', dateTime(system.last_seen_ms)]
    ])));
    content.append(section('Observed Actions', table(withoutGrantActions(response.actionCounts), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No actions recorded', { type: 'action-counts' })));
  }
}

async function renderTalkgroup() {
  const systemScope = requiredSystemScope();
  const id = requiredId();
  const response = await api('/api/talkgroup', { ...systemScope, talkgroup_id: id });
  const talkgroup = response.talkgroup;
  const tab = route.get('tab') || 'info';
  const title = aliasLabel(talkgroup) || `Talkgroup ${id}`;
  content.append(pageHeader(title, `${systemLabel(talkgroup)} · Talkgroup ${id}`),
    entityTabs('talkgroup', talkgroup, id, tab, false));

  if (tab === 'radios') {
    const [relationships, affiliations] = await Promise.all([
      api('/api/radio-talkgroups', pageParameters({ ...systemScope, talkgroup_id: id })),
      api('/api/affiliations', { ...systemScope, talkgroup_id: id, limit: 500 })
    ]);
    const affiliated = new Set((affiliations.rows || []).map((row) => Number(row.radio_id)));
    const columns = [
      { id: 'radio', label: 'Radio', render: (row) => radioLink(row), className: 'numeric', sort: 'radio', sortValue: (row) => Number(row.radio_id) },
      { id: 'alias', label: 'Alias', render: (row) => row.radio_alias_name ? radioLink(row, row.radio_id, row.radio_alias_name) : '', className: 'alias-cell', sort: 'radio_alias', sortValue: (row) => row.radio_alias_name || '' },
      { label: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
      { id: 'affiliated', label: 'Affiliated', render: (row) => checkbox(affiliated.has(Number(row.radio_id))), className: 'center', sort: 'affiliated', sortValue: (row) => affiliated.has(Number(row.radio_id)) },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    content.append(pagedSection('Radios', relationships, columns, null, 'talkgroup-radios'));
  } else if (tab === 'activity') {
    await renderActivity({ ...systemScope, talkgroup_id: id });
  } else {
    const affiliationLink = anchor(number(talkgroup.affiliated_radios),
      href('talkgroup', { ...scope(talkgroup), id, tab: 'radios' }));
    content.append(section('Talkgroup Info', keyValues([
      ['System', systemLink(talkgroup)], ['Talkgroup ID', id], ['Alias', aliasLabel(talkgroup)],
      ['Group', talkgroup.alias_group], ['First Seen', dateTime(talkgroup.first_seen_ms)],
      ['Last Seen', dateTime(talkgroup.last_seen_ms)], ['Calls', number(talkgroup.call_count)],
      ['Radios', number(talkgroup.radios)], ['Currently Affiliated', affiliationLink],
      ['Enc', number(talkgroup.encrypted_count)],
      ['Last Source', radioLink(talkgroup, talkgroup.last_source_radio_id)],
      ['Last Alg ID', hexDecimal(talkgroup.last_encryption_algorithm_id, 2)],
      ['Last Key ID', hexDecimal(talkgroup.last_encryption_key_id)]
    ])));
    content.append(section('Action Counts', table(actionCounts(talkgroup).map(([action, count]) => ({ action, count })), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No actions recorded', { type: 'action-counts' })));
  }
}

async function renderRadio() {
  const systemScope = requiredSystemScope();
  const id = requiredId();
  const response = await api('/api/radio', { ...systemScope, radio_id: id });
  const radio = response.radio;
  const tab = route.get('tab') || 'info';
  const title = aliasLabel(radio) || radio.last_talker_alias || `Radio ${id}`;
  content.append(pageHeader(title, `${systemLabel(radio)} · Radio ${id}`),
    entityTabs('radio', radio, id, tab, true));

  if (tab === 'talkgroups') {
    const relationships = await api('/api/radio-talkgroups',
      pageParameters({ ...systemScope, radio_id: id }));
    const columns = [
      { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sort: 'talkgroup', sortValue: (row) => Number(row.talkgroup_id) },
      { id: 'talkgroup-name', label: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row,
        row.talkgroup_id, 'talkgroup_alias_'), className: 'alias-cell', sort: 'talkgroup_alias', sortValue: (row) => row.talkgroup_alias_name || '' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    content.append(pagedSection('Talkgroups', relationships, columns, null, 'radio-talkgroups'));
  } else if (tab === 'activity') {
    await renderActivity({ ...systemScope, radio_id: id });
  } else {
    content.append(section('Radio Info', keyValues([
      ['System', systemLink(radio)], ['Radio ID', id], ['Alias', aliasLabel(radio)],
      ['Talker Alias', radio.last_talker_alias], ['Talker Alias Seen', dateTime(radio.last_talker_alias_seen_ms)],
      ['Current Affiliation TGID', talkgroupLink(radio, radio.affiliated_talkgroup_id)],
      ['Current Affiliation Name', talkgroupAliasLink(radio, radio.affiliated_talkgroup_id,
        'affiliated_talkgroup_alias_')],
      ['Affiliation Updated', dateTime(radio.affiliation_updated_at_ms)],
      ['First Seen', dateTime(radio.first_seen_ms)], ['Last Seen', dateTime(radio.last_seen_ms)],
      ['Calls', number(radio.call_count)],
      ['Talkgroups', number(radio.talkgroups)],
      ['Enc', number(radio.encrypted_count)],
      ['Last Alg ID', hexDecimal(radio.last_encryption_algorithm_id, 2)],
      ['Last Key ID', hexDecimal(radio.last_encryption_key_id)]
    ])));
    content.append(section('Action Counts', table(actionCounts(radio).map(([action, count]) => ({ action, count })), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No actions recorded', { type: 'action-counts' })));
  }
}

async function renderSite() {
  const guid = route.get('guid');
  if (!guid) throw new Error('Site GUID is missing from the URL');
  const response = await api('/api/site', { guid });
  const site = response.site;
  const tab = route.get('tab') || 'info';
  content.append(pageHeader(siteLabel(site), `${systemLabel(site)} · ${hexDecimal(site.rfss, 2)}-${hexDecimal(site.site, 2)}`),
    siteTabs(site, tab));

  if (tab === 'quality') {
    content.append(await siteSignalHistorySection(site));
  } else if (tab === 'channels') {
    const data = await api('/api/site/channels', { guid });
    const columns = [
      { label: 'LCN / Modes', key: 'descriptor' },
      { label: 'Callsign', key: 'callsign' },
      { label: 'Tags', key: 'tags', render: channelTags },
      { id: 'downlink', label: 'Downlink MHz', render: (row) => frequency(row.downlink_hz), className: 'numeric', sortValue: (row) => Number(row.downlink_hz || 0) },
      { id: 'uplink', label: 'Uplink MHz', render: (row) => frequency(row.uplink_hz), className: 'numeric', sortValue: (row) => Number(row.uplink_hz || 0) },
      { id: 'tdma', label: 'TDMA', render: (row) => yesNo(row.tdma), sortValue: (row) => Boolean(row.tdma) },
      { label: 'Slots', key: 'timeslots', className: 'numeric' },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Voice Grants', key: 'voice_grant_observations', className: 'numeric' },
      { label: 'Data Grants', key: 'data_grant_observations', className: 'numeric' },
      { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    const rows = data.rows || [];
    content.append(section('Channels', table(rows, columns, 'No channels recorded', { type: 'site-channels' })));
  } else if (tab === 'neighbors') {
    const data = await api('/api/site/neighbors', { guid });
    content.append(section('Neighbors', table(data.rows || [], [
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { id: 'system', label: 'System', render: (row) => hexDecimal(row.system_id, 3), sortValue: (row) => Number(row.system_id || 0) },
      { id: 'rfss', label: 'RFSS', render: (row) => hexDecimal(row.rfss, 2), sortValue: (row) => Number(row.rfss || 0) },
      { id: 'site', label: 'Site', render: (row) => hexDecimal(row.site, 2), sortValue: (row) => Number(row.site || 0) },
      { label: 'LCN', key: 'channel_descriptor' },
      { id: 'control-frequency', label: 'Control MHz', render: (row) => frequency(row.downlink_hz), className: 'numeric', sortValue: (row) => Number(row.downlink_hz || 0) },
      { id: 'advertised-status', label: 'Advertised Status', render: (row) => neighborStatus(row.status), sortValue: (row) => row.status || '' },
      { label: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No neighbors recorded', { type: 'site-neighbors' })));
  } else if (tab === 'band-plan') {
    const data = await api('/api/site/bands', { guid });
    content.append(section('Band Plan', table(data.rows || [], [
      { label: 'Band', key: 'band', className: 'numeric' },
      { id: 'base', label: 'Base MHz', render: (row) => frequency(row.base_hz), className: 'numeric', sortValue: (row) => Number(row.base_hz || 0) },
      { id: 'spacing', label: 'Spacing kHz', render: (row) => row.spacing_hz ? (row.spacing_hz / 1000).toFixed(3) : '', className: 'numeric', sortValue: (row) => Number(row.spacing_hz || 0) },
      { label: 'Bandwidth Hz', key: 'bandwidth', className: 'numeric' },
      { id: 'offset', label: 'Offset MHz', render: (row) => row.transmit_offset_hz ? (row.transmit_offset_hz / 1000000).toFixed(5) : '', className: 'numeric', sortValue: (row) => Number(row.transmit_offset_hz || 0) },
      { id: 'tdma', label: 'TDMA', render: (row) => yesNo(row.tdma), sortValue: (row) => Boolean(row.tdma) },
      { label: 'Slots', key: 'timeslots', className: 'numeric' },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No band plan recorded', { type: 'site-bands' })));
  } else if (tab === 'patches') {
    const data = await api('/api/site/patches', { guid });
    const talkgroups = new Map();
    const radios = new Map();
    (data.talkgroups || []).forEach((row) => {
      if (!talkgroups.has(row.patch_group)) talkgroups.set(row.patch_group, []);
      talkgroups.get(row.patch_group).push(row);
    });
    (data.radios || []).forEach((row) => {
      if (!radios.has(row.patch_group)) radios.set(row.patch_group, []);
      radios.get(row.patch_group).push(row);
    });
    const memberLinks = (values, builder) => {
      const span = node('span');
      (values || []).forEach((value, index) => {
        if (index) span.append(document.createTextNode(', '));
        span.append(builder(value));
      });
      return span;
    };
    const groups = data.groups || [];
    const columns = [
      { id: 'patch-id', label: 'Patch TGID', render: (row) => talkgroupLink(site, row.patch_group), className: 'numeric', sortValue: (row) => Number(row.patch_group) },
      { id: 'patch-name', label: 'Patch Name', render: (row) => row.patch_alias_name ?
        talkgroupLink(site, row.patch_group, row.patch_alias_name) : '', className: 'alias-cell', sortValue: (row) => row.patch_alias_name || '' },
      { id: 'member-talkgroup-ids', label: 'Member TGIDs', render: (row) =>
        memberLinks(talkgroups.get(row.patch_group), (member) => talkgroupLink(site, member.talkgroup_id)) },
      { id: 'member-talkgroup-names', label: 'Talkgroup Names', className: 'alias-cell', render: (row) =>
        memberLinks(talkgroups.get(row.patch_group), (member) => member.alias_name ?
          talkgroupLink(site, member.talkgroup_id, member.alias_name) : '') },
      { id: 'member-radio-ids', label: 'Radio IDs', render: (row) =>
        memberLinks(radios.get(row.patch_group), (member) => radioLink(site, member.radio_id)) },
      { id: 'member-radio-names', label: 'Radio Names', className: 'alias-cell', render: (row) =>
        memberLinks(radios.get(row.patch_group), (member) => member.alias_name ?
          radioLink(site, member.radio_id, member.alias_name) : '') },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    if (groups.some((row) => Number(row.version))) columns.splice(2, 0,
      { label: 'Version', key: 'version', className: 'numeric' });
    content.append(section('Patches', table(groups, columns, 'No patches recorded', { type: 'site-patches' })));
  } else if (tab === 'activity') {
    await renderActivity({ guid });
  } else {
    content.append(section('Site Info', keyValues([
      ['System', systemLink(site)], ['GUID', site.guid], ['Name', site.channel_name],
      ['Alias List', site.alias_list_name], ['Protocol', site.protocol], ['Decoder', site.decoder],
      ['Callsign', site.callsign], ['WACN', hexDecimal(site.wacn, 5)],
      ['SysID', hexDecimal(site.system_id, 3)], ['NAC', hexDecimal(site.nac, 3)],
      ['RFSS', hexDecimal(site.rfss, 2)], ['Site', hexDecimal(site.site, 2)],
      ['Local Registration Area', hexDecimal(site.lra, 2)], ['MFID', site.mfid_display],
      ['Broadcast Clock', dateTime(site.broadcast_clock_ms)],
      ['Data', yesNoKnown(site.data_service)], ['Data Access', site.data_access],
      ['Working Unit ID Lease Time', site.wuid_lease_minutes == null ? '' : `${number(site.wuid_lease_minutes)} minutes`],
      ['Unit registration over control channel', yesNoKnown(site.registration_service)],
      ['TDMA', yesNoKnown(site.tdma)],
      ['u-Slots', site.micro_slots == null ? '' : number(site.micro_slots)],
      ['Voice', yesNoKnown(site.voice_service)],
      ['Control Frequency', frequency(site.current_control_hz)],
      ['First Seen', dateTime(site.first_seen_ms)], ['Last Seen', dateTime(site.last_seen_ms)],
      ['Channels', number(site.channels)], ['Neighbors', number(site.neighbors)],
      ['Band Plans', number(site.bands)], ['Patches', number(site.patches)]
    ])));
  }
}

function activityColumns() {
  return [
    { id: 'time', label: 'Time', render: (row) => dateTime(row.observed_at_ms), sortValue: (row) => Number(row.observed_at_ms || 0) },
    { label: 'Action', key: 'action' },
    { label: 'Event', key: 'event_type' },
    { id: 'source', label: 'Source', render: (row) => radioLink(row, row.source_radio_id), className: 'numeric', sortValue: (row) => Number(row.source_radio_id || 0) },
    { id: 'source-alias', label: 'Source Alias', render: (row) => row.source_alias_name ? radioLink(row, row.source_radio_id, row.source_alias_name) : '', className: 'alias-cell', sortValue: (row) => row.source_alias_name || '' },
    { id: 'target', label: 'Target', render: (row) => TALKGROUP_TARGET_KINDS.has(Number(row.target_kind_code)) ? talkgroupLink(row, row.target_id) : row.target_id, className: 'numeric', sortValue: (row) => Number(row.target_id || 0) },
    { id: 'target-alias', label: 'Target Alias', render: (row) => row.target_alias_name || '', className: 'alias-cell', sortValue: (row) => row.target_alias_name || '' },
    { id: 'frequency', label: 'MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sortValue: (row) => Number(row.frequency_hz || 0) },
    { label: 'LCN', key: 'lcn' },
    { label: 'Slot', key: 'timeslot', className: 'numeric' },
    { id: 'encryption', label: 'Encryption', render: (row) => row.encrypted ? `${hexDecimal(row.encryption_algorithm_id, 2)}:${hexDecimal(row.encryption_key_id)}` : '', className: 'encrypted', sortValue: (row) => row.encrypted ? `${row.encryption_algorithm_id}:${row.encryption_key_id}` : '' }
  ];
}

async function renderActivity(scopeParameters) {
  if (!detailedHistoryAvailable()) {
    content.append(section('Activity', node('div', 'empty',
      'Detailed history logging is not running. Summary views and Live Systems remain available.')));
    return;
  }
  const data = await api('/api/activity', {
    ...scopeParameters,
    before_id: route.get('before_id'),
    hide_grants: true,
    limit: 200
  });
  const columns = activityColumns();
  const activityTable = table(withoutGrantActions(data.rows), columns, 'No activity recorded',
    { type: 'activity', rowKey: (row) => row.id });
  const body = activityTable.querySelector('tbody');
  const block = section('Activity', activityTable);
  const controls = node('div', 'pager');
  controls.append(route.get('before_id') ? anchor('Newest', currentHref({ before_id: null }), 'button secondary') :
    node('span', 'button disabled', 'Newest'));
  controls.append(data.hasMore ? anchor('Older', currentHref({ before_id: data.nextBeforeId }), 'button secondary') :
    node('span', 'button disabled', 'Older'));
  block.append(controls);
  content.append(block);

  if (!route.get('before_id')) {
    const source = liveConnection('/live/activity', scopeParameters);
    source.addEventListener('activity', (event) => {
      const row = JSON.parse(event.data);
      if (String(row.action || '').toUpperCase() === 'GRANT') return;
      if (row.id !== null && row.id !== undefined && body.querySelector(`[data-id="${row.id}"]`)) return;
      activityTable.tableController.addRow(row, { prepend: true, limit: 200 });
    });
  }
}

async function renderConventional() {
  const page = await api('/api/conventional', pageParameters());
  content.append(pageHeader('Conventional', 'Started conventional analog and P25 channel history'));
  const columns = [
    { label: 'Name', render: (row) => anchor(row.channel_name || row.context_key,
      href('conventional-detail', { context: row.context_key, tab: 'info' })), className: 'alias-cell', sort: 'name', sortValue: (row) => row.channel_name || row.context_key },
    { id: 'protocol', label: 'Protocol', render: (row) => protocol(row.protocol_code), sort: 'protocol', sortValue: (row) => protocol(row.protocol_code) },
    { label: 'Decoder', key: 'decoder', sort: 'decoder' },
    { id: 'frequency', label: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency', sortValue: (row) => Number(row.frequency_hz || 0) },
    { label: 'Slot', key: 'timeslot', className: 'numeric', sort: 'slot' },
    { id: 'nac', label: 'NAC', render: (row) => hexDecimal(row.nac, 3), sort: 'nac', sortValue: (row) => Number(row.nac || 0) },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
  content.append(pagedSection('Conventional Channels', page, columns, 'Search name or frequency', 'conventional'));
}

async function renderConventionalDetail() {
  const contextKey = route.get('context');
  if (!contextKey) throw new Error('Conventional context is missing from the URL');
  const data = await api('/api/conventional/detail', { context: contextKey });
  const context = data.context;
  const tab = route.get('tab') || 'info';
  content.append(pageHeader(context.channel_name || context.context_key, protocol(context.protocol_code)), tabs([
    { id: 'info', label: 'Info', href: href('conventional-detail', { context: contextKey, tab: 'info' }) },
    { id: 'activity', label: 'Activity', href: href('conventional-detail', { context: contextKey, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' }
  ], tab));

  if (tab === 'activity') {
    await renderActivity({ context: contextKey });
  } else {
    content.append(section('Channel Info', keyValues([
      ['Name', context.channel_name], ['Context', context.context_key], ['GUID', context.guid],
      ['Protocol', protocol(context.protocol_code)], ['Decoder', context.decoder],
      ['Alias List', context.alias_list_name], ['Frequency', frequency(context.primary_frequency_hz)],
      ['NAC', hexDecimal(context.nac, 3)], ['First Seen', dateTime(context.first_seen_ms)],
      ['Last Seen', dateTime(context.last_seen_ms)]
    ])));
    content.append(section('Frequency Summaries', table(data.summaries || [], [
      { id: 'frequency', label: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sortValue: (row) => Number(row.frequency_hz || 0) },
      { label: 'Slot', key: 'timeslot', className: 'numeric' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'first-seen', label: 'First Seen', render: (row) => dateTime(row.first_seen_ms), sortValue: (row) => Number(row.first_seen_ms || 0) },
      { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No frequency summaries recorded', { type: 'conventional-frequencies' })));
  }
}

function renderCredits() {
  content.append(pageHeader('Credits & Licensing', 'Open-source authorship, source lineage, and license terms'));

  const project = node('div', 'credits-copy');
  project.append(node('p', '', 'Copyright © 2014-2026 Dennis Sheirer and respective contributors.'));
  const lineage = node('p');
  lineage.append('sdrtrunk-vce is a modified version of ',
    externalAnchor('SDRTrunk', 'https://github.com/DSheirer/sdrtrunk'),
    ', created by Dennis Sheirer. It includes work from SDRTrunk contributors and optimization and platform work ',
    'associated with the ', externalAnchor('W6BAZ experimental fork', 'https://github.com/bazineta/sdrtrunk'), '.');
  project.append(lineage);
  content.append(section('Project', project));

  const license = node('div', 'credits-copy');
  const licenseText = node('p');
  licenseText.append('This program is free software licensed under the ',
    externalAnchor('GNU General Public License, version 3 or later',
      'https://www.gnu.org/licenses/gpl-3.0.html'),
    '. It is distributed without any warranty, including implied warranties of merchantability or fitness for a ',
    'particular purpose.');
  license.append(licenseText);
  const licenseFiles = node('p');
  licenseFiles.append(externalAnchor('Read the complete GNU GPL v3 license', '/LICENSE.txt'), ' · ',
    externalAnchor('Read the project notice', '/NOTICE.txt'));
  license.append(licenseFiles);
  content.append(section('GNU GPL v3', license));

  const projects = node('ul', 'credits-list');
  [
    ['JMBE', 'https://github.com/DSheirer/jmbe'],
    ['JavaFX', 'https://openjfx.io/'],
    ['SQLite JDBC', 'https://github.com/xerial/sqlite-jdbc'],
    ['Jackson', 'https://github.com/FasterXML/jackson'],
    ['Guava', 'https://github.com/google/guava'],
    ['JTransforms', 'https://github.com/wendykierp/JTransforms'],
    ['usb4java', 'https://usb4java.org/'],
    ['ControlsFX', 'https://github.com/controlsfx/controlsfx'],
    ['JIDE OSS', 'https://github.com/jidesoft/jide-oss'],
    ['OP25', 'https://github.com/boatbod/op25'],
    ['DSD-FME', 'https://github.com/lwvmobile/dsd-fme']
  ].forEach(([label, target]) => {
    const item = node('li');
    item.append(externalAnchor(label, target));
    projects.append(item);
  });
  const acknowledgements = node('div', 'credits-copy');
  acknowledgements.append(node('p', '', 'This application builds on open-source libraries and radio-decoding work ' +
    'maintained by their respective authors. Each component remains under its own copyright and license terms.'),
    projects);
  content.append(section('Open-source acknowledgements', acknowledgements));
}

function activateNavigation(view) {
  const parent = ['system', 'talkgroup', 'radio'].includes(view) ? 'systems' :
    (view === 'site' ? 'sites' : (view === 'conventional-detail' ? 'conventional' : view));
  document.querySelectorAll('.primary-nav a').forEach((link) =>
    link.classList.toggle('active', link.dataset.view === parent));
}

function loggingAvailabilitySignature() {
  const logging = statsLoggingState();
  const historyMode = logging.historyActive ? 'active' : (logging.historyRetained ? 'retained' : 'unavailable');
  return [logging.available, logging.summaryActive, historyMode, logging.state].join('|');
}

async function loadStatus(refreshCurrentView = false) {
  const previousSignature = loggingAvailabilitySignature();
  try {
    serviceStatus = await api('/api/status');
    const database = serviceStatus.database || {};
    const logging = statsLoggingState();
    const size = (Number(database.databaseBytes || 0) / 1048576).toFixed(1);
    const summaryLabel = logging.summaryActive ? 'Summaries on' :
      (logging.summaryConfigured ? 'Summaries unavailable' : 'Summaries off');
    const historyLabel = logging.historyActive ? 'History on' : (logging.historyRetained ? 'History paused' :
      (logging.historyConfigured ? 'History unavailable' : 'History off'));
    document.getElementById('server-status').textContent =
      `${summaryLabel} · ${historyLabel} · ${size} MB`;
  } catch (error) {
    serviceStatus = null;
    document.getElementById('server-status').textContent = 'Database unavailable';
  }

  const currentView = route.get('view') || 'dashboard';
  if (refreshCurrentView && previousSignature !== loggingAvailabilitySignature() &&
      !['live', 'credits'].includes(currentView)) {
    render();
  }
}

async function render() {
  const view = route.get('view') || 'dashboard';
  document.body.dataset.view = view;
  closePageConnections();
  activateNavigation(view);
  content.replaceChildren(node('div', 'loading', 'Loading'));

  try {
    content.replaceChildren();
    const handlers = {
      dashboard: renderDashboard,
      live: renderLive,
      systems: renderSystems,
      sites: renderSites,
      system: renderSystem,
      talkgroup: renderTalkgroup,
      radio: renderRadio,
      site: renderSite,
      conventional: renderConventional,
      'conventional-detail': renderConventionalDetail,
      credits: renderCredits
    };
    await (handlers[view] || renderDashboard)();
    const notice = databaseLoggingNotice(view);
    if (notice) {
      const header = content.querySelector('.page-header');
      if (header) header.after(notice);
      else content.prepend(notice);
    }
  } catch (error) {
    const notice = databaseLoggingNotice(view);
    content.replaceChildren(...[notice, node('div', 'error', error.message)].filter(Boolean));
  }
}

const TALKGROUP_TARGET_KINDS = new Set([1, 3]);
document.addEventListener('click', (event) => {
  const link = event.target.closest('a');
  if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey ||
      event.altKey || link.target || link.hasAttribute('download')) return;
  const target = new URL(link.href, window.location.href);
  if (target.origin !== window.location.origin || target.pathname !== '/') return;
  event.preventDefault();
  window.history.pushState({}, '', `${target.pathname}${target.search}${target.hash}`);
  route = new URLSearchParams(target.search);
  render();
});
window.addEventListener('popstate', () => {
  route = new URLSearchParams(window.location.search);
  render();
});
initializePlaybackHeader();
loadStatus().finally(render);
window.setInterval(() => {
  if (!document.hidden) loadStatus(true);
}, 10_000);
