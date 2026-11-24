'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(process.argv[2], 'utf8');
const start = source.indexOf('function tunerOperatorState(tuner) {');
const end = source.indexOf('function tunerStoppedChannels(tuner)', start);
assert.ok(start >= 0 && end > start, 'Tuner state presentation helpers are present');
const context = vm.createContext({});
vm.runInContext(source.slice(start, end), context);
const channelStart = source.indexOf('function channelProcessingPresentation(row) {');
const channelEnd = source.indexOf('function channelSummaryCards(', channelStart);
assert.ok(channelStart >= 0 && channelEnd > channelStart);
vm.runInContext(source.slice(channelStart, channelEnd), context);
const label = (tuner) => JSON.parse(JSON.stringify(context.tunerOperatorStateLabel(tuner)));

test('a requested Live mode cannot hide a disconnected or failed tuner', () => {
  const live = { operator_state: 'live', status: 'enabled', available: true };
  assert.deepEqual(label(live), { label: 'Live', tone: 'success' });
  assert.deepEqual(label({ ...live, status: 'error', available: false }),
    { label: 'Tuner error', tone: 'danger', unavailable: true });
  assert.deepEqual(label({ ...live, status: 'removed', available: false }),
    { label: 'Disconnected', tone: 'warning', unavailable: true });
  assert.deepEqual(label({ ...live, available: false }),
    { label: 'Unavailable', tone: 'warning', unavailable: true });
  assert.equal(context.tunerOperatorState({ ...live, status: 'removed' }), 'live');
});

test('failure also overrides setup and a stale transition', () => {
  assert.deepEqual(label({ operator_state: 'setup', status: 'error', available: false,
    transition: 'going_live' }), { label: 'Tuner error', tone: 'danger', unavailable: true });
  assert.deepEqual(label({ operator_state: 'setup', status: 'unsupported', available: false }),
    { label: 'Unavailable', tone: 'warning', unavailable: true });
  assert.deepEqual(label({ operator_state: 'setup', status: 'enabled', available: true }),
    { label: 'Setup', tone: 'warning' });
});

test('intentional disable stays neutral even when its USB device is absent', () => {
  for (const status of ['disabled', 'removed', 'error']) {
    assert.deepEqual(label({ operator_state: 'disabled', status, available: false }),
      { label: 'Disabled', tone: 'neutral' });
  }
});

test('ordinary operator transitions retain their existing feedback', () => {
  for (const [transition, text] of Object.entries({ disabling: 'Stopping', starting_setup: 'Starting',
    going_live: 'Going live', restoring: 'Restarting channels' })) {
    assert.deepEqual(label({ operator_state: 'setup', status: 'disabled', available: false, transition }),
      { label: text, tone: 'warning' });
  }
});

test('channels waiting for USB recovery remain stoppable without claiming to be running', () => {
  const state = (processing_state) => JSON.parse(JSON.stringify(
    context.channelProcessingPresentation({ processing_state })));
  assert.deepEqual(state('RECOVERING'),
    { label: 'Reconnecting', tone: 'warning', icon: 'icon-tuner', canStop: true });
  assert.deepEqual(state('RUNNING'),
    { label: 'Running', tone: 'success', icon: 'icon-play', canStop: true });
  assert.deepEqual(state('STOPPED'),
    { label: 'Stopped', tone: 'neutral', icon: 'icon-stop', canStop: false });
});
