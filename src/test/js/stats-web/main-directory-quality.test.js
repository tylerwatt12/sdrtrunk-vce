'use strict';

const assert = require('node:assert/strict');
const vm = require('node:vm');

class Element {
  constructor(tag, className = '', text = '') {
    Object.assign(this, { tag, className, text, children: [], dataset: {}, attributes: {} });
    this.classList = { add: value => { this.className += ` ${value}`; } };
  }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  append(...values) {
    for (const value of values) {
      const child = value instanceof Element ? value : new Element('text', '', String(value));
      child.parent = this; this.children.push(child);
    }
  }
  replaceWith(next) {
    const index = this.parent.children.indexOf(this);
    this.parent.children[index] = next; next.parent = this.parent;
  }
  setAttribute(key, value) { this.attributes[key] = String(value); }
  querySelector(selector) {
    const matches = child => selector[0] === '.' ? child.className.split(' ').includes(selector.slice(1)) : child.tag === selector;
    for (const child of this.children) {
      if (matches(child)) return child;
      const nested = child.querySelector(selector); if (nested) return nested;
    }
    return null;
  }
}

function checkMainDirectoryQuality(source, functionBinding) {
  const now = 1_800_000_000_000;
  const id = '11111111-1111-4111-8111-111111111111';
  let radioAllowed = true;
  let liveAllowed = true;
  const node = (tag, className, text) => new Element(tag, className, text);
  const context = vm.createContext({
    node, Date: { now: () => now },
    ACCESS_CAPABILITIES: { RADIO: 'radio', LIVE: 'live' },
    capabilityAllowed: capability => capability === 'radio' ? radioAllowed : liveAllowed,
    href: (view, parameters) => `${view}:${JSON.stringify(parameters)}`,
    anchor: (label, target, className) => { const link = node('a', className, label); link.target = target; return link; },
    uiPill: (label, tone) => node('span', `ui-pill-${tone}`, label),
    uiStatus: (label, tone) => node('span', `ui-status-${tone}`, label),
    remoteOriginIcon: () => node('svg'), frequency: value => (value / 1_000_000).toFixed(5),
    optionalNumber: value => value === null || value === undefined || value === '' ? null : Number(value),
    canonicalConfigurationId: value => /^[0-9a-f-]{36}$/.test(value || '') ? value : null,
    SIGNAL_OFFLINE_MILLISECONDS: 45_000, DECODE_HEALTHY_MINIMUM_PERCENT: 95,
    DECODE_DEGRADED_MINIMUM_PERCENT: 75
  });
  for (const [name, parameters] of Object.entries({ percentNumber: 'value', elapsedLabel: 'timestamp, now = Date.now()',
    signalChannelState: 'channel, now = Date.now()', radioDirectoryField: 'element, field',
    radioDirectoryReading: "label, field, value, unit = ''", radioDirectorySiteRow: 'row, conventional = false, quality = null',
    radioDirectoryLiveLink: 'children', radioDirectoryQualityOnly: 'currentQuality' })) {
    vm.runInContext(`function ${name}(${parameters}) ${functionBinding(source, name)}`, context);
  }
  const row = { configuration_id: id, name: 'North Simulcast', site_name: 'North',
    processing_state: 'RUNNING', display_frequency_source: 'active-control', display_frequency_hz: 851_012_500,
    frequencies_hz: [852_000_000] };
  const quality = { configuration_id: id, channel_kind: 'TRUNKED', radio_system_key: 'p25:00001:001',
    system_name: 'County', last_observed_ms: now - 2000, signal_dbfs: -48.2,
    average_signal_dbfs: -49.1, decode_health_pct: 99.3 };
  const render = (channel = row, sample = quality) => context.radioDirectorySiteRow(channel, false, sample);
  let result = render();
  assert.match(result.textContent, /North Simulcast.*Running.*Updated 2s ago/);
  assert.match(result.querySelector('.radio-directory-frequency').textContent, /851\.01250 MHz/);
  assert.match(result.textContent, /-48\.2 dBFS.*30s avg -49\.1.*99\.3%.*Healthy/);
  assert.equal(result.querySelector('meter').attributes.value, '99.3');
  assert.equal(result.querySelector('a').target, `channel:${JSON.stringify({ configuration_id: id })}`);
  for (const sourceKind of ['configured', 'quality', null]) {
    result = render({ ...row, display_frequency_source: sourceKind });
    assert.match(result.querySelector('.radio-directory-frequency').textContent, /Unavailable/,
      'Quality/configured frequencies must never stand in for confirmed active control.');
  }
  result = render({ ...row, remote_origin: { remote: true } });
  assert.match(result.textContent, /Remote.*Signal—Remote site.*99\.3%Healthy/);
  assert.doesNotMatch(result.textContent, /dBFS|30s avg/);
  assert.equal(result.querySelector('meter').hidden, false, 'Remote decode quality is still measurable.');
  for (const sample of [null, { ...quality, last_observed_ms: now - 45_001 },
    { ...quality, last_observed_ms: now + 10_000 }]) {
    result = render(row, sample);
    assert.equal(result.querySelector('meter').hidden, true);
    assert.doesNotMatch(result.textContent, /99\.3%|Healthy|-48\.2/);
  }
  result = render({ ...row, processing_state: 'STOPPED' });
  assert.match(result.textContent, /Stopped/);
  assert.match(result.querySelector('.radio-directory-frequency').textContent, /Unavailable/);
  assert.equal(result.querySelector('meter').hidden, true);
  const conventional = context.radioDirectorySiteRow(row, true, quality);
  assert.match(conventional.querySelector('.radio-directory-frequency').textContent, /852\.00000 MHz/);
  assert.equal(conventional.querySelector('meter'), null);
  radioAllowed = false;
  result = render({ ...quality, name: 'Read-only site' });
  assert.equal(result.querySelector('a'), null);
  assert.match(result.textContent, /Status unavailable/);
  assert.match(result.querySelector('.radio-directory-frequency').textContent, /Unavailable/);
  assert.match(result.textContent, /99\.3%Healthy/);
  assert.equal(context.radioDirectoryLiveLink([row]), null,
    'Live permission alone must not expose a directory shortcut without radio access.');
  const grouped = context.radioDirectoryQualityOnly([quality, { ...quality,
    configuration_id: '22222222-2222-4222-8222-222222222222' }, { ...quality, configuration_id: 'invalid' }]);
  assert.equal(grouped.systems.length, 1);
  assert.equal(grouped.systems[0].children.length, 2);
  assert.equal(grouped.systems[0].children[0].processing_state, undefined);
  assert.equal(grouped.channels.length, 0);
  radioAllowed = true; liveAllowed = false;
  assert.equal(context.radioDirectoryLiveLink([row]), null);
  liveAllowed = true;
  assert.match(context.radioDirectoryLiveLink([row]).target, /live:.*channel/);
  console.log('Grouped Main quality freshness, confirmed controls, remote data and access checks passed.');
}

module.exports = { checkMainDirectoryQuality };
