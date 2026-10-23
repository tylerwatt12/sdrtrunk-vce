// Reuse map: Administration sections, shared toggles/page form actions,
// form busy/dirty feedback, and warning notices cover both themes and sizes.
import { createFormWorkflow } from '../core/form-workflows.js?v=6';

const ROOT = '/api/v1/admin/paging-tone-detection';
const REVISION = /^[0-9a-f]{64}$/;

export function decodePagingToneDetection(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      typeof value.revision !== 'string' || !REVISION.test(value.revision) ||
      typeof value.enabled !== 'boolean' || !['ready', 'unavailable'].includes(value.status) ||
      (value.status === 'unavailable' && value.enabled)) {
    throw new Error('Tone detection status could not be read. Reload status to try again.');
  }
  return { revision: value.revision, enabled: value.enabled, status: value.status };
}

export async function requestPagingToneDetection(fetchDocument, { action = 'status', revision = '',
  value = null, signal = null, timeoutMs = 15_000 } = {}) {
  if (!['status', 'enabled'].includes(action)) throw new Error('Unknown paging-tone action.');
  const headers = { Accept: 'application/json' };
  const options = { method: action === 'status' ? 'GET' : 'PUT', headers };
  if (action === 'enabled') {
    if (typeof revision !== 'string' || !REVISION.test(revision)) throw new Error('Reload paging-tone status before saving.');
    if (typeof value !== 'boolean') throw new Error('Choose On or Off for tone detection.');
    headers['If-Match'] = `"${revision}"`; headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify({ value });
  }
  const controller = new AbortController(); let timedOut = false;
  const abort = () => controller.abort();
  if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  options.signal = controller.signal;
  try {
    const response = await fetchDocument(`${ROOT}${action === 'status' ? '' : '/enabled'}`, options);
    let result = null;
    try { result = await response.json(); } catch (_) { }
    if (!response.ok) {
      const error = new Error(result?.error?.message || 'Tone detection could not be saved. Try again.');
      error.status = response.status;
      if (response.status === 409) {
        try { error.current = decodePagingToneDetection(result); } catch (_) { }
      }
      throw error;
    }
    return decodePagingToneDetection(result);
  } catch (error) {
    if (timedOut) {
      const timeout = new Error('The receiver took too long to respond. Reload status to try again.');
      timeout.code = 'request_timeout'; throw timeout;
    }
    throw error;
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
}

export function createPagingToneDetectionSettings({ node, section, sectionActionHost, uiToggleField,
  setUiToggle, fetchDocument, signal = null, isCurrent = () => true }) {
  const body = node('div', 'admin-section-body');
  const form = node('form', 'admin-form settings-page-form ui-settings-form');
  const reload = node('button', 'ui-button ui-button-secondary', 'Reload status'); reload.type = 'button';
  const panel = section('Tone detection', body, sectionActionHost(reload));
  panel.classList.add('ui-settings-panel'); panel.dataset.pagingToneDetection = '';
  const toggle = uiToggleField('Tone detection', true, 'Tone detection',
    'Detects single tones in P25 Phase 1 calls, and single tones and DTMF in AM/NBFM calls. Off skips detection; tones remain audible.');
  const enabled = toggle.querySelector('input');
  const recovery = node('p', 'ui-notice ui-notice-warning',
    'Tone detection setting could not be loaded. Detection is off. Save to choose a setting.');
  recovery.setAttribute('role', 'status'); recovery.hidden = true;
  const save = node('button', 'ui-button ui-button-primary', 'Save'); save.type = 'submit';
  const feedback = node('div', 'admin-form-message'); feedback.setAttribute('role', 'status');
  const footer = node('div', 'settings-form-footer'); const actions = node('div', 'admin-form-actions');
  actions.append(save); footer.append(feedback, actions); form.append(toggle, recovery, footer); body.append(form);
  let confirmed = null; let pending = false;
  const current = () => isCurrent() && !signal?.aborted && panel.isConnected;
  const workflow = createFormWorkflow({ form, submit: save, feedback, ready: false,
    changed: () => Boolean(confirmed) && (confirmed.status === 'unavailable' || enabled.checked !== confirmed.enabled) });
  const sync = () => {
    const busy = pending || workflow.isBusy();
    workflow.setReady(Boolean(confirmed));
    enabled.disabled = busy || !confirmed; reload.disabled = busy;
    if (busy) save.disabled = true;
  };
  const apply = snapshot => {
    confirmed = snapshot; setUiToggle(enabled, snapshot.enabled);
    recovery.hidden = snapshot.status !== 'unavailable'; sync();
  };
  const failure = error => {
    if (error.current) {
      apply(error.current);
      return { state: 'warning', message: 'Tone detection changed elsewhere. The latest saved setting was loaded.' };
    }
    if (!error.status || error.code === 'request_timeout') {
      confirmed = null; recovery.hidden = true; sync();
      return { state: 'error', message: 'The change could not be confirmed. Reload status before trying again.' };
    }
    return { state: 'error', message: error.message || 'Tone detection could not be saved. Try again.' };
  };
  async function load() {
    if (pending || !current()) return;
    pending = true; sync(); workflow.showFeedback('loading', 'Loading tone detection setting…');
    try {
      const snapshot = await requestPagingToneDetection(fetchDocument, { signal });
      if (!current()) return;
      apply(snapshot); workflow.showFeedback('status', '');
    } catch (error) {
      if (!current()) return;
      confirmed = null; recovery.hidden = true;
      workflow.showFeedback('error', error.message || 'Tone detection status could not be loaded. Reload status to try again.');
    } finally { if (current()) { pending = false; sync(); } }
  }
  enabled.addEventListener('change', sync);
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (pending || !current()) return;
    const value = enabled.checked;
    await workflow.save(async () => {
      pending = true; sync();
      const snapshot = await requestPagingToneDetection(fetchDocument, { action: 'enabled', revision: confirmed.revision, value, signal });
      if (current()) apply(snapshot);
    }, { saving: 'Saving tone detection…',
      onError: error => current() ? failure(error) : null });
    if (current()) { pending = false; sync(); }
  });
  reload.addEventListener('click', () => { void load(); });
  sync();
  return { element: panel, load };
}
