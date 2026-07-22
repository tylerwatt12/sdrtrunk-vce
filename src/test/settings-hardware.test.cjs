const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../stats-web/assets/settings-hardware.js'), 'utf8');
const context = {
  AbortController,
  URL,
  URLSearchParams,
  document: {},
  fetch: async () => { throw new Error('Unexpected fetch'); },
  queueMicrotask,
  window: {}
};
vm.runInNewContext(source, context, { filename: 'settings-hardware.js' });
const SettingsHardwareView = context.window.SettingsHardwareView;

function numeric(value, disabled = false) {
  return { valueAsNumber: value, disabled };
}

function rtlForm() {
  return {
    ppm: numeric(12),
    center: numeric(773.58125),
    minimum: numeric(3.18),
    maximum: numeric(1782.03),
    autoPpm: { checked: true },
    fixedCenter: { checked: false },
    device: {
      type: 'RTL_R8X',
      sampleRate: { input: { value: '2400000' } },
      rtlBiasT: { input: { checked: false } },
      rtlMasterGain: { input: { value: 'GAIN_327' } },
      rtlMixerGain: { input: { value: 'GAIN_105' } },
      rtlLnaGain: { input: { value: 'GAIN_222' } },
      rtlVgaGain: { input: { value: 'GAIN_210' } }
    }
  };
}

test('an unrelated gain save omits center frequency and manual PPM', () => {
  const view = {
    settings: { revision: 42 },
    settingsForm: rtlForm(),
    settingsDirtyFields: new Set(['rtlMasterGain'])
  };

  const body = SettingsHardwareView.prototype.settingsRequestBody.call(view);

  assert.equal(body.frequencyCorrectionPpm, null);
  assert.equal(body.centerFrequencyHz, null);
  assert.equal(body.rtlMasterGain, 'GAIN_327');
});

test('explicit center frequency and PPM edits are included', () => {
  const view = {
    settings: { revision: 43 },
    settingsForm: rtlForm(),
    settingsDirtyFields: new Set(['centerFrequencyHz', 'frequencyCorrectionPpm'])
  };

  const body = SettingsHardwareView.prototype.settingsRequestBody.call(view);

  assert.equal(body.frequencyCorrectionPpm, 12);
  assert.equal(body.centerFrequencyHz, 773_581_250);
});

test('poll refreshes clean controls without replacing a dirty value', () => {
  const dirtyPpm = { type: 'number', value: '7' };
  const cleanCenter = { type: 'number', value: '765' };
  const cleanAutomaticPpm = { type: 'checkbox', checked: false };
  const view = {
    settingsDirtyFields: new Set(['frequencyCorrectionPpm']),
    settingsForm: {
      fields: new Map([
        ['frequencyCorrectionPpm', { control: dirtyPpm, read: (value) => value.frequencyCorrectionPpm }],
        ['centerFrequencyHz', { control: cleanCenter, read: (value) => value.centerFrequencyHz }],
        ['autoPpm', { control: cleanAutomaticPpm, read: (value) => value.autoPpm }]
      ])
    },
    updateDeviceFieldVisibility() {}
  };

  SettingsHardwareView.prototype.updatePristineSettingsFields.call(view, {
    frequencyCorrectionPpm: 8.375,
    centerFrequencyHz: 766,
    autoPpm: true
  });

  assert.equal(dirtyPpm.value, '7');
  assert.equal(cleanCenter.value, '766');
  assert.equal(cleanAutomaticPpm.checked, true);
});

test('poll preserves the edit revision so concurrent changes still receive a conflict', async () => {
  context.document.visibilityState = 'visible';
  context.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ id: 'rtl', revision: 99, activeChannelCount: 3, editable: true })
  });
  const view = {
    closed: false,
    authenticated: true,
    selectedTunerId: 'rtl',
    settings: { id: 'rtl', revision: 42, activeChannelCount: 0 },
    settingsDirty: true,
    settingsDirtyFields: new Set(['rtlMasterGain']),
    settingsForm: {},
    mutationPending: false,
    settingsRequestController: null,
    settingsPollController: null,
    updatePristineSettingsFields() {},
    updateSettingsRuntimeState() {},
    syncSelectedTunerState() {},
    scheduleSettingsPoll() {}
  };

  await SettingsHardwareView.prototype.pollSelectedTunerSettings.call(view);

  assert.equal(view.settings.revision, 42);
  assert.equal(view.settings.activeChannelCount, 3);
});

test('receiver-only dirty fields are recognized when radio work starts', () => {
  const view = { settingsDirtyFields: new Set(['frequencyCorrectionPpm', 'rtlMasterGain']) };
  assert.equal(SettingsHardwareView.prototype.hasDirtyIdleOnlySettings.call(view), true);
  view.settingsDirtyFields = new Set(['rtlMasterGain']);
  assert.equal(SettingsHardwareView.prototype.hasDirtyIdleOnlySettings.call(view), false);
});

test('only a named form control marks settings dirty', () => {
  const save = { disabled: true };
  const message = { textContent: '' };
  const view = {
    settingsForm: { save, message },
    settingsDirtyFields: new Set(),
    settingsDirty: false
  };

  SettingsHardwareView.prototype.markSettingsDirty.call(view, { dataset: {} });
  assert.equal(view.settingsDirty, false);

  SettingsHardwareView.prototype.markSettingsDirty.call(view,
    { dataset: { settingsField: 'rtlMasterGain' } });
  assert.equal(view.settingsDirty, true);
  assert.deepEqual([...view.settingsDirtyFields], ['rtlMasterGain']);
  assert.equal(save.disabled, false);
  assert.equal(message.textContent, 'Unsaved changes');
});

test('disabling a receiver closes its locally owned spectrum before the request', async () => {
  let spectrumClosed = false;
  let requestObserved = false;
  context.fetch = async () => {
    assert.equal(spectrumClosed, true);
    requestObserved = true;
    return {
      ok: true,
      status: 200,
      json: async () => ({ id: 'rtl', revision: 2, enabled: false })
    };
  };
  const view = {
    closed: false,
    mutationPending: false,
    selectedTunerId: 'rtl',
    settings: { id: 'rtl', revision: 1, enabled: true, radioWorkActive: false },
    settingsDirty: false,
    settingsDirtyFields: new Set(),
    settingsEnabledButton: { disabled: false },
    settingsState: { className: '', textContent: '' },
    session: { csrfToken: 'test' },
    spectrumView: {},
    spectrumTunerId: 'rtl',
    closeSpectrum() {
      spectrumClosed = true;
      this.spectrumView = null;
    },
    setMutationPending(value) { this.mutationPending = value; },
    handleMutationAuthorizationFailure: async () => false,
    loadInventory: async () => {}
  };

  await SettingsHardwareView.prototype.changeEnabledState.call(view);

  assert.equal(requestObserved, true);
  assert.equal(view.settings.enabled, false);
});

test('an incomplete shutdown retries disable instead of enabling', async () => {
  let requestBody = null;
  context.fetch = async (url, options) => {
    requestBody = JSON.parse(options.body);
    return {
      ok: true,
      status: 200,
      json: async () => ({ id: 'airspy', revision: 3, enabled: false, shutdownIncomplete: false })
    };
  };
  const view = {
    closed: false,
    mutationPending: false,
    selectedTunerId: 'airspy',
    settings: {
      id: 'airspy', revision: 2, enabled: false, shutdownIncomplete: true, radioWorkActive: false
    },
    settingsDirty: false,
    settingsDirtyFields: new Set(),
    settingsEnabledButton: { disabled: false },
    settingsState: { className: '', textContent: '' },
    session: { csrfToken: 'test' },
    spectrumView: null,
    spectrumTunerId: null,
    setMutationPending(value) { this.mutationPending = value; },
    handleMutationAuthorizationFailure: async () => false,
    loadInventory: async () => {}
  };

  await SettingsHardwareView.prototype.changeEnabledState.call(view);

  assert.equal(requestBody.enabled, false);
});

test('an asynchronous receiver error keeps the disable recovery action available', () => {
  const controls = [];
  const view = {
    settings: {
      enabled: true, lifecycleQuiescing: true, shutdownIncomplete: false, radioWorkActive: false,
      editable: false, available: false, activeChannelCount: 0
    },
    settingsForm: {
      fields: new Map(),
      idleOnlyControls: [],
      form: { elements: controls },
      reset: {},
      save: {},
      message: { textContent: '' }
    },
    settingsSubtitle: { textContent: '' },
    settingsEnabledButton: { textContent: '', className: '', disabled: true },
    settingsRuntimeNotice: null,
    mutationPending: false,
    settingsDirty: false,
    integer: (value) => String(value)
  };

  SettingsHardwareView.prototype.updateSettingsRuntimeState.call(view);

  assert.equal(view.settingsEnabledButton.textContent, 'Disable receiver');
  assert.equal(view.settingsEnabledButton.disabled, false);
});
