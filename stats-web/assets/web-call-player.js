class WebCallPlayer {
  static MAXIMUM_QUEUE_KEY = 'sdrtrunk-vce.web-player.maximum-queued-calls';
  static VOLUME_KEY = 'sdrtrunk-vce.web-player.volume';

  constructor(ids) {
    this.ui = Object.fromEntries(Object.entries(ids).map(([key, id]) => [key, document.getElementById(id)]));
    this.queue = [];
    this.avoids = new Set();
    this.holdTarget = null;
    this.current = null;
    this.currentBuffer = null;
    this.source = null;
    this.gainNode = null;
    this.audioContext = null;
    this.playbackOffset = 0;
    this.playbackStartedAt = 0;
    this.progressFrame = null;
    this.transportToken = 0;
    this.events = null;
    this.loadToken = 0;
    this.dropped = 0;
    this.paused = true;
    this.volume = this.readVolume();
    this.maximumQueued = this.readMaximumQueued();
    this.ui.volume.value = String(this.volume);
    this.ui.maximumQueued.value = this.maximumQueued;
    this.bindControls();
    this.render();
  }

  connect(url) {
    if (this.events) this.events.close();
    const events = new EventSource(url);
    this.events = events;
    events.addEventListener('call', (event) => this.enqueue(JSON.parse(event.data)));
    events.onopen = () => this.setStatus(this.paused ? (this.currentBuffer ? 'Paused' : 'Ready') :
      (this.source ? 'Listening' : this.current ? 'Buffering' : 'Waiting'));
    events.onerror = () => {
      if (this.events === events) this.setStatus('Reconnecting');
    };
    return events;
  }

  disconnect(status = 'Unavailable') {
    if (this.events) {
      this.events.close();
      this.events = null;
    }
    this.transportToken++;
    this.paused = true;
    this.queue = [];
    this.avoids.clear();
    this.holdTarget = null;
    this.stopCurrent();
    if (this.audioContext?.state === 'running') this.audioContext.suspend().catch(() => {});
    this.setStatus(status);
    this.render();
  }

  // Recorded-call pages can feed this same queue by providing the same call shape and an audio_url.
  enqueue(call) {
    if (!call?.audio_url || !this.isAllowed(call)) return;

    while (this.queue.length >= this.maximumQueued) {
      this.queue.shift();
      this.dropped++;
    }

    this.queue.push(call);
    if (!this.paused && !this.current) this.playNext();
    else this.render();
  }

  bindControls() {
    this.ui.play.addEventListener('click', () => this.togglePlayback());
    this.ui.skip.addEventListener('click', () => this.skip());
    this.ui.replay.addEventListener('click', () => this.replayCurrent());
    this.ui.hold.addEventListener('click', () => this.toggleHold());
    this.ui.avoid.addEventListener('click', () => this.avoidCurrent());
    this.ui.clear.addEventListener('click', () => {
      this.avoids.clear();
      this.render();
    });
    this.ui.volume.addEventListener('input', () => this.changeVolume());
    this.ui.maximumQueued.addEventListener('change', () => this.changeMaximumQueued());
  }

  async togglePlayback() {
    const token = ++this.transportToken;
    this.paused = !this.paused;

    if (this.paused) {
      if (this.source) {
        this.playbackOffset = this.getPlaybackPosition();
        this.stopSource();
      }
      if (this.audioContext?.state === 'running') await this.audioContext.suspend();
      if (token !== this.transportToken) return;
      this.setStatus(this.currentBuffer ? 'Paused' : 'Ready');
    } else {
      this.ensureAudioContext();
      await this.audioContext.resume();
      if (token !== this.transportToken) return;
      if (this.currentBuffer) this.startCurrent();
      else if (!this.current) this.playNext();
      else this.setStatus('Buffering');
    }

    this.render();
  }

  toggleHold() {
    if (this.holdTarget) {
      this.holdTarget = null;
    } else if (this.current && this.currentBuffer) {
      this.holdTarget = this.targetKey(this.current);
      this.queue = this.queue.filter((call) => this.targetKey(call) === this.holdTarget);
    }

    this.render();
  }

  avoidCurrent() {
    if (!this.current || !this.currentBuffer) return;
    const target = this.targetKey(this.current);
    this.avoids.add(target);
    if (this.holdTarget === target) this.holdTarget = null;
    this.queue = this.queue.filter((call) => this.targetKey(call) !== target);
    this.stopCurrent();
    if (this.paused) this.setStatus('Ready');
    else this.playNext();
  }

  skip() {
    if (this.current) this.stopCurrent();
    else if (this.queue.length) this.queue.shift();
    if (this.paused) {
      this.setStatus('Ready');
      this.render();
    } else {
      this.playNext();
    }
  }

  async replayCurrent() {
    if (!this.current || !this.currentBuffer) return;
    this.playbackOffset = 0;
    this.stopSource();
    if (this.paused) {
      this.setStatus('Paused');
      this.render();
      return;
    }

    this.ensureAudioContext();
    await this.audioContext.resume();
    this.startCurrent();
  }

  async playNext() {
    if (this.paused || this.current) return;

    while (this.queue.length && !this.isAllowed(this.queue[0])) this.queue.shift();
    if (!this.queue.length) {
      this.setStatus('Waiting');
      this.render();
      return;
    }

    this.current = this.queue.shift();
    this.currentBuffer = null;
    this.playbackOffset = 0;
    const token = ++this.loadToken;
    this.setStatus('Buffering');
    this.render();

    try {
      const response = await fetch(this.current.audio_url, { cache: 'no-store', credentials: 'same-origin' });
      if (!response.ok) throw new Error(`Audio returned ${response.status}`);
      this.ensureAudioContext();
      const data = await response.arrayBuffer();
      const buffer = await this.audioContext.decodeAudioData(data);
      if (token !== this.loadToken || !this.current) return;
      this.currentBuffer = buffer;
      if (!this.paused) this.startCurrent();
      else {
        this.setStatus('Paused');
        this.render();
      }
    } catch (_) {
      if (token === this.loadToken) {
        this.stopCurrent();
        this.setStatus('Skipped unavailable call');
        setTimeout(() => {
          if (this.paused) {
            this.setStatus('Ready');
            this.render();
          } else {
            this.playNext();
          }
        }, 150);
      }
    }
  }

  startCurrent() {
    if (!this.current || !this.currentBuffer || this.paused || this.source) return;
    const token = this.loadToken;
    const source = this.audioContext.createBufferSource();
    const duration = Number(this.currentBuffer.duration);
    const maximumOffset = Number.isFinite(duration) && duration > 0 ? Math.max(0, duration - 0.001) : 0;
    const offset = Math.min(maximumOffset, Math.max(0, this.playbackOffset));
    source.buffer = this.currentBuffer;
    source.connect(this.gainNode);
    source.onended = () => {
      if (token !== this.loadToken || source !== this.source) return;
      source.disconnect();
      this.stopProgress();
      this.source = null;
      this.current = null;
      this.currentBuffer = null;
      this.playbackOffset = 0;
      this.render();
      this.playNext();
    };
    this.source = source;
    this.playbackStartedAt = this.audioContext.currentTime - offset;
    source.start(0, offset);
    this.setStatus('Listening');
    this.render();
    this.startProgress();
  }

  stopSource() {
    const source = this.source;
    this.source = null;
    this.stopProgress();
    if (source) {
      source.onended = null;
      try { source.stop(); } catch (_) { }
      source.disconnect();
    }
  }

  stopCurrent() {
    this.loadToken++;
    this.stopSource();
    this.current = null;
    this.currentBuffer = null;
    this.playbackOffset = 0;
    this.render();
  }

  getPlaybackPosition() {
    const duration = Number(this.currentBuffer?.duration);
    const position = this.source && this.audioContext ?
      this.audioContext.currentTime - this.playbackStartedAt : this.playbackOffset;
    if (!Number.isFinite(position)) return 0;
    return Number.isFinite(duration) && duration > 0 ? Math.min(duration, Math.max(0, position)) :
      Math.max(0, position);
  }

  startProgress() {
    this.stopProgress();
    const update = () => {
      if (!this.source) return;
      this.renderProgress();
      this.progressFrame = window.requestAnimationFrame(update);
    };
    update();
  }

  stopProgress() {
    if (this.progressFrame !== null) {
      window.cancelAnimationFrame(this.progressFrame);
      this.progressFrame = null;
    }
  }

  renderProgress() {
    const duration = Number(this.currentBuffer?.duration);
    const hasDuration = Boolean(this.current) && Number.isFinite(duration) && duration > 0;
    const position = hasDuration ? this.getPlaybackPosition() : 0;
    const progress = hasDuration ? Math.min(1, position / duration) : 0;
    const fadeWindow = hasDuration ? Math.min(1.2, Math.max(0.35, duration * 0.12)) : 0;
    const active = hasDuration && (Boolean(this.source) || (this.paused && this.playbackOffset > 0));
    this.ui.progress.style.setProperty('--playback-progress', String(progress));
    this.ui.progress.classList.toggle('active', active);
    this.ui.progress.classList.toggle('paused', active && this.paused);
    this.ui.progress.classList.toggle('ending', Boolean(this.source) && duration - position <= fadeWindow);
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

  changeVolume() {
    const requested = Number(this.ui.volume.value);
    this.volume = Number.isFinite(requested) ? Math.max(0, Math.min(1, requested)) : 1;
    this.ui.volume.value = String(this.volume);
    if (this.gainNode) this.gainNode.gain.value = this.volume;
    try {
      localStorage.setItem(WebCallPlayer.VOLUME_KEY, String(this.volume));
    } catch (_) { }
    this.renderVolume();
  }

  readVolume() {
    try {
      const stored = localStorage.getItem(WebCallPlayer.VOLUME_KEY);
      if (stored === null || stored.trim() === '') return 1;
      const saved = Number(stored);
      if (Number.isFinite(saved) && saved >= 0 && saved <= 1) return saved;
    } catch (_) { }
    return 1;
  }

  readMaximumQueued() {
    const saved = Math.trunc(Number(localStorage.getItem(WebCallPlayer.MAXIMUM_QUEUE_KEY)));
    return Number.isFinite(saved) && saved >= 1 && saved <= 500 ? saved : 100;
  }

  ensureAudioContext() {
    if (!this.audioContext) {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      this.audioContext = new AudioContext();
      this.gainNode = this.audioContext.createGain();
      this.gainNode.gain.value = this.volume;
      this.gainNode.connect(this.audioContext.destination);
    }
  }

  renderVolume() {
    this.ui.volumeValue.value = `${Math.round(this.volume * 100)}%`;
    this.ui.volume.setAttribute('aria-valuetext', `${Math.round(this.volume * 100)} percent`);
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
    const targetId = call.target_id === null || call.target_id === undefined || call.target_id === '' ? '' :
      String(call.target_id);
    const sourceId = call.source_id === null || call.source_id === undefined || call.source_id === '' ? '' :
      String(call.source_id);
    const targetType = this.identifierType(call.target_form, 'TGID');
    const sourceType = this.identifierType(call.source_form, 'Radio');
    const target = call.target_alias ?
      `${call.target_alias}${targetId ? ` · ${targetType} ${targetId}` : ''}` :
      (targetId ? `${targetType} ${targetId}` : call.channel || 'Unknown target');
    const source = call.source_alias ?
      `${call.source_alias}${sourceId ? ` · ${sourceType} ${sourceId}` : ''}` :
      (sourceId ? `${sourceType} ${sourceId}` : '');
    return `${target}${source ? ` ← ${source}` : ''}`;
  }

  identifierType(form, fallback) {
    const normalized = String(form || '').toUpperCase();
    const label = {
      PATCH_GROUP: 'Patch',
      RADIO: 'Radio',
      TALKGROUP: 'TGID'
    }[normalized];
    return label || (normalized ? 'ID' : fallback);
  }

  callDetails(call) {
    const details = [];
    if (call.system) details.push(String(call.system));
    if (call.channel) details.push(String(call.channel));
    const frequency = Number(call.frequency_hz);
    if (Number.isFinite(frequency) && frequency > 0) details.push(`${(frequency / 1000000).toFixed(5)} MHz`);
    const timeslot = Number(call.timeslot);
    if (call.timeslot !== null && call.timeslot !== undefined && call.timeslot !== '' &&
        Number.isFinite(timeslot) && timeslot >= 0) {
      details.push(`Slot ${timeslot}`);
    }
    if (call.decoder) details.push(String(call.decoder));
    if (call.encrypted) details.push('Encrypted');
    const duration = Number(call.duration_ms);
    if (Number.isFinite(duration) && duration > 0) details.push(`${(duration / 1000).toFixed(1)} sec`);
    return details.join(' · ');
  }

  setStatus(value) {
    this.ui.status.textContent = value;
  }

  render() {
    this.renderVolume();
    const currentReady = Boolean(this.current && this.currentBuffer);
    this.ui.current.replaceChildren();
    if (currentReady) {
      const label = this.callLabel(this.current);
      const primary = document.createElement('strong');
      primary.className = 'playback-current-primary';
      primary.textContent = label;
      this.ui.current.append(primary);
      const details = this.callDetails(this.current);
      if (details) {
        const secondary = document.createElement('span');
        secondary.className = 'playback-current-secondary';
        secondary.textContent = details;
        this.ui.current.append(secondary);
      }
      this.ui.current.title = details ? `${label}\n${details}` : label;
    } else {
      const idle = document.createElement('strong');
      idle.className = 'playback-current-primary';
      idle.textContent = 'Idle';
      this.ui.current.append(idle);
      this.ui.current.removeAttribute('title');
    }
    this.ui.queued.textContent = String(this.queue.length);
    this.ui.dropped.textContent = this.dropped ? ` · Dropped ${this.dropped}` : '';
    this.ui.queueList.replaceChildren();
    this.queue.slice(0, 100).forEach((call, index) => {
      const item = document.createElement('div');
      item.className = 'playback-queue-item';
      item.textContent = `${index + 1}. ${this.callLabel(call)}`;
      const details = this.callDetails(call);
      if (details) item.title = details;
      this.ui.queueList.append(item);
    });
    if (!this.queue.length) {
      const empty = document.createElement('div');
      empty.className = 'muted';
      empty.textContent = 'No queued calls';
      this.ui.queueList.append(empty);
    }

    this.ui.play.textContent = this.paused ? 'Play' : 'Pause';
    this.ui.play.classList.toggle('active', !this.paused);
    this.ui.play.setAttribute('aria-pressed', String(!this.paused));
    this.ui.play.title = this.paused ? 'Play browser call audio' : 'Pause browser call audio';
    this.ui.replay.disabled = !currentReady;
    this.ui.hold.classList.toggle('active', Boolean(this.holdTarget));
    this.ui.hold.disabled = !this.holdTarget && !currentReady;
    this.ui.hold.title = this.holdTarget ? 'Release browser hold' : 'Hold the current target in this browser';
    this.ui.avoid.disabled = !currentReady;
    this.ui.clear.disabled = !this.avoids.size;
    this.ui.clear.title = `Clear ${this.avoids.size} browser avoid(s)`;
    this.ui.skip.disabled = !this.current && !this.queue.length;
    this.renderProgress();
  }
}

window.WebCallPlayer = WebCallPlayer;
