class WebCallPlayer {
  static VOLUME_KEY = 'sdrtrunk-vce.web-player.volume';
  static SELECTED_SCAN_LISTS_KEY = 'sdrtrunk-vce.web-player.selected-scan-lists';
  static MAXIMUM_SEEN_CALL_IDS = 2048;
  static MAXIMUM_SCAN_LISTS = 128;
  static MAXIMUM_CONSECUTIVE_CONVERSATION_CALLS = 4;

  constructor(ids) {
    this.ui = Object.fromEntries(Object.entries(ids).map(([key, id]) => [key, document.getElementById(id)]));
    this.conversationLanes = new Map();
    this.queuedCount = 0;
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
    this.connectionTopic = null;
    this.connectionFactory = null;
    this.loadToken = 0;
    this.loadController = null;
    this.missedCalls = 0;
    this.missedCountExact = true;
    this.paused = true;
    this.volume = this.readVolume();
    this.maximumQueued = 100;
    this.maximumSelectedScanLists = WebCallPlayer.MAXIMUM_SCAN_LISTS;
    this.arrivalSequence = 0;
    this.lastConversationKey = null;
    this.consecutiveConversationCalls = 0;
    this.seenCallIds = new Set();
    this.seenCallOrder = [];
    this.scanLists = [];
    this.scanListById = new Map();
    this.selectedScanListIds = new Set();
    this.scanListCatalogReady = false;
    this.scanListCatalogState = 'loading';
    this.listenerToken = null;
    this.capacity = null;
    this.ui.volume.value = String(this.volume);
    this.bindControls();
    this.render();
  }

  connect(url, connectionFactory) {
    if (this.events) this.events.close();
    this.events = null;
    if (typeof connectionFactory !== 'function') throw new Error('A shared live connection is required.');
    this.connectionTopic = url;
    this.connectionFactory = connectionFactory;
    return this.connectionHandle();
  }

  connectionHandle() {
    return {
      close: () => {
        this.closeEvents();
        this.connectionTopic = null;
        this.connectionFactory = null;
      }
    };
  }

  subscriptionParameters() {
    return { scan_list_id: [...this.selectedScanListIds].sort((first, second) => Number(first) - Number(second)) };
  }

  ensureConnected() {
    if (this.events) return this.events;
    if (typeof this.connectionFactory !== 'function' || !this.scanListCatalogReady ||
        !this.selectedScanListIds.size) return null;
    const events = this.connectionFactory(this.connectionTopic, this.subscriptionParameters());
    this.events = events;
    events.addEventListener('ready', (event) => this.handleReady(this.eventPayload(event)));
    events.addEventListener('call', (event) => this.enqueue(this.eventPayload(event)));
    events.addEventListener('snapshot', (event) => this.consumeSnapshot(this.eventPayload(event)));
    events.addEventListener('overrun', (event) => this.handleMissedEvent(this.eventPayload(event)));
    events.addEventListener('missed', (event) => this.handleMissedEvent(this.eventPayload(event)));
    events.onopen = () => this.setStatus(this.paused ? (this.currentBuffer ? 'Paused' : 'Ready') :
      (this.source ? 'Listening' : this.current ? 'Buffering' : 'Waiting'));
    events.onerror = () => {
      if (this.events === events) this.setStatus('Reconnecting');
    };
    return events;
  }

  closeEvents() {
    if (this.events) {
      this.events.close();
      this.events = null;
    }
    this.listenerToken = null;
  }

  synchronizeSubscription() {
    if (!this.scanListCatalogReady || !this.selectedScanListIds.size) {
      this.closeEvents();
      this.setStatus(this.scanListCatalogReady ? 'Select a scan list' : 'Loading scan lists');
      return;
    }
    if (this.events) this.events.update(this.subscriptionParameters());
    else if (!this.paused) this.ensureConnected();
  }

  eventPayload(event) {
    try {
      const parsed = JSON.parse(event?.data || '{}');
      return parsed?.data && typeof parsed.data === 'object' ? parsed.data : parsed;
    } catch (_) {
      return {};
    }
  }

  handleReady(payload) {
    if (typeof payload?.listener_token === 'string') this.listenerToken = payload.listener_token;
    this.applyLimits(payload);
    this.renderScanLists();
    this.render();
  }

  handleMissedEvent(payload) {
    const exact = payload?.exact !== false;
    const reported = Math.trunc(Number(payload?.missed_calls ?? payload?.missed_count ?? payload?.count));
    this.recordMissed(Number.isFinite(reported) && reported > 0 ? reported : (exact ? 1 : 0), exact);
    this.setStatus('Calls missed');
  }

  disconnect(status = 'Unavailable') {
    this.closeEvents();
    this.transportToken++;
    this.loadController?.abort();
    this.loadController = null;
    this.paused = true;
    this.clearQueuedCalls('disconnected');
    this.avoids.clear();
    this.holdTarget = null;
    this.stopCurrent();
    if (this.audioContext?.state === 'running') this.audioContext.suspend().catch(() => {});
    this.setStatus(status);
    this.render();
  }

  setScanListsLoading() {
    this.scanListCatalogReady = false;
    this.scanListCatalogState = 'loading';
    this.closeEvents();
    if (this.ui.scanListStatus) this.ui.scanListStatus.textContent = 'Loading available scan lists…';
    this.renderScanLists();
  }

  setScanListsUnavailable(message = 'Scan lists are unavailable') {
    this.scanListCatalogReady = false;
    this.scanListCatalogState = 'unavailable';
    this.scanLists = [];
    this.scanListById.clear();
    this.closeEvents();
    if (this.ui.scanListStatus) this.ui.scanListStatus.textContent = message;
    this.setStatus(message);
    this.renderScanLists();
  }

  setScanLists(rows, limits = {}) {
    this.applyLimits(limits);
    const unique = new Map();
    (Array.isArray(rows) ? rows : []).slice(0, WebCallPlayer.MAXIMUM_SCAN_LISTS).forEach((row) => {
      const idValue = row?.scan_list_id ?? row?.id;
      const id = idValue === null || idValue === undefined ? '' : String(idValue).trim();
      if (!id || unique.has(id)) return;
      unique.set(id, {
        id,
        name: String(row?.name || `Scan list ${id}`).trim() || `Scan list ${id}`,
        description: String(row?.description || '').trim(),
        enabled: row?.enabled !== false && row?.available !== false,
        defaultSelected: row?.default === true || row?.default_selected === true
      });
    });
    // The server already applies the administrator-owned scan-list order.
    this.scanLists = [...unique.values()];
    this.scanListById = new Map(this.scanLists.map((item) => [item.id, item]));
    const stored = this.readSelectedScanLists();
    const existing = this.selectedScanListIds.size ? [...this.selectedScanListIds] : stored.ids;
    let selected = existing.filter((id) => this.scanListById.get(id)?.enabled);
    if (!selected.length && !stored.present && !this.selectedScanListIds.size) {
      selected = this.scanLists.filter((item) => item.enabled && item.defaultSelected).map((item) => item.id);
      if (!selected.length) {
        const first = this.scanLists.find((item) => item.enabled);
        if (first) selected = [first.id];
      }
    }
    this.selectedScanListIds = new Set(selected.slice(0, this.maximumSelectedScanLists));
    this.scanListCatalogReady = true;
    this.scanListCatalogState = 'ready';
    this.persistSelectedScanLists();
    this.updateScanListStatus();
    this.filterQueueForSelectedLists();
    this.renderScanLists();
    this.synchronizeSubscription();
    this.render();
  }

  scanListsReady() {
    return this.scanListCatalogReady;
  }

  applyLimits(value = {}) {
    const selected = Math.trunc(Number(value?.maximum_selected_scan_lists ?? value?.maximumSelectedScanLists));
    if (Number.isFinite(selected) && selected >= 1) {
      this.maximumSelectedScanLists = Math.min(WebCallPlayer.MAXIMUM_SCAN_LISTS, selected);
      this.selectedScanListIds = new Set([...this.selectedScanListIds].slice(0, this.maximumSelectedScanLists));
    }
    const queued = Math.trunc(Number(value?.waiting_calls_per_listener));
    if (Number.isFinite(queued) && queued >= 1) {
      this.maximumQueued = Math.min(500, queued);
      this.trimQueueToLimit();
    }
  }

  readSelectedScanLists() {
    try {
      const stored = localStorage.getItem(WebCallPlayer.SELECTED_SCAN_LISTS_KEY);
      if (stored === null) return { present: false, ids: [] };
      const parsed = JSON.parse(stored);
      return { present: true, ids: Array.isArray(parsed) ? parsed.map(String) : [] };
    } catch (_) {
      return { present: false, ids: [] };
    }
  }

  persistSelectedScanLists() {
    try {
      localStorage.setItem(WebCallPlayer.SELECTED_SCAN_LISTS_KEY,
        JSON.stringify([...this.selectedScanListIds].sort()));
    } catch (_) { }
  }

  setScanListSelected(id, selected) {
    const item = this.scanListById.get(String(id));
    if (!item?.enabled) return;
    const updated = new Set(this.selectedScanListIds);
    if (selected && !updated.has(item.id) && updated.size >= this.maximumSelectedScanLists) {
      if (this.ui.scanListStatus) {
        this.ui.scanListStatus.textContent = `Choose up to ${this.maximumSelectedScanLists} scan lists.`;
      }
      this.renderScanLists();
      return;
    }
    if (selected) updated.add(item.id);
    else updated.delete(item.id);
    if ([...updated].sort().join('|') === [...this.selectedScanListIds].sort().join('|')) return;
    this.selectedScanListIds = updated;
    this.persistSelectedScanLists();
    this.updateScanListStatus();
    this.filterQueueForSelectedLists();
    this.renderScanLists();
    this.synchronizeSubscription();
    this.render();
    if (selected && this.paused) void this.togglePlayback();
  }

  renderScanLists() {
    if (this.ui.scanListSummary) {
      if (!this.scanListCatalogReady) {
        this.ui.scanListSummary.textContent = this.scanListCatalogState === 'loading' ? 'Loading' : 'Unavailable';
        this.ui.scanListSummary.removeAttribute('title');
      } else {
        const selected = this.scanLists.filter((item) => this.selectedScanListIds.has(item.id));
        this.ui.scanListSummary.textContent = !selected.length ? 'None' :
          (selected.length === 1 ? selected[0].name : `${selected.length} lists`);
        this.ui.scanListSummary.title = selected.map((item) => item.name).join(', ');
      }
    }
    if (!this.ui.scanListOptions) return;
    this.ui.scanListOptions.replaceChildren();
    if (!this.scanListCatalogReady) {
      this.ui.scanListOptions.append(this.scanListMessage(this.scanListCatalogState === 'loading' ?
        'Waiting for the receiver…' : 'Scan lists are unavailable.'));
      return;
    }
    if (!this.scanLists.length) {
      this.ui.scanListOptions.append(this.scanListMessage('No scan lists are available.'));
      return;
    }
    const selectionFull = this.selectedScanListIds.size >= this.maximumSelectedScanLists;
    this.scanLists.forEach((item) => {
      const label = document.createElement('label');
      label.className = 'playback-scan-list-option';
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.value = item.id;
      checkbox.checked = this.selectedScanListIds.has(item.id);
      checkbox.disabled = !item.enabled || (selectionFull && !checkbox.checked);
      checkbox.addEventListener('change', () => this.setScanListSelected(item.id, checkbox.checked));
      const copy = document.createElement('span');
      const name = document.createElement('strong');
      name.textContent = item.name;
      copy.append(name);
      if (item.description) {
        const description = document.createElement('small');
        description.textContent = item.description;
        copy.append(description);
      }
      if (!item.enabled) {
        const unavailable = document.createElement('small');
        unavailable.textContent = 'Unavailable';
        copy.append(unavailable);
      }
      label.append(checkbox, copy);
      this.ui.scanListOptions.append(label);
    });
  }

  updateScanListStatus() {
    if (!this.ui.scanListStatus || !this.scanListCatalogReady) return;
    if (!this.scanLists.length) this.ui.scanListStatus.textContent = 'No scan lists are available.';
    else if (!this.selectedScanListIds.size) {
      this.ui.scanListStatus.textContent = 'Select at least one scan list to receive calls.';
    } else {
      this.ui.scanListStatus.textContent =
        `Matching calls are delivered once. Choose up to ${this.maximumSelectedScanLists} scan lists.`;
    }
  }

  scanListMessage(message) {
    const value = document.createElement('div');
    value.className = 'muted';
    value.textContent = message;
    return value;
  }

  updateCapacity(capacity) {
    this.capacity = capacity && typeof capacity === 'object' ? capacity : null;
    if (!this.ui.capacity) return;
    const value = this.capacity || {};
    const listeners = this.firstFinite(value.active_listeners, value.listeners, value.subscribers);
    const maximumListeners = this.firstFinite(value.maximum_listeners, value.maximum_clients);
    const artifacts = this.firstFinite(value.artifact_count, value.cached_calls);
    const maximumArtifacts = this.firstFinite(value.maximum_artifacts, value.maximum_calls);
    const audioBytes = this.firstFinite(value.artifact_bytes, value.cached_audio_bytes);
    const maximumAudioBytes = this.firstFinite(value.maximum_artifact_bytes, value.maximum_audio_bytes);
    const parts = [];
    if (listeners !== null || maximumListeners !== null) {
      parts.push(`${listeners ?? 0}${maximumListeners !== null ? `/${maximumListeners}` : ''} listeners`);
    }
    if (artifacts !== null || maximumArtifacts !== null) {
      parts.push(`${artifacts ?? 0}${maximumArtifacts !== null ? `/${maximumArtifacts}` : ''} calls cached`);
    }
    if (audioBytes !== null || maximumAudioBytes !== null) {
      parts.push(`${this.byteLabel(audioBytes ?? 0)}${maximumAudioBytes !== null ?
        `/${this.byteLabel(maximumAudioBytes)}` : ''} audio`);
    }
    this.ui.capacity.textContent = parts.join(' · ') || 'Listener capacity is unavailable.';
  }

  firstFinite(...values) {
    for (const value of values) {
      const candidate = Number(value);
      if (Number.isFinite(candidate) && candidate >= 0) return candidate;
    }
    return null;
  }

  byteLabel(value) {
    const bytes = Math.max(0, Number(value) || 0);
    if (bytes >= 1073741824) return `${(bytes / 1073741824).toFixed(1)} GB`;
    if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(bytes >= 10485760 ? 0 : 1)} MB`;
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`;
    return `${Math.round(bytes)} B`;
  }

  // Recorded-call pages can feed this same queue by providing the same call shape and an audio_url.
  enqueue(call) {
    const normalized = this.normalizeCall(call);
    if (!normalized || !this.callMatchesSelection(normalized)) return;
    if (this.seenCallIds.has(normalized._logicalCallId)) return;
    this.rememberCallId(normalized._logicalCallId);
    if (!this.isAllowed(normalized)) {
      this.acknowledge(normalized, 'filtered');
      return;
    }

    while (this.queuedCount >= this.maximumQueued) {
      const dropped = this.dropOldestQueued();
      if (!dropped) break;
      this.recordMissed(1, true, false);
      this.acknowledge(dropped, 'queue_overflow');
    }

    this.insertQueuedCall(normalized);
    if (!this.paused && !this.current) this.playNext();
    else this.render();
  }

  consumeSnapshot(snapshot) {
    if (typeof snapshot?.listener_token === 'string') this.listenerToken = snapshot.listener_token;
    this.applyLimits(snapshot);
    (Array.isArray(snapshot?.calls) ? snapshot.calls : []).forEach((call) => this.enqueue(call));
  }

  normalizeCall(value) {
    if (!value || typeof value !== 'object' || !value.audio_url) return null;
    const idValue = value.logical_call_id ?? value.call_id;
    const logicalCallId = idValue === null || idValue === undefined ? '' : String(idValue).trim();
    if (!logicalCallId) return null;
    const started = Number(value.started_at_ms ?? value.start_timestamp_ms ?? value.completed_at_ms);
    const matchedValue = value.matched_scan_list_ids ?? value.scan_list_ids ?? value.selected_scan_list_ids;
    const matched = Array.isArray(matchedValue) ? matchedValue.map(String) : [...this.selectedScanListIds];
    const call = {
      ...value,
      _logicalCallId: logicalCallId,
      _startedAtMs: Number.isFinite(started) && started > 0 ? started : Date.now(),
      _arrivalSequence: this.arrivalSequence++,
      _matchedScanListIds: [...new Set(matched)]
    };
    call._conversationKey = String(value.conversation_key || this.targetKey(call));
    return call;
  }

  rememberCallId(id) {
    this.seenCallIds.add(id);
    this.seenCallOrder.push(id);
    while (this.seenCallOrder.length > WebCallPlayer.MAXIMUM_SEEN_CALL_IDS) {
      this.seenCallIds.delete(this.seenCallOrder.shift());
    }
  }

  insertQueuedCall(call) {
    const lane = this.conversationLanes.get(call._conversationKey) || [];
    let index = lane.length;
    while (index > 0 && this.compareCalls(call, lane[index - 1]) < 0) index--;
    lane.splice(index, 0, call);
    this.conversationLanes.set(call._conversationKey, lane);
    this.queuedCount++;
  }

  compareCalls(first, second) {
    return first._startedAtMs - second._startedAtMs ||
      first._arrivalSequence - second._arrivalSequence ||
      first._logicalCallId.localeCompare(second._logicalCallId);
  }

  oldestLaneKey(lanes, excludedKey = null) {
    let selectedKey = null;
    let selected = null;
    lanes.forEach((lane, key) => {
      if (!lane.length || key === excludedKey) return;
      if (!selected || this.compareCalls(lane[0], selected) < 0) {
        selectedKey = key;
        selected = lane[0];
      }
    });
    return selectedKey;
  }

  chooseNextLane(lanes, lastKey, consecutive) {
    const same = lastKey ? lanes.get(lastKey) : null;
    const otherKey = this.oldestLaneKey(lanes, lastKey);
    if (same?.length &&
        (consecutive < WebCallPlayer.MAXIMUM_CONSECUTIVE_CONVERSATION_CALLS || !otherKey)) return lastKey;
    return otherKey || this.oldestLaneKey(lanes);
  }

  takeNextCall() {
    const key = this.chooseNextLane(this.conversationLanes, this.lastConversationKey,
      this.consecutiveConversationCalls);
    if (!key) return null;
    const lane = this.conversationLanes.get(key);
    const call = lane.shift();
    if (!lane.length) this.conversationLanes.delete(key);
    this.queuedCount--;
    if (key === this.lastConversationKey) {
      this.consecutiveConversationCalls = Math.min(WebCallPlayer.MAXIMUM_CONSECUTIVE_CONVERSATION_CALLS,
        this.consecutiveConversationCalls + 1);
    } else {
      this.lastConversationKey = key;
      this.consecutiveConversationCalls = 1;
    }
    return call;
  }

  scheduledQueue(limit = 100) {
    const lanes = new Map([...this.conversationLanes].map(([key, lane]) => [key, lane.slice()]));
    const result = [];
    let lastKey = this.lastConversationKey;
    let consecutive = this.consecutiveConversationCalls;
    while (result.length < limit) {
      const key = this.chooseNextLane(lanes, lastKey, consecutive);
      if (!key) break;
      const lane = lanes.get(key);
      result.push(lane.shift());
      if (!lane.length) lanes.delete(key);
      if (key === lastKey) consecutive = Math.min(WebCallPlayer.MAXIMUM_CONSECUTIVE_CONVERSATION_CALLS,
        consecutive + 1);
      else {
        lastKey = key;
        consecutive = 1;
      }
    }
    return result;
  }

  dropOldestQueued() {
    const key = this.oldestLaneKey(this.conversationLanes);
    if (!key) return null;
    const lane = this.conversationLanes.get(key);
    const call = lane.shift();
    if (!lane.length) this.conversationLanes.delete(key);
    this.queuedCount--;
    return call;
  }

  clearQueuedCalls(outcome) {
    this.conversationLanes.forEach((lane) => lane.forEach((call) => this.acknowledge(call, outcome)));
    this.conversationLanes.clear();
    this.queuedCount = 0;
  }

  filterQueuedCalls(predicate, outcome) {
    this.conversationLanes.forEach((lane, key) => {
      const retained = [];
      lane.forEach((call) => {
        if (predicate(call)) retained.push(call);
        else {
          this.queuedCount--;
          this.acknowledge(call, outcome);
        }
      });
      if (retained.length) this.conversationLanes.set(key, retained);
      else this.conversationLanes.delete(key);
    });
  }

  filterQueueForSelectedLists() {
    this.filterQueuedCalls((call) => this.callMatchesSelection(call), 'subscription_changed');
  }

  callMatchesSelection(call) {
    if (!this.selectedScanListIds.size) return false;
    return !call?._matchedScanListIds?.length ||
      call._matchedScanListIds.some((id) => this.selectedScanListIds.has(String(id)));
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
    const panels = [this.ui.scanListOptions?.closest('details'), this.ui.queueList?.closest('details')]
      .filter(Boolean);
    panels.forEach((panel) => panel.addEventListener('toggle', () => {
      if (panel.open) panels.forEach((other) => { if (other !== panel) other.open = false; });
    }));
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
      if (!this.ensureConnected()) {
        this.paused = true;
        this.setStatus('Unavailable');
        this.render();
        return;
      }
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
      this.holdTarget = this.current._conversationKey;
      this.filterQueuedCalls((call) => call._conversationKey === this.holdTarget, 'hold_filtered');
    }

    this.render();
  }

  avoidCurrent() {
    if (!this.current || !this.currentBuffer) return;
    const target = this.current._conversationKey;
    this.avoids.add(target);
    if (this.holdTarget === target) this.holdTarget = null;
    this.filterQueuedCalls((call) => call._conversationKey !== target, 'avoided');
    this.stopCurrent('avoided');
    if (this.paused) this.setStatus('Ready');
    else this.playNext();
  }

  skip() {
    if (this.current) this.stopCurrent('skipped');
    else {
      const skipped = this.takeNextCall();
      if (skipped) this.acknowledge(skipped, 'skipped');
    }
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

    let next = this.takeNextCall();
    while (next && (!this.callMatchesSelection(next) || !this.isAllowed(next))) {
      this.acknowledge(next, 'filtered');
      next = this.takeNextCall();
    }
    if (!next) {
      this.setStatus('Waiting');
      this.render();
      return;
    }

    this.current = next;
    this.currentBuffer = null;
    this.playbackOffset = 0;
    const token = ++this.loadToken;
    this.loadController?.abort();
    const loadController = new AbortController();
    this.loadController = loadController;
    let loadTimedOut = false;
    let loadTimeout;
    const timeoutFailure = new Promise((_, reject) => {
      loadTimeout = window.setTimeout(() => {
        loadTimedOut = true;
        loadController.abort();
        reject(new Error('Audio loading timed out.'));
      }, 15_000);
    });
    this.setStatus('Buffering');
    this.render();

    try {
      const buffer = await Promise.race([(async () => {
        const headers = {};
        if (this.listenerToken) headers['X-SDRTrunk-Listener-Token'] = this.listenerToken;
        const response = await fetch(this.current.audio_url, {
          cache: 'no-store', credentials: 'same-origin', headers, signal: loadController.signal
        });
        if (!response.ok) throw new Error(`Audio returned ${response.status}`);
        this.ensureAudioContext();
        const data = await response.arrayBuffer();
        return this.audioContext.decodeAudioData(data);
      })(), timeoutFailure]);
      if (token !== this.loadToken || !this.current) return;
      this.currentBuffer = buffer;
      if (!this.paused) this.startCurrent();
      else {
        this.setStatus('Paused');
        this.render();
      }
    } catch (error) {
      if (error?.name === 'AbortError' && !loadTimedOut) return;
      if (token === this.loadToken) {
        this.recordMissed(1, true, false);
        this.stopCurrent('unavailable');
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
    } finally {
      window.clearTimeout(loadTimeout);
      if (this.loadController === loadController) this.loadController = null;
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
      const completed = this.current;
      source.disconnect();
      this.stopProgress();
      this.source = null;
      this.current = null;
      this.currentBuffer = null;
      this.playbackOffset = 0;
      this.acknowledge(completed, 'completed');
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

  stopCurrent(outcome = null) {
    const stopped = this.current;
    this.loadToken++;
    this.loadController?.abort();
    this.loadController = null;
    this.stopSource();
    this.current = null;
    this.currentBuffer = null;
    this.playbackOffset = 0;
    if (outcome && stopped) this.acknowledge(stopped, outcome);
    this.render();
  }

  acknowledge(call, outcome) {
    const url = call?.control_url || call?.ack_url;
    if (!url) return;
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
    if (this.listenerToken) headers['X-SDRTrunk-Listener-Token'] = this.listenerToken;
    fetch(url, {
      method: 'POST', cache: 'no-store', credentials: 'same-origin', headers,
      body: JSON.stringify({ logical_call_id: call._logicalCallId || call.logical_call_id || call.call_id, outcome })
    }).catch(() => {});
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

  trimQueueToLimit() {
    while (this.queuedCount > this.maximumQueued) {
      const dropped = this.dropOldestQueued();
      if (!dropped) break;
      this.recordMissed(1, true, false);
      this.acknowledge(dropped, 'queue_overflow');
    }
  }

  recordMissed(count, exact = true, render = true) {
    if (Number.isFinite(Number(count)) && Number(count) > 0) this.missedCalls += Math.trunc(Number(count));
    if (!exact) this.missedCountExact = false;
    if (render) this.render();
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
    const target = call?._conversationKey || this.targetKey(call);
    return !this.avoids.has(target) && (!this.holdTarget || this.holdTarget === target);
  }

  targetKey(call) {
    const system = call?.system || '';
    const protocol = call?.protocol || call?.decoder || '';
    const timeslot = call?.timeslot === null || call?.timeslot === undefined ? '' : `|slot:${call.timeslot}`;
    if (call?.target_id !== null && call?.target_id !== undefined && call?.target_id !== '') {
      return `${protocol}|${system}|target:${call.target_id}${timeslot}`;
    }
    if (call?.channel) return `${protocol}|${system}|channel:${call.channel}${timeslot}`;
    return `${protocol}|${system}|frequency:${call?.frequency_hz || 0}${timeslot}`;
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
    const names = (call._matchedScanListIds || []).map((id) => this.scanListById.get(String(id))?.name)
      .filter(Boolean);
    if (names.length) details.push(names.join(', '));
    return details.join(' · ');
  }

  setStatus(value) {
    this.ui.status.textContent = value;
  }

  render() {
    this.renderVolume();
    const currentReady = Boolean(this.current && this.currentBuffer);
    this.ui.current.replaceChildren();
    if (this.current) {
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
    this.ui.queued.textContent = String(this.queuedCount);
    if (this.ui.missed) {
      const count = this.missedCountExact ? String(this.missedCalls) :
        (this.missedCalls > 0 ? `${this.missedCalls}+` : 'calls');
      this.ui.missed.textContent = `Not played ${count}`;
      this.ui.missed.classList.toggle('active', this.missedCalls > 0 || !this.missedCountExact);
      this.ui.missed.hidden = this.missedCalls <= 0 && this.missedCountExact;
    }
    this.ui.queueList.replaceChildren();
    this.scheduledQueue(100).forEach((call, index) => {
      const item = document.createElement('div');
      item.className = 'playback-queue-item';
      const order = document.createElement('span');
      order.className = 'playback-queue-order';
      order.textContent = `${index + 1}.`;
      const copy = document.createElement('span');
      copy.textContent = this.callLabel(call);
      const details = this.callDetails(call);
      if (details) copy.title = details;
      item.append(order, copy);
      this.ui.queueList.append(item);
    });
    if (!this.queuedCount) {
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
    this.ui.skip.disabled = !this.current && !this.queuedCount;
    this.renderProgress();
  }
}

window.WebCallPlayer = WebCallPlayer;
