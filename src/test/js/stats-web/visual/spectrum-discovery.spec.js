const { expect, test } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');
const root = resolve(__dirname, '../../../../..');
const app = readFileSync(resolve(root, 'stats-web/assets/app.js'), 'utf8');
const protocols = require(resolve(root, 'src/main/resources/channel-protocols.json'));
const realDirectory = process.env.VCE_DISCOVERY_REAL_DATA ?
  JSON.parse(readFileSync(process.env.VCE_DISCOVERY_REAL_DATA, 'utf8')) : null;

const ohioSystem = 'Ohio MARCS-IP: Multi-Agency Radio Communications';
const ohioSite = 'Cuyahoga County / Greater Cleveland Simulcast';
const fixtureStates = new WeakMap();

function ohioDiscoveryFixture(theme) {
  // Public system names with synthetic observations; no receiver is contacted.
  return { theme, templateName: 'MARCS Cuyahoga control', templateSystem: ohioSystem, templateSite: ohioSite,
    defaultAliasName: 'MARCS-IP', probeOverride: { identity: { wacn: 0xbee00, system: 0x348, rfss: 1, site: 27 } },
    directoryConfiguration: { account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 },
    directoryResult: { state: 'matched', provenance: 'Directory-only provenance', match: { rr_system_id: 123,
      system_name: ohioSystem, site_name: ohioSite, url: 'https://www.radioreference.com/db/sid/123',
      site_url: 'https://www.radioreference.com/db/site/456',
      channels: [{ frequency_hz: 851012500, primary_control: true }] } } };
}

function tuner() {
  return { id: 'idle-a', name: 'Test receiver', tuner_class: 'AIRSPY', status: 'ENABLED',
    available: true, operator_state: 'setup', channel_count: 0, frequency_hz: 851012500,
    spectrum_target_id: 'target-a', sample_rate_hz: 10000000,
    settings: [{ id: 'frequency_mhz', label: 'Center frequency', value: 851.0125,
      editable: true, availability: 'setup', minimum: 24, maximum: 1800,
      unavailable_reason: 'Unlock center', dependencies: [{ setting_id: 'center_frequency_locked', equals: false }] },
    { id: 'center_frequency_locked', label: 'Lock center', kind: 'boolean', value: false,
      editable: true, availability: 'live', dependencies: [] }] };
}

function snapshot(protocolId, state) {
  const profile = protocols.profiles.find((candidate) => candidate.id === protocolId);
  const isP25 = protocolId === 'p25-phase1';
  const digital = ['dmr', 'nxdn'].includes(protocolId);
  const analogName = digital ? `${protocolId.toUpperCase()} control` : protocolId === 'am' ? 'AM channel' : 'FM channel';
  const settings = Object.fromEntries(profile.sections.flatMap((section) => section.fields)
    .filter((field) => field.path.startsWith('settings.') && Object.hasOwn(field, 'default'))
    .map((field) => [field.path.substring(9), field.default]));
  if (digital) settings.channel_mode = 'TRUNKED';
  return { session_id: state.sessionId || 'discovery-a', state: state.saved ? state.running ? 'running' : 'saved' : state.phase || 'ready', reason: state.reason || null,
    protocol_id: state.automaticIdentifying && state.phase === 'identifying' ? 'auto' : protocolId,
    tuner_id: 'idle-a', target_id: 'target-a', frequency_hz: state.frequencyHz || 851012500,
    expires_at_ms: Date.now() + 30000,
    radio_reference: state.directoryResult?.state === 'matched' ? { ...state.directoryResult,
      match: { rr_system_id: 123, ...state.directoryResult.match } } : state.directoryResult,
    alias_import: state.aliasImports,
    digital_probe: digital ? { state: state.phase || 'ready', elapsed_ms: 1200, timeout_ms: 10000 } : null,
    trunked_evidence: digital ? { variant: protocolId === 'dmr' ? 'Tier III' : 'Type-C',
      serving_frequency_hz: state.servingFrequencyHz || null, identity: {
      radio_system_key: protocolId === 'dmr' ? 'dmr:tier3:small:12' : 'nxdn-c:local:12',
      network: protocolId === 'dmr' ? 12 : null, system: protocolId === 'nxdn' ? 12 : null, site: 3, ran: 9 },
      frequency_map: [{ number: 1, downlink_hz: 851012500 }] } : null,
    probe: isP25 ? {
      c4fm: { valid_messages: 18, valid_control_messages: 12, invalid_control_messages: 4, quality_pct: 81.2 },
      cqpsk: { valid_messages: 52, valid_control_messages: 41, invalid_control_messages: 1, quality_pct: 99.1,
        ...state.cqpskMetrics },
      elapsed_ms: 1500, timeout_ms: 15000,
      selected_modulation: state.phase === 'identifying' ? null : 'CQPSK',
      identity: state.phase === 'identifying' ? null : { wacn: 0xb0001, system: 0x123, rfss: 1, site: 2 },
      ...state.probeOverride
    } : null,
    review: state.saved || (state.phase && state.phase !== 'ready') ? null : {
      revision: state.revision || 7, template: { protocol_id: protocolId, name: state.templateName || (isP25 ? 'Control' : analogName),
        system: state.templateSystem || (isP25 ? 'P25 B0001-123' : digital ? 'County Transit' : ''),
        site: state.templateSite || (isP25 ? 'RFSS 1 Site 2' : digital ? 'North' : ''), settings,
        frequency_map: digital ? state.frequencyMap || [{ number: 1, downlink_hz: 851012500 }] : [] },
      alias_lists: state.aliasLists || (state.ambiguous ? [{ id: 21, name: 'North aliases', matched: true },
        { id: 22, name: 'South aliases', matched: true }] :
        (state.singleMatch ? [{ id: 21, name: 'County aliases', matched: true }] : [])),
      suggested_alias_list_id: null, default_new_alias_list_name: state.defaultAliasName || (isP25 ? 'P25 B0001-123' : analogName)
    },
    saved: state.saved ? { configuration_id: 'channel-a', alias_list_id: state.savedAliasId || 41,
      running: state.running === true, start_error: state.running ? null : state.startError || 'Tuner capacity is in use.',
      browse_lease_id: state.retainedBrowseLeaseId || null,
      previous_channels_not_resumed: state.previousChannelsNotResumed || [] } : null
  };
}

async function installLiveSpectrumStream(page) {
  await page.addInitScript(({ centerFrequencyHz, sampleRateHz }) => {
    const nativeFetch = window.fetch.bind(window);
    const encoder = new TextEncoder();
    let streamController = null;

    const multiplexFrame = (topic, kind, payload) => {
      const frame = new Uint8Array(16 + payload.byteLength);
      const header = new DataView(frame.buffer);
      header.setUint32(0, 0x534c4d58);
      header.setUint8(4, 2);
      header.setUint8(5, kind);
      header.setUint16(6, topic);
      header.setUint32(8, payload.byteLength);
      frame.set(payload, 16);
      return frame;
    };
    const tunerStateFrame = () => {
      const payload = encoder.encode(JSON.stringify({
        stream_state: 'live', center_frequency_hz: centerFrequencyHz,
        sample_rate_hz: sampleRateHz, profile: 'balanced'
      }));
      const frame = new Uint8Array(64 + payload.byteLength);
      const header = new DataView(frame.buffer);
      header.setUint32(0, 0x53444447, true);
      header.setUint8(4, 1);
      header.setUint8(5, 1);
      header.setUint16(6, 64, true);
      header.setUint32(8, payload.byteLength, true);
      header.setBigInt64(16, 1n, true);
      header.setBigInt64(24, 1n, true);
      header.setBigInt64(32, BigInt(Date.now()), true);
      header.setBigInt64(40, BigInt(Date.now()), true);
      header.setBigInt64(48, BigInt(centerFrequencyHz), true);
      header.setInt32(56, sampleRateHz, true);
      header.setInt32(60, 2048, true);
      frame.set(payload, 64);
      return frame;
    };
    const sendTunerState = () => {
      if (!streamController || streamController.desiredSize === null) return;
      streamController.enqueue(multiplexFrame(5, 2, tunerStateFrame()));
    };

    window.fetch = (input, options = {}) => {
      const rawUrl = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
      const url = new URL(rawUrl, window.location.href);
      if (url.pathname === '/api/v1/live/multiplex') {
        const ready = multiplexFrame(0, 1, encoder.encode(JSON.stringify({
          event: 'ready', data: { client_id: url.searchParams.get('client_id') }
        })));
        return Promise.resolve(new Response(new ReadableStream({
          start(controller) {
            streamController = controller;
            controller.enqueue(ready);
            window.setTimeout(sendTunerState, 100);
            window.setTimeout(sendTunerState, 500);
          },
          cancel() {
            streamController = null;
          }
        }), { status: 200, headers: { 'Content-Type': 'application/vnd.sdrtrunk.live+binary' } }));
      }
      return nativeFetch(input, options);
    };
  }, { centerFrequencyHz: 851012500, sampleRateHz: 10000000 });
}

async function install(page, state = {}) {
  fixtureStates.set(page, state);
  const browseWillFail = state.failBrowseOnce === true;
  state.tuner = tuner();
  const ownership = state.realSpectrumOwnership ? { nextLease: 0, active: null, stoppedChannels: 0 } : null;
  if (ownership) {
    state.ownership = ownership;
    state.tuner.operator_state = 'live';
    state.tuner.channel_count = 1;
  }
  state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value = state.locked === true;
  state.requests = [];
  state.completedSaveIndexes = [];
  if (state.liveSpectrum) await installLiveSpectrumStream(page);
  const preferenceModule = await import(pathToFileURL(resolve(root,
    'stats-web/assets/core/preference-schema.js')).href);
  await page.route('**/assets/app.js*', (route) => route.fulfill({ contentType: 'text/javascript',
    body: `${app}\nexport { openSpectrumDiscoveryWizard, openTunerFrequencyActions };` }));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const body = JSON.parse(request.postData() || '{}');
    const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify({ data }) });
    state.requests.push({ path, method: request.method(), body });
    if (path === '/api/v1/auth/session') return respond({ configured: true, authenticated: true,
      tier: 'admin', username: 'admin', csrf_token: 'test-token', capabilities: {
        'web-access': true, dashboard: true, 'tuner-spectrum': true, 'admin-tuners': true,
        'admin-channels': true, 'admin-aliases': true, 'admin-settings': state.adminSettings !== false,
        ...(state.listening ? { live: true, 'call-audio': true, recordings: true } : {})
      } });
    if (path === '/api/v1/me/preferences') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, preferences: { ...preferenceModule.defaults,
        appearance: { hue: null, theme: state.theme || 'light' } } }) });
    if (path === '/api/v1/spectrum-snap-presets') return route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ revision: 1, country_code: 'US', country_label: 'United States',
        countries: [{ code: 'US', label: 'United States' }], scopes: [] }) });
    if (path === '/api/v1/admin/tuners') return respond({ tuners: [state.tuner] });
    if (path.startsWith('/api/v1/admin/tuners/idle-a/settings/')) {
      const setting = state.tuner.settings.find((candidate) => candidate.id === path.split('/').at(-1));
      setting.value = body.value;
      if (setting.id === 'frequency_mhz') state.tuner.frequency_hz = Math.round(body.value * 1_000_000);
      return respond({});
    }
    if (path.endsWith('/browse')) {
      if (ownership) {
        if (request.method() === 'DELETE') {
          if (ownership.active && ownership.active.lease_id !== body.lease_id) return route.fulfill({ status: 409,
            json: { error: { code: 'tuner_browse_unavailable', message: 'Spectrum tuner session belongs to another browser' } } });
          if (ownership.active?.takeover) {
            state.previousChannelsNotResumed = body.handoff && state.leavePreviousStopped ?
              [{ id: 'previous-channel', name: 'Original distant receiver channel' }] : [];
            state.tuner.channel_count = state.previousChannelsNotResumed.length ? 0 : ownership.stoppedChannels;
            state.tuner.operator_state = 'live';
          }
          ownership.active = null;
          return respond({ released: true, previous_channels_not_resumed: state.previousChannelsNotResumed || [] });
        }
        if (body.lease_id && body.lease_id !== ownership.active?.lease_id) return route.fulfill({ status: 410,
          json: { error: { code: 'tuner_browse_expired', message: 'Spectrum tuner session expired' } } });
        if (!body.lease_id) {
          if (ownership.active) return route.fulfill({ status: 409, json: { error: {
            code: 'tuner_browse_unavailable', message: 'Spectrum tuner session belongs to another browser' } } });
          if (body.takeover) {
            ownership.stoppedChannels = state.tuner.channel_count;
            state.tuner.channel_count = 0;
            state.tuner.operator_state = 'setup';
          }
          ownership.active = { lease_id: `owned-browse-${++ownership.nextLease}`, takeover: body.takeover === true };
        }
        const result = { ...ownership.active, expires_at_epoch_ms: Date.now() + 30000,
          can_tune: ownership.active.takeover, tuner: structuredClone(state.tuner),
          stopped_channels: ownership.active.takeover ? [{ id: 'previous-channel', name: 'Original distant receiver channel' }] : [] };
        if (body.lease_id && state.delayOwnedRenewalOnce) {
          state.delayOwnedRenewalOnce = false;
          await new Promise((release) => { state.releaseOwnedRenewal = release; });
          if (state.failDelayedOwnedRenewal) return route.fulfill({ status: 410, json: { error: {
            code: 'tuner_browse_expired', message: 'Spectrum tuner session expired' } } });
        }
        return respond(result);
      }
      if (request.method() === 'DELETE') return respond(null, 204);
      if (state.failBrowseOnce) {
        state.failBrowseOnce = false;
        state.browseError = true;
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'busy', message: 'Tuner is temporarily unavailable.' } }) });
      }
      if (Number.isInteger(state.browseChannelCount)) state.tuner.channel_count = state.browseChannelCount;
      return respond({ lease_id: 'browse-a', expires_at_epoch_ms: Date.now() + 30000,
        can_tune: !state.running && state.tuner.channel_count === 0, tuner: state.tuner });
    }
    if (path === '/api/v1/diagnostics/tuners') return respond({ rows: state.liveSpectrum ? [{
      target_id: 'target-a', label: 'Test receiver', center_frequency_hz: 851012500,
      sample_rate_hz: 10000000
    }] : [] });
    if (path === '/api/v1/admin/radioreference') {
      if (state.delayDirectoryConfiguration) await new Promise((release) => { state.releaseDirectory = release; });
      return respond(state.directoryConfiguration || (state.directoryResult &&
        !['login_required', 'premium_required'].includes(state.directoryResult.state) ?
        { account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } : { account: { state: 'SIGNED_OUT' } }));
    }
    if (path === '/api/v1/admin/radioreference/session' && request.method() === 'PUT') {
      state.directoryConfiguration = state.connectedConfiguration || {
        account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 };
      return respond(state.directoryConfiguration);
    }
    if (path === '/api/v1/admin/radioreference/countries') {
      if (state.failDirectoryCountriesCount > 0) {
        state.failDirectoryCountriesCount -= 1;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'Countries temporarily unavailable.' } }) });
      }
      return respond({ items: [
        { id: 1, name: 'United States', abbreviation: 'US' }, { id: 2, name: 'Canada', abbreviation: 'CA' }
      ] });
    }
    if (path === '/api/v1/admin/radioreference/states') {
      if (state.delayDirectoryStates) {
        state.delayDirectoryStates = false;
        await new Promise((release) => { state.releaseDirectoryStates = release; });
      }
      if (state.failDirectoryStatesCount > 0) {
        state.failDirectoryStatesCount -= 1;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'States temporarily unavailable.' } }) });
      }
      return respond({ items: [{ id: 39, name: 'Ohio' }, { id: 42, name: 'Pennsylvania' }] });
    }
    if (path === '/api/v1/admin/channels/protocols') return respond(protocols);
    if (path.endsWith('/eligibility')) return respond({ eligible: !state.known,
      reason: state.known ? 'This frequency belongs to County Control.' : null,
      matches: state.known ? [{ configuration_id: 'channel-a', name: 'County Control',
        system: 'County P25', site: 'North' }] : [] });
    if (path === '/api/v1/admin/spectrum-discovery' && request.method() === 'POST') {
      if (state.failCreateOnce) {
        state.failCreateOnce = false;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'Could not start the signal check. Try again.' } }) });
      }
      state.protocolId = body.protocol_id === 'auto' ? state.detectedProtocolId || 'p25-phase1' : body.protocol_id;
      state.frequencyHz = body.frequency_hz;
      state.discoveryActive = true;
      if (state.uniqueSessionIds) state.sessionId = `discovery-${state.sessionSequence = (state.sessionSequence || 0) + 1}`;
      return respond(snapshot(state.protocolId, state));
    }
    if (path === `/api/v1/admin/spectrum-discovery/${state.sessionId || 'discovery-a'}`) {
      if (request.method() === 'DELETE') {
        if (state.expiredCleanup) return route.fulfill({ status: 409, json: { error: {
          code: 'discovery_conflict', message: 'Channel discovery expired; begin again' } } });
        if (state.deferDeleteOnce) {
          state.deferDeleteOnce = false;
          await new Promise((release) => { state.releaseDelete = release; });
        }
        if (state.failDeleteOnce) {
          state.failDeleteOnce = false;
          return route.fulfill({ status: 503, contentType: 'application/json',
            body: JSON.stringify({ error: { code: 'unavailable', message: 'Could not stop the signal check.' } }) });
        }
        state.discoveryActive = false;
        return respond(null, 204);
      }
      if (state.publishedSave) return route.fulfill({ status: 409, json: { error: {
        code: 'discovery_conflict', message: 'Channel discovery expired; begin again' } } });
      if (state.expiredStatus) return route.fulfill({ status: state.expiredStatusCode || 409, contentType: 'application/json',
        body: JSON.stringify({ error: { code: state.expiredStatusCode === 410 ? 'spectrum_discovery_expired' : 'discovery_conflict',
          message: 'Channel discovery expired; begin again' } }) });
      if (state.failProbeStatusOnce) {
        state.failProbeStatusOnce = false;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'Status temporarily unavailable.' } }) });
      }
      if (state.deferStatusOnce) {
        state.deferStatusOnce = false;
        const previous = snapshot(state.protocolId, state);
        await new Promise((release) => { state.releaseStatus = release; });
        if (state.deferredStatusError) return route.fulfill({ status: state.deferredStatusError.status,
          contentType: 'application/json', body: JSON.stringify({ error: state.deferredStatusError }) });
        return respond(previous);
      }
      return respond(snapshot(state.protocolId, state));
    }
    if (path.endsWith('/save')) {
      if (state.restartRequired) {
        state.publishedSave = true;
        state.discoveryActive = false;
        state.completedSaveIndexes.push(state.requests.length - 1);
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'channel_saved_restart_required',
            message: 'The channel was saved, but receiver configuration could not refresh.' } }) });
      }
      if (state.failSaveOnce) {
        state.failSaveOnce = false;
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'unavailable', message: 'Could not add this channel. Try again.' } }) });
      }
      if (state.staleOnce) {
        state.staleOnce = false;
        state.revision = 8;
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'stale_revision', message: 'Configuration changed.' } }) });
      }
      state.saved = true;
      state.discoveryActive = false;
      state.completedSaveIndexes.push(state.requests.length - 1);
      state.running = state.failStart !== true;
      if (ownership) {
        expect(body.browse_lease_id).toBe(ownership.active?.lease_id);
        if (state.retainHandoffOnce) {
          state.retainHandoffOnce = false;
          state.retainedBrowseLeaseId = ownership.active.lease_id;
          state.running = false;
          state.startError = 'The receiver handoff is busy. Try again.';
        } else {
          state.retainedBrowseLeaseId = null;
          ownership.active = null;
          state.previousChannelsNotResumed = state.leavePreviousStopped ?
            [{ id: 'previous-channel', name: 'Original distant receiver channel' }] : [];
          state.tuner.channel_count = (state.running ? 1 : 0) +
            (state.previousChannelsNotResumed.length ? 0 : ownership.stoppedChannels);
          state.tuner.operator_state = 'live';
        }
      }
      state.savedAliasId = body.alias_list_id || 41;
      const hasTarget = !state.missingImportTarget && ['p25-phase1', 'dmr', 'nxdn'].includes(state.protocolId);
      state.aliasImports = { targets: hasTarget ? [{ alias_list_id: state.savedAliasId,
        alias_list_name: body.new_alias_list_name || state.aliasLists?.find(list => list.id === body.alias_list_id)?.name || 'County aliases',
        system_id: state.directoryResult?.state === 'matched' ? 123 : null,
        system_name: state.directoryResult?.match?.system_name || body.system,
        state: state.directoryResult?.state === 'matched' ? 'pending' : 'unavailable',
        added: 0, updated: 0, unchanged: 0, message: 'Import these talkgroups later.' }] : [], complete: !hasTarget };
      return respond(snapshot(state.protocolId, state));
    }
    if (path.endsWith('/aliases/import')) {
      expect(body).toEqual({ alias_list_id: state.savedAliasId || 41 });
      if (state.delayImport) await new Promise((release) => { state.releaseImport = release; });
      const failed = state.failImportOnce === true;
      state.failImportOnce = false;
      state.aliasImports = { targets: state.aliasImports.targets.map((target) => ({ ...target,
        state: failed ? 'failed' : 'imported', added: failed ? 0 : state.importCount ?? 73, updated: 0, unchanged: 0,
        message: failed ? 'RadioReference could not load these talkgroups. Try again.' : null })), complete: !failed };
      return respond(state.aliasImports);
    }
    if (path.endsWith('/start')) {
      state.running = true;
      state.tuner.channel_count = 1;
      state.tuner.operator_state = 'live';
      return respond(snapshot(state.protocolId, state));
    }
    if (path === '/api/v1/admin/channels/actions') {
      let receiver = null;
      if (body.receiver) {
        expect(body.receiver.tuner_id).toBe('idle-a');
        if (ownership?.active?.lease_id !== body.receiver.browse_lease_id) return route.fulfill({ status: 409,
          json: { error: { code: 'tuner_browse_unavailable', message: 'Spectrum tuner session belongs to another browser' } } });
        ownership.active = null;
        state.retainedBrowseLeaseId = null;
        state.previousChannelsNotResumed = state.leavePreviousStopped ?
          [{ id: 'previous-channel', name: 'Original distant receiver channel' }] : [];
        receiver = { browse_lease_id: null, previous_channels_not_resumed: state.previousChannelsNotResumed };
      } else if (ownership?.active?.takeover) return route.fulfill({ status: 409, json: { error: {
        code: 'tuner_browse_unavailable', message: 'Receiver is still reserved by Spectrum' } } });
      state.channelActionStarts = (state.channelActionStarts || 0) + 1;
      state.running = true;
      state.tuner.channel_count = 1;
      state.tuner.operator_state = 'live';
      return respond({ results: [{ configuration_id: 'channel-a', success: true, state: 'RUNNING', message: null }],
        ...(receiver ? { receiver } : {}) });
    }
    return respond({});
  });
  await page.goto('/app.html?view=tuner-spectrum');
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  if (browseWillFail) await expect(page.getByRole('button', { name: 'Retry browsing', exact: true })).toBeVisible();
  else await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
  await page.evaluate(async (frequencyHz) => {
    window.discoveryApi = await import(document.querySelector('script[type="module"][src*="/assets/app.js"]').src);
    window.discoveryProbeStates = [];
    window.openDiscovery = () => window.discoveryApi.openSpectrumDiscoveryWizard({
      tunerId: 'idle-a', tunerName: 'Test receiver', targetId: 'target-a', frequencyHz,
      browseLeaseId: 'browse-a',
      setProbeActive: (active) => window.discoveryProbeStates.push(active)
    });
  }, state.discoveryFrequencyHz || 851012500);
}

const wizard = (page) => page.locator('.spectrum-discovery-modal');
const signalDetails = (page) => page.locator('.spectrum-discovery-detail-modal');
async function begin(page, protocolId = 'p25-phase1') {
  fixtureStates.get(page).detectedProtocolId = protocolId;
  await page.evaluate(() => window.openDiscovery());
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
}

async function beginThroughSpectrumRail(page) {
  await page.getByRole('button', { name: 'Setup', exact: true }).click();
  const confirmation = page.getByRole('alertdialog', { name: 'Stop channels to tune?', exact: true });
  await expect(confirmation).toBeVisible({ timeout: 5000 });
  await confirmation.getByRole('button', { name: 'Stop channels and tune', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.spectrum-browse-panel .channel-diagnostic-overlay').first()).toBeHidden();
  await page.locator('.spectrum-browse-panel').getByRole('img', { name: 'Tuner frequency spectrum', exact: true })
    .click({ position: { x: 240, y: 80 } });
  const add = page.locator('.spectrum-browse-control-rail').getByRole('button', {
    name: 'Add channel or system', exact: true });
  await expect(add).toBeEnabled();
  await add.click();
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
}

async function review(page) {
  await wizard(page).getByRole('button', { name: 'Review channel', exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'Review channel', exact: true })).toBeVisible();
}

const importYes = true;
const importNo = false;
const importTalkgroups = (page) => wizard(page).getByRole('checkbox', { name: 'Import talkgroup names', exact: true });

test.afterEach(async ({ page }) => {
  const state = fixtureStates.get(page);
  if (!state) return;
  expect(state.requests.filter(({ path }) =>
    path.startsWith('/api/v1/admin/radioreference/systems/talkgroups'))).toHaveLength(0);
  for (const savedAt of state.completedSaveIndexes) {
    const nextSetup = state.requests.findIndex(({ path, method }, index) => index > savedAt &&
      path === '/api/v1/admin/spectrum-discovery' && method === 'POST');
    const afterSave = state.requests.slice(savedAt + 1, nextSetup < 0 ? undefined : nextSetup);
    expect(afterSave.filter(({ path, method }) => path === '/api/v1/admin/spectrum-discovery/discovery-a' &&
      ['GET', 'DELETE'].includes(method))).toHaveLength(0);
  }
});

test('initial manual RadioReference state stays quiet until lookup starts and keeps real failures visible', async ({ page }) => {
  await page.clock.install();
  const state = { phase: 'identifying', directoryResult: { state: 'manual' } };
  await install(page, state);
  await begin(page);
  await expect(wizard(page).locator('.spectrum-discovery-directory')).toBeHidden();
  await expect(wizard(page).getByText('Directory names are unavailable.', { exact: true })).toHaveCount(0);
  state.phase = 'ready';
  state.directoryResult = { state: 'pending' };
  await page.clock.fastForward(800);
  const loading = wizard(page).getByRole('status').filter({ hasText: /^Looking up RadioReference names…$/ });
  await expect(loading).toBeVisible();
  await expect(wizard(page).getByRole('button', { name: 'Review channel', exact: true })).toBeDisabled();
  state.directoryResult = { state: 'unavailable' };
  await page.clock.fastForward(800);
  await expect(loading).toBeHidden();
  await expect(wizard(page).getByRole('button', { name: 'Review channel', exact: true })).toBeEnabled();
  const result = wizard(page).locator('.spectrum-discovery-directory details');
  await expect(result).toBeVisible();
  await result.locator('summary').click();
  await expect(result).toContainText('RadioReference is unavailable. The on-air result is still available.');
});

test('saved discovery completion closes without deleting the ended RF session', async ({ page }) => {
  await page.clock.install();
  const state = {};
  await install(page, state);
  await begin(page);
  await review(page);
  await addDiscovery(page);
  state.expiredCleanup = true;
  await wizard(page).getByRole('button', { name: 'Done', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
  expect(state.requests.filter((request) => request.method === 'DELETE' &&
    request.path === '/api/v1/admin/spectrum-discovery/discovery-a')).toHaveLength(0);
});

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
test(`real Spectrum rail takes over a running receiver and keeps the new channel running when previous channels stay stopped in ${theme} at ${width}px`, async ({ page }, testInfo) => {
  await page.setViewportSize({ width, height: 900 });
  const state = { theme, realSpectrumOwnership: true, liveSpectrum: true, leavePreviousStopped: true };
  await install(page, state);
  await beginThroughSpectrumRail(page);
  await review(page);
  await addDiscovery(page);
  await expect(wizard(page).locator('.discovery-completion-status')).toHaveText('Running');
  await expect.poll(() => state.ownership.active?.lease_id).toBe('owned-browse-3');
  await expect(wizard(page)).toContainText('1 previous channel remains stopped.');
  await expect(wizard(page).getByRole('link', { name: 'Review in Tuners', exact: true })).toBeVisible();
  await expect(wizard(page).getByRole('link', { name: 'Review in Tuners', exact: true })).toHaveAttribute('href', '/?view=tuners');
  await expect(wizard(page)).not.toContainText('listening couldn’t start');
  await expect(wizard(page).locator('.discovery-completion-details')).toContainText('Original distant receiver channel');
  await expect(page.getByRole('button', { name: 'Live', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(state.requests.find((request) => request.path.endsWith('/save')).body.browse_lease_id).toBe('owned-browse-2');
  expect(await wizard(page).evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
  await wizard(page).screenshot({ path: testInfo.outputPath(`handoff-complete-${theme}-${width}.png`) });
  await wizard(page).getByRole('button', { name: 'Done', exact: true }).click();
  await expect(wizard(page)).toHaveCount(0);
  await expect(page.locator('.spectrum-browse-state')).not.toContainText('In use');
});
}

test('real Spectrum rail atomically retries a failed handoff with its exact lease after completion expiry', async ({ page }) => {
  await page.clock.install();
  const state = { realSpectrumOwnership: true, liveSpectrum: true, retainHandoffOnce: true, leavePreviousStopped: true };
  await install(page, state);
  await beginThroughSpectrumRail(page);
  await review(page);
  await wizard(page).getByLabel('Channel name', { exact: true }).fill('My saved control');
  await wizard(page).getByLabel('New Alias List name', { exact: true }).fill('My saved aliases');
  await addDiscovery(page);
  await expect(wizard(page).getByRole('button', { name: 'Try starting again', exact: true })).toBeEnabled();
  expect(state.ownership.active.lease_id).toBe('owned-browse-2');
  await expect(page.getByRole('button', { name: 'Setup', exact: true })).toHaveAttribute('aria-pressed', 'true');
  state.expiredStatus = true;
  state.expiredCleanup = true;
  await page.clock.fastForward(16 * 60_000);
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/browse') &&
    request.method === 'POST' && request.body.lease_id === 'owned-browse-2')).toBe(true);
  await wizard(page).getByRole('button', { name: 'Try starting again', exact: true }).click();
  await expect(wizard(page).locator('.discovery-completion-status')).toHaveText('Running');
  await expect.poll(() => state.ownership.active?.lease_id).toBe('owned-browse-3');
  expect(state.requests.filter((request) => request.path.endsWith('/browse') &&
    request.method === 'DELETE' && request.body.lease_id === 'owned-browse-2')).toHaveLength(0);
  const starts = state.requests.filter((request) => request.path === '/api/v1/admin/channels/actions');
  expect(starts).toHaveLength(1);
  expect(starts[0].body).toEqual({ action: 'START', configuration_ids: ['channel-a'],
    receiver: { tuner_id: 'idle-a', browse_lease_id: 'owned-browse-2' } });
  expect(state.channelActionStarts).toBe(1);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  await expect(wizard(page).locator('.discovery-completion-name')).toHaveText('My saved control');
  await expect(wizard(page).locator('.discovery-completion-details')).toContainText('My saved aliases');
  await expect(wizard(page)).toContainText('1 previous channel remains stopped.');
  await wizard(page).getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.locator('.spectrum-browse-state')).not.toContainText('In use');
  expect(state.requests.filter((request) => request.method === 'DELETE' &&
    request.path === '/api/v1/admin/spectrum-discovery/discovery-a')).toHaveLength(0);
});

for (const failure of [false, true]) {
  test(`real Spectrum rail ignores a late old browse renewal ${failure ? 'error' : 'snapshot'} after handoff`, async ({ page }) => {
    await page.clock.install();
    const state = { realSpectrumOwnership: true, liveSpectrum: true,
      delayOwnedRenewalOnce: false, failDelayedOwnedRenewal: failure };
    await install(page, state);
    await beginThroughSpectrumRail(page);
    await review(page);
    state.delayOwnedRenewalOnce = true;
    await page.clock.fastForward(10100);
    await expect.poll(() => typeof state.releaseOwnedRenewal).toBe('function');
    await addDiscovery(page);
    await expect(wizard(page).locator('.discovery-completion-status')).toHaveText('Running');
    await expect.poll(() => state.ownership.active?.lease_id).toBe('owned-browse-3');
    state.releaseOwnedRenewal();
    await page.clock.fastForward(1000);
    await expect(page.getByRole('button', { name: 'Live', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('button', { name: 'Retry browsing', exact: true })).toBeHidden();
    await page.clock.fastForward(11000);
    await expect.poll(() => state.requests.some((request) => request.path.endsWith('/browse') &&
      request.method === 'POST' && request.body.lease_id === 'owned-browse-3')).toBe(true);
    expect(state.ownership.active.lease_id).toBe('owned-browse-3');
  });
}

test('real Spectrum rail rejects atomic retry against another tab ownership without starting or releasing it', async ({ page }) => {
  const state = { realSpectrumOwnership: true, liveSpectrum: true, retainHandoffOnce: true };
  await install(page, state);
  await beginThroughSpectrumRail(page);
  await review(page);
  await addDiscovery(page);
  await expect(wizard(page).getByRole('button', { name: 'Try starting again', exact: true })).toBeEnabled();
  state.ownership.active = { lease_id: 'other-tab-lease', takeover: true };
  await wizard(page).getByRole('button', { name: 'Try starting again', exact: true }).click();
  await expect(wizard(page).getByRole('button', { name: 'Try starting again', exact: true })).toBeEnabled();
  await expect(wizard(page)).toContainText('Spectrum tuner session belongs to another browser');
  expect(state.ownership.active.lease_id).toBe('other-tab-lease');
  const starts = state.requests.filter((request) => request.path === '/api/v1/admin/channels/actions');
  expect(starts).toHaveLength(1);
  expect(starts[0].body.receiver).toEqual({ tuner_id: 'idle-a', browse_lease_id: 'owned-browse-2' });
  expect(state.channelActionStarts || 0).toBe(0);
  expect(state.running).toBe(false);
  expect(state.requests.filter((request) => request.path.endsWith('/browse') && request.method === 'DELETE' &&
    ['owned-browse-2', 'other-tab-lease'].includes(request.body.lease_id))).toHaveLength(0);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
});

async function expectStepWordsFit(modal) {
  expect(await modal.evaluate((element) => [...element.querySelectorAll('.spectrum-discovery-progress li > span:last-child')]
    .every((label) => [...label.textContent.matchAll(/\S+/g)].every((word) => {
      const range = document.createRange();
      range.setStart(label.firstChild, word.index);
      range.setEnd(label.firstChild, word.index + word[0].length);
      return range.getClientRects().length <= 1;
    })))).toBe(true);
}

async function addDiscovery(page, importChoice = importNo) {
  const modal = wizard(page);
  const choice = importTalkgroups(page);
  if (await choice.count()) await choice.setChecked(importChoice);
  await modal.getByRole('button', { name: 'Add and start listening', exact: true }).click();
}

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`discovery offers talkgroup import inline on one Review in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    const state = ohioDiscoveryFixture(theme);
    await install(page, state);
    await begin(page);
    await review(page);
    await expect(wizard(page).getByRole('heading', { name: 'Review channel', exact: true })).toBeVisible();
    await expect(wizard(page).getByRole('button', { name: 'Continue', exact: true })).toHaveCount(0);
    await expect(wizard(page).getByRole('radio')).toHaveCount(0);
    await expect(importTalkgroups(page)).toBeChecked();
    await expect(wizard(page).getByRole('button', { name: 'Add and start listening', exact: true })).toBeEnabled();
    await expect(wizard(page).getByRole('button', { name: 'Back', exact: true })).toBeVisible();
    expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(0);
    expect(state.requests.filter(({ path }) => path.endsWith('/systems/talkgroups/availability'))).toHaveLength(0);
    expect(state.requests.filter(({ path }) => path.endsWith('/systems/talkgroups'))).toHaveLength(0);
    const fits = await wizard(page).evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      const footer = element.querySelector('.spectrum-discovery-actions').getBoundingClientRect();
      return element.scrollWidth <= element.clientWidth + 1 && bounds.left >= 0 && bounds.right <= innerWidth &&
        footer.bottom <= innerHeight;
    });
    expect(fits).toBe(true);
    await wizard(page).screenshot({ animations: 'disabled', path: testInfo.outputPath(`import-choice-${theme}-${width}.png`) });
    await importTalkgroups(page).scrollIntoViewIfNeeded();
    await expect(wizard(page).getByRole('button', { name: 'Add and start listening', exact: true })).toBeInViewport();
    const footer = await wizard(page).locator('.spectrum-discovery-actions').boundingBox();
    expect(footer.y).toBeGreaterThanOrEqual(0);
    expect(footer.y + footer.height).toBeLessThanOrEqual(900);
    await wizard(page).screenshot({ animations: 'disabled', path: testInfo.outputPath(`review-options-${theme}-${width}.png`) });
    await importTalkgroups(page).uncheck();
    await addDiscovery(page);
    await expect(wizard(page).locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
    expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(0);
  });
}

const directoryNamesLoading = (page) => wizard(page).getByRole('status')
  .filter({ hasText: /^Looking up RadioReference names…$/ });
const discoveryAdvance = (page) => wizard(page).getByRole('button', {
  name: 'Add and start listening', exact: true
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`discovery waits for RadioReference names before adding and still offers talkgroup import in ${theme} at ${width}px`, async ({ page }) => {
    await page.clock.install();
    await page.setViewportSize({ width, height: 900 });
    const matched = ohioDiscoveryFixture(theme);
    const state = { ...matched, templateSystem: 'Decoded network', templateName: 'Decoded control',
      defaultAliasName: 'Decoded network', directoryResult: { state: 'pending' } };
    await install(page, state);
    await begin(page);
    await expect(directoryNamesLoading(page)).toBeVisible();
    const reviewAction = wizard(page).getByRole('button', { name: 'Review channel', exact: true });
    await expect(reviewAction).toBeDisabled();
    await expect(directoryNamesLoading(page)).toBeVisible();
    await expect(directoryNamesLoading(page)).toBeInViewport();
    await expect(wizard(page).getByLabel('Channel name', { exact: true })).toHaveCount(0);
    expect(await wizard(page).evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return element.scrollWidth <= element.clientWidth + 1 && bounds.left >= 0 && bounds.right <= innerWidth;
    })).toBe(true);
    await wizard(page).screenshot({ path: test.info().outputPath(`loading-${theme}-${width}.png`) });
    // A queued click must not bypass readiness even if the button has just become disabled.
    await reviewAction.dispatchEvent('click');
    await page.clock.fastForward(50);
    expect(state.requests.filter(({ path }) => path.endsWith('/save') || path.includes('/systems/talkgroups'))).toHaveLength(0);
    state.templateSystem = matched.templateSystem;
    state.defaultAliasName = matched.defaultAliasName;
    state.directoryResult = matched.directoryResult;
    await page.clock.fastForward(800);
    await expect(directoryNamesLoading(page)).toBeHidden();
    await expect(reviewAction).toBeEnabled();
    await review(page);
    await expect(discoveryAdvance(page)).toBeEnabled();
    await wizard(page).getByLabel('Channel name', { exact: true }).fill('My control channel');
    await expect(wizard(page).getByLabel('Channel name', { exact: true })).toHaveValue('My control channel');
    await expect(importTalkgroups(page)).toBeChecked();
    expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(0);
    await addDiscovery(page, importYes);
    await expect(wizard(page).locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
    expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
    expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(1);
  });
}

for (const terminalState of ['no_match', 'unavailable', 'ambiguous']) {
  test(`discovery unlocks adding after RadioReference names resolve to ${terminalState}`, async ({ page }) => {
    await page.clock.install();
    const state = { directoryResult: { state: 'pending' } };
    await install(page, state);
    await begin(page);
    await expect(wizard(page).getByRole('button', { name: 'Review channel', exact: true })).toBeDisabled();
    await expect(directoryNamesLoading(page)).toBeVisible();
    state.directoryResult = { state: terminalState };
    await page.clock.fastForward(800);
    await expect(directoryNamesLoading(page)).toBeHidden();
    await expect(wizard(page)).toContainText('The detected names will be used.');
    await review(page);
    await expect(importTalkgroups(page)).toHaveCount(0);
    await expect(wizard(page).getByRole('button', { name: 'Add and start listening', exact: true })).toBeEnabled();
    await addDiscovery(page);
    await expect(wizard(page).locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
    expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
    expect(state.requests.filter(({ path }) => path.includes('/systems/talkgroups') || path.endsWith('/aliases/import'))).toHaveLength(0);
  });
}

test('discovery keeps adding protected after a RadioReference names poll fails and recovers on retry', async ({ page }) => {
  await page.clock.install();
  const state = { ...ohioDiscoveryFixture('light'), directoryResult: { state: 'pending' } };
  await install(page, state);
  await begin(page);
  const reviewAction = wizard(page).getByRole('button', { name: 'Review channel', exact: true });
  await expect(reviewAction).toBeDisabled();
  state.failProbeStatusOnce = true;
  await page.clock.fastForward(800);
  const retry = wizard(page).getByRole('button', { name: 'Retry directory lookup', exact: true });
  await expect(retry).toBeEnabled();
  await expect(directoryNamesLoading(page)).toBeVisible();
  await expect(reviewAction).toBeDisabled();
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(0);
  state.directoryResult = ohioDiscoveryFixture('light').directoryResult;
  await retry.click();
  await expect(directoryNamesLoading(page)).toBeHidden();
  await review(page);
  await expect(discoveryAdvance(page)).toBeEnabled();
});

for (const response of [
  { status: 409, code: 'discovery_conflict', message: 'Channel discovery expired; begin again' },
  { status: 503, code: 'discovery_unavailable', message: 'Status temporarily unavailable.' }
]) {
  test(`completed discovery ignores an in-flight heartbeat ${response.status} and stops polling`, async ({ page }) => {
    await page.clock.install();
    const state = {};
    await install(page, state);
    await begin(page);
    await review(page);
    state.deferStatusOnce = true;
    await page.clock.fastForward(11000);
    await expect.poll(() => typeof state.releaseStatus).toBe('function');
    await addDiscovery(page);
    const modal = wizard(page);
    await expect(modal.locator('.discovery-completion')).toBeVisible();
    state.deferredStatusError = response;
    state.releaseStatus();
    await page.clock.fastForward(100);
    await expect(modal).not.toContainText(/refresh this setup|Check your connection|discovery expired/);
    const statusRequests = () => state.requests.filter(({ path, method }) =>
      path === '/api/v1/admin/spectrum-discovery/discovery-a' && method === 'GET').length;
    const finishedCount = statusRequests();
    state.expiredStatus = true;
    await page.clock.fastForward(6 * 60_000);
    expect(statusRequests()).toBe(finishedCount);
    await expect(modal.getByRole('button', { name: 'Done', exact: true })).toBeEnabled();
    expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
  });
}

test('a long successful talkgroup import remains complete after the temporary discovery expires', async ({ page }) => {
  await page.clock.install();
  const state = { ...ohioDiscoveryFixture('light'), delayImport: true, importCount: 6650 };
  await install(page, state);
  await begin(page);
  await review(page);
  await addDiscovery(page, importYes);
  await expect.poll(() => typeof state.releaseImport).toBe('function');
  await expect(wizard(page)).toContainText('Importing talkgroups');
  state.expiredStatus = true;
  await page.clock.fastForward(45_000);
  state.delayImport = false;
  state.releaseImport();
  await expect(wizard(page).locator('.discovery-completion')).toBeVisible();
  await expect(wizard(page)).toContainText('6,650 talkgroups added');
  await page.clock.fastForward(6 * 60_000);
  await expect(wizard(page)).not.toContainText(/refresh this setup|Check your connection|discovery expired/);
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(1);
});

test('completion uses the detected serving carrier when the saved channel differs from the selected frequency', async ({ page }) => {
  const state = { servingFrequencyHz: 852012500 };
  await install(page, state);
  await begin(page, 'dmr');
  await review(page);
  await addDiscovery(page);
  const metadata = wizard(page).locator('.discovery-completion-meta');
  await expect(metadata).toContainText('852.012500 MHz');
  await expect(metadata).not.toContainText('851.012500 MHz');
  await expect(metadata).toContainText('Running');
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
});

test('failed talkgroup imports remain retryable after RF discovery expiry without recreating the channel', async ({ page }) => {
  await page.clock.install();
  const state = { ...ohioDiscoveryFixture('light'), failImportOnce: true };
  await install(page, state);
  await begin(page);
  await review(page);
  await addDiscovery(page, importYes);
  const retry = wizard(page).getByRole('button', { name: 'Retry talkgroup imports', exact: true });
  await expect(retry).toBeEnabled();
  state.expiredStatus = true;
  await page.clock.fastForward(6 * 60_000);
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect(wizard(page).locator('.discovery-completion')).toContainText('73 talkgroups added');
  await expect(retry).toHaveCount(0);
  await expect(wizard(page)).not.toContainText(/refresh this setup|Check your connection|discovery expired/);
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(2);
});

for (const theme of ['light', 'dark']) for (const width of [1280, 390]) {
  test(`compact channel completion shows one long result and folded details in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    const name = 'Greater Cleveland Radio Communications Network · Cleveland Simulcast and Regional Public Safety Dispatch';
    const state = { ...ohioDiscoveryFixture(theme), singleMatch: true, importCount: 6650 };
    await install(page, state);
    await begin(page);
    await review(page);
    await wizard(page).getByLabel('Channel name', { exact: true }).fill(name);
    await addDiscovery(page, importYes);
    const modal = wizard(page);
    const completion = modal.locator('.discovery-completion');
    await expect(completion).toBeVisible();
    await expect(modal.locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toHaveCount(1);
    await expect(completion.locator('.discovery-completion-name')).toHaveText(name);
    await expect(completion.locator('.ui-pill').filter({ hasText: /^Running$/ })).toHaveCount(1);
    await expect(completion.locator('.discovery-completion-import')).toHaveCount(1);
    await expect(completion).toContainText('6,650 talkgroups added');
    await expect(modal.locator('.spectrum-discovery-context')).toBeHidden();
    await expect(modal.locator('.spectrum-discovery-progress')).toBeHidden();
    const details = completion.locator('.discovery-completion-details');
    await expect(details).not.toHaveAttribute('open', '');
    await expect(modal.getByRole('link', { name: 'Open channel', exact: true })).toBeVisible();
    const bounds = await modal.boundingBox();
    expect(bounds.height).toBeLessThan(700);
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
    expect(await completion.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    await modal.screenshot({ path: testInfo.outputPath(`completion-${theme}-${width}.png`) });
    await details.getByText('Details', { exact: true }).click();
    await expect(details).toContainText('6650 added, 0 updated, 0 unchanged.');
    await expect(details).toContainText('Test receiver');
  });
}

test('discovery Yes imports talkgroups after adding and retries an import failure without adding again', async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), failImportOnce: true };
  await install(page, state);
  await begin(page);
  await review(page);
  await addDiscovery(page, importYes);
  const retry = wizard(page).getByRole('button', { name: 'Retry talkgroup imports', exact: true });
  await expect(retry).toBeEnabled();
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
  await retry.click();
  await expect(retry).toHaveCount(0);
  await expect(wizard(page)).toContainText('73 added, 0 updated, 0 unchanged.');
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import')).map(({ body }) => body))
    .toEqual([{ alias_list_id: 41 }, { alias_list_id: 41 }]);
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
});

test('discovery keeps an explicit No choice after Back and never retries imports after a publication failure', async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), restartRequired: true };
  await install(page, state);
  await begin(page);
  await review(page);
  await importTalkgroups(page).uncheck();
  await wizard(page).getByRole('button', { name: 'Back', exact: true }).click();
  await review(page);
  await expect(importTalkgroups(page)).not.toBeChecked();
  await importTalkgroups(page).check();
  await addDiscovery(page, importYes);
  await expect(wizard(page)).toContainText('Your channel was saved, but VCE needs to restart');
  await expect(wizard(page).getByRole('button', { name: 'Retry talkgroup imports', exact: true })).toHaveCount(0);
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(0);
});

test('discovery imports into an explicitly reused same-name list and skips without a directory match', async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), aliasLists: [{ id: 21, name: ohioSystem, matched: false }] };
  await install(page, state);
  await begin(page);
  await review(page);
  await addDiscovery(page, importYes);
  await expect(wizard(page).locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(1);
  expect(state.requests.find(({ path }) => path.endsWith('/save')).body.alias_list_id).toBe(21);
  await wizard(page).getByRole('button', { name: 'Done', exact: true }).click();
  state.saved = false;
  state.directoryResult = { state: 'no_match' };
  await begin(page);
  await review(page);
  await expect(wizard(page).getByRole('button', { name: 'Continue', exact: true })).toHaveCount(0);
  await expect(wizard(page).getByRole('button', { name: 'Add and start listening', exact: true })).toBeEnabled();
  await expect(importTalkgroups(page)).toHaveCount(0);
});

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`real directory replay discovery review import and completion in ${theme} at ${width}px`, async ({ page }, testInfo) => {
    test.skip(!realDirectory, 'Set VCE_DISCOVERY_REAL_DATA to an independently verified directory capture.');
    await page.setViewportSize({ width, height: 900 });
    const state = { theme, discoveryFrequencyHz: realDirectory.frequencies_hz[0],
      templateName: 'Lorain County control', templateSystem: realDirectory.system_name,
      templateSite: 'Lorain County Simulcast', defaultAliasName: realDirectory.system_name,
      importCount: realDirectory.talkgroups.length,
      probeOverride: { identity: { wacn: realDirectory.wacn, system: realDirectory.system,
        rfss: realDirectory.rfss, site: realDirectory.site } },
      directoryResult: { state: 'matched', match: { system_name: realDirectory.system_name,
        site_name: 'Lorain County Simulcast', url: realDirectory.source_url,
        channels: realDirectory.frequencies_hz.map((frequency_hz) => ({ frequency_hz, primary_control: true })) } } };
    await install(page, state);
    await begin(page);
    await review(page);
    await expect(wizard(page).getByLabel('New Alias List name', { exact: true })).toHaveValue(realDirectory.system_name);
    await wizard(page).getByLabel('New Alias List name', { exact: true }).scrollIntoViewIfNeeded();
    const capture = async (stage) => {
      const fits = await wizard(page).evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        const actions = element.querySelector('.spectrum-discovery-actions').getBoundingClientRect();
        return element.scrollWidth <= element.clientWidth + 1 && bounds.left >= 0 && bounds.right <= innerWidth &&
          actions.bottom <= innerHeight;
      });
      expect(fits).toBe(true);
      await expectStepWordsFit(wizard(page));
      const name = `real-discovery-${stage}-${theme}-${width}.png`;
      await wizard(page).screenshot({ animations: 'disabled', path: process.env.VCE_DISCOVERY_SCREENSHOTS ?
        resolve(process.env.VCE_DISCOVERY_SCREENSHOTS, name) : testInfo.outputPath(name) });
    };
    await capture('review');
    await expect(importTalkgroups(page)).toBeChecked();
    await expect(wizard(page).locator('.spectrum-discovery-review dl dd').first()).toHaveText(realDirectory.system_name);
    await expect(wizard(page).getByRole('button', { name: 'Back', exact: true })).toBeVisible();
    await capture('import-choice');
    await addDiscovery(page, importYes);
    await expect(wizard(page)).toContainText(`${realDirectory.talkgroups.length} added, 0 updated, 0 unchanged.`);
    await capture('completion');
    expect(state.requests.find(({ path }) => path.endsWith('/save')).body)
      .toMatchObject({ system: realDirectory.system_name, new_alias_list_name: realDirectory.system_name });
    expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(1);
  });
}

async function disclose(page, label) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  await wizard(page).locator('summary').filter({ hasText: new RegExp(`^${escaped}$`) }).click();
}

for (const [theme, viewport] of [['light', { width: 1365, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`signed-in spectrum discovery offers a missing region directly in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    const state = { theme, directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } };
    await install(page, state);
    await page.evaluate(() => window.openDiscovery());
    await expect(wizard(page).getByLabel('RadioReference country')).toBeVisible();
    await expect(wizard(page)).toContainText('Choose a country to look up nearby systems.');
    await wizard(page).getByLabel('RadioReference country').selectOption('1');
    await expect(wizard(page).getByLabel('RadioReference state or province')).toBeEnabled();
    await expect(wizard(page).getByLabel('RadioReference state or province')).toHaveValue('');
    await wizard(page).getByLabel('RadioReference state or province').selectOption('42');
    await wizard(page).getByLabel('RadioReference state or province').scrollIntoViewIfNeeded();
    await wizard(page).screenshot({ path: testInfo.outputPath('region-selection.png') });
    await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
    await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
    expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
      request.method === 'POST').body.radioreference_state_id).toBe(42);
  });
}

test('starting a signal check waits for the saved RadioReference region to load', async ({ page }) => {
  await page.clock.install();
  const state = { delayDirectoryConfiguration: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect.poll(() => typeof state.releaseDirectory).toBe('function');
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await page.clock.fastForward(6000);
  expect(state.requests.some((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST')).toBe(false);
  state.releaseDirectory();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST').body.radioreference_state_id).toBe(39);
});

test('discovery lookup regions wait beyond five seconds and preserve the saved state at start', async ({ page }) => {
  await page.clock.install();
  const state = { delayDirectoryStates: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect.poll(() => typeof state.releaseDirectoryStates).toBe('function');
  await disclose(page, 'RadioReference names (optional)');
  await expect(wizard(page)).toContainText('Loading states and provinces…');
  await page.clock.fastForward(6000);
  await expect(wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true })).toBeHidden();
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  expect(state.requests.some(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')).toBe(false);
  state.releaseDirectoryStates();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

for (const [theme, viewport] of [['light', { width: 1365, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`discovery lookup region failure can retry in ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    const state = { theme, failDirectoryStatesCount: 1,
      directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } };
    await install(page, state);
    await page.evaluate(() => window.openDiscovery());
    await expect(wizard(page).getByLabel('RadioReference country')).toBeVisible();
    await wizard(page).getByLabel('RadioReference country').selectOption('1');
    await expect(wizard(page)).toContainText(
      'States and provinces could not be loaded. Retry, or continue without frequency matching.');
    const retry = wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true });
    await expect(retry).toBeVisible();
    await expect(wizard(page).getByLabel('RadioReference state or province')).toBeDisabled();
    await retry.scrollIntoViewIfNeeded();
    await wizard(page).screenshot({ path: testInfo.outputPath('region-load-failure.png') });
    await retry.click();
    const region = wizard(page).getByLabel('RadioReference state or province');
    await expect(region).toBeEnabled();
    await expect(region).toHaveValue('');
    await expect(retry).toBeHidden();
    await region.selectOption('42');
    await region.scrollIntoViewIfNeeded();
    await wizard(page).screenshot({ path: testInfo.outputPath('region-load-recovered.png') });
    await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
    await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
    expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/states')).toHaveLength(2);
    expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
      .body.radioreference_state_id).toBe(42);
  });
}

test('discovery lookup region list failure keeps the saved state in the start payload', async ({ page }) => {
  const state = { failDirectoryStatesCount: 1, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect(wizard(page)).toContainText('Your saved lookup region will be used.');
  await expect(wizard(page).getByLabel('RadioReference state or province')).toHaveValue('39');
  await expect(wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true })).toBeVisible();
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('discovery start waits for a pending region retry', async ({ page }) => {
  await page.clock.install();
  const state = { failDirectoryStatesCount: 1, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect(wizard(page)).toContainText('Your saved lookup region will be used.');
  state.delayDirectoryStates = true;
  await wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true }).click();
  await expect.poll(() => typeof state.releaseDirectoryStates).toBe('function');
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await page.clock.fastForward(1000);
  expect(state.requests.some(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')).toBe(false);
  state.releaseDirectoryStates();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/states')).toHaveLength(2);
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('discovery lookup country failure retries account settings and recovers its region choices', async ({ page }) => {
  const state = { failDirectoryCountriesCount: 1,
    directoryConfiguration: { account: { state: 'VALID_PREMIUM' } } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect(wizard(page)).toContainText('Lookup regions could not be loaded. Retry, or continue without choosing a lookup region.');
  await wizard(page).getByRole('button', { name: 'Retry loading regions', exact: true }).click();
  await expect(wizard(page).getByLabel('RadioReference country')).toBeVisible();
  await wizard(page).getByLabel('RadioReference country').selectOption('1');
  const region = wizard(page).getByLabel('RadioReference state or province');
  await expect(region).toBeEnabled();
  await region.selectOption('39');
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference')).toHaveLength(2);
  expect(state.requests.filter(({ path }) => path === '/api/v1/admin/radioreference/countries')).toHaveLength(2);
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .body.radioreference_state_id).toBe(39);
});

test('signed-out spectrum discovery offers connection before checking and hides late directory panels', async ({ page }) => {
  const state = {};
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await disclose(page, 'RadioReference names (optional)');
  await expect(wizard(page).getByRole('button', { name: 'Connect account', exact: true })).toBeEnabled();
  await expect(wizard(page).getByRole('link', { name: 'RadioReference settings', exact: true })).toHaveCount(0);
  await expect(wizard(page).getByLabel('RadioReference state or province')).toHaveCount(0);
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(wizard(page).locator('summary').filter({ hasText: /^RadioReference$/ })).toHaveCount(0);
  expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST').body.radioreference_state_id).toBe(null);
});

test('discovery signs in above the same wizard and immediately uses its region without leaving the setup choices', async ({ page }) => {
  const state = {};
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  const parent = wizard(page);
  const original = await parent.elementHandle();
  state.detectedProtocolId = 'dmr';
  await disclose(page, 'RadioReference names (optional)');
  await parent.getByRole('button', { name: 'Connect account', exact: true }).click();
  const connection = page.getByRole('dialog', { name: 'Connect RadioReference', exact: true });
  await expect(connection).toBeVisible();
  expect(await parent.evaluate(element => element.closest('.modal-backdrop').inert)).toBe(true);
  await connection.getByRole('textbox', { name: 'Username', exact: true }).fill('fixture-user');
  await connection.getByLabel('Password', { exact: true }).fill('synthetic-password');
  await connection.getByRole('button', { name: 'Connect account', exact: true }).click();
  await expect(connection).toHaveCount(0);
  expect(await parent.evaluate((element, previous) => element === previous, original)).toBe(true);
  await expect(parent).toContainText('Connected to RadioReference.');
  await expect(parent.getByRole('link', { name: 'Set up manually', exact: true })).toBeVisible();
  await expect(parent.getByLabel('RadioReference state or province')).toHaveValue('39');
  await expect(parent.getByLabel('RadioReference state or province')).toBeFocused();
  await parent.getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await expect(parent.getByRole('heading', { name: /details found$/, exact: false })).toBeVisible();
  expect(state.requests.find(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST').body)
    .toMatchObject({ protocol_id: 'auto', radioreference_state_id: 39 });
  expect(state.requests.filter(({ path, method }) => path === '/api/v1/admin/radioreference/session' && method === 'PUT')).toHaveLength(1);
});

test('discovery discovers an empty talkgroup import only after saving and completes successfully', async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), importCount: 0 };
  await install(page, state);
  await begin(page);
  await review(page);
  await expect(importTalkgroups(page)).toBeChecked();
  expect(state.requests.filter(({ path }) => path.endsWith('/save') || path.endsWith('/aliases/import'))).toHaveLength(0);
  await addDiscovery(page, importYes);
  await expect(wizard(page).locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
  await expect(wizard(page)).toContainText('No talkgroups available');
  await expect(wizard(page).getByRole('button', { name: 'Retry talkgroup imports', exact: true })).toHaveCount(0);
  await expect(wizard(page).getByRole('button', { name: 'Done', exact: true })).toBeEnabled();
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(1);
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
});

test('discovery start failure restores the saved region control after its pending initial load', async ({ page }) => {
  const state = { delayDirectoryStates: true, failCreateOnce: true,
    directoryConfiguration: { account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect.poll(() => typeof state.releaseDirectoryStates).toBe('function');
  await disclose(page, 'RadioReference names (optional)');
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  state.releaseDirectoryStates();
  await expect(wizard(page)).toContainText('Could not start the signal check. Try again.');
  const region = wizard(page).getByLabel('RadioReference state or province');
  await expect(region).toBeEnabled();
  await expect(region).toHaveValue('39');
  await wizard(page).getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  expect(state.requests.filter(({ path, method }) => path === '/api/v1/admin/spectrum-discovery' && method === 'POST')
    .map(({ body }) => body.radioreference_state_id)).toEqual([39, 39]);
});

test('manually expanded discovery lookup settings remain open after delayed saved-region configuration', async ({ page }) => {
  const state = { delayDirectoryConfiguration: true, directoryConfiguration: {
    account: { state: 'VALID_PREMIUM' }, country_id: 1, state_id: 39 } };
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  await expect.poll(() => typeof state.releaseDirectory).toBe('function');
  const lookup = wizard(page).locator('details').filter({ has: page.locator('summary')
    .filter({ hasText: /^RadioReference names \(optional\)$/ }) });
  await disclose(page, 'RadioReference names (optional)');
  const lookupNode = await lookup.elementHandle();
  await expect(lookup).toHaveAttribute('open', '');
  await expect(lookup).toContainText('Loading RadioReference settings…');
  state.releaseDirectory();
  await expect(lookup.getByLabel('RadioReference state or province')).toBeEnabled();
  await expect(lookup.getByLabel('RadioReference state or province')).toHaveValue('39');
  await expect(lookup).toHaveAttribute('open', '');
  expect(await lookup.evaluate((element, previous) => element === previous, lookupNode)).toBe(true);
});

for (const [theme, viewport] of [['light', { width: 1280, height: 900 }],
  ['dark', { width: 390, height: 844 }]]) {
  test(`discovery pending directory stays expanded through polls before its matched system becomes the heading in ${theme}`,
    async ({ page }, testInfo) => {
      await page.clock.install();
      await page.setViewportSize(viewport);
      const state = { theme, phase: 'identifying', directoryResult: { state: 'pending', message: 'Raw directory stage 0' } };
      await install(page, state);
      await begin(page);
      const directory = wizard(page).locator('details').filter({ has: page.locator('summary')
        .filter({ hasText: /^RadioReference$/ }) });
      await disclose(page, 'RadioReference');
      await expect(directory).toHaveAttribute('open', '');
      await directory.locator('summary').focus();
      await expect(directory.locator('p')).toHaveText('Looking up the system name…');
      const directoryNode = await directory.elementHandle();
      const pendingNode = await directory.locator('p').elementHandle();
      const polls = () => state.requests.filter(({ path, method }) =>
        path === '/api/v1/admin/spectrum-discovery/discovery-a' && method === 'GET').length;
      for (let update = 1; update <= 3; update += 1) {
        const before = polls();
        state.directoryResult.message = `Raw directory stage ${update}`;
        await page.clock.fastForward(800);
        await expect.poll(polls).toBeGreaterThan(before);
        await expect(directory).toHaveAttribute('open', '');
        expect(await directory.evaluate((element, previous) => element === previous, directoryNode)).toBe(true);
        expect(await directory.locator('p').evaluate((element, previous) => element === previous, pendingNode)).toBe(true);
        await expect(directory.locator('p')).toHaveText('Looking up the system name…');
      }
      await directory.scrollIntoViewIfNeeded();
      await wizard(page).screenshot({ path: testInfo.outputPath('directory-expanded-loading.png') });
      state.phase = 'ready';
      state.templateSystem = 'County Transit';
      state.templateSite = 'North';
      state.directoryResult = { state: 'matched', provenance: 'Directory-only provenance', match: {
        system_name: 'County Transit', site_name: 'Directory-only North',
        url: 'https://www.radioreference.com/db/sid/123', site_url: 'https://www.radioreference.com/db/site/456',
        channels: [{ logical_channel_number: 3, frequency_hz: 851500000, primary_control: true }] } };
      await page.clock.fastForward(800);
      await expect(wizard(page).getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
      await expect(directory).toHaveCount(0);
      const system = wizard(page).locator('.spectrum-discovery-system');
      await expect(system.getByRole('link', { name: 'County Transit', exact: true }))
        .toHaveAttribute('href', 'https://www.radioreference.com/db/sid/123');
      await expect(system.getByRole('link', { name: 'County Transit', exact: true })).toBeFocused();
      await expect(system.getByRole('link')).toHaveCount(1);
      await expect(system).not.toContainText('Directory-only');
      await expect(system).not.toContainText('851.5');
      await expect(system).not.toContainText('Primary control');
      await system.scrollIntoViewIfNeeded();
      await wizard(page).screenshot({ path: testInfo.outputPath('directory-compact-match.png') });
    });
}

test('discovery Alias List follows the System label while Channel name and custom alias edits remain independent', async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), defaultAliasName: 'P25 BEE00-348' };
  await install(page, state);
  await begin(page);
  await review(page);
  const alias = wizard(page).getByLabel('New Alias List name', { exact: true });
  await expect(alias).toHaveAttribute('maxlength', '128');
  await expect(alias).toHaveValue(ohioSystem);
  await wizard(page).getByLabel('Channel name', { exact: true }).fill('My control channel');
  await expect(alias).toHaveValue(ohioSystem);
  await disclose(page, 'Additional labels (optional)');
  const system = wizard(page).getByLabel('System', { exact: true });
  await system.fill(`${'A'.repeat(127)}📻 regional`);
  await expect(alias).toHaveValue('A'.repeat(127));
  await system.fill(`${'A'.repeat(126)}📻 regional`);
  await expect(alias).toHaveValue(`${'A'.repeat(126)}📻`);
  await alias.fill('My listening list');
  await system.fill('Another system label');
  await expect(alias).toHaveValue('My listening list');
});

test('resolved discovery names populate Review before its Alias List input receives focus', async ({ page }) => {
  await page.clock.install();
  const state = { directoryResult: { state: 'pending' }, templateName: 'Decoded control',
    templateSystem: 'Decoded network', defaultAliasName: 'DMR 851.012500' };
  await install(page, state);
  await begin(page, 'dmr');
  await expect(wizard(page).getByRole('button', { name: 'Review channel', exact: true })).toBeDisabled();
  await expect(wizard(page).getByLabel('New Alias List name', { exact: true })).toHaveCount(0);
  state.templateSystem = ohioSystem;
  state.defaultAliasName = ohioSystem;
  state.directoryResult = ohioDiscoveryFixture('light').directoryResult;
  await page.clock.fastForward(800);
  await review(page);
  const alias = wizard(page).getByLabel('New Alias List name', { exact: true });
  await expect(alias).toHaveValue(ohioSystem);
  const original = await alias.elementHandle();
  await alias.focus();
  await page.clock.fastForward(11000);
  expect(await alias.evaluate((element, previous) => element === previous, original)).toBe(true);
  await expect(alias).toBeFocused();
  await disclose(page, 'Additional labels (optional)');
  await expect(wizard(page).getByLabel('System', { exact: true })).toHaveValue(ohioSystem);
});

test('review heartbeat preserves edited System, Alias List, channel name and channel maps after lookup', async ({ page }) => {
  await page.clock.install();
  const state = { directoryResult: { state: 'pending' }, templateSystem: 'Decoded network' };
  await install(page, state);
  await begin(page, 'dmr');
  state.templateSystem = ohioSystem;
  state.directoryResult = ohioDiscoveryFixture('light').directoryResult;
  await page.clock.fastForward(800);
  await review(page);
  await disclose(page, 'Additional labels (optional)');
  const system = wizard(page).getByLabel('System', { exact: true });
  const alias = wizard(page).getByLabel('New Alias List name', { exact: true });
  const name = wizard(page).getByLabel('Channel name', { exact: true });
  await system.fill('My transit system');
  await expect(alias).toHaveValue('My transit system');
  await alias.fill('My transit listeners');
  await name.fill('My transit control');
  await importTalkgroups(page).uncheck();
  await wizard(page).getByRole('button', { name: 'Add mapping', exact: true }).click();
  const manual = wizard(page).locator('.channel-map-row').nth(1);
  await manual.locator('input').nth(0).fill('2');
  await manual.locator('input').nth(1).fill('851.25');
  const original = await name.elementHandle();
  await name.focus();
  await page.clock.fastForward(11000);
  expect(await name.evaluate((element, previous) => element === previous, original)).toBe(true);
  await expect(name).toBeFocused();
  await expect(system).toHaveValue('My transit system');
  await expect(alias).toHaveValue('My transit listeners');
  await expect(importTalkgroups(page)).not.toBeChecked();
  await expect(manual.locator('input').nth(1)).toHaveValue('851.25');
  await addDiscovery(page);
  const saved = state.requests.find(request => request.path.endsWith('/save')).body;
  expect(saved.system).toBe('My transit system');
  expect(saved.name).toBe('My transit control');
  expect(saved.new_alias_list_name).toBe('My transit listeners');
  expect(saved.frequency_map).toHaveLength(2);
});

test('Spectrum directory polls preserve focused signal details before Review becomes available', async ({ page }, testInfo) => {
  await page.clock.install();
  const state = { templateName: 'Decoded control', templateSystem: 'Decoded network',
    directoryResult: { state: 'pending' } };
  await install(page, state);
  await begin(page, 'dmr');
  const parent = wizard(page);
  await expect(parent.getByRole('button', { name: 'Review channel', exact: true })).toBeDisabled();
  const button = parent.getByRole('button', { name: 'Details', exact: true });
  await button.click();
  const child = signalDetails(page);
  const childNode = await child.elementHandle();
  const back = child.getByRole('button', { name: 'Back to channel', exact: true });
  await back.focus();
  const polls = () => state.requests.filter(request => request.path.endsWith('/discovery-a') && request.method === 'GET').length;
  const before = polls();
  await page.clock.fastForward(1600);
  await expect.poll(polls).toBeGreaterThan(before);
  await expect(back).toBeFocused();
  expect(await child.evaluate((element, previous) => element === previous, childNode)).toBe(true);
  await child.screenshot({ path: testInfo.outputPath('wizard-details-directory-pending.png') });
  state.templateSystem = ohioSystem;
  state.templateSite = ohioSite;
  state.directoryResult = ohioDiscoveryFixture('light').directoryResult;
  await page.clock.fastForward(800);
  await expect(child.getByRole('group', { name: 'Channel', exact: true })).toContainText(ohioSystem);
  await expect(back).toBeFocused();
  expect(await child.evaluate((element, previous) => element === previous, childNode)).toBe(true);
  await child.screenshot({ path: testInfo.outputPath('wizard-details-directory-matched.png') });
  await back.click();
  await expect(child).toHaveCount(0);
  await expect(button).toBeFocused();
  await review(page);
  await expect(parent.getByRole('link', { name: ohioSystem, exact: true })).toHaveCount(1);
  expect(state.requests.filter(request => request.path.endsWith('/save'))).toHaveLength(0);
});

for (const protocolId of ['dmr', 'nxdn']) {
  test(`click spectrum checks ${protocolId.toUpperCase()} identity and reviews detected trunked channels`, async ({ page }) => {
    const theme = protocolId === 'dmr' ? 'light' : 'dark';
    await page.setViewportSize({ width: protocolId === 'dmr' ? 1280 : 390, height: 900 });
    const state = { theme, phase: 'identifying', directoryResult: { state: 'unavailable' } };
    await install(page, state);
    await begin(page, protocolId);
    await expect(wizard(page).getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
    await expect(wizard(page).getByRole('button', { name: 'Review channel', exact: true })).toHaveCount(0);
    state.phase = 'ready';
    state.directoryResult = { state: 'matched', match: { system_name: 'County Transit', site_name: 'North',
      url: 'https://www.radioreference.com/db/sid/123',
      channels: [{ frequency_hz: 851012500, logical_channel_number: 1, primary_control: true }] } };
    await expect(wizard(page).getByRole('button', { name: 'Review channel', exact: true })).toBeVisible();
    await review(page);
    await expect(wizard(page)).toContainText('Unmapped channels can be followed only when the system broadcasts their frequencies.');
    await expect(wizard(page).locator('.channel-map-row input').nth(1)).toHaveValue(/^851\.01250*$/);
    await expect(wizard(page).getByRole('link', { name: 'County Transit', exact: true })).toHaveCount(1);
    await expect(wizard(page).locator('summary').filter({ hasText: /^RadioReference$/ })).toHaveCount(0);
    await expect(wizard(page)).not.toContainText('Primary control');
    await wizard(page).getByText('Unmapped channels can be followed only when the system broadcasts their frequencies.',
      { exact: false }).scrollIntoViewIfNeeded();
    const boundedMap = await wizard(page).evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      return element.scrollWidth <= element.clientWidth + 1 &&
        [...element.querySelectorAll('.channel-map-row')].every((row) => {
          const rect = row.getBoundingClientRect();
          return rect.left >= bounds.left && rect.right <= bounds.right;
        });
    });
    expect(boundedMap).toBe(true);
    await expect(wizard(page)).toHaveScreenshot(`spectrum-discovery-${protocolId}-${theme}.png`);
    await addDiscovery(page);
    const saved = state.requests.find((request) => request.path.endsWith('/save'));
    expect(saved.body.settings.channel_mode).toBe('TRUNKED');
    expect(saved.body.frequency_map).toEqual([{ number: 1, downlink_hz: 851012500, uplink_hz: 0 }]);
    expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
      request.method === 'POST').body.protocol_id).toBe('auto');
  });
}

for (const [theme, width] of [
  ['light', 1280], ['light', 390], ['light', 320], ['dark', 1280], ['dark', 390], ['dark', 320]
]) {
  test(`Spectrum respects a saved center lock in ${theme} at ${width}px`, async ({ page }) => {
    const state = { locked: true, theme, liveSpectrum: true };
    await page.setViewportSize({ width, height: 900 });
    await install(page, state);
    const center = page.locator('.spectrum-browse-center .tuners-center-frequency');
    const digit = page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true });
    await expect(page.locator('.spectrum-browse-toolbar').getByRole('checkbox', { name: 'Lock center' }))
      .toHaveCount(0);
    await expect(center).toHaveAttribute('aria-disabled', 'true');
    await expect(digit).toBeDisabled();
    await center.focus();
    await center.locator('.tuners-frequency-digit').first().hover();
    await page.keyboard.type('075');
    await page.keyboard.press('Enter');
    await expect(center).toContainText('0851.01250MHz');
    const plot = page.locator('.spectrum-browse-panel .tuner-spectrum-canvas').first();
    await expect(page.locator('.spectrum-browse-panel .channel-diagnostic-overlay').first()).toBeHidden();
    const bounds = await plot.boundingBox();
    await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width / 2 + 30, bounds.y + bounds.height / 2, { steps: 3 });
    await page.mouse.up();
    await expect(center).toContainText('0851.01250MHz');
    expect(state.requests.filter((request) => request.path.includes('/settings/'))).toEqual([]);
    expect(state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value).toBe(true);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
      document.documentElement.clientWidth)).toBe(true);
  });

  test(`Spectrum tunes an unlocked receiver with its lease in ${theme} at ${width}px`, async ({ page }) => {
    const state = { theme };
    await page.setViewportSize({ width, height: 900 });
    await install(page, state);
    const center = page.locator('.spectrum-browse-center .tuners-center-frequency');
    const digit = page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true });
    await expect(page.locator('.spectrum-browse-toolbar').getByRole('checkbox', { name: 'Lock center' }))
      .toHaveCount(0);
    await expect(center).toHaveAttribute('aria-disabled', 'false');
    await expect(digit).toBeEnabled();
    await digit.click();
    await expect(page.locator('.spectrum-browse-center')).toContainText('0751.01250MHz');
    expect(state.requests.find((request) => request.path.endsWith('/settings/frequency_mhz')).body)
      .toEqual({ value: 751.0125, lease_id: 'browse-a' });
    expect(state.requests.filter((request) => request.path.endsWith('/settings/center_frequency_locked')))
      .toEqual([]);
    expect(state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value).toBe(false);
    await expect(center).toHaveAttribute('aria-disabled', 'false');
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
      document.documentElement.clientWidth)).toBe(true);
  });
}

test('active monitoring keeps Spectrum center tuning disabled without changing its saved lock', async ({ page }) => {
  const state = { locked: false, running: true, browseChannelCount: 1 };
  await install(page, state);
  await expect(page.locator('.spectrum-browse-toolbar').getByRole('checkbox', { name: 'Lock center' }))
    .toHaveCount(0);
  await expect(page.locator('.spectrum-browse-center .tuners-center-frequency'))
    .toHaveAttribute('aria-disabled', 'true');
  await expect(page.getByRole('button', { name: 'Decrease 100 MHz place', includeHidden: true })).toBeDisabled();
  expect(state.requests.filter((request) => request.path.includes('/settings/'))).toEqual([]);
  expect(state.tuner.settings.find((setting) => setting.id === 'center_frequency_locked').value).toBe(false);
});

for (const width of [1280, 1440]) {
  test(`desktop Spectrum keeps its fixed panes while the visible audio player resizes at ${width}px`,
    async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await install(page, { liveSpectrum: true, listening: true });
      const dock = page.locator('#audio-dock');
      const handle = dock.locator('#audio-dock-size-handle:visible, #audio-dock-slim-expand:visible');
      await expect(dock).toBeVisible();
      await expect(page.locator('.spectrum-browse-panel .channel-diagnostic-overlay').first()).toBeHidden();
      const geometry = () => page.evaluate(() => {
        const selectors = ['.spectrum-browse-workspace', '.spectrum-browse-panel', '.tuner-spectrum-plot',
          '.tuner-spectrum-waterfall', '.spectrum-browse-control-rail'];
        return selectors.map(selector => {
          const bounds = document.querySelector(selector).getBoundingClientRect();
          return [Math.round(bounds.x), Math.round(bounds.y), Math.round(bounds.width), Math.round(bounds.height)];
        });
      });
      let baseline;
      const heights = [];
      for (const size of ['collapsed', 'minimal', 'full', 'collapsed']) {
        await handle.focus();
        await handle.press(size === 'full' ? 'End' : 'Home');
        if (size === 'minimal') await handle.press('ArrowUp');
        await expect(dock).toHaveAttribute('data-state', size);
        await expect.poll(() => page.evaluate(() => {
          const measured = parseFloat(getComputedStyle(document.documentElement)
            .getPropertyValue('--audio-dock-height'));
          return Math.abs(measured - document.querySelector('#audio-dock').getBoundingClientRect().height);
        })).toBeLessThanOrEqual(1);
        await expect(page.locator('.content')).toHaveCSS('overflow', 'visible');
        // An ungenerated pseudo-element can report display:block without
        // creating a box. Desktop must not generate the mobile dock spacer.
        expect(await page.locator('.content').evaluate(content => getComputedStyle(content, '::after').content))
          .toBe('none');
        if (!baseline) {
          baseline = await geometry();
          expect(baseline[1][3]).toBeGreaterThan(500);
        }
        await expect.poll(geometry).toEqual(baseline);
        heights.push(await dock.evaluate(element => element.getBoundingClientRect().height));
      }
      expect(heights[1]).toBeGreaterThan(heights[0]);
      expect(heights[2]).toBeGreaterThan(heights[1]);
      expect(heights[3]).toBe(heights[0]);
    });
}

test('managed Spectrum keeps organized toolbar rows and updates one persistent frequency rail', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const state = { liveSpectrum: true };
  await install(page, state);

  const heading = page.getByRole('heading', { name: 'Browse Spectrum', exact: true });
  const header = page.locator('.page-header').filter({ has: heading });
  const toolbar = page.locator('.spectrum-browse-toolbar');
  const panel = page.locator('.spectrum-browse-panel');
  const rail = page.locator('.spectrum-browse-control-rail');
  await expect(toolbar.getByRole('checkbox', { name: 'Lock center', exact: true })).toHaveCount(0);
  await expect(toolbar.locator('.spectrum-browse-field-label')).toHaveText('Tuner');
  await expect(toolbar.locator('.tuners-center-lock-field')).toHaveCount(0);
  await expect(page.getByText('Keep tuner here', { exact: true })).toHaveCount(0);
  await expect(header.getByRole('button', { name: 'Find P25 channels', exact: true })).toHaveCount(0);
  await expect(toolbar.getByRole('button', { name: 'Find Trunked Systems', exact: true })).toHaveCount(0);
  await expect(rail).toHaveCount(1);
  await expect(rail.getByText('Select a signal', { exact: true })).toBeVisible();
  await expect(toolbar.locator('.spectrum-browse-message')).toHaveText('Drag to tune · Zoom to pan');
  await expect(rail).toContainText(
    'Click a signal in the spectrum or waterfall to see its frequency and available actions.');
  const toolbarRows = await toolbar.evaluate((element) => {
    const centers = [...element.children].filter((child) => !child.hidden).map((child) => {
      const bounds = child.getBoundingClientRect();
      return bounds.top + bounds.height / 2;
    });
    return { centerSpread: Math.max(...centers) - Math.min(...centers) };
  });
  expect(toolbarRows.centerSpread).toBeLessThanOrEqual(1);
  await page.evaluate(() => {
    window.originalSpectrumFrequencyRail = document.querySelector('.spectrum-browse-control-rail');
  });

  const desktop = await page.evaluate(() => {
    const spectrum = document.querySelector('.spectrum-browse-panel').getBoundingClientRect();
    const actions = document.querySelector('.spectrum-browse-control-rail').getBoundingClientRect();
    return { spectrum: { x: spectrum.x, right: spectrum.right, y: spectrum.y },
      actions: { x: actions.x, y: actions.y } };
  });
  expect(desktop.actions.x).toBeGreaterThanOrEqual(desktop.spectrum.right);
  expect(Math.abs(desktop.actions.y - desktop.spectrum.y)).toBeLessThanOrEqual(1);

  await expect(panel.locator('.channel-diagnostic-overlay').first()).toBeHidden();
  await panel.getByRole('img', { name: 'Tuner frequency spectrum', exact: true })
    .click({ position: { x: 240, y: 80 } });
  await expect(rail.getByText('Selected frequency', { exact: true })).toBeVisible();
  await expect(rail.locator('.tuner-frequency-action-frequency')).toHaveText(/^\d+\.\d{6} MHz$/);
  await expect(rail.getByRole('button', { name: 'Add channel or system', exact: true })).toBeEnabled();
  expect(await rail.evaluate((element) => element === window.originalSpectrumFrequencyRail)).toBe(true);
  await expect(page.locator('.tuner-frequency-popover')).toHaveCount(0);
  await expect(rail.getByText('What can I do here?', { exact: true })).toHaveCount(0);
  await expect(rail.locator('.tuner-frequency-action-tip')).toHaveCount(0);
  expect(state.requests.filter((request) => request.path.endsWith('/eligibility'))).toHaveLength(1);

  await page.setViewportSize({ width: 900, height: 900 });
  await expect.poll(() => page.evaluate(() => {
    const spectrum = document.querySelector('.spectrum-browse-panel').getBoundingClientRect();
    const actions = document.querySelector('.spectrum-browse-control-rail').getBoundingClientRect();
    const toolbar = document.querySelector('.spectrum-browse-toolbar');
    const bounds = toolbar.getBoundingClientRect();
    const tuner = toolbar.querySelector('.spectrum-browse-tuner').getBoundingClientRect();
    const frequency = toolbar.querySelector('.spectrum-browse-center').getBoundingClientRect();
    const commands = toolbar.querySelector('.spectrum-browse-command-cluster').getBoundingClientRect();
    const state = toolbar.querySelector('.spectrum-browse-state').getBoundingClientRect();
    const actionsRow = toolbar.querySelector('.spectrum-browse-actions').getBoundingClientRect();
    const style = getComputedStyle(toolbar);
    const gap = parseFloat(style.rowGap);
    const inset = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom) +
      parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth);
    const center = (rect) => rect.top + rect.height / 2;
    const controlsBounded = [...toolbar.querySelectorAll('button, select, summary')]
      .filter((control) => control.getClientRects().length &&
        getComputedStyle(control).visibility !== 'hidden').every((control) => {
        const rect = control.getBoundingClientRect();
        return rect.left >= bounds.left - 1 && rect.right <= bounds.right + 1 &&
          rect.top >= bounds.top - 1 && rect.bottom <= bounds.bottom + 1;
      });
    return { aligned: Math.abs(actions.x - spectrum.x) <= 1,
      railStacked: actions.y >= spectrum.bottom,
      tunerAndFrequencyAligned: Math.abs(center(tuner) - center(frequency)) <= 1,
      commandsOnSecondRow: Math.abs(commands.top - Math.max(tuner.bottom, frequency.bottom) - gap) <= 1,
      commandsAligned: Math.abs(center(state) - center(actionsRow)) <= 1,
      exactlyTwoToolbarRows: Math.abs(bounds.height - Math.max(tuner.height, frequency.height) -
        commands.height - gap - inset) <= 1,
      controlsBounded };
  })).toEqual({ aligned: true, railStacked: true, tunerAndFrequencyAligned: true,
    commandsOnSecondRow: true, commandsAligned: true, exactlyTwoToolbarRows: true, controlsBounded: true });
  await panel.getByRole('img', { name: 'Tuner frequency spectrum', exact: true })
    .click({ position: { x: 180, y: 80 } });
  await expect.poll(() => rail.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return bounds.top >= 0 && bounds.top < window.innerHeight;
  })).toBe(true);
});

test('bands, FFT, waterfall, legend, and measurements render as one continuous instrument', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await install(page, { liveSpectrum: true });
  const geometry = await page.locator('.spectrum-browse-instrument').evaluate((instrument) => {
    const visual = instrument.querySelector('.tuner-spectrum-visual-window');
    const fft = visual.querySelector('.tuner-spectrum-fft');
    const plot = fft.querySelector('.tuner-spectrum-plot');
    const bands = fft.querySelector('.tuner-spectrum-band-rail');
    const waterfall = visual.querySelector('.tuner-spectrum-waterfall');
    const statusRail = instrument.querySelector('.spectrum-browse-status-rail');
    const legend = instrument.querySelector('.tuner-spectrum-display-controls');
    const measurements = instrument.querySelector('.tuner-spectrum-measurement-panel');
    const bounds = (element) => {
      const rect = element.getBoundingClientRect();
      return { top: rect.top, right: rect.right, bottom: rect.bottom, left: rect.left };
    };
    return {
      instrument: bounds(instrument), visual: bounds(visual), fft: bounds(fft), plot: bounds(plot),
      bands: bounds(bands), waterfall: bounds(waterfall), statusRail: bounds(statusRail), legend: bounds(legend),
      measurements: bounds(measurements),
      legendClientWidth: legend.clientWidth, legendScrollWidth: legend.scrollWidth,
      instrumentBorder: getComputedStyle(instrument).borderTopWidth,
      visualBorder: getComputedStyle(visual).borderTopWidth,
      bandBorderTop: getComputedStyle(bands).borderTopWidth,
      bandBorderBottom: getComputedStyle(bands).borderBottomWidth,
      plotBorderBottom: getComputedStyle(plot).borderBottomWidth,
      statusRailBorderTop: getComputedStyle(statusRail).borderTopWidth,
      legendBorderTop: getComputedStyle(legend).borderTopWidth,
      measurementBorderTop: getComputedStyle(measurements).borderTopWidth
    };
  });
  expect(geometry.bands.top).toBeLessThan(geometry.plot.top);
  expect(Math.abs(geometry.bands.bottom - geometry.plot.top)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.fft.bottom - geometry.waterfall.top)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.fft.left - geometry.waterfall.left)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.fft.right - geometry.waterfall.right)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.visual.bottom - geometry.statusRail.top)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.legend.top - geometry.measurements.top)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.legend.right - geometry.measurements.left)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(geometry.legend.bottom - geometry.measurements.bottom)).toBeLessThanOrEqual(0.5);
  expect(geometry.legendScrollWidth).toBeLessThanOrEqual(geometry.legendClientWidth);
  expect(geometry.instrumentBorder).toBe('1px');
  expect(geometry.visualBorder).toBe('0px');
  expect(geometry.bandBorderTop).toBe('0px');
  expect(geometry.bandBorderBottom).toBe('1px');
  expect(geometry.plotBorderBottom).toBe('1px');
  expect(geometry.statusRailBorderTop).toBe('1px');
  expect(geometry.legendBorderTop).toBe('0px');
  expect(geometry.measurementBorderTop).toBe('0px');

  const reset = page.getByRole('button', { name: 'Reset zoom', exact: true });
  const pause = page.getByRole('button', { name: 'Pause', exact: true });
  const display = page.getByRole('button', { name: 'Display options', exact: true });
  const more = page.locator('.tuner-spectrum-more-measurements > summary');
  for (const control of [reset, pause, display, more]) {
    await expect(control).toHaveClass(/ui-icon-button/);
    await expect(control).toHaveText('');
  }
  await expect(more).toHaveAttribute('aria-label', 'More measurements');
  await more.click();
  const moreReadouts = page.locator('.tuner-spectrum-more-readouts');
  await expect(moreReadouts).toBeVisible();
  const popup = await moreReadouts.boundingBox();
  const instrument = await page.locator('.spectrum-browse-instrument').boundingBox();
  expect(popup.y).toBeGreaterThanOrEqual(instrument.y);
  expect(popup.y + popup.height).toBeLessThanOrEqual(instrument.y + instrument.height);
  await more.click();
  await expect(pause).toBeEnabled();
  await pause.click();
  const resume = page.getByRole('button', { name: 'Resume', exact: true });
  await expect(resume).toHaveClass(/ui-icon-button/);
  await expect(resume).toHaveText('');
  await expect(resume).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.tuner-spectrum-readouts .channel-diagnostic-readout small'))
    .toHaveText(['Visible span', 'Zoom', 'Peak', 'Best SNR']);
});

test('Spectrum display options remain reachable from desktop and mobile controls', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await install(page, { liveSpectrum: true });
  const options = page.locator('.tuner-spectrum-options');
  const panel = page.locator('.tuner-spectrum-options-panel');
  const desktopDisplay = page.getByRole('button', { name: 'Display options', exact: true });
  await expect(desktopDisplay).toBeVisible();
  await desktopDisplay.click();
  await expect(options).toHaveAttribute('open', '');
  await expect(desktopDisplay).toHaveAttribute('aria-expanded', 'true');
  await expect(panel).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(options).not.toHaveAttribute('open', '');
  await expect(desktopDisplay).toBeFocused();

  await page.setViewportSize({ width: 390, height: 844 });
  const more = page.getByRole('button', { name: 'More spectrum actions', exact: true });
  await expect(desktopDisplay).toBeHidden();
  await expect(more).toBeVisible();
  await more.click();
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Reset view', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(panel).toBeHidden();
  await expect(more).toBeFocused();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <=
    document.documentElement.clientWidth)).toBe(true);
});

for (const theme of ['light', 'dark']) for (const width of [1280, 390]) {
  test(`filled Spectrum display options share one retune setting in ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = { theme, listening: true, liveSpectrum: true };
    await install(page, state);
    const trigger = page.getByRole('button', { name: width < 600 ? 'More spectrum actions' : 'Display options', exact: true });
    await trigger.click();
    const panel = page.locator('.tuner-spectrum-options-panel');
    await expect(panel.locator('.settings-card-title')).toHaveText(['Spectrum performance', 'Display', 'FFT', 'Waterfall']);
    const display = panel.locator('.settings-card').filter({ has: page.getByRole('heading', { name: 'Display', exact: true }) });
    const choice = display.getByRole('checkbox', { name: 'Auto range display range on retune', exact: true });
    await expect(panel.getByRole('checkbox', { name: 'Auto range display range on retune', exact: true })).toHaveCount(1);
    await expect(choice).toBeChecked();
    await expect(panel.getByRole('slider', { name: 'Lower display limit' })).toBeDisabled();
    await expect(panel.getByRole('slider', { name: 'Upper display limit' })).toBeDisabled();
    await choice.locator('..').click();
    await expect(choice).not.toBeChecked();
    await expect(panel.getByRole('slider', { name: 'Lower display limit' })).toBeEnabled();
    expect(state.requests.filter((request) => request.path === '/api/v1/me/preferences' && request.method !== 'GET')).toHaveLength(0);
    const styles = await panel.locator('.settings-field-control, .admin-toggle-control').evaluateAll((controls) =>
      controls.filter((control) => !control.hidden).map((control) => {
        const style = getComputedStyle(control);
        return { border: style.borderTopWidth, background: style.backgroundColor };
      }));
    expect(styles).toHaveLength(7);
    expect(styles.every((style) => style.border === '0px' && ['transparent', 'rgba(0, 0, 0, 0)'].includes(style.background))).toBe(true);
    await expect(panel.locator('select').first()).toHaveCSS('border-top-width', '1px');
    const overflow = await panel.evaluate((element) => [element, ...element.querySelectorAll('*')]
      .filter((control) => control.clientWidth > 0 && control.scrollWidth > control.clientWidth + 1)
      .map((control) => ({ className: control.className, tag: control.tagName,
        width: control.clientWidth, scroll: control.scrollWidth })));
    expect(overflow).toEqual([]);
    if (width < 600) {
      const dock = page.locator('#audio-dock');
      await expect(dock).toBeVisible();
      const unobstructed = async (control) => {
        await control.evaluate((element) => element.scrollIntoView({ block: 'center' }));
        const bounds = await control.boundingBox();
        const panelBounds = await panel.boundingBox();
        const dockBounds = await dock.boundingBox();
        expect(bounds.y).toBeGreaterThanOrEqual(panelBounds.y);
        expect(bounds.y + bounds.height).toBeLessThanOrEqual(panelBounds.y + panelBounds.height + 1);
        expect(bounds.y + bounds.height).toBeLessThanOrEqual(dockBounds.y - 7);
      };
      for (const control of await panel.locator('.settings-field-control, .admin-toggle-control').all()) {
        if (await control.isVisible()) await unobstructed(control);
      }
      const fftControl = display.locator('.admin-toggle-control').first();
      const waterfallControl = panel.locator('.settings-card').filter({
        has: page.getByRole('heading', { name: 'Waterfall', exact: true })
      }).locator('.settings-field-control').first();
      await unobstructed(fftControl);
      await expect(fftControl).toHaveScreenshot(`spectrum-options-${theme}-${width}-fft.png`);
      await unobstructed(waterfallControl);
      await expect(waterfallControl).toHaveScreenshot(`spectrum-options-${theme}-${width}-waterfall.png`);
    } else {
      await panel.evaluate((element) => { element.scrollTop = 0; });
      await expect(panel).toHaveScreenshot(`spectrum-options-${theme}-${width}-top.png`);
      await panel.evaluate((element) => { element.scrollTop = element.scrollHeight; });
      await expect(panel).toHaveScreenshot(`spectrum-options-${theme}-${width}-bottom.png`);
    }
    await page.keyboard.press('Escape');
    await expect(panel).toBeHidden();
    await expect(trigger).toBeFocused();
    await page.reload();
    await trigger.click();
    await expect(choice).not.toBeChecked();
    await expect(panel.getByRole('slider', { name: 'Lower display limit' })).toBeEnabled();
  });
}

test('confirmed lease activity updates the existing tuner option and preserves picker focus', async ({ page }) => {
  const state = { browseChannelCount: 1 };
  await page.clock.install();
  await install(page, state);
  const picker = page.locator('.spectrum-browse-tuner select');
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · 1 active · 10 MHz');
  await picker.focus();
  await page.evaluate(() => {
    window.originalSpectrumPicker = document.querySelector('.spectrum-browse-tuner select');
    window.originalSpectrumOption = window.originalSpectrumPicker.selectedOptions[0];
  });
  state.browseChannelCount = 0;
  await page.clock.fastForward(11000);
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · Idle · 10 MHz');
  await expect(picker).toBeFocused();
  state.browseChannelCount = 2;
  await page.clock.fastForward(11000);
  await expect(picker.locator('option:checked')).toHaveText('Test receiver · 2 active · 10 MHz');
  await expect(picker).toBeFocused();
  expect(await picker.evaluate((element) => element === window.originalSpectrumPicker &&
    element.selectedOptions[0] === window.originalSpectrumOption)).toBe(true);
});

test('an unchanged lease renewal preserves a typed center frequency draft and its focused control', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  const center = page.locator('.spectrum-browse-center .tuners-center-frequency');
  await center.focus();
  await page.locator('.spectrum-browse-center .tuners-frequency-digit').first().hover();
  await page.keyboard.type('075');
  await expect(center).toContainText('0751.01250MHz');
  await page.evaluate(() => {
    window.originalSpectrumCenter = document.querySelector('.spectrum-browse-center .tuners-center-frequency');
  });
  await page.clock.fastForward(11000);
  await expect.poll(() => state.requests.some((request) => request.path.endsWith('/browse') &&
    request.body.lease_id === 'browse-a')).toBe(true);
  await expect(center).toBeFocused();
  expect(await center.evaluate((element) => element === window.originalSpectrumCenter)).toBe(true);
  await page.keyboard.type('412345');
  await page.keyboard.press('Enter');
  await expect(center).toContainText('0754.12345MHz');
  expect(state.requests.find((request) => request.path.endsWith('/settings/frequency_mhz')).body)
    .toEqual({ value: 754.12345, lease_id: 'browse-a' });
});

test('idle receiver browsing renews and releases on leaving Spectrum', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  await page.clock.fastForward(11000);
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/browse') &&
    request.body.lease_id === 'browse-a').length).toBeGreaterThan(0);
  await page.evaluate(() => document.querySelector('a[data-view="dashboard"]').click());
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/browse') &&
    request.method === 'DELETE').length).toBeGreaterThan(0);
});

test('a single tuner can retry failed browsing without changing selection', async ({ page }) => {
  const state = { failBrowseOnce: true };
  await install(page, state);
  await expect(page.getByRole('button', { name: 'Retry browsing' })).toBeVisible();
  await page.getByRole('button', { name: 'Retry browsing' }).click();
  await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
  await expect(page.getByRole('button', { name: 'Retry browsing' })).toBeHidden();
  expect(state.requests.filter((request) => request.path.endsWith('/browse') && request.method === 'POST'))
    .toHaveLength(2);
});

test('the shared trunked detection path uses plain language and waits for explicit review', async ({ page }) => {
  const state = {};
  await install(page, state);
  await page.evaluate(() => window.openDiscovery());
  const dialog = wizard(page);
  await expect(dialog.getByRole('heading', { name: 'Add a channel', exact: true })).toBeVisible();
  await expect(dialog.getByRole('radio')).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Detect trunked system', exact: true })).toBeVisible();
  await expect(dialog.getByRole('link', { name: 'Set up manually', exact: true })).toBeVisible();
  await expect(dialog).toContainText('Checks P25 Phase 1, DMR, and NXDN trunked control channels.');
  await expect(dialog).toContainText('Supports AM, NBFM, P25, DMR, and NXDN.');
  await expect(dialog.getByText('Help me choose', { exact: true })).toHaveCount(0);
  expect(await dialog.innerText()).not.toMatch(/C4FM|CQPSK|WACN|RFSS|SysID|NAC/);
  await dialog.getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(dialog.getByText('Ready to add', { exact: true })).toBeVisible();
  await expect(dialog.getByText('The system, site, and signal setting stayed consistent.', { exact: true })).toBeVisible();
  await expect(dialog.getByText('How does this work?', { exact: true })).toHaveCount(0);
  await expect(dialog.locator('details.spectrum-discovery-technical')).toHaveCount(0);
  await expect(dialog.getByRole('table')).toHaveCount(0);
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Review channel', exact: true })).toBeVisible();
  const detailsButton = dialog.getByRole('button', { name: 'Details', exact: true });
  await detailsButton.click();
  const details = signalDetails(page);
  await expect(details.getByRole('heading', { name: 'Signal details · 851.012500 MHz', exact: true })).toBeVisible();
  expect(await dialog.evaluate(element => element.closest('.modal-backdrop').inert)).toBe(true);
  await expect(details.getByRole('table')).toBeVisible();
  await expect(details.getByRole('rowheader', { name: 'CQPSK Selected', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(details).toHaveCount(0);
  await expect(detailsButton).toBeFocused();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  await review(page);
  await expect(dialog.getByLabel('Channel name', { exact: true })).toBeVisible();
  await expect(dialog.getByLabel('System', { exact: true })).toBeHidden();
  await expect(dialog.getByLabel('Site', { exact: true })).toBeHidden();
  await expect(dialog.locator('summary').filter({ hasText: /^Additional labels \(optional\)$/ })).toBeVisible();
  await expect(dialog.getByText('New Alias Lists use your Default listening list.', { exact: true })).toBeVisible();
  await expect(dialog.getByText('What is an Alias List?', { exact: true })).toHaveCount(0);
  expect(await dialog.innerText()).not.toMatch(/WACN|SysID|NAC/);
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toBeVisible();
});

for (const [theme, width] of [['light', 1280], ['dark', 1280], ['light', 390], ['dark', 390]]) {
  test(`Ohio Spectrum channel setup separates summary, details and labels in ${theme} at ${width}px`,
    async ({ page }, testInfo) => {
      await page.clock.install();
      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
      await page.emulateMedia({ colorScheme: theme });
      const state = ohioDiscoveryFixture(theme);
      await install(page, state);
      await begin(page);
      const parent = wizard(page);
      const system = parent.locator('.spectrum-discovery-system');
      const systemNode = await system.elementHandle();
      await expect(system.getByRole('link', { name: ohioSystem, exact: true }))
        .toHaveAttribute('href', 'https://www.radioreference.com/db/sid/123');
      await expect(parent.getByRole('link', { name: ohioSystem, exact: true })).toHaveCount(1);
      await expect(system).toContainText(ohioSite);
      await expect(parent.getByRole('table')).toHaveCount(0);
      await expect(parent.locator('summary').filter({ hasText: /^(Signal details|RadioReference)$/ })).toHaveCount(0);
      await expect(system).not.toContainText('Directory-only');
      await expect(system).not.toContainText('Primary control');
      expect(await parent.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
      await parent.screenshot({ path: testInfo.outputPath('ohio-wizard-ready.png') });

      const detailsButton = parent.getByRole('button', { name: 'Details', exact: true });
      await detailsButton.click();
      const details = signalDetails(page);
      expect(await parent.evaluate(element => element.closest('.modal-backdrop').inert)).toBe(true);
      await expect(details.getByRole('heading', { name: 'Signal details · 851.012500 MHz', exact: true })).toBeVisible();
      for (const label of ['Channel', 'On-air identity', 'Signal health', 'Signal setting comparison'])
        await expect(details.getByRole('group', { name: label, exact: true })).toBeVisible();
      await expect(details).toContainText(ohioSystem);
      await expect(details).toContainText(ohioSite);
      const wacn = details.locator('dd').filter({ hasText: /^0xBEE00 \(781824\)$/ });
      await expect(wacn).toHaveCount(1);
      expect(await wacn.evaluate(element => {
        const range = document.createRange();
        range.selectNodeContents(element);
        return new Set([...range.getClientRects()].map(rect => Math.round(rect.top))).size;
      })).toBe(1);
      expect(await details.evaluate(element => {
        const bounds = element.getBoundingClientRect();
        return bounds.left >= 0 && bounds.right <= innerWidth && element.scrollWidth <= element.clientWidth + 1;
      })).toBe(true);
      await details.screenshot({ path: testInfo.outputPath('ohio-wizard-details.png') });
      await page.keyboard.press('Escape');
      await expect(details).toHaveCount(0);
      await expect(detailsButton).toBeFocused();
      expect(await system.evaluate((element, previous) => element === previous, systemNode)).toBe(true);

      await review(page);
      await expect(parent.getByRole('group', { name: 'Channel', exact: true })).toBeVisible();
      await expect(parent.getByRole('group', { name: 'Alias List', exact: true })).toBeVisible();
      await expect(parent.getByRole('link', { name: ohioSystem, exact: true })).toHaveCount(1);
      await expect(parent.getByRole('table')).toHaveCount(0);
      expect(await parent.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
      await parent.screenshot({ path: testInfo.outputPath('ohio-wizard-review.png') });
      await parent.getByLabel('Channel name', { exact: true }).fill('Cuyahoga control');
      await parent.getByLabel('New Alias List name', { exact: true }).fill('MARCS Dispatch');
      await disclose(page, 'Additional labels (optional)');
      await parent.getByLabel('System', { exact: true }).fill('My MARCS system');
      await parent.getByLabel('Site', { exact: true }).fill('My Cuyahoga site');
      await parent.getByLabel('Site', { exact: true }).scrollIntoViewIfNeeded();
      await parent.screenshot({ path: testInfo.outputPath('ohio-wizard-edited-labels.png') });
      await parent.getByRole('button', { name: 'Details', exact: true }).click();
      await expect(signalDetails(page)).toBeVisible();
      if (theme === 'light' && width === 1280) {
        const back = signalDetails(page).getByRole('button', { name: 'Back to channel', exact: true });
        await back.focus();
        const polls = () => state.requests.filter(request =>
          request.path.endsWith('/discovery-a') && request.method === 'GET').length;
        const before = polls();
        const childNode = await signalDetails(page).elementHandle();
        await page.clock.fastForward(11000);
        await expect.poll(polls).toBeGreaterThan(before);
        expect(await signalDetails(page).evaluate((element, previous) => element === previous, childNode)).toBe(true);
        await expect(back).toBeFocused();
      }
      await page.keyboard.press('Escape');
      await expect(parent.getByRole('button', { name: 'Details', exact: true })).toBeFocused();
      await expect(parent.getByLabel('Channel name', { exact: true })).toHaveValue('Cuyahoga control');
      await expect(parent.getByLabel('System', { exact: true })).toHaveValue('My MARCS system');
      await expect(parent.getByLabel('Site', { exact: true })).toHaveValue('My Cuyahoga site');
      await addDiscovery(page);
      await expect(parent.locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
      expect(state.requests.find(request => request.path.endsWith('/save')).body).toMatchObject({
        name: 'Cuyahoga control', system: 'My MARCS system', site: 'My Cuyahoga site',
        new_alias_list_name: 'MARCS Dispatch', alias_list_id: 0
      });
    });
}

test('automatic trunked detection starts without a chosen protocol and adopts the detected protocol', async ({ page }) => {
  const state = { phase: 'identifying', automaticIdentifying: true };
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.clock.install();
  await install(page, state);
  await begin(page, 'dmr');
  const dialog = wizard(page);
  await expect(dialog.getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeEnabled();
  state.phase = 'ready';
  await page.clock.fastForward(900);
  await expect(dialog.getByRole('heading', { name: 'DMR trunked system details found', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Details', exact: true })).toBeEnabled();
  await review(page);
  await expect(dialog.getByRole('group', { name: 'Channel', exact: true })).toBeVisible();
  expect(state.requests.find(request => request.path === '/api/v1/admin/spectrum-discovery' && request.method === 'POST')
    .body.protocol_id).toBe('auto');
  expect(errors).toEqual([]);
});

test('P25 becoming ready preserves keyboard focus until the user reviews it', async ({ page }) => {
  const state = { phase: 'identifying' };
  await page.clock.install();
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  const cancel = dialog.getByRole('button', { name: 'Cancel', exact: true });
  await expect(cancel).toBeEnabled();
  await cancel.focus();
  state.phase = 'ready';
  await page.clock.fastForward(900);
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(cancel).toBeFocused();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveCount(0);
  await review(page);
  await expect(dialog.getByRole('heading', { name: 'Review channel', exact: true })).toBeFocused();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('P25 polling retains Cancel focus and cancellation releases the probe', async ({ page }, testInfo) => {
  const state = { phase: 'identifying' };
  await page.clock.install();
  await install(page, state);
  await begin(page);
  const cancel = wizard(page).getByRole('button', { name: 'Cancel', exact: true });
  await expect(wizard(page).getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  await expect(wizard(page).getByRole('table')).toHaveCount(0);
  await expect(wizard(page).locator('.ui-feedback-loading')).toHaveText('Reading the signal');
  await expect(wizard(page).getByText('1s elapsed · This can take up to 15s.', { exact: true })).toBeVisible();
  await wizard(page).screenshot({ path: testInfo.outputPath('single-signal-progress.png') });
  const detailsButton = wizard(page).getByRole('button', { name: 'Details', exact: true });
  await detailsButton.click();
  const details = signalDetails(page);
  const detailsNode = await details.elementHandle();
  const table = details.getByRole('table');
  await expect(table.getByRole('columnheader')).toHaveText([
    'Setting', 'Valid', 'Control', 'Rejected', 'Decode score'
  ]);
  await expect(table.locator('tbody tr').nth(0).getByRole('cell')).toHaveText(['18', '12', '4', '81%']);
  await expect(table.locator('tbody tr').nth(1).getByRole('cell')).toHaveText(['52', '41', '1', '99%']);
  await expect(table.getByRole('rowheader', { name: 'C4FM', exact: true })).toBeVisible();
  await expect(table.getByRole('rowheader', { name: 'CQPSK', exact: true })).toBeVisible();
  await expect(wizard(page).getByText(/Message quality is the proportion of messages/)).toHaveCount(0);
  const back = details.getByRole('button', { name: 'Back to channel', exact: true });
  await back.focus();
  state.cqpskMetrics = { valid_messages: 53, valid_control_messages: 42 };
  await page.clock.fastForward(900);
  await expect(table.locator('tbody tr').nth(1).getByRole('cell')).toHaveText(['53', '42', '1', '99%']);
  await expect(back).toBeFocused();
  expect(await details.evaluate((element, previous) => element === previous, detailsNode)).toBe(true);
  await page.keyboard.press('Escape');
  await expect(details).toHaveCount(0);
  await expect(detailsButton).toBeFocused();
  expect(state.requests.filter((request) => request.path.endsWith('/discovery-a') && request.method === 'DELETE'))
    .toHaveLength(0);
  await cancel.focus();
  await page.clock.fastForward(900);
  await expect(cancel).toBeFocused();
  await cancel.click();
  await expect(wizard(page)).toHaveCount(0);
  await expect.poll(() => state.requests.filter((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE').length).toBe(1);
  await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
});

const noTrunkedSignal = 'No supported trunked control channel was found. Try again or set up the channel manually.';

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`manual Spectrum setup opens Channel Manager in a new tab without checking or changing the receiver in ${theme}`,
    async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 });
      const state = { theme, discoveryFrequencyHz: 154920000 };
      await install(page, state);
      await page.evaluate(() => window.openDiscovery());
      const dialog = wizard(page);
      const manual = dialog.getByRole('link', { name: 'Set up manually', exact: true });
      await expect(manual).toHaveAttribute('target', '_blank');
      await expect(manual).toHaveAttribute('rel', /noopener/);
      await expect(manual).toHaveAttribute('href', '/?view=channel-setup&create=1&frequency_hz=154920000');
      expect(await dialog.evaluate(element => {
        const bounds = element.getBoundingClientRect();
        return bounds.left >= 0 && bounds.right <= innerWidth && element.scrollWidth <= element.clientWidth + 1;
      })).toBe(true);
      await dialog.screenshot({ path: testInfo.outputPath(`spectrum-setup-choices-${theme}.png`) });
      const receiverMutations = () => state.requests.filter(request =>
        request.path === '/api/v1/admin/spectrum-discovery' && request.method === 'POST' ||
        request.path.includes('/settings/') || request.path.endsWith('/save') || request.path.endsWith('/start'));
      const before = receiverMutations().length;
      const [manualPage] = await Promise.all([page.waitForEvent('popup'), manual.click()]);
      await expect(manualPage).toHaveURL(/view=channel-setup&create=1&frequency_hz=154920000/);
      await expect(page).toHaveURL(/view=tuner-spectrum/);
      await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
      await expect(dialog).toHaveCount(0);
      expect(receiverMutations()).toHaveLength(before);
      expect(await page.evaluate(() => window.discoveryProbeStates)).toEqual([false]);
      await manualPage.close();
    });
}

for (const protocolId of ['p25-phase1', 'dmr', 'nxdn']) {
  for (const phase of ['inconclusive', 'failed']) {
    test(`${phase} ${protocolId} detection releases the check and returns to the same setup choices`, async ({ page }) => {
      const state = { phase, uniqueSessionIds: true };
      await install(page, state);
      await begin(page, protocolId);
      const dialog = wizard(page);
      await expect(dialog).toContainText(noTrunkedSignal);
      await expect(dialog.getByRole('button', { name: 'Detect trunked system', exact: true })).toBeEnabled();
      await expect(dialog.getByRole('link', { name: 'Set up manually', exact: true }))
        .toHaveAttribute('href', '/?view=channel-setup&create=1&frequency_hz=851012500');
      await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveCount(0);
      await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
      expect(state.requests.filter(request => request.path.endsWith('/discovery-1') && request.method === 'DELETE')).toHaveLength(1);
      expect(state.requests.filter(request => request.path.endsWith('/save') || request.path.endsWith('/start'))).toHaveLength(0);
      state.phase = 'identifying';
      await dialog.getByRole('button', { name: 'Detect trunked system', exact: true }).click();
      await expect(dialog.getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
      await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await expect.poll(() => state.requests.filter(request => request.path.startsWith('/api/v1/admin/spectrum-discovery/discovery-') &&
        request.method === 'DELETE').map(request => request.path.split('/').pop())).toEqual(['discovery-1', 'discovery-2']);
      expect(state.requests.filter(request => request.path === '/api/v1/admin/spectrum-discovery' && request.method === 'POST')
        .map(request => request.body.protocol_id)).toEqual(['auto', 'auto']);
    });
  }
}

test('an inconclusive automatic check waits for successful cancellation before returning to setup', async ({ page }) => {
  const state = { phase: 'inconclusive', deferDeleteOnce: true, failDeleteOnce: true };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect.poll(() => typeof state.releaseDelete).toBe('function');
  await expect(dialog).toHaveAttribute('aria-busy', 'true');
  await expect(dialog.getByRole('link', { name: 'Set up manually', exact: true })).toHaveCount(0);
  state.releaseDelete();
  await expect(dialog).toContainText('The signal check could not be stopped. Try again.');
  await expect(dialog).not.toHaveAttribute('aria-busy', 'true');
  await dialog.getByRole('button', { name: 'Back to setup', exact: true }).click();
  await expect(dialog).toContainText(noTrunkedSignal);
  await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
  expect(state.requests.filter(request => request.path.endsWith('/save') || request.path.endsWith('/start'))).toHaveLength(0);
});

test('recovering P25 probe status restores the scanning footer', async ({ page }) => {
  const state = { phase: 'identifying', failProbeStatusOnce: true };
  await install(page, state);
  await begin(page);
  const dialog = wizard(page);
  await expect(dialog.getByRole('button', { name: 'Retry connection', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Retry connection', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Retry connection', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('heading', { name: 'Checking this radio system…', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
});

test('a setup that expires during review cannot add a channel and can retry signal checking', async ({ page }) => {
  const state = {};
  await page.clock.install();
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Channel name', { exact: true }).fill('County Control');
  state.phase = 'failed';
  state.reason = 'The signal check has expired.';
  await page.clock.fastForward(11000);
  await expect(dialog).toContainText(noTrunkedSignal);
  await expect(dialog.getByRole('button', { name: 'Add and start listening', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('link', { name: 'Set up manually', exact: true })).toBeVisible();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  state.phase = 'ready';
  state.reason = null;
  await dialog.getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveCount(0);
  await review(page);
  await expect(dialog.getByRole('button', { name: 'Add and start listening', exact: true })).toBeEnabled();
  expect(state.requests.filter((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST')).toHaveLength(2);
});

test('a delayed status from a cancelled automatic check cannot replace a new check', async ({ page }) => {
  const state = { phase: 'identifying' };
  await page.clock.install();
  await install(page, state);
  await begin(page);
  state.deferStatusOnce = true;
  await page.clock.fastForward(900);
  await expect.poll(() => typeof state.releaseStatus).toBe('function');
  await wizard(page).getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(wizard(page)).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.discoveryProbeStates)).toEqual([true, false]);
  state.phase = 'ready';
  await begin(page, 'nxdn');
  const dialog = wizard(page);
  await expect(dialog.getByRole('heading', { name: 'NXDN trunked system details found', exact: true })).toBeVisible();
  state.releaseStatus();
  await page.waitForTimeout(100);
  await page.clock.fastForward(100);
  await expect(dialog.getByRole('heading', { name: 'NXDN trunked system details found', exact: true })).toBeVisible();
  await review(page);
  expect(state.requests.filter(request => request.path === '/api/v1/admin/spectrum-discovery' && request.method === 'POST')
    .map(request => request.body.protocol_id)).toEqual(['auto', 'auto']);
  expect(state.requests.filter(request => request.path.endsWith('/save'))).toHaveLength(0);
});

for (const expiredStatusCode of [409, 410]) {
  test(`an expired session response ${expiredStatusCode} requires a fresh check instead of leaving Add available`, async ({ page }) => {
    const state = { expiredStatusCode };
    await page.clock.install();
    await install(page, state);
    await begin(page);
    await review(page);
    await wizard(page).getByRole('button', { name: 'Details', exact: true }).click();
    await expect(signalDetails(page)).toBeVisible();
    state.expiredStatus = true;
    await page.clock.fastForward(11000);
    const dialog = wizard(page);
    await expect(signalDetails(page)).toHaveCount(0);
    await expect(dialog).toContainText(noTrunkedSignal);
    await expect(dialog.getByRole('heading', { name: 'How would you like to add this frequency?', exact: true })).toBeFocused();
    await expect(dialog.getByRole('button', { name: 'Detect trunked system', exact: true })).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Add and start listening', exact: true })).toHaveCount(0);
    state.expiredStatus = false;
    await dialog.getByRole('button', { name: 'Detect trunked system', exact: true }).click();
    await expect(dialog.getByRole('heading', { name: 'P25 details found', exact: true })).toBeVisible();
    expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  });
}

test('a saved channel requiring a receiver restart never offers another Add or start attempt', async ({ page }) => {
  const state = { restartRequired: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await addDiscovery(page);
  await expect(dialog.locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
  await expect(dialog).toContainText('Your channel was saved, but VCE needs to restart');
  await expect(dialog.getByRole('button', { name: 'Try adding again', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Try starting again', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('link', { name: 'Open channels', exact: true })).toBeVisible();
  await expect(dialog.locator('.spectrum-discovery-progress')).toBeHidden();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(dialog).toHaveCount(0);
});

test('real Spectrum rail ends RF checking after a published-save failure and retains its parent browse lease until page cleanup', async ({ page }) => {
  await page.clock.install();
  const state = { ...ohioDiscoveryFixture('light'), realSpectrumOwnership: true, liveSpectrum: true,
    restartRequired: true };
  await install(page, state);
  await beginThroughSpectrumRail(page);
  await review(page);
  await expect(importTalkgroups(page)).toBeChecked();
  await addDiscovery(page, importYes);
  const dialog = wizard(page);
  await expect(dialog).toContainText('Your channel was saved, but VCE needs to restart');
  await expect(dialog.locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
  expect(state.discoveryActive).toBe(false);
  expect(state.ownership.active.lease_id).toBe('owned-browse-2');
  await expect(dialog.getByRole('button', { name: /^(Add and start listening|Try adding again|Try starting again|Retry talkgroup imports)$/ }))
    .toHaveCount(0);
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import') ||
    path === '/api/v1/admin/channels/actions')).toHaveLength(0);
  const renewals = () => state.requests.filter(({ path, method, body }) => path.endsWith('/browse') &&
    method === 'POST' && body.lease_id === 'owned-browse-2').length;
  const before = renewals();
  await page.clock.fastForward(35_000);
  await expect.poll(renewals).toBeGreaterThan(before);
  expect(state.ownership.active.lease_id).toBe('owned-browse-2');
  await expect(dialog).not.toContainText(/refresh this setup|Check your connection|discovery expired/);
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(state.ownership.active.lease_id).toBe('owned-browse-2');
  expect(state.requests.filter(({ path, method, body }) => path.endsWith('/browse') &&
    method === 'DELETE' && body.lease_id === 'owned-browse-2')).toHaveLength(0);
  await page.evaluate(() => document.querySelector('a[data-view="dashboard"]').click());
  await expect.poll(() => state.ownership.active).toBe(null);
  const releases = state.requests.filter(({ path, method, body }) => path.endsWith('/browse') &&
    method === 'DELETE' && body.lease_id === 'owned-browse-2');
  expect(releases).toHaveLength(1);
  expect(releases[0].body).toEqual({ lease_id: 'owned-browse-2' });
  expect(state.tuner.channel_count).toBe(state.ownership.stoppedChannels);
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
});

test('ambiguous aliases require a choice; saved start failure retries without recreating', async ({ page }) => {
  await page.clock.install();
  const state = { ambiguous: true, failStart: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await expect(dialog.getByLabel('Alias List', { exact: true })).toHaveValue('');
  await expect(dialog.getByLabel('Alias List', { exact: true }).locator('option[value="new"]')).toHaveCount(0);
  await addDiscovery(page);
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
  await dialog.getByLabel('Alias List', { exact: true }).selectOption('22');
  await addDiscovery(page);
  await expect(dialog.locator('.modal-header').getByRole('heading', { name: 'Channel added' })).toBeVisible();
  await expect(dialog).toContainText('Tuner capacity is in use.');
  state.expiredStatus = true;
  await page.clock.fastForward(6 * 60_000);
  await dialog.getByRole('button', { name: 'Try starting again' }).click();
  await expect(dialog.locator('.modal-header').getByRole('heading', { name: 'Channel added' })).toBeVisible();
  await expect(dialog.locator('.discovery-completion-status')).toHaveText('Running');
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  expect(state.requests.filter((request) => request.path.endsWith('/start'))).toHaveLength(0);
  expect(state.requests.filter((request) => request.path === '/api/v1/admin/channels/actions')).toHaveLength(1);
  const save = state.requests.find((request) => request.path.endsWith('/save')).body;
  expect(save.alias_list_id).toBe(22);
  expect(save.new_alias_list_name).toBeNull();
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  expect(state.requests.filter((request) => request.path.endsWith('/discovery-a') &&
    request.method === 'DELETE')).toHaveLength(0);
});

test('one P25 identity match offers an explicit existing or new Alias List choice', async ({ page }) => {
  const state = { singleMatch: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await expect(dialog).toContainText('County aliases');
  await expect(dialog.getByRole('combobox', { name: 'Alias List', exact: true })).toHaveValue('21');
  await expect(dialog.getByRole('option', { name: 'Create a new Alias List', exact: true })).toHaveCount(1);
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toBeHidden();
  await addDiscovery(page);
  await expect(dialog.locator('.modal-header').getByRole('heading', { name: 'Channel added' })).toBeVisible();
  expect(state.requests.find((request) => request.path.endsWith('/save')).body.alias_list_id).toBe(21);
});

test('stale save refreshes choices and preserves the draft before an explicit retry', async ({ page }) => {
  const state = { staleOnce: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Channel name', { exact: true }).fill('County Control');
  await dialog.getByLabel('New Alias List name', { exact: true }).fill('County aliases');
  await addDiscovery(page);
  await expect(dialog).toContainText('Your saved choices changed. Check the Alias List and try adding again.');
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('County Control');
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toHaveValue('County aliases');
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  await addDiscovery(page);
  await expect(dialog.locator('.modal-header').getByRole('heading', { name: 'Channel added' })).toBeVisible();
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves.map((request) => request.body.revision)).toEqual([7, 8]);
  expect(saves[1].body.alias_list_id).toBe(0);
  expect(saves[1].body.name).toBe('County Control');
});

test('retrying a failed add preserves names and listening choices without adding twice automatically', async ({ page }) => {
  const state = { failSaveOnce: true };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Channel name', { exact: true }).fill('County Control');
  await dialog.getByLabel('New Alias List name', { exact: true }).fill('County listening');
  await disclose(page, 'Additional labels (optional)');
  await dialog.getByLabel('System', { exact: true }).fill('County Radio');
  await dialog.getByLabel('Site', { exact: true }).fill('North');
  await addDiscovery(page);
  await expect(dialog.getByText('We couldn’t add your channel. Your choices are still here. Try again.', { exact: true }))
    .toBeVisible();
  await expect(dialog.getByText('Could not add this channel. Try again.', { exact: true })).toBeHidden();
  await expect(dialog.getByRole('button', { name: 'Try adding again', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Back', exact: true }).click();
  await review(page);
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('County Control');
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toHaveValue('County listening');
  await expect(dialog.getByLabel('System', { exact: true })).toHaveValue('County Radio');
  await expect(dialog.getByLabel('Site', { exact: true })).toHaveValue('North');
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(1);
  await addDiscovery(page);
  await expect(dialog.locator('.modal-header').getByRole('heading', { name: 'Channel added', exact: true })).toBeVisible();
  await expect(dialog).toContainText('County Control');
  await expect(dialog).toContainText('Test receiver');
  await expect(dialog).toContainText('851.012500 MHz');
  await expect(dialog).toContainText('P25 Phase 1');
  await expect(dialog).toContainText('County listening');
  await expect(dialog.getByText('Open channel', { exact: true })).toBeVisible();
  const saves = state.requests.filter((request) => request.path.endsWith('/save'));
  expect(saves).toHaveLength(2);
  expect(saves[1].body).toEqual(saves[0].body);
  expect(saves[1].body).toMatchObject({ name: 'County Control', system: 'County Radio', site: 'North',
    alias_list_id: 0, new_alias_list_name: 'County listening' });
  expect(state.requests.filter((request) => request.path.endsWith('/start'))).toHaveLength(0);
});

test('a fractional Hertz spectrum selection displays and submits the same integer frequency', async ({ page }) => {
  const state = {};
  await install(page, state);
  await page.evaluate(() => window.discoveryApi.openTunerFrequencyActions({
    tunerId: 'idle-a', targetId: 'target-a', browseLeaseId: 'browse-a',
    frequencyHz: 776715007.9617834, rawFrequencyHz: 776715007.9617834
  }));
  const add = page.getByRole('button', { name: 'Add channel / system', exact: true });
  await expect(add).toBeEnabled();
  await add.click();
  const dialog = wizard(page);
  await expect(dialog.locator('.spectrum-discovery-context strong')).toHaveText('776.715008 MHz');
  await dialog.getByRole('button', { name: 'Detect trunked system', exact: true }).click();
  await review(page);
  await expect(dialog.getByRole('heading', { name: 'Review channel' })).toBeVisible();
  await expect(dialog.locator('.spectrum-discovery-context strong')).toHaveText('776.715008 MHz');
  expect(state.requests.find((request) => request.path === '/api/v1/admin/spectrum-discovery' &&
    request.method === 'POST').body.frequency_hz).toBe(776715008);
  await dialog.getByRole('button', { name: 'Close Add a channel', exact: true }).click();
  expect(state.requests.filter((request) => request.path.endsWith('/save'))).toHaveLength(0);
});

test('configured or learned channel frequency blocks Add using receiver eligibility', async ({ page }) => {
  const state = { known: true };
  await install(page, state);
  await page.evaluate(() => window.discoveryApi.openTunerFrequencyActions({
    tunerId: 'idle-a', targetId: 'target-a', frequencyHz: 851012500
  }));
  const add = page.getByRole('button', { name: 'Add channel / system', exact: true });
  await expect(add).toBeDisabled();
  const popover = page.locator('.tuner-frequency-popover');
  await expect(popover).toContainText('This frequency belongs to County Control.');
  await expect(popover.getByRole('link', { name: 'County Control', exact: true }))
    .toHaveAttribute('href', /view=channel.*configuration_id=channel-a/);
  expect(state.requests.filter((request) => request.path.endsWith('/eligibility'))).toHaveLength(1);
});

test('Spectrum discovery gallery adopts its compact summary into the shared details stack', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/design-system.html?view=spectrum-discovery&theme=light');
  const workspace = page.locator('.visual-spectrum-discovery-example .spectrum-discovery-workspace');
  const workspaceNode = await workspace.elementHandle();
  await expect(wizard(page).getByRole('table')).toHaveCount(0);
  await expect(wizard(page).getByRole('link', { name: ohioSystem, exact: true })).toHaveCount(1);
  await wizard(page).getByRole('button', { name: 'Details', exact: true }).click();
  await expect(signalDetails(page)).toBeVisible();
  expect(await wizard(page).evaluate(element => element.closest('.modal-backdrop').inert)).toBe(true);
  await expect(signalDetails(page).getByRole('group', { name: 'On-air identity', exact: true })).toContainText('BEE00');
  await signalDetails(page).screenshot({ path: testInfo.outputPath('gallery-wizard-details.png') });
  await page.keyboard.press('Escape');
  await expect(signalDetails(page)).toHaveCount(0);
  await expect(wizard(page).getByRole('button', { name: 'Details', exact: true })).toBeFocused();
  expect(await wizard(page).locator('.spectrum-discovery-workspace').evaluate((element, previous) => element === previous,
    workspaceNode)).toBe(true);
  await expect(wizard(page).getByRole('link', { name: ohioSystem, exact: true })).toHaveCount(1);
});

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`P25 discovery gallery ${theme} at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/design-system.html?view=spectrum-discovery&theme=${theme}`);
    await expect(page.locator('.spectrum-discovery-modal')).toBeVisible();
    await expect(page.locator('.spectrum-discovery-modal')).toHaveScreenshot(`spectrum-discovery-p25-${theme}.png`);
  });
}

for (const width of [1280, 943, 900, 820, 390, 320]) {
  test(`Browse Spectrum frequency width stays fixed through status changes at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await install(page, { liveSpectrum: true });
    const frequency = page.locator('.spectrum-browse-center .tuners-center-frequency');
    await expect(frequency).toBeVisible();
    const measurements = await page.evaluate(() => {
      const field = document.querySelector('.spectrum-browse-center .tuners-center-frequency');
      const status = document.querySelector('.spectrum-browse-state .badge');
      return ['Live', 'Unfocused', 'Loading', 'Unavailable', 'Reconnecting'].map((label) => {
        status.textContent = label;
        const rect = field.getBoundingClientRect();
        return { width: rect.width, left: rect.left };
      });
    });
    for (const value of measurements) {
      expect(value.width).toBeCloseTo(measurements[0].width, 3);
      expect(value.left).toBeCloseTo(measurements[0].left, 3);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <=
      document.documentElement.clientWidth)).toBe(true);
  });
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`discovery explicitly reuses a same-name Alias List and imports into it in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = { ...ohioDiscoveryFixture(theme), aliasLists: [{ id: 21, name: ohioSystem, matched: false }], delayImport: true };
    await install(page, state);
    await begin(page);
    await review(page);
    const dialog = wizard(page);
    await expect(dialog.getByLabel('Alias List', { exact: true })).toHaveValue('21');
    await expect(dialog.getByText(`Existing Alias List found: ${ohioSystem}`, { exact: true })).toBeVisible();
    await expect(dialog.getByText('What is an Alias List?', { exact: true })).toHaveCount(0);
    await expect(dialog.getByText('An existing compatible Alias List with this name will be used.', { exact: true })).toHaveCount(0);
    await expect(dialog.getByText('Start automatically', { exact: true })).toHaveCount(0);
    await expect(dialog.getByText('Follow control frequency changes', { exact: true })).toHaveCount(0);
    await dialog.getByLabel('Alias List', { exact: true }).scrollIntoViewIfNeeded();
    await dialog.screenshot({ path: test.info().outputPath(`alias-choice-${theme}-${width}.png`) });
    await expect(importTalkgroups(page)).toBeChecked();
    await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
    await expect(dialog.getByRole('heading', { name: 'Importing talkgroups', exact: true })).toBeVisible();
    await expect.poll(() => typeof state.releaseImport).toBe('function');
    state.releaseImport();
    await expect(dialog).toContainText('73 added, 0 updated, 0 unchanged.');
    const saves = state.requests.filter(({ path }) => path.endsWith('/save'));
    expect(saves).toHaveLength(1);
    expect(saves[0].body.alias_list_id).toBe(21);
    expect(saves[0].body.new_alias_list_name).toBeNull();
    expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import')).map(({ body }) => body.alias_list_id)).toEqual([21]);
  });
}

test('discovery can explicitly create a uniquely named list beside an existing system list', async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), aliasLists: [{ id: 21, name: ohioSystem, matched: true }] };
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Alias List', { exact: true }).selectOption('0');
  const name = dialog.getByLabel('New Alias List name', { exact: true });
  await expect(name).toBeVisible();
  await expect(dialog).toContainText('already exists. Choose it above or enter a different name.');
  await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
  expect(state.requests.filter(({ path }) => path.endsWith('/save') || path.includes('/systems/talkgroups'))).toHaveLength(0);
  await name.fill('MARCS event listeners');
  await importTalkgroups(page).uncheck();
  await dialog.getByRole('button', { name: 'Back', exact: true }).click();
  await review(page);
  await expect(dialog.getByLabel('Alias List', { exact: true })).toHaveValue('0');
  await expect(name).toHaveValue('MARCS event listeners');
  await expect(importTalkgroups(page)).not.toBeChecked();
  await addDiscovery(page, importYes);
  await expect(dialog).toContainText('73 added, 0 updated, 0 unchanged.');
  const saved = state.requests.find(({ path }) => path.endsWith('/save')).body;
  expect(saved.alias_list_id).toBe(0);
  expect(saved.new_alias_list_name).toBe('MARCS event listeners');
});

test('discovery reports a missing saved import target instead of silently finishing', async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), singleMatch: true, missingImportTarget: true };
  await install(page, state);
  await begin(page);
  await review(page);
  await addDiscovery(page, importYes);
  await expect(wizard(page)).toContainText('Talkgroups were not imported. Retry, or import them from RadioReference.');
  await expect(wizard(page).getByRole('button', { name: 'Retry talkgroup imports', exact: true })).toBeVisible();
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(1);
});

test('discovery restores import choices after a failed channel save and retries once', async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), failSaveOnce: true };
  await install(page, state);
  await begin(page);
  await review(page);
  await addDiscovery(page, importYes);
  const dialog = wizard(page);
  await expect(dialog.getByRole('button', { name: 'Try adding again', exact: true })).toBeEnabled();
  await expect(importTalkgroups(page)).toBeEnabled();
  await expect(importTalkgroups(page)).toBeChecked();
  await dialog.getByRole('button', { name: 'Try adding again', exact: true }).click();
  await expect(dialog).toContainText('73 added, 0 updated, 0 unchanged.');
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(2);
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(1);
});

for (const failure of ['stale', 'unavailable']) {
test(`discovery keeps an unchecked import choice and edited names through ${failure} save and retry`, async ({ page }) => {
  const state = { ...ohioDiscoveryFixture('light'), staleOnce: failure === 'stale', failSaveOnce: failure === 'unavailable' };
  await page.clock.install();
  await install(page, state);
  await begin(page);
  await review(page);
  const dialog = wizard(page);
  await dialog.getByLabel('Channel name', { exact: true }).fill('My control');
  await dialog.getByLabel('New Alias List name', { exact: true }).fill('My names');
  await importTalkgroups(page).uncheck();
  await page.clock.fastForward(11_000);
  await expect(importTalkgroups(page)).not.toBeChecked();
  await dialog.getByRole('button', { name: 'Add and start listening', exact: true }).click();
  await expect(dialog).toContainText(failure === 'stale' ?
    'Your saved choices changed. Check the Alias List and try adding again.' :
    'We couldn’t add your channel. Your choices are still here. Try again.');
  await expect(importTalkgroups(page)).not.toBeChecked();
  await expect(dialog.getByLabel('Channel name', { exact: true })).toHaveValue('My control');
  await expect(dialog.getByLabel('New Alias List name', { exact: true })).toHaveValue('My names');
  await dialog.getByRole('button', { name: /^(Add and start listening|Try adding again)$/ }).click();
  await expect(dialog.locator('.discovery-completion')).toBeVisible();
  expect(state.requests.filter(({ path }) => path.endsWith('/save'))).toHaveLength(2);
  expect(state.requests.filter(({ path }) => path.endsWith('/aliases/import'))).toHaveLength(0);
});
}
