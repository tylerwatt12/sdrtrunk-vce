// Reuse map: the same inline feedback and scoped control locking serve catalog
// actions and confirmation dialogs; navigation and background polling stay independent.
const pendingSurfaces = new WeakMap();

export function actionFailureMessage(error, fallback = 'The action could not be completed. Try again.') {
  if (error?.outcomeUnknown || error?.code === 'request_timeout' || error?.code === 'invalid_response' ||
      error?.name === 'TypeError' || error?.name === 'AbortError') {
    return 'The receiver did not confirm the result. Check the current state before trying again.';
  }
  return error?.message || fallback;
}

export function createActionWorkflow({ surface, feedback, controls = () =>
  surface.querySelectorAll('button,input,select,textarea'), modal = null, renderFeedback = null }) {
  const show = (state, message, error = null) => {
    if (renderFeedback) { renderFeedback(state, message, error); return; }
    if (!feedback) return;
    feedback.classList.remove('ui-feedback', 'ui-feedback-loading', 'ui-feedback-error', 'ui-form-state');
    feedback.textContent = message;
    feedback.hidden = !message;
    feedback.setAttribute('role', state === 'error' ? 'alert' : 'status');
    feedback.setAttribute('aria-live', state === 'error' ? 'assertive' : 'polite');
    if (message) {
      feedback.classList.add(state === 'status' ? 'ui-form-state' : 'ui-feedback');
      if (state !== 'status') feedback.classList.add(`ui-feedback-${state}`);
    }
  };
  const run = async (operation, { pending = 'Working…', success = '', failure,
    onSuccess = null, onError = null } = {}) => {
    if (pendingSurfaces.has(surface)) return undefined;
    const locked = new Map();
    const lockControls = () => {
      [...(typeof controls === 'function' ? controls() : controls)]
        .filter((control) => typeof control.disabled === 'boolean').forEach((control) => {
          if (!locked.has(control)) locked.set(control, control.disabled);
          control.disabled = true;
        });
    };
    pendingSurfaces.set(surface, { lockControls });
    const document = surface.ownerDocument;
    const focused = document?.activeElement;
    const previousBusy = surface.getAttribute('aria-busy');
    lockControls();
    surface.setAttribute('aria-busy', 'true');
    modal?.setBusy(true);
    show('loading', pending);
    let committed = false;
    try {
      const result = await operation();
      committed = true;
      if (onSuccess) await onSuccess(result);
      if (success != null) show('status', success);
      return result;
    } catch (error) {
      const message = committed ?
        'The action completed, but the view could not be updated. Refresh to see the current state.' :
        actionFailureMessage(error, failure);
      show('error', message, error);
      if (onError) await onError(error, message);
      throw error;
    } finally {
      locked.forEach((disabled, control) => { control.disabled = disabled; });
      if (previousBusy == null) surface.removeAttribute('aria-busy');
      else surface.setAttribute('aria-busy', previousBusy);
      modal?.setBusy(false);
      pendingSurfaces.delete(surface);
      if (focused?.isConnected && !focused.disabled && document.activeElement === document.body &&
          locked.has(focused)) focused.focus({ preventScroll: true });
    }
  };
  return { run, showFeedback: show, isBusy: () => pendingSurfaces.has(surface),
    refreshControls: () => pendingSurfaces.get(surface)?.lockControls() };
}
