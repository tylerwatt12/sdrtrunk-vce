'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const application = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/app.js'), 'utf8');

// Table headings, expanded column names, charts, and help must use the same public vocabulary.
assert.doesNotMatch(application,
  /['"`](?:Logical Calls|Recorded Logical Calls|Encrypted Logical Calls|Logical Call Activity|Retained Signaling)/,
  'Call counts need friendly names in headings, column help, chart legends, and metric explanations.');

function functionSource(name) {
  const marker = `function ${name}(`;
  const start = application.indexOf(marker);
  assert.notEqual(start, -1, `Missing ${name}`);
  let parentheses = 1;
  let quote = '';
  let openingBrace = -1;
  for (let index = start + marker.length; index < application.length; index += 1) {
    const character = application[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
    } else if (character === '\'' || character === '"' || character === '`') quote = character;
    else if (character === '(') parentheses += 1;
    else if (character === ')' && --parentheses === 0) {
      openingBrace = application.indexOf('{', index);
      break;
    }
  }
  assert.notEqual(openingBrace, -1, `Missing body for ${name}`);
  let depth = 0;
  quote = '';
  for (let index = openingBrace; index < application.length; index += 1) {
    const character = application[index];
    if (quote) {
      if (character === '\\') index += 1;
      else if (character === quote) quote = '';
    } else if (character === '\'' || character === '"' || character === '`') quote = character;
    else if (character === '{') depth += 1;
    else if (character === '}' && --depth === 0) {
      return application.slice(application.slice(start - 6, start) === 'async ' ? start - 6 : start, index + 1);
    }
  }
  throw new Error(`Unclosed ${name}`);
}

function node(tag, className = '', value = '') {
  const element = {
    tag, className, children: [], attributes: {}, hidden: false,
    classList: { add() {}, toggle() {} },
    append(...children) { this.children.push(...children.filter((child) => child != null)); },
    replaceChildren(...children) { this.children = children.filter((child) => child != null); },
    setAttribute(key, value) { this.attributes[key] = value; },
    addEventListener() {}
  };
  Object.defineProperty(element, 'textContent', {
    get() { return element.children.map(text).join(''); },
    set(value) { element.children = value == null || value === '' ? [] : [String(value)]; }
  });
  element.textContent = value;
  return element;
}

function text(value) {
  if (value == null) return '';
  if (typeof value !== 'object') return String(value);
  return value.textContent ?? '';
}

function fragment(...children) {
  const result = node('fragment');
  result.append(...children);
  return result;
}

function section(title, body, action = null) {
  const result = node('section');
  result.title = title;
  result.body = body;
  result.append(title, body, action);
  return result;
}

function sections(root) {
  return (root?.children || []).flatMap((child) => child?.tag === 'section' ? [child] : sections(child));
}

const labelSource = fs.readFileSync(path.resolve(__dirname,
  '../../../../stats-web/assets/core/call-count-labels.js'), 'utf8');
const labels = vm.runInNewContext(labelSource.replace(/^export /gm, '') +
  '\n({CALL_COUNT_HELP, systemCallTitle, observedCallPresentation, callCountDateRows});');
const plain = (value) => JSON.parse(JSON.stringify(value));
const hour = 3_600_000;
const first = Date.UTC(2026, 9, 3, 13);
const latestHour = Date.UTC(2026, 9, 18, 14);
const latestCall = latestHour + 21 * 60_000;
const snapshot = latestHour + 40 * 60_000;
const trackingStart = Date.UTC(2026, 8, 5, 15, 9);

assert.deepEqual(plain(labels.observedCallPresentation({ protocol: 'P25', topology: 'TRUNKED',
  channel_names: 'Cuyahoga' })), {
  title: 'Calls heard on Cuyahoga', countLabel: 'Calls heard', dateMode: 'observed'
}, 'P25 observations count copies heard on the selected channel.');
for (const protocol of ['DMR', 'NXDN']) {
  assert.deepEqual(plain(labels.observedCallPresentation({ protocol, topology: 'TRUNKED',
    channel_names: 'A configured channel' })), {
    title: 'Calls heard across this system', countLabel: 'Calls', dateMode: 'hourly'
  }, 'A configured channel name must not turn a system counter into a channel counter.');
}
for (const prefix of ['dmr', 'nxdn-c', 'nxdn-d']) {
  const row = { protocol: prefix === 'dmr' ? 'DMR' : 'NXDN', topology: 'TRUNKED',
    radio_system_key: `${prefix}:channel:channel-1`, channel_names: 'North Site' };
  assert.equal(labels.systemCallTitle(row), 'Calls heard on this channel',
    'A system identified only within a saved channel must keep its channel scope.');
  assert.deepEqual(plain(labels.observedCallPresentation(row)), {
    title: 'Calls heard on North Site', countLabel: 'Calls', dateMode: 'hourly'
  });
}
for (const radio_system_key of ['p25:00001:001', 'dmr:tier3:1:2', 'nxdn-c:system:123', null]) {
  assert.equal(labels.systemCallTitle({ radio_system_key }), 'Calls heard across this system');
}
assert.deepEqual(plain(labels.observedCallPresentation({ protocol_code: 1, topology: 'trunked' })), {
  title: 'Calls heard on this channel', countLabel: 'Calls heard', dateMode: 'observed'
});
for (const row of [{ protocol: 'APCO25_PHASE2' }, { decoder: 'P25_PHASE1' }]) {
  assert.equal(labels.observedCallPresentation({ ...row, topology: 'TRUNKED' }).countLabel, 'Calls heard',
    'P25 names recognized elsewhere in the interface must keep the channel counter meaning.');
}
for (const protocol of ['P25', 'NXDN']) {
  assert.deepEqual(plain(labels.observedCallPresentation({ protocol, topology: 'CONVENTIONAL',
    channel_names: 'Dispatch' })), {
    title: 'Calls heard on Dispatch', countLabel: 'Calls', dateMode: 'hourly'
  });
}
assert.equal(labels.observedCallPresentation({ protocol: 'DMR', topology: 'CONVENTIONAL' }).dateMode,
  'observed', 'Conventional DMR summaries have exact first and latest observations.');
assert.equal(labels.observedCallPresentation({ protocol: 'NBFM' }, { assigned: true }).dateMode,
  'observed', 'Assigned analog numbers use their call tracking dates.');

const counters = {
  first_seen_ms: first - 100 * hour, last_seen_ms: snapshot + 100 * hour,
  call_totals_started_at_ms: trackingStart, call_totals_as_of_ms: snapshot,
  calls_from_ms: first, calls_through_hour_ms: latestHour,
  channel_observations_from_ms: first + 5 * hour, channel_observations_to_ms: latestCall,
  from_ms: latestHour - 23 * hour, to_ms: snapshot
};
assert.deepEqual(plain(labels.callCountDateRows(counters, 'totals')),
  [['Since', trackingStart], ['As of', snapshot]],
  'Overall call totals use the tracking start and snapshot, not identity activity dates.');
assert.deepEqual(plain(labels.callCountDateRows(counters, 'system')),
  [['From', first], ['To', snapshot]],
  'Hourly system history stops at the snapshot inside the current hour.');
assert.deepEqual(plain(labels.callCountDateRows(counters, 'channels')),
  [['From', first + 5 * hour], ['Latest call', latestCall]],
  'Channel observations keep their own shorter history and exact latest call.');
assert.deepEqual(plain(labels.callCountDateRows(counters, 'selected')),
  [['From', latestHour - 23 * hour], ['To', snapshot]],
  'Selected chart dates use the API window, including an empty edge of the chart.');
assert.deepEqual(plain(labels.callCountDateRows({ channel_observations_from_ms: first,
  channel_observations_through_hour_ms: latestHour, call_totals_as_of_ms: snapshot }, 'channels')),
  [['From', first], ['To', snapshot]], 'Hourly channel history must not claim an exact latest call.');
assert.deepEqual(plain(labels.callCountDateRows({ calls_from_ms: first,
  calls_through_hour_ms: latestHour - hour, call_totals_as_of_ms: snapshot }, 'system')),
  [['From', first], ['To', latestHour - 1]], 'A completed hour includes the whole hour.');
assert.deepEqual(plain(labels.callCountDateRows({ first_seen_ms: first, last_seen_ms: latestCall }, 'observed')),
  [['From', first], ['Latest call', latestCall]]);
assert.deepEqual(plain(labels.callCountDateRows({ first_seen_ms: first, last_seen_ms: latestHour }, 'hourly')),
  [['From', first], ['To', latestHour + hour - 1]],
  'The last hourly bucket is an interval, not an exact latest-call timestamp.');
assert.deepEqual(plain(labels.callCountDateRows({ first_seen_ms: first, last_seen_ms: latestHour,
  counts_as_of_ms: snapshot }, 'hourly')), [['From', first], ['To', snapshot]]);

for (const mode of ['totals', 'system', 'channels', 'selected']) {
  assert.deepEqual(plain(labels.callCountDateRows({ first_seen_ms: first, last_seen_ms: latestCall }, mode))
    .map(([, value]) => value), [null, null], `${mode} must not substitute identity activity dates.`);
}
for (const invalid of [null, undefined, '', ' ', false, true, 0, -1, NaN, Infinity, {}, [first]]) {
  assert.deepEqual(plain(labels.callCountDateRows({ from_ms: invalid, to_ms: invalid }, 'selected')),
    [['From', null], ['To', null]], 'Missing dates remain unavailable instead of becoming dates in 1970.');
}
assert.deepEqual(plain(labels.callCountDateRows({ from_ms: String(first), to_ms: String(snapshot) }, 'selected')),
  [['From', first], ['To', snapshot]]);
assert.match(labels.CALL_COUNT_HELP, /several channels count as one call/);
assert.match(labels.CALL_COUNT_HELP, /cannot be matched may count separately/,
  'Friendly help must preserve the limitation of call matching.');

const context = {
  node, fragment, section, Intl, Date,
  callCountDateRows: labels.callCountDateRows,
  systemCallTitle: labels.systemCallTitle,
  observedCallPresentation: labels.observedCallPresentation,
  metrics(values) {
    const result = node('metrics');
    result.values = values;
    result.append(...values.map(([label, value, display]) => `${label}: ${display ?? value}`));
    return result;
  },
  keyValues(values) {
    const result = node('facts');
    result.values = values;
    result.append(...values.map(([label, value]) => `${label}: ${text(value)}`));
    return result;
  },
  dateTime(value) {
    const result = node('time', '', `date ${value}`);
    result.epoch = value;
    return result;
  },
  aliasMetricValue: (row, field) => String(row[field]),
  configuredAliasAssignment: (row) => row.assigned === true,
  isP25: (row) => row.protocol === 'P25' || row.protocol_code === 1,
  groupIdentityLabel: () => 'Talkgroup',
  identityNumber: (_row, value) => value,
  groupIdentifierText: (_row, value) => value,
  protocolFamily: (row) => row.protocol,
  radioSystemLink: (_reference, label) => label,
  radioSystemPrimaryName: (row) => row.system_name,
  radioSystemDisplayName: () => 'Radio system',
  entityTarget: () => '',
  hexDecimalPair: (value) => String(value),
  identifierNumber: (value) => String(value),
  frequency: (value) => String(value),
  observedGroupIdentityMatch: () => 'No match',
  observedGroupIdentityPromotionSupported: () => true,
  aliasAdminAllowed: () => false
};
vm.createContext(context);
for (const name of ['aliasDetailMetricBand', 'callCountDates', 'systemCallCounts', 'channelCallCounts',
  'observedGroupIdentityDetail']) vm.runInContext(functionSource(name), context);

function facts(root) {
  if (root?.tag === 'facts' || root?.tag === 'dl' && root.className.split(/\s+/).includes('ui-fact-list')) {
    return [root];
  }
  return (root?.children || []).flatMap(facts);
}

function epoch(root) {
  if (root?.epoch !== undefined) return root.epoch;
  return (root?.children || []).map(epoch).find((value) => value !== undefined);
}

function dates(root) {
  return facts(root).flatMap((fact) => fact.values || fact.children.flatMap((child, index, children) =>
    child?.tag === 'dt' ? [[text(child), children[index + 1]]] : []))
    .map(([label, value]) => [label, epoch(value) ?? text(value)]);
}

const p25 = { protocol: 'P25', topology: 'TRUNKED', channel_names: 'Cuyahoga',
  group_identity_id: 29560, logical_call_count: 17973, encrypted_logical_call_count: 17973,
  recorded_logical_call_count: null, stream_submitted_logical_call_count: null,
  first_seen_ms: first, last_seen_ms: latestCall };
const popup = context.observedGroupIdentityDetail(p25, {});
const popupCalls = sections(popup).find((item) => item.title === 'Calls heard on Cuyahoga');
assert.ok(popupCalls, 'The observed popup identifies its channel beside its count.');
assert.match(text(popupCalls), /Calls heard: 17973/);
assert.match(text(popupCalls), /Recorded: Not available/,
  'An unavailable recording count must not look like a zero.');
assert.deepEqual(dates(popupCalls), [['From', first], ['Latest call', latestCall]]);
assert.match(text(popup), /Signaling counts are not available in this view/);
for (const protocol of ['DMR', 'NXDN']) {
  const systemPopup = context.observedGroupIdentityDetail({ ...p25, protocol,
    last_seen_ms: latestHour }, {});
  const callSection = sections(systemPopup).find((item) => item.title === 'Calls heard across this system');
  assert.ok(callSection, 'Other trunked protocols must preserve their system counter scope.');
  assert.deepEqual(dates(callSection), [['From', first], ['To', latestHour + hour - 1]]);
}
const systemRow = { ...counters, protocol: 'P25', logical_call_count: 58605,
  channel_observation_count: 48311, recorded_logical_call_count: 5,
  stream_submitted_logical_call_count: 0, encrypted_logical_call_count: 58600 };
const systemCalls = context.systemCallCounts('Calls heard across this system', systemRow);
const channelCopies = context.channelCallCounts(systemRow);
assert.deepEqual(dates(systemCalls), [['Since', trackingStart], ['As of', snapshot]]);
assert.deepEqual(dates(channelCopies), [['From', first + 5 * hour], ['Latest call', latestCall]]);
assert.match(text(systemCalls), /Calls: 58605/);
assert.doesNotMatch(text(systemCalls), /48311/,
  'Channel copies need a separate section because their scope and dates differ.');
assert.match(text(channelCopies), /Times heard: 48311/);
assert.equal(context.channelCallCounts({ ...systemRow, protocol: 'DMR' }), null,
  'Do not present unsupported channel-copy counters for other protocols.');
const unknownDates = context.systemCallCounts('Calls heard across this system', {
  logical_call_count: 5, first_seen_ms: first, last_seen_ms: latestCall
});
assert.deepEqual(dates(unknownDates), [['Since', 'Not available'], ['As of', 'Not available']]);

async function checkSelectedHistory() {
  let response = { totals: { logical_call_count: 945 }, from_ms: latestHour - 23 * hour,
    to_ms: snapshot, call_totals_as_of_ms: snapshot, signaling_totals_started_at_ms: trackingStart,
    series: [{ time_ms: latestHour - hour, logical_call_count: 945 }] };
  let changeRange;
  Object.assign(context, {
    ACTIVITY_RANGES: [], GROUP_IDENTITY_CALL_ACTIVITY_SERIES: [],
    captureRenderContext: () => ({}),
    rangeControls(_ranges, _selected, callback) {
      changeRange = callback;
      return { controls: node('controls'), buttons: [] };
    },
    sectionActionHost: (controls) => controls,
    statsLoggingState: () => ({ available: true, summaryActive: true }),
    groupIdentityApiPath: () => '/group/activity',
    api: async () => response,
    groupIdentityActivityChart: () => node('chart'),
    signalingMetrics: () => node('signals'),
    signalingCounts: () => [],
    activityMetricGuide: () => node('help'),
    renderIsCurrent: () => false,
    rethrowPageHandlingError(error) { throw error; }
  });
  vm.runInContext(functionSource('groupIdentityHistoryTotals'), context);
  vm.runInContext(functionSource('groupIdentityActivityHistorySection'), context);
  const history = await context.groupIdentityActivityHistorySection({ radio_system_key: 'p25:00001:001',
    identity_key: 'v1-g-00001-001-1201' });
  const calls = sections(history).find((item) => item.title === 'Call history');
  assert.ok(calls);
  assert.deepEqual(dates(calls), [['From', latestHour - 23 * hour], ['To', snapshot]],
    'The visible history dates must cover the chosen window, not just the first and last nonzero buckets.');
  assert.match(text(calls), /Calls heard across this system/);
  assert.match(text(calls), /Calls: 945/);
  response = { ...response, totals: { logical_call_count: 55 }, from_ms: latestHour - 5 * hour };
  await changeRange('6h', []);
  assert.deepEqual(dates(calls), [['From', latestHour - 5 * hour], ['To', snapshot]],
    'Changing the history range must update its dates together with its count.');
  assert.match(text(calls), /Calls: 55/);
  const channelHistory = await context.groupIdentityActivityHistorySection({
    radio_system_key: 'nxdn-c:channel:channel-1', identity_key: 'v1-g-1201'
  });
  const channelCalls = sections(channelHistory).find((item) => item.title === 'Call history');
  assert.match(text(channelCalls), /Calls heard on this channel/,
    'A channel-owned fallback system keeps that scope in its history panel too.');
  assert.doesNotMatch(text(channelCalls), /Calls heard across this system/);
}

checkSelectedHistory().then(() => console.log('call count scope tests passed')).catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
