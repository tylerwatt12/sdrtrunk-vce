export const DEFAULT_CALL_DISPLAY_HANGTIME_SECONDS = 5;
export const MAXIMUM_CALL_DISPLAY_HANGTIME_SECONDS = 60;

// Playback owns one completion deadline. Presentations read it without extending it.
export function createCallDisplayRetention({
  getHangtimeSeconds = () => DEFAULT_CALL_DISPLAY_HANGTIME_SECONDS,
  onChange = () => {}, now = () => Date.now(),
  setTimeout = (callback, delay) => window.setTimeout(callback, delay),
  clearTimeout = (timer) => window.clearTimeout(timer)
} = {}) {
  let call = null;
  let endedAt = null;
  let deadline = 0;
  let timer = null;
  const cancelTimer = () => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  };
  const clear = () => {
    const changed = call !== null;
    cancelTimer(); call = null; endedAt = null; deadline = 0;
    if (changed) onChange();
  };
  const schedule = () => {
    cancelTimer();
    if (!call) return;
    const remaining = deadline - now();
    if (remaining <= 0) { clear(); return; }
    timer = setTimeout(schedule, remaining);
  };
  const refresh = () => {
    if (!call) return;
    if (deadline <= now()) { clear(); return; }
    deadline = endedAt + getHangtimeSeconds() * 1000;
    if (deadline <= now()) { clear(); return; }
    schedule(); onChange();
  };
  return {
    show(value, completedAt = now()) {
      cancelTimer();
      call = value || null;
      endedAt = call ? completedAt : null;
      deadline = call ? endedAt + getHangtimeSeconds() * 1000 : 0;
      if (call && deadline <= now()) { call = null; endedAt = null; deadline = 0; }
      schedule(); onChange();
    },
    clear, refresh,
    current: () => call && now() < deadline ? call : null,
    get endedAt() { return endedAt; },
    get deadline() { return deadline; }
  };
}
