const API = '/api/v1/admin/radioreference';
const DIRECTORY_TIMEOUT_MS = 15_000;

export function createDiscoveryRadioReferenceContext(ui, request, { signal, requiredRegion = false } = {}) {
  const { node, uiSelect, uiSelectFrame, formField, uiActionButton, anchor, href } = ui;
  const element = node('details', 'ui-section-disclosure ui-section-disclosure-flat');
  const summary = node('summary', 'ui-section-summary', requiredRegion ? 'Lookup region' : 'RadioReference names (optional)');
  const status = node('p', 'ui-field-hint', 'Loading RadioReference settings…');
  const content = node('div');
  let retryLoad = null;
  const retry = uiActionButton('Retry loading regions', '', () => {
    retry.hidden = true;
    void retryLoad?.();
  }, 'ui-button ui-button-secondary');
  retry.hidden = true;
  element.append(summary, status, retry, content);
  let stateId = null;
  let configuration = null;
  let loading = null;
  let regionLoading = null;
  let busy = false;
  let revision = 0;
  let connecting = false;
  const controls = new Map([[retry, true]]);
  const enable = (control, value) => {
    controls.set(control, value);
    control.disabled = busy || !value;
  };
  const setBusy = (value) => {
    busy = value;
    controls.forEach((enabled, control) => { control.disabled = busy || !enabled; });
  };
  const rows = (document) => Array.isArray(document) ? document : document?.items || document?.rows || [];
  const available = () => configuration?.account?.state === 'VALID_PREMIUM';
  const load = (updatedConfiguration) => {
    if (updatedConfiguration === undefined && loading) return loading;
    const generation = ++revision;
    const active = () => generation === revision && !signal?.aborted;
    element.hidden = false;
    retry.hidden = true;
    retryLoad = null;
    regionLoading = null;
    controls.clear();
    enable(retry, true);
    content.replaceChildren();
    stateId = null;
    status.textContent = 'Loading RadioReference settings…';
    const pending = (async () => {
      try {
        const value = updatedConfiguration === undefined ?
          await request(API, { csrf: false, timeoutMs: DIRECTORY_TIMEOUT_MS }) : updatedConfiguration;
        if (!active()) return;
        configuration = value;
        if (!available()) {
          if (!ui.connectRadioReference) { element.hidden = true; return; }
          status.textContent = 'Connect RadioReference to add system and site names.';
          if (ui.connectRadioReference) {
            const connect = uiActionButton('Connect account', '', async () => {
              if (busy || connecting || !active()) return;
              connecting = true;
              enable(connect, false);
              try {
                const connected = await ui.connectRadioReference({
                  introduction: 'Connect RadioReference to add system and site names.', signal
                });
                if (connected && active()) {
                  await load(connected);
                  if (!signal?.aborted && available() && element.isConnected &&
                      !element.closest('.modal-backdrop')?.inert) {
                    (content.querySelector('select:not([disabled])') || summary).focus({ preventScroll: true });
                  }
                }
              } finally {
                connecting = false;
                if (active()) enable(connect, true);
              }
            }, 'ui-button ui-button-secondary');
            enable(connect, true);
            content.append(connect);
          }
          return;
        }
        content.append(node('p', 'ui-field-hint', 'Connected to RadioReference.'));
        const stored = Number(configuration?.state_id);
        stateId = Number.isSafeInteger(stored) && stored > 0 ? stored : null;
        if (!stateId || !configuration.country_id) element.open = true;
        const statePlaceholder = requiredRegion ? 'Choose a state or province' : 'Skip frequency matching';
        const select = uiSelect([{ value: '', label: statePlaceholder }]);
        select.setAttribute('aria-label', 'RadioReference state or province');
        enable(select, false);
        select.addEventListener('change', () => { stateId = Number(select.value) || null; });
        const stateField = formField('State or province', uiSelectFrame(select));
        let regionLoad = 0;
        const loadStates = (countryId, selectedState = null) => {
          regionLoading = (async () => {
            const regionGeneration = ++regionLoad;
            stateId = null;
            retry.hidden = true;
            enable(select, false);
            status.textContent = 'Loading states and provinces…';
            try {
              const states = rows(await request(`${API}/states?country_id=${encodeURIComponent(countryId)}`,
                { csrf: false, timeoutMs: DIRECTORY_TIMEOUT_MS }));
              if (!active() || regionGeneration !== regionLoad) return;
              select.replaceChildren(node('option', '', statePlaceholder), ...states.map((state) => {
                const option = node('option', '', state.name);
                option.value = String(state.id);
                return option;
              }));
              select.firstChild.value = '';
              select.value = states.some((state) => Number(state.id) === selectedState) ? String(selectedState) : '';
              stateId = Number(select.value) || null;
              if (!stateId) element.open = true;
              enable(select, Boolean(states.length));
              status.textContent = states.length ? (requiredRegion ?
                'Choose a state or province to look up this frequency.' :
                'Choose your state or province to help match nearby systems.') : (requiredRegion ?
                'No states or provinces are available. Choose another country.' :
                'No states or provinces are available. Choose another country in RadioReference settings.');
            } catch (_) {
              if (!active() || regionGeneration !== regionLoad) return;
              stateId = selectedState;
              if (selectedState) {
                const saved = node('option', '', 'Saved lookup region');
                saved.value = String(selectedState);
                select.replaceChildren(saved);
              }
              status.textContent = selectedState ?
                'States and provinces could not be loaded. Your saved lookup region will be used.' :
                requiredRegion ? 'States and provinces could not be loaded. Retry to choose a lookup region.' :
                'States and provinces could not be loaded. Retry, or continue without frequency matching.';
              retryLoad = () => loadStates(countryId, selectedState);
              retry.hidden = false;
              element.open = true;
            }
          })();
          return regionLoading;
        };
        if (configuration.country_id && !requiredRegion) {
          content.append(stateField);
          await loadStates(configuration.country_id, stateId);
        } else {
          const countries = rows(await request(`${API}/countries`,
            { csrf: false, timeoutMs: DIRECTORY_TIMEOUT_MS }));
          if (!active()) return;
          const country = uiSelect([{ value: '', label: 'Choose a country' },
            ...countries.map((value) => ({ value: value.id, label: value.name }))], configuration.country_id || '');
          enable(country, true);
          country.setAttribute('aria-label', 'RadioReference country');
          country.addEventListener('change', () => {
            if (country.value) void loadStates(country.value);
            else {
              regionLoad += 1;
              regionLoading = null;
              retry.hidden = true;
              stateId = null;
              select.value = '';
              enable(select, false);
              status.textContent = requiredRegion ? 'Choose a country to look up this frequency.' :
                'Choose a country to look up nearby systems.';
            }
          });
          content.append(formField('Country', uiSelectFrame(country)), stateField);
          if (country.value) await loadStates(country.value, stateId);
          else status.textContent = requiredRegion ? 'Choose a country to look up this frequency.' :
            'Choose a country to look up nearby systems.';
        }
        if (!requiredRegion) {
          const settings = node('p', 'ui-field-hint');
          settings.append(anchor('Save a default lookup region', href('radioreference')));
          content.append(settings);
        }
      } catch (cause) {
        if (!active()) return;
        if (cause?.status === 403) { element.hidden = true; configuration = null; return; }
        configuration = updatedConfiguration || null;
        retryLoad = () => { loading = null; return load(updatedConfiguration); };
        retry.hidden = false;
        element.open = true;
        status.textContent = requiredRegion ? 'Lookup regions could not be loaded. Retry to choose a lookup region.' :
          'Lookup regions could not be loaded. Retry, or continue without choosing a lookup region.';
      }
    })();
    loading = pending;
    return pending;
  };
  const ready = async (configuration) => {
    await load(configuration);
    let pending;
    do {
      pending = regionLoading;
      if (pending) await pending;
    } while (pending !== regionLoading);
  };
  return { element, load: ready, stateId: () => stateId, available, setBusy };
}

const renderedDirectoryResults = new WeakMap();

export function discoveryRadioReferencePending(values = []) {
  return values.some((value) => value?.radio_reference?.state === 'pending');
}

export function createDiscoveryRadioReferenceWait({ node }) {
  const element = node('div');
  const status = node('p', 'ui-feedback ui-feedback-loading', 'Looking up RadioReference names…');
  status.setAttribute('role', 'status');
  element.append(status);
  element.hidden = true;
  return {
    element,
    update(pending, action = null, disabled = false) {
      element.hidden = !pending;
      status.setAttribute('aria-busy', String(pending));
      if (action) action.disabled = disabled || pending;
      return pending;
    }
  };
}

export function discoveryRadioReferenceSystemUrl(value) {
  const result = value?.radio_reference;
  if (result?.state !== 'matched') return null;
  try {
    const parsed = new URL(result.match?.url);
    return parsed.protocol === 'https:' && /(^|\.)radioreference\.com$/i.test(parsed.hostname) ? parsed.href : null;
  } catch (_) { return null; }
}

export function discoveryRadioReferenceResult(ui, value, existing = null) {
  const result = value?.radio_reference;
  if (!result || ['login_required', 'premium_required'].includes(result.state)) return null;
  const { node, anchor } = ui;
  const details = existing || node('details', 'ui-section-disclosure ui-section-disclosure-flat');
  if (!existing) details.append(node('summary', 'ui-section-summary', 'RadioReference'));
  const match = result.state === 'matched' ? result.match : null;
  const systemUrl = discoveryRadioReferenceSystemUrl(value);
  const signature = JSON.stringify(match ? [match.system_name, systemUrl] : [result.state]);
  if (renderedDirectoryResults.get(details) === signature) return details;
  renderedDirectoryResults.set(details, signature);
  const children = [];
  if (!match) {
    const fallback = {
      location_required: 'Choose a state or province to look up this frequency.',
      identity_required: 'More consistent on-air identity is needed for a directory match.',
      no_match: 'No RadioReference site matches this system and frequency.',
      ambiguous: 'Multiple directory entries match. No directory names were applied.',
      unavailable: 'RadioReference is unavailable. The on-air result is still available.',
      pending: 'Looking up the system name…'
    };
    children.push(node('p', 'muted', fallback[result.state] || 'Directory names are unavailable.'));
  } else {
    const label = node('p');
    const systemName = match.system_name || 'Matched system';
    label.append(systemUrl ? anchor(systemName, systemUrl) : systemName);
    children.push(label);
  }
  if (existing) details.replaceChildren(details.firstElementChild, ...children);
  else details.append(...children);
  return details;
}


export const ALIAS_LIST_NAME_MAX_LENGTH = 128;

export function discoveryAliasListName(system, fallback = '') {
  const value = String(system || '').trim() || String(fallback || '').trim();
  let bounded = value.slice(0, ALIAS_LIST_NAME_MAX_LENGTH);
  if (bounded.length < value.length && /[\uD800-\uDBFF]$/.test(bounded)) bounded = bounded.slice(0, -1);
  return bounded.trim();
}

export function createDiscoveryAliasListChoice(ui, {
  lists = [], aliasListId = null, newAliasListName = '', suggestedAliasListId = null,
  labelSuffix = '', nameEdited: initialNameEdited = false,
  choiceEdited: initialChoiceEdited = aliasListId != null, onChange = () => {}
} = {}) {
  const { node, uiSelect, uiSelectFrame, formField } = ui;
  const element = node('fieldset', 'ui-form-section');
  element.append(node('legend', '', 'Alias List'));
  const matched = lists.filter((list) => list.matched);
  const sameName = (name) => lists.find((list) =>
    list.name.trim().toLowerCase() === String(name || '').trim().toLowerCase());
  const preferred = lists.find((list) => Number(list.id) === Number(suggestedAliasListId));
  let choiceEdited = initialChoiceEdited;
  let nameEdited = initialNameEdited;
  let selected = aliasListId == null ? preferred?.id || (matched.length === 1 ? matched[0].id :
    sameName(newAliasListName)?.id || (matched.length > 1 ? '' : 0)) : aliasListId;
  if (Number(selected) > 0 && !lists.some((list) => Number(list.id) === Number(selected))) selected = '';
  const select = uiSelect([
    ...lists.map((list) => ({ value: String(list.id), label: `Use existing: ${list.name}` })),
    { value: '0', label: 'Create a new Alias List' }
  ], String(selected), selected === '', 'Choose an Alias List');
  select.required = true;
  select.setAttribute('aria-label', `Alias List${labelSuffix}`);
  const found = node('p', 'ui-field-hint');
  const input = node('input', 'ui-input');
  input.type = 'text';
  input.maxLength = ALIAS_LIST_NAME_MAX_LENGTH;
  input.value = discoveryAliasListName(newAliasListName);
  input.setAttribute('aria-label', `New Alias List name${labelSuffix}`);
  const newField = formField('New Alias List name', input);
  const collision = node('p', 'ui-feedback ui-feedback-error');
  collision.setAttribute('role', 'status');
  const defaults = node('p', 'ui-field-hint', 'New Alias Lists use your Default listening list.');
  element.append(found, formField('Alias List', uiSelectFrame(select)), newField, collision, defaults);
  const value = () => select.value === '' ? null : Number(select.value);
  const newName = () => input.value.trim();
  const name = () => value() > 0 ? lists.find((list) => Number(list.id) === value())?.name || '' : newName();
  const sync = () => {
    const creating = value() === 0;
    newField.hidden = !creating;
    input.required = creating;
    input.disabled = !creating;
    defaults.hidden = !creating;
    const existing = sameName(newName());
    const selectedList = lists.find((list) => Number(list.id) === value());
    found.textContent = selectedList ? `Existing Alias List found: ${selectedList.name}` :
      existing ? `Existing Alias List found: ${existing.name}` : lists.length ? 'Existing Alias Lists are available.' : '';
    found.hidden = !found.textContent;
    const error = creating && existing ?
      `An Alias List named ${existing.name} already exists. Choose it above or enter a different name.` : '';
    collision.textContent = error;
    collision.hidden = !error;
    input.setCustomValidity(error);
  };
  const notify = () => onChange({ alias_list_id: value(), new_alias_list_name: newName(),
    name_edited: nameEdited, choice_edited: choiceEdited });
  select.addEventListener('change', () => { choiceEdited = true; sync(); notify(); });
  input.addEventListener('input', () => { nameEdited = true; sync(); notify(); });
  sync();
  return {
    element, select, input, value, name, newName,
    refreshName(updated) {
      if (nameEdited) return;
      const before = [select.value, input.value];
      input.value = discoveryAliasListName(updated);
      if (!choiceEdited) {
        const existing = sameName(input.value);
        if (existing && value() === 0) select.value = String(existing.id);
      }
      sync();
      if (before[0] !== select.value || before[1] !== input.value) notify();
    },
    validate() {
      sync();
      return select.reportValidity() && (input.disabled || input.reportValidity());
    }
  };
}

export function createDiscoveryTalkgroupAvailability(request) {
  const catalogs = new Map();
  return async (targets) => {
    const checked = await Promise.all(targets.map(async (target) => {
      const id = Number(target.rrSystemId);
      if (!target.matched || !Number.isSafeInteger(id) || id <= 0) return null;
      if (!catalogs.has(id)) {
        const pending = request(`${API}/systems/talkgroups?system_id=${id}&offset=0&limit=1`,
          { csrf: false, timeoutMs: DIRECTORY_TIMEOUT_MS }).then((page) =>
          (Array.isArray(page) ? page : page?.items || []).length > 0).catch(() => {
          catalogs.delete(id);
          throw new Error('RadioReference talkgroups could not be checked. Try again.');
        });
        catalogs.set(id, pending);
      }
      return await catalogs.get(id) ? target : null;
    }));
    return checked.filter(Boolean);
  };
}

export function discoveryAliasImportChoice(ui, targets, selected = true) {
  const { node } = ui;
  const matched = targets.some((target) => target.matched);
  const element = node('div', 'spectrum-discovery-review');
  element.append(node('p', 'muted', 'Import all talkgroups from each matched RadioReference system into the selected Alias List.'));
  targets.forEach((target) => {
    const lists = node('dl', 'ui-fact-list');
    lists.append(node('dt', '', 'Alias List'), node('dd', '', target.name), node('dt', '', 'System'),
      node('dd', '', target.matched ? target.systemName : 'No RadioReference match. Import these talkgroups later.'));
    element.append(lists);
  });
  const choices = node('fieldset', 'spectrum-discovery-protocols');
  choices.setAttribute('aria-label', 'Import talkgroups');
  let wantsImport = matched && selected;
  [
    [true, 'Yes, import talkgroup names'],
    [false, "No, I'll import these later"]
  ].forEach(([value, label]) => {
    const option = node('label', 'ui-choice-card spectrum-discovery-protocol-option');
    const radio = node('input', 'ui-choice-radio');
    radio.type = 'radio'; radio.name = 'discovery-import-talkgroups'; radio.value = String(value);
    radio.checked = wantsImport === value;
    radio.disabled = value && !matched;
    if (radio.disabled) radio.dataset.importUnavailable = 'true';
    radio.setAttribute('aria-label', label);
    radio.addEventListener('change', () => { wantsImport = value; });
    option.append(radio, node('strong', '', label));
    choices.append(option);
  });
  element.append(choices);
  if (!matched) element.append(node('p', 'ui-field-hint',
    'A RadioReference match and Premium account are needed to import talkgroups. Your channels can still be added.'));
  return { element, wantsImport: () => wantsImport };
}

export function discoveryAliasImportResults(ui, result) {
  const { node } = ui;
  const element = node('section', 'ui-form-section spectrum-discovery-review');
  element.append(node('h4', '', 'Talkgroup imports'));
  (result?.targets || []).forEach((target) => {
    const row = node('div', 'ui-notice' + (target.state === 'imported' ? ' ui-notice-success' :
      ['failed', 'unavailable', 'restart_required'].includes(target.state) ? ' ui-notice-warning' : ''));
    row.append(node('strong', '', target.alias_list_name || target.system_name || 'Alias List'));
    const text = target.state === 'imported' ?
      target.message || `${target.added} added, ${target.updated} updated, ${target.unchanged} unchanged.` :
      target.state === 'importing' ? 'Importing all system talkgroups…' :
      target.state === 'pending' ? 'Waiting to import talkgroups.' : target.message || 'Import these talkgroups later.';
    row.append(node('p', '', text));
    element.append(row);
  });
  return element;
}

export function discoveryAliasImportForSelection(result, expected) {
  const targets = [...new Map(expected.map((target) => [Number(target.alias_list_id), target])).values()]
    .map((target) => (result?.targets || []).find((row) => Number(row.alias_list_id) === Number(target.alias_list_id)) || {
      ...target, state: 'failed', added: 0, updated: 0, unchanged: 0,
      message: 'Talkgroups were not imported. Retry, or import them from RadioReference.'
    });
  return { ...result, targets, complete: targets.every((target) => target.state === 'imported') };
}

export async function importDiscoveryAliases(request, url, initial, onProgress, current = () => true) {
  let result = initial || { targets: [], complete: true };
  const scope = new Set(result.targets.map((target) => target.alias_list_id));
  const ids = result.targets.filter((target) => !['imported', 'unavailable', 'restart_required'].includes(target.state))
    .map((target) => target.alias_list_id);
  for (const id of ids) {
    if (!current()) break;
    if (result.targets.find((target) => target.alias_list_id === id)?.state === 'restart_required') continue;
    result = { ...result, targets: result.targets.map((target) => target.alias_list_id === id ?
      { ...target, state: 'importing' } : target) };
    onProgress(result);
    try {
      const updated = await request(url, { method: 'POST', body: { alias_list_id: id }, timeoutMs: 90_000 });
      result = discoveryAliasImportForSelection(updated, initial.targets.filter((target) => scope.has(target.alias_list_id)));
    } catch (cause) {
      if (!current()) break;
      result = { ...result, complete: false, targets: result.targets.map((target) => target.alias_list_id === id ?
        { ...target, state: 'failed', message: cause.message || 'Talkgroups could not be imported. Try again.' } : target) };
    }
    if (current()) onProgress(result);
  }
  return result;
}
