import * as routeFoundation from './core/routes.js';
import * as preferenceSchema from './core/preference-schema.js';
import { Controller as UserPreferenceController } from './core/user-preferences.js';
import * as tableLayouts from './core/table-layout.js';
import { Controller as PageTitleController } from './core/page-title.js';
import { href as entityRefHref } from './core/entity-ref.js';
import * as pageLifecycle from './core/page-lifecycle.js';
import {
  receiverHealthAlertGroups,
  receiverHealthAlertIds,
  isReceiverHealthAlertEnabled
} from './core/receiver-health-alerts.js';
import * as radioSystemsDirectory from './features/radio-systems-directory.js';
import * as rfPlanner from './features/rf-planner.js';
import { WebCallPlayer } from './web-call-player.js';

let route = new URLSearchParams(window.location.search);
const content = document.getElementById('content');
const WEB_CLIENT_REVISION = document.querySelector('meta[name="sdrtrunk-web-revision"]')?.content?.trim() || '';
const ALIAS_CREATE_ROUTE_KEYS = Object.freeze([
  'createAlias', 'createListId', 'createType', 'createProtocol', 'createVariant', 'createValue', 'createName'
]);
const P25_OVERRIDE_CREATE_ROUTE_KEYS = Object.freeze([
  'createP25Override', 'wacn', 'system', 'rfss', 'site', 'configuration_id'
]);
const TABLE_WIDTH_MINIMUM = 48;
const TABLE_WIDTH_MAXIMUM = 1200;
const SIGNAL_OFFLINE_MILLISECONDS = 45_000;
const RECEIVER_HEALTH_STALE_MILLISECONDS = 15_000;
const RECEIVER_HEALTH_RESOLVED_PAGE_SIZE = 5;
const RECEIVER_HEALTH_GC_BAR_MAXIMUM_MILLISECONDS = 1_000;
const DECODE_HEALTHY_MINIMUM_PERCENT = 90;
const DECODE_DEGRADED_MINIMUM_PERCENT = 75;
const VOICE_QUALITY_WARMUP_FRAMES = 50;
const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
const NAVIGATION_DRAWER_MEDIA = '(max-width: 1180px)';
const NAVIGATION_HOVER_MEDIA = '(min-width: 1181px) and (hover: hover)';
const LIVE_DETAIL_DEFAULT_MATCHING_ROW_LIMIT = 200;
const LIVE_DETAIL_CAPTURE_MULTIPLIER = 25;
const LIVE_DETAIL_MINIMUM_CAPTURE = 2000;
const LIVE_DETAIL_MAXIMUM_CAPTURE = 10000;
const LIVE_DETAIL_REFRESH_INTERVAL_MILLISECONDS = 125;
const ACTIVITY_REFRESH_INTERVAL_MILLISECONDS = 10_000;
const RADIO_REFERENCE_DIRECTORY_TIMEOUT_MILLISECONDS = 15_000;
let anonymousUserPreferences = preferenceSchema.validate(JSON.parse(JSON.stringify(preferenceSchema.defaults)));
const ALIAS_LIST_FAMILY_LABELS = Object.freeze({
  P25: 'P25', DMR: 'DMR', NXDN: 'NXDN', NBFM: 'Conventional Analog (AM/NBFM)'
});
const ACCESS_CAPABILITIES = Object.freeze({
  WEB_ACCESS: 'web-access',
  DASHBOARD: 'dashboard',
  LIVE: 'live',
  TUNER_SPECTRUM: 'tuner-spectrum',
  RADIO: 'radio',
  CREDITS: 'credits',
  CSV_EXPORT: 'csv-export',
  CALL_AUDIO: 'call-audio',
  RECEIVER_HEALTH: 'receiver-health',
  ADMIN_ALIASES: 'admin-aliases',
  ADMIN_SETTINGS: 'admin-settings',
  ADMIN_USERS: 'admin-users',
  ADMIN_ACCESS: 'admin-access'
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
  { field: 'logical_call_count', label: 'Logical Calls', color: 'var(--chart-call)', visible: true },
  { field: 'recorded_logical_call_count', label: 'Recorded', color: 'var(--chart-recorded)', visible: true },
  { field: 'stream_submitted_logical_call_count', label: 'Submitted to Streamer', color: 'var(--chart-streamed)', visible: true }
]);
const DASHBOARD_CALL_METRICS = Object.freeze([
  { field: 'logical_call_count', label: 'Logical Calls' },
  { field: 'recorded_logical_call_count', label: 'Recorded' },
  { field: 'stream_submitted_logical_call_count', label: 'Submitted' }
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
const GROUP_IDENTITY_CALL_ACTIVITY_SERIES = Object.freeze([
  ...CALL_ACTIVITY_SERIES,
  { field: 'encrypted_logical_call_count', label: 'Encrypted', color: 'var(--chart-encrypted)', visible: true }
]);
const GROUP_IDENTITY_SIGNALING_SERIES = Object.freeze([
  { field: 'emergency_observation_count', label: 'Emergency', color: 'var(--chart-emergency)' },
  { field: 'data_observation_count', label: 'Data', color: 'var(--chart-data)' },
  { field: 'join_observation_count', label: 'Join', color: 'var(--chart-join)' },
  { field: 'register_observation_count', label: 'Register', color: 'var(--chart-register)' },
  { field: 'denial_observation_count', label: 'Denial', color: 'var(--chart-denial)' },
  { field: 'busy_observation_count', label: 'Busy', color: 'var(--chart-busy)' },
  { field: 'queued_observation_count', label: 'Queued', color: 'var(--chart-queued)' },
  { field: 'continue_observation_count', label: 'Continue', color: 'var(--chart-continue)' },
  { field: 'active_observation_count', label: 'Active', color: 'var(--chart-active)' },
  { field: 'acknowledge_observation_count', label: 'Acknowledge', color: 'var(--chart-acknowledge)' },
  { field: 'check_observation_count', label: 'Check', color: 'var(--chart-check)' },
  { field: 'check_ack_observation_count', label: 'Check Ack', color: 'var(--chart-check-ack)' },
  { field: 'gps_observation_count', label: 'GPS', color: 'var(--chart-gps)' },
  { field: 'logout_observation_count', label: 'Logout', color: 'var(--chart-logout)' },
  { field: 'page_observation_count', label: 'Page', color: 'var(--chart-page)' },
  { field: 'patch_observation_count', label: 'Patch', color: 'var(--chart-patch)' },
  { field: 'patch_cancel_observation_count', label: 'Patch Cancel', color: 'var(--chart-patch-cancel)' },
  { field: 'patch_create_observation_count', label: 'Patch Create', color: 'var(--chart-patch-create)' },
  { field: 'request_observation_count', label: 'Request', color: 'var(--chart-request)' },
  { field: 'status_observation_count', label: 'Status', color: 'var(--chart-status)' },
  { field: 'unknown_observation_count', label: 'Unknown', color: 'var(--chart-unknown)' }
]);
const DASHBOARD_ACTIVITY_SERIES = Object.freeze([
  { action: 'GRANT', label: 'Grant', color: 'var(--chart-grant)' },
  ...GROUP_IDENTITY_SIGNALING_SERIES.filter((series) => series.field !== 'continue_observation_count')
    .map((series) => ({
      action: series.field.replace(/_observation_count$/, '').toUpperCase(),
      label: series.label,
      color: series.color
    }))
]);
const DASHBOARD_ACTIVITY_RANGES = Object.freeze([
  ['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days']
]);
const CALL_METRIC_GUIDE = Object.freeze([
  ['Logical Calls', 'One transmission after matching copies heard by multiple monitored sites have been combined.'],
  ['Recorded', 'Logical calls whose selected best copy was written to a nonempty recording file.'],
  ['Submitted to Streamer', 'Logical calls whose selected best copy was encoded and handed to at least one configured stream. This does not mean the remote service accepted the upload.'],
  ['Encrypted', 'Logical calls for which encrypted voice was confirmed.']
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
  'confirmed-channel': 220,
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
  'group-identity-description': 240,
  'group-identity-id': 90,
  'group-identity-name': 175,
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
  'source-ota-alias': 165,
  'state': 82,
  'status': 116,
  'streamed': 82,
  'system': 106,
  'talker-alias': 160,
  'talkgroup-name': 175,
  'target': 82,
  'target-alias': 165,
  'time': 166,
  'wacn': 108
};
const TABLE_DEFAULT_COLUMN_WIDTHS = Object.freeze({
  'dashboard-receivers': Object.freeze({
    name: 442,
    mode: 180,
    context: 315,
    frequency: 237,
    'last-seen': 419
  }),
  'live-events': Object.freeze({
    time: 92,
    duration: 78,
    event: 181,
    from: 96,
    to: 164,
    channel: 92,
    details: 864
  }),
  'live-messages': Object.freeze({
    time: 95,
    context: 110,
    message: 1200
  })
});
const SERVER_TABLE_DEFAULT_SORTS = {
  'radio-system-channels': 'last_seen',
  'group-identities': 'logical_call_count',
  radios: 'logical_call_count',
  'talker-aliases': 'talker_alias',
  'group-identity-radios': 'last_seen',
  'radio-groups': 'last_seen',
  channels: 'name',
  'channel-group-identities': 'logical_call_count',
  'channel-radios': 'logical_call_count'
};
const CHANNEL_IDENTITY_PAGE_LIMIT = 100;
const SERVICE_STATUS_FAILURE_WARNING_THRESHOLD = 3;
const SERVICE_STATUS_INITIAL_ATTEMPTS = 3;
const SERVICE_STATUS_RETRY_DELAY_MS = 500;
const ALIAS_CATALOG_DEFAULT_COLUMNS = Object.freeze([
  'alias', 'description', 'identifier', 'matcher', 'group', 'calls', 'signaling', 'last-seen'
]);
const ALIAS_BULK_SELECTION_LIMIT = 10_000;
const ALIAS_BULK_REQUEST_TIMEOUT_MS = 60_000;
let serviceStatus = null;
let serviceStatusRequestPending = false;
let serviceStatusConsecutiveFailures = 0;
let webClientReloadAttempted = false;
let activeReadOnlyModal = null;
let aliasEditorSelection = new Set();
let aliasEditorSelectionScope = null;
let aliasEditorSelectionRequest = 0;
let aliasEditorLastSelectionIndex = null;
let aliasEditorContext = null;
let accessSession = anonymousAccessSession();
let accessSessionAvailable = false;
let applicationRoutes = null;
let userPreferenceError = null;
const tableLayoutResetPending = new Set();
const tableSchemaRegistry = new Map();
let tableLayoutPanelSequence = 0;
const pageTitleController = new PageTitleController(document);
const userPreferenceController = new UserPreferenceController({
  defaults: preferenceSchema.defaults,
  validate: preferenceSchema.validate,
  fetch: jsonDocumentFetch,
  onChange: (snapshot) => applyUserPreferenceSnapshot(snapshot),
  onError: (error) => showUserPreferenceError(error)
});
let notifyConfirmedAccessRefresh = () => {};
let playbackScanListRequest = 0;
let playbackScanListLoading = false;
let webCallPlayer = null;

function node(tag, className, textValue) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (textValue !== undefined && textValue !== null) element.textContent = String(textValue);
  return element;
}

function clearUserPreferenceError() {
  userPreferenceError = null;
  const status = document.getElementById('preference-status');
  if (!status) return;
  status.hidden = true;
  status.className = 'preference-status';
  status.setAttribute('role', 'status');
  status.replaceChildren();
}

function showUserPreferenceError(error, retry = null, saveFailed = false) {
  userPreferenceError = error instanceof Error ? error : new Error('My Settings are unavailable.');
  const status = document.getElementById('preference-status');
  if (!status) return;
  const retryable = typeof retry === 'function';
  const message = error?.code === 'preference_conflict' ?
    (error.reloadError ? 'My Settings changed in another session, but the current values could not be reloaded.' :
      'My Settings changed in another session. Current values were reloaded.') :
    (saveFailed ? 'My Settings could not be saved. Your previous values are still active.' :
      'My Settings are unavailable.');
  status.hidden = false;
  status.className = 'preference-status preference-error';
  status.setAttribute('role', 'alert');
  const actions = node('span', 'preference-status-actions');
  if (retryable) {
    const retryButton = node('button', 'button secondary', 'Retry');
    retryButton.type = 'button';
    retryButton.addEventListener('click', () => {
      retryButton.disabled = true;
      void Promise.resolve().then(retry).catch((retryError) =>
        showUserPreferenceError(retryError, retry)).finally(() => {
          if (retryButton.isConnected) retryButton.disabled = false;
        });
    });
    actions.append(retryButton);
  }
  const dismiss = node('button', 'button secondary', 'Dismiss');
  dismiss.type = 'button';
  dismiss.addEventListener('click', clearUserPreferenceError);
  actions.append(dismiss);
  status.replaceChildren(node('span', 'preference-status-message', message), actions);
}

async function jsonDocumentFetch(path, options = {}) {
  const method = String(options.method || 'GET').toUpperCase();
  const headers = new Headers(options.headers || {});
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && accessSession.csrfToken) {
    headers.set('X-CSRF-Token', accessSession.csrfToken);
  }
  const response = await fetch(path, {
    ...options,
    method,
    headers,
    cache: 'no-store',
    credentials: 'same-origin'
  });
  let consumed = false;
  return {
    ok: response.ok,
    status: response.status,
    async json() {
      if (consumed) throw new Error('The preference response was already read.');
      consumed = true;
      return response.json();
    }
  };
}

function activeUserPreferences() {
  const snapshot = userPreferenceController.snapshot();
  return snapshot.loaded ? snapshot.preferences : anonymousUserPreferences;
}

function applyUserPreferenceSnapshot(snapshot) {
  if (snapshot.loaded) clearUserPreferenceError();
  const preferences = snapshot.loaded ? snapshot.preferences : anonymousUserPreferences;
  applyTheme();
  pageTitleController.update({ prependPlaying: preferences.page_titles.prepend_playing_call });
  const player = webCallPlayer;
  if (player && typeof player.applyPreferences === 'function') player.applyPreferences(preferences.playback);
  if (typeof scannerDetailMode !== 'undefined') scannerDetailMode = preferences.scanner.detail_mode;
  receiverHealthController.updateIndicator();
  receiverHealthController.updatePage();
}

async function synchronizeUserPreferences() {
  const identity = accessSession.authenticated ? accessSession.username : null;
  const snapshot = userPreferenceController.snapshot();
  if (snapshot.identity === identity && (identity === null || snapshot.loaded)) return snapshot;
  userPreferenceError = null;
  try {
    return await userPreferenceController.activate(identity);
  } catch (error) {
    return userPreferenceController.snapshot();
  }
}

async function updateUserPreferences(mutator, allowRetry = true) {
  try {
    const result = await userPreferenceController.update(mutator);
    if (result?.state === 'stale') {
      const error = new Error('The signed-in user changed before these settings were saved.');
      error.code = 'preference_session_changed';
      throw error;
    }
    clearUserPreferenceError();
    return result?.preferences || userPreferenceController.snapshot().preferences;
  } catch (error) {
    if (error?.code === 'preference_session_changed') throw error;
    const retry = allowRetry && error?.code !== 'preference_conflict' ?
      () => settleUserPreferenceMutation(mutator) : null;
    showUserPreferenceError(error, retry, true);
    throw error;
  }
}

function settleUserPreferenceMutation(mutator, allowRetry = true) {
  return updateUserPreferences(mutator, allowRetry).catch(() => null);
}

async function saveTableLayoutPreference(tableType, layout) {
  if (!userPreferenceController.snapshot().loaded) return null;
  return settleUserPreferenceMutation((preferences) => {
    preferences.tables[tableType] = tableLayouts.persisted(layout);
  }, false);
}

function removeResetTableLayout(tableType) {
  if (!userPreferenceController.snapshot().loaded || tableLayoutResetPending.has(tableType)) return;
  tableLayoutResetPending.add(tableType);
  void settleUserPreferenceMutation((preferences) => { delete preferences.tables[tableType]; }, false)
    .finally(() => tableLayoutResetPending.delete(tableType));
}

function svgNode(tag, attributes = {}, textValue) {
  const element = document.createElementNS(SVG_NAMESPACE, tag);
  Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, String(value)));
  if (textValue !== undefined) element.textContent = String(textValue);
  return element;
}

function iconGlyph(id) {
  const icon = svgNode('svg', { 'aria-hidden': 'true' });
  icon.append(svgNode('use', { href: `#${id}` }));
  return icon;
}

function setIconButton(button, iconId, label) {
  button.replaceChildren(iconGlyph(iconId));
  button.setAttribute('aria-label', label);
  button.title = label;
  return button;
}

function iconButton(iconId, label, className = 'button secondary icon-button') {
  const button = node('button', className);
  button.type = 'button';
  return setIconButton(button, iconId, label);
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

function storedTheme() {
  return activeUserPreferences().appearance.theme;
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
  if (userPreferenceController.snapshot().loaded) {
    void settleUserPreferenceMutation((preferences) => { preferences.appearance.theme = selected; });
  } else anonymousUserPreferences = preferenceSchema.validate({
    ...anonymousUserPreferences, appearance: { theme: selected }
  });
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
  return applicationRoutes?.[view]?.capability || null;
}

function routeDefinitionAllowed(definition) {
  if (definition.access === 'admin-configuration') {
    return accessSession.tier === 'ADMIN' && capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ALIASES);
  }
  if (definition.access === 'admin-tuner') {
    return accessSession.tier === 'ADMIN' && capabilityAllowed(ACCESS_CAPABILITIES.TUNER_SPECTRUM);
  }
  if (definition.access === 'admin-aliases') {
    return accessSession.tier === 'ADMIN' && capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ALIASES);
  }
  if (definition.access === 'admin') {
    return accessSession.tier === 'ADMIN' &&
      (capabilityAllowed(ACCESS_CAPABILITIES.RECEIVER_HEALTH) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_SETTINGS) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ALIASES) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_USERS) ||
        capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_ACCESS));
  }
  const capability = definition.capability;
  return !capability || capabilityAllowed(capability);
}

function viewAllowed(view) {
  return applicationRoutes?.[view]?.allowed() === true;
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
  const status = document.getElementById('auth-status-label');
  const settings = document.getElementById('auth-session-label');
  const action = document.getElementById('auth-action');
  if (!status || !settings || !action) return;
  status.hidden = false;
  settings.hidden = true;
  if (!accessSessionAvailable) {
    status.textContent = 'Access unavailable';
    status.title = 'The receiver did not return its current access policy.';
    action.textContent = 'Retry sign in';
    action.disabled = false;
  } else if (!accessSession.configured) {
    status.textContent = 'Primary admin not set';
    status.title = 'Set the primary administrator password from the local JavaFX Web Server settings.';
    action.textContent = 'Sign In';
    action.disabled = false;
  } else if (accessSession.authenticated) {
    status.hidden = true;
    settings.hidden = false;
    settings.textContent = accessSession.username;
    settings.title = `Open My Settings · ${accessTierLabel(accessSession.tier)} access`;
    settings.setAttribute('aria-label', `Open My Settings for ${accessSession.username}`);
    action.textContent = 'Sign Out';
    action.disabled = false;
  } else {
    const signInRequired = accessSession.capabilities?.[ACCESS_CAPABILITIES.WEB_ACCESS] === false;
    status.textContent = signInRequired ? 'Sign in required' : 'Public';
    status.title = signInRequired ? 'This receiver requires an account for the web interface' : 'Using public access';
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
      await synchronizeUserPreferences();
      updateAccessControls();
      synchronizePlaybackAccess();
      liveMultiplexer.ensureConnected();
      if (!accessSession.authenticated) throw new Error('The receiver did not create a session.');
      modal.close();
      void receiverHealthController.refresh();
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
    await synchronizeUserPreferences();
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
  await render();
}

function initializeAccessControls() {
  const action = document.getElementById('auth-action');
  const settings = document.getElementById('auth-session-label');
  if (!action) return;
  action.addEventListener('click', () => {
    if (accessSession.authenticated) signOut();
    else showLoginModal();
  });
  settings?.addEventListener('click', () => navigateTo(href('settings')));
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
  await synchronizeUserPreferences();
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

function radioSystemLabel(row) {
  if (isP25(row)) {
    const wacn = hex(row.wacn, 5);
    const system = hex(row.system_id, 3);
    return wacn && system ? `${wacn}-${system}` : wacn || system;
  }
  if (isSavedChannelRadioSystem(row)) return savedChannelScopeLabel(row);
  if (protocolFamily(row) === 'DMR') {
    const model = semanticLabel(row.model);
    const network = identifierNumber(row.network_id);
    return ['DMR Tier III', model ? `${model} model` : '', network ? `Network ${network}` : '']
      .filter(Boolean).join(' · ');
  }
  if (protocolFamily(row) === 'NXDN') {
    const category = semanticLabel(row.location_category);
    const system = identifierNumber(row.system_id);
    return ['NXDN Type-C', category, system ? `System ${system}` : ''].filter(Boolean).join(' · ');
  }
  return `${protocolFamily(row) || 'Unknown'} radio system`;
}

function savedChannelScopeLabel(row) {
  const family = protocolFamily(row);
  return `${family ? `${family} ` : ''}saved channel scope`;
}

function isSavedChannelRadioSystem(row) {
  return /^(?:dmr|nxdn-c|nxdn-d):channel:/.test(String(row?.radio_system_key || ''));
}

function radioSystemOwnerLabel(row) {
  return isSavedChannelRadioSystem(row) ? 'Saved channel scope' : 'Radio System';
}

function radioSystemValue(row) {
  return radioSystemLabel(row);
}

function radioSystemAliasLists(row) {
  const assigned = Array.isArray(row?.alias_lists) ? row.alias_lists : [];
  if (!assigned.length) return '—';
  const values = node('span', 'alias-list-values');
  assigned.forEach((item, index) => {
    if (index) values.append(', ');
    values.append(aliasListLink(item?.name, item?.id));
  });
  return values;
}

function radioSystemInfoValue(row) {
  if (!isP25(row)) return radioSystemValue(row);
  const hexadecimal = radioSystemLabel(row);
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
  const identity = `${hex(row.rfss, 2)}-${hex(row.site_id, 2)}`;
  return row.name || `${radioSystemLabel(row)} ${identity}`;
}

function normalizedSiteText(value) {
  return String(value || '').trim();
}

function sameSiteText(left, right) {
  return Boolean(left && right) && left.localeCompare(right, undefined, { sensitivity: 'base' }) === 0;
}

function siteNameValue(row) {
  return normalizedSiteText(row?.site_name);
}

function nameValue(row) {
  return normalizedSiteText(row?.name);
}

function channelDisplayParts(row) {
  const site = siteNameValue(row);
  const name = nameValue(row);
  const primary = name || site || normalizedSiteText(row?.source_label) || observedSiteLabel(row);
  return { primary, secondary: site && !sameSiteText(site, primary) ? site : '' };
}

function neighborSiteDisplayParts(row) {
  const site = normalizedSiteText(row?.neighbor_site_name);
  const name = normalizedSiteText(row?.neighbor_name);
  const primary = name || site;
  return { primary, secondary: site && !sameSiteText(site, primary) ? site : '' };
}

function neighborSiteId(row) {
  return row?.site_id;
}

function identitySummaryValue(primary, secondary, target = '') {
  if (!primary) return '';
  const summary = node('span', 'identity-summary');
  summary.title = [primary, secondary].filter(Boolean).join(' · ');
  const heading = node('span', 'identity-summary-primary');
  heading.append(target ? anchor(primary, target) : valueNode(primary));
  summary.append(heading);
  if (secondary) summary.append(node('small', 'identity-summary-context', secondary));
  return summary;
}

function channelNameSummary(row, linked = true) {
  const labels = channelDisplayParts(row);
  const target = linked && capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ? entityRefHref(row?.entity_ref) : '';
  return identitySummaryValue(labels.primary, labels.secondary, target);
}

function authoritativePresence(row) {
  const presence = row?.presence;
  const evidence = String(presence?.evidence || '').trim().toLowerCase();
  const confirmedAt = Number(presence?.confirmed_at_ms);
  const channel = presence?.channel;
  if (!['registration', 'affiliation'].includes(evidence) ||
      !Number.isFinite(confirmedAt) || confirmedAt <= 0 || !channel || typeof channel !== 'object' ||
      Array.isArray(channel) || !normalizedSiteText(channel.protocol) ||
      (!identifierNumber(channel.site_id) && !normalizedSiteText(channel.configuration_id))) return null;
  return { evidence, confirmed_at_ms: confirmedAt, channel };
}

function presenceChannelIdentity(channel) {
  if (!channel) return '';
  if (isP25(channel)) {
    const values = [];
    const rfss = hex(channel.rfss, 2);
    const siteId = hex(channel.site_id, 2);
    if (rfss) values.push(`RFSS ${rfss}`);
    if (siteId) values.push(`Site ${siteId}`);
    return values.join(' · ') || normalizedSiteText(channel.configuration_id);
  }
  const siteId = identifierNumber(channel.site_id);
  return siteId ? `Site ${siteId}` : normalizedSiteText(channel.configuration_id);
}

function presenceChannelContext(channel) {
  return normalizedSiteText(channel?.site_name) || normalizedSiteText(channel?.name);
}

function presenceChannelSortValue(row) {
  const presence = authoritativePresence(row);
  if (!presence) return '';
  return `${presenceChannelIdentity(presence.channel)}\u0000${presenceChannelContext(presence.channel)}`;
}

function channelPresenceCell(row, showConfirmation = true) {
  const presence = authoritativePresence(row);
  if (!presence) return '—';
  const identity = presenceChannelIdentity(presence.channel);
  const configured = presenceChannelContext(presence.channel);
  const summary = node('span', 'identity-summary');
  const primary = node('span', 'identity-summary-primary');
  primary.append(channelLink(presence.channel, identity));
  summary.append(primary);
  if (configured || showConfirmation) {
    const context = node('small', 'identity-summary-context');
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

function channelLabel(row) {
  return channelDisplayParts(row).primary;
}

function channelValue(row) {
  return channelLabel(row);
}

function protocolFamily(row) {
  return protocol(row?.protocol);
}

function isAnalogChannel(row) {
  const family = String(protocolFamily(row) || '').toUpperCase();
  const decoder = String(row?.decoder || '').trim().toUpperCase();
  return ['AM', 'NBFM'].includes(family) || ['AM', 'NBFM'].includes(decoder);
}

function isP25(row) {
  return protocolFamily(row) === 'P25';
}

function identifierNumber(value) {
  const numeric = Number(value);
  return value === null || value === undefined || value === '' || !Number.isFinite(numeric) || numeric < 0 ?
    '' : String(Math.trunc(numeric));
}

function timeslotLabel(value) {
  const timeslot = identifierNumber(value);
  return timeslot ? `Slot ${timeslot}` : '';
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

function trunkedSiteLabel(row) {
  const family = protocolFamily(row);
  const site = identifierNumber(row.site_id) ||
    (family === 'NXDN' ? identifierNumber(row.ran) : '');
  return row.name || row.system_name ||
    `${family} site ${site || row.configuration_id}`;
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

function radioSystemRoute(row) {
  return { radio_system_key: row?.radio_system_key || '' };
}

function radioSystemApiPath(radioSystemKey, child = '') {
  const base = `/api/v1/radio-systems/${encodeURIComponent(String(radioSystemKey || ''))}`;
  return child ? `${base}/${child}` : base;
}

function groupIdentityApiPath(radioSystemKey, identityKey, child = '') {
  const base = `${radioSystemApiPath(radioSystemKey, 'group-identities')}/${encodeURIComponent(String(identityKey))}`;
  return child ? `${base}/${child}` : base;
}

function canonicalConfigurationId(value) {
  const configurationId = String(value || '').trim();
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(configurationId) ?
    configurationId : '';
}

function channelApiPath(configurationId, child = '') {
  const base = `/api/v1/channels/${encodeURIComponent(String(configurationId || ''))}`;
  return child ? `${base}/${child}` : base;
}

function href(view, values = {}) {
  if (applicationRoutes && !applicationRoutes[view]) throw new Error(`Unknown route: ${view}`);
  const parameters = new URLSearchParams();
  parameters.set('view', view);
  Object.entries(values).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') parameters.set(key, String(value));
  });
  return `/?${parameters.toString()}`;
}

function navigateTo(target, options = {}) {
  if (!closeReadOnlyModal()) return false;
  return routeFoundation.navigate(window, target, (nextRoute) => {
    route = nextRoute;
    void render();
  }, options);
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

function exportCsvLink(dataset, context = {}, label = 'Export CSV') {
  if (!capabilityAllowed(ACCESS_CAPABILITIES.CSV_EXPORT)) {
    const disabled = node('span', 'button secondary disabled export-csv-action', label);
    disabled.setAttribute('aria-disabled', 'true');
    disabled.title = accessSession.authenticated ? 'CSV export is not available to this account.' :
      'Sign in to use CSV export.';
    return disabled;
  }
  const link = anchor(label, exportCsvHref(dataset, context), 'button secondary export-csv-action');
  link.setAttribute('download', '');
  link.setAttribute('aria-label', `Export ${dataset.replace(/-/g, ' ')} as CSV`);
  return link;
}

function aliasListLink(name, id) {
  const configuredLabel = String(name || '').trim();
  const aliasListId = Number(id);
  const validId = Number.isInteger(aliasListId) && aliasListId > 0;
  if (!configuredLabel) return '';
  if (!validId || !aliasAdminAllowed()) return configuredLabel;
  return anchor(configuredLabel, href('aliases', { list: aliasListId }));
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

function radioSystemLink(reference, label) {
  const target = capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ? entityRefHref(reference) : '';
  return target ? anchor(label, target) : label;
}

function channelLink(row, label = channelValue(row)) {
  const target = capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ? entityRefHref(row?.entity_ref) : '';
  return target ? anchor(label, target) : label;
}

function neighborSiteLink(row) {
  const labels = neighborSiteDisplayParts(row);
  const target = labels.primary && capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ?
    entityRefHref(row?.entity_ref) : '';
  return identitySummaryValue(labels.primary, labels.secondary, target);
}

function identityKind(value) {
  const kind = String(value || '').trim().toLowerCase();
  return ['talkgroup', 'radio', 'patch_group', 'unknown'].includes(kind) ? kind : '';
}

function rowGroupIdentityKind(row, explicitKind) {
  return identityKind(explicitKind ?? row?.group_identity_kind ?? row?.identity_kind ?? row?.target_kind) ||
    'unknown';
}

function groupIdentityLabel(row, explicitKind, compact = true) {
  const kind = rowGroupIdentityKind(row, explicitKind);
  if (kind === 'patch_group') return compact ? 'Patch' : 'Patch Group';
  if (kind === 'radio') return 'Radio';
  if (kind === 'unknown') return compact ? 'ID' : 'Identity';
  return compact ? 'TG' : 'Talkgroup';
}

function groupIdentityDisplayId(row, id) {
  return id ?? row?.observed_local_id ?? row?.native_id ?? row?.group_native_id ??
    row?.group_identity_id;
}

function radioDisplayId(row, id) {
  return id ?? row?.observed_local_id ?? row?.native_id ?? row?.radio_native_id ?? row?.radio_id;
}

function groupIdentityLink(row, id, label, reference = row?.entity_ref) {
  id = groupIdentityDisplayId(row, id);
  const text = label || identityNumber(row, id);
  const target = capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ?
    entityTarget(reference, { channel: 'groups' }) : '';
  return target ? anchor(text, target) : text;
}

function radioLink(row, id, label, reference = row?.entity_ref) {
  id = radioDisplayId(row, id);
  const text = label || identityNumber(row, id);
  const target = capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ?
    entityTarget(reference, { channel: 'radios' }) : '';
  return target ? anchor(text, target) : text;
}

function groupIdentityAliasLink(row, id, prefix = 'alias_', reference = row?.entity_ref) {
  id = groupIdentityDisplayId(row, id);
  if (id === null || id === undefined) return '';
  const name = row[`${prefix}name`];
  return name ? groupIdentityLink(row, id, name, reference) : '';
}

function affiliationTalkgroupCell(row) {
  const id = row?.affiliated_talkgroup_id;
  if (id === null || id === undefined) return '—';
  const alias = String(row.affiliated_talkgroup_alias_name || '').trim();
  const identifier = `TG ${identityNumber(row, id)}`;
  const summary = node('span', 'identity-summary');
  const primary = node('span', 'identity-summary-primary');
  primary.append(groupIdentityLink(row, id, alias || identifier, row.affiliated_talkgroup_entity_ref));
  summary.append(primary);
  if (alias) summary.append(node('small', 'identity-summary-context', identifier));
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

function clearAliasEditorRoute() {
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

function closeReadOnlyModal(force = false) {
  const active = activeReadOnlyModal;
  if (!active) return true;
  if (!force && active.isBusy?.()) return false;
  if (!force && active.isDirty?.() && !window.confirm('Discard your unsaved changes?')) return false;
  activeReadOnlyModal = null;
  document.removeEventListener('keydown', active.keydown);
  active.cleanup?.();
  active.onClose?.();
  active.backdrop.remove();
  document.body.classList.remove('modal-open');
  const returnFocus = active.returnFocusSelector ? document.querySelector(active.returnFocusSelector) : null;
  if (returnFocus instanceof HTMLElement) returnFocus.focus();
  return true;
}

function openReadOnlyModal(title, body, options = {}) {
  if (!closeReadOnlyModal()) return null;
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

  let modalState = null;
  const dismiss = () => activeReadOnlyModal === modalState && closeReadOnlyModal();
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
  let busy = false;
  modalState = {
    backdrop, keydown, returnFocusSelector: options.returnFocusSelector || null,
    isDirty: () => dirty,
    isBusy: () => busy,
    cleanup: options.cleanup || null,
    onClose: options.onClose || null
  };
  activeReadOnlyModal = modalState;
  document.addEventListener('keydown', keydown);
  document.body.classList.add('modal-open');
  document.body.append(backdrop);
  close.focus();
  return {
    dialog, content: contentNode, close: dismiss, state: modalState,
    setDirty: (value = true) => { dirty = Boolean(value); },
    setBusy: (value = true) => {
      busy = Boolean(value);
      close.disabled = busy;
      if (busy) dialog.setAttribute('aria-busy', 'true');
      else dialog.removeAttribute('aria-busy');
    },
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
  if (applicationRoutes?.[view]?.databaseNotice !== true) return null;
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

function sectionActionHost(action = null) {
  if (action?.classList?.contains('section-title-actions')) return action;
  const actions = node('div', 'section-title-actions');
  if (action) actions.append(action);
  return actions;
}

function valueNode(value) {
  return value instanceof Node ? value : document.createTextNode(value === null || value === undefined ? '' : String(value));
}

function tableColumnKey(column, index) {
  return tableLayouts.columnId(column);
}

function tableSortValue(row, column) {
  if (column.sortValue) return column.sortValue(row);
  if (column.key) return row[column.key];
  const rendered = column.render ? column.render(row) : '';
  if (rendered instanceof HTMLInputElement && rendered.type === 'checkbox') return rendered.checked;
  return rendered instanceof Node ? rendered.textContent : rendered;
}

function anchoredDropdownPlacement(anchorRect, panelRect, viewport) {
  const gutter = 8;
  const gap = 6;
  const viewportWidth = Math.max(gutter * 2, Number(viewport?.width) || 0);
  const viewportHeight = Math.max(gutter * 2, Number(viewport?.height) || 0);
  const panelWidth = Math.min(Math.max(0, Number(panelRect?.width) || 0), viewportWidth - gutter * 2);
  const panelHeight = Math.min(480, Math.max(0, Number(panelRect?.height) || 0));
  const rightAligned = (Number(anchorRect?.right) || 0) - panelWidth;
  const maximumLeft = viewportWidth - panelWidth - gutter;
  const left = Math.max(gutter, Math.min(rightAligned, maximumLeft));
  const belowTop = Math.max(gutter, (Number(anchorRect?.bottom) || 0) + gap);
  const availableBelow = Math.max(0, Math.floor(viewportHeight - belowTop - gutter));
  const availableAbove = Math.max(0,
    Math.floor((Number(anchorRect?.top) || 0) - gap - gutter));
  const minimumUsefulHeight = Math.min(96, panelHeight || 96);
  if (availableBelow < minimumUsefulHeight && availableAbove > availableBelow) {
    const maxHeight = Math.min(480, availableAbove);
    return {
      left: Math.round(left),
      top: Math.round(Math.max(gutter,
        (Number(anchorRect?.top) || 0) - gap - Math.min(panelHeight || maxHeight, maxHeight))),
      maxHeight
    };
  }
  return {
    left: Math.round(left),
    top: Math.round(belowTop),
    maxHeight: Math.min(480, availableBelow)
  };
}

function bindAnchoredDropdown(trigger, panel, signal = null) {
  let viewportListeners = null;
  let cleaned = false;
  const stopTracking = () => {
    viewportListeners?.abort();
    viewportListeners = null;
  };
  const position = () => {
    const anchorRect = trigger.getBoundingClientRect();
    if (anchorRect.bottom <= 0 || anchorRect.top >= window.innerHeight ||
        anchorRect.right <= 0 || anchorRect.left >= window.innerWidth) {
      if (panel.matches(':popover-open')) panel.hidePopover();
      return;
    }
    panel.style.maxHeight = '';
    const placement = anchoredDropdownPlacement(anchorRect, panel.getBoundingClientRect(), {
      width: window.innerWidth,
      height: window.innerHeight
    });
    panel.style.left = `${placement.left}px`;
    panel.style.top = `${placement.top}px`;
    panel.style.maxHeight = `${placement.maxHeight}px`;
  };
  const toggle = (event) => {
    const open = event.newState === 'open';
    trigger.setAttribute('aria-expanded', String(open));
    stopTracking();
    if (!open) return;
    position();
    viewportListeners = new AbortController();
    const options = { signal: viewportListeners.signal };
    window.addEventListener('resize', position, options);
    window.addEventListener('scroll', position, { ...options, capture: true, passive: true });
  };
  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    stopTracking();
    panel.removeEventListener('toggle', toggle);
    signal?.removeEventListener('abort', cleanup);
    trigger.setAttribute('aria-expanded', 'false');
    if (panel.matches(':popover-open')) panel.hidePopover();
  };
  panel.addEventListener('toggle', toggle);
  signal?.addEventListener('abort', cleanup, { once: true });
  return cleanup;
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

function renderTableRow(data, columns, rowKey, rowClass, onRowClick) {
  const row = node('tr');
  row.tableRowData = data;
  const classes = typeof rowClass === 'function' ? rowClass(data) : rowClass;
  if (classes) row.classList.add(...String(classes).split(/\s+/).filter(Boolean));
  if (rowKey) {
    const value = rowKey(data);
    if (value !== null && value !== undefined) row.dataset.id = String(value);
  }
  columns.forEach((column) => {
    const className = typeof column.className === 'function' ? column.className(data) : column.className;
    const cell = node('td', className || '');
    const title = typeof column.title === 'function' ? column.title(data) : column.title;
    if (title) cell.title = String(title);
    const value = column.render ? column.render(data) : data[column.key];
    cell.append(valueNode(value));
    if (typeof column.reconcileKey === 'function') {
      cell.tableReconcileKey = column.reconcileKey(data);
    }
    row.append(cell);
  });
  if (typeof onRowClick === 'function') {
    row.addEventListener('click', (event) => {
      if (!event.target.closest('a, button, input, select, textarea, label')) {
        onRowClick(row.tableRowData, row, event);
      }
    });
  }
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

function applyPreferredTableWidths(element, columns, columnElements, layout) {
  const widths = columns.map((column, index) => {
    const savedWidth = layout.column_widths[tableColumnKey(column, index)];
    const width = Number(savedWidth || defaultTableColumnWidth(column));
    return Number.isFinite(width) && width >= TABLE_WIDTH_MINIMUM && width <= TABLE_WIDTH_MAXIMUM ?
      Math.round(width) : defaultTableColumnWidth(column);
  });
  setTableColumnWidths(element, columnElements, widths);
}

function addColumnResizers(element, columns, columnElements, headers, tableType,
    currentLayout, setCurrentLayout, beginLayoutMutation, endLayoutMutation, onSaveFailure) {
  const saveWidths = async (widths) => {
    let nextLayout = currentLayout();
    columns.forEach((column, index) => {
      nextLayout = tableLayouts.resize(nextLayout, tableColumnKey(column, index), Math.round(widths[index]));
    });
    setCurrentLayout(nextLayout);
    const saved = await saveTableLayoutPreference(tableType, nextLayout);
    endLayoutMutation();
    if (!saved) onSaveFailure?.();
  };
  const resizeColumns = (index, startingWidths, requestedDelta) => {
    const widths = [...startingWidths];
    widths[index] = Math.max(TABLE_WIDTH_MINIMUM,
      Math.min(TABLE_WIDTH_MAXIMUM, startingWidths[index] + requestedDelta));
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
      if (!beginLayoutMutation()) return;
      const startingWidths = headers.map((candidate) => Math.round(candidate.getBoundingClientRect().width));
      const widths = resizeColumns(index, startingWidths, event.key === 'ArrowLeft' ? -10 : 10);
      handle.setAttribute('aria-valuenow', String(Math.round(widths[index])));
      void saveWidths(widths);
    });
    handle.addEventListener('pointerdown', (event) => {
      if (event.button !== 0) return;
      event.preventDefault();
      event.stopPropagation();
      if (!beginLayoutMutation()) return;
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
        void saveWidths(resizedWidths);
      };
      handle.setPointerCapture(event.pointerId);
      handle.addEventListener('pointermove', pointerMove);
      handle.addEventListener('pointerup', pointerUp);
      handle.addEventListener('pointercancel', pointerUp);
    });
    header.append(handle);
  });
}

function cleanupTableLayoutMenu(controller) {
  controller?.layoutMenuCleanup?.();
  if (controller) controller.layoutMenuCleanup = null;
}

function table(rows, columns, emptyText = 'No rows', options = {}) {
  const tableType = tableLayouts.tableId(options.type);
  const tableController = options.controller || {};
  cleanupTableLayoutMenu(tableController);
  const declaredColumns = columns.slice();
  const defaultSchema = tableLayouts.registerSchema(tableSchemaRegistry, tableType, declaredColumns);
  const defaultColumnWidths = Object.fromEntries(Object.entries(TABLE_DEFAULT_COLUMN_WIDTHS[tableType] || {})
    .filter(([id]) => defaultSchema.includes(id)));
  const defaultHiddenColumns = Array.isArray(options.defaultHiddenColumns) ?
    options.defaultHiddenColumns.filter((id) => defaultSchema.includes(id)) : [];
  const storedLayout = options.layout || activeUserPreferences().tables[tableType] ||
    (Array.isArray(options.defaultHiddenColumns) || Object.keys(defaultColumnWidths).length ? {
      schema: defaultSchema,
      column_order: defaultSchema,
      column_widths: defaultColumnWidths,
      hidden_columns: defaultHiddenColumns
    } : null);
  let layout = tableLayouts.normalize(declaredColumns, storedLayout);
  if (layout.reset) removeResetTableLayout(tableType);
  columns = layout.columns;
  if (!columns.length) {
    layout = tableLayouts.normalize(declaredColumns, null);
    columns = layout.columns;
    removeResetTableLayout(tableType);
  }
  const wrapper = options.wrapper || node('div');
  wrapper.className = 'table-wrap';
  wrapper.replaceChildren();
  String(options.wrapperClass || '').split(/\s+/).filter(Boolean)
    .forEach((className) => wrapper.classList.add(className));
  const element = node('table', 'data-table resizable-table');
  String(options.tableClass || '').split(/\s+/).filter(Boolean)
    .forEach((className) => element.classList.add(className));
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
  let clientSortEnabled = typeof tableController.clientSortEnabled === 'boolean' ?
    tableController.clientSortEnabled : options.sortable !== false;
  const clientSortControls = [];

  const orderedDataRows = () => {
    const orderedRows = clientSort ? [...dataRows].sort((left, right) => {
      const result = compareTableValues(tableSortValue(left, clientSort.column),
        tableSortValue(right, clientSort.column));
      return clientSort.direction === 'asc' ? result : -result;
    }) : dataRows;
    return orderedRows;
  };

  const replaceBody = (orderedRows) => {
    body.replaceChildren();
    if (!orderedRows.length) {
      const row = node('tr');
      const cell = node('td', 'empty', emptyText);
      cell.colSpan = columns.length;
      row.append(cell);
      body.append(row);
      return;
    }
    orderedRows.forEach((data) => body.append(renderTableRow(data, columns, options.rowKey, options.rowClass,
      options.onRowClick)));
  };

  const reconcileBody = (orderedRows) => {
    //Live rows can receive several quality-only updates per second.  Preserve keyed rows and unchanged cells so
    //links, hover state, and focus do not churn when only a neighboring measurement changes.
    if (!options.rowKey || !orderedRows.length || body.querySelector('td.empty')) {
      replaceBody(orderedRows);
      return;
    }
    const incomingKeys = orderedRows.map((data) => options.rowKey(data));
    if (incomingKeys.some((key) => key === null || key === undefined) ||
        new Set(incomingKeys.map(String)).size !== incomingKeys.length) {
      replaceBody(orderedRows);
      return;
    }
    const existing = new Map([...body.children]
      .filter((row) => row.dataset.id !== undefined)
      .map((row) => [row.dataset.id, row]));
    let cursor = body.firstElementChild;
    const retained = new Set();
    orderedRows.forEach((data, index) => {
      const key = String(incomingKeys[index]);
      const replacement = renderTableRow(data, columns, options.rowKey, options.rowClass,
        options.onRowClick);
      let current = existing.get(key);
      if (current) {
        retained.add(current);
        current.tableRowData = data;
        current.className = replacement.className;
        const currentCells = [...current.children];
        const replacementCells = [...replacement.children];
        replacementCells.forEach((nextCell, cellIndex) => {
          const previousCell = currentCells[cellIndex];
          const keyedMatch = nextCell.tableReconcileKey !== undefined &&
            previousCell?.tableReconcileKey === nextCell.tableReconcileKey;
          if (!previousCell) current.append(nextCell);
          else if (!keyedMatch && !previousCell.isEqualNode(nextCell)) previousCell.replaceWith(nextCell);
        });
        while (current.children.length > replacementCells.length) current.lastElementChild.remove();
      } else {
        current = replacement;
        retained.add(current);
      }
      if (current !== cursor) body.insertBefore(current, cursor);
      cursor = current.nextElementSibling;
    });
    [...body.children].forEach((row) => {
      if (!retained.has(row)) row.remove();
    });
  };

  const renderBody = (reconcile = false) => {
    const orderedRows = orderedDataRows();
    if (reconcile) reconcileBody(orderedRows);
    else replaceBody(orderedRows);
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
    } else if (!options.serverSort && options.sortable !== false) {
      const control = node('button', 'table-sort-control', column.label);
      control.type = 'button';
      control.disabled = !clientSortEnabled;
      control.addEventListener('click', () => {
        if (!clientSortEnabled) return;
        clientSort = clientSort?.column === column ?
          { column, direction: clientSort.direction === 'asc' ? 'desc' : 'asc' } :
          { column, direction: 'asc' };
        updateSortIndicators();
        renderBody();
      });
      clientSortControls.push(control);
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
  let layoutMenuOpen = () => false;
  let layoutMenuFocus = () => null;
  let setLayoutMenuBusy = () => {};
  let layoutMutationPending = false;
  const setTableLayoutBusy = (busy) => {
    setLayoutMenuBusy(busy);
    element.classList.toggle('table-layout-busy', Boolean(busy));
    element.querySelectorAll('.column-resizer').forEach((handle) =>
      handle.setAttribute('aria-disabled', String(Boolean(busy))));
  };
  const beginLayoutMutation = () => {
    if (layoutMutationPending) return false;
    layoutMutationPending = true;
    setTableLayoutBusy(true);
    return true;
  };
  const endLayoutMutation = () => {
    layoutMutationPending = false;
    setTableLayoutBusy(false);
  };
  const rebuildTable = (nextLayout, reopenLayoutMenu = false, restoreLayoutFocus = null) => {
    if (!wrapper.isConnected) return;
    table(tableController.rows(), declaredColumns, emptyText,
      { ...options, layout: nextLayout, layoutMenuOpen: reopenLayoutMenu,
        layoutMenuFocus: restoreLayoutFocus,
        controller: tableController, wrapper });
  };
  const replaceForLayout = async (nextLayout) => {
    if (!beginLayoutMutation()) return;
    const saved = await saveTableLayoutPreference(tableType, nextLayout);
    const reopenLayoutMenu = layoutMenuOpen();
    const restoreLayoutFocus = reopenLayoutMenu ? layoutMenuFocus() : null;
    endLayoutMutation();
    if (!wrapper.isConnected) {
      return;
    }
    if (!saved) {
      rebuildTable(null, reopenLayoutMenu, restoreLayoutFocus);
      return;
    }
    layout = nextLayout;
    rebuildTable(tableLayouts.persisted(layout), reopenLayoutMenu, restoreLayoutFocus);
  };
  if (userPreferenceController.snapshot().loaded) {
    const chooser = node('div', 'table-layout-menu');
    const inline = options.layoutMenuHost instanceof Node;
    if (inline) chooser.classList.add('table-layout-menu-inline');
    chooser.dataset.tableType = tableType;
    const panelId = `table-layout-panel-${++tableLayoutPanelSequence}`;
    const trigger = iconButton('icon-columns', 'Choose table columns',
      'button secondary icon-button section-title-icon table-layout-trigger');
    trigger.setAttribute('popovertarget', panelId);
    trigger.setAttribute('aria-haspopup', 'dialog');
    trigger.setAttribute('aria-controls', panelId);
    trigger.setAttribute('aria-expanded', 'false');
    const panel = node('div', 'table-layout-panel');
    panel.id = panelId;
    panel.setAttribute('popover', 'auto');
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-label', 'Table columns');
    const panelHeader = node('div', 'table-layout-panel-header');
    panelHeader.append(node('strong', '', 'Table columns'), node('span', '', 'Show and arrange this table.'));
    const optionsHost = node('div', 'table-layout-options');
    panel.append(panelHeader, optionsHost);
    const byId = new Map(declaredColumns.map((column) => [column.id, column]));
    layout.column_order.forEach((id) => {
      const item = node('div', 'table-layout-column');
      const visibility = node('input');
      visibility.type = 'checkbox';
      visibility.dataset.layoutFocusKey = `visibility:${id}`;
      visibility.dataset.layoutColumnId = id;
      visibility.checked = !layout.hidden_columns.includes(id);
      const visibleCount = layout.column_order.length - layout.hidden_columns.length;
      visibility.disabled = visibility.checked && visibleCount <= 1;
      const displayLabel = byId.get(id).fullLabel || byId.get(id).label || id;
      visibility.setAttribute('aria-label', `Show ${displayLabel} column`);
      visibility.addEventListener('change', () => void replaceForLayout(
        tableLayouts.setHidden(layout, id, !visibility.checked)));
      const label = node('span', '', displayLabel);
      const group = layout.groups[id] || '';
      const siblings = layout.column_order.filter((column) => (layout.groups[column] || '') === group);
      const position = siblings.indexOf(id);
      const earlier = node('button', 'button secondary table-layout-move', '←');
      earlier.type = 'button';
      earlier.dataset.layoutFocusKey = `earlier:${id}`;
      earlier.dataset.layoutColumnId = id;
      earlier.disabled = position <= 0;
      earlier.setAttribute('aria-label', `Move ${label.textContent} left`);
      earlier.addEventListener('click', () => void replaceForLayout(
        tableLayouts.move(layout, id, siblings[position - 1])));
      const later = node('button', 'button secondary table-layout-move', '→');
      later.type = 'button';
      later.dataset.layoutFocusKey = `later:${id}`;
      later.dataset.layoutColumnId = id;
      later.disabled = position < 0 || position >= siblings.length - 1;
      later.setAttribute('aria-label', `Move ${label.textContent} right`);
      later.addEventListener('click', () => {
        const after = siblings[position + 2] || null;
        void replaceForLayout(tableLayouts.move(layout, id, after));
      });
      item.append(visibility, label, earlier, later);
      optionsHost.append(item);
    });
    const reset = node('button', 'button secondary table-layout-reset', 'Reset this table');
    reset.type = 'button';
    reset.dataset.layoutFocusKey = 'reset';
    reset.addEventListener('click', async () => {
      if (!beginLayoutMutation()) return;
      const saved = await settleUserPreferenceMutation(
        (preferences) => { delete preferences.tables[tableType]; }, false);
      const reopenLayoutMenu = layoutMenuOpen();
      const restoreLayoutFocus = reopenLayoutMenu ? layoutMenuFocus() : null;
      endLayoutMutation();
      if (!wrapper.isConnected) {
        return;
      }
      rebuildTable(null, reopenLayoutMenu, restoreLayoutFocus);
    });
    const panelFooter = node('div', 'table-layout-panel-footer');
    panelFooter.append(reset);
    panel.append(panelFooter);
    chooser.append(trigger, panel);
    layoutMenuOpen = () => panel.matches(':popover-open');
    layoutMenuFocus = () => {
      const active = document.activeElement;
      if (!(active instanceof HTMLElement) || !chooser.contains(active)) return null;
      return {
        key: active.dataset.layoutFocusKey || null,
        columnId: active.dataset.layoutColumnId || null
      };
    };
    setLayoutMenuBusy = (busy) => chooser.querySelectorAll('button, input').forEach((control) => {
      if (busy) {
        if (!control.hasAttribute('data-layout-was-disabled')) {
          control.dataset.layoutWasDisabled = String(control.disabled);
        }
        control.disabled = true;
      } else if (control.hasAttribute('data-layout-was-disabled')) {
        control.disabled = control.dataset.layoutWasDisabled === 'true';
        delete control.dataset.layoutWasDisabled;
      }
    });
    const dropdownCleanup = bindAnchoredDropdown(trigger, panel, activeRenderController?.signal);
    tableController.layoutMenuCleanup = () => {
      dropdownCleanup();
      chooser.remove();
    };
    (options.layoutMenuHost || wrapper).append(chooser);
    if (options.layoutMenuOpen) window.requestAnimationFrame(() => {
      if (!panel.isConnected || typeof panel.showPopover !== 'function') return;
      panel.showPopover();
      const controls = [...panel.querySelectorAll('[data-layout-focus-key]')];
      const requested = options.layoutMenuFocus;
      const focusTarget = controls.find((control) => !control.disabled &&
        control.dataset.layoutFocusKey === requested?.key) ||
        controls.find((control) => !control.disabled && requested?.columnId &&
          control.dataset.layoutColumnId === requested.columnId) ||
        controls.find((control) => !control.disabled);
      if (focusTarget instanceof HTMLElement) focusTarget.focus();
    });
  }
  wrapper.append(element);
  applyPreferredTableWidths(element, columns, columnElements, layout);
  addColumnResizers(element, columns, columnElements, headers, tableType,
    () => layout, (nextLayout) => { layout = nextLayout; }, beginLayoutMutation, endLayoutMutation,
    () => rebuildTable(null));
  updateSortIndicators();
  renderBody();
  Object.assign(tableController, {
    addRow(data, { prepend = true, limit = null } = {}) {
      if (prepend) dataRows.unshift(data);
      else dataRows.push(data);
      if (limit && dataRows.length > limit) {
        dataRows = prepend ? dataRows.slice(0, limit) : dataRows.slice(-limit);
      }
      if (clientSort) {
        renderBody();
        return;
      }
      const rendered = renderTableRow(data, columns, options.rowKey, options.rowClass, options.onRowClick);
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
    reconcileRows(rows) {
      dataRows = [...(rows || [])];
      renderBody(true);
    },
    setEmptyText(value) {
      emptyText = String(value || 'No rows');
      renderBody();
    },
    setSortable(value) {
      const enabled = Boolean(value);
      tableController.clientSortEnabled = enabled;
      if (clientSortEnabled === enabled) return;
      clientSortEnabled = enabled;
      clientSortControls.forEach((control) => { control.disabled = !enabled; });
      if (!enabled && clientSort) {
        clientSort = null;
        updateSortIndicators();
        renderBody();
      }
    },
    rows: () => dataRows.slice(),
    render: renderBody
  });
  wrapper.tableController = tableController;
  return wrapper;
}

function tableSection(title, rows, columns, emptyText = 'No rows', options = {}, trailing = null,
  action = null) {
  const actions = sectionActionHost(action);
  const child = fragment(table(rows, columns, emptyText, { ...options, layoutMenuHost: actions }), trailing);
  return section(title, child, actions);
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
    defaultSort: SERVER_TABLE_DEFAULT_SORTS[tableType] ||
      SERVER_TABLE_DEFAULT_SORTS[String(tableType).split('.')[0]],
    defaultDirection: 'desc',
    ...(options.tableOptions || {})
  }));
  result.append(pager(page, 'bottom', itemLabel));
  return result;
}

function pagedSection(title, page, columns, searchPlaceholder, tableType, action = null, options = {}) {
  const actions = sectionActionHost(action);
  const tableOptions = { ...(options.tableOptions || {}), layoutMenuHost: actions };
  return fragment(searchPlaceholder ? searchBar(searchPlaceholder) : null,
    section(title, pagedTableContent(page, columns, tableType, { ...options, tableOptions }), actions));
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
    { id: 'overlap', label: 'Overlap', group: 'Validation', render: aliasConflictButton }
  ];
}

function aliasActivityColumns() {
  const count = (id, label, field, group, fullLabel, sort = field) => ({
    id, label, field, group, fullLabel, sort,
    render: (row) => aliasMetricValue(row, field), className: 'numeric',
    sortValue: (row) => row[field] === null || row[field] === undefined ? -1 : Number(row[field])
  });
  return [
    count('calls', 'Calls', 'logical_call_count', 'Activity',
      'Unique transmissions associated with this alias after matching multisite copies are combined.'),
    count('signaling', 'Signaling', 'signaling_observation_count', 'Activity',
      'Recognized grants, joins, registrations, logouts, emergencies, denials, data, and other signaling actions.'),
    { id: 'last-seen', label: 'Last Seen', field: 'last_evidence_ms', group: 'Activity',
      fullLabel: 'Most recent retained call or signaling activity',
      render: (row) => aliasMetricTime(row, 'last_evidence_ms'), sort: 'last_evidence_ms',
      sortValue: (row) => Number(row.last_evidence_ms || 0) }
  ];
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

function aliasMatcherSummary(matcher) {
  if (!matcher || typeof matcher !== 'object') return 'Matcher unavailable';
  const type = aliasMatcherOption(matcher.type).label || 'Matcher';
  const context = [matcher.protocol, matcher.variant].filter(Boolean).join(' · ');
  let value = '';
  if (matcher.value !== null && matcher.value !== undefined) value = identifierNumber(matcher.value);
  else if (matcher.minimum !== null && matcher.minimum !== undefined &&
      matcher.maximum !== null && matcher.maximum !== undefined) {
    value = `${identifierNumber(matcher.minimum)}–${identifierNumber(matcher.maximum)}`;
  } else if (matcher.status !== null && matcher.status !== undefined) value = identifierNumber(matcher.status);
  else if (matcher.code) value = String(matcher.code).toUpperCase();
  else if (matcher.esn) value = String(matcher.esn);
  else if (Array.isArray(matcher.tones)) {
    value = matcher.tones.map((tone) => `${tone?.tone || 'Tone'} ×${identifierNumber(tone?.duration ?? 1)}`)
      .join(' → ');
  }
  return [type, context, value].filter(Boolean).join(' · ');
}

function aliasConflictButton(row, label = 'Conflict', detailsHost = null) {
  const id = Number(row?.alias_id);
  if (!row?.overlap || !Number.isInteger(id) || id <= 0) return '—';
  const button = node('button', 'button secondary alias-conflict-button', label);
  button.type = 'button';
  button.dataset.aliasId = String(id);
  button.title = 'Show aliases with overlapping identifiers';
  button.addEventListener('click', async (event) => {
    event.stopPropagation();
    if (!detailsHost) {
      openAliasConflictModal(id, row.name);
      return;
    }
    button.disabled = true;
    detailsHost.replaceChildren(node('div', 'loading', 'Finding conflicting aliases'));
    try {
      const response = await requestJson(`/api/v1/admin/aliases/${id}/conflicts`, { csrf: false });
      detailsHost.replaceChildren(aliasConflictDetail(response, id, row.name));
    } catch (error) {
      detailsHost.replaceChildren(node('div', 'error', error.message || 'Unable to load conflicting aliases.'));
    } finally {
      button.disabled = false;
      if (button.isConnected) button.focus();
    }
  });
  return button;
}

function aliasDetailMetricBand(row, definitions) {
  return metrics(definitions.map(([label, field]) =>
    [label, row[field] ?? 0, aliasMetricValue(row, field)]), true);
}

function aliasEditorSourceBreakdownColumns() {
  return [
    { id: 'source', label: 'Source', width: 230, className: 'alias-cell', render: (row) => {
      const value = node('div', 'alias-source-identity');
      value.append(node('strong', '', availableValue(row.source_label)));
      if (row.topology) value.append(node('span', 'muted', availableValue(row.topology)));
      return value;
    } },
    { id: 'source-calls', label: 'Calls', width: 100, className: 'numeric',
      render: (row) => aliasMetricValue(row, 'logical_call_count'),
      sortValue: (row) => Number(row.logical_call_count || 0) },
    { id: 'source-signaling', label: 'Signaling', width: 110, className: 'numeric',
      render: (row) => aliasMetricValue(row, 'signaling_observation_count'),
      sortValue: (row) => Number(row.signaling_observation_count || 0) },
    { id: 'last-seen', label: 'Last Seen', width: 166,
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
  const publicById = new Map((publicRows || []).map((row) => [aliasListId(row), row]));
  return (adminRows || []).map((row) => {
    const publicRow = publicById.get(aliasListId(row));
    return {
      ...row,
      ...(publicRow?.alias_count !== undefined ? { alias_count: publicRow.alias_count } : {}),
      ...(publicRow?.assigned_channel_count !== undefined ?
        { assigned_channel_count: publicRow.assigned_channel_count } : {})
    };
  })
    .sort((left, right) => String(left.name || '').localeCompare(String(right.name || ''), undefined,
      { numeric: true, sensitivity: 'base' }));
}

function aliasListCountLabel(row) {
  const value = Number(row?.alias_count);
  return Number.isInteger(value) && value >= 0 ? `${number(value)} aliases` : 'Alias count unavailable';
}

function aliasOptionLimit(options, name) {
  const values = Array.isArray(options?.[name]) ? options[name] : [];
  const reportedTotal = Number(options?.[`${name}_total`]);
  const total = Number.isInteger(reportedTotal) && reportedTotal >= values.length ? reportedTotal : values.length;
  return {
    shown: values.length,
    total,
    truncated: options?.[`${name}_truncated`] === true || total > values.length
  };
}

function aliasCloneOptionValue(value, configured, cloning, optionsTruncated) {
  return cloning && value && !configured && !optionsTruncated ? '' : value || '';
}

function aliasStreamOptionSelected(selected, configured, editing, optionsTruncated) {
  return Boolean(selected && (editing || configured || optionsTruncated));
}

function aliasOptionLimitNotice(options, name, label, guidance = '') {
  const limit = aliasOptionLimit(options, name);
  if (!limit.truncated) return null;
  return node('p', 'logging-notice warning alias-option-limit-notice',
    `Showing ${number(limit.shown)} of ${number(limit.total)} ${label}.${guidance ? ` ${guidance}` : ''}`);
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
        node('span', '', aliasListCountLabel(row)));
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
    const option = node('option', '', `${row.name} · ${aliasListFamilyLabel(row)} · ${aliasListCountLabel(row)}`);
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

function aliasEditorView(selectedList) {
  const supportsDiscovery = observedGroupIdentityDiscoverySupported(selectedList);
  const allowed = supportsDiscovery ? ['configure', 'discover', 'activity', 'custom'] :
    ['configure', 'activity', 'custom'];
  const requested = ['calls', 'evidence'].includes(route.get('aliasTab')) ?
    'activity' : route.get('aliasTab');
  return allowed.includes(requested) ? requested : 'configure';
}

function aliasEditorDefaultOrder(view) {
  return view === 'activity' ? { sort: 'logical_call_count', direction: 'desc' } :
    { sort: 'name', direction: 'asc' };
}

function aliasEditorViewTabs(selectedList) {
  const id = aliasListId(selectedList);
  const active = aliasEditorView(selectedList);
  const entries = [
    { id: 'configure', label: 'Configure', href: href('aliases', { list: id, aliasTab: 'configure' }) }
  ];
  if (observedGroupIdentityDiscoverySupported(selectedList)) {
    entries.push({ id: 'discover', label: 'Discover', href: href('aliases', { list: id, aliasTab: 'discover' }) });
  }
  entries.push(
    { id: 'activity', label: 'Activity', href: href('aliases', { list: id, aliasTab: 'activity' }) },
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
  const filterGroup = (label, className, controls) => {
    const group = node('fieldset', `alias-filter-group ${className}`);
    const fields = node('div', 'alias-filter-group-fields');
    fields.append(...controls);
    group.append(node('legend', '', label), fields);
    return group;
  };
  const identityGroup = filterGroup('Find aliases', 'alias-filter-group-identity', [
    search,
    selectFilter('Identity', 'type', [['', 'All identities'], ['talkgroup', 'Talkgroups'],
      ['radio', 'Radios'], ['other', 'Other']]),
    selectFilter('Matcher', 'matcher', [['', 'All matchers'],
      ...matcherOptions.map((entry) => [entry.value, entry.label])]),
    groupFilterWrapper,
    groupList
  ]);
  const behaviorGroup = filterGroup('Call handling', 'alias-filter-group-behavior', [
    selectFilter('Scan list', 'scanListId', [
      ...(scanListScope ? [] : [['', 'Any scan list']]),
      ...(options?.scan_lists || []).map((row) => [String(row.id),
        `${row.name}${row.published === false ? ' · not published' : ''}`])]),
    selectFilter('Record', 'record', [['', 'Any'], ['enabled', 'Enabled'], ['disabled', 'Disabled']]),
    selectFilter('Stream', 'stream', [['', 'Any'], ['present', 'Configured'], ['none', 'None']]),
    selectFilter('Calls', 'use', [['', 'Any'], ['used', 'Has calls'],
      ['unused', 'No calls observed']])
  ]);
  const seenAfter = node('label', 'alias-filter alias-date-filter');
  seenAfter.append(node('span', '', 'Seen after'), lastAfter);
  const seenBefore = node('label', 'alias-filter alias-date-filter');
  seenBefore.append(node('span', '', 'Seen before'), lastBefore);
  const activeFilters = ['q', 'type', 'matcher', 'group', ...(scanListScope ? [] : ['scanListId']),
    'record', 'stream', 'evidence', 'use', 'lastActivityAfter', 'lastActivityBefore'];
  const actions = node('div', 'alias-filter-actions');
  actions.append(node('button', '', 'Apply'));
  if (activeFilters.some((key) => route.get(key))) {
    actions.append(anchor('Clear', href('aliases', {
      list: route.get('list'), aliasTab: route.get('aliasTab') || 'configure',
      scanListId: scanListScope ? route.get('scanListId') : null
    }), 'button secondary'));
  }
  form.append(identityGroup, behaviorGroup,
    filterGroup('Observed activity', 'alias-filter-group-observed', [seenAfter, seenBefore, actions]));
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
        try {
          if (event.shiftKey && aliasEditorLastSelectionIndex !== null) {
            const start = Math.min(index, aliasEditorLastSelectionIndex);
            const end = Math.max(index, aliasEditorLastSelectionIndex);
            const ids = rows.slice(start, end + 1).map((candidate) => Number(candidate.alias_id));
            if (checkbox.checked) aliasEditorSelection = extendedAliasSelection(aliasEditorSelection, ids);
            else ids.forEach((candidateId) => aliasEditorSelection.delete(candidateId));
          } else if (checkbox.checked) aliasEditorSelection = extendedAliasSelection(aliasEditorSelection, [id]);
          else aliasEditorSelection.delete(id);
          aliasEditorLastSelectionIndex = index;
          onSelectionChange();
        } catch (error) {
          onSelectionChange(error.message, true);
        }
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

function aliasEditorColumns(view, rows, onSelectionChange) {
  const base = aliasEditorBaseColumns(rows, onSelectionChange);
  const activity = aliasActivityColumns();
  if (view === 'activity') {
    return [...base, ...activity];
  }
  if (view === 'custom') {
    const selection = base.filter((column) => column.id === 'select');
    const definitions = [...aliasCustomConfigurationColumns(), ...activity];
    return [...selection, ...definitions];
  }
  return [...base,
    { id: 'behavior', label: 'Behavior', group: 'Call Handling', render: aliasBehavior },
    { id: 'overlap', label: 'Overlap', group: 'Validation', render: aliasConflictButton }];
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
    { id: 'overlap', label: 'Overlap', group: 'Validation', render: aliasConflictButton });
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
    const id = Number(scanList?.id);
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

function aliasConflictSummary(row) {
  const wrapper = node('li', 'alias-conflict-item');
  const id = Number(row?.alias_id);
  const name = String(row?.name || '').trim() || `Alias ${identifierNumber(id)}`;
  const link = anchor(name, href('aliases', {
    list: Number(row?.alias_list_id), aliasTab: 'configure', alias: id
  }), 'alias-conflict-alias-link');
  link.dataset.aliasId = String(id);
  link.addEventListener('click', (event) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    openAliasEditorModal('edit', id, { alias_list_id: Number(row?.alias_list_id) });
  });
  wrapper.append(link);
  const context = [row?.alias_list_name, aliasMatcherSummary(row?.matcher)].filter(Boolean).join(' · ');
  if (context) wrapper.append(node('small', 'muted', context));
  return wrapper;
}

function aliasConflictDetail(response, aliasId, aliasName = '') {
  const id = Number(aliasId);
  const conflicts = Array.isArray(response?.conflicts) ? response.conflicts : [];
  const total = Number(response?.conflicts_total ?? conflicts.length);
  const body = node('div', 'alias-conflict-detail');
  body.append(node('p', '', `${response?.alias?.name || aliasName || `Alias ${identifierNumber(id)}`} uses ${
    aliasMatcherSummary(response?.alias?.matcher)}.`));
  if (!conflicts.length) {
    body.append(node('div', 'empty', 'No current conflicts were found. The list may have changed.'));
  } else {
    body.append(node('p', 'muted', `${number(total)} overlapping ${total === 1 ? 'alias' : 'aliases'} found.`));
    const list = node('ul', 'alias-conflict-list');
    list.append(...conflicts.map(aliasConflictSummary));
    body.append(list);
    if (response?.conflicts_truncated === true || total > conflicts.length) {
      body.append(node('p', 'logging-notice warning',
        `Showing the first ${number(conflicts.length)} of ${number(total)} conflicts.`));
    }
  }
  return body;
}

async function openAliasConflictModal(aliasId, aliasName = '') {
  const id = Number(aliasId);
  if (!Number.isInteger(id) || id <= 0) return;
  const loading = node('div', 'loading', 'Finding conflicting aliases');
  const modal = openReadOnlyModal(`Identifier conflicts${aliasName ? ` · ${aliasName}` : ''}`, loading, {
    id: `alias-conflicts-${id}`, className: 'alias-editor-modal alias-conflict-modal',
    returnFocusSelector: `.alias-conflict-button[data-alias-id="${id}"]`
  });
  if (!modal) return;
  modal.setBusy(true);
  try {
    const response = await requestJson(`/api/v1/admin/aliases/${id}/conflicts`, { csrf: false });
    if (activeReadOnlyModal !== modal.state) return;
    modal.content.replaceChildren(aliasConflictDetail(response, id, aliasName));
    modal.setBusy(false);
    modal.dialog.querySelector('.modal-close')?.focus();
  } catch (error) {
    if (activeReadOnlyModal !== modal.state) return;
    modal.content.replaceChildren(node('div', 'error', error.message || 'Unable to load conflicting aliases.'));
    modal.setBusy(false);
  }
}

async function finishAliasMutation(modal, result, routeChanges = {}) {
  if (modal) modal.setDirty(false);
  closeReadOnlyModal(true);
  resetAliasEditorSelection();
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
  closeReadOnlyModal(true);
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

function reorderedAliasToneRows(rows, index, direction) {
  const reordered = [...(rows || [])];
  const target = index + (direction < 0 ? -1 : 1);
  if (!Number.isInteger(index) || index < 0 || index >= reordered.length ||
      target < 0 || target >= reordered.length) return reordered;
  [reordered[index], reordered[target]] = [reordered[target], reordered[index]];
  return reordered;
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
      const refreshToneActions = () => {
        const rows = [...toneHost.children];
        rows.forEach((row, index) => {
          const up = row.querySelector('.alias-tone-up');
          const down = row.querySelector('.alias-tone-down');
          if (up) up.disabled = index === 0;
          if (down) down.disabled = index === rows.length - 1;
        });
      };
      const moveTone = (row, direction, button) => {
        const rows = [...toneHost.children];
        const index = rows.indexOf(row);
        const reordered = reorderedAliasToneRows(rows, index, direction);
        if (reordered[index] === row) return;
        toneHost.replaceChildren(...reordered);
        refreshToneActions();
        const fallback = row.querySelector(direction < 0 ? '.alias-tone-down' : '.alias-tone-up');
        const focusTarget = button.disabled ? fallback : button;
        if (focusTarget instanceof HTMLElement) focusTarget.focus();
      };
      const addTone = (tone = {}) => {
        const row = node('div', 'alias-tone-row');
        const toneSelect = aliasSelect('matcher-tone', options?.tones || [], tone.tone || options?.tones?.[0]);
        const duration = aliasTextInput('matcher-tone-duration', tone.duration ?? 1, 'number');
        duration.min = '1';
        duration.max = '50';
        duration.step = '1';
        duration.required = true;
        const actions = node('div', 'alias-tone-actions');
        actions.setAttribute('role', 'group');
        actions.setAttribute('aria-label', 'Tone sequence order');
        const up = node('button', 'button secondary alias-tone-move alias-tone-up', '↑');
        up.type = 'button';
        up.title = 'Move tone up';
        up.setAttribute('aria-label', 'Move tone up');
        up.addEventListener('click', () => moveTone(row, -1, up));
        const down = node('button', 'button secondary alias-tone-move alias-tone-down', '↓');
        down.type = 'button';
        down.title = 'Move tone down';
        down.setAttribute('aria-label', 'Move tone down');
        down.addEventListener('click', () => moveTone(row, 1, down));
        const remove = node('button', 'button secondary alias-tone-remove', 'Remove');
        remove.type = 'button';
        remove.addEventListener('click', () => {
          row.remove();
          refreshToneActions();
        });
        actions.append(up, down, remove);
        row.append(toneSelect, duration, actions);
        toneHost.append(row);
        refreshToneActions();
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

function aliasActivityContent(response) {
  const alias = response || {};
  const sourceRows = response?.breakdown || [];
  const wrapper = node('div', 'alias-usage-content');
  wrapper.append(section('Calls', aliasDetailMetricBand(alias, [
    ['Logical Calls', 'logical_call_count'], ['Recorded', 'recorded_logical_call_count'],
    ['Submitted', 'stream_submitted_logical_call_count'], ['Encrypted', 'encrypted_logical_call_count']
  ])));
  wrapper.append(section('Signaling', fragment(
    aliasDetailMetricBand(alias, [
      ['Total', 'signaling_observation_count'],
      ['Grants', 'grant_observation_count'], ['Join', 'join_observation_count'],
      ['Emergency', 'emergency_observation_count'], ['Register', 'register_observation_count'],
      ['Logout', 'logout_observation_count'], ['Denial', 'denial_observation_count'],
      ['Data', 'data_observation_count']
    ]),
    node('p', 'metric-meaning-note',
      'The total also includes other recognized signaling actions. Calls and signaling can describe the same ' +
      'transmission, so they should not be added together. Logout means unit deregistration, not leaving a group.')
  )));
  wrapper.append(section('Related State', fragment(
    aliasDetailMetricBand(alias, [
      ['Relationships', 'relationship_count'],
      ['Join Relationships', 'join_relationship_count'], ['Current Affiliations', 'current_affiliation_count']
    ]),
    keyValues([
      ['First Seen', aliasMetricTime(alias, 'first_evidence_ms')],
      ['Last Seen', aliasMetricTime(alias, 'last_evidence_ms')]
    ]),
    node('p', 'metric-meaning-note',
      'Relationships and current affiliations are derived from retained call and signaling activity and are not ' +
      'included in the Signaling total. ' +
      'An em dash means unavailable; 0 means monitored with none observed.')
  )));
  wrapper.append(tableSection('Source Breakdown', sourceRows, aliasEditorSourceBreakdownColumns(),
    'No compatible monitored sources', { type: 'alias-editor-source-breakdown' },
    node('p', 'metric-meaning-note',
      'Each source is a radio system or saved channel. Calls and signaling remain separate.')));
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
    ['usage', 'Activity']].forEach(([id, label]) => {
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
    broadcast_configuration_ids: [...form.querySelectorAll('[name="broadcastChannel"]:checked')]
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
      onClose: clearAliasEditorRoute,
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
      source.broadcast_configuration_ids = inherits ? [...(defaults.broadcast_configuration_ids || [])] : [];
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
      const prefix = options.icon_names_truncated === true ? 'Current (outside suggestion limit)' : 'Missing';
      iconEntries.unshift({ value: source.icon_name, label: `${prefix}: ${source.icon_name}` });
    }
    const selectedIcon = aliasCloneOptionValue(source.icon_name, configuredIcons.has(source.icon_name), cloning,
      options.icon_names_truncated === true);
    const icon = aliasSelect('iconName', iconEntries, selectedIcon, true);
    const basicsGrid = node('div', 'alias-editor-grid');
    basicsGrid.append(aliasFormField('Alias list', listSelect), aliasFormField('Alias name', name),
      aliasFormField('Group', group), groupList, aliasFormField('Color', color),
      aliasFormField('Icon', icon), aliasFormField('Description', description));
    basics.append(basicsGrid);
    const groupLimitNotice = aliasOptionLimitNotice(options, 'group_names', 'existing group suggestions',
      'You can still type an exact group name.');
    const iconLimitNotice = aliasOptionLimitNotice(options, 'icon_names', 'icons');
    if (groupLimitNotice) basics.append(groupLimitNotice);
    if (iconLimitNotice) basics.append(iconLimitNotice);

    const matcherType = aliasSelect('matcherType', (options.matchers || []).map((entry) => ({
      value: aliasMatcherKey(entry), label: entry.label
    })), aliasMatcherKey(descriptor));
    matcherType.dataset.originalSelection = aliasMatcherKey(descriptor);
    matcherType.dataset.originalType = String(source.matcher?.type || '');
    matcherType.dataset.originalProtocol = String(source.matcher?.protocol || '');
    matcherType.dataset.originalVariant = String(source.matcher?.variant || '');
    const matcherNotice = node('div', 'alias-identifier-notice');
    if (source.overlap) {
      const warning = node('div', 'logging-notice warning',
        'This identifier overlaps another alias in the list.');
      const conflictDetails = node('div', 'alias-conflict-inline');
      warning.append(' ', aliasConflictButton({ ...source, alias_id: source.alias_id || id },
        'Show conflicts', conflictDetails));
      matcherNotice.append(warning, conflictDetails);
    }
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
    const selectedStreams = new Set(source.broadcast_configuration_ids || []);
    const configuredStreams = new Map((options.streams || []).map((entry) =>
      [entry.configuration_id, entry.name || entry.configuration_id]));
    const streamIds = [...new Set([...configuredStreams.keys(), ...selectedStreams])];
    if (!streamIds.length) streams.append(node('div', 'empty', 'No stream destinations configured'));
    streamIds.forEach((configurationId) => {
      const label = node('label', 'alias-check-option');
      const checkbox = node('input');
      checkbox.type = 'checkbox';
      checkbox.name = 'broadcastChannel';
      checkbox.value = configurationId;
      checkbox.checked = aliasStreamOptionSelected(selectedStreams.has(configurationId),
        configuredStreams.has(configurationId), editing, options.streams_truncated === true);
      const missing = !configuredStreams.has(configurationId);
      if (missing) label.classList.add('missing');
      const missingLabel = options.streams_truncated === true ?
        `Current destination outside display limit (${configurationId})` :
        `Missing destination (${configurationId})`;
      label.append(checkbox, node('span', '', missing ? missingLabel : configuredStreams.get(configurationId)));
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
      const selectedDestinations = new Set(inherits ? (defaults.broadcast_configuration_ids || []) : []);
      streams.querySelectorAll('[name="broadcastChannel"]').forEach((checkbox) => {
        checkbox.checked = selectedDestinations.has(checkbox.value);
      });
    };
    const streamAs = aliasTextInput('streamAsTalkgroup', source.stream_as_talkgroup ?? '', 'number');
    streamAs.min = '1';
    streamAs.max = '16777215';
    streamAs.step = '1';
    audio.append(scanLists, audioGrid, streams);
    const streamLimitNotice = aliasOptionLimitNotice(options, 'streams', 'stream destinations',
      'Destinations already saved on this alias remain visible.');
    if (streamLimitNotice) audio.append(streamLimitNotice);
    audio.append(aliasFormField('Stream as talkgroup', streamAs,
      'Optional talkgroup ID sent to configured streaming destinations'));

    usage.append(analytics ? aliasActivityContent(analytics) :
      node('div', 'empty', 'Activity becomes available after the alias is saved and observed.'));
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
  closeReadOnlyModal(true);
        openAliasEditorModal('clone', id);
      });
    }
    if (remove) {
      remove.type = 'button';
      remove.addEventListener('click', () => {
        if (modal.isDirty() && !window.confirm('Discard these edits and delete the saved alias?')) return;
        modal.setDirty(false);
  closeReadOnlyModal(true);
        openAliasDeleteModal(id, source.name, revision);
      });
    }
    form.append(tabBar, basics, identifier, audio, usage, errorHost,
      aliasModalFooter(remove, clone, node('span', 'alias-modal-footer-spacer'), cancel, save));
    form.addEventListener('input', () => modal.setDirty(true));
    form.addEventListener('change', () => modal.setDirty(true));
    form.addEventListener('click', (event) => {
      if (event.target.closest('.alias-tone-add, .alias-tone-remove, .alias-tone-move')) modal.setDirty(true);
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
  closeReadOnlyModal(true);
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
  closeReadOnlyModal(true);
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
    id: `delete-alias-${id}`, className: 'alias-editor-modal alias-confirm-modal',
    onClose: clearAliasEditorRoute
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
  closeReadOnlyModal(true);
        render();
      });
      remove.disabled = false;
    }
  });
}

function aliasSelectionScopeKey(kind, filters = {}) {
  const fields = ['list', 'type', 'matcher', 'group', 'scan_list_id', 'record', 'stream', 'q', 'evidence', 'use',
    'last_activity_before', 'last_activity_after'];
  return JSON.stringify([String(kind || ''), ...fields.map((field) => {
    const value = filters?.[field];
    return value === null || value === undefined || value === '' ? null : String(value);
  })]);
}

function resetAliasEditorSelection(scope = null) {
  aliasEditorSelection.clear();
  aliasEditorSelectionScope = scope;
  aliasEditorLastSelectionIndex = null;
  aliasEditorSelectionRequest += 1;
}

function synchronizeAliasEditorSelectionScope(scope) {
  if (aliasEditorSelectionScope !== scope) resetAliasEditorSelection(scope);
  else {
    aliasEditorLastSelectionIndex = null;
    aliasEditorSelectionRequest += 1;
  }
}

function clearInactiveAliasSelection(activeTable) {
  if (!activeTable && (aliasEditorSelection.size || aliasEditorSelectionScope !== null)) {
    resetAliasEditorSelection();
  }
}

function clearAliasSelectionOutsideEditor(view) {
  clearInactiveAliasSelection(view === 'aliases');
}

function completeAliasSelection(response, maximum = ALIAS_BULK_SELECTION_LIMIT) {
  if (!response || !Array.isArray(response.alias_ids) ||
      Number(response.count) !== response.alias_ids.length) {
    throw new Error('The Alias list returned an invalid selection response.');
  }
  if (response.alias_ids.length > maximum) {
    throw new Error(`Alias selections are limited to ${maximum} aliases.`);
  }
  const ids = response.alias_ids.map(Number);
  if (ids.some((id) => !Number.isInteger(id) || id <= 0) || new Set(ids).size !== ids.length) {
    throw new Error('The Alias list returned invalid or duplicate Alias IDs.');
  }
  return ids;
}

function extendedAliasSelection(selection, additions, maximum = ALIAS_BULK_SELECTION_LIMIT) {
  const next = new Set(selection);
  for (const value of additions) {
    const id = Number(value);
    if (!Number.isInteger(id) || id <= 0) throw new Error('The Alias selection contains an invalid Alias ID.');
    next.add(id);
  }
  if (next.size > maximum) {
    throw new Error(`Select no more than ${maximum} aliases at one time. The previous selection was kept.`);
  }
  return next;
}

function validatedAliasSelectionIds(selection, maximum = ALIAS_BULK_SELECTION_LIMIT) {
  const ids = [...selection].map(Number);
  if (ids.some((id) => !Number.isInteger(id) || id <= 0) || new Set(ids).size !== ids.length) {
    throw new Error('The Alias selection contains invalid Alias IDs.');
  }
  if (ids.length > maximum) {
    throw new Error(`Select no more than ${maximum} aliases at one time.`);
  }
  return ids;
}

async function selectAllMatchingAliases(filters, scope, button, onSelectionChange) {
  const request = ++aliasEditorSelectionRequest;
  const label = button.textContent;
  button.disabled = true;
  button.textContent = 'Selecting…';
  try {
    const response = await api('/api/v1/aliases/ids', filters, {
      timeoutMs: ALIAS_BULK_REQUEST_TIMEOUT_MS
    });
    if (request !== aliasEditorSelectionRequest || aliasEditorSelectionScope !== scope) return;
    const ids = completeAliasSelection(response, ALIAS_BULK_SELECTION_LIMIT);
    aliasEditorSelection = new Set(ids);
    aliasEditorLastSelectionIndex = null;
    onSelectionChange(ids.length ? `Selected all ${number(ids.length)} matching aliases.` :
      'No aliases match the current filters.', false, true);
  } catch (error) {
    if (request === aliasEditorSelectionRequest && aliasEditorSelectionScope === scope) {
      onSelectionChange(error.message || 'Unable to select matching aliases.', true, true);
    }
  } finally {
    if (button.isConnected) {
      button.disabled = false;
      button.textContent = label;
    }
  }
}

function aliasMutationSelectionIds() {
  try {
    return validatedAliasSelectionIds(aliasEditorSelection, ALIAS_BULK_SELECTION_LIMIT);
  } catch (error) {
    openReadOnlyModal('Selection is too large', node('div', 'error', error.message), {
      id: 'alias-selection-limit', className: 'alias-editor-modal alias-confirm-modal'
    });
    return null;
  }
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
  (options?.streams || []).forEach((stream) => {
    const label = node('label', 'alias-check-option');
    const checkbox = node('input');
    checkbox.type = 'checkbox';
    checkbox.name = 'broadcastChannel';
    checkbox.value = stream.configuration_id;
    label.append(checkbox, node('span', '', stream.name || stream.configuration_id));
    fieldset.append(label);
  });
  if (!(options?.streams || []).length) fieldset.append(node('div', 'empty', 'No destinations configured'));
  const limitNotice = aliasOptionLimitNotice(options, 'streams', 'stream destinations',
    'Open an alias individually if its saved destination is not listed.');
  if (limitNotice) fieldset.append(limitNotice);
  return fieldset;
}

function aliasBulkBinaryOperation(ariaLabel, positiveDescription, negativeDescription) {
  const operation = node('div', 'alias-membership-operation');
  operation.setAttribute('role', 'group');
  operation.setAttribute('aria-label', ariaLabel);
  let selected = 'add';
  [['add', '+', positiveDescription], ['remove', '−', negativeDescription]]
    .forEach(([value, label, description]) => {
      const button = node('button', 'secondary', label);
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
  const ids = aliasMutationSelectionIds();
  if (!ids?.length) return;
  const form = node('form', 'alias-editor-form alias-bulk-form');
  form.append(node('p', 'modal-introduction',
    `This change applies only to the ${number(ids.length)} selected aliases.`));
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
        value: row.id,
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
        broadcast_configuration_ids: operation.value === 'clear' ? null : channels };
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
          }, timeoutMs: ALIAS_BULK_REQUEST_TIMEOUT_MS
        });
      } else {
        Object.assign(payload, change);
        result = await requestJson('/api/v1/admin/aliases/bulk', {
          method: 'POST', body: payload, timeoutMs: ALIAS_BULK_REQUEST_TIMEOUT_MS
        });
      }
      await finishAliasMutation(modal, result, kind === 'move' ? { list: payload.alias_list_id } : {});
    } catch (error) {
      aliasMutationError(errorHost, error, () => {
        modal.setDirty(false);
  closeReadOnlyModal(true);
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
  const ids = aliasMutationSelectionIds();
  if (!ids?.length) return;
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
        }, timeoutMs: ALIAS_BULK_REQUEST_TIMEOUT_MS
      });
      await finishAliasMutation(modal, result);
    } catch (error) {
      aliasMutationError(message, error, () => {
        modal.setDirty(false);
  closeReadOnlyModal(true);
        render();
      });
      remove.disabled = false;
    }
  });
  remove.focus();
}

function fullScanListMembershipRequest(revision, operation, aliasListId = null) {
  const id = Number(aliasListId);
  return {
    revision: Number(revision),
    operation,
    alias_scope: Number.isInteger(id) && id > 0 ? { alias_list_id: id } : {}
  };
}

function openFullScanListMembershipModal(scanList, operation) {
  const adding = operation === 'add';
  const form = node('form', 'alias-editor-form alias-full-membership-form');
  let aliasList = null;
  if (adding) {
    aliasList = aliasSelect('aliasListId', [
      { value: '', label: 'Choose an alias list' },
      ...(aliasEditorContext?.lists || []).map((row) => ({
        value: aliasListId(row),
        label: `${row.name} · ${aliasListFamilyLabel(row)} · ${aliasListCountLabel(row)}`
      }))
    ], '');
    form.append(node('p', 'modal-introduction',
      `Every alias in the chosen list will be added to ${scanList.name}. Existing memberships are left unchanged.`),
    aliasFormField('Alias list', aliasList));
  } else {
    form.append(node('p', '', `Remove all ${number(scanList.alias_count || 0)} alias memberships from ${scanList.name}?`),
      node('p', 'muted',
        'The aliases, their other scan-list memberships, and Alias List Defaults will be preserved.'));
  }
  const message = node('div', 'alias-form-message');
  message.setAttribute('role', 'alert');
  const cancel = node('button', 'button secondary', 'Cancel');
  cancel.type = 'button';
  const submit = node('button', adding ? 'button' : 'danger', adding ? 'Add All Aliases' : 'Remove All Members');
  submit.type = 'submit';
  form.append(message, aliasModalFooter(cancel, submit));
  const modal = openReadOnlyModal(`${adding ? 'Add All' : 'Remove All'} · ${scanList.name}`, form, {
    id: `${adding ? 'add-all-to' : 'remove-all-from'}-scan-list-${scanList.id}`,
    className: 'alias-editor-modal alias-confirm-modal',
    returnFocusSelector: adding ? '.scan-list-add-all' : '.scan-list-remove-all'
  });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const selectedAliasListId = adding ? Number(aliasList.value) : null;
    if (adding && (!Number.isInteger(selectedAliasListId) || selectedAliasListId <= 0)) {
      message.replaceChildren(node('div', 'error', 'Choose an alias list.'));
      return;
    }
    submit.disabled = true;
    message.textContent = adding ? 'Adding aliases…' : 'Removing memberships…';
    try {
      const result = await requestJson(`/api/v1/admin/scan-lists/${scanList.id}/members`, {
        method: 'PUT', body: fullScanListMembershipRequest(aliasEditorContext?.revision ?? 0, operation,
          selectedAliasListId)
      });
      await finishAliasMutation(modal, result);
    } catch (error) {
      aliasMutationError(message, error, () => {
        modal.setDirty(false);
        closeReadOnlyModal(true);
        render();
      });
      submit.disabled = false;
    }
  });
  if (adding) aliasList.focus();
  else submit.focus();
}

function observedGroupIdentityDiscoverySupported(selectedList) {
  return ['P25', 'DMR', 'NXDN'].includes(aliasListFamily(selectedList));
}

function unmatchedTalkgroupsSupported(selectedList) {
  return ['P25', 'DMR', 'NXDN', 'NBFM'].includes(aliasListFamily(selectedList));
}

function aliasTransferListDefaults(selectedList, options = {}) {
  const policy = selectedList?.unmatched_talkgroup_policy ||
    options?.alias_list?.unmatched_talkgroup_policy || {};
  const names = (ids, choices, id) => {
    const configured = new Map((choices || []).map((choice) => [String(choice?.[id] ?? ''), choice?.name]));
    return (ids || []).map((value) => configured.get(String(value)) || `Missing (${value})`);
  };
  return {
    recordable: Boolean(policy.recordable),
    scanLists: names(policy.scan_list_ids, options.scan_lists, 'id'),
    streams: names(policy.broadcast_configuration_ids, options.streams, 'configuration_id')
  };
}

function aliasTransferListDefaultsSummary(defaults) {
  const list = (values) => values?.length ? values.join(', ') : 'None';
  return `Recording: ${defaults?.recordable ? 'On' : 'Off'} · Scan lists: ${list(defaults?.scanLists)} · ` +
    `Streaming: ${list(defaults?.streams)}`;
}

function aliasTransferAssignmentNames(enabled, selectedNames = [], exactNames = []) {
  if (!enabled) return null;
  return [...new Set([...selectedNames, ...exactNames].filter((name) => typeof name === 'string' && name))];
}

function aliasTransferDetectedFormat(csv) {
  const header = String(csv || '').replace(/^\uFEFF/, '').split(/\r?\n/, 1)[0].trim();
  if (header === 'Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category') return 'RADIOREFERENCE';
  if (header.startsWith('format_version,alias_list,name,description,group,') ||
      header.startsWith('format_version,name,description,group,')) return 'VCE';
  return '';
}

function openAliasTransferModal(selectedList, action = 'Import') {
  const importing = action === 'Import';
  const listId = aliasListId(selectedList);
  const endpoint = `/api/v1/admin/alias-lists/${listId}/transfer`;
  const options = aliasEditorContext?.options || {};
  const body = node('div', 'alias-editor-form alias-transfer');
  const destinationSummary = node('div', 'alias-transfer-destination');
  destinationSummary.append(
    node('strong', '', `${selectedList.name} · ${aliasListFamilyLabel(selectedList)} Alias List`),
    node('span', 'muted', importing ?
      'Only aliases in this list can change. History, counters, and Alias List Defaults are preserved.' :
      `${number(selectedList.alias_count || 0)} aliases will be saved in an importable VCE file.`));
  body.append(destinationSummary);
  const importPanel = node('div', 'alias-transfer-panel');
  const exportPanel = node('div', 'alias-transfer-panel');
  body.append(importing ? importPanel : exportPanel);
  const form = node('form', 'alias-editor-form');
  importPanel.append(form);
  const field = (title, control) => {
    const label = node('label', 'alias-form-field');
    control.setAttribute('aria-label', title);
    label.append(node('span', '', title), control);
    return label;
  };
  const format = node('select');
  [['VCE', 'VCE alias configuration CSV'], ['RADIOREFERENCE', 'RadioReference talkgroup CSV']].forEach(([value, text]) => {
    const option = node('option', '', text); option.value = value; format.append(option);
  });
  const formatField = field('File type', format);
  formatField.hidden = true;
  const mode = node('select');
  mode.hidden = true;
  [['UPDATE_ADD', 'Add new aliases and update matches'], ['REPLACE', 'Replace this list’s aliases']].forEach(([value, text]) => {
    const option = node('option', '', text); option.value = value; mode.append(option);
  });
  const file = node('input'); file.type = 'file'; file.accept = '.csv,text/csv'; file.required = true;
  file.className = 'visually-hidden';
  const fileDrop = node('label', 'alias-transfer-file-drop');
  const filePrompt = node('strong', '', 'Drop a CSV file here');
  const fileAction = node('span', 'button secondary', 'Choose CSV file');
  const fileStatus = node('span', 'muted', 'VCE alias exports and RadioReference talkgroup CSV files are supported.');
  fileDrop.append(file, filePrompt, node('span', 'muted', 'or'), fileAction, fileStatus);
  const sourceSection = node('section', 'alias-transfer-section');
  sourceSection.append(node('h3', '', 'Choose a source file'), fileDrop, formatField);
  const help = node('details');
  help.append(node('summary', '', 'CSV format help'), node('p', '',
    'VCE alias export: created with Export aliases. It preserves matchers, appearance, recording, scan-list ' +
    'memberships, and streaming settings.'), node('p', '',
    'RadioReference header: Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category. ' +
    'Existing aliases update name, description, and group only. New fully encrypted talkgroups have recording, ' +
    'scan-list playback, and streaming disabled. Download talkgroups for a system compatible with this list.'));
  sourceSection.append(help);
  const behaviorSection = node('section', 'alias-transfer-section');
  behaviorSection.hidden = true;
  const modeChoices = node('fieldset', 'alias-transfer-mode-choices');
  modeChoices.append(node('legend', 'visually-hidden', 'Import behavior'));
  [['UPDATE_ADD', 'Add new aliases and update matches',
    'Existing aliases that are not in the file will be kept.', 'Recommended'],
   ['REPLACE', 'Replace this list’s aliases',
    'Aliases that are not in the file will be removed after you review the changes.', '']]
    .forEach(([value, title, description, badgeText]) => {
      const input = node('input'); input.type = 'radio'; input.name = 'aliasTransferMode'; input.value = value;
      input.checked = value === 'UPDATE_ADD';
      input.addEventListener('change', () => { if (input.checked) mode.value = value; });
      const copy = node('span', 'alias-transfer-choice-copy');
      const heading = node('strong', '', title);
      if (badgeText) heading.append(document.createTextNode(' '), badge(badgeText, 'state-current'));
      copy.append(heading, node('span', 'muted', description));
      const choice = node('label', 'alias-transfer-mode-choice');
      choice.append(input, copy);
      modeChoices.append(choice);
    });
  behaviorSection.append(node('h3', '', 'Choose import behavior'), mode, modeChoices);
  const progress = node('ol', 'alias-transfer-progress');
  ['Choose file', 'Import options', 'Review'].forEach((label, index) => {
    const step = node('li');
    step.append(node('span', '', String(index + 1)), document.createTextNode(label));
    step.dataset.step = String(index + 1);
    progress.append(step);
  });
  form.append(progress, sourceSection, behaviorSection);

  const vceAssignments = node('p', 'muted alias-transfer-source-settings',
    'New aliases will use the recording, scan-list, streaming, and appearance settings stored in the VCE file.');
  behaviorSection.append(vceAssignments);
  const listDefaults = aliasTransferListDefaults(selectedList, options);
  const radioDefaults = node('section', 'alias-transfer-radio-defaults');
  radioDefaults.append(node('h4', '', 'New RadioReference aliases'), node('p', '',
    'The RadioReference file has no local routing fields, so new aliases use this list’s current defaults:'),
    node('p', 'alias-transfer-default-summary', aliasTransferListDefaultsSummary(listDefaults)));
  const overrides = node('details', 'alias-transfer-overrides');
  overrides.append(node('summary', '', 'Optional: override defaults for this import'));
  const record = node('select');
  [['', `Use list default (${listDefaults.recordable ? 'Record calls' : 'Do not record'})`],
    ['true', 'Record calls'], ['false', 'Do not record']].forEach(([value, text]) => {
    const option = node('option', '', text); option.value = value; record.append(option);
  });
  overrides.append(field('Recording', record));
  let busy = false;
  const assignmentOverride = (title, choices, defaultNames, id, allowExactNames = false) => {
    const wrapper = node('fieldset', 'alias-stream-options alias-transfer-assignment-override');
    const enabled = node('input'); enabled.type = 'checkbox';
    wrapper.append(node('legend', '', title), aliasCheckOption(`Override ${title.toLowerCase()}`, enabled));
    const choicesHost = node('div', 'alias-transfer-assignment-choices');
    const exactNames = new Set();
    const nameCounts = new Map();
    (choices || []).forEach((choice) => nameCounts.set(choice.name, (nameCounts.get(choice.name) || 0) + 1));
    (choices || []).forEach((choice) => {
      const checkbox = node('input'); checkbox.type = 'checkbox'; checkbox.value = choice.name;
      checkbox.name = `aliasTransfer${id}`;
      checkbox.checked = defaultNames.includes(choice.name);
      const ambiguous = nameCounts.get(choice.name) > 1;
      if (ambiguous) checkbox.disabled = true;
      choicesHost.append(aliasCheckOption(ambiguous ? `${choice.name} (duplicate name; rename first)` : choice.name,
        checkbox));
    });
    if (!choices?.length) choicesHost.append(node('div', 'empty', `No ${title.toLowerCase()} configured`));
    let exactInput = null;
    let exactAdd = null;
    if (allowExactNames) {
      const exact = node('details', 'alias-transfer-exact-name');
      exact.append(node('summary', '', 'Add an exact configured name not shown'));
      exactInput = node('input'); exactInput.type = 'text'; exactInput.placeholder = 'Exact configured name';
      exactInput.setAttribute('aria-label', `Exact ${title.toLowerCase()} name`);
      exactAdd = node('button', 'button secondary', 'Add'); exactAdd.type = 'button';
      const controls = node('div', 'alias-transfer-exact-controls');
      const selected = node('div', 'alias-transfer-names');
      const drawExactNames = () => {
        selected.replaceChildren();
        exactNames.forEach((name) => {
          const remove = node('button', 'button secondary', `${name} ×`); remove.type = 'button';
          remove.setAttribute('aria-label', `Remove ${name}`);
          remove.addEventListener('click', () => { exactNames.delete(name); drawExactNames(); invalidate(); });
          selected.append(remove);
        });
      };
      exactAdd.addEventListener('click', () => {
        if (!exactInput.value) return;
        exactNames.add(exactInput.value);
        exactInput.value = '';
        drawExactNames();
        invalidate();
      });
      exactInput.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') { event.preventDefault(); exactAdd.click(); }
      });
      controls.append(exactInput, exactAdd);
      exact.append(node('p', 'muted',
        'Up to 500 destinations are listed. Exact names are validated when the preview is built.'),
      controls, selected);
      choicesHost.append(exact);
    }
    wrapper.append(choicesHost);
    const sync = () => {
      choicesHost.hidden = !enabled.checked;
      choicesHost.querySelectorAll('input[type="checkbox"]').forEach((control) => {
        control.disabled = busy || !enabled.checked || nameCounts.get(control.value) > 1;
      });
      if (exactInput) exactInput.disabled = busy || !enabled.checked;
      if (exactAdd) exactAdd.disabled = busy || !enabled.checked;
    };
    enabled.addEventListener('change', sync);
    sync();
    overrides.append(wrapper);
    return {
      sync,
      value: () => {
        if (exactInput?.value) throw new Error(`Click Add for the typed ${title.toLowerCase()} name first.`);
        return aliasTransferAssignmentNames(enabled.checked,
          [...choicesHost.querySelectorAll('input[type="checkbox"]:checked')].map((item) => item.value), exactNames);
      }
    };
  };
  const scans = assignmentOverride('Scan lists', options.scan_lists || [], listDefaults.scanLists, 'ScanList');
  const streams = assignmentOverride('Streaming destinations', options.streams || [], listDefaults.streams,
    'Stream', options.streams_truncated === true);
  radioDefaults.append(overrides);
  behaviorSection.append(radioDefaults);
  const sourceCancel = node('button', 'button secondary', 'Cancel'); sourceCancel.type = 'button';
  const sourceContinue = node('button', 'button', 'Continue'); sourceContinue.type = 'button';
  sourceContinue.disabled = true;
  sourceSection.append(aliasModalFooter(sourceCancel, node('span', 'alias-modal-footer-spacer'), sourceContinue));
  const behaviorBack = node('button', 'button secondary', 'Back'); behaviorBack.type = 'button';
  const previewButton = node('button', 'button', 'Review import'); previewButton.type = 'submit';
  behaviorSection.append(aliasModalFooter(behaviorBack, node('span', 'alias-modal-footer-spacer'), previewButton));
  const errorHost = node('div', 'alias-form-message'); errorHost.setAttribute('role', 'status');
  const review = node('section', 'alias-transfer-review');
  review.hidden = true;
  review.append(node('h3', '', 'Review changes'));
  const destination = node('p', 'muted');
  const summary = node('div', 'alias-transfer-counts');
  const filters = node('div', 'alias-transfer-review-filters');
  filters.setAttribute('role', 'group');
  filters.setAttribute('aria-label', 'Filter reviewed aliases');
  const rowsHost = node('div');
  const pagerHost = node('div', 'toolbar');
  const confirm = node('input'); confirm.type = 'checkbox';
  const confirmLabel = aliasCheckOption(`Replace aliases in ${selectedList.name}, including the deletions shown above`, confirm);
  const reviewBack = node('button', 'button secondary', 'Back'); reviewBack.type = 'button';
  const apply = node('button', 'button', 'Import changes'); apply.type = 'button'; apply.disabled = true;
  review.append(progress.cloneNode(true), destination, summary, filters, rowsHost, pagerHost, confirmLabel,
    aliasModalFooter(reviewBack, node('span', 'alias-modal-footer-spacer'), apply));
  importPanel.append(review);
  const exportButton = node('button', 'button', 'Download alias list CSV');
  exportButton.type = 'button';
  const exportCancel = node('button', 'button secondary', 'Cancel');
  exportCancel.type = 'button';
  const exportSummary = node('div', 'alias-transfer-export-summary');
  exportSummary.append(
    node('h3', '', `Export ${selectedList.name}`),
    node('p', '', `Download all ${number(selectedList.alias_count || 0)} aliases in this list as an importable VCE CSV.`),
    node('p', 'muted', 'The file includes matchers, appearance, recording choices, scan-list memberships, ' +
      'named streaming destinations, and the source Alias List name.'),
    node('p', 'muted', 'Referenced scan lists, streaming destinations, and icons must exist on the receiving installation.'));
  exportPanel.append(exportSummary,
    aliasModalFooter(exportCancel, node('span', 'alias-modal-footer-spacer'), exportButton));
  body.insertBefore(errorHost, importing ? importPanel : exportPanel);
  let request = null;
  let preview = null;
  let selectedFileReady = false;
  let fileInspection = 0;
  let previewFilter = 'all';
  const modal = openReadOnlyModal(`${importing ? 'Import aliases into' : 'Export aliases from'} ${selectedList.name}`, body, {
    id: `alias-transfer-${listId}`, className: 'alias-editor-modal alias-transfer-modal',
    returnFocusSelector: importing ? '.alias-transfer-import-button' : '.alias-transfer-export-button'
  });
  if (!modal) return;
  const setStep = (step) => {
    form.hidden = step === 3;
    sourceSection.hidden = step !== 1;
    behaviorSection.hidden = step !== 2;
    review.hidden = step !== 3;
    body.querySelectorAll('.alias-transfer-progress li').forEach((item) => {
      const itemStep = Number(item.dataset.step);
      item.classList.toggle('active', itemStep === step);
      item.classList.toggle('complete', itemStep < step);
      item.setAttribute('aria-current', itemStep === step ? 'step' : 'false');
    });
  };
  const invalidate = () => {
    request = null; preview = null; apply.disabled = true; confirm.checked = false;
    destination.textContent = ''; summary.replaceChildren(); filters.replaceChildren();
    rowsHost.replaceChildren(); pagerHost.replaceChildren(); previewFilter = 'all';
    confirmLabel.hidden = mode.value !== 'REPLACE';
    const radioReference = format.value === 'RADIOREFERENCE';
    radioDefaults.hidden = !radioReference;
    vceAssignments.hidden = radioReference;
    modal.setDirty(Boolean(file.files?.length));
  };
  const updateApply = () => { apply.disabled = busy || !preview || preview.counts.error > 0 ||
    (request.mode === 'REPLACE' && preview.counts.deleted > 0 && !confirm.checked); };
  const setBusy = (value) => {
    busy = value; modal.setBusy(value);
    form.querySelectorAll('input,select,button').forEach((control) => { control.disabled = value; });
    exportButton.disabled = value;
    confirm.disabled = value;
    reviewBack.disabled = value;
    scans.sync(); streams.sync();
    pagerHost.querySelectorAll('button').forEach((button) => { button.disabled = value; });
    sourceContinue.disabled = value || !selectedFileReady;
    updateApply();
  };
  const inspectFile = async () => {
    const generation = ++fileInspection;
    const selected = file.files?.[0];
    selectedFileReady = false;
    sourceContinue.disabled = true;
    formatField.hidden = true;
    fileDrop.classList.remove('has-file');
    filePrompt.textContent = 'Drop a CSV file here';
    fileStatus.textContent = 'VCE alias exports and RadioReference talkgroup CSV files are supported.';
    invalidate();
    if (!selected) return;
    if (selected.size > 8 * 1024 * 1024) {
      errorHost.replaceChildren(node('div', 'error', 'Choose a CSV file no larger than 8 MiB.'));
      fileStatus.textContent = `${selected.name} · ${number(selected.size)} bytes`;
      return;
    }
    try {
      const csv = await selected.text();
      if (generation !== fileInspection) return;
      const detected = aliasTransferDetectedFormat(csv);
      if (detected) format.value = detected;
      formatField.hidden = Boolean(detected);
      selectedFileReady = true;
      sourceContinue.disabled = false;
      fileDrop.classList.add('has-file');
      filePrompt.textContent = selected.name;
      fileStatus.textContent = `${detected === 'RADIOREFERENCE' ? 'RadioReference talkgroup CSV' :
        detected === 'VCE' ? 'VCE alias export' : 'Choose the file type below'} · ${number(selected.size)} bytes`;
      invalidate();
    } catch (error) {
      errorHost.replaceChildren(node('div', 'error', `The selected file could not be read: ${error.message}`));
    }
  };
  file.addEventListener('change', () => void inspectFile());
  fileDrop.addEventListener('dragover', (event) => {
    event.preventDefault();
    fileDrop.classList.add('dragging');
  });
  fileDrop.addEventListener('dragleave', () => fileDrop.classList.remove('dragging'));
  fileDrop.addEventListener('drop', (event) => {
    event.preventDefault();
    fileDrop.classList.remove('dragging');
    if (!event.dataTransfer?.files?.length) return;
    try { file.files = event.dataTransfer.files; } catch (_) { return; }
    file.dispatchEvent(new Event('change', { bubbles: true }));
  });
  sourceCancel.addEventListener('click', modal.close);
  exportCancel.addEventListener('click', modal.close);
  sourceContinue.addEventListener('click', () => {
    errorHost.replaceChildren();
    if (!selectedFileReady) {
      errorHost.replaceChildren(node('div', 'error', 'Choose a supported CSV file to continue.'));
      return;
    }
    setStep(2);
  });
  behaviorBack.addEventListener('click', () => setStep(1));
  reviewBack.addEventListener('click', () => {
    invalidate();
    setStep(2);
  });
  exportButton.addEventListener('click', async () => {
    if (busy) return;
    setBusy(true); errorHost.replaceChildren();
    try {
      const response = await fetch(endpoint, {
        headers: { Accept: 'text/csv' }, cache: 'no-store', credentials: 'same-origin'
      });
      if (!response.ok) {
        const contentType = String(response.headers.get('Content-Type') || '').toLowerCase();
        const payload = contentType.includes('json') ? await response.json().catch(() => null) :
          { message: await response.text().catch(() => '') };
        const failure = payload?.error && typeof payload.error === 'object' ? payload.error : payload;
        throw new Error(failure?.message || `Export failed (${response.status}).`);
      }
      const downloadUrl = window.URL.createObjectURL(await response.blob());
      const download = node('a');
      download.href = downloadUrl;
      download.download = `vce-alias-list-${listId}.csv`;
      download.hidden = true;
      document.body.append(download);
      download.click();
      download.remove();
      window.setTimeout(() => window.URL.revokeObjectURL(downloadUrl), 0);
    } catch (error) {
      const duplicate = /one alias per exact matcher|duplicate matcher/i.test(error.message);
      errorHost.append(node('div', 'error', duplicate ?
        'This list contains duplicate exact matchers. Resolve the highlighted identifier conflicts, then export again.' :
        error.message));
    } finally {
      setBusy(false);
    }
  });
  const loadPreview = async (offset = 0) => {
    setBusy(true); errorHost.replaceChildren();
    try {
      const response = await requestJson(endpoint, { method: 'POST', timeoutMs: ALIAS_BULK_REQUEST_TIMEOUT_MS,
        body: { ...request, action: 'preview', offset } });
      preview = response; confirm.checked = false;
      destination.textContent = response.source_list ?
        `From ${response.source_list} into ${response.destination_list}` :
        `Importing into ${response.destination_list}`;
      const countLabels = { added: 'Added', updated: 'Updated', unchanged: 'Unchanged', deleted: 'Removed', error: 'Errors' };
      summary.replaceChildren(...Object.entries(countLabels).map(([key, label]) => {
        const card = node('div', `alias-transfer-count alias-transfer-count-${key}`);
        card.append(node('strong', '', number(response.counts[key] || 0)), node('span', '', label));
        return card;
      }));
      const drawRows = () => {
        rowsHost.replaceChildren();
        const visibleRows = response.rows.filter((row) => previewFilter === 'all' || row.result === previewFilter);
        visibleRows.forEach((row) => {
          const detail = node('details', `alias-transfer-row alias-transfer-row-${row.result}`);
          detail.append(node('summary', '', `${countLabels[row.result] || row.result} · ${row.name || '(unnamed)'}${row.row ? ` · row ${row.row}` : ''}`));
          if (row.error) detail.append(node('p', 'error', row.error));
          if (row.changes.length) detail.append(table(row.changes, [
            { id: 'field', label: 'Field', render: (change) => change.field.replaceAll('_', ' ') },
            { id: 'before', label: 'Current', render: (change) => change.before || '—' },
            { id: 'after', label: 'Proposed', render: (change) => change.after || '—' }
          ], '', { type: 'alias-import-changes', sortable: false }));
          rowsHost.append(detail);
        });
        if (!visibleRows.length) rowsHost.append(node('div', 'empty', 'No matching aliases on this review page.'));
        filters.querySelectorAll('button').forEach((button) =>
          button.setAttribute('aria-pressed', String(button.dataset.filter === previewFilter)));
      };
      filters.replaceChildren();
      [['all', 'All', response.total], ...Object.entries(countLabels).map(([key, label]) =>
        [key, label, response.counts[key] || 0])].forEach(([key, label, count]) => {
          const button = node('button', 'button secondary', `${label} ${number(count)}`);
          button.type = 'button'; button.dataset.filter = key;
          button.addEventListener('click', () => { previewFilter = key; drawRows(); });
          filters.append(button);
        });
      drawRows();
      const first = response.total ? offset + 1 : 0;
      pagerHost.replaceChildren(node('span', '', `${first}–${Math.min(offset + 100, response.total)} of ${response.total}`));
      for (const [label, next] of [['Previous', offset - 100], ['Next', offset + 100]]) {
        if(next >= 0 && next < response.total) {
          const button = node('button', 'button secondary', label); button.type = 'button';
          button.addEventListener('click', () => loadPreview(next)); pagerHost.append(button);
        }
      }
      confirmLabel.hidden = request.mode !== 'REPLACE' || response.counts.deleted === 0;
      const changed = response.counts.added + response.counts.updated + response.counts.deleted;
      apply.textContent = changed ? `Import ${number(changed)} change${changed === 1 ? '' : 's'}` : 'Finish import';
      setStep(3);
      updateApply();
    } catch(error) { preview = null; errorHost.append(node('div', 'error', error.message)); }
    finally { setBusy(false); }
  };
  const invalidateChangedOption = (event) => {
    if (event.target === file) return;
    errorHost.replaceChildren();
    invalidate();
  };
  form.addEventListener('input', invalidateChangedOption);
  form.addEventListener('change', invalidateChangedOption);
  confirm.addEventListener('change', updateApply);
  form.addEventListener('submit', async (event) => {
    event.preventDefault(); errorHost.replaceChildren();
    try {
      const selected = file.files?.[0];
      if(!selected || selected.size > 8 * 1024 * 1024) throw new Error('Select a CSV file no larger than 8 MiB.');
      setBusy(true);
      request = { format: format.value, mode: mode.value, csv: await selected.text(), defaults: format.value === 'RADIOREFERENCE' ? {
        recordable: record.value === '' ? null : record.value === 'true', scan_lists: scans.value(),
        streams: streams.value()
      } : null };
      await loadPreview();
    } catch(error) { errorHost.append(node('div', 'error', error.message)); setBusy(false); }
  });
  apply.addEventListener('click', async () => {
    if(apply.disabled) return;
    setBusy(true); errorHost.replaceChildren();
    try {
      const result = await requestJson(endpoint, { method: 'POST', timeoutMs: ALIAS_BULK_REQUEST_TIMEOUT_MS,
        body: { ...request, action: 'apply', revision: preview.revision, digest: preview.digest,
          confirm_replace: confirm.checked } });
      modal.setBusy(false); modal.setDirty(false);
      const counts = preview.counts;
      await finishAliasMutation(modal, result);
      openReadOnlyModal('Alias import complete', node('p', '',
        `${counts.added} added · ${counts.updated} updated · ${counts.deleted} deleted · ${counts.unchanged} unchanged`));
    } catch(error) {
      preview = null; errorHost.append(node('div', 'error', `${error.message}. Preview again before retrying.`));
      setBusy(false);
    }
  });
  invalidate();
  setStep(1);
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
  const selectedStreams = new Set(policy.broadcast_configuration_ids || []);
  const configuredStreams = new Map((options.streams || []).map((entry) =>
    [entry.configuration_id, entry.name || entry.configuration_id]));
  const streamIds = [...new Set([...configuredStreams.keys(), ...selectedStreams])];
  if (!streamIds.length) streams.append(node('div', 'empty', 'No stream destinations configured'));
  streamIds.forEach((configurationId) => {
    const label = node('label', 'alias-check-option');
    const checkbox = node('input');
    checkbox.type = 'checkbox';
    checkbox.name = 'broadcastChannel';
    checkbox.value = configurationId;
    checkbox.checked = selectedStreams.has(configurationId);
    const missing = !configuredStreams.has(configurationId);
    if (missing) label.classList.add('missing');
    label.append(checkbox, node('span', '', missing ? `Missing destination (${configurationId})` :
      configuredStreams.get(configurationId)));
    streams.append(label);
  });
  const streamLimitNotice = aliasOptionLimitNotice(options, 'streams', 'stream destinations',
    'Destinations already saved in these defaults remain visible.');
  if (streamLimitNotice) streams.append(streamLimitNotice);

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
          broadcast_configuration_ids: [...streams.querySelectorAll('[name="broadcastChannel"]:checked')]
            .map((checkbox) => checkbox.value),
          scan_list_ids: selectedAliasScanListIds(form)
        }
      });
      await finishAliasMutation(modal, result);
    } catch (error) {
      aliasMutationError(errorHost, error, () => {
        modal.setDirty(false);
  closeReadOnlyModal(true);
        render();
      });
      save.disabled = false;
    }
  });
}

function observedGroupIdentityProtocol(row) {
  const value = String(row?.protocol || '').trim().toLowerCase();
  return ['am', 'p25', 'dmr', 'nxdn', 'nbfm', 'fleetsync', 'mdc1200'].includes(value) ? value : '';
}

function observedGroupIdentityVariant(row) {
  if (observedGroupIdentityProtocol(row) !== 'p25') return null;
  return ['phase_1', 'phase_2'].includes(row?.protocol_variant) ? row.protocol_variant : 'phase_1';
}

function observedGroupIdentityMatchKind(row) {
  const kind = String(row?.match_kind || 'none').trim().toLowerCase();
  return ['exact', 'range'].includes(kind) ? kind : 'none';
}

function observedGroupIdentityPromotionSupported(row) {
  return row?.promotion_supported === true || row?.promotion_supported === 1 ||
    String(row?.promotion_supported).toLowerCase() === 'true';
}

function observedGroupIdentityPromotionReason(row) {
  return String(row?.promotion_reason ||
    'This observation does not contain enough identity information to create a safe alias.');
}

function observedGroupIdentityValue(row) {
  const wrapper = node('div', 'observed-group-identity-value');
  wrapper.append(node('strong', '', identityNumber(row, row.group_identity_id)));
  if (observedGroupIdentityMatchKind(row) === 'range') {
    wrapper.append(node('small', '', `Covered by range · ${row.matched_alias_name ||
      `Alias ${identifierNumber(row.matched_alias_id)}`}`));
  }
  return wrapper;
}

function observedGroupIdentitySystem(row) {
  const wrapper = node('div', 'observed-group-identity-system');
  const label = row.channel_names || row.system_name || row.radio_system_key || 'Unknown source';
  const details = [protocolFamily(row), row.topology,
    row.system_name && row.system_name !== label ? row.system_name : null]
    .filter(Boolean).join(' · ');
  wrapper.append(node('strong', '', label));
  if (details) wrapper.append(node('small', '', details));
  return wrapper;
}

function observedGroupIdentityMatch(row) {
  const kind = observedGroupIdentityMatchKind(row);
  if (kind === 'none') return badge('No match', 'state-stale', 'No exact alias or covering range exists');
  const wrapper = node('div', 'observed-group-identity-match');
  wrapper.append(node('strong', '', row.matched_alias_name || `Alias ${identifierNumber(row.matched_alias_id)}`),
    node('small', '', kind === 'range' ? 'Covered by range' : 'Exact alias'));
  return wrapper;
}

function observedGroupIdentityTime(row, value) {
  const rendered = dateTime(value);
  if (value !== null && value !== undefined &&
      String(row?.topology || '').toUpperCase() === 'CONVENTIONAL' &&
      ['P25', 'NXDN'].includes(protocolFamily(row))) {
    const wrapper = node('span', 'observed-group-identity-time');
    wrapper.append(rendered, ' (hour beginning)');
    return wrapper;
  }
  return rendered;
}

function observedGroupIdentityKey(row) {
  const topology = String(row?.topology || 'UNKNOWN').trim().toUpperCase() || 'UNKNOWN';
  const protocol = observedGroupIdentityProtocol(row) || 'UNKNOWN';
  let source = 'source:unknown';
  if (row?.radio_system_key) source = `radio-system:${row.radio_system_key}`;
  else if (row?.configuration_id) source = `channel:${row.configuration_id}`;
  const canonical = String(row?.identity_key || '').trim() || `native:${row?.native_id ?? 'x'}`;
  const carrier = topology === 'CONVENTIONAL' && protocol === 'dmr' ?
    `|frequency:${row?.frequency_hz ?? 'x'}|timeslot:${row?.timeslot ?? 'x'}` : '';
  return `${topology}|${protocol}|${source}|${rowGroupIdentityKind(row)}|${canonical}|local:${
    row.group_identity_id ?? 'x'}${carrier}`;
}

function observedGroupIdentityFocusKey(row) {
  return encodeURIComponent(observedGroupIdentityKey(row));
}

function observedGroupIdentityPrefill(row, selectedList) {
  if (!observedGroupIdentityPromotionSupported(row)) {
    throw new Error(observedGroupIdentityPromotionReason(row));
  }
  const policy = selectedList?.unmatched_talkgroup_policy || {};
  const groupIdentityId = Number(row.group_identity_id);
  let matcher;
  if (isP25(row)) {
    if (String(row?.topology || '').toUpperCase() === 'CONVENTIONAL') {
      throw new Error('Conventional P25 observations cannot be promoted until canonical identity evidence is retained.');
    }
    if (Number.isInteger(groupIdentityId) &&
        groupIdentityId > 0 && groupIdentityId < 0xFFFF) {
      matcher = { type: 'talkgroup', protocol: observedGroupIdentityProtocol(row),
        variant: observedGroupIdentityVariant(row), value: groupIdentityId };
    } else {
      throw new Error('This P25 observation does not have a usable local talkgroup ID.');
    }
  } else {
    matcher = { type: 'talkgroup', protocol: observedGroupIdentityProtocol(row), value: groupIdentityId };
  }
  return {
    alias_list_id: aliasListId(selectedList),
    name: '',
    description: '',
    group: '',
    color: 0,
    icon_name: null,
    recordable: Boolean(policy.recordable),
    broadcast_configuration_ids: [...(policy.broadcast_configuration_ids || [])],
    scan_list_ids: [...(policy.scan_list_ids || [])],
    stream_as_talkgroup: null,
    matcher,
    returnFocusSelector: `.observed-group-identity-create[data-observed-key="${observedGroupIdentityFocusKey(row)}"]`
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
    broadcast_configuration_ids: [...(policy.broadcast_configuration_ids || [])],
    scan_list_ids: [...(policy.scan_list_ids || [])],
    stream_as_talkgroup: null,
    matcher: { type, protocol, ...(variant ? { variant } : {}), value }
  };
}

function openObservedGroupIdentityAliasEditor(row, selectedList) {
  if (observedGroupIdentityMatchKind(row) === 'exact' || !observedGroupIdentityPromotionSupported(row)) return;
  openAliasEditorModal('create', null, observedGroupIdentityPrefill(row, selectedList));
}

function observedGroupIdentityCreateButton(row, selectedList) {
  if (!aliasAdminAllowed() || observedGroupIdentityMatchKind(row) === 'exact') return '—';
  if (!observedGroupIdentityPromotionSupported(row)) {
    return badge('Review only', 'state-stale', observedGroupIdentityPromotionReason(row));
  }
  const button = node('button', 'button secondary observed-group-identity-create', 'Create Alias');
  button.type = 'button';
  button.dataset.groupIdentityId = String(row.group_identity_id);
  button.dataset.observedKey = observedGroupIdentityFocusKey(row);
  button.addEventListener('click', () => openObservedGroupIdentityAliasEditor(row, selectedList));
  return button;
}

function observedGroupIdentityDetail(row, selectedList) {
  const wrapper = node('div', 'observed-group-identity-detail');
  const identityColumn = node('div', 'observed-group-identity-detail-column');
  const activityColumn = node('div', 'observed-group-identity-detail-column');
  const localP25 = isP25(row) && String(row?.topology || '').toUpperCase() === 'TRUNKED';
  const identity = [
    [localP25 ? 'Local Talkgroup' : groupIdentityLabel(row, null, false),
      identityNumber(row, row.group_identity_id)],
    ['Protocol', protocolFamily(row) || row.protocol],
    ['System', row.system_name || '—'],
    ['Channel', row.channel_names || '—'],
    ['Topology', row.topology || '—'],
    ['WACN', row.wacn === null || row.wacn === undefined ? '—' : hexDecimalPair(row.wacn, 5)],
    ['System ID', row.system_id === null || row.system_id === undefined ? '—' : hexDecimalPair(row.system_id, 3)],
    ['Network ID', row.network_id === null || row.network_id === undefined ? '—' : identifierNumber(row.network_id)],
    ['Frequency', row.frequency_hz === null || row.frequency_hz === undefined ? '—' :
      frequency(row.frequency_hz)],
    ['Timeslot', row.timeslot === null || row.timeslot === undefined ? '—' :
      identifierNumber(row.timeslot)]
  ];
  identityColumn.append(section('Identity', keyValues(identity)));
  const coverage = [['Match', observedGroupIdentityMatch(row)]];
  if (!observedGroupIdentityPromotionSupported(row)) {
    coverage.push(['Promotion', badge('Review only', 'state-stale', observedGroupIdentityPromotionReason(row))],
      ['Reason', observedGroupIdentityPromotionReason(row)]);
  }
  activityColumn.append(section('Alias Coverage', keyValues(coverage)));
  identityColumn.append(section('Calls', aliasDetailMetricBand(row, [
    ['Logical Calls', 'logical_call_count'], ['Recorded', 'recorded_logical_call_count'],
    ['Submitted', 'stream_submitted_logical_call_count'], ['Encrypted', 'encrypted_logical_call_count']
  ])));
  activityColumn.append(section('Signaling', fragment(
    aliasDetailMetricBand(row, [
      ['Total', 'signaling_observation_count'],
      ['Grants', 'grant_observation_count'], ['Join', 'join_observation_count'],
      ['Emergency', 'emergency_observation_count'], ['Register', 'register_observation_count'],
      ['Logout', 'logout_observation_count'], ['Denial', 'denial_observation_count'],
      ['Data', 'data_observation_count']
    ]),
    node('p', 'metric-meaning-note',
      'The total also includes other recognized signaling actions. Calls and signaling can overlap.'))));
  identityColumn.append(section('Observed', keyValues([
    ['First Activity', observedGroupIdentityTime(row, row.first_seen_ms)],
    ['Latest Activity', observedGroupIdentityTime(row, row.last_seen_ms)]
  ])));
  wrapper.append(identityColumn, activityColumn);
  if (aliasAdminAllowed() && observedGroupIdentityMatchKind(row) !== 'exact' &&
      observedGroupIdentityPromotionSupported(row)) {
    const actions = node('div', 'observed-group-identity-detail-actions');
    actions.append(observedGroupIdentityCreateButton(row, selectedList));
    wrapper.append(actions);
  }
  return wrapper;
}

function openObservedGroupIdentityDetail(row, selectedList) {
  const id = identityNumber(row, row.group_identity_id);
  openReadOnlyModal(`Observed ${groupIdentityLabel(row, null, false)} ${id}`,
    observedGroupIdentityDetail(row, selectedList), {
    id: `observed-group-identity-${id}`, className: 'alias-editor-modal observed-group-identity-modal'
  });
}

function observedGroupIdentityToolbar(selectedList) {
  const form = node('form', 'toolbar alias-catalog-toolbar observed-group-identity-toolbar');
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
  input.placeholder = 'Group, system, or channel';
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

function renderObservedGroupIdentities(main, page, selectedList) {
  const rows = (page.rows || []).filter((row) => observedGroupIdentityMatchKind(row) !== 'exact');
  const columns = [
    { id: 'group-identity-id', label: 'Identity', fullLabel: 'Group identity', sort: 'group_identity',
      render: observedGroupIdentityValue },
    { id: 'source', label: 'System / Channel', sort: 'system', className: 'alias-cell',
      render: observedGroupIdentitySystem },
    { id: 'calls', label: 'Calls', sort: 'logical_call_count', className: 'numeric',
      render: (row) => aliasMetricValue(row, 'logical_call_count') },
    { id: 'signaling', label: 'Signaling', className: 'numeric',
      render: (row) => aliasMetricValue(row, 'signaling_observation_count') },
    { id: 'last-seen', label: 'Last Seen', sort: 'last_seen',
      render: (row) => observedGroupIdentityTime(row, row.last_seen_ms) },
    { id: 'action', label: '', render: (row) => observedGroupIdentityCreateButton(row, selectedList) }
  ];
  const host = node('div', 'alias-catalog-table-host observed-group-identity-table-host');
  const actions = node('div', 'section-title-actions');
  const observedTable = table(rows, columns,
    'No observed groups without an exact alias are available for this list', {
      type: 'alias-observed-group-identities', serverSort: true, sortable: false,
      defaultSort: 'last_seen', defaultDirection: 'desc',
      rowKey: observedGroupIdentityKey, rowClass: 'observed-group-identity-row',
      onRowClick: (row) => openObservedGroupIdentityDetail(row, selectedList), layoutMenuHost: actions
    });
  host.append(observedTable);
  const block = section('Observed Groups', host, actions);
  block.classList.add('alias-catalog-section', 'alias-editor-table-section', 'observed-group-identity-section');
  block.append(node('p', 'metric-meaning-note alias-catalog-guide',
    'This list contains talkgroups and patch groups observed on assigned systems or channels that do not have an ' +
    'exact alias. Existing range coverage appears beneath the identity.'), pager({ ...page, rows }));
  main.append(block);
}

async function renderScanListMembers(main, listResponse, scanListCatalog, scanList, renderContext) {
  const filters = {
    type: route.get('type'), matcher: route.get('matcher'), group: route.get('group'),
    scan_list_id: scanList.id, record: route.get('record'), stream: route.get('stream'),
    evidence: route.get('evidence'), use: route.get('use'),
    last_activity_before: route.get('lastActivityBefore'), last_activity_after: route.get('lastActivityAfter')
  };
  const page = await apiPage('/api/v1/aliases', pageParameters({ ...filters, include_activity: false }));
  if (!renderIsCurrent(renderContext) || !main.isConnected) return;
  const options = {
    scan_lists: scanListCatalog.scan_lists || [], scan_list_scope: true
  };
  aliasEditorContext.page = page;
  aliasEditorContext.options = options;
  aliasEditorContext.revision = Number(scanListCatalog.revision ?? 0);
  const rows = page.rows || [];
  const selectionFilters = { ...filters, q: route.get('q') };
  const selectionScope = aliasSelectionScopeKey('scan-list-members', selectionFilters);
  synchronizeAliasEditorSelectionScope(selectionScope);

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
  const addAll = node('button', 'button secondary scan-list-add-all', 'Add All from Alias List');
  addAll.type = 'button';
  addAll.disabled = !(aliasEditorContext?.lists || []).length;
  addAll.addEventListener('click', () => openFullScanListMembershipModal(scanList, 'add'));
  const removeAll = node('button', 'button secondary danger-outline scan-list-remove-all', 'Remove All Members');
  removeAll.type = 'button';
  removeAll.disabled = Number(scanList.alias_count || 0) <= 0;
  removeAll.addEventListener('click', () => openFullScanListMembershipModal(scanList, 'remove'));
  summaryActions.append(addAll, removeAll);
  summary.append(summaryCopy, summaryActions);
  main.append(summary, aliasEditorFilterToolbar(listResponse, options));

  const tableHost = node('div', 'alias-catalog-table-host alias-editor-table-host');
  const selectionStatus = node('div', 'alias-form-message alias-selection-status');
  selectionStatus.setAttribute('role', 'status');
  selectionStatus.setAttribute('aria-live', 'polite');
  let bulkBar = null;
  const updateSelection = (message = '', error = false, preserveRequest = false) => {
    if (!preserveRequest) aliasEditorSelectionRequest += 1;
    tableHost.querySelectorAll('.alias-row-select').forEach((checkbox) => {
      const tableRow = checkbox.closest('tr');
      const id = Number(tableRow?.dataset.id);
      checkbox.checked = aliasEditorSelection.has(id);
      tableRow?.classList.toggle('selected', checkbox.checked);
    });
    bulkBar?.update();
    selectionStatus.replaceChildren();
    if (message) selectionStatus.append(node(error ? 'div' : 'span', error ? 'error' : 'muted', message));
  };
  const actions = node('div', 'section-title-actions');
  const aliasTable = table(rows, scanListMemberColumns(rows, updateSelection),
    'No aliases belong to this scan list', {
      type: 'alias-scan-list-members', serverSort: true, sortable: false,
      defaultSort: 'name', defaultDirection: 'asc', rowKey: (row) => row.alias_id,
      layoutMenuHost: actions,
      onRowClick: (row, _tableRow, event) => {
        const id = Number(row.alias_id);
        if (event.shiftKey || event.metaKey || event.ctrlKey) {
          const index = rows.indexOf(row);
          try {
            if (event.shiftKey && aliasEditorLastSelectionIndex !== null) {
              const start = Math.min(index, aliasEditorLastSelectionIndex);
              const end = Math.max(index, aliasEditorLastSelectionIndex);
              aliasEditorSelection = extendedAliasSelection(aliasEditorSelection,
                rows.slice(start, end + 1).map((candidate) => Number(candidate.alias_id)));
            } else if (aliasEditorSelection.has(id)) aliasEditorSelection.delete(id);
            else aliasEditorSelection = extendedAliasSelection(aliasEditorSelection, [id]);
            aliasEditorLastSelectionIndex = index;
            updateSelection();
          } catch (error) {
            updateSelection(error.message, true);
          }
          return;
        }
        window.location.assign(aliasEditorRowHref(row));
      }
  });
  tableHost.append(aliasTable);

  const selectPage = node('button', 'button secondary', 'Select This Page');
  selectPage.type = 'button';
  selectPage.addEventListener('click', () => {
    try {
      aliasEditorSelection = extendedAliasSelection(aliasEditorSelection,
        rows.map((row) => Number(row.alias_id)));
      updateSelection();
    } catch (error) {
      updateSelection(error.message, true);
    }
  });
  const selectAll = node('button', 'button secondary alias-select-all', 'Select All Matching');
  selectAll.type = 'button';
  selectAll.addEventListener('click', () =>
    selectAllMatchingAliases(selectionFilters, selectionScope, selectAll, updateSelection));
  actions.append(selectPage, selectAll);
  const exportContext = { scan_list_id: scanList.id };
  new Map([
    ['type', 'type'], ['matcher', 'matcher'], ['group', 'group'], ['record', 'record'],
    ['stream', 'stream'], ['evidence', 'evidence'], ['use', 'use'],
    ['lastActivityBefore', 'last_activity_before'], ['lastActivityAfter', 'last_activity_after']
  ]).forEach((queryKey, routeKey) => {
    if (route.get(routeKey)) exportContext[queryKey] = route.get(routeKey);
  });
  actions.append(exportCsvLink('aliases', exportContext, 'Download table report'));
  const block = section(`Aliases in ${scanList.name}`, tableHost, actions);
  block.classList.add('alias-catalog-section', 'alias-editor-table-section', 'scan-list-member-table-section');
  bulkBar = scanListMemberBulkBar(scanList, () => {
    resetAliasEditorSelection(selectionScope);
    updateSelection();
  });
  block.append(bulkBar, selectionStatus);
  updateSelection();
  block.append(node('p', 'metric-meaning-note alias-catalog-guide',
    'This view includes members from every alias list. Removing membership preserves each alias and its other ' +
      'scan-list memberships.'), pager(page));
  main.append(block);
}

async function renderAliases() {
  const renderContext = captureRenderContext();
  const requestedListId = /^[1-9][0-9]*$/.test(route.get('list') || '') ? Number(route.get('list')) : null;
  const requestedScanListId = !route.get('list') && /^[1-9][0-9]*$/.test(route.get('scanListId') || '') ?
    Number(route.get('scanListId')) : null;
  const requestedTable = route.get('aliasTab') !== 'discover' &&
    (requestedListId !== null || requestedScanListId !== null);
  clearInactiveAliasSelection(aliasAdminAllowed() && requestedTable);
  if (!aliasAdminAllowed()) throw Object.assign(new Error('Administrator access is required.'), { status: 403 });
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
  if (route.get('createAlias') === '1' && route.has('createListId')) {
    const routedListId = Number(route.get('createListId'));
    const requestedList = Number.isInteger(routedListId) && routedListId > 0 ?
      lists.find((row) => aliasListId(row) === routedListId) : null;
    if (requestedList) {
      selectedList = requestedList;
      route.set('list', String(aliasListId(selectedList)));
      route.delete('createListId');
      window.history.replaceState({}, '', currentHref());
    } else selectedList = null;
  }
  const scanListScope = requestedScanListId ?
    (scanListCatalog.scan_lists || []).find((row) => Number(row.id) === requestedScanListId) :
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
    clearInactiveAliasSelection(false);
    const missing = node('section', 'alias-editor-welcome');
    missing.append(node('h2', '', 'Scan list not found'),
      node('p', '', 'This scan list may have been deleted or changed.'),
      anchor('Back to Scan Lists', href('configuration', { tab: 'scan-lists' }), 'button secondary'));
    main.append(missing);
    return;
  }

  if (!selectedList) {
    clearInactiveAliasSelection(false);
    main.append(aliasEditorEmptyState(lists));
    return;
  }

  const view = aliasEditorView(selectedList);
  const defaultOrder = aliasEditorDefaultOrder(view);
  const filters = {
    list: aliasListId(selectedList), type: route.get('type'), matcher: route.get('matcher'),
    group: route.get('group'), scan_list_id: route.get('scanListId'), record: route.get('record'),
    stream: route.get('stream'), evidence: route.get('evidence'), use: route.get('use'),
    last_activity_before: route.get('lastActivityBefore'), last_activity_after: route.get('lastActivityAfter')
  };
  const pagePromise = view === 'discover' ?
    apiPage(`/api/v1/alias-lists/${aliasListId(selectedList)}/observed-group-identities`,
      pageParameters({ include_exact: false })) : apiPage('/api/v1/aliases',
      pageParameters({ ...filters, ...(view === 'configure' ? { include_activity: false } : {}),
        sort: route.get('sort') || defaultOrder.sort,
        direction: route.get('direction') || defaultOrder.direction }));
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
  const selectionFilters = { ...filters, q: route.get('q') };
  const selectionScope = aliasSelectionScopeKey('alias-list', selectionFilters);
  synchronizeAliasEditorSelectionScope(selectionScope);

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
  const importAliases = node('button', 'button secondary alias-transfer-import-button', 'Import aliases…');
  importAliases.type = 'button';
  importAliases.addEventListener('click', () => openAliasTransferModal(selectedList, 'Import'));
  const exportAliases = node('button', 'button secondary alias-transfer-export-button', 'Export aliases…');
  exportAliases.type = 'button';
  exportAliases.addEventListener('click', () => openAliasTransferModal(selectedList, 'Export'));
  listActions.append(importAliases, exportAliases);
  if (unmatchedTalkgroupsSupported(selectedList)) {
    const policy = node('button', 'button secondary alias-policy-button', 'Alias List Defaults');
    policy.type = 'button';
    policy.addEventListener('click', () => openUnmatchedTalkgroupPolicyModal(selectedList));
    listActions.append(policy);
  }
  listActions.append(remove);
  summary.append(listActions);
  main.append(summary, aliasEditorViewTabs(selectedList), view === 'discover' ?
    observedGroupIdentityToolbar(selectedList) : aliasEditorFilterToolbar(listResponse, options));

  if (view === 'discover') {
    renderObservedGroupIdentities(main, page, selectedList);
    return;
  }

  const definitions = [...aliasCustomConfigurationColumns(), ...aliasActivityColumns()];
  const tableHost = node('div', 'alias-catalog-table-host alias-editor-table-host');
  const selectionStatus = node('div', 'alias-form-message alias-selection-status');
  selectionStatus.setAttribute('role', 'status');
  selectionStatus.setAttribute('aria-live', 'polite');
  const actions = node('div', 'section-title-actions');
  let bulkBar = null;
  const updateSelection = (message = '', error = false, preserveRequest = false) => {
    if (!preserveRequest) aliasEditorSelectionRequest += 1;
    tableHost.querySelectorAll('.alias-row-select').forEach((checkbox) => {
      const row = checkbox.closest('tr');
      const id = Number(row?.dataset.id);
      checkbox.checked = aliasEditorSelection.has(id);
      row?.classList.toggle('selected', checkbox.checked);
    });
    bulkBar?.update();
    selectionStatus.replaceChildren();
    if (message) selectionStatus.append(node(error ? 'div' : 'span', error ? 'error' : 'muted', message));
  };
  const columnsForView = () => aliasEditorColumns(view, rows, updateSelection);
  const renderTable = () => {
    const aliasTable = table(rows, columnsForView(), 'No aliases match these filters', {
      type: `alias-editor-${view}`, serverSort: true, sortable: false,
      defaultSort: defaultOrder.sort, defaultDirection: defaultOrder.direction,
      rowKey: (row) => row.alias_id,
      defaultHiddenColumns: view === 'custom' ? definitions
        .map((column) => column.id).filter((id) => !ALIAS_CATALOG_DEFAULT_COLUMNS.includes(id)) : [],
      layoutMenuHost: actions,
      onRowClick: (row, _tableRow, event) => {
        const id = Number(row.alias_id);
        if (event.shiftKey || event.metaKey || event.ctrlKey) {
          const index = rows.indexOf(row);
          try {
            if (event.shiftKey && aliasEditorLastSelectionIndex !== null) {
              const start = Math.min(index, aliasEditorLastSelectionIndex);
              const end = Math.max(index, aliasEditorLastSelectionIndex);
              aliasEditorSelection = extendedAliasSelection(aliasEditorSelection,
                rows.slice(start, end + 1).map((candidate) => Number(candidate.alias_id)));
            } else if (aliasEditorSelection.has(id)) aliasEditorSelection.delete(id);
            else aliasEditorSelection = extendedAliasSelection(aliasEditorSelection, [id]);
            aliasEditorLastSelectionIndex = index;
            updateSelection();
          } catch (error) {
            updateSelection(error.message, true);
          }
          return;
        }
        window.location.assign(currentHref({ alias: id }));
      }
    });
    tableHost.replaceChildren(aliasTable);
    updateSelection();
  };

  const selectPage = node('button', 'button secondary', 'Select This Page');
  selectPage.type = 'button';
  selectPage.addEventListener('click', () => {
    try {
      aliasEditorSelection = extendedAliasSelection(aliasEditorSelection,
        rows.map((row) => Number(row.alias_id)));
      updateSelection();
    } catch (error) {
      updateSelection(error.message, true);
    }
  });
  const selectAll = node('button', 'button secondary alias-select-all', 'Select All Matching');
  selectAll.type = 'button';
  selectAll.addEventListener('click', () =>
    selectAllMatchingAliases(selectionFilters, selectionScope, selectAll, updateSelection));
  actions.append(selectPage, selectAll);
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
  actions.append(exportCsvLink('aliases', exportContext, 'Download table report'));
  const block = section(view === 'configure' ? 'Alias Configuration' :
    (view === 'activity' ? 'Activity' : 'Custom View'),
  tableHost, actions);
  block.classList.add('alias-catalog-section', 'alias-editor-table-section');
  bulkBar = aliasBulkBar(() => {
    resetAliasEditorSelection(selectionScope);
    updateSelection();
  });
  block.append(bulkBar, selectionStatus);
  renderTable();
  block.append(node('p', 'metric-meaning-note alias-catalog-guide', view === 'configure' ?
    'Configuration controls what the alias matches and what happens to its calls. Open an alias to edit it.' :
    'Calls are completed transmissions. Signaling counts recognized system actions. A call can also have signaling, ' +
      'so the columns should not be added together. An em dash means unavailable; 0 means monitored with none ' +
      'observed.'), pager(page));
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

const SIGNALING_COUNT_LABELS = new Map(GROUP_IDENTITY_SIGNALING_SERIES.map((series) =>
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
      .replace(/[^a-z0-9]+/g, '_')}_observation_count`;
    return SIGNALING_COUNT_LABELS.has(field);
  });
}

function groupIdentitySignaling(row) {
  const total = groupIdentitySignalingSortValue(row);
  return total > 0 ? number(total) : '—';
}

function groupIdentitySignalingSortValue(row) {
  const total = Number(row.signaling_observation_count || 0);
  return Number.isFinite(total) && total > 0 ? total : 0;
}

function withoutGrantActions(rows) {
  return (rows || []).filter((row) => String(row.action || '').toUpperCase() !== 'GRANT');
}

function dashboardChannelKind(row) {
  const value = String(row?.channel_kind || '').trim().toUpperCase();
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
  if (dashboardChannelKind(row) === 'TRUNKED') return channelLabel(row);
  if (row.source_label) return row.source_label;
  if (row.name) return row.name;
  if (row.frequency_hz) return `${frequency(row.frequency_hz)} MHz`;
  return 'Unknown receiver';
}

function callSourceLink(row) {
  const label = callSourceLabel(row);
  if (dashboardChannelKind(row) === 'TRUNKED') {
    return channelNameSummary(row);
  }
  const target = entityReferenceAllowed(row.entity_ref) ? entityTarget(row.entity_ref) : '';
  if (target) return anchor(label, target);
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

function groupIdentityActivityChart(response, seriesConfigurations, ariaLabel) {
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

  const wrapper = node('div', 'group-identity-activity-chart');
  const legend = node('div', 'activity-series-legend');
  const chartHost = node('div', 'group-identity-activity-chart-host');
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

function signalChannelState(channel, now = Date.now()) {
  const observed = Number(channel.last_observed_ms || 0);
  if (!observed || now - observed > SIGNAL_OFFLINE_MILLISECONDS) {
    return { label: 'Offline', className: 'offline', rank: 0 };
  }
  const decode = optionalNumber(channel.decode_health_pct);
  if (!Number.isFinite(optionalNumber(channel.average_signal_dbfs))) {
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

function sharedSignalDomain(channels) {
  const values = [];
  (channels || []).forEach((channel) => {
    (channel.series || []).forEach((point) => {
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

function qualityHistoryChart(channel, response, metric, domain) {
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
  svg.setAttribute('aria-label', `${channelLabel(channel)} ${signal ? 'signal strength' : 'decode quality'} history`);
  const points = (channel.series || []).map((point) => ({
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

function updateSignalCurrentTile(tile, channel) {
  const state = signalChannelState(channel);
  const header = node('div', 'signal-current-header');
  const labels = node('div', 'signal-current-labels');
  labels.append(channelNameSummary(channel));
  const system = node('div', 'signal-current-system');
  system.append(dashboardChannelContext(channel));
  labels.append(system);
  header.append(labels, badge(state.label, `signal-state ${state.className}`));
  const power = node('div', 'signal-current-power');
  power.append(node('strong', '', signalNumber(channel.signal_dbfs)),
    node('span', '', `30s avg ${signalNumber(channel.average_signal_dbfs)}`));
  const details = node('div', 'signal-current-details');
  const qualityFrequency = channel.quality_frequency_hz || channel.current_control_hz;
  const decode = optionalNumber(channel.decode_health_pct);
  const decodeClass = !Number.isFinite(decode) ? '' :
    (decode >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'quality-good' :
      (decode >= DECODE_DEGRADED_MINIMUM_PERCENT ? 'quality-warn' : 'quality-bad'));
  details.append(node('span', decodeClass,
  `Decode ${percentNumber(channel.decode_health_pct)}`),
  node('span', '', Number(qualityFrequency) ? `${frequency(qualityFrequency)} MHz` : 'Frequency unavailable'),
  node('span', '', elapsedLabel(channel.last_observed_ms)));
  tile.dataset.configurationId = channel.configuration_id || '';
  tile.replaceChildren(header, power, details);
  return tile;
}

function signalCurrentTile(channel) {
  return updateSignalCurrentTile(node('article', 'signal-current-tile'), channel);
}

function sortSignalChannels(channels) {
  return [...channels].sort((left, right) =>
    channelLabel(left).localeCompare(channelLabel(right), undefined, { sensitivity: 'base' }) ||
      String(left.configuration_id || '').localeCompare(String(right.configuration_id || '')));
}

function signalOverview(channel, includeName = true) {
  const overview = node('div', 'signal-history-overview');
  overview.classList.toggle('without-identity', !includeName);
  if (includeName) {
    const identity = node('div', 'signal-history-identity');
    const system = node('span');
    system.append(radioSystemValue(channel));
    identity.append(channelLink(channel), system);
    overview.append(identity);
  }
  [['Current', signalNumber(channel.signal_dbfs)], ['30s average', signalNumber(channel.average_signal_dbfs)],
    ['Decode', percentNumber(channel.decode_health_pct)],
    ['Last sample', elapsedLabel(channel.last_observed_ms)]].forEach(([label, value]) => {
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
  if (pageLifecycle.requiresPageHandling(error)) throw error;
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
    const channels = sortSignalChannels((currentResponse?.rows || []).filter((channel) =>
      canonicalConfigurationId(channel?.configuration_id) &&
      now - Number(channel.last_observed_ms || 0) <= SIGNAL_OFFLINE_MILLISECONDS));
    const healthy = channels.filter((channel) =>
      optionalNumber(channel.decode_health_pct) >= DECODE_HEALTHY_MINIMUM_PERCENT).length;
    const degraded = channels.filter((channel) => {
      const decode = optionalNumber(channel.decode_health_pct);
      return Number.isFinite(decode) && decode >= DECODE_DEGRADED_MINIMUM_PERCENT &&
        decode < DECODE_HEALTHY_MINIMUM_PERCENT;
    }).length;
    const poor = channels.filter((channel) => {
      const decode = optionalNumber(channel.decode_health_pct);
      return Number.isFinite(decode) && decode < DECODE_DEGRADED_MINIMUM_PERCENT;
    }).length;
    const unknown = channels.length - healthy - degraded - poor;
    summary.textContent = `${number(channels.length)} reporting · ${number(healthy)} healthy · ` +
      `${number(degraded)} degraded · ${number(poor)} poor${unknown ? ` · ${number(unknown)} unknown` : ''}`;

    const activeKeys = new Set();
    const orderedTiles = channels.map((channel) => {
      const key = canonicalConfigurationId(channel.configuration_id);
      activeKeys.add(key);
      const existing = tileNodes.get(key);
      const tile = existing ? updateSignalCurrentTile(existing, channel) : signalCurrentTile(channel);
      tileNodes.set(key, tile);
      return tile;
    });
    [...tileNodes.keys()].filter((key) => !activeKeys.has(key)).forEach((key) => tileNodes.delete(key));
    tiles.replaceChildren(...orderedTiles);
    if (!channels.length) tiles.append(node('div', 'empty', 'No receiver channels are currently reporting'));
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

async function channelSignalHistorySection(channel) {
  const renderContext = captureRenderContext();
  const host = node('div', 'site-signal-history');
  const block = section('Control Channel Quality History', host);
  block.classList.add('site-signal-history-section');
  let selectedRange = '24h';
  let loadingSequence = 0;
  let loading = false;
  const rangeControl = signalRangeControls(selectedRange, async (value, buttons) => {
    selectedRange = value;
    exportLink.href = exportCsvHref('channel-quality', {
      configuration_id: channel.configuration_id, range: selectedRange
    });
    await load(buttons, true);
  });
  const exportLink = exportCsvLink('channel-quality', {
    configuration_id: channel.configuration_id, range: selectedRange
  });
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
      const response = await api(channelApiPath(channel.configuration_id, 'quality'), {
        range: selectedRange, points: 300
      });
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

async function groupIdentityActivityHistorySection(scopeParameters) {
  const renderContext = captureRenderContext();
  const host = node('div', 'group-identity-activity-history');
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
      host.replaceChildren(node('div', 'loading', 'Loading group activity history'));
    }
    try {
      const response = await api(groupIdentityApiPath(scopeParameters.radio_system_key,
        scopeParameters.identity_key, 'activity'), { range: selectedRange });
      if (sequence !== loadingSequence) return;
      host.replaceChildren(metrics([
        ['Logical Calls', response.totals?.logical_call_count],
        ['Recorded', response.totals?.recorded_logical_call_count],
        ['Submitted to Streamer', response.totals?.stream_submitted_logical_call_count],
        ['Encrypted', response.totals?.encrypted_logical_call_count]
      ], true),
      section('Call Activity', groupIdentityActivityChart(response, GROUP_IDENTITY_CALL_ACTIVITY_SERIES,
        'Group calls and call outcomes by time')),
      tableSection('Retained Signaling Totals',
        signalingCounts(response.totals || {}).map(([action, count]) => ({ action, count })), [
          { id: 'action', label: 'Action', key: 'action' },
          { id: 'count', label: 'Count', render: (row) => number(row.count),
            className: 'numeric', sortValue: (row) => Number(row.count || 0) }
        ], 'No signaling observations recorded', { type: 'action-counts' }),
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
    host.append(node('div', 'empty', 'Group activity history requires Stats Logging.'));
  } else {
    await load(rangeControl.buttons, true, true);
    if (renderIsCurrent(renderContext)) pageInterval(load, 30_000);
  }
  return block;
}

async function channelTopGroupsSection(channel) {
  const host = node('div', 'channel-top-groups');
  let selectedRange = '24h';
  let loadingSequence = 0;
  let loading = false;
  const tableController = {};
  const rangeControl = rangeControls(SIGNAL_RANGES, selectedRange, async (value, buttons) => {
    selectedRange = value;
    await load(buttons, true);
  });
  const titleActions = sectionActionHost(rangeControl.controls);
  const block = section('Group Activity on This Channel', host, titleActions);
  const columns = [
    { id: 'group-identity-id', label: 'Group', render: (row) => groupIdentityLink(row),
      className: 'numeric', sortValue: (row) => Number(groupIdentityDisplayId(row)) },
    { id: 'group-identity-kind', label: 'Kind', render: (row) => groupIdentityLabel(row) },
    { id: 'group-identity-name', label: 'Alias', fullLabel: 'Group Alias',
      render: (row) => groupIdentityAliasLink(row),
      className: 'alias-cell', sortValue: aliasLabel },
    { id: 'group', label: 'Group', key: 'alias_group', className: 'alias-cell', sortValue: (row) => row.alias_group || '' },
    { id: 'logical-calls', label: 'Logical Calls',
      render: (row) => number(row.logical_call_count), className: 'numeric',
      sortValue: (row) => Number(row.logical_call_count || 0) },
    { id: 'encrypted-logical-calls', label: 'Encrypted',
      fullLabel: 'Encrypted Logical Calls',
      render: (row) => number(row.encrypted_logical_call_count),
      className: 'numeric encrypted',
      sortValue: (row) => Number(row.encrypted_logical_call_count || 0) }
  ];

  const load = async (buttons = rangeControl.buttons, interactive = false, pageOwned = false) => {
    if (loading && !interactive) return;
    const sequence = ++loadingSequence;
    loading = true;
    if (interactive) {
      buttons.forEach((button) => { button.disabled = true; });
      cleanupTableLayoutMenu(tableController);
      host.replaceChildren(node('div', 'loading', 'Loading group activity for this channel'));
    }
    try {
      const response = await api(channelApiPath(channel.configuration_id, 'group-identities'), {
        range: selectedRange, limit: 20
      });
      if (sequence !== loadingSequence) return;
      host.replaceChildren(table(response.rows || [], columns,
        'No group activity is available for this range', {
          type: 'channel-top-groups', controller: tableController, layoutMenuHost: titleActions
        }));
    } catch (error) {
      if (sequence === loadingSequence) cleanupTableLayoutMenu(tableController);
      if (pageOwned) rethrowPageHandlingError(error);
      if (sequence === loadingSequence) {
        host.replaceChildren(node('div', 'error', error.message));
      }
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
    host.append(node('div', 'empty', 'Group activity requires Stats Logging.'));
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
  return pageLifecycle.decodeOffsetPage(response, path);
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
  const activeCount = Math.max(receiverHealthCount(reported.active_count), active.length);
  const warningCount = Math.max(receiverHealthCount(reported.warning_count),
    active.filter((incident) => receiverHealthSeverity(incident.severity) === 'warning').length);
  const criticalCount = Math.max(receiverHealthCount(reported.critical_count),
    active.filter((incident) => receiverHealthSeverity(incident.severity) === 'critical').length);
  let severity = receiverHealthSeverity(reported.severity);
  if (criticalCount > 0) severity = 'critical';
  else if (warningCount > 0 || activeCount > 0) severity = 'warning';
  return {
    started_at_ms: Number(value.started_at_ms) || 0,
    generated_at_ms: Number(value.generated_at_ms) || 0,
    summary: { severity, active_count: activeCount, warning_count: warningCount,
      critical_count: criticalCount },
    active,
    resolved,
    measurements
  };
}

function receiverHealthAccountAlertSummary(snapshot, preferences) {
  const active = Array.isArray(snapshot?.active) ? snapshot.active : [];
  const summary = snapshot?.summary || {};
  const activeCount = Math.max(receiverHealthCount(summary.active_count), active.length);
  const disabled = active.filter((incident) =>
    !isReceiverHealthAlertEnabled(preferences, incident.code));
  const enabled = active.filter((incident) =>
    isReceiverHealthAlertEnabled(preferences, incident.code));
  const listedCritical = active.filter((incident) =>
    receiverHealthSeverity(incident.severity) === 'critical').length;
  const listedWarnings = active.filter((incident) =>
    receiverHealthSeverity(incident.severity) === 'warning').length;
  const criticalCount = enabled.filter((incident) =>
    receiverHealthSeverity(incident.severity) === 'critical').length +
    Math.max(0, receiverHealthCount(summary.critical_count) - listedCritical);
  const warningCount = enabled.filter((incident) =>
    receiverHealthSeverity(incident.severity) === 'warning').length +
    Math.max(0, receiverHealthCount(summary.warning_count) - listedWarnings);
  return {
    active_count: activeCount,
    enabled_count: Math.max(0, activeCount - disabled.length),
    disabled_count: disabled.length,
    critical_count: criticalCount,
    warning_count: warningCount
  };
}

function receiverHealthDisabledCodesForSave(preferences, controls) {
  const unknownDisabledCodes = preferences?.health_alerts?.disabled_codes
    ?.filter((code) => !controls.has(code)) || [];
  const disabledKnownCodes = receiverHealthAlertIds.filter((id) => !controls.get(id)?.checked);
  return [...unknownDisabledCodes, ...disabledKnownCodes];
}

class ReceiverHealthController {
  constructor() {
    this.snapshot = null;
    this.stale = false;
    this.lastError = '';
    this.requestController = null;
    this.pageHost = null;
    this.resolvedSort = 'recent';
    this.resolvedPage = 0;
    this.openHealthSections = new Set(['host-overview', 'current', 'active', 'resolved']);
    this.expandedResolvedIncidents = new Set();
  }

  authorized() {
    return capabilityAllowed(ACCESS_CAPABILITIES.RECEIVER_HEALTH);
  }

  desktopEnabled() {
    return this.authorized();
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
      this.resolvedPage = 0;
      this.openHealthSections = new Set(['host-overview', 'current', 'active', 'resolved']);
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
    if (this.pageHost !== host) {
      this.resolvedPage = 0;
      this.openHealthSections = new Set(['host-overview', 'current', 'active', 'resolved']);
    }
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
    const accountAlerts = receiverHealthAccountAlertSummary(this.snapshot, activeUserPreferences());
    let className = 'loading';
    let label = 'Loading';
    let detail = 'Receiver health status is loading.';
    if (this.stale) {
      className = 'stale';
      if (accountAlerts.critical_count > 0) {
        const count = accountAlerts.critical_count || accountAlerts.enabled_count;
        label = `Stale · Critical ${number(count)}`;
      } else if (accountAlerts.warning_count > 0) {
        const count = accountAlerts.warning_count || accountAlerts.enabled_count;
        label = `Stale · Warning ${number(count)}`;
      } else {
        label = 'Stale';
      }
      detail = this.lastError || 'Receiver health status is stale.';
    } else if (accountAlerts.critical_count > 0) {
      className = 'critical';
      const count = accountAlerts.critical_count || accountAlerts.enabled_count;
      label = `Critical ${number(count)}`;
      detail = `${number(accountAlerts.enabled_count)} active alert${accountAlerts.enabled_count === 1 ? '' : 's'}, ` +
        `${number(accountAlerts.critical_count)} critical.`;
    } else if (accountAlerts.warning_count > 0) {
      className = 'warning';
      const count = accountAlerts.warning_count || accountAlerts.enabled_count;
      label = `Warning ${number(count)}`;
      detail = `${number(accountAlerts.enabled_count)} active alert${accountAlerts.enabled_count === 1 ? '' : 's'}, ` +
        `${number(accountAlerts.warning_count)} warning.`;
    } else if (accountAlerts.enabled_count > 0) {
      className = 'warning';
      label = `Alert ${number(accountAlerts.enabled_count)}`;
      detail = `${number(accountAlerts.enabled_count)} active receiver health alert` +
        `${accountAlerts.enabled_count === 1 ? '' : 's'}.`;
    } else if (accountAlerts.active_count > 0) {
      className = 'neutral';
      label = `${number(accountAlerts.disabled_count)} alert${accountAlerts.disabled_count === 1 ? '' : 's'} turned off`;
      detail = `All ${number(accountAlerts.disabled_count)} active receiver health alert` +
        `${accountAlerts.disabled_count === 1 ? ' is' : 's are'} turned off for this account. ` +
        'Monitoring and history continue.';
    } else if (summary) {
      className = 'healthy';
      label = 'Healthy';
      detail = 'No active receiver health incidents.';
    }
    if (!this.stale && accountAlerts.disabled_count > 0 && accountAlerts.enabled_count > 0) {
      detail += ` ${number(accountAlerts.disabled_count)} alert` +
        `${accountAlerts.disabled_count === 1 ? ' is' : 's are'} turned off for this account.`;
    }
    if (this.snapshot?.generated_at_ms) {
      detail += ` Last update: ${exactDateTime(this.snapshot.generated_at_ms)}.`;
    }
    state.textContent = label;
    ['healthy', 'warning', 'critical', 'neutral', 'stale', 'loading'].forEach((status) => {
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
  const title = content.querySelector(':scope > .page-header .page-title')?.textContent?.trim();
  if (title) pageTitleController.update({ pageTitle: title });
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
  const titleActions = sectionActionHost(options.action || null);
  const tableController = {};
  const element = section(title, host, titleActions);
  let loadSequence = 0;
  let focusAfterAttempt = false;

  const load = (loader, present, renderContext) => {
    const sequence = ++loadSequence;
    return pageLifecycle.run({
      isCurrent: () => sequence === loadSequence && renderIsCurrent(renderContext) && host.isConnected,
      onLoading: ({ retry }) => {
        focusAfterAttempt = retry;
        host.setAttribute('aria-busy', 'true');
        const loading = node('div', 'loading', options.loadingMessage || 'Loading…');
        loading.setAttribute('role', 'status');
        if (retry) loading.tabIndex = -1;
        cleanupTableLayoutMenu(tableController);
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
        cleanupTableLayoutMenu(tableController);
        host.replaceChildren(failure);
        host.setAttribute('aria-busy', 'false');
        if (focusAfterAttempt) failure.querySelector('.async-section-retry')?.focus();
      }
    });
  };

  return Object.freeze({ element, host, load, titleActions, tableController });
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
const LIVE_MULTIPLEX_VERSION = 2;
const LIVE_MULTIPLEX_HEADER_BYTES = 16;
const LIVE_MULTIPLEX_MAXIMUM_BYTES = 16 * 1024 * 1024;
const LIVE_MULTIPLEX_READY_TIMEOUT_MS = 10_000;
const LIVE_MULTIPLEX_LIVENESS_TIMEOUT_MS = 25_000;
const LIVE_MULTIPLEX_TOPICS = Object.freeze({
  0: 'control',
  1: 'channel_activity',
  2: 'decode_events',
  3: 'decode_messages',
  4: 'channel_diagnostics',
  5: 'tuner_diagnostics'
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
    if (attempt !== this.attempt) return;
    if (!this.hasSubscribers()) {
      this.stop();
      return;
    }
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
      if (header.getUint32(0) !== LIVE_MULTIPLEX_MAGIC || header.getUint8(4) !== LIVE_MULTIPLEX_VERSION) {
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
    if (!this.ready || !this.clientId) {
      if (!this.hasSubscribers()) this.stop();
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
  const player = webCallPlayer;
  if (!player || !capabilityAllowed(ACCESS_CAPABILITIES.CALL_AUDIO)) return;
  if (playbackScanListLoading && !force) return;
  const request = ++playbackScanListRequest;
  playbackScanListLoading = true;
  player.setScanListsLoading();
  try {
    const response = await api('/api/v1/scan-lists');
    if (request !== playbackScanListRequest || player !== webCallPlayer ||
        !capabilityAllowed(ACCESS_CAPABILITIES.CALL_AUDIO)) return;
    player.setScanLists(response?.scan_lists);
  } catch (error) {
    if (request !== playbackScanListRequest || player !== webCallPlayer) return;
    const message = error?.status === 404 ? 'Scan lists are not available on this receiver' :
      'Unable to load scan lists';
    player.setScanListsUnavailable(message);
  } finally {
    if (request === playbackScanListRequest) playbackScanListLoading = false;
  }
}

function synchronizePlaybackAccess(accessChanged = false) {
  if (accessChanged) scannerChannelCache.clear();
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
    if (webCallPlayer) webCallPlayer.disconnect(unavailableMessage);
    else status.textContent = unavailableMessage;
    bar.querySelectorAll('button, input').forEach((control) => { control.disabled = true; });
    return;
  }

  if (!webCallPlayer) {
    webCallPlayer = new WebCallPlayer({
      play: 'playback-play',
      pause: 'playback-pause',
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
    webCallPlayer.setPreferenceWriter((playback) => {
      if (!userPreferenceController.snapshot().loaded) return;
      return updateUserPreferences((preferences) => { preferences.playback = playback; });
    });
    webCallPlayer.applyPreferences(activeUserPreferences().playback);
    webCallPlayer.subscribeState((playerState) =>
      pageTitleController.update({ playerState }));
  }
  webCallPlayer.setActions({
    openAvoidList: openPlaybackAvoidList
  });
  bar.querySelectorAll('button, input').forEach((control) => { control.disabled = false; });
  webCallPlayer.render();
  webCallPlayer.renderScanLists();
  if (accessChanged || !webCallPlayer.scanListsReady()) refreshPlaybackScanLists(accessChanged);
  if (!webCallPlayer.feedUrl) {
    webCallPlayer.connect('/api/v1/calls/feed', (path, options) =>
      requestJson(path, { ...options, csrf: false, page: false, timeoutMs: 10_000 }));
  }
}

const SCANNER_DETAIL_LEVELS = Object.freeze({ simple: 0, normal: 1, advanced: 2, engineer: 3 });
const SCANNER_CHANNEL_CACHE_LIMIT = 64;
const scannerChannelCache = new Map();
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

function scannerChannelMetadata(configurationId) {
  const key = String(configurationId || '').trim();
  if (!key) return Promise.resolve(null);
  if (scannerChannelCache.has(key)) return scannerChannelCache.get(key);
  const request = api(channelApiPath(key), {}, { page: false })
    .then((response) => response?.channel || null).catch(() => null);
  scannerChannelCache.set(key, request);
  while (scannerChannelCache.size > SCANNER_CHANNEL_CACHE_LIMIT) {
    scannerChannelCache.delete(scannerChannelCache.keys().next().value);
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

function scannerIsP25(call) {
  const protocol = String(call?.protocol || call?.decoder || '').toUpperCase();
  return protocol.includes('P25') || protocol.includes('APCO25');
}

function scannerNetworkSiteIdentity(call) {
  if (scannerIsP25(call)) {
    const wacn = scannerHex(call?.wacn, 5);
    const system = scannerHex(call?.system_id, 3);
    const rfss = scannerHex(call?.rfss_id, 2);
    const site = scannerHex(call?.site_id, 2);
    const network = wacn && system ? `${wacn}-${system}` : wacn || (system ? `SYS ${system}` : '');
    const location = rfss && site ? `${rfss}-${site}` : rfss ? `RFSS ${rfss}` : site ? `SITE ${site}` : '';
    return [network, location].filter(Boolean).join(' · ');
  }

  const family = String(call?.protocol || call?.decoder || '').toUpperCase();
  const values = [];
  const network = scannerIdentifierNumber(call?.network_id);
  const site = scannerIdentifierNumber(call?.site_id);
  if (family.includes('DMR')) {
    if (network !== null) values.push(`Network ${network}`);
    if (site !== null) values.push(`Site ${site}`);
    return values.join(' · ');
  }

  const system = scannerIdentifierNumber(call?.system_id);
  const ran = scannerIdentifierNumber(call?.ran);
  if (network !== null && system !== null) values.push(`${network}-${system}`);
  else if (network !== null) values.push(`Network ${network}`);
  else if (system !== null) values.push(`System ${system}`);
  if (site !== null) values.push(`Site ${site}`);
  if (ran !== null) values.push(`RAN ${ran}`);
  return values.join(' · ');
}

function scannerTargetLabel(call, channel) {
  if (isAnalogChannel(channel || call)) return call?.channel || channel?.name || 'Analog channel';
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
  return ids.map((id) => names.get(id)).filter(Boolean).join(' · ');
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

function entityTarget(reference, tabsByView = {}) {
  const target = entityRefHref(reference);
  if (!target) return '';
  const parsed = new URL(target, window.location.origin);
  const tab = tabsByView[parsed.searchParams.get('view')];
  if (tab) parsed.searchParams.set('tab', tab);
  return `${parsed.pathname}?${parsed.searchParams}`;
}

function entityReferenceAllowed(reference) {
  const kind = String(reference?.kind || '').trim();
  return ['radio_system', 'channel', 'talkgroup', 'patch_group', 'radio'].includes(kind) &&
    capabilityAllowed(ACCESS_CAPABILITIES.RADIO);
}

function scannerNavigate(call, channel, destination) {
  let reference = null;
  let tabs = {};
  if (destination === 'system') reference = call?.radio_system_entity_ref;
  else if (destination === 'target' || destination === 'target-alias') reference = call?.target_entity_ref;
  else if (destination === 'source' || destination === 'source-alias') reference = call?.source_entity_ref;
  else {
    reference = call?.entity_ref;
    tabs = ['channel', 'frequency', 'lcn'].includes(destination) ? { channel: 'frequencies' } : {};
  }
  if (!reference && dashboardChannelKind(channel) === 'CONVENTIONAL' && !isAnalogChannel(channel || call)) {
    reference = call?.entity_ref || channel?.entity_ref;
    if (destination === 'target' || destination === 'target-alias') tabs = { channel: 'groups' };
    else if (destination === 'source' || destination === 'source-alias') tabs = { channel: 'radios' };
  }
  const target = entityTarget(reference, tabs);

  if (!target) {
    openReadOnlyModal('Details unavailable', node('p', '',
      'This completed call does not include a stable destination for that detail page.'),
      { id: 'scanner-navigation' });
    return;
  }
  navigateTo(target);
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

function scannerCallQuality(call) {
  const quality = node('section', 'scanner-call-quality');
  quality.append(node('span', 'scanner-call-quality-heading', 'Call Quality'));
  const values = node('div', 'scanner-call-quality-values');
  [
    ['Decoded', call?.vc_decoded_frames], ['Repeated', call?.vc_repeated_frames],
    ['Concealed', call?.vc_concealed_frames], ['Missing', call?.vc_missing_frames],
    ['FEC Errors', call?.vc_fec_errors], ['FEC Protected', call?.vc_fec_protected_bits]
  ].forEach(([label, value]) => {
    const metric = node('div', 'scanner-call-quality-stat');
    metric.append(node('span', '', label), node('strong', '',
      value === null || value === undefined || value === '' ? '—' : String(value)));
    values.append(metric);
  });
  quality.append(values);
  return quality;
}

function scannerCallRenderKey(call, state, site) {
  if (!call) return `idle:${scannerDetailMode}:${state.stopped ? 'stopped' : state.paused ? 'paused' : 'listening'}`;
  return JSON.stringify([
    scannerDetailMode, call.call_id || '', call.started_at_ms || '',
    scannerMatchedScanLists(call, state), site?.p25_decoder_mode || '',
    site?.modulation || '', site?.configuration_id || '', site?.channel_kind || '',
    site?.entity_ref?.key || ''
  ]);
}

function renderScannerCall(host, state, channelMetadata) {
  const call = state.displayCall || state.current;
  const renderKey = scannerCallRenderKey(call, state, channelMetadata);
  host.dataset.scannerMode = scannerDetailMode;
  if (host.dataset.renderKey === renderKey && host.childNodes.length) {
    const wave = host.querySelector('.scanner-audio-wave');
    if (wave) {
      wave.classList.toggle('paused', !state.playing);
      wave.setAttribute('aria-label', state.playing ? 'Audio playing' : 'Audio idle');
    }
    return;
  }
  host.dataset.renderKey = renderKey;
  host.replaceChildren();
  if (!call) {
    const idle = node('div', 'scanner-idle');
    idle.append(node('strong', '', state.stopped ? 'Ready to listen' : state.paused ? 'Playback paused' : 'Scanning selected lists'),
      node('span', '', state.paused ? 'Calls continue queuing. Press Resume to listen; older calls may expire.' :
        state.stopped ? 'Press Play to receive completed calls.' :
        'The next matching completed call will appear here.'));
    host.append(idle);
    return;
  }

  const intro = node('div', 'scanner-call-intro');
  const copy = node('div');
  const analog = isAnalogChannel(channelMetadata || call);
  copy.append(node('span', 'scanner-call-kind', `${call.decoder || call.protocol || 'Call'}${call.encrypted ?
    ' · Encrypted' : ' · Voice'}`), node('strong', 'scanner-call-title', scannerTargetLabel(call, channelMetadata)),
    node('span', 'scanner-call-subtitle', [call.system, call.site].filter(Boolean).join(' · ')));
  const wave = node('div', `scanner-audio-wave${state.playing ? '' : ' paused'}`);
  for (let index = 0; index < 24; index++) wave.append(node('i'));
  wave.setAttribute('aria-label', state.playing ? 'Audio playing' : 'Audio idle');
  const instruments = node('div', 'scanner-call-instruments');
  instruments.append(scannerVoiceMeter(call), wave);
  intro.append(copy, instruments);

  const fields = node('div', 'scanner-field-grid');
  const open = (destination) => () => scannerNavigate(call, channelMetadata, destination);
  const participants = node('div', 'scanner-participant-grid');
  if (!analog) {
    participants.append(scannerParticipant('Target', call.target_alias, call.target_id, call.target_description,
      call.target_group, open('target-alias'), open('target')));
  }
  participants.append(scannerParticipant('Source', scannerSourceAlias(call), call.source_id,
    call.source_description, call.source_group, open('source-alias'), open('source')));
  const networkSiteIdentity = scannerNetworkSiteIdentity(call);
  const nac = scannerIsP25(call) ? scannerHex(call.nac, 3) : '';
  const modulation = channelMetadata?.p25_decoder_mode || call.modulation || '';
  [
    scannerField('System', call.system, 0, open('system')),
    scannerField('Site', call.site, 0, open('site')),
    scannerField('Matched Scan Lists', scannerMatchedScanLists(call, state), 1, null, true),
    scannerField('Channel', call.channel, 1, open('channel'), true),
    scannerField('Network / Site', networkSiteIdentity, 1, open('site'), true),
    scannerField('NAC', nac, 2, open('identifier')),
    scannerField('Frequency', scannerFrequency(call), 1, open('frequency')),
    scannerField('LCN', call.lcn, 2, open('lcn')),
    scannerField('Decoder', call.decoder, 1, open('decoder')),
    scannerField('Modulation', modulation ? `${modulation} · configured` : '', 2, open('decoder'))
  ].filter(Boolean).forEach((field) => fields.append(field));

  const engineer = node('div', 'scanner-engineer-grid');
  if (scannerDetailMode === 'engineer') {
    const values = [
      ['Call ID', call.call_id], ['Protocol', call.protocol],
      ['Started', exactDateTime(call.started_at_ms)],
      ['Duration', scannerDuration(call.duration_ms)], ['Timeslot', call.timeslot],
      ['Encryption', call.encrypted ? 'Encrypted' : 'Clear'], ['Alias List', call.alias_list]
    ];
    engineer.append(scannerCallQuality(call));
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

function openPlaybackAvoidList(player = webCallPlayer) {
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
      copy.append(node('strong', '', avoid.label || 'Avoided target'));
      if (avoid.system_scope) copy.append(node('span', '', avoid.system_scope));
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

function scanListCoverageTree(coverage) {
  const host = node('div', 'scanner-coverage-tree');
  const aliases = Array.isArray(coverage?.aliases) ? coverage.aliases : [];
  const lists = new Map();
  aliases.forEach((alias) => {
    const listId = aliasListId(alias);
    if (listId === null) return;
    const listKey = String(listId);
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

function openPlaybackScanListCoverage(player = webCallPlayer, preferredId = null) {
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
  const player = webCallPlayer;
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
  const controls = node('div', 'scanner-controls');
  const play = scannerControl('Play', () => void player.togglePlayback(), 'primary');
  const pause = scannerControl('Pause', () => void player.togglePause());
  pause.title = 'Pause audio and keep collecting calls. The queue is limited and older calls may expire.';
  const replay = scannerControl('Replay Last Call', () => void player.replayLastCall());
  const skip = scannerControl('Skip', () => player.skip());
  const hold = scannerControl('Hold', () => player.toggleHold());
  const avoid = scannerControl('Avoid', () => player.avoidCurrent(), 'danger');
  const avoidList = scannerControl('Avoid List', () => openPlaybackAvoidList(player));
  avoidList.dataset.scannerAction = 'avoid-list';
  const clearQueue = scannerControl('Clear Queue', () => player.clearQueue());
  controls.append(play, pause, replay, skip, hold, avoid, avoidList, clearQueue);

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
    player.changeVolume(false);
  });
  volume.addEventListener('change', () => player.writePreferences());
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

  chassis.append(statusBar, displayShell, controls, utility);
  page.append(chassis, scanPanel);
  const heading = pageHeader('Scanner', 'Listen to completed calls from this receiver');
  const headingActions = node('div', 'scanner-header-actions');
  const scannerSettings = iconButton('icon-live-presentation', 'Scanner settings',
    'button secondary icon-button section-title-icon scanner-settings');
  scannerSettings.id = 'scanner-settings';
  scannerSettings.addEventListener('click', () => openScannerSettings('#scanner-settings'));
  headingActions.append(modeBar, scannerSettings);
  heading.append(headingActions);
  const host = node('div', 'scanner-player-host');
  host.append(page);
  if (!beginPage(renderContext, heading, host)) return;
  placePlaybackBar();

  let latestState = player.viewState();
  let currentConfigurationId = '';
  let currentChannel = null;
  const updateAge = () => {
    const displayedCall = latestState.displayCall || latestState.current;
    age.textContent = displayedCall ? scannerRelativeAge(displayedCall.completed_at_ms) :
      'Waiting for a call';
  };
  const draw = (state) => {
    latestState = state;
    const nextConfigurationId = String((state.displayCall || state.current)?.configuration_id || '');
    const channelChanged = nextConfigurationId !== currentConfigurationId;
    if (channelChanged) {
      currentConfigurationId = nextConfigurationId;
      currentChannel = null;
    }
    playbackStatus.textContent = state.status ||
      (state.stopped ? 'Ready' : state.paused ? 'Paused' : 'Listening');
    playbackStatus.classList.toggle('active', !state.stopped && !state.paused);
    play.textContent = state.stopped ? 'Play' : 'Stop';
    play.classList.toggle('active', !state.stopped);
    pause.textContent = state.paused ? 'Resume' : 'Pause';
    pause.disabled = state.stopped;
    pause.classList.toggle('active', state.paused);
    pause.setAttribute('aria-pressed', String(state.paused));
    replay.disabled = !state.lastCallReady || state.paused;
    skip.disabled = !state.current && !state.queuedCount;
    hold.disabled = state.replayingLast || (!state.holdTarget && !state.currentReady);
    hold.classList.toggle('active', Boolean(state.holdTarget));
    avoid.disabled = !state.currentReady || state.replayingLast;
    avoidList.textContent = `Avoid List${state.avoids.length ? ` (${state.avoids.length})` : ''}`;
    clearQueue.textContent = `Clear Queue${state.queuedCount ? ` (${state.queuedCount})` : ''}`;
    clearQueue.disabled = !state.queuedCount;
    volume.value = String(state.volume);
    updateAge();
    renderScannerCall(display, state, currentChannel);

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
        node('small', '', item.description || (item.default ? 'Default list' : 'Available')));
      button.addEventListener('click', () => player.setScanListSelected(item.id, !item.selected));
      scanButtons.append(button);
    });

    if (channelChanged) {
      if (nextConfigurationId) void scannerChannelMetadata(nextConfigurationId).then((channel) => {
        if (currentConfigurationId === nextConfigurationId && display.isConnected) {
          currentChannel = channel;
          renderScannerCall(display, latestState, currentChannel);
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
      wave.setAttribute('aria-label', playing ? 'Audio playing' : 'Audio idle');
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
    const selectedMode = button.dataset.mode;
    scannerDetailMode = selectedMode;
    void settleUserPreferenceMutation((preferences) => { preferences.scanner.detail_mode = selectedMode; });
    modeBar.querySelectorAll('button').forEach((item) => item.classList.toggle('active', item === button));
    renderScannerCall(display, latestState, currentChannel);
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
  const configurationId = normalizedSiteText(route.get('configuration_id'));
  return {
    affiliated: route.get('affiliated') === 'true' ? true : null,
    configuration_id: configurationId || null
  };
}

function affiliationFilterActions(exportAction = null) {
  const filters = affiliationRouteFilters();
  const actions = node('div', 'section-title-actions');
  if (filters.affiliated || filters.configuration_id) {
    actions.append(anchor('Clear Filter', currentHref({ affiliated: null, configuration_id: null, offset: null }),
      'button secondary'));
  }
  if (exportAction) actions.append(exportAction);
  return actions.childNodes.length ? actions : null;
}

function requiredRadioSystem() {
  const radioSystemKey = String(route.get('radio_system_key') || '').trim();
  if (!radioSystemKey) throw new Error('Radio system key is missing from the URL');
  return { radio_system_key: radioSystemKey };
}

function requiredIdentityKey() {
  const identityKey = String(route.get('identity_key') || '').trim();
  if (!/^v1-[grp]-(?:x|[0-9a-f]{5})-(?:x|[0-9a-f]{3})-[0-9]+$/.test(identityKey)) {
    throw new Error('Identity key is missing from the URL');
  }
  return identityKey;
}

function radioSystemCapability(system, capability) {
  const capabilities = system?.capabilities;
  if (!capabilities || typeof capabilities !== 'object') return false;
  return Boolean(capabilities[capability]);
}

function radioSystemTabItems(system) {
  const values = radioSystemRoute(system);
  const items = [{ id: 'info', label: 'Info', href: href('radio-system', { ...values, tab: 'info' }) }];
  if (radioSystemCapability(system, 'group_identities')) {
    items.push({ id: 'groups', label: 'Groups', href: href('radio-system', { ...values, tab: 'groups' }) });
  }
  if (radioSystemCapability(system, 'radios')) {
    items.push({ id: 'radios', label: 'Radios', href: href('radio-system', { ...values, tab: 'radios' }) });
  }
  if (radioSystemCapability(system, 'activity')) {
    items.push({ id: 'activity', label: 'Activity', href: href('radio-system', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' });
  }
  if (radioSystemCapability(system, 'talker_aliases')) {
    items.push({ id: 'talker-aliases', label: 'Talker Aliases',
      href: href('radio-system', { ...values, tab: 'talker-aliases' }) });
  }
  return items;
}

function radioSystemTabs(system, active) {
  return tabs(radioSystemTabItems(system), active);
}

function entityTabs(view, system, identityKey, active, radio) {
  const values = { ...radioSystemRoute(system), identity_key: identityKey };
  const activity = { id: 'activity', label: 'Activity', href: href(view, { ...values, tab: 'activity' }),
    disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' };
  const items = [{ id: 'info', label: 'Info', href: href(view, { ...values, tab: 'info' }) }];
  if (radio && radioSystemCapability(system, 'group_identities')) {
    items.push({ id: 'groups', label: 'Groups', href: href(view, { ...values, tab: 'groups' }) });
  } else if (!radio && radioSystemCapability(system, 'radios')) {
    items.push({ id: 'radios', label: 'Radios', href: href(view, { ...values, tab: 'radios' }) });
  }
  if (radioSystemCapability(system, 'activity')) items.push(activity);
  return tabs(items, active);
}

function channelCapability(channel, capability) {
  return Boolean(channel?.capabilities?.[capability]);
}

function trunkedChannelTabItems(channel) {
  const values = { configuration_id: channel.configuration_id };
  const items = [
    { id: 'info', label: 'Info', href: href('channel', { ...values, tab: 'info' }) }
  ];
  if (channelCapability(channel, 'channels')) {
    items.push({ id: 'frequencies', label: 'Frequencies', href: href('channel', { ...values, tab: 'frequencies' }) });
  }
  if (channelCapability(channel, 'quality')) {
    items.push({ id: 'quality', label: 'Quality', href: href('channel', { ...values, tab: 'quality' }) });
  }
  if (channelCapability(channel, 'neighbors')) {
    items.push({ id: 'neighbors', label: 'Neighbors', href: href('channel', { ...values, tab: 'neighbors' }) });
  }
  if (channelCapability(channel, 'frequency_bands')) {
    items.push({ id: 'band-plan', label: 'Band Plan', href: href('channel', { ...values, tab: 'band-plan' }) });
  }
  if (channelCapability(channel, 'patch_groups')) {
    items.push({ id: 'patches', label: 'Patches', href: href('channel', { ...values, tab: 'patches' }) });
  }
  if (channelCapability(channel, 'activity')) {
    items.push({ id: 'activity', label: 'Activity', href: href('channel', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' }
    );
  }
  return items;
}

function trunkedChannelTabs(channel, active) {
  return tabs(trunkedChannelTabItems(channel), active);
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

function channelDirectoryRfIdentity(row) {
  const family = protocolFamily(row);
  if (family === 'DMR') {
    return row.site_id == null ? '' : `Site ${identifierNumber(row.site_id)}`;
  }
  if (family === 'NXDN') {
    return [
      row.site_id == null ? '' : `Site ${identifierNumber(row.site_id)}`,
      row.ran == null ? '' : `RAN ${identifierNumber(row.ran)}`
    ].filter(Boolean).join(' · ');
  }
  return [
    row.rfss == null ? '' : `RFSS ${hex(row.rfss, 2)}`,
    row.site_id == null ? '' : `Site ${hex(row.site_id, 2)}`,
    row.nac == null ? '' : `NAC ${hex(row.nac, 3)}`
  ].filter(Boolean).join(' · ');
}

function channelDirectoryDetails(row) {
  const bands = Number(row.bands);
  return [
    channelDirectoryRfIdentity(row),
    Number.isFinite(bands) && bands > 0 ? `${number(bands)} band plan${bands === 1 ? '' : 's'}` : ''
  ].filter(Boolean).join(' · ');
}

const radioSystemChannelColumns = [
  { id: 'name', label: 'Name / Site', render: channelNameSummary, className: 'alias-cell', sort: 'name',
    sortValue: channelLabel },
  { id: 'details', label: 'Details', render: channelDirectoryDetails, sortValue: channelDirectoryDetails },
  { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz', render: (row) => frequency(row.current_control_hz), className: 'numeric', sort: 'control', sortValue: (row) => Number(row.current_control_hz || 0) },
  { id: 'channels', label: 'Ch', fullLabel: 'Channels', key: 'channels', className: 'numeric', sort: 'channels' },
  { id: 'neighbors', label: 'Nbrs', fullLabel: 'Neighbors', key: 'neighbors', className: 'numeric', sort: 'neighbors' },
  { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

function dashboardChannelContext(row) {
  const values = [];
  const trunked = dashboardChannelKind(row) === 'TRUNKED';
  if (trunked) {
    values.push(radioSystemLabel(row));
    if (isP25(row) && row.rfss != null) values.push(`RFSS ${hex(row.rfss, 2)}`);
    if (row.site_id != null) {
      values.push(`Site ${isP25(row) ? hex(row.site_id, 2) : identifierNumber(row.site_id)}`);
    }
    if (protocolFamily(row) === 'NXDN' && row.ran != null) values.push(`RAN ${identifierNumber(row.ran)}`);
  }
  if (isP25(row) && row.nac != null) values.push(`NAC ${hex(row.nac, 3)}`);
  return values.join(' · ');
}

const dashboardHealthColumns = [
  { id: 'name', label: 'Channel', render: callSourceLink, className: 'alias-cell',
    sortValue: callSourceLabel },
  { id: 'mode', label: 'Mode', fullLabel: 'Protocol and Topology',
    render: dashboardMode, sortValue: dashboardModeLabel },
  { id: 'radio-context', label: 'Radio Context', render: dashboardChannelContext,
    sortValue: dashboardChannelContext },
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
  const entityTab = ['groups', 'radios'].includes(row.entity_tab) ? row.entity_tab : null;
  const target = entityReferenceAllowed(row.entity_ref) ?
    entityTarget(row.entity_ref, entityTab ? { channel: entityTab } : {}) : '';
  return target ? anchor(label, target) : label;
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
  { id: 'receiver', label: 'Conventional Channel', render: callSourceLink, className: 'alias-cell',
    sortValue: callSourceLabel },
  { id: 'mode', label: 'Mode', fullLabel: 'Protocol and Topology',
    render: dashboardMode, sortValue: dashboardModeLabel },
  { id: 'logical-calls', label: 'Logical Calls',
    render: (row) => number(row.logical_call_count), className: 'numeric',
    sortValue: (row) => Number(row.logical_call_count || 0) },
  { id: 'recorded-logical-calls', label: 'Recorded',
    render: (row) => number(row.recorded_logical_call_count), className: 'numeric',
    sortValue: (row) => Number(row.recorded_logical_call_count || 0) },
  { id: 'stream-submitted-logical-calls', label: 'Submitted', fullLabel: 'Submitted to Streamer',
    render: (row) => number(row.stream_submitted_logical_call_count), className: 'numeric',
    sortValue: (row) => Number(row.stream_submitted_logical_call_count || 0) }
];

function dashboardIdentityColumns(identityLabel) {
  return [
    { id: 'identity', label: identityLabel, render: dashboardIdentity, className: 'alias-cell',
      sortValue: (row) =>
        `${row.alias_name || row.last_talker_alias || ''}\u0000${dashboardIdentityId(row)}` },
    { id: 'system', label: 'System / Channel', render: dashboardActivitySystem, className: 'alias-cell' },
    { id: 'mode', label: 'Mode', fullLabel: 'Protocol and Topology',
      render: dashboardMode, sortValue: dashboardModeLabel },
    { id: 'logical-calls', label: 'Logical Calls',
      render: (row) => number(row.logical_call_count), className: 'numeric',
      sortValue: (row) => Number(row.logical_call_count || 0) },
    { id: 'recorded-logical-calls', label: 'Recorded',
      render: (row) => number(row.recorded_logical_call_count), className: 'numeric',
      sortValue: (row) => Number(row.recorded_logical_call_count || 0) },
    { id: 'stream-submitted-logical-calls', label: 'Submitted',
      fullLabel: 'Submitted to Streamer',
      render: (row) => number(row.stream_submitted_logical_call_count), className: 'numeric',
      sortValue: (row) => Number(row.stream_submitted_logical_call_count || 0) }
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
      count: Math.max(0, Number(row.observation_count || 0))
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
    'aria-label': `Activity mix, ${number(total)} observations`
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
  const scopedSystem = row.radio_system_key ? radioSystemLabel(row) : '';
  const label = row.system_name || row.name || scopedSystem || row.source_label ||
    row.radio_system_key || '—';
  const discriminator = String(row.radio_system_key || row.configuration_id || '').trim();
  const target = entityReferenceAllowed(row.entity_ref) ? entityTarget(row.entity_ref) : '';
  const primary = target ? anchor(label, target) : node('span', '', label);
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
  const identifier = identityNumber(row, radioDisplayId(row)) || '—';
  const reference = row.radio_entity_ref;
  const target = entityReferenceAllowed(reference) ?
    entityTarget(reference, { channel: 'radios' }) : '';
  return target ? anchor(identifier, target) : identifier;
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
  { id: 'observations', label: 'Observations', render: (row) => number(row.observation_count),
    className: 'numeric' },
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
    `Hourly ${actionLabel.toLowerCase()} observations: ${number(page.action_observation_count)}. ` +
    `Exact currently retained detail: ${number(page.retained_observation_count)} observations; ` +
    `${number(page.identified_observation_count)} identify a source radio and ` +
    `${number(page.unknown_source_observation_count)} have no source radio ID.`);
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
  let radioTitleActions = null;
  const radioTableController = {};
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
    cleanupTableLayoutMenu(radioTableController);
    radioHost.replaceChildren(node('div', 'empty', 'Select an activity type to list source radios.'));
  };

  const loadRadios = async (offset = 0, restorePagingFocus = false) => {
    if (!selectedAction || !radioHost || !radioStatus || !radioTitle) return;
    selectedOffset = Math.max(0, Number(offset) || 0);
    const sequence = ++radioSequence;
    const action = selectedAction;
    const actionLabel = selectedActionLabel || semanticLabel(action);
    radioTitle.textContent = `${actionLabel} · Source radios`;
    if (!capabilityAllowed(ACCESS_CAPABILITIES.RADIO)) {
      radioRequest?.controller.abort();
      radioRequest?.unlink();
      radioRequest = null;
      radioStatus.textContent = 'Radio access is required to list source radios.';
      radioHost.setAttribute('aria-busy', 'false');
      cleanupTableLayoutMenu(radioTableController);
      radioHost.replaceChildren(node('div', 'empty',
        'Radio access is required to list source radios.'));
      return;
    }
    const request = nextRequest(radioRequest);
    radioRequest = request;
    radioStatus.textContent = `Loading ${actionLabel.toLowerCase()} source radios.`;
    radioHost.setAttribute('aria-busy', 'true');
    cleanupTableLayoutMenu(radioTableController);
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
          {
            type: 'dashboard-activity-radios', sortable: false,
            controller: radioTableController, layoutMenuHost: radioTitleActions
          }),
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
      cleanupTableLayoutMenu(radioTableController);
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
    radioTitleActions = sectionActionHost();
    const radios = section('Source radios', radioBody, radioTitleActions);
    radioTitle = radios.querySelector('.section-title').firstChild;
    host.replaceChildren(mix, radios);
    showRadioPrompt();
  };

  const loadSummary = async (buttons = rangeControl.buttons) => {
    const sequence = ++summarySequence;
    radioSequence += 1;
    radioRequest?.controller.abort();
    radioRequest?.unlink();
    radioRequest = null;
    cleanupTableLayoutMenu(radioTableController);
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

const groupIdentityColumns = [
  { id: 'group-identity-id', label: 'ID', render: (row) => groupIdentityLink(row), className: 'numeric', sort: 'group_identity', sortValue: (row) => Number(row.native_id) },
  { id: 'group-identity-kind', label: 'Kind', render: (row) => groupIdentityLabel(row) },
  { id: 'group-identity-name', label: 'Alias', fullLabel: 'Group Alias', render: (row) => groupIdentityAliasLink(row), className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
  { id: 'group-identity-description', label: 'Description', key: 'alias_description', className: 'alias-cell' },
  { id: 'alias-group', label: 'Alias Group', key: 'alias_group', className: 'alias-cell', sort: 'alias_group' },
  { id: 'logical-calls', label: 'Logical Calls', render: (row) => number(row.logical_call_count), className: 'numeric', sort: 'logical_call_count', sortValue: (row) => Number(row.logical_call_count || 0) },
  { id: 'recorded-logical-calls', label: 'Rec', fullLabel: 'Recorded Logical Calls', render: (row) => number(row.recorded_logical_call_count), className: 'numeric', sort: 'recorded_logical_call_count', sortValue: (row) => Number(row.recorded_logical_call_count || 0) },
  { id: 'stream-submitted-logical-calls', label: 'Submitted', fullLabel: 'Submitted to Streamer', render: (row) => number(row.stream_submitted_logical_call_count), className: 'numeric', sort: 'stream_submitted_logical_call_count', sortValue: (row) => Number(row.stream_submitted_logical_call_count || 0) },
  { id: 'encrypted-logical-calls', label: 'Enc', render: (row) => number(row.encrypted_logical_call_count), className: 'numeric encrypted', sort: 'encrypted_logical_call_count', sortValue: (row) => Number(row.encrypted_logical_call_count || 0) },
  { id: 'signaling', label: 'Signaling', fullLabel: 'Signaling observations',
    render: groupIdentitySignaling, className: 'numeric', sort: 'signaling_observation_count',
    sortValue: groupIdentitySignalingSortValue },
  { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
];

function radioSystemRadioColumns(system) {
  const columns = [
    { id: 'radio', label: 'ID', render: (row) => radioLink(row), className: 'numeric', sort: 'id', sortValue: (row) => Number(row.native_id) },
    { id: 'alias', label: 'Alias', render: (row) => aliasLabel(row) ? radioLink(row, undefined, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel }
  ];
  if (radioSystemCapability(system, 'talker_aliases')) {
    columns.push({ id: 'talker-alias', label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias',
      className: 'alias-cell', sort: 'talker_alias' });
  }
  if (radioSystemCapability(system, 'current_affiliations')) {
    columns.push({ id: 'affiliation', label: 'Affiliation', fullLabel: 'Current Talkgroup Affiliation',
      render: affiliationTalkgroupCell, className: 'alias-cell', sort: 'affiliated_talkgroup',
      sortValue: affiliationTalkgroupSortValue });
  }
  if (radioSystemCapability(system, 'radio_channel_presence')) {
    columns.push({ id: 'confirmed-channel', label: 'Last Confirmed Channel', render: channelPresenceCell,
      className: 'alias-cell', sort: 'channel', sortValue: presenceChannelSortValue });
  }
  columns.push(
    { id: 'logical-calls', label: 'Logical Calls', render: (row) => number(row.logical_call_count),
      className: 'numeric', sort: 'logical_call_count',
      sortValue: (row) => Number(row.logical_call_count || 0) },
    { id: 'encrypted-logical-calls', label: 'Enc',
      render: (row) => number(row.encrypted_logical_call_count),
      className: 'numeric encrypted', sort: 'encrypted_logical_call_count',
      sortValue: (row) => Number(row.encrypted_logical_call_count || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen',
      sortValue: (row) => Number(row.last_seen_ms || 0) }
  );
  return columns;
}

function radioTableType(baseType, columns) {
  const ids = new Set(tableLayouts.schema(columns));
  const variant = [
    ids.has('talker-alias') ? 'talker-alias' : null,
    ids.has('affiliation') ? 'affiliation' : null,
    ids.has('confirmed-channel') ? 'channel' : null
  ].filter(Boolean).join('-') || 'base';
  return tableLayouts.tableId(`${baseType}.${variant}`);
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
      ['Radio Systems', counts.radio_systems],
      ['Trunked Channels', counts.trunked_channels],
      ['Conventional Channels', counts.conventional_channels]
    ]));
    content.append(tableSection('Recent Channels', dashboard.recent_channels || [],
      dashboardHealthColumns, 'No channels recorded', { type: 'dashboard-channels' }));
    return;
  }

  if (tab === 'activity') {
    await renderDashboardActivity(renderContext);
    return;
  }

  content.append(dashboardSummarySection('Logical Call Totals · Last 24 Hours', [
    [dashboardMetricLabel(callActivity, 'logical_call_count', 'Logical Calls'),
      callTotals.logical_call_count, dashboardMetricDisplay(callActivity, 'logical_call_count')],
    [dashboardMetricLabel(callActivity, 'recorded_logical_call_count', 'Recorded'),
      callTotals.recorded_logical_call_count,
      dashboardMetricDisplay(callActivity, 'recorded_logical_call_count')],
    [dashboardMetricLabel(callActivity, 'stream_submitted_logical_call_count', 'Submitted'),
      callTotals.stream_submitted_logical_call_count,
      dashboardMetricDisplay(callActivity, 'stream_submitted_logical_call_count')]
  ]));
  content.append(section('Call Activity · Last 24 Hours', dashboardCallActivityChart(callActivity)));
  const sourceRows = Array.isArray(dashboard.source_activity_24h) ? dashboard.source_activity_24h :
    dashboard.source_activity_24h?.rows || [];
  content.append(tableSection('Logical Calls by Conventional Channel · Last 24 Hours', sourceRows,
    dashboardCallSourceColumns, 'No call activity recorded', { type: 'dashboard-call-sources' }));
  const destinations = tableSection('Top Destinations · Last 24 Hours', dashboard.top_destinations || [],
    dashboardIdentityColumns('Destination'), 'No call destinations recorded',
    { type: 'dashboard-destinations' });
  const sources = tableSection('Top Sources · Last 24 Hours', dashboard.top_sources || [],
    dashboardIdentityColumns('Source'), 'No call sources recorded', { type: 'dashboard-sources' });
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

function liveDetailSelectionUnchanged(previous, next) {
  return ['kind', 'role', 'logicalKey', 'transportKey', 'rowKey', 'configurationId',
    'bindingFrequencyHz', 'bindingTimeslot', 'label', 'channelLabel']
    .every((field) => previous?.[field] === next?.[field]);
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
  const configured = Math.trunc(Number(activeUserPreferences().presentation.live_detail_row_limit));
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
  const messagesTable = table([], [
    { id: 'time', label: 'Time', render: (message) => {
      const date = new Date(Number(message.timestamp_ms));
      const text = Number.isFinite(date.getTime()) ? date.toLocaleTimeString([], {
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      }) : '';
      const value = node('span', '', text);
      if (text) value.title = exactDateTime(message.timestamp_ms);
      return value;
    } },
    { id: 'context', label: 'Context', render: (message) => [
      message.protocol,
      timeslotLabel(message.timeslot)
    ].filter(Boolean).join(' · ') },
    { id: 'message', label: 'Message', className: 'live-message-text', render: (message) => {
      const value = node('span', '', message.text || '');
      value.title = message.text || '';
      return value;
    } }
  ], 'Select a live row above', {
    type: 'live-messages', sortable: false, rowKey: (message) => message.message_id,
    rowClass: (message) => message.valid ? '' : 'message-invalid',
    wrapperClass: 'live-messages-scroll', tableClass: 'live-messages-table',
    layoutMenuHost: toolbar
  });
  pane.append(toolbar, gap, messagesTable);

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
    const rows = order.map((id) => messages.get(id)).filter((message) => message && matches(message))
      .slice(0, liveDetailMatchingRowLimit());
    messagesTable.tableController.setEmptyText(!selection ? 'Select a live row above' :
      (selection.bindingFrequencyHz ? 'No matching messages received since this tab was opened' :
        'Select an active channel'));
    messagesTable.tableController.replaceRows(selection ? rows : []);
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
const TUNER_SPECTRUM_FLOOR_PREFERENCE = 'floor_db';
const TUNER_SPECTRUM_CEILING_PREFERENCE = 'ceiling_db';
const TUNER_WATERFALL_SPEED_PREFERENCE = 'waterfall_speed';
const TUNER_SPECTRUM_SNAP_PREFERENCE = 'snap_frequency';
const TUNER_SPECTRUM_SMOOTH_PREFERENCE = 'smooth_fft';
const TUNER_SPECTRUM_IDLE_PREFERENCE = 'show_idle_channels';
const TUNER_WATERFALL_CHANNELS_PREFERENCE = 'highlight_waterfall_channels';
const TUNER_SPECTRUM_PROFILE_PREFERENCE = 'profile';
let tunerSpectrumSessionTarget = '';
const TUNER_SPECTRUM_PROFILES = Object.freeze({
  efficient: Object.freeze({ fftSize: 2048, fps: 5 }),
  balanced: Object.freeze({ fftSize: 8192, fps: 10 }),
  'high-detail': Object.freeze({ fftSize: 16384, fps: 20 }),
  'maximum-detail': Object.freeze({ fftSize: 32768, fps: 20 })
});
const TUNER_CHANNEL_VISUAL_BANDWIDTH_HZ = 25_000;
const TUNER_CHANNEL_MINIMUM_WIDTH_PX = 3.5;
const TUNER_CHANNEL_MAXIMUM_WIDTH_PX = 14;
const TUNER_ACTIVITY_PRIORITY = Object.freeze({
  ENCRYPTED: 6, CALL: 5, DATA: 4, CONTROL: 3, ACTIVE: 2, IDLE: 1
});
const TUNER_ACTIVITY_LABELS = Object.freeze({
  ENCRYPTED: 'Encrypted voice',
  CALL: 'Voice call',
  DATA: 'Data activity',
  CONTROL: 'Control channel',
  ACTIVE: 'Other activity',
  IDLE: 'Idle channel'
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
  const channelLabel = (value) => {
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
      const title = isTrunked ? channelLabel(row) || row.description || row.system_name :
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
        addFact('site', 'Site', channelLabel(row), true);
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
              const loadedSite = channelLabel(loaded.site) || 'Unknown site';
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
  const value = Number(activeUserPreferences().tuner[key]);
  return Number.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
}

function storeTunerNumber(key, value) {
  void settleUserPreferenceMutation((preferences) => { preferences.tuner[key] = Number(value); });
}

function tunerStoredBoolean(key, fallback) {
  const value = activeUserPreferences().tuner[key];
  return typeof value === 'boolean' ? value : fallback;
}

function storeTunerBoolean(key, value) {
  void settleUserPreferenceMutation((preferences) => { preferences.tuner[key] = Boolean(value); });
}

function tunerStoredChoice(key, fallback, choices) {
  const value = key === 'session-target' ? tunerSpectrumSessionTarget : activeUserPreferences().tuner[key];
  return choices.includes(value) ? value : fallback;
}

function storeTunerChoice(key, value) {
  if (key === 'session-target') tunerSpectrumSessionTarget = String(value);
  else void settleUserPreferenceMutation((preferences) => { preferences.tuner[key] = String(value); });
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

function decodeSpectrumSnapPresetDocument(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      !Number.isInteger(value.revision) || value.revision < 1 ||
      typeof value.country_code !== 'string' || !value.country_code ||
      typeof value.country_label !== 'string' || !value.country_label ||
      !Array.isArray(value.countries) || !Array.isArray(value.scopes)) {
    throw new Error('The server returned invalid spectrum snap presets.');
  }
  const countries = value.countries.map((country) => {
    if (!country || typeof country.code !== 'string' || !country.code ||
        typeof country.label !== 'string' || !country.label) throw new Error('Invalid spectrum snap country.');
    return Object.freeze({ code: country.code, label: country.label });
  });
  const scopes = value.scopes.map((scope) => {
    if (!scope || typeof scope.id !== 'string' || !scope.id ||
        typeof scope.label !== 'string' || !scope.label ||
        !Number.isSafeInteger(scope.min_hz) || !Number.isSafeInteger(scope.max_hz) ||
        scope.min_hz <= 0 || scope.max_hz < scope.min_hz) {
      throw new Error('Invalid spectrum frequency scope.');
    }
    let snap = null;
    if (scope.snap !== null && scope.snap !== undefined) {
      const kind = String(scope.snap?.kind || '');
      const frequenciesHz = Array.isArray(scope.snap?.frequencies_hz) ?
        scope.snap.frequencies_hz.map(Number) : [];
      if (!['RASTER', 'CHANNELS'].includes(kind) ||
          !Number.isSafeInteger(scope.snap.origin_hz) || !Number.isSafeInteger(scope.snap.step_hz) ||
          !Number.isSafeInteger(scope.snap.match_tolerance_hz) ||
          !frequenciesHz.every((frequency) => Number.isSafeInteger(frequency) && frequency > 0) ||
          (kind === 'RASTER' && (scope.snap.origin_hz <= 0 || scope.snap.step_hz <= 0 ||
            scope.snap.match_tolerance_hz !== 0 || frequenciesHz.length)) ||
          (kind === 'CHANNELS' && (scope.snap.origin_hz !== 0 || scope.snap.step_hz !== 0 ||
            scope.snap.match_tolerance_hz <= 0 || !frequenciesHz.length))) {
        throw new Error('Invalid spectrum frequency snap rule.');
      }
      snap = Object.freeze({ kind, originHz: scope.snap.origin_hz, stepHz: scope.snap.step_hz,
        matchToleranceHz: scope.snap.match_tolerance_hz, frequenciesHz: Object.freeze(frequenciesHz) });
    }
    return Object.freeze({ id: scope.id, label: scope.label,
      minHz: scope.min_hz, maxHz: scope.max_hz, snap });
  });
  if (!countries.some((country) => country.code === value.country_code)) {
    throw new Error('The active spectrum snap country is unavailable.');
  }
  return Object.freeze({ revision: value.revision, countryCode: value.country_code,
    countryLabel: value.country_label, countries: Object.freeze(countries), scopes: Object.freeze(scopes) });
}

function tunerScopeSnapCandidate(scope, frequencyHz) {
  if (!scope?.snap || !Number.isFinite(Number(frequencyHz))) return null;
  if (scope.snap.kind === 'RASTER') {
    if (frequencyHz < scope.minHz || frequencyHz > scope.maxHz) return null;
    const snappedHz = scope.snap.originHz +
      Math.round((frequencyHz - scope.snap.originHz) / scope.snap.stepHz) * scope.snap.stepHz;
    return snappedHz < scope.minHz || snappedHz > scope.maxHz ? null : snappedHz;
  }
  if (frequencyHz < scope.minHz - scope.snap.matchToleranceHz ||
      frequencyHz > scope.maxHz + scope.snap.matchToleranceHz) return null;
  let nearestHz = null;
  let distanceHz = Number.POSITIVE_INFINITY;
  scope.snap.frequenciesHz.forEach((candidateHz) => {
    const candidateDistance = Math.abs(candidateHz - frequencyHz);
    if (candidateDistance < distanceHz) {
      nearestHz = candidateHz;
      distanceHz = candidateDistance;
    }
  });
  return distanceHz <= scope.snap.matchToleranceHz ? nearestHz : null;
}

function tunerSnapMatches(frequencyHz, scopes) {
  const matches = (scopes || []).map((scope) => ({
    scope, frequencyHz: tunerScopeSnapCandidate(scope, frequencyHz)
  })).filter((match) => match.frequencyHz !== null);
  if (!matches.length) return [];
  const smallestSpanHz = Math.min(...matches.map((match) => match.scope.maxHz - match.scope.minHz));
  return matches.filter((match) => match.scope.maxHz - match.scope.minHz === smallestSpanHz);
}

function tunerSnapFrequency(frequencyHz, scopes = []) {
  if (!Number.isFinite(Number(frequencyHz))) return null;
  const matches = tunerSnapMatches(frequencyHz, scopes);
  if (!matches.length) return null;
  matches.sort((left, right) => Math.abs(left.frequencyHz - frequencyHz) -
    Math.abs(right.frequencyHz - frequencyHz));
  const selected = matches[0];
  return { source: selected.scope.snap.kind.toLowerCase(), frequencyHz: selected.frequencyHz,
    scope: selected.scope, label: selected.scope.label };
}

function tunerResolvedScopeSegments(viewport, scopes = []) {
  if (!viewport || !(viewport.endHz > viewport.startHz)) return [];
  const intersecting = scopes.filter((scope) =>
    scope.maxHz > viewport.startHz && scope.minHz < viewport.endHz);
  const boundaries = [...new Set([viewport.startHz, viewport.endHz,
    ...intersecting.flatMap((scope) => [Math.max(viewport.startHz, scope.minHz),
      Math.min(viewport.endHz, scope.maxHz)])])].sort((left, right) => left - right);
  const segments = [];
  for (let index = 1; index < boundaries.length; index += 1) {
    const startHz = boundaries[index - 1];
    const endHz = boundaries[index];
    if (!(endHz > startHz)) continue;
    const midpointHz = startHz + (endHz - startHz) / 2;
    const covering = intersecting.filter((scope) =>
      scope.minHz <= midpointHz && scope.maxHz >= midpointHz);
    if (!covering.length) continue;
    const smallestSpanHz = Math.min(...covering.map((scope) => scope.maxHz - scope.minHz));
    const winners = covering.filter((scope) => scope.maxHz - scope.minHz === smallestSpanHz)
      .sort((left, right) => left.label.localeCompare(right.label) || left.id.localeCompare(right.id));
    const key = winners.map((scope) => scope.id).join('|');
    const previous = segments.at(-1);
    if (previous?.key === key && previous.endHz === startHz) previous.endHz = endHz;
    else segments.push({ key, startHz, endHz, scopes: winners });
  }
  return segments;
}

function tunerSpectrumPanel(snapPresetDocument) {
  const frequencyScopes = snapPresetDocument?.scopes || [];
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
  const zoomIn = iconButton('icon-zoom-in', 'Zoom in');
  zoomIn.disabled = true;
  const zoomOut = iconButton('icon-zoom-out', 'Zoom out');
  zoomOut.disabled = true;
  const resetZoom = iconButton('icon-replay', 'Reset zoom');
  resetZoom.disabled = true;
  const pause = iconButton('icon-pause', 'Pause');
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
  let initialFloor = tunerStoredNumber(TUNER_SPECTRUM_FLOOR_PREFERENCE,
    TUNER_SPECTRUM_DEFAULT_FLOOR_DB, TUNER_SPECTRUM_MINIMUM_DISPLAY_DB,
    TUNER_SPECTRUM_MAXIMUM_DISPLAY_DB - TUNER_SPECTRUM_MINIMUM_DISPLAY_SPAN_DB);
  let initialCeiling = tunerStoredNumber(TUNER_SPECTRUM_CEILING_PREFERENCE,
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
  speedInput.value = String(tunerStoredNumber(TUNER_WATERFALL_SPEED_PREFERENCE, 1, 0.25, 4));
  speedInput.id = 'tuner-waterfall-speed';
  const speedValue = node('output', '', `${Number(speedInput.value).toFixed(2)}×`);
  speedValue.htmlFor = speedInput.id;
  speedControl.append(node('span', '', 'Waterfall speed'), speedInput, speedValue);
  const snapControl = node('label', 'tuner-spectrum-toggle-control');
  const snapInput = node('input');
  snapInput.type = 'checkbox';
  snapInput.checked = tunerStoredBoolean(TUNER_SPECTRUM_SNAP_PREFERENCE, true);
  snapControl.title = 'Snap the cursor to the nearest preset frequency in supported bands.';
  snapControl.append(snapInput, node('span', '', 'Snap frequency'));
  const smoothControl = node('label', 'tuner-spectrum-toggle-control');
  const smoothInput = node('input');
  smoothInput.type = 'checkbox';
  smoothInput.checked = tunerStoredBoolean(TUNER_SPECTRUM_SMOOTH_PREFERENCE, true);
  smoothControl.title = 'Average successive frames to make the FFT trace steadier.';
  smoothControl.append(smoothInput, node('span', '', 'Smooth FFT'));
  const idleChannelsControl = node('label', 'tuner-spectrum-toggle-control');
  const idleChannelsInput = node('input');
  idleChannelsInput.type = 'checkbox';
  idleChannelsInput.checked = tunerStoredBoolean(TUNER_SPECTRUM_IDLE_PREFERENCE, false);
  idleChannelsControl.title = 'Outline idle channels from the current activity feed on the FFT. ' +
    'Markers do not indicate allocation to this tuner.';
  idleChannelsControl.append(idleChannelsInput, node('span', '', 'Show idle channel markers'));
  const waterfallChannelsControl = node('label', 'tuner-spectrum-toggle-control');
  const waterfallChannelsInput = node('input');
  waterfallChannelsInput.type = 'checkbox';
  waterfallChannelsInput.checked = tunerStoredBoolean(TUNER_WATERFALL_CHANNELS_PREFERENCE, false);
  waterfallChannelsControl.title = 'Highlight active channels while the pointer is over the waterfall.';
  waterfallChannelsControl.append(waterfallChannelsInput,
    node('span', '', 'Highlight channels on waterfall when hovered'));
  const liveActivityAllowed = capabilityAllowed(ACCESS_CAPABILITIES.LIVE);
  waterfallChannelsControl.hidden = !liveActivityAllowed;
  idleChannelsControl.hidden = !liveActivityAllowed;
  const fftOptions = node('fieldset', 'tuner-spectrum-display-section');
  fftOptions.append(node('legend', '', 'FFT'), smoothControl, idleChannelsControl);
  const waterfallOptions = node('fieldset', 'tuner-spectrum-display-section');
  waterfallOptions.append(node('legend', '', 'Waterfall'), speedControl, waterfallChannelsControl);
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
  profileSelect.value = tunerStoredChoice(TUNER_SPECTRUM_PROFILE_PREFERENCE, 'balanced',
    Object.keys(TUNER_SPECTRUM_PROFILES));
  profileControl.append(node('span', '', 'Profile'), profileSelect);
  const profileWarning = node('p', 'tuner-spectrum-control-help',
    'Higher-detail profiles use more CPU and may affect decoding on lower-end systems. All profiles use 8-bit spectrum data.');
  profilePanel.append(profileControl, profileWarning);
  optionsPanel.append(rangeControl, rangeHelp, snapControl, fftOptions, waterfallOptions, profilePanel);
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
  const fftBandRail = node('div', 'tuner-spectrum-band-rail');
  fftBandRail.setAttribute('role', 'img');
  fftBandRail.setAttribute('aria-label', `${snapPresetDocument.countryLabel} frequency bands`);
  spectrum.card.append(fftBandRail);
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
  let pageFocused = document.hasFocus();
  let pageSuspended = false;
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
  let frequencyBandSignature = '';

  function formatScopeFrequency(frequencyHz) {
    const megahertz = frequencyHz / 1_000_000;
    return `${megahertz.toFixed(megahertz >= 100 ? 3 : 4).replace(/\.0+$/, '')} MHz`;
  }

  function renderFrequencyBands() {
    const segments = tunerResolvedScopeSegments(viewport, frequencyScopes);
    const signature = JSON.stringify([viewport?.startHz, viewport?.endHz,
      segments.map((segment) => [segment.startHz, segment.endHz,
        segment.scopes.map((scope) => scope.id)])]);
    if (signature === frequencyBandSignature) return;
    frequencyBandSignature = signature;
    if (!viewport || !segments.length) {
      fftBandRail.setAttribute('aria-label', `${snapPresetDocument.countryLabel}: no frequency band in view`);
      fftBandRail.replaceChildren(node('span', 'tuner-spectrum-band-empty',
        `${snapPresetDocument.countryLabel} · No frequency band in view`));
      return;
    }
    fftBandRail.setAttribute('aria-label', `${snapPresetDocument.countryLabel} frequency bands: ${
      segments.map((segment) => `${segment.scopes.map((scope) => scope.label).join(' and ')}, ${
        formatScopeFrequency(segment.startHz)} to ${formatScopeFrequency(segment.endHz)}`).join('; ')}`);
    const spanHz = viewport.endHz - viewport.startHz;
    const markers = segments.map((segment) => {
      const marker = node('div', 'tuner-spectrum-band-segment');
      const paletteIndex = [...segment.key].reduce((hash, character) =>
        (hash * 31 + character.codePointAt(0)) >>> 0, 0) % 4;
      marker.classList.add(`palette-${paletteIndex}`);
      const labels = segment.scopes.map((scope) => scope.label);
      const start = formatScopeFrequency(segment.startHz);
      const end = formatScopeFrequency(segment.endHz);
      const visibleStart = segment.startHz === viewport.startHz &&
        segment.scopes.some((scope) => scope.minHz < viewport.startHz) ? '←' : start;
      const visibleEnd = segment.endHz === viewport.endHz &&
        segment.scopes.some((scope) => scope.maxHz > viewport.endHz) ? '→' : end;
      const fullBounds = segment.scopes.map((scope) => `${scope.label} ${formatScopeFrequency(scope.minHz)}–${
        formatScopeFrequency(scope.maxHz)}`).join('; ');
      marker.style.left = `${((segment.startHz - viewport.startHz) / spanHz * 100).toFixed(4)}%`;
      marker.style.width = `${((segment.endHz - segment.startHz) / spanHz * 100).toFixed(4)}%`;
      marker.setAttribute('aria-label', `${labels.join(' and ')}, ${start} to ${end}`);
      marker.title = fullBounds;
      marker.append(node('span', 'tuner-spectrum-band-bound', visibleStart),
        node('span', 'tuner-spectrum-band-label', labels.join(' / ')),
        node('span', 'tuner-spectrum-band-bound', visibleEnd));
      return marker;
    });
    fftBandRail.replaceChildren(...markers);
  }

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
      window.removeEventListener('blur', onBlur);
      window.removeEventListener('focus', onFocus);
      window.removeEventListener('pagehide', onPageHide);
      window.removeEventListener('pageshow', onPageShow);
      document.removeEventListener('freeze', onFreeze);
      document.removeEventListener('resume', onResume);
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
    return closed && typeof closed.then === 'function' ? closed :
      (connection.whenClosed?.() || Promise.resolve());
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
  const shouldRun = () => !disposed && !paused && pageFocused && !pageSuspended &&
    !document.hidden && selectedTargetId();

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
      renderFrequencyBands();
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
    if (!shouldRun() || stream) return streamRelease;
    const openingEpoch = streamEpoch;
    setStatus(refining ? 'Refining' : 'Connecting');
    if (!stream && !refining) setOverlay('Waiting for tuner data…');
    return streamRelease.then(() => {
      if (!shouldRun() || stream || openingEpoch !== streamEpoch) return;
      const epoch = ++streamEpoch;
      let candidate = null;
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
    });
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
      const released = closeStreams();
      closeActiveChannels();
      if (disposed) return released;
      setRefining(false);
      if (paused) setStatus('Paused');
      else if (pageSuspended || document.hidden) setStatus('Hidden');
      else if (!pageFocused) setStatus('Unfocused');
      else setStatus('Waiting');
      return released;
    }
    connectActiveChannels();
    //A drag owns the client-side viewport until pointer release.  Reopening here would allow incoming frames to
    //replace that viewport and discard part of the user's pan before the refined request is sent.
    if (!stream && !drag) return openDiagnosticStream();
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
    renderFrequencyBands();
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
    const snap = snapInput.checked ? tunerSnapFrequency(rawFrequencyHz, frequencyScopes) : null;
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
    const snap = snapInput.checked ? tunerSnapFrequency(pointerHz, frequencyScopes) : null;
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

  function tunerActivityStatus(row, includeIdle = false) {
    const status = String(row?.status || '').toUpperCase();
    const tags = channelTagSet(row?.tags);
    if (tags.has('CURRENT_CONTROL')) return 'CONTROL';
    if (tags.has('ALTERNATE_CONTROL')) return null;
    if (status === 'IDLE') return includeIdle ? 'IDLE' : null;
    return TUNER_ACTIVITY_PRIORITY[status] ? status : null;
  }

  function updateSpectrumActivityTable(table) {
    const id = String(table?.table_id || '');
    if (!id) return;
    // Retain idle rows so the local display switch takes effect without reconnecting the feed.
    const rows = (Array.isArray(table?.rows) ? table.rows : []).filter((row) => tunerActivityStatus(row, true));
    if (rows.length) activeChannelTables.set(id, { ...table, rows });
    else activeChannelTables.delete(id);
  }

  function activeCarriers(includeIdle = false) {
    if (!viewport) return [];
    const byFrequency = new Map();
    activeChannelTables.forEach((table) => {
      const tableChannelName = String(table?.channel_name || '').trim();
      const tableSystemName = String(table?.system_name || '').trim();
      const tableSiteName = String(table?.site_name || '').trim();
      const tableIdentifiers = Array.isArray(table?.identifiers) ? table.identifiers : [];
      (Array.isArray(table?.rows) ? table.rows : []).forEach((row) => {
        const frequencyHz = Number(row?.frequency_hz);
        const status = tunerActivityStatus(row, includeIdle);
        if (!Number.isFinite(frequencyHz) || frequencyHz < viewport.startHz ||
            frequencyHz > viewport.endHz || !status) return;
        const decorated = { ...row, status, frequencyHz, tableChannelName, tableSystemName, tableSiteName,
          tableIdentifiers };
        let carrier = byFrequency.get(frequencyHz);
        if (!carrier) {
          carrier = { frequencyHz, status, rows: [] };
          byFrequency.set(frequencyHz, carrier);
        } else {
          // Active details win over idle entries at the same frequency, regardless of arrival order.
          if (status === 'IDLE' && carrier.status !== 'IDLE') return;
          if (carrier.status === 'IDLE' && status !== 'IDLE') carrier.rows = [];
          if (TUNER_ACTIVITY_PRIORITY[status] > TUNER_ACTIVITY_PRIORITY[carrier.status]) carrier.status = status;
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

    const identityRows = rows.filter((row) => !isAnalogChannel(row));
    const targetForms = [...new Set(identityRows.filter((row) => row.target_id)
      .map((row) => String(row.target_form || '').toUpperCase()))];
    (targetForms.length ? targetForms : ['']).forEach((form) => add(targetIdentifierLabel(form),
      activityValues(identityRows.filter((row) => String(row.target_form || '').toUpperCase() === form ||
        (!form && !row.target_form)), (row) => row.target_id)));
    add('Target alias', activityValues(identityRows, (row) => activityAliasLabel(row, 'target')));
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
    const carriers = activeCarriers(idleChannelsInput.checked);
    const visibleSpanHz = Math.max(1, viewport.endHz - viewport.startHz);
    const waterfallPlotWidth = Math.max(0, waterfall.host.getBoundingClientRect().width);
    const waterfallFlagWidth = Math.max(TUNER_CHANNEL_MINIMUM_WIDTH_PX,
      Math.min(TUNER_CHANNEL_MAXIMUM_WIDTH_PX,
        waterfallPlotWidth * TUNER_CHANNEL_VISUAL_BANDWIDTH_HZ / visibleSpanHz));
    const signature = JSON.stringify([viewport.startHz, viewport.endHz,
      idleChannelsInput.checked, waterfallChannelsInput.checked, waterfallFlagWidth,
      carriers.map((carrier) => [carrier.frequencyHz, carrier.status, activeCarrierDescription(carrier)])]);
    if (signature === activeFlagSignature) return;
    if (hoverFlag) hideCursor();
    activeFlagSignature = signature;
    const createFlags = (waterfallLayer) => carriers.filter((carrier) =>
      !waterfallLayer || carrier.status !== 'IDLE').map((carrier) => {
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
    renderFrequencyBands();
  }

  targetSelect.addEventListener('change', () => {
    storeTunerChoice('session-target', targetSelect.value);
    closeStreams();
    closeActiveChannels();
    resetViewportForTarget();
    resetPlots('Waiting for tuner data…');
    sync();
  });
  function applySelectedProfile() {
    spectrumProfile = profileSelect.value;
    storeTunerChoice(TUNER_SPECTRUM_PROFILE_PREFERENCE, spectrumProfile);
    if (shouldRun()) queueViewportUpdate(true);
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
    setIconButton(pause, paused ? 'icon-play' : 'icon-pause', paused ? 'Resume' : 'Pause');
    pause.setAttribute('aria-pressed', String(paused));
    sync();
    setReadouts(true);
  });
  function updateDisplayRange(changedHandle = '', persist = false) {
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
    if (persist) {
      void settleUserPreferenceMutation((preferences) => {
        preferences.tuner.floor_db = dbFloor;
        preferences.tuner.ceiling_db = dbCeiling;
      });
    }
    restoreWaterfallHistory();
    if (!refining) scheduleDraw();
  }
  floorInput.addEventListener('input', () => updateDisplayRange('floor'));
  ceilingInput.addEventListener('input', () => updateDisplayRange('ceiling'));
  floorInput.addEventListener('change', () => updateDisplayRange('floor', true));
  ceilingInput.addEventListener('change', () => updateDisplayRange('ceiling', true));
  updateDisplayRange();
  speedInput.addEventListener('input', () => {
    const candidate = Number(speedInput.value);
    if (!Number.isFinite(candidate)) return;
    waterfallSpeed = Math.max(0.25, Math.min(4, candidate));
    speedValue.textContent = `${waterfallSpeed.toFixed(2)}×`;
  });
  speedInput.addEventListener('change', () =>
    storeTunerNumber(TUNER_WATERFALL_SPEED_PREFERENCE, waterfallSpeed));
  snapInput.addEventListener('change', () => {
    storeTunerBoolean(TUNER_SPECTRUM_SNAP_PREFERENCE, snapInput.checked);
    if (hoverRatio !== null) updateCursor(hoverRatio);
  });
  smoothInput.addEventListener('change', () => {
    storeTunerBoolean(TUNER_SPECTRUM_SMOOTH_PREFERENCE, smoothInput.checked);
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
    storeTunerBoolean(TUNER_WATERFALL_CHANNELS_PREFERENCE, waterfallChannelsInput.checked);
    activeFlagSignature = '';
    renderActiveChannels();
  });
  idleChannelsInput.addEventListener('change', () => {
    storeTunerBoolean(TUNER_SPECTRUM_IDLE_PREFERENCE, idleChannelsInput.checked);
    renderActiveChannels();
  });
  [spectrum.canvas, waterfall.canvas].forEach(addPlotInteractions);
  const onVisibilityChange = () => {
    pageFocused = document.hasFocus();
    sync();
  };
  const onBlur = () => {
    pageFocused = false;
    sync();
  };
  const onFocus = () => {
    pageFocused = true;
    pageSuspended = false;
    sync();
  };
  const onPageHide = () => {
    pageFocused = false;
    pageSuspended = true;
    sync();
  };
  const onPageShow = () => {
    pageSuspended = false;
    pageFocused = document.hasFocus();
    sync();
  };
  const onFreeze = () => {
    pageSuspended = true;
    sync();
  };
  const onResume = () => {
    pageSuspended = false;
    pageFocused = document.hasFocus();
    sync();
  };
  const onResize = () => {
    renderActiveChannels();
    if (hoverFlag) positionCursorPopup(hoverFlag);
    else positionCursorPopup();
    if (!refining) scheduleDraw();
  };
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('blur', onBlur);
  window.addEventListener('focus', onFocus);
  window.addEventListener('pagehide', onPageHide);
  window.addEventListener('pageshow', onPageShow);
  document.addEventListener('freeze', onFreeze);
  document.addEventListener('resume', onResume);
  window.addEventListener('resize', onResize);
  resetPlots('Loading tuners…');
  renderFrequencyBands();

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
    targetSelect.value = tunerStoredChoice('session-target', targets[0].id,
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

  const eventsTable = table([], [
    { id: 'time', label: 'Time', className: 'live-event-time', render: (event) => {
      const started = new Date(Number(event.time_start_ms));
      const text = Number.isFinite(started.getTime()) ? started.toLocaleTimeString([], {
        hour: '2-digit', minute: '2-digit', second: '2-digit'
      }) : '';
      const value = node('strong', '', text);
      if (text) value.title = exactDateTime(event.time_start_ms);
      return value;
    } },
    { id: 'duration', label: 'Duration', className: 'live-event-duration', render: (event) => {
      const text = liveEventDuration(event.duration_ms);
      const value = node('strong', 'live-event-duration-value', text);
      value.title = `Duration ${text}`;
      return value;
    } },
    { id: 'event', label: 'Event', className: 'live-event-stack', render: (event) => {
      const value = node('span', 'live-event-stack');
      value.append(node('strong', '', event.event_label || event.event_type || 'Event'));
      if (event.protocol) value.append(node('small', '', event.protocol));
      return value;
    } },
    { id: 'from', label: 'From', render: (event) => liveEventParty(event, 'from') },
    { id: 'to', label: 'To', render: (event) => liveEventParty(event, 'to') },
    { id: 'channel', label: 'Channel', className: 'live-event-stack', render: (event) => {
      const value = node('span', 'live-event-stack');
      if (event.channel) value.append(node('strong', '', event.channel));
      const detail = [event.frequency_hz ? `${frequency(event.frequency_hz)} MHz` : '',
        event.timeslot == null ? '' : `TS ${event.timeslot}`].filter(Boolean).join(' · ');
      if (detail) value.append(node(event.channel ? 'small' : 'span', '', detail));
      return value;
    } },
    { id: 'details', label: 'Details', className: 'live-event-details', render: (event) => {
      const value = node('span', '', event.details || '');
      if (event.details) value.title = event.details;
      return value;
    } }
  ], 'Select a live row above', {
    type: 'live-events', sortable: false, rowKey: (event) => event.event_id,
    rowClass: (event) => liveEventCategoryClass(event.category),
    wrapperClass: 'live-events-scroll', tableClass: 'live-events-table',
    layoutMenuHost: eventToolbar
  });
  eventPane.append(eventToolbar, eventGap, eventsTable);

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
    const rows = order.map((id) => events.get(id)).filter((event) => event && eventMatches(event))
      .slice(0, liveDetailMatchingRowLimit());
    eventsTable.tableController.setEmptyText(selection ?
      'No matching events received since this tab was opened' : 'Select a live row above');
    eventsTable.tableController.replaceRows(selection ? rows : []);
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

function liveExistingAliasHref(reference) {
  return reference && aliasAdminAllowed() ? href('aliases', {
    list: Number(reference.alias_list_id), aliasTab: 'configure', alias: Number(reference.alias_id)
  }) : '';
}

function liveIdentityType(row, kind) {
  const matcherType = String(row?.[`${kind}_matcher`]?.type || '').trim().toLowerCase();
  if (['talkgroup', 'patch_group', 'radio'].includes(matcherType)) return matcherType;
  const referenceType = String(row?.[`${kind}_entity_ref`]?.kind || '').trim().toLowerCase();
  if (['talkgroup', 'patch_group', 'radio'].includes(referenceType)) return referenceType;
  const form = String(row?.[`${kind}_form`] || '').trim().toUpperCase();
  if (form.includes('PATCH_GROUP')) return 'patch_group';
  if (form.includes('RADIO')) return 'radio';
  if (form.includes('TALKGROUP')) return 'talkgroup';
  return 'unknown';
}

function liveIdentityLabel(row, kind, titleCase = false) {
  const type = liveIdentityType(row, kind);
  if (type === 'radio') return titleCase ? 'Radio' : 'radio';
  if (type === 'patch_group') return titleCase ? 'Patch Group' : 'patch group';
  if (type === 'talkgroup') return titleCase ? 'Talkgroup' : 'talkgroup';
  return titleCase ? 'Identity' : 'identity';
}

function liveIdentityInfo(row, kind) {
  if (!capabilityAllowed(ACCESS_CAPABILITIES.RADIO)) return null;
  const identityLabel = liveIdentityLabel(row, kind);
  const direct = entityRefHref(row?.[`${kind}_entity_ref`]);
  if (direct) return {
    target: direct,
    title: `Open ${identityLabel} info`,
    description: `View this ${identityLabel}'s activity and radio-system details.`
  };
  const conventional = channelTagSet(row?.tags).has('CONVENTIONAL') ||
    dashboardChannelKind(row) === 'CONVENTIONAL';
  const channelTarget = conventional && !isAnalogChannel(row) ? entityTarget(row?.entity_ref,
    { channel: kind === 'source' ? 'radios' : 'groups' }) : '';
  if (!channelTarget) return null;
  const collection = kind === 'source' ? 'radios' : 'groups';
  return {
    target: channelTarget,
    title: `Open channel ${collection}`,
    description: `View ${collection} heard on this saved channel.`
  };
}

let liveIdentityActionSequence = 0;

function liveIdentityActionLink(row, kind, label, aliasTarget, aliasMode = 'edit') {
  const info = liveIdentityInfo(row, kind);
  const infoTarget = info?.target || '';
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
    const identityLabel = liveIdentityLabel(row, kind);
    const modalBody = node('div', 'tuner-frequency-action-body');
    modalBody.append(node('p', 'tuner-frequency-action-intro', `Choose what to do with ${String(label)}.`));
    const actions = node('div', 'tuner-frequency-action-list');
    const manage = anchor('', aliasTarget, 'button secondary tuner-frequency-action');
    manage.append(node('strong', '', aliasMode === 'create' ? 'Create alias' : 'Edit alias'),
      node('small', '', `${aliasMode === 'create' ? 'Create' : 'Open'} this ${identityLabel}'s configured alias.`));
    const infoLink = anchor('', infoTarget, 'button secondary tuner-frequency-action');
    infoLink.append(node('strong', '', info.title),
      node('small', '', info.description));
    actions.append(manage, infoLink);
    modalBody.append(actions);
    openReadOnlyModal(`${liveIdentityLabel(row, kind, true)} ${row?.[`${kind}_id`] || ''}`,
      modalBody, { id: 'live-identity-actions', className: 'frequency-action-modal',
        returnFocusSelector: `#${id}` });
  });
  return trigger;
}

function liveAliasDraftHref(row, kind) {
  const matcher = row?.[`${kind}_matcher`];
  const aliasListId = Number(row?.alias_list_id);
  const type = String(matcher?.type || '').trim().toLowerCase();
  const protocol = String(matcher?.protocol || '').trim().toLowerCase();
  const variant = String(matcher?.variant || '').trim().toLowerCase();
  const value = Number(matcher?.value);
  if (!aliasAdminAllowed() || !Number.isInteger(aliasListId) || aliasListId <= 0 ||
      !['talkgroup', 'radio'].includes(type) ||
      !['am', 'p25', 'dmr', 'nxdn', 'nbfm', 'fleetsync', 'mdc1200'].includes(protocol) ||
      !Number.isSafeInteger(value) || value < 0 ||
      specialIdentifierLabel(row, value, type)) return '';
  const suggestedName = kind === 'source' && String(row?.talker_alias || '').trim() ?
    String(row.talker_alias).trim() : `${type === 'radio' ? 'Radio' : 'Talkgroup'} ${value}`;
  return href('aliases', {
    aliasTab: 'configure', createAlias: 1, createListId: aliasListId, createType: type,
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
    const label = String(reference.name || '').trim() || 'Unnamed alias';
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
  const target = capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ? entityRefHref(row?.entity_ref) : '';
  return label && target ? anchor(label, target, 'live-channel-link') : label;
}

function liveChannelTabTitle(value, label, channelTarget, selectTable) {
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
    const button = node('button', 'channels-tab-title-button', text);
    button.type = 'button';
    button.addEventListener('click', selectTable);
    return button;
  };
  if (linkAt < 0 || !channelTarget) return selectable(title);
  const site = anchor(linkText, channelTarget, 'channels-tab-label');
  site.setAttribute('aria-label', `Open ${siteName || channelName} site details`);
  return fragment(selectable(title.slice(0, linkAt)), site, selectable(title.slice(linkAt + linkText.length)));
}

function openLocalHref(target) {
  navigateTo(target);
}

const LIVE_IDLE_CALL_FIELDS = [
  'source_id', 'source_form', 'source_alias', 'source_alias_description', 'source_alias_display',
  'source_aliases', 'source_entity_ref', 'talker_alias', 'target_id', 'target_form', 'target_alias',
  'target_alias_description', 'target_aliases', 'target_entity_ref', 'encryption_details'
];
const LIVE_VOICE_QUALITY_FIELDS = [
  'vc_quality_pct', 'vc_decoded_frames', 'vc_repeated_frames', 'vc_concealed_frames',
  'vc_missing_frames', 'vc_fec_errors', 'vc_fec_protected_bits'
];

function liveIdentityRenderKey(row, kind) {
  return JSON.stringify([
    row?.[`${kind}_id`], row?.[`${kind}_form`], row?.[`${kind}_alias`],
    row?.[`${kind}_alias_description`], row?.[`${kind}_alias_display`],
    row?.[`${kind}_aliases`], row?.[`${kind}_entity_ref`], row?.[`${kind}_matcher`],
    kind === 'source' ? row?.talker_alias : null, row?.alias_list_id, row?.protocol,
    row?.decoder, row?.tags, row?.entity_ref, row?.channel_kind, row?.channel_name
  ]);
}

function liveRowIsActive(row) {
  const order = Number(row?.activation_order);
  return Number.isSafeInteger(order) && order > 0;
}

function livePresentedRow(row, presentation) {
  if (String(row?.status || '').toUpperCase() !== 'IDLE' ||
      (presentation.retain_last_call_on_idle_rows && !presentation.clear_voice_quality_when_idle)) return row;
  const displayed = { ...row };
  if (!presentation.retain_last_call_on_idle_rows) {
    LIVE_IDLE_CALL_FIELDS.forEach((field) => delete displayed[field]);
  }
  if (presentation.clear_voice_quality_when_idle) {
    LIVE_VOICE_QUALITY_FIELDS.forEach((field) => delete displayed[field]);
  }
  return displayed;
}

function livePresentedTableRows(tableValue, presentation) {
  const rows = Array.isArray(tableValue?.rows) ? tableValue.rows : [];
  const tableId = String(tableValue?.table_id || '');
  if (!presentation.show_only_active_trunked_channels || tableId === 'conventional') {
    return rows.map((row) => livePresentedRow(row, presentation));
  }
  return rows.filter(liveRowIsActive).sort((left, right) => {
    return Number(left.activation_order) - Number(right.activation_order);
  }).map((row) => livePresentedRow(row, presentation));
}

function liveChannelsSection(onSelectionChange) {
  const tables = new Map();
  const tabNodes = new Map();
  const dismissedStoppedTables = new Set();
  const presentation = activeUserPreferences().presentation;
  const decodeDisplay = {
    show_control: presentation.show_control_decode_quality,
    show_voice: presentation.show_voice_decode_quality,
    mode: presentation.decode_quality_display_mode
  };
  const showEncryptionDetails = presentation.show_encryption_details;
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
  const channelStateClass = (row) => {
    const tags = channelTagSet(row.tags);
    return tags.has('CURRENT_CONTROL') ? 'control-current' :
      (tags.has('ALTERNATE_CONTROL') ? 'control-alternate' : '');
  };
  const decodeQualityClass = (row) => {
    const values = decodeQualityValues(row);
    const percent = values.length ? Math.min(...values) : null;
    return percent == null ? '' : (percent >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'quality-good' :
      (percent >= DECODE_DEGRADED_MINIMUM_PERCENT ? 'quality-warn' : 'quality-bad'));
  };
  const columns = [
    { id: 'status', label: 'Status', width: 145,
      render: (row) => showEncryptionDetails && row.status === 'ENCRYPTED' && row.encryption_details ?
        row.encryption_details : row.status,
      className: (row) => `activity-status state-${String(row.status || 'idle').toLowerCase()}`,
      sortValue: (row) => row.status || '' },
    { id: 'tags', label: 'Tags', width: 180, render: channelTagText, title: channelTagTitle,
      sortValue: channelTagText },
    { id: 'channel', label: 'Channel', width: 130, render: (row) =>
      channelTagSet(row.tags).has('CONVENTIONAL') ? liveConventionalChannelValue(row) :
        (row.lcn == null || row.lcn === '' ? '' : `LCN ${row.lcn}`),
      title: (row) => channelTagSet(row.tags).has('CONVENTIONAL') ? row.channel_name || '' : '',
      className: channelStateClass, sortValue: (row) =>
        channelTagSet(row.tags).has('CONVENTIONAL') ? (row.channel_name || '') : (row.lcn || '') },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz', width: 100,
      render: (row) => frequency(row.frequency_hz), className: channelStateClass,
      sortValue: (row) => Number(row.frequency_hz || 0) },
    { id: 'signal', label: 'dBFS', fullLabel: 'Signal dBFS', width: 90,
      render: (row) => row.signal_dbfs == null ? '' : `${Number(row.signal_dbfs).toFixed(1)} dBFS`,
      className: channelStateClass, sortValue: (row) => Number(row.signal_dbfs ?? -999) },
    { id: 'decode-health', label: 'Decode %', width: decodeDisplay.mode === 'detailed' ? 260 : 120,
      render: decodeQualityText, title: decodeQualityTitle, className: decodeQualityClass,
      sortValue: (row) => {
        const values = decodeQualityValues(row);
        return values.length ? Math.min(...values) : -1;
      } },
    { id: 'source-alias', label: 'Source', fullLabel: 'Source Alias', width: 220,
      render: (row) => liveAliasValue(row, 'source'), title: (row) => row.source_alias_description || '',
      sortValue: (row) => row.source_alias_display || row.source_alias || row.talker_alias || '',
      reconcileKey: (row) => liveIdentityRenderKey(row, 'source') },
    { id: 'source', label: 'Src ID', fullLabel: 'Source ID', width: 105,
      render: (row) => liveIdentifierAliasValue(row, 'source'), sortValue: (row) => Number(row.source_id || 0),
      reconcileKey: (row) => liveIdentityRenderKey(row, 'source') },
    { id: 'target-alias', label: 'Target', fullLabel: 'Target Alias', width: 220,
      render: (row) => isAnalogChannel(row) ? liveConventionalChannelValue(row) : liveAliasValue(row, 'target'),
      title: (row) => isAnalogChannel(row) ? row.channel_name || '' : row.target_alias_description || '',
      sortValue: (row) => isAnalogChannel(row) ? row.channel_name || '' : row.target_alias || '',
      reconcileKey: (row) => liveIdentityRenderKey(row, 'target') },
    { id: 'target', label: 'Tgt ID', fullLabel: 'Target ID', width: 105,
      render: (row) => isAnalogChannel(row) ? '' : liveIdentifierAliasValue(row, 'target'),
      sortValue: (row) => isAnalogChannel(row) ? 0 : Number(row.target_id || 0),
      reconcileKey: (row) => liveIdentityRenderKey(row, 'target') },
    { id: 'decoder', label: 'Decoder', width: 80, render: (row) => decoderLabel(row.decoder, true),
      title: (row) => decoderLabel(row.decoder), sortValue: (row) => row.decoder || '' }
  ];
  const tabBar = node('div', 'channels-live-tabs');
  const connection = badge('Connecting', 'state-stale');
  const titleActions = node('div', 'section-title-actions live-channels-title-actions');
  titleActions.append(connection);
  if (userPreferenceController.snapshot().loaded) {
    const presentationSettings = iconButton('icon-live-presentation', 'Live presentation settings',
      'button secondary icon-button section-title-icon live-presentation-settings');
    presentationSettings.id = 'live-presentation-settings';
    presentationSettings.addEventListener('click', () =>
      openLivePresentationSettings('#live-presentation-settings'));
    titleActions.append(presentationSettings);
  }
  let activeTableId = null;
  let selection = null;
  let selectRow = () => {};
  const liveTable = table([], columns, presentation.show_only_active_trunked_channels ?
    'No active channels observed' : 'No channels observed', {
    type: 'live-channels', rowKey: (row) => row.key,
    sortable: true,
    rowClass: (row) => selection?.rowKey === row.key ? 'selected' : '',
    onRowClick: (row) => {
      const value = tables.get(activeTableId);
      if (value) selectRow(value, row);
    },
    wrapperClass: 'table-scroll', tableClass: 'channels-live-table', layoutMenuHost: titleActions
  });
  const host = node('div', 'channels-live');
  host.append(tabBar, liveTable);
  const block = section('Live Channels', host, titleActions);

  const clearSelection = () => {
    if (!selection) return;
    selection = null;
    liveTable.tableController.render();
    onSelectionChange(null);
  };

  selectRow = (value, row, renderSelection = true) => {
    if (!value || !row) return;
    const nextSelection = liveDetailRowSelection(value, row);
    if (!nextSelection || liveDetailSelectionUnchanged(selection, nextSelection)) return;
    selection = nextSelection;
    if (renderSelection) liveTable.tableController.render();
    onSelectionChange(selection);
  };

  const showTable = (tableId) => {
    const value = tables.get(tableId);
    if (!value) return;
    clearSelection();
    activeTableId = tableId;
    const activeFilter = presentation.show_only_active_trunked_channels && tableId !== 'conventional';
    liveTable.tableController.setSortable(!activeFilter);
    const displayed = { ...value, rows: livePresentedTableRows(value, presentation) };
    liveTable.tableController.replaceRows(displayed.rows);
    const currentControl = displayed.control_active ? liveCurrentControlRow(displayed) : null;
    if (currentControl) selectRow(displayed, currentControl);
    tabNodes.forEach((tab, id) => tab.classList.toggle('active', id === activeTableId));
  };

  const updateVisibleRows = (value) => {
    if (value.table_id !== activeTableId) return;
    const displayed = { ...value, rows: livePresentedTableRows(value, presentation) };
    const incoming = new Map(displayed.rows.map((row) => [row.key, row]));
    const activeFilter = presentation.show_only_active_trunked_channels && value.table_id !== 'conventional';
    if (activeFilter && selection && !incoming.has(selection.rowKey)) clearSelection();
    if (selection?.kind === LIVE_DETAIL_SELECTION_KINDS.CONTROL) {
      const currentControl = displayed.control_active ? liveCurrentControlRow(displayed) : null;
      if (currentControl) selectRow(displayed, currentControl, false);
      else {
        const controlIntent = displayed.rows.find((row) =>
          LIVE_DETAIL_CONTROL_ROLES.has(String(row?.role || '').toUpperCase())) || {
          configuration_id: selection.configurationId,
          role: 'CURRENT_CONTROL'
        };
        selection = liveDetailSelection(displayed, controlIntent, null);
        onSelectionChange(selection);
      }
    } else if (selection) {
      const selectedRow = incoming.get(selection.rowKey);
      if (selectedRow) selectRow(displayed, selectedRow, false);
      else clearSelection();
    }
    liveTable.tableController.reconcileRows(displayed.rows);
  };

  const upsertTable = (value) => {
    if (!value?.table_id) return;
    if (dismissedStoppedTables.has(value.table_id)) {
      if (value.channel_running !== true) return;
      dismissedStoppedTables.delete(value.table_id);
    }
    tables.set(value.table_id, value);
    let tab = tabNodes.get(value.table_id);
    if (!tab) {
      tab = node('div', 'channels-live-tab');
      const select = node('button', 'channels-tab-select');
      select.type = 'button';
      const quality = node('span', 'channels-tab-quality');
      for (let index = 0; index < 4; index += 1) quality.append(node('span'));
      select.append(quality);
      select.addEventListener('click', () => {
        const current = tables.get(value.table_id);
        const qualityTarget = current?.table_id !== 'conventional' &&
          capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ? entityTarget(current?.entity_ref, { channel: 'quality' }) : '';
        if (qualityTarget) openLocalHref(qualityTarget);
        else showTable(value.table_id);
      });
      const title = node('span', 'channels-tab-title');
      const close = node('button', 'channels-tab-close', '×');
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
    const select = tab.querySelector('.channels-tab-select');
    const title = tab.querySelector('.channels-tab-title');
    const titleSignature = `${label}|${JSON.stringify(value.entity_ref || null)}|${value.table_id}`;
    if (title.dataset.liveValue !== titleSignature) {
      title.dataset.liveValue = titleSignature;
      const channelTarget = value.table_id !== 'conventional' &&
        capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ? entityTarget(value.entity_ref) : '';
      title.replaceChildren(liveChannelTabTitle(value, label, channelTarget, () => showTable(value.table_id)));
    }
    const quality = tab.querySelector('.channels-tab-quality');
    const qualityLinksToChannel = value.table_id !== 'conventional' &&
      capabilityAllowed(ACCESS_CAPABILITIES.RADIO) && Boolean(entityTarget(value.entity_ref, { channel: 'quality' }));
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
      quality.className = 'channels-tab-quality quality-neutral';
      tab.title = label;
      select.setAttribute('aria-label', `Show live channels for ${label}`);
    } else if (signalStrength === null && decodeQuality === null) {
      quality.className = 'channels-tab-quality quality-unavailable';
      tab.title = `${label} · Signal strength and decode quality unavailable`;
      select.setAttribute('aria-label', `Open ${label} channel quality; signal strength and decode quality unavailable`);
    } else {
      const level = signalBarLevel(signalStrength);
      const state = decodeQuality === null ? 'unavailable' :
        (decodeQuality >= DECODE_HEALTHY_MINIMUM_PERCENT ? 'healthy' :
          (decodeQuality >= DECODE_DEGRADED_MINIMUM_PERCENT ? 'degraded' : 'poor'));
      quality.className = `channels-tab-quality quality-${state} quality-level-${level}`;
      const signalLabel = signalStrength === null ? 'Signal strength unavailable' :
        `${signalStrength.toFixed(1)} dBFS signal strength`;
      const qualityLabel = decodeQuality === null ? 'Decode quality unavailable' :
        `${decodeQuality.toFixed(1)}% decode quality`;
      tab.title = `${label} · ${signalLabel} · ${qualityLabel}`;
      select.setAttribute('aria-label', `Open ${label} channel quality, ${signalLabel}, ${qualityLabel}`);
    }
    quality.classList.toggle('quality-link', Boolean(qualityLinksToChannel));
    select.classList.toggle('quality-link', Boolean(qualityLinksToChannel));
    const stopped = value.table_id !== 'conventional' && value.channel_running === false;
    tab.classList.toggle('stopped', stopped);
    const close = tab.querySelector('.channels-tab-close');
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
      else liveTable.tableController.replaceRows([]);
    }
  };

  subscribeLiveChannelActivity({
    snapshot: (snapshot) => {
      const values = Array.isArray(snapshot?.tables) ? snapshot.tables : [];
      const tableIds = new Set(values.map((value) => String(value?.table_id || '')).filter(Boolean));
      [...tables.keys()].forEach((tableId) => {
        if (!tableIds.has(tableId)) removeTable(tableId);
      });
      values.forEach(upsertTable);
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
  const channels = liveChannelsSection(eventsPanel.select);
  split.append(channels, eventsPanel.element);
  beginPage(renderContext, split);
}

async function requestSpectrumSnapPresetDocument(path = '/api/v1/spectrum-snap-presets', method = 'GET',
    countryCode = null, revision = null) {
  const headers = { Accept: 'application/json' };
  const options = { method, headers };
  if (method === 'PUT') {
    if (!Number.isInteger(revision) || revision < 1) throw new Error('Spectrum snap settings must be loaded before saving.');
    headers['Content-Type'] = 'application/json';
    headers['If-Match'] = `"${revision}"`;
    options.body = JSON.stringify({ country_code: countryCode });
  }
  const response = await jsonDocumentFetch(path, options);
  let value = null;
  try { value = await response.json(); } catch (_) { }
  if (!response.ok) {
    const failure = value?.error && typeof value.error === 'object' ? value.error : null;
    const error = new Error(failure?.message || 'Spectrum snap presets could not be loaded.');
    error.status = response.status;
    error.code = failure?.code || 'spectrum_snap_settings_failed';
    if (response.status === 409) {
      try { error.current = decodeSpectrumSnapPresetDocument(value); } catch (_) { }
    }
    throw error;
  }
  return decodeSpectrumSnapPresetDocument(value);
}

async function renderTunerSpectrum() {
  const renderContext = captureRenderContext();
  const snapPresetDocument = await requestSpectrumSnapPresetDocument();
  if (!renderIsCurrent(renderContext)) return;
  const spectrum = tunerSpectrumPanel(snapPresetDocument);
  pageConnections.add(spectrum);
  beginPage(renderContext, pageHeader('Tuner Spectrum',
    'Inspect the full bandwidth of each active tuner. Click a frequency to choose an action.'), spectrum.element);
}

function radioSystemAssignmentLabel(row) {
  const state = String(row?.assignment_state || '').trim().toUpperCase();
  if (state === 'CURRENT') return 'Current receiver assignment';
  if (state === 'HISTORICAL') return 'Historical activity';
  return '';
}

function radioSystemsDirectoryDetails(row) {
  if (row.directory_type === 'channel') return channelDirectoryDetails(row);
  const assignment = radioSystemAssignmentLabel(row);
  if (isP25(row)) {
    return [
      assignment,
      row.wacn == null ? '' : `WACN ${hex(row.wacn, 5)}`,
      row.system_id == null ? '' : `System ${hex(row.system_id, 3)}`
    ].filter(Boolean).join(' · ');
  }
  if (!isSavedChannelRadioSystem(row)) {
    if (protocolFamily(row) === 'DMR') {
      return [assignment, trunkedVariant(row), semanticLabel(row.model),
        row.network_id == null ? '' : `Network ${identifierNumber(row.network_id)}`]
        .filter(Boolean).join(' · ');
    }
    if (protocolFamily(row) === 'NXDN') {
      return [assignment, trunkedVariant(row), semanticLabel(row.location_category),
        row.system_id == null ? '' : `System ${identifierNumber(row.system_id)}`]
        .filter(Boolean).join(' · ');
    }
  }
  const variant = trunkedVariant(row);
  const domain = identityDomainLabel(row);
  const variantKey = variant.toLowerCase().replace(/[^a-z0-9]/g, '');
  const domainKey = domain.toLowerCase().replace(/[^a-z0-9]/g, '');
  return [
    assignment, 'Scoped to this saved channel', variant, domain && domainKey !== variantKey ? domain : ''
  ].filter(Boolean).join(' · ');
}

function radioSystemsDirectoryInventory(row) {
  if (row.directory_type === 'channel') {
    return `${number(row.learned_channels)} ${Number(row.learned_channels) === 1 ? 'frequency' : 'frequencies'}`;
  }
  const values = [`${number(row.channels)} ${Number(row.channels) === 1 ? 'channel' : 'channels'}`];
  if (isP25(row) && Number(row.patch_groups) > 0) values.push(`${number(row.patch_groups)} patches`);
  return values.join(' · ');
}

function radioSystemsDirectoryContent(data) {
  const { page, tableRows: rows, truncatedParentCount, previewLimit, tableOptions = {} } = data;
  const columns = [
    { id: 'directory-name', label: 'Radio System / Channel', width: 230, className: 'directory-name', render: (row) => {
      const wrapper = node('div', 'directory-entity');
      if (row.directory_type === 'radio_system') {
        const label = row.system_name || radioSystemLabel(row);
        const heading = node('strong');
        heading.append(radioSystemLink(row.entity_ref, label));
        wrapper.append(heading);
        const aliasLists = Array.isArray(row.alias_lists) ? row.alias_lists : [];
        if (aliasLists.length) wrapper.append(node('span', 'muted',
          `${aliasLists.length} Alias List${aliasLists.length === 1 ? '' : 's'}`));
      } else {
        wrapper.append(node('span', 'directory-branch', '↳'), channelNameSummary(row));
      }
      return wrapper;
    } },
    { id: 'protocol', label: 'Protocol', render: (row) => protocolFamily(row) },
    { id: 'details', label: 'Details', render: radioSystemsDirectoryDetails },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz', className: 'numeric',
      render: (row) => row.directory_type === 'channel' ? frequency(row.current_control_hz) : '' },
    { id: 'inventory', label: 'Inventory', render: radioSystemsDirectoryInventory },
    { id: 'groups', label: 'Groups', className: 'numeric',
      render: (row) => row.directory_type === 'radio_system' ?
        number(Number(row.talkgroups || 0) + Number(row.patch_groups || 0)) : '' },
    { id: 'radios', label: 'Radios', className: 'numeric', render: (row) =>
      row.directory_type === 'radio_system' ? number(row.radios) : '' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms) }
  ];
  const directoryTable = table(rows, columns, 'No radio systems or channels recorded', {
    type: 'radio-system-directory',
    sortable: false,
    rowClass: (row) => `directory-${row.directory_type}-row`,
    ...tableOptions
  });
  const rendered = [directoryTable];
  if (truncatedParentCount) rendered.push(node('div', 'directory-warning',
    `${number(truncatedParentCount)} radio system${truncatedParentCount === 1 ? '' : 's'} exceeded the ` +
    `${number(previewLimit)}-channel preview limit. Open the radio system for its complete channel list.`));
  rendered.push(pager(page, 'bottom', 'Radio systems'));
  return fragment(...rendered);
}

async function renderRadioSystems() {
  const renderContext = captureRenderContext();
  const directory = createAsyncSection('Radio Systems', {
    loadingMessage: 'Loading radio systems and channels…',
    errorMessage: 'The radio system directory could not be loaded.'
  });
  if (!beginPage(renderContext,
    pageHeader('Radio Systems',
      'Browse trunked radio systems and the saved receiver channels that receive them'),
    searchBar('Search protocol, system, channel, or name'), directory.element)) return;
  await directory.load(
    () => radioSystemsDirectory.load(apiPage, pageParameters()),
    (data) => radioSystemsDirectoryContent({ ...data, tableOptions: {
      layoutMenuHost: directory.titleActions, controller: directory.tableController
    } }),
    renderContext);
}

async function renderRadioSystem() {
  const renderContext = captureRenderContext();
  const radioSystem = requiredRadioSystem();
  const response = await api(radioSystemApiPath(radioSystem.radio_system_key));
  if (!renderIsCurrent(renderContext)) return;
  const system = response;
  const requestedTab = route.get('tab') || 'info';
  const tabItems = radioSystemTabItems(system);
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : 'info';
  if (tab !== requestedTab) {
    route.set('tab', tab);
    window.history.replaceState({}, '', currentHref());
  }
  const pageContext = [system.channel_names, radioSystemsDirectoryDetails(system)].filter(Boolean).join(' · ');
  if (!beginPage(renderContext,
    pageHeader(radioSystemValue(system), pageContext),
    radioSystemTabs(system, tab))) return;

  if (tab === 'groups') {
    const directory = createAsyncSection('Groups', {
      action: exportCsvLink('radio-system-group-identities', radioSystem),
      loadingMessage: 'Loading groups…',
      errorMessage: 'The radio system groups could not be loaded.'
    });
    content.append(searchBar('Search group ID'), directory.element);
    await directory.load(
      () => apiPage(radioSystemApiPath(radioSystem.radio_system_key, 'group-identities'), pageParameters()),
      (page) => pagedTableContent(page, groupIdentityColumns, 'group-identities', {
        topPager: true,
        tableOptions: { layoutMenuHost: directory.titleActions, controller: directory.tableController }
      }),
      renderContext);
  } else if (tab === 'radios') {
    const filters = affiliationRouteFilters();
    const title = filters.configuration_id ?
      (filters.affiliated ? 'Affiliated Radios on Channel' : 'Radios on Channel') :
      (filters.affiliated ? 'Affiliated Radios' : 'Radios');
    const exportAction = exportCsvLink('radio-system-radios', { ...radioSystem, ...filters });
    const columns = radioSystemRadioColumns(system);
    const directory = createAsyncSection(title, {
      action: affiliationFilterActions(exportAction),
      loadingMessage: 'Loading radios…',
      errorMessage: 'The radio system radios could not be loaded.'
    });
    content.append(searchBar('Search radio ID'), directory.element);
    await directory.load(
      () => apiPage(radioSystemApiPath(radioSystem.radio_system_key, 'radios'), pageParameters(filters)),
      (page) => pagedTableContent(page, columns, radioTableType('radios', columns), {
        topPager: true,
        tableOptions: { layoutMenuHost: directory.titleActions, controller: directory.tableController }
      }),
      renderContext);
  } else if (tab === 'talker-aliases') {
    const columns = [
    { id: 'radio', label: 'Radio', fullLabel: 'Radio ID', render: (row) => radioLink(row), className: 'numeric', sort: 'radio', sortValue: (row) => Number(row.native_id) },
      { id: 'talker-alias', label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias', className: 'alias-cell', sort: 'talker_alias' },
      { id: 'radio-alias', label: 'Alias', fullLabel: 'Configured Alias', render: (row) => aliasLabel(row) ? radioLink(row, undefined, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
      { id: 'logical-calls', label: 'Logical Calls', render: (row) => number(row.logical_call_count), className: 'numeric', sort: 'logical_call_count', sortValue: (row) => Number(row.logical_call_count || 0) },
      { id: 'encrypted-logical-calls', label: 'Enc', render: (row) => number(row.encrypted_logical_call_count), className: 'numeric encrypted', sort: 'encrypted_logical_call_count', sortValue: (row) => Number(row.encrypted_logical_call_count || 0) },
      { id: 'last-seen', label: 'Alias Seen', fullLabel: 'Talker Alias Last Seen', render: (row) => dateTime(row.last_talker_alias_seen_ms), sort: 'talker_alias_seen', sortValue: (row) => Number(row.last_talker_alias_seen_ms || 0) }
    ];
    const directory = createAsyncSection('Talker Alias Summary', {
      loadingMessage: 'Loading talker aliases…',
      errorMessage: 'The talker alias summary could not be loaded.'
    });
    content.append(searchBar('Search radio ID or talker alias'), directory.element);
    await directory.load(
      () => apiPage(radioSystemApiPath(radioSystem.radio_system_key, 'talker-aliases'), pageParameters()),
      (page) => pagedTableContent(page, columns, 'talker-aliases', {
        topPager: true,
        emptyText: 'No talker aliases recorded for this system',
        tableOptions: { layoutMenuHost: directory.titleActions, controller: directory.tableController }
      }),
      renderContext);
  } else if (tab === 'activity') {
    await renderActivity(radioSystem,
      isSavedChannelRadioSystem(system) ? 'Saved Channel Activity' : 'System Activity');
  } else {
    const infoColumn = node('div', 'entity-info-column system-info-column');
    const blocks = [section('Directory', metrics([
      ['Configured Channels', system.channels],
      ['Known Talkgroups', system.talkgroups],
      ['Known Patch Groups', system.patch_groups],
      ['Known Radios', system.radios]
    ], true)), section('Logical Call Activity', metrics([
      ['Logical Calls', system.logical_call_count],
      ['Channel Observations', system.channel_observation_count],
      ['Recorded', system.recorded_logical_call_count],
      ['Submitted to Streamer', system.stream_submitted_logical_call_count],
      ['Encrypted', system.encrypted_logical_call_count]
    ], true))];
    if (radioSystemCapability(system, 'current_affiliations')) {
      blocks.push(section('Current State', metrics([
        ['Currently Affiliated', system.affiliated_radios]
      ], true)));
    }
    blocks.push(section(isSavedChannelRadioSystem(system) ? 'Saved Channel Scope' : 'System Info', keyValues([
      [radioSystemOwnerLabel(system), radioSystemInfoValue(system)],
      ['Assignment', radioSystemAssignmentLabel(system)],
      ['Alias Lists', radioSystemAliasLists(system)],
      ['First Seen', dateTime(system.first_seen_ms)], ['Last Seen', dateTime(system.last_seen_ms)]
    ])), tableSection('Retained Signaling Observations',
      signalingActionRows(response.action_counts), [
      { id: 'action', label: 'Action', key: 'action' },
      { id: 'observations', label: 'Observations',
        render: (row) => number(row.observation_count), className: 'numeric',
        sortValue: (row) => Number(row.observation_count || 0) }
    ], 'No signaling observations recorded', { type: 'system-action-observations' }, activityMetricGuide()));
    infoColumn.append(...blocks);

    const channelsPage = await apiPage(radioSystemApiPath(radioSystem.radio_system_key, 'channels'), pageParameters());
    const channelsColumn = node('div', 'entity-info-column radio-system-channels-column');
    channelsColumn.append(pagedSection('Channels', channelsPage, radioSystemChannelColumns,
      'Search channel, site, or name', 'radio-system-channels'));
    const layout = node('div', 'entity-info-layout system-info-layout');
    layout.append(infoColumn, channelsColumn);
    content.append(layout);
  }
}

async function renderGroupIdentity() {
  const renderContext = captureRenderContext();
  const radioSystem = requiredRadioSystem();
  const identityKey = requiredIdentityKey();
  const response = await api(groupIdentityApiPath(radioSystem.radio_system_key, identityKey));
  const groupIdentity = response;
  const kind = rowGroupIdentityKind(groupIdentity);
  const tab = route.get('tab') || 'info';
  const formattedId = identityNumber(groupIdentity, groupIdentity.native_id);
  const kindLabel = kind === 'patch_group' ? 'Patch Group' : 'Talkgroup';
  const title = aliasLabel(groupIdentity) || `${kindLabel} ${formattedId}`;
  if (!beginPage(renderContext,
    pageHeader(title, fragment(radioSystemValue(groupIdentity), ` · ${kindLabel} ${formattedId}`)),
    entityTabs('group-identity', groupIdentity, identityKey, tab, false))) return;

  if (tab === 'radios') {
    const currentAffiliations = kind === 'talkgroup' &&
      radioSystemCapability(groupIdentity, 'current_affiliations');
    const channelPresence = currentAffiliations && radioSystemCapability(groupIdentity, 'radio_channel_presence');
    const affiliatedOnly = currentAffiliations && route.get('affiliated') === 'true';
    const relationships = await apiPage(radioSystemApiPath(radioSystem.radio_system_key, 'relationships'),
      pageParameters({ group_identity_key: identityKey,
        affiliated: affiliatedOnly ? true : null }));
    const columns = [
      { id: 'radio', label: 'Radio', render: (row) => radioLink(row, row.radio_native_id, undefined,
        row.radio_entity_ref), className: 'numeric', sort: 'radio', sortValue: (row) => Number(row.radio_native_id) },
      { id: 'alias', label: 'Alias', render: (row) => row.radio_alias_name ?
        radioLink(row, row.radio_native_id, row.radio_alias_name, row.radio_entity_ref) : '',
        className: 'alias-cell', sort: 'radio_alias', sortValue: (row) => row.radio_alias_name || '' },
      { id: 'logical-calls', label: 'Logical Calls', render: (row) => number(row.logical_call_count), className: 'numeric', sort: 'logical_call_count', sortValue: (row) => Number(row.logical_call_count || 0) },
      { id: 'encrypted-logical-calls', label: 'Enc', render: (row) => number(row.encrypted_logical_call_count), className: 'numeric encrypted', sort: 'encrypted_logical_call_count', sortValue: (row) => Number(row.encrypted_logical_call_count || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    if (radioSystemCapability(groupIdentity, 'talker_aliases')) {
      columns.splice(2, 0, { id: 'talker-alias', label: 'OTA Alias', fullLabel: 'Talker Alias', key: 'last_talker_alias',
        className: 'alias-cell', sort: 'talker_alias' });
    }
    if (channelPresence) {
      columns.splice(radioSystemCapability(groupIdentity, 'talker_aliases') ? 3 : 2, 0,
        { id: 'confirmed-channel', label: 'Confirmed Channel', fullLabel: 'Last Confirmed Affiliated Channel',
          render: (row) => row.currently_affiliated === true ? channelPresenceCell(row) : '',
          className: 'alias-cell', sort: 'channel', sortValue: (row) => row.currently_affiliated === true ?
            presenceChannelSortValue(row) : '' });
    }
    const action = currentAffiliations ? anchor(affiliatedOnly ? 'Clear Filter' : 'Show Affiliated',
      currentHref({ affiliated: affiliatedOnly ? null : true, offset: null }), 'button secondary') : null;
    content.append(pagedSection(affiliatedOnly ? 'Affiliated Radios' : 'Radios', relationships,
      columns, null, radioTableType('group-identity-radios', columns), action));
  } else if (tab === 'activity') {
    if (detailedHistoryAvailable()) {
      await renderActivity({ ...radioSystem, group_identity_key: identityKey }, 'Activity Log');
    } else {
      content.append(section('Activity Log', node('div', 'empty',
        'Detailed history logging is not running.')));
    }
  } else {
    const infoColumn = node('div', 'entity-info-column');
    const blocks = [section('Identity', keyValues([
      [radioSystemOwnerLabel(groupIdentity), radioSystemLink(groupIdentity.radio_system_entity_ref,
        radioSystemInfoValue(groupIdentity))],
      [kind === 'patch_group' ? 'Patch Group ID' : 'Talkgroup ID', formattedId],
      ['Alias', aliasLabel(groupIdentity)],
      ['Description', groupIdentity.alias_description],
      ['Group', groupIdentity.alias_group]
    ])), section('Logical Call Activity', metrics([
      ['Logical Calls', groupIdentity.logical_call_count],
      ['Channel Observations', groupIdentity.channel_observation_count],
      ['Recorded', groupIdentity.recorded_logical_call_count],
      ['Submitted to Streamer', groupIdentity.stream_submitted_logical_call_count],
      ['Encrypted', groupIdentity.encrypted_logical_call_count]
    ], true)), section('Relationships', metrics([
      ['Observed Radios', groupIdentity.radios]
    ], true))];
    if (kind === 'talkgroup' && radioSystemCapability(groupIdentity, 'current_affiliations')) {
      const currentState = [
        ['Currently Affiliated', anchor(number(groupIdentity.affiliated_radios),
          href('group-identity', { ...radioSystemRoute(groupIdentity), identity_key: identityKey,
            tab: 'radios', affiliated: true }))]
      ];
      if (radioSystemCapability(groupIdentity, 'radio_channel_presence')) {
        currentState.push(['Affiliated Channels', number(groupIdentity.affiliated_channels)]);
      }
      blocks.push(section('Current State', keyValues(currentState)));
    }
    blocks.push(section('Last-known Facts', keyValues([
      ['Last Source', radioLink(groupIdentity, groupIdentity.last_source_radio_id, undefined,
        groupIdentity.last_source_entity_ref)],
      ['Last Encryption Algorithm', encryptionAlgorithmInfoValue(groupIdentity.last_encryption_algorithm_name,
        groupIdentity.last_encryption_algorithm_id)],
      ['Last Encryption Key ID', hexDecimalPair(groupIdentity.last_encryption_key_id)]
    ])), section('Observed Times', keyValues([
      ['First Observed', dateTime(groupIdentity.first_seen_ms)],
      ['Last Observed', dateTime(groupIdentity.last_seen_ms)]
    ])), tableSection('Collected Signaling Observations',
      signalingCounts(groupIdentity).map(([action, count]) => ({ action, count })), [
      { id: 'action', label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No signaling observations recorded', { type: 'action-counts' }));
    infoColumn.append(...blocks);
    const layout = node('div', 'entity-info-layout');
    const activityHistory = await groupIdentityActivityHistorySection({ ...radioSystem,
      identity_key: identityKey });
    if (!renderIsCurrent(renderContext)) return;
    layout.append(infoColumn, activityHistory);
    content.append(layout);
  }
}

async function renderRadio() {
  const renderContext = captureRenderContext();
  const radioSystem = requiredRadioSystem();
  const identityKey = requiredIdentityKey();
  const response = await api(`${radioSystemApiPath(radioSystem.radio_system_key, 'radios')}/${encodeURIComponent(identityKey)}`);
  const radio = response;
  const tab = route.get('tab') || 'info';
  const formattedId = identityNumber(radio, radio.native_id);
  const title = aliasLabel(radio) || radio.last_talker_alias || `Radio ${formattedId}`;
  if (!beginPage(renderContext,
    pageHeader(title, fragment(radioSystemValue(radio), ` · Radio ${formattedId}`)),
    entityTabs('radio', radio, identityKey, tab, true))) return;

  if (tab === 'groups') {
    const relationships = await apiPage(radioSystemApiPath(radioSystem.radio_system_key, 'relationships'),
      pageParameters({ radio_identity_key: identityKey }));
    const columns = [
      { id: 'group-identity-id', label: 'Group', render: (row) => groupIdentityLink(row,
        row.group_native_id, undefined, row.group_identity_entity_ref), className: 'numeric',
        sort: 'group_identity', sortValue: (row) => Number(row.group_native_id) },
      { id: 'group-identity-kind', label: 'Kind', render: (row) => groupIdentityLabel(row) },
      { id: 'group-identity-name', label: 'Group Alias', render: (row) => groupIdentityAliasLink(row,
        row.group_native_id, 'group_identity_alias_', row.group_identity_entity_ref), className: 'alias-cell',
        sort: 'group_identity_alias', sortValue: (row) => row.group_identity_alias_name || '' },
      { id: 'group-identity-description', label: 'Description', key: 'group_identity_alias_description',
        className: 'alias-cell' },
      { id: 'logical-calls', label: 'Logical Calls', render: (row) => number(row.logical_call_count), className: 'numeric', sort: 'logical_call_count', sortValue: (row) => Number(row.logical_call_count || 0) },
      { id: 'encrypted-logical-calls', label: 'Enc', render: (row) => number(row.encrypted_logical_call_count), className: 'numeric encrypted', sort: 'encrypted_logical_call_count', sortValue: (row) => Number(row.encrypted_logical_call_count || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    content.append(pagedSection('Groups', relationships, columns, null, 'radio-groups'));
  } else if (tab === 'activity') {
    await renderActivity({ ...radioSystem, radio_identity_key: identityKey });
  } else {
    const infoColumn = node('div', 'entity-info-column entity-info-standalone');
    const identityValues = [
      [radioSystemOwnerLabel(radio), radioSystemLink(radio.radio_system_entity_ref, radioSystemInfoValue(radio))],
      ['Radio ID', formattedId],
      ['Alias', aliasLabel(radio)]
    ];
    if (radioSystemCapability(radio, 'talker_aliases')) {
      identityValues.push(['Talker Alias', radio.last_talker_alias]);
    }
    const blocks = [section('Identity', keyValues(identityValues)), section('Logical Call Activity', metrics([
      ['Logical Calls', radio.logical_call_count],
      ['Recorded', radio.recorded_logical_call_count],
      ['Submitted to Streamer', radio.stream_submitted_logical_call_count],
      ['Encrypted', radio.encrypted_logical_call_count]
    ], true))];
    if (radioSystemCapability(radio, 'current_affiliations')) {
      blocks.push(section('Current Affiliation', keyValues([
        ['Talkgroup ID', groupIdentityLink(radio, radio.affiliated_talkgroup_id, undefined,
          radio.affiliated_talkgroup_entity_ref)],
        ['Talkgroup Alias', groupIdentityAliasLink(radio, radio.affiliated_talkgroup_id,
          'affiliated_talkgroup_alias_', radio.affiliated_talkgroup_entity_ref)],
        ['Affiliation Confirmed', dateTime(radio.affiliation_confirmed_at_ms)]
      ])));
    }
    if (radioSystemCapability(radio, 'radio_channel_presence')) {
      const presence = authoritativePresence(radio);
      blocks.push(section('Last Confirmed Channel', keyValues([
        ['Channel', channelPresenceCell(radio, false)],
        ['Confirmed', presence ? dateTime(presence.confirmed_at_ms) : '—']
      ])));
    }
    blocks.push(section('Relationships', metrics([
      ['Observed Groups', radio.groups]
    ])), section('Last-known Facts', keyValues([
      ['Last Encryption Algorithm', encryptionAlgorithmInfoValue(radio.last_encryption_algorithm_name,
        radio.last_encryption_algorithm_id)],
      ['Last Encryption Key ID', hexDecimalPair(radio.last_encryption_key_id)]
    ])), section('Observed Times', keyValues([
      ['Talker Alias Observed', dateTime(radio.last_talker_alias_seen_ms)],
      ['First Observed', dateTime(radio.first_seen_ms)],
      ['Last Observed', dateTime(radio.last_seen_ms)]
    ])), tableSection('Collected Signaling Observations',
      signalingCounts(radio).map(([action, count]) => ({ action, count })), [
      { id: 'action', label: 'Action', key: 'action' },
      { id: 'count', label: 'Count', render: (row) => number(row.count), className: 'numeric', sortValue: (row) => Number(row.count || 0) }
    ], 'No signaling observations recorded', { type: 'action-counts' }, activityMetricGuide()));
    infoColumn.append(...blocks);
    content.append(infoColumn);
  }
}

function channelLocationIdentity(channel) {
  if (protocolFamily(channel) === 'DMR') {
    const model = semanticLabel(channel.model);
    return [
      model ? `${model} model` : '',
      channel.network_id == null ? '' : `Network ${identifierNumber(channel.network_id)}`,
      channel.site_id == null ? '' : `Site ${identifierNumber(channel.site_id)}`
    ].filter(Boolean).join(' · ');
  }
  if (protocolFamily(channel) === 'NXDN') {
    return [
      semanticLabel(channel.location_category),
      channel.system_id == null ? '' : `System ${identifierNumber(channel.system_id)}`,
      channel.site_id == null ? '' : `Site ${identifierNumber(channel.site_id)}`,
      channel.site_network_id == null ? '' : `Integrator ${identifierNumber(channel.site_network_id)}`,
      channel.ran == null ? '' : `RAN ${identifierNumber(channel.ran)}`
    ].filter(Boolean).join(' · ');
  }
  return [
    channel.wacn == null ? '' : `WACN ${hex(channel.wacn, 5)}`,
    channel.system_id == null ? '' : `System ${hex(channel.system_id, 3)}`,
    channel.rfss == null ? '' : `RFSS ${hex(channel.rfss, 2)}`,
    channel.site_id == null ? '' : `Site ${hex(channel.site_id, 2)}`
  ].filter(Boolean).join(' · ');
}

function p25DecoderMode(value) {
  return ({ C4FM: 'Normal (C4FM)', CQPSK: 'Simulcast (LSM / CQPSK)' })[
    String(value || '').trim().toUpperCase()] || availableValue(value);
}

function p25ChannelDetailRows(channel) {
  return [
    ['Callsign', callsignLink(channel.callsign)], ['WACN', hexDecimalPair(channel.wacn, 5)],
    ['SysID', hexDecimalPair(channel.system_id, 3)], ['NAC', hexDecimalPair(channel.nac, 3)],
    ['RFSS', hexDecimalPair(channel.rfss, 2)], ['Site', hexDecimalPair(channel.site_id, 2)],
    ['Local Registration Area', hexDecimalPair(channel.lra, 2)],
    ['Active RFSS Network Connection', yesNoKnown(channel.active_rfss_network_connection)],
    ['Manufacturer', channel.mfid_display],
    ['Configured Decoder Mode', p25DecoderMode(channel.p25_decoder_mode)],
    ['Broadcast Clock', dateTime(channel.broadcast_clock_ms)],
    ['Data', yesNoKnown(channel.data_service)], ['Data Access', channel.data_access],
    ['Working Unit ID Lease Time', channel.wuid_lease_minutes == null ? '' :
      `${number(channel.wuid_lease_minutes)} minutes`],
    ['Unit registration over control channel', yesNoKnown(channel.registration_service)],
    ['TDMA', yesNoKnown(channel.tdma)], ['u-Slots', channel.micro_slots == null ? '' : number(channel.micro_slots)],
    ['Voice', yesNoKnown(channel.voice_service)]
  ];
}

function distinctObservedVariant(channel) {
  const observed = trunkedVariant({ variant: channel?.site_variant });
  const authoritative = trunkedVariant(channel);
  return observed && observed !== authoritative ? observed : '';
}

function distinctObservedClassification(observed, authoritative) {
  const observedLabel = semanticLabel(observed);
  return observedLabel && observedLabel !== semanticLabel(authoritative) ? observedLabel : '';
}

function dmrChannelDetailRows(channel) {
  const rows = [
    ['Radio System Variant', trunkedVariant(channel)],
    ['Radio System Network', identifierNumber(channel.network_id)],
    ['Radio System Model', semanticLabel(channel.model)],
    ['Observed Site', identifierNumber(channel.site_id)]
  ];
  const siteVariant = distinctObservedVariant(channel);
  const siteModel = distinctObservedClassification(channel.site_model, channel.model);
  if (siteVariant) rows.push(['Observed Site Variant', siteVariant]);
  if (siteModel) rows.push(['Observed Site Model', siteModel]);
  rows.push(
    ['Brand', semanticLabel(channel.brand)], ['Mode', semanticLabel(channel.mode)],
    ['Channel Type', semanticLabel(channel.channel_type)],
    ['Color Code TS1', identifierNumber(channel.color_code_ts1)],
    ['Color Code TS2', identifierNumber(channel.color_code_ts2)]
  );
  return rows;
}

function nxdnChannelDetailRows(channel) {
  const rows = [
    ['Radio System Variant', trunkedVariant(channel)],
    ['Radio System Category', semanticLabel(channel.location_category)],
    ['Radio System ID', identifierNumber(channel.system_id)],
    ['Observed Site', identifierNumber(channel.site_id)]
  ];
  const integrator = identifierNumber(channel.site_network_id);
  const siteVariant = distinctObservedVariant(channel);
  const siteCategory = distinctObservedClassification(channel.site_location_category,
    channel.location_category);
  if (integrator) rows.push(['Observed Integrator', integrator]);
  if (siteVariant) rows.push(['Observed Site Variant', siteVariant]);
  if (siteCategory) rows.push(['Observed Site Category', siteCategory]);
  rows.push(
    ['RAN', identifierNumber(channel.ran)],
    ['Repeater State', semanticLabel(channel.repeater_state)],
    ['Current Repeater', identifierNumber(channel.current_repeater)],
    ['Services', (channel.services || []).map(semanticLabel).join(', ')],
    ['Failure Call Timer', Object.hasOwn(channel, 'failure_call_timer_seconds') ?
      (channel.failure_call_timer_seconds == null ? 'Unspecified' :
        `${number(channel.failure_call_timer_seconds)} seconds`) : '']
  );
  return rows;
}

function channelProtocolDetailRows(channel) {
  if (isP25(channel)) return p25ChannelDetailRows(channel);
  if (protocolFamily(channel) === 'DMR') return dmrChannelDetailRows(channel);
  if (protocolFamily(channel) === 'NXDN') return nxdnChannelDetailRows(channel);
  return [];
}

function p25ChannelFrequencyColumns() {
  return [
    { id: 'descriptor', label: 'LCN / Mode', fullLabel: 'Logical Channel Number and Modes', key: 'descriptor' },
    { id: 'callsign', label: 'Callsign', render: (row) => callsignLink(row.callsign),
      sortValue: (row) => row.callsign || '' },
    { id: 'tags', label: 'Tags', key: 'tags', render: channelTags },
    { id: 'downlink', label: 'Down MHz', fullLabel: 'Downlink MHz',
      render: (row) => frequency(row.downlink_hz), className: 'numeric',
      sortValue: (row) => Number(row.downlink_hz || 0) },
    { id: 'uplink', label: 'Up MHz', fullLabel: 'Uplink MHz',
      render: (row) => frequency(row.uplink_hz), className: 'numeric',
      sortValue: (row) => Number(row.uplink_hz || 0) },
    { id: 'tdma', label: 'TDMA', render: (row) => yesNo(row.tdma),
      sortValue: (row) => Boolean(row.tdma) },
    { id: 'slots', label: 'Slots', key: 'timeslots', className: 'numeric' },
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state),
      sortValue: (row) => row.state || '' },
    { id: 'voice-observations', label: 'Voice', fullLabel: 'Voice Grant Observations', key: 'voice_grant_observations',
      className: 'numeric' },
    { id: 'data-observations', label: 'Data', fullLabel: 'Data Grant Observations', key: 'data_grant_observations',
      className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

function trunkedChannelFrequencyColumns() {
  return [
    { id: 'channel', label: 'Channel', key: 'channel_number', className: 'numeric',
      render: (row) => identifierNumber(row.channel_number) },
    { id: 'inbound-channel', label: 'Inbound', fullLabel: 'Inbound Channel', key: 'inbound_channel_number', className: 'numeric',
      render: (row) => identifierNumber(row.inbound_channel_number) },
    { id: 'slot', label: 'Slot', key: 'timeslot', className: 'numeric',
      render: (row) => identifierNumber(row.timeslot) },
    { id: 'use', label: 'Use', render: (row) => trunkedChannelUse(row.roles) },
    { id: 'source', label: 'Source', render: (row) => trunkedChannelSources(row.sources) },
    { id: 'downlink', label: 'Down MHz', fullLabel: 'Downlink MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric' },
    { id: 'uplink', label: 'Up MHz', fullLabel: 'Uplink MHz',
      render: (row) => frequency(row.uplink_hz), className: 'numeric' },
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state) },
    { id: 'snapshots', label: 'Snapshots', key: 'observation_count', className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms) }
  ];
}

async function renderTrunkedChannelFrequencies(channel, renderContext) {
  const p25 = isP25(channel);
  const directory = createAsyncSection('Frequencies', {
    action: exportCsvLink('channel-frequencies', { configuration_id: channel.configuration_id }),
    loadingMessage: 'Loading channel frequencies…',
    errorMessage: 'The channel frequencies could not be loaded.'
  });
  if (!renderIsCurrent(renderContext)) return;
  content.append(directory.element);
  await directory.load(
    () => apiPage(channelApiPath(channel.configuration_id, 'frequencies'), pageParameters()),
    (page) => fragment(
      protocolFamily(channel) === 'DMR' ? node('p', 'muted',
        'DMR grants usually identify an LCN and timeslot. Frequencies marked LCN Map were resolved from the configured map; OTA Freq means the system broadcast an absolute frequency.') : null,
      pagedTableContent(page, p25 ? p25ChannelFrequencyColumns() : trunkedChannelFrequencyColumns(),
        p25 ? 'channel-frequencies-p25' : 'channel-frequencies-trunked', {
          itemLabel: 'Frequencies', emptyText: 'No frequencies recorded',
          tableOptions: {
            sortable: false, serverSort: false,
            layoutMenuHost: directory.titleActions, controller: directory.tableController
          }
        })),
    renderContext);
}

function p25ChannelNeighborColumns() {
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
    { id: 'lcn', label: 'LCN', key: 'channel_descriptor' },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz',
      render: (row) => frequency(row.downlink_hz), className: 'numeric',
      sortValue: (row) => Number(row.downlink_hz || 0) },
    { id: 'modes', label: 'Modes', render: neighborModes },
    { id: 'bands', label: 'Bands', key: 'band_count', className: 'numeric' },
    { id: 'advertised-status', label: 'Status', fullLabel: 'Advertised Status',
      render: (row) => neighborStatus(row.status), sortValue: (row) => row.status || '' },
    { id: 'observations', label: 'Observations', key: 'observation_count', className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

function trunkedChannelNeighborColumns(channel) {
  return [
    { id: 'variant', label: 'Variant', render: (row) => trunkedVariant({
      protocol: channel.protocol,
      variant: row.variant
    }) },
    { id: 'model-category', label: 'Model / Category', render: (row) => identityDomainLabel({
      protocol: channel.protocol,
      address_domain: row.address_domain,
      model: row.model,
      location_category: row.location_category
    }) },
    { id: 'neighbor-name', label: 'Name / Site', fullLabel: 'Monitored Name and Site',
      render: neighborSiteLink,
      sortValue: (row) => neighborSiteDisplayParts(row).primary },
    { id: 'network', label: 'Network', key: 'network_id', className: 'numeric',
      render: (row) => identifierNumber(row.network_id) },
    { id: 'system', label: 'System', key: 'system_id', className: 'numeric',
      render: (row) => identifierNumber(row.system_id) },
    { id: 'site', label: 'Site', key: 'site_id', className: 'numeric',
      render: (row) => identifierNumber(neighborSiteId(row)) },
    { id: 'channel', label: 'Channel', key: 'channel_number', className: 'numeric',
      render: (row) => identifierNumber(row.channel_number) },
    { id: 'control-frequency', label: 'CC MHz', fullLabel: 'Control Frequency MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric' },
    { id: 'status', label: 'Status', render: (row) => trunkedNeighborStatus(row.statuses) },
    { id: 'state', label: 'State', render: (row) => stateBadge(row.state) },
    { id: 'observations', label: 'Observations', key: 'observation_count', className: 'numeric' },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms) }
  ];
}

function patchMembersByLocalGroup(values) {
  const members = new Map();
  (Array.isArray(values) ? values : []).forEach((row) => {
    const localPatchGroupId = Number(row?.local_patch_group_id);
    if (!Number.isInteger(localPatchGroupId) || localPatchGroupId <= 0) return;
    if (!members.has(localPatchGroupId)) members.set(localPatchGroupId, []);
    members.get(localPatchGroupId).push(row);
  });
  return members;
}

async function renderChannelNeighbors(channel, renderContext) {
  const p25 = isP25(channel);
  const directory = createAsyncSection('Neighbors', {
    action: exportCsvLink('channel-neighbors', { configuration_id: channel.configuration_id }),
    loadingMessage: 'Loading channel neighbors…',
    errorMessage: 'The channel neighbors could not be loaded.'
  });
  if (!renderIsCurrent(renderContext)) return;
  content.append(directory.element);
  await directory.load(
    () => apiPage(channelApiPath(channel.configuration_id, 'neighbors'), pageParameters()),
    (page) => pagedTableContent(page,
      p25 ? p25ChannelNeighborColumns() : trunkedChannelNeighborColumns(channel),
      p25 ? 'channel-neighbors-p25' : 'channel-neighbors-trunked', {
        itemLabel: 'Neighbors', emptyText: 'No neighbors recorded',
        tableOptions: {
          sortable: false, serverSort: false,
          layoutMenuHost: directory.titleActions, controller: directory.tableController
        }
      }),
    renderContext);
}

async function renderTrunkedChannelInfo(channel, renderContext) {
  const summary = [
    ['Metadata Updates', channel.observation_count], ['Frequencies', channel.frequencies], ['Neighbors', channel.neighbors]
  ];
  if (channelCapability(channel, 'frequency_bands')) summary.push(['Band Plans', channel.bands]);
  if (channelCapability(channel, 'patch_groups')) summary.push(['Patches', channel.patches]);
  if (channelCapability(channel, 'current_affiliations') && channelCapability(channel, 'radio_channel_presence')) {
    const label = number(channel.affiliated_radios);
    const linked = channel.radio_system_key && channel.configuration_id ? anchor(label,
      href('radio-system', { ...radioSystemRoute(channel), tab: 'radios', affiliated: true,
        configuration_id: channel.configuration_id })) : label;
    summary.push(['Affiliated Radios', channel.affiliated_radios, linked]);
  }

  const infoColumn = node('div', 'entity-info-column');
  infoColumn.append(section('Channel Info', keyValues([
    [radioSystemOwnerLabel(channel), radioSystemLink(channel.radio_system_entity_ref, radioSystemInfoValue(channel))],
    ['Site', siteNameValue(channel)], ['Name', nameValue(channel)],
    ['Configuration ID', channel.configuration_id],
    ['Alias List', aliasListLink(channel.alias_list_name, channel.alias_list_id)],
    ['Protocol', protocolFamily(channel)], ['Decoder', decoderDisplay(channel.decoder)],
    ['Configured Frequency', frequency(channel.primary_frequency_hz)],
    ['Current Control Frequency', frequency(channel.current_control_hz)],
    ['First Seen', dateTime(channel.first_seen_ms)], ['Last Seen', dateTime(channel.last_seen_ms)]
  ])));
  const protocolDetails = channelProtocolDetailRows(channel);
  if (protocolDetails.length) {
    infoColumn.append(section(`${protocolFamily(channel)} Details`, keyValues(protocolDetails)));
  }

  const activityColumn = node('div', 'entity-info-column');
  if (channelCapability(channel, 'group_identities')) {
    activityColumn.append(await channelTopGroupsSection(channel));
  }
  if (!renderIsCurrent(renderContext)) return;
  const layout = node('div', 'entity-info-layout');
  layout.append(infoColumn);
  if (activityColumn.childNodes.length) layout.append(activityColumn);
  content.append(metrics(summary), layout);
}

async function renderTrunkedChannel(channel, configurationId, renderContext) {
  const requestedTab = route.get('tab') || 'info';
  const tabItems = trunkedChannelTabItems(channel);
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : 'info';
  const display = channelDisplayParts(channel);
  const siteVariant = trunkedVariant({ variant: channel.site_variant });
  const subtitle = [display.secondary, protocolFamily(channel), trunkedVariant(channel) || siteVariant,
    channelLocationIdentity(channel)]
    .filter(Boolean).join(' · ');
  if (!beginPage(renderContext, pageHeader(channelValue(channel), subtitle), trunkedChannelTabs(channel, tab))) return;

  if (tab === 'quality') {
    const signalHistory = await channelSignalHistorySection(channel);
    if (!renderIsCurrent(renderContext)) return;
    content.append(signalHistory);
  } else if (tab === 'frequencies') {
    await renderTrunkedChannelFrequencies(channel, renderContext);
  } else if (tab === 'neighbors') {
    await renderChannelNeighbors(channel, renderContext);
  } else if (tab === 'band-plan') {
    const data = await api(channelApiPath(configurationId, 'frequency-bands'));
    const overrideActive = data.band_source === 'P25_OVERRIDE';
    const homeBandColumns = [
      { id: 'band', label: 'Band', key: 'band', className: 'numeric' },
      { id: 'base', label: 'Base', fullLabel: 'Base MHz', render: (row) => frequency(row.base_hz), className: 'numeric', sortValue: (row) => Number(row.base_hz || 0) },
      { id: 'spacing', label: 'Space', fullLabel: 'Spacing kHz', render: (row) => row.spacing_hz ? (row.spacing_hz / 1000).toFixed(3) : '', className: 'numeric', sortValue: (row) => Number(row.spacing_hz || 0) },
      { id: 'bandwidth', label: 'BW Hz', fullLabel: 'Bandwidth Hz', key: 'bandwidth_hz', className: 'numeric' },
      { id: 'offset', label: 'Offset', fullLabel: 'Offset MHz', render: (row) => row.transmit_offset_hz ? (row.transmit_offset_hz / 1000000).toFixed(5) : '', className: 'numeric', sortValue: (row) => Number(row.transmit_offset_hz || 0) },
      { id: 'tdma', label: 'TDMA', render: (row) => yesNo(row.tdma), sortValue: (row) => Boolean(row.tdma) },
      { id: 'slots', label: 'Slots', key: 'timeslots', className: 'numeric' }
    ];
    if (!overrideActive) homeBandColumns.push(
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { id: 'observations', label: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    );
    const bandSource = badge(overrideActive ? 'P25 Override' : 'OTA Bandplan',
      overrideActive ? 'state-current' : '');
    content.append(tableSection('Home System Band Plan', data.home_bands || [], homeBandColumns,
      'No home-system band plan recorded', { type: 'channel-frequency-bands' }, null, bandSource));
    content.append(tableSection('ISSI Advertised Band Plans', data.foreign_bands || [], [
      { id: 'wacn', label: 'WACN', render: (row) => hex(row.foreign_wacn, 5), sortValue: (row) => Number(row.foreign_wacn || 0) },
      { id: 'system', label: 'Sys', fullLabel: 'Foreign System', render: (row) => hex(row.foreign_system_id, 3), sortValue: (row) => Number(row.foreign_system_id || 0) },
      { id: 'band', label: 'Band', key: 'band', className: 'numeric' },
      { id: 'mode', label: 'Mode', render: (row) => semanticLabel(row.access_mode) },
      { id: 'base', label: 'Base', fullLabel: 'Base MHz', render: (row) => frequency(row.base_hz), className: 'numeric', sortValue: (row) => Number(row.base_hz || 0) },
      { id: 'spacing', label: 'Space', fullLabel: 'Spacing kHz', render: (row) => row.spacing_hz ? (row.spacing_hz / 1000).toFixed(3) : '', className: 'numeric', sortValue: (row) => Number(row.spacing_hz || 0) },
      { id: 'bandwidth', label: 'BW Hz', fullLabel: 'Bandwidth Hz', key: 'bandwidth_hz', className: 'numeric' },
      { id: 'offset', label: 'Offset', fullLabel: 'Offset MHz', render: (row) => row.transmit_offset_hz ? (row.transmit_offset_hz / 1000000).toFixed(5) : '', className: 'numeric', sortValue: (row) => Number(row.transmit_offset_hz || 0) },
      { id: 'slots', label: 'Slots', key: 'timeslots', className: 'numeric' },
      { id: 'voice-rate', label: 'Voice Rate', render: (row) => semanticLabel(row.voice_rate) },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { id: 'observations', label: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No ISSI-advertised band plans recorded', { type: 'channel-foreign-frequency-bands' }));
  } else if (tab === 'patches') {
    const data = await api(channelApiPath(configurationId, 'patch-groups'), {
      offset: route.get('offset'), limit: 100
    });
    const talkgroups = patchMembersByLocalGroup(data.talkgroups);
    const radios = patchMembersByLocalGroup(data.radios);
    const memberValues = (values, formatter, omitted = 0) => {
      const span = node('span');
      const rendered = (values || []).map(formatter).filter((value) =>
        value !== null && value !== undefined && value !== '');
      rendered.forEach((value, index) => {
        if (index) span.append(document.createTextNode(', '));
        span.append(valueNode(value));
      });
      if (omitted > 0) {
        if (rendered.length) span.append(document.createTextNode(', '));
        span.append(node('span', 'muted', `+${number(omitted)} more`));
      }
      return span;
    };
    const omittedMembers = (row, member) => Math.max(0,
      Number(row?.[`${member.slice(0, -1)}_count`] || 0) - Number(row?.[`${member}_included`] || 0));
    const groups = data.groups || [];
    const columns = [
      { id: 'patch-id', label: 'Local Patch', fullLabel: 'Local Patch Talkgroup ID',
        render: (row) => groupIdentityLink(row, row.local_patch_group_id),
        className: 'numeric', sortValue: (row) => Number(row.local_patch_group_id) },
      { id: 'patch-name', label: 'Alias', fullLabel: 'Local Patch Alias', render: (row) =>
        row.patch_alias_name ? groupIdentityLink(row, row.local_patch_group_id, row.patch_alias_name) : '',
        className: 'alias-cell', sortValue: (row) => row.patch_alias_name || '' },
      { id: 'member-talkgroup-ids', label: 'Local TGIDs', fullLabel: 'Local Member Talkgroup IDs', render: (row) =>
        memberValues(talkgroups.get(row.local_patch_group_id), (member) =>
          groupIdentityLink(member, member.local_talkgroup_id), omittedMembers(row, 'talkgroups')) },
      { id: 'member-talkgroup-names', label: 'TG Aliases', fullLabel: 'Local Talkgroup Aliases',
        className: 'alias-cell', render: (row) =>
          memberValues(talkgroups.get(row.local_patch_group_id), (member) => member.alias_name ?
            groupIdentityLink(member, member.local_talkgroup_id, member.alias_name) : '',
          omittedMembers(row, 'talkgroups')) },
      { id: 'member-radio-ids', label: 'Local Radios', fullLabel: 'Local Radio IDs', render: (row) =>
        memberValues(radios.get(row.local_patch_group_id), (member) =>
          radioLink(member, member.local_radio_id), omittedMembers(row, 'radios')) },
      { id: 'member-radio-names', label: 'Radio Aliases', fullLabel: 'Local Radio Aliases',
        className: 'alias-cell', render: (row) =>
          memberValues(radios.get(row.local_patch_group_id), (member) => member.alias_name ?
            radioLink(member, member.local_radio_id, member.alias_name) : '', omittedMembers(row, 'radios')) },
      { id: 'state', label: 'State', render: (row) => stateBadge(row.state), sortValue: (row) => row.state || '' },
      { id: 'observations', label: 'Observations', key: 'observation_count', className: 'numeric' },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ];
    if (groups.some((row) => Number(row.version))) columns.splice(2, 0,
      { id: 'version', label: 'Version', key: 'version', className: 'numeric' });
    const patchPage = { rows: groups, offset: Number(data.offset || 0), limit: Number(data.limit || 100),
      has_more: Boolean(data.has_more), next_offset: data.next_offset };
    const trailing = fragment();
    if (data.members_truncated) trailing.append(node('p', 'logging-notice warning',
      `Large patches are bounded to ${number(data.member_limit_per_group)} members per patch and ` +
      `${number(data.member_limit_total)} members per type on this page. Omitted counts are shown in the table.`));
    trailing.append(pager(patchPage, 'bottom', 'Patch groups'));
    content.append(tableSection('Patches', groups, columns, 'No patches recorded',
      { type: 'channel-patches' }, trailing));
  } else if (tab === 'activity') {
    await renderActivity({ configuration_id: configurationId });
  } else {
    await renderTrunkedChannelInfo(channel, renderContext);
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

function activityIdentifier(row, value, kind, reference) {
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
  if (['talkgroup', 'patch_group'].includes(kind)) {
    return groupIdentityLink(row, value, identifier, reference);
  }
  if (kind === 'radio') return radioLink(row, value, identifier, reference);
  return identifier;
}

function activityTargetKind(row) {
  const kind = identityKind(row?.target_kind);
  return ['talkgroup', 'patch_group', 'radio'].includes(kind) ? kind : '';
}

function activityTargetIdentifier(row) {
  if (isAnalogChannel(row)) return '';
  const kind = activityTargetKind(row);
  return activityIdentifier(row, row.target_id, kind, row.target_entity_ref);
}

function activitySourceAlias(row) {
  const alias = row.source_alias_name || '';
  if (!alias) return '';
  return specialIdentifierLabel(row, row.source_radio_id, 'radio') ?
    alias : radioLink(row, row.source_radio_id, alias, row.source_entity_ref);
}

function activitySourceTalkerAlias(row) {
  const alias = String(row.source_talker_alias || '').trim();
  if (!alias) return '';
  return specialIdentifierLabel(row, row.source_radio_id, 'radio') ?
    alias : radioLink(row, row.source_radio_id, alias, row.source_entity_ref);
}

function activityTargetAlias(row) {
  if (isAnalogChannel(row)) return '';
  const alias = row.target_alias_name || '';
  if (!alias) return '';
  const kind = activityTargetKind(row);
  if (specialIdentifierLabel(row, row.target_id, kind)) return alias;
  if (['talkgroup', 'patch_group'].includes(kind)) {
    return groupIdentityLink(row, row.target_id, alias, row.target_entity_ref);
  }
  if (kind === 'radio') return radioLink(row, row.target_id, alias, row.target_entity_ref);
  return alias;
}

function activityChannel(row) {
  const label = String(row.name || '').trim();
  const target = capabilityAllowed(ACCESS_CAPABILITIES.RADIO) ? entityRefHref(row.entity_ref) : '';
  const channel = label && target ? anchor(label, target) : label;
  const details = [
    Number(row.frequency_hz) ? `${frequency(row.frequency_hz)} MHz` : '',
    row.lcn == null || row.lcn === '' ? '' : `LCN ${row.lcn}`,
    timeslotLabel(row.timeslot)
  ].filter(Boolean).join(' · ');
  if (!channel) return details;
  return details ? fragment(channel, node('small', 'identity-summary-context', details)) : channel;
}

function activityColumns() {
  return [
    { id: 'time', label: 'Seen', fullLabel: 'Observed Time', render: (row) => dateTime(row.observed_at_ms), sortValue: (row) => Number(row.observed_at_ms || 0) },
    { id: 'action', label: 'Action', key: 'action' },
    { id: 'event', label: 'Event', key: 'event_type' },
    { id: 'source', label: 'Src', fullLabel: 'Source ID',
      render: (row) => activityIdentifier(row, row.source_radio_id, 'radio', row.source_entity_ref),
      className: 'numeric identifier-cell', sortValue: (row) => Number(row.source_radio_id || 0) },
    { id: 'source-alias', label: 'Src Alias', fullLabel: 'Source Alias',
      render: activitySourceAlias, className: 'alias-cell',
      sortValue: (row) => row.source_alias_name || '' },
    { id: 'source-ota-alias', label: 'Src OTA Alias', fullLabel: 'Source Over-the-Air Talker Alias',
      render: activitySourceTalkerAlias, className: 'alias-cell',
      sortValue: (row) => row.source_talker_alias || '' },
    { id: 'target', label: 'Tgt', fullLabel: 'Target ID', render: activityTargetIdentifier,
      className: 'numeric identifier-cell', sortValue: (row) => Number(row.target_id || 0) },
    { id: 'target-alias', label: 'Tgt Alias', fullLabel: 'Target Alias', render: activityTargetAlias,
      className: 'alias-cell', sortValue: (row) => row.target_alias_name || '' },
    { id: 'channel', label: 'Channel', render: activityChannel, sortValue: (row) =>
      Number(row.frequency_hz || 0) },
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
  const titleActions = node('div', 'section-title-actions');
  const activityTable = table(initialRows, columns, 'No activity recorded',
    { type: 'activity', rowKey: (row) => row.id, layoutMenuHost: titleActions });
  activityTable.setAttribute('aria-live', 'off');
  const block = section(title, activityTable, titleActions);
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
    titleActions.prepend(refreshControls);
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

function channelMode(row) {
  const family = protocolFamily(row);
  const decoder = decoderLabel(row.decoder, true);
  return family && decoder && family.toUpperCase() === decoder.toUpperCase() ? family :
    [family, decoder].filter(Boolean).join(' · ');
}

function channelDetails(row) {
  if (String(row.channel_kind || '').toUpperCase() === 'TRUNKED') return channelDirectoryRfIdentity(row);
  return isP25(row) && row.nac != null ? `NAC ${hex(row.nac, 3)}` : '';
}

function channelDirectoryColumns() {
  return [
    { id: 'name', label: 'Name', render: (row) => {
      const label = row.name || 'Unnamed channel';
      const target = entityRefHref(row.entity_ref);
      return target ? anchor(label, target) : label;
    }, className: 'alias-cell', sort: 'name', sortValue: (row) => row.name || '' },
    { id: 'type', label: 'Type', render: (row) =>
      String(row.channel_kind || '').toUpperCase() === 'TRUNKED' ? 'Trunked' : 'Conventional',
      sort: 'type', sortValue: (row) => row.channel_kind || '' },
    { id: 'system', label: 'System / Site', render: (row) =>
      [row.system_name, row.site_name].filter(Boolean).join(' · '),
      sortValue: (row) => `${row.system_name || ''}\u0000${row.site_name || ''}` },
    { id: 'mode', label: 'Mode', render: channelMode, sort: 'decoder', sortValue: channelMode },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz',
      render: (row) => frequency(row.primary_frequency_hz), className: 'numeric', sort: 'frequency',
      sortValue: (row) => Number(row.primary_frequency_hz || 0) },
    { id: 'details', label: 'Details', render: channelDetails, sortValue: channelDetails },
    { id: 'logical-calls', label: 'Logical Calls', render: (row) => number(row.logical_call_count), className: 'numeric', sort: 'logical_call_count', sortValue: (row) => Number(row.logical_call_count || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen', render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen', sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

async function renderChannels() {
  const renderContext = captureRenderContext();
  const directory = createAsyncSection('Channels', {
    action: exportCsvLink('channels'),
    loadingMessage: 'Loading channels…',
    errorMessage: 'The channel directory could not be loaded.'
  });
  if (!beginPage(renderContext,
    pageHeader('Channels', 'Every configured trunked and conventional receiver channel'),
    searchBar('Search name, system, site, protocol, or frequency'), directory.element)) return;
  await directory.load(
    () => apiPage('/api/v1/channels', pageParameters()),
    (page) => pagedTableContent(page, channelDirectoryColumns(), 'channels', {
      itemLabel: 'Channels', tableOptions: {
        layoutMenuHost: directory.titleActions, controller: directory.tableController
      }
    }),
    renderContext);
}

function channelTabItems(channel) {
  const values = { configuration_id: channel.configuration_id };
  const items = [];
  items.push({ id: 'info', label: 'Info',
    href: href('channel', { ...values, tab: 'info' }) });
  const analog = ['AM', 'NBFM'].includes(String(channel.decoder || '').toUpperCase());
  if (!analog && channelCapability(channel, 'group_identities')) {
    items.push({ id: 'groups', label: 'Groups',
      href: href('channel', { ...values, tab: 'groups' }) });
  }
  if (channelCapability(channel, 'radios')) {
    items.push({ id: 'radios', label: 'Radios',
      href: href('channel', { ...values, tab: 'radios' }) });
  }
  if (channelCapability(channel, 'activity')) {
    items.push({ id: 'activity', label: 'Activity',
      href: href('channel', { ...values, tab: 'activity' }),
      disabled: !detailedHistoryAvailable(), disabledReason: 'Detailed history logging is not running' });
  }
  return items;
}

function channelGroupIdentityColumns() {
  return [
    { id: 'group-identity-id', label: 'Group', className: 'numeric',
      sort: 'group_identity', render: (row) => groupIdentityLink(row) },
    { id: 'group-identity-kind', label: 'Kind', render: (row) => groupIdentityLabel(row) },
    { id: 'group-identity-name', label: 'Alias', render: (row) => groupIdentityAliasLink(row),
      className: 'alias-cell', sort: 'alias', sortValue: aliasLabel },
    { id: 'group-identity-description', label: 'Description', key: 'alias_description',
      className: 'alias-cell' },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency',
      sortValue: (row) => Number(row.frequency_hz || 0) },
    { id: 'timeslot', label: 'Slot', key: 'timeslot', className: 'numeric', sort: 'slot',
      render: (row) => identifierNumber(row.timeslot) },
    { id: 'logical-calls', label: 'Logical Calls', render: (row) => number(row.logical_call_count),
      className: 'numeric', sort: 'logical_call_count',
      sortValue: (row) => Number(row.logical_call_count || 0) },
    { id: 'encrypted-logical-calls', label: 'Encrypted',
      render: (row) => number(row.encrypted_logical_call_count), className: 'numeric',
      sort: 'encrypted_logical_call_count',
      sortValue: (row) => Number(row.encrypted_logical_call_count || 0) },
    { id: 'source', label: 'Last Source', key: 'last_source_radio_id', className: 'numeric',
      render: (row) => row.last_source_radio_id == null ? '' :
        radioLink(row, row.last_source_radio_id) },
    { id: 'source-alias', label: 'Source Alias', className: 'alias-cell',
      render: (row) => row.last_source_alias_name ?
        radioLink(row, row.last_source_radio_id, row.last_source_alias_name) : '' },
    { id: 'first-seen', label: 'First', fullLabel: 'First Seen',
      render: (row) => dateTime(row.first_seen_ms), sort: 'first_seen',
      sortValue: (row) => Number(row.first_seen_ms || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen',
      sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

function channelRadioColumns() {
  return [
    { id: 'radio', label: 'Radio', className: 'numeric', sort: 'radio',
      render: (row) => radioLink(row) },
    { id: 'radio-alias', label: 'Alias', render: (row) => aliasLabel(row) ?
      radioLink(row, undefined, aliasLabel(row)) : '', className: 'alias-cell', sort: 'alias',
      sortValue: aliasLabel },
    { id: 'frequency', label: 'MHz', fullLabel: 'Frequency MHz',
      render: (row) => frequency(row.frequency_hz), className: 'numeric', sort: 'frequency',
      sortValue: (row) => Number(row.frequency_hz || 0) },
    { id: 'timeslot', label: 'Slot', key: 'timeslot', className: 'numeric', sort: 'slot',
      render: (row) => identifierNumber(row.timeslot) },
    { id: 'logical-calls', label: 'Logical Calls', render: (row) => number(row.logical_call_count),
      className: 'numeric', sort: 'logical_call_count',
      sortValue: (row) => Number(row.logical_call_count || 0) },
    { id: 'encrypted-logical-calls', label: 'Encrypted',
      render: (row) => number(row.encrypted_logical_call_count), className: 'numeric',
      sort: 'encrypted_logical_call_count',
      sortValue: (row) => Number(row.encrypted_logical_call_count || 0) },
    { id: 'source-logical-calls', label: 'As Source',
      render: (row) => number(row.source_logical_call_count), className: 'numeric',
      sort: 'source_logical_call_count',
      sortValue: (row) => Number(row.source_logical_call_count || 0) },
    { id: 'target-logical-calls', label: 'As Target',
      render: (row) => number(row.target_logical_call_count), className: 'numeric',
      sort: 'target_logical_call_count',
      sortValue: (row) => Number(row.target_logical_call_count || 0) },
    { id: 'group-logical-calls', label: 'Group',
      render: (row) => number(row.group_logical_call_count), className: 'numeric',
      sort: 'group_logical_call_count',
      sortValue: (row) => Number(row.group_logical_call_count || 0) },
    { id: 'private-logical-calls', label: 'Private',
      render: (row) => number(row.private_logical_call_count), className: 'numeric',
      sort: 'private_logical_call_count',
      sortValue: (row) => Number(row.private_logical_call_count || 0) },
    { id: 'last-talkgroup', label: 'Last Talkgroup', key: 'last_talkgroup_id', className: 'numeric',
      render: (row) => row.last_talkgroup_id == null ? '' :
        groupIdentityLink(row, row.last_talkgroup_id) },
    { id: 'talkgroup-name', label: 'Talkgroup Alias', className: 'alias-cell',
      render: (row) => row.last_talkgroup_alias_name ?
        groupIdentityLink(row, row.last_talkgroup_id, row.last_talkgroup_alias_name) : '' },
    { id: 'last-peer', label: 'Last Peer', key: 'last_peer_radio_id', className: 'numeric',
      render: (row) => row.last_peer_radio_id == null ? '' :
        radioLink(row, row.last_peer_radio_id) },
    { id: 'peer-alias', label: 'Peer Alias', className: 'alias-cell',
      render: (row) => row.last_peer_alias_name ?
        radioLink(row, row.last_peer_radio_id, row.last_peer_alias_name) : '' },
    { id: 'first-seen', label: 'First', fullLabel: 'First Seen',
      render: (row) => dateTime(row.first_seen_ms), sort: 'first_seen',
      sortValue: (row) => Number(row.first_seen_ms || 0) },
    { id: 'last-seen', label: 'Seen', fullLabel: 'Last Seen',
      render: (row) => dateTime(row.last_seen_ms), sort: 'last_seen',
      sortValue: (row) => Number(row.last_seen_ms || 0) }
  ];
}

async function renderChannelGroupIdentities(configurationId) {
  const page = await apiPage(channelApiPath(configurationId, 'group-identities'), pageParameters({
    limit: CHANNEL_IDENTITY_PAGE_LIMIT
  }));
  content.append(pagedSection('Groups', page, channelGroupIdentityColumns(),
    'Search group ID or alias', 'channel-group-identities',
    exportCsvLink('channel-group-identities', { configuration_id: configurationId })));
}

async function renderChannelRadios(configurationId) {
  const page = await apiPage(channelApiPath(configurationId, 'radios'), pageParameters({
    limit: CHANNEL_IDENTITY_PAGE_LIMIT
  }));
  content.append(pagedSection('Radios', page, channelRadioColumns(),
    'Search radio ID or alias', 'channel-radios',
    exportCsvLink('channel-radios', { configuration_id: configurationId })));
}

async function renderConventionalChannel(data, channel, configurationId, renderContext) {
  const tabItems = channelTabItems(channel);
  const requestedTab = route.get('tab') || 'info';
  const tab = tabItems.some((item) => item.id === requestedTab) ? requestedTab : tabItems[0].id;
  if (!beginPage(renderContext,
    pageHeader(channel.name || 'Channel',
      `${protocolFamily(channel)} · Conventional`),
    tabs(tabItems, tab))) return;

  if (tab === 'activity') {
    await renderActivity({ configuration_id: configurationId });
  } else if (tab === 'groups') {
    await renderChannelGroupIdentities(configurationId);
  } else if (tab === 'radios') {
    await renderChannelRadios(configurationId);
  } else {
    content.append(section('Channel Info', keyValues([
      ['Name', channel.name],
      ['Protocol', protocolFamily(channel)], ['Decoder', decoderDisplay(channel.decoder)],
      ['Alias List', aliasListLink(channel.alias_list_name, channel.alias_list_id)],
      ['Frequency', frequency(channel.primary_frequency_hz)],
      ['NAC', hexDecimalPair(channel.nac, 3)], ['First Seen', dateTime(channel.first_seen_ms)],
      ['Last Seen', dateTime(channel.last_seen_ms)]
    ])));
    content.append(tableSection('Frequency Summaries', data.summaries || [], [
      { id: 'channel', label: 'Channel', render: (row) => [
        Number(row.frequency_hz) ? `${frequency(row.frequency_hz)} MHz` : '',
        timeslotLabel(row.timeslot)
      ].filter(Boolean).join(' · '), sortValue: (row) => Number(row.frequency_hz || 0) },
      { id: 'logical-calls', label: 'Logical Calls',
        render: (row) => number(row.logical_call_count), className: 'numeric',
        sortValue: (row) => Number(row.logical_call_count || 0) },
      { id: 'recorded-logical-calls', label: 'Rec', fullLabel: 'Recorded Logical Calls',
        render: (row) => number(row.recorded_logical_call_count), className: 'numeric',
        sortValue: (row) => Number(row.recorded_logical_call_count || 0) },
      { id: 'stream-submitted-logical-calls', label: 'Submitted', fullLabel: 'Submitted to Streamer',
        render: (row) => number(row.stream_submitted_logical_call_count), className: 'numeric',
        sortValue: (row) => Number(row.stream_submitted_logical_call_count || 0) },
      { id: 'encrypted-logical-calls', label: 'Enc', fullLabel: 'Encrypted Logical Calls',
        render: (row) => number(row.encrypted_logical_call_count), className: 'numeric encrypted',
        sortValue: (row) => Number(row.encrypted_logical_call_count || 0) },
      { id: 'first-seen', label: 'First', fullLabel: 'First Observed', render: (row) => dateTime(row.first_seen_ms), sortValue: (row) => Number(row.first_seen_ms || 0) },
      { id: 'last-seen', label: 'Seen', fullLabel: 'Last Observed', render: (row) => dateTime(row.last_seen_ms), sortValue: (row) => Number(row.last_seen_ms || 0) }
    ], 'No frequency summaries recorded', { type: 'channel-frequencies' }));
  }
}

async function renderChannel() {
  const renderContext = captureRenderContext();
  const configurationId = String(route.get('configuration_id') || '').trim();
  if (!configurationId) throw new Error('Channel configuration ID is missing from the URL');
  const data = await api(channelApiPath(configurationId));
  const channel = data?.channel;
  if (!channel || typeof channel !== 'object' || Array.isArray(channel)) {
    throw new Error('The channel response is invalid');
  }
  if (String(channel.channel_kind || '').toUpperCase() === 'TRUNKED') {
    await renderTrunkedChannel(channel, configurationId, renderContext);
  } else {
    await renderConventionalChannel(data, channel, configurationId, renderContext);
  }
}

function adminUserRecord(value) {
  return {
    username: String(value?.username || '').trim(),
    tier: accessTierFromWire(value?.tier) || 'PUBLIC',
    passwordChangedAtEpochMillis: Number(value?.password_changed_at_epoch_millis || 0),
    authRevision: Number(value?.auth_revision || 0),
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
  const titleActions = sectionActionHost(create);
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
  ], 'No web users have been created', {
    type: 'admin-users', sortable: false, layoutMenuHost: titleActions
  }));
  content.append(section('User management', body, titleActions));
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

function webAccessControl(policy, statusHost) {
  const wrapper = node('div', 'admin-web-access-control');
  const copy = node('div', 'admin-web-access-copy');
  copy.append(node('strong', '', 'Entire web interface'),
    node('p', '', 'Set the minimum tier for every receiver page, API, live stream, audio request, diagnostic, and ' +
      'export. The application shell and sign-in endpoints remain public so authorized users can sign in.'));
  const control = node('label', 'admin-web-access-tier');
  control.append(node('span', '', 'Minimum tier'), accessPolicyTierControl(policy, statusHost));
  wrapper.append(copy, control);
  return wrapper;
}

async function renderAdminAccess() {
  const response = await requestJson('/api/v1/admin/access', { csrf: false });
  const policies = adminAccessPolicies(response).sort((left, right) =>
    (left.displayName || left.id).localeCompare(right.displayName || right.id));
  const webPolicy = policies.find((policy) => policy.id === ACCESS_CAPABILITIES.WEB_ACCESS);
  const featurePolicies = policies.filter((policy) => policy.id !== ACCESS_CAPABILITIES.WEB_ACCESS);
  const statusHost = node('div', 'admin-operation-status');
  statusHost.setAttribute('role', 'status');
  const titleActions = sectionActionHost();
  const body = node('div', 'admin-section-body');
  body.append(node('p', 'admin-section-intro',
    'Web access applies first. Each capability below can then require a higher tier for its page and backing ' +
      'APIs.'), statusHost);
  if (webPolicy) body.append(webAccessControl(webPolicy, statusHost));
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
    ], 'No feature access capabilities were returned', {
      type: 'admin-access', sortable: false, layoutMenuHost: titleActions
    }));
  content.append(section('Access policy', body, titleActions));
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
  const actions = node('div', 'section-title-actions');
  actions.append(anchor('Manage Alias Membership', href('aliases', { aliasTab: 'configure' }),
    'button secondary'), create);
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
    ], 'No scan lists are configured', {
      type: 'admin-scan-lists', sortable: false, layoutMenuHost: actions
    }));
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

async function renderAdminRadioReferenceSettings() {
  const body = node('div', 'admin-section-body radioreference-settings');
  const accountForm = node('form',
    'admin-form admin-settings-form settings-card settings-card-form radioreference-account-form');
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
    node('p', 'settings-card-description', 'Connect the receiver with a current RadioReference Premium account.'),
    formField('Username', userName), formField('Password', password,
    'A current Premium subscription is required. The password is never returned to the browser.'),
    rememberLabel, accountMessage, accountActions);

  const regionForm = node('form',
    'admin-form admin-settings-form settings-card settings-card-form radioreference-region-form');
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
    node('p', 'settings-card-description', 'Choose the region used for exact-frequency spectrum lookups.'),
    formField('Country', country), formField('State or region', state), regionMessage, regionActions);

  const settingsForms = settingsCardGrid(accountForm, regionForm);
  settingsForms.classList.add('admin-settings-form-stack');
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

async function renderReceiverSettings() {
  const renderContext = captureRenderContext();
  await renderAdminReceiverBehaviorSettings();
  if (!renderIsCurrent(renderContext)) return;
  await renderAdminSpectrumSnapSettings();
  if (!renderIsCurrent(renderContext)) return;
  await renderAdminRadioReferenceSettings();
}

async function renderAdminSpectrumSnapSettings() {
  const body = node('div', 'admin-section-body spectrum-snap-settings');
  const form = node('form', 'admin-form settings-page-form');
  const country = node('select');
  country.required = true;
  country.disabled = true;
  country.append(node('option', '', 'Loading countries…'));
  const message = node('div', 'admin-form-message', 'Loading spectrum snap settings…');
  message.setAttribute('role', 'status');
  const save = node('button', '', 'Save Spectrum Country');
  save.type = 'submit';
  save.disabled = true;
  const actions = node('div', 'admin-form-actions');
  actions.append(save);
  const presetSummary = node('p', 'settings-card-description');
  const card = settingsCard('Country frequency scopes',
    'Select the regulatory catalog used for FFT band indicators and optional cursor snapping.',
    formField('Country', country,
      'The selected catalog applies receiver-wide. Only the United States catalog is currently bundled.'),
    presetSummary);
  const footer = node('div', 'settings-form-footer');
  footer.append(message, actions);
  form.append(settingsCardGrid(card), footer);
  body.append(form);
  content.append(section('Spectrum frequency scopes', body));

  let confirmed = null;
  const apply = (documentValue) => {
    confirmed = documentValue;
    country.replaceChildren(...documentValue.countries.map((item) => {
      const option = node('option', '', item.label);
      option.value = item.code;
      return option;
    }));
    country.value = documentValue.countryCode;
    const snapping = documentValue.scopes.filter((scope) => scope.snap).length;
    presetSummary.textContent = `${number(documentValue.scopes.length)} frequency scopes are available for ${
      documentValue.countryLabel}; ${number(snapping)} include snap rules.`;
    country.disabled = false;
    save.disabled = true;
  };
  country.addEventListener('change', () => {
    save.disabled = !confirmed || country.value === confirmed.countryCode;
    message.textContent = save.disabled ? 'Spectrum snap country is unchanged.' :
      'Save to apply this spectrum snap country to every user.';
  });
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!confirmed || !form.reportValidity() || save.disabled) return;
    country.disabled = true;
    save.disabled = true;
    message.textContent = 'Saving spectrum snap country…';
    try {
      apply(await requestSpectrumSnapPresetDocument('/api/v1/admin/spectrum-snap-presets', 'PUT',
        country.value, confirmed.revision));
      message.textContent = 'Spectrum snap country saved.';
    } catch (error) {
      if (error.current) apply(error.current);
      message.textContent = error.message;
      country.disabled = false;
      save.disabled = !confirmed || country.value === confirmed.countryCode;
    }
  });
  try {
    apply(await requestSpectrumSnapPresetDocument('/api/v1/admin/spectrum-snap-presets'));
    message.textContent = 'Spectrum snap country loaded.';
  } catch (error) {
    message.textContent = error.message;
  }
}

function decodeReceiverSettingsEnvelope(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      !Number.isInteger(value.revision) || value.revision < 1 ||
      !value.settings || typeof value.settings !== 'object' || Array.isArray(value.settings) ||
      Object.keys(value).sort().join('|') !== 'revision|settings' ||
      Object.keys(value.settings).sort().join('|') !== 'traffic_grant_age_out_milliseconds' ||
      !Number.isInteger(value.settings.traffic_grant_age_out_milliseconds) ||
      value.settings.traffic_grant_age_out_milliseconds < 100 ||
      value.settings.traffic_grant_age_out_milliseconds > 15000) {
    const error = new Error('The server returned invalid Receiver Settings.');
    error.code = 'invalid_receiver_settings_response';
    throw error;
  }
  return {
    revision: value.revision,
    settings: { ...value.settings }
  };
}

async function requestReceiverSettings(method = 'GET', settings = null, revision = null) {
  const headers = { Accept: 'application/json' };
  const options = { method, headers };
  if (method === 'PUT') {
    if (!Number.isInteger(revision) || revision < 1) throw new Error('Receiver Settings must be loaded before saving.');
    headers['Content-Type'] = 'application/json';
    headers['If-Match'] = `"${revision}"`;
    options.body = JSON.stringify(settings);
  }
  const response = await jsonDocumentFetch('/api/v1/admin/receiver-settings', options);
  let documentValue = null;
  try {
    documentValue = await response.json();
  } catch (_) { }
  if (!response.ok) {
    const failure = documentValue?.error && typeof documentValue.error === 'object' ? documentValue.error : null;
    const error = new Error(failure?.message || 'Receiver Settings could not be saved.');
    error.status = response.status;
    error.code = failure?.code || (response.status === 409 ? 'receiver_settings_conflict' : 'receiver_settings_failed');
    if (response.status === 409) {
      try { error.current = decodeReceiverSettingsEnvelope(documentValue); } catch (_) { }
    }
    throw error;
  }
  return decodeReceiverSettingsEnvelope(documentValue);
}

async function renderAdminReceiverBehaviorSettings() {
  const body = node('div', 'admin-section-body receiver-settings');
  const form = node('form', 'admin-form settings-page-form receiver-settings-form');
  const grantAge = node('input');
  grantAge.type = 'number';
  grantAge.min = '100';
  grantAge.max = '15000';
  grantAge.required = true;
  grantAge.disabled = true;
  const message = node('div', 'admin-form-message', 'Loading receiver settings…');
  message.setAttribute('role', 'status');
  const save = node('button', '', 'Save Receiver Settings');
  save.type = 'submit';
  save.disabled = true;
  const actions = node('div', 'admin-form-actions');
  actions.append(save);
  const group = settingsCard('Traffic grant timing',
    'This receiver-wide timing controls when inactive traffic grants become idle.',
    formField('Idle grant retention (milliseconds)', grantAge,
      'How long inactive traffic grants remain in the shared Live state.'));
  const footer = node('div', 'settings-form-footer');
  footer.append(message, actions);
  form.append(settingsCardGrid(group), footer);
  body.append(form);
  content.append(section('Receiver behavior', body));

  let confirmed = null;
  const apply = (envelope) => {
    confirmed = envelope;
    const value = envelope.settings;
    grantAge.value = String(value.traffic_grant_age_out_milliseconds);
  };
  const disable = (value) => {
    grantAge.disabled = value;
    save.disabled = value;
  };
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!form.reportValidity() || save.disabled) return;
    disable(true);
    message.textContent = 'Saving Receiver Settings…';
    try {
      const next = await requestReceiverSettings('PUT', {
        traffic_grant_age_out_milliseconds: Number(grantAge.value)
      }, confirmed?.revision);
      apply(next);
      message.textContent = 'Receiver Settings saved.';
    } catch (error) {
      if (error?.code === 'receiver_settings_conflict' && error.current) {
        apply(error.current);
        message.textContent = 'Receiver Settings changed in another session. Current server values were reloaded.';
      } else {
        if (confirmed) apply(confirmed);
        message.textContent = error.message;
      }
    } finally {
      disable(false);
    }
  });
  try {
    apply(await requestReceiverSettings());
    disable(false);
    message.textContent = '';
  } catch (error) {
    message.textContent = error.message;
  }
}

function p25OverrideInput(label, field, value = '', options = {}) {
  const input = node('input');
  input.dataset.p25OverrideField = field;
  input.type = options.type || 'text';
  input.value = value;
  if (options.required) input.required = true;
  if (options.pattern) input.pattern = options.pattern;
  if (options.placeholder) input.placeholder = options.placeholder;
  if (options.min !== undefined) input.min = String(options.min);
  if (options.max !== undefined) input.max = String(options.max);
  if (options.step !== undefined) input.step = String(options.step);
  return formField(label, input, options.detail || '');
}

function p25OverrideNumber(value, divisor) {
  if (!Number.isFinite(Number(value))) return '';
  return String(Number((Number(value) / divisor).toFixed(6)));
}

function p25OverrideCreateRouteProfile(parameters) {
  if (!parameters || typeof parameters.get !== 'function' || parameters.get('createP25Override') !== '1') {
    return null;
  }
  const hexValue = (name, width, maximum) => {
    const value = String(parameters.get(name) || '').trim();
    if (!new RegExp(`^[0-9A-Fa-f]{${width}}$`).test(value)) return null;
    const parsed = Number.parseInt(value, 16);
    return Number.isSafeInteger(parsed) && parsed >= 0 && parsed <= maximum ? parsed : null;
  };
  const profile = {
    wacn: hexValue('wacn', 5, 0xFFFFF),
    system: hexValue('system', 3, 0xFFF),
    rfss: hexValue('rfss', 2, 0xFF),
    site: hexValue('site', 2, 0xFF)
  };
  return Object.values(profile).every((value) => value !== null) ? profile : null;
}

function p25OverrideCreateRouteConfigurationId(parameters) {
  if (!parameters || typeof parameters.get !== 'function' || parameters.get('createP25Override') !== '1') {
    return null;
  }
  return canonicalConfigurationId(parameters.get('configuration_id')) || null;
}

function p25OverrideDetectedBands(documentValue, requestedProfile) {
  const detectedProfile = {
    wacn: documentValue?.wacn,
    system: documentValue?.system_id,
    rfss: documentValue?.rfss,
    site: documentValue?.site_id
  };
  if (documentValue?.band_source !== 'OTA' || !p25OverrideSameScope(detectedProfile, requestedProfile) ||
      !Array.isArray(documentValue.home_bands)) return [];
  const bands = documentValue.home_bands.map((row) => ({
    identifier: row?.band,
    type: row?.tdma === true || row?.tdma === 1 ? 'TDMA' : 'FDMA',
    base_frequency: row?.base_hz,
    bandwidth: row?.bandwidth_hz,
    channel_spacing: row?.spacing_hz,
    transmit_offset: row?.transmit_offset_hz
  }));
  const valid = bands.every((band) => [band.identifier, band.base_frequency, band.bandwidth,
    band.channel_spacing, band.transmit_offset].every(Number.isSafeInteger));
  const unique = new Set(bands.map((band) => band.identifier)).size === bands.length;
  return valid && unique ? bands : [];
}

function p25OverrideSameScope(left, right) {
  if (!left || !right) return false;
  return ['wacn', 'system', 'rfss', 'site'].every((field) =>
    Number.isInteger(left[field]) && left[field] === right[field]);
}

function clearP25OverrideCreateRoute() {
  P25_OVERRIDE_CREATE_ROUTE_KEYS.forEach((key) => route.delete(key));
  window.history.replaceState({}, '', currentHref());
}

function p25OverrideBandRow(band = null) {
  const row = node('div', 'p25-override-band-row');
  const type = node('select');
  type.dataset.p25OverrideField = 'type';
  [['FDMA', 'FDMA'], ['TDMA', 'P25 2-slot TDMA']].forEach(([value, label]) => {
    const option = node('option', '', label);
    option.value = value;
    type.append(option);
  });
  type.value = band?.type === 'TDMA' ? 'TDMA' : 'FDMA';
  const remove = node('button', 'button danger p25-override-remove', 'Remove band');
  remove.type = 'button';
  remove.addEventListener('click', () => row.remove());
  row.append(
    p25OverrideInput('Band ID', 'identifier', band?.identifier ?? '',
      { type: 'number', required: true, min: 0, max: 15, step: 1 }),
    formField('Type', type),
    p25OverrideInput('Base frequency (MHz)', 'base_frequency',
      p25OverrideNumber(band?.base_frequency, 1_000_000),
      { type: 'number', required: true, min: 0, step: 0.000001 }),
    p25OverrideInput('Bandwidth (kHz)', 'bandwidth', p25OverrideNumber(band?.bandwidth, 1_000),
      { type: 'number', required: true, min: 0.001, step: 0.001 }),
    p25OverrideInput('Spacing (kHz)', 'channel_spacing',
      p25OverrideNumber(band?.channel_spacing, 1_000),
      { type: 'number', required: true, min: 0.001, step: 0.001 }),
    p25OverrideInput('Offset (MHz)', 'transmit_offset',
      p25OverrideNumber(band?.transmit_offset, 1_000_000),
      { type: 'number', required: true, step: 0.000001 }),
    remove
  );
  return row;
}

function p25OverrideProfileCard(profile = null) {
  const card = node('section', 'settings-card p25-override-profile');
  const header = node('div', 'settings-card-header p25-override-profile-header');
  const title = node('h3', 'settings-card-title', 'New P25 override');
  const remove = node('button', 'button danger', 'Delete profile');
  remove.type = 'button';
  remove.addEventListener('click', () => card.remove());
  header.append(title, remove);
  const body = node('div', 'settings-card-body');
  const identity = node('div', 'p25-override-identity');
  const hex = (value, width) => Number.isInteger(value) ? value.toString(16).toUpperCase().padStart(width, '0') : '';
  identity.append(
    p25OverrideInput('WACN (hex)', 'wacn', hex(profile?.wacn, 5),
      { required: true, pattern: '[0-9A-Fa-f]{1,5}', placeholder: 'BEE00' }),
    p25OverrideInput('System ID (hex)', 'system', hex(profile?.system, 3),
      { required: true, pattern: '[0-9A-Fa-f]{1,3}', placeholder: '49F' }),
    p25OverrideInput('RFSS (hex, optional)', 'rfss', hex(profile?.rfss, 2),
      { pattern: '[0-9A-Fa-f]{1,2}', placeholder: '01' }),
    p25OverrideInput('Site ID (hex, optional)', 'site', hex(profile?.site, 2),
      { pattern: '[0-9A-Fa-f]{1,2}', placeholder: '01' })
  );
  const bands = node('div', 'p25-override-bands');
  (profile?.bands || [null]).forEach((band) => bands.append(p25OverrideBandRow(band)));
  const addBand = node('button', 'button secondary', 'Add band');
  addBand.type = 'button';
  addBand.addEventListener('click', () => bands.append(p25OverrideBandRow()));
  body.append(identity, node('h4', 'p25-override-bands-title', 'Replacement bands'), bands, addBand);
  card.append(header, body);

  const updateTitle = () => {
    const wacn = card.querySelector('[data-p25-override-field="wacn"]')?.value.trim().toUpperCase();
    const system = card.querySelector('[data-p25-override-field="system"]')?.value.trim().toUpperCase();
    const rfss = card.querySelector('[data-p25-override-field="rfss"]')?.value.trim().toUpperCase();
    const site = card.querySelector('[data-p25-override-field="site"]')?.value.trim().toUpperCase();
    title.textContent = wacn && system ? `${wacn}-${system}${rfss && site ? ` · ${rfss}-${site}` : ''}` :
      'New P25 override';
  };
  identity.addEventListener('input', updateTitle);
  updateTitle();
  return card;
}

function p25OverrideProfilesFromForm(list) {
  const value = (host, field) => host.querySelector(`[data-p25-override-field="${field}"]`)?.value.trim() || '';
  const integer = (host, field, multiplier = 1) => Math.round(Number(value(host, field)) * multiplier);
  return [...list.querySelectorAll(':scope > .p25-override-profile')].map((profile) => {
    const rfss = value(profile, 'rfss');
    const site = value(profile, 'site');
    if (Boolean(rfss) !== Boolean(site)) throw new Error('RFSS and Site ID must both be filled in or both be blank.');
    const bands = [...profile.querySelectorAll('.p25-override-band-row')].map((band) => ({
      identifier: integer(band, 'identifier'),
      type: value(band, 'type'),
      base_frequency: integer(band, 'base_frequency', 1_000_000),
      bandwidth: integer(band, 'bandwidth', 1_000),
      channel_spacing: integer(band, 'channel_spacing', 1_000),
      transmit_offset: integer(band, 'transmit_offset', 1_000_000)
    }));
    if (!bands.length) throw new Error('Each P25 override must contain at least one band.');
    return {
      wacn: parseInt(value(profile, 'wacn'), 16),
      system: parseInt(value(profile, 'system'), 16),
      rfss: rfss ? parseInt(rfss, 16) : null,
      site: site ? parseInt(site, 16) : null,
      bands
    };
  });
}

async function requestP25BandplanOverrides(method = 'GET', profiles = null) {
  const headers = { Accept: 'application/json' };
  const options = { method, headers };
  if (method === 'PUT') {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify({ profiles });
  }
  const response = await jsonDocumentFetch('/api/v1/admin/p25-bandplan-overrides', options);
  let documentValue = null;
  try { documentValue = await response.json(); } catch (_) { }
  if (!response.ok) {
    const failure = documentValue?.error && typeof documentValue.error === 'object' ? documentValue.error : null;
    throw new Error(failure?.message || 'P25 bandplan overrides could not be saved.');
  }
  if (!documentValue || !Array.isArray(documentValue.profiles)) {
    throw new Error('The server returned invalid P25 bandplan overrides.');
  }
  return documentValue;
}

async function renderAdminP25BandplanOverrides() {
  const renderRoute = route.toString();
  const body = node('div', 'admin-section-body');
  const form = node('form', 'admin-form settings-page-form p25-overrides-form');
  const intro = node('p', 'p25-overrides-intro',
    'A matching profile replaces the complete over-the-air band plan only for P25 channels that have the override enabled. Site-specific profiles take priority over system-wide profiles.');
  const list = node('div', 'p25-override-profile-list');
  const message = node('div', 'admin-form-message', 'Loading P25 bandplan overrides…');
  message.setAttribute('role', 'status');
  const add = node('button', 'button secondary', 'Add P25 override');
  add.type = 'button';
  add.disabled = true;
  add.addEventListener('click', () => list.append(p25OverrideProfileCard()));
  const save = node('button', '', 'Save P25 Bandplan Overrides');
  save.type = 'submit';
  save.disabled = true;
  const actions = node('div', 'admin-form-actions');
  actions.append(add, save);
  const footer = node('div', 'settings-form-footer');
  footer.append(message, actions);
  form.append(intro, list, footer);
  body.append(form);
  content.append(section('P25 Bandplan Overrides', body));

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!form.reportValidity() || save.disabled) return;
    add.disabled = true;
    save.disabled = true;
    message.textContent = 'Saving P25 bandplan overrides…';
    try {
      const documentValue = await requestP25BandplanOverrides('PUT', p25OverrideProfilesFromForm(list));
      list.replaceChildren(...(documentValue?.profiles || []).map(p25OverrideProfileCard));
      message.textContent = 'P25 bandplan overrides saved.';
    } catch (error) {
      message.textContent = error.message;
    } finally {
      add.disabled = false;
      save.disabled = false;
    }
  });

  try {
    const documentValue = await requestP25BandplanOverrides();
    if (route.toString() !== renderRoute) return;
    const createRequested = route.has('createP25Override');
    const requestedProfile = p25OverrideCreateRouteProfile(route);
    const requestedConfigurationId = p25OverrideCreateRouteConfigurationId(route);
    let requestedCard = null;
    const cards = documentValue.profiles.map((profile) => {
      const card = p25OverrideProfileCard(profile);
      if (requestedProfile && p25OverrideSameScope(profile, requestedProfile)) requestedCard = card;
      return card;
    });
    list.append(...cards);
    if (createRequested) {
      if (requestedProfile && requestedConfigurationId) {
        if (!requestedCard) {
          let detectedBands = [];
          let detectedBandsLoaded = true;
          try {
            const detected = await api(channelApiPath(requestedConfigurationId, 'frequency-bands'));
            detectedBands = p25OverrideDetectedBands(detected, requestedProfile);
          } catch (_) {
            detectedBandsLoaded = false;
          }
          if (route.toString() !== renderRoute) return;
          const draft = detectedBands.length ? { ...requestedProfile, bands: detectedBands } : requestedProfile;
          requestedCard = p25OverrideProfileCard(draft);
          list.prepend(requestedCard);
          if (detectedBands.length) {
            message.textContent = `Site override prepared with ${detectedBands.length} currently detected OTA band${detectedBands.length === 1 ? '' : 's'}. Review them before saving; the receiver may not have learned every band yet.`;
          } else if (detectedBandsLoaded) {
            message.textContent = 'Site override prepared, but no usable detected OTA bands were available for this site. Enter its replacement bands, then save.';
          } else {
            message.textContent = 'Site override prepared, but its detected OTA bands could not be loaded. Enter its replacement bands, then save.';
          }
        } else {
          message.textContent = 'This site already has an override. Review its replacement bands before saving.';
        }
        window.requestAnimationFrame(() => {
          requestedCard.scrollIntoView({ block: 'center' });
          requestedCard.querySelector('[data-p25-override-field="identifier"]')?.focus({ preventScroll: true });
        });
      } else {
        message.textContent = 'The site override could not be prepared because its P25 identity is invalid.';
      }
      clearP25OverrideCreateRoute();
    } else {
      message.textContent = '';
    }
    add.disabled = false;
    save.disabled = false;
  } catch (error) {
    message.textContent = error.message;
  }
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
    heading.dataset.receiverHealthFocus = `resolved-incident:${receiverHealthResolvedIncidentKey(incident)}`;
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

function receiverHealthResolvedPage(incidents, sort, requestedPage) {
  const sorted = receiverHealthSortedResolvedIncidents(incidents, sort);
  const pageCount = Math.ceil(sorted.length / RECEIVER_HEALTH_RESOLVED_PAGE_SIZE);
  const numericPage = Number(requestedPage);
  const page = Math.max(0, Math.min(Number.isFinite(numericPage) ? Math.trunc(numericPage) : 0,
    Math.max(0, pageCount - 1)));
  const offset = page * RECEIVER_HEALTH_RESOLVED_PAGE_SIZE;
  const rows = sorted.slice(offset, offset + RECEIVER_HEALTH_RESOLVED_PAGE_SIZE);
  return {
    rows,
    page,
    page_count: pageCount,
    total_count: sorted.length,
    offset,
    limit: RECEIVER_HEALTH_RESOLVED_PAGE_SIZE,
    has_more: offset + rows.length < sorted.length
  };
}

function receiverHealthPruneExpandedResolvedIncidents(incidents) {
  const current = new Set(incidents.map(receiverHealthResolvedIncidentKey));
  receiverHealthController.expandedResolvedIncidents.forEach((key) => {
    if (!current.has(key)) receiverHealthController.expandedResolvedIncidents.delete(key);
  });
}

function receiverHealthIncidentList(incidents, resolved = false) {
  if (!incidents.length) {
    return node('div', resolved ? 'receiver-health-empty' : 'receiver-health-empty receiver-health-empty-healthy',
      resolved ? 'No recently resolved incidents.' : 'No active receiver health incidents.');
  }
  const list = node('div', 'receiver-health-incident-list');
  list.append(...incidents.map((incident) => {
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

function receiverHealthResolvedPager(page, onPage) {
  const navigation = node('nav', 'pager receiver-health-resolved-pager');
  navigation.setAttribute('aria-label', 'Recently resolved pagination');
  navigation.dataset.receiverHealthFocus = 'resolved-pager';
  navigation.tabIndex = -1;
  const first = page.offset + 1;
  const last = page.offset + page.rows.length;
  navigation.append(node('span', 'muted',
    `Resolved alerts ${number(first)}-${number(last)} of ${number(page.total_count)} · ` +
      `Page ${number(page.page + 1)} of ${number(page.page_count)}`));
  const previous = node('button', 'secondary', 'Previous');
  previous.type = 'button';
  previous.dataset.receiverHealthFocus = 'resolved-previous';
  previous.disabled = page.page <= 0;
  previous.addEventListener('click', () => onPage(page.page - 1));
  const next = node('button', 'secondary', 'Next');
  next.type = 'button';
  next.dataset.receiverHealthFocus = 'resolved-next';
  next.disabled = !page.has_more;
  next.addEventListener('click', () => onPage(page.page + 1));
  navigation.append(previous, next);
  return navigation;
}

function receiverHealthResolvedSection(incidents) {
  receiverHealthPruneExpandedResolvedIncidents(incidents);
  if (!incidents.length) {
    receiverHealthController.resolvedPage = 0;
    return receiverHealthSection('resolved', 'Recently resolved', receiverHealthIncidentList(incidents, true));
  }
  const body = node('div');
  const sort = node('select');
  sort.setAttribute('aria-label', 'Sort resolved alerts');
  sort.dataset.receiverHealthFocus = 'resolved-sort';
  [['recent', 'Newest resolved'], ['type', 'Alert type (A–Z)']].forEach(([value, label]) => {
    const option = node('option', '', label);
    option.value = value;
    option.selected = receiverHealthController.resolvedSort === value;
    sort.append(option);
  });
  const control = node('label', 'receiver-health-resolved-sort');
  control.append(node('span', '', 'Sort'), sort);
  const draw = (focusPager = false) => {
    const page = receiverHealthResolvedPage(incidents, receiverHealthController.resolvedSort,
      receiverHealthController.resolvedPage);
    receiverHealthController.resolvedPage = page.page;
    const paginator = receiverHealthResolvedPager(page, (nextPage) => {
      receiverHealthController.resolvedPage = nextPage;
      draw(true);
    });
    body.replaceChildren(receiverHealthIncidentList(page.rows, true), paginator);
    if (focusPager) paginator.focus();
  };
  sort.addEventListener('change', () => {
    receiverHealthController.resolvedSort = sort.value === 'type' ? 'type' : 'recent';
    receiverHealthController.resolvedPage = 0;
    draw();
  });
  draw();
  return receiverHealthSection('resolved', 'Recently resolved', body, control);
}

let receiverHealthSectionSequence = 0;

function receiverHealthSection(key, title, child, action = null) {
  const wrapper = node('section', 'section receiver-health-section');
  const titleBar = node('div', 'section-title');
  const body = node('div', 'receiver-health-section-body');
  body.id = `receiver-health-section-body-${++receiverHealthSectionSequence}`;
  if (child) body.append(child);

  const toggle = node('button', 'receiver-health-section-toggle', title);
  toggle.type = 'button';
  toggle.dataset.receiverHealthFocus = `section:${key}`;
  toggle.setAttribute('aria-controls', body.id);
  const applyExpanded = (expanded) => {
    toggle.setAttribute('aria-expanded', String(expanded));
    body.hidden = !expanded;
    wrapper.classList.toggle('collapsed', !expanded);
  };
  applyExpanded(receiverHealthController.openHealthSections.has(key));
  toggle.addEventListener('click', () => {
    const expanded = body.hidden;
    if (expanded) receiverHealthController.openHealthSections.add(key);
    else receiverHealthController.openHealthSections.delete(key);
    applyExpanded(expanded);
  });

  titleBar.append(toggle);
  if (action) titleBar.append(action);
  wrapper.append(titleBar, body);
  return wrapper;
}

function receiverHealthResourceScale(row) {
  const numeric = Number(row?.value);
  const available = Number.isFinite(numeric) && numeric >= 0;
  const label = receiverHealthText(row?.label).toLowerCase();
  const unit = receiverHealthText(row?.unit, '').toLowerCase();
  const maximum = unit === '%' ? 100 :
    label === 'garbage collection' && unit === 'ms in last sample' ?
      RECEIVER_HEALTH_GC_BAR_MAXIMUM_MILLISECONDS : available ? Math.max(1, numeric) : 100;
  return {
    available,
    maximum,
    value: available ? Math.min(maximum, numeric) : 0
  };
}

function receiverHealthResourceBar(row) {
  const severity = receiverHealthSeverity(row.severity);
  const label = receiverHealthText(row.label, 'Host resource');
  const value = receiverHealthText(row.value);
  const unit = receiverHealthText(row.unit, '');
  const formattedValue = unit ? `${value} ${unit}` : value;
  const scale = receiverHealthResourceScale(row);
  const item = node('article', `receiver-health-resource-bar receiver-health-${severity}`);
  const heading = node('div', 'receiver-health-resource-heading');
  heading.append(node('strong', '', label), receiverHealthSeverityBadge(row.severity));
  const reading = node('div', 'receiver-health-resource-value');
  reading.append(node('strong', '', value));
  if (unit) reading.append(node('span', '', unit));
  const progress = node('progress', `receiver-health-resource-progress receiver-health-${severity}`);
  progress.max = scale.maximum;
  progress.value = scale.value;
  progress.setAttribute('aria-label', label);
  progress.setAttribute('aria-valuetext', scale.available ? formattedValue : 'Unavailable');
  item.append(heading, reading, progress,
    node('div', 'receiver-health-resource-detail', receiverHealthText(row.detail)));
  return item;
}

function receiverHealthHostResourceOverview(snapshot) {
  const group = snapshot.measurements.find((measurement) =>
    receiverHealthText(measurement.id).toLowerCase() === 'host');
  const body = node('div', 'receiver-health-resource-bars');
  if (group?.rows?.length) body.append(...group.rows.map(receiverHealthResourceBar));
  else body.append(node('div', 'receiver-health-empty', 'No host resource measurements were reported.'));
  return receiverHealthSection('host-overview', 'Host resource overview', body);
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

function receiverHealthMeasurementGroup(group, index) {
  const body = node('div', 'receiver-health-measurement-list');
  body.setAttribute('role', 'list');
  if (group.rows.length) body.append(...group.rows.map(receiverHealthMeasurementRow));
  else body.append(node('div', 'receiver-health-empty', 'No measurements were reported.'));
  const title = receiverHealthText(group.title, receiverHealthText(group.id, 'Measurements'));
  const key = `measurement:${receiverHealthText(group.id, `${title}:${index}`)}`;
  return receiverHealthSection(key, title, body);
}

function receiverHealthRefreshButton() {
  const refresh = node('button', 'secondary', 'Refresh now');
  refresh.type = 'button';
  refresh.dataset.receiverHealthFocus = 'refresh';
  refresh.addEventListener('click', async () => {
    refresh.disabled = true;
    await receiverHealthController.refresh();
    if (refresh.isConnected) refresh.disabled = false;
  });
  return refresh;
}

function receiverHealthAccountSettingNotice(snapshot) {
  const settings = receiverHealthAccountAlertSummary(snapshot, activeUserPreferences());
  const notice = node('aside', 'receiver-health-account-setting');
  let message = 'Alert switches affect only this account\'s header indicator. Monitoring, measurements, and ' +
    'history always continue.';
  if (settings.disabled_count > 0) {
    message = `${number(settings.disabled_count)} of ${number(settings.active_count)} active incident` +
      `${settings.active_count === 1 ? ' is' : 's are'} turned off for this account's header alert. ` +
      'They remain visible below because monitoring and history always continue.';
  } else if (settings.active_count > 0) {
    message = `All ${number(settings.active_count)} active incident` +
      `${settings.active_count === 1 ? '' : 's'} currently ` +
      `${settings.active_count === 1 ? 'affects' : 'affect'} this account's header alert. ` +
      'Monitoring, measurements, and history always continue.';
  }
  const settingsLink = anchor('Manage alert switches', href('admin', { tab: 'alerts' }));
  settingsLink.dataset.receiverHealthFocus = 'alert-settings';
  notice.append(node('span', '', message), settingsLink);
  return notice;
}

function receiverHealthFocusedControl(host) {
  const active = document.activeElement;
  return active && host.contains(active) ? active.dataset.receiverHealthFocus || '' : '';
}

function receiverHealthRestoreFocus(host, key) {
  if (!key) return;
  const target = [...host.querySelectorAll('[data-receiver-health-focus]')]
    .find((element) => element.dataset.receiverHealthFocus === key);
  target?.focus({ preventScroll: true });
}

function renderReceiverHealthPage(host, snapshot, stale, lastError) {
  const focusedControl = receiverHealthFocusedControl(host);
  host.replaceChildren();
  if (!snapshot) {
    const message = stale ? (lastError || 'Receiver health status is unavailable.') :
      'Loading receiver health status…';
    const body = node('div', 'admin-section-body');
    body.append(node('div', stale ? 'logging-notice warning' : 'receiver-health-loading-message', message));
    host.append(receiverHealthSection('current', 'Current status', body, receiverHealthRefreshButton()));
    receiverHealthRestoreFocus(host, focusedControl);
    return;
  }

  const summary = snapshot.summary;
  const stateLabel = stale ? 'Stale' : summary.severity === 'critical' ? 'Critical' :
    summary.severity === 'warning' ? 'Warning' : 'Healthy';
  const overview = node('div', 'receiver-health-overview');
  const status = node('div', `receiver-health-overview-state receiver-health-${stale ? 'stale' : summary.severity}`);
  status.append(node('span', '', 'Receiver health'), node('strong', '', stateLabel));
  overview.append(status, metrics([
    ['Active incidents', summary.active_count],
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

  host.append(receiverHealthHostResourceOverview(snapshot),
    receiverHealthSection('current', 'Current status', overview, receiverHealthRefreshButton()),
    receiverHealthAccountSettingNotice(snapshot),
    receiverHealthSection('active', 'Active alerts and diagnostics', receiverHealthIncidentList(snapshot.active)),
    receiverHealthResolvedSection(snapshot.resolved));
  if (snapshot.measurements.length) {
    host.append(...snapshot.measurements.map(receiverHealthMeasurementGroup));
  } else {
    host.append(receiverHealthSection('measurements', 'Measurements', node('div', 'receiver-health-empty',
      'No receiver health measurements were reported.')));
  }
  receiverHealthRestoreFocus(host, focusedControl);
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

function preferenceCheckbox(name, label, checked, detail = '') {
  const input = node('input');
  input.type = 'checkbox';
  input.name = name;
  input.checked = checked === true;
  const copy = node('span', 'admin-toggle-copy');
  copy.append(node('strong', '', label));
  if (detail) copy.append(node('span', '', detail));
  const control = node('label', 'admin-toggle-control');
  control.append(input, copy);
  return { input, control };
}

function preferenceSelect(name, choices, selected) {
  const select = node('select');
  select.name = name;
  choices.forEach(([value, label]) => {
    const option = node('option', '', label);
    option.value = value;
    select.append(option);
  });
  select.value = selected;
  return select;
}

function settingsCard(title, description, ...items) {
  const card = node('section', 'settings-card');
  const header = node('div', 'settings-card-header');
  header.append(node('h3', 'settings-card-title', title));
  if (description) header.append(node('p', 'settings-card-description', description));
  const body = node('div', 'settings-card-body');
  body.append(...items);
  card.append(header, body);
  return card;
}

function settingsCardGrid(...cards) {
  const grid = node('div', 'settings-card-grid');
  grid.append(...cards);
  return grid;
}

function settingsSummary(rows) {
  const summary = node('dl', 'settings-summary');
  rows.forEach(([label, value]) => {
    summary.append(node('dt', '', label), node('dd', '', value));
  });
  return summary;
}

function settingsEnabled(value) {
  return value === true ? 'On' : 'Off';
}

function selectedScanListSummary(ids) {
  if (!ids.length) return 'None';
  const names = new Map((webCallPlayer?.viewState()?.scanLists || [])
    .map((scanList) => [Number(scanList.id), scanList.name]));
  return ids.map((id) => names.get(id) || `Scan list ${id}`).join(', ');
}

function disabledHealthAlertSummary(codes) {
  if (!codes.length) return 'None';
  const names = new Map(receiverHealthAlertGroups.flatMap((group) => group.alerts)
    .map((alert) => [alert.id, alert.name]));
  return codes.map((code) => names.has(code) ? `${names.get(code)} — ${code}` : code).join(', ');
}

function tableLayoutSummary(tables) {
  const entries = Object.entries(tables);
  if (!entries.length) return node('div', 'muted', 'No customized table layouts.');
  const list = node('div', 'settings-table-layouts');
  entries.forEach(([tableId, layout]) => {
    const details = node('details', 'settings-table-layout');
    details.append(node('summary', '', semanticLabel(tableId)), settingsSummary([
      ['Available columns', layout.schema.map(semanticLabel).join(', ')],
      ['Column order', layout.column_order.map(semanticLabel).join(', ')],
      ['Hidden columns', layout.hidden_columns.length ? layout.hidden_columns.map(semanticLabel).join(', ') : 'None'],
      ['Custom widths', Object.entries(layout.column_widths)
        .map(([column, width]) => `${semanticLabel(column)} ${number(width)} px`).join(', ') || 'Defaults']
    ]));
    list.append(details);
  });
  return list;
}

function userPreferenceSummaryCards(preferences) {
  const disabledAlerts = preferences.health_alerts.disabled_codes;
  const knownDisabledAlerts = receiverHealthAlertIds.filter((id) => disabledAlerts.includes(id)).length;
  return settingsCardGrid(
    settingsCard('Appearance', 'Changed with the theme button in the header.', settingsSummary([
      ['Theme', semanticLabel(preferences.appearance.theme)]
    ])),
    settingsCard('Scanner', 'Changed on the Scanner page.', settingsSummary([
      ['Playing call in every page title', settingsEnabled(preferences.page_titles.prepend_playing_call)],
      ['Playback volume', `${Math.round(preferences.playback.volume * 100)}%`],
      ['Selected scan lists', selectedScanListSummary(preferences.playback.selected_scan_list_ids)],
      ['Group calls by target', settingsEnabled(preferences.playback.target_grouping)],
      ['Calls per target', number(preferences.playback.target_burst_limit)],
      ['Detail level', semanticLabel(preferences.scanner.detail_mode)]
    ])),
    settingsCard('Live presentation', 'Changed from the presentation icon on the Live page.', settingsSummary([
      ['Show only active trunked channels', settingsEnabled(preferences.presentation.show_only_active_trunked_channels)],
      ['Retain the last call on idle rows', settingsEnabled(preferences.presentation.retain_last_call_on_idle_rows)],
      ['Clear voice quality on idle rows', settingsEnabled(preferences.presentation.clear_voice_quality_when_idle)],
      ['Show encryption details', settingsEnabled(preferences.presentation.show_encryption_details)],
      ['Show control-channel quality', settingsEnabled(preferences.presentation.show_control_decode_quality)],
      ['Show voice-channel quality', settingsEnabled(preferences.presentation.show_voice_decode_quality)],
      ['Decode quality format', semanticLabel(preferences.presentation.decode_quality_display_mode)],
      ['Matching rows shown', number(preferences.presentation.live_detail_row_limit)]
    ])),
    settingsCard('Tuner spectrum', 'Changed from a tuner Spectrum view.', settingsSummary([
      ['Display floor', `${number(preferences.tuner.floor_db)} dB`],
      ['Display ceiling', `${number(preferences.tuner.ceiling_db)} dB`],
      ['Waterfall speed', `${number(preferences.tuner.waterfall_speed)}×`],
      ['Snap frequency', settingsEnabled(preferences.tuner.snap_frequency)],
      ['Smooth FFT', settingsEnabled(preferences.tuner.smooth_fft)],
      ['Idle FFT markers', settingsEnabled(preferences.tuner.show_idle_channels)],
      ['Highlight channels', settingsEnabled(preferences.tuner.highlight_waterfall_channels)],
      ['Performance profile', semanticLabel(preferences.tuner.profile)]
    ])),
    settingsCard('Health alerts', 'Changed from Administration > Alerts.', settingsSummary([
      ['Header alerts enabled', `${number(receiverHealthAlertIds.length - knownDisabledAlerts)} of ` +
        number(receiverHealthAlertIds.length)],
      ['Disabled alerts', disabledHealthAlertSummary(disabledAlerts)]
    ])),
    settingsCard('Table layouts', 'Changed with the Columns control on each table.',
      tableLayoutSummary(preferences.tables))
  );
}

async function renderAdminAlerts() {
  const snapshot = userPreferenceController.snapshot();
  if (!snapshot.loaded) {
    const unavailable = node('div', 'error', userPreferenceError?.message ||
      'Alert settings could not be loaded for this account.');
    const retry = node('button', 'button secondary', 'Retry');
    retry.type = 'button';
    retry.addEventListener('click', async () => {
      retry.disabled = true;
      await synchronizeUserPreferences();
      void render();
    });
    content.append(section('Alert settings unavailable', unavailable, retry));
    return;
  }

  const form = node('form', 'admin-form settings-page-form health-alert-settings-form');
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'status');
  const controls = new Map();
  const apply = (preferences) => {
    receiverHealthAlertIds.forEach((id) => {
      const input = controls.get(id);
      if (input) input.checked = isReceiverHealthAlertEnabled(preferences, id);
    });
  };
  const cards = receiverHealthAlertGroups.map((group) => {
    const toggles = group.alerts.map((alert) => {
      const setting = preferenceCheckbox(`health-alert-${alert.id}`, alert.name, true, alert.description);
      setting.input.dataset.alertCode = alert.id;
      controls.set(alert.id, setting.input);
      return setting.control;
    });
    const card = settingsCard(group.name, group.description, ...toggles);
    card.classList.add('health-alert-settings-card');
    return card;
  });
  apply(snapshot.preferences);

  const save = node('button', '', 'Save Alert Settings');
  save.type = 'submit';
  const actions = node('div', 'admin-form-actions');
  actions.append(save);
  const footer = node('div', 'settings-form-footer');
  footer.append(message, actions);
  form.append(node('p', 'health-alert-settings-intro',
    'Choose which receiver-health incidents can change the health icon in this account\'s header. ' +
    'Turning an alert off does not stop monitoring, remove measurements, or hide current and resolved incidents ' +
    'from the Health page.'), settingsCardGrid(...cards), footer);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (save.disabled) return;
    controls.forEach((input) => { input.disabled = true; });
    save.disabled = true;
    message.textContent = 'Saving alert settings…';
    try {
      await updateUserPreferences((preferences) => {
        preferences.health_alerts.disabled_codes = receiverHealthDisabledCodesForSave(preferences, controls);
      }, false);
      message.textContent = 'Alert settings saved.';
    } catch (error) {
      if (error?.code === 'preference_conflict' && !error.reloadError) {
        const current = userPreferenceController.snapshot();
        if (current.loaded) apply(current.preferences);
        message.textContent = 'These settings changed in another session. The current saved values were loaded.';
      } else if (error?.code === 'preference_conflict') {
        message.textContent = 'These settings changed in another session, but the current values could not be ' +
          'reloaded. Try saving again or reload this page.';
      } else message.textContent = error.message;
    } finally {
      controls.forEach((input) => { input.disabled = false; });
      save.disabled = false;
    }
  });
  content.append(section('Header alerts', form));
}

function openLivePresentationSettings(returnFocusSelector = null) {
  const snapshot = userPreferenceController.snapshot();
  if (!snapshot.loaded) return;
  const current = snapshot.preferences.presentation;
  const form = node('form', 'admin-form live-presentation-form');
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'status');
  const encryption = preferenceCheckbox('show-encryption-details', 'Show encryption algorithm and key',
    current.show_encryption_details,
    'Show the decoded algorithm and key identifiers when they are available.');
  const controlQuality = preferenceCheckbox('show-control-quality', 'Show control-channel decode quality',
    current.show_control_decode_quality,
    'Add the rolling control-channel quality reading to Live rows.');
  const voiceQuality = preferenceCheckbox('show-voice-quality', 'Show voice-channel decode quality',
    current.show_voice_decode_quality,
    'Add call-level voice quality when enough frames have been received.');
  const activeOnly = preferenceCheckbox('show-only-active-trunked', 'Show only active trunked channels',
    current.show_only_active_trunked_channels,
    'Hide inactive trunked rows. Conventional channels are always shown.');
  const retainLastCall = preferenceCheckbox('retain-last-call-on-idle', 'Retain the last call on idle rows',
    current.retain_last_call_on_idle_rows,
    'Keep the last source, target, alias, talker, and encryption details visible after a row becomes idle.');
  const clearIdleQuality = preferenceCheckbox('clear-idle-voice-quality',
    'Clear voice quality when a row becomes idle', current.clear_voice_quality_when_idle,
    'Hide the completed call\'s voice-quality result after its row becomes idle.');
  const qualityMode = preferenceSelect('quality-mode', [['percentage', 'Percentage'], ['detailed', 'Detailed counters']],
    current.decode_quality_display_mode);
  const rowLimit = node('input');
  rowLimit.type = 'number';
  rowLimit.name = 'live-detail-row-limit';
  rowLimit.min = '25';
  rowLimit.max = '500';
  rowLimit.required = true;
  rowLimit.value = String(current.live_detail_row_limit);
  const apply = (presentation) => {
    encryption.input.checked = presentation.show_encryption_details;
    controlQuality.input.checked = presentation.show_control_decode_quality;
    voiceQuality.input.checked = presentation.show_voice_decode_quality;
    activeOnly.input.checked = presentation.show_only_active_trunked_channels;
    retainLastCall.input.checked = presentation.retain_last_call_on_idle_rows;
    clearIdleQuality.input.checked = presentation.clear_voice_quality_when_idle;
    qualityMode.value = presentation.decode_quality_display_mode;
    rowLimit.value = String(presentation.live_detail_row_limit);
  };
  const fields = node('div', 'settings-field-grid');
  fields.append(formField('Decode quality format', qualityMode,
    'Choose a compact percentage or the underlying frame and error counters.'),
  formField('Matching rows shown', rowLimit, 'Limit each matching Live detail list to 25–500 rows.'));
  const save = node('button', '', 'Save Live Presentation');
  save.type = 'submit';
  const actions = node('div', 'admin-form-actions');
  actions.append(save);
  const footer = node('div', 'settings-form-footer');
  footer.append(message, actions);
  const presentationCard = settingsCard('Live details',
    'Choose how decoded activity is shown on the Live page.',
    activeOnly.control, retainLastCall.control, clearIdleQuality.control,
    encryption.control, controlQuality.control, voiceQuality.control, fields);
  form.append(node('p', 'live-presentation-intro',
    'These choices affect only this signed-in user.'),
  settingsCardGrid(presentationCard), footer);
  const modal = openReadOnlyModal('Live presentation', form, {
    id: 'live-presentation-settings', className: 'live-presentation-modal', returnFocusSelector
  });
  if (!modal) return;
  form.addEventListener('input', () => modal.setDirty(true));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!form.reportValidity() || save.disabled) return;
    const submitted = {
      show_encryption_details: encryption.input.checked,
      show_control_decode_quality: controlQuality.input.checked,
      show_voice_decode_quality: voiceQuality.input.checked,
      decode_quality_display_mode: qualityMode.value,
      live_detail_row_limit: Number(rowLimit.value),
      show_only_active_trunked_channels: activeOnly.input.checked,
      retain_last_call_on_idle_rows: retainLastCall.input.checked,
      clear_voice_quality_when_idle: clearIdleQuality.input.checked
    };
    const controls = [activeOnly.input, retainLastCall.input, clearIdleQuality.input, encryption.input,
      controlQuality.input, voiceQuality.input, qualityMode, rowLimit, save];
    controls.forEach((control) => { control.disabled = true; });
    save.disabled = true;
    modal.setBusy(true);
    message.textContent = 'Saving Live presentation…';
    try {
      await updateUserPreferences((preferences) => {
        preferences.presentation = submitted;
      }, false);
      modal.setDirty(false);
      modal.setBusy(false);
      if (modal.close()) void render();
    } catch (error) {
      if (error?.code === 'preference_session_changed') {
        modal.setDirty(false);
        modal.setBusy(false);
        if (modal.close()) void render();
      } else if (error?.code === 'preference_conflict') {
        if (error.reloadError) {
          modal.setDirty(true);
          message.textContent = 'These settings changed in another session, but the current values could not be ' +
            'reloaded. Try saving again or reopen this panel.';
        } else {
          const latest = userPreferenceController.snapshot();
          if (latest.loaded) apply(latest.preferences.presentation);
          modal.setDirty(false);
          message.textContent = 'These settings changed in another session. The current saved values were loaded.';
        }
      } else message.textContent = error.message;
    } finally {
      modal.setBusy(false);
      controls.forEach((control) => { control.disabled = false; });
    }
  });
}

function openScannerSettings(returnFocusSelector = null) {
  const snapshot = userPreferenceController.snapshot();
  if (!snapshot.loaded) return;
  const current = snapshot.preferences;
  const form = node('form', 'admin-form scanner-settings-form');
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'status');
  const targetGrouping = preferenceCheckbox('target-grouping', 'Group calls by playback target',
    current.playback.target_grouping,
    'When calls are waiting, keep calls for the same playback target together before switching to a different target.');
  const prependTitle = preferenceCheckbox('prepend-playing-call', 'Show the playing call in every page title',
    current.page_titles.prepend_playing_call,
    'The Scanner title always shows the audible target. Turn this on to add it to other pages too.');
  const targetBurstLimit = node('input');
  targetBurstLimit.type = 'number';
  targetBurstLimit.name = 'target-burst-limit';
  targetBurstLimit.min = '1';
  targetBurstLimit.max = '20';
  targetBurstLimit.required = true;
  targetBurstLimit.value = String(current.playback.target_burst_limit);
  const apply = (preferences) => {
    targetGrouping.input.checked = preferences.playback.target_grouping;
    targetBurstLimit.value = String(preferences.playback.target_burst_limit);
    prependTitle.input.checked = preferences.page_titles.prepend_playing_call;
  };
  const fields = node('div', 'settings-field-grid');
  fields.append(formField('Calls before switching targets', targetBurstLimit,
    'Play 1–20 waiting calls for the current playback target before another target gets a turn.'));
  const card = settingsCard('Playback order',
    'These choices affect only calls that have already built up in this browser queue.',
    targetGrouping.control, fields);
  const titleCard = settingsCard('Page titles',
    'Choose whether Scanner playback also appears in the title of other pages.', prependTitle.control);
  const save = node('button', '', 'Save Scanner Settings');
  save.type = 'submit';
  const actions = node('div', 'admin-form-actions');
  actions.append(save);
  const footer = node('div', 'settings-form-footer');
  footer.append(message, actions);
  form.append(node('p', 'live-presentation-intro',
    'These choices affect only this signed-in user.'), settingsCardGrid(card, titleCard), footer);
  const modal = openReadOnlyModal('Scanner settings', form, {
    id: 'scanner-settings-modal', className: 'scanner-settings-modal', returnFocusSelector
  });
  if (!modal) return;
  form.addEventListener('input', () => modal.setDirty(true));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!form.reportValidity() || save.disabled) return;
    const submitted = {
      target_grouping: targetGrouping.input.checked,
      target_burst_limit: Number(targetBurstLimit.value),
      prepend_playing_call: prependTitle.input.checked
    };
    const controls = [targetGrouping.input, targetBurstLimit, prependTitle.input, save];
    controls.forEach((control) => { control.disabled = true; });
    modal.setBusy(true);
    message.textContent = 'Saving Scanner settings…';
    try {
      await updateUserPreferences((preferences) => {
        preferences.playback.target_grouping = submitted.target_grouping;
        preferences.playback.target_burst_limit = submitted.target_burst_limit;
        preferences.page_titles.prepend_playing_call = submitted.prepend_playing_call;
      }, false);
      modal.setDirty(false);
      modal.setBusy(false);
      if (modal.close()) void render();
    } catch (error) {
      if (error?.code === 'preference_session_changed') {
        modal.setDirty(false);
        modal.setBusy(false);
        if (modal.close()) void render();
      } else if (error?.code === 'preference_conflict') {
        if (error.reloadError) {
          modal.setDirty(true);
          message.textContent = 'These settings changed in another session, but the current values could not be ' +
            'reloaded. Try saving again or reopen this panel.';
        } else {
          const latest = userPreferenceController.snapshot();
          if (latest.loaded) apply(latest.preferences);
          modal.setDirty(false);
          message.textContent = 'These settings changed in another session. The current saved values were loaded.';
        }
      } else message.textContent = error.message;
    } finally {
      modal.setBusy(false);
      controls.forEach((control) => { control.disabled = false; });
    }
  });
}

function openResetUserPreferences(returnFocusSelector = null) {
  const body = node('div', 'admin-confirmation');
  body.append(node('p', '', 'Reset every personal preference for this account to its default value?'),
    node('p', 'muted', 'This resets the theme, Scanner and Live choices, volume and scan-list subscriptions, ' +
      'tuner display, health alert switches, and saved table layouts. It does not change the username, password, ' +
      'access, receiver configuration, or other users.'));
  const message = node('div', 'admin-form-message');
  message.setAttribute('role', 'status');
  const cancel = node('button', 'secondary', 'Cancel');
  cancel.type = 'button';
  const reset = node('button', 'danger', 'Reset All Personal Preferences');
  reset.type = 'button';
  const actions = node('div', 'admin-form-actions');
  actions.append(cancel, reset);
  body.append(message, actions);
  const modal = openReadOnlyModal('Reset personal preferences', body, {
    id: 'reset-user-preferences', className: 'admin-modal', returnFocusSelector
  });
  if (!modal) return;
  cancel.addEventListener('click', modal.close);
  reset.addEventListener('click', async () => {
    if (reset.disabled) return;
    cancel.disabled = true;
    reset.disabled = true;
    modal.setBusy(true);
    message.textContent = 'Resetting personal preferences…';
    try {
      await updateUserPreferences(() => preferenceSchema.defaults, false);
      modal.setBusy(false);
      if (modal.close()) void render();
    } catch (error) {
      modal.setBusy(false);
      if (error?.code === 'preference_session_changed') {
        if (modal.close()) void render();
        return;
      }
      if (error?.code === 'preference_conflict' && !error.reloadError) {
        if (modal.close()) void render();
        return;
      }
      message.textContent = error?.code === 'preference_conflict' ?
        'These preferences changed in another session, but the current values could not be reloaded. Close this ' +
          'panel and reload the page before trying again.' :
        (error.message || 'Personal preferences could not be reset.');
      cancel.disabled = false;
      reset.disabled = false;
    }
  });
}

async function renderSettings() {
  const renderContext = captureRenderContext();
  if (!beginPage(renderContext, pageHeader('My Settings',
    'A read-only overview of every personal preference for this account'))) return;
  const snapshot = userPreferenceController.snapshot();
  if (!snapshot.loaded) {
    const unavailable = node('div', 'error', userPreferenceError?.message || 'My Settings could not be loaded.');
    const retry = node('button', 'button secondary', 'Retry');
    retry.type = 'button';
    retry.addEventListener('click', async () => {
      retry.disabled = true;
      await synchronizeUserPreferences();
      if (renderIsCurrent(renderContext)) void render();
    });
    content.append(section('Preferences unavailable', unavailable, retry));
    return;
  }

  const current = snapshot.preferences;
  const overview = node('div', 'settings-page-form user-settings-summary');
  const reset = node('button', 'button danger-outline', 'Reset All Personal Preferences');
  reset.type = 'button';
  reset.id = 'reset-user-preferences';
  reset.addEventListener('click', () => openResetUserPreferences('#reset-user-preferences'));
  const footer = node('div', 'settings-summary-footer');
  const actions = node('div', 'admin-form-actions');
  actions.append(reset);
  footer.append(node('p', '', 'Reset affects only this account’s personal choices.'), actions);
  overview.append(userPreferenceSummaryCards(current), footer);
  content.append(section('Personal preferences', overview));
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
  const availableTabs = [
    { id: 'tuners', label: 'Tuners' },
    { id: 'rf-planner', label: 'RF Planner' }
  ];
  const requested = route.get('tab') || 'tuners';
  const active = availableTabs.some((item) => item.id === requested) ? requested : 'tuners';
  if (!beginPage(renderContext, pageHeader('Hardware', 'Inspect and configure receiver hardware'),
    tabs(availableTabs.map((item) => ({ ...item, href: href('hardware', { tab: item.id }) })), active))) return;
  content.append(active === 'rf-planner' ? rfPlanner.createPlanner() : comingSoonPanel('Tuners'));
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
    { id: 'alerts', label: 'Alerts', capability: ACCESS_CAPABILITIES.RECEIVER_HEALTH },
    { id: 'receiver-settings', label: 'Receiver Settings', capability: ACCESS_CAPABILITIES.ADMIN_SETTINGS },
    { id: 'p25-bandplans', label: 'P25 Bandplan Overrides', capability: ACCESS_CAPABILITIES.ADMIN_SETTINGS },
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
  else if (active === 'alerts') {
    pageTitleController.update({ pageTitle: 'Health Alerts' });
    await renderAdminAlerts();
  }
  else if (active === 'receiver-settings') {
    pageTitleController.update({ pageTitle: 'Receiver Settings' });
    await renderReceiverSettings();
  }
  else if (active === 'p25-bandplans') {
    pageTitleController.update({ pageTitle: 'P25 Bandplan Overrides' });
    await renderAdminP25BandplanOverrides();
  }
  else if (active === 'access') await renderAdminAccess();
  else if (active === 'system') renderAdminSystem();
  else await renderAdminUsers();
}

function routeViewLabel(view) {
  return applicationRoutes?.[view]?.label || 'this page';
}

function renderNotFound(view, renderContext = captureRenderContext()) {
  const panel = node('section', 'access-denied-card');
  panel.append(node('h2', '', 'Page not found'),
    node('p', '', `The page “${String(view || '').slice(0, 80)}” does not exist.`));
  const home = anchor('Open Dashboard', href('dashboard'), 'button');
  panel.append(home);
  beginPage(renderContext, pageHeader('Not Found', 'The requested web-interface route is invalid'), panel);
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
  const parent = applicationRoutes?.[view]?.parent || null;
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

applicationRoutes = routeFoundation.createRegistry({
  dashboard: renderDashboard,
  live: renderLive,
  scanner: renderScanner,
  'tuner-spectrum': renderTunerSpectrum,
  'radio-systems': renderRadioSystems,
  'radio-system': renderRadioSystem,
  'group-identity': renderGroupIdentity,
  radio: renderRadio,
  channels: renderChannels,
  channel: renderChannel,
  aliases: renderAliases,
  configuration: renderConfiguration,
  hardware: renderHardware,
  admin: renderAdmin,
  settings: renderSettings,
  credits: renderCredits
}, routeDefinitionAllowed);

async function render() {
  setNavigationOpen(false);
  const view = routeFoundation.requestedView(route);
  const entry = routeFoundation.resolve(applicationRoutes, route);
  if (!closeReadOnlyModal()) return;
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

  pageTitleController.update({ routeId: entry?.id || 'not-found', pageTitle: entry?.title || 'Not Found' });
  let effectiveView = entry?.id || 'not-found';
  clearAliasSelectionOutsideEditor(effectiveView);
  try {
    if (!entry) {
      document.body.dataset.view = 'not-found';
      activateNavigation('not-found');
      renderNotFound(view, renderContext);
      return;
    }
    document.body.dataset.view = effectiveView;
    activateNavigation(effectiveView);
    if (!entry.allowed()) {
      clearInactiveAliasSelection(false);
      document.body.dataset.view = 'access-denied';
      pageTitleController.update({ pageTitle: 'Access Required' });
      renderAccessDenied(effectiveView, renderContext);
      return;
    }
    await entry.handler();
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
      clearInactiveAliasSelection(false);
      document.body.dataset.view = 'access-denied';
      renderAccessDenied(effectiveView, renderContext);
      return;
    }
    if (effectiveView === 'aliases') clearInactiveAliasSelection(false);
    const notice = databaseLoggingNotice(effectiveView);
    beginPage(renderContext, ...[notice, node('div', 'error', error.message)].filter(Boolean));
  }
}

document.addEventListener('click', (event) => {
  const link = event.target.closest('a');
  if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey ||
      event.altKey || link.target || link.hasAttribute('download')) return;
  const target = new URL(link.href, window.location.href);
  if (!routeFoundation.localTarget(window.location, target)) return;
  event.preventDefault();
  navigateTo(target);
});
window.addEventListener('popstate', () => {
  setNavigationOpen(false);
  const previous = `/?${route.toString()}`;
  if (!closeReadOnlyModal()) {
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
  .then(() => Promise.all([loadStatus(false), receiverHealthController.refresh()]))
  .finally(render);
let refreshCycle = null;
window.setInterval(() => {
  if (document.hidden || refreshCycle) return;
  refreshCycle = refreshAccessSession(true)
    .then(() => Promise.all([loadStatus(true), receiverHealthController.refresh()]))
    .finally(() => { refreshCycle = null; });
}, 10_000);
