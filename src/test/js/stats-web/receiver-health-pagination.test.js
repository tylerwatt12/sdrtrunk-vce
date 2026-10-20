'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

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

const context = { RECEIVER_HEALTH_RESOLVED_PAGE_SIZE: 5 };
vm.createContext(context);
vm.runInContext(`
  ${functionSource('function receiverHealthResolvedGroupKey(incident)')}
  ${functionSource('function receiverHealthResolvedGroups(incidents)')}
  ${functionSource('function receiverHealthResolvedIncidentKey(incident)')}
  ${functionSource('function receiverHealthSortedResolvedIncidents(incidents, sort)')}
  ${functionSource('function receiverHealthResolvedPage(incidents, sort, requestedPage)')}
  globalThis.resolvedPage = receiverHealthResolvedPage;
  globalThis.resolvedGroups = receiverHealthResolvedGroups;
  globalThis.resolvedIncidentKey = receiverHealthResolvedIncidentKey;
`, context);

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

function incident(id, resolvedAt, title = `Alert ${id}`) {
  return {
    occurrence_id: id,
    title,
    code: `code-${id}`,
    scope: `scope-${id}`,
    resolved_at_ms: resolvedAt
  };
}

const incidents = Array.from({ length: 12 }, (_, index) => incident(index + 1, index + 1));
const first = plain(context.resolvedPage(incidents, 'recent', 0));
const second = plain(context.resolvedPage(incidents, 'recent', 1));
const third = plain(context.resolvedPage(incidents, 'recent', 2));

assert.deepEqual(first.rows.map((row) => row.occurrence_id), [12, 11, 10, 9, 8]);
assert.deepEqual(second.rows.map((row) => row.occurrence_id), [7, 6, 5, 4, 3]);
assert.deepEqual(third.rows.map((row) => row.occurrence_id), [2, 1]);
assert.deepEqual({ page: first.page, page_count: first.page_count, total_count: first.total_count,
  offset: first.offset, limit: first.limit, has_more: first.has_more },
{ page: 0, page_count: 3, total_count: 12, offset: 0, limit: 5, has_more: true });
assert.equal(third.has_more, false);

assert.equal(context.resolvedPage(incidents, 'recent', 99).page, 2,
  'A page above the available range must clamp to the final page.');
assert.equal(context.resolvedPage(incidents, 'recent', -4).page, 0,
  'A negative page must clamp to the first page.');
assert.equal(context.resolvedPage(incidents, 'recent', Number.NaN).page, 0,
  'A non-number page must clamp to the first page.');

const sixRows = plain(context.resolvedPage(incidents.slice(0, 6), 'recent', 2));
assert.equal(sixRows.page, 1, 'Shrinking to six rows must clamp page three to page two.');
assert.deepEqual(sixRows.rows.map((row) => row.occurrence_id), [1]);
const fiveRows = plain(context.resolvedPage(incidents.slice(0, 5), 'recent', 1));
assert.equal(fiveRows.page, 0, 'Shrinking to five rows must clamp to the only page.');
assert.equal(fiveRows.rows.length, 5);

const byType = [
  incident(1, 1, 'Zulu'), incident(2, 2, 'Echo'), incident(3, 3, 'Alpha'),
  incident(4, 4, 'Hotel'), incident(5, 5, 'Charlie'), incident(6, 6, 'Foxtrot'),
  incident(7, 7, 'Bravo'), incident(8, 8, 'Golf'), incident(9, 9, 'Delta')
];
assert.deepEqual(plain(context.resolvedPage(byType, 'type', 0)).rows.map((row) => row.title),
  ['Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo'],
  'The complete resolved list must be sorted before the first five rows are selected.');

assert.deepEqual(plain(context.resolvedPage([], 'recent', 4)), {
  rows: [],
  page: 0,
  page_count: 0,
  total_count: 0,
  offset: 0,
  limit: 5,
  has_more: false
});

const repeated = [
  { ...incident('older', 100), code: 'control-stopped', configuration_id: 'county', scope: 'Original name',
    observed: 'Earlier evidence', severity: 'warning' },
  { ...incident('newer', 300), code: 'control-stopped', configuration_id: 'county', scope: 'Renamed channel',
    observed: 'Latest evidence', severity: 'critical' },
  { ...incident('other-channel', 200), code: 'control-stopped', configuration_id: 'north',
    scope: 'Renamed channel' },
  { ...incident('other-code', 400), code: 'usb-disconnected', configuration_id: 'county', scope: 'Renamed channel' }
];
const originalOrder = repeated.map(row => row.occurrence_id);
const groups = plain(context.resolvedGroups(repeated));
assert.deepEqual(groups.map(row => row.occurrence_id), ['other-code', 'newer', 'other-channel'],
  'History groups use the newest event and remain ordered by the most recent resolution.');
assert.deepEqual(groups[1].occurrences.map(row => row.occurrence_id), ['newer', 'older']);
assert.equal(groups[1].observed, 'Latest evidence');
assert.equal(groups[1].occurrences[1].observed, 'Earlier evidence',
  'Grouping must preserve the evidence and severity of every occurrence.');
assert.equal(groups[1].occurrences[1].severity, 'warning');
assert.deepEqual(repeated.map(row => row.occurrence_id), originalOrder, 'Grouping must not reorder source events.');
const stableGroup = context.resolvedGroups(repeated.slice(0, 1))[0];
assert.equal(context.resolvedIncidentKey(groups[1]), context.resolvedIncidentKey(stableGroup),
  'A channel group keeps its expansion key when a newer event or renamed channel appears.');
const scopeGroups = plain(context.resolvedGroups([
  { ...incident('first', 1), code: 'host-memory', scope: 'Computer A' },
  { ...incident('second', 2), code: 'host-memory', scope: 'Computer B' },
  { ...incident('third', 3), code: 'host-memory', scope: 'Computer A' }
]));
assert.equal(scopeGroups.length, 2, 'Issues without a channel ID remain separated by their exact scope.');
assert.deepEqual(scopeGroups[0].occurrences.map(row => row.occurrence_id), ['third', 'first']);
assert.deepEqual(plain(context.resolvedGroups([])), []);
const groupPage = plain(context.resolvedPage(context.resolvedGroups([
  ...repeated,
  ...Array.from({ length: 5 }, (_, index) => ({ ...incident(`distinct-${index}`, 500 + index),
    code: `distinct-${index}`, scope: 'Receiver' }))
]), 'recent', 1));
assert.equal(groupPage.total_count, 8, 'History pagination counts groups rather than individual events.');
assert.equal(groupPage.rows.length, 3);

context.number = (value) => String(value);
vm.runInContext(`
  ${functionSource('function receiverHealthText(value, fallback = \'—\')')}
  ${functionSource('function receiverHealthSeverity(value)')}
  ${functionSource('function receiverHealthCount(value, fallback = 0)')}
  ${functionSource('function normalizeReceiverHealthSnapshot(value)')}
  ${functionSource('function receiverHealthComputerFacts(computer)')}
  globalThis.computerFacts = receiverHealthComputerFacts;
  globalThis.normalizeHealth = normalizeReceiverHealthSnapshot;
`, context);

const computer = {
  application: 'VCE', version: 'nightly', update_track: 'nightly', update_build: '123456',
  build_timestamp: '2026-10-09T12:00:00.000+0000', operating_system: 'Test OS', operating_system_version: '1.2',
  architecture: 'aarch64', cpu_model: 'Example processor', processors_available: 8,
  installed_memory_bytes: 32 * 1073741824, jvm_memory_limit_bytes: 2 * 1073741824,
  java_version: '25.0.1', java_vendor: 'Example Java', application_uptime_ms: 93780000,
  hostname: 'must-not-be-rendered', network_address: 'must-not-be-copied'
};
const normalizedComputer = plain(context.normalizeHealth({ computer }).computer);
assert.equal(normalizedComputer.hostname, undefined);
assert.equal(normalizedComputer.network_address, undefined);
const facts = plain(context.computerFacts(normalizedComputer));
const factValue = (label, values = facts) => values.find(([name]) => name === label)?.[1];
assert.equal(factValue('Installed RAM'), '32.0 GiB');
assert.equal(factValue('VCE memory limit'), '2.0 GiB');
assert.equal(factValue('Application uptime'), '1d 2h 3m');
assert.equal(factValue('Logical processors available to VCE'), '8');
assert.equal(factValue('Operating system'), 'Test OS · 1.2');
const unavailableFacts = plain(context.computerFacts({ installed_memory_bytes: 'Unavailable',
  jvm_memory_limit_bytes: 2 * 1073741824, application_uptime_ms: -1, processors_available: Number.NaN,
  system_memory_bytes: 16 * 1073741824 }));
assert.equal(factValue('Installed RAM', unavailableFacts), 'Unavailable', 'Heap limit never substitutes for RAM.');
assert.equal(factValue('RAM available to the operating system', unavailableFacts), '16.0 GiB');
assert.equal(factValue('Application uptime', unavailableFacts), 'Unavailable');
assert.equal(factValue('Logical processors available to VCE', unavailableFacts), 'Unavailable');
assert.equal(factValue('Operating system', unavailableFacts), 'Unavailable');

function fakeNode(tag, className = '', textContent = '') {
  return { tag, className, textContent, children: [], dataset: {}, classList: { add() {} },
    append(...items) { this.children.push(...items); }, setAttribute() {} };
}
let copied = '';
let denyCopy = false;
let authorized = true;
context.node = fakeNode;
context.keyValues = (entries) => Object.assign(fakeNode('dl'), { entries: plain(entries) });
context.uiActionButton = (label, icon, click) => Object.assign(fakeNode('button', '', label), { click });
context.receiverHealthSection = (key, title, body, action) => ({ key, title, body, action });
context.navigator = { clipboard: { async writeText(value) {
  if (denyCopy) throw new Error('Denied');
  copied = value;
} } };
context.receiverHealthController = { authorized: () => authorized, pageHost: {},
  computerCopyBusy: false, computerCopyState: '', updatePage() {} };
vm.runInContext(`
  ${functionSource('function receiverHealthComputerOverview(snapshot)')}
  globalThis.computerOverview = receiverHealthComputerOverview;
`, context);

async function verifyComputerCopy() {
  const overview = context.computerOverview({ computer: normalizedComputer });
  assert.equal(overview.title, 'Computer & version');
  assert.equal(overview.action.textContent, 'Copy details');
  await overview.action.click();
  assert.match(copied, /Installed RAM: 32\.0 GiB/);
  assert.match(copied, /VCE memory limit: 2\.0 GiB/);
  assert.doesNotMatch(copied, /must-not-be-/);
  assert.equal(context.receiverHealthController.computerCopyState, 'success');
  denyCopy = true;
  await overview.action.click();
  assert.equal(context.receiverHealthController.computerCopyState, 'error');
  assert.equal(context.receiverHealthController.computerCopyBusy, false);
  const errorOverview = context.computerOverview({ computer: normalizedComputer });
  assert.match(errorOverview.body.children.at(-1).textContent, /The browser could not copy/);
  authorized = false;
  context.receiverHealthController.computerCopyState = '';
  await overview.action.click();
  assert.equal(context.receiverHealthController.computerCopyState, '', 'Unauthorized sessions cannot copy host details.');
}

verifyComputerCopy().catch((error) => { throw error; });
