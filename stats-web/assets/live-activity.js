(function() {
  'use strict';

  const MAXIMUM_ROWS = 2000;
  const DEFAULT_ROW_LIMIT = 200;
  const SEARCH_DELAY_MILLISECONDS = 80;
  const ACCESS_CHECK_DELAY_MILLISECONDS = 1000;
  const TIME_FORMAT = new Intl.DateTimeFormat([], {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  });

  function element(tag, className, text) {
    const value = document.createElement(tag);
    if (className) value.className = className;
    if (text !== undefined && text !== null) value.textContent = String(text);
    return value;
  }

  function button(label, className) {
    const value = element('button', className, label);
    value.type = 'button';
    return value;
  }

  function asArray(value) {
    return Array.isArray(value) ? value : [];
  }

  function valueOf(object, ...names) {
    if (!object || typeof object !== 'object') return null;
    for (const name of names) {
      if (object[name] !== null && object[name] !== undefined && object[name] !== '') return object[name];
    }
    return null;
  }

  function numeric(value, fallback = 0) {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function compare(left, right) {
    if (typeof left === 'number' || typeof right === 'number') {
      return numeric(left, Number.NEGATIVE_INFINITY) - numeric(right, Number.NEGATIVE_INFINITY);
    }
    return String(left ?? '').localeCompare(String(right ?? ''), undefined,
      { numeric: true, sensitivity: 'base' });
  }

  function timeValue(row, kind) {
    return numeric(kind === 'events' ? valueOf(row, 'timeStartMs', 'timestampMs') :
      valueOf(row, 'timestampMs', 'timeStartMs'));
  }

  function rowIdentifier(row) {
    return String(valueOf(row, 'id', 'sequence') ?? '');
  }

  function formatTime(milliseconds) {
    const value = numeric(milliseconds, NaN);
    if (!Number.isFinite(value) || value <= 0) return '—';
    const date = new Date(value);
    return `${TIME_FORMAT.format(date)}.${String(date.getMilliseconds()).padStart(3, '0')}`;
  }

  function formatDuration(milliseconds) {
    const value = Math.max(0, numeric(milliseconds));
    if (value < 1000) return `${Math.round(value)} ms`;
    return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)} s`;
  }

  function formatFrequency(hertz) {
    const value = numeric(hertz, NaN);
    return Number.isFinite(value) && value > 0 ? (value / 1_000_000).toFixed(6) : '—';
  }

  function formatTimeslot(timeslot) {
    return timeslot === null || timeslot === undefined || timeslot === '' ? '—' : String(timeslot);
  }

  function identifierText(identifiers) {
    const values = [];
    for (const identifier of asArray(identifiers)) {
      const value = String(valueOf(identifier, 'value') ?? '').trim();
      if (value && !values.includes(value)) values.push(value);
      if (values.length >= 3) break;
    }
    return values.length ? values.join(' · ') : '—';
  }

  function eventChannel(row) {
    const channel = String(valueOf(row, 'channel') ?? '').trim();
    const timeslot = valueOf(row, 'timeslot');
    return `${channel || '—'}${timeslot === null ? '' : ` · TS ${timeslot}`}`;
  }

  function categoryLabel(category) {
    const raw = String(category || 'other');
    const known = {
      voice: 'Voice calls',
      'protected-voice': 'Encrypted voice calls',
      encrypted: 'Encrypted voice calls',
      data: 'Data calls',
      commands: 'Commands',
      registrations: 'Registrations',
      registration: 'Registrations',
      headers: 'Headers',
      trunking: 'Trunking commands',
      packet: 'Packet and data',
      terminator: 'Terminators',
      sync: 'Sync loss',
      other: 'Other'
    };
    return known[raw] || raw.replace(/[-_]+/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
  }

  function normalizedFilters(value, rows) {
    let source = value;
    if (source && !Array.isArray(source) && typeof source === 'object') {
      source = source.categories || source.rows || source.filters || Object.entries(source).map(([id, label]) =>
        ({ id, label: typeof label === 'string' ? label : id }));
    }
    const result = [];
    const seen = new Set();
    for (const candidate of asArray(source)) {
      const object = typeof candidate === 'string' ? { id: candidate } : candidate;
      const id = String(valueOf(object, 'id', 'value', 'category', 'key') ?? '').trim();
      if (!id || seen.has(id)) continue;
      seen.add(id);
      result.push({
        id,
        label: String(valueOf(object, 'label', 'name', 'title') ?? categoryLabel(id)),
        description: String(valueOf(object, 'description', 'detail', 'help') ?? ''),
        enabled: valueOf(object, 'enabled', 'defaultEnabled') !== false
      });
    }
    for (const row of rows) {
      const id = String(valueOf(row, 'category') ?? 'other');
      if (!seen.has(id)) {
        seen.add(id);
        result.push({ id, label: categoryLabel(id), description: '', enabled: true });
      }
    }
    return result;
  }

  class HttpError extends Error {
    constructor(status, message) {
      super(message);
      this.status = status;
    }
  }

  class LiveActivityView {
    constructor(host, options = {}) {
      this.host = host;
      this.contextId = String(options.contextId || '');
      this.kind = options.activity === 'messages' ? 'messages' : 'events';
      this.embedded = options.embedded === true;
      this.onActivityChange = typeof options.onActivityChange === 'function' ? options.onActivityChange : null;
      this.closed = false;
      this.source = null;
      this.rows = new Map();
      this.filters = [];
      this.enabledFilters = new Set();
      this.filtersInitialized = false;
      this.context = null;
      this.status = null;
      this.generation = null;
      this.sequence = 0;
      this.rowLimit = DEFAULT_ROW_LIMIT;
      this.search = '';
      this.sortKey = null;
      this.sortDirection = 'desc';
      this.paused = false;
      this.frozenRows = null;
      this.pendingChanges = 0;
      this.clearedIds = new Set();
      this.renderFrame = null;
      this.searchTimer = null;
      this.accessTimer = null;
      this.refreshing = null;
      this.build();
      this.load();
    }

    close() {
      this.closed = true;
      this.source?.close();
      this.source = null;
      window.cancelAnimationFrame(this.renderFrame);
      window.clearTimeout(this.searchTimer);
      window.clearTimeout(this.accessTimer);
    }

    endpoint(suffix = '') {
      return `/api/v1/contexts/${encodeURIComponent(this.contextId)}/${this.kind}${suffix}`;
    }

    build() {
      this.host.className = `live-activity-root${this.embedded ? ' live-activity-embedded' : ''}`;

      const heading = element('header', 'live-activity-heading');
      const headingCopy = element('div');
      this.title = element('h1', '', this.kind === 'events' ? 'Events' : 'Messages');
      this.subtitle = element('p', '', this.kind === 'events' ?
        'Decoded activity for the selected site or channel. Active calls update in place.' :
        'Decoder messages for the exact selected processing context.');
      headingCopy.append(this.title, this.subtitle);
      const headingStatus = element('div', 'live-activity-heading-status');
      this.age = element('span', 'live-activity-count', 'Waiting for current data');
      this.connection = element('span', 'badge state-stale', 'Connecting');
      headingStatus.append(this.age, this.connection);
      heading.append(headingCopy, headingStatus);

      this.notice = element('div', 'live-activity-notice');
      this.notice.hidden = true;

      this.contextCard = element('section', 'live-activity-context');
      this.contextCard.setAttribute('aria-label', 'Selected Live context');
      this.contextSystem = this.contextCell('System and site');
      this.contextScope = this.contextCell('Selected scope');
      this.contextFrequency = this.contextCell('Frequency and timeslot');
      const change = element('div', 'live-activity-context-actions');
      const changeLink = element('a', '', 'Change selection in Live');
      changeLink.href = '/?view=live';
      change.append(changeLink);
      this.contextCard.append(this.contextSystem.cell, this.contextScope.cell, this.contextFrequency.cell, change);

      this.workspace = element('section', 'live-activity-workspace');
      const tabs = element('nav', 'live-activity-tabs');
      tabs.setAttribute('aria-label', 'Selected channel activity');
      for (const kind of ['events', 'messages']) {
        const link = element('a', kind === this.kind ? 'active' : '', kind === 'events' ? 'Events' : 'Messages');
        const target = new URLSearchParams({ view: 'live', context: this.contextId, activity: kind });
        link.href = `/?${target}`;
        if (kind === this.kind) link.setAttribute('aria-current', 'page');
        if (this.embedded && this.onActivityChange) {
          link.addEventListener('click', (event) => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            this.onActivityChange(kind);
          });
        }
        tabs.append(link);
      }

      if (this.embedded) {
        const tabStatus = element('div', 'live-activity-tab-status');
        tabStatus.append(this.age, this.connection);
        tabs.append(tabStatus);
      }

      this.activityBody = element('div', 'live-activity-body');
      this.workspace.append(tabs, this.activityBody);
      this.host.replaceChildren(...(this.embedded ? [this.notice, this.workspace] :
        [heading, this.notice, this.contextCard, this.workspace]));
      this.showLoading();
    }

    contextCell(label) {
      const cell = element('div', 'live-activity-context-cell');
      const name = element('span', '', label);
      const value = element('strong', '', '—');
      cell.append(name, value);
      return { cell, value };
    }

    showLoading() {
      this.activityBody.replaceChildren(element('div', 'live-activity-empty', 'Loading current activity…'));
    }

    async requestSnapshot() {
      const response = await fetch(this.endpoint(), { cache: 'no-store', credentials: 'same-origin' });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new HttpError(response.status, result.error || result.message ||
          `Activity request returned ${response.status}`);
      }
      return result;
    }

    async load() {
      this.setConnection('Connecting', 'state-stale', 'Waiting for current data');
      try {
        const snapshot = await this.requestSnapshot();
        if (this.closed) return;
        this.applySnapshot(snapshot);
        this.buildActivityBody();
        this.connect();
      } catch (error) {
        if (this.closed) return;
        if (error instanceof HttpError && (error.status === 401 || error.status === 403)) {
          this.showLogin();
        } else {
          this.showFailure(error.message || 'Current activity is unavailable');
        }
      }
    }

    buildActivityBody() {
      const toolbar = element('div', 'live-activity-toolbar');
      this.filterButton = button('Filters', 'secondary');
      this.filterCount = element('span', 'live-activity-filter-count');
      this.filterButton.append(' ', this.filterCount);
      this.filterButton.addEventListener('click', () => {
        this.filterPanel.hidden = !this.filterPanel.hidden;
        this.filterButton.setAttribute('aria-expanded', String(!this.filterPanel.hidden));
      });
      this.filterButton.setAttribute('aria-expanded', 'false');

      const clear = button('Clear view', 'secondary');
      clear.title = 'Hide everything received so far in this browser';
      clear.addEventListener('click', () => this.clearView());
      this.pauseButton = button('Pause', 'secondary');
      this.pauseButton.title = 'Freeze row movement while new activity remains bounded in memory';
      this.pauseButton.addEventListener('click', () => this.togglePause());
      const reset = button('Reset view', 'secondary');
      reset.addEventListener('click', () => this.resetView());

      const searchLabel = element('label', 'live-activity-search');
      searchLabel.append(element('span', '', 'Find in visible rows'));
      this.searchInput = element('input');
      this.searchInput.type = 'search';
      this.searchInput.placeholder = this.kind === 'events' ?
        'ID, frequency, event, or details' : 'Protocol, identifier, or message text';
      this.searchInput.addEventListener('input', () => {
        this.search = this.searchInput.value;
        window.clearTimeout(this.searchTimer);
        this.searchTimer = window.setTimeout(() => this.scheduleRender(true), SEARCH_DELAY_MILLISECONDS);
      });
      searchLabel.append(this.searchInput);

      const rowLimit = element('label', 'live-activity-limit');
      const rowLimitLabel = element('span', '', 'Visible row limit');
      this.rowLimitOutput = element('output', '', String(this.rowLimit));
      const rowLimitHeading = element('span', 'live-activity-limit-heading');
      rowLimitHeading.append(rowLimitLabel, this.rowLimitOutput);
      this.rowLimitInput = element('input');
      this.rowLimitInput.type = 'range';
      this.rowLimitInput.min = '0';
      this.rowLimitInput.max = String(MAXIMUM_ROWS);
      this.rowLimitInput.step = '50';
      this.rowLimitInput.value = String(this.rowLimit);
      this.rowLimitInput.addEventListener('input', () => {
        this.rowLimit = numeric(this.rowLimitInput.value);
        this.rowLimitOutput.textContent = String(this.rowLimit);
      });
      this.rowLimitInput.addEventListener('change', () => this.scheduleRender(true));
      rowLimit.append(rowLimitHeading, this.rowLimitInput);

      toolbar.append(this.filterButton, clear, this.pauseButton, reset, searchLabel, rowLimit);
      this.filterPanel = element('div', 'live-activity-filter-panel');
      this.filterPanel.hidden = true;
      this.feedState = element('div', 'live-activity-feed-state');
      this.feedCopy = element('span');
      this.visibleCount = element('span');
      this.feedState.append(this.feedCopy, this.visibleCount);
      this.tableHost = element('div', 'live-activity-table-host');
      this.mobileHost = element('div', 'live-activity-mobile');
      this.activityBody.replaceChildren(toolbar, this.filterPanel, this.feedState, this.tableHost, this.mobileHost);
      this.renderFilterPanel();
      this.scheduleRender(true);
    }

    renderFilterPanel() {
      if (!this.filterPanel) return;
      const controls = element('div', 'live-activity-filter-actions');
      const all = button('Select all', 'secondary');
      const none = button('Select none', 'secondary');
      all.addEventListener('click', () => {
        this.enabledFilters = new Set(this.filters.map((filter) => filter.id));
        this.renderFilterPanel();
        this.scheduleRender(true);
      });
      none.addEventListener('click', () => {
        this.enabledFilters.clear();
        this.renderFilterPanel();
        this.scheduleRender(true);
      });
      controls.append(all, none);
      const list = element('div', 'live-activity-filter-list');
      for (const filter of this.filters) {
        const label = element('label', 'live-activity-filter');
        const checkbox = element('input');
        checkbox.type = 'checkbox';
        checkbox.checked = this.enabledFilters.has(filter.id);
        checkbox.addEventListener('change', () => {
          if (checkbox.checked) this.enabledFilters.add(filter.id);
          else this.enabledFilters.delete(filter.id);
          this.updateFilterCount();
          this.scheduleRender(true);
        });
        const copy = element('span');
        copy.append(element('strong', '', filter.label));
        if (filter.description) copy.append(element('small', '', filter.description));
        label.append(checkbox, copy);
        list.append(label);
      }
      this.filterPanel.replaceChildren(controls, list);
      this.updateFilterCount();
    }

    updateFilterCount() {
      if (this.filterCount) this.filterCount.textContent = String(this.enabledFilters.size);
    }

    applySnapshot(snapshot) {
      const incoming = asArray(snapshot?.rows).filter((row) => rowIdentifier(row));
      this.context = snapshot?.context || this.context;
      this.status = snapshot?.status || this.status;
      this.generation = valueOf(snapshot, 'generation');
      this.sequence = numeric(valueOf(snapshot, 'sequence'), this.sequence);
      this.rows = new Map(incoming.slice(0, MAXIMUM_ROWS).map((row) => [rowIdentifier(row), row]));
      const nextFilters = normalizedFilters(snapshot?.filters, incoming);
      if (!this.filtersInitialized) {
        this.enabledFilters = new Set(nextFilters.filter((filter) => filter.enabled).map((filter) => filter.id));
        this.filtersInitialized = true;
      } else {
        const existing = new Set(nextFilters.map((filter) => filter.id));
        this.enabledFilters.forEach((id) => { if (!existing.has(id)) this.enabledFilters.delete(id); });
        nextFilters.forEach((filter) => {
          if (!this.filters.some((current) => current.id === filter.id) && filter.enabled) {
            this.enabledFilters.add(filter.id);
          }
        });
      }
      this.filters = nextFilters;
      this.updateContext();
      this.updateStatus();
      this.renderFilterPanel();
      this.scheduleRender(true);
    }

    applyDelta(delta) {
      const generation = valueOf(delta, 'generation');
      if (this.generation !== null && generation !== null && String(generation) !== String(this.generation)) {
        this.resnapshot('The selected decoder context changed. Refreshing current rows…');
        return;
      }
      let changes = 0;
      for (const removal of asArray(delta?.removes)) {
        const id = typeof removal === 'object' ? rowIdentifier(removal) : String(removal ?? '');
        if (id && this.rows.delete(id)) changes += 1;
      }
      for (const row of asArray(delta?.upserts)) {
        const id = rowIdentifier(row);
        if (!id) continue;
        this.rows.set(id, row);
        const category = String(valueOf(row, 'category') ?? 'other');
        if (!this.filters.some((filter) => filter.id === category)) {
          this.filters.push({ id: category, label: categoryLabel(category), description: '', enabled: true });
          this.enabledFilters.add(category);
          this.renderFilterPanel();
        }
        changes += 1;
      }
      this.sequence = numeric(valueOf(delta, 'sequence'), this.sequence);
      this.trimRows();
      if (this.paused) {
        this.pendingChanges = Math.min(MAXIMUM_ROWS, this.pendingChanges + changes);
        this.updateFeedState();
      } else {
        this.scheduleRender();
      }
      this.setConnection('Live', 'state-current', 'Updated just now');
    }

    trimRows() {
      if (this.rows.size <= MAXIMUM_ROWS) return;
      const ordered = [...this.rows.values()].sort((left, right) =>
        timeValue(right, this.kind) - timeValue(left, this.kind));
      this.rows = new Map(ordered.slice(0, MAXIMUM_ROWS).map((row) => [rowIdentifier(row), row]));
    }

    connect() {
      this.source?.close();
      if (this.closed) return;
      const source = new EventSource(this.endpoint('/stream'));
      this.source = source;
      source.addEventListener('snapshot', (event) => this.readStreamEvent(event, (payload) => {
        this.applySnapshot(payload);
      }));
      source.addEventListener('delta', (event) => this.readStreamEvent(event, (payload) => {
        this.applyDelta(payload);
      }));
      source.addEventListener('resnapshot', (event) => this.readStreamEvent(event, (payload) => {
        if (payload?.rows) this.applySnapshot(payload);
        else this.resnapshot('Some updates were missed. Refreshing current rows…');
      }));
      source.onmessage = (event) => this.readStreamEvent(event, (payload) => {
        if (payload?.rows) this.applySnapshot(payload);
        else if (payload?.upserts || payload?.removes) this.applyDelta(payload);
      });
      source.onopen = () => {
        if (source !== this.source || this.closed) return;
        this.setConnection('Live', 'state-current', 'Updated just now');
      };
      source.onerror = () => {
        if (source !== this.source || this.closed) return;
        this.setConnection('Reconnecting', 'state-stale', 'Keeping the last complete rows');
        this.noticeMessage('The live connection is reconnecting. Current rows remain available.');
        this.scheduleAccessCheck();
      };
    }

    readStreamEvent(event, consumer) {
      if (this.closed) return;
      try {
        consumer(JSON.parse(event.data));
      } catch (error) {
        this.resnapshot('A live update could not be read. Refreshing current rows…');
      }
    }

    async resnapshot(message) {
      if (this.refreshing || this.closed) return;
      this.noticeMessage(message);
      this.setConnection('Refreshing', 'state-stale', 'Catching up after missed updates');
      this.source?.close();
      this.source = null;
      this.refreshing = this.requestSnapshot();
      try {
        const snapshot = await this.refreshing;
        if (this.closed) return;
        this.applySnapshot(snapshot);
        this.hideNotice();
        this.connect();
      } catch (error) {
        if (this.closed) return;
        if (error instanceof HttpError && (error.status === 401 || error.status === 403)) this.showLogin();
        else this.showFailure(error.message || 'Current activity is unavailable');
      } finally {
        this.refreshing = null;
      }
    }

    scheduleAccessCheck() {
      window.clearTimeout(this.accessTimer);
      this.accessTimer = window.setTimeout(async () => {
        if (this.closed || !this.source) return;
        try {
          const response = await fetch(this.endpoint(), { cache: 'no-store', credentials: 'same-origin' });
          if (response.status === 401 || response.status === 403) {
            this.source?.close();
            this.source = null;
            this.rows.clear();
            this.showLogin();
          }
        } catch (error) {
          // EventSource owns ordinary reconnects; this request only distinguishes an access-policy change.
        }
      }, ACCESS_CHECK_DELAY_MILLISECONDS);
    }

    updateContext() {
      const context = this.context || {};
      const system = valueOf(context, 'systemAndSite', 'systemSite', 'title', 'tableTitle', 'systemName', 'system');
      const site = valueOf(context, 'siteName', 'site', 'channelName');
      const combined = [system, site].filter(Boolean).filter((value, index, values) => values.indexOf(value) === index)
        .join(' · ');
      this.contextSystem.value.textContent = combined || valueOf(context, 'decoderHint', 'decoder') || 'Selected Live context';
      const scope = String(valueOf(context, 'scope', 'selectionScope') ?? '').toLowerCase();
      this.contextScope.value.textContent = valueOf(context, 'scopeLabel') ||
        (scope.includes('site') ? 'Entire site' : (this.kind === 'messages' ? 'Exact processing chain' : 'Exact channel'));
      const frequency = valueOf(context, 'frequencyHz', 'frequency_hz');
      const timeslot = valueOf(context, 'timeslot');
      const frequencyText = formatFrequency(frequency);
      this.contextFrequency.value.textContent = `${frequencyText === '—' ? 'Frequency unavailable' : `${frequencyText} MHz`}` +
        `${timeslot === null ? ' · no timeslot' : ` · timeslot ${timeslot}`}`;
    }

    updateStatus() {
      const status = this.status;
      const label = typeof status === 'string' ? status : valueOf(status, 'label', 'state', 'status');
      if (label) this.age.textContent = String(label);
    }

    setConnection(label, className, age) {
      this.connection.textContent = label;
      this.connection.className = `badge ${className}`;
      if (age) this.age.textContent = age;
      if (label === 'Live') this.hideNotice();
    }

    noticeMessage(message) {
      this.notice.textContent = message;
      this.notice.hidden = false;
    }

    hideNotice() {
      this.notice.hidden = true;
      this.notice.textContent = '';
    }

    clearView() {
      this.clearedIds = new Set(this.rows.keys());
      this.scheduleRender(true);
      this.noticeMessage('This browser now hides everything received before Clear view.');
    }

    togglePause() {
      this.paused = !this.paused;
      if (this.paused) {
        this.frozenRows = new Map(this.rows);
        this.pendingChanges = 0;
      } else {
        this.frozenRows = null;
        this.pendingChanges = 0;
      }
      this.pauseButton.textContent = this.paused ? 'Continue' : 'Pause';
      this.scheduleRender(true);
    }

    resetView() {
      this.search = '';
      this.searchInput.value = '';
      this.rowLimit = DEFAULT_ROW_LIMIT;
      this.rowLimitInput.value = String(this.rowLimit);
      this.rowLimitOutput.textContent = String(this.rowLimit);
      this.sortKey = null;
      this.sortDirection = 'desc';
      this.clearedIds.clear();
      this.enabledFilters = new Set(this.filters.map((filter) => filter.id));
      if (this.paused) this.togglePause();
      else this.scheduleRender(true);
      this.renderFilterPanel();
      this.hideNotice();
    }

    scheduleRender(force = false) {
      if (this.closed || (this.paused && !force)) return;
      if (this.renderFrame !== null) return;
      this.renderFrame = window.requestAnimationFrame(() => {
        this.renderFrame = null;
        this.renderRows();
      });
    }

    visibleRows() {
      if (this.rowLimit <= 0) return [];
      const source = this.paused && this.frozenRows ? this.frozenRows : this.rows;
      const query = this.search.trim().toLocaleLowerCase();
      const rows = [...source.values()].filter((row) => {
        const id = rowIdentifier(row);
        if (this.clearedIds.has(id)) return false;
        const category = String(valueOf(row, 'category') ?? 'other');
        if (!this.enabledFilters.has(category)) return false;
        return !query || this.searchableText(row).includes(query);
      });
      rows.sort((left, right) => {
        const key = this.sortKey;
        const result = key ? compare(this.sortValue(left, key), this.sortValue(right, key)) :
          timeValue(left, this.kind) - timeValue(right, this.kind);
        return (key ? this.sortDirection : 'desc') === 'asc' ? result : -result;
      });
      return rows.slice(0, Math.min(this.rowLimit, MAXIMUM_ROWS));
    }

    searchableText(row) {
      const fields = this.kind === 'events' ? [
        valueOf(row, 'eventLabel', 'eventType'), valueOf(row, 'category'), valueOf(row, 'protocol'),
        identifierText(row.from), identifierText(row.to), valueOf(row, 'channel'),
        formatFrequency(valueOf(row, 'frequencyHz')), valueOf(row, 'timeslot'), valueOf(row, 'details')
      ] : [
        valueOf(row, 'protocol'), valueOf(row, 'category'), valueOf(row, 'timeslot'),
        valueOf(row, 'valid'), valueOf(row, 'text'), identifierText(row.identifiers)
      ];
      return fields.join(' ').toLocaleLowerCase();
    }

    sortValue(row, key) {
      if (key === 'time') return timeValue(row, this.kind);
      if (key === 'duration') return numeric(valueOf(row, 'durationMs'));
      if (key === 'event') return valueOf(row, 'eventLabel', 'eventType');
      if (key === 'from') return identifierText(row.from);
      if (key === 'to') return identifierText(row.to);
      if (key === 'channel') return eventChannel(row);
      if (key === 'frequency') return numeric(valueOf(row, 'frequencyHz'));
      if (key === 'details') return valueOf(row, 'details');
      if (key === 'protocol') return valueOf(row, 'protocol');
      if (key === 'timeslot') return numeric(valueOf(row, 'timeslot'), -1);
      if (key === 'valid') return valueOf(row, 'valid') === true ? 1 : 0;
      if (key === 'message') return valueOf(row, 'text');
      return '';
    }

    renderRows() {
      if (!this.tableHost) return;
      const rows = this.visibleRows();
      this.updateFeedState(rows.length);
      if (!rows.length) {
        const empty = element('div', 'live-activity-empty');
        const heading = element('strong', '', 'No visible rows');
        const copy = element('span', '', this.rows.size ?
          'Change the search, filters, row limit, or Clear view state.' :
          `This selected context has not produced any ${this.kind} yet.`);
        empty.append(heading, copy);
        this.tableHost.replaceChildren(empty);
        this.mobileHost.replaceChildren();
        return;
      }
      this.tableHost.replaceChildren(this.renderTable(rows));
      const mobile = document.createDocumentFragment();
      rows.forEach((row) => mobile.append(this.renderMobileRow(row)));
      this.mobileHost.replaceChildren(mobile);
    }

    updateFeedState(visible = null) {
      if (!this.feedCopy) return;
      this.feedCopy.textContent = this.kind === 'events' ?
        'Newest decoded events appear first; active calls update one row.' :
        'Newest decoder messages appear first; Stuff bits are omitted.';
      if (this.paused) {
        this.visibleCount.textContent = `${this.pendingChanges} change${this.pendingChanges === 1 ? '' : 's'} waiting`;
        this.visibleCount.className = 'live-activity-pending';
      } else {
        this.visibleCount.textContent = `${visible ?? this.visibleRows().length} visible · ${this.rows.size} retained`;
        this.visibleCount.className = '';
      }
    }

    columns() {
      return this.kind === 'events' ? [
        ['Time', 'time'], ['Duration', 'duration'], ['Event', 'event'], ['From', 'from'], ['To', 'to'],
        ['Channel / timeslot', 'channel'], ['Frequency (MHz)', 'frequency'], ['Details', 'details']
      ] : [
        ['Time', 'time'], ['Protocol', 'protocol'], ['Timeslot', 'timeslot'], ['Validity', 'valid'],
        ['Message', 'message']
      ];
    }

    renderTable(rows) {
      const wrapper = element('div', 'live-activity-table-scroll');
      const table = element('table', `live-activity-table live-activity-${this.kind}-table`);
      const head = element('thead');
      const headerRow = element('tr');
      for (const [label, key] of this.columns()) {
        const header = element('th');
        const control = button(label, 'live-activity-sort');
        if (this.sortKey === key) {
          header.setAttribute('aria-sort', this.sortDirection === 'asc' ? 'ascending' : 'descending');
          control.append(element('span', 'live-activity-sort-arrow', this.sortDirection === 'asc' ? '▲' : '▼'));
        } else {
          header.setAttribute('aria-sort', 'none');
        }
        control.addEventListener('click', () => this.changeSort(key));
        header.append(control);
        headerRow.append(header);
      }
      head.append(headerRow);
      const body = element('tbody');
      const fragment = document.createDocumentFragment();
      rows.forEach((row) => fragment.append(this.kind === 'events' ? this.eventRow(row) : this.messageRow(row)));
      body.append(fragment);
      table.append(head, body);
      wrapper.append(table);
      return wrapper;
    }

    eventRow(row) {
      const line = element('tr');
      const category = String(valueOf(row, 'category') ?? 'other');
      line.append(
        this.cell(formatTime(valueOf(row, 'timeStartMs')), 'live-activity-mono'),
        this.cell(formatDuration(valueOf(row, 'durationMs')), 'live-activity-mono'),
        this.pillCell(valueOf(row, 'eventLabel', 'eventType') || 'Unknown', category),
        this.identityCell(row.from), this.identityCell(row.to),
        this.cell(eventChannel(row)),
        this.cell(formatFrequency(valueOf(row, 'frequencyHz')), 'live-activity-mono'),
        this.cell(valueOf(row, 'details') || '—')
      );
      return line;
    }

    messageRow(row) {
      const line = element('tr', valueOf(row, 'valid') === false ? 'invalid' : '');
      line.append(
        this.cell(formatTime(valueOf(row, 'timestampMs')), 'live-activity-mono'),
        this.cell(valueOf(row, 'protocol') || 'Unknown'),
        this.cell(formatTimeslot(valueOf(row, 'timeslot')), 'live-activity-mono'),
        this.pillCell(valueOf(row, 'valid') === false ? 'Invalid' : 'Valid',
          valueOf(row, 'valid') === false ? 'invalid' : 'valid'),
        this.cell(valueOf(row, 'text') || '—', 'live-activity-message-text live-activity-mono')
      );
      return line;
    }

    cell(value, className) {
      const cell = element('td', className, value);
      if (value && String(value).length > 28) cell.title = String(value);
      return cell;
    }

    identityCell(identifiers) {
      const cell = element('td', 'live-activity-identity');
      const values = asArray(identifiers);
      cell.append(element('strong', '', identifierText(values)));
      const forms = values.map((identifier) => valueOf(identifier, 'form', 'identifierClass')).filter(Boolean);
      if (forms.length) cell.append(element('span', '', [...new Set(forms)].slice(0, 2).join(' · ')));
      return cell;
    }

    pillCell(label, category) {
      const cell = element('td');
      const safeCategory = String(category || 'other').toLowerCase().replace(/[^a-z0-9-]/g, '-');
      cell.append(element('span', `live-activity-pill category-${safeCategory}`, label));
      return cell;
    }

    renderMobileRow(row) {
      const card = element('article', 'live-activity-card');
      const top = element('div', 'live-activity-card-top');
      const title = this.kind === 'events' ? valueOf(row, 'eventLabel', 'eventType') : valueOf(row, 'protocol');
      top.append(element('strong', '', title || 'Unknown'),
        element('span', 'live-activity-mono', formatTime(timeValue(row, this.kind))));
      const main = element('div', this.kind === 'messages' ? 'live-activity-mono' : '',
        this.kind === 'events' ? `${identifierText(row.from)} → ${identifierText(row.to)}` :
          (valueOf(row, 'text') || '—'));
      const meta = element('div', 'live-activity-card-meta');
      if (this.kind === 'events') {
        meta.append(element('span', '', `${formatFrequency(valueOf(row, 'frequencyHz'))} MHz`),
          element('span', '', eventChannel(row)), element('span', '', formatDuration(valueOf(row, 'durationMs'))));
      } else {
        meta.append(element('span', '', valueOf(row, 'valid') === false ? 'Invalid' : 'Valid'),
          element('span', '', `Timeslot ${formatTimeslot(valueOf(row, 'timeslot'))}`),
          element('span', '', identifierText(row.identifiers)));
      }
      card.append(top, main, meta);
      return card;
    }

    changeSort(key) {
      if (this.sortKey !== key) {
        this.sortKey = key;
        this.sortDirection = 'asc';
      } else if (this.sortDirection === 'asc') {
        this.sortDirection = 'desc';
      } else {
        this.sortKey = null;
        this.sortDirection = 'desc';
      }
      this.scheduleRender(true);
    }

    showLogin() {
      this.source?.close();
      this.source = null;
      this.setConnection('Sign-in required', 'state-stale', `${this.kind === 'events' ? 'Events are' : 'Messages are'} administrator-only`);
      this.contextCard.hidden = true;
      const panel = element('div', 'live-activity-login');
      panel.append(element('h2', '', 'Administrator sign-in required'),
        element('p', '', `Sign in with the receiver administrator account to view ${this.kind}.`));
      const form = element('form', 'live-activity-login-form');
      const usernameLabel = element('label');
      usernameLabel.append(element('span', '', 'Username'));
      const username = element('input');
      username.name = 'username';
      username.autocomplete = 'username';
      username.required = true;
      username.maxLength = 256;
      usernameLabel.append(username);
      const passwordLabel = element('label');
      passwordLabel.append(element('span', '', 'Password'));
      const password = element('input');
      password.type = 'password';
      password.name = 'password';
      password.autocomplete = 'current-password';
      password.required = true;
      password.maxLength = 256;
      passwordLabel.append(password);
      const submit = button('Sign in');
      submit.type = 'submit';
      const message = element('span', 'live-activity-login-message');
      message.setAttribute('role', 'status');
      form.append(usernameLabel, passwordLabel, submit, message);
      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        submit.disabled = true;
        message.textContent = 'Signing in…';
        try {
          const response = await fetch('/api/v1/auth/login', {
            method: 'POST', credentials: 'same-origin', cache: 'no-store',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username.value, password: password.value })
          });
          password.value = '';
          if (response.ok) {
            message.textContent = 'Signed in';
            this.contextCard.hidden = false;
            this.showLoading();
            this.load();
            return;
          }
          const result = await response.json().catch(() => ({}));
          if (result.error === 'secure_transport_required') {
            message.textContent = 'Remote sign-in requires HTTPS or a local/SSH-tunneled connection.';
          } else if (response.status === 429) {
            message.textContent = 'Too many attempts. Wait a few minutes, then try again.';
          } else if (response.status === 503) {
            message.textContent = 'Sign-in is busy. Wait a moment, then try again.';
          } else {
            message.textContent = 'Username or password was not accepted.';
          }
        } catch (error) {
          password.value = '';
          message.textContent = 'The receiver could not process sign-in.';
        } finally {
          submit.disabled = false;
        }
      });
      panel.append(form);
      this.activityBody.replaceChildren(panel);
      username.focus();
    }

    showFailure(message) {
      this.setConnection('Unavailable', 'state-stale', 'No current activity received');
      const panel = element('div', 'live-activity-empty');
      panel.append(element('strong', '', 'Activity is unavailable'), element('span', '', message));
      const retry = button('Try again');
      retry.addEventListener('click', () => {
        this.showLoading();
        this.load();
      });
      panel.append(retry);
      this.activityBody.replaceChildren(panel);
    }
  }

  window.LiveActivityView = LiveActivityView;
}());
