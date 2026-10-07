'use strict';

const assert = require('node:assert/strict');
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const radioLabelsSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/radio-labels.js'), 'utf8');
const radioLabels = vm.runInNewContext(radioLabelsSource.replace(/^export .*;$/m, '') +
  '\n({p25ServingSystemKey});');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');

function functionSource(signature) {
  const start = application.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const openingBrace = application.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = openingBrace; index < application.length; index += 1) {
    if (application[index] === '{') depth += 1;
    else if (application[index] === '}' && --depth === 0) return application.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

class RuntimeNode {
  constructor(tag = 'div', className = '', text = '') {
    this.tag = tag;
    this.className = className;
    this.classList = {
      contains: (value) => this.className.split(/\s+/).includes(value),
      add: (...values) => { this.className = [...new Set([...this.className.split(/\s+/), ...values])].filter(Boolean).join(' '); }
    };
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.style = { setProperty: (key, value) => { this.style[key] = value; } };
    this.textContent = String(text ?? '');
    this.hidden = false;
    this.checked = false;
    this.disabled = false;
    this.indeterminate = false;
  }

  append(...children) {
    children.forEach((child) => this.children.push(child instanceof RuntimeNode ? child :
      new RuntimeNode('text', '', child)));
  }

  setAttribute(key, value) {
    this.attributes.set(key, String(value));
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  dispatch(type) {
    this.listeners.get(type)?.({ target: this });
  }

  querySelectorAll(selector) {
    return findAll(this, (candidate) => selector === '.live-filter-choice-group input' &&
      candidate.tag === 'input' && hasAncestorClass(this, candidate, 'live-filter-choice-group'));
  }
}

function findAll(root, predicate) {
  const matches = [];
  const visit = (candidate) => {
    if (predicate(candidate)) matches.push(candidate);
    candidate.children.forEach(visit);
  };
  visit(root);
  return matches;
}

function hasAncestorClass(root, target, className) {
  const visit = (candidate, matched) => candidate === target ? matched :
    candidate.children.some((child) => visit(child, matched || candidate.className.split(/\s+/).includes(className)));
  return visit(root, false);
}

let lastModalBody = null;
let modalOpenCount = 0;
const context = {
  ...radioLabels,
  activeReadOnlyModal: null,
  frequency: (value) => (Number(value) / 1_000_000).toFixed(5),
  node: (tag, className = '', text = '') => new RuntimeNode(tag, className, text),
  iconButton: (_iconId, label, className = '') => {
    const button = new RuntimeNode('button', className);
    button.type = 'button';
    button.title = label;
    button.setAttribute('aria-label', label);
    return button;
  },
  setIconButton: (button, _iconId, label) => {
    button.title = label;
    button.setAttribute('aria-label', label);
  },
  openReadOnlyModal: (_title, body, options) => {
    lastModalBody = body;
    modalOpenCount += 1;
    const state = {};
    const api = {
      state,
      close: () => {
        if (context.activeReadOnlyModal !== state) return;
        context.activeReadOnlyModal = null;
        options.cleanup?.();
      }
    };
    context.activeReadOnlyModal = state;
    return api;
  }
};
vm.createContext(context);
vm.runInContext([
  "const LIVE_DETAIL_SELECTION_KINDS = Object.freeze({ CONTROL: 'CONTROL', EXACT: 'EXACT', CONVENTIONAL: 'CONVENTIONAL' });",
  "const LIVE_DETAIL_CONTROL_ROLES = new Set(['CONFIGURED_CONTROL', 'CURRENT_CONTROL', 'ALTERNATE_CONTROL']);",
  functionSource('function liveDetailSelection(tableValue, row, bindingRow = row)'),
  functionSource('function liveDetailViewSelection(tableValue, rowSelection)'),
  functionSource('function liveDetailTransportParameters(selection, includeTimeslot = false)'),
  functionSource('function liveConventionalScopeMatchesSelection(subscriptionId, source)'),
  functionSource('function liveDetailCapturedRowKey(value, idField)'),
  functionSource('function liveDetailCaptureRow(values, order, value, idField, timeField, limit)'),
  functionSource('function liveCurrentControlRow(tableValue)'),
  functionSource('function liveDetailRowSelection(tableValue, row)'),
  functionSource('function liveDetailSelectionAfterRowsChanged(tableValue, selection)'),
  functionSource('function liveDetailSelectionDelta(previous, next)'),
  functionSource('function liveMessageTransportChanged(previous, next)'),
  functionSource('function liveMessageSourceMatchesSelection(selection, subscriptionId, source)'),
  functionSource('function liveMessageMatchesSelection(selection, message)'),
  functionSource('function liveEventMatchesSelection(selection, event)'),
  functionSource('function liveEventScopeMatchesSelection(selection, subscriptionId, source)'),
  functionSource('function liveChannelStateMatchesSelection(selection, subscriptionId, source)'),
  functionSource('function liveDetailText(value)'),
  functionSource('function liveDetailFilterCatalog(value)'),
  'let liveDetailFilterSequence = 0;',
  functionSource('function liveDetailFilterModel(options = {})'),
  functionSource('function aliasModalFooter(...controls)'),
  functionSource('function liveDetailFilterController(options)')
].join('\n'), context);

const catalogV1 = {
  signature: 'messages-v1',
  groups: [{
    key: 'message/root', label: 'P25 Phase 1 Messages', children: [
      { key: 'message/root/a', label: 'Header Messages', children: [] },
      { key: 'message/root/b', label: 'Trunking Messages', children: [] }
    ]
  }],
  timeslots: [1, 2]
};
const model = context.liveDetailFilterModel({ timeslots: true, validity: true });

// The complete catalog is available before the first row is received.
assert.equal(model.setCatalog(catalogV1), 'initial');
assert.deepEqual(Array.from(model.catalog().leafKeys), ['message/root/a', 'message/root/b']);
assert.equal(model.enabledLeafCount(), 2);
const firstCatalog = model.catalog();

model.setLeaves(['message/root/a'], false);
model.setTimeslot(2, false);
model.setValidity('invalid', false);
model.setSearch('needle');
assert.equal(model.matchesLeaf('message/root/a'), false);
assert.equal(model.matchesLeaf('message/root/b'), true);
assert.equal(model.matchesTimeslot(1), true);
assert.equal(model.matchesTimeslot(2), false);
assert.equal(model.matchesValidity(true), true);
assert.equal(model.matchesValidity(false), false);
assert.equal(model.query(), 'needle');

// A repeated immutable signature and a temporary unbound/null catalog preserve state and identity.
const sameSignatureDifferentObject = JSON.parse(JSON.stringify(catalogV1));
sameSignatureDifferentObject.groups[0].label = 'A label that must not replace the bound catalog';
assert.equal(model.setCatalog(sameSignatureDifferentObject), 'same');
assert.strictEqual(model.catalog(), firstCatalog);
assert.equal(model.matchesLeaf('message/root/a'), false);
assert.equal(model.query(), 'needle');
assert.equal(model.setCatalog(null), 'ignored');
assert.strictEqual(model.catalog(), firstCatalog);

// Incoming rows only consult the model. They cannot mutate or shrink the catalog.
model.resetFilters();
model.setLeaves(['message/root/a'], false);
const rows = Array.from({ length: 600 }, (_, index) => {
  const id = 599 - index;
  return { id, filter_key: id % 2 === 0 ? 'message/root/b' : 'message/root/a' };
});
const beforeRows = model.catalog();
const matching = rows.filter((row) => model.matchesLeaf(row.filter_key)).slice(0, 200);
assert.equal(matching.length, 200);
assert.equal(matching[0].id, 598);
assert.equal(matching[199].id, 200);
assert.equal(rows.slice(0, 200).filter((row) => model.matchesLeaf(row.filter_key)).length, 100);
assert.strictEqual(model.catalog(), beforeRows);
assert.equal(model.catalog().leafKeys.length, 2);

// Invalid or duplicate node keys fail closed and cannot disturb a working catalog.
const duplicateCatalog = {
  signature: 'bad', timeslots: [], groups: [{ key: 'root', label: 'Root', children: [
    { key: 'duplicate', label: 'One', children: [] },
    { key: 'duplicate', label: 'Two', children: [] }
  ] }]
};
assert.equal(model.setCatalog(duplicateCatalog), 'ignored');
assert.strictEqual(model.catalog(), beforeRows);
assert.equal(model.setCatalog({ signature: 'bad', groups: [], timeslots: [] }), 'ignored');
assert.equal(model.setCatalog({ signature: 'bad', groups: [{ key: 'x', label: 'X' }], timeslots: [] }), 'ignored');

const catalogV2 = {
  signature: 'messages-v2',
  groups: [{ key: 'message/new', label: 'New Decoder', children: [
    { key: 'message/new/c', label: 'New Message', children: [] }
  ] }],
  timeslots: [3]
};
model.setSearch('stale');
model.setTimeslot(1, false);
assert.equal(model.setCatalog(catalogV2), 'changed');
assert.notStrictEqual(model.catalog(), beforeRows);
assert.deepEqual(Array.from(model.catalog().leafKeys), ['message/new/c']);
assert.equal(model.enabledLeafCount(), 1);
assert.equal(model.matchesLeaf('message/root/a'), false);
assert.equal(model.matchesLeaf('message/new/c'), true);
assert.equal(model.matchesTimeslot(1), false);
assert.equal(model.matchesTimeslot(3), true);
assert.equal(model.query(), 'stale');
assert.equal(model.setCatalog(catalogV1), 'changed');
assert.equal(model.matchesLeaf('message/root/a'), false);
assert.equal(model.matchesLeaf('message/root/b'), true);
assert.equal(model.matchesTimeslot(1), false);
assert.equal(model.matchesTimeslot(2), true);
assert.equal(model.query(), 'stale');
model.resetFilters();
assert.equal(model.enabledLeafCount(), 2);
assert.equal(model.enabledTimeslotCount(), 2);
assert.equal(model.query(), '');

// The actual compact UI enables from the source catalog and renders every option with zero captured rows.
const controller = context.liveDetailFilterController({ noun: 'messages', timeslots: true, validity: true });
const trigger = controller.element.children[0];
const compactSummary = controller.element.children[1];
assert.equal(trigger.disabled, true);
assert.equal(controller.setCatalog(catalogV1), 'initial');
assert.equal(trigger.disabled, false);
assert.equal(compactSummary.textContent, 'All Messages');
trigger.dispatch('click');
assert.equal(modalOpenCount, 1);
const originalModalBody = lastModalBody;
assert.equal(findAll(originalModalBody,
  (candidate) => candidate.tag === 'footer' && candidate.classList.contains('ui-modal-footer')).length, 1);
const originalLeafItems = findAll(originalModalBody,
  (candidate) => candidate.className.split(/\s+/).includes('leaf'));
assert.equal(originalLeafItems.length, 2);

const firstLeafInput = findAll(originalLeafItems[0], (candidate) => candidate.tag === 'input')[0];
firstLeafInput.checked = false;
firstLeafInput.dispatch('change');
assert.equal(controller.matchesLeaf('message/root/a'), false);
const rootBranch = findAll(originalModalBody,
  (candidate) => candidate.className.split(/\s+/).includes('branch'))[0];
const rootDisclosure = findAll(rootBranch,
  (candidate) => candidate.className.split(/\s+/).includes('live-filter-expand'))[0];
const rootChildren = findAll(rootBranch,
  (candidate) => candidate.className.split(/\s+/).includes('live-filter-tree-children'))[0];
assert.equal(rootDisclosure.attributes.get('aria-expanded'), 'true');
assert.equal(rootDisclosure.attributes.get('aria-controls'), rootChildren.id);
assert.equal(rootDisclosure.attributes.get('aria-label'), 'Collapse P25 Phase 1 Messages');
assert.equal(rootDisclosure.title, 'Collapse P25 Phase 1 Messages');
assert.equal(rootChildren.hidden, false);
rootDisclosure.dispatch('click');
assert.equal(rootDisclosure.attributes.get('aria-expanded'), 'false');
assert.equal(rootDisclosure.attributes.get('aria-label'), 'Expand P25 Phase 1 Messages');
assert.equal(rootDisclosure.title, 'Expand P25 Phase 1 Messages');
assert.equal(rootChildren.hidden, true);
const rootInput = findAll(rootBranch, (candidate) => candidate.tag === 'input')[0];
const rootCount = findAll(rootBranch,
  (candidate) => candidate.className.split(/\s+/).includes('live-filter-node-count'))[0];
assert.equal(rootInput.indeterminate, true);
assert.equal(rootCount.textContent, '1/2');
assert.equal(findAll(originalLeafItems[0],
  (candidate) => candidate.className.split(/\s+/).includes('live-filter-node-count')).length, 0);
assert.equal(controller.setCatalog(JSON.parse(JSON.stringify(catalogV1))), 'same');
assert.strictEqual(lastModalBody, originalModalBody);
assert.equal(modalOpenCount, 1);
assert.equal(controller.matchesLeaf('message/root/a'), false);
assert.equal(controller.setCatalog(null), 'ignored');
assert.strictEqual(lastModalBody, originalModalBody);

assert.equal(controller.setCatalog(catalogV2), 'changed');
assert.equal(context.activeReadOnlyModal, null);
assert.equal(compactSummary.textContent, 'All Messages');
trigger.dispatch('click');
assert.equal(modalOpenCount, 2);
assert.equal(findAll(lastModalBody,
  (candidate) => candidate.className.split(/\s+/).includes('leaf')).length, 1);
assert.equal(controller.setCatalog(catalogV1), 'changed');
assert.equal(controller.matchesLeaf('message/root/a'), false);
assert.equal(typeof controller.resetForSelection, 'undefined');
controller.close();

// Control selections retain logical identity while their exact transport follows the active control row.
const site = { table_id: 'site-a', title: 'County · Downtown', configuration_id: 'site-config' };
const controlA = context.liveDetailSelection(site, {
  key: 'control-a', role: 'CURRENT_CONTROL', frequency_hz: 851_012_500
});
const controlB = context.liveDetailSelection(site, {
  key: 'control-b', role: 'CURRENT_CONTROL', frequency_hz: 852_012_500
});
assert.equal(controlA.kind, 'CONTROL');
assert.equal(controlA.logicalKey, controlB.logicalKey);
assert.notEqual(controlA.transportKey, controlB.transportKey);
assert.deepEqual(JSON.parse(JSON.stringify(context.liveDetailSelectionDelta(controlA, controlB))), {
  logicalChanged: false, transportChanged: true
});
assert.equal(controlB.bindingFrequencyHz, 852_012_500);
const remoteControl = context.liveDetailSelection({ ...site, remote_origin: { remote: true } }, {
  key: 'control-a', role: 'CURRENT_CONTROL', frequency_hz: 851_012_500
});
assert.equal(remoteControl.remote, true);
assert.deepEqual(JSON.parse(JSON.stringify(context.liveDetailSelectionDelta(controlA, remoteControl))), {
  logicalChanged: true, transportChanged: false
}, 'Remote provenance changes refresh diagnostic availability without retuning a channel');
const currentRow = { key: 'control-b', role: 'CURRENT_CONTROL', frequency_hz: 852_012_500 };
const alternateRow = { key: 'alternate', role: 'ALTERNATE_CONTROL', frequency_hz: 853_012_500 };
const alternateIntent = context.liveDetailRowSelection({
  ...site, control_active: true, rows: [alternateRow, currentRow]
}, alternateRow);
assert.equal(alternateIntent.logicalKey, controlB.logicalKey);
assert.equal(alternateIntent.transportKey, controlB.transportKey);
assert.equal(alternateIntent.rowKey, 'control-b');
const inactiveControlIntent = context.liveDetailRowSelection({
  ...site, control_active: false, rows: [alternateRow, currentRow]
}, alternateRow);
assert.equal(inactiveControlIntent.logicalKey, controlB.logicalKey);
assert.equal(inactiveControlIntent.bindingFrequencyHz, null);
assert.equal(inactiveControlIntent.rowKey, null);
const waitingControl = context.liveDetailSelection(site, controlA, null);
assert.equal(waitingControl.kind, 'CONTROL');
assert.equal(waitingControl.logicalKey, controlA.logicalKey);
assert.equal(waitingControl.bindingFrequencyHz, null);
assert.equal(waitingControl.rowKey, null);
const controlLost = context.liveDetailSelectionAfterRowsChanged({
  ...site, control_active: false, rows: []
}, controlA);
assert.notEqual(controlLost, null);
assert.equal(controlLost.logicalKey, controlA.logicalKey);
assert.equal(controlLost.bindingFrequencyHz, null);
assert.equal(controlLost.rowKey, null);
assert.deepEqual(JSON.parse(JSON.stringify(context.liveDetailSelectionDelta(controlA, controlLost))), {
  logicalChanged: false, transportChanged: true
});
const controlRotated = context.liveDetailSelectionAfterRowsChanged({
  ...site, control_active: true, rows: [currentRow]
}, controlLost);
assert.equal(controlRotated.logicalKey, controlA.logicalKey);
assert.equal(controlRotated.bindingFrequencyHz, 852_012_500);
assert.equal(controlRotated.rowKey, 'control-b');
assert.deepEqual(JSON.parse(JSON.stringify(context.liveDetailSelectionDelta(controlLost, controlRotated))), {
  logicalChanged: false, transportChanged: true
});
const directlyRotated = context.liveDetailSelectionAfterRowsChanged({
  ...site, control_active: true, rows: [currentRow]
}, controlA);
assert.equal(directlyRotated.logicalKey, controlA.logicalKey);
assert.equal(directlyRotated.transportKey, controlB.transportKey);

const scopedSite = { ...site, radio_system_key: 'p25:bee00:49f' };
const scopedControl = context.liveDetailSelection(scopedSite, currentRow);
assert.equal(scopedControl.radioSystemKey, 'p25:bee00:49f');
const changedSystemControl = context.liveDetailSelection({ ...scopedSite, radio_system_key: 'p25:bee00:348' }, currentRow);
assert.deepEqual(JSON.parse(JSON.stringify(context.liveDetailSelectionDelta(scopedControl, changedSystemControl))), {
  logicalChanged: true, transportChanged: false
}, 'A receiving-system change clears captured events even when the channel frequency stays the same.');
const conventionalAggregate = { ...scopedSite, table_id: 'conventional' };
assert.equal(context.liveDetailSelection(conventionalAggregate, currentRow).radioSystemKey, '',
  'A conventional channel must not inherit a system from the combined conventional table.');
const conventionalSource = { ...currentRow, source_entity_ref: { kind: 'radio', radio_system_key: 'p25:bee00:348' },
  source_home_system_entity_ref: { kind: 'radio_system', radio_system_key: 'p25:00001:047' } };
assert.equal(context.liveDetailSelection(conventionalAggregate, conventionalSource).radioSystemKey, 'p25:bee00:348',
  'A conventional row can use its verified receiving scope, without borrowing its radio home system.');

// Traffic and conventional rows remain exact even if legacy display tags suggest control activity.
const voice = context.liveDetailSelection(site, {
  key: 'voice-a', role: 'TRAFFIC', tags: ['CURRENT_CONTROL'], frequency_hz: 853_012_500, timeslot: 2
});
assert.equal(voice.kind, 'EXACT');
assert.equal(voice.bindingFrequencyHz, 853_012_500);
assert.equal(voice.bindingTimeslot, 2);
assert.equal(context.liveDetailSelectionAfterRowsChanged({ ...site, rows: [] }, voice), null);
const refreshedVoice = context.liveDetailSelectionAfterRowsChanged({
  ...site, rows: [{
    key: 'voice-a', role: 'TRAFFIC', frequency_hz: 853_012_500, timeslot: 2, lcn: '1-101'
  }]
}, voice);
assert.equal(refreshedVoice.logicalKey, voice.logicalKey);
assert.equal(refreshedVoice.rowKey, voice.rowKey);
const conventional = context.liveDetailSelection({
  table_id: 'conventional', title: 'Conventional', configuration_id: 'channel-config'
}, { key: 'channel-a', role: 'CONVENTIONAL', frequency_hz: 155_730_000 });
assert.equal(conventional.kind, 'EXACT');
assert.equal(conventional.logicalKey, 'EXACT:channel-config:155730000:');
assert.equal(context.liveDetailSelection({ table_id: 'conventional' }, {
  key: 'remote-conventional', configuration_id: 'channel-config', role: 'CONVENTIONAL',
  frequency_hz: 155_730_000, remote_origin: { remote: true }
}).remote, true, 'Mixed Live lists retain the remote flag on individual rows');
const sameFrequencyDifferentConfiguration = context.liveDetailSelection({
  table_id: 'conventional', title: 'Conventional', configuration_id: 'other-channel-config'
}, { key: 'channel-b', role: 'CONVENTIONAL', frequency_hz: 155_730_000 });
assert.notEqual(conventional.logicalKey, sameFrequencyDifferentConfiguration.logicalKey);
assert.notEqual(conventional.transportKey, sameFrequencyDifferentConfiguration.transportKey);
assert.deepEqual(JSON.parse(JSON.stringify(context.liveDetailSelectionDelta(
  conventional, sameFrequencyDifferentConfiguration))), { logicalChanged: true, transportChanged: true });
const subscriptionId = 'selection-subscription';
assert.equal(context.liveMessageTransportChanged(controlA, controlB), true);
assert.equal(context.liveMessageTransportChanged(voice, { ...voice, bindingTimeslot: 1 }), false);
assert.equal(context.liveMessageTransportChanged(conventional, sameFrequencyDifferentConfiguration), true);
assert.equal(context.liveMessageSourceMatchesSelection(voice, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, subscription_id: subscriptionId
}), true);
assert.equal(context.liveMessageSourceMatchesSelection(voice, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, subscription_id: 'stale-subscription'
}), false);
assert.equal(context.liveMessageSourceMatchesSelection(voice, subscriptionId, {
  configuration_id: 'other-site-config', frequency_hz: 853_012_500, subscription_id: subscriptionId
}), false);
assert.equal(context.liveMessageSourceMatchesSelection(voice, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 854_012_500, subscription_id: subscriptionId
}), false);
// A source state missed while hidden cannot pre-arm a reopened A subscription or admit queued B messages.
const reopenedSubscriptionId = 'reopened-a-subscription';
let reopenedTransportReady = false;
const confirmReopenedMessageSource = (source) => {
  reopenedTransportReady = false;
  if (context.liveMessageSourceMatchesSelection(controlA, reopenedSubscriptionId, source)) {
    reopenedTransportReady = true;
  }
};
confirmReopenedMessageSource({
  configuration_id: 'site-config', frequency_hz: 852_012_500, subscription_id: subscriptionId
});
assert.equal(reopenedTransportReady, false);
confirmReopenedMessageSource({
  configuration_id: 'site-config', frequency_hz: 851_012_500, subscription_id: reopenedSubscriptionId
});
assert.equal(reopenedTransportReady, true);

// Site events remain configuration-wide, while exact selections reject buffered events from another frequency/slot.
assert.equal(context.liveEventMatchesSelection(controlB, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, timeslot: 2
}), true);
assert.equal(context.liveEventMatchesSelection(voice, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, timeslot: 2
}), true);
assert.equal(context.liveEventMatchesSelection(voice, {
  configuration_id: 'site-config', frequency_hz: 854_012_500, timeslot: 2
}), false);
assert.equal(context.liveEventMatchesSelection(voice, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, timeslot: 1
}), false);
assert.equal(context.liveEventMatchesSelection(voice, {
  configuration_id: 'other-site-config', frequency_hz: 853_012_500, timeslot: 2
}), false);
assert.equal(context.liveEventMatchesSelection(conventional, {
  configuration_id: 'channel-config', frequency_hz: 155_730_000, timeslot: 1
}), true);
assert.equal(context.liveEventScopeMatchesSelection(controlB, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: null, timeslot: null,
  subscription_id: subscriptionId
}), true);
assert.equal(context.liveEventScopeMatchesSelection(controlB, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 852_012_500, timeslot: null,
  subscription_id: subscriptionId
}), false);
assert.equal(context.liveEventScopeMatchesSelection(voice, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, timeslot: 2,
  subscription_id: subscriptionId
}), true);
assert.equal(context.liveEventScopeMatchesSelection(voice, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, timeslot: null,
  subscription_id: subscriptionId
}), false);
assert.equal(context.liveEventScopeMatchesSelection(voice, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, timeslot: 2,
  subscription_id: 'stale-subscription'
}), false);
assert.equal(context.liveChannelStateMatchesSelection(voice, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, timeslot: 2,
  subscription_id: subscriptionId
}), true);
assert.equal(context.liveChannelStateMatchesSelection(voice, subscriptionId, {
  configuration_id: 'site-config', frequency_hz: 853_012_500, timeslot: 2,
  subscription_id: 'stale-subscription'
}), false);
assert.equal(context.liveChannelStateMatchesSelection(conventional, subscriptionId, {
  configuration_id: 'other-channel-config', frequency_hz: 155_730_000,
  subscription_id: subscriptionId
}), false);

// Conventional without a row selects a bounded combined feed; Channel still needs an exact row.
const combined = context.liveDetailViewSelection({ table_id: 'conventional' }, null);
assert.equal(combined.kind, 'CONVENTIONAL');
assert.equal(combined.configurationId, undefined);
assert.deepEqual(JSON.parse(JSON.stringify(context.liveDetailTransportParameters(combined))), { scope: 'conventional' });
assert.strictEqual(context.liveDetailViewSelection({ table_id: 'conventional' }, conventional), conventional);
assert.equal(context.liveDetailViewSelection(site, null), null);
assert.equal(context.liveMessageTransportChanged(combined, conventional), true);
const combinedSource = { scope: 'CONVENTIONAL', subscription_id: subscriptionId };
for (const checker of [context.liveMessageSourceMatchesSelection, context.liveEventScopeMatchesSelection]) {
  assert.equal(checker(combined, subscriptionId, combinedSource), true);
  assert.equal(checker(combined, 'new-subscription', combinedSource), false);
  assert.equal(checker(combined, subscriptionId, { ...combinedSource, scope: 'CHANNEL' }), false);
  assert.equal(checker(combined, subscriptionId, { ...combinedSource, configuration_id: 'unexpected' }), false);
  assert.equal(checker(combined, subscriptionId, { ...combinedSource, frequency_hz: 155_730_000 }), false);
  assert.equal(checker(conventional, subscriptionId, combinedSource), false);
}
assert.equal(context.liveEventMatchesSelection(combined, { configuration_id: 'source-a', frequency_hz: 155_730_000 }), true);
assert.equal(context.liveEventMatchesSelection(combined, { frequency_hz: 155_730_000 }), false);

// Replaying sources in any order retains the newest combined rows. IDs from different origins never collide.
for (const [idField, timeField] of [['event_id', 'time_start_ms'], ['message_id', 'timestamp_ms']]) {
  const values = new Map(), order = [];
  const capture = (source, id, timestamp, text = '') => context.liveDetailCaptureRow(values, order,
    { configuration_id: source, [idField]: id, [timeField]: timestamp, text }, idField, timeField, 3);
  capture('source-a', 'shared', 100);
  capture('source-b', 'shared', 300);
  capture('source-a', 'middle', 200);
  capture('source-b', 'old', 50);
  assert.deepEqual(order, ['source-b:shared', 'source-a:middle', 'source-a:shared']);
  assert.equal(values.size, 3);
  capture('source-a', 'shared', 100, 'updated');
  assert.equal(values.get('source-a:shared').text, 'updated');
  assert.equal(order.length, 3);
  capture('source-a', 'shared', 400);
  assert.deepEqual(order, ['source-a:shared', 'source-b:shared', 'source-a:middle']);
  assert.equal(context.liveDetailCaptureRow(values, order, {}, idField, timeField, 3), false);
}
assert.equal(context.liveEventMatchesSelection(combined, { configuration_id: 'source-a', source_frequency_hz: 155_730_000 }), true);
assert.equal(context.liveMessageMatchesSelection(conventional, {
  configuration_id: 'channel-config', frequency_hz: 155_730_000, timeslot: 2
}), true);
assert.equal(context.liveMessageMatchesSelection(conventional, {
  configuration_id: 'different-channel', frequency_hz: 155_730_000
}), false);
assert.equal(context.liveMessageMatchesSelection(conventional, {
  configuration_id: 'channel-config', frequency_hz: 155_740_000
}), false);
