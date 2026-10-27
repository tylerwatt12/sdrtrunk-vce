'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const feature = path.resolve(process.argv[2] || path.resolve(__dirname,
  '../../../../stats-web/assets/features/network-visualizer'));

async function main() {
  const { P25_LABEL_TIER: tier, resolveP25NodeAttention: attention,
    resolveP25LabelTier: labelTier, isP25LinkDimmed: linkDimmed } =
    await import(pathToFileURL(path.join(feature, 'emphasis.js')).href);
  const focus = new Set(['radio-focus', 'group-old', 'group-new']);
  const moving = new Set([...focus, 'radio-other', 'group-other-old', 'group-other-new']);
  const idle = new Set();

  assert.equal(Object.isFrozen(tier), true, 'label priority is a shared immutable policy');
  assert.ok(tier.depth < tier.activity && tier.activity < tier.eventFocus &&
    tier.eventFocus < tier.keyboard && tier.keyboard < tier.pointer,
  'explicit label interaction wins over event focus, which wins over ordinary activity');

  const selected = attention({ id: 'group-new', type: 'talkgroup' }, focus, idle);
  assert.equal(selected.eventFocused, true);
  assert.equal(selected.activity, false, 'an event can focus an otherwise inactive entity');
  assert.equal(selected.clear, true);
  assert.equal(selected.dimmed, false);
  assert.equal(labelTier(selected), tier.eventFocus);
  const selectedActive = attention({ id: 'radio-focus', type: 'radio', active: true,
    signalAction: 'emergency' }, focus, moving);
  assert.equal(labelTier(selectedActive), tier.eventFocus,
    'activity on a selected participant does not create a competing priority');

  const outsiders = [
    { name: 'unrelated live call', node: { id: 'radio-call', type: 'radio', active: true } },
    { name: 'unrelated signal', node: { id: 'group-signal', type: 'talkgroup', signalAction: 'emergency' } },
    { name: 'another moving radio', node: { id: 'radio-other', type: 'radio' } },
    { name: 'another movement destination', node: { id: 'group-other-new', type: 'talkgroup' } },
    { name: 'system context', node: { id: 'system', type: 'system' } },
    { name: 'ordinary idle entity', node: { id: 'radio-idle', type: 'radio' } }
  ];
  for (const { name, node } of outsiders) {
    const state = attention(node, focus, moving);
    assert.equal(state.eventFocused, false, name);
    assert.equal(state.dimmed, true, `${name} fades while a different event owns focus`);
    assert.equal(labelTier(state), tier.depth, `${name} cannot bypass the event's label fade`);
  }
  assert.equal(attention(outsiders[0].node, focus, moving).clear, true,
    'fading retains the live call paint so its ordinary appearance can restore smoothly');
  assert.equal(attention(outsiders[2].node, focus, moving).participant, true,
    'the competing movement remains tracked even though it is faded');

  const unrelated = Object.freeze(attention(outsiders[0].node, focus, moving));
  for (const [interaction, expected] of [
    [{ pointed: true }, tier.pointer],
    [{ pressing: true }, tier.pointer],
    [{ focused: true }, tier.keyboard],
    [{ pointed: true, pressing: true, focused: true }, tier.pointer],
    [{ pressing: true, focused: true }, tier.pointer]
  ]) {
    assert.equal(labelTier(unrelated, interaction), expected,
      'deliberate interaction makes a background name readable');
    assert.equal(unrelated.dimmed, true, 'promoting a name never unfades its shape');
  }
  assert.equal(labelTier(selected, { focused: true }), tier.keyboard,
    'keyboard focus is above automatic event emphasis');
  assert.equal(labelTier(selected, { pointed: true, focused: true }), tier.pointer,
    'the pointed name is above both keyboard and automatic focus');
  assert.equal(labelTier(unrelated, { pointed: false, pressing: false, focused: false }), tier.depth,
    'leaving the name restores the still-focused background fade');

  for (const { name, node } of outsiders) {
    const restored = attention(node, idle, moving);
    assert.equal(restored.dimmed, false, `${name} restores when event focus ends`);
    assert.equal(labelTier(restored), restored.activity ? tier.activity : tier.depth,
      `${name} regains ordinary activity or depth priority`);
  }
  assert.equal(labelTier(attention(outsiders[1].node, idle, moving)), tier.activity,
    'ordinary signal highlighting remains available outside event focus');
  assert.equal(labelTier(attention({ id: 7, type: 'radio' }, new Set(['7']), idle)), tier.eventFocus,
    'node identity matching uses the same normalized keys as the graph');

  for (const link of [
    { source: 'radio-focus', target: 'group-new' },
    { source: { id: 'group-old' }, target: { id: 'radio-focus' } },
    { source: 'radio-focus', target: { id: 'group-old' } }
  ]) assert.equal(linkDimmed(link, focus), false, 'the focused event connections remain visible');
  for (const link of [
    { source: 'radio-focus', target: 'group-other-new' },
    { source: { id: 'radio-other' }, target: { id: 'group-other-new' } },
    { source: 'radio-idle', target: { id: 'group-idle' } }
  ]) {
    assert.equal(linkDimmed(link, focus), true,
      'a connection outside the focused event fades, including a separate moving affiliation');
    assert.equal(linkDimmed(link, idle), false, 'normal connections restore when focus ends');
  }
  assert.equal(linkDimmed({ source: 7, target: { id: 8 } }, new Set(['7', '8'])), false);
  assert.deepEqual([...focus], ['radio-focus', 'group-old', 'group-new'],
    'resolving labels and links never changes event ownership');
  assert.deepEqual([...moving], ['radio-focus', 'group-old', 'group-new',
    'radio-other', 'group-other-old', 'group-other-new'], 'presentation never changes movement tracking');
  console.log('P25 Visualizer event, activity, interaction and restoration priority checks passed.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
