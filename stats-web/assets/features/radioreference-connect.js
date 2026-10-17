import { createFormWorkflow } from '../core/form-workflows.js?v=4';

function connectionFailure(error) {
  switch (error?.accountState) {
    case 'INVALID_CREDENTIALS':
      return 'RadioReference rejected the username or password. Check both and try again.';
    case 'EXPIRED_PREMIUM':
      return 'A current RadioReference Premium subscription is required. Renew or upgrade your account, then try again.';
    case 'SECURE_TRANSPORT_REQUIRED':
      return 'A secure connection to RadioReference could not be established. Try again later.';
    case 'UNAVAILABLE':
      return 'RadioReference is currently unavailable. Try again in a moment.';
    default:
      if (error?.status === 401 || error?.status === 403) {
        return 'Your application sign-in no longer permits this action. Sign in with an administrator account and try again.';
      }
      return 'RadioReference could not be connected. Check your account details and try again.';
  }
}

// Reuse map: the shared modal stack preserves the parent draft and focus;
// account fields, toggle, footer and form workflow serve every connection entry.
export function openRadioReferenceConnect(ui, { introduction = '', signal = null } = {}) {
  if (signal?.aborted) return Promise.resolve(null);
  const { node, formField, uiToggleField, openReadOnlyModal, requestJson } = ui;
  return new Promise((resolve) => {
    const form = node('form', 'admin-form radioreference-account-form editor-workspace');
    const userName = node('input', 'ui-input');
    userName.name = 'radioreference-username';
    userName.autocomplete = 'username';
    userName.maxLength = 256;
    userName.required = true;
    const password = node('input', 'ui-input');
    password.name = 'radioreference-password';
    password.type = 'password';
    password.autocomplete = 'current-password';
    password.maxLength = 1024;
    password.required = true;
    const rememberField = uiToggleField('Remember credentials on this receiver', false,
      'Remember credentials on this receiver',
      'Stores credentials in this receiver’s protected portable settings.');
    const remember = rememberField.querySelector('input');
    remember.name = 'radioreference-remember';
    const feedback = node('div', 'admin-form-message');
    const cancel = node('button', 'ui-button ui-button-secondary', 'Cancel');
    const connect = node('button', 'ui-button ui-button-primary', 'Connect account');
    cancel.type = 'button';
    connect.type = 'submit';
    const footer = node('footer', 'ui-modal-footer ui-action-row');
    footer.append(cancel, connect);
    if (introduction) form.append(node('p', 'ui-field-detail', introduction));
    form.append(formField('Username', userName), formField('Password', password), rememberField,
      node('p', 'ui-field-detail', 'A RadioReference Premium subscription is required.'), feedback, footer);

    let disposed = false;
    let cancelled = false;
    let result = null;
    let modal = null;
    const request = new AbortController();
    const cleanup = () => {
      if (disposed) return;
      disposed = true;
      password.value = '';
      request.abort();
      signal?.removeEventListener('abort', abort);
      resolve(cancelled ? null : result);
    };
    const abort = () => {
      if (disposed) return;
      cancelled = true;
      request.abort();
      password.value = '';
      modal?.setBusy(false);
      modal?.setDirty(false);
      modal?.close();
    };
    modal = openReadOnlyModal('Connect RadioReference', form, {
      id: 'radioreference-connect', className: 'modal-size-small', stack: 'child',
      cleanup, onClose: cleanup
    });
    if (!modal) { cleanup(); return; }
    const workflow = createFormWorkflow({ form, submit: connect, feedback, modal: {
      setDirty: (value) => modal.setDirty(!disposed && !cancelled && value),
      setBusy: (value) => modal.setBusy(!disposed && !cancelled && value)
    }, ready: false });
    workflow.setModalLayout(true);
    cancel.addEventListener('click', () => modal.close());
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    void modal.ready.then((shown) => {
      if (!shown) { cleanup(); return; }
      if (disposed || cancelled) { modal.close(); return; }
      workflow.setReady(true);
      modal.focus(userName);
    });
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (disposed || cancelled || workflow.isBusy()) return;
      await workflow.save(async () => {
        const payload = { userName: userName.value, password: password.value, remember: remember.checked };
        let configuration;
        try {
          configuration = await requestJson('/api/v1/admin/radioreference/session', {
            method: 'PUT', body: payload, timeoutMs: 15_000, signal: request.signal, page: false
          });
        } finally { payload.password = ''; }
        if (disposed || cancelled) return null;
        if (configuration?.account?.state !== 'VALID_PREMIUM') {
          const failure = new Error('RadioReference connection was not accepted.');
          failure.accountState = configuration?.account?.state;
          throw failure;
        }
        return configuration;
      }, { saving: 'Connecting to RadioReference…',
        onError: (error) => ({ state: 'error', message: connectionFailure(error) }),
        onSuccess: (configuration) => {
          if (disposed || cancelled) return;
          result = configuration;
          modal.setDirty(false);
          modal.close();
        }
      });
    });
  });
}
