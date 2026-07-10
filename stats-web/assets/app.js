let route = new URLSearchParams(window.location.search);
const content = document.getElementById('content');
const tableOnly = route.get('layout') === 'table';

if (tableOnly) {
  document.body.classList.add('table-only');
}

function node(tag, className, textValue) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (textValue !== undefined && textValue !== null) element.textContent = String(textValue);
  return element;
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

function badge(label, className = '') {
  return node('span', `badge ${className}`.trim(), label);
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
  return talkgroupLink(row, id, name ? `${id} · ${name}` : String(id));
}

function channelRole(role) {
  const values = {
    primary_control: ['Primary', 'role-primary'],
    current_control: ['Primary', 'role-primary'],
    secondary_control: ['Secondary', 'role-secondary'],
    traffic: ['Voice', 'role-voice'],
    voice: ['Voice', 'role-voice'],
    fdma_data: ['Data', 'role-data'],
    tdma_data: ['Data', 'role-data']
  };
  const value = values[role] || [String(role || '').replaceAll('_', ' '), ''];
  return value[0] ? badge(value[0], value[1]) : '';
}

function channelRoles(row) {
  const roles = [];
  if (number(row.primary_control_observations) > 0) roles.push(badge('Primary', 'role-primary'));
  if (number(row.alternate_control_observations) > 0) roles.push(badge('Secondary', 'role-secondary'));
  if (number(row.traffic_observations) > 0) roles.push(badge('Voice', 'role-voice'));
  if (String(row.role || '').endsWith('_data')) roles.push(badge('Data', 'role-data'));
  return roles.length ? fragment(...roles) : channelRole(row.role);
}

function pageHeader(title, subtitle) {
  const wrapper = node('div', 'page-header');
  const labels = node('div');
  labels.append(node('h1', 'page-title', title));
  if (subtitle) labels.append(node('div', 'page-subtitle', subtitle));
  wrapper.append(labels);
  return wrapper;
}

function tabs(items, active) {
  const bar = node('nav', 'tabs');
  bar.setAttribute('aria-label', 'Section navigation');
  items.forEach((item) => {
    const link = anchor(item.label, item.href, item.id === active ? 'active' : '');
    bar.append(link);
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

function table(rows, columns, emptyText = 'No rows') {
  const wrapper = node('div', 'table-wrap');
  const element = node('table');
  const head = node('thead');
  const headRow = node('tr');

  columns.forEach((column) => {
    const header = node('th', column.className || '');
    if (column.sort) {
      const currentSort = route.get('sort');
      const currentDirection = route.get('direction') || 'desc';
      const direction = currentSort === column.sort && currentDirection === 'desc' ? 'asc' : 'desc';
      header.append(anchor(column.label, currentHref({ sort: column.sort, direction, offset: null })));
    } else {
      header.textContent = column.label;
    }
    headRow.append(header);
  });

  head.append(headRow);
  element.append(head);
  const body = node('tbody');

  if (!rows || rows.length === 0) {
    const row = node('tr');
    const cell = node('td', 'empty', emptyText);
    cell.colSpan = columns.length;
    row.append(cell);
    body.append(row);
  } else {
    rows.forEach((data) => {
      const row = node('tr');
      columns.forEach((column) => {
        const cell = node('td', column.className || '');
        const value = column.render ? column.render(data) : data[column.key];
        cell.append(valueNode(value));
        row.append(cell);
      });
      body.append(row);
    });
  }

  element.append(body);
  wrapper.append(element);
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

function pagedSection(title, page, columns, searchPlaceholder) {
  return fragment(searchPlaceholder ? searchBar(searchPlaceholder) : null,
    (() => {
      const block = section(title, table(page.rows, columns));
      block.append(pager(page));
      return block;
    })());
}

function actionCounts(row) {
  return Object.entries(row)
    .filter(([key, value]) => key.endsWith('_count') && Number(value) > 0)
    .map(([key, value]) => [key.replace(/_count$/, '').replaceAll('_', ' '), number(value)]);
}

const actionColors = [
  '#0b7168', '#2f6da5', '#cc7a00', '#9d174d', '#6b4fa3', '#3c7a3c', '#a65a3a', '#526778',
  '#b33b5e', '#0085a1', '#7c6b2f', '#4d7f7b', '#865d9c', '#8b5d2e', '#556b2f', '#737c86'
];

function actionPie(rows) {
  const actions = rows.filter((row) => Number(row.hits) > 0);
  const total = actions.reduce((sum, row) => sum + Number(row.hits), 0);
  if (!total) return node('div', 'empty', 'No actions recorded in the last 24 hours');

  let start = 0;
  const segments = actions.map((row, index) => {
    const end = start + (Number(row.hits) / total * 100);
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
    const percentage = Number(row.hits) / total * 100;
    item.append(swatch, label, node('span', 'action-value', `${number(row.hits)} · ${percentage.toFixed(1)}%`));
    legend.append(item);
  });

  return fragment(chart, legend);
}

function hourlyLineGraph(rows) {
  const values = (rows || []).map((row) => ({
    hour: Number(row.hour_ms),
    hits: Number(row.hits || 0)
  }));
  if (!values.length) return node('div', 'empty', 'No hourly hit data');

  const width = 960;
  const height = 270;
  const margin = { top: 18, right: 20, bottom: 42, left: 55 };
  const plotWidth = width - margin.left - margin.right;
  const plotHeight = height - margin.top - margin.bottom;
  const maximum = Math.max(1, ...values.map((value) => value.hits));
  const roundedMaximum = Math.max(4, Math.ceil(maximum / 4) * 4);
  const svgNamespace = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNamespace, 'svg');
  svg.setAttribute('class', 'hits-line-svg');
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', 'Total grant hits per hour for the last 24 hours');

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

  const points = values.map((value, index) => {
    const x = margin.left + (values.length === 1 ? plotWidth / 2 : plotWidth * index / (values.length - 1));
    const y = margin.top + plotHeight - (plotHeight * value.hits / roundedMaximum);
    return { ...value, x, y };
  });
  const path = points.map((point, index) => `${index ? 'L' : 'M'} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ');
  svg.append(svgNode('path', { d: path, class: 'hits-line-path' }));

  points.forEach((point, index) => {
    const circle = svgNode('circle', { cx: point.x, cy: point.y, r: 3.5, class: 'hits-line-point' });
    circle.append(svgNode('title', {}, `${new Date(point.hour).toLocaleString()}: ${number(point.hits)} hits`));
    svg.append(circle);

    if (index % 4 === 0 || index === points.length - 1) {
      svg.append(svgNode('text', { x: point.x, y: height - 15, class: 'chart-axis-label',
        'text-anchor': 'middle' }, new Date(point.hour).toLocaleTimeString([], { hour: 'numeric' })));
    }
  });

  const wrapper = node('div', 'hits-line-chart');
  wrapper.append(svg);
  return wrapper;
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
    { id: 'radios', label: 'Radios', href: href('system', { ...values, tab: 'radios' }) }
  ], active);
}

function entityTabs(view, system, id, active, radio) {
  const values = { ...scope(system), id };
  return tabs(radio ? [
    { id: 'info', label: 'Info', href: href(view, { ...values, tab: 'info' }) },
    { id: 'talkgroups', label: 'Talkgroups', href: href(view, { ...values, tab: 'talkgroups' }) },
    { id: 'activity', label: 'Activity', href: href(view, { ...values, tab: 'activity' }) }
  ] : [
    { id: 'info', label: 'Info', href: href(view, { ...values, tab: 'info' }) },
    { id: 'radios', label: 'Radios', href: href(view, { ...values, tab: 'radios' }) },
    { id: 'activity', label: 'Activity', href: href(view, { ...values, tab: 'activity' }) }
  ], active);
}

function siteTabs(site, active) {
  const values = { guid: site.guid };
  return tabs([
    { id: 'info', label: 'Info', href: href('site', { ...values, tab: 'info' }) },
    { id: 'channels', label: 'Channels', href: href('site', { ...values, tab: 'channels' }) },
    { id: 'neighbors', label: 'Neighbors', href: href('site', { ...values, tab: 'neighbors' }) },
    { id: 'band-plan', label: 'Band Plan', href: href('site', { ...values, tab: 'band-plan' }) },
    { id: 'patches', label: 'Patches', href: href('site', { ...values, tab: 'patches' }) },
    { id: 'activity', label: 'Activity', href: href('site', { ...values, tab: 'activity' }) }
  ], active);
}

const siteColumns = [
  { label: 'System', render: systemLink },
  { label: 'RFSS', key: 'rfss', render: (row) => hexDecimal(row.rfss, 2), className: 'numeric', sort: 'rfss' },
  { label: 'Site', key: 'site', render: (row) => hexDecimal(row.site, 2), className: 'numeric', sort: 'site' },
  { label: 'Name', render: (row) => siteLink(row), className: 'alias-cell', sort: 'name' },
  { label: 'Control MHz', render: (row) => frequency(row.current_control_hz), className: 'numeric' },
  { label: 'Channels', key: 'channels', className: 'numeric' },
  { label: 'Neighbors', key: 'neighbors', className: 'numeric' },
  { label: 'Bands', key: 'bands', className: 'numeric' },
  { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen' }
];

const talkgroupColumns = [
  { label: 'Talkgroup', render: (row) => talkgroupAliasLink(row, row.talkgroup_id), className: 'alias-cell', sort: 'id' },
  { label: 'Group', key: 'alias_group', className: 'alias-cell' },
  { label: 'Hits', render: (row) => number(row.hits), className: 'numeric', sort: 'hits' },
  { label: 'Encrypted Events', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted' },
  { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen' }
];

const radioColumns = [
  { label: 'ID', render: (row) => radioLink(row), className: 'numeric', sort: 'id' },
  { label: 'Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell' },
  { label: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell' },
  { label: 'Hits', render: (row) => number(row.hits), className: 'numeric', sort: 'hits' },
  { label: 'Encrypted Events', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted' },
  { label: 'Affiliated TG', render: (row) => talkgroupAliasLink(row, row.affiliated_talkgroup_id,
    'affiliated_talkgroup_alias_'), className: 'alias-cell' },
  { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen' }
];

async function renderDashboard() {
  const dashboard = await api('/api/dashboard');
  const counts = dashboard.counts || {};
  content.append(pageHeader('Dashboard', `Last activity ${dateTime(dashboard.lastSeenMs) || 'not recorded'}`));
  content.append(metrics([
    ['Systems', counts.systems], ['Sites', counts.sites], ['Talkgroups', counts.talkgroups],
    ['Radios', counts.radios], ['Frequencies', counts.frequencies], ['Conventional', counts.conventional]
  ]));
  content.append(section('Total Hits Per Hour', hourlyLineGraph(dashboard.hitsPerHour || [])));
  const sites = section('Recent Sites', table(dashboard.recentSites || [], siteColumns));
  const actions = section('24 Hour Actions', node('div', 'action-chart'));
  actions.lastChild.append(actionPie(dashboard.actionMix || []));
  content.append(node('div', 'split'));
  content.lastChild.append(sites, actions);
  const talkgroups = section('Top Talkgroups', table(dashboard.topTalkgroups || [], talkgroupColumns));
  const radios = section('Top Radios', table(dashboard.topRadios || [], radioColumns));
  content.append(node('div', 'split'));
  content.lastChild.append(talkgroups, radios);
}

function liveSystemsSection() {
  const tables = new Map();
  const tabNodes = new Map();
  const rowNodes = new Map();
  const tabBar = node('div', 'systems-live-tabs');
  const connection = badge('Connecting', 'state-stale');
  const tableElement = node('table', 'data-table systems-live-table');
  const head = node('thead');
  const headerRow = node('tr');
  ['Status', 'LCN', 'Frequency', 'Source Alias', 'Source', 'Target Alias', 'Target', 'Decoder']
    .forEach((label) => headerRow.append(node('th', '', label)));
  head.append(headerRow);
  const body = node('tbody');
  tableElement.append(head, body);
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
    const statusText = row.status === 'ENCRYPTED' && row.encryption_details ? row.encryption_details : row.status;
    cellText(cells[0], statusText);
    cellText(cells[1], row.lcn);
    cellText(cells[2], frequency(row.frequency_hz));
    cellText(cells[3], row.source_alias);
    cellText(cells[4], row.source_id);
    cellText(cells[5], row.target_alias);
    cellText(cells[6], row.target_id);
    cellText(cells[7], row.decoder);
    cells[0].className = `activity-status state-${String(row.status || 'idle').toLowerCase()}`;
    cells[1].className = row.control_role === 'CURRENT' ? 'control-current' :
      (row.control_role === 'ALTERNATE' ? 'control-alternate' : '');
    cells[2].className = cells[1].className;
    element.classList.toggle('selected', selectedRowKey === row.key);
  };

  const createRow = (row) => {
    const element = node('tr');
    element.dataset.key = row.key;
    for (let index = 0; index < 8; index += 1) element.append(node('td'));
    element.addEventListener('click', () => {
      selectedRowKey = row.key;
      rowNodes.forEach((candidate, key) => candidate.classList.toggle('selected', key === selectedRowKey));
    });
    updateRow(element, row);
    return element;
  };

  const showTable = (tableId) => {
    const value = tables.get(tableId);
    if (!value) return;
    activeTableId = tableId;
    selectedRowKey = null;
    rowNodes.clear();
    body.replaceChildren();
    (value.rows || []).forEach((row) => {
      const element = createRow(row);
      rowNodes.set(row.key, element);
      body.append(element);
    });
    if (!value.rows?.length) {
      const empty = node('tr', 'empty');
      const message = node('td', '', 'No channels observed');
      message.colSpan = 8;
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
    if (!rowNodes.size) {
      const empty = node('tr', 'empty');
      const message = node('td', '', 'No channels observed');
      message.colSpan = 8;
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
  content.append(pageHeader('Live Systems', 'The same persistent channel activity shown in the Java Systems view'));
  content.append(liveSystemsSection());
}

async function renderSystems() {
  const page = await api('/api/systems', pageParameters());
  content.append(pageHeader('Systems', 'P25 systems are grouped by WACN and System ID'));
  content.append(liveSystemsSection());
  const columns = [
    { label: 'WACN', render: (row) => systemLink(row, hexDecimal(row.wacn, 5)), sort: 'wacn' },
    { label: 'System', render: (row) => systemLink(row, hexDecimal(row.system_id, 3)), sort: 'system_id' },
    { label: 'Site Names', key: 'site_names', className: 'alias-cell' },
    { label: 'Sites', key: 'sites', className: 'numeric', sort: 'sites' },
    { label: 'Talkgroups', key: 'talkgroups', className: 'numeric', sort: 'talkgroups' },
    { label: 'Radios', key: 'radios', className: 'numeric', sort: 'radios' },
    { label: 'Affiliated', key: 'affiliations', className: 'numeric' },
    { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen' }
  ];
  content.append(pagedSection('System Directory', page, columns, 'Search system or site name'));
}

async function renderSites() {
  const page = await api('/api/sites', pageParameters());
  content.append(pageHeader('Sites', 'All observed trunked sites with their parent system'));
  content.append(pagedSection('Sites', page, siteColumns, 'Search site name or GUID'));
}

async function renderSystem() {
  const systemScope = requiredSystemScope();
  const response = await api('/api/system', systemScope);
  const system = response.system;
  const tab = route.get('tab') || 'info';
  content.append(pageHeader(systemLabel(system), system.site_names || 'P25 trunked system'), systemTabs(system, tab));

  if (tab === 'sites') {
    const page = await api('/api/system/sites', pageParameters(systemScope));
    content.append(pagedSection('Sites', page, siteColumns, 'Search site name or GUID'));
  } else if (tab === 'talkgroups') {
    const page = await api('/api/system/talkgroups', pageParameters(systemScope));
    content.append(pagedSection('Talkgroups', page, talkgroupColumns, 'Search talkgroup ID'));
  } else if (tab === 'radios') {
    const page = await api('/api/system/radios', pageParameters(systemScope));
    content.append(pagedSection('Radios', page, radioColumns, 'Search radio ID'));
  } else {
    content.append(metrics([
      ['Sites', system.sites], ['Talkgroups', system.talkgroups], ['Radios', system.radios],
      ['Affiliated', system.affiliations], ['Activity Hits', system.activity_hits]
    ]));
    content.append(section('System Info', keyValues([
      ['System', systemLabel(system)],
      ['First Seen', dateTime(system.first_seen_ms)], ['Last Seen', dateTime(system.last_seen_ms)]
    ])));
    content.append(section('Observed Actions', table(response.actionCounts || [], [
      { label: 'Action', key: 'action' }, { label: 'Count', render: (row) => number(row.hits), className: 'numeric' }
    ])));
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
      { label: 'Radio', render: (row) => radioLink(row), className: 'numeric', sort: 'radio' },
      { label: 'Alias', render: (row) => row.radio_alias_name ? radioLink(row, row.radio_id, row.radio_alias_name) : '', className: 'alias-cell' },
      { label: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell' },
      { label: 'Affiliated', render: (row) => checkbox(affiliated.has(Number(row.radio_id))), className: 'center' },
      { label: 'Hits', render: (row) => number(row.hits), className: 'numeric', sort: 'hits' },
      { label: 'Encrypted Events', render: (row) => number(row.encrypted_count), className: 'numeric encrypted' },
      { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen' }
    ];
    content.append(pagedSection('Radios', relationships, columns));
  } else if (tab === 'activity') {
    await renderActivity({ ...systemScope, talkgroup_id: id });
  } else {
    const affiliationLink = anchor(number(talkgroup.affiliated_radios),
      href('talkgroup', { ...scope(talkgroup), id, tab: 'radios' }));
    content.append(section('Talkgroup Info', keyValues([
      ['System', systemLink(talkgroup)], ['Talkgroup ID', id], ['Alias', aliasLabel(talkgroup)],
      ['Group', talkgroup.alias_group], ['First Seen', dateTime(talkgroup.first_seen_ms)],
      ['Last Seen', dateTime(talkgroup.last_seen_ms)], ['Hits', number(talkgroup.hits)],
      ['Radios', number(talkgroup.radios)], ['Currently Affiliated', affiliationLink],
      ['Encrypted Events', number(talkgroup.encrypted_count)],
      ['Last Source', radioLink(talkgroup, talkgroup.last_source_radio_id)],
      ['Last Alg ID', hexDecimal(talkgroup.last_encryption_algorithm_id, 2)],
      ['Last Key ID', hexDecimal(talkgroup.last_encryption_key_id)]
    ])));
    content.append(section('Action Counts', table(actionCounts(talkgroup).map(([action, hits]) => ({ action, hits })), [
      { label: 'Action', key: 'action' }, { label: 'Count', key: 'hits', className: 'numeric' }
    ])));
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
      { label: 'Talkgroup', render: (row) => talkgroupAliasLink(row, row.talkgroup_id, 'talkgroup_alias_'), className: 'alias-cell', sort: 'talkgroup' },
      { label: 'Hits', render: (row) => number(row.hits), className: 'numeric', sort: 'hits' },
      { label: 'Encrypted Events', render: (row) => number(row.encrypted_count), className: 'numeric encrypted' },
      { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen' }
    ];
    content.append(pagedSection('Talkgroups', relationships, columns));
  } else if (tab === 'activity') {
    await renderActivity({ ...systemScope, radio_id: id });
  } else {
    content.append(section('Radio Info', keyValues([
      ['System', systemLink(radio)], ['Radio ID', id], ['Alias', aliasLabel(radio)],
      ['Talker Alias', radio.last_talker_alias], ['Talker Alias Seen', dateTime(radio.last_talker_alias_seen_ms)],
      ['Current Affiliation', talkgroupAliasLink(radio, radio.affiliated_talkgroup_id,
        'affiliated_talkgroup_alias_')],
      ['Affiliation Updated', dateTime(radio.affiliation_updated_at_ms)],
      ['First Seen', dateTime(radio.first_seen_ms)], ['Last Seen', dateTime(radio.last_seen_ms)],
      ['Hits', number(radio.hits)], ['Talkgroups', number(radio.talkgroups)],
      ['Encrypted Events', number(radio.encrypted_count)],
      ['Last Alg ID', hexDecimal(radio.last_encryption_algorithm_id, 2)],
      ['Last Key ID', hexDecimal(radio.last_encryption_key_id)]
    ])));
    content.append(section('Action Counts', table(actionCounts(radio).map(([action, hits]) => ({ action, hits })), [
      { label: 'Action', key: 'action' }, { label: 'Count', key: 'hits', className: 'numeric' }
    ])));
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

  if (tab === 'channels') {
    const data = await api('/api/site/channels', { guid });
    const columns = [
      { label: 'LCN', key: 'descriptor' },
      { label: 'Role', key: 'role', render: channelRoles },
      { label: 'Downlink MHz', render: (row) => frequency(row.downlink_hz), className: 'numeric' },
      { label: 'Uplink MHz', render: (row) => frequency(row.uplink_hz), className: 'numeric' },
      { label: 'TDMA', render: (row) => yesNo(row.tdma) },
      { label: 'Slots', key: 'timeslots', className: 'numeric' },
      { label: 'State', render: (row) => stateBadge(row.state) },
      { label: 'Observations', key: 'observation_count', className: 'numeric' },
      { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
    ];
    const rows = data.rows || [];
    content.append(section('Channels', table(rows, columns)));
  } else if (tab === 'neighbors') {
    const data = await api('/api/site/neighbors', { guid });
    content.append(section('Neighbors', table(data.rows || [], [
      { label: 'State', render: (row) => stateBadge(row.state) },
      { label: 'System', render: (row) => hexDecimal(row.system_id, 3) },
      { label: 'RFSS', render: (row) => hexDecimal(row.rfss, 2) },
      { label: 'Site', render: (row) => hexDecimal(row.site, 2) },
      { label: 'LCN', key: 'channel_descriptor' },
      { label: 'Control MHz', render: (row) => frequency(row.downlink_hz), className: 'numeric' },
      { label: 'Advertised Status', render: (row) => neighborStatus(row.status) },
      { label: 'Observations', key: 'observation_count', className: 'numeric' },
      { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
    ])));
  } else if (tab === 'band-plan') {
    const data = await api('/api/site/bands', { guid });
    content.append(section('Band Plan', table(data.rows || [], [
      { label: 'Band', key: 'band', className: 'numeric' },
      { label: 'Base MHz', render: (row) => frequency(row.base_hz), className: 'numeric' },
      { label: 'Spacing kHz', render: (row) => row.spacing_hz ? (row.spacing_hz / 1000).toFixed(3) : '', className: 'numeric' },
      { label: 'Bandwidth Hz', key: 'bandwidth', className: 'numeric' },
      { label: 'Offset MHz', render: (row) => row.transmit_offset_hz ? (row.transmit_offset_hz / 1000000).toFixed(5) : '', className: 'numeric' },
      { label: 'TDMA', render: (row) => yesNo(row.tdma) },
      { label: 'Slots', key: 'timeslots', className: 'numeric' },
      { label: 'State', render: (row) => stateBadge(row.state) },
      { label: 'Observations', key: 'observation_count', className: 'numeric' },
      { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
    ])));
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
      { label: 'Patch', render: (row) => talkgroupLink(site, row.patch_group,
        row.patch_alias_name ? `${row.patch_group} · ${row.patch_alias_name}` : row.patch_group), className: 'numeric' },
      { label: 'Talkgroups', render: (row) => memberLinks(talkgroups.get(row.patch_group), (member) =>
        talkgroupLink(site, member.talkgroup_id, member.alias_name ? `${member.talkgroup_id} · ${member.alias_name}` : member.talkgroup_id)) },
      { label: 'Radios', render: (row) => memberLinks(radios.get(row.patch_group), (member) =>
        radioLink(site, member.radio_id, member.alias_name ? `${member.radio_id} · ${member.alias_name}` : member.radio_id)) },
      { label: 'State', render: (row) => stateBadge(row.state) },
      { label: 'Observations', key: 'observation_count', className: 'numeric' },
      { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
    ];
    if (groups.some((row) => Number(row.version))) columns.splice(1, 0,
      { label: 'Version', key: 'version', className: 'numeric' });
    content.append(section('Patches', table(groups, columns)));
  } else if (tab === 'activity') {
    await renderActivity({ guid });
  } else {
    content.append(section('Site Info', keyValues([
      ['System', systemLink(site)], ['GUID', site.guid], ['Name', site.channel_name],
      ['Alias List', site.alias_list_name], ['Protocol', site.protocol], ['Decoder', site.decoder],
      ['NAC', hexDecimal(site.nac, 3)],
      ['RFSS', hexDecimal(site.rfss, 2)], ['Site', hexDecimal(site.site, 2)],
      ['Control Frequency', frequency(site.current_control_hz)],
      ['First Seen', dateTime(site.first_seen_ms)], ['Last Seen', dateTime(site.last_seen_ms)],
      ['Channels', number(site.channels)], ['Neighbors', number(site.neighbors)],
      ['Band Plans', number(site.bands)], ['Patches', number(site.patches)]
    ])));
  }
}

function activityColumns() {
  return [
    { label: 'Time', render: (row) => dateTime(row.observed_at_ms) },
    { label: 'Action', key: 'action' },
    { label: 'Event', key: 'event_type' },
    { label: 'Source', render: (row) => radioLink(row, row.source_radio_id), className: 'numeric' },
    { label: 'Source Alias', render: (row) => row.source_alias_name ? radioLink(row, row.source_radio_id, row.source_alias_name) : '', className: 'alias-cell' },
    { label: 'Target', render: (row) => TALKGROUP_TARGET_KINDS.has(Number(row.target_kind_code)) ? talkgroupLink(row, row.target_id) : row.target_id, className: 'numeric' },
    { label: 'Target Alias', render: (row) => row.target_alias_name || '', className: 'alias-cell' },
    { label: 'MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric' },
    { label: 'LCN', key: 'lcn' },
    { label: 'Slot', key: 'timeslot', className: 'numeric' },
    { label: 'Encryption', render: (row) => row.encrypted ? `${hexDecimal(row.encryption_algorithm_id, 2)}:${hexDecimal(row.encryption_key_id)}` : '', className: 'encrypted' }
  ];
}

async function renderActivity(scopeParameters) {
  const data = await api('/api/activity', {
    ...scopeParameters,
    before_id: route.get('before_id'),
    limit: 200
  });
  const columns = activityColumns();
  const activityTable = table(data.rows || [], columns);
  const body = activityTable.querySelector('tbody');
  (data.rows || []).forEach((row, index) => {
    if (body.children[index] && row.id !== null && row.id !== undefined) body.children[index].dataset.id = String(row.id);
  });
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
      if (row.id !== null && row.id !== undefined && body.querySelector(`[data-id="${row.id}"]`)) return;
      const rendered = table([row], columns).querySelector('tbody tr');
      if (row.id !== null && row.id !== undefined) rendered.dataset.id = String(row.id);
      if (body.querySelector('.empty')) body.replaceChildren(rendered);
      else body.prepend(rendered);
      while (body.children.length > 200) body.lastElementChild.remove();
    });
  }
}

async function renderConventional() {
  const page = await api('/api/conventional', pageParameters());
  content.append(pageHeader('Conventional', 'Started conventional analog and P25 channel history'));
  const columns = [
    { label: 'Name', render: (row) => anchor(row.channel_name || row.context_key,
      href('conventional-detail', { context: row.context_key, tab: 'info' })), className: 'alias-cell', sort: 'name' },
    { label: 'Protocol', render: (row) => protocol(row.protocol_code) },
    { label: 'Decoder', key: 'decoder' },
    { label: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency' },
    { label: 'Slot', key: 'timeslot', className: 'numeric' },
    { label: 'NAC', render: (row) => hexDecimal(row.nac, 3) },
    { label: 'Hits', render: (row) => number(row.hits), className: 'numeric', sort: 'hits' },
    { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen' }
  ];
  content.append(pagedSection('Conventional Channels', page, columns, 'Search name or frequency'));
}

async function renderConventionalDetail() {
  const contextKey = route.get('context');
  if (!contextKey) throw new Error('Conventional context is missing from the URL');
  const data = await api('/api/conventional/detail', { context: contextKey });
  const context = data.context;
  const tab = route.get('tab') || 'info';
  content.append(pageHeader(context.channel_name || context.context_key, protocol(context.protocol_code)), tabs([
    { id: 'info', label: 'Info', href: href('conventional-detail', { context: contextKey, tab: 'info' }) },
    { id: 'activity', label: 'Activity', href: href('conventional-detail', { context: contextKey, tab: 'activity' }) }
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
      { label: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric' },
      { label: 'Slot', key: 'timeslot', className: 'numeric' },
      { label: 'Hits', render: (row) => number(row.hits), className: 'numeric' },
      { label: 'First Seen', render: (row) => dateTime(row.first_seen_ms) },
      { label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
    ])));
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

async function loadStatus() {
  try {
    const status = await api('/api/status');
    const database = status.database || {};
    const size = (Number(database.databaseBytes || 0) / 1048576).toFixed(1);
    document.getElementById('server-status').textContent =
      `${database.statsLoggingEnabled ? 'Logging' : 'Read only'} · ${size} MB`;
  } catch (error) {
    document.getElementById('server-status').textContent = 'Database unavailable';
  }
}

async function render() {
  const view = route.get('view') || 'dashboard';
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
  } catch (error) {
    content.replaceChildren(node('div', 'error', error.message));
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
loadStatus();
render();
