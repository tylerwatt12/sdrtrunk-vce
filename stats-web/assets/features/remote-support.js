// Reuse map: Administration page panels, fields, action rows, code metric,
// copy action, and form feedback provide the same light/dark and phone behavior.
import { createFormWorkflow } from '../core/form-workflows.js?v=6';

const ENDPOINT = '/api/v1/remote-support';
export const remoteSupportStopWarning = 'Remote access is off for now, but this setting could not be saved. Check the application data folder before restarting VCE.';
export const remoteSupportDurations = Object.freeze([
  [1800, '30 minutes'], [3600, '1 hour'], [21600, '6 hours'], [86400, '24 hours'], [0, 'Unlimited']
]);

export function decodeRemoteSupport(value) {
  const data = value?.data || value;
  if (!data || typeof data !== 'object' || Array.isArray(data) ||
      typeof data.enabled !== 'boolean' || typeof data.connected !== 'boolean' ||
      (data.code != null && typeof data.code !== 'string') ||
      (data.expires_at_ms != null && (!Number.isSafeInteger(data.expires_at_ms) || data.expires_at_ms < 0)) ||
      (data.unlimited != null && typeof data.unlimited !== 'boolean') ||
      (data.can_start != null && typeof data.can_start !== 'boolean') ||
      (data.stop_not_saved !== undefined && typeof data.stop_not_saved !== 'boolean')) {
    throw new Error('Remote support status could not be read. Try again.');
  }
  return { enabled: data.enabled, connected: data.connected, code: data.code || '',
    expiresAt: data.expires_at_ms || 0, unlimited: data.unlimited === true,
    canStart: data.can_start !== false, stopNotSaved: data.stop_not_saved === true };
}

export async function requestRemoteSupport(fetchDocument, { action = 'status', duration = 21600,
  signal = null, timeoutMs = 15_000 } = {}) {
  if (!['status', 'start', 'stop'].includes(action)) throw new Error('Choose a remote support action.');
  if (action === 'start' && !remoteSupportDurations.some(([seconds]) => seconds === duration)) {
    throw new Error('Choose how long to allow remote support.');
  }
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  const options = { method: action === 'status' ? 'GET' : 'POST',
    headers: { Accept: 'application/json' }, signal: controller.signal };
  if (action !== 'status') {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(action === 'start' ? { action, duration_seconds: duration } : { action });
  }
  try {
    const response = await fetchDocument(ENDPOINT, options);
    let result = null;
    try { result = await response.json(); } catch (_) { }
    if (!response.ok) {
      const error = new Error(action === 'status' ? 'Remote support status is unavailable. Try again.' :
        `Remote support could not be ${action === 'stop' ? 'stopped' : 'started'}. Try again.`);
      error.status = response.status;
      if (action === 'stop' && result?.error?.code === 'remote_support_stop_not_saved') {
        error.code = 'remote_support_stop_not_saved';
        error.message = remoteSupportStopWarning;
      }
      throw error;
    }
    return decodeRemoteSupport(result);
  } catch (error) {
    if (timedOut) throw new Error('The receiver took too long to respond. Try again.');
    throw error;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

export function remoteSupportEnd(snapshot, locale = undefined) {
  if (snapshot.unlimited) return 'No time limit';
  return snapshot.expiresAt ? `Access ends ${new Intl.DateTimeFormat(locale,
    { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(snapshot.expiresAt))}.` : '';
}

export function createRemoteSupportWorkspace({ node, formField, uiSelectFrame, metricCard, uiActionButton,
  fetchDocument, signal, isCurrent = () => true, copyText = text => navigator.clipboard.writeText(text) }) {
  const element = node('section', 'ui-section ui-settings-panel');
  element.dataset.remoteSupport = '';
  const form = node('form', 'admin-form settings-page-form ui-settings-form');
  const duration = node('select', 'ui-select');
  remoteSupportDurations.forEach(([seconds, label]) => {
    const option = node('option', '', label); option.value = String(seconds); duration.append(option);
  });
  duration.value = '21600';
  const durationField = formField('Allow access for', uiSelectFrame(duration));
  const shared = node('div'); shared.hidden = true;
  const codeRow = node('div', 'ui-action-row ui-action-row-start');
  const codeHost = node('div');
  const copied = node('p', 'ui-form-state'); copied.setAttribute('role', 'status'); copied.hidden = true;
  const copy = uiActionButton('Copy', 'icon-copy', async () => {
    if (!snapshot?.code || copy.disabled) return;
    copy.disabled = true; copied.hidden = true;
    try {
      await copyText(snapshot.code);
      if (current()) { copied.textContent = 'Code copied.'; copied.hidden = false; }
    } catch (_) {
      if (current()) {
        copied.textContent = 'The code could not be copied. Select the code to copy it.'; copied.hidden = false;
      }
    } finally { if (current()) copy.disabled = false; }
  }, 'ui-button ui-button-secondary');
  codeRow.append(codeHost, copy);
  const connection = node('p'); connection.setAttribute('role', 'status');
  const end = node('p');
  shared.append(codeRow, copied, connection, end);
  const unavailable = node('p', '', 'Start remote support from this installation.');
  unavailable.hidden = true;
  const stopWarning = node('p', 'ui-notice ui-notice-warning', remoteSupportStopWarning);
  stopWarning.setAttribute('role', 'alert'); stopWarning.hidden = true;
  const submit = node('button', 'ui-button ui-button-primary', 'Allow remote support'); submit.type = 'submit';
  const retry = node('button', 'ui-button ui-button-secondary', 'Retry'); retry.type = 'button'; retry.hidden = true;
  const feedback = node('div', 'admin-form-message'); feedback.setAttribute('role', 'status');
  const actions = node('div', 'admin-form-actions'); actions.append(retry, submit);
  const footer = node('div', 'settings-form-footer'); footer.append(feedback, actions);
  form.append(durationField, shared, unavailable, stopWarning, footer); element.append(form);
  let snapshot = null;
  let pending = false;
  let denied = false;
  let requestController = null;
  const current = () => !signal?.aborted && isCurrent() && element.isConnected;
  const workflow = createFormWorkflow({ form, submit, feedback, ready: false, hideCleanSubmit: false, allowUnchangedSubmit: true,
    changed: () => false });
  const sync = () => {
    const enabled = snapshot?.enabled === true;
    const needsStop = enabled || snapshot?.stopNotSaved === true;
    durationField.hidden = needsStop || snapshot?.canStart === false;
    duration.disabled = pending || !snapshot || workflow.isBusy();
    shared.hidden = !enabled || snapshot?.stopNotSaved === true;
    unavailable.hidden = !snapshot || needsStop || snapshot.canStart;
    stopWarning.hidden = snapshot?.stopNotSaved !== true;
    if (snapshot?.stopNotSaved && !workflow.isBusy()) workflow.showFeedback('status', '');
    submit.textContent = needsStop ? 'Stop remote support' : 'Allow remote support';
    submit.classList.toggle('ui-button-danger', needsStop);
    submit.classList.toggle('ui-button-primary', !needsStop);
    workflow.setReady(Boolean(snapshot) && !denied && (needsStop || snapshot.canStart));
    submit.hidden = Boolean(snapshot) && !needsStop && !snapshot.canStart;
    if (pending) submit.disabled = true;
    retry.disabled = pending || workflow.isBusy();
  };
  const apply = value => {
    const previousCode = snapshot?.code;
    snapshot = value;
    if (previousCode !== value.code) {
      codeHost.replaceChildren(value.code ? metricCard('Give this code to Tyler', null,
        value.code.replace(/\s/g, '').replace(/(.{3})(?=.)/g, '$1 '), { tone: 'neutral', icon: 'icon-lock' }) : '');
      copied.hidden = true;
    }
    codeRow.hidden = !value.code;
    copy.disabled = !value.code;
    connection.textContent = value.connected ? 'Ready for Tyler.' : 'Connecting… VCE will keep trying.';
    end.textContent = remoteSupportEnd(value);
    sync();
  };
  async function load({ initial = false } = {}) {
    if (!current() || pending || denied || workflow.isBusy()) return;
    pending = true; sync();
    if (initial) workflow.showFeedback('loading', 'Loading remote support…');
    requestController = new AbortController();
    const controller = requestController;
    try {
      const value = await requestRemoteSupport(fetchDocument, { signal: controller.signal });
      if (!current()) return;
      apply(value); retry.hidden = true; workflow.showFeedback('status', '');
    } catch (error) {
      if (!current() || error?.name === 'AbortError') return;
      if ([401, 403].includes(error.status)) { denied = true; snapshot = null; }
      retry.hidden = denied;
      workflow.showFeedback('error', denied ? 'You no longer have access to remote support.' :
        'Remote support status is unavailable. Try again.');
    } finally {
      if (requestController === controller) requestController = null;
      pending = false; if (current()) sync();
    }
  }
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (!current() || pending || !snapshot) return;
    const action = snapshot.enabled || snapshot.stopNotSaved ? 'stop' : 'start';
    await workflow.save(() => requestRemoteSupport(fetchDocument,
      { action, duration: Number(duration.value), signal }), {
      saving: action === 'stop' ? 'Stopping remote support…' : 'Starting remote support…',
      onSuccess: value => { if (current()) { apply(value); retry.hidden = true; } },
      onError: error => {
        if (current() && error.code === 'remote_support_stop_not_saved') {
          apply({ ...snapshot, enabled: false, connected: false, code: '', expiresAt: 0,
            unlimited: false, stopNotSaved: true });
          return { state: 'warning', message: remoteSupportStopWarning };
        }
        return { state: 'error', message: action === 'stop' ?
          'Remote support could not be stopped. Try again.' : 'Remote support could not be started. Try again.' };
      }
    });
    if (current()) sync();
  });
  retry.addEventListener('click', () => { void load({ initial: true }); });
  signal?.addEventListener('abort', () => requestController?.abort(), { once: true });
  sync();
  return { element, load };
}
