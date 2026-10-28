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

function job(name, document = workflow) {
  const marker = `\n  ${name}:\n`;
  const start = document.indexOf(marker);
  assert.notEqual(start, -1, `Missing workflow job: ${name}`);
  const remaining = document.slice(start + marker.length);
  const next = /\n  [a-z][a-z_-]+:\n/.exec(remaining);
  return document.slice(start, next ? start + marker.length + next.index : undefined);
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
      path: '.github/workflows/nightly.yml', head_repository: { full_name: 'example/vce' }, head_sha: sha,
      head_branch: 'main' },
    jobs: ['Package candidate', 'Candidate ready', 'Test candidate / Main CI gate',
      ...platforms.map(platform => `Native runtime smoke (${platform})`)]
      .map(name => ({ name, conclusion: 'success' })),
    artifacts: [{ id: 501, name: 'nightly-candidate-100', expired: false },
      { id: 502, name: 'radio-system-catalog-100', expired: false }],
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
const catalog = path.join(temporary, 'radio-system-catalog');
const catalogFile = path.join(catalog, 'dsdplus.networks');
const catalogBytes = Buffer.from('synthetic catalog');
fs.mkdirSync(catalog);
const metadata = { format: 1, purpose: 'prepare', source_commit: sha, source_ref: 'refs/heads/main',
  run_id: '100', runtime: '25.0.1', channel: 'nightly',
  radio_system_catalog_sha256: createHash('sha256').update(catalogBytes).digest('hex') };
function resetCandidate() {
  for (const name of fs.readdirSync(candidate)) fs.rmSync(path.join(candidate, name), { recursive: true });
  for (const platform of platforms) fs.writeFileSync(path.join(candidate, `vce-${platform}-100.zip`), `ZIP fixture ${platform}`);
  fs.writeFileSync(path.join(candidate, 'build_info.txt'), `commit: ${sha}\nupdate-build: 100\n`);
  fs.writeFileSync(path.join(candidate, 'update.properties'), 'format=2\ntrack=nightly\nbuild=100\n');
  fs.writeFileSync(path.join(candidate, 'candidate.json'), JSON.stringify(metadata));
  fs.writeFileSync(catalogFile, catalogBytes);
  rewriteChecksums();
}
function rewriteChecksums() {
  const lines = fs.readdirSync(candidate).filter(name => name !== 'SHA256SUMS').map(name =>
    `${createHash('sha256').update(fs.readFileSync(path.join(candidate, name))).digest('hex')}  ${name}`);
  fs.writeFileSync(path.join(candidate, 'SHA256SUMS'), lines.join('\n') + '\n');
}
function verified() {
  const result = spawnSync(python, ['-c', pythonBody[1]], { cwd: temporary, encoding: 'utf8', timeout: 10_000,
    env: { ...process.env, GITHUB_SHA: sha, CANDIDATE_RUN_ID: '100',
      CANDIDATE_SOURCE_REF: 'refs/heads/main' } });
  assert.ifError(result.error);
  return result;
}

const stagingStep = 'Stage stable Nightly download names without changing package contents';
const publishStep = 'Publish the approved candidate files';
const cleanupStep = 'Remove superseded Nightly packages after verifying the new uploads';
const staging = literal(step(stagingStep), '        run: |\n', 10)
  .match(/python3 - <<'PY'\n([\s\S]+?)\nPY/);
assert(staging, 'Missing executable stable download staging');
const cleanup = new AsyncFunction('github', 'context', 'core',
  literal(step(cleanupStep), '          script: |\n', 12));
const publicNames = [...platforms.map(platform => `vce-${platform}-nightly.zip`),
  'build_info.txt', 'candidate.json', 'SHA256SUMS', 'update.properties'];

function verifyStagedDownloads() {
  resetCandidate();
  const original = new Map(fs.readdirSync(candidate).map(name =>
    [name, fs.readFileSync(path.join(candidate, name))]));
  const result = spawnSync(python, ['-c', staging[1]], { cwd: temporary, encoding: 'utf8', timeout: 10_000,
    env: { ...process.env, CANDIDATE_RUN_ID: '100' } });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
  const published = path.join(temporary, 'nightly-assets');
  assert.deepEqual(fs.readdirSync(published).sort(), [...publicNames].sort());
  assert.deepEqual(fs.readdirSync(candidate).sort(), [...original.keys()].sort(),
    'Staging must leave the prepared file inventory unchanged.');
  for (const [name, bytes] of original) {
    assert.deepEqual(fs.readFileSync(path.join(candidate, name)), bytes,
      `Staging must leave prepared candidate bytes unchanged: ${name}`);
  }
  for (const name of publicNames.filter(name => name !== 'SHA256SUMS')) {
    const preparedName = name.endsWith('-nightly.zip') ? name.replace(/-nightly\.zip$/, '-100.zip') : name;
    assert.deepEqual(fs.readFileSync(path.join(published, name)), original.get(preparedName),
      `Published ZIP contents and embedded identity must match the tested candidate: ${name}`);
  }
  const checksums = fs.readFileSync(path.join(published, 'SHA256SUMS'), 'utf8').trimEnd().split(/\r?\n/)
    .map(line => {
      const match = /^([0-9a-f]{64})  (.+)$/.exec(line);
      assert(match, `Invalid public checksum entry: ${line}`);
      return { digest: match[1], name: match[2] };
    });
  assert.equal(new Set(checksums.map(item => item.name)).size, checksums.length,
    'Public checksums must not contain duplicate names.');
  assert.deepEqual(checksums.map(item => item.name).sort(), publicNames.filter(name => name !== 'SHA256SUMS').sort(),
    'Public checksums must list exactly the stable ZIP names and unchanged candidate metadata.');
  for (const { digest, name } of checksums) {
    assert.equal(createHash('sha256').update(fs.readFileSync(path.join(published, name))).digest('hex'), digest,
      `Public checksum must match staged bytes: ${name}`);
  }
  assert.notDeepEqual(fs.readFileSync(path.join(published, 'SHA256SUMS')), original.get('SHA256SUMS'),
    'Public checksums must be regenerated for renamed downloads.');
}

function cleanupFixture() {
  const uploaded = publicNames.map((name, index) => ({ id: 2000 + index, name, state: 'uploaded', size: 64 + index }));
  const obsoleteNames = [...platforms.map(platform => `vce-${platform}-99.zip`),
    ...['linux', 'osx', 'windows'].flatMap(platform => ['aarch64', 'x86_64']
      .map(architecture => `sdrtrunk-vce-${platform}-${architecture}-vnightly.zip`))];
  const preservedNames = ['release-notes.txt', 'vce-win64-0.6.2-alpha-10.zip', 'vce-linux64-99.zip.backup',
    'vce-linux64-99-extra.zip', 'vce-linux64--99.zip', 'vce-linux64-abc.zip', 'vce-other-99.zip',
    'prefix-vce-linux64-99.zip', 'vce-linux64-99.tar.gz', 'sdrtrunk-vce-linux-aarch64-vnightly.zip.backup',
    'sdrtrunk-vce-linux-arm64-vnightly.zip', 'sdrtrunk-vce-mac-x86_64-vnightly.zip'];
  const old = obsoleteNames.map((name, index) => ({ id: 3000 + index, name }));
  const preserved = preservedNames.map((name, index) => ({ id: 4000 + index, name }));
  const firstPage = Array.from({ length: 100 }, (_, index) => ({ id: 5000 + index, name: `unrelated-${index}.txt` }));
  return { uploaded, releaseId: '123', release: { id: 123 },
    pages: [firstPage, [...uploaded.map(asset => ({ ...asset })), ...old, ...preserved]],
    obsoleteIds: old.map(asset => asset.id), deleted: [], paginationCalls: 0, releaseCalls: 0 };
}

async function evaluateCleanup(state) {
  const listReleaseAssets = Symbol('release assets');
  const github = { rest: { repos: {
    getReleaseByTag: async (params) => {
      assert.deepEqual(params, { owner: 'example', repo: 'vce', tag: 'nightly' });
      state.releaseCalls++;
      return { data: state.release };
    },
    listReleaseAssets,
    deleteReleaseAsset: async (params) => {
      assert.equal(params.owner, 'example');
      assert.equal(params.repo, 'vce');
      state.deleted.push(params.asset_id);
    }
  } }, paginate: async (method, params) => {
    assert.equal(method, listReleaseAssets, 'Cleanup must use the paginated release asset API.');
    assert.deepEqual(params, { owner: 'example', repo: 'vce', release_id: 123, per_page: 100 });
    state.paginationCalls++;
    return state.pages.flat();
  } };
  const saved = { RELEASE_ID: process.env.RELEASE_ID, UPLOADED_ASSETS: process.env.UPLOADED_ASSETS };
  process.env.RELEASE_ID = state.releaseId;
  process.env.UPLOADED_ASSETS = JSON.stringify(state.uploaded);
  try {
    await cleanup(github, { repo: { owner: 'example', repo: 'vce' } }, { info() {} });
  } finally {
    for (const [name, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }
}

async function verifyGuardedCleanup() {
  const complete = cleanupFixture();
  await evaluateCleanup(complete);
  assert.equal(complete.paginationCalls, 1);
  assert.deepEqual(complete.deleted.sort((a, b) => a - b), complete.obsoleteIds.sort((a, b) => a - b),
    'Delete every recognized old ZIP across pages while retaining stable downloads and unrelated files.');
  for (const tamper of [
    state => state.uploaded.pop(),
    state => state.uploaded.push({ ...state.uploaded[0] }),
    state => { state.uploaded[0].name = state.uploaded[1].name; },
    state => { state.uploaded[0].name = 'unexpected.zip'; },
    state => { state.uploaded[0].state = 'starter'; },
    state => { state.uploaded[0].size = 0; }
  ]) {
    const incomplete = cleanupFixture(); tamper(incomplete);
    await assert.rejects(evaluateCleanup(incomplete), /uploads are incomplete/);
    assert.deepEqual(incomplete.deleted, [], 'Incomplete upload outputs must never delete previous downloads.');
    assert.equal(incomplete.releaseCalls, 0, 'Validate every upload output before reading or changing the release.');
  }
  const wrongRelease = cleanupFixture(); wrongRelease.release.id = 124;
  await assert.rejects(evaluateCleanup(wrongRelease), /release identity changed/);
  assert.deepEqual(wrongRelease.deleted, []);
  assert.equal(wrongRelease.paginationCalls, 0);
  for (const tamper of [
    state => state.pages[1].shift(),
    state => { state.pages[1][0].id = 9999; },
    state => { state.pages[1][0].name = 'replaced.zip'; },
    state => { state.pages[1][0].size++; },
    state => { state.pages[1][0].state = 'starter'; }
  ]) {
    const unverified = cleanupFixture(); tamper(unverified);
    await assert.rejects(evaluateCleanup(unverified), /Cannot verify the new Nightly downloads/);
    assert.deepEqual(unverified.deleted, [], 'Missing or replaced public uploads must prevent every deletion.');
  }
}

function verifyRollingPublicationWiring() {
  const ordered = ['Verify candidate identity, exact file list, and every checksum',
    'Refuse changed main or an out-of-order publication', stagingStep, publishStep, cleanupStep,
    'Point only the rolling Nightly tag to the approved commit'];
  for (let index = 1; index < ordered.length; index++) {
    assert(workflow.indexOf(`      - name: ${ordered[index - 1]}\n`) <
      workflow.indexOf(`      - name: ${ordered[index]}\n`), `${ordered[index]} must follow ${ordered[index - 1]}`);
  }
  const publication = step(publishStep);
  assert.match(publication, /^        id: release$/m);
  assert.match(publication, /^          overwrite_files: true$/m);
  assert.match(publication, /^          tag_name: nightly$/m);
  const files = literal(publication, '          files: |\n', 12).split('\n');
  assert.deepEqual(files, ['nightly-assets/*.zip', 'nightly-assets/build_info.txt', 'nightly-assets/candidate.json',
    'nightly-assets/SHA256SUMS', 'nightly-assets/update.properties']);
  assert.match(step(cleanupStep), /RELEASE_ID: \$\{\{ steps\.release\.outputs\.id \}\}/);
  assert.match(step(cleanupStep), /UPLOADED_ASSETS: \$\{\{ steps\.release\.outputs\.assets \}\}/);
}

function verifyImmutableRadioSystemCatalogWiring() {
  const catalog = job('catalog');
  assert.match(catalog, /if: \$\{\{ inputs\.mode == 'prepare' \}\}/);
  assert.equal((workflow.match(/python tools\/build\/fetch_dsdplus_networks\.py/g) || []).length, 1,
    'Preparation must fetch the public list once for every tested and packaged platform.');
  assert.match(job('ci'), /needs: \[validate, catalog\]/);
  assert.match(job('ci'), /radio_system_catalog_artifact: radio-system-catalog-\$\{\{ github\.run_id \}\}/);
  assert.match(step('Save the exact radio-system catalog for testing and packaging'),
    /name: radio-system-catalog-\$\{\{ github\.run_id \}\}/);
  assert.match(step('Download the tested radio-system catalog'),
    /name: radio-system-catalog-\$\{\{ github\.run_id \}\}/);
  assert.match(step('Build and verify all six packages'),
    /-PradioSystemCatalogFile="\$\{\{ runner\.temp \}\}\/radio-system-catalog\/dsdplus\.networks"/);
  const approvedCatalog = step('Download the approved radio-system catalog without fetching again');
  assert.match(approvedCatalog, /artifact-ids: \$\{\{ steps\.candidate\.outputs\.catalog-artifact-id \}\}/);
  assert.match(approvedCatalog, /run-id: \$\{\{ inputs\.candidate_run_id \}\}/);
  assert.match(approvedCatalog, /path: radio-system-catalog/);
  assert.match(approvedCatalog, /digest-mismatch: error/);
  assert.match(step('Verify candidate identity, exact file list, and every checksum'),
    /CANDIDATE_SOURCE_REF: \$\{\{ steps\.candidate\.outputs\.source-ref \}\}/);
  assert.match(job('package'), /runs-on: macos-15/,
    'Packaging must build the pinned macOS ARM native library on its native toolchain.');
  assert.doesNotMatch(job('publish'), /fetch_dsdplus_networks|prepareRadioSystemCatalog|runtimeZipAll/,
    'Publication must promote the tested bytes without fetching or rebuilding a catalog.');
  const reusable = fs.readFileSync(path.join(path.dirname(process.argv[2] || '.github/workflows/nightly.yml'),
    'gradle.yml'), 'utf8');
  assert.match(reusable, /name: \$\{\{ inputs\.radio_system_catalog_artifact \}\}/);
  assert.match(reusable, /-PradioSystemCatalogFile="\$RADIO_SYSTEM_CATALOG_FILE"/);
  assert.match(reusable, /src\/test\/resources\/radio-systems\/dsdplus-fixture\.networks/,
    'Ordinary CI must use the committed synthetic fixture instead of depending on the public wiki.');
}

function verifyParallelPreparationWiring() {
  const reusable = fs.readFileSync(path.join(path.dirname(process.argv[2] || '.github/workflows/nightly.yml'),
    'gradle.yml'), 'utf8').replace(/\r\n/g, '\n');
  const application = job('check', reusable);
  const groups = [...application.matchAll(/\{os: ([\w-]+), test_group: ([\w-]+)\}/g)]
    .map(([, os, group]) => `${os}:${group}`).sort();
  assert.deepEqual(groups, ['ubuntu-latest:core', 'ubuntu-latest:migrations', 'ubuntu-latest:activity',
    'ubuntu-latest:web', 'ubuntu-latest:http', 'windows-latest:platform-filesystem'].sort(),
  'Run the complete Java partition once and retain Windows filesystem regressions.');
  assert.match(application, /fail-fast: false/);
  assert.match(application, /-PtestGroup=\$\{\{ matrix\.test_group \}\}/);
  assert.match(application, /main-ci-gradle-failure-\$\{\{ matrix\.os \}\}-\$\{\{ matrix\.test_group \}\}/,
    'Application report names must remain unique when one operating system has two jobs.');
  const visual = job('visual', reusable);
  const shards = visual.match(/shard: \[([^\]]+)\]/);
  assert(shards, 'Browser shards must be explicit.');
  assert.deepEqual(shards[1].split(',').map(value => Number(value.trim())), [1, 2, 3, 4],
    'Every browser shard must run exactly once.');
  assert.match(visual, /runs-on: macos-15/);
  assert.match(visual, /fail-fast: false/);
  assert.match(visual, /npm run test:web-visual -- --workers=2 --shard=\$\{\{ matrix\.shard \}\}\/4/);
  assert.doesNotMatch(visual, /--grep|--test-ignore|--update-snapshots|continue-on-error/,
    'Sharding must retain the complete browser suite and fail on errors.');
  assert.match(visual, /name: web-design-system-visual-differences-\$\{\{ matrix\.shard \}\}/);
  assert.match(visual, /name: Save browser timings and results\n        if: always\(\)/);
  const gate = job('gate', reusable);
  assert.match(gate, /needs:\n      - check\n      - visual\n      - contracts/);
  assert.match(gate, /CHECK_RESULT: \$\{\{ needs\.check\.result \}\}/);
  assert.match(gate, /VISUAL_RESULT: \$\{\{ needs\.visual\.result \}\}/);
  assert.match(gate, /CONTRACT_RESULT: \$\{\{ needs\.contracts\.result \}\}/);
  assert.match(gate, /test "\$CHECK_RESULT" = "success" && test "\$VISUAL_RESULT" = "success" && test "\$CONTRACT_RESULT" = "success"/,
    'The Main CI gate must require every application group, browser shard and direct contract job.');
  const contracts = job('contracts', reusable);
  assert.match(contracts, /runs-on: ubuntu-latest/);
  assert.match(contracts, /uses: actions\/setup-java@v5[\s\S]*java-version: '25'/,
    'Packaged USB probe contracts compile Java 25 source and require its JDK.');
  assert.match(contracts, /run: node src\/test\/js\/stats-web\/run-contracts\.js/,
    'Pure JavaScript contracts run directly, without a Java subprocess wrapper.');
  assert.match(contracts, /run: node src\/test\/js\/release\/nightly-workflow\.test\.js/);
  assert.match(contracts, /python -m unittest discover -s tools\/release/);
  assert.match(contracts, /python -m unittest discover -s tools\/build/);
  assert.match(job('package'), /needs: \[validate, catalog\]/,
    'Packaging can overlap checks only after the same catalog has been prepared.');
  assert.match(job('package'), /ref: \$\{\{ github\.sha \}\}/);
  assert.match(job('smoke'), /needs: package/);
  assert.match(job('ready'), /needs: \[validate, ci, package, smoke\]/,
    'Overlapping work must never allow an untested candidate to become ready.');
}

function verifyPreparedCandidateRoundTrip() {
  const root = path.join(temporary, 'package-job');
  const image = path.join(root, 'build', 'image');
  const catalog = path.join(root, 'radio-system-catalog');
  fs.mkdirSync(image, { recursive: true });
  fs.mkdirSync(catalog, { recursive: true });
  fs.writeFileSync(path.join(catalog, 'dsdplus.networks'), catalogBytes);
  for (const platform of platforms) fs.writeFileSync(path.join(image, `vce-${platform}-100.zip`), platform);
  const source = literal(step('Record the exact candidate and file checksums'), '        run: |\n', 10);
  //Use the selected Python even on Macs whose system python3 predates hashlib.file_digest.
  const script = source.replace("python3 - <<'PY'", '"$PYTHON_BINARY" - <<\'PY\'');
  const result = spawnSync(bash, ['-e', '-c', script], { cwd: root, encoding: 'utf8', timeout: 10_000,
    env: { ...process.env, PYTHON_BINARY: python, RUNNER_TEMP: root, UPDATE_BUILD: '100',
      GITHUB_SHA: sha, GITHUB_REF: 'refs/heads/main' } });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
  const lines = fs.readFileSync(path.join(image, 'SHA256SUMS'), 'utf8').trim().split(/\r?\n/);
  assert.equal(lines.length, platforms.length + 3);
  for (const line of lines) {
    const [digest, name] = line.split('  ');
    assert.equal(digest, createHash('sha256').update(fs.readFileSync(path.join(image, name))).digest('hex'));
  }
  // Feed the actual producer output through the actual publication validator. A separate
  // hand-written metadata fixture cannot detect a producer/consumer contract mismatch.
  fs.cpSync(image, candidate, { recursive: true });
  fs.copyFileSync(path.join(catalog, 'dsdplus.networks'), catalogFile);
  const validation = verified();
  assert.equal(validation.status, 0, validation.stderr);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(candidate, 'candidate.json'), 'utf8')), metadata);
}

async function main() {
  verifyPreparedCandidateRoundTrip();
  const expectedOutputs = { 'artifact-id': 501, 'catalog-artifact-id': 502, 'source-ref': 'refs/heads/main' };
  assert.deepEqual(await evaluate(fixture()), expectedOutputs);
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
  for (const expired of [false, true]) {
    const state = fixture();
    if (expired) state.artifacts[1].expired = true;
    else state.artifacts.pop();
    await assert.rejects(evaluate(state), /catalog expired or is unavailable/);
  }
  const newer = fixture(); newer.newer = [{ id: 101 }];
  newer.newerArtifacts = [{ name: 'nightly-candidate-101' }];
  await assert.rejects(evaluate(newer), /newer successful preparation/);
  newer.newerArtifacts = [];
  assert.deepEqual(await evaluate(newer), expectedOutputs, 'A newer publication run is not a new preparation.');

  resetCandidate();
  let result = verified();
  assert.equal(result.status, 0, result.stderr);
  for (const tamper of [
    () => fs.appendFileSync(path.join(candidate, 'vce-win64-100.zip'), 'changed'),
    () => fs.rmSync(path.join(candidate, 'vce-linux-arm64-100.zip')),
    () => fs.writeFileSync(path.join(candidate, 'unexpected.txt'), 'unexpected'),
    () => fs.appendFileSync(catalogFile, 'changed'),
    () => fs.rmSync(catalogFile),
    () => fs.appendFileSync(path.join(candidate, 'SHA256SUMS'), fs.readFileSync(path.join(candidate, 'SHA256SUMS'), 'utf8').split('\n')[0] + '\n'),
    () => fs.writeFileSync(path.join(candidate, 'SHA256SUMS'), fs.readFileSync(path.join(candidate, 'SHA256SUMS'), 'utf8').split('\n').slice(1).join('\n')),
    () => fs.appendFileSync(path.join(candidate, 'SHA256SUMS'), `${'0'.repeat(64)}  ../outside\n`)
  ]) {
    resetCandidate(); tamper(); result = verified();
    assert.notEqual(result.status, 0, 'Changed, missing, extra, duplicated, incomplete or unsafe files must fail.');
  }
  for (const [field, value] of [['source_commit', 'b'.repeat(40)], ['run_id', '99'],
    ['source_ref', 'refs/heads/other'], ['purpose', 'publish'], ['channel', 'alpha'], ['runtime', '17'], ['format', 2],
    ['radio_system_catalog_sha256', '0'.repeat(64)], ['radio_system_catalog_sha256', 'malformed'],
    ['radio_system_catalog_sha256', undefined], ['unexpected_field', 'unexpected']]) {
    resetCandidate();
    fs.writeFileSync(path.join(candidate, 'candidate.json'), JSON.stringify({ ...metadata, [field]: value }));
    rewriteChecksums();
    result = verified();
    assert.notEqual(result.status, 0, `A matching checksum must not admit the wrong ${field}.`);
  }
  verifyStagedDownloads();
  await verifyGuardedCleanup();
  verifyRollingPublicationWiring();
  verifyImmutableRadioSystemCatalogWiring();
  verifyParallelPreparationWiring();
  console.log('Nightly authorization, candidate verification, stable download staging and guarded rolling asset cleanup checks passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; })
  .finally(() => fs.rmSync(temporary, { recursive: true, force: true }));
