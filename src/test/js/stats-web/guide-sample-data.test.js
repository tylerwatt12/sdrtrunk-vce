'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

async function checkGuideSampleData(assetsRoot) {
  const assets = path.resolve(assetsRoot || process.argv[2] || 'stats-web/assets');
  const { createGuideSampleData } = await import(pathToFileURL(path.join(assets, 'guides/sample-data.js')).href);
  const samples = createGuideSampleData();
  let actualChannels = [{ id: 'saved', state: 'RUNNING' }];
  const actualTuners = [{ name: 'Saved tuner' }];
  const fixture = [{ id: 'sample', state: 'STOPPED' }];
  let renderedChannels;
  let renderedTuners;
  let channelRenders = 0;
  let tunerRenders = 0;
  const registerChannels = () => samples.register('channel-catalog', () => {
    channelRenders += 1;
    renderedChannels = samples.read('channel-catalog', actualChannels);
  });
  const unregisterChannels = registerChannels();
  samples.register('tuner-catalog', () => {
    tunerRenders += 1;
    renderedTuners = samples.read('tuner-catalog', actualTuners);
  });
  assert.equal(samples.read('channel-catalog', actualChannels), actualChannels,
    'An inactive adapter retains its actual live state.');

  const closeChannelStep = samples.activate([{ id: 'channel-catalog', value: fixture }]);
  assert.equal(samples.isActive('channel-catalog'), true);
  assert.deepEqual(renderedChannels, fixture);
  assert.notEqual(renderedChannels, fixture);
  assert.equal(tunerRenders, 0, 'Activating a region leaves unrelated UI regions alone.');
  fixture[0].state = 'MUTATED';
  renderedChannels[0].state = 'ALSO MUTATED';
  assert.equal(samples.read('channel-catalog', actualChannels)[0].state, 'STOPPED',
    'The scope owns its fixture snapshot and each render gets a separate copy.');
  assert.deepEqual(actualChannels, [{ id: 'saved', state: 'RUNNING' }],
    'Displaying or changing a fixture cannot pollute actual state.');

  const closeTunerStep = samples.activate([{ id: 'tuner-catalog', value: [{ name: 'Sample tuner' }] }]);
  assert.equal(channelRenders, 1, 'An unrelated nested scope does not redraw this region.');
  assert.equal(renderedTuners[0].name, 'Sample tuner');
  const closeNestedChannelStep = samples.activate([{ id: 'channel-catalog', value: [{ id: 'second sample' }] }]);
  assert.equal(renderedChannels[0].id, 'second sample');
  closeChannelStep();
  assert.equal(renderedChannels[0].id, 'second sample',
    'An earlier cleanup cannot remove a newer sample for the same region.');
  const beforeUnregister = channelRenders;
  unregisterChannels();
  closeNestedChannelStep();
  assert.equal(channelRenders, beforeUnregister, 'Disposed UI adapters are no longer called.');
  assert.equal(samples.isActive('channel-catalog'), false);
  assert.equal(samples.isActive('tuner-catalog'), true, 'The unrelated scope remains active.');
  closeTunerStep();
  assert.equal(renderedTuners, actualTuners, 'Closing a scope restores actual state.');
  closeTunerStep();
  assert.equal(tunerRenders, 2, 'Repeated cleanup is harmless.');

  const lateStep = samples.activate([{ id: 'channel-catalog', value: [{ id: 'late sample' }] }]);
  const unregisterAgain = registerChannels();
  assert.equal(renderedChannels[0].id, 'late sample', 'Registering during a step immediately presents its sample.');
  actualChannels = [{ id: 'saved-after-refresh', state: 'STOPPED' }];
  lateStep();
  assert.equal(renderedChannels, actualChannels,
    'Step cleanup asks the adapter to render its latest real state, rather than a saved stale snapshot.');
  unregisterAgain();

  const nested = createGuideSampleData();
  const closeOuter = nested.activate([{ id: 'row', value: 'outer' }]);
  const closeInner = nested.activate([{ id: 'row', value: 'inner' }]);
  closeInner();
  assert.equal(nested.read('row', 'actual'), 'outer', 'Nested cleanup restores the previous scoped fixture.');
  closeOuter();
  assert.equal(nested.read('row', 'actual'), 'actual');

  const transactional = createGuideSampleData();
  let healthyView = null;
  transactional.register('healthy', () => { healthyView = transactional.read('healthy', 'actual'); });
  transactional.register('broken', () => {
    if (transactional.isActive('broken')) throw new Error('Rendering failed');
  });
  assert.throws(() => transactional.activate([
    { id: 'healthy', value: 'sample' }, { id: 'broken', value: 'sample' }
  ]), /Rendering failed/);
  assert.equal(healthyView, 'actual', 'An adapter failure rolls the complete activation back.');
  assert.equal(transactional.isActive('healthy'), false);
  assert.equal(transactional.isActive('broken'), false);
  assert.throws(() => transactional.activate([
    { id: 'healthy', value: 'sample' }, { id: 'bad', value: () => {} }
  ]), /clone|cloned/);
  assert.equal(transactional.isActive('healthy'), false, 'Uncloneable fixtures fail before any sample appears.');
  assert.throws(() => transactional.activate([{ id: 'a', value: 1 }, { id: 'a', value: 2 }]), /unique/);
  assert.throws(() => transactional.activate([{ id: 'a' }]), /value/);
  assert.throws(() => transactional.register('', () => {}), /ID/);

  const clearScope = samples.activate([{ id: 'tuner-catalog', value: [] }]);
  samples.clear();
  assert.equal(renderedTuners, actualTuners, 'Global disposal restores registered views.');
  clearScope();
  assert.equal(samples.isActive('tuner-catalog'), false);

  class RegionRoot extends EventTarget {
    attributes = new Map();
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    removeAttribute(name) { this.attributes.delete(name); }
  }
  const shielded = createGuideSampleData();
  const root = new RegionRoot();
  root.setAttribute('data-guide-sample', 'previous-marker');
  const renderingMarkers = [];
  shielded.register('form', () => { renderingMarkers.push(root.getAttribute('data-guide-sample')); }, { root });
  const stopFormStep = shielded.activate([{ id: 'form', value: { name: 'Sample' } }]);
  assert.equal(renderingMarkers[0], '', 'A sample region is protected before its renderer is called.');
  let mutations = 0;
  const mutationEvents = ['click', 'auxclick', 'dblclick', 'contextmenu', 'beforeinput', 'input', 'change', 'submit',
    'pointerdown', 'pointerup', 'pointermove', 'mousedown', 'mouseup', 'mousemove',
    'touchstart', 'touchmove', 'touchend', 'dragstart', 'dragover', 'drop', 'wheel'];
  for (const type of mutationEvents) {
    root.addEventListener(type, () => { mutations += 1; });
    const event = new Event(type, { cancelable: true });
    assert.equal(root.dispatchEvent(event), false, `${type} cannot perform a browser default action in a sample region.`);
  }
  assert.equal(mutations, 0, 'Sample events cannot reach mutation handlers.');
  const guideOutsideRegion = new EventTarget();
  guideOutsideRegion.addEventListener('click', () => { mutations += 1; });
  guideOutsideRegion.dispatchEvent(new Event('click'));
  assert.equal(mutations, 1, 'Controls outside the registered sample region remain interactive.');
  stopFormStep();
  assert.equal(root.getAttribute('data-guide-sample'), 'previous-marker');
  assert.equal(renderingMarkers.at(-1), 'previous-marker', 'The original marker is restored before rendering real data.');
  for (const type of mutationEvents) assert.equal(root.dispatchEvent(new Event(type, { cancelable: true })), true);
  assert.equal(mutations, mutationEvents.length + 1,
    'Scope cleanup restores ordinary pointer, touch, drag, wheel, and form events.');

  const keyboardRoot = new RegionRoot();
  shielded.register('keyboard-control', () => {}, { root: keyboardRoot });
  const stopKeyboardStep = shielded.activate([{ id: 'keyboard-control', value: [] }]);
  const receivedKeys = [];
  const keyEvent = (type, key) => {
    const event = new Event(type, { cancelable: true });
    Object.defineProperty(event, 'key', { value: key });
    return event;
  };
  for (const type of ['keydown', 'keyup']) {
    keyboardRoot.addEventListener(type, event => { receivedKeys.push(`${event.type}:${event.key}`); });
    assert.equal(keyboardRoot.dispatchEvent(keyEvent(type, 'Enter')), false,
      'A sample cannot trigger an Enter key action.');
    assert.equal(keyboardRoot.dispatchEvent(keyEvent(type, ' ')), false);
    assert.equal(keyboardRoot.dispatchEvent(keyEvent(type, 'Tab')), true,
      'Keyboard focus navigation remains available during a sample.');
    assert.equal(keyboardRoot.dispatchEvent(keyEvent(type, 'Escape')), true,
      'Escape still reaches the shared modal dismissal handler.');
  }
  assert.deepEqual(receivedKeys, ['keydown:Tab', 'keydown:Escape', 'keyup:Tab', 'keyup:Escape']);
  stopKeyboardStep();
  for (const type of ['keydown', 'keyup']) {
    assert.equal(keyboardRoot.dispatchEvent(keyEvent(type, 'Enter')), true);
    assert.equal(keyboardRoot.dispatchEvent(keyEvent(type, ' ')), true);
  }
  assert.deepEqual(receivedKeys.slice(4), ['keydown:Enter', 'keydown: ', 'keyup:Enter', 'keyup: '],
    'Cleanup restores keyboard handlers with their original event behavior.');

  const sharedRoot = new RegionRoot();
  const unregisterFirst = shielded.register('first', () => {}, { root: sharedRoot });
  const unregisterSecond = shielded.register('second', () => {}, { root: sharedRoot });
  shielded.activate([{ id: 'first', value: 1 }, { id: 'second', value: 2 }]);
  unregisterFirst();
  assert.equal(sharedRoot.dispatchEvent(new Event('click', { cancelable: true })), false,
    'One registration cannot remove protection still owned by another region.');
  unregisterSecond();
  assert.equal(sharedRoot.dispatchEvent(new Event('click', { cancelable: true })), true);
  assert.equal(sharedRoot.getAttribute('data-guide-sample'), null, 'Final unregistration restores an absent marker.');
  const lateRoot = new RegionRoot();
  const unregisterLate = shielded.register('first', () => {
    assert.equal(lateRoot.getAttribute('data-guide-sample'), '');
  }, { root: lateRoot });
  assert.equal(lateRoot.dispatchEvent(new Event('click', { cancelable: true })), false,
    'Adapters registered during an active scope receive the same event protection.');
  unregisterLate();
  assert.equal(lateRoot.getAttribute('data-guide-sample'), null);

  const failedRoot = new RegionRoot();
  shielded.register('failing-form', () => {
    if (shielded.isActive('failing-form')) throw new Error('Failed form renderer');
  }, { root: failedRoot });
  assert.throws(() => shielded.activate([{ id: 'failing-form', value: [] }]), /Failed form renderer/);
  assert.equal(failedRoot.getAttribute('data-guide-sample'), null);
  assert.equal(failedRoot.dispatchEvent(new Event('submit', { cancelable: true })), true,
    'Activation rollback removes event shields as well as fixture data.');
  const clearRoot = new RegionRoot();
  shielded.register('clear-form', () => {}, { root: clearRoot });
  shielded.activate([{ id: 'clear-form', value: [] }]);
  shielded.clear();
  assert.equal(clearRoot.getAttribute('data-guide-sample'), null);
  assert.equal(clearRoot.dispatchEvent(new Event('input', { cancelable: true })), true);

  console.log('Guide sample scopes, adapter lifecycle, event protection, real-state isolation, and failure rollback checks passed.');
}
module.exports = { checkGuideSampleData };
if (require.main === module) checkGuideSampleData().catch(error => { console.error(error); process.exitCode = 1; });
