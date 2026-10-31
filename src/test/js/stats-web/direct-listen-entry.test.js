'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { functionSource } = require('./source-functions.cjs');

async function main() {
  const { directListenEntryTarget: target } = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../stats-web/assets/core/direct-listen-entry.js')).href);
  const channel = '11111111-1111-4111-8111-111111111111';
  const reference = { kind: 'talkgroup', radio_system_key: 'p25:bee00:348', identity_key: 'v1-g-bee00-348-1201' };
  assert.deepEqual(target({ target_alias: 'Dispatch', target_id: 9999 }, reference), reference,
    'The typed canonical identity takes precedence over a displayed/local number or alias.');
  assert.notDeepEqual(target({}, { ...reference, radio_system_key: 'p25:bee00:349' }), reference);
  assert.equal(target({}, { ...reference, kind: 'radio' }), null);
  assert.equal(target({ protocol: 'P25', radio_system_key: reference.radio_system_key,
    target_form: 'TALKGROUP', target_id: 1201 }), null, 'A trunked P25 identity must not be invented.');
  assert.deepEqual(target({ protocol: 'DMR', tags: ['CONVENTIONAL'], configuration_id: channel,
    target_form: 'TALKGROUP', target_id: 40 }, { kind: 'channel', key: channel }),
    { kind: 'channel', key: channel, identity_key: 'v1-g-x-x-40' },
    'A conventional decoded talkgroup narrows the selected saved channel.');
  assert.deepEqual(target({ protocol: 'NBFM', tags: ['CONVENTIONAL'], configuration_id: channel,
    target_form: 'TALKGROUP', target_id: 40 }, { kind: 'channel', key: channel }),
    { kind: 'channel', key: channel }, 'An analog assigned number never becomes a talkgroup filter.');
  assert.deepEqual(target({ protocol: 'DMR', configuration_id: channel }, reference, { channelOnly: true }),
    { kind: 'channel', key: channel });
  assert.equal(target({}, { kind: 'channel', key: 'unknown' }), null);
  assert.equal(target({ protocol: 'P25', channel_kind: 'CONVENTIONAL', configuration_id: channel,
    identity_kind: 'Patch Group', native_id: 40 }, { kind: 'channel', key: channel }), null,
    'An unresolved digital patch must never silently subscribe to the entire channel.');
  assert.equal(target({ protocol: 'DMR', configuration_id: channel }, { kind: 'channel', key: channel }), null,
    'Only an explicit channel action can select all digital destinations.');

  const application = fs.readFileSync(path.resolve(__dirname, '../../../../stats-web/assets/app.js'), 'utf8');
  const recordingControl = new Function('directListenAction',
    `${functionSource(application, 'function recordingDirectListenControl(')}\nreturn recordingDirectListenControl;`)(
    (row, reference, options) => {
      const selection = target(row, reference, options);
      return selection ? { target: selection, label: options.label } : null;
    });
  const channelReference = { kind: 'channel', key: channel };
  const recording = { protocol: 'APCO25', call_type: 'GROUP', target_id: 9001, talkgroup_id: 9001,
    group_alias: 'Fire Dispatch', channel_id: channel, channel_entity_ref: channelReference,
    system_key: reference.radio_system_key, target_entity_ref: reference };
  assert.deepEqual(recordingControl(recording), { target: reference, label: 'Fire Dispatch' },
    'Recordings preserve the typed canonical group instead of subscribing to its observed Working ID.');
  assert.equal(recordingControl({ ...recording, target_entity_ref: null }), null,
    'An unresolved trunked P25 recording cannot invent a Direct Listen identity.');
  assert.equal(recordingControl({ ...recording, call_type: 'DIRECT', destination_radio_id: 123,
    target_entity_ref: { ...reference, kind: 'radio' } }), null,
    'A private call cannot fall back to listening to the whole channel.');
  assert.deepEqual(recordingControl({ protocol: 'NBFM', channel_id: channel,
    channel_entity_ref: channelReference, channel_name: 'Fire Channel' }),
    { target: channelReference, label: 'Fire Channel' });
  assert.deepEqual(recordingControl({ ...recording, protocol: 'DMR', channel_kind: 'CONVENTIONAL',
    target_entity_ref: channelReference, talkgroup_id: 40, target_id: 40 }),
    { target: { ...channelReference, identity_key: 'v1-g-x-x-40' }, label: 'Fire Dispatch' },
    'A conventional digital recording keeps its group restriction on the saved channel.');
  assert.equal(recordingControl({ ...recording,
    target_entity_ref: { ...reference, identity_key: 'invalid' } }), null);
  assert.deepEqual(recordingControl({ ...recording, protocol: 'NBFM', channel_name: 'Analog Channel' }),
    { target: channelReference, label: 'Analog Channel' },
    'An analog assigned group remains a channel subscription.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
