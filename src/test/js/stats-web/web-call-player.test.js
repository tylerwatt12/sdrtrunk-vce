'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

function wave(sampleRate = 8_000) {
  const data = new ArrayBuffer(44);
  const bytes = new Uint8Array(data);
  const view = new DataView(data);
  const write = (offset, value) => [...value].forEach((character, index) => {
    bytes[offset + index] = character.charCodeAt(0);
  });
  write(0, 'RIFF');
  view.setUint32(4, 36, true);
  write(8, 'WAVE');
  write(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  write(36, 'data');
  view.setUint32(40, 0, true);
  return data;
}

async function main() {
  const playerPath = path.resolve(process.argv[2] ||
    path.resolve(__dirname, '../../../../stats-web/assets/web-call-player.js'));
  const source = fs.readFileSync(playerPath, 'utf8');
  const { WebCallPlayer } = await import(pathToFileURL(playerPath).href);
  const { rememberSystemNames } = await import(pathToFileURL(path.join(path.dirname(playerPath),
    'core/system-labels.js')).href + '?v=1');

  let now = 0;
  let nextTimerId = 1;
  const timers = new Map();
  const originalNow = Date.now;
  const originalWindow = global.window;
  Date.now = () => now;
  global.window = {
    setTimeout(callback, delay) {
      const id = nextTimerId++;
      timers.set(id, { callback, delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); }
  };

  try {
    const volumeAttributes = new Map();
    const volumeStyles = new Map();
    const volumePlayer = Object.assign(Object.create(WebCallPlayer.prototype), {
      volume: 0.65,
      ui: { volume: {
        setAttribute(name, value) { volumeAttributes.set(name, value); },
        closest(selector) {
          assert.equal(selector, '.playback-volume');
          return { style: { setProperty(name, value) { volumeStyles.set(name, value); } } };
        }
      } }
    });
    volumePlayer.renderVolume();
    assert.equal(volumeAttributes.get('aria-valuetext'), '65 percent');
    assert.equal(volumeStyles.get('--playback-volume-level'), '65%',
      'The handleless volume control must preserve a visible fill level');
    volumePlayer.volume = 0;
    volumePlayer.renderVolume();
    assert.equal(volumeStyles.get('--playback-volume-level'), '0%',
      'The handleless volume control must render its empty state');

    const pendingVolumeWrites = [];
    const volumePreferences = Object.assign(Object.create(WebCallPlayer.prototype), {
      volume: 0.65,
      ui: { volume: {
        value: '0.65',
        setAttribute() {},
        closest() { return { style: { setProperty() {} } }; }
      } },
      gainNode: { gain: { value: 0.65 } },
      selectedScanListIds: new Set(['1']),
      targetGrouping: true,
      targetBurstLimit: 4,
      stateObservers: new Set(),
      filterQueueForSelectedLists() {}, renderScanLists() {}, synchronizeSubscription() {}, render() {},
      preferenceWriter(snapshot) {
        let finish;
        const promise = new Promise((resolve) => { finish = resolve; });
        pendingVolumeWrites.push({ snapshot, finish });
        return promise;
      }
    });
    const settleVolumeWrites = async () => { for (let index = 0; index < 8; index++) await Promise.resolve(); };
    const acknowledgeVolumeWrite = async (index, apply = true) => {
      const request = pendingVolumeWrites[index];
      if (apply) volumePreferences.applyPreferences(request.snapshot, { identity: 'operator' });
      request.finish();
      await settleVolumeWrites();
    };
    volumePreferences.applyPreferences({ volume: 0.65 }, { identity: 'operator' });
    volumePreferences.setVolume(0);
    volumePreferences.setVolume(0.65);
    volumePreferences.setVolume(0.75, false);
    await settleVolumeWrites();
    assert.deepEqual(pendingVolumeWrites.map((request) => request.snapshot.volume), [0, 0.65],
      'Preference writes must capture their volume when requested, before a newer drag changes it');
    await acknowledgeVolumeWrite(0);
    await acknowledgeVolumeWrite(1);
    assert.equal(volumePreferences.volume, 0.75,
      'Mute and unmute acknowledgments must not overwrite a newer unsaved drag');
    assert.equal(volumePreferences.ui.volume.value, '0.75');
    assert.equal(volumePreferences.gainNode.gain.value, 0.75,
      'Stale preference responses must not change the audible gain');
    assert.equal(volumePreferences.volumeEditPending, true);
    volumePreferences.applyPreferences({ target_grouping: false });
    volumePreferences.writePreferences();
    await settleVolumeWrites();
    await acknowledgeVolumeWrite(2);
    assert.equal(volumePreferences.volumeEditPending, true,
      'Saving a Scan List or grouping preference cannot commit an unfinished volume drag');

    volumePreferences.writePreferences({ volume: true });
    await settleVolumeWrites();
    volumePreferences.setVolume(0.8, false);
    await acknowledgeVolumeWrite(3);
    assert.equal(volumePreferences.volume, 0.8);
    assert.equal(volumePreferences.volumeEditPending, true,
      'A completed older volume save cannot release the guard for a newer drag');
    volumePreferences.setVolume(0.85);
    await settleVolumeWrites();
    volumePreferences.setVolume(0.9);
    await settleVolumeWrites();
    await acknowledgeVolumeWrite(5);
    assert.equal(volumePreferences.volumeEditPending, true,
      'The newest save must keep its guard while an older preference response is outstanding');
    await acknowledgeVolumeWrite(4);
    assert.equal(volumePreferences.volume, 0.9,
      'An older acknowledgment delivered after the latest save cannot replace the selected volume');
    assert.equal(volumePreferences.volumeEditPending, false);
    volumePreferences.applyPreferences({ volume: 0.2 }, { identity: 'operator' });
    assert.equal(volumePreferences.volume, 0.2,
      'Confirmed preferences may update volume after the latest edit and older responses settle');

    volumePreferences.setVolume(0.6);
    await settleVolumeWrites();
    volumePreferences.applyPreferences({ volume: 0.3 }, { identity: 'another-operator' });
    assert.equal(volumePreferences.volume, 0.3,
      "Changing the signed-in identity must discard the prior identity's transient volume edit");
    assert.equal(volumePreferences.volumeEditPending, false);
    volumePreferences.setVolume(0.45, false);
    await acknowledgeVolumeWrite(6, false);
    assert.equal(volumePreferences.volume, 0.45);
    assert.equal(volumePreferences.volumeEditPending, true,
      "An old identity's pending save cannot release a newer identity's volume guard");

    const presentation = Object.create(WebCallPlayer.prototype);
    Object.assign(presentation, {
      current: null,
      holdTarget: null,
      heldDisplayCall: null,
      idleDisplayCall: null,
      idleDisplayTimer: null,
      idleDisplayDeadline: 0,
      renderCount: 0,
      render() { this.renderCount++; }
    });
    const first = { _callId: 'first' };
    presentation.showIdleDisplay(first);
    const firstTimer = timers.values().next().value;
    assert.equal(firstTimer.delay, 5000);
    now = 4999;
    assert.equal(presentation.displayCall(), first);
    firstTimer.callback();
    const finalTimer = [...timers.values()].at(-1);
    assert.equal(finalTimer.delay, 1);
    now = 5000;
    assert.equal(presentation.displayCall(), null);
    finalTimer.callback();
    assert.equal(presentation.renderCount, 1);

    const held = { _callId: 'held', _playbackTargetKey: 'target:held' };
    presentation.holdTarget = held._playbackTargetKey;
    presentation.heldDisplayCall = held;
    assert.equal(presentation.displayCall(), held,
      'The held target must keep its call details visible after the normal idle display expires');
    presentation.holdTarget = null;
    assert.equal(presentation.displayCall(), null,
      'Releasing Hold must remove the retained call details');

    assert.equal(WebCallPlayer.waveSampleRate(wave()), 8_000);
    assert.equal(WebCallPlayer.waveSampleRate(new ArrayBuffer(44)), null);
    let maximumSpectrumBinRead = -1;
    const spectrumSamples = new Proxy(new Array(256).fill(0), {
      get(target, property, receiver) {
        if (/^\d+$/.test(String(property))) maximumSpectrumBinRead = Math.max(maximumSpectrumBinRead, Number(property));
        return Reflect.get(target, property, receiver);
      }
    });
    const spectrum = Object.assign(Object.create(WebCallPlayer.prototype), {
      paused: false,
      source: {},
      currentSourceSampleRate: 8_000,
      spectrumSamples,
      audioContext: { state: 'running', sampleRate: 48_000 },
      analyserNode: {
        frequencyBinCount: 256,
        getByteFrequencyData(samples) {
          samples.fill(0);
          samples.fill(128, 1, 43);
          samples[200] = 255;
        }
      }
    });
    const spectrumLevels = new Float32Array(64);
    assert.equal(spectrum.readAudioSpectrum(spectrumLevels, 8_000), true);
    assert.ok(spectrumLevels.every((level) => level > 0 && level < 1),
      'An 8 kHz source must spread its 4 kHz Nyquist band across all 64 Scanner bars');
    assert.equal(maximumSpectrumBinRead, 42,
      'The Scanner visualizer must not sample beyond the source audio bandwidth');

    const queuePlayer = Object.create(WebCallPlayer.prototype);
    Object.assign(queuePlayer, {
      queuedCalls: [],
      queuedCount: 0,
      targetGrouping: false,
      targetBurstLimit: 2,
      lastPlaybackTargetKey: 'A',
      consecutiveTargetCalls: 1
    });
    const call = (id, started, target) => ({
      _callId: id, _startedAtMs: started, _arrivalSequence: started, _playbackTargetKey: target
    });
    queuePlayer.insertQueuedCall(call('a-late', 30, 'A'));
    queuePlayer.insertQueuedCall(call('b-first', 10, 'B'));
    queuePlayer.insertQueuedCall(call('a-first', 20, 'A'));
    assert.equal(queuePlayer.takeNextCall()._callId, 'b-first',
      'Conversation Mode off must choose the globally earliest waiting call');

    queuePlayer.queuedCalls = [];
    queuePlayer.queuedCount = 0;
    queuePlayer.targetGrouping = true;
    queuePlayer.lastPlaybackTargetKey = 'A';
    queuePlayer.consecutiveTargetCalls = 1;
    queuePlayer.insertQueuedCall(call('b-oldest', 10, 'B'));
    queuePlayer.insertQueuedCall(call('a-one', 20, 'A'));
    queuePlayer.insertQueuedCall(call('a-two', 30, 'A'));
    assert.equal(queuePlayer.takeNextCall()._callId, 'a-one',
      'Conversation Mode may regroup only a call that is already waiting');
    assert.equal(queuePlayer.takeNextCall()._callId, 'b-oldest',
      'The burst limit must give another waiting conversation its turn');
    assert.equal(queuePlayer.takeNextCall()._callId, 'a-two');
    queuePlayer.queuedCalls = [];
    queuePlayer.queuedCount = 0;
    queuePlayer.lastPlaybackTargetKey = 'A';
    queuePlayer.consecutiveTargetCalls = 0;
    queuePlayer.targetBurstLimit = 20;
    queuePlayer.insertQueuedCall(call('same-later', 30, 'A'));
    queuePlayer.insertQueuedCall(call('same-earlier', 20, 'A'));
    assert.deepEqual(queuePlayer.scheduledQueue().map((item) => item._callId), ['same-earlier', 'same-later'],
      'Calls from one conversation must remain chronological');

    const trunked = {
      protocol: 'P25', system: 'Display name can change', radio_system_key: 'p25:bee00:49f',
      target_form: 'TALKGROUP', target_id: 56735, timeslot: 0,
      playback_target: {
        key: 'system:p25:bee00:49f:talkgroup:56735', kind: 'talkgroup',
        radio_system_key: 'p25:bee00:49f', label: 'Talkgroup 56735'
      }
    };

    const labels = Object.create(WebCallPlayer.prototype);
    const namedSource = { target_id: 77, source_id: 312, source_alias: 'Engine 4', talker_alias: 'ENG 4' };
    assert.equal(labels.callLabel(namedSource), 'ID 77 ← ENG 4 · Radio 312',
      'Live call labels prefer the talker alias by default while retaining the source ID');
    assert.equal(labels.callLabel({ ...namedSource, talker_alias: '  ' }), 'ID 77 ← Engine 4 · Radio 312',
      'A missing or blank talker alias falls back to the configured source alias');
    let sourceMode = 'source_alias';
    labels.getSourceNameDisplay = () => sourceMode;
    assert.equal(labels.callLabel(namedSource), 'ID 77 ← Engine 4 · Radio 312');
    assert.equal(labels.callLabel({ ...namedSource, source_alias: '' }), 'ID 77 ← ENG 4 · Radio 312');
    sourceMode = 'both';
    assert.equal(labels.callLabel(namedSource), 'ID 77 ← Engine 4 · ENG 4 · Radio 312',
      'Both shows the two distinct names without a name prefix');
    assert.equal(labels.callLabel({ ...namedSource, talker_alias: ' engine 4 ' }),
      'ID 77 ← Engine 4 · Radio 312', 'Both must not repeat an equivalent name');
    sourceMode = 'talker_alias';
    assert.equal(labels.callLabel(namedSource), 'ID 77 ← ENG 4 · Radio 312',
      'Call labels read a changed source-name preference without creating a new player');
    assert.equal(labels.callLabel({
      channel: 'Fire Dispatch', decoder: 'NBFM', target_form: 'TALKGROUP', target_id: 1,
      target_alias: 'Synthetic route', source_id: '',
      playback_target: { key: 'channel:conventional-am', kind: 'channel', label: 'Fire Dispatch' }
    }), 'Fire Dispatch', 'Analog calls must use the saved channel instead of the synthetic TGID');
    assert.equal(labels.callLabel({
      channel: 'DMR Repeater', protocol: 'DMR', target_form: 'TALKGROUP', target_id: 91,
      target_alias: 'Operations', source_id: '',
      playback_target: {
        key: 'channel:conventional-dmr:timeslot:2', kind: 'channel_timeslot',
        label: 'DMR Repeater · Timeslot 2'
      }
    }), 'Operations · TGID 91', 'A real conventional DMR target must remain visible in the call label');
    assert.equal(labels.targetLabel({
      protocol: 'DMR', target_alias: 'Operations', target_id: 91,
      playback_target: {
        key: 'channel:conventional-dmr:timeslot:2', kind: 'channel_timeslot',
        label: 'DMR Repeater · Timeslot 2'
      }
    }), 'DMR Repeater · Timeslot 2', 'Hold and Avoid must describe their channel-and-timeslot scope');
    assert.equal(labels.callLabel({
      channel: 'P25 Conventional', protocol: 'P25', target_form: 'TALKGROUP', target_id: 1201,
      source_id: '', playback_target: {
        key: 'channel:p25-conventional', kind: 'channel', label: 'P25 Conventional'
      }
    }), 'TGID 1201', 'A real conventional P25 talkgroup must remain visible in the call label');
    assert.equal(labels.targetLabel({
      protocol: 'P25', target_id: 1201,
      playback_target: { key: 'channel:p25-conventional', kind: 'channel', label: 'P25 Conventional' }
    }), 'P25 Conventional', 'Hold and Avoid must remain channel-scoped for conventional P25');
    assert.equal(labels.callLabel({ target_id: 77, source_id: '' }), 'ID 77');
    assert.equal(labels.callLabel({ target_form: 'UNKNOWN', target_id: 78, source_id: '' }), 'ID 78');
    assert.equal(labels.callLabel({ target_form: 'TELEPHONE_NUMBER', target_id: 5551212, source_id: '' }),
      'ID 5551212');
    assert.equal(labels.targetLabel({ target_id: 77 }), 'ID 77');
    assert.equal(labels.targetLabel({ target_form: 'TELEPHONE_NUMBER', target_id: 5551212 }), 'ID 5551212');
    assert.equal(labels.targetLabel({}), 'Unknown identity');
    assert.equal(labels.callLabel({
      protocol: 'P25', target_form: 'TALKGROUP', target_id: 91,
      source_form: 'RADIO', source_id: 501,
      source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 2_115_288 },
      source_observed_working_id: 501
    }), 'TGID 91 ← BEE00.348.2115288 (Working ID 501)');
    assert.equal(labels.callLabel({
      protocol: 'P25', target_form: 'TALKGROUP', target_id: 91,
      source_form: 'RADIO', source_id: 2_115_288,
      source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 2_115_288 }
    }), 'TGID 91 ← BEE00.348.2115288');
    for (const homeField of ['wacn', 'system_id']) {
      for (const missing of [null, undefined, '', ' ', '\t']) {
        assert.equal(labels.canonicalRadioLabel({ source_canonical_identity: {
          wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501, [homeField]: missing
        } }, 'source'), '', 'Missing home IDs must not fabricate a permanent identity containing zero.');
      }
    }
    assert.equal(labels.canonicalRadioLabel({ source_canonical_identity: {
      wacn: 0, system_id: 0, subscriber_id: 501
    } }, 'source'), '00000.000.501', 'Explicit numeric zero remains a valid home ID.');
    const equalWorkingTarget = {
      protocol: 'P25', target_form: 'RADIO', target_id: 2_115_288, target_alias: 'Dispatch',
      target_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 2_115_288 },
      target_observed_working_id: 2_115_288,
      playback_target: { kind: 'radio', label: 'Radio 2115288' }
    };
    assert.equal(labels.callLabel(equalWorkingTarget),
      'Dispatch · BEE00.348.2115288');
    assert.equal(labels.targetLabel(equalWorkingTarget),
      'Dispatch · BEE00.348.2115288');
    const localRadioTarget = { ...equalWorkingTarget, radio_system_key: 'p25:bee00:348' };
    assert.equal(labels.callLabel(localRadioTarget), 'Dispatch · 2115288',
      'Local radio numbers should stay compact in the listening player.');
    assert.equal(labels.targetLabel(localRadioTarget), 'Dispatch · 2115288',
      'Hold and Avoid must use the same local radio label as the listening player.');
    const foreignNamedRadio = {
      ...equalWorkingTarget, radio_system_key: 'p25:bee00:349',
      target_home_system_name: 'Home System', home_system_name: 'Less specific name'
    };
    assert.equal(labels.callLabel(foreignNamedRadio), 'Dispatch · Home System · 2115288',
      'A foreign radio with the same Working ID still needs its home system.');
    assert.equal(labels.targetLabel(foreignNamedRadio), 'Dispatch · Home System · 2115288');
    assert.equal(labels.targetLabel({ ...foreignNamedRadio, target_home_system_name: ' ' }),
      'Dispatch · BEE00.348.2115288', 'An unqualified home name must not be assigned to either radio endpoint.');
    assert.equal(labels.targetLabel({ ...foreignNamedRadio, target_observed_working_id: 501 }),
      'Dispatch · Home System · 2115288 (Working ID 501)');
    assert.equal(labels.targetLabel({ ...foreignNamedRadio, radio_system_key: 'p25:bee01:348' }),
      'Dispatch · Home System · 2115288', 'A matching SysID alone does not establish local ownership.');
    assert.equal(labels.targetLabel({ ...equalWorkingTarget,
      playback_target: { kind: 'radio', radio_system_key: 'p25:bee00:348' } }), 'Dispatch · 2115288');
    assert.equal(labels.targetLabel({ ...equalWorkingTarget,
      target_entity_ref: { kind: 'radio', radio_system_key: 'p25:bee00:348',
        identity_key: 'v1-r-bee00-348-2115288' } }), 'Dispatch · 2115288',
      'Target radio links can supply missing receiving scope without a repeated local prefix.');
    assert.equal(labels.callLabel({
      protocol: 'P25', target_form: 'TALKGROUP', target_id: 91,
      source_form: 'RADIO', source_id: 2_115_288,
      source_canonical_identity: equalWorkingTarget.target_canonical_identity,
      source_observed_working_id: 2_115_288,
      source_entity_ref: { kind: 'radio', radio_system_key: 'p25:bee00:348',
        identity_key: 'v1-r-bee00-348-2115288' }
    }), 'TGID 91 ← 2115288', 'Source playback labels share the endpoint receiving scope rule.');
    assert.equal(labels.targetLabel({ ...equalWorkingTarget,
      radio_system_key: 'p25:bee00:349',
      target_entity_ref: { kind: 'radio', radio_system_key: 'p25:bee00:348' } }),
      'Dispatch · BEE00.348.2115288', 'Explicit receiving scope wins over an endpoint link.');
    assert.equal(labels.targetLabel({ ...foreignNamedRadio,
      playback_target: { kind: 'radio', radio_system_key: 'p25:bee00:348' } }),
      'Dispatch · Home System · 2115288', 'The call receiving system takes precedence over playback context.');
    assert.equal(labels.callLabel({ ...localRadioTarget, target_alias: '', target_observed_working_id: 501 }),
      '2115288 (Working ID 501)');
    assert.equal(labels.canonicalRadioLabel({ ...foreignNamedRadio, radio_system_key: '',
      target_home_system_name: 'Home System' }, 'target'), 'Home System · 2115288',
      'Missing receiving scope should keep home system context even when the Working ID matches.');
    const differentHomes = {
      protocol: 'P25', radio_system_key: 'p25:bee00:349',
      source_form: 'RADIO', source_id: 501, source_home_system_name: 'Source Home',
      source_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
      target_form: 'RADIO', target_id: 501,
      target_canonical_identity: { wacn: 0xBEE01, system_id: 0x348, subscriber_id: 501 },
      home_system_name: 'Source Home'
    };
    assert.equal(labels.callLabel(differentHomes), 'BEE01.348.501 ← Source Home · 501',
      'The source home name must not label a target radio from a different home, even with equal radio numbers.');
    assert.equal(labels.callLabel({ ...differentHomes, target_home_system_name: 'Target Home' }),
      'Target Home · 501 ← Source Home · 501');
    rememberSystemNames({ radio_system_key: 'p25:bee00:348', system_name: 'Cached Home' });
    assert.equal(labels.targetLabel(equalWorkingTarget), 'Dispatch · Cached Home · 2115288',
      'Previously received system names can label an exact home identity.');
    assert.equal(labels.targetLabel(localRadioTarget), 'Dispatch · 2115288',
      'A cached name must not lengthen a confirmed local radio label.');
    rememberSystemNames({ radio_system_key: 'p25:bee00:348', system_name: '' });
    const conventionalCanonicalTarget = {
      protocol: 'P25', target_form: 'RADIO', target_id: 501,
      target_canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 2_115_288 },
      target_observed_working_id: 501,
      playback_target: { key: 'channel:p25-conventional', kind: 'channel', label: 'P25 Conventional' }
    };
    assert.equal(labels.callLabel(conventionalCanonicalTarget),
      'BEE00.348.2115288 (Working ID 501)');
    assert.equal(labels.targetLabel(conventionalCanonicalTarget), 'P25 Conventional',
      'Hold and Avoid labels must describe the channel-scoped playback target');
    const firstSystemScope = labels.avoidSystemScope(trunked);
    const secondSystemScope = labels.avoidSystemScope({
      ...trunked, system: 'Display name can change', radio_system_key: 'p25:bee00:4a0',
      playback_target: { ...trunked.playback_target, radio_system_key: 'p25:bee00:4a0' }
    });
    assert.equal(firstSystemScope, 'Display name can change');
    assert.equal(secondSystemScope, 'Display name can change',
      'Avoid scope displays friendly names while native playback target keys retain system ownership');
    assert.equal(labels.avoidSystemScope({ ...trunked, system: '', system_name: '',
      radio_system_key: 'p25:bee00:49f' }), 'BEE00-49F', 'Unnamed systems keep a native identity fallback');
    assert.equal(labels.avoidSystemScope({
      system: 'Conventional', playback_target: { kind: 'channel', radio_system_key: 'ignored' }
    }), '', 'Channel-scoped avoids must not invent a radio-system scope');

    const normalized = Object.assign(Object.create(WebCallPlayer.prototype), { arrivalSequence: 0 });
    const normalizedCall = normalized.normalizeCall({
      ...trunked,
      call_id: 'instance:1', audio_url: '/api/v1/calls/instance:1/audio',
      started_at_ms: 100, completed_at_ms: 200, scan_list_ids: [1, 1, 2]
    });
    assert.deepEqual(normalizedCall._matchedScanListIds, ['1', '2']);
    assert.equal(normalizedCall._playbackTargetKey, trunked.playback_target.key);

    const dedupe = Object.assign(Object.create(WebCallPlayer.prototype), {
      arrivalSequence: 0,
      selectedScanListIds: new Set(['1', '2']),
      scanListById: new Map([['1', { enabled: true }], ['2', { enabled: true }]]),
      maximumSelectedScanLists: 128,
      maximumQueued: 100,
      seenCallIds: new Set(),
      seenCallOrder: [],
      queuedCalls: [],
      queuedCount: 0,
      avoids: new Map(),
      holdTarget: null,
      stopped: true,
      current: null,
      render() {}
    });
    const overlap = {
      ...trunked,
      call_id: 'instance:overlap', audio_url: '/api/v1/calls/instance:overlap/audio',
      started_at_ms: 300, completed_at_ms: 400, scan_list_ids: [1, 2]
    };
    dedupe.enqueue(overlap);
    dedupe.enqueue(overlap);
    assert.equal(dedupe.queuedCount, 1,
      'One call matching several selected Scan Lists must enter the browser queue only once');

    const feedPlayer = Object.assign(Object.create(WebCallPlayer.prototype), {
      feedUrl: '/api/v1/calls/feed',
      feedCursor: null,
      scanListById: new Map([['1', { enabled: true }], ['2', { enabled: true }]]),
      selectedScanListIds: new Set(['2', '1']),
      maximumSelectedScanLists: 128
    });
    assert.equal(feedPlayer.feedRequestUrl(), '/api/v1/calls/feed?scan_list_id=1&scan_list_id=2');
    feedPlayer.feedCursor = '42';
    assert.equal(feedPlayer.feedRequestUrl(), '/api/v1/calls/feed?scan_list_id=1&scan_list_id=2&cursor=42');
    feedPlayer.feedFetch = async () => ({ cursor: '43', reset: false, calls: [] });
    assert.deepEqual(await feedPlayer.requestFeed({}), { cursor: '43', reset: false, calls: [] });
    feedPlayer.feedFetch = async () => ({ cursor: 43, reset: false, calls: [] });
    await assert.rejects(() => feedPlayer.requestFeed({}), /invalid response/);
    feedPlayer.feedCursor = '99';
    feedPlayer.feedActive = true;
    feedPlayer.feedGeneration = 0;
    feedPlayer.feedController = null;
    feedPlayer.feedTimer = null;
    feedPlayer.stopFeed();
    assert.equal(feedPlayer.feedCursor, null);
    assert.equal(feedPlayer.feedRequestUrl(), '/api/v1/calls/feed?scan_list_id=1&scan_list_id=2',
      'A restarted player must omit its old cursor and begin at the live edge');
    feedPlayer.stopped = false;
    feedPlayer.feedActive = false;
    feedPlayer.scanListCatalogReady = true;
    feedPlayer.pollFeed = async function () { this.firstRestartUrl = this.feedRequestUrl(); };
    assert.equal(feedPlayer.ensureConnected(), true);
    await Promise.resolve();
    assert.equal(feedPlayer.firstRestartUrl, '/api/v1/calls/feed?scan_list_id=1&scan_list_id=2');

    const resetPoll = Object.assign(Object.create(WebCallPlayer.prototype), {
      feedActive: true,
      stopped: false,
      feedGeneration: 7,
      feedController: null,
      feedCursor: '10',
      current: null,
      queuedCount: 0,
      statusValue: '',
      ui: { status: { textContent: '' } },
      stateObservers: new Set(),
      requestFeed: async () => ({ cursor: '20', reset: true, calls: [] }),
      scheduleFeedPoll() {},
      enqueue() {}
    });
    await resetPoll.pollFeed(7);
    assert.equal(resetPoll.feedCursor, '20');
    assert.equal(resetPoll.ui.status.textContent, 'Waiting');

    const failedPoll = Object.assign(Object.create(WebCallPlayer.prototype), {
      feedActive: true,
      stopped: false,
      feedGeneration: 8,
      feedController: null,
      feedCursor: '20',
      statusValue: '',
      ui: { status: { textContent: '' } },
      stateObservers: new Set(),
      requestFeed: async () => { throw new Error('offline'); },
      scheduleFeedPoll(generation, delay) { this.retry = { generation, delay }; }
    });
    await failedPoll.pollFeed(8);
    assert.equal(failedPoll.feedCursor, null,
      'A failed live-feed request must discard its stale cursor and resume at the live edge');
    assert.equal(failedPoll.ui.status.textContent, 'Reconnecting');
    assert.deepEqual(failedPoll.retry, {
      generation: 8,
      delay: WebCallPlayer.FEED_RETRY_INTERVAL_MS
    });

    const recovering = Object.assign(Object.create(WebCallPlayer.prototype), {
      feedActive: true,
      stopped: false,
      statusValue: 'Reconnecting',
      stopFeed() { this.feedActive = false; this.stopCount = (this.stopCount || 0) + 1; },
      ensureConnected() { this.feedActive = true; this.connectCount = (this.connectCount || 0) + 1; return true; }
    });
    assert.equal(recovering.recoverFeed(), true);
    assert.equal(recovering.stopCount, 1);
    assert.equal(recovering.connectCount, 1);
    recovering.statusValue = 'Waiting';
    assert.equal(recovering.recoverFeed(), false,
      'Page focus must leave a healthy audio feed alone');
    assert.equal(recovering.stopCount, 1);

    const stopped = Object.assign(Object.create(WebCallPlayer.prototype), {
      stopped: false,
      transportToken: 0,
      feedStopped: 0,
      queueCleared: 0,
      currentStopped: 0,
      replayingLast: false,
      stopAfterReplay: false,
      lastPlaybackTargetKey: 'A',
      consecutiveTargetCalls: 2,
      lastHeard: { _callId: 'retained' },
      lastHeardBuffer: { duration: 3 },
      audioContext: { state: 'running', async suspend() {} },
      stopFeed() { this.feedStopped++; },
      clearQueuedCalls() { this.queueCleared++; },
      stopCurrent() { this.currentStopped++; },
      setStatus(value) { this.status = value; },
      render() {}
    });
    await stopped.togglePlayback();
    assert.equal(stopped.stopped, true);
    assert.equal(stopped.feedStopped, 1);
    assert.equal(stopped.queueCleared, 1);
    assert.equal(stopped.currentStopped, 1);
    assert.equal(stopped.lastHeard._callId, 'retained');
    assert.equal(stopped.lastHeardBuffer.duration, 3,
      'Stop must retain exactly the one local Replay Last buffer');
    assert.equal(stopped.lastPlaybackTargetKey, null);
    assert.equal(stopped.status, 'Ready');

    function playerReadyToStart(selectedScanListIds = []) {
      const defaultScanList = { id: '2', name: 'Default', enabled: true, default: true };
      const otherScanList = { id: '1', name: 'Dispatch', enabled: true, default: false };
      return Object.assign(Object.create(WebCallPlayer.prototype), {
        stopped: true,
        scanListCatalogReady: true,
        scanLists: [otherScanList, defaultScanList],
        scanListById: new Map([[otherScanList.id, otherScanList], [defaultScanList.id, defaultScanList]]),
        selectedScanListIds: new Set(selectedScanListIds),
        maximumSelectedScanLists: 16,
        transportToken: 0,
        preferenceWrites: 0,
        feedStarts: 0,
        writePreferences() { this.preferenceWrites++; },
        updateScanListStatus() {},
        filterQueueForSelectedLists() {},
        renderScanLists() {},
        ensureConnected() { this.feedStarts++; return true; },
        ensureAudioContext() {},
        audioContext: { state: 'suspended', async resume() {} },
        setStatus(value) { this.status = value; },
        render() {}
      });
    }

    const defaultOnPlay = playerReadyToStart();
    await defaultOnPlay.togglePlayback();
    assert.deepEqual([...defaultOnPlay.selectedScanListIds], ['2'],
      'Play with no selection must choose the administrator-configured default Scan List');
    assert.equal(defaultOnPlay.preferenceWrites, 1,
      'The automatic default selection must be saved through the current user preferences');
    assert.equal(defaultOnPlay.feedStarts, 1);
    assert.equal(defaultOnPlay.stopped, false);

    const staleSelectionOnPlay = playerReadyToStart(['999']);
    await staleSelectionOnPlay.togglePlayback();
    assert.deepEqual([...staleSelectionOnPlay.selectedScanListIds], ['2'],
      'A stale saved selection must not prevent fallback to the current default Scan List');
    assert.equal(staleSelectionOnPlay.preferenceWrites, 1);

    const explicitSelectionOnPlay = playerReadyToStart(['1']);
    await explicitSelectionOnPlay.togglePlayback();
    assert.deepEqual([...explicitSelectionOnPlay.selectedScanListIds], ['1'],
      'Play must preserve an available Scan List explicitly selected by the user');
    assert.equal(explicitSelectionOnPlay.preferenceWrites, 0);

    const selection = Object.assign(Object.create(WebCallPlayer.prototype), {
      scanListById: new Map([['1', { id: '1', enabled: true }]]),
      selectedScanListIds: new Set(),
      maximumSelectedScanLists: 128,
      scanListCatalogReady: true,
      stopped: true,
      toggleCount: 0,
      togglePlayback() { this.toggleCount++; },
      writePreferences() {}, updateScanListStatus() {}, filterQueueForSelectedLists() {},
      renderScanLists() {},
      ensureConnected() { this.feedStartCount = (this.feedStartCount || 0) + 1; return true; },
      stopFeed() {}, setStatus() {}, render() {}
    });
    selection.setScanListSelected('1', true);
    assert.equal(selection.toggleCount, 0, 'Selecting a Scan List while stopped must not start playback');
    assert.equal(selection.feedStartCount || 0, 0, 'Selecting a Scan List while stopped must not start the feed');

    const preferencePlayer = Object.assign(Object.create(WebCallPlayer.prototype), {
      selectedScanListIds: new Set(['1']),
      ui: { volume: { value: '1' } },
      volume: 1,
      targetGrouping: true,
      targetBurstLimit: 4,
      subscriptionChanges: 0,
      filterQueueForSelectedLists() {}, renderScanLists() {}, render() {},
      synchronizeSubscription() { this.subscriptionChanges++; }
    });
    preferencePlayer.applyPreferences({
      volume: 0.5, selected_scan_list_ids: [1], target_grouping: false,
      target_burst_limit: 2
    });
    assert.equal(preferencePlayer.subscriptionChanges, 0,
      'Unrelated preference saves must not restart a live call feed at a new cursor');
    preferencePlayer.applyPreferences({
      volume: 0.5, selected_scan_list_ids: [2], target_grouping: false,
      target_burst_limit: 2
    });
    assert.equal(preferencePlayer.subscriptionChanges, 1);

    let audioSource;
    const heard = { _callId: 'heard' };
    const heardBuffer = { duration: 1 };
    const replay = Object.assign(Object.create(WebCallPlayer.prototype), {
      current: heard,
      currentBuffer: heardBuffer,
      lastHeard: null,
      lastHeardBuffer: null,
      replayingLast: false,
      stopAfterReplay: false,
      stopped: false,
      source: null,
      playbackStartedAt: 0,
      loadToken: 1,
      transportToken: 0,
      loadController: null,
      audioContext: {
        currentTime: 0, state: 'running', async resume() {}, async suspend() {},
        createBufferSource() {
          audioSource = { connect() {}, disconnect() {}, start() {}, stop() {}, onended: null, buffer: null };
          return audioSource;
        }
      },
      analyserNode: {},
      setStatus(value) { this.status = value; }, render() {}, startProgress() {}, stopProgress() {},
      clearIdleDisplay() {}, playNext() {}
    });
    replay.startCurrent();
    assert.equal(replay.lastHeard, null,
      'Replay Last must refer to the prior completed call, not the call currently playing');
    audioSource.onended();
    assert.equal(replay.lastHeard, heard);
    assert.equal(replay.lastHeardBuffer, heardBuffer,
      'A naturally completed call must keep one decoded audio buffer for local replay');
    replay.stopped = true;
    replay.current = null;
    replay.currentBuffer = null;
    assert.equal(await replay.replayLastCall(), true);
    assert.equal(replay.current, heard);
    assert.equal(replay.currentBuffer, heardBuffer);
    assert.equal(replay.replayingLast, true);
    assert.equal(replay.stopAfterReplay, true);

    function pauseFixture() {
      const player = Object.assign(Object.create(WebCallPlayer.prototype), {
        stopped: false, paused: false, transportToken: 0, loadToken: 0, loadController: null,
        current: null, currentBuffer: null, source: null, lastHeard: heard, lastHeardBuffer: heardBuffer,
        replayingLast: false, stopAfterReplay: false, playbackStartedAt: 0,
        queuedCalls: [], queuedCount: 0, maximumQueued: 100, targetGrouping: false,
        seenCallIds: new Set(), seenCallOrder: [], arrivalSequence: 0,
        avoids: new Map(), holdTarget: null, selectedScanListIds: new Set(['1']),
        scanLists: [{ id: '1', name: 'Test', enabled: true, default: true }],
        scanListById: new Map([['1', { id: '1', enabled: true }]]),
        maximumSelectedScanLists: 16, scanListCatalogReady: true,
        feedActive: true, feedGeneration: 1, feedCursor: '10', feedTimer: null,
        feedController: null, feedUrl: '/api/v1/calls/feed',
        statusValue: 'Waiting', stateObservers: new Set(),
        ui: { status: { textContent: '' } }, progressStarts: 0, progressStops: 0,
        ensureAudioContext() {}, clearIdleDisplay() {}, renderScanLists() {},
        writePreferences() {}, updateScanListStatus() {},
        startProgress() { this.progressStarts++; }, stopProgress() { this.progressStops++; },
        render() { this.renderStatus(); },
        audioContext: {
          state: 'running', currentTime: 3,
          async suspend() { this.state = 'suspended'; },
          async resume() { this.state = 'running'; },
          createBufferSource() {
            return { connect() {}, disconnect() {}, start() {}, stop() {}, onended: null };
          }
        }
      });
      return player;
    }

    const pausing = pauseFixture();
    pausing.current = { ...overlap, _callId: 'current' };
    pausing.currentBuffer = { duration: 20 };
    pausing.startCurrent();
    pausing.audioContext.currentTime = 7;
    const retainedSource = pausing.source;
    const retainedAudio = pausing.currentBuffer;
    await pausing.togglePause();
    assert.equal(pausing.getPlaybackPosition(), 4);
    assert.equal(pausing.source, retainedSource, 'Pause must preserve the actual audio source');
    assert.equal(pausing.audioContext.state, 'suspended');
    assert.equal(pausing.feedActive, true);
    assert.equal(pausing.feedCursor, '10', 'Pause must not restart the feed at the live edge');
    assert.equal(pausing.viewState().playing, false);
    assert.equal(pausing.viewState().stopped, false);
    assert.equal(await pausing.replayLastCall(), false, 'Replay must not silently unpause playback');
    await pausing.togglePause();
    assert.equal(pausing.source, retainedSource);
    assert.equal(pausing.currentBuffer, retainedAudio);
    assert.equal(pausing.getPlaybackPosition(), 4, 'Resume must not restart the call');
    assert.equal(pausing.audioContext.state, 'running');
    await pausing.togglePause();
    pausing.enqueue(overlap);
    assert.equal(pausing.queuedCount, 1);
    await pausing.togglePlayback();
    assert.equal(pausing.stopped, true);
    assert.equal(pausing.paused, false);
    assert.equal(pausing.source, null);
    assert.equal(pausing.currentBuffer, null);
    assert.equal(pausing.queuedCount, 0);
    assert.equal(pausing.feedActive, false);
    assert.equal(pausing.feedCursor, null);
    await pausing.togglePause();
    assert.equal(pausing.stopped, true, 'Pause while stopped is a no-op');

    const collecting = pauseFixture();
    await collecting.togglePause();
    collecting.requestFeed = async () => ({ cursor: '11', reset: false, calls: [overlap] });
    await collecting.pollFeed(1);
    assert.equal(collecting.queuedCount, 1);
    assert.equal(collecting.current, null, 'Feed arrivals must not start paused audio');
    assert.equal(collecting.feedCursor, '11');
    assert.ok(collecting.feedTimer !== null, 'Paused playback must keep polling');
    for (let i = 0; i < 105; i++) collecting.enqueue({
      ...overlap, call_id: 'overflow-' + i, started_at_ms: 500 + i
    });
    assert.equal(collecting.queuedCount, 100);
    assert.equal(collecting.queuedCalls[0]._callId, 'overflow-5');
    assert.equal(collecting.ui.status.textContent, 'Paused — 100 calls queued');
    collecting.skip();
    assert.equal(collecting.queuedCount, 99);
    assert.equal(collecting.paused, true);
    collecting.clearQueue();
    assert.equal(collecting.paused, true);
    assert.equal(collecting.queuedCount, 0);
    collecting.stopFeed();

    const selectionWhilePaused = pauseFixture();
    selectionWhilePaused.current = selectionWhilePaused.normalizeCall(overlap);
    selectionWhilePaused.currentBuffer = { duration: 20 };
    await selectionWhilePaused.togglePause();
    selectionWhilePaused.toggleHold();
    assert.equal(selectionWhilePaused.holdTarget, overlap.playback_target.key);
    assert.equal(selectionWhilePaused.heldDisplayCall, selectionWhilePaused.current,
      'Enabling Hold must retain the current call for Scanner details');
    selectionWhilePaused.stopCurrent();
    assert.equal(selectionWhilePaused.displayCall(), selectionWhilePaused.heldDisplayCall,
      'Held Scanner details must remain after the active call ends');
    selectionWhilePaused.toggleHold();
    assert.equal(selectionWhilePaused.heldDisplayCall, null,
      'Releasing Hold must remove the retained Scanner details');
    selectionWhilePaused.current = selectionWhilePaused.normalizeCall(overlap);
    selectionWhilePaused.currentBuffer = { duration: 20 };
    selectionWhilePaused.toggleHold();
    selectionWhilePaused.avoidCurrent();
    assert.equal(selectionWhilePaused.current, null);
    assert.equal(selectionWhilePaused.heldDisplayCall, null,
      'Avoiding the held target must remove its retained Scanner details');
    assert.equal(selectionWhilePaused.paused, true);
    assert.equal(selectionWhilePaused.avoids.size, 1);
    assert.equal([...selectionWhilePaused.avoids.values()][0].system_scope,
      'Display name can change');
    assert.equal([...selectionWhilePaused.avoids.values()][0].radio_system_key, 'p25:bee00:49f',
      'Avoid entries retain their exact system key for identity and removal');
    selectionWhilePaused.setScanListSelected('1', false);
    assert.equal(selectionWhilePaused.stopped, true, 'Removing the last list also stops a paused feed');
    assert.equal(selectionWhilePaused.paused, false);
    assert.equal(selectionWhilePaused.feedActive, false);

    const originalFetch = global.fetch;
    try {
      let finishDownload;
      global.fetch = () => new Promise((resolve) => { finishDownload = resolve; });
      const loading = pauseFixture();
      loading.current = loading.normalizeCall(overlap);
      loading.audioContext.decodeAudioData = async () => ({ duration: 10, sampleRate: 48_000 });
      const download = loading.loadCurrent();
      await loading.togglePause();
      finishDownload({ ok: true, arrayBuffer: async () => wave(8_000) });
      await download;
      assert.equal(loading.source, null, 'Download completion while paused must remain silent');
      assert.equal(loading.currentBuffer.duration, 10);
      assert.equal(loading.currentSourceSampleRate, 8_000,
        'Browser resampling must not replace the source WAV rate used by the Scanner spectrum');
      await loading.togglePause();
      assert.ok(loading.source, 'Resume starts audio that finished downloading while paused');
      await loading.togglePlayback();

      const stoppedDownload = pauseFixture();
      stoppedDownload.current = stoppedDownload.normalizeCall(overlap);
      stoppedDownload.audioContext.decodeAudioData = async () => ({ duration: 10 });
      const staleDownload = stoppedDownload.loadCurrent();
      await stoppedDownload.togglePause();
      await stoppedDownload.togglePlayback();
      finishDownload({ ok: true, arrayBuffer: async () => new ArrayBuffer(4) });
      await staleDownload;
      assert.equal(stoppedDownload.currentBuffer, null, 'Late downloads cannot resurrect stopped audio');
      assert.equal(stoppedDownload.source, null);

      global.fetch = async () => ({ ok: false, status: 410 });
      const expired = pauseFixture();
      expired.current = expired.normalizeCall(overlap);
      await expired.togglePause();
      await expired.loadCurrent();
      assert.equal(expired.current, null);
      assert.equal(expired.paused, true, 'Expired audio must not resume playback');
    } finally {
      global.fetch = originalFetch;
    }

    const resumeQueue = pauseFixture();
    await resumeQueue.togglePause();
    resumeQueue.enqueue(overlap);
    resumeQueue.loadCurrent = async function () { this.loadedId = this.current._callId; };
    await resumeQueue.togglePause();
    assert.equal(resumeQueue.loadedId, overlap.call_id, 'Resume while idle must start the queued call');

    const racing = pauseFixture();
    let finishSuspend;
    racing.audioContext.suspend = () => new Promise((resolve) => { finishSuspend = resolve; });
    const pendingPause = racing.togglePause();
    racing.audioContext.suspend = async function () { this.state = 'suspended'; };
    await racing.togglePlayback();
    finishSuspend();
    await pendingPause;
    assert.equal(racing.stopped, true);
    assert.equal(racing.paused, false);
    assert.equal(racing.ui.status.textContent, 'Ready', 'Stale pause completion must not undo Stop');

    const rejectedResume = pauseFixture();
    await rejectedResume.togglePause();
    rejectedResume.audioContext.resume = async () => { throw new Error('Audio unavailable'); };
    await rejectedResume.togglePause();
    assert.equal(rejectedResume.paused, true, 'Rejected resume must remain paused');

    assert.doesNotMatch(source, /Recent Calls|recentCalls|recentReplay|live_gap|conversationLanes|playbackOffset/);
    assert.match(source, /feedCursor/);
    assert.doesNotMatch(source, /recordSkippedCallNotice|skippedNotice|clearLossNotice/);
  } finally {
    Date.now = originalNow;
    global.window = originalWindow;
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
