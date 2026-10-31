const assert = require('node:assert/strict');
const test = require('node:test');
const { resolve } = require('node:path');
const { pathToFileURL } = require('node:url');

const controls = import(pathToFileURL(resolve('stats-web/assets/core/preference-controls.js')).href);
const schema = import(pathToFileURL(resolve('stats-web/assets/core/preference-schema.js')).href);

test('both status editors preserve current unknown codes while replacing their known choices', async () => {
  const { mergeStatusAlertDisabledCodes, receiverHealthDisabledCodesForSave } = await controls;
  const preferences = { health_alerts: { disabled_codes: ['future-disabled-alert', 'tuner-error'] } };
  const knownIds = ['tuner-error', 'usb-sample-loss'];
  const submittedDraft = ['stale-unknown-alert', 'usb-sample-loss'];
  const switches = new Map([
    ['tuner-error', { checked: true }],
    ['usb-sample-loss', { checked: false }]
  ]);
  const expected = ['future-disabled-alert', 'usb-sample-loss'];
  assert.deepEqual(mergeStatusAlertDisabledCodes(preferences.health_alerts.disabled_codes,
    knownIds, submittedDraft), expected);
  assert.deepEqual(receiverHealthDisabledCodesForSave(preferences, switches), expected);
  assert.deepEqual(preferences.health_alerts.disabled_codes, ['future-disabled-alert', 'tuner-error'],
    'Preparing a save cannot mutate the current profile.');
});

test('a newly available status switch replaces only its own saved choice', async () => {
  const { receiverHealthDisabledCodesForSave } = await controls;
  const preferences = { health_alerts: { disabled_codes: ['future-disabled-alert', 'tuner-error'] } };
  const switches = new Map([
    ['tuner-error', { checked: false }],
    ['future-disabled-alert', { checked: true }],
    ['new-alert', { checked: true }]
  ]);
  assert.deepEqual(receiverHealthDisabledCodesForSave(preferences, switches), ['tuner-error']);
});

test('shared click choices and burst limits agree with the strict stored preference contract', async () => {
  const { recordingClickModeChoices, targetBurstLimits } = await controls;
  const { defaults, validate } = await schema;
  for (const [mode] of recordingClickModeChoices) {
    const preferences = structuredClone(defaults);
    preferences.playback.recording_click_mode = mode;
    assert.equal(validate(preferences).playback.recording_click_mode, mode);
  }
  assert.equal(new Set(recordingClickModeChoices.map(([mode]) => mode)).size, recordingClickModeChoices.length);
  for (const value of [targetBurstLimits.minimum, targetBurstLimits.maximum]) {
    const preferences = structuredClone(defaults);
    preferences.playback.target_burst_limit = value;
    assert.equal(validate(preferences).playback.target_burst_limit, value);
  }
  for (const value of [targetBurstLimits.minimum - 1, targetBurstLimits.maximum + 1]) {
    const preferences = structuredClone(defaults);
    preferences.playback.target_burst_limit = value;
    assert.throws(() => validate(preferences), /playback.target_burst_limit/);
  }
});
