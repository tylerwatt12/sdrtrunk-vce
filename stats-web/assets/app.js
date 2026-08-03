let route = new URLSearchParams(window.location.search);
const content = document.getElementById('content');
const tableOnly = route.get('layout') === 'table';
const TABLE_WIDTH_COOKIE = 'sdrtrunk_table_widths_v4';
const ALIAS_CATALOG_COLUMNS_STORAGE_KEY = 'sdrtrunk_alias_catalog_enrichment_columns_v1';
const TABLE_WIDTH_MINIMUM = 48;
const TABLE_WIDTH_MAXIMUM = 1200;
const SIGNAL_OFFLINE_MILLISECONDS = 45_000;
const SITE_METADATA_OFFLINE_MILLISECONDS = 30_000;
const DECODE_HEALTHY_MINIMUM_PERCENT = 90;
const DECODE_DEGRADED_MINIMUM_PERCENT = 75;
const VOICE_QUALITY_WARMUP_FRAMES = 50;
const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
const THEME_STORAGE_KEY = 'sdrtrunk_theme';
const ACCESS_CAPABILITIES = Object.freeze({
  DASHBOARD: 'dashboard',
  LIVE: 'live',
  SYSTEMS: 'systems',
  CONVENTIONAL: 'conventional',
  ALIASES: 'aliases',
  CREDITS: 'credits',
  CSV_EXPORT: 'csv-export',
  CALL_AUDIO: 'call-audio',
  ADMIN_USERS: 'admin-users',
  ADMIN_ACCESS: 'admin-access'
});
const VIEW_ACCESS_CAPABILITY = Object.freeze({
  dashboard: ACCESS_CAPABILITIES.DASHBOARD,
  live: ACCESS_CAPABILITIES.LIVE,
  systems: ACCESS_CAPABILITIES.SYSTEMS,
  system: ACCESS_CAPABILITIES.SYSTEMS,
  talkgroup: ACCESS_CAPABILITIES.SYSTEMS,
  radio: ACCESS_CAPABILITIES.SYSTEMS,
  site: ACCESS_CAPABILITIES.SYSTEMS,
  conventional: ACCESS_CAPABILITIES.CONVENTIONAL,
  'conventional-detail': ACCESS_CAPABILITIES.CONVENTIONAL,
  aliases: ACCESS_CAPABILITIES.ALIASES,
  credits: ACCESS_CAPABILITIES.CREDITS
});
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
const ALIAS_CATALOG_DEFAULT_ENRICHMENT_COLUMNS = Object.freeze([
  'calls', 'recorded', 'streamed', 'encrypted-evidence', 'grants', 'joins', 'emergency', 'logout',
  'relationships', 'last-evidence'
]);
let serviceStatus = null;
let tableWidthPreferences = readTableWidthPreferences();
let activeReadOnlyModal = null;
let accessSession = anonymousAccessSession();
let accessSessionAvailable = false;
let playbackConnection = null;

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

function normalizeAccessTier(value) {
  const tier = String(value || 'PUBLIC').trim().toUpperCase();
  return ['PUBLIC', 'USER', 'ADMIN'].includes(tier) ? tier : 'PUBLIC';
}

function accessTierRank(value) {
  return ({ PUBLIC: 0, USER: 1, ADMIN: 2 })[normalizeAccessTier(value)];
}

function accessTierLabel(value) {
  const tier = normalizeAccessTier(value);
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

function normalizeCapabilityMap(value) {
  if (Array.isArray(value)) {
    return Object.fromEntries(value.map((entry) => {
      if (typeof entry === 'string') return [entry, true];
      const id = String(entry?.id || entry?.capability || '').trim();
      return id ? [id, entry?.allowed ?? entry?.enabled ?? entry?.requiredTier ?? false] : null;
    }).filter(Boolean));
  }
  return value && typeof value === 'object' ? { ...value } : {};
}

function normalizedAccessSession(value) {
  const authenticated = value?.authenticated === true;
  return {
    configured: value?.configured === true,
    authenticated,
    username: authenticated ? String(value.username || '').trim() : null,
    tier: authenticated ? normalizeAccessTier(value.tier) : 'PUBLIC',
    primary: authenticated && Boolean(value.primary ?? value.primaryAdmin),
    csrfToken: authenticated && typeof value.csrfToken === 'string' ? value.csrfToken : null,
    capabilities: normalizeCapabilityMap(value?.capabilities)
  };
}

function capabilityAllowed(capability) {
  if (!accessSessionAvailable) return false;
  const values = accessSession.capabilities || {};
  const entry = Object.prototype.hasOwnProperty.call(values, capability) ? values[capability] : undefined;
  if (typeof entry === 'boolean') return entry;
  if (typeof entry === 'number') return entry !== 0;
  if (typeof entry === 'string') {
    const normalized = entry.trim().toUpperCase();
    if (normalized === 'TRUE') return true;
    if (normalized === 'FALSE') return false;
    if (['PUBLIC', 'USER', 'ADMIN'].includes(normalized)) {
      return accessTierRank(accessSession.tier) >= accessTierRank(normalized);
    }
    return false;
  }
  if (entry && typeof entry === 'object') {
    if (typeof entry.allowed === 'boolean') return entry.allowed;
    if (typeof entry.enabled === 'boolean') return entry.enabled;
    const requiredTier = String(entry.requiredTier || '').trim().toUpperCase();
    if (['PUBLIC', 'USER', 'ADMIN'].includes(requiredTier)) {
      return accessTierRank(accessSession.tier) >= accessTierRank(requiredTier);
    }
  }
  return capability.startsWith('admin-') && accessSession.tier === 'ADMIN';
}

function viewAccessCapability(view) {
  return VIEW_ACCESS_CAPABILITY[view] || null;
}

function viewAllowed(view) {
  if (view === 'admin') {
    return accessSession.tier === 'ADMIN' &&
      (capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_USERS) ||
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
    const allowed = viewAllowed(link.dataset.view);
    link.hidden = !allowed;
    link.setAttribute('aria-hidden', String(!allowed));
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
    label.textContent = 'Public';
    label.title = 'Using public access';
    action.textContent = 'Sign In';
    action.disabled = false;
  }
  updateNavigationAccess();
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
      await requestJson('/api/v1/auth/login', {
        method: 'POST', body: { username: username.value, password: password.value }, csrf: false
      });
      password.value = '';
      await refreshAccessSession(false);
      if (!accessSession.authenticated) throw new Error('The receiver did not create a session.');
      modal.close();
      await render();
    } catch (error) {
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
    await requestJson('/api/v1/auth/logout', { method: 'POST', csrf: true });
  } catch (error) {
    openReadOnlyModal('Unable to sign out', node('div', 'error', error.message), {
      id: 'sign-out-error', returnFocusSelector: '#auth-action', className: 'admin-modal'
    });
    if (action) action.disabled = false;
    return;
  }
  await refreshAccessSession(false);
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

async function refreshAccessSession(refreshCurrentView = false) {
  const previousSignature = accessSessionSignature();
  try {
    const session = await requestJson('/api/v1/auth/session', { csrf: false });
    accessSession = normalizedAccessSession(session);
    accessSessionAvailable = true;
  } catch (error) {
    accessSession = anonymousAccessSession();
    accessSessionAvailable = false;
  }
  updateAccessControls();
  synchronizePlaybackAccess();
  if (refreshCurrentView && previousSignature !== accessSessionSignature()) await render();
  return accessSession;
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

function identityNumber(row, value) {
  const numeric = Number(value);
  if (protocolFamily(row) === 'NXDN' && Number(row?.identity_domain_code) === 2 &&
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
    if (row.scope_kind_code !== undefined || row.identity_kind_code !== undefined ||
        row.target_kind_code !== undefined) {
      return ({ 1: 'Type-C', 2: 'Type-D' })[code] || '';
    }
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
  return { scope: row?.scope_token || '' };
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
  parameters.set('dataset', dataset);
  Object.entries(context).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') parameters.set(key, String(value));
  });
  ['q', 'sort', 'direction'].forEach((key) => {
    const value = route.get(key);
    if (value) parameters.set(key, value);
  });
  return `/api/export.csv?${parameters}`;
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
  return label && Number.isInteger(aliasListId) && aliasListId > 0 ?
    anchor(label, href('aliases', { list: aliasListId })) : label;
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
  if (!row?.scope_token) return label;
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

function talkgroupLink(row, id = row.talkgroup_id, label, explicitKindCode) {
  if (id === null || id === undefined || !row?.scope_token) return label || identityNumber(row, id);
  const kindCode = Number(explicitKindCode ?? row.identity_kind_code ?? row.target_kind_code);
  const kind = kindCode === 3 ? 'patch' : null;
  return anchor(label || identityNumber(row, id),
    href('talkgroup', { ...scope(row), id, kind, tab: 'info' }));
}

function radioLink(row, id = row.radio_id, label) {
  if (id === null || id === undefined || !row?.scope_token) return label || identityNumber(row, id);
  return anchor(label || identityNumber(row, id), href('radio', { ...scope(row), id, tab: 'info' }));
}

function talkgroupAliasLink(row, id, prefix = 'alias_', explicitKindCode) {
  if (id === null || id === undefined) return '';
  const name = row[`${prefix}name`];
  return name ? talkgroupLink(row, id, name, explicitKindCode) : '';
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

function closeReadOnlyModal(updateRoute = false) {
  const active = activeReadOnlyModal;
  if (!active) return;
  activeReadOnlyModal = null;
  document.removeEventListener('keydown', active.keydown);
  active.backdrop.remove();
  document.body.classList.remove('modal-open');
  if (updateRoute && route.has('alias')) {
    route.delete('alias');
    window.history.replaceState({}, '', currentHref());
  }
  const returnFocus = active.returnFocusSelector ? document.querySelector(active.returnFocusSelector) : null;
  if (returnFocus instanceof HTMLElement) returnFocus.focus();
}

function openReadOnlyModal(title, body, options = {}) {
  closeReadOnlyModal(false);
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
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])')]
    .filter((element) => !element.hidden);
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
  activeReadOnlyModal = {
    backdrop, keydown, returnFocusSelector: options.returnFocusSelector || null
  };
  document.addEventListener('keydown', keydown);
  document.body.classList.add('modal-open');
  document.body.append(backdrop);
  close.focus();
  return { dialog, content: contentNode, close: dismiss, state: activeReadOnlyModal };
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
  if (tableOnly || ['live', 'admin', 'credits'].includes(view)) return null;
  if (accessSessionAvailable && !capabilityAllowed(ACCESS_CAPABILITIES.DASHBOARD)) return null;
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

function pagedSection(title, page, columns, searchPlaceholder, tableType, action = null) {
  return fragment(searchPlaceholder ? searchBar(searchPlaceholder) : null,
    (() => {
      const block = section(title, table(page.rows, columns, 'No rows', {
        type: tableType,
        serverSort: true,
        defaultSort: SERVER_TABLE_DEFAULT_SORTS[tableType],
        defaultDirection: 'desc'
      }), action);
      block.append(pager(page));
      return block;
    })());
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
  const priority = row.priority === null || row.priority === undefined ? Number.NaN : Number(row.priority);
  if (priority === -1) values.push(badge('Muted', 'state-stale', 'This alias is not monitored for audio'));
  else if (Number.isFinite(priority) && priority >= 0 && priority < 100) {
    values.push(badge(`Priority ${identifierNumber(priority)}`, '', 'Audio monitoring priority'));
  }
  if (Number(row.record_enabled)) values.push(badge('Record', 'state-current'));
  const destinations = Array.isArray(row.broadcast_channels) ? row.broadcast_channels.length : 0;
  if (destinations) values.push(badge(`Stream ×${identifierNumber(destinations)}`, 'state-current'));
  if (row.stream_as_talkgroup !== null && row.stream_as_talkgroup !== undefined) {
    values.push(badge(`As TG ${identifierNumber(row.stream_as_talkgroup)}`));
  }
  return values.length ? badgeGroup(values) : badge('Default', 'state-historical');
}

function aliasDetailLink(row) {
  const id = Number(row.alias_id);
  const label = String(row.name || '').trim() || `Alias ${identifierNumber(id)}`;
  if (!Number.isInteger(id) || id <= 0) return label;
  const link = anchor(label, currentHref({ alias: id }), 'alias-detail-link');
  link.dataset.aliasId = String(id);
  return link;
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
    'Configuration columns remain visible. Choose any call, signaling, and relationship evidence columns.'));
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
    [row.name, row.family, `${number(row.alias_count)} aliases`].filter(Boolean).join(' · ')]);
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
    selectFilter('Family', 'family', [['', 'All families'], ...families.map((family) => [family, family])]),
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

function aliasScopeBreakdownColumns() {
  const count = (id, label, field, group, fullLabel = label) => ({
    id, label, group, fullLabel, render: (row) => aliasMetricValue(row, field), className: 'numeric',
    sortValue: (row) => row[field] === null || row[field] === undefined ? -1 : Number(row[field])
  });
  return [
    { id: 'scope', label: 'Scope', group: 'Scope', render: (row) => availableValue(row.scope_label),
      className: 'alias-cell' },
    { id: 'topology', label: 'Topology', group: 'Scope', render: (row) => availableValue(row.topology) },
    { id: 'protocol', label: 'Protocol', group: 'Scope', render: (row) => availableValue(row.protocol) },
    { id: 'system', label: 'System', group: 'Scope', render: (row) => availableValue(row.system_name),
      className: 'alias-cell' },
    { id: 'site', label: 'Site / Channel', group: 'Scope', render: (row) =>
      availableValue(row.site_name), className: 'alias-cell' },
    { id: 'evidence-state', label: 'Evidence', group: 'Scope', render: (row) =>
      aliasMetricsState(row.metrics_state) },
    count('calls', 'Calls', 'call_count', 'Call Activity',
      'Call observations associated with this alias in this scope.'),
    count('recorded', 'Recorded', 'recorded_count', 'Call Activity'),
    count('streamed', 'Sent', 'streamed_count', 'Call Activity'),
    count('encrypted-evidence', 'Enc Obs.', 'encrypted_evidence_count', 'Call Activity',
      'Encrypted observations, not necessarily unique completed calls.'),
    count('grants', 'Grants', 'grant_count', 'Signaling / Relationships'),
    count('joins', 'Join', 'join_count', 'Signaling / Relationships'),
    count('emergency', 'Emergency', 'emergency_count', 'Signaling / Relationships'),
    count('register', 'Register', 'register_count', 'Signaling / Relationships'),
    count('logout', 'Logout', 'logout_count', 'Signaling / Relationships',
      'Unit deregistration or logout observations, not talkgroup leaves.'),
    count('denial', 'Denial', 'denial_count', 'Signaling / Relationships'),
    count('data', 'Data', 'data_count', 'Signaling / Relationships'),
    count('other-signaling', 'Other', 'other_signaling_count', 'Signaling / Relationships'),
    count('relationships', 'Relationships', 'relationship_count', 'Signaling / Relationships'),
    count('join-relationships', 'Join Rel.', 'join_relationship_count', 'Signaling / Relationships'),
    count('current-affiliations', 'Current Affil.', 'current_affiliation_count',
      'Signaling / Relationships'),
    { id: 'first-evidence', label: 'First Evidence', group: 'Signaling / Relationships',
      render: (row) => aliasMetricTime(row, 'first_evidence_ms'),
      sortValue: (row) => Number(row.first_evidence_ms || 0) },
    { id: 'last-evidence', label: 'Last Evidence', group: 'Signaling / Relationships',
      render: (row) => aliasMetricTime(row, 'last_evidence_ms'),
      sortValue: (row) => Number(row.last_evidence_ms || 0) }
  ];
}

function aliasDetailContent(alias, breakdown) {
  const wrapper = node('div', 'alias-detail');
  wrapper.append(section('Configuration', keyValues([
    ['Alias List', aliasListLink(alias.alias_list_name, alias.alias_list_id)],
    ['Family', availableValue(alias.family)],
    ['Alias', availableValue(alias.name)],
    ['Description', availableValue(alias.description)],
    ['Group', availableValue(alias.group)],
    ['Matcher', availableValue(alias.matcher_label || alias.matcher_type)],
    ['Identifier', availableValue(alias.identifier_display)],
    ['Color', aliasColorValue(alias)],
    ['Icon', availableValue(alias.icon_name)],
    ['Behavior', aliasBehavior(alias)]
  ])));

  wrapper.append(section('Raw Matcher Values', keyValues([
    ['Matcher Type', availableValue(alias.matcher_type)],
    ['Identity Type', availableValue(alias.identity_type)],
    ['Protocol', availableValue(alias.protocol)],
    ['Exact', alias.exact === null || alias.exact === undefined ? '—' : yesNoKnown(alias.exact)],
    ['Ranged', alias.ranged === null || alias.ranged === undefined ? '—' : yesNoKnown(alias.ranged)],
    ['Fully Qualified', alias.fully_qualified === null || alias.fully_qualified === undefined ? '—' :
      yesNoKnown(alias.fully_qualified)],
    ['Value', aliasRawValue(alias.value)],
    ['Minimum', aliasRawValue(alias.min_value)],
    ['Maximum', aliasRawValue(alias.max_value)],
    ['WACN', alias.wacn === null || alias.wacn === undefined ? '—' : hexDecimalPair(alias.wacn, 5)],
    ['P25 System', alias.p25_system_id === null || alias.p25_system_id === undefined ? '—' :
      hexDecimalPair(alias.p25_system_id, 3)],
    ['Text Value', availableValue(alias.text_value)],
    ['Numeric Value', aliasRawValue(alias.numeric_value)],
    ['Tone Sequence', availableValue(alias.tone_sequence)],
    ['Stream as Talkgroup', aliasRawValue(alias.stream_as_talkgroup)]
  ])));

  const destinations = node('ul', 'alias-destination-list');
  const channels = Array.isArray(alias.broadcast_channels) ? alias.broadcast_channels : [];
  channels.forEach((channel) => destinations.append(node('li', '', channel)));
  wrapper.append(section('Broadcast Destinations', channels.length ? destinations :
    node('div', 'empty', 'No broadcast destinations configured')));

  wrapper.append(section('Call Activity', aliasDetailMetricBand(alias, [
    ['Calls', 'call_count'], ['Recorded', 'recorded_count'], ['Sent', 'streamed_count'],
    ['Enc Obs.', 'encrypted_evidence_count']
  ])));
  wrapper.append(section('Signaling / Relationship Evidence', fragment(
    aliasDetailMetricBand(alias, [
      ['Grants', 'grant_count'], ['Join', 'join_count'], ['Emergency', 'emergency_count'],
      ['Register', 'register_count'], ['Logout', 'logout_count'], ['Denial', 'denial_count'],
      ['Data', 'data_count'], ['Other', 'other_signaling_count'],
      ['Relationships', 'relationship_count'], ['Join Relationships', 'join_relationship_count'],
      ['Current Affiliations', 'current_affiliation_count'], ['Covered Scopes', 'coverage_scope_count'],
      ['Observed Scopes', 'observed_scope_count']
    ]),
    keyValues([
      ['Collection State', aliasMetricsState(alias.metrics_state)],
      ['First Evidence', aliasMetricTime(alias, 'first_evidence_ms')],
      ['Last Evidence', aliasMetricTime(alias, 'last_evidence_ms')]
    ]),
    node('p', 'metric-meaning-note',
      'Calls are observations. Logout means unit deregistration, not leaving a talkgroup. ' +
      'An em dash means unavailable or not collected; 0 means coverage was collected and the count was zero.')
  )));

  const scopeRows = Array.isArray(breakdown) ? breakdown : [];
  wrapper.append(section('Scope Breakdown', table(scopeRows, aliasScopeBreakdownColumns(),
    'No compatible monitored scopes', { type: 'alias-scope-breakdown' })));
  return wrapper;
}

async function renderAliasDetailModal(id) {
  const modal = openReadOnlyModal(`Alias ${identifierNumber(id)}`, node('div', 'loading', 'Loading alias details'), {
    id: `alias-${id}`,
    returnFocusSelector: `.alias-detail-link[data-alias-id="${id}"]`
  });
  try {
    const response = await api('/api/alias', { id });
    if (activeReadOnlyModal !== modal.state || Number(route.get('alias')) !== id) return;
    const alias = response.alias || {};
    modal.dialog.querySelector('.modal-header h2').textContent = String(alias.name || '').trim() ||
      `Alias ${identifierNumber(id)}`;
    modal.content.replaceChildren(aliasDetailContent(alias, response.breakdown || []));
  } catch (error) {
    if (activeReadOnlyModal === modal.state) modal.content.replaceChildren(node('div', 'error', error.message));
  }
}

async function renderAliases() {
  const filters = {
    list: route.get('list'), family: route.get('family'), type: route.get('type'),
    matcher: route.get('matcher')
  };
  const [listResponse, page] = await Promise.all([
    api('/api/alias-lists'),
    api('/api/aliases', pageParameters(filters))
  ]);
  const selectedList = (listResponse.rows || []).find((row) =>
    String(row.alias_list_id) === String(route.get('list') || ''));
  const subtitle = selectedList ?
    `${selectedList.name} · ${selectedList.family} · ${number(selectedList.alias_count)} configured aliases · ` +
      `${number(selectedList.assigned_channel_count)} assigned channels` :
    `${number(listResponse.count ?? (listResponse.rows || []).length)} alias lists · read-only`;
  content.append(pageHeader('Alias Catalog', subtitle));
  content.append(aliasCatalogFilterToolbar(listResponse));

  const definitions = aliasCatalogEnrichmentColumns();
  const selected = readAliasCatalogColumnSelection(definitions);
  const tableHost = node('div', 'alias-catalog-table-host');
  const renderTable = () => {
    const optional = definitions.filter((column) => selected.has(column.id));
    tableHost.replaceChildren(table(page.rows || [], [...aliasCatalogCoreColumns(), ...optional],
      'No configured aliases match these filters', {
        type: 'alias-catalog', serverSort: true, sortable: false,
        defaultSort: 'name', defaultDirection: 'asc'
      }));
  };
  const sortIsVisible = () => {
    const current = route.get('sort');
    if (!current) return true;
    return [...aliasCatalogCoreColumns(), ...definitions.filter((column) => selected.has(column.id))]
      .some((column) => column.sort === current);
  };
  const onColumnChange = () => {
    if (!sortIsVisible()) {
      route.set('sort', 'name');
      route.set('direction', 'asc');
      route.delete('offset');
      window.history.replaceState({}, '', currentHref());
      render();
      return;
    }
    renderTable();
  };
  const chooser = aliasColumnChooser(definitions, selected, onColumnChange);
  const exportContext = {};
  ['list', 'family', 'type', 'matcher'].forEach((key) => {
    if (route.get(key)) exportContext[key] = route.get(key);
  });
  const actions = node('div', 'section-title-actions');
  actions.append(chooser, exportCsvLink('aliases', exportContext));
  const block = section('Configured Aliases', tableHost, actions);
  block.classList.add('alias-catalog-section');
  renderTable();
  block.append(node('p', 'metric-meaning-note alias-catalog-guide',
    'Every configured alias is shown, including aliases never observed on the air. Calls are observations. ' +
    'Logout means deregistration, not leaving a talkgroup. An em dash means unavailable or not collected; ' +
    '0 means coverage was collected and the count was zero.'), pager(page));
  content.append(block);

  if (route.has('alias')) {
    const aliasId = Number(route.get('alias'));
    if (Number.isInteger(aliasId) && aliasId > 0) await renderAliasDetailModal(aliasId);
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
  const block = section('Signal Health', host, exportCsvLink('signal-health'));
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
    exportLink.href = exportCsvHref('site-quality', { guid: site.guid, range: selectedRange });
    await load(buttons, true);
  });
  const exportLink = exportCsvLink('site-quality', { guid: site.guid, range: selectedRange });
  const titleActions = node('div', 'section-title-actions');
  titleActions.append(rangeControl.controls, exportLink);
  block.querySelector('.section-title').append(titleActions);
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
      section('Retained Signaling Totals', table(
        signalingCounts(response.totals || {}).map(([action, count]) => ({ action, count })), [
          { label: 'Action', key: 'action' },
          { id: 'count', label: 'Count', render: (row) => number(row.count),
            className: 'numeric', sortValue: (row) => Number(row.count || 0) }
        ], 'No signaling observations recorded', { type: 'action-counts' })),
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
    { id: 'talkgroup-kind', label: 'Kind', render: (row) =>
      Number(row.target_kind_code ?? row.identity_kind_code) === 3 ? 'Patch' : 'TG' },
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

async function requestJson(path, options = {}) {
  const method = String(options.method || 'GET').toUpperCase();
  const headers = new Headers(options.headers || {});
  headers.set('Accept', 'application/json');
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
  if (options.csrf !== false && !['GET', 'HEAD', 'OPTIONS'].includes(method) && accessSession.csrfToken) {
    headers.set('X-CSRF-Token', accessSession.csrfToken);
  }
  const response = await fetch(path, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    cache: 'no-store',
    credentials: 'same-origin'
  });
  const contentType = String(response.headers.get('Content-Type') || '').toLowerCase();
  let result = null;
  if (response.status !== 204) {
    if (contentType.includes('json')) result = await response.json().catch(() => null);
    else {
      const message = await response.text().catch(() => '');
      result = message ? { error: message } : null;
    }
  }
  if (!response.ok) {
    const error = new Error(result?.message || result?.error || `${path} returned ${response.status}`);
    error.status = response.status;
    error.code = result?.code || null;
    error.path = path;
    throw error;
  }
  return result;
}

async function api(path, parameters = {}) {
  const query = new URLSearchParams();
  Object.entries(parameters).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') query.set(key, String(value));
  });
  return requestJson(`${path}${query.size ? `?${query}` : ''}`, { csrf: false });
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
  const bar = document.getElementById('playback-bar');
  if (bar) {
    bar.classList.add('access-unavailable');
    bar.setAttribute('aria-disabled', 'true');
    bar.querySelectorAll('button, input').forEach((control) => { control.disabled = true; });
  }
}

function synchronizePlaybackAccess() {
  if (tableOnly) return;
  const allowed = capabilityAllowed(ACCESS_CAPABILITIES.CALL_AUDIO);
  const bar = document.getElementById('playback-bar');
  const status = document.getElementById('playback-status');
  if (!bar || !status) return;
  bar.classList.toggle('access-unavailable', !allowed);
  bar.setAttribute('aria-disabled', String(!allowed));

  if (!allowed) {
    if (playbackConnection) {
      playbackConnection.close();
      liveConnections.delete(playbackConnection);
      playbackConnection = null;
    }
    const unavailableMessage = !accessSessionAvailable ? 'Access unavailable' :
      (accessSession.authenticated ? 'Web audio unavailable' : 'Sign in for web audio');
    if (window.sdrtrunkWebPlayer) window.sdrtrunkWebPlayer.disconnect(unavailableMessage);
    else status.textContent = unavailableMessage;
    bar.querySelectorAll('button, input').forEach((control) => { control.disabled = true; });
    return;
  }

  if (!window.sdrtrunkWebPlayer) {
    window.sdrtrunkWebPlayer = new WebCallPlayer({
      mute: 'playback-mute',
      hold: 'playback-hold',
      avoid: 'playback-avoid',
      clear: 'playback-clear',
      skip: 'playback-skip',
      volume: 'playback-volume',
      volumeValue: 'playback-volume-value',
      current: 'playback-current',
      queued: 'playback-queued',
      dropped: 'playback-dropped',
      queueList: 'playback-queue-list',
      maximumQueued: 'playback-max-queued',
      status: 'playback-status'
    });
  }
  bar.querySelectorAll('button, input').forEach((control) => { control.disabled = false; });
  window.sdrtrunkWebPlayer.render();
  if (!playbackConnection) {
    playbackConnection = window.sdrtrunkWebPlayer.connect('/live/web-calls');
    liveConnections.add(playbackConnection);
  }
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
  const items = [
    { id: 'info', label: 'Info', href: href('system', { ...values, tab: 'info' }) },
    { id: 'talkgroups', label: 'Talkgroups', href: href('system', { ...values, tab: 'talkgroups' }) },
    { id: 'radios', label: 'Radios', href: href('system', { ...values, tab: 'radios' }) },
    { id: 'activity', label: 'Activity', href: href('system', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' }
  ];
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
  const values = { ...scope(system), id, kind: kind === 'patch' ? 'patch' : null };
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
  { id: 'talkgroup-kind', label: 'Kind', render: (row) =>
    Number(row.target_kind_code) === 3 ? 'Patch' : 'TG' },
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
    columns.push(
      { id: 'talkgroup-id', label: 'Affil TG', fullLabel: 'Affiliated Talkgroup ID',
        render: (row) => talkgroupLink(row, row.affiliated_talkgroup_id), className: 'numeric',
        sort: 'affiliated_talkgroup', sortValue: (row) => Number(row.affiliated_talkgroup_id) },
      { id: 'talkgroup-name', label: 'TG Alias', fullLabel: 'Talkgroup Alias',
        render: (row) => talkgroupAliasLink(row, row.affiliated_talkgroup_id,
          'affiliated_talkgroup_alias_'), className: 'alias-cell',
        sortValue: (row) => row.affiliated_talkgroup_alias_name || '' }
    );
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
      channelTagSet(row.tags).has('CURRENT_CONTROL'));
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
      tab.setAttribute('aria-label', label);
    } else if (signalStrength === null && decodeQuality === null) {
      quality.className = 'systems-tab-quality quality-unavailable';
      tab.title = `${label} · Signal strength and decode quality unavailable`;
      tab.setAttribute('aria-label', `${label}, signal strength and decode quality unavailable`);
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
      tab.setAttribute('aria-label', `${label}, ${signalLabel}, ${qualityLabel}`);
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
    'P25, DMR, and NXDN systems with their observed sites'));
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
        heading.append(systemLink(row, label));
        wrapper.append(heading);
      } else {
        wrapper.append(node('span', 'directory-branch', '↳'),
          siteLink(row, siteValue(row)));
      }
      return wrapper;
    } },
    { id: 'protocol', label: 'Protocol', render: (row) => protocolFamily(row) },
    { label: 'Variant / Model', render: (row) => {
      if (isP25(row)) return '';
      return [...new Set([trunkedVariant(row), identityDomainLabel(row)].filter(Boolean))].join(' · ');
    } },
    { id: 'wacn', label: 'WACN / Net', fullLabel: 'WACN or Network', className: 'numeric', render: (row) =>
      isP25(row) ? (row.directory_type === 'system' ? hex(row.wacn, 5) : '') :
        (row.directory_type === 'system' ? identifierNumber(row.network_id) : '') },
    { id: 'system', label: 'Sys ID', fullLabel: 'System ID', className: 'numeric', render: (row) => {
      if (row.directory_type !== 'system') return '';
      return isP25(row) ? hex(row.system_id, 3) : identifierNumber(row.system_id);
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
      row.directory_type === 'system' ? number(row.talkgroups) : '' },
    { id: 'radios', label: 'Radios', className: 'numeric', render: (row) =>
      row.directory_type === 'system' ? number(row.radios) : '' },
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

async function renderSystem() {
  const systemScope = requiredSystemScope();
  const response = await api('/api/system', systemScope);
  const system = response.system;
  const requestedTab = route.get('tab') || 'info';
  const tabItems = systemTabItems(system);
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : 'info';
  if (tab !== requestedTab) {
    route.set('tab', tab);
    window.history.replaceState({}, '', currentHref());
  }
  content.append(pageHeader(systemValue(system), system.site_names || `${protocolFamily(system)} trunked system`),
    systemTabs(system, tab));

  if (tab === 'talkgroups') {
    const page = await api('/api/system/talkgroups', pageParameters(systemScope));
    content.append(pagedSection('Talkgroups', page, talkgroupColumns, 'Search talkgroup ID', 'talkgroups',
      exportCsvLink('system-talkgroups', systemScope)));
  } else if (tab === 'radios') {
    const page = await api('/api/system/radios', pageParameters(systemScope));
    content.append(pagedSection('Radios', page, systemRadioColumns(system), 'Search radio ID', 'radios',
      exportCsvLink('system-radios', systemScope)));
  } else if (tab === 'talker-aliases') {
    const page = await api('/api/system/talker-aliases', pageParameters(systemScope));
    const columns = [
    { id: 'radio', label: 'Radio', fullLabel: 'Radio ID', render: (row) => radioLink(row), className: 'numeric', sort: 'radio', sortValue: (row) => Number(row.radio_id) },
      { id: 'talker-alias', label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
      { id: 'radio-alias', label: 'Alias', fullLabel: 'Configured Alias', render: (row) => aliasLabel(row) ? radioLink(row, row.radio_id, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
      { id: 'talkgroup-id', label: 'Last TGID', render: (row) => talkgroupLink(row,
        row.last_talkgroup_id, undefined, row.last_talkgroup_kind_code), className: 'numeric',
        sort: 'last_talkgroup', sortValue: (row) => Number(row.last_talkgroup_id) },
      { id: 'talkgroup-name', label: 'TG Alias', fullLabel: 'Talkgroup Alias',
        render: (row) => talkgroupAliasLink(row, row.last_talkgroup_id, 'talkgroup_alias_',
          row.last_talkgroup_kind_code), className: 'alias-cell', sort: 'last_talkgroup_name',
        sortValue: (row) => row.talkgroup_alias_name || '' },
      { id: 'calls', label: 'Calls', render: (row) => number(row.call_count), className: 'numeric', sort: 'calls', sortValue: (row) => Number(row.call_count || 0) },
      { id: 'encrypted', label: 'Enc', render: (row) => number(row.encrypted_count), className: 'numeric encrypted', sort: 'encrypted', sortValue: (row) => Number(row.encrypted_count || 0) },
      { id: 'last-seen', label: 'Alias Seen', fullLabel: 'Talker Alias Last Seen', render: (row) => dateTime(row.last_talker_alias_seen_ms), sort: 'talker_alias_seen', sortValue: (row) => Number(row.last_talker_alias_seen_ms || 0) }
    ];
    const block = pagedSection('Talker Alias Summary', page, columns,
      'Search radio ID or talker alias', 'talker-aliases');
    if (!page.rows.length) block.querySelector('.empty').textContent = 'No talker aliases recorded for this system';
    content.append(block);
  } else if (tab === 'activity') {
    await renderActivity(systemScope, 'System Activity');
  } else {
    const infoColumn = node('div', 'entity-info-column system-info-column');
    const blocks = [section('Directory', metrics([
      ['Known Sites', system.sites],
      ['Known Talkgroups', system.talkgroups],
      ['Known Radios', system.radios]
    ], true)), section('Retained Call Activity', metrics([
      ['Calls', system.activity_retained_calls],
      ['Recorded', system.activity_recorded],
      ['Sent to Streamer', system.activity_streamed],
      ['Encrypted', system.activity_encrypted]
    ], true))];
    if (systemCapability(system, 'current_affiliations')) {
      blocks.push(section('Current State', metrics([
        ['Currently Affiliated', system.affiliations]
      ], true)));
    }
    blocks.push(section('System Info', keyValues([
      ['System', systemInfoValue(system)],
      ['First Seen', dateTime(system.first_seen_ms)], ['Last Seen', dateTime(system.last_seen_ms)]
    ])), section('Retained Signaling Observations', fragment(table(
      signalingActionRows(response.actionCounts), [
      { label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No signaling observations recorded', { type: 'action-counts' }), activityMetricGuide())));
    infoColumn.append(...blocks);

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
  const kind = route.get('kind') === 'patch' ? 'patch' : 'talkgroup';
  const response = await api('/api/talkgroup', { ...systemScope, talkgroup_id: id, kind });
  const talkgroup = response.talkgroup;
  const tab = route.get('tab') || 'info';
  const formattedId = identityNumber(talkgroup, id);
  const kindLabel = kind === 'patch' ? 'Patch Group' : 'Talkgroup';
  const title = aliasLabel(talkgroup) || `${kindLabel} ${formattedId}`;
  content.append(pageHeader(title, fragment(systemValue(talkgroup), ` · ${kindLabel} ${formattedId}`)),
    entityTabs('talkgroup', talkgroup, id, tab, false, kind));

  if (tab === 'radios') {
    const relationships = await api('/api/relationships',
      pageParameters({ ...systemScope, talkgroup_id: id, kind }));
    const affiliations = systemCapability(talkgroup, 'current_affiliations') ?
      await api('/api/affiliations', { ...systemScope, talkgroup_id: id, limit: 500 }) : { rows: [] };
    const affiliated = new Set((affiliations.rows || []).map((row) => Number(row.radio_id)));
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
    if (systemCapability(talkgroup, 'current_affiliations')) {
      columns.splice(systemCapability(talkgroup, 'talker_aliases') ? 3 : 2, 0,
        { id: 'affiliated', label: 'Affil', fullLabel: 'Affiliated',
          render: (row) => checkbox(affiliated.has(Number(row.radio_id))), className: 'center',
          sort: 'affiliated', sortValue: (row) => affiliated.has(Number(row.radio_id)) });
    }
    content.append(pagedSection('Radios', relationships, columns, null, 'talkgroup-radios'));
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
      [kind === 'patch' ? 'Patch Group ID' : 'Talkgroup ID', formattedId],
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
    if (systemCapability(talkgroup, 'current_affiliations')) {
      blocks.push(section('Current State', keyValues([
        ['Currently Affiliated', anchor(number(talkgroup.affiliated_radios),
          href('talkgroup', { ...scope(talkgroup), id, kind: kind === 'patch' ? 'patch' : null,
            tab: 'radios' }))]
      ])));
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
    layout.append(infoColumn, await talkgroupActivityHistorySection({ ...systemScope, talkgroup_id: id, kind }));
    content.append(layout);
  }
}

async function renderRadio() {
  const systemScope = requiredSystemScope();
  const id = requiredId();
  const response = await api('/api/radio', { ...systemScope, radio_id: id });
  const radio = response.radio;
  const tab = route.get('tab') || 'info';
  const formattedId = identityNumber(radio, id);
  const title = aliasLabel(radio) || radio.last_talker_alias || `Radio ${formattedId}`;
  content.append(pageHeader(title, fragment(systemValue(radio), ` · Radio ${formattedId}`)),
    entityTabs('radio', radio, id, tab, true));

  if (tab === 'talkgroups') {
    const relationships = await api('/api/relationships',
      pageParameters({ ...systemScope, radio_id: id }));
    const columns = [
      { id: 'talkgroup-id', label: 'TGID', render: (row) => talkgroupLink(row), className: 'numeric', sort: 'talkgroup', sortValue: (row) => Number(row.talkgroup_id) },
      { id: 'talkgroup-kind', label: 'Kind', render: (row) =>
        Number(row.target_kind_code) === 3 ? 'Patch' : 'TG' },
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
        ['Updated', dateTime(radio.affiliation_updated_at_ms)]
      ])));
    }
    blocks.push(section('Relationships', metrics([
      ['Observed Talkgroups', radio.talkgroups]
    ])), section('Last-known Facts', keyValues([
      ['Last Talkgroup', talkgroupLink(radio, radio.last_talkgroup_id, undefined,
        radio.last_talkgroup_kind_code)],
      ['Talkgroup Alias', talkgroupAliasLink(radio, radio.last_talkgroup_id,
        'last_talkgroup_alias_', radio.last_talkgroup_kind_code)],
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
  const data = await api('/api/site/channels', pageParameters({ guid: site.guid }));
  const explanation = protocolFamily(site) === 'DMR' ? node('p', 'muted',
    'DMR grants usually identify an LCN and timeslot. Frequencies marked LCN Map were resolved from the configured map; OTA Freq means the system broadcast an absolute frequency.') :
    fragment();
  const p25 = isP25(site);
  const block = section('Channels', fragment(explanation, table(data.rows || [],
    p25 ? p25SiteChannelColumns() : trunkedSiteChannelColumns(), 'No channels recorded',
    { type: p25 ? 'site-channels' : 'trunked-site-channels', sortable: false })),
    exportCsvLink('site-channels', { guid: site.guid }));
  block.append(pager(data));
  content.append(block);
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
    { id: 'neighbor-name', label: 'Name', fullLabel: 'Monitored Site Name',
      render: neighborSiteLink,
      sortValue: (row) => row.neighbor_name || row.neighbor_site_name || row.neighbor_channel_name || '' },
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
  const data = await api('/api/site/neighbors', pageParameters({ guid: site.guid }));
  const p25 = isP25(site);
  const block = section('Neighbors', table(data.rows || [],
    p25 ? p25SiteNeighborColumns() : trunkedSiteNeighborColumns(site), 'No neighbors recorded',
    { type: p25 ? 'site-neighbors' : 'trunked-site-neighbors', sortable: false }),
    exportCsvLink('site-neighbors', { guid: site.guid }));
  block.append(pager(data));
  content.append(block);
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
    ['GUID', site.guid], ['Name', site.channel_name],
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
      { id: 'patch-id', label: 'Patch', fullLabel: 'Patch Talkgroup ID',
        render: (row) => talkgroupLink(site, row.patch_group, undefined, 3),
        className: 'numeric', sortValue: (row) => Number(row.patch_group) },
      { id: 'patch-name', label: 'Alias', fullLabel: 'Patch Alias', render: (row) => row.patch_alias_name ?
        talkgroupLink(site, row.patch_group, row.patch_alias_name, 3) : '',
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
    await renderSiteInfo(site);
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
  if (protocolFamily(row) === 'NXDN' && Number(row.identity_domain_code) !== 2) {
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
  const kind = TALKGROUP_TARGET_KINDS.has(Number(row.target_kind_code)) ? 'talkgroup' :
    Number(row.target_kind_code) === 2 ? 'radio' : '';
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
  const kind = TALKGROUP_TARGET_KINDS.has(Number(row.target_kind_code)) ? 'talkgroup' :
    Number(row.target_kind_code) === 2 ? 'radio' : '';
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
  content.append(pagedSection('Conventional Channels', page, columns, 'Search name or frequency', 'conventional',
    exportCsvLink('conventional-channels')));
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
    'Search talkgroup ID or alias', 'conventional-talkgroups',
    exportCsvLink('conventional-talkgroups', { context: contextKey })));
}

async function renderConventionalRadios(contextKey) {
  const page = await api('/api/conventional/radios', pageParameters({
    context: contextKey,
    limit: CONVENTIONAL_IDENTITY_PAGE_LIMIT
  }));
  content.append(pagedSection('Radios', page, conventionalRadioColumns(),
    'Search radio ID or alias', 'conventional-radios',
    exportCsvLink('conventional-radios', { context: contextKey })));
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
    tier: normalizeAccessTier(value?.tier),
    passwordChangedAtEpochMillis: Number(value?.passwordChangedAtEpochMillis ||
      value?.passwordChangedAtMs || 0),
    credentialVersion: Number(value?.credentialVersion || 0),
    primaryAdmin: Boolean(value?.primaryAdmin ?? value?.primary)
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
    const requested = normalizeAccessTier(select.value);
    select.disabled = true;
    adminStatusMessage(statusHost, `Updating ${account.username}…`);
    try {
      await requestJson(adminUserEndpoint(account.username), { method: 'PUT', body: { tier: requested } });
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
            tier: normalizeAccessTier(tier.value) }
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
  const maximumUsers = Number(response?.maximumUsers || 0);
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
  const supplied = response?.capabilities ?? response?.policies ?? response;
  if (Array.isArray(supplied)) {
    return supplied.map((entry) => ({
      id: String(entry?.id || entry?.capability || '').trim(),
      displayName: String(entry?.displayName || entry?.name || entry?.id || entry?.capability || '').trim(),
      requiredTier: normalizeAccessTier(entry?.requiredTier ?? entry?.tier),
      defaultTier: normalizeAccessTier(entry?.defaultTier ?? entry?.requiredTier ?? entry?.tier),
      configurable: entry?.configurable !== false
    })).filter((entry) => entry.id);
  }
  if (supplied && typeof supplied === 'object') {
    return Object.entries(supplied).map(([id, value]) => ({
      id,
      displayName: id.split('-').map((word) => word[0]?.toUpperCase() + word.slice(1)).join(' '),
      requiredTier: normalizeAccessTier(value?.requiredTier ?? value?.tier ?? value),
      defaultTier: normalizeAccessTier(value?.defaultTier ?? value?.requiredTier ?? value?.tier ?? value),
      configurable: value?.configurable !== false
    }));
  }
  return [];
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
    const requested = normalizeAccessTier(select.value);
    select.disabled = true;
    adminStatusMessage(statusHost, `Updating ${policy.displayName || policy.id}…`);
    try {
      await requestJson('/api/v1/admin/access', {
        method: 'PUT', body: { capability: policy.id, tier: requested }
      });
      policy.requiredTier = requested;
      adminStatusMessage(statusHost,
        `${policy.displayName || policy.id} now requires ${accessTierLabel(requested)} access.`);
      await refreshAccessSession(false);
      updateNavigationAccess();
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

async function renderAdminAccess() {
  const response = await requestJson('/api/v1/admin/access', { csrf: false });
  const policies = adminAccessPolicies(response).sort((left, right) =>
    (left.displayName || left.id).localeCompare(right.displayName || right.id));
  const statusHost = node('div', 'admin-operation-status');
  statusHost.setAttribute('role', 'status');
  const body = node('div', 'admin-section-body');
  body.append(node('p', 'admin-section-intro',
    'Each capability protects its page and backing APIs together. New capabilities appear here automatically.'),
    statusHost,
    table(policies, [
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
    ], 'No access capabilities were returned', { type: 'admin-access', sortable: false }));
  content.append(section('Access policy', body));
}

async function renderAdmin() {
  const availableTabs = [
    { id: 'users', label: 'Users', capability: ACCESS_CAPABILITIES.ADMIN_USERS },
    { id: 'access', label: 'Access', capability: ACCESS_CAPABILITIES.ADMIN_ACCESS }
  ].filter((item) => capabilityAllowed(item.capability));
  if (!availableTabs.length) throw Object.assign(new Error('Administrator access is unavailable.'), { status: 403 });
  const requested = route.get('tab') || 'users';
  const active = availableTabs.some((item) => item.id === requested) ? requested : availableTabs[0].id;
  if (active !== requested) {
    route.set('tab', active);
    window.history.replaceState({}, '', currentHref());
  }
  content.append(pageHeader('Administration',
    'Manage web users and the Public, User, and Admin access tiers'),
    tabs(availableTabs.map((item) => ({ ...item, href: href('admin', { tab: item.id }) })), active));
  if (active === 'access') await renderAdminAccess();
  else await renderAdminUsers();
}

function routeViewLabel(view) {
  return ({
    dashboard: 'Dashboard', live: 'Live', systems: 'Systems & Sites', system: 'System details',
    site: 'Site details', talkgroup: 'Talkgroup details', radio: 'Radio details',
    conventional: 'Conventional', 'conventional-detail': 'Conventional details', aliases: 'Aliases',
    admin: 'Administration'
  })[view] || 'this page';
}

function renderAccessDenied(view) {
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
      const first = [...document.querySelectorAll('.primary-nav a[data-view]')].find((link) => !link.hidden);
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
  content.append(pageHeader('Access', routeViewLabel(view)), panel);
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
  if (accessSessionAvailable && !capabilityAllowed(ACCESS_CAPABILITIES.DASHBOARD)) {
    serviceStatus = null;
    document.getElementById('server-status').textContent = 'Status restricted';
    return;
  }
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
      !['live', 'admin', 'credits'].includes(currentView)) {
    render();
  }
}

async function render() {
  const view = route.get('view') || 'dashboard';
  closeReadOnlyModal(false);
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
      system: renderSystem,
      talkgroup: renderTalkgroup,
      radio: renderRadio,
      site: renderSite,
      conventional: renderConventional,
      'conventional-detail': renderConventionalDetail,
      aliases: renderAliases,
      admin: renderAdmin,
      credits: renderCredits
    };
    const effectiveView = handlers[view] ? view : 'dashboard';
    if (!viewAllowed(effectiveView)) {
      document.body.dataset.view = 'access-denied';
      renderAccessDenied(effectiveView);
      return;
    }
    await handlers[effectiveView]();
    const notice = databaseLoggingNotice(effectiveView);
    if (notice) {
      const header = content.querySelector('.page-header');
      if (header) header.after(notice);
      else content.prepend(notice);
    }
  } catch (error) {
    if (error?.status === 401 || error?.status === 403) {
      await refreshAccessSession(false);
      content.replaceChildren();
      document.body.dataset.view = 'access-denied';
      renderAccessDenied(view);
      return;
    }
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
initializeAccessControls();
initializePlaybackHeader();
refreshAccessSession(false)
  .then(() => loadStatus(false))
  .finally(render);
window.setInterval(async () => {
  if (!document.hidden) {
    await refreshAccessSession(true);
    await loadStatus(true);
  }
}, 10_000);
