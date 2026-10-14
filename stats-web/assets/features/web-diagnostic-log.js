import { createApplicationLogWorkspace } from '../core/application-log.js?v=5';
import { createFormWorkflow } from '../core/form-workflows.js?v=2';

const ROOT = '/api/v1/admin/web-diagnostic-log';
const STATUS_INTERVAL = 5_000;

export function decodeWebDiagnosticLog(value) {
  if (!value || typeof value.enabled !== 'boolean' || typeof value.active !== 'boolean' ||
      typeof value.can_download !== 'boolean' || typeof value.file_name !== 'string' ||
      !['retention_days', 'maximum_bytes', 'queue_depth', 'dropped_records', 'written_records']
        .every((key) => Number.isSafeInteger(value[key]) && value[key] >= 0) ||
      (value.last_error !== null && typeof value.last_error !== 'string')) {
    throw new Error('Web server log status could not be read. Use Reload status to try again.');
  }
  return { ...value };
}

export function webDiagnosticLogState(snapshot) {
  if (!snapshot) return { label: 'Status unavailable', tone: 'warning' };
  if (!snapshot.enabled) return { label: 'Off', tone: 'neutral' };
  if (snapshot.last_error) return { label: 'Unable to write', tone: 'danger' };
  return snapshot.active ? { label: 'Active', tone: 'success' } : { label: 'Starting', tone: 'neutral' };
}

export async function requestWebDiagnosticLog(requestJson, { enabled, signal } = {}) {
  if (enabled !== undefined && typeof enabled !== 'boolean') {
    throw new Error('Choose On or Off for web server diagnostic logging.');
  }
  const result = await requestJson(ROOT, { method: enabled === undefined ? 'GET' : 'PUT',
    ...(enabled === undefined ? {} : { body: { enabled } }), signal, page: false });
  return decodeWebDiagnosticLog(result?.data ?? result);
}

// Reuse map: Administration settings, the shared toggle/form save workflow,
// status/notice controls and Application log viewer cover both themes and sizes.
export function createWebDiagnosticLogWorkspace(deps) {
  const { node, section, sectionActionHost, uiToggleField, setUiToggle, uiStatus,
    uiActionButton, requestJson, signal } = deps;
  const element = node('div', 'application-log-workspace web-diagnostic-log-workspace');
  const body = node('div', 'admin-section-body');
  const form = node('form', 'admin-form ui-settings-form');
  const toggle = uiToggleField('Web server diagnostic logging', false);
  const enabled = toggle.querySelector('input');
  enabled.disabled = true;
  const facts = node('dl', 'ui-fact-list ui-fact-list-grid');
  const state = node('dd');
  state.setAttribute('role', 'status');
  const skipped = node('dd', '', '—');
  for (const [label, value] of [['Logging', state], ['Skipped diagnostic entries', skipped]]) {
    const row = node('div', 'ui-fact-row');
    row.append(node('dt', '', label), value);
    facts.append(row);
  }
  const writeFailure = node('div', 'ui-notice ui-notice-warning');
  writeFailure.hidden = true;
  writeFailure.setAttribute('role', 'status');
  const retention = node('p', 'ui-field-detail', 'Saved alongside the application log as sdrtrunk_web.log. ' +
    'Logs are kept for up to 7 days, within a 100 MB limit.');
  const feedback = node('div', 'admin-form-message');
  const save = node('button', 'ui-button ui-button-primary', 'Save');
  save.type = 'submit';
  const actions = node('div', 'admin-form-actions');
  actions.append(save);
  const footer = node('div', 'settings-form-footer');
  footer.append(feedback, actions);
  form.append(toggle, facts, writeFailure, retention, footer);
  body.append(form);
  const reload = uiActionButton('Reload status', 'icon-refresh', () => void loadStatus());
  const download = uiActionButton('Download saved logs', 'icon-download', () => void downloadSaved());
  const statusActions = node('div', 'ui-action-row');
  statusActions.append(download, reload);
  const panel = section('Diagnostic logging', body, sectionActionHost(statusActions));
  element.append(panel);
  const viewer = createApplicationLogWorkspace({ ...deps, logPath: `${ROOT}/entries`,
    logName: 'Web server log', downloadPrefix: 'web-server-log' });
  element.append(viewer.element);

  let confirmed = null;
  let disposed = false;
  let denied = false;
  let downloading = false;
  let timer = null;
  let loading = null;
  let loadSequence = 0;
  const workflow = createFormWorkflow({ form, submit: save, feedback, ready: false,
    changed: () => Boolean(confirmed) && enabled.checked !== confirmed.enabled });
  const current = () => !disposed && !signal?.aborted;
  const stopTimer = () => { if (timer !== null) window.clearTimeout(timer); timer = null; };
  const cancelLoad = () => { loadSequence += 1; loading?.abort(); loading = null; };
  const sync = () => {
    const busy = workflow.isBusy();
    workflow.setReady(Boolean(confirmed) && !denied && !downloading);
    enabled.disabled = !confirmed || denied || busy || downloading;
    reload.disabled = denied || busy || downloading || Boolean(loading);
    download.disabled = denied || busy || downloading || !confirmed?.can_download;
    const display = webDiagnosticLogState(confirmed);
    state.replaceChildren(uiStatus(display.label, display.tone));
    skipped.textContent = confirmed ? Number(confirmed.dropped_records).toLocaleString() : '—';
    writeFailure.textContent = confirmed?.last_error ?
      'The log could not be written. Check available disk space and folder access, then turn logging off and on.' :
      confirmed?.dropped_records ? 'Skipped entries mark gaps when diagnostic logging could not keep up.' : '';
    writeFailure.hidden = !writeFailure.textContent;
  };
  const apply = (snapshot, preserveDraft = true) => {
    const dirty = confirmed && enabled.checked !== confirmed.enabled;
    confirmed = snapshot;
    if (!preserveDraft || !dirty) setUiToggle(enabled, snapshot.enabled);
    sync();
  };
  const schedule = () => {
    stopTimer();
    if (current() && !denied && !document.hidden && !workflow.isBusy()) {
      timer = window.setTimeout(() => void loadStatus(), STATUS_INTERVAL);
    }
  };

  async function loadStatus() {
    if (!current() || denied || loading || workflow.isBusy() || document.hidden) return;
    stopTimer();
    const controller = new AbortController();
    loading = controller;
    const sequence = ++loadSequence;
    sync();
    try {
      const snapshot = await requestWebDiagnosticLog(requestJson, { signal: controller.signal });
      if (!current() || sequence !== loadSequence) return;
      apply(snapshot);
      if (!confirmed || enabled.checked === confirmed.enabled) workflow.showFeedback('status', '');
    } catch (error) {
      if (!current() || sequence !== loadSequence || error?.name === 'AbortError') return;
      denied = error?.status === 401 || error?.status === 403;
      if (denied) confirmed = null;
      workflow.showFeedback('error', denied ?
        'Web server log access is no longer available. Sign in with an administrator account.' :
        'Web server log status could not be loaded. Use Reload status to try again.');
    } finally {
      if (current() && sequence === loadSequence) { loading = null; sync(); schedule(); }
    }
  }

  async function downloadSaved() {
    if (!current() || denied || downloading || workflow.isBusy() || !confirmed?.can_download) return;
    downloading = true;
    sync();
    try {
      const snapshot = await requestWebDiagnosticLog(requestJson, { signal });
      if (!current()) return;
      apply(snapshot);
      if (!snapshot.can_download) {
        workflow.showFeedback('warning', 'No saved web server logs are available yet.');
        return;
      }
      const link = node('a');
      link.href = `${ROOT}/download`;
      link.download = 'web-server-logs.zip';
      link.hidden = true;
      element.append(link);
      link.click();
      link.remove();
    } catch (error) {
      if (!current()) return;
      if (error?.status === 401 || error?.status === 403) { denied = true; confirmed = null; }
      workflow.showFeedback('error', denied ?
        'Web server log access is no longer available. Sign in with an administrator account.' :
        'Saved log download could not be started. Use Reload status and try again.');
    } finally { if (current()) { downloading = false; sync(); } }
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!current() || denied || downloading || workflow.isBusy()) return;
    const value = enabled.checked;
    stopTimer();
    cancelLoad();
    await workflow.save(async () => {
      sync();
      const snapshot = await requestWebDiagnosticLog(requestJson, { enabled: value, signal });
      if (current()) apply(snapshot, false);
    }, { saving: 'Saving diagnostic logging…', success: 'Diagnostic logging saved.',
      onError: (error) => {
        if (error?.status === 401 || error?.status === 403) { denied = true; confirmed = null; }
        return { state: 'error', message: denied ?
          'Web server log access is no longer available. Sign in with an administrator account.' :
          'Diagnostic logging could not be saved. Your choice is kept; try saving again.' };
      } });
    if (!current()) return;
    sync();
    schedule();
  });
  const visibilityChanged = () => {
    stopTimer();
    if (document.hidden) cancelLoad();
    else void loadStatus();
  };
  document.addEventListener('visibilitychange', visibilityChanged);
  function close() {
    if (disposed) return;
    disposed = true;
    stopTimer();
    cancelLoad();
    viewer.close();
    document.removeEventListener('visibilitychange', visibilityChanged);
    signal?.removeEventListener('abort', close);
  }
  signal?.addEventListener('abort', close, { once: true });
  sync();
  if (signal?.aborted) close();
  else void loadStatus();
  return Object.freeze({ element, close });
}
