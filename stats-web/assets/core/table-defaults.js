// Default layouts for every data table. Tables not listed here use their declared
// column order, show every column, and use the shared widths below.
const COLUMN_WIDTHS = Object.freeze({
  action: 82, affiliation: 190, 'confirmed-channel': 220, alias: 170,
  band: 54, calls: 66, 'control-frequency': 94, count: 94,
  decoder: 78, encrypted: 52, encryption: 92, signaling: 134,
  event: 115, 'first-seen': 166, frequency: 94, group: 135,
  'group-identity-description': 240, 'group-identity-id': 90,
  'group-identity-name': 175, 'last-active': 166, 'last-seen': 166,
  lcn: 68, name: 175, 'neighbor-name': 175, radio: 82,
  'radio-alias': 165, recorded: 78, rfss: 66, signal: 82,
  'decode-health': 90, site: 66, source: 82, 'source-alias': 165,
  'source-ota-alias': 165, state: 82, status: 116, streamed: 82,
  system: 106, 'talker-alias': 160, 'talkgroup-name': 175,
  target: 82, 'target-alias': 165, time: 166, wacn: 108,
  select: 48
});

const TABLE_DEFAULTS = Object.freeze({
  'dashboard-receivers': { widths: {
    name: 442, mode: 180, context: 315, frequency: 237, 'last-seen': 419
  } },
  'live-events': { widths: {
    time: 92, duration: 78, event: 181, from: 96, to: 164,
    channel: 92, details: 864
  } },
  'live-messages': { widths: { time: 95, context: 110, message: 1200 } },
  'alias-editor-source-breakdown': { widths: {
    source: 230, 'source-calls': 100, 'source-signaling': 110, 'last-seen': 166
  } },
  'alias-editor-custom': { visible: [
    'select', 'alias', 'description', 'identifier', 'matcher', 'group', 'calls', 'signaling', 'last-seen'
  ] },
  'live-channels': { widths: {
    status: 145, tags: 180, channel: 130, frequency: 100, signal: 90,
    'decode-health': { normal: 120, detailed: 260 }, 'source-alias': 220,
    source: 105, 'target-alias': 220, target: 105, decoder: 80
  } },
  'admin-users': { widths: {
    username: 230, 'access-tier': 150, 'password-changed': 190, actions: 230
  } },
  'admin-access': { widths: {
    capability: 310, 'required-tier': 170, 'default-tier': 120, 'policy-status': 130
  } },
  'admin-scan-lists': { widths: {
    'scan-list': 240, aliases: 130, 'unmatched-alias-lists': 260, actions: 360
  } },
  'radioreference-sites': { widths: { site: 340, system: 200 } },
  'radioreference-talkgroups': { widths: {
    selected: 54, talkgroup: 130, 'alpha-tag': 220, category: 180, status: 150
  } },
  'radioreference-conventional': { widths: {
    frequency: 150, 'alpha-tag': 220, mode: 120
  } }
});

function layout(tableType, columns) {
  const schema = columns.map((column) => column.id);
  const defaults = TABLE_DEFAULTS[tableType] || {};
  const namedOrder = (defaults.order || []).filter((id) => schema.includes(id));
  const order = [...namedOrder, ...schema.filter((id) => !namedOrder.includes(id))];
  const visible = defaults.visible ? new Set(defaults.visible) : null;
  return {
    schema,
    column_order: order,
    column_widths: {},
    hidden_columns: visible ? columns.filter((column) =>
      !visible.has(column.id) && !column.essential).map((column) => column.id) : []
  };
}

function width(tableType, column, variant = 'normal') {
  const specified = TABLE_DEFAULTS[tableType]?.widths?.[column.id];
  const tableWidth = typeof specified === 'object' ? specified[variant] ?? specified.normal : specified;
  if (Number.isFinite(tableWidth)) return tableWidth;
  const semanticWidth = COLUMN_WIDTHS[column.id];
  if (semanticWidth) return semanticWidth;
  if (String(column.className || '').includes('alias-cell')) return 190;
  if (String(column.className || '').includes('numeric')) return 100;
  return Math.max(90, Math.min(220, String(column.label || '').length * 9 + 34));
}

export { layout, width };
