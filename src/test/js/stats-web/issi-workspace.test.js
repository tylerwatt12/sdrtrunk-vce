'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../../../stats-web/assets/app.js'), 'utf8');

function declaration(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing ${name}`);
  let quote = '', depth = 0;
  const open = source.indexOf('{', start);
  for (let index = open; index < source.length; index++) {
    const char = source[index];
    if (quote) {
      if (char === '\\') index++;
      else if (char === quote) quote = '';
    } else if (['\'', '"', '`'].includes(char)) quote = char;
    else if (char === '{') depth++;
    else if (char === '}' && --depth === 0) {
      return (source.slice(Math.max(0, start - 6), start).endsWith('async ') ? 'async ' : '') +
        source.slice(start, index + 1);
    }
  }
  throw new Error(`Unclosed ${name}`);
}

class Element {
  constructor(tag, className = '', text = '') {
    this.tag = tag; this.className = className; this.textContent = text;
    this.children = []; this.isConnected = true; this.listeners = {};
    this.classList = { add: (...values) => { this.className = [this.className, ...values].filter(Boolean).join(' '); } };
  }
  append(...values) { this.children.push(...values.filter(value => value !== null && value !== undefined)); }
  insertBefore(value, reference) { this.children.splice(this.children.indexOf(reference), 0, value); }
  replaceChildren(...values) { this.children = []; this.append(...values); }
  prepend(...values) { this.children.unshift(...values); }
  setAttribute(name, value) { this[name] = value; }
  addEventListener(name, handler) { this.listeners[name] = handler; }
}

function descendant(element, tag) {
  if (element?.tag === tag) return element;
  for (const child of element?.children || []) {
    const match = descendant(child, tag);
    if (match) return match;
  }
  return null;
}

function facts(element) {
  const result = descendant(element, 'dl');
  assert.ok(result, 'Expected identity or receiver status facts.');
  return result.values;
}

async function main() {
  const calls = [], intervals = [], timeouts = [], sections = [], renderedTables = [];
  const route = new URLSearchParams('configuration_id=site-7');
  const document = { hidden: false };
  const content = new Element('main');
  let current = true, failure = false;
  let state = { state: 'current', snapshot_at_ms: 100, last_confirmation_ms: 50,
    receiver_started_at_ms: 10, current_assignment_count: 5, meaningful_assignment_count: 1,
    observed_channel_count: 1 };
  let rows = [{ canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
    observed_working_id: 130001, confirmed_at_ms: 50, expires_at_ms: null, evidence: 'registration' }];
  const page = () => ({ rows: rows.slice(), limit: 100, offset: 0, total_count: rows.length,
    has_more: false, next_offset: null, current_state: { ...state } });
  const node = (...args) => new Element(...args);
  const fragment = (...children) => { const element = node('fragment'); element.append(...children); return element; };
  const context = {
    URLSearchParams, route, document, content, node, fragment,
    radioSystemApiPath: (key, suffix) => `/api/v1/radio-systems/${key}/${suffix}`,
    renderIsCurrent: () => current,
    apiPage: async (url, parameters) => {
      calls.push({ url, parameters });
      if (url.endsWith('/channels')) return { rows: [{ configuration_id: 'site-7', name: 'North' }] };
      if (failure) throw new Error('Connection lost');
      return page();
    },
    api: async (url) => { throw new Error(`Unexpected separate status request: ${url}`); },
    section: (title, child) => { const element = node('section', '', title); element.append(child); return element; },
    keyValues: values => { const element = node('dl'); element.values = values; return element; },
    metrics: values => { const element = node('metrics'); element.values = values; return element; },
    dateTime: value => `time:${value}`, number: value => String(value),
    tabs: (items, active) => ({ items, active }),
    currentHref: values => JSON.stringify(values),
    issiFilterToolbar: () => node('form'),
    pageParameters: values => ({ limit: 100, offset: 0, ...values }),
    SERVER_TABLE_DEFAULT_SORTS: { 'issi-current-assignments': 'confirmed_at', 'issi-recent-changes': 'changed_at' },
    browsingWorkflows: { pageRangeText: values => values.label },
    pager: () => { const element = node('pager'); element.update = value => { element.lastUpdate = value; }; return element; },
    table: (data, columns, empty, options) => {
      const result = { data, columns, empty, options }; renderedTables.push(result);
      options.controller.replaceRows = next => { result.data = next; };
      return result;
    },
    pageLifecycle: { requiresPageHandling: () => false },
    pageInterval: callback => { intervals.push(callback); },
    pageTimeout: callback => { timeouts.push(callback); },
    createAsyncSection: title => {
      const host = node('div'), element = node('section', '', title), titleActions = node('div');
      element.append(host, titleActions);
      const result = { host, element, titleActions, tableController: {},
        load: async (load, present) => {
          try { host.replaceChildren(present(await load())); return { state: 'ready' }; }
          catch (error) { host.replaceChildren(node('error')); return { state: 'error', error }; }
        } };
      sections.push(result); return result;
    },
    anchor: (label, href, className) => { const element = node('a', className, label); element.href = href; return element; },
    href: (view, values) => `${view}?${new URLSearchParams(Object.entries(values)
      .filter(([, value]) => value !== null && value !== undefined && value !== ''))}`,
    p25CanonicalSubscriber: row => row.canonical_identity,
    canonicalSubscriberText: row => `${row.wacn}.${row.system_id}.${row.subscriber_id}`,
    identifierNumber: value => value == null ? '' : String(value),
    semanticLabel: value => value,
    issiHomeSystemCell: row => row.home_system_name,
    issiObservedOnCell: row => row.observed_on?.name,
    issiSubscriberCell: row => row.alias_name,
    aliasLabel: row => row.alias_name || '',
    capabilityAllowed: () => true, ACCESS_CAPABILITIES: { RADIO: 'radio' },
    entityRefHref: reference => reference?.href || '',
    identitySummaryValue: (primary, secondary, target) => ({primary,secondary,target}),
    systemLabels: { systemName: () => '' },
    hex: (value, width) => Number(value).toString(16).toUpperCase().padStart(width, '0'),
    issiSubscriberColumns: () => [], issiForeignSystemColumns: () => [], issiBandColumns: () => [],
    render: async () => {}
  };
  const behavior = vm.runInNewContext([
    'issiStateLabel', 'issiEmptyMessage', 'issiStateContent', 'issiSectionTabs',
    'issiCurrentAssignmentColumns', 'issiRecentChangeColumns', 'renderRadioSystemIssi',
    'issiAssignmentIsOrdinaryLocal', 'radioCurrentAssignmentSection', 'p25IdentityEvidenceLabel', 'liveIdentityRenderKey',
    'issiSubscriberCell', 'issiHomeSystemCell', 'issiHomeSystemText'
  ].map(declaration).join('\n') + '\n({renderRadioSystemIssi,radioCurrentAssignmentSection,' +
    'issiStateLabel,issiEmptyMessage,issiAssignmentIsOrdinaryLocal,p25IdentityEvidenceLabel,' +
    'liveIdentityRenderKey,issiSubscriberCell,issiHomeSystemCell});', context);
  await behavior.renderRadioSystemIssi({ radio_system_key: 'p25:BEE00:348' }, { signal: {} });
  assert.equal(content.children[0].active, 'current-assignments', 'System ISSI must start with current receiver knowledge.');
  assert.equal(intervals.length, 1);
  const request = calls.find(call => call.url.endsWith('/current-assignments'));
  assert.equal(request.parameters.configuration_id, 'site-7', 'Evidence channel filter must be sent unchanged.');
  assert.equal(request.parameters.meaningful_only, true, 'System ISSI must omit unchanged ordinary local mappings.');
  assert.equal(renderedTables[0].options.serverSort, true);
  assert.equal(renderedTables[0].options.defaultSort, 'confirmed_at');
  assert.equal(renderedTables[0].data[0].observed_working_id, 130001);
  assert.equal(renderedTables[0].columns.some(column => column.id === 'alias-action'), false,
    'Public Current Assignments must not render administrator editing actions.');
  assert.equal(calls.length, 2, 'Current Assignments must request channels and one coherent rows/status page.');
  const initialSnapshotFacts = facts(content.children[1]);
  assert.equal(initialSnapshotFacts.find(([label]) => label === 'Last Updated')[1], 'time:100',
    'Receiver status must come from the same response as the displayed assignment rows.');
  const initialCounts = descendant(content.children[1], 'metrics').values;
  assert.equal(initialCounts.find(([label]) => label === 'ISSI Assignments')[1], 1,
    'The ISSI summary must count useful mappings rather than every ordinary local registration.');

  state = { ...state, snapshot_at_ms: 200 };
  await intervals[0]();
  const snapshotFacts = facts(content.children[1]);
  assert.equal(snapshotFacts.find(([label]) => label === 'Last Updated')[1], 'time:200');
  assert.equal(snapshotFacts.find(([label]) => label === 'Last Confirmed')[1], 'time:50',
    'Refreshing a snapshot must not manufacture fresh assignment confirmation.');
  document.hidden = true;
  const beforeHidden = calls.length;
  await intervals[0]();
  assert.equal(calls.length, beforeHidden, 'Hidden pages must not poll the receiver.');
  document.hidden = false;
  failure = true;
  await intervals[0]();
  assert.equal(renderedTables[0].data[0].observed_working_id, 130001, 'Refresh failure must preserve the displayed rows.');
  assert.match(sections[0].element.children.at(-1).textContent, /Could not update/);
  assert.equal(sections[0].element.children.at(-1).hidden, false);
  const staleCounts = descendant(content.children[1], 'metrics').values;
  assert.equal(staleCounts.find(([label]) => label === 'ISSI Assignments')[1], 0,
    'A failed refresh must not label retained rows as currently confirmed.');
  assert.equal(staleCounts.find(([label]) => label === 'Last Known Assignments')[1], 1);
  failure = false; rows = [];
  await intervals[0]();
  assert.equal(renderedTables[0].data.length, 0, 'Cleared receiver assignments must clear the current rows.');
  assert.equal(sections[0].element.children.at(-1).textContent, '');
  assert.equal(sections[0].element.children.at(-1).hidden, true);
  state = { ...state, meaningful_assignment_count: 0 };
  await intervals[0]();
  assert.match(renderedTables.at(-1).empty, /No foreign radios or different Working IDs to show/,
    'Known local-only evidence must not be presented as still waiting to learn assignments.');
  current = false;
  const beforeNavigation = calls.length;
  await intervals[0]();
  assert.equal(calls.length, beforeNavigation, 'A navigated-away page must not poll or repaint.');

  current = true;
  const canonical = { wacn: 0x92498, system_id: 0x926, subscriber_id: 34006 };
  state = { ...state, snapshot_at_ms: 300, meaningful_assignment_count: 1 };
  rows = [{ canonical_identity: canonical, observed_working_id: 901,
    confirmed_at_ms: 280, expires_at_ms: null, evidence: 'registration' }];
  const beforeDetail = calls.length;
  behavior.radioCurrentAssignmentSection({ radio_system_key: 'p25:BEE00:348', canonical_identity: canonical }, { signal: {} });
  await timeouts[0]();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, beforeDetail + 1, 'Radio detail must obtain its assignment and status together.');
  const detailRequest = calls.at(-1);
  assert.equal(detailRequest.url, '/api/v1/radio-systems/p25:BEE00:348/issi/current-assignments');
  assert.equal(detailRequest.parameters.home_wacn, canonical.wacn);
  assert.equal(detailRequest.parameters.home_system_id, canonical.system_id);
  assert.equal(detailRequest.parameters.subscriber_id, canonical.subscriber_id,
    'Radio detail must match the complete permanent identity, including a foreign home system.');
  assert.equal(detailRequest.parameters.meaningful_only, undefined,
    'Radio Info must retain confirmed ordinary local assignments.');
  const detail = sections.at(-1);
  const detailFacts = facts(detail.host);
  assert.equal(detailFacts.find(([label]) => label === 'Last Updated')[1], 'time:300');
  assert.equal(detailFacts.find(([label]) => label === 'Working ID')[1], '901');
  assert.equal(detail.host.children[0].children.at(-1).children.length, 2);
  await intervals[1]();
  assert.equal(detail.host.children[0].children.at(-1).children.length, 2,
    'Current/history drilldowns must remain available after a refresh.');
  rows = [{ ...rows[0], canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 34006 },
    observed_working_id: 34006 }];
  behavior.radioCurrentAssignmentSection({ radio_system_key: 'p25:BEE00:348',
    canonical_identity: rows[0].canonical_identity }, { signal: {} });
  await timeouts.at(-1)();
  await new Promise(resolve => setImmediate(resolve));
  const localDetail = sections.at(-1);
  const localFacts = facts(localDetail.host);
  assert.equal(localFacts.find(([label]) => label === 'Working ID')[1], 'Same as permanent radio ID');
  const localLink = localDetail.host.children[0].children.at(-1).children[0];
  assert.equal(localLink.textContent, 'ISSI Assignments');
  assert.equal(new URL(localLink.href, 'http://fixture.invalid').searchParams.has('q'), false,
    'Ordinary local assignments must link to the ISSI workspace without a filter that excludes themselves.');
  rows = [{ ...rows[0], canonical_identity: canonical }];
  await intervals[1]();
  assert.equal(facts(detail.host).find(([label]) => label === 'Working ID')[1], '34006',
    'Foreign subscribers with an equal numeric Working ID are still useful ISSI mappings.');
  const foreignLink = detail.host.children[0].children.at(-1).children[0];
  assert.equal(foreignLink.textContent, 'Current Assignments');
  assert.equal(new URL(foreignLink.href, 'http://fixture.invalid').searchParams.get('q'),
    `${canonical.wacn}.${canonical.system_id}.${canonical.subscriber_id}`);
  route.set('issi_view', 'recent-changes');
  state = { ...state, snapshot_at_ms: 400 };
  const beforeChanges = calls.length;
  const beforeChangesContent = content.children.length;
  await behavior.renderRadioSystemIssi({ radio_system_key: 'p25:BEE00:348' }, { signal: {} });
  assert.equal(calls.length, beforeChanges + 2, 'Recent Changes must request channels and one coherent rows/status page.');
  assert.equal(calls.at(-1).url, '/api/v1/radio-systems/p25:BEE00:348/issi/recent-changes');
  assert.equal(calls.at(-1).parameters.meaningful_only, true);
  const changesFacts = facts(content.children[beforeChangesContent + 1]);
  assert.equal(changesFacts.find(([label]) => label === 'Last Updated')[1], 'time:400',
    'Recent Changes must use the receiver status captured with its change rows.');
  assert.equal(behavior.issiStateLabel({ state: 'current', snapshot_stale: true }), 'Updates delayed');
  assert.equal(behavior.issiStateLabel({ state: 'needs_confirmation' }), 'Relearning assignments');
  const changeColumn = renderedTables.at(-1).columns.find(column => column.id === 'change');
  assert.equal(changeColumn.render({ change: 'needs_confirmation', change_reason: 'observation_gap',
    invalidation_scope: 'receiver' }), 'Observation gap');
  for (const reason of ['reset', 'contention', 'saturation']) {
    assert.equal(changeColumn.render({ change: 'needs_confirmation', change_reason: reason,
      invalidation_scope: 'receiver' }), 'Assignments cleared');
  }
  assert.equal(behavior.issiAssignmentIsOrdinaryLocal({ canonical_identity: canonical, observed_working_id: 34006 },
    'p25:bee00:348'), false, 'Matching numbers on a foreign home system do not make a mapping ordinary local.');
  assert.equal(behavior.p25IdentityEvidenceLabel({ source_identity_source: 'registration_mapping' }, 'source'), 'Confirmed Working ID assignment');
  assert.equal(behavior.p25IdentityEvidenceLabel({ source_identity_source: 'working_id' }, 'source'), 'Working ID only');
  assert.notEqual(behavior.liveIdentityRenderKey({ source_id: 501, source_identity_source: 'working_id' }, 'source'),
    behavior.liveIdentityRenderKey({ source_id: 501, source_identity_source: 'explicit_identity' }, 'source'),
    'A new identity evidence source must update the Live action dialog even when the number is unchanged.');
  assert.equal(behavior.issiSubscriberCell({invalidation_scope:'receiver'}), 'All ISSI assignments');
  assert.equal(behavior.issiSubscriberCell({invalidation_scope:'system'}), 'All assignments on this system');
  assert.equal(behavior.issiHomeSystemCell({invalidation_scope:'system'}), '—',
    'A system-wide observation gap must not invent an unknown subscriber or home system.');
  const named = behavior.issiSubscriberCell({canonical_identity:canonical,alias_name:'Engine 4'});
  assert.equal(named.primary, 'Engine 4');
  assert.equal(named.secondary, '34006', 'Home identity has its own column, so the Radio cell needs only its number.');
  assert.match(named.title, /34006/, 'The full address remains available on the cell.');
  const unnamed = behavior.issiSubscriberCell({canonical_identity:canonical});
  assert.equal(unnamed.primary, '34006');
  assert.equal(unnamed.secondary, '');
  assert.equal(behavior.issiHomeSystemCell({canonical_identity:canonical}).primary, '92498.926',
    'An unknown friendly system name must still show the exact home-system identity.');
  console.log('ISSI workspace behavior checks passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
