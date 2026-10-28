// Fictional API documents for the production Streaming workspace. Each tour stop
// receives a new instance; requests never leave this in-memory transport.
const ROOT = '/api/v1/admin/streaming';
const copy = value => structuredClone(value);
const field = (key, label, type = 'text', extra = {}) => ({ key, label, type,
  minimum: 0, maximum: type === 'password' ? 4096 : 1024, advanced: false, choices: [], ...extra });
const common = [field('name', 'Name'), field('enabled', 'Enable this destination', 'boolean'),
  field('host', 'Server address')];
const maximumAge = field('maximum_recording_age', 'Maximum queued call age (milliseconds)', 'number',
  { maximum: 604800000, advanced: true });
const apiKey = field('api_key', 'API key', 'password');
const providers = [
  { id: 'BROADCASTIFY_CALL', label: 'Broadcastify Call', connection_test: true,
    fields: [...common, maximumAge, apiKey, field('system_id', 'System ID', 'number', { maximum: 2147483647 }),
      field('test_enabled', 'Send periodic keep-alive', 'boolean', { advanced: true }),
      field('test_interval', 'Keep-alive interval (minutes)', 'number', { minimum: 1, maximum: 1440, advanced: true })] },
  { id: 'BROADCASTIFY', label: 'Broadcastify Feed', connection_test: true,
    fields: [...common, maximumAge, field('port', 'Port', 'number', { minimum: 1, maximum: 65535 }),
      field('password', 'Password', 'password'), field('delay', 'Delivery delay (milliseconds)', 'number',
        { maximum: 604800000, advanced: true }), field('mount_point', 'Mount point'),
      field('feed_id', 'Feed ID', 'number', { maximum: 2147483647 }),
      field('verbose_logging', 'Verbose logging', 'boolean', { advanced: true })] },
  { id: 'OPENMHZ', label: 'OpenMHz', connection_test: true,
    fields: [...common, maximumAge, apiKey, field('system_name', 'System short name')] },
  { id: 'RADIORESOLVE', label: 'RadioResolve', connection_test: true,
    fields: [...common, apiKey, field('mode', 'Publishing mode', 'select',
      { choices: ['CALLS_AND_METADATA', 'CALLS_ONLY', 'METADATA_ONLY'] }),
      field('ignore_certificate_errors', 'Ignore certificate errors', 'boolean', { advanced: true })] }
];
const rows = [
  { configuration_id: 'county-calls', name: 'County Calls', provider: 'BROADCASTIFY_CALL',
    provider_label: 'Broadcastify Call', enabled: true, state: 'CONNECTED', state_label: 'Connected',
    queued: 3, sent: 128, aged_off: 2, duplicate_rejected: 6, errors: 1, last_error: 'Connection timed out', attention: false },
  { configuration_id: 'regional-archive', name: 'Regional Archive', provider: 'RADIORESOLVE',
    provider_label: 'RadioResolve', enabled: true, state: 'CONNECTED', state_label: 'Connected',
    queued: 1, sent: 84, aged_off: 0, duplicate_rejected: null, errors: 0, last_error: null, attention: false },
  { configuration_id: 'county-feed', name: 'County Feed', provider: 'BROADCASTIFY',
    provider_label: 'Broadcastify Feed', enabled: false, state: 'DISABLED', state_label: 'Disabled',
    queued: 0, sent: 0, aged_off: 0, duplicate_rejected: null, errors: 0, last_error: null, attention: false }
];
const aliases = [
  { id: 910001, name: 'Sheriff Dispatch', identifier: '1001', alias_list_id: 900001,
    alias_list_name: 'County P25', assigned: true },
  { id: 910002, name: 'Regional Fire', identifier: '1002', alias_list_id: 900001,
    alias_list_name: 'County P25', assigned: false },
  { id: 910003, name: 'Police Department', identifier: '2001', alias_list_id: 900002,
    alias_list_name: 'City P25', assigned: true }
];
const operational = { revision: 'c'.repeat(64), settings: {
  patch_group_streaming_option: 'PATCH_GROUP', audio_record_format: 'MP3', mp3_setting: 'CBR_16',
  mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: false, stats_logging_enabled: true,
  stats_detailed_history_enabled: true, stats_logging_retention_days: 30
}, options: {
  patch_group_streaming_options: [{ value: 'PATCH_GROUP', label: 'Patch Group' },
    { value: 'TALKGROUPS', label: 'Individual Talkgroups' }],
  audio_record_formats: [{ value: 'MP3', label: 'MP3' }, { value: 'WAVE', label: 'WAV' }],
  mp3_settings: [{ value: 'CBR_16', label: '16 kbps' }],
  mp3_input_audio_formats_by_setting: { CBR_16: [{ value: 'SR_16000', label: '16 kHz' }] },
  minimum_stats_logging_retention_days: 1, maximum_stats_logging_retention_days: 365
} };

export function createStreamingGuideData({ signal } = {}) {
  const destinations = copy(rows), assignments = copy(aliases), preferences = copy(operational), requests = [];
  let assignmentRevision = 'd'.repeat(64);
  const checkAbort = requestSignal => {
    if (signal?.aborted || requestSignal?.aborted) throw new DOMException('The tour was closed.', 'AbortError');
  };
  const requestJson = async (path, options = {}) => {
    checkAbort(options.signal);
    const url = new URL(path, 'https://tour.invalid');
    const method = (options.method || 'GET').toUpperCase();
    const body = typeof options.body === 'string' ? JSON.parse(options.body) : options.body;
    requests.push({ path: url.pathname + url.search, method, body: copy(body) });
    const local = url.pathname.startsWith(ROOT) ? url.pathname.slice(ROOT.length) : null;
    if (method === 'GET' && url.pathname === '/api/v1/admin/radioreference') {
      return { account: { state: 'VALID_PREMIUM' } };
    }
    if (method === 'GET' && url.pathname === '/api/v1/admin/operational-preferences') return copy(preferences);
    if (method === 'GET' && local === '') return { destinations: copy(destinations) };
    if (method === 'GET' && local === '/options') return { providers: copy(providers), sites: [] };
    if (method === 'GET' && local?.startsWith('/templates/')) {
      const provider = local.slice('/templates/'.length);
      if (providers.some(item => item.id === provider)) return { provider, configured_credentials: [],
        settings: { name: '', enabled: false, host: '', maximum_recording_age: 600000,
          system_id: 0, test_enabled: false, test_interval: 15, mode: 'CALLS_AND_METADATA' } };
    }
    const id = local?.split('/')[1];
    const destination = destinations.find(row => row.configuration_id === id);
    if (method === 'GET' && destination && local === `/${id}`) return {
      edit_revision: 'e'.repeat(64), destination: { provider: destination.provider,
        configured_credentials: ['api_key'], settings: { name: destination.name, enabled: destination.enabled,
          host: 'https://example.invalid', maximum_recording_age: 600000, system_id: 42,
          test_enabled: true, test_interval: 15, mode: 'CALLS_AND_METADATA', ignore_certificate_errors: false } },
      status: copy(destination), references: { aliases: 2, alias_lists: [] }
    };
    if (method === 'GET' && destination && local === `/${id}/aliases`) {
      let items = assignments;
      if (url.searchParams.get('assigned') === 'true') items = items.filter(row => row.assigned);
      const query = (url.searchParams.get('q') || '').toLowerCase();
      if (query) items = items.filter(row => [row.name, row.identifier, row.alias_list_name]
        .some(value => value.toLowerCase().includes(query)));
      const offset = Number(url.searchParams.get('offset') || 0), limit = Number(url.searchParams.get('limit') || 50);
      return { revision: assignmentRevision, total: items.length, items: copy(items.slice(offset, offset + limit)) };
    }
    if (method === 'POST' && destination && local === `/${id}/aliases`) {
      if (body?.revision !== assignmentRevision) throw Object.assign(new Error('Example assignments changed.'), { code: 'stale_revision' });
      const add = new Set(body.add || []), remove = new Set(body.remove || []);
      assignments.forEach(row => { if (add.has(row.id)) row.assigned = true; if (remove.has(row.id)) row.assigned = false; });
      assignmentRevision = 'f'.repeat(64);
      return { revision: assignmentRevision };
    }
    if (method === 'POST' && local === '/feeds/refresh') return { items: [
      { id: 73001, name: 'County Feed', configured: true },
      { id: 73002, name: 'City Public Safety', configured: false }
    ] };
    throw new Error(`Unsupported Streaming example request: ${method} ${url.pathname}`);
  };
  const requestOperationalPreference = async (method = 'GET', preference = '', value, revision = '') => {
    checkAbort();
    if (method === 'GET' && !preference) return requestJson('/api/v1/admin/operational-preferences');
    if (method === 'PUT' && preference === 'patch_group_streaming_option' &&
        revision === preferences.revision && preferences.options.patch_group_streaming_options
          .some(option => option.value === value)) {
      requests.push({ path: `/api/v1/admin/operational-preferences/${preference}`, method, body: { value } });
      preferences.settings.patch_group_streaming_option = value;
      preferences.revision = 'b'.repeat(64);
      return copy(preferences);
    }
    throw new Error('Unsupported Streaming example preference request.');
  };
  return { requestJson, requestOperationalPreference, requests };
}
