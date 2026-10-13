import { href as entityRefHref } from '../core/entity-ref.js?v=1';

export const ALERT_TRIGGERS = Object.freeze([
  ['call', 'Call activity'], ['emergency', 'Emergency alarm received'], ['paging', 'Paging or call alert'],
  ['affiliation', 'Radio affiliation'], ['command', 'Radio control message'],
  ['event', 'Decoded message'], ['tone', 'Decoded AMBE tone'], ['dcs', 'DCS code'],
  ['health', 'Tuner or system health issue']
]);
export const ALERT_PROTOCOL_FAMILIES = Object.freeze([
  ['P25', 'P25'], ['DMR', 'DMR'], ['NXDN', 'NXDN'], ['AM', 'AM'], ['NBFM', 'NBFM']
]);
export const ALERT_FIELDS = Object.freeze([
  ['radio', 'Transmitting radio'], ['target_radio', 'Target radio'], ['talkgroup', 'Talkgroup'],
  ['alias_id', 'Alias'], ['channel_id', 'Channel'], ['system_key', 'Radio system'], ['site_id', 'Site'],
  ['protocol', 'Message protocol'], ['event_type', 'Decoded message type'], ['encrypted', 'Encrypted'],
  ['emergency', 'Emergency flag'], ['duration_seconds', 'Call duration (seconds)'],
  ['tone', 'AMBE tone'], ['dcs', 'DCS code'], ['issue', 'Health issue'],
  ['severity', 'Health severity'], ['state', 'Health state']
]);
export const ALERT_OPERATORS = Object.freeze([
  ['is', 'is'], ['not', 'is not'], ['in', 'is one of'], ['contains', 'contains'],
  ['gte', 'is at least'], ['lte', 'is at most'], ['exists', 'is present']
]);
export const ALERT_TEMPLATE_FIELDS = Object.freeze([
  'talkgroup', 'radio', 'site', 'channel', 'vce', 'issue', 'time'
]);
export function cloneAlert(value) { return JSON.parse(JSON.stringify(value)); }
export function newAlertCondition(field = 'talkgroup', value = '') {
  return { field, operator: 'is', value };
}
export function newAlertRule(timeZone = 'UTC') {
  return { id: '', name: '', topic_id: '', enabled: true, trigger: 'call', system_key: '',
    site_id: '', channel_id: '', protocol_family: '', protocol: '', conditions: { op: 'all', items: [] },
    timing: { mode: 'immediate', minutes: 15, count: 5, window_minutes: 5 },
    schedule: { mode: 'always', days: [0, 1, 2, 3, 4, 5, 6], start: '09:00', end: '17:00', time_zone: timeZone },
    cooldown_minutes: 15, repeat_key: 'rule', notify_recovery: false,
    title_template: '{talkgroup} is active', body_template: '{radio} transmitted on {talkgroup} at {site}.',
    click_action: 'details', action_button_enabled: false };
}

export function alertActionLabel(kind) {
  return ({ live: 'View live channels', channel: 'View channel', radio: 'View radio',
    talkgroup: 'View talkgroup', patch_group: 'View patch group', radio_system: 'View radio system' })[kind] || '';
}

export function alertPopupAction(event) {
  const action = event?.action;
  if (!action || typeof action !== 'object' || Array.isArray(action)) return null;
  const label = alertActionLabel(action.kind);
  if (!label) return null;
  if (action.kind === 'live') return action.entity_ref == null ? { label, href: '/?view=live' } : null;
  if (action.entity_ref?.kind !== action.kind) return null;
  const href = entityRefHref(action.entity_ref);
  return href ? { label, href } : null;
}
export function alertSourceChoices(choices, family = '', systemKey = '') {
  return (Array.isArray(choices) ? choices : []).filter(choice =>
    (!family || choice.protocol_family === family) && (!systemKey || choice.system_key === systemKey));
}
export function alertActivityChoices(family = '') {
  return ALERT_TRIGGERS.filter(([trigger]) => !family ||
    (trigger !== 'tone' || ['P25', 'DMR'].includes(family)) &&
    (trigger !== 'dcs' || ['AM', 'NBFM'].includes(family)));
}
export function alertRuleDescription(rule, topicName = '') {
  const trigger = ALERT_TRIGGERS.find(([id]) => id === rule.trigger)?.[1] || 'Decoded event';
  const timing = rule.timing || {};
  const when = timing.mode === 'quiet' ? `after ${timing.minutes} quiet minutes` :
    timing.mode === 'count' ? `after ${timing.count} matches in ${timing.window_minutes} minutes` :
    timing.mode === 'sustained' ? `after it persists for ${timing.minutes} minutes` : 'when an event matches';
  return `${trigger} ${when}${topicName ? ` → ${topicName}` : ''}.`;
}
export function describeAlertConditions(condition) {
  if (!condition) return 'Any matching event';
  if (condition.op && Array.isArray(condition.items)) {
    if (!condition.items.length) return 'Any matching event';
    const prefix = ({ all: 'All', any: 'Any', none: 'None' })[condition.op] || 'All';
    return `${prefix} of (${condition.items.map(describeAlertConditions).join('; ')})`;
  }
  const label = ALERT_FIELDS.find(([id]) => id === condition.field)?.[1] || condition.field || 'Field';
  const operator = ALERT_OPERATORS.find(([id]) => id === condition.operator)?.[1] || 'is';
  return `${label} ${operator}${condition.operator === 'exists' ? '' : ` ${condition.value ?? ''}`}`;
}
export function visibleAlertTopics(topics) {
  return (Array.isArray(topics) ? topics : []).filter(topic => topic.visible === true);
}
export function availableAlertTopics(topics) {
  return visibleAlertTopics(topics).filter(topic => topic.enabled === true);
}
export function visibleAlertHistory(items, topics, topicId = '') {
  const visible = new Set(visibleAlertTopics(topics).map(topic => topic.id));
  return (Array.isArray(items) ? items : []).filter(item => visible.has(item.topic_id) &&
    (!topicId || item.topic_id === topicId));
}
export function validAlertSiteUrl(value) {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    return url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash &&
      (url.pathname === '/' || !url.pathname) && url.port !== '0' &&
      host.includes('.') && !host.includes(':') && !/^\d+(\.\d+){3}$/.test(host) &&
      !host.endsWith('.local') && !host.endsWith('.localhost');
  } catch { return false; }
}
export function directPushSupport(windowValue) {
  if (!windowValue?.isSecureContext) return { supported: false, message: 'Open this VCE site over trusted HTTPS to enable notifications.' };
  if (!windowValue.Notification || !windowValue.PushManager || !windowValue.navigator?.serviceWorker) {
    return { supported: false, message: 'This browser cannot receive push notifications. Use a supported browser.' };
  }
  const navigatorValue = windowValue.navigator;
  const ios = /iPad|iPhone|iPod/.test(navigatorValue.userAgent || '') ||
    navigatorValue.platform === 'MacIntel' && navigatorValue.maxTouchPoints > 1;
  const installed = Boolean(navigatorValue.standalone || windowValue.matchMedia?.('(display-mode: standalone)').matches);
  if (ios && !installed) return { supported: false, install: true,
    message: 'Add this VCE site to your Home Screen, then open it from there to enable notifications.' };
  return { supported: true };
}
export function vapidKeyBytes(value) {
  const base64 = String(value || '').replace(/-/g, '+').replace(/_/g, '/');
  const decoded = atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '='));
  return Uint8Array.from(decoded, character => character.charCodeAt(0));
}
