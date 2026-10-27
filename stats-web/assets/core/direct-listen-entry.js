/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
import { normalizeDirectListenTarget } from './direct-listen.js?v=1';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const groupKey = /^v1-[gp]-(?:x|[0-9a-f]{5})-(?:x|[0-9a-f]{3})-[1-9][0-9]*$/;

// Navigation references remain the source of identity; labels never become selectors.
export function directListenEntryTarget(row = {}, reference = row.target_entity_ref || row.entity_ref,
  { id, channelOnly = false } = {}) {
  if (reference?.kind === 'radio') return null;
  const conventional = String(row.channel_kind || '').toUpperCase() === 'CONVENTIONAL' ||
    (Array.isArray(row.tags) ? row.tags : String(row.tags || '').split(',')).includes('CONVENTIONAL');
  const channel = reference?.kind === 'channel' ? reference.key : row.configuration_id;
  const analog = /^(?:AM|NBFM)$/.test(String(row.protocol || row.decoder || '').toUpperCase());
  let identity = reference?.identity_key || row.target_identity_key || row.group_identity_key || row.identity_key;
  const referenceKind = ['talkgroup', 'patch_group', 'radio'].includes(reference?.kind) ? reference.kind : '';
  const kind = String(referenceKind || row.group_identity_kind || row.identity_kind ||
    row.target_kind || row.target_form || '').toLowerCase().replace(/\s+/g, '_');
  const p25 = /P25|APCO25/.test(String(row.protocol || row.decoder || '').toUpperCase());
  if (!groupKey.test(identity || '') && ['talkgroup', 'patch_group'].includes(kind) &&
      (!p25 || conventional || reference?.kind === 'channel')) {
    const value = Number(id ?? row.target_id ?? row.native_id ?? row.identity_id ?? row.group_identity_id);
    if (Number.isSafeInteger(value) && value > 0 && value <= 0xFFFFFF) {
      identity = `v1-${kind === 'patch_group' ? 'p' : 'g'}-x-x-${value}`;
    }
  }
  if ((channelOnly || conventional || reference?.kind === 'channel' || analog) && uuid.test(channel || '')) {
    const target = { kind: 'channel', key: channel };
    if (!channelOnly && !analog && !groupKey.test(identity || '')) return null;
    if (!channelOnly && !analog && groupKey.test(identity || '')) target.identity_key = identity;
    return normalizeDirectListenTarget(target);
  }
  const system = reference?.radio_system_key || row.radio_system_key;
  if (system && groupKey.test(identity || '')) return normalizeDirectListenTarget({
    kind: identity.startsWith('v1-p-') ? 'patch_group' : 'talkgroup',
    radio_system_key: system, identity_key: identity
  });
  return null;
}
