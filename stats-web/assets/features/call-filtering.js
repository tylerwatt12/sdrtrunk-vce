// Reuse map: Administration sections, shared toggle/number fields and the page
// form workflow provide layout, feedback and busy states in both themes and sizes.
import { createFormWorkflow } from '../core/form-workflows.js?v=6';

const ROOT = '/api/v1/admin/call-filtering';
const REVISION = /^[0-9a-f]{64}$/;
const validMinimum = value => Number.isInteger(value) && value >= 1 && value <= 100;
const validSettings = value => value && typeof value.enabled === 'boolean' &&
  (validMinimum(value.minimum_usable_voice_percent) ||
    !value.enabled && value.minimum_usable_voice_percent === null);

export function decodeCallFiltering(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      typeof value.revision !== 'string' || !REVISION.test(value.revision) || !validSettings(value)) {
    throw new Error('Call filtering settings could not be read. Reload settings to try again.');
  }
  return { revision: value.revision, enabled: value.enabled,
    minimum_usable_voice_percent: value.minimum_usable_voice_percent };
}

export async function requestCallFiltering(fetchDocument, { value = null, revision = '', signal = null,
  timeoutMs = 15_000 } = {}) {
  const headers = { Accept: 'application/json' };
  const options = { method: value === null ? 'GET' : 'PUT', headers };
  if (value !== null) {
    if (typeof revision !== 'string' || !REVISION.test(revision)) throw new Error('Reload call filtering settings before saving.');
    if (!validSettings(value)) throw new Error('Choose a minimum usable voice percentage from 1 to 100.');
    headers['If-Match'] = `"${revision}"`; headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify({ enabled: value.enabled,
      minimum_usable_voice_percent: value.minimum_usable_voice_percent });
  }
  const controller = new AbortController(); let timedOut = false;
  const abort = () => controller.abort();
  if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  options.signal = controller.signal;
  try {
    const response = await fetchDocument(ROOT, options);
    let result = null;
    try { result = await response.json(); } catch (_) { }
    if (!response.ok) {
      const error = new Error(result?.error?.message || 'Call filtering settings could not be saved. Try again.');
      error.status = response.status;
      if (response.status === 409) {
        try { error.current = decodeCallFiltering(result); } catch (_) { }
      }
      throw error;
    }
    return decodeCallFiltering(result);
  } catch (error) {
    if (timedOut) {
      const timeout = new Error('The receiver took too long to respond. Reload settings to try again.');
      timeout.code = 'request_timeout'; throw timeout;
    }
    throw error;
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
}

export function createCallFilteringSettings({ node, section, sectionActionHost, formField, uiToggleField,
  setUiToggle, fetchDocument, signal = null, isCurrent = () => true }) {
  const body = node('div', 'admin-section-body');
  const form = node('form', 'admin-form settings-page-form ui-settings-form ui-form-section');
  const reload = node('button', 'ui-button ui-button-secondary', 'Reload settings'); reload.type = 'button';
  const panel = section('Call filtering', body, sectionActionHost(reload));
  panel.classList.add('ui-settings-panel'); panel.dataset.callFiltering = '';
  const toggle = uiToggleField('Filter low-quality calls', false, 'Filter low-quality calls',
    'Applies to P25, DMR and NXDN calls.');
  const enabled = toggle.querySelector('input');
  const minimum = node('input'); minimum.type = 'number'; minimum.min = '1'; minimum.max = '100';
  minimum.step = '1'; minimum.name = 'minimum_usable_voice_percent';
  minimum.placeholder = '1–100'; minimum.inputMode = 'numeric';
  minimum.setAttribute('aria-label', 'Minimum usable voice (%)');
  const minimumField = formField('Minimum usable voice (%)', minimum,
    'Calls below this percentage are skipped for listening, recordings, streaming and call alerts.');
  const save = node('button', 'ui-button ui-button-primary', 'Save'); save.type = 'submit';
  const feedback = node('div', 'admin-form-message'); feedback.setAttribute('role', 'status');
  const footer = node('div', 'settings-form-footer'); const actions = node('div', 'admin-form-actions');
  actions.append(save); footer.append(feedback, actions); form.append(toggle, minimumField, footer); body.append(form);
  let confirmed = null; let pending = false; let needsReload = false;
  const current = () => isCurrent() && !signal?.aborted && panel.isConnected;
  const readMinimum = () => minimum.value === '' ? null : minimum.valueAsNumber;
  const changed = () => Boolean(confirmed) && (enabled.checked !== confirmed.enabled ||
    readMinimum() !== confirmed.minimum_usable_voice_percent);
  const workflow = createFormWorkflow({ form, submit: save, feedback, ready: false, changed });
  const sync = () => {
    const busy = pending || workflow.isBusy();
    minimumField.hidden = !enabled.checked; minimum.required = enabled.checked;
    minimum.disabled = busy || !confirmed || !enabled.checked;
    enabled.disabled = busy || !confirmed; reload.disabled = busy;
    workflow.setReady(Boolean(confirmed) && !needsReload && (!enabled.checked || minimum.validity.valid));
    if (busy) save.disabled = true;
  };
  const apply = (snapshot, preserveDraft = false) => {
    const keepDraft = preserveDraft && changed();
    confirmed = snapshot; needsReload = false;
    if (!keepDraft) {
      setUiToggle(enabled, snapshot.enabled);
      minimum.value = snapshot.minimum_usable_voice_percent === null ? '' : String(snapshot.minimum_usable_voice_percent);
    }
    sync();
  };
  const failure = error => {
    if (error.current) {
      apply(error.current, true);
      return { state: 'warning', message: 'Call filtering changed elsewhere. Your unsaved changes were kept. Review and save again.' };
    }
    if (!error.status || error.status === 409 || error.code === 'request_timeout') {
      needsReload = true; sync();
      return { state: 'error', message: 'The change could not be confirmed. Your unsaved changes were kept. Reload settings before trying again.' };
    }
    return { state: 'error', message: error.message || 'Call filtering settings could not be saved. Try again.' };
  };
  async function load() {
    if (pending || !current()) return;
    pending = true; sync(); workflow.showFeedback('loading', 'Loading call filtering settings…');
    try {
      const snapshot = await requestCallFiltering(fetchDocument, { signal });
      if (!current()) return;
      apply(snapshot, true); workflow.showFeedback('status', changed() ? 'Unsaved changes' : '');
    } catch (error) {
      if (!current()) return;
      needsReload = true;
      workflow.showFeedback('error', error.message || 'Call filtering settings could not be loaded. Reload settings to try again.');
    } finally { if (current()) { pending = false; sync(); } }
  }
  enabled.addEventListener('change', () => {
    if (!enabled.checked && !validMinimum(readMinimum())) {
      minimum.value = confirmed?.minimum_usable_voice_percent == null ? '' : String(confirmed.minimum_usable_voice_percent);
    }
    sync();
  });
  minimum.addEventListener('input', sync);
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (pending || !current()) return;
    const value = { enabled: enabled.checked, minimum_usable_voice_percent: readMinimum() };
    await workflow.save(async () => {
      pending = true; sync();
      const snapshot = await requestCallFiltering(fetchDocument, { value, revision: confirmed.revision, signal });
      if (current()) apply(snapshot);
    }, { saving: 'Saving call filtering…', onError: error => current() ? failure(error) : null });
    if (current()) { pending = false; sync(); }
  });
  reload.addEventListener('click', () => { void load(); });
  sync();
  return { element: panel, load };
}
