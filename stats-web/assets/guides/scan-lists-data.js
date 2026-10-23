import { getAliasesGuideData } from './aliases-presentation.js?v=2';

// Fictional records only. The normal Scan Lists page, member table, dialogs and
// player panel render these inputs; this module does not create UI.
export function getScanListsGuideData(section = 'lists') {
  const base = getAliasesGuideData();
  const lists = [
    { ...base.list, alias_count: 2,
      unknown_alias_behavior: { recordable: true, scan_list_ids: [], broadcast_configuration_ids: [] } },
    { ...base.list, id: 900002, alias_list_id: 900002, name: 'Regional Services',
      alias_count: 2, assigned_channel_count: 1,
      unknown_alias_behavior: { recordable: false, scan_list_ids: [], broadcast_configuration_ids: [] } }
  ];
  const scanLists = [
    { id: 900001, name: 'Public Safety', description: 'Dispatch and public services across our radio systems.',
      sort_order: 0, default: true, published: true, enabled: true, alias_count: 4,
      unmatched_alias_list_count: 0, unmatched_alias_lists: [], edit_revision: 'guide-public-safety' },
    { id: 900002, name: 'Fire & EMS', description: 'Fire dispatch and emergency medical calls.',
      sort_order: 10, default: false, published: true, enabled: true, alias_count: 2,
      unmatched_alias_list_count: 0, unmatched_alias_lists: [], edit_revision: 'guide-fire-ems' }
  ];
  const rows = base.rows.map((row, index) => ({ ...row,
    alias_list_id: lists[index < 2 ? 0 : 1].id,
    alias_list_name: lists[index < 2 ? 0 : 1].name,
    scan_list_ids: index < 2 ? [900001, 900002] : [900001],
    scan_lists: index < 2 ? ['Public Safety', 'Fire & EMS'] : ['Public Safety']
  }));
  const memberPage = { ...base.page, rows };
  const selectedScanList = scanLists[0];
  const options = { ...base.options, alias_list: lists[0], scan_lists: scanLists };
  return { ...base, section, list: lists[0], lists, rows, page: memberPage, memberPage, options,
    selectedScanList, scanLists, scanListCatalog: { revision: 0, scan_lists: scanLists },
    recordResponse: { ...base.recordResponse, alias: rows[0] },
    analytics: { ...base.analytics, ...rows[0] },
    selectedIds: [],
    query: new URLSearchParams({ view: 'aliases', scanListId: '900001', aliasTab: 'configure' }) };
}
