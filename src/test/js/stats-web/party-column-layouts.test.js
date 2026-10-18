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

async function checkPartyColumnLayouts(core = defaultCore) {
  const layouts = await import(pathToFileURL(path.join(core, 'table-layout.js')).href);
  const savedLayout = (ids) => layouts.persisted(layouts.normalize(columns(ids), null));
  const migrate = (definition, saved, declared = columns(definition.current)) =>
    layouts.mergePartyColumns(definition.type, declared, saved);

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
  for (const type of ['activity-conventional-analog-v1', 'radios', 'constructor']) {
    assert.equal(layouts.mergePartyColumns(type, columns(activity.current), oldActivity), oldActivity,
      'Only the two named table schemas opt into this migration.');
  }
  assert.equal(layouts.mergePartyColumns('activity', columns(activity.current), null), null);
  console.log('Party column layout migration checks passed.');
}

module.exports = { checkPartyColumnLayouts };
if (require.main === module) {
  checkPartyColumnLayouts(path.resolve(process.argv[2] || defaultCore))
    .catch((error) => { console.error(error); process.exitCode = 1; });
}
