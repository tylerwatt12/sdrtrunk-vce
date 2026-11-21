// Reuse map: Administration owns navigation and the page heading. Shared sections,
// responsive tables, browsing controls, modal forms and action workflows own the UI.
import { createFormWorkflow } from '../core/form-workflows.js?v=6';
import { createActionWorkflow } from '../core/action-workflows.js?v=1';

const ROOT = '/api/v1/admin/encryption-keys';
const REVISION = /^[0-9a-f]{64}$/;
const PAGE_SIZE = 25;
const KEY_FIELDS = ['label', 'enabled', 'protocol', 'algorithm_id', 'key_id', 'key_hex', 'scope'];
const integer = (value, maximum) => Number.isInteger(value) && value >= 0 && value <= maximum;
const optionalText = value => value === null || typeof value === 'string';
const invalidResponse = () => Object.assign(new Error('Encryption keys could not be read. Reload keys to try again.'),
  { code: 'invalid_response' });

export function decodeEncryptionKeys(value) {
  if (!value || !REVISION.test(value.revision) || typeof value.revision !== 'string' ||
      !Array.isArray(value.keys) || value.keys.length > 10_000 || !Array.isArray(value.protocols) ||
      value.protocols.length !== 3) throw invalidResponse();
  const protocols = new Map();
  for (const protocol of value.protocols) {
    if (!['APCO25', 'DMR', 'NXDN'].includes(protocol?.id) || protocols.has(protocol.id) ||
        typeof protocol.label !== 'string' || !Array.isArray(protocol.algorithms) || !protocol.algorithms.length) {
      throw invalidResponse();
    }
    const algorithms = new Set();
    for (const algorithm of protocol.algorithms) {
      if (!integer(algorithm?.id, 255) || algorithms.has(algorithm.id) || typeof algorithm.label !== 'string' ||
          !Number.isInteger(algorithm.key_bytes) || algorithm.key_bytes < 1 ||
          !Number.isInteger(algorithm.minimum_key_bytes) || algorithm.minimum_key_bytes < 1 ||
          !Number.isInteger(algorithm.maximum_key_bytes) || algorithm.maximum_key_bytes < algorithm.minimum_key_bytes) {
        throw invalidResponse();
      }
      algorithms.add(algorithm.id);
    }
    protocols.set(protocol.id, protocol);
  }
  const ids = new Set();
  for (const key of value.keys) {
    if (!key || typeof key.id !== 'string' || !key.id || ids.has(key.id) || !protocols.has(key.protocol) ||
        typeof key.enabled !== 'boolean' || !integer(key.algorithm_id, 255) || !integer(key.key_id, 65535) ||
        !optionalText(key.label) || !optionalText(key.scope) || !optionalText(key.key_hex)) throw invalidResponse();
    ids.add(key.id);
  }
  return value;
}

export async function requestEncryptionKeys(fetchDocument, { method = 'GET', id = '', value = null,
  revision = '', signal = null } = {}) {
  const headers = { Accept: 'application/json' };
  const options = { method, headers, signal };
  if (method !== 'GET') {
    if (!REVISION.test(revision)) throw new Error('Reload keys before saving changes.');
    headers['If-Match'] = `"${revision}"`;
    if (method !== 'DELETE') {
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(Object.fromEntries(KEY_FIELDS.map(field => [field, value?.[field]])));
    }
  }
  const response = await fetchDocument(`${ROOT}${id ? `/${encodeURIComponent(id)}` : ''}`, options);
  let result = null;
  try { result = await response.json(); } catch (_) { }
  if (!response.ok) {
    const fallback = method === 'GET' ? 'Encryption keys could not be loaded. Reload keys to try again.' :
      method === 'DELETE' ? 'The key could not be deleted. Try again.' : 'The key could not be saved. Try again.';
    const error = new Error(result?.error?.message || fallback);
    error.status = response.status;
    if (response.status === 409) {
      try { error.current = decodeEncryptionKeys(result); } catch (_) { }
    }
    throw error;
  }
  return decodeEncryptionKeys(result);
}

export function normalizeEncryptionKeyHex(value) {
  return String(value || '').replace(/[\s:_-]/g, '').toUpperCase();
}

export function parseEncryptionKeyId(value) {
  const text = String(value).trim();
  const number = /^(?:0x[0-9a-f]+|\d+)$/i.test(text) ? Number(text) : NaN;
  return integer(number, 65535) ? number : NaN;
}

const keyName = key => key.label?.trim() || `${key.protocol} key ${key.key_id}`;
const algorithmLabel = algorithm => `${algorithm.label} (0x${algorithm.id.toString(16).toUpperCase().padStart(2, '0')})`;
const keyValue = key => Object.fromEntries(KEY_FIELDS.map(field => [field, key[field]]));
const sameKey = (left, right) => Boolean(left && right) && JSON.stringify(keyValue(left)) === JSON.stringify(keyValue(right));

export function encryptionKeyPage(snapshot, query = '', requestedPage = 0) {
  const protocol = id => snapshot.protocols.find(item => item.id === id);
  const search = String(query).trim().toLocaleLowerCase();
  const rows = snapshot.keys.filter(key => !search || [keyName(key), key.scope?.trim() || 'All matching calls', key.protocol,
    protocol(key.protocol)?.label, protocol(key.protocol)?.algorithms.find(item => item.id === key.algorithm_id)?.label,
    key.key_id, `0x${key.key_id.toString(16)}`, key.algorithm_id, `0x${key.algorithm_id.toString(16)}`]
    .join(' ').toLocaleLowerCase().includes(search)).sort((left, right) =>
      keyName(left).localeCompare(keyName(right), undefined, { numeric: true, sensitivity: 'base' }) ||
      left.id.localeCompare(right.id));
  const page = Math.max(0, Math.min(Number.isFinite(requestedPage) ? Math.trunc(requestedPage) : 0,
    Math.max(0, Math.ceil(rows.length / PAGE_SIZE) - 1)));
  return { rows: rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE), page,
    offset: page * PAGE_SIZE, total: rows.length, previous: page > 0, next: (page + 1) * PAGE_SIZE < rows.length };
}

export function createEncryptionKeysWorkspace({ node, section, sectionActionHost, formField, uiSelectFrame,
  uiToggle, uiStatus, iconButton, table, browsingWorkflows, openReadOnlyModal, confirmAction,
  modalFooter, fetchDocument, formatNumber = String, signal = null, isCurrent = () => true }) {
  const host = node('div', 'encryption-keys-workspace ui-settings-page');
  const feedback = node('div'); feedback.hidden = true;
  const add = node('button', 'ui-button ui-button-primary', 'Add key'); add.type = 'button'; add.id = 'encryption-key-add';
  const reload = node('button', 'ui-button ui-button-secondary', 'Reload keys'); reload.type = 'button';
  const actions = sectionActionHost(); actions.append(reload, add);
  const results = node('div'); const pager = node('div');
  const search = node('input', 'ui-input'); search.type = 'search'; search.placeholder = 'Label, protocol, algorithm, ID or scope';
  const toolbar = node('div', 'ui-section-body'); toolbar.append(formField('Search keys', search));
  const panel = section('Saved keys', null, actions);
  panel.append(toolbar, results, pager); host.append(feedback, panel);
  const pageActions = createActionWorkflow({ surface: host, feedback });
  const controller = {};
  let snapshot = null; let currentPage = 0; let disposed = false; let activeEditor = null;
  const current = () => !disposed && !signal?.aborted && isCurrent() && host.isConnected;
  const read = options => requestEncryptionKeys(fetchDocument, { signal, ...options });
  const protocolFor = id => snapshot?.protocols.find(item => item.id === id);
  const algorithmFor = key => protocolFor(key.protocol)?.algorithms.find(item => item.id === key.algorithm_id);
  const apply = value => { snapshot = value; draw(); };

  function draw() {
    add.disabled = !snapshot; search.disabled = !snapshot;
    if (!snapshot) return;
    const page = encryptionKeyPage(snapshot, search.value, currentPage); currentPage = page.page;
    const empty = snapshot.keys.length ? 'No matching keys. Try another search.' : 'No encryption keys configured. Add a key to begin.';
    results.replaceChildren(table(page.rows, [
      { id: 'label', label: 'Label', render: keyName },
      { id: 'protocol', label: 'Protocol', render: key => protocolFor(key.protocol)?.label || key.protocol },
      { id: 'algorithm', label: 'Algorithm', render: key => algorithmFor(key) ? algorithmLabel(algorithmFor(key)) :
        `Algorithm 0x${key.algorithm_id.toString(16).toUpperCase().padStart(2, '0')}` },
      { id: 'key-id', label: 'Key ID', render: key => `${key.key_id} (0x${key.key_id.toString(16).toUpperCase()})` },
      { id: 'material', label: 'Key', render: key => key.key_hex ? `${key.key_hex.length / 2} bytes` : 'Not configured' },
      { id: 'scope', label: 'Scope', render: key => key.scope?.trim() || 'All matching calls' },
      { id: 'enabled', label: 'State', render: key => uiStatus(key.enabled ? 'Enabled' : 'Disabled', 'neutral') },
      { id: 'actions', label: 'Actions', alwaysVisible: true, resizable: false, render: key => {
        const controls = node('div', 'ui-action-row');
        const edit = iconButton('icon-edit', `Edit key ${keyName(key)}`); edit.type = 'button';
        const remove = iconButton('icon-trash', `Delete key ${keyName(key)}`, 'ui-button ui-button-danger-quiet ui-icon-button');
        remove.type = 'button';
        edit.addEventListener('click', () => void openEditor(key));
        remove.addEventListener('click', () => void deleteKey(key));
        controls.append(edit, remove); return controls;
      } }
    ], empty, { type: 'encryption-keys', sortable: false, mobileCards: true, controller,
      layoutMenuHost: actions, rowKey: key => key.id }));
    pager.replaceChildren(browsingWorkflows.createBrowsingPager({ node, ariaLabel: 'Key pages',
      countText: browsingWorkflows.pageRangeText({ offset: page.offset, visible: page.rows.length,
        total: page.total, label: 'Keys', format: formatNumber }),
      previous: { enabled: page.previous, onClick: () => { currentPage--; draw(); } },
      next: { enabled: page.next, onClick: () => { currentPage++; draw(); } }
    }));
  }

  async function load() {
    if (!current() || pageActions.isBusy()) return;
    try {
      await pageActions.run(() => read(), { pending: 'Loading encryption keys…',
        onSuccess: value => { if (current()) apply(value); } });
    } catch (_) { /* Shared feedback retains the reload action. */ }
    finally { if (current()) draw(); }
  }

  async function deleteKey(key) {
    await confirmAction(`Delete ${keyName(key)}? New calls that use this key will no longer be decrypted.`, {
      title: 'Delete encryption key', confirmLabel: 'Delete key', pending: 'Deleting key…',
      returnFocusSelector: '#encryption-key-add',
      onConfirm: async () => {
        const saved = snapshot.keys.find(item => item.id === key.id);
        if (!saved) throw new Error('This key no longer exists. Cancel to return to the list.');
        if (!sameKey(saved, key)) throw new Error('This key changed elsewhere. Cancel and review it before deleting.');
        try { const value = await read({ method: 'DELETE', id: key.id, revision: snapshot.revision });
          if (current()) apply(value);
        } catch (error) {
          if (error.current && current()) { apply(error.current);
            throw new Error('Keys changed elsewhere. Review the saved key before trying again.'); }
          throw error;
        }
      }
    });
  }

  async function openEditor(existing = null) {
    if (!snapshot || activeEditor || !current()) return;
    const initial = existing ? keyValue(existing) : { label: '', enabled: true, protocol: snapshot.protocols[0].id,
      algorithm_id: snapshot.protocols[0].algorithms[0].id, key_id: 0, key_hex: '', scope: '' };
    const form = node('form', 'admin-form');
    const label = node('input', 'ui-input'); label.type = 'text'; label.maxLength = 4096; label.value = initial.label || '';
    const enabledField = node('div', 'ui-field-row');
    const enabledCopy = node('span', 'ui-toggle-copy'); enabledCopy.append(node('strong', '', 'Enabled'));
    const enabledToggle = uiToggle(initial.enabled, 'Enabled'); const enabled = enabledToggle.querySelector('input');
    enabledField.append(enabledCopy, enabledToggle);
    const protocol = node('select', 'ui-select');
    snapshot.protocols.forEach(item => { const option = node('option', '', item.label); option.value = item.id; protocol.append(option); });
    protocol.value = initial.protocol;
    const algorithm = node('select', 'ui-select');
    const keyId = node('input', 'ui-input'); keyId.type = 'text'; keyId.maxLength = 10;
    keyId.required = true; keyId.value = String(initial.key_id);
    const keyHex = node('input', 'ui-input mono'); keyHex.type = 'text'; keyHex.required = true; keyHex.maxLength = 4096;
    keyHex.autocomplete = 'off'; keyHex.spellcheck = false; keyHex.value = initial.key_hex || '';
    const keyHelp = node('p', 'ui-field-detail'); keyHelp.id = 'encryption-key-length-help'; keyHex.setAttribute('aria-describedby', keyHelp.id);
    const scope = node('input', 'ui-input'); scope.type = 'text'; scope.maxLength = 4096; scope.value = initial.scope || '';
    const message = node('div', 'admin-form-message');
    const cancel = node('button', 'ui-button ui-button-secondary', 'Cancel'); cancel.type = 'button';
    const reloadSaved = node('button', 'ui-button ui-button-secondary', 'Reload saved keys'); reloadSaved.type = 'button';
    reloadSaved.hidden = true;
    const save = node('button', 'ui-button ui-button-primary', 'Save'); save.type = 'submit'; save.disabled = true;
    const readDraft = () => ({ label: label.value || null, enabled: enabled.checked, protocol: protocol.value,
      algorithm_id: Number(algorithm.value), key_id: parseEncryptionKeyId(keyId.value),
      key_hex: normalizeEncryptionKeyHex(keyHex.value), scope: scope.value || null });
    const normalizedInitial = { ...initial, label: initial.label || null, scope: initial.scope || null };
    let baseline = JSON.stringify(normalizedInitial); let needsReload = false;
    let uncertainCreate = false; let previousIds = new Set(); let recoveredDraft = null;
    const canSave = () => !needsReload && (!recoveredDraft || recoveredDraft !== JSON.stringify(readDraft()));
    const keyField = formField('Key (hex)', keyHex); keyField.append(keyHelp);
    form.append(enabledField, formField('Label', label), formField('Protocol', uiSelectFrame(protocol)),
      formField('Algorithm', uiSelectFrame(algorithm)), formField('Key ID', keyId, 'Enter decimal or 0x hexadecimal.'), keyField,
      formField('Scope', scope, 'Optional. Enter part of a call identifier to limit where this key is used.'),
      message, modalFooter(reloadSaved, cancel, save));
    let modal;
    modal = openReadOnlyModal(existing ? 'Edit encryption key' : 'Add encryption key', form, {
      id: 'encryption-key-editor', className: 'admin-modal', returnFocusSelector: '#encryption-key-add',
      onClose: () => { if (activeEditor === modal) activeEditor = null; }
    });
    if (!modal) return;
    activeEditor = modal;
    if (modal.ready && !await modal.ready) { if (activeEditor === modal) activeEditor = null; return; }
    const workflow = createFormWorkflow({ form, submit: save, feedback: message, modal,
      changed: () => JSON.stringify(readDraft()) !== baseline, hideCleanSubmit: false });
    const reloadWorkflow = createActionWorkflow({ surface: form, feedback: message, modal });
    const syncAlgorithms = (selected = null) => {
      const choices = protocolFor(protocol.value)?.algorithms || [];
      algorithm.replaceChildren(...choices.map(item => { const option = node('option', '', algorithmLabel(item)); option.value = String(item.id); return option; }));
      if (selected !== null && !choices.some(item => item.id === selected)) {
        const unavailable = node('option', '', `Algorithm 0x${selected.toString(16).toUpperCase().padStart(2, '0')} (unavailable)`);
        unavailable.value = String(selected); algorithm.append(unavailable);
      }
      if (selected !== null) algorithm.value = String(selected);
      validateKey(); workflow.refresh();
    };
    const validateKey = () => {
      const choice = protocolFor(protocol.value)?.algorithms.find(item => item.id === Number(algorithm.value));
      const minimum = choice?.minimum_key_bytes; const maximum = choice?.maximum_key_bytes;
      algorithm.setCustomValidity(choice ? '' : 'Choose a supported algorithm.');
      keyHelp.textContent = choice ? (minimum === maximum ? `Enter ${maximum} bytes (${maximum * 2} hexadecimal digits).` :
        `Enter ${minimum}–${maximum} bytes (${minimum * 2}–${maximum * 2} hexadecimal digits).`) : 'Choose a supported algorithm.';
      const value = normalizeEncryptionKeyHex(keyHex.value);
      keyHex.setCustomValidity(!value ? '' : !/^[0-9A-F]+$/.test(value) || value.length % 2 ?
        'Enter complete hexadecimal bytes.' : !choice || value.length / 2 < minimum || value.length / 2 > maximum ?
          'Enter a key with the length shown below.' : '');
    };
    syncAlgorithms(initial.algorithm_id);
    // Compare against the actual supported defaults after the option controls are ready.
    baseline = JSON.stringify(readDraft());
    workflow.refresh();
    protocol.addEventListener('change', () => syncAlgorithms()); algorithm.addEventListener('change', validateKey);
    keyId.addEventListener('input', () => keyId.setCustomValidity(keyId.value &&
      !Number.isFinite(parseEncryptionKeyId(keyId.value)) ? 'Enter a key ID from 0 through 65535, or 0x0000 through 0xFFFF.' : ''));
    keyHex.addEventListener('input', validateKey);
    form.addEventListener('input', () => workflow.setReady(canSave()));
    form.addEventListener('change', () => workflow.setReady(canSave()));
    cancel.addEventListener('click', modal.close);
    reloadSaved.addEventListener('click', async () => {
      if (workflow.isBusy() || reloadWorkflow.isBusy()) return;
      try {
        await reloadWorkflow.run(() => read(), { pending: 'Reloading saved keys…', success: null,
          onSuccess: value => {
            if (current()) apply(value);
            needsReload = Boolean(existing && !value.keys.some(key => key.id === existing.id));
            recoveredDraft = !existing && uncertainCreate && value.keys.some(key =>
              !previousIds.has(key.id) && sameKey(key, readDraft())) ? JSON.stringify(readDraft()) : null;
            if (recoveredDraft) baseline = recoveredDraft;
          },
          onError: () => { needsReload = true; } });
        reloadWorkflow.showFeedback(needsReload ? 'error' : 'status', needsReload ?
          'This key no longer exists. Cancel and add it again.' : recoveredDraft ?
            'This key is already saved. Cancel to return to the list, or change the draft to add another key.' :
            'Saved keys reloaded. Your draft was kept. Review it before saving.');
      } catch (_) { /* Shared feedback retains the draft and reload action. */ }
      workflow.setReady(canSave());
    });
    form.addEventListener('submit', async event => {
      event.preventDefault(); if (reloadWorkflow.isBusy() || !current()) return;
      validateKey();
      await workflow.save(async () => {
        if (!existing) previousIds = new Set(snapshot.keys.map(key => key.id));
        const value = await read({ method: existing ? 'PUT' : 'POST', id: existing?.id || '',
          value: readDraft(), revision: snapshot.revision });
        baseline = JSON.stringify(readDraft());
        if (current()) apply(value);
      }, { saving: 'Saving encryption key…', onSuccess: () => { modal.setDirty(false); modal.close(); },
        onError: error => {
          if (error.current && current()) { apply(error.current);
            return { state: 'warning', message: 'Keys changed elsewhere. Your draft was kept. Review it and save again.' }; }
          if (!error.status || [404, 409].includes(error.status) || error.code === 'request_timeout') {
            uncertainCreate = !existing && !error.status;
            needsReload = true; reloadSaved.hidden = false; workflow.setReady(false);
            return { state: 'error', message: 'The save could not be confirmed. Your draft was kept. Reload saved keys before trying again.' };
          }
          return { state: 'error', message: error.message || 'The key could not be saved. Try again.' };
        } });
    });
    modal.focus(label);
  }

  add.addEventListener('click', () => void openEditor()); reload.addEventListener('click', () => void load());
  search.addEventListener('input', () => { currentPage = 0; draw(); });
  add.disabled = search.disabled = true;
  return { element: host, load, close() { disposed = true; controller.widthObserver?.disconnect();
    controller.layoutMenuCleanup?.(); controller.overflowControls?.destroy(); } };
}
