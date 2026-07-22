/* global AbortController, document, fetch, queueMicrotask, window */
'use strict';

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
    window.addEventListener('sdrtrunk:auth-changed', this.onAuthenticationChange);
    document.addEventListener('visibilitychange', this.onVisibilityReturn);
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
    this.inventoryBody = null;
    this.inventoryState = null;
    this.refreshButton = null;
    this.spectrumPanel = null;
    this.spectrumHost = null;
    this.infoDialog = null;
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
    group.append(hardwareLink);
    navigation.append(group);

    const main = this.element('div', 'settings-main');
    const header = this.element('header', 'settings-header');
    const heading = this.element('div');
    heading.append(this.element('p', 'settings-breadcrumbs', 'Settings / Playlist Settings / Hardware'),
      this.element('h1', '', 'Hardware'),
      this.element('p', 'settings-description',
        'Review detected receivers and open the spectrum for one selected receiver.'));
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

    main.append(header, inventoryPanel, this.spectrumPanel, this.infoDialog);
    layout.append(navigation, main);
    this.root.replaceChildren(layout);
    this.loadInventory();
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
      this.inventoryState.textContent = 'Sign out failed. Try again.';
      this.inventoryState.className = 'hardware-inventory-state failed';
    }
  }

  requireAuthentication(notify = true) {
    if (this.closed) return;
    this.sessionRevision += 1;
    this.requestController?.abort();
    this.requestController = null;
    this.authenticated = false;
    this.renderLogin(true);
    if (notify) {
      window.dispatchEvent(new CustomEvent('sdrtrunk:auth-changed', {
        detail: { authenticated: false }
      }));
    }
  }

  async loadInventory(manual = false) {
    if (this.closed || !this.authenticated || !this.inventoryBody) return;
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
    selector.disabled = selected;
    selector.setAttribute('aria-pressed', String(selected));
    selector.addEventListener('click', () => this.selectTuner(tuner.id));
    const view = this.button(this.spectrumTunerId === tuner.id ? 'Spectrum open' : 'View spectrum', 'primary');
    view.classList.add('hardware-view-spectrum');
    view.dataset.tunerId = tuner.id;
    const spectrumUnavailable = tuner.available !== true || tuner.spectrumAvailable !== true;
    view.disabled = !selected || spectrumUnavailable || externallyBusy || Boolean(this.spectrumView);
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
    if (id === this.selectedTunerId) return;
    if (this.spectrumView) this.closeSpectrum();
    this.selectedTunerId = id;
    this.renderInventory();
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
    window.removeEventListener('sdrtrunk:auth-changed', this.onAuthenticationChange);
    document.removeEventListener('visibilitychange', this.onVisibilityReturn);
    this.spectrumView?.close();
    this.spectrumView = null;
    if (this.infoDialog?.open) this.infoDialog.close();
  }
}

window.SettingsHardwareView = SettingsHardwareView;
