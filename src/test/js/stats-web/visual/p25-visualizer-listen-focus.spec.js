'use strict';

const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication, systemKey, channelId, groupKey, radioKey, systemName } =
  require('./fixtures/site-style-app.cjs');

const voice = { key: 'county-voice', configuration_id: channelId, lcn: 2, frequency_hz: 851037500,
  protocol: 'P25', serving_system_key: systemKey, radio_system_key: systemKey, system_name: systemName,
  status: 'CALL', tags: ['VOICE'], role: 'TRAFFIC', activation_order: 2,
  source_id: 30914, source_identity_key: radioKey, source_identity_kind_code: 2, source_alias: 'Engine 4',
  target_id: 1201, target_identity_key: groupKey, target_kind: 'talkgroup', target_alias: 'Fire Dispatch' };

async function openVisualizer(page, { theme = 'light', direct = true, audio = true } = {}) {
  const fixture = await installSiteStyleApplication(page, theme);
  const state = { fixture, feeds: [] };
  await page.clock.setSystemTime(new Date('2026-10-01T12:00:00Z'));
  await page.addInitScript(() => {
    window.p25ListenAudio = { resumes: 0 };
    class Context {
      constructor() { this.state = 'suspended'; this.currentTime = 0; this.destination = {}; }
      resume() { window.p25ListenAudio.resumes++; this.state = 'running'; return Promise.resolve(); }
      suspend() { this.state = 'suspended'; return Promise.resolve(); }
      close() { this.state = 'closed'; return Promise.resolve(); }
      createGain() { return { gain: { value: 1 }, connect() {}, disconnect() {} }; }
      createAnalyser() { return { frequencyBinCount: 256, connect() {}, disconnect() {},
        getByteFrequencyData(values) { values.fill(0); } }; }
    }
    window.AudioContext = Context;
    window.webkitAudioContext = Context;
  });
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
    configured: true, authenticated: true, username: 'fixture-admin', tier: 'user', primary: false,
    csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100',
    capabilities: { 'web-access': true, dashboard: true, live: true, radio: true, credits: true,
      'call-audio': audio, 'direct-listen': direct, recordings: true, 'user-settings': true }
  } } }));
  await page.route('**/api/v1/calls/feed*', route => {
    state.feeds.push(new URL(route.request().url()).search);
    return route.fulfill({ json: { data: { cursor: '1', reset: false, calls: [] } } });
  });
  const filename = path.resolve(__dirname, '../../../../../stats-web/assets/features/network-visualizer/index.js');
  const marker = '  const close = () => {';
  const original = fs.readFileSync(filename, 'utf8');
  expect(original.split(marker)).toHaveLength(2);
  await page.route('**/assets/features/network-visualizer/index.js?**', route => route.fulfill({
    contentType: 'text/javascript', body: original.replace(marker,
      `  window.p25ListenSelectTalkgroup = enterTalkgroup;\n${marker}`)
  }));
  await page.goto('/app.html?view=network-visualizer');
  await expect(page.locator('.network-visualizer-canvas')).toHaveAttribute('data-renderer-state', 'ready');
  await expect(page.locator('.network-visualizer-status')).toHaveText('Live');
  await expect.poll(() => page.evaluate(key => window.p25ListenSelectTalkgroup?.(key),
    `${systemKey}:${groupKey}`)).toBe(true);
  await expect(page.locator('.network-visualizer-back')).toHaveText('← System');
  return state;
}

async function burst(page, count = 80) {
  await page.evaluate(async ({ voice, count, channelId }) => {
    let revision = window.p25ListenRevision || 1;
    for (let index = 0; index < count; index++) {
      window.fixtureSendLiveFrame(1, 'activity_batch', { base_revision: revision, revision: ++revision,
        changes: [{ table_id: channelId, operation: 'upsert', replace: false,
          rows: [{ ...voice, source_alias: `Engine 4 · update ${revision}` }] }] });
    }
    window.p25ListenRevision = revision;
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  }, { voice, count, channelId });
}

async function pressThroughUpdate(page, control) {
  await control.scrollIntoViewIfNeeded();
  await control.hover();
  const original = await control.elementHandle();
  const before = await control.boundingBox();
  await page.mouse.move(before.x + before.width / 2, before.y + before.height / 2);
  await page.mouse.down();
  const held = await control.boundingBox();
  await burst(page);
  expect(await original.evaluate(element => element.isConnected)).toBe(true);
  expect(await control.evaluate((element, retained) => element === retained, original)).toBe(true);
  await expect(control).toBeFocused();
  const after = await control.boundingBox();
  expect(Math.abs(after.x - held.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(after.y - held.y)).toBeLessThanOrEqual(1);
  await page.mouse.up();
  return original;
}

async function expectListenContrast(label) {
  const paint = await label.evaluate(button => {
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    const rgba = color => {
      context.clearRect(0, 0, 1, 1);
      context.fillStyle = color;
      context.fillRect(0, 0, 1, 1);
      return [...context.getImageData(0, 0, 1, 1).data];
    };
    const text = button.querySelector('[data-direct-listen-text]');
    const ancestors = [];
    for (let node = text; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      ancestors.push({ opacity: Number(style.opacity), filter: style.filter });
    }
    return { text: rgba(getComputedStyle(text).color),
      background: rgba(getComputedStyle(button).backgroundColor), ancestors };
  });
  expect(paint.background[3]).toBe(255);
  expect(paint.text[3]).toBe(255);
  for (const ancestor of paint.ancestors) expect(ancestor).toEqual({ opacity: 1, filter: 'none' });
  const luminance = rgba => rgba.slice(0, 3).map(value => {
    const channel = value / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  }).reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
  const foreground = luminance(paint.text), background = luminance(paint.background);
  expect((Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05))
    .toBeGreaterThanOrEqual(4.5);
}

for (const [theme, width] of [['light', 1280], ['dark', 390]]) {
  test(`P25 graph listening and system links keep their pointer gesture and focus through live updates in ${theme} ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const state = await openVisualizer(page, { theme });
    const scope = page.locator('.network-visualizer-scope-title');
    await expect(scope.locator('[data-direct-listen-action]')).toHaveCount(0);
    const label = page.locator('.network-visualizer-label[data-type="talkgroup"][data-direct-listen-target-key]')
      .filter({ hasText: 'Fire Dispatch' });
    await expect(page.getByRole('button', { name: 'Add Fire Dispatch to Direct Listen and play', exact: true })).toBeVisible();
    await expect(label.locator('[data-direct-listen-text]')).toHaveText('Fire Dispatch');
    await expect(label).toBeInViewport({ ratio: 1 });
    await expectListenContrast(label);
    if (process.env.P25_LISTEN_EVIDENCE) {
      fs.mkdirSync(process.env.P25_LISTEN_EVIDENCE, { recursive: true });
      await page.screenshot({ path: path.join(process.env.P25_LISTEN_EVIDENCE,
        `p25-listen-before-pointer-${theme}-${width}.png`) });
    }
    const retainedLabel = await pressThroughUpdate(page, label);
    await expect(label).toHaveAccessibleName('Remove Fire Dispatch from Direct Listen');
    await expect(label).toHaveAttribute('aria-pressed', 'true');
    await expectListenContrast(label);
    await expect.poll(() => state.feeds.length).toBeGreaterThan(0);
    expect(new URLSearchParams(state.feeds[0]).getAll('direct_target').map(value => JSON.parse(value))).toEqual([
      { kind: 'talkgroup', radio_system_key: systemKey, identity_key: groupKey }
    ]);
    expect(await page.evaluate(() => window.p25ListenAudio.resumes)).toBe(1);
    expect(await label.evaluate((element, retained) => element === retained, retainedLabel)).toBe(true);
    const evidence = process.env.P25_LISTEN_EVIDENCE;
    if (evidence) {
      fs.mkdirSync(evidence, { recursive: true });
      await page.screenshot({ path: path.join(evidence, `p25-listen-label-${theme}-${width}.png`) });
    }
    await pressThroughUpdate(page, label);
    await expect(label).toHaveAccessibleName('Add Fire Dispatch to Direct Listen and play');
    await expect(label).toHaveAttribute('aria-pressed', 'false');
    expect(await page.evaluate(() => window.p25ListenAudio.resumes)).toBe(1);
    const system = scope.getByRole('link', { name: systemName, exact: true });
    await pressThroughUpdate(page, system);
    await expect(page).toHaveURL(/view=radio-system/);
    expect(new URL(page.url()).searchParams.get('radio_system_key')).toBe(systemKey);
    expect(state.fixture.pageErrors).toEqual([]);
    expect(state.fixture.unexpected).toEqual([]);
  });
}

for (const [name, capabilities] of [['Direct Listen', { direct: false }], ['call audio', { audio: false }]]) {
  test(`P25 graph labels stay noninteractive without ${name} permission`, async ({ page }) => {
    const state = await openVisualizer(page, capabilities);
    const label = page.locator('.network-visualizer-label[data-type="talkgroup"]').filter({ hasText: 'Fire Dispatch' });
    await expect(label).toBeVisible();
    await expect(label).not.toHaveAttribute('role', 'button');
    expect(await label.evaluate(element => element.tagName)).toBe('SPAN');
    await expect(page.locator('.network-visualizer-layout [data-direct-listen-target-key]')).toHaveCount(0);
    await label.dispatchEvent('click');
    await label.dispatchEvent('keydown', { key: 'Enter' });
    await burst(page);
    expect(state.feeds).toEqual([]);
    expect(await page.evaluate(() => window.p25ListenAudio.resumes)).toBe(0);
    expect(state.fixture.pageErrors).toEqual([]);
  });
}
