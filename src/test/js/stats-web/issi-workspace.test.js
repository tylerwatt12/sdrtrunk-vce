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
  }
  append(...values) { this.children.push(...values.filter(value => value !== null && value !== undefined)); }
  replaceChildren(...values) { this.children = []; this.append(...values); }
  prepend(...values) { this.children.unshift(...values); }
  setAttribute(name, value) { this[name] = value; }
  addEventListener(name, handler) { this.listeners[name] = handler; }
}

async function main() {
  const calls = [], intervals = [], timeouts = [], sections = [], renderedTables = [];
  const route = new URLSearchParams('configuration_id=site-7');
  const document = { hidden: false };
  const content = new Element('main');
  let current = true, failure = false;
  let state = { state: 'current', snapshot_at_ms: 100, last_confirmation_ms: 50,
    receiver_started_at_ms: 10, current_assignment_count: 1, observed_channel_count: 1 };
  let rows = [{ canonical_identity: { wacn: 0xBEE00, system_id: 0x348, subscriber_id: 501 },
    observed_working_id: 130001, confirmed_at_ms: 50, expires_at_ms: null, evidence: 'registration' }];
  const page = () => ({ rows: rows.slice(), limit: 100, offset: 0, total_count: rows.length,
    has_more: false, next_offset: null });
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
    api: async (url) => { calls.push({ url }); return state; },
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
    href: (view, values) => `${view}?${new URLSearchParams(values)}`,
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
    issiAliasAction: () => '',
    render: async () => {}
  };
  const behavior = vm.runInNewContext([
    'issiStateLabel', 'issiEmptyMessage', 'issiStateContent', 'issiSectionTabs',
    'issiCurrentAssignmentColumns', 'issiRecentChangeColumns', 'renderRadioSystemIssi',
    'radioCurrentAssignmentSection', 'p25IdentityEvidenceLabel', 'liveIdentityRenderKey',
    'issiSubscriberCell', 'issiHomeSystemCell', 'issiHomeSystemText'
  ].map(declaration).join('\n') + '\n({renderRadioSystemIssi,radioCurrentAssignmentSection,' +
    'issiStateLabel,p25IdentityEvidenceLabel,liveIdentityRenderKey,issiSubscriberCell,issiHomeSystemCell});', context);
  await behavior.renderRadioSystemIssi({ radio_system_key: 'p25:BEE00:348' }, { signal: {} });
  assert.equal(content.children[0].active, 'current-assignments', 'System ISSI must start with current receiver knowledge.');
  assert.equal(intervals.length, 1);
  const request = calls.find(call => call.url.endsWith('/current-assignments'));
  assert.equal(request.parameters.configuration_id, 'site-7', 'Evidence channel filter must be sent unchanged.');
  assert.equal(renderedTables[0].options.serverSort, true);
  assert.equal(renderedTables[0].options.defaultSort, 'confirmed_at');
  assert.equal(renderedTables[0].data[0].observed_working_id, 130001);

  state = { ...state, snapshot_at_ms: 200 };
  await intervals[0]();
  const snapshotFacts = content.children[1].children[0].children[0].children[1].values;
  assert.equal(snapshotFacts.find(([label]) => label === 'Snapshot Updated')[1], 'time:200');
  assert.equal(snapshotFacts.find(([label]) => label === 'Last Assignment Confirmation')[1], 'time:50',
    'Refreshing a snapshot must not manufacture fresh assignment confirmation.');
  document.hidden = true;
  const beforeHidden = calls.length;
  await intervals[0]();
  assert.equal(calls.length, beforeHidden, 'Hidden pages must not poll the receiver.');
  document.hidden = false;
  failure = true;
  await intervals[0]();
  assert.equal(renderedTables[0].data[0].observed_working_id, 130001, 'Refresh failure must preserve the displayed rows.');
  assert.match(content.children[3].textContent, /Refresh failed/);
  const staleContent = content.children[1].children[0].children[0];
  const staleCounts = staleContent.children.at(-1).values;
  assert.equal(staleCounts.find(([label]) => label === 'Confirmed Assignments')[1], 0,
    'A failed refresh must not label retained rows as currently confirmed.');
  assert.equal(staleCounts.find(([label]) => label === 'Last Snapshot Assignments')[1], 1);
  failure = false; rows = [];
  await intervals[0]();
  assert.equal(renderedTables[0].data.length, 0, 'Cleared receiver assignments must clear the current rows.');
  assert.equal(content.children[3].textContent, '');
  current = false;
  const beforeNavigation = calls.length;
  await intervals[0]();
  assert.equal(calls.length, beforeNavigation, 'A navigated-away page must not poll or repaint.');

  current = true;
  const canonical = { wacn: 0x92498, system_id: 0x926, subscriber_id: 34006 };
  behavior.radioCurrentAssignmentSection({ radio_system_key: 'p25:BEE00:348', canonical_identity: canonical }, { signal: {} });
  await timeouts[0]();
  await new Promise(resolve => setImmediate(resolve));
  const detailRequest = calls.at(-2);
  assert.equal(detailRequest.url, '/api/v1/radio-systems/p25:BEE00:348/issi/current-assignments');
  assert.equal(detailRequest.parameters.home_wacn, canonical.wacn);
  assert.equal(detailRequest.parameters.home_system_id, canonical.system_id);
  assert.equal(detailRequest.parameters.subscriber_id, canonical.subscriber_id,
    'Radio detail must match the complete permanent identity, including a foreign home system.');
  const detail = sections.at(-1);
  assert.equal(detail.host.children[0].children.at(-1).children.length, 2);
  await intervals[1]();
  assert.equal(detail.host.children[0].children.at(-1).children.length, 2,
    'Current/history drilldowns must remain available after a refresh.');
  assert.equal(behavior.issiStateLabel({ state: 'current', snapshot_stale: true }), 'Snapshot needs refresh');
  assert.match(behavior.p25IdentityEvidenceLabel({ source_identity_source: 'registration_mapping' }, 'source'), /confirmed working assignment/);
  assert.match(behavior.p25IdentityEvidenceLabel({ source_identity_source: 'working_id' }, 'source'), /not confirmed/);
  assert.notEqual(behavior.liveIdentityRenderKey({ source_id: 501, source_identity_source: 'working_id' }, 'source'),
    behavior.liveIdentityRenderKey({ source_id: 501, source_identity_source: 'explicit_identity' }, 'source'),
    'A new identity evidence source must update the Live action dialog even when the number is unchanged.');
  assert.equal(behavior.issiSubscriberCell({invalidation_scope:'receiver'}), 'All receiver mappings');
  assert.equal(behavior.issiSubscriberCell({invalidation_scope:'system'}), 'All mappings on this system');
  assert.equal(behavior.issiHomeSystemCell({invalidation_scope:'system'}), '—',
    'A system-wide observation gap must not invent an unknown subscriber or home system.');
  const named = behavior.issiSubscriberCell({canonical_identity:canonical,alias_name:'Engine 4'});
  assert.equal(named.primary, 'Engine 4');
  assert.match(named.secondary, /34006/);
  assert.equal(behavior.issiHomeSystemCell({canonical_identity:canonical}).primary, '92498.926',
    'An unknown friendly system name must still show the exact home-system identity.');
  console.log('ISSI workspace behavior checks passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
