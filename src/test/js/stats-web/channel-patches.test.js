'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');

const application = fs.readFileSync(process.argv[2], 'utf8');

function functionSource(name, nextName) {
  const start = application.indexOf(`function ${name}(`);
  let end = application.indexOf(`\nfunction ${nextName}(`, start);
  if (end < 0) end = application.indexOf(`\nasync function ${nextName}(`, start);
  assert.notEqual(start, -1, `Missing ${name}`);
  assert.notEqual(end, -1, `Missing function after ${name}`);
  return application.slice(start, end);
}

const patchMembersByLocalGroup = Function(`
  ${functionSource('patchMembersByLocalGroup', 'renderChannelNeighbors')}
  return patchMembersByLocalGroup;
`)();

const members = patchMembersByLocalGroup([
  { local_patch_group_id: 500, local_talkgroup_id: 101 },
  { local_patch_group_id: 700, local_talkgroup_id: 303 },
  { local_patch_group_id: 500, local_talkgroup_id: 202 },
  { local_talkgroup_id: 999 }
]);

assert.deepEqual([...members.keys()], [500, 700]);
assert.deepEqual(members.get(500).map((row) => row.local_talkgroup_id), [101, 202]);
assert.deepEqual(members.get(700).map((row) => row.local_talkgroup_id), [303]);
assert.equal(members.has(undefined), false, 'Malformed rows must not collapse into one undefined patch group');

const patchRenderer = functionSource('renderTrunkedChannel', 'specialIdentifierLabel');
assert.match(patchRenderer, /groupIdentityLink\(row, row\.local_patch_group_id\)/);
assert.match(patchRenderer, /groupIdentityLink\(member, member\.local_talkgroup_id\)/);
assert.match(patchRenderer, /radioLink\(member, member\.local_radio_id\)/);
assert.doesNotMatch(patchRenderer, /row\.patch_group|member\.talkgroup_id|member\.radio_id/);
