class WebCallPlayer {
  static MAXIMUM_QUEUE_KEY = 'sdrtrunk-vce.web-player.maximum-queued-calls';

  constructor(ids) {
    this.ui = Object.fromEntries(Object.entries(ids).map(([key, id]) => [key, document.getElementById(id)]));
    this.queue = [];
    this.avoids = new Set();
    this.holdTarget = null;
    this.current = null;
    this.currentBuffer = null;
    this.source = null;
    this.audioContext = null;
    this.loadToken = 0;
    this.dropped = 0;
    this.muted = true;
    this.maximumQueued = this.readMaximumQueued();
    this.ui.maximumQueued.value = this.maximumQueued;
    this.bindControls();
    this.render();
  }

  connect(url) {
    const events = new EventSource(url);
    events.addEventListener('call', (event) => this.enqueue(JSON.parse(event.data)));
    events.onopen = () => this.setStatus(this.muted ? 'Muted' : this.current ? 'Listening' : 'Waiting');
    events.onerror = () => this.setStatus('Reconnecting');
    return events;
  }

  // Recorded-call pages can feed this same queue by providing the same call shape and an audio_url.
  enqueue(call) {
    if (!call?.audio_url || !this.isAllowed(call)) return;

    while (this.queue.length >= this.maximumQueued) {
      this.queue.shift();
      this.dropped++;
    }

    this.queue.push(call);
    this.render();
    if (!this.muted && !this.current) this.playNext();
  }

  bindControls() {
    this.ui.mute.addEventListener('click', () => this.toggleMute());
    this.ui.hold.addEventListener('click', () => this.toggleHold());
    this.ui.avoid.addEventListener('click', () => this.avoidCurrent());
    this.ui.clear.addEventListener('click', () => {
      this.avoids.clear();
      this.render();
    });
    this.ui.skip.addEventListener('click', () => this.skip());
    this.ui.maximumQueued.addEventListener('change', () => this.changeMaximumQueued());
  }

  async toggleMute() {
    this.muted = !this.muted;

    if (this.muted) {
      if (this.audioContext?.state === 'running') await this.audioContext.suspend();
      this.setStatus('Muted');
    } else {
      this.ensureAudioContext();
      await this.audioContext.resume();
      if (this.source) this.setStatus('Listening');
      else if (this.currentBuffer) this.startCurrent();
      else if (!this.current) this.playNext();
    }

    this.render();
  }

  toggleHold() {
    if (this.holdTarget) {
      this.holdTarget = null;
    } else if (this.current) {
      this.holdTarget = this.targetKey(this.current);
      this.queue = this.queue.filter((call) => this.targetKey(call) === this.holdTarget);
    }

    this.render();
  }

  avoidCurrent() {
    if (!this.current) return;
    const target = this.targetKey(this.current);
    this.avoids.add(target);
    if (this.holdTarget === target) this.holdTarget = null;
    this.queue = this.queue.filter((call) => this.targetKey(call) !== target);
    this.stopCurrent();
    this.playNext();
  }

  skip() {
    if (this.current) this.stopCurrent();
    else if (this.queue.length) this.queue.shift();
    this.playNext();
  }

  async playNext() {
    if (this.muted || this.current) return;

    while (this.queue.length && !this.isAllowed(this.queue[0])) this.queue.shift();
    if (!this.queue.length) {
      this.setStatus('Waiting');
      this.render();
      return;
    }

    this.current = this.queue.shift();
    this.currentBuffer = null;
    const token = ++this.loadToken;
    this.setStatus('Buffering');
    this.render();

    try {
      const response = await fetch(this.current.audio_url, { cache: 'no-store' });
      if (!response.ok) throw new Error(`Audio returned ${response.status}`);
      this.ensureAudioContext();
      const data = await response.arrayBuffer();
      const buffer = await this.audioContext.decodeAudioData(data);
      if (token !== this.loadToken || !this.current) return;
      this.currentBuffer = buffer;
      if (!this.muted) this.startCurrent();
    } catch (_) {
      if (token === this.loadToken) {
        this.stopCurrent();
        this.setStatus('Skipped unavailable call');
        setTimeout(() => this.playNext(), 150);
      }
    }
  }

  startCurrent() {
    if (!this.current || !this.currentBuffer || this.muted || this.source) return;
    const token = this.loadToken;
    const source = this.audioContext.createBufferSource();
    source.buffer = this.currentBuffer;
    source.connect(this.audioContext.destination);
    source.onended = () => {
      if (token !== this.loadToken || source !== this.source) return;
      this.source = null;
      this.current = null;
      this.currentBuffer = null;
      this.render();
      this.playNext();
    };
    this.source = source;
    source.start();
    this.setStatus('Listening');
    this.render();
  }

  stopCurrent() {
    this.loadToken++;
    const source = this.source;
    this.source = null;
    this.current = null;
    this.currentBuffer = null;
    if (source) {
      try { source.stop(); } catch (_) { }
      source.disconnect();
    }
    this.render();
  }

  changeMaximumQueued() {
    const requested = Math.trunc(Number(this.ui.maximumQueued.value));
    this.maximumQueued = Math.min(500, Math.max(1, Number.isFinite(requested) ? requested : 100));
    this.ui.maximumQueued.value = this.maximumQueued;
    localStorage.setItem(WebCallPlayer.MAXIMUM_QUEUE_KEY, String(this.maximumQueued));

    while (this.queue.length > this.maximumQueued) {
      this.queue.shift();
      this.dropped++;
    }
    this.render();
  }

  readMaximumQueued() {
    const saved = Math.trunc(Number(localStorage.getItem(WebCallPlayer.MAXIMUM_QUEUE_KEY)));
    return Number.isFinite(saved) && saved >= 1 && saved <= 500 ? saved : 100;
  }

  ensureAudioContext() {
    if (!this.audioContext) {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      this.audioContext = new AudioContext();
    }
  }

  isAllowed(call) {
    const target = this.targetKey(call);
    return !this.avoids.has(target) && (!this.holdTarget || this.holdTarget === target);
  }

  targetKey(call) {
    const system = call.system || '';
    if (call.target_id !== null && call.target_id !== undefined && call.target_id !== '') {
      return `${system}|target:${call.target_id}`;
    }
    if (call.channel) return `${system}|channel:${call.channel}`;
    return `${system}|frequency:${call.frequency_hz || 0}`;
  }

  callLabel(call) {
    const target = call.target_alias || call.target_id || call.channel || 'Unknown target';
    const source = call.source_alias || call.source_id;
    return `${target}${source ? ` · ${source}` : ''}`;
  }

  setStatus(value) {
    this.ui.status.textContent = value;
  }

  render() {
    const activelyPlaying = Boolean(this.source);
    this.ui.current.textContent = activelyPlaying && this.current ? this.callLabel(this.current) : 'Idle';
    this.ui.queued.textContent = String(this.queue.length);
    this.ui.dropped.textContent = this.dropped ? ` · Dropped ${this.dropped}` : '';
    this.ui.queueList.replaceChildren();
    this.queue.slice(0, 100).forEach((call, index) => {
      const item = document.createElement('div');
      item.className = 'playback-queue-item';
      item.textContent = `${index + 1}. ${this.callLabel(call)}`;
      this.ui.queueList.append(item);
    });
    if (!this.queue.length) {
      const empty = document.createElement('div');
      empty.className = 'muted';
      empty.textContent = 'No queued calls';
      this.ui.queueList.append(empty);
    }

    this.ui.mute.textContent = this.muted ? 'Unmute' : 'Mute';
    this.ui.mute.classList.toggle('active', !this.muted);
    this.ui.hold.classList.toggle('active', Boolean(this.holdTarget));
    this.ui.hold.disabled = !this.holdTarget && !this.current;
    this.ui.hold.title = this.holdTarget ? 'Release browser hold' : 'Hold the current target in this browser';
    this.ui.avoid.disabled = !this.current;
    this.ui.clear.disabled = !this.avoids.size;
    this.ui.clear.title = `Clear ${this.avoids.size} browser avoid(s)`;
    this.ui.skip.disabled = !this.current && !this.queue.length;
  }
}

window.WebCallPlayer = WebCallPlayer;
