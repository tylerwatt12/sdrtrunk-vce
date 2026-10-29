'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const defaultCore = path.resolve(__dirname, '../../../../stats-web/assets/core');
const activity = {
  type: 'activity',
  previous: ['time', 'duration', 'action', 'event', 'tones', 'source', 'source-alias',
    'source-ota-alias', 'target', 'target-alias', 'channel', 'encryption', 'related'],
  current: ['time', 'duration', 'action', 'event', 'tones', 'source', 'target',
    'channel', 'encryption', 'related'],
  source: ['source', 'source-alias', 'source-ota-alias'], target: ['target', 'target-alias']
};
const live = {
  type: 'live-channels',
  previous: ['status', 'tags', 'channel', 'frequency', 'signal', 'decode-health',
    'source-alias', 'source', 'target-alias', 'target', 'decoder'],
  current: ['status', 'tags', 'channel', 'frequency', 'signal', 'decode-health',
    'source', 'target', 'decoder'],
  source: ['source-alias', 'source'], target: ['target-alias', 'target']
};
const columns = (ids) => ids.map((id) => ({ id }));
const ids = (value) => value.split(' ');
const identities = [
  { type: 'group-identities',
    previous: ids('group-identity-id group-identity-kind group-identity-name group-identity-description alias-group logical-calls recorded-logical-calls stream-submitted-logical-calls encrypted-logical-calls signaling last-seen'),
    current: ids('group-identity-id group-identity-kind group-identity-description alias-group logical-calls recorded-logical-calls stream-submitted-logical-calls encrypted-logical-calls signaling last-seen'),
    merged: { 'group-identity-id': ['group-identity-name', 'group-identity-id'] } },
  { type: 'radio-groups',
    previous: ids('group-identity-id group-identity-kind group-identity-name group-identity-description logical-calls encrypted-logical-calls last-seen'),
    current: ids('group-identity-id group-identity-kind group-identity-description logical-calls encrypted-logical-calls last-seen'),
    merged: { 'group-identity-id': ['group-identity-name', 'group-identity-id'] } },
  { type: 'talker-aliases',
    previous: ids('radio talker-alias radio-alias logical-calls encrypted-logical-calls last-seen'),
    current: ids('radio logical-calls encrypted-logical-calls last-seen'),
    merged: { radio: ['radio-alias', 'talker-alias', 'radio'] } },
  { type: 'channel-top-groups',
    previous: ids('group-identity-id group-identity-kind group-identity-name group logical-calls encrypted-logical-calls'),
    current: ids('group-identity-id group-identity-kind group logical-calls encrypted-logical-calls'),
    merged: { 'group-identity-id': ['group-identity-name', 'group-identity-id'] } },
  { type: 'channel-group-identities',
    previous: ids('group-identity-id group-identity-kind group-identity-name group-identity-description frequency timeslot logical-calls encrypted-logical-calls source source-alias first-seen last-seen'),
    current: ids('group-identity-id group-identity-kind group-identity-description frequency timeslot logical-calls encrypted-logical-calls source first-seen last-seen'),
    merged: { 'group-identity-id': ['group-identity-name', 'group-identity-id'], source: ['source-alias', 'source'] } },
  { type: 'channel-radios',
    previous: ids('radio radio-alias frequency timeslot logical-calls encrypted-logical-calls source-logical-calls target-logical-calls group-logical-calls private-logical-calls last-talkgroup talkgroup-name last-peer peer-alias first-seen last-seen'),
    current: ids('radio frequency timeslot logical-calls encrypted-logical-calls source-logical-calls target-logical-calls group-logical-calls private-logical-calls last-talkgroup last-peer first-seen last-seen'),
    merged: { radio: ['radio-alias', 'radio'], 'last-talkgroup': ['talkgroup-name', 'last-talkgroup'],
      'last-peer': ['peer-alias', 'last-peer'] } },
  { type: 'channel-patches',
    previous: ids('patch-id patch-name member-talkgroup-ids member-talkgroup-names member-radio-ids member-radio-names state observations last-seen'),
    current: ids('patch-id member-talkgroup-ids member-radio-ids state observations last-seen'),
    merged: { 'patch-id': ['patch-name', 'patch-id'], 'member-talkgroup-ids': ['member-talkgroup-names', 'member-talkgroup-ids'],
      'member-radio-ids': ['member-radio-names', 'member-radio-ids'] } },
  { type: 'channel-patches-versioned',
    previous: ids('patch-id patch-name version member-talkgroup-ids member-talkgroup-names member-radio-ids member-radio-names state observations last-seen'),
    current: ids('patch-id version member-talkgroup-ids member-radio-ids state observations last-seen'),
    merged: { 'patch-id': ['patch-name', 'patch-id'], 'member-talkgroup-ids': ['member-talkgroup-names', 'member-talkgroup-ids'],
      'member-radio-ids': ['member-radio-names', 'member-radio-ids'] } },
  { type: 'issi-subscribers',
    previous: ids('canonical-subscriber working-id home-system talker-alias evidence affiliation observed-on registrations affiliations logical-calls last-mapping-seen last-seen'),
    current: ids('canonical-subscriber working-id home-system evidence affiliation observed-on registrations affiliations logical-calls last-mapping-seen last-seen'),
    merged: { 'canonical-subscriber': ['talker-alias', 'canonical-subscriber'] } }
];
// The table IDs retain their capability suffixes so existing saved layouts remain addressable.
for (const [variant, optional] of [
  ['base', []], ['channel', ['confirmed-channel']], ['affiliation', ['affiliation']],
  ['affiliation-channel', ['affiliation', 'confirmed-channel']], ['talker-alias', ['talker-alias']],
  ['talker-alias-channel', ['talker-alias', 'confirmed-channel']],
  ['talker-alias-affiliation', ['talker-alias', 'affiliation']],
  ['talker-alias-affiliation-channel', ['talker-alias', 'affiliation', 'confirmed-channel']]
]) {
  for (const type of ['radios', 'group-identity-radios']) {
    if (type === 'group-identity-radios' && optional.includes('affiliation')) continue;
    identities.push({ type: `${type}.${variant}`,
      previous: ['radio', 'alias', ...optional, 'logical-calls', 'encrypted-logical-calls', 'last-seen'],
      current: ['radio', ...optional.filter((id) => id !== 'talker-alias'),
        'logical-calls', 'encrypted-logical-calls', 'last-seen'],
      merged: { radio: ['alias', ...(optional.includes('talker-alias') ? ['talker-alias'] : []), 'radio'] } });
  }
}

async function checkPartyColumnLayouts(core = defaultCore) {
  const layouts = await import(pathToFileURL(path.join(core, 'table-layout.js')).href);
  const savedLayout = (ids) => layouts.persisted(layouts.normalize(columns(ids), null));
  const migrate = (definition, saved, declared = columns(definition.current)) =>
    layouts.mergePartyColumns(definition.type, declared, saved);

  const defaults = await import(pathToFileURL(path.join(core, 'table-defaults.js')).href);
  const receiverColumns = columns([...live.current, 'receiver']);
  const savedLive = savedLayout(live.current);
  savedLive.column_order.reverse();
  savedLive.column_widths.channel = 307;
  savedLive.hidden_columns = ['tags'];
  savedLive.collapsed_groups = ['site-1'];
  const originalLive = structuredClone(savedLive);
  const withReceiver = layouts.upgradeTableLayout(live.type, receiverColumns, savedLive);
  assert.deepEqual(withReceiver, { ...originalLive, schema: [...live.current, 'receiver'],
    column_order: [...originalLive.column_order, 'receiver'], hidden_columns: ['tags', 'receiver'] },
    'Introducing the optional receiver column preserves saved Live choices and starts it hidden.');
  assert.deepEqual(savedLive, originalLive, 'Upgrading a Live layout does not alter the saved input.');
  assert.equal(layouts.normalize(receiverColumns, withReceiver).reset, false);
  assert.deepEqual(layouts.upgradeTableLayout(live.type, columns(live.current), savedLive), savedLive,
    'The existing Live schema keeps its personalized layout.');
  for (const definition of identities) {
    const replacement = Object.fromEntries(Object.entries(definition.merged)
      .flatMap(([id, members]) => members.map((member) => [member, id])));
    const saved = savedLayout(definition.previous);
    saved.column_order.reverse();
    saved.column_widths = Object.fromEntries(definition.previous.map((id, index) =>
      [id, Object.hasOwn(definition.merged, id) ? 74 : 230 + index]));
    const unrelated = definition.previous.find((id) => !replacement[id]);
    saved.hidden_columns = [unrelated, ...Object.keys(definition.merged)];
    saved.collapsed_groups = ['site-1', 'conventional'];
    const original = structuredClone(saved);
    const upgraded = migrate(definition, saved);
    const expectedOrder = [...new Set(saved.column_order.map((id) => replacement[id] || id))];
    const expectedWidths = Object.fromEntries(Object.entries(saved.column_widths)
      .filter(([id]) => !replacement[id]));
    for (const [id, members] of Object.entries(definition.merged)) {
      expectedWidths[id] = saved.column_widths[members.find((member) => member !== id)];
    }
    assert.deepEqual(upgraded.schema, definition.current, `${definition.type}: all split name columns migrate.`);
    assert.equal(layouts.normalize(columns(definition.current), upgraded).reset, false, definition.type);
    assert.deepEqual(upgraded.column_order, expectedOrder, `${definition.type}: personalized order survives.`);
    assert.deepEqual(upgraded.column_widths, expectedWidths, `${definition.type}: name and unrelated widths survive.`);
    assert.deepEqual(upgraded.hidden_columns, [unrelated], `${definition.type}: any visible name keeps its identity visible.`);
    assert.deepEqual(upgraded.collapsed_groups, saved.collapsed_groups, `${definition.type}: collapsed groups survive.`);
    assert.deepEqual(saved, original, `${definition.type}: stored input is not mutated.`);
    assert.equal(migrate(definition, upgraded), upgraded, `${definition.type}: current preferences are unchanged.`);

    for (const [id, members] of Object.entries(definition.merged)) {
      for (let mask = 0; mask < (1 << members.length); mask += 1) {
        const choice = savedLayout(definition.previous);
        choice.hidden_columns = members.filter((_member, index) => mask & (1 << index));
        const result = migrate(definition, choice);
        assert.equal(result.hidden_columns.includes(id), mask === (1 << members.length) - 1,
          `${definition.type}: ${id} hides only when every former column was hidden (mask ${mask}).`);
      }
    }
    const narrowIds = savedLayout(definition.previous);
    narrowIds.column_widths = Object.fromEntries(Object.keys(definition.merged).map((id) => [id, 74]));
    assert.deepEqual(migrate(definition, narrowIds).column_widths, {},
      `${definition.type}: old narrow identifier widths yield to combined defaults.`);

    for (const invalidate of [
      (saved) => { saved.column_order.pop(); },
      (saved) => { saved.column_order[1] = saved.column_order[0]; },
      (saved) => { saved.hidden_columns = ['unknown']; },
      (saved) => { saved.hidden_columns = [unrelated, unrelated]; },
      (saved) => { saved.hidden_columns = definition.previous.slice(); },
      (saved) => { saved.column_widths[unrelated] = 47; },
      (saved) => { saved.column_widths[unrelated] = 1201; },
      (saved) => { saved.column_widths[unrelated] = '230'; },
      (saved) => { saved.column_widths.unknown = 230; },
      (saved) => { saved.collapsed_groups = ['Invalid group']; },
      (saved) => { saved.schema.reverse(); }
    ]) {
      const invalid = savedLayout(definition.previous);
      invalidate(invalid);
      assert.equal(migrate(definition, invalid), invalid, `${definition.type}: invalid preferences are not repaired.`);
      assert.equal(layouts.normalize(columns(definition.current), invalid).reset, true);
    }
    const future = [...columns(definition.current), { id: 'future' }];
    assert.equal(migrate(definition, saved, future), saved, `${definition.type}: future schemas do not opt in.`);
    const declared = columns(definition.current);
    const defaultLayout = defaults.layout(definition.type, declared);
    assert.deepEqual(defaultLayout.column_order, definition.current);
    const removed = definition.previous.filter((id) => !definition.current.includes(id));
    assert.ok(defaults.fit(definition.type).grow.every((id) => !removed.includes(id)),
      `${definition.type}: default growth excludes removed name columns.`);
    for (const id of Object.keys(definition.merged)) {
      assert.ok(defaults.width(definition.type, { id }) >= 220,
        `${definition.type}: combined ${id} has space for a name and identifier.`);
    }
  }

  for (const definition of [activity, live]) {
    const saved = savedLayout(definition.previous);
    // Deliberately separate the constituent columns and reorder unrelated ones.
    const unrelated = definition.previous.filter((id) =>
      !definition.source.includes(id) && !definition.target.includes(id)).reverse();
    saved.column_order = [definition.source[1], unrelated[0], definition.target[1],
      ...unrelated.slice(1), definition.source[0], definition.target[0], ...definition.source.slice(2)];
    saved.column_widths = { [unrelated[0]]: 307, source: 74, 'source-alias': 263,
      target: 79, 'target-alias': 241 };
    if (definition.type === 'activity') saved.column_widths['source-ota-alias'] = 212;
    saved.hidden_columns = [unrelated[0], definition.source[0], definition.target[0]];
    saved.collapsed_groups = ['p25-phase1', 'site-2'];
    const original = structuredClone(saved);
    const upgraded = migrate(definition, saved);
    const restored = layouts.normalize(columns(definition.current), upgraded);
    assert.equal(restored.reset, false);
    assert.deepEqual(upgraded.schema, definition.current);
    assert.deepEqual(upgraded.column_order, ['source', unrelated[0], 'target', ...unrelated.slice(1)],
      'A combined column takes the first constituent position and leaves unrelated order intact.');
    assert.deepEqual(upgraded.hidden_columns, [unrelated[0]], 'Any visible constituent keeps its combined column visible.');
    assert.deepEqual(upgraded.column_widths, { [unrelated[0]]: 307, source: 263, target: 241 },
      'Name widths take precedence over narrow ID widths; unrelated widths survive.');
    assert.deepEqual(upgraded.collapsed_groups, saved.collapsed_groups);
    assert.deepEqual(saved, original, 'Migration must not change the stored preferences object.');
    assert.equal(migrate(definition, upgraded), upgraded, 'Current layouts retain their exact existing choices.');

    const members = [...definition.source, ...definition.target];
    for (let mask = 0; mask < (1 << members.length); mask += 1) {
      const choice = savedLayout(definition.previous);
      choice.hidden_columns = members.filter((_id, index) => mask & (1 << index));
      const upgradedChoice = migrate(definition, choice);
      for (const party of ['source', 'target']) {
        assert.equal(upgradedChoice.hidden_columns.includes(party),
          definition[party].every((id) => choice.hidden_columns.includes(id)),
          `${definition.type}: ${party} hides only when all its old columns were hidden (mask ${mask}).`);
      }
    }

    const idWidths = savedLayout(definition.previous);
    idWidths.column_widths = { source: 74, target: 79 };
    assert.deepEqual(migrate(definition, idWidths).column_widths, {},
      'Without a saved name width, use the new default rather than a narrow ID width.');

    const invalidCases = [
      (saved) => { saved.column_order.pop(); },
      (saved) => { saved.column_order[1] = saved.column_order[0]; },
      (saved) => { saved.hidden_columns = ['unknown']; },
      (saved) => { saved.hidden_columns = ['source', 'source']; },
      (saved) => { saved.hidden_columns = definition.previous.slice(); },
      (saved) => { saved.column_widths.source = 47; },
      (saved) => { saved.column_widths.source = 1201; },
      (saved) => { saved.column_widths.source = '220'; },
      (saved) => { saved.column_widths.unknown = 220; },
      (saved) => { saved.collapsed_groups = ['P25 Phase 1']; },
      (saved) => { saved.collapsed_groups = ['site-2', 'site-2']; },
      (saved) => { saved.schema[0] = 'unrecognized'; },
      (saved) => { saved.schema.reverse(); }
    ];
    for (const invalidate of invalidCases) {
      const invalid = savedLayout(definition.previous);
      invalidate(invalid);
      assert.equal(migrate(definition, invalid), invalid, 'Invalid or unrecognized preferences are not repaired.');
      assert.equal(layouts.normalize(columns(definition.current), invalid).reset, true);
    }
    const futureColumns = [...columns(definition.current), { id: 'future' }];
    assert.equal(migrate(definition, saved, futureColumns), saved,
      'A later schema change must not accidentally opt into this targeted migration.');
  }

  const withoutDuration = activity.previous.filter((id) => id !== 'duration');
  const oldActivity = savedLayout(withoutDuration);
  oldActivity.column_order = ['event', 'source-alias', 'time', 'source', 'target', 'target-alias',
    'action', 'source-ota-alias', 'tones', 'channel', 'encryption', 'related'];
  oldActivity.hidden_columns = ['source', 'source-alias', 'source-ota-alias', 'tones'];
  oldActivity.column_widths = { 'source-ota-alias': 214, channel: 375 };
  oldActivity.collapsed_groups = ['site-1'];
  const upgradedActivity = migrate(activity, oldActivity);
  assert.deepEqual(upgradedActivity.column_order,
    ['event', 'source', 'time', 'duration', 'target', 'action', 'tones', 'channel', 'encryption', 'related'],
    'The earlier Duration upgrade still inserts immediately after Seen in a personalized order.');
  assert.deepEqual(upgradedActivity.hidden_columns, ['source', 'tones']);
  assert.deepEqual(upgradedActivity.column_widths, { channel: 375, source: 214 },
    'An OTA-name width is preferred when no configured-alias width was saved.');
  assert.deepEqual(upgradedActivity.collapsed_groups, ['site-1']);
  assert.equal(layouts.normalize(columns(activity.current), upgradedActivity).reset, false);

  const essential = columns(activity.current).map((column) =>
    column.id === 'source' ? { ...column, essential: true } : column);
  assert.equal(migrate(activity, oldActivity, essential), oldActivity,
    'Migration must not bypass current essential-column validation.');
  for (const type of ['activity-conventional-analog-v1', 'radios', 'radios.future',
    'retained-statistics-v3.radio_system.radios', 'call-matching-duplicates', 'constructor']) {
    assert.equal(layouts.mergePartyColumns(type, columns(activity.current), oldActivity), oldActivity,
      'Unrelated table types do not opt into this migration.');
  }
  assert.equal(layouts.mergePartyColumns('activity', columns(activity.current), null), null);
  console.log('Party column layout migration checks passed.');
}

module.exports = { checkPartyColumnLayouts };
if (require.main === module) {
  checkPartyColumnLayouts(path.resolve(process.argv[2] || defaultCore))
    .catch((error) => { console.error(error); process.exitCode = 1; });
}
