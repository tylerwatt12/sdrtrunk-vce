'use strict';

function text(value, fallback = 'Unknown') {
  const normalized = value === null || value === undefined ? '' : String(value).trim();
  return normalized || fallback;
}

function compactNumber(value) {
  const count = Math.max(0, Number(value) || 0);
  return new Intl.NumberFormat(undefined, { notation: count >= 10_000 ? 'compact' : 'standard' }).format(count);
}

function timeLabel(value) {
  const timestamp = Number(value);
  return Number.isFinite(timestamp) && timestamp > 0 ? new Intl.DateTimeFormat(undefined, {
    hour: 'numeric', minute: '2-digit', second: '2-digit'
  }).format(timestamp) : 'Unknown';
}

function createTextButton(node, label, className = 'ui-button ui-button-secondary') {
  const button = node('button', className, label);
  button.type = 'button';
  return button;
}

function toggleButton(node, label, pressed = true) {
  const button = createTextButton(node, label, 'ui-button ui-button-secondary');
  button.setAttribute('aria-pressed', String(pressed));
  return button;
}

function appendFact(node, list, label, value) {
  const normalized = text(value, '');
  if (!normalized) return;
  list.append(node('dt', '', label), node('dd', '', normalized));
}

function createNetworkVisualizerUi(dependencies = {}) {
  const { node, iconGlyph, iconButton, entityRefHref, callbacks = {}, config } = dependencies;
  if (typeof node !== 'function' || typeof iconGlyph !== 'function' || typeof iconButton !== 'function') {
    throw new TypeError('Network Visualizer UI helpers are required.');
  }
  const filters = {
    affiliations: dependencies.initialFilters?.affiliations !== false,
    activity: dependencies.initialFilters?.activity !== false,
    quiet: dependencies.initialFilters?.quiet !== false
  };
  const emptyTitle = 'Listening — the map builds as activity arrives.';
  const emptyDetail = 'Only fresh live calls and confirmed affiliation observations appear here.';
  let renderedNodeCount = 0;
  let eventsReturnFocus = null;
  let inspectorReturnFocus = null;

  const layout = node('section', 'network-visualizer-layout');
  layout.setAttribute('aria-label', 'Network Visualizer');
  const toolbar = node('div', 'network-visualizer-toolbar');
  toolbar.setAttribute('role', 'toolbar');
  toolbar.setAttribute('aria-label', 'Network Visualizer controls');

  const brand = node('div', 'network-visualizer-brand');
  brand.append(iconGlyph('icon-network-visualizer'), node('strong', '', 'Network Visualizer'));
  const status = node('span', 'badge ui-pill state-stale network-visualizer-status', 'Connecting');
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');

  const search = node('label', 'ui-search network-visualizer-search');
  search.append(iconGlyph('icon-search'));
  const searchInput = node('input');
  searchInput.type = 'search';
  searchInput.placeholder = 'Find a system, talkgroup, or radio';
  searchInput.setAttribute('aria-label', 'Search retained network entities');
  search.append(searchInput);

  const filterGroup = node('div', 'network-visualizer-filter-group');
  filterGroup.setAttribute('role', 'group');
  filterGroup.setAttribute('aria-label', 'Display filters');
  const affiliationFilter = toggleButton(node, 'Affiliations', filters.affiliations);
  const activityFilter = toggleButton(node, 'Activity', filters.activity);
  const quietFilter = toggleButton(node, 'Quiet', filters.quiet);
  filterGroup.append(affiliationFilter, activityFilter, quietFilter);

  const actions = node('div', 'network-visualizer-actions');
  const cameraActions = node('div', 'network-visualizer-action-group');
  cameraActions.setAttribute('role', 'group');
  cameraActions.setAttribute('aria-label', 'Camera');
  const fit = createTextButton(node, 'Fit all');
  const focus = createTextButton(node, 'Focus');
  focus.disabled = true;
  cameraActions.append(fit, focus);

  const modeActions = node('div', 'network-visualizer-action-group');
  modeActions.setAttribute('role', 'group');
  modeActions.setAttribute('aria-label', 'View mode');
  const mode3d = toggleButton(node, '3D');
  const modeFlat = toggleButton(node, 'Flatten', false);
  modeActions.append(mode3d, modeFlat);

  const freeze = toggleButton(node, 'Freeze layout', false);
  const eventsToggle = toggleButton(node, 'Events', false);
  const clear = createTextButton(node, 'Clear map', 'ui-button ui-button-danger-quiet');
  const fullscreen = iconButton('icon-fullscreen', 'Enter fullscreen');
  const settingsButton = iconButton('icon-playback-controls', 'Density settings');
  settingsButton.setAttribute('popovertarget', 'network-visualizer-settings');
  actions.append(cameraActions, modeActions, freeze, eventsToggle, clear, fullscreen, settingsButton);
  toolbar.append(brand, status, search, filterGroup, actions);

  const stage = node('div', 'network-visualizer-stage');
  stage.tabIndex = 0;
  stage.setAttribute('aria-label', 'Interactive radio network canvas');
  stage.dataset.gap = 'false';
  stage.dataset.webgl = 'pending';
  const canvas = node('div', 'network-visualizer-canvas');

  const empty = node('div', 'network-visualizer-empty');
  empty.append(iconGlyph('icon-network-visualizer'), node('strong', '', emptyTitle), node('p', '', emptyDetail));

  const counts = node('div', 'network-visualizer-overlay network-visualizer-counts', 'Showing 0 of 0 retained radios');
  counts.setAttribute('role', 'status');
  counts.setAttribute('aria-live', 'polite');

  const offscreen = createTextButton(node, '',
    'network-visualizer-overlay network-visualizer-offscreen');
  offscreen.hidden = true;
  offscreen.setAttribute('aria-live', 'polite');
  offscreen.addEventListener('click', () => callbacks.onFit?.());

  const legend = node('div', 'network-visualizer-overlay network-visualizer-legend');
  legend.setAttribute('aria-label', 'Network Visualizer legend');
  const legendItem = (kind, label) => {
    const item = node('span', 'network-visualizer-legend-item');
    const mark = node('span', 'network-visualizer-legend-mark');
    mark.dataset.kind = kind;
    item.append(mark, node('span', '', label));
    return item;
  };
  const lock = node('span', 'network-visualizer-legend-item');
  lock.append(node('span', 'network-visualizer-legend-lock', '▧'), node('span', '', 'Encrypted'));
  legend.append(legendItem('affiliation', 'Affiliation'), legendItem('activity', 'Activity only'),
    legendItem('tx', 'Transmitting'), lock);

  const notice = node('div', 'network-visualizer-notice');
  notice.hidden = true;
  notice.setAttribute('role', 'status');
  notice.setAttribute('aria-live', 'polite');

  const inspector = node('aside', 'network-visualizer-inspector');
  inspector.hidden = true;
  inspector.setAttribute('aria-label', 'Selected network entity');
  const inspectorHeader = node('div', 'network-visualizer-panel-header');
  const inspectorHeading = node('div', 'network-visualizer-panel-heading');
  const inspectorTitle = node('h2', '', 'Selection');
  const inspectorSubtitle = node('p');
  inspectorHeading.append(inspectorTitle, inspectorSubtitle);
  const inspectorClose = iconButton('icon-close', 'Close inspector');
  inspectorHeader.append(inspectorHeading, inspectorClose);
  const inspectorBody = node('div', 'network-visualizer-panel-body');
  inspector.append(inspectorHeader, inspectorBody);

  const events = node('aside', 'network-visualizer-events');
  events.hidden = true;
  events.setAttribute('aria-label', 'Recent observed network events');
  const eventsHeader = node('div', 'network-visualizer-panel-header');
  const eventsHeading = node('div', 'network-visualizer-panel-heading');
  eventsHeading.append(node('h3', '', 'Observed activity'),
    node('p', '', 'Bounded to this live viewing session'));
  const eventsClose = iconButton('icon-close', 'Close event drawer');
  eventsHeader.append(eventsHeading, eventsClose);
  const eventList = node('ol', 'network-visualizer-event-list');
  events.append(eventsHeader, eventList);

  const settings = node('div', 'ui-popover network-visualizer-settings');
  settings.id = 'network-visualizer-settings';
  settings.setAttribute('popover', 'auto');
  settings.setAttribute('aria-label', 'Network Visualizer density settings');
  const settingsHeading = node('div', 'network-visualizer-panel-heading');
  settingsHeading.append(node('h3', '', 'Density'), node('p', '', 'Rendering limits never change receiver behavior.'));
  const settingsGrid = node('div', 'network-visualizer-settings-grid');
  const radioRangeLabel = node('label');
  const radioRangeOutput = node('output', '', String(config?.render?.softRadiosTotal || 1000));
  radioRangeLabel.append(node('span', '', 'Visible radio target'), radioRangeOutput);
  const radioRange = node('input');
  radioRange.type = 'range';
  radioRange.min = '100';
  radioRange.max = String(config?.render?.hardNodes || 1000);
  radioRange.step = '50';
  radioRange.value = String(config?.render?.softRadiosTotal || 1000);
  radioRangeLabel.append(radioRange);
  const labelRangeLabel = node('label');
  const labelRangeOutput = node('output', '', String(config?.render?.hardLabels || 80));
  labelRangeLabel.append(node('span', '', 'Maximum labels'), labelRangeOutput);
  const labelRange = node('input');
  labelRange.type = 'range';
  labelRange.min = '10';
  labelRange.max = String(config?.render?.hardLabels || 80);
  labelRange.step = '5';
  labelRange.value = String(config?.render?.hardLabels || 80);
  labelRangeLabel.append(labelRange);
  const unlock = createTextButton(node, 'Unlock saved layout');
  settingsGrid.append(radioRangeLabel, labelRangeLabel, unlock);
  settings.append(settingsHeading, settingsGrid);

  stage.append(canvas, empty, counts, offscreen, legend, notice, inspector, events, settings);
  layout.append(toolbar, stage);

  const notifyFilters = () => callbacks.onFilters?.({ ...filters });
  [[affiliationFilter, 'affiliations'], [activityFilter, 'activity'], [quietFilter, 'quiet']].forEach(([button, key]) => {
    button.addEventListener('click', () => {
      filters[key] = !filters[key];
      button.setAttribute('aria-pressed', String(filters[key]));
      notifyFilters();
    });
  });
  searchInput.addEventListener('input', () => callbacks.onSearch?.(searchInput.value));
  searchInput.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    callbacks.onSearchCommit?.(searchInput.value);
  });
  fit.addEventListener('click', () => callbacks.onFit?.());
  focus.addEventListener('click', () => callbacks.onFocus?.());
  mode3d.addEventListener('click', () => callbacks.onMode?.('3d'));
  modeFlat.addEventListener('click', () => callbacks.onMode?.('flat'));
  freeze.addEventListener('click', () => callbacks.onFreeze?.(freeze.getAttribute('aria-pressed') !== 'true'));
  eventsToggle.addEventListener('click', () => setEventsOpen(events.hidden));
  eventsClose.addEventListener('click', () => setEventsOpen(false));
  inspectorClose.addEventListener('click', () => callbacks.onSelectionClear?.());
  clear.addEventListener('click', () => callbacks.onClear?.());
  fullscreen.addEventListener('click', async () => {
    try {
      if (document.fullscreenElement === layout) await document.exitFullscreen();
      else await layout.requestFullscreen();
    } catch (error) {
      showNotice('Fullscreen is not available in this browser.');
    }
  });
  const fullscreenChanged = () => {
    const active = document.fullscreenElement === layout;
    fullscreen.setAttribute('aria-label', active ? 'Exit fullscreen' : 'Enter fullscreen');
    fullscreen.title = active ? 'Exit fullscreen' : 'Enter fullscreen';
    callbacks.onResize?.();
  };
  document.addEventListener('fullscreenchange', fullscreenChanged);
  radioRange.addEventListener('input', () => {
    radioRangeOutput.textContent = radioRange.value;
    callbacks.onDensity?.({ softRadiosTotal: Number(radioRange.value), hardLabels: Number(labelRange.value) });
  });
  labelRange.addEventListener('input', () => {
    labelRangeOutput.textContent = labelRange.value;
    callbacks.onDensity?.({ softRadiosTotal: Number(radioRange.value), hardLabels: Number(labelRange.value) });
  });
  unlock.addEventListener('click', () => callbacks.onUnlock?.());

  function setTransport(state, detail = '') {
    const value = String(state || '').toLowerCase();
    status.textContent = value === 'open' ? 'Live' : value === 'gap' ? 'Live gap' :
      value === 'error' ? 'Reconnecting' : 'Connecting';
    status.className = `badge ui-pill network-visualizer-status ${value === 'open' ? 'state-current' :
      value === 'gap' ? 'state-warning' : 'state-stale'}`;
    status.title = detail;
  }

  function setCounts(value = {}) {
    const visible = Math.max(0, Number(value.visibleRadios ?? value.renderedRadios) || 0);
    const retained = Math.max(visible, Number(value.retainedRadios) || 0);
    const suppressedActive = Math.max(0, Number(value.suppressedActiveRadios) || 0);
    const suppressedLegs = Math.max(0, Number(value.suppressedActiveCallLegs) || 0);
    const nextText = `Showing ${compactNumber(visible)} of ${compactNumber(retained)} retained radios` +
      (suppressedActive ? ` · +${compactNumber(suppressedActive)} active radios aggregated` : '') +
      (suppressedLegs ? ` · +${compactNumber(suppressedLegs)} active call legs aggregated` : '');
    if (counts.textContent !== nextText) counts.textContent = nextText;
    renderedNodeCount = Math.max(0, Number(value.renderedNodes ?? value.visibleNodes) || 0);
    empty.hidden = stage.dataset.webgl === 'failed' ? false : renderedNodeCount > 0;
  }

  function setOffscreenActivity(value = 0) {
    const active = Math.max(0, Math.trunc(Number(value) || 0));
    offscreen.hidden = active === 0;
    offscreen.textContent = active === 1 ? '1 active entity offscreen · Fit' :
      `${compactNumber(active)} active entities offscreen · Fit`;
  }

  function setMode(mode) {
    const flat = mode === 'flat' || mode === '2d';
    mode3d.setAttribute('aria-pressed', String(!flat));
    modeFlat.setAttribute('aria-pressed', String(flat));
  }

  function setFrozen(value) {
    const frozen = Boolean(value);
    freeze.setAttribute('aria-pressed', String(frozen));
    freeze.textContent = frozen ? 'Layout frozen' : 'Freeze layout';
  }

  function setEventsOpen(open) {
    const selected = Boolean(open);
    if (selected && events.hidden) eventsReturnFocus = document.activeElement;
    const restoreFocus = !selected && !events.hidden && events.contains(document.activeElement);
    events.hidden = !selected;
    eventsToggle.setAttribute('aria-pressed', String(selected));
    if (selected) eventsClose.focus();
    else {
      if (restoreFocus) {
        const target = eventsReturnFocus?.isConnected && typeof eventsReturnFocus.focus === 'function' ?
          eventsReturnFocus : eventsToggle;
        target.focus();
      }
      eventsReturnFocus = null;
    }
  }

  function showNotice(message = '', gap = false) {
    const normalized = text(message, '');
    notice.hidden = !normalized;
    notice.textContent = normalized;
    stage.dataset.gap = String(Boolean(gap));
  }

  function setSelection(entity, transitions = []) {
    if (!entity) {
      const restoreFocus = !inspector.hidden && inspector.contains(document.activeElement);
      inspector.hidden = true;
      focus.disabled = true;
      if (restoreFocus) {
        const target = inspectorReturnFocus?.isConnected && typeof inspectorReturnFocus.focus === 'function' ?
          inspectorReturnFocus : stage;
        target.focus();
      }
      inspectorReturnFocus = null;
      return;
    }
    if (inspector.hidden) inspectorReturnFocus = document.activeElement;
    inspector.hidden = false;
    focus.disabled = false;
    inspectorTitle.textContent = text(entity.label || entity.name || entity.displayId, 'Unlabeled entity');
    inspectorSubtitle.textContent = text(entity.kind, 'entity').replace(/_/g, ' ');
    const facts = node('dl', 'network-visualizer-facts');
    appendFact(node, facts, 'ID', entity.displayId || entity.nativeId || entity.identityKey);
    appendFact(node, facts, 'Protocol', entity.protocol);
    appendFact(node, facts, 'System', entity.systemName || entity.radioSystemKey);
    appendFact(node, facts, 'WACN · System', entity.wacn && entity.systemId ? `${entity.wacn}:${entity.systemId}` : '');
    appendFact(node, facts, 'Observed at', entity.siteName || entity.channelName || entity.configurationId);
    appendFact(node, facts, 'RFSS · Site', entity.rfssId && entity.siteId ?
      `${entity.rfssId} / ${entity.siteId}` : '');
    appendFact(node, facts, 'NAC', entity.nac);
    appendFact(node, facts, 'Timeslot', entity.timeslot);
    appendFact(node, facts, 'Configuration', entity.configurationId);
    appendFact(node, facts, 'Affiliation', entity.affiliationCapability === 'not_applicable' ? '' :
      (entity.affiliationLabel || entity.affiliation?.groupLabel ||
        (entity.affiliationCapability === 'unsupported' ? 'Unsupported by this live feed' :
          (entity.affiliationAmbiguous ? 'Ambiguous across observation scopes' : 'Unknown'))));
    appendFact(node, facts, 'Evidence', text(entity.affiliationEvidenceType, '').replace(/_/g, ' '));
    appendFact(node, facts, 'Affiliation seen', timeLabel(entity.affiliationObservedAtMs || entity.affiliation?.observedAtMs));
    appendFact(node, facts, 'Current TX target', entity.txTargetLabel || entity.currentTx?.groupLabel || 'Not transmitting');
    if (entity.txContinuityUncertain) appendFact(node, facts, 'TX continuity', 'Current after a live gap; start unknown');
    appendFact(node, facts, 'Last activity', timeLabel(entity.lastMeaningfulAtMs || entity.lastObservedAtMs));
    if (entity.encrypted) appendFact(node, facts, 'Encryption', 'Observed on current/recent transmission');
    const panelActions = node('div', 'network-visualizer-panel-actions');
    const pin = toggleButton(node, entity.pinned ? 'Pinned' : 'Pin', Boolean(entity.pinned));
    pin.addEventListener('click', () => callbacks.onPin?.(entity.key, !entity.pinned));
    panelActions.append(pin);
    const reference = entity.entityRef || entity.entity_ref;
    if (reference && typeof entityRefHref === 'function') {
      const target = entityRefHref(reference);
      if (target) {
        const details = node('a', 'ui-button ui-button-secondary', 'Open details');
        details.href = target;
        panelActions.append(details);
      }
    }
    inspectorBody.replaceChildren(facts, panelActions);
    if (Array.isArray(transitions) && transitions.length) {
      const recent = node('div', 'network-visualizer-panel-body');
      recent.append(node('strong', '', 'Recent observed transitions'));
      const list = node('ol', 'network-visualizer-event-list');
      transitions.slice(-5).reverse().forEach((transition) => {
        const item = node('li', 'network-visualizer-event');
        item.append(node('span', 'network-visualizer-event-kind', 'Affiliation'),
          node('span', 'network-visualizer-event-detail',
            `${text(transition.fromLabel, 'Unknown')} → ${text(transition.toLabel, 'Unknown')}`),
          node('time', '', timeLabel(transition.observedAtMs)));
        list.append(item);
      });
      recent.append(list);
      inspectorBody.append(recent);
    }
  }

  function setEvents(items = []) {
    const values = Array.isArray(items) ? items.slice(-100).reverse() : [];
    if (!values.length) {
      eventList.replaceChildren(node('li', 'network-visualizer-event', 'No observed transitions yet.'));
      return;
    }
    eventList.replaceChildren(...values.map((event) => {
      const item = node('li', 'network-visualizer-event');
      const kind = ['affiliation_change', 'observed_affiliation_change'].includes(event.kind) ?
        'Observed affiliation change' :
        text(event.label || event.kind, 'Observation').replace(/_/g, ' ');
      const detail = event.detail || [event.radioLabel, event.fromLabel && event.toLabel ?
        `${event.fromLabel} → ${event.toLabel}` : event.groupLabel].filter(Boolean).join(' · ');
      item.append(node('span', 'network-visualizer-event-kind', kind),
        node('span', 'network-visualizer-event-detail', text(detail, 'Live observation')),
        node('time', '', timeLabel(event.observedAtMs)));
      return item;
    }));
  }

  function setWebglState(value, detail = '') {
    stage.dataset.webgl = value;
    if (value === 'failed') {
      empty.hidden = false;
      empty.querySelector('strong').textContent = 'WebGL is unavailable';
      empty.querySelector('p').textContent = detail || 'The live event list and inspector remain available.';
      setEventsOpen(true);
    } else if (value === 'ready') {
      empty.querySelector('strong').textContent = emptyTitle;
      empty.querySelector('p').textContent = emptyDetail;
      empty.hidden = renderedNodeCount > 0;
    }
  }

  return {
    element: layout,
    stage,
    canvas,
    searchInput,
    setTransport,
    setCounts,
    setOffscreenActivity,
    setMode,
    setFrozen,
    setEventsOpen,
    showNotice,
    setSelection,
    setEvents,
    setWebglState,
    close() {
      document.removeEventListener('fullscreenchange', fullscreenChanged);
      if (document.fullscreenElement === layout) void document.exitFullscreen().catch(() => {});
      settings.hidePopover?.();
    }
  };
}

export { createNetworkVisualizerUi };
