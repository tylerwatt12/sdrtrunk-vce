const ICON_CONTROL_SELECTOR = [
  '.ui-icon-button', '.icon-button', '.ui-header-indicator', '.playback-command',
  '.playback-control-menu > summary', '.channels-tab-close', '.ui-segmented-option-icon', '[data-ui-hint]'
].join(', ');

const hintDetails = new WeakMap();

// Keep structured hints in the shared hover/focus lifecycle without interpreting HTML.
export function setIconHintDetails(control, { heading, columns, rows }) {
  hintDetails.set(control, { heading, columns, rows });
}

export function installIconHints(root = document) {
  const hint = root.createElement('div');
  hint.className = 'ui-icon-hint';
  hint.setAttribute('role', 'tooltip');
  hint.setAttribute('popover', 'manual');
  root.body.append(hint);
  let active = null;
  let timer = null;
  let hideTimer = null;

  const hide = () => {
    clearTimeout(timer);
    clearTimeout(hideTimer);
    timer = null;
    hideTimer = null;
    active = null;
    if (hint.matches(':popover-open')) hint.hidePopover();
  };
  const scheduleHide = () => {
    clearTimeout(hideTimer);
    hideTimer = window.setTimeout(hide, 120);
  };
  const withinHint = (target) => target instanceof Node && hint.contains(target);
  const render = (control, label) => {
    const details = hintDetails.get(control);
    hint.classList.toggle('ui-icon-hint-details', Boolean(details));
    hint.replaceChildren();
    if (!details) {
      hint.textContent = label;
      return;
    }
    const heading = root.createElement('div');
    heading.className = 'ui-icon-hint-heading';
    heading.textContent = details.heading;
    const table = root.createElement('table');
    table.className = 'ui-icon-hint-table';
    const head = table.createTHead().insertRow();
    details.columns.forEach((label) => {
      const cell = root.createElement('th');
      cell.scope = 'col';
      cell.textContent = label;
      head.append(cell);
    });
    const body = table.createTBody();
    details.rows.forEach((values) => {
      const row = body.insertRow();
      values.forEach((value) => { row.insertCell().textContent = value; });
    });
    hint.append(heading, table);
  };
  const position = (control) => {
    const rect = control.getBoundingClientRect();
    const width = hint.offsetWidth;
    const height = hint.offsetHeight;
    const gap = 8;
    const left = Math.max(gap, Math.min(rect.left + rect.width / 2 - width / 2,
      window.innerWidth - width - gap));
    const below = rect.bottom + gap + height <= window.innerHeight - gap;
    const top = below ? rect.bottom + gap : Math.max(gap, rect.top - height - gap);
    hint.style.left = `${Math.round(left)}px`;
    hint.style.top = `${Math.round(top)}px`;
  };
  const show = (control) => {
    if (active !== control || !control.isConnected) return;
    const title = control.getAttribute('title');
    if (title) {
      control.dataset.uiHint = title;
      control.removeAttribute('title');
    }
    const label = control.dataset.uiHint || control.getAttribute('aria-label');
    if (!label) return;
    render(control, label);
    if (!hint.matches(':popover-open')) hint.showPopover();
    position(control);
  };
  const controlFor = (target) => target instanceof Element ? target.closest(ICON_CONTROL_SELECTOR) : null;
  const activate = (control, delay) => {
    if (!control || control === active) return;
    hide();
    active = control;
    if (delay) timer = window.setTimeout(() => show(control), delay);
    else show(control);
  };

  root.addEventListener('pointerover', (event) => {
    if (withinHint(event.target) || active?.contains(event.target)) {
      clearTimeout(hideTimer);
      hideTimer = null;
    }
    if (!withinHint(event.target)) activate(controlFor(event.target), 350);
  });
  root.addEventListener('pointermove', (event) => {
    if (!active) activate(controlFor(event.target), 350);
  });
  root.addEventListener('pointerout', (event) => {
    if (active && (active.contains(event.target) || withinHint(event.target)) &&
        !active.contains(event.relatedTarget) && !withinHint(event.relatedTarget)) scheduleHide();
  });
  root.addEventListener('focusin', (event) => {
    const control = controlFor(event.target);
    if (!control?.matches(':focus-visible')) return;
    if (control === active) {
      clearTimeout(timer);
      timer = null;
      show(control);
      return;
    }
    activate(control, 0);
  });
  root.addEventListener('focusout', (event) => {
    if (active && active.contains(event.target) && !active.contains(event.relatedTarget)) hide();
  });
  root.addEventListener('click', (event) => {
    const control = controlFor(event.target);
    if (control !== active || !hint.matches(':popover-open')) return;
    window.queueMicrotask(() => {
      if (active === control && hint.matches(':popover-open')) show(control);
    });
  });
  root.addEventListener('pointerdown', (event) => { if (!withinHint(event.target)) hide(); });
  root.addEventListener('scroll', (event) => {
    if (withinHint(event.target)) return;
    if (active === root.activeElement && hint.matches(':popover-open')) position(active);
    else hide();
  }, true);
  root.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      hide();
      return;
    }
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey ||
        active !== root.activeElement || !hintDetails.has(active) || !hint.matches(':popover-open')) return;
    const maximum = hint.scrollHeight - hint.clientHeight;
    if (maximum <= 0) return;
    const line = hint.querySelector('tbody tr')?.getBoundingClientRect().height || hint.clientHeight / 8;
    const scroll = {
      ArrowUp: hint.scrollTop - line,
      ArrowDown: hint.scrollTop + line,
      PageUp: hint.scrollTop - hint.clientHeight,
      PageDown: hint.scrollTop + hint.clientHeight,
      Home: 0,
      End: maximum
    }[event.key];
    if (scroll === undefined) return;
    event.preventDefault();
    hint.scrollTop = Math.max(0, Math.min(maximum, scroll));
  });
  window.addEventListener('resize', hide);
}
