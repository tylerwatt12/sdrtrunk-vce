/* global document, window, Worker, WebSocket, requestAnimationFrame, cancelAnimationFrame */
'use strict';

class WidebandSignalView {
  constructor(root) {
    this.root = root;
    this.closed = false;
    this.paused = false;
    this.hidden = document.hidden;
    this.socket = null;
    this.retryTimer = null;
    this.retryCount = 0;
    this.frame = null;
    this.lastSequence = null;
    this.dropped = 0;
    this.framesThisSecond = 0;
    this.fps = 0;
    this.lastFpsTick = performance.now();
    this.metadataLoaded = false;
    this.animation = null;
    this.worker = new Worker('/assets/signal-worker.js?v=1');
    this.worker.onmessage = (event) => this.onWorkerMessage(event.data);
    this.onVisibility = () => {
      this.hidden = document.hidden;
      if (this.hidden) this.unsubscribe();
      else if (!this.paused) this.subscribeOrConnect();
    };
    document.addEventListener('visibilitychange', this.onVisibility);
    this.build();
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(this.plotArea);
    this.subscribeOrConnect();
    this.animation = requestAnimationFrame(() => this.render());
  }

  element(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = String(text);
    return element;
  }

  readout(label, id, value = '—') {
    const row = this.element('div', 'wideband-readout');
    row.append(this.element('span', '', label));
    const output = this.element('output', '', value);
    output.id = id;
    row.append(output);
    return row;
  }

  build() {
    this.root.className = 'wideband-page';
    const header = this.element('div', 'wideband-heading');
    const titles = this.element('div');
    titles.append(this.element('h1', 'page-title', 'Wideband spectrum'),
      this.element('div', 'page-subtitle', 'Shared read-only FFT and browser-local waterfall'));
    this.access = this.element('span', 'wideband-access', 'PUBLIC VIEW');
    header.append(titles, this.access);

    const toolbar = this.element('div', 'wideband-toolbar');
    const target = this.element('div', 'wideband-target');
    this.targetName = this.element('strong', '', 'Available tuner');
    target.append(this.targetName,
      this.element('span', '', 'Shared stream • passive viewers'));
    this.status = this.element('div', 'wideband-status connecting', 'Connecting to shared signal stream');
    this.pauseButton = this.element('button', 'button secondary', 'Pause');
    this.pauseButton.type = 'button';
    this.pauseButton.onclick = () => this.togglePause();
    toolbar.append(target, this.status, this.pauseButton);

    const workspace = this.element('div', 'wideband-workspace');
    this.plotArea = this.element('div', 'wideband-plots');
    const fftWrap = this.element('section', 'wideband-plot');
    fftWrap.append(this.element('div', 'wideband-plot-label', 'FFT'));
    this.fft = this.element('canvas', 'wideband-canvas');
    this.fft.setAttribute('aria-label', 'Live wideband spectrum plot');
    fftWrap.append(this.fft);
    const waterfallWrap = this.element('section', 'wideband-plot waterfall');
    waterfallWrap.append(this.element('div', 'wideband-plot-label', 'WATERFALL · newest at top'));
    this.waterfall = this.element('canvas', 'wideband-canvas');
    this.waterfall.setAttribute('aria-label', 'Live browser-local waterfall history');
    waterfallWrap.append(this.waterfall);
    this.plotArea.append(fftWrap, waterfallWrap);

    const readouts = this.element('aside', 'wideband-readouts');
    readouts.append(this.element('h2', '', 'Readouts'),
      this.readout('Peak', 'wideband-peak'),
      this.readout('Center', 'wideband-center'),
      this.readout('Span', 'wideband-span'),
      this.readout('Rate', 'wideband-rate'),
      this.readout('Bins', 'wideband-bins'),
      this.readout('Dropped', 'wideband-drops', '0'),
      this.readout('Generation', 'wideband-generation'));
    const note = this.element('p', 'wideband-note',
      'Zoom, palette, smoothing, and waterfall history stay in this browser. Viewing never retunes hardware.');
    readouts.append(note);
    workspace.append(this.plotArea, readouts);

    this.footer = this.element('div', 'wideband-footer', 'CONNECTING · no frame received');
    this.root.append(header, toolbar, workspace, this.footer);
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
    this.resizeCanvas(this.fft, 250);
    if (this.resizeCanvas(this.waterfall, 310)) this.clearWaterfall();
  }

  webSocketUrl() {
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${scheme}//${window.location.host}/api/v1/ws/signal`;
  }

  subscribeOrConnect() {
    if (this.closed || this.paused || this.hidden) return;
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify({ action: 'subscribe', maxFps: 20 }));
      return;
    }
    if (this.socket?.readyState === WebSocket.CONNECTING) return;
    this.setState('connecting', this.retryCount ? `Reconnecting · attempt ${this.retryCount + 1}` :
      'Connecting to shared signal stream');
    const socket = new WebSocket(this.webSocketUrl());
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    socket.onopen = () => {
      if (this.closed || socket !== this.socket) return socket.close();
      this.retryCount = 0;
      socket.send(JSON.stringify({ action: 'subscribe', maxFps: 20 }));
      this.refreshMetadata();
    };
    socket.onmessage = (event) => {
      if (event.data instanceof ArrayBuffer) this.worker.postMessage({ type: 'frame', buffer: event.data }, [event.data]);
    };
    socket.onclose = (event) => {
      if (socket !== this.socket) return;
      this.socket = null;
      if (this.closed || this.paused || this.hidden) return;
      if (event.code === 4401 || event.code === 4403) {
        this.setState('locked', 'Administrator sign-in required');
        this.footer.textContent = 'ADMIN ONLY · signal data cleared';
        this.frame = null;
        this.clearWaterfall();
        return;
      }
      this.setState('reconnecting', 'Signal stream disconnected · retrying');
      const delay = Math.min(10000, 500 * (2 ** Math.min(this.retryCount++, 4)));
      this.retryTimer = window.setTimeout(() => this.subscribeOrConnect(), delay);
    };
    socket.onerror = () => this.setState('reconnecting', 'Unable to reach signal stream');
  }

  async refreshMetadata() {
    try {
      const response = await fetch('/api/status', { cache: 'no-store' });
      if (!response.ok) return;
      const status = await response.json();
      if (status.signal?.target) this.targetName.textContent = status.signal.target;
      const mode = status.featureAccess?.modes?.['wideband-signal'];
      if (mode === 'ADMIN_ONLY') this.access.textContent = 'ADMIN VIEW';
    } catch (error) {
      // Signal data remains usable when unrelated status/statistics access is restricted.
    }
  }

  unsubscribe() {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify({ action: 'unsubscribe' }));
    }
  }

  togglePause() {
    this.paused = !this.paused;
    this.pauseButton.textContent = this.paused ? 'Resume' : 'Pause';
    if (this.paused) {
      this.unsubscribe();
      this.setState('paused', 'Paused in this browser');
    } else {
      this.subscribeOrConnect();
    }
  }

  setState(className, text) {
    this.status.className = `wideband-status ${className}`;
    this.status.textContent = text;
  }

  onWorkerMessage(message) {
    if (this.closed) return;
    if (message.type === 'error') {
      this.setState('degraded', message.message);
      return;
    }
    if (message.type !== 'frame') return;
    if (!this.metadataLoaded) {
      this.metadataLoaded = true;
      this.refreshMetadata();
    }
    const metadata = message.metadata;
    if (this.frame && metadata.generation !== this.frame.metadata.generation) this.clearWaterfall();
    if (this.lastSequence !== null && metadata.sequence > this.lastSequence + 1) {
      this.dropped += metadata.sequence - this.lastSequence - 1;
    }
    this.lastSequence = metadata.sequence;
    this.frame = { metadata, buffer: message.buffer, received: performance.now() };
    this.framesThisSecond++;
    this.setState('live', 'Live · shared signal stream');
    this.updateReadouts();
    this.addWaterfallRow();
  }

  bins() {
    return this.frame ? new Float32Array(this.frame.buffer, 80, this.frame.metadata.bins) : null;
  }

  updateReadouts() {
    const bins = this.bins();
    if (!bins) return;
    let peak = -Infinity;
    for (let index = 0; index < bins.length; index++) peak = Math.max(peak, bins[index]);
    document.getElementById('wideband-peak').textContent = `${peak.toFixed(1)} dB`;
    document.getElementById('wideband-center').textContent = `${(this.frame.metadata.centerHz / 1e6).toFixed(6)} MHz`;
    document.getElementById('wideband-span').textContent = `${(this.frame.metadata.sampleRateHz / 1e6).toFixed(3)} MHz`;
    document.getElementById('wideband-bins').textContent = String(bins.length);
    document.getElementById('wideband-drops').textContent = String(this.dropped);
    document.getElementById('wideband-generation').textContent = String(this.frame.metadata.generation);
  }

  drawFft() {
    const bins = this.bins();
    if (!bins || !this.fft.width) return;
    const context = this.fft.getContext('2d');
    const width = this.fft.width;
    const height = this.fft.height;
    context.fillStyle = '#071018';
    context.fillRect(0, 0, width, height);
    context.strokeStyle = '#233746';
    context.lineWidth = 1;
    for (let line = 1; line < 6; line++) {
      const y = Math.round(height * line / 6) + 0.5;
      context.beginPath(); context.moveTo(0, y); context.lineTo(width, y); context.stroke();
    }
    context.strokeStyle = '#48dfca';
    context.lineWidth = Math.max(1, window.devicePixelRatio || 1);
    context.beginPath();
    for (let x = 0; x < width; x++) {
      const start = Math.floor(x * bins.length / width);
      const end = Math.max(start + 1, Math.floor((x + 1) * bins.length / width));
      let power = -196;
      for (let index = start; index < end; index++) power = Math.max(power, bins[index]);
      const y = Math.max(0, Math.min(height, height - ((power + 140) / 140) * height));
      if (x === 0) context.moveTo(x, y); else context.lineTo(x, y);
    }
    context.stroke();
  }

  palette(power) {
    const value = Math.max(0, Math.min(1, (power + 130) / 110));
    const red = Math.round(255 * Math.max(0, Math.min(1, value * 2 - 0.35)));
    const green = Math.round(255 * Math.max(0, 1 - Math.abs(value - 0.58) * 2.4));
    const blue = Math.round(255 * Math.max(0, Math.min(1, 1.25 - value * 1.7)));
    return [red, green, blue];
  }

  addWaterfallRow() {
    const bins = this.bins();
    if (!bins || !this.waterfall.width) return;
    const context = this.waterfall.getContext('2d', { alpha: false });
    const width = this.waterfall.width;
    const height = this.waterfall.height;
    context.drawImage(this.waterfall, 0, 0, width, height - 1, 0, 1, width, height - 1);
    const row = context.createImageData(width, 1);
    for (let x = 0; x < width; x++) {
      const bin = bins[Math.min(bins.length - 1, Math.floor(x * bins.length / width))];
      const color = this.palette(bin);
      const offset = x * 4;
      row.data[offset] = color[0]; row.data[offset + 1] = color[1];
      row.data[offset + 2] = color[2]; row.data[offset + 3] = 255;
    }
    context.putImageData(row, 0, 0);
  }

  clearWaterfall() {
    const context = this.waterfall?.getContext('2d');
    if (context) { context.fillStyle = '#071018'; context.fillRect(0, 0, this.waterfall.width, this.waterfall.height); }
  }

  render() {
    if (this.closed) return;
    const now = performance.now();
    if (!this.paused) this.drawFft();
    if (now - this.lastFpsTick >= 1000) {
      this.fps = this.framesThisSecond * 1000 / (now - this.lastFpsTick);
      this.framesThisSecond = 0;
      this.lastFpsTick = now;
      document.getElementById('wideband-rate').textContent = `${this.fps.toFixed(1)} fps`;
    }
    if (this.frame) {
      const age = now - this.frame.received;
      this.footer.textContent = `${age < 1000 ? 'LIVE' : 'STALE'} · last frame ${age < 1000 ? '<1 s' :
        `${(age / 1000).toFixed(1)} s`} · browser history only · ${this.dropped} dropped`;
    }
    this.animation = requestAnimationFrame(() => this.render());
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.resizeObserver?.disconnect();
    window.clearTimeout(this.retryTimer);
    cancelAnimationFrame(this.animation);
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ action: 'unsubscribe' }));
    this.socket?.close(1000, 'page closed');
    this.worker.terminate();
    this.frame = null;
  }
}

window.WidebandSignalView = WidebandSignalView;
