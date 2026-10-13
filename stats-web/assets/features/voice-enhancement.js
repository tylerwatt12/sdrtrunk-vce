// Reuse map: Administration sections, ui-toggle-field/ui-field/ui-admin-facts,
// shared form busy/feedback guards and action rows cover both themes and sizes.
import { createFormWorkflow } from '../core/form-workflows.js?v=2';

export const MODEL_ZIP_MAX_BYTES = 16 * 1024 * 1024;
const ROOT = '/api/v1/admin/voice-enhancement';
const REVISION = /^[0-9a-f]{64}$/;

export function decodeVoiceEnhancement(value) {
  const model = value?.model;
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      typeof value.revision !== 'string' || !REVISION.test(value.revision) || typeof value.enabled !== 'boolean' ||
      !['ready', 'no_model', 'unavailable'].includes(value.status) ||
      (model !== null && (!model || typeof model !== 'object' || Array.isArray(model) ||
        typeof model.id !== 'string' || !model.id.trim() || model.id.length > 160 ||
        model.input_sample_rate !== 8000 || model.output_sample_rate !== 16000 ||
        typeof model.sha256 !== 'string' || !REVISION.test(model.sha256) || !Array.isArray(model.codecs) ||
        !model.codecs.includes('IMBE') || !model.codecs.includes('AMBE_PLUS_2'))) ||
      (value.status === 'ready' && model === null) || (value.status === 'no_model' && model !== null)) {
    throw new Error('Voice enhancement status could not be read. Reload to try again.');
  }
  return { revision: value.revision, enabled: value.enabled, status: value.status,
    model: model === null ? null : { ...model } };
}

export function validateModelZip(file) {
  if (!file) return 'Choose a model ZIP file.';
  if (!/\.zip$/i.test(String(file.name || ''))) return 'Choose a ZIP file containing a trained voice model.';
  if (!Number.isFinite(file.size) || file.size <= 0) return 'The model ZIP file is empty.';
  if (file.size > MODEL_ZIP_MAX_BYTES) return 'Choose a model ZIP file no larger than 16 MB.';
  return '';
}

export async function requestVoiceEnhancement(fetchDocument, { action = 'status', revision = '',
  value = null, file = null, signal = null, timeoutMs = 30_000 } = {}) {
  if (!['status', 'enabled', 'model'].includes(action)) throw new Error('Unknown voice enhancement action.');
  const headers = { Accept: 'application/json' };
  const options = { method: action === 'status' ? 'GET' : action === 'enabled' ? 'PUT' : 'POST', headers };
  if (action !== 'status') {
    if (typeof revision !== 'string' || !REVISION.test(revision)) throw new Error('Reload voice enhancement status before saving.');
    headers['If-Match'] = `"${revision}"`;
    if (action === 'enabled') {
      if (typeof value !== 'boolean') throw new Error('Choose On or Off for voice enhancement.');
      headers['Content-Type'] = 'application/json'; options.body = JSON.stringify({ value });
    } else {
      const failure = validateModelZip(file);
      if (failure) throw new Error(failure);
      headers['Content-Type'] = 'application/zip'; options.body = file;
    }
  }
  const controller = new AbortController(); let timedOut = false;
  const abort = () => controller.abort();
  if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  options.signal = controller.signal;
  try {
    const response = await fetchDocument(`${ROOT}${action === 'status' ? '' : `/${action}`}`, options);
    let result = null;
    try { result = await response.json(); } catch (_) { }
    if (!response.ok) {
      const failure = result?.error && typeof result.error === 'object' ? result.error : null;
      const error = new Error(failure?.message || 'Voice enhancement could not be saved. Try again.');
      error.status = response.status; error.code = failure?.code;
      if (response.status === 409) {
        try { error.current = decodeVoiceEnhancement(result); } catch (_) { }
      }
      throw error;
    }
    return decodeVoiceEnhancement(result);
  } catch (error) {
    if (timedOut) {
      const timeout = new Error('The receiver took too long to respond. Reload status to try again.');
      timeout.code = 'request_timeout'; throw timeout;
    }
    throw error;
  } finally {
    clearTimeout(timer); signal?.removeEventListener('abort', abort);
  }
}

export function createVoiceEnhancementSettings({ node, section, sectionActionHost, uiToggleField,
  setUiToggle, formField, fetchDocument, signal = null, isCurrent = () => true }) {
  const body = node('div', 'admin-section-body');
  const workspace = node('div', 'settings-page-form ui-settings-form voice-enhancement-settings');
  const reload = node('button', 'ui-button ui-button-secondary', 'Reload status'); reload.type = 'button';
  const panel = section('Voice enhancement', body, sectionActionHost(reload));
  panel.classList.add('ui-settings-panel'); panel.dataset.voiceEnhancement = '';
  const form = node('form', 'admin-form ui-settings-form');
  const toggle = uiToggleField('16 kHz voice enhancement', false, '16 kHz voice enhancement', 'Applies to new browser calls.');
  const enabled = toggle.querySelector('input'); enabled.disabled = true;
  const fallback = node('p', 'ui-field-detail'); fallback.setAttribute('role', 'status');
  const save = node('button', 'ui-button ui-button-primary', 'Save'); save.type = 'submit';
  const message = node('div', 'admin-form-message'); message.setAttribute('role', 'status');
  const footer = node('div', 'settings-form-footer'); const actions = node('div', 'admin-form-actions');
  actions.append(save); footer.append(message, actions); form.append(toggle, fallback, footer);
  const modelForm = node('form', 'admin-form ui-settings-form');
  const facts = node('dl', 'ui-facts ui-admin-facts'); const fact = node('div', 'ui-fact');
  const modelId = node('dd', '', 'Loading…'); fact.append(node('dt', '', 'Current model'), modelId); facts.append(fact);
  const file = node('input', 'voice-enhancement-model-file'); file.type = 'file'; file.accept = '.zip,application/zip'; file.required = true;
  const update = node('button', 'ui-button ui-button-primary', 'Update model'); update.type = 'submit';
  const modelMessage = node('div', 'admin-form-message'); modelMessage.setAttribute('role', 'status');
  const modelFooter = node('div', 'settings-form-footer'); const modelActions = node('div', 'admin-form-actions');
  modelActions.append(update); modelFooter.append(modelMessage, modelActions);
  modelForm.append(facts, formField('Model ZIP file', file, 'Choose a trained model ZIP file, up to 16 MB.'), modelFooter);
  workspace.append(form, modelForm); body.append(workspace);
  let confirmed = null; let pending = false; let loadId = 0;
  const current = () => isCurrent() && !signal?.aborted && panel.isConnected;
  const selectedFile = () => file.files?.[0] || null;
  const enabledWorkflow = createFormWorkflow({ form, submit: save, feedback: message, ready: false,
    changed: () => Boolean(confirmed) && enabled.checked !== confirmed.enabled });
  const modelWorkflow = createFormWorkflow({ form: modelForm, submit: update, feedback: modelMessage, ready: false,
    changed: () => Boolean(selectedFile()) });
  const sync = () => {
    const busy = pending || enabledWorkflow.isBusy() || modelWorkflow.isBusy();
    const loaded = Boolean(confirmed);
    enabledWorkflow.setReady(loaded); modelWorkflow.setReady(loaded && !validateModelZip(selectedFile()));
    enabled.disabled = busy || !loaded || (confirmed.status !== 'ready' && !confirmed.enabled);
    file.disabled = busy || !loaded; reload.disabled = busy;
    if (busy) { save.disabled = true; update.disabled = true; }
  };
  const apply = (snapshot) => {
    confirmed = snapshot; setUiToggle(enabled, snapshot.enabled);
    modelId.textContent = snapshot.model?.id || (snapshot.status === 'unavailable' ? 'Model unavailable' : 'No model installed');
    fallback.textContent = snapshot.status === 'no_model' ? 'Install a model to turn this on. Browser calls use original audio.' :
      snapshot.status === 'unavailable' ? 'The model is unavailable. Browser calls use original audio.' : '';
    fallback.hidden = !fallback.textContent; sync();
  };
  const failure = (error, uploading = false) => {
    if (error.current) {
      apply(error.current);
      return { state: 'warning', message: 'Voice enhancement changed elsewhere. The latest saved settings were loaded.' };
    }
    if (uploading && error.status === 422) return { state: 'error',
      message: 'This model could not be installed. Choose a compatible model ZIP and try again. The current model is unchanged.' };
    if (error.status === 503) return { state: 'error', message: 'Voice enhancement is busy. Try again in a moment.' };
    if (!error.status || error.code === 'request_timeout') {
      confirmed = null; modelId.textContent = 'Status unavailable'; sync();
      return { state: 'error', message: 'The change could not be confirmed. Reload status before trying again.' };
    }
    return { state: 'error', message: error.message || 'Voice enhancement could not be saved. Try again.' };
  };
  async function load() {
    if (pending || !current()) return;
    const id = ++loadId; pending = true; sync(); enabledWorkflow.showFeedback('loading', 'Loading voice enhancement…');
    try {
      const snapshot = await requestVoiceEnhancement(fetchDocument, { signal });
      if (id !== loadId || !current()) return;
      apply(snapshot); enabledWorkflow.showFeedback('status', '');
    } catch (error) {
      if (!current()) return;
      confirmed = null; modelId.textContent = 'Status unavailable'; fallback.hidden = true;
      enabledWorkflow.showFeedback('error', error.message || 'Voice enhancement status could not be loaded.');
    } finally { if (current()) { pending = false; sync(); } }
  }
  enabled.addEventListener('change', sync);
  file.addEventListener('change', () => {
    const invalid = selectedFile() ? validateModelZip(selectedFile()) : '';
    sync();
    // Shared dirty feedback runs when this change bubbles through the form.
    // Keep a rejected file's actionable validation message after that update.
    if (invalid) queueMicrotask(() => { if (current()) modelWorkflow.showFeedback('error', invalid); });
  });
  form.addEventListener('submit', async (event) => {
    event.preventDefault(); if (pending || !current()) return;
    const value = enabled.checked;
    await enabledWorkflow.save(async () => {
      pending = true; sync();
      const snapshot = await requestVoiceEnhancement(fetchDocument, { action: 'enabled', revision: confirmed.revision, value, signal });
      if (current()) apply(snapshot);
    }, { saving: 'Saving voice enhancement…', success: 'Voice enhancement saved.', onError: (error) => current() ? failure(error) : null });
    if (current()) { pending = false; sync(); }
  });
  modelForm.addEventListener('submit', async (event) => {
    event.preventDefault(); if (pending || !current()) return;
    const chosen = selectedFile();
    await modelWorkflow.save(async () => {
      pending = true; sync();
      const snapshot = await requestVoiceEnhancement(fetchDocument, { action: 'model', revision: confirmed.revision, file: chosen, signal });
      if (current()) { file.value = ''; apply(snapshot); }
    }, { saving: 'Updating model…', success: 'Model updated.', onError: (error) => current() ? failure(error, true) : null });
    if (current()) { pending = false; sync(); }
  });
  reload.addEventListener('click', () => { void load(); });
  sync();
  return { element: panel, load };
}
