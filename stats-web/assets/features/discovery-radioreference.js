const API = '/api/v1/admin/radioreference';

export function createDiscoveryRadioReferenceContext(ui, request) {
  const { node, uiSelect, uiSelectFrame, formField, anchor, href } = ui;
  const element = node('details', 'ui-section-disclosure ui-section-disclosure-flat');
  element.append(node('summary', 'ui-section-summary', 'RadioReference names (optional)'));
  const status = node('p', 'ui-field-hint', 'Loading RadioReference settings…');
  element.append(status);
  let stateId = null;
  let initialized = false;
  const load = async () => {
    if (initialized) return;
    initialized = true;
    try {
      const configuration = await request(API, { csrf: false, timeoutMs: 5000 });
      const stored = Number(configuration?.state_id);
      stateId = Number.isSafeInteger(stored) && stored > 0 ? stored : null;
      if (configuration?.account?.state !== 'VALID_PREMIUM') {
        status.replaceChildren('Connect a Premium account to add directory names. ',
          anchor('RadioReference settings', href('radioreference')));
        return;
      }
      if (!configuration.country_id) {
        status.replaceChildren('Choose your country in ', anchor('RadioReference settings', href('radioreference')),
          ' to match systems in your area.');
        return;
      }
      const document = await request(`${API}/states?country_id=${encodeURIComponent(configuration.country_id)}`,
        { csrf: false, timeoutMs: 5000 });
      const states = Array.isArray(document) ? document : document?.items || document?.rows || [];
      const select = uiSelect([{ value: '', label: 'Skip directory matching' },
        ...states.map((state) => ({ value: state.id, label: state.name }))], stateId || '');
      select.setAttribute('aria-label', 'RadioReference state or province');
      select.addEventListener('change', () => { stateId = Number(select.value) || null; });
      status.textContent = 'Matches require consistent on-air system and site identity within this area.';
      element.append(formField('State or province', uiSelectFrame(select)));
    } catch (_) {
      status.textContent = 'Directory names are unavailable. Signal discovery can continue.';
    }
  };
  return { element, load, stateId: () => stateId };
}

export function discoveryRadioReferenceResult(ui, value) {
  const result = value?.radio_reference;
  if (!result) return null;
  const { node, anchor, channelMHz } = ui;
  const details = node('details', 'ui-section-disclosure ui-section-disclosure-flat');
  details.append(node('summary', 'ui-section-summary', 'RadioReference'));
  const match = result.state === 'matched' ? result.match : null;
  if (!match) {
    const fallback = {
      location_required: 'Choose a state or province to look up directory names.',
      login_required: 'Connect RadioReference to look up directory names.',
      premium_required: 'A Premium account is required for directory names.',
      identity_required: 'More consistent on-air identity is needed for a directory match.',
      no_match: 'No directory system matched this on-air identity.',
      ambiguous: 'Multiple directory entries match. No directory names were applied.',
      unavailable: 'RadioReference is unavailable. The on-air result is still available.',
      pending: 'Directory matching is in progress.'
    };
    details.append(node('p', 'muted', result.message || fallback[result.state] || 'Directory names are unavailable.'));
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
