'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');

const applicationPath = path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/app.js'));
const source = fs.readFileSync(applicationPath, 'utf8');

function functionSource(name) {
  const signature = `function ${name}(`;
  let start = source.indexOf(signature);
  assert.notEqual(start, -1, `Missing ${name}`);
  if (source.slice(start - 6, start) === 'async ') start -= 6;
  const opening = source.indexOf('{', start);
  let depth = 0;
  let quote = '';
  for (let index = opening; index < source.length; index += 1) {
    const character = source[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
    } else if (['\'', '"', '`'].includes(character)) quote = character;
    else if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`Unclosed ${name}`);
}

class Element {
  constructor(tag, className = '', text = '') {
    this.tagName = tag.toUpperCase();
    this.textContent = text;
    this.children = [];
    this.listeners = new Map();
    this.attributes = new Map();
    this.disabled = false;
    this.hidden = false;
    this.value = '';
    const classes = new Set(className.split(/\s+/).filter(Boolean));
    this.classList = {
      add: (...values) => values.forEach((value) => classes.add(value)),
      remove: (...values) => values.forEach((value) => classes.delete(value)),
      contains: (value) => classes.has(value)
    };
  }
  append(...children) { this.children.push(...children); }
  addEventListener(name, listener) {
    if (!this.listeners.has(name)) this.listeners.set(name, []);
    this.listeners.get(name).push(listener);
  }
  async dispatch(name) {
    await Promise.all((this.listeners.get(name) || []).map((listener) =>
      listener({ preventDefault() {} })));
  }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  reportValidity() { return true; }
  get elements() {
    const controls = [];
    const visit = (element) => {
      if (['INPUT', 'SELECT', 'BUTTON', 'TEXTAREA'].includes(element.tagName)) controls.push(element);
      element.children?.forEach(visit);
    };
    this.children.forEach(visit);
    return controls;
  }
}

function modalHarness(createFormWorkflow, scenario = {}) {
  let identity = 'listener';
  const preferences = {
    presentation: { source_name_display: 'talker_alias', show_encryption_details: true },
    playback: { volume: 0.35 },
    scanner: { detail_mode: 'normal' }
  };
  const nodes = [];
  let modal = null;
  let form = null;
  let renderCount = 0;
  let focusCount = 0;
  let writeCount = 0;
  let allowRetry = null;
  const snapshot = () => ({ loaded: true, identity, preferences });
  const context = {
    userPreferenceController: { snapshot },
    createFormWorkflow,
    node: (tag, className, text) => {
      const element = new Element(tag, className, text);
      nodes.push(element);
      return element;
    },
    preferenceSelect: (name, choices, selected) => {
      const select = new Element('select');
      select.name = name;
      select.value = selected;
      select.choices = choices;
      nodes.push(select);
      return select;
    },
    formField: (label, control, help) => {
      const field = new Element('label', '', label);
      field.append(control, new Element('span', '', help));
      return field;
    },
    aliasModalFooter: (...controls) => {
      const footer = new Element('footer', 'ui-modal-footer');
      footer.append(...controls);
      return footer;
    },
    openReadOnlyModal: (title, body, options) => {
      form = body;
      modal = {
        title, options, dirty: false, busy: false, closeCount: 0, focused: null,
        ready: scenario.ready || Promise.resolve(true),
        setDirty: (value) => { modal.dirty = value; },
        setBusy: (value) => { modal.busy = value; },
        focus: (element) => { modal.focused = element; },
        close: () => {
          if (modal.busy || modal.dirty) return false;
          modal.closeCount += 1;
          return true;
        }
      };
      return modal;
    },
    updateUserPreferences: async (mutator, retry) => {
      writeCount += 1;
      allowRetry = retry;
      if (scenario.write) return scenario.write({ preferences, mutator });
      mutator(preferences);
      return preferences;
    },
    render: async () => { renderCount += 1; },
    document: { querySelector: () => ({ focus: () => { focusCount += 1; } }) }
  };
  vm.createContext(context);
  vm.runInContext(`${functionSource('openSourceNameSettings')};`, context);
  return {
    open: () => context.openSourceNameSettings('#source-name-settings'),
    setIdentity: (value) => { identity = value; },
    preferences,
    get modal() { return modal; },
    get form() { return form; },
    get select() { return nodes.find((node) => node.tagName === 'SELECT'); },
    get save() { return nodes.find((node) => node.type === 'submit'); },
    get feedback() { return nodes.find((node) => node.classList.contains('admin-form-message')); },
    get writeCount() { return writeCount; },
    get allowRetry() { return allowRetry; },
    get renderCount() { return renderCount; },
    get focusCount() { return focusCount; },
    async choose(mode) { this.select.value = mode; await this.form.dispatch('change'); },
    submit: async () => { await form.dispatch('submit'); }
  };
}

async function main() {
  const { formatSourceName } = await import(pathToFileURL(path.join(path.dirname(applicationPath),
    'core/source-names.js')).href);
  const { createFormWorkflow } = await import(pathToFileURL(path.join(path.dirname(applicationPath),
    'core/form-workflows.js')).href);
  let mode = 'talker_alias';
  const context = {
    activeUserPreferences: () => ({ presentation: { source_name_display: mode } }),
    formatSourceName,
    scannerDetailMode: 'normal',
    scannerMatchedScanLists: () => '',
    liveIdentityActionLink: (row, kind, text) => ({ row, kind, text })
  };
  vm.createContext(context);
  vm.runInContext(['sourceNameDisplayMode', 'liveAliasReferences', 'liveSourceName',
    'liveAliasValue', 'liveIdentityRenderKey', 'scannerSourceAlias', 'scannerCallRenderKey']
    .map(functionSource).join('\n'), context);

  const call = { source_alias: 'Engine 4', talker_alias: 'Portable 4', source_id: '44', call_id: 'call-1' };
  for (const [choice, expected] of [['talker_alias', 'Portable 4'], ['source_alias', 'Engine 4'],
    ['both', 'Engine 4 · Portable 4']]) {
    mode = choice;
    assert.equal(context.sourceNameDisplayMode(), choice);
    assert.equal(context.liveSourceName(call), expected, `Live respects ${choice}`);
    assert.equal(context.scannerSourceAlias(call), expected, `Scanner respects ${choice}`);
    const linked = context.liveAliasValue(call, 'source');
    assert.equal(linked.text, expected);
    assert.equal(linked.kind, 'source');
    assert.equal(linked.row, call, 'The chosen name keeps the original source drilldown identity');
    assert.equal(context.liveSourceName({ source_alias: ' Engine 4 ', talker_alias: ' ' }), 'Engine 4');
    assert.equal(context.scannerSourceAlias({ source_alias: ' ', talker_alias: ' Portable 4 ' }), 'Portable 4');
    assert.equal(context.liveSourceName({}), '');
    assert.equal(context.scannerSourceAlias(null), '');
  }
  mode = 'both';
  assert.equal(context.scannerSourceAlias({ source_alias: 'Engine 4', talker_alias: ' engine 4 ' }), 'Engine 4');
  const multipleAliases = { ...call, source_aliases: [
    { alias_id: 1, alias_list_id: 2, name: 'Engine 4' },
    { alias_id: 2, alias_list_id: 2, name: 'Command' },
    { alias_id: 3, alias_list_id: 2, name: 'engine 4' }
  ] };
  assert.equal(context.liveSourceName(multipleAliases), 'Engine 4, Command · Portable 4');
  assert.equal(context.liveSourceName({ ...multipleAliases, talker_alias: 'COMMAND' }), 'Engine 4, Command');
  mode = 'source_alias';
  assert.equal(context.liveSourceName(multipleAliases), 'Engine 4, Command');
  mode = 'talker_alias';
  assert.equal(context.liveSourceName(multipleAliases), 'Portable 4');
  assert.equal(context.liveSourceName({ ...call, source_aliases: [{ alias_id: 0, alias_list_id: 2,
    name: 'Invalid reference' }], talker_alias: '' }), 'Engine 4');

  const sourceKey = context.liveIdentityRenderKey(call, 'source');
  const targetKey = context.liveIdentityRenderKey(call, 'target');
  const scannerKey = context.scannerCallRenderKey(call, {}, null);
  mode = 'source_alias';
  assert.notEqual(context.liveIdentityRenderKey(call, 'source'), sourceKey,
    'A source-name preference change invalidates unchanged Live cell content');
  assert.equal(context.liveIdentityRenderKey(call, 'target'), targetKey,
    'A source-name change must not invalidate unrelated target cells');
  assert.notEqual(context.scannerCallRenderKey(call, {}, null), scannerKey,
    'An unchanged current Scanner call redraws when its name preference changes');
  assert.notEqual(context.scannerCallRenderKey(call, {}, null),
    context.scannerCallRenderKey({ ...call, source_alias: 'Engine 5' }, {}, null),
    'Late alias metadata must invalidate the Scanner call display');
  const column = functionSource('liveChannelsSection').match(/\{ id: 'source-alias',[\s\S]*?reconcileKey:/)?.[0];
  assert.ok(column, 'The Live Source column exists');
  assert.match(column, /sortValue: liveSourceName/, 'Sorting uses the same source name shown to the user');
  const rows = [{ source_alias: 'Zulu', talker_alias: 'Alpha' },
    { source_alias: 'Alpha', talker_alias: 'Zulu' }];
  assert.equal([...rows].sort((a, b) => context.liveSourceName(a).localeCompare(context.liveSourceName(b)))[0], rows[1]);
  mode = 'talker_alias';
  assert.equal([...rows].sort((a, b) => context.liveSourceName(a).localeCompare(context.liveSourceName(b)))[0], rows[0]);

  const saved = modalHarness(createFormWorkflow);
  await saved.open();
  assert.equal(saved.modal.focused, saved.select, 'The shared modal focuses its name choice once ready');
  assert.equal(saved.save.disabled, true, 'Saving is disabled before a choice changes');
  assert.deepEqual(JSON.parse(JSON.stringify(saved.select.choices.map((choice) => choice[0]))),
    ['talker_alias', 'source_alias', 'both']);
  await saved.choose('both');
  assert.equal(saved.modal.dirty, true);
  await saved.submit();
  assert.equal(saved.preferences.presentation.source_name_display, 'both');
  assert.equal(saved.preferences.presentation.show_encryption_details, true,
    'Saving the source choice preserves other presentation preferences');
  assert.equal(saved.preferences.playback.volume, 0.35);
  assert.equal(saved.allowRetry, false, 'The modal owns its explicit retry and conflict workflow');
  assert.equal(saved.modal.dirty, false);
  assert.equal(saved.modal.closeCount, 1);
  assert.equal(saved.renderCount, 1, 'Successful save refreshes My Settings once');
  assert.equal(saved.focusCount, 1, 'The settings action receives focus after refresh');

  const failed = modalHarness(createFormWorkflow, { write: () => { throw new Error('Offline'); } });
  await failed.open();
  await failed.choose('source_alias');
  await failed.submit();
  assert.equal(failed.select.value, 'source_alias', 'A failed save retains the user choice');
  assert.equal(failed.preferences.presentation.source_name_display, 'talker_alias');
  assert.equal(failed.save.disabled, false, 'The retained choice can be saved again');
  assert.equal(failed.modal.dirty, true);
  assert.equal(failed.modal.closeCount, 0);
  assert.match(failed.feedback.textContent, /Your choice is still here/);

  const conflict = modalHarness(createFormWorkflow, { write: ({ preferences }) => {
    preferences.presentation.source_name_display = 'source_alias';
    const error = new Error('Conflict');
    error.code = 'preference_conflict';
    throw error;
  } });
  await conflict.open();
  await conflict.choose('both');
  await conflict.submit();
  assert.equal(conflict.select.value, 'source_alias', 'A successful conflict reload shows the current saved choice');
  assert.equal(conflict.modal.dirty, false);
  assert.equal(conflict.save.disabled, true);
  assert.match(conflict.feedback.textContent, /saved choice was loaded/);

  const reloadFailure = modalHarness(createFormWorkflow, { write: () => {
    const error = new Error('Conflict');
    error.code = 'preference_conflict';
    error.reloadError = new Error('Offline');
    throw error;
  } });
  await reloadFailure.open();
  await reloadFailure.choose('both');
  await reloadFailure.submit();
  assert.equal(reloadFailure.select.value, 'both', 'A failed conflict reload preserves the draft');
  assert.equal(reloadFailure.save.disabled, false);
  assert.match(reloadFailure.feedback.textContent, /saved choice could not be loaded/);

  const switched = modalHarness(createFormWorkflow);
  await switched.open();
  await switched.choose('both');
  switched.setIdentity('another-listener');
  await switched.submit();
  assert.equal(switched.writeCount, 0, 'A former account draft cannot update the newly signed-in account');
  assert.equal(switched.save.disabled, true);
  assert.match(switched.feedback.textContent, /signed-in account changed/);

  let finishSave;
  const pending = modalHarness(createFormWorkflow, { write: async ({ mutator, preferences }) => {
    await new Promise((resolve) => { finishSave = resolve; });
    mutator(preferences);
  } });
  await pending.open();
  await pending.choose('both');
  const firstSubmit = pending.submit();
  assert.equal(pending.modal.busy, true);
  assert.equal(pending.modal.close(), false, 'A busy save keeps the modal open');
  assert.equal(pending.select.disabled, true);
  await pending.submit();
  assert.equal(pending.writeCount, 1, 'Duplicate submit must not write the preference twice');
  finishSave();
  await firstSubmit;
  assert.equal(pending.modal.busy, false);
  assert.equal(pending.modal.closeCount, 1);
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
