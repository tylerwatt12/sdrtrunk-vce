/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '../..');
const TEST_ROOT = path.join(ROOT, 'src/test/js/stats-web/visual');
const CONFIG = path.join(ROOT, 'playwright.config.cjs');
const WEIGHTS = path.join(__dirname, 'web-visual-durations.json');
const SHARDS = 4;
const WORKERS = 2;

function hash(identities) {
  return crypto.createHash('sha256').update([...identities].sort().join('\n')).digest('hex');
}

function inventory(report, testRoot = TEST_ROOT) {
  if (!Array.isArray(report?.suites)) throw new Error('Playwright discovery must contain suites.');
  const files = new Map();
  const seen = new Set();
  function visit(suite, parents = [], inheritedFile = '') {
    let file = suite.file || inheritedFile;
    if (file) {
      file = path.isAbsolute(file) ? path.relative(testRoot, file) : file;
      file = file.replaceAll('\\', '/');
      if (file.startsWith('../') || file.startsWith('/') || file.includes('/../')) {
        throw new Error(`Test file is outside the visual test directory: ${file}`);
      }
    }
    const titles = [...parents, ...(suite.title ? [suite.title] : [])];
    for (const spec of suite.specs || []) {
      if (!file) throw new Error('A discovered test has no source file.');
      for (const test of spec.tests || []) {
        const identity = JSON.stringify([file, ...titles, spec.title, test.projectName || test.projectId || '']);
        if (seen.has(identity)) throw new Error(`Duplicate discovered test: ${identity}`);
        seen.add(identity);
        if (!files.has(file)) files.set(file, []);
        files.get(file).push(identity);
      }
    }
    for (const child of suite.suites || []) visit(child, titles, file);
  }
  for (const suite of report.suites) visit(suite);
  if (!seen.size) throw new Error('Visual test discovery returned no tests.');
  return files;
}

function validateWeights(files, data) {
  if (data?.version !== 1 || data.shards !== SHARDS || data.workers !== WORKERS ||
      !data.files || typeof data.files !== 'object' || Array.isArray(data.files)) {
    throw new Error('Unsupported visual duration file; refresh it with --refresh.');
  }
  const missing = [...files.keys()].filter(file => !Object.hasOwn(data.files, file));
  const stale = Object.keys(data.files).filter(file => !files.has(file));
  if (missing.length || stale.length) {
    throw new Error(`Visual duration inventory changed. Missing: ${missing.join(', ') || 'none'}; ` +
      `removed: ${stale.join(', ') || 'none'}. Run node tools/test/web-visual-shards.cjs --refresh and commit its duration file.`);
  }
  const caseCount = [...files.values()].reduce((sum, cases) => sum + cases.length, 0);
  if (data.caseCount !== caseCount || data.inventorySha256 !== hash([...files.values()].flat())) {
    throw new Error('Visual semantic test inventory changed. Run node tools/test/web-visual-shards.cjs --refresh and commit its duration file.');
  }
  for (const [file, weight] of Object.entries(data.files)) {
    if (!Number.isFinite(weight.durationMs) || weight.durationMs <= 0 ||
        !Number.isInteger(weight.caseCount) || weight.caseCount <= 0 ||
        !['measured', 'estimated'].includes(weight.source)) {
      throw new Error(`Invalid duration/count/source for ${file}; refresh the duration file.`);
    }
  }
}

// Playwright schedules ordinary files in filename order with two workers. Score that
// queue when assigning each longest file; do not rely on test-count-only balance.
function queueDuration(files, weights) {
  const lanes = Array(WORKERS).fill(0);
  for (const file of [...files].sort()) {
    const lane = lanes.indexOf(Math.min(...lanes));
    lanes[lane] += weights.get(file);
  }
  return Math.max(...lanes);
}

function partition(files, durations) {
  validateWeights(files, durations);
  const weights = new Map([...files].map(([file, cases]) => {
    const measured = durations.files[file];
    return [file, measured.durationMs * cases.length / measured.caseCount];
  }));
  const groups = Array.from({ length: SHARDS }, () => []);
  const ordered = [...files.keys()].sort((a, b) => weights.get(b) - weights.get(a) || a.localeCompare(b, 'en'));
  for (const file of ordered) {
    const ranked = groups.map((group, index) => ({ index,
      duration: queueDuration([...group, file], weights),
      work: group.reduce((sum, item) => sum + weights.get(item), weights.get(file)) }));
    ranked.sort((a, b) => a.duration - b.duration || a.work - b.work || a.index - b.index);
    groups[ranked[0].index].push(file);
  }
  const assigned = groups.flat();
  if (assigned.length !== files.size || new Set(assigned).size !== files.size ||
      [...files.keys()].some(file => !assigned.includes(file))) {
    throw new Error('Visual shard partition must contain every file exactly once.');
  }
  return groups.map((group, index) => {
    const names = group.sort();
    const identities = names.flatMap(file => files.get(file));
    return { shard: index + 1, totalShards: SHARDS, workers: WORKERS, files: names,
      caseCount: identities.length, inventorySha256: hash(identities),
      modeledDurationMs: Math.round(queueDuration(names, weights)) };
  });
}

function sameInventory(actual, expected, description) {
  const all = [...actual.values()].flat();
  const planned = [...expected.values()].flat();
  if (all.length !== planned.length || hash(all) !== hash(planned)) {
    throw new Error(`${description}: selected tests differ from the checked whole-file inventory.`);
  }
}

function literalFileFilter(file, testRoot = TEST_ROOT) {
  const absolute = path.resolve(testRoot, file);
  return '^' + absolute.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$';
}

function playwrightArgs(files = []) {
  return ['test', '--config', CONFIG, '--workers=' + WORKERS, ...files.map(file => literalFileFilter(file))];
}

function discover(files = []) {
  const cli = require.resolve('@playwright/test/cli', { paths: [ROOT] });
  const result = spawnSync(process.execPath, [cli, ...playwrightArgs(files), '--list', '--reporter=json'],
    { cwd: ROOT, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 30_000 });
  if (result.error || result.status !== 0) {
    throw new Error(`Playwright discovery failed: ${result.error?.message || result.stderr || result.status}`);
  }
  let report;
  try { report = JSON.parse(result.stdout); }
  catch { throw new Error('Playwright discovery did not return JSON; no tests were run.'); }
  if ((report.errors || []).length) throw new Error('Playwright discovery reported errors; no tests were run.');
  return inventory(report);
}

function jsonFiles(directory) {
  const files = [];
  for (const item of fs.readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, item.name);
    if (item.isDirectory()) files.push(...jsonFiles(full));
    else if (item.isFile() && item.name === 'results.json') files.push(full);
  }
  return files.sort();
}

function reportDurations(directory) {
  const timings = new Map();
  const reportCases = new Set();
  for (const filename of jsonFiles(directory)) {
    const report = JSON.parse(fs.readFileSync(filename, 'utf8'));
    if (!Array.isArray(report.suites)) continue;
    function visit(suite, inheritedFile = '') {
      const file = (suite.file || inheritedFile).replaceAll('\\', '/');
      for (const spec of suite.specs || []) {
        for (const test of spec.tests || []) {
          const identity = [test.projectName || test.projectId || '', spec.id].join('|');
          if (reportCases.has(identity)) throw new Error('Timing reports contain duplicated cases; use one run/attempt.');
          reportCases.add(identity);
          if (!timings.has(file)) timings.set(file, { caseCount: 0, sumMs: 0, first: Infinity, last: -Infinity });
          const timing = timings.get(file);
          timing.caseCount += 1;
          for (const attempt of test.results || []) {
            const start = Date.parse(attempt.startTime);
            const duration = attempt.duration;
            if (!Number.isFinite(start) || !Number.isFinite(duration) || duration < 0) {
              throw new Error(`Invalid timing report for ${file}`);
            }
            timing.sumMs += duration;
            timing.first = Math.min(timing.first, start);
            timing.last = Math.max(timing.last, start + duration);
          }
        }
      }
      for (const child of suite.suites || []) visit(child, file);
    }
    for (const suite of report.suites) visit(suite);
  }
  if (!timings.size) throw new Error('No Playwright results.json reports found.');
  return Object.fromEntries([...timings].map(([file, timing]) => [file, {
    durationMs: Math.max(1, Math.ceil(Math.max(timing.sumMs, timing.last - timing.first))),
    caseCount: timing.caseCount, source: 'measured',
  }]));
}

function refreshedWeights(files, existing, measured = {}) {
  const known = { ...(existing?.files || {}), ...measured };
  const rates = Object.values(known).filter(weight => weight.durationMs > 0 && weight.caseCount > 0)
    .map(weight => weight.durationMs / weight.caseCount).sort((a, b) => a - b);
  if (!rates.length) throw new Error('Seed durations from CI with --refresh --reports <extracted-report-directory>.');
  // New files get an explicit conservative estimate until their next measured refresh.
  const estimatePerCase = rates[Math.floor((rates.length - 1) * 0.75)];
  const entries = [...files].sort(([a], [b]) => a.localeCompare(b, 'en')).map(([file, cases]) => [file,
    known[file] || { durationMs: Math.ceil(estimatePerCase * cases.length), caseCount: cases.length, source: 'estimated' }]);
  return { version: 1, shards: SHARDS, workers: WORKERS,
    caseCount: [...files.values()].reduce((sum, cases) => sum + cases.length, 0),
    inventorySha256: hash([...files.values()].flat()), files: Object.fromEntries(entries) };
}

function argumentsForCli(args) {
  const options = {};
  for (let index = 0; index < args.length; index++) {
    const argument = args[index];
    if (['--refresh', '--plan', '--verify'].includes(argument)) options[argument.slice(2)] = true;
    else if (argument === '--reports') {
      const value = args[++index];
      if (!value || value.startsWith('--')) throw new Error(`${argument} requires a directory.`);
      options[argument.slice(2)] = value;
    }
    else if (argument === '--shard') options.shard = args[++index];
    else if (argument.startsWith('--shard=')) options.shard = argument.slice('--shard='.length);
    else throw new Error(`Unknown option: ${argument}`);
  }
  if (options.reports && !options.refresh) throw new Error('--reports requires --refresh.');
  if ([options.refresh, options.plan, options.verify, Boolean(options.shard)].filter(Boolean).length !== 1) {
    throw new Error('Use --shard 1/4, --plan, --verify, or --refresh [--reports <directory>].');
  }
  if (options.shard && !/^[1-4]\/4$/.test(options.shard)) throw new Error('Expected --shard 1/4 through 4/4.');
  return options;
}

function verifyGroup(group, files) {
  const expected = new Map(group.files.map(file => [file, files.get(file)]));
  sameInventory(discover(group.files), expected, `Shard ${group.shard}/${SHARDS}`);
}

function main(args) {
  const options = argumentsForCli(args);
  const files = discover();
  const existing = fs.existsSync(WEIGHTS) ? JSON.parse(fs.readFileSync(WEIGHTS, 'utf8')) : null;
  if (options.refresh) {
    const measured = options.reports ? reportDurations(path.resolve(options.reports)) : {};
    const data = refreshedWeights(files, existing, measured);
    validateWeights(files, data);
    fs.writeFileSync(WEIGHTS, JSON.stringify(data, null, 2) + '\n');
    const estimates = Object.entries(data.files).filter(([, weight]) => weight.source === 'estimated').map(([file]) => file);
    console.log(`Refreshed ${files.size} file weights; ${estimates.length} explicit estimates: ${estimates.join(', ') || 'none'}`);
    return 0;
  }
  const groups = partition(files, existing);
  const proof = { fileCount: files.size, caseCount: [...files.values()].reduce((sum, cases) => sum + cases.length, 0),
    inventorySha256: hash([...files.values()].flat()), assignments: groups };
  if (options.plan) { console.log(JSON.stringify(proof, null, 2)); return 0; }
  if (options.verify) {
    for (const group of groups) verifyGroup(group, files);
    console.log(JSON.stringify(proof, null, 2));
    return 0;
  }
  const group = groups[Number(options.shard.split('/')[0]) - 1];
  verifyGroup(group, files);
  const receipt = path.join(ROOT, 'build/playwright-shards', `shard-${group.shard}-inventory.json`);
  fs.mkdirSync(path.dirname(receipt), { recursive: true });
  fs.writeFileSync(receipt, JSON.stringify({ ...proof, selectedShard: group.shard, sourceCommit: process.env.GITHUB_SHA || null,
    runId: process.env.GITHUB_RUN_ID || null, runAttempt: process.env.GITHUB_RUN_ATTEMPT || null }, null, 2) + '\n');
  console.log(`Running checked shard ${group.shard}/${SHARDS}: ${group.files.length} whole files, ${group.caseCount} cases, ${WORKERS} workers.`);
  const cli = require.resolve('@playwright/test/cli', { paths: [ROOT] });
  const result = spawnSync(process.execPath, [cli, ...playwrightArgs(group.files)], { cwd: ROOT, stdio: 'inherit' });
  if (result.error) throw result.error;
  return result.status ?? 1;
}

module.exports = { inventory, validateWeights, queueDuration, partition, sameInventory, literalFileFilter,
  reportDurations, refreshedWeights, argumentsForCli, main };
if (require.main === module) {
  try { process.exitCode = main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
