'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');

function node(tag, className = '', value = '') {
  const element = {
    tag, className, dataset: {}, children: [],
    append(...children) { this.children.push(...children.filter((child) => child != null)); }
  };
  Object.defineProperty(element, 'textContent', {
    get() { return element.children.map((child) => typeof child === 'object' ? child.textContent : String(child)).join(''); },
    set(text) { element.children = text == null || text === '' ? [] : [String(text)]; }
  });
  element.textContent = value;
  return element;
}

function descendants(element) {
  return typeof element === 'object' ? [element, ...element.children.flatMap(descendants)] : [];
}

function checkApplicationPresentation({ summarizeBsi, bsiObservationFreshness }) {
  const application = fs.readFileSync(path.resolve(__dirname, '../../../../stats-web/assets/app.js'), 'utf8');
  function functionSource(name) {
    const start = application.indexOf(`function ${name}(`);
    assert.notEqual(start, -1, `Missing ${name}`);
    const open = application.indexOf(') {', start) + 2;
    assert.ok(open > start, `Missing body for ${name}`);
    let depth = 0, quote = '';
    for (let index = open; index < application.length; index += 1) {
      const character = application[index];
      if (quote) {
        if (character === '\\') index += 1;
        else if (character === quote) quote = '';
      } else if (character === '\'' || character === '"' || character === '`') quote = character;
      else if (character === '{') depth += 1;
      else if (character === '}' && --depth === 0) return application.slice(start, index + 1);
    }
    throw new Error(`Unclosed ${name}`);
  }
  function activityConstant(name) {
    const start = application.indexOf(`const ${name} =`);
    assert.notEqual(start, -1, `Missing ${name}`);
    return application.slice(start, application.indexOf('});', start) + 3);
  }
  const now = 10_000_000;
  const tables = new Map();
  const behavior = vm.runInNewContext(`(() => {
    ${activityConstant('TUNER_ACTIVITY_PRIORITY')}
    ${activityConstant('TUNER_ACTIVITY_LABELS')}
    ${['bsiFreshness', 'bsiEvidenceText', 'bsiCallsigns', 'tunerActivityStatus', 'activeCarriers',
      'activityValues', 'activityTokenLabel', 'targetIdentifierLabel', 'activityAliasLabel',
      'activeCarrierFields'].map(functionSource).join('\n')}
    return { bsiCallsigns, activeCarriers, activeCarrierFields };
  })()`, {
    summarizeBsi,
    bsiObservationFreshness: (observation) => bsiObservationFreshness(observation, now),
    node,
    callsignLink: (callsign) => node('a', '', callsign),
    badge: (text, className) => node('span', className, text),
    dateTime: (timestamp) => ({ textContent: `time-${timestamp}` }),
    activeChannelTables: tables,
    viewport: { startHz: 850_000_000, endHz: 854_000_000 },
    channelTagSet: (tags) => new Set(tags || []),
    isAnalogChannel: () => false,
    isP25: () => false,
    partyIdentifierText: () => '',
    radioIdentifierText: () => '',
    decoderLabel: (decoder) => decoder || ''
  });

  const control = { source: 'control', callsign: 'WKSD533', lastHeardMs: 1000 };
  const voice = { source: 'voice', callsign: 'WKSD533', lastHeardMs: now - 1000 };
  const matched = behavior.bsiCallsigns({ bsi: [control, voice] });
  const matchedNodes = descendants(matched);
  assert.equal(matchedNodes.filter((element) => element.tag === 'a').length, 1,
    'Matching control/voice evidence renders one linked carrier callsign');
  assert.equal(matched.textContent.match(/WKSD533/g).length, 1);
  assert.equal(matched.textContent, 'WKSD533', 'The BSI value contains only the confirmed callsign');
  assert.equal(matchedNodes.some((element) => Object.keys(element.dataset).length), false,
    'Removed reception annotations are not moved into hidden hints');

  const conflicting = behavior.bsiCallsigns({ bsi: [control, { ...voice, callsign: 'WPTZ770' }] });
  const conflictNodes = descendants(conflicting);
  assert.equal(conflictNodes.filter((element) => element.tag === 'a')[0].textContent, 'WPTZ770');
  assert.deepEqual(conflictNodes.filter((element) => element.tag === 'a').map((element) => element.textContent),
    ['WPTZ770', 'WKSD533'], 'Both distinct known callsigns remain linked, newest evidence first');
  assert.equal(conflicting.textContent, 'WPTZ770WKSD533');
  assert.equal(behavior.bsiCallsigns({ bsi: [] }), '');
  assert.equal(behavior.bsiCallsigns({}), '');
  assert.equal(behavior.bsiCallsigns({ bsi: [{ ...control, callsign: ' ' }] }), '');
  assert.equal(behavior.bsiCallsigns({ bsi: [{ ...control, lastHeardMs: null }] }).textContent,
    'WKSD533', 'Unknown reception time adds no subtitle or invented freshness');
  assert.deepEqual(control, { source: 'control', callsign: 'WKSD533', lastHeardMs: 1000 },
    'Simplifying the value does not alter retained source or reception facts');

  const frequency = 851_137_500;
  const row = (extra = {}) => ({ frequency_hz: frequency, status: 'CALL', bsi: [control, voice], ...extra });
  tables.set('site-a', {
    table_id: 'site-a', system_name: 'Example P25', site_name: 'Alpha',
    rows: [row({ timeslot: 1, bsi: [{ ...control, callsign: 'WOLD01', lastHeardMs: 500 }] }),
      row({ timeslot: 2 }), row({ frequency_hz: 852_812_500,
        bsi: [{ ...control, callsign: 'WOTHER22', lastHeardMs: now - 500 }] })]
  });
  let carriers = behavior.activeCarriers();
  assert.equal(carriers.length, 2, 'Different frequencies remain separate spectrum carriers');
  assert.equal(carriers[0].rows.length, 2, 'Timeslot rows can share one RF carrier');
  assert.equal(carriers[0].rows.every((entry) => entry.tableBsiScope === 'site-a'), true,
    'Spectrum decoration retains the parent scope needed by BSI presentation');
  let fields = behavior.activeCarrierFields(carriers[0]);
  assert.equal(fields.find((field) => field.label === 'BSI').value, 'WKSD533',
    'Older same-source timeslot snapshots cannot fabricate a conflict');
  assert.equal(fields.some((field) => field.value.includes('Conflict')), false);
  assert.match(fields.find((field) => field.label === 'BSI source').value, /Control · Historical/);
  assert.match(fields.find((field) => field.label === 'BSI source').value, /Voice · Fresh/);
  assert.equal(behavior.activeCarrierFields(carriers[1]).find((field) => field.label === 'BSI').value, 'WOTHER22');

  tables.set('site-b', {
    table_id: 'site-b', system_name: 'Example P25', site_name: 'Beta',
    rows: [row({ bsi: [{ ...voice, callsign: 'WPTZ770' }] })]
  });
  carriers = behavior.activeCarriers();
  fields = behavior.activeCarrierFields(carriers[0]);
  const bsiFields = fields.filter((field) => field.label.startsWith('BSI · '));
  assert.equal(bsiFields.length, 2, 'Two sites sharing a frequency retain separate carrier identity evidence');
  assert.equal(bsiFields.find((field) => field.label.includes('Alpha')).value, 'WKSD533');
  assert.equal(bsiFields.find((field) => field.label.includes('Beta')).value, 'WPTZ770');
  assert.equal(fields.some((field) => field.value.includes('Conflict')), false,
    'Different site callsigns on the same frequency are not a cross-source conflict');
  assert.deepEqual([...new Set(carriers[0].rows.map((entry) => entry.tableBsiScope))].sort(), ['site-a', 'site-b']);

  tables.get('site-a').rows.push(row({ bsi: [{ ...voice, callsign: 'WNEW99', lastHeardMs: now }] }));
  fields = behavior.activeCarrierFields(behavior.activeCarriers()[0]);
  assert.match(fields.find((field) => field.label === 'BSI · Example P25 / Alpha').value, /^WNEW99 · Conflict/,
    'A genuine same-site cross-source difference is visible in spectrum details');
  assert.equal(fields.find((field) => field.label === 'BSI · Example P25 / Beta').value, 'WPTZ770');
}

async function main() {
  const modulePath = path.resolve(process.argv[2] || path.resolve(__dirname,
    '../../../../stats-web/assets/core/bsi.js'));
  const { summarizeBsi, bsiObservationFreshness } = await import(pathToFileURL(modulePath).href);
  const control = { source: 'control', callsign: 'WKSD533', lastHeardMs: 1000 };
  const voice = { source: 'voice', callsign: 'WKSD533', lastHeardMs: 2000 };

  assert.deepEqual(summarizeBsi(), {
    callsign: '', sourceLabel: '', lastHeardMs: null, conflict: false, observations: []
  });
  assert.deepEqual(summarizeBsi([]), summarizeBsi(null));
  assert.equal(summarizeBsi([control]).sourceLabel, 'Control');
  assert.equal(summarizeBsi([voice]).sourceLabel, 'Voice');

  const matched = summarizeBsi([control, voice]);
  assert.equal(matched.callsign, 'WKSD533');
  assert.equal(matched.sourceLabel, 'Control + voice');
  assert.equal(matched.lastHeardMs, 2000);
  assert.equal(matched.conflict, false, 'Independent matching observations corroborate one callsign');
  assert.deepEqual(matched.observations, [voice, control], 'Each source retains its actual reception time');
  assert.deepEqual(summarizeBsi([voice, control]), matched, 'API ordering must not decide the primary');
  assert.deepEqual(summarizeBsi([control, voice, control, { ...voice }]), matched,
    'Repeated timeslot snapshots must retain only one observation per source');
  const revisedControl = { ...control, callsign: 'WPTZ770', lastHeardMs: 3000 };
  const sameSource = summarizeBsi([control, revisedControl]);
  assert.equal(sameSource.callsign, 'WPTZ770');
  assert.equal(sameSource.conflict, false,
    'A newer callsign revision from one source is not a control/voice disagreement');
  assert.deepEqual(sameSource.observations, [revisedControl]);
  assert.deepEqual(summarizeBsi([revisedControl, control]), sameSource,
    'Snapshot ordering cannot bring back an older revision');
  assert.deepEqual(summarizeBsi([revisedControl, { ...control, lastHeardMs: null }]), sameSource,
    'An unknown timestamp cannot displace a newer known reception from the same source');
  assert.equal(summarizeBsi([{ ...control, callsign: 'WZZZ999' }, control]).callsign, 'WKSD533',
    'Equal-time same-source snapshots are resolved deterministically');

  const conflict = summarizeBsi([control, { ...voice, callsign: 'WPTZ770' }]);
  assert.equal(conflict.callsign, 'WPTZ770', 'The newest confirmed callsign is primary');
  assert.equal(conflict.sourceLabel, 'Voice');
  assert.equal(conflict.conflict, true);
  assert.equal(conflict.observations.length, 2, 'A conflict must preserve both values for inspection');
  const tied = summarizeBsi([{ ...voice, callsign: 'WPTZ770', lastHeardMs: 1000 }, control]);
  assert.equal(tied.callsign, 'WKSD533', 'Equal timestamps choose control deterministically');
  assert.equal(tied.conflict, true);

  const unknown = { source: 'voice', callsign: 'WPTZ770', lastHeardMs: null };
  assert.equal(summarizeBsi([unknown, control]).callsign, 'WKSD533');
  assert.equal(summarizeBsi([unknown]).lastHeardMs, null, 'No time may be invented for migrated evidence');
  assert.equal(summarizeBsi([unknown, { ...control, lastHeardMs: null }]).callsign, 'WKSD533');
  assert.equal(summarizeBsi([unknown, control]).conflict, true,
    'Different retained evidence remains visible even when its reception time is unknown');

  const input = [control, voice];
  summarizeBsi(input);
  assert.deepEqual(input, [control, voice], 'Presentation must not reorder or mutate input data');
  assert.equal(summarizeBsi([{ ...control, callsign: '  WKSD533  ' }]).callsign, 'WKSD533');
  assert.deepEqual(summarizeBsi([null, {}, { ...voice, callsign: '' }, { ...voice, callsign: ' ' },
    { ...voice, callsign: null }, { ...voice, source: 'other' }, { ...voice, source: 'toString' }]),
  summarizeBsi([]), 'Missing, blank or unrecognized sources cannot fabricate a callsign or conflict');

  assert.deepEqual(bsiObservationFreshness(control, 3_601_000), { lastHeardMs: 1000, freshness: 'Fresh' });
  assert.deepEqual(bsiObservationFreshness(control, 3_601_001), { lastHeardMs: 1000, freshness: 'Historical' });
  assert.equal(bsiObservationFreshness(voice, 1999).freshness, 'Fresh',
    'Small receiver/browser clock skew must not make a new observation historical');
  assert.deepEqual(bsiObservationFreshness({ lastHeardMs: '1000' }, 1001),
    { lastHeardMs: 1000, freshness: 'Fresh' });
  for (const value of [null, undefined, '', ' ', 0, -1, NaN, Infinity, 'invalid', true, {}]) {
    assert.deepEqual(bsiObservationFreshness({ lastHeardMs: value }, 1000),
      { lastHeardMs: null, freshness: null }, `Invalid or unknown time must remain unknown: ${value}`);
  }
  assert.deepEqual(bsiObservationFreshness(null, 1000), { lastHeardMs: null, freshness: null });
  checkApplicationPresentation({ summarizeBsi, bsiObservationFreshness });
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
