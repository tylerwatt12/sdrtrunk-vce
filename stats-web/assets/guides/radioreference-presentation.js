/** Data-only RadioReference responses for the production import workspace during its page tour. */
const ROOT = '/api/v1/admin/radioreference';
const copy = (value) => structuredClone(value);
const IDS = Object.freeze({ system: 900201, site: 900301, aliasList: 900101,
  state: 900501, county: 900401, sheriff: 900601, fire: 900602, publicWorks: 900603 });

const SYSTEM = Object.freeze({ id: IDS.system, name: 'County P25', type: 'TRUNKED_SYSTEM',
  system_type: 'P25', protocol: 'P25', flavor: 'Project 25 Phase II', scope: 'COUNTY',
  last_updated_epoch_millis: 1792454400000 });
const SITE = Object.freeze({ id: IDS.site, number: 1, name: 'North Site', county_id: IDS.county,
  county_name: 'Example County', detected_modulation: 'C4FM', channels: [
    { frequency_hz: 851012500, primary_control: true },
    { frequency_hz: 851512500, alternate_control: true },
    { frequency_hz: 852012500 }
  ] });
const TALKGROUPS = Object.freeze([
  { status: 'DIFFERENT', talkgroup: { id: IDS.sheriff, value: 1001, alpha_tag: 'Sheriff Dispatch 1',
    description: 'County sheriff dispatch', category_id: 1 }, category: 'Law Enforcement',
  changes: [{ field: 'name', before: 'Sheriff Dispatch', after: 'Sheriff Dispatch 1' }] },
  { status: 'NOT_PRESENT', talkgroup: { id: IDS.fire, value: 2001, alpha_tag: 'Regional Fire',
    description: 'Regional fire dispatch', category_id: 2 }, category: 'Fire/EMS', changes: [] },
  { status: 'IDENTICAL', talkgroup: { id: IDS.publicWorks, value: 3001, alpha_tag: 'Public Works',
    description: 'County public works', category_id: 3 }, category: 'Public Works', changes: [] }
]);
const CATEGORIES = Object.freeze([{ id: 1, name: 'Law Enforcement' }, { id: 2, name: 'Fire/EMS' },
  { id: 3, name: 'Public Works' }]);

export const RADIO_REFERENCE_GUIDE_IDS = IDS;

/** Each call owns its fake account, bookmarks, preferences, previews and locality cache. */
export function createRadioReferenceGuideData({ signal = null } = {}) {
  const bookmarks = new Map();
  const preferences = new Map([[IDS.system, IDS.aliasList]]);
  const previews = new Map();
  const localities = new Map();
  const calls = [];
  let updateApplied = false;
  const check = () => {
    if (signal?.aborted) throw new DOMException('RadioReference examples were closed.', 'AbortError');
  };
  const talkgroups = (compared = true) => copy(TALKGROUPS).map((row) => {
    if (!compared) return { ...row, status: 'UNCOMPARED', changes: [] };
    return updateApplied && row.talkgroup.id === IDS.sheriff ?
      { ...row, status: 'IDENTICAL', changes: [] } : row;
  });
  const configuration = { account: { state: 'VALID_PREMIUM', user_name: 'Tour example' },
    country_id: 1, state_id: IDS.state, county_id: IDS.county };
  const requestJson = async (path, options = {}) => {
    check();
    const url = new URL(path, 'https://radioreference-guide.invalid');
    const method = options.method || 'GET';
    const body = typeof options.body === 'string' ? JSON.parse(options.body) : options.body || {};
    calls.push({ path: url.pathname + url.search, method, body: copy(body) });
    let response;
    if (url.pathname === '/api/v1/admin/alias-lists' && method === 'GET') {
      response = { alias_lists: [{ id: IDS.aliasList, name: 'County P25', family: 'P25', alias_count: 2 }] };
    } else if (url.pathname === ROOT && method === 'GET') response = configuration;
    else if (url.pathname === `${ROOT}/countries` && method === 'GET') {
      response = [{ id: 1, name: 'United States', abbreviation: 'US' }];
    } else if (url.pathname === `${ROOT}/states` && method === 'GET') {
      response = [{ id: IDS.state, name: 'Pennsylvania', abbreviation: 'PA' }];
    } else if (url.pathname === `${ROOT}/counties` && method === 'GET') {
      response = [{ id: IDS.county, name: 'Example County' }];
    } else if (url.pathname === `${ROOT}/browse/catalog` && method === 'GET') {
      response = { items: [SYSTEM,
        { id: 900204, name: 'City P25', type: 'TRUNKED_SYSTEM', system_type: 'P25', scope: 'COUNTY',
          last_updated_epoch_millis: 1792195200000 },
        { id: 900202, name: 'Statewide P25', type: 'TRUNKED_SYSTEM', system_type: 'P25', scope: 'STATE',
          last_updated_epoch_millis: 1792368000000 },
        { id: 900203, name: 'National Interoperability', type: 'CONVENTIONAL_AGENCY', scope: 'NATIONAL',
          last_updated_epoch_millis: 1792281600000 }] };
    } else if (url.pathname === `${ROOT}/bookmarks`) {
      if (method === 'PUT') bookmarks.set(`${body.kind}:${body.id}`, copy(body));
      else if (method === 'DELETE') bookmarks.delete(`${body.kind}:${body.id}`);
      else if (method !== 'GET') throw new Error('Unsupported example bookmark action.');
      response = { items: [...bookmarks.values()] };
    } else if (url.pathname === `${ROOT}/system-preferences`) {
      if (method === 'PUT') preferences.set(Number(body.system_id), Number(body.preferred_alias_list_id));
      else if (method === 'DELETE') preferences.delete(Number(body.system_id));
      else if (method !== 'GET') throw new Error('Unsupported example preference action.');
      response = method === 'GET' ? { items: [...preferences].map(([system_id, preferred_alias_list_id]) =>
        ({ system_id, preferred_alias_list_id })) } :
        { system_id: Number(body.system_id), preferred_alias_list_id:
          preferences.get(Number(body.system_id)) || null };
    } else if (url.pathname === `${ROOT}/systems/details` && method === 'GET') {
      response = { system: SYSTEM, talkgroup_categories: CATEGORIES };
    } else if (url.pathname === `${ROOT}/systems/sites/catalog` && method === 'GET') {
      response = { items: [SITE] };
    } else if (url.pathname === `${ROOT}/systems/talkgroups/catalog` && method === 'GET') {
      response = { catalog_id: 'guide-county-p25-talkgroups', categories: CATEGORIES,
        items: talkgroups(Boolean(url.searchParams.get('alias_list_id'))) };
    } else if (url.pathname === `${ROOT}/imports/site/preview` && method === 'POST') {
      response = { preview_id: 'guide-site-preview', operation: 'CREATE', warnings: [],
        detected_modulation: 'C4FM', channel: { name: body.channel_name || 'County P25 · North Site',
          protocol: 'P25', source: { frequencies_hz: [851012500, 851512500] } } };
      previews.set(response.preview_id, { kind: 'site', body: copy(body) });
    } else if (url.pathname === `${ROOT}/imports/talkgroups/preview` && method === 'POST') {
      const selected = talkgroups().filter((row) => body.import_all ||
        body.talkgroup_ids?.includes(row.talkgroup.id));
      response = { preview_id: 'guide-talkgroups-preview', rows: selected,
        add: selected.filter((row) => row.status === 'NOT_PRESENT').length,
        update: selected.filter((row) => row.status === 'DIFFERENT').length,
        unchanged: selected.filter((row) => row.status === 'IDENTICAL').length, warnings: [] };
      previews.set(response.preview_id, { kind: 'talkgroups', body: copy(body) });
    } else {
      const match = new RegExp(`^${ROOT}/imports/([^/]+)/apply$`).exec(url.pathname);
      const preview = match && previews.get(decodeURIComponent(match[1]));
      if (method !== 'POST' || !preview) throw new Error(`Unsupported RadioReference example request: ${method} ${url.pathname}`);
      previews.delete(decodeURIComponent(match[1]));
      if (preview.kind === 'site') response = { created: 1, configuration_id: 'guide-county-north' };
      else {
        updateApplied = true;
        response = { added: 0, updated: 1, alias_list_id: IDS.aliasList };
      }
    }
    check();
    return copy(response);
  };
  return {
    configuration: copy(configuration), requestJson,
    localityCache: { async load(key, loader) {
      check();
      if (!localities.has(key)) localities.set(key, copy(await loader()));
      check();
      return copy(localities.get(key));
    } },
    get requests() { return copy(calls); }
  };
}
