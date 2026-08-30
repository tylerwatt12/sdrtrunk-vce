'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const applicationPath = process.argv[2];
assert.ok(applicationPath, 'The app.js path is required.');
const application = fs.readFileSync(applicationPath, 'utf8');

function functionSource(signature) {
  const start = application.indexOf(signature);
  if (start < 0) throw new Error(`Missing ${signature}`);
  const openingBrace = application.indexOf('{', start + signature.length);
  let depth = 0;
  for (let index = openingBrace; index < application.length; index += 1) {
    if (application[index] === '{') depth += 1;
    else if (application[index] === '}' && --depth === 0) return application.slice(start, index + 1);
  }
  throw new Error(`Unterminated ${signature}`);
}

const context = { RECEIVER_HEALTH_RESOLVED_PAGE_SIZE: 5 };
vm.createContext(context);
vm.runInContext(`
  ${functionSource('function receiverHealthSortedResolvedIncidents(incidents, sort)')}
  ${functionSource('function receiverHealthResolvedPage(incidents, sort, requestedPage)')}
  globalThis.resolvedPage = receiverHealthResolvedPage;
`, context);

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

function incident(id, resolvedAt, title = `Alert ${id}`) {
  return {
    occurrence_id: id,
    title,
    code: `code-${id}`,
    scope: `scope-${id}`,
    resolved_at_ms: resolvedAt
  };
}

const incidents = Array.from({ length: 12 }, (_, index) => incident(index + 1, index + 1));
const first = plain(context.resolvedPage(incidents, 'recent', 0));
const second = plain(context.resolvedPage(incidents, 'recent', 1));
const third = plain(context.resolvedPage(incidents, 'recent', 2));

assert.deepEqual(first.rows.map((row) => row.occurrence_id), [12, 11, 10, 9, 8]);
assert.deepEqual(second.rows.map((row) => row.occurrence_id), [7, 6, 5, 4, 3]);
assert.deepEqual(third.rows.map((row) => row.occurrence_id), [2, 1]);
assert.deepEqual({ page: first.page, page_count: first.page_count, total_count: first.total_count,
  offset: first.offset, limit: first.limit, has_more: first.has_more },
{ page: 0, page_count: 3, total_count: 12, offset: 0, limit: 5, has_more: true });
assert.equal(third.has_more, false);

assert.equal(context.resolvedPage(incidents, 'recent', 99).page, 2,
  'A page above the available range must clamp to the final page.');
assert.equal(context.resolvedPage(incidents, 'recent', -4).page, 0,
  'A negative page must clamp to the first page.');
assert.equal(context.resolvedPage(incidents, 'recent', Number.NaN).page, 0,
  'A non-number page must clamp to the first page.');

const sixRows = plain(context.resolvedPage(incidents.slice(0, 6), 'recent', 2));
assert.equal(sixRows.page, 1, 'Shrinking to six rows must clamp page three to page two.');
assert.deepEqual(sixRows.rows.map((row) => row.occurrence_id), [1]);
const fiveRows = plain(context.resolvedPage(incidents.slice(0, 5), 'recent', 1));
assert.equal(fiveRows.page, 0, 'Shrinking to five rows must clamp to the only page.');
assert.equal(fiveRows.rows.length, 5);

const byType = [
  incident(1, 1, 'Zulu'), incident(2, 2, 'Echo'), incident(3, 3, 'Alpha'),
  incident(4, 4, 'Hotel'), incident(5, 5, 'Charlie'), incident(6, 6, 'Foxtrot'),
  incident(7, 7, 'Bravo'), incident(8, 8, 'Golf'), incident(9, 9, 'Delta')
];
assert.deepEqual(plain(context.resolvedPage(byType, 'type', 0)).rows.map((row) => row.title),
  ['Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo'],
  'The complete resolved list must be sorted before the first five rows are selected.');

assert.deepEqual(plain(context.resolvedPage([], 'recent', 4)), {
  rows: [],
  page: 0,
  page_count: 0,
  total_count: 0,
  offset: 0,
  limit: 5,
  has_more: false
});
