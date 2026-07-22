/* global AbortController, Blob, CustomEvent, URL, document, fetch, window */
'use strict';

class ChannelApiError extends Error {
  constructor(status, payload, fallback) {
    super(payload?.message || payload?.error || fallback || `Request failed (${status}).`);
    this.name = 'ChannelApiError';
    this.status = status;
    this.payload = payload || {};
  }
}

class SettingsChannelsView {
  constructor(root, session, callbacks = {}) {
    this.root = root;
    this.session = session || {};
    this.callbacks = callbacks;
    this.closed = false;
    this.listController = null;
    this.detailController = null;
    this.items = [];
    this.listFailed = false;
    this.total = 0;
    this.offset = 0;
    this.limit = 50;
    this.query = '';
    this.sort = 'startOrder';
    this.direction = 'ascending';
    this.queueRevision = null;
    this.autoStartCount = null;
    this.options = {};
    this.selectedIds = new Map();
    this.selectionAnchorId = null;
    this.bulkFailures = new Map();
    this.detail = null;
    this.originalDetail = null;
    this.dirty = false;
    this.guidUnlocked = false;
    this.revisionConflict = false;
    this.pending = false;
    this.searchTimer = null;
    this.helpOwner = null;
    this.helpPinned = false;
    this.dialogReturnFocus = null;
    this.onBeforeUnload = (event) => {
      if (!this.dirty) return;
      event.preventDefault();
      event.returnValue = '';
    };
    this.onNavigation = (event) => {
      if (!this.dirty || event.defaultPrevented || event.button !== 0 || event.metaKey ||
          event.ctrlKey || event.shiftKey || event.altKey) return;
      const link = event.target?.closest?.('a');
      if (!link || link.target || link.hasAttribute('download')) return;
      const target = new URL(link.href, window.location.href);
      if (target.origin !== window.location.origin || target.pathname !== '/') return;
      if (target.searchParams.get('view') === 'settings' &&
          target.searchParams.get('section') === 'channels') return;
      if (window.confirm('Discard the unsaved channel changes?')) {
        this.dirty = false;
      } else {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    };
    this.onDocumentPointer = (event) => {
      if (!this.helpPinned || !this.helpOwner) return;
      if (this.helpOwner.contains(event.target) || this.helpPopover?.contains(event.target)) return;
      this.hideHelp();
    };
    this.onBeforeRouteChange = (event) => {
      if (!this.dirty) return;
      if (window.confirm('Discard the unsaved channel changes?')) this.dirty = false;
      else event.preventDefault();
    };
    window.addEventListener('beforeunload', this.onBeforeUnload);
    window.addEventListener('sdrtrunk:before-route-change', this.onBeforeRouteChange);
    document.addEventListener('click', this.onNavigation, true);
    document.addEventListener('pointerdown', this.onDocumentPointer, true);
    this.renderShell();
    this.loadList();
  }

  element(tag, className = '', text = null) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== null && text !== undefined) element.textContent = String(text);
    return element;
  }

  button(text, className = '') {
    const button = this.element('button', className, text);
    button.type = 'button';
    return button;
  }

  escape(value) {
    return String(value ?? '').replace(/[&<>"']/g, (character) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[character]);
  }

  clone(value) {
    return value == null ? value : JSON.parse(JSON.stringify(value));
  }

  renderShell() {
    this.root.className = 'settings-page channels-settings-page';
    const layout = this.element('div', 'settings-layout');
    const navigation = this.element('nav', 'settings-navigation');
    navigation.setAttribute('aria-label', 'Settings sections');
    navigation.append(this.element('h2', '', 'Settings'));
    const group = this.element('section', 'settings-navigation-group');
    group.append(this.element('h3', '', 'Playlist Settings'));
    const hardware = this.element('a', '', 'Hardware');
    hardware.href = '/?view=settings&section=hardware';
    const channels = this.element('a', 'active', 'Channels');
    channels.href = '/?view=settings&section=channels';
    channels.setAttribute('aria-current', 'page');
    group.append(hardware, channels);
    navigation.append(group);

    this.main = this.element('div', 'settings-main channels-settings-main');
    const header = this.element('header', 'settings-header');
    const heading = this.element('div');
    heading.append(this.element('p', 'settings-breadcrumbs', 'Settings / Playlist Settings / Channels'),
      this.element('h1', '', 'Channels'),
      this.element('p', 'settings-description',
        'Find and configure saved radio channels, set their automatic-start order, or start and stop a bounded selection.'));
    const administrator = this.element('div', 'settings-administrator');
    administrator.append(this.element('span', 'settings-administrator-name',
      this.session.username || 'Administrator'));
    const signOut = this.button('Sign out');
    signOut.addEventListener('click', () => {
      if (this.dirty && !window.confirm('Discard the unsaved channel changes and sign out?')) return;
      this.dirty = false;
      this.callbacks.logout?.(signOut);
    });
    administrator.append(signOut);
    header.append(heading, administrator);

    this.workspace = this.element('div', 'channels-workspace');
    this.listPanel = this.buildListPanel();
    this.editorPanel = this.element('section', 'settings-panel channels-editor-panel');
    this.renderEditorEmpty();
    this.workspace.append(this.listPanel, this.editorPanel);

    this.helpPopover = this.element('div', 'channels-help-popover');
    this.helpPopover.id = 'channels-field-help';
    this.helpPopover.setAttribute('role', 'tooltip');
    this.helpPopover.hidden = true;
    this.dialog = this.buildDialog();
    this.toastRegion = this.element('div', 'channels-toast-region');
    this.toastRegion.setAttribute('role', 'status');
    this.toastRegion.setAttribute('aria-live', 'polite');

    this.main.append(header, this.workspace, this.helpPopover, this.dialog, this.toastRegion);
    layout.append(navigation, this.main);
    this.root.replaceChildren(layout);
    this.bindHelpDelegation();
  }

  buildListPanel() {
    const panel = this.element('section', 'settings-panel channels-list-panel');
    const head = this.element('header', 'settings-panel-header channels-list-header');
    const title = this.element('div');
    this.countCopy = this.element('p', '', 'Loading a bounded page of saved channels…');
    title.append(this.element('h2', '', 'Saved channels'), this.countCopy);
    this.addMenu = this.element('details', 'channels-add-menu');
    this.addMenu.append(this.element('summary', '', 'Add channel'));
    this.addMenuBody = this.element('div', 'channels-add-menu-body');
    this.addMenuBody.setAttribute('aria-label', 'Supported channel types');
    this.addMenu.append(this.addMenuBody);
    head.append(title, this.addMenu);

    const toolbar = this.element('div', 'channels-list-toolbar');
    const searchField = this.element('div', 'channels-search-field');
    const searchLabel = this.element('label', '', 'Search System, Site, Name, or decoder');
    searchLabel.htmlFor = 'channels-search';
    this.searchInput = this.element('input');
    this.searchInput.id = 'channels-search';
    this.searchInput.type = 'search';
    this.searchInput.autocomplete = 'off';
    this.searchInput.maxLength = 80;
    this.searchInput.placeholder = 'Search saved channels';
    this.searchInput.addEventListener('input', () => {
      window.clearTimeout(this.searchTimer);
      this.searchTimer = window.setTimeout(() => {
        this.query = this.searchInput.value.trim().slice(0, 80);
        this.offset = 0;
        this.clearSelection();
        this.loadList();
      }, 250);
    });
    const clearSearch = this.button('×', 'channels-search-clear');
    clearSearch.setAttribute('aria-label', 'Clear channel search');
    clearSearch.addEventListener('click', () => {
      if (!this.searchInput.value && !this.query) return;
      this.searchInput.value = '';
      this.query = '';
      this.offset = 0;
      this.clearSelection();
      this.loadList();
      this.searchInput.focus();
    });
    searchField.append(searchLabel, this.searchInput, clearSearch);

    this.selectionToolbar = this.element('div', 'channels-selection-toolbar');
    this.selectionCount = this.element('span', 'channels-selection-count', 'No channels selected');
    this.startSelected = this.button('Start selected');
    this.stopSelected = this.button('Stop selected');
    this.clearSelected = this.button('Clear selection', 'button-link');
    this.startSelected.addEventListener('click', () => this.bulkRuntime('START'));
    this.stopSelected.addEventListener('click', () => this.bulkRuntime('STOP'));
    this.clearSelected.addEventListener('click', () => this.clearSelection());
    this.selectionToolbar.append(this.selectionCount, this.startSelected, this.stopSelected,
      this.clearSelected);
    toolbar.append(searchField, this.selectionToolbar);

    this.bulkResult = this.element('section', 'channels-bulk-result');
    this.bulkResult.hidden = true;
    this.bulkResult.setAttribute('role', 'status');

    this.timeoutBar = this.element('div', 'channels-timeout-bar');
    const timeoutText = this.element('div');
    const timeoutTitle = this.element('strong', '', 'Automatic-start timeout');
    timeoutText.append(timeoutTitle, this.helpButton('Automatic-start timeout',
      'How long startup waits for one automatic-start channel before continuing to the next. This setting applies to the whole automatic-start queue.'));
    timeoutText.append(this.element('span', '', 'Maximum wait for each channel during application startup.'));
    const timeoutControls = this.element('div', 'channels-timeout-controls');
    this.timeoutInput = this.element('input');
    this.timeoutInput.type = 'number';
    this.timeoutInput.step = '1';
    this.timeoutInput.setAttribute('aria-label', 'Automatic-start timeout in seconds');
    const seconds = this.element('span', 'channels-input-unit', 'seconds');
    this.timeoutSave = this.button('Save timeout');
    this.timeoutSave.addEventListener('click', () => this.saveAutoStartTimeout());
    timeoutControls.append(this.timeoutInput, seconds, this.timeoutSave);
    this.timeoutBar.append(timeoutText, timeoutControls);

    this.tableRegion = this.element('div', 'channels-table-region');
    this.tableRegion.tabIndex = 0;
    this.tableRegion.setAttribute('role', 'region');
    this.tableRegion.setAttribute('aria-label', 'Scrollable channels table');
    this.table = this.element('table', 'channels-table');
    const caption = this.element('caption', 'visually-hidden', 'Saved radio channels.');
    const headRow = this.element('tr');
    const selectHeader = this.element('th');
    this.selectPage = this.element('input');
    this.selectPage.type = 'checkbox';
    this.selectPage.setAttribute('aria-label', 'Select all channels on this page');
    this.selectPage.addEventListener('change', () => this.selectCurrentPage(this.selectPage.checked));
    selectHeader.append(this.selectPage);
    headRow.append(selectHeader);
    [
      ['system', 'System'], ['site', 'Site'], ['name', 'Name'],
      ['frequency', 'Frequency or frequencies'], ['protocol', 'Protocol'],
      ['state', 'State'], ['startOrder', 'Start order']
    ].forEach(([key, label]) => {
      const cell = this.element('th');
      cell.scope = 'col';
      const sort = this.button('', 'channels-sort-button');
      sort.dataset.sort = key;
      sort.append(document.createTextNode(label + ' '), this.element('span', 'channels-sort-indicator', '↕'));
      sort.addEventListener('click', () => this.changeSort(key));
      cell.append(sort);
      headRow.append(cell);
    });
    const actionHeader = this.element('th', '', 'Action');
    actionHeader.scope = 'col';
    headRow.append(actionHeader);
    const thead = this.element('thead');
    thead.append(headRow);
    this.tableBody = this.element('tbody');
    this.table.append(caption, thead, this.tableBody);
    this.listState = this.element('div', 'channels-list-state');
    this.listState.hidden = true;
    this.tableRegion.append(this.table, this.listState);

    const footer = this.element('footer', 'channels-table-footer');
    this.tableStatus = this.element('span', '', 'Loading channels…');
    this.tableStatus.setAttribute('role', 'status');
    this.tableStatus.setAttribute('aria-live', 'polite');
    const pager = this.element('div', 'channels-pager');
    this.previousPage = this.button('Previous');
    this.pageLabel = this.element('span', '', 'Page 1');
    this.nextPage = this.button('Next');
    this.previousPage.addEventListener('click', () => this.changePage(-1));
    this.nextPage.addEventListener('click', () => this.changePage(1));
    pager.append(this.previousPage, this.pageLabel, this.nextPage);
    footer.append(this.tableStatus, pager);
    panel.append(head, toolbar, this.bulkResult, this.timeoutBar, this.tableRegion, footer);
    return panel;
  }

  buildDialog() {
    const dialog = this.element('dialog', 'channels-dialog');
    dialog.setAttribute('aria-labelledby', 'channels-dialog-title');
    const header = this.element('header', 'channels-dialog-header');
    const heading = this.element('div');
    this.dialogTitle = this.element('h2');
    this.dialogTitle.id = 'channels-dialog-title';
    this.dialogSubtitle = this.element('p');
    heading.append(this.dialogTitle, this.dialogSubtitle);
    const close = this.button('×', 'channels-dialog-close');
    close.setAttribute('aria-label', 'Close dialog');
    close.addEventListener('click', () => this.closeDialog());
    header.append(heading, close);
    this.dialogBody = this.element('div', 'channels-dialog-body');
    this.dialogFooter = this.element('footer', 'channels-dialog-footer');
    dialog.append(header, this.dialogBody, this.dialogFooter);
    dialog.addEventListener('click', (event) => {
      if (event.target === dialog) this.closeDialog();
    });
    return dialog;
  }

  openDialog(title, subtitle, body, buttons) {
    this.hideHelp();
    this.dialogReturnFocus = document.activeElement;
    this.dialogTitle.textContent = title;
    this.dialogSubtitle.textContent = subtitle || '';
    this.dialogBody.replaceChildren();
    if (typeof body === 'string') this.dialogBody.innerHTML = body;
    else if (body) this.dialogBody.append(body);
    this.dialogFooter.replaceChildren();
    (buttons || [{ label: 'Close' }]).forEach((definition) => {
      const button = this.button(definition.label, definition.className || '');
      button.disabled = Boolean(definition.disabled);
      button.addEventListener('click', definition.action || (() => this.closeDialog()));
      this.dialogFooter.append(button);
    });
    this.dialog.showModal();
  }

  closeDialog() {
    if (this.dialog.open) this.dialog.close();
    const focus = this.dialogReturnFocus;
    this.dialogReturnFocus = null;
    if (focus?.isConnected) focus.focus();
  }

  helpButton(title, copy) {
    const button = this.button('i', 'channels-help-button');
    button.setAttribute('aria-label', `More information about ${title}`);
    button.setAttribute('aria-expanded', 'false');
    button.setAttribute('aria-controls', 'channels-field-help');
    button.dataset.helpTitle = title;
    button.dataset.help = copy;
    return button;
  }

  fieldLabel(label, help, id = '') {
    const wrapper = this.element('div', 'channels-field-label');
    const text = id ? this.element('label', '', label) : this.element('span', '', label);
    if (id) text.htmlFor = id;
    wrapper.append(text, this.helpButton(label, help));
    return wrapper;
  }

  bindHelpDelegation() {
    this.main.addEventListener('pointerover', (event) => {
      const button = event.target.closest('.channels-help-button');
      if (button && !this.helpPinned) this.showHelp(button, false);
    });
    this.main.addEventListener('pointerout', (event) => {
      const button = event.target.closest('.channels-help-button');
      if (button && !this.helpPinned && !button.contains(event.relatedTarget)) this.hideHelp();
    });
    this.main.addEventListener('focusin', (event) => {
      const button = event.target.closest('.channels-help-button');
      if (button && !this.helpPinned) this.showHelp(button, false);
    });
    this.main.addEventListener('focusout', (event) => {
      const button = event.target.closest('.channels-help-button');
      if (button && !this.helpPinned) window.setTimeout(() => {
        if (document.activeElement !== button) this.hideHelp();
      }, 0);
    });
    this.main.addEventListener('click', (event) => {
      const button = event.target.closest('.channels-help-button');
      if (!button) return;
      event.stopPropagation();
      if (this.helpPinned && this.helpOwner === button) this.hideHelp();
      else this.showHelp(button, true);
    });
  }

  showHelp(button, pinned) {
    if (!button?.isConnected) return;
    if (this.helpOwner && this.helpOwner !== button) {
      this.helpOwner.setAttribute('aria-expanded', 'false');
      this.helpOwner.removeAttribute('aria-describedby');
    }
    this.helpOwner = button;
    this.helpPinned = pinned;
    this.helpPopover.replaceChildren(this.element('strong', '', button.dataset.helpTitle),
      this.element('p', '', button.dataset.help));
    this.helpPopover.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    button.setAttribute('aria-describedby', this.helpPopover.id);
    const bounds = button.getBoundingClientRect();
    const gap = 7;
    let left = Math.max(12, Math.min(bounds.left, window.innerWidth - this.helpPopover.offsetWidth - 12));
    let top = bounds.bottom + gap;
    if (top + this.helpPopover.offsetHeight > window.innerHeight - 12) {
      top = Math.max(12, bounds.top - this.helpPopover.offsetHeight - gap);
    }
    this.helpPopover.style.left = `${left}px`;
    this.helpPopover.style.top = `${top}px`;
  }

  hideHelp() {
    this.helpOwner?.setAttribute('aria-expanded', 'false');
    this.helpOwner?.removeAttribute('aria-describedby');
    this.helpOwner = null;
    this.helpPinned = false;
    if (this.helpPopover) this.helpPopover.hidden = true;
  }

  async api(path, init = {}, controller = null) {
    const response = await fetch(path, {
      cache: 'no-store', credentials: 'same-origin', ...init,
      signal: controller?.signal || init.signal
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (response.status === 401) this.callbacks.requireAuthentication?.();
      if (response.status === 403) await this.checkAuthentication();
      throw new ChannelApiError(response.status, payload);
    }
    return payload;
  }

  async checkAuthentication() {
    try {
      const response = await fetch('/api/v1/auth/session', {
        cache: 'no-store', credentials: 'same-origin'
      });
      const session = response.ok ? await response.json() : null;
      if (session?.authenticated === true) this.session = session;
      else this.callbacks.requireAuthentication?.();
    } catch (error) {
      // A transient connection error is reported by the original operation.
    }
  }

  mutationHeaders() {
    return {
      'Content-Type': 'application/json',
      'X-CSRF-Token': String(this.session?.csrfToken || '')
    };
  }

  optionSource(name, fallback = []) {
    const candidates = [this.detail?.options?.[name], this.options?.[name]];
    const source = candidates.find(Array.isArray) || fallback;
    return source.map((entry) => {
      if (entry && typeof entry === 'object') {
        return {
          value: entry.value ?? entry.id ?? entry.key ?? entry.protocol ?? '',
          label: entry.label ?? entry.name ?? entry.displayName ?? entry.value ?? entry.id ?? ''
        };
      }
      return { value: entry, label: entry };
    });
  }

  firstOption(names, fallback = []) {
    for (const name of names) {
      const options = this.optionSource(name);
      if (options.length) return options;
    }
    return this.optionSource('', fallback);
  }

  renderAddMenu() {
    const fallback = [
      { value: 'P25_CONVENTIONAL', label: 'P25 Conventional' },
      { value: 'P25_PHASE1', label: 'P25 Trunked Phase 1' },
      { value: 'P25_PHASE2', label: 'P25 Trunked Phase 2' },
      { value: 'DMR', label: 'DMR' }, { value: 'NBFM', label: 'NBFM' },
      { value: 'NXDN', label: 'NXDN' }
    ];
    let protocols = this.firstOption(['protocols', 'supportedProtocols'], fallback);
    const allowed = new Set(['P25_CONVENTIONAL', 'P25_PHASE1', 'P25_PHASE2', 'DMR', 'NBFM', 'NXDN']);
    protocols = protocols.filter((entry) => allowed.has(this.protocolKind(entry.value)) ||
      allowed.has(this.protocolKind(entry.label)));
    this.addMenuBody.replaceChildren();
    protocols.forEach((protocol) => {
      const button = this.button(protocol.label);
      button.dataset.protocol = String(protocol.value);
      button.addEventListener('click', () => {
        this.addMenu.open = false;
        this.createChannel(protocol.value);
      });
      this.addMenuBody.append(button);
    });
    this.addMenu.hidden = protocols.length === 0;
  }

  protocolKind(value) {
    const normalized = String(value ?? '').toUpperCase().replace(/[^A-Z0-9]+/g, '_');
    if (normalized.includes('P25') && normalized.includes('CONVENT')) return 'P25_CONVENTIONAL';
    if (normalized.includes('P25') && (normalized.includes('PHASE_2') || normalized.includes('PHASE2'))) return 'P25_PHASE2';
    if (normalized.includes('P25') && (normalized.includes('PHASE_1') || normalized.includes('PHASE1') || normalized.includes('TRUNK'))) return 'P25_PHASE1';
    if (normalized.includes('DMR')) return 'DMR';
    if (normalized.includes('NBFM')) return 'NBFM';
    if (normalized.includes('NXDN')) return 'NXDN';
    return normalized;
  }

  async loadList() {
    if (this.closed) return;
    this.listController?.abort();
    const controller = new AbortController();
    this.listController = controller;
    this.showListState('Loading channels', 'Loading one bounded page of saved channels…');
    const parameters = new URLSearchParams({
      q: String(this.query || '').slice(0, 80), sort: this.sort, direction: this.direction,
      offset: String(this.offset), limit: String(this.limit)
    });
    try {
      const result = await this.api(`/api/v1/configuration/channels?${parameters}`, {}, controller);
      if (this.closed || controller !== this.listController) return;
      this.items = Array.isArray(result.items) ? result.items : [];
      this.listFailed = false;
      this.total = Number.isFinite(Number(result.total)) ? Number(result.total) : this.items.length;
      this.offset = Number.isFinite(Number(result.offset)) ? Number(result.offset) : this.offset;
      this.limit = Math.max(1, Number(result.limit) || this.limit);
      if (this.total === 0 && this.offset !== 0) {
        this.offset = 0;
      } else if (this.total > 0 && this.offset >= this.total) {
        this.offset = Math.floor((this.total - 1) / this.limit) * this.limit;
        await this.loadList();
        return;
      }
      this.sort = result.sort || this.sort;
      this.direction = result.direction || this.direction;
      this.queueRevision = result.queueRevision ?? this.queueRevision;
      this.autoStartCount = Number.isFinite(Number(result.autoStartCount)) ? Number(result.autoStartCount) : null;
      this.options = result.options && typeof result.options === 'object' ? result.options : {};
      if (this.autoStartCount == null && this.sort === 'startOrder' && this.direction === 'ascending' &&
          !this.query && this.offset === 0) {
        const count = this.items.filter((item) => Number(item.autoStartOrder) > 0).length;
        if (this.items.some((item) => item.autoStartOrder == null) || this.items.length >= this.total) {
          this.autoStartCount = count;
        }
      }
      this.reconcileSelectedRevisions();
      const refreshOpenDetail = this.reconcileOpenDetail();
      this.renderAddMenu();
      this.renderTimeout();
      this.renderTable();
      if (refreshOpenDetail) {
        await this.reloadOpenDetail().catch((error) => this.toast(this.errorMessage(error), true));
      }
    } catch (error) {
      if (error.name === 'AbortError' || this.closed) return;
      this.listFailed = true;
      this.showListState('Channels could not be loaded', this.errorMessage(error), true);
      this.tableStatus.textContent = 'Channel list unavailable';
    } finally {
      if (this.listController === controller) this.listController = null;
    }
  }

  renderTimeout() {
    const raw = this.options.autoStartTimeoutSeconds ?? this.options.autoStartTimeout?.value;
    const minimum = this.options.autoStartTimeoutMinimumSeconds ??
      this.options.autoStartTimeout?.minimum ?? 0;
    const maximum = this.options.autoStartTimeoutMaximumSeconds ??
      this.options.autoStartTimeout?.maximum ?? 30;
    this.timeoutInput.min = String(minimum);
    this.timeoutInput.max = String(maximum);
    this.timeoutInput.value = raw == null ? '' : String(raw);
    this.timeoutInput.placeholder = 'Seconds';
  }

  async saveAutoStartTimeout() {
    if (this.pending || !this.timeoutInput.reportValidity() || this.timeoutInput.value === '') return;
    const seconds = Number(this.timeoutInput.value);
    await this.runMutation(async () => {
      await this.api('/api/v1/configuration/channels/auto-start-timeout', {
        method: 'PUT', headers: this.mutationHeaders(), body: JSON.stringify({ seconds })
      });
      this.toast('Automatic-start timeout saved.');
      await this.loadList();
    });
  }

  showListState(title, detail, failed = false) {
    this.table.hidden = true;
    this.listState.hidden = false;
    this.listState.classList.toggle('failed', failed);
    this.listState.replaceChildren(this.element('strong', '', title), this.element('span', '', detail));
    if (failed) {
      const retry = this.button('Try again');
      retry.addEventListener('click', () => this.loadList());
      this.listState.append(retry);
    }
  }

  renderTable() {
    this.tableBody.replaceChildren();
    this.table.hidden = this.items.length === 0;
    this.listState.hidden = this.items.length !== 0;
    if (!this.items.length) {
      this.showListState(this.query ? 'No matching channels' : 'No saved channels',
        this.query ? 'Try another System, Site, Name, or decoder search.' :
          'Use Add channel to create one of the supported decoder types.');
    } else {
      this.listState.replaceChildren();
      this.listState.hidden = true;
      this.items.forEach((item) => this.tableBody.append(this.channelRow(item)));
    }
    const first = this.total ? this.offset + 1 : 0;
    const last = Math.min(this.total, this.offset + this.items.length);
    this.countCopy.textContent = this.total ? `${first}–${last} of ${this.total} saved channels` :
      (this.query ? 'No matching saved channels' : 'No saved channels');
    this.tableStatus.textContent = this.total ? `Showing ${first}–${last} of ${this.total} channels` : '0 channels';
    const page = Math.floor(this.offset / this.limit) + 1;
    const pages = Math.max(1, Math.ceil(this.total / this.limit));
    this.pageLabel.textContent = `Page ${page} of ${pages}`;
    this.previousPage.disabled = this.pending || this.offset <= 0;
    this.nextPage.disabled = this.pending || this.offset + this.items.length >= this.total;
    this.updateSortIndicators();
    this.updateSelectionControls();
  }

  channelRow(item) {
    const row = this.element('tr');
    row.dataset.rowId = item.id;
    row.tabIndex = 0;
    row.setAttribute('aria-selected', String(this.selectedIds.has(item.id)));
    row.classList.toggle('selected', this.selectedIds.has(item.id));
    if (this.detail?.id === item.id) row.classList.add('open-channel');
    const failure = this.bulkFailures.get(item.id);
    if (failure) row.classList.add('channels-row-failed');
    const checkCell = this.element('td');
    const checkbox = this.element('input');
    checkbox.type = 'checkbox';
    checkbox.checked = this.selectedIds.has(item.id);
    checkbox.disabled = this.pending || item.supported === false;
    checkbox.setAttribute('aria-label', `Select ${this.channelDisplayName(item)} for a runtime command`);
    checkbox.addEventListener('click', (event) => {
      event.stopPropagation();
      this.applyRowSelection(item, { toggle: true });
    });
    checkCell.append(checkbox);
    row.append(checkCell, this.textCell(item.system), this.textCell(item.site));
    const nameCell = this.element('td', 'channels-channel-name', this.channelDisplayName(item));
    if (failure) nameCell.append(this.element('span', 'channels-row-failure', failure));
    row.append(nameCell, this.textCell(this.frequencyText(item), 'channels-frequency-cell'),
      this.textCell(item.protocolLabel || item.protocol || 'Unknown'), this.stateCell(item));
    const orderCell = this.element('td', 'channels-order-cell');
    orderCell.innerHTML = this.autoStartMarkup(item, 'table');
    this.bindAutoStart(orderCell);
    row.append(orderCell);
    const action = this.element('td');
    const runtime = this.button(this.isRunning(item) ? 'Stop' : 'Start');
    runtime.dataset.runtimeId = item.id;
    runtime.disabled = this.pending || item.supported === false || this.isTransitioning(item) ||
      (this.detail?.id === item.id && this.dirty);
    runtime.addEventListener('click', () => this.runtime(item, this.isRunning(item) ? 'STOP' : 'START'));
    action.append(runtime);
    row.append(action);
    row.addEventListener('click', (event) => {
      if (event.target.closest('button, input, select, textarea, a')) return;
      const modified = event.ctrlKey || event.metaKey;
      this.applyRowSelection(item, { toggle: modified, range: event.shiftKey, additive: modified });
      if (!modified && !event.shiftKey) this.openChannel(item.id);
    });
    row.addEventListener('keydown', (event) => {
      if (event.target !== row || !['Enter', ' '].includes(event.key)) return;
      event.preventDefault();
      if (event.key === ' ') this.applyRowSelection(item, { toggle: true });
      else {
        this.applyRowSelection(item);
        this.openChannel(item.id);
      }
    });
    return row;
  }

  applyRowSelection(item, behavior = {}) {
    const maximum = Number(this.options.maximumSelectionSize) || this.limit;
    const selection = window.SdrtrunkTableSelection?.apply({
      items: this.items,
      keyOf: (candidate) => candidate.id,
      selectable: (candidate) => candidate.supported !== false,
      selectedKeys: this.selectedIds.keys(),
      targetKey: item.id,
      anchorKey: this.selectionAnchorId,
      maximum,
      toggle: behavior.toggle,
      range: behavior.range,
      additive: behavior.additive
    });
    if (!selection) return;
    this.selectedIds.clear();
    selection.selectedKeys.forEach((id) => {
      const selected = this.items.find((candidate) => String(candidate.id) === id);
      if (selected) this.selectedIds.set(selected.id, selected.revision);
    });
    this.selectionAnchorId = selection.anchorKey;
    if (selection.limitReached) this.toast(`At most ${maximum} channels can be selected at once.`, true);
    this.renderTable();
  }

  textCell(value, className = '') {
    const cell = this.element('td', className, value ?? '');
    if (!className) cell.classList.add('channels-cell-ellipsis');
    cell.title = String(value ?? '');
    return cell;
  }

  stateCell(item) {
    const cell = this.element('td');
    const state = String(item.state || 'Stopped');
    const badge = this.element('span', `channels-status-badge ${this.stateClass(state)}`, state);
    cell.append(badge);
    return cell;
  }

  stateClass(state) {
    const normalized = String(state).toLowerCase();
    if (normalized.includes('running') || normalized.includes('playing')) return 'running';
    if (normalized.includes('start') || normalized.includes('stop')) return 'pending';
    if (normalized.includes('error') || normalized.includes('unsupported')) return 'failed';
    return '';
  }

  channelDisplayName(item) {
    return String(item?.name || '(unnamed channel)');
  }

  frequencyText(item) {
    const frequencies = Array.isArray(item.frequenciesHz) ? item.frequenciesHz : [];
    if (!frequencies.length && Number(item.primaryFrequencyHz) > 0) frequencies.push(item.primaryFrequencyHz);
    return frequencies.filter((value) => Number(value) > 0)
      .map((value) => `${(Number(value) / 1_000_000).toFixed(6).replace(/0+$/, '').replace(/\.$/, '')} MHz`)
      .join(', ');
  }

  updateSortIndicators() {
    this.table.querySelectorAll('[data-sort]').forEach((button) => {
      const active = button.dataset.sort === this.sort;
      const indicator = button.querySelector('.channels-sort-indicator');
      indicator.textContent = active ? (this.direction.toLowerCase().startsWith('desc') ? '↓' : '↑') : '↕';
      button.closest('th')?.setAttribute('aria-sort', active ?
        (this.direction.toLowerCase().startsWith('desc') ? 'descending' : 'ascending') : 'none');
    });
  }

  changeSort(key) {
    if (this.pending) return;
    if (this.sort === key) this.direction = this.direction.toLowerCase().startsWith('asc') ? 'descending' : 'ascending';
    else {
      this.sort = key;
      this.direction = key === 'frequency' ? 'descending' : 'ascending';
    }
    this.offset = 0;
    this.clearSelection();
    this.loadList();
  }

  changePage(direction) {
    if (this.pending) return;
    const next = Math.max(0, this.offset + direction * this.limit);
    if (next === this.offset || next >= Math.max(this.total, 1)) return;
    this.offset = next;
    this.clearSelection();
    this.loadList();
  }

  selectCurrentPage(checked) {
    const maximum = Number(this.options.maximumSelectionSize) || this.limit;
    this.items.forEach((item) => {
      if (item.supported === false) return;
      if (checked) {
        if (this.selectedIds.has(item.id) || this.selectedIds.size < maximum) {
          this.selectedIds.set(item.id, item.revision);
        }
      } else this.selectedIds.delete(item.id);
    });
    if (checked && this.items.filter((item) => item.supported !== false).length > maximum) {
      this.toast(`At most ${maximum} channels can be selected at once.`, true);
    }
    this.renderTable();
  }

  clearSelection() {
    this.selectedIds.clear();
    this.selectionAnchorId = null;
    this.updateSelectionControls();
    this.tableBody?.querySelectorAll('input[type="checkbox"]').forEach((input) => { input.checked = false; });
  }

  updateSelectionControls() {
    const count = this.selectedIds.size;
    this.selectionCount.textContent = count ? `${count} ${count === 1 ? 'channel' : 'channels'} selected` :
      'No channels selected';
    this.startSelected.disabled = this.pending || this.dirty || count === 0;
    this.stopSelected.disabled = this.pending || this.dirty || count === 0;
    this.clearSelected.disabled = this.pending || count === 0;
    const selectable = this.items.filter((item) => item.supported !== false);
    const selectedOnPage = selectable.filter((item) => this.selectedIds.has(item.id)).length;
    this.selectPage.checked = selectable.length > 0 && selectedOnPage === selectable.length;
    this.selectPage.indeterminate = selectedOnPage > 0 && selectedOnPage < selectable.length;
    this.selectPage.disabled = this.pending || selectable.length === 0;
  }

  reconcileSelectedRevisions() {
    this.items.forEach((item) => {
      if (this.selectedIds.has(item.id)) this.selectedIds.set(item.id, item.revision);
    });
  }

  reconcileOpenDetail() {
    if (!this.detail?.id) return false;
    const item = this.items.find((candidate) => candidate.id === this.detail.id);
    if (!item) return false;
    if (item.revision !== this.detail.revision) {
      if (!this.dirty) return true;
      this.revisionConflict = true;
      const notice = this.editorPanel.querySelector('[data-editor-notice]');
      if (notice) {
        notice.hidden = false;
        notice.className = 'channels-editor-notice failed';
        notice.textContent = 'This saved channel changed while you were editing. Your staged values remain here; saving will require resolving the version conflict.';
      }
      return false;
    }
    this.detail.autoStartOrder = item.autoStartOrder;
    this.detail.state = item.state;
    if (!this.dirty) this.originalDetail = this.clone(this.detail);
    this.refreshEditorChrome();
    return false;
  }

  async openChannel(id) {
    if (this.pending || !id || this.detail?.id === id) return;
    if (this.dirty && !window.confirm('Discard the unsaved channel changes?')) return;
    this.dirty = false;
    this.guidUnlocked = false;
    this.detailController?.abort();
    const controller = new AbortController();
    this.detailController = controller;
    this.renderEditorLoading();
    try {
      const result = await this.api(`/api/v1/configuration/channels/${encodeURIComponent(id)}`, {}, controller);
      if (this.closed || controller !== this.detailController) return;
      this.detail = result.detail || result;
      this.originalDetail = this.clone(this.detail);
      this.revisionConflict = false;
      this.renderEditor();
      this.renderTable();
      window.requestAnimationFrame(() => this.editorPanel.querySelector('h2')?.focus());
    } catch (error) {
      if (error.name === 'AbortError' || this.closed) return;
      this.renderEditorError('Channel could not be opened', this.errorMessage(error));
    } finally {
      if (this.detailController === controller) this.detailController = null;
    }
  }

  async createChannel(protocol) {
    if (this.pending) return;
    if (this.dirty && !window.confirm('Discard the unsaved channel changes?')) return;
    this.dirty = false;
    this.detailController?.abort();
    const controller = new AbortController();
    this.detailController = controller;
    this.renderEditorLoading('Loading application defaults…');
    try {
      const result = await this.api(`/api/v1/configuration/channels/templates/${encodeURIComponent(protocol)}`,
        {}, controller);
      if (this.closed || controller !== this.detailController) return;
      this.detail = result.detail || result;
      this.detail.isNew = true;
      this.originalDetail = this.clone(this.detail);
      this.revisionConflict = false;
      this.dirty = true;
      this.guidUnlocked = false;
      this.renderEditor();
      window.requestAnimationFrame(() => this.editorPanel.querySelector('input[name="system"]')?.focus());
    } catch (error) {
      if (error.name === 'AbortError' || this.closed) return;
      this.renderEditorError('New channel could not be prepared', this.errorMessage(error));
    } finally {
      if (this.detailController === controller) this.detailController = null;
    }
  }

  renderEditorEmpty() {
    const empty = this.element('div', 'channels-editor-state');
    empty.append(this.element('strong', '', 'No channel selected'),
      this.element('span', '', 'Select a channel name from the table to open its editor.'));
    this.editorPanel.replaceChildren(empty);
  }

  renderEditorLoading(message = 'Loading the saved channel…') {
    const loading = this.element('div', 'channels-editor-state');
    loading.append(this.element('strong', '', 'Opening channel'), this.element('span', '', message));
    this.editorPanel.replaceChildren(loading);
  }

  renderEditorError(title, message) {
    const state = this.element('div', 'channels-editor-state failed');
    state.append(this.element('strong', '', title), this.element('span', '', message));
    this.editorPanel.replaceChildren(state);
  }

  renderEditor() {
    const channel = this.detail;
    if (!channel) return this.renderEditorEmpty();
    if (channel.supported === false) return this.renderUnsupportedEditor(channel);
    const header = this.editorHeader(channel);
    const actions = this.editorActions(channel);
    const form = this.element('form', 'channels-editor-body');
    form.noValidate = true;
    form.innerHTML = [
      '<div class="channels-editor-notice" data-editor-notice role="status" hidden></div>',
      this.jmbeNotice(channel),
      this.identitySection(channel), this.sourceSection(channel), this.decoderSection(channel),
      this.auxiliarySection(channel), this.outputsSections(channel), this.editorFooter(channel)
    ].join('');
    form.addEventListener('input', (event) => this.onFormChanged(event));
    form.addEventListener('change', (event) => this.onFormChanged(event));
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      this.saveChannel();
    });
    this.editorPanel.replaceChildren(header, actions, form);
    this.bindEditor(form);
    this.updateConditionalFields();
    this.renumberFrequencyRows();
    this.updateMapSlots();
    this.setEditorPending(this.pending || this.isTransitioning(channel));
  }

  requiresJmbe(channel) {
    return ['P25_CONVENTIONAL', 'P25_PHASE1', 'P25_PHASE2', 'DMR', 'NXDN']
      .includes(this.protocolKind(channel?.protocol));
  }

  jmbeNotice(channel) {
    if (this.options.jmbeConfigured !== false || !this.requiresJmbe(channel)) return '';
    return '<div class="channels-inline-notice failed"><strong>JMBE voice library unavailable.</strong>' +
      '<span>The control and data decoder can run, but decoded voice is unavailable until JMBE is configured in the local application’s Voice decoders and keys settings.</span></div>';
  }

  renderUnsupportedEditor(channel) {
    const header = this.editorHeader(channel);
    const actions = this.element('div', 'channels-editor-actions');
    const exportButton = this.button('Export preserved configuration');
    const deleteButton = this.button('Delete channel', 'button-danger');
    exportButton.addEventListener('click', () => this.exportChannel());
    deleteButton.addEventListener('click', () => this.deleteChannel());
    actions.append(exportButton, deleteButton);
    const body = this.element('div', 'channels-editor-body');
    const notice = this.element('div', 'channels-inline-notice failed');
    notice.append(this.element('strong', '', 'Unsupported imported configuration.'),
      this.element('span', '', 'It can be inspected, exported, or deleted without converting its decoder or source.'));
    const facts = this.element('dl', 'channels-fact-grid');
    [['System', channel.system], ['Site', channel.site], ['Decoder', channel.protocol],
      ['Source', channel.source?.kind || 'Unknown']].forEach(([label, value]) => {
      const group = this.element('div');
      group.append(this.element('dt', '', label), this.element('dd', '', value || '—'));
      facts.append(group);
    });
    body.append(notice, facts);
    this.editorPanel.replaceChildren(header, actions, body);
  }

  editorHeader(channel) {
    const header = this.element('header', 'channels-editor-head');
    const row = this.element('div', 'channels-editor-title-row');
    const heading = this.element('div');
    const title = this.element('h2', '', channel.name || (channel.isNew ? 'New channel' : '(unnamed channel)'));
    title.tabIndex = -1;
    heading.append(title, this.element('p', 'channels-editor-subtitle',
      `${channel.system || 'No system'} / ${channel.site || 'No site'} · ${channel.protocolLabel || channel.protocol || 'Unknown decoder'}`));
    const badges = this.element('div', 'channels-editor-badges');
    badges.append(this.element('span', `channels-status-badge ${this.stateClass(channel.state)}`,
      channel.state || (channel.isNew ? 'New' : 'Stopped')));
    this.dirtyBadge = this.element('span', `channels-status-badge ${this.dirty ? 'dirty' : 'saved'}`,
      this.dirty ? 'Unsaved changes' : 'Saved');
    badges.append(this.dirtyBadge);
    row.append(heading, badges);
    header.append(row);
    return header;
  }

  editorActions(channel) {
    const actions = this.element('div', 'channels-editor-actions');
    const runtime = this.button(this.isRunning(channel) ? 'Stop channel' : 'Start channel');
    runtime.dataset.editorRuntime = 'true';
    runtime.disabled = this.dirty || channel.isNew || this.isTransitioning(channel);
    runtime.title = this.dirty ? 'Save or reset changes before a runtime command.' : '';
    runtime.addEventListener('click', () => this.runtime(channel, this.isRunning(channel) ? 'STOP' : 'START'));
    const clone = this.button('Clone channel');
    clone.disabled = channel.isNew || this.isTransitioning(channel);
    clone.addEventListener('click', () => this.cloneChannel());
    const remove = this.button(channel.isNew ? 'Discard new channel' : 'Delete channel', 'button-danger');
    remove.disabled = this.isTransitioning(channel);
    remove.addEventListener('click', () => this.deleteChannel());
    const hint = this.element('span', 'channels-action-hint',
      'Clone and Delete always affect this one open channel.');
    actions.append(runtime, clone, remove, hint);
    return actions;
  }

  section(title, help, body, open = false) {
    return `<details class="channels-form-section"${open ? ' open' : ''}><summary>${this.escape(title)}</summary>` +
      `<div class="channels-section-body"><div class="channels-about-label"><span>About ${this.escape(title)}</span>` +
      this.helpMarkup(title, help) + `</div>${body}</div></details>`;
  }

  helpMarkup(title, copy) {
    return `<button class="channels-help-button" type="button" aria-label="More information about ${this.escape(title)}" ` +
      `aria-expanded="false" aria-controls="channels-field-help" data-help-title="${this.escape(title)}" ` +
      `data-help="${this.escape(copy)}">i</button>`;
  }

  labelMarkup(label, help, id = '') {
    const target = id ? `<label for="${this.escape(id)}">${this.escape(label)}</label>` : `<span>${this.escape(label)}</span>`;
    return `<div class="channels-field-label">${target}${this.helpMarkup(label, help)}</div>`;
  }

  errorMarkup(id) {
    return `<span class="channels-field-error" data-error-for="${this.escape(id)}" hidden></span>`;
  }

  selectMarkup(id, name, selected, options, help, label) {
    const entries = [...options];
    if (selected != null && selected !== '' && !entries.some((entry) => String(entry.value) === String(selected))) {
      entries.push({ value: selected, label: `${selected} (saved value)` });
    }
    return `<div class="channels-field">${this.labelMarkup(label, help, id)}<select id="${id}" name="${name}">` +
      entries.map((entry) => `<option value="${this.escape(entry.value)}"${String(entry.value) === String(selected) ? ' selected' : ''}>${this.escape(entry.label)}</option>`).join('') +
      '</select></div>';
  }

  value(object, keys, fallback = null) {
    for (const key of keys) {
      if (object && Object.prototype.hasOwnProperty.call(object, key)) return object[key];
    }
    return fallback;
  }

  key(object, keys) {
    return keys.find((key) => object && Object.prototype.hasOwnProperty.call(object, key)) || keys[0];
  }

  identitySection(channel) {
    const aliases = this.firstOption(['aliasLists', 'aliases'], [])
      .filter((entry) => String(entry.value).toLowerCase() !== '(no alias list)');
    const aliasValue = String(channel.aliasList ?? '').toLowerCase() === '(no alias list)' ? '' :
      (channel.aliasList ?? '');
    if (!aliases.some((entry) => String(entry.value) === String(aliasValue))) {
      aliases.unshift({ value: aliasValue, label: aliasValue ? `${aliasValue} (saved value)` : 'No alias list' });
    }
    const aliasOptions = aliases.map((entry) => `<option value="${this.escape(entry.value)}"${String(entry.value) === String(aliasValue) ? ' selected' : ''}>${this.escape(entry.label)}</option>`).join('');
    const body = `<div class="channels-form-grid">
      <div class="channels-field">${this.labelMarkup('System', 'The radio system this channel belongs to. Search uses this name; it does not choose the decoder.', 'channel-system')}
        <input id="channel-system" name="system" type="text" maxlength="160" value="${this.escape(channel.system)}">${this.errorMarkup('channel-system')}</div>
      <div class="channels-field">${this.labelMarkup('Site', 'The site or operating area within the system. This is a descriptive name, not a numeric P25 System value.', 'channel-site')}
        <input id="channel-site" name="site" type="text" maxlength="160" value="${this.escape(channel.site)}">${this.errorMarkup('channel-site')}</div>
      <div class="channels-field">${this.labelMarkup('Name', 'The short channel name shown in channel lists and runtime status.', 'channel-name')}
        <input id="channel-name" name="name" type="text" maxlength="160" value="${this.escape(channel.name)}">${this.errorMarkup('channel-name')}</div>
      <div class="channels-field">${this.labelMarkup('Alias List', 'Select an existing Alias List for talkgroup and radio matching. Alias Lists are managed on the Aliases page.', 'channel-alias-list')}
        <select id="channel-alias-list" name="aliasList">${aliasOptions}</select></div>
      <div class="channels-field">${this.labelMarkup('Automatic start order', 'Plus adds the channel at position 1. The left down-chevron moves it later. The right up-chevron moves it earlier. At position 1, minus removes it from automatic start.')}
        <div data-editor-auto-start>${this.autoStartMarkup(channel, 'editor')}</div><span class="channels-field-hint">A blank number means this channel does not start automatically.</span></div>
      <div class="channels-field full">${this.labelMarkup('Site GUID', 'This permanent identifier links the site to saved statistics and web pages. Normal edits preserve it; a clone receives a new GUID.', 'channel-guid')}
        <div class="channels-guid-row"><input id="channel-guid" name="guid" type="text" required value="${this.escape(channel.guid)}"${this.guidUnlocked ? '' : ' readonly'}>
        <button type="button" data-action="unlock-guid"${this.isRunning(channel) ? ' disabled' : ''}>${this.guidUnlocked ? 'Unlocked' : 'Unlock GUID'}</button></div>${this.errorMarkup('channel-guid')}
        <span class="channels-field-hint">${this.isRunning(channel) ? 'Stop this channel before changing its GUID.' : 'Read-only until the warning is accepted.'}</span></div>
    </div>`;
    return this.section('Identity', 'Names and the Alias List can change normally. The GUID requires a deliberate warning-based unlock.', body, true);
  }

  isMultiFrequency(channel = this.detail) {
    return ['P25_PHASE1', 'P25_PHASE2', 'DMR', 'NXDN'].includes(this.protocolKind(channel?.protocol));
  }

  sourceSection(channel) {
    const source = channel.source || {};
    const values = Array.isArray(source.frequenciesHz) ? source.frequenciesHz : [];
    const frequencies = values.length ? values : [null];
    const rows = frequencies.map((frequency, index) => this.frequencyRow(frequency, index, frequencies.length)).join('');
    const multi = this.isMultiFrequency(channel);
    const frequencyBody = multi ? `<div class="channels-field full">${this.labelMarkup('Control frequencies', 'SDRTrunk tries these frequencies in the displayed order. Use the chevrons to change that order.')}
      <div class="channels-frequency-list" data-frequency-list>${rows}</div><button class="channels-add-row" type="button" data-action="add-frequency">Add frequency</button>
      <span class="channels-field-hint">At least one frequency. Enter only the number; MHz is fixed.</span></div>` :
      `<div class="channels-field">${this.labelMarkup('Frequency', 'The center frequency for this conventional channel. Enter only the number; MHz is fixed.', 'channel-frequency-0')}
      <div class="channels-input-with-unit"><input id="channel-frequency-0" name="frequencies" type="number" min="1" max="9999.999999" step="0.000001" required value="${this.mhzValue(frequencies[0])}"><span>MHz</span></div>${this.errorMarkup('channel-frequency-0')}</div>`;
    const tunerValue = source.preferredTuner ?? '';
    const tuners = this.firstOption(['tuners', 'preferredTuners'], [{ value: '', label: 'Automatic receiver selection' }]);
    if (!tuners.some((entry) => String(entry.value) === String(tunerValue))) {
      tuners.push({ value: tunerValue, label: tunerValue ? `${tunerValue} (saved receiver)` : 'Automatic receiver selection' });
    }
    const tunerOptions = tuners.map((entry) => `<option value="${this.escape(entry.value)}"${String(entry.value) === String(tunerValue) ? ' selected' : ''}>${this.escape(entry.label)}</option>`).join('');
    let rotation = '';
    if (multi) {
      const kind = this.protocolKind(channel.protocol);
      const minimum = ['P25_PHASE2', 'DMR', 'NXDN'].includes(kind) ? 200 : 400;
      const delay = source.rotationDelayMs ?? 500;
      rotation = `<div class="channels-field">${this.labelMarkup('Frequency rotation delay', 'How long SDRTrunk listens before trying the next control frequency.', 'channel-rotation-delay')}
        <div class="channels-input-with-unit"><input id="channel-rotation-delay" name="rotationDelayMs" type="number" min="${minimum}" max="2000" step="1" required value="${this.escape(delay ?? '')}"><span>ms</span></div>
        ${this.errorMarkup('channel-rotation-delay')}<span class="channels-field-hint">Allowed: ${minimum}–2000 ms</span></div>`;
    }
    const body = `<div class="channels-form-grid">${frequencyBody}
      <div class="channels-field">${this.labelMarkup('Preferred tuner', 'Optionally prefer one receiver. An unavailable saved receiver remains selected until you deliberately change it.', 'channel-preferred-tuner')}
        <select id="channel-preferred-tuner" name="preferredTuner">${tunerOptions}</select></div>${rotation}</div>`;
    return this.section('Source', 'Source settings contain the channel frequency or ordered control frequencies and an optional receiver preference.', body, true);
  }

  frequencyRow(frequencyHz, index, total) {
    return `<div class="channels-frequency-row" data-frequency-row><div><div class="channels-input-with-unit">
      <input id="channel-frequency-${index}" name="frequencies" type="number" min="1" max="9999.999999" step="0.000001" required aria-label="Control frequency ${index + 1}" value="${this.mhzValue(frequencyHz)}"><span>MHz</span></div>
      ${this.errorMarkup(`channel-frequency-${index}`)}</div><div class="channels-row-actions">
      <button class="channels-icon-button" type="button" data-action="frequency-up" aria-label="Move control frequency ${index + 1} up"${index === 0 ? ' disabled' : ''}><span class="channels-chevron up"></span></button>
      <button class="channels-icon-button" type="button" data-action="frequency-down" aria-label="Move control frequency ${index + 1} down"${index === total - 1 ? ' disabled' : ''}><span class="channels-chevron down"></span></button>
      <button type="button" data-action="frequency-remove"${total <= 1 ? ' disabled' : ''}>Remove</button></div></div>`;
  }

  mhzValue(hertz) {
    if (hertz == null || !Number.isFinite(Number(hertz)) || Number(hertz) <= 0) return '';
    return (Number(hertz) / 1_000_000).toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
  }

  booleanField(id, name, label, help, checked) {
    return `<div class="channels-field channels-boolean-field"><div class="channels-checkbox-field-line">` +
      `<label class="channels-checkbox-row" for="${this.escape(id)}"><input id="${this.escape(id)}" ` +
      `name="${this.escape(name)}" type="checkbox"${checked ? ' checked' : ''}>${this.escape(label)}</label>` +
      `${this.helpMarkup(label, help)}</div></div>`;
  }

  maximumTraffic(decoder) {
    const value = this.value(decoder, ['maximumTrafficChannels', 'maxTrafficChannels', 'maxTraffic', 'trafficChannelPoolSize'], '');
    return `<div class="channels-field">${this.labelMarkup('Maximum traffic channels', 'Limits how many simultaneous traffic calls this configured system may allocate.', 'channel-max-traffic')}
      <input id="channel-max-traffic" name="maximumTrafficChannels" type="number" min="0" max="50" step="1" required value="${this.escape(value)}">${this.errorMarkup('channel-max-traffic')}<span class="channels-field-hint">Allowed: 0–50</span></div>`;
  }

  decoderSection(channel) {
    const decoder = channel.decoder || {};
    const kind = this.protocolKind(channel.protocol);
    let body = '';
    if (kind === 'P25_PHASE1') body = this.p25PhaseOne(decoder);
    else if (kind === 'P25_PHASE2') body = this.p25PhaseTwo(decoder);
    else if (kind === 'DMR') body = this.dmrDecoder(decoder);
    else if (kind === 'NXDN') body = this.nxdnDecoder(decoder);
    else if (kind === 'NBFM') body = this.nbfmDecoder(decoder);
    else body = this.p25Conventional(decoder);
    return this.section(`Decoder — ${channel.protocolLabel || channel.protocol || kind}`, 'Only settings used by this decoder are shown. Values come from the saved channel or the application template.', body, true);
  }

  p25Conventional(decoder) {
    const modulation = this.value(decoder, ['modulation'], 'C4FM');
    return `<div class="channels-form-grid"><div class="channels-field">${this.labelMarkup('Modulation', 'P25 Conventional uses C4FM modulation.', 'channel-modulation')}
      <input id="channel-modulation" type="text" value="${this.escape(modulation)}" readonly></div></div>`;
  }

  p25PhaseOne(decoder) {
    const modulation = this.value(decoder, ['modulation'], '');
    const modulations = ['C4FM', 'LSM'].map((value) => ({ value, label: value }));
    return `<div class="channels-form-grid">${this.selectMarkup('channel-modulation', 'modulation', modulation, modulations,
      'Use C4FM for repeaters and non-simulcast systems. Use LSM for simulcast systems.', 'Modulation')}${this.maximumTraffic(decoder)}
      ${this.booleanField('channel-ignore-data', 'ignoreDataCalls', 'Ignore data calls', 'Prevents data-only grants from allocating traffic-channel resources.', this.value(decoder, ['ignoreDataCalls', 'ignoreData'], false))}
      ${this.booleanField('channel-learn-controls', 'learnAnnouncedControlChannels', 'Learn announced control channels', 'Adds stable current and alternate control frequencies announced by the system.', this.value(decoder, ['learnAnnouncedControlChannels'], false))}</div>`;
  }

  p25PhaseTwo(decoder) {
    const automatic = this.value(decoder, ['autoDetectScrambleParameters', 'autoDetectScramble'], true) !== false;
    const wacn = this.value(decoder, ['wacn'], decoder.scrambleParameters?.wacn ?? '');
    const system = this.value(decoder, ['p25System'], '');
    const nac = this.value(decoder, ['nac'], decoder.scrambleParameters?.nac ?? '');
    return `<div class="channels-form-grid">${this.maximumTraffic(decoder)}
      ${this.booleanField('channel-ignore-data', 'ignoreDataCalls', 'Ignore data calls', 'Prevents data-only grants from allocating traffic-channel resources.', this.value(decoder, ['ignoreDataCalls', 'ignoreData'], false))}
      ${this.booleanField('channel-learn-controls', 'learnAnnouncedControlChannels', 'Learn announced control channels', 'Adds stable current and alternate control frequencies announced by the system.', this.value(decoder, ['learnAnnouncedControlChannels'], false))}</div>
      <details class="channels-form-section nested"><summary>Advanced traffic-channel parameters</summary><div class="channels-section-body">
      <div class="channels-about-label"><span>About advanced parameters</span>${this.helpMarkup('Advanced traffic-channel parameters', 'WACN, System, and NAC identify the P25 network. Normal systems should detect these values automatically.')}</div>
      <div class="channels-form-grid">${this.booleanField('channel-auto-scramble', 'autoDetectScrambleParameters', 'Automatically detect parameters', 'Keep enabled for normal trunked systems. Disable only when manual traffic-channel parameters are required.', automatic)}<div></div>
      <div class="channels-field">${this.labelMarkup('WACN', 'Wide Area Communications Network number, normally detected automatically.', 'channel-wacn')}<input id="channel-wacn" name="wacn" type="text" maxlength="5" pattern="[0-9A-Fa-f]{0,5}" value="${this.escape(wacn)}"><span class="channels-field-hint">0–FFFFF hexadecimal</span></div>
      <div class="channels-field">${this.labelMarkup('P25 System', 'Numeric P25 system identifier. This is separate from the descriptive System name under Identity.', 'channel-p25-system')}<input id="channel-p25-system" name="p25System" type="text" maxlength="3" pattern="[0-9A-Fa-f]{0,3}" value="${this.escape(system)}"><span class="channels-field-hint">0–FFF hexadecimal</span></div>
      <div class="channels-field">${this.labelMarkup('NAC', 'Network Access Code used by P25 traffic channels.', 'channel-nac')}<input id="channel-nac" name="nac" type="text" maxlength="3" pattern="[0-9A-Fa-f]{0,3}" value="${this.escape(nac)}"><span class="channels-field-hint">0–FFF hexadecimal</span></div>
      </div></div></details>`;
  }

  dmrDecoder(decoder) {
    return `<div class="channels-form-grid">${this.maximumTraffic(decoder)}
      ${this.booleanField('channel-ignore-data', 'ignoreDataCalls', 'Ignore data calls', 'Prevents data-only calls from allocating traffic resources.', this.value(decoder, ['ignoreDataCalls', 'ignoreData'], false))}
      ${this.booleanField('channel-ignore-crc', 'ignoreCrcChecksums', 'Ignore CRC checksums (RAS)', 'Use only for a system that applies Restricted Access to System signaling.', this.value(decoder, ['ignoreCrcChecksums', 'ignoreCrc'], false))}
      ${this.booleanField('channel-compressed-talkgroups', 'compressedTalkgroups', 'Use compressed talkgroups', 'Hytera Tier III systems may transmit compressed talkgroup identifiers.', this.value(decoder, ['compressedTalkgroups'], false))}</div>${this.mapEditor(decoder, 'DMR')}`;
  }

  nxdnDecoder(decoder) {
    const mode = this.value(decoder, ['transmissionMode'], '');
    const encoding = this.value(decoder, ['encoding'], '');
    const modes = [['M4800', '4800'], ['M9600', '9600'], ['TYPE_D', 'Type D']].map(([value, label]) => ({ value, label }));
    const encodings = [['UTF8', 'UTF-8'], ['BIG5', 'BIG5']].map(([value, label]) => ({ value, label }));
    return `<div class="channels-form-grid">${this.selectMarkup('channel-nxdn-mode', 'transmissionMode', mode, modes, 'Choose the air-interface mode used by this system.', 'NXDN mode')}
      ${this.selectMarkup('channel-nxdn-encoding', 'encoding', encoding, encodings, 'Choose the character encoding used for talker aliases.', 'Talker alias encoding')}
      ${this.maximumTraffic(decoder)}
      ${this.booleanField('channel-ignore-data', 'ignoreDataCalls', 'Ignore data calls', 'Prevents data-only calls from allocating traffic resources.', this.value(decoder, ['ignoreDataCalls', 'ignoreData'], false))}
      ${this.booleanField('channel-ignore-encrypted', 'ignoreEncryptedCalls', 'Ignore encrypted calls', 'Prevents encrypted voice grants from allocating traffic resources.', this.value(decoder, ['ignoreEncryptedCalls', 'ignoreEncrypted'], false))}</div>${this.mapEditor(decoder, 'NXDN')}`;
  }

  mapEntries(decoder) {
    const keys = ['frequencyMap', 'channelMap', 'lcnMap', 'nxdnMap'];
    for (const key of keys) if (Array.isArray(decoder[key])) return decoder[key];
    return [];
  }

  mapEditor(decoder, kind) {
    const entries = this.mapEntries(decoder);
    const rows = entries.map((entry, index) => this.mapRow(entry, index, kind)).join('');
    const title = kind === 'DMR' ? 'LCN frequency map' : 'NXDN channel map';
    const number = kind === 'DMR' ? 'LCN' : 'Channel number';
    return `<details class="channels-form-section nested" open><summary>${title}</summary><div class="channels-section-body">
      <div class="channels-about-label"><span>About ${title}</span>${this.helpMarkup(title, kind === 'DMR' ?
        'Matches DMR logical channel numbers to downlink and optional saved uplink frequencies. Timeslot identifiers are calculated from the LCN.' :
        'Matches NXDN channel numbers to downlink and optional saved uplink frequencies. Channel numbers may be 1 through 2048.')}</div>
      <div class="channels-map-scroll"><table class="channels-map-table"><thead><tr><th>${number}</th><th>Downlink MHz</th><th>Uplink MHz</th>${kind === 'DMR' ? '<th>TS1</th><th>TS2</th>' : ''}<th>Action</th></tr></thead>
      <tbody data-map-body data-map-kind="${kind}">${rows}</tbody></table></div><button type="button" class="channels-add-row" data-action="map-add">Add ${kind === 'DMR' ? 'LCN' : 'channel'} row</button></div></details>`;
  }

  mapRow(entry, index, kind) {
    const number = this.value(entry, ['number', 'channelNumber', 'lcn'], '');
    const downlink = this.value(entry, ['downlinkFrequencyHz', 'downlinkHz', 'downlink'], null);
    const uplink = this.value(entry, ['uplinkFrequencyHz', 'uplinkHz', 'uplink'], null);
    const displayNumber = Number(number);
    const hasNumber = number !== '' && Number.isFinite(displayNumber) && displayNumber > 0;
    const slots = kind === 'DMR' ? `<td data-ts1>${hasNumber ? displayNumber * 2 - 1 : '—'}</td>` +
      `<td data-ts2>${hasNumber ? displayNumber * 2 : '—'}</td>` : '';
    const maximum = kind === 'NXDN' ? 2048 : 4095;
    return `<tr data-map-row><td><input name="mapNumber" type="number" min="1" max="${maximum}" step="1" required value="${this.escape(number)}" aria-label="${kind === 'DMR' ? 'LCN' : 'NXDN channel'} number for map row ${index + 1}"></td>
      <td><input name="mapDownlink" type="number" min="1" max="9999.999999" step="0.000001" required value="${this.mhzValue(downlink)}" aria-label="Downlink frequency for map row ${index + 1}"></td>
      <td><input name="mapUplink" type="number" min="1" max="9999.999999" step="0.000001" value="${this.mhzValue(uplink)}" aria-label="Uplink frequency for map row ${index + 1}"></td>${slots}
      <td><button type="button" data-action="map-remove">Remove</button></td></tr>`;
  }

  nbfmDecoder(decoder) {
    const bandwidth = this.value(decoder, ['bandwidth'], '');
    const deemphasis = this.value(decoder, ['deemphasis'], '');
    const bandwidths = [['BW_7_5', '7.5 kHz'], ['BW_12_5', '12.5 kHz'], ['BW_25_0', '25 kHz']]
      .map(([value, label]) => ({ value, label }));
    const deemphasisOptions = [['NONE', 'None'], ['US_750US', '750 µs'], ['CEPT_530US', '530 µs']]
      .map(([value, label]) => ({ value, label }));
    const number = (name, label, help, min, max, step, value, unit = '') => `<div class="channels-field">${this.labelMarkup(label, help, `channel-${name}`)}<div class="channels-input-with-unit"><input id="channel-${name}" name="${name}" type="number" min="${min}" max="${max}" step="${step}" required value="${this.escape(value ?? '')}"><span>${unit}</span></div>${this.errorMarkup(`channel-${name}`)}</div>`;
    const voice = this.value(decoder, ['voiceEnhancePercent'], 0);
    return `<div class="channels-form-grid">
      ${this.selectMarkup('channel-bandwidth', 'bandwidth', bandwidth, bandwidths, 'Choose the occupied NBFM channel width.', 'Channel bandwidth')}
      ${number('talkgroup', 'Talkgroup to assign', 'Assigns a matchable talkgroup number to this analog channel.', 1, 65535, 1, this.value(decoder, ['talkgroup'], ''))}
      ${this.selectMarkup('channel-deemphasis', 'deemphasis', deemphasis, deemphasisOptions, 'Applies audio de-emphasis before recording or streaming.', 'De-emphasis')}
      ${number('outputGain', 'Output gain', 'Scales decoded audio before recording or streaming.', .25, 4, .05, this.value(decoder, ['outputGain'], ''))}
      ${this.booleanField('channel-high-pass', 'highPassEnabled', 'High-pass filter', 'Reduces low-frequency rumble in decoded audio.', this.value(decoder, ['highPassEnabled'], false))}
      ${this.booleanField('channel-low-pass', 'lowPassEnabled', 'Low-pass filter', 'Reduces audio above the selected cutoff.', this.value(decoder, ['lowPassEnabled'], false))}
      ${number('lowPassCutoffHz', 'Low-pass cutoff', 'Sets the upper audio cutoff when the low-pass filter is enabled.', 2000, 4000, 10, this.value(decoder, ['lowPassCutoffHz', 'lowPassCutoff'], ''), 'Hz')}
      <div class="channels-field">${this.labelMarkup('Voice enhance', 'Applies bounded speech enhancement to decoded audio.', 'channel-voice-enhance')}<div class="channels-range-row"><input id="channel-voice-enhance" name="voiceEnhancePercent" type="range" min="0" max="100" step="1" value="${this.escape(voice)}"><output data-voice-output>${this.escape(voice)}%</output></div></div>
      ${this.booleanField('channel-squelch-trim', 'squelchTrimEnabled', 'Squelch trim', 'Enables bounded head and tail audio trim.', this.value(decoder, ['squelchTrimEnabled'], false))}
      ${number('tailTrimMs', 'Tail trim', 'Removes up to 300 ms from the end of a call.', 0, 300, 1, this.value(decoder, ['tailTrimMs', 'tailTrim'], ''), 'ms')}
      ${number('headTrimMs', 'Head trim', 'Removes up to 150 ms from the beginning of a call.', 0, 150, 1, this.value(decoder, ['headTrimMs', 'headTrim'], ''), 'ms')}
      ${number('bassBoostDb', 'Bass boost', 'Adds up to 12 dB of low-frequency emphasis.', 0, 12, .5, this.value(decoder, ['bassBoostDb', 'bassBoost'], ''), 'dB')}
    </div>`;
  }

  auxiliarySection(channel) {
    if (this.protocolKind(channel.protocol) !== 'NBFM') return '';
    let choices = [
      { value: 'DCS', label: 'DCS' }, { value: 'FLEETSYNC2', label: 'Fleetsync II' },
      { value: 'LJ_1200', label: 'LJ1200' }, { value: 'MDC1200', label: 'MDC1200' },
      { value: 'TAIT_1200', label: 'Tait 1200' }
    ];
    const selected = Array.isArray(channel.auxiliaries) ? channel.auxiliaries : [];
    selected.forEach((value) => {
      if (!choices.some((entry) => String(entry.value) === String(value))) choices.push({ value, label: value });
    });
    const body = `<div class="channels-checkbox-grid">${choices.map((entry, index) =>
      `<label class="channels-checkbox-row"><input name="auxiliaries" type="checkbox" value="${this.escape(entry.value)}"${selected.some((value) => String(value) === String(entry.value)) ? ' checked' : ''}>${this.escape(entry.label)}</label>`).join('')}</div>`;
    return this.section('Auxiliary decoders', 'Optional signaling decoders used only by this NBFM channel.', body, false);
  }

  outputsSections(channel) {
    const kind = this.protocolKind(channel.protocol);
    const standardLogs = [{ value: 'CALL_EVENT', label: 'Call Events' },
      { value: 'DECODED_MESSAGE', label: 'Decoded Messages' }];
    const logs = ['P25_PHASE1', 'DMR', 'NXDN'].includes(kind) ? standardLogs.concat([
      { value: 'TRAFFIC_CALL_EVENT', label: 'Traffic Channel Call Events' },
      { value: 'TRAFFIC_DECODED_MESSAGE', label: 'Traffic Channel Decoded Messages' }
    ]) : standardLogs;
    const conventionalRecorders = [
      { value: 'BASEBAND', label: 'Baseband I/Q' },
      { value: 'DEMODULATED_BIT_STREAM', label: 'Demodulated Bitstream' },
      { value: 'MBE_CALL_SEQUENCE', label: 'MBE Audio CODEC Frames' }
    ];
    let recorders = kind === 'NBFM' ? [conventionalRecorders[0]] : conventionalRecorders;
    if (['P25_PHASE1', 'P25_PHASE2', 'DMR', 'NXDN'].includes(kind)) {
      recorders = recorders.concat([
        { value: 'TRAFFIC_BASEBAND', label: 'Traffic Channel Baseband I/Q' },
        { value: 'TRAFFIC_DEMODULATED_BIT_STREAM', label: 'Traffic Channel Demodulated Bitstream' },
        { value: 'TRAFFIC_MBE_CALL_SEQUENCE', label: 'Traffic Channel MBE Audio CODEC Frames' }
      ]);
    }
    return this.outputSection('Logging', 'logging', channel.logging, logs,
      'Choose decoder event and message logs for this channel. Logs follow the application rotation policy.') +
      this.outputSection('Recording', 'recording', channel.recording, recorders,
        'Choose technical decoder outputs. Call-audio recording remains controlled by Alias Record rules.');
  }

  outputSection(title, name, selectedValue, available, help) {
    const selected = Array.isArray(selectedValue) ? selectedValue : [];
    const choices = [...available];
    const body = choices.length ? `<div class="channels-checkbox-grid">${choices.map((entry) =>
      `<label class="channels-checkbox-row"><input name="${name}" type="checkbox" value="${this.escape(entry.value)}"${selected.some((value) => String(value) === String(entry.value)) ? ' checked' : ''}>${this.escape(entry.label)}</label>`).join('')}</div>` :
      '<p class="channels-field-hint">No choices are available for this decoder.</p>';
    return this.section(title, help, body, false);
  }

  editorFooter(channel) {
    return `<div class="channels-editor-footer"><span data-save-status role="status" aria-live="polite">${channel.isNew ? 'New channel has not been saved.' : 'No changes staged.'}</span>
      <div><button type="button" data-action="reset"${this.dirty ? '' : ' disabled'}>Reset</button><button type="submit" class="primary"${this.dirty ? '' : ' disabled'}>Save changes</button></div></div>`;
  }

  bindEditor(form) {
    form.querySelector('[data-action="unlock-guid"]')?.addEventListener('click', () => this.unlockGuid());
    form.querySelector('[data-action="reset"]')?.addEventListener('click', () => this.resetEditor());
    form.querySelector('[data-action="add-frequency"]')?.addEventListener('click', () => this.addFrequency());
    form.querySelectorAll('[data-frequency-row]').forEach((row) => this.bindFrequencyRow(row));
    form.querySelector('[data-action="map-add"]')?.addEventListener('click', () => this.addMapRow());
    form.querySelectorAll('[data-map-row]').forEach((row) => this.bindMapRow(row));
    this.bindAutoStart(form.querySelector('[data-editor-auto-start]'));
  }

  onFormChanged(event) {
    if (event.target.closest('[data-editor-auto-start]')) return;
    if (event.target.matches('[data-action]')) return;
    this.markDirty();
    const name = event.target.name;
    if (['autoDetectScrambleParameters', 'lowPassEnabled', 'squelchTrimEnabled',
      'voiceEnhancePercent'].includes(name)) this.updateConditionalFields();
    if (name === 'mapNumber') {
      const row = event.target.closest('[data-map-row]');
      const body = row?.closest('[data-map-body]');
      if (row && body) this.updateMapRow(row, [...body.children].indexOf(row), body.dataset.mapKind);
    }
  }

  markDirty() {
    if (!this.detail || this.pending) return;
    this.dirty = true;
    if (this.dirtyBadge) {
      this.dirtyBadge.className = 'channels-status-badge dirty';
      this.dirtyBadge.textContent = 'Unsaved changes';
    }
    const save = this.editorPanel.querySelector('button[type="submit"]');
    const reset = this.editorPanel.querySelector('[data-action="reset"]');
    const runtime = this.editorPanel.querySelector('[data-editor-runtime]');
    if (save) save.disabled = false;
    if (reset) reset.disabled = false;
    if (runtime) {
      runtime.disabled = true;
      runtime.title = 'Save or reset changes before a runtime command.';
    }
    [...this.tableBody.querySelectorAll('[data-runtime-id]')]
      .filter((button) => button.dataset.runtimeId === this.detail?.id)
      .forEach((button) => { button.disabled = true; });
    const status = this.editorPanel.querySelector('[data-save-status]');
    if (status) status.textContent = 'Changes are staged in this browser.';
    this.updateSelectionControls();
  }

  updateConditionalFields() {
    const automatic = this.editorPanel.querySelector('[name="autoDetectScrambleParameters"]');
    ['channel-wacn', 'channel-p25-system', 'channel-nac'].forEach((id) => {
      const input = this.editorPanel.querySelector(`#${id}`);
      if (input) input.disabled = this.pending || Boolean(automatic?.checked);
    });
    const lowPass = this.editorPanel.querySelector('[name="lowPassEnabled"]');
    const cutoff = this.editorPanel.querySelector('[name="lowPassCutoffHz"]');
    if (cutoff) cutoff.disabled = this.pending || !lowPass?.checked;
    const trim = this.editorPanel.querySelector('[name="squelchTrimEnabled"]');
    ['tailTrimMs', 'headTrimMs'].forEach((name) => {
      const input = this.editorPanel.querySelector(`[name="${name}"]`);
      if (input) input.disabled = this.pending || !trim?.checked;
    });
    const voice = this.editorPanel.querySelector('[name="voiceEnhancePercent"]');
    const output = this.editorPanel.querySelector('[data-voice-output]');
    if (voice && output) output.textContent = `${voice.value}%`;
  }

  bindFrequencyRow(row) {
    row.querySelector('[data-action="frequency-up"]')?.addEventListener('click', () => this.moveFrequency(row, -1));
    row.querySelector('[data-action="frequency-down"]')?.addEventListener('click', () => this.moveFrequency(row, 1));
    row.querySelector('[data-action="frequency-remove"]')?.addEventListener('click', () => {
      row.remove();
      this.markDirty();
      this.renumberFrequencyRows();
    });
  }

  addFrequency() {
    const list = this.editorPanel.querySelector('[data-frequency-list]');
    if (!list) return;
    const maximum = Number(this.options.maximumFrequencies) || 64;
    if (list.children.length >= maximum) {
      this.toast(`A channel can contain at most ${maximum} frequencies.`, true);
      return;
    }
    const holder = this.element('div');
    holder.innerHTML = this.frequencyRow(null, list.children.length, list.children.length + 1);
    const row = holder.firstElementChild;
    list.append(row);
    this.bindFrequencyRow(row);
    this.markDirty();
    this.renumberFrequencyRows();
    row.querySelector('input')?.focus();
  }

  moveFrequency(row, delta) {
    const sibling = delta < 0 ? row.previousElementSibling : row.nextElementSibling;
    if (!sibling) return;
    if (delta < 0) row.parentElement.insertBefore(row, sibling);
    else row.parentElement.insertBefore(sibling, row);
    this.markDirty();
    this.renumberFrequencyRows();
    row.querySelector(`button[data-action="frequency-${delta < 0 ? 'up' : 'down'}"]`)?.focus();
  }

  renumberFrequencyRows() {
    const rows = [...this.editorPanel.querySelectorAll('[data-frequency-row]')];
    rows.forEach((row, index) => {
      const input = row.querySelector('[name="frequencies"]');
      if (input) {
        input.id = `channel-frequency-${index}`;
        input.setAttribute('aria-label', `Control frequency ${index + 1}`);
      }
      const error = row.querySelector('.channels-field-error');
      if (error) error.dataset.errorFor = `channel-frequency-${index}`;
      const up = row.querySelector('[data-action="frequency-up"]');
      const down = row.querySelector('[data-action="frequency-down"]');
      const remove = row.querySelector('[data-action="frequency-remove"]');
      if (up) up.disabled = this.pending || index === 0;
      if (down) down.disabled = this.pending || index === rows.length - 1;
      if (remove) remove.disabled = this.pending || rows.length <= 1;
    });
  }

  bindMapRow(row) {
    row.querySelector('[data-action="map-remove"]')?.addEventListener('click', () => {
      row.remove();
      this.markDirty();
      this.updateMapSlots();
    });
  }

  addMapRow() {
    const body = this.editorPanel.querySelector('[data-map-body]');
    if (!body) return;
    const maximum = Number(this.options.maximumMapRows) || 256;
    if (body.children.length >= maximum) {
      this.toast(`A frequency map can contain at most ${maximum} rows.`, true);
      return;
    }
    const holder = this.element('tbody');
    holder.innerHTML = this.mapRow({}, body.children.length, body.dataset.mapKind);
    const row = holder.firstElementChild;
    body.append(row);
    this.bindMapRow(row);
    this.markDirty();
    this.updateMapSlots();
    row.querySelector('input')?.focus();
  }

  updateMapSlots() {
    const body = this.editorPanel.querySelector('[data-map-body]');
    if (!body) return;
    [...body.querySelectorAll('[data-map-row]')].forEach((row, index) => {
      this.updateMapRow(row, index, body.dataset.mapKind);
    });
  }

  updateMapRow(row, index, kind) {
    const input = row.querySelector('[name="mapNumber"]');
    const raw = input?.value ?? '';
    const number = Number(raw);
    const hasNumber = raw !== '' && Number.isFinite(number) && number > 0;
    const ts1 = row.querySelector('[data-ts1]');
    const ts2 = row.querySelector('[data-ts2]');
    if (ts1) ts1.textContent = hasNumber ? String(number * 2 - 1) : '—';
    if (ts2) ts2.textContent = hasNumber ? String(number * 2) : '—';
    row.querySelectorAll('input').forEach((field) => {
      const label = field.name === 'mapNumber' ? (kind === 'DMR' ? 'LCN' : 'NXDN channel number') :
        (field.name === 'mapDownlink' ? 'Downlink frequency' : 'Uplink frequency');
      field.setAttribute('aria-label', `${label} for map row ${index + 1}`);
    });
  }

  unlockGuid() {
    if (this.isRunning(this.detail)) return;
    this.openDialog('Unlock Site GUID',
      'Changing this value can separate the channel from existing statistics and web links.',
      '<p>Only change the GUID when you deliberately want this channel to become a different site identity.</p>', [
        { label: 'Cancel' },
        { label: 'Unlock GUID', className: 'button-danger', action: () => {
          this.closeDialog();
          this.guidUnlocked = true;
          const input = this.editorPanel.querySelector('[name="guid"]');
          const button = this.editorPanel.querySelector('[data-action="unlock-guid"]');
          if (input) {
            input.readOnly = false;
            input.focus();
          }
          if (button) button.textContent = 'Unlocked';
        } }
      ]);
  }

  async resetEditor() {
    if (!this.detail) return;
    if (this.revisionConflict && this.detail.id) {
      this.dirty = false;
      try {
        await this.reloadOpenDetail();
        await this.loadList();
      } catch (error) {
        this.toast(this.errorMessage(error), true);
      }
      return;
    }
    this.detail = this.clone(this.originalDetail);
    this.dirty = Boolean(this.detail?.isNew);
    this.guidUnlocked = false;
    this.renderEditor();
    this.renderTable();
  }

  validateEditor() {
    const form = this.editorPanel.querySelector('form');
    if (!form) return false;
    form.querySelectorAll('[aria-invalid="true"]').forEach((input) => input.removeAttribute('aria-invalid'));
    form.querySelectorAll('[name="mapNumber"]').forEach((input) => input.setCustomValidity(''));
    form.querySelectorAll('.channels-field-error').forEach((error) => {
      error.hidden = true;
      error.textContent = '';
    });
    let valid = form.checkValidity();
    const guid = form.querySelector('[name="guid"]');
    if (guid && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(guid.value.trim())) {
      valid = false;
      this.showFieldError(guid, 'Enter a complete UUID, including hyphens.');
    }
    const mapNumbers = [...form.querySelectorAll('[name="mapNumber"]')];
    const numbers = new Set();
    mapNumbers.forEach((input) => {
      if (!input.value) return;
      const normalized = Number(input.value);
      if (!Number.isFinite(normalized)) return;
      if (numbers.has(normalized)) {
        valid = false;
        input.setCustomValidity('Map numbers must be unique.');
      } else input.setCustomValidity('');
      numbers.add(normalized);
    });
    if (!valid) {
      const first = form.querySelector(':invalid, [aria-invalid="true"]');
      first?.focus();
      first?.reportValidity();
    }
    return valid;
  }

  showFieldError(input, message) {
    input.setAttribute('aria-invalid', 'true');
    const error = this.editorPanel.querySelector(`[data-error-for="${input.id}"]`);
    if (error) {
      error.textContent = message;
      error.hidden = false;
    }
  }

  selectedChecks(name) {
    return [...this.editorPanel.querySelectorAll(`input[name="${name}"]:checked`)].map((input) => input.value);
  }

  setDecoderValue(decoder, keys, value) {
    decoder[this.key(decoder, keys)] = value;
  }

  readWriteBody(applyPolicy) {
    const form = this.editorPanel.querySelector('form');
    const source = this.clone(this.detail.source || {});
    const decoder = this.clone(this.detail.decoder || {});
    source.frequenciesHz = [...form.querySelectorAll('[name="frequencies"]')]
      .map((input) => Math.round(Number(input.value) * 1_000_000));
    source.preferredTuner = form.elements.preferredTuner?.value || null;
    if (form.elements.rotationDelayMs) source.rotationDelayMs = Number(form.elements.rotationDelayMs.value);
    const number = (name) => form.elements[name] ? Number(form.elements[name].value) : null;
    const checked = (name) => Boolean(form.elements[name]?.checked);
    const value = (name) => form.elements[name]?.value;
    const kind = this.protocolKind(this.detail.protocol);
    source.kind = this.isMultiFrequency(this.detail) ? 'MULTIPLE' : 'SINGLE';
    if (kind === 'P25_CONVENTIONAL') {
      Object.keys(decoder).forEach((key) => {
        if (key !== 'type') delete decoder[key];
      });
    }
    if (form.elements.modulation) this.setDecoderValue(decoder, ['modulation'], value('modulation'));
    if (form.elements.maximumTrafficChannels) this.setDecoderValue(decoder,
      ['maximumTrafficChannels', 'maxTrafficChannels', 'maxTraffic', 'trafficChannelPoolSize'], number('maximumTrafficChannels'));
    if (form.elements.ignoreDataCalls) this.setDecoderValue(decoder, ['ignoreDataCalls', 'ignoreData'], checked('ignoreDataCalls'));
    if (form.elements.learnAnnouncedControlChannels) this.setDecoderValue(decoder,
      ['learnAnnouncedControlChannels'], checked('learnAnnouncedControlChannels'));
    if (kind === 'P25_PHASE2') {
      this.setDecoderValue(decoder, ['autoDetectScrambleParameters', 'autoDetectScramble'], checked('autoDetectScrambleParameters'));
      if (!checked('autoDetectScrambleParameters')) {
        decoder.wacn = value('wacn').toUpperCase();
        decoder.p25System = value('p25System').toUpperCase();
        decoder.nac = value('nac').toUpperCase();
      }
    } else if (kind === 'DMR') {
      this.setDecoderValue(decoder, ['ignoreCrcChecksums', 'ignoreCrc'], checked('ignoreCrcChecksums'));
      this.setDecoderValue(decoder, ['compressedTalkgroups'], checked('compressedTalkgroups'));
      this.writeMap(decoder);
    } else if (kind === 'NXDN') {
      this.setDecoderValue(decoder, ['transmissionMode'], value('transmissionMode'));
      this.setDecoderValue(decoder, ['encoding'], value('encoding'));
      this.setDecoderValue(decoder, ['ignoreEncryptedCalls', 'ignoreEncrypted'], checked('ignoreEncryptedCalls'));
      this.writeMap(decoder);
    } else if (kind === 'NBFM') {
      this.setDecoderValue(decoder, ['bandwidth'], value('bandwidth'));
      this.setDecoderValue(decoder, ['talkgroup'], number('talkgroup'));
      this.setDecoderValue(decoder, ['deemphasis'], value('deemphasis'));
      this.setDecoderValue(decoder, ['outputGain'], number('outputGain'));
      this.setDecoderValue(decoder, ['highPassEnabled'], checked('highPassEnabled'));
      this.setDecoderValue(decoder, ['lowPassEnabled'], checked('lowPassEnabled'));
      this.setDecoderValue(decoder, ['lowPassCutoffHz', 'lowPassCutoff'], number('lowPassCutoffHz'));
      this.setDecoderValue(decoder, ['voiceEnhancePercent'], number('voiceEnhancePercent'));
      this.setDecoderValue(decoder, ['squelchTrimEnabled'], checked('squelchTrimEnabled'));
      this.setDecoderValue(decoder, ['tailTrimMs', 'tailTrim'], number('tailTrimMs'));
      this.setDecoderValue(decoder, ['headTrimMs', 'headTrim'], number('headTrimMs'));
      this.setDecoderValue(decoder, ['bassBoostDb', 'bassBoost'], number('bassBoostDb'));
    }
    return {
      ...(this.detail.isNew ? {} : { revision: this.detail.revision }),
      applyPolicy,
      system: form.elements.system.value.trim(), site: form.elements.site.value.trim(),
      name: form.elements.name.value.trim(), guid: form.elements.guid.value.trim(),
      confirmGuidChange: !this.detail.isNew && this.guidUnlocked &&
        form.elements.guid.value.trim() !== String(this.originalDetail?.guid || ''),
      aliasList: form.elements.aliasList.value, source, decoder,
      auxiliaries: this.selectedChecks('auxiliaries'), logging: this.selectedChecks('logging'),
      recording: this.selectedChecks('recording')
    };
  }

  writeMap(decoder) {
    const entries = [...this.editorPanel.querySelectorAll('[data-map-row]')].map((row) => {
      const uplink = row.querySelector('[name="mapUplink"]').value;
      return {
        number: Number(row.querySelector('[name="mapNumber"]').value),
        downlinkHz: Math.round(Number(row.querySelector('[name="mapDownlink"]').value) * 1_000_000),
        uplinkHz: uplink === '' ? null : Math.round(Number(uplink) * 1_000_000)
      };
    });
    decoder.frequencyMap = entries;
  }

  stableValue(value) {
    if (Array.isArray(value)) return value.map((entry) => this.stableValue(entry));
    if (value && typeof value === 'object') {
      return Object.keys(value).sort().reduce((result, key) => {
        result[key] = this.stableValue(value[key]);
        return result;
      }, {});
    }
    return value;
  }

  normalizedRadioConfiguration(value) {
    const source = this.clone(value?.source || {});
    source.preferredTuner = source.preferredTuner || '';
    source.frequenciesHz = Array.isArray(source.frequenciesHz) ? source.frequenciesHz.map(Number) : [];
    if (source.kind === 'MULTIPLE' && source.rotationDelayMs == null) source.rotationDelayMs = 500;
    if (source.kind === 'SINGLE') source.rotationDelayMs = null;
    return this.stableValue({
      source,
      decoder: this.clone(value?.decoder || {}),
      auxiliaries: Array.isArray(value?.auxiliaries) ? [...value.auxiliaries].map(String).sort() : []
    });
  }

  hasRadioConfigurationChanges(draft) {
    return JSON.stringify(this.normalizedRadioConfiguration(draft)) !==
      JSON.stringify(this.normalizedRadioConfiguration(this.originalDetail));
  }

  async saveChannel() {
    if (this.pending || !this.dirty || !this.validateEditor()) return;
    if (this.isRunning(this.detail)) {
      this.openSavePolicyDialog();
      return;
    }
    await this.performSave('APPLY');
  }

  openSavePolicyDialog() {
    const radioChanged = this.hasRadioConfigurationChanges(this.readWriteBody('APPLY'));
    const definitions = [
      ['STOP', 'Stop and save', 'Stop the channel, apply these settings, and leave it stopped.'],
      ['RESTART', 'Stop, save, and restart', 'Briefly stop the channel, apply these settings, and start it again.']
    ];
    if (!radioChanged) definitions.unshift([
      'NEXT_START', 'Save for next start',
      'Keep the current running channel unchanged and use these settings next time it starts.'
    ]);
    const choices = this.element('div', 'channels-policy-choices');
    definitions.forEach(([value, label, description], index) => {
      const option = this.element('label', 'channels-policy-option');
      const radio = this.element('input');
      radio.type = 'radio';
      radio.name = 'apply-policy';
      radio.value = value;
      radio.checked = index === 0;
      option.append(radio, this.element('span', '', label), this.element('small', '', description));
      choices.append(option);
    });
    this.openDialog('Save a running channel', radioChanged ?
      'Source, decoder, or auxiliary-decoder changes require stopping this channel.' :
      'Choose when the new settings should take effect.', choices, [
      { label: 'Cancel' },
      { label: 'Save', className: 'primary', action: () => {
        const policy = choices.querySelector('[name="apply-policy"]:checked')?.value || definitions[0][0];
        this.closeDialog();
        this.performSave(policy);
      } }
    ]);
  }

  async performSave(applyPolicy) {
    const creating = Boolean(this.detail.isNew);
    const body = this.readWriteBody(applyPolicy);
    await this.runMutation(async () => {
      const path = creating ? '/api/v1/configuration/channels' :
        `/api/v1/configuration/channels/${encodeURIComponent(this.detail.id)}`;
      const result = await this.api(path, {
        method: creating ? 'POST' : 'PUT', headers: this.mutationHeaders(), body: JSON.stringify(body)
      });
      const operation = result.operation && typeof result.operation === 'object' ? result.operation : {};
      const returned = result.detail || result.channel || (result.id ? result : null);
      this.dirty = false;
      this.guidUnlocked = false;
      if (returned?.id) this.detail = returned;
      else if (creating) throw new Error('The channel was created, but its new identity was not returned. Reload Channels.');
      await this.loadList();
      await this.reloadOpenDetail();
      const message = operation.message || (creating ? 'Channel created.' : 'Channel changes saved.');
      if (operation.restartSucceeded === false) {
        this.showEditorNotice(message, true);
        this.toast(message, true);
      } else this.toast(message);
    }, true);
  }

  async reloadOpenDetail() {
    if (!this.detail?.id) return;
    const result = await this.api(`/api/v1/configuration/channels/${encodeURIComponent(this.detail.id)}`);
    this.detail = result.detail || result;
    this.originalDetail = this.clone(this.detail);
    this.dirty = false;
    this.revisionConflict = false;
    this.renderEditor();
  }

  async cloneChannel() {
    if (this.pending || !this.detail?.id || this.detail.isNew) return;
    if (this.dirty && !window.confirm('Clone the last saved version and discard the staged changes?')) return;
    if (this.dirty) {
      this.detail = this.clone(this.originalDetail);
      this.dirty = false;
      this.guidUnlocked = false;
      this.renderEditor();
      this.renderTable();
    }
    await this.runMutation(async () => {
      const result = await this.api(`/api/v1/configuration/channels/${encodeURIComponent(this.detail.id)}/clone`, {
        method: 'POST', headers: this.mutationHeaders(), body: JSON.stringify({ revision: this.detail.revision })
      });
      const clone = result.detail || result.channel || result;
      await this.loadList();
      if (clone?.id) {
        this.detail = clone;
        this.originalDetail = this.clone(clone);
        this.dirty = false;
        this.guidUnlocked = false;
        this.revisionConflict = false;
        this.renderEditor();
        this.renderTable();
      }
      this.toast('Channel cloned with a new GUID.');
    });
  }

  deleteChannel() {
    if (!this.detail) return;
    if (this.detail.isNew) {
      this.detail = null;
      this.originalDetail = null;
      this.dirty = false;
      this.renderEditorEmpty();
      this.renderTable();
      return;
    }
    const name = this.channelDisplayName(this.detail);
    this.openDialog('Delete channel', `Permanently remove ${name}.`,
      '<p>This removes the saved channel configuration. A running channel must be stopped by the server before deletion can succeed.</p>', [
        { label: 'Cancel' },
        { label: 'Delete channel', className: 'button-danger', action: () => {
          this.closeDialog();
          this.performDelete();
        } }
      ]);
  }

  async performDelete() {
    const id = this.detail.id;
    const revision = this.detail.revision;
    await this.runMutation(async () => {
      await this.api(`/api/v1/configuration/channels/${encodeURIComponent(id)}`, {
        method: 'DELETE', headers: this.mutationHeaders(), body: JSON.stringify({ revision, confirm: true })
      });
      this.detail = null;
      this.originalDetail = null;
      this.dirty = false;
      this.selectedIds.delete(id);
      this.renderEditorEmpty();
      await this.loadList();
      this.toast('Channel deleted.');
    });
  }

  async runtime(item, action, jmbeConfirmed = false) {
    if (this.pending || !item?.id || (this.detail?.id === item.id && this.dirty)) return;
    if (!jmbeConfirmed && action === 'START' && this.options.jmbeConfigured === false && this.requiresJmbe(item)) {
      this.showJmbeRequired(item, 1, () => this.runtime(item, action, true));
      return;
    }
    await this.runMutation(async () => {
      await this.api(`/api/v1/configuration/channels/${encodeURIComponent(item.id)}/runtime`, {
        method: 'PUT', headers: this.mutationHeaders(),
        body: JSON.stringify({ revision: item.revision, action })
      });
      await this.loadList();
      if (this.detail?.id === item.id && !this.dirty) await this.reloadOpenDetail();
      this.toast(action === 'START' ? 'Start command accepted.' : 'Stop command accepted.');
    });
  }

  async bulkRuntime(action, jmbeConfirmed = false, startConfirmed = false) {
    if (this.pending || this.selectedIds.size === 0) return;
    const channels = [...this.selectedIds].map(([id, revision]) => ({ id, revision }));
    const names = new Map(this.items.map((item) => [item.id, this.channelDisplayName(item)]));
    if (!jmbeConfirmed && action === 'START' && this.options.jmbeConfigured === false) {
      const blocked = this.items.filter((item) => this.selectedIds.has(item.id) && this.requiresJmbe(item));
      if (blocked.length) {
        this.showJmbeRequired(blocked[0], blocked.length, () => this.bulkRuntime(action, true, false));
        return;
      }
    }
    if (action === 'START' && !startConfirmed) {
      const body = this.element('div');
      body.append(this.element('p', '',
        `The server will try to start ${channels.length} selected ${channels.length === 1 ? 'channel' : 'channels'}.`),
      this.element('p', '',
        'Available tuner capacity is checked as each channel starts, so some channels may fail while others succeed. Channels already processing are not interrupted.'));
      this.openDialog(`Start ${channels.length} selected ${channels.length === 1 ? 'channel' : 'channels'}?`,
        'Confirm this bounded runtime command.', body, [
          { label: 'Cancel' },
          { label: 'Start selected', className: 'primary', action: () => {
            this.closeDialog();
            this.bulkRuntime(action, jmbeConfirmed, true);
          } }
        ]);
      return;
    }
    await this.runMutation(async () => {
      const result = await this.api('/api/v1/configuration/channels/runtime', {
        method: 'PUT', headers: this.mutationHeaders(), body: JSON.stringify({ action, channels })
      });
      const failed = Array.isArray(result.results) ? result.results.filter((entry) => entry.success === false) : [];
      this.selectedIds.clear();
      this.bulkFailures.clear();
      failed.forEach((entry) => {
        const selected = channels.find((channel) => channel.id === entry.id);
        if (selected) this.selectedIds.set(selected.id, selected.revision);
        this.bulkFailures.set(entry.id, entry.message || 'The runtime command failed.');
      });
      await this.loadList();
      if (this.detail?.id && !this.dirty) await this.reloadOpenDetail();
      this.showBulkResult(failed, channels.length, action, names);
      this.toast(failed.length ? `${channels.length - failed.length} succeeded; ${failed.length} failed.` :
        `${action === 'START' ? 'Start' : 'Stop'} command accepted for ${channels.length} channels.`,
      failed.length > 0);
    });
  }

  showBulkResult(failed, total, action, names = new Map()) {
    this.bulkResult.replaceChildren();
    if (!failed.length) {
      this.bulkResult.hidden = true;
      return;
    }
    this.bulkResult.hidden = false;
    this.bulkResult.className = 'channels-bulk-result failed';
    const header = this.element('div', 'channels-bulk-result-header');
    const copy = this.element('div');
    copy.append(this.element('strong', '', `${failed.length} of ${total} runtime commands failed`),
      this.element('span', '', 'Failed channels remain selected so you can review them or retry.'));
    const dismiss = this.button('Dismiss', 'button-link');
    dismiss.addEventListener('click', () => {
      this.bulkFailures.clear();
      this.bulkResult.hidden = true;
      this.bulkResult.replaceChildren();
      this.renderTable();
    });
    header.append(copy, dismiss);
    const list = this.element('ul');
    failed.forEach((entry) => {
      const item = this.element('li');
      const label = names.get(entry.id) || entry.id || 'Unknown channel';
      item.append(this.element('strong', '', label),
        this.element('span', '', entry.message || `${action === 'START' ? 'Start' : 'Stop'} failed.`));
      list.append(item);
    });
    this.bulkResult.append(header, list);
  }

  showJmbeRequired(item, count = 1, continueAction = null) {
    this.openDialog('JMBE voice library unavailable',
      count === 1 ? this.channelDisplayName(item) : `${count} selected digital channels`,
      '<p>The channel can still run its control and data decoder, but calls will not have decoded voice. Configure JMBE from the receiver computer’s local <strong>Voice decoders and keys</strong> settings when voice is needed.</p>',
      [{ label: 'Cancel' }, { label: 'Start without voice', className: 'primary', action: () => {
        this.closeDialog();
        continueAction?.();
      } }]);
  }

  autoStartMarkup(item, location) {
    const order = Number(item.autoStartOrder);
    const active = Number.isInteger(order) && order > 0;
    if (item.supported === false) {
      return `<div class="channels-order-control"><span></span><span class="${active ? 'channels-order-value' : 'channels-order-blank'}">${active ? order : ''}</span><span></span></div>`;
    }
    const count = this.autoStartCount;
    const id = this.escape(item.id || '');
    if (!active) {
      return `<div class="channels-order-control"><span></span><span class="channels-order-blank" aria-label="Not in automatic start"></span><button type="button" data-auto-id="${id}" data-auto-action="ENABLE" data-auto-location="${location}" aria-label="Add ${this.escape(this.channelDisplayName(item))} to automatic start">+</button></div>`;
    }
    const later = count == null || order < count ? `<button class="channels-icon-button" type="button" data-auto-id="${id}" data-auto-action="LATER" data-auto-location="${location}" aria-label="Move ${this.escape(this.channelDisplayName(item))} later"><span class="channels-chevron down"></span></button>` : '<span></span>';
    const earlier = order === 1 ? `<button type="button" data-auto-id="${id}" data-auto-action="DISABLE" data-auto-location="${location}" aria-label="Remove ${this.escape(this.channelDisplayName(item))} from automatic start">−</button>` :
      `<button class="channels-icon-button" type="button" data-auto-id="${id}" data-auto-action="EARLIER" data-auto-location="${location}" aria-label="Move ${this.escape(this.channelDisplayName(item))} earlier"><span class="channels-chevron up"></span></button>`;
    return `<div class="channels-order-control">${later}<span class="channels-order-value">${order}</span>${earlier}</div>`;
  }

  bindAutoStart(scope) {
    scope?.querySelectorAll?.('[data-auto-action]').forEach((button) => {
      button.disabled = Boolean(this.detail?.isNew && button.closest('[data-editor-auto-start]'));
      button.addEventListener('click', () => this.changeAutoStart(button.dataset.autoId,
        button.dataset.autoAction, button.dataset.autoLocation));
    });
  }

  async changeAutoStart(id, action, location) {
    if (this.pending || !id) return;
    const item = this.items.find((candidate) => candidate.id === id) ||
      (this.detail?.id === id ? this.detail : null);
    if (!item) return;
    await this.runMutation(async () => {
      const result = await this.api(`/api/v1/configuration/channels/${encodeURIComponent(id)}/auto-start`, {
        method: 'PUT', headers: this.mutationHeaders(), body: JSON.stringify({
          revision: item.revision, queueRevision: this.queueRevision, action
        })
      });
      this.applyAutoStartResult(result);
      this.sort = 'startOrder';
      this.direction = 'ascending';
      this.offset = 0;
      await this.loadList();
      this.refreshEditorChrome();
      window.requestAnimationFrame(() => {
        const scope = location === 'editor' ? this.editorPanel : this.tableBody;
        [...(scope?.querySelectorAll('[data-auto-id]') || [])]
          .find((button) => button.dataset.autoId === id)?.focus();
      });
    });
  }

  applyAutoStartResult(result) {
    const order = Array.isArray(result.orders) ? result.orders : [];
    this.autoStartCount = order.filter((entry) => Number(entry.autoStartOrder) > 0).length;
    order.forEach((entry) => {
      const item = this.items.find((candidate) => candidate.id === entry.id);
      if (item) Object.assign(item, entry);
      if (this.detail?.id === entry.id && !this.revisionConflict) {
        this.detail.autoStartOrder = entry.autoStartOrder;
        this.detail.revision = entry.revision ?? this.detail.revision;
        if (this.originalDetail?.id === entry.id) {
          this.originalDetail.autoStartOrder = entry.autoStartOrder;
          this.originalDetail.revision = entry.revision ?? this.originalDetail.revision;
        }
      }
    });
    const updated = result.detail || result.channel;
    if (updated?.id && this.detail?.id === updated.id && !this.revisionConflict) {
      this.detail.autoStartOrder = updated.autoStartOrder;
      this.detail.revision = updated.revision ?? this.detail.revision;
      if (this.originalDetail?.id === updated.id) {
        this.originalDetail.autoStartOrder = updated.autoStartOrder;
        this.originalDetail.revision = updated.revision ?? this.originalDetail.revision;
      }
    }
    this.queueRevision = result.queueRevision ?? this.queueRevision;
  }

  refreshEditorChrome() {
    if (!this.detail || !this.editorPanel.isConnected) return;
    const holder = this.editorPanel.querySelector('[data-editor-auto-start]');
    if (holder) {
      holder.innerHTML = this.autoStartMarkup(this.detail, 'editor');
      this.bindAutoStart(holder);
    }
    const state = this.editorPanel.querySelector('.channels-editor-badges .channels-status-badge:first-child');
    if (state) {
      state.className = `channels-status-badge ${this.stateClass(this.detail.state)}`;
      state.textContent = this.detail.state || 'Stopped';
    }
  }

  async exportChannel() {
    if (!this.detail?.id || this.pending) return;
    await this.runMutation(async () => {
      const result = await this.api(`/api/v1/configuration/channels/${encodeURIComponent(this.detail.id)}/export`);
      const blob = new Blob([JSON.stringify(result.configuration, null, 2)], { type: 'application/json' });
      const anchor = this.element('a');
      anchor.href = URL.createObjectURL(blob);
      anchor.download = result.fileName || 'channel-configuration.json';
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(anchor.href), 1000);
    });
  }

  async runMutation(operation, keepDirtyOnFailure = false) {
    if (this.pending || this.closed) return;
    this.setPending(true);
    try {
      await operation();
    } catch (error) {
      if (this.closed) return;
      if (keepDirtyOnFailure) this.dirty = true;
      this.showOperationError(error);
    } finally {
      this.setPending(false);
    }
  }

  setPending(pending) {
    this.pending = pending;
    this.searchInput.disabled = pending;
    this.listPanel.querySelector('.channels-search-clear').disabled = pending;
    this.table.querySelectorAll('.channels-sort-button').forEach((button) => { button.disabled = pending; });
    this.addMenu?.querySelectorAll('button').forEach((button) => { button.disabled = pending; });
    this.timeoutInput.disabled = pending;
    this.timeoutSave.disabled = pending;
    this.previousPage.disabled = pending || this.offset <= 0;
    this.nextPage.disabled = pending || this.offset + this.items.length >= this.total;
    this.tableBody?.querySelectorAll('button, input').forEach((control) => {
      if (pending) {
        control.dataset.wasDisabled = String(control.disabled);
        control.disabled = true;
      } else if (control.dataset.wasDisabled !== undefined) {
        control.disabled = control.dataset.wasDisabled === 'true';
        delete control.dataset.wasDisabled;
      }
    });
    this.setEditorPending(pending || this.isTransitioning(this.detail));
    this.updateSelectionControls();
    if (!pending && !this.listFailed) this.renderTable();
  }

  setEditorPending(pending) {
    const form = this.editorPanel.querySelector('form');
    if (form) form.querySelectorAll('input, select, textarea, button').forEach((control) => {
      if (control.classList.contains('channels-help-button')) return;
      if (pending) {
        control.dataset.wasDisabled = String(control.disabled);
        control.disabled = true;
      } else if (control.dataset.wasDisabled !== undefined) {
        control.disabled = control.dataset.wasDisabled === 'true';
        delete control.dataset.wasDisabled;
      }
    });
    this.editorPanel.querySelectorAll(':scope > .channels-editor-actions button').forEach((button) => {
      if (pending) {
        button.dataset.wasDisabled = String(button.disabled);
        button.disabled = true;
      } else if (button.dataset.wasDisabled !== undefined) {
        button.disabled = button.dataset.wasDisabled === 'true';
        delete button.dataset.wasDisabled;
      }
    });
    if (!pending) this.updateConditionalFields();
  }

  showEditorNotice(message, failed = false) {
    const notice = this.editorPanel.querySelector('[data-editor-notice]');
    if (!notice) {
      this.toast(message, failed);
      return;
    }
    const copy = String(message || '').trim();
    if (!copy) {
      notice.replaceChildren();
      notice.hidden = true;
      return;
    }
    notice.hidden = false;
    notice.className = `channels-editor-notice${failed ? ' failed' : ''}`;
    notice.replaceChildren(this.element('span', '', copy));
  }

  showOperationError(error) {
    const message = this.errorMessage(error);
    const notice = this.editorPanel.querySelector('[data-editor-notice]');
    if (notice) {
      notice.hidden = false;
      notice.className = 'channels-editor-notice failed';
      notice.replaceChildren(this.element('span', '', message));
      if (error.status === 409) {
        this.revisionConflict = true;
        const reload = this.button('Reload saved channel');
        reload.addEventListener('click', async () => {
          if (this.dirty && !window.confirm('Discard these staged changes and reload the saved channel?')) return;
          this.dirty = false;
          await this.reloadOpenDetail().catch((reloadError) => this.toast(this.errorMessage(reloadError), true));
          await this.loadList();
        });
        notice.append(reload);
      }
    } else this.toast(message, true);
    if (error.status === 409) this.toast('Saved or runtime state changed. Review your staged values, then reload if needed.', true);
  }

  errorMessage(error) {
    if (error?.status === 409) return error.message || 'This channel changed elsewhere. Reload it before retrying.';
    if (error?.status === 422) return error.message || 'One or more channel settings are invalid.';
    if (error?.status === 503) return error.message || 'Channel settings are busy. Wait a moment and try again.';
    if (error?.status === 401 || error?.status === 403) return 'Administrator access must be renewed.';
    return error?.message || 'The channel operation failed.';
  }

  toast(message, failed = false) {
    const toast = this.element('div', `channels-toast${failed ? ' failed' : ''}`, message);
    this.toastRegion.append(toast);
    window.setTimeout(() => toast.remove(), 5000);
  }

  isRunning(item) {
    const state = String(item?.state || '').toUpperCase();
    return state.includes('RUNNING') || state.includes('PLAYING') || state === 'ACTIVE';
  }

  isTransitioning(item) {
    const state = String(item?.state || '').toUpperCase();
    return state.includes('STARTING') || state.includes('STOPPING') || state.includes('PENDING');
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    window.clearTimeout(this.searchTimer);
    this.listController?.abort();
    this.detailController?.abort();
    window.removeEventListener('beforeunload', this.onBeforeUnload);
    window.removeEventListener('sdrtrunk:before-route-change', this.onBeforeRouteChange);
    document.removeEventListener('click', this.onNavigation, true);
    document.removeEventListener('pointerdown', this.onDocumentPointer, true);
    this.hideHelp();
    if (this.dialog?.open) this.dialog.close();
  }
}

window.SettingsChannelsView = SettingsChannelsView;
