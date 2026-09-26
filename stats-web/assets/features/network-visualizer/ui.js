'use strict';

function text(value, fallback = 'Unknown') {
  const normalized = value === null || value === undefined ? '' : String(value).trim();
  return normalized || fallback;
}

function timeLabel(value) {
  const timestamp = Number(value);
  return Number.isFinite(timestamp) && timestamp > 0 ? new Intl.DateTimeFormat(undefined, {
    hour: 'numeric', minute: '2-digit', second: '2-digit'
  }).format(timestamp) : 'Unknown';
}

const ROUTINE_ACTIVITY_KINDS = new Set([
  'affiliation_observed',
  'affiliation_observed_after_gap',
  'explicit_presence_remove',
  'signal_busy',
  'signal_check',
  'signal_denial',
  'signal_page',
  'transmission_end',
  'transmission_observed_after_gap',
  'transmission_observed_after_uncertainty',
  'transmission_start'
]);

function activityEventId(event, index = 0) {
  return String(event?.id || `${event?.kind || 'event'}:${event?.observedAtMs || 0}:${index}`);
}

function activityEventDetail(event = {}) {
  const identity = [event.radioLabel, event.fromLabel && event.toLabel ?
    `${event.fromLabel} → ${event.toLabel}` : event.groupLabel].filter(Boolean).join(' · ');
  const detail = text(event.detail, '');
  return text([identity, detail].filter(Boolean).join(' — '), 'Live observation');
}

function activityEventLabel(event = {}) {
  const kind = String(event.kind || '');
  if (kind === 'transmission_end') {
    return event.certainty === 'confirmed' ? 'Grant ended' :
      text(event.label, 'Grant activity became uncertain');
  }
  const labels = {
    affiliation_change: 'Observed affiliation change',
    observed_affiliation_change: 'Observed affiliation change',
    affiliation_observed: 'Join',
    affiliation_observed_after_gap: 'Join after live gap',
    explicit_presence_remove: 'Logout',
    identity_reconciled: 'Identity reconciled',
    signal_busy: 'Busy',
    signal_check: 'Check',
    signal_denial: 'Denial',
    signal_emergency: 'Emergency',
    signal_page: 'Page',
    transmission_observed_after_gap: 'Current Grant after live gap',
    transmission_observed_after_uncertainty: 'Current Grant after uncertain updates',
    transmission_start: 'Grant',
    transport_gap: 'Live observation gap'
  };
  return labels[kind] || text(event.label || kind, 'Observation').replace(/_/g, ' ');
}

function coalesceActivityEvents(items = [], options = {}) {
  const maximumSourceEvents = Math.max(1, Number(options.maximumSourceEvents) || 100);
  const windowMs = Math.max(0, Number(options.windowMs) || 2_000);
  const source = (Array.isArray(items) ? items : []).slice(-maximumSourceEvents)
    .map((event, index) => ({ event, index }))
    .sort((left, right) => (Number(left.event?.observedAtMs) || 0) -
      (Number(right.event?.observedAtMs) || 0) || left.index - right.index);
  const rows = [];

  source.forEach(({ event }, index) => {
    const id = activityEventId(event, index);
    const kind = String(event?.kind || 'event');
    const observedAtMs = Number(event?.observedAtMs) || 0;
    const routine = ROUTINE_ACTIVITY_KINDS.has(kind);
    const previous = routine && rows.at(-1)?.kind === kind ? rows.at(-1) : null;
    if (previous && observedAtMs - previous.lastObservedAtMs <= windowMs) {
      previous.count += 1;
      previous.lastObservedAtMs = observedAtMs;
      previous.sourceEventIds.push(id);
      const detail = activityEventDetail(event);
      if (!previous.sampleDetails.includes(detail) && previous.sampleDetails.length < 2) {
        previous.sampleDetails.push(detail);
      }
      return;
    }
    const row = {
      ...event,
      id: routine ? `activity-burst:${kind}:${id}` : id,
      count: 1,
      firstObservedAtMs: observedAtMs,
      lastObservedAtMs: observedAtMs,
      observedAtMs,
      sourceEventIds: [id],
      sampleDetails: [activityEventDetail(event)]
    };
    rows.push(row);
  });
  return rows;
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
  const initialAutoRotate = dependencies.initialAutoRotate !== false;
  const emptyTitle = 'Listening — the map builds as activity arrives.';
  const emptyDetail = 'Only fresh live calls and confirmed affiliation observations appear here.';
  let renderedNodeCount = 0;
  let eventsReturnFocus = null;
  let inspectorReturnFocus = null;
  let autoRotateRequested = initialAutoRotate;
  let reducedMotion = Boolean(dependencies.reducedMotion);
  let inspectorSignature = '';
  let eventsFollowing = true;
  let unseenEventCount = 0;
  let eventScrollFrame = 0;
  let knownEventIds = new Set();
  let affiliationAlertTimer = 0;
  let affiliationAlertId = '';
  const eventRows = new Map();

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
  const autoRotate = iconButton('icon-replay', 'Auto rotate');
  autoRotate.setAttribute('aria-pressed', String(initialAutoRotate));
  autoRotate.title = 'Slowly orbit the current view while idle';
  cameraActions.append(fit, focus, autoRotate);

  const freeze = toggleButton(node, 'Freeze layout', false);
  const eventsToggle = toggleButton(node, 'Events', false);
  const clear = createTextButton(node, 'Clear map', 'ui-button ui-button-danger-quiet');
  const fullscreen = iconButton('icon-fullscreen', 'Enter fullscreen');
  const settingsButton = iconButton('icon-playback-controls', 'Density settings');
  settingsButton.setAttribute('popovertarget', 'network-visualizer-settings');
  actions.append(cameraActions, freeze, eventsToggle, clear, fullscreen, settingsButton);
  toolbar.append(brand, status, search, filterGroup, actions);

  const stage = node('div', 'network-visualizer-stage');
  stage.tabIndex = 0;
  stage.setAttribute('aria-label', 'Interactive radio network canvas');
  stage.dataset.webgl = 'pending';
  const canvas = node('div', 'network-visualizer-canvas');

  const scopeNavigation = node('nav', 'network-visualizer-overlay network-visualizer-scope');
  scopeNavigation.setAttribute('aria-label', 'Network view hierarchy');
  scopeNavigation.dataset.level = 'overview';
  const scopeBack = iconButton('icon-arrow-down', 'Back to all systems',
    'ui-button ui-button-secondary ui-icon-button network-visualizer-back');
  scopeBack.hidden = true;
  const scopeCopy = node('div', 'network-visualizer-scope-copy');
  const scopeBreadcrumb = node('ol', 'network-visualizer-breadcrumb');
  const overviewCrumb = node('li', '', 'Overview');
  overviewCrumb.setAttribute('aria-current', 'page');
  scopeBreadcrumb.append(overviewCrumb);
  const scopeTitle = node('strong', 'network-visualizer-scope-title', 'Observed radio systems');
  scopeTitle.setAttribute('aria-live', 'polite');
  scopeCopy.append(scopeBreadcrumb, scopeTitle);
  scopeNavigation.append(scopeBack, scopeCopy);

  const empty = node('div', 'network-visualizer-empty');
  empty.append(iconGlyph('icon-network-visualizer'), node('strong', '', emptyTitle), node('p', '', emptyDetail));

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
    legendItem('tx', 'Grant'), lock);

  const notice = node('div', 'network-visualizer-notice');
  notice.hidden = true;
  notice.setAttribute('role', 'status');
  notice.setAttribute('aria-live', 'polite');

  const affiliationAlert = node('div', 'network-visualizer-affiliation-alert');
  affiliationAlert.hidden = true;
  affiliationAlert.setAttribute('role', 'alert');
  affiliationAlert.setAttribute('aria-live', 'assertive');
  affiliationAlert.setAttribute('aria-atomic', 'true');

  const inspector = node('aside', 'network-visualizer-inspector');
  inspector.hidden = true;
  inspector.setAttribute('aria-label', 'Selected network entity');
  const inspectorHeader = node('div', 'network-visualizer-panel-header');
  const inspectorHeading = node('div', 'network-visualizer-panel-heading');
  const inspectorTitle = node('h2', '', 'Selection');
  const inspectorSubtitle = node('p');
  inspectorHeading.append(inspectorTitle, inspectorSubtitle);
  const inspectorActions = node('div', 'network-visualizer-panel-header-actions');
  const inspectorCollapse = iconButton('icon-chevron-down', 'Collapse inspector');
  inspectorCollapse.setAttribute('aria-controls', 'network-visualizer-inspector-body');
  inspectorCollapse.setAttribute('aria-expanded', 'true');
  const inspectorClose = iconButton('icon-close', 'Close inspector');
  inspectorActions.append(inspectorCollapse, inspectorClose);
  inspectorHeader.append(inspectorHeading, inspectorActions);
  const inspectorBody = node('div', 'network-visualizer-panel-body');
  inspectorBody.id = 'network-visualizer-inspector-body';
  inspector.append(inspectorHeader, inspectorBody);

  const events = node('aside', 'network-visualizer-events');
  events.hidden = true;
  events.setAttribute('aria-label', 'Recent observed network events');
  const eventsHeader = node('div', 'network-visualizer-panel-header');
  const eventsHeading = node('div', 'network-visualizer-panel-heading');
  const eventsSubtitle = node('p', '', 'Routine bursts are grouped; affiliation changes stay individual.');
  eventsHeading.append(node('h3', '', 'Observed activity'), eventsSubtitle);
  const eventsHeaderActions = node('div', 'network-visualizer-panel-header-actions');
  const eventsLatest = createTextButton(node, '',
    'ui-button ui-button-secondary network-visualizer-events-latest');
  eventsLatest.hidden = true;
  const eventsClose = iconButton('icon-close', 'Close event drawer');
  eventsHeaderActions.append(eventsLatest, eventsClose);
  eventsHeader.append(eventsHeading, eventsHeaderActions);
  const eventList = node('ol', 'network-visualizer-event-list');
  eventList.tabIndex = 0;
  eventList.setAttribute('aria-label', 'Observed activity, oldest to newest');
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
  settingsGrid.append(radioRangeLabel, labelRangeLabel);
  settings.append(settingsHeading, settingsGrid);

  stage.append(canvas, scopeNavigation, empty, legend, notice, affiliationAlert, inspector, events, settings);
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
  autoRotate.addEventListener('click', () => {
    const value = autoRotate.getAttribute('aria-pressed') !== 'true';
    setAutoRotate(value);
    callbacks.onAutoRotate?.(value);
  });
  freeze.addEventListener('click', () => callbacks.onFreeze?.(freeze.getAttribute('aria-pressed') !== 'true'));
  eventsToggle.addEventListener('click', () => setEventsOpen(events.hidden));
  eventsClose.addEventListener('click', () => setEventsOpen(false));
  eventsLatest.addEventListener('click', () => {
    setEventsFollowing(true);
    scheduleLatestEventScroll();
    eventsClose.focus({ preventScroll: true });
  });
  eventList.addEventListener('scroll', onEventListScroll, { passive: true });
  scopeBack.addEventListener('click', () => callbacks.onBack?.());
  inspectorCollapse.addEventListener('click', () => {
    const collapsed = inspectorCollapse.getAttribute('aria-expanded') === 'true';
    setInspectorCollapsed(collapsed);
    callbacks.onInspectorCollapse?.(collapsed);
  });
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
  function setTransport(state, detail = '') {
    const value = String(state || '').toLowerCase();
    status.textContent = value === 'open' ? 'Live' : value === 'gap' ? 'Live gap' :
      value === 'error' ? 'Reconnecting' : 'Connecting';
    status.className = `badge ui-pill network-visualizer-status ${value === 'open' ? 'state-current' :
      value === 'gap' ? 'state-warning' : 'state-stale'}`;
    status.title = detail;
  }

  function setCounts(value = {}) {
    renderedNodeCount = Math.max(0, Number(value.renderedNodes ?? value.visibleNodes) || 0);
    empty.hidden = stage.dataset.webgl === 'failed' ? false : renderedNodeCount > 0;
  }

  function setAutoRotate(value) {
    autoRotateRequested = Boolean(value);
    updateAutoRotateControl();
  }

  function updateAutoRotateControl() {
    autoRotate.disabled = reducedMotion;
    autoRotate.setAttribute('aria-pressed', String(autoRotateRequested && !reducedMotion));
    autoRotate.title = reducedMotion ? 'Auto rotate is disabled by reduced motion' :
      'Slowly orbit the current view while idle';
  }

  function setReducedMotion(value) {
    reducedMotion = Boolean(value);
    updateAutoRotateControl();
  }

  function setScope(value = {}) {
    const requestedLevel = value.level === 'group' ? 'talkgroup' : value.level;
    const level = ['system', 'talkgroup'].includes(requestedLevel) ? requestedLevel : 'overview';
    const title = text(value.title || value.label,
      level === 'overview' ? 'Observed radio systems' : level === 'system' ? 'Radio system' : 'Talkgroup');
    const suppliedPath = Array.isArray(value.path) ? value.path : [];
    const derivedPath = level === 'overview' ? ['Overview'] : level === 'system' ?
      ['Overview', title] : ['Overview', text(value.parentLabel, 'Radio system'), title];
    const path = (suppliedPath.length ? suppliedPath : derivedPath)
      .map((entry) => text(typeof entry === 'object' ? entry.label || entry.title : entry, ''))
      .filter(Boolean);
    const canGoBack = value.canGoBack === undefined ? level !== 'overview' : Boolean(value.canGoBack);
    scopeNavigation.dataset.level = level;
    stage.dataset.scopeLevel = level;
    scopeTitle.textContent = title;
    scopeBreadcrumb.replaceChildren(...path.map((label, index) => {
      const item = node('li', '', label);
      if (index === path.length - 1) item.setAttribute('aria-current', 'page');
      return item;
    }));
    scopeBack.hidden = !canGoBack;
    const destination = text(value.backLabel, level === 'talkgroup' ?
      `Back to ${text(value.parentLabel, 'radio system')}` : 'Back to all systems');
    scopeBack.setAttribute('aria-label', destination);
    scopeBack.title = destination;
  }

  function setInspectorCollapsed(value) {
    const collapsed = Boolean(value);
    inspector.dataset.collapsed = String(collapsed);
    inspectorBody.hidden = collapsed;
    inspectorCollapse.setAttribute('aria-expanded', String(!collapsed));
    const label = collapsed ? 'Expand inspector' : 'Collapse inspector';
    inspectorCollapse.setAttribute('aria-label', label);
    inspectorCollapse.title = label;
  }

  function setFrozen(value) {
    const frozen = Boolean(value);
    freeze.setAttribute('aria-pressed', String(frozen));
    freeze.textContent = frozen ? 'Layout frozen' : 'Freeze layout';
  }

  function updateEventsLatestControl() {
    const waiting = !eventsFollowing && unseenEventCount > 0;
    eventsLatest.hidden = !waiting;
    eventsLatest.textContent = waiting ? `${unseenEventCount} new · Jump to latest` : '';
    eventsLatest.setAttribute('aria-label', waiting ?
      `${unseenEventCount} new observed ${unseenEventCount === 1 ? 'event' : 'events'}. Jump to latest.` :
      'Jump to latest observed activity');
    eventsSubtitle.textContent = eventsFollowing ?
      'Routine bursts are grouped; affiliation changes stay individual.' :
      'Paused while you read. New activity will wait below.';
  }

  function setEventsFollowing(value) {
    eventsFollowing = Boolean(value);
    if (eventsFollowing) unseenEventCount = 0;
    updateEventsLatestControl();
  }

  function isAtLatestEvent() {
    return eventList.scrollHeight - eventList.clientHeight - eventList.scrollTop <= 24;
  }

  function onEventListScroll() {
    if (events.hidden) return;
    const atLatest = isAtLatestEvent();
    if (atLatest !== eventsFollowing) setEventsFollowing(atLatest);
  }

  function scheduleLatestEventScroll() {
    const view = document.defaultView || globalThis;
    if (eventScrollFrame) view.cancelAnimationFrame?.(eventScrollFrame);
    eventScrollFrame = view.requestAnimationFrame?.(() => {
      eventScrollFrame = 0;
      eventList.scrollTop = eventList.scrollHeight;
    }) || 0;
    if (!eventScrollFrame) eventList.scrollTop = eventList.scrollHeight;
  }

  function captureEventScrollAnchor() {
    if (eventsFollowing) return null;
    const scrollTop = eventList.scrollTop;
    const row = [...eventList.children].find((item) => item.offsetTop + item.offsetHeight >= scrollTop);
    return row ? { id: row.dataset.eventId, offset: row.offsetTop - scrollTop } : null;
  }

  function restoreEventScrollAnchor(anchor) {
    if (!anchor) return;
    const row = eventRows.get(anchor.id);
    if (row?.isConnected) eventList.scrollTop = row.offsetTop - anchor.offset;
  }

  function setEventsOpen(open) {
    const selected = Boolean(open);
    if (selected && events.hidden) eventsReturnFocus = document.activeElement;
    const restoreFocus = !selected && !events.hidden && events.contains(document.activeElement);
    events.hidden = !selected;
    eventsToggle.setAttribute('aria-pressed', String(selected));
    if (selected) {
      setEventsFollowing(true);
      scheduleLatestEventScroll();
      eventsClose.focus();
    }
    else {
      if (restoreFocus) {
        const target = eventsReturnFocus?.isConnected && typeof eventsReturnFocus.focus === 'function' ?
          eventsReturnFocus : eventsToggle;
        target.focus();
      }
      eventsReturnFocus = null;
    }
  }

  function showNotice(message = '') {
    const normalized = text(message, '');
    notice.hidden = !normalized;
    notice.textContent = normalized;
  }

  function clearAffiliationAlert() {
    const view = document.defaultView || globalThis;
    if (affiliationAlertTimer) view.clearTimeout?.(affiliationAlertTimer);
    affiliationAlertTimer = 0;
    affiliationAlertId = '';
    affiliationAlert.hidden = true;
    affiliationAlert.dataset.visible = 'false';
    affiliationAlert.replaceChildren();
  }

  function showAffiliationAlert(value = {}) {
    const id = String(value.id || '');
    if (!id || id === affiliationAlertId) return false;
    const view = document.defaultView || globalThis;
    if (affiliationAlertTimer) view.clearTimeout?.(affiliationAlertTimer);
    affiliationAlertId = id;
    affiliationAlert.replaceChildren(
      node('span', 'network-visualizer-affiliation-alert-kicker', 'Observed affiliation change'),
      node('strong', 'network-visualizer-affiliation-alert-radio', text(value.radioLabel, 'Radio')),
      node('span', 'network-visualizer-affiliation-alert-route',
        `${text(value.fromLabel, 'Unknown talkgroup')} → ${text(value.toLabel, 'Unknown talkgroup')}`),
      node('span', 'network-visualizer-affiliation-alert-meta',
        `${text(value.systemLabel, 'Radio system')} · ${timeLabel(value.observedAtMs)}`)
    );
    affiliationAlert.hidden = false;
    affiliationAlert.dataset.visible = 'true';
    affiliationAlertTimer = view.setTimeout?.(() => clearAffiliationAlert(), 6_000) || 0;
    return true;
  }

  function setSelection(entity, transitions = []) {
    if (!entity) {
      inspectorSignature = '';
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
    const signature = JSON.stringify([
      entity.key, entity.label, entity.kind, entity.displayId, entity.nativeId, entity.protocol,
      entity.systemName, entity.wacn, entity.systemId, entity.siteName, entity.channelName,
      entity.configurationId, entity.rfssId, entity.siteId, entity.nac, entity.timeslot,
      entity.affiliationCapability, entity.affiliationLabel, entity.affiliationEvidenceType,
      entity.affiliationObservedAtMs, entity.txTargetLabel, entity.txContinuityUncertain,
      Math.floor(Number(entity.lastMeaningfulAtMs || entity.lastObservedAtMs || 0) / 1_000),
      entity.encrypted,
      (Array.isArray(transitions) ? transitions : []).slice(-5).map((item) =>
        [item.id, item.oldGroupKey, item.newGroupKey, item.observedAtMs])
    ]);
    if (!inspector.hidden && signature === inspectorSignature) return;
    inspectorSignature = signature;
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
    const radioSystem = entity.type === 'universe' && entity.kind === 'radio_system';
    if (!radioSystem) appendFact(node, facts, 'Observed at', entity.siteName || entity.channelName || entity.configurationId);
    if (!radioSystem) appendFact(node, facts, 'RFSS · Site', entity.rfssId && entity.siteId ?
      `${entity.rfssId} / ${entity.siteId}` : '');
    if (!radioSystem) appendFact(node, facts, 'NAC', entity.nac);
    appendFact(node, facts, 'Timeslot', entity.timeslot);
    if (!radioSystem) appendFact(node, facts, 'Configuration', entity.configurationId);
    appendFact(node, facts, 'Affiliation', entity.affiliationCapability === 'not_applicable' ? '' :
      (entity.affiliationLabel || entity.affiliation?.groupLabel ||
        (entity.affiliationCapability === 'unsupported' ? 'Unsupported by this live feed' :
          (entity.affiliationAmbiguous ? 'Ambiguous across observation scopes' : 'Unknown'))));
    appendFact(node, facts, 'Evidence', text(entity.affiliationEvidenceType, '').replace(/_/g, ' '));
    if (!radioSystem) appendFact(node, facts, 'Affiliation seen',
      timeLabel(entity.affiliationObservedAtMs || entity.affiliation?.observedAtMs));
    if (!radioSystem) appendFact(node, facts, 'Current Grant target',
      entity.txTargetLabel || entity.currentTx?.groupLabel || 'Not transmitting');
    if (entity.txContinuityUncertain) appendFact(node, facts, 'Grant continuity',
      'Current after a live gap; start unknown');
    if (!radioSystem) appendFact(node, facts, 'Last activity',
      timeLabel(entity.lastMeaningfulAtMs || entity.lastObservedAtMs));
    if (entity.encrypted) appendFact(node, facts, 'Encryption', 'Observed on current/recent grant');
    const panelActions = node('div', 'network-visualizer-panel-actions');
    const reference = entity.entityRef || entity.entity_ref;
    if (reference && typeof entityRefHref === 'function') {
      const target = entityRefHref(reference);
      if (target) {
        const details = node('a', 'ui-button ui-button-secondary', 'Open details');
        details.href = target;
        panelActions.append(details);
      }
    }
    inspectorBody.replaceChildren(facts);
    if (panelActions.childElementCount) inspectorBody.append(panelActions);
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
    const scrollAnchor = captureEventScrollAnchor();
    const retainedLimit = Math.max(100, Number(config?.state?.hardSemanticEvents) || 5_000);
    const source = Array.isArray(items) ? items.slice(-retainedLimit) : [];
    const nextKnownEventIds = new Set(source.map((event, index) => activityEventId(event, index)));
    const introduced = [...nextKnownEventIds].filter((id) => !knownEventIds.has(id)).length;
    if (!events.hidden && !eventsFollowing && knownEventIds.size && introduced) {
      unseenEventCount += introduced;
      updateEventsLatestControl();
    }
    knownEventIds = nextKnownEventIds;
    const values = coalesceActivityEvents(source, { maximumSourceEvents: retainedLimit }).slice(-100);
    if (!values.length) {
      eventRows.clear();
      const emptyRow = node('li', 'network-visualizer-event', 'No observed activity yet.');
      emptyRow.dataset.eventId = 'empty';
      eventList.replaceChildren(emptyRow);
      setEventsFollowing(true);
      return;
    }
    const wanted = new Set();
    values.forEach((event, index) => {
      const id = activityEventId(event, index);
      wanted.add(id);
      let item = eventRows.get(id);
      if (!item) {
        item = node('li', 'network-visualizer-event');
        item.dataset.eventId = id;
        eventRows.set(id, item);
      }
      const kind = activityEventLabel(event);
      const count = Math.max(1, Number(event.count) || 1);
      const samples = Array.isArray(event.sampleDetails) && event.sampleDetails.length ?
        event.sampleDetails : [activityEventDetail(event)];
      const more = Math.max(0, count - samples.length);
      const detail = `${samples.join(' • ')}${more ? ` • +${more} more` : ''}`;
      const valuesForRow = [kind, detail, timeLabel(event.observedAtMs), count];
      if (item.dataset.signature !== JSON.stringify(valuesForRow)) {
        item.dataset.signature = JSON.stringify(valuesForRow);
        item.dataset.category = ['affiliation_change', 'observed_affiliation_change'].includes(event.kind) ?
          'affiliation-change' : String(event.kind || '').startsWith('signal_') ?
            String(event.kind).replace('signal_', 'signal-') : 'observation';
        const kindLine = node('span', 'network-visualizer-event-kind-line');
        kindLine.append(node('span', 'network-visualizer-event-kind', valuesForRow[0]));
        if (count > 1) {
          const countBadge = node('span', 'badge ui-pill network-visualizer-event-count', `${count}×`);
          countBadge.setAttribute('aria-label', `${count} similar observations grouped`);
          kindLine.append(countBadge);
        }
        const timestamp = node('time', '', valuesForRow[2]);
        timestamp.dateTime = Number.isFinite(Number(event.observedAtMs)) ?
          new Date(Number(event.observedAtMs)).toISOString() : '';
        item.replaceChildren(kindLine,
          node('span', 'network-visualizer-event-detail', valuesForRow[1]), timestamp);
      }
      const current = eventList.children[index];
      if (current !== item) eventList.insertBefore(item, current || null);
    });
    for (const [id, row] of eventRows) {
      if (wanted.has(id)) continue;
      row.remove();
      eventRows.delete(id);
    }
    const emptyRow = eventList.querySelector?.('[data-event-id="empty"]');
    emptyRow?.remove();
    if (eventsFollowing && !events.hidden) scheduleLatestEventScroll();
    else restoreEventScrollAnchor(scrollAnchor);
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
    scopeNavigation,
    scopeBack,
    autoRotate,
    setTransport,
    setCounts,
    setAutoRotate,
    setReducedMotion,
    setScope,
    setFrozen,
    setInspectorCollapsed,
    setEventsOpen,
    showNotice,
    showAffiliationAlert,
    clearAffiliationAlert,
    setSelection,
    setEvents,
    setWebglState,
    close() {
      document.removeEventListener('fullscreenchange', fullscreenChanged);
      if (document.fullscreenElement === layout) void document.exitFullscreen().catch(() => {});
      settings.hidePopover?.();
      clearAffiliationAlert();
      const view = document.defaultView || globalThis;
      if (eventScrollFrame) view.cancelAnimationFrame?.(eventScrollFrame);
      eventList.removeEventListener('scroll', onEventListScroll);
      eventRows.clear();
      knownEventIds.clear();
      inspectorSignature = '';
    }
  };
}

export { activityEventDetail, activityEventLabel, coalesceActivityEvents, createNetworkVisualizerUi };
