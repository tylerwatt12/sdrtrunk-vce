let route = new URLSearchParams(window.location.search);
const content = document.getElementById('content');
const tableOnly = route.get('layout') === 'table';
const WEB_CLIENT_REVISION = document.querySelector('meta[name="sdrtrunk-web-revision"]')?.content?.trim() || '';
const TABLE_WIDTH_COOKIE = 'sdrtrunk_table_widths_v4';
const ALIAS_CATALOG_COLUMNS_STORAGE_KEY = 'sdrtrunk_alias_catalog_columns_v2';
const ALIAS_CREATE_ROUTE_KEYS = Object.freeze([
  'createAlias', 'createListName', 'createType', 'createProtocol', 'createVariant', 'createValue', 'createName'
]);
const TABLE_WIDTH_MINIMUM = 48;
const TABLE_WIDTH_MAXIMUM = 1200;
const SIGNAL_OFFLINE_MILLISECONDS = 45_000;
const RECEIVER_HEALTH_STALE_MILLISECONDS = 15_000;
const DECODE_HEALTHY_MINIMUM_PERCENT = 90;
const DECODE_DEGRADED_MINIMUM_PERCENT = 75;
const VOICE_QUALITY_WARMUP_FRAMES = 50;
const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
const THEME_STORAGE_KEY = 'sdrtrunk_theme';
const NAVIGATION_DRAWER_MEDIA = '(max-width: 1180px)';
const NAVIGATION_HOVER_MEDIA = '(min-width: 1181px) and (hover: hover)';
const LIVE_DETAIL_DEFAULT_MATCHING_ROW_LIMIT = 200;
const LIVE_DETAIL_CAPTURE_MULTIPLIER = 25;
const LIVE_DETAIL_MINIMUM_CAPTURE = 2000;
const LIVE_DETAIL_MAXIMUM_CAPTURE = 10000;
const LIVE_DETAIL_REFRESH_INTERVAL_MILLISECONDS = 125;
const ACTIVITY_REFRESH_INTERVAL_MILLISECONDS = 10_000;
const RADIO_REFERENCE_DIRECTORY_TIMEOUT_MILLISECONDS = 15_000;
let themePreference = null;
const ALIAS_LIST_FAMILY_LABELS = Object.freeze({
  P25: 'P25', DMR: 'DMR', NXDN: 'NXDN', NBFM: 'Conventional Analog (AM/NBFM)'
});
const ACCESS_CAPABILITIES = Object.freeze({
  SITE_ACCESS: 'site-access',
  DASHBOARD: 'dashboard',
  LIVE: 'live',
  TUNER_SPECTRUM: 'tuner-spectrum',
  SYSTEMS: 'systems',
  CONVENTIONAL: 'conventional',
  CREDITS: 'credits',
  CSV_EXPORT: 'csv-export',
  CALL_AUDIO: 'call-audio',
  RECEIVER_HEALTH: 'receiver-health',
  ADMIN_ALIASES: 'admin-aliases',
  ADMIN_AUDIO: 'admin-audio',
  ADMIN_SETTINGS: 'admin-settings',
  ADMIN_USERS: 'admin-users',
  ADMIN_ACCESS: 'admin-access'
});
const VIEW_ACCESS_CAPABILITY = Object.freeze({
  dashboard: ACCESS_CAPABILITIES.DASHBOARD,
  live: ACCESS_CAPABILITIES.LIVE,
  scanner: ACCESS_CAPABILITIES.CALL_AUDIO,
  'tuner-spectrum': ACCESS_CAPABILITIES.TUNER_SPECTRUM,
  systems: ACCESS_CAPABILITIES.SYSTEMS,
  system: ACCESS_CAPABILITIES.SYSTEMS,
  talkgroup: ACCESS_CAPABILITIES.SYSTEMS,
  radio: ACCESS_CAPABILITIES.SYSTEMS,
  site: ACCESS_CAPABILITIES.SYSTEMS,
  conventional: ACCESS_CAPABILITIES.CONVENTIONAL,
  'conventional-detail': ACCESS_CAPABILITIES.CONVENTIONAL,
  aliases: ACCESS_CAPABILITIES.ADMIN_ALIASES,
  credits: ACCESS_CAPABILITIES.CREDITS
});
const SIGNAL_RANGES = Object.freeze([
  ['1h', '1 hour'], ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days']
]);
const ACTIVITY_RANGES = Object.freeze([
  ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days']
]);
const LIVE_EVENT_CATEGORY_CLASSES = Object.freeze({
  VOICE: 'live-event-category-voice',
  ENCRYPTED_VOICE: 'live-event-category-encrypted-voice',
  DATA: 'live-event-category-data',
  COMMAND: 'live-event-category-command',
  REGISTRATION: 'live-event-category-registration',
  OTHER: 'live-event-category-other'
});
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
  { key: 'AM', label: 'AM', color: 'var(--chart-join)' },
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
const DASHBOARD_ACTIVITY_SERIES = Object.freeze([
  { action: 'CALL', label: 'Call', color: 'var(--chart-call)' },
  { action: 'GRANT', label: 'Grant', color: 'var(--chart-grant)' },
  ...TALKGROUP_SIGNALING_SERIES.filter((series) => series.field !== 'continue_count')
    .map((series) => ({
      action: series.field.replace(/_count$/, '').toUpperCase(),
      label: series.label,
      color: series.color
    }))
]);
const DASHBOARD_ACTIVITY_RANGES = Object.freeze([
  ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days']
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
  'affiliation': 190,
  'affiliated-site': 220,
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
const SERVICE_STATUS_FAILURE_WARNING_THRESHOLD = 3;
const SERVICE_STATUS_INITIAL_ATTEMPTS = 3;
const SERVICE_STATUS_RETRY_DELAY_MS = 500;
const ALIAS_CATALOG_DEFAULT_ENRICHMENT_COLUMNS = Object.freeze([
  'alias', 'description', 'identifier', 'matcher', 'group', 'calls', 'recorded', 'streamed',
  'encrypted-evidence', 'grants', 'joins', 'emergency', 'logout', 'relationships', 'last-evidence'
]);
let serviceStatus = null;
let serviceStatusRequestPending = false;
let serviceStatusConsecutiveFailures = 0;
let liveDisplaySettings = null;
let webClientReloadAttempted = false;
let tableWidthPreferences = readTableWidthPreferences();
let activeReadOnlyModal = null;
let aliasEditorSelection = new Set();
let aliasEditorLastSelectionIndex = null;
let aliasEditorContext = null;
let accessSession = anonymousAccessSession();
let accessSessionAvailable = false;
let notifyConfirmedAccessRefresh = () => {};
let playbackScanListRequest = 0;
let playbackScanListLoading = false;

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

function storedTheme() {
  if (themePreference) return themePreference;
  try {
    const value = window.localStorage.getItem(THEME_STORAGE_KEY);
    themePreference = value === 'dark' ? 'dark' : 'light';
  } catch (_) {
    themePreference = 'light';
  }
  return themePreference;
}

function updateThemeButton(toggle, theme) {
  if (!toggle) return;
  const dark = theme === 'dark';
  toggle.setAttribute('aria-pressed', String(dark));
  toggle.setAttribute('aria-label', `Use ${dark ? 'light' : 'dark'} theme`);
  toggle.title = `Use ${dark ? 'light' : 'dark'} theme`;
  const use = toggle.querySelector('use');
  if (use) use.setAttribute('href', dark ? '#icon-sun' : '#icon-moon');
}

function applyTheme() {
  const theme = storedTheme();
  if (theme === 'dark') document.documentElement.dataset.theme = 'dark';
  else document.documentElement.removeAttribute('data-theme');
  updateThemeButton(document.getElementById('theme-toggle'), theme);
}

function setTheme(theme) {
  const selected = theme === 'dark' ? 'dark' : 'light';
  themePreference = selected;
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, selected);
  } catch (error) {
    // Browser storage can be disabled; the active page theme still changes.
  }
  applyTheme();
}

function initializeThemeToggle() {
  applyTheme();
  document.getElementById('theme-toggle')?.addEventListener('click', () =>
    setTheme(storedTheme() === 'dark' ? 'light' : 'dark'));
}

function accessTierFromWire(value) {
  return ({ public: 'PUBLIC', user: 'USER', admin: 'ADMIN' })[value] || null;
}

function accessTierToWire(value) {
  return ({ PUBLIC: 'public', USER: 'user', ADMIN: 'admin' })[value] || null;
}

function accessTierValue(value) {
  return ['PUBLIC', 'USER', 'ADMIN'].includes(value) ? value : 'PUBLIC';
}

function accessTierRank(value) {
  return ({ PUBLIC: 0, USER: 1, ADMIN: 2 })[accessTierValue(value)];
}

function accessTierLabel(value) {
  const tier = accessTierValue(value);
  return tier[0] + tier.slice(1).toLowerCase();
}

function anonymousAccessSession() {
  return {
    configured: false,
    authenticated: false,
    username: null,
    tier: 'PUBLIC',
    primary: false,
    csrfToken: null,
    capabilities: {}
  };
}

function normalizedAccessSession(value) {
  const authenticated = value?.authenticated === true;
  const capabilities = value?.capabilities && !Array.isArray(value.capabilities) &&
    typeof value.capabilities === 'object' ? value.capabilities : {};
  return {
    configured: value?.configured === true,
    authenticated,
    username: authenticated ? String(value.username || '').trim() : null,
    tier: authenticated ? accessTierFromWire(value?.tier) || 'PUBLIC' : 'PUBLIC',
    primary: authenticated && value?.primary === true,
    csrfToken: authenticated && typeof value.csrf_token === 'string' ? value.csrf_token : null,
    capabilities: Object.fromEntries(Object.entries(capabilities)
      .filter(([id, allowed]) => typeof id === 'string' && typeof allowed === 'boolean'))
  };
}

function capabilityAllowed(capability) {
  if (!accessSessionAvailable) return false;
  return accessSession.capabilities?.[capability] === true;
}

function viewAccessCapability(view) {
  return VIEW_ACCESS_CAPABILITY[view] || null;
}

function viewAllowed(view) {
  if (view === 'configuration') {
    return accessSession.tier === 'ADMIN' && capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ALIASES);
  }
  if (view === 'hardware') {
    return accessSession.tier === 'ADMIN' && capabilityAllowed(ACCESS_CAPABILITIES.TUNER_SPECTRUM);
  }
  if (view === 'aliases') {
    return accessSession.tier === 'ADMIN' && capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ALIASES);
  }
  if (view === 'tuner-spectrum') {
    return accessSession.tier === 'ADMIN' && capabilityAllowed(ACCESS_CAPABILITIES.TUNER_SPECTRUM);
  }
  if (view === 'admin') {
    return accessSession.tier === 'ADMIN' &&
      (capabilityAllowed(ACCESS_CAPABILITIES.RECEIVER_HEALTH) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_SETTINGS) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ALIASES) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_AUDIO) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_USERS) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ACCESS));
  }
  const capability = viewAccessCapability(view);
  return !capability || capabilityAllowed(capability);
}

function accessSessionSignature() {
  const capabilities = Object.entries(accessSession.capabilities || {})
    .sort(([left], [right]) => left.localeCompare(right));
  return JSON.stringify([accessSessionAvailable, accessSession.configured, accessSession.authenticated,
    accessSession.username,
    accessSession.tier, accessSession.primary, capabilities]);
}

function updateNavigationAccess() {
  document.querySelectorAll('.primary-nav a[data-view]').forEach((link) => {
    const locked = !viewAllowed(link.dataset.view);
    link.classList.toggle('access-locked', locked);
    const lock = link.querySelector('.nav-lock');
    if (lock) lock.hidden = !locked;
    const label = link.querySelector('span')?.textContent?.trim() || routeViewLabel(link.dataset.view);
    link.title = locked ? `${label}: access required` : '';
  });
}

function updateAccessControls() {
  const label = document.getElementById('auth-session-label');
  const action = document.getElementById('auth-action');
  if (!label || !action) return;
  label.hidden = false;
  if (!accessSessionAvailable) {
    label.textContent = 'Access unavailable';
    label.title = 'The receiver did not return its current access policy.';
    action.textContent = 'Retry sign in';
    action.disabled = false;
  } else if (!accessSession.configured) {
    label.textContent = 'Primary admin not set';
    label.title = 'Set the primary administrator password from the local JavaFX Web Server settings.';
    action.textContent = 'Sign In';
    action.disabled = false;
  } else if (accessSession.authenticated) {
    label.textContent = `${accessSession.username} · ${accessTierLabel(accessSession.tier)}`;
    label.title = accessSession.primary ? 'Primary administrator managed from the JavaFX interface' :
      `Signed in with ${accessTierLabel(accessSession.tier)} access`;
    action.textContent = 'Sign Out';
    action.disabled = false;
  } else {
    const signInRequired = accessSession.capabilities?.[ACCESS_CAPABILITIES.SITE_ACCESS] === false;
    label.textContent = signInRequired ? 'Sign in required' : 'Public';
    label.title = signInRequired ? 'This receiver requires an account for all site features' : 'Using public access';
    action.textContent = 'Sign In';
    action.disabled = false;
  }
  updateNavigationAccess();
  receiverHealthController.synchronizeAccess();
}

function formField(labelText, control, detail = '') {
  const label = node('label', 'admin-form-field');
  label.append(node('span', 'admin-form-label', labelText), control);
  if (detail) label.append(node('small', 'admin-form-help', detail));
  return label;
}

function authenticationFailureMessage(error) {
  if (error?.status === 401) return 'The username or password was not accepted.';
  if (error?.status === 403 && window.location.protocol !== 'https:' && !['localhost', '127.0.0.1', '::1']
    .includes(window.location.hostname)) {
    return 'Remote sign-in requires HTTPS or a local connection.';
  }
  if (error?.status === 429) return 'Too many sign-in attempts. Wait a few minutes, then try again.';
  if (error?.status === 503) return 'Sign-in is busy. Wait a moment, then try again.';
  return error?.message || 'The receiver could not process sign-in.';
}

function showLoginModal(returnFocusSelector = '#auth-action') {
  if (accessSessionAvailable && !accessSession.configured) {
    const body = node('div', 'admin-confirmation');
    body.append(node('p', '',
      'Set the primary administrator password from the local JavaFX Web Server settings before signing in.'));
    openReadOnlyModal('Sign-in is not configured', body, {
      id: 'sign-in-setup', returnFocusSelector, className: 'admin-modal'
    });
    return;
  }
  const form = node('form', 'admin-form login-form');
  const username = node('input');
  username.name = 'username';
  username.autocomplete = 'username';
  username.maxLength = 64;
  username.required = true;
  const password = node('input');
  password.type = 'password';
  password.name = 'password';
  password.autocomplete = 'current-password';
  password.maxLength = 256;
  password.required = true;
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'alert');
  const actions = node('div', 'admin-form-actions');
  const submit = node('button', '', 'Sign In');
  submit.type = 'submit';
  actions.append(submit);
  form.append(formField('Username', username), formField('Password', password), message, actions);
  const modal = openReadOnlyModal('Sign in', form, {
    id: 'sign-in', returnFocusSelector, className: 'admin-modal'
  });
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (submit.disabled) return;
    submit.disabled = true;
    username.disabled = true;
    password.disabled = true;
    message.textContent = 'Signing in…';
    try {
      liveMultiplexer.stop();
      const session = await requestJson('/api/v1/auth/login', {
        method: 'POST', body: { username: username.value, password: password.value }, csrf: false,
        page: false, timeoutMs: 35_000
      });
      password.value = '';
      accessSession = normalizedAccessSession(session);
      accessSessionAvailable = true;
      updateAccessControls();
      synchronizePlaybackAccess();
      liveMultiplexer.ensureConnected();
      if (!accessSession.authenticated) throw new Error('The receiver did not create a session.');
      modal.close();
      void receiverHealthController.refresh();
      await refreshLiveDisplaySettings(false);
      await render();
    } catch (error) {
      liveMultiplexer.ensureConnected();
      password.value = '';
      password.disabled = false;
      username.disabled = false;
      submit.disabled = false;
      message.textContent = authenticationFailureMessage(error);
      password.focus();
    }
  });
  username.focus();
}

async function signOut() {
  const action = document.getElementById('auth-action');
  if (action) action.disabled = true;
  try {
    liveMultiplexer.stop();
    const session = await requestJson('/api/v1/auth/logout', {
      method: 'POST', csrf: true, page: false
    });
    accessSession = normalizedAccessSession(session);
    accessSessionAvailable = true;
    updateAccessControls();
    synchronizePlaybackAccess();
    liveMultiplexer.ensureConnected();
  } catch (error) {
    liveMultiplexer.ensureConnected();
    openReadOnlyModal('Unable to sign out', node('div', 'error', error.message), {
      id: 'sign-out-error', returnFocusSelector: '#auth-action', className: 'admin-modal'
    });
    if (action) action.disabled = false;
    return;
  }
  await refreshLiveDisplaySettings(false);
  await render();
}

function initializeAccessControls() {
  const action = document.getElementById('auth-action');
  if (!action) return;
  action.addEventListener('click', () => {
    if (accessSession.authenticated) signOut();
    else showLoginModal();
  });
}

function navigationUsesDrawer() {
  return window.matchMedia(NAVIGATION_DRAWER_MEDIA).matches;
}

function closeNavigationGroups(except = null) {
  document.querySelectorAll('#primary-navigation .nav-group[open]').forEach((group) => {
    if (group !== except) group.open = false;
  });
}

function openNavigationGroup(group) {
  if (!group) return;
  closeNavigationGroups(group);
  group.open = true;
}

function firstUsableNavigationControl(navigation) {
  return [...navigation.querySelectorAll('.nav-group > summary, a[href]')]
    .find((control) => !control.hidden) || null;
}

function drawerNavigationFocusTargets(navigation, toggle) {
  return [toggle, ...navigation.querySelectorAll('.nav-group > summary, a[href]')]
    .filter((control) => control.getClientRects().length > 0);
}

function synchronizeNavigationAccessibility(navigation = document.getElementById('primary-navigation')) {
  if (!navigation) return;
  const hiddenDrawer = navigationUsesDrawer() && !document.body.classList.contains('navigation-open');
  navigation.toggleAttribute('inert', hiddenDrawer);
  navigation.setAttribute('aria-hidden', String(hiddenDrawer));
}

function setNavigationOpen(open, returnFocus = false) {
  const navigation = document.getElementById('primary-navigation');
  const toggle = document.getElementById('navigation-toggle');
  const backdrop = document.getElementById('navigation-backdrop');
  if (!navigation || !toggle || !backdrop) return;
  const next = navigationUsesDrawer() && open === true;
  if (next) openNavigationGroup(navigation.querySelector('.nav-group.active'));
  document.body.classList.toggle('navigation-open', next);
  toggle.setAttribute('aria-expanded', String(next));
  toggle.setAttribute('aria-label', next ? 'Close navigation' : 'Open navigation');
  backdrop.hidden = !next;
  synchronizeNavigationAccessibility(navigation);
  if (next) {
    window.requestAnimationFrame(() => firstUsableNavigationControl(navigation)?.focus());
  } else if (returnFocus && navigationUsesDrawer()) {
    toggle.focus();
  }
}

function initializeNavigation() {
  const navigation = document.getElementById('primary-navigation');
  const toggle = document.getElementById('navigation-toggle');
  const backdrop = document.getElementById('navigation-backdrop');
  if (!navigation || !toggle || !backdrop) return;
  const groups = [...navigation.querySelectorAll('.nav-group')];
  const drawerMedia = window.matchMedia(NAVIGATION_DRAWER_MEDIA);
  const hoverMedia = window.matchMedia(NAVIGATION_HOVER_MEDIA);
  toggle.addEventListener('click', () => {
    const open = document.body.classList.contains('navigation-open');
    setNavigationOpen(!open, open);
  });
  backdrop.addEventListener('click', () => setNavigationOpen(false, true));
  navigation.addEventListener('click', (event) => {
    if (event.target.closest('a[href]')) setNavigationOpen(false, navigationUsesDrawer());
  });
  groups.forEach((group) => {
    const summary = group.querySelector(':scope > summary');
    group.addEventListener('toggle', () => {
      if (group.open) closeNavigationGroups(group);
    });
    group.addEventListener('pointerenter', () => {
      if (hoverMedia.matches) openNavigationGroup(group);
    });
    group.addEventListener('pointerleave', () => {
      if (hoverMedia.matches && !group.querySelector(':focus-visible')) group.open = false;
    });
    group.addEventListener('focusin', () => {
      if (!drawerMedia.matches) openNavigationGroup(group);
    });
    group.addEventListener('focusout', () => {
      window.requestAnimationFrame(() => {
        if (!drawerMedia.matches && !group.contains(document.activeElement) && !group.matches(':hover')) {
          group.open = false;
        }
      });
    });
    summary?.addEventListener('click', (event) => {
      if (!hoverMedia.matches) return;
      event.preventDefault();
      openNavigationGroup(group);
    });
  });
  document.addEventListener('pointerdown', (event) => {
    if (hoverMedia.matches && !navigation.contains(event.target)) closeNavigationGroups();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Tab' && drawerMedia.matches && document.body.classList.contains('navigation-open')) {
      const targets = drawerNavigationFocusTargets(navigation, toggle);
      if (!targets.length) return;
      const current = targets.indexOf(document.activeElement);
      const next = event.shiftKey ? (current <= 0 ? targets.length - 1 : current - 1) :
        (current < 0 || current === targets.length - 1 ? 0 : current + 1);
      event.preventDefault();
      targets[next].focus();
      return;
    }
    if (event.key !== 'Escape') return;
    if (document.body.classList.contains('navigation-open')) {
      event.preventDefault();
      setNavigationOpen(false, true);
      return;
    }
    if (!drawerMedia.matches) {
      const openGroup = navigation.querySelector('.nav-group[open]');
      if (openGroup) {
        event.preventDefault();
        const summary = openGroup.querySelector(':scope > summary');
        summary?.focus();
        openGroup.open = false;
      }
    }
  });
  drawerMedia.addEventListener('change', () => {
    setNavigationOpen(false);
    closeNavigationGroups();
  });
  hoverMedia.addEventListener('change', () => closeNavigationGroups());
  synchronizeNavigationAccessibility(navigation);
}

async function refreshAccessSession(refreshCurrentView = false) {
  const previousSignature = accessSessionSignature();
  try {
    const session = await requestJson('/api/v1/auth/session', { csrf: false, page: false });
    accessSession = normalizedAccessSession(session);
    accessSessionAvailable = true;
    notifyConfirmedAccessRefresh();
  } catch (error) {
    //A transport timeout is not evidence that an authenticated server session ended. Preserve the last confirmed
    //identity and policy; only an explicit authorization denial can replace it before a confirmed session response.
    if (error?.status === 401 || error?.status === 403) {
      accessSession = anonymousAccessSession();
      accessSessionAvailable = true;
    } else if (!accessSessionAvailable) {
      accessSession = anonymousAccessSession();
    }
  }
  const accessChanged = previousSignature !== accessSessionSignature();
  updateAccessControls();
  synchronizePlaybackAccess(accessChanged);
  if (refreshCurrentView && accessChanged) await render();
  return accessSession;
}

function fragment(...children) {
  const result = document.createDocumentFragment();
  children.flat().filter(Boolean).forEach((child) => result.append(child));
  return result;
}

function number(value) {
  const numeric = value === null || value === undefined || value === '' || value === false ? 0 : Number(value);
  return Number.isFinite(numeric) ? new Intl.NumberFormat().format(numeric) : '—';
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

function protocol(value) {
  const named = { am: 'AM', p25: 'P25', dmr: 'DMR', nxdn: 'NXDN', nbfm: 'NBFM' };
  return named[String(value || '').toLowerCase()] || value || '';
}

function aliasListFamilyLabel(value) {
  const family = String(value?.family ?? value ?? '').trim().toUpperCase();
  return ALIAS_LIST_FAMILY_LABELS[family] || family || 'Unknown';
}

function decoderLabel(value, compact = false) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  const labels = {
    AM: ['AM', 'AM'],
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

function observedSiteLabel(row) {
  if (!isP25(row)) return trunkedSiteLabel(row);
  const identity = `${hex(row.rfss, 2)}-${hex(row.site, 2)}`;
  return row.channel_name || `${systemLabel(row)} ${identity}`;
}

function normalizedSiteText(value) {
  return String(value || '').trim();
}

function sameSiteText(left, right) {
  return Boolean(left && right) && left.localeCompare(right, undefined, { sensitivity: 'base' }) === 0;
}

function configuredSiteValue(row) {
  return normalizedSiteText(row?.configured_site);
}

function configuredNameValue(row) {
  const configured = normalizedSiteText(row?.configured_name);
  if (configured || !row || Object.prototype.hasOwnProperty.call(row, 'configured_name')) return configured;
  return normalizedSiteText(row.configured_site) ? normalizedSiteText(row.channel_name) : '';
}

function siteInfoSiteValue(row) {
  const site = configuredSiteValue(row);
  return site || (configuredNameValue(row) ? '' : normalizedSiteText(row?.channel_name));
}

function siteDisplayParts(row) {
  const site = configuredSiteValue(row);
  const name = configuredNameValue(row);
  const primary = name || site || normalizedSiteText(row?.source_label) || observedSiteLabel(row);
  return { primary, secondary: site && !sameSiteText(site, primary) ? site : '' };
}

function neighborSiteDisplayParts(row) {
  const site = normalizedSiteText(row?.neighbor_configured_site) ||
    normalizedSiteText(row?.neighbor_site_name) || normalizedSiteText(row?.neighbor_channel_name) ||
    normalizedSiteText(row?.neighbor_name);
  const name = normalizedSiteText(row?.neighbor_configured_name);
  const primary = name || site;
  return { primary, secondary: site && !sameSiteText(site, primary) ? site : '' };
}

function neighborSiteId(row) {
  return row?.site_id ?? row?.site;
}

function siteNameSummaryValue(primary, secondary, target = '') {
  if (!primary) return '';
  const summary = node('span', 'site-name-summary');
  summary.title = [primary, secondary].filter(Boolean).join(' · ');
  const heading = node('span', 'site-name-summary-primary');
  heading.append(target ? anchor(primary, target) : valueNode(primary));
  summary.append(heading);
  if (secondary) summary.append(node('small', 'site-name-summary-context', secondary));
  return summary;
}

function siteNameSummary(row, linked = true) {
  const labels = siteDisplayParts(row);
  const target = linked && row?.guid && capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS) ?
    href('site', { guid: row.guid, tab: 'info' }) : '';
  return siteNameSummaryValue(labels.primary, labels.secondary, target);
}

function authoritativePresence(row) {
  const presence = row?.presence;
  const evidence = String(presence?.evidence || '').trim().toLowerCase();
  const confirmedAt = Number(presence?.confirmed_at_ms);
  const site = presence?.site;
  if (!['registration', 'affiliation'].includes(evidence) ||
      !Number.isFinite(confirmedAt) || confirmedAt <= 0 || !site || typeof site !== 'object' ||
      Array.isArray(site) || !normalizedSiteText(site.protocol) ||
      (!identifierNumber(site.site_id) && !normalizedSiteText(site.guid))) return null;
  return { evidence, confirmed_at_ms: confirmedAt, site };
}

function presenceSiteIdentity(site) {
  if (!site) return '';
  if (isP25(site)) {
    const values = [];
    const rfss = hex(site.rfss, 2);
    const siteId = hex(site.site_id, 2);
    if (rfss) values.push(`RFSS ${rfss}`);
    if (siteId) values.push(`Site ${siteId}`);
    return values.join(' · ') || normalizedSiteText(site.guid);
  }
  const siteId = identifierNumber(site.site_id);
  return siteId ? `Site ${siteId}` : normalizedSiteText(site.guid);
}

function presenceSiteContext(site) {
  return normalizedSiteText(site?.configured_site) || normalizedSiteText(site?.configured_name) ||
    normalizedSiteText(site?.channel_name);
}

function presenceSiteSortValue(row) {
  const presence = authoritativePresence(row);
  if (!presence) return '';
  return `${presenceSiteIdentity(presence.site)}\u0000${presenceSiteContext(presence.site)}`;
}

function sitePresenceCell(row, showConfirmation = true) {
  const presence = authoritativePresence(row);
  if (!presence) return '—';
  const identity = presenceSiteIdentity(presence.site);
  const configured = presenceSiteContext(presence.site);
  const summary = node('span', 'site-name-summary');
  const primary = node('span', 'site-name-summary-primary');
  primary.append(siteLink(presence.site, identity));
  summary.append(primary);
  if (configured || showConfirmation) {
    const context = node('small', 'site-name-summary-context');
    if (configured) context.append(configured);
    if (showConfirmation) {
      if (configured) context.append(' · ');
      context.append('Confirmed ', dateTime(presence.confirmed_at_ms));
    }
    summary.append(context);
  }
  summary.title = [identity, configured,
    `${semanticLabel(presence.evidence)} confirmed ${exactDateTime(presence.confirmed_at_ms)}`]
    .filter(Boolean).join(' · ');
  return summary;
}

function siteLabel(row) {
  return siteDisplayParts(row).primary;
}

function siteValue(row) {
  return siteLabel(row);
}

function protocolFamily(row) {
  return protocol(row?.protocol);
}

function isP25(row) {
  return protocolFamily(row) === 'P25';
}

function identifierNumber(value) {
  const numeric = Number(value);
  return value === null || value === undefined || value === '' || !Number.isFinite(numeric) || numeric < 0 ?
    '' : String(Math.trunc(numeric));
}

function identityNumber(row, value) {
  const numeric = Number(value);
  if (protocolFamily(row) === 'NXDN' && row?.address_domain === 'nxdn_type_d' &&
      Number.isInteger(numeric) && numeric >= 0 && numeric <= 0xFFFF) {
    return `${String((numeric >> 11) & 0x1F).padStart(2, '0')}-${
      String(numeric & 0x7FF).padStart(4, '0')}`;
  }
  return identifierNumber(value);
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
  return raw ? raw.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g,
    (character) => character.toUpperCase()) : '';
}

function semanticLabel(value) {
  return String(value || '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g,
    (character) => character.toUpperCase());
}

function identityDomainLabel(row) {
  const value = row?.address_domain || row?.model || row?.location_category || '';
  return String(value).toLowerCase().replace(/^(dmr|nxdn)_/, '').replace(/_/g, ' ').replace(/\b\w/g,
    (character) => character.toUpperCase());
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

function scope(row) {
  return { scope: row?.scope_token || '' };
}

function systemApiPath(scopeToken, child = '') {
  const base = `/api/v1/systems/${encodeURIComponent(String(scopeToken || ''))}`;
  return child ? `${base}/${child}` : base;
}

function groupIdentityApiPath(scopeToken, kind, id, child = '') {
  const identityKind = kind === 'patch_group' ? 'patch_group' : 'talkgroup';
  const base = `${systemApiPath(scopeToken, 'group-identities')}/${identityKind}/${encodeURIComponent(String(id))}`;
  return child ? `${base}/${child}` : base;
}

function siteApiPath(guid, child = '') {
  const base = `/api/v1/sites/${encodeURIComponent(String(guid || ''))}`;
  return child ? `${base}/${child}` : base;
}

function conventionalApiPath(contextKey, child = '') {
  const base = `/api/v1/conventional-contexts/${encodeURIComponent(String(contextKey || ''))}`;
  return child ? `${base}/${child}` : base;
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
  if (route.get('view') === 'aliases' && !Object.prototype.hasOwnProperty.call(overrides, 'alias')) {
    parameters.delete('alias');
  }
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

function exportCsvHref(dataset, context = {}) {
  const parameters = new URLSearchParams();
  Object.entries(context).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') parameters.set(key, String(value));
  });
  ['q', 'sort', 'direction'].forEach((key) => {
    const value = route.get(key);
    if (value) parameters.set(key, value);
  });
  const path = `/api/v1/exports/${encodeURIComponent(String(dataset))}.csv`;
  return `${path}${parameters.size ? `?${parameters}` : ''}`;
}

function exportCsvLink(dataset, context = {}) {
  if (!capabilityAllowed(ACCESS_CAPABILITIES.CSV_EXPORT)) {
    const disabled = node('span', 'button secondary disabled export-csv-action', 'Export CSV');
    disabled.setAttribute('aria-disabled', 'true');
    disabled.title = accessSession.authenticated ? 'CSV export is not available to this account.' :
      'Sign in to use CSV export.';
    return disabled;
  }
  const link = anchor('Export CSV', exportCsvHref(dataset, context), 'button secondary export-csv-action');
  link.setAttribute('download', '');
  link.setAttribute('aria-label', `Export ${dataset.replace(/-/g, ' ')} as CSV`);
  return link;
}

function aliasListLink(name, id) {
  const label = String(name || '').trim();
  const aliasListId = Number(id);
  if (!label || !Number.isInteger(aliasListId) || aliasListId <= 0 ||
      !aliasAdminAllowed()) return label;
  return anchor(label, href('aliases', { list: aliasListId }));
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
  if (!row?.scope_token || !capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)) return label;
  return anchor(label, href('system', { ...scope(row), tab: 'info' }));
}

function siteLink(row, label = siteValue(row)) {
  if (!row?.guid || !capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)) return label;
  return anchor(label, href('site', { guid: row.guid, tab: 'info' }));
}

function neighborSiteLink(row) {
  const labels = neighborSiteDisplayParts(row);
  const target = labels.primary && row.neighbor_guid && capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS) ?
    href('site', { guid: row.neighbor_guid, tab: 'info' }) : '';
  return siteNameSummaryValue(labels.primary, labels.secondary, target);
}

function identityKind(value) {
  const kind = String(value || '').trim().toLowerCase();
  return ['talkgroup', 'radio', 'patch_group', 'unknown'].includes(kind) ? kind : '';
}

function rowGroupIdentityKind(row, explicitKind) {
  return identityKind(explicitKind ?? row?.identity_kind ?? row?.target_kind) || 'talkgroup';
}

function groupIdentityLabel(row, explicitKind, compact = true) {
  const kind = rowGroupIdentityKind(row, explicitKind);
  if (kind === 'patch_group') return compact ? 'Patch' : 'Patch Group';
  if (kind === 'radio') return 'Radio';
  if (kind === 'unknown') return compact ? 'Unknown' : 'Unknown Identity';
  return compact ? 'TG' : 'Talkgroup';
}

function talkgroupLink(row, id = row.talkgroup_id, label, explicitKind) {
  if (id === null || id === undefined || !row?.scope_token ||
      !capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)) return label || identityNumber(row, id);
  const kind = rowGroupIdentityKind(row, explicitKind) === 'patch_group' ? 'patch_group' : null;
  return anchor(label || identityNumber(row, id),
    href('talkgroup', { ...scope(row), id, kind, tab: 'info' }));
}

function radioLink(row, id = row.radio_id, label) {
  if (id === null || id === undefined || !row?.scope_token ||
      !capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)) return label || identityNumber(row, id);
  return anchor(label || identityNumber(row, id), href('radio', { ...scope(row), id, tab: 'info' }));
}

function talkgroupAliasLink(row, id, prefix = 'alias_', explicitKind) {
  if (id === null || id === undefined) return '';
  const name = row[`${prefix}name`];
  return name ? talkgroupLink(row, id, name, explicitKind) : '';
}

function affiliationTalkgroupCell(row) {
  const id = row?.affiliated_talkgroup_id;
  if (id === null || id === undefined) return '—';
  const alias = String(row.affiliated_talkgroup_alias_name || '').trim();
  const identifier = `TG ${identityNumber(row, id)}`;
  const summary = node('span', 'site-name-summary');
  const primary = node('span', 'site-name-summary-primary');
  primary.append(talkgroupLink(row, id, alias || identifier));
  summary.append(primary);
  if (alias) summary.append(node('small', 'site-name-summary-context', identifier));
  summary.title = alias ? `${alias} · ${identifier}` : identifier;
  return summary;
}

function affiliationTalkgroupSortValue(row) {
  return `${row?.affiliated_talkgroup_alias_name || ''}\u0000${identityNumber(row,
    row?.affiliated_talkgroup_id)}`;
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

function closeReadOnlyModal(updateRoute = false, force = false) {
  const active = activeReadOnlyModal;
  if (!active) return true;
  if (!force && active.isDirty?.() && !window.confirm('Discard your unsaved changes?')) return false;
  activeReadOnlyModal = null;
  document.removeEventListener('keydown', active.keydown);
  active.cleanup?.();
  active.backdrop.remove();
  document.body.classList.remove('modal-open');
  if (updateRoute) {
    let changed = false;
    if (route.has('alias')) {
      route.delete('alias');
      changed = true;
    }
    ALIAS_CREATE_ROUTE_KEYS.forEach((key) => {
      if (route.has(key)) {
        route.delete(key);
        changed = true;
      }
    });
    if (changed) window.history.replaceState({}, '', currentHref());
  }
  const returnFocus = active.returnFocusSelector ? document.querySelector(active.returnFocusSelector) : null;
  if (returnFocus instanceof HTMLElement) returnFocus.focus();
  return true;
}

function openReadOnlyModal(title, body, options = {}) {
  if (!closeReadOnlyModal(false)) return null;
  const backdrop = node('div', 'modal-backdrop');
  const dialog = node('section', 'read-only-modal');
  String(options.className || '').split(/\s+/).filter(Boolean)
    .forEach((className) => dialog.classList.add(className));
  const titleId = `read-only-modal-title-${String(options.id || 'detail').replace(/[^a-z0-9-]/gi, '')}`;
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  dialog.setAttribute('aria-labelledby', titleId);
  const header = node('header', 'modal-header');
  const heading = node('h2', '', title);
  heading.id = titleId;
  const close = node('button', 'button secondary modal-close', 'Close');
  close.type = 'button';
  close.setAttribute('aria-label', `Close ${title}`);
  header.append(heading, close);
  const contentNode = node('div', 'modal-content');
  contentNode.append(valueNode(body));
  dialog.append(header, contentNode);
  backdrop.append(dialog);

  const dismiss = () => closeReadOnlyModal(true);
  const focusable = () => [...dialog.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), ' +
    '[tabindex]:not([tabindex="-1"])')]
    .filter((element) => !element.hidden && element.getClientRects().length > 0);
  const keydown = (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      dismiss();
      return;
    }
    if (event.key !== 'Tab') return;
    const values = focusable();
    if (!values.length) {
      event.preventDefault();
      dialog.focus();
      return;
    }
    const first = values[0];
    const last = values[values.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };
  close.addEventListener('click', dismiss);
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) dismiss();
  });
  let dirty = false;
  activeReadOnlyModal = {
    backdrop, keydown, returnFocusSelector: options.returnFocusSelector || null,
    isDirty: () => dirty,
    cleanup: options.cleanup || null
  };
  document.addEventListener('keydown', keydown);
  document.body.classList.add('modal-open');
  document.body.append(backdrop);
  close.focus();
  return {
    dialog, content: contentNode, close: dismiss, state: activeReadOnlyModal,
    setDirty: (value = true) => { dirty = Boolean(value); },
    isDirty: () => dirty
  };
}

function statsLoggingState() {
  const current = serviceStatus?.stats_logging;
  const database = serviceStatus?.database || {};
  const logger = new Map((database.logger || []).map((row) => [row.key, row.value]));
  const persistedLastWrite = Number(logger.get('last_successful_write_ms') || 0);
  if (current) {
    return {
      available: true,
      summaryConfigured: Boolean(current.summary_configured),
      historyConfigured: Boolean(current.detailed_history_configured),
      summaryActive: Boolean(current.summary_active),
      historyActive: Boolean(current.detailed_history_active),
      historyRetained: Boolean(database.detailed_history_available),
      lastHistoryMs: Number(database.last_detailed_history_ms || 0),
      lastSuccessfulWriteMs: Math.max(Number(current.last_successful_write_ms || 0), persistedLastWrite),
      state: String(current.state || ''),
      lastError: current.last_error || ''
    };
  }
  if (serviceStatus) {
    const summary = Boolean(database.stats_logging_enabled);
    const history = Boolean(database.detailed_history_enabled);
    return { available: true, summaryConfigured: summary, historyConfigured: history,
      summaryActive: summary, historyActive: summary && history,
      historyRetained: Boolean(database.detailed_history_available),
      lastHistoryMs: Number(database.last_detailed_history_ms || 0), lastSuccessfulWriteMs: persistedLastWrite,
      state: summary ? 'RUNNING' : 'DISABLED', lastError: '' };
  }
  return { available: false, summaryConfigured: false, historyConfigured: false,
    summaryActive: false, historyActive: false, historyRetained: false, lastHistoryMs: 0,
    lastSuccessfulWriteMs: 0, state: '', lastError: '' };
}

function beginServiceStatusRequest() {
  serviceStatusRequestPending = true;
}

function acceptServiceStatus(value) {
  serviceStatus = value;
  serviceStatusRequestPending = false;
  serviceStatusConsecutiveFailures = 0;
}

function rejectServiceStatusRequest() {
  serviceStatusRequestPending = false;
  serviceStatusConsecutiveFailures += 1;
}

function clearServiceStatus() {
  serviceStatus = null;
  serviceStatusRequestPending = false;
  serviceStatusConsecutiveFailures = 0;
}

function serviceStatusWarningRequired() {
  return serviceStatusConsecutiveFailures >= SERVICE_STATUS_FAILURE_WARNING_THRESHOLD;
}

function serviceStatusRetryDelay(milliseconds) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

async function requestServiceStatus() {
  const attempts = serviceStatus ? 1 : SERVICE_STATUS_INITIAL_ATTEMPTS;
  let lastError = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    beginServiceStatusRequest();
    try {
      const value = await api('/api/v1/status', {}, { page: false });
      acceptServiceStatus(value);
      return value;
    } catch (error) {
      lastError = error;
      rejectServiceStatusRequest();
    }
    if (attempt < attempts) await serviceStatusRetryDelay(SERVICE_STATUS_RETRY_DELAY_MS * attempt);
  }
  throw lastError;
}

function detailedHistoryAvailable() {
  const logging = statsLoggingState();
  return !logging.available || logging.historyActive || logging.historyRetained;
}

function databaseLoggingNotice(view) {
  if (tableOnly || ['live', 'scanner', 'configuration', 'hardware', 'tuner-spectrum', 'admin', 'credits']
    .includes(view)) return null;
  if (accessSessionAvailable && !capabilityAllowed(ACCESS_CAPABILITIES.DASHBOARD)) return null;
  const logging = statsLoggingState();
  if (serviceStatusWarningRequired()) return node('div', 'logging-notice warning',
    'Logging status is unavailable. Database-backed views may not be current.');
  if (!logging.available) return null;
  if (!logging.summaryActive) {
    const state = logging.summaryConfigured && logging.state ? ` (${logging.state.toLowerCase()})` : '';
    const message = logging.summaryConfigured ?
      `Summary logging is not running${state}. Database-backed views remain available but are not updating.` :
      'Summary logging is off. Database-backed views remain available but are not updating.';
    const detail = logging.summaryConfigured && logging.lastError ? ` ${logging.lastError}` : '';
    const lastWrite = logging.lastSuccessfulWriteMs ?
      ` Last successful summary write: ${exactDateTime(logging.lastSuccessfulWriteMs)}.` : '';
    return node('div', 'logging-notice warning', `${message}${detail}${lastWrite}`);
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

function section(title, child, action = null) {
  const wrapper = node('section', 'section');
  const titleBar = node('div', 'section-title', title);
  if (action) titleBar.append(action);
  wrapper.append(titleBar);
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
    const current = tableWidthPreferences[tableType];
    const saved = current && typeof current === 'object' ? { ...current } : {};
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

  const grouped = columns.some((column) => column.group);
  if (grouped) {
    const groupRow = node('tr', 'table-group-row');
    let index = 0;
    while (index < columns.length) {
      const label = columns[index].group || '';
      let end = index + 1;
      while (end < columns.length && (columns[end].group || '') === label) end += 1;
      const groupHeader = node('th', 'table-group-header', label);
      groupHeader.colSpan = end - index;
      groupHeader.scope = 'colgroup';
      groupRow.append(groupHeader);
      index = end;
    }
    head.append(groupRow);
  }
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
    replaceRows(rows) {
      dataRows = [...(rows || [])];
      renderBody();
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
    const displayed = node('strong');
    displayed.append(valueNode(displayValue === undefined ? number(value) : displayValue));
    metric.append(node('span', '', label), displayed);
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

function pager(page, position = 'bottom', itemLabel = 'Rows') {
  const bar = node('nav', `pager pager-${position}`);
  bar.setAttribute('aria-label', `${position === 'top' ? 'Top' : 'Bottom'} table pagination`);
  const { offset, limit } = page;
  const firstRow = offset + (page.rows.length ? 1 : 0);
  const lastRow = offset + page.rows.length;
  const totalCount = page.total_count;
  const range = Number.isInteger(totalCount) ?
    `${itemLabel} ${number(firstRow)}-${number(lastRow)} of ${number(totalCount)}` :
    `${itemLabel} ${number(firstRow)}-${number(lastRow)}`;
  bar.append(node('span', 'muted', range));
  bar.append(offset > 0 ? anchor('Previous', currentHref({ offset: Math.max(0, offset - limit) }), 'button secondary') :
    node('span', 'button disabled', 'Previous'));
  bar.append(page.has_more ? anchor('Next', currentHref({ offset: page.next_offset }), 'button secondary') :
    node('span', 'button disabled', 'Next'));
  return bar;
}

function pagedTableContent(page, columns, tableType, options = {}) {
  const itemLabel = options.itemLabel || 'Rows';
  const result = fragment();
  if (options.topPager) result.append(pager(page, 'top', itemLabel));
  result.append(table(page.rows, columns, options.emptyText || 'No rows', {
    type: tableType,
    serverSort: true,
    defaultSort: SERVER_TABLE_DEFAULT_SORTS[tableType],
    defaultDirection: 'desc',
    ...(options.tableOptions || {})
  }));
  result.append(pager(page, 'bottom', itemLabel));
  return result;
}

function pagedSection(title, page, columns, searchPlaceholder, tableType, action = null, options = {}) {
  return fragment(searchPlaceholder ? searchBar(searchPlaceholder) : null,
    section(title, pagedTableContent(page, columns, tableType, options), action));
}

function availableValue(value) {
  return value === null || value === undefined || value === '' ? '—' : String(value);
}

function aliasMetricValue(row, field) {
  if (!Object.prototype.hasOwnProperty.call(row || {}, field) || row[field] === null || row[field] === undefined) {
    return '—';
  }
  const value = Number(row[field]);
  return Number.isFinite(value) ? number(value) : '—';
}

function aliasMetricTime(row, field) {
  if (!Object.prototype.hasOwnProperty.call(row || {}, field) || row[field] === null || row[field] === undefined) {
    return '—';
  }
  return Number(row[field]) > 0 ? dateTime(row[field]) : '—';
}

function aliasMetricsState(value) {
  return ({
    observed: 'Observed',
    covered_no_evidence: 'Covered · no evidence',
    not_collected: 'Not collected',
    unsupported: 'Unsupported'
  })[String(value || '').toLowerCase()] || '—';
}

function aliasBehavior(row) {
  const values = [];
  const scanLists = Array.isArray(row.scan_lists) ? row.scan_lists.filter(Boolean) : [];
  if (scanLists.length) {
    values.push(badge(scanLists.length === 1 ? `Scan · ${scanLists[0]}` : `Scan Lists ×${scanLists.length}`,
      'state-current', scanLists.join(', ')));
  }
  if (Number(row.record_enabled)) values.push(badge('Record', 'state-current'));
  const destinations = Array.isArray(row.broadcast_channels) ? row.broadcast_channels.length : 0;
  if (destinations) values.push(badge(`Stream ×${identifierNumber(destinations)}`, 'state-current'));
  if (row.stream_as_talkgroup !== null && row.stream_as_talkgroup !== undefined) {
    values.push(badge(`As TG ${identifierNumber(row.stream_as_talkgroup)}`));
  }
  return values.length ? badgeGroup(values) : badge('No call actions', 'state-historical');
}

function aliasDetailLink(row) {
  const id = Number(row.alias_id);
  const label = String(row.name || '').trim() || `Alias ${identifierNumber(id)}`;
  if (!Number.isInteger(id) || id <= 0) return label;
  const link = anchor(label, aliasEditorRowHref(row), 'alias-detail-link');
  link.dataset.aliasId = String(id);
  return link;
}

function aliasEditorRowHref(row) {
  const id = Number(row?.alias_id);
  if (aliasEditorContext?.scanListScope) {
    return href('aliases', {
      list: Number(row?.alias_list_id), aliasTab: 'configure', alias: id
    });
  }
  return currentHref({ alias: id });
}

function aliasListCatalogLink(row) {
  return aliasListLink(row.alias_list_name, row.alias_list_id) || '—';
}

function aliasCatalogCoreColumns() {
  return [
    { id: 'alias-list', label: 'Alias List', group: 'Configuration', render: aliasListCatalogLink,
      className: 'alias-cell', sort: 'list', sortValue: (row) => row.alias_list_name || '' },
    { id: 'family', label: 'Family', group: 'Configuration', key: 'family', sort: 'family' },
    { id: 'matcher', label: 'Matcher', group: 'Configuration', render: (row) =>
      availableValue(row.matcher_label || row.matcher_type), sort: 'matcher',
      sortValue: (row) => row.matcher_label || row.matcher_type || '' },
    { id: 'identifier', label: 'Identifier', group: 'Configuration', render: (row) =>
      availableValue(row.identifier_display), sort: 'value', className: 'numeric',
      sortValue: (row) => row.identifier_display || '' },
    { id: 'alias', label: 'Alias', group: 'Configuration', render: aliasDetailLink,
      className: 'alias-cell', sort: 'name', sortValue: (row) => row.name || '' },
    { id: 'description', label: 'Description', group: 'Configuration', render: (row) =>
      availableValue(row.description), className: 'alias-cell' },
    { id: 'group', label: 'Group', group: 'Configuration', render: (row) =>
      availableValue(row.group), className: 'alias-cell', sort: 'group' },
    { id: 'behavior', label: 'Behavior', group: 'Configuration', render: aliasBehavior }
  ];
}

function aliasJoinedValues(values) {
  return Array.isArray(values) && values.length ? values.join(', ') : '—';
}

function aliasCustomConfigurationColumns() {
  const raw = (id, label, field, fullLabel = '') => ({
    id, label, field, group: 'Raw Matcher Values', fullLabel,
    render: (row) => aliasRawValue(row[field]),
    sortValue: (row) => row[field] === null || row[field] === undefined ? '' : row[field]
  });
  return [
    { id: 'alias-id', label: 'Alias ID', group: 'Identity', fullLabel: 'Durable Alias identifier',
      render: (row) => aliasRawValue(row.alias_id), className: 'numeric' },
    { id: 'alias-list', label: 'Alias List', group: 'Identity', render: aliasListCatalogLink,
      className: 'alias-cell', sortValue: (row) => row.alias_list_name || '' },
    { id: 'alias-list-id', label: 'Alias List ID', group: 'Identity',
      render: (row) => aliasRawValue(row.alias_list_id), className: 'numeric' },
    { id: 'family', label: 'Family', group: 'Identity', render: (row) => availableValue(row.family) },
    { id: 'alias', label: 'Alias', group: 'Identity', render: aliasDetailLink,
      className: 'alias-cell', sortValue: (row) => row.name || '' },
    { id: 'description', label: 'Description', group: 'Appearance', render: (row) =>
      availableValue(row.description), className: 'alias-cell' },
    { id: 'group', label: 'Group', group: 'Appearance', render: (row) =>
      availableValue(row.group), className: 'alias-cell' },
    { id: 'color', label: 'Color', group: 'Appearance', render: aliasColorValue },
    { id: 'icon', label: 'Icon', group: 'Appearance', render: (row) => availableValue(row.icon_name) },
    { id: 'matcher', label: 'Matcher', group: 'Matcher', render: (row) =>
      availableValue(row.matcher_label || row.matcher_type) },
    { id: 'matcher-type', label: 'Matcher Type', group: 'Matcher', render: (row) =>
      availableValue(row.matcher_type) },
    { id: 'identity-type', label: 'Identity Type', group: 'Matcher', render: (row) =>
      availableValue(row.identity_type) },
    { id: 'protocol', label: 'Protocol', group: 'Matcher', render: (row) => availableValue(row.protocol) },
    { id: 'protocol-variant', label: 'Protocol Variant', group: 'Matcher', render: (row) =>
      availableValue(row.protocol_variant) },
    { id: 'identifier', label: 'Identifier', group: 'Matcher', render: (row) =>
      availableValue(row.identifier_display), className: 'numeric' },
    { id: 'exact', label: 'Exact', group: 'Matcher', render: (row) =>
      row.exact === null || row.exact === undefined ? '—' : yesNoKnown(row.exact) },
    { id: 'ranged', label: 'Ranged', group: 'Matcher', render: (row) =>
      row.ranged === null || row.ranged === undefined ? '—' : yesNoKnown(row.ranged) },
    raw('value', 'Value', 'value'),
    raw('minimum', 'Minimum', 'min_value'),
    raw('maximum', 'Maximum', 'max_value'),
    { id: 'text-value', label: 'Text Value', group: 'Raw Matcher Values', render: (row) =>
      availableValue(row.text_value) },
    raw('numeric-value', 'Numeric Value', 'numeric_value'),
    { id: 'tone-sequence', label: 'Tone Sequence', group: 'Raw Matcher Values', render: (row) =>
      availableValue(row.tone_sequence) },
    { id: 'scan-lists', label: 'Scan Lists', group: 'Call Handling', render: (row) =>
      aliasJoinedValues(row.scan_lists), className: 'alias-cell' },
    { id: 'record', label: 'Record', group: 'Call Handling', render: (row) => yesNoKnown(row.record_enabled) },
    { id: 'broadcast-channels', label: 'Stream Destinations', group: 'Call Handling', render: (row) =>
      aliasJoinedValues(row.broadcast_channels), className: 'alias-cell' },
    { id: 'stream-as-talkgroup', label: 'Stream as Talkgroup', group: 'Call Handling', render: (row) =>
      aliasRawValue(row.stream_as_talkgroup) },
    { id: 'behavior', label: 'Behavior', group: 'Call Handling', render: aliasBehavior },
    { id: 'overlap', label: 'Overlap', group: 'Validation', render: (row) => row.overlap ?
      badge('Conflict', 'state-stale', 'This identifier overlaps another alias') : '—' }
  ];
}

function aliasCatalogEnrichmentColumns() {
  const count = (id, label, field, group, fullLabel, sort = field) => ({
    id, label, field, group, fullLabel, sort,
    render: (row) => aliasMetricValue(row, field), className: 'numeric',
    sortValue: (row) => row[field] === null || row[field] === undefined ? -1 : Number(row[field])
  });
  const evidence = 'Signaling / Relationship Evidence';
  return [
    count('calls', 'Calls', 'call_count', 'Call Activity',
      'Call observations associated with this alias. 0 means coverage was collected and no calls were observed.'),
    count('recorded', 'Recorded', 'recorded_count', 'Call Activity',
      'Recorded call observations associated with this alias.'),
    count('streamed', 'Sent', 'streamed_count', 'Call Activity',
      'Call observations sent to at least one configured streamer.'),
    count('encrypted-evidence', 'Enc Obs.', 'encrypted_evidence_count', 'Call Activity',
      'Encrypted observations. This is evidence of encryption, not necessarily a unique completed-call count.'),
    count('grants', 'Grants', 'grant_count', evidence, 'Channel-grant observations.'),
    count('joins', 'Join', 'join_count', evidence, 'Group affiliation or join observations.'),
    count('emergency', 'Emergency', 'emergency_count', evidence, 'Emergency signaling observations.'),
    count('register', 'Register', 'register_count', evidence, 'Unit registration observations.'),
    count('logout', 'Logout', 'logout_count', evidence,
      'Unit deregistration or logout observations. This does not mean a radio left a talkgroup.'),
    count('denial', 'Denial', 'denial_count', evidence, 'Denied service observations.'),
    count('data', 'Data', 'data_count', evidence, 'Data-service observations.'),
    count('other-signaling', 'Other', 'other_signaling_count', evidence,
      'Other signaling observations that do not fit the named categories.'),
    count('relationships', 'Relationships', 'relationship_count', evidence,
      'Distinct retained radio/talkgroup relationship evidence.'),
    count('join-relationships', 'Join Rel.', 'join_relationship_count', evidence,
      'Relationship evidence established by join or affiliation signaling.'),
    count('current-affiliations', 'Current Affil.', 'current_affiliation_count', evidence,
      'Current affiliations. Unsupported protocols show an em dash.'),
    count('covered-scopes', 'Covered', 'coverage_scope_count', evidence,
      'Compatible monitored scopes where this alias could be resolved.', null),
    count('observed-scopes', 'Observed', 'observed_scope_count', evidence,
      'Compatible scopes with retained activity or relationship evidence.', null),
    { id: 'evidence-state', label: 'Evidence', field: 'metrics_state', group: evidence,
      fullLabel: 'Collection coverage state', render: (row) => aliasMetricsState(row.metrics_state),
      sortValue: (row) => row.metrics_state || '' },
    { id: 'first-evidence', label: 'First Evidence', field: 'first_evidence_ms', group: evidence,
      fullLabel: 'First retained activity or relationship evidence', render: (row) =>
        aliasMetricTime(row, 'first_evidence_ms'), sort: 'first_evidence_ms',
      sortValue: (row) => Number(row.first_evidence_ms || 0) },
    { id: 'last-evidence', label: 'Last Evidence', field: 'last_evidence_ms', group: evidence,
      fullLabel: 'Most recent retained activity or relationship evidence',
      render: (row) => aliasMetricTime(row, 'last_evidence_ms'), sort: 'last_evidence_ms',
      sortValue: (row) => Number(row.last_evidence_ms || 0) }
  ];
}

function readAliasCatalogColumnSelection(definitions) {
  const valid = new Set(definitions.map((column) => column.id));
  try {
    const parsed = JSON.parse(window.localStorage.getItem(ALIAS_CATALOG_COLUMNS_STORAGE_KEY));
    if (!Array.isArray(parsed)) return new Set(ALIAS_CATALOG_DEFAULT_ENRICHMENT_COLUMNS);
    const selected = parsed.filter((id) => valid.has(id));
    if (parsed.length && !selected.length) return new Set(ALIAS_CATALOG_DEFAULT_ENRICHMENT_COLUMNS);
    return new Set(selected);
  } catch (error) {
    return new Set(ALIAS_CATALOG_DEFAULT_ENRICHMENT_COLUMNS);
  }
}

function writeAliasCatalogColumnSelection(selected, definitions) {
  const ordered = definitions.map((column) => column.id).filter((id) => selected.has(id));
  try {
    window.localStorage.setItem(ALIAS_CATALOG_COLUMNS_STORAGE_KEY, JSON.stringify(ordered));
  } catch (error) {
    // Browser storage can be disabled; the current page selection still works.
  }
}

function aliasColumnChooser(definitions, selected, onChange) {
  const chooser = node('details', 'column-chooser');
  const summary = node('summary', 'button secondary column-chooser-summary');
  const updateSummary = () => {
    summary.textContent = `Columns · ${number(selected.size)} optional`;
    summary.setAttribute('aria-label', `Choose optional columns; ${selected.size} selected`);
  };
  updateSummary();
  const panel = node('div', 'column-chooser-panel');
  panel.setAttribute('role', 'group');
  panel.setAttribute('aria-label', 'Optional Alias Catalog columns');
  panel.append(node('p', 'column-chooser-help',
    'Choose any Alias configuration, call handling, matcher, activity, signaling, or relationship columns.'));
  const groups = node('div', 'column-chooser-groups');
  const checkboxes = new Map();
  const grouped = new Map();
  definitions.forEach((definition) => {
    if (!grouped.has(definition.group)) grouped.set(definition.group, []);
    grouped.get(definition.group).push(definition);
  });
  grouped.forEach((columns, label) => {
    const fieldset = node('fieldset', 'column-chooser-group');
    fieldset.append(node('legend', '', label));
    columns.forEach((definition) => {
      const item = node('label', 'column-chooser-option');
      const checkbox = node('input');
      checkbox.type = 'checkbox';
      checkbox.checked = selected.has(definition.id);
      checkbox.addEventListener('change', () => {
        if (checkbox.checked) selected.add(definition.id);
        else selected.delete(definition.id);
        writeAliasCatalogColumnSelection(selected, definitions);
        updateSummary();
        onChange(definition);
      });
      checkboxes.set(definition.id, checkbox);
      const copy = node('span');
      copy.append(node('strong', '', definition.label));
      if (definition.fullLabel) copy.append(node('small', '', definition.fullLabel));
      item.append(checkbox, copy);
      fieldset.append(item);
    });
    groups.append(fieldset);
  });
  const controls = node('div', 'column-chooser-controls');
  const applySelection = (ids) => {
    selected.clear();
    ids.forEach((id) => selected.add(id));
    checkboxes.forEach((checkbox, id) => { checkbox.checked = selected.has(id); });
    writeAliasCatalogColumnSelection(selected, definitions);
    updateSummary();
    onChange(null);
  };
  const selectAll = node('button', 'button secondary', 'Select all');
  selectAll.type = 'button';
  selectAll.addEventListener('click', () => applySelection(definitions.map((column) => column.id)));
  const reset = node('button', 'button secondary', 'Reset');
  reset.type = 'button';
  reset.addEventListener('click', () => applySelection(ALIAS_CATALOG_DEFAULT_ENRICHMENT_COLUMNS));
  const done = node('button', 'button secondary', 'Done');
  done.type = 'button';
  done.addEventListener('click', () => {
    chooser.open = false;
    summary.focus();
  });
  controls.append(selectAll, reset, done);
  panel.append(groups, controls);
  chooser.append(summary, panel);
  return chooser;
}

function aliasMatcherOption(value) {
  if (value && typeof value === 'object') {
    const raw = value.value || value.matcher_type || value.id || '';
    return { value: String(raw), label: String(value.label || value.matcher_label || raw) };
  }
  const raw = String(value || '');
  return { value: raw, label: raw.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g,
    (character) => character.toUpperCase()) };
}

function aliasCatalogFilterToolbar(listResponse) {
  const form = node('form', 'toolbar alias-catalog-toolbar');
  form.method = 'get';
  const view = node('input');
  view.type = 'hidden';
  view.name = 'view';
  view.value = 'aliases';
  form.append(view);
  ['sort', 'direction'].forEach((key) => {
    const value = route.get(key);
    if (!value) return;
    const hidden = node('input');
    hidden.type = 'hidden';
    hidden.name = key;
    hidden.value = value;
    form.append(hidden);
  });

  const selectFilter = (label, name, options) => {
    const wrapper = node('label', 'alias-filter');
    wrapper.append(node('span', '', label));
    const select = node('select');
    select.name = name;
    options.forEach(([value, text]) => {
      const option = node('option', '', text);
      option.value = value;
      option.selected = String(route.get(name) || '') === String(value);
      select.append(option);
    });
    wrapper.append(select);
    return wrapper;
  };

  const lists = (listResponse.rows || []).map((row) => [String(row.alias_list_id),
    [row.name, aliasListFamilyLabel(row), `${number(row.alias_count)} aliases`].filter(Boolean).join(' · ')]);
  const preferredFamilies = ['P25', 'DMR', 'NXDN', 'NBFM'];
  const families = [...new Set((listResponse.rows || []).map((row) => String(row.family || '').trim())
    .filter(Boolean))].sort((left, right) => {
    const leftIndex = preferredFamilies.indexOf(left);
    const rightIndex = preferredFamilies.indexOf(right);
    if (leftIndex >= 0 || rightIndex >= 0) {
      return (leftIndex < 0 ? preferredFamilies.length : leftIndex) -
        (rightIndex < 0 ? preferredFamilies.length : rightIndex);
    }
    return left.localeCompare(right);
  });
  form.append(
    selectFilter('Alias List', 'list', [['', 'All alias lists'], ...lists]),
    selectFilter('Family', 'family', [['', 'All families'],
      ...families.map((family) => [family, aliasListFamilyLabel(family)])]),
    selectFilter('Identity', 'type', [['', 'All identities'], ['talkgroup', 'Talkgroups'],
      ['radio', 'Radios'], ['other', 'Other']])
  );
  const matcherOptions = (listResponse.matcher_types || []).map(aliasMatcherOption)
    .filter((option) => option.value).sort((left, right) => left.label.localeCompare(right.label))
    .map((option) => [option.value, option.label]);
  form.append(selectFilter('Matcher', 'matcher', [['', 'All matchers'], ...matcherOptions]));
  const search = node('label', 'alias-filter alias-search-filter');
  search.append(node('span', '', 'Search'));
  const input = node('input');
  input.type = 'search';
  input.name = 'q';
  input.value = route.get('q') || '';
  input.placeholder = 'Alias, description, group, or identifier';
  search.append(input);
  form.append(search, node('button', '', 'Apply'));
  if (['list', 'family', 'type', 'matcher', 'q'].some((key) => route.get(key))) {
    form.append(anchor('Clear', href('aliases'), 'button secondary'));
  }
  form.addEventListener('submit', () => {
    [...form.elements].forEach((control) => {
      if (control.name && control.name !== 'view' && !String(control.value || '').trim()) control.disabled = true;
    });
  });
  return form;
}

function aliasRawValue(value) {
  return value === null || value === undefined || value === '' ? '—' : identifierNumber(value);
}

function aliasColorValue(row) {
  if (row.color === null || row.color === undefined) return '—';
  const value = Number(row.color) >>> 0;
  const hexValue = value.toString(16).toUpperCase().padStart(8, '0');
  const wrapper = node('span', 'alias-color-value');
  const swatch = node('span', 'alias-color-swatch');
  swatch.style.backgroundColor = `#${hexValue.slice(-6)}`;
  wrapper.append(swatch, `#${hexValue}`);
  return wrapper;
}

function aliasDetailMetricBand(row, definitions) {
  return metrics(definitions.map(([label, field]) =>
    [label, row[field] ?? 0, aliasMetricValue(row, field)]), true);
}

function aliasScopeMetricSummary(row, definitions, emptyText) {
  const values = definitions.filter(([, field]) => Number(row[field] || 0) > 0)
    .map(([label, field]) => badge(`${label} ${aliasMetricValue(row, field)}`));
  return values.length ? badgeGroup(values) : node('span', 'muted', emptyText);
}

function aliasEditorScopeBreakdownColumns() {
  const callUse = [['Calls', 'call_count'], ['Rec', 'recorded_count'], ['Sent', 'streamed_count'],
    ['Enc', 'encrypted_evidence_count']];
  const systemEvidence = [['Grants', 'grant_count'], ['Join', 'join_count'], ['Emergency', 'emergency_count'],
    ['Register', 'register_count'], ['Logout', 'logout_count'], ['Denial', 'denial_count'],
    ['Data', 'data_count'], ['Other', 'other_signaling_count'], ['Relationships', 'relationship_count'],
    ['Join Rel.', 'join_relationship_count'], ['Current Affil.', 'current_affiliation_count']];
  const total = (row, definitions) => definitions.reduce((sum, [, field]) => sum + Number(row[field] || 0), 0);
  return [
    { id: 'scope', label: 'Scope', width: 230, className: 'alias-cell', render: (row) => {
      const value = node('div', 'alias-scope-identity');
      value.append(node('strong', '', availableValue(row.scope_label)));
      if (row.topology) value.append(node('span', 'muted', availableValue(row.topology)));
      return value;
    } },
    { id: 'scope-call-use', label: 'Call Use', width: 210,
      render: (row) => aliasScopeMetricSummary(row, callUse, 'No calls observed'),
      sortValue: (row) => total(row, callUse) },
    { id: 'scope-system-evidence', label: 'System Evidence', width: 320,
      render: (row) => aliasScopeMetricSummary(row, systemEvidence, 'No signaling observed'),
      sortValue: (row) => total(row, systemEvidence) },
    { id: 'last-evidence', label: 'Last Evidence', width: 166,
      render: (row) => aliasMetricTime(row, 'last_evidence_ms'),
      sortValue: (row) => Number(row.last_evidence_ms || 0) }
  ];
}

function aliasAdminAllowed() {
  return capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ALIASES);
}

function aliasListId(row) {
  const value = Number(row?.alias_list_id);
  return Number.isInteger(value) && value > 0 ? value : null;
}

function aliasListFamily(row) {
  return String(row?.family || '').trim().toUpperCase();
}

function mergedAliasLists(publicRows, adminRows = []) {
  const adminById = new Map((adminRows || []).map((row) => [aliasListId(row), row]));
  return (publicRows || []).map((row) => ({ ...row, ...(adminById.get(aliasListId(row)) || {}) }))
    .sort((left, right) => String(left.name || '').localeCompare(String(right.name || ''), undefined,
      { numeric: true, sensitivity: 'base' }));
}

function aliasListRail(lists, selectedList) {
  const rail = node('aside', 'alias-list-rail');
  const header = node('div', 'alias-list-rail-header');
  header.append(node('strong', '', 'Alias Lists'));
  const create = node('button', 'button alias-list-create', 'New');
  create.type = 'button';
  create.addEventListener('click', () => openAliasListCreateModal());
  header.append(create);
  const search = node('input', 'alias-list-search');
  search.type = 'search';
  search.placeholder = 'Find a list';
  search.setAttribute('aria-label', 'Find an alias list');
  const list = node('nav', 'alias-list-items');
  list.setAttribute('aria-label', 'Alias lists');
  const draw = () => {
    const query = search.value.trim().toLowerCase();
    const matches = lists.filter((row) => !query || String(row.name || '').toLowerCase().includes(query) ||
      aliasListFamily(row).toLowerCase().includes(query) || aliasListFamilyLabel(row).toLowerCase().includes(query));
    list.replaceChildren();
    if (!matches.length) {
      list.append(node('div', 'empty alias-list-empty', 'No matching alias lists'));
      return;
    }
    matches.forEach((row) => {
      const id = aliasListId(row);
      const link = anchor('', href('aliases', { list: id, aliasTab: 'configure' }), 'alias-list-item');
      if (id === aliasListId(selectedList)) {
        link.classList.add('active');
        link.setAttribute('aria-current', 'page');
      }
      const label = node('span', 'alias-list-item-name', row.name || `Alias List ${identifierNumber(id)}`);
      const detail = node('span', 'alias-list-item-detail');
      detail.append(node('span', 'alias-list-family', aliasListFamilyLabel(row)),
        node('span', '', `${number(row.alias_count || 0)} aliases`));
      link.append(label, detail);
      list.append(link);
    });
  };
  search.addEventListener('input', draw);
  draw();

  const mobile = node('div', 'alias-list-mobile');
  mobile.append(node('span', '', 'Alias List'));
  const select = node('select');
  select.setAttribute('aria-label', 'Alias list');
  const prompt = node('option', '', 'Select an alias list');
  prompt.value = '';
  select.append(prompt);
  lists.forEach((row) => {
    const option = node('option', '', `${row.name} · ${aliasListFamilyLabel(row)} · ${number(row.alias_count || 0)}`);
    option.value = String(aliasListId(row));
    option.selected = aliasListId(row) === aliasListId(selectedList);
    select.append(option);
  });
  select.addEventListener('change', () => {
    if (select.value) window.location.assign(href('aliases', { list: select.value, aliasTab: 'configure' }));
  });
  mobile.append(select);
  const mobileCreate = node('button', 'button secondary alias-list-mobile-create', 'New Alias List');
  mobileCreate.type = 'button';
  mobileCreate.addEventListener('click', () => openAliasListCreateModal());
  mobile.append(mobileCreate);
  rail.append(header, search, list, mobile);
  return rail;
}

function aliasEditorViewTabs(selectedList) {
  const id = aliasListId(selectedList);
  const supportsDiscovery = observedTalkgroupDiscoverySupported(selectedList);
  const allowed = supportsDiscovery ? ['configure', 'discover', 'calls', 'evidence', 'custom'] :
    ['configure', 'calls', 'evidence', 'custom'];
  const active = allowed.includes(route.get('aliasTab')) ?
    route.get('aliasTab') : 'configure';
  const entries = [
    { id: 'configure', label: 'Configure', href: href('aliases', { list: id, aliasTab: 'configure' }) }
  ];
  if (supportsDiscovery) {
    entries.push({ id: 'discover', label: 'Discover', href: href('aliases', { list: id, aliasTab: 'discover' }) });
  }
  entries.push(
    { id: 'calls', label: 'Call Use', href: href('aliases', { list: id, aliasTab: 'calls' }) },
    { id: 'evidence', label: 'System Evidence', href: href('aliases', { list: id, aliasTab: 'evidence' }) },
    { id: 'custom', label: 'Custom', href: href('aliases', { list: id, aliasTab: 'custom' }) }
  );
  return tabs(entries, active);
}

function aliasLocalDateTimeValue(epoch) {
  const value = Number(epoch);
  if (!Number.isFinite(value) || value <= 0) return '';
  const date = new Date(value - new Date(value).getTimezoneOffset() * 60_000);
  return date.toISOString().slice(0, 16);
}

function aliasEditorFilterToolbar(listResponse, options = null) {
  const scanListScope = options?.scan_list_scope === true;
  const form = node('form', 'toolbar alias-catalog-toolbar alias-editor-filter-toolbar');
  form.method = 'get';
  [['view', 'aliases'], ['list', route.get('list')], ['aliasTab', route.get('aliasTab') || 'configure'],
    ['sort', route.get('sort')], ['direction', route.get('direction')]].forEach(([name, value]) => {
    if (!value) return;
    const hidden = node('input');
    hidden.type = 'hidden';
    hidden.name = name;
    hidden.value = value;
    form.append(hidden);
  });
  const selectFilter = (label, name, values) => {
    const wrapper = node('label', 'alias-filter');
    wrapper.append(node('span', '', label));
    const select = node('select');
    select.name = name;
    values.forEach(([value, text]) => {
      const option = node('option', '', text);
      option.value = value;
      option.selected = String(route.get(name) || '') === String(value);
      select.append(option);
    });
    wrapper.append(select);
    return wrapper;
  };
  const search = node('label', 'alias-filter alias-search-filter');
  search.append(node('span', '', 'Search'));
  const input = node('input');
  input.type = 'search';
  input.name = 'q';
  input.value = route.get('q') || '';
  input.placeholder = 'Alias, description, group, or identifier';
  search.append(input);
  const matcherOptions = (listResponse.matcher_types || []).map(aliasMatcherOption)
    .filter((entry) => entry.value).sort((left, right) => left.label.localeCompare(right.label));
  const groupNames = [...new Set((options?.group_names || []).map((value) => String(value || '').trim())
    .filter(Boolean))].sort((left, right) => left.localeCompare(right));
  const groupFilter = aliasTextInput('group', route.get('group') || '');
  groupFilter.placeholder = 'Exact group';
  const groupList = node('datalist');
  groupList.id = 'alias-editor-group-filter-options';
  groupFilter.setAttribute('list', groupList.id);
  groupNames.forEach((value) => {
    const option = node('option');
    option.value = value;
    groupList.append(option);
  });
  const groupFilterWrapper = node('label', 'alias-filter');
  groupFilterWrapper.append(node('span', '', 'Group'), groupFilter);
  const lastAfter = aliasTextInput('', aliasLocalDateTimeValue(route.get('lastActivityAfter')),
    'datetime-local');
  const lastBefore = aliasTextInput('', aliasLocalDateTimeValue(route.get('lastActivityBefore')),
    'datetime-local');
  form.append(search,
    selectFilter('Identity', 'type', [['', 'All identities'], ['talkgroup', 'Talkgroups'],
      ['radio', 'Radios'], ['other', 'Other']]),
    selectFilter('Matcher', 'matcher', [['', 'All matchers'],
      ...matcherOptions.map((entry) => [entry.value, entry.label])]),
    groupFilterWrapper, groupList,
    selectFilter('Scan list', 'scanListId', [
      ...(scanListScope ? [] : [['', 'Any scan list']]),
      ...(options?.scan_lists || []).map((row) => [String(row.id ?? row.scan_list_id),
        `${row.name}${row.published === false ? ' · not published' : ''}`])]),
    selectFilter('Record', 'record', [['', 'Any'], ['enabled', 'Enabled'], ['disabled', 'Disabled']]),
    selectFilter('Stream', 'stream', [['', 'Any'], ['present', 'Configured'], ['none', 'None']]),
    selectFilter('Evidence', 'evidence', [['', 'Any'], ['observed', 'Observed'],
      ['covered_no_evidence', 'Covered · no evidence'], ['not_collected', 'Not collected'],
      ['unsupported', 'Unsupported']]),
    selectFilter('Call use', 'use', [['', 'Any'], ['used', 'Has calls'],
      ['unused', 'No calls observed']]),
    (() => {
      const wrapper = node('label', 'alias-filter');
      wrapper.append(node('span', '', 'Active after'), lastAfter);
      return wrapper;
    })(),
    (() => {
      const wrapper = node('label', 'alias-filter');
      wrapper.append(node('span', '', 'Active before'), lastBefore);
      return wrapper;
    })(),
    node('button', '', 'Apply'));
  const activeFilters = ['q', 'type', 'matcher', 'group', ...(scanListScope ? [] : ['scanListId']),
    'record', 'stream', 'evidence', 'use', 'lastActivityAfter', 'lastActivityBefore'];
  if (activeFilters.some((key) => route.get(key))) {
    form.append(anchor('Clear', href('aliases', {
      list: route.get('list'), aliasTab: route.get('aliasTab') || 'configure',
      scanListId: scanListScope ? route.get('scanListId') : null
    }), 'button secondary'));
  }
  form.addEventListener('submit', () => {
    [[lastAfter, 'lastActivityAfter'], [lastBefore, 'lastActivityBefore']].forEach(([control, name]) => {
      if (!control.value) return;
      const hidden = node('input');
      hidden.type = 'hidden';
      hidden.name = name;
      hidden.value = String(new Date(control.value).getTime());
      form.append(hidden);
    });
    [...form.elements].forEach((control) => {
      if (control.name && !['view', 'list', 'aliasTab'].includes(control.name) &&
          !String(control.value || '').trim()) control.disabled = true;
    });
  });
  return form;
}

function aliasEditorBaseColumns(rows, onSelectionChange) {
  const columns = [{ id: 'select', label: 'Select', group: 'Selection', className: 'alias-select-cell',
    render: (row) => {
      const id = Number(row.alias_id);
      const checkbox = node('input', 'alias-row-select');
      checkbox.type = 'checkbox';
      checkbox.checked = aliasEditorSelection.has(id);
      checkbox.setAttribute('aria-label', `Select ${row.name || `alias ${id}`}`);
      checkbox.addEventListener('click', (event) => {
        const index = rows.findIndex((candidate) => Number(candidate.alias_id) === id);
        if (event.shiftKey && aliasEditorLastSelectionIndex !== null) {
          const start = Math.min(index, aliasEditorLastSelectionIndex);
          const end = Math.max(index, aliasEditorLastSelectionIndex);
          const select = checkbox.checked;
          rows.slice(start, end + 1).forEach((candidate) => {
            const candidateId = Number(candidate.alias_id);
            if (select) aliasEditorSelection.add(candidateId);
            else aliasEditorSelection.delete(candidateId);
          });
        } else if (checkbox.checked) aliasEditorSelection.add(id);
        else aliasEditorSelection.delete(id);
        aliasEditorLastSelectionIndex = index;
        onSelectionChange();
      });
      return checkbox;
    }, sortValue: (row) => aliasEditorSelection.has(Number(row.alias_id)) }];
  columns.push(
    { id: 'alias', label: 'Alias', group: 'Configuration', render: aliasDetailLink,
      className: 'alias-cell', sort: 'name', sortValue: (row) => row.name || '' },
    { id: 'description', label: 'Description', group: 'Configuration', render: (row) =>
      availableValue(row.description), className: 'alias-cell' },
    { id: 'identifier', label: 'Identifier', group: 'Configuration', render: (row) =>
      availableValue(row.identifier_display), sort: 'value', className: 'numeric',
      sortValue: (row) => row.identifier_display || '' },
    { id: 'matcher', label: 'Matcher', group: 'Configuration', render: (row) =>
      availableValue(row.matcher_label || row.matcher_type), sort: 'matcher',
      sortValue: (row) => row.matcher_label || row.matcher_type || '' },
    { id: 'group', label: 'Group', group: 'Configuration', render: (row) =>
      availableValue(row.group), className: 'alias-cell', sort: 'group' }
  );
  return columns;
}

function aliasEditorColumns(view, rows, onSelectionChange, selectedCustom) {
  const base = aliasEditorBaseColumns(rows, onSelectionChange);
  const enrichment = aliasCatalogEnrichmentColumns();
  if (view === 'calls') {
    return [...base, ...enrichment.filter((column) =>
      ['calls', 'recorded', 'streamed', 'encrypted-evidence', 'last-evidence'].includes(column.id))];
  }
  if (view === 'evidence') {
    return [...base, ...enrichment.filter((column) =>
      ['grants', 'joins', 'emergency', 'register', 'logout', 'relationships', 'join-relationships',
        'current-affiliations', 'evidence-state', 'last-evidence'].includes(column.id))];
  }
  if (view === 'custom') {
    const selection = base.filter((column) => column.id === 'select');
    const definitions = [...aliasCustomConfigurationColumns(), ...enrichment];
    return [...selection, ...definitions.filter((column) => selectedCustom.has(column.id))];
  }
  return [...base,
    { id: 'behavior', label: 'Behavior', group: 'Call Handling', render: aliasBehavior },
    { id: 'overlap', label: 'Overlap', group: 'Validation', render: (row) => row.overlap ?
      badge('Conflict', 'state-stale', 'This identifier overlaps another alias') : '—' }];
}

function scanListMemberColumns(rows, onSelectionChange) {
  const columns = aliasEditorBaseColumns(rows, onSelectionChange);
  const aliasIndex = columns.findIndex((column) => column.id === 'alias');
  columns.splice(aliasIndex + 1, 0,
    { id: 'alias-list', label: 'Alias List', group: 'Configuration', render: aliasListCatalogLink,
      className: 'alias-cell', sort: 'list', sortValue: (row) => row.alias_list_name || '' },
    { id: 'family', label: 'Family', group: 'Configuration', key: 'family', sort: 'family' });
  columns.push(
    { id: 'behavior', label: 'Behavior', group: 'Call Handling', render: aliasBehavior },
    { id: 'overlap', label: 'Overlap', group: 'Validation', render: (row) => row.overlap ?
      badge('Conflict', 'state-stale', 'This identifier overlaps another alias') : '—' });
  return columns;
}

function aliasEditorEmptyState(lists) {
  const wrapper = node('section', 'alias-editor-welcome');
  wrapper.append(node('h2', '', lists.length ? 'Select an alias list' : 'No alias lists are configured'),
    node('p', '', lists.length ?
      'Aliases load only after you select a list. This keeps large radio systems responsive.' :
      'Create an alias list to begin organizing talkgroups, radio IDs, and other identifiers.'));
  const create = node('button', 'button', 'Create Alias List');
  create.type = 'button';
  create.addEventListener('click', () => openAliasListCreateModal());
  wrapper.append(create);
  return wrapper;
}

function aliasFormField(label, control, help = '') {
  const wrapper = node('label', 'alias-editor-field');
  wrapper.append(node('span', 'alias-editor-field-label', label), control);
  if (help) wrapper.append(node('small', '', help));
  return wrapper;
}

function aliasCheckOption(labelText, control) {
  const label = node('label', 'alias-check-option');
  label.append(control, node('span', '', labelText));
  return label;
}

function aliasScanListChoices(options, selectedValues = []) {
  const fieldset = node('fieldset', 'alias-stream-options alias-scan-list-options');
  fieldset.append(node('legend', '', 'Scan list membership'));
  const selected = new Set((selectedValues || []).map((value) => Number(value))
    .filter((value) => Number.isInteger(value) && value > 0));
  const scanLists = Array.isArray(options?.scan_lists) ? options.scan_lists : [];
  if (!scanLists.length) {
    fieldset.append(node('div', 'empty', 'No scan lists configured'));
    return fieldset;
  }
  scanLists.forEach((scanList) => {
    const id = Number(scanList?.id ?? scanList?.scan_list_id);
    if (!Number.isInteger(id) || id <= 0) return;
    const label = node('label', 'alias-check-option alias-scan-list-option');
    const checkbox = node('input');
    checkbox.type = 'checkbox';
    checkbox.name = 'scanListId';
    checkbox.value = String(id);
    checkbox.checked = selected.has(id);
    const copy = node('span');
    copy.append(node('strong', '', scanList.name || `Scan list ${id}`));
    const detail = [scanList.description, scanList.published === false ? 'Not published to listeners' : null]
      .filter(Boolean).join(' · ');
    if (detail) copy.append(node('small', '', detail));
    label.append(checkbox, copy);
    fieldset.append(label);
  });
  return fieldset;
}

function selectedAliasScanListIds(root) {
  return [...root.querySelectorAll('[name="scanListId"]:checked')]
    .map((checkbox) => Number(checkbox.value)).filter((value) => Number.isInteger(value) && value > 0);
}

function aliasSelect(name, values, selectedValue = '', includeBlank = false) {
  const select = node('select');
  select.name = name;
  if (includeBlank) {
    const blank = node('option', '', 'None');
    blank.value = '';
    select.append(blank);
  }
  values.forEach((entry) => {
    const value = typeof entry === 'object' ? entry.value : entry;
    const label = typeof entry === 'object' ? entry.label : entry;
    const option = node('option', '', label);
    option.value = String(value ?? '');
    option.selected = String(value ?? '') === String(selectedValue ?? '');
    select.append(option);
  });
  return select;
}

function aliasTextInput(name, value = '', type = 'text') {
  const input = node('input');
  input.type = type;
  input.name = name;
  input.value = value ?? '';
  return input;
}

function aliasModalFooter(...controls) {
  const footer = node('footer', 'alias-modal-footer');
  footer.append(...controls.filter(Boolean));
  return footer;
}

function aliasMutationError(host, error, retry = null) {
  host.replaceChildren();
  const message = node('div', 'error', error.message);
  host.append(message);
  if (error?.status === 409 && error?.code === 'stale_revision') {
    message.append(document.createTextNode(' No changes were saved.'));
    if (retry) {
      const reload = node('button', 'button secondary alias-conflict-reload', 'Reload current values');
      reload.type = 'button';
      reload.addEventListener('click', retry);
      host.append(reload);
    }
  }
}

async function finishAliasMutation(modal, result, routeChanges = {}) {
  if (modal) modal.setDirty(false);
  closeReadOnlyModal(false, true);
  aliasEditorSelection.clear();
  aliasEditorLastSelectionIndex = null;
  Object.entries(routeChanges).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '') route.delete(key);
    else route.set(key, String(value));
  });
  route.delete('alias');
  ALIAS_CREATE_ROUTE_KEYS.forEach((key) => route.delete(key));
  route.delete('offset');
  window.history.replaceState({}, '', currentHref());
  if (result?.revision !== undefined && aliasEditorContext) aliasEditorContext.revision = result.revision;
  await render();
}

function openAliasListCreateModal() {
  const form = node('form', 'alias-editor-form alias-list-form');
  const name = aliasTextInput('name');
  name.required = true;
  name.maxLength = 25;
  const family = aliasSelect('family', Object.entries(ALIAS_LIST_FAMILY_LABELS)
    .map(([value, label]) => ({ value: value.toLowerCase(), label })), 'p25');
  const errorHost = node('div', 'alias-form-message');
  form.append(node('p', 'modal-introduction',
    'A list owns one protocol family. Channels can share the list when their protocol matches.'),
    aliasFormField('List name', name, 'Up to 25 characters'), aliasFormField('Protocol', family), errorHost);
  const cancel = node('button', 'button secondary', 'Cancel');
  cancel.type = 'button';
  const submit = node('button', 'button', 'Create Alias List');
  submit.type = 'submit';
  form.append(aliasModalFooter(cancel, submit));
  const modal = openReadOnlyModal('Create Alias List', form, { id: 'create-alias-list', className: 'alias-editor-modal' });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  form.addEventListener('input', () => modal.setDirty(true));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    submit.disabled = true;
    errorHost.replaceChildren();
    try {
      const result = await requestJson('/api/v1/admin/alias-lists', {
        method: 'POST', body: {
          revision: Number(aliasEditorContext?.revision ?? 0), name: name.value.trim(), family: family.value
        }
      });
      await finishAliasMutation(modal, result, { list: result.alias_list_id, aliasTab: 'configure' });
    } catch (error) {
      aliasMutationError(errorHost, error);
      submit.disabled = false;
    }
  });
  name.focus();
}

async function openAliasListDeleteModal(selectedList) {
  const id = aliasListId(selectedList);
  const loading = node('div', 'loading', 'Checking list usage');
  const modal = openReadOnlyModal(`Delete ${selectedList.name}`, loading, {
    id: `delete-alias-list-${id}`, className: 'alias-editor-modal alias-confirm-modal'
  });
  if (!modal) return;
  try {
    const impact = await requestJson(`/api/v1/admin/alias-lists/${id}/delete-impact`, { csrf: false });
    if (activeReadOnlyModal !== modal.state) return;
    const body = node('div', 'alias-confirmation');
    body.append(node('p', '', `This permanently deletes ${number(impact.alias_count || 0)} aliases.`));
    if (Number(impact.channel_count || 0) > 0) {
      body.append(node('div', 'logging-notice warning',
        `${number(impact.channel_count)} configured channels use this list. Their alias-list assignment will be removed.`));
    }
    const confirm = node('label', 'alias-confirm-check');
    const checkbox = node('input');
    checkbox.type = 'checkbox';
    confirm.append(checkbox, node('span', '', 'I understand this cannot be undone.'));
    const errorHost = node('div', 'alias-form-message');
    const cancel = node('button', 'button secondary', 'Cancel');
    cancel.type = 'button';
    const remove = node('button', 'danger', 'Delete Alias List');
    remove.type = 'button';
    remove.disabled = true;
    checkbox.addEventListener('change', () => { remove.disabled = !checkbox.checked; });
    cancel.addEventListener('click', modal.close);
    remove.addEventListener('click', async () => {
      remove.disabled = true;
      try {
        const result = await requestJson(`/api/v1/admin/alias-lists/${id}`, {
          method: 'DELETE', body: { revision: Number(impact.revision), confirmed: true }
        });
        await finishAliasMutation(modal, result, { list: null, aliasTab: null });
      } catch (error) {
        aliasMutationError(errorHost, error, () => {
          modal.setDirty(false);
          closeReadOnlyModal(false, true);
          openAliasListDeleteModal(selectedList);
        });
        remove.disabled = false;
      }
    });
    body.append(confirm, errorHost, aliasModalFooter(cancel, remove));
    modal.content.replaceChildren(body);
  } catch (error) {
    aliasMutationError(modal.content, error);
  }
}

function aliasNumericValue(value) {
  const text = String(value ?? '').trim();
  if (!text) return null;
  if (/^0x[0-9a-f]+$/i.test(text)) return Number.parseInt(text.slice(2), 16);
  if (/^[0-9]+$/.test(text)) return Number.parseInt(text, 10);
  if (/^[0-9a-f]+$/i.test(text) && /[a-f]/i.test(text)) return Number.parseInt(text, 16);
  return Number.NaN;
}

function aliasColorHex(value) {
  const numeric = Number(value ?? -1) >>> 0;
  return `#${numeric.toString(16).padStart(8, '0').slice(-6)}`;
}

function aliasColorInteger(value) {
  return (0xFF000000 | Number.parseInt(String(value || '#ffffff').slice(1), 16)) | 0;
}

function aliasEditorColorValue(control) {
  const currentHex = String(control?.value || '').toLowerCase();
  const originalHex = String(control?.dataset.originalHex || '').toLowerCase();
  const originalColor = Number(control?.dataset.originalColor);
  return currentHex && currentHex === originalHex && Number.isInteger(originalColor) ?
    originalColor : aliasColorInteger(currentHex);
}

function aliasMatcherKey(value) {
  const type = String(value?.type || '');
  const protocol = String(value?.protocol || '');
  const variant = String(value?.variant || '');
  return protocol ? `${type}:${protocol}${variant ? `:${variant}` : ''}` : type;
}

function aliasMatcherDescriptor(options, keyOrType, protocol = '', variant = '') {
  const matchers = options?.matchers || [];
  const key = String(keyOrType || '');
  const exact = matchers.find((entry) => aliasMatcherKey(entry) === key);
  if (exact) return exact;
  const typed = matchers.find((entry) => {
    if (String(entry.type) !== key) return false;
    if (!protocol || String(entry.protocol) === String(protocol)) return true;
    return false;
  });
  return typed || matchers[0];
}

function aliasMatcherDefault(descriptor, options = {}) {
  const matcher = { type: descriptor?.type };
  if (descriptor?.protocol) matcher.protocol = descriptor.protocol;
  if (descriptor?.variant) matcher.variant = descriptor.variant;
  (descriptor?.fields || []).forEach((field) => {
    if (field === 'tones') matcher.tones = [{ tone: options.tones?.[0] || '', duration: 1 }];
    else if (field === 'code') matcher.code = options.dcs_codes?.[0] || 'n023';
    else if (field === 'esn') matcher.esn = '';
    else matcher[field] = field === 'minimum' ? Number(descriptor.minimum || 0) :
      (field === 'maximum' ? Number(descriptor.minimum || 0) : 0);
  });
  return matcher;
}

function aliasMatcherFields(host, descriptor, matcher, options) {
  host.replaceChildren();
  const fields = descriptor?.fields || [];
  const numberField = (field, label, help = '') => {
    const input = aliasTextInput(`matcher-${field}`, matcher?.[field] ?? '', 'text');
    input.inputMode = 'numeric';
    input.required = true;
    host.append(aliasFormField(label, input, help));
  };
  fields.forEach((field) => {
    if (field === 'value') {
      numberField(field, 'Identifier', descriptor?.minimum !== undefined ?
        `${identifierNumber(descriptor.minimum)} through ${identifierNumber(descriptor.maximum)}` : 'Decimal value');
    } else if (field === 'minimum') numberField(field, 'Minimum identifier');
    else if (field === 'maximum') numberField(field, 'Maximum identifier');
    else if (field === 'status') numberField(field, 'Status', '0 through 255');
    else if (field === 'code') {
      host.append(aliasFormField('DCS code', aliasSelect('matcher-code', options?.dcs_codes || [], matcher?.code)));
    } else if (field === 'esn') {
      const input = aliasTextInput('matcher-esn', matcher?.esn || '');
      input.required = true;
      host.append(aliasFormField('Electronic serial number', input));
    } else if (field === 'tones') {
      const toneHost = node('div', 'alias-tone-list');
      const tones = Array.isArray(matcher?.tones) && matcher.tones.length ? matcher.tones :
        [{ tone: options?.tones?.[0] || '', duration: 1 }];
      const addTone = (tone = {}) => {
        const row = node('div', 'alias-tone-row');
        const toneSelect = aliasSelect('matcher-tone', options?.tones || [], tone.tone || options?.tones?.[0]);
        const duration = aliasTextInput('matcher-tone-duration', tone.duration ?? 1, 'number');
        duration.min = '1';
        duration.max = '50';
        duration.step = '1';
        duration.required = true;
        const remove = node('button', 'button secondary alias-tone-remove', 'Remove');
        remove.type = 'button';
        remove.addEventListener('click', () => row.remove());
        row.append(toneSelect, duration, remove);
        toneHost.append(row);
      };
      tones.forEach(addTone);
      const add = node('button', 'button secondary alias-tone-add', 'Add tone');
      add.type = 'button';
      add.addEventListener('click', () => addTone());
      host.append(aliasFormField('Tone sequence', toneHost, 'Tone and duration pairs in order'), add);
    }
  });
}

function aliasMatcherPayload(form, descriptor) {
  const matcher = { type: descriptor.type };
  if (descriptor.protocol) {
    const selector = form.elements.matcherType;
    const preserveProtocol = selector.value === selector.dataset.originalSelection &&
      String(descriptor.type) === selector.dataset.originalType && selector.dataset.originalProtocol;
    matcher.protocol = preserveProtocol ? selector.dataset.originalProtocol : descriptor.protocol;
    if (preserveProtocol && selector.dataset.originalVariant) matcher.variant = selector.dataset.originalVariant;
    else if (descriptor.variant) matcher.variant = descriptor.variant;
  }
  (descriptor.fields || []).forEach((field) => {
    if (field === 'tones') {
      matcher.tones = [...form.querySelectorAll('.alias-tone-row')].map((row) => ({
        tone: row.querySelector('[name="matcher-tone"]').value,
        duration: Number(row.querySelector('[name="matcher-tone-duration"]').value)
      }));
    } else if (field === 'code') matcher.code = form.elements['matcher-code'].value;
    else if (field === 'esn') matcher.esn = form.elements['matcher-esn'].value.trim();
    else matcher[field] = aliasNumericValue(form.elements[`matcher-${field}`].value);
  });
  return matcher;
}

function aliasUsageContent(response) {
  const alias = response?.alias || {};
  const scopeRows = response?.breakdown || [];
  const wrapper = node('div', 'alias-usage-content');
  wrapper.append(section('Call Use', aliasDetailMetricBand(alias, [
    ['Calls', 'call_count'], ['Recorded', 'recorded_count'], ['Sent', 'streamed_count'],
    ['Enc Obs.', 'encrypted_evidence_count']
  ])));
  wrapper.append(section('System Evidence', fragment(
    aliasDetailMetricBand(alias, [
      ['Grants', 'grant_count'], ['Join', 'join_count'], ['Emergency', 'emergency_count'],
      ['Register', 'register_count'], ['Logout', 'logout_count'], ['Denial', 'denial_count'],
      ['Data', 'data_count'], ['Other', 'other_signaling_count'], ['Relationships', 'relationship_count'],
      ['Join Relationships', 'join_relationship_count'], ['Current Affiliations', 'current_affiliation_count']
    ]),
    keyValues([
      ['Collection State', aliasMetricsState(alias.metrics_state)],
      ['First Evidence', aliasMetricTime(alias, 'first_evidence_ms')],
      ['Last Evidence', aliasMetricTime(alias, 'last_evidence_ms')]
    ]),
    node('p', 'metric-meaning-note',
      'Calls and signaling are separate evidence. An em dash means unavailable or not collected; 0 means the ' +
      'compatible scope was collected and the count was zero.')
  )));
  wrapper.append(section('Scope Breakdown', fragment(
    table(scopeRows, aliasEditorScopeBreakdownColumns(), 'No compatible monitored scopes',
      { type: 'alias-editor-scope-breakdown' }),
    node('p', 'metric-meaning-note',
      'Scope already includes the system and site. Per-scope cells show only positive counts; complete totals remain ' +
      'above, including zero or unavailable metrics.'))));
  return wrapper;
}

function aliasEditorModalTabs(panels, initial = 'basics') {
  const navigation = node('nav', 'tabs alias-modal-tabs');
  navigation.setAttribute('aria-label', 'Alias editor sections');
  const activate = (id) => {
    Object.entries(panels).forEach(([key, panel]) => { panel.hidden = key !== id; });
    [...navigation.children].forEach((button) => {
      const active = button.dataset.tab === id;
      button.classList.toggle('active', active);
      button.setAttribute('aria-selected', String(active));
    });
  };
  [['basics', 'Basics'], ['identifier', 'Identifier'], ['audio', 'Call Handling'],
    ['usage', 'Usage & Evidence']].forEach(([id, label]) => {
    const button = node('button', 'secondary', label);
    button.type = 'button';
    button.dataset.tab = id;
    button.setAttribute('role', 'tab');
    button.addEventListener('click', () => activate(id));
    navigation.append(button);
  });
  activate(initial);
  return navigation;
}

function aliasEditorPayload(form, options) {
  const matcherType = form.elements.matcherType.value;
  const descriptor = aliasMatcherDescriptor(options, matcherType);
  if (!descriptor) throw new Error('Select a supported identifier type');
  const matcher = aliasMatcherPayload(form, descriptor);
  Object.entries(matcher).forEach(([key, value]) => {
    if (key !== 'protocol' && key !== 'type' && (value === null || Number.isNaN(value))) {
      throw new Error('Enter a valid identifier value');
    }
  });
  if (matcher.minimum !== undefined && matcher.maximum !== undefined && matcher.minimum > matcher.maximum) {
    throw new Error('The minimum identifier cannot be greater than the maximum');
  }
  if (matcher.value !== undefined && descriptor.minimum !== undefined && descriptor.maximum !== undefined &&
      (matcher.value < Number(descriptor.minimum) || matcher.value > Number(descriptor.maximum))) {
    throw new Error(`The identifier must be between ${identifierNumber(descriptor.minimum)} and ${
      identifierNumber(descriptor.maximum)}`);
  }
  const streamValue = form.elements.streamAsTalkgroup.value.trim();
  return {
    alias_list_id: Number(form.elements.aliasListId.value),
    name: form.elements.name.value.trim(),
    description: form.elements.description.value.trim(),
    group: form.elements.group.value.trim(),
    color: aliasEditorColorValue(form.elements.color),
    icon_name: form.elements.iconName.value || null,
    scan_list_ids: selectedAliasScanListIds(form),
    recordable: form.elements.recordable.checked,
    broadcast_channels: [...form.querySelectorAll('[name="broadcastChannel"]:checked')]
      .map((checkbox) => checkbox.value),
    stream_as_talkgroup: streamValue ? Number(streamValue) : null,
    matcher
  };
}

async function openAliasEditorModal(mode = 'create', id = null, prefill = null) {
  const editing = mode === 'edit';
  const cloning = mode === 'clone';
  const selectedListId = Number(prefill?.alias_list_id || route.get('list'));
  const loading = node('div', 'loading', editing || cloning ?
    'Loading alias settings' : 'Preparing alias editor');
  const modal = openReadOnlyModal(editing ? `Edit Alias ${identifierNumber(id)}` :
    (cloning ? 'Clone Alias' : 'Add Alias'), loading, {
      id: `${mode}-alias-${id || 'new'}`, className: 'alias-editor-modal alias-record-modal',
      returnFocusSelector: prefill?.returnFocusSelector ||
        (id ? `.alias-detail-link[data-alias-id="${id}"]` : '.alias-add-button')
    });
  if (!modal) return;
  try {
    const recordPromise = editing || cloning ? requestJson(`/api/v1/admin/aliases/${id}`, { csrf: false }) :
      Promise.resolve(null);
    const initialOptionsPromise = requestJson(`/api/v1/admin/aliases/options?alias_list_id=${selectedListId}`,
      { csrf: false });
    const analyticsPromise = editing || cloning ?
      api(`/api/v1/aliases/${encodeURIComponent(String(id))}`).catch(() => null) : Promise.resolve(null);
    const [recordResponse, options, analytics] = await Promise.all([
      recordPromise, initialOptionsPromise, analyticsPromise
    ]);
    if (activeReadOnlyModal !== modal.state) return;
    const source = editing || cloning ? { ...(recordResponse?.alias || {}) } : { ...(prefill || {}) };
    if ((editing || cloning) && Number(source.alias_list_id) !== selectedListId) {
      throw new Error('This alias is no longer in the selected alias list. Reload the list and try again.');
    }
    const revision = Number(recordResponse?.revision ?? options?.revision ??
      aliasEditorContext?.revision ?? 0);
    const currentList = aliasEditorContext?.lists.find((row) => aliasListId(row) ===
      Number(source.alias_list_id || selectedListId)) || aliasEditorContext?.selectedList;
    const family = aliasListFamily(currentList);
    const compatibleLists = (aliasEditorContext?.lists || []).filter((row) => aliasListFamily(row) === family);
    const descriptor = aliasMatcherDescriptor(options, source.matcher?.type, source.matcher?.protocol,
      source.matcher?.variant);
    const initialMatcher = source.matcher || aliasMatcherDefault(descriptor, options);
    const initialType = String(initialMatcher?.type || descriptor?.type || '');
    if (!editing && !cloning && source.recordable === undefined) {
      const defaults = options?.alias_list?.unmatched_talkgroup_policy || {};
      const inherits = ['talkgroup', 'talkgroup_range'].includes(initialType);
      source.recordable = inherits && Boolean(defaults.recordable);
      source.broadcast_channels = inherits ? [...(defaults.broadcast_channels || [])] : [];
      source.scan_list_ids = inherits ? [...(defaults.scan_list_ids || [])] : [];
    }
    const form = node('form', 'alias-editor-form');
    const basics = node('section', 'alias-editor-panel');
    const identifier = node('section', 'alias-editor-panel');
    const audio = node('section', 'alias-editor-panel');
    const usage = node('section', 'alias-editor-panel alias-editor-usage');

    const listSelect = aliasSelect('aliasListId', compatibleLists.map((row) => ({
      value: aliasListId(row), label: row.name
    })), source.alias_list_id || selectedListId);
    if (!editing) listSelect.disabled = true;
    const name = aliasTextInput('name', cloning ? `Copy of ${source.name || ''}` : source.name || '');
    name.required = true;
    name.maxLength = 256;
    const description = node('textarea');
    description.name = 'description';
    description.value = source.description || '';
    description.maxLength = 4096;
    description.rows = 4;
    const group = aliasTextInput('group', source.group || '');
    group.maxLength = 256;
    const groupListId = `alias-group-options-${id || 'new'}`;
    group.setAttribute('list', groupListId);
    const groupList = node('datalist');
    groupList.id = groupListId;
    (options.group_names || []).forEach((value) => {
      const option = node('option');
      option.value = value;
      groupList.append(option);
    });
    const originalColor = Number.isInteger(Number(source.color)) ? Number(source.color) : 0;
    const color = aliasTextInput('color', aliasColorHex(originalColor), 'color');
    color.dataset.originalColor = String(originalColor);
    color.dataset.originalHex = aliasColorHex(originalColor);
    const configuredIcons = new Set(options.icon_names || []);
    const iconEntries = [...(options.icon_names || []).map((value) => ({ value, label: value }))];
    if (source.icon_name && !configuredIcons.has(source.icon_name)) {
      iconEntries.unshift({ value: source.icon_name, label: `Missing: ${source.icon_name}` });
    }
    const selectedIcon = cloning && source.icon_name && !configuredIcons.has(source.icon_name) ? '' :
      source.icon_name || '';
    const icon = aliasSelect('iconName', iconEntries, selectedIcon, true);
    const basicsGrid = node('div', 'alias-editor-grid');
    basicsGrid.append(aliasFormField('Alias list', listSelect), aliasFormField('Alias name', name),
      aliasFormField('Group', group), groupList, aliasFormField('Color', color),
      aliasFormField('Icon', icon), aliasFormField('Description', description));
    basics.append(basicsGrid);

    const matcherType = aliasSelect('matcherType', (options.matchers || []).map((entry) => ({
      value: aliasMatcherKey(entry), label: entry.label
    })), aliasMatcherKey(descriptor));
    matcherType.dataset.originalSelection = aliasMatcherKey(descriptor);
    matcherType.dataset.originalType = String(source.matcher?.type || '');
    matcherType.dataset.originalProtocol = String(source.matcher?.protocol || '');
    matcherType.dataset.originalVariant = String(source.matcher?.variant || '');
    const matcherNotice = node('div', 'alias-identifier-notice');
    if (source.overlap) matcherNotice.append(node('div', 'logging-notice warning',
      'This identifier overlaps another alias in the list. Review both aliases before saving.'));
    const matcherHost = node('div', 'alias-matcher-fields alias-editor-grid');
    let activeDescriptor = aliasMatcherDescriptor(options, matcherType.value);
    aliasMatcherFields(matcherHost, activeDescriptor, initialMatcher, options);
    let updateCreationRoutingDefaults = () => {};
    matcherType.addEventListener('change', () => {
      activeDescriptor = aliasMatcherDescriptor(options, matcherType.value);
      aliasMatcherFields(matcherHost, activeDescriptor, aliasMatcherDefault(activeDescriptor, options), options);
      updateCreationRoutingDefaults(activeDescriptor);
      matcherNotice.replaceChildren(node('div', 'logging-notice warning',
        'Changing the identifier type resets the old identifier values.'));
      modal.setDirty(true);
    });
    identifier.append(aliasFormField('Identifier type', matcherType), matcherNotice, matcherHost);

    const scanLists = aliasScanListChoices(options, source.scan_list_ids || []);
    const record = node('input');
    record.type = 'checkbox';
    record.name = 'recordable';
    record.checked = Boolean(source.recordable);
    const audioGrid = node('div', 'alias-editor-grid');
    audioGrid.append(aliasCheckOption('Record calls', record));
    const streams = node('fieldset', 'alias-stream-options');
    streams.append(node('legend', '', 'Streaming destinations'));
    const selectedStreams = new Set(source.broadcast_channels || []);
    const configuredStreams = new Set(options.stream_names || []);
    const streamNames = [...new Set([...(options.stream_names || []), ...(source.broadcast_channels || [])])];
    if (!streamNames.length) streams.append(node('div', 'empty', 'No stream destinations configured'));
    streamNames.forEach((streamName) => {
      const label = node('label', 'alias-check-option');
      const checkbox = node('input');
      checkbox.type = 'checkbox';
      checkbox.name = 'broadcastChannel';
      checkbox.value = streamName;
      checkbox.checked = selectedStreams.has(streamName) && (editing || configuredStreams.has(streamName));
      const missing = !configuredStreams.has(streamName);
      if (missing) label.classList.add('missing');
      label.append(checkbox, node('span', '', missing ? `Missing: ${streamName}` : streamName));
      streams.append(label);
    });
    updateCreationRoutingDefaults = (changedDescriptor) => {
      if (editing || cloning) return;
      const defaults = options?.alias_list?.unmatched_talkgroup_policy || {};
      const inherits = ['talkgroup', 'talkgroup_range'].includes(String(changedDescriptor?.type || ''));
      record.checked = inherits && Boolean(defaults.recordable);
      const selectedScanLists = new Set(inherits ? (defaults.scan_list_ids || []).map(Number) : []);
      scanLists.querySelectorAll('[name="scanListId"]').forEach((checkbox) => {
        checkbox.checked = selectedScanLists.has(Number(checkbox.value));
      });
      const selectedDestinations = new Set(inherits ? (defaults.broadcast_channels || []) : []);
      streams.querySelectorAll('[name="broadcastChannel"]').forEach((checkbox) => {
        checkbox.checked = selectedDestinations.has(checkbox.value);
      });
    };
    const streamAs = aliasTextInput('streamAsTalkgroup', source.stream_as_talkgroup ?? '', 'number');
    streamAs.min = '1';
    streamAs.max = '65535';
    streamAs.step = '1';
    audio.append(scanLists, audioGrid, streams, aliasFormField('Stream as talkgroup', streamAs,
      'Optional talkgroup ID sent to configured streaming destinations'));

    usage.append(analytics ? aliasUsageContent(analytics) :
      node('div', 'empty', 'Usage and evidence become available after the alias is saved and observed.'));
    const panels = { basics, identifier, audio, usage };
    const tabBar = aliasEditorModalTabs(panels);
    const errorHost = node('div', 'alias-form-message');
    const cancel = node('button', 'button secondary', 'Cancel');
    cancel.type = 'button';
    cancel.addEventListener('click', modal.close);
    const save = node('button', 'button', editing ? 'Save Changes' : (cloning ? 'Create Copy' : 'Create Alias'));
    save.type = 'submit';
    const clone = editing ? node('button', 'button secondary', 'Clone') : null;
    const remove = editing ? node('button', 'button secondary danger-outline', 'Delete') : null;
    if (clone) {
      clone.type = 'button';
      clone.addEventListener('click', () => {
        if (modal.isDirty() && !window.confirm('Discard these edits and clone the saved alias?')) return;
        modal.setDirty(false);
        closeReadOnlyModal(false, true);
        openAliasEditorModal('clone', id);
      });
    }
    if (remove) {
      remove.type = 'button';
      remove.addEventListener('click', () => {
        if (modal.isDirty() && !window.confirm('Discard these edits and delete the saved alias?')) return;
        modal.setDirty(false);
        closeReadOnlyModal(false, true);
        openAliasDeleteModal(id, source.name, revision);
      });
    }
    form.append(tabBar, basics, identifier, audio, usage, errorHost,
      aliasModalFooter(remove, clone, node('span', 'alias-modal-footer-spacer'), cancel, save));
    form.addEventListener('input', () => modal.setDirty(true));
    form.addEventListener('change', () => modal.setDirty(true));
    form.addEventListener('click', (event) => {
      if (event.target.closest('.alias-tone-add, .alias-tone-remove')) modal.setDirty(true);
    });
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!form.reportValidity()) return;
      errorHost.replaceChildren();
      save.disabled = true;
      try {
        const payload = aliasEditorPayload(form, options);
        let result;
        if (editing) {
          result = await requestJson(`/api/v1/admin/aliases/${id}`, {
            method: 'PUT', body: { revision, alias: payload }
          });
        } else {
          result = await requestJson('/api/v1/admin/aliases', {
            method: 'POST', body: { revision, alias: payload }
          });
        }
        await finishAliasMutation(modal, result, { list: payload.alias_list_id });
      } catch (error) {
        aliasMutationError(errorHost, error, () => {
          modal.setDirty(false);
          closeReadOnlyModal(false, true);
          openAliasEditorModal(mode, id, prefill);
        });
        save.disabled = false;
      }
    });
    modal.dialog.querySelector('.modal-header h2').textContent = editing ? `Edit ${source.name}` :
      (cloning ? `Clone ${source.name}` : `Add Alias to ${currentList?.name || 'List'}`);
    modal.content.replaceChildren(form);
    modal.setDirty(false);
    name.focus();
  } catch (error) {
    aliasMutationError(modal.content, error, () => {
      closeReadOnlyModal(false, true);
      openAliasEditorModal(mode, id, prefill);
    });
  }
}

function openAliasDeleteModal(id, name, revision) {
  const body = node('div', 'alias-confirmation');
  body.append(node('p', '', `Delete ${name || `Alias ${id}`} from this alias list?`),
    node('p', 'muted', 'This removes its identifier, scan-list membership, recording, and streaming settings.'));
  const errorHost = node('div', 'alias-form-message');
  const cancel = node('button', 'button secondary', 'Cancel');
  cancel.type = 'button';
  const remove = node('button', 'danger', 'Delete Alias');
  remove.type = 'button';
  body.append(errorHost, aliasModalFooter(cancel, remove));
  const modal = openReadOnlyModal(`Delete ${name || 'Alias'}`, body, {
    id: `delete-alias-${id}`, className: 'alias-editor-modal alias-confirm-modal'
  });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  remove.addEventListener('click', async () => {
    remove.disabled = true;
    try {
      const result = await requestJson(`/api/v1/admin/aliases/${id}`, {
        method: 'DELETE', body: { revision }
      });
      await finishAliasMutation(modal, result);
    } catch (error) {
      aliasMutationError(errorHost, error, () => {
        closeReadOnlyModal(false, true);
        render();
      });
      remove.disabled = false;
    }
  });
}

function aliasBulkBar(onClear) {
  const bar = node('div', 'alias-bulk-bar');
  bar.hidden = !aliasEditorSelection.size;
  const count = node('strong', 'alias-bulk-count', `${number(aliasEditorSelection.size)} selected`);
  const actions = [
    ['move', 'Move'], ['group', 'Group'], ['scan-lists', 'Scan Lists'], ['record', 'Record'],
    ['stream', 'Stream'], ['appearance', 'Appearance'], ['delete', 'Delete']
  ];
  bar.append(count);
  actions.forEach(([kind, label]) => {
    const button = node('button', kind === 'delete' ? 'button secondary danger-outline' : 'button secondary', label);
    button.type = 'button';
    button.addEventListener('click', () => openAliasBulkModal(kind));
    bar.append(button);
  });
  const clear = node('button', 'button secondary alias-bulk-clear', 'Clear selection');
  clear.type = 'button';
  clear.addEventListener('click', onClear);
  bar.append(clear);
  bar.update = () => {
    count.textContent = `${number(aliasEditorSelection.size)} selected`;
    bar.hidden = !aliasEditorSelection.size;
  };
  return bar;
}

function aliasBulkStreamChoices(options) {
  const fieldset = node('fieldset', 'alias-stream-options alias-bulk-streams');
  fieldset.append(node('legend', '', 'Destinations'));
  (options?.stream_names || []).forEach((streamName) => {
    const label = node('label', 'alias-check-option');
    const checkbox = node('input');
    checkbox.type = 'checkbox';
    checkbox.name = 'broadcastChannel';
    checkbox.value = streamName;
    label.append(checkbox, node('span', '', streamName));
    fieldset.append(label);
  });
  if (!(options?.stream_names || []).length) fieldset.append(node('div', 'empty', 'No destinations configured'));
  return fieldset;
}

function aliasBulkBinaryOperation(ariaLabel, positiveDescription, negativeDescription) {
  const operation = node('div', 'alias-membership-operation');
  operation.setAttribute('role', 'group');
  operation.setAttribute('aria-label', ariaLabel);
  let selected = 'add';
  [['add', '+', positiveDescription], ['remove', '−', negativeDescription]]
    .forEach(([value, label, description]) => {
      const button = node('button', '', label);
      button.type = 'button';
      button.title = description;
      button.setAttribute('aria-label', description);
      button.setAttribute('aria-pressed', String(value === selected));
      button.addEventListener('click', () => {
        selected = value;
        operation.querySelectorAll('button').forEach((candidate) =>
          candidate.setAttribute('aria-pressed', String(candidate === button)));
        operation.dispatchEvent(new Event('change', { bubbles: true }));
      });
      operation.append(button);
    });
  return { element: operation, value: () => selected };
}

function openAliasBulkModal(kind) {
  const ids = [...aliasEditorSelection].filter((id) => Number.isInteger(id) && id > 0).slice(0, 500);
  if (!ids.length) return;
  const form = node('form', 'alias-editor-form alias-bulk-form');
  form.append(node('p', 'modal-introduction',
    `This change applies only to the ${number(ids.length)} explicitly selected aliases on this page.`));
  const options = aliasEditorContext?.options || {};
  const payload = { revision: Number(aliasEditorContext?.revision ?? options.revision ?? 0), alias_ids: ids };
  let readChange;
  let submitLabel = 'Apply Change';

  if (kind === 'move') {
    const current = aliasEditorContext.selectedList;
    const targets = aliasEditorContext.lists.filter((row) => aliasListId(row) !== aliasListId(current) &&
      aliasListFamily(row) === aliasListFamily(current));
    const select = aliasSelect('aliasListId', [{ value: '', label: 'Leave unchanged' }, ...targets.map((row) => ({
      value: aliasListId(row), label: `${row.name} · ${aliasListFamilyLabel(row)}`
    }))], '');
    form.append(aliasFormField('Move to alias list', select,
      'Only lists with a compatible protocol are available.'));
    readChange = () => {
      if (!select.value) throw new Error('Choose a destination list');
      return { alias_list_id: Number(select.value) };
    };
  } else if (kind === 'group') {
    const operation = aliasBulkBinaryOperation('Group change', 'Assign group', 'Clear group');
    const group = aliasTextInput('group');
    operation.element.addEventListener('change', () => { group.disabled = operation.value() !== 'add'; });
    form.append(aliasFormField('Group change', operation.element), aliasFormField('Group name', group));
    readChange = () => {
      if (operation.value() === 'add' && !group.value.trim()) throw new Error('Enter a group name');
      return { group_operation: operation.value() === 'add' ? 'set' : 'clear',
        group: operation.value() === 'add' ? group.value.trim() : null };
    };
  } else if (kind === 'scan-lists') {
    const scanList = aliasSelect('scanListId', [{ value: '', label: 'Choose a scan list' },
      ...(options.scan_lists || []).map((row) => ({
        value: row.id ?? row.scan_list_id,
        label: `${row.name}${row.published === false ? ' · not published' : ''}`
      }))], '');
    const operation = aliasBulkBinaryOperation('Membership change', 'Add selected aliases',
      'Remove selected aliases');
    form.append(aliasFormField('Scan list', scanList), aliasFormField('Membership change', operation.element));
    readChange = () => {
      const scanListId = Number(scanList.value);
      if (!Number.isInteger(scanListId) || scanListId <= 0) throw new Error('Choose a scan list');
      return { scan_list_id: scanListId, operation: operation.value() };
    };
  } else if (kind === 'record') {
    const operation = aliasBulkBinaryOperation('Recording change', 'Enable recording', 'Disable recording');
    form.append(aliasFormField('Recording change', operation.element));
    readChange = () => ({ recordable: operation.value() === 'add' });
  } else if (kind === 'stream') {
    const operation = aliasSelect('streamOperation', [
      { value: '', label: 'Leave unchanged' }, { value: 'add', label: 'Add destinations' },
      { value: 'remove', label: 'Remove destinations' }, { value: 'replace', label: 'Replace destinations' },
      { value: 'clear', label: 'Clear all destinations' }
    ], '');
    const choices = aliasBulkStreamChoices(options);
    operation.addEventListener('change', () => {
      choices.disabled = operation.value === 'clear' || !operation.value;
      choices.querySelectorAll('input').forEach((input) => { input.disabled = choices.disabled; });
    });
    choices.disabled = true;
    choices.querySelectorAll('input').forEach((input) => { input.disabled = true; });
    form.append(aliasFormField('Stream change', operation), choices);
    readChange = () => {
      if (!operation.value) throw new Error('Choose how streams should change');
      const channels = [...choices.querySelectorAll('input:checked')].map((input) => input.value);
      if (operation.value !== 'clear' && !channels.length) throw new Error('Select at least one destination');
      return { stream_operation: operation.value,
        broadcast_channels: operation.value === 'clear' ? null : channels };
    };
  } else if (kind === 'appearance') {
    const colorOperation = aliasSelect('colorOperation', [
      { value: '', label: 'Leave color unchanged' }, { value: 'SET', label: 'Set color' },
      { value: 'RESET', label: 'Reset to default' }
    ], '');
    const color = aliasTextInput('color', '#ffffff', 'color');
    color.disabled = true;
    colorOperation.addEventListener('change', () => { color.disabled = colorOperation.value !== 'SET'; });
    const icon = aliasSelect('iconName', [{ value: '', label: 'Leave icon unchanged' },
      ...(options.icon_names || []).map((value) => ({ value, label: value }))], '');
    form.append(aliasFormField('Color change', colorOperation), aliasFormField('Color', color),
      aliasFormField('Icon change', icon));
    readChange = () => {
      if (!colorOperation.value && !icon.value) throw new Error('Choose a color or icon change');
      return { ...(colorOperation.value ? {
        color: colorOperation.value === 'RESET' ? 0 : aliasColorInteger(color.value)
      } : {}),
        ...(icon.value ? { icon_name: icon.value } : {}) };
    };
  } else if (kind === 'delete') {
    submitLabel = `Delete ${number(ids.length)} Aliases`;
    const confirm = node('label', 'alias-confirm-check');
    const checkbox = node('input');
    checkbox.type = 'checkbox';
    confirm.append(checkbox, node('span', '',
      `I understand this permanently deletes exactly ${number(ids.length)} selected aliases.`));
    form.append(node('div', 'logging-notice warning',
      'Deleting an alias also removes its identifier, scan-list membership, recording, and streaming settings.'),
      confirm);
    readChange = () => {
      if (!checkbox.checked) throw new Error('Confirm the deletion first');
      return { delete: true };
    };
  }

  const errorHost = node('div', 'alias-form-message');
  const cancel = node('button', 'button secondary', 'Cancel');
  cancel.type = 'button';
  const submit = node('button', kind === 'delete' ? 'danger' : 'button', submitLabel);
  submit.type = 'submit';
  form.append(errorHost, aliasModalFooter(cancel, submit));
  const modal = openReadOnlyModal(`${kind === 'delete' ? 'Delete' : 'Bulk'} · ${number(ids.length)} aliases`, form, {
    id: `bulk-alias-${kind}`, className: 'alias-editor-modal alias-bulk-modal'
  });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  form.addEventListener('input', () => modal.setDirty(true));
  form.addEventListener('change', () => modal.setDirty(true));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    errorHost.replaceChildren();
    try {
      const change = readChange();
      submit.disabled = true;
      let result;
      if (kind === 'scan-lists') {
        result = await requestJson(`/api/v1/admin/scan-lists/${change.scan_list_id}/members`, {
          method: 'PUT', body: {
            revision: payload.revision,
            operation: change.operation,
            alias_ids: ids
          }
        });
      } else {
        Object.assign(payload, change);
        result = await requestJson('/api/v1/admin/aliases/bulk', {
          method: 'POST', body: payload
        });
      }
      await finishAliasMutation(modal, result, kind === 'move' ? { list: payload.alias_list_id } : {});
    } catch (error) {
      aliasMutationError(errorHost, error, () => {
        modal.setDirty(false);
        closeReadOnlyModal(false, true);
        render();
      });
      submit.disabled = false;
    }
  });
}

function scanListMemberBulkBar(scanList, onClear) {
  const bar = node('div', 'alias-bulk-bar scan-list-member-bulk-bar');
  bar.hidden = !aliasEditorSelection.size;
  const count = node('strong', 'alias-bulk-count', `${number(aliasEditorSelection.size)} selected`);
  const remove = node('button', 'button secondary danger-outline scan-list-member-remove',
    `Remove from ${scanList.name}`);
  remove.type = 'button';
  remove.addEventListener('click', () => openScanListMemberRemoveModal(scanList));
  const clear = node('button', 'button secondary alias-bulk-clear', 'Clear selection');
  clear.type = 'button';
  clear.addEventListener('click', onClear);
  bar.append(count, remove, clear);
  bar.update = () => {
    count.textContent = `${number(aliasEditorSelection.size)} selected`;
    bar.hidden = !aliasEditorSelection.size;
  };
  return bar;
}

function openScanListMemberRemoveModal(scanList) {
  const ids = [...aliasEditorSelection].filter((id) => Number.isInteger(id) && id > 0).slice(0, 500);
  if (!ids.length) return;
  const body = node('div', 'admin-confirmation');
  body.append(node('p', '', `Remove ${number(ids.length)} selected aliases from ${scanList.name}?`),
    node('p', 'muted', 'The aliases and their other scan-list memberships will be preserved.'));
  const message = node('div', 'alias-form-message');
  message.setAttribute('role', 'alert');
  const cancel = node('button', 'button secondary', 'Cancel');
  cancel.type = 'button';
  const remove = node('button', 'danger', `Remove ${number(ids.length)} Aliases`);
  remove.type = 'button';
  body.append(message, aliasModalFooter(cancel, remove));
  const modal = openReadOnlyModal(`Remove aliases · ${scanList.name}`, body, {
    id: `remove-scan-list-members-${scanList.id}`, className: 'alias-editor-modal',
    returnFocusSelector: '.scan-list-member-remove'
  });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  remove.addEventListener('click', async () => {
    if (remove.disabled) return;
    remove.disabled = true;
    message.textContent = 'Removing aliases…';
    try {
      const result = await requestJson(`/api/v1/admin/scan-lists/${scanList.id}/members`, {
        method: 'PUT', body: {
          revision: Number(aliasEditorContext?.revision ?? 0), operation: 'remove', alias_ids: ids
        }
      });
      await finishAliasMutation(modal, result);
    } catch (error) {
      aliasMutationError(message, error, () => {
        modal.setDirty(false);
        closeReadOnlyModal(false, true);
        render();
      });
      remove.disabled = false;
    }
  });
  remove.focus();
}

function observedTalkgroupDiscoverySupported(selectedList) {
  return ['P25', 'DMR', 'NXDN'].includes(aliasListFamily(selectedList));
}

function unmatchedTalkgroupsSupported(selectedList) {
  return ['P25', 'DMR', 'NXDN', 'NBFM'].includes(aliasListFamily(selectedList));
}

function openUnmatchedTalkgroupPolicyModal(selectedList) {
  const listId = aliasListId(selectedList);
  const options = aliasEditorContext?.options || {};
  const policy = selectedList?.unmatched_talkgroup_policy || {};
  const form = node('form', 'alias-editor-form alias-policy-form');
  form.append(node('p', 'modal-introduction',
    'These settings apply when a destination talkgroup or patch group has no exact Alias or covering talkgroup ' +
    'range in this Alias List. New talkgroup Aliases created in this list start with the same selections. ' +
    'Existing Aliases are not changed.'));

  const record = node('input');
  record.type = 'checkbox';
  record.name = 'recordable';
  record.checked = Boolean(policy.recordable);
  const behavior = node('fieldset', 'alias-stream-options alias-defaults-section');
  behavior.append(node('legend', '', 'Recording'), node('p', 'muted',
    'Records completed unmatched talkgroup calls in the configured recording directory. New talkgroup Aliases ' +
    'are created with these defaults. Existing Aliases are unchanged.'), aliasCheckOption('Record calls', record));

  const scanLists = aliasScanListChoices(options, policy.scan_list_ids || []);
  const scanListLegend = scanLists.querySelector('legend');
  if (scanListLegend) scanListLegend.textContent = 'Scan List';
  scanListLegend?.after(node('p', 'muted',
    'Routes unmatched talkgroup calls to the selected scan lists for browser playback. New talkgroup Aliases are ' +
    'created with these defaults.'));

  const streams = node('fieldset', 'alias-stream-options');
  streams.append(node('legend', '', 'Streaming'), node('p', 'muted',
    'Sends unmatched talkgroup calls to the selected external streaming destinations. New talkgroup Aliases are ' +
    'created with these defaults.'));
  const selectedStreams = new Set(policy.broadcast_channels || []);
  const configuredStreams = new Set(options.stream_names || []);
  const streamNames = [...new Set([...(options.stream_names || []), ...(policy.broadcast_channels || [])])];
  if (!streamNames.length) streams.append(node('div', 'empty', 'No stream destinations configured'));
  streamNames.forEach((streamName) => {
    const label = node('label', 'alias-check-option');
    const checkbox = node('input');
    checkbox.type = 'checkbox';
    checkbox.name = 'broadcastChannel';
    checkbox.value = streamName;
    checkbox.checked = selectedStreams.has(streamName);
    const missing = !configuredStreams.has(streamName);
    if (missing) label.classList.add('missing');
    label.append(checkbox, node('span', '', missing ? `Missing: ${streamName}` : streamName));
    streams.append(label);
  });

  const errorHost = node('div', 'alias-form-message');
  const cancel = node('button', 'button secondary', 'Cancel');
  cancel.type = 'button';
  const warning = node('div', 'logging-notice warning',
    'Warning: These settings act as a catch-all and can play, record, or stream traffic that has not been ' +
    'individually reviewed, including sensitive traffic. If a selected streaming destination sends to Broadcastify ' +
    'or another third-party provider, leave catch-all Streaming disabled and configure approved talkgroups individually.');
  const save = node('button', 'button', 'Save Alias List Defaults');
  save.type = 'submit';
  form.append(behavior, scanLists, streams, warning, errorHost, aliasModalFooter(cancel, save));
  const modal = openReadOnlyModal(`Alias List Defaults · ${selectedList.name}`, form, {
    id: `unmatched-talkgroups-${listId}`,
    className: 'alias-editor-modal alias-policy-modal',
    returnFocusSelector: '.alias-policy-button'
  });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  form.addEventListener('input', () => modal.setDirty(true));
  form.addEventListener('change', () => modal.setDirty(true));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    errorHost.replaceChildren();
    save.disabled = true;
    try {
      const result = await requestJson(`/api/v1/admin/alias-lists/${listId}/unmatched-talkgroups`, {
        method: 'PUT', body: {
          revision: Number(aliasEditorContext?.revision ?? options.revision ?? 0),
          recordable: record.checked,
          broadcast_channels: [...streams.querySelectorAll('[name="broadcastChannel"]:checked')]
            .map((checkbox) => checkbox.value),
          scan_list_ids: selectedAliasScanListIds(form)
        }
      });
      await finishAliasMutation(modal, result);
    } catch (error) {
      aliasMutationError(errorHost, error, () => {
        modal.setDirty(false);
        closeReadOnlyModal(false, true);
        render();
      });
      save.disabled = false;
    }
  });
}

function observedTalkgroupProtocol(row) {
  const value = String(row?.protocol || '').trim().toLowerCase();
  return ['am', 'p25', 'dmr', 'nxdn', 'nbfm', 'fleetsync', 'mdc1200'].includes(value) ? value : '';
}

function observedTalkgroupVariant(row) {
  if (observedTalkgroupProtocol(row) !== 'p25') return null;
  return ['phase_1', 'phase_2'].includes(row?.protocol_variant) ? row.protocol_variant : 'phase_1';
}

function observedTalkgroupMatchKind(row) {
  const kind = String(row?.match_kind || 'none').trim().toLowerCase();
  return ['exact', 'range'].includes(kind) ? kind : 'none';
}

function observedP25IdentityState(row) {
  const state = String(row?.qualification?.state || 'unknown').trim().toLowerCase();
  return ['unknown', 'ordinary', 'stable_fully_qualified', 'ambiguous'].includes(state) ? state : 'unknown';
}

function observedP25HomeIdentity(row) {
  if (observedP25IdentityState(row) !== 'stable_fully_qualified') return null;
  const wacn = Number(row?.qualification?.home?.wacn);
  const system = Number(row?.qualification?.home?.system_id);
  const talkgroup = Number(row?.qualification?.home?.talkgroup_id);
  return Number.isInteger(wacn) && wacn >= 0 && wacn <= 0xFFFFF &&
    Number.isInteger(system) && system >= 0 && system <= 0xFFF &&
    Number.isInteger(talkgroup) && talkgroup > 0 && talkgroup < 0xFFFF ?
      { wacn, system, talkgroup } : null;
}

function observedTalkgroupPromotionSupported(row) {
  return row?.promotion_supported === true || row?.promotion_supported === 1 ||
    String(row?.promotion_supported).toLowerCase() === 'true';
}

function observedTalkgroupPromotionReason(row) {
  return String(row?.promotion_reason ||
    'This observation does not contain enough identity information to create a safe alias.');
}

function observedTalkgroupIdentity(row) {
  return identityNumber(row, row.talkgroup_id);
}

function observedTalkgroupSystem(row) {
  const wrapper = node('div', 'observed-talkgroup-system');
  const label = row.system_name || row.site_names || row.scope_key || row.context_key || 'Unknown system';
  const details = [protocolFamily(row), row.topology, row.site_names && row.site_names !== label ? row.site_names : null]
    .filter(Boolean).join(' · ');
  wrapper.append(node('strong', '', label));
  if (details) wrapper.append(node('small', '', details));
  return wrapper;
}

function observedTalkgroupMatch(row) {
  const kind = observedTalkgroupMatchKind(row);
  if (kind === 'none') return badge('No match', 'state-stale', 'No exact alias or covering range exists');
  const wrapper = node('div', 'observed-talkgroup-match');
  wrapper.append(node('strong', '', row.matched_alias_name || `Alias ${identifierNumber(row.matched_alias_id)}`),
    node('small', '', kind === 'range' ? 'Covered by range' : 'Exact alias'));
  return wrapper;
}

function observedTalkgroupCounts(definitions) {
  const wrapper = node('div', 'observed-talkgroup-counts');
  definitions.forEach(([label, value]) => {
    wrapper.append(node('span', '', `${label} ${value === null || value === undefined ? '—' : number(value)}`));
  });
  return wrapper;
}

function observedTalkgroupTime(row, value) {
  const rendered = dateTime(value);
  if (value !== null && value !== undefined &&
      String(row?.topology || '').toUpperCase() === 'CONVENTIONAL' &&
      ['P25', 'NXDN'].includes(protocolFamily(row))) {
    const wrapper = node('span', 'observed-talkgroup-time');
    wrapper.append(rendered, ' (hour beginning)');
    return wrapper;
  }
  return rendered;
}

function observedTalkgroupKey(row) {
  const topology = String(row?.topology || 'UNKNOWN').trim().toUpperCase() || 'UNKNOWN';
  const protocol = observedTalkgroupProtocol(row) || 'UNKNOWN';
  let source = 'source:unknown';
  if (row?.scope_key) source = `scope-key:${row.scope_key}`;
  else if (row?.context_key) source = `context-key:${row.context_key}`;
  const home = row?.qualification?.home || {};
  return `${topology}|${protocol}|${source}|${rowGroupIdentityKind(row)}-${
    row.talkgroup_id}-${observedP25IdentityState(row)}-${
    home.wacn ?? 'x'}-${home.system_id ?? 'x'}-${home.talkgroup_id ?? 'x'}`;
}

function observedTalkgroupFocusKey(row) {
  return encodeURIComponent(observedTalkgroupKey(row));
}

function observedTalkgroupPrefill(row, selectedList) {
  if (!observedTalkgroupPromotionSupported(row)) {
    throw new Error(observedTalkgroupPromotionReason(row));
  }
  const policy = selectedList?.unmatched_talkgroup_policy || {};
  const talkgroupId = Number(row.talkgroup_id);
  let matcher;
  if (isP25(row)) {
    if (String(row?.topology || '').toUpperCase() === 'CONVENTIONAL') {
      throw new Error('Conventional P25 observations cannot be promoted until identity qualification is retained.');
    }
    if (['ordinary', 'stable_fully_qualified'].includes(observedP25IdentityState(row)) &&
        Number.isInteger(talkgroupId) &&
        talkgroupId > 0 && talkgroupId < 0xFFFF) {
      matcher = { type: 'talkgroup', protocol: observedTalkgroupProtocol(row),
        variant: observedTalkgroupVariant(row), value: talkgroupId };
    } else {
      throw new Error('This P25 observation does not have a usable local talkgroup ID.');
    }
  } else {
    matcher = { type: 'talkgroup', protocol: observedTalkgroupProtocol(row), value: talkgroupId };
  }
  return {
    alias_list_id: aliasListId(selectedList),
    name: '',
    description: '',
    group: '',
    color: 0,
    icon_name: null,
    recordable: Boolean(policy.recordable),
    broadcast_channels: [...(policy.broadcast_channels || [])],
    scan_list_ids: [...(policy.scan_list_ids || [])],
    stream_as_talkgroup: null,
    matcher,
    returnFocusSelector: `.observed-talkgroup-create[data-observed-key="${observedTalkgroupFocusKey(row)}"]`
  };
}

function routedAliasPrefill(selectedList, options) {
  if (!aliasAdminAllowed() || route.get('createAlias') !== '1' || !selectedList) return null;
  const type = String(route.get('createType') || '').trim().toLowerCase();
  const protocol = String(route.get('createProtocol') || '').trim().toLowerCase();
  const variant = String(route.get('createVariant') || '').trim().toLowerCase();
  const valueText = String(route.get('createValue') || '').trim();
  if (!['talkgroup', 'radio'].includes(type) ||
      !['am', 'p25', 'dmr', 'nxdn', 'nbfm', 'fleetsync', 'mdc1200'].includes(protocol) ||
      !/^[0-9]+$/.test(valueText)) return null;
  if ((protocol === 'p25' && !['phase_1', 'phase_2'].includes(variant)) ||
      (protocol !== 'p25' && variant)) return null;
  const value = Number(valueText);
  if (!Number.isSafeInteger(value) || value < 0) return null;
  const descriptor = aliasMatcherDescriptor(options, type, protocol, variant);
  if (!descriptor || String(descriptor.type) !== type || String(descriptor.protocol || '') !== protocol ||
      (protocol !== 'p25' && String(descriptor.variant || '') !== variant) ||
      (descriptor.minimum !== undefined && value < Number(descriptor.minimum)) ||
      (descriptor.maximum !== undefined && value > Number(descriptor.maximum))) return null;
  const policy = type === 'talkgroup' ? selectedList.unmatched_talkgroup_policy || {} : {};
  return {
    alias_list_id: aliasListId(selectedList),
    name: String(route.get('createName') || '').trim().slice(0, 256),
    description: '',
    group: '',
    color: 0,
    icon_name: null,
    recordable: Boolean(policy.recordable),
    broadcast_channels: [...(policy.broadcast_channels || [])],
    scan_list_ids: [...(policy.scan_list_ids || [])],
    stream_as_talkgroup: null,
    matcher: { type, protocol, ...(variant ? { variant } : {}), value }
  };
}

function openObservedTalkgroupAliasEditor(row, selectedList) {
  if (observedTalkgroupMatchKind(row) === 'exact' || !observedTalkgroupPromotionSupported(row)) return;
  openAliasEditorModal('create', null, observedTalkgroupPrefill(row, selectedList));
}

function observedTalkgroupCreateButton(row, selectedList) {
  if (!aliasAdminAllowed() || observedTalkgroupMatchKind(row) === 'exact') return '—';
  if (!observedTalkgroupPromotionSupported(row)) {
    return badge('Review only', 'state-stale', observedTalkgroupPromotionReason(row));
  }
  const button = node('button', 'button secondary observed-talkgroup-create', 'Create Alias');
  button.type = 'button';
  button.dataset.talkgroupId = String(row.talkgroup_id);
  button.dataset.observedKey = observedTalkgroupFocusKey(row);
  button.addEventListener('click', () => openObservedTalkgroupAliasEditor(row, selectedList));
  return button;
}

function observedTalkgroupDetail(row, selectedList) {
  const wrapper = node('div', 'observed-talkgroup-detail');
  const home = observedP25HomeIdentity(row);
  const identity = [
    [home ? 'Local Talkgroup' : groupIdentityLabel(row, null, false), identityNumber(row, row.talkgroup_id)],
    ['Protocol', protocolFamily(row) || row.protocol],
    ['System', row.system_name || '—'],
    ['Sites', row.site_names || '—'],
    ['Topology', row.topology || '—'],
    ['WACN', row.wacn === null || row.wacn === undefined ? '—' : hexDecimalPair(row.wacn, 5)],
    ['System ID', row.system_id === null || row.system_id === undefined ? '—' : hexDecimalPair(row.system_id, 3)],
    ['Network ID', row.network_id === null || row.network_id === undefined ? '—' : identifierNumber(row.network_id)],
    ['Frequency', row.frequency_count === null || row.frequency_count === undefined ||
      Number(row.frequency_count) <= 0 ? '—' :
      Number(row.frequency_count) === 1 ? frequency(row.frequency_hz) :
        `${number(row.frequency_count)} frequencies`],
    ['Timeslot', row.timeslot_count === null || row.timeslot_count === undefined ||
      Number(row.timeslot_count) <= 0 ? '—' :
      Number(row.timeslot_count) === 1 ? identifierNumber(row.timeslot) :
        `${number(row.timeslot_count)} timeslots`]
  ];
  if (home) {
    identity.splice(1, 0,
      ['Decoded Home', `${hex(home.wacn, 5)}-${hex(home.system, 3)}-${identifierNumber(home.talkgroup)}`],
      ['Home WACN', hexDecimalPair(home.wacn, 5)], ['Home System ID', hexDecimalPair(home.system, 3)]);
  }
  wrapper.append(section('Identity', keyValues(identity)));
  const coverage = [['Match', observedTalkgroupMatch(row)]];
  if (!observedTalkgroupPromotionSupported(row)) {
    coverage.push(['Promotion', badge('Review only', 'state-stale', observedTalkgroupPromotionReason(row))],
      ['Reason', observedTalkgroupPromotionReason(row)]);
  }
  wrapper.append(section('Alias Coverage', keyValues(coverage)));
  wrapper.append(section('Call Use', aliasDetailMetricBand(row, [
    ['Calls', 'call_count'], ['Recorded', 'recorded_count'], ['Sent', 'streamed_count'],
    ['Encrypted', 'encrypted_count']
  ])));
  wrapper.append(section('System Evidence', aliasDetailMetricBand(row, [
    ['Grants', 'grant_count'], ['Join', 'join_count'], ['Emergency', 'emergency_count'],
    ['Register', 'register_count'], ['Logout', 'logout_count'], ['Denial', 'denial_count'],
    ['Data', 'data_count'], ['Other', 'other_signaling_count']
  ])));
  wrapper.append(section('Observed', keyValues([
    ['First Activity', observedTalkgroupTime(row, row.first_seen_ms)],
    ['Latest Activity', observedTalkgroupTime(row, row.last_seen_ms)]
  ])));
  if (aliasAdminAllowed() && observedTalkgroupMatchKind(row) !== 'exact' &&
      observedTalkgroupPromotionSupported(row)) {
    const actions = node('div', 'observed-talkgroup-detail-actions');
    actions.append(observedTalkgroupCreateButton(row, selectedList));
    wrapper.append(actions);
  }
  return wrapper;
}

function openObservedTalkgroupDetail(row, selectedList) {
  const id = identityNumber(row, row.talkgroup_id);
  openReadOnlyModal(`Observed ${groupIdentityLabel(row, null, false)} ${id}`,
    observedTalkgroupDetail(row, selectedList), {
    id: `observed-talkgroup-${id}`, className: 'alias-editor-modal observed-talkgroup-modal'
  });
}

function observedTalkgroupToolbar(selectedList) {
  const form = node('form', 'toolbar alias-catalog-toolbar observed-talkgroup-toolbar');
  form.method = 'get';
  [['view', 'aliases'], ['list', aliasListId(selectedList)], ['aliasTab', 'discover'],
    ['sort', route.get('sort')], ['direction', route.get('direction')]].forEach(([name, value]) => {
    if (!value) return;
    const hidden = node('input');
    hidden.type = 'hidden';
    hidden.name = name;
    hidden.value = String(value);
    form.append(hidden);
  });
  const search = node('label', 'alias-filter alias-search-filter');
  search.append(node('span', '', 'Search'));
  const input = node('input');
  input.type = 'search';
  input.name = 'q';
  input.value = route.get('q') || '';
  input.placeholder = 'Talkgroup, system, or site';
  search.append(input);
  form.append(search, node('button', '', 'Search'));
  if (route.get('q')) {
    form.append(anchor('Clear', href('aliases', {
      list: aliasListId(selectedList), aliasTab: 'discover', sort: route.get('sort'),
      direction: route.get('direction')
    }), 'button secondary'));
  }
  return form;
}

function renderObservedTalkgroups(main, page, selectedList) {
  const rows = (page.rows || []).filter((row) => observedTalkgroupMatchKind(row) !== 'exact');
  const columns = [
    { id: 'talkgroup-id', label: 'TG', fullLabel: 'Talkgroup', sort: 'talkgroup', className: 'numeric',
      render: observedTalkgroupIdentity },
    { id: 'system', label: 'System', sort: 'system', className: 'alias-cell', render: observedTalkgroupSystem },
    { id: 'match', label: 'Alias Match', className: 'alias-cell', render: observedTalkgroupMatch },
    { id: 'call-use', label: 'Call Use', sort: 'calls', render: (row) => observedTalkgroupCounts([
      ['Calls', row.call_count], ['Rec', row.recorded_count], ['Sent', row.streamed_count]
    ]) },
    { id: 'evidence', label: 'Evidence', render: (row) => observedTalkgroupCounts([
      ['Enc', row.encrypted_count], ['Signal', row.signaling_count]
    ]) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', sort: 'last_seen',
      render: (row) => observedTalkgroupTime(row, row.last_seen_ms) },
    { id: 'action', label: '', render: (row) => observedTalkgroupCreateButton(row, selectedList) }
  ];
  const host = node('div', 'alias-catalog-table-host observed-talkgroup-table-host');
  const observedTable = table(rows, columns,
    'No observed talkgroups without an exact alias are available for this list', {
      type: 'alias-observed-talkgroups', serverSort: true, sortable: false,
      defaultSort: 'last_seen', defaultDirection: 'desc',
      rowKey: observedTalkgroupKey
    });
  host.append(observedTable);
  const rowsByKey = new Map(rows.map((row) => [observedTalkgroupKey(row), row]));
  observedTable.querySelectorAll('tbody tr[data-id]').forEach((tableRow) => {
    tableRow.classList.add('observed-talkgroup-row');
    tableRow.addEventListener('click', (event) => {
      if (event.target.closest('a, button, input, select, label')) return;
      const row = rowsByKey.get(tableRow.dataset.id);
      if (row) openObservedTalkgroupDetail(row, selectedList);
    });
  });
  const block = section('Observed Talkgroups', host);
  block.classList.add('alias-catalog-section', 'alias-editor-table-section', 'observed-talkgroup-section');
  block.append(node('p', 'metric-meaning-note alias-catalog-guide',
    'This list contains talkgroups observed on its assigned systems that do not have an exact alias. Range matches ' +
    'are shown so you can decide whether a dedicated alias is useful.'), pager({ ...page, rows }));
  main.append(block);
}

async function renderScanListMembers(main, listResponse, scanListCatalog, scanList, renderContext) {
  const filters = {
    type: route.get('type'), matcher: route.get('matcher'), group: route.get('group'),
    scan_list_id: scanList.id, record: route.get('record'), stream: route.get('stream'),
    evidence: route.get('evidence'), use: route.get('use'),
    last_activity_before: route.get('lastActivityBefore'), last_activity_after: route.get('lastActivityAfter')
  };
  const page = await apiPage('/api/v1/aliases', pageParameters(filters));
  if (!renderIsCurrent(renderContext) || !main.isConnected) return;
  const options = {
    scan_lists: scanListCatalog.scan_lists || [], scan_list_scope: true
  };
  aliasEditorContext.page = page;
  aliasEditorContext.options = options;
  aliasEditorContext.revision = Number(scanListCatalog.revision ?? 0);
  const rows = page.rows || [];
  const visibleIds = new Set(rows.map((row) => Number(row.alias_id)));
  aliasEditorSelection = new Set([...aliasEditorSelection].filter((id) => visibleIds.has(id)));
  aliasEditorLastSelectionIndex = null;

  const summary = node('section', 'alias-list-summary scan-list-member-summary');
  const summaryCopy = node('div', 'alias-list-summary-copy');
  summaryCopy.append(...[
    node('h2', '', scanList.name), badge('Scan List', 'state-current'),
    scanList.default === true ? badge('Default', 'state-current') : null,
    scanList.published === false ? badge('Not published', 'state-stale') : null,
    node('span', 'muted', `${number(scanList.alias_count || 0)} alias members · ` +
      `${number(scanList.unmatched_alias_list_count || 0)} unknown-talkgroup routes`)
  ].filter(Boolean));
  const summaryActions = node('div', 'alias-list-summary-actions');
  summaryActions.append(anchor('Back to Scan Lists', href('configuration', { tab: 'scan-lists' }),
    'button secondary'));
  summary.append(summaryCopy, summaryActions);
  main.append(summary, aliasEditorFilterToolbar(listResponse, options));

  const tableHost = node('div', 'alias-catalog-table-host alias-editor-table-host');
  let bulkBar = null;
  const updateSelection = () => {
    tableHost.querySelectorAll('.alias-row-select').forEach((checkbox) => {
      const tableRow = checkbox.closest('tr');
      const id = Number(tableRow?.dataset.id);
      checkbox.checked = aliasEditorSelection.has(id);
      tableRow?.classList.toggle('selected', checkbox.checked);
    });
    bulkBar?.update();
  };
  const aliasTable = table(rows, scanListMemberColumns(rows, updateSelection),
    'No aliases belong to this scan list', {
      type: 'alias-scan-list-members', serverSort: true, sortable: false,
      defaultSort: 'name', defaultDirection: 'asc', rowKey: (row) => row.alias_id
    });
  tableHost.append(aliasTable);
  aliasTable.querySelectorAll('tbody tr[data-id]').forEach((tableRow) => {
    tableRow.addEventListener('click', (event) => {
      if (event.target.closest('a, button, input, select, label')) return;
      const id = Number(tableRow.dataset.id);
      const row = rows.find((candidate) => Number(candidate.alias_id) === id);
      if (!row) return;
      if (event.shiftKey || event.metaKey || event.ctrlKey) {
        const index = rows.indexOf(row);
        if (event.shiftKey && aliasEditorLastSelectionIndex !== null) {
          const start = Math.min(index, aliasEditorLastSelectionIndex);
          const end = Math.max(index, aliasEditorLastSelectionIndex);
          rows.slice(start, end + 1).forEach((candidate) =>
            aliasEditorSelection.add(Number(candidate.alias_id)));
        } else if (aliasEditorSelection.has(id)) aliasEditorSelection.delete(id);
        else aliasEditorSelection.add(id);
        aliasEditorLastSelectionIndex = index;
        updateSelection();
        return;
      }
      window.location.assign(aliasEditorRowHref(row));
    });
  });

  const actions = node('div', 'section-title-actions');
  const selectPage = node('button', 'button secondary', 'Select This Page');
  selectPage.type = 'button';
  selectPage.addEventListener('click', () => {
    rows.forEach((row) => {
      if (aliasEditorSelection.size < 500) aliasEditorSelection.add(Number(row.alias_id));
    });
    updateSelection();
  });
  actions.append(selectPage);
  const exportContext = { scan_list_id: scanList.id };
  new Map([
    ['type', 'type'], ['matcher', 'matcher'], ['group', 'group'], ['record', 'record'],
    ['stream', 'stream'], ['evidence', 'evidence'], ['use', 'use'],
    ['lastActivityBefore', 'last_activity_before'], ['lastActivityAfter', 'last_activity_after']
  ]).forEach((queryKey, routeKey) => {
    if (route.get(routeKey)) exportContext[queryKey] = route.get(routeKey);
  });
  actions.append(exportCsvLink('aliases', exportContext));
  const block = section(`Aliases in ${scanList.name}`, tableHost, actions);
  block.classList.add('alias-catalog-section', 'alias-editor-table-section', 'scan-list-member-table-section');
  bulkBar = scanListMemberBulkBar(scanList, () => {
    aliasEditorSelection.clear();
    aliasEditorLastSelectionIndex = null;
    updateSelection();
  });
  block.append(bulkBar);
  updateSelection();
  block.append(node('p', 'metric-meaning-note alias-catalog-guide',
    'This view includes members from every alias list. Removing membership preserves each alias and its other ' +
      'scan-list memberships.'), pager(page));
  main.append(block);
}

async function renderAliases() {
  const renderContext = captureRenderContext();
  if (!aliasAdminAllowed()) throw Object.assign(new Error('Administrator access is required.'), { status: 403 });
  const requestedScanListId = !route.get('list') && /^[1-9][0-9]*$/.test(route.get('scanListId') || '') ?
    Number(route.get('scanListId')) : null;
  const publicListsPromise = apiPage('/api/v1/alias-lists');
  const adminListsPromise = requestJson('/api/v1/admin/alias-lists', { csrf: false });
  const scanListCatalogPromise = requestedScanListId ?
    requestJson('/api/v1/admin/scan-lists', { csrf: false }) :
    Promise.resolve({ revision: null, scan_lists: [] });
  const [listResponse, adminCatalog, scanListCatalog] = await Promise.all([
    publicListsPromise, adminListsPromise, scanListCatalogPromise
  ]);
  if (!renderIsCurrent(renderContext)) return;
  const lists = mergedAliasLists(listResponse.rows || [], adminCatalog.alias_lists || []);
  let selectedList = lists.find((row) => aliasListId(row) === Number(route.get('list')));
  if (route.get('createAlias') === '1' && route.has('createListName')) {
    const requestedListName = String(route.get('createListName') || '').trim();
    const requestedList = lists.find((row) => requestedListName &&
      String(row.name || '').toLowerCase() === requestedListName.toLowerCase());
    if (requestedList) {
      selectedList = requestedList;
      route.set('list', String(aliasListId(selectedList)));
      route.delete('createListName');
      window.history.replaceState({}, '', currentHref());
    } else selectedList = null;
  }
  const scanListScope = requestedScanListId ?
    (scanListCatalog.scan_lists || []).find((row) => Number(row.id ?? row.scan_list_id) === requestedScanListId) :
    null;
  aliasEditorContext = {
    admin: true, revision: Number(scanListScope ? scanListCatalog.revision : adminCatalog.revision ?? 0),
    lists, selectedList, scanListScope, options: null, page: null
  };

  const subtitle = scanListScope ?
    `${number(scanListScope.alias_count || 0)} members across all alias lists · administrator editing enabled` :
    `${number(lists.length)} alias lists · administrator editing enabled`;
  const workspace = node('div', 'alias-editor-workspace');
  workspace.append(aliasListRail(lists, selectedList));
  const main = node('div', 'alias-editor-main');
  workspace.append(main);
  if (!beginPage(renderContext, pageHeader('Alias Editor', subtitle), workspace)) return;

  if (scanListScope) {
    await renderScanListMembers(main, listResponse, scanListCatalog, scanListScope, renderContext);
    return;
  }

  if (requestedScanListId) {
    const missing = node('section', 'alias-editor-welcome');
    missing.append(node('h2', '', 'Scan list not found'),
      node('p', '', 'This scan list may have been deleted or changed.'),
      anchor('Back to Scan Lists', href('configuration', { tab: 'scan-lists' }), 'button secondary'));
    main.append(missing);
    return;
  }

  if (!selectedList) {
    main.append(aliasEditorEmptyState(lists));
    return;
  }

  const allowedViews = observedTalkgroupDiscoverySupported(selectedList) ?
    ['configure', 'discover', 'calls', 'evidence', 'custom'] : ['configure', 'calls', 'evidence', 'custom'];
  const view = allowedViews.includes(route.get('aliasTab')) ?
    route.get('aliasTab') : 'configure';
  const filters = {
    list: aliasListId(selectedList), type: route.get('type'), matcher: route.get('matcher'),
    group: route.get('group'), scan_list_id: route.get('scanListId'), record: route.get('record'),
    stream: route.get('stream'), evidence: route.get('evidence'), use: route.get('use'),
    last_activity_before: route.get('lastActivityBefore'), last_activity_after: route.get('lastActivityAfter')
  };
  const pagePromise = view === 'discover' ?
    apiPage(`/api/v1/alias-lists/${aliasListId(selectedList)}/observed-talkgroups`,
      pageParameters({ include_exact: false })) : apiPage('/api/v1/aliases', pageParameters(filters));
  const optionsPromise = api('/api/v1/admin/aliases/options', { alias_list_id: aliasListId(selectedList) });
  const [page, options] = await Promise.all([pagePromise, optionsPromise]);
  if (!renderIsCurrent(renderContext) || !main.isConnected) return;
  aliasEditorContext.page = page;
  aliasEditorContext.options = options;
  if (options?.alias_list && options?.revision !== undefined &&
      aliasListId(options.alias_list) === aliasListId(selectedList)) {
    const unmatchedPolicy = selectedList.unmatched_talkgroup_policy ||
      options.alias_list.unmatched_talkgroup_policy;
    selectedList = { ...selectedList, ...options.alias_list,
      ...(unmatchedPolicy ? { unmatched_talkgroup_policy: unmatchedPolicy } : {}) };
    const selectedIndex = lists.findIndex((row) => aliasListId(row) === aliasListId(selectedList));
    if (selectedIndex >= 0) lists[selectedIndex] = selectedList;
    aliasEditorContext.selectedList = selectedList;
    aliasEditorContext.revision = Number(options.revision);
  }
  const rows = page.rows || [];
  const visibleIds = new Set(rows.map((row) => Number(row.alias_id)));
  aliasEditorSelection = new Set([...aliasEditorSelection].filter((id) => visibleIds.has(id)));
  aliasEditorLastSelectionIndex = null;

  const summary = node('section', 'alias-list-summary');
  const summaryCopy = node('div', 'alias-list-summary-copy');
  summaryCopy.append(node('h2', '', selectedList.name), badge(aliasListFamilyLabel(selectedList), 'state-current'),
    node('span', 'muted', `${number(selectedList.alias_count || 0)} aliases · ` +
      `${number(selectedList.assigned_channel_count || 0)} assigned channels`));
  summary.append(summaryCopy);
  const listActions = node('div', 'alias-list-summary-actions');
  const add = node('button', 'button alias-add-button', 'Add Alias');
  add.type = 'button';
  add.addEventListener('click', () => openAliasEditorModal('create'));
  const remove = node('button', 'button secondary danger-outline', 'Delete List');
  remove.type = 'button';
  remove.addEventListener('click', () => openAliasListDeleteModal(selectedList));
  listActions.append(add);
  if (unmatchedTalkgroupsSupported(selectedList)) {
    const policy = node('button', 'button secondary alias-policy-button', 'Alias List Defaults');
    policy.type = 'button';
    policy.addEventListener('click', () => openUnmatchedTalkgroupPolicyModal(selectedList));
    listActions.append(policy);
  }
  listActions.append(remove);
  summary.append(listActions);
  main.append(summary, aliasEditorViewTabs(selectedList), view === 'discover' ?
    observedTalkgroupToolbar(selectedList) : aliasEditorFilterToolbar(listResponse, options));

  if (view === 'discover') {
    aliasEditorSelection.clear();
    aliasEditorLastSelectionIndex = null;
    renderObservedTalkgroups(main, page, selectedList);
    return;
  }

  const definitions = [...aliasCustomConfigurationColumns(), ...aliasCatalogEnrichmentColumns()];
  const selectedCustom = readAliasCatalogColumnSelection(definitions);
  const tableHost = node('div', 'alias-catalog-table-host alias-editor-table-host');
  let bulkBar = null;
  const updateSelection = () => {
    tableHost.querySelectorAll('.alias-row-select').forEach((checkbox) => {
      const row = checkbox.closest('tr');
      const id = Number(row?.dataset.id);
      checkbox.checked = aliasEditorSelection.has(id);
      row?.classList.toggle('selected', checkbox.checked);
    });
    bulkBar?.update();
  };
  const columnsForView = () => aliasEditorColumns(view, rows, updateSelection, selectedCustom);
  const renderTable = () => {
    const aliasTable = table(rows, columnsForView(), 'No aliases match these filters', {
      type: `alias-editor-${view}`, serverSort: true, sortable: false,
      defaultSort: 'name', defaultDirection: 'asc', rowKey: (row) => row.alias_id
    });
    tableHost.replaceChildren(aliasTable);
    aliasTable.querySelectorAll('tbody tr[data-id]').forEach((tableRow) => {
      tableRow.addEventListener('click', (event) => {
        if (event.target.closest('a, button, input, select, label')) return;
        const id = Number(tableRow.dataset.id);
        if (event.shiftKey || event.metaKey || event.ctrlKey) {
          const index = rows.findIndex((row) => Number(row.alias_id) === id);
          if (event.shiftKey && aliasEditorLastSelectionIndex !== null) {
            const start = Math.min(index, aliasEditorLastSelectionIndex);
            const end = Math.max(index, aliasEditorLastSelectionIndex);
            rows.slice(start, end + 1).forEach((row) => aliasEditorSelection.add(Number(row.alias_id)));
          } else if (aliasEditorSelection.has(id)) aliasEditorSelection.delete(id);
          else aliasEditorSelection.add(id);
          aliasEditorLastSelectionIndex = index;
          updateSelection();
          return;
        }
        window.location.assign(currentHref({ alias: id }));
      });
    });
    updateSelection();
  };

  const actions = node('div', 'section-title-actions');
  const selectPage = node('button', 'button secondary', 'Select This Page');
  selectPage.type = 'button';
  selectPage.addEventListener('click', () => {
    rows.forEach((row) => {
      if (aliasEditorSelection.size < 500) aliasEditorSelection.add(Number(row.alias_id));
    });
    updateSelection();
  });
  actions.append(selectPage);
  if (view === 'custom') {
    actions.append(aliasColumnChooser(definitions, selectedCustom, () => renderTable()));
  }
  const exportContext = { list: aliasListId(selectedList) };
  const exportFilters = new Map([
    ['type', 'type'], ['matcher', 'matcher'], ['group', 'group'],
    ['scanListId', 'scan_list_id'],
    ['record', 'record'], ['stream', 'stream'], ['evidence', 'evidence'], ['use', 'use'],
    ['lastActivityBefore', 'last_activity_before'], ['lastActivityAfter', 'last_activity_after']
  ]);
  exportFilters.forEach((queryKey, routeKey) => {
    if (route.get(routeKey)) exportContext[queryKey] = route.get(routeKey);
  });
  actions.append(exportCsvLink('aliases', exportContext));
  const block = section(view === 'configure' ? 'Alias Configuration' :
    (view === 'calls' ? 'Call Use' : (view === 'evidence' ? 'System Evidence' : 'Custom View')),
  tableHost, actions);
  block.classList.add('alias-catalog-section', 'alias-editor-table-section');
  bulkBar = aliasBulkBar(() => {
    aliasEditorSelection.clear();
    aliasEditorLastSelectionIndex = null;
    updateSelection();
  });
  block.append(bulkBar);
  renderTable();
  block.append(node('p', 'metric-meaning-note alias-catalog-guide', view === 'configure' ?
    'Configuration controls what the alias matches and what happens to its calls. Open an alias to edit it.' :
    'Calls and recordings are separate from system signaling. Logout means unit deregistration, not leaving a ' +
      'talkgroup. An em dash means unavailable or not collected; ' +
      '0 means coverage was collected and the count was zero.'), pager(page));
  main.append(block);

  if (route.get('createAlias') === '1') {
    const prefill = routedAliasPrefill(selectedList, options);
    if (prefill) await openAliasEditorModal('create', null, prefill);
  } else if (route.has('alias')) {
    const id = Number(route.get('alias'));
    if (Number.isInteger(id) && id > 0) {
      await openAliasEditorModal('edit', id);
    }
  }
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
  if (dashboardChannelKind(row) === 'TRUNKED') return siteLabel(row);
  if (row.source_label) return row.source_label;
  if (row.channel_name) return row.channel_name;
  if (row.context_key) return row.context_key;
  if (row.frequency_hz) return `${frequency(row.frequency_hz)} MHz`;
  return 'Unknown receiver';
}

function callSourceLink(row) {
  const label = callSourceLabel(row);
  const detailAvailable = Number(row.detail_available);
  if (dashboardChannelKind(row) === 'TRUNKED') {
    return siteNameSummary(row, Boolean(detailAvailable && row.guid));
  }
  if (!detailAvailable) return label;
  if (dashboardChannelKind(row) === 'CONVENTIONAL' && row.context_key &&
      capabilityAllowed(ACCESS_CAPABILITIES.CONVENTIONAL)) {
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
  if (value === 'AM' || value.includes('AMPLITUDE MODULATION')) return 'AM';
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
  return String(activity?.metric_coverage?.[field] || '').toUpperCase();
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

function signalBarLevel(value) {
  const signal = optionalNumber(value);
  if (!Number.isFinite(signal)) return 0;
  if (signal >= -65) return 4;
  if (signal >= -75) return 3;
  if (signal >= -85) return 2;
  return 1;
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
    if (!wrapper.isConnected) return;
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
  labels.append(siteNameSummary(site));
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

function rethrowPageHandlingError(error) {
  if (window.sdrtrunkPageLifecycle.requiresPageHandling(error)) throw error;
}

async function signalHealthSection() {
  const renderContext = captureRenderContext();
  const host = node('div', 'signal-health');
  const currentPanel = node('div', 'signal-current-panel');
  const currentToolbar = node('div', 'signal-current-toolbar');
  const summary = node('div', 'signal-health-summary');
  currentToolbar.append(summary);
  const tiles = node('div', 'signal-current-grid');
  currentPanel.append(currentToolbar, tiles);
  host.append(currentPanel);
  const block = section('Signal Health', host, exportCsvLink('signal-health'));
  let currentResponse = null;
  const tileNodes = new Map();
  let loading = false;

  const renderCurrent = () => {
    const now = Date.now();
    const sites = sortSignalSites((currentResponse?.rows || []).filter((site) =>
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
    message.append('Signal health requires Stats Logging.');
    if (capabilityAllowed(ACCESS_CAPABILITIES.LIVE)) {
      message.append(' ', anchor('Open Live signal levels', href('live')), '.');
    }
    tiles.append(message);
  } else {
    const loadCurrent = async (initial = false, pageOwned = false) => {
      if (loading) return;
      loading = true;
      if (initial) summary.textContent = 'Loading current signal health…';
      try {
        currentResponse = await apiPage('/api/v1/quality', {
          range: '1h', points: 60, include_history: false
        });
        renderCurrent();
      } catch (error) {
        if (pageOwned) rethrowPageHandlingError(error);
        summary.textContent = currentResponse ? `Signal health update failed: ${error.message}` : '';
        if (!currentResponse) tiles.replaceChildren(node('div', 'error', error.message));
      } finally {
        loading = false;
      }
    };
    await loadCurrent(true, true);
    if (renderIsCurrent(renderContext)) pageInterval(loadCurrent, 10_000);
  }
  return block;
}

async function siteSignalHistorySection(site) {
  const renderContext = captureRenderContext();
  const host = node('div', 'site-signal-history');
  const block = section('Control Channel Quality History', host);
  block.classList.add('site-signal-history-section');
  let selectedRange = '24h';
  let loadingSequence = 0;
  let loading = false;
  const rangeControl = signalRangeControls(selectedRange, async (value, buttons) => {
    selectedRange = value;
    exportLink.href = exportCsvHref('site-quality', { guid: site.guid, range: selectedRange });
    await load(buttons, true);
  });
  const exportLink = exportCsvLink('site-quality', { guid: site.guid, range: selectedRange });
  const titleActions = node('div', 'section-title-actions');
  titleActions.append(rangeControl.controls, exportLink);
  block.querySelector('.section-title').append(titleActions);
  const load = async (buttons = rangeControl.buttons, interactive = false, pageOwned = false) => {
    if (loading && !interactive) return;
    const sequence = ++loadingSequence;
    loading = true;
    if (interactive) {
      buttons.forEach((button) => { button.disabled = true; });
      disconnectPageObserversWithin(host);
      host.replaceChildren(node('div', 'loading', 'Loading control channel quality history'));
    }
    try {
      const response = await api(siteApiPath(site.guid, 'quality'), { range: selectedRange, points: 300 });
      if (sequence !== loadingSequence) return;
      const qualitySite = (response.rows || [])[0];
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
      if (pageOwned) rethrowPageHandlingError(error);
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
    await load(rangeControl.buttons, true, true);
    if (renderIsCurrent(renderContext)) pageInterval(load, 30_000);
  }
  return block;
}

async function talkgroupActivityHistorySection(scopeParameters) {
  const renderContext = captureRenderContext();
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

  const load = async (buttons = rangeControl.buttons, interactive = false, pageOwned = false) => {
    if (loading && !interactive) return;
    const sequence = ++loadingSequence;
    loading = true;
    if (interactive) {
      buttons.forEach((button) => { button.disabled = true; });
      host.replaceChildren(node('div', 'loading', 'Loading talkgroup activity history'));
    }
    try {
      const response = await api(groupIdentityApiPath(scopeParameters.scope, scopeParameters.kind,
        scopeParameters.talkgroup_id, 'activity'), { range: selectedRange });
      if (sequence !== loadingSequence) return;
      host.replaceChildren(metrics([
        ['Tracked Calls', response.totals?.call_count],
        ['Recorded', response.totals?.recorded_count],
        ['Sent to Streamer', response.totals?.streamed_count],
        ['Encrypted', response.totals?.encrypted_count]
      ], true),
      section('Call Activity', talkgroupActivityChart(response, TALKGROUP_CALL_ACTIVITY_SERIES,
        'Talkgroup calls and call outcomes by time')),
      section('Retained Signaling Totals', table(
        signalingCounts(response.totals || {}).map(([action, count]) => ({ action, count })), [
          { label: 'Action', key: 'action' },
          { id: 'count', label: 'Count', render: (row) => number(row.count),
            className: 'numeric', sortValue: (row) => Number(row.count || 0) }
        ], 'No signaling observations recorded', { type: 'action-counts' })),
      activityMetricGuide(true));
    } catch (error) {
      if (pageOwned) rethrowPageHandlingError(error);
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
    await load(rangeControl.buttons, true, true);
    if (renderIsCurrent(renderContext)) pageInterval(load, 30_000);
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
    { id: 'talkgroup-kind', label: 'Kind', render: (row) => groupIdentityLabel(row) },
    { id: 'talkgroup-name', label: 'Alias', fullLabel: 'Talkgroup Alias', render: (row) => talkgroupAliasLink(row, row.talkgroup_id), className: 'alias-cell', sortValue: aliasLabel },
    { label: 'Group', key: 'alias_group', className: 'alias-cell', sortValue: (row) => row.alias_group || '' },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'recorded', label: 'Rec', fullLabel: 'Recorded', render: (row) => number(row.recorded_count), className: 'numeric', sortValue: (row) => Number(row.recorded_count || 0) },
    { id: 'streamed', label: 'Sent', fullLabel: 'Sent to Streamer', render: (row) => number(row.streamed_count), className: 'numeric', sortValue: (row) => Number(row.streamed_count || 0) },
    { id: 'encrypted', label: 'Enc', fullLabel: 'Encrypted',
      render: (row) => number(row.encrypted_count), className: 'numeric encrypted',
      sortValue: (row) => Number(row.encrypted_count || 0) }
  ];

  const load = async (buttons = rangeControl.buttons, interactive = false, pageOwned = false) => {
    if (loading && !interactive) return;
    const sequence = ++loadingSequence;
    loading = true;
    if (interactive) {
      buttons.forEach((button) => { button.disabled = true; });
      host.replaceChildren(node('div', 'loading', 'Loading talkgroup call activity'));
    }
    try {
      const response = await api(siteApiPath(site.guid, 'group-identities'), {
        range: selectedRange, limit: 20
      });
      if (sequence !== loadingSequence) return;
      host.replaceChildren(table(response.rows || [], columns,
        'No talkgroup activity is available for this range', { type: 'site-top-talkgroups' }));
    } catch (error) {
      if (pageOwned) rethrowPageHandlingError(error);
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
    await load(rangeControl.buttons, true, true);
  }
  return block;
}

function snakeCaseKey(value) {
  return String(value).replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();
}

function snakeCasePayload(value) {
  if (Array.isArray(value)) return value.map(snakeCasePayload);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) =>
    [snakeCaseKey(key), snakeCasePayload(item)]));
}

async function requestJson(path, options = {}) {
  const method = String(options.method || 'GET').toUpperCase();
  const headers = new Headers(options.headers || {});
  headers.set('Accept', 'application/json');
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
  if (options.csrf !== false && !['GET', 'HEAD', 'OPTIONS'].includes(method) && accessSession.csrfToken) {
    headers.set('X-CSRF-Token', accessSession.csrfToken);
  }
  const controller = new AbortController();
  const upstreamSignal = options.signal || (options.page === false ? null : activeRenderController?.signal);
  const timeoutMs = Math.max(250, Number(options.timeoutMs) || 10_000);
  let timedOut = false;
  const abortFromUpstream = () => controller.abort(upstreamSignal?.reason);
  if (upstreamSignal?.aborted) abortFromUpstream();
  else upstreamSignal?.addEventListener('abort', abortFromUpstream, { once: true });
  const timeout = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  let response;
  let result = null;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(snakeCasePayload(options.body)),
      cache: 'no-store',
      credentials: 'same-origin',
      signal: controller.signal
    });
    const contentType = String(response.headers.get('Content-Type') || '').toLowerCase();
    if (response.status !== 204) {
      if (contentType.includes('json')) result = await response.json().catch((error) => {
        if (controller.signal.aborted) throw error;
        return null;
      });
      else {
        const message = await response.text().catch((error) => {
          if (controller.signal.aborted) throw error;
          return '';
        });
        result = message ? { error: message } : null;
      }
    }
  } catch (error) {
    if (timedOut) {
      const timeoutError = new Error('The receiver did not respond in time.');
      timeoutError.code = 'request_timeout';
      timeoutError.path = path;
      throw timeoutError;
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
    upstreamSignal?.removeEventListener('abort', abortFromUpstream);
  }
  if (!response.ok) {
    const failure = result?.error && typeof result.error === 'object' ? result.error : result;
    const fallback = typeof result?.error === 'string' ? result.error : `${path} returned ${response.status}`;
    const error = new Error(failure?.message || fallback);
    error.status = Number(failure?.status) || response.status;
    error.code = failure?.code || (typeof result?.error === 'string' ? result.error : null);
    error.field = failure?.field || null;
    error.path = path;
    throw error;
  }
  if (response.status === 204) return null;
  if (!result || typeof result !== 'object' || !Object.prototype.hasOwnProperty.call(result, 'data')) {
    const error = new Error('The API returned an invalid success response.');
    error.status = response.status;
    error.code = 'invalid_response';
    error.path = path;
    throw error;
  }
  if (Array.isArray(result.data)) return { rows: result.data, ...(result.meta || {}) };
  return result.meta && typeof result.meta === 'object' ? { ...result.data, ...result.meta } : result.data;
}

async function api(path, parameters = {}, options = {}) {
  const query = new URLSearchParams();
  Object.entries(parameters).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') query.set(snakeCaseKey(key), String(value));
  });
  return requestJson(`${path}${query.size ? `?${query}` : ''}`, { csrf: false, ...options });
}

async function apiPage(path, parameters = {}, options = {}) {
  const response = await api(path, parameters, options);
  return window.sdrtrunkPageLifecycle.decodeOffsetPage(response, path);
}

function receiverHealthSeverity(value) {
  const severity = String(value || '').trim().toLowerCase();
  if (severity === 'critical') return 'critical';
  if (severity === 'warning' || severity === 'warn') return 'warning';
  return 'healthy';
}

function receiverHealthCount(value, fallback = 0) {
  const numeric = Number(value);
  return Number.isFinite(numeric) && numeric >= 0 ? Math.trunc(numeric) : Math.max(0, fallback);
}

function normalizeReceiverHealthSnapshot(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('The receiver returned invalid health status.');
  }
  const active = Array.isArray(value.active) ? value.active.filter((incident) =>
    incident && typeof incident === 'object' && !Array.isArray(incident)) : [];
  const resolved = Array.isArray(value.resolved) ? value.resolved.filter((incident) =>
    incident && typeof incident === 'object' && !Array.isArray(incident)) : [];
  const measurements = Array.isArray(value.measurements) ? value.measurements.filter((group) =>
    group && typeof group === 'object' && !Array.isArray(group)).map((group) => ({
      ...group,
      rows: Array.isArray(group.rows) ? group.rows.filter((row) =>
        row && typeof row === 'object' && !Array.isArray(row)) : []
    })) : [];
  const reported = value.summary && typeof value.summary === 'object' && !Array.isArray(value.summary) ?
    value.summary : {};
  const activeCount = receiverHealthCount(reported.active_count, active.length);
  const warningCount = receiverHealthCount(reported.warning_count,
    active.filter((incident) => receiverHealthSeverity(incident.severity) === 'warning').length);
  const criticalCount = receiverHealthCount(reported.critical_count,
    active.filter((incident) => receiverHealthSeverity(incident.severity) === 'critical').length);
  const diagnosticCount = receiverHealthCount(reported.diagnostic_count,
    Math.max(0, active.length - activeCount));
  let severity = receiverHealthSeverity(reported.severity);
  if (criticalCount > 0) severity = 'critical';
  else if (warningCount > 0 || activeCount > 0) severity = 'warning';
  return {
    started_at_ms: Number(value.started_at_ms) || 0,
    generated_at_ms: Number(value.generated_at_ms) || 0,
    summary: { severity, active_count: activeCount, warning_count: warningCount,
      critical_count: criticalCount, diagnostic_count: diagnosticCount },
    active,
    resolved,
    measurements
  };
}

class ReceiverHealthController {
  constructor() {
    this.snapshot = null;
    this.stale = false;
    this.lastError = '';
    this.requestController = null;
    this.pageHost = null;
    this.resolvedSort = 'recent';
    this.expandedResolvedIncidents = new Set();
  }

  authorized() {
    return capabilityAllowed(ACCESS_CAPABILITIES.RECEIVER_HEALTH);
  }

  desktopEnabled() {
    return !tableOnly && this.authorized();
  }

  abortRequest() {
    const controller = this.requestController;
    this.requestController = null;
    controller?.abort();
  }

  synchronizeAccess() {
    if (!this.authorized()) {
      this.abortRequest();
      this.snapshot = null;
      this.stale = false;
      this.lastError = '';
      this.expandedResolvedIncidents.clear();
    } else if (!this.desktopEnabled()) {
      this.abortRequest();
    }
    this.updateIndicator();
  }

  async refresh() {
    if (!this.desktopEnabled() || document.hidden || this.requestController) return;
    const controller = new AbortController();
    this.requestController = controller;
    try {
      const response = await api('/api/v1/receiver-health', {}, {
        page: false, signal: controller.signal, timeoutMs: 10_000
      });
      if (this.requestController !== controller || !this.desktopEnabled()) return;
      this.snapshot = normalizeReceiverHealthSnapshot(response);
      this.stale = this.snapshot.generated_at_ms <= 0 ||
        Date.now() - this.snapshot.generated_at_ms > RECEIVER_HEALTH_STALE_MILLISECONDS;
      this.lastError = this.stale ? 'The receiver health sampler has not produced a recent snapshot.' : '';
    } catch (error) {
      if (controller.signal.aborted || this.requestController !== controller) return;
      this.stale = true;
      this.lastError = error?.message || 'Receiver health status is unavailable.';
    } finally {
      if (this.requestController === controller) this.requestController = null;
      if (this.desktopEnabled()) {
        this.updateIndicator();
        this.updatePage();
      }
    }
  }

  bindPage(host) {
    this.pageHost = host;
    this.updatePage();
  }

  updatePage() {
    if (!this.desktopEnabled() || !this.pageHost?.isConnected) return;
    renderReceiverHealthPage(this.pageHost, this.snapshot, this.stale, this.lastError);
  }

  updateIndicator() {
    const indicator = document.getElementById('receiver-health-indicator');
    const state = document.getElementById('receiver-health-indicator-state');
    if (!indicator || !state) return;
    const visible = this.desktopEnabled();
    indicator.hidden = !visible;
    if (!visible) return;

    const summary = this.snapshot?.summary;
    let className = 'loading';
    let label = 'Loading';
    let detail = 'Receiver health status is loading.';
    if (this.stale) {
      className = 'stale';
      if (summary?.severity === 'critical') {
        const count = summary.critical_count || summary.active_count;
        label = `Stale · Critical ${number(count)}`;
      } else if (summary?.severity === 'warning') {
        const count = summary.warning_count || summary.active_count;
        label = `Stale · Warning ${number(count)}`;
      } else {
        label = 'Stale';
      }
      detail = this.lastError || 'Receiver health status is stale.';
    } else if (summary?.severity === 'critical') {
      className = 'critical';
      const count = summary.critical_count || summary.active_count;
      label = `Critical ${number(count)}`;
      detail = `${number(summary.active_count)} active incident${summary.active_count === 1 ? '' : 's'}, ` +
        `${number(summary.critical_count)} critical.`;
    } else if (summary?.severity === 'warning') {
      className = 'warning';
      const count = summary.warning_count || summary.active_count;
      label = `Warning ${number(count)}`;
      detail = `${number(summary.active_count)} active incident${summary.active_count === 1 ? '' : 's'}, ` +
        `${number(summary.warning_count)} warning.`;
    } else if (summary) {
      className = 'healthy';
      label = 'Healthy';
      detail = 'No active service-impact alerts.';
      if (summary.diagnostic_count > 0) {
        detail += ` ${number(summary.diagnostic_count)} troubleshooting condition` +
          `${summary.diagnostic_count === 1 ? '' : 's'} recorded.`;
      }
    }
    if (this.snapshot?.generated_at_ms) {
      detail += ` Last update: ${exactDateTime(this.snapshot.generated_at_ms)}.`;
    }
    state.textContent = label;
    ['healthy', 'warning', 'critical', 'stale', 'loading'].forEach((status) => {
      indicator.classList.remove(`receiver-health-${status}`);
    });
    indicator.classList.add(`receiver-health-${className}`);
    indicator.title = detail;
    indicator.setAttribute('aria-label', `Health: ${label}. ${detail}`);
  }
}

const receiverHealthController = new ReceiverHealthController();

const liveConnections = new Set();
const pageConnections = new Set();
const pageObservers = new Map();
const pageTimers = new Set();
let activeRenderController = null;
let activeRenderEpoch = 0;

function captureRenderContext() {
  return Object.freeze({ epoch: activeRenderEpoch, signal: activeRenderController?.signal || null });
}

function renderIsCurrent(renderContext) {
  return Boolean(renderContext) && renderContext.epoch === activeRenderEpoch && !renderContext.signal?.aborted;
}

function beginPage(renderContext, ...children) {
  if (!renderIsCurrent(renderContext)) return false;
  content.replaceChildren(...children);
  content.setAttribute('aria-busy', 'false');
  return true;
}

function replaceAsyncContent(host, rendered) {
  const children = (Array.isArray(rendered) ? rendered.flat() : [rendered])
    .filter((child) => child !== null && child !== undefined && child !== false);
  host.replaceChildren(...children);
}

function asyncSectionFailure(error, fallbackMessage, retry) {
  const failure = node('div', 'error async-section-error');
  failure.setAttribute('role', 'alert');
  failure.append(node('div', '', error?.message || fallbackMessage || 'This section could not be loaded.'));
  const action = node('button', 'secondary async-section-retry', 'Retry');
  action.type = 'button';
  action.addEventListener('click', () => {
    action.disabled = true;
    void retry().catch((retryError) => {
      if (retryError?.name !== 'AbortError') void render();
    });
  });
  failure.append(action);
  return failure;
}

function createAsyncSection(title, options = {}) {
  const host = node('div', 'async-section-content');
  host.setAttribute('role', 'region');
  host.setAttribute('aria-label', title);
  const element = section(title, host, options.action || null);
  let loadSequence = 0;
  let focusAfterAttempt = false;

  const load = (loader, present, renderContext) => {
    const sequence = ++loadSequence;
    return window.sdrtrunkPageLifecycle.run({
      isCurrent: () => sequence === loadSequence && renderIsCurrent(renderContext) && host.isConnected,
      onLoading: ({ retry }) => {
        focusAfterAttempt = retry;
        host.setAttribute('aria-busy', 'true');
        const loading = node('div', 'loading', options.loadingMessage || 'Loading…');
        loading.setAttribute('role', 'status');
        if (retry) loading.tabIndex = -1;
        host.replaceChildren(loading);
        if (retry) loading.focus();
      },
      load: loader,
      onReady: (value) => {
        replaceAsyncContent(host, present(value));
        host.setAttribute('aria-busy', 'false');
        if (focusAfterAttempt) {
          host.tabIndex = -1;
          host.focus();
        }
      },
      onError: (error, retry) => {
        const failure = asyncSectionFailure(error, options.errorMessage, retry);
        host.replaceChildren(failure);
        host.setAttribute('aria-busy', 'false');
        if (focusAfterAttempt) failure.querySelector('.async-section-retry')?.focus();
      }
    });
  };

  return Object.freeze({ element, host, load });
}

function pageInterval(callback, interval) {
  const timer = window.setInterval(() => {
    if (!document.hidden) Promise.resolve(callback()).catch(() => {});
  }, interval);
  pageTimers.add(timer);
  return timer;
}

function pageTimeout(callback, delay) {
  const timer = window.setTimeout(() => {
    pageTimers.delete(timer);
    callback();
  }, delay);
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

const LIVE_MULTIPLEX_MAGIC = 0x534c4d58;
const LIVE_MULTIPLEX_HEADER_BYTES = 16;
const LIVE_MULTIPLEX_MAXIMUM_BYTES = 16 * 1024 * 1024;
const LIVE_MULTIPLEX_READY_TIMEOUT_MS = 10_000;
const LIVE_MULTIPLEX_LIVENESS_TIMEOUT_MS = 25_000;
const LIVE_MULTIPLEX_TOPICS = Object.freeze({
  0: 'control',
  1: 'channel_activity',
  2: 'calls',
  3: 'decode_events',
  4: 'decode_messages',
  5: 'channel_diagnostics',
  6: 'tuner_diagnostics'
});
const LIVE_MULTIPLEX_DECODER = new TextDecoder();

function randomLiveClientId() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  window.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function invokeLiveSubscriber(target, callback, ...parameters) {
  try {
    target?.[callback]?.(...parameters);
  } catch (error) {
    console.error(`Live subscriber ${callback} callback failed`, error);
  }
}

function invokeLiveListener(callback, ...parameters) {
  try {
    callback?.(...parameters);
  } catch (error) {
    console.error('Live event listener callback failed', error);
  }
}

class LiveMultiplexer {
  constructor() {
    this.subscribers = new Map();
    this.parameters = new Map();
    this.failedTopics = new Set();
    this.controller = null;
    this.reader = null;
    this.clientId = null;
    this.ready = false;
    this.pending = new Uint8Array(0);
    this.reconnectTimer = null;
    this.controlTimer = null;
    this.controlInFlight = false;
    this.controlPending = false;
    this.controlRevision = 0;
    this.controlDesiredRevision = 0;
    this.controlAppliedRevision = 0;
    this.controlWaiters = [];
    this.authorizationRecoveryUsed = false;
    this.authorizationBlocked = false;
    this.reconnectDelay = 500;
    this.attempt = 0;
    this.lastFrameAt = 0;
  }

  subscribe(topic, parameters, callbacks = {}) {
    if (!Object.values(LIVE_MULTIPLEX_TOPICS).includes(topic) || topic === 'control') {
      throw new Error(`Unknown live stream topic: ${topic}`);
    }
    const subscriber = callbacks;
    let subscribers = this.subscribers.get(topic);
    if (!subscribers) {
      subscribers = new Set();
      this.subscribers.set(topic, subscribers);
    }
    subscribers.add(subscriber);
    this.parameters.set(topic, snakeCasePayload(parameters || {}));
    if (this.ready) queueMicrotask(() => invokeLiveSubscriber(subscriber, 'onOpen'));
    this.ensureConnected();
    this.queueControl();
    let closed = false;
    return {
      close: () => {
        if (closed) return Promise.resolve();
        closed = true;
        subscribers.delete(subscriber);
        if (!subscribers.size) {
          this.subscribers.delete(topic);
          this.parameters.delete(topic);
        }
        const desiredRevision = this.queueControl(true);
        return this.closeIfIdle(desiredRevision);
      },
      update: (nextParameters = {}) => {
        if (closed) return false;
        const next = snakeCasePayload(nextParameters);
        if (JSON.stringify(next) === JSON.stringify(this.parameters.get(topic) || {})) return false;
        this.parameters.set(topic, next);
        this.queueControl();
        return true;
      },
      whenClosed: () => closed ? Promise.resolve() : new Promise((resolve) => {
        const check = window.setInterval(() => {
          if (closed) {
            window.clearInterval(check);
            resolve();
          }
        }, 25);
      })
    };
  }

  hasSubscribers() {
    return [...this.subscribers.values()].some((subscribers) => subscribers.size);
  }

  ensureConnected() {
    if (!this.hasSubscribers() || this.controller || this.reconnectTimer !== null) return;
    void this.connect();
  }

  async connect() {
    if (!this.hasSubscribers() || this.controller) return;
    const attempt = ++this.attempt;
    const controller = new AbortController();
    this.controller = controller;
    this.clientId = randomLiveClientId();
    this.ready = false;
    this.pending = new Uint8Array(0);
    let responseStatus = 0;
    let attemptReader = null;
    let watchdogTimedOut = false;
    const attemptStartedAt = Date.now();
    this.lastFrameAt = attemptStartedAt;
    const watchdog = window.setInterval(() => {
      if (this.controller !== controller || controller.signal.aborted) return;
      const lastProgress = this.ready ? this.lastFrameAt : attemptStartedAt;
      const deadline = this.ready ? LIVE_MULTIPLEX_LIVENESS_TIMEOUT_MS : LIVE_MULTIPLEX_READY_TIMEOUT_MS;
      if (Date.now() - lastProgress >= deadline) {
        watchdogTimedOut = true;
        controller.abort();
      }
    }, 1_000);
    try {
      const response = await fetch(`/api/v1/live/multiplex?client_id=${encodeURIComponent(this.clientId)}`, {
        cache: 'no-store',
        credentials: 'same-origin',
        headers: { Accept: 'application/vnd.sdrtrunk.live+binary' },
        signal: controller.signal
      });
      responseStatus = response.status;
      if (!response.ok) throw Object.assign(new Error(`Live connection returned ${response.status}`), {
        status: response.status
      });
      if (!response.body) throw new Error('This browser does not support streaming responses.');
      attemptReader = response.body.getReader();
      this.reader = attemptReader;
      while (!controller.signal.aborted) {
        const { done, value } = await attemptReader.read();
        if (done) break;
        if (this.controller !== controller) break;
        this.consume(value);
      }
      if (!controller.signal.aborted) throw new Error('The live connection ended.');
    } catch (error) {
      if (watchdogTimedOut) this.dispatchError(new Error(this.ready ?
        'The live connection stopped responding.' : 'The live connection did not become ready in time.'));
      else if (!controller.signal.aborted) this.dispatchError(error);
    } finally {
      window.clearInterval(watchdog);
      if (attemptReader) void attemptReader.cancel().catch(() => {});
      controller.abort();
      if (this.controller === controller) {
        this.controller = null;
        this.reader = null;
        this.ready = false;
        this.pending = new Uint8Array(0);
      }
    }
    if (attempt !== this.attempt || !this.hasSubscribers()) return;
    if (responseStatus === 401 || responseStatus === 403) return;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.ensureConnected();
    }, this.reconnectDelay);
    this.reconnectDelay = Math.min(10_000, Math.round(this.reconnectDelay * 1.7));
  }

  consume(chunk) {
    if (!(chunk instanceof Uint8Array) || !chunk.byteLength) return;
    if (!this.pending.byteLength) this.pending = chunk;
    else {
      const combined = new Uint8Array(this.pending.byteLength + chunk.byteLength);
      combined.set(this.pending);
      combined.set(chunk, this.pending.byteLength);
      this.pending = combined;
    }
    let offset = 0;
    while (this.pending.byteLength - offset >= LIVE_MULTIPLEX_HEADER_BYTES) {
      const header = new DataView(this.pending.buffer, this.pending.byteOffset + offset,
        this.pending.byteLength - offset);
      if (header.getUint32(0) !== LIVE_MULTIPLEX_MAGIC || header.getUint8(4) !== 1) {
        throw new Error('The live connection returned an invalid frame marker.');
      }
      const kind = header.getUint8(5);
      const topic = LIVE_MULTIPLEX_TOPICS[header.getUint16(6)];
      const payloadBytes = header.getUint32(8);
      if (!topic || ![1, 2].includes(kind) || payloadBytes > LIVE_MULTIPLEX_MAXIMUM_BYTES) {
        throw new Error('The live connection returned an unsupported frame.');
      }
      const frameBytes = LIVE_MULTIPLEX_HEADER_BYTES + payloadBytes;
      if (this.pending.byteLength - offset < frameBytes) break;
      const payload = this.pending.slice(offset + LIVE_MULTIPLEX_HEADER_BYTES, offset + frameBytes);
      this.dispatch(topic, kind, payload);
      offset += frameBytes;
    }
    this.pending = offset ? this.pending.slice(offset) : this.pending;
  }

  dispatch(topic, kind, payload) {
    this.lastFrameAt = Date.now();
    if (kind === 1) {
      let message;
      try {
        message = JSON.parse(LIVE_MULTIPLEX_DECODER.decode(payload));
      } catch (_) {
        throw new Error('The live connection returned invalid JSON.');
      }
      if (topic === 'control' && message?.event === 'ready') {
        if (String(message?.data?.client_id || '') !== this.clientId) {
          throw new Error('The live connection identifier did not match.');
        }
        this.ready = true;
        this.reconnectDelay = 500;
        this.failedTopics.clear();
        this.subscribers.forEach((subscribers) => subscribers.forEach((target) =>
          invokeLiveSubscriber(target, 'onOpen')));
        this.queueControl(true);
        return;
      }
      if (message?.event === 'error') this.failedTopics.add(topic);
      else if (this.failedTopics.delete(topic)) {
        this.subscribers.get(topic)?.forEach((target) => invokeLiveSubscriber(target, 'onOpen'));
      }
      this.subscribers.get(topic)?.forEach((target) =>
        invokeLiveSubscriber(target, 'onEvent', message?.event, message?.data));
    } else {
      const frame = decodeDiagnosticFrame(payload);
      if (this.failedTopics.delete(topic)) {
        this.subscribers.get(topic)?.forEach((target) => invokeLiveSubscriber(target, 'onOpen'));
      }
      this.subscribers.get(topic)?.forEach((target) => invokeLiveSubscriber(target, 'onFrame', frame));
    }
  }

  dispatchError(error) {
    this.subscribers.forEach((subscribers) => subscribers.forEach((target) =>
      invokeLiveSubscriber(target, 'onError', error)));
  }

  queueControl(immediate = false) {
    const desiredRevision = ++this.controlDesiredRevision;
    this.controlPending = true;
    if (!this.ready || this.controlInFlight) return desiredRevision;
    if (this.controlTimer !== null) window.clearTimeout(this.controlTimer);
    this.controlTimer = window.setTimeout(() => {
      this.controlTimer = null;
      void this.sendControl();
    }, immediate ? 0 : 20);
    return desiredRevision;
  }

  async sendControl() {
    if (!this.ready || this.controlInFlight || !this.clientId) return;
    this.controlPending = false;
    this.controlInFlight = true;
    const controlClientId = this.clientId;
    const revision = ++this.controlRevision;
    const desiredRevision = this.controlDesiredRevision;
    const subscriptions = {};
    this.subscribers.forEach((targets, topic) => {
      if (targets.size) subscriptions[topic] = this.parameters.get(topic) || {};
    });
    try {
      await requestJson('/api/v1/live/multiplex/control', {
        method: 'POST',
        body: { client_id: controlClientId, revision, subscriptions },
        page: false,
        timeoutMs: 5_000
      });
      this.controlAppliedRevision = Math.max(this.controlAppliedRevision, desiredRevision);
      this.settleControlWaiters(desiredRevision, true);
      this.authorizationRecoveryUsed = false;
      this.authorizationBlocked = false;
    } catch (error) {
      this.settleControlWaiters(desiredRevision, false);
      if (this.ready && this.clientId === controlClientId) {
        this.dispatchError(error);
        if (error?.status === 401 || error?.status === 403) {
          this.authorizationBlocked = true;
          if (!this.authorizationRecoveryUsed) {
            this.authorizationRecoveryUsed = true;
            await refreshAccessSession(false);
          }
        } else if (error?.status === 404) {
          this.restart();
        }
      }
    } finally {
      this.controlInFlight = false;
      if (this.controlPending) this.queueControl(true);
    }
  }

  settleControlWaiters(revision, delivered) {
    const pending = [];
    this.controlWaiters.forEach((waiter) => {
      if (waiter.revision <= revision) waiter.resolve(delivered);
      else pending.push(waiter);
    });
    this.controlWaiters = pending;
  }

  waitForControlRevision(revision) {
    if (this.controlAppliedRevision >= revision) return Promise.resolve(true);
    return new Promise((resolve) => this.controlWaiters.push({ revision, resolve }));
  }

  closeIfIdle(revision = this.controlDesiredRevision) {
    if (this.hasSubscribers()) return Promise.resolve();
    if (!this.ready || !this.clientId) {
      this.stop();
      return Promise.resolve();
    }
    return this.waitForControlRevision(revision).then(() => {
      if (this.hasSubscribers()) return;
      if (this.controlDesiredRevision > revision) return this.closeIfIdle(this.controlDesiredRevision);
      this.stop();
    });
  }

  stop() {
    this.attempt += 1;
    this.ready = false;
    this.clientId = null;
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    if (this.controlTimer !== null) window.clearTimeout(this.controlTimer);
    this.reconnectTimer = null;
    this.controlTimer = null;
    this.controlPending = false;
    this.settleControlWaiters(Number.MAX_SAFE_INTEGER, false);
    this.controller?.abort();
    this.controller = null;
    const reader = this.reader;
    this.reader = null;
    if (reader) void reader.cancel().catch(() => {});
    this.pending = new Uint8Array(0);
    this.lastFrameAt = 0;
  }

  restart() {
    this.stop();
    this.ensureConnected();
  }

  confirmedAccessRefresh() {
    if (!this.authorizationBlocked) return;
    this.authorizationBlocked = false;
    if (this.hasSubscribers()) this.restart();
  }
}

const liveMultiplexer = new LiveMultiplexer();
notifyConfirmedAccessRefresh = () => liveMultiplexer.confirmedAccessRefresh();

function liveConnection(topic, parameters = {}, pageScoped = true) {
  const listeners = new Map();
  let requestedParameters = parameters;
  let subscription = null;
  let closed = false;
  const callbacks = {
    onOpen: () => source.onopen?.({ type: 'open' }),
    onError: (error) => source.onerror?.(error),
    onEvent: (event, data) => {
      if (event === 'error') source.onerror?.(Object.assign(new Error(data?.message || 'Live stream unavailable'), data));
      else listeners.get(event)?.forEach((callback) =>
        invokeLiveListener(callback, { type: event, data: JSON.stringify(data) }));
    }
  };
  const synchronizeVisibility = () => {
    if (closed) return;
    if (pageScoped && document.hidden) {
      subscription?.close();
      subscription = null;
    } else if (!subscription) {
      subscription = liveMultiplexer.subscribe(topic, requestedParameters, callbacks);
    }
  };
  const source = {
    onopen: null,
    onerror: null,
    addEventListener(event, callback) {
      if (!listeners.has(event)) listeners.set(event, new Set());
      listeners.get(event).add(callback);
    },
    close() {
      if (closed) return Promise.resolve();
      closed = true;
      document.removeEventListener('visibilitychange', synchronizeVisibility);
      liveConnections.delete(source);
      pageConnections.delete(source);
      const active = subscription;
      subscription = null;
      return active?.close() || Promise.resolve();
    },
    update(nextParameters) {
      requestedParameters = nextParameters;
      return subscription?.update(nextParameters) || false;
    }
  };
  if (pageScoped) document.addEventListener('visibilitychange', synchronizeVisibility);
  synchronizeVisibility();
  liveConnections.add(source);
  if (pageScoped) pageConnections.add(source);
  return source;
}

let liveChannelActivitySource = null;
let liveChannelActivityState = 'connecting';
const liveChannelActivitySubscribers = new Set();
const liveChannelActivityTables = new Map();
let liveChannelActivityRevision = 0;
let liveChannelActivityNeedsResync = false;

function applyLiveChannelActivitySnapshot(snapshot) {
  liveChannelActivityTables.clear();
  (Array.isArray(snapshot?.tables) ? snapshot.tables : []).forEach((table) => {
    if (table?.table_id) liveChannelActivityTables.set(String(table.table_id), table);
  });
  liveChannelActivityRevision = Math.max(0, Number(snapshot?.revision) || 0);
  liveChannelActivityNeedsResync = false;
  const current = { ...snapshot, tables: [...liveChannelActivityTables.values()] };
  liveChannelActivitySubscribers.forEach((target) => invokeLiveSubscriber(target, 'snapshot', current));
}

function synchronizeLiveChannelActivitySource() {
  if (document.hidden || !liveChannelActivitySubscribers.size) {
    if (liveChannelActivitySource) {
      const source = liveChannelActivitySource;
      liveChannelActivitySource = null;
      liveChannelActivityState = 'connecting';
      source.close();
      liveConnections.delete(source);
    }
    return;
  }

  if (!liveChannelActivitySource) {
    liveChannelActivityState = 'connecting';
    const source = liveConnection('channel_activity', {}, false);
    liveChannelActivitySource = source;
    source.addEventListener('snapshot', (event) => {
      try {
        applyLiveChannelActivitySnapshot(JSON.parse(event.data));
      } catch (error) {
        //Ignore a malformed optional live update and retain the last complete snapshot.
      }
    });
    source.addEventListener('activity_table', (event) => {
      try {
        const update = JSON.parse(event.data);
        const id = String(update?.table_id || update?.table?.table_id || '');
        if (!id) return;
        const revision = Math.max(0, Number(update?.revision) || 0);
        if (liveChannelActivityNeedsResync) return;
        if (revision && revision <= liveChannelActivityRevision) return;
        if (revision && liveChannelActivityRevision && revision !== liveChannelActivityRevision + 1) {
          liveChannelActivityNeedsResync = true;
          return;
        }
        if (update.operation === 'remove') liveChannelActivityTables.delete(id);
        else if (update.table) liveChannelActivityTables.set(id, update.table);
        if (revision) liveChannelActivityRevision = revision;
        liveChannelActivitySubscribers.forEach((target) => invokeLiveSubscriber(target, 'activityTable', update));
      } catch (error) {
        //Ignore one malformed update; a later snapshot restores authoritative state.
      }
    });
    source.addEventListener('activity_resync', (event) => {
      try {
        const resync = JSON.parse(event.data);
        applyLiveChannelActivitySnapshot(resync?.snapshot || resync);
      } catch (error) {
        //A later drop-triggered authoritative snapshot remains a bounded fallback.
      }
    });
    source.onopen = () => {
      if (liveChannelActivitySource !== source) return;
      liveChannelActivityState = 'open';
      liveChannelActivitySubscribers.forEach((target) => invokeLiveSubscriber(target, 'open'));
    };
    source.onerror = () => {
      if (liveChannelActivitySource !== source) return;
      liveChannelActivityState = 'error';
      liveChannelActivitySubscribers.forEach((target) => invokeLiveSubscriber(target, 'error'));
    };
  }
}

document.addEventListener('visibilitychange', synchronizeLiveChannelActivitySource);

function subscribeLiveChannelActivity(callbacks = {}) {
  const subscriber = {
    snapshot: typeof callbacks.snapshot === 'function' ? callbacks.snapshot : null,
    activityTable: typeof callbacks.activityTable === 'function' ? callbacks.activityTable : null,
    open: typeof callbacks.open === 'function' ? callbacks.open : null,
    error: typeof callbacks.error === 'function' ? callbacks.error : null
  };
  liveChannelActivitySubscribers.add(subscriber);
  synchronizeLiveChannelActivitySource();

  if (liveChannelActivitySource) {
    if (liveChannelActivityTables.size) {
      invokeLiveSubscriber(subscriber, 'snapshot', { tables: [...liveChannelActivityTables.values()] });
    }
    if (liveChannelActivityState === 'open') invokeLiveSubscriber(subscriber, 'open');
    else if (liveChannelActivityState === 'error') invokeLiveSubscriber(subscriber, 'error');
  }

  let closed = false;
  const connection = {
    close() {
      if (closed) return;
      closed = true;
      liveChannelActivitySubscribers.delete(subscriber);
      pageConnections.delete(connection);
      if (!liveChannelActivitySubscribers.size) {
        liveChannelActivityTables.clear();
        liveChannelActivityRevision = 0;
        liveChannelActivityNeedsResync = false;
      }
      synchronizeLiveChannelActivitySource();
    }
  };
  pageConnections.add(connection);
  return connection;
}

const DIAGNOSTIC_FRAME_MAGIC = 0x53444447;
const DIAGNOSTIC_FRAME_HEADER_BYTES = 64;
const DIAGNOSTIC_FRAME_MAXIMUM_BYTES = 16 * 1024 * 1024;
const DIAGNOSTIC_TEXT_DECODER = new TextDecoder();
const DIAGNOSTIC_FRAME_TYPES = Object.freeze({
  STATE: 1,
  CHANNEL_SIGNAL: 2,
  CHANNEL_SYMBOLS: 3,
  TUNER_FFT: 4,
  HEARTBEAT: 127
});

function decodeDiagnosticFrame(encoded) {
  if (!(encoded instanceof Uint8Array) || encoded.byteLength < DIAGNOSTIC_FRAME_HEADER_BYTES) {
    throw new Error('The diagnostic stream returned a truncated frame.');
  }
  const header = new DataView(encoded.buffer, encoded.byteOffset, encoded.byteLength);
  if (header.getUint32(0, true) !== DIAGNOSTIC_FRAME_MAGIC) {
    throw new Error('The diagnostic stream returned an invalid frame marker.');
  }
  const version = header.getUint8(4);
  const type = header.getUint8(5);
  const headerBytes = header.getUint16(6, true);
  const payloadBytes = header.getUint32(8, true);
  const valueCount = header.getUint32(12, true);
  if (version !== 1 || headerBytes < DIAGNOSTIC_FRAME_HEADER_BYTES || headerBytes > 4096 ||
      payloadBytes > DIAGNOSTIC_FRAME_MAXIMUM_BYTES || headerBytes + payloadBytes !== encoded.byteLength) {
    throw new Error('The diagnostic stream returned an unsupported frame.');
  }
  return {
    version,
    type,
    valueCount,
    generation: Number(header.getBigInt64(16, true)),
    sequence: Number(header.getBigInt64(24, true)),
    observedAtEpochMs: Number(header.getBigInt64(32, true)),
    encodedAtEpochMs: Number(header.getBigInt64(40, true)),
    centerFrequencyHz: Number(header.getBigInt64(48, true)),
    sampleRateHz: header.getInt32(56, true),
    fftSize: header.getInt32(60, true),
    firstBin: headerBytes >= 68 ? header.getInt32(64, true) : 0,
    sourceBinCount: headerBytes >= 72 ? header.getInt32(68, true) : valueCount,
    payload: encoded.slice(headerBytes)
  };
}

function binaryFrameConnection(topic, parameters = {}, callbacks = {}) {
  const connection = liveMultiplexer.subscribe(topic, parameters, {
    onOpen: () => callbacks.onOpen?.(),
    onError: (error) => callbacks.onError?.(error instanceof Error ? error : new Error(String(error))),
    onEvent: (event, data) => {
      if (event === 'error') callbacks.onError?.(Object.assign(new Error(data?.message ||
        'Diagnostic stream unavailable'), data));
    },
    onFrame: (frame) => callbacks.onFrame?.(frame)
  });
  const close = connection.close;
  connection.close = () => {
    liveConnections.delete(connection);
    pageConnections.delete(connection);
    return close();
  };
  liveConnections.add(connection);
  pageConnections.add(connection);
  return connection;
}

function diagnosticJsonPayload(frame) {
  try {
    return JSON.parse(DIAGNOSTIC_TEXT_DECODER.decode(frame.payload));
  } catch (error) {
    throw new Error('The diagnostic stream returned invalid state data.');
  }
}

function diagnosticFloatPayload(frame) {
  const count = Math.max(0, Number(frame.valueCount || 0));
  const payloadBytes = frame.payload.byteLength;
  const valueBits = payloadBytes === count * 4 ? 32 : payloadBytes === count * 2 ? 16 :
    payloadBytes === count ? 8 : payloadBytes === Math.ceil(count / 2) ? 4 :
      payloadBytes === Math.ceil(count / 4) ? 2 : 0;
  if (!valueBits) throw new Error('The diagnostic stream returned unsupported values.');
  const values = new Float32Array(count);
  const data = new DataView(frame.payload.buffer, frame.payload.byteOffset, frame.payload.byteLength);
  const maximumCode = valueBits === 32 ? 0 : (1 << valueBits) - 1;
  for (let index = 0; index < count; index += 1) {
    if (valueBits === 32) values[index] = data.getFloat32(index * 4, true);
    else {
      const code = valueBits === 16 ? data.getUint16(index * 2, true) : valueBits === 8 ? data.getUint8(index) :
        data.getUint8(Math.floor(index * valueBits / 8)) >> (index * valueBits % 8) & maximumCode;
      values[index] = -196 + code * 216 / maximumCode;
    }
  }
  return values;
}

function diagnosticFrameLatency(frame, clock) {
  const receivedAt = Date.now();
  const transit = receivedAt - frame.encodedAtEpochMs;
  if (!Number.isFinite(transit)) return null;
  clock.offsetMs = Number.isFinite(clock.offsetMs) ? Math.min(clock.offsetMs, transit) : transit;
  const latency = receivedAt - frame.observedAtEpochMs - clock.offsetMs;
  return Number.isFinite(latency) && latency >= 0 && latency < 60_000 ? latency : null;
}

function updateDiagnosticReadouts(target, values) {
  const labels = values.map(([label]) => label).join('|');
  if (target.dataset.labels !== labels) {
    target.dataset.labels = labels;
    target.replaceChildren(...values.map(([label, value]) => {
      const item = node('span', 'channel-diagnostic-readout');
      item.append(node('small', '', label), node('strong', '', value));
      return item;
    }));
  }
  values.forEach(([, value], index) => {
    const output = target.children[index]?.querySelector('strong');
    if (output && output.textContent !== String(value)) output.textContent = String(value);
  });
}

function closePageConnections() {
  pageConnections.forEach((source) => {
    source.close();
    liveConnections.delete(source);
  });
  pageConnections.clear();
  pageObservers.forEach((target, observer) => observer.disconnect());
  pageObservers.clear();
  pageTimers.forEach((timer) => {
    window.clearInterval(timer);
    window.clearTimeout(timer);
  });
  pageTimers.clear();
}

window.addEventListener('beforeunload', (event) => {
  if (activeReadOnlyModal?.isDirty?.()) {
    event.preventDefault();
    event.returnValue = '';
  }
  liveConnections.forEach((source) => source.close());
  liveConnections.clear();
});

function restorePlaybackBarBeforeRender() {
  const bar = document.getElementById('playback-bar');
  const slot = document.getElementById('desktop-playback-slot');
  if (!bar || !slot || !content.contains(bar)) return;
  bar.classList.remove('scanner-expanded');
  bar.querySelectorAll('details:not(.playback-control-menu)').forEach((panel) => { panel.open = false; });
  const controlMenu = bar.querySelector('.playback-control-menu');
  if (controlMenu) controlMenu.open = !navigationUsesDrawer();
  slot.append(bar);
  bar.setAttribute('aria-label', 'Web call playback');
}

function placePlaybackBar() {
  if (tableOnly) return;
  const bar = document.getElementById('playback-bar');
  const slot = document.getElementById('desktop-playback-slot');
  if (!bar || !slot) return;
  const scannerHost = route.get('view') === 'scanner' ? document.querySelector('.scanner-player-host') : null;
  bar.classList.toggle('scanner-expanded', Boolean(scannerHost));
  (scannerHost || slot).append(bar);
  bar.setAttribute('aria-label', scannerHost ? 'Browser scanner and call playback' : 'Web call playback');
  bar.querySelectorAll('details:not(.playback-control-menu)')
    .forEach((panel) => { panel.open = Boolean(scannerHost); });
  const controlMenu = bar.querySelector('.playback-control-menu');
  if (controlMenu) controlMenu.open = !navigationUsesDrawer();
}

function initializePlaybackHeader() {
  if (tableOnly) return;
  const bar = document.getElementById('playback-bar');
  if (bar) {
    bar.classList.add('access-unavailable');
    bar.setAttribute('aria-disabled', 'true');
    bar.querySelectorAll('button, input').forEach((control) => { control.disabled = true; });
  }
  const controlMenu = bar?.querySelector('.playback-control-menu');
  const panels = [...(bar?.querySelectorAll('details:not(.playback-control-menu)') || [])];
  panels.forEach((panel) => panel.addEventListener('toggle', () => {
    if (!panel.open || bar.classList.contains('scanner-expanded')) return;
    if (navigationUsesDrawer() && controlMenu) controlMenu.open = false;
    panels.forEach((other) => { if (other !== panel) other.open = false; });
  }));
  controlMenu?.addEventListener('toggle', () => {
    if (!controlMenu.open || !navigationUsesDrawer()) return;
    panels.forEach((panel) => { panel.open = false; });
  });
  window.matchMedia(NAVIGATION_DRAWER_MEDIA).addEventListener('change', () => {
    if (controlMenu) controlMenu.open = !navigationUsesDrawer();
  });
  if (controlMenu) controlMenu.open = !navigationUsesDrawer();
  document.addEventListener('click', (event) => {
    if (bar?.classList.contains('scanner-expanded')) return;
    panels.forEach((panel) => {
      if (panel.open && !panel.contains(event.target)) panel.open = false;
    });
    if (navigationUsesDrawer() && controlMenu?.open && !controlMenu.contains(event.target)) {
      controlMenu.open = false;
    }
  });
}

async function refreshPlaybackScanLists(force = false) {
  const player = window.sdrtrunkWebPlayer;
  if (!player || !capabilityAllowed(ACCESS_CAPABILITIES.CALL_AUDIO)) return;
  if (playbackScanListLoading && !force) return;
  const request = ++playbackScanListRequest;
  playbackScanListLoading = true;
  player.setScanListsLoading();
  try {
    const response = await api('/api/v1/scan-lists');
    if (request !== playbackScanListRequest || player !== window.sdrtrunkWebPlayer ||
        !capabilityAllowed(ACCESS_CAPABILITIES.CALL_AUDIO)) return;
    const rows = Array.isArray(response?.rows) ? response.rows :
      (Array.isArray(response?.scan_lists) ? response.scan_lists : []);
    const limits = response?.limits && typeof response.limits === 'object' ?
      { ...response, ...response.limits } : response;
    player.setScanLists(rows, limits);
  } catch (error) {
    if (request !== playbackScanListRequest || player !== window.sdrtrunkWebPlayer) return;
    const message = error?.status === 404 ? 'Scan lists are not available on this receiver' :
      'Unable to load scan lists';
    player.setScanListsUnavailable(message);
  } finally {
    if (request === playbackScanListRequest) playbackScanListLoading = false;
  }
}

function synchronizePlaybackAccess(accessChanged = false) {
  if (tableOnly) return;
  if (accessChanged) scannerSiteCache.clear();
  const allowed = capabilityAllowed(ACCESS_CAPABILITIES.CALL_AUDIO);
  const bar = document.getElementById('playback-bar');
  const status = document.getElementById('playback-status');
  if (!bar || !status) return;
  bar.classList.toggle('access-unavailable', !allowed);
  bar.setAttribute('aria-disabled', String(!allowed));

  if (!allowed) {
    playbackScanListRequest++;
    playbackScanListLoading = false;
    const unavailableMessage = !accessSessionAvailable ? 'Access unavailable' :
      (accessSession.authenticated ? 'Web audio unavailable' : 'Sign in for web audio');
    if (window.sdrtrunkWebPlayer) window.sdrtrunkWebPlayer.disconnect(unavailableMessage);
    else status.textContent = unavailableMessage;
    bar.querySelectorAll('button, input').forEach((control) => { control.disabled = true; });
    return;
  }

  if (!window.sdrtrunkWebPlayer) {
    window.sdrtrunkWebPlayer = new WebCallPlayer({
      play: 'playback-play',
      skip: 'playback-skip',
      replay: 'playback-replay',
      hold: 'playback-hold',
      avoid: 'playback-avoid',
      avoidList: 'playback-avoid-list',
      clearQueue: 'playback-clear-queue',
      volume: 'playback-volume',
      current: 'playback-current',
      queued: 'playback-queued',
      queueList: 'playback-queue-list',
      status: 'playback-status',
      progress: 'playback-progress',
      scanListSummary: 'playback-scan-list-summary',
      scanListOptions: 'playback-scan-list-options',
      scanListStatus: 'playback-scan-list-status'
    });
  }
  window.sdrtrunkWebPlayer.setActions({
    openAvoidList: openPlaybackAvoidList,
    openRecentCalls: openPlaybackRecentCalls,
    openScanListCoverage: openPlaybackScanListCoverage
  });
  bar.querySelectorAll('button, input').forEach((control) => { control.disabled = false; });
  window.sdrtrunkWebPlayer.render();
  window.sdrtrunkWebPlayer.renderScanLists();
  if (accessChanged || !window.sdrtrunkWebPlayer.scanListsReady()) refreshPlaybackScanLists(accessChanged);
  if (!window.sdrtrunkWebPlayer.connectionFactory) {
    window.sdrtrunkWebPlayer.connect('calls',
      (topic, parameters) => liveConnection(topic, parameters, false));
  }
}

const SCANNER_DETAIL_LEVELS = Object.freeze({ simple: 0, normal: 1, advanced: 2, engineer: 3 });
const SCANNER_SITE_CACHE_LIMIT = 64;
const scannerSiteCache = new Map();
let scannerDetailMode = 'normal';

function scannerRelativeAge(timestamp) {
  const value = Number(timestamp);
  if (!Number.isFinite(value) || value <= 0) return 'Time unavailable';
  const seconds = Math.max(0, Math.floor((Date.now() - value) / 1000));
  if (seconds < 5) return 'Just now';
  if (seconds < 60) return `${seconds} seconds ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours} hour${hours === 1 ? '' : 's'} ago`;
}

function scannerSiteMetadata(guid) {
  const key = String(guid || '').trim();
  if (!key) return Promise.resolve(null);
  if (scannerSiteCache.has(key)) return scannerSiteCache.get(key);
  const request = api(siteApiPath(key), {}, { page: false }).catch(() => null);
  scannerSiteCache.set(key, request);
  while (scannerSiteCache.size > SCANNER_SITE_CACHE_LIMIT) {
    scannerSiteCache.delete(scannerSiteCache.keys().next().value);
  }
  return request;
}

function scannerIdentifierNumber(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) && numeric >= 0 ? Math.trunc(numeric) : null;
}

function scannerHex(value, width) {
  const numeric = scannerIdentifierNumber(value);
  return numeric === null ? '' : numeric.toString(16).toUpperCase().padStart(width, '0');
}

function scannerNativeIdentifier(call) {
  const protocol = String(call?.protocol || call?.decoder || '').toUpperCase();
  if (protocol.includes('P25') || protocol.includes('APCO25')) {
    const values = [];
    const wacn = scannerHex(call?.wacn, 5);
    const system = scannerHex(call?.system_id, 3);
    const rfss = scannerIdentifierNumber(call?.rfss_id);
    const site = scannerIdentifierNumber(call?.site_id);
    if (wacn) values.push(`WACN ${wacn}`);
    if (system) values.push(`SYSID ${system}`);
    if (rfss !== null) values.push(`RFSS ${rfss}`);
    if (site !== null) values.push(`SITE ${site}`);
    return values.join(' · ');
  }
  const values = [];
  const network = scannerIdentifierNumber(call?.network_id);
  const system = scannerIdentifierNumber(call?.system_id);
  const site = scannerIdentifierNumber(call?.site_id);
  const ran = scannerIdentifierNumber(call?.ran);
  if (network !== null) values.push(`Network ${network}`);
  if (system !== null) values.push(`System ${system}`);
  if (site !== null) values.push(`Site ${site}`);
  if (ran !== null) values.push(`RAN ${ran}`);
  return values.join(' · ');
}

function scannerTargetLabel(call) {
  return call?.target_alias || (call?.target_id ? `${identifierTypeLabel(call.target_form)} ${call.target_id}` :
    call?.channel || 'Waiting for a call');
}

function identifierTypeLabel(form) {
  const normalized = String(form || '').toUpperCase();
  if (normalized === 'PATCH_GROUP') return 'Patch';
  if (normalized === 'RADIO') return 'Radio';
  if (normalized === 'TALKGROUP') return 'TGID';
  return 'ID';
}

function scannerSourceAlias(call) {
  const alias = String(call?.source_alias || '').trim();
  const talker = String(call?.talker_alias || '').trim();
  if (alias && talker && alias.toLowerCase() !== talker.toLowerCase()) return `${alias} · TA: ${talker}`;
  return alias || talker || '';
}

function scannerMatchedScanLists(call, state) {
  const values = call?._matchedScanListIds ?? call?.scan_list_ids ?? [];
  const ids = Array.isArray(values) ? [...new Set(values.map(String))] : [];
  const names = new Map((state?.scanLists || []).map((item) => [String(item.id), item.name]));
  return ids.map((id) => names.get(id) || `Scan list ${id}`).join(' · ');
}

function scannerFrequency(call) {
  const frequency = Number(call?.frequency_hz);
  return Number.isFinite(frequency) && frequency > 0 ? `${(frequency / 1_000_000).toFixed(5)} MHz` : '';
}

function scannerDuration(milliseconds) {
  const value = Number(milliseconds);
  return Number.isFinite(value) && value > 0 ? `${(value / 1000).toFixed(1)} sec` : '';
}

function scannerField(label, value, level, action, wide = false) {
  if (level > SCANNER_DETAIL_LEVELS[scannerDetailMode]) return null;
  const field = node('div', `scanner-field${wide ? ' wide' : ''}`);
  field.append(node('span', 'scanner-field-label', label));
  const text = value === null || value === undefined || String(value).trim() === '' ? '—' : String(value);
  if (action && text !== '—') {
    const link = node('button', 'scanner-field-link', text);
    link.type = 'button';
    link.addEventListener('click', action);
    field.append(link);
  } else {
    field.append(node('strong', 'scanner-field-value', text));
  }
  return field;
}

function scannerParticipant(title, alias, identifier, description, group, aliasAction, identifierAction) {
  const participant = node('section', 'scanner-participant');
  participant.append(node('strong', 'scanner-participant-heading', title));
  const fields = node('div', 'scanner-participant-fields');
  [
    scannerField(title === 'Source' ? 'Alias / Talker Alias' : 'Alias', alias, 0, aliasAction, true),
    scannerField('ID', identifier, 1, identifierAction),
    scannerField('Group', group, 1),
    scannerField('Description', description, 1, null, true)
  ].filter(Boolean).forEach((field) => fields.append(field));
  participant.append(fields);
  return participant;
}

async function scannerNavigate(call, destination, knownSite = null) {
  const guid = String(call?.site_guid || '').trim();
  const site = knownSite || (guid ? await scannerSiteMetadata(guid) : null);
  const scopeToken = site?.scope_token || '';
  const configurationId = String(call?.configuration_id || '').trim();
  let target = null;

  if (destination === 'system' && scopeToken) {
    target = href('system', { scope: scopeToken, tab: 'info' });
  } else if ((destination === 'target' || destination === 'target-alias') && scopeToken && call?.target_id) {
    if (String(call.target_form || '').toUpperCase() === 'RADIO') {
      target = href('radio', { scope: scopeToken, id: call.target_id, tab: 'info' });
    } else {
      target = href('talkgroup', { scope: scopeToken, id: call.target_id,
        kind: String(call.target_form || '').toUpperCase() === 'PATCH_GROUP' ? 'patch_group' : null, tab: 'info' });
    }
  } else if ((destination === 'source' || destination === 'source-alias') && scopeToken && call?.source_id) {
    target = href('radio', { scope: scopeToken, id: call.source_id, tab: 'info' });
  } else if (guid) {
    const tab = ['channel', 'frequency', 'lcn'].includes(destination) ? 'channels' : 'info';
    target = href('site', { guid, tab });
  } else if (configurationId) {
    target = href('conventional-detail', { context: configurationId, tab: 'info' });
  }

  if (!target) {
    openReadOnlyModal('Details unavailable', node('p', '',
      'This completed call does not include a stable destination for that detail page.'),
      { id: 'scanner-navigation' });
    return;
  }
  window.history.pushState({}, '', target);
  route = new URLSearchParams(new URL(target, window.location.href).search);
  await render();
}

function scannerVoiceMeter(call) {
  const quality = Number(call?.vc_quality_pct);
  const measured = Number.isFinite(quality) && quality >= 0;
  const bounded = measured ? Math.max(0, Math.min(100, quality)) : 0;
  const strength = measured ? Math.ceil(bounded / 20) : 0;
  const bars = node('span', 'scanner-quality-bars');
  bars.setAttribute('role', 'img');
  bars.setAttribute('aria-label', measured ? `Voice quality ${Math.round(bounded)} percent` :
    'Voice quality not measured');
  for (let index = 1; index <= 5; index++) {
    const bar = node('i', index <= strength ? 'active' : '');
    bar.setAttribute('aria-hidden', 'true');
    bars.append(bar);
  }
  return bars;
}

function renderScannerCall(host, state, site) {
  const call = state.current;
  host.replaceChildren();
  if (!call) {
    const idle = node('div', 'scanner-idle');
    idle.append(node('strong', '', state.paused ? 'Ready to listen' : 'Scanning selected lists'),
      node('span', '', state.paused ? 'Press Play to receive completed calls.' :
        'The next matching completed call will appear here.'));
    host.append(idle);
    return;
  }

  const intro = node('div', 'scanner-call-intro');
  const copy = node('div');
  copy.append(node('span', 'scanner-call-kind', `${call.decoder || call.protocol || 'Call'}${call.encrypted ?
    ' · Encrypted' : ' · Voice'}`), node('strong', 'scanner-call-title', scannerTargetLabel(call)),
    node('span', 'scanner-call-subtitle', [call.system, call.site].filter(Boolean).join(' · ')));
  const wave = node('div', `scanner-audio-wave${state.paused || !state.currentReady ? ' paused' : ''}`);
  for (let index = 0; index < 24; index++) wave.append(node('i'));
  wave.setAttribute('aria-label', state.paused ? 'Audio paused' : 'Audio playing');
  const instruments = node('div', 'scanner-call-instruments');
  instruments.append(scannerVoiceMeter(call), wave);
  intro.append(copy, instruments);

  const fields = node('div', 'scanner-field-grid');
  const open = (destination) => () => void scannerNavigate(call, destination, site);
  const participants = node('div', 'scanner-participant-grid');
  participants.append(
    scannerParticipant('Target', call.target_alias, call.target_id, call.target_description, call.target_group,
      open('target-alias'), open('target')),
    scannerParticipant('Source', scannerSourceAlias(call), call.source_id, call.source_description,
      call.source_group, open('source-alias'), open('source'))
  );
  const nativeIdentifier = scannerNativeIdentifier(call);
  const nac = scannerHex(call.nac, 3) || (scannerIdentifierNumber(call.ran) !== null ? String(call.ran) : '');
  const modulation = site?.p25_decoder_mode || call.modulation || '';
  [
    scannerField('System', call.system, 0, open('system')),
    scannerField('Site', call.site, 0, open('site')),
    scannerField('Matched Scan Lists', scannerMatchedScanLists(call, state), 1, null, true),
    scannerField('Channel', call.channel, 1, open('channel'), true),
    scannerField('Identifier', nativeIdentifier, 2, open('identifier'), true),
    scannerField(call.ran !== null && call.ran !== undefined ? 'RAN' : 'NAC', nac, 2, open('identifier')),
    scannerField('Frequency', scannerFrequency(call), 1, open('frequency')),
    scannerField('LCN', call.lcn, 2, open('lcn')),
    scannerField('Decoder', call.decoder, 1, open('decoder')),
    scannerField('Modulation', modulation ? `${modulation} · configured` : '', 2, open('decoder'))
  ].filter(Boolean).forEach((field) => fields.append(field));

  const engineer = node('div', 'scanner-engineer-grid');
  if (scannerDetailMode === 'engineer') {
    const values = [
      ['Call ID', call.call_id], ['Protocol', call.protocol],
      ['Started', exactDateTime(call.started_at_ms)], ['Completed', exactDateTime(call.completed_at_ms)],
      ['Duration', scannerDuration(call.duration_ms)], ['Timeslot', call.timeslot],
      ['Encryption', call.encrypted ? 'Encrypted' : 'Clear'], ['Alias List', call.alias_list],
      ['Decoded Frames', call.vc_decoded_frames], ['Repeated Frames', call.vc_repeated_frames],
      ['Concealed Frames', call.vc_concealed_frames], ['Missing Frames', call.vc_missing_frames],
      ['FEC Errors', call.vc_fec_errors], ['FEC Protected Bits', call.vc_fec_protected_bits],
      ['Configuration ID', call.configuration_id], ['Site GUID', call.site_guid],
      ['System Identity', call.system_identity], ['Site Identity', call.site_identity],
      ['Channel Identity', call.channel_identity]
    ];
    values.forEach(([label, value]) => {
      const item = node('div', 'scanner-engineer-item');
      item.append(node('span', '', label), node('strong', '', value === null || value === undefined || value === '' ?
        '—' : String(value)));
      engineer.append(item);
    });
  }
  host.append(intro, participants, fields);
  if (engineer.childNodes.length) host.append(engineer);
}

function scannerControl(label, action, className = '') {
  const button = node('button', `scanner-key ${className}`.trim(), label);
  button.type = 'button';
  button.addEventListener('click', action);
  return button;
}

function openPlaybackAvoidList(player = window.sdrtrunkWebPlayer) {
  if (!player) return;
  const body = node('div', 'scanner-modal-list');
  const renderRows = () => {
    body.replaceChildren();
    const avoids = player.viewState().avoids;
    if (!avoids.length) {
      body.append(node('div', 'empty', 'No browser avoids are active.'));
      return;
    }
    avoids.forEach((avoid) => {
      const row = node('div', 'scanner-modal-row');
      const copy = node('div');
      copy.append(node('strong', '', avoid.label || 'Avoided target'),
        node('span', '', `${avoid.details || avoid.key} · ${scannerRelativeAge(avoid.addedAtMs)}`));
      const remove = node('button', 'secondary', 'Remove');
      remove.type = 'button';
      remove.addEventListener('click', () => {
        player.removeAvoid(avoid.key);
        renderRows();
      });
      row.append(copy, remove);
      body.append(row);
    });
  };
  renderRows();
  openReadOnlyModal('Avoid List', body, { id: 'scanner-avoids', className: 'scanner-list-modal' });
}

function openPlaybackRecentCalls(player = window.sdrtrunkWebPlayer) {
  if (!player) return;
  const body = node('div', 'scanner-modal-list');
  const calls = player.viewState().recentCalls;
  if (!calls.length) body.append(node('div', 'empty', 'No recent calls are available in this browser session.'));
  calls.forEach((call) => {
    const row = node('div', 'scanner-modal-row');
    const copy = node('div');
    copy.append(node('strong', '', player.callLabel(call)), node('span', '',
      `${scannerRelativeAge(call.completed_at_ms)}${call.duration_ms ? ` · ${scannerDuration(call.duration_ms)}` : ''}`));
    const replay = node('button', call._audioUnavailable ? 'secondary' : '',
      call._audioUnavailable ? 'Unavailable' : 'Replay');
    replay.type = 'button';
    replay.disabled = Boolean(call._audioUnavailable);
    replay.addEventListener('click', async () => {
      replay.disabled = true;
      const started = await player.replayRecent(call._logicalCallId);
      if (started) closeReadOnlyModal(true, true);
      else replay.disabled = false;
    });
    row.append(copy, replay);
    body.append(row);
  });
  body.append(node('p', 'scanner-modal-note',
    'Recent audio is session-only and remains replayable only while retained in the receiver cache (up to 30 minutes).'));
  openReadOnlyModal('Recent Calls', body, { id: 'scanner-recent', className: 'scanner-list-modal' });
}

function scanListCoverageTree(coverage) {
  const host = node('div', 'scanner-coverage-tree');
  const aliases = Array.isArray(coverage?.aliases) ? coverage.aliases : [];
  const lists = new Map();
  aliases.forEach((alias) => {
    const listKey = String(alias.alias_list_id ?? alias.alias_list ?? 'unknown');
    if (!lists.has(listKey)) lists.set(listKey, { name: alias.alias_list || 'Alias List', groups: new Map() });
    const list = lists.get(listKey);
    const groupName = String(alias.group || 'Ungrouped');
    if (!list.groups.has(groupName)) list.groups.set(groupName, []);
    list.groups.get(groupName).push(alias);
  });
  if (!lists.size && !(coverage?.unmatched_alias_lists || []).length) {
    host.append(node('div', 'empty', 'This scan list has no members.'));
    return host;
  }
  lists.forEach((list) => {
    const listDetails = node('details', 'scanner-coverage-list');
    listDetails.open = true;
    listDetails.append(node('summary', '', `${list.name} · ${[...list.groups.values()]
      .reduce((count, rows) => count + rows.length, 0)} aliases`));
    list.groups.forEach((rows, groupName) => {
      const group = node('details', 'scanner-coverage-group');
      group.open = true;
      group.append(node('summary', '', `${groupName} · ${rows.length}`));
      const values = node('ul');
      rows.forEach((alias) => {
        const item = node('li');
        item.append(node('strong', '', alias.name || 'Unnamed alias'));
        if (alias.matcher) item.append(node('span', '', alias.matcher));
        values.append(item);
      });
      group.append(values);
      listDetails.append(group);
    });
    host.append(listDetails);
  });
  const unmatched = Array.isArray(coverage?.unmatched_alias_lists) ? coverage.unmatched_alias_lists : [];
  if (unmatched.length) {
    const rules = node('div', 'scanner-unmatched-rules');
    rules.append(node('strong', '', 'Unmatched talkgroups'));
    unmatched.forEach((item) => rules.append(node('span', '', `${item.name} · ${semanticLabel(item.family)}`)));
    host.append(rules);
  }
  if (coverage?.aliases_truncated) host.append(node('div', 'warning',
    `Showing the first ${number(coverage.maximum_aliases)} of ${number(coverage.alias_count)} aliases.`));
  return host;
}

function openPlaybackScanListCoverage(player = window.sdrtrunkWebPlayer, preferredId = null) {
  if (!player) return;
  const available = player.viewState().scanLists.filter((item) => item.enabled);
  const selected = available.filter((item) => item.selected);
  const choices = selected.length ? selected : available;
  const body = node('div', 'scanner-coverage-modal');
  const chooser = node('div', 'scanner-coverage-choices');
  const contentHost = node('div', 'scanner-coverage-content');
  body.append(chooser, contentHost);
  let controller = null;
  const modal = openReadOnlyModal('Scan List Coverage', body, {
    id: 'scanner-coverage', className: 'scanner-list-modal scanner-coverage-modal-shell',
    cleanup: () => controller?.abort()
  });
  if (!modal) return;
  if (!choices.length) {
    contentHost.append(node('div', 'empty', 'No scan lists are available.'));
    return;
  }

  const load = async (scanList) => {
    controller?.abort();
    controller = new AbortController();
    chooser.querySelectorAll('button').forEach((button) =>
      button.classList.toggle('active', button.dataset.id === scanList.id));
    contentHost.replaceChildren(node('div', 'loading', 'Loading coverage'));
    try {
      const coverage = await api(`/api/v1/scan-lists/${encodeURIComponent(scanList.id)}/coverage`, {},
        { signal: controller.signal, page: false });
      if (!modal.dialog.isConnected) return;
      contentHost.replaceChildren(scanListCoverageTree(coverage));
    } catch (error) {
      if (error?.name !== 'AbortError' && modal.dialog.isConnected) {
        contentHost.replaceChildren(node('div', 'error', error.message || 'Unable to load scan-list coverage.'));
      }
    }
  };
  choices.forEach((scanList) => {
    const button = node('button', 'secondary', scanList.name);
    button.type = 'button';
    button.dataset.id = scanList.id;
    button.addEventListener('click', () => void load(scanList));
    chooser.append(button);
  });
  const initial = choices.find((item) => item.id === String(preferredId || '')) || choices[0];
  void load(initial);
}

function renderScanner() {
  const renderContext = captureRenderContext();
  const player = window.sdrtrunkWebPlayer;
  const page = node('div', 'scanner-page');
  if (!player) {
    page.append(node('div', 'error', 'Browser call playback is unavailable.'));
    beginPage(renderContext, pageHeader('Scanner', 'Listen to completed calls from this receiver'), page);
    return;
  }

  const modeBar = node('div', 'scanner-view-modes');
  Object.entries({ simple: 'Simple', normal: 'Normal', advanced: 'Advanced', engineer: 'Engineer' })
    .forEach(([id, label]) => {
      const button = node('button', scannerDetailMode === id ? 'active' : '', label);
      button.type = 'button';
      button.dataset.mode = id;
      modeBar.append(button);
    });
  const chassis = node('section', 'scanner-chassis');
  const statusBar = node('div', 'scanner-status-bar');
  const playbackStatus = node('strong', 'scanner-live-status', 'Ready');
  const age = node('output', 'scanner-relative-age', 'Time unavailable');
  statusBar.append(playbackStatus, age);
  const displayShell = node('div', 'scanner-display-shell');
  const display = node('div', 'scanner-display');
  displayShell.append(display);
  const replayBanner = node('div', 'scanner-replay-banner');
  const replayCopy = node('span', '', 'Replaying a recent call. Live playback is paused at its saved position.');
  const returnLive = node('button', '', 'Return to live');
  returnLive.type = 'button';
  returnLive.addEventListener('click', () => void player.returnToLive());
  replayBanner.append(replayCopy, returnLive);
  replayBanner.hidden = true;

  const controls = node('div', 'scanner-controls');
  const play = scannerControl('Play', () => void player.togglePlayback(), 'primary');
  const replay = scannerControl('Replay Call', () => void player.replayCurrent());
  const skip = scannerControl('Skip', () => player.skip());
  const hold = scannerControl('Hold', () => player.toggleHold());
  const avoid = scannerControl('Avoid', () => player.avoidCurrent(), 'danger');
  const avoidList = scannerControl('Avoid List', () => openPlaybackAvoidList(player));
  avoidList.dataset.scannerAction = 'avoid-list';
  const recent = scannerControl('Recent Calls', () => openPlaybackRecentCalls(player));
  const clearQueue = scannerControl('Clear Queue', () => player.clearQueue());
  controls.append(play, replay, skip, hold, avoid, avoidList, recent, clearQueue);

  const utility = node('div', 'scanner-utility-row');
  const volume = node('input');
  volume.type = 'range';
  volume.min = '0';
  volume.max = '1';
  volume.step = '0.05';
  volume.value = String(player.volume);
  volume.setAttribute('aria-label', 'Browser playback volume');
  volume.addEventListener('input', () => {
    player.ui.volume.value = volume.value;
    player.changeVolume();
  });
  utility.append(node('span', '', 'Browser volume'), volume);

  const scanPanel = node('section', 'scanner-scan-lists');
  const scanHeading = node('div', 'scanner-scan-heading');
  const scanCopy = node('div');
  scanCopy.append(node('strong', '', 'Scan Lists'), node('span', 'scanner-scan-summary', 'Loading'));
  const coverage = node('button', 'secondary', 'View coverage tree');
  coverage.type = 'button';
  coverage.addEventListener('click', () => openPlaybackScanListCoverage(player));
  scanHeading.append(scanCopy, coverage);
  const scanButtons = node('div', 'scanner-scan-buttons');
  scanPanel.append(scanHeading, scanButtons);

  chassis.append(statusBar, displayShell, replayBanner, controls, utility);
  page.append(chassis, scanPanel);
  const heading = pageHeader('Scanner', 'Listen to completed calls from this receiver');
  heading.append(modeBar);
  const host = node('div', 'scanner-player-host');
  host.append(page);
  if (!beginPage(renderContext, heading, host)) return;
  placePlaybackBar();

  let latestState = player.viewState();
  let currentSiteGuid = '';
  let currentSite = null;
  const updateAge = () => {
    age.textContent = latestState.current ? scannerRelativeAge(latestState.current.completed_at_ms) :
      'Waiting for a call';
  };
  const draw = (state) => {
    latestState = state;
    playbackStatus.textContent = state.recentReplay ? 'Replaying recent call' : state.status ||
      (state.paused ? 'Ready' : 'Listening');
    playbackStatus.classList.toggle('active', !state.paused || state.recentReplay);
    replayBanner.hidden = !state.recentReplay;
    if (state.recentReplay && state.current) replayCopy.textContent =
      `Replaying ${scannerTargetLabel(state.current)}. Live playback is paused at its saved position.`;
    play.textContent = state.paused ? 'Play' : 'Pause';
    play.classList.toggle('active', !state.paused);
    replay.disabled = !state.currentReady;
    skip.disabled = !state.current && !state.queuedCount;
    hold.disabled = state.recentReplay || (!state.holdTarget && !state.currentReady);
    hold.classList.toggle('active', Boolean(state.holdTarget));
    avoid.disabled = !state.currentReady || state.recentReplay;
    avoidList.textContent = `Avoid List${state.avoids.length ? ` (${state.avoids.length})` : ''}`;
    recent.textContent = `Recent Calls${state.recentCalls.length ? ` (${state.recentCalls.length})` : ''}`;
    clearQueue.textContent = `Clear Queue${state.queuedCount ? ` (${state.queuedCount})` : ''}`;
    clearQueue.disabled = !state.queuedCount;
    volume.value = String(state.volume);
    updateAge();
    renderScannerCall(display, state, currentSite);

    scanButtons.replaceChildren();
    const selectedCount = state.scanLists.filter((item) => item.selected).length;
    scanCopy.querySelector('.scanner-scan-summary').textContent = state.scanListCatalogReady ?
      `${selectedCount} of ${state.scanLists.length} listening` : 'Loading available lists';
    state.scanLists.forEach((item, index) => {
      const button = node('button', `scanner-scan-button${item.selected ? ' active' : ''}`);
      button.type = 'button';
      button.disabled = !item.enabled;
      button.setAttribute('aria-pressed', String(item.selected));
      button.append(node('span', 'scanner-scan-number', String(index + 1)), node('strong', '', item.name),
        node('small', '', item.description || (item.defaultSelected ? 'Default list' : 'Available')));
      button.addEventListener('click', () => player.setScanListSelected(item.id, !item.selected));
      scanButtons.append(button);
    });

    const nextGuid = String(state.current?.site_guid || '');
    if (nextGuid !== currentSiteGuid) {
      currentSiteGuid = nextGuid;
      currentSite = null;
      if (nextGuid) void scannerSiteMetadata(nextGuid).then((site) => {
        if (currentSiteGuid === nextGuid && display.isConnected) {
          currentSite = site;
          renderScannerCall(display, latestState, currentSite);
        }
      });
    }
  };
  const unsubscribe = player.subscribeState(draw);
  renderContext.signal?.addEventListener('abort', unsubscribe, { once: true });
  const waveformLevels = new Float32Array(24);
  let waveformFrame = null;
  const drawWaveform = () => {
    if (!renderIsCurrent(renderContext)) return;
    const wave = display.querySelector('.scanner-audio-wave');
    if (wave) {
      const playing = player.readAudioWaveform(waveformLevels);
      wave.classList.toggle('paused', !playing);
      wave.setAttribute('aria-label', playing ? 'Audio playing' : 'Audio paused');
      [...wave.children].forEach((bar, index) => {
        bar.style.height = `${Math.round(3 + waveformLevels[index] * 31)}px`;
      });
    }
    waveformFrame = window.requestAnimationFrame(drawWaveform);
  };
  waveformFrame = window.requestAnimationFrame(drawWaveform);
  renderContext.signal?.addEventListener('abort', () => {
    if (waveformFrame !== null) window.cancelAnimationFrame(waveformFrame);
  }, { once: true });
  pageInterval(updateAge, 1_000);
  modeBar.querySelectorAll('button').forEach((button) => button.addEventListener('click', () => {
    scannerDetailMode = button.dataset.mode;
    modeBar.querySelectorAll('button').forEach((item) => item.classList.toggle('active', item === button));
    renderScannerCall(display, latestState, currentSite);
  }));
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

function affiliationRouteFilters() {
  const siteGuid = normalizedSiteText(route.get('site_guid'));
  return {
    affiliated: route.get('affiliated') === 'true' ? true : null,
    site_guid: siteGuid || null
  };
}

function affiliationFilterActions(exportAction = null) {
  const filters = affiliationRouteFilters();
  const actions = node('div', 'section-title-actions');
  if (filters.affiliated || filters.site_guid) {
    actions.append(anchor('Clear Filter', currentHref({ affiliated: null, site_guid: null, offset: null }),
      'button secondary'));
  }
  if (exportAction) actions.append(exportAction);
  return actions.childNodes.length ? actions : null;
}

function requiredSystemScope() {
  const scopeToken = String(route.get('scope') || '').trim();
  if (!scopeToken) throw new Error('System scope is missing from the URL');
  return { scope: scopeToken };
}

function requiredId() {
  const id = Number(route.get('id'));
  if (!Number.isInteger(id) || id < 0) throw new Error('Identifier is missing from the URL');
  return id;
}

function systemCapability(system, capability) {
  const capabilities = system?.capabilities;
  if (!capabilities || typeof capabilities !== 'object') return false;
  return Boolean(capabilities[capability]);
}

function systemTabItems(system) {
  const values = scope(system);
  const items = [{ id: 'info', label: 'Info', href: href('system', { ...values, tab: 'info' }) }];
  if (systemCapability(system, 'group_identities')) {
    items.push({ id: 'talkgroups', label: 'Talkgroups', href: href('system', { ...values, tab: 'talkgroups' }) });
  }
  if (systemCapability(system, 'radios')) {
    items.push({ id: 'radios', label: 'Radios', href: href('system', { ...values, tab: 'radios' }) });
  }
  if (systemCapability(system, 'activity')) {
    items.push({ id: 'activity', label: 'Activity', href: href('system', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' });
  }
  if (systemCapability(system, 'talker_aliases')) {
    items.push({ id: 'talker-aliases', label: 'Talker Aliases',
      href: href('system', { ...values, tab: 'talker-aliases' }) });
  }
  return items;
}

function systemTabs(system, active) {
  return tabs(systemTabItems(system), active);
}

function entityTabs(view, system, id, active, radio, kind = null) {
  const values = { ...scope(system), id, kind: kind === 'patch_group' ? 'patch_group' : null };
  const activity = { id: 'activity', label: 'Activity', href: href(view, { ...values, tab: 'activity' }),
    disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' };
  const items = [{ id: 'info', label: 'Info', href: href(view, { ...values, tab: 'info' }) }];
  if (radio && systemCapability(system, 'group_identities')) {
    items.push({ id: 'talkgroups', label: 'Talkgroups', href: href(view, { ...values, tab: 'talkgroups' }) });
  } else if (!radio && systemCapability(system, 'radios')) {
    items.push({ id: 'radios', label: 'Radios', href: href(view, { ...values, tab: 'radios' }) });
  }
  if (systemCapability(system, 'activity')) items.push(activity);
  return tabs(items, active);
}

function siteCapability(site, capability) {
  return Boolean(site?.capabilities?.[capability]);
}

function siteTabItems(site) {
  const values = { guid: site.guid };
  const items = [
    { id: 'info', label: 'Info', href: href('site', { ...values, tab: 'info' }) }
  ];
  if (siteCapability(site, 'channels')) {
    items.push({ id: 'channels', label: 'Channels', href: href('site', { ...values, tab: 'channels' }) });
  }
  if (siteCapability(site, 'quality')) {
    items.push({ id: 'quality', label: 'Quality', href: href('site', { ...values, tab: 'quality' }) });
  }
  if (siteCapability(site, 'neighbors')) {
    items.push({ id: 'neighbors', label: 'Neighbors', href: href('site', { ...values, tab: 'neighbors' }) });
  }
  if (siteCapability(site, 'frequency_bands')) {
    items.push({ id: 'band-plan', label: 'Band Plan', href: href('site', { ...values, tab: 'band-plan' }) });
  }
  if (siteCapability(site, 'patch_groups')) {
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
  const roles = new Set(Array.isArray(value) ? value : []);
  const values = [];
  if (roles.has('current_control')) values.push(badge('Current CC', 'state-current'));
  if (roles.has('alternate_control')) values.push(badge('Alt CC', 'state-current'));
  if (roles.has('traffic')) values.push(badge('Traffic'));
  return badgeGroup(values);
}

function trunkedChannelSources(value) {
  const sources = new Set(Array.isArray(value) ? value : []);
  const values = [];
  if (sources.has('observed')) values.push(badge('OTA Seen', '',
    'This channel and timeslot were decoded over the air'));
  if (sources.has('configured_map_frequency')) values.push(badge('LCN Map', '',
    'Frequency resolved from the configured LCN-to-frequency map'));
  if (sources.has('over_air_frequency')) values.push(badge('OTA Freq', '',
    'Absolute frequency was broadcast over the air'));
  return badgeGroup(values);
}

function trunkedNeighborStatus(value) {
  const statuses = new Set(Array.isArray(value) ? value : []);
  const values = [];
  if (statuses.has('linked')) values.push(badge('Linked', 'state-current'));
  if (statuses.has('isolated')) values.push(badge('Isolated', 'state-stale'));
  return badgeGroup(values);
}

const siteColumns = [
  { id: 'system', label: 'Sys', fullLabel: 'System', render: systemLink, sort: 'system', sortValue: systemLabel },
  { id: 'rfss', label: 'RFSS', key: 'rfss', render: (row) => hex(row.rfss, 2), className: 'numeric', sort: 'rfss' },
  { id: 'site', label: 'Site', key: 'site', render: (row) => hex(row.site, 2), className: 'numeric', sort: 'site' },
  { id: 'name', label: 'Name / Site', render: siteNameSummary, className: 'alias-cell', sort: 'name', sortValue: siteLabel },
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
  return identityKind(row.identity_kind) === 'unknown' || Number(row.identity_id) <= 0 ? '—' :
    identityNumber(row, row.identity_id);
}

function dashboardIdentityLink(row, label = dashboardIdentityId(row)) {
  if (!Number(row.identity_detail_available)) return label;
  if (row.identity_detail_view === 'talkgroup') {
    return talkgroupLink(row, row.identity_id, label);
  }
  if (row.identity_detail_view === 'radio') {
    return radioLink(row, row.identity_id, label);
  }
  if (row.identity_detail_view === 'conventional-talkgroups' && row.context_key &&
      capabilityAllowed(ACCESS_CAPABILITIES.CONVENTIONAL)) {
    return anchor(label, href('conventional-detail', { context: row.context_key, tab: 'talkgroups' }));
  }
  if (row.identity_detail_view === 'conventional-radios' && row.context_key &&
      capabilityAllowed(ACCESS_CAPABILITIES.CONVENTIONAL)) {
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
    talkgroup: 'TG',
    radio: 'Radio',
    patch_group: 'Patch',
    unknown: 'Channel'
  })[identityKind(kind)] || kind;
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

function dashboardActivityActionConfiguration(action, index = 0) {
  const normalized = String(action || '').trim().toUpperCase();
  const configured = DASHBOARD_ACTIVITY_SERIES.find((series) => series.action === normalized);
  return configured || {
    action: normalized,
    label: semanticLabel(normalized) || 'Unknown',
    color: `hsl(${Math.round(index * 137.508) % 360} 58% var(--chart-dynamic-lightness))`
  };
}

function dashboardActivityActionRows(response) {
  return (response?.rows || []).map((row, index) => {
    const configuration = dashboardActivityActionConfiguration(row.action, index);
    return {
      ...row,
      ...configuration,
      count: Math.max(0, Number(row.count || 0))
    };
  }).filter((row) => row.action && row.action !== 'CONTINUE' && row.count > 0)
    .sort((left, right) => right.count - left.count || left.label.localeCompare(right.label));
}

function dashboardActivityMix(response, selectedAction, onSelect) {
  const actions = dashboardActivityActionRows(response);
  if (!actions.length) return node('div', 'empty', 'No activity was recorded for this range.');
  const summedTotal = actions.reduce((sum, row) => sum + row.count, 0);
  const total = summedTotal;
  const circumference = 2 * Math.PI * 78;
  const wrapper = node('div', 'dashboard-activity-layout');
  const chart = node('div', 'dashboard-activity-chart');
  const svg = svgNode('svg', {
    class: 'dashboard-activity-donut', viewBox: '0 0 220 220', role: 'group',
    'aria-label': `Activity mix, ${number(total)} events`
  });
  svg.append(svgNode('circle', {
    class: 'dashboard-activity-donut-background', cx: 110, cy: 110, r: 78
  }));
  const centerCount = svgNode('text', {
    class: 'dashboard-activity-center-count', x: 110, y: 106, 'text-anchor': 'middle'
  });
  const centerLabel = svgNode('text', {
    class: 'dashboard-activity-center-label', x: 110, y: 127, 'text-anchor': 'middle'
  });
  const legend = node('div', 'activity-series-legend dashboard-activity-legend');
  legend.setAttribute('role', 'group');
  legend.setAttribute('aria-label', 'Activity types');
  let offset = 0;

  const updateSelection = (action, notify = true) => {
    const selected = actions.find((row) => row.action === action) || null;
    wrapper.querySelectorAll('[data-action]').forEach((element) => {
      const active = element.dataset.action === selected?.action;
      element.classList.toggle('active', active);
      element.setAttribute('aria-pressed', String(active));
    });
    centerCount.textContent = number(selected?.count ?? total);
    centerLabel.textContent = selected?.label || 'Visible events';
    if (notify && selected) onSelect(selected);
  };

  actions.forEach((row) => {
    const percentage = total ? row.count / total * 100 : 0;
    const length = circumference * percentage / 100;
    const segment = svgNode('circle', {
      class: 'dashboard-activity-segment', cx: 110, cy: 110, r: 78,
      'stroke-dasharray': `${length} ${Math.max(0, circumference - length)}`,
      'stroke-dashoffset': -offset, transform: 'rotate(-90 110 110)',
      role: 'button', tabindex: 0, focusable: 'true',
      'aria-label': `${row.label}: ${number(row.count)} events, ${percentage.toFixed(1)} percent`
    });
    segment.dataset.action = row.action;
    segment.style.stroke = row.color;
    segment.append(svgNode('title', {},
      `${row.label}: ${number(row.count)} events (${percentage.toFixed(1)}%)`));
    segment.addEventListener('click', () => updateSelection(row.action));
    segment.addEventListener('keydown', (event) => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      updateSelection(row.action);
    });
    svg.append(segment);
    offset += length;

    const button = node('button', 'activity-series-button secondary dashboard-activity-legend-button');
    button.type = 'button';
    button.dataset.action = row.action;
    const swatch = node('span', 'activity-series-swatch');
    swatch.style.backgroundColor = row.color;
    button.append(swatch, node('span', '', row.label),
      node('span', 'activity-series-total', `${number(row.count)} · ${percentage.toFixed(1)}%`));
    button.addEventListener('click', () => updateSelection(row.action));
    legend.append(button);
  });

  svg.append(centerCount, centerLabel);
  chart.append(svg);
  wrapper.append(chart, legend);
  updateSelection(actions.some((row) => row.action === selectedAction) ? selectedAction : '', false);
  return wrapper;
}

function dashboardActivitySystem(row) {
  const scopedSystem = row.scope_token ? systemLabel(row) : '';
  const label = row.system_name || row.resolved_system_name || row.configured_system ||
    row.configured_name || scopedSystem || row.resolved_channel_name || row.channel_name ||
    row.context_key || row.scope_label || row.scope_token || '—';
  const discriminator = String(row.scope_token || row.context_key || row.guid || '').trim();
  const primary = row.scope_token ? systemLink(row, label) : node('span', '', label);
  if (!discriminator || discriminator === label) return primary;
  const summary = node('span', 'dashboard-identity');
  const primaryLine = node('span', 'dashboard-identity-primary');
  primaryLine.append(primary);
  const context = node('small', 'dashboard-identity-context', discriminator);
  context.title = discriminator;
  summary.append(primaryLine, context);
  return summary;
}

function dashboardActivityRadio(row) {
  const identifier = identityNumber(row, row.radio_id) || '—';
  return row.scope_token ? radioLink(row, row.radio_id, identifier) : identifier;
}

function dashboardActivityAlias(row) {
  const alias = String(row.alias_name || '').trim();
  const description = String(row.alias_description || '').trim();
  if (!alias && !description) return '—';
  const summary = node('span', 'dashboard-identity');
  const primary = node('span', 'dashboard-identity-primary');
  primary.textContent = alias || description;
  summary.append(primary);
  if (alias && description) summary.append(node('small', 'dashboard-identity-context', description));
  return summary;
}

const dashboardActivityRadioColumns = [
  { id: 'system', label: 'System / Channel', render: dashboardActivitySystem, className: 'alias-cell' },
  { id: 'radio', label: 'Radio', render: dashboardActivityRadio, className: 'numeric' },
  { id: 'alias', label: 'Alias', render: dashboardActivityAlias, className: 'alias-cell' },
  { id: 'events', label: 'Events', render: (row) => number(row.event_count), className: 'numeric' },
  { id: 'last-seen', label: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
];

function dashboardActivityRangeLabel(range) {
  return DASHBOARD_ACTIVITY_RANGES.find(([value]) => value === range)?.[1] || range;
}

function dashboardActivityRadioPager(page, onOffset) {
  const navigation = node('nav', 'pager dashboard-activity-radio-pager');
  navigation.setAttribute('aria-label', 'Source radio pagination');
  navigation.tabIndex = -1;
  const first = page.offset + (page.rows.length ? 1 : 0);
  const last = page.offset + page.rows.length;
  navigation.append(node('span', 'muted', page.rows.length ?
    `Source radios ${number(first)}-${number(last)} of ${number(page.total_count)}` :
    `Source radios 0 of ${number(page.total_count)}`));
  const previous = node('button', 'secondary', 'Previous');
  previous.type = 'button';
  previous.disabled = page.offset <= 0;
  previous.addEventListener('click', () => onOffset(Math.max(0, page.offset - page.limit)));
  const next = node('button', 'secondary', 'Next');
  next.type = 'button';
  next.disabled = !page.has_more;
  next.addEventListener('click', () => onOffset(page.next_offset));
  navigation.append(previous, next);
  return navigation;
}

function dashboardActivityRadioNote(page, actionLabel) {
  return node('p', 'metric-meaning-note',
    `Hourly ${actionLabel.toLowerCase()} total: ${number(page.action_total)}. ` +
    `Exact currently retained detail: ${number(page.retained_event_count)} events; ` +
    `${number(page.identified_event_count)} identify a source radio and ` +
    `${number(page.unknown_source_event_count)} have no source radio ID.`);
}

async function renderDashboardActivity(renderContext) {
  let selectedRange = '24h';
  let selectedAction = '';
  let selectedActionLabel = '';
  let selectedOffset = 0;
  let summarySequence = 0;
  let radioSequence = 0;
  let summaryRequest = null;
  let radioRequest = null;
  let radioHost = null;
  let radioStatus = null;
  let radioTitle = null;
  const toolbar = node('div', 'dashboard-activity-page-controls');
  toolbar.setAttribute('aria-label', 'Activity analytics controls');
  toolbar.append(node('span', 'dashboard-control-label', 'Time range'));
  const host = node('div', 'dashboard-activity-view');
  const rangeControl = rangeControls(DASHBOARD_ACTIVITY_RANGES, selectedRange, async (value, buttons) => {
    selectedRange = value;
    selectedAction = '';
    selectedActionLabel = '';
    selectedOffset = 0;
    await loadSummary(buttons);
  });
  toolbar.append(rangeControl.controls);
  content.append(toolbar, host);

  const nextRequest = (previous) => {
    previous?.controller.abort();
    previous?.unlink();
    const controller = new AbortController();
    let linked = false;
    const abortFromPage = () => controller.abort(renderContext.signal?.reason);
    if (renderContext.signal?.aborted) controller.abort(renderContext.signal.reason);
    else if (renderContext.signal) {
      renderContext.signal.addEventListener('abort', abortFromPage, { once: true });
      linked = true;
    }
    return {
      controller,
      unlink: () => {
        if (!linked) return;
        renderContext.signal.removeEventListener('abort', abortFromPage);
        linked = false;
      }
    };
  };

  const showRadioPrompt = () => {
    if (!radioHost || !radioStatus || !radioTitle) return;
    radioTitle.textContent = 'Source radios';
    radioStatus.textContent = 'Select an activity type to list source radios.';
    radioHost.setAttribute('aria-busy', 'false');
    radioHost.replaceChildren(node('div', 'empty', 'Select an activity type to list source radios.'));
  };

  const loadRadios = async (offset = 0, restorePagingFocus = false) => {
    if (!selectedAction || !radioHost || !radioStatus || !radioTitle) return;
    selectedOffset = Math.max(0, Number(offset) || 0);
    const sequence = ++radioSequence;
    const action = selectedAction;
    const actionLabel = selectedActionLabel || semanticLabel(action);
    radioTitle.textContent = `${actionLabel} · Source radios`;
    if (!capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)) {
      radioRequest?.controller.abort();
      radioRequest?.unlink();
      radioRequest = null;
      radioStatus.textContent = 'Systems & Sites access is required to list source radios.';
      radioHost.setAttribute('aria-busy', 'false');
      radioHost.replaceChildren(node('div', 'empty',
        'Systems & Sites access is required to list source radios.'));
      return;
    }
    const request = nextRequest(radioRequest);
    radioRequest = request;
    radioStatus.textContent = `Loading ${actionLabel.toLowerCase()} source radios.`;
    radioHost.setAttribute('aria-busy', 'true');
    radioHost.replaceChildren(node('div', 'loading', 'Loading source radios'));
    try {
      const page = await apiPage('/api/v1/activity/radios', {
        range: selectedRange, action, limit: 100, offset: selectedOffset
      }, { signal: request.controller.signal });
      if (sequence !== radioSequence || action !== selectedAction ||
          !renderIsCurrent(renderContext) || !host.isConnected) return;
      selectedOffset = page.offset;
      radioStatus.textContent = `${number(page.total_count)} source radio` +
        `${Number(page.total_count) === 1 ? '' : 's'} found for ${actionLabel}.`;
      const result = node('div', 'dashboard-activity-radio-result');
      const pager = dashboardActivityRadioPager(page,
        (nextOffset) => void loadRadios(nextOffset, true));
      result.append(dashboardActivityRadioNote(page, actionLabel),
        table(page.rows, dashboardActivityRadioColumns,
          `No source radios were identified in currently retained ${actionLabel.toLowerCase()} detail.`,
          { type: 'dashboard-activity-radios', sortable: false }),
        pager);
      radioHost.replaceChildren(result);
      if (restorePagingFocus) pager.focus();
    } catch (error) {
      if (error?.name === 'AbortError' || sequence !== radioSequence ||
          !renderIsCurrent(renderContext) || !host.isConnected) return;
      if (error?.status === 401 || error?.status === 403) {
        await refreshAccessSession(false);
        if (sequence === radioSequence && renderIsCurrent(renderContext) && host.isConnected) await render();
        return;
      }
      radioStatus.textContent = `${actionLabel} source radios could not be loaded.`;
      const failure = asyncSectionFailure(error, 'Source radios could not be loaded.',
        () => loadRadios(selectedOffset, restorePagingFocus));
      radioHost.replaceChildren(failure);
      if (restorePagingFocus) failure.querySelector('.async-section-retry')?.focus();
    } finally {
      request.unlink();
      if (radioRequest === request) radioRequest = null;
      if (sequence === radioSequence && renderIsCurrent(renderContext) && host.isConnected) {
        radioHost.setAttribute('aria-busy', 'false');
      }
    }
  };

  const renderSummary = (response) => {
    const mixBody = node('div', 'dashboard-activity-mix-body');
    mixBody.append(dashboardActivityMix(response, selectedAction, (row) => {
      selectedAction = row.action;
      selectedActionLabel = row.label;
      selectedOffset = 0;
      void loadRadios(0);
    }), node('p', 'metric-meaning-note',
      'Percentages use the visible activity total. Repeated signaling can produce more than one event for the same ' +
      'call or radio.'));
    const mix = section(`Activity Mix · ${dashboardActivityRangeLabel(selectedRange)}`, mixBody);
    const radioBody = node('div', 'dashboard-activity-radio-body');
    radioStatus = node('p', 'dashboard-activity-radio-status');
    radioStatus.setAttribute('aria-live', 'polite');
    radioHost = node('div', 'dashboard-activity-radio-host');
    radioBody.append(radioStatus, radioHost);
    const radios = section('Source radios', radioBody);
    radioTitle = radios.querySelector('.section-title');
    host.replaceChildren(mix, radios);
    showRadioPrompt();
  };

  const loadSummary = async (buttons = rangeControl.buttons) => {
    const sequence = ++summarySequence;
    radioSequence += 1;
    radioRequest?.controller.abort();
    radioRequest?.unlink();
    radioRequest = null;
    const request = nextRequest(summaryRequest);
    summaryRequest = request;
    buttons.forEach((button) => { button.disabled = true; });
    host.setAttribute('aria-busy', 'true');
    host.replaceChildren(node('div', 'loading', 'Loading activity analytics'));
    try {
      const response = await api('/api/v1/activity/actions', { range: selectedRange },
        { signal: request.controller.signal });
      if (sequence !== summarySequence || !renderIsCurrent(renderContext) || !host.isConnected) return;
      renderSummary(response);
    } catch (error) {
      if (error?.name === 'AbortError' || sequence !== summarySequence ||
          !renderIsCurrent(renderContext) || !host.isConnected) return;
      if (error?.status === 401 || error?.status === 403) {
        await refreshAccessSession(false);
        if (sequence === summarySequence && renderIsCurrent(renderContext) && host.isConnected) await render();
        return;
      }
      host.replaceChildren(asyncSectionFailure(error, 'Activity totals could not be loaded.',
        () => loadSummary(buttons)));
    } finally {
      request.unlink();
      if (summaryRequest === request) summaryRequest = null;
      if (sequence === summarySequence && renderIsCurrent(renderContext) && host.isConnected) {
        host.setAttribute('aria-busy', 'false');
        buttons.forEach((button) => { button.disabled = false; });
      }
    }
  };

  await loadSummary();
}

const talkgroupColumns = [
  { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sort: 'talkgroup', sortValue: (row) => Number(row.talkgroup_id) },
  { id: 'talkgroup-kind', label: 'Kind', render: (row) => groupIdentityLabel(row) },
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

function systemRadioColumns(system) {
  const columns = [
    { id: 'radio', label: 'ID', render: (row) => radioLink(row), className: 'numeric', sort: 'id', sortValue: (row) => Number(row.radio_id) },
    { id: 'alias', label: 'Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel }
  ];
  if (systemCapability(system, 'talker_aliases')) {
    columns.push({ label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias',
      className: 'alias-cell', sort: 'talker_alias' });
  }
  if (systemCapability(system, 'current_affiliations')) {
    columns.push({ id: 'affiliation', label: 'Affiliation', fullLabel: 'Current Talkgroup Affiliation',
      render: affiliationTalkgroupCell, className: 'alias-cell', sort: 'affiliated_talkgroup',
      sortValue: affiliationTalkgroupSortValue });
  }
  if (systemCapability(system, 'radio_site_presence')) {
    columns.push({ id: 'affiliated-site', label: 'Last Confirmed Site', render: sitePresenceCell,
      className: 'alias-cell', sort: 'site', sortValue: presenceSiteSortValue });
  }
  columns.push(
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric',
      sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count),
      className: 'numeric encrypted', sort: 'encrypted',
      sortValue: (row) => Number(row.encrypted_count || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen',
      sortValue: (row) => Number(row.last_seen_ms || 0) }
  );
  return columns;
}

async function renderDashboard() {
  const renderContext = captureRenderContext();
  const dashboard = await api('/api/v1/dashboard');
  const counts = dashboard.counts || {};
  const callActivity = dashboard.call_activity || {};
  const callTotals = callActivity.totals || {};
  const requestedTab = route.get('tab') || 'health';
  const tab = ['health', 'calls', 'activity'].includes(requestedTab) ? requestedTab : 'health';
  if (!beginPage(renderContext,
    pageHeader('Dashboard', dashboard.last_seen_ms ?
      fragment('Last activity ', dateTime(dashboard.last_seen_ms)) : 'Last activity not recorded'),
    tabs([
      { id: 'health', label: 'Health', href: href('dashboard', { tab: 'health' }) },
      { id: 'calls', label: 'Calls', href: href('dashboard', { tab: 'calls' }) },
      { id: 'activity', label: 'Activity', href: href('dashboard', { tab: 'activity' }) }
    ], tab))) return;

  if (tab === 'health') {
    const signalHealth = await signalHealthSection();
    if (!renderIsCurrent(renderContext)) return;
    content.append(signalHealth);
    content.append(dashboardSummarySection('Monitored Coverage', [
      ['Trunked Systems', counts.trunked_systems],
      ['Trunked Sites', counts.trunked_sites],
      ['Conventional Channels', counts.conventional_channels]
    ]));
    content.append(section('Recent Sites / Channels', table(dashboard.recent_receivers || [],
      dashboardHealthColumns, 'No sites or channels recorded', { type: 'dashboard-receivers' })));
    return;
  }

  if (tab === 'activity') {
    await renderDashboardActivity(renderContext);
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
  const sourceRows = Array.isArray(dashboard.source_activity_24h) ? dashboard.source_activity_24h :
    dashboard.source_activity_24h?.rows || [];
  content.append(section('Calls by Site / Channel · Last 24 Hours',
    table(sourceRows, dashboardCallSourceColumns, 'No call activity recorded',
      { type: 'dashboard-call-sources' })));
  const destinations = section('Top Destinations · Last 24 Hours',
    table(dashboard.top_destinations || [], dashboardIdentityColumns('Destination'),
      'No call destinations recorded', { type: 'dashboard-destinations' }));
  const sources = section('Top Sources · Last 24 Hours',
    table(dashboard.top_sources || [], dashboardIdentityColumns('Source'),
      'No call sources recorded', { type: 'dashboard-sources' }));
  content.append(node('div', 'split dashboard-identity-split'));
  content.lastChild.append(destinations, sources);
}

const LIVE_DETAIL_SELECTION_KINDS = Object.freeze({ CONTROL: 'CONTROL', EXACT: 'EXACT' });
const LIVE_DETAIL_CONTROL_ROLES = new Set(['CONFIGURED_CONTROL', 'CURRENT_CONTROL', 'ALTERNATE_CONTROL']);

function liveDetailSelection(tableValue, row, bindingRow = row) {
  const configurationId = row?.configuration_id || tableValue?.configuration_id;
  if (!configurationId) return null;
  const role = String(row?.role || (tableValue?.table_id === 'conventional' ? 'CONVENTIONAL' : '')).toUpperCase();
  const kind = tableValue?.table_id !== 'conventional' && LIVE_DETAIL_CONTROL_ROLES.has(role) ?
    LIVE_DETAIL_SELECTION_KINDS.CONTROL : LIVE_DETAIL_SELECTION_KINDS.EXACT;
  const resolvedRow = kind === LIVE_DETAIL_SELECTION_KINDS.CONTROL ? bindingRow : row;
  const bindingFrequencyHz = Number(resolvedRow?.frequency_hz) || null;
  const bindingTimeslot = Number(resolvedRow?.timeslot) || null;
  const rowLabelBase = resolvedRow?.channel_name || resolvedRow?.lcn ||
    (bindingFrequencyHz ? `${frequency(bindingFrequencyHz)} MHz` : '');
  const rowLabel = bindingTimeslot && !/\bTS\s*:?\s*\d+\b/i.test(rowLabelBase) ?
    `${rowLabelBase} · TS ${bindingTimeslot}` : rowLabelBase;
  const tableLabel = tableValue.title || tableValue.channel_name || tableValue.table_id;
  return {
    kind,
    role,
    logicalKey: kind === LIVE_DETAIL_SELECTION_KINDS.CONTROL ? `CONTROL:${configurationId}` :
      `EXACT:${configurationId}:${bindingFrequencyHz || ''}:${bindingTimeslot || ''}`,
    transportKey: `${configurationId}:${bindingFrequencyHz || ''}:${bindingTimeslot || ''}`,
    rowKey: resolvedRow?.key || null,
    configurationId,
    bindingFrequencyHz,
    bindingTimeslot,
    label: [tableLabel, kind === LIVE_DETAIL_SELECTION_KINDS.CONTROL ? '' : rowLabel].filter(Boolean).join(' · '),
    channelLabel: [tableLabel, rowLabel].filter(Boolean).join(' · ')
  };
}

function liveCurrentControlRow(tableValue) {
  return (tableValue?.rows || []).find((row) =>
    String(row?.role || '').toUpperCase() === 'CURRENT_CONTROL') || null;
}

function liveDetailRowSelection(tableValue, row) {
  const role = String(row?.role || '').toUpperCase();
  const controlIntent = tableValue?.table_id !== 'conventional' && LIVE_DETAIL_CONTROL_ROLES.has(role);
  const controlBinding = controlIntent && tableValue?.control_active === true ?
    liveCurrentControlRow(tableValue) : null;
  return liveDetailSelection(tableValue, row, controlIntent ? controlBinding : row);
}

function liveDetailSelectionDelta(previous, next) {
  return {
    logicalChanged: next?.logicalKey !== previous?.logicalKey,
    transportChanged: next?.transportKey !== previous?.transportKey
  };
}

function liveMessageTransportChanged(previous, next) {
  return String(previous?.configurationId || '') !== String(next?.configurationId || '') ||
    Number(previous?.bindingFrequencyHz || 0) !== Number(next?.bindingFrequencyHz || 0);
}

function liveMessageSourceMatchesSelection(selection, subscriptionId, source) {
  return String(source?.configuration_id || '') === String(selection?.configurationId || '') &&
    String(source?.subscription_id || '') === String(subscriptionId || '') &&
    Number(source?.frequency_hz || 0) === Number(selection?.bindingFrequencyHz || 0);
}

function liveDetailTransportParameters(selection, includeTimeslot = false) {
  if (!selection?.configurationId || !selection?.bindingFrequencyHz) return null;
  const parameters = {
    configuration_id: selection.configurationId,
    frequency_hz: selection.bindingFrequencyHz
  };
  if (includeTimeslot && selection.bindingTimeslot) parameters.timeslot = selection.bindingTimeslot;
  return parameters;
}

function liveEventMatchesSelection(selection, event) {
  if (String(event?.configuration_id || '') !== String(selection?.configurationId || '')) return false;
  if (selection?.kind !== LIVE_DETAIL_SELECTION_KINDS.EXACT) return true;
  if (Number(event?.frequency_hz || 0) !== Number(selection?.bindingFrequencyHz || 0)) return false;
  return selection.bindingTimeslot == null ||
    Number(event?.timeslot || 0) === Number(selection.bindingTimeslot);
}

function liveEventScopeMatchesSelection(selection, subscriptionId, source) {
  if (String(source?.configuration_id || '') !== String(selection?.configurationId || '')) return false;
  if (String(source?.subscription_id || '') !== String(subscriptionId || '')) return false;
  const exact = selection?.kind === LIVE_DETAIL_SELECTION_KINDS.EXACT;
  const expectedFrequencyHz = exact ? Number(selection?.bindingFrequencyHz) || null : null;
  const expectedTimeslot = exact ? Number(selection?.bindingTimeslot) || null : null;
  const sourceFrequencyHz = Number(source?.frequency_hz) || null;
  const sourceTimeslot = Number(source?.timeslot) || null;
  return sourceFrequencyHz === expectedFrequencyHz && sourceTimeslot === expectedTimeslot;
}

function liveChannelStateMatchesSelection(selection, subscriptionId, source) {
  return String(source?.configuration_id || '') === String(selection?.configurationId || '') &&
    String(source?.subscription_id || '') === String(subscriptionId || '') &&
    Number(source?.frequency_hz || 0) === Number(selection?.bindingFrequencyHz || 0) &&
    Number(source?.timeslot || 0) === Number(selection?.bindingTimeslot || 0);
}

function liveEventDuration(value) {
  const milliseconds = Math.max(0, Number(value) || 0);
  if (milliseconds < 1000) return `${milliseconds} ms`;
  return `${(milliseconds / 1000).toFixed(milliseconds < 10000 ? 1 : 0)} s`;
}

function liveEventCategoryClass(value) {
  const category = String(value || 'OTHER').toUpperCase();
  return LIVE_EVENT_CATEGORY_CLASSES[category] || LIVE_EVENT_CATEGORY_CLASSES.OTHER;
}

function liveEventParty(event, side) {
  const aliases = event?.[`${side}_aliases`] || '';
  const identifiers = event?.[`${side}_identifiers`] || '';
  const value = node('td', 'live-event-stack');
  if (aliases) value.append(node('strong', '', aliases));
  if (identifiers) value.append(node(aliases ? 'small' : 'span', '', identifiers));
  return value;
}

function liveDetailMatchingRowLimit() {
  const configured = Math.trunc(Number(liveDisplaySettings?.live_detail_matching_row_limit));
  return Number.isFinite(configured) ? Math.max(25, Math.min(500, configured)) :
    LIVE_DETAIL_DEFAULT_MATCHING_ROW_LIMIT;
}

function liveDetailCaptureLimit() {
  return Math.max(LIVE_DETAIL_MINIMUM_CAPTURE, Math.min(LIVE_DETAIL_MAXIMUM_CAPTURE,
    liveDetailMatchingRowLimit() * LIVE_DETAIL_CAPTURE_MULTIPLIER));
}

function liveDetailText(value) {
  return String(value ?? '').trim().toLowerCase();
}

function liveDetailFilterCatalog(value) {
  if (!value || typeof value !== 'object' || !Array.isArray(value.groups) || !Array.isArray(value.timeslots)) return null;
  const keys = new Set();
  const leafKeys = [];
  let invalid = false;
  const normalizeNode = (candidate) => {
    const key = String(candidate?.key || '').trim();
    const label = String(candidate?.label || '').trim();
    if (!candidate || typeof candidate !== 'object' || !key || !label || keys.has(key) ||
        !Array.isArray(candidate.children)) {
      invalid = true;
      return null;
    }
    keys.add(key);
    const children = candidate.children.map(normalizeNode).filter(Boolean);
    if (!children.length) leafKeys.push(key);
    return { key, label, children };
  };
  const groups = value.groups.map(normalizeNode).filter(Boolean);
  if (invalid || !groups.length || !leafKeys.length) return null;
  const timeslots = [...new Set(value.timeslots.map((timeslot) => String(timeslot ?? '').trim()).filter(Boolean))]
    .sort((left, right) => left.localeCompare(right, undefined, { numeric: true }));
  const suppliedSignature = String(value.signature || '').trim();
  const signature = suppliedSignature || JSON.stringify({ groups, timeslots });
  return { signature, groups, timeslots, leafKeys };
}

let liveDetailFilterSequence = 0;

function liveDetailFilterModel(options = {}) {
  let catalog = null;
  let excludedLeafKeys = new Set();
  let excludedTimeslots = new Set();
  let excludedValidity = new Set();
  let searchText = '';
  const enabledLeafCount = () => catalog ?
    catalog.leafKeys.filter((key) => !excludedLeafKeys.has(key)).length : 0;
  const enabledTimeslotCount = () => catalog ?
    catalog.timeslots.filter((value) => !excludedTimeslots.has(value)).length : 0;
  const enabledValidityCount = () => 2 - excludedValidity.size;
  const allLeafKeysEnabled = () => Boolean(catalog) && enabledLeafCount() === catalog.leafKeys.length;
  const allTimeslotsEnabled = () => !catalog?.timeslots.length ||
    enabledTimeslotCount() === catalog.timeslots.length;
  const resetFilters = () => {
    excludedLeafKeys = new Set();
    excludedTimeslots = new Set();
    excludedValidity = new Set();
    searchText = '';
  };
  const leafSelection = (filterNode) => {
    const leafKeys = filterNode.children.length ? filterNode.children.flatMap((child) =>
      leafSelection(child).leafKeys) : [filterNode.key];
    return { leafKeys, selected: leafKeys.filter((key) => !excludedLeafKeys.has(key)).length };
  };
  return {
    catalog: () => catalog,
    setCatalog(value) {
      const next = liveDetailFilterCatalog(value);
      if (!next) return 'ignored';
      if (catalog?.signature === next.signature) return 'same';
      const state = catalog ? 'changed' : 'initial';
      catalog = next;
      return state;
    },
    resetFilters,
    leafSelection,
    setLeaves(leafKeys, enabled) {
      leafKeys.forEach((key) => {
        if (enabled) excludedLeafKeys.delete(key);
        else excludedLeafKeys.add(key);
      });
    },
    setTimeslot(value, enabled) {
      const key = String(value ?? '').trim();
      if (enabled) excludedTimeslots.delete(key);
      else excludedTimeslots.add(key);
    },
    setValidity(value, enabled) {
      if (enabled) excludedValidity.delete(value);
      else excludedValidity.add(value);
    },
    setSearch(value) { searchText = liveDetailText(value); },
    enabledLeafCount,
    enabledTimeslotCount,
    enabledValidityCount,
    isLeafEnabled: (key) => !excludedLeafKeys.has(key),
    isTimeslotEnabled: (value) => !excludedTimeslots.has(String(value ?? '').trim()),
    isValidityEnabled: (value) => !excludedValidity.has(value),
    allLeafKeysEnabled,
    allTimeslotsEnabled,
    matchesLeaf(value) {
      if (!catalog) return true;
      return !excludedLeafKeys.has(String(value || '').trim());
    },
    matchesTimeslot(value) {
      if (!options.timeslots || !catalog?.timeslots.length) return true;
      return !excludedTimeslots.has(String(value ?? '').trim());
    },
    matchesValidity(value) {
      if (!options.validity || excludedValidity.size === 0) return true;
      return !excludedValidity.has(value === true ? 'valid' : 'invalid');
    },
    query: () => searchText
  };
}

function liveDetailFilterController(options) {
  const model = liveDetailFilterModel(options);
  let expandedKeys = new Set();
  let modalApi = null;
  const triggerId = `live-detail-filter-trigger-${++liveDetailFilterSequence}`;
  const container = node('div', 'live-detail-filter-summary');
  const trigger = node('button', 'button secondary live-detail-filter-trigger', 'Filters');
  trigger.id = triggerId;
  trigger.type = 'button';
  trigger.disabled = true;
  trigger.setAttribute('aria-haspopup', 'dialog');
  const summary = node('span', 'live-detail-filter-state', `Waiting for ${options.noun} types`);
  summary.setAttribute('aria-live', 'polite');
  container.append(trigger, summary);

  const updateCompactSummary = () => {
    const catalog = model.catalog();
    trigger.disabled = !catalog;
    if (!catalog) {
      summary.textContent = `Waiting for ${options.noun} types`;
      trigger.title = summary.textContent;
      return;
    }
    const active = [];
    if (!model.allLeafKeysEnabled()) active.push(`${model.enabledLeafCount()}/${catalog.leafKeys.length} types`);
    if (options.timeslots && !model.allTimeslotsEnabled()) {
      active.push(`${model.enabledTimeslotCount()}/${catalog.timeslots.length} timeslots`);
    }
    if (options.validity && model.enabledValidityCount() !== 2) active.push('validity');
    if (model.query()) active.push('search');
    summary.textContent = active.length ? active.join(' · ') : `All ${options.noun}`;
    trigger.title = active.length ? `Active filters: ${active.join(', ')}` : `Showing all ${options.noun}`;
  };

  const notifyChange = () => {
    updateCompactSummary();
    options.onChange?.();
  };

  const closeModal = () => {
    if (modalApi?.state === activeReadOnlyModal) modalApi.close();
    modalApi = null;
  };

  const resetFilters = () => {
    model.resetFilters();
    notifyChange();
  };

  const openFilterModal = () => {
    const catalog = model.catalog();
    if (!catalog) return;
    const modalBody = node('div', 'live-filter-editor');
    modalBody.append(node('p', 'modal-lead',
      `Choose which ${options.noun} appear. New items are filtered in this browser only.`));
    const typeSection = node('section', 'live-filter-section');
    typeSection.append(node('h3', '', options.typeHeading || 'Types'));
    const typeActions = node('div', 'live-filter-type-actions');
    const showAll = node('button', 'button secondary', 'Show all types');
    const hideAll = node('button', 'button secondary', 'Hide all types');
    showAll.type = 'button';
    hideAll.type = 'button';
    typeActions.append(showAll, hideAll);
    typeSection.append(typeActions);
    const tree = node('div', 'live-filter-tree');
    tree.setAttribute('role', 'tree');
    tree.setAttribute('aria-label', options.typeHeading || `${options.noun} types`);
    typeSection.append(tree);
    modalBody.append(typeSection);

    const selectionInputs = [];
    let treeNodeSequence = 0;
    const updateTreeSelection = () => {
      selectionInputs.forEach(({ input, count, leafKeys }) => {
        const selected = leafKeys.filter((key) => model.isLeafEnabled(key)).length;
        input.checked = selected === leafKeys.length;
        input.indeterminate = selected > 0 && selected < leafKeys.length;
        if (count) count.textContent = `${selected}/${leafKeys.length}`;
      });
    };
    const appendFilterNode = (filterNode, parent, depth) => {
      const branch = filterNode.children.length > 0;
      const { leafKeys } = model.leafSelection(filterNode);
      const item = node('div', `live-filter-tree-item${branch ? ' branch' : ' leaf'}`);
      item.setAttribute('role', 'treeitem');
      item.setAttribute('aria-level', String(depth + 1));
      const row = node('div', 'live-filter-node-row');
      row.style.setProperty('--filter-indent', `${6 + depth * 18}px`);
      let children = null;
      if (branch) {
        const expand = node('button', 'live-filter-expand', expandedKeys.has(filterNode.key) ? '−' : '+');
        expand.type = 'button';
        expand.setAttribute('aria-label', `${expandedKeys.has(filterNode.key) ? 'Collapse' : 'Expand'} ${filterNode.label}`);
        expand.setAttribute('aria-expanded', String(expandedKeys.has(filterNode.key)));
        row.append(expand);
        children = node('div', 'live-filter-tree-children');
        children.id = `${triggerId}-tree-${++treeNodeSequence}`;
        children.setAttribute('role', 'group');
        children.hidden = !expandedKeys.has(filterNode.key);
        expand.setAttribute('aria-controls', children.id);
        expand.addEventListener('click', () => {
          const opening = children.hidden;
          children.hidden = !opening;
          expand.textContent = opening ? '−' : '+';
          expand.setAttribute('aria-expanded', String(opening));
          expand.setAttribute('aria-label', `${opening ? 'Collapse' : 'Expand'} ${filterNode.label}`);
          if (opening) expandedKeys.add(filterNode.key);
          else expandedKeys.delete(filterNode.key);
        });
      } else {
        const spacer = node('span', 'live-filter-expand-spacer');
        spacer.setAttribute('aria-hidden', 'true');
        row.append(spacer);
      }
      const label = node('label', 'live-filter-node-label');
      const input = node('input');
      input.type = 'checkbox';
      const text = node('span', '', filterNode.label);
      const count = branch ? node('span', 'live-filter-node-count') : null;
      label.append(input, text);
      if (count) label.append(count);
      row.append(label);
      item.append(row);
      selectionInputs.push({ input, count, leafKeys });
      input.addEventListener('change', () => {
        model.setLeaves(leafKeys, input.checked);
        updateTreeSelection();
        notifyChange();
      });
      if (children) {
        filterNode.children.forEach((child) => appendFilterNode(child, children, depth + 1));
        item.append(children);
      }
      parent.append(item);
    };
    catalog.groups.forEach((group) => appendFilterNode(group, tree, 0));
    updateTreeSelection();
    showAll.addEventListener('click', () => {
      model.setLeaves(catalog.leafKeys, true);
      updateTreeSelection();
      notifyChange();
    });
    hideAll.addEventListener('click', () => {
      model.setLeaves(catalog.leafKeys, false);
      updateTreeSelection();
      notifyChange();
    });

    const settings = node('div', 'live-filter-settings');
    if (options.timeslots && catalog.timeslots.length) {
      const timeslots = node('fieldset', 'live-filter-choice-group');
      timeslots.append(node('legend', '', 'Timeslots'));
      catalog.timeslots.forEach((value) => {
        const label = node('label');
        const input = node('input');
        input.type = 'checkbox';
        input.checked = model.isTimeslotEnabled(value);
        input.addEventListener('change', () => {
          model.setTimeslot(value, input.checked);
          notifyChange();
        });
        label.append(input, node('span', '', `Timeslot ${value}`));
        timeslots.append(label);
      });
      settings.append(timeslots);
    }
    if (options.validity) {
      const validity = node('fieldset', 'live-filter-choice-group');
      validity.append(node('legend', '', 'Validity'));
      [['valid', 'Valid messages'], ['invalid', 'Invalid messages']].forEach(([value, textValue]) => {
        const label = node('label');
        const input = node('input');
        input.type = 'checkbox';
        input.checked = model.isValidityEnabled(value);
        input.addEventListener('change', () => {
          model.setValidity(value, input.checked);
          notifyChange();
        });
        label.append(input, node('span', '', textValue));
        validity.append(label);
      });
      settings.append(validity);
    }
    const searchField = node('label', 'live-filter-search');
    searchField.append(node('span', '', 'Search'));
    const search = node('input');
    search.type = 'search';
    search.value = model.query();
    search.placeholder = options.searchPlaceholder || `Search ${options.noun}`;
    search.addEventListener('input', () => {
      model.setSearch(search.value);
      notifyChange();
    });
    searchField.append(search);
    settings.append(searchField);
    modalBody.append(settings);

    const footer = node('div', 'live-filter-footer');
    const reset = node('button', 'button secondary', 'Reset filters');
    const done = node('button', 'button', 'Done');
    reset.type = 'button';
    done.type = 'button';
    reset.addEventListener('click', () => {
      resetFilters();
      selectionInputs.forEach(({ input }) => { input.checked = true; });
      updateTreeSelection();
      modalBody.querySelectorAll('.live-filter-choice-group input').forEach((input) => { input.checked = true; });
      search.value = '';
    });
    done.addEventListener('click', () => modalApi?.close());
    footer.append(reset, done);
    modalBody.append(footer);

    let openedModal = null;
    openedModal = openReadOnlyModal(options.title || `${options.noun} filters`, modalBody, {
      id: `${options.noun}-filters`,
      className: 'live-filter-modal',
      returnFocusSelector: `#${triggerId}`,
      cleanup: () => {
        if (modalApi === openedModal) modalApi = null;
      }
    });
    modalApi = openedModal;
  };

  trigger.addEventListener('click', openFilterModal);
  updateCompactSummary();
  return {
    element: container,
    setCatalog(value) {
      const state = model.setCatalog(value);
      if (state === 'ignored' || state === 'same') return state;
      closeModal();
      const catalog = model.catalog();
      expandedKeys = new Set(catalog.groups.length <= 2 ? catalog.groups.map((group) => group.key) : []);
      updateCompactSummary();
      return state;
    },
    matchesLeaf: model.matchesLeaf,
    matchesTimeslot: model.matchesTimeslot,
    matchesValidity: model.matchesValidity,
    query: model.query,
    close: closeModal
  };
}

function liveMessagesPane() {
  const messages = new Map();
  const order = [];
  let selection = null;
  let active = false;
  let collapsed = false;
  let paused = false;
  let stream = null;
  let streamEpoch = 0;
  let renderTimer = null;
  let lastRenderAt = 0;
  let missed = 0;
  let possibleGap = false;
  let expectedSubscriptionId = null;
  let transportReady = false;
  let scheduleRender = () => {};

  const pane = node('div', 'live-details-pane live-messages-pane');
  const toolbar = node('div', 'live-messages-toolbar live-detail-toolbar');
  const selectionLabel = node('strong', 'live-message-selection', 'Select a live row above');
  const filters = liveDetailFilterController({
    noun: 'messages',
    title: 'Message filters',
    typeHeading: 'Message types',
    searchPlaceholder: 'Search message text',
    timeslots: true,
    validity: true,
    onChange: () => scheduleRender()
  });
  toolbar.append(selectionLabel, filters.element);
  const gap = node('div', 'live-detail-gap');
  gap.hidden = true;
  gap.setAttribute('role', 'status');
  const scroll = node('div', 'live-messages-scroll');
  const table = node('table', 'data-table live-messages-table');
  const head = node('thead');
  const headerRow = node('tr');
  ['Time', 'Protocol', 'Timeslot', 'Message'].forEach((label) => headerRow.append(node('th', '', label)));
  head.append(headerRow);
  const body = node('tbody');
  table.append(head, body);
  scroll.append(table);
  pane.append(toolbar, gap, scroll);

  const matches = (message) => {
    if (!filters.matchesLeaf(message.filter_key)) return false;
    if (!filters.matchesTimeslot(message.timeslot)) return false;
    if (!filters.matchesValidity(message.valid)) return false;
    const query = filters.query();
    return !query || [message.text, message.protocol, message.filter_label]
      .some((value) => liveDetailText(value).includes(query));
  };

  const render = () => {
    if (paused) return;
    body.replaceChildren();
    const rows = order.map((id) => messages.get(id)).filter((message) => message && matches(message))
      .slice(0, liveDetailMatchingRowLimit());
    if (!selection || !rows.length) {
      const empty = node('tr', 'empty');
      const text = !selection ? 'Select a live row above' :
        (selection.bindingFrequencyHz ? 'No matching messages received since this tab was opened' :
          'Select an active channel');
      const cell = node('td', '', text);
      cell.colSpan = 4;
      empty.append(cell);
      body.append(empty);
      return;
    }
    rows.forEach((message) => {
      const row = node('tr', message.valid ? '' : 'message-invalid');
      const date = new Date(Number(message.timestamp_ms));
      const timeText = Number.isFinite(date.getTime()) ? date.toLocaleTimeString([], {
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      }) : '';
      const time = node('td', '', timeText);
      if (timeText) time.title = exactDateTime(message.timestamp_ms);
      const detail = node('td', 'live-message-text', message.text || '');
      detail.title = message.text || '';
      row.append(time, node('td', '', message.protocol || ''),
        node('td', '', message.timeslot == null ? '' : String(message.timeslot)), detail);
      body.append(row);
    });
  };

  scheduleRender = () => {
    if (renderTimer !== null || paused) return;
    const delay = Math.max(0, LIVE_DETAIL_REFRESH_INTERVAL_MILLISECONDS - (Date.now() - lastRenderAt));
    renderTimer = window.setTimeout(() => {
      renderTimer = null;
      lastRenderAt = Date.now();
      render();
    }, delay);
  };
  const updateGapNotice = () => {
    const notices = [];
    if (missed > 0) {
      notices.push(`${number(missed)} live message${missed === 1 ? '' : 's'} skipped while the viewer was open.`);
    }
    if (possibleGap) notices.push('The live source reconnected or changed; additional messages may have been missed.');
    gap.textContent = notices.join(' ');
    gap.hidden = !notices.length;
  };
  const clearSession = () => {
    messages.clear();
    order.length = 0;
    missed = 0;
    possibleGap = false;
    updateGapNotice();
    scheduleRender();
  };
  const closeStream = () => {
    streamEpoch += 1;
    transportReady = false;
    expectedSubscriptionId = null;
    if (!stream) return;
    stream.close();
    liveConnections.delete(stream);
    pageConnections.delete(stream);
    stream = null;
  };
  const addMessage = (message) => {
    if (!message?.message_id) return;
    if (!messages.has(message.message_id)) order.unshift(message.message_id);
    messages.set(message.message_id, message);
    while (order.length > liveDetailCaptureLimit()) {
      const removedId = order.pop();
      messages.delete(removedId);
    }
    scheduleRender();
  };
  const addGap = (value) => {
    missed += Math.max(1, Math.trunc(Number(value?.dropped) || 1));
    updateGapNotice();
  };
  const shouldRun = () => active && !collapsed && !document.hidden && selection?.configurationId &&
    selection?.bindingFrequencyHz;
  const sync = () => {
    if (!shouldRun()) {
      closeStream();
      return;
    }
    if (stream) return;
    const epoch = ++streamEpoch;
    const parameters = liveDetailTransportParameters(selection);
    expectedSubscriptionId = randomLiveClientId();
    parameters.subscription_id = expectedSubscriptionId;
    transportReady = false;
    let opened = document.hidden;
    let sourceStatusSeen = false;
    let sourceEverBound = false;
    stream = liveConnection('decode_messages', parameters);
    stream.onopen = () => {
      if (epoch !== streamEpoch) return;
      if (opened) {
        possibleGap = true;
        updateGapNotice();
      }
      opened = true;
    };
    stream.addEventListener('decode_message', (event) => {
      if (epoch === streamEpoch && transportReady) addMessage(JSON.parse(event.data));
    });
    stream.addEventListener('live_gap', (event) => {
      if (epoch === streamEpoch && transportReady) addGap(JSON.parse(event.data));
    });
    stream.addEventListener('source_change', (event) => {
      if (epoch !== streamEpoch) return;
      const change = JSON.parse(event.data);
      transportReady = false;
      if (!liveMessageSourceMatchesSelection(selection, expectedSubscriptionId, change)) return;
      transportReady = true;
      filters.setCatalog(change?.filter_catalog);
      const bound = change?.bound === true;
      if (!sourceStatusSeen) {
        sourceStatusSeen = true;
        sourceEverBound = bound;
      } else if (sourceEverBound) {
        possibleGap = true;
        updateGapNotice();
      } else if (bound) sourceEverBound = true;
    });
  };
  const select = (nextSelection) => {
    const { logicalChanged } = liveDetailSelectionDelta(selection, nextSelection);
    const transportChanged = liveMessageTransportChanged(selection, nextSelection);
    selection = nextSelection;
    selectionLabel.textContent = selection?.channelLabel || 'Select a live row above';
    if (logicalChanged) {
      closeStream();
      clearSession();
    } else if (transportChanged) {
      transportReady = false;
      if (stream) {
        possibleGap = true;
        updateGapNotice();
      }
      const parameters = liveDetailTransportParameters(selection);
      if (stream && parameters) {
        expectedSubscriptionId = randomLiveClientId();
        parameters.subscription_id = expectedSubscriptionId;
        stream.update(parameters);
      }
      else if (stream) closeStream();
    }
    sync();
  };
  const onVisibilityChange = () => {
    if (document.hidden && stream) {
      possibleGap = true;
      updateGapNotice();
    }
    sync();
  };
  document.addEventListener('visibilitychange', onVisibilityChange);
  render();
  return {
    element: pane,
    select,
    setActive(value) {
      const next = value === true;
      if (active && !next) {
        possibleGap = Boolean(selection);
        updateGapNotice();
        closeStream();
      }
      active = next;
      sync();
    },
    setCollapsed(value) {
      if (!collapsed && value === true && stream) {
        possibleGap = true;
        updateGapNotice();
      }
      collapsed = value;
      sync();
    },
    setPaused(value) { paused = value; if (!paused) scheduleRender(); },
    close() {
      closeStream();
      if (renderTimer !== null) window.clearTimeout(renderTimer);
      renderTimer = null;
      messages.clear();
      order.length = 0;
      filters.close();
      document.removeEventListener('visibilitychange', onVisibilityChange);
    }
  };
}

function liveChannelPane() {
  let selection = null;
  let active = false;
  let collapsed = false;
  let paused = false;
  let stream = null;
  let streamEpoch = 0;
  let state = null;
  let generation = -1;
  let awaitingState = false;
  let expectedSubscriptionId = null;
  let signalSequence = 0;
  let symbolSequence = 0;
  let signalValues = new Float32Array(0);
  let signalPeak = null;
  let signalLatencyMs = null;
  const latencyClock = { offsetMs: null };
  let signalView = 'fft';
  const waterfallBuffer = document.createElement('canvas');
  const waterfallContext = waterfallBuffer.getContext('2d', { alpha: false });
  const waterfallPalette = tunerWaterfallPalette();
  let waterfallRowImage = null;
  let newestWaterfallRow = -1;
  let nextWaterfallRow = -1;
  let symbolValues = new Float32Array(4800);
  symbolValues.fill(Number.NaN);
  let symbolCursor = 0;
  let symbolCount = 0;
  let signalDirty = true;
  let symbolsDirty = true;
  let drawPending = false;

  const pane = node('div', 'live-details-pane live-channel-pane');
  const toolbar = node('div', 'live-channel-toolbar');
  const selectionLabel = node('strong', 'live-channel-selection', 'Select a live row above');
  const connection = badge('Waiting', 'state-stale');
  toolbar.append(selectionLabel, connection);

  const diagnostic = (title, ariaLabel) => {
    const card = node('section', 'channel-diagnostic-card');
    const header = node('div', 'channel-diagnostic-header');
    const plot = node('div', 'channel-diagnostic-plot');
    const canvas = node('canvas', 'channel-diagnostic-canvas');
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', ariaLabel);
    const overlay = node('div', 'channel-diagnostic-overlay', 'Select a live row above');
    const readouts = node('div', 'channel-diagnostic-readouts');
    plot.append(canvas, overlay);
    header.append(node('h3', 'channel-diagnostic-title', title));
    card.append(header, plot, readouts);
    return { card, header, canvas, overlay, readouts };
  };
  const signalDiagnostic = diagnostic('Signal', 'Selected channel signal spectrum');
  const symbolDiagnostic = diagnostic('Symbols', 'Selected channel demodulated symbols');
  const signalViewToggle = node('div', 'channel-diagnostic-view-toggle');
  signalViewToggle.setAttribute('role', 'group');
  signalViewToggle.setAttribute('aria-label', 'Signal graph view');
  const signalViewButtons = ['FFT', 'Waterfall'].map((label) => {
    const button = node('button', 'channel-diagnostic-view-button', label);
    button.type = 'button';
    button.dataset.view = label.toLowerCase();
    button.setAttribute('aria-pressed', String(button.dataset.view === signalView));
    signalViewToggle.append(button);
    return button;
  });
  signalDiagnostic.header.append(signalViewToggle);
  const diagnosticGrid = node('div', 'channel-diagnostic-grid');
  diagnosticGrid.append(signalDiagnostic.card, symbolDiagnostic.card);
  pane.append(toolbar, diagnosticGrid);

  const setStatus = (text, className = 'state-stale') => {
    connection.textContent = text;
    connection.className = `badge ${className}`;
  };

  const updateReadouts = () => {
    const centerFrequencyHz = Number(state?.frequency_hz ?? state?.center_frequency_hz ??
      selection?.bindingFrequencyHz);
    updateDiagnosticReadouts(signalDiagnostic.readouts, [
      ['Center', centerFrequencyHz ? `${frequency(centerFrequencyHz)} MHz` : '—'],
      ['Peak', Number.isFinite(signalPeak) ? `${signalPeak.toFixed(1)} dB` : '—'],
      ['Latency', Number.isFinite(signalLatencyMs) ? `${Math.round(signalLatencyMs)} ms` : '—']
    ]);
    updateDiagnosticReadouts(symbolDiagnostic.readouts, [
      ['Decoder', state?.decoder_profile || state?.protocol || '—']
    ]);
  };

  const prepareCanvas = (target) => {
    const bounds = target.canvas.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return null;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    const width = Math.max(1, Math.round(bounds.width * ratio));
    const height = Math.max(1, Math.round(bounds.height * ratio));
    if (target.canvas.width !== width || target.canvas.height !== height) {
      target.canvas.width = width;
      target.canvas.height = height;
    }
    const context = target.canvas.getContext('2d');
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    const cssWidth = width / ratio;
    const cssHeight = height / ratio;
    return { context, cssWidth, cssHeight, width, height };
  };

  const drawBackground = ({ context, cssWidth, cssHeight }) => {
    context.fillStyle = '#07111d';
    context.fillRect(0, 0, cssWidth, cssHeight);
    context.strokeStyle = 'rgba(150, 177, 199, 0.18)';
    context.lineWidth = 1;
    for (let line = 1; line < 4; line += 1) {
      const y = cssHeight * line / 4;
      context.beginPath();
      context.moveTo(0, y);
      context.lineTo(cssWidth, y);
      context.stroke();
    }
  };

  const resetWaterfall = (binCount = 1) => {
    waterfallBuffer.width = Math.max(1, binCount);
    waterfallBuffer.height = 256;
    waterfallContext.fillStyle = '#040b18';
    waterfallContext.fillRect(0, 0, waterfallBuffer.width, waterfallBuffer.height);
    waterfallRowImage = waterfallContext.createImageData(waterfallBuffer.width, 1);
    newestWaterfallRow = -1;
    nextWaterfallRow = waterfallBuffer.height - 1;
  };

  const addWaterfallFrame = (values) => {
    if (!values.length) return;
    if (waterfallBuffer.width !== values.length || waterfallBuffer.height !== 256 || !waterfallRowImage) {
      resetWaterfall(values.length);
    }
    const row = waterfallRowImage;
    values.forEach((raw, index) => {
      const value = Number.isFinite(raw) ? Math.max(-120, Math.min(0, raw)) : -120;
      const color = Math.max(0, Math.min(255, Math.round((value + 120) / 120 * 255)));
      row.data[index * 4] = waterfallPalette[color * 4];
      row.data[index * 4 + 1] = waterfallPalette[color * 4 + 1];
      row.data[index * 4 + 2] = waterfallPalette[color * 4 + 2];
      row.data[index * 4 + 3] = 255;
    });
    waterfallContext.putImageData(row, 0, nextWaterfallRow);
    newestWaterfallRow = nextWaterfallRow;
    nextWaterfallRow = (nextWaterfallRow - 1 + waterfallBuffer.height) % waterfallBuffer.height;
  };

  const drawWaterfall = (prepared) => {
    const { context, width, height } = prepared;
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.fillStyle = '#040b18';
    context.fillRect(0, 0, width, height);
    if (newestWaterfallRow < 0) return;
    const firstRows = waterfallBuffer.height - newestWaterfallRow;
    const firstHeight = firstRows / waterfallBuffer.height * height;
    context.drawImage(waterfallBuffer, 0, newestWaterfallRow, waterfallBuffer.width, firstRows,
      0, 0, width, firstHeight);
    if (newestWaterfallRow > 0) {
      context.drawImage(waterfallBuffer, 0, 0, waterfallBuffer.width, newestWaterfallRow,
        0, firstHeight, width, height - firstHeight);
    }
  };

  const drawSignal = () => {
    const prepared = prepareCanvas(signalDiagnostic);
    if (!prepared) return;
    if (signalView === 'waterfall') {
      drawWaterfall(prepared);
      return;
    }
    drawBackground(prepared);
    if (signalValues.length < 2) return;
    const { context, cssWidth, cssHeight } = prepared;
    context.strokeStyle = '#55c7ff';
    context.lineWidth = 1.5;
    context.beginPath();
    for (let index = 0; index < signalValues.length; index += 1) {
      const raw = signalValues[index];
      const value = Number.isFinite(raw) ? Math.max(-120, Math.min(0, raw)) : -120;
      const x = index * cssWidth / (signalValues.length - 1);
      const y = -value / 120 * cssHeight;
      if (index === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    }
    context.stroke();
  };

  const drawSymbols = () => {
    const prepared = prepareCanvas(symbolDiagnostic);
    if (!prepared) return;
    drawBackground(prepared);
    if (!symbolCount) return;
    const { context, cssWidth, cssHeight } = prepared;
    context.fillStyle = '#65d6a6';
    const denominator = Math.max(1, symbolValues.length - 1);
    for (let index = 0; index < symbolValues.length; index += 1) {
      const raw = symbolValues[index];
      if (!Number.isFinite(raw)) continue;
      const value = Math.max(-Math.PI, Math.min(Math.PI, raw));
      const x = index * Math.max(0, cssWidth - 1.5) / denominator;
      const y = (Math.PI - value) / (2 * Math.PI) * cssHeight;
      context.fillRect(x, y, 1.5, 1.5);
    }
  };

  const draw = () => {
    drawPending = false;
    if (signalDirty) {
      signalDirty = false;
      drawSignal();
    }
    if (symbolsDirty) {
      symbolsDirty = false;
      drawSymbols();
    }
  };

  const scheduleDraw = (kind = 'both') => {
    if (kind === 'signal' || kind === 'both') signalDirty = true;
    if (kind === 'symbols' || kind === 'both') symbolsDirty = true;
    if (drawPending) return;
    drawPending = true;
    window.requestAnimationFrame(draw);
  };

  const setSignalView = (view) => {
    signalView = view === 'waterfall' ? 'waterfall' : 'fft';
    signalViewButtons.forEach((button) => {
      button.setAttribute('aria-pressed', String(button.dataset.view === signalView));
    });
    signalDiagnostic.canvas.setAttribute('aria-label', signalView === 'waterfall' ?
      'Selected channel signal waterfall' : 'Selected channel signal spectrum');
    scheduleDraw('signal');
  };
  signalViewButtons.forEach((button) => {
    button.addEventListener('click', () => setSignalView(button.dataset.view));
  });

  const clearPlots = (message) => {
    state = null;
    generation = -1;
    signalSequence = 0;
    symbolSequence = 0;
    signalValues = new Float32Array(0);
    signalPeak = null;
    signalLatencyMs = null;
    latencyClock.offsetMs = null;
    resetWaterfall();
    symbolValues = new Float32Array(4800);
    symbolValues.fill(Number.NaN);
    symbolCursor = 0;
    symbolCount = 0;
    [signalDiagnostic, symbolDiagnostic].forEach((target) => {
      target.overlay.textContent = message || '';
      target.overlay.hidden = !message;
    });
    updateReadouts();
    scheduleDraw();
  };

  const updateDiagnosticState = (target, currentState, reason, fallback) => {
    const live = currentState === 'live';
    target.overlay.textContent = live ? '' : (reason || fallback);
    target.overlay.hidden = live;
  };

  const closeStream = () => {
    streamEpoch += 1;
    awaitingState = false;
    expectedSubscriptionId = null;
    if (!stream) return;
    stream.close();
    liveConnections.delete(stream);
    pageConnections.delete(stream);
    stream = null;
  };

  const shouldRun = () => active && !collapsed && !paused && !document.hidden &&
    selection?.configurationId && selection?.bindingFrequencyHz;

  const sync = () => {
    if (!shouldRun()) {
      closeStream();
      if (!selection) {
        setStatus('Waiting');
        clearPlots('Select a live row above');
      } else if (!selection.bindingFrequencyHz) {
        setStatus('Unavailable');
        clearPlots('The selected row does not have an active frequency.');
      } else if (paused) {
        setStatus('Paused');
      } else {
        setStatus(document.hidden ? 'Hidden' : 'Paused');
      }
      return;
    }
    if (stream) return;
    setStatus('Connecting');
    clearPlots('Waiting for channel data…');
    awaitingState = true;
    expectedSubscriptionId = randomLiveClientId();
    const epoch = ++streamEpoch;
    const parameters = liveDetailTransportParameters(selection, true);
    parameters.subscription_id = expectedSubscriptionId;
    stream = binaryFrameConnection('channel_diagnostics', parameters, {
      onOpen: () => {
        if (epoch === streamEpoch) setStatus('Connected', 'state-current');
      },
      onFrame: (frame) => {
        if (epoch !== streamEpoch || frame.type === DIAGNOSTIC_FRAME_TYPES.HEARTBEAT) return;
        if (frame.type === DIAGNOSTIC_FRAME_TYPES.STATE) {
          const nextState = diagnosticJsonPayload(frame);
          if (!liveChannelStateMatchesSelection(selection, expectedSubscriptionId, nextState)) return;
          awaitingState = false;
          state = nextState;
          generation = frame.generation;
          signalSequence = 0;
          symbolSequence = 0;
          signalValues = new Float32Array(0);
          signalPeak = null;
          signalLatencyMs = null;
          latencyClock.offsetMs = null;
          resetWaterfall();
          const maximumSymbols = Math.max(1, Number(state?.maximum_visible_symbols) || 4800);
          symbolValues = new Float32Array(maximumSymbols);
          symbolValues.fill(Number.NaN);
          symbolCursor = 0;
          symbolCount = 0;
          const signalState = state?.signal_state;
          const symbolsState = state?.symbols_state;
          updateDiagnosticState(signalDiagnostic, signalState,
            state?.signal_reason, 'Signal diagnostics are unavailable.');
          updateDiagnosticState(symbolDiagnostic, symbolsState,
            state?.symbols_reason, 'Symbol diagnostics are unavailable.');
          const live = signalState === 'live' || symbolsState === 'live';
          const waiting = signalState === 'waiting' || symbolsState === 'waiting';
          setStatus(live ? 'Live' : (waiting ? 'Waiting' : 'Unavailable'),
            live ? 'state-current' : 'state-stale');
          updateReadouts();
          scheduleDraw();
          return;
        }
        if (awaitingState) return;
        if (generation >= 0 && frame.generation !== generation) return;
        if (frame.type === DIAGNOSTIC_FRAME_TYPES.CHANNEL_SIGNAL) {
          if (frame.sequence <= signalSequence) return;
          signalSequence = frame.sequence;
          signalValues = diagnosticFloatPayload(frame);
          signalPeak = null;
          for (let index = 0; index < signalValues.length; index += 1) {
            if (Number.isFinite(signalValues[index]) &&
                (!Number.isFinite(signalPeak) || signalValues[index] > signalPeak)) signalPeak = signalValues[index];
          }
          if (frame.centerFrequencyHz > 0 && state) state.frequency_hz = frame.centerFrequencyHz;
          signalLatencyMs = diagnosticFrameLatency(frame, latencyClock);
          addWaterfallFrame(signalValues);
          signalDiagnostic.overlay.hidden = true;
          updateReadouts();
          scheduleDraw('signal');
          return;
        }
        if (frame.type !== DIAGNOSTIC_FRAME_TYPES.CHANNEL_SYMBOLS || frame.sequence <= symbolSequence) return;
        symbolSequence = frame.sequence;
        const incoming = diagnosticFloatPayload(frame);
        if (!incoming.length) return;
        const capacity = symbolValues.length;
        for (let index = 0; index < incoming.length; index += 1) {
          symbolValues[symbolCursor] = incoming[index];
          symbolCursor = (symbolCursor + 1) % capacity;
          symbolCount = Math.min(capacity, symbolCount + 1);
        }
        symbolDiagnostic.overlay.hidden = true;
        scheduleDraw('symbols');
      },
      onError: (error) => {
        if (epoch !== streamEpoch) return;
        setStatus(error?.status === 429 ? 'Busy' : 'Reconnecting');
        const reason = error?.status === 429 ? 'Diagnostic viewer capacity is currently in use.' :
          'Connection interrupted. Reconnecting…';
        const signalState = state?.signal_state;
        const symbolsState = state?.symbols_state;
        if (signalState === 'live') updateDiagnosticState(signalDiagnostic, 'stale', reason, reason);
        if (symbolsState === 'live') updateDiagnosticState(symbolDiagnostic, 'stale', reason, reason);
      }
    });
  };

  const select = (nextSelection) => {
    const { logicalChanged, transportChanged } = liveDetailSelectionDelta(selection, nextSelection);
    selection = nextSelection;
    selectionLabel.textContent = selection?.channelLabel || 'Select a live row above';
    if (logicalChanged) {
      closeStream();
      clearPlots(selection ? 'Waiting for channel data…' : 'Select a live row above');
    } else if (transportChanged) {
      awaitingState = true;
      setStatus('Connecting');
      clearPlots('Waiting for channel data…');
      const parameters = liveDetailTransportParameters(selection, true);
      if (stream && parameters) {
        expectedSubscriptionId = randomLiveClientId();
        parameters.subscription_id = expectedSubscriptionId;
        stream.update(parameters);
      }
      else if (stream) closeStream();
    }
    sync();
  };

  const onVisibilityChange = () => sync();
  const onResize = () => scheduleDraw();
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('resize', onResize);
  clearPlots('Select a live row above');
  return {
    element: pane,
    select,
    setActive(value) { active = value; sync(); scheduleDraw(); },
    setCollapsed(value) { collapsed = value; sync(); },
    setPaused(value) { paused = value; sync(); },
    close() {
      closeStream();
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('resize', onResize);
    }
  };
}

function tunerDiagnosticTargets(response) {
  const values = Array.isArray(response?.rows) ? response.rows : [];
  return values.map((target) => ({
    ...target,
    id: String(target?.target_id ?? ''),
    label: String(target?.label ?? target?.target_id ?? 'Tuner')
  })).filter((target) => target.id);
}

function tunerWaterfallPalette() {
  const stops = [
    [0, 4, 11, 24],
    [0.22, 14, 42, 94],
    [0.46, 22, 135, 184],
    [0.68, 76, 214, 170],
    [0.84, 247, 216, 74],
    [1, 239, 77, 61]
  ];
  const palette = new Uint8ClampedArray(256 * 4);
  for (let index = 0; index < 256; index += 1) {
    const value = index / 255;
    let upper = 1;
    while (upper < stops.length - 1 && value > stops[upper][0]) upper += 1;
    const lower = Math.max(0, upper - 1);
    const span = Math.max(0.0001, stops[upper][0] - stops[lower][0]);
    const mix = Math.max(0, Math.min(1, (value - stops[lower][0]) / span));
    for (let channel = 1; channel <= 3; channel += 1) {
      palette[index * 4 + channel - 1] = Math.round(
        stops[lower][channel] + (stops[upper][channel] - stops[lower][channel]) * mix);
    }
    palette[index * 4 + 3] = 255;
  }
  return palette;
}

const TUNER_SPECTRUM_DEFAULT_FLOOR_DB = -140;
const TUNER_SPECTRUM_DEFAULT_CEILING_DB = 0;
const TUNER_SPECTRUM_MINIMUM_DISPLAY_DB = -200;
const TUNER_SPECTRUM_MAXIMUM_DISPLAY_DB = 0;
const TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB = 5;
const TUNER_SPECTRUM_MAXIMUM_ZOOM = 64;
const TUNER_SPECTRUM_ZOOM_FACTOR = 1.5;
const TUNER_SPECTRUM_VIEWPORT_DEBOUNCE_MS = 160;
const TUNER_SPECTRUM_SMOOTHING_ALPHA = 0.25;
const TUNER_WATERFALL_HISTORY_ROWS = 256;
const TUNER_SPECTRUM_FLOOR_STORAGE_KEY = 'sdrtrunk.wideband.lowerDisplayLimitDb';
const TUNER_SPECTRUM_CEILING_STORAGE_KEY = 'sdrtrunk.wideband.upperDisplayLimitDb';
const TUNER_WATERFALL_SPEED_STORAGE_KEY = 'sdrtrunk.wideband.waterfallScrollSpeed';
const TUNER_SPECTRUM_SNAP_STORAGE_KEY = 'sdrtrunk.wideband.snapFrequency';
const TUNER_SPECTRUM_SMOOTH_STORAGE_KEY = 'sdrtrunk.wideband.smoothFft';
const TUNER_WATERFALL_CHANNELS_STORAGE_KEY = 'sdrtrunk.wideband.highlightWaterfallChannels';
const TUNER_SPECTRUM_PROFILE_STORAGE_KEY = 'sdrtrunk.wideband.profile';
const TUNER_SPECTRUM_TARGET_STORAGE_KEY = 'sdrtrunk.wideband.targetId';
const TUNER_SPECTRUM_PROFILES = Object.freeze({
  efficient: Object.freeze({ fftSize: 2048, fps: 5 }),
  balanced: Object.freeze({ fftSize: 8192, fps: 10 }),
  'high-detail': Object.freeze({ fftSize: 16384, fps: 20 }),
  'maximum-detail': Object.freeze({ fftSize: 32768, fps: 20 })
});
const TUNER_CHANNEL_VISUAL_BANDWIDTH_HZ = 25_000;
const TUNER_CHANNEL_MINIMUM_WIDTH_PX = 3.5;
const TUNER_CHANNEL_MAXIMUM_WIDTH_PX = 14;
const TUNER_FREQUENCY_RASTERS = Object.freeze([
  Object.freeze({ id: 'vhf-land-mobile', minHz: 150_000_000, maxHz: 173_997_500,
    originHz: 150_000_000, stepHz: 2_500, label: 'VHF land mobile' }),
  Object.freeze({ id: 'uhf-land-mobile-421', minHz: 421_000_000, maxHz: 429_993_750,
    originHz: 421_000_000, stepHz: 6_250, label: 'UHF land mobile' }),
  Object.freeze({ id: 'uhf-land-mobile-450', minHz: 450_000_000, maxHz: 511_993_750,
    originHz: 450_000_000, stepHz: 6_250, label: 'UHF land mobile' }),
  Object.freeze({ id: '700-base', minHz: 769_006_250, maxHz: 774_993_750,
    originHz: 769_006_250, stepHz: 6_250, label: '700 MHz base' }),
  Object.freeze({ id: '700-mobile', minHz: 799_006_250, maxHz: 804_993_750,
    originHz: 799_006_250, stepHz: 6_250, label: '700 MHz mobile' }),
  Object.freeze({ id: '800-mobile', minHz: 806_006_250, maxHz: 823_993_750,
    originHz: 806_006_250, stepHz: 6_250, label: '800 MHz mobile' }),
  Object.freeze({ id: '800-base', minHz: 851_006_250, maxHz: 868_993_750,
    originHz: 851_006_250, stepHz: 6_250, label: '800 MHz base' }),
  Object.freeze({ id: '900-mobile', minHz: 896_012_500, maxHz: 900_987_500,
    originHz: 896_012_500, stepHz: 12_500, label: '900 MHz mobile' }),
  Object.freeze({ id: '900-base', minHz: 935_012_500, maxHz: 939_987_500,
    originHz: 935_012_500, stepHz: 12_500, label: '900 MHz base' })
]);
const TUNER_ACTIVITY_PRIORITY = Object.freeze({
  ENCRYPTED: 6, CALL: 5, DATA: 4, CONTROL: 3, ACTIVE: 2
});
const TUNER_ACTIVITY_LABELS = Object.freeze({
  ENCRYPTED: 'Encrypted voice',
  CALL: 'Voice call',
  DATA: 'Data activity',
  CONTROL: 'Control channel',
  ACTIVE: 'Other activity'
});
const RADIO_REFERENCE_DETAIL_CACHE_LIMIT = 100;
const radioReferenceDetailCache = new Map();

function radioReferenceDetailKey(row, frequencyHz) {
  return [Math.round(Number(frequencyHz)), Number(row.system_id || 0), Number(row.site_number || 0),
    Number(row.sub_category_id || 0),
    Number(row.agency_id || 0), Number(row.county_id || 0), String(row.mode_code || '')].join(':');
}

async function loadRadioReferenceDetails(row, frequencyHz, signal = null) {
  const key = radioReferenceDetailKey(row, frequencyHz);
  if (radioReferenceDetailCache.has(key)) return radioReferenceDetailCache.get(key);
  const query = new URLSearchParams({
    frequency_hz: String(Math.round(Number(frequencyHz))),
    system_id: String(Number(row.system_id || 0)),
    site_number: String(Number(row.site_number || 0)),
    sub_category_id: String(Number(row.sub_category_id || 0)),
    agency_id: String(Number(row.agency_id || 0)),
    county_id: String(Number(row.county_id || 0)),
    mode: String(row.mode_code || '')
  });
  const details = await requestJson(`/api/v1/admin/radioreference/frequencies/details?${query}`, {
    csrf: false, page: false, timeoutMs: 65_000, signal
  });
  if (radioReferenceDetailCache.size >= RADIO_REFERENCE_DETAIL_CACHE_LIMIT) {
    radioReferenceDetailCache.delete(radioReferenceDetailCache.keys().next().value);
  }
  radioReferenceDetailCache.set(key, details);
  return details;
}

function radioReferenceResultView(matches, frequencyHz, signal = null) {
  const rows = Array.isArray(matches) ? matches : [];
  const siteLabel = (value) => {
    const number = Number(value?.site_number);
    const numbered = Number.isInteger(number) && number > 0 ? `Site ${String(number).padStart(3, '0')}` : '';
    return [numbered, String(value?.site_name || '').trim()].filter(Boolean).join(' ');
  };
  const conventional = rows.filter((row) => row.match_type !== 'TRUNKED');
  const trunked = rows.filter((row) => row.match_type === 'TRUNKED');

  if (!rows.length) {
    return node('p', 'empty radioreference-frequency-empty',
      'No RadioReference records match this frequency in the selected state.');
  }

  const grouped = node('div', 'radioreference-frequency-groups');
  const section = (label, items, isTrunked) => {
    if (!items.length) return;
    const group = node('section', 'radioreference-frequency-group');
    const grid = node('div', 'radioreference-result-grid');
    items.forEach((row) => {
      const card = node('article', 'radioreference-result-card');
      const title = isTrunked ? siteLabel(row) || row.description || row.system_name :
        row.alpha_tag || row.description || 'Conventional frequency';
      const header = node('div', 'radioreference-result-card-header');
      header.append(node('h4', '', title), node('span', 'radioreference-result-type',
        isTrunked ? 'Trunked' : 'Conventional'));

      const values = node('dl', 'radioreference-result-facts');
      const factValues = new Map();
      const addFact = (key, factLabel, value, required = false) => {
        const present = value instanceof Node || String(value ?? '').trim();
        if (!required && !present) return;
        const factValue = node('dd');
        factValue.append(valueNode(present ? value : '—'));
        values.append(node('dt', '', factLabel), factValue);
        factValues.set(key, factValue);
      };
      const replaceFact = (key, value) => {
        const target = factValues.get(key);
        if (target) target.replaceChildren(valueNode(value));
      };

      if (isTrunked) {
        const system = row.radio_reference_url ?
          externalAnchor(availableValue(row.system_name), row.radio_reference_url) : availableValue(row.system_name);
        addFact('system', 'System', system, true);
        addFact('site', 'Site', siteLabel(row), true);
        addFact('channel-use', 'Channel use', 'Load details to identify', true);
      } else {
        addFact('name', 'Name', row.alpha_tag);
        addFact('description', 'Description', row.description);
        addFact('agency', 'Agency', row.agency_name);
        addFact('county', 'County', row.county_name);
        addFact('category', 'Category', 'Load details to identify', true);
        addFact('mode', 'Mode', row.mode_name, true);
        addFact('type', 'Radio type', row.classification);
        addFact('tone', 'Tone', row.tone);
        addFact('callsign', 'Callsign', callsignLink(row.callsign));
      }

      const actions = node('div', 'radioreference-result-actions');
      if (!isTrunked && row.radio_reference_url) {
        const open = externalAnchor('Open RadioReference', row.radio_reference_url);
        open.classList.add('button', 'secondary');
        actions.append(open);
      }
      const detailsButton = node('button', 'secondary', 'Load details');
      detailsButton.type = 'button';
      actions.append(detailsButton);

      const status = node('p', 'radioreference-result-status');
      let detailRequest = 0;
      status.setAttribute('aria-live', 'polite');
      const showDetails = async (button) => {
        const request = ++detailRequest;
        status.classList.remove('error');
        status.textContent = 'Loading RadioReference details…';
        button.disabled = true;
        try {
          const loaded = await loadRadioReferenceDetails(row, frequencyHz, signal);
          if (request !== detailRequest) return;
          if (isTrunked) {
            if (loaded?.site) {
              const loadedSite = siteLabel(loaded.site) || 'Unknown site';
              replaceFact('site', loaded.site.radio_reference_url ?
                externalAnchor(loadedSite, loaded.site.radio_reference_url) : loadedSite);
              replaceFact('channel-use', availableValue(loaded.site.channel_use));
              status.textContent = 'Site details loaded.';
            } else {
              replaceFact('channel-use', 'Site details unavailable');
              status.textContent = 'RadioReference did not return an exact site match.';
            }
          } else {
            replaceFact('mode', availableValue(loaded?.mode_name || row.mode_name));
            const category = [loaded?.category, loaded?.sub_category].filter(Boolean).join(' — ');
            replaceFact('category', category || 'Category details unavailable');
            status.textContent = 'Frequency details loaded.';
          }
          detailsButton.textContent = 'Details loaded';
        } catch (error) {
          if (request !== detailRequest) return;
          status.classList.add('error');
          status.textContent = error.message;
          detailsButton.textContent = 'Retry details';
          button.disabled = false;
        } finally {
          if (request === detailRequest && detailsButton.textContent !== 'Details loaded') button.disabled = false;
        }
      };
      detailsButton.addEventListener('click', () => showDetails(detailsButton));
      card.append(header, values, actions, status);
      grid.append(card);
    });
    group.append(node('h3', '', `${label} (${items.length})`), grid);
    grouped.append(group);
  };
  section('Conventional', conventional, false);
  section('Trunked systems and sites', trunked, true);
  return grouped;
}

function tunerFrequencyAction(label, detail, disabled = false) {
  const button = node('button', `tuner-frequency-action${disabled ? ' disabled-action' : ''}`);
  button.type = 'button';
  button.disabled = disabled;
  button.append(node('strong', '', label), node('small', '', detail));
  if (disabled) button.setAttribute('aria-disabled', 'true');
  return button;
}

function openTunerFrequencyActions(selection) {
  const selectedHz = Number(selection?.frequencyHz);
  const rawHz = Number(selection?.rawFrequencyHz);
  if (!Number.isFinite(selectedHz) || selectedHz <= 0) return null;
  const detailController = new AbortController();
  const body = node('div', 'tuner-frequency-action-body');
  const summary = node('dl', 'tuner-frequency-action-summary');
  const facts = [['Frequency', `${(selectedHz / 1_000_000).toFixed(6)} MHz`]];
  if (Number.isFinite(rawHz) && Math.abs(rawHz - selectedHz) >= 0.5) {
    facts.push(['Pointer', `${(rawHz / 1_000_000).toFixed(6)} MHz`]);
    if (selection.snap?.label) facts.push(['Snap raster', selection.snap.label]);
  }
  if (selection.targetLabel) facts.push(['Tuner', selection.targetLabel]);
  facts.forEach(([label, value]) => summary.append(node('dt', '', label), node('dd', '', value)));

  const actions = node('div', 'tuner-frequency-action-list');
  const radioReference = tunerFrequencyAction('RadioReference Lookup',
    'Search conventional and trunked-site records in the configured RadioReference state.');
  const listen = tunerFrequencyAction('Listen',
    'Live arbitrary-frequency browser listening is planned for a later phase.', true);
  const addSystem = tunerFrequencyAction('Add System',
    'Guided system, site, channel, alias-list, and scan-list creation is planned for a later phase.', true);
  const message = node('div', 'tuner-frequency-action-message');
  message.setAttribute('role', 'status');
  const results = node('div', 'tuner-frequency-results');
  radioReference.addEventListener('click', async () => {
    if (radioReference.disabled) return;
    radioReference.disabled = true;
    results.replaceChildren();
    message.textContent = 'Checking RadioReference account and lookup region…';
    try {
      const configuration = await requestJson('/api/v1/admin/radioreference', { csrf: false, page: false });
      if (configuration?.account?.state !== 'VALID_PREMIUM') {
        throw new Error('Connect a current RadioReference Premium account in Settings before searching.');
      }
      const stateId = Number(configuration?.state_id);
      if (!Number.isInteger(stateId) || stateId <= 0) {
        throw new Error('Choose a RadioReference country and state in Settings before searching.');
      }
      message.textContent = 'Searching RadioReference…';
      const query = new URLSearchParams({
        state_id: String(stateId), frequency_hz: String(Math.round(selectedHz)), limit: '100'
      });
      const response = await requestJson(`/api/v1/admin/radioreference/frequencies?${query}`, {
        csrf: false, page: false, timeoutMs: 15_000
      });
      const matches = Array.isArray(response?.items) ? response.items : [];
      results.replaceChildren(radioReferenceResultView(matches, selectedHz, detailController.signal));
      const total = Number(response?.total_items || matches.length);
      message.textContent = total > matches.length ?
        `Showing the first ${number(matches.length)} of ${number(total)} matches.` :
        `${number(total)} RadioReference ${total === 1 ? 'match' : 'matches'} found.`;
    } catch (error) {
      message.textContent = error.message;
      results.append(anchor('Open RadioReference settings', href('admin', { tab: 'live-activity' }),
        'button secondary'));
    } finally {
      radioReference.disabled = false;
    }
  });
  actions.append(radioReference, listen, addSystem);
  body.append(node('p', 'tuner-frequency-action-intro',
    'Choose what to do with this selected frequency.'), summary, actions, message, results);
  return openReadOnlyModal('Frequency actions', body, {
    id: 'tuner-frequency-actions', className: 'frequency-action-modal',
    cleanup: () => detailController.abort()
  });
}

function tunerStoredNumber(key, fallback, minimum, maximum) {
  try {
    const value = Number(window.localStorage.getItem(key));
    return Number.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
  } catch (error) {
    return fallback;
  }
}

function storeTunerNumber(key, value) {
  try {
    window.localStorage.setItem(key, String(value));
  } catch (error) {
    // Privacy modes can disable local storage. The control still works for the current page.
  }
}

function tunerStoredBoolean(key, fallback) {
  try {
    const value = window.localStorage.getItem(key);
    return value === null ? fallback : value === 'true';
  } catch (error) {
    return fallback;
  }
}

function storeTunerBoolean(key, value) {
  try {
    window.localStorage.setItem(key, String(Boolean(value)));
  } catch (error) {
    // Privacy modes can disable local storage. The control still works for the current page.
  }
}

function tunerStoredChoice(key, fallback, choices) {
  try {
    const value = window.localStorage.getItem(key);
    return choices.includes(value) ? value : fallback;
  } catch (error) {
    return fallback;
  }
}

function storeTunerChoice(key, value) {
  try {
    window.localStorage.setItem(key, String(value));
  } catch (error) {
    // Privacy modes can disable local storage. The control still works for the current page.
  }
}

function tunerFrameDomain(frame, valueCount = frame?.valueCount || 0) {
  const center = Number(frame?.centerFrequencyHz || 0);
  const sampleRate = Number(frame?.sampleRateHz || 0);
  const fftSize = Number(frame?.fftSize || valueCount || 0);
  const firstBin = Math.max(0, Number(frame?.firstBin || 0));
  const sourceBinCount = Math.max(0, Number(frame?.sourceBinCount || valueCount));
  const rawBinWidthHz = sampleRate > 0 && fftSize > 0 ? sampleRate / fftSize : 0;
  const fullStartHz = center - sampleRate / 2;
  const startHz = fullStartHz + firstBin * rawBinWidthHz;
  return {
    fullStartHz,
    fullEndHz: fullStartHz + sampleRate,
    startHz,
    endHz: startHz + sourceBinCount * rawBinWidthHz,
    rawBinWidthHz,
    sentBinWidthHz: valueCount > 0 ? sourceBinCount * rawBinWidthHz / valueCount : 0,
    sourceBinCount,
    transmittedBinCount: Math.max(0, Number(valueCount || 0))
  };
}

function tunerFrequencyAtBin(domain, coordinate) {
  const rawBinWidthHz = Number(domain?.rawBinWidthHz || 0);
  const sourceBinCount = Number(domain?.sourceBinCount || 0);
  const transmittedBinCount = Number(domain?.transmittedBinCount || 0);
  if (!(rawBinWidthHz > 0) || sourceBinCount < 1 || transmittedBinCount < 1) {
    return Number(domain?.startHz || 0) + coordinate * Number(domain?.sentBinWidthHz || 0);
  }
  const bounded = Math.max(0, Math.min(transmittedBinCount - 1, coordinate));
  const bin = Math.floor(bounded);
  const fraction = bounded - bin;
  const center = (index) => {
    const rawStart = Math.floor(index * sourceBinCount / transmittedBinCount);
    const rawEnd = Math.floor((index + 1) * sourceBinCount / transmittedBinCount);
    return (rawStart + rawEnd - 1) / 2;
  };
  const rawCenter = bin < transmittedBinCount - 1 ?
    center(bin) + (center(bin + 1) - center(bin)) * fraction : center(bin);
  return Number(domain.startHz) + rawCenter * rawBinWidthHz;
}

function tunerBinAtFrequency(domain, frequencyHz) {
  const count = Number(domain?.transmittedBinCount || 0);
  if (count < 1 || !Number.isFinite(Number(frequencyHz))) return 0;
  let lower = 0;
  let upper = count - 1;
  while (lower < upper) {
    const middle = Math.floor((lower + upper) / 2);
    if (tunerFrequencyAtBin(domain, middle) < frequencyHz) lower = middle + 1;
    else upper = middle;
  }
  if (lower > 0 && Math.abs(tunerFrequencyAtBin(domain, lower - 1) - frequencyHz) <=
      Math.abs(tunerFrequencyAtBin(domain, lower) - frequencyHz)) return lower - 1;
  return lower;
}

function tunerSnapFrequency(frequencyHz) {
  if (!Number.isFinite(Number(frequencyHz))) return null;
  const raster = TUNER_FREQUENCY_RASTERS.find((candidate) =>
    frequencyHz >= candidate.minHz && frequencyHz <= candidate.maxHz);
  if (!raster) return null;
  const snappedHz = raster.originHz + Math.round((frequencyHz - raster.originHz) / raster.stepHz) * raster.stepHz;
  if (snappedHz < raster.minHz || snappedHz > raster.maxHz) return null;
  return { source: 'raster', frequencyHz: snappedHz, raster, label: raster.label };
}

function tunerSpectrumPanel() {
  const layout = node('div', 'tuner-spectrum-layout');
  const toolbar = node('div', 'tuner-spectrum-toolbar');
  const targetLabel = node('label', 'tuner-spectrum-target');
  targetLabel.append(node('span', '', 'Tuner'));
  const targetSelect = node('select');
  targetSelect.disabled = true;
  targetSelect.append(node('option', '', 'Loading tuners…'));
  targetLabel.append(targetSelect);
  const status = badge('Loading', 'state-stale');
  const toolbarActions = node('div', 'tuner-spectrum-toolbar-actions');
  const zoomIn = node('button', 'button secondary', 'Zoom in');
  zoomIn.type = 'button';
  zoomIn.disabled = true;
  const zoomOut = node('button', 'button secondary', 'Zoom out');
  zoomOut.type = 'button';
  zoomOut.disabled = true;
  const resetZoom = node('button', 'button secondary', 'Reset zoom');
  resetZoom.type = 'button';
  resetZoom.disabled = true;
  const pause = node('button', 'button secondary', 'Pause');
  pause.type = 'button';
  pause.disabled = true;
  pause.setAttribute('aria-pressed', 'false');
  toolbarActions.append(zoomIn, zoomOut, resetZoom, pause);
  toolbar.append(targetLabel, status, toolbarActions);

  const displayControls = node('div', 'tuner-spectrum-display-controls');
  const options = node('details', 'tuner-spectrum-options');
  const optionsSummary = node('summary', 'button secondary tuner-spectrum-options-summary', 'Options');
  optionsSummary.setAttribute('role', 'button');
  optionsSummary.setAttribute('aria-label', 'Options');
  optionsSummary.setAttribute('aria-expanded', 'false');
  const optionsPanel = node('div', 'tuner-spectrum-options-panel');
  optionsPanel.setAttribute('role', 'group');
  optionsPanel.setAttribute('aria-label', 'Tuner spectrum options');
  let initialFloor = tunerStoredNumber(TUNER_SPECTRUM_FLOOR_STORAGE_KEY,
    TUNER_SPECTRUM_DEFAULT_FLOOR_DB, TUNER_SPECTRUM_MINIMUM_DISPLAY_DB,
    TUNER_SPECTRUM_MAXIMUM_DISPLAY_DB - TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB);
  let initialCeiling = tunerStoredNumber(TUNER_SPECTRUM_CEILING_STORAGE_KEY,
    TUNER_SPECTRUM_DEFAULT_CEILING_DB,
    TUNER_SPECTRUM_MINIMUM_DISPLAY_DB + TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB,
    TUNER_SPECTRUM_MAXIMUM_DISPLAY_DB);
  if (initialCeiling - initialFloor < TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB) {
    initialFloor = TUNER_SPECTRUM_DEFAULT_FLOOR_DB;
    initialCeiling = TUNER_SPECTRUM_DEFAULT_CEILING_DB;
  }
  const rangeControl = node('div', 'tuner-spectrum-display-control tuner-spectrum-range-control');
  const rangeHeading = node('div', 'tuner-spectrum-range-heading');
  const rangeValue = node('output', '', `${initialFloor} to ${initialCeiling} dB`);
  rangeHeading.append(node('span', '', 'Display range'), rangeValue);
  const rangeSlider = node('div', 'tuner-spectrum-dual-range');
  rangeSlider.append(node('span', 'tuner-spectrum-dual-range-track'));
  const floorInput = node('input');
  floorInput.type = 'range';
  floorInput.min = String(TUNER_SPECTRUM_MINIMUM_DISPLAY_DB);
  floorInput.max = String(TUNER_SPECTRUM_MAXIMUM_DISPLAY_DB);
  floorInput.step = '5';
  floorInput.value = String(initialFloor);
  floorInput.id = 'tuner-spectrum-floor';
  floorInput.setAttribute('aria-label', 'Lower display limit');
  const ceilingInput = node('input');
  ceilingInput.type = 'range';
  ceilingInput.min = String(TUNER_SPECTRUM_MINIMUM_DISPLAY_DB);
  ceilingInput.max = String(TUNER_SPECTRUM_MAXIMUM_DISPLAY_DB);
  ceilingInput.step = '5';
  ceilingInput.value = String(initialCeiling);
  ceilingInput.id = 'tuner-spectrum-ceiling';
  ceilingInput.setAttribute('aria-label', 'Upper display limit');
  rangeSlider.append(floorInput, ceilingInput);
  rangeControl.append(rangeHeading, rangeSlider);
  const rangeHelp = node('span', 'tuner-spectrum-control-help',
    'Move either handle to set display contrast. Receiver gain and decoder thresholds do not change.');
  const speedControl = node('label', 'tuner-spectrum-display-control');
  const speedInput = node('input');
  speedInput.type = 'range';
  speedInput.min = '0.25';
  speedInput.max = '4';
  speedInput.step = '0.25';
  speedInput.value = String(tunerStoredNumber(TUNER_WATERFALL_SPEED_STORAGE_KEY, 1, 0.25, 4));
  speedInput.id = 'tuner-waterfall-speed';
  const speedValue = node('output', '', `${Number(speedInput.value).toFixed(2)}×`);
  speedValue.htmlFor = speedInput.id;
  speedControl.append(node('span', '', 'Waterfall speed'), speedInput, speedValue);
  const snapControl = node('label', 'tuner-spectrum-toggle-control');
  const snapInput = node('input');
  snapInput.type = 'checkbox';
  snapInput.checked = tunerStoredBoolean(TUNER_SPECTRUM_SNAP_STORAGE_KEY, true);
  snapControl.title = 'Snap the cursor to the nearest preset frequency in supported bands.';
  snapControl.append(snapInput, node('span', '', 'Snap frequency'));
  const smoothControl = node('label', 'tuner-spectrum-toggle-control');
  const smoothInput = node('input');
  smoothInput.type = 'checkbox';
  smoothInput.checked = tunerStoredBoolean(TUNER_SPECTRUM_SMOOTH_STORAGE_KEY, true);
  smoothControl.title = 'Average successive frames to make the FFT trace steadier.';
  smoothControl.append(smoothInput, node('span', '', 'Smooth FFT'));
  const waterfallChannelsControl = node('label', 'tuner-spectrum-toggle-control');
  const waterfallChannelsInput = node('input');
  waterfallChannelsInput.type = 'checkbox';
  waterfallChannelsInput.checked = tunerStoredBoolean(TUNER_WATERFALL_CHANNELS_STORAGE_KEY, false);
  waterfallChannelsControl.title = 'Show known and active channel bandwidths over the waterfall.';
  waterfallChannelsControl.append(waterfallChannelsInput,
    node('span', '', 'Highlight channels on waterfall'));
  const liveActivityAllowed = capabilityAllowed(ACCESS_CAPABILITIES.LIVE);
  waterfallChannelsControl.hidden = !liveActivityAllowed;
  const toggleControls = node('div', 'tuner-spectrum-option-toggles');
  toggleControls.append(snapControl, smoothControl, waterfallChannelsControl);
  const profilePanel = node('fieldset', 'tuner-spectrum-profile');
  profilePanel.append(node('legend', '', 'Spectrum performance'));
  const profileControl = node('label', 'tuner-spectrum-display-control');
  const profileSelect = node('select');
  [
    ['efficient', 'Efficient · 2,048 bins / 5 FPS'],
    ['balanced', 'Balanced · 8,192 bins / 10 FPS'],
    ['high-detail', 'High detail · 16,384 bins / 20 FPS'],
    ['maximum-detail', 'Maximum detail · 32,768 bins / 20 FPS']
  ].forEach(([value, text]) => {
    const option = node('option', '', text);
    option.value = value;
    profileSelect.append(option);
  });
  profileSelect.value = tunerStoredChoice(TUNER_SPECTRUM_PROFILE_STORAGE_KEY, 'balanced',
    Object.keys(TUNER_SPECTRUM_PROFILES));
  profileControl.append(node('span', '', 'Profile'), profileSelect);
  const profileWarning = node('p', 'tuner-spectrum-control-help',
    'Higher-detail profiles use more CPU and may affect decoding on lower-end systems. All profiles use 8-bit spectrum data.');
  profilePanel.append(profileControl, profileWarning);
  optionsPanel.append(rangeControl, rangeHelp, speedControl, toggleControls, profilePanel);
  options.append(optionsSummary, optionsPanel);
  options.addEventListener('toggle', () => {
    optionsSummary.setAttribute('aria-expanded', String(options.open));
  });
  toolbarActions.append(options);
  const refiningBadge = node('span', 'tuner-spectrum-refining', 'Refining…');
  refiningBadge.hidden = true;
  refiningBadge.setAttribute('role', 'status');
  const flagLegend = node('div', 'tuner-spectrum-flag-legend');
  flagLegend.setAttribute('aria-label', 'Activity flag colors');
  ['CONTROL', 'CALL', 'ENCRYPTED', 'DATA', 'ACTIVE'].forEach((flagStatus) => {
    const item = node('span', 'tuner-spectrum-flag-legend-item');
    item.append(node('span', `tuner-spectrum-flag-swatch status-${flagStatus.toLowerCase()}`),
      node('span', '', TUNER_ACTIVITY_LABELS[flagStatus]));
    flagLegend.append(item);
  });
  flagLegend.hidden = !liveActivityAllowed;
  if (!liveActivityAllowed) {
    displayControls.append(node('span', 'tuner-spectrum-control-help',
      'Channel markers require Live access.'));
  }
  displayControls.append(refiningBadge, flagLegend);

  const instructions = node('p', 'visually-hidden',
    'Click a frequency for actions. Use the mouse wheel or plus and minus keys to zoom. ' +
    'Drag or use the arrow keys to pan. Press R to reset zoom.');
  instructions.id = 'tuner-spectrum-instructions';

  const plot = (title, ariaLabel, extraClass = '') => {
    const card = node('section', `tuner-spectrum-card ${extraClass}`.trim());
    const heading = node('h3', 'channel-diagnostic-title', title);
    const host = node('div', 'tuner-spectrum-plot');
    const canvas = node('canvas', 'channel-diagnostic-canvas tuner-spectrum-canvas');
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', ariaLabel);
    canvas.setAttribute('aria-describedby', instructions.id);
    canvas.setAttribute('aria-keyshortcuts', '+ - ArrowLeft ArrowRight R 0 Home');
    canvas.tabIndex = 0;
    const guide = node('div', 'tuner-spectrum-cursor-guide');
    guide.hidden = true;
    const overlay = node('div', 'channel-diagnostic-overlay', 'Select a tuner');
    host.append(canvas, guide, overlay);
    card.append(heading, host);
    return { card, host, canvas, guide, overlay };
  };
  const spectrum = plot('FFT', 'Tuner frequency spectrum', 'tuner-spectrum-fft');
  const waterfall = plot('Waterfall', 'Tuner spectrum history', 'tuner-spectrum-waterfall');
  const spectrumActiveFlags = node('div', 'tuner-spectrum-active-flags');
  const waterfallActiveFlags = node('div', 'tuner-spectrum-active-flags');
  waterfallActiveFlags.hidden = true;
  waterfallActiveFlags.setAttribute('aria-hidden', 'true');
  const activeFlagLayers = [spectrumActiveFlags, waterfallActiveFlags];
  spectrum.host.insertBefore(spectrumActiveFlags, spectrum.guide);
  waterfall.host.insertBefore(waterfallActiveFlags, waterfall.guide);
  const cursorPopup = node('div', 'tuner-spectrum-cursor-popup');
  const cursorFrequency = node('span', 'tuner-spectrum-cursor-frequency');
  const cursorSnap = node('span', 'tuner-spectrum-cursor-snap');
  const cursorPower = node('span', 'tuner-spectrum-cursor-power');
  const cursorChannel = node('div', 'tuner-spectrum-cursor-channel');
  cursorSnap.hidden = true;
  cursorChannel.hidden = true;
  cursorPopup.hidden = true;
  cursorPopup.append(cursorFrequency, cursorSnap, cursorPower, cursorChannel);
  const readouts = node('div', 'tuner-spectrum-readouts channel-diagnostic-readouts');
  layout.append(instructions, toolbar, displayControls, spectrum.card, waterfall.card, cursorPopup, readouts);

  let disposed = false;
  let paused = false;
  let stream = null;
  let streamRelease = Promise.resolve();
  let streamEpoch = 0;
  let generation = -1;
  let sequence = null;
  let droppedFrames = 0;
  let fullViewport = null;
  let viewport = null;
  let analysisViewport = null;
  let refining = false;
  let awaitingViewportState = false;
  let viewportUpdateTimer = null;
  let hoverRatio = null;
  let hoverCanvas = null;
  let hoverYRatio = null;
  let hoverFlag = null;
  let drag = null;
  let dbFloor = initialFloor;
  let dbCeiling = initialCeiling;
  let waterfallSpeed = Number(speedInput.value);
  let waterfallScrollAccumulator = 0;
  let activeChannelSource = null;
  const activeChannelTables = new Map();
  const targetsById = new Map();
  let activeFlagSignature = '';
  let fftValues = new Float32Array(0);
  let smoothedFftValues = new Float32Array(0);
  let spectrumSmoothingKey = '';
  let frameMetadata = null;
  let peak = null;
  let latencyMs = null;
  const latencyClock = { offsetMs: null };
  let frameTimes = [];
  let spectrumProfile = profileSelect.value;
  let spectrumDirty = true;
  let waterfallDirty = true;
  let drawPending = false;
  const waterfallBuffer = document.createElement('canvas');
  const spectrumScratch = document.createElement('canvas');
  const waterfallScratch = document.createElement('canvas');
  const waterfallContext = waterfallBuffer.getContext('2d', { alpha: false });
  const palette = tunerWaterfallPalette();
  let newestWaterfallRow = -1;
  let nextWaterfallRow = -1;
  let waterfallRowImage = null;
  let waterfallObservedAtRows = new Float64Array(0);
  const waterfallHistoryRows = [];
  let retainedWaterfallRows = 0;
  let readoutTimer = null;
  let lastReadoutAt = 0;

  const controller = {
    element: layout,
    close: () => {
      if (disposed) return;
      disposed = true;
      cancelDrag();
      closeStreams();
      closeActiveChannels();
      if (readoutTimer !== null) window.clearTimeout(readoutTimer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('resize', onResize);
      [spectrum.canvas, waterfall.canvas].forEach(removePlotInteractions);
    }
  };

  const setStatus = (text, className = 'state-stale') => {
    if (status.textContent !== text) status.textContent = text;
    const nextClassName = `badge ${className}`;
    if (status.className !== nextClassName) status.className = nextClassName;
  };

  const setOverlay = (message = '') => {
    [spectrum, waterfall].forEach((target) => {
      if (target.overlay.textContent !== message) target.overlay.textContent = message;
      if (target.overlay.hidden !== !message) target.overlay.hidden = !message;
    });
  };

  function formatTunerSpan(spanHz) {
    if (spanHz >= 1_000_000) return `${(spanHz / 1_000_000).toFixed(3)} MHz`;
    if (spanHz >= 1_000) return `${(spanHz / 1_000).toFixed(1)} kHz`;
    return `${Math.round(spanHz)} Hz`;
  }

  function zoomAmount() {
    if (!fullViewport || !viewport) return 1;
    return (fullViewport.endHz - fullViewport.startHz) /
      Math.max(1, viewport.endHz - viewport.startHz);
  }

  function median(values) {
    if (!values.length) return null;
    const sorted = [...values].sort((left, right) => left - right);
    const middle = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  }

  function estimatedBestVisibleSnr() {
    if (!frameMetadata || !fftValues.length || !viewport) return null;
    const values = displayedSpectrumValues();
    const domain = tunerFrameDomain(frameMetadata, values.length);
    const carriers = activeCarriers().filter((carrier) => carrier.frequencyHz >= domain.startHz &&
      carrier.frequencyHz <= domain.endHz);
    let best = null;
    carriers.forEach((carrier) => {
      const signalDb = [];
      const noiseDb = [];
      const signalHalfWidthHz = 6250;
      const noiseGuardHz = 9375;
      const noiseOuterHz = 31250;
      values.forEach((value, index) => {
        if (!Number.isFinite(value)) return;
        const frequencyHz = tunerFrequencyAtBin(domain, index);
        const offset = Math.abs(frequencyHz - carrier.frequencyHz);
        if (offset <= signalHalfWidthHz) signalDb.push(value);
        else if (offset >= noiseGuardHz && offset <= noiseOuterHz &&
            !carriers.some((other) => other !== carrier &&
              Math.abs(frequencyHz - other.frequencyHz) <= noiseGuardHz)) noiseDb.push(value);
      });
      if (signalDb.length < 2 || noiseDb.length < 4) return;
      const noiseFloorDb = median(noiseDb);
      const noisePower = 10 ** (noiseFloorDb / 10);
      const signalPower = signalDb.reduce((sum, value) => sum + 10 ** (value / 10), 0) / signalDb.length;
      const carrierPower = signalPower - noisePower;
      if (!(carrierPower > 0) || !(noisePower > 0)) return;
      const snr = 10 * Math.log10(carrierPower / noisePower);
      if (Number.isFinite(snr) && (!best || snr > best.snr)) {
        best = { snr, frequencyHz: carrier.frequencyHz };
      }
    });
    return best;
  }

  const renderReadouts = () => {
    const center = fullViewport ? (fullViewport.startHz + fullViewport.endHz) / 2 : 0;
    const sampleRate = fullViewport ? fullViewport.endHz - fullViewport.startHz : 0;
    const fftSize = Number(frameMetadata?.fftSize || fftValues.length || 0);
    const visibleSpan = viewport ? viewport.endHz - viewport.startHz : sampleRate;
    const zoom = sampleRate > 0 && visibleSpan > 0 ? sampleRate / visibleSpan : 1;
    const frameDomain = tunerFrameDomain(frameMetadata, fftValues.length);
    const analysisSpan = frameDomain.endHz > frameDomain.startHz ? frameDomain.endHz - frameDomain.startHz : 0;
    const resolution = frameDomain.sentBinWidthHz || null;
    const fps = frameTimes.length > 1 ? (frameTimes.length - 1) * 1000 /
      Math.max(1, frameTimes[frameTimes.length - 1] - frameTimes[0]) : null;
    const bestSnr = estimatedBestVisibleSnr();
    const values = [
      ['Center', center ? `${frequency(center)} MHz` : '—'],
      ['Full span', sampleRate ? formatTunerSpan(sampleRate) : '—'],
      ['Visible span', visibleSpan ? formatTunerSpan(visibleSpan) : '—'],
      ['Analysis span', analysisSpan ? formatTunerSpan(analysisSpan) : '—'],
      ['Zoom', `${zoom.toFixed(2)}×`],
      ['Sent bins', fftValues.length ? number(fftValues.length) : '—'],
      ['FFT detail', fftSize ? number(fftSize) : '—'],
      ['Displayed resolution', Number.isFinite(resolution) ? `${number(Math.round(resolution))} Hz` : '—'],
      ['Peak', Number.isFinite(peak) ? `${peak.toFixed(1)} dB` : '—'],
      ['Estimated best visible SNR', bestSnr ?
        `${bestSnr.snr.toFixed(1)} dB · ${frequency(bestSnr.frequencyHz)} MHz` : '—'],
      ['Rate', Number.isFinite(fps) ? `${fps.toFixed(1)} fps` : '—'],
      ['Dropped', number(droppedFrames)],
      ['Generation', generation >= 0 ? number(generation) : '—'],
      ['Latency', Number.isFinite(latencyMs) ? `${Math.round(latencyMs)} ms` : '—']
    ];
    updateDiagnosticReadouts(readouts, values);
    zoomIn.disabled = !shouldRun() || !fullViewport || !viewport || zoom >= TUNER_SPECTRUM_MAXIMUM_ZOOM - 0.0001;
    zoomOut.disabled = !shouldRun() || !fullViewport || !viewport || zoom <= 1.0001;
    resetZoom.disabled = !shouldRun() || !fullViewport || !viewport || zoom <= 1.0001;
    profileSelect.disabled = !shouldRun() || refining;
    layout.classList.toggle('zoomed', zoom > 1.0001);
  };

  const setReadouts = (immediate = false) => {
    if (disposed) return;
    const now = performance.now();
    const remaining = Math.max(0, 200 - (now - lastReadoutAt));
    if (immediate || remaining === 0) {
      if (readoutTimer !== null) window.clearTimeout(readoutTimer);
      readoutTimer = null;
      lastReadoutAt = now;
      renderReadouts();
      return;
    }
    if (readoutTimer !== null) return;
    readoutTimer = window.setTimeout(() => {
      readoutTimer = null;
      lastReadoutAt = performance.now();
      renderReadouts();
    }, remaining);
  };

  const prepareCanvas = (target) => {
    const bounds = target.canvas.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return null;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    const width = Math.max(1, Math.round(bounds.width * ratio));
    const height = Math.max(1, Math.round(bounds.height * ratio));
    const resized = target.canvas.width !== width || target.canvas.height !== height;
    if (resized) {
      target.canvas.width = width;
      target.canvas.height = height;
    }
    const context = target.canvas.getContext('2d', { alpha: false });
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    return { context, ratio, cssWidth: width / ratio, cssHeight: height / ratio, resized };
  };

  const drawSpectrum = () => {
    const prepared = prepareCanvas(spectrum);
    if (!prepared) return;
    const { context, cssWidth, cssHeight } = prepared;
    context.fillStyle = '#07111d';
    context.fillRect(0, 0, cssWidth, cssHeight);
    context.strokeStyle = 'rgba(150, 177, 199, 0.18)';
    context.lineWidth = 1;
    for (let line = 1; line < 6; line += 1) {
      const power = dbFloor + (dbCeiling - dbFloor) * (1 - line / 6);
      const y = cssHeight * line / 6;
      context.beginPath();
      context.moveTo(0, y);
      context.lineTo(cssWidth, y);
      context.stroke();
      context.fillStyle = 'rgba(7, 17, 29, 0.82)';
      context.fillRect(4, y - 8, 57, 16);
      context.fillStyle = '#8fa8b7';
      context.font = '10px ui-monospace, SFMono-Regular, Menlo, monospace';
      context.textBaseline = 'middle';
      context.fillText(`${Math.round(power)} dB`, 7, y);
    }
    for (let line = 1; line < 4; line += 1) {
      context.beginPath();
      context.moveTo(cssWidth * line / 4, 0);
      context.lineTo(cssWidth * line / 4, cssHeight);
      context.stroke();
    }
    const spectrumValues = visibleSpectrumValues();
    if (spectrumValues.length < 2) return;
    context.strokeStyle = '#55c7ff';
    context.lineWidth = 1.35;
    context.beginPath();
    const points = Math.max(2, Math.round(cssWidth));
    for (let x = 0; x < points; x += 1) {
      const first = Math.min(spectrumValues.length - 1, Math.floor(x * spectrumValues.length / points));
      const last = Math.min(spectrumValues.length,
        Math.max(first + 1, Math.ceil((x + 1) * spectrumValues.length / points)));
      let raw = -Infinity;
      for (let bin = first; bin < last; bin += 1) {
        if (Number.isFinite(spectrumValues[bin])) raw = Math.max(raw, spectrumValues[bin]);
      }
      const value = Number.isFinite(raw) ? Math.max(dbFloor, Math.min(dbCeiling, raw)) : dbFloor;
      const drawX = x * cssWidth / (points - 1);
      const y = (dbCeiling - value) / (dbCeiling - dbFloor) * cssHeight;
      if (x === 0) context.moveTo(drawX, y);
      else context.lineTo(drawX, y);
    }
    context.stroke();
  };

  const resetWaterfallBuffer = (width, height) => {
    waterfallBuffer.width = Math.max(1, width);
    waterfallBuffer.height = Math.max(1, height);
    waterfallContext.fillStyle = '#040b18';
    waterfallContext.fillRect(0, 0, waterfallBuffer.width, waterfallBuffer.height);
    waterfallRowImage = waterfallContext.createImageData(waterfallBuffer.width, 1);
    waterfallObservedAtRows = new Float64Array(waterfallBuffer.height);
    newestWaterfallRow = -1;
    nextWaterfallRow = waterfallBuffer.height - 1;
  };

  function visibleValuesFor(values, metadata) {
    if (!values.length || !metadata || !viewport) return values;
    const domain = tunerFrameDomain(metadata, values.length);
    const span = domain.endHz - domain.startHz;
    if (!(span > 0)) return values;
    const visibleStart = Math.max(domain.startHz, viewport.startHz);
    const visibleEnd = Math.min(domain.endHz, viewport.endHz);
    if (visibleEnd <= visibleStart) return values.subarray(0, 0);
    const first = Math.max(0, Math.min(values.length - 1,
      Math.floor((visibleStart - domain.startHz) / span * values.length)));
    const end = Math.max(first + 1, Math.min(values.length,
      Math.ceil((visibleEnd - domain.startHz) / span * values.length)));
    return values.subarray(first, end);
  }

  function waterfallMetadata(metadata) {
    return {
      centerFrequencyHz: metadata.centerFrequencyHz,
      sampleRateHz: metadata.sampleRateHz,
      fftSize: metadata.fftSize,
      firstBin: metadata.firstBin,
      sourceBinCount: metadata.sourceBinCount
    };
  }

  const renderWaterfallRow = (values, metadata, observedAtEpochMs, rowCount = 1) => {
    if (!values.length || !metadata || !viewport || !waterfallRowImage) return;
    const domain = tunerFrameDomain(metadata, values.length);
    const viewportSpan = viewport.endHz - viewport.startHz;
    const domainSpan = domain.endHz - domain.startHz;
    if (!(viewportSpan > 0) || !(domainSpan > 0)) return;
    const row = waterfallRowImage;
    for (let x = 0; x < waterfallBuffer.width; x += 1) {
      row.data[x * 4] = palette[0];
      row.data[x * 4 + 1] = palette[1];
      row.data[x * 4 + 2] = palette[2];
      row.data[x * 4 + 3] = 255;
    }
    const overlapStart = Math.max(domain.startHz, viewport.startHz);
    const overlapEnd = Math.min(domain.endHz, viewport.endHz);
    if (overlapEnd > overlapStart) {
      const firstX = Math.max(0,
        Math.floor((overlapStart - viewport.startHz) / viewportSpan * waterfallBuffer.width));
      const lastX = Math.min(waterfallBuffer.width,
        Math.ceil((overlapEnd - viewport.startHz) / viewportSpan * waterfallBuffer.width));
      for (let x = firstX; x < lastX; x += 1) {
        const pixelStartHz = viewport.startHz + x / waterfallBuffer.width * viewportSpan;
        const pixelEndHz = viewport.startHz + (x + 1) / waterfallBuffer.width * viewportSpan;
        const firstBin = Math.max(0, Math.min(values.length - 1,
          Math.floor((Math.max(pixelStartHz, domain.startHz) - domain.startHz) / domainSpan * values.length)));
        const lastBin = Math.min(values.length, Math.max(firstBin + 1,
          Math.ceil((Math.min(pixelEndHz, domain.endHz) - domain.startHz) / domainSpan * values.length)));
        let raw = -Infinity;
        for (let bin = firstBin; bin < lastBin; bin += 1) {
          if (Number.isFinite(values[bin])) raw = Math.max(raw, values[bin]);
        }
        const value = Number.isFinite(raw) ? Math.max(dbFloor, Math.min(dbCeiling, raw)) : dbFloor;
        const color = Math.max(0, Math.min(255,
          Math.round((value - dbFloor) / (dbCeiling - dbFloor) * 255)));
        row.data[x * 4] = palette[color * 4];
        row.data[x * 4 + 1] = palette[color * 4 + 1];
        row.data[x * 4 + 2] = palette[color * 4 + 2];
        row.data[x * 4 + 3] = 255;
      }
    }
    for (let count = 0; count < rowCount; count += 1) {
      waterfallContext.putImageData(row, 0, nextWaterfallRow);
      waterfallObservedAtRows[nextWaterfallRow] = observedAtEpochMs;
      newestWaterfallRow = nextWaterfallRow;
      nextWaterfallRow = (nextWaterfallRow - 1 + waterfallBuffer.height) % waterfallBuffer.height;
    }
  };

  const restoreWaterfallHistory = () => {
    const bounds = waterfall.canvas.getBoundingClientRect();
    const width = Math.max(1, Math.round(bounds.width));
    const height = Math.max(1, Math.round(bounds.height));
    resetWaterfallBuffer(width, height);
    waterfallHistoryRows.forEach((row) =>
      renderWaterfallRow(row.values, row.metadata, row.observedAtEpochMs, row.repeat));
  };

  const ensureWaterfallBuffer = () => {
    const bounds = waterfall.canvas.getBoundingClientRect();
    const width = Math.max(1, Math.round(bounds.width));
    const height = Math.max(1, Math.round(bounds.height));
    if (waterfallBuffer.width !== width || waterfallBuffer.height !== height) restoreWaterfallHistory();
    return { width, height };
  };

  const addWaterfallFrame = () => {
    if (!fftValues.length || !frameMetadata) return;
    const size = ensureWaterfallBuffer();
    waterfallScrollAccumulator += waterfallSpeed;
    const rowCount = Math.min(size.height, Math.floor(waterfallScrollAccumulator));
    if (rowCount < 1) return;
    waterfallScrollAccumulator -= rowCount;
    const observedAtEpochMs = Number(frameMetadata?.observedAtEpochMs || 0);
    const cached = { values: fftValues.slice(), metadata: waterfallMetadata(frameMetadata),
      observedAtEpochMs, repeat: rowCount };
    waterfallHistoryRows.push(cached);
    retainedWaterfallRows += rowCount;
    while (retainedWaterfallRows > TUNER_WATERFALL_HISTORY_ROWS && waterfallHistoryRows.length) {
      retainedWaterfallRows -= waterfallHistoryRows.shift().repeat;
    }
    renderWaterfallRow(cached.values, cached.metadata, observedAtEpochMs, rowCount);
  };

  const drawWaterfall = () => {
    const prepared = prepareCanvas(waterfall);
    if (!prepared) return;
    const { width: ringWidth, height: ringHeight } = ensureWaterfallBuffer();
    const context = prepared.context;
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.fillStyle = '#040b18';
    context.fillRect(0, 0, waterfall.canvas.width, waterfall.canvas.height);
    if (newestWaterfallRow < 0) return;
    const firstRows = ringHeight - newestWaterfallRow;
    const firstHeight = firstRows / ringHeight * waterfall.canvas.height;
    context.drawImage(waterfallBuffer, 0, newestWaterfallRow, ringWidth, firstRows,
      0, 0, waterfall.canvas.width, firstHeight);
    if (newestWaterfallRow > 0) {
      context.drawImage(waterfallBuffer, 0, 0, ringWidth, newestWaterfallRow,
        0, firstHeight, waterfall.canvas.width, waterfall.canvas.height - firstHeight);
    }
  };

  const draw = () => {
    drawPending = false;
    if (refining) return;
    if (spectrumDirty) {
      spectrumDirty = false;
      drawSpectrum();
    }
    if (waterfallDirty) {
      waterfallDirty = false;
      drawWaterfall();
    }
  };

  const scheduleDraw = (kind = 'both') => {
    if (kind === 'spectrum' || kind === 'both') spectrumDirty = true;
    if (kind === 'waterfall' || kind === 'both') waterfallDirty = true;
    if (drawPending) return;
    drawPending = true;
    window.requestAnimationFrame(draw);
  };

  const resetPlots = (message) => {
    generation = -1;
    sequence = null;
    droppedFrames = 0;
    fftValues = new Float32Array(0);
    clearSpectrumSmoothing();
    frameMetadata = null;
    peak = null;
    latencyMs = null;
    latencyClock.offsetMs = null;
    frameTimes = [];
    waterfallScrollAccumulator = 0;
    hoverFlag = null;
    waterfallHistoryRows.length = 0;
    retainedWaterfallRows = 0;
    resetWaterfallBuffer(1, 1);
    setRefining(false);
    setOverlay(message);
    setReadouts(true);
    renderActiveChannels();
    scheduleDraw();
  };

  function setRefining(value) {
    refining = value;
    refiningBadge.hidden = !value;
    layout.classList.toggle('refining', value);
    if (value) setStatus('Refining');
  }

  function releaseConnection(connection) {
    if (!connection) return Promise.resolve();
    const closed = connection.close();
    liveConnections.delete(connection);
    pageConnections.delete(connection);
    return connection.whenClosed?.() || (closed instanceof Promise ? closed : Promise.resolve());
  }

  function closeStreams() {
    streamEpoch += 1;
    if (viewportUpdateTimer !== null) window.clearTimeout(viewportUpdateTimer);
    viewportUpdateTimer = null;
    awaitingViewportState = false;
    const active = stream;
    stream = null;
    streamRelease = Promise.all([streamRelease, releaseConnection(active)]).then(() => {});
    return streamRelease;
  }

  const selectedTargetId = () => targetSelect.value;
  const shouldRun = () => !disposed && !paused && !document.hidden && selectedTargetId();

  function diagnosticParameters() {
    const parameters = {
      target_id: selectedTargetId(),
      profile: spectrumProfile
    };
    if (fullViewport && viewport && zoomAmount() > 1.0001) {
      parameters.viewport_start_hz = Math.round(viewport.startHz);
      parameters.viewport_end_hz = Math.round(viewport.endHz);
    }
    return parameters;
  }

  function stateNumber(state, ...keys) {
    for (const key of keys) {
      const value = state?.[key];
      if (value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value))) {
        return Number(value);
      }
    }
    return null;
  }

  function requestedViewport() {
    return fullViewport && viewport && zoomAmount() > 1.0001 ? {
      startHz: Math.round(viewport.startHz), endHz: Math.round(viewport.endHz)
    } : null;
  }

  function stateViewport(state, prefix) {
    const start = stateNumber(state, `${prefix}_start_frequency_hz`, `${prefix}_start_hz`);
    const end = stateNumber(state, `${prefix}_end_frequency_hz`, `${prefix}_end_hz`);
    return start !== null && end !== null && end > start ? { startHz: start, endHz: end } : null;
  }

  function sameViewport(left, right, toleranceHz = 1) {
    return !!left && !!right && Math.abs(left.startHz - right.startHz) <= toleranceHz &&
      Math.abs(left.endHz - right.endHz) <= toleranceHz;
  }

  function stateMatchesRequest(state) {
    const desired = requestedViewport();
    const requested = stateViewport(state, 'requested');
    const viewportMatches = desired ? sameViewport(desired, requested) : !requested;
    return viewportMatches && String(state?.profile || '') === spectrumProfile;
  }

  function acceptTunerState(frame) {
    const tunerState = diagnosticJsonPayload(frame);
    const streamState = String(tunerState?.stream_state ?? tunerState?.state ??
      (tunerState?.bound ? 'live' : 'waiting')).toLowerCase();
    const live = streamState === 'live' || streamState === 'active';
    const unavailable = streamState === 'unavailable' || streamState === 'closed';
    const center = stateNumber(tunerState, 'center_frequency_hz');
    const sampleRate = stateNumber(tunerState, 'sample_rate_hz');
    if (center > 0 && sampleRate > 0) {
      const nextFull = { startHz: center - sampleRate / 2, endHz: center + sampleRate / 2 };
      const changed = fullViewport && !sameViewport(fullViewport, nextFull);
      fullViewport = nextFull;
      if (!viewport || changed) viewport = { ...nextFull };
      if (changed) {
        analysisViewport = null;
        waterfallHistoryRows.length = 0;
        retainedWaterfallRows = 0;
        resetWaterfallBuffer(1, 1);
        clearSpectrumSmoothing();
        if (shouldRun()) queueViewportUpdate(true);
      }
    }
    if (awaitingViewportState && !stateMatchesRequest(tunerState)) return;
    awaitingViewportState = false;
    const acceptedProfile = String(tunerState?.profile || '');
    if (Object.hasOwn(TUNER_SPECTRUM_PROFILES, acceptedProfile)) {
      spectrumProfile = acceptedProfile;
      profileSelect.value = acceptedProfile;
    }
    analysisViewport = stateViewport(tunerState, 'visible') || requestedViewport() ||
      (fullViewport ? { ...fullViewport } : null);
    setOverlay(live ? '' : (tunerState?.reason || tunerState?.message || 'Waiting for tuner samples…'));
    setStatus(live ? (refining ? 'Refining' : 'Live') : (unavailable ? 'Unavailable' : 'Waiting'),
      live && !refining ? 'state-current' : 'state-stale');
    setReadouts(true);
  }

  function clearSpectrumSmoothing() {
    smoothedFftValues = new Float32Array(0);
    spectrumSmoothingKey = '';
  }

  function displayedSpectrumValues() {
    return smoothInput.checked && smoothedFftValues.length === fftValues.length ?
      smoothedFftValues : fftValues;
  }

  function visibleSpectrumValues(useSmoothing = true) {
    const values = useSmoothing ? displayedSpectrumValues() : fftValues;
    return visibleValuesFor(values, frameMetadata);
  }

  function updateSpectrumPeak() {
    peak = null;
    const values = visibleSpectrumValues();
    for (let index = 0; index < values.length; index += 1) {
      if (Number.isFinite(values[index]) && (!Number.isFinite(peak) || values[index] > peak)) {
        peak = values[index];
      }
    }
  }

  function updateSpectrumSmoothing(values, frame, domain) {
    if (!smoothInput.checked) return;
    const key = [frame.generation, domain.startHz, domain.endHz, domain.rawBinWidthHz,
      domain.sourceBinCount, domain.transmittedBinCount].join(':');
    if (key !== spectrumSmoothingKey || smoothedFftValues.length !== values.length) {
      smoothedFftValues = values.slice();
      spectrumSmoothingKey = key;
      return;
    }
    for (let index = 0; index < values.length; index += 1) {
      const current = values[index];
      const previous = smoothedFftValues[index];
      smoothedFftValues[index] = Number.isFinite(current) && Number.isFinite(previous) ?
        previous + TUNER_SPECTRUM_SMOOTHING_ALPHA * (current - previous) : current;
    }
  }

  function acceptTunerFrame(frame) {
    if (frame.type !== DIAGNOSTIC_FRAME_TYPES.TUNER_FFT ||
        drag?.moved ||
        awaitingViewportState ||
        (generation === frame.generation && sequence !== null && frame.sequence <= sequence)) return;
    const values = diagnosticFloatPayload(frame);
    if (!values.length) return;
    const domain = tunerFrameDomain(frame, values.length);
    const nextAnalysis = { startHz: domain.startHz, endHz: domain.endHz };
    const tolerance = Math.max(1, domain.sentBinWidthHz * 1.5);
    if ((analysisViewport && !sameViewport(analysisViewport, nextAnalysis, tolerance)) ||
        (viewport && (domain.startHz > viewport.startHz + tolerance ||
          domain.endHz < viewport.endHz - tolerance))) return;
    const previousDomain = frameMetadata ? tunerFrameDomain(frameMetadata, fftValues.length) : null;
    const analysisChanged = !previousDomain || !sameViewport(
      { startHz: previousDomain.startHz, endHz: previousDomain.endHz }, nextAnalysis, tolerance) ||
      previousDomain.transmittedBinCount !== domain.transmittedBinCount;
    const generationChanged = generation >= 0 && generation !== frame.generation;
    if (generationChanged || analysisChanged) {
      droppedFrames = 0;
      hoverFlag = null;
      clearSpectrumSmoothing();
    } else if (sequence !== null && frame.sequence > sequence + 1) {
      droppedFrames += frame.sequence - sequence - 1;
    }
    generation = frame.generation;
    sequence = frame.sequence;
    fftValues = values;
    updateSpectrumSmoothing(values, frame, domain);
    frameMetadata = frame;
    if (analysisChanged) {
      restoreWaterfallHistory();
    }
    analysisViewport = nextAnalysis;
    updateSpectrumPeak();
    const now = performance.now();
    frameTimes.push(now);
    while (frameTimes.length > 2 && frameTimes[0] < now - 1000) frameTimes.shift();
    latencyMs = diagnosticFrameLatency(frame, latencyClock);
    setRefining(false);
    setOverlay('');
    setStatus('Live', 'state-current');
    addWaterfallFrame();
    setReadouts();
    if (analysisChanged) renderActiveChannels();
    if (hoverRatio !== null) updateCursor(hoverRatio);
    scheduleDraw();
  }

  function openDiagnosticStream() {
    if (!shouldRun()) return;
    const epoch = ++streamEpoch;
    let candidate = null;
    setStatus(refining ? 'Refining' : 'Connecting');
    if (!stream && !refining) setOverlay('Waiting for tuner data…');
    candidate = binaryFrameConnection('tuner_diagnostics', diagnosticParameters(), {
      onOpen: () => {
        if (disposed || candidate !== stream || epoch !== streamEpoch) return;
        sequence = null;
        clearSpectrumSmoothing();
        setStatus(refining ? 'Refining' : 'Connected', refining ? 'state-stale' : 'state-current');
      },
      onFrame: (frame) => {
        if (disposed || candidate !== stream || epoch !== streamEpoch ||
            frame.type === DIAGNOSTIC_FRAME_TYPES.HEARTBEAT) return;
        if (frame.type === DIAGNOSTIC_FRAME_TYPES.STATE) acceptTunerState(frame);
        else acceptTunerFrame(frame);
      },
      onError: (error) => {
        if (disposed || epoch !== streamEpoch || candidate !== stream) return;
        setStatus(error?.status === 429 ? 'Busy' : 'Reconnecting');
        setOverlay(error?.status === 429 ? 'Tuner spectrum viewer capacity is currently in use.' :
          'Connection interrupted. Reconnecting…');
      }
    });
    stream = candidate;
  }

  function queueViewportUpdate(immediate = false) {
    awaitingViewportState = true;
    setRefining(true);
    if (viewportUpdateTimer !== null) window.clearTimeout(viewportUpdateTimer);
    viewportUpdateTimer = null;
    if (disposed || !shouldRun()) {
      awaitingViewportState = false;
      setRefining(false);
      return;
    }
    viewportUpdateTimer = window.setTimeout(() => {
      viewportUpdateTimer = null;
      if (disposed || !shouldRun()) {
        awaitingViewportState = false;
        setRefining(false);
      } else if (stream) {
        restoreWaterfallHistory();
        drawWaterfall();
        if (!stream.update(diagnosticParameters())) {
          awaitingViewportState = false;
          setRefining(false);
        }
      } else openDiagnosticStream();
    }, immediate ? 0 : TUNER_SPECTRUM_VIEWPORT_DEBOUNCE_MS);
  }

  function sync() {
    if (!shouldRun()) {
      closeStreams();
      closeActiveChannels();
      if (disposed) return;
      setRefining(false);
      if (paused) setStatus('Paused');
      else if (document.hidden) setStatus('Hidden');
      else setStatus('Waiting');
      return;
    }
    connectActiveChannels();
    //A drag owns the client-side viewport until pointer release.  Reopening here would allow incoming frames to
    //replace that viewport and discard part of the user's pan before the refined request is sent.
    if (!stream && !drag) openDiagnosticStream();
  }

  function transformCanvas(canvas, scratch, fromViewport, toViewport) {
    if (!canvas.width || !canvas.height || !fromViewport || !toViewport) return;
    if (scratch.width !== canvas.width || scratch.height !== canvas.height) {
      scratch.width = canvas.width;
      scratch.height = canvas.height;
    }
    const scratchContext = scratch.getContext('2d', { alpha: false });
    scratchContext.setTransform(1, 0, 0, 1, 0, 0);
    scratchContext.drawImage(canvas, 0, 0);
    const context = canvas.getContext('2d', { alpha: false });
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.fillStyle = '#07111d';
    context.fillRect(0, 0, canvas.width, canvas.height);
    const overlapStart = Math.max(fromViewport.startHz, toViewport.startHz);
    const overlapEnd = Math.min(fromViewport.endHz, toViewport.endHz);
    if (overlapEnd <= overlapStart) return;
    const fromSpan = fromViewport.endHz - fromViewport.startHz;
    const toSpan = toViewport.endHz - toViewport.startHz;
    const sourceX = (overlapStart - fromViewport.startHz) / fromSpan * canvas.width;
    const sourceWidth = (overlapEnd - overlapStart) / fromSpan * canvas.width;
    const destinationX = (overlapStart - toViewport.startHz) / toSpan * canvas.width;
    const destinationWidth = (overlapEnd - overlapStart) / toSpan * canvas.width;
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    context.drawImage(scratch, sourceX, 0, sourceWidth, canvas.height,
      destinationX, 0, destinationWidth, canvas.height);
  }

  function transformPlots(fromViewport, toViewport) {
    transformCanvas(spectrum.canvas, spectrumScratch, fromViewport, toViewport);
    transformCanvas(waterfall.canvas, waterfallScratch, fromViewport, toViewport);
    transformCanvas(waterfallBuffer, waterfallScratch, fromViewport, toViewport);
  }

  function clampViewport(startHz, endHz) {
    if (!fullViewport) return null;
    const fullSpan = fullViewport.endHz - fullViewport.startHz;
    const span = Math.max(fullSpan / TUNER_SPECTRUM_MAXIMUM_ZOOM,
      Math.min(fullSpan, endHz - startHz));
    let start = startHz;
    let end = start + span;
    if (start < fullViewport.startHz) {
      start = fullViewport.startHz;
      end = start + span;
    }
    if (end > fullViewport.endHz) {
      end = fullViewport.endHz;
      start = end - span;
    }
    return { startHz: start, endHz: end };
  }

  function applyViewport(nextViewport, requestMode = 'debounced') {
    if (!viewport || !nextViewport || nextViewport.endHz <= nextViewport.startHz) return;
    if (Math.abs(nextViewport.startHz - viewport.startHz) < 0.5 &&
        Math.abs(nextViewport.endHz - viewport.endHz) < 0.5) return;
    const previous = viewport;
    transformPlots(previous, nextViewport);
    viewport = nextViewport;
    setRefining(true);
    setReadouts(true);
    renderActiveChannels();
    if (hoverRatio !== null) updateCursor(hoverRatio);
    if (requestMode !== 'none') queueViewportUpdate(requestMode === 'immediate');
  }

  function zoomAt(anchor, factor) {
    if (!fullViewport || !viewport) return;
    const fullSpan = fullViewport.endHz - fullViewport.startHz;
    const oldSpan = viewport.endHz - viewport.startHz;
    const newSpan = Math.max(fullSpan / TUNER_SPECTRUM_MAXIMUM_ZOOM,
      Math.min(fullSpan, oldSpan * factor));
    const frequencyHz = viewport.startHz + anchor * oldSpan;
    applyViewport(clampViewport(frequencyHz - anchor * newSpan,
      frequencyHz + (1 - anchor) * newSpan));
  }

  function panBy(deltaHz, requestMode = 'immediate') {
    if (!viewport || zoomAmount() <= 1.0001) return;
    applyViewport(clampViewport(viewport.startHz + deltaHz, viewport.endHz + deltaHz), requestMode);
  }

  function resetViewport() {
    if (!shouldRun() || !fullViewport || zoomAmount() <= 1.0001) return;
    applyViewport({ ...fullViewport }, 'immediate');
  }

  function canInteract() {
    return shouldRun() && !!fullViewport && !!viewport && !spectrum.overlay.textContent;
  }

  function frequencySelectionAtPointer(event) {
    if (!viewport) return null;
    const rect = event.currentTarget.getBoundingClientRect();
    if (!(rect.width > 0)) return null;
    const ratio = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
    const rawFrequencyHz = viewport.startHz + ratio * (viewport.endHz - viewport.startHz);
    const snap = snapInput.checked ? tunerSnapFrequency(rawFrequencyHz) : null;
    const target = targetsById.get(selectedTargetId());
    return Object.freeze({
      targetId: selectedTargetId(),
      targetLabel: String(target?.label || ''),
      rawFrequencyHz,
      frequencyHz: snap?.frequencyHz ?? rawFrequencyHz,
      snap,
      canvas: event.currentTarget === waterfall.canvas ? 'waterfall' : 'spectrum'
    });
  }

  function frequencySelectionForCarrier(carrier) {
    const target = targetsById.get(selectedTargetId());
    return Object.freeze({
      targetId: selectedTargetId(),
      targetLabel: String(target?.label || ''),
      rawFrequencyHz: carrier.frequencyHz,
      frequencyHz: carrier.frequencyHz,
      snap: null,
      canvas: 'active-carrier',
      activeCarrier: carrier
    });
  }

  function openFrequencyActionsAtPointer(event) {
    const selection = frequencySelectionAtPointer(event);
    if (selection) openTunerFrequencyActions(selection);
  }

  function waterfallObservedAt(yRatio) {
    if (newestWaterfallRow < 0 || !waterfallObservedAtRows.length) return 0;
    const displayRow = Math.max(0, Math.min(waterfallObservedAtRows.length - 1,
      Math.floor(yRatio * waterfallObservedAtRows.length)));
    return waterfallObservedAtRows[(newestWaterfallRow + displayRow) % waterfallObservedAtRows.length];
  }

  function setCursorGuide(frequencyHz) {
    const spanHz = viewport.endHz - viewport.startHz;
    const ratio = Math.max(0, Math.min(1, (frequencyHz - viewport.startHz) / spanHz));
    const left = `${(ratio * 100).toFixed(3)}%`;
    spectrum.guide.style.left = left;
    waterfall.guide.style.left = left;
  }

  function updateCursor(ratio) {
    if (!viewport) return hideCursor();
    const spanHz = viewport.endHz - viewport.startHz;
    const pointerHz = viewport.startHz + ratio * spanHz;
    const viewingHistory = hoverCanvas === waterfall.canvas;
    const snap = snapInput.checked ? tunerSnapFrequency(pointerHz) : null;
    const displayHz = snap?.frequencyHz ?? pointerHz;
    setCursorGuide(displayHz);

    cursorFrequency.textContent = `${(displayHz / 1_000_000).toFixed(6)} MHz`;
    cursorSnap.hidden = true;
    cursorSnap.textContent = '';

    if (viewingHistory) {
      const observedAtEpochMs = waterfallObservedAt(hoverYRatio);
      if (observedAtEpochMs > 0) {
        cursorPower.textContent = `History · ${new Date(observedAtEpochMs).toLocaleTimeString([], {
          hour: 'numeric', minute: '2-digit', second: '2-digit'
        })}`;
      } else {
        cursorPower.textContent = 'Historical row';
      }
    } else if (refining || !fftValues.length) {
      cursorPower.textContent = refining ? 'Refining…' : '—';
    } else {
      const index = tunerBinAtFrequency(tunerFrameDomain(frameMetadata, fftValues.length), displayHz);
      const value = displayedSpectrumValues()[index];
      cursorPower.textContent = Number.isFinite(value) ? `${value.toFixed(1)} dB` : '—';
    }
    cursorChannel.hidden = true;
    cursorChannel.textContent = '';
    cursorPopup.hidden = false;
    positionCursorPopup();
  }

  function showCursor(ratio, canvas, yRatio) {
    hoverFlag = null;
    hoverRatio = ratio;
    hoverCanvas = canvas;
    hoverYRatio = yRatio;
    spectrum.guide.hidden = false;
    waterfall.guide.hidden = false;
    updateCursor(ratio);
  }

  function positionCursorPopup(anchor = null) {
    if (cursorPopup.hidden) return;
    const layoutRect = layout.getBoundingClientRect();
    let pointerX;
    let pointerY;
    if (anchor) {
      const anchorRect = anchor.getBoundingClientRect();
      pointerX = anchorRect.left - layoutRect.left + anchorRect.width / 2;
      pointerY = anchorRect.bottom - layoutRect.top;
    } else {
      if (!hoverCanvas || hoverRatio === null || hoverYRatio === null) return;
      const canvasRect = hoverCanvas.getBoundingClientRect();
      pointerX = canvasRect.left - layoutRect.left + hoverRatio * canvasRect.width;
      pointerY = canvasRect.top - layoutRect.top + hoverYRatio * canvasRect.height;
    }
    const maximumLeft = Math.max(6, layoutRect.width - cursorPopup.offsetWidth - 6);
    cursorPopup.style.left = `${Math.max(6, Math.min(maximumLeft,
      pointerX - cursorPopup.offsetWidth / 2))}px`;
    const preferredTop = anchor ? pointerY + 7 : pointerY - cursorPopup.offsetHeight - 10;
    const maximumTop = Math.max(6, layoutRect.height - cursorPopup.offsetHeight - 6);
    cursorPopup.style.top = `${Math.max(6, Math.min(maximumTop, preferredTop))}px`;
  }

  function hideCursor() {
    hoverFlag = null;
    hoverRatio = null;
    hoverCanvas = null;
    hoverYRatio = null;
    spectrum.guide.hidden = true;
    waterfall.guide.hidden = true;
    cursorPopup.hidden = true;
  }

  function cancelDrag(releaseCapture = true) {
    const current = drag;
    drag = null;
    if (!current) return;
    current.canvas.classList.remove('dragging');
    if (releaseCapture && current.canvas.hasPointerCapture(current.pointerId)) {
      try { current.canvas.releasePointerCapture(current.pointerId); } catch (error) { /* Already released. */ }
    }
  }

  function onPlotWheel(event) {
    if (!canInteract()) return;
    event.preventDefault();
    const rect = event.currentTarget.getBoundingClientRect();
    const anchor = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
    zoomAt(anchor, event.deltaY < 0 ? 1 / TUNER_SPECTRUM_ZOOM_FACTOR : TUNER_SPECTRUM_ZOOM_FACTOR);
  }

  function onPlotKeyDown(event) {
    if (!canInteract()) return;
    if (event.key === '+' || event.key === '=') {
      event.preventDefault();
      zoomAt(0.5, 1 / TUNER_SPECTRUM_ZOOM_FACTOR);
    } else if (event.key === '-' || event.key === '_') {
      event.preventDefault();
      zoomAt(0.5, TUNER_SPECTRUM_ZOOM_FACTOR);
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      panBy((event.key === 'ArrowLeft' ? -1 : 1) * (viewport.endHz - viewport.startHz) * 0.1);
    } else if (event.key === 'r' || event.key === 'R' || event.key === '0' || event.key === 'Home') {
      event.preventDefault();
      resetViewport();
    }
  }

  function onPlotPointerMove(event) {
    waterfallActiveFlags.hidden = event.currentTarget !== waterfall.canvas;
    const rect = event.currentTarget.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
    const yRatio = Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height));
    showCursor(ratio, event.currentTarget, yRatio);
    if (!drag || drag.pointerId !== event.pointerId || drag.canvas !== event.currentTarget) return;
    const deltaPixels = event.clientX - drag.lastX;
    drag.lastX = event.clientX;
    if (!deltaPixels) return;
    if (!drag.moved) {
      drag.moved = true;
      //Keep the server session/producer attached and freeze only local frame application until pointer release.
    }
    panBy(-deltaPixels / rect.width * (viewport.endHz - viewport.startHz), 'none');
  }

  function onPlotPointerDown(event) {
    if (!canInteract() || zoomAmount() <= 1.0001 || event.button !== 0) return;
    event.preventDefault();
    cancelDrag();
    event.currentTarget.setPointerCapture(event.pointerId);
    event.currentTarget.classList.add('dragging');
    drag = { pointerId: event.pointerId, lastX: event.clientX, canvas: event.currentTarget, moved: false };
  }

  function onPlotPointerUp(event) {
    if (!drag || drag.pointerId !== event.pointerId) return;
    const moved = drag.moved;
    cancelDrag();
    if (moved) queueViewportUpdate();
    else openFrequencyActionsAtPointer(event);
  }

  function onPlotPointerCancel(event) {
    if (!drag || drag.pointerId !== event.pointerId) return;
    const moved = drag.moved;
    cancelDrag();
    if (moved) queueViewportUpdate();
  }

  function onPlotClick(event) {
    if (!canInteract() || zoomAmount() > 1.0001) return;
    openFrequencyActionsAtPointer(event);
  }

  function onPlotPointerLeave(event) {
    if (event.currentTarget === waterfall.canvas) waterfallActiveFlags.hidden = true;
    if (!drag || drag.pointerId !== event.pointerId) hideCursor();
  }

  function onPlotLostCapture(event) {
    if (!drag || drag.pointerId !== event.pointerId) return;
    const moved = drag.moved;
    cancelDrag(false);
    if (moved) queueViewportUpdate();
  }

  function addPlotInteractions(canvas) {
    canvas.addEventListener('wheel', onPlotWheel, { passive: false });
    canvas.addEventListener('keydown', onPlotKeyDown);
    canvas.addEventListener('pointerenter', onPlotPointerMove);
    canvas.addEventListener('pointermove', onPlotPointerMove);
    canvas.addEventListener('pointerdown', onPlotPointerDown);
    canvas.addEventListener('pointerup', onPlotPointerUp);
    canvas.addEventListener('pointercancel', onPlotPointerCancel);
    canvas.addEventListener('pointerleave', onPlotPointerLeave);
    canvas.addEventListener('lostpointercapture', onPlotLostCapture);
    canvas.addEventListener('click', onPlotClick);
  }

  function removePlotInteractions(canvas) {
    canvas.removeEventListener('wheel', onPlotWheel);
    canvas.removeEventListener('keydown', onPlotKeyDown);
    canvas.removeEventListener('pointerenter', onPlotPointerMove);
    canvas.removeEventListener('pointermove', onPlotPointerMove);
    canvas.removeEventListener('pointerdown', onPlotPointerDown);
    canvas.removeEventListener('pointerup', onPlotPointerUp);
    canvas.removeEventListener('pointercancel', onPlotPointerCancel);
    canvas.removeEventListener('pointerleave', onPlotPointerLeave);
    canvas.removeEventListener('lostpointercapture', onPlotLostCapture);
    canvas.removeEventListener('click', onPlotClick);
  }

  function connectActiveChannels() {
    if (!liveActivityAllowed || !shouldRun() || activeChannelSource) return;
    const source = subscribeLiveChannelActivity({
      snapshot: (snapshot) => {
        activeChannelTables.clear();
        (Array.isArray(snapshot?.tables) ? snapshot.tables : []).forEach((table) => {
          updateSpectrumActivityTable(table);
        });
        renderActiveChannels();
      },
      activityTable: (update) => {
        const id = String(update?.table_id || update?.table?.table_id || '');
        if (!id) return;
        if (update.operation === 'remove') activeChannelTables.delete(id);
        else if (update.table) updateSpectrumActivityTable(update.table);
        renderActiveChannels();
      }
    });
    activeChannelSource = source;
  }

  function closeActiveChannels() {
    if (activeChannelSource) {
      activeChannelSource.close();
      activeChannelSource = null;
    }
    activeChannelTables.clear();
    activeFlagSignature = '';
    activeFlagLayers.forEach((layer) => layer.replaceChildren());
  }

  function tunerActivityStatus(row) {
    const status = String(row?.status || '').toUpperCase();
    const tags = channelTagSet(row?.tags);
    if (tags.has('CURRENT_CONTROL')) return 'CONTROL';
    if (tags.has('ALTERNATE_CONTROL') || status === 'IDLE') return null;
    return TUNER_ACTIVITY_PRIORITY[status] ? status : null;
  }

  function updateSpectrumActivityTable(table) {
    const id = String(table?.table_id || '');
    if (!id) return;
    const rows = (Array.isArray(table?.rows) ? table.rows : []).filter(tunerActivityStatus);
    if (rows.length) activeChannelTables.set(id, { ...table, rows });
    else activeChannelTables.delete(id);
  }

  function activeCarriers() {
    if (!viewport) return [];
    const byFrequency = new Map();
    activeChannelTables.forEach((table) => {
      const tableChannelName = String(table?.channel_name || '').trim();
      const tableSystemName = String(table?.system_name || '').trim();
      const tableSiteName = String(table?.site_name || '').trim();
      const tableIdentifiers = Array.isArray(table?.identifiers) ? table.identifiers : [];
      (Array.isArray(table?.rows) ? table.rows : []).forEach((row) => {
        const frequencyHz = Number(row?.frequency_hz);
        const status = tunerActivityStatus(row);
        if (!Number.isFinite(frequencyHz) || frequencyHz < viewport.startHz ||
            frequencyHz > viewport.endHz || !status) return;
        const decorated = { ...row, status, frequencyHz, tableChannelName, tableSystemName, tableSiteName,
          tableIdentifiers };
        let carrier = byFrequency.get(frequencyHz);
        if (!carrier) {
          carrier = { frequencyHz, status, rows: [] };
          byFrequency.set(frequencyHz, carrier);
        } else if (TUNER_ACTIVITY_PRIORITY[status] > TUNER_ACTIVITY_PRIORITY[carrier.status]) {
          carrier.status = status;
        }
        carrier.rows.push(decorated);
      });
    });
    return [...byFrequency.values()].sort((left, right) => left.frequencyHz - right.frequencyHz);
  }

  function activityValues(rows, selector) {
    return [...new Set(rows.map(selector).flat().map((value) => String(value ?? '').trim()).filter(Boolean))];
  }

  function activityTokenLabel(value) {
    return String(value || '').toLowerCase().split('_').filter(Boolean)
      .map((word) => `${word.charAt(0).toUpperCase()}${word.slice(1)}`).join(' ');
  }

  function targetIdentifierLabel(form) {
    switch (String(form || '').toUpperCase()) {
      case 'TALKGROUP': return 'TGID';
      case 'PATCH_GROUP': return 'Patch group';
      case 'RADIO': return 'Target radio';
      case 'TELEPHONE_NUMBER': return 'Telephone';
      default: return 'Target';
    }
  }

  function activityAliasLabel(row, prefix) {
    const name = String(row?.[`${prefix}_alias`] || '').trim();
    const description = String(row?.[`${prefix}_alias_description`] || '').trim();
    if (description && name && description !== name) return `${description} (${name})`;
    return description || name;
  }

  function activeCarrierFields(carrier, fftPower = null) {
    const rows = carrier?.rows || [];
    const fields = [];
    const add = (label, values) => {
      const list = Array.isArray(values) ? values : [values];
      const unique = [...new Set(list.map((value) => String(value ?? '').trim()).filter(Boolean))];
      if (unique.length) fields.push({ label, value: unique.join(' · ') });
    };

    add('System', activityValues(rows, (row) => row.tableSystemName));
    add('Site', activityValues(rows, (row) => row.tableSiteName));
    const identifiers = [];
    rows.forEach((row) => (row.tableIdentifiers || []).forEach((identifier) => {
      const label = String(identifier?.label || '').trim();
      const value = String(identifier?.value || '').trim();
      if (label && value) identifiers.push({ label, value });
    }));
    [...new Set(identifiers.map((identifier) => identifier.label))].forEach((label) =>
      add(label, identifiers.filter((identifier) => identifier.label === label).map((identifier) => identifier.value)));
    add('Channel', activityValues(rows, (row) => row.channel_name || row.tableChannelName));
    add('Call type', TUNER_ACTIVITY_LABELS[carrier?.status] || TUNER_ACTIVITY_LABELS.ACTIVE);
    add('Role', activityValues(rows, (row) => (row.tags || []).map(activityTokenLabel)));
    add('Callsign', activityValues(rows, (row) => row.callsign));
    add('LCN', activityValues(rows, (row) => row.lcn));
    add('Timeslot', activityValues(rows, (row) => row.timeslot));

    const targetForms = [...new Set(rows.filter((row) => row.target_id)
      .map((row) => String(row.target_form || '').toUpperCase()))];
    (targetForms.length ? targetForms : ['']).forEach((form) => add(targetIdentifierLabel(form),
      activityValues(rows.filter((row) => String(row.target_form || '').toUpperCase() === form ||
        (!form && !row.target_form)), (row) => row.target_id)));
    add('Target alias', activityValues(rows, (row) => activityAliasLabel(row, 'target')));
    add('Source type', activityValues(rows, (row) => activityTokenLabel(row.source_form)));
    add('Source', activityValues(rows, (row) => row.source_id));
    add('Source alias', activityValues(rows, (row) => activityAliasLabel(row, 'source')));
    add('Talker alias', activityValues(rows, (row) => row.talker_alias));
    const measuredSignal = activityValues(rows, (row) => Number.isFinite(Number(row.signal_dbfs)) ?
      `${Number(row.signal_dbfs).toFixed(1)} dBFS` : '');
    add('Signal', measuredSignal.length ? measuredSignal :
      (Number.isFinite(fftPower) ? `${fftPower.toFixed(1)} dB (FFT)` : ''));
    add('Control quality', activityValues(rows, (row) => Number.isFinite(Number(row.decode_health_pct)) ?
      `${Number(row.decode_health_pct).toFixed(1)}%` : ''));
    add('Voice quality', activityValues(rows, (row) => Number.isFinite(Number(row.vc_quality_pct)) ?
      `${Number(row.vc_quality_pct).toFixed(1)}%` : ''));
    add('Decoder', activityValues(rows, (row) => decoderLabel(row.decoder)));
    add('Encryption', activityValues(rows, (row) => row.encryption_details));
    return fields;
  }

  function activeCarrierDescription(carrier) {
    return activeCarrierFields(carrier).map((field) => `${field.label}: ${field.value}`).join(', ');
  }

  function renderActiveCarrierFields(carrier, power) {
    const fragments = [];
    activeCarrierFields(carrier, power).forEach((field) => {
      fragments.push(node('span', 'tuner-spectrum-cursor-field-label', field.label));
      fragments.push(node('span', 'tuner-spectrum-cursor-field-value', field.value));
    });
    cursorChannel.replaceChildren(...fragments);
    cursorChannel.hidden = fragments.length === 0;
  }

  function activeCarrierPower(carrier) {
    if (!fftValues.length || !frameMetadata) return null;
    const index = tunerBinAtFrequency(tunerFrameDomain(frameMetadata, fftValues.length), carrier.frequencyHz);
    const value = displayedSpectrumValues()[index];
    return Number.isFinite(value) ? value : null;
  }

  function showActiveFlag(carrier, flag) {
    if (!viewport) return;
    hoverFlag = flag;
    hoverRatio = null;
    hoverCanvas = null;
    hoverYRatio = null;
    setCursorGuide(carrier.frequencyHz);
    spectrum.guide.hidden = false;
    waterfall.guide.hidden = false;
    cursorFrequency.textContent = `${(carrier.frequencyHz / 1_000_000).toFixed(6)} MHz`;
    cursorSnap.hidden = false;
    cursorSnap.textContent = TUNER_ACTIVITY_LABELS[carrier.status] || TUNER_ACTIVITY_LABELS.ACTIVE;
    const power = activeCarrierPower(carrier);
    cursorPower.textContent = Number.isFinite(power) ? `FFT ${power.toFixed(1)} dB` : 'FFT —';
    renderActiveCarrierFields(carrier, power);
    cursorPopup.hidden = false;
    positionCursorPopup(flag);
  }

  function hideActiveFlag(flag) {
    if (hoverFlag === flag) hideCursor();
  }

  function renderActiveChannels() {
    if (!viewport) {
      if (hoverFlag) hideCursor();
      activeFlagSignature = '';
      activeFlagLayers.forEach((layer) => layer.replaceChildren());
      return;
    }
    const carriers = activeCarriers();
    const visibleSpanHz = Math.max(1, viewport.endHz - viewport.startHz);
    const waterfallPlotWidth = Math.max(0, waterfall.host.getBoundingClientRect().width);
    const waterfallFlagWidth = Math.max(TUNER_CHANNEL_MINIMUM_WIDTH_PX,
      Math.min(TUNER_CHANNEL_MAXIMUM_WIDTH_PX,
        waterfallPlotWidth * TUNER_CHANNEL_VISUAL_BANDWIDTH_HZ / visibleSpanHz));
    const signature = JSON.stringify([viewport.startHz, viewport.endHz,
      waterfallChannelsInput.checked, waterfallFlagWidth,
      carriers.map((carrier) => [carrier.frequencyHz, carrier.status, activeCarrierDescription(carrier)])]);
    if (signature === activeFlagSignature) return;
    if (hoverFlag) hideCursor();
    activeFlagSignature = signature;
    const createFlags = (waterfallLayer) => carriers.map((carrier) => {
      const flag = node(waterfallLayer ? 'span' : 'button',
        `tuner-spectrum-active-flag status-${carrier.status.toLowerCase()}`);
      if (!waterfallLayer) flag.type = 'button';
      flag.style.left = `${((carrier.frequencyHz - viewport.startHz) / visibleSpanHz * 100).toFixed(3)}%`;
      if (waterfallLayer) flag.style.width = `${waterfallFlagWidth.toFixed(2)}px`;
      flag.style.zIndex = String(TUNER_ACTIVITY_PRIORITY[carrier.status]);
      const details = activeCarrierDescription(carrier).replaceAll('\n', ', ');
      if (!waterfallLayer) {
        flag.setAttribute('aria-label', `${TUNER_ACTIVITY_LABELS[carrier.status]}, ${
          (carrier.frequencyHz / 1_000_000).toFixed(6)} MHz${details ? `, ${details}` : ''}`);
        flag.addEventListener('pointerenter', () => showActiveFlag(carrier, flag));
        flag.addEventListener('pointerleave', () => hideActiveFlag(flag));
        flag.addEventListener('focus', () => showActiveFlag(carrier, flag));
        flag.addEventListener('blur', () => hideActiveFlag(flag));
        flag.addEventListener('click', () => openTunerFrequencyActions(frequencySelectionForCarrier(carrier)));
      }
      return flag;
    });
    spectrumActiveFlags.replaceChildren(...createFlags(false));
    waterfallActiveFlags.replaceChildren(...(waterfallChannelsInput.checked ? createFlags(true) : []));
    if (hoverRatio !== null) updateCursor(hoverRatio);
  }

  function resetViewportForTarget() {
    const target = targetsById.get(selectedTargetId());
    const center = Number(target?.center_frequency_hz ?? 0);
    const sampleRate = Number(target?.sample_rate_hz ?? 0);
    if (center > 0 && sampleRate > 0) {
      fullViewport = { startHz: center - sampleRate / 2, endHz: center + sampleRate / 2 };
      viewport = { ...fullViewport };
      analysisViewport = { ...fullViewport };
    } else {
      fullViewport = null;
      viewport = null;
      analysisViewport = null;
    }
  }

  targetSelect.addEventListener('change', () => {
    storeTunerChoice(TUNER_SPECTRUM_TARGET_STORAGE_KEY, targetSelect.value);
    closeStreams();
    closeActiveChannels();
    resetViewportForTarget();
    resetPlots('Waiting for tuner data…');
    sync();
  });
  function applySelectedProfile() {
    if (!shouldRun()) return;
    spectrumProfile = profileSelect.value;
    storeTunerChoice(TUNER_SPECTRUM_PROFILE_STORAGE_KEY, spectrumProfile);
    queueViewportUpdate(true);
  }
  profileSelect.addEventListener('change', applySelectedProfile);
  zoomIn.addEventListener('click', () => {
    if (canInteract()) zoomAt(0.5, 1 / TUNER_SPECTRUM_ZOOM_FACTOR);
  });
  zoomOut.addEventListener('click', () => {
    if (canInteract()) zoomAt(0.5, TUNER_SPECTRUM_ZOOM_FACTOR);
  });
  resetZoom.addEventListener('click', resetViewport);
  pause.addEventListener('click', () => {
    paused = !paused;
    pause.textContent = paused ? 'Resume' : 'Pause';
    pause.setAttribute('aria-pressed', String(paused));
    sync();
    setReadouts(true);
  });
  function updateDisplayRange(changedHandle = '') {
    let floor = Number(floorInput.value);
    let ceiling = Number(ceilingInput.value);
    if (!Number.isFinite(floor) || !Number.isFinite(ceiling)) return;
    if (ceiling - floor < TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB) {
      if (changedHandle === 'floor') floor = ceiling - TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB;
      else ceiling = floor + TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB;
    }
    dbFloor = Math.max(TUNER_SPECTRUM_MINIMUM_DISPLAY_DB, floor);
    dbCeiling = Math.min(TUNER_SPECTRUM_MAXIMUM_DISPLAY_DB, ceiling);
    floorInput.value = String(dbFloor);
    ceilingInput.value = String(dbCeiling);
    rangeValue.textContent = `${dbFloor} to ${dbCeiling} dB`;
    const fullSpan = TUNER_SPECTRUM_MAXIMUM_DISPLAY_DB - TUNER_SPECTRUM_MINIMUM_DISPLAY_DB;
    rangeSlider.style.setProperty('--range-lower',
      `${(dbFloor - TUNER_SPECTRUM_MINIMUM_DISPLAY_DB) / fullSpan * 100}%`);
    rangeSlider.style.setProperty('--range-upper',
      `${(dbCeiling - TUNER_SPECTRUM_MINIMUM_DISPLAY_DB) / fullSpan * 100}%`);
    storeTunerNumber(TUNER_SPECTRUM_FLOOR_STORAGE_KEY, dbFloor);
    storeTunerNumber(TUNER_SPECTRUM_CEILING_STORAGE_KEY, dbCeiling);
    restoreWaterfallHistory();
    if (!refining) scheduleDraw();
  }
  floorInput.addEventListener('input', () => updateDisplayRange('floor'));
  ceilingInput.addEventListener('input', () => updateDisplayRange('ceiling'));
  updateDisplayRange();
  speedInput.addEventListener('input', () => {
    const candidate = Number(speedInput.value);
    if (!Number.isFinite(candidate)) return;
    waterfallSpeed = Math.max(0.25, Math.min(4, candidate));
    speedValue.textContent = `${waterfallSpeed.toFixed(2)}×`;
    storeTunerNumber(TUNER_WATERFALL_SPEED_STORAGE_KEY, waterfallSpeed);
  });
  snapInput.addEventListener('change', () => {
    storeTunerBoolean(TUNER_SPECTRUM_SNAP_STORAGE_KEY, snapInput.checked);
    if (hoverRatio !== null) updateCursor(hoverRatio);
  });
  smoothInput.addEventListener('change', () => {
    storeTunerBoolean(TUNER_SPECTRUM_SMOOTH_STORAGE_KEY, smoothInput.checked);
    clearSpectrumSmoothing();
    if (smoothInput.checked && fftValues.length && frameMetadata) {
      updateSpectrumSmoothing(fftValues, frameMetadata, tunerFrameDomain(frameMetadata, fftValues.length));
    }
    updateSpectrumPeak();
    setReadouts(true);
    if (hoverRatio !== null) updateCursor(hoverRatio);
    if (!refining) scheduleDraw('spectrum');
  });
  waterfallChannelsInput.addEventListener('change', () => {
    storeTunerBoolean(TUNER_WATERFALL_CHANNELS_STORAGE_KEY, waterfallChannelsInput.checked);
    activeFlagSignature = '';
    renderActiveChannels();
  });
  [spectrum.canvas, waterfall.canvas].forEach(addPlotInteractions);
  const onVisibilityChange = () => sync();
  const onResize = () => {
    renderActiveChannels();
    if (hoverFlag) positionCursorPopup(hoverFlag);
    else positionCursorPopup();
    if (!refining) scheduleDraw();
  };
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('resize', onResize);
  resetPlots('Loading tuners…');

  api('/api/v1/diagnostics/tuners').then((response) => {
    if (disposed) return;
    const targets = tunerDiagnosticTargets(response);
    targetsById.clear();
    targetSelect.replaceChildren();
    if (!targets.length) {
      targetSelect.append(node('option', '', 'No tuners available'));
      targetSelect.disabled = true;
      pause.disabled = true;
      setStatus('Unavailable');
      resetPlots('No enabled tuner supports spectrum diagnostics.');
      return;
    }
    targets.forEach((target) => {
      targetsById.set(target.id, target);
      const option = node('option', '', target.label);
      option.value = target.id;
      targetSelect.append(option);
    });
    targetSelect.value = tunerStoredChoice(TUNER_SPECTRUM_TARGET_STORAGE_KEY, targets[0].id,
      targets.map((target) => target.id));
    targetSelect.disabled = false;
    pause.disabled = false;
    resetViewportForTarget();
    resetPlots('Waiting for tuner data…');
    sync();
  }).catch((error) => {
    if (disposed) return;
    targetSelect.replaceChildren(node('option', '', 'Tuners unavailable'));
    targetSelect.disabled = true;
    pause.disabled = true;
    setStatus('Unavailable');
    resetPlots(error.message || 'Could not load tuner diagnostics.');
  });
  return controller;
}

function liveEventsPanel(onCollapse) {
  const events = new Map();
  const order = [];
  let selection = null;
  let paused = false;
  let eventsActive = true;
  let collapsed = false;
  let stream = null;
  let streamEpoch = 0;
  let renderTimer = null;
  let lastRenderAt = 0;
  let missed = 0;
  let possibleGap = false;
  let transportReady = false;
  let expectedSubscriptionId = null;
  let scheduleRender = () => {};

  const panel = node('section', 'section live-details');
  const header = node('div', 'live-details-header');
  const tabBar = node('div', 'live-details-tabs');
  tabBar.setAttribute('role', 'tablist');
  tabBar.setAttribute('aria-label', 'Live details');
  const controls = node('div', 'live-details-controls');
  const pause = node('button', 'button secondary live-details-pause', 'Pause');
  pause.type = 'button';
  pause.setAttribute('aria-label', 'Pause Events, Messages, and Channel');
  pause.setAttribute('aria-pressed', 'false');
  const collapse = node('button', 'button secondary live-details-collapse', 'Collapse');
  collapse.type = 'button';
  collapse.setAttribute('aria-expanded', 'true');
  controls.append(pause, collapse);
  header.append(tabBar, controls);

  const body = node('div', 'live-details-body');
  const eventPane = node('div', 'live-details-pane live-events-pane');
  const eventToolbar = node('div', 'live-events-toolbar live-detail-toolbar');
  const selectionLabel = node('strong', 'live-event-selection', 'Select a live row above');
  const filters = liveDetailFilterController({
    noun: 'events',
    title: 'Event filters',
    typeHeading: 'Event types',
    searchPlaceholder: 'Search parties or details',
    onChange: () => scheduleRender()
  });
  eventToolbar.append(selectionLabel, filters.element);
  const eventGap = node('div', 'live-detail-gap');
  eventGap.hidden = true;
  eventGap.setAttribute('role', 'status');

  const eventScroll = node('div', 'live-events-scroll');
  const table = node('table', 'data-table live-events-table');
  const head = node('thead');
  const headerRow = node('tr');
  ['Time', 'Duration', 'Event', 'From', 'To', 'Channel', 'Details'].forEach((label) =>
    headerRow.append(node('th', '', label)));
  head.append(headerRow);
  const eventBody = node('tbody');
  table.append(head, eventBody);
  eventScroll.append(table);
  eventPane.append(eventToolbar, eventGap, eventScroll);

  const messagesController = liveMessagesPane();
  const channelController = liveChannelPane();
  const messagesPane = messagesController.element;
  const channelPane = channelController.element;
  body.append(eventPane, messagesPane, channelPane);
  panel.append(header, body);

  const eventMatches = (event) => {
    if (!filters.matchesLeaf(event.event_type)) return false;
    const query = filters.query();
    return !query || [event.event_label, event.event_type, event.from_aliases, event.from_identifiers,
      event.to_aliases, event.to_identifiers, event.channel, event.details]
      .some((value) => liveDetailText(value).includes(query));
  };

  const renderEvents = () => {
    if (paused) return;
    eventBody.replaceChildren();
    const rows = order.map((id) => events.get(id)).filter((event) => event && eventMatches(event))
      .slice(0, liveDetailMatchingRowLimit());
    if (!selection || !rows.length) {
      const empty = node('tr', 'empty');
      const message = node('td', '', selection ?
        'No matching events received since this tab was opened' : 'Select a live row above');
      message.colSpan = 7;
      empty.append(message);
      eventBody.append(empty);
      return;
    }
    rows.forEach((event) => {
      const row = node('tr', liveEventCategoryClass(event.category));
      row.dataset.eventId = event.event_id;
      row.dataset.eventCategory = event.category || 'OTHER';
      const time = node('td', 'live-event-time');
      const started = new Date(Number(event.time_start_ms));
      const timeText = Number.isFinite(started.getTime()) ? started.toLocaleTimeString([], {
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      }) : '';
      const timeValue = node('strong', '', timeText);
      if (timeText) timeValue.title = exactDateTime(event.time_start_ms);
      time.append(timeValue);

      const durationText = liveEventDuration(event.duration_ms);
      const duration = node('td', 'live-event-duration');
      const durationValue = node('strong', 'live-event-duration-value', durationText);
      durationValue.title = `Duration ${durationText}`;
      duration.append(durationValue);

      const eventType = node('td', 'live-event-stack');
      eventType.append(node('strong', '', event.event_label || event.event_type || 'Event'));
      if (event.protocol) eventType.append(node('small', '', event.protocol));

      const channel = node('td', 'live-event-stack');
      if (event.channel) channel.append(node('strong', '', event.channel));
      const channelDetail = [event.frequency_hz ? `${frequency(event.frequency_hz)} MHz` : '',
        event.timeslot == null ? '' : `TS ${event.timeslot}`].filter(Boolean).join(' · ');
      if (channelDetail) channel.append(node(event.channel ? 'small' : 'span', '', channelDetail));

      const details = node('td', 'live-event-details', event.details || '');
      if (event.details) details.title = event.details;
      row.append(time, duration, eventType, liveEventParty(event, 'from'), liveEventParty(event, 'to'),
        channel, details);
      eventBody.append(row);
    });
  };

  scheduleRender = () => {
    if (renderTimer !== null || paused) return;
    const delay = Math.max(0, LIVE_DETAIL_REFRESH_INTERVAL_MILLISECONDS - (Date.now() - lastRenderAt));
    renderTimer = window.setTimeout(() => {
      renderTimer = null;
      lastRenderAt = Date.now();
      renderEvents();
    }, delay);
  };

  const updateGapNotice = () => {
    const notices = [];
    if (missed > 0) {
      notices.push(`${number(missed)} live event${missed === 1 ? '' : 's'} skipped while the viewer was open.`);
    }
    if (possibleGap) notices.push('The live source reconnected; additional events may have been missed.');
    eventGap.textContent = notices.join(' ');
    eventGap.hidden = !notices.length;
  };

  const closeStream = () => {
    streamEpoch += 1;
    transportReady = false;
    expectedSubscriptionId = null;
    if (!stream) return;
    stream.close();
    liveConnections.delete(stream);
    pageConnections.delete(stream);
    stream = null;
  };

  const addEvent = (event) => {
    if (!event?.event_id) return;
    if (!events.has(event.event_id)) order.unshift(event.event_id);
    events.set(event.event_id, event);
    while (order.length > liveDetailCaptureLimit()) {
      const removedId = order.pop();
      events.delete(removedId);
    }
    scheduleRender();
  };

  const clearSession = () => {
    events.clear();
    order.length = 0;
    missed = 0;
    possibleGap = false;
    updateGapNotice();
    scheduleRender();
  };

  const addGap = (value) => {
    missed += Math.max(1, Math.trunc(Number(value?.dropped) || 1));
    updateGapNotice();
  };

  const shouldRun = () => eventsActive && !collapsed && selection?.configurationId;

  const sync = () => {
    if (!shouldRun()) {
      closeStream();
      return;
    }
    if (stream) return;
    const epoch = ++streamEpoch;
    const subscriptionId = randomLiveClientId();
    const parameters = { configuration_id: selection.configurationId };
    if (selection.kind === LIVE_DETAIL_SELECTION_KINDS.EXACT && selection.bindingFrequencyHz) {
      parameters.frequency_hz = selection.bindingFrequencyHz;
      if (selection.bindingTimeslot) parameters.timeslot = selection.bindingTimeslot;
    }
    parameters.subscription_id = subscriptionId;
    expectedSubscriptionId = subscriptionId;
    transportReady = false;
    let opened = document.hidden;
    stream = liveConnection('decode_events', parameters);
    stream.onopen = () => {
      if (epoch !== streamEpoch) return;
      if (opened) {
        possibleGap = true;
        updateGapNotice();
      }
      opened = true;
    };
    stream.addEventListener('decode_event', (event) => {
      if (epoch !== streamEpoch || !transportReady) return;
      const value = JSON.parse(event.data);
      if (liveEventMatchesSelection(selection, value)) addEvent(value);
    });
    stream.addEventListener('source_change', (event) => {
      if (epoch !== streamEpoch) return;
      const source = JSON.parse(event.data);
      if (!liveEventScopeMatchesSelection(selection, expectedSubscriptionId, source)) return;
      transportReady = true;
      filters.setCatalog(source?.filter_catalog);
    });
    stream.addEventListener('filter_catalog', (event) => {
      if (epoch !== streamEpoch) return;
      filters.setCatalog(JSON.parse(event.data));
    });
    stream.addEventListener('live_gap', (event) => {
      if (epoch === streamEpoch && transportReady) addGap(JSON.parse(event.data));
    });
  };

  const select = (nextSelection) => {
    messagesController.select(nextSelection);
    channelController.select(nextSelection);
    const { logicalChanged } = liveDetailSelectionDelta(selection, nextSelection);
    selection = nextSelection;
    selectionLabel.textContent = selection?.label || 'Select a live row above';
    if (logicalChanged) {
      closeStream();
      clearSession();
    }
    scheduleRender();
    sync();
  };

  const panes = { events: eventPane, messages: messagesPane, channel: channelPane };
  ['events', 'messages', 'channel'].forEach((id) => {
    const button = node('button', 'live-details-tab', id[0].toUpperCase() + id.slice(1));
    button.type = 'button';
    button.setAttribute('role', 'tab');
    button.addEventListener('click', () => {
      Object.entries(panes).forEach(([paneId, pane]) => {
        const active = paneId === id;
        pane.hidden = !active;
        tabBar.querySelector(`[data-tab="${paneId}"]`)?.setAttribute('aria-selected', String(active));
      });
      messagesController.setActive(id === 'messages');
      channelController.setActive(id === 'channel');
      const nextEventsActive = id === 'events';
      if (eventsActive && !nextEventsActive) {
        possibleGap = Boolean(selection);
        updateGapNotice();
        closeStream();
      }
      eventsActive = nextEventsActive;
      sync();
    });
    button.dataset.tab = id;
    button.setAttribute('aria-selected', String(id === 'events'));
    tabBar.append(button);
    panes[id].hidden = id !== 'events';
  });

  collapse.addEventListener('click', () => {
    collapsed = !panel.classList.contains('collapsed');
    if (collapsed && stream) {
      possibleGap = true;
      updateGapNotice();
    }
    panel.classList.toggle('collapsed', collapsed);
    collapse.textContent = collapsed ? 'Expand' : 'Collapse';
    collapse.setAttribute('aria-expanded', String(!collapsed));
    messagesController.setCollapsed(collapsed);
    channelController.setCollapsed(collapsed);
    onCollapse(collapsed);
    sync();
  });
  pause.addEventListener('click', () => {
    paused = !paused;
    pause.textContent = paused ? 'Resume' : 'Pause';
    pause.setAttribute('aria-label', `${paused ? 'Resume' : 'Pause'} Events, Messages, and Channel`);
    pause.setAttribute('aria-pressed', String(paused));
    messagesController.setPaused(paused);
    channelController.setPaused(paused);
    if (!paused) scheduleRender();
  });
  renderEvents();
  return {
    element: panel,
    select,
    close() {
      closeStream();
      if (renderTimer !== null) window.clearTimeout(renderTimer);
      renderTimer = null;
      events.clear();
      order.length = 0;
      filters.close();
      messagesController.close();
      channelController.close();
    }
  };
}

function liveAliasReferences(row, kind) {
  const values = Array.isArray(row?.[`${kind}_aliases`]) ? row[`${kind}_aliases`] : [];
  return values.filter((value) => Number.isInteger(Number(value?.alias_id)) && Number(value.alias_id) > 0 &&
    Number.isInteger(Number(value?.alias_list_id)) && Number(value.alias_list_id) > 0);
}

function liveTableIdentifier(value, label) {
  const expected = String(label || '').trim().toLowerCase();
  const identifiers = Array.isArray(value?.identifiers) ? value.identifiers : [];
  const identifier = identifiers.find((candidate) =>
    String(candidate?.label || '').trim().toLowerCase() === expected);
  return String(identifier?.value || '').trim();
}

function liveTableScopeToken(value) {
  const wacn = liveTableIdentifier(value, 'WACN').replace(/^0x/i, '');
  const system = liveTableIdentifier(value, 'System ID').replace(/^0x/i, '');
  if (/^[0-9a-f]{1,5}$/i.test(wacn) && /^[0-9a-f]{1,3}$/i.test(system)) {
    return `p25:${wacn.padStart(5, '0').toUpperCase()}:${system.padStart(3, '0').toUpperCase()}`;
  }
  const protocol = (value?.rows || []).flatMap((row) =>
    [row?.source_matcher?.protocol, row?.target_matcher?.protocol])
    .map((candidate) => String(candidate || '').trim().toLowerCase())
    .find((candidate) => candidate === 'dmr' || candidate === 'nxdn');
  const guid = String(value?.guid || '').trim();
  return protocol && guid ? `${protocol}:guid:${guid}` : '';
}

function liveTableWithNavigation(value) {
  const scopeToken = liveTableScopeToken(value);
  if (!scopeToken || !Array.isArray(value?.rows)) return value;
  return { ...value, rows: value.rows.map((row) => row?.scope_token ? row : { ...row, scope_token: scopeToken }) };
}

function liveExistingAliasHref(reference) {
  return reference && aliasAdminAllowed() ? href('aliases', {
    list: Number(reference.alias_list_id), aliasTab: 'configure', alias: Number(reference.alias_id)
  }) : '';
}

function liveIdentityType(row, kind) {
  const matcherType = String(row?.[`${kind}_matcher`]?.type || '').trim().toLowerCase();
  if (matcherType === 'talkgroup' || matcherType === 'radio') return matcherType;
  const form = String(row?.[`${kind}_form`] || '').trim().toUpperCase();
  if (form.includes('RADIO')) return 'radio';
  if (form.includes('TALKGROUP')) return 'talkgroup';
  return '';
}

function liveIdentityInfoHref(row, kind) {
  if (!capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS) || !row?.scope_token) return '';
  const id = Number(row?.[`${kind}_id`]);
  const type = liveIdentityType(row, kind);
  if (!Number.isSafeInteger(id) || id < 0 || !type || specialIdentifierLabel(row, id, type)) return '';
  return href(type, { scope: row.scope_token, id, tab: 'info' });
}

let liveIdentityActionSequence = 0;

function liveIdentityActionLink(row, kind, label, aliasTarget, aliasMode = 'edit') {
  const infoTarget = liveIdentityInfoHref(row, kind);
  if (!aliasTarget && !infoTarget) return label;
  const trigger = anchor(label, infoTarget || aliasTarget, 'live-alias-link');
  if (!aliasTarget || !infoTarget) return trigger;
  const id = `live-identity-action-${++liveIdentityActionSequence}`;
  trigger.id = id;
  trigger.setAttribute('aria-haspopup', 'dialog');
  trigger.setAttribute('aria-label', `${String(label)} actions`);
  trigger.addEventListener('click', (event) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    const type = liveIdentityType(row, kind);
    const identityLabel = type === 'radio' ? 'radio' : 'talkgroup';
    const modalBody = node('div', 'tuner-frequency-action-body');
    modalBody.append(node('p', 'tuner-frequency-action-intro', `Choose what to do with ${String(label)}.`));
    const actions = node('div', 'tuner-frequency-action-list');
    const manage = anchor('', aliasTarget, 'button secondary tuner-frequency-action');
    manage.append(node('strong', '', aliasMode === 'create' ? 'Create alias' : 'Edit alias'),
      node('small', '', `${aliasMode === 'create' ? 'Create' : 'Open'} this ${identityLabel}'s configured alias.`));
    const info = anchor('', infoTarget, 'button secondary tuner-frequency-action');
    info.append(node('strong', '', `Open ${identityLabel} info`),
      node('small', '', `View this ${identityLabel}'s activity and system details.`));
    actions.append(manage, info);
    modalBody.append(actions);
    openReadOnlyModal(`${identityLabel === 'radio' ? 'Radio' : 'Talkgroup'} ${row?.[`${kind}_id`] || ''}`,
      modalBody, { id: 'live-identity-actions', className: 'frequency-action-modal',
        returnFocusSelector: `#${id}` });
  });
  return trigger;
}

function liveAliasDraftHref(row, kind) {
  const matcher = row?.[`${kind}_matcher`];
  const aliasListName = String(row?.alias_list_name || '').trim();
  const type = String(matcher?.type || '').trim().toLowerCase();
  const protocol = String(matcher?.protocol || '').trim().toLowerCase();
  const variant = String(matcher?.variant || '').trim().toLowerCase();
  const value = Number(matcher?.value);
  if (!aliasAdminAllowed() || !aliasListName || !['talkgroup', 'radio'].includes(type) ||
      !['am', 'p25', 'dmr', 'nxdn', 'nbfm', 'fleetsync', 'mdc1200'].includes(protocol) ||
      !Number.isSafeInteger(value) || value < 0 ||
      specialIdentifierLabel(row, value, type)) return '';
  const suggestedName = kind === 'source' && String(row?.talker_alias || '').trim() ?
    String(row.talker_alias).trim() : `${type === 'radio' ? 'Radio' : 'Talkgroup'} ${value}`;
  return href('aliases', {
    aliasTab: 'configure', createAlias: 1, createListName: aliasListName, createType: type,
    createProtocol: protocol, createVariant: variant, createValue: value, createName: suggestedName
  });
}

function liveIdentifierAliasValue(row, kind) {
  const value = row?.[`${kind}_id`];
  const text = value === null || value === undefined ? '' : String(value);
  if (!text) return '';
  const reference = liveAliasReferences(row, kind)[0];
  const target = liveExistingAliasHref(reference) || liveAliasDraftHref(row, kind);
  return liveIdentityActionLink(row, kind, text, target, reference ? 'edit' : 'create');
}

function liveAliasValue(row, kind) {
  const references = liveAliasReferences(row, kind);
  const fallback = kind === 'source' ?
    (row?.source_alias_display || row?.source_alias || (row?.talker_alias ? `TA: ${row.talker_alias}` : '')) :
    (row?.target_alias || '');
  if (!references.length) return fallback ?
    liveIdentityActionLink(row, kind, fallback, liveAliasDraftHref(row, kind), 'create') : fallback;
  const result = node('span', 'live-alias-values');
  references.forEach((reference, index) => {
    if (index) result.append(document.createTextNode(', '));
    const label = String(reference.name || '').trim() || `Alias ${Number(reference.alias_id)}`;
    const target = liveExistingAliasHref(reference);
    result.append(liveIdentityActionLink(row, kind, label, target));
  });
  if (kind === 'source' && row?.talker_alias) {
    const talker = String(row.talker_alias).trim();
    const configured = new Set(references.map((reference) => String(reference.name || '').trim().toLowerCase()));
    if (talker && !configured.has(talker.toLowerCase())) result.append(document.createTextNode(` · TA: ${talker}`));
  }
  return result;
}

function liveConventionalChannelValue(row) {
  const label = String(row?.channel_name || '');
  return label && row?.context_key && capabilityAllowed(ACCESS_CAPABILITIES.CONVENTIONAL) ?
    anchor(label, href('conventional-detail', { context: row.context_key, tab: 'info' }),
      'live-channel-link') : label;
}

function liveSystemTabTitle(value, label, siteTarget, selectTable) {
  const title = String(label || '');
  const channelName = String(value?.channel_name || '').trim();
  const parenthetical = channelName ? `(${channelName})` : '';
  const parentheticalAt = parenthetical && title.endsWith(parenthetical) ?
    title.length - parenthetical.length : -1;
  const siteName = String(value?.site_name || '').trim();
  const linkText = parentheticalAt >= 0 ? parenthetical : siteName;
  const linkAt = parentheticalAt >= 0 ? parentheticalAt :
    (siteName && title.includes(siteName) ? title.indexOf(siteName) : -1);
  const selectable = (text) => {
    if (!text) return null;
    const button = node('button', 'systems-tab-title-button', text);
    button.type = 'button';
    button.addEventListener('click', selectTable);
    return button;
  };
  if (linkAt < 0 || !siteTarget) return selectable(title);
  const site = anchor(linkText, siteTarget, 'systems-tab-label');
  site.setAttribute('aria-label', `Open ${siteName || channelName} site details`);
  return fragment(selectable(title.slice(0, linkAt)), site, selectable(title.slice(linkAt + linkText.length)));
}

function openLocalHref(target) {
  const url = new URL(target, window.location.href);
  window.history.pushState({}, '', `${url.pathname}${url.search}${url.hash}`);
  route = new URLSearchParams(url.search);
  void render();
}

function liveSystemsSection(onSelectionChange) {
  const tables = new Map();
  const tabNodes = new Map();
  const rowNodes = new Map();
  const dismissedStoppedTables = new Set();
  const decodeDisplay = liveDisplaySettings ? {
    show_control: liveDisplaySettings.show_control_decode_quality !== false,
    show_voice: liveDisplaySettings.show_voice_decode_quality !== false,
    mode: liveDisplaySettings.decode_quality_display_mode === 'detailed' ? 'detailed' : 'percentage'
  } : serviceStatus?.decode_display || { show_control: true, show_voice: true, mode: 'percentage' };
  const showEncryptionDetails = liveDisplaySettings?.show_encryption_details ??
    (serviceStatus?.web_display?.show_encryption_details !== false);
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
    if (decodeDisplay.show_control && row.decode_health_pct != null) {
      values.push(Number(row.decode_health_pct));
    }
    if (decodeDisplay.show_voice && row.vc_quality_pct != null && voiceQualityReady(row)) {
      values.push(Number(row.vc_quality_pct));
    }
    return values.filter(Number.isFinite);
  };
  const decodeQualityText = (row) => {
    const values = [];
    const detailed = decodeDisplay.mode === 'detailed';
    if (decodeDisplay.show_control && row.decode_health_pct != null) {
      let value = `CC ${Number(row.decode_health_pct).toFixed(1)}%`;
      if (detailed) {
        value += ` · ${Number(row.cc_valid_frames || 0)}/${Number(row.cc_invalid_frames || 0)}/` +
          `${Number(row.cc_corrected_bits || 0)}/${Number(row.cc_sync_loss_bits || 0)}/` +
          `${Number(row.cc_dropped_bits || 0)}`;
      }
      values.push(value);
    }
    if (decodeDisplay.show_voice && row.vc_quality_pct != null) {
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
    if (decodeDisplay.show_control && row.decode_health_pct != null) {
      values.push('CC uses a rolling 30-second control-channel window. Detail order: valid frames / ' +
        'invalid frames / corrected bits / sync-loss bits / dropped bits.');
    }
    if (decodeDisplay.show_voice && row.vc_quality_pct != null) {
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
  let selection = null;

  const clearSelection = () => {
    if (!selection) return;
    selection = null;
    rowNodes.forEach((candidate) => candidate.classList.remove('selected'));
    onSelectionChange(null);
  };

  const selectRow = (value, row) => {
    if (!value || !row) return;
    const nextSelection = liveDetailRowSelection(value, row);
    if (!nextSelection) return;
    selection = nextSelection;
    rowNodes.forEach((candidate, key) => candidate.classList.toggle('selected', key === selection.rowKey));
    onSelectionChange(selection);
  };

  const cellText = (cell, value) => {
    const text = value === null || value === undefined ? '' : String(value);
    if (cell.textContent !== text) cell.textContent = text;
  };

  const cellValue = (cell, value, signature) => {
    const nextSignature = String(signature || '');
    if (cell.dataset.liveValue === nextSignature) return;
    cell.dataset.liveValue = nextSignature;
    cell.replaceChildren();
    cell.append(valueNode(value));
  };

  const updateRow = (element, row) => {
    const cells = element.children;
    const conventional = channelTagSet(row.tags).has('CONVENTIONAL');
    const statusText = showEncryptionDetails && row.status === 'ENCRYPTED' && row.encryption_details ?
      row.encryption_details : row.status;
    cellText(cells[0], statusText);
    cellText(cells[1], channelTagText(row));
    cellValue(cells[2], conventional ? liveConventionalChannelValue(row) : row.lcn,
      conventional ? `channel:${row.context_key || ''}:${row.channel_name || ''}` : `lcn:${row.lcn || ''}`);
    cellText(cells[3], frequency(row.frequency_hz));
    cellText(cells[4], row.signal_dbfs == null ? '' : `${Number(row.signal_dbfs).toFixed(1)} dBFS`);
    cellText(cells[5], decodeQualityText(row));
    cellValue(cells[6], liveAliasValue(row, 'source'),
      JSON.stringify([row.source_aliases, row.source_alias_display, row.source_alias, row.talker_alias]));
    cellValue(cells[7], liveIdentifierAliasValue(row, 'source'),
      JSON.stringify([row.source_id, row.source_aliases, row.source_matcher, row.alias_list_name]));
    cellValue(cells[8], liveAliasValue(row, 'target'), JSON.stringify([row.target_aliases, row.target_alias]));
    cellValue(cells[9], liveIdentifierAliasValue(row, 'target'),
      JSON.stringify([row.target_id, row.target_aliases, row.target_matcher, row.alias_list_name]));
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
    cells[6].title = row.source_alias_description || '';
    cells[8].title = row.target_alias_description || '';
    cells[10].title = decoderLabel(row.decoder);
    const decodeValues = decodeQualityValues(row);
    const decodePercent = decodeValues.length ? Math.min(...decodeValues) : null;
    cells[5].className = decodePercent == null ? '' :
      (decodePercent >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'quality-good' :
        (decodePercent >= DECODE_DEGRADED_MINIMUM_PERCENT ?
          'quality-warn' : 'quality-bad'));
    element.classList.toggle('selected', selection?.rowKey === row.key);
  };

  const createRow = (row) => {
    const element = node('tr');
    element.dataset.key = row.key;
    for (let index = 0; index < 11; index += 1) element.append(node('td'));
    element.addEventListener('click', (event) => {
      if (event.target.closest('a, button')) return;
      const value = tables.get(activeTableId);
      const currentRow = (value?.rows || []).find((candidate) => candidate.key === element.dataset.key);
      if (!currentRow) return;
      selectRow(value, currentRow);
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
    clearSelection();
    activeTableId = tableId;
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
    const currentControl = value.control_active ? liveCurrentControlRow(value) : null;
    if (currentControl) selectRow(value, currentControl);
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
    if (selection?.kind === LIVE_DETAIL_SELECTION_KINDS.CONTROL) {
      const currentControl = value.control_active ? liveCurrentControlRow(value) : null;
      if (currentControl) selectRow(value, currentControl);
      else {
        const controlIntent = (value.rows || []).find((row) =>
          LIVE_DETAIL_CONTROL_ROLES.has(String(row?.role || '').toUpperCase())) || {
          configuration_id: selection.configurationId,
          role: 'CURRENT_CONTROL'
        };
        selection = liveDetailSelection(value, controlIntent, null);
        rowNodes.forEach((candidate) => candidate.classList.remove('selected'));
        onSelectionChange(selection);
      }
    } else if (selection) {
      const selectedRow = incoming.get(selection.rowKey);
      if (selectedRow) selectRow(value, selectedRow);
      else clearSelection();
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
    value = liveTableWithNavigation(value);
    if (!value?.table_id) return;
    if (dismissedStoppedTables.has(value.table_id)) {
      if (value.channel_running !== true) return;
      dismissedStoppedTables.delete(value.table_id);
    }
    tables.set(value.table_id, value);
    let tab = tabNodes.get(value.table_id);
    if (!tab) {
      tab = node('div', 'systems-live-tab');
      const select = node('button', 'systems-tab-select');
      select.type = 'button';
      const quality = node('span', 'systems-tab-quality');
      for (let index = 0; index < 4; index += 1) quality.append(node('span'));
      select.append(quality);
      select.addEventListener('click', () => {
        const current = tables.get(value.table_id);
        const qualityTarget = current?.table_id !== 'conventional' && current?.guid &&
          capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS) ? href('site', { guid: current.guid, tab: 'quality' }) : '';
        if (qualityTarget) openLocalHref(qualityTarget);
        else showTable(value.table_id);
      });
      const title = node('span', 'systems-tab-title');
      const close = node('button', 'systems-tab-close', '×');
      close.type = 'button';
      close.hidden = true;
      close.addEventListener('click', (event) => {
        event.stopPropagation();
        const current = tables.get(value.table_id);
        if (!current || current.channel_running !== false) return;
        dismissedStoppedTables.add(value.table_id);
        removeTable(value.table_id);
      });
      tab.append(select, title, close);
      tabNodes.set(value.table_id, tab);
      tabBar.append(tab);
    }
    const label = value.title || value.channel_name || value.table_id;
    const select = tab.querySelector('.systems-tab-select');
    const title = tab.querySelector('.systems-tab-title');
    const titleSignature = `${label}|${value.guid || ''}|${value.table_id}`;
    if (title.dataset.liveValue !== titleSignature) {
      title.dataset.liveValue = titleSignature;
      const siteTarget = value.table_id !== 'conventional' && value.guid &&
        capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS) ? href('site', { guid: value.guid, tab: 'info' }) : '';
      title.replaceChildren(liveSystemTabTitle(value, label, siteTarget, () => showTable(value.table_id)));
    }
    const quality = tab.querySelector('.systems-tab-quality');
    const qualityLinksToSite = value.table_id !== 'conventional' && value.guid &&
      capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS);
    const currentControl = liveCurrentControlRow(value);
    const qualityObservedAt = Number(currentControl?.quality_observed_at_ms || 0);
    const qualityFresh = currentControl && value.control_active && qualityObservedAt > 0 &&
      Date.now() - qualityObservedAt <= SIGNAL_OFFLINE_MILLISECONDS;
    const signalValue = optionalNumber(currentControl?.signal_dbfs);
    const decodeValue = optionalNumber(currentControl?.decode_health_pct);
    const signalStrength = qualityFresh && Number.isFinite(signalValue) ? signalValue : null;
    const decodeQuality = qualityFresh && Number.isFinite(decodeValue) ?
      Math.max(0, Math.min(100, decodeValue)) : null;
    if (value.table_id === 'conventional') {
      quality.className = 'systems-tab-quality quality-neutral';
      tab.title = label;
      select.setAttribute('aria-label', `Show live channels for ${label}`);
    } else if (signalStrength === null && decodeQuality === null) {
      quality.className = 'systems-tab-quality quality-unavailable';
      tab.title = `${label} · Signal strength and decode quality unavailable`;
      select.setAttribute('aria-label', `Open ${label} channel quality; signal strength and decode quality unavailable`);
    } else {
      const level = signalBarLevel(signalStrength);
      const state = decodeQuality === null ? 'unavailable' :
        (decodeQuality >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'healthy' :
          (decodeQuality >= DECODE_DEGRADED_MINIMUM_PERCENT ? 'degraded' : 'poor'));
      quality.className = `systems-tab-quality quality-${state} quality-level-${level}`;
      const signalLabel = signalStrength === null ? 'Signal strength unavailable' :
        `${signalStrength.toFixed(1)} dBFS signal strength`;
      const qualityLabel = decodeQuality === null ? 'Decode quality unavailable' :
        `${decodeQuality.toFixed(1)}% decode quality`;
      tab.title = `${label} · ${signalLabel} · ${qualityLabel}`;
      select.setAttribute('aria-label', `Open ${label} channel quality, ${signalLabel}, ${qualityLabel}`);
    }
    quality.classList.toggle('quality-link', Boolean(qualityLinksToSite));
    select.classList.toggle('quality-link', Boolean(qualityLinksToSite));
    const stopped = value.table_id !== 'conventional' && value.channel_running === false;
    tab.classList.toggle('stopped', stopped);
    const close = tab.querySelector('.systems-tab-close');
    close.hidden = !stopped;
    close.title = stopped ? `Close stopped channel ${label}` : '';
    close.setAttribute('aria-label', `Close stopped channel ${label}`);
    if (!activeTableId) showTable(tables.has('conventional') ? 'conventional' : value.table_id);
    else updateVisibleRows(value);
  };

  const removeTable = (tableId) => {
    tables.delete(tableId);
    tabNodes.get(tableId)?.remove();
    tabNodes.delete(tableId);
    if (activeTableId === tableId) {
      clearSelection();
      activeTableId = null;
      const next = tables.has('conventional') ? 'conventional' : tables.keys().next().value;
      if (next) showTable(next);
      else body.replaceChildren();
    }
  };

  subscribeLiveChannelActivity({
    snapshot: (snapshot) => {
      (snapshot.tables || []).forEach(upsertTable);
    },
    activityTable: (update) => {
      if (update.operation === 'remove') removeTable(update.table_id);
      else upsertTable(update.table);
    },
    open: () => {
      connection.textContent = 'Live';
      connection.className = 'badge state-current';
    },
    error: () => {
      connection.textContent = 'Reconnecting';
      connection.className = 'badge state-stale';
    }
  });
  return block;
}

async function renderLive() {
  const renderContext = captureRenderContext();
  const split = node('div', 'live-split');
  const eventsPanel = liveEventsPanel((collapsed) => split.classList.toggle('details-collapsed', collapsed));
  pageConnections.add(eventsPanel);
  const systems = liveSystemsSection(eventsPanel.select);
  split.append(systems, eventsPanel.element);
  beginPage(renderContext, split);
}

async function renderTunerSpectrum() {
  const renderContext = captureRenderContext();
  const spectrum = tunerSpectrumPanel();
  pageConnections.add(spectrum);
  beginPage(renderContext, pageHeader('Tuner Spectrum',
    'Inspect the full bandwidth of each active tuner. Click a frequency to choose an action.'), spectrum.element);
}

function systemsDirectoryContent(data) {
  const { page, tableRows: rows, truncatedParentCount, previewLimit } = data;
  const columns = [
    { id: 'directory-name', label: 'System / Site', width: 230, className: 'directory-name', render: (row) => {
      const wrapper = node('div', 'directory-entity');
      if (row.directory_type === 'system') {
        const label = row.configured_system || `${protocolFamily(row)} System`;
        const heading = node('strong');
        heading.append(systemLink(row, label));
        wrapper.append(heading);
      } else {
        wrapper.append(node('span', 'directory-branch', '↳'), siteNameSummary(row));
      }
      return wrapper;
    } },
    { id: 'protocol', label: 'Protocol', render: (row) => protocolFamily(row) },
    { label: 'Variant / Model', render: (row) => {
      if (row.directory_type !== 'system' || isP25(row)) return '';
      return [...new Set([trunkedVariant(row), identityDomainLabel(row)].filter(Boolean))].join(' · ');
    } },
    { id: 'wacn', label: 'WACN / Net', fullLabel: 'WACN or Network', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ?
        (isP25(row) ? hex(row.wacn, 5) : identifierNumber(row.network_id)) : '' },
    { id: 'system', label: 'Sys ID', fullLabel: 'System ID', className: 'numeric', render: (row) => {
      if (row.directory_type !== 'system') return '';
      return isP25(row) ? hex(row.system_id, 3) : identifierNumber(row.system_id);
    } },
    { id: 'rfss', label: 'RFSS / RAN', className: 'numeric', render: (row) =>
      row.directory_type === 'site' ? (isP25(row) ? hex(row.rfss, 2) : identifierNumber(row.ran)) : '' },
    { id: 'site', label: 'Site', className: 'numeric', render: (row) =>
      row.directory_type === 'site' ? (isP25(row) ? hex(row.site_id, 2) : identifierNumber(row.site_id)) : '' },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz', className: 'numeric',
      render: (row) => row.directory_type === 'site' ? frequency(row.current_control_hz) : '' },
    { id: 'count', label: 'Sites / Ch', fullLabel: 'Sites or Channels', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? `${number(row.sites)} ${Number(row.sites) === 1 ? 'site' : 'sites'}` :
        `${number(row.channels)} ch` },
    { id: 'talkgroups', label: 'TGs', fullLabel: 'Talkgroups', className: 'numeric',
      render: (row) => row.directory_type === 'system' ? number(row.talkgroups) : '' },
    { id: 'patch-groups', label: 'Patches', fullLabel: 'Patch Groups', className: 'numeric',
      render: (row) => row.directory_type === 'system' ? number(row.patch_groups) : '' },
    { id: 'radios', label: 'Radios', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? number(row.radios) : '' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
  ];
  const directoryTable = table(rows, columns, 'No systems or sites recorded', {
    type: 'system-directory',
    sortable: false,
    rowClass: (row) => `directory-${row.directory_type}-row`
  });
  const rendered = [directoryTable];
  if (truncatedParentCount) rendered.push(node('div', 'directory-warning',
    `${number(truncatedParentCount)} system group${truncatedParentCount === 1 ? '' : 's'} exceeded the ` +
    `${number(previewLimit)}-site preview limit. Open the system for its complete paged site list.`));
  rendered.push(pager(page, 'bottom', 'Systems'));
  return fragment(...rendered);
}

async function renderSystems() {
  const renderContext = captureRenderContext();
  const directory = createAsyncSection('System Directory', {
    loadingMessage: 'Loading systems and sites…',
    errorMessage: 'The systems directory could not be loaded.'
  });
  if (!beginPage(renderContext,
    pageHeader('Systems & Sites', 'P25, DMR, and NXDN systems with their observed sites'),
    searchBar('Search protocol, system, site, name, or GUID'), directory.element)) return;
  await directory.load(
    () => window.sdrtrunkSystemsDirectory.load(apiPage, pageParameters()),
    systemsDirectoryContent,
    renderContext);
}

async function renderSystem() {
  const renderContext = captureRenderContext();
  const systemScope = requiredSystemScope();
  const response = await api(systemApiPath(systemScope.scope));
  if (!renderIsCurrent(renderContext)) return;
  const system = response;
  const requestedTab = route.get('tab') || 'info';
  const tabItems = systemTabItems(system);
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : 'info';
  if (tab !== requestedTab) {
    route.set('tab', tab);
    window.history.replaceState({}, '', currentHref());
  }
  if (!beginPage(renderContext,
    pageHeader(systemValue(system), system.site_names || `${protocolFamily(system)} trunked system`),
    systemTabs(system, tab))) return;

  if (tab === 'talkgroups') {
    const page = await apiPage(systemApiPath(systemScope.scope, 'group-identities'), pageParameters());
    content.append(pagedSection('Talkgroups', page, talkgroupColumns, 'Search talkgroup ID', 'talkgroups',
      exportCsvLink('system-talkgroups', systemScope), { topPager: true }));
  } else if (tab === 'radios') {
    const filters = affiliationRouteFilters();
    const page = await apiPage(systemApiPath(systemScope.scope, 'radios'), pageParameters(filters));
    const title = filters.site_guid ? (filters.affiliated ? 'Affiliated Radios at Site' : 'Radios at Site') :
      (filters.affiliated ? 'Affiliated Radios' : 'Radios');
    const exportAction = exportCsvLink('system-radios', { ...systemScope, ...filters });
    content.append(pagedSection(title, page, systemRadioColumns(system), 'Search radio ID', 'radios',
      affiliationFilterActions(exportAction), { topPager: true }));
  } else if (tab === 'talker-aliases') {
    const page = await apiPage(systemApiPath(systemScope.scope, 'talker-aliases'), pageParameters());
    const columns = [
    { id: 'radio', label: 'Radio', fullLabel: 'Radio ID', render: (row) => radioLink(row), className: 'numeric', sort: 'radio', sortValue: (row) => Number(row.radio_id) },
      { id: 'talker-alias', label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
      { id: 'radio-alias', label: 'Alias', fullLabel: 'Configured Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
      { id: 'talkgroup-id', label: 'Last TGID', render: (row) => talkgroupLink(row,
        row.last_talkgroup_id, undefined, row.last_talkgroup_kind), className: 'numeric',
        sort: 'last_talkgroup', sortValue: (row) => Number(row.last_talkgroup_id) },
      { id: 'talkgroup-name', label: 'TG Alias', fullLabel: 'Talkgroup Alias',
        render: (row) => talkgroupAliasLink(row, row.last_talkgroup_id, 'talkgroup_alias_',
          row.last_talkgroup_kind), className: 'alias-cell', sort: 'last_talkgroup_name',
        sortValue: (row) => row.talkgroup_alias_name || '' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Alias Seen', fullLabel: 'Talker Alias Last Seen', render: (row) => dateTime(row.last_talker_alias_seen_ms), sort: 'talker_alias_seen', sortValue: (row) => Number(row.last_talker_alias_seen_ms || 0) }
    ];
    const block = pagedSection('Talker Alias Summary', page, columns,
      'Search radio ID or talker alias', 'talker-aliases', null, { topPager: true });
    if (!page.rows.length) block.querySelector('.empty').textContent = 'No talker aliases recorded for this system';
    content.append(block);
  } else if (tab === 'activity') {
    await renderActivity(systemScope, 'System Activity');
  } else {
    const infoColumn = node('div', 'entity-info-column system-info-column');
    const blocks = [section('Directory', metrics([
      ['Known Sites', system.sites],
      ['Known Talkgroups', system.talkgroups],
      ['Known Patch Groups', system.patch_groups],
      ['Known Radios', system.radios]
    ], true)), section('Retained Call Activity', metrics([
      ['Calls', system.activity_retained_calls],
      ['Recorded', system.activity_recorded],
      ['Sent to Streamer', system.activity_streamed],
      ['Encrypted', system.activity_encrypted]
    ], true))];
    if (systemCapability(system, 'current_affiliations')) {
      blocks.push(section('Current State', metrics([
        ['Currently Affiliated', system.affiliated_radios]
      ], true)));
    }
    blocks.push(section('System Info', keyValues([
      ['System', systemInfoValue(system)],
      ['First Seen', dateTime(system.first_seen_ms)], ['Last Seen', dateTime(system.last_seen_ms)]
    ])), section('Retained Signaling Observations', fragment(table(
      signalingActionRows(response.action_counts), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No signaling observations recorded', { type: 'action-counts' }), activityMetricGuide())));
    infoColumn.append(...blocks);

    const sitesPage = await apiPage(systemApiPath(systemScope.scope, 'sites'), pageParameters());
    const sitesColumn = node('div', 'entity-info-column system-sites-column');
    sitesColumn.append(pagedSection('Sites', sitesPage, scopedSiteColumns,
      'Search site, name, or GUID', 'sites'));
    const layout = node('div', 'entity-info-layout system-info-layout');
    layout.append(infoColumn, sitesColumn);
    content.append(layout);
  }
}

async function renderTalkgroup() {
  const renderContext = captureRenderContext();
  const systemScope = requiredSystemScope();
  const id = requiredId();
  const kind = route.get('kind') === 'patch_group' ? 'patch_group' : 'talkgroup';
  const response = await api(groupIdentityApiPath(systemScope.scope, kind, id));
  const talkgroup = response;
  const tab = route.get('tab') || 'info';
  const formattedId = identityNumber(talkgroup, id);
  const kindLabel = kind === 'patch_group' ? 'Patch Group' : 'Talkgroup';
  const title = aliasLabel(talkgroup) || `${kindLabel} ${formattedId}`;
  if (!beginPage(renderContext,
    pageHeader(title, fragment(systemValue(talkgroup), ` · ${kindLabel} ${formattedId}`)),
    entityTabs('talkgroup', talkgroup, id, tab, false, kind))) return;

  if (tab === 'radios') {
    const currentAffiliations = kind === 'talkgroup' &&
      systemCapability(talkgroup, 'current_affiliations');
    const sitePresence = currentAffiliations && systemCapability(talkgroup, 'radio_site_presence');
    const affiliatedOnly = currentAffiliations && route.get('affiliated') === 'true';
    const relationships = await apiPage(systemApiPath(systemScope.scope, 'relationships'),
      pageParameters({ talkgroup_id: id, kind: kind === 'patch_group' ? 'patch_group' : null,
        affiliated: affiliatedOnly ? true : null }));
    const columns = [
      { id: 'radio', label: 'Radio', render: (row) => radioLink(row), className: 'numeric', sort: 'radio', sortValue: (row) => Number(row.radio_id) },
      { id: 'alias', label: 'Alias', render: (row) => row.radio_alias_name ? radioLink(row, row.radio_id, row.radio_alias_name) : '', className: 'alias-cell', sort: 'radio_alias', sortValue: (row) => row.radio_alias_name || '' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    if (systemCapability(talkgroup, 'talker_aliases')) {
      columns.splice(2, 0, { label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias',
        className: 'alias-cell', sort: 'talker_alias' });
    }
    if (sitePresence) {
      columns.splice(systemCapability(talkgroup, 'talker_aliases') ? 3 : 2, 0,
        { id: 'affiliated-site', label: 'Affiliated Site', fullLabel: 'Last Confirmed Affiliated Site',
          render: (row) => row.currently_affiliated === true ? sitePresenceCell(row) : '',
          className: 'alias-cell', sort: 'site', sortValue: (row) => row.currently_affiliated === true ?
            presenceSiteSortValue(row) : '' });
    }
    const action = currentAffiliations ? anchor(affiliatedOnly ? 'Clear Filter' : 'Show Affiliated',
      currentHref({ affiliated: affiliatedOnly ? null : true, offset: null }), 'button secondary') : null;
    content.append(pagedSection(affiliatedOnly ? 'Affiliated Radios' : 'Radios', relationships,
      columns, null, 'talkgroup-radios', action));
  } else if (tab === 'activity') {
    if (detailedHistoryAvailable()) {
      await renderActivity({ ...systemScope, talkgroup_id: id, kind }, 'Activity Log');
    } else {
      content.append(section('Activity Log', node('div', 'empty',
        'Detailed history logging is not running.')));
    }
  } else {
    const infoColumn = node('div', 'entity-info-column');
    const blocks = [section('Identity', keyValues([
      ['System', systemLink(talkgroup, systemInfoValue(talkgroup))],
      [kind === 'patch_group' ? 'Patch Group ID' : 'Talkgroup ID', formattedId],
      ['Alias', aliasLabel(talkgroup)],
      ['Description', talkgroup.alias_description],
      ['Group', talkgroup.alias_group]
    ])), section('Collected Call Activity', metrics([
      ['Calls', talkgroup.call_count],
      ['Recorded', talkgroup.recorded_count],
      ['Sent to Streamer', talkgroup.streamed_count],
      ['Encrypted', talkgroup.encrypted_count]
    ], true)), section('Relationships', metrics([
      ['Observed Radios', talkgroup.radios]
    ], true))];
    if (kind === 'talkgroup' && systemCapability(talkgroup, 'current_affiliations')) {
      const currentState = [
        ['Currently Affiliated', anchor(number(talkgroup.affiliated_radios),
          href('talkgroup', { ...scope(talkgroup), id, tab: 'radios', affiliated: true }))]
      ];
      if (systemCapability(talkgroup, 'radio_site_presence')) {
        currentState.push(['Affiliated Sites', number(talkgroup.affiliated_sites)]);
      }
      blocks.push(section('Current State', keyValues(currentState)));
    }
    blocks.push(section('Last-known Facts', keyValues([
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
    infoColumn.append(...blocks);
    const layout = node('div', 'entity-info-layout');
    const activityHistory = await talkgroupActivityHistorySection({ ...systemScope, talkgroup_id: id, kind });
    if (!renderIsCurrent(renderContext)) return;
    layout.append(infoColumn, activityHistory);
    content.append(layout);
  }
}

async function renderRadio() {
  const renderContext = captureRenderContext();
  const systemScope = requiredSystemScope();
  const id = requiredId();
  const response = await api(`${systemApiPath(systemScope.scope, 'radios')}/${encodeURIComponent(String(id))}`);
  const radio = response;
  const tab = route.get('tab') || 'info';
  const formattedId = identityNumber(radio, id);
  const title = aliasLabel(radio) || radio.last_talker_alias || `Radio ${formattedId}`;
  if (!beginPage(renderContext,
    pageHeader(title, fragment(systemValue(radio), ` · Radio ${formattedId}`)),
    entityTabs('radio', radio, id, tab, true))) return;

  if (tab === 'talkgroups') {
    const relationships = await apiPage(systemApiPath(systemScope.scope, 'relationships'),
      pageParameters({ radio_id: id }));
    const columns = [
      { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sort: 'talkgroup', sortValue: (row) => Number(row.talkgroup_id) },
      { id: 'talkgroup-kind', label: 'Kind', render: (row) => groupIdentityLabel(row) },
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
    const identityValues = [
      ['System', systemLink(radio, systemInfoValue(radio))],
      ['Radio ID', formattedId],
      ['Alias', aliasLabel(radio)]
    ];
    if (systemCapability(radio, 'talker_aliases')) {
      identityValues.push(['Talker Alias', radio.last_talker_alias]);
    }
    const blocks = [section('Identity', keyValues(identityValues)), section('Collected Call Activity', metrics([
      ['Calls', radio.call_count],
      ['Recorded', radio.recorded_count],
      ['Sent to Streamer', radio.streamed_count],
      ['Encrypted', radio.encrypted_count]
    ], true))];
    if (systemCapability(radio, 'current_affiliations')) {
      blocks.push(section('Current Affiliation', keyValues([
        ['Talkgroup ID', talkgroupLink(radio, radio.affiliated_talkgroup_id)],
        ['Talkgroup Alias', talkgroupAliasLink(radio, radio.affiliated_talkgroup_id,
          'affiliated_talkgroup_alias_')],
        ['Affiliation Confirmed', dateTime(radio.affiliation_confirmed_at_ms)]
      ])));
    }
    if (systemCapability(radio, 'radio_site_presence')) {
      const presence = authoritativePresence(radio);
      blocks.push(section('Last Confirmed Site', keyValues([
        ['Site', sitePresenceCell(radio, false)],
        ['Confirmed', presence ? dateTime(presence.confirmed_at_ms) : '—']
      ])));
    }
    blocks.push(section('Relationships', metrics([
      ['Observed Talkgroups', radio.talkgroups]
    ])), section('Last-known Facts', keyValues([
      ['Last Talkgroup', talkgroupLink(radio, radio.last_talkgroup_id, undefined,
        radio.last_talkgroup_kind)],
      ['Talkgroup Alias', talkgroupAliasLink(radio, radio.last_talkgroup_id,
        'last_talkgroup_alias_', radio.last_talkgroup_kind)],
      ['Last Peer Radio', radioLink(radio, radio.last_peer_radio_id)],
      ['Peer Alias', radio.last_peer_alias_name ?
        radioLink(radio, radio.last_peer_radio_id, radio.last_peer_alias_name) : ''],
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
    infoColumn.append(...blocks);
    content.append(infoColumn);
  }
}

function siteIdentity(site) {
  if (!isP25(site)) return trunkedIdentity(site);
  return [
    site.wacn == null ? '' : `WACN ${hex(site.wacn, 5)}`,
    site.system_id == null ? '' : `System ${hex(site.system_id, 3)}`,
    site.rfss == null ? '' : `RFSS ${hex(site.rfss, 2)}`,
    site.site_id == null ? '' : `Site ${hex(site.site_id, 2)}`
  ].filter(Boolean).join(' · ');
}

function p25DecoderMode(value) {
  return ({ C4FM: 'Normal (C4FM)', CQPSK: 'Simulcast (LSM / CQPSK)' })[
    String(value || '').trim().toUpperCase()] || availableValue(value);
}

function p25SiteDetailRows(site) {
  return [
    ['Callsign', callsignLink(site.callsign)], ['WACN', hexDecimalPair(site.wacn, 5)],
    ['SysID', hexDecimalPair(site.system_id, 3)], ['NAC', hexDecimalPair(site.nac, 3)],
    ['RFSS', hexDecimalPair(site.rfss, 2)], ['Site', hexDecimalPair(site.site_id, 2)],
    ['Local Registration Area', hexDecimalPair(site.lra, 2)],
    ['Active RFSS Network Connection', yesNoKnown(site.active_rfss_network_connection)],
    ['Manufacturer', site.mfid_display],
    ['Configured Decoder Mode', p25DecoderMode(site.p25_decoder_mode)],
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
    ['RAN', identifierNumber(site.ran)], ['Brand', semanticLabel(site.brand)],
    ['Model', semanticLabel(site.model)], ['Mode', semanticLabel(site.mode)],
    ['Channel Type', semanticLabel(site.channel_type)],
    ['Color Code TS1', identifierNumber(site.color_code_ts1)],
    ['Color Code TS2', identifierNumber(site.color_code_ts2)]
  ];
}

function nxdnSiteDetailRows(site) {
  return [
    ['Variant', trunkedVariant(site)], ['Network', identifierNumber(site.network_id)],
    ['System', identifierNumber(site.system_id)], ['Site', identifierNumber(site.site_id)],
    ['RAN', identifierNumber(site.ran)], ['Category', identityDomainLabel(site)],
    ['Repeater State', semanticLabel(site.repeater_state)],
    ['Current Repeater', identifierNumber(site.current_repeater)],
    ['Services', (site.services || []).map(semanticLabel).join(', ')],
    ['Failure Call Timer', Object.hasOwn(site, 'failure_call_timer_seconds') ?
      (site.failure_call_timer_seconds == null ? 'Unspecified' :
        `${number(site.failure_call_timer_seconds)} seconds`) : '']
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
    { label: 'Use', render: (row) => trunkedChannelUse(row.roles) },
    { label: 'Source', render: (row) => trunkedChannelSources(row.sources) },
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

async function renderSiteChannels(site, renderContext) {
  const p25 = isP25(site);
  const directory = createAsyncSection('Channels', {
    action: exportCsvLink('site-channels', { guid: site.guid }),
    loadingMessage: 'Loading site channels…',
    errorMessage: 'The site channels could not be loaded.'
  });
  if (!renderIsCurrent(renderContext)) return;
  content.append(directory.element);
  await directory.load(
    () => apiPage(siteApiPath(site.guid, 'channels'), pageParameters()),
    (page) => fragment(
      protocolFamily(site) === 'DMR' ? node('p', 'muted',
        'DMR grants usually identify an LCN and timeslot. Frequencies marked LCN Map were resolved from the configured map; OTA Freq means the system broadcast an absolute frequency.') : null,
      pagedTableContent(page, p25 ? p25SiteChannelColumns() : trunkedSiteChannelColumns(),
        p25 ? 'site-channels' : 'trunked-site-channels', {
          itemLabel: 'Channels', emptyText: 'No channels recorded',
          tableOptions: { sortable: false, serverSort: false }
        })),
    renderContext);
}

function p25SiteNeighborColumns() {
  return [
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state),
      sortValue: (row) => row.state || '' },
    { id: 'type', label: 'Type', render: (row) => row.entry_type === 'ISSI' ? 'ISSI System' : 'Site' },
    { id: 'neighbor-name', label: 'Name / Site', fullLabel: 'Monitored Name and Site',
      render: neighborSiteLink,
      sortValue: (row) => neighborSiteDisplayParts(row).primary },
    { id: 'wacn', label: 'WACN', render: (row) => hex(row.wacn, 5),
      sortValue: (row) => Number(row.wacn || 0) },
    { id: 'system', label: 'Sys', fullLabel: 'System', render: (row) => hex(row.system_id, 3),
      sortValue: (row) => Number(row.system_id || 0) },
    { id: 'rfss', label: 'RFSS', render: (row) => hex(row.rfss, 2),
      sortValue: (row) => Number(row.rfss || 0) },
    { id: 'site', label: 'Site', render: (row) => hex(neighborSiteId(row), 2),
      sortValue: (row) => Number(neighborSiteId(row) || 0) },
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
      protocol: site.protocol,
      variant: row.variant
    }) },
    { label: 'Model / Category', render: (row) => identityDomainLabel({
      protocol: site.protocol,
      address_domain: row.address_domain,
      model: row.model,
      location_category: row.location_category
    }) },
    { id: 'neighbor-name', label: 'Name / Site', fullLabel: 'Monitored Name and Site',
      render: neighborSiteLink,
      sortValue: (row) => neighborSiteDisplayParts(row).primary },
    { label: 'Network', key: 'network_id', className: 'numeric',
      render: (row) => identifierNumber(row.network_id) },
    { label: 'System', key: 'system_id', className: 'numeric',
      render: (row) => identifierNumber(row.system_id) },
    { label: 'Site', key: 'site_id', className: 'numeric',
      render: (row) => identifierNumber(neighborSiteId(row)) },
    { label: 'Channel', key: 'channel_number', className: 'numeric',
      render: (row) => identifierNumber(row.channel_number) },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric' },
    { label: 'Status', render: (row) => trunkedNeighborStatus(row.statuses) },
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state) },
    { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms) }
  ];
}

async function renderSiteNeighbors(site, renderContext) {
  const p25 = isP25(site);
  const directory = createAsyncSection('Neighbors', {
    action: exportCsvLink('site-neighbors', { guid: site.guid }),
    loadingMessage: 'Loading site neighbors…',
    errorMessage: 'The site neighbors could not be loaded.'
  });
  if (!renderIsCurrent(renderContext)) return;
  content.append(directory.element);
  await directory.load(
    () => apiPage(siteApiPath(site.guid, 'neighbors'), pageParameters()),
    (page) => pagedTableContent(page,
      p25 ? p25SiteNeighborColumns() : trunkedSiteNeighborColumns(site),
      p25 ? 'site-neighbors' : 'trunked-site-neighbors', {
        itemLabel: 'Neighbors', emptyText: 'No neighbors recorded',
        tableOptions: { sortable: false, serverSort: false }
      }),
    renderContext);
}

async function renderSiteInfo(site, renderContext) {
  const summary = [
    ['Metadata Updates', site.observation_count], ['Channels', site.channels], ['Neighbors', site.neighbors]
  ];
  if (siteCapability(site, 'frequency_bands')) summary.push(['Band Plans', site.bands]);
  if (siteCapability(site, 'patch_groups')) summary.push(['Patches', site.patches]);
  if (siteCapability(site, 'current_affiliations') && siteCapability(site, 'radio_site_presence')) {
    const label = number(site.affiliated_radios);
    const linked = site.scope_token && site.guid ? anchor(label,
      href('system', { ...scope(site), tab: 'radios', affiliated: true, site_guid: site.guid })) : label;
    summary.push(['Affiliated Radios', site.affiliated_radios, linked]);
  }

  const infoColumn = node('div', 'entity-info-column');
  infoColumn.append(section('Site Info', keyValues([
    ['System', systemLink(site, systemInfoValue(site))],
    ['Site', siteInfoSiteValue(site)], ['Name', configuredNameValue(site)],
    ['GUID', site.guid],
    ['Alias List', aliasListLink(site.alias_list_name, site.alias_list_id)],
    ['Protocol', protocolFamily(site)], ['Decoder', decoderDisplay(site.decoder)],
    ['Configured Frequency', frequency(site.primary_frequency_hz)],
    ['Current Control Frequency', frequency(site.current_control_hz)],
    ['First Seen', dateTime(site.first_seen_ms)], ['Last Seen', dateTime(site.last_seen_ms)]
  ])));
  const protocolDetails = siteProtocolDetailRows(site);
  if (protocolDetails.length) {
    infoColumn.append(section(`${protocolFamily(site)} Details`, keyValues(protocolDetails)));
  }

  const activityColumn = node('div', 'entity-info-column');
  if (siteCapability(site, 'group_identities')) {
    activityColumn.append(await siteTopTalkgroupsSection(site));
  }
  if (!renderIsCurrent(renderContext)) return;
  const layout = node('div', 'entity-info-layout');
  layout.append(infoColumn);
  if (activityColumn.childNodes.length) layout.append(activityColumn);
  content.append(metrics(summary), layout);
}

async function renderSite() {
  const renderContext = captureRenderContext();
  const guid = route.get('guid');
  if (!guid) throw new Error('Site GUID is missing from the URL');
  const response = await api(siteApiPath(guid));
  const site = response;
  const requestedTab = route.get('tab') || 'info';
  const tabItems = siteTabItems(site);
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : 'info';
  const display = siteDisplayParts(site);
  const subtitle = [display.secondary, protocolFamily(site), trunkedVariant(site), siteIdentity(site)]
    .filter(Boolean).join(' · ');
  if (!beginPage(renderContext, pageHeader(siteValue(site), subtitle), siteTabs(site, tab))) return;

  if (tab === 'quality') {
    const signalHistory = await siteSignalHistorySection(site);
    if (!renderIsCurrent(renderContext)) return;
    content.append(signalHistory);
  } else if (tab === 'channels') {
    await renderSiteChannels(site, renderContext);
  } else if (tab === 'neighbors') {
    await renderSiteNeighbors(site, renderContext);
  } else if (tab === 'band-plan') {
    const data = await api(siteApiPath(guid, 'frequency-bands'));
    content.append(section('Home System Band Plan', table(data.home_bands || [], [
      { label: 'Band', key: 'band', className: 'numeric' },
      { id: 'base', label: 'Base', fullLabel: 'Base MHz', render: (row) => frequency(row.base_hz), className: 'numeric', sortValue: (row) => Number(row.base_hz || 0) },
      { id: 'spacing', label: 'Space', fullLabel: 'Spacing kHz', render: (row) => row.spacing_hz ? (row.spacing_hz / 1000).toFixed(3) : '', className: 'numeric', sortValue: (row) => Number(row.spacing_hz || 0) },
      { label: 'BW Hz', fullLabel: 'Bandwidth Hz', key: 'bandwidth_hz', className: 'numeric' },
      { id: 'offset', label: 'Offset', fullLabel: 'Offset MHz', render: (row) => row.transmit_offset_hz ? (row.transmit_offset_hz / 1000000).toFixed(5) : '', className: 'numeric', sortValue: (row) => Number(row.transmit_offset_hz || 0) },
      { id: 'tdma', label: 'TDMA', render: (row) => yesNo(row.tdma), sortValue: (row) => Boolean(row.tdma) },
      { label: 'Slots', key: 'timeslots', className: 'numeric' },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No home-system band plan recorded', { type: 'site-bands' })));
    content.append(section('ISSI Advertised Band Plans', table(data.foreign_bands || [], [
      { id: 'wacn', label: 'WACN', render: (row) => hex(row.foreign_wacn, 5), sortValue: (row) => Number(row.foreign_wacn || 0) },
      { id: 'system', label: 'Sys', fullLabel: 'Foreign System', render: (row) => hex(row.foreign_system_id, 3), sortValue: (row) => Number(row.foreign_system_id || 0) },
      { label: 'Band', key: 'band', className: 'numeric' },
      { id: 'mode', label: 'Mode', render: (row) => semanticLabel(row.access_mode) },
      { id: 'base', label: 'Base', fullLabel: 'Base MHz', render: (row) => frequency(row.base_hz), className: 'numeric', sortValue: (row) => Number(row.base_hz || 0) },
      { id: 'spacing', label: 'Space', fullLabel: 'Spacing kHz', render: (row) => row.spacing_hz ? (row.spacing_hz / 1000).toFixed(3) : '', className: 'numeric', sortValue: (row) => Number(row.spacing_hz || 0) },
      { id: 'bandwidth', label: 'BW Hz', fullLabel: 'Bandwidth Hz', key: 'bandwidth_hz', className: 'numeric' },
      { id: 'offset', label: 'Offset', fullLabel: 'Offset MHz', render: (row) => row.transmit_offset_hz ? (row.transmit_offset_hz / 1000000).toFixed(5) : '', className: 'numeric', sortValue: (row) => Number(row.transmit_offset_hz || 0) },
      { id: 'slots', label: 'Slots', key: 'timeslots', className: 'numeric' },
      { id: 'voice-rate', label: 'Voice Rate', render: (row) => semanticLabel(row.voice_rate) },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { label: 'Obs', fullLabel: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No ISSI-advertised band plans recorded', { type: 'site-foreign-bands' })));
  } else if (tab === 'patches') {
    const data = await api(siteApiPath(guid, 'patch-groups'));
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
      { id: 'patch-id', label: 'Patch', fullLabel: 'Patch Talkgroup ID',
        render: (row) => talkgroupLink(site, row.patch_group, undefined, 'patch_group'),
        className: 'numeric', sortValue: (row) => Number(row.patch_group) },
      { id: 'patch-name', label: 'Alias', fullLabel: 'Patch Alias', render: (row) => row.patch_alias_name ?
        talkgroupLink(site, row.patch_group, row.patch_alias_name, 'patch_group') : '',
        className: 'alias-cell', sortValue: (row) => row.patch_alias_name || '' },
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
    await renderSiteInfo(site, renderContext);
  }
}

function specialIdentifierLabel(row, value, kind) {
  const identifier = Number(value);
  if (isP25(row)) {
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
  }

  // Mirrors Tier3Gateway: these values name network services or broadcast destinations, not subscriber identities.
  if (protocolFamily(row) === 'DMR') {
    return ({
      0x000000: 'Reserved',
      0xFFFEC0: 'PSTN Gateway',
      0xFFFEC1: 'PABX Gateway',
      0xFFFEC2: 'Line Gateway',
      0xFFFEC3: 'IP Gateway',
      0xFFFEC4: 'Supplementary Data Service',
      0xFFFEC5: 'UDT Short Data Service',
      0xFFFEC6: 'Registration Service',
      0xFFFEC7: 'Call Diversion to Radio Gateway',
      0xFFFEC9: 'Call Diversion Cancellation',
      0xFFFECA: 'Trunking System Controller',
      0xFFFECB: 'System Dispatcher',
      0xFFFECC: 'Radio Stun/Revive',
      0xFFFECD: 'Authentication',
      0xFFFECE: 'Call Diversion to Talkgroup Gateway',
      0xFFFECF: 'Radio Kill',
      0xFFFED0: 'PSTN-D Gateway',
      0xFFFED1: 'PABX-D Gateway',
      0xFFFED2: 'Line-D Gateway',
      0xFFFED3: 'System Dispatcher-D',
      0xFFFED4: 'All Radios/Talkgroups',
      0xFFFED5: 'IP-D Gateway',
      0xFFFED6: 'Dynamic Group Number Assignment',
      0xFFFED7: 'Talkgroup Subscribe/Attach Service',
      0xFFFFFD: 'All Radios at Site',
      0xFFFFFE: 'All Radios in Zone',
      0xFFFFFF: 'All Radios in System'
    })[identifier] || '';
  }

  // Mirrors NXDNRadioIdentifier/NXDNTalkgroupIdentifier. Type-D uses the same bits as a real HH-NNNN identity.
  if (protocolFamily(row) === 'NXDN' && row.address_domain !== 'nxdn_type_d') {
    if (kind === 'talkgroup') {
      return ({
        0x0000: 'Null Group',
        0xFFF0: 'Reserved Group',
        0xFFFF: 'All Groups'
      })[identifier] || '';
    }
    if (kind === 'radio') {
      return ({
        0x0000: 'No Unit',
        0xFFF0: 'Trunking Controller',
        0xFFF1: 'PSTN',
        0xFFF2: 'Special ID',
        0xFFF3: 'Special ID',
        0xFFF4: 'Special ID',
        0xFFF5: 'Conventional PSTN',
        0xFFFF: 'All Units'
      })[identifier] || '';
    }
  }

  return '';
}

function activityIdentifier(row, value, kind) {
  const identifier = identityNumber(row, value);
  if (!identifier) return '';
  const specialLabel = specialIdentifierLabel(row, value, kind);
  if (specialLabel) {
    const protocol = protocolFamily(row);
    const result = node('span', 'special-identifier', specialLabel);
    result.title = `${protocol} ${specialLabel} (${identifier}): system or special signaling identifier`;
    result.setAttribute('aria-label',
      `${specialLabel}, ${protocol} system or special signaling identifier ${identifier}`);
    return result;
  }
  if (kind === 'talkgroup') return talkgroupLink(row, value, identifier);
  if (kind === 'radio') return radioLink(row, value, identifier);
  return identifier;
}

function activityTargetIdentifier(row) {
  const targetKind = identityKind(row.target_kind);
  const kind = targetKind === 'radio' ? 'radio' :
    ['talkgroup', 'patch_group'].includes(targetKind) ? 'talkgroup' : '';
  return activityIdentifier(row, row.target_id, kind);
}

function activitySourceAlias(row) {
  const alias = row.source_alias_name || '';
  if (!alias) return '';
  return specialIdentifierLabel(row, row.source_radio_id, 'radio') ?
    alias : radioLink(row, row.source_radio_id, alias);
}

function activityTargetAlias(row) {
  const alias = row.target_alias_name || '';
  if (!alias) return '';
  const targetKind = identityKind(row.target_kind);
  const kind = targetKind === 'radio' ? 'radio' :
    ['talkgroup', 'patch_group'].includes(targetKind) ? 'talkgroup' : '';
  if (specialIdentifierLabel(row, row.target_id, kind)) return alias;
  if (kind === 'talkgroup') return talkgroupLink(row, row.target_id, alias);
  if (kind === 'radio') return radioLink(row, row.target_id, alias);
  return alias;
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
    { id: 'target-alias', label: 'Tgt Alias', fullLabel: 'Target Alias', render: activityTargetAlias,
      className: 'alias-cell', sortValue: (row) => row.target_alias_name || '' },
    { id: 'frequency', label: 'MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sortValue: (row) => Number(row.frequency_hz || 0) },
    { label: 'LCN', key: 'lcn' },
    { label: 'Slot', render: (row) => identifierNumber(row.timeslot), className: 'numeric' },
    { id: 'encryption', label: 'Enc', fullLabel: 'Encryption', render: encryptionActivityValue,
      className: 'encrypted', sortValue: (row) => row.encryption_display || (row.encrypted ? 'ENC' : '') }
  ];
}

async function renderActivity(scopeParameters, title = 'Activity') {
  const renderContext = captureRenderContext();
  if (!detailedHistoryAvailable()) {
    if (renderIsCurrent(renderContext)) {
      content.append(section(title, node('div', 'empty', 'Detailed history logging is not running.')));
    }
    return;
  }
  const data = await api('/api/v1/activity', {
    ...scopeParameters,
    before_id: route.get('before_id'),
    hide_grants: true,
    limit: 200
  });
  if (!renderIsCurrent(renderContext)) return;
  const columns = activityColumns();
  const initialRows = withoutGrantActions(data.rows);
  const activityTable = table(initialRows, columns, 'No activity recorded',
    { type: 'activity', rowKey: (row) => row.id });
  activityTable.setAttribute('aria-live', 'off');
  const block = section(title, activityTable);
  const controls = node('div', 'pager');
  let newestControl = null;
  let olderControl = null;
  const pagerControl = (current, enabled, label, target) => {
    if (enabled && current?.tagName === 'A') {
      current.setAttribute('href', target);
      return current;
    }
    if (!enabled && current?.tagName === 'SPAN' && current.classList.contains('disabled')) return current;
    return enabled ? anchor(label, target, 'button secondary') : node('span', 'button disabled', label);
  };
  const updatePager = (page) => {
    const nextNewest = pagerControl(newestControl, Boolean(route.get('before_id')), 'Newest',
      currentHref({ before_id: null }));
    const nextOlder = pagerControl(olderControl, Boolean(page?.has_more), 'Older',
      currentHref({ before_id: page?.next_before_id }));
    if (nextNewest !== newestControl) {
      if (newestControl) newestControl.replaceWith(nextNewest);
      else controls.append(nextNewest);
      newestControl = nextNewest;
    }
    if (nextOlder !== olderControl) {
      if (olderControl) olderControl.replaceWith(nextOlder);
      else controls.append(nextOlder);
      olderControl = nextOlder;
    }
  };
  updatePager(data);
  block.append(controls);
  content.append(block);

  if (!route.get('before_id')) {
    const titleBar = block.querySelector('.section-title');
    const refreshControls = node('div', 'section-title-actions activity-refresh-controls');
    const countdown = node('span', 'activity-refresh-countdown');
    countdown.setAttribute('role', 'timer');
    countdown.setAttribute('aria-live', 'off');
    const announcement = node('span', 'visually-hidden');
    announcement.setAttribute('role', 'status');
    announcement.setAttribute('aria-live', 'polite');
    const pause = node('button', 'button secondary', 'Pause refresh');
    pause.type = 'button';
    pause.setAttribute('aria-pressed', 'false');
    refreshControls.append(countdown, pause, announcement);
    titleBar.append(refreshControls);
    let paused = false;
    let refreshInFlight = false;
    let refreshFailed = false;
    let refreshGeneration = 0;
    let nextRefreshAt = Date.now() + ACTIVITY_REFRESH_INTERVAL_MILLISECONDS;
    const activityRowKey = (row) => row?.id === null || row?.id === undefined ? null : String(row.id);
    const updateRefreshDisplay = () => {
      const seconds = Math.max(0, Math.ceil((nextRefreshAt - Date.now()) / 1000));
      countdown.textContent = paused ? 'Refresh paused' : refreshInFlight ? 'Refreshing…' :
        `${refreshFailed ? 'Retry' : 'Refresh'} in ${seconds}s`;
      countdown.classList.toggle('error', refreshFailed && !refreshInFlight);
      pause.textContent = paused ? 'Resume refresh' : 'Pause refresh';
      pause.setAttribute('aria-pressed', String(paused));
    };
    const highlightActivityRows = (rowIds) => {
      if (!rowIds.size) return;
      const highlighted = [];
      activityTable.querySelectorAll('tbody tr[data-id]').forEach((row) => {
        if (rowIds.has(row.dataset.id)) {
          row.classList.add('activity-row-new');
          highlighted.push(row);
        }
      });
      pageTimeout(() => highlighted.forEach((row) => row.classList.remove('activity-row-new')), 8_000);
    };
    const refreshActivity = async () => {
      if (paused || refreshInFlight || document.hidden || !renderIsCurrent(renderContext) ||
          !block.isConnected) return;
      const generation = refreshGeneration;
      refreshInFlight = true;
      updateRefreshDisplay();
      try {
        const refreshed = await api('/api/v1/activity', {
          ...scopeParameters,
          hide_grants: true,
          limit: 200
        });
        if (generation !== refreshGeneration || paused || document.hidden || !renderIsCurrent(renderContext) ||
            !block.isConnected) return;
        const rows = withoutGrantActions(refreshed.rows);
        const currentRows = activityTable.tableController.rows();
        const currentIds = new Set(currentRows.map(activityRowKey).filter((key) => key !== null));
        const newIds = new Set(rows.map(activityRowKey)
          .filter((key) => key !== null && !currentIds.has(key)));
        if (JSON.stringify(rows) !== JSON.stringify(currentRows)) {
          activityTable.tableController.replaceRows(rows);
          highlightActivityRows(newIds);
        }
        updatePager(refreshed);
        if (newIds.size) {
          announcement.textContent = `${number(newIds.size)} new activity entr${newIds.size === 1 ? 'y' : 'ies'}.`;
        } else if (refreshFailed) announcement.textContent = 'Activity refresh recovered.';
        else announcement.textContent = '';
        refreshFailed = false;
      } catch (error) {
        if (generation !== refreshGeneration || document.hidden || !renderIsCurrent(renderContext) ||
            !block.isConnected || error?.name === 'AbortError') return;
        refreshFailed = true;
        announcement.textContent = 'Activity refresh failed. The current entries were retained; retrying automatically.';
      } finally {
        refreshInFlight = false;
        if (!renderIsCurrent(renderContext) || !block.isConnected) return;
        nextRefreshAt = generation === refreshGeneration && !paused && !document.hidden ?
          Date.now() + ACTIVITY_REFRESH_INTERVAL_MILLISECONDS : Date.now();
        updateRefreshDisplay();
      }
    };
    pause.addEventListener('click', () => {
      paused = !paused;
      refreshGeneration += 1;
      nextRefreshAt = paused ? nextRefreshAt : Date.now();
      updateRefreshDisplay();
      if (!paused && !refreshInFlight) void refreshActivity();
    });
    const refreshTick = () => {
      if (!renderIsCurrent(renderContext) || !block.isConnected) return;
      updateRefreshDisplay();
      if (!paused && !refreshInFlight && !document.hidden && Date.now() >= nextRefreshAt) {
        void refreshActivity();
      }
    };
    updateRefreshDisplay();
    pageInterval(refreshTick, 1_000);
  }
}

function conventionalColumns() {
  return [
    { label: 'Name', render: (row) => anchor(row.channel_name || row.context_key,
      href('conventional-detail', { context: row.context_key, tab: 'info' })), className: 'alias-cell', sort: 'name', sortValue: (row) => row.channel_name || row.context_key },
    { id: 'protocol', label: 'Protocol', render: protocolFamily, sort: 'protocol', sortValue: protocolFamily },
    { label: 'Decoder', render: (row) => decoderDisplay(row.decoder), sort: 'decoder',
      sortValue: (row) => decoderLabel(row.decoder, true) },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz', render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency', sortValue: (row) => Number(row.frequency_hz || 0) },
    { label: 'Slot', key: 'timeslot', className: 'numeric', sort: 'slot',
      render: (row) => identifierNumber(row.timeslot) },
    { id: 'nac', label: 'NAC', render: (row) => hex(row.nac, 3), sort: 'nac', sortValue: (row) => Number(row.nac || 0) },
    { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

async function renderConventional() {
  const renderContext = captureRenderContext();
  const directory = createAsyncSection('Conventional Channels', {
    action: exportCsvLink('conventional-channels'),
    loadingMessage: 'Loading conventional channels…',
    errorMessage: 'The conventional channel directory could not be loaded.'
  });
  if (!beginPage(renderContext,
    pageHeader('Conventional', 'Started conventional analog and digital channel summaries'),
    searchBar('Search name or frequency'), directory.element)) return;
  await directory.load(
    () => apiPage('/api/v1/conventional-contexts', pageParameters()),
    (page) => pagedTableContent(page, conventionalColumns(), 'conventional', { itemLabel: 'Channels' }),
    renderContext);
}

function conventionalCapability(context, capability) {
  return Boolean(context?.capabilities?.[capability]);
}

function conventionalTabItems(context) {
  const values = { context: context.context_key };
  const items = [];
  items.push({ id: 'info', label: 'Info',
    href: href('conventional-detail', { ...values, tab: 'info' }) });
  if (conventionalCapability(context, 'group_identities')) {
    items.push({ id: 'talkgroups', label: 'Talkgroups',
      href: href('conventional-detail', { ...values, tab: 'talkgroups' }) });
  }
  if (conventionalCapability(context, 'radios')) {
    items.push({ id: 'radios', label: 'Radios',
      href: href('conventional-detail', { ...values, tab: 'radios' }) });
  }
  if (conventionalCapability(context, 'activity') && capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)) {
    items.push({ id: 'activity', label: 'Activity',
      href: href('conventional-detail', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' });
  }
  return items;
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
  const page = await apiPage(conventionalApiPath(contextKey, 'talkgroups'), pageParameters({
    limit: CONVENTIONAL_IDENTITY_PAGE_LIMIT
  }));
  content.append(pagedSection('Talkgroups', page, conventionalTalkgroupColumns(),
    'Search talkgroup ID or alias', 'conventional-talkgroups',
    exportCsvLink('conventional-talkgroups', { context: contextKey })));
}

async function renderConventionalRadios(contextKey) {
  const page = await apiPage(conventionalApiPath(contextKey, 'radios'), pageParameters({
    limit: CONVENTIONAL_IDENTITY_PAGE_LIMIT
  }));
  content.append(pagedSection('Radios', page, conventionalRadioColumns(),
    'Search radio ID or alias', 'conventional-radios',
    exportCsvLink('conventional-radios', { context: contextKey })));
}

async function renderConventionalDetail() {
  const renderContext = captureRenderContext();
  const contextKey = route.get('context');
  if (!contextKey) throw new Error('Conventional context is missing from the URL');
  const data = await api(conventionalApiPath(contextKey));
  const context = data.context;
  const tabItems = conventionalTabItems(context);
  const requestedTab = route.get('tab') || 'info';
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : tabItems[0].id;
  if (!beginPage(renderContext,
    pageHeader(context.channel_name || context.context_key, protocolFamily(context)),
    tabs(tabItems, tab))) return;

  if (tab === 'activity') {
    await renderActivity({ context: contextKey });
  } else if (tab === 'talkgroups') {
    await renderConventionalTalkgroups(contextKey);
  } else if (tab === 'radios') {
    await renderConventionalRadios(contextKey);
  } else {
    content.append(section('Channel Info', keyValues([
      ['Name', context.channel_name], ['Context', context.context_key], ['GUID', context.guid],
      ['Protocol', protocolFamily(context)], ['Decoder', decoderDisplay(context.decoder)],
      ['Alias List', aliasListLink(context.alias_list_name, context.alias_list_id)],
      ['Frequency', frequency(context.primary_frequency_hz)],
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

function adminUserRecord(value) {
  return {
    username: String(value?.username || '').trim(),
    tier: accessTierFromWire(value?.tier) || 'PUBLIC',
    passwordChangedAtEpochMillis: Number(value?.password_changed_at_epoch_millis || 0),
    credentialVersion: Number(value?.credential_version || 0),
    primaryAdmin: value?.primary === true
  };
}

function adminUserEndpoint(username) {
  return `/api/v1/admin/users/${encodeURIComponent(username)}`;
}

function adminStatusMessage(host, message, error = false) {
  if (!host) return;
  host.textContent = message || '';
  host.classList.toggle('has-error', error);
}

function userIdentityCell(account) {
  const wrapper = node('div', 'admin-user-identity');
  wrapper.append(node('strong', '', account.username));
  if (account.primaryAdmin) wrapper.append(badge('Primary', 'state-current',
    'Primary administrator managed from the JavaFX interface'));
  return wrapper;
}

function userTierControl(account, statusHost) {
  if (account.primaryAdmin) {
    const locked = node('span', 'admin-tier-locked', 'Admin');
    locked.title = 'The primary administrator is managed from the JavaFX interface.';
    return locked;
  }
  const select = node('select', 'admin-tier-select');
  select.setAttribute('aria-label', `Access tier for ${account.username}`);
  ['USER', 'ADMIN'].forEach((tier) => {
    const option = node('option', '', accessTierLabel(tier));
    option.value = tier;
    option.selected = tier === account.tier;
    select.append(option);
  });
  select.addEventListener('change', async () => {
    const previous = account.tier;
    const requested = accessTierValue(select.value);
    select.disabled = true;
    adminStatusMessage(statusHost, `Updating ${account.username}…`);
    try {
      await requestJson(adminUserEndpoint(account.username), {
        method: 'PUT', body: { tier: accessTierToWire(requested) }
      });
      account.tier = requested;
      adminStatusMessage(statusHost, `${account.username} now has ${accessTierLabel(requested)} access.`);
      await refreshAccessSession(false);
      if (!viewAllowed('admin')) await render();
    } catch (error) {
      select.value = previous;
      adminStatusMessage(statusHost, error.message, true);
    } finally {
      select.disabled = false;
    }
  });
  return select;
}

function normalizedManagedUsername(value) {
  return String(value || '').normalize('NFKC').trim().toLowerCase();
}

function validateManagedUserInput(username, password, confirmation, creating) {
  const normalizedUsername = normalizedManagedUsername(username);
  if (creating && (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(normalizedUsername) || normalizedUsername === 'admin')) {
    return 'Use 1–64 lowercase letters, numbers, dots, underscores, or hyphens. The name admin is reserved.';
  }
  if (password.length < 7 || password.length > 256) return 'Password must contain 7–256 characters.';
  if (password !== confirmation) return 'Passwords do not match.';
  return null;
}

function openManagedUserModal(account, statusHost, returnFocusSelector) {
  const creating = !account;
  const form = node('form', 'admin-form managed-user-form');
  const username = node('input');
  username.name = 'username';
  username.autocomplete = 'username';
  username.maxLength = 64;
  username.required = true;
  username.value = account?.username || '';
  username.disabled = !creating;
  const password = node('input');
  password.type = 'password';
  password.name = 'password';
  password.autocomplete = 'new-password';
  password.minLength = 7;
  password.maxLength = 256;
  password.required = true;
  const confirmation = node('input');
  confirmation.type = 'password';
  confirmation.name = 'password-confirmation';
  confirmation.autocomplete = 'new-password';
  confirmation.minLength = 7;
  confirmation.maxLength = 256;
  confirmation.required = true;
  const tier = node('select');
  ['USER', 'ADMIN'].forEach((value) => {
    const option = node('option', '', accessTierLabel(value));
    option.value = value;
    option.selected = value === (account?.tier || 'USER');
    tier.append(option);
  });
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'alert');
  const actions = node('div', 'admin-form-actions');
  const submit = node('button', '', creating ? 'Create User' : 'Change Password');
  submit.type = 'submit';
  actions.append(submit);
  form.append(formField('Username', username, creating ? 'Usernames are stored in lowercase.' : ''),
    formField('Password', password, 'Use 7–256 characters.'),
    formField('Confirm password', confirmation));
  if (creating) form.append(formField('Access tier', tier));
  form.append(message, actions);
  const modal = openReadOnlyModal(creating ? 'Create user' : `Change password · ${account.username}`, form, {
    id: creating ? 'create-user' : 'change-password', returnFocusSelector, className: 'admin-modal'
  });
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (submit.disabled) return;
    const validation = validateManagedUserInput(username.value, password.value, confirmation.value, creating);
    if (validation) {
      message.textContent = validation;
      return;
    }
    submit.disabled = true;
    username.disabled = true;
    password.disabled = true;
    confirmation.disabled = true;
    tier.disabled = true;
    message.textContent = creating ? 'Creating user…' : 'Changing password…';
    try {
      if (creating) {
        await requestJson('/api/v1/admin/users', {
          method: 'POST', body: { username: normalizedManagedUsername(username.value), password: password.value,
            tier: accessTierToWire(accessTierValue(tier.value)) }
        });
      } else {
        await requestJson(adminUserEndpoint(account.username), {
          method: 'PUT', body: { password: password.value }
        });
      }
      password.value = '';
      confirmation.value = '';
      modal.close();
      adminStatusMessage(statusHost, creating ? 'User created.' : `Password changed for ${account.username}.`);
      if (!creating) await refreshAccessSession(false);
      await render();
    } catch (error) {
      password.value = '';
      confirmation.value = '';
      message.textContent = error.message;
      submit.disabled = false;
      username.disabled = !creating;
      password.disabled = false;
      confirmation.disabled = false;
      tier.disabled = false;
      password.focus();
    }
  });
  (creating ? username : password).focus();
}

function openDeleteUserModal(account, statusHost, returnFocusSelector) {
  const body = node('div', 'admin-confirmation');
  body.append(node('p', '', `Delete ${account.username}? This immediately revokes that user’s active sessions.`));
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'alert');
  const actions = node('div', 'admin-form-actions');
  const remove = node('button', 'danger', 'Delete User');
  remove.type = 'button';
  actions.append(remove);
  body.append(message, actions);
  const modal = openReadOnlyModal(`Delete user · ${account.username}`, body, {
    id: 'delete-user', returnFocusSelector, className: 'admin-modal'
  });
  remove.addEventListener('click', async () => {
    if (remove.disabled) return;
    remove.disabled = true;
    message.textContent = 'Deleting user…';
    try {
      await requestJson(adminUserEndpoint(account.username), { method: 'DELETE' });
      modal.close();
      adminStatusMessage(statusHost, `${account.username} was deleted.`);
      await refreshAccessSession(false);
      await render();
    } catch (error) {
      remove.disabled = false;
      message.textContent = error.message;
    }
  });
  remove.focus();
}

function userActions(account, statusHost) {
  if (account.primaryAdmin) return node('span', 'admin-managed-note', 'Managed in JavaFX');
  const actions = node('div', 'admin-row-actions');
  const reset = node('button', 'secondary', 'Change Password');
  reset.type = 'button';
  reset.dataset.username = account.username;
  const remove = node('button', 'secondary danger-outline', 'Delete');
  remove.type = 'button';
  remove.dataset.username = account.username;
  reset.addEventListener('click', () => openManagedUserModal(account, statusHost,
    `.admin-row-actions button[data-username="${account.username}"]`));
  remove.addEventListener('click', () => openDeleteUserModal(account, statusHost,
    `.admin-row-actions button[data-username="${account.username}"]`));
  actions.append(reset, remove);
  return actions;
}

async function renderAdminUsers() {
  const response = await requestJson('/api/v1/admin/users', { csrf: false });
  const users = (Array.isArray(response) ? response : response?.users || []).map(adminUserRecord)
    .filter((account) => account.username)
    .sort((left, right) => Number(right.primaryAdmin) - Number(left.primaryAdmin) ||
      left.username.localeCompare(right.username));
  const statusHost = node('div', 'admin-operation-status');
  statusHost.setAttribute('role', 'status');
  const create = node('button', '', 'Create User');
  create.type = 'button';
  create.id = 'admin-create-user';
  const maximumUsers = Number(response?.maximum_users || 0);
  if (maximumUsers > 0 && users.filter((account) => !account.primaryAdmin).length >= maximumUsers) {
    create.disabled = true;
    create.title = `The limit of ${number(maximumUsers)} managed users has been reached.`;
  }
  create.addEventListener('click', () => openManagedUserModal(null, statusHost, '#admin-create-user'));
  const body = node('div', 'admin-section-body');
  body.append(statusHost, table(users, [
    { id: 'username', label: 'Username', width: 230, render: userIdentityCell,
      sortValue: (account) => account.username },
    { id: 'access-tier', label: 'Access tier', width: 150,
      render: (account) => userTierControl(account, statusHost),
      sortValue: (account) => accessTierRank(account.tier) },
    { id: 'password-changed', label: 'Password changed', width: 190,
      render: (account) => dateTime(account.passwordChangedAtEpochMillis),
      sortValue: (account) => account.passwordChangedAtEpochMillis },
    { id: 'actions', label: 'Actions', width: 230, render: (account) => userActions(account, statusHost),
      sortable: false }
  ], 'No web users have been created', { type: 'admin-users', sortable: false }));
  content.append(section('User management', body, create));
}

function adminAccessPolicies(response) {
  if (!Array.isArray(response?.capabilities)) return [];
  return response.capabilities.map((entry) => {
    const requiredTier = accessTierFromWire(entry?.required_tier);
    const defaultTier = accessTierFromWire(entry?.default_tier);
    if (!requiredTier || !defaultTier) return null;
    return {
      id: typeof entry?.id === 'string' ? entry.id.trim() : '',
      displayName: typeof entry?.display_name === 'string' ? entry.display_name.trim() : '',
      requiredTier,
      defaultTier,
      configurable: entry?.configurable === true
    };
  }).filter((entry) => entry?.id && entry.displayName);
}

function accessPolicyTierControl(policy, statusHost) {
  const select = node('select', 'admin-tier-select');
  select.setAttribute('aria-label', `Required access tier for ${policy.displayName || policy.id}`);
  ['PUBLIC', 'USER', 'ADMIN'].forEach((tier) => {
    const option = node('option', '', accessTierLabel(tier));
    option.value = tier;
    option.selected = tier === policy.requiredTier;
    select.append(option);
  });
  const fixedAdmin = policy.id.startsWith('admin-');
  select.disabled = !policy.configurable || fixedAdmin;
  if (select.disabled) select.title = 'This capability is always administrator-only.';
  select.addEventListener('change', async () => {
    const previous = policy.requiredTier;
    const requested = accessTierValue(select.value);
    select.disabled = true;
    adminStatusMessage(statusHost, `Updating ${policy.displayName || policy.id}…`);
    try {
      await requestJson('/api/v1/admin/access', {
        method: 'PUT', body: { capability: policy.id, tier: accessTierToWire(requested) }
      });
      policy.requiredTier = requested;
      adminStatusMessage(statusHost,
        `${policy.displayName || policy.id} now requires ${accessTierLabel(requested)} access.`);
      await refreshAccessSession(false);
    } catch (error) {
      select.value = previous;
      adminStatusMessage(statusHost, error.message, true);
    } finally {
      select.disabled = !policy.configurable || fixedAdmin;
    }
  });
  return select;
}

function accessPolicyIdentity(policy) {
  const wrapper = node('div', 'admin-capability-identity');
  wrapper.append(node('strong', '', policy.displayName || policy.id),
    node('code', '', policy.id));
  return wrapper;
}

function wholeSiteAccessControl(policy, statusHost) {
  const wrapper = node('div', 'admin-site-access-control');
  const copy = node('div', 'admin-site-access-copy');
  copy.append(node('strong', '', 'Whole-site access'),
    node('p', '', 'Set the minimum tier for every receiver page, API, live stream, audio request, diagnostic, and ' +
      'export. The application shell and sign-in endpoints remain public so authorized users can sign in.'));
  const control = node('label', 'admin-site-access-tier');
  control.append(node('span', '', 'Minimum tier'), accessPolicyTierControl(policy, statusHost));
  wrapper.append(copy, control);
  return wrapper;
}

async function renderAdminAccess() {
  const response = await requestJson('/api/v1/admin/access', { csrf: false });
  const policies = adminAccessPolicies(response).sort((left, right) =>
    (left.displayName || left.id).localeCompare(right.displayName || right.id));
  const sitePolicy = policies.find((policy) => policy.id === ACCESS_CAPABILITIES.SITE_ACCESS);
  const featurePolicies = policies.filter((policy) => policy.id !== ACCESS_CAPABILITIES.SITE_ACCESS);
  const statusHost = node('div', 'admin-operation-status');
  statusHost.setAttribute('role', 'status');
  const body = node('div', 'admin-section-body');
  body.append(node('p', 'admin-section-intro',
    'Whole-site access applies first. Each capability below can then require a higher tier for its page and backing ' +
      'APIs.'), statusHost);
  if (sitePolicy) body.append(wholeSiteAccessControl(sitePolicy, statusHost));
  body.append(table(featurePolicies, [
      { id: 'capability', label: 'Capability', width: 310, render: accessPolicyIdentity,
        sortValue: (policy) => policy.displayName || policy.id },
      { id: 'required-tier', label: 'Required tier', width: 170,
        render: (policy) => accessPolicyTierControl(policy, statusHost),
        sortValue: (policy) => accessTierRank(policy.requiredTier) },
      { id: 'default-tier', label: 'Default', width: 120,
        render: (policy) => accessTierLabel(policy.defaultTier),
        sortValue: (policy) => accessTierRank(policy.defaultTier) },
      { id: 'policy-status', label: 'Policy', width: 130,
        render: (policy) => policy.configurable && !policy.id.startsWith('admin-') ? 'Configurable' : 'Fixed' }
    ], 'No feature access capabilities were returned', { type: 'admin-access', sortable: false }));
  content.append(section('Access policy', body));
}

function scanListAdminPayload(controls) {
  return {
    sort_order: Number(controls.sortOrder.value),
    name: controls.name.value.trim(),
    description: controls.description.value.trim(),
    published: controls.published.checked,
    default: controls.defaultScanList.checked
  };
}

function openScanListAdminModal(scanList, revision) {
  const editing = Boolean(scanList);
  const form = node('form', 'admin-form scan-list-admin-form');
  const name = node('input');
  name.required = true;
  name.maxLength = 100;
  name.value = scanList?.name || '';
  const description = node('textarea');
  description.maxLength = 1000;
  description.rows = 4;
  description.value = scanList?.description || '';
  const sortOrder = node('input');
  sortOrder.type = 'number';
  sortOrder.min = '0';
  sortOrder.max = '1000000';
  sortOrder.step = '1';
  sortOrder.required = true;
  sortOrder.value = String(scanList?.sort_order ?? 0);
  const published = node('input');
  published.type = 'checkbox';
  published.checked = scanList?.published !== false;
  const defaultScanList = node('input');
  defaultScanList.type = 'checkbox';
  defaultScanList.checked = scanList?.default === true;
  if (scanList?.default === true) {
    defaultScanList.disabled = true;
    published.disabled = true;
  }
  const syncDefault = () => {
    if (defaultScanList.checked) {
      published.checked = true;
      published.disabled = true;
    } else if (scanList?.default !== true) published.disabled = false;
  };
  defaultScanList.addEventListener('change', syncDefault);
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'alert');
  const cancel = node('button', 'secondary', 'Cancel');
  cancel.type = 'button';
  const submit = node('button', '', editing ? 'Save Scan List' : 'Create Scan List');
  submit.type = 'submit';
  const actions = node('div', 'admin-form-actions');
  actions.append(cancel, submit);
  form.append(formField('Name', name, 'Shown to listeners; up to 100 characters.'),
    formField('Description', description, 'Optional context for listeners.'),
    formField('Display order', sortOrder, 'Lower numbers appear first.'),
    formField('Available to listeners', published,
      'Unpublished lists remain configurable but cannot be selected in the listener.'),
    formField('Default scan list', defaultScanList, scanList?.default === true ?
      'Choose another list as the default before changing or deleting this one.' :
      'Making this the default replaces the current default.'), message, actions);
  const modal = openReadOnlyModal(editing ? `Edit scan list · ${scanList.name}` : 'Create scan list', form, {
    id: editing ? `edit-scan-list-${scanList.id}` : 'create-scan-list',
    className: 'admin-modal scan-list-admin-modal',
    returnFocusSelector: editing ? `.admin-scan-list-edit[data-scan-list-id="${scanList.id}"]` :
      '#admin-create-scan-list'
  });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  form.addEventListener('input', () => modal.setDirty(true));
  form.addEventListener('change', () => modal.setDirty(true));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!form.reportValidity() || submit.disabled) return;
    submit.disabled = true;
    message.textContent = editing ? 'Saving scan list…' : 'Creating scan list…';
    try {
      const path = editing ? `/api/v1/admin/scan-lists/${scanList.id}` : '/api/v1/admin/scan-lists';
      await requestJson(path, {
        method: editing ? 'PUT' : 'POST',
        body: { revision, scan_list: scanListAdminPayload({
          name, description, sortOrder, published, defaultScanList
        }) }
      });
      modal.setDirty(false);
      modal.close();
      await refreshPlaybackScanLists(true);
      await render();
    } catch (error) {
      message.textContent = error.status === 409 ?
        `${error.message} Reload Scan Lists and try again.` : error.message;
      submit.disabled = false;
    }
  });
  name.focus();
}

function openDeleteScanListAdminModal(scanList, revision) {
  if (scanList.default === true) return;
  const body = node('div', 'admin-confirmation');
  const aliasCount = Number(scanList.alias_count || 0);
  const unmatchedAliasListCount = Number(scanList.unmatched_alias_list_count || 0);
  body.append(node('p', '', `Delete ${scanList.name}?`),
    node('p', 'muted', `This removes the list from ${number(aliasCount)} aliases and the Alias List Defaults of ` +
      `${number(unmatchedAliasListCount)} alias lists. The aliases and alias lists ` +
      'themselves are preserved.'));
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'alert');
  const cancel = node('button', 'secondary', 'Cancel');
  cancel.type = 'button';
  const remove = node('button', 'danger', 'Delete Scan List');
  remove.type = 'button';
  const actions = node('div', 'admin-form-actions');
  actions.append(cancel, remove);
  body.append(message, actions);
  const modal = openReadOnlyModal(`Delete scan list · ${scanList.name}`, body, {
    id: `delete-scan-list-${scanList.id}`, className: 'admin-modal',
    returnFocusSelector: `.admin-scan-list-delete[data-scan-list-id="${scanList.id}"]`
  });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  remove.addEventListener('click', async () => {
    if (remove.disabled) return;
    remove.disabled = true;
    message.textContent = 'Deleting scan list…';
    try {
      await requestJson(`/api/v1/admin/scan-lists/${scanList.id}`, {
        method: 'DELETE', body: { revision }
      });
      modal.close();
      await refreshPlaybackScanLists(true);
      await render();
    } catch (error) {
      message.textContent = error.status === 409 ?
        `${error.message} Reload Scan Lists and try again.` : error.message;
      remove.disabled = false;
    }
  });
  remove.focus();
}

function adminScanListIdentity(scanList) {
  const wrapper = node('div', 'admin-capability-identity');
  wrapper.append(node('strong', '', scanList.name));
  if (scanList.default === true) wrapper.append(badge('Default', 'state-current'));
  if (scanList.published === false) wrapper.append(badge('Not published', 'state-stale'));
  return wrapper;
}

function adminScanListActions(scanList, revision) {
  const actions = node('div', 'admin-row-actions');
  const members = anchor('View Aliases', href('aliases', {
    scanListId: scanList.id, aliasTab: 'configure'
  }), 'button secondary admin-scan-list-members');
  const edit = node('button', 'secondary admin-scan-list-edit', 'Edit');
  edit.type = 'button';
  edit.dataset.scanListId = String(scanList.id);
  edit.addEventListener('click', () => openScanListAdminModal(scanList, revision));
  const remove = node('button', 'secondary danger-outline admin-scan-list-delete', 'Delete');
  remove.type = 'button';
  remove.dataset.scanListId = String(scanList.id);
  remove.disabled = scanList.default === true;
  if (remove.disabled) remove.title = 'Choose another default scan list before deleting this one.';
  remove.addEventListener('click', () => openDeleteScanListAdminModal(scanList, revision));
  actions.append(members, edit, remove);
  return actions;
}

function adminScanListMemberCount(scanList) {
  return anchor(number(scanList.alias_count || 0), href('aliases', {
    scanListId: scanList.id, aliasTab: 'configure'
  }), 'admin-scan-list-member-count');
}

async function renderAdminScanLists() {
  const response = await requestJson('/api/v1/admin/scan-lists', { csrf: false });
  const revision = Number(response?.revision ?? 0);
  const scanLists = Array.isArray(response?.scan_lists) ? response.scan_lists : [];
  const create = node('button', '', 'Create Scan List');
  create.type = 'button';
  create.id = 'admin-create-scan-list';
  create.addEventListener('click', () => openScanListAdminModal(null, revision));
  const body = node('div', 'admin-section-body');
  body.append(node('p', 'admin-section-intro',
    'Scan lists group aliases from any alias list, and overlapping listener subscriptions are deduplicated. ' +
    'Open a scan list to search all of its alias members and remove selected memberships in bounded batches. ' +
    'Route unmatched talkgroups from an Alias List\'s Alias List Defaults.'),
    table(scanLists, [
      { id: 'scan-list', label: 'Scan list', width: 240, render: adminScanListIdentity,
        sortValue: (row) => Number(row.sort_order || 0) },
      { id: 'description', label: 'Description', render: (row) => availableValue(row.description) },
      { id: 'aliases', label: 'Aliases', width: 100, className: 'numeric',
        render: adminScanListMemberCount, sortValue: (row) => Number(row.alias_count || 0) },
      { id: 'unmatched-alias-lists', label: 'Alias List Defaults', width: 160, className: 'numeric',
        render: (row) => number(row.unmatched_alias_list_count || 0),
        sortValue: (row) => Number(row.unmatched_alias_list_count || 0) },
      { id: 'actions', label: 'Actions', width: 300, sortable: false,
        render: (row) => adminScanListActions(row, revision) }
    ], 'No scan lists are configured', { type: 'admin-scan-lists', sortable: false }));
  const actions = node('div', 'section-title-actions');
  actions.append(anchor('Manage Alias Membership', href('aliases', { aliasTab: 'configure' }),
    'button secondary'), create);
  content.append(section('Scan-list management', body, actions));
}

function radioReferenceAccountMessage(account) {
  const state = String(account?.state || 'SIGNED_OUT');
  const userName = String(account?.user_name || '').trim();
  const expiration = String(account?.account_expires || '').trim();
  switch (state) {
    case 'VALID_PREMIUM':
      return `Connected${userName ? ` as ${userName}` : ''}${expiration ? ` · Premium ${expiration}` : ''}.`;
    case 'EXPIRED_PREMIUM':
      return `Connected${userName ? ` as ${userName}` : ''}, but the Premium subscription is expired.`;
    case 'INVALID_CREDENTIALS': return 'RadioReference rejected the username or password.';
    case 'SECURE_TRANSPORT_REQUIRED': return 'Secure RadioReference transport is unavailable.';
    case 'UNAVAILABLE': return 'RadioReference is currently unavailable.';
    case 'CHECKING': return 'Checking the RadioReference account…';
    default: return 'RadioReference is not connected.';
  }
}

function replaceRadioReferenceOptions(select, options, selectedId, placeholder) {
  select.replaceChildren();
  const rows = Array.isArray(options) ? options : [];
  if (!rows.length) {
    const empty = node('option', '', placeholder);
    empty.value = '';
    select.append(empty);
    select.value = '';
    return false;
  }
  rows.forEach((item) => {
    const suffix = item.abbreviation ? ` (${item.abbreviation})` : '';
    const option = node('option', '', `${item.name}${suffix}`);
    option.value = String(item.id);
    select.append(option);
  });
  const requested = String(selectedId || '');
  select.value = rows.some((item) => String(item.id) === requested) ? requested :
    String(rows.find((item) => String(item.abbreviation).toUpperCase() === 'US')?.id || rows[0].id);
  return true;
}

async function renderAdminWebDisplaySettings() {
  const body = node('div', 'admin-section-body web-display-settings');
  const form = node('form', 'admin-form web-display-settings-form');
  const controls = {};
  const toggle = (name, title, detail) => {
    const input = node('input');
    input.type = 'checkbox';
    input.name = name;
    input.disabled = true;
    controls[name] = input;
    const copy = node('span', 'admin-toggle-copy');
    copy.append(node('strong', '', title), node('span', '', detail));
    const label = node('label', 'admin-toggle-control');
    label.append(input, copy);
    return label;
  };
  const qualityMode = node('select');
  qualityMode.name = 'decode_quality_display_mode';
  qualityMode.disabled = true;
  [['percentage', 'Percentage'], ['detailed', 'Detailed counters']].forEach(([value, label]) => {
    const option = node('option', '', label);
    option.value = value;
    qualityMode.append(option);
  });
  controls.decode_quality_display_mode = qualityMode;
  const grantAge = node('input');
  grantAge.type = 'number';
  grantAge.name = 'traffic_grant_age_out_milliseconds';
  grantAge.min = '100';
  grantAge.max = '15000';
  grantAge.required = true;
  grantAge.disabled = true;
  controls.traffic_grant_age_out_milliseconds = grantAge;
  const rowLimit = node('input');
  rowLimit.type = 'number';
  rowLimit.name = 'live_detail_matching_row_limit';
  rowLimit.min = '25';
  rowLimit.max = '500';
  rowLimit.required = true;
  rowLimit.disabled = true;
  controls.live_detail_matching_row_limit = rowLimit;
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'status');
  message.textContent = 'Loading Live and activity settings…';
  const save = node('button', '', 'Save Live & Activity Settings');
  save.type = 'submit';
  save.disabled = true;
  const actions = node('div', 'admin-form-actions');
  actions.append(save);
  const groups = node('div', 'admin-settings-grid');
  const group = (title, description, ...items) => {
    const fieldset = node('fieldset', 'admin-settings-group');
    fieldset.append(node('legend', '', title), node('p', 'admin-settings-group-intro', description), ...items);
    return fieldset;
  };
  groups.append(
    group('Live activity', 'Choose what remains visible as calls start, stop, and become encrypted.',
      toggle('retain_idle_call_details', 'Retain the last call on idle rows',
        'Keep the most recent source and target visible after a Live row becomes idle.'),
      toggle('show_encryption_details', 'Show encryption algorithm and key',
        'Use protocol-aware algorithm and key details when the receiver has them.')),
    group('Decode quality', 'Control the quality information shown in Live system rows.',
      toggle('show_control_decode_quality', 'Show control-channel decode quality',
        'Display control-channel quality in Live system rows and signal indicators.'),
      toggle('show_voice_decode_quality', 'Show voice-channel decode quality',
        'Display voice quality after enough frames have been received.'),
      toggle('clear_voice_decode_quality_on_call_end', 'Clear voice quality when a call ends',
        'Remove the previous call’s voice-quality value when its row becomes idle.'),
      formField('Decode quality format', qualityMode)),
    group('Live detail viewers', 'Events and Messages are temporary browser sessions; filtering happens locally.',
      formField('Matching rows shown', rowLimit,
        'The newest matching rows are shown after frontend filters are applied. Allowed range: 25–500.')),
    group('Trunked activity', 'Control when inactive traffic activity leaves Live.',
      formField('Idle grant retention (milliseconds)', grantAge)));
  form.append(groups, message, actions);
  body.append(node('p', 'admin-section-intro',
    'Receiver-wide presentation settings. Changes apply without restarting the receiver.'), form);
  content.append(section('Live and activity', body));

  let configuration = null;
  const setDisabled = (disabled) => {
    Object.values(controls).forEach((control) => { control.disabled = disabled; });
    save.disabled = disabled;
  };
  const applyConfiguration = (next) => {
    configuration = next || {};
    controls.retain_idle_call_details.checked = configuration.retain_idle_call_details === true;
    controls.show_encryption_details.checked = configuration.show_encryption_details !== false;
    controls.show_control_decode_quality.checked = configuration.show_control_decode_quality !== false;
    controls.show_voice_decode_quality.checked = configuration.show_voice_decode_quality !== false;
    controls.clear_voice_decode_quality_on_call_end.checked =
      configuration.clear_voice_decode_quality_on_call_end === true;
    qualityMode.value = configuration.decode_quality_display_mode === 'detailed' ? 'detailed' : 'percentage';
    grantAge.value = String(configuration.traffic_grant_age_out_milliseconds ?? 1000);
    rowLimit.value = String(configuration.live_detail_matching_row_limit ?? LIVE_DETAIL_DEFAULT_MATCHING_ROW_LIMIT);
    liveDisplaySettings = configuration;
  };
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!form.reportValidity() || save.disabled) return;
    setDisabled(true);
    message.textContent = 'Saving Live and activity settings…';
    try {
      const next = await requestJson('/api/v1/admin/web-display', { method: 'PUT', body: {
        retain_idle_call_details: controls.retain_idle_call_details.checked,
        show_encryption_details: controls.show_encryption_details.checked,
        show_control_decode_quality: controls.show_control_decode_quality.checked,
        show_voice_decode_quality: controls.show_voice_decode_quality.checked,
        clear_voice_decode_quality_on_call_end: controls.clear_voice_decode_quality_on_call_end.checked,
        decode_quality_display_mode: qualityMode.value,
        traffic_grant_age_out_milliseconds: Number(grantAge.value),
        live_detail_matching_row_limit: Number(rowLimit.value)
      } });
      applyConfiguration(next);
      message.textContent = 'Live and activity settings saved.';
    } catch (error) {
      if (configuration) applyConfiguration(configuration);
      message.textContent = error.message;
    } finally {
      setDisabled(false);
    }
  });
  try {
    applyConfiguration(await requestJson('/api/v1/admin/web-display', { csrf: false }));
    message.textContent = '';
    setDisabled(false);
  } catch (error) {
    message.textContent = error.message;
  }
}

async function renderAdminRadioReferenceSettings() {
  const body = node('div', 'admin-section-body radioreference-settings');
  const accountForm = node('form', 'admin-form admin-settings-form radioreference-account-form');
  const userName = node('input');
  userName.name = 'radioreference-username';
  userName.autocomplete = 'username';
  userName.maxLength = 256;
  userName.required = true;
  const password = node('input');
  password.type = 'password';
  password.name = 'radioreference-password';
  password.autocomplete = 'current-password';
  password.maxLength = 1024;
  password.required = true;
  const rememberLabel = node('label', 'radioreference-remember');
  const remember = node('input');
  remember.type = 'checkbox';
  remember.checked = true;
  rememberLabel.append(remember, node('span', '', 'Remember credentials in this receiver’s portable settings'));
  const accountMessage = node('div', 'admin-form-message');
  accountMessage.setAttribute('role', 'status');
  const connect = node('button', '', 'Connect RadioReference');
  connect.type = 'submit';
  const signOut = node('button', 'secondary danger-outline', 'Sign Out');
  signOut.type = 'button';
  signOut.disabled = true;
  const accountActions = node('div', 'admin-form-actions');
  accountActions.append(signOut, connect);
  accountForm.append(node('h3', 'admin-settings-form-title', 'Account'),
    formField('Username', userName), formField('Password', password,
    'A current Premium subscription is required. The password is never returned to the browser.'),
    rememberLabel, accountMessage, accountActions);

  const regionForm = node('form', 'admin-form admin-settings-form radioreference-region-form');
  const country = node('select');
  const state = node('select');
  country.disabled = true;
  state.disabled = true;
  replaceRadioReferenceOptions(country, [], null, 'Connect an account first');
  replaceRadioReferenceOptions(state, [], null, 'Choose a country first');
  const regionMessage = node('div', 'admin-form-message');
  regionMessage.setAttribute('role', 'status');
  regionMessage.textContent = 'Choose the state used for exact-frequency searches.';
  const saveRegion = node('button', '', 'Save Lookup Region');
  saveRegion.type = 'submit';
  saveRegion.disabled = true;
  const regionActions = node('div', 'admin-form-actions');
  regionActions.append(saveRegion);
  regionForm.append(node('h3', 'admin-settings-form-title', 'Lookup region'),
    formField('Country', country), formField('State or region', state), regionMessage, regionActions);

  const settingsForms = node('div', 'admin-settings-form-stack');
  settingsForms.append(accountForm, regionForm);
  body.append(node('p', 'admin-section-intro',
    'Connect the receiver to RadioReference’s database API, then choose the state searched when a frequency is ' +
    'clicked in Tuner Spectrum. Use your own current Premium account.'), settingsForms);
  content.append(section('RadioReference lookup', body));

  let configuration = null;

  const updateAccount = (next, initializeUserName = false) => {
    configuration = next || configuration || {};
    const account = configuration?.account || {};
    const connected = account.state === 'VALID_PREMIUM';
    accountMessage.textContent = radioReferenceAccountMessage(account);
    if (initializeUserName || !userName.value) {
      userName.value = account.user_name || configuration?.stored_user_name || '';
    }
    remember.checked = configuration?.credentials_stored === true;
    signOut.disabled = account.state === 'SIGNED_OUT';
    country.disabled = !connected;
    state.disabled = !connected || !country.value;
    saveRegion.disabled = !connected || !state.value;
    return connected;
  };

  const loadStates = async (countryId, selectedStateId = null) => {
    state.disabled = true;
    saveRegion.disabled = true;
    replaceRadioReferenceOptions(state, [], null, 'Loading states…');
    const response = await requestJson(`/api/v1/admin/radioreference/states?country_id=${
      encodeURIComponent(countryId)}`, { csrf: false,
      timeoutMs: RADIO_REFERENCE_DIRECTORY_TIMEOUT_MILLISECONDS });
    const available = replaceRadioReferenceOptions(state, response?.items, selectedStateId, 'No states available');
    state.disabled = !available;
    saveRegion.disabled = !available;
  };

  const loadRegions = async () => {
    const response = await requestJson('/api/v1/admin/radioreference/countries', { csrf: false,
      timeoutMs: RADIO_REFERENCE_DIRECTORY_TIMEOUT_MILLISECONDS });
    const available = replaceRadioReferenceOptions(country, response?.items, configuration?.country_id,
      'No countries available');
    country.disabled = !available;
    if (available) await loadStates(country.value, configuration?.state_id);
  };

  accountForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!accountForm.reportValidity() || connect.disabled) return;
    connect.disabled = true;
    signOut.disabled = true;
    accountMessage.textContent = 'Connecting to RadioReference…';
    try {
      const next = await requestJson('/api/v1/admin/radioreference/session', {
        method: 'PUT', body: { userName: userName.value, password: password.value, remember: remember.checked },
        timeoutMs: 15_000
      });
      radioReferenceDetailCache.clear();
      password.value = '';
      if (updateAccount(next)) await loadRegions();
    } catch (error) {
      password.value = '';
      accountMessage.textContent = error.message;
    } finally {
      connect.disabled = false;
      signOut.disabled = configuration?.account?.state === 'SIGNED_OUT';
    }
  });

  signOut.addEventListener('click', async () => {
    if (signOut.disabled) return;
    signOut.disabled = true;
    connect.disabled = true;
    accountMessage.textContent = 'Signing out of RadioReference…';
    try {
      const next = await requestJson('/api/v1/admin/radioreference/session', { method: 'DELETE' });
      radioReferenceDetailCache.clear();
      password.value = '';
      userName.value = '';
      updateAccount(next);
      replaceRadioReferenceOptions(country, [], null, 'Connect an account first');
      replaceRadioReferenceOptions(state, [], null, 'Choose a country first');
      regionMessage.textContent = 'Choose the state used for exact-frequency searches.';
    } catch (error) {
      accountMessage.textContent = error.message;
    } finally {
      connect.disabled = false;
    }
  });

  country.addEventListener('change', async () => {
    regionMessage.textContent = 'Loading states…';
    try {
      await loadStates(country.value);
      regionMessage.textContent = 'Save this state to use it for spectrum frequency searches.';
    } catch (error) {
      regionMessage.textContent = error.message;
    }
  });

  regionForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!regionForm.reportValidity() || saveRegion.disabled) return;
    saveRegion.disabled = true;
    regionMessage.textContent = 'Saving RadioReference lookup region…';
    try {
      configuration = await requestJson('/api/v1/admin/radioreference/location', {
        method: 'PUT', body: { countryId: Number(country.value), stateId: Number(state.value) }
      });
      regionMessage.textContent = 'RadioReference lookup region saved.';
    } catch (error) {
      regionMessage.textContent = error.message;
    } finally {
      saveRegion.disabled = configuration?.account?.state !== 'VALID_PREMIUM' || !state.value;
    }
  });

  try {
    const initial = await requestJson('/api/v1/admin/radioreference', { csrf: false });
    if (updateAccount(initial, true)) await loadRegions();
  } catch (error) {
    accountMessage.textContent = error.message;
    connect.disabled = false;
  }
}

async function renderAdminLiveActivitySettings() {
  const renderContext = captureRenderContext();
  await renderAdminWebDisplaySettings();
  if (!renderIsCurrent(renderContext)) return;
  await renderAdminRadioReferenceSettings();
}

function adminAudioNumberField(configuration, limits, key, label, help) {
  const input = node('input');
  input.type = 'number';
  input.name = key;
  input.required = true;
  input.step = '1';
  input.value = String(configuration?.[key] ?? '');
  const range = limits?.[key] || {};
  if (Number.isFinite(Number(range.minimum))) input.min = String(range.minimum);
  if (Number.isFinite(Number(range.maximum))) input.max = String(range.maximum);
  return formField(label, input, `${help} Allowed range: ${number(range.minimum)}–${number(range.maximum)}.`);
}

function adminStatusNumber(value) {
  const numeric = typeof value === 'number' ? value : Number.NaN;
  return Number.isFinite(numeric) && numeric >= 0 ? number(numeric) : '—';
}

function adminStatusBytes(value) {
  const numeric = typeof value === 'number' ? value : Number.NaN;
  if (!Number.isFinite(numeric) || numeric < 0) return '—';
  if (numeric >= 1073741824) return `${(numeric / 1073741824).toFixed(1)} GB`;
  if (numeric >= 1048576) return `${(numeric / 1048576).toFixed(numeric >= 10485760 ? 0 : 1)} MB`;
  if (numeric >= 1024) return `${(numeric / 1024).toFixed(0)} KB`;
  return `${number(numeric)} B`;
}

function adminDatabaseDisplay(database) {
  if (typeof database?.database_exists !== 'boolean') return 'Unknown';
  if (!database.database_exists) return 'Missing';
  const size = adminStatusBytes(database.database_bytes);
  return size === '—' ? 'Present' : size;
}

function adminStatusRatio(status, currentKey, maximumKey, formatter = adminStatusNumber) {
  return `${formatter(status?.[currentKey])} / ${formatter(status?.[maximumKey])}`;
}

function adminListenerStatusGroup(title, entries) {
  const group = node('section', 'admin-listener-status-group');
  group.append(node('h3', '', title));
  const values = node('dl', 'admin-listener-status-values');
  entries.forEach(([label, value]) => {
    values.append(node('dt', '', label), node('dd', '', value));
  });
  group.append(values);
  return group;
}

async function renderAdminWebAudio() {
  const response = await requestJson('/api/v1/admin/web-audio', { csrf: false });
  const configuration = response?.configuration || {};
  const limits = response?.limits || {};
  const status = response?.status || {};
  const form = node('form', 'admin-form admin-audio-form');
  let formDirty = false;
  const fields = [
    ['maximum_listeners', 'Simultaneous listeners', 'Admission limit for browser-audio connections.'],
    ['maximum_selected_scan_lists', 'Scan lists per listener', 'Maximum lists one listener may subscribe to.'],
    ['waiting_calls_per_listener', 'Waiting calls per listener',
      'Exact bounded waiting-call policy silently enforced for each browser listener.'],
    ['maximum_cached_calls', 'Cached calls', 'Maximum completed calls retained for browser retrieval.'],
    ['maximum_cached_audio_mib', 'Cached audio (MiB)', 'Maximum memory used by completed-call audio.']
  ];
  fields.forEach(([key, label, help]) => form.append(
    adminAudioNumberField(configuration, limits, key, label, help)));
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'status');
  const save = node('button', '', 'Save Web Audio Settings');
  save.type = 'submit';
  const actions = node('div', 'admin-form-actions');
  actions.append(save);
  form.append(message, actions);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!form.reportValidity() || save.disabled) return;
    save.disabled = true;
    message.textContent = 'Saving web-audio settings…';
    try {
      const body = Object.fromEntries(fields.map(([key]) => [key, Number(form.elements[key].value)]));
      await requestJson('/api/v1/admin/web-audio', { method: 'PUT', body });
      formDirty = false;
      message.textContent = 'Web-audio settings saved.';
    } catch (error) {
      message.textContent = error.message;
    } finally {
      save.disabled = false;
    }
  });
  form.addEventListener('input', () => { formDirty = true; });
  const runtime = metrics([
    ['Active listeners', status.active_listeners || 0],
    ['Cached calls', status.cached_calls || 0],
    ['Published calls', status.published_calls || 0],
    ['Dropped calls', Number(status.dropped_no_listeners || 0) +
      Number(status.dropped_no_matching_listeners || 0) + Number(status.dropped_invalid_calls || 0) +
      Number(status.dropped_no_scan_list || 0) + Number(status.dropped_pending_capacity || 0) +
      Number(status.dropped_encoder_capacity || 0)]
  ], true);
  const statusDetails = node('div', 'admin-listener-status-grid');
  statusDetails.append(
    adminListenerStatusGroup('Delivery', [
      ['Listeners / maximum', adminStatusRatio(status, 'active_listeners', 'maximum_listeners')],
      ['Audio responses / maximum', adminStatusRatio(status, 'active_audio_responses',
        'maximum_audio_responses')],
      ['Received calls', adminStatusNumber(status.received_calls)],
      ['Published calls', adminStatusNumber(status.published_calls)],
      ['SSE events dropped', adminStatusNumber(status.dropped_sse_events)],
      ['Listeners rejected', adminStatusNumber(status.rejected_listeners)],
      ['Audio responses rejected', adminStatusNumber(status.rejected_audio_responses)]
    ]),
    adminListenerStatusGroup('Cache & pipeline', [
      ['Cached calls / maximum', adminStatusRatio(status, 'cached_calls', 'maximum_calls')],
      ['Cached audio / maximum', adminStatusRatio(status, 'cached_audio_bytes', 'maximum_audio_bytes',
        adminStatusBytes)],
      ['Pending audio / maximum', adminStatusRatio(status, 'pending_audio_bytes',
        'maximum_pending_audio_bytes', adminStatusBytes)],
      ['Per-call audio limit', adminStatusBytes(status.maximum_call_audio_bytes)],
      ['Encoder queue depth', adminStatusNumber(status.encoder_queue_depth)],
      ['SSE event queue capacity', adminStatusNumber(status.event_queue_capacity)]
    ]),
    adminListenerStatusGroup('Call drops', [
      ['No listeners', adminStatusNumber(status.dropped_no_listeners)],
      ['No matching subscription', adminStatusNumber(status.dropped_no_matching_listeners)],
      ['No scan-list membership', adminStatusNumber(status.dropped_no_scan_list)],
      ['Invalid call', adminStatusNumber(status.dropped_invalid_calls)],
      ['Pending-audio capacity', adminStatusNumber(status.dropped_pending_capacity)],
      ['Encoder capacity', adminStatusNumber(status.dropped_encoder_capacity)]
    ]),
    adminListenerStatusGroup('Retrieval & eviction', [
      ['Audio fetch misses', adminStatusNumber(status.audio_fetch_misses)],
      ['Age evictions', adminStatusNumber(status.age_evictions)],
      ['Capacity evictions', adminStatusNumber(status.capacity_evictions)]
    ])
  );
  const body = node('div', 'admin-section-body admin-audio-settings');
  body.append(node('p', 'admin-section-intro',
    'Live listener status and bounded completed-call browser-audio controls. Settings update without a restart.'),
    runtime, statusDetails, form);
  const refresh = node('button', 'secondary', 'Refresh Status');
  refresh.type = 'button';
  refresh.addEventListener('click', async () => {
    if (formDirty && !window.confirm('Discard unsaved web-audio setting changes and refresh status?')) return;
    refresh.disabled = true;
    await render();
  });
  content.append(section('Listener status & capacity', body, refresh));
}

function receiverHealthText(value, fallback = '—') {
  if (value === null || value === undefined) return fallback;
  const text = String(value).trim();
  return text || fallback;
}

function receiverHealthTime(value) {
  return dateTime(value) || node('span', 'muted', '—');
}

function receiverHealthSeverityBadge(value) {
  const severity = receiverHealthSeverity(value);
  const label = severity === 'critical' ? 'Critical' : severity === 'warning' ? 'Warning' : 'Healthy';
  return badge(label, `receiver-health-severity receiver-health-${severity}`);
}

function receiverHealthIncident(incident, resolved = false, expanded = false, onToggle = null) {
  const severity = receiverHealthSeverity(incident.severity);
  const card = node(resolved ? 'details' : 'article', `receiver-health-incident receiver-health-${severity}`);
  const heading = node(resolved ? 'summary' : 'div', 'receiver-health-incident-heading');
  const identity = node('div', 'receiver-health-incident-identity');
  identity.append(node('h3', '', receiverHealthText(incident.title, receiverHealthText(incident.code,
    'Receiver health incident'))), node('div', 'receiver-health-incident-scope',
    receiverHealthText(incident.scope, 'Receiver')));
  if (resolved) {
    const observations = receiverHealthCount(incident.count, 1);
    const resolvedSummary = node('div', 'receiver-health-incident-resolved-summary');
    resolvedSummary.append('Resolved ', receiverHealthTime(incident.resolved_at_ms),
      ` · ${number(observations)} observation${observations === 1 ? '' : 's'}`);
    identity.append(resolvedSummary);
  }
  heading.append(identity, receiverHealthSeverityBadge(incident.severity));

  const facts = node('dl', 'receiver-health-incident-facts');
  const entries = [
    ['Code', receiverHealthText(incident.code)],
    ['Occurrence ID', receiverHealthText(incident.occurrence_id)],
    ['Observations', number(receiverHealthCount(incident.count, 1))],
    ['Opened', receiverHealthTime(incident.opened_at_ms)],
    ['Last seen', receiverHealthTime(incident.last_seen_ms)]
  ];
  if (resolved) entries.push(['Resolved', receiverHealthTime(incident.resolved_at_ms)]);
  entries.forEach(([label, value]) => {
    facts.append(node('dt', '', label));
    const detail = node('dd');
    detail.append(valueNode(value));
    facts.append(detail);
  });

  const guidance = node('div', 'receiver-health-incident-guidance');
  [
    ['Observed', incident.observed],
    ['Likely cause', incident.likely_cause],
    ['Impact', incident.impact],
    ['Check next', incident.check_next]
  ].forEach(([label, value]) => {
    const item = node('div', 'receiver-health-guidance-item');
    item.append(node('h4', '', label), node('p', '', receiverHealthText(value)));
    guidance.append(item);
  });
  card.append(heading, facts, guidance);
  if (resolved) {
    card.open = expanded;
    card.addEventListener('toggle', () => onToggle?.(card.open));
  }
  return card;
}

function receiverHealthResolvedIncidentKey(incident) {
  const occurrence = String(incident?.occurrence_id ?? '').trim();
  if (occurrence) return `occurrence:${occurrence}`;
  return ['fallback', incident?.code, incident?.scope, incident?.opened_at_ms, incident?.resolved_at_ms]
    .map((value) => String(value ?? '')).join('\u0000');
}

function receiverHealthSortedResolvedIncidents(incidents, sort) {
  const sorted = [...incidents];
  const compareText = (left, right) => String(left || '').localeCompare(String(right || ''), undefined,
    { sensitivity: 'base', numeric: true });
  sorted.sort((left, right) => {
    if (sort === 'type') {
      const title = compareText(left.title || left.code, right.title || right.code);
      if (title) return title;
      const code = compareText(left.code, right.code);
      if (code) return code;
      const scope = compareText(left.scope, right.scope);
      if (scope) return scope;
    }
    const resolved = Number(right.resolved_at_ms || 0) - Number(left.resolved_at_ms || 0);
    if (resolved) return resolved;
    return compareText(right.occurrence_id, left.occurrence_id);
  });
  return sorted;
}

function receiverHealthIncidentList(incidents, resolved = false) {
  if (!incidents.length) {
    return node('div', resolved ? 'receiver-health-empty' : 'receiver-health-empty receiver-health-empty-healthy',
      resolved ? 'No recently resolved incidents.' : 'No active receiver health incidents.');
  }
  const list = node('div', 'receiver-health-incident-list');
  const rows = resolved ? receiverHealthSortedResolvedIncidents(incidents, receiverHealthController.resolvedSort) :
    incidents;
  if (resolved) {
    const current = new Set(rows.map(receiverHealthResolvedIncidentKey));
    receiverHealthController.expandedResolvedIncidents.forEach((key) => {
      if (!current.has(key)) receiverHealthController.expandedResolvedIncidents.delete(key);
    });
  }
  list.append(...rows.map((incident) => {
    if (!resolved) return receiverHealthIncident(incident);
    const key = receiverHealthResolvedIncidentKey(incident);
    return receiverHealthIncident(incident, true,
      receiverHealthController.expandedResolvedIncidents.has(key), (open) => {
        if (open) receiverHealthController.expandedResolvedIncidents.add(key);
        else receiverHealthController.expandedResolvedIncidents.delete(key);
      });
  }));
  return list;
}

function receiverHealthResolvedSection(incidents) {
  if (!incidents.length) {
    return section('Recently resolved', receiverHealthIncidentList(incidents, true));
  }
  const body = node('div');
  const sort = node('select');
  sort.setAttribute('aria-label', 'Sort resolved alerts');
  [['recent', 'Newest resolved'], ['type', 'Alert type (A–Z)']].forEach(([value, label]) => {
    const option = node('option', '', label);
    option.value = value;
    option.selected = receiverHealthController.resolvedSort === value;
    sort.append(option);
  });
  const control = node('label', 'receiver-health-resolved-sort');
  control.append(node('span', '', 'Sort'), sort);
  const draw = () => body.replaceChildren(receiverHealthIncidentList(incidents, true));
  sort.addEventListener('change', () => {
    receiverHealthController.resolvedSort = sort.value === 'type' ? 'type' : 'recent';
    draw();
  });
  draw();
  return section('Recently resolved', body, control);
}

function receiverHealthMeasurementRow(row) {
  const severity = receiverHealthSeverity(row.severity);
  const item = node('div', `receiver-health-measurement-row receiver-health-${severity}`);
  item.setAttribute('role', 'listitem');
  const scope = node('div', 'receiver-health-measurement-scope', receiverHealthText(row.scope, 'Receiver'));
  const label = node('div', 'receiver-health-measurement-label', receiverHealthText(row.label));
  const reading = node('div', 'receiver-health-measurement-value');
  reading.append(node('strong', '', receiverHealthText(row.value)));
  const unit = receiverHealthText(row.unit, '');
  if (unit) reading.append(node('span', '', unit));
  item.append(scope, label, reading, receiverHealthSeverityBadge(row.severity),
    node('div', 'receiver-health-measurement-detail', receiverHealthText(row.detail)));
  return item;
}

function receiverHealthMeasurementGroup(group) {
  const body = node('div', 'receiver-health-measurement-list');
  body.setAttribute('role', 'list');
  if (group.rows.length) body.append(...group.rows.map(receiverHealthMeasurementRow));
  else body.append(node('div', 'receiver-health-empty', 'No measurements were reported.'));
  return section(receiverHealthText(group.title, receiverHealthText(group.id, 'Measurements')), body);
}

function receiverHealthRefreshButton() {
  const refresh = node('button', 'secondary', 'Refresh now');
  refresh.type = 'button';
  refresh.addEventListener('click', async () => {
    refresh.disabled = true;
    await receiverHealthController.refresh();
    if (refresh.isConnected) refresh.disabled = false;
  });
  return refresh;
}

function renderReceiverHealthPage(host, snapshot, stale, lastError) {
  host.replaceChildren();
  if (!snapshot) {
    const message = stale ? (lastError || 'Receiver health status is unavailable.') :
      'Loading receiver health status…';
    const body = node('div', 'admin-section-body');
    body.append(node('div', stale ? 'logging-notice warning' : 'receiver-health-loading-message', message));
    host.append(section('Current status', body, receiverHealthRefreshButton()));
    return;
  }

  const summary = snapshot.summary;
  const stateLabel = stale ? 'Stale' : summary.severity === 'critical' ? 'Critical' :
    summary.severity === 'warning' ? 'Warning' : 'Healthy';
  const overview = node('div', 'receiver-health-overview');
  const status = node('div', `receiver-health-overview-state receiver-health-${stale ? 'stale' : summary.severity}`);
  status.append(node('span', '', 'Receiver health'), node('strong', '', stateLabel));
  overview.append(status, metrics([
    ['Service-impact alerts', summary.active_count],
    ['Diagnostics', summary.diagnostic_count],
    ['Critical', summary.critical_count],
    ['Warnings', summary.warning_count]
  ], true));
  const timing = node('dl', 'receiver-health-timing');
  [
    ['Monitoring since', receiverHealthTime(snapshot.started_at_ms)],
    ['Last update', receiverHealthTime(snapshot.generated_at_ms)]
  ].forEach(([label, value]) => {
    timing.append(node('dt', '', label));
    const detail = node('dd');
    detail.append(valueNode(value));
    timing.append(detail);
  });
  overview.append(timing);
  if (stale) overview.append(node('div', 'logging-notice warning receiver-health-stale-notice',
    `Showing the last receiver health snapshot. ${lastError || 'The latest refresh failed.'}`));

  host.append(section('Current status', overview, receiverHealthRefreshButton()),
    section('Active alerts and diagnostics', receiverHealthIncidentList(snapshot.active)),
    receiverHealthResolvedSection(snapshot.resolved));
  if (snapshot.measurements.length) {
    host.append(...snapshot.measurements.map(receiverHealthMeasurementGroup));
  } else {
    host.append(section('Measurements', node('div', 'receiver-health-empty',
      'No receiver health measurements were reported.')));
  }
}

async function renderAdminHealth() {
  const host = node('div', 'receiver-health-page');
  content.append(host);
  receiverHealthController.bindPage(host);
  void receiverHealthController.refresh();
}

function comingSoonPanel(title) {
  const panel = node('section', 'section placeholder-page');
  panel.append(node('h2', '', title), badge('Coming Soon', 'state-stale'));
  return panel;
}

async function renderConfiguration() {
  const renderContext = captureRenderContext();
  const availableTabs = [
    { id: 'scan-lists', label: 'Scan Lists' },
    { id: 'radioreference', label: 'RadioReference' },
    { id: 'recording', label: 'Recording' },
    { id: 'streaming', label: 'Streaming' }
  ];
  const requested = route.get('tab') || 'scan-lists';
  const active = availableTabs.some((item) => item.id === requested) ? requested : 'scan-lists';
  if (!beginPage(renderContext, pageHeader('Configuration',
    'Manage receiver configuration and external data sources'),
    tabs(availableTabs.map((item) => ({ ...item, href: href('configuration', { tab: item.id }) })), active))) return;
  if (active === 'scan-lists') await renderAdminScanLists();
  else if (active === 'radioreference') content.append(comingSoonPanel('RadioReference'));
  else if (active === 'recording') content.append(comingSoonPanel('Recording'));
  else content.append(comingSoonPanel('Streaming'));
}

function renderHardware() {
  const renderContext = captureRenderContext();
  beginPage(renderContext, pageHeader('Hardware', 'Inspect and configure receiver hardware'),
    comingSoonPanel('Tuners'));
}

function adminSystemStatusSection() {
  const database = serviceStatus?.database;
  const logging = statsLoggingState();
  const loggingState = logging.available && logging.state ? semanticLabel(logging.state) : 'Unknown';
  const inactiveState = loggingState !== 'Unknown' && loggingState !== 'Running' ? loggingState : 'Inactive';
  const summaryState = !logging.available ? 'Unknown' : logging.summaryActive ? 'Running' :
    (logging.summaryConfigured ? `Configured · ${inactiveState}` :
      (loggingState === 'Failed' ? 'Off · Failed' : 'Off'));
  const historyState = !logging.available ? 'Unknown' : logging.historyActive ? 'Running' :
    (logging.historyConfigured ? 'Configured · Inactive' :
      (logging.historyRetained ? 'Off · Data retained' : 'Off'));
  const databaseDisplay = adminDatabaseDisplay(database);
  const body = node('div', 'admin-section-body');
  body.append(metrics([
    ['Summary logging', logging.summaryActive, summaryState],
    ['Detailed history', logging.historyActive, historyState],
    ['Activity database', database?.database_bytes, databaseDisplay]
  ], true));
  const result = section('System status', body);
  result.id = 'admin-system-status';
  return result;
}

function renderAdminSystem() {
  content.append(adminSystemStatusSection());
}

function refreshAdminSystemStatus() {
  const current = document.getElementById('admin-system-status');
  if (current) current.replaceWith(adminSystemStatusSection());
}

async function renderAdmin() {
  const renderContext = captureRenderContext();
  const availableTabs = [
    { id: 'health', label: 'Health', capability: ACCESS_CAPABILITIES.RECEIVER_HEALTH },
    { id: 'live-activity', label: 'Live & Activity', capability: ACCESS_CAPABILITIES.ADMIN_SETTINGS },
    { id: 'web-audio', label: 'Listener Status', capability: ACCESS_CAPABILITIES.ADMIN_AUDIO },
    { id: 'users', label: 'Users', capability: ACCESS_CAPABILITIES.ADMIN_USERS },
    { id: 'access', label: 'Access', capability: ACCESS_CAPABILITIES.ADMIN_ACCESS },
    { id: 'system', label: 'System', capability: ACCESS_CAPABILITIES.ADMIN_SETTINGS }
  ].filter((item) => capabilityAllowed(item.capability));
  if (!availableTabs.length) throw Object.assign(new Error('Administrator access is unavailable.'), { status: 403 });
  const requested = route.get('tab') || 'health';
  const active = availableTabs.some((item) => item.id === requested) ? requested : availableTabs[0].id;
  if (active !== requested) {
    route.set('tab', active);
    window.history.replaceState({}, '', currentHref());
  }
  if (!beginPage(renderContext, pageHeader('Administration',
    'Monitor receiver health and manage receiver-wide web settings'),
    tabs(availableTabs.map((item) => ({ ...item, href: href('admin', { tab: item.id }) })), active))) return;
  if (active === 'health') await renderAdminHealth();
  else if (active === 'live-activity') await renderAdminLiveActivitySettings();
  else if (active === 'web-audio') await renderAdminWebAudio();
  else if (active === 'access') await renderAdminAccess();
  else if (active === 'system') renderAdminSystem();
  else await renderAdminUsers();
}

function routeViewLabel(view) {
  return ({
    dashboard: 'Dashboard', live: 'Live', scanner: 'Scanner', 'tuner-spectrum': 'Tuner Spectrum',
    systems: 'Trunked', system: 'System details', configuration: 'Configuration', hardware: 'Hardware',
    site: 'Site details', talkgroup: 'Talkgroup details', radio: 'Radio details',
    conventional: 'Conventional', 'conventional-detail': 'Conventional details', aliases: 'Aliases',
    admin: 'Administration'
  })[view] || 'this page';
}

function renderAccessDenied(view, renderContext = captureRenderContext()) {
  const panel = node('section', 'access-denied-card');
  const heading = node('h2', '', accessSessionAvailable ? 'Access denied' : 'Access information unavailable');
  const detail = !accessSessionAvailable ?
    'The receiver did not return its access policy. Retry before opening protected pages.' :
    (!accessSession.configured ?
      'Set the primary administrator password from the local JavaFX Web Server settings before signing in.' :
      (accessSession.authenticated ?
      `${accessSession.username} is signed in with ${accessTierLabel(accessSession.tier)} access, which does not include ${routeViewLabel(view)}.` :
      `${routeViewLabel(view)} is not available to public visitors. Sign in with an authorized account.`));
  panel.append(heading, node('p', '', detail));
  const actions = node('div', 'admin-form-actions');
  const action = node('button', '', accessSession.authenticated ? 'Return to an available page' :
    (accessSessionAvailable ? 'Sign In' : 'Retry'));
  action.type = 'button';
  action.addEventListener('click', async () => {
    if (accessSession.authenticated) {
      const first = [...document.querySelectorAll('.primary-nav a[data-view]')]
        .find((link) => viewAllowed(link.dataset.view));
      if (first) first.click();
    } else if (!accessSessionAvailable) {
      action.disabled = true;
      await refreshAccessSession(false);
      await render();
    } else {
      showLoginModal();
    }
  });
  actions.append(action);
  panel.append(actions);
  beginPage(renderContext, pageHeader('Access', routeViewLabel(view)), panel);
}

function renderCredits() {
  const renderContext = captureRenderContext();
  if (!beginPage(renderContext,
    pageHeader('Credits & Licensing', 'Open-source authorship, source lineage, and license terms'))) return;

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
  const parent = ['system', 'talkgroup', 'radio', 'site'].includes(view) ? 'systems' :
    (view === 'conventional-detail' ? 'conventional' : view);
  const activeTab = route.get('tab');
  document.querySelectorAll('.primary-nav a').forEach((link) => {
    const active = link.dataset.view === parent && (!link.dataset.navTab || link.dataset.navTab === activeTab);
    link.classList.toggle('active', active);
  });
  let activeGroup = null;
  document.querySelectorAll('.primary-nav .nav-group').forEach((group) => {
    const active = Boolean(group.querySelector('a.active'));
    group.classList.toggle('active', active);
    if (active) activeGroup = group;
  });
  closeNavigationGroups(navigationUsesDrawer() ? activeGroup : null);
  if (navigationUsesDrawer() && activeGroup) activeGroup.open = true;
}

function loggingAvailabilitySignature() {
  const logging = statsLoggingState();
  const historyMode = logging.historyActive ? 'active' : (logging.historyRetained ? 'retained' : 'unavailable');
  return [logging.available, logging.summaryActive, historyMode, logging.state,
    serviceStatusWarningRequired()].join('|');
}

async function reloadForWebClientRevision() {
  let serverRevision = '';
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 5_000);
  try {
    const response = await fetch('/', {
      method: 'HEAD', cache: 'no-store', credentials: 'same-origin', signal: controller.signal
    });
    if (!response.ok) return false;
    serverRevision = String(response.headers.get('X-Sdrtrunk-Web-Revision') || '').trim();
  } catch (error) {
    return false;
  } finally {
    window.clearTimeout(timeout);
  }

  if (!WEB_CLIENT_REVISION || !serverRevision || serverRevision === WEB_CLIENT_REVISION ||
      webClientReloadAttempted) return false;

  webClientReloadAttempted = true;
  const label = document.getElementById('global-status');
  if (label) label.textContent = 'Web update available · reloading';
  window.setTimeout(() => {
    if (label) label.textContent = 'Web update available · reload required';
  }, 1_000);
  window.location.reload();
  return true;
}

async function loadStatus(refreshCurrentView = false) {
  const previousSignature = loggingAvailabilitySignature();
  if (await reloadForWebClientRevision()) return;
  if (accessSessionAvailable && !capabilityAllowed(ACCESS_CAPABILITIES.DASHBOARD)) {
    clearServiceStatus();
    const status = document.getElementById('global-status');
    if (status) status.textContent = 'Status restricted';
    return;
  }
  try {
    await requestServiceStatus();
    const status = document.getElementById('global-status');
    if (status) status.textContent = 'Receiver status available';
  } catch (error) {
    const status = document.getElementById('global-status');
    if (status) status.textContent = serviceStatus ? 'Receiver status stale' : 'Receiver status unavailable';
  }

  const currentView = route.get('view') || 'dashboard';
  if (refreshCurrentView && currentView === 'admin' && route.get('tab') === 'system') {
    refreshAdminSystemStatus();
    return;
  }
  if (refreshCurrentView && previousSignature !== loggingAvailabilitySignature() &&
      !['live', 'scanner', 'configuration', 'hardware', 'tuner-spectrum', 'admin', 'credits']
        .includes(currentView)) {
    render();
  }
}

function liveDisplaySettingsSignature(value) {
  if (!value) return '';
  return JSON.stringify([value.format_version, value.show_encryption_details, value.retain_idle_call_details,
    value.show_control_decode_quality, value.show_voice_decode_quality,
    value.clear_voice_decode_quality_on_call_end, value.decode_quality_display_mode,
    value.traffic_grant_age_out_milliseconds, value.live_detail_matching_row_limit]);
}

async function refreshLiveDisplaySettings(refreshCurrentView = false) {
  const previous = liveDisplaySettingsSignature(liveDisplaySettings);
  if (!capabilityAllowed(ACCESS_CAPABILITIES.LIVE)) liveDisplaySettings = null;
  else {
    try {
      liveDisplaySettings = await requestJson('/api/v1/live/settings', { csrf: false, page: false });
    } catch (error) {
      // Preserve the last confirmed receiver policy across a transient read failure.
    }
  }
  const changed = previous !== liveDisplaySettingsSignature(liveDisplaySettings);
  if (refreshCurrentView && changed && (route.get('view') || 'dashboard') === 'live') await render();
  return liveDisplaySettings;
}

async function render() {
  setNavigationOpen(false);
  const view = route.get('view') || 'dashboard';
  if (!closeReadOnlyModal(false)) return;
  restorePlaybackBarBeforeRender();
  const epoch = ++activeRenderEpoch;
  activeRenderController?.abort();
  const renderController = new AbortController();
  activeRenderController = renderController;
  const renderContext = Object.freeze({ epoch, signal: renderController.signal });
  closePageConnections();
  const loading = node('div', 'loading', 'Loading');
  loading.setAttribute('role', 'status');
  content.setAttribute('aria-busy', 'true');
  content.replaceChildren(loading);

  let effectiveView = view;
  try {
    const handlers = {
      dashboard: renderDashboard,
      live: renderLive,
      scanner: renderScanner,
      'tuner-spectrum': renderTunerSpectrum,
      systems: renderSystems,
      system: renderSystem,
      talkgroup: renderTalkgroup,
      radio: renderRadio,
      site: renderSite,
      conventional: renderConventional,
      'conventional-detail': renderConventionalDetail,
      aliases: renderAliases,
      configuration: renderConfiguration,
      hardware: renderHardware,
      admin: renderAdmin,
      credits: renderCredits
    };
    effectiveView = handlers[view] ? view : 'dashboard';
    document.body.dataset.view = effectiveView;
    activateNavigation(effectiveView);
    if (!viewAllowed(effectiveView)) {
      document.body.dataset.view = 'access-denied';
      renderAccessDenied(effectiveView, renderContext);
      return;
    }
    await handlers[effectiveView]();
    if (epoch !== activeRenderEpoch || renderController.signal.aborted) return;
    const notice = databaseLoggingNotice(effectiveView);
    if (notice) {
      const header = content.querySelector('.page-header');
      if (header) header.after(notice);
      else content.prepend(notice);
    }
  } catch (error) {
    if (epoch !== activeRenderEpoch || renderController.signal.aborted || error?.name === 'AbortError') return;
    if (error?.status === 401 || error?.status === 403) {
      await refreshAccessSession(false);
      if (!renderIsCurrent(renderContext)) return;
      document.body.dataset.view = 'access-denied';
      renderAccessDenied(effectiveView, renderContext);
      return;
    }
    const notice = databaseLoggingNotice(view);
    beginPage(renderContext, ...[notice, node('div', 'error', error.message)].filter(Boolean));
  }
}

document.addEventListener('click', (event) => {
  const link = event.target.closest('a');
  if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey ||
      event.altKey || link.target || link.hasAttribute('download')) return;
  const target = new URL(link.href, window.location.href);
  if (target.origin !== window.location.origin || target.pathname !== '/') return;
  event.preventDefault();
  if (!closeReadOnlyModal(false)) return;
  window.history.pushState({}, '', `${target.pathname}${target.search}${target.hash}`);
  route = new URLSearchParams(target.search);
  render();
});
window.addEventListener('popstate', () => {
  setNavigationOpen(false);
  const previous = `/?${route.toString()}`;
  if (!closeReadOnlyModal(false)) {
    window.history.pushState({}, '', previous);
    return;
  }
  route = new URLSearchParams(window.location.search);
  render();
});
initializeThemeToggle();
initializeAccessControls();
initializeNavigation();
initializePlaybackHeader();
refreshAccessSession(false)
  .then(() => Promise.all([loadStatus(false), refreshLiveDisplaySettings(false),
    receiverHealthController.refresh()]))
  .finally(render);
let refreshCycle = null;
window.setInterval(() => {
  if (document.hidden || refreshCycle) return;
  refreshCycle = refreshAccessSession(true)
    .then(() => Promise.all([loadStatus(true), refreshLiveDisplaySettings(true),
      receiverHealthController.refresh()]))
    .finally(() => { refreshCycle = null; });
}, 10_000);
