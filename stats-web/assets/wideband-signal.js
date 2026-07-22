/* global document, window, Worker, WebSocket, EventSource, requestAnimationFrame, cancelAnimationFrame, ResizeObserver, fetch */
'use strict';

const SIGNAL_DEFAULT_FPS = 20;
const SIGNAL_DEFAULT_DB_FLOOR = -140;
const SIGNAL_MINIMUM_DB_FLOOR = -200;
const SIGNAL_MAXIMUM_DB_FLOOR = -40;
const SIGNAL_MAXIMUM_ZOOM = 8;
const SIGNAL_ZOOM_FACTOR = 1.5;
const SIGNAL_REFINEMENT_DELAY_MS = 180;
const SIGNAL_FIRST_FRAME_TIMEOUT_MS = 15000;
const SIGNAL_PALETTE_MINIMUM_DB = -220;
const SIGNAL_PALETTE_MAXIMUM_DB = 40;
const SIGNAL_PALETTE_STEP_DB = 0.5;
const SIGNAL_DB_FLOOR_STORAGE_KEY = 'sdrtrunk.wideband.lowerDisplayLimitDb';
const SIGNAL_WATERFALL_SPEED_STORAGE_KEY = 'sdrtrunk.wideband.waterfallScrollSpeed';

function signalStoredNumber(key, fallback, minimum, maximum) {
  try {
    const value = Number(window.localStorage.getItem(key));
    return Number.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
  } catch (error) {
    return fallback;
  }
}

function signalStoreNumber(key, value) {
  try {
    window.localStorage.setItem(key, String(value));
  } catch (error) {
    // Browser privacy settings can disable local storage. The control still works for this tab.
  }
}

class WidebandSignalView {
  constructor(root, options = {}) {
    this.root = root;
    this.options = {
      initialTargetId: typeof options.initialTargetId === 'string' ? options.initialTargetId : null,
      lockedTarget: options.lockedTarget === true,
      embedded: options.embedded === true,
      authenticationControls: options.authenticationControls !== false,
      onAuthenticationRequired: typeof options.onAuthenticationRequired === 'function' ?
        options.onAuthenticationRequired : null
    };
    this.closed = false;
    this.paused = false;
    this.hidden = document.hidden;
    this.socket = null;
    this.retryTimer = null;
    this.readyTimer = null;
    this.refinementTimer = null;
    this.firstFrameTimer = null;
    this.retryCount = 0;
    this.connectionEpoch = 0;
    this.requestId = 0;
    this.pendingRequestId = null;
    this.acceptedViewRevision = null;
    this.deferredFrame = null;
    this.subscriptionSent = false;
    this.protocolReady = false;
    this.frame = null;
    this.lastSequence = null;
    this.lastGeneration = null;
    this.lastViewRevision = null;
    this.dropped = 0;
    this.framesThisSecond = 0;
    this.fps = 0;
    this.lastFpsTick = performance.now();
    this.renderRequest = null;
    this.fullViewport = null;
    this.viewport = null;
    this.displayViewport = null;
    this.viewportIntentVersion = 0;
    this.sentViewportIntentVersion = null;
    this.hoverRatio = null;
    this.hoverCanvas = null;
    this.hoverYRatio = null;
    this.drag = null;
    this.refining = false;
    this.dbFloor = signalStoredNumber(SIGNAL_DB_FLOOR_STORAGE_KEY, SIGNAL_DEFAULT_DB_FLOOR,
      SIGNAL_MINIMUM_DB_FLOOR, SIGNAL_MAXIMUM_DB_FLOOR);
    this.waterfallSpeed = signalStoredNumber(SIGNAL_WATERFALL_SPEED_STORAGE_KEY, 1, 0.25, 4);
    this.waterfallScrollAccumulator = 0;
    this.activeChannelTables = new Map();
    this.activeChannelLabelRows = [];
    this.activeChannelLabelSignature = '';
    this.activeChannelLabelViewportKey = '';
    this.activeChannelSource = null;
    this.paletteLut = new Uint8ClampedArray(
      (Math.round((SIGNAL_PALETTE_MAXIMUM_DB - SIGNAL_PALETTE_MINIMUM_DB) / SIGNAL_PALETTE_STEP_DB) + 1) * 3);
    this.binRange = { start: 0, end: 0 };
    this.waterfallRow = null;
    this.fftScratch = document.createElement('canvas');
    this.waterfallScratch = document.createElement('canvas');
    this.worker = new Worker('/assets/signal-worker.js?v=3');
    this.worker.onmessage = (event) => this.onWorkerMessage(event.data);
    this.worker.onerror = () => this.onWorkerFailure();
    this.worker.onmessageerror = () => this.onWorkerFailure();
    this.onVisibility = () => {
      this.hidden = document.hidden;
      if (this.hidden) {
        this.disconnectSocket('page hidden');
        this.disconnectActiveChannels();
        this.setState('paused', 'Page hidden · spectrum slot released');
      } else if (!this.paused) {
        this.connectActiveChannels();
        this.subscribeOrConnect();
      }
    };
    document.addEventListener('visibilitychange', this.onVisibility);
    this.build();
    this.onDocumentPointerDown = (event) => {
      if (!this.statsPanel.hidden && !this.statsPanel.contains(event.target)) this.hideStats();
    };
    this.onDocumentKeyDown = (event) => {
      if (event.key === 'Escape' && !this.statsPanel.hidden) {
        event.preventDefault();
        this.hideStats(true);
      }
    };
    document.addEventListener('pointerdown', this.onDocumentPointerDown);
    document.addEventListener('keydown', this.onDocumentKeyDown);
    this.rebuildPalette();
    this.connectActiveChannels();
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(this.plotArea);
    this.statusTimer = window.setInterval(() => this.updateTimedReadouts(), 250);
    this.subscribeOrConnect();
  }

  element(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = String(text);
    return element;
  }

  readout(label, id, value = '—') {
    const row = this.element('div', 'wideband-stats-row');
    row.append(this.element('span', '', label));
    const output = this.element('output', '', value);
    output.id = id;
    row.append(output);
    return { row, output };
  }

  build() {
    this.root.className = `wideband-page${this.options.embedded ? ' embedded' : ''}`;
    const pageHeading = this.element('h1', 'visually-hidden', 'Spectrum');
    this.access = this.element('span', 'wideband-access', 'ADMIN ONLY');

    const toolbar = this.element('div', 'wideband-toolbar');
    const target = this.element('div', 'wideband-target');
    const targetLabel = this.element('label', '', 'Receiver');
    targetLabel.htmlFor = 'wideband-target-select';
    this.targetSelect = this.element('select', 'wideband-target-select');
    this.targetSelect.id = 'wideband-target-select';
    this.targetSelect.disabled = true;
    const targetPlaceholder = this.element('option', '', 'Available receiver');
    targetPlaceholder.value = '';
    this.targetSelect.append(targetPlaceholder);
    this.targetSelect.addEventListener('change', () => this.changeTarget());
    this.targetName = this.element('span', 'wideband-target-summary', 'Waiting for receiver inventory');
    if (this.options.lockedTarget) {
      targetLabel.hidden = true;
      this.targetSelect.hidden = true;
    }
    target.append(targetLabel, this.targetSelect, this.targetName, this.access);

    this.status = this.element('div', 'wideband-status connecting', 'Connecting to admin spectrum');
    this.status.setAttribute('role', 'status');
    this.status.setAttribute('aria-live', 'polite');
    const commands = this.element('div', 'wideband-toolbar-actions');
    this.pauseButton = this.element('button', 'button secondary', 'Pause');
    this.pauseButton.type = 'button';
    this.pauseButton.onclick = () => this.togglePause();
    this.resetButton = this.element('button', 'button secondary', 'Reset zoom');
    this.resetButton.type = 'button';
    this.resetButton.disabled = true;
    this.resetButton.onclick = () => this.resetZoom();
    this.logoutButton = this.element('button', 'button secondary', 'Sign out');
    this.logoutButton.type = 'button';
    this.logoutButton.hidden = true;
    this.logoutButton.onclick = () => this.logout();
    commands.append(this.resetButton, this.pauseButton);
    if (this.options.authenticationControls) commands.append(this.logoutButton);
    toolbar.append(target, this.status, commands);

    const displayControls = this.element('div', 'wideband-display-controls');
    const floorLabel = this.element('label', 'wideband-floor-control');
    const floorText = this.element('span', '', 'Lower display limit');
    this.floorInput = this.element('input');
    this.floorInput.type = 'range';
    this.floorInput.min = String(SIGNAL_MINIMUM_DB_FLOOR);
    this.floorInput.max = String(SIGNAL_MAXIMUM_DB_FLOOR);
    this.floorInput.step = '5';
    this.floorInput.value = String(this.dbFloor);
    this.floorInput.id = 'wideband-floor';
    this.floorInput.setAttribute('aria-describedby', 'wideband-floor-help');
    this.floorValue = this.element('output', '', `${this.dbFloor} dB`);
    this.floorValue.htmlFor = 'wideband-floor';
    this.floorInput.addEventListener('input', () => this.changeDbFloor());
    floorLabel.append(floorText, this.floorInput, this.floorValue);
    const floorHelp = this.element('span', 'wideband-control-help',
      'Changes display contrast only; it does not change receiver gain or decoder thresholds.');
    floorHelp.id = 'wideband-floor-help';
    const speedLabel = this.element('label', 'wideband-floor-control wideband-speed-control');
    const speedText = this.element('span', '', 'Waterfall speed');
    this.speedInput = this.element('input');
    this.speedInput.type = 'range';
    this.speedInput.min = '0.25';
    this.speedInput.max = '4';
    this.speedInput.step = '0.25';
    this.speedInput.value = String(this.waterfallSpeed);
    this.speedInput.id = 'wideband-waterfall-speed';
    this.speedValue = this.element('output', '', `${this.waterfallSpeed.toFixed(2)}×`);
    this.speedValue.htmlFor = this.speedInput.id;
    this.speedInput.addEventListener('input', () => this.changeWaterfallSpeed());
    speedLabel.append(speedText, this.speedInput, this.speedValue);
    this.refiningBadge = this.element('span', 'wideband-refining');
    this.refiningBadge.hidden = true;
    this.refiningBadge.setAttribute('role', 'status');
    this.refiningBadge.setAttribute('aria-live', 'polite');
    displayControls.append(floorLabel, floorHelp, speedLabel, this.refiningBadge);

    const workspace = this.element('div', 'wideband-workspace');
    this.plotArea = this.element('div', 'wideband-plots');
    this.plotInstructions = this.element('p', 'visually-hidden',
      'Use the mouse wheel or plus and minus keys to zoom. When zoomed, drag horizontally or use the arrow keys to pan. Press R to reset zoom. Right-click or press Shift plus F10 for technical statistics.');
    this.plotInstructions.id = 'wideband-plot-instructions';

    const fftWrap = this.element('section', 'wideband-plot');
    fftWrap.append(this.element('div', 'wideband-plot-label', 'FFT · relative dB'));
    this.fft = this.element('canvas', 'wideband-canvas');
    this.configureInteractiveCanvas(this.fft,
      'Live spectrum plot. Wheel to zoom and drag to pan when zoomed.');
    this.fftGuide = this.element('div', 'wideband-cursor-guide');
    this.fftGuide.hidden = true;
    this.activeChannelConnectors = this.element('canvas', 'wideband-active-channel-connectors');
    this.activeChannelConnectors.setAttribute('aria-hidden', 'true');
    this.activeChannelLabels = this.element('div', 'wideband-active-channel-labels');
    this.activeChannelLabels.setAttribute('aria-hidden', 'true');
    fftWrap.append(this.fft, this.activeChannelConnectors, this.activeChannelLabels, this.fftGuide);

    const waterfallWrap = this.element('section', 'wideband-plot waterfall');
    this.waterfall = this.element('canvas', 'wideband-canvas');
    this.configureInteractiveCanvas(this.waterfall,
      'Live waterfall plot. Wheel to zoom and drag to pan when zoomed.');
    this.waterfallGuide = this.element('div', 'wideband-cursor-guide');
    this.waterfallGuide.hidden = true;
    waterfallWrap.append(this.waterfall, this.waterfallGuide);

    this.cursorPopup = this.element('div', 'wideband-cursor-popup');
    this.cursorPopup.hidden = true;
    this.cursorFrequency = this.element('span', 'wideband-cursor-frequency');
    this.cursorPower = this.element('span', 'wideband-cursor-power');
    this.cursorPopup.append(this.cursorFrequency, this.cursorPower);

    this.blocker = this.element('div', 'wideband-blocker');
    this.blocker.hidden = true;
    this.blocker.setAttribute('role', 'alert');
    this.blockerTitle = this.element('strong');
    this.blockerDetail = this.element('span');
    this.blockerRetry = this.element('button', 'button secondary', 'Try again');
    this.blockerRetry.type = 'button';
    this.blockerRetry.onclick = () => this.retryNow();
    this.loginForm = this.element('form', 'wideband-login-form');
    this.loginForm.hidden = true;
    const usernameLabel = this.element('label', '', 'Administrator username');
    this.loginUsername = this.element('input');
    this.loginUsername.name = 'username';
    this.loginUsername.autocomplete = 'username';
    this.loginUsername.maxLength = 256;
    this.loginUsername.required = true;
    usernameLabel.append(this.loginUsername);
    const passwordLabel = this.element('label', '', 'Password');
    this.loginPassword = this.element('input');
    this.loginPassword.type = 'password';
    this.loginPassword.name = 'password';
    this.loginPassword.autocomplete = 'current-password';
    this.loginPassword.maxLength = 256;
    this.loginPassword.required = true;
    passwordLabel.append(this.loginPassword);
    this.loginSubmit = this.element('button', 'button', 'Sign in');
    this.loginSubmit.type = 'submit';
    this.loginMessage = this.element('span', 'wideband-login-message');
    this.loginMessage.setAttribute('role', 'status');
    this.loginForm.append(usernameLabel, passwordLabel, this.loginSubmit, this.loginMessage);
    this.loginForm.addEventListener('submit', (event) => this.login(event));
    this.blocker.append(this.blockerTitle, this.blockerDetail, this.loginForm, this.blockerRetry);

    this.statsPanel = this.element('aside', 'wideband-stats-panel');
    this.statsPanel.hidden = true;
    this.statsPanel.setAttribute('role', 'dialog');
    this.statsPanel.setAttribute('aria-label', 'Spectrum technical statistics');
    const statsHeading = this.element('div', 'wideband-stats-heading');
    statsHeading.append(this.element('h2', '', 'Stats for nerds'));
    this.statsCloseButton = this.element('button', 'wideband-stats-close', 'Close');
    this.statsCloseButton.type = 'button';
    this.statsCloseButton.onclick = () => this.hideStats(true);
    statsHeading.append(this.statsCloseButton);
    const peak = this.readout('Peak', 'wideband-peak');
    const center = this.readout('Center', 'wideband-center');
    const span = this.readout('Visible span', 'wideband-span');
    const zoom = this.readout('Zoom', 'wideband-zoom', '1.00×');
    const rate = this.readout('Rate', 'wideband-rate');
    const bins = this.readout('Sent bins', 'wideband-bins');
    const fftSize = this.readout('FFT detail', 'wideband-fft-size');
    const dropped = this.readout('Dropped', 'wideband-drops', '0');
    const generation = this.readout('Generation', 'wideband-generation');
    this.outputs = {
      peak: peak.output,
      center: center.output,
      span: span.output,
      zoom: zoom.output,
      rate: rate.output,
      bins: bins.output,
      fftSize: fftSize.output,
      dropped: dropped.output,
      generation: generation.output
    };
    this.statsPanel.append(statsHeading, peak.row, center.row, span.row, zoom.row, rate.row,
      bins.row, fftSize.row, dropped.row, generation.row);
    const note = this.element('p', 'wideband-stats-note',
      'Power is relative FFT dB, not calibrated dBm. Zoom can use more server CPU, but never adds tuner USB traffic or blocks radio processing.');
    this.statsPanel.append(note);
    this.plotArea.append(this.plotInstructions, fftWrap, waterfallWrap, this.cursorPopup, this.blocker, this.statsPanel);
    workspace.append(this.plotArea);

    this.root.append(pageHeading, toolbar, displayControls, workspace);
  }

  configureInteractiveCanvas(canvas, label) {
    canvas.setAttribute('aria-label', label);
    canvas.setAttribute('aria-describedby', 'wideband-plot-instructions');
    canvas.setAttribute('aria-keyshortcuts', '+ - ArrowLeft ArrowRight R Shift+F10');
    canvas.setAttribute('role', 'img');
    canvas.tabIndex = 0;
    canvas.addEventListener('wheel', (event) => this.onWheel(event), { passive: false });
    canvas.addEventListener('pointerenter', (event) => this.onPointerMove(event));
    canvas.addEventListener('pointermove', (event) => this.onPointerMove(event));
    canvas.addEventListener('pointerleave', (event) => this.onPointerLeave(event));
    canvas.addEventListener('pointerdown', (event) => this.onPointerDown(event));
    canvas.addEventListener('pointerup', (event) => this.onPointerUp(event));
    canvas.addEventListener('pointercancel', (event) => this.onPointerUp(event));
    canvas.addEventListener('lostpointercapture', (event) => this.onLostPointerCapture(event));
    canvas.addEventListener('contextmenu', (event) => this.onContextMenu(event));
    canvas.addEventListener('keydown', (event) => this.onKeyDown(event));
  }

  resizeCanvas(canvas, cssHeight) {
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    const width = Math.max(320, Math.floor(canvas.clientWidth * ratio));
    const height = Math.max(120, Math.floor(cssHeight * ratio));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
      return true;
    }
    return false;
  }

  resize() {
    const fftChanged = this.resizeCanvas(this.fft, 250);
    const connectorChanged = this.resizeCanvas(this.activeChannelConnectors, 250);
    const waterfallChanged = this.resizeCanvas(this.waterfall, 310);
    if (waterfallChanged) {
      this.waterfallRow = null;
      this.clearWaterfall();
    }
    if (fftChanged || connectorChanged) this.renderActiveChannelLabels();
    if (fftChanged || connectorChanged || waterfallChanged) this.requestFftRender();
  }

  webSocketUrl() {
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${scheme}//${window.location.host}/api/v1/ws/signal`;
  }

  nextRequestId() {
    this.requestId += 1;
    return this.requestId;
  }

  selectedTargetId() {
    return this.targetSelect.value || this.options.initialTargetId || null;
  }

  subscribeOrConnect() {
    if (this.closed || this.paused || this.hidden) return;
    if (this.socket?.readyState === WebSocket.OPEN) {
      if (!this.subscriptionSent && this.protocolReady) this.sendSubscribe();
      return;
    }
    if (this.socket?.readyState === WebSocket.CONNECTING) return;
    this.hideBlocker();
    this.setState('connecting', this.retryCount ? `Reconnecting · attempt ${this.retryCount + 1}` :
      'Connecting to admin spectrum');
    const connectionEpoch = ++this.connectionEpoch;
    const socket = new WebSocket(this.webSocketUrl());
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    this.subscriptionSent = false;
    this.protocolReady = false;
    socket.onopen = () => {
      if (this.closed || socket !== this.socket) return socket.close();
      // SFFT v2 announces the allowed target inventory before subscribing. The bounded fallback keeps an older v1
      // server usable during a rolling update; a current server normally answers within the same event-loop turn.
      this.readyTimer = window.setTimeout(() => this.sendSubscribe(true), 2000);
    };
    socket.onmessage = (event) => {
      if (this.closed || socket !== this.socket || connectionEpoch !== this.connectionEpoch) return;
      if (event.data instanceof ArrayBuffer) {
        this.worker.postMessage({ type: 'frame', buffer: event.data, connectionEpoch }, [event.data]);
      } else if (typeof event.data === 'string') {
        this.onSignalState(event.data);
      }
    };
    socket.onclose = async (event) => {
      if (socket !== this.socket) return;
      this.socket = null;
      this.connectionEpoch += 1;
      this.subscriptionSent = false;
      this.protocolReady = false;
      window.clearTimeout(this.readyTimer);
      window.clearTimeout(this.firstFrameTimer);
      if (this.closed || this.paused || this.hidden) return;
      if (event.code === 4401 || event.code === 4403) {
        this.showAdminRequired();
        return;
      }
      if (this.isBusyClose(event)) {
        this.showBusy();
        return;
      }
      if (event.code === 1008) {
        this.setUnavailable('degraded', 'Spectrum request was rejected',
          'The browser and server did not agree on a safe spectrum request. Refresh after the application is updated.');
        return;
      }
      if (event.code === 4410) {
        this.showNoReceiver();
        return;
      }
      const closeState = await this.resolveOpaqueCloseState();
      if (this.closed || this.paused || this.hidden || this.socket) return;
      if (closeState === 'locked') {
        this.showAdminRequired();
        return;
      }
      if (closeState === 'unconfigured') {
        this.showAdminSetupRequired();
        return;
      }
      if (closeState === 'busy') {
        this.showBusy();
        return;
      }
      this.scheduleReconnect();
    };
    socket.onerror = () => {
      if (socket === this.socket && connectionEpoch === this.connectionEpoch) {
        this.setState('reconnecting', 'Unable to reach admin spectrum');
      }
    };
  }

  sendSubscribe(allowWithoutReady = false) {
    if (this.socket?.readyState !== WebSocket.OPEN || this.subscriptionSent ||
        (!this.protocolReady && !allowWithoutReady)) return;
    const requestId = this.nextRequestId();
    const control = { action: 'subscribe', requestId, maxFps: SIGNAL_DEFAULT_FPS };
    const targetId = this.selectedTargetId();
    if (targetId) control.targetId = targetId;
    this.socket.send(JSON.stringify(control));
    this.subscriptionSent = true;
    this.pendingRequestId = requestId;
    this.armSignalFrameTimeout();
  }

  armSignalFrameTimeout() {
    window.clearTimeout(this.firstFrameTimer);
    this.firstFrameTimer = window.setTimeout(() => this.onFirstFrameTimeout(), SIGNAL_FIRST_FRAME_TIMEOUT_MS);
  }

  onSignalState(encoded) {
    let message;
    try {
      message = JSON.parse(encoded);
    } catch (error) {
      return;
    }
    if (!message || message.type !== 'signal-state' || typeof message.state !== 'string') return;

    if (message.state === 'ready') {
      window.clearTimeout(this.readyTimer);
      this.protocolReady = true;
      this.retryCount = 0;
      this.logoutButton.hidden = !this.options.authenticationControls;

      if (!this.updateTargetInventory(message.targets, message.targetId)) {
        this.showNoReceiver();
        return;
      }

      this.hideBlocker();
      this.setState('connecting', message.exclusive ? 'Spectrum slot acquired · waiting for data' :
        'Connected · waiting for data');
      this.sendSubscribe();
    } else if (message.state === 'refining') {
      if (this.isCurrentRequest(message.requestId)) this.setRefining(true);
    } else if (message.state === 'live') {
      this.retryCount = 0;
      this.acceptLiveState(message);
    } else if (message.state === 'busy') {
      this.showBusy();
    } else if (message.state === 'locked') {
      this.showAdminRequired();
    } else if (message.state === 'degraded') {
      this.setState('degraded', message.message || 'Spectrum quality reduced to protect radio processing');
    }
  }

  isCurrentRequest(requestId) {
    return Number.isSafeInteger(requestId) && (this.pendingRequestId === null || requestId >= this.pendingRequestId);
  }

  acceptLiveState(message) {
    if (Number.isSafeInteger(message.requestId) && this.pendingRequestId !== null &&
        message.requestId < this.pendingRequestId) return;
    const hasNewerLocalViewport = this.refinementTimer !== null || this.drag !== null ||
      (this.sentViewportIntentVersion !== null &&
        this.viewportIntentVersion > this.sentViewportIntentVersion);
    if (Number.isSafeInteger(message.requestId)) this.pendingRequestId = null;
    if (Number.isSafeInteger(message.viewRevision)) this.acceptedViewRevision = message.viewRevision;
    if (Number.isFinite(message.centerFrequencyHz) && Number.isFinite(message.sampleRateHz) &&
        message.sampleRateHz > 0) {
      this.fullViewport = {
        startHz: message.centerFrequencyHz - message.sampleRateHz / 2,
        endHz: message.centerFrequencyHz + message.sampleRateHz / 2
      };
    }
    if (!hasNewerLocalViewport && Number.isFinite(message.visibleStartHz) && Number.isFinite(message.visibleEndHz) &&
        message.visibleEndHz > message.visibleStartHz) {
      this.viewport = { startHz: message.visibleStartHz, endHz: message.visibleEndHz };
      this.displayViewport = { ...this.viewport };
    } else if (!hasNewerLocalViewport && !this.viewport && this.fullViewport) {
      this.viewport = { ...this.fullViewport };
      this.displayViewport = { ...this.fullViewport };
    }
    if (typeof message.targetId === 'string') this.selectTarget(message.targetId);
    if (typeof message.targetLabel === 'string') this.targetName.textContent = message.targetLabel;
    this.hideBlocker();

    if (hasNewerLocalViewport) {
      this.setRefining(true);
      this.updateViewportReadouts();
      return;
    }

    this.sentViewportIntentVersion = null;
    this.setRefining(false);
    this.setState('live', 'Live · exclusive admin spectrum');
    this.updateViewportReadouts();

    if (this.deferredFrame && (this.acceptedViewRevision === null ||
        this.deferredFrame.metadata.viewRevision === this.acceptedViewRevision)) {
      const deferred = this.deferredFrame;
      this.deferredFrame = null;
      this.acceptFrame(deferred);
    }
  }

  isBusyClose(event) {
    const reason = String(event.reason || '').toLowerCase();
    return event.code === 4409 || event.code === 4429 || event.code === 1013 ||
      reason.includes('in use') || reason.includes('busy') || reason.includes('capacity');
  }

  async resolveOpaqueCloseState() {
    try {
      const sessionResponse = await fetch('/api/v1/auth/session', {
        cache: 'no-store', credentials: 'same-origin'
      });
      if (sessionResponse.status === 401 || sessionResponse.status === 403) return 'locked';
      if (!sessionResponse.ok) return null;
      const session = await sessionResponse.json();
      if (session.configured !== true) return 'unconfigured';
      if (session.authenticated !== true) return 'locked';

      const statusResponse = await fetch('/api/status', { cache: 'no-store', credentials: 'same-origin' });
      if (!statusResponse.ok) return null;
      const status = await statusResponse.json();
      if (Number(status.signal?.sessions || 0) > 0) return 'busy';
      return null;
    } catch (error) {
      return null;
    }
  }

  scheduleReconnect() {
    this.setState('reconnecting', 'Spectrum disconnected · retrying');
    const delay = Math.min(10000, 500 * (2 ** Math.min(this.retryCount++, 4)));
    this.retryTimer = window.setTimeout(() => this.subscribeOrConnect(), delay);
  }

  retryNow() {
    window.clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.disconnectSocket('manual retry');
    this.subscribeOrConnect();
  }

  showAdminRequired() {
    this.setUnavailable('locked', 'Administrator sign-in required',
      'Spectrum is available only to the single administrator account. Account setup and recovery stay in the receiver\'s local Web Server settings utility.');
    this.logoutButton.hidden = true;
    this.loginForm.hidden = !this.options.authenticationControls;
    this.blockerRetry.hidden = true;
    this.loginMessage.textContent = '';
    if (this.options.authenticationControls) this.loginUsername.focus();
    this.options.onAuthenticationRequired?.();
  }

  showAdminSetupRequired() {
    this.setUnavailable('locked', 'Administrator setup required',
      'On the receiver, stop normal sdrtrunk-vce and open Local Web Server Settings with --server-admin-ui. Create the account, restart normally, then retry here.');
    this.logoutButton.hidden = true;
    this.loginForm.hidden = true;
    this.blockerRetry.hidden = false;
  }

  showBusy() {
    this.setUnavailable('busy', 'Spectrum is currently in use',
      'Only one admin browser can use the interactive spectrum at a time. Close the other spectrum page, then try again.');
  }

  showNoReceiver() {
    this.setUnavailable('degraded', 'No receiver is available',
      'Enable the selected receiver, or close spectrum and choose another available receiver. The spectrum slot has been released.');
    this.targetName.textContent = 'No running receiver';
  }

  onFirstFrameTimeout() {
    this.firstFrameTimer = null;
    if (this.closed || this.paused || this.hidden || !this.subscriptionSent) return;
    this.setUnavailable('degraded', 'Spectrum did not start',
      'No signal frame arrived within 15 seconds. The spectrum slot has been released; try again after checking the receiver.');
  }

  onWorkerFailure() {
    if (this.closed) return;
    this.setUnavailable('degraded', 'Spectrum renderer stopped',
      'The browser could not process signal frames. The spectrum slot has been released; refresh or try again.');
  }

  setUnavailable(state, title, detail) {
    this.disconnectSocket(state);
    this.setState(state, title);
    this.resetSignalData(true);
    this.setRefining(false);
    this.blocker.className = `wideband-blocker ${state}`;
    this.blockerTitle.textContent = title;
    this.blockerDetail.textContent = detail;
    this.loginForm.hidden = true;
    this.blockerRetry.hidden = false;
    this.blocker.hidden = false;
  }

  async login(event) {
    event.preventDefault();
    if (this.loginSubmit.disabled) return;
    const username = this.loginUsername.value;
    const password = this.loginPassword.value;
    this.loginSubmit.disabled = true;
    this.loginMessage.textContent = 'Signing in…';

    try {
      const response = await fetch('/api/v1/auth/login', {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
      });
      this.loginPassword.value = '';
      if (response.ok) {
        this.loginMessage.textContent = 'Signed in';
        this.logoutButton.hidden = !this.options.authenticationControls;
        window.dispatchEvent(new CustomEvent('sdrtrunk:auth-changed', {
          detail: { authenticated: true }
        }));
        this.retryNow();
        return;
      }
      const result = await response.json().catch(() => ({}));
      if (result.error === 'secure_transport_required') {
        this.loginMessage.textContent = 'Remote sign-in requires HTTPS or the receiver’s local/SSH-tunneled address.';
      } else if (response.status === 429) {
        this.loginMessage.textContent = 'Too many attempts. Wait a few minutes, then try again.';
      } else if (response.status === 503) {
        this.loginMessage.textContent = 'Sign-in is busy. Wait a moment, then try again.';
      } else {
        this.loginMessage.textContent = 'Username or password was not accepted.';
      }
    } catch (error) {
      this.loginPassword.value = '';
      this.loginMessage.textContent = 'The receiver could not process sign-in.';
    } finally {
      this.loginSubmit.disabled = false;
    }
  }

  async logout() {
    this.logoutButton.disabled = true;
    try {
      const sessionResponse = await fetch('/api/v1/auth/session', {
        cache: 'no-store', credentials: 'same-origin'
      });
      const session = sessionResponse.ok ? await sessionResponse.json() : null;
      if (session?.authenticated === true && typeof session.csrfToken === 'string') {
        await fetch('/api/v1/auth/logout', {
          method: 'POST', credentials: 'same-origin', cache: 'no-store',
          headers: { 'X-CSRF-Token': session.csrfToken }
        });
      }
    } finally {
      this.logoutButton.hidden = true;
      this.logoutButton.disabled = false;
      window.dispatchEvent(new CustomEvent('sdrtrunk:auth-changed', {
        detail: { authenticated: false }
      }));
      this.showAdminRequired();
    }
  }

  hideBlocker() {
    this.blocker.hidden = true;
  }

  resetSignalData(clearTargets) {
    this.cancelDrag();
    window.clearTimeout(this.refinementTimer);
    window.clearTimeout(this.firstFrameTimer);
    this.refinementTimer = null;
    this.firstFrameTimer = null;
    this.frame = null;
    this.deferredFrame = null;
    this.pendingRequestId = null;
    this.acceptedViewRevision = null;
    this.sentViewportIntentVersion = null;
    this.viewportIntentVersion += 1;
    this.lastSequence = null;
    this.lastGeneration = null;
    this.lastViewRevision = null;
    this.fullViewport = null;
    this.viewport = null;
    this.displayViewport = null;
    this.hoverRatio = null;
    this.hoverCanvas = null;
    this.hoverYRatio = null;
    this.dropped = 0;
    this.framesThisSecond = 0;
    this.fps = 0;
    this.outputs.peak.textContent = '—';
    this.outputs.center.textContent = '—';
    this.outputs.span.textContent = '—';
    this.outputs.zoom.textContent = '1.00×';
    this.outputs.rate.textContent = '—';
    this.outputs.bins.textContent = '—';
    this.outputs.fftSize.textContent = '—';
    this.outputs.dropped.textContent = '0';
    this.outputs.generation.textContent = '—';
    this.resetButton.disabled = true;
    this.plotArea.classList.remove('zoomed');
    this.hideStats(true);

    if (clearTargets) {
      this.targetSelect.replaceChildren();
      const placeholder = this.element('option', '', 'Available receiver');
      placeholder.value = '';
      this.targetSelect.append(placeholder);
      this.targetSelect.disabled = true;
      this.targetName.textContent = 'Waiting for authenticated receiver inventory';
    }

    this.clearPlots();
  }

  updateTargetInventory(targets, selectedId) {
    if (!Array.isArray(targets) || !targets.length) {
      this.targetSelect.replaceChildren();
      const unavailable = this.element('option', '', 'No running receiver');
      unavailable.value = '';
      this.targetSelect.append(unavailable);
      this.targetSelect.disabled = true;
      this.targetName.textContent = 'No running receiver';
      return false;
    }
    const previous = this.options.lockedTarget ? this.options.initialTargetId :
      (selectedId || this.selectedTargetId());
    this.targetSelect.replaceChildren();
    targets.forEach((target) => {
      if (!target || typeof target.id !== 'string' || typeof target.label !== 'string') return;
      const option = this.element('option', '', target.label);
      option.value = target.id;
      this.targetSelect.append(option);
    });
    if (!this.targetSelect.options.length) return false;
    if (previous && Array.from(this.targetSelect.options).some((option) => option.value === previous)) {
      this.targetSelect.value = previous;
    } else if (this.options.lockedTarget) {
      this.targetName.textContent = 'Selected receiver is no longer available';
      return false;
    }
    this.targetSelect.disabled = this.options.lockedTarget || this.targetSelect.options.length < 2;
    this.targetName.textContent = this.targetSelect.selectedOptions[0]?.textContent || 'Available receiver';
    return true;
  }

  selectTarget(targetId) {
    if (Array.from(this.targetSelect.options).some((option) => option.value === targetId)) {
      this.targetSelect.value = targetId;
      this.targetName.textContent = this.targetSelect.selectedOptions[0]?.textContent || targetId;
    }
  }

  changeTarget() {
    if (!this.selectedTargetId() || this.socket?.readyState !== WebSocket.OPEN) return;
    this.targetName.textContent = this.targetSelect.selectedOptions[0]?.textContent || 'Changing receiver';
    this.resetSignalData(false);
    const requestId = this.nextRequestId();
    this.pendingRequestId = requestId;
    this.setRefining(true, 'Changing receiver…');
    this.socket.send(JSON.stringify({
      action: 'update', requestId, targetId: this.selectedTargetId(), maxFps: SIGNAL_DEFAULT_FPS
    }));
    this.armSignalFrameTimeout();
  }

  disconnectSocket(reason) {
    this.cancelDrag();
    window.clearTimeout(this.retryTimer);
    window.clearTimeout(this.readyTimer);
    window.clearTimeout(this.refinementTimer);
    window.clearTimeout(this.firstFrameTimer);
    this.retryTimer = null;
    this.readyTimer = null;
    this.refinementTimer = null;
    this.firstFrameTimer = null;
    const socket = this.socket;
    this.socket = null;
    this.connectionEpoch += 1;
    this.subscriptionSent = false;
    this.protocolReady = false;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ action: 'unsubscribe' }));
      socket.close(1000, reason);
    } else if (socket?.readyState === WebSocket.CONNECTING) {
      socket.close();
    }
  }

  togglePause() {
    this.paused = !this.paused;
    this.pauseButton.textContent = this.paused ? 'Resume' : 'Pause';
    if (this.paused) {
      this.disconnectSocket('paused');
      this.disconnectActiveChannels();
      this.setState('paused', 'Paused · spectrum slot released');
    } else {
      this.connectActiveChannels();
      this.subscribeOrConnect();
    }
  }

  setState(className, text) {
    this.status.className = `wideband-status ${className}`;
    this.status.textContent = text;
  }

  setRefining(refining, text = 'Refining…') {
    this.refining = refining;
    this.refiningBadge.hidden = !refining;
    this.refiningBadge.textContent = refining ? text : '';
    this.plotArea.classList.toggle('refining', refining);
    if (refining) this.setState('refining', text);
  }

  onWorkerMessage(message) {
    if (this.closed || message?.connectionEpoch !== this.connectionEpoch ||
        this.socket?.readyState !== WebSocket.OPEN || !this.subscriptionSent || !this.blocker.hidden ||
        this.paused || this.hidden) return;
    if (message.type === 'error') {
      this.onWorkerFailure();
      return;
    }
    if (message.type !== 'frame') return;
    const decoded = {
      metadata: message.metadata,
      buffer: message.buffer,
      bins: new Float32Array(message.buffer, message.metadata.headerBytes, message.metadata.bins),
      received: performance.now()
    };
    if (decoded.metadata.version === 1 && this.refining) {
      // A rolling frontend-first update can still zoom locally against an older server. It will become truly
      // higher-resolution as soon as SFFT v2 is deployed.
      this.pendingRequestId = null;
      this.setRefining(false);
      this.acceptFrame(decoded);
      return;
    }
    if (this.refining) {
      // SFFT v2 refinement ends only after the server's live state accepts the requested revision. Keeping the latest
      // frame deferred prevents an old full-width frame from cancelling the new-view timeout during wheel debounce.
      this.deferredFrame = decoded;
      return;
    }
    if (this.acceptedViewRevision !== null && decoded.metadata.viewRevision !== null &&
        decoded.metadata.viewRevision !== this.acceptedViewRevision) {
      this.deferredFrame = decoded;
      return;
    }
    this.acceptFrame(decoded);
  }

  acceptFrame(decoded) {
    const metadata = decoded.metadata;
    window.clearTimeout(this.firstFrameTimer);
    this.firstFrameTimer = null;
    const domainStartHz = metadata.centerHz - metadata.sampleRateHz / 2;
    const domainEndHz = domainStartHz + metadata.sampleRateHz;
    const domainChanged = !this.fullViewport ||
      Math.abs(this.fullViewport.startHz - domainStartHz) >= 0.5 ||
      Math.abs(this.fullViewport.endHz - domainEndHz) >= 0.5;

    if (domainChanged) {
      this.fullViewport = { startHz: domainStartHz, endHz: domainEndHz };

      if (!this.refining && this.pendingRequestId === null) {
        const frameStartHz = this.frameStartHz(metadata);
        this.viewport = {
          startHz: frameStartHz,
          endHz: frameStartHz + metadata.bins * this.frameBinWidthHz(metadata)
        };
        this.displayViewport = { ...this.viewport };
      }
    }

    if (this.lastGeneration !== null && metadata.generation !== this.lastGeneration) {
      this.lastSequence = null;
      this.clearWaterfall();
    } else if (this.lastViewRevision !== null && metadata.viewRevision !== this.lastViewRevision) {
      // The source sequence spans view revisions, but frames for superseded zoom requests are deliberately skipped.
      // Start a new loss baseline so those intentional skips are not reported as transport drops.
      this.lastSequence = null;
    }
    if (this.lastSequence !== null && metadata.sequence > this.lastSequence + 1) {
      this.dropped += metadata.sequence - this.lastSequence - 1;
    }
    this.lastGeneration = metadata.generation;
    this.lastViewRevision = metadata.viewRevision;
    this.lastSequence = metadata.sequence;
    this.frame = decoded;
    this.framesThisSecond += 1;
    if (!this.viewport) {
      const startHz = this.frameStartHz(metadata);
      this.viewport = { startHz, endHz: startHz + metadata.bins * this.frameBinWidthHz(metadata) };
      this.displayViewport = { ...this.viewport };
    }
    this.hideBlocker();
    this.setState('live', 'Live · exclusive admin spectrum');
    this.updateReadouts();
    this.addWaterfallRow();
    this.requestFftRender();
    if (this.hoverRatio !== null) this.updateCursorReadout(this.hoverRatio);
  }

  frameBinWidthHz(metadata = this.frame?.metadata) {
    if (!metadata) return 0;
    return metadata.sampleRateHz / (metadata.fftSize || metadata.bins);
  }

  frameStartHz(metadata = this.frame?.metadata) {
    if (!metadata) return 0;
    return metadata.centerHz - metadata.sampleRateHz / 2 +
      (metadata.firstBin || 0) * this.frameBinWidthHz(metadata);
  }

  updateReadouts() {
    if (!this.frame) return;
    this.updateViewportReadouts();
    if (this.statsPanel.hidden) return;
    let peak = -Infinity;
    const bins = this.frame.bins;
    const range = this.visibleBinRange();
    for (let index = range.start; index < range.end; index += 1) peak = Math.max(peak, bins[index]);
    this.outputs.peak.textContent = `${peak.toFixed(1)} dB`;
    this.outputs.center.textContent = this.formatFrequency(this.frame.metadata.centerHz);
    this.outputs.bins.textContent = String(bins.length);
    this.outputs.fftSize.textContent = String(this.frame.metadata.fftSize || bins.length);
    this.outputs.dropped.textContent = String(this.dropped);
    this.outputs.generation.textContent = String(this.frame.metadata.generation);
    this.outputs.rate.textContent = `${this.fps.toFixed(1)} fps`;
  }

  updateViewportReadouts() {
    const viewport = this.viewport || this.fullViewport;
    if (!viewport) return;
    const span = viewport.endHz - viewport.startHz;
    const zoom = this.zoomAmount();
    if (!this.statsPanel.hidden) {
      this.outputs.span.textContent = this.formatSpan(span);
      this.outputs.zoom.textContent = `${zoom.toFixed(2)}×`;
    }
    this.resetButton.disabled = zoom <= 1.0001;
    this.plotArea.classList.toggle('zoomed', zoom > 1.0001);
    const labelViewportKey = `${viewport.startHz}:${viewport.endHz}:${this.fft?.clientWidth || 0}`;
    if (labelViewportKey !== this.activeChannelLabelViewportKey) {
      this.activeChannelLabelViewportKey = labelViewportKey;
      this.renderActiveChannelLabels();
    }
  }

  formatFrequency(frequencyHz) {
    return `${(frequencyHz / 1e6).toFixed(6)} MHz`;
  }

  formatSpan(spanHz) {
    if (spanHz >= 1e6) return `${(spanHz / 1e6).toFixed(3)} MHz`;
    if (spanHz >= 1e3) return `${(spanHz / 1e3).toFixed(1)} kHz`;
    return `${Math.round(spanHz)} Hz`;
  }

  zoomAmount() {
    if (!this.fullViewport || !this.viewport) return 1;
    return (this.fullViewport.endHz - this.fullViewport.startHz) /
      (this.viewport.endHz - this.viewport.startHz);
  }

  requestFftRender() {
    if (this.renderRequest !== null || this.closed) return;
    this.renderRequest = requestAnimationFrame(() => {
      this.renderRequest = null;
      if (!this.refining && !this.paused) this.drawFft();
    });
  }

  drawFft() {
    if (!this.frame || !this.fft.width) return;
    const bins = this.frame.bins;
    const context = this.fft.getContext('2d', { alpha: false });
    const width = this.fft.width;
    const height = this.fft.height;
    const range = this.visibleBinRange();
    const visibleBins = range.end - range.start;
    context.fillStyle = '#071018';
    context.fillRect(0, 0, width, height);
    this.drawDbGrid(context, width, height);
    context.strokeStyle = '#48dfca';
    context.lineWidth = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
    context.beginPath();
    for (let x = 0; x < width; x += 1) {
      const start = range.start + Math.floor(x * visibleBins / width);
      const end = Math.max(start + 1, range.start + Math.floor((x + 1) * visibleBins / width));
      let power = SIGNAL_PALETTE_MINIMUM_DB;
      for (let index = start; index < end && index < bins.length; index += 1) power = Math.max(power, bins[index]);
      const y = this.powerY(power, height);
      if (x === 0) context.moveTo(x, y); else context.lineTo(x, y);
    }
    context.stroke();
    this.drawActiveChannelConnectors();
  }

  drawDbGrid(context, width, height) {
    const range = -this.dbFloor;
    const targetStep = range / 6;
    const choices = [5, 10, 20, 25, 50];
    const step = choices.find((candidate) => candidate >= targetStep) || 50;
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    context.font = `${Math.round(10 * ratio)}px ui-monospace, SFMono-Regular, Menlo, monospace`;
    context.textBaseline = 'middle';
    context.lineWidth = 1;
    for (let power = -step; power > this.dbFloor; power -= step) {
      const y = Math.round(this.powerY(power, height)) + 0.5;
      context.strokeStyle = '#233746';
      context.beginPath();
      context.moveTo(0, y);
      context.lineTo(width, y);
      context.stroke();
      const label = `${power} dB`;
      const labelWidth = context.measureText(label).width + 8 * ratio;
      context.fillStyle = 'rgb(7 16 24 / 82%)';
      context.fillRect(4 * ratio, y - 8 * ratio, labelWidth, 16 * ratio);
      context.fillStyle = '#8fa8b7';
      context.fillText(label, 8 * ratio, y);
    }
  }

  powerY(power, height) {
    const normalized = Math.max(0, Math.min(1, (power - this.dbFloor) / -this.dbFloor));
    return height - normalized * height;
  }

  rebuildPalette() {
    const entries = this.paletteLut.length / 3;
    for (let index = 0; index < entries; index += 1) {
      const power = SIGNAL_PALETTE_MINIMUM_DB + index * SIGNAL_PALETTE_STEP_DB;
      const value = Math.max(0, Math.min(1, (power - this.dbFloor) / -this.dbFloor));
      const offset = index * 3;
      this.paletteLut[offset] = Math.round(255 * Math.max(0, Math.min(1, value * 2 - 0.35)));
      this.paletteLut[offset + 1] = Math.round(255 * Math.max(0, 1 - Math.abs(value - 0.58) * 2.4));
      this.paletteLut[offset + 2] = Math.round(255 * Math.max(0, Math.min(1, 1.25 - value * 1.7)));
    }
  }

  paletteOffset(power) {
    const index = Math.max(0, Math.min(this.paletteLut.length / 3 - 1,
      Math.round((power - SIGNAL_PALETTE_MINIMUM_DB) / SIGNAL_PALETTE_STEP_DB)));
    return index * 3;
  }

  addWaterfallRow() {
    if (!this.frame || !this.waterfall.width) return;
    const bins = this.frame.bins;
    const context = this.waterfall.getContext('2d', { alpha: false });
    const width = this.waterfall.width;
    const height = this.waterfall.height;
    const range = this.visibleBinRange();
    const visibleBins = range.end - range.start;
    this.waterfallScrollAccumulator += this.waterfallSpeed;
    const rowCount = Math.min(height, Math.floor(this.waterfallScrollAccumulator));
    if (rowCount < 1) return;
    this.waterfallScrollAccumulator -= rowCount;
    context.drawImage(this.waterfall, 0, 0, width, height - rowCount, 0, rowCount, width, height - rowCount);
    if (!this.waterfallRow || this.waterfallRow.width !== width) {
      this.waterfallRow = context.createImageData(width, 1);
    }
    const data = this.waterfallRow.data;
    for (let x = 0; x < width; x += 1) {
      const index = range.start + Math.min(visibleBins - 1, Math.floor(x * visibleBins / width));
      const bin = bins[index];
      const color = this.paletteOffset(bin);
      const offset = x * 4;
      data[offset] = this.paletteLut[color];
      data[offset + 1] = this.paletteLut[color + 1];
      data[offset + 2] = this.paletteLut[color + 2];
      data[offset + 3] = 255;
    }
    for (let row = 0; row < rowCount; row += 1) context.putImageData(this.waterfallRow, 0, row);
  }

  visibleBinRange() {
    if (!this.frame || !this.viewport) {
      this.binRange.start = 0;
      this.binRange.end = this.frame?.bins.length || 0;
      return this.binRange;
    }
    const binWidth = this.frameBinWidthHz();
    const frameStart = this.frameStartHz();
    const start = Math.max(0, Math.min(this.frame.bins.length - 1,
      Math.floor((this.viewport.startHz - frameStart) / binWidth)));
    const end = Math.max(start + 1, Math.min(this.frame.bins.length,
      Math.ceil((this.viewport.endHz - frameStart) / binWidth)));
    this.binRange.start = start;
    this.binRange.end = end;
    return this.binRange;
  }

  changeDbFloor() {
    const candidate = Number(this.floorInput.value);
    if (!Number.isFinite(candidate)) return;
    this.dbFloor = Math.max(SIGNAL_MINIMUM_DB_FLOOR, Math.min(SIGNAL_MAXIMUM_DB_FLOOR, candidate));
    this.floorValue.textContent = `${this.dbFloor} dB`;
    signalStoreNumber(SIGNAL_DB_FLOOR_STORAGE_KEY, this.dbFloor);
    this.rebuildPalette();
    this.requestFftRender();
  }

  changeWaterfallSpeed() {
    const candidate = Number(this.speedInput.value);
    if (!Number.isFinite(candidate)) return;
    this.waterfallSpeed = Math.max(0.25, Math.min(4, candidate));
    this.speedValue.textContent = `${this.waterfallSpeed.toFixed(2)}×`;
    signalStoreNumber(SIGNAL_WATERFALL_SPEED_STORAGE_KEY, this.waterfallSpeed);
  }

  connectActiveChannels() {
    if (this.closed || this.activeChannelSource) return;
    const source = new EventSource('/live/systems');
    this.activeChannelSource = source;
    const read = (event, callback) => {
      try {
        callback(JSON.parse(event.data));
      } catch (error) {
        // A malformed optional activity update must never interrupt the spectrum display.
      }
    };
    source.addEventListener('snapshot', (event) => read(event, (snapshot) => {
      this.activeChannelTables.clear();
      (Array.isArray(snapshot?.tables) ? snapshot.tables : []).forEach((table) => {
        if (table?.table_id) this.activeChannelTables.set(String(table.table_id), table);
      });
      this.renderActiveChannelLabels();
    }));
    source.addEventListener('activity_table', (event) => read(event, (update) => {
      const id = String(update?.table_id || update?.table?.table_id || '');
      if (!id) return;
      if (update.operation === 'remove') this.activeChannelTables.delete(id);
      else if (update.table) this.activeChannelTables.set(id, update.table);
      this.renderActiveChannelLabels();
    }));
  }

  disconnectActiveChannels() {
    this.activeChannelSource?.close();
    this.activeChannelSource = null;
    this.activeChannelTables.clear();
    this.activeChannelLabelRows = [];
    this.activeChannelLabelSignature = '';
    this.activeChannelLabelViewportKey = '';
    this.activeChannelLabels?.replaceChildren();
    this.clearActiveChannelConnectors();
  }

  activeChannelRows() {
    const viewport = this.viewport || this.fullViewport;
    if (!viewport || viewport.endHz <= viewport.startHz) return [];
    const statusPriority = { ENCRYPTED: 4, CALL: 3, DATA: 2, CONTROL: 1 };
    const channels = new Map();
    this.activeChannelTables.forEach((table) => {
      (Array.isArray(table?.rows) ? table.rows : []).forEach((row) => {
        const frequencyHz = Number(row?.frequency_hz);
        const status = String(row?.status || '').toUpperCase();
        if (!Number.isFinite(frequencyHz) || frequencyHz < viewport.startHz || frequencyHz > viewport.endHz ||
            !status || status === 'IDLE') return;
        const previous = channels.get(frequencyHz);
        if (!previous || (statusPriority[status] || 0) > (statusPriority[previous.status] || 0)) {
          channels.set(frequencyHz, { ...row, status });
        }
      });
    });
    return [...channels.entries()].sort((left, right) => left[0] - right[0]).slice(0, 48)
      .map(([frequencyHz, row]) => ({ ...row, frequencyHz }));
  }

  renderActiveChannelLabels() {
    if (!this.activeChannelLabels) return;
    const viewport = this.viewport || this.fullViewport;
    if (!viewport || viewport.endHz <= viewport.startHz) {
      this.activeChannelLabelRows = [];
      this.activeChannelLabels.replaceChildren();
      this.clearActiveChannelConnectors();
      return;
    }
    const rows = this.activeChannelRows().slice(0, 24);
    const availableWidth = Math.max(320, this.fft?.clientWidth || 320);
    const maximumPerLane = Math.max(1, Math.floor(availableWidth / 150));
    const laneCount = Math.max(1, Math.min(4, Math.ceil(rows.length / maximumPerLane)));
    const laneCounts = Array.from({ length: laneCount }, (_, lane) =>
      rows.filter((row, index) => index % laneCount === lane).length);
    this.activeChannelLabelRows = rows.map((row, index) => {
      const lane = index % laneCount;
      const slot = Math.floor(index / laneCount);
      return { ...row, lane, labelRatio: (slot + 0.5) / Math.max(1, laneCounts[lane]) };
    });
    const signature = `${Math.round(availableWidth)}|${this.activeChannelLabelRows.map((row) =>
      `${row.frequencyHz}:${row.status}:${row.target_alias || row.target_id || row.channel_name || row.lcn || ''}:${row.lane}:${row.labelRatio}`).join('|')}`;
    if (signature === this.activeChannelLabelSignature) return;
    this.activeChannelLabelSignature = signature;
    const labels = this.activeChannelLabelRows.map((row) => {
      const label = this.element('span', `wideband-active-channel-label status-${row.status.toLowerCase()}`,
        row.target_alias || row.target_id || row.channel_name || row.lcn || row.status);
      label.style.left = `${row.labelRatio * 100}%`;
      label.style.setProperty('--channel-label-lane', String(row.lane));
      return label;
    });
    this.activeChannelLabels.replaceChildren(...labels);
    this.requestFftRender();
  }

  clearActiveChannelConnectors() {
    const context = this.activeChannelConnectors?.getContext('2d');
    if (context) context.clearRect(0, 0,
      this.activeChannelConnectors.width, this.activeChannelConnectors.height);
  }

  drawActiveChannelConnectors() {
    const canvas = this.activeChannelConnectors;
    if (!canvas?.width || !canvas.height) return;
    const context = canvas.getContext('2d');
    context.clearRect(0, 0, canvas.width, canvas.height);
    if (!this.frame || !this.activeChannelLabelRows.length) return;
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    const colors = { ENCRYPTED: '#ff9b8b', CONTROL: '#f1c86b', DATA: '#69ddff' };
    this.activeChannelLabelRows.forEach((row) => {
      const peak = this.activeSignalPeak(row, canvas.width, canvas.height);
      if (!peak) return;
      const labelX = row.labelRatio * canvas.width;
      const labelY = (7 + row.lane * 24 + 18) * ratio;
      context.strokeStyle = colors[row.status] || '#6ee7a5';
      context.fillStyle = context.strokeStyle;
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(labelX, labelY);
      context.lineTo(peak.x, peak.y);
      context.stroke();
      context.beginPath();
      context.arc(peak.x, peak.y, Math.max(1.5, ratio), 0, Math.PI * 2);
      context.fill();
    });
  }

  activeSignalPeak(row, canvasWidth, canvasHeight) {
    const bins = this.frame?.bins;
    const binWidthHz = this.frameBinWidthHz();
    if (!bins?.length || !Number.isFinite(binWidthHz) || binWidthHz <= 0) return null;
    const center = Math.round((row.frequencyHz - this.frameStartHz()) / binWidthHz);
    const visible = this.visibleBinRange();
    if (center < visible.start || center >= visible.end) return null;
    const radius = Math.max(1, Math.min(32, Math.ceil(5_000 / binWidthHz)));
    const start = Math.max(visible.start, center - radius);
    const end = Math.min(visible.end - 1, center + radius);
    let peak = center;
    for (let index = start; index <= end; index += 1) {
      if (bins[index] > bins[peak]) peak = index;
    }
    const visibleBins = visible.end - visible.start;
    return {
      x: (peak - visible.start + 0.5) / visibleBins * canvasWidth,
      y: this.powerY(bins[peak], canvasHeight)
    };
  }

  clearWaterfall() {
    const context = this.waterfall?.getContext('2d', { alpha: false });
    if (context) {
      context.fillStyle = '#071018';
      context.fillRect(0, 0, this.waterfall.width, this.waterfall.height);
    }
  }

  clearPlots() {
    const fftContext = this.fft?.getContext('2d', { alpha: false });
    if (fftContext) {
      fftContext.fillStyle = '#071018';
      fftContext.fillRect(0, 0, this.fft.width, this.fft.height);
    }
    this.clearWaterfall();
    this.hideCursor();
  }

  onWheel(event) {
    if (!this.canInteract()) return;
    event.preventDefault();
    const rect = event.currentTarget.getBoundingClientRect();
    const anchor = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
    const factor = event.deltaY < 0 ? 1 / SIGNAL_ZOOM_FACTOR : SIGNAL_ZOOM_FACTOR;
    this.zoomAt(anchor, factor);
  }

  onKeyDown(event) {
    if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
      event.preventDefault();
      if (!this.blocker.hidden) return;
      const rect = event.currentTarget.getBoundingClientRect();
      this.showStats(event.currentTarget, rect.left + rect.width / 2, rect.top + rect.height / 2, true);
      return;
    }
    if (!this.canInteract()) return;
    if (event.key === '+' || event.key === '=') {
      event.preventDefault();
      this.zoomAt(0.5, 1 / SIGNAL_ZOOM_FACTOR);
    } else if (event.key === '-' || event.key === '_') {
      event.preventDefault();
      this.zoomAt(0.5, SIGNAL_ZOOM_FACTOR);
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      const direction = event.key === 'ArrowLeft' ? -1 : 1;
      this.panBy(direction * (this.viewport.endHz - this.viewport.startHz) * 0.1, true);
    } else if (event.key === 'r' || event.key === 'R' || event.key === '0' || event.key === 'Home') {
      event.preventDefault();
      this.resetZoom();
    }
  }

  onPointerDown(event) {
    if (!this.canInteract() || this.zoomAmount() <= 1.0001 || event.button !== 0) return;
    event.preventDefault();
    this.cancelDrag();
    event.currentTarget.setPointerCapture(event.pointerId);
    event.currentTarget.classList.add('dragging');
    this.drag = { pointerId: event.pointerId, lastX: event.clientX, canvas: event.currentTarget };
  }

  onPointerMove(event) {
    const rect = event.currentTarget.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
    const yRatio = Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height));
    this.showCursor(ratio, event.currentTarget, yRatio);
    if (!this.drag || this.drag.pointerId !== event.pointerId || this.drag.canvas !== event.currentTarget) return;
    if (!this.canInteract()) {
      this.cancelDrag();
      return;
    }
    const deltaPixels = event.clientX - this.drag.lastX;
    this.drag.lastX = event.clientX;
    const deltaHz = -deltaPixels / rect.width * (this.viewport.endHz - this.viewport.startHz);
    this.panBy(deltaHz, false);
  }

  onPointerLeave(event) {
    if (!this.drag || this.drag.pointerId !== event.pointerId) this.hideCursor();
  }

  onPointerUp(event) {
    if (!this.drag || this.drag.pointerId !== event.pointerId) return;
    this.cancelDrag();
    if (this.canInteract()) this.queueViewportUpdate(true);
  }

  onLostPointerCapture(event) {
    if (!this.drag || this.drag.pointerId !== event.pointerId || this.drag.canvas !== event.currentTarget) return;
    this.cancelDrag(false);
    if (this.canInteract()) this.queueViewportUpdate(true);
  }

  onContextMenu(event) {
    event.preventDefault();
    if (!this.blocker.hidden) return;
    this.showStats(event.currentTarget, event.clientX, event.clientY, false);
  }

  showStats(originCanvas, clientX, clientY, focusPanel) {
    this.statsOriginCanvas = originCanvas;
    this.statsPanel.hidden = false;
    this.updateReadouts();
    const plotRect = this.plotArea.getBoundingClientRect();
    const panelWidth = this.statsPanel.offsetWidth;
    const panelHeight = this.statsPanel.offsetHeight;
    const maximumLeft = Math.max(8, plotRect.width - panelWidth - 8);
    const maximumTop = Math.max(8, plotRect.height - panelHeight - 8);
    const left = Math.max(8, Math.min(maximumLeft, clientX - plotRect.left + 8));
    const top = Math.max(8, Math.min(maximumTop, clientY - plotRect.top + 8));
    this.statsPanel.style.left = `${left}px`;
    this.statsPanel.style.top = `${top}px`;
    if (focusPanel) this.statsCloseButton.focus({ preventScroll: true });
  }

  hideStats(restoreFocus = false) {
    if (!this.statsPanel || this.statsPanel.hidden) return;
    this.statsPanel.hidden = true;
    this.statsPanel.style.left = '';
    this.statsPanel.style.top = '';
    if (restoreFocus) this.statsOriginCanvas?.focus({ preventScroll: true });
    this.statsOriginCanvas = null;
  }

  cancelDrag(releaseCapture = true) {
    const drag = this.drag;
    this.drag = null;
    if (!drag) return;
    drag.canvas.classList.remove('dragging');
    if (releaseCapture && drag.canvas.hasPointerCapture(drag.pointerId)) {
      try {
        drag.canvas.releasePointerCapture(drag.pointerId);
      } catch (error) {
        // Pointer capture can disappear as a tab is hidden or a canvas is detached. State is already safely cleared.
      }
    }
  }

  canInteract() {
    return !this.closed && !this.paused && !!this.fullViewport && !!this.viewport && this.blocker.hidden;
  }

  zoomAt(anchor, factor) {
    const fullSpan = this.fullViewport.endHz - this.fullViewport.startHz;
    const oldSpan = this.viewport.endHz - this.viewport.startHz;
    const newSpan = Math.max(fullSpan / SIGNAL_MAXIMUM_ZOOM, Math.min(fullSpan, oldSpan * factor));
    const frequency = this.viewport.startHz + anchor * oldSpan;
    let startHz = frequency - anchor * newSpan;
    let endHz = startHz + newSpan;
    ({ startHz, endHz } = this.clampViewport(startHz, endHz));
    this.applyViewport({ startHz, endHz });
  }

  panBy(deltaHz, requestImmediately) {
    if (this.zoomAmount() <= 1.0001) return;
    const candidate = this.clampViewport(this.viewport.startHz + deltaHz, this.viewport.endHz + deltaHz);
    this.applyViewport(candidate, requestImmediately ? 'immediate' : 'deferred');
  }

  clampViewport(startHz, endHz) {
    const span = endHz - startHz;
    if (startHz < this.fullViewport.startHz) {
      startHz = this.fullViewport.startHz;
      endHz = startHz + span;
    }
    if (endHz > this.fullViewport.endHz) {
      endHz = this.fullViewport.endHz;
      startHz = endHz - span;
    }
    return { startHz, endHz };
  }

  applyViewport(nextViewport, requestMode = 'debounced') {
    const previousDisplay = this.displayViewport || this.viewport;
    if (!previousDisplay || !nextViewport || nextViewport.endHz <= nextViewport.startHz) return;
    if (Math.abs(nextViewport.startHz - this.viewport.startHz) < 0.5 &&
        Math.abs(nextViewport.endHz - this.viewport.endHz) < 0.5) return;
    this.transformPlots(previousDisplay, nextViewport);
    this.viewport = nextViewport;
    this.displayViewport = { ...nextViewport };
    this.viewportIntentVersion += 1;
    this.setRefining(true);
    this.updateViewportReadouts();
    if (this.hoverRatio !== null) this.updateCursorReadout(this.hoverRatio);
    if (requestMode === 'immediate') this.queueViewportUpdate(true);
    else if (requestMode === 'debounced') this.queueViewportUpdate(false);
  }

  resetZoom() {
    if (!this.fullViewport || !this.viewport || this.zoomAmount() <= 1.0001) return;
    this.applyViewport({ ...this.fullViewport }, 'immediate');
  }

  transformPlots(fromViewport, toViewport) {
    this.transformCanvas(this.fft, this.fftScratch, fromViewport, toViewport);
    this.transformCanvas(this.waterfall, this.waterfallScratch, fromViewport, toViewport);
  }

  transformCanvas(canvas, scratch, fromViewport, toViewport, transparent = false) {
    if (!canvas.width || !canvas.height) return;
    if (scratch.width !== canvas.width || scratch.height !== canvas.height) {
      scratch.width = canvas.width;
      scratch.height = canvas.height;
    }
    const scratchContext = scratch.getContext('2d', { alpha: transparent });
    scratchContext.clearRect(0, 0, scratch.width, scratch.height);
    scratchContext.drawImage(canvas, 0, 0);
    const context = canvas.getContext('2d', { alpha: transparent });
    if (transparent) context.clearRect(0, 0, canvas.width, canvas.height);
    else {
      context.fillStyle = '#071018';
      context.fillRect(0, 0, canvas.width, canvas.height);
    }
    const overlapStart = Math.max(fromViewport.startHz, toViewport.startHz);
    const overlapEnd = Math.min(fromViewport.endHz, toViewport.endHz);
    if (overlapEnd <= overlapStart) return;
    const fromSpan = fromViewport.endHz - fromViewport.startHz;
    const toSpan = toViewport.endHz - toViewport.startHz;
    const sourceX = (overlapStart - fromViewport.startHz) / fromSpan * canvas.width;
    const sourceWidth = (overlapEnd - overlapStart) / fromSpan * canvas.width;
    const destinationX = (overlapStart - toViewport.startHz) / toSpan * canvas.width;
    const destinationWidth = (overlapEnd - overlapStart) / toSpan * canvas.width;
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    context.drawImage(scratch, sourceX, 0, sourceWidth, canvas.height,
      destinationX, 0, destinationWidth, canvas.height);
  }

  queueViewportUpdate(immediate) {
    window.clearTimeout(this.refinementTimer);
    this.refinementTimer = null;
    if (immediate) this.sendViewportUpdate();
    else this.refinementTimer = window.setTimeout(() => {
      this.refinementTimer = null;
      this.sendViewportUpdate();
    }, SIGNAL_REFINEMENT_DELAY_MS);
  }

  sendViewportUpdate() {
    if (!this.viewport || this.socket?.readyState !== WebSocket.OPEN) return;
    const requestId = this.nextRequestId();
    this.pendingRequestId = requestId;
    this.sentViewportIntentVersion = this.viewportIntentVersion;
    this.deferredFrame = null;
    this.setRefining(true);
    this.socket.send(JSON.stringify({
      action: 'update',
      requestId,
      maxFps: SIGNAL_DEFAULT_FPS,
      targetId: this.selectedTargetId() || undefined,
      viewport: { startHz: Math.round(this.viewport.startHz), endHz: Math.round(this.viewport.endHz) }
    }));
    this.armSignalFrameTimeout();
  }

  showCursor(ratio, canvas, yRatio) {
    this.hoverRatio = ratio;
    this.hoverCanvas = canvas;
    this.hoverYRatio = yRatio;
    const percentage = `${(ratio * 100).toFixed(3)}%`;
    this.fftGuide.style.left = percentage;
    this.waterfallGuide.style.left = percentage;
    this.fftGuide.hidden = false;
    this.waterfallGuide.hidden = false;
    this.updateCursorReadout(ratio);
  }

  hideCursor() {
    this.hoverRatio = null;
    this.hoverCanvas = null;
    this.hoverYRatio = null;
    this.fftGuide.hidden = true;
    this.waterfallGuide.hidden = true;
    this.cursorPopup.hidden = true;
    this.cursorFrequency.textContent = '';
    this.cursorPower.textContent = '';
  }

  updateCursorReadout(ratio) {
    const viewport = this.viewport || this.fullViewport;
    if (!viewport) {
      this.cursorPopup.hidden = true;
      return;
    }
    const frequencyHz = viewport.startHz + ratio * (viewport.endHz - viewport.startHz);
    this.cursorFrequency.textContent = this.formatFrequency(frequencyHz);
    let power = '—';
    if (!this.frame) {
      power = '—';
    } else {
      const binWidth = this.frameBinWidthHz();
      const frameStart = this.frameStartHz();
      let index = Math.floor((frequencyHz - frameStart) / binWidth);
      const frameEnd = frameStart + this.frame.bins.length * binWidth;
      if (index === this.frame.bins.length && frequencyHz <= frameEnd + 0.5) index -= 1;
      if (index < 0 || index >= this.frame.bins.length) {
        power = this.refining ? 'Refining…' : '—';
      } else {
        power = `${this.frame.bins[index].toFixed(1)} dB`;
      }
    }
    this.cursorPower.textContent = power;
    this.cursorPopup.hidden = false;
    this.positionCursorPopup();
  }

  positionCursorPopup() {
    if (this.cursorPopup.hidden || !this.hoverCanvas || this.hoverYRatio === null || this.hoverRatio === null) return;
    const plotRect = this.plotArea.getBoundingClientRect();
    const canvasRect = this.hoverCanvas.getBoundingClientRect();
    const pointerX = canvasRect.left - plotRect.left + this.hoverRatio * canvasRect.width;
    const pointerY = canvasRect.top - plotRect.top + this.hoverYRatio * canvasRect.height;
    const width = this.cursorPopup.offsetWidth;
    const height = this.cursorPopup.offsetHeight;
    const maximumLeft = Math.max(6, plotRect.width - width - 6);
    const left = Math.max(6, Math.min(maximumLeft, pointerX - width / 2));
    const top = Math.max(6, pointerY - height - 10);
    this.cursorPopup.style.left = `${left}px`;
    this.cursorPopup.style.top = `${top}px`;
  }

  updateTimedReadouts() {
    if (this.closed) return;
    const now = performance.now();
    if (now - this.lastFpsTick >= 1000) {
      this.fps = this.framesThisSecond * 1000 / (now - this.lastFpsTick);
      this.framesThisSecond = 0;
      this.lastFpsTick = now;
      if (!this.statsPanel.hidden) this.outputs.rate.textContent = `${this.fps.toFixed(1)} fps`;
    }
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    document.removeEventListener('visibilitychange', this.onVisibility);
    document.removeEventListener('pointerdown', this.onDocumentPointerDown);
    document.removeEventListener('keydown', this.onDocumentKeyDown);
    this.resizeObserver?.disconnect();
    window.clearTimeout(this.retryTimer);
    window.clearTimeout(this.readyTimer);
    window.clearTimeout(this.refinementTimer);
    window.clearInterval(this.statusTimer);
    if (this.renderRequest !== null) cancelAnimationFrame(this.renderRequest);
    this.disconnectActiveChannels();
    this.disconnectSocket('page closed');
    this.worker.terminate();
    this.frame = null;
    this.deferredFrame = null;
  }
}

window.WidebandSignalView = WidebandSignalView;
