const ROOT = '/api/v1/admin/remote-links';
const POLL_MS = 3000;

// Reuse map: ui-section-title/body, ui-surface-inset, ui-record-card/section/header/body,
// ui-disclosure-toggle/copy, ui-heading-group, ui-fact-list, ui-status, shared glyphs,
// ui-toggle-field, ui-field, ui-select-frame, ui-segmented and the shared modal lifecycle
// provide the visual language. This feature owns only Remote Links layout geometry.
export function createRemoteLinksWorkspace(deps) {
  const { node, formField, uiSelectFrame, uiToggleField, uiStatus, uiSegmentedControl, iconGlyph, remoteOriginIcon,
    openReadOnlyModal, confirmAction, createFormWorkflow, requestJson, modalFooter, signal } = deps;
  const host = node('div', 'remote-links-page data-workspace');
  const message = node('div');
  message.setAttribute('role', 'status');
  message.hidden = true;
  const restartNotice = node('div', 'ui-notice ui-notice-warning remote-links-restart-notice',
    'Sender removed. Restart VCE to finish updating its channel list.');
  restartNotice.setAttribute('role', 'status');
  restartNotice.hidden = true;
  let selectedDirection = 'receive';
  const directionPanels = new Map(['receive', 'send'].map((direction) => {
    const panel = node('div', 'remote-links-direction-view');
    panel.id = `remote-links-${direction}-view`;
    panel.setAttribute('role', 'tabpanel');
    panel.setAttribute('aria-labelledby', `remote-links-${direction}-tab`);
    panel.hidden = direction !== selectedDirection;
    return [direction, panel];
  }));
  const navigation = uiSegmentedControl([
    { value: 'receive', label: 'Receive here' }, { value: 'send', label: 'Send from here' }
  ], selectedDirection, (direction) => {
    selectedDirection = direction;
    directionPanels.forEach((panel, key) => { panel.hidden = key !== selectedDirection; });
    receiveContext.hidden = selectedDirection !== 'receive';
    [...navigation.children].forEach((control) => {
      const selected = control.dataset.value === selectedDirection;
      control.setAttribute('aria-selected', String(selected));
      control.removeAttribute('aria-pressed');
      control.tabIndex = selected ? 0 : -1;
    });
  });
  navigation.setAttribute('role', 'tablist');
  navigation.setAttribute('aria-label', 'Remote Link direction');
  [...navigation.children].forEach((control) => {
    control.id = `remote-links-${control.dataset.value}-tab`;
    control.setAttribute('role', 'tab');
    control.setAttribute('aria-selected', String(control.dataset.value === selectedDirection));
    control.removeAttribute('aria-pressed');
    control.tabIndex = control.dataset.value === selectedDirection ? 0 : -1;
    control.setAttribute('aria-controls', directionPanels.get(control.dataset.value).id);
  });
  navigation.addEventListener('keydown', (event) => {
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
    const controls = [...navigation.children];
    const index = controls.indexOf(event.target);
    if (index < 0) return;
    const next = { ArrowLeft: (index + controls.length - 1) % controls.length,
      ArrowRight: (index + 1) % controls.length, Home: 0, End: controls.length - 1 }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    controls[next].focus();
    controls[next].click();
  });
  const overview = node('div', 'remote-links-overview');
  overview.append(...directionPanels.values());
  const senderHeader = node('div', 'remote-links-section-header');
  const senderHeading = node('div', 'ui-heading-group');
  const senderCount = node('p', 'muted');
  senderHeading.append(node('h3', '', 'Senders allowed to send here'), senderCount);
  const addSender = button('Add sender', () => openCreateSender(), true, 'add-sender');
  addSender.prepend(iconGlyph('icon-plus'));
  senderHeader.append(senderHeading, addSender);
  const senderHost = node('div', 'remote-links-sender-grid ui-record-card');
  const pageMeta = node('footer', 'remote-links-page-meta');
  const freshness = node('span', 'muted');
  const receiveContext = node('span', 'muted',
    'Remote channels use this receiver’s Alias Lists and depend on their sender for live traffic.');
  pageMeta.append(freshness, receiveContext);
  host.append(message, navigation, overview, pageMeta);

  let snapshot = null;
  let loading = false;
  let disposed = false;
  let modalActive = false;
  let timer = null;
  let activationReleaseTimer = null;
  let pointerActivation = false;
  let keyboardActivation = '';
  let pendingSnapshot = null;
  const expandedSenders = new Map();
  let nextDisclosureId = 0;
  const senderDisclosureIds = new Map();

  function button(label, action, primary = false, actionKey = '') {
    const control = node('button', `ui-button ui-button-${primary ? 'primary' : 'secondary'}`, label);
    control.type = 'button';
    if (actionKey) control.dataset.remoteAction = actionKey;
    if (action) control.addEventListener('click', action);
    return control;
  }

  const read = () => requestJson(ROOT, { csrf: false, signal });
  const write = (path, method, body, requestSignal = signal) => requestJson(ROOT + path,
    { method, body, signal: requestSignal, timeoutMs: 30000, page: false });
  const statusLabel = (value) => stateKey(value) === 'PENDING' ? 'Setting up' :
    String(value || 'UNKNOWN').replaceAll('_', ' ').toLowerCase()
      .replace(/^./, (letter) => letter.toUpperCase());
  const stateKey = (value) => String(value || 'UNKNOWN').toUpperCase();
  const statusTone = (value) => ['CONNECTED', 'LISTENING', 'READY'].includes(stateKey(value)) ? 'success' :
    ['ERROR', 'MISSING', 'AUTHENTICATION_FAILED', 'UNSUPPORTED'].includes(stateKey(value)) ? 'danger' :
    ['DEGRADED', 'DISCONNECTED'].includes(stateKey(value)) ? 'warning' : 'neutral';
  const status = (value) => uiStatus(statusLabel(value), statusTone(value));
  const age = (milliseconds) => {
    const value = Number(milliseconds);
    if (!Number.isFinite(value) || value <= 0) return 'Never';
    const seconds = Math.max(0, Math.round((Date.now() - value) / 1000));
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
    return `${Math.floor(seconds / 86400)}d ago`;
  };
  const frequency = (hz) => Number(hz) > 0 ? `${(Number(hz) / 1_000_000).toFixed(5)} MHz` : 'Unavailable';
  const protocolLabel = (value) => ({ P25_PHASE1: 'P25 Phase 1', P25_PHASE2: 'P25 Phase 2' })[value] ||
    String(value || 'P25').replaceAll('_', ' ');
  const notice = (text, tone = '') => node('div', tone ? `ui-notice ui-notice-${tone}` : 'ui-feedback', text);
  const showError = (target, error) => target.replaceChildren(notice(error?.message || 'The request failed', 'danger'));
  const pathPart = (value) => encodeURIComponent(String(value));

  function heading(title, glyph, stateValue = null) {
    const header = node('header', 'remote-links-card-header ui-section-title');
    const identity = node('div', 'remote-links-direction-title');
    const icon = node('span', 'ui-record-card-icon');
    icon.append(iconGlyph(glyph));
    identity.append(icon, node('h2', 'ui-section-heading', title));
    header.append(identity);
    if (stateValue) header.append(status(stateValue));
    return header;
  }

  function facts(items) {
    const list = node('dl', 'ui-fact-list remote-links-facts');
    items.forEach(([label, value]) => {
      list.append(node('dt', '', label), node('dd', '', value == null || value === '' ? '—' : String(value)));
    });
    return list;
  }

  function feedFacts(feed) {
    const items = [feed.wacn != null ? ['WACN', feed.wacn] : null,
      feed.system != null ? ['System ID', feed.system] : null,
      feed.rfss != null ? ['RFSS', feed.rfss] : null, feed.site != null ? ['Site', feed.site] : null].filter(Boolean);
    if (feed.lag_milliseconds != null) items.push(['Link delay', `${Math.max(0, Number(feed.lag_milliseconds))}ms`],
      ['Sequence gaps', feed.sequence_gap_count || 0]);
    return items;
  }

  function directionFlow(receive) {
    const flow = node('div', 'remote-links-flow ui-surface-inset');
    const endpoint = (label, detail, glyph, local = false) => {
      const host = node('div', 'remote-links-endpoint');
      const icon = node('span', local ? 'ui-icon-tile' : 'ui-record-card-icon');
      icon.append(iconGlyph(glyph));
      const copy = node('div', 'ui-heading-group');
      copy.append(node('strong', '', label), node('small', 'muted', detail));
      host.append(icon, copy);
      return host;
    };
    const arrow = node('span', 'ui-record-card-icon');
    arrow.append(iconGlyph('icon-arrow-right'));
    flow.append(receive ? endpoint('Other installations', 'Send their P25 feeds', 'icon-network-visualizer') :
      endpoint('This receiver', 'Your local P25 feeds', 'icon-radio-tower', true), arrow,
    receive ? endpoint('This receiver', 'Receives remote feeds', 'icon-radio-tower', true) :
      endpoint('Destination receiver', 'One other installation', 'icon-server'));
    return flow;
  }

  function drawListener(listener) {
    const card = node('section', 'remote-links-card ui-surface');
    card.dataset.remoteDirection = 'receive';
    card.setAttribute('aria-label', 'Receive here');
    card.append(heading('Receive here', 'icon-download', listener.state));
    const body = node('div', 'ui-section-body');
    const connection = node('div', 'remote-links-listener-controls');
    const address = ['0.0.0.0', '::', '0:0:0:0:0:0:0:0'].includes(listener.bind_address) ?
      'All network interfaces' : listener.bind_address || 'All network interfaces';
    connection.append(node('span', 'muted', listener.enabled ? `${address} · Port ${listener.port}` :
      'Receiving disabled'), button('Receive settings', openListener, false, 'listener'));
    body.append(directionFlow(true), connection);
    const rows = Array.isArray(listener.dependencies) ? listener.dependencies : [];
    if (rows.length) {
      const dependencies = node('div', 'remote-links-dependencies');
      rows.forEach((dependency) => {
        const item = node('div', 'remote-links-dependency');
        item.append(node('span', '', dependency.label || dependency.id || 'Dependency'), status(dependency.state));
        if (dependency.status_message) item.append(node('small', 'muted', dependency.status_message));
        dependencies.append(item);
      });
      body.append(dependencies);
    }
    body.append(senderHeader, senderHost);
    if (listener.bind_address === '127.0.0.1' || listener.bind_address === '::1') {
      body.append(notice('This address accepts connections only from this receiver. Choose its VPN address to receive remote feeds.',
        'warning'));
    } else if (listener.enabled && ['0.0.0.0', '::', '0:0:0:0:0:0:0:0'].includes(listener.bind_address)) {
      body.append(notice('Restrict access with a VPN or firewall; authentication does not encrypt traffic.',
        'warning'));
    }
    if (listener.status_message) body.append(notice(listener.status_message,
      stateKey(listener.state) === 'ERROR' ? 'danger' : 'warning'));
    card.append(body);
    return card;
  }

  function drawSenderConnection(connection) {
    const selected = Array.isArray(connection.exported_channel_configuration_ids) ?
      connection.exported_channel_configuration_ids.length : 0;
    const card = node('section', 'remote-links-card ui-surface');
    card.dataset.remoteDirection = 'send';
    card.setAttribute('aria-label', 'Send from here');
    const destination = connection.destination_host && connection.destination_port ?
      `${connection.destination_host}:${connection.destination_port}` : 'Not configured';
    const details = facts([
      ['Destination', destination],
      ['Selected feeds', selected],
      ['Authentication', connection.credential_configured ? 'Credential saved' : 'Not configured'],
      ['Last connected', age(connection.last_connected_at_ms)]
    ]);
    card.append(heading('Send from here', 'icon-upload', connection.state));
    const body = node('div', 'ui-section-body');
    body.append(directionFlow(false));
    if (!connection.destination_host) body.append(node('p', 'ui-section-note',
      'Create a trusted sender on the destination receiver, then enter its credentials here and choose the local P25 feeds to send.'));
    else body.append(details);
    if (connection.status_message) body.append(notice(connection.status_message,
      ['ERROR', 'AUTHENTICATION_FAILED'].includes(stateKey(connection.state)) ? 'danger' : 'warning'));
    const actions = node('footer', 'remote-links-card-actions ui-action-row');
    actions.append(button(connection.destination_host ? 'Sending settings' : 'Set up sending',
      openSenderConnection, !connection.destination_host, 'sender-connection'));
    body.append(actions);
    card.append(body);
    return card;
  }

  function drawFeed(sender, feed) {
    const card = node('article', 'remote-links-feed-card ui-record-section');
    const header = node('header', 'remote-links-feed-header');
    const title = node('div', 'remote-links-feed-title');
    title.append(remoteOriginIcon({ remote: true }), node('strong', '', feed.display_name || feed.advertised_name || feed.feed_id));
    header.append(title);
    if (stateKey(feed.state) !== 'CONNECTED') header.append(status(feed.state));
    const managed = Boolean(feed.channel_configuration_id);
    const identity = [protocolLabel(feed.protocol), frequency(feed.frequency_hz)].filter(Boolean).join(' · ');
    const catalog = [feed.system_name, feed.site_name].filter(Boolean).join(' · ');
    const detail = node('div', 'remote-links-feed-detail muted ui-label');
    detail.append(node('span', '', identity));
    if (catalog) detail.append(node('span', '', catalog));
    if (!managed || !feed.enabled) detail.append(node('span', 'muted', managed ? 'Managed locally · disabled' :
      stateKey(feed.state) === 'UNSUPPORTED' ? 'This feed is not supported by this version.' :
        stateKey(feed.state) === 'REMOVED' ? 'No longer advertised by the sender.' :
          'Setting up a local channel.'));
    if (feed.status_message) detail.append(node('span', statusTone(feed.state) === 'danger' ?
      'ui-status-danger' : 'muted', feed.status_message));
    card.append(header, detail);
    const diagnostics = feedFacts(feed);
    if (!managed && diagnostics.length) card.append(facts(diagnostics));
    if (managed) card.append(button('Manage feed', () => openFeed(sender, feed), false,
      `feed:${sender.sender_id}:${feed.feed_id}`));
    return card;
  }

  function drawSender(sender, index) {
    const card = node('article', 'remote-links-sender-card ui-record-section');
    const feeds = Array.isArray(sender.feeds) ? sender.feeds : [];
    const name = sender.display_name || sender.sender_id;
    if (!expandedSenders.has(sender.sender_id)) expandedSenders.set(sender.sender_id, index === 0);
    if (!senderDisclosureIds.has(sender.sender_id)) senderDisclosureIds.set(sender.sender_id,
      `remote-links-sender-feeds-${++nextDisclosureId}`);
    const header = node('header', 'remote-links-sender-header ui-record-card-header');
    const toggle = node('button', 'ui-disclosure-toggle');
    toggle.type = 'button';
    toggle.dataset.remoteAction = `toggle-sender:${sender.sender_id}`;
    toggle.setAttribute('aria-expanded', String(expandedSenders.get(sender.sender_id)));
    const copy = node('span', 'ui-disclosure-copy');
    copy.append(node('strong', 'remote-links-sender-name', name), node('small', '', `${feeds.length} feed${feeds.length === 1 ? '' : 's'}`));
    toggle.append(copy);
    const settings = button('Settings', () => openSender(sender), false, `sender:${sender.sender_id}`);
    settings.setAttribute('aria-label', `Settings for ${name}`);
    header.append(toggle, status(sender.state), settings);
    const feedHost = node('div', 'remote-links-feed-grid ui-record-card-body');
    feedHost.id = senderDisclosureIds.get(sender.sender_id);
    feedHost.hidden = !expandedSenders.get(sender.sender_id);
    toggle.setAttribute('aria-controls', feedHost.id);
    toggle.addEventListener('click', () => {
      const expanded = !expandedSenders.get(sender.sender_id);
      expandedSenders.set(sender.sender_id, expanded);
      toggle.setAttribute('aria-expanded', String(expanded));
      feedHost.hidden = !expanded;
    });
    if (sender.status_message) feedHost.append(notice(sender.status_message,
      stateKey(sender.state) === 'ERROR' ? 'danger' : 'warning'));
    if (feeds.length) feedHost.append(...feeds.map((feed) => drawFeed(sender, feed)));
    else feedHost.append(node('div', 'ui-feedback', 'No feeds have been advertised by this sender.'));
    card.append(header, feedHost);
    return card;
  }

  function draw(value) {
    snapshot = value;
    restartNotice.hidden = snapshot.restart_required !== true;
    const senders = Array.isArray(snapshot.senders) ? snapshot.senders : [];
    const connectedCount = senders.filter((sender) => stateKey(sender.state) === 'CONNECTED').length;
    const feedCount = senders.reduce((count, sender) => count + (Array.isArray(sender.feeds) ? sender.feeds.length : 0), 0);
    senderCount.textContent = `${connectedCount} connected sender${connectedCount === 1 ? '' : 's'} · ` +
      `${feedCount} feed${feedCount === 1 ? '' : 's'}`;
    const activeIds = new Set(senders.map(sender => sender.sender_id));
    for (const id of expandedSenders.keys()) if (!activeIds.has(id)) {
      expandedSenders.delete(id); senderDisclosureIds.delete(id);
    }
    if (senders.length) senderHost.replaceChildren(...senders.map(drawSender));
    else {
      const empty = node('div', 'ui-empty-state remote-links-empty');
      empty.append(iconGlyph('icon-network-visualizer'), node('h3', '', 'No trusted senders'),
        node('p', '', 'Add a sender to create credentials for the installation that will send P25 feeds here.'));
      senderHost.replaceChildren(empty);
    }
    directionPanels.get('receive').replaceChildren(restartNotice, drawListener(snapshot.listener || {}));
    directionPanels.get('send').replaceChildren(drawSenderConnection(snapshot.sender_connection || {}));
    freshness.textContent = 'Status updated just now';
  }

  function present(value, force = false) {
    snapshot = value;
    // A press in progress must finish on the same control; ordinary focus does not pause status updates.
    const focused = host.contains(document.activeElement) ? document.activeElement.dataset.remoteAction : '';
    if ((pointerActivation || keyboardActivation) && !force) pendingSnapshot = value;
    else {
      pendingSnapshot = null;
      draw(value);
      if (focused) [...host.querySelectorAll('[data-remote-action]')]
        .find((control) => control.dataset.remoteAction === focused)?.focus();
    }
  }

  function flushPending() {
    if (disposed || modalActive || pointerActivation || keyboardActivation || !pendingSnapshot) return;
    const value = pendingSnapshot;
    pendingSnapshot = null;
    present(value, true);
  }

  host.addEventListener('pointerdown', (event) => {
    if (event.isPrimary && event.button === 0 && event.target.closest('button[data-remote-action]')) {
      window.clearTimeout(activationReleaseTimer);
      pointerActivation = true;
    }
  });
  const pointerUp = () => {
    if (!pointerActivation) return;
    // Click follows pointerup. The timer is a fallback for a release that produces no click.
    activationReleaseTimer = window.setTimeout(() => { pointerActivation = false; flushPending(); }, 0);
  };
  const pointerCancel = () => { pointerActivation = false; flushPending(); };
  document.addEventListener('pointerup', pointerUp);
  document.addEventListener('pointercancel', pointerCancel);
  host.addEventListener('click', () => {
    if (!pointerActivation) return;
    window.clearTimeout(activationReleaseTimer);
    pointerActivation = false;
    flushPending();
  });
  host.addEventListener('keydown', (event) => {
    if (['Enter', ' '].includes(event.key) && event.target.closest('button[data-remote-action]')) {
      window.clearTimeout(activationReleaseTimer);
      keyboardActivation = event.key;
    }
  });
  const keyUp = (event) => {
    if (event.key !== keyboardActivation) return;
    keyboardActivation = '';
    // Space activates on keyup, so let its click finish before replacing controls.
    activationReleaseTimer = window.setTimeout(flushPending, 0);
  };
  document.addEventListener('keyup', keyUp);

  async function refresh(force = false) {
    if (disposed || loading || modalActive) return;
    loading = true;
    try {
      const value = await read();
      if (!disposed) {
        // Keep the editor's opening revision stable until its mutation completes.
        if (modalActive) pendingSnapshot = value;
        else present(value, force);
        message.replaceChildren();
        message.hidden = true;
      }
    } catch (error) {
      if (!disposed && error?.name !== 'AbortError') {
        showError(message, error);
        message.hidden = false;
        freshness.textContent = 'Status unavailable · retrying';
      }
    } finally { loading = false; }
  }

  async function poll() {
    if (!document.hidden) await refresh();
    if (!disposed) timer = window.setTimeout(poll, POLL_MS);
  }

  function openFormModal(title, form, options = {}) {
    modalActive = true;
    const modal = openReadOnlyModal(title, form, {
      id: options.id || 'remote-links-editor',
      className: `remote-links-modal${options.modalSize ? ` remote-links-modal-${options.modalSize}` : ''}`,
      cleanup: () => {
        modalActive = false;
        options.cleanup?.();
        queueMicrotask(() => { if (!disposed) void refresh(true); });
      }
    });
    if (!modal) modalActive = false;
    else if (modal.ready) void modal.ready.then((shown) => {
      if (shown && modal.dialog.isConnected) modalActive = true;
    });
    return modal;
  }

  function input(type, value = '') {
    const control = node('input', type === 'checkbox' ? 'ui-selection-check' : 'ui-input');
    control.type = type;
    control.value = value ?? '';
    return control;
  }

  function selectAlias(value, allowEmpty = false) {
    const control = node('select', 'ui-select');
    if (allowEmpty) {
      const option = node('option', '', 'Use host default');
      option.value = '';
      control.append(option);
    }
    (snapshot.alias_lists || []).forEach((item) => {
      const option = node('option', '', item.name || `Alias List ${item.alias_list_id}`);
      option.value = String(item.alias_list_id);
      control.append(option);
    });
    control.value = value == null ? '' : String(value);
    return control;
  }

  function submitModal(title, fields, saveLabel, operation, options = {}) {
    const form = node('form', 'remote-links-editor');
    const localMessage = node('div');
    localMessage.setAttribute('role', 'status');
    localMessage.hidden = true;
    const body = node('div', `remote-links-fields${options.fieldLayout ? ` remote-links-fields-${options.fieldLayout}` : ''}`);
    body.append(...fields);
    const cancel = button('Cancel');
    const save = button(saveLabel, null, true);
    save.type = 'submit';
    const danger = options.danger || null;
    form.append(body, localMessage, modalFooter(danger, cancel, save));
    const modal = openFormModal(title, form, options);
    if (!modal) return null;
    cancel.addEventListener('click', modal.close);
    const workflow = createFormWorkflow({ form, submit: save, modal,
      renderFeedback: (state, text) => {
        localMessage.setAttribute('role', state === 'error' ? 'alert' : 'status');
        localMessage.hidden = !text;
        if (state === 'error') showError(localMessage, { message: text });
        else localMessage.replaceChildren(...(text ? [state === 'status' ?
          node('div', 'ui-form-state', text) : notice(text)] : []));
      } });
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (modal.state?.isBusy?.()) return;
      await workflow.save(() => operation(form, modal), { onSuccess: async (result) => {
        if (result === false) {
          workflow.setReady(false);
          workflow.setDirty(true);
          return;
        }
        if (modal.close()) { if (result?.revision) present(result, true); else await refresh(true); }
      } });
    });
    return { form, modal, message: localMessage, save };
  }

  function openListener() {
    const current = snapshot.listener || {};
    const enabled = uiToggleField('Enable receiving', Boolean(current.enabled),
      'Enable receiving', 'Accept decoded P25 feeds from trusted senders.');
    const address = input('text', current.bind_address || '0.0.0.0');
    address.required = true; address.maxLength = 255;
    const port = input('number', current.port || 53800);
    port.required = true; port.min = '1'; port.max = '65535'; port.step = '1';
    submitModal('Receive settings', [enabled, formField('Bind address', address,
      'Use a VPN address or restrict access with a firewall. 0.0.0.0 listens on every interface; authentication does not encrypt traffic.'),
    formField('Port', port)],
    'Save listener', () => write('/listener', 'PUT', { revision: snapshot.revision,
      enabled: enabled.querySelector('input').checked, bind_address: address.value.trim(), port: Number(port.value) }),
    { modalSize: 'compact', fieldLayout: 'network' });
  }

  function openSenderConnection() {
    const current = snapshot.sender_connection || {};
    const enabled = uiToggleField('Enable sending', Boolean(current.enabled),
      'Enable sending', 'Send selected local P25 feeds to one destination receiver.');
    const destination = input('text', current.destination_host || '');
    destination.maxLength = 255;
    const port = input('number', current.destination_port || 53800);
    port.required = true; port.min = '1'; port.max = '65535'; port.step = '1';
    const senderId = input('text', current.sender_id || '');
    senderId.maxLength = 36; senderId.autocomplete = 'off';
    const secret = input('password', '');
    secret.maxLength = 256; secret.autocomplete = 'new-password';
    secret.placeholder = current.credential_configured ? 'Leave blank to keep saved secret' : 'Paste host-issued secret';
    const choices = node('fieldset', 'remote-links-export-list ui-form-section');
    choices.append(node('legend', '', 'Local P25 feeds to send'));
    const selected = new Set(current.exported_channel_configuration_ids || []);
    const options = snapshot.export_channel_options || [];
    if (!options.length) choices.append(node('p', 'muted', 'No saved P25 trunked channels are eligible for export.'));
    options.forEach((option) => {
      const label = node('label', 'remote-links-export-choice');
      const control = input('checkbox');
      control.value = option.channel_configuration_id;
      control.checked = selected.has(option.channel_configuration_id);
      label.append(control, node('span', '', option.name || option.site_name || option.channel_configuration_id),
        node('small', 'muted', [option.system_name, option.site_name, option.protocol].filter(Boolean).join(' · ')));
      choices.append(label);
    });
    const senderEnabled = enabled.querySelector('input');
    const syncRequired = () => {
      destination.required = senderEnabled.checked;
      senderId.required = senderEnabled.checked;
      secret.required = senderEnabled.checked && !current.credential_configured;
    };
    senderEnabled.addEventListener('change', syncRequired);
    syncRequired();
    submitModal('Sending settings', [notice('On the destination receiver, open Remote Links → Receive here → Add sender. Enter the credentials it provides below.'),
      enabled, formField('Destination host', destination),
      formField('Destination port', port), formField('Sender ID', senderId,
        'Generated by the destination host.'), formField('Shared secret', secret,
        current.credential_configured ? 'A credential is already saved. Enter a new one only to replace it.' :
          'The destination host shows this secret once.'), choices], 'Save sender', () =>
      write('/sender-connection', 'PUT', { revision: snapshot.revision,
        enabled: enabled.querySelector('input').checked, destination_host: destination.value.trim(),
        destination_port: Number(port.value), sender_id: senderId.value.trim(), secret: secret.value,
        exported_channel_configuration_ids: [...choices.querySelectorAll('input:checked')].map((item) => item.value) }));
  }

  function openCreateSender() {
    const name = input('text');
    name.required = true; name.maxLength = 120; name.placeholder = 'Example: Mountain receiver';
    const editor = submitModal('Add sender', [formField('Sender name', name,
      'Use a name that identifies the sending installation.'),
    notice('Enter the new credentials in Remote Links → Send from here on the sending installation.')], 'Create credential', async () => {
      const result = await write('/senders', 'POST', { revision: snapshot.revision, display_name: name.value.trim() });
      editor.modal.setDirty(true);
      editor.modal.setDiscardMessage('Close this one-time credential? The shared secret will not be shown again.');
      editor.modal.setTitle('Credential created');
      editor.modal.dialog.classList.remove('remote-links-modal-compact');
      const credential = node('div', 'remote-links-credential');
      const reminder = notice('Copy this credential now. The shared secret will not be shown again.', 'warning');
      reminder.setAttribute('role', 'alert');
      reminder.tabIndex = -1;
      credential.append(reminder);
      const copyStatus = node('small', 'ui-field-detail');
      copyStatus.setAttribute('role', 'status');
      copyStatus.setAttribute('aria-live', 'polite');
      copyStatus.hidden = true;
      [['Sender ID', result.sender_id], ['Shared secret', result.secret]].forEach(([label, value]) => {
        const row = node('div', 'remote-links-credential-row');
        const text = input('text', value);
        text.readOnly = true;
        const copy = button('Copy', async () => {
          try {
            await navigator.clipboard.writeText(value);
            copyStatus.textContent = `${label} copied.`;
            if (label === 'Shared secret') editor.modal.setDirty(false);
          } catch (_) {
            text.focus(); text.select();
            copyStatus.textContent = `Clipboard access failed. ${label} is selected; copy it manually.`;
          }
          copyStatus.hidden = false;
        });
        copy.prepend(iconGlyph('icon-copy'));
        copy.setAttribute('aria-label', `Copy ${label}`);
        row.append(formField(label, text), copy);
        credential.append(row);
      });
      credential.append(copyStatus);
      const done = button('Done', () => { editor.modal.close(); }, true);
      const workflowBody = editor.form.querySelector(':scope > .ui-form-workflow-body');
      workflowBody.replaceChildren(credential);
      editor.form.replaceChildren(workflowBody, modalFooter(done));
      editor.modal.setBusy(false);
      reminder.focus();
      return false;
    }, { modalSize: 'compact', fieldLayout: 'single' });
  }

  function openSender(sender) {
    const name = input('text', sender.display_name || '');
    name.required = true; name.maxLength = 120;
    const alias = selectAlias(sender.default_alias_list_id, true);
    const remove = button('Remove sender');
    remove.className = 'ui-button ui-button-danger';
    const editor = submitModal('Sender settings', [facts([
      ['Sender ID', sender.sender_id], ['Last seen', age(sender.last_seen_at_ms)]
    ]), formField('Sender name', name),
      formField('Preferred P25 Alias List', uiSelectFrame(alias),
        'Used for new feeds. If blank, this receiver chooses Default P25 or the first available P25 Alias List.')],
    'Save sender', () => write(`/senders/${pathPart(sender.sender_id)}`, 'PUT', {
      revision: snapshot.revision, display_name: name.value.trim(),
      default_alias_list_id: alias.value ? Number(alias.value) : null
    }), { danger: remove, fieldLayout: 'single' });
    if (!editor) return;
    let removing = false;
    remove.addEventListener('click', async () => {
      if (removing || editor.modal.state?.isBusy?.()) return;
      removing = true;
      const disabled = [...editor.form.elements].map(control => [control, control.disabled]);
      disabled.forEach(([control]) => { control.disabled = true; });
      editor.modal.setBusy(true);
      try {
        if (!await confirmAction(`Remove ${sender.display_name || sender.sender_id}? This removes the sender and all of its remote channels from this receiver.`, {
          title: 'Remove trusted sender', confirmLabel: 'Remove sender'
        }) || !editor.modal.dialog.isConnected) return;
        const result = await write(`/senders/${pathPart(sender.sender_id)}`, 'DELETE',
          { revision: snapshot.revision });
        editor.modal.setDirty(false); editor.modal.setBusy(false);
        if (editor.modal.close()) present(result, true);
      } catch (error) {
        if (editor.modal.dialog.isConnected) {
          editor.message.hidden = false;
          editor.message.setAttribute('role', 'alert');
          showError(editor.message, error);
        }
      } finally {
        removing = false;
        editor.modal.setBusy(false);
        disabled.forEach(([control, wasDisabled]) => { control.disabled = wasDisabled; });
      }
    });
  }

  function openFeed(sender, feed) {
    if (!feed.channel_configuration_id) return;
    const name = input('text', feed.display_name || feed.advertised_name || '');
    name.required = true; name.maxLength = 120;
    const alias = selectAlias(feed.alias_list_id, false);
    alias.required = true;
    const enabled = uiToggleField('Enable this remote channel', Boolean(feed.enabled),
      'Enable this remote channel', 'Disabled feeds remain configured but do not process traffic.');
    const path = `/senders/${pathPart(sender.sender_id)}/feeds/${pathPart(feed.feed_id)}`;
    const identity = feedFacts(feed);
    submitModal('Remote feed', [
      notice(`${protocolLabel(feed.protocol)} · ${frequency(feed.frequency_hz)} · advertised by ${sender.display_name || sender.sender_id}`),
      ...(identity.length ? [facts(identity)] : []), formField('Local channel name', name), formField('Alias List', uiSelectFrame(alias),
        'Aliases, streaming, recording, and activity use this host’s Alias List.'), enabled
    ], 'Save feed', () => write(path, 'PUT', {
      revision: snapshot.revision, display_name: name.value.trim(), alias_list_id: Number(alias.value),
      enabled: enabled.querySelector('input').checked
    }), { fieldLayout: 'single' });
  }

  function close() {
    disposed = true;
    window.clearTimeout(timer);
    window.clearTimeout(activationReleaseTimer);
    document.removeEventListener('pointerup', pointerUp);
    document.removeEventListener('pointercancel', pointerCancel);
    document.removeEventListener('keyup', keyUp);
  }

  signal?.addEventListener('abort', close, { once: true });
  void poll();
  return { element: host, close };
}
