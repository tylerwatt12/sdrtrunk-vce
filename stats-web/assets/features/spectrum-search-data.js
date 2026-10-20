import { systemName } from '../core/system-labels.js?v=2';
import { formatHexDecimal } from '../core/call-value-format.js?v=1';

export function spectrumSearchProtocolLabel(candidate, profiles = []) {
  const id = String(candidate?.protocol_id || '');
  const known = { p25phase1: 'P25 Phase 1', p25phase2: 'P25 Phase 2', p25: 'P25', dmr: 'DMR', nxdn: 'NXDN' };
  const label = profiles.find((profile) => profile.id === id)?.label ||
    known[id.toLowerCase().replace(/[^a-z0-9]/g, '')] || id.toUpperCase() || 'Digital';
  const normalized = (value) => String(value).toLowerCase().replace(/[^a-z0-9]/g, '');
  const variant = candidate?.variant;
  const variants = { typec: 'Type-C', typed: 'Type-D', tieriii: 'Tier III',
    hyteratieriii: 'Hytera Tier III', capacitymax: 'Capacity Max', capacityplus: 'Capacity Plus',
    connectplus: 'Connect Plus' };
  const variantLabel = known[normalized(variant)] || variants[normalized(variant)] ||
    String(variant || '').replace(/_/g, ' ');
  if (!variant || normalized(label).includes(normalized(variantLabel))) return label;
  return normalized(variantLabel).startsWith(normalized(label)) ? variantLabel : `${label} ${variantLabel}`;
}

export function spectrumSearchSystemName(candidate) {
  const identity = candidate?.identity || candidate?.trunked_evidence?.identity;
  const wacn = Number(identity?.wacn);
  const system = Number(identity?.system);
  const validIdentity = identity?.wacn != null && identity?.system != null &&
    Number.isInteger(wacn) && wacn >= 0 && wacn <= 0xfffff &&
    Number.isInteger(system) && system >= 0 && system <= 0xfff;
  return systemName(candidate?.system_identity || identity?.system_identity) ||
    systemName({ ...candidate, protocol: candidate?.protocol_id || 'P25',
    radio_system_key: candidate?.radio_system_key || (validIdentity ?
      `p25:${wacn.toString(16).padStart(5, '0')}:${system.toString(16).padStart(3, '0')}` : identity?.radio_system_key || '') });
}


export function spectrumSearchIdentityFacts(candidate, hex) {
  const identity = candidate?.identity || candidate?.trunked_evidence?.identity;
  if (!identity) return [];
  if (identity.wacn != null) return [['WACN', formatHexDecimal(identity.wacn, 5)],
    ['System ID', formatHexDecimal(identity.system, 3)],
    ['RFSS', formatHexDecimal(identity.rfss, 2)], ['Site ID', formatHexDecimal(identity.site, 2)]];
  return [['Protocol', spectrumSearchProtocolLabel(candidate)],
    ['Network', identity.network], ['System ID', identity.system], ['Site ID', identity.site],
    ['Model', identity.model], ['Location category', identity.category], ['Integrator', identity.integrator],
    ['RAN', identity.ran], ['Color code', identity.color_code]]
    .filter(([, value]) => value != null && value !== '');
}

export function spectrumSearchMapDraft(control) {
  return [...control.querySelectorAll('.channel-map-row')].map((row) => {
    const inputs = row.querySelectorAll('input');
    return { number: inputs[0]?.value || '', downlink_hz: inputs[1]?.value ? Number(inputs[1].value) * 1_000_000 : null,
      uplink_hz: inputs[2]?.value ? Number(inputs[2].value) * 1_000_000 : 0 };
  });
}

export function spectrumSearchGroupKey(candidate) {
  const identity = candidate?.identity || candidate?.trunked_evidence?.identity || {};
  return candidate.alias_group_id || candidate.radio_system_key || identity.radio_system_key ||
    (identity.wacn != null && identity.system != null ? `p25:${identity.wacn}:${identity.system}` :
      `candidate:${candidate.candidate_id}`);
}
