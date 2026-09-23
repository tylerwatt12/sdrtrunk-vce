const API_ROOT = '/api/v1/admin/radioreference';

export const RADIO_REFERENCE_IMPORT_PATHS = Object.freeze({
  configuration: API_ROOT,
  countries: `${API_ROOT}/countries`,
  states: `${API_ROOT}/states`,
  counties: `${API_ROOT}/counties`,
  browse: `${API_ROOT}/browse`,
  browseCatalog: `${API_ROOT}/browse/catalog`,
  systemDetails: `${API_ROOT}/systems/details`,
  systemSites: `${API_ROOT}/systems/sites`,
  siteCatalog: `${API_ROOT}/systems/sites/catalog`,
  systemTalkgroups: `${API_ROOT}/systems/talkgroups`,
  talkgroupCatalog: `${API_ROOT}/systems/talkgroups/catalog`,
  bookmarks: `${API_ROOT}/bookmarks`,
  conventionalCategories: `${API_ROOT}/conventional/categories`,
  conventionalFrequencies: `${API_ROOT}/conventional/frequencies`,
  sitePreview: `${API_ROOT}/imports/site/preview`,
  conventionalPreview: `${API_ROOT}/imports/conventional/preview`,
  talkgroupsPreview: `${API_ROOT}/imports/talkgroups/preview`,
  apply: (previewId) => `${API_ROOT}/imports/${encodeURIComponent(String(previewId))}/apply`
});

const SITE_LIMIT = 50;
const TALKGROUP_LIMIT = 50;
const FREQUENCY_LIMIT = 50;

function rows(documentValue) {
  if (Array.isArray(documentValue)) return documentValue;
  for (const key of ['items', 'rows', 'results', 'talkgroups', 'frequencies', 'sites', 'categories']) {
    if (Array.isArray(documentValue?.[key])) return documentValue[key];
  }
  return [];
}

function firstValue(value, keys, fallback = null) {
  for (const key of keys) {
    const candidate = value?.[key];
    if (candidate !== null && candidate !== undefined && candidate !== '') return candidate;
  }
  return fallback;
}

function integerValue(value, keys) {
  const numeric = Number(firstValue(value, keys));
  return Number.isSafeInteger(numeric) && numeric > 0 ? numeric : null;
}

function textValue(value, keys, fallback = '') {
  const result = firstValue(value, keys, fallback);
  return result === null || result === undefined ? fallback : String(result).trim();
}

function totalValue(documentValue, fallback) {
  const total = Number(firstValue(documentValue, ['total_count', 'total_items', 'total'], fallback));
  return Number.isSafeInteger(total) && total >= 0 ? total : fallback;
}

function hasMore(documentValue, offset, limit, visible) {
  const explicit = firstValue(documentValue, ['has_more', 'hasMore']);
  if (typeof explicit === 'boolean') return explicit;
  return offset + visible < totalValue(documentValue, offset + visible);
}

function aliasListId(value) {
  return integerValue(value, ['aliasListId', 'alias_list_id', 'id']);
}

function aliasListFamily(value) {
  return textValue(value, ['family', 'protocol_family', 'protocol']).toUpperCase();
}

function compatibleFamily(value) {
  const family = textValue(value, ['alias_list_family', 'protocol_family', 'family', 'protocol',
    'decoder_type', 'recommended_decoder', 'type']).toUpperCase();
  if (family.includes('P25') || family.includes('PROJECT 25')) return 'P25';
  if (family.includes('DMR')) return 'DMR';
  if (family.includes('NXDN')) return 'NXDN';
  if (family.includes('AM') || family.includes('FM') || family.includes('ANALOG')) return 'ANALOG';
  return '';
}

function query(path, values) {
  const parameters = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') parameters.set(key, String(value));
  });
  const suffix = parameters.toString();
  return suffix ? `${path}?${suffix}` : path;
}

function previewId(value) {
  return textValue(value, ['preview_id', 'previewId', 'id']);
}

function systemId(value) {
  // System details also contain a radio network's "system_id"; its RadioReference database ID is "id".
  return integerValue(value?.detail || value, ['id', 'sid', 'system_id', 'systemId']);
}

function ownerId(value) {
  return integerValue(value?.detail || value, ['owner_id', 'ownerId', 'agency_id', 'agencyId', 'id']);
}

function entryKind(value) {
  const kind = textValue(value, ['type', 'kind'], textValue(value?.detail, ['type', 'kind'])).toUpperCase();
  return kind.includes('TRUNK') || kind.includes('SYSTEM') ? 'TRUNKED_SYSTEM' : 'CONVENTIONAL_AGENCY';
}

function frequencyHz(value) {
  if (typeof value === 'number') return value < 100000 ? Math.round(value * 1_000_000) : Math.round(value);
  const numeric = Number(firstValue(value, ['frequency_hz', 'frequencyHz', 'downlink_hz', 'downlinkHz',
    'output_hz', 'outputHz', 'frequency']));
  if (!Number.isFinite(numeric) || numeric <= 0) return 0;
  return numeric < 100000 ? Math.round(numeric * 1_000_000) : Math.round(numeric);
}

function siteChannels(site, detail = null) {
  const candidates = [site?.channels, site?.frequencies, detail?.channels, detail?.frequencies];
  return candidates.find(Array.isArray) || [];
}

function siteModulation(site) {
  const modulation = textValue(site, ['detected_modulation', 'detectedModulation', 'p25_modulation',
    'p25Modulation', 'modulation']);
  return modulation || 'Not applicable';
}

function siteUsesTdmaControl(site, detail = null) {
  const value = firstValue(site, ['tdma_control_channel', 'tdmaControlChannel'],
    firstValue(detail?.site || detail, ['tdma_control_channel', 'tdmaControlChannel']));
  if (value !== null && value !== undefined) {
    return value === true || value === 1 || String(value).toLowerCase() === 'true';
  }
  const protocol = textValue(site, ['protocol_id', 'protocol', 'decoder_type'],
    textValue(detail?.site || detail, ['protocol_id', 'protocol', 'decoder_type'])).toLowerCase();
  return protocol.includes('phase ii') || protocol.includes('phase 2') || protocol.includes('p25-phase2') ||
    protocol.includes('tdma');
}

function detectedSiteModulation(system, site, detail = null) {
  const family = compatibleFamily(system) || compatibleFamily(detail) || compatibleFamily(site);
  if (family !== 'P25' || siteUsesTdmaControl(site, detail)) return 'Not applicable';
  const description = [textValue(site, ['name', 'description']),
    textValue(detail?.site || detail, ['name', 'description'])].join(' ').toLowerCase();
  if (description.includes('simul')) return 'CQPSK';
  const structured = [siteModulation(site), siteModulation(detail?.site || detail)].join(' ').toUpperCase();
  return structured.includes('CQPSK') || structured.includes('LSM') ? 'CQPSK' : 'C4FM';
}

function talkgroupId(value) {
  return integerValue(value?.talkgroup || value, ['talkgroup_id', 'talkgroupId', 'id']);
}

function talkgroupValue(value) {
  return value?.talkgroup || value;
}

function talkgroupCategory(value) {
  const category = firstValue(value, ['category', 'category_name', 'group']);
  if (category && typeof category === 'object') {
    return textValue(category, ['name', 'category_name', 'label'], '—');
  }
  return category === null || category === undefined || category === '' ? '—' : String(category);
}

function talkgroupChanges(value) {
  if (!Array.isArray(value?.changes)) return [];
  const owned = new Set(['name', 'description', 'group']);
  return value.changes.filter((change) => owned.has(textValue(change, ['field']).toLowerCase()));
}

function frequencyId(value) {
  return integerValue(value, ['frequency_id', 'frequencyId', 'id']);
}

function importStatus(value) {
  const status = textValue(value, ['import_status', 'importStatus', 'status'], 'NOT_PRESENT').toUpperCase();
  if (status === 'IDENTICAL') return { label: 'Identical', tone: 'success' };
  if (status === 'DIFFERENT') return { label: 'Different', tone: 'warning' };
  if (status === 'NOT_COMPATIBLE' || status === 'INCOMPATIBLE') {
    return { label: 'Not compatible', tone: 'danger' };
  }
  return { label: 'Not present', tone: 'neutral' };
}

function bookmarkKey(value) {
  return [value?.kind, firstValue(value, ['ownerKind', 'owner_kind'], ''),
    Number(firstValue(value, ['parentId', 'parent_id'], 0)), Number(value?.id || 0)].join(':');
}

function normalizeBookmark(value) {
  return {
    kind: textValue(value, ['kind']), id: integerValue(value, ['id']),
    parentId: Number(firstValue(value, ['parent_id', 'parentId'], 0)),
    ownerKind: textValue(value, ['owner_kind', 'ownerKind']),
    name: textValue(value, ['name']),
    parentName: textValue(value, ['parent_name', 'parentName']),
    preferredAliasListId: integerValue(value, ['preferred_alias_list_id', 'preferredAliasListId'])
  };
}

function resultCount(value, key) {
  const summary = value?.summary || value?.counts || value || {};
  const numeric = Number(firstValue(summary, [key, key === 'add' ? 'added' : key === 'update' ? 'updated' :
    key === 'unchanged' ? 'identical' : key], 0));
  return Number.isSafeInteger(numeric) && numeric >= 0 ? numeric : 0;
}

export function createRadioReferenceImportWorkspace(dependencies) {
  const {
    node, iconGlyph, formField, uiSelectFrame, uiPill, uiStatus, uiSegmentedControl, table,
    openReadOnlyModal, closeReadOnlyModal, requestJson, formatFrequency, formatNumber, href, anchor,
    modalFooter, directoryTimeoutMs = 15_000, mutationTimeoutMs = 65_000,
    onLocationSaved = null
  } = dependencies;

  const host = node('div', 'radioreference-import-workspace data-workspace');
  const state = {
    configuration: null,
    aliasLists: [],
    countries: [],
    initialized: false,
    initializing: false,
    country: null,
    region: null,
    county: null,
    browseRows: [],
    browseDocument: null,
    browseTab: 'browse',
    bookmarks: [],
    siteCatalogs: new Map(),
    talkgroupCatalogs: new Map(),
    talkgroupCatalogId: null,
    siteSearch: '',
    siteSort: 'number',
    talkgroupStatus: 'ALL',
    browseSequence: 0,
    detailSequence: 0,
    activeSystemId: null,
    selectedTalkgroups: new Set(),
    talkgroupAliasListId: null,
    talkgroupOffset: 0,
    talkgroupSearch: '',
    talkgroupCategoryId: null
  };

  const feedback = (message, kind = '') => node('div', `ui-feedback${kind ? ` ui-feedback-${kind}` : ''}`,
    message);
  const empty = (title, message) => {
    const value = node('div', 'ui-empty-state');
    value.append(node('h3', '', title), node('p', '', message));
    return value;
  };
  const button = (label, className = 'ui-button ui-button-secondary') => {
    const control = node('button', className, label);
    control.type = 'button';
    return control;
  };
  const select = () => node('select', 'ui-select');
  const input = (type = 'text') => {
    const control = node('input', 'ui-input');
    control.type = type;
    return control;
  };
  const selectFrame = (control) => uiSelectFrame(control);
  const setOptions = (control, values, selected, placeholder = '', optional = false) => {
    control.replaceChildren();
    if (optional || !values.length) {
      const option = node('option', '', placeholder);
      option.value = '';
      control.append(option);
    }
    values.forEach((value) => {
      const id = integerValue(value, ['id', 'country_id', 'state_id', 'county_id', 'category_id',
        'sub_category_id']);
      if (!id) return;
      const abbreviation = textValue(value, ['abbreviation', 'abbr']);
      const label = textValue(value, ['name', 'label', 'category_name', 'sub_category_name'], `Item ${id}`);
      const option = node('option', '', `${label}${abbreviation ? ` (${abbreviation})` : ''}`);
      option.value = String(id);
      control.append(option);
    });
    const requested = String(selected || '');
    if (requested && [...control.options].some((option) => option.value === requested)) control.value = requested;
    else if (!optional && control.options.length) control.selectedIndex = 0;
    return Boolean(control.value);
  };
  const aliasOptions = (family = '') => {
    const normalized = String(family || '').toUpperCase();
    const exact = state.aliasLists.filter((value) => !normalized || aliasListFamily(value) === normalized);
    return normalized ? exact : state.aliasLists;
  };
  const aliasSelect = (family = '', selectedId = null) => {
    const control = select();
    control.required = true;
    const options = aliasOptions(family);
    const placeholder = node('option', '', options.length ? 'Choose an Alias List' : 'No compatible Alias Lists');
    placeholder.value = '';
    control.append(placeholder);
    options.forEach((value) => {
      const id = aliasListId(value);
      if (!id) return;
      const option = node('option', '', textValue(value, ['name'], `Alias List ${id}`));
      option.value = String(id);
      control.append(option);
    });
    const requested = String(selectedId || '');
    if (requested && [...control.options].some((option) => option.value === requested)) control.value = requested;
    else if (control.options.length === 2) control.selectedIndex = 1;
    return control;
  };
  const api = (path, options = {}) => requestJson(path, {
    csrf: options.method && options.method !== 'GET' ? undefined : false,
    timeoutMs: options.timeoutMs || directoryTimeoutMs,
    ...options
  });
  const bookmarked = (value) => state.bookmarks.some((entry) => bookmarkKey(entry) === bookmarkKey(value));
  const bookmarkForEntry = (entry) => {
    const trunked = entryKind(entry) === 'TRUNKED_SYSTEM';
    return {
      kind: trunked ? 'TRUNKED_SYSTEM' : 'CONVENTIONAL_AGENCY',
      id: trunked ? systemId(entry) : ownerId(entry),
      parentId: 0,
      ownerKind: trunked ? '' : textValue(entry?.detail || entry, ['kind', 'owner_kind'], 'AGENCY'),
      name: textValue(entry, ['name'], 'Unnamed'),
      parentName: textValue(entry, ['breadcrumb', 'parentName', 'parent_name'])
    };
  };
  const toggleBookmark = async (value, control, onChanged) => {
    if (control.disabled) return;
    control.disabled = true;
    try {
      const targetSystem = value.kind === 'TALKGROUP_CATEGORY' ? value.parentId : value.id;
      const preferred = targetSystem === state.activeSystemId &&
        (value.kind === 'TRUNKED_SYSTEM' || value.kind === 'TALKGROUP_CATEGORY') ?
        state.talkgroupAliasListId : null;
      const next = await api(RADIO_REFERENCE_IMPORT_PATHS.bookmarks, {
        method: bookmarked(value) ? 'DELETE' : 'PUT',
        body: { ...value, preferredAliasListId: preferred }
      });
      state.bookmarks = rows(next).map(normalizeBookmark);
      const saved = bookmarked(value);
      control.textContent = saved ? '★' : '☆';
      control.setAttribute('aria-pressed', String(saved));
      control.setAttribute('aria-label', `${saved ? 'Remove' : 'Add'} bookmark: ${value.name}`);
      control.title = saved ? 'Remove bookmark' : 'Bookmark this item';
      control.disabled = false;
      onChanged?.();
    } catch (error) {
      control.disabled = false;
      control.title = `Bookmark failed: ${error.message}`;
    }
  };
  const starButton = (value, onChanged) => {
    const saved = bookmarked(value);
    const control = button(saved ? '★' : '☆',
      'ui-button ui-button-secondary ui-icon-button radioreference-bookmark');
    control.setAttribute('aria-label', `${saved ? 'Remove' : 'Add'} bookmark: ${value.name}`);
    control.setAttribute('aria-pressed', String(saved));
    control.title = saved ? 'Remove bookmark' : 'Bookmark this item';
    control.addEventListener('click', () => toggleBookmark(value, control, onChanged));
    return control;
  };

  const internalPager = ({ offset, limit, visible, total, more, label, onPage }) => {
    const pager = node('nav', 'radioreference-pager ui-pager ui-pager-surface');
    pager.setAttribute('aria-label', `${label} pages`);
    const first = visible ? offset + 1 : 0;
    const last = offset + visible;
    pager.append(node('span', 'muted', total === null ? `${label} ${formatNumber(first)}–${formatNumber(last)}` :
      `${label} ${formatNumber(first)}–${formatNumber(last)} of ${formatNumber(total)}`));
    const previous = button('Previous');
    previous.disabled = offset <= 0;
    previous.addEventListener('click', () => onPage(Math.max(0, offset - limit)));
    const next = button('Next');
    next.disabled = !more;
    next.addEventListener('click', () => onPage(offset + limit));
    const actions = node('div', 'ui-action-row');
    actions.append(previous, next);
    pager.append(actions);
    return pager;
  };

  const renderPreviewDetails = (preview, kind) => {
    const wrapper = node('div', 'radioreference-preview');
    const operation = textValue(preview, ['operation', 'action']);
    const summary = node('div', 'radioreference-preview-summary');
    if (kind === 'talkgroups') {
      [['Add', resultCount(preview, 'add')], ['Update', resultCount(preview, 'update')],
        ['Unchanged', resultCount(preview, 'unchanged')]].forEach(([label, count]) => {
        const card = node('div', 'ui-summary-card');
        card.append(iconGlyph(label === 'Add' ? 'icon-plus' : label === 'Update' ? 'icon-refresh' : 'icon-aliases'),
          node('strong', '', formatNumber(count)), node('span', '', label));
        summary.append(card);
      });
      wrapper.append(summary, node('p', 'muted',
        'Updates replace only RadioReference-owned name, description, and group fields. Local handling stays intact.'));
      const previewRows = Array.isArray(preview?.rows) ? preview.rows : [];
      const changed = previewRows.length === 1 && importStatus(previewRows[0]).label === 'Different' ?
        talkgroupChanges(previewRows[0]) : [];
      if (changed.length) {
        const detail = node('div', 'radioreference-talkgroup-changes');
        detail.append(node('strong', '', 'RadioReference fields changing'));
        const list = node('dl');
        changed.forEach((change) => {
          const field = textValue(change, ['field']);
          const before = textValue(change, ['before']) || 'Empty';
          const after = textValue(change, ['after']) || 'Empty';
          const values = node('dd');
          values.append(node('span', '', before), node('span', 'muted', '→'), node('strong', '', after));
          list.append(node('dt', '', `${field.charAt(0).toUpperCase()}${field.slice(1)}`), values);
        });
        detail.append(list);
        wrapper.append(detail);
      }
    } else {
      const channel = preview?.channel || preview?.candidate || preview?.result || {};
      const name = textValue(channel, ['name', 'channel_name'], textValue(preview, ['channel_name'], 'Channel'));
      const protocol = textValue(channel, ['protocol', 'protocol_name', 'protocol_id', 'decoder_type'],
        textValue(preview, ['protocol', 'decoder_type']));
      const frequencies = firstValue(channel?.source, ['frequencies_hz', 'frequency_hz'],
        firstValue(channel, ['frequencies_hz', 'frequency_hz'],
          firstValue(preview, ['frequencies_hz', 'frequency_hz'], [])));
      const values = Array.isArray(frequencies) ? frequencies : [frequencies];
      const facts = node('dl', 'radioreference-preview-facts');
      const factsValues = [['Action', operation ? operation.toLowerCase().replace(/_/g, ' ') : 'Create or refresh'],
        ['Channel', name], ['Protocol', protocol || 'Detected by RadioReference'],
        ['Frequencies', values.filter(Number).map((value) => `${formatFrequency(value)} MHz`).join(', ') ||
          'Selected RadioReference frequencies']];
      const modulation = textValue(preview, ['detected_modulation', 'detectedModulation']);
      if (modulation) factsValues.push(['Detected modulation', modulation]);
      factsValues.forEach(([label, value]) => {
        facts.append(node('dt', '', label), node('dd', '', value));
      });
      wrapper.append(facts);
      if (String(operation).toUpperCase().includes('UPDATE')) wrapper.append(node('div', 'ui-notice',
        'The RadioReference frequency set will replace the saved set. Recording, Alias List, band-plan overrides, ' +
        'decoder details, and other local settings remain unchanged.'));
    }
    const warnings = Array.isArray(preview?.warnings) ? preview.warnings : [];
    warnings.forEach((warning) => wrapper.append(node('div', 'ui-notice ui-notice-warning', String(warning))));
    return wrapper;
  };

  const completionLink = (response, kind, aliasListIdValue = null) => {
    const actions = node('div', 'ui-action-row radioreference-completion-actions');
    const configurationId = textValue(response, ['configuration_id', 'configurationId'],
      textValue(response?.channel, ['configuration_id', 'configurationId']));
    if (configurationId) actions.append(anchor('Open channel', href('channel-setup', { channel: configurationId }),
      'ui-button ui-button-secondary'));
    const listId = integerValue(response, ['alias_list_id', 'aliasListId']) || Number(aliasListIdValue);
    if (kind === 'talkgroups' && Number.isSafeInteger(listId) && listId > 0) {
      actions.append(anchor('Open Alias List', href('aliases', { list: listId, aliasTab: 'configure' }),
        'ui-button ui-button-secondary'));
    }
    return actions;
  };

  const openPreview = async ({ title, path, body, kind, returnFocusSelector, aliasListIdValue, onApplied }) => {
    const loading = feedback('Preparing the RadioReference import preview…', 'loading');
    const modal = openReadOnlyModal(title, loading, {
      id: `radioreference-${kind}-preview`, className: 'radioreference-import-modal', returnFocusSelector
    });
    if (!modal) return;
    modal.setBusy(true);
    try {
      const preview = await api(path, { method: 'POST', body, timeoutMs: mutationTimeoutMs });
      if (!modal.dialog.isConnected) return;
      const id = previewId(preview);
      if (!id) throw new Error('RadioReference did not return a usable import preview.');
      const message = node('div', 'admin-form-message');
      message.setAttribute('role', 'alert');
      const cancel = button('Cancel');
      const apply = button(kind === 'talkgroups' ? 'Apply Import' : 'Apply Channel',
        'ui-button ui-button-primary');
      cancel.addEventListener('click', modal.close);
      apply.addEventListener('click', async () => {
        if (apply.disabled) return;
        apply.disabled = true;
        cancel.disabled = true;
        modal.setBusy(true);
        message.textContent = 'Applying the reviewed RadioReference import…';
        try {
          const response = await api(RADIO_REFERENCE_IMPORT_PATHS.apply(id), {
            method: 'POST', body: {}, timeoutMs: mutationTimeoutMs
          });
          modal.setBusy(false);
          const directValue = firstValue(response, ['created', 'applied']);
          const directCount = Number(directValue);
          const changedCount = Number(firstValue(response, ['added'], 0)) +
            Number(firstValue(response, ['updated'], 0));
          const count = directValue !== null && directValue !== undefined && Number.isFinite(directCount) &&
            directCount >= 0 ? directCount : changedCount;
          const completed = node('div', 'radioreference-import-complete');
          completed.append(uiPill('Import complete', 'success'),
            node('h3', '', kind === 'talkgroups' ? 'Talkgroups imported' : 'Channel saved'),
            node('p', 'muted', kind === 'talkgroups' ?
              `${formatNumber(Number.isFinite(count) ? count : 0)} talkgroup change${count === 1 ? '' : 's'} applied.` :
              'The channel is ready in Channel Setup.'), completionLink(response, kind, aliasListIdValue));
          modal.dialog.classList.add('radioreference-import-modal-complete');
          modal.dialog.querySelector('.modal-header h2').textContent = 'Import complete';
          modal.content.replaceChildren(completed);
          onApplied?.(response);
        } catch (error) {
          modal.setBusy(false);
          const close = button('Close', 'ui-button ui-button-primary');
          close.addEventListener('click', modal.close);
          const invalid = node('div', 'radioreference-import-complete');
          invalid.append(uiPill('Preview no longer valid', 'danger'),
            node('h3', '', 'Create a fresh preview'),
            feedback(`${error.message} This preview cannot be reused. Close this window and choose Import again.`,
              'error'), modalFooter(close));
          modal.content.replaceChildren(invalid);
          close.focus();
        }
      });
      const actions = modalFooter(cancel, apply);
      modal.content.replaceChildren(renderPreviewDetails(preview, kind), message, actions);
      modal.setBusy(false);
      apply.focus();
    } catch (error) {
      modal.setBusy(false);
      modal.content.replaceChildren(feedback(error.message, 'error'));
    }
  };

  const siteFrequencyDescription = (value) => {
    const use = textValue(value, ['use', 'description', 'channel_use']);
    const tags = [];
    if (value?.primary_control === true || value?.primaryControl === true) tags.push('Control');
    if (value?.alternate_control === true || value?.alternateControl === true) tags.push('Alternate');
    return tags.join(' · ') || use || 'Frequency';
  };

  const openSiteImport = (system, site, detail) => {
    const family = compatibleFamily(detail) || compatibleFamily(system);
    const channels = siteChannels(site, detail).filter((value) => frequencyHz(value) > 0);
    const form = node('form', 'radioreference-site-form editor-workspace');
    const systemName = input();
    const siteName = input();
    const channelName = input();
    systemName.value = textValue(detail, ['system_name'], textValue(system, ['name'], ''));
    siteName.value = textValue(site, ['name', 'description'], `Site ${textValue(site, ['number', 'site_number'])}`);
    channelName.value = textValue(detail, ['channel_name'], `${systemName.value} · ${siteName.value}`);
    [systemName, siteName, channelName].forEach((control) => { control.required = true; control.maxLength = 256; });
    const aliases = aliasSelect(family, integerValue(detail, ['default_alias_list_id', 'defaultAliasListId']));
    const modulation = detectedSiteModulation(system, site, detail);
    const modeInputs = new Map();
    const modeGrid = node('div', 'radioreference-frequency-modes');
    [
      ['CONTROL', 'Control', 'Use only the primary control frequency.'],
      ['CONTROL_AND_ALTERNATES', 'Control + alternates', 'Recommended for normal trunked operation.'],
      ['SELECTED', 'Selected', 'Choose an exact set of site frequencies.'],
      ['ALL', 'All', 'Use every listed frequency for this site.']
    ].forEach(([value, label, description]) => {
      const choice = node('label', 'ui-choice-card radioreference-frequency-mode');
      const radio = node('input', 'ui-choice-radio');
      radio.type = 'radio';
      radio.name = 'frequency-mode';
      radio.value = value;
      radio.checked = value === 'CONTROL_AND_ALTERNATES';
      choice.append(radio, node('span', '', label), node('small', 'muted', description));
      modeInputs.set(value, radio);
      modeGrid.append(choice);
    });
    const selectedFrequencies = node('div', 'radioreference-frequency-choices');
    channels.forEach((value) => {
      const choice = node('label', 'radioreference-frequency-choice');
      const checkbox = node('input', 'ui-selection-check');
      checkbox.type = 'checkbox';
      checkbox.value = String(frequencyHz(value));
      choice.append(checkbox, node('span', '', `${formatFrequency(frequencyHz(value))} MHz`),
        node('small', 'muted', siteFrequencyDescription(value)));
      selectedFrequencies.append(choice);
    });
    const frequencyPanel = node('div', 'radioreference-selected-frequencies');
    frequencyPanel.hidden = true;
    frequencyPanel.append(node('strong', '', 'Choose frequencies'), selectedFrequencies);
    modeGrid.addEventListener('change', () => {
      frequencyPanel.hidden = !modeInputs.get('SELECTED').checked;
    });
    const detection = node('div', 'radioreference-detection');
    detection.append(node('span', 'muted', 'Detected P25 modulation'), uiPill(modulation,
      modulation.toUpperCase().includes('CQPSK') || modulation.toUpperCase().includes('LSM') ? 'blue' : 'neutral'));
    if (modulation !== 'Not applicable') detection.append(node('small', 'muted',
      'Detection uses RadioReference modulation hints and “simul” in the site name or description. Change it later ' +
      'in Channel Setup if needed.'));
    const message = node('div', 'admin-form-message');
    message.setAttribute('role', 'alert');
    const preview = button('Review Channel', 'ui-button ui-button-primary');
    preview.type = 'submit';
    const footer = modalFooter(button('Cancel'), preview);
    footer.firstElementChild.addEventListener('click', () => closeReadOnlyModal());
    const nameGrid = node('div', 'radioreference-form-grid');
    nameGrid.append(formField('System name', systemName), formField('Site name', siteName),
      formField('Channel name', channelName), formField('Alias List', selectFrame(aliases)));
    form.append(node('div', 'radioreference-modal-intro',
      `Create one combined channel for ${siteName.value || 'this site'}.`), nameGrid, detection,
      formField('Frequencies', modeGrid), frequencyPanel, message, footer);
    const modal = openReadOnlyModal(`Import ${siteName.value || 'site'}`, form, {
      id: 'radioreference-site-import', className: 'radioreference-import-modal',
      returnFocusSelector: '.radioreference-site-import'
    });
    if (!modal) return;
    form.addEventListener('input', () => modal.setDirty(true));
    form.addEventListener('change', () => modal.setDirty(true));
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const mode = [...modeInputs].find(([, control]) => control.checked)?.[0] || '';
      const selected = [...selectedFrequencies.querySelectorAll('input:checked')].map((control) => Number(control.value));
      if (!form.reportValidity() || !mode || mode === 'SELECTED' && !selected.length) {
        message.textContent = 'Choose an Alias List, a frequency mode, and at least one selected frequency.';
        return;
      }
      modal.setDirty(false);
      closeReadOnlyModal(true);
      await openPreview({
        title: `Review ${channelName.value}`,
        path: RADIO_REFERENCE_IMPORT_PATHS.sitePreview,
        kind: 'site',
        returnFocusSelector: '.radioreference-site-import',
        body: {
          system_id: systemId(system), site_id: integerValue(site, ['site_id', 'siteId', 'id']),
          alias_list_id: Number(aliases.value), frequency_mode: mode,
          selected_frequency_hz: selected, system_name: systemName.value.trim(), site_name: siteName.value.trim(),
          channel_name: channelName.value.trim()
        }
      });
    });
  };

  const renderSites = async (system, target, systemDocument) => {
    target.replaceChildren(feedback('Loading RadioReference sites…', 'loading'));
    try {
      const id = systemId(system);
      if (!state.siteCatalogs.has(id)) {
        const response = await api(query(RADIO_REFERENCE_IMPORT_PATHS.siteCatalog,
          { system_id: id }), { timeoutMs: 65_000 });
        state.siteCatalogs.set(id, rows(response).map((site) => ({
          site,
          searchText: [
            textValue(site, ['name', 'description']), textValue(site, ['number', 'site_number']),
            textValue(site, ['county_name', 'county']),
            ...siteChannels(site).flatMap((channel) => [
              textValue(channel, ['channel_id', 'channelId']),
              textValue(channel, ['name', 'alpha_tag', 'description', 'use']),
              formatFrequency(frequencyHz(channel))
            ])
          ].join(' ').toLowerCase()
        })));
      }
      if (state.activeSystemId !== id || target.dataset.rrTab !== 'sites') return;
      const catalog = state.siteCatalogs.get(id);
      const systemDetails = systemDocument?.system || systemDocument || system;
      const toolbar = node('div', 'radioreference-sites-toolbar ui-catalog-toolbar');
      const searchFrame = node('label', 'ui-search');
      searchFrame.append(iconGlyph('icon-search'));
      const search = input('search');
      search.placeholder = 'Filter site or channel name';
      search.setAttribute('aria-label', search.placeholder);
      search.value = state.siteSearch;
      searchFrame.append(search);
      const sort = select();
      [['number', 'Site number'], ['alphabetical', 'Alphabetical']].forEach(([value, label]) => {
        const option = node('option', '', label);
        option.value = value;
        sort.append(option);
      });
      sort.value = state.siteSort;
      const count = node('span', 'muted');
      toolbar.append(formField('Search sites & channels', searchFrame),
        formField('Sort sites', selectFrame(sort)), count);
      const list = node('div', 'radioreference-sites-list');
      target.replaceChildren(toolbar, list);
      let offset = 0;
      const draw = () => {
        const term = search.value.trim().toLowerCase();
        state.siteSearch = search.value;
        const matches = (term ? catalog.filter((value) => value.searchText.includes(term)) : catalog).slice();
        if (sort.value === 'alphabetical') matches.sort((a, b) =>
          textValue(a.site, ['name', 'description']).localeCompare(textValue(b.site, ['name', 'description']),
            undefined, { numeric: true }));
        const values = matches.slice(offset, offset + SITE_LIMIT).map((value) => value.site);
        count.textContent = `${formatNumber(matches.length)} of ${formatNumber(catalog.length)} sites`;
        const rendered = table(values, [
        { id: 'site', label: 'Site', width: 340, render: (site) => {
          const identity = node('span', 'radioreference-row-identity');
          const open = button(textValue(site, ['name', 'description'],
            `Site ${textValue(site, ['number', 'site_number'], '')}`), 'link-button radioreference-site-import');
          open.title = 'Review this site for import';
          open.addEventListener('click', () => openSiteImport(systemDetails, site, site));
          identity.append(open);
          const context = [textValue(site, ['county_name', 'county']),
            textValue(site, ['number', 'site_number']) ? `Site ${textValue(site, ['number', 'site_number'])}` : '']
            .filter(Boolean).join(' · ');
          if (context) identity.append(node('small', 'muted', context));
          return identity;
        } },
        { id: 'modulation', label: 'Detected modulation', width: 170,
          render: (site) => {
            const modulation = detectedSiteModulation(systemDetails, site);
            return uiPill(modulation, modulation === 'CQPSK' ? 'blue' : 'neutral');
          } },
        { id: 'frequencies', label: 'Frequencies', render: (site) => {
          const channels = siteChannels(site);
          return channels.length ? `${formatNumber(channels.length)} available` : 'Loaded during preview';
        } }
        ], 'No sites match this filter.',
        { type: 'radioreference-sites', sortable: false, mobileCards: true });
        rendered.querySelector('table')?.classList.add('ui-data-table-quiet');
        list.replaceChildren(rendered, internalPager({
          offset, limit: SITE_LIMIT, visible: values.length, total: matches.length,
          more: offset + SITE_LIMIT < matches.length, label: 'Sites',
          onPage: (next) => { offset = next; draw(); }
        }));
      };
      search.addEventListener('input', () => { offset = 0; draw(); });
      sort.addEventListener('change', () => { state.siteSort = sort.value; offset = 0; draw(); });
      draw();
    } catch (error) {
      target.replaceChildren(feedback(error.message, 'error'));
    }
  };

  const renderTalkgroups = async (system, target, systemDocument) => {
    const systemIdValue = systemId(system);
    const systemName = textValue(systemDocument?.system || systemDocument || system, ['name'], 'System');
    const toolbar = node('div', 'radioreference-talkgroup-toolbar ui-catalog-toolbar');
    const aliasList = aliasSelect(compatibleFamily(systemDocument?.system || systemDocument) ||
      compatibleFamily(system), state.talkgroupAliasListId);
    if (aliasList.value) state.talkgroupAliasListId = Number(aliasList.value);
    const category = select();
    const categoryStar = node('span', 'radioreference-category-star');
    const categoryField = node('div', 'radioreference-category-field');
    categoryField.append(formField('Category', selectFrame(category)), categoryStar);
    const search = input('search');
    search.placeholder = 'Filter talkgroup ID, name, or description';
    search.setAttribute('aria-label', search.placeholder);
    search.value = state.talkgroupSearch;
    const searchFrame = node('label', 'ui-search radioreference-talkgroup-search');
    searchFrame.append(iconGlyph('icon-search'), search);
    const selectionBadge = uiPill('0 selected', 'blue');
    selectionBadge.classList.add('radioreference-selection-badge');
    const clear = button('Clear selection');
    const importSelected = button('Review selected', 'ui-button ui-button-primary');
    const importAll = button('Import all system talkgroups');
    const status = node('div', 'admin-form-message');
    status.setAttribute('role', 'status');
    const tableHost = node('div', 'radioreference-talkgroup-table');
    let offset = 0;
    let catalog = [];
    const filter = uiSegmentedControl([
      { value: 'ALL', label: 'All' },
      { value: 'NOT_PRESENT', label: 'Not in list' },
      { value: 'DIFFERENT', label: 'Different' },
      { value: 'IDENTICAL', label: 'Identical' }
    ], state.talkgroupStatus, (value) => {
      state.talkgroupStatus = value;
      offset = 0;
      draw();
    });
    filter.classList.add('radioreference-status-filter');
    filter.setAttribute('aria-label', 'Filter by import status');
    const actions = node('div', 'radioreference-talkgroup-actions ui-selection-bar');
    actions.append(selectionBadge, clear, importSelected);
    toolbar.append(formField('Compare with Alias List', selectFrame(aliasList)), categoryField,
      formField('Search talkgroups', searchFrame), filter, importAll);
    target.replaceChildren(toolbar, actions, status, tableHost);

    const selected = state.selectedTalkgroups;
    const updateSelection = () => {
      const count = selected.size;
      selectionBadge.querySelector('span').textContent = formatNumber(count) + ' selected';
      actions.hidden = count === 0;
      clear.disabled = count === 0;
      importSelected.disabled = count === 0 || !aliasList.value || !state.talkgroupCatalogId;
      importAll.disabled = !aliasList.value || !state.talkgroupCatalogId;
      aliasList.disabled = count > 0;
      aliasList.title = count > 0 ? 'Clear the current selection before changing Alias Lists.' : '';
    };
    const categoryRows = rows(systemDocument?.talkgroup_categories || systemDocument?.categories ||
      systemDocument?.talkgroupCategories);
    setOptions(category, categoryRows, state.talkgroupCategoryId, 'All categories', true);
    const updateCategoryStar = () => {
      categoryStar.replaceChildren();
      if (!category.value) return;
      const bookmark = {
        kind: 'TALKGROUP_CATEGORY', id: Number(category.value), parentId: systemIdValue,
        ownerKind: 'TRUNKED_SYSTEM', name: category.selectedOptions[0]?.textContent || 'Category',
        parentName: [textValue(system, ['breadcrumb']), systemName].filter(Boolean).join(' > ')
      };
      categoryStar.append(starButton(bookmark, () => {
        updateCategoryStar();
        state.onBookmarksChanged?.();
      }));
    };
    updateCategoryStar();
    updateSelection();

    const draw = () => {
      if (!aliasList.value || !catalog.length) {
        tableHost.replaceChildren(empty(catalog.length ? 'Choose an Alias List' : 'No talkgroups',
          catalog.length ? 'Choose a compatible destination Alias List.' :
            'RadioReference returned no talkgroups for this system.'));
        return;
      }
      const term = search.value.trim().toLowerCase();
      const categoryId = Number(category.value) || null;
      const filtered = catalog.filter((item) =>
        (!categoryId || item.categoryId === categoryId) &&
        (state.talkgroupStatus === 'ALL' || item.status === state.talkgroupStatus) &&
        (!term || item.searchText.includes(term)));
      if (offset >= filtered.length) offset = Math.max(0,
        Math.floor(Math.max(0, filtered.length - 1) / TALKGROUP_LIMIT) * TALKGROUP_LIMIT);
      state.talkgroupOffset = offset;
      const values = filtered.slice(offset, offset + TALKGROUP_LIMIT).map((item) => item.row);
      const pageToggle = node('input', 'ui-selection-check');
      pageToggle.type = 'checkbox';
      pageToggle.setAttribute('aria-label', 'Select every compatible talkgroup on this page');
      const pageIds = values.filter((value) => importStatus(value).tone !== 'danger')
        .map(talkgroupId).filter(Boolean);
      const refreshPageToggle = () => {
        const checked = pageIds.filter((id) => selected.has(id)).length;
        pageToggle.checked = pageIds.length > 0 && checked === pageIds.length;
        pageToggle.indeterminate = checked > 0 && checked < pageIds.length;
      };
      pageToggle.addEventListener('change', () => {
        pageIds.forEach((id) => pageToggle.checked ? selected.add(id) : selected.delete(id));
        tableHost.querySelectorAll('tbody input[type="checkbox"]').forEach((control) => {
          control.checked = selected.has(Number(control.dataset.talkgroupId));
        });
        refreshPageToggle();
        updateSelection();
      });
      refreshPageToggle();
      const grid = table(values, [
        { id: 'selected', label: '', width: 54, sortable: false, renderHeader: () => pageToggle,
          render: (talkgroup) => {
            const id = talkgroupId(talkgroup);
            const control = node('input', 'ui-selection-check');
            control.type = 'checkbox';
            control.checked = selected.has(id);
            control.disabled = !id || importStatus(talkgroup).tone === 'danger';
            control.dataset.talkgroupId = String(id || '');
            control.setAttribute('aria-label', 'Select ' + textValue(talkgroupValue(talkgroup),
              ['alpha_tag', 'alphaTag', 'name'], 'talkgroup ' + (id || '')));
            control.addEventListener('change', () => {
              if (control.checked) selected.add(id); else selected.delete(id);
              updateSelection();
              refreshPageToggle();
            });
            return control;
          } },
        { id: 'talkgroup', label: 'Talkgroup', width: 130,
          render: (talkgroup) => formatNumber(firstValue(talkgroupValue(talkgroup),
            ['value', 'decimal', 'talkgroup_value'], talkgroupId(talkgroup) || 0)) },
        { id: 'alpha-tag', label: 'Alpha tag', width: 220,
          render: (talkgroup) => textValue(talkgroupValue(talkgroup),
            ['alpha_tag', 'alphaTag', 'name'], 'Unnamed') },
        { id: 'description', label: 'Description',
          render: (talkgroup) => textValue(talkgroupValue(talkgroup), ['description'], '—') },
        { id: 'category', label: 'Category', width: 180, render: talkgroupCategory },
        { id: 'status', label: 'Import status', width: 150, render: (talkgroup) => {
          const value = importStatus(talkgroup);
          const content = node('span', 'radioreference-talkgroup-status');
          content.append(uiPill(value.label, value.tone));
          return content;
        } }
      ], 'No talkgroups match these filters.',
      { type: 'radioreference-talkgroups', sortable: false, mobileCards: true });
      grid.querySelector('table')?.classList.add('ui-data-table-quiet');
      tableHost.replaceChildren(grid, internalPager({
        offset, limit: TALKGROUP_LIMIT, visible: values.length, total: filtered.length,
        more: offset + TALKGROUP_LIMIT < filtered.length, label: 'Talkgroups',
        onPage: (next) => { offset = next; draw(); }
      }));
      status.textContent = formatNumber(filtered.length) + ' matching of ' +
        formatNumber(catalog.length) + ' loaded talkgroups. Selections persist across filters and pages.';
      updateSelection();
    };

    const load = async (force = false) => {
      if (!aliasList.value) {
        tableHost.replaceChildren(empty('Choose an Alias List',
          'Talkgroup status is calculated against one compatible destination Alias List.'));
        status.textContent = '';
        updateSelection();
        return;
      }
      const key = systemIdValue + ':' + aliasList.value;
      tableHost.replaceChildren(feedback('Loading all talkgroups for instant filtering…', 'loading'));
      status.textContent = '';
      try {
        if (force || !state.talkgroupCatalogs.has(key)) {
          const response = await api(query(RADIO_REFERENCE_IMPORT_PATHS.talkgroupCatalog, {
            system_id: systemIdValue, alias_list_id: aliasList.value,
            catalog_id: state.talkgroupCatalogId || undefined
          }), { timeoutMs: 65_000 });
          const loadedCatalogId = textValue(response, ['catalog_id', 'catalogId']) || null;
          state.talkgroupCatalogs.set(key, {
            catalogId: loadedCatalogId,
            categories: Array.isArray(response?.categories) ? response.categories : [],
            items: rows(response).map((row) => {
              const talkgroup = talkgroupValue(row);
              return {
                row,
                categoryId: Number(firstValue(talkgroup, ['category_id', 'categoryId'])) || null,
                status: textValue(row, ['status', 'import_status', 'importStatus']).toUpperCase(),
                searchText: [firstValue(talkgroup, ['value', 'decimal', 'talkgroup_value']),
                  textValue(talkgroup, ['alpha_tag', 'alphaTag', 'name']),
                  textValue(talkgroup, ['description']), talkgroupCategory(row)].join(' ').toLowerCase()
              };
            })
          });
        }
        if (state.activeSystemId !== systemIdValue || target.dataset.rrTab !== 'talkgroups') return;
        const saved = state.talkgroupCatalogs.get(key);
        state.talkgroupCatalogId = saved.catalogId;
        catalog = saved.items;
        if (!categoryRows.length && saved.categories.length && category.options.length <= 1) {
          setOptions(category, saved.categories, state.talkgroupCategoryId, 'All categories', true);
        }
        updateCategoryStar();
        offset = 0;
        draw();
      } catch (error) {
        if (state.talkgroupCatalogId && /catalog is no longer available/i.test(error.message)) {
          state.talkgroupCatalogId = null;
          state.talkgroupCatalogs.delete(key);
          await load(true);
          return;
        }
        tableHost.replaceChildren(feedback(error.message, 'error'));
      }
    };

    const previewTalkgroups = (all) => {
      if (!aliasList.value) {
        status.textContent = 'Choose a compatible Alias List first.';
        aliasList.focus();
        return;
      }
      const selectedIds = [...selected];
      if (!all && !selectedIds.length) return;
      openPreview({
        title: all ? 'Import all system talkgroups' : 'Import ' + formatNumber(selectedIds.length) + ' talkgroups',
        path: RADIO_REFERENCE_IMPORT_PATHS.talkgroupsPreview,
        kind: 'talkgroups',
        returnFocusSelector: all ? '.radioreference-import-all' : '.radioreference-import-selected',
        aliasListIdValue: Number(aliasList.value),
        body: { system_id: systemIdValue, alias_list_id: Number(aliasList.value),
          import_all: all, talkgroup_ids: all ? [] : selectedIds,
          catalog_id: state.talkgroupCatalogId },
        onApplied: () => {
          selected.clear();
          state.talkgroupCatalogs.delete(systemIdValue + ':' + aliasList.value);
          updateSelection();
          load(true);
        }
      });
    };
    clear.addEventListener('click', () => { selected.clear(); draw(); });
    importSelected.classList.add('radioreference-import-selected');
    importAll.classList.add('radioreference-import-all');
    importSelected.addEventListener('click', () => previewTalkgroups(false));
    importAll.addEventListener('click', () => previewTalkgroups(true));
    aliasList.addEventListener('change', () => {
      state.talkgroupAliasListId = Number(aliasList.value) || null;
      const preferred = state.bookmarks.find((entry) => entry.kind === 'TALKGROUP_CATEGORY' &&
        entry.parentId === systemIdValue && entry.id === Number(category.value)) ||
        state.bookmarks.find((entry) => entry.kind === 'TRUNKED_SYSTEM' && entry.id === systemIdValue);
      if (preferred && state.talkgroupAliasListId) {
        void api(RADIO_REFERENCE_IMPORT_PATHS.bookmarks, {
          method: 'PUT', body: { ...preferred, preferredAliasListId: state.talkgroupAliasListId }
        }).then((response) => {
          state.bookmarks = rows(response).map(normalizeBookmark);
          state.onBookmarksChanged?.();
        })
          .catch((error) => { status.textContent = `Alias List preference could not be saved: ${error.message}`; });
      }
      load();
    });
    category.addEventListener('change', () => {
      state.talkgroupCategoryId = Number(category.value) || null;
      updateCategoryStar();
      offset = 0;
      draw();
    });
    search.addEventListener('input', () => {
      state.talkgroupSearch = search.value;
      offset = 0;
      draw();
    });
    await load();
  };

  const renderSystem = async (system, detailHost, options = {}) => {
    const id = systemId(system);
    const sequence = ++state.detailSequence;
    state.activeSystemId = id;
    state.talkgroupCatalogId = null;
    state.selectedTalkgroups.clear();
    state.talkgroupAliasListId = null;
    state.talkgroupAliasListId = options.bookmark?.preferredAliasListId || null;
    state.talkgroupCategoryId = Number(options.categoryId) || null;
    state.talkgroupStatus = 'ALL';
    state.talkgroupSearch = '';
    state.siteSearch = '';
    detailHost.closest('.radioreference-browser')?.classList.add('radioreference-detail-open');
    detailHost.replaceChildren(feedback('Loading system details…', 'loading'));
    try {
      const documentValue = await api(query(RADIO_REFERENCE_IMPORT_PATHS.systemDetails, { system_id: id }));
      if (sequence !== state.detailSequence) return;
      const details = documentValue?.system || documentValue;
      const panel = node('section', 'radioreference-system-workspace ui-surface');
      const header = node('header', 'radioreference-detail-header');
      const title = node('div');
      title.append(node('span', 'muted', [textValue(system, ['breadcrumb']), 'Trunked system']
        .filter(Boolean).join(' · ')),
        node('h2', '', textValue(details, ['name'], textValue(system, ['name'], `System ${id}`))),
        node('p', 'muted', textValue(details, ['protocol', 'type_name', 'type'])));
      const close = button('Back to results');
      close.classList.add('radioreference-back');
      close.addEventListener('click', () => {
        ++state.detailSequence;
        detailHost.replaceChildren(empty('Choose a result', 'Select a system or agency to review.'));
        detailHost.closest('.radioreference-browser')?.classList.remove('radioreference-detail-open');
      });
      header.append(title, starButton(bookmarkForEntry(system), () => state.onBookmarksChanged?.()), close);
      const initialTab = options.categoryId ? 'talkgroups' : 'sites';
      const tabs = uiSegmentedControl([
        { value: 'sites', label: 'Sites & Channels' },
        { value: 'talkgroups', label: 'Talkgroups & Aliases' }
      ], initialTab, (value) => {
        body.dataset.rrTab = value;
        if (value === 'sites') void renderSites(system, body, documentValue);
        else void renderTalkgroups(system, body, documentValue);
      });
      tabs.classList.add('radioreference-system-tabs');
      tabs.setAttribute('aria-label', 'System import sections');
      const body = node('div', 'radioreference-system-body');
      body.dataset.rrTab = initialTab;
      panel.append(header, tabs, body);
      detailHost.replaceChildren(panel);
      if (initialTab === 'sites') await renderSites(system, body, documentValue);
      else await renderTalkgroups(system, body, documentValue);
    } catch (error) {
      if (sequence === state.detailSequence) detailHost.replaceChildren(feedback(error.message, 'error'));
    }
  };

  const openConventionalImport = (entry, frequency, categoryId) => {
    const form = node('form', 'radioreference-conventional-form editor-workspace');
    const systemName = input();
    const siteName = input();
    const channelName = input();
    systemName.value = textValue(entry, ['name'], '');
    siteName.value = textValue(entry, ['secondary', 'location'], '');
    channelName.value = textValue(frequency, ['alpha_tag', 'alphaTag', 'description'],
      `${formatFrequency(frequencyHz(frequency))} MHz`);
    [systemName, siteName, channelName].forEach((control) => { control.required = true; control.maxLength = 256; });
    const facts = node('dl', 'radioreference-preview-facts');
    [['Frequency', `${formatFrequency(frequencyHz(frequency))} MHz`],
      ['Mode', textValue(frequency, ['mode_name', 'mode', 'protocol'], 'Detected by RadioReference')],
      ['Description', textValue(frequency, ['description'], '—')]].forEach(([label, value]) => {
      facts.append(node('dt', '', label), node('dd', '', value));
    });
    const message = node('div', 'admin-form-message');
    message.setAttribute('role', 'alert');
    const cancel = button('Cancel');
    const review = button('Review Channel', 'ui-button ui-button-primary');
    review.type = 'submit';
    const footer = modalFooter(cancel, review);
    const nameGrid = node('div', 'radioreference-form-grid');
    nameGrid.append(formField('System name', systemName), formField('Site name', siteName),
      formField('Channel name', channelName));
    form.append(facts, nameGrid,
      node('div', 'ui-notice', 'The receiver will use the compatible default Alias List for this protocol.'),
      message, footer);
    const modal = openReadOnlyModal(`Import ${channelName.value}`, form, {
      id: 'radioreference-conventional-import', className: 'radioreference-import-modal',
      returnFocusSelector: '.radioreference-conventional-import'
    });
    if (!modal) return;
    cancel.addEventListener('click', modal.close);
    form.addEventListener('input', () => modal.setDirty(true));
    form.addEventListener('change', () => modal.setDirty(true));
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!form.reportValidity()) return;
      modal.setDirty(false);
      closeReadOnlyModal(true);
      await openPreview({
        title: `Review ${channelName.value}`,
        path: RADIO_REFERENCE_IMPORT_PATHS.conventionalPreview,
        kind: 'conventional',
        returnFocusSelector: '.radioreference-conventional-import',
        body: { owner_kind: textValue(entry?.detail || entry, ['owner_kind', 'kind'], 'AGENCY'),
          owner_id: ownerId(entry), sub_category_id: Number(categoryId), frequency_id: frequencyId(frequency),
          system_name: systemName.value.trim(), site_name: siteName.value.trim(), channel_name: channelName.value.trim() }
      });
    });
  };

  const renderConventional = async (entry, detailHost, preferredCategoryId = null) => {
    const id = ownerId(entry);
    const kind = textValue(entry?.detail || entry, ['owner_kind', 'kind'], 'AGENCY');
    const sequence = ++state.detailSequence;
    state.activeSystemId = null;
    detailHost.closest('.radioreference-browser')?.classList.add('radioreference-detail-open');
    detailHost.replaceChildren(feedback('Loading conventional categories…', 'loading'));
    try {
      const categoriesDocument = await api(query(RADIO_REFERENCE_IMPORT_PATHS.conventionalCategories,
        { owner_kind: kind, owner_id: id, offset: 0, limit: 500 }));
      if (sequence !== state.detailSequence) return;
      const categories = rows(categoriesDocument);
      const panel = node('section', 'radioreference-system-workspace ui-surface');
      const header = node('header', 'radioreference-detail-header');
      const title = node('div');
      title.append(node('span', 'muted', [textValue(entry, ['breadcrumb']), 'Conventional agency']
        .filter(Boolean).join(' · ')),
        node('h2', '', textValue(entry, ['name'], `Agency ${id}`)),
        node('p', 'muted', textValue(entry, ['secondary', 'location'])));
      const close = button('Back to results');
      close.classList.add('radioreference-back');
      close.addEventListener('click', () => {
        ++state.detailSequence;
        detailHost.replaceChildren(empty('Choose a result', 'Select a system or agency to review.'));
        detailHost.closest('.radioreference-browser')?.classList.remove('radioreference-detail-open');
      });
      header.append(title, starButton(bookmarkForEntry(entry), () => state.onBookmarksChanged?.()), close);
      const category = select();
      categories.forEach((value) => {
        const categoryId = integerValue(value, ['sub_category_id', 'subCategoryId', 'category_id', 'id']);
        if (!categoryId) return;
        const name = [textValue(value, ['category_name', 'category']),
          textValue(value, ['sub_category_name', 'name'])].filter(Boolean).join(' · ');
        const option = node('option', '', name || `Category ${categoryId}`);
        option.value = String(categoryId);
        category.append(option);
      });
      if (preferredCategoryId && [...category.options].some((value) =>
        value.value === String(preferredCategoryId))) category.value = String(preferredCategoryId);
      const toolbar = node('div', 'radioreference-conventional-toolbar ui-catalog-toolbar');
      const status = node('div', 'admin-form-message');
      status.setAttribute('role', 'status');
      const categoryStar = node('span', 'radioreference-category-star');
      const updateCategoryStar = () => {
        categoryStar.replaceChildren();
        if (!category.value) return;
        const bookmark = {
          kind: 'CONVENTIONAL_CATEGORY', id: Number(category.value), parentId: id,
          ownerKind: kind, name: category.selectedOptions[0]?.textContent || 'Category',
          parentName: [textValue(entry, ['breadcrumb']), textValue(entry, ['name'], 'Agency')]
            .filter(Boolean).join(' > ')
        };
        categoryStar.append(starButton(bookmark, () => {
          updateCategoryStar();
          state.onBookmarksChanged?.();
        }));
      };
      toolbar.append(formField('Category', selectFrame(category)), categoryStar, status);
      updateCategoryStar();
      const body = node('div', 'radioreference-system-body');
      panel.append(header, toolbar, body);
      detailHost.replaceChildren(panel);

      const load = async (offset = 0) => {
        if (!category.value) {
          body.replaceChildren(empty('No conventional categories',
            'RadioReference did not return an importable category for this agency.'));
          return;
        }
        body.replaceChildren(feedback('Loading conventional frequencies…', 'loading'));
        try {
          const response = await api(query(RADIO_REFERENCE_IMPORT_PATHS.conventionalFrequencies,
            { sub_category_id: category.value, offset, limit: FREQUENCY_LIMIT }));
          if (sequence !== state.detailSequence) return;
          const values = rows(response);
          const grid = table(values, [
            { id: 'frequency', label: 'Frequency', width: 150,
              render: (value) => `${formatFrequency(frequencyHz(value))} MHz` },
            { id: 'alpha-tag', label: 'Alpha tag', width: 220, render: (value) => {
              const open = button(textValue(value, ['alpha_tag', 'alphaTag', 'name'], 'Unnamed'),
                'link-button radioreference-conventional-import');
              open.title = 'Review this channel for import';
              open.addEventListener('click', () => openConventionalImport(entry, value, category.value));
              return open;
            } },
            { id: 'description', label: 'Description',
              render: (value) => textValue(value, ['description'], '—') },
            { id: 'mode', label: 'Mode', width: 120,
              render: (value) => uiPill(textValue(value, ['mode_name', 'mode', 'protocol'], 'Unknown'), 'protocol') }
          ], 'No frequencies were returned for this category.',
          { type: 'radioreference-conventional', sortable: false, mobileCards: true });
          grid.querySelector('table')?.classList.add('ui-data-table-quiet');
          const total = totalValue(response, values.length);
          body.replaceChildren(grid, internalPager({ offset, limit: FREQUENCY_LIMIT, visible: values.length, total,
            more: hasMore(response, offset, FREQUENCY_LIMIT, values.length), label: 'Frequencies', onPage: load }));
          status.textContent = `${formatNumber(total)} frequency${total === 1 ? '' : 'ies'} available. ` +
            'Import one channel at a time.';
        } catch (error) {
          body.replaceChildren(feedback(error.message, 'error'));
        }
      };
      category.addEventListener('change', () => { updateCategoryStar(); load(0); });
      await load(0);
    } catch (error) {
      if (sequence === state.detailSequence) detailHost.replaceChildren(feedback(error.message, 'error'));
    }
  };

  const renderDirectory = (listHost, detailHost, values, location) => {
    if (!values.length) {
      listHost.replaceChildren(empty('No systems or agencies here',
        'Choose a broader country, state, or county to browse.'));
      return;
    }
    const itemFor = (entry, trail) => {
      entry.breadcrumb = trail;
      const item = node('div', 'radioreference-directory-item');
      const open = button('', 'link-button ui-row-action radioreference-result-open');
      const identity = node('span', 'radioreference-row-identity');
      identity.append(node('strong', '', textValue(entry, ['name'], 'Unnamed')));
      const secondary = textValue(entry, ['secondary', 'location', 'description']);
      if (secondary) identity.append(node('small', 'muted', secondary));
      open.append(identity, uiPill(entryKind(entry) === 'TRUNKED_SYSTEM' ? 'Trunked' : 'Conventional',
        entryKind(entry) === 'TRUNKED_SYSTEM' ? 'blue' : 'neutral'));
      open.addEventListener('click', () => entryKind(entry) === 'TRUNKED_SYSTEM' ?
        renderSystem(entry, detailHost) : renderConventional(entry, detailHost));
      item.append(open, starButton(bookmarkForEntry(entry), () => state.onBookmarksChanged?.()));
      return item;
    };
    const branch = (label, entries, trail, initiallyOpen = false) => {
      const group = node('details', 'radioreference-directory-branch');
      const count = entries.length;
      group.dataset.count = String(count);
      const summary = node('summary', '', '');
      summary.append(node('span', 'radioreference-directory-branch-label', label),
        uiPill(formatNumber(count), 'neutral'));
      group.append(summary);
      // Populate only opened branches; large counties need not create thousands of hidden buttons.
      const populate = () => {
        if (!group.open || group.dataset.populated) return;
        group.dataset.populated = 'true';
        if (!entries.length) return;
        const content = node('div', 'radioreference-directory-branch-content');
        entries.forEach((entry) => content.append(itemFor(entry, trail)));
        group.append(content);
      };
      group.addEventListener('toggle', populate);
      if (initiallyOpen) {
        group.open = true;
        populate();
      }
      return group;
    };
    const national = values.filter((entry) => textValue(entry, ['scope']).toUpperCase() === 'NATIONAL');
    const statewide = values.filter((entry) => textValue(entry, ['scope']).toUpperCase() === 'STATE');
    const countywide = values.filter((entry) => textValue(entry, ['scope']).toUpperCase() === 'COUNTY');
    const tree = node('div', 'radioreference-directory-tree');
    const countyTrail = [location.region, location.county].filter(Boolean).join(' > ') || 'County';
    if (countywide.length) tree.append(branch(`${location.county || 'County'} results`, countywide,
      countyTrail, true));
    if (statewide.length) tree.append(branch(`${location.region || 'State'} statewide`, statewide,
      location.region || 'Statewide', !countywide.length));
    if (national.length) tree.append(branch('National', national, 'National',
      !countywide.length && !statewide.length));
    const scope = node('div', 'radioreference-directory-scope');
    scope.append(node('strong', '', location.county || location.region || location.country || 'Results'),
      node('small', 'muted', 'Results are grouped by coverage area. Select one to review it.'));
    listHost.replaceChildren(scope, tree);
  };

  const buildBrowser = async () => {
    const browser = node('div', 'radioreference-browser');
    const browseForm = node('div', 'radioreference-browse-form ui-surface');
    const country = select();
    const region = select();
    const county = select();
    const message = node('div', 'admin-form-message');
    message.setAttribute('role', 'status');
    const fields = node('div', 'radioreference-browse-fields');
    fields.append(formField('Country', selectFrame(country)),
      formField('State or region', selectFrame(region)), formField('County', selectFrame(county)));
    browseForm.append(node('strong', 'radioreference-browse-title', 'Browse area'), fields, message);
    const workbench = node('div', 'radioreference-workbench-grid');
    const resultHost = node('div', 'radioreference-directory-results ui-surface');
    const tabHost = node('div', 'radioreference-directory-tabs');
    const listHost = node('div', 'radioreference-directory-list');
    resultHost.append(tabHost, listHost);
    const detailHost = node('div', 'radioreference-import-detail');
    detailHost.append(empty('Choose a result', 'Select a system or agency to review.'));
    workbench.append(resultHost, detailHost);
    browser.append(browseForm, workbench);
    host.replaceChildren(browser);

    const showBookmarks = () => {
      if (!state.bookmarks.length) {
        listHost.replaceChildren(empty('No bookmarks yet',
          'Use a star beside a system, agency, or category to save it here.'));
        return;
      }
      const fragmentValue = document.createDocumentFragment();
      let previousKind = '';
      const labels = {
        TRUNKED_SYSTEM: 'Trunked systems', CONVENTIONAL_AGENCY: 'Conventional agencies',
        TALKGROUP_CATEGORY: 'Talkgroup categories', CONVENTIONAL_CATEGORY: 'Conventional categories'
      };
      state.bookmarks.forEach((bookmark) => {
        if (bookmark.kind !== previousKind) {
          fragmentValue.append(node('div', 'radioreference-directory-group',
            labels[bookmark.kind] || 'Bookmarks'));
          previousKind = bookmark.kind;
        }
        const item = node('div', 'radioreference-directory-item');
        const open = button('', 'link-button ui-row-action radioreference-result-open');
        const identity = node('span', 'radioreference-row-identity');
        const parentEntry = state.browseRows.find((entry) =>
          (bookmark.kind === 'TRUNKED_SYSTEM' || bookmark.kind === 'CONVENTIONAL_AGENCY' ?
            (bookmark.kind === 'TRUNKED_SYSTEM' ? systemId(entry) : ownerId(entry)) === bookmark.id :
            (bookmark.kind === 'TALKGROUP_CATEGORY' ? systemId(entry) : ownerId(entry)) === bookmark.parentId) &&
          entryKind(entry) === (bookmark.kind === 'TRUNKED_SYSTEM' || bookmark.kind === 'TALKGROUP_CATEGORY' ?
            'TRUNKED_SYSTEM' : 'CONVENTIONAL_AGENCY'));
        const savedPath = parentEntry ? [parentEntry.breadcrumb,
          bookmark.kind.includes('CATEGORY') ? textValue(parentEntry, ['name']) : ''].filter(Boolean).join(' > ') :
          bookmark.parentName;
        const trail = [savedPath, bookmark.name].filter(Boolean).join(' > ');
        identity.append(node('strong', '', trail));
        const preferredList = state.aliasLists.find((entry) =>
          aliasListId(entry) === bookmark.preferredAliasListId);
        if (preferredList) identity.append(node('small', 'muted',
          `Import talkgroups to ${textValue(preferredList, ['name'], 'Alias List')}`));
        open.append(identity);
        open.addEventListener('click', () => {
          if (bookmark.kind === 'TRUNKED_SYSTEM' || bookmark.kind === 'TALKGROUP_CATEGORY') {
            const system = {
              id: bookmark.kind === 'TRUNKED_SYSTEM' ? bookmark.id : bookmark.parentId,
              name: bookmark.kind === 'TRUNKED_SYSTEM' ? bookmark.name :
                bookmark.parentName.split(' > ').at(-1),
              type: 'TRUNKED_SYSTEM', breadcrumb: savedPath
            };
            void renderSystem(system, detailHost, { bookmark,
              categoryId: bookmark.kind === 'TALKGROUP_CATEGORY' ? bookmark.id : null });
          } else {
            const entry = {
              id: bookmark.kind === 'CONVENTIONAL_AGENCY' ? bookmark.id : bookmark.parentId,
              name: bookmark.kind === 'CONVENTIONAL_AGENCY' ? bookmark.name :
                bookmark.parentName.split(' > ').at(-1),
              type: 'CONVENTIONAL_AGENCY',
              breadcrumb: savedPath,
              detail: { kind: bookmark.ownerKind,
                id: bookmark.kind === 'CONVENTIONAL_AGENCY' ? bookmark.id : bookmark.parentId }
            };
            void renderConventional(entry, detailHost,
              bookmark.kind === 'CONVENTIONAL_CATEGORY' ? bookmark.id : null);
          }
        });
        item.append(open, starButton(bookmark, () => state.onBookmarksChanged?.()));
        fragmentValue.append(item);
      });
      listHost.replaceChildren(fragmentValue);
    };
    const showCurrent = () => {
      if (state.browseTab === 'bookmarks') showBookmarks();
      else if (state.browseDocument) renderDirectory(listHost, detailHost,
        state.browseRows, state.browseDocument.location);
    };
    const renderTabs = () => {
      const tabs = uiSegmentedControl([
        { value: 'browse', label: 'Browse' },
        { value: 'bookmarks', label: '★ Bookmarks (' + formatNumber(state.bookmarks.length) + ')' }
      ], state.browseTab, (value) => {
        state.browseTab = value;
        showCurrent();
      });
      tabs.setAttribute('aria-label', 'Browse or open RadioReference bookmarks');
      tabHost.replaceChildren(tabs);
    };
    state.onBookmarksChanged = () => { renderTabs(); showCurrent(); };
    renderTabs();

    let locationSequence = 0;
    const loadCounties = async (selected = null, sequence = locationSequence) => {
      county.disabled = true;
      setOptions(county, [], null, 'Loading counties…', true);
      if (!region.value) {
        setOptions(county, [], null, 'All counties', true);
        county.disabled = false;
        return;
      }
      const response = await api(query(RADIO_REFERENCE_IMPORT_PATHS.counties,
        { state_id: region.value, offset: 0, limit: 500 }));
      if (sequence !== locationSequence) return;
      setOptions(county, rows(response), selected, 'All counties', true);
      county.disabled = false;
    };
    const loadStates = async (selectedRegion = null, selectedCounty = null, sequence = locationSequence) => {
      region.disabled = true;
      county.disabled = true;
      setOptions(region, [], null, 'Loading regions…', true);
      const response = await api(query(RADIO_REFERENCE_IMPORT_PATHS.states, { country_id: country.value }));
      if (sequence !== locationSequence) return;
      setOptions(region, rows(response), selectedRegion, 'All states or regions', true);
      region.disabled = false;
      await loadCounties(selectedCounty, sequence);
    };

    const browse = async () => {
      if (!country.value) return;
      const sequence = ++state.browseSequence;
      message.textContent = 'Loading systems and agencies…';
      if (state.browseTab === 'browse') listHost.replaceChildren(feedback('Loading directory results…', 'loading'));
      try {
        const response = await api(query(RADIO_REFERENCE_IMPORT_PATHS.browseCatalog, {
          country_id: country.value, state_id: region.value || null, county_id: county.value || null
        }), { timeoutMs: 65_000 });
        if (sequence !== state.browseSequence) return;
        state.browseRows = rows(response);
        const location = {
          country: country.selectedOptions[0]?.textContent || '',
          region: region.value ? region.selectedOptions[0]?.textContent || '' : '',
          county: county.value ? county.selectedOptions[0]?.textContent || '' : ''
        };
        state.browseRows.forEach((entry) => {
          const scope = textValue(entry, ['scope']).toUpperCase();
          entry.breadcrumb = scope === 'NATIONAL' ? 'National' : scope === 'STATE' ?
            location.region || 'Statewide' : [location.region, location.county].filter(Boolean).join(' > ');
        });
        state.browseDocument = { location };
        ++state.detailSequence;
        state.activeSystemId = null;
        state.selectedTalkgroups.clear();
        detailHost.replaceChildren(empty('Choose a result', 'Select a system or agency to review.'));
        browser.classList.remove('radioreference-detail-open');
        showCurrent();
        message.textContent = formatNumber(state.browseRows.length) +
          ' systems and agencies grouped by coverage area.';
      } catch (error) {
        if (sequence !== state.browseSequence) return;
        if (state.browseTab === 'browse') listHost.replaceChildren(feedback(error.message, 'error'));
        message.textContent = 'RadioReference directory could not be loaded.';
      }
    };
    setOptions(country, state.countries, state.configuration?.country_id, 'Choose a country');
    try {
      await loadStates(state.configuration?.state_id, state.configuration?.county_id);
      await browse();
    } catch (error) {
      listHost.replaceChildren(feedback(error.message, 'error'));
    }
    country.addEventListener('change', async () => {
      const sequence = ++locationSequence;
      message.textContent = 'Loading browse area…';
      try {
        await loadStates(null, null, sequence);
        if (sequence === locationSequence) await browse();
      } catch (error) {
        if (sequence === locationSequence) listHost.replaceChildren(feedback(error.message, 'error'));
      }
    });
    region.addEventListener('change', async () => {
      const sequence = ++locationSequence;
      message.textContent = 'Loading counties…';
      try {
        await loadCounties(null, sequence);
        if (sequence === locationSequence) await browse();
      } catch (error) {
        if (sequence === locationSequence) listHost.replaceChildren(feedback(error.message, 'error'));
      }
    });
    county.addEventListener('change', () => void browse());
  };

  const initialize = async () => {
    if (state.initializing || state.initialized) return;
    if (state.configuration?.account?.state !== 'VALID_PREMIUM') {
      host.replaceChildren(empty('Connect RadioReference to import',
        'A current Premium account is required for directory browsing and imports.'));
      return;
    }
    state.initializing = true;
    host.replaceChildren(feedback('Loading the RadioReference directory…', 'loading'));
    try {
      const [aliasesDocument, countriesDocument, bookmarksDocument] = await Promise.all([
        requestJson('/api/v1/admin/alias-lists?include_counts=false', { csrf: false }),
        api(RADIO_REFERENCE_IMPORT_PATHS.countries),
        api(RADIO_REFERENCE_IMPORT_PATHS.bookmarks)
      ]);
      state.aliasLists = Array.isArray(aliasesDocument?.alias_lists) ? aliasesDocument.alias_lists :
        rows(aliasesDocument);
      state.countries = rows(countriesDocument);
      state.bookmarks = rows(bookmarksDocument).map(normalizeBookmark);
      state.initialized = true;
      await buildBrowser();
    } catch (error) {
      host.replaceChildren(feedback(error.message, 'error'));
    } finally {
      state.initializing = false;
    }
  };

  return {
    element: host,
    setConfiguration(configuration) {
      const wasConnected = state.configuration?.account?.state === 'VALID_PREMIUM';
      const previousUser = state.configuration?.account?.user_name;
      state.configuration = configuration || {};
      const connected = state.configuration?.account?.state === 'VALID_PREMIUM';
      if (wasConnected && connected && previousUser !== state.configuration?.account?.user_name) {
        state.initialized = false;
        state.siteCatalogs.clear();
        state.talkgroupCatalogs.clear();
        state.talkgroupCatalogId = null;
        state.selectedTalkgroups.clear();
      }
      if (!connected) {
        state.initialized = false;
        state.selectedTalkgroups.clear();
        state.siteCatalogs.clear();
        state.talkgroupCatalogs.clear();
        state.talkgroupCatalogId = null;
        state.bookmarks = [];
        host.replaceChildren(empty('Connect RadioReference to import',
          'A current Premium account is required for directory browsing and imports.'));
        return;
      }
      if (!wasConnected || !state.initialized) void initialize();
    },
    reload() {
      state.initialized = false;
      state.siteCatalogs.clear();
      state.talkgroupCatalogs.clear();
      state.talkgroupCatalogId = null;
      state.selectedTalkgroups.clear();
      void initialize();
    }
  };
}
