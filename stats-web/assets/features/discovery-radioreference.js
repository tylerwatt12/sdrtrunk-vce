const API = '/api/v1/admin/radioreference';
const DIRECTORY_TIMEOUT_MS = 15_000;

export function createDiscoveryRadioReferenceContext(ui, request) {
  const { node, uiSelect, uiSelectFrame, formField, uiActionButton, anchor, href } = ui;
  const element = node('details', 'ui-section-disclosure ui-section-disclosure-flat');
  element.append(node('summary', 'ui-section-summary', 'RadioReference names (optional)'));
  const status = node('p', 'ui-field-hint', 'Loading RadioReference settings…');
  let retryLoad = null;
  const retry = uiActionButton('Retry loading regions', '', () => {
    retry.hidden = true;
    void retryLoad?.();
  }, 'ui-button ui-button-secondary');
  retry.hidden = true;
  element.append(status, retry);
  let stateId = null;
  let loading = null;
  let regionLoading = null;
  const rows = (document) => Array.isArray(document) ? document : document?.items || document?.rows || [];
  const load = () => {
    if (loading) return loading;
    retry.hidden = true;
    status.textContent = 'Loading RadioReference settings…';
    loading = (async () => {
      try {
        const configuration = await request(API, { csrf: false, timeoutMs: DIRECTORY_TIMEOUT_MS });
        const stored = Number(configuration?.state_id);
        stateId = Number.isSafeInteger(stored) && stored > 0 ? stored : null;
        if (configuration?.account?.state !== 'VALID_PREMIUM') {
          status.replaceChildren('Connect a Premium account to add directory names. ',
            anchor('RadioReference settings', href('radioreference')));
          return;
        }
        element.open = !stateId || !configuration.country_id;
        const select = uiSelect([{ value: '', label: 'Skip frequency matching' }]);
        select.setAttribute('aria-label', 'RadioReference state or province');
        select.disabled = true;
        select.addEventListener('change', () => { stateId = Number(select.value) || null; });
        const stateField = formField('State or province', uiSelectFrame(select));
        let regionLoad = 0;
        const loadStates = (countryId, selectedState = null) => {
          regionLoading = (async () => {
            const generation = ++regionLoad;
            stateId = null;
            retry.hidden = true;
            select.disabled = true;
            status.textContent = 'Loading states and provinces…';
            try {
              const states = rows(await request(`${API}/states?country_id=${encodeURIComponent(countryId)}`,
                { csrf: false, timeoutMs: DIRECTORY_TIMEOUT_MS }));
              if (generation !== regionLoad) return;
              select.replaceChildren(node('option', '', 'Skip frequency matching'), ...states.map((state) => {
                const option = node('option', '', state.name);
                option.value = String(state.id);
                return option;
              }));
              select.firstChild.value = '';
              select.value = states.some((state) => Number(state.id) === selectedState) ? String(selectedState) : '';
              stateId = Number(select.value) || null;
              if (!stateId) element.open = true;
              select.disabled = !states.length;
              status.textContent = states.length ?
                'P25 systems can match by on-air identity. Choose a region for other frequency lookups.' :
                'No states or provinces are available. Choose another country in RadioReference settings.';
            } catch (_) {
              if (generation !== regionLoad) return;
              stateId = selectedState;
              if (selectedState) {
                const saved = node('option', '', 'Saved lookup region');
                saved.value = String(selectedState);
                select.replaceChildren(saved);
              }
              status.textContent = selectedState ?
                'States and provinces could not be loaded. Your saved lookup region will be used.' :
                'States and provinces could not be loaded. Retry, or continue without frequency matching.';
              retryLoad = () => loadStates(countryId, selectedState);
              retry.hidden = false;
              element.open = true;
            }
          })();
          return regionLoading;
        };
        if (configuration.country_id) {
          element.append(stateField);
          await loadStates(configuration.country_id, stateId);
        } else {
          const countries = rows(await request(`${API}/countries`,
            { csrf: false, timeoutMs: DIRECTORY_TIMEOUT_MS }));
          const country = uiSelect([{ value: '', label: 'Choose a country' },
            ...countries.map((value) => ({ value: value.id, label: value.name }))]);
          country.setAttribute('aria-label', 'RadioReference country');
          country.addEventListener('change', () => {
            if (country.value) void loadStates(country.value);
            else {
              regionLoad += 1;
              regionLoading = null;
              retry.hidden = true;
              stateId = null;
              select.value = '';
              select.disabled = true;
              status.textContent = 'Choose a country for frequency matching.';
            }
          });
          element.append(formField('Country', uiSelectFrame(country)), stateField);
          status.textContent = 'P25 systems can match by on-air identity. Choose a country for other frequency lookups.';
        }
        const settings = node('p', 'ui-field-hint');
        settings.append(anchor('Save a default lookup region', href('radioreference')));
        element.append(settings);
      } catch (_) {
        loading = null;
        retryLoad = load;
        retry.hidden = false;
        element.open = true;
        status.textContent = 'Lookup regions could not be loaded. Retry, or continue without choosing a lookup region.';
      }
    })();
    return loading;
  };
  const ready = async () => {
    await load();
    let pending;
    do {
      pending = regionLoading;
      if (pending) await pending;
    } while (pending !== regionLoading);
  };
  return { element, load: ready, stateId: () => stateId };
}

export function discoveryRadioReferenceResult(ui, value) {
  const result = value?.radio_reference;
  if (!result) return null;
  const { node, anchor, channelMHz, href } = ui;
  const details = node('details', 'ui-section-disclosure ui-section-disclosure-flat');
  details.append(node('summary', 'ui-section-summary', 'RadioReference'));
  const match = result.state === 'matched' ? result.match : null;
  if (!match) {
    const fallback = {
      location_required: 'Choose a state or province to look up this frequency.',
      login_required: 'Connect RadioReference to look up directory names.',
      premium_required: 'A Premium account is required for directory names.',
      identity_required: 'More consistent on-air identity is needed for a directory match.',
      no_match: 'No directory system matched this on-air identity.',
      ambiguous: 'Multiple directory entries match. No directory names were applied.',
      unavailable: 'RadioReference is unavailable. The on-air result is still available.',
      pending: 'Directory matching is in progress.'
    };
    details.append(node('p', 'muted', result.message || fallback[result.state] || 'Directory names are unavailable.'));
    if (href && ['location_required', 'login_required', 'premium_required'].includes(result.state)) {
      const action = node('p', 'ui-field-hint');
      action.append(anchor('RadioReference settings', href('radioreference')));
      details.append(action);
    }
    return details;
  }
  const allowedLink = (url) => {
    try {
      const parsed = new URL(url);
      return parsed.protocol === 'https:' && /(^|\.)radioreference\.com$/i.test(parsed.hostname) ? parsed.href : null;
    } catch (_) { return null; }
  };
  const label = node('p');
  const systemUrl = allowedLink(match.url);
  const siteUrl = allowedLink(match.site_url);
  label.append(systemUrl ? anchor(match.system_name, systemUrl) : match.system_name || 'Matched system');
  if (match.site_name) label.append(' · ', siteUrl ? anchor(match.site_name, siteUrl) : match.site_name);
  details.append(label);
  const channels = Array.isArray(match.channels) ? match.channels : [];
  if (channels.length) {
    const list = node('dl', 'ui-fact-list');
    channels.forEach((channel) => {
      const number = channel.logical_channel_number ?? channel.channel_id;
      list.append(node('dt', '', number == null ? 'Channel' : `Channel ${number}`),
        node('dd', '', `${channelMHz(channel.frequency_hz)} MHz${channel.primary_control ? ' · Primary control' :
          channel.alternate_control ? ' · Alternate control' : channel.use ? ` · ${channel.use}` : ''}`));
    });
    details.append(list);
  }
  if (result.provenance) details.append(node('p', 'ui-field-hint', typeof result.provenance === 'string' ?
    result.provenance : result.provenance.description || 'Matched by on-air identity and site frequency.'));
  return details;
}
