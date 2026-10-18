// One page owns pending selections. Browsing Activity never looks up recordings until Play is pressed.
export function createActivityCallPlayback({ node, iconButton, setIconButton, requestJson, playback,
  canPlay, isCurrent, signal }) {
  const rows = new Map();
  let selection = 0;
  let pending = null;
  let closed = false;
  const paint = state => {
    if (!state.controls) return;
    const { button, feedback } = state.controls;
    setIconButton(button, state.busy ? 'icon-more' : 'icon-play',
      state.busy ? 'Loading recording…' : 'Play recording');
    button.disabled = state.busy;
    button.setAttribute('aria-busy', String(state.busy));
    feedback.textContent = state.message;
    feedback.hidden = !state.message;
  };
  const play = async (row, state) => {
    if (state.busy || closed || !isCurrent() || !canPlay()) return;
    rows.set(String(row.id), state);
    const intent = playback.viewState().intentRevision;
    const chosen = ++selection;
    pending?.abort();
    const controller = new AbortController();
    pending = controller;
    const abort = () => controller.abort();
    if (signal?.aborted) abort();
    else signal?.addEventListener('abort', abort, { once: true });
    state.busy = true;
    state.message = '';
    paint(state);
    try {
      const data = await requestJson(`/api/v1/call-relations/activity/${encodeURIComponent(row.id)}`,
        { signal: controller.signal, page: false });
      if (controller.signal.aborted || closed || chosen !== selection || !isCurrent() || !canPlay() ||
          playback.viewState().intentRevision !== intent) return;
      const unique = new Map();
      for (const recording of data.recordings || []) {
        const id = String(recording?.id || '');
        if (/^[1-9]\d{0,18}$/.test(id) && !unique.has(id)) unique.set(id, recording);
      }
      const recordings = [...unique.values()].sort((left, right) =>
        (Number(left.start_ms) || 0) - (Number(right.start_ms) || 0) ||
        String(left.id).localeCompare(String(right.id), 'en', { numeric: true }));
      if (!recordings.length) {
        state.message = 'No recording available.';
        return;
      }
      playback.play(recordings[0], 'once');
      recordings.slice(1).forEach(recording => playback.play(recording, 'queue'));
    } catch (error) {
      if (!controller.signal.aborted && !closed && chosen === selection && isCurrent()) {
        state.message = 'Recording could not be loaded. Try again.';
      }
    } finally {
      signal?.removeEventListener('abort', abort);
      if (pending === controller) pending = null;
      state.busy = false;
      paint(state);
      if (!state.message && rows.get(String(row.id)) === state) rows.delete(String(row.id));
    }
  };
  return {
    render(row) {
      if (row.has_recording !== true || !row.has_call_source || !canPlay()) return null;
      const id = String(row.id);
      let state = rows.get(id);
      if (!state) state = { busy: false, message: '', controls: null };
      const button = iconButton('icon-play', 'Play recording',
        'ui-button ui-button-secondary ui-icon-button ui-icon-button-compact');
      const feedback = node('span', 'activity-call-feedback ui-muted');
      feedback.setAttribute('role', 'status');
      button.addEventListener('click', () => void play(row, state));
      state.controls = { button, feedback };
      paint(state);
      return state.controls;
    },
    close() {
      closed = true;
      selection++;
      pending?.abort();
      rows.clear();
    }
  };
}
