'use strict';

const { installSiteStyleApplication } = require('./site-style-app.cjs');

async function installAliasSectionGroups(page, theme) {
  await installSiteStyleApplication(page, theme);
  const behavior = { recordable: true, scan_list_ids: [7], broadcast_configuration_ids: ['stream-1'] };
  const list = { alias_list_id: 1, name: 'County Public Safety', family: 'P25',
    unknown_alias_behavior: behavior, new_alias_behavior: behavior };
  await page.route('**/api/v1/admin/aliases/options*', route => route.fulfill({ json: { data: {
    revision: 1, edit_revision: 'defaults-v1', alias_list: list,
    scan_lists: ['Dispatch Favorites', 'Countywide', 'Fireground', 'Mutual Aid', 'Public Works', 'Local Events']
      .map((name, index) => ({ id: 7 + index, name, published: true })),
    streams: [{ configuration_id: 'stream-1', name: 'County Calls' },
      { configuration_id: 'stream-2', name: 'Regional Calls' }],
    matchers: [{ type: 'talkgroup', protocol: 'APCO25', label: 'Talkgroup', fields: ['value'],
      minimum: 1, maximum: 65535 }]
  } } }));
  await page.route('**/api/v1/admin/aliases/101', route => route.fulfill({ json: { data: {
    edit_revision: 'alias-v1', alias: { alias_id: 101, alias_list_id: 1, name: 'Fire Dispatch',
      group: 'Fire', color: 0, ...behavior, matcher: { type: 'talkgroup', protocol: 'APCO25', value: 1201 } }
  } } }));
}

module.exports = { installAliasSectionGroups };
