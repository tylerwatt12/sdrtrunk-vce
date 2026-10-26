import { createActionWorkflow } from '../core/action-workflows.js?v=1';

const ROOT = '/api/v1/admin/streaming';
const POLL_MS = 3000;

// Reuse map: data-workspace, ui-catalog-toolbar, ui-surface, ui-metric and ui-status
// compose the destination cards; shared table, feedback, fields, toggles, select frames,
// segmented tabs, selection bar and modal lifecycle compose the editor. Only feature
// geometry lives in streaming.css; desktop/mobile and light/dark use shared tokens.
export function createStreamingWorkspace(deps) {
  const { node, formField, uiSelectFrame, uiToggleField, uiStatus, uiSegmentedControl, table,
    openReadOnlyModal, confirmAction, requestJson, modalFooter, metrics, formatNumber, href,
    browsingWorkflows, connectRadioReference, radioReferenceAllowed = true, signal: parentSignal,
    guideMode = false } = deps;
  const lifetime = new AbortController();
  const signal = lifetime.signal;
  const host = node('div', 'streaming-page data-workspace');
  const toolbar = node('div', 'streaming-toolbar streaming-page-toolbar ui-catalog-toolbar');
  const summary = node('div', 'streaming-summary');
  const actions = node('div', 'ui-action-row streaming-page-actions');
  const message = node('div');
  message.setAttribute('role', 'status');
  const destinationHost = node('div', 'streaming-destination-grid');
  const note = node('p', 'muted', 'Delivery counters belong to the current sender session and reset when the destination restarts.');
  const freshness = node('span', 'muted');
  const pageMeta = node('footer', 'streaming-page-meta');
  let catalog = null, timer = null, loading = false, disposed = false, signature = '', modalActive = false;
  let pendingAction = Promise.resolve(), editor = null, feedBrowser = null;
  const ownedDialogs = new Set();
  const markGuide = (element, id) => {
    element.dataset.guideTarget = `st-${id}`;
    return element;
  };
  const perform = operation => { pendingAction = Promise.resolve(operation()); return pendingAction; };
  const cells = new Map();
  const button = (label, action, primary = false) => {
    const control = node('button', `ui-button ui-button-${primary ? 'primary' : 'secondary'}`, label);
    control.type = 'button';
    if (action) control.addEventListener('click', action);
    return control;
  };
  const feedback = (text, tone = '') => node('div', tone === 'warning' ? 'ui-notice ui-notice-warning' :
    `ui-feedback${tone === 'error' || tone === 'loading' ? ` ui-feedback-${tone}` : ''}`, text);
  const input = (type = 'text') => { const value = node('input', 'ui-input'); value.type = type; return value; };
  const select = (values, current = '') => {
    const control = node('select', 'ui-select');
    values.forEach(([value, label]) => { const option = node('option', '', label); option.value = value; control.append(option); });
    control.value = current;
    return control;
  };
  const read = (path, options = {}) => requestJson(ROOT + path, { csrf: false, signal, ...options });
  const write = (path, method, body, options = {}) => requestJson(ROOT + path,
    { method, body, signal, timeoutMs: 65000, ...options });
  const statusTone = (row) => row.attention ? 'danger' : row.state === 'CONNECTED' ? 'success' : 'neutral';
  const errorTone = (row) => Number(row.errors) > 0 ? (row.attention ? 'danger' : 'warning') : 'blue';
  const count = (value) => value == null ? '—' : formatNumber(value);
  const showError = (target, error) => {
    target.replaceChildren(feedback(error.message || 'The request failed', 'error'));
    target.hidden = false;
  };

  function draw(documentValue) {
    catalog = documentValue;
    const rows = catalog.destinations || [];
    summary.replaceChildren(uiStatus(`${rows.length} destination${rows.length === 1 ? '' : 's'}`, 'neutral'),
      uiStatus(`${rows.filter(row => row.state === 'CONNECTED').length} connected`, 'success'),
      uiStatus(`${rows.filter(row => row.attention).length} need attention`, rows.some(row => row.attention) ? 'danger' : 'neutral'),
      uiStatus(`${rows.filter(row => !row.enabled).length} disabled`, 'neutral'));
    const nextSignature = JSON.stringify(rows.map(row => [row.configuration_id, row.name, row.provider, row.enabled]));
    if (signature !== nextSignature || !destinationHost.childElementCount) {
      signature = nextSignature;
      cells.clear();
      const cell = (row, key) => {
        const element = node('span');
        if (!cells.has(row.configuration_id)) cells.set(row.configuration_id, {});
        cells.get(row.configuration_id)[key] = element;
        return element;
      };
      if (!rows.length) {
        const empty = node('div', 'streaming-empty ui-surface');
        empty.append(node('div', 'ui-feedback', 'No streaming destinations configured. Add a destination to get started.'));
        destinationHost.replaceChildren(empty);
      } else {
        destinationHost.replaceChildren(...rows.map(row => {
          const card = node('article', 'streaming-destination-card ui-surface');
          if (row === rows[0]) markGuide(card, 'destinations');
          const header = node('header', 'streaming-destination-header');
          const identity = node('div', 'streaming-identity');
          const title = node('h2', 'streaming-destination-title');
          const open = button(row.name || 'Unnamed destination', () => perform(() => openEditor(row.configuration_id)));
          open.className = 'link-button streaming-destination-name';
          title.append(open);
          identity.append(title, node('small', 'muted', row.provider_label));
          const statuses = node('div', 'streaming-destination-statuses');
          statuses.append(cell(row, 'state'), cell(row, 'enabled'));
          header.append(identity, statuses);

          const counters = metrics([
            ['queued', 'Queued', 'icon-replay'], ['sent', 'Sent / uploaded', 'icon-share'],
            ['aged_off', 'Aged off', 'icon-skip'], ['errors', 'Errors', 'icon-warning']
          ].map(([key, label, icon]) => [label, row[key], cell(row, key),
            { icon, tone: key === 'errors' ? errorTone(row) : 'blue' }]), true);
          counters.classList.add('streaming-destination-metrics');
          const error = node('div', 'streaming-destination-error ui-notice ui-notice-danger');
          error.append(node('strong', '', 'Last error'), cell(row, 'last_error'));
          const footer = node('footer', 'streaming-destination-actions ui-action-row');
          const manage = button('Manage destination', () => perform(() => openEditor(row.configuration_id)));
          if (row === rows[0]) markGuide(manage, 'manage-button');
          footer.append(manage);
          card.append(header, counters, error, footer);
          return card;
        }));
      }
    }
    rows.forEach(row => {
      const target = cells.get(row.configuration_id);
      if (!target) return;
      target.state.replaceChildren(uiStatus(row.state_label, statusTone(row)));
      target.enabled.replaceChildren(uiStatus(row.enabled ? 'Enabled' : 'Disabled', row.enabled ? 'success' : 'neutral'));
      ['queued', 'sent', 'aged_off', 'errors'].forEach(key => { target[key].textContent = count(row[key]); });
      const errorsMetric = target.errors.closest('.ui-metric');
      ['blue', 'warning', 'danger'].forEach(tone => {
        errorsMetric.classList.toggle(`ui-metric-${tone}`, tone === errorTone(row));
      });
      const error = target.last_error.closest('.streaming-destination-error');
      target.last_error.textContent = row.last_error || '';
      error.hidden = !row.last_error;
    });
    freshness.textContent = 'Status updated just now';
  }

  async function refresh() {
    if (disposed || loading || modalActive) return;
    loading = true;
    try { const data = await read(''); if (!disposed) { draw(data); message.replaceChildren(); } }
    catch (error) { if (!disposed && error.name !== 'AbortError') { showError(message, error); freshness.textContent = 'Status unavailable · retrying'; } }
    finally { loading = false; }
  }
  async function poll() {
    if (!document.hidden) await refresh();
    if (!disposed) timer = setTimeout(poll, POLL_MS);
  }

  async function openEditor(id = null, initialTab = 'settings') {
    if (disposed) return;
    const content = node('div', 'streaming-editor editor-workspace ui-form-workflow ui-form-workflow-modal');
    const workflowBody = node('div', 'ui-form-workflow-body');
    workflowBody.append(feedback('Loading destination…', 'loading'));
    content.append(workflowBody);
    const abort = new AbortController();
    let alive = true, busy = false, settingsDirty = false, assignmentDirty = false, editRevision, assignmentRevision, options, definition;
    let tabs = null, statusTimer = null;
    let activeTab = initialTab, aliasLoaded = false, aliasOffset = 0, aliasTotal = 0, aliasSequence = 0;
    const changedAliases = new Map(), aliasOriginal = new Map(), controls = new Map();
    const modal = openReadOnlyModal(id ? 'Streaming destination' : 'Add streaming destination', content, {
      id: 'streaming-destination', className: 'streaming-editor-modal',
      cleanup: () => {
        alive = false; abort.abort(); clearTimeout(statusTimer); modalActive = false;
        ownedDialogs.delete(modal); if (editor?.modal === modal) editor = null;
        void refresh();
      }
    });
    if (modal) ownedDialogs.add(modal);
    if (!modal || (modal.ready && !await modal.ready)) return;
    if (disposed) { modal.close(); return; }
    modalActive = true;
    const requestOptions = { signal: abort.signal, page: false };
    const localMessage = node('div');
    localMessage.setAttribute('role', 'status');
    localMessage.hidden = true;
    const dirty = () => {
      modal.setDirty(settingsDirty || assignmentDirty);
      localMessage.replaceChildren();
      localMessage.hidden = true;
      reload.hidden = true;
    };
    const settingsPanel = node('section', 'streaming-panel');
    const aliasesPanel = node('section', 'streaming-panel');
    const statusPanel = node('section', 'streaming-panel');
    const panels = { settings: settingsPanel, aliases: aliasesPanel, status: statusPanel };
    const save = markGuide(button('Save settings', () => perform(saveActive), true), 'save-assignments');
    save.classList.add('ui-form-submit');
    const formState = node('span', 'ui-form-state');
    formState.setAttribute('role', 'status');
    formState.setAttribute('aria-live', 'polite');
    formState.hidden = true;
    const test = button('Test connection', () => void run(async () => {
      const result = await write('/test', 'POST', { configuration_id: id, provider: definition.provider, settings: readChanges() }, requestOptions);
      if (alive) {
        localMessage.replaceChildren(feedback(result.message, result.success ? 'success' : 'error'));
        localMessage.hidden = false;
      }
    }, 'Testing connection…'));
    const cancel = markGuide(button('Cancel', modal.close), 'add-cancel');
    const remove = button('Delete destination', () => void removeDestination());
    remove.classList.remove('ui-button-secondary');
    remove.classList.add('ui-button-danger');
    const reload = button('Reload current values', () => {
      const reopen = () => void openEditor(id, activeTab);
      if (modal.close(reopen)) reopen();
    });
    reload.hidden = true;
    const footer = modalFooter(formState, test, remove, cancel, save);
    const providerControl = select([], '');
    markGuide(providerControl, 'provider');
    providerControl.setAttribute('aria-label', 'Provider');
    const fields = node('div', 'streaming-fields');
    const advanced = node('details', 'streaming-advanced');
    const advancedFields = node('div', 'streaming-fields');
    advanced.append(node('summary', '', 'Advanced settings'), advancedFields);
    const warning = feedback('Saving restarts this destination. Queued calls may be discarded and session counters reset. Receiving continues.', 'warning');
    const aliasSearch = input('search');
    aliasSearch.placeholder = 'Search name, identifier, or Alias List';
    const assignedOnly = select([['false', 'All aliases'], ['true', 'Assigned only']], 'false');
    const aliasRows = node('div');
    const aliasCount = node('span', 'muted');
    const aliasPager = browsingWorkflows.createBrowsingPager({ node,
      className: 'streaming-alias-pager', ariaLabel: 'Alias pages' });
    const drawAliasPager = (loading = false) => aliasPager.update({
      countText: browsingWorkflows.pageRangeText({ offset: aliasOffset, visible: visibleAliases.length,
        total: aliasTotal, label: 'Aliases', format: formatNumber }),
      previous: { enabled: !loading && aliasOffset > 0, onClick: () => {
        aliasOffset = Math.max(0, aliasOffset - 50); void loadAliases();
      } },
      next: { enabled: !loading && aliasOffset + 50 < aliasTotal, onClick: () => {
        aliasOffset += 50; void loadAliases();
      } }
    });
    const aliasQuery = node('form', 'streaming-alias-filters ui-catalog-toolbar');
    const searchButton = button('Search'); searchButton.type = 'submit';
    searchButton.classList.add('streaming-alias-search');
    const queryFields = [formField('Search aliases', aliasSearch), formField('Show', uiSelectFrame(assignedOnly))];
    queryFields.forEach(field => field.classList.add('streaming-query-field'));
    aliasQuery.append(...queryFields, searchButton);
    aliasQuery.addEventListener('submit', event => { event.preventDefault(); aliasOffset = 0; void loadAliases(); });
    assignedOnly.addEventListener('change', () => { aliasOffset = 0; void loadAliases(); });
    const aliasActions = node('div', 'streaming-alias-actions ui-selection-bar');
    const aliasLayoutHost = node('div', 'streaming-alias-layout');
    const aliasTableController = {};
    let pageToggle = null;
    let visibleAliases = [];
    const visibleAliasChecks = () => [...aliasRows.querySelectorAll('tbody input[type="checkbox"]')];
    const syncPageToggle = () => {
      if (!pageToggle) return;
      const checked = visibleAliases.filter(row => changedAliases.has(row.id) ?
        changedAliases.get(row.id) : row.assigned).length;
      pageToggle.checked = visibleAliases.length > 0 && checked === visibleAliases.length;
      pageToggle.indeterminate = checked > 0 && checked < visibleAliases.length;
      pageToggle.disabled = visibleAliases.length === 0;
    };
    const renderPageToggle = () => {
      const checkbox = node('input', 'ui-selection-check');
      checkbox.type = 'checkbox';
      checkbox.setAttribute('aria-label', 'Send all aliases on this page to this destination');
      checkbox.addEventListener('change', () => {
        const checked = checkbox.checked;
        visibleAliasChecks().forEach(control => {
          if (control.checked === checked) return;
          control.checked = checked;
          control.dispatchEvent(new Event('change'));
        });
        syncPageToggle();
      });
      pageToggle = checkbox;
      syncPageToggle();
      return checkbox;
    };
    aliasActions.append(aliasCount, aliasLayoutHost);
    const aliasTableRegion = node('div', 'streaming-alias-table-region');
    aliasTableRegion.append(aliasActions, aliasRows);
    aliasesPanel.append(aliasQuery, aliasTableRegion, aliasPager,
      node('p', 'muted', 'These are the same assignments shown in the Alias Editor. Only your explicit changes are saved, up to 500 per save.'));

    const actionWorkflow = createActionWorkflow({ surface: content, modal, feedback: localMessage,
      renderFeedback: (state, text) => {
        localMessage.replaceChildren(...(text ? [feedback(text, state)] : []));
        localMessage.hidden = !text;
      } });
    async function run(operation, pending = 'Updating destination…') {
      if (busy || !alive) return;
      busy = true;
      reload.hidden = true;
      try {
        await actionWorkflow.run(operation, { pending, success: null,
          onSuccess: () => {
            if (localMessage.firstElementChild?.classList.contains('ui-feedback-loading')) {
              localMessage.replaceChildren(); localMessage.hidden = true;
            }
          }, onError: (error, message) => {
            if (alive && error.name !== 'AbortError') {
              showError(localMessage, { message }); reload.hidden = error.code !== 'stale_revision';
            }
          } });
      } catch (_) { /* The editor retains the draft and actionable feedback. */ }
      finally { busy = false; if (alive) syncButtons(); }
    }
    function syncButtons() {
      if (!definition) return;
      const activeDirty = activeTab === 'aliases' ? assignmentDirty : activeTab === 'settings' && settingsDirty;
      content.classList.toggle('is-dirty', activeDirty);
      formState.textContent = activeDirty ? 'Unsaved changes' : '';
      formState.hidden = !activeDirty;
      save.hidden = activeTab === 'status' || (Boolean(id) && !activeDirty);
      save.textContent = activeTab === 'aliases' ? 'Save assignments' : !id ? 'Add destination' :
        controls.get('enabled')?.checked ? 'Save and reconnect' : 'Save settings';
      save.disabled = busy || (activeTab === 'aliases' ? !assignmentDirty : id && !settingsDirty);
      test.hidden = activeTab !== 'settings' || !options.providers.find(item => item.id === definition.provider)?.connection_test;
      remove.hidden = !id;
      providerControl.disabled = busy || Boolean(id);
      tabs?.querySelectorAll('button').forEach(control => { control.disabled = busy || (!id && control.dataset.value !== 'settings'); });
    }
    function settingsEdited() {
      settingsDirty = options.providers.find(item => item.id === definition.provider).fields.some(field => {
        const control = controls.get(field.key);
        const value = field.type === 'boolean' ? control.checked : control.value;
        if (field.type === 'boolean') return value !== (definition.settings[field.key] === true);
        if (field.type === 'password') return Boolean(value);
        return String(value ?? '') !== String(definition.settings[field.key] ?? '');
      });
      dirty();
      syncButtons();
    }
    async function switchTab(value) {
      activeTab = value;
      Object.entries(panels).forEach(([key, panel]) => { panel.hidden = key !== value; });
      syncButtons();
      const aliasesReady = value === 'aliases' && !aliasLoaded && id ? loadAliases() : null;
      clearTimeout(statusTimer);
      const statusReady = value === 'status' && id ? updateStatus() : null;
      await Promise.all([aliasesReady, statusReady]);
    }
    function bindField(field, target) {
      const initial = definition.settings[field.key];
      let control;
      if (field.type === 'boolean') {
        const toggle = uiToggleField(field.label, initial === true, field.label,
          field.key === 'ignore_certificate_errors' ? 'Disables certificate validation for this destination.' : '');
        control = toggle.querySelector('input'); target.append(toggle);
      } else if (field.key === 'alias_list_id' || field.key === 'channel_configuration_id') {
        const sites = options.sites || [];
        const choices = field.key === 'alias_list_id' ? [...new Map(sites.map(site => [String(site.alias_list_id), site.alias_list_name])).entries()] :
          sites.filter(site => Number(site.alias_list_id) === Number(controls.get('alias_list_id')?.value || definition.settings.alias_list_id))
            .map(site => [site.configuration_id, site.name]);
        if (initial && !choices.some(([key]) => String(key) === String(initial))) choices.unshift([String(initial), 'Saved selection (currently unavailable)']);
        control = select([['', 'Choose…'], ...choices], String(initial || ''));
        target.append(formField(field.label, uiSelectFrame(control), field.key === 'channel_configuration_id' ?
          'A call is sent only when an alias routes it here and the selected trunked site observed it. Calls heard only on other sites are excluded.' : ''));
      } else if (field.type === 'select') {
        control = select(field.choices.map(value => [value, value.replaceAll('_', ' ').toLowerCase()]), initial);
        target.append(formField(field.label, uiSelectFrame(control)));
      } else {
        control = input(field.type === 'password' ? 'password' : field.type === 'number' ? 'number' : 'text');
        control.value = field.type === 'password' ? '' : (initial ?? '');
        if (field.type === 'number') { control.min = String(field.minimum); control.max = String(field.maximum); control.step = '1'; }
        else control.maxLength = Number(field.maximum) || 1024;
        if (field.key === 'name') control.required = true;
        if (field.type === 'password') {
          control.autocomplete = 'new-password';
          const configured = definition.configured_credentials.includes(field.key);
          control.placeholder = configured ? 'Leave blank to keep saved credential' : 'Enter credential';
          target.append(formField(field.label, control, configured ? 'Saved credential configured. Enter a value to replace it.' : 'Stored only on the receiver.'));
        } else target.append(formField(field.label, control));
      }
      control.name = field.key;
      control.setAttribute('aria-label', field.label);
      controls.set(field.key, control);
      control.addEventListener('input', settingsEdited);
      control.addEventListener('change', () => {
        if (field.key === 'alias_list_id') {
          const site = controls.get('channel_configuration_id');
          const choices = (options.sites || []).filter(row => String(row.alias_list_id) === control.value);
          site.replaceChildren();
          const empty = node('option', '', 'Choose…'); empty.value = ''; site.append(empty);
          choices.forEach(row => { const option = node('option', '', row.name); option.value = row.configuration_id; site.append(option); });
        }
        settingsEdited();
      });
    }
    function renderFields() {
      controls.clear(); fields.replaceChildren(); advancedFields.replaceChildren();
      const provider = options.providers.find(item => item.id === definition.provider);
      if (!provider) throw new Error('This provider cannot be edited.');
      provider.fields.forEach(field => bindField(field, field.advanced ? advancedFields : fields));
      if (definition.provider === 'RADIORESOLVE') fields.append(node('p', 'muted',
        'Delivery uses a durable queue: calls are kept for up to 24 hours, within a 2 GiB storage limit.'));
      advanced.hidden = !advancedFields.childElementCount;
      warning.hidden = !id;
      settingsDirty = false; dirty(); syncButtons();
    }
    function readChanges() {
      const settings = {};
      for (const field of options.providers.find(item => item.id === definition.provider).fields) {
        const control = controls.get(field.key);
        if (field.type === 'password' && !control.value) continue;
        let value = field.type === 'boolean' ? control.checked : control.value;
        if (field.type === 'number') {
          if (String(value) === String(definition.settings[field.key] ?? '')) continue;
          if (value === '' && (!id || definition.settings[field.key] == null)) continue;
          value = Number(value);
          if (!Number.isSafeInteger(value) || value < field.minimum || value > field.maximum) {
            control.focus(); throw new Error(`${field.label} is out of range`);
          }
        }
        if (value !== definition.settings[field.key] && !(value === '' && definition.settings[field.key] == null)) settings[field.key] = value;
      }
      return settings;
    }
    async function loadAliases() {
      if (!id || !alive || busy) return;
      const sequence = ++aliasSequence;
      searchButton.disabled = true;
      aliasRows.setAttribute('aria-busy', 'true');
      localMessage.replaceChildren(feedback('Loading aliases…', 'loading'));
      localMessage.hidden = false;
      drawAliasPager(true);
      try {
        const query = new URLSearchParams({ q: aliasSearch.value, assigned: assignedOnly.value, offset: aliasOffset, limit: 50 });
        const result = await read(`/${id}/aliases?${query}`, requestOptions);
        if (!alive || sequence !== aliasSequence) return;
        if (assignmentDirty && assignmentRevision !== result.revision) throw Object.assign(new Error('Assignments changed. Your edits are still here. Reload current values before saving.'), { code: 'stale_revision' });
        assignmentRevision = result.revision;
        aliasLoaded = true; aliasTotal = result.total;
        visibleAliases = result.items;
        aliasRows.replaceChildren(table(result.items, [
          { id: 'selected', label: '', layoutLabel: 'Assign aliases', essential: true, fixed: true,
            renderHeader: renderPageToggle, render: row => {
            aliasOriginal.set(row.id, row.assigned);
            const check = node('input', 'ui-selection-check');
            check.type = 'checkbox';
            check.checked = changedAliases.has(row.id) ? changedAliases.get(row.id) : row.assigned;
            check.setAttribute('aria-label', `Send ${row.name || row.identifier} to this destination`);
            check.addEventListener('change', () => {
              if (check.checked === aliasOriginal.get(row.id)) changedAliases.delete(row.id); else changedAliases.set(row.id, check.checked);
              assignmentDirty = changedAliases.size > 0; dirty(); syncButtons(); syncPageToggle();
            }); return check;
          } },
          { id: 'name', label: 'Alias', render: row => {
            const link = node('a', '', row.name || 'Unnamed alias');
            link.href = href('aliases', { list: row.alias_list_id, alias: row.id }); return link;
          } },
          { id: 'identifier', label: 'Identifier', render: row => row.identifier },
          { id: 'list', label: 'Alias List', render: row => row.alias_list_name }
        ], 'No matching aliases', { type: 'streaming-aliases', sortable: false, mobileCards: true,
          tableClass: 'ui-mobile-cards ui-data-table-quiet', layoutMenuHost: aliasLayoutHost,
          controller: aliasTableController, presentation: guideMode }));
        syncPageToggle();
        aliasCount.textContent = `${formatNumber(result.total)} ${result.total === 1 ? 'alias' : 'aliases'}`;
        localMessage.replaceChildren(); localMessage.hidden = true;
      } catch (error) { if (alive && error.name !== 'AbortError') { showError(localMessage, error); reload.hidden = error.code !== 'stale_revision'; } }
      finally { if (alive && sequence === aliasSequence) {
        searchButton.disabled = false; aliasRows.removeAttribute('aria-busy'); drawAliasPager();
      } }
    }
    function drawStatus(row) {
      if (!row) { statusPanel.replaceChildren(feedback('Destination no longer exists.', 'error')); return; }
      const counters = metrics([
        ['queued', 'Queued', 'icon-replay'], ['sent', 'Sent / uploaded', 'icon-share'],
        ['aged_off', 'Aged off', 'icon-skip'], ['errors', 'Errors', 'icon-warning']
      ].map(([key, label, icon]) => [label, row[key], count(row[key]),
        { icon, tone: key === 'errors' ? errorTone(row) : 'blue' }]));
      const facts = node('dl', 'ui-facts');
      for (const [label, value] of [['Last error', row.last_error || 'None'], ['Provider', row.provider_label]]) {
        const fact = node('div', 'ui-fact'); fact.append(node('dt', '', label), node('dd', '', value)); facts.append(fact);
        if (label === 'Last error') markGuide(fact, 'recovery-error');
      }
      markGuide(counters, 'delivery');
      statusPanel.replaceChildren(markGuide(uiStatus(row.state_label, statusTone(row)), 'recovery-state'), counters, facts,
        node('p', 'muted', 'These counts show delivery by this sender. The dashboard’s “Sent to Streamer” count shows calls handed over for delivery.'),
        button('Refresh status', () => void updateStatus()));
    }
    async function updateStatus() {
      try { if (!busy) { const result = await read(`/${id}`, requestOptions); if (alive) drawStatus(result.status); } }
      catch (error) { if (alive && error.name !== 'AbortError') showError(statusPanel, error); }
      finally {
        clearTimeout(statusTimer);
        if (!guideMode && alive && activeTab === 'status') statusTimer = setTimeout(() => void updateStatus(), POLL_MS);
      }
    }
    async function saveActive() {
      await run(async () => {
        let result;
        if (activeTab === 'aliases') {
          result = await write(`/${id}/aliases`, 'POST', { revision: assignmentRevision,
            add: [...changedAliases].filter(([, value]) => value).map(([key]) => key),
            remove: [...changedAliases].filter(([, value]) => !value).map(([key]) => key) }, requestOptions);
          aliasSequence += 1;
          assignmentRevision = result.revision; changedAliases.clear(); assignmentDirty = false; aliasLoaded = false;
        } else {
          const settings = readChanges();
          result = await write(id ? `/${id}` : '', id ? 'PUT' : 'POST', { ...(id ? { edit_revision: editRevision } : {}), provider: definition.provider, settings }, requestOptions);
          id = result.configuration_id; editRevision = result.edit_revision;
          const current = await read(`/${id}`, requestOptions);
          if (!alive) return;
          definition = current.destination; editRevision = current.edit_revision; renderFields();
          providerControl.disabled = true;
          if (tabs) tabs.querySelectorAll('button').forEach(control => { control.disabled = false; });
        }
        if (!alive) return;
        dirty();
      }, activeTab === 'aliases' ? 'Saving alias assignments…' : id ? 'Saving destination…' : 'Adding destination…');
      if (alive && activeTab === 'aliases' && !aliasLoaded) await loadAliases();
    }
    async function removeDestination() {
      await run(async () => {
        const current = await read(`/${id}`, requestOptions);
        if (!alive) return;
        const references = current.references;
        if (references.aliases || references.alias_lists.length) {
          localMessage.replaceChildren(feedback(`This destination is used by ${formatNumber(references.aliases)} aliases. Remove its assignments before deleting.`, 'warning'));
          localMessage.hidden = false;
          references.alias_lists.forEach(list => {
            const link = node('a', '', `${list.name} · ${[list.unmatched ? 'unknown calls' : '', list.new_aliases ? 'new alias defaults' : ''].filter(Boolean).join(', ')}`);
            link.href = href('aliases', { list: list.id }); localMessage.append(link);
          }); return;
        }
        if (!await confirmAction(`Delete ${definition.settings.name}? This removes its saved connection settings.`, {
          title: 'Delete streaming destination', confirmLabel: 'Delete destination'
        }) || !alive) return;
        actionWorkflow.showFeedback('loading', 'Deleting destination…');
        await write(`/${id}`, 'DELETE', { edit_revision: editRevision }, requestOptions);
        modal.setDirty(false); settingsDirty = assignmentDirty = false;
        modal.setBusy(false); modal.close();
      }, 'Checking destination usage…');
    }
    try {
      options = await read('/options', requestOptions);
      const existing = id ? await read(`/${id}`, requestOptions) : null;
      definition = existing?.destination || await read('/templates/BROADCASTIFY_CALL', requestOptions);
      editRevision = existing?.edit_revision;
      if (!alive) return;
      providerControl.replaceChildren(...options.providers.map(provider => {
        const option = node('option', '', provider.label); option.value = provider.id; return option;
      }));
      providerControl.value = definition.provider; providerControl.disabled = Boolean(id);
      providerControl.addEventListener('change', () => void run(async () => {
        definition = await read(`/templates/${providerControl.value}`, requestOptions);
        if (alive) renderFields();
      }, 'Loading provider settings…'));
      settingsPanel.append(formField('Provider', uiSelectFrame(providerControl)), fields, advanced, warning);
      tabs = uiSegmentedControl([{ value: 'settings', label: 'Settings' }, { value: 'aliases', label: 'Aliases' },
        { value: 'status', label: 'Status' }], initialTab, value => perform(() => switchTab(value)));
      tabs.setAttribute('aria-label', 'Streaming destination sections');
      markGuide(tabs, 'sections');
      markGuide(tabs.querySelector('[data-value="aliases"]'), 'aliases-button');
      markGuide(tabs.querySelector('[data-value="status"]'), 'status-button');
      markGuide(modal.dialog.querySelector('.modal-close'), 'destination-close');
      workflowBody.replaceChildren(tabs, settingsPanel, aliasesPanel, statusPanel, localMessage, reload);
      content.replaceChildren(workflowBody, footer);
      renderFields();
      if (existing) drawStatus(existing.status);
      await switchTab(initialTab);
      if (alive && !disposed) editor = { modal, tabs, aliasesPanel, save, cancel };
    } catch (error) { if (alive && error.name !== 'AbortError') {
      workflowBody.replaceChildren(feedback(error.message, 'error'), button('Retry', () => {
        const reopen = () => void openEditor(id, initialTab);
        if (modal.close(reopen)) reopen();
      }));
      content.replaceChildren(workflowBody);
    } }
  }

  async function findFeeds() {
    if (disposed || modalActive || !radioReferenceAllowed) return;
    modalActive = true;
    const body = node('div', 'streaming-feed-browser editor-workspace');
    let alive = true, running = false;
    const abort = new AbortController();
    const modal = openReadOnlyModal('Available Broadcastify feeds', body, {
      id: 'streaming-feeds', className: 'streaming-feeds-modal', stack: 'child',
      cleanup: () => {
        alive = false; abort.abort(); modalActive = false;
        ownedDialogs.delete(modal); if (feedBrowser?.modal === modal) feedBrowser = null;
        signal?.removeEventListener('abort', closeFeeds);
        void refresh();
      }
    });
    if (modal) ownedDialogs.add(modal);
    const closeFeeds = () => { abort.abort(); modal?.close(); };
    if (!modal || (modal.ready && !await modal.ready)) { modalActive = false; return; }
    if (disposed) { modal.close(); return; }
    signal?.addEventListener('abort', closeFeeds, { once: true });
    const current = () => alive && !disposed && !abort.signal.aborted;
    const showConnect = () => {
      const prompt = node('div', 'streaming-feed-introduction');
      prompt.append(feedback('Connect an account to load your available Broadcastify feeds.'),
        button('Connect account', () => void loadFeeds(true)));
      body.replaceChildren(prompt);
    };
    async function loadFeeds(connect = false) {
      if (!current() || running) return;
      running = true;
      body.replaceChildren(feedback('Loading available Broadcastify feeds…', 'loading'));
      try {
        let configuration = connect ? null : await requestJson('/api/v1/admin/radioreference', {
          csrf: false, signal: abort.signal, page: false, timeoutMs: 15000
        });
        if (!current()) return;
        if (configuration?.account?.state !== 'VALID_PREMIUM') {
          configuration = await connectRadioReference({
            introduction: 'Connect your account to load your available Broadcastify feeds.', signal: abort.signal
          });
        }
        if (!current()) return;
        if (configuration?.account?.state !== 'VALID_PREMIUM') { showConnect(); return; }
        const result = await write('/feeds/refresh', 'POST', {}, { signal: abort.signal, page: false });
        if (!current()) return;
        const rows = result.items || [];
        const introduction = node('div', 'streaming-feed-introduction');
        const introductionText = node('div');
        introductionText.append(node('h3', '', 'Feeds assigned to this account'),
          node('p', 'muted', 'Add a feed as a disabled destination, then review its settings before connecting.'));
        introduction.append(introductionText, uiStatus(`${rows.length} feed${rows.length === 1 ? '' : 's'}`, 'neutral'));
        if (!rows.length) {
          const empty = node('div', 'streaming-feed-empty ui-empty-state');
          empty.append(node('h3', '', 'No assigned feeds'),
            node('p', '', 'No Broadcastify feeds are assigned to the saved RadioReference account.'));
          body.replaceChildren(introduction, empty);
          return;
        }
        const grid = node('div', 'streaming-feed-grid');
        rows.forEach(row => {
          const card = node('article', 'streaming-feed-card ui-surface ui-surface-soft');
          const header = node('header', 'streaming-feed-card-header');
          const identity = node('div', 'streaming-feed-identity');
          identity.append(node('h3', 'streaming-feed-name', row.name || 'Unnamed Broadcastify feed'),
            node('span', 'muted', 'Broadcastify feed'));
          header.append(identity, uiStatus(row.configured ? 'Configured' : 'Available', row.configured ? 'success' : 'neutral'));
          const facts = node('div', 'ui-section-body');
          const feedDetails = node('dl', 'ui-fact-list streaming-feed-facts');
          feedDetails.append(node('dt', '', 'Feed ID'), node('dd', '', formatNumber(row.id)));
          facts.append(feedDetails);
          const cardMessage = node('div');
          cardMessage.setAttribute('role', 'status');
          const cardActions = node('footer', 'streaming-feed-card-actions ui-action-row');
          const workflow = createActionWorkflow({ surface: body, feedback: cardMessage, modal,
            controls: () => body.querySelectorAll('button') });
          const add = button(row.configured ? 'Already added' : 'Add feed', async () => {
            if (add.disabled) return;
            try {
              await workflow.run(() => write('/feeds', 'POST', { feed_id: row.id },
                { signal: abort.signal, page: false }), { pending: 'Adding feed…',
                onSuccess: (saved) => {
                  if (alive) { modal.setBusy(false); modal.close(); void openEditor(saved.configuration_id); }
                } });
            } catch (_) { /* Preserve the feed list and its recovery feedback. */ }
          }, !row.configured);
          add.classList.add('streaming-feed-add');
          add.disabled = row.configured;
          cardActions.append(add);
          card.append(header, facts, cardMessage, cardActions);
          grid.append(card);
        });
        body.replaceChildren(introduction, grid);
        markGuide(grid, 'feeds');
      } catch (error) {
        if (alive && error.name !== 'AbortError') {
          const errorState = node('div', 'streaming-feed-error');
          errorState.append(feedback(error.message || 'Available feeds could not be loaded', 'error'));
          const recovery = node('div', 'ui-action-row');
          recovery.append(button('Try again', () => void loadFeeds()),
            button('Connect account', () => void loadFeeds(true)));
          errorState.append(recovery);
          body.replaceChildren(errorState);
        }
      } finally { running = false; }
    }
    await loadFeeds();
    if (current()) {
      markGuide(modal.dialog.querySelector('.modal-close'), 'feeds-close');
      feedBrowser = { modal };
    }
  }

  const findFeedsAction = markGuide(button('Find Broadcastify feeds', () => perform(findFeeds)), 'feeds-button');
  findFeedsAction.disabled = !radioReferenceAllowed;
  if (!radioReferenceAllowed) findFeedsAction.title = 'RadioReference settings access is required.';
  const addAction = markGuide(button('Add destination', () => perform(() => openEditor()), true), 'add-button');
  actions.append(findFeedsAction, addAction);
  toolbar.append(summary, actions);
  pageMeta.append(note, freshness);
  host.append(toolbar, message, destinationHost, pageMeta);
  const dispose = () => {
    if (disposed) return;
    disposed = true; lifetime.abort(); clearTimeout(timer);
    parentSignal?.removeEventListener('abort', dispose);
    ownedDialogs.forEach(modal => { modal.setDirty(false); modal.setBusy(false); modal.close(); });
    ownedDialogs.clear(); editor = null; feedBrowser = null;
  };
  parentSignal?.addEventListener('abort', dispose, { once: true });
  if (parentSignal?.aborted) dispose();
  const ready = refresh();
  if (!guideMode) void ready.then(() => { if (!disposed) timer = setTimeout(poll, POLL_MS); });

  async function prepareGuideSection(section, context = {}) {
    if (!guideMode) throw new Error('Streaming examples require an isolated workspace.');
    await ready;
    const current = () => !disposed && !context.signal?.aborted && context.isCurrent?.() !== false;
    const activate = async control => {
      if (!current()) return;
      if (!control || control.disabled) throw new Error(`Streaming tour control unavailable: ${section}`);
      control.click();
      await pendingAction;
    };
    if (!current()) return;
    if (['provider', 'add-cancel'].includes(section)) {
      await activate(addAction);
      return;
    }
    if (['feeds', 'feeds-close'].includes(section)) {
      await activate(findFeedsAction);
      return;
    }
    const editorSections = ['sections', 'aliases-button', 'assign-alias', 'save-assignments',
      'status-button', 'delivery', 'recovery', 'destination-close'];
    if (!editorSections.includes(section)) return;
    await activate(host.querySelector('[data-guide-target="st-manage-button"]'));
    if (!current() || !editor) return;
    if (['sections', 'aliases-button'].includes(section)) return;
    await activate(editor.tabs.querySelector('[data-value="aliases"]'));
    if (!current() || !editor) return;
    const unchecked = [...editor.aliasesPanel.querySelectorAll('tbody input[type="checkbox"]')]
      .find(control => !control.checked);
    if (!unchecked) throw new Error('The Streaming example needs an unassigned alias.');
    markGuide(unchecked.closest('tr') || unchecked, 'assign-alias');
    if (section === 'assign-alias') return;
    unchecked.click();
    if (section === 'save-assignments') return;
    await activate(editor.save);
    if (!current() || !editor || section === 'status-button') return;
    await activate(editor.tabs.querySelector('[data-value="status"]'));
  }
  async function prepareGuide(section, context = {}) {
    await prepareGuideSection(section, context);
    if (disposed || context.signal?.aborted || context.isCurrent?.() === false) return;
    const ids = section === 'recovery' ? ['recovery-state', 'recovery-error'] : [section];
    const targets = ids.map(id => {
      const selector = `[data-guide-target="st-${id}"]`;
      return editor?.modal.dialog.querySelector(selector) || feedBrowser?.modal.dialog.querySelector(selector) ||
        host.querySelector(selector);
    }).filter(Boolean);
    targets.forEach(target => target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' }));
  }
  return { element: host, dispose, refresh, ready, prepareGuide };
}
