'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');

(async () => {
  const modulePath = path.resolve(process.argv[2] ||
    path.join(__dirname, '../../../../stats-web/assets/features/web-diagnostic-log.js'));
  const { decodeWebDiagnosticLog, webDiagnosticLogState, requestWebDiagnosticLog } =
    await import(pathToFileURL(modulePath).href);
  const off = { enabled: false, active: false, file_name: 'sdrtrunk_web.log', retention_days: 7,
    maximum_bytes: 104857600, queue_depth: 0, dropped_records: 0, written_records: 0,
    last_error: null, can_download: false };
  assert.deepEqual(decodeWebDiagnosticLog(off), off);
  assert.deepEqual(webDiagnosticLogState(off), { label: 'Off', tone: 'neutral' });
  assert.equal(webDiagnosticLogState({ ...off, enabled: true }).label, 'Starting');
  assert.deepEqual(webDiagnosticLogState({ ...off, enabled: true, active: true }),
    { label: 'Active', tone: 'success' });
  assert.deepEqual(webDiagnosticLogState({ ...off, enabled: true, last_error: 'Write failed' }),
    { label: 'Unable to write', tone: 'danger' });
  assert.equal(webDiagnosticLogState(null).label, 'Status unavailable');
  for (const invalid of [null, { ...off, enabled: 'true' }, { ...off, active: null },
    { ...off, can_download: 1 }, { ...off, dropped_records: -1 }, { ...off, queue_depth: NaN },
    { ...off, written_records: 1.5 }, { ...off, last_error: {} }]) {
    assert.throws(() => decodeWebDiagnosticLog(invalid), /Reload status/);
  }
  const calls = [];
  const controller = new AbortController();
  const requestJson = async (url, options) => { calls.push({ url, options }); return { data: off }; };
  assert.deepEqual(await requestWebDiagnosticLog(requestJson, { signal: controller.signal }), off);
  assert.equal(calls.at(-1).url, '/api/v1/admin/web-diagnostic-log');
  assert.equal(calls.at(-1).options.method, 'GET');
  assert.equal(calls.at(-1).options.body, undefined);
  assert.strictEqual(calls.at(-1).options.signal, controller.signal);
  await requestWebDiagnosticLog(requestJson, { enabled: true, signal: controller.signal });
  assert.equal(calls.at(-1).options.method, 'PUT');
  assert.deepEqual(calls.at(-1).options.body, { enabled: true });
  assert.equal(calls.at(-1).options.page, false);
  const beforeInvalid = calls.length;
  await assert.rejects(requestWebDiagnosticLog(requestJson, { enabled: 'true' }), /Choose On or Off/);
  assert.equal(calls.length, beforeInvalid, 'Invalid local values must not write to the receiver.');
  const denied = Object.assign(new Error('Access unavailable'), { status: 403 });
  await assert.rejects(requestWebDiagnosticLog(async () => { throw denied; }, { enabled: true }),
    (error) => error === denied, 'Save failures retain their access status for the form workflow.');
  await assert.rejects(requestWebDiagnosticLog(async () => ({ data: { ...off, active: 'yes' } })), /Reload status/);
  await workflowContract(modulePath, off);
  console.log('Web diagnostic log status, saved preference payload and failure contracts passed.');
})().catch((error) => { console.error(error); process.exitCode = 1; });

async function workflowContract(modulePath, off) {
  const { createFormWorkflow } = await import(pathToFileURL(path.join(path.dirname(modulePath),
    '../core/form-workflows.js')).href);
  class Element extends EventTarget {
    constructor(tag, classes = '', text = '') {
      super(); this.tagName = tag.toUpperCase(); this.children = []; this.textContent = text;
      this.disabled = false; this.hidden = false; this.attributes = {};
      const tokens = new Set(classes.split(/\s+/));
      this.classList = { add: (...values) => values.forEach((value) => tokens.add(value)),
        remove: (...values) => values.forEach((value) => tokens.delete(value)), contains: (value) => tokens.has(value) };
    }
    append(...values) { values.forEach((value) => { value.parentNode = this; this.children.push(value); }); }
    replaceChildren(...values) { this.children = []; this.append(...values); }
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; }
    querySelectorAll(selector) {
      const matches = (value) => selector.startsWith('.') ? value.classList.contains(selector.slice(1)) :
        value.tagName.toLowerCase() === selector;
      return this.children.flatMap((child) => [...(matches(child) ? [child] : []), ...child.querySelectorAll(selector)]);
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    get elements() { return [...this.querySelectorAll('input'), ...this.querySelectorAll('button')]; }
    reportValidity() { return true; }
  }
  const node = (tag, classes = '', text = '') => new Element(tag, classes, text);
  const timers = new Map(); let timerId = 0; const closed = []; const requests = []; let viewerDeps;
  let reply = () => ({ data: off });
  const doc = new EventTarget(); doc.hidden = false;
  const context = { AbortController, document: doc, createFormWorkflow,
    window: { setTimeout: (callback, delay) => { timers.set(++timerId, { callback, delay }); return timerId; },
      clearTimeout: (id) => timers.delete(id) },
    createApplicationLogWorkspace: (deps) => { viewerDeps = deps; return { element: node('div'), close: () => closed.push(true) }; } };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(modulePath, 'utf8').replace(/^import[^\n]+\n/gm, '').replace(/^export /gm, ''), context);
  const controller = new AbortController();
  const deps = { node, signal: controller.signal,
    section: (_title, body, actions) => { const panel = node('section'); panel.append(body, actions); return panel; },
    sectionActionHost: (actions) => actions,
    uiToggleField: () => { const field = node('div'); const input = node('input'); input.checked = false; field.append(input); return field; },
    setUiToggle: (input, checked) => { input.checked = checked; },
    uiStatus: (text) => node('span', '', text),
    uiActionButton: (label, _icon, callback) => { const button = node('button', '', label); button.addEventListener('click', callback); return button; },
    requestJson: async (url, options) => { requests.push({ url, options }); return reply(url, options); } };
  const workspace = context.createWebDiagnosticLogWorkspace(deps);
  const flush = () => new Promise((resolve) => setImmediate(resolve));
  await flush();
  const form = workspace.element.querySelector('form'); const input = form.querySelector('input');
  const save = form.querySelector('button'); const feedback = form.querySelector('.admin-form-message');
  assert.equal(save.disabled, true);
  assert.equal(viewerDeps.logPath, '/api/v1/admin/web-diagnostic-log/entries');
  assert.equal(viewerDeps.logName, 'Web server log');
  assert.equal(viewerDeps.downloadPrefix, 'web-server-log');
  assert.equal(timers.size, 1);
  input.checked = true; form.dispatchEvent(new Event('change'));
  assert.equal(save.disabled, false);
  reply = (_url, options) => {
    if (options.method === 'PUT') throw Object.assign(new Error('No disk space'), { status: 503 });
    return { data: off };
  };
  form.dispatchEvent(new Event('submit', { cancelable: true }));
  await flush();
  assert.equal(input.checked, true, 'A failed save preserves the operator’s selected draft.');
  assert.equal(save.disabled, false, 'A failed save remains retryable.');
  assert.match(feedback.textContent, /Your choice is kept/);
  const timer = [...timers.values()][0]; timer.callback();
  await flush();
  assert.equal(input.checked, true, 'Background status updates preserve an unsaved draft.');
  let release;
  reply = (_url, options) => options.method === 'PUT' ? new Promise((resolve) => { release = resolve; }) : { data: off };
  form.dispatchEvent(new Event('submit', { cancelable: true }));
  await flush();
  assert.equal(input.disabled, true);
  const writes = requests.filter(({ options }) => options.method === 'PUT').length;
  form.dispatchEvent(new Event('submit', { cancelable: true }));
  await flush();
  assert.equal(requests.filter(({ options }) => options.method === 'PUT').length, writes,
    'A second submit cannot create a duplicate write while saving.');
  release({ data: { ...off, enabled: true, active: true } });
  await flush();
  assert.equal(input.checked, true); assert.equal(input.disabled, false); assert.equal(save.disabled, true);
  reply = () => ({ data: { ...off, enabled: true, active: true, can_download: true } });
  [...timers.values()][0].callback();
  await flush();
  const download = workspace.element.querySelectorAll('button').find((button) => button.textContent === 'Download saved logs');
  input.checked = false; form.dispatchEvent(new Event('change'));
  let finishDownloadCheck;
  reply = () => new Promise((resolve) => { finishDownloadCheck = resolve; });
  download.dispatchEvent(new Event('click'));
  await flush();
  assert.equal(save.disabled, true, 'Download preflight serializes against a concurrent setting save.');
  assert.equal(input.disabled, true);
  const beforeDownloadWrite = requests.filter(({ options }) => options.method === 'PUT').length;
  form.dispatchEvent(new Event('submit', { cancelable: true }));
  await flush();
  assert.equal(requests.filter(({ options }) => options.method === 'PUT').length, beforeDownloadWrite);
  finishDownloadCheck({ data: { ...off, enabled: true, active: true, can_download: false } });
  await flush();
  assert.equal(input.checked, false, 'Archive status preflight also preserves an unsaved setting draft.');
  assert.equal(save.disabled, false);
  assert.equal(download.disabled, true);
  assert.match(feedback.textContent, /No saved web server logs/);
  let statusSignal;
  reply = (_url, options) => { statusSignal = options.signal; return new Promise(() => {}); };
  [...timers.values()][0].callback();
  await flush();
  controller.abort();
  assert.equal(statusSignal.aborted, true, 'Closing the page aborts an in-flight status request.');
  assert.equal(timers.size, 0, 'Closing the page releases scheduled polling.');
  assert.deepEqual(closed, [true], 'Closing the page also closes the reused log viewer.');
}
