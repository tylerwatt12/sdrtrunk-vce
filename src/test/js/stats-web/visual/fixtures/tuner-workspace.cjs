const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const root = resolve(__dirname, '../../../../../..');
const mode = (page) => page.getByRole('group', { name: 'Tuner mode', exact: true });
const departure = (page) => page.getByRole('alertdialog', { name: 'Leave this page?', exact: true });

async function install(page, { operatorState = 'setup', theme = 'light', savedCenterLocked = false,
  channelCount = 0 } = {}) {
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);
  const receiver = {
    id: 'tuner-a', name: 'Airspy R2', nickname: 'North antenna', tuner_class: 'AIRSPY',
    tuner_type: 'AIRSPY_R820T', status: 'ENABLED', available: operatorState !== 'disabled',
    operator_state: operatorState, operator_generation: 1, transition: null,
    channel_count: channelCount, frequency_hz: 851012500, configured_frequency_hz: 851012500,
    sample_rate_hz: 10000000, configured_sample_rate_hz: 10000000,
    usable_bandwidth_hz: 9000000, minimum_frequency_hz: 24000000, maximum_frequency_hz: 1800000000,
    spectrum_target_id: null, spectrum_available: false,
    device_group: { id: 'group-a', kind: 'single', role: 'member' },
    stopped_channels: operatorState === 'setup' ? [{ id: 'channel-a', name: 'County Control' }] : [],
    restore_result: null,
    settings: []
  };
  const state = { receiver, requests: [], lease: null, leaseSequence: 0, savedCenterLocked,
    runtimeLocked: operatorState !== 'setup' && savedCenterLocked, pauseFrequency: false,
    releaseFrequency: null, liveBeforeFrequencyCompleted: false };
  const updateDescriptors = () => {
    const setup = receiver.operator_state === 'setup';
    receiver.settings = [
      { id: 'frequency_mhz', label: 'Center frequency', group: 'frequency', kind: 'decimal',
        value: receiver.frequency_hz / 1000000, minimum: 24, maximum: 1800, step: 0.00001, unit: 'MHz',
        scope: 'tuner', availability: 'live', editable: setup || !state.savedCenterLocked,
        unavailable_reason: !setup && state.savedCenterLocked ? 'Unlock center' : null,
        dependencies: setup ? [] : [{ setting_id: 'center_frequency_locked', equals: false }] },
      { id: 'center_frequency_locked', label: 'Lock center', group: 'frequency', kind: 'boolean',
        value: state.savedCenterLocked, scope: 'tuner', availability: 'live', editable: true, dependencies: [] }
    ];
  };
  updateDescriptors();
  const tourHistory = { version: 1, dismissed: { welcome: 1, tuners: 3, 'tuner-spectrum': 5, 'channel-setup': 5 } };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const body = JSON.parse(request.postData() || '{}');
    const record = { path, method, body };
    state.requests.push(record);
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      tier: 'admin', username: 'admin', csrf_token: 'test-token', guide_dismissal_scope: 'tuner-navigation-tests',
      capabilities: { 'web-access': true, dashboard: true, 'admin-tuners': true, 'tuner-spectrum': true,
        'admin-channels': true, 'admin-aliases': true, 'admin-settings': true } });
    if (path === '/api/v1/me/tour-dismissals') return respond(tourHistory);
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferenceModule.defaults,
        appearance: { theme, hue: null } } }) });
    if (path === '/api/v1/admin/tuners') {
      updateDescriptors();
      return respond({ tuners: [receiver] });
    }
    if (path === '/api/v1/admin/tuners/tuner-a/state' && method === 'PUT') {
      if (body.operator_generation != null && body.operator_generation !== receiver.operator_generation)
        return route.fulfill({ status: 409, json: { error: { code: 'tuner_state_changed', message: 'The tuner changed.' } } });
      if (body.state === 'setup' && receiver.operator_state === 'live' && receiver.channel_count)
        receiver.stopped_channels = [{ id: 'channel-a', name: 'County Control' }];
      receiver.operator_state = body.state;
      receiver.operator_generation += 1;
      receiver.available = body.state !== 'disabled';
      state.runtimeLocked = body.state !== 'setup' && state.savedCenterLocked;
      if (body.state === 'live') {
        state.liveBeforeFrequencyCompleted = state.requests.some((candidate) =>
          candidate.path.endsWith('/settings/frequency_mhz') && !candidate.completed);
        receiver.channel_count = receiver.stopped_channels.length;
        receiver.stopped_channels = [];
      } else receiver.channel_count = 0;
      updateDescriptors();
      return respond({ status: 'accepted' }, 202);
    }
    const setting = path.match(/^\/api\/v1\/admin\/tuners\/tuner-a\/settings\/([^/]+)$/);
    if (setting && method === 'PUT') {
      if (setting[1] === 'frequency_mhz') {
        if (receiver.operator_state !== 'setup' || state.runtimeLocked)
          return route.fulfill({ status: 409, json: { error: { message: 'Use Setup to tune this receiver.' } } });
        if (state.pauseFrequency) {
          await new Promise((release) => { state.releaseFrequency = release; });
          state.releaseFrequency = null;
        }
        receiver.frequency_hz = Math.round(Number(body.value) * 1000000);
        receiver.configured_frequency_hz = receiver.frequency_hz;
      } else if (setting[1] === 'center_frequency_locked') {
        state.savedCenterLocked = Boolean(body.value);
        state.runtimeLocked = receiver.operator_state !== 'setup' && state.savedCenterLocked;
      }
      record.completed = true;
      updateDescriptors();
      return respond({ status: 'applied' });
    }
    if (path === '/api/v1/admin/tuners/tuner-a/browse') {
      if (method === 'DELETE') {
        state.lease = null;
        return respond(null, 204);
      }
      state.lease ||= `monitor-${++state.leaseSequence}`;
      updateDescriptors();
      return respond({ lease_id: state.lease, expires_at_epoch_ms: Date.now() + 30000,
        can_tune: receiver.operator_state === 'setup', takeover: false,
        stopped_channels: [], tuner: receiver });
    }
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: [] });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/channels') return respond({ revision: 1, channels: [] });
    if (path === '/api/v1/admin/channels/protocols') return respond({ profiles: [] });
    if (path === '/api/v1/admin/channels/options') return respond({ alias_lists: [] });
    if (path === '/api/v1/dashboard/main') return respond({ quality: { current: [] },
      directory: { systems: [], channels: [] } });
    return respond({});
  });
  return state;
}

async function navigate(page, view) {
  await page.locator(`a[data-view="${view}"]`).first().evaluate((link) => link.click());
}

function stateWrites(state) {
  return state.requests.filter(({ path, method }) => path.endsWith('/state') && method === 'PUT');
}

module.exports = { install, mode, departure, navigate, stateWrites };
