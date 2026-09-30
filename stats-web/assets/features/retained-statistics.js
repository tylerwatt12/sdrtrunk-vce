const ROOT = '/api/v1/admin/retained-statistics';
const PAGE_SIZE = 25;
const CHOICE_SIZE = 50;
const JOB_POLL_MS = 1500;

const TYPES = Object.freeze([
  { value: 'frequencies', label: 'Frequencies' },
  { value: 'radios', label: 'Radio IDs' },
  { value: 'talkgroups', label: 'Talkgroups' },
  { value: 'sites', label: 'Sites' },
  { value: 'channels', label: 'Saved channels' },
  { value: 'systems', label: 'Radio systems' }
]);

const CHANNEL_TYPES = new Set(['frequencies', 'sites', 'channels']);

function typeAvailable(sourceKind, source, type) {
  if (!source) return false;
  if (sourceKind === 'radio_system') return true;
  if (CHANNEL_TYPES.has(type)) return true;
  return ['radios', 'talkgroups'].includes(type) &&
    String(source.channel_kind || '').toUpperCase() === 'CONVENTIONAL' &&
    String(source.protocol || '').toUpperCase() === 'DMR';
}

function queryPath(path, values) {
  const query = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== '' && value !== null && value !== undefined) query.set(key, String(value));
  });
  return `${ROOT}${path}?${query}`;
}

function targetForRemoval(row, includeChannelHistory) {
  const target = row?.target;
  if (!target || typeof target !== 'object' || Array.isArray(target)) {
    throw new Error('This row cannot be removed. Refresh the results and try again.');
  }
  if (target.kind === 'learned_site' || target.kind === 'saved_site' || target.kind === 'system') {
    return { ...target, include_channel_history: Boolean(includeChannelHistory) };
  }
  return { ...target };
}

function requiresSite(source, type) {
  return Boolean(source && type === 'frequencies');
}

function canShowResults(source, type, site) {
  return Boolean(source && type && (!requiresSite(source, type) || site?.configuration_id));
}

// Reuse map: the Administration settings shell owns navigation; shared ui-field,
// ui-select, ui-segmented, ui-data-table, ui-pager, ui-feedback and modal controls
// own appearance and interaction. This module adds only workspace geometry.
export function createRetainedStatisticsWorkspace(deps) {
  const { node, formField, uiSelectFrame, uiSegmentedControl, section, sectionActionHost,
    table, openReadOnlyModal, modalFooter, requestJson, formatNumber, formatDateTime,
    renderItem, renderSource, renderAliasList, signal } = deps;
  const host = node('div', 'retained-statistics-page data-workspace');
  const pickerBody = node('div', 'retained-statistics-picker-body');
  const sourceBlock = node('div', 'retained-statistics-source-block');
  const typeBlock = node('div', 'retained-statistics-type-block');
  const siteBlock = node('div', 'retained-statistics-site-block');
  const resultsBody = node('div', 'retained-statistics-results-body');
  const resultsActions = sectionActionHost();
  const resultsSection = section('Results', resultsBody, resultsActions);
  const selectionSection = section('Choose data', pickerBody);
  const jobNotice = node('div', 'retained-statistics-job-notice');
  jobNotice.setAttribute('role', 'status');
  jobNotice.setAttribute('aria-live', 'polite');
  host.append(selectionSection, jobNotice, resultsSection);

  const state = {
    sourceKind: 'radio_system', source: null, sourceRows: [], sourceOffset: 0,
    sourceSearch: '', sourceMore: false, sourceLoading: false, sourceError: '', sourceRequest: 0,
    type: '', site: null, siteRows: [], siteOffset: 0, siteSearch: '',
    siteMore: false, siteLoading: false, siteError: '', siteRequest: 0,
    resultSearch: '', resultRows: [], resultOffset: 0, resultMore: false,
    resultTotal: null, resultLoading: false, resultRequest: 0, resultError: '',
    job: null, jobTarget: null, jobSource: null, jobTimer: null,
    choiceTimer: null, resultTimer: null,
    disposed: false
  };

  const button = (label, action, classes = 'ui-button ui-button-secondary') => {
    const control = node('button', classes, label);
    control.type = 'button';
    if (action) control.addEventListener('click', action);
    return control;
  };
  const select = (label) => {
    const control = node('select', 'ui-select');
    control.setAttribute('aria-label', label);
    return control;
  };
  const option = (value, label) => {
    const item = node('option', '', label);
    item.value = value;
    return item;
  };
  const feedback = (message, tone = '') => node('div', tone ? `ui-feedback ui-feedback-${tone}` :
    'ui-feedback', message);
  const read = (path, values, requestSignal = signal) => requestJson(queryPath(path, values),
    { csrf: false, signal: requestSignal });
  const choiceName = (row, includeList = true) => [...new Set([row.label, row.site_name, row.channel_name,
    row.radio_system_name, includeList ? row.alias_list_name : ''].filter(Boolean))].join(' · ');
  const choiceLabel = (row, rows, key, includeList = true) => {
    const label = choiceName(row, includeList);
    return rows.some((other) => other[key] !== row[key] && choiceName(other) === choiceName(row)) ?
      `${label} · ${row[key]}` : label;
  };

  const sourceMode = uiSegmentedControl([
    { value: 'radio_system', label: 'Radio system' },
    { value: 'saved_channel', label: 'Saved channel' }
  ], state.sourceKind, (value) => {
    if (value === state.sourceKind) return;
    state.sourceKind = value;
    state.source = null;
    state.type = '';
    clearSite();
    clearResults();
    state.sourceSearch = '';
    sourceSearch.value = '';
    drawSourceChoices();
    drawTypes();
    drawSite();
    drawResults();
    void loadSources(true);
  });
  sourceMode.setAttribute('aria-label', 'Source type');
  sourceMode.classList.add('retained-statistics-source-mode');
  const sourceSearch = node('input', 'ui-input');
  sourceSearch.type = 'search';
  sourceSearch.autocomplete = 'off';
  sourceSearch.placeholder = 'Find a source';
  sourceSearch.setAttribute('aria-label', 'Find a source');
  const sourceSelect = select('Source');
  const sourceMoreButton = button('More sources', () => void loadSources(Boolean(state.sourceError)));
  const sourceMessage = node('small', 'ui-field-detail');
  sourceMessage.setAttribute('role', 'status');
  sourceMessage.setAttribute('aria-live', 'polite');
  const sourceChoices = node('div', 'retained-statistics-source-choices');
  sourceChoices.append(uiSelectFrame(sourceSelect), sourceMoreButton);
  sourceBlock.append(node('h3', 'retained-statistics-label', 'Source'), sourceMode,
    sourceSearch, sourceChoices, sourceMessage);

  const typeChoices = node('div', 'retained-statistics-types ui-segmented');
  typeChoices.setAttribute('role', 'group');
  typeChoices.setAttribute('aria-label', 'Data type');
  typeBlock.append(node('h3', 'retained-statistics-label', 'Data type'), typeChoices);

  const siteSearch = node('input', 'ui-input');
  siteSearch.type = 'search';
  siteSearch.autocomplete = 'off';
  siteSearch.placeholder = 'Find a site';
  siteSearch.setAttribute('aria-label', 'Find a site');
  const siteSelect = select('Site');
  const siteMoreButton = button('More sites', () => void loadSites(Boolean(state.siteError)));
  const siteMessage = node('small', 'ui-field-detail');
  siteMessage.setAttribute('role', 'status');
  siteMessage.setAttribute('aria-live', 'polite');
  const siteChoices = node('div', 'retained-statistics-site-choices');
  siteChoices.append(uiSelectFrame(siteSelect), siteMoreButton);
  siteBlock.append(node('h3', 'retained-statistics-label', 'Site'),
    siteSearch, siteChoices, siteMessage);
  pickerBody.append(sourceBlock, typeBlock, siteBlock);

  const resultSearch = node('input', 'ui-input');
  resultSearch.type = 'search';
  resultSearch.autocomplete = 'off';
  resultSearch.placeholder = 'Search results';
  resultSearch.setAttribute('aria-label', 'Search results');
  const resultSearchForm = node('form', 'retained-statistics-search');
  resultSearchForm.append(resultSearch);
  const resultContext = node('div', 'retained-statistics-context muted');
  const resultCount = node('span', 'muted retained-statistics-result-count');
  const resultStatus = node('div', 'retained-statistics-result-status');
  resultStatus.setAttribute('role', 'status');
  resultStatus.setAttribute('aria-live', 'polite');
  const resultTable = node('div', 'retained-statistics-table');
  const resultPager = node('nav', 'retained-statistics-pager pager ui-pager');
  resultPager.setAttribute('aria-label', 'Results pages');
  resultsBody.append(resultContext, resultSearchForm, resultCount, resultStatus,
    resultTable, resultPager);

  const selectedSource = () => state.source;
  const selectedSite = () => state.site;
  const busyWithJob = () => Boolean(state.job && ['queued', 'running'].includes(state.job.state));
  const tableController = {};

  function clearSite() {
    state.site = null;
    state.siteRows = [];
    state.siteOffset = 0;
    state.siteMore = false;
    state.siteSearch = '';
    state.siteRequest += 1;
    siteSearch.value = '';
  }

  function clearResults() {
    state.resultRows = [];
    state.resultOffset = 0;
    state.resultMore = false;
    state.resultTotal = null;
    state.resultSearch = '';
    state.resultError = '';
    state.resultRequest += 1;
    resultSearch.value = '';
  }

  function drawSourceChoices() {
    const selected = selectedSource();
    sourceSearch.placeholder = state.sourceKind === 'radio_system' ?
      'System, site, channel, or alias list' : 'Channel, site, or alias list';
    sourceSelect.replaceChildren(option('', state.sourceKind === 'radio_system' ?
      'Choose radio system' : 'Choose saved channel'));
    const choices = selected ? [...state.sourceRows, selected] : state.sourceRows;
    if (selected && !state.sourceRows.some((row) => row.source_key === selected.source_key)) {
      sourceSelect.append(option(selected.source_key, choiceLabel(selected, choices, 'source_key')));
    }
    state.sourceRows.forEach((row) => sourceSelect.append(option(row.source_key,
      choiceLabel(row, choices, 'source_key'))));
    sourceSelect.value = selected?.source_key || '';
    sourceSelect.disabled = state.sourceLoading && !state.sourceRows.length;
    sourceSearch.hidden = state.sourceRows.length < 20 && !state.sourceMore && !state.sourceSearch;
    sourceMoreButton.hidden = !state.sourceMore && !state.sourceError;
    sourceMoreButton.textContent = state.sourceError ? 'Retry' : 'More sources';
    sourceMoreButton.disabled = state.sourceLoading;
    sourceMessage.textContent = state.sourceLoading ? 'Loading sources…' : state.sourceError ||
      (!state.sourceRows.length && !selected ? 'No sources found.' : '');
  }

  function drawTypes() {
    typeChoices.replaceChildren(...TYPES.map((entry) => {
      const control = button(entry.label, () => chooseType(entry.value),
        'ui-segmented-option retained-statistics-type-option');
      control.disabled = !typeAvailable(state.sourceKind, selectedSource(), entry.value);
      control.classList.toggle('active', state.type === entry.value);
      control.setAttribute('aria-pressed', String(state.type === entry.value));
      return control;
    }));
  }

  function drawSite() {
    siteBlock.hidden = !requiresSite(selectedSource(), state.type);
    if (siteBlock.hidden) return;
    const selected = selectedSite();
    siteSelect.replaceChildren(option('', 'Choose site'));
    const choices = selected ? [...state.siteRows, selected] : state.siteRows;
    if (selected && !state.siteRows.some((row) => row.configuration_id === selected.configuration_id)) {
      siteSelect.append(option(selected.configuration_id,
        choiceLabel(selected, choices, 'configuration_id')));
    }
    state.siteRows.forEach((row) => siteSelect.append(option(row.configuration_id,
      choiceLabel(row, choices, 'configuration_id'))));
    siteSelect.value = selected?.configuration_id || '';
    siteSelect.disabled = state.siteLoading && !state.siteRows.length;
    siteSearch.hidden = state.siteRows.length < 20 && !state.siteMore && !state.siteSearch;
    siteMoreButton.hidden = !state.siteMore && !state.siteError;
    siteMoreButton.textContent = state.siteError ? 'Retry' : 'More sites';
    siteMoreButton.disabled = state.siteLoading;
    siteMessage.textContent = state.siteLoading ? 'Loading sites…' : state.siteError ||
      (!state.siteRows.length && !selected ? 'No sites found.' : '');
  }

  async function loadSources(reset) {
    if (state.disposed || state.sourceLoading && !reset) return;
    const generation = ++state.sourceRequest;
    state.sourceError = '';
    if (reset) {
      state.sourceRows = [];
      state.sourceOffset = 0;
      state.sourceMore = false;
    }
    state.sourceLoading = true;
    drawSourceChoices();
    try {
      const response = await read('/sources', {
        kind: state.sourceKind, q: state.sourceSearch, limit: CHOICE_SIZE,
        offset: state.sourceOffset
      });
      if (state.disposed || generation !== state.sourceRequest) return;
      const rows = Array.isArray(response.rows) ? response.rows : [];
      state.sourceRows = state.sourceRows.concat(rows);
      state.sourceOffset = Number(response.next_offset ?? state.sourceOffset + rows.length);
      state.sourceMore = response.has_more === true;
    } catch (error) {
      if (state.disposed || generation !== state.sourceRequest || error?.name === 'AbortError') return;
      state.sourceError = error.message || 'Sources could not be loaded.';
    } finally {
      if (generation === state.sourceRequest) {
        state.sourceLoading = false;
        drawSourceChoices();
      }
    }
  }

  async function loadSites(reset) {
    if (state.disposed || !selectedSource() || state.siteLoading && !reset) return;
    const generation = ++state.siteRequest;
    state.siteError = '';
    if (reset) {
      state.siteRows = [];
      state.siteOffset = 0;
      state.siteMore = false;
    }
    state.siteLoading = true;
    drawSite();
    try {
      const response = await read('/sites', {
        source_kind: state.sourceKind, source_key: selectedSource().source_key,
        q: state.siteSearch, limit: CHOICE_SIZE, offset: state.siteOffset
      });
      if (state.disposed || generation !== state.siteRequest) return;
      const rows = Array.isArray(response.rows) ? response.rows : [];
      state.siteRows = state.siteRows.concat(rows);
      state.siteOffset = Number(response.next_offset ?? state.siteOffset + rows.length);
      state.siteMore = response.has_more === true;
    } catch (error) {
      if (state.disposed || generation !== state.siteRequest || error?.name === 'AbortError') return;
      state.siteError = error.message || 'Sites could not be loaded.';
    } finally {
      if (generation === state.siteRequest) {
        state.siteLoading = false;
        drawSite();
      }
    }
  }

  function chooseType(value) {
    if (state.type === value || !selectedSource()) return;
    state.type = value;
    clearSite();
    clearResults();
    drawTypes();
    drawSite();
    drawResults();
    if (requiresSite(selectedSource(), state.type)) void loadSites(true);
    else void loadResults();
  }

  function resultsTable() {
    const identity = ['radios', 'talkgroups'].includes(state.type);
    const columns = [
      { id: 'item', label: ({ frequencies: 'Frequency', radios: 'Radio', talkgroups: 'Talkgroup',
        sites: 'Site', channels: 'Saved channel', systems: 'Radio system' })[state.type],
        render: renderItem, sortable: false }
    ];
    if (identity) columns.push({ id: 'alias-group', label: 'Alias group',
      render: (row) => row.alias_group || '—', sortable: false });
    if (state.type !== 'frequencies') columns.push({ id: 'alias-list', label: 'Alias list',
      render: (row) => renderAliasList(row) || '—', sortable: false });
    if (identity || state.type === 'frequencies') columns.push({ id: 'count',
      label: identity ? 'Calls' : 'Observations', className: 'numeric', sortable: false,
      render: (row) => {
        const count = identity ? row.logical_call_count ?? row.observation_count : row.observation_count;
        return count == null ? '—' : formatNumber(count);
      } });
    columns.push({ id: 'last-seen', label: 'Last seen',
      render: (row) => formatDateTime(row.last_seen_ms) || '—', sortable: false },
      { id: 'action', label: 'Action', render: (row) => {
        const action = button('Review', () => reviewRow(row));
        action.disabled = busyWithJob();
        action.setAttribute('aria-label', `Review removal of ${row.label || 'item'}`);
        return action;
      }, sortable: false });
    return table(state.resultRows, columns, 'No matching results', {
      type: `retained-statistics-${state.type}`, layoutMenuHost: resultsActions,
      tableClass: 'ui-data-table-quiet', mobileCards: true, sortable: false,
      controller: tableController
    });
  }

  function drawPager() {
    resultPager.replaceChildren();
    const length = state.resultRows.length;
    const first = length ? state.resultOffset + 1 : 0;
    const last = state.resultOffset + length;
    const range = state.resultTotal == null ? `${formatNumber(first)}–${formatNumber(last)}` :
      `${formatNumber(first)}–${formatNumber(last)} of ${formatNumber(state.resultTotal)}`;
    const previous = button('Previous', () => {
      state.resultOffset = Math.max(0, state.resultOffset - PAGE_SIZE);
      void loadResults();
    });
    const next = button('Next', () => {
      state.resultOffset += PAGE_SIZE;
      void loadResults();
    });
    previous.disabled = state.resultLoading || state.resultOffset === 0;
    next.disabled = state.resultLoading || !state.resultMore;
    resultPager.append(node('span', 'muted', range), previous, next);
    resultPager.hidden = !length && !state.resultOffset ||
      (state.resultOffset === 0 && !state.resultMore);
  }

  function drawResults() {
    const ready = canShowResults(selectedSource(), state.type, selectedSite());
    resultContext.hidden = !ready;
    resultSearchForm.hidden = !ready;
    resultCount.hidden = !ready;
    resultTable.replaceChildren();
    if (!ready) {
      resultContext.textContent = '';
      resultCount.textContent = '';
      resultStatus.replaceChildren(feedback(!selectedSource() || !state.type ?
        'Choose a source and data type.' : 'Choose a site to see frequencies.'));
      resultPager.hidden = true;
      return;
    }
    resultSearch.placeholder = ({ frequencies: 'Frequency in MHz or Hz',
      radios: 'Radio alias, over-the-air name, or ID', talkgroups: 'Talkgroup alias or ID',
      sites: 'Site, channel, or alias list', channels: 'Channel, site, or alias list',
      systems: 'System, site, channel, or alias list' })[state.type];
    resultContext.replaceChildren(renderSource(selectedSource(),
      choiceLabel(selectedSource(), state.sourceRows, 'source_key', false)));
    if (selectedSite()) resultContext.append(' · ', renderSource(selectedSite(),
      choiceLabel(selectedSite(), state.siteRows, 'configuration_id', false)));
    const contextList = renderAliasList(selectedSite() || selectedSource());
    if (contextList) resultContext.append(' · ', contextList);
    resultContext.append(' · ', TYPES.find((entry) => entry.value === state.type)?.label || '');
    resultStatus.replaceChildren();
    if (state.resultLoading) {
      resultStatus.append(feedback('Loading results…', 'loading'));
    } else if (state.resultError) {
      resultStatus.append(feedback(state.resultError, 'error'));
      resultStatus.append(button('Retry', () => void loadResults()));
    } else if (!state.resultRows.length) {
      resultStatus.append(feedback('No matching results.'));
    } else {
      resultTable.append(resultsTable());
    }
    if (state.resultTotal != null) {
      resultCount.textContent = `${formatNumber(state.resultTotal)} result${state.resultTotal === 1 ? '' : 's'}`;
    } else resultCount.textContent = state.resultRows.length ? 'Results' : '';
    drawPager();
  }

  async function loadResults() {
    if (state.disposed || !canShowResults(selectedSource(), state.type, selectedSite())) return;
    const generation = ++state.resultRequest;
    state.resultLoading = true;
    state.resultError = '';
    drawResults();
    try {
      const response = await read('/results', {
        source_kind: state.sourceKind, source_key: selectedSource().source_key,
        data_type: state.type, site_configuration_id: selectedSite()?.configuration_id,
        q: state.resultSearch, limit: PAGE_SIZE, offset: state.resultOffset
      });
      if (state.disposed || generation !== state.resultRequest) return;
      state.resultRows = Array.isArray(response.rows) ? response.rows : [];
      state.resultMore = response.has_more === true;
      state.resultTotal = response.total_count != null && Number.isInteger(Number(response.total_count)) ?
        Number(response.total_count) : null;
      if (!state.resultRows.length && state.resultOffset > 0) {
        state.resultOffset = Math.max(0, state.resultOffset - PAGE_SIZE);
        void loadResults();
        return;
      }
    } catch (error) {
      if (state.disposed || generation !== state.resultRequest || error?.name === 'AbortError') return;
      state.resultRows = [];
      state.resultError = error.message || 'Results could not be loaded.';
    } finally {
      if (generation === state.resultRequest) {
        state.resultLoading = false;
        drawResults();
      }
    }
  }

  function confirmationPhrase(row, includeChannelHistory) {
    if (row.target?.kind === 'system') return row.label;
    if (['saved_site', 'learned_site'].includes(row.target?.kind) && includeChannelHistory) return 'DELETE SITE';
    return '';
  }

  function removalImpact(row, includeChannelHistory) {
    switch (row.target?.kind) {
      case 'frequency': return 'Removes this observed frequency from the selected site.';
      case 'radio': return 'Removes this radio ID and its directly owned retained data.';
      case 'talkgroup': return 'Removes this talkgroup and its directly owned retained data.';
      case 'conventional_radio':
      case 'conventional_talkgroup':
        return 'Removes this ID’s saved summary for the listed frequency and timeslot. Call history may remain.';
      case 'learned_site': return includeChannelHistory ?
        'Removes this learned site and associated saved-channel history.' :
        'Removes this learned site. Saved-site inventory remains.';
      case 'saved_site': return includeChannelHistory ?
        'Removes site inventory and saved-channel history.' :
        'Removes site inventory, including frequencies. Channel history remains.';
      case 'channel': return 'Removes directly owned statistics for this saved channel. Its configuration remains.';
      case 'system': return includeChannelHistory ?
        'Removes this system and associated saved-channel history.' : 'Removes system-owned statistics.';
      default: return 'Removes this retained statistic.';
    }
  }

  function reviewRow(row) {
    if (busyWithJob()) return;
    const body = node('div', 'retained-statistics-review editor-workspace');
    const summary = node('div', 'ui-fact retained-statistics-review-target');
    summary.append(node('strong', '', row.label || 'Selected item'));
    if (row.detail) summary.append(node('small', 'muted', row.detail));
    const impact = node('p', 'retained-statistics-impact');
    const reappearance = node('div', 'ui-notice ui-notice-warning',
      'Receiving continues; removed rows may return. Shared Alias Activity totals may remain.');
    const errorHost = node('div', 'retained-statistics-review-error');
    errorHost.setAttribute('role', 'alert');
    const cancel = button('Cancel');
    const confirm = button('Remove', null, 'ui-button ui-button-danger');
    const footer = modalFooter(cancel, confirm);
    footer.classList.add('retained-statistics-review-actions');
    let includeChannelHistory = false;
    let phraseInput = null;
    let modal = null;
    let submittedTarget = null;
    let requestId = null;
    const scoped = ['learned_site', 'saved_site', 'system'].includes(row.target?.kind);
    const scopeChoices = row.target?.kind === 'system' ? [
      { value: 'record', label: 'System statistics' },
      { value: 'history', label: 'System + channel history' }
    ] : row.target?.kind === 'saved_site' ? [
      { value: 'record', label: 'Site inventory' },
      { value: 'history', label: 'Site inventory + channel history' }
    ] : [
      { value: 'record', label: 'Learned site' },
      { value: 'history', label: 'Learned site + channel history' }
    ];
    const scope = scoped ? uiSegmentedControl(scopeChoices, 'record', (value) => {
      includeChannelHistory = value === 'history';
      drawReview();
    }) : null;
    if (scope) {
      scope.setAttribute('aria-label', 'Removal scope');
      scope.classList.add('retained-statistics-scope-control');
      const scopeField = node('div', 'admin-form-field ui-field');
      scopeField.append(node('span', 'admin-form-label ui-field-label', 'Scope'), scope);
      body.append(scopeField);
    }
    body.prepend(summary);
    body.append(impact, reappearance, errorHost, footer);

    function drawReview() {
      impact.textContent = removalImpact(row, includeChannelHistory);
      const old = body.querySelector('.retained-statistics-phrase');
      old?.remove();
      phraseInput = null;
      const phrase = confirmationPhrase(row, includeChannelHistory);
      if (phrase) {
        phraseInput = node('input', 'ui-input');
        phraseInput.type = 'text';
        phraseInput.autocomplete = 'off';
        phraseInput.spellcheck = false;
        phraseInput.addEventListener('input', updateConfirm);
        const field = formField(`Type ${phrase} to confirm`, phraseInput);
        field.classList.add('retained-statistics-phrase');
        body.insertBefore(field, reappearance);
      }
      updateConfirm();
    }

    function updateConfirm() {
      const phrase = confirmationPhrase(row, includeChannelHistory);
      confirm.disabled = Boolean(phrase && phraseInput?.value !== phrase);
    }

    cancel.addEventListener('click', () => modal?.close());
    confirm.addEventListener('click', async () => {
      if (confirm.disabled || !modal) return;
      modal.setBusy(true);
      confirm.disabled = true;
      errorHost.replaceChildren();
      try {
        if (!submittedTarget) {
          submittedTarget = targetForRemoval(row, includeChannelHistory);
          if (submittedTarget.kind === 'frequency') {
            submittedTarget.expected_site_key = submittedTarget.expected_site_key || selectedSite()?.site_key;
          }
          requestId = crypto.randomUUID();
          scope?.querySelectorAll('button').forEach((control) => { control.disabled = true; });
        }
        const response = await requestJson(`${ROOT}/deletions`, {
          method: 'POST', body: { request_id: requestId, target: submittedTarget },
          signal, timeoutMs: 30000
        });
        if (state.disposed) return;
        modal.setBusy(false);
        modal.close();
        state.job = response;
        state.jobTarget = submittedTarget;
        state.jobSource = { kind: state.sourceKind, key: selectedSource()?.source_key };
        drawJob();
        void pollJob();
      } catch (error) {
        if (state.disposed || error?.name === 'AbortError') return;
        if (error?.status === 409) {
          modal.setBusy(false);
          errorHost.replaceChildren(feedback('Removal request conflicted. Close and review the item again.',
            'error'));
          confirm.disabled = true;
          return;
        }
        errorHost.replaceChildren(feedback(error.message || 'Removal could not be started.', 'error'));
        modal.setBusy(false);
        updateConfirm();
      }
    });
    modal = openReadOnlyModal(`Remove ${row.label || 'item'}?`, body, {
      id: 'retained-statistics-review', className: 'retained-statistics-modal',
      returnFocusSelector: '.retained-statistics-result-count'
    });
    if (!modal) return;
    drawReview();
  }

  function drawJob() {
    jobNotice.replaceChildren();
    if (!state.job) return;
    const stateName = String(state.job.state || '').toLowerCase();
    if (stateName === 'queued') jobNotice.append(feedback('Removal queued…', 'loading'));
    else if (stateName === 'running') jobNotice.append(feedback('Removing retained statistics…', 'loading'));
    else if (stateName === 'succeeded') {
      const message = state.job.outcome === 'stale_site' ? 'Site changed. Choose it again.' :
        state.job.outcome === 'not_found' ? 'The item was already gone.' : 'Removal complete.';
      jobNotice.append(feedback(message));
    } else if (stateName === 'failed') {
      jobNotice.append(feedback(state.job.error || 'Removal failed. Try again.', 'error'));
    }
    drawResults();
  }

  async function pollJob() {
    if (state.disposed || !busyWithJob()) return;
    try {
      const response = await requestJson(`${ROOT}/deletions/${encodeURIComponent(state.job.job_id)}`,
        { csrf: false, signal });
      if (state.disposed) return;
      state.job = response;
      drawJob();
      if (busyWithJob()) state.jobTimer = window.setTimeout(pollJob, JOB_POLL_MS);
      else {
        const target = state.jobTarget;
        const submittedSource = state.jobSource;
        const staleSite = state.job.state === 'succeeded' && state.job.outcome === 'stale_site';
        const sourceIsCurrent = selectedSource()?.source_key === submittedSource?.key &&
          state.sourceKind === submittedSource?.kind;
        const removedSource = state.job.state === 'succeeded' && !staleSite && sourceIsCurrent &&
          (target?.kind === 'system' && state.sourceKind === 'radio_system' ||
            target?.kind === 'channel' && state.sourceKind === 'saved_channel');
        if (removedSource) {
          state.source = null;
          state.type = '';
          clearSite();
          clearResults();
          drawSourceChoices();
          drawTypes();
          drawSite();
          drawResults();
          void loadSources(true);
        } else {
          const removedSelectedSite = staleSite || state.job.state === 'succeeded' &&
            ['saved_site', 'channel'].includes(target?.kind) &&
            selectedSite()?.configuration_id === target?.configuration_id;
          if (removedSelectedSite) {
            clearSite();
            clearResults();
            drawSite();
            drawResults();
          } else void loadResults();
          if (requiresSite(selectedSource(), state.type)) void loadSites(true);
        }
      }
    } catch (error) {
      if (state.disposed || error?.name === 'AbortError') return;
      jobNotice.replaceChildren(feedback('Removal status is unavailable.', 'error'),
        button('Retry status', () => void pollJob()));
    }
  }

  sourceSelect.addEventListener('change', () => {
    state.source = state.sourceRows.find((row) => row.source_key === sourceSelect.value) ||
      (state.source?.source_key === sourceSelect.value ? state.source : null);
    state.type = '';
    clearSite();
    clearResults();
    drawTypes();
    drawSite();
    drawResults();
  });
  sourceSearch.addEventListener('input', () => {
    window.clearTimeout(state.choiceTimer);
    state.sourceSearch = sourceSearch.value.trim();
    state.choiceTimer = window.setTimeout(() => void loadSources(true), 250);
  });
  siteSelect.addEventListener('change', () => {
    state.site = state.siteRows.find((row) => row.configuration_id === siteSelect.value) ||
      (state.site?.configuration_id === siteSelect.value ? state.site : null);
    clearResults();
    drawSite();
    drawResults();
    if (selectedSite()) void loadResults();
  });
  siteSearch.addEventListener('input', () => {
    window.clearTimeout(state.choiceTimer);
    state.siteSearch = siteSearch.value.trim();
    state.choiceTimer = window.setTimeout(() => void loadSites(true), 250);
  });
  resultSearchForm.addEventListener('submit', (event) => {
    event.preventDefault();
    window.clearTimeout(state.resultTimer);
    state.resultSearch = resultSearch.value.trim();
    state.resultOffset = 0;
    void loadResults();
  });
  resultSearch.addEventListener('input', () => {
    window.clearTimeout(state.resultTimer);
    state.resultTimer = window.setTimeout(() => {
      state.resultSearch = resultSearch.value.trim();
      state.resultOffset = 0;
      void loadResults();
    }, 300);
  });
  signal?.addEventListener('abort', () => {
    state.disposed = true;
    window.clearTimeout(state.choiceTimer);
    window.clearTimeout(state.resultTimer);
    window.clearTimeout(state.jobTimer);
  }, { once: true });
  drawSourceChoices();
  drawTypes();
  drawSite();
  drawResults();
  void loadSources(true);
  return host;
}

export { canShowResults, queryPath, targetForRemoval };
