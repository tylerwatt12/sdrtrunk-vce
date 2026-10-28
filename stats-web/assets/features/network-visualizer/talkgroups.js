import { createActionWorkflow } from '../../core/action-workflows.js?v=1';

// This panel is imported on first use. It shares the Actions panel's layout,
// while its bounded reads stay independent of the live scene and its counters.
export function createP25TalkgroupsPanel({ node, iconButton, openReadOnlyModal,
  loadTalkgroups, renderTalkgroup, formatCount, host, layout, toggle, shortViewport }) {
  const panel = node('aside', 'network-visualizer-panel network-visualizer-talkgroups ui-surface');
  panel.hidden = true;
  const header = node('header', 'network-visualizer-panel-header');
  const heading = node('div', 'network-visualizer-panel-heading');
  const scope = node('p');
  heading.append(node('h2', '', 'Top Talkgroups'), scope);
  const closeButton = iconButton('icon-close', 'Close top talkgroups');
  header.append(heading, closeButton);
  const list = node('ol', 'network-visualizer-panel-list');
  list.setAttribute('aria-label', 'Talkgroups by total calls');
  const status = node('div', 'network-visualizer-action');
  const feedback = node('div');
  const retry = node('button', 'ui-button ui-button-secondary', 'Retry');
  retry.type = 'button';
  retry.hidden = true;
  status.append(feedback, retry);
  panel.append(header, status, list);
  host.append(panel);
  const workflow = createActionWorkflow({ surface: list, feedback, controls: [retry] });
  let system = null, opened = false, disposed = false, modal = null, introduction = null;
  let timer = 0, generation = 0, request = null, reloadPending = false;

  const canLoad = () => opened && !disposed && !document.hidden && Boolean(system?.key);
  function cancel() {
    generation++;
    window.clearTimeout(timer);
    timer = 0;
    request?.abort();
    request = null;
    reloadPending = false;
  }

  async function refresh() {
    if (!canLoad()) return;
    if (workflow.isBusy()) { reloadPending = true; return; }
    window.clearTimeout(timer);
    const version = ++generation, key = system.key;
    request = new AbortController();
    retry.hidden = true;
    try {
      await workflow.run(async () => {
        const page = await loadTalkgroups(key, { signal: request.signal });
        if (version !== generation || !canLoad()) return;
        const items = (page.rows || []).slice(0, 20).filter(row => Number(row.logical_call_count) > 0)
          .map(row => {
            const item = node('li', 'network-visualizer-action');
            const rowHeading = node('div', 'network-visualizer-action-heading');
            const count = node('span', 'ui-pill', formatCount(row.logical_call_count));
            count.setAttribute('aria-label', `${formatCount(row.logical_call_count)} total calls`);
            rowHeading.append(renderTalkgroup(row), count);
            item.append(rowHeading);
            return item;
          });
        list.replaceChildren(...items);
        status.hidden = items.length > 0;
        workflow.showFeedback('status', items.length ? '' : 'No call totals are available for this system yet.');
      }, { pending: list.childElementCount ? '' : 'Loading talkgroups…', success: null,
        onError: () => {
          if (version !== generation || !canLoad()) return;
          status.hidden = false;
          retry.hidden = false;
          workflow.showFeedback('error', 'Talkgroups could not be loaded. Select Retry to try again.');
        } });
    } catch (_) {
      // The shared workflow presents failures. Cancellation never changes scope.
    } finally {
      if (version === generation) {
        request = null;
        if (canLoad() && retry.hidden) timer = window.setTimeout(refresh, 10_000);
      }
      if (reloadPending) { reloadPending = false; void refresh(); }
    }
  }

  function renderScope() {
    scope.textContent = system ? `${system.name || system.identity || system.label} · Top 20 by total calls` :
      'Select a system to see its call totals.';
    if (introduction) introduction.textContent = scope.textContent;
    if (!system) {
      list.replaceChildren();
      status.hidden = true;
    }
  }

  function setSystem(value) {
    const changed = system?.key !== value?.key;
    system = value || null;
    renderScope();
    if (!changed) return;
    cancel();
    list.replaceChildren();
    status.hidden = !system;
    workflow.showFeedback('status', '');
    if (canLoad()) void refresh();
  }

  async function ensureSheet() {
    if (!opened || modal || disposed || !shortViewport.matches ||
        document.body.classList.contains('modal-open')) return;
    const body = node('div', 'network-visualizer-events-sheet');
    introduction = node('p', 'modal-introduction', scope.textContent);
    panel.classList.remove('ui-surface');
    body.append(introduction, panel);
    modal = openReadOnlyModal('Top Talkgroups', body, {
      id: 'p25-visualizer-talkgroups', className: 'network-visualizer-events-modal',
      onClose: () => {
        modal = null;
        introduction = null;
        panel.classList.add('ui-surface');
        if (disposed) panel.remove();
        else host.append(panel);
        hide();
      }
    });
    if (!modal) { panel.classList.add('ui-surface'); host.append(panel); return; }
    if (document.fullscreenElement === layout) layout.append(modal.state.backdrop);
    const current = modal;
    if (current.ready && !await current.ready) return;
    if (modal === current) current.focus?.(list.querySelector('a') || current.dialog);
  }

  function hide() {
    if (modal) { modal.close(); return; }
    if (!opened) return;
    opened = false;
    cancel();
    panel.hidden = true;
    toggle.setAttribute('aria-pressed', 'false');
    if (!disposed) toggle.focus({ preventScroll: true });
  }

  function show(value) {
    opened = true;
    panel.hidden = false;
    toggle.setAttribute('aria-pressed', 'true');
    toggle.focus({ preventScroll: true });
    setSystem(value);
    renderScope();
    status.hidden = !system || list.childElementCount > 0;
    if (canLoad() && !request) void refresh();
    void ensureSheet();
  }

  function onVisibility() {
    cancel();
    if (canLoad()) void refresh();
  }
  closeButton.addEventListener('click', hide);
  retry.addEventListener('click', refresh);
  shortViewport.addEventListener('change', ensureSheet);
  document.addEventListener('visibilitychange', onVisibility);
  return { show, hide, setSystem, isOpen: () => opened,
    close() {
      disposed = true;
      hide();
      shortViewport.removeEventListener('change', ensureSheet);
      document.removeEventListener('visibilitychange', onVisibility);
      panel.remove();
    }
  };
}
