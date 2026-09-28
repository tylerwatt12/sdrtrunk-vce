'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const application = fs.readFileSync(path.resolve(process.argv[2] ||
  path.resolve(__dirname, '../../../../stats-web/assets/app.js')), 'utf8');

function functionSource(signature) {
  const start = application.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const opening = application.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = opening; index < application.length; index += 1) {
    if (application[index] === '{') depth += 1;
    else if (application[index] === '}' && --depth === 0) return application.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

const revision = 'a'.repeat(64);
const envelope = {
  revision,
  settings: {
    patch_group_streaming_option: 'INDIVIDUAL', audio_record_format: 'MP3', mp3_setting: 'CBR_16',
    mp3_input_audio_format: 'SR_16000', mp3_normalize_audio: false, stats_logging_enabled: true,
    stats_detailed_history_enabled: false, stats_logging_retention_days: 30
  },
  options: {
    patch_group_streaming_options: [], audio_record_formats: [], mp3_settings: [],
    mp3_input_audio_formats_by_setting: {}, minimum_stats_logging_retention_days: 1,
    maximum_stats_logging_retention_days: 365
  }
};

const calls = [];
let response = { ok: true, status: 200, json: async () => envelope };
const context = {
  jsonDocumentFetch: async (requestPath, options) => {
    calls.push({ path: requestPath, options });
    return response;
  }
};
vm.createContext(context);
vm.runInContext([
  functionSource('function decodeOperationalPreferencesEnvelope(value)'),
  functionSource("async function requestOperationalPreference(method = 'GET'")
].join('\n'), context);

async function main() {
  assert.equal(context.decodeOperationalPreferencesEnvelope(envelope).revision, revision);
  assert.throws(() => context.decodeOperationalPreferencesEnvelope({ ...envelope, revision: 'bad' }),
    /settings this page could not read/);
  assert.throws(() => context.decodeOperationalPreferencesEnvelope({ ...envelope, settings: {} }),
    /settings this page could not read/);

  assert.equal((await context.requestOperationalPreference()).revision, revision);
  assert.equal(calls.at(-1).path, '/api/v1/admin/operational-preferences');
  const saved = await context.requestOperationalPreference('PUT', 'stats_logging_enabled', false, revision);
  assert.equal(saved.revision, revision);
  assert.equal(calls.at(-1).path, '/api/v1/admin/operational-preferences/stats_logging_enabled');
  assert.equal(calls.at(-1).options.headers['If-Match'], `"${revision}"`);
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { value: false });

  response = { ok: false, status: 409, json: async () => envelope };
  await assert.rejects(context.requestOperationalPreference('PUT', 'mp3_setting', 'CBR_32', revision),
    (error) => error.status === 409 && error.current?.revision === revision);
  response = { ok: false, status: 422,
    json: async () => ({ error: { message: 'Input audio rate is not supported' } }) };
  await assert.rejects(context.requestOperationalPreference('PUT', 'mp3_setting', 'CBR_32', revision),
    /Input audio rate is not supported/);

  assert.match(application, /id: 'operations', label: 'Call output & activity'/);
  assert.match(application, /active === 'operations'\) await renderAdminOperationalPreferences/);
  assert.match(application, /Administration > Call output & activity/);
  assert.doesNotMatch(application, /Stats & Web > Stats Server/);

  const page = functionSource('async function renderAdminOperationalPreferences(');
  assert.match(page, /node\('div', 'settings-page-form operational-preferences'\)/);
  assert.match(page, /body\.append\(introduction, workspace\)/);
  assert.match(page,
    /content\.append\(section\('Call output & activity', body, sectionActionHost\(reload\)\)\)/);
  assert.doesNotMatch(page, /const heading = node\('div', 'ui-action-row'\)/,
    'Reload belongs in the section heading instead of a detached blank action row');
  assert.match(page, /lane\('output', 'Calls & audio'/);
  assert.match(page, /lane\('activity', 'Activity history'/);
  assert.match(page, /These settings apply across the receiver/);
  assert.match(page, /A patch group joins two or more talkgroups/);
  assert.match(page, /This does not turn recording on/);
  assert.match(page, /This does not change tuner sample rate or/);
  assert.match(page, /Turning this off stops/);
  assert.match(page, /Lowering this number can permanently remove/);
  assert.match(page, /const drafts = new Map\(\)/);
  assert.match(page, /drafts\.delete\(field\.id\)/);
  assert.match(page, /state\.textContent = changed \? 'Unsaved change' : ''/);
  assert.match(page, /actions\.hidden = !changed/);
  assert.match(page, /drafts\.clear\(\)/);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
