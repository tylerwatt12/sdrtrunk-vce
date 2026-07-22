(function() {
  'use strict';

  const MAXIMUM_SYMBOLS = 4800;
  const SIGNAL_UPPER_DB = -20;
  const SIGNAL_MINIMUM_FLOOR_DB = -150;
  const SIGNAL_MAXIMUM_FLOOR_DB = -60;
  const DEFAULT_SIGNAL_FLOOR_DB = -120;
  const SIGNAL_FLOOR_STORAGE_KEY = 'sdrtrunk.narrowband.lowerDisplayLimitDb';
  const MAXIMUM_ZOOM = 16;
  const RECONNECT_MAXIMUM_MILLISECONDS = 10_000;
  let informationControlSequence = 0;
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

  function finite(value, fallback = 0) {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function storedSignalFloor() {
    try {
      const value = Number(window.localStorage.getItem(SIGNAL_FLOOR_STORAGE_KEY));
      return Number.isFinite(value) && value >= SIGNAL_MINIMUM_FLOOR_DB && value <= SIGNAL_MAXIMUM_FLOOR_DB ?
        value : DEFAULT_SIGNAL_FLOOR_DB;
    } catch (error) {
      return DEFAULT_SIGNAL_FLOOR_DB;
    }
  }

  function storeSignalFloor(value) {
    try {
      window.localStorage.setItem(SIGNAL_FLOOR_STORAGE_KEY, String(value));
    } catch (error) {
      // The slider still works for this tab when browser storage is unavailable.
    }
  }

  function safeLong(view, offset) {
    const value = view.getBigUint64(offset, true);
    return value <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(value) : null;
  }

  function formatFrequency(hertz) {
    const value = finite(hertz, NaN);
    return Number.isFinite(value) && value > 0 ? `${(value / 1_000_000).toFixed(6)} MHz` : '—';
  }

  function formatAge(timestamp) {
    if (!timestamp) return '—';
    const age = Math.max(0, performance.now() - timestamp);
    if (age < 950) return `${Math.max(0.1, age / 1000).toFixed(1)} seconds`;
    if (age < 60_000) return `${Math.round(age / 1000)} seconds`;
    return `${Math.round(age / 60_000)} minutes`;
  }

  function activityLabel(activity) {
    return { events: 'Events', messages: 'Messages', signal: 'Signal', symbols: 'Symbols' }[activity] || activity;
  }

  class SelectedChannelDiagnosticsView {
    constructor(host, options = {}) {
      this.host = host;
      this.contextId = String(options.contextId || '');
      this.view = options.view === 'symbols' ? 'symbols' : 'signal';
      this.embedded = options.embedded === true;
      this.onActivityChange = typeof options.onActivityChange === 'function' ? options.onActivityChange : null;
      this.activities = Array.isArray(options.activities) && options.activities.length ?
        [...options.activities] : ['events', 'messages', 'signal', 'symbols'];
      this.closed = false;
      this.hidden = document.hidden;
      this.socket = null;
      this.connectionEpoch = 0;
      this.requestId = 0;
      this.subscribed = false;
      this.protocolReady = false;
      this.reconnectAttempts = 0;
      this.reconnectTimer = null;
      this.renderFrame = null;
      this.ageTimer = null;
      this.worker = null;
      this.context = {};
      this.activeGeneration = null;
      this.state = 'connecting';
      this.paused = false;
      this.lowerLimit = storedSignalFloor();
      this.zoom = 1;
      this.viewportCenter = 0.5;
      this.dragging = false;
      this.dragPointer = null;
      this.dragX = 0;
      this.hover = null;
      this.signalFrame = null;
      this.signalReceivedAt = 0;
      this.signalFrames = 0;
      this.signalDropped = 0;
      this.lastSignalSequence = null;
      this.symbols = new Float32Array(MAXIMUM_SYMBOLS);
      this.symbolWriteIndex = 0;
      this.symbolCount = 0;
      this.symbolReceivedAt = 0;
      this.symbolBatches = 0;
      this.symbolDropped = 0;
      this.lastSymbolSequence = null;
      this.boundVisibility = () => this.onVisibilityChange();
      this.boundResize = () => this.requestRender();
      this.boundDocumentClick = (event) => {
        if (!event.target.closest('.diagnostic-info-button') &&
            !event.target.closest('.diagnostic-help-popover')) this.hideHelp();
      };
      this.build();
      this.createWorker();
      document.addEventListener('visibilitychange', this.boundVisibility);
      document.addEventListener('click', this.boundDocumentClick);
      window.addEventListener('resize', this.boundResize);
      this.connect();
    }

    close() {
      if (this.closed) return;
      this.closed = true;
      window.clearTimeout(this.reconnectTimer);
      window.clearInterval(this.ageTimer);
      window.cancelAnimationFrame(this.renderFrame);
      document.removeEventListener('visibilitychange', this.boundVisibility);
      document.removeEventListener('click', this.boundDocumentClick);
      window.removeEventListener('resize', this.boundResize);
      this.worker?.terminate();
      this.worker = null;
      this.hideHelp();
      this.disconnect('view closed');
    }

    setView(view) {
      const requested = view === 'symbols' ? 'symbols' : 'signal';
      if (requested === this.view || this.closed) return;
      this.view = requested;
      this.paused = false;
      this.hover = null;
      this.updateTabs();
      this.buildView();
      if (this.socket?.readyState === WebSocket.OPEN && this.subscribed) {
        this.send({ action: 'update', requestId: this.nextRequestId(), view: this.view });
        this.setState('binding', `Opening ${activityLabel(this.view)}…`);
      } else {
        this.connect();
      }
    }

    build() {
      this.host.className = `live-activity-root selected-diagnostic-root${this.embedded ?
        ' live-activity-embedded selected-diagnostic-embedded' : ''}`;

      const heading = element('header', 'live-activity-heading');
      const headingCopy = element('div');
      headingCopy.append(element('h1', '', 'Selected-channel diagnostics'),
        element('p', '', 'Live signal and decoder symbols for the channel selected in Live.'));
      const headingAccess = element('span', 'diagnostic-admin-pill', 'Administrator only');
      heading.append(headingCopy, headingAccess);

      this.contextCard = element('section', 'diagnostic-context-card');
      this.contextCard.setAttribute('aria-label', 'Selected channel context');
      this.contextCells = {
        system: this.contextCell('System and site'),
        channel: this.contextCell('Selected channel'),
        frequency: this.contextCell('Frequency'),
        timeslot: this.contextCell('Timeslot'),
        decoder: this.contextCell('Decoder and state')
      };
      Object.values(this.contextCells).forEach((cell) => this.contextCard.append(cell.cell));

      this.notice = element('div', 'diagnostic-notice');
      this.notice.hidden = true;
      this.workspace = element('section', 'live-activity-workspace selected-diagnostic-workspace');
      this.tabs = element('nav', 'live-activity-tabs selected-diagnostic-tabs');
      this.tabs.setAttribute('aria-label', 'Selected channel details');
      this.tabLinks = new Map();
      this.activities.forEach((activity) => {
        const link = element('a', '', activityLabel(activity));
        const target = new URLSearchParams({ view: 'live', context: this.contextId, activity });
        link.href = `/?${target}`;
        link.dataset.activity = activity;
        if (this.onActivityChange) {
          link.addEventListener('click', (event) => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            this.onActivityChange(activity);
          });
        }
        this.tabLinks.set(activity, link);
        this.tabs.append(link);
      });
      this.tabStatus = element('div', 'live-activity-tab-status diagnostic-tab-status');
      this.connection = element('span', 'badge state-stale', 'Connecting');
      this.workspaceOwner = element('span', 'diagnostic-workspace-pill', 'One diagnostic workspace');
      this.tabStatus.append(this.connection, this.workspaceOwner);
      this.tabs.append(this.tabStatus);
      this.body = element('div', 'selected-diagnostic-body');
      this.workspace.append(this.tabs, this.body);

      this.statsDialog = this.createStatsDialog();
      this.helpPopover = element('div', 'diagnostic-help-popover');
      this.helpPopover.id = `diagnostic-help-${++informationControlSequence}`;
      this.helpPopover.setAttribute('role', 'tooltip');
      this.helpPopover.hidden = true;
      this.host.replaceChildren(...(this.embedded ? [this.notice, this.workspace, this.statsDialog, this.helpPopover] :
        [heading, this.contextCard, this.notice, this.workspace, this.statsDialog, this.helpPopover]));
      this.updateTabs();
      this.buildView();
      this.ageTimer = window.setInterval(() => this.updateReadouts(), 1000);
    }

    contextCell(label) {
      const cell = element('div', 'diagnostic-context-cell');
      const name = element('span', '', label);
      const value = element('strong', '', '—');
      cell.append(name, value);
      return { cell, value };
    }

    updateTabs() {
      this.tabLinks?.forEach((link, activity) => {
        const active = activity === this.view;
        link.classList.toggle('active', active);
        if (active) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
      });
    }

    buildView() {
      this.body.replaceChildren();
      if (this.view === 'symbols') this.buildSymbolsView();
      else this.buildSignalView();
      this.updateReadouts();
      this.requestRender();
    }

    toolbarButton(label, handler, title) {
      const value = button(label, 'secondary');
      if (title) value.title = title;
      value.addEventListener('click', handler);
      return value;
    }

    infoButton(text) {
      const value = button('i', 'diagnostic-info-button');
      value.setAttribute('aria-label', `More information: ${text}`);
      value.setAttribute('aria-expanded', 'false');
      const show = () => {
        this.hideHelp();
        this.helpOwner = value;
        value.setAttribute('aria-describedby', this.helpPopover.id);
        value.setAttribute('aria-expanded', 'true');
        const rect = value.getBoundingClientRect();
        this.helpPopover.textContent = text;
        this.helpPopover.hidden = false;
        const left = Math.min(window.innerWidth - this.helpPopover.offsetWidth - 12, Math.max(12, rect.left));
        let top = rect.bottom + 7;
        if (top + this.helpPopover.offsetHeight > window.innerHeight - 12) {
          top = rect.top - this.helpPopover.offsetHeight - 7;
        }
        this.helpPopover.style.left = `${left}px`;
        this.helpPopover.style.top = `${Math.max(8, top)}px`;
      };
      value.addEventListener('pointerenter', show);
      value.addEventListener('pointerleave', () => {
        if (document.activeElement !== value) this.hideHelp();
      });
      value.addEventListener('focus', show);
      value.addEventListener('blur', () => this.hideHelp());
      value.addEventListener('click', (event) => {
        event.stopPropagation();
        show();
      });
      value.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          this.hideHelp();
        }
      });
      return value;
    }

    hideHelp() {
      if (!this.helpPopover) return;
      this.helpPopover.hidden = true;
      if (this.helpOwner) {
        this.helpOwner.setAttribute('aria-expanded', 'false');
        this.helpOwner.removeAttribute('aria-describedby');
      }
      this.helpOwner = null;
    }

    buildSignalView() {
      const toolbar = element('div', 'diagnostic-toolbar');
      this.pauseButton = this.toolbarButton('Pause', () => this.togglePause(),
        'Freeze this browser display while the bounded latest frame continues to replace older work.');
      const reset = this.toolbarButton('Reset view', () => {
        this.zoom = 1;
        this.viewportCenter = 0.5;
        this.requestRender();
      });
      const spacer = element('span', 'diagnostic-toolbar-spacer');
      const floor = element('label', 'diagnostic-slider-field');
      floor.append(element('span', '', 'Lower display limit'));
      this.floorInput = element('input');
      this.floorInput.type = 'range';
      this.floorInput.min = String(SIGNAL_MINIMUM_FLOOR_DB);
      this.floorInput.max = String(SIGNAL_MAXIMUM_FLOOR_DB);
      this.floorInput.step = '5';
      this.floorInput.value = String(this.lowerLimit);
      this.floorOutput = element('output', '', `${this.lowerLimit} dB`);
      this.floorInput.addEventListener('input', () => {
        this.lowerLimit = finite(this.floorInput.value, DEFAULT_SIGNAL_FLOOR_DB);
        this.floorOutput.textContent = `${this.lowerLimit} dB`;
        storeSignalFloor(this.lowerLimit);
        this.requestRender();
      });
      floor.append(this.floorInput, this.floorOutput);
      const stats = this.toolbarButton('Technical details', () => this.openStats());
      toolbar.append(this.pauseButton,
        this.infoButton('Pause only freezes what this browser draws. Decoder work and the latest-only safety limits do not change.'),
        reset, spacer, floor,
        this.infoButton('This changes contrast in this browser only. It does not alter tuner gain or decoder measurements.'), stats);

      const layout = element('div', 'diagnostic-chart-layout');
      const card = element('section', 'diagnostic-chart-card');
      const head = element('header', 'diagnostic-chart-head');
      head.append(element('strong', '', 'Channel spectrum'),
        element('span', '', '+/− to zoom · drag or arrow keys to pan'));
      const wrap = element('div', 'diagnostic-canvas-wrap');
      this.canvas = element('canvas', 'diagnostic-canvas');
      this.canvas.tabIndex = 0;
      this.canvas.setAttribute('aria-label',
        'Channel FFT. Scroll normally, use plus and minus to zoom, arrow keys to pan, and R to reset.');
      this.cursorPopup = element('div', 'diagnostic-cursor-popup');
      this.cursorPopup.hidden = true;
      wrap.append(this.canvas, this.cursorPopup);
      card.append(head, wrap);
      this.bindSignalCanvas();

      this.readouts = this.createReadouts([
        ['frequency', 'Viewed frequency'], ['power', 'Current power'], ['peak', 'Peak power'],
        ['decoder', 'Decoder'], ['rate', 'Sample rate'], ['age', 'Measurement age']
      ]);
      layout.append(card, this.readouts.host);
      this.body.append(toolbar, layout);
    }

    buildSymbolsView() {
      const toolbar = element('div', 'diagnostic-toolbar');
      this.pauseButton = this.toolbarButton('Pause', () => this.togglePause(),
        'Freeze this browser display while incoming symbol batches remain bounded.');
      const clear = this.toolbarButton('Clear display', () => {
        this.symbolCount = 0;
        this.symbolWriteIndex = 0;
        this.requestRender();
        this.updateReadouts();
      });
      const spacer = element('span', 'diagnostic-toolbar-spacer');
      const limit = element('span', 'diagnostic-workspace-pill', '4,800 visible points maximum');
      const stats = this.toolbarButton('Technical details', () => this.openStats());
      toolbar.append(this.pauseButton,
        this.infoButton('Pause freezes this browser graph. New telemetry never waits on the browser and older batches are discarded first.'),
        clear,
        this.infoButton('This graph shows demodulated symbol phase. It is available only for decoders that expose symbol telemetry.'),
        spacer, limit, stats);

      const layout = element('div', 'diagnostic-chart-layout');
      const card = element('section', 'diagnostic-chart-card');
      const head = element('header', 'diagnostic-chart-head');
      this.symbolTitle = element('strong', '', 'Demodulated symbols');
      head.append(this.symbolTitle, element('span', '', 'Left-to-right sweep · −π to π'));
      const wrap = element('div', 'diagnostic-canvas-wrap');
      this.canvas = element('canvas', 'diagnostic-canvas diagnostic-symbol-canvas');
      this.canvas.tabIndex = 0;
      this.canvas.setAttribute('aria-label',
        'Demodulated symbol phase graph scanning from left to right with a marker at the latest symbol.');
      this.canvas.addEventListener('contextmenu', (event) => {
        event.preventDefault();
        this.openStats();
      });
      wrap.append(this.canvas);
      card.append(head, wrap);
      this.readouts = this.createReadouts([
        ['protocol', 'Protocol'], ['timeslot', 'Timeslot'], ['points', 'Visible points'],
        ['batch', 'Last batch'], ['delivery', 'Delivery'], ['age', 'Measurement age']
      ]);
      layout.append(card, this.readouts.host);
      this.body.append(toolbar, layout);
    }

    createReadouts(definitions) {
      const host = element('aside', 'diagnostic-readouts');
      const values = {};
      definitions.forEach(([key, label]) => {
        const row = element('div', 'diagnostic-readout');
        const name = element('span', '', label);
        const output = element('strong', '', '—');
        row.append(name, output);
        host.append(row);
        values[key] = output;
      });
      return { host, values };
    }

    createStatsDialog() {
      const dialog = element('dialog', 'diagnostic-stats-dialog');
      const head = element('header', 'diagnostic-dialog-head');
      const copy = element('div');
      copy.append(element('h2', '', 'Technical details'),
        element('p', '', 'Bounded delivery and browser rendering details.'));
      const close = button('×', 'diagnostic-dialog-close');
      close.setAttribute('aria-label', 'Close');
      close.addEventListener('click', () => dialog.close());
      head.append(copy, close);
      this.statsList = element('dl', 'diagnostic-stats-list');
      const body = element('div', 'diagnostic-dialog-body');
      body.append(this.statsList);
      const foot = element('footer', 'diagnostic-dialog-foot');
      const done = button('Done', 'primary');
      done.addEventListener('click', () => dialog.close());
      foot.append(done);
      dialog.append(head, body, foot);
      return dialog;
    }

    openStats() {
      const rows = this.view === 'signal' ? [
        ['Active view', 'Channel FFT'],
        ['Received frames', this.signalFrames],
        ['FFT bins', this.signalFrame?.metadata?.bins ?? '—'],
        ['FFT size', this.signalFrame?.metadata?.fftSize ?? '—'],
        ['Local zoom', `${this.zoom.toFixed(2)}×`],
        ['Detected gaps', this.signalDropped]
      ] : [
        ['Active view', 'Symbols'],
        ['Received batches', this.symbolBatches],
        ['Visible points', this.symbolCount],
        ['Maximum points', MAXIMUM_SYMBOLS],
        ['Detected gaps', this.symbolDropped],
        ['Queue policy', 'Bounded · drop oldest']
      ];
      rows.push(['Workspace', 'One administrator tab'], ['Database writes', 'None']);
      const fragment = document.createDocumentFragment();
      rows.forEach(([label, value]) => {
        fragment.append(element('dt', '', label), element('dd', '', value));
      });
      this.statsList.replaceChildren(fragment);
      if (typeof this.statsDialog.showModal === 'function') this.statsDialog.showModal();
      else this.statsDialog.setAttribute('open', '');
    }

    createWorker() {
      try {
        this.worker = new Worker('/assets/signal-worker.js?v=3');
        this.worker.onmessage = (event) => this.onWorkerMessage(event.data);
        this.worker.onmessageerror = () => this.showNotice('The browser could not read a signal frame.');
      } catch (error) {
        this.showStateCard('Signal renderer unavailable',
          'This browser could not start the bounded FFT decoder. Events and Messages remain available.');
      }
    }

    webSocketUrl() {
      const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      return `${scheme}//${window.location.host}/api/v1/ws/channel-diagnostics`;
    }

    connect() {
      if (this.closed || this.hidden || this.socket || !this.contextId) return;
      window.clearTimeout(this.reconnectTimer);
      this.setState('connecting', 'Connecting');
      const epoch = ++this.connectionEpoch;
      const socket = new WebSocket(this.webSocketUrl());
      socket.binaryType = 'arraybuffer';
      this.socket = socket;
      this.subscribed = false;
      this.protocolReady = false;
      socket.onmessage = (event) => {
        if (this.closed || socket !== this.socket || epoch !== this.connectionEpoch) return;
        if (event.data instanceof ArrayBuffer) this.onBinary(event.data, epoch);
        else if (typeof event.data === 'string') this.onStateMessage(event.data);
      };
      socket.onclose = (event) => this.onClose(socket, event);
      socket.onerror = () => {
        if (socket === this.socket) this.setState('reconnecting', 'Connection interrupted');
      };
    }

    disconnect(reason) {
      const socket = this.socket;
      this.socket = null;
      this.subscribed = false;
      this.protocolReady = false;
      this.connectionEpoch += 1;
      if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
        try {
          socket.close(1000, reason);
        } catch (error) {
          // The browser may reject a close reason while the upgrade is still opaque.
        }
      }
    }

    onVisibilityChange() {
      this.hidden = document.hidden;
      if (this.hidden) {
        window.clearTimeout(this.reconnectTimer);
        this.disconnect('page hidden');
        this.setState('paused', 'Released while hidden');
      } else {
        this.reconnectAttempts = 0;
        this.connect();
      }
    }

    nextRequestId() {
      this.requestId += 1;
      return this.requestId;
    }

    send(payload) {
      if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(payload));
    }

    subscribe() {
      if (this.subscribed || this.socket?.readyState !== WebSocket.OPEN || !this.protocolReady) return;
      this.subscribed = true;
      this.send({
        action: 'subscribe', requestId: this.nextRequestId(), selectionId: this.contextId, view: this.view
      });
      this.setState('binding', `Opening ${activityLabel(this.view)}…`);
    }

    onStateMessage(encoded) {
      let message;
      try {
        message = JSON.parse(encoded);
      } catch (error) {
        return;
      }
      if (message?.type !== 'channel-diagnostics-state') return;
      if (message.state === 'ready') {
        this.protocolReady = true;
        this.subscribe();
        return;
      }
      const generation = Number(message.generation);
      if (Number.isSafeInteger(generation) && generation >= 0) {
        if (this.activeGeneration !== null && generation !== this.activeGeneration) {
          this.resetTelemetry();
        }
        this.activeGeneration = generation;
      }
      this.updateContext(message);
      if (message.view && ['signal', 'symbols'].includes(message.view) && message.view !== this.view) return;
      if (message.state === 'binding') {
        this.setState('binding', `Opening ${activityLabel(this.view)}…`);
      } else if (message.state === 'live') {
        this.reconnectAttempts = 0;
        this.hideNotice();
        this.setState(this.paused ? 'paused' : 'live', this.paused ? 'Display paused' : 'Live');
      } else if (message.state === 'unsupported') {
        this.setState('unsupported', 'Unavailable for this decoder');
        this.showUnsupported();
      } else if (message.state === 'ended') {
        this.setState('ended', 'Selected channel ended');
        this.showStateCard('Selected channel ended',
          'This exact traffic-channel context no longer exists. Choose a current row in Live.');
      } else if (message.state === 'degraded') {
        this.setState('degraded', 'Newest bounded data continues');
        this.showNotice(message.message || 'Some display work was dropped to keep decoder processing clear.');
      }
      if (Number.isFinite(Number(message.dropped))) {
        if (this.view === 'signal') this.signalDropped = Math.max(this.signalDropped, Number(message.dropped));
        else this.symbolDropped = Math.max(this.symbolDropped, Number(message.dropped));
      }
      this.updateReadouts();
    }

    updateContext(message) {
      const fields = ['tableTitle', 'channelName', 'frequencyHz', 'timeslot', 'decoder', 'protocol', 'sampleRateHz',
        'generation'];
      fields.forEach((field) => {
        if (message[field] !== undefined && message[field] !== null) this.context[field] = message[field];
      });
      if (!this.contextCells) return;
      this.contextCells.system.value.textContent = this.context.tableTitle || '—';
      this.contextCells.channel.value.textContent = this.context.channelName || '—';
      this.contextCells.frequency.value.textContent = formatFrequency(this.context.frequencyHz);
      this.contextCells.timeslot.value.textContent = this.context.timeslot === undefined || this.context.timeslot === null ?
        'No timeslot' : `Timeslot ${this.context.timeslot}`;
      this.contextCells.decoder.value.textContent = `${this.context.decoder || this.context.protocol || '—'} · ${this.state}`;
    }

    onBinary(buffer, epoch) {
      if (buffer.byteLength < 4) return;
      const bytes = new Uint8Array(buffer, 0, 4);
      if (bytes[0] === 83 && bytes[1] === 70 && bytes[2] === 70 && bytes[3] === 84) {
        if (this.view !== 'signal' || !this.worker) return;
        this.worker.postMessage({ type: 'frame', buffer, connectionEpoch: epoch }, [buffer]);
      } else if (bytes[0] === 83 && bytes[1] === 83 && bytes[2] === 89 && bytes[3] === 77) {
        if (this.view === 'symbols') this.onSymbolFrame(buffer);
      }
    }

    onWorkerMessage(message) {
      if (this.closed || this.view !== 'signal' || message?.connectionEpoch !== this.connectionEpoch) return;
      if (message.type === 'error') {
        this.showNotice(message.message || 'A signal frame could not be decoded.');
        return;
      }
      if (message.type !== 'frame' || !(message.buffer instanceof ArrayBuffer)) return;
      const metadata = message.metadata || {};
      if (this.activeGeneration !== null && metadata.generation !== this.activeGeneration) return;
      const bins = new Float32Array(message.buffer, metadata.headerBytes, metadata.bins);
      if (this.lastSignalSequence !== null && metadata.sequence > this.lastSignalSequence + 1) {
        this.signalDropped += metadata.sequence - this.lastSignalSequence - 1;
      }
      this.lastSignalSequence = metadata.sequence;
      this.signalFrame = { metadata, bins };
      this.signalFrames += 1;
      this.signalReceivedAt = performance.now();
      this.context.frequencyHz = this.context.frequencyHz || metadata.centerHz;
      this.context.sampleRateHz = metadata.sampleRateHz;
      this.setState(this.paused ? 'paused' : 'live', this.paused ? 'Display paused' : 'Live');
      this.updateReadouts();
      this.updateCursorPopup();
      if (!this.paused) this.requestRender();
    }

    onSymbolFrame(buffer) {
      try {
        const view = new DataView(buffer);
        if (view.byteLength < 48 || view.getUint16(4, true) !== 1 || view.getUint16(6, true) !== 48 ||
            view.getUint8(40) !== 1 || view.getUint8(41) !== 0 || view.getUint8(42) !== 0 ||
            view.getUint8(43) !== 0) throw new Error('Unsupported symbols frame');
        const generation = safeLong(view, 12);
        const sequence = safeLong(view, 20);
        const count = view.getUint32(36, true);
        const payloadBytes = view.getUint32(44, true);
        if (generation === null || sequence === null || count > 120 || payloadBytes !== count * 4 ||
            view.byteLength !== 48 + payloadBytes) throw new Error('Invalid symbols frame');
        if (this.activeGeneration !== null && generation !== this.activeGeneration) return;
        const values = new Float32Array(buffer, 48, count);
        if (this.lastSymbolSequence !== null && sequence > this.lastSymbolSequence + 1) {
          this.symbolDropped += sequence - this.lastSymbolSequence - 1;
        }
        this.lastSymbolSequence = sequence;
        for (let index = 0; index < values.length; index += 1) {
          const value = values[index];
          if (!Number.isFinite(value)) continue;
          this.symbols[this.symbolWriteIndex] = Math.max(-Math.PI, Math.min(Math.PI, value));
          this.symbolWriteIndex = (this.symbolWriteIndex + 1) % MAXIMUM_SYMBOLS;
          this.symbolCount = Math.min(MAXIMUM_SYMBOLS, this.symbolCount + 1);
        }
        this.symbolBatches += 1;
        this.symbolReceivedAt = performance.now();
        this.setState(this.paused ? 'paused' : 'live', this.paused ? 'Display paused' : 'Live');
        this.updateReadouts();
        if (!this.paused) this.requestRender();
      } catch (error) {
        this.showNotice(error.message || 'A symbols frame could not be decoded.');
      }
    }

    async onClose(socket, event) {
      if (socket !== this.socket) return;
      this.socket = null;
      this.subscribed = false;
      this.protocolReady = false;
      if (this.closed || this.hidden) return;
      if (event.code === 4401 || event.code === 4403) {
        this.showSignedOut();
        return;
      }
      if (event.code === 4411) {
        this.setState('ended', 'Selected channel ended');
        this.showStateCard('Selected channel ended',
          'This exact channel is no longer running. Select another current row in Live.');
        return;
      }
      if (event.code === 4409 || event.code === 4429 || event.code === 1013) {
        this.showBusy();
        return;
      }
      const resolution = await this.resolveOpaqueClose();
      if (this.closed || this.hidden || this.socket) return;
      if (resolution === 'signed-out') this.showSignedOut();
      else if (resolution === 'busy') this.showBusy();
      else this.scheduleReconnect();
    }

    resetTelemetry() {
      this.signalFrame = null;
      this.signalReceivedAt = 0;
      this.signalFrames = 0;
      this.signalDropped = 0;
      this.lastSignalSequence = null;
      this.symbolWriteIndex = 0;
      this.symbolCount = 0;
      this.symbolReceivedAt = 0;
      this.symbolBatches = 0;
      this.symbolDropped = 0;
      this.lastSymbolSequence = null;
      this.hover = null;
      if (this.cursorPopup) this.cursorPopup.hidden = true;
      this.updateReadouts();
      this.requestRender();
    }

    async resolveOpaqueClose() {
      try {
        const response = await fetch('/api/v1/auth/session', { cache: 'no-store', credentials: 'same-origin' });
        if (!response.ok) return 'signed-out';
        const session = await response.json();
        if (session.authenticated !== true) return 'signed-out';

        const statusResponse = await fetch('/api/status', {
          cache: 'no-store', credentials: 'same-origin'
        });
        if (!statusResponse.ok) return null;
        const status = await statusResponse.json();
        if (status.selectedChannelDiagnostics?.workspaceActive === true ||
            Number(status.signal?.sessions || 0) > 0) return 'busy';
        return null;
      } catch (error) {
        return null;
      }
    }

    scheduleReconnect() {
      this.setState('reconnecting', 'Reconnecting');
      const delay = Math.min(RECONNECT_MAXIMUM_MILLISECONDS,
        500 * (2 ** Math.min(this.reconnectAttempts++, 4)));
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = window.setTimeout(() => this.connect(), delay);
    }

    showBusy() {
      this.setState('busy', 'Diagnostic workspace in use');
      this.showStateCard('Signal workspace is already open',
        'Another administrator tab owns the one bounded diagnostic workspace. Close it, then try again.', true);
    }

    showSignedOut() {
      this.setState('locked', 'Administrator sign-in required');
      this.showStateCard('Administrator session ended',
        'Diagnostic telemetry stopped and the workspace was released. Sign in again through Events or Messages.');
      window.dispatchEvent(new CustomEvent('sdrtrunk:auth-changed', { detail: { authenticated: false } }));
    }

    showUnsupported() {
      const decoder = this.context.decoder || this.context.protocol || 'This decoder';
      this.showStateCard('This decoder does not provide symbols',
        `${decoder} does not expose the demodulated symbol stream used by this graph. Signal remains available.`,
        false, () => this.onActivityChange ? this.onActivityChange('signal') : this.setView('signal'));
    }

    showStateCard(title, message, retry = false, alternate = null) {
      const card = element('div', 'diagnostic-state-card');
      card.append(element('h2', '', title), element('p', '', message));
      const actions = element('div', 'diagnostic-state-actions');
      if (retry) {
        const retryButton = button('Try again', 'primary');
        retryButton.addEventListener('click', () => {
          this.reconnectAttempts = 0;
          this.buildView();
          this.connect();
        });
        actions.append(retryButton);
      }
      if (alternate) {
        const signal = button('Open Signal', 'primary');
        signal.addEventListener('click', alternate);
        actions.append(signal);
      }
      if (actions.childNodes.length) card.append(actions);
      this.body.replaceChildren(card);
    }

    showNotice(message) {
      this.notice.textContent = message;
      this.notice.hidden = false;
    }

    hideNotice() {
      this.notice.hidden = true;
      this.notice.textContent = '';
    }

    setState(state, label) {
      this.state = state;
      if (!this.connection) return;
      this.connection.textContent = label;
      const good = state === 'live';
      const bad = ['locked', 'busy', 'ended', 'unsupported'].includes(state);
      this.connection.className = `badge ${good ? 'state-current' : (bad ? 'diagnostic-state-bad' : 'state-stale')}`;
      if (this.contextCells) {
        this.contextCells.decoder.value.textContent =
          `${this.context.decoder || this.context.protocol || '—'} · ${label}`;
      }
    }

    togglePause() {
      this.paused = !this.paused;
      if (this.pauseButton) this.pauseButton.textContent = this.paused ? 'Resume' : 'Pause';
      this.setState(this.paused ? 'paused' : 'live', this.paused ? 'Display paused' : 'Live');
      if (!this.paused) this.requestRender();
    }

    updateReadouts() {
      if (!this.readouts?.values) return;
      const values = this.readouts.values;
      if (this.view === 'signal') {
        const bins = this.signalFrame?.bins;
        let peak = Number.NEGATIVE_INFINITY;
        if (bins) {
          for (let index = 0; index < bins.length; index += 1) peak = Math.max(peak, bins[index]);
        }
        const center = bins?.length ? bins[Math.floor(bins.length / 2)] : NaN;
        values.frequency.textContent = formatFrequency(this.context.frequencyHz || this.signalFrame?.metadata?.centerHz);
        values.power.textContent = Number.isFinite(center) ? `${center.toFixed(1)} dB` : '—';
        values.peak.textContent = Number.isFinite(peak) ? `${peak.toFixed(1)} dB` : '—';
        values.decoder.textContent = this.context.decoder || this.context.protocol || '—';
        values.rate.textContent = this.context.sampleRateHz ?
          `${(this.context.sampleRateHz / 1000).toFixed(this.context.sampleRateHz < 100_000 ? 1 : 0)} kHz` : '—';
        values.age.textContent = formatAge(this.signalReceivedAt);
      } else {
        values.protocol.textContent = this.context.protocol || this.context.decoder || '—';
        values.timeslot.textContent = this.context.timeslot === undefined || this.context.timeslot === null ?
          'No timeslot' : `Timeslot ${this.context.timeslot}`;
        values.points.textContent = this.symbolCount.toLocaleString();
        values.batch.textContent = this.symbolBatches ? 'Up to 120 points' : '—';
        values.delivery.textContent = this.symbolDropped ? `${this.symbolDropped} gaps detected` : 'Current';
        values.age.textContent = formatAge(this.symbolReceivedAt);
        if (this.symbolTitle) this.symbolTitle.textContent =
          `${this.context.protocol || this.context.decoder || 'Decoder'} demodulated symbols`;
      }
    }

    bindSignalCanvas() {
      const canvas = this.canvas;
      canvas.addEventListener('pointerdown', (event) => {
        if (this.zoom <= 1) return;
        this.dragging = true;
        this.dragPointer = event.pointerId;
        this.dragX = event.clientX;
        canvas.setPointerCapture(event.pointerId);
        canvas.classList.add('dragging');
      });
      canvas.addEventListener('pointermove', (event) => {
        const rect = canvas.getBoundingClientRect();
        this.hover = {
          x: Math.max(0, Math.min(rect.width, event.clientX - rect.left)),
          y: Math.max(0, Math.min(rect.height, event.clientY - rect.top))
        };
        if (this.dragging && event.pointerId === this.dragPointer) {
          const delta = event.clientX - this.dragX;
          this.dragX = event.clientX;
          const span = 1 / this.zoom;
          this.viewportCenter = this.clampCenter(this.viewportCenter - (delta / rect.width) * span);
        }
        this.updateCursorPopup();
        this.requestRender();
      });
      const endDrag = () => {
        this.dragging = false;
        this.dragPointer = null;
        canvas.classList.remove('dragging');
      };
      canvas.addEventListener('pointerup', endDrag);
      canvas.addEventListener('pointercancel', endDrag);
      canvas.addEventListener('pointerleave', () => {
        endDrag();
        this.hover = null;
        if (this.cursorPopup) this.cursorPopup.hidden = true;
        this.requestRender();
      });
      canvas.addEventListener('keydown', (event) => {
        if (event.key === '+' || event.key === '=') this.zoomAt(0.5, 1.25);
        else if (event.key === '-') this.zoomAt(0.5, 0.8);
        else if (event.key === 'ArrowLeft') this.pan(-0.08);
        else if (event.key === 'ArrowRight') this.pan(0.08);
        else if (event.key.toLowerCase() === 'r') {
          this.zoom = 1;
          this.viewportCenter = 0.5;
          this.requestRender();
        } else return;
        event.preventDefault();
      });
      canvas.addEventListener('contextmenu', (event) => {
        event.preventDefault();
        this.openStats();
      });
    }

    zoomAt(anchor, multiplier) {
      const oldSpan = 1 / this.zoom;
      const oldStart = this.viewportCenter - oldSpan / 2;
      const nextZoom = Math.max(1, Math.min(MAXIMUM_ZOOM, this.zoom * multiplier));
      const nextSpan = 1 / nextZoom;
      const anchorPosition = oldStart + anchor * oldSpan;
      this.zoom = nextZoom;
      this.viewportCenter = this.clampCenter(anchorPosition + (0.5 - anchor) * nextSpan);
      this.requestRender();
    }

    pan(direction) {
      if (this.zoom <= 1) return;
      this.viewportCenter = this.clampCenter(this.viewportCenter + direction / this.zoom);
      this.requestRender();
    }

    clampCenter(center) {
      const half = 0.5 / this.zoom;
      return Math.max(half, Math.min(1 - half, center));
    }

    requestRender() {
      if (this.closed || this.renderFrame !== null) return;
      this.renderFrame = window.requestAnimationFrame(() => {
        this.renderFrame = null;
        if (this.view === 'signal') this.drawSignal();
        else this.drawSymbols();
      });
    }

    fitCanvas() {
      if (!this.canvas?.isConnected) return null;
      const rect = this.canvas.getBoundingClientRect();
      if (!rect.width || !rect.height) return null;
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const width = Math.max(320, Math.round(rect.width * dpr));
      const height = Math.max(220, Math.round(rect.height * dpr));
      if (this.canvas.width !== width || this.canvas.height !== height) {
        this.canvas.width = width;
        this.canvas.height = height;
      }
      return { context: this.canvas.getContext('2d'), width, height, dpr, rect };
    }

    drawSignal() {
      const fit = this.fitCanvas();
      if (!fit) return;
      const { context, width, height, dpr } = fit;
      const padding = { left: 58 * dpr, right: 16 * dpr, top: 17 * dpr, bottom: 40 * dpr };
      const plotWidth = width - padding.left - padding.right;
      const plotHeight = height - padding.top - padding.bottom;
      context.fillStyle = '#071118';
      context.fillRect(0, 0, width, height);
      context.font = `${11 * dpr}px ui-monospace, SFMono-Regular, Consolas, monospace`;
      context.textBaseline = 'middle';
      context.textAlign = 'right';
      const range = Math.max(1, SIGNAL_UPPER_DB - this.lowerLimit);
      for (let db = Math.ceil(this.lowerLimit / 20) * 20; db <= SIGNAL_UPPER_DB; db += 20) {
        const y = padding.top + (SIGNAL_UPPER_DB - db) / range * plotHeight;
        context.strokeStyle = '#253946';
        context.lineWidth = dpr;
        context.beginPath();
        context.moveTo(padding.left, y);
        context.lineTo(width - padding.right, y);
        context.stroke();
        context.fillStyle = '#9fb2bf';
        context.fillText(`${db} dB`, padding.left - 7 * dpr, y);
      }

      const frame = this.signalFrame;
      if (frame?.bins?.length) {
        const span = 1 / this.zoom;
        const startRatio = this.viewportCenter - span / 2;
        const endRatio = startRatio + span;
        const startIndex = Math.max(0, Math.floor(startRatio * (frame.bins.length - 1)));
        const endIndex = Math.min(frame.bins.length - 1,
          Math.max(startIndex + 1, Math.ceil(endRatio * (frame.bins.length - 1))));
        const count = endIndex - startIndex;
        const point = (index) => {
          const value = frame.bins[index];
          return {
            x: padding.left + ((index - startIndex) / count) * plotWidth,
            y: padding.top + Math.max(0, Math.min(1, (SIGNAL_UPPER_DB - value) / range)) * plotHeight
          };
        };
        context.beginPath();
        for (let index = startIndex; index <= endIndex; index += 1) {
          const current = point(index);
          if (index === startIndex) context.moveTo(current.x, current.y);
          else context.lineTo(current.x, current.y);
        }
        context.lineTo(width - padding.right, padding.top + plotHeight);
        context.lineTo(padding.left, padding.top + plotHeight);
        context.closePath();
        context.fillStyle = 'rgba(48, 178, 106, .24)';
        context.fill();
        context.beginPath();
        for (let index = startIndex; index <= endIndex; index += 1) {
          const current = point(index);
          if (index === startIndex) context.moveTo(current.x, current.y);
          else context.lineTo(current.x, current.y);
        }
        context.strokeStyle = '#65d48c';
        context.lineWidth = 1.5 * dpr;
        context.stroke();

        context.textAlign = 'center';
        context.textBaseline = 'top';
        context.fillStyle = '#9fb2bf';
        for (let tick = 0; tick < 5; tick += 1) {
          const ratio = startRatio + span * tick / 4;
          const frequency = this.frequencyAtFrameRatio(ratio);
          const x = padding.left + plotWidth * tick / 4;
          context.fillText(frequency > 0 ? (frequency / 1_000_000).toFixed(6) : '—', x,
            padding.top + plotHeight + 8 * dpr);
        }
        context.fillText('MHz', padding.left + plotWidth / 2, padding.top + plotHeight + 24 * dpr);
      }

      if (this.hover) {
        const x = Math.max(padding.left, Math.min(width - padding.right, this.hover.x * dpr));
        context.strokeStyle = '#f2cf66';
        context.lineWidth = dpr;
        context.beginPath();
        context.moveTo(x, padding.top);
        context.lineTo(x, padding.top + plotHeight);
        context.stroke();
      }
    }

    frequencyAtFrameRatio(ratio) {
      const metadata = this.signalFrame?.metadata;
      if (!metadata) return 0;
      const fftSize = Math.max(1, finite(metadata.fftSize, metadata.bins));
      const firstBin = finite(metadata.firstBin);
      const binCount = Math.max(1, finite(metadata.bins));
      const fullRatio = (firstBin + Math.max(0, Math.min(1, ratio)) * Math.max(0, binCount - 1)) / fftSize;
      return finite(metadata.centerHz) + (fullRatio - 0.5) * finite(metadata.sampleRateHz);
    }

    updateCursorPopup() {
      if (!this.cursorPopup || !this.hover || !this.signalFrame?.bins?.length) return;
      const rect = this.canvas.getBoundingClientRect();
      const plotLeft = 58;
      const plotRight = 16;
      const plotWidth = Math.max(1, rect.width - plotLeft - plotRight);
      const xRatio = Math.max(0, Math.min(1, (this.hover.x - plotLeft) / plotWidth));
      const span = 1 / this.zoom;
      const frameRatio = this.viewportCenter - span / 2 + xRatio * span;
      const binIndex = Math.max(0, Math.min(this.signalFrame.bins.length - 1,
        Math.round(frameRatio * (this.signalFrame.bins.length - 1))));
      const frequency = this.frequencyAtFrameRatio(frameRatio);
      const power = this.signalFrame.bins[binIndex];
      this.cursorPopup.textContent = `${formatFrequency(frequency)} · ${power.toFixed(1)} dB`;
      this.cursorPopup.hidden = false;
      const left = Math.min(rect.width - this.cursorPopup.offsetWidth - 8,
        Math.max(8, this.hover.x - this.cursorPopup.offsetWidth / 2));
      const top = Math.max(8, this.hover.y - this.cursorPopup.offsetHeight - 12);
      this.cursorPopup.style.left = `${left}px`;
      this.cursorPopup.style.top = `${top}px`;
    }

    drawSymbols() {
      const fit = this.fitCanvas();
      if (!fit) return;
      const { context, width, height, dpr } = fit;
      const padding = { left: 58 * dpr, right: 16 * dpr, top: 17 * dpr, bottom: 38 * dpr };
      const plotWidth = width - padding.left - padding.right;
      const plotHeight = height - padding.top - padding.bottom;
      context.fillStyle = '#071118';
      context.fillRect(0, 0, width, height);
      context.font = `${11 * dpr}px ui-monospace, SFMono-Regular, Consolas, monospace`;
      context.textAlign = 'right';
      context.textBaseline = 'middle';
      const levels = [[-Math.PI, '−π'], [-Math.PI / 2, '−π/2'], [0, '0'],
        [Math.PI / 2, 'π/2'], [Math.PI, 'π']];
      levels.forEach(([value, label]) => {
        const y = padding.top + (Math.PI - value) / (2 * Math.PI) * plotHeight;
        context.strokeStyle = '#253946';
        context.lineWidth = dpr;
        context.beginPath();
        context.moveTo(padding.left, y);
        context.lineTo(width - padding.right, y);
        context.stroke();
        context.fillStyle = '#9fb2bf';
        context.fillText(label, padding.left - 8 * dpr, y);
      });
      if (this.symbolCount) {
        context.fillStyle = 'rgba(84, 205, 190, .78)';
        const size = Math.max(1, 1.25 * dpr);
        const visibleSlots = this.symbolCount === MAXIMUM_SYMBOLS ? MAXIMUM_SYMBOLS : this.symbolWriteIndex;
        for (let index = 0; index < visibleSlots; index += 1) {
          const value = this.symbols[index];
          const ratio = index / (MAXIMUM_SYMBOLS - 1);
          const x = padding.left + ratio * plotWidth;
          const y = padding.top + (Math.PI - value) / (2 * Math.PI) * plotHeight;
          context.fillRect(x, y, size, size);
        }

        const latestIndex = (this.symbolWriteIndex - 1 + MAXIMUM_SYMBOLS) % MAXIMUM_SYMBOLS;
        const markerX = padding.left + latestIndex / (MAXIMUM_SYMBOLS - 1) * plotWidth;
        context.strokeStyle = 'rgba(84, 205, 190, .78)';
        context.lineWidth = dpr;
        context.beginPath();
        context.moveTo(markerX, padding.top);
        context.lineTo(markerX, padding.top + plotHeight);
        context.stroke();
      }
      context.textAlign = 'center';
      context.textBaseline = 'top';
      context.fillStyle = '#9fb2bf';
      context.fillText('Sweep position', padding.left + plotWidth / 2,
        padding.top + plotHeight + 10 * dpr);
    }
  }

  window.SelectedChannelDiagnosticsView = SelectedChannelDiagnosticsView;
}());
