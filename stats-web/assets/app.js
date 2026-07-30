let route = new URLSearchParams(window.location.search);
const content = document.getElementById('content');
const tableOnly = route.get('layout') === 'table';
const TABLE_WIDTH_COOKIE = 'sdrtrunk_table_widths_v4';
const TABLE_WIDTH_MINIMUM = 48;
const TABLE_WIDTH_MAXIMUM = 1200;
const SIGNAL_OFFLINE_MILLISECONDS = 45_000;
const SITE_METADATA_OFFLINE_MILLISECONDS = 30_000;
const DECODE_HEALTHY_MINIMUM_PERCENT = 90;
const DECODE_DEGRADED_MINIMUM_PERCENT = 75;
const VOICE_QUALITY_WARMUP_FRAMES = 50;
const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
const THEME_STORAGE_KEY = 'sdrtrunk_theme';
const SIGNAL_RANGES = Object.freeze([
  ['1h', '1 hour'], ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days']
]);
const ACTIVITY_RANGES = Object.freeze([
  ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days']
]);
const CALL_ACTIVITY_SERIES = Object.freeze([
  { field: 'call_count', label: 'Tracked Calls', color: 'var(--chart-call)', visible: true },
  { field: 'recorded_count', label: 'Recorded', color: 'var(--chart-recorded)', visible: true },
  { field: 'streamed_count', label: 'Sent to Streamer', color: 'var(--chart-streamed)', visible: true }
]);
const DASHBOARD_CALL_METRICS = Object.freeze([
  { field: 'call_count', label: 'Calls' },
  { field: 'recorded_count', label: 'Recorded' },
  { field: 'streamed_count', label: 'Sent' }
]);
const DASHBOARD_PROTOCOL_SERIES = Object.freeze([
  { key: 'P25', label: 'P25', color: 'var(--chart-call)' },
  { key: 'DMR', label: 'DMR', color: 'var(--chart-recorded)' },
  { key: 'NXDN', label: 'NXDN', color: 'var(--chart-streamed)' },
  { key: 'NBFM', label: 'NBFM', color: 'var(--chart-data)' }
]);
const DASHBOARD_CHANNEL_KIND_FILTERS = Object.freeze([
  { value: 'ALL', label: 'All' },
  { value: 'TRUNKED', label: 'Trunked' },
  { value: 'CONVENTIONAL', label: 'Conventional' }
]);
const TALKGROUP_CALL_ACTIVITY_SERIES = Object.freeze([
  ...CALL_ACTIVITY_SERIES,
  { field: 'encrypted_count', label: 'Encrypted', color: 'var(--chart-encrypted)', visible: true }
]);
const TALKGROUP_SIGNALING_SERIES = Object.freeze([
  { field: 'emergency_count', label: 'Emergency', color: 'var(--chart-emergency)' },
  { field: 'data_count', label: 'Data', color: 'var(--chart-data)' },
  { field: 'join_count', label: 'Join', color: 'var(--chart-join)' },
  { field: 'register_count', label: 'Register', color: 'var(--chart-register)' },
  { field: 'denial_count', label: 'Denial', color: 'var(--chart-denial)' },
  { field: 'busy_count', label: 'Busy', color: 'var(--chart-busy)' },
  { field: 'queued_count', label: 'Queued', color: 'var(--chart-queued)' },
  { field: 'continue_count', label: 'Continue', color: 'var(--chart-continue)' },
  { field: 'active_count', label: 'Active', color: 'var(--chart-active)' },
  { field: 'acknowledge_count', label: 'Acknowledge', color: 'var(--chart-acknowledge)' },
  { field: 'check_count', label: 'Check', color: 'var(--chart-check)' },
  { field: 'check_ack_count', label: 'Check Ack', color: 'var(--chart-check-ack)' },
  { field: 'gps_count', label: 'GPS', color: 'var(--chart-gps)' },
  { field: 'logout_count', label: 'Logout', color: 'var(--chart-logout)' },
  { field: 'page_count', label: 'Page', color: 'var(--chart-page)' },
  { field: 'patch_count', label: 'Patch', color: 'var(--chart-patch)' },
  { field: 'patch_cancel_count', label: 'Patch Cancel', color: 'var(--chart-patch-cancel)' },
  { field: 'patch_create_count', label: 'Patch Create', color: 'var(--chart-patch-create)' },
  { field: 'request_count', label: 'Request', color: 'var(--chart-request)' },
  { field: 'status_count', label: 'Status', color: 'var(--chart-status)' },
  { field: 'unknown_count', label: 'Unknown', color: 'var(--chart-unknown)' }
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
  DATA_ANNOUNCED: { abbreviation: 'DAT-A', description: 'Announced data channel', className: 'role-data-announced' },
  CWID: { abbreviation: 'CWID', description: 'Base station identification channel' }
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
  'signaling': 134,
  'event': 115,
  'first-seen': 166,
  'frequency': 94,
  'group': 135,
  'last-active': 166,
  'last-seen': 166,
  'lcn': 68,
  'name': 175,
  'neighbor-name': 175,
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
  'talkgroup-description': 240,
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
  conventional: 'frequency',
  'conventional-talkgroups': 'calls',
  'conventional-radios': 'calls'
};
const CONVENTIONAL_IDENTITY_PAGE_LIMIT = 100;
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

function currentTheme() {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

function updateThemeToggle() {
  const toggle = document.getElementById('theme-toggle');
  if (!toggle) return;
  const dark = currentTheme() === 'dark';
  toggle.textContent = dark ? 'Light' : 'Dark';
  toggle.setAttribute('aria-pressed', String(dark));
  toggle.setAttribute('aria-label', dark ? 'Use light theme' : 'Use dark theme');
  toggle.title = dark ? 'Use light theme' : 'Use dark theme';
}

function setTheme(theme, persist = true) {
  if (theme === 'dark') document.documentElement.dataset.theme = 'dark';
  else document.documentElement.removeAttribute('data-theme');
  if (persist) {
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, theme === 'dark' ? 'dark' : 'light');
    } catch (error) {
      // Browser storage can be disabled; the active page theme still changes.
    }
  }
  updateThemeToggle();
}

function initializeThemeToggle() {
  const toggle = document.getElementById('theme-toggle');
  if (!toggle) return;
  updateThemeToggle();
  toggle.addEventListener('click', () => setTheme(currentTheme() === 'dark' ? 'light' : 'dark'));
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

function encryptionAlgorithmInfoValue(display, rawValue) {
  if (display) return display;
  if (rawValue === null || rawValue === undefined || rawValue === '') return '';
  return `ALG:${hex(rawValue, 2)}`;
}

function encryptionActivityValue(row) {
  if (!row.encrypted) return '';
  const value = node('span', '', row.encryption_display || 'ENC');
  if (row.encryption_full_display && row.encryption_full_display !== row.encryption_display) {
    value.title = row.encryption_full_display;
  }
  return value;
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
  const values = { 1: 'P25 Phase 1', 2: 'P25 Phase 2', 3: 'DMR', 4: 'NXDN', 10: 'NBFM' };
  return values[Number(value)] || value || '';
}

function decoderLabel(value, compact = false) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  const labels = {
    P25_PHASE1: ['P25 P1', 'P25 Phase 1'],
    P25_PHASE_1: ['P25 P1', 'P25 Phase 1'],
    'P25-1': ['P25 P1', 'P25 Phase 1'],
    P25_PHASE2: ['P25 P2', 'P25 Phase 2'],
    P25_PHASE_2: ['P25 P2', 'P25 Phase 2'],
    'P25-2': ['P25 P2', 'P25 Phase 2'],
    P25_CONVENTIONAL: ['P25 Conv', 'P25 Conventional'],
    'P25-C': ['P25 Conv', 'P25 Conventional'],
    DMR: ['DMR', 'DMR'],
    NXDN: ['NXDN', 'NXDN'],
    NBFM: ['NBFM', 'NBFM']
  };
  const known = labels[raw.toUpperCase()];
  if (known) return compact ? known[0] : known[1];
  return raw;
}

function decoderDisplay(value) {
  const label = decoderLabel(value, true);
  if (!label) return '';
  const display = node('span', '', label);
  display.title = decoderLabel(value);
  return display;
}

function aliasLabel(row, prefix = 'alias_') {
  return row[`${prefix}name`] || '';
}

function systemLabel(row) {
  if (!isP25(row)) return trunkedSystemLabel(row);
  const wacn = hex(row.wacn, 5);
  const system = hex(row.system_id, 3);
  return wacn && system ? `${wacn}-${system}` : wacn || system;
}

function systemValue(row) {
  return systemLabel(row);
}

function systemInfoValue(row) {
  if (!isP25(row)) return systemValue(row);
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
  if (!isP25(row)) return trunkedSiteLabel(row);
  const identity = `${hex(row.rfss, 2)}-${hex(row.site, 2)}`;
  return row.channel_name || `${systemLabel(row)} ${identity}`;
}

function siteValue(row) {
  return siteLabel(row);
}

function protocolFamily(row) {
  const code = Number(row?.protocol_code);
  if (code === 1 || code === 2) return 'P25';
  if (code === 3) return 'DMR';
  if (code === 4) return 'NXDN';
  if (code === 10) return 'NBFM';
  return row?.protocol || '';
}

function isP25(row) {
  return ['P25', 'APCO25', 'APCO25_PHASE2'].includes(protocolFamily(row)) ||
    ['APCO25', 'APCO25_PHASE2'].includes(row?.protocol) ||
    (row?.wacn !== null && row?.wacn !== undefined);
}

function identifierNumber(value) {
  const numeric = Number(value);
  return value === null || value === undefined || value === '' || !Number.isFinite(numeric) || numeric < 0 ?
    '' : String(Math.trunc(numeric));
}

function trunkedSystemLabel(row) {
  const configured = row.configured_system || row.site_names;
  if (configured) return configured;
  const identities = [];
  if (row.network_id !== null && row.network_id !== undefined) {
    identities.push(`Network ${identifierNumber(row.network_id)}`);
  }
  if (row.system_id !== null && row.system_id !== undefined) {
    identities.push(`System ${identifierNumber(row.system_id)}`);
  }
  return identities.length ? `${protocolFamily(row)} ${identities.join(' · ')}` : `${protocolFamily(row)} system`;
}

function trunkedSiteLabel(row) {
  return row.channel_name || row.configured_system ||
    `${protocolFamily(row)} site ${identifierNumber(row.site_id) || identifierNumber(row.ran) || row.guid}`;
}

function trunkedVariant(row) {
  const protocolName = protocolFamily(row);
  const raw = String(row.variant || '').toUpperCase();
  if (raw === 'TIER_III') return 'Tier III';
  if (raw === 'CONNECT_PLUS') return 'Connect Plus';
  if (raw === 'CAPACITY_MAX') return 'Capacity Max';
  if (raw === 'HYTERA_TIER_III') return 'Hytera Tier III';
  if (raw === 'CAPACITY_PLUS') return 'Capacity Plus';
  if (raw === 'TYPE_C' || raw === 'TYPE-C') return 'Type-C';
  if (raw === 'TYPE_D' || raw === 'TYPE-D') return 'Type-D';
  if (raw === 'P25_PHASE_1') return 'Phase 1';
  if (raw === 'P25_PHASE_2') return 'Phase 2';
  const variant = Number(row.variant_code);
  if (protocolName === 'DMR' && variant === 1) return 'Tier III';
  if (protocolName === 'DMR' && variant === 2) return 'Connect Plus';
  if (protocolName === 'DMR' && variant === 3) return 'Capacity Max';
  if (protocolName === 'DMR' && variant === 4) return 'Hytera Tier III';
  if (protocolName === 'DMR' && variant === 5) return 'Capacity Plus';
  if (protocolName === 'NXDN' && variant === 1) return 'Type-C';
  if (protocolName === 'NXDN' && variant === 2) return 'Type-D';
  return variant > 0 ? `Variant ${variant}` : '';
}

function identityDomainLabel(row) {
  const code = Number(row.identity_domain_code || 0);
  if (protocolFamily(row) === 'DMR') {
    return ({ 1: 'Tiny', 2: 'Small', 3: 'Large', 4: 'Huge' })[code] || '';
  }
  if (protocolFamily(row) === 'NXDN') {
    return ({ 1: 'Global', 2: 'Regional', 3: 'Local', 4: 'Type-D', 5: 'Reserved' })[code] || '';
  }
  return '';
}

function trunkedIdentity(row) {
  const values = [];
  const domain = identityDomainLabel(row);
  if (domain && domain !== trunkedVariant(row)) values.push(domain);
  if (row.network_id !== null && row.network_id !== undefined) {
    values.push(`Network ${identifierNumber(row.network_id)}`);
  }
  if (row.system_id !== null && row.system_id !== undefined) {
    values.push(`System ${identifierNumber(row.system_id)}`);
  }
  if (row.site_id !== null && row.site_id !== undefined) values.push(`Site ${identifierNumber(row.site_id)}`);
  if (row.ran !== null && row.ran !== undefined) values.push(`RAN ${identifierNumber(row.ran)}`);
  return values.join(' · ');
}

function badge(label, className = '', title = '') {
  const element = node('span', `badge ${className}`.trim(), label);
  if (title) element.title = title;
  return element;
}

function badgeGroup(values) {
  const badges = (values || []).filter(Boolean);
  if (!badges.length) return fragment();
  const group = node('span', 'badge-group');
  group.append(...badges);
  return group;
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
  return badgeGroup(labels.map(([label, className]) => badge(label, className)));
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

function callsignLink(value) {
  const callsign = String(value || '').trim();
  return callsign ? externalAnchor(callsign,
    `https://www.radioreference.com/db/fcc/callsign/${encodeURIComponent(callsign)}`) : '';
}

function systemLink(row, label = systemValue(row)) {
  if (!isP25(row)) {
    const search = row.configured_system || [
      protocolFamily(row),
      row.network_id == null ? '' : row.network_id,
      row.system_id == null ? '' : row.system_id
    ].filter((value) => value !== '').join(' ');
    return anchor(label, href('systems', { q: search }));
  }
  return anchor(label, href('system', { ...scope(row), tab: 'info' }));
}

function siteLink(row, label = siteValue(row)) {
  return anchor(label, href('site', { guid: row.guid, tab: 'info' }));
}

function neighborSiteLink(row) {
  const label = row.neighbor_name || row.neighbor_site_name || row.neighbor_channel_name || '';
  return label && row.neighbor_guid ?
    anchor(label, href('site', { guid: row.neighbor_guid, tab: 'info' })) : label;
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
  if (observed.has('CWID') || current.has('CWID')) tags.push(channelTagBadge('CWID'));
  return tags.length ? badgeGroup(tags) : badge('Unknown', 'state-historical');
}

function visibleLiveChannelTags(row) {
  const tags = channelTagSet(row.tags);
  const visible = ['CURRENT_CONTROL', 'ALTERNATE_CONTROL', 'VOICE', 'DATA', 'DATA_ANNOUNCED', 'CWID']
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
    upsertRow(data, settings = {}) {
      const key = options.rowKey ? options.rowKey(data) : null;
      if (key !== null && key !== undefined) {
        const existingIndex = dataRows.findIndex((candidate) => {
          const candidateKey = options.rowKey(candidate);
          return candidateKey !== null && candidateKey !== undefined &&
            String(candidateKey) === String(key);
        });
        if (existingIndex >= 0) {
          dataRows[existingIndex] = data;
          renderBody();
          return;
        }
      }
      wrapper.tableController.addRow(data, settings);
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
  values.forEach(([label, value, displayValue]) => {
    const metric = node('div', 'metric');
    metric.append(node('span', '', label),
      node('strong', '', displayValue === undefined ? number(value) : displayValue));
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

const SIGNALING_COUNT_LABELS = new Map(TALKGROUP_SIGNALING_SERIES.map((series) =>
  [series.field, series.label]));

function signalingCounts(row) {
  return [...SIGNALING_COUNT_LABELS]
    .filter(([field]) => Number(row[field] || 0) > 0)
    .map(([field, label]) => [label, Number(row[field])])
    .sort((left, right) => right[1] - left[1]);
}

function signalingActionRows(rows) {
  return (rows || []).filter((row) => {
    const field = `${String(row.action || '').trim().toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')}_count`;
    return SIGNALING_COUNT_LABELS.has(field);
  });
}

function talkgroupSignaling(row) {
  const total = talkgroupSignalingSortValue(row);
  return total > 0 ? number(total) : '—';
}

function talkgroupSignalingSortValue(row) {
  const total = Number(row.signaling_count || 0);
  return Number.isFinite(total) && total > 0 ? total : 0;
}

function withoutGrantActions(rows) {
  return (rows || []).filter((row) => String(row.action || '').toUpperCase() !== 'GRANT');
}

function dashboardChannelKind(row) {
  const value = String(row?.channel_kind || row?.channel_type || '').trim().toUpperCase();
  if (value === 'TRUNKED' || value === 'CONVENTIONAL') return value;
  return '';
}

function dashboardModeLabel(row) {
  const family = protocolFamily(row) || 'Unknown';
  const channelKind = dashboardChannelKind(row);
  if (!['P25', 'DMR', 'NXDN'].includes(family)) return family;
  if (channelKind === 'TRUNKED') return `${family}-T`;
  if (channelKind === 'CONVENTIONAL') return `${family}-C`;
  return family;
}

function dashboardMode(row) {
  const family = protocolFamily(row) || 'Unknown protocol';
  const channelKind = dashboardChannelKind(row);
  const topology = channelKind === 'TRUNKED' ? 'Trunked' :
    channelKind === 'CONVENTIONAL' ? 'Conventional' : 'Unknown topology';
  const value = node('span', 'dashboard-mode', dashboardModeLabel(row));
  value.title = `${family} · ${topology}`;
  value.setAttribute('aria-label', `${family}, ${topology}`);
  return value;
}

function callSourceLabel(row) {
  if (row.source_label) return row.source_label;
  if (row.channel_name) return row.channel_name;
  if (dashboardChannelKind(row) === 'TRUNKED') return siteLabel(row);
  if (row.context_key) return row.context_key;
  if (row.frequency_hz) return `${frequency(row.frequency_hz)} MHz`;
  return 'Unknown receiver';
}

function callSourceLink(row) {
  const label = callSourceLabel(row);
  if (!Number(row.detail_available ?? row.receiver_detail_available)) return label;
  if (dashboardChannelKind(row) === 'TRUNKED' && row.guid) return siteLink(row, label);
  if (dashboardChannelKind(row) === 'CONVENTIONAL' && row.context_key) {
    return anchor(label, href('conventional-detail', { context: row.context_key, tab: 'info' }));
  }
  return label;
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
    values.map((value) => value[configuration.field])
      .filter((value) => !options.preserveNulls || value !== null)
      .map((value) => Number(value || 0))));
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
      count: options.preserveNulls && value[configuration.field] === null ?
        null : Number(value[configuration.field] || 0) }));
    let connected = false;
    const path = points.flatMap((point) => {
      if (point.count === null) {
        connected = false;
        return [];
      }
      const command = connected ? 'L' : 'M';
      connected = true;
      return `${command} ${xFor(point.timestamp).toFixed(2)} ${yFor(point.count).toFixed(2)}`;
    }).join(' ');
    if (path) {
      const line = svgNode('path', { d: path, class: 'activity-line-path' });
      line.style.stroke = configuration.color;
      svg.append(line);
    }
    if (values.length <= 96) {
      points.filter((point) => point.count !== null).forEach((point) => {
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
      y: options.preserveNulls && point[configuration.field] === null ?
        Number.NaN : yFor(point[configuration.field]),
      color: configuration.color
    })),
    tooltipText: (point) => [new Date(point.time_ms).toLocaleString(),
      ...series.map((configuration) =>
        `${configuration.label}: ${options.preserveNulls && point[configuration.field] === null ?
          'Unavailable' : number(point[configuration.field] || 0)}`)]
  });
  return wrapper;
}

function dashboardProtocolKey(row) {
  const value = String(protocolFamily(row) || row?.protocol || '').trim().toUpperCase();
  if (value.startsWith('P25') || value.startsWith('APCO25')) return 'P25';
  if (value.startsWith('DMR')) return 'DMR';
  if (value.startsWith('NXDN')) return 'NXDN';
  if (value === 'NBFM' || value.includes('NARROWBAND FM')) return 'NBFM';
  return value;
}

function dashboardCoverageRows(activity) {
  const coverage = activity?.coverage;
  if (Array.isArray(coverage)) return coverage;
  if (!coverage || typeof coverage !== 'object') return [];
  return Object.entries(coverage).flatMap(([protocolName, value]) => {
    if (Array.isArray(value)) {
      return value.map((entry) => ({ protocol: protocolName, ...entry }));
    }
    if (value && typeof value === 'object') {
      return Object.entries(value).map(([channelKind, status]) => ({
        protocol: protocolName, channel_kind: channelKind,
        status: typeof status === 'object' ? status.status : status
      }));
    }
    return [];
  });
}

function dashboardCoverageStatus(activity, protocolKey, channelKind, metricField = '') {
  const coverage = dashboardCoverageRows(activity).filter((row) =>
    dashboardProtocolKey(row) === protocolKey &&
    (channelKind === 'ALL' || dashboardChannelKind(row) === channelKind));
  if (coverage.length) {
    const statuses = coverage.map((row) =>
      String((metricField && row[metricField]) || row.status || '').toUpperCase());
    if (statuses.every((status) => status === 'COLLECTED')) return 'COLLECTED';
    if (statuses.every((status) => status === 'NOT_COLLECTED')) return 'NOT_COLLECTED';
    if (statuses.some((status) => status === 'COLLECTED' || status === 'PARTIAL')) return 'PARTIAL';
    return 'UNKNOWN';
  }
  const hasRows = (activity?.series || []).some((row) =>
    dashboardProtocolKey(row) === protocolKey &&
    (channelKind === 'ALL' || dashboardChannelKind(row) === channelKind));
  return hasRows ? 'COLLECTED' : 'UNKNOWN';
}

function dashboardMetricCoverageStatus(activity, field) {
  return String(activity?.metricCoverage?.[field] || '').toUpperCase();
}

function dashboardMetricLabel(activity, field, label) {
  const status = dashboardMetricCoverageStatus(activity, field);
  if (status === 'NOT_COLLECTED') return `${label} · Unavailable`;
  return label;
}

function dashboardMetricDisplay(activity, field) {
  return dashboardMetricCoverageStatus(activity, field) === 'NOT_COLLECTED' ? '—' : undefined;
}

function dashboardProtocolConfigurations(activity) {
  const configured = new Map(DASHBOARD_PROTOCOL_SERIES.map((item) => [item.key, item]));
  const observed = [...(activity?.series || []), ...dashboardCoverageRows(activity)];
  observed.forEach((row) => {
    const key = dashboardProtocolKey(row);
    if (!key || configured.has(key)) return;
    configured.set(key, {
      key,
      label: key,
      color: `hsl(${Math.round(configured.size * 137.508) % 360} 58% var(--chart-dynamic-lightness))`
    });
  });
  return [...configured.values()];
}

function dashboardActivitySeries(activity, channelKind, metricField, configurations) {
  const rows = (activity?.series || []).filter((row) =>
    channelKind === 'ALL' || dashboardChannelKind(row) === channelKind);
  const timestamps = new Set(rows.map((row) => Number(row.time_ms)).filter(Number.isFinite));
  const from = Number(activity?.from_ms);
  const to = Number(activity?.to_ms);
  const bucket = Number(activity?.bucket_ms);
  if (Number.isFinite(from) && Number.isFinite(to) && Number.isFinite(bucket) && bucket > 0) {
    for (let timestamp = from, count = 0; timestamp < to && count < 1000; timestamp += bucket, count += 1) {
      timestamps.add(timestamp);
    }
  }
  const values = [...timestamps].sort((left, right) => left - right)
    .map((time_ms) => ({ time_ms }));
  const byTimestamp = new Map(values.map((row) => [row.time_ms, row]));
  rows.forEach((row) => {
    const timestamp = Number(row.time_ms);
    const protocolKey = dashboardProtocolKey(row);
    const target = byTimestamp.get(timestamp);
    if (!target || !protocolKey) return;
    const field = `protocol_${protocolKey.toLowerCase().replace(/[^a-z0-9]+/g, '_')}`;
    const value = row[metricField];
    if (value === null) {
      if (!(field in target)) target[field] = null;
    } else {
      target[field] = Number(target[field] || 0) + Number(value || 0);
    }
  });
  const series = configurations.map((configuration) => ({
    ...configuration,
    field: `protocol_${configuration.key.toLowerCase().replace(/[^a-z0-9]+/g, '_')}`
  }));
  return { values, series };
}

function dashboardCoverage(activity) {
  const coverage = dashboardCoverageRows(activity);
  if (!coverage.length) return null;
  const details = node('details', 'dashboard-coverage');
  details.append(node('summary', '', 'Metric availability'));
  const grid = node('div', 'dashboard-coverage-grid');
  dashboardProtocolConfigurations(activity).forEach((configuration) => {
    const reported = coverage.filter((row) => dashboardProtocolKey(row) === configuration.key);
    if (!reported.length) return;
    const item = node('div', 'dashboard-coverage-protocol');
    item.append(node('strong', '', configuration.label));
    ['TRUNKED', 'CONVENTIONAL'].forEach((channelKind) => {
      const row = reported.find((candidate) => dashboardChannelKind(candidate) === channelKind);
      if (!row) return;
      const status = String(row.status || 'UNKNOWN').toUpperCase();
      const statusLabel = status === 'COLLECTED' ? 'Full 24 hours' :
        status === 'PARTIAL' ? 'Partial history' :
          status === 'NOT_COLLECTED' ? 'Not collected' : 'Unknown';
      const line = node('span', 'dashboard-coverage-entry');
      line.append(node('span', '', channelKind === 'TRUNKED' ? 'Trunked' : 'Conventional'),
        badge(statusLabel, status === 'COLLECTED' ? 'state-current' :
          status === 'NOT_COLLECTED' ? 'state-historical' : 'state-stale'));
      item.append(line);
    });
    grid.append(item);
  });
  details.append(grid);
  return details;
}

function dashboardCallActivityChart(activity) {
  const configurations = dashboardProtocolConfigurations(activity);
  const selectedProtocols = new Set(configurations.map((configuration) => configuration.key));
  let selectedMetric = DASHBOARD_CALL_METRICS[0];
  let selectedChannelKind = DASHBOARD_CHANNEL_KIND_FILTERS[0].value;
  const wrapper = node('div', 'dashboard-call-activity');
  const controls = node('div', 'dashboard-activity-controls');
  const metricControls = node('div', 'dashboard-control-group');
  const channelControls = node('div', 'dashboard-control-group');
  const protocolLegend = node('div', 'activity-series-legend dashboard-protocol-legend');
  const chartHost = node('div', 'dashboard-call-activity-chart-host');
  metricControls.setAttribute('role', 'group');
  metricControls.setAttribute('aria-label', 'Call activity metric');
  channelControls.setAttribute('role', 'group');
  channelControls.setAttribute('aria-label', 'Channel type');
  metricControls.append(node('span', 'dashboard-control-label', 'Metric'));
  channelControls.append(node('span', 'dashboard-control-label', 'Channel type'));
  controls.append(metricControls, channelControls);
  wrapper.append(controls, protocolLegend, chartHost);

  const draw = () => {
    chartHost.replaceChildren();
    protocolLegend.replaceChildren();
    const available = configurations.filter((configuration) =>
      ['COLLECTED', 'PARTIAL'].includes(
        dashboardCoverageStatus(activity, configuration.key, selectedChannelKind, selectedMetric.field)));
    const { values, series } = dashboardActivitySeries(activity, selectedChannelKind,
      selectedMetric.field, configurations);
    configurations.forEach((configuration) => {
      const status = dashboardCoverageStatus(activity, configuration.key, selectedChannelKind,
        selectedMetric.field);
      const collected = status !== 'NOT_COLLECTED' && status !== 'UNKNOWN';
      const seriesConfiguration = series.find((candidate) => candidate.key === configuration.key);
      const total = values.reduce((sum, row) =>
        sum + Number(row[seriesConfiguration.field] || 0), 0);
      const button = node('button', 'activity-series-button secondary');
      button.type = 'button';
      button.disabled = !collected;
      const swatch = node('span', 'activity-series-swatch');
      swatch.style.backgroundColor = configuration.color;
      button.append(swatch, node('span', '', configuration.label));
      if (collected) {
        button.append(node('span', 'activity-series-total', number(total)));
        if (status === 'PARTIAL') button.append(node('span', 'activity-series-status', 'Partial history'));
      }
      else button.append(node('span', 'activity-series-status', 'Unavailable'));
      button.title = status === 'PARTIAL' ? `${configuration.label} is available for part of this range` :
        collected ? `Show or hide ${configuration.label}` :
          `${configuration.label} activity is unavailable for this channel type`;
      const update = () => {
        const active = collected && selectedProtocols.has(configuration.key);
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', String(active));
      };
      button.addEventListener('click', () => {
        if (selectedProtocols.has(configuration.key)) selectedProtocols.delete(configuration.key);
        else selectedProtocols.add(configuration.key);
        update();
        draw();
      });
      update();
      protocolLegend.append(button);
    });
    const visible = series.filter((configuration) =>
      available.some((candidate) => candidate.key === configuration.key) &&
      selectedProtocols.has(configuration.key));
    if (!visible.length) {
      chartHost.append(node('div', 'empty',
        available.length ? 'Select at least one protocol' :
          'Call activity is unavailable for this channel type'));
      return;
    }
    chartHost.append(countTimeSeriesChart(values, visible, {
      from: activity?.from_ms,
      to: activity?.to_ms,
      height: 300,
      margin: { top: 18, right: 20, bottom: 48, left: 55 },
      ariaLabel: `${selectedMetric.label} by protocol for ` +
        `${selectedChannelKind === 'ALL' ? 'all channel types' : selectedChannelKind.toLowerCase()}`,
      emptyMessage: 'No call activity data is available',
      preserveNulls: true
    }));
  };

  DASHBOARD_CALL_METRICS.forEach((metric) => {
    const button = node('button', 'dashboard-filter-button secondary', metric.label);
    button.type = 'button';
    const coverageStatus = dashboardMetricCoverageStatus(activity, metric.field);
    if (coverageStatus === 'NOT_COLLECTED') {
      button.append(node('small', 'dashboard-metric-coverage', 'Unavailable'));
      button.title = `${metric.label} is unavailable`;
      button.disabled = true;
    }
    button.addEventListener('click', () => {
      selectedMetric = metric;
      metricControls.querySelectorAll('button').forEach((candidate) => {
        const active = candidate === button;
        candidate.classList.toggle('active', active);
        candidate.setAttribute('aria-pressed', String(active));
      });
      draw();
    });
    const active = metric === selectedMetric;
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
    metricControls.append(button);
  });
  DASHBOARD_CHANNEL_KIND_FILTERS.forEach((filter) => {
    const button = node('button', 'dashboard-filter-button secondary', filter.label);
    button.type = 'button';
    button.addEventListener('click', () => {
      selectedChannelKind = filter.value;
      channelControls.querySelectorAll('button').forEach((candidate) => {
        const active = candidate === button;
        candidate.classList.toggle('active', active);
        candidate.setAttribute('aria-pressed', String(active));
      });
      draw();
    });
    const active = filter.value === selectedChannelKind;
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
    channelControls.append(button);
  });

  draw();
  const coverage = dashboardCoverage(activity);
  if (coverage) wrapper.append(coverage);
  return wrapper;
}

function talkgroupActivityChart(response, seriesConfigurations, ariaLabel) {
  const values = (response.series || []).map((row) => ({ ...row, time_ms: Number(row.time_ms) }));
  if (!values.length) return node('div', 'empty', 'No activity data is available for this range');

  const totals = response.totals || {};
  const configurations = seriesConfigurations.filter((series) => series.visible ||
    Number(totals[series.field] || 0) > 0);
  if (!configurations.length) {
    return node('div', 'empty', 'No activity of this type is available for this range');
  }
  const selected = new Set(configurations.filter((series) => series.visible).map((series) => series.field));
  if (!selected.size && configurations.length) {
    const largest = configurations.reduce((current, candidate) =>
      Number(totals[candidate.field] || 0) > Number(totals[current.field] || 0) ? candidate : current);
    selected.add(largest.field);
  }

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
      ariaLabel,
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
        color: signal ? 'var(--chart-call)' : 'var(--chart-decode)'
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
  system.append(dashboardReceiverSystemDetails(site));
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

function sortSignalSites(sites) {
  return [...sites].sort((left, right) =>
    siteLabel(left).localeCompare(siteLabel(right), undefined, { sensitivity: 'base' }) ||
      String(left.guid || '').localeCompare(String(right.guid || '')));
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
  currentToolbar.append(summary);
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
      now - Number(site.last_observed_ms || 0) <= SIGNAL_OFFLINE_MILLISECONDS));
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
    if (!sites.length) tiles.append(node('div', 'empty', 'No trunked receivers are currently reporting'));
  };

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
      if (!qualitySite || !Array.isArray(qualitySite.series) || !qualitySite.series.length) {
        host.append(node('div', 'empty',
          `No retained control channel quality samples are available in the selected ${selectedRange} range`));
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
        ['Sent to Streamer', response.totals?.streamed_count],
        ['Encrypted', response.totals?.encrypted_count]
      ], true),
      section('Call Activity', talkgroupActivityChart(response, TALKGROUP_CALL_ACTIVITY_SERIES,
        'Talkgroup calls and call outcomes by time')),
      section('Signaling Observations', talkgroupActivityChart(response, TALKGROUP_SIGNALING_SERIES,
        'Talkgroup signaling observations by time')),
      activityMetricGuide(true));
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
  const block = section('Talkgroup Call Activity', host);
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
    { id: 'talkgroup-name', label: 'Alias', fullLabel: 'Talkgroup Alias', render: (row) => talkgroupAliasLink(row, row.talkgroup_id), className: 'alias-cell', sortValue: aliasLabel },
    { label: 'Group', key: 'alias_group', className: 'alias-cell', sortValue: (row) => row.alias_group || '' },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'recorded', label: 'Rec', fullLabel: 'Recorded', render: (row) => number(row.recorded_count), className: 'numeric', sortValue: (row) => Number(row.recorded_count || 0) },
    { id: 'streamed', label: 'Sent', fullLabel: 'Sent to Streamer', render: (row) => number(row.streamed_count), className: 'numeric', sortValue: (row) => Number(row.streamed_count || 0) },
    { id: 'encrypted', label: 'Enc', fullLabel: 'Encrypted',
      render: (row) => number(row.encrypted_count), className: 'numeric encrypted',
      sortValue: (row) => Number(row.encrypted_count || 0) }
  ];

  const load = async (buttons = rangeControl.buttons, interactive = false) => {
    if (loading && !interactive) return;
    const sequence = ++loadingSequence;
    loading = true;
    if (interactive) {
      buttons.forEach((button) => { button.disabled = true; });
      host.replaceChildren(node('div', 'loading', 'Loading talkgroup call activity'));
    }
    try {
      const response = await api('/api/site/talkgroups', {
        guid: site.guid, range: selectedRange, limit: 20
      });
      if (sequence !== loadingSequence) return;
      host.replaceChildren(table(response.rows || [], columns,
        'No talkgroup activity is available for this range', { type: 'site-top-talkgroups' }));
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
    host.append(node('div', 'empty', 'Talkgroup call activity requires Stats Logging.'));
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

const SITE_CAPABILITY_ALIASES = Object.freeze({
  channels: ['channels'],
  quality: ['quality'],
  'quality-live': ['quality-live', 'quality_live', 'qualityLive'],
  'quality-history': ['quality-history', 'quality_history', 'qualityHistory'],
  neighbors: ['neighbors'],
  'band-plan': ['band-plan', 'band_plan', 'bandPlan', 'bands'],
  patches: ['patches'],
  activity: ['activity', 'detailed-history', 'detailed_history', 'detailedHistory'],
  'top-talkgroups': ['top-talkgroups', 'top_talkgroups', 'topTalkgroups', 'talkgroups', 'site-talkgroups']
});

function normalizedSiteCapability(value) {
  return String(value || '').replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

function siteCapabilityValue(value) {
  if (value && typeof value === 'object') {
    for (const key of ['available', 'enabled', 'supported']) {
      if (Object.prototype.hasOwnProperty.call(value, key)) return Boolean(value[key]);
    }
  }
  return Boolean(value);
}

function explicitSiteCapability(site, capability) {
  const capabilities = site?.capabilities;
  if (!capabilities) return undefined;
  const aliases = new Set((SITE_CAPABILITY_ALIASES[capability] || [capability])
    .map(normalizedSiteCapability));
  if (Array.isArray(capabilities)) {
    return capabilities.some((value) => aliases.has(normalizedSiteCapability(value)));
  }
  if (typeof capabilities === 'string') {
    return capabilities.split(',').some((value) => aliases.has(normalizedSiteCapability(value)));
  }
  if (typeof capabilities !== 'object') return undefined;
  const entry = Object.entries(capabilities)
    .find(([key]) => aliases.has(normalizedSiteCapability(key)));
  return entry ? siteCapabilityValue(entry[1]) : undefined;
}

function siteCapability(site, capability) {
  const explicit = explicitSiteCapability(site, capability);
  if (explicit !== undefined) return explicit;
  return ['channels', 'quality', 'quality-live', 'quality-history', 'neighbors'].includes(capability) ||
    isP25(site);
}

function siteTabItems(site) {
  const values = { guid: site.guid };
  const items = [
    { id: 'info', label: 'Info', href: href('site', { ...values, tab: 'info' }) }
  ];
  if (siteCapability(site, 'channels')) {
    items.push({ id: 'channels', label: 'Channels', href: href('site', { ...values, tab: 'channels' }) });
  }
  const hasQuality = siteCapability(site, 'quality') &&
    (siteCapability(site, 'quality-live') || siteCapability(site, 'quality-history'));
  if (hasQuality) {
    items.push({ id: 'quality', label: 'Quality', href: href('site', { ...values, tab: 'quality' }) });
  }
  if (siteCapability(site, 'neighbors')) {
    items.push({ id: 'neighbors', label: 'Neighbors', href: href('site', { ...values, tab: 'neighbors' }) });
  }
  if (siteCapability(site, 'band-plan')) {
    items.push({ id: 'band-plan', label: 'Band Plan', href: href('site', { ...values, tab: 'band-plan' }) });
  }
  if (siteCapability(site, 'patches')) {
    items.push({ id: 'patches', label: 'Patches', href: href('site', { ...values, tab: 'patches' }) });
  }
  if (siteCapability(site, 'activity')) {
    items.push({ id: 'activity', label: 'Activity', href: href('site', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' }
    );
  }
  return items;
}

function siteTabs(site, active) {
  return tabs(siteTabItems(site), active);
}

function trunkedChannelUse(value) {
  const flags = Number(value || 0);
  const values = [];
  if (flags & 1) values.push(badge('Current CC', 'state-current'));
  if (flags & 2) values.push(badge('Alt CC', 'state-current'));
  if (flags & 4) values.push(badge('Traffic'));
  return badgeGroup(values);
}

function trunkedChannelSources(value) {
  const flags = Number(value || 0);
  const values = [];
  if (flags & 8) values.push(badge('OTA Seen', '', 'This channel and timeslot were decoded over the air'));
  if (flags & 16) values.push(badge('LCN Map', '', 'Frequency resolved from the configured LCN-to-frequency map'));
  if (flags & 32) values.push(badge('OTA Freq', '', 'Absolute frequency was broadcast over the air'));
  return badgeGroup(values);
}

function trunkedNeighborStatus(value) {
  const flags = Number(value || 0);
  const values = [];
  if (flags & 1) values.push(badge('Linked', 'state-current'));
  if (flags & 2) values.push(badge('Isolated', 'state-stale'));
  return badgeGroup(values);
}

function dmrBrand(value) {
  return ({ 1: 'Tier III', 2: 'Motorola Connect+', 3: 'Motorola Capacity Max',
    4: 'Hytera Tier III', 5: 'Motorola Capacity+' })[Number(value)] || '';
}

function dmrModel(value) {
  return ({ 1: 'Tiny', 2: 'Small', 3: 'Large', 4: 'Huge' })[Number(value)] || '';
}

function dmrMode(value) {
  return ({ 1: 'Open System', 2: 'Advantage' })[Number(value)] || '';
}

function dmrChannelType(value) {
  return ({ 1: 'Control', 2: 'Traffic' })[Number(value)] || '';
}

function nxdnRepeaterMode(value) {
  return ({ 1: 'Idle', 2: 'Free', 3: 'Halted / CWID' })[Number(value)] || '';
}

function liveSiteReceiverSection(site) {
  const connection = badge('Waiting', 'state-stale');
  const signal = node('span', '', '—');
  const decode = node('span', '', '—');
  const observed = node('span', '', 'No live metadata received');
  const protocolVariant = node('span', '',
    [protocolFamily(site), trunkedVariant(site)].filter(Boolean).join(' · '));
  const details = keyValues([
    ['Connection', connection], ['Protocol', protocolVariant], ['Signal', signal], ['Decode', decode],
    ['Live Observation', observed]
  ]);
  let current = null;
  let reconnecting = false;
  const refresh = () => {
    const live = current && Date.now() - Number(current.live_received_at_ms || current.observed_at_ms || 0) <=
      SITE_METADATA_OFFLINE_MILLISECONDS;
    connection.textContent = reconnecting ? 'Reconnecting' : (live ? 'Live' : (current ? 'Stale' : 'Waiting'));
    connection.className = `badge ${live && !reconnecting ? 'state-current' : 'state-stale'}`;
    const qualityLive = live && Date.now() -
      Number(current?.quality_received_at_ms || current?.quality_observed_at_ms || 0) <=
      SIGNAL_OFFLINE_MILLISECONDS;
    signal.textContent = qualityLive ? signalNumber(current.signal_dbfs) : '—';
    decode.textContent = qualityLive ? percentNumber(current.decode_health_pct) : '—';
  };
  const apply = (value) => {
    if (!value || value.guid !== site.guid) return;
    current = value;
    protocolVariant.textContent = [
      protocolFamily(value) || protocolFamily(site),
      trunkedVariant(value) || trunkedVariant(site)
    ].filter(Boolean).join(' · ');
    observed.replaceChildren(valueNode(dateTime(value.observed_at_ms)));
    refresh();
  };
  const source = liveConnection('/live/sites');
  source.addEventListener('snapshot', (event) => {
    const snapshot = JSON.parse(event.data);
    current = null;
    (snapshot.sites || []).forEach(apply);
    if (!current) observed.textContent = 'No live metadata received';
    refresh();
  });
  source.addEventListener('site_metadata', (event) => apply(JSON.parse(event.data)));
  source.addEventListener('site_removed', (event) => {
    if (JSON.parse(event.data)?.guid === site.guid) {
      current = null;
      observed.textContent = 'No live metadata received';
      refresh();
    }
  });
  source.onopen = () => {
    reconnecting = false;
    refresh();
  };
  source.onerror = () => {
    reconnecting = true;
    refresh();
  };
  pageInterval(refresh, 5_000);
  return section('Live Receiver', details);
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

function dashboardReceiverSystem(row) {
  if (dashboardChannelKind(row) === 'CONVENTIONAL') return '';
  return isP25(row) ? systemLabel(row) : trunkedSystemLabel(row);
}

function dashboardReceiverRfss(row) {
  return isP25(row) ? hex(row.rfss, 2) : '';
}

function dashboardReceiverSiteId(row) {
  return isP25(row) ? hex(row.site, 2) : identifierNumber(row.site_id);
}

function dashboardReceiverNac(row) {
  return isP25(row) ? hex(row.nac, 3) : '';
}

function dashboardReceiverIdentifiers(row) {
  const values = [];
  const rfss = dashboardReceiverRfss(row);
  const site = dashboardReceiverSiteId(row);
  const nac = dashboardReceiverNac(row);
  if (rfss) values.push(`RFSS ${rfss}`);
  if (site) values.push(`Site ID ${site}`);
  if (nac) values.push(`NAC ${nac}`);
  return values.join(' · ');
}

function dashboardReceiverSystemDetails(row) {
  return [dashboardReceiverSystem(row), dashboardReceiverIdentifiers(row)].filter(Boolean).join(' · ');
}

const dashboardHealthColumns = [
  { id: 'name', label: 'Site / Channel', render: callSourceLink, className: 'alias-cell',
    sortValue: callSourceLabel },
  { id: 'system', label: 'System', render: dashboardReceiverSystem,
    sortValue: dashboardReceiverSystem },
  { id: 'mode', label: 'Mode', fullLabel: 'Protocol and Topology',
    render: dashboardMode, sortValue: dashboardModeLabel },
  { id: 'rfss', label: 'RFSS', render: dashboardReceiverRfss, className: 'numeric',
    sortValue: (row) => Number(row.rfss ?? -1) },
  { id: 'site-id', label: 'Site ID', render: dashboardReceiverSiteId, className: 'numeric',
    sortValue: (row) => Number((isP25(row) ? row.site : row.site_id) ?? -1) },
  { id: 'nac', label: 'NAC', render: dashboardReceiverNac, className: 'numeric',
    sortValue: (row) => Number(row.nac ?? -1) },
  { id: 'frequency', label: 'MHz', fullLabel: 'Current or Primary Frequency MHz',
    render: (row) => frequency(row.current_control_hz || row.primary_frequency_hz),
    className: 'numeric', sortValue: (row) =>
      Number(row.current_control_hz || row.primary_frequency_hz || 0) },
  { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
    render: (row) => dateTime(row.last_seen_ms),
    sortValue: (row) => Number(row.last_seen_ms || 0) }
];

function dashboardIdentityId(row) {
  return Number(row.identity_kind_code) === 0 || Number(row.identity_id) <= 0 ? '—' :
    identifierNumber(row.identity_id);
}

function dashboardIdentityLink(row, label = dashboardIdentityId(row)) {
  if (!Number(row.identity_detail_available)) return label;
  if (row.identity_detail_view === 'talkgroup') {
    return talkgroupLink(row, row.identity_id, label);
  }
  if (row.identity_detail_view === 'radio') {
    return radioLink(row, row.identity_id, label);
  }
  if (row.identity_detail_view === 'conventional-talkgroups' && row.context_key) {
    return anchor(label, href('conventional-detail', { context: row.context_key, tab: 'talkgroups' }));
  }
  if (row.identity_detail_view === 'conventional-radios' && row.context_key) {
    return anchor(label, href('conventional-detail', { context: row.context_key, tab: 'radios' }));
  }
  return label;
}

function dashboardIdentity(row) {
  const identity = node('span', 'dashboard-identity');
  const id = dashboardIdentityId(row);
  const kind = String(row.identity_kind || '').trim();
  const configuredAlias = String(row.alias_name || '').trim();
  const talkerAlias = String(row.last_talker_alias || '').trim();
  const hasId = id !== '—';
  const compactKind = ({
    Talkgroup: 'TG',
    Radio: 'Radio',
    'Patch Group': 'Patch',
    'Channel / Unknown': 'Channel'
  })[kind] || kind;
  const compactIdentity = hasId ? `${compactKind || 'ID'} ${id}` : kind;
  const primaryLabel = configuredAlias || talkerAlias || compactIdentity || 'Unknown identity';
  const primary = node('span', 'dashboard-identity-primary');
  primary.append(valueNode(dashboardIdentityLink(row, primaryLabel)));
  identity.append(primary);
  const details = [];
  if (configuredAlias && talkerAlias &&
      configuredAlias.toLocaleLowerCase() !== talkerAlias.toLocaleLowerCase()) {
    details.push(`OTA ${talkerAlias}`);
  }
  if ((configuredAlias || talkerAlias) && hasId) details.push(compactIdentity);
  if (details.length) identity.append(node('small', 'dashboard-identity-context', details.join(' · ')));
  return identity;
}

const dashboardCallSourceColumns = [
  { id: 'receiver', label: 'Site / Channel', render: callSourceLink, className: 'alias-cell',
    sortValue: callSourceLabel },
  { id: 'mode', label: 'Mode', fullLabel: 'Protocol and Topology',
    render: dashboardMode, sortValue: dashboardModeLabel },
  { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric',
    sortValue: (row) => Number(row.call_count || 0) },
  { id: 'recorded', label: 'Recorded', render: (row) => number(row.recorded_count),
    className: 'numeric', sortValue: (row) => Number(row.recorded_count || 0) },
  { id: 'streamed', label: 'Sent', fullLabel: 'Sent to Streamer',
    render: (row) => number(row.streamed_count), className: 'numeric',
    sortValue: (row) => Number(row.streamed_count || 0) }
];

function dashboardIdentityColumns(identityLabel) {
  return [
    { id: 'identity', label: identityLabel, render: dashboardIdentity, className: 'alias-cell',
      sortValue: (row) =>
        `${row.alias_name || row.last_talker_alias || ''}\u0000${dashboardIdentityId(row)}` },
    ...dashboardCallSourceColumns
  ];
}

function dashboardSummarySection(title, values) {
  const block = section(title, metrics(values, true));
  block.classList.add('dashboard-summary-section');
  return block;
}

const talkgroupColumns = [
  { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sort: 'talkgroup', sortValue: (row) => Number(row.talkgroup_id) },
  { id: 'talkgroup-name', label: 'Alias', fullLabel: 'Talkgroup Alias', render: (row) => talkgroupAliasLink(row, row.talkgroup_id), className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
  { id: 'talkgroup-description', label: 'Description', key: 'alias_description', className: 'alias-cell' },
  { label: 'Group', key: 'alias_group', className: 'alias-cell', sort: 'group' },
  { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
  { id: 'recorded', label: 'Rec', fullLabel: 'Recorded', render: (row) => number(row.recorded_count), className: 'numeric', sort: 'recorded', sortValue: (row) => Number(row.recorded_count || 0) },
  { id: 'streamed', label: 'Sent', fullLabel: 'Sent to Streamer', render: (row) => number(row.streamed_count), className: 'numeric', sort: 'streamed', sortValue: (row) => Number(row.streamed_count || 0) },
  { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
  { id: 'signaling', label: 'Signaling', fullLabel: 'Signaling observations',
    render: talkgroupSignaling, className: 'numeric', sort: 'signaling',
    sortValue: talkgroupSignalingSortValue },
  { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

const radioColumns = [
  { id: 'radio', label: 'ID', render: (row) => radioLink(row), className: 'numeric', sort: 'id', sortValue: (row) => Number(row.radio_id) },
  { id: 'alias', label: 'Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
  { label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
  { id: 'talkgroup-id', label: 'Affil TG', fullLabel: 'Affiliated Talkgroup ID', render: (row) => talkgroupLink(row, row.affiliated_talkgroup_id), className: 'numeric', sort: 'affiliated_talkgroup', sortValue: (row) => Number(row.affiliated_talkgroup_id) },
  { id: 'talkgroup-name', label: 'TG Alias', fullLabel: 'Talkgroup Alias', render: (row) => talkgroupAliasLink(row,
    row.affiliated_talkgroup_id, 'affiliated_talkgroup_alias_'), className: 'alias-cell', sortValue: (row) => row.affiliated_talkgroup_alias_name || '' },
  { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
  { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
  { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

async function renderDashboard() {
  const dashboard = await api('/api/dashboard');
  const counts = dashboard.counts || {};
  const callActivity = dashboard.callActivity || {};
  const callTotals = callActivity.totals || {};
  const requestedTab = route.get('tab') || 'calls';
  const tab = requestedTab === 'health' ? 'health' : 'calls';
  content.append(pageHeader('Dashboard', dashboard.lastSeenMs ?
    fragment('Last activity ', dateTime(dashboard.lastSeenMs)) : 'Last activity not recorded'),
  tabs([
    { id: 'calls', label: 'Calls', href: href('dashboard', { tab: 'calls' }) },
    { id: 'health', label: 'Health', href: href('dashboard', { tab: 'health' }) }
  ], tab));

  if (tab === 'health') {
    content.append(await signalHealthSection());
    content.append(dashboardSummarySection('Monitored Coverage', [
      ['Trunked Systems', counts.trunked_systems],
      ['Trunked Sites', counts.trunked_sites],
      ['Conventional Channels', counts.conventional_channels]
    ]));
    content.append(section('Recent Sites / Channels', table(dashboard.recentReceivers || [],
      dashboardHealthColumns, 'No sites or channels recorded', { type: 'dashboard-receivers' })));
    return;
  }

  content.append(dashboardSummarySection('Call Totals · Last 24 Hours', [
    [dashboardMetricLabel(callActivity, 'call_count', 'Calls'), callTotals.call_count,
      dashboardMetricDisplay(callActivity, 'call_count')],
    [dashboardMetricLabel(callActivity, 'recorded_count', 'Recorded'), callTotals.recorded_count,
      dashboardMetricDisplay(callActivity, 'recorded_count')],
    [dashboardMetricLabel(callActivity, 'streamed_count', 'Sent'), callTotals.streamed_count,
      dashboardMetricDisplay(callActivity, 'streamed_count')]
  ]));
  content.append(section('Call Activity · Last 24 Hours', dashboardCallActivityChart(callActivity)));
  const sourceRows = Array.isArray(dashboard.sourceActivity24h) ? dashboard.sourceActivity24h :
    dashboard.sourceActivity24h?.rows || [];
  content.append(section('Calls by Site / Channel · Last 24 Hours',
    table(sourceRows, dashboardCallSourceColumns, 'No call activity recorded',
      { type: 'dashboard-call-sources' })));
  const destinations = section('Top Destinations · Last 24 Hours',
    table(dashboard.topDestinations || [], dashboardIdentityColumns('Destination'),
      'No call destinations recorded', { type: 'dashboard-destinations' }));
  const sources = section('Top Sources · Last 24 Hours',
    table(dashboard.topSources || [], dashboardIdentityColumns('Source'),
      'No call sources recorded', { type: 'dashboard-sources' }));
  content.append(node('div', 'split dashboard-identity-split'));
  content.lastChild.append(destinations, sources);
}

function liveSystemsSection() {
  const tables = new Map();
  const tabNodes = new Map();
  const rowNodes = new Map();
  const decodeDisplay = serviceStatus?.decodeDisplay || { showControl: true, showVoice: true, mode: 'percentage' };
  const compactQualityCount = (value) => {
    const count = Number(value || 0);
    if (count >= 1000000) return `${(count / 1000000).toFixed(1)}m`;
    if (count >= 1000) return `${(count / 1000).toFixed(1)}k`;
    return String(count);
  };
  const voiceQualityReady = (row) => Number(row.vc_decoded_frames || 0) +
    Number(row.vc_repeated_frames || 0) + Number(row.vc_concealed_frames || 0) >=
    VOICE_QUALITY_WARMUP_FRAMES;
  const decodeQualityValues = (row) => {
    const values = [];
    if (decodeDisplay.showControl && row.decode_health_pct != null) {
      values.push(Number(row.decode_health_pct));
    }
    if (decodeDisplay.showVoice && row.vc_quality_pct != null && voiceQualityReady(row)) {
      values.push(Number(row.vc_quality_pct));
    }
    return values.filter(Number.isFinite);
  };
  const decodeQualityText = (row) => {
    const values = [];
    const detailed = decodeDisplay.mode === 'detailed';
    if (decodeDisplay.showControl && row.decode_health_pct != null) {
      let value = `CC ${Number(row.decode_health_pct).toFixed(1)}%`;
      if (detailed) {
        value += ` · ${Number(row.cc_valid_frames || 0)}/${Number(row.cc_invalid_frames || 0)}/` +
          `${Number(row.cc_corrected_bits || 0)}/${Number(row.cc_sync_loss_bits || 0)}/` +
          `${Number(row.cc_dropped_bits || 0)}`;
      }
      values.push(value);
    }
    if (decodeDisplay.showVoice && row.vc_quality_pct != null) {
      if (!voiceQualityReady(row)) {
        values.push('VC -');
        return values.join(' · ');
      }
      let value = `VC ${Number(row.vc_quality_pct).toFixed(1)}%`;
      if (detailed) {
        value += ` · ${Number(row.vc_decoded_frames || 0)}/${Number(row.vc_repeated_frames || 0)}/` +
          `${Number(row.vc_concealed_frames || 0)}/${Number(row.vc_missing_frames || 0)} · ` +
          `${Number(row.vc_fec_errors || 0)}/${compactQualityCount(row.vc_fec_protected_bits)}`;
      }
      values.push(value);
    }
    return values.join(' · ');
  };
  const decodeQualityTitle = (row) => {
    const values = [];
    if (decodeDisplay.showControl && row.decode_health_pct != null) {
      values.push('CC uses a rolling 30-second control-channel window. Detail order: valid frames / ' +
        'invalid frames / corrected bits / sync-loss bits / dropped bits.');
    }
    if (decodeDisplay.showVoice && row.vc_quality_pct != null) {
      values.push('VC uses 20 ms voice frames. Detail order: decoded / repeated / concealed / missing frames · ' +
        'FEC detected corrections / inspected protected bits.');
    }
    return values.join('\n');
  };
  const columns = [
    { id: 'status', label: 'Status', width: 145, sortValue: (row) => row.status || '' },
    { id: 'tags', label: 'Tags', width: 180, sortValue: channelTagText },
    { id: 'channel-lcn', label: 'LCN', width: 130, sortValue: (row) =>
      channelTagSet(row.tags).has('CONVENTIONAL') ? (row.channel_name || '') : (row.lcn || '') },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz', width: 100, sortValue: (row) => Number(row.frequency_hz || 0) },
    { id: 'signal', label: 'dBFS', fullLabel: 'Signal dBFS', width: 90, sortValue: (row) => Number(row.signal_dbfs ?? -999) },
    { id: 'decode-health', label: 'Decode %', width: decodeDisplay.mode === 'detailed' ? 260 : 120,
      sortValue: (row) => {
        const values = decodeQualityValues(row);
        return values.length ? Math.min(...values) : -1;
      } },
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
    cellText(cells[5], decodeQualityText(row));
    cellText(cells[6], row.source_alias_display || row.source_alias ||
      (row.talker_alias ? `TA: ${row.talker_alias}` : ''));
    cellText(cells[7], row.source_id);
    cellText(cells[8], row.target_alias);
    cellText(cells[9], row.target_id);
    cellText(cells[10], decoderLabel(row.decoder, true));
    cells[1].title = channelTagTitle(row);
    cells[2].title = conventional ? (row.channel_name || '') : '';
    cells[0].className = `activity-status state-${String(row.status || 'idle').toLowerCase()}`;
    cells[1].className = '';
    const tags = channelTagSet(row.tags);
    cells[2].className = tags.has('CURRENT_CONTROL') ? 'control-current' :
      (tags.has('ALTERNATE_CONTROL') ? 'control-alternate' : '');
    cells[3].className = cells[2].className;
    cells[4].className = cells[2].className;
    cells[5].title = decodeQualityTitle(row);
    cells[10].title = decoderLabel(row.decoder);
    const decodeValues = decodeQualityValues(row);
    const decodePercent = decodeValues.length ? Math.min(...decodeValues) : null;
    cells[5].className = decodePercent == null ? '' :
      (decodePercent >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'quality-good' :
        (decodePercent >= DECODE_DEGRADED_MINIMUM_PERCENT ?
          'quality-warn' : 'quality-bad'));
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
      const quality = node('span', 'systems-tab-quality');
      for (let index = 0; index < 4; index += 1) quality.append(node('span'));
      tab.append(quality, node('span', 'systems-tab-label'));
      tab.addEventListener('click', () => showTable(value.table_id));
      tabNodes.set(value.table_id, tab);
      tabBar.append(tab);
    }
    const label = value.title || value.channel_name || value.table_id;
    tab.querySelector('.systems-tab-label').textContent = label;
    const quality = tab.querySelector('.systems-tab-quality');
    const currentControl = (value.rows || []).find((row) =>
      channelTagSet(row.tags).has('CURRENT_CONTROL') && Number.isFinite(optionalNumber(row.decode_health_pct)));
    const qualityObservedAt = Number(currentControl?.quality_observed_at_ms || 0);
    const decodeQuality = currentControl && value.control_active && qualityObservedAt > 0 &&
      Date.now() - qualityObservedAt <= SIGNAL_OFFLINE_MILLISECONDS ?
      Math.max(0, Math.min(100, Number(currentControl.decode_health_pct))) : null;
    if (value.table_id === 'conventional') {
      quality.className = 'systems-tab-quality quality-neutral';
      tab.title = label;
      tab.setAttribute('aria-label', label);
    } else if (decodeQuality === null) {
      quality.className = 'systems-tab-quality quality-unavailable';
      tab.title = `${label} · Decode quality unavailable`;
      tab.setAttribute('aria-label', `${label}, decode quality unavailable`);
    } else {
      const level = decodeQuality === 0 ? 0 : Math.min(4, Math.ceil(decodeQuality / 25));
      const state = decodeQuality >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'healthy' :
        (decodeQuality >= DECODE_DEGRADED_MINIMUM_PERCENT ? 'degraded' : 'poor');
      quality.className = `systems-tab-quality quality-${state} quality-level-${level}`;
      const qualityLabel = `${decodeQuality.toFixed(1)}% decode quality`;
      tab.title = `${label} · ${qualityLabel}`;
      tab.setAttribute('aria-label', `${label}, ${qualityLabel}`);
    }
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
  const page = await api('/api/system-directory', pageParameters({ limit: 25 }));
  content.append(pageHeader('Systems & Sites',
    'P25 systems plus DMR and NXDN receiver channels, each with its observed site'));
  const rows = [];
  (page.rows || []).forEach((system) => {
    rows.push({ ...system, directory_type: 'system' });
    (system.children || []).forEach((site) => rows.push({ ...site, directory_type: 'site' }));
  });
  const columns = [
    { id: 'directory-name', label: 'System / Site', width: 230, className: 'directory-name', render: (row) => {
      const wrapper = node('div', 'directory-entity');
      if (row.directory_type === 'system') {
        const label = row.configured_system || `${protocolFamily(row)} System`;
        const heading = node('strong');
        heading.append(isP25(row) ? systemLink(row, label) : label);
        wrapper.append(heading);
      } else {
        wrapper.append(node('span', 'directory-branch', '↳'),
          siteLink(row, siteValue(row)));
      }
      return wrapper;
    } },
    { id: 'protocol', label: 'Protocol', render: (row) => protocolFamily(row) },
    { label: 'Variant / Model', render: (row) => isP25(row) ? '' :
      [trunkedVariant(row), identityDomainLabel(row)].filter(Boolean).join(' · ') },
    { id: 'wacn', label: 'WACN / Net', fullLabel: 'WACN or Network', className: 'numeric', render: (row) =>
      isP25(row) ? (row.directory_type === 'system' ? hex(row.wacn, 5) : '') :
        (row.directory_type === 'system' ? identifierNumber(row.network_id) : '') },
    { id: 'system', label: 'Sys ID', fullLabel: 'System ID', className: 'numeric', render: (row) => {
      if (row.directory_type !== 'system') return '';
      return isP25(row) ? systemLink(row, hex(row.system_id, 3)) : identifierNumber(row.system_id);
    } },
    { id: 'rfss', label: 'RFSS / RAN', className: 'numeric', render: (row) =>
      row.directory_type === 'site' ? (isP25(row) ? hex(row.rfss, 2) : identifierNumber(row.ran)) : '' },
    { id: 'site', label: 'Site', className: 'numeric', render: (row) =>
      row.directory_type === 'site' ? (isP25(row) ? hex(row.site, 2) : identifierNumber(row.site_id)) : '' },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz', className: 'numeric',
      render: (row) => row.directory_type === 'site' ? frequency(row.current_control_hz) : '' },
    { id: 'count', label: 'Sites / Ch', fullLabel: 'Sites or Channels', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? `${number(row.sites)} ${Number(row.sites) === 1 ? 'site' : 'sites'}` :
        `${number(row.channels)} ch` },
    { id: 'talkgroups', label: 'TGs', fullLabel: 'Talkgroups', className: 'numeric', render: (row) =>
      row.directory_type === 'system' && isP25(row) ? number(row.talkgroups) : '' },
    { id: 'radios', label: 'Radios', className: 'numeric', render: (row) =>
      row.directory_type === 'system' && isP25(row) ? number(row.radios) : '' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
  ];
  content.append(searchBar('Search protocol, system, site name, or GUID'));
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
      { id: 'talkgroup-name', label: 'TG Alias', fullLabel: 'Talkgroup Alias', render: (row) => talkgroupAliasLink(row, row.last_talkgroup_id, 'talkgroup_alias_'), className: 'alias-cell', sort: 'last_talkgroup_name', sortValue: (row) => row.talkgroup_alias_name || '' },
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
    infoColumn.append(section('Directory', metrics([
      ['Known Sites', system.sites],
      ['Known Talkgroups', system.talkgroups],
      ['Known Radios', system.radios]
    ], true)), section('Retained Call Activity', metrics([
      ['Calls', system.activity_retained_calls],
      ['Recorded', system.activity_recorded],
      ['Sent to Streamer', system.activity_streamed],
      ['Encrypted', system.activity_encrypted]
    ], true)), section('Current State', metrics([
      ['Currently Affiliated', system.affiliations]
    ], true)), section('System Info', keyValues([
      ['System', systemInfoValue(system)],
      ['First Seen', dateTime(system.first_seen_ms)], ['Last Seen', dateTime(system.last_seen_ms)]
    ])), section('Retained Signaling Observations', fragment(table(
      signalingActionRows(response.actionCounts), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No signaling observations recorded', { type: 'action-counts' }), activityMetricGuide())));

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
    infoColumn.append(section('Identity', keyValues([
      ['System', systemLink(talkgroup, systemInfoValue(talkgroup))],
      ['Talkgroup ID', id], ['Alias', aliasLabel(talkgroup)],
      ['Description', talkgroup.alias_description],
      ['Group', talkgroup.alias_group]
    ])), section('Collected Call Activity', metrics([
      ['Calls', talkgroup.call_count],
      ['Recorded', talkgroup.recorded_count],
      ['Sent to Streamer', talkgroup.streamed_count],
      ['Encrypted', talkgroup.encrypted_count]
    ], true)), section('Relationships', metrics([
      ['Observed Radios', talkgroup.radios]
    ], true)), section('Current State', keyValues([
      ['Currently Affiliated', affiliationLink]
    ])), section('Last-known Facts', keyValues([
      ['Last Source', radioLink(talkgroup, talkgroup.last_source_radio_id)],
      ['Last Encryption Algorithm', encryptionAlgorithmInfoValue(talkgroup.last_encryption_algorithm_name,
        talkgroup.last_encryption_algorithm_id)],
      ['Last Encryption Key ID', hexDecimalPair(talkgroup.last_encryption_key_id)]
    ])), section('Observed Times', keyValues([
      ['First Observed', dateTime(talkgroup.first_seen_ms)],
      ['Last Observed', dateTime(talkgroup.last_seen_ms)]
    ])), section('Collected Signaling Observations', table(
      signalingCounts(talkgroup).map(([action, count]) => ({ action, count })), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No signaling observations recorded', { type: 'action-counts' })));
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
      { id: 'talkgroup-name', label: 'TG Alias', fullLabel: 'Talkgroup Alias', render: (row) => talkgroupAliasLink(row,
        row.talkgroup_id, 'talkgroup_alias_'), className: 'alias-cell', sort: 'talkgroup_alias', sortValue: (row) => row.talkgroup_alias_name || '' },
      { id: 'talkgroup-description', label: 'Description', key: 'talkgroup_alias_description',
        className: 'alias-cell' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    content.append(pagedSection('Talkgroups', relationships, columns, null, 'radio-talkgroups'));
  } else if (tab === 'activity') {
    await renderActivity({ ...systemScope, radio_id: id });
  } else {
    const infoColumn = node('div', 'entity-info-column entity-info-standalone');
    infoColumn.append(section('Identity', keyValues([
      ['System', systemLink(radio, systemInfoValue(radio))],
      ['Radio ID', id],
      ['Alias', aliasLabel(radio)],
      ['Talker Alias', radio.last_talker_alias]
    ])), section('Collected Call Activity', metrics([
      ['Calls', radio.call_count],
      ['Encrypted', radio.encrypted_count]
    ], true)), section('Current Affiliation', keyValues([
      ['Talkgroup ID', talkgroupLink(radio, radio.affiliated_talkgroup_id)],
      ['Talkgroup Alias', talkgroupAliasLink(radio, radio.affiliated_talkgroup_id,
        'affiliated_talkgroup_alias_')],
      ['Updated', dateTime(radio.affiliation_updated_at_ms)]
    ])), section('Relationships', metrics([
      ['Observed Talkgroups', radio.talkgroups]
    ])), section('Last-known Facts', keyValues([
      ['Last Encryption Algorithm', encryptionAlgorithmInfoValue(radio.last_encryption_algorithm_name,
        radio.last_encryption_algorithm_id)],
      ['Last Encryption Key ID', hexDecimalPair(radio.last_encryption_key_id)]
    ])), section('Observed Times', keyValues([
      ['Talker Alias Observed', dateTime(radio.last_talker_alias_seen_ms)],
      ['First Observed', dateTime(radio.first_seen_ms)],
      ['Last Observed', dateTime(radio.last_seen_ms)]
    ])), section('Collected Signaling Observations', fragment(table(
      signalingCounts(radio).map(([action, count]) => ({ action, count })), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No signaling observations recorded', { type: 'action-counts' }), activityMetricGuide())));
    content.append(infoColumn);
  }
}

function siteIdentity(site) {
  if (!isP25(site)) return trunkedIdentity(site);
  return [
    site.wacn == null ? '' : `WACN ${hex(site.wacn, 5)}`,
    site.system_id == null ? '' : `System ${hex(site.system_id, 3)}`,
    site.rfss == null ? '' : `RFSS ${hex(site.rfss, 2)}`,
    site.site == null ? '' : `Site ${hex(site.site, 2)}`
  ].filter(Boolean).join(' · ');
}

function p25SiteDetailRows(site) {
  return [
    ['Callsign', callsignLink(site.callsign)], ['WACN', hexDecimalPair(site.wacn, 5)],
    ['SysID', hexDecimalPair(site.system_id, 3)], ['NAC', hexDecimalPair(site.nac, 3)],
    ['RFSS', hexDecimalPair(site.rfss, 2)], ['Site', hexDecimalPair(site.site, 2)],
    ['Local Registration Area', hexDecimalPair(site.lra, 2)],
    ['Manufacturer', site.mfid_display],
    ['Broadcast Clock', dateTime(site.broadcast_clock_ms)],
    ['Data', yesNoKnown(site.data_service)], ['Data Access', site.data_access],
    ['Working Unit ID Lease Time', site.wuid_lease_minutes == null ? '' :
      `${number(site.wuid_lease_minutes)} minutes`],
    ['Unit registration over control channel', yesNoKnown(site.registration_service)],
    ['TDMA', yesNoKnown(site.tdma)], ['u-Slots', site.micro_slots == null ? '' : number(site.micro_slots)],
    ['Voice', yesNoKnown(site.voice_service)]
  ];
}

function dmrSiteDetailRows(site) {
  return [
    ['Variant', trunkedVariant(site)], ['Network', identifierNumber(site.network_id)],
    ['System', identifierNumber(site.system_id)], ['Site', identifierNumber(site.site_id)],
    ['RAN', identifierNumber(site.ran)], ['Brand', dmrBrand(site.brand_code)],
    ['Model', dmrModel(site.model_code)], ['Mode', dmrMode(site.mode_code)],
    ['Channel Type', dmrChannelType(site.channel_type_code)],
    ['Color Code TS1', identifierNumber(site.color_code_ts1)],
    ['Color Code TS2', identifierNumber(site.color_code_ts2)]
  ];
}

function nxdnSiteDetailRows(site) {
  return [
    ['Variant', trunkedVariant(site)], ['Network', identifierNumber(site.network_id)],
    ['System', identifierNumber(site.system_id)], ['Site', identifierNumber(site.site_id)],
    ['RAN', identifierNumber(site.ran)], ['Category', identityDomainLabel(site)],
    ['Repeater State', nxdnRepeaterMode(site.mode_code)],
    ['Current Repeater', identifierNumber(site.current_repeater)],
    ['Service Flags', Number(site.service_flags) ? `0x${hex(site.service_flags, 4)}` : ''],
    ['Failure Call Timer', site.failure_code == null ? '' :
      (Number(site.failure_code) === 0 ? 'Unspecified' : `${number(site.failure_code)} seconds`)]
  ];
}

function siteProtocolDetailRows(site) {
  if (isP25(site)) return p25SiteDetailRows(site);
  if (protocolFamily(site) === 'DMR') return dmrSiteDetailRows(site);
  if (protocolFamily(site) === 'NXDN') return nxdnSiteDetailRows(site);
  return [];
}

function p25SiteChannelColumns() {
  return [
    { label: 'LCN / Mode', fullLabel: 'Logical Channel Number and Modes', key: 'descriptor' },
    { label: 'Callsign', render: (row) => callsignLink(row.callsign),
      sortValue: (row) => row.callsign || '' },
    { label: 'Tags', key: 'tags', render: channelTags },
    { id: 'downlink', label: 'Down MHz', fullLabel: 'Downlink MHz',
      render: (row) => frequency(row.downlink_hz), className: 'numeric',
      sortValue: (row) => Number(row.downlink_hz || 0) },
    { id: 'uplink', label: 'Up MHz', fullLabel: 'Uplink MHz',
      render: (row) => frequency(row.uplink_hz), className: 'numeric',
      sortValue: (row) => Number(row.uplink_hz || 0) },
    { id: 'tdma', label: 'TDMA', render: (row) => yesNo(row.tdma),
      sortValue: (row) => Boolean(row.tdma) },
    { label: 'Slots', key: 'timeslots', className: 'numeric' },
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state),
      sortValue: (row) => row.state || '' },
    { label: 'Voice', fullLabel: 'Voice Grant Observations', key: 'voice_grant_observations',
      className: 'numeric' },
    { label: 'Data', fullLabel: 'Data Grant Observations', key: 'data_grant_observations',
      className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

function trunkedSiteChannelColumns() {
  return [
    { label: 'Channel', key: 'channel_number', className: 'numeric',
      render: (row) => identifierNumber(row.channel_number) },
    { label: 'Inbound', fullLabel: 'Inbound Channel', key: 'inbound_channel_number', className: 'numeric',
      render: (row) => identifierNumber(row.inbound_channel_number) },
    { label: 'Slot', key: 'timeslot', className: 'numeric',
      render: (row) => identifierNumber(row.timeslot) },
    { label: 'Use', render: (row) => trunkedChannelUse(row.role_flags) },
    { label: 'Source', render: (row) => trunkedChannelSources(row.role_flags) },
    { id: 'downlink', label: 'Down MHz', fullLabel: 'Downlink MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric' },
    { id: 'uplink', label: 'Up MHz', fullLabel: 'Uplink MHz',
      render: (row) => frequency(row.uplink_hz), className: 'numeric' },
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state) },
    { label: 'Snapshots', key: 'observation_count', className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms) }
  ];
}

async function renderSiteChannels(site) {
  const data = await api('/api/site/channels', { guid: site.guid, limit: 500 });
  const explanation = protocolFamily(site) === 'DMR' ? node('p', 'muted',
    'DMR grants usually identify an LCN and timeslot. Frequencies marked LCN Map were resolved from the configured map; OTA Freq means the system broadcast an absolute frequency.') :
    fragment();
  const p25 = isP25(site);
  content.append(section('Channels', fragment(explanation, table(data.rows || [],
    p25 ? p25SiteChannelColumns() : trunkedSiteChannelColumns(), 'No channels recorded',
    { type: p25 ? 'site-channels' : 'trunked-site-channels' }))));
}

function p25SiteNeighborColumns() {
  return [
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state),
      sortValue: (row) => row.state || '' },
    { id: 'type', label: 'Type', render: (row) => row.entry_type === 'ISSI' ? 'ISSI System' : 'Site' },
    { id: 'neighbor-name', label: 'Name', fullLabel: 'Monitored Site Name',
      render: neighborSiteLink,
      sortValue: (row) => row.neighbor_name || row.neighbor_site_name || row.neighbor_channel_name || '' },
    { id: 'wacn', label: 'WACN', render: (row) => hex(row.wacn, 5),
      sortValue: (row) => Number(row.wacn || 0) },
    { id: 'system', label: 'Sys', fullLabel: 'System', render: (row) => hex(row.system_id, 3),
      sortValue: (row) => Number(row.system_id || 0) },
    { id: 'rfss', label: 'RFSS', render: (row) => hex(row.rfss, 2),
      sortValue: (row) => Number(row.rfss || 0) },
    { id: 'site', label: 'Site', render: (row) => hex(row.site, 2),
      sortValue: (row) => Number(row.site || 0) },
    { id: 'lra', label: 'LRA', render: (row) => hex(row.lra, 2),
      sortValue: (row) => Number(row.lra || 0) },
    { label: 'LCN', key: 'channel_descriptor' },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz',
      render: (row) => frequency(row.downlink_hz), className: 'numeric',
      sortValue: (row) => Number(row.downlink_hz || 0) },
    { id: 'modes', label: 'Modes', render: neighborModes },
    { id: 'bands', label: 'Bands', key: 'band_count', className: 'numeric' },
    { id: 'advertised-status', label: 'Status', fullLabel: 'Advertised Status',
      render: (row) => neighborStatus(row.status), sortValue: (row) => row.status || '' },
    { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

function trunkedSiteNeighborColumns(site) {
  return [
    { label: 'Variant', render: (row) => trunkedVariant({
      protocol_code: site.protocol_code,
      variant_code: row.variant_code
    }) },
    { label: 'Model / Category', render: (row) => identityDomainLabel({
      protocol_code: site.protocol_code,
      identity_domain_code: row.identity_domain_code
    }) },
    { label: 'Network', key: 'network_id', className: 'numeric',
      render: (row) => identifierNumber(row.network_id) },
    { label: 'System', key: 'system_id', className: 'numeric',
      render: (row) => identifierNumber(row.system_id) },
    { label: 'Site', key: 'site_id', className: 'numeric',
      render: (row) => identifierNumber(row.site_id) },
    { label: 'Channel', key: 'channel_number', className: 'numeric',
      render: (row) => identifierNumber(row.channel_number) },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric' },
    { label: 'Status', render: (row) => trunkedNeighborStatus(row.status_flags) },
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state) },
    { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms) }
  ];
}

async function renderSiteNeighbors(site) {
  const data = await api('/api/site/neighbors', { guid: site.guid, limit: 500 });
  const p25 = isP25(site);
  content.append(section('Neighbors', table(data.rows || [],
    p25 ? p25SiteNeighborColumns() : trunkedSiteNeighborColumns(site), 'No neighbors recorded',
    { type: p25 ? 'site-neighbors' : 'trunked-site-neighbors' })));
}

async function renderSiteInfo(site) {
  const summary = [
    ['Metadata Updates', site.observation_count], ['Channels', site.channels], ['Neighbors', site.neighbors]
  ];
  if (siteCapability(site, 'band-plan')) summary.push(['Band Plans', site.bands]);
  if (siteCapability(site, 'patches')) summary.push(['Patches', site.patches]);

  const infoColumn = node('div', 'entity-info-column');
  infoColumn.append(section('Site Info', keyValues([
    ['System', systemLink(site, systemInfoValue(site))],
    ['GUID', site.guid], ['Name', site.channel_name], ['Alias List', site.alias_list_name],
    ['Protocol', protocolFamily(site)], ['Decoder', decoderDisplay(site.decoder)],
    ['Configured Frequency', frequency(site.primary_frequency_hz)],
    ['Current Control Frequency', frequency(site.current_control_hz)],
    ['First Seen', dateTime(site.first_seen_ms)], ['Last Seen', dateTime(site.last_seen_ms)]
  ])));
  const protocolDetails = siteProtocolDetailRows(site);
  if (protocolDetails.length) {
    infoColumn.append(section(`${protocolFamily(site)} Details`, keyValues(protocolDetails)));
  }

  const receiverColumn = node('div', 'entity-info-column');
  if (siteCapability(site, 'quality-live')) {
    receiverColumn.append(liveSiteReceiverSection(site));
  }
  if (siteCapability(site, 'top-talkgroups')) {
    receiverColumn.append(await siteTopTalkgroupsSection(site));
  }
  const layout = node('div', 'entity-info-layout');
  layout.append(infoColumn);
  if (receiverColumn.childNodes.length) layout.append(receiverColumn);
  content.append(metrics(summary), layout);
}

async function renderSite() {
  const guid = route.get('guid');
  if (!guid) throw new Error('Site GUID is missing from the URL');
  const response = await api('/api/site', { guid });
  const site = response.site;
  const requestedTab = route.get('tab') || 'info';
  const tabItems = siteTabItems(site);
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : 'info';
  const subtitle = [protocolFamily(site), trunkedVariant(site), siteIdentity(site)].filter(Boolean).join(' · ');
  content.append(pageHeader(siteValue(site), subtitle), siteTabs(site, tab));

  if (tab === 'quality') {
    if (siteCapability(site, 'quality-live')) content.append(liveSiteReceiverSection(site));
    if (siteCapability(site, 'quality-history')) content.append(await siteSignalHistorySection(site));
  } else if (tab === 'channels') {
    await renderSiteChannels(site);
  } else if (tab === 'neighbors') {
    await renderSiteNeighbors(site);
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
      { id: 'patch-name', label: 'Alias', fullLabel: 'Patch Alias', render: (row) => row.patch_alias_name ?
        talkgroupLink(site, row.patch_group, row.patch_alias_name) : '', className: 'alias-cell', sortValue: (row) => row.patch_alias_name || '' },
      { id: 'member-talkgroup-ids', label: 'TGIDs', fullLabel: 'Member Talkgroup IDs', render: (row) =>
        memberLinks(talkgroups.get(row.patch_group), (member) => talkgroupLink(site, member.talkgroup_id)) },
      { id: 'member-talkgroup-names', label: 'TG Aliases', fullLabel: 'Talkgroup Aliases', className: 'alias-cell', render: (row) =>
        memberLinks(talkgroups.get(row.patch_group), (member) => member.alias_name ?
          talkgroupLink(site, member.talkgroup_id, member.alias_name) : '') },
      { id: 'member-radio-ids', label: 'Radios', fullLabel: 'Radio IDs', render: (row) =>
        memberLinks(radios.get(row.patch_group), (member) => radioLink(site, member.radio_id)) },
      { id: 'member-radio-names', label: 'Radio Aliases', className: 'alias-cell', render: (row) =>
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
    await renderSiteInfo(site);
  }
}

function p25SpecialIdentifierLabel(row, value, kind) {
  if (!isP25(row)) return '';
  const identifier = Number(value);
  if (kind === 'talkgroup') {
    return ({ 0x0000: 'No Talkgroup', 0xFFFF: 'Everyone' })[identifier] || '';
  }
  if (kind === 'radio') {
    return ({
      0x000000: 'No Unit',
      0xFFFFFC: 'FNE',
      0xFFFFFD: 'System Default',
      0xFFFFFE: 'Registration Default',
      0xFFFFFF: 'All Units'
    })[identifier] || '';
  }
  return '';
}

function activityIdentifier(row, value, kind) {
  const identifier = identifierNumber(value);
  if (!identifier) return '';
  const specialLabel = p25SpecialIdentifierLabel(row, value, kind);
  if (specialLabel) {
    const result = node('span', 'special-identifier');
    result.append(node('span', 'special-identifier-label', specialLabel),
      badge('System/special', 'special-signaling'),
      node('span', 'special-identifier-value', `(${identifier})`));
    result.title = `P25 ${specialLabel}: system or special signaling identifier ${identifier}`;
    result.setAttribute('aria-label',
      `${specialLabel}, P25 system or special signaling identifier ${identifier}`);
    return result;
  }
  if (kind === 'talkgroup') return talkgroupLink(row, value, identifier);
  if (kind === 'radio') return radioLink(row, value, identifier);
  return identifier;
}

function activityTargetIdentifier(row) {
  const kind = TALKGROUP_TARGET_KINDS.has(Number(row.target_kind_code)) ? 'talkgroup' :
    Number(row.target_kind_code) === 2 ? 'radio' : '';
  if (kind === 'radio' && !p25SpecialIdentifierLabel(row, row.target_id, kind)) {
    return identifierNumber(row.target_id);
  }
  return activityIdentifier(row, row.target_id, kind);
}

function activitySourceAlias(row) {
  const alias = row.source_alias_name || '';
  if (!alias) return '';
  return p25SpecialIdentifierLabel(row, row.source_radio_id, 'radio') ?
    alias : radioLink(row, row.source_radio_id, alias);
}

function activityColumns() {
  return [
    { id: 'time', label: 'Seen', fullLabel: 'Observed Time', render: (row) => dateTime(row.observed_at_ms), sortValue: (row) => Number(row.observed_at_ms || 0) },
    { label: 'Action', key: 'action' },
    { label: 'Event', key: 'event_type' },
    { id: 'source', label: 'Src', fullLabel: 'Source ID',
      render: (row) => activityIdentifier(row, row.source_radio_id, 'radio'),
      className: 'numeric identifier-cell', sortValue: (row) => Number(row.source_radio_id || 0) },
    { id: 'source-alias', label: 'Src Alias', fullLabel: 'Source Alias',
      render: activitySourceAlias, className: 'alias-cell',
      sortValue: (row) => row.source_alias_name || '' },
    { id: 'target', label: 'Tgt', fullLabel: 'Target ID', render: activityTargetIdentifier,
      className: 'numeric identifier-cell', sortValue: (row) => Number(row.target_id || 0) },
    { id: 'target-alias', label: 'Tgt Alias', fullLabel: 'Target Alias', render: (row) => row.target_alias_name || '', className: 'alias-cell', sortValue: (row) => row.target_alias_name || '' },
    { id: 'frequency', label: 'MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sortValue: (row) => Number(row.frequency_hz || 0) },
    { label: 'LCN', key: 'lcn' },
    { label: 'Slot', render: (row) => identifierNumber(row.timeslot), className: 'numeric' },
    { id: 'encryption', label: 'Enc', fullLabel: 'Encryption', render: encryptionActivityValue,
      className: 'encrypted', sortValue: (row) => row.encryption_display || (row.encrypted ? 'ENC' : '') }
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
  const block = section(title, activityTable);
  const controls = node('div', 'pager');
  controls.append(route.get('before_id') ? anchor('Newest', currentHref({ before_id: null }), 'button secondary') :
    node('span', 'button disabled', 'Newest'));
  controls.append(data.hasMore ? anchor('Older', currentHref({ before_id: data.nextBeforeId }), 'button secondary') :
    node('span', 'button disabled', 'Older'));
  block.append(controls);
  content.append(block);

  if (!route.get('before_id')) {
    const titleBar = block.querySelector('.section-title');
    const pause = node('button', 'button secondary', 'Pause updates');
    pause.type = 'button';
    pause.setAttribute('aria-pressed', 'false');
    titleBar.append(pause);
    let paused = false;
    const pending = new Map();
    const activityRowKey = (row) =>
      row.id !== null && row.id !== undefined ? String(row.id) : Symbol();
    const updatePauseLabel = () => {
      pause.textContent = paused ? `Resume${pending.size ? ` (${number(pending.size)})` : ''}` :
        'Pause updates';
      pause.setAttribute('aria-pressed', String(paused));
    };
    const addActivityRow = (row) => {
      activityTable.tableController.upsertRow(row, { prepend: true, limit: 200 });
    };
    pause.addEventListener('click', () => {
      paused = !paused;
      if (!paused && pending.size) {
        const rows = [...pending.values()].sort((left, right) =>
          Number(left.observed_at_ms || 0) - Number(right.observed_at_ms || 0) ||
            Number(left.id || 0) - Number(right.id || 0));
        pending.clear();
        rows.forEach(addActivityRow);
      }
      updatePauseLabel();
    });
    const source = liveConnection('/live/activity', scopeParameters);
    source.addEventListener('activity', (event) => {
      const row = JSON.parse(event.data);
      if (String(row.action || '').toUpperCase() === 'GRANT') return;
      if (!paused) {
        addActivityRow(row);
        return;
      }
      const key = activityRowKey(row);
      if (pending.has(key)) pending.delete(key);
      pending.set(key, row);
      if (pending.size > 200) {
        pending.delete(pending.keys().next().value);
      }
      updatePauseLabel();
    });
  }
}

async function renderConventional() {
  const page = await api('/api/conventional', pageParameters());
  content.append(pageHeader('Conventional', 'Started conventional analog and digital channel summaries'));
  const columns = [
    { label: 'Name', render: (row) => anchor(row.channel_name || row.context_key,
      href('conventional-detail', { context: row.context_key, tab: 'info' })), className: 'alias-cell', sort: 'name', sortValue: (row) => row.channel_name || row.context_key },
    { id: 'protocol', label: 'Protocol', render: (row) => protocol(row.protocol_code), sort: 'protocol', sortValue: (row) => protocol(row.protocol_code) },
    { label: 'Decoder', render: (row) => decoderDisplay(row.decoder), sort: 'decoder',
      sortValue: (row) => decoderLabel(row.decoder, true) },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency', sortValue: (row) => Number(row.frequency_hz || 0) },
    { label: 'Slot', key: 'timeslot', className: 'numeric', sort: 'slot',
      render: (row) => identifierNumber(row.timeslot) },
    { id: 'nac', label: 'NAC', render: (row) => hex(row.nac, 3), sort: 'nac', sortValue: (row) => Number(row.nac || 0) },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
  content.append(pagedSection('Conventional Channels', page, columns, 'Search name or frequency', 'conventional'));
}

function conventionalCapability(context, capability) {
  const capabilities = context?.capabilities;
  if (!capabilities) return capability === 'info';
  const normalized = normalizedSiteCapability(capability);
  if (Array.isArray(capabilities)) {
    return capabilities.some((value) => normalizedSiteCapability(value) === normalized);
  }
  if (typeof capabilities === 'string') {
    return capabilities.split(',').some((value) => normalizedSiteCapability(value) === normalized);
  }
  if (typeof capabilities !== 'object') return false;
  const entry = Object.entries(capabilities)
    .find(([key]) => normalizedSiteCapability(key) === normalized);
  return entry ? siteCapabilityValue(entry[1]) : false;
}

function conventionalTabItems(context) {
  const values = { context: context.context_key };
  const items = [];
  if (conventionalCapability(context, 'info')) {
    items.push({ id: 'info', label: 'Info',
      href: href('conventional-detail', { ...values, tab: 'info' }) });
  }
  if (conventionalCapability(context, 'talkgroups')) {
    items.push({ id: 'talkgroups', label: 'Talkgroups',
      href: href('conventional-detail', { ...values, tab: 'talkgroups' }) });
  }
  if (conventionalCapability(context, 'radios')) {
    items.push({ id: 'radios', label: 'Radios',
      href: href('conventional-detail', { ...values, tab: 'radios' }) });
  }
  if (conventionalCapability(context, 'activity')) {
    items.push({ id: 'activity', label: 'Activity',
      href: href('conventional-detail', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' });
  }
  return items.length ? items : [
    { id: 'info', label: 'Info', href: href('conventional-detail', { ...values, tab: 'info' }) }
  ];
}

function conventionalTalkgroupColumns() {
  return [
    { id: 'talkgroup-id', label: 'Talkgroup', key: 'talkgroup_id', className: 'numeric',
      sort: 'talkgroup', render: (row) => identifierNumber(row.talkgroup_id) },
    { id: 'talkgroup-name', label: 'Alias', key: 'alias_name', className: 'alias-cell', sort: 'alias' },
    { id: 'talkgroup-description', label: 'Description', key: 'alias_description',
      className: 'alias-cell' },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency',
      sortValue: (row) => Number(row.frequency_hz || 0) },
    { id: 'timeslot', label: 'Slot', key: 'timeslot', className: 'numeric', sort: 'slot',
      render: (row) => identifierNumber(row.timeslot) },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric',
      sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'encrypted', label: 'Encrypted', render: (row) => number(row.encrypted_count),
      className: 'numeric', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
    { id: 'source', label: 'Last Source', key: 'last_source_radio_id', className: 'numeric',
      render: (row) => identifierNumber(row.last_source_radio_id) },
    { id: 'source-alias', label: 'Source Alias', key: 'last_source_alias_name', className: 'alias-cell' },
    { id: 'first-seen', label: 'First', fullLabel: 'First Seen',
      render: (row) => dateTime(row.first_seen_ms), sort: 'first_seen',
      sortValue: (row) => Number(row.first_seen_ms || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen',
      sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

function conventionalRadioColumns() {
  return [
    { id: 'radio', label: 'Radio', key: 'radio_id', className: 'numeric', sort: 'radio',
      render: (row) => identifierNumber(row.radio_id) },
    { id: 'radio-alias', label: 'Alias', key: 'alias_name', className: 'alias-cell', sort: 'alias' },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency',
      sortValue: (row) => Number(row.frequency_hz || 0) },
    { id: 'timeslot', label: 'Slot', key: 'timeslot', className: 'numeric', sort: 'slot',
      render: (row) => identifierNumber(row.timeslot) },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric',
      sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'encrypted', label: 'Encrypted', render: (row) => number(row.encrypted_count),
      className: 'numeric', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
    { id: 'source-calls', label: 'As Source', render: (row) => number(row.source_call_count),
      className: 'numeric', sort: 'source_calls', sortValue: (row) => Number(row.source_call_count || 0) },
    { id: 'target-calls', label: 'As Target', render: (row) => number(row.target_call_count),
      className: 'numeric', sort: 'target_calls', sortValue: (row) => Number(row.target_call_count || 0) },
    { id: 'group-calls', label: 'Group', render: (row) => number(row.group_call_count),
      className: 'numeric', sort: 'group_calls', sortValue: (row) => Number(row.group_call_count || 0) },
    { id: 'private-calls', label: 'Private', render: (row) => number(row.private_call_count),
      className: 'numeric', sort: 'private_calls', sortValue: (row) => Number(row.private_call_count || 0) },
    { id: 'last-talkgroup', label: 'Last Talkgroup', key: 'last_talkgroup_id', className: 'numeric',
      render: (row) => identifierNumber(row.last_talkgroup_id) },
    { id: 'talkgroup-name', label: 'Talkgroup Alias', key: 'last_talkgroup_alias_name',
      className: 'alias-cell' },
    { id: 'last-peer', label: 'Last Peer', key: 'last_peer_radio_id', className: 'numeric',
      render: (row) => identifierNumber(row.last_peer_radio_id) },
    { id: 'peer-alias', label: 'Peer Alias', key: 'last_peer_alias_name', className: 'alias-cell' },
    { id: 'first-seen', label: 'First', fullLabel: 'First Seen',
      render: (row) => dateTime(row.first_seen_ms), sort: 'first_seen',
      sortValue: (row) => Number(row.first_seen_ms || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen',
      sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

async function renderConventionalTalkgroups(contextKey) {
  const page = await api('/api/conventional/talkgroups', pageParameters({
    context: contextKey,
    limit: CONVENTIONAL_IDENTITY_PAGE_LIMIT
  }));
  content.append(pagedSection('Talkgroups', page, conventionalTalkgroupColumns(),
    'Search talkgroup ID or alias', 'conventional-talkgroups'));
}

async function renderConventionalRadios(contextKey) {
  const page = await api('/api/conventional/radios', pageParameters({
    context: contextKey,
    limit: CONVENTIONAL_IDENTITY_PAGE_LIMIT
  }));
  content.append(pagedSection('Radios', page, conventionalRadioColumns(),
    'Search radio ID or alias', 'conventional-radios'));
}

async function renderConventionalDetail() {
  const contextKey = route.get('context');
  if (!contextKey) throw new Error('Conventional context is missing from the URL');
  const data = await api('/api/conventional/detail', { context: contextKey });
  const context = data.context;
  const tabItems = conventionalTabItems(context);
  const requestedTab = route.get('tab') || 'info';
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : tabItems[0].id;
  content.append(pageHeader(context.channel_name || context.context_key, protocol(context.protocol_code)),
    tabs(tabItems, tab));

  if (tab === 'activity') {
    await renderActivity({ context: contextKey });
  } else if (tab === 'talkgroups') {
    await renderConventionalTalkgroups(contextKey);
  } else if (tab === 'radios') {
    await renderConventionalRadios(contextKey);
  } else {
    content.append(section('Channel Info', keyValues([
      ['Name', context.channel_name], ['Context', context.context_key], ['GUID', context.guid],
      ['Protocol', protocol(context.protocol_code)], ['Decoder', decoderDisplay(context.decoder)],
      ['Alias List', context.alias_list_name], ['Frequency', frequency(context.primary_frequency_hz)],
      ['NAC', hexDecimalPair(context.nac, 3)], ['First Seen', dateTime(context.first_seen_ms)],
      ['Last Seen', dateTime(context.last_seen_ms)]
    ])));
    content.append(section('Frequency Summaries', table(data.summaries || [], [
      { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sortValue: (row) => Number(row.frequency_hz || 0) },
      { label: 'Slot', key: 'timeslot', className: 'numeric',
        render: (row) => identifierNumber(row.timeslot) },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'recorded', label: 'Rec', fullLabel: 'Recorded',
        render: (row) => number(row.recorded_count), className: 'numeric',
        sortValue: (row) => Number(row.recorded_count || 0) },
      { id: 'streamed', label: 'Sent', fullLabel: 'Sent to Streamer',
        render: (row) => number(row.streamed_count), className: 'numeric',
        sortValue: (row) => Number(row.streamed_count || 0) },
      { id: 'encrypted', label: 'Enc', fullLabel: 'Encrypted',
        render: (row) => number(row.encrypted_count), className: 'numeric encrypted',
        sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'first-seen', label: 'First', fullLabel: 'First Observed', render: (row) => dateTime(row.first_seen_ms), sortValue: (row) => Number(row.first_seen_ms || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Observed', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
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
initializeThemeToggle();
initializePlaybackHeader();
loadStatus().finally(render);
window.setInterval(() => {
  if (!document.hidden) loadStatus(true);
}, 10_000);
