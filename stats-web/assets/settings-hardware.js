/* global AbortController, document, fetch, queueMicrotask, window */
'use strict';

const SETTINGS_CHANNELS_SCRIPT = '/assets/settings-channels.js?v=3';
let settingsChannelsScriptPromise = null;

function loadSettingsChannelsScript() {
  if (window.SettingsChannelsView) return Promise.resolve(window.SettingsChannelsView);
  if (settingsChannelsScriptPromise) return settingsChannelsScriptPromise;

  const script = document.createElement('script');
  script.src = SETTINGS_CHANNELS_SCRIPT;
  script.async = true;
  script.dataset.settingsChannels = 'true';
  const attempt = new Promise((resolve, reject) => {
    script.addEventListener('load', () => {
      if (window.SettingsChannelsView) resolve(window.SettingsChannelsView);
      else {
        script.remove();
        reject(new Error('The Channels editor did not initialize.'));
      }
    }, { once: true });
    script.addEventListener('error', () => {
      script.remove();
      reject(new Error('The Channels editor file could not be loaded.'));
    }, { once: true });
    document.head.append(script);
  });
  settingsChannelsScriptPromise = attempt;
  attempt.catch(() => {
    if (settingsChannelsScriptPromise === attempt) settingsChannelsScriptPromise = null;
  });
  return attempt;
}

class SettingsHardwareView {
  constructor(root) {
    this.root = root;
    this.closed = false;
    this.authenticated = false;
    this.session = null;
    this.inventory = null;
    this.tuners = [];
    this.selectedTunerId = null;
    this.spectrumTunerId = null;
    this.spectrumView = null;
    this.requestController = null;
    this.settingsRequestController = null;
    this.settings = null;
    this.settingsDirty = false;
    this.settingsForm = null;
    this.mutationPending = false;
    this.mutationDisabledStates = null;
    this.fieldSequence = 0;
    this.helpPinned = false;
    this.sessionRevision = 0;
    this.sessionCheckPending = false;
    this.onAuthenticationChange = (event) => {
      const authenticated = event.detail?.authenticated;
      if (authenticated === false && this.authenticated) this.requireAuthentication(false);
      if (authenticated === true && !this.authenticated) this.start();
    };
    this.onVisibilityReturn = () => {
      if (document.visibilityState === 'visible') this.verifySession();
    };
    this.onBeforeUnload = (event) => {
      if (!this.settingsDirty) return;
      event.preventDefault();
      event.returnValue = '';
    };
    this.onTopLevelNavigation = (event) => {
      if (!this.settingsDirty || event.defaultPrevented || event.button !== 0 || event.metaKey ||
          event.ctrlKey || event.shiftKey || event.altKey) return;
      const link = event.target?.closest?.('.primary-nav a, .settings-navigation a, a.brand');
      if (!link || link.target || link.hasAttribute('download')) return;
      const target = new URL(link.href, window.location.href);
      if (target.origin === window.location.origin && target.pathname === '/' &&
          target.searchParams.get('view') === 'settings' &&
          target.searchParams.get('section') === 'hardware') return;
      if (window.confirm('Discard the unsaved receiver changes?')) {
        this.settingsDirty = false;
        return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
    };
    this.onBeforeRouteChange = (event) => {
      if (!this.settingsDirty) return;
      if (window.confirm('Discard the unsaved receiver changes?')) this.settingsDirty = false;
      else event.preventDefault();
    };
    this.onDocumentPointerDown = (event) => {
      if (!this.helpPinned || !this.helpOwner) return;
      if (event.target === this.helpOwner || this.helpOwner.contains(event.target) ||
          this.helpPopover?.contains(event.target)) return;
      this.hideFieldHelp();
    };
    window.addEventListener('sdrtrunk:auth-changed', this.onAuthenticationChange);
    window.addEventListener('beforeunload', this.onBeforeUnload);
    window.addEventListener('sdrtrunk:before-route-change', this.onBeforeRouteChange);
    document.addEventListener('visibilitychange', this.onVisibilityReturn);
    document.addEventListener('click', this.onTopLevelNavigation, true);
    document.addEventListener('pointerdown', this.onDocumentPointerDown, true);
    this.start();
  }

  element(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined && text !== null) element.textContent = String(text);
    return element;
  }

  button(text, className = '') {
    const button = this.element('button', className, text);
    button.type = 'button';
    return button;
  }

  async start() {
    const revision = ++this.sessionRevision;
    this.showLoading('Checking administrator access');

    try {
      const response = await fetch('/api/v1/auth/session', {
        cache: 'no-store', credentials: 'same-origin'
      });
      if (this.closed || revision !== this.sessionRevision) return;
      if (!response.ok) throw new Error('Administrator session could not be checked.');
      const session = await response.json();
      if (session?.authenticated === true) {
        this.renderHardware(session);
      } else {
        this.renderLogin(session?.configured === true);
      }
    } catch (error) {
      if (!this.closed && revision === this.sessionRevision) {
        this.renderAccessError(error.message || 'Administrator session could not be checked.');
      }
    }
  }

  async verifySession() {
    if (this.closed || !this.authenticated || this.sessionCheckPending) return;
    this.sessionCheckPending = true;

    try {
      const response = await fetch('/api/v1/auth/session', {
        cache: 'no-store', credentials: 'same-origin'
      });
      if (this.closed) return;
      const session = response.ok ? await response.json().catch(() => null) : null;
      if (session?.authenticated !== true) {
        this.requireAuthentication();
      } else {
        this.session = session;
      }
    } catch (error) {
      // A transient connection failure must not create a retry loop. The next tab return or protected action rechecks.
    } finally {
      this.sessionCheckPending = false;
    }
  }

  showLoading(message) {
    this.root.className = 'settings-page settings-access-page';
    const card = this.element('section', 'settings-access-card');
    card.append(this.element('div', 'settings-access-spinner'),
      this.element('h1', '', 'Settings'), this.element('p', '', message));
    this.root.replaceChildren(card);
  }

  renderAccessError(message) {
    this.authenticated = false;
    this.root.className = 'settings-page settings-access-page';
    const card = this.element('section', 'settings-access-card');
    card.append(this.element('h1', '', 'Settings unavailable'), this.element('p', '', message));
    const retry = this.button('Try again', 'primary');
    retry.addEventListener('click', () => this.start());
    card.append(retry);
    this.root.replaceChildren(card);
  }

  renderLogin(configured) {
    this.authenticated = false;
    this.session = null;
    this.closeSpectrum();
    if (this.infoDialog?.open) this.infoDialog.close();
    this.inventory = null;
    this.tuners = [];
    this.selectedTunerId = null;
    this.settingsRequestController?.abort();
    this.settingsRequestController = null;
    this.settings = null;
    this.settingsDirty = false;
    this.settingsForm = null;
    this.mutationPending = false;
    this.mutationDisabledStates = null;
    this.inventoryBody = null;
    this.inventoryState = null;
    this.refreshButton = null;
    this.settingsPanel = null;
    this.settingsBody = null;
    this.settingsState = null;
    this.spectrumPanel = null;
    this.spectrumHost = null;
    this.infoDialog = null;
    this.helpPopover = null;
    this.helpOwner = null;
    this.helpPinned = false;
    this.root.className = 'settings-page settings-access-page';
    const card = this.element('section', 'settings-access-card');
    card.append(this.element('span', 'settings-admin-label', 'ADMINISTRATOR'),
      this.element('h1', '', configured ? 'Sign in to Settings' : 'Administrator setup required'));

    if (!configured) {
      card.append(this.element('p', '',
        'Create the administrator account in the receiver’s local Web Server settings, then return here.'));
      const retry = this.button('Check again', 'primary');
      retry.addEventListener('click', () => this.start());
      card.append(retry);
      this.root.replaceChildren(card);
      return;
    }

    card.append(this.element('p', '', 'Hardware and radio settings are available only to the administrator.'));
    const form = this.element('form', 'settings-login-form');
    const usernameLabel = this.element('label', '', 'Username');
    const username = this.element('input');
    username.name = 'username';
    username.autocomplete = 'username';
    username.maxLength = 256;
    username.required = true;
    usernameLabel.append(username);
    const passwordLabel = this.element('label', '', 'Password');
    const password = this.element('input');
    password.type = 'password';
    password.name = 'password';
    password.autocomplete = 'current-password';
    password.maxLength = 256;
    password.required = true;
    passwordLabel.append(password);
    const submit = this.button('Sign in', 'primary');
    submit.type = 'submit';
    const message = this.element('div', 'settings-login-message');
    message.setAttribute('role', 'status');
    message.setAttribute('aria-live', 'polite');
    form.append(usernameLabel, passwordLabel, submit, message);
    form.addEventListener('submit', (event) => this.login(event, username, password, submit, message));
    card.append(form);
    this.root.replaceChildren(card);
    window.requestAnimationFrame(() => username.focus());
  }

  async login(event, username, password, submit, message) {
    event.preventDefault();
    if (submit.disabled || this.closed) return;
    submit.disabled = true;
    message.textContent = 'Signing in…';

    try {
      const response = await fetch('/api/v1/auth/login', {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username.value, password: password.value })
      });
      password.value = '';
      const result = await response.json().catch(() => ({}));
      if (response.ok && result?.authenticated === true) {
        this.renderHardware(result);
        window.dispatchEvent(new CustomEvent('sdrtrunk:auth-changed', {
          detail: { authenticated: true }
        }));
        return;
      }
      if (result.error === 'secure_transport_required') {
        message.textContent = 'Remote sign-in requires HTTPS.';
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
  }

  renderHardware(session) {
    if (this.closed) return;
    this.authenticated = true;
    this.session = session;

    const section = new URLSearchParams(window.location.search).get('section') || 'hardware';
    if (section === 'channels') {
      this.mountChannels(session);
      return;
    }

    this.root.className = 'settings-page';

    const layout = this.element('div', 'settings-layout');
    const navigation = this.element('nav', 'settings-navigation');
    navigation.setAttribute('aria-label', 'Settings sections');
    navigation.append(this.element('h2', '', 'Settings'));
    const group = this.element('section', 'settings-navigation-group');
    group.append(this.element('h3', '', 'Playlist Settings'));
    const hardwareLink = this.element('a', 'active', 'Hardware');
    hardwareLink.href = '/?view=settings&section=hardware';
    hardwareLink.setAttribute('aria-current', 'page');
    const channelsLink = this.element('a', '', 'Channels');
    channelsLink.href = '/?view=settings&section=channels';
    group.append(hardwareLink, channelsLink);
    navigation.append(group);

    const main = this.element('div', 'settings-main');
    const header = this.element('header', 'settings-header');
    const heading = this.element('div');
    heading.append(this.element('p', 'settings-breadcrumbs', 'Settings / Playlist Settings / Hardware'),
      this.element('h1', '', 'Hardware'),
      this.element('p', 'settings-description',
        'Configure detected receivers and open the spectrum for one selected receiver.'));
    const administrator = this.element('div', 'settings-administrator');
    const identity = this.element('span', 'settings-administrator-name', session.username || 'Administrator');
    const signOut = this.button('Sign out');
    signOut.addEventListener('click', () => this.logout(signOut));
    administrator.append(identity, signOut);
    header.append(heading, administrator);

    const inventoryPanel = this.element('section', 'settings-panel hardware-inventory-panel');
    const inventoryHeader = this.element('header', 'settings-panel-header');
    const inventoryHeading = this.element('div');
    inventoryHeading.append(this.element('h2', '', 'Receivers'),
      this.element('p', '', 'Select a detected receiver to inspect it or open its spectrum.'));
    this.refreshButton = this.button('Refresh');
    this.refreshButton.addEventListener('click', () => this.loadInventory(true));
    inventoryHeader.append(inventoryHeading, this.refreshButton);
    this.inventoryState = this.element('div', 'hardware-inventory-state', 'Loading receivers…');
    this.inventoryState.setAttribute('role', 'status');
    this.inventoryState.setAttribute('aria-live', 'polite');
    this.inventoryBody = this.element('div', 'hardware-inventory-body');
    inventoryPanel.append(inventoryHeader, this.inventoryState, this.inventoryBody);

    this.settingsPanel = this.element('section', 'settings-panel hardware-settings-panel');
    this.settingsPanel.hidden = true;
    const settingsHeader = this.element('header', 'settings-panel-header hardware-settings-header');
    const settingsHeading = this.element('div');
    this.settingsTitle = this.element('h2', '', 'Receiver settings');
    this.settingsSubtitle = this.element('p', '', 'Select a receiver above.');
    settingsHeading.append(this.settingsTitle, this.settingsSubtitle);
    this.settingsEnabledButton = this.button('Enable receiver');
    this.settingsEnabledButton.addEventListener('click', () => this.changeEnabledState());
    settingsHeader.append(settingsHeading, this.settingsEnabledButton);
    this.settingsState = this.element('div', 'hardware-settings-state');
    this.settingsState.setAttribute('role', 'status');
    this.settingsState.setAttribute('aria-live', 'polite');
    this.settingsBody = this.element('div', 'hardware-settings-body');
    this.settingsPanel.append(settingsHeader, this.settingsState, this.settingsBody);

    this.spectrumPanel = this.element('section', 'settings-spectrum-panel');
    this.spectrumPanel.hidden = true;
    const spectrumHeader = this.element('header', 'settings-spectrum-header');
    const spectrumHeading = this.element('div');
    this.spectrumTitle = this.element('h2', '', 'Spectrum');
    this.spectrumTarget = this.element('p', '', 'Selected receiver');
    spectrumHeading.append(this.spectrumTitle, this.spectrumTarget);
    this.spectrumCloseButton = this.button('Close spectrum');
    this.spectrumCloseButton.addEventListener('click', () => this.closeSpectrum(true));
    spectrumHeader.append(spectrumHeading, this.spectrumCloseButton);
    this.spectrumHost = this.element('div', 'settings-spectrum-host');
    this.spectrumPanel.append(spectrumHeader, this.spectrumHost);

    this.infoDialog = this.element('dialog', 'settings-info-dialog');
    this.infoDialog.setAttribute('aria-labelledby', 'settings-receiver-dialog-title');
    this.infoDialog.addEventListener('click', (event) => {
      if (event.target === this.infoDialog) this.infoDialog.close();
    });

    this.helpPopover = this.element('div', 'diagnostic-help-popover');
    this.helpPopover.id = 'settings-field-help';
    this.helpPopover.setAttribute('role', 'tooltip');
    this.helpPopover.hidden = true;

    main.append(header, inventoryPanel, this.settingsPanel, this.spectrumPanel, this.infoDialog,
      this.helpPopover);
    layout.append(navigation, main);
    this.root.replaceChildren(layout);
    this.loadInventory();
  }

  async mountChannels(session) {
    const revision = this.sessionRevision;
    this.showLoading('Loading Channels settings');
    try {
      const ChannelsView = await loadSettingsChannelsScript();
      if (this.closed || !this.authenticated || revision !== this.sessionRevision) return;
      this.channelsView?.close();
      this.channelsView = new ChannelsView(this.root, session, {
        requireAuthentication: () => this.requireAuthentication(),
        logout: (button) => this.logout(button)
      });
    } catch (error) {
      if (!this.closed && this.authenticated && revision === this.sessionRevision) {
        this.renderChannelsUnavailable(session, error.message);
      }
    }
  }

  renderChannelsUnavailable(session, message) {
    this.root.className = 'settings-page settings-access-page';
    const card = this.element('section', 'settings-access-card');
    card.append(this.element('span', 'settings-admin-label', 'ADMINISTRATOR'),
      this.element('h1', '', 'Channels settings unavailable'),
      this.element('p', '', message || 'The Channels editor could not be loaded.'));
    const actions = this.element('div', 'settings-access-actions');
    const retry = this.button('Try again', 'primary');
    retry.addEventListener('click', () => this.mountChannels(session));
    const hardware = this.element('a', '', 'Open Hardware settings');
    hardware.href = '/?view=settings&section=hardware';
    actions.append(retry, hardware);
    card.append(actions);
    this.root.replaceChildren(card);
  }

  async logout(button) {
    if (button.disabled || this.closed) return;
    button.disabled = true;

    try {
      let session = this.session;
      if (!session?.csrfToken) {
        const response = await fetch('/api/v1/auth/session', {
          cache: 'no-store', credentials: 'same-origin'
        });
        session = response.ok ? await response.json() : null;
      }
      if (session?.authenticated === true && typeof session.csrfToken === 'string') {
        const response = await fetch('/api/v1/auth/logout', {
          method: 'POST', credentials: 'same-origin', cache: 'no-store',
          headers: { 'X-CSRF-Token': session.csrfToken }
        });
        if (!response.ok && response.status !== 401) throw new Error('Sign out failed.');
      }
      this.requireAuthentication(true);
    } catch (error) {
      button.disabled = false;
      if (this.inventoryState) {
        this.inventoryState.textContent = 'Sign out failed. Try again.';
        this.inventoryState.className = 'hardware-inventory-state failed';
      } else {
        this.channelsView?.toast('Sign out failed. Try again.', true);
      }
    }
  }

  requireAuthentication(notify = true) {
    if (this.closed) return;
    this.sessionRevision += 1;
    this.requestController?.abort();
    this.requestController = null;
    this.settingsRequestController?.abort();
    this.settingsRequestController = null;
    this.authenticated = false;
    this.channelsView?.close();
    this.channelsView = null;
    this.renderLogin(true);
    if (notify) {
      window.dispatchEvent(new CustomEvent('sdrtrunk:auth-changed', {
        detail: { authenticated: false }
      }));
    }
  }

  async loadInventory(manual = false) {
    if (this.closed || !this.authenticated || !this.inventoryBody) return;
    if (manual && this.settingsDirty && !window.confirm('Discard the unsaved receiver changes?')) return;
    this.requestController?.abort();
    const controller = new AbortController();
    this.requestController = controller;
    this.refreshButton.disabled = true;
    this.inventoryState.className = 'hardware-inventory-state';
    this.inventoryState.textContent = manual ? 'Refreshing receivers…' : 'Loading receivers…';

    try {
      const response = await fetch('/api/v1/tuners', {
        cache: 'no-store', credentials: 'same-origin', signal: controller.signal
      });
      if (response.status === 401 || response.status === 403) {
        this.requireAuthentication();
        return;
      }
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.error || 'Receiver inventory is unavailable.');
      if (!Array.isArray(result.tuners)) throw new Error('Receiver inventory is invalid.');
      this.inventory = result;
      this.tuners = result.tuners.filter((tuner) => tuner && typeof tuner.id === 'string' && tuner.id);
      const selectedStillExists = this.tuners.some((tuner) => tuner.id === this.selectedTunerId);
      if (!selectedStillExists) {
        this.selectedTunerId = this.tuners.find((tuner) => tuner.available === true)?.id ||
          this.tuners[0]?.id || null;
      }
      if (this.spectrumTunerId && !this.tuners.some((tuner) => tuner.id === this.spectrumTunerId)) {
        this.closeSpectrum();
      }
      this.renderInventory();
      if (this.selectedTunerId) this.loadSettings(this.selectedTunerId);
    } catch (error) {
      if (error.name === 'AbortError' || this.closed) return;
      this.inventoryState.className = 'hardware-inventory-state failed';
      this.inventoryState.textContent = error.message || 'Receiver inventory is unavailable.';
      this.inventoryBody.replaceChildren();
      const retry = this.button('Try again');
      retry.addEventListener('click', () => this.loadInventory(true));
      this.inventoryBody.append(this.stateCard('Receivers could not be loaded',
        'Radio processing continues normally. Try loading the current receiver list again.', retry));
    } finally {
      if (this.requestController === controller) {
        this.requestController = null;
        if (this.refreshButton) this.refreshButton.disabled = false;
      }
    }
  }

  stateCard(title, detail, action = null) {
    const card = this.element('div', 'hardware-state-card');
    card.append(this.element('h3', '', title), this.element('p', '', detail));
    if (action) card.append(action);
    return card;
  }

  renderInventory() {
    if (this.closed || !this.inventoryBody) return;
    const count = this.tuners.length;
    this.inventoryState.className = 'hardware-inventory-state';
    this.inventoryState.textContent = `${count} ${count === 1 ? 'receiver' : 'receivers'}`;
    this.inventoryBody.replaceChildren();

    if (!count) {
      this.settings = null;
      if (this.settingsPanel) this.settingsPanel.hidden = true;
      this.inventoryBody.append(this.stateCard('No receivers detected',
        'Connect or enable a supported receiver, then refresh this page.'));
      return;
    }

    const externallyBusy = this.inventory?.spectrum?.busy === true && !this.spectrumView;
    if (externallyBusy) {
      const notice = this.element('div', 'hardware-spectrum-notice');
      notice.append(this.element('strong', '', 'Spectrum is currently in use'),
        this.element('span', '', 'Close the other diagnostic view, then refresh the receiver list.'));
      this.inventoryBody.append(notice);
    }

    const cards = this.element('div', 'hardware-tuner-grid');
    this.tuners.forEach((tuner) => cards.append(this.tunerCard(tuner, externallyBusy)));
    this.inventoryBody.append(cards);
  }

  tunerCard(tuner, externallyBusy) {
    const selected = tuner.id === this.selectedTunerId;
    const card = this.element('article', `hardware-tuner-card${selected ? ' selected' : ''}`);
    const summary = this.element('div', 'hardware-tuner-summary');
    const heading = this.element('div', 'hardware-tuner-heading');
    const title = this.element('div');
    title.append(this.element('h3', '', this.tunerName(tuner)),
      this.element('p', '', this.tunerDescription(tuner)));
    heading.append(title, this.statusBadge(tuner));
    const facts = this.element('dl', 'hardware-tuner-facts');
    this.fact(facts, 'Center', this.formatFrequency(tuner.centerFrequencyHz));
    this.fact(facts, 'Sample rate', this.formatSampleRate(tuner.sampleRateHz));
    this.fact(facts, 'Active channels', this.integer(tuner.activeChannelCount));
    this.fact(facts, 'Fixed center', this.onOff(tuner.centerFrequencyFixed));
    summary.append(heading, facts);

    const actions = this.element('div', 'hardware-tuner-actions');
    const selector = this.button(selected ? 'Selected' : 'Select', 'hardware-tuner-select');
    selector.dataset.tunerId = tuner.id;
    selector.disabled = selected || this.mutationPending;
    selector.setAttribute('aria-pressed', String(selected));
    selector.addEventListener('click', () => this.selectTuner(tuner.id));
    const view = this.button(this.spectrumTunerId === tuner.id ? 'Spectrum open' : 'View spectrum', 'primary');
    view.classList.add('hardware-view-spectrum');
    view.dataset.tunerId = tuner.id;
    const spectrumUnavailable = tuner.available !== true || tuner.spectrumAvailable !== true;
    view.disabled = this.mutationPending || !selected || spectrumUnavailable || externallyBusy ||
      Boolean(this.spectrumView);
    let unavailableReason = '';
    if (!selected) unavailableReason = 'Select this receiver first.';
    if (spectrumUnavailable) unavailableReason = 'Spectrum is unavailable for this receiver.';
    if (externallyBusy) unavailableReason = 'Spectrum is in use by another diagnostic view.';
    if (unavailableReason) {
      view.title = unavailableReason;
      view.setAttribute('aria-label', `View spectrum. ${unavailableReason}`);
    }
    view.addEventListener('click', () => this.openSpectrum(tuner.id));
    const information = this.button('i', 'settings-info-button');
    information.setAttribute('aria-label', `Information about ${this.tunerName(tuner)}`);
    information.title = 'Receiver information';
    information.addEventListener('click', () => this.showInformation(tuner));
    actions.append(selector, view, information);
    card.append(summary, actions);
    return card;
  }

  statusBadge(tuner) {
    const value = typeof tuner.status === 'string' && tuner.status ? tuner.status : 'UNKNOWN';
    const badge = this.element('span', `hardware-status ${this.statusClass(value)}`, this.statusLabel(value));
    return badge;
  }

  statusClass(status) {
    const normalized = status.toUpperCase();
    if (normalized === 'ENABLED') return 'available';
    if (normalized === 'ERROR' || normalized === 'REMOVED') return 'failed';
    return 'unavailable';
  }

  statusLabel(status) {
    return status.toLowerCase().replaceAll('_', ' ').replace(/^./, (character) => character.toUpperCase());
  }

  tunerName(tuner) {
    return tuner.displayName || tuner.tunerType || tuner.tunerClass || 'Receiver';
  }

  tunerDescription(tuner) {
    const values = [tuner.tunerType, tuner.tunerClass].filter((value, index, all) =>
      typeof value === 'string' && value && value !== this.tunerName(tuner) && all.indexOf(value) === index);
    return values.join(' · ') || 'Detected receiver';
  }

  fact(list, label, value) {
    const wrapper = this.element('div');
    wrapper.append(this.element('dt', '', label), this.element('dd', '', value));
    list.append(wrapper);
  }

  integer(value) {
    return Number.isSafeInteger(value) && value >= 0 ? value.toLocaleString() : '—';
  }

  onOff(value) {
    return typeof value === 'boolean' ? (value ? 'On' : 'Off') : '—';
  }

  formatFrequency(value) {
    if (!Number.isFinite(value) || value < 0) return '—';
    return `${(value / 1_000_000).toLocaleString(undefined, { minimumFractionDigits: 3,
      maximumFractionDigits: 6 })} MHz`;
  }

  formatSampleRate(value) {
    if (!Number.isFinite(value) || value <= 0) return '—';
    if (value >= 1_000_000) {
      return `${(value / 1_000_000).toLocaleString(undefined, { maximumFractionDigits: 3 })} MHz`;
    }
    return `${(value / 1_000).toLocaleString(undefined, { maximumFractionDigits: 3 })} kHz`;
  }

  selectTuner(id) {
    if (id === this.selectedTunerId || this.mutationPending) return;
    if (this.settingsDirty && !window.confirm('Discard the unsaved receiver changes?')) return;
    if (this.spectrumView) this.closeSpectrum();
    this.selectedTunerId = id;
    this.settings = null;
    this.settingsDirty = false;
    this.renderInventory();
    this.loadSettings(id);
  }

  async loadSettings(id, manual = false) {
    if (this.closed || !this.authenticated || !id || !this.settingsPanel) return;
    this.settingsRequestController?.abort();
    const controller = new AbortController();
    this.settingsRequestController = controller;
    const tuner = this.tuners.find((candidate) => candidate.id === id);
    this.settingsPanel.hidden = false;
    this.settingsTitle.textContent = tuner ? `${this.tunerName(tuner)} settings` : 'Receiver settings';
    this.settingsSubtitle.textContent = 'Loading saved receiver values…';
    this.settingsState.className = 'hardware-settings-state';
    this.settingsState.textContent = manual ? 'Reloading saved settings…' : 'Loading settings…';
    this.settingsBody.replaceChildren();
    this.settingsEnabledButton.disabled = true;

    try {
      const response = await fetch(`/api/v1/tuners/${encodeURIComponent(id)}/settings`, {
        cache: 'no-store', credentials: 'same-origin', signal: controller.signal
      });
      if (response.status === 401 || response.status === 403) {
        this.requireAuthentication();
        return;
      }
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.error || 'Receiver settings are unavailable.');
      if (this.closed || this.selectedTunerId !== id) return;
      this.settings = result;
      this.settingsDirty = false;
      this.renderSettings(manual ? 'Saved values restored.' : '');
    } catch (error) {
      if (error.name === 'AbortError' || this.closed || this.selectedTunerId !== id) return;
      this.settings = null;
      this.settingsState.className = 'hardware-settings-state failed';
      this.settingsState.textContent = error.message || 'Receiver settings are unavailable.';
      const retry = this.button('Try again');
      retry.addEventListener('click', () => this.loadSettings(id, true));
      this.settingsBody.replaceChildren(this.stateCard('Settings could not be loaded',
        'Radio processing continues normally. Try loading this receiver again.', retry));
    } finally {
      if (this.settingsRequestController === controller) this.settingsRequestController = null;
    }
  }

  renderSettings(message = '') {
    const settings = this.settings;
    if (!settings || !this.settingsBody || settings.id !== this.selectedTunerId) return;
    this.settingsForm = null;
    const tuner = this.tuners.find((candidate) => candidate.id === settings.id);
    this.settingsPanel.hidden = false;
    this.settingsTitle.textContent = tuner ? `${this.tunerName(tuner)} settings` : 'Receiver settings';
    const active = settings.radioWorkActive === true;
    const editable = settings.editable === true;
    this.settingsSubtitle.textContent = active ?
      (settings.activeChannelCount > 0 ?
        `${this.integer(settings.activeChannelCount)} active ${settings.activeChannelCount === 1 ? 'channel' : 'channels'}` :
        'Controls locked by active receiver work') :
      (settings.available === true ? 'Ready for changes' : 'Receiver is not running');
    this.settingsEnabledButton.textContent = settings.enabled === true ? 'Disable receiver' : 'Enable receiver';
    this.settingsEnabledButton.className = settings.enabled === true ? 'danger-outline' : 'primary';
    this.settingsEnabledButton.disabled = false;
    this.settingsState.className = 'hardware-settings-state';
    this.settingsState.textContent = message;
    this.settingsBody.replaceChildren();

    if (!editable) {
      const detail = settings.enabled === false ?
        'Enable this receiver to load its supported sample rates and apply configuration changes.' :
        (settings.device?.message || 'Detailed settings are not available for this receiver type yet.');
      this.settingsBody.append(this.stateCard('Configuration is unavailable', detail));
      return;
    }

    if (active) {
      const notice = this.element('div', 'hardware-settings-notice');
      notice.append(this.element('strong', '', 'Radio work is active'),
        this.element('span', '',
          'Tuning, sample-rate, gain, and Bias-T controls are locked to protect live decoding. Automatic PPM and fixed-center mode remain available.'));
      this.settingsBody.append(notice);
    }

    const form = this.element('form', 'hardware-settings-form');
    const tuning = this.formSection('Tuning',
      'Frequency limits control where this receiver may be assigned. Values are saved in MHz.');
    const tuningGrid = this.element('div', 'hardware-field-grid');
    const unsafeDisabled = active;
    const ppm = this.numberField('Frequency correction', settings.frequencyCorrectionPpm, {
      min: -1000, max: 1000, step: 0.1, suffix: 'PPM', disabled: unsafeDisabled,
      help: 'Compensates for a receiver oscillator that is slightly off frequency. Changing it retunes the hardware.'
    });
    const minimum = this.numberField('Minimum frequency', this.toMHz(settings.minimumFrequencyHz), {
      min: this.toMHz(settings.hardwareMinimumFrequencyHz),
      max: this.toMHz(settings.hardwareMaximumFrequencyHz), step: 0.000001, suffix: 'MHz',
      disabled: unsafeDisabled,
      help: 'The lowest center frequency that automatic channel assignment may use for this receiver.'
    });
    const maximum = this.numberField('Maximum frequency', this.toMHz(settings.maximumFrequencyHz), {
      min: this.toMHz(settings.hardwareMinimumFrequencyHz),
      max: this.toMHz(settings.hardwareMaximumFrequencyHz), step: 0.000001, suffix: 'MHz',
      disabled: unsafeDisabled,
      help: 'The highest center frequency that automatic channel assignment may use for this receiver.'
    });
    const autoPpm = this.checkboxField('Automatic PPM correction', settings.autoPpm === true,
      'Lets compatible decoders use measured frequency error to keep this receiver accurately tuned.');
    const fixedCenter = this.checkboxField('Keep center frequency fixed', settings.centerFrequencyFixed === true,
      'Prevents automatic channel assignment from moving the receiver’s center frequency. It does not retune the receiver when switched on.');
    tuningGrid.append(ppm.wrapper, minimum.wrapper, maximum.wrapper, autoPpm.wrapper, fixedCenter.wrapper);
    tuning.append(tuningGrid);

    const device = this.formSection('Device settings', 'Only controls supported by this receiver are shown.');
    const deviceGrid = this.element('div', 'hardware-field-grid');
    const deviceControls = this.renderDeviceFields(settings.device, deviceGrid, unsafeDisabled);
    device.append(deviceGrid);

    const actions = this.element('footer', 'hardware-settings-actions');
    const actionMessage = this.element('div', 'hardware-settings-action-message');
    actionMessage.setAttribute('role', 'status');
    actionMessage.setAttribute('aria-live', 'polite');
    const reset = this.button('Reset');
    reset.addEventListener('click', () => this.loadSettings(settings.id, true));
    const save = this.button('Save changes', 'primary');
    save.type = 'submit';
    save.disabled = true;
    actions.append(actionMessage, reset, save);
    form.append(tuning, device, actions);
    this.settingsBody.append(form);

    this.settingsForm = { form, ppm: ppm.input, minimum: minimum.input, maximum: maximum.input,
      autoPpm: autoPpm.input, fixedCenter: fixedCenter.input, device: deviceControls,
      save, reset, message: actionMessage };
    form.addEventListener('input', () => this.markSettingsDirty());
    form.addEventListener('change', () => {
      this.updateDeviceFieldVisibility();
      this.markSettingsDirty();
    });
    form.addEventListener('submit', (event) => this.saveSettings(event));
    this.updateDeviceFieldVisibility();
  }

  formSection(title, description) {
    const section = this.element('section', 'hardware-form-section');
    const header = this.element('header');
    header.append(this.element('h3', '', title), this.element('p', '', description));
    section.append(header);
    return section;
  }

  fieldLabel(text, help, controlId) {
    const label = this.element('span', 'hardware-field-label');
    const textLabel = this.element('label', '', text);
    textLabel.htmlFor = controlId;
    label.append(textLabel);
    if (help) label.append(this.infoControl(help));
    return label;
  }

  infoControl(help) {
    const information = this.button('i', 'settings-info-button settings-field-info');
    information.setAttribute('aria-label', `More information: ${help}`);
    information.setAttribute('aria-expanded', 'false');
    const show = (pin = false) => {
      if (this.helpPinned && this.helpOwner !== information && !pin) return;
      if (this.helpOwner !== information) this.hideFieldHelp();
      if (!this.helpPopover) return;
      this.helpOwner = information;
      if (pin) this.helpPinned = true;
      information.setAttribute('aria-describedby', this.helpPopover.id);
      information.setAttribute('aria-expanded', 'true');
      this.helpPopover.textContent = help;
      this.helpPopover.hidden = false;
      const rect = information.getBoundingClientRect();
      const left = Math.min(window.innerWidth - this.helpPopover.offsetWidth - 12,
        Math.max(12, rect.left));
      let top = rect.bottom + 7;
      if (top + this.helpPopover.offsetHeight > window.innerHeight - 12) {
        top = rect.top - this.helpPopover.offsetHeight - 7;
      }
      this.helpPopover.style.left = `${left}px`;
      this.helpPopover.style.top = `${Math.max(8, top)}px`;
    };
    information.addEventListener('pointerenter', () => show());
    information.addEventListener('pointerleave', () => {
      if (!this.helpPinned && document.activeElement !== information) this.hideFieldHelp();
    });
    information.addEventListener('focus', () => show());
    information.addEventListener('blur', () => {
      if (!this.helpPinned) this.hideFieldHelp();
    });
    information.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (this.helpOwner === information && this.helpPinned) {
        this.hideFieldHelp();
      } else {
        show(true);
      }
    });
    information.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        this.hideFieldHelp();
      }
    });
    return information;
  }

  hideFieldHelp() {
    this.helpPinned = false;
    if (!this.helpPopover) return;
    this.helpPopover.hidden = true;
    if (this.helpOwner) {
      this.helpOwner.setAttribute('aria-expanded', 'false');
      this.helpOwner.removeAttribute('aria-describedby');
    }
    this.helpOwner = null;
  }

  numberField(label, value, options) {
    const wrapper = this.element('div', 'hardware-field');
    const control = this.element('span', 'hardware-input-with-unit');
    const input = this.element('input');
    input.id = `hardware-field-${++this.fieldSequence}`;
    input.type = 'number';
    input.required = true;
    input.value = Number.isFinite(value) ? String(value) : '';
    if (Number.isFinite(options.min)) input.min = String(options.min);
    if (Number.isFinite(options.max)) input.max = String(options.max);
    if (options.step !== undefined) input.step = String(options.step);
    input.disabled = options.disabled === true;
    wrapper.append(this.fieldLabel(label, options.help, input.id));
    control.append(input, this.element('span', '', options.suffix));
    wrapper.append(control);
    return { wrapper, input };
  }

  selectField(label, value, options, help, disabled = false) {
    const wrapper = this.element('div', 'hardware-field');
    const select = this.element('select');
    select.id = `hardware-field-${++this.fieldSequence}`;
    select.disabled = disabled;
    (Array.isArray(options) ? options : []).forEach((choice) => {
      const option = this.element('option', '', choice.label);
      option.value = String(choice.value);
      option.selected = String(choice.value) === String(value);
      select.append(option);
    });
    wrapper.append(this.fieldLabel(label, help, select.id));
    wrapper.append(select);
    return { wrapper, input: select };
  }

  checkboxField(label, checked, help, disabled = false) {
    const wrapper = this.element('div', 'hardware-field hardware-checkbox-field');
    const input = this.element('input');
    input.id = `hardware-field-${++this.fieldSequence}`;
    input.type = 'checkbox';
    input.checked = checked;
    input.disabled = disabled;
    wrapper.append(input, this.fieldLabel(label, help, input.id));
    return { wrapper, input };
  }

  renderDeviceFields(device, host, disabled) {
    const controls = { type: device?.type || 'UNSUPPORTED' };
    controls.sampleRate = this.selectField('Sample rate', device.sampleRateHz, device.sampleRates,
      'Controls how much radio spectrum the receiver captures at once. It cannot change while channels are active.',
      disabled);
    host.append(controls.sampleRate.wrapper);

    if (device?.type === 'AIRSPY') {
      controls.airspyGainMode = this.selectField('Gain mode', device.gainMode,
        ['LINEARITY', 'SENSITIVITY', 'CUSTOM'].map((value) => ({ value,
          label: value.charAt(0) + value.slice(1).toLowerCase() })),
        'Linearity resists strong-signal distortion, Sensitivity favors weak signals, and Custom exposes each gain stage.',
        disabled);
      controls.airspyGain = this.numberField('Preset gain', device.gain, {
        min: device.gainMinimum, max: device.gainMaximum, step: 1, suffix: '', disabled,
        help: 'Selects the strength of the Linearity or Sensitivity preset from 1 through 22.'
      });
      controls.airspyIfGain = this.numberField('IF gain', device.ifGain, {
        min: device.ifGainMinimum, max: device.ifGainMaximum, step: 1, suffix: '', disabled,
        help: 'Custom-mode gain applied in the receiver’s intermediate-frequency stage.'
      });
      controls.airspyMixerGain = this.numberField('Mixer gain', device.mixerGain, {
        min: device.mixerGainMinimum, max: device.mixerGainMaximum, step: 1, suffix: '', disabled,
        help: 'Custom-mode gain applied in the frequency mixer.'
      });
      controls.airspyMixerAgc = this.checkboxField('Mixer automatic gain', device.mixerAgc === true,
        'Lets the Airspy adjust mixer gain automatically in Custom mode.', disabled);
      controls.airspyLnaGain = this.numberField('LNA gain', device.lnaGain, {
        min: device.lnaGainMinimum, max: device.lnaGainMaximum, step: 1, suffix: '', disabled,
        help: 'Custom-mode gain applied by the low-noise amplifier closest to the antenna.'
      });
      controls.airspyLnaAgc = this.checkboxField('LNA automatic gain', device.lnaAgc === true,
        'Lets the Airspy adjust low-noise-amplifier gain automatically in Custom mode.', disabled);
      controls.airspyPreset = this.element('div', 'hardware-device-subgrid');
      controls.airspyPreset.append(controls.airspyGain.wrapper);
      controls.airspyCustom = this.element('div', 'hardware-device-subgrid');
      controls.airspyCustom.append(controls.airspyIfGain.wrapper, controls.airspyMixerGain.wrapper,
        controls.airspyMixerAgc.wrapper, controls.airspyLnaGain.wrapper, controls.airspyLnaAgc.wrapper);
      host.append(controls.airspyGainMode.wrapper, controls.airspyPreset, controls.airspyCustom);
    } else if (device?.type === 'RTL_R8X') {
      controls.rtlBiasT = this.checkboxField('Bias-T', device.biasT === true,
        'Supplies DC power through the antenna connector for compatible active antennas or amplifiers.', disabled);
      controls.rtlMasterGain = this.selectField('Master gain', device.masterGain, device.masterGains,
        'Automatic lets the receiver choose gain, a numbered preset chooses a fixed gain, and Manual exposes each stage.',
        disabled);
      controls.rtlMixerGain = this.selectField('Mixer gain', device.mixerGain, device.mixerGains,
        'Manual-mode gain applied in the RTL-SDR frequency mixer.', disabled);
      controls.rtlLnaGain = this.selectField('LNA gain', device.lnaGain, device.lnaGains,
        'Manual-mode gain applied by the low-noise amplifier closest to the antenna.', disabled);
      controls.rtlVgaGain = this.selectField('VGA gain', device.vgaGain, device.vgaGains,
        'Manual-mode gain applied after the tuner’s mixer and filtering stages.', disabled);
      controls.rtlManual = this.element('div', 'hardware-device-subgrid');
      controls.rtlManual.append(controls.rtlLnaGain.wrapper, controls.rtlMixerGain.wrapper,
        controls.rtlVgaGain.wrapper);
      host.append(controls.rtlBiasT.wrapper, controls.rtlMasterGain.wrapper, controls.rtlManual);
    }
    return controls;
  }

  updateDeviceFieldVisibility() {
    const controls = this.settingsForm?.device;
    if (!controls) return;
    if (controls.type === 'AIRSPY') {
      const custom = controls.airspyGainMode.input.value === 'CUSTOM';
      controls.airspyPreset.hidden = custom;
      controls.airspyCustom.hidden = !custom;
    }
    if (controls.type === 'RTL_R8X') {
      controls.rtlManual.hidden = controls.rtlMasterGain.input.value !== 'MANUAL';
    }
  }

  markSettingsDirty() {
    if (!this.settingsForm) return;
    this.settingsDirty = true;
    this.settingsForm.save.disabled = false;
    this.settingsForm.message.textContent = 'Unsaved changes';
  }

  async saveSettings(event) {
    event.preventDefault();
    const form = this.settingsForm;
    const settings = this.settings;
    if (!form || !settings || form.save.disabled || this.closed) return;
    if (!form.form.reportValidity()) return;
    const body = this.settingsRequestBody();
    if (!body) {
      form.message.textContent = 'Check the frequency and gain values.';
      return;
    }
    form.save.disabled = true;
    form.reset.disabled = true;
    form.message.textContent = 'Saving…';
    this.setMutationPending(true);

    try {
      const response = await fetch(`/api/v1/tuners/${encodeURIComponent(settings.id)}/settings`, {
        method: 'PUT', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': this.session?.csrfToken || '' },
        body: JSON.stringify(body)
      });
      const result = await response.json().catch(() => ({}));
      if (await this.handleMutationAuthorizationFailure(response.status)) return;
      if (!response.ok) throw Object.assign(new Error(result.error || 'Receiver settings were not saved.'),
        { status: response.status });
      if (this.closed || this.selectedTunerId !== settings.id || this.settingsForm !== form) return;
      this.setMutationPending(false);
      this.settings = result;
      this.settingsDirty = false;
      this.renderSettings('Changes saved.');
    } catch (error) {
      if (this.closed || !this.settingsForm) return;
      this.setMutationPending(false);
      if (error.status === 412) {
        this.settingsForm.message.textContent = 'Settings changed elsewhere. Reset to reload them.';
      } else {
        this.settingsForm.message.textContent = error.message || 'Receiver settings were not saved.';
      }
      this.settingsForm.save.disabled = false;
      this.settingsForm.reset.disabled = false;
    } finally {
      if (!this.closed && this.mutationPending && this.settingsForm === form) this.setMutationPending(false);
    }
  }

  settingsRequestBody() {
    const form = this.settingsForm;
    const settings = this.settings;
    const device = form?.device;
    if (!form || !settings || !device) return null;
    const ppm = form.ppm.valueAsNumber;
    const minimumMHz = form.minimum.valueAsNumber;
    const maximumMHz = form.maximum.valueAsNumber;
    const sampleRate = Number(device.sampleRate.input.value);
    if (![ppm, minimumMHz, maximumMHz, sampleRate].every(Number.isFinite)) return null;
    const body = {
      revision: settings.revision,
      frequencyCorrectionPpm: ppm,
      autoPpm: form.autoPpm.checked,
      minimumFrequencyHz: Math.round(minimumMHz * 1_000_000),
      maximumFrequencyHz: Math.round(maximumMHz * 1_000_000),
      centerFrequencyFixed: form.fixedCenter.checked,
      deviceType: device.type,
      sampleRateHz: sampleRate,
      airspyGainMode: null, airspyGain: null, airspyIfGain: null, airspyMixerGain: null,
      airspyLnaGain: null, airspyMixerAgc: null, airspyLnaAgc: null,
      rtlBiasT: null, rtlMasterGain: null, rtlMixerGain: null, rtlLnaGain: null, rtlVgaGain: null
    };
    if (device.type === 'AIRSPY') {
      Object.assign(body, {
        airspyGainMode: device.airspyGainMode.input.value,
        airspyGain: device.airspyGain.input.valueAsNumber,
        airspyIfGain: device.airspyIfGain.input.valueAsNumber,
        airspyMixerGain: device.airspyMixerGain.input.valueAsNumber,
        airspyLnaGain: device.airspyLnaGain.input.valueAsNumber,
        airspyMixerAgc: device.airspyMixerAgc.input.checked,
        airspyLnaAgc: device.airspyLnaAgc.input.checked
      });
    } else if (device.type === 'RTL_R8X') {
      Object.assign(body, {
        rtlBiasT: device.rtlBiasT.input.checked,
        rtlMasterGain: device.rtlMasterGain.input.value,
        rtlMixerGain: device.rtlMixerGain.input.value,
        rtlLnaGain: device.rtlLnaGain.input.value,
        rtlVgaGain: device.rtlVgaGain.input.value
      });
    }
    return body;
  }

  async changeEnabledState() {
    const settings = this.settings;
    const button = this.settingsEnabledButton;
    if (!settings || !button || button.disabled || this.mutationPending) return;
    if (this.settingsDirty && !window.confirm('Discard the unsaved receiver changes?')) return;
    const enabled = settings.enabled !== true;
    let confirmActiveStop = false;
    if (!enabled && settings.radioWorkActive === true) {
      const workDescription = settings.activeChannelCount > 0 ?
        `${settings.activeChannelCount} active ${settings.activeChannelCount === 1 ? 'channel' : 'channels'}` :
        'active receiver work';
      confirmActiveStop = window.confirm(
        `Disable this receiver and stop its ${workDescription}?`);
      if (!confirmActiveStop) return;
    }
    button.disabled = true;
    this.setMutationPending(true);
    this.settingsState.className = 'hardware-settings-state';
    this.settingsState.textContent = enabled ? 'Enabling receiver…' : 'Disabling receiver…';

    try {
      const response = await fetch(`/api/v1/tuners/${encodeURIComponent(settings.id)}/enabled`, {
        method: 'PUT', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': this.session?.csrfToken || '' },
        body: JSON.stringify({ revision: settings.revision, enabled, confirmActiveStop })
      });
      const result = await response.json().catch(() => ({}));
      if (await this.handleMutationAuthorizationFailure(response.status)) return;
      if (!response.ok) throw new Error(result.error || 'The receiver state could not be changed.');
      if (this.closed || this.selectedTunerId !== settings.id) return;
      this.settings = result;
      this.settingsDirty = false;
      this.setMutationPending(false);
      await this.loadInventory(true);
    } catch (error) {
      if (this.closed || !this.settingsState) return;
      this.setMutationPending(false);
      button.disabled = false;
      this.settingsState.className = 'hardware-settings-state failed';
      this.settingsState.textContent = error.message || 'The receiver state could not be changed.';
    } finally {
      if (!this.closed && this.mutationPending && this.selectedTunerId === settings.id) {
        this.setMutationPending(false);
      }
    }
  }

  async handleMutationAuthorizationFailure(status) {
    if (status === 401) {
      this.requireAuthentication();
      return true;
    }
    if (status !== 403) return false;

    try {
      const response = await fetch('/api/v1/auth/session', {
        cache: 'no-store', credentials: 'same-origin'
      });
      const session = response.ok ? await response.json().catch(() => null) : null;
      if (response.ok && session?.authenticated === false) {
        this.requireAuthentication();
        return true;
      }
      if (session?.authenticated === true) this.session = session;
    } catch (error) {
      // Keep the current page and show the original rejection when session rechecking is temporarily unavailable.
    }
    return false;
  }

  setMutationPending(pending) {
    this.mutationPending = pending;
    if (this.refreshButton) this.refreshButton.disabled = pending;
    if (pending && this.settingsForm?.form) {
      this.mutationDisabledStates = new Map();
      Array.from(this.settingsForm.form.elements).forEach((control) => {
        this.mutationDisabledStates.set(control, control.disabled);
        control.disabled = true;
      });
    } else if (!pending && this.mutationDisabledStates) {
      this.mutationDisabledStates.forEach((disabled, control) => {
        if (control.isConnected) control.disabled = disabled;
      });
      this.mutationDisabledStates = null;
    }
    if (this.settingsEnabledButton) {
      this.settingsEnabledButton.disabled = pending || !this.settings;
    }
    if (this.inventoryBody && this.inventory) this.renderInventory();
  }

  toMHz(value) {
    return Number.isFinite(value) ? Number((value / 1_000_000).toFixed(6)) : NaN;
  }

  openSpectrum(id) {
    if (this.closed || this.spectrumView || !window.WidebandSignalView) return;
    const tuner = this.tuners.find((candidate) => candidate.id === id);
    if (!tuner || tuner.available !== true || tuner.spectrumAvailable !== true ||
        this.inventory?.spectrum?.busy === true) return;
    this.selectedTunerId = id;
    this.spectrumTunerId = id;
    this.spectrumPanel.hidden = false;
    this.spectrumTitle.textContent = 'Spectrum';
    this.spectrumTarget.textContent = this.tunerName(tuner);
    this.spectrumHost.replaceChildren();
    const host = this.element('div');
    this.spectrumHost.append(host);
    try {
      this.spectrumView = new window.WidebandSignalView(host, {
        initialTargetId: id,
        lockedTarget: true,
        embedded: true,
        authenticationControls: false,
        onAuthenticationRequired: () => queueMicrotask(() => this.requireAuthentication())
      });
    } catch (error) {
      this.spectrumTunerId = null;
      this.spectrumPanel.hidden = true;
      this.spectrumHost.replaceChildren();
      this.renderInventory();
      this.inventoryState.className = 'hardware-inventory-state failed';
      this.inventoryState.textContent = 'Spectrum could not be opened in this browser.';
      return;
    }
    this.renderInventory();
    this.spectrumTitle.tabIndex = -1;
    this.spectrumTitle.focus({ preventScroll: true });
    this.spectrumPanel.scrollIntoView({ block: 'start' });
  }

  closeSpectrum(returnFocus = false) {
    const id = this.spectrumTunerId;
    this.spectrumView?.close();
    this.spectrumView = null;
    this.spectrumTunerId = null;
    if (this.spectrumHost) this.spectrumHost.replaceChildren();
    if (this.spectrumPanel) this.spectrumPanel.hidden = true;
    if (this.inventoryBody && this.inventory) this.renderInventory();
    if (returnFocus && id) {
      Array.from(this.root.querySelectorAll('.hardware-view-spectrum'))
        .find((button) => button.dataset.tunerId === id)?.focus();
    }
  }

  showInformation(tuner) {
    if (!this.infoDialog) return;
    const header = this.element('header', 'settings-dialog-header');
    const title = this.element('div');
    const dialogTitle = this.element('h2', '', this.tunerName(tuner));
    dialogTitle.id = 'settings-receiver-dialog-title';
    title.append(dialogTitle,
      this.element('p', '', 'Receiver status and hardware details'));
    const close = this.button('×', 'settings-dialog-close');
    close.setAttribute('aria-label', 'Close receiver information');
    close.addEventListener('click', () => this.infoDialog.close());
    header.append(title, close);
    const details = this.element('dl', 'settings-detail-list');
    this.detail(details, 'Tuner type', tuner.tunerType || '—');
    this.detail(details, 'Status', this.statusLabel(tuner.status || 'UNKNOWN'));
    this.detail(details, 'Enabled', typeof tuner.enabled === 'boolean' ? (tuner.enabled ? 'Yes' : 'No') : 'Unavailable');
    this.detail(details, 'Center frequency', this.formatFrequency(tuner.centerFrequencyHz));
    this.detail(details, 'Sample rate', this.formatSampleRate(tuner.sampleRateHz));
    this.detail(details, 'Active channels', this.integer(tuner.activeChannelCount));
    this.detail(details, 'Keep center frequency fixed', this.onOff(tuner.centerFrequencyFixed));
    this.detail(details, 'Sample-rate changes', typeof tuner.sampleRateLocked === 'boolean' ?
      (tuner.sampleRateLocked ? 'Locked by active radio work' : 'Not locked') : 'Unavailable');
    this.detail(details, 'Spectrum', typeof tuner.spectrumAvailable === 'boolean' ?
      (tuner.spectrumAvailable ? 'Available' : 'Unavailable') : 'Unavailable');
    if (typeof tuner.hardwareIdentifier === 'string' && tuner.hardwareIdentifier) {
      this.detail(details, 'Hardware identifier', tuner.hardwareIdentifier);
    }
    if (typeof tuner.errorMessage === 'string' && tuner.errorMessage) {
      this.detail(details, 'Receiver error', tuner.errorMessage);
    }
    const footer = this.element('footer', 'settings-dialog-footer');
    const done = this.button('Close', 'primary');
    done.addEventListener('click', () => this.infoDialog.close());
    footer.append(done);
    this.infoDialog.replaceChildren(header, details, footer);
    this.infoDialog.showModal();
  }

  detail(list, label, value) {
    list.append(this.element('dt', '', label), this.element('dd', '', value));
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.sessionRevision += 1;
    this.requestController?.abort();
    this.requestController = null;
    this.settingsRequestController?.abort();
    this.settingsRequestController = null;
    window.removeEventListener('sdrtrunk:auth-changed', this.onAuthenticationChange);
    window.removeEventListener('beforeunload', this.onBeforeUnload);
    window.removeEventListener('sdrtrunk:before-route-change', this.onBeforeRouteChange);
    document.removeEventListener('visibilitychange', this.onVisibilityReturn);
    document.removeEventListener('click', this.onTopLevelNavigation, true);
    document.removeEventListener('pointerdown', this.onDocumentPointerDown, true);
    this.spectrumView?.close();
    this.spectrumView = null;
    this.channelsView?.close();
    this.channelsView = null;
    if (this.infoDialog?.open) this.infoDialog.close();
    this.hideFieldHelp();
  }
}

window.SettingsHardwareView = SettingsHardwareView;
