'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const workflow = fs.readFileSync(process.argv[2] || '.github/workflows/nightly.yml', 'utf8')
  .replace(/\r\n/g, '\n');
function step(name) {
  const marker = `      - name: ${name}\n`;
  const start = workflow.indexOf(marker);
  assert.notEqual(start, -1, `Missing workflow step: ${name}`);
  const end = workflow.indexOf('\n      - name:', start + marker.length);
  return workflow.slice(start, end < 0 ? undefined : end);
}
function literal(section, marker, indent) {
  const start = section.indexOf(marker);
  assert.notEqual(start, -1, `Missing executable block: ${marker}`);
  const lines = section.slice(start + marker.length).split('\n');
  const result = [];
  for (const line of lines) {
    if (line.trim() && !line.startsWith(' '.repeat(indent))) break;
    result.push(line.slice(indent));
  }
  return result.join('\n').trim();
}

const operation = literal(step('Require an explicit publication choice'), '        run: |\n', 10);
const bash = process.platform === 'win32' ? path.join(process.env.ProgramFiles, 'Git', 'bin', 'bash.exe') : 'bash';
function operationAllowed(mode, confirmed, candidate, ref) {
  const result = spawnSync(bash, ['-e', '-c', operation], { encoding: 'utf8', timeout: 10_000,
    env: { ...process.env, MODE: mode, CONFIRM_PUBLICATION: confirmed, CANDIDATE_RUN_ID: candidate,
      GITHUB_REF: ref } });
  assert.ifError(result.error);
  return result.status === 0;
}
assert(operationAllowed('prepare', 'false', '', 'refs/heads/main'));
assert(operationAllowed('prepare', 'false', '', 'refs/heads/codex/candidate'));
assert(operationAllowed('publish', 'true', '100', 'refs/heads/main'));
for (const args of [
  ['publish', 'false', '100', 'refs/heads/main'],
  ['publish', 'true', '100', 'refs/heads/codex/candidate'],
  ['publish', 'true', '', 'refs/heads/main'],
  ['publish', 'true', '0', 'refs/heads/main'],
  ['publish', 'true', '-1', 'refs/heads/main'],
  ['publish', 'true', '1; echo unexpected', 'refs/heads/main'],
  ['prepare', 'true', '', 'refs/heads/main'],
  ['prepare', 'false', '100', 'refs/heads/main'],
  ['other', 'false', '', 'refs/heads/main']
]) assert.equal(operationAllowed(...args), false, `Operation should fail closed: ${args}`);

const source = literal(step('Require the latest successful preparation of this exact commit'), '          script: |\n', 12);
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const guard = new AsyncFunction('github', 'context', 'core', source);
const platforms = ['linux64', 'linux-arm64', 'win64', 'win-arm64', 'mac64', 'mac-arm64'];
const sha = 'a'.repeat(40);
function fixture() {
  return {
    id: '100',
    run: { status: 'completed', conclusion: 'success', event: 'workflow_dispatch',
      path: '.github/workflows/nightly.yml', head_repository: { full_name: 'example/vce' }, head_sha: sha },
    jobs: ['Package candidate', 'Candidate ready', 'Test candidate / Main CI gate',
      ...platforms.map(platform => `Native runtime smoke (${platform})`)]
      .map(name => ({ name, conclusion: 'success' })),
    artifacts: [{ id: 501, name: 'nightly-candidate-100', expired: false }],
    newer: [], newerArtifacts: []
  };
}
async function evaluate(state) {
  const outputs = {};
  const getWorkflowRun = async ({ run_id }) => {
    assert.equal(run_id, 100);
    return { data: state.run };
  };
  const listJobsForWorkflowRun = Symbol('jobs');
  const listWorkflowRuns = Symbol('runs');
  const listWorkflowRunArtifacts = async ({ run_id }) => {
    assert.equal(run_id, 101);
    return { data: { artifacts: state.newerArtifacts } };
  };
  const github = { rest: { actions: { getWorkflowRun, listJobsForWorkflowRun,
    listWorkflowRuns, listWorkflowRunArtifacts } },
    paginate: async (method, params) => {
      if (method === listJobsForWorkflowRun) {
        assert.equal(params.filter, 'latest');
        return state.jobs;
      }
      if (method === listWorkflowRunArtifacts) return state.artifacts;
      assert.equal(method, listWorkflowRuns);
      assert.equal(params.head_sha, sha);
      assert.equal(params.status, 'success');
      return state.newer;
    } };
  const savedId = process.env.CANDIDATE_RUN_ID;
  const savedRepo = process.env.GITHUB_REPOSITORY;
  process.env.CANDIDATE_RUN_ID = state.id;
  process.env.GITHUB_REPOSITORY = 'example/vce';
  try {
    await guard(github, { repo: { owner: 'example', repo: 'vce' }, sha },
      { setOutput: (name, value) => { outputs[name] = value; } });
    return outputs;
  } finally {
    if (savedId === undefined) delete process.env.CANDIDATE_RUN_ID;
    else process.env.CANDIDATE_RUN_ID = savedId;
    if (savedRepo === undefined) delete process.env.GITHUB_REPOSITORY;
    else process.env.GITHUB_REPOSITORY = savedRepo;
  }
}

const checks = literal(step('Verify candidate identity, exact file list, and every checksum'), '        run: |\n', 10);
const pythonBody = checks.match(/python3 - <<'PY'\n([\s\S]+?)\nPY/);
assert(pythonBody, 'Missing executable candidate checksum validator');
const python = process.env.PYTHON_BINARY || (process.platform === 'win32' ? 'python' : 'python3');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'vce-nightly-contract-'));
const candidate = path.join(temporary, 'candidate');
fs.mkdirSync(candidate);
const { createHash } = require('node:crypto');
const metadata = { format: 1, purpose: 'prepare', source_commit: sha, source_ref: 'refs/heads/main',
  run_id: '100', runtime: '25.0.1', channel: 'nightly' };
function resetCandidate() {
  for (const name of fs.readdirSync(candidate)) fs.rmSync(path.join(candidate, name), { recursive: true });
  for (const platform of platforms) fs.writeFileSync(path.join(candidate, `vce-${platform}-100.zip`), `ZIP fixture ${platform}`);
  fs.writeFileSync(path.join(candidate, 'build_info.txt'), `commit: ${sha}\nupdate-build: 100\n`);
  fs.writeFileSync(path.join(candidate, 'update.properties'), 'format=2\ntrack=nightly\nbuild=100\n');
  fs.writeFileSync(path.join(candidate, 'candidate.json'), JSON.stringify(metadata));
  rewriteChecksums();
}
function rewriteChecksums() {
  const lines = fs.readdirSync(candidate).filter(name => name !== 'SHA256SUMS').map(name =>
    `${createHash('sha256').update(fs.readFileSync(path.join(candidate, name))).digest('hex')}  ${name}`);
  fs.writeFileSync(path.join(candidate, 'SHA256SUMS'), lines.join('\n') + '\n');
}
function verified() {
  const result = spawnSync(python, ['-c', pythonBody[1]], { cwd: temporary, encoding: 'utf8', timeout: 10_000,
    env: { ...process.env, GITHUB_SHA: sha, CANDIDATE_RUN_ID: '100' } });
  assert.ifError(result.error);
  return result;
}

async function main() {
  assert.deepEqual(await evaluate(fixture()), { 'artifact-id': 501 });
  for (const id of ['-1', '0', 'NaN', '9007199254740992']) {
    const state = fixture(); state.id = id;
    await assert.rejects(evaluate(state), /Invalid preparation run ID/);
  }
  for (const [field, value] of [['status', 'in_progress'], ['conclusion', 'failure'],
    ['event', 'pull_request'], ['path', '.github/workflows/other.yml'], ['head_sha', 'b'.repeat(40)],
    ['head_repository', { full_name: 'other/vce' }]]) {
    const state = fixture(); state.run[field] = value;
    await assert.rejects(evaluate(state), /successful Nightly preparation/);
  }
  for (const job of fixture().jobs) {
    const missing = fixture(); missing.jobs = missing.jobs.filter(item => item.name !== job.name);
    await assert.rejects(evaluate(missing), /missing a successful/);
    const failed = fixture(); failed.jobs.find(item => item.name === job.name).conclusion = 'failure';
    await assert.rejects(evaluate(failed), /missing a successful/);
  }
  for (const artifacts of [[], [{ id: 501, name: 'nightly-candidate-99', expired: false }],
    [{ id: 501, name: 'nightly-candidate-100', expired: true }]]) {
    const state = fixture(); state.artifacts = artifacts;
    await assert.rejects(evaluate(state), /expired or are unavailable/);
  }
  const newer = fixture(); newer.newer = [{ id: 101 }];
  newer.newerArtifacts = [{ name: 'nightly-candidate-101' }];
  await assert.rejects(evaluate(newer), /newer successful preparation/);
  newer.newerArtifacts = [];
  assert.deepEqual(await evaluate(newer), { 'artifact-id': 501 }, 'A newer publication run is not a new preparation.');

  resetCandidate();
  let result = verified();
  assert.equal(result.status, 0, result.stderr);
  for (const tamper of [
    () => fs.appendFileSync(path.join(candidate, 'vce-win64-100.zip'), 'changed'),
    () => fs.rmSync(path.join(candidate, 'vce-linux-arm64-100.zip')),
    () => fs.writeFileSync(path.join(candidate, 'unexpected.txt'), 'unexpected'),
    () => fs.appendFileSync(path.join(candidate, 'SHA256SUMS'), fs.readFileSync(path.join(candidate, 'SHA256SUMS'), 'utf8').split('\n')[0] + '\n'),
    () => fs.writeFileSync(path.join(candidate, 'SHA256SUMS'), fs.readFileSync(path.join(candidate, 'SHA256SUMS'), 'utf8').split('\n').slice(1).join('\n')),
    () => fs.appendFileSync(path.join(candidate, 'SHA256SUMS'), `${'0'.repeat(64)}  ../outside\n`)
  ]) {
    resetCandidate(); tamper(); result = verified();
    assert.notEqual(result.status, 0, 'Changed, missing, extra, duplicated, incomplete or unsafe files must fail.');
  }
  for (const [field, value] of [['source_commit', 'b'.repeat(40)], ['run_id', '99'],
    ['purpose', 'publish'], ['channel', 'alpha'], ['runtime', '17'], ['format', 2]]) {
    resetCandidate();
    fs.writeFileSync(path.join(candidate, 'candidate.json'), JSON.stringify({ ...metadata, [field]: value }));
    rewriteChecksums();
    result = verified();
    assert.notEqual(result.status, 0, `A matching checksum must not admit the wrong ${field}.`);
  }
  console.log('Nightly operation authorization, candidate provenance, required gates, expiry, newer candidates and checksum tamper checks passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; })
  .finally(() => fs.rmSync(temporary, { recursive: true, force: true }));
