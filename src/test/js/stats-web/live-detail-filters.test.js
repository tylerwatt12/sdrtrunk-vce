'use strict';

const assert = require('node:assert/strict');
const fs = require('fs');
const vm = require('vm');

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
  activeReadOnlyModal: null,
  node: (tag, className = '', text = '') => new RuntimeNode(tag, className, text),
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
  functionSource('function liveDetailText(value)'),
  functionSource('function liveDetailFilterCatalog(value)'),
  'let liveDetailFilterSequence = 0;',
  functionSource('function liveDetailFilterModel(options = {})'),
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
  timeslots: []
};
model.setSearch('stale');
assert.equal(model.setCatalog(catalogV2), 'changed');
assert.notStrictEqual(model.catalog(), beforeRows);
assert.equal(model.catalog().leafKeys[0], 'message/new/c');
assert.equal(model.enabledLeafCount(), 1);
assert.equal(model.query(), '');

model.setLeaves(['message/new/c'], false);
model.setSearch('selection');
model.resetForSelection();
assert.equal(model.catalog(), null);
assert.equal(model.enabledLeafCount(), 0);
assert.equal(model.query(), '');
assert.equal(model.setCatalog(catalogV1), 'initial');
assert.equal(model.enabledLeafCount(), 2);

// The actual compact UI enables from the source catalog and renders every option with zero captured rows.
const controller = context.liveDetailFilterController({ noun: 'messages', timeslots: true, validity: true });
const trigger = controller.element.children[0];
const compactSummary = controller.element.children[1];
assert.equal(trigger.disabled, true);
assert.equal(controller.setCatalog(catalogV1), 'initial');
assert.equal(trigger.disabled, false);
assert.equal(compactSummary.textContent, 'All messages');
trigger.dispatch('click');
assert.equal(modalOpenCount, 1);
const originalModalBody = lastModalBody;
const originalLeafItems = findAll(originalModalBody,
  (candidate) => candidate.className.split(/\s+/).includes('leaf'));
assert.equal(originalLeafItems.length, 2);

const firstLeafInput = findAll(originalLeafItems[0], (candidate) => candidate.tag === 'input')[0];
firstLeafInput.checked = false;
firstLeafInput.dispatch('change');
assert.equal(controller.matchesLeaf('message/root/a'), false);
const rootBranch = findAll(originalModalBody,
  (candidate) => candidate.className.split(/\s+/).includes('branch'))[0];
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
assert.equal(compactSummary.textContent, 'All messages');
trigger.dispatch('click');
assert.equal(modalOpenCount, 2);
assert.equal(findAll(lastModalBody,
  (candidate) => candidate.className.split(/\s+/).includes('leaf')).length, 1);
controller.resetForSelection();
assert.equal(trigger.disabled, true);
assert.equal(context.activeReadOnlyModal, null);
