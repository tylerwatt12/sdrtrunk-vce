'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

(async () => {
  const modulePath = path.resolve(process.argv[2] ||
    path.join(__dirname, '../../../../stats-web/assets/core/application-log.js'));
  const { filterApplicationLogEntries, applicationLogShownText } = await import(pathToFileURL(modulePath).href);
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
})().catch((error) => { console.error(error); process.exitCode = 1; });
