// This panel is imported on first use and reads the visualizer's session activity.
export function createP25TalkgroupsPanel({ node, iconButton, openReadOnlyModal,
  getTalkgroups, renderTalkgroup, formatCount, host, layout, toggle, shortViewport }) {
  const panel = node('aside', 'network-visualizer-panel network-visualizer-talkgroups ui-surface');
  panel.hidden = true;
  const header = node('header', 'network-visualizer-panel-header');
  const heading = node('div', 'network-visualizer-panel-heading');
  const scope = node('p');
  heading.append(node('h2', '', 'Top Talkgroups'), scope);
  const closeButton = iconButton('icon-close', 'Close top talkgroups');
  header.append(heading, closeButton);
  const list = node('ol', 'network-visualizer-panel-list');
  list.setAttribute('aria-label', 'Talkgroups by calls and grants since this page opened');
  const status = node('div', 'network-visualizer-action');
  panel.append(header, status, list);
  host.append(panel);
  let system = null, opened = false, disposed = false, modal = null, introduction = null;
  let signature = '';

  function refresh() {
    if (!opened || disposed || document.hidden) return;
    const groups = system?.key ? getTalkgroups(system.key).slice(0, 20) : [];
    const nextSignature = JSON.stringify([system?.key, system?.name, system?.identity, system?.label,
      groups.map(group => [group.key, group.systemKey, group.identityKey, group.nativeId, group.label, group.callCount])]);
    if (signature === nextSignature) return;
    const focusedKey = list.contains(document.activeElement) ?
      document.activeElement.closest('[data-talkgroup-key]')?.dataset.talkgroupKey : null;
    signature = nextSignature;
    scope.textContent = system ?
      `${system.name || system.identity || system.label} · Calls and grants since this page opened · Top 20` :
      'Select a system to see its call activity.';
    if (introduction) introduction.textContent = scope.textContent;
    list.replaceChildren(...groups.map(group => {
      const item = node('li', 'network-visualizer-action');
      item.dataset.talkgroupKey = group.key;
      const rowHeading = node('div', 'network-visualizer-action-heading');
      const count = node('span', 'ui-pill', formatCount(group.callCount));
      count.setAttribute('aria-label', `${formatCount(group.callCount)} calls and grants since this page opened`);
      rowHeading.append(renderTalkgroup(group, system), count);
      item.append(rowHeading);
      return item;
    }));
    if (focusedKey) [...list.children].find(item => item.dataset.talkgroupKey === focusedKey)
      ?.querySelector('a')?.focus({ preventScroll: true });
    status.hidden = !system || groups.length > 0;
    status.textContent = system && !groups.length ? 'Waiting for call activity in this system.' : '';
  }

  function setSystem(value) {
    system = value || null;
    refresh();
  }

  async function ensureSheet() {
    if (!opened || modal || disposed || document.hidden || !shortViewport.matches ||
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
    panel.hidden = true;
    toggle.setAttribute('aria-pressed', 'false');
    if (!disposed) toggle.focus({ preventScroll: true });
  }

  function show(value) {
    if (disposed) return;
    opened = true;
    panel.hidden = false;
    toggle.setAttribute('aria-pressed', 'true');
    toggle.focus({ preventScroll: true });
    setSystem(value);
    void ensureSheet();
  }

  function onVisibility() {
    refresh();
    if (!document.hidden) void ensureSheet();
  }
  closeButton.addEventListener('click', hide);
  shortViewport.addEventListener('change', ensureSheet);
  document.addEventListener('visibilitychange', onVisibility);
  return { show, hide, setSystem, refresh, isOpen: () => opened,
    close() {
      disposed = true;
      hide();
      shortViewport.removeEventListener('change', ensureSheet);
      document.removeEventListener('visibilitychange', onVisibility);
      panel.remove();
    }
  };
}
