import { ALERT_TRIGGERS, ALERT_PROTOCOL_FAMILIES, ALERT_FIELDS, ALERT_OPERATORS, ALERT_TEMPLATE_FIELDS, cloneAlert,
  newAlertRule, newAlertCondition, alertRuleDescription, describeAlertConditions,
  alertSourceChoices, alertActivityChoices,
  visibleAlertTopics, availableAlertTopics, visibleAlertHistory, validAlertSiteUrl,
  directPushSupport, vapidKeyBytes } from './alerts-model.js?v=2';
import { readAlertDevice, saveAlertDevice, forgetAlertDevice } from './alerts-device-storage.js?v=1';
import { reconcileDirectSubscription, syncDirectWorkerDevice } from './alerts-direct.js?v=1';
import { enrollmentQrCanvas } from './alerts-qr.js?v=1';
import { receiverHealthAlertGroups } from '../core/receiver-health-alerts.js?v=3';

const ADMIN = '/api/v1/admin/alerts';
const PUBLIC = '/api/v1/alerts';
const PAGE_SIZE = 30;

// Reuse map: settings panels, shared tables, segmented controls, field builders,
// notices, browsing pager, and the shared dirty/busy modal lifecycle own appearance.
export function createAlertsWorkspace(deps) {
  const { node, formField, uiSelectFrame, uiToggleField, uiStatus, uiSegmentedControl,
    section, sectionActionHost, table, browsingWorkflows, openReadOnlyModal,
    confirmAction, createFormWorkflow, requestJson, apiPage, modalFooter, href,
    identityPicker, createId, signal, view = 'topics', selectedTopic = '', selectedAlert = '' } = deps;
  const host = node('div', 'alerts-workspace data-workspace');
  const feedback = node('div');
  feedback.setAttribute('role', 'status');
  const body = node('div', 'alerts-content');
  host.append(feedback, body);
  let snapshot = null;
  let disposed = false;
  let modal = null;
  let pollTimer = null;
  let publicOffset = 0;
  let publicTopic = selectedTopic;
  let openedLinkedAlert = false;
  let directConnection = { state: 'none' };
  let device = readAlertDevice(window.localStorage);
  const originalManifest = document.querySelector('link[rel="manifest"]')?.getAttribute('href');

  function button(label, action, primary = false) {
    const control = node('button', `ui-button ui-button-${primary ? 'primary' : 'secondary'}`, label);
    control.type = 'button';
    if (action) control.addEventListener('click', action);
    return control;
  }
  function input(value = '', type = 'text', required = false, maximum = null) {
    const control = node('input', type === 'checkbox' ? 'ui-selection-check' : 'ui-input');
    control.type = type;
    control.value = String(value ?? '');
    control.required = required;
    if (maximum != null) control.maxLength = maximum;
    return control;
  }
  function select(values, value = '', blank = null) {
    const control = node('select', 'ui-select');
    const entries = values.map(entry => Array.isArray(entry) ? { value: entry[0], label: entry[1] } : entry);
    if (blank != null) entries.unshift({ value: '', label: blank });
    if (value && !entries.some(entry => String(entry.value) === String(value))) {
      entries.push({ value, label: `${value} · unavailable` });
    }
    entries.forEach(entry => {
      const option = node('option', '', entry.label);
      option.value = String(entry.value);
      control.append(option);
    });
    control.value = String(value ?? '');
    return control;
  }
  function selectField(label, values, value, onChange, blank = null, detail = '') {
    const control = select(values, value, blank);
    if (onChange) control.addEventListener('change', () => onChange(control.value));
    return { control, element: formField(label, uiSelectFrame(control), detail) };
  }
  function toggle(label, checked, onChange, detail = '') {
    const field = uiToggleField(label, checked, label, detail);
    const control = field.querySelector('input');
    control.addEventListener('change', () => onChange(control.checked));
    return { control, element: field };
  }
  function notice(text, tone = '') {
    return node('div', tone ? `ui-notice ui-notice-${tone}` : 'ui-notice', text);
  }
  function showError(error) {
    if (disposed || error?.name === 'AbortError') return;
    feedback.replaceChildren(notice(error?.message || 'Alerts could not be loaded. Try again.', 'danger'),
      button('Retry', () => void load()));
  }
  const read = path => requestJson(path, { csrf: false, signal });
  const write = (path, method, value, extra = {}) => requestJson(path,
    { method, body: value, signal, page: false, timeoutMs: 30000, ...extra });
  const authorize = () => ({ Authorization: `Bearer ${device.manage_token}` });
  const options = key => Array.isArray(snapshot?.options?.[key]) ? snapshot.options[key] : [];
  const timeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  const topicName = id => snapshot?.topics?.find(topic => topic.id === id)?.name || 'Unavailable topic';
  const actionRow = (...actions) => { const row = node('div', 'ui-action-row'); row.append(...actions); return row; };
  function link(label, target) {
    const control = node('a', 'ui-button ui-button-secondary', label);
    control.href = target;
    return control;
  }
  function paragraph(text) { return node('p', 'muted', text); }
  function catalogPanel(title, rows, columns, emptyText, type, action) {
    const actions = sectionActionHost(action);
    const panel = section(title, null, actions);
    panel.append(table(rows, columns, emptyText,
      { type, layoutMenuHost: actions, rowKey: row => row.id }));
    return panel;
  }
  function replaceSnapshot(value) {
    snapshot = value;
    renderCatalog();
  }

  async function load() {
    feedback.replaceChildren(node('div', 'ui-feedback ui-feedback-loading', 'Loading alerts…'));
    try {
      snapshot = await read(view === 'public' ? PUBLIC + '/topics' : ADMIN + (view === 'setup' ? '/config' : '/topics'));
      if (disposed) return;
      feedback.replaceChildren();
      if (view === 'setup') renderSetup();
      else if (view === 'public') {
        if (snapshot.mode === 'direct') directConnection = await reconcileDirectSubscription(window, device,
          value => write(PUBLIC + '/subscriptions/me/refresh', 'POST', value, { headers: authorize(), csrf: false }));
        await renderPublic();
      }
      else renderCatalog();
    } catch (error) { showError(error); }
  }

  function renderCatalog() {
    body.replaceChildren();
    if (view === 'rules') {
      const add = button('Create alert rule', () => void editRule(), true);
      add.disabled = !snapshot.topics?.length;
      body.append(catalogPanel('Alert rules', snapshot.rules || [], [
        { id: 'name', label: 'Rule', render: rule => button(rule.name, () => void editRule(rule)) },
        { id: 'topic', label: 'Topic', render: rule => topicName(rule.topic_id) },
        { id: 'trigger', label: 'When', render: rule => alertRuleDescription(rule) },
        { id: 'enabled', label: 'State', render: rule => uiStatus(rule.enabled ? 'Enabled' : 'Paused', 'neutral') },
        { id: 'actions', label: 'Actions', render: rule => button('Delete', () => void deleteRule(rule)) }
      ], 'No alert rules yet. Create a rule to publish activity to a topic.', 'alert-rules', add));
      body.append(paragraph('Administrator alerts also receive the existing tuner and system health alerts.'));
    } else {
      body.append(catalogPanel('Topics', snapshot.topics || [], [
        { id: 'name', label: 'Topic', render: topic => button(topic.name, () => void editTopic(topic)) },
        { id: 'visibility', label: 'Listeners', render: topic => uiStatus(topic.visible ? 'Available to listeners' : 'Hidden from listeners', 'neutral') },
        { id: 'enabled', label: 'State', render: topic => uiStatus(topic.enabled ? 'Enabled' : 'Paused', 'neutral') },
        { id: 'rules', label: 'Rules', render: topic => String(topic.rule_count || 0) },
        { id: 'subscriptions', label: 'Subscriptions', render: topic => String(topic.subscription_count || 0) },
        { id: 'actions', label: 'Actions', render: topic => {
          const actions = actionRow();
          if (topic.visible) actions.append(link('View public topic', href('topics', { topic: topic.id })));
          if (!topic.built_in) actions.append(button('Delete', () => void deleteTopic(topic)));
          return actions;
        } }
      ], 'No topics yet. Create one for listeners to follow.', 'alert-topics',
      button('Create topic', () => void editTopic(), true)));
      body.append(paragraph('Making a topic available lets anyone with this VCE address read its alerts and subscribe without an account.'));
    }
  }

  async function deleteTopic(topic) {
    if (!await confirmAction(`Delete “${topic.name}” and its alert rules? Existing subscriptions to this topic will stop.`,
      { title: 'Delete topic', confirmLabel: 'Delete topic', tone: 'danger' })) return;
    try { replaceSnapshot(await write(ADMIN + '/topics/' + encodeURIComponent(topic.id), 'DELETE', { revision: snapshot.revision })); }
    catch (error) { showError(error); }
  }
  async function deleteRule(rule) {
    if (!await confirmAction(`Delete “${rule.name}”? This rule will stop publishing alerts.`,
      { title: 'Delete alert rule', confirmLabel: 'Delete rule', tone: 'danger' })) return;
    try { replaceSnapshot(await write(ADMIN + '/rules/' + encodeURIComponent(rule.id), 'DELETE', { revision: snapshot.revision })); }
    catch (error) { showError(error); }
  }

  async function editTopic(existing = null) {
    const draft = existing ? cloneAlert(existing) : { id: createId(), name: '', description: '', visible: true, enabled: true, built_in: false };
    const revision = snapshot.revision;
    const form = node('form', 'alerts-form');
    const name = input(draft.name, 'text', true, 100);
    name.disabled = draft.built_in === true;
    const description = node('textarea', 'ui-textarea');
    description.value = draft.description;
    description.maxLength = 500;
    const visible = toggle('Available to listeners', draft.visible, value => { draft.visible = value; },
      'Anyone who can open this VCE site can read and subscribe to an available topic.');
    const enabled = toggle('Enable topic', draft.enabled, value => { draft.enabled = value; },
      'Pausing keeps existing subscriptions and saved alerts.');
    const status = node('div');
    const cancel = button('Cancel', () => modal?.close());
    const save = button(existing ? 'Save topic' : 'Create topic', null, true);
    save.type = 'submit';
    form.append(formField('Topic name', name), formField('Description', description), visible.element, enabled.element);
    if (draft.built_in) form.append(notice('Administrator alerts receive the existing tuner and system health alerts.'));
    form.append(status, modalFooter(cancel, save));
    modal = openReadOnlyModal(existing ? 'Edit topic' : 'Create topic', form,
      { id: 'alert-topic-editor', className: 'modal-size-small' });
    const localModal = modal;
    await localModal.ready;
    const workflow = createFormWorkflow({ form, submit: save, feedback: status, modal: localModal });
    form.addEventListener('submit', event => {
      event.preventDefault();
      void (async () => {
        if (workflow.isBusy()) return;
        if (draft.visible && !existing?.visible && draft.built_in && !await confirmAction(
          'Make Administrator alerts available to everyone who can open this VCE site?',
          { title: 'Make administrator alerts public', confirmLabel: 'Make available' })) return;
        await workflow.save(async () => {
        const topic = { id: draft.id, name: name.value.trim(), description: description.value.trim(),
          visible: draft.visible, enabled: draft.enabled, built_in: draft.built_in };
        return write(ADMIN + '/topics', existing ? 'PUT' : 'POST', { revision, topic });
      }, { onSuccess: value => { replaceSnapshot(value); localModal.setDirty(false); localModal.close(); } });
      })();
    });
    localModal.focus(name.disabled ? description : name);
  }

  async function chooseAlias(condition, onChange, append = false) {
    const pickerBody = node('div', 'alerts-form');
    const searchForm = node('form', 'ui-action-row');
    const search = input('', 'search');
    search.placeholder = 'Search alias names or IDs';
    search.setAttribute('aria-label', 'Find an alias');
    const submit = button('Search', null, true);
    submit.type = 'submit';
    searchForm.append(search, submit);
    const results = node('div', 'alerts-alias-results');
    const status = node('div');
    let offset = 0;
    let generation = 0;
    let searchController = null;
    const child = openReadOnlyModal('Choose an alias', pickerBody,
      { id: 'alert-alias-picker', stack: 'child', cleanup: () => searchController?.abort() });
    pickerBody.append(searchForm, status, results);
    await child.ready;
    async function query() {
      searchController?.abort();
      searchController = new AbortController();
      const current = ++generation;
      const abort = () => searchController?.abort();
      signal?.addEventListener('abort', abort, { once: true });
      status.replaceChildren(node('div', 'ui-feedback ui-feedback-loading', 'Searching aliases…'));
      try {
        const page = await apiPage('/api/v1/aliases',
          { q: search.value.trim() || null, limit: 25, offset, include_activity: false },
          { signal: searchController.signal });
        if (current !== generation || disposed || !pickerBody.isConnected) return;
        status.replaceChildren();
        const rows = page.rows || [];
        results.replaceChildren(...rows.map(row => {
          const id = row.id ?? row.alias_id;
          const choice = button(`${row.name || row.alias_name || 'Unnamed alias'} · ${row.alias_list || row.alias_list_name || 'Alias List'} · ${id}`,
            () => {
              condition.value = append ? [...new Set([...condition.value.split(',').map(value => value.trim()).filter(Boolean), String(id)])].join(', ') : String(id);
              onChange(row.name || row.alias_name || `Alias ${id}`); child.close();
            });
          return choice;
        }));
        if (!rows.length) results.append(paragraph('No matching aliases. Try another name or ID.'));
        results.append(browsingWorkflows.createBrowsingPager({ node, countText: browsingWorkflows.pageRangeText(
          { offset, visible: rows.length, total: page.total_count, label: 'Aliases' }),
          previous: { enabled: offset > 0, onClick: () => { offset -= 25; void query(); } },
          next: { enabled: page.has_more,
            onClick: () => { offset = page.next_offset; void query(); } } }));
      } catch (error) { if (error?.name !== 'AbortError') status.replaceChildren(notice('Aliases could not be searched. Try again.', 'danger')); }
      finally { signal?.removeEventListener('abort', abort); }
    }
    searchForm.addEventListener('submit', event => { event.preventDefault(); offset = 0; void query(); });
    child.focus(search);
    void query();
  }

  async function editRule(existing = null) {
    const draft = existing ? cloneAlert(existing) : newAlertRule(timeZone());
    if (!draft.id) draft.id = createId();
    if (!draft.topic_id) draft.topic_id = snapshot.topics[0]?.id || '';
    const revision = snapshot.revision;
    const form = node('form', 'alerts-form');
    const status = node('div');
    const save = button(existing ? 'Save rule' : 'Create rule', null, true);
    save.type = 'submit';
    const cancel = button('Cancel', () => modal?.close());
    const panels = new Map();
    let workflow = null;
    const changed = () => { workflow?.setDirty(true); form.dispatchEvent(new Event('change', { bubbles: true })); };
    const tabs = uiSegmentedControl([
      { value: 'when', label: 'When' }, { value: 'conditions', label: 'Conditions' },
      { value: 'timing', label: 'Timing' }, { value: 'message', label: 'Notification' }
    ], 'when', value => {
      panels.forEach((panel, key) => { panel.hidden = value !== key; });
    });
    tabs.setAttribute('aria-label', 'Alert rule settings');
    for (const key of ['when', 'conditions', 'timing', 'message']) {
      const panel = node('div', 'alerts-form alerts-editor-panel');
      panel.hidden = key !== 'when';
      panels.set(key, panel);
    }
    const name = input(draft.name, 'text', true, 100);
    name.addEventListener('input', () => { draft.name = name.value; });
    const enabled = toggle('Enable rule', draft.enabled, value => { draft.enabled = value; });
    const topics = selectField('Publish to topic', snapshot.topics.map(topic => ({ value: topic.id, label: topic.name })),
      draft.topic_id, value => { draft.topic_id = value; });
    topics.control.required = true;
    const trigger = selectField('Activity', ALERT_TRIGGERS, draft.trigger, value => {
      draft.trigger = value;
      if (value !== 'health' && draft.timing.mode === 'sustained') draft.timing.mode = 'immediate';
      renderScope();
      renderConditions();
      renderTiming();
      capabilityCopy.hidden = value !== 'tone';
      healthCopy.hidden = value !== 'health';
    });
    const capabilityCopy = paragraph('AMBE tones require DMR or P25 Phase II.');
    capabilityCopy.hidden = draft.trigger !== 'tone';
    const healthCopy = paragraph('Matches tuner and system health issues.');
    healthCopy.hidden = draft.trigger !== 'health';
    const activityWarning = notice('Choose another activity or source.', 'warning');
    activityWarning.hidden = true;
    let sourceMode = draft.system_key ? 'system' : draft.channel_id ? 'channel' : 'all';
    draft.protocol_family ||= '';
    const scopeHost = node('fieldset', 'alerts-source ui-surface-inset alerts-form');
    const sourceChoices = (key, systemKey = '') => alertSourceChoices(options(key), draft.protocol_family, systemKey);
    const selectedChannel = () => options('channels').find(channel => channel.value === draft.channel_id);
    function refreshActivity() {
      const family = draft.protocol_family || selectedChannel()?.protocol_family ||
        options('systems').find(system => system.value === draft.system_key)?.protocol_family || '';
      const choices = alertActivityChoices(family);
      const supported = choices.some(([id]) => id === draft.trigger);
      if (!supported) choices.push([draft.trigger, `${ALERT_TRIGGERS.find(([id]) => id === draft.trigger)?.[1] || draft.trigger} · unavailable for this source`]);
      trigger.control.replaceChildren(...select(choices, draft.trigger).childNodes);
      trigger.control.value = draft.trigger;
      activityWarning.hidden = supported;
    }
    function renderScope() {
      const health = draft.trigger === 'health';
      scopeHost.hidden = health;
      if (health) {
        draft.system_key = draft.site_id = draft.channel_id = draft.protocol = draft.protocol_family = '';
        sourceMode = 'all';
        scopeHost.replaceChildren();
        refreshActivity();
        return;
      }
      const family = selectField('Protocol', ALERT_PROTOCOL_FAMILIES, draft.protocol_family, value => {
        draft.protocol_family = value;
        if (draft.system_key && !sourceChoices('systems').some(system => system.value === draft.system_key)) {
          draft.system_key = draft.site_id = draft.channel_id = '';
        }
        if (draft.channel_id && !sourceChoices('channels').some(channel => channel.value === draft.channel_id)) draft.channel_id = '';
        renderScope(); renderConditions(); changed();
      }, 'All protocols');
      const source = uiSegmentedControl([{ value: 'all', label: 'All' }, { value: 'system', label: 'System' }, { value: 'channel', label: 'Channel' }],
        sourceMode, value => {
          sourceMode = value; draft.system_key = draft.site_id = draft.channel_id = '';
          renderScope(); renderConditions(); changed();
        });
      source.setAttribute('aria-label', 'Alert source');
      const sourceRow = node('div', 'alerts-field-grid'); sourceRow.append(family.element, formField('Source', source));
      scopeHost.replaceChildren(node('legend', 'ui-section-heading', 'Radio source'), sourceRow);
      if (sourceMode === 'system') {
        const system = selectField('Radio system', sourceChoices('systems'), draft.system_key, value => {
          draft.system_key = value; draft.site_id = draft.channel_id = '';
          renderScope(); renderConditions(); changed();
        }, 'Choose a system');
        system.control.required = true;
        const systemRow = node('div', 'alerts-field-grid'); systemRow.append(system.element);
        scopeHost.append(systemRow);
        if (draft.system_key) {
          const sites = sourceChoices('sites', draft.system_key);
          if (sites.length || draft.site_id) {
            const site = selectField('Site', sites, draft.site_id, value => { draft.site_id = value; }, 'All sites');
            systemRow.append(site.element);
          }
        }
      } else if (sourceMode === 'channel') {
        const channel = selectField('Channel', sourceChoices('channels'), draft.channel_id, value => {
          draft.channel_id = value; renderScope(); renderConditions(); changed();
        }, 'Choose a channel');
        channel.control.required = true; scopeHost.append(channel.element);
      }
      const channel = selectedChannel();
      if (channel) scopeHost.append(paragraph([
        channel.channel_kind === 'TRUNKED' ? 'Trunked' : channel.channel_kind === 'CONVENTIONAL' ? 'Conventional' : '',
        channel.protocol_family, channel.label
      ].filter(Boolean).join(' · ')));
      const more = node('details', 'alerts-form');
      more.open = Boolean(draft.protocol || draft.site_id && sourceMode !== 'system' || draft.channel_id && sourceMode === 'system');
      more.append(node('summary', '', 'More source filters'),
        selectField('Message protocol', options('protocols'), draft.protocol, value => { draft.protocol = value; }, 'Any message protocol').element);
      if (sourceMode === 'system' && draft.system_key) more.append(
        selectField('Channel', sourceChoices('channels', draft.system_key), draft.channel_id, value => {
          draft.channel_id = value; renderScope(); renderConditions(); changed();
        }, 'All channels').element);
      if (draft.site_id && sourceMode !== 'system') more.append(
        selectField('Site', options('sites'), draft.site_id, value => { draft.site_id = value; }, 'Any site').element);
      scopeHost.append(more);
      refreshActivity();
    }
    renderScope();
    const ruleIdentity = node('div', 'alerts-field-grid'); ruleIdentity.append(formField('Rule name', name), topics.element);
    panels.get('when').append(ruleIdentity, scopeHost, trigger.element, activityWarning, capabilityCopy, healthCopy, enabled.element);

    const conditionsHost = node('div', 'alerts-conditions');
    const identityPickers = [];
    const identitySystemKey = () => draft.system_key || selectedChannel()?.system_key || '';
    function fieldOptions(field) {
      return ({ channel_id: sourceChoices('channels'), system_key: sourceChoices('systems'), site_id: sourceChoices('sites').filter(
        site => !draft.system_key || !site.system_key || site.system_key === draft.system_key),
        protocol: options('protocols'), event_type: options('message_types'), tone: options('tone_types'),
        dcs: options('dcs_codes'), issue: options('health_issues').length ? options('health_issues') :
          receiverHealthAlertGroups.flatMap(group => group.alerts.map(alert => ({ value: alert.id, label: alert.name }))),
        severity: [['info', 'Information'], ['warning', 'Warning'], ['critical', 'Critical']],
        state: [['active', 'Active'], ['opened', 'Opened'], ['escalated', 'Escalated'], ['recovered', 'Recovered']], encrypted: [['true', 'Yes'], ['false', 'No']],
        emergency: [['true', 'Yes'], ['false', 'No']] })[field] || [];
    }
    function leaf(condition, parent) {
      const row = node('div', 'alerts-condition-row');
      const fields = draft.trigger === 'health' ? ALERT_FIELDS.filter(([key]) => ['issue', 'severity', 'state'].includes(key)) : ALERT_FIELDS;
      const field = selectField('Field', fields, condition.field, value => {
        condition.field = value; condition.value = ''; renderConditions(); changed();
      });
      const operator = selectField('Match', ALERT_OPERATORS, condition.operator, value => {
        condition.operator = value; renderConditions(); changed();
      });
      const valueHost = node('div', 'alerts-condition-value');
      if (condition.operator !== 'exists') {
        const choices = fieldOptions(condition.field);
        if (condition.field === 'alias_id' && ['is', 'not', 'in'].includes(condition.operator)) {
          if (condition.operator === 'in') {
            const list = input(condition.value, 'text', true, 512);
            list.addEventListener('input', () => { condition.value = list.value; });
            valueHost.append(formField('Aliases', list), button('Add alias', () => void chooseAlias(condition, () => {
              list.value = condition.value; changed();
            }, true)));
          } else {
          const choose = button(condition.value ? `Alias ${condition.value} · choose` : 'Choose alias', () => void chooseAlias(condition, label => {
            choose.textContent = label; changed();
          }));
          const field = node('div', 'ui-field');
          field.append(node('span', 'ui-field-label', 'Alias'), choose);
          valueHost.append(field);
          }
        } else if (['radio', 'target_radio', 'talkgroup'].includes(condition.field) &&
          ['is', 'not'].includes(condition.operator) && identitySystemKey() && identityPicker) {
          const kind = condition.field === 'talkgroup' ? 'talkgroup' : 'radio';
          const picker = identityPicker({ radioSystemKey: identitySystemKey() }, { destination: kind === 'talkgroup',
            allowedKinds: [kind], exactOnly: true, label: 'Identity', nativeValue: condition.value,
            onSelection: entry => { condition.value = entry?.id != null ? String(entry.id) : ''; changed(); } });
          identityPickers.push(picker);
          valueHost.append(picker.element);
        } else if (choices.length && ['is', 'not'].includes(condition.operator)) {
          const value = selectField('Value', choices, condition.value, result => { condition.value = result; }, 'Choose value');
          value.control.required = true;
          valueHost.append(value.element);
        } else {
          const value = input(condition.value, condition.field === 'duration_seconds' ? 'number' : 'text', true, 512);
          value.addEventListener('input', () => { condition.value = value.value; });
          valueHost.append(formField('Value', value, condition.operator === 'in' ? 'Separate exact values with commas.' :
            ['radio', 'target_radio', 'talkgroup'].includes(condition.field) && !identitySystemKey() ?
              'Choose a system or channel to search names and aliases.' : ''));
        }
      }
      row.append(field.element, operator.element, valueHost,
        button('Remove condition', () => { parent.items.splice(parent.items.indexOf(condition), 1); renderConditions(); changed(); }));
      return row;
    }
    function group(condition, parent = null, depth = 0) {
      const wrapper = node('fieldset', 'alerts-condition-group');
      wrapper.append(node('legend', '', depth ? 'Condition group' : 'Match conditions'));
      const match = selectField('Match', [['all', 'All of these'], ['any', 'Any of these'], ['none', 'None of these']],
        condition.op || 'all', value => { condition.op = value; changed(); });
      const rows = node('div', 'alerts-condition-children');
      for (const child of condition.items || []) rows.append(child.op ? group(child, condition, depth + 1) : leaf(child, condition));
      const newCondition = () => newAlertCondition(draft.trigger === 'health' ? 'issue' : 'talkgroup');
      const addCondition = button('Add condition', () => { condition.items.push(newCondition()); renderConditions(); changed(); });
      const addGroup = button('Add group', () => { condition.items.push({ op: 'any', items: [newCondition()] }); renderConditions(); changed(); });
      addGroup.disabled = depth >= 6;
      const actions = actionRow(addCondition, addGroup);
      if (parent) actions.append(button('Remove group', () => { parent.items.splice(parent.items.indexOf(condition), 1); renderConditions(); changed(); }));
      wrapper.append(match.element, rows, actions);
      return wrapper;
    }
    function renderConditions() {
      identityPickers.splice(0).forEach(picker => picker.close());
      if (!draft.conditions?.op) draft.conditions = { op: 'all', items: draft.conditions ? [draft.conditions] : [] };
      conditionsHost.replaceChildren(group(draft.conditions));
    }
    renderConditions();
    panels.get('conditions').append(conditionsHost, paragraph('Missing observations don’t match conditions, including “None of these”.'));

    function numberField(label, object, key, minimum, maximum) {
      const control = input(object[key], 'number', true);
      control.min = String(minimum); control.max = String(maximum); control.step = '1';
      control.addEventListener('input', () => { object[key] = Number(control.value); });
      return formField(label, control);
    }
    function renderTiming() {
      const panel = panels.get('timing');
      const timings = [['immediate', 'When the event matches'], ['quiet', 'After matching activity becomes quiet'],
        ['count', 'After a number of matching events']];
      if (draft.trigger === 'health') timings.push(['sustained', 'When a health issue persists']);
      const timing = selectField('Send alert', timings, draft.timing.mode, value => {
        draft.timing.mode = value; renderTiming(); changed();
      });
      panel.replaceChildren(timing.element);
      if (['quiet', 'sustained'].includes(draft.timing.mode)) panel.append(numberField('Minutes', draft.timing, 'minutes', 1, 10080));
      if (draft.timing.mode === 'count') panel.append(numberField('Matching events', draft.timing, 'count', 1, 10000),
        numberField('Within minutes', draft.timing, 'window_minutes', 1, 1440));
      panel.append(numberField('Wait before repeating (minutes)', draft, 'cooldown_minutes', 0, 10080),
        selectField('Limit repeated alerts by', [['rule', 'This rule'], ['radio', 'Radio'], ['talkgroup', 'Talkgroup'],
          ['pair', 'Radio and talkgroup'], ['channel', 'Channel']], draft.repeat_key, value => { draft.repeat_key = value; }).element);
      if (draft.trigger === 'health') panel.append(toggle('Notify when recovered', draft.notify_recovery,
        value => { draft.notify_recovery = value; }).element);
      const schedule = selectField('Active schedule', [['always', 'Always'], ['custom', 'Selected days and times']],
        draft.schedule.mode, value => { draft.schedule.mode = value; renderTiming(); changed(); });
      panel.append(schedule.element);
      if (draft.schedule.mode === 'custom') {
        const days = node('div', 'ui-action-row');
        ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].forEach((label, day) => {
          const control = input('', 'checkbox');
          control.checked = draft.schedule.days.includes(day);
          control.addEventListener('change', () => {
            draft.schedule.days = control.checked ? [...new Set([...draft.schedule.days, day])] : draft.schedule.days.filter(value => value !== day);
          });
          const field = node('label', 'alerts-check-row'); field.append(control, node('span', '', label)); days.append(field);
        });
        const start = input(draft.schedule.start, 'time', true);
        const end = input(draft.schedule.end, 'time', true);
        start.addEventListener('input', () => { draft.schedule.start = start.value; });
        end.addEventListener('input', () => { draft.schedule.end = end.value; });
        const zone = input(draft.schedule.time_zone, 'text', true, 80);
        zone.addEventListener('input', () => { draft.schedule.time_zone = zone.value; });
        panel.append(formField('Days', days), formField('From', start), formField('Until', end),
          formField('Time zone', zone, 'Use a time zone such as America/New_York. Times may cross midnight.'));
      }
    }
    renderTiming();
    const title = input(draft.title_template, 'text', true, 128);
    const text = node('textarea', 'ui-textarea'); text.value = draft.body_template; text.required = true; text.maxLength = 2048;
    title.addEventListener('input', () => { draft.title_template = title.value; });
    text.addEventListener('input', () => { draft.body_template = text.value; });
    const preview = node('div', 'ui-notice');
    const previewTitle = node('strong'); const previewBody = node('p');
    const examples = { talkgroup: 'Fire Tactical', radio: 'Engine 12', site: 'North site', channel: 'Dispatch', vce: 'VCE installation', issue: 'Tuner disconnected', time: '14:32' };
    const expand = value => value.replace(/\{([a-z_]+)\}/g, (match, key) => examples[key] || match);
    const updatePreview = () => { previewTitle.textContent = expand(title.value); previewBody.textContent = expand(text.value); };
    preview.append(node('span', 'ui-field-detail', 'Sample preview'), previewTitle, previewBody);
    title.addEventListener('input', updatePreview); text.addEventListener('input', updatePreview); updatePreview();
    panels.get('message').append(formField('Title', title), formField('Notification text', text,
      `Available fields: ${ALERT_TEMPLATE_FIELDS.map(field => `{${field}}`).join(', ')}.`),
      selectField('Open when tapped', [['details', 'Alert details'], ['listen', 'Open VCE Listen view (Direct)']], draft.click_action,
        value => { draft.click_action = value; }, null, 'Cloud notifications open the hosted alert page.').element, preview);
    const tabScroll = node('div', 'alerts-tab-scroll');
    tabScroll.append(tabs);
    form.append(tabScroll, ...panels.values(), status, modalFooter(cancel, save));
    modal = openReadOnlyModal(existing ? 'Edit alert rule' : 'Create alert rule', form,
      { id: 'alert-rule-editor', cleanup: () => identityPickers.forEach(picker => picker.close()) });
    const localModal = modal;
    await localModal.ready;
    workflow = createFormWorkflow({ form, submit: save, feedback: status, modal: localModal });
    form.addEventListener('invalid', event => {
      for (const [key, panel] of panels) {
        if (panel.contains(event.target) && panel.hidden) tabs.querySelector(`button[data-value="${key}"]`)?.click();
      }
    }, true);
    form.addEventListener('submit', event => {
      event.preventDefault();
      void workflow.save(async () => {
        for (const picker of identityPickers) {
          const selection = await picker.resolve();
          if (selection.error) throw new Error(selection.error);
        }
        if (draft.schedule.mode === 'custom' && !draft.schedule.days.length) throw new Error('Choose at least one active day.');
        return write(ADMIN + '/rules', existing ? 'PUT' : 'POST', { revision, rule: draft });
      }, { onSuccess: value => { replaceSnapshot(value); localModal.setDirty(false); localModal.close(); } });
    });
    localModal.focus(name);
  }

  function renderSetup() {
    let saved = cloneAlert(snapshot);
    const draft = { enabled: saved.enabled, mode: saved.mode, site_url: saved.site_url || '', sender_label: saved.sender_label || 'VCE installation' };
    let proof = null;
    let testing = false;
    const form = node('form', 'alerts-form settings-page-form ui-settings-form');
    const status = node('div');
    const save = button('Save settings', null, true); save.type = 'submit';
    const enabled = toggle('Enable push notifications', draft.enabled, value => { draft.enabled = value; });
    const deliveryStatus = node('div');
    const header = node('div', 'alerts-delivery-header'); header.append(enabled.element, deliveryStatus);
    const mode = uiSegmentedControl([{ value: 'proxy', label: 'Cloud · Recommended' }, { value: 'direct', label: 'Direct' }],
      draft.mode, value => { draft.mode = value; renderMode(); workflow.setDirty(true); });
    mode.setAttribute('aria-label', 'Notification delivery');
    const detail = node('div', 'alerts-form');
    const footer = node('footer', 'settings-form-footer'); footer.append(status, save);
    form.append(header, mode, detail, footer);
    const workflow = createFormWorkflow({ form, submit: save, feedback: status });
    function needsApproval() {
      if (snapshot.registration?.status === 'banned') return false;
      return !snapshot.registration?.sender_id || ['unregistered', 'deregistered'].includes(snapshot.registration?.status);
    }
    function updateSaveAvailability() {
      save.textContent = draft.mode === 'proxy' && needsApproval() ? 'Request approval' : 'Save settings';
      workflow.setReady(draft.mode !== 'direct' || Boolean(proof && proof.url === draft.site_url && validAlertSiteUrl(draft.site_url)));
      if (draft.mode === 'direct') deliveryStatus.replaceChildren(uiStatus(proof ? 'Verified' : 'Needs public test', 'neutral'));
    }
    function renderMode() {
      detail.replaceChildren();
      if (draft.mode === 'proxy') {
        detail.append(paragraph('Sends push notifications through the cloud to your device. Choose this if you do not expose VCE for public access.'),
          paragraph('Usage is subject to approval. Most requests are granted within 24 hours. Abuse is subject to ban.'));
        const senderName = input(draft.sender_label, 'text', true, 80);
        senderName.addEventListener('input', () => { draft.sender_label = senderName.value.trim(); });
        detail.append(formField('VCE name', senderName));
        const registration = snapshot.registration || {};
        const registrationStatus = String(registration.status).toLowerCase();
        const label = ({ approved: 'Approved', pending: 'Awaiting approval', rejected: 'Not approved', banned: 'Banned',
          deregistered: 'Deregistered', unregistered: 'Not requested' })[registrationStatus] || 'Not requested';
        deliveryStatus.replaceChildren(uiStatus(label, 'neutral'));
        const actions = actionRow();
        const check = button('Check status', () => void registrationAction('GET'));
        const disconnect = button('Disconnect cloud', async () => {
          if (await confirmAction('Stop cloud notifications for this VCE installation?',
            { title: 'Disconnect cloud', confirmLabel: 'Disconnect cloud', tone: 'danger' })) void registrationAction('DELETE');
        });
        if (!needsApproval()) actions.append(check);
        if (registration.sender_id && !['banned', 'deregistered'].includes(registrationStatus)) actions.append(disconnect);
        detail.append(actions);
        if (registration.moderation_reason) detail.append(notice(registration.moderation_reason));
        if (registration.sender_id && registrationStatus !== 'deregistered') {
          const reference = node('dl', 'ui-fact-list');
          reference.append(node('dt', '', 'Registration reference'), node('dd', '', registration.sender_id));
          const more = node('details'); more.append(node('summary', '', 'Registration details'), reference);
          detail.append(more);
        }
      } else {
        detail.append(paragraph('Requires a public domain and HTTPS. Choose this if VCE is accessible through port forwarding or a service such as Cloudflare.'));
        const url = input(draft.site_url, 'url', true, 2048);
        url.placeholder = 'https://vce.example.com';
        const testStatus = node('div');
        const test = button('Test public access', async () => {
          if (testing) return;
          if (!validAlertSiteUrl(url.value.trim())) { testStatus.replaceChildren(notice('Enter an HTTPS domain address, then test again.', 'danger')); return; }
          testing = true;
          const controls = [...form.querySelectorAll('input,select,button')];
          const disabled = controls.map(control => control.disabled);
          controls.forEach(control => { control.disabled = true; });
          form.setAttribute('aria-busy', 'true');
          testStatus.replaceChildren(node('div', 'ui-feedback ui-feedback-loading', 'Checking public access…'));
          const testedUrl = url.value.trim();
          try {
            const result = await write(ADMIN + '/public-test', 'POST', { site_url: testedUrl });
            if (draft.site_url !== testedUrl || disposed) return;
            proof = result.test_token ? { url: testedUrl, token: result.test_token } : null;
            const passed = proof && ['passed', 'success', 'verified'].includes(String(result.public_access?.status).toLowerCase());
            if (!passed) proof = null;
            testStatus.replaceChildren(notice(passed ? 'Public access verified.' : result.public_access?.message || 'Couldn’t verify VCE. Check the address and HTTPS, then try again.', passed ? '' : 'danger'));
          } catch (error) { proof = null; testStatus.replaceChildren(notice(error.message || 'Couldn’t test public access. Try again.', 'danger')); }
          finally {
            controls.forEach((control, index) => { control.disabled = disabled[index]; });
            testing = false; form.removeAttribute('aria-busy'); updateSaveAvailability();
          }
        });
        url.addEventListener('input', () => { draft.site_url = url.value.trim(); proof = null; updateSaveAvailability(); testStatus.replaceChildren(); });
        const testRow = node('div', 'alerts-direct-test'); testRow.append(formField('VCE site URL', url), test);
        detail.append(testRow, testStatus);
      }
      updateSaveAvailability();
    }
    async function registrationAction(method) {
      const controls = [...form.querySelectorAll('input,select,button')];
      const disabled = controls.map(control => control.disabled);
      controls.forEach(control => { control.disabled = true; });
      try {
        snapshot.registration = method === 'GET' ? await read(ADMIN + '/registration') : await write(ADMIN + '/registration', method, method === 'POST' ? {} : undefined);
        renderMode();
      } catch { status.replaceChildren(notice('Couldn’t update approval. Try again.', 'danger')); }
      finally { controls.forEach((control, index) => { control.disabled = disabled[index]; }); updateSaveAvailability(); }
    }
    renderMode();
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (saved.mode !== draft.mode && !await confirmAction('Switch notification delivery? Connected devices will need to reconnect.',
        { title: 'Change delivery', confirmLabel: 'Switch delivery' })) return;
      const requestApproval = draft.mode === 'proxy' && needsApproval();
      void workflow.save(async () => {
        if (draft.mode === 'direct' && (!proof || proof.url !== draft.site_url || !validAlertSiteUrl(draft.site_url))) {
          throw new Error('Test public access before saving.');
        }
        const value = await write(ADMIN + '/config', 'PUT', { revision: saved.revision, ...draft,
          site_url: draft.mode === 'direct' ? draft.site_url : '', test_token: draft.mode === 'direct' ? proof.token : '' });
        saved = value;
        snapshot = value;
        if (requestApproval) {
          await write(ADMIN + '/registration', 'POST', { sender_label: draft.sender_label });
          return read(ADMIN + '/config');
        }
        return value;
      }, { saving: requestApproval ? 'Requesting approval…' : 'Saving…',
        onSuccess: value => { snapshot = value; renderSetup(); feedback.replaceChildren(node('div', 'ui-feedback', requestApproval ? 'Approval requested.' : 'Settings saved.')); },
        onError: error => ({ state: 'error', message: error.message || (requestApproval ? 'Couldn’t request approval. Try again.' : 'Couldn’t save settings. Try again.') }) });
    });
    body.replaceChildren(section('Delivery', form));
  }

  async function renderPublic() {
    document.querySelector('link[rel="manifest"]')?.setAttribute('href', '/assets/alerts.webmanifest?v=1');
    const topics = visibleAlertTopics(snapshot.topics);
    if (publicTopic && !topics.some(topic => topic.id === publicTopic)) publicTopic = '';
    body.replaceChildren();
    const introduction = node('header', 'ui-surface ui-surface-heading');
    const heading = node('div', 'ui-heading-group'); heading.append(node('h2', '', 'Follow alert topics'), paragraph('Read recent alerts and choose which topics notify this device. No account is needed.'));
    introduction.append(heading, button(device ? 'Notification settings' : 'Subscribe', () => void openSubscription()));
    body.append(introduction);
    if (directConnection.message) body.append(notice(directConnection.message, 'warning'));
    const cards = node('div', 'alerts-topic-grid');
    for (const topic of topics) {
      const card = node('article', 'ui-surface alerts-topic-card');
      const identity = node('div', 'ui-heading-group');
      identity.append(node('h3', '', topic.name), paragraph(topic.description || 'Alerts published by this VCE installation.'));
      card.append(identity, uiStatus(topic.enabled ? 'Available' : 'Paused', 'neutral'));
      const subscribe = button('Subscribe', () => void openSubscription(topic.id), true); subscribe.disabled = !topic.enabled || !snapshot.push_enabled;
      card.append(actionRow(button('Read alerts', () => { publicTopic = topic.id; publicOffset = 0; filter.control.value = topic.id; void loadHistory(); }), subscribe));
      cards.append(card);
    }
    if (!topics.length) cards.append(notice('No topics are available to listeners yet.'));
    body.append(cards);
    if (!snapshot.push_enabled && topics.length) body.append(notice('Push notifications are not ready. You can still read the available alerts.'));
    const filter = selectField('Show alerts from', topics.map(topic => ({ value: topic.id, label: topic.name })), publicTopic,
      value => { publicTopic = value; publicOffset = 0; void loadHistory(); }, 'All topics');
    const timeline = node('div', 'alerts-timeline');
    const timelineContent = node('div', 'alerts-form ui-surface-inset');
    timelineContent.append(filter.element, timeline);
    const timelinePanel = section('Recent alerts', timelineContent, button('Refresh', () => void loadHistory()));
    body.append(timelinePanel);
    let generation = 0;
    async function loadHistory() {
      const current = ++generation;
      timeline.replaceChildren(node('div', 'ui-feedback ui-feedback-loading', 'Loading recent alerts…'));
      try {
        const query = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(publicOffset) });
        if (publicTopic) query.set('topic_id', publicTopic);
        const page = await read(PUBLIC + '/history?' + query);
        if (disposed || current !== generation) return;
        const events = visibleAlertHistory(page.items, snapshot.topics, publicTopic);
        timeline.replaceChildren(...events.map(event => {
          const card = node('article', 'alerts-event ui-surface');
          const when = event.occurred_at ?? event.created_at_ms;
          const timestamp = node('time', 'muted', formatTime(when));
          if (Number.isFinite(Number(when))) timestamp.dateTime = new Date(Number(when)).toISOString();
          card.append(node('span', 'ui-field-detail', topicName(event.topic_id)),
            button(event.title || 'VCE alert', () => openAlert(event)), paragraph(event.body || ''), timestamp);
          return card;
        }));
        if (!events.length) timeline.append(paragraph(publicTopic ? 'No alerts have been published to this topic yet.' : 'No alerts have been published yet.'));
        timeline.append(browsingWorkflows.createBrowsingPager({ node,
          countText: browsingWorkflows.pageRangeText({ offset: publicOffset, visible: events.length, total: page.total, label: 'Alerts' }),
          previous: { enabled: publicOffset > 0, onClick: () => { publicOffset -= PAGE_SIZE; void loadHistory(); } },
          next: { enabled: Number.isInteger(page.total) ? publicOffset + events.length < page.total : events.length === PAGE_SIZE,
            onClick: () => { publicOffset += PAGE_SIZE; void loadHistory(); } } }));
      } catch (error) { if (error?.name !== 'AbortError') timeline.replaceChildren(notice('Recent alerts could not be loaded. Try Refresh.', 'danger')); }
    }
    await loadHistory();
    if (selectedAlert && !openedLinkedAlert) {
      openedLinkedAlert = true;
      try {
        const result = await read(PUBLIC + '/history/' + encodeURIComponent(selectedAlert));
        const event = result.item || result;
        if (!disposed && visibleAlertHistory([event], snapshot.topics).length) openAlert(event);
        else if (!disposed) feedback.replaceChildren(notice('This alert is unavailable. Read the recent alerts below.'));
      } catch (error) {
        if (!disposed && error?.name !== 'AbortError') feedback.replaceChildren(notice('This alert is unavailable. Read the recent alerts below.'));
      }
    }
  }
  function formatTime(value) {
    const date = new Date(typeof value === 'number' || /^\d+$/.test(String(value)) ? Number(value) : value);
    return Number.isFinite(date.getTime()) ? date.toLocaleString() : 'Time unavailable';
  }
  function openAlert(event) {
    const detail = node('div', 'alerts-form');
    detail.append(node('span', 'ui-field-detail', topicName(event.topic_id)), paragraph(event.body || ''),
      node('time', 'muted', formatTime(event.occurred_at ?? event.created_at_ms)));
    if (event.click_action === 'listen') detail.append(link('Listen', href('live')));
    const alertModal = openReadOnlyModal(event.title || 'VCE alert', detail, { id: 'public-alert-detail', className: 'modal-size-small' });
    const first = detail.querySelector('a');
    void alertModal.ready.then(() => { if (first) alertModal.focus(first); });
  }

  async function openSubscription(initialTopic = '') {
    const form = node('form', 'alerts-form');
    const status = node('div');
    const fields = node('div', 'alerts-form');
    const save = button(device?.status === 'connected' ? 'Save topics' : 'Connect notifications', null, true); save.type = 'submit';
    const cancel = button('Close', () => modal?.close());
    let currentDevice = null;
    let topicIds = initialTopic ? [initialTopic] : [];
    let localPollController = null;
    let pollFailures = 0;
    let workflow;
    const localModal = openReadOnlyModal('Alert subscription', form, { id: 'public-alert-subscription', className: 'modal-size-small',
      cleanup: () => { clearTimeout(pollTimer); localPollController?.abort(); } });
    modal = localModal;
    form.append(fields, status, modalFooter(cancel, save));
    await localModal.ready;
    workflow = createFormWorkflow({ form, submit: save, feedback: status, modal: localModal, ready: false });
    try {
      if (device?.status === 'pending') {
        const pending = await requestJson(PUBLIC + '/enrollments/' + encodeURIComponent(device.id),
          { headers: authorize(), csrf: false, signal, page: false });
        if (['connected', 'completed'].includes(pending.status)) device = saveAlertDevice(window.localStorage,
          { ...device, id: pending.device_id || pending.id || device.id, status: 'connected' });
        else if (['expired', 'rejected', 'disconnected', 'cancelled'].includes(pending.status)) {
          forgetAlertDevice(window.localStorage); device = null;
        } else {
          topicIds = Array.isArray(pending.topic_ids) ? pending.topic_ids.slice() : topicIds;
        }
      }
      if (device?.status === 'connected') {
        currentDevice = await requestJson(PUBLIC + '/subscriptions/me', { headers: authorize(), csrf: false, signal, page: false });
        topicIds = [...new Set([...(currentDevice.topic_ids || []), ...(initialTopic ? [initialTopic] : [])])];
      }
    } catch (error) {
      if (error?.status === 403 || error?.status === 404) { forgetAlertDevice(window.localStorage); device = null; }
      else { workflow.showFeedback('error', 'Couldn’t load subscriptions. Close and try again.'); return; }
    }
    function renderFields() {
      fields.replaceChildren();
      const knownMode = currentDevice?.mode || device?.mode;
      const deliveryChanged = Boolean(knownMode && knownMode !== snapshot.mode);
      const directRenewal = Boolean(currentDevice?.mode === 'direct' && snapshot.mode === 'direct' &&
        (directConnection.state === 'reconnect' || currentDevice.status === 'reconnect_required'));
      const proxyRenewal = Boolean(currentDevice?.mode === 'proxy' && snapshot.mode === 'proxy' && currentDevice.status === 'reconnect_required');
      const needsReconnect = Boolean(deliveryChanged || directConnection.state === 'reconnect' ||
        currentDevice?.status === 'reconnect_required');
      if (needsReconnect) fields.append(notice(deliveryChanged ? 'Notification delivery changed. Reconnect this device to receive alerts.' :
        proxyRenewal ? 'Notifications need to be reconnected in RadioResolve on the phone.' :
          'The notification connection expired or is unavailable. Reconnect this device to receive alerts.', 'warning'));
      const choices = node('fieldset', 'alerts-topic-choices'); choices.append(node('legend', '', 'Topics'));
      const available = new Set(availableAlertTopics(snapshot.topics).map(topic => topic.id));
      const publicIds = new Set(visibleAlertTopics(snapshot.topics).map(topic => topic.id));
      visibleAlertTopics(snapshot.topics).forEach(topic => {
        const check = input('', 'checkbox'); check.checked = topicIds.includes(topic.id);
        check.disabled = !available.has(topic.id) && !check.checked;
        check.addEventListener('change', () => {
          topicIds = check.checked ? [...new Set([...topicIds, topic.id])] : topicIds.filter(id => id !== topic.id);
          if (!available.has(topic.id) && !check.checked) check.disabled = true;
        });
        const label = node('label', 'alerts-check-row'); label.append(check, node('span', '', topic.name + (!topic.enabled ? ' · paused' : ''))); choices.append(label);
      });
      if (topicIds.some(id => !publicIds.has(id))) choices.append(paragraph('Some saved subscriptions are unavailable to listeners. Their choices are retained.'));
      fields.append(choices);
      if (needsReconnect && currentDevice && !directRenewal && !proxyRenewal) fields.append(button('Disconnect & reconnect', async () => {
        if (!await confirmAction('Disconnect the previous connection, then reconnect with your selected topics?',
          { title: 'Reconnect notifications', confirmLabel: 'Disconnect previous connection', tone: 'danger' })) return;
        workflow.setReady(true);
        await workflow.save(async () => {
          await write(PUBLIC + '/subscriptions/me', 'DELETE', undefined, { headers: authorize(), csrf: false });
          if (currentDevice?.mode === 'direct') {
            try { await (await navigator.serviceWorker.getRegistration('/'))?.pushManager.getSubscription().then(subscription => subscription?.unsubscribe()); }
            catch { /* The explicit Connect action checks for an expired subscription again. */ }
          }
          try { await syncDirectWorkerDevice(navigator, null); } catch { /* The server has revoked this credential. */ }
          forgetAlertDevice(window.localStorage);
          device = null; currentDevice = null; directConnection = { state: 'none' };
          topicIds = topicIds.filter(id => available.has(id));
        }, { saving: 'Disconnecting…', success: 'Disconnected. Choose Connect notifications to finish.',
          onSuccess: () => { localModal.setDirty(false); renderFields(); },
          onError: error => { workflow.setReady(false); return { state: 'error', message: error.message || 'The previous connection could not be disconnected. Try again.' }; } });
      }));
      if (proxyRenewal) {
        save.textContent = 'Save topics';
        const settingsUrl = 'https://radioresolve.com/vce-push/settings.php';
        const qr = enrollmentQrCanvas(document, settingsUrl);
        qr.setAttribute('aria-label', 'Scan to open RadioResolve notification settings');
        const open = link('RadioResolve notification settings', settingsUrl);
        open.target = '_blank'; open.rel = 'noopener noreferrer';
        const guide = node('div', 'alerts-form ui-surface-inset');
        guide.append(paragraph('Open notification settings in RadioResolve on the phone and choose Reconnect notifications. On iPhone, open RadioResolve from its Home Screen icon.'),
          qr, open);
        fields.append(guide);
      } else if (!currentDevice || needsReconnect) {
        save.textContent = 'Connect notifications';
        if (snapshot.mode === 'direct') {
          const support = directPushSupport(window);
          if (!support.supported) fields.append(notice(support.message, 'warning'));
          if (support.install) fields.append(paragraph('On iPhone, use Share → Add to Home Screen, then reopen VCE from its icon.'));
          if (window.Notification?.permission === 'denied') fields.append(notice('Notifications are blocked for this site. Allow them in your browser or phone settings, then reconnect.', 'warning'));
        }
      } else {
        save.textContent = 'Save topics';
        fields.append(uiStatus('Connected', 'neutral'));
      }
      if (currentDevice && !deliveryChanged) fields.append(button('Unsubscribe this device', async () => {
          if (!await confirmAction('Stop all alert notifications on this device?', { title: 'Unsubscribe device', confirmLabel: 'Unsubscribe', tone: 'danger' })) return;
          workflow.setReady(true);
          await workflow.save(async () => {
            await write(PUBLIC + '/subscriptions/me', 'DELETE', undefined, { headers: authorize(), csrf: false });
            try { await syncDirectWorkerDevice(navigator, null); } catch { /* The server has revoked this credential. */ }
            if (snapshot.mode === 'direct') {
              try { await (await navigator.serviceWorker.getRegistration('/'))?.pushManager.getSubscription().then(subscription => subscription?.unsubscribe()); } catch { /* The revoked device no longer receives sends. */ }
            }
            forgetAlertDevice(window.localStorage); device = null;
          }, { success: 'This device is unsubscribed.', onSuccess: () => { localModal.setDirty(false); localModal.close(); void renderPublic(); } });
        }));
      if (device?.status === 'pending') {
        fields.append(paragraph(needsReconnect ? 'Finish the previous RadioResolve connection, then disconnect it here before reconnecting.' :
          'Finish connecting on RadioResolve before changing topics.'));
        fields.querySelectorAll('input,select,button').forEach(control => { control.disabled = true; });
        save.textContent = 'Waiting for device';
      }
      if (directRenewal) save.textContent = 'Reconnect notifications';
      else if (needsReconnect && currentDevice && !proxyRenewal) save.textContent = 'Disconnect before reconnecting';
      workflow.setReady(device?.status !== 'pending' && (!needsReconnect || directRenewal || proxyRenewal) && Boolean(snapshot.push_enabled || currentDevice));
      if (device?.status === 'pending') void resumeEnrollment();
    }
    async function resumeEnrollment() {
      clearTimeout(pollTimer);
      if (!device || device.status !== 'pending') return;
      if (device.expires_at_ms && Date.now() >= device.expires_at_ms) {
        forgetAlertDevice(window.localStorage); device = null;
        renderFields();
        workflow.showFeedback('warning', 'The connection link expired. Connect again to get a new link.');
        return;
      }
      localPollController?.abort(); localPollController = new AbortController();
      try {
        const result = await requestJson(PUBLIC + '/enrollments/' + encodeURIComponent(device.id),
          { headers: authorize(), csrf: false, signal: localPollController.signal, page: false });
        if (!form.isConnected || disposed) return;
        pollFailures = 0;
        if (result.status === 'connected' || result.status === 'completed') {
          device = saveAlertDevice(window.localStorage, { ...device, id: result.device_id || result.id || device.id, status: 'connected' });
          currentDevice = await requestJson(PUBLIC + '/subscriptions/me', { headers: authorize(), csrf: false, signal, page: false });
          topicIds = currentDevice.topic_ids || topicIds;
          workflow.showFeedback('status', 'Notifications connected.'); renderFields();
        } else if (['expired', 'rejected', 'disconnected', 'cancelled'].includes(result.status)) {
          forgetAlertDevice(window.localStorage); device = null;
          renderFields();
          workflow.showFeedback('warning', 'This connection link is unavailable. Connect again.');
        } else {
          if (result.enrollment_url) showEnrollmentLink(result);
          pollTimer = setTimeout(() => void resumeEnrollment(), 4000);
        }
      } catch (error) {
        if (error?.name === 'AbortError' || disposed || !form.isConnected) return;
        if ([401, 403, 404, 410].includes(error?.status)) {
          forgetAlertDevice(window.localStorage); device = null; renderFields();
          workflow.showFeedback('warning', 'This connection link is unavailable. Connect again.');
          return;
        }
        workflow.showFeedback('warning', 'The connection could not be checked. Retrying shortly.');
        const delay = Math.min(30000, 4000 * 2 ** Math.min(pollFailures++, 3));
        pollTimer = setTimeout(() => void resumeEnrollment(), delay);
      }
    }
    function showEnrollmentLink(result) {
      let url;
      try { url = new URL(result.enrollment_url); if (url.protocol !== 'https:' || url.username || url.password) return; } catch { return; }
      const open = link('Continue on RadioResolve', url.href); open.target = '_blank'; open.rel = 'noopener noreferrer';
      const panel = node('div', 'alerts-form');
      panel.append(node('h3', 'ui-section-heading', 'Connect a phone'),
        paragraph('Scan with your phone’s camera, or open RadioResolve on this phone. Enable notifications there and add RadioResolve to your Home Screen on iPhone.'),
        enrollmentQrCanvas(document, url.href), open);
      if (result.code) {
        const code = input(result.code); code.readOnly = true;
        code.setAttribute('aria-label', 'Connection code');
        const copy = button('Copy connection code', async () => {
          try { await navigator.clipboard.writeText(result.code); copy.textContent = 'Code copied'; }
          catch { code.focus(); code.select(); copy.textContent = 'Select and copy code'; }
        });
        panel.append(formField('Connection code', code), copy);
      }
      panel.append(paragraph('This connection link expires after 15 minutes. This VCE browser manages the chosen topics; the phone can disconnect in RadioResolve notification settings.'));
      status.replaceChildren(panel);
    }
    renderFields();
    async function directSubscription(forceRenewal = false) {
      const support = directPushSupport(window);
      if (!support.supported) throw new Error(support.message);
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') throw new Error(permission === 'denied' ?
        'Notifications are blocked. Allow them in browser or phone settings, then reconnect.' : 'Notifications were not enabled. Choose Connect notifications to try again.');
      const worker = await navigator.serviceWorker.register('/alerts-worker.js?v=2', { scope: '/' });
      await navigator.serviceWorker.ready;
      let subscription = await worker.pushManager.getSubscription();
      if (subscription && (forceRenewal || subscription.expirationTime != null && subscription.expirationTime <= Date.now())) {
        await subscription.unsubscribe(); subscription = null;
      }
      subscription ||= await worker.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: vapidKeyBytes(snapshot.vapid_public_key) });
      return subscription.toJSON();
    }
    form.addEventListener('submit', event => {
      event.preventDefault();
      void workflow.save(async () => {
        if (!topicIds.length) throw new Error('Choose at least one topic.');
        const settings = { topic_ids: topicIds };
        if (currentDevice && currentDevice.mode === snapshot.mode) {
          if (snapshot.mode === 'direct' && (directConnection.state === 'reconnect' || currentDevice.status === 'reconnect_required')) {
            currentDevice = await write(PUBLIC + '/subscriptions/me/refresh', 'POST',
              { subscription: await directSubscription(true) }, { headers: authorize(), csrf: false });
            if (currentDevice.status === 'reconnect_required') throw new Error('The browser connection could not be renewed. Try reconnecting again.');
            directConnection = { state: 'connected' };
          }
          currentDevice = await write(PUBLIC + '/subscriptions/me', 'PUT', settings, { headers: authorize(), csrf: false });
          if (device.mode === 'direct') await syncDirectWorkerDevice(navigator, device);
          return { managed: true };
        }
        if (currentDevice) throw new Error('Disconnect the previous notification connection before reconnecting.');
        if (!snapshot.push_enabled) throw new Error('Push notifications are not ready. Try again after the administrator finishes setup.');
        if (snapshot.mode === 'direct') {
          settings.subscription = await directSubscription();
        }
        settings.topic_ids = settings.topic_ids.filter(id => availableAlertTopics(snapshot.topics).some(topic => topic.id === id));
        if (!settings.topic_ids.length) throw new Error('Choose at least one available topic.');
        const result = await write(PUBLIC + '/enrollments', 'POST', settings, { csrf: false });
        device = saveAlertDevice(window.localStorage, { id: result.device_id || result.id,
          manage_token: result.manage_token, mode: snapshot.mode,
          status: result.status === 'pending' ? 'pending' : 'connected', expires_at_ms: result.expires_at_ms });
        if (device.mode === 'direct') await syncDirectWorkerDevice(navigator, device);
        return result;
      }, { saving: currentDevice ? 'Saving topics…' : 'Connecting notifications…', success: 'Topics saved.',
        onSuccess: async result => {
          if (result.managed) { localModal.setDirty(false); localModal.close(); await renderPublic(); return; }
          if (result.status === 'pending') { localModal.setDirty(false); showEnrollmentLink(result); renderFields(); }
          else if (result.status === 'reconnect_required') {
            currentDevice = await requestJson(PUBLIC + '/subscriptions/me', { headers: authorize(), csrf: false, signal, page: false });
            directConnection = { state: 'reconnect', message: 'The notification connection expired. Open Notification settings to reconnect this device.' };
            localModal.setDirty(false); renderFields();
            workflow.showFeedback('warning', 'This saved connection expired. Choose Reconnect notifications to renew it.');
          }
          else { localModal.setDirty(false); localModal.close(); await renderPublic(); }
        } });
    });
    localModal.focus(fields.querySelector('input') || save);
  }

  function close() {
    disposed = true;
    clearTimeout(pollTimer);
    if (view === 'public' && originalManifest != null) document.querySelector('link[rel="manifest"]')?.setAttribute('href', originalManifest);
  }
  signal?.addEventListener('abort', close, { once: true });
  const ready = load();
  return { element: host, ready, close };
}
