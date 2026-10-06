'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

(async () => {
  const modulePath = path.resolve(process.argv[2] ||
    path.join(__dirname, '../../../../stats-web/assets/core/application-log.js'));
  const { filterApplicationLogEntries, applicationLogShownText, mergeApplicationLogSnapshot } =
    await import(pathToFileURL(modulePath).href);
  const entries = [
    { id: '1', time: '2026-10-02T18:00:00Z', level: 'INFO', source: 'Receiver',
      message: 'North Ridge channel started', details: '', text: 'INFO North Ridge channel started' },
    { id: '2', time: '2026-10-02T18:00:01Z', level: 'WARN', source: 'TunerManager',
      message: 'Device is busy', details: 'USB transfer unavailable', text: 'WARN Device is busy\nUSB transfer unavailable' },
    { id: '3', time: '2026-10-02T18:00:02Z', level: 'ERROR', source: 'Streaming',
      message: 'Upload failed', details: 'java.io.IOException: temporary failure\n\tat upload(Upload.java:17)',
      text: 'ERROR Upload failed\njava.io.IOException: temporary failure\n\tat upload(Upload.java:17)' },
    { id: '4', time: null, level: 'TEXT', source: '', message: 'Unformatted startup output', details: '',
      text: 'Unformatted startup output' }
  ];
  assert.deepEqual(filterApplicationLogEntries(entries, 'north RIDGE').map(({ id }) => id), ['1']);
  assert.deepEqual(filterApplicationLogEntries(entries, 'tunermanager').map(({ id }) => id), ['2']);
  assert.deepEqual(filterApplicationLogEntries(entries, '  IOException  ').map(({ id }) => id), ['3']);
  assert.deepEqual(filterApplicationLogEntries(entries, '', 'WARN').map(({ id }) => id), ['2', '3']);
  assert.deepEqual(filterApplicationLogEntries(entries, '', 'ERROR').map(({ id }) => id), ['3']);
  assert.deepEqual(filterApplicationLogEntries(entries, 'Device', 'ERROR'), []);
  assert.equal(filterApplicationLogEntries(entries).length, 4);
  assert.equal(applicationLogShownText(filterApplicationLogEntries(entries, '', 'ERROR')), entries[2].text,
    'Shown exports must include the full details and exclude filtered messages.');
  assert.equal(applicationLogShownText([]), '');
  assert.equal(applicationLogShownText([{ time: null, level: 'TEXT', source: '', message: 'plain',
    details: 'continued' }]), 'TEXT\nplain\ncontinued');
  const id = (offset) => `0123456789abcdef:${offset.toString(16)}`;
  const compact = (offset, message, details = '') => ({ id: id(offset), header_prefix: 'original prefix ', message, details });
  const first = mergeApplicationLogSnapshot(null, { log: 'current', incremental: false,
    entries: [compact(1, 'first'), compact(2, 'failure', 'trace')], first_id: id(1) });
  assert.equal(applicationLogShownText(first.entries), 'original prefix first\noriginal prefix failure\ntrace');
  const next = mergeApplicationLogSnapshot(first, { log: 'current', incremental: true,
    entries: [compact(2, 'failure', 'trace\ncontinued'), compact(3, 'next')], first_id: id(2), max_entries: 500 });
  assert.deepEqual(next.entries.map((entry) => entry.id), [id(2), id(3)], 'Evicted entries must leave the client tail.');
  assert.equal(next.entries[0].text, 'original prefix failure\ntrace\ncontinued');
  assert.deepEqual(mergeApplicationLogSnapshot(next, { ...next, entries: [] }).entries, next.entries,
    'An unchanged delta must preserve the displayed tail.');
  assert.equal(mergeApplicationLogSnapshot(next, { log: 'current', gap: true, incremental: false,
    entries: [compact(4, 'rotated')] }).entries.length, 1);
  const blank = mergeApplicationLogSnapshot(null, { log: 'current', entries: [
    { id: 'blank', header_prefix: 'header - ', message: 'Message', details: '', multiline: true },
    { id: 'raw', header_prefix: '', message: 'Unstructured', details: '', multiline: true },
    { id: 'shortened', header_prefix: '', message: 'x'.repeat(100), details: '[message truncated]', multiline: true }
  ] });
  assert.equal(blank.entries[0].text, 'header - Message\n');
  assert.equal(blank.entries[1].text, 'Unstructured\n');
  assert.equal(blank.entries[2].text, 'x'.repeat(100) + '\n[message truncated]');
  assert.equal(applicationLogShownText(blank.entries.slice(0, 2)), 'header - Message\n\nUnstructured\n');
})().catch((error) => { console.error(error); process.exitCode = 1; });
