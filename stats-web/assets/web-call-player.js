export class WebCallPlayer {
  static MAXIMUM_SEEN_CALL_IDS = 2048;
  static IDLE_CALL_DISPLAY_MS = 5000;
  static MAXIMUM_SCAN_LISTS = 128;
  static MAXIMUM_SELECTED_SCAN_LISTS = 16;
  static MAXIMUM_QUEUED_CALLS = 100;
  static FEED_POLL_INTERVAL_MS = 100;
  static FEED_RETRY_INTERVAL_MS = 2000;
  static MAXIMUM_AVOIDS = 256;

  constructor(ids) {
    this.ui = Object.fromEntries(Object.entries(ids).map(([key, id]) => [key, document.getElementById(id)]));
    this.queuedCalls = [];
    this.queuedCount = 0;
    this.avoids = new Map();
    this.lastHeard = null;
    this.lastHeardBuffer = null;
    this.replayingLast = false;
    this.stopAfterReplay = false;
    this.stateObservers = new Set();
    this.actions = {};
    this.holdTarget = null;
    this.current = null;
    this.currentBuffer = null;
    this.idleDisplayCall = null;
    this.idleDisplayTimer = null;
    this.idleDisplayDeadline = 0;
    this.source = null;
    this.analyserNode = null;
    this.waveformSamples = null;
    this.gainNode = null;
    this.audioContext = null;
    this.playbackStartedAt = 0;
    this.progressFrame = null;
    this.transportToken = 0;
    this.feedUrl = null;
    this.feedFetch = null;
    this.feedActive = false;
    this.feedController = null;
    this.feedTimer = null;
    this.feedGeneration = 0;
    this.feedCursor = null;
    this.loadToken = 0;
    this.loadController = null;
    this.paused = true;
    this.volume = 1;
    this.statusValue = this.ui.status?.textContent || '';
    this.skippedNotice = false;
    this.preferenceWriter = null;
    this.maximumQueued = WebCallPlayer.MAXIMUM_QUEUED_CALLS;
    this.maximumSelectedScanLists = WebCallPlayer.MAXIMUM_SELECTED_SCAN_LISTS;
    this.conversationGrouping = true;
    this.conversationBurstLimit = 4;
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
    this.ui.volume.value = String(this.volume);
    this.bindControls();
    this.render();
  }

  connect(url, feedFetch = null) {
    this.stopFeed();
    this.feedUrl = String(url || '').trim();
    this.feedFetch = typeof feedFetch === 'function' ? feedFetch :
      ((path, options) => fetch(path, { cache: 'no-store', credentials: 'same-origin', ...options })
        .then(async (response) => {
          if (!response.ok) throw Object.assign(new Error(`Call feed returned ${response.status}`),
            { status: response.status });
          return response.json();
        }));
    if (!this.feedUrl) throw new Error('A browser call feed URL is required.');
    return this.connectionHandle();
  }

  setActions(actions = {}) {
    this.actions = actions && typeof actions === 'object' ? actions : {};
  }

  setPreferenceWriter(writer) {
    this.preferenceWriter = typeof writer === 'function' ? writer : null;
  }

  applyPreferences(preferences) {
    if (!preferences || typeof preferences !== 'object') return;
    const volume = Number(preferences.volume);
    if (Number.isFinite(volume) && volume >= 0 && volume <= 1) {
      this.volume = volume;
      this.ui.volume.value = String(volume);
      if (this.gainNode) this.gainNode.gain.value = volume;
    }
    if (Array.isArray(preferences.selected_scan_list_ids)) {
      const selected = new Set(preferences.selected_scan_list_ids.map(String));
      const changed = selected.size !== this.selectedScanListIds.size ||
        [...selected].some((id) => !this.selectedScanListIds.has(id));
      if (changed) {
        this.selectedScanListIds = selected;
        this.filterQueueForSelectedLists();
        this.renderScanLists();
        this.synchronizeSubscription();
      }
    }
    if (typeof preferences.conversation_grouping === 'boolean') {
      this.conversationGrouping = preferences.conversation_grouping;
    }
    const burstLimit = Number(preferences.conversation_burst_limit);
    if (Number.isInteger(burstLimit) && burstLimit >= 1 && burstLimit <= 20) {
      this.conversationBurstLimit = burstLimit;
    }
    this.render();
  }

  writePreferences() {
    if (!this.preferenceWriter) return;
    const selectedScanListIds = [...this.selectedScanListIds].map(Number)
      .filter((id) => Number.isSafeInteger(id) && id > 0).sort((left, right) => left - right);
    void Promise.resolve().then(() => this.preferenceWriter({
      volume: this.volume,
      selected_scan_list_ids: selectedScanListIds,
      conversation_grouping: this.conversationGrouping,
      conversation_burst_limit: this.conversationBurstLimit
    })).catch(() => {});
  }

  subscribeState(observer) {
    if (typeof observer !== 'function') return () => {};
    this.stateObservers.add(observer);
    observer(this.viewState());
    return () => this.stateObservers.delete(observer);
  }

  notifyStateObservers() {
    if (!this.stateObservers.size) return;
    const state = this.viewState();
    this.stateObservers.forEach((observer) => {
      try { observer(state); } catch (_) { }
    });
  }

  viewState() {
    return {
      current: this.current,
      displayCall: this.displayCall(),
      currentReady: Boolean(this.current && this.currentBuffer),
      replayingLast: this.replayingLast,
      lastCallReady: Boolean(this.lastHeard && this.lastHeardBuffer),
      paused: this.paused,
      playing: Boolean(this.source),
      targetLabel: this.currentTargetLabel(),
      holdTarget: this.holdTarget,
      queuedCount: this.queuedCount,
      status: this.ui.status?.textContent || '',
      avoids: [...this.avoids.values()].reverse(),
      scanLists: this.scanLists.map((item) => ({ ...item, selected: this.selectedScanListIds.has(item.id) })),
      scanListCatalogReady: this.scanListCatalogReady,
      maximumSelectedScanLists: this.maximumSelectedScanLists,
      volume: this.volume,
      conversationGrouping: this.conversationGrouping,
      conversationBurstLimit: this.conversationBurstLimit
    };
  }

  connectionHandle() {
    return {
      close: () => {
        this.stopFeed();
        this.feedUrl = null;
        this.feedFetch = null;
      }
    };
  }

  activeSelectedScanListIds() {
    return [...this.selectedScanListIds]
      .filter((id) => this.scanListById.get(id)?.enabled)
      .sort((first, second) => Number(first) - Number(second))
      .slice(0, this.maximumSelectedScanLists);
  }

  ensureConnected() {
    if (this.feedActive) return true;
    if (typeof this.feedFetch !== 'function' || !this.feedUrl || !this.scanListCatalogReady ||
        !this.activeSelectedScanListIds().length || this.paused) return false;
    this.feedActive = true;
    this.feedCursor = null;
    const generation = ++this.feedGeneration;
    void this.pollFeed(generation);
    return true;
  }

  feedRequestUrl() {
    const query = new URLSearchParams();
    this.activeSelectedScanListIds().forEach((id) => query.append('scan_list_id', id));
    if (this.feedCursor !== null) query.set('cursor', this.feedCursor);
    return `${this.feedUrl}?${query}`;
  }

  async requestFeed(signal) {
    const value = await this.feedFetch(this.feedRequestUrl(), { signal });
    const cursor = typeof value?.cursor === 'string' && /^\d+$/.test(value.cursor) ? value.cursor : null;
    if (cursor === null || typeof value?.reset !== 'boolean' || !Array.isArray(value?.calls)) {
      throw new Error('The browser call feed returned an invalid response.');
    }
    return { cursor, reset: value.reset, calls: value.calls };
  }

  scheduleFeedPoll(generation, delay) {
    if (!this.feedActive || this.paused || generation !== this.feedGeneration) return;
    this.feedTimer = window.setTimeout(() => {
      this.feedTimer = null;
      void this.pollFeed(generation);
    }, delay);
  }

  async pollFeed(generation) {
    if (!this.feedActive || this.paused || generation !== this.feedGeneration) return;
    const controller = new AbortController();
    this.feedController = controller;
    try {
      const response = await this.requestFeed(controller.signal);
      if (!this.feedActive || this.paused || generation !== this.feedGeneration) return;
      this.feedCursor = response.cursor;
      if (response.reset) this.recordSkippedCallNotice();
      response.calls.forEach((call) => this.enqueue(call));
      if (!this.current && !this.queuedCount) this.setStatus('Waiting');
      this.scheduleFeedPoll(generation, WebCallPlayer.FEED_POLL_INTERVAL_MS);
    } catch (error) {
      if (controller.signal.aborted || !this.feedActive || this.paused || generation !== this.feedGeneration) return;
      this.setStatus('Reconnecting');
      this.scheduleFeedPoll(generation, WebCallPlayer.FEED_RETRY_INTERVAL_MS);
    } finally {
      if (this.feedController === controller) this.feedController = null;
    }
  }

  stopFeed() {
    this.feedActive = false;
    this.feedCursor = null;
    this.feedGeneration++;
    this.feedController?.abort();
    this.feedController = null;
    if (this.feedTimer !== null) window.clearTimeout(this.feedTimer);
    this.feedTimer = null;
  }

  synchronizeSubscription() {
    if (!this.scanListCatalogReady || !this.activeSelectedScanListIds().length) {
      this.stopFeed();
      if (!this.paused) {
        this.paused = true;
        this.clearQueuedCalls();
        this.stopCurrent();
        this.replayingLast = false;
        this.stopAfterReplay = false;
        if (this.audioContext?.state === 'running') this.audioContext.suspend().catch(() => {});
      }
      this.setStatus(this.scanListCatalogReady ? 'Select a scan list' : 'Loading scan lists');
      return;
    }
    if (this.feedActive) {
      this.stopFeed();
      this.ensureConnected();
    } else if (!this.paused && !this.stopAfterReplay) this.ensureConnected();
    else this.setStatus('Ready');
  }

  disconnect(status = 'Unavailable') {
    this.stopFeed();
    this.transportToken++;
    this.loadController?.abort();
    this.loadController = null;
    this.paused = true;
    this.clearQueuedCalls();
    this.avoids.clear();
    this.lastHeard = null;
    this.lastHeardBuffer = null;
    this.replayingLast = false;
    this.stopAfterReplay = false;
    this.holdTarget = null;
    this.clearLossNotice();
    this.clearIdleDisplay();
    this.stopCurrent();
    if (this.audioContext?.state === 'running') this.audioContext.suspend().catch(() => {});
    this.setStatus(status);
    this.render();
  }

  setScanListsLoading() {
    this.scanListCatalogReady = false;
    this.scanListCatalogState = 'loading';
    this.stopFeed();
    if (this.ui.scanListStatus) this.ui.scanListStatus.textContent = 'Loading available scan lists…';
    this.renderScanLists();
  }

  setScanListsUnavailable(message = 'Scan lists are unavailable') {
    this.scanListCatalogReady = false;
    this.scanListCatalogState = 'unavailable';
    this.scanLists = [];
    this.scanListById.clear();
    this.stopFeed();
    this.clearLossNotice();
    if (this.ui.scanListStatus) this.ui.scanListStatus.textContent = message;
    this.setStatus(message);
    this.renderScanLists();
  }

  setScanLists(rows) {
    const unique = new Map();
    (Array.isArray(rows) ? rows : []).slice(0, WebCallPlayer.MAXIMUM_SCAN_LISTS).forEach((row) => {
      const id = Number.isSafeInteger(row?.id) && row.id > 0 ? String(row.id) : '';
      const name = typeof row?.name === 'string' ? row.name.trim() : '';
      const description = row?.description === undefined ? '' : row.description;
      if (typeof description !== 'string' || typeof row?.default !== 'boolean') return;
      if (!id || !name || unique.has(id)) return;
      unique.set(id, {
        id,
        name,
        description: description.trim(),
        enabled: true,
        default: row.default
      });
    });
    // The server already applies the administrator-owned scan-list order.
    this.scanLists = [...unique.values()];
    this.scanListById = new Map(this.scanLists.map((item) => [item.id, item]));
    const retainedSelections = new Set([...this.selectedScanListIds]
      .filter((id) => this.scanListById.has(id)));
    const selectionChanged = retainedSelections.size !== this.selectedScanListIds.size;
    if (selectionChanged) this.selectedScanListIds = retainedSelections;
    this.scanListCatalogReady = true;
    this.scanListCatalogState = 'ready';
    this.updateScanListStatus();
    this.filterQueueForSelectedLists();
    this.renderScanLists();
    this.synchronizeSubscription();
    this.render();
    if (selectionChanged) this.writePreferences();
  }

  scanListsReady() {
    return this.scanListCatalogReady;
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
    this.clearLossNotice();
    this.writePreferences();
    this.updateScanListStatus();
    this.filterQueueForSelectedLists();
    this.renderScanLists();
    this.synchronizeSubscription();
    this.render();
  }

  renderScanLists() {
    if (this.ui.scanListSummary) {
      if (!this.scanListCatalogReady) {
        this.ui.scanListSummary.textContent = this.scanListCatalogState === 'loading' ? 'Loading' : 'Unavailable';
        this.ui.scanListSummary.removeAttribute('title');
      } else {
        const selected = this.scanLists.filter((item) => this.selectedScanListIds.has(item.id));
        this.ui.scanListSummary.textContent = String(selected.length);
        this.ui.scanListSummary.title = selected.length ? selected.map((item) => item.name).join(', ') :
          'No scan lists selected';
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
    const selectionFull = this.activeSelectedScanListIds().length >= this.maximumSelectedScanLists;
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
      label.append(checkbox, copy);
      this.ui.scanListOptions.append(label);
    });
  }

  updateScanListStatus() {
    if (!this.ui.scanListStatus || !this.scanListCatalogReady) return;
    if (!this.scanLists.length) this.ui.scanListStatus.textContent = 'No scan lists are available.';
    else if (!this.activeSelectedScanListIds().length) {
      this.ui.scanListStatus.textContent = 'Select at least one scan list to receive calls.';
    } else {
      this.ui.scanListStatus.textContent = '';
    }
  }

  scanListMessage(message) {
    const value = document.createElement('div');
    value.className = 'muted';
    value.textContent = message;
    return value;
  }

  // The shared HTTP feed supplies normalized completed-call announcements.
  enqueue(call) {
    const normalized = this.normalizeCall(call);
    if (!normalized || !this.callMatchesSelection(normalized)) return;
    if (this.seenCallIds.has(normalized._callId)) return;
    this.rememberCallId(normalized._callId);
    if (!this.isAllowed(normalized)) return;

    while (this.queuedCount >= this.maximumQueued) {
      const dropped = this.dropOldestQueued();
      if (!dropped) break;
      this.recordSkippedCallNotice();
    }

    this.insertQueuedCall(normalized);
    if (!this.paused && !this.current) this.playNext();
    else this.render();
  }

  normalizeCall(value) {
    if (!value || typeof value !== 'object' || typeof value.audio_url !== 'string' ||
        !value.audio_url.trim()) return null;
    const callId = typeof value.call_id === 'string' ? value.call_id.trim() : '';
    const started = value.started_at_ms;
    const completed = value.completed_at_ms;
    if (!callId || !Number.isFinite(started) || started <= 0 || !Number.isFinite(completed) || completed <= 0 ||
        !Array.isArray(value.scan_list_ids)) return null;
    const scanListIds = value.scan_list_ids;
    if (!scanListIds.length || scanListIds.some((id) => !Number.isSafeInteger(id) || id <= 0) ||
        typeof value.conversation_key !== 'string' || !value.conversation_key.trim()) return null;
    const call = {
      ...value,
      _callId: callId,
      _startedAtMs: started,
      _arrivalSequence: this.arrivalSequence++,
      _matchedScanListIds: [...new Set(scanListIds.map(String))]
    };
    call._conversationKey = value.conversation_key.trim();
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
    let index = this.queuedCalls.length;
    while (index > 0 && this.compareCalls(call, this.queuedCalls[index - 1]) < 0) index--;
    this.queuedCalls.splice(index, 0, call);
    this.queuedCount = this.queuedCalls.length;
  }

  compareCalls(first, second) {
    return first._startedAtMs - second._startedAtMs ||
      first._arrivalSequence - second._arrivalSequence ||
      first._callId.localeCompare(second._callId);
  }

  nextQueueIndex(queue, lastKey, consecutive) {
    if (!queue.length || !this.conversationGrouping || !lastKey) return queue.length ? 0 : -1;
    const same = queue.findIndex((call) => call._conversationKey === lastKey);
    const other = queue.findIndex((call) => call._conversationKey !== lastKey);
    if (same >= 0 && (consecutive < this.conversationBurstLimit || other < 0)) return same;
    return other >= 0 ? other : same;
  }

  takeNextCall() {
    const index = this.nextQueueIndex(this.queuedCalls, this.lastConversationKey,
      this.consecutiveConversationCalls);
    if (index < 0) return null;
    const [call] = this.queuedCalls.splice(index, 1);
    this.queuedCount = this.queuedCalls.length;
    const key = call._conversationKey;
    if (key === this.lastConversationKey) {
      this.consecutiveConversationCalls = Math.min(this.conversationBurstLimit,
        this.consecutiveConversationCalls + 1);
    } else {
      this.lastConversationKey = key;
      this.consecutiveConversationCalls = 1;
    }
    return call;
  }

  scheduledQueue(limit = 100) {
    const queue = this.queuedCalls.slice();
    const result = [];
    let lastKey = this.lastConversationKey;
    let consecutive = this.consecutiveConversationCalls;
    while (result.length < limit) {
      const index = this.nextQueueIndex(queue, lastKey, consecutive);
      if (index < 0) break;
      const [call] = queue.splice(index, 1);
      result.push(call);
      const key = call._conversationKey;
      if (key === lastKey) consecutive = Math.min(this.conversationBurstLimit,
        consecutive + 1);
      else {
        lastKey = key;
        consecutive = 1;
      }
    }
    return result;
  }

  dropOldestQueued() {
    const call = this.queuedCalls.shift() || null;
    this.queuedCount = this.queuedCalls.length;
    return call;
  }

  clearQueuedCalls() {
    this.queuedCalls.length = 0;
    this.queuedCount = 0;
  }

  clearQueue() {
    this.clearQueuedCalls();
    this.render();
  }

  filterQueuedCalls(predicate) {
    this.queuedCalls = this.queuedCalls.filter(predicate);
    this.queuedCount = this.queuedCalls.length;
  }

  filterQueueForSelectedLists() {
    this.filterQueuedCalls((call) => this.callMatchesSelection(call));
  }

  callMatchesSelection(call) {
    const selected = new Set(this.activeSelectedScanListIds());
    if (!selected.size) return false;
    return Boolean(call?._matchedScanListIds?.length) &&
      call._matchedScanListIds.some((id) => selected.has(String(id)));
  }

  bindControls() {
    this.ui.play.addEventListener('click', () => this.togglePlayback());
    this.ui.skip.addEventListener('click', () => this.skip());
    this.ui.replay.addEventListener('click', () => this.replayLastCall());
    this.ui.hold.addEventListener('click', () => this.toggleHold());
    this.ui.avoid.addEventListener('click', () => this.avoidCurrent());
    this.ui.avoidList?.addEventListener('click', () => this.actions.openAvoidList?.(this));
    this.ui.clearQueue?.addEventListener('click', () => this.clearQueue());
    this.ui.volume.addEventListener('input', () => this.changeVolume(false));
    this.ui.volume.addEventListener('change', () => this.writePreferences());
    const panels = [this.ui.scanListOptions?.closest('details'), this.ui.queueList?.closest('details')]
      .filter(Boolean);
    panels.forEach((panel) => panel.addEventListener('toggle', () => {
      const expanded = panel.closest('.playback-bar')?.classList.contains('scanner-expanded');
      if (panel.open && !expanded) {
        panels.forEach((other) => { if (other !== panel) other.open = false; });
      }
    }));
  }

  async togglePlayback() {
    const token = ++this.transportToken;
    if (!this.paused) {
      this.paused = true;
      this.stopFeed();
      this.clearQueuedCalls();
      this.lastConversationKey = null;
      this.consecutiveConversationCalls = 0;
      this.stopCurrent();
      this.replayingLast = false;
      this.stopAfterReplay = false;
      this.clearLossNotice();
      if (this.audioContext?.state === 'running') await this.audioContext.suspend();
      if (token !== this.transportToken) return;
      this.setStatus('Ready');
    } else {
      this.paused = false;
      if (!this.ensureConnected()) {
        this.paused = true;
        this.setStatus('Unavailable');
        this.render();
        return;
      }
      this.ensureAudioContext();
      await this.audioContext.resume();
      if (token !== this.transportToken) return;
      this.setStatus('Waiting');
    }

    this.render();
  }

  toggleHold() {
    if (this.replayingLast) return;
    if (this.holdTarget) {
      this.holdTarget = null;
    } else if (this.current && this.currentBuffer) {
      this.holdTarget = this.current._conversationKey;
      this.filterQueuedCalls((call) => call._conversationKey === this.holdTarget);
    }

    this.render();
  }

  avoidCurrent() {
    if (!this.current || !this.currentBuffer || this.replayingLast) return;
    const target = this.current._conversationKey;
    this.avoids.delete(target);
    this.avoids.set(target, {
      key: target,
      label: this.targetLabel(this.current)
    });
    while (this.avoids.size > WebCallPlayer.MAXIMUM_AVOIDS) {
      this.avoids.delete(this.avoids.keys().next().value);
    }
    if (this.holdTarget === target) this.holdTarget = null;
    this.filterQueuedCalls((call) => call._conversationKey !== target);
    this.stopCurrent();
    if (this.paused) this.setStatus('Ready');
    else this.playNext();
  }

  removeAvoid(key) {
    if (!this.avoids.delete(String(key || ''))) return false;
    this.render();
    return true;
  }

  skip() {
    if (this.current) this.stopCurrent();
    else this.takeNextCall();
    if (this.replayingLast) {
      this.replayingLast = false;
      if (this.stopAfterReplay) this.paused = true;
      this.stopAfterReplay = false;
    }
    if (this.paused) {
      this.setStatus('Ready');
      this.render();
    } else {
      this.playNext();
    }
  }

  async replayLastCall() {
    if (!this.lastHeard || !this.lastHeardBuffer) return false;
    const wasStopped = this.paused;
    this.transportToken++;
    this.loadToken++;
    this.loadController?.abort();
    this.loadController = null;
    this.stopSource();
    this.clearIdleDisplay();
    this.current = this.lastHeard;
    this.currentBuffer = this.lastHeardBuffer;
    this.paused = false;
    this.replayingLast = true;
    this.stopAfterReplay = wasStopped;
    this.ensureAudioContext();
    await this.audioContext.resume();
    if (!this.replayingLast || !this.currentBuffer) return false;
    this.setStatus('Replaying last call');
    this.startCurrent();
    return true;
  }

  async playNext(completedCall = null) {
    if (this.paused || this.current) return;

    let next = this.takeNextCall();
    while (next && (!this.callMatchesSelection(next) || !this.isAllowed(next))) {
      next = this.takeNextCall();
    }
    if (!next) {
      if (completedCall) this.showIdleDisplay(completedCall);
      this.setStatus('Waiting');
      this.render();
      return;
    }

    this.clearIdleDisplay();
    this.current = next;
    this.currentBuffer = null;
    await this.loadCurrent();
  }

  async loadCurrent() {
    if (!this.current) return;
    const requested = this.current;
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
        const response = await fetch(requested.audio_url, {
          cache: 'no-store', credentials: 'same-origin', signal: loadController.signal
        });
        if (!response.ok) throw new Error(`Audio returned ${response.status}`);
        this.ensureAudioContext();
        const data = await response.arrayBuffer();
        return this.audioContext.decodeAudioData(data);
      })(), timeoutFailure]);
      if (token !== this.loadToken || this.current !== requested) return;
      this.currentBuffer = buffer;
      if (!this.paused) this.startCurrent();
    } catch (error) {
      if (error?.name === 'AbortError' && !loadTimedOut) return;
      if (token === this.loadToken) {
        this.recordSkippedCallNotice();
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
    } finally {
      window.clearTimeout(loadTimeout);
      if (this.loadController === loadController) this.loadController = null;
    }
  }

  startCurrent() {
    if (!this.current || !this.currentBuffer || this.paused || this.source) return;
    const token = this.loadToken;
    const source = this.audioContext.createBufferSource();
    source.buffer = this.currentBuffer;
    source.connect(this.analyserNode);
    source.onended = () => {
      if (token !== this.loadToken || source !== this.source) return;
      if (this.replayingLast) {
        const stopAfterReplay = this.stopAfterReplay;
        source.disconnect();
        this.stopProgress();
        this.source = null;
        this.current = null;
        this.currentBuffer = null;
        this.replayingLast = false;
        this.stopAfterReplay = false;
        if (stopAfterReplay) {
          this.paused = true;
          if (this.audioContext?.state === 'running') this.audioContext.suspend().catch(() => {});
          this.setStatus('Ready');
          this.render();
        } else {
          this.playNext();
        }
        return;
      }
      const completed = this.current;
      const completedBuffer = this.currentBuffer;
      source.disconnect();
      this.stopProgress();
      this.source = null;
      this.current = null;
      this.currentBuffer = null;
      this.lastHeard = completed;
      this.lastHeardBuffer = completedBuffer;
      this.playNext(completed);
    };
    this.source = source;
    this.playbackStartedAt = this.audioContext.currentTime;
    source.start(0);
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
    this.loadController?.abort();
    this.loadController = null;
    this.stopSource();
    this.current = null;
    this.currentBuffer = null;
    this.clearIdleDisplay();
    this.render();
  }

  clearIdleDisplay() {
    if (this.idleDisplayTimer !== null) {
      window.clearTimeout(this.idleDisplayTimer);
      this.idleDisplayTimer = null;
    }
    this.idleDisplayCall = null;
    this.idleDisplayDeadline = 0;
  }

  showIdleDisplay(call) {
    this.clearIdleDisplay();
    if (!call) return;
    this.idleDisplayCall = call;
    this.idleDisplayDeadline = Date.now() + WebCallPlayer.IDLE_CALL_DISPLAY_MS;
    const expire = () => {
      if (this.idleDisplayCall !== call) return;
      const remaining = this.idleDisplayDeadline - Date.now();
      if (remaining > 0) {
        this.idleDisplayTimer = window.setTimeout(expire, remaining);
        return;
      }
      this.clearIdleDisplay();
      this.render();
    };
    this.idleDisplayTimer = window.setTimeout(expire, WebCallPlayer.IDLE_CALL_DISPLAY_MS);
  }

  displayCall() {
    if (this.current) return this.current;
    return this.idleDisplayCall && Date.now() < this.idleDisplayDeadline ? this.idleDisplayCall : null;
  }

  getPlaybackPosition() {
    const duration = Number(this.currentBuffer?.duration);
    const position = this.source && this.audioContext ?
      this.audioContext.currentTime - this.playbackStartedAt : 0;
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
    const active = hasDuration && Boolean(this.source);
    this.ui.progress.style.setProperty('--playback-progress', String(progress));
    this.ui.progress.classList.toggle('active', active);
    this.ui.progress.classList.toggle('ending', Boolean(this.source) && duration - position <= fadeWindow);
  }

  changeVolume(write = false) {
    const requested = Number(this.ui.volume.value);
    this.volume = Number.isFinite(requested) ? Math.max(0, Math.min(1, requested)) : 1;
    this.ui.volume.value = String(this.volume);
    if (this.gainNode) this.gainNode.gain.value = this.volume;
    this.renderVolume();
    if (write) this.writePreferences();
  }

  ensureAudioContext() {
    if (!this.audioContext) {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      this.audioContext = new AudioContext();
      this.analyserNode = this.audioContext.createAnalyser();
      this.analyserNode.fftSize = 256;
      this.analyserNode.smoothingTimeConstant = 0.5;
      this.gainNode = this.audioContext.createGain();
      this.gainNode.gain.value = this.volume;
      this.analyserNode.connect(this.gainNode);
      this.gainNode.connect(this.audioContext.destination);
    }
  }

  readAudioWaveform(levels) {
    if (!levels?.length) return false;
    levels.fill(0);
    if (!this.source || !this.analyserNode || this.audioContext?.state !== 'running') return false;
    const sampleCount = this.analyserNode.fftSize;
    if (!this.waveformSamples || this.waveformSamples.length !== sampleCount) {
      this.waveformSamples = new Uint8Array(sampleCount);
    }
    this.analyserNode.getByteTimeDomainData(this.waveformSamples);
    for (let bar = 0; bar < levels.length; bar++) {
      const start = Math.floor(bar * sampleCount / levels.length);
      const end = Math.max(start + 1, Math.floor((bar + 1) * sampleCount / levels.length));
      let peak = 0;
      for (let sample = start; sample < end; sample++) {
        peak = Math.max(peak, Math.abs(this.waveformSamples[sample] - 128) / 128);
      }
      levels[bar] = peak < 0.01 ? 0 : Math.min(1, Math.sqrt(peak) * 1.15);
    }
    return true;
  }

  renderVolume() {
    this.ui.volume.setAttribute('aria-valuetext', `${Math.round(this.volume * 100)} percent`);
  }

  isAllowed(call) {
    const target = call?._conversationKey || this.targetKey(call);
    return !this.avoids.has(target) && (!this.holdTarget || this.holdTarget === target);
  }

  targetKey(call) {
    return String(call?._conversationKey || call?.conversation_key || '').trim();
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

  targetLabel(call) {
    if (!call) return '';
    const alias = String(call.target_alias || '').trim();
    if (alias) return alias;
    const targetId = call.target_id === null || call.target_id === undefined || call.target_id === '' ? '' :
      String(call.target_id);
    if (targetId) return `${this.identifierType(call.target_form, 'TGID')} ${targetId}`;
    return String(call.channel || '').trim() || 'Unknown target';
  }

  currentTargetLabel() {
    return this.targetLabel(this.current);
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
    this.statusValue = String(value || '');
    this.renderStatus();
  }

  renderStatus() {
    if (!this.ui.status) return;
    this.ui.status.textContent = [this.statusValue, this.skippedNotice ? 'Some calls were skipped' : '']
      .filter(Boolean).join(' · ');
  }

  recordSkippedCallNotice() {
    this.skippedNotice = true;
    this.renderStatus();
    this.notifyStateObservers();
  }

  clearLossNotice() {
    this.skippedNotice = false;
    this.renderStatus();
  }

  render() {
    this.renderVolume();
    const currentReady = Boolean(this.current && this.currentBuffer);
    const lastCallReady = Boolean(this.lastHeard && this.lastHeardBuffer);
    const displayedCall = this.displayCall();
    this.ui.current.replaceChildren();
    if (displayedCall) {
      const label = this.callLabel(displayedCall);
      const primary = document.createElement('strong');
      primary.className = 'playback-current-primary';
      primary.textContent = label;
      this.ui.current.append(primary);
      const details = this.callDetails(displayedCall);
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

    const playLabel = this.paused ? 'Play browser call audio' : 'Stop browser call audio';
    const playIcon = this.ui.play.querySelector('use');
    if (playIcon) playIcon.setAttribute('href', this.paused ? '#icon-play' : '#icon-stop');
    this.ui.play.classList.toggle('active', !this.paused);
    this.ui.play.setAttribute('aria-pressed', String(!this.paused));
    this.ui.play.setAttribute('aria-label', playLabel);
    this.ui.play.title = playLabel;
    this.ui.replay.disabled = !lastCallReady;
    this.ui.replay.setAttribute('aria-label', 'Replay last call');
    this.ui.replay.title = 'Replay last call';
    this.ui.hold.classList.toggle('active', Boolean(this.holdTarget));
    this.ui.hold.disabled = this.replayingLast || (!this.holdTarget && !currentReady);
    this.ui.hold.title = this.holdTarget ? 'Release browser hold' : 'Hold the current target in this browser';
    this.ui.hold.setAttribute('aria-label', this.ui.hold.title);
    this.ui.avoid.disabled = !currentReady || this.replayingLast;
    if (this.ui.avoidList) {
      const avoidListLabel = `View ${this.avoids.size} browser avoid(s)`;
      this.ui.avoidList.setAttribute('aria-label', avoidListLabel);
      this.ui.avoidList.title = avoidListLabel;
    }
    if (this.ui.clearQueue) {
      this.ui.clearQueue.disabled = !this.queuedCount;
      this.ui.clearQueue.title = `Clear ${this.queuedCount} queued browser call(s)`;
      this.ui.clearQueue.setAttribute('aria-label', this.ui.clearQueue.title);
    }
    this.ui.skip.disabled = !this.current && !this.queuedCount;
    this.renderProgress();
    this.notifyStateObservers();
  }
}
