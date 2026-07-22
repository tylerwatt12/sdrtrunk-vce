let route = new URLSearchParams(window.location.search);
const content = document.getElementById('content');
const tableOnly = route.get('layout') === 'table';
const TABLE_WIDTH_COOKIE = 'sdrtrunk_table_widths_v4';
const TABLE_WIDTH_MINIMUM = 48;
const TABLE_WIDTH_MAXIMUM = 1200;
const SIGNAL_OFFLINE_MILLISECONDS = 45_000;
const DECODE_HEALTHY_MINIMUM_PERCENT = 90;
const DECODE_DEGRADED_MINIMUM_PERCENT = 75;
const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
const SIGNAL_RANGES = Object.freeze([
  ['1h', '1 hour'], ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days']
]);
const ACTIVITY_RANGES = Object.freeze([
  ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days']
]);
const CALL_ACTIVITY_SERIES = Object.freeze([
  { field: 'call_count', label: 'Tracked Calls', color: '#0b7168', visible: true },
  { field: 'recorded_count', label: 'Recorded', color: '#2f6da5', visible: true },
  { field: 'streamed_count', label: 'Sent to Streamer', color: '#cc7a00', visible: true }
]);
const DASHBOARD_CALL_ACTIVITY_SERIES = Object.freeze([
  { field: 'call_count', label: 'P25 Calls', color: '#0b7168', visible: true },
  { field: 'non_p25_call_count', label: 'Non-P25 Calls', color: '#6b4fa3', visible: true },
  { field: 'recorded_count', label: 'Recorded', color: '#2f6da5', visible: true },
  { field: 'streamed_count', label: 'Sent to Streamer', color: '#cc7a00', visible: true }
]);
const TALKGROUP_ACTIVITY_SERIES = Object.freeze([
  ...CALL_ACTIVITY_SERIES,
  { field: 'encrypted_count', label: 'Encrypted', color: '#9d174d' },
  { field: 'emergency_count', label: 'Emergency', color: '#b42318' },
  { field: 'data_count', label: 'Data', color: '#6b4fa3' },
  { field: 'join_count', label: 'Join', color: '#3c7a3c' },
  { field: 'register_count', label: 'Register', color: '#0085a1' },
  { field: 'denial_count', label: 'Denial', color: '#b33b5e' },
  { field: 'busy_count', label: 'Busy', color: '#a65a3a' },
  { field: 'queued_count', label: 'Queued', color: '#7c6b2f' },
  { field: 'continue_count', label: 'Continue', color: '#526778' },
  { field: 'active_count', label: 'Active', color: '#4d7f7b' },
  { field: 'acknowledge_count', label: 'Acknowledge', color: '#865d9c' },
  { field: 'check_count', label: 'Check', color: '#8b5d2e' },
  { field: 'check_ack_count', label: 'Check Ack', color: '#556b2f' },
  { field: 'gps_count', label: 'GPS', color: '#00758a' },
  { field: 'logout_count', label: 'Logout', color: '#806000' },
  { field: 'page_count', label: 'Page', color: '#754668' },
  { field: 'patch_count', label: 'Patch', color: '#476a30' },
  { field: 'patch_cancel_count', label: 'Patch Cancel', color: '#9a5b13' },
  { field: 'patch_create_count', label: 'Patch Create', color: '#5b5f97' },
  { field: 'request_count', label: 'Request', color: '#2f728f' },
  { field: 'status_count', label: 'Status', color: '#6b6257' },
  { field: 'unknown_count', label: 'Unknown', color: '#737c86' }
]);
const CALL_METRIC_GUIDE = Object.freeze([
  ['Tracked Calls', 'Traffic calls accepted by the channel manager. A tracked call can have no usable audio.'],
  ['Recorded', 'Completed calls written to a nonempty recording file. Recording rules and duplicate suppression apply.'],
  ['Sent to Streamer', 'Completed calls encoded into a nonempty temporary file and handed to at least one configured stream. This does not mean the remote service accepted the upload.'],
  ['Encrypted', 'Tracked activity for which encrypted audio was confirmed.']
]);
const ACTION_METRIC_GUIDE = Object.freeze([
  ['Active', 'A voice-channel grant event observed outside the dedicated call-start assignment. It is an event count, not the number of calls currently active.'],
  ['Continue', 'A repeated or continuing channel-grant observation for an already assigned call. It is not a new call.'],
  ['Emergency', 'An emergency service event or a tracked call carrying the emergency flag.'],
  ['Data', 'A data-call event.'],
  ['Join', 'A radio affiliation or group-join event.'],
  ['Register', 'A unit-registration event.'],
  ['Denial', 'A denied service request.'],
  ['Busy', 'A busy response, including a target group already active response.'],
  ['Queued', 'A service request placed in a queue.'],
  ['Acknowledge', 'An acknowledgement response.'],
  ['Check', 'A radio-check or query event.'],
  ['Check Ack', 'An acknowledgement of a radio check.'],
  ['GPS', 'A location or GPS event.'],
  ['Logout', 'A unit de-registration or logout event.'],
  ['Page', 'A page event.'],
  ['Patch', 'A dynamic regroup or patch event whose operation was not more specific.'],
  ['Patch Create', 'A dynamic regroup or patch activation.'],
  ['Patch Cancel', 'A dynamic regroup or patch deactivation.'],
  ['Request', 'A service request event.'],
  ['Status', 'A unit or user status event.'],
  ['Unknown', 'An observed event that could not be mapped to a more specific action.']
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
  'count': 94,
  'decoder': 78,
  'encrypted': 52,
  'encryption': 92,
  'event': 115,
  'first-seen': 166,
  'frequency': 94,
  'group': 135,
  'last-active': 166,
  'last-seen': 166,
  'lcn': 68,
  'name': 175,
  'radio': 82,
  'radio-alias': 165,
  'recorded': 78,
  'rfss': 66,
  'signal': 82,
  'decode-health': 90,
  'site': 66,
  'source': 82,
  'source-alias': 165,
  'state': 82,
  'status': 116,
  'streamed': 82,
  'system': 106,
  'talker-alias': 160,
  'talkgroup-id': 90,
  'talkgroup-name': 175,
  'target': 82,
  'target-alias': 165,
  'time': 166,
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

function svgNode(tag, attributes = {}, textValue) {
  const element = document.createElementNS(SVG_NAMESPACE, tag);
  Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, String(value)));
  if (textValue !== undefined) element.textContent = String(textValue);
  return element;
}

function installTimeChartHover(wrapper, svg, options) {
  const { width, height, margin, from, to, points, timestamp, markers, tooltipText } = options;
  if (!points.length) return;

  wrapper.querySelector(':scope > .chart-tooltip')?.remove();
  const tooltip = node('div', 'chart-tooltip');
  tooltip.hidden = true;
  tooltip.setAttribute('role', 'tooltip');
  const guide = svgNode('line', {
    y1: margin.top,
    y2: height - margin.bottom,
    class: 'chart-hover-guide',
    visibility: 'hidden'
  });
  const markerGroup = svgNode('g', { class: 'chart-hover-markers', visibility: 'hidden' });
  const plotWidth = width - margin.left - margin.right;
  const range = Math.max(1, to - from);

  const hide = () => {
    tooltip.hidden = true;
    guide.setAttribute('visibility', 'hidden');
    markerGroup.setAttribute('visibility', 'hidden');
  };

  const show = (event) => {
    const bounds = svg.getBoundingClientRect();
    const scale = Math.min(bounds.width / width, bounds.height / height);
    if (!Number.isFinite(scale) || scale <= 0) return;
    const renderedWidth = width * scale;
    const renderedHeight = height * scale;
    const renderedLeft = bounds.left + (bounds.width - renderedWidth) / 2;
    const renderedTop = bounds.top + (bounds.height - renderedHeight) / 2;
    const chartX = (event.clientX - renderedLeft) / scale;
    const hoveredTimestamp = from + Math.max(0, Math.min(1,
      (chartX - margin.left) / plotWidth)) * range;
    const point = points.reduce((nearest, candidate) =>
      Math.abs(timestamp(candidate) - hoveredTimestamp) < Math.abs(timestamp(nearest) - hoveredTimestamp) ?
        candidate : nearest);
    const visibleMarkers = markers(point).filter((marker) =>
      Number.isFinite(marker.x) && Number.isFinite(marker.y));
    if (!visibleMarkers.length) return;

    const pointX = visibleMarkers[0].x;
    const pointY = Math.min(...visibleMarkers.map((marker) => marker.y));
    guide.setAttribute('x1', pointX);
    guide.setAttribute('x2', pointX);
    guide.removeAttribute('visibility');
    markerGroup.replaceChildren(...visibleMarkers.map((marker) => {
      const circle = svgNode('circle', { cx: marker.x, cy: marker.y, r: marker.radius || 4,
        class: 'chart-hover-point' });
      if (marker.color) circle.style.stroke = marker.color;
      return circle;
    }));
    markerGroup.removeAttribute('visibility');

    const text = tooltipText(point);
    tooltip.textContent = Array.isArray(text) ? text.join('\n') : String(text);
    tooltip.hidden = false;
    const wrapperBounds = wrapper.getBoundingClientRect();
    const leftAtPoint = renderedLeft + pointX * scale - wrapperBounds.left + wrapper.scrollLeft;
    const topAtPoint = renderedTop + pointY * scale - wrapperBounds.top + wrapper.scrollTop;
    const gap = 10;
    let left = leftAtPoint + gap;
    if (left + tooltip.offsetWidth > wrapper.clientWidth - 4) {
      left = leftAtPoint - tooltip.offsetWidth - gap;
    }
    let top = topAtPoint - tooltip.offsetHeight - gap;
    if (top < 4) top = topAtPoint + gap;
    tooltip.style.left = `${Math.max(4, left)}px`;
    tooltip.style.top = `${Math.max(4, top)}px`;
  };

  const surface = svgNode('rect', {
    x: margin.left,
    y: margin.top,
    width: plotWidth,
    height: height - margin.top - margin.bottom,
    class: 'chart-hover-surface'
  });
  surface.addEventListener('pointermove', show);
  surface.addEventListener('pointerleave', hide);
  svg.append(guide, markerGroup, surface);
  wrapper.append(tooltip);
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

function labeledBaseValue(value, label) {
  const result = node('span', 'number-base-value');
  result.append(String(value), node('small', 'number-base-label', label));
  return result;
}

function hexDecimalPair(value, width = 0) {
  if (value === null || value === undefined || value === '') return '';
  const result = node('span', 'number-base-pair');
  result.append(labeledBaseValue(hex(value, width), 'HEX'),
    node('span', 'number-base-separator', '·'),
    labeledBaseValue(Number(value), 'DEC'));
  return result;
}

const P25_ENCRYPTION_ALGORITHM_NAMES = Object.freeze({
  0x00: 'ACRD3', 0x01: 'BAT-E', 0x02: 'FIREF', 0x03: 'MAYFL', 0x04: 'SAVIL', 0x05: 'PADSTN',
  0x41: 'BAT-O', 0x81: 'DESOFB', 0x82: '3DES2', 0x83: '3DES3', 0x84: 'AES256', 0x85: 'AES128',
  0x88: 'AESCBC', 0x89: 'A128OF', 0x9F: 'DESXL', 0xA0: 'DVIXL', 0xA1: 'DVPXL', 0xA2: 'DVPSPF',
  0xA3: 'HAYSTK', 0xA4: 'MOT-A4', 0xA5: 'MOT-A5', 0xA6: 'MOT-A6', 0xA7: 'MOT-A7',
  0xA8: 'MOT-A8', 0xA9: 'MOT-A9', 0xAA: 'ADP', 0xAB: 'CFX256', 0xAC: 'MOT-AC',
  0xAD: 'MOT-AD', 0xAE: 'MOT-AE', 0xAF: 'A256GM', 0xB0: 'DVPB0'
});

function encryptionAlgorithm(value) {
  if (value === null || value === undefined || value === '') return '';
  const algorithm = Number(value);
  return P25_ENCRYPTION_ALGORITHM_NAMES[algorithm] || `0x${hex(algorithm, 2)}`;
}

function encryptionAlgorithmInfoValue(value) {
  const label = encryptionAlgorithm(value);
  return label.startsWith('0x') ? hexDecimalPair(value, 2) : label;
}

function encryptionDetails(algorithm, key) {
  const algorithmValue = encryptionAlgorithm(algorithm);
  const keyValue = hex(key);
  return `${algorithmValue}${keyValue ? `:${keyValue}` : ''}`;
}

function frequency(value) {
  return value ? (Number(value) / 1000000).toFixed(5) : '';
}

function exactDateTime(value) {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '';
  const date = new Date(timestamp);
  const twoDigits = (part) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${twoDigits(date.getMonth() + 1)}-${twoDigits(date.getDate())} ` +
    `${twoDigits(date.getHours())}:${twoDigits(date.getMinutes())}:${twoDigits(date.getSeconds())}`;
}

function dateTime(value) {
  const exact = exactDateTime(value);
  if (!exact) return '';
  const timestamp = Number(value);
  const time = node('time', 'exact-time', exact);
  time.dateTime = new Date(timestamp).toISOString();
  time.title = new Intl.DateTimeFormat([], {
    dateStyle: 'full', timeStyle: 'long'
  }).format(new Date(timestamp));
  return time;
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

function systemValue(row) {
  return systemLabel(row);
}

function systemInfoValue(row) {
  const hexadecimal = systemLabel(row);
  const wacn = row.wacn === null || row.wacn === undefined || row.wacn === '' ? '' : Number(row.wacn);
  const system = row.system_id === null || row.system_id === undefined || row.system_id === '' ? '' :
    Number(row.system_id);
  if (!hexadecimal || wacn === '' || system === '') return hexadecimal;
  const result = node('span', 'number-base-pair');
  result.append(labeledBaseValue(hexadecimal, 'HEX'),
    node('span', 'number-base-separator', '·'),
    labeledBaseValue(`${wacn}-${system}`, 'DEC'));
  return result;
}

function siteLabel(row) {
  const identity = `${hex(row.rfss, 2)}-${hex(row.site, 2)}`;
  return row.channel_name || `${systemLabel(row)} ${identity}`;
}

function siteValue(row) {
  return siteLabel(row);
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
  if (status.includes('ISSI ADVERTISED')) labels.push(['ISSI Advertised', 'state-current']);
  if (!labels.length && status) labels.push([value, '']);
  return fragment(...labels.map(([label, className]) => badge(label, className)));
}

function neighborModes(row) {
  const modes = [];
  if (Number(row.has_fdma)) modes.push('FDMA');
  if (Number(row.has_tdma)) modes.push('TDMA');
  if (Number(row.has_unknown)) modes.push('Unknown');
  return modes.join(', ');
}

function foreignBandDetails(value) {
  const types = [
    { mode: 'FDMA', bandwidth: 12500, slots: 1, voiceRate: 'Half-rate' },
    { mode: 'FDMA', bandwidth: 12500, slots: 1, voiceRate: 'Full-rate' },
    { mode: 'FDMA', bandwidth: 6250, slots: 1, voiceRate: 'Half-rate' },
    { mode: 'TDMA', bandwidth: 12500, slots: 2, voiceRate: 'Half-rate' },
    { mode: 'TDMA', bandwidth: 25000, slots: 4, voiceRate: 'Half-rate' },
    { mode: 'TDMA H-D8PSK', bandwidth: 12500, slots: 2, voiceRate: 'Half-rate' }
  ];
  return types[Number(value)] || { mode: 'Unknown', bandwidth: '', slots: '', voiceRate: 'Unknown' };
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
  const element = node('a', className);
  element.append(valueNode(label));
  element.href = target;
  return element;
}

function externalAnchor(label, target) {
  const element = anchor(label, target);
  element.target = '_blank';
  element.rel = 'noopener noreferrer';
  return element;
}

function systemLink(row, label = systemValue(row)) {
  return anchor(label, href('system', { ...scope(row), tab: 'info' }));
}

function siteLink(row, label = siteValue(row)) {
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
  const heading = node('h1', 'page-title');
  heading.append(valueNode(title));
  labels.append(heading);
  if (subtitle) {
    const detail = node('div', 'page-subtitle');
    detail.append(valueNode(subtitle));
    labels.append(detail);
  }
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
      ` Last successful summary write: ${exactDateTime(logging.lastSuccessfulWriteMs)}.` : '';
    const activityState = logging.historyRetained ?
      ` Activity pages show retained history${logging.lastHistoryMs ?
        ` through ${exactDateTime(logging.lastHistoryMs)}` : ''} and are not updating.` :
      ' Activity pages require detailed history and are unavailable.';
    return node('div', 'logging-notice warning',
      `${message}${detail}${lastWrite}${activityState} ` +
      'Live Systems and audio playback do not require logging and remain available.');
  }
  if (!logging.historyActive) {
    const reason = logging.historyConfigured ? 'not currently running' : 'off';
    if (logging.historyRetained) {
      const through = logging.lastHistoryMs ? ` through ${exactDateTime(logging.lastHistoryMs)}` : '';
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

function renderTableRow(data, columns, rowKey, rowClass) {
  const row = node('tr');
  const classes = typeof rowClass === 'function' ? rowClass(data) : rowClass;
  if (classes) row.classList.add(...String(classes).split(/\s+/).filter(Boolean));
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

function setTableColumnWidths(element, columnElements, widths) {
  const total = widths.reduce((sum, width) => sum + width, 0) || 1;
  widths.forEach((width, index) => {
    columnElements[index].style.width = `${Math.round(width)}px`;
  });
  element.style.width = '100%';
  element.style.minWidth = `${Math.round(total)}px`;
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
  setTableColumnWidths(element, columnElements, widths);
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
    setTableColumnWidths(element, columnElements, widths);
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
    orderedRows.forEach((data) => body.append(renderTableRow(data, columns, options.rowKey, options.rowClass)));
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
    const fullLabel = column.fullLabel || column.label;
    if (fullLabel) header.title = fullLabel;
    const serverSortable = options.serverSort && column.sort;
    if (serverSortable) {
      const currentSort = route.get('sort') || options.defaultSort;
      const currentDirection = route.get('direction') || options.defaultDirection || 'desc';
      const direction = currentSort === column.sort && currentDirection === 'desc' ? 'asc' : 'desc';
      header.append(anchor(column.label, currentHref({ sort: column.sort, direction, offset: null }),
        'table-sort-control'));
    } else if (options.sortable !== false) {
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
    } else {
      header.append(node('span', 'table-column-label', column.label));
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
      const rendered = renderTableRow(data, columns, options.rowKey, options.rowClass);
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

function metrics(values, embedded = false) {
  const band = node(embedded ? 'div' : 'section', 'summary-band');
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

const NON_ACTION_COUNT_FIELDS = new Set(['grant_count', 'recorded_count', 'streamed_count']);

function actionCounts(row) {
  return Object.entries(row)
    .filter(([key, value]) => key.endsWith('_count') && !NON_ACTION_COUNT_FIELDS.has(key) && Number(value) > 0)
    .map(([key, value]) => [key.replace(/_count$/, '').replaceAll('_', ' '), Number(value)]);
}

function withoutGrantActions(rows) {
  return (rows || []).filter((row) => String(row.action || '').toUpperCase() !== 'GRANT');
}

function siteActivityColor(index) {
  return `hsl(${Math.round(index * 137.508) % 360} 58% 42%)`;
}

function siteActivityPie(activity) {
  const rows = (activity?.rows || []).filter((row) => Number(row.call_count) > 0);
  if (!rows.length) return node('div', 'empty', 'No P25 site calls recorded in the last 24 hours');

  const total = Number(rows[0].total_call_count) ||
    rows.reduce((sum, row) => sum + Number(row.call_count), 0);
  let offset = 0;
  const segments = rows.map((row, index) => {
    const calls = Number(row.call_count);
    const share = total ? calls / total * 100 : 0;
    const segment = { row, calls, share, start: offset, end: offset + share,
      color: siteActivityColor(index) };
    offset += share;
    return segment;
  });
  const chart = node('div', 'site-activity-chart');
  const graphic = node('div', 'site-activity-pie');
  graphic.style.background = `conic-gradient(${segments.map((segment) =>
    `${segment.color} ${segment.start.toFixed(4)}% ${segment.end.toFixed(4)}%`).join(', ')})`;
  graphic.setAttribute('role', 'img');
  graphic.setAttribute('aria-label', segments.map((segment) =>
    `${siteLabel(segment.row)} ${number(segment.calls)} calls ${segment.share.toFixed(1)} percent`).join('; '));
  const legend = node('div', 'site-activity-legend');
  legend.setAttribute('role', 'list');
  segments.forEach((segment) => {
    const item = node('div', 'site-activity-legend-row');
    item.setAttribute('role', 'listitem');
    const swatch = node('span', 'site-activity-swatch');
    swatch.style.backgroundColor = segment.color;
    const identity = node('span', 'site-activity-identity');
    identity.append(siteLink(segment.row));
    const values = node('span', 'site-activity-values');
    values.append(node('strong', '', number(segment.calls)), node('span', '', `${segment.share.toFixed(1)}%`));
    item.append(swatch, identity, values);
    legend.append(item);
  });
  chart.append(graphic, legend);
  return chart;
}

function activityMetricGuide(includeCallMetrics = false) {
  const details = node('details', 'metric-guide');
  details.append(node('summary', '', 'What these activity metrics mean'));
  const list = node('dl', 'metric-guide-list');
  const entries = includeCallMetrics ? [...CALL_METRIC_GUIDE, ...ACTION_METRIC_GUIDE] : ACTION_METRIC_GUIDE;
  entries.forEach(([label, description]) => {
    list.append(node('dt', '', label), node('dd', '', description));
  });
  details.append(list);
  return details;
}

function roundedChartMaximum(maximum) {
  const roughStep = Math.max(1, maximum) / 4;
  const magnitude = Math.pow(10, Math.floor(Math.log10(roughStep)));
  const normalized = roughStep / magnitude;
  const step = (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude;
  return Math.max(4, step * 4);
}

function countTimeSeriesChart(rows, configurations, options = {}) {
  const values = (rows || []).map((row) => ({ ...row,
    time_ms: Number(row[options.timeField || 'time_ms']) }));
  if (!values.length) return node('div', 'empty', options.emptyMessage || 'No hourly activity data');

  const series = (configurations || []).filter((configuration) => configuration && configuration.field);
  if (!series.length) return node('div', 'empty', 'No activity series selected');

  const width = Number(options.width || 960);
  const height = Number(options.height || 270);
  const margin = options.margin || { top: 18, right: 20, bottom: 42, left: 55 };
  const plotWidth = width - margin.left - margin.right;
  const plotHeight = height - margin.top - margin.bottom;
  const maximum = Math.max(1, ...series.flatMap((configuration) =>
    values.map((value) => Number(value[configuration.field] || 0))));
  const roundedMaximum = roundedChartMaximum(maximum);
  const from = Number(options.from ?? values[0].time_ms);
  const to = Math.max(from + 1, Number(options.to ?? values.at(-1).time_ms));
  const xFor = (timestamp) => margin.left + plotWidth * Math.max(0, Math.min(1,
    (timestamp - from) / (to - from)));
  const yFor = (value) => margin.top + plotHeight - plotHeight * Number(value || 0) / roundedMaximum;
  const svg = svgNode('svg', {
    class: 'activity-line-svg',
    viewBox: `0 0 ${width} ${height}`,
    role: 'img',
    'aria-label': options.ariaLabel || 'Call activity by time'
  });
  svg.style.height = `${height}px`;

  for (let index = 0; index <= 4; index += 1) {
    const value = roundedMaximum * index / 4;
    const y = yFor(value);
    svg.append(svgNode('line', { x1: margin.left, y1: y, x2: width - margin.right, y2: y,
      class: 'chart-grid-line' }));
    svg.append(svgNode('text', { x: margin.left - 10, y: y + 4, class: 'chart-axis-label',
      'text-anchor': 'end' }, number(value)));
  }

  series.forEach((configuration) => {
    const points = values.map((value) => ({ timestamp: value.time_ms,
      count: Number(value[configuration.field] || 0) }));
    const path = points.map((point, index) => `${index ? 'L' : 'M'} ${xFor(point.timestamp).toFixed(2)} ` +
      `${yFor(point.count).toFixed(2)}`).join(' ');
    const line = svgNode('path', { d: path, class: 'activity-line-path' });
    line.style.stroke = configuration.color;
    svg.append(line);
    if (values.length <= 96) {
      points.forEach((point) => {
        const circle = svgNode('circle', { cx: xFor(point.timestamp), cy: yFor(point.count),
          r: values.length <= 48 ? 3 : 1.8, class: 'activity-line-point' });
        circle.style.stroke = configuration.color;
        svg.append(circle);
      });
    }
  });

  const tickStep = Math.max(1, Math.ceil(values.length / 6));
  values.forEach((value, index) => {
    if (index % tickStep !== 0 && index !== values.length - 1) return;
    const longRange = to - from > 2 * 86_400_000;
    const label = longRange ? new Date(value.time_ms).toLocaleString([], {
      month: 'short', day: 'numeric', hour: 'numeric'
    }) : new Date(value.time_ms).toLocaleTimeString([], { hour: 'numeric' });
    svg.append(svgNode('text', { x: xFor(value.time_ms), y: height - 15,
      class: 'chart-axis-label', 'text-anchor': 'middle' }, label));
  });

  const wrapper = node('div', 'activity-line-chart');
  wrapper.style.minHeight = `${height}px`;
  wrapper.append(svg);
  installTimeChartHover(wrapper, svg, {
    width, height, margin, from, to, points: values,
    timestamp: (point) => point.time_ms,
    markers: (point) => series.map((configuration) => ({
      x: xFor(point.time_ms),
      y: yFor(point[configuration.field]),
      color: configuration.color
    })),
    tooltipText: (point) => [new Date(point.time_ms).toLocaleString(),
      ...series.map((configuration) =>
        `${configuration.label}: ${number(point[configuration.field] || 0)}`)]
  });
  return wrapper;
}

function outputMetricStartNote(response) {
  const metricStart = Number(response?.metric_start_ms || 0);
  if (metricStart > Number(response?.from_ms || 0) &&
      metricStart <= Number(response?.to_ms || Date.now())) {
    const note = node('div', 'activity-metric-note');
    note.append('Recorded and Sent to Streamer counters begin ', dateTime(metricStart), '.');
    return note;
  }
  return null;
}

function talkgroupActivityChart(response) {
  const values = (response.series || []).map((row) => ({ ...row, time_ms: Number(row.time_ms) }));
  if (!values.length) return node('div', 'empty', 'No activity data is available for this range');

  const totals = response.totals || {};
  const configurations = TALKGROUP_ACTIVITY_SERIES.filter((series) => series.visible ||
    Number(totals[series.field] || 0) > 0);
  const selected = new Set(configurations.filter((series) => series.visible).map((series) => series.field));
  if (!selected.size && configurations.length) selected.add(configurations[0].field);

  const wrapper = node('div', 'talkgroup-activity-chart');
  const legend = node('div', 'activity-series-legend');
  const chartHost = node('div', 'talkgroup-activity-chart-host');
  wrapper.append(legend, chartHost);

  const draw = () => {
    chartHost.replaceChildren();
    const visible = configurations.filter((series) => selected.has(series.field));
    if (!visible.length) {
      chartHost.append(node('div', 'empty', 'Select at least one activity type'));
      return;
    }

    chartHost.append(countTimeSeriesChart(values, visible, {
      from: Number(response.from_ms || values[0].time_ms),
      to: Number(response.to_ms || values.at(-1).time_ms),
      height: 300,
      margin: { top: 18, right: 20, bottom: 48, left: 55 },
      ariaLabel: 'Talkgroup activity by time and activity type',
      emptyMessage: 'No activity data is available for this range'
    }));
  };

  configurations.forEach((series) => {
    const button = node('button', 'activity-series-button secondary');
    button.type = 'button';
    const swatch = node('span', 'activity-series-swatch');
    swatch.style.backgroundColor = series.color;
    button.append(swatch, node('span', '', series.label),
      node('span', 'activity-series-total', number(totals[series.field] || 0)));
    const update = () => {
      const active = selected.has(series.field);
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    };
    button.addEventListener('click', () => {
      if (selected.has(series.field)) selected.delete(series.field);
      else selected.add(series.field);
      update();
      draw();
    });
    update();
    legend.append(button);
  });

  draw();
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
  if (decode >= DECODE_HEALTHY_MINIMUM_PERCENT) {
    return { label: 'Healthy', className: 'healthy', rank: 4 };
  }
  if (decode >= DECODE_DEGRADED_MINIMUM_PERCENT) {
    return { label: 'Degraded', className: 'degraded', rank: 3 };
  }
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
  const svg = svgNode('svg');
  svg.setAttribute('class', `quality-chart-svg ${signal ? 'signal-chart-svg' : 'decode-chart-svg'}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', `${siteLabel(site)} ${signal ? 'signal strength' : 'decode quality'} history`);
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
      [[0, DECODE_DEGRADED_MINIMUM_PERCENT, 'poor'],
        [DECODE_DEGRADED_MINIMUM_PERCENT, DECODE_HEALTHY_MINIMUM_PERCENT, 'degraded'],
        [DECODE_HEALTHY_MINIMUM_PERCENT, 100, 'healthy']].forEach(([minimum, maximum, state]) => {
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
      [DECODE_DEGRADED_MINIMUM_PERCENT, DECODE_HEALTHY_MINIMUM_PERCENT].forEach((value) =>
        svg.append(svgNode('line', { x1: margin.left, y1: yFor(value),
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

    [from, from + range / 2, to].forEach((timestamp, index) => {
      const longRange = range > 86_400_000;
      const label = new Date(timestamp).toLocaleString([], longRange ?
        { month: 'short', day: 'numeric', hour: 'numeric' } : { hour: 'numeric', minute: '2-digit' });
      svg.append(svgNode('text', { x: xFor(timestamp), y: height - 9, class: 'quality-axis-label',
        'text-anchor': index === 0 ? 'start' : (index === 2 ? 'end' : 'middle') }, label));
    });

    const hoverPoints = points.filter((point) => Number.isFinite(point.value));
    installTimeChartHover(wrapper, svg, {
      width, height, margin, from, to, points: hoverPoints,
      timestamp: (point) => point.timestamp,
      markers: (point) => [{
        x: xFor(point.timestamp),
        y: yFor(point.value),
        color: signal ? '#0b7168' : '#3d64b1'
      }],
      tooltipText: (point) => {
        const frequencyText = Number(point.frequency_hz) ? `${frequency(point.frequency_hz)} MHz` :
          (Number(point.frequency_count) > 1 ? `${number(point.frequency_count)} frequencies` :
            'Frequency unavailable');
        let detail;
        if (signal) {
          const rangeText = Number.isFinite(point.minimum) && Number.isFinite(point.maximum) ?
            `${point.minimum.toFixed(1)} to ${point.maximum.toFixed(1)} dBFS` : 'Unavailable';
          detail = [`30s average: ${point.average.toFixed(1)} dBFS`, `Range: ${rangeText}`,
            `Decode health: ${percentNumber(point.decode)}`];
        } else {
          detail = [`Decode health: ${point.decode.toFixed(1)}%`,
            `30s signal average: ${signalNumber(point.average)}`];
        }
        return [exactDateTime(point.last_observed_ms || point.timestamp), ...detail, frequencyText,
          `${number(point.sample_count)} retained sample${Number(point.sample_count) === 1 ? '' : 's'}`];
      }
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
  labels.append(siteLink(site));
  const system = node('div', 'signal-current-system');
  system.append(systemValue(site));
  labels.append(system);
  header.append(labels, badge(state.label, `signal-state ${state.className}`));
  const power = node('div', 'signal-current-power');
  power.append(node('strong', '', signalNumber(site.signal_dbfs)),
    node('span', '', `30s avg ${signalNumber(site.average_signal_dbfs)}`));
  const details = node('div', 'signal-current-details');
  const qualityFrequency = site.quality_frequency_hz || site.current_control_hz;
  const decode = optionalNumber(site.decode_health_pct);
  const decodeClass = !Number.isFinite(decode) ? '' :
    (decode >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'quality-good' :
      (decode >= DECODE_DEGRADED_MINIMUM_PERCENT ? 'quality-warn' : 'quality-bad'));
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
  return [...sites].sort((left, right) => {
    if (selectedSort === 'name') return siteLabel(left).localeCompare(siteLabel(right));
    if (selectedSort === 'signal') {
      const leftSignal = optionalNumber(left.average_signal_dbfs);
      const rightSignal = optionalNumber(right.average_signal_dbfs);
      return (Number.isFinite(leftSignal) ? leftSignal : -Infinity) -
        (Number.isFinite(rightSignal) ? rightSignal : -Infinity);
    }
    const leftDecode = optionalNumber(left.decode_health_pct);
    const rightDecode = optionalNumber(right.decode_health_pct);
    return (Number.isFinite(rightDecode) ? rightDecode : -1) -
      (Number.isFinite(leftDecode) ? leftDecode : -1);
  });
}

function signalOverview(site, includeName = true) {
  const overview = node('div', 'signal-history-overview');
  overview.classList.toggle('without-identity', !includeName);
  if (includeName) {
    const identity = node('div', 'signal-history-identity');
    const system = node('span');
    system.append(systemValue(site));
    identity.append(siteLink(site), system);
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

function rangeControls(ranges, selectedRange, onChange) {
  const controls = node('div', 'signal-range-controls');
  const buttons = new Map();
  ranges.forEach(([value, label]) => {
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

function signalRangeControls(selectedRange, onChange) {
  return rangeControls(SIGNAL_RANGES, selectedRange, onChange);
}

async function signalHealthSection() {
  const host = node('div', 'signal-health');
  const currentPanel = node('div', 'signal-current-panel');
  const currentToolbar = node('div', 'signal-current-toolbar');
  const summary = node('div', 'signal-health-summary');
  const sortLabel = node('label', 'signal-sort-label', 'Sort');
  const sort = node('select', 'signal-sort');
  [['decode', 'Highest decode'], ['signal', 'Weakest signal'], ['name', 'Name']].forEach(([value, label]) => {
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
    const healthy = sites.filter((site) =>
      optionalNumber(site.decode_health_pct) >= DECODE_HEALTHY_MINIMUM_PERCENT).length;
    const degraded = sites.filter((site) => {
      const decode = optionalNumber(site.decode_health_pct);
      return Number.isFinite(decode) && decode >= DECODE_DEGRADED_MINIMUM_PERCENT &&
        decode < DECODE_HEALTHY_MINIMUM_PERCENT;
    }).length;
    const poor = sites.filter((site) => {
      const decode = optionalNumber(site.decode_health_pct);
      return Number.isFinite(decode) && decode < DECODE_DEGRADED_MINIMUM_PERCENT;
    }).length;
    const unknown = sites.length - healthy - degraded - poor;
    summary.textContent = `${number(sites.length)} reporting · ${number(healthy)} healthy · ` +
      `${number(degraded)} degraded · ${number(poor)} poor${unknown ? ` · ${number(unknown)} unknown` : ''}`;

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

async function talkgroupActivityHistorySection(scopeParameters) {
  const host = node('div', 'talkgroup-activity-history');
  const block = section('Activity History', host);
  let selectedRange = '24h';
  let loadingSequence = 0;
  let loading = false;
  const rangeControl = rangeControls(ACTIVITY_RANGES, selectedRange, async (value, buttons) => {
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
      host.replaceChildren(node('div', 'loading', 'Loading talkgroup activity history'));
    }
    try {
      const response = await api('/api/talkgroup/activity', { ...scopeParameters, range: selectedRange });
      if (sequence !== loadingSequence) return;
      host.replaceChildren(metrics([
        ['Tracked Calls', response.totals?.call_count],
        ['Recorded', response.totals?.recorded_count],
        ['Sent to Streamer', response.totals?.streamed_count]
      ], true), talkgroupActivityChart(response), activityMetricGuide(true));
      const metricNote = outputMetricStartNote(response);
      if (metricNote) host.append(metricNote);
    } catch (error) {
      if (sequence === loadingSequence) host.replaceChildren(node('div', 'error', error.message));
    } finally {
      if (sequence === loadingSequence) {
        loading = false;
        buttons.forEach((button) => { button.disabled = false; });
      }
    }
  };

  const logging = statsLoggingState();
  if (logging.available && !logging.summaryActive) {
    rangeControl.controls.hidden = true;
    host.append(node('div', 'empty', 'Talkgroup activity history requires Stats Logging.'));
  } else {
    await load(rangeControl.buttons, true);
    pageInterval(load, 30_000);
  }
  return block;
}

async function siteTopTalkgroupsSection(site) {
  const host = node('div', 'site-top-talkgroups');
  const block = section('Top Talkgroups', host);
  let selectedRange = '24h';
  let loadingSequence = 0;
  let loading = false;
  const rangeControl = rangeControls(SIGNAL_RANGES, selectedRange, async (value, buttons) => {
    selectedRange = value;
    await load(buttons, true);
  });
  block.querySelector('.section-title').append(rangeControl.controls);
  const columns = [
    { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sortValue: (row) => Number(row.talkgroup_id) },
    { id: 'talkgroup-name', label: 'Name', fullLabel: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row, row.talkgroup_id), className: 'alias-cell', sortValue: aliasLabel },
    { label: 'Group', key: 'alias_group', className: 'alias-cell', sortValue: (row) => row.alias_group || '' },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'recorded', label: 'Rec', fullLabel: 'Recorded', render: (row) => number(row.recorded_count), className: 'numeric', sortValue: (row) => Number(row.recorded_count || 0) },
    { id: 'streamed', label: 'Sent', fullLabel: 'Sent to Streamer', render: (row) => number(row.streamed_count), className: 'numeric', sortValue: (row) => Number(row.streamed_count || 0) },
    { id: 'last-active', label: 'Active', fullLabel: 'Last Active', render: (row) => dateTime(row.last_active_ms), sortValue: (row) => Number(row.last_active_ms || 0) }
  ];

  const load = async (buttons = rangeControl.buttons, interactive = false) => {
    if (loading && !interactive) return;
    const sequence = ++loadingSequence;
    loading = true;
    if (interactive) {
      buttons.forEach((button) => { button.disabled = true; });
      host.replaceChildren(node('div', 'loading', 'Loading top talkgroups'));
    }
    try {
      const response = await api('/api/site/talkgroups', {
        guid: site.guid, range: selectedRange, limit: 20
      });
      if (sequence !== loadingSequence) return;
      host.replaceChildren(table(response.rows || [], columns,
        'No talkgroup activity is available for this range', { type: 'site-top-talkgroups' }));
      host.append(node('div', 'activity-metric-note',
        'Last Active identifies the newest hourly activity bucket for this site and range.'));
      const metricNote = outputMetricStartNote(response);
      if (metricNote) host.append(metricNote);
    } catch (error) {
      if (sequence === loadingSequence) host.replaceChildren(node('div', 'error', error.message));
    } finally {
      if (sequence === loadingSequence) {
        loading = false;
        buttons.forEach((button) => { button.disabled = false; });
      }
    }
  };

  const logging = statsLoggingState();
  if (logging.available && !logging.summaryActive) {
    rangeControl.controls.hidden = true;
    host.append(node('div', 'empty', 'Top talkgroups require Stats Logging.'));
  } else {
    await load(rangeControl.buttons, true);
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

async function routedSystemScope() {
  const guid = route.get('guid');
  if (!guid) return requiredSystemScope();
  const response = await api('/api/site', { guid });
  return { wacn: response.site.wacn, system_id: response.site.system_id };
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
  { id: 'system', label: 'Sys', fullLabel: 'System', render: systemLink, sort: 'system', sortValue: systemLabel },
  { id: 'rfss', label: 'RFSS', key: 'rfss', render: (row) => hex(row.rfss, 2), className: 'numeric', sort: 'rfss' },
  { id: 'site', label: 'Site', key: 'site', render: (row) => hex(row.site, 2), className: 'numeric', sort: 'site' },
  { id: 'name', label: 'Name', render: (row) => siteLink(row), className: 'alias-cell', sort: 'name', sortValue: siteLabel },
  { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz', render: (row) => frequency(row.current_control_hz), className: 'numeric', sort: 'control', sortValue: (row) => Number(row.current_control_hz || 0) },
  { label: 'Ch', fullLabel: 'Channels', key: 'channels', className: 'numeric', sort: 'channels' },
  { label: 'Nbrs', fullLabel: 'Neighbors', key: 'neighbors', className: 'numeric', sort: 'neighbors' },
  { label: 'Bands', key: 'bands', className: 'numeric', sort: 'bands' },
  { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];
const scopedSiteColumns = siteColumns.filter((column) => column.id !== 'system');

const talkgroupColumns = [
  { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sort: 'talkgroup', sortValue: (row) => Number(row.talkgroup_id) },
  { id: 'talkgroup-name', label: 'Name', fullLabel: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row, row.talkgroup_id), className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
  { label: 'Group', key: 'alias_group', className: 'alias-cell', sort: 'group' },
  { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
  { id: 'recorded', label: 'Rec', fullLabel: 'Recorded', render: (row) => number(row.recorded_count), className: 'numeric', sort: 'recorded', sortValue: (row) => Number(row.recorded_count || 0) },
  { id: 'streamed', label: 'Sent', fullLabel: 'Sent to Streamer', render: (row) => number(row.streamed_count), className: 'numeric', sort: 'streamed', sortValue: (row) => Number(row.streamed_count || 0) },
  { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
  { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

const radioColumns = [
  { id: 'radio', label: 'ID', render: (row) => radioLink(row), className: 'numeric', sort: 'id', sortValue: (row) => Number(row.radio_id) },
  { id: 'alias', label: 'Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
  { label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
  { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
  { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
  { id: 'talkgroup-id', label: 'Affil TG', fullLabel: 'Affiliated Talkgroup ID', render: (row) => talkgroupLink(row, row.affiliated_talkgroup_id), className: 'numeric', sort: 'affiliated_talkgroup', sortValue: (row) => Number(row.affiliated_talkgroup_id) },
  { id: 'talkgroup-name', label: 'TG Name', fullLabel: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row,
    row.affiliated_talkgroup_id, 'affiliated_talkgroup_alias_'), className: 'alias-cell', sortValue: (row) => row.affiliated_talkgroup_alias_name || '' },
  { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

async function renderDashboard() {
  const dashboard = await api('/api/dashboard');
  const counts = dashboard.counts || {};
  content.append(pageHeader('Dashboard', dashboard.lastSeenMs ?
    fragment('Last activity ', dateTime(dashboard.lastSeenMs)) : 'Last activity not recorded'));
  content.append(await signalHealthSection());
  content.append(metrics([
    ['Systems', counts.systems], ['Sites', counts.sites], ['Talkgroups', counts.talkgroups],
    ['Radios', counts.radios], ['Frequencies', counts.frequencies], ['Conventional', counts.conventional]
  ]));
  const p25CallActivity = dashboard.p25CallActivity || {};
  const p25CallBody = node('div', 'dashboard-call-activity');
  p25CallBody.append(metrics([
    ['P25 Calls', p25CallActivity.totals?.call_count],
    ['Non-P25 Calls', p25CallActivity.totals?.non_p25_call_count],
    ['Recorded', p25CallActivity.totals?.recorded_count],
    ['Sent to Streamer', p25CallActivity.totals?.streamed_count]
  ], true), countTimeSeriesChart(p25CallActivity.series || [], DASHBOARD_CALL_ACTIVITY_SERIES, {
    from: p25CallActivity.from_ms,
    to: p25CallActivity.to_ms,
    ariaLabel: 'P25, non-P25, recorded, and sent-to-streamer calls per hour'
  }));
  const metricNote = outputMetricStartNote(p25CallActivity);
  if (metricNote) p25CallBody.append(metricNote);
  const p25CallSection = section('Call Activity · Last 24 Hours', p25CallBody);
  p25CallSection.append(node('div', 'dashboard-scope-note',
    'Non-P25 calls are the hourly total minus P25 trunked calls. Recorded and streamer counts apply to P25 calls.'));
  content.append(p25CallSection);
  const sites = section('Recent Sites', table(dashboard.recentSites || [], siteColumns, 'No rows', { type: 'sites' }));
  const actions = section('Site Activity · Last 24 Hours', siteActivityPie(dashboard.siteActivity24h));
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
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz', width: 100, sortValue: (row) => Number(row.frequency_hz || 0) },
    { id: 'signal', label: 'dBFS', fullLabel: 'Signal dBFS', width: 90, sortValue: (row) => Number(row.signal_dbfs ?? -999) },
    { id: 'decode-health', label: 'Decode %', width: 90, sortValue: (row) => Number(row.decode_health_pct ?? -1) },
    { id: 'source-alias', label: 'Source', fullLabel: 'Source Alias', width: 220, sortValue: (row) => row.source_alias_display || row.source_alias || row.talker_alias || '' },
    { id: 'source', label: 'Src ID', fullLabel: 'Source ID', width: 105, sortValue: (row) => Number(row.source_id || 0) },
    { id: 'target-alias', label: 'Target', fullLabel: 'Target Alias', width: 220, sortValue: (row) => row.target_alias || '' },
    { id: 'target', label: 'Tgt ID', fullLabel: 'Target ID', width: 105, sortValue: (row) => Number(row.target_id || 0) },
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
    header.title = column.fullLabel || column.label;
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
  const selectionPanel = node('div', 'live-selection-panel');
  const selectionSummary = node('div', 'live-selection-summary');
  const selectionActivity = node('div', 'live-selection-activity');
  selectionPanel.append(selectionSummary, selectionActivity);
  selectionPanel.hidden = true;
  const host = node('div', 'systems-live');
  host.append(tabBar, tableScroll, selectionPanel);
  const block = section('Live Systems', host);
  block.querySelector('.section-title').append(connection);
  let activeTableId = null;
  let selectedRowKey = null;
  let selectedSelectionId = null;
  let selectedRow = null;
  let activityView = null;
  let activityKind = null;
  let activityContextId = null;

  const rowSelectionId = (row) => row?.selection_id || row?.selectionId || null;

  const closeActivity = () => {
    if (activityView) {
      activityView.close();
      pageConnections.delete(activityView);
    }
    activityView = null;
    activityKind = null;
    activityContextId = null;
    selectionActivity.replaceChildren();
  };

  const openActivity = (kind = 'events') => {
    const requestedKind = kind === 'messages' ? 'messages' : 'events';
    if (!selectedSelectionId || !window.LiveActivityView) {
      closeActivity();
      return;
    }
    if (activityView && activityContextId === selectedSelectionId && activityKind === requestedKind) return;
    closeActivity();
    activityKind = requestedKind;
    activityContextId = selectedSelectionId;
    const activityHost = node('div');
    selectionActivity.replaceChildren(activityHost);
    activityView = new window.LiveActivityView(activityHost, {
      contextId: selectedSelectionId,
      activity: requestedKind,
      embedded: true,
      onActivityChange: openActivity
    });
    pageConnections.add(activityView);
  };

  const renderSelection = (ended = false) => {
    if (!selectedRow || !selectedSelectionId) {
      closeActivity();
      selectionPanel.hidden = true;
      selectionSummary.replaceChildren();
      return;
    }
    const tableValue = tables.get(activeTableId) || {};
    const copy = node('div', 'live-selection-copy');
    const identity = selectedRow.channel_name || selectedRow.channelName || selectedRow.lcn ||
      frequency(selectedRow.frequency_hz ?? selectedRow.frequencyHz);
    copy.append(node('strong', '', `${tableValue.title || tableValue.channel_name || 'Live'} · ${identity}`));
    const scope = String(selectedRow.selection_scope || selectedRow.selectionScope || '').toUpperCase() === 'SITE' ?
      'Site-wide context' : `Exact frequency${selectedRow.timeslot == null ? '' : ` · timeslot ${selectedRow.timeslot}`}`;
    copy.append(node('span', '', `${scope}${ended ? ' · selected channel ended' : ''}`));
    selectionSummary.replaceChildren(copy);
    selectionPanel.hidden = false;
  };

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
      (Number(row.decode_health_pct) >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'quality-good' :
        (Number(row.decode_health_pct) >= DECODE_DEGRADED_MINIMUM_PERCENT ?
          'quality-warn' : 'quality-bad'));
    element.classList.toggle('selected', selectedRowKey === row.key);
  };

  const createRow = (row) => {
    const element = node('tr');
    element.dataset.key = row.key;
    element.tabIndex = 0;
    element.setAttribute('role', 'button');
    for (let index = 0; index < 11; index += 1) element.append(node('td'));
    const select = () => {
      selectedRowKey = row.key;
      const current = (tables.get(activeTableId)?.rows || []).find((candidate) => candidate.key === row.key) || row;
      selectedSelectionId = rowSelectionId(current);
      selectedRow = current;
      rowNodes.forEach((candidate, key) => candidate.classList.toggle('selected', key === selectedRowKey));
      renderSelection();
      openActivity('events');
    };
    element.addEventListener('click', select);
    element.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        select();
      }
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
    selectedSelectionId = null;
    selectedRow = null;
    renderSelection();
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
    if (selectedSelectionId) {
      const matching = (value.rows || []).find((row) => row.key === selectedRowKey) ||
        (value.rows || []).find((row) => rowSelectionId(row) === selectedSelectionId);
      if (matching) {
        selectedRowKey = matching.key;
        selectedRow = matching;
        rowNodes.forEach((candidate, key) => candidate.classList.toggle('selected', key === selectedRowKey));
        renderSelection();
      } else {
        renderSelection(true);
      }
    }
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
      else {
        selectedRowKey = null;
        selectedSelectionId = null;
        selectedRow = null;
        renderSelection();
        body.replaceChildren();
      }
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
  const contextId = route.get('context');
  const activity = route.get('activity');
  if (contextId && ['events', 'messages'].includes(activity) && window.LiveActivityView) {
    const host = node('div');
    content.append(host);
    const view = new window.LiveActivityView(host, { contextId, activity });
    pageConnections.add(view);
  } else {
    content.append(liveSystemsSection());
  }
}

async function renderSystems() {
  const page = await api('/api/system-directory', pageParameters({ limit: 25 }));
  content.append(pageHeader('Systems & Sites',
    'Parent systems with child sites · fixed order by WACN, System ID, RFSS, and Site'));
  const rows = [];
  (page.rows || []).forEach((system) => {
    rows.push({ ...system, directory_type: 'system' });
    (system.children || []).forEach((site) => rows.push({ ...site, directory_type: 'site' }));
  });
  const columns = [
    { id: 'directory-name', label: 'System / Site', width: 230, className: 'directory-name', render: (row) => {
      const wrapper = node('div', 'directory-entity');
      if (row.directory_type === 'system') {
        wrapper.append(node('strong', '', 'System'));
        if (row.site_names) wrapper.append(node('span', 'directory-secondary', row.site_names));
      } else {
        wrapper.append(node('span', 'directory-branch', '↳'), siteLink(row));
      }
      return wrapper;
    } },
    { id: 'wacn', label: 'WACN', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? hex(row.wacn, 5) : '' },
    { id: 'system', label: 'Sys ID', fullLabel: 'System ID', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? systemLink(row, hex(row.system_id, 3)) : '' },
    { id: 'rfss', label: 'RFSS', className: 'numeric', render: (row) =>
      row.directory_type === 'site' ? hex(row.rfss, 2) : '' },
    { id: 'site', label: 'Site', className: 'numeric', render: (row) =>
      row.directory_type === 'site' ? hex(row.site, 2) : '' },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz', className: 'numeric',
      render: (row) => row.directory_type === 'site' ? frequency(row.current_control_hz) : '' },
    { id: 'count', label: 'Sites / Ch', fullLabel: 'Sites or Channels', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? `${number(row.sites)} ${Number(row.sites) === 1 ? 'site' : 'sites'}` :
        `${number(row.channels)} ch` },
    { id: 'talkgroups', label: 'TGs', fullLabel: 'Talkgroups', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? number(row.talkgroups) : '' },
    { id: 'radios', label: 'Radios', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? number(row.radios) : '' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
  ];
  content.append(searchBar('Search system, site name, or GUID'));
  const directory = section('System Directory', table(rows, columns, 'No systems or sites recorded', {
    type: 'system-directory',
    sortable: false,
    rowClass: (row) => `directory-${row.directory_type}-row`
  }));
  const truncated = (page.rows || []).filter((row) => row.children_truncated);
  if (truncated.length) directory.append(node('div', 'directory-warning',
    `${number(truncated.length)} system group${truncated.length === 1 ? '' : 's'} exceeded the child-site display limit.`));
  directory.append(pager(page));
  content.append(directory);
}

async function renderSites() {
  return renderSystems();
}

async function renderSystem() {
  const systemScope = await routedSystemScope();
  const response = await api('/api/system', systemScope);
  const system = response.system;
  const requestedTab = route.get('tab') || 'info';
  const tab = requestedTab === 'sites' ? 'info' : requestedTab;
  content.append(pageHeader(systemValue(system), system.site_names || 'P25 trunked system'), systemTabs(system, tab));

  if (tab === 'talkgroups') {
    const page = await api('/api/system/talkgroups', pageParameters(systemScope));
    content.append(pagedSection('Talkgroups', page, talkgroupColumns, 'Search talkgroup ID', 'talkgroups'));
  } else if (tab === 'radios') {
    const page = await api('/api/system/radios', pageParameters(systemScope));
    content.append(pagedSection('Radios', page, radioColumns, 'Search radio ID', 'radios'));
  } else if (tab === 'talker-aliases') {
    const page = await api('/api/system/talker-aliases', pageParameters(systemScope));
    const columns = [
      { id: 'radio', label: 'Radio', fullLabel: 'Radio ID', render: (row) => radioLink(row), className: 'numeric', sort: 'radio', sortValue: (row) => Number(row.radio_id) },
      { id: 'talker-alias', label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
      { id: 'radio-alias', label: 'Alias', fullLabel: 'Configured Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
      { id: 'talkgroup-id', label: 'Last TGID', render: (row) => talkgroupLink(row, row.last_talkgroup_id), className: 'numeric', sort: 'last_talkgroup', sortValue: (row) => Number(row.last_talkgroup_id) },
      { id: 'talkgroup-name', label: 'TG Name', fullLabel: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row, row.last_talkgroup_id, 'talkgroup_alias_'), className: 'alias-cell', sort: 'last_talkgroup_name', sortValue: (row) => row.talkgroup_alias_name || '' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Alias Seen', fullLabel: 'Talker Alias Last Seen', render: (row) => dateTime(row.last_talker_alias_seen_ms), sort: 'talker_alias_seen', sortValue: (row) => Number(row.last_talker_alias_seen_ms || 0) }
    ];
    const block = pagedSection('Talker Alias Summary', page, columns,
      'Search radio ID or talker alias', 'talker-aliases');
    if (!page.rows.length) block.querySelector('.empty').textContent = 'No talker aliases recorded for this system';
    content.append(block);
  } else {
    const infoColumn = node('div', 'entity-info-column system-info-column');
    const summary = metrics([
      ['Sites', system.sites], ['Talkgroups', system.talkgroups], ['Radios', system.radios],
      ['Affiliated', system.affiliations], ['Calls', system.activity_calls]
    ]);
    summary.classList.add('system-summary-band');
    infoColumn.append(summary, section('System Info', keyValues([
      ['System', systemInfoValue(system)],
      ['First Seen', dateTime(system.first_seen_ms)], ['Last Seen', dateTime(system.last_seen_ms)]
    ])), section('Observed Actions', fragment(table(withoutGrantActions(response.actionCounts), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No actions recorded', { type: 'action-counts' }), activityMetricGuide())));

    const sitesPage = await api('/api/system/sites', pageParameters(systemScope));
    const sitesColumn = node('div', 'entity-info-column system-sites-column');
    sitesColumn.append(pagedSection('Sites', sitesPage, scopedSiteColumns,
      'Search site name or GUID', 'sites'));
    const layout = node('div', 'entity-info-layout system-info-layout');
    layout.append(infoColumn, sitesColumn);
    content.append(layout);
  }
}

async function renderTalkgroup() {
  const systemScope = requiredSystemScope();
  const id = requiredId();
  const response = await api('/api/talkgroup', { ...systemScope, talkgroup_id: id });
  const talkgroup = response.talkgroup;
  const tab = route.get('tab') || 'info';
  const title = aliasLabel(talkgroup) || `Talkgroup ${id}`;
  content.append(pageHeader(title, fragment(systemValue(talkgroup), ` · Talkgroup ${id}`)),
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
      { label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
      { id: 'affiliated', label: 'Affil', fullLabel: 'Affiliated', render: (row) => checkbox(affiliated.has(Number(row.radio_id))), className: 'center', sort: 'affiliated', sortValue: (row) => affiliated.has(Number(row.radio_id)) },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    content.append(pagedSection('Radios', relationships, columns, null, 'talkgroup-radios'));
  } else if (tab === 'activity') {
    if (detailedHistoryAvailable()) {
      await renderActivity({ ...systemScope, talkgroup_id: id }, 'Activity Log');
    } else {
      content.append(section('Activity Log', node('div', 'empty',
        'Detailed history logging is not running.')));
    }
  } else {
    const affiliationLink = anchor(number(talkgroup.affiliated_radios),
      href('talkgroup', { ...scope(talkgroup), id, tab: 'radios' }));
    const infoColumn = node('div', 'entity-info-column');
    infoColumn.append(section('Talkgroup Info', keyValues([
      ['System', systemLink(talkgroup, systemInfoValue(talkgroup))],
      ['Talkgroup ID', id], ['Alias', aliasLabel(talkgroup)],
      ['Group', talkgroup.alias_group], ['First Seen', dateTime(talkgroup.first_seen_ms)],
      ['Last Seen', dateTime(talkgroup.last_seen_ms)], ['Calls', number(talkgroup.call_count)],
      ['Recorded', number(talkgroup.recorded_count)],
      ['Sent to Streamer', number(talkgroup.streamed_count)],
      ['Radios', number(talkgroup.radios)], ['Currently Affiliated', affiliationLink],
      ['Enc', number(talkgroup.encrypted_count)],
      ['Last Source', radioLink(talkgroup, talkgroup.last_source_radio_id)],
      ['Last Alg', encryptionAlgorithmInfoValue(talkgroup.last_encryption_algorithm_id)],
      ['Last Key ID', hexDecimalPair(talkgroup.last_encryption_key_id)]
    ])));
    infoColumn.append(section('Action Counts', table(actionCounts(talkgroup).map(([action, count]) => ({ action, count })), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No actions recorded', { type: 'action-counts' })));
    const layout = node('div', 'entity-info-layout');
    layout.append(infoColumn, await talkgroupActivityHistorySection({ ...systemScope, talkgroup_id: id }));
    content.append(layout);
  }
}

async function renderRadio() {
  const systemScope = requiredSystemScope();
  const id = requiredId();
  const response = await api('/api/radio', { ...systemScope, radio_id: id });
  const radio = response.radio;
  const tab = route.get('tab') || 'info';
  const title = aliasLabel(radio) || radio.last_talker_alias || `Radio ${id}`;
  content.append(pageHeader(title, fragment(systemValue(radio), ` · Radio ${id}`)),
    entityTabs('radio', radio, id, tab, true));

  if (tab === 'talkgroups') {
    const relationships = await api('/api/radio-talkgroups',
      pageParameters({ ...systemScope, radio_id: id }));
    const columns = [
      { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sort: 'talkgroup', sortValue: (row) => Number(row.talkgroup_id) },
      { id: 'talkgroup-name', label: 'TG Name', fullLabel: 'Talkgroup Name', render: (row) => talkgroupAliasLink(row,
        row.talkgroup_id, 'talkgroup_alias_'), className: 'alias-cell', sort: 'talkgroup_alias', sortValue: (row) => row.talkgroup_alias_name || '' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    content.append(pagedSection('Talkgroups', relationships, columns, null, 'radio-talkgroups'));
  } else if (tab === 'activity') {
    await renderActivity({ ...systemScope, radio_id: id });
  } else {
    content.append(section('Radio Info', keyValues([
      ['System', systemLink(radio, systemInfoValue(radio))], ['Radio ID', id], ['Alias', aliasLabel(radio)],
      ['Talker Alias', radio.last_talker_alias], ['Talker Alias Seen', dateTime(radio.last_talker_alias_seen_ms)],
      ['Current Affiliation TGID', talkgroupLink(radio, radio.affiliated_talkgroup_id)],
      ['Current Affiliation Name', talkgroupAliasLink(radio, radio.affiliated_talkgroup_id,
        'affiliated_talkgroup_alias_')],
      ['Affiliation Updated', dateTime(radio.affiliation_updated_at_ms)],
      ['First Seen', dateTime(radio.first_seen_ms)], ['Last Seen', dateTime(radio.last_seen_ms)],
      ['Calls', number(radio.call_count)],
      ['Talkgroups', number(radio.talkgroups)],
      ['Enc', number(radio.encrypted_count)],
      ['Last Alg', encryptionAlgorithmInfoValue(radio.last_encryption_algorithm_id)],
      ['Last Key ID', hexDecimalPair(radio.last_encryption_key_id)]
    ])));
    content.append(section('Action Counts', fragment(table(actionCounts(radio).map(([action, count]) => ({ action, count })), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No actions recorded', { type: 'action-counts' }), activityMetricGuide())));
  }
}

async function renderSite() {
  const guid = route.get('guid');
  if (!guid) throw new Error('Site GUID is missing from the URL');
  const response = await api('/api/site', { guid });
  const site = response.site;
  const requestedTab = route.get('tab') || 'info';
  const tab = requestedTab === 'talkgroups' ? 'info' : requestedTab;
  content.append(pageHeader(siteValue(site), fragment(systemValue(site), ' · ',
    hex(site.rfss, 2), '-', hex(site.site, 2))),
    siteTabs(site, tab));

  if (tab === 'quality') {
    content.append(await siteSignalHistorySection(site));
  } else if (tab === 'channels') {
    const data = await api('/api/site/channels', { guid });
    const columns = [
      { label: 'LCN / Mode', fullLabel: 'Logical Channel Number and Modes', key: 'descriptor' },
      { label: 'Callsign', key: 'callsign' },
      { label: 'Tags', key: 'tags', render: channelTags },
      { id: 'downlink', label: 'Down MHz', fullLabel: 'Downlink MHz', render: (row) => frequency(row.downlink_hz), className: 'numeric', sortValue: (row) => Number(row.downlink_hz || 0) },
      { id: 'uplink', label: 'Up MHz', fullLabel: 'Uplink MHz', render: (row) => frequency(row.uplink_hz), className: 'numeric', sortValue: (row) => Number(row.uplink_hz || 0) },
      { id: 'tdma', label: 'TDMA', render: (row) => yesNo(row.tdma), sortValue: (row) => Boolean(row.tdma) },
      { label: 'Slots', key: 'timeslots', className: 'numeric' },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Voice', fullLabel: 'Voice Grant Observations', key: 'voice_grant_observations', className: 'numeric' },
      { label: 'Data', fullLabel: 'Data Grant Observations', key: 'data_grant_observations', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    const rows = data.rows || [];
    content.append(section('Channels', table(rows, columns, 'No channels recorded', { type: 'site-channels' })));
  } else if (tab === 'neighbors') {
    const data = await api('/api/site/neighbors', { guid });
    content.append(section('Neighbors', table(data.rows || [], [
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { id: 'type', label: 'Type', render: (row) => row.entry_type === 'ISSI' ? 'ISSI System' : 'Site' },
      { id: 'wacn', label: 'WACN', render: (row) => hex(row.wacn, 5), sortValue: (row) => Number(row.wacn || 0) },
      { id: 'system', label: 'Sys', fullLabel: 'System', render: (row) => hex(row.system_id, 3), sortValue: (row) => Number(row.system_id || 0) },
      { id: 'rfss', label: 'RFSS', render: (row) => hex(row.rfss, 2), sortValue: (row) => Number(row.rfss || 0) },
      { id: 'site', label: 'Site', render: (row) => hex(row.site, 2), sortValue: (row) => Number(row.site || 0) },
      { id: 'lra', label: 'LRA', render: (row) => hex(row.lra, 2), sortValue: (row) => Number(row.lra || 0) },
      { label: 'LCN', key: 'channel_descriptor' },
      { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz', render: (row) => frequency(row.downlink_hz), className: 'numeric', sortValue: (row) => Number(row.downlink_hz || 0) },
      { id: 'modes', label: 'Modes', render: neighborModes },
      { id: 'bands', label: 'Bands', key: 'band_count', className: 'numeric' },
      { id: 'advertised-status', label: 'Status', fullLabel: 'Advertised Status', render: (row) => neighborStatus(row.status), sortValue: (row) => row.status || '' },
      { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No neighbors recorded', { type: 'site-neighbors' })));
  } else if (tab === 'band-plan') {
    const data = await api('/api/site/bands', { guid });
    content.append(section('Home System Band Plan', table(data.rows || [], [
      { label: 'Band', key: 'band', className: 'numeric' },
      { id: 'base', label: 'Base', fullLabel: 'Base MHz', render: (row) => frequency(row.base_hz), className: 'numeric', sortValue: (row) => Number(row.base_hz || 0) },
      { id: 'spacing', label: 'Space', fullLabel: 'Spacing kHz', render: (row) => row.spacing_hz ? (row.spacing_hz / 1000).toFixed(3) : '', className: 'numeric', sortValue: (row) => Number(row.spacing_hz || 0) },
      { label: 'BW Hz', fullLabel: 'Bandwidth Hz', key: 'bandwidth', className: 'numeric' },
      { id: 'offset', label: 'Offset', fullLabel: 'Offset MHz', render: (row) => row.transmit_offset_hz ? (row.transmit_offset_hz / 1000000).toFixed(5) : '', className: 'numeric', sortValue: (row) => Number(row.transmit_offset_hz || 0) },
      { id: 'tdma', label: 'TDMA', render: (row) => yesNo(row.tdma), sortValue: (row) => Boolean(row.tdma) },
      { label: 'Slots', key: 'timeslots', className: 'numeric' },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No home-system band plan recorded', { type: 'site-bands' })));
    content.append(section('ISSI Advertised Band Plans', table(data.foreign_rows || [], [
      { id: 'wacn', label: 'WACN', render: (row) => hex(row.foreign_wacn, 5), sortValue: (row) => Number(row.foreign_wacn || 0) },
      { id: 'system', label: 'Sys', fullLabel: 'Foreign System', render: (row) => hex(row.foreign_system_id, 3), sortValue: (row) => Number(row.foreign_system_id || 0) },
      { label: 'Band', key: 'band', className: 'numeric' },
      { id: 'mode', label: 'Mode', render: (row) => foreignBandDetails(row.channel_type).mode },
      { id: 'base', label: 'Base', fullLabel: 'Base MHz', render: (row) => frequency(row.base_hz), className: 'numeric', sortValue: (row) => Number(row.base_hz || 0) },
      { id: 'spacing', label: 'Space', fullLabel: 'Spacing kHz', render: (row) => row.spacing_hz ? (row.spacing_hz / 1000).toFixed(3) : '', className: 'numeric', sortValue: (row) => Number(row.spacing_hz || 0) },
      { id: 'bandwidth', label: 'BW Hz', fullLabel: 'Bandwidth Hz', render: (row) => foreignBandDetails(row.channel_type).bandwidth, className: 'numeric' },
      { id: 'offset', label: 'Offset', fullLabel: 'Offset MHz', render: (row) => row.transmit_offset_hz ? (row.transmit_offset_hz / 1000000).toFixed(5) : '', className: 'numeric', sortValue: (row) => Number(row.transmit_offset_hz || 0) },
      { id: 'slots', label: 'Slots', render: (row) => foreignBandDetails(row.channel_type).slots, className: 'numeric' },
      { id: 'voice-rate', label: 'Voice Rate', render: (row) => foreignBandDetails(row.channel_type).voiceRate },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No ISSI-advertised band plans recorded', { type: 'site-foreign-bands' })));
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
      { id: 'patch-id', label: 'Patch', fullLabel: 'Patch Talkgroup ID', render: (row) => talkgroupLink(site, row.patch_group), className: 'numeric', sortValue: (row) => Number(row.patch_group) },
      { id: 'patch-name', label: 'Name', fullLabel: 'Patch Name', render: (row) => row.patch_alias_name ?
        talkgroupLink(site, row.patch_group, row.patch_alias_name) : '', className: 'alias-cell', sortValue: (row) => row.patch_alias_name || '' },
      { id: 'member-talkgroup-ids', label: 'TGIDs', fullLabel: 'Member Talkgroup IDs', render: (row) =>
        memberLinks(talkgroups.get(row.patch_group), (member) => talkgroupLink(site, member.talkgroup_id)) },
      { id: 'member-talkgroup-names', label: 'TG Names', fullLabel: 'Talkgroup Names', className: 'alias-cell', render: (row) =>
        memberLinks(talkgroups.get(row.patch_group), (member) => member.alias_name ?
          talkgroupLink(site, member.talkgroup_id, member.alias_name) : '') },
      { id: 'member-radio-ids', label: 'Radios', fullLabel: 'Radio IDs', render: (row) =>
        memberLinks(radios.get(row.patch_group), (member) => radioLink(site, member.radio_id)) },
      { id: 'member-radio-names', label: 'Radio Names', className: 'alias-cell', render: (row) =>
        memberLinks(radios.get(row.patch_group), (member) => member.alias_name ?
          radioLink(site, member.radio_id, member.alias_name) : '') },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    if (groups.some((row) => Number(row.version))) columns.splice(2, 0,
      { label: 'Version', key: 'version', className: 'numeric' });
    content.append(section('Patches', table(groups, columns, 'No patches recorded', { type: 'site-patches' })));
  } else if (tab === 'activity') {
    await renderActivity({ guid });
  } else {
    const infoColumn = node('div', 'entity-info-column');
    infoColumn.append(section('Site Info', keyValues([
      ['System', systemLink(site, systemInfoValue(site))], ['GUID', site.guid], ['Name', site.channel_name],
      ['Alias List', site.alias_list_name], ['Protocol', site.protocol], ['Decoder', site.decoder],
      ['Callsign', site.callsign], ['WACN', hexDecimalPair(site.wacn, 5)],
      ['SysID', hexDecimalPair(site.system_id, 3)], ['NAC', hexDecimalPair(site.nac, 3)],
      ['RFSS', hexDecimalPair(site.rfss, 2)], ['Site', hexDecimalPair(site.site, 2)],
      ['Local Registration Area', hexDecimalPair(site.lra, 2)],
      ['MFID', site.mfid === null || site.mfid === undefined ? site.mfid_display : hexDecimalPair(site.mfid, 2)],
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
    const layout = node('div', 'entity-info-layout');
    layout.append(infoColumn, await siteTopTalkgroupsSection(site));
    content.append(layout);
  }
}

function activityColumns() {
  return [
    { id: 'time', label: 'Seen', fullLabel: 'Observed Time', render: (row) => dateTime(row.observed_at_ms), sortValue: (row) => Number(row.observed_at_ms || 0) },
    { label: 'Action', key: 'action' },
    { label: 'Event', key: 'event_type' },
    { id: 'source', label: 'Src', fullLabel: 'Source ID', render: (row) => radioLink(row, row.source_radio_id), className: 'numeric', sortValue: (row) => Number(row.source_radio_id || 0) },
    { id: 'source-alias', label: 'Src Alias', fullLabel: 'Source Alias', render: (row) => row.source_alias_name ? radioLink(row, row.source_radio_id, row.source_alias_name) : '', className: 'alias-cell', sortValue: (row) => row.source_alias_name || '' },
    { id: 'target', label: 'Tgt', fullLabel: 'Target ID', render: (row) => TALKGROUP_TARGET_KINDS.has(Number(row.target_kind_code)) ? talkgroupLink(row, row.target_id) : row.target_id, className: 'numeric', sortValue: (row) => Number(row.target_id || 0) },
    { id: 'target-alias', label: 'Tgt Alias', fullLabel: 'Target Alias', render: (row) => row.target_alias_name || '', className: 'alias-cell', sortValue: (row) => row.target_alias_name || '' },
    { id: 'frequency', label: 'MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sortValue: (row) => Number(row.frequency_hz || 0) },
    { label: 'LCN', key: 'lcn' },
    { label: 'Slot', key: 'timeslot', className: 'numeric' },
    { id: 'encryption', label: 'Enc', fullLabel: 'Encryption', render: (row) => row.encrypted ? encryptionDetails(row.encryption_algorithm_id, row.encryption_key_id) : '', className: 'encrypted', sortValue: (row) => row.encrypted ? `${row.encryption_algorithm_id}:${row.encryption_key_id}` : '' }
  ];
}

async function renderActivity(scopeParameters, title = 'Activity') {
  if (!detailedHistoryAvailable()) {
    content.append(section(title, node('div', 'empty',
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
  const block = section(title, activityTable);
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
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency', sortValue: (row) => Number(row.frequency_hz || 0) },
    { label: 'Slot', key: 'timeslot', className: 'numeric', sort: 'slot' },
    { id: 'nac', label: 'NAC', render: (row) => hex(row.nac, 3), sort: 'nac', sortValue: (row) => Number(row.nac || 0) },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
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
      ['NAC', hexDecimalPair(context.nac, 3)], ['First Seen', dateTime(context.first_seen_ms)],
      ['Last Seen', dateTime(context.last_seen_ms)]
    ])));
    content.append(section('Frequency Summaries', table(data.summaries || [], [
      { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sortValue: (row) => Number(row.frequency_hz || 0) },
      { label: 'Slot', key: 'timeslot', className: 'numeric' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'first-seen', label: 'First', fullLabel: 'First Seen', render: (row) => dateTime(row.first_seen_ms), sortValue: (row) => Number(row.first_seen_ms || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
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
  const webInterface = node('p');
  webInterface.append('Web interface by Tyler Watthanaphand ',
    externalAnchor('@tylerwatt12', 'https://github.com/tylerwatt12'), '.');
  project.append(lineage, webInterface);
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

function renderSpectrum() {
  const host = node('div');
  content.append(host);
  const view = new window.WidebandSignalView(host);
  pageConnections.add(view);
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
    const runtime = serviceStatus.runtime || {};
    const calibration = runtime.calibration || {};
    const voiceDecryption = runtime.voiceDecryption || {};
    const runtimeAlerts = [];
    const pendingCalibrations = Number(calibration.pending);
    if (calibration.available && pendingCalibrations > 0) {
      runtimeAlerts.push(`${number(pendingCalibrations)} calibrations pending`);
    }
    const vaultState = String(voiceDecryption.vaultState || '').toLowerCase();
    if (voiceDecryption.moduleLoaded && voiceDecryption.vaultPresent && vaultState !== 'unlocked') {
      runtimeAlerts.push(`Vault ${vaultState || 'unavailable'}`);
    }
    document.getElementById('server-status').textContent =
      `${summaryLabel} · ${historyLabel} · ${size} MB` +
      (runtimeAlerts.length ? ` · ${runtimeAlerts.join(' · ')}` : '');
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
  if (route.get('view') === 'sites') {
    route.set('view', 'systems');
    window.history.replaceState({}, '', `${window.location.pathname}?${route}`);
  }
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
      spectrum: renderSpectrum,
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
