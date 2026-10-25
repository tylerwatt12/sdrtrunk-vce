'use strict';

const { expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./site-style-app.cjs');

const coach = (page, id) => page.locator(`.ui-setup-guide[data-guide-id="${id}"]`);
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

async function installTour(page, id, { theme = 'light', firstVisit = false } = {}) {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await installSiteStyleApplication(page, theme, { revision: 1, tables: [] }, { guides: firstVisit });
  await page.addInitScript(({ id, firstVisit }) => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    for (const version of [1, 2]) localStorage.setItem(`sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:${version}`, '1');
    if (!firstVisit) localStorage.setItem(`sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:${id}:3`, '1');
  }, { id, firstVisit });
  const effects = { writes: [], tourHistory: [], samples: [], audio: [], sockets: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/')) return;
    if (!['GET', 'HEAD'].includes(request.method()) && url.pathname !== '/api/v1/live/multiplex/control') {
      if (request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals')
        effects.tourHistory.push(request.postDataJSON());
      else effects.writes.push(`${request.method()} ${url.pathname}`);
    }
    if (/guide-map-|guide-p25-|guide-visualizer-|guide-network-|tour-map-|tour-p25-|p25:bee00:101|cafe2500|v1-[rg]-bee00-101/.test(decodeURIComponent(url.pathname + url.search + (request.postData() || ''))))
      effects.samples.push(`${request.method()} ${url.pathname}`);
    if (/\/audio(?:$|\/)/.test(url.pathname)) effects.audio.push(url.pathname);
  });
  page.on('websocket', socket => effects.sockets.push(socket.url()));
  return { fixture, effects };
}
async function advance(page, id, destination, button = 'Next') {
  await coach(page, id).getByRole('button', { name: button, exact: true }).click();
  if (destination) await expect(coach(page, id)).toHaveAttribute('data-guide-step', destination);
}
async function startTour(page, id, first) {
  await expect(coach(page, id)).toHaveAttribute('data-guide-step', 'overview');
  await expect(coach(page, id).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(0);
  await advance(page, id, first, 'Start tour');
}
function isolated(state, { allowExistingSockets = false } = {}) {
  expect(state.effects.writes).toEqual([]);
  for (const update of state.effects.tourHistory) {
    expect(Object.keys(update).sort()).toEqual(['dismissed', 'version']);
    expect(update.version).toBe(1);
    expect(update.dismissed).not.toBeNull();
    expect(typeof update.dismissed).toBe('object');
    expect(Array.isArray(update.dismissed)).toBe(false);
    expect(Object.keys(update.dismissed).length).toBeLessThanOrEqual(64);
    for (const [id, version] of Object.entries(update.dismissed)) {
      expect(id).toMatch(/^[a-z][a-z0-9_-]{0,63}$/);
      expect(Number.isInteger(version)).toBe(true);
      expect(version).toBeGreaterThanOrEqual(1);
      expect(version).toBeLessThanOrEqual(1_000_000);
      expect(state.fixture.tourDismissals.dismissed[id]).toBeGreaterThanOrEqual(version);
    }
  }
  expect(state.effects.samples).toEqual([]);
  expect(state.effects.audio).toEqual([]);
  if (!allowExistingSockets) expect(state.effects.sockets).toEqual([]);
  expect(state.fixture.pageErrors).toEqual([]);
  expect(state.fixture.unexpected).toEqual([]);
}
async function screenshot(page, folder, name) {
  if (!folder) return;
  fs.mkdirSync(folder, { recursive: true });
  await page.screenshot({ path: path.join(folder, name + '.png') });
}
async function focusVisible(page, guideId, names, { owners = [], allowFocusOverlap = false } = {}) {
  for (const name of names) await expect(page.locator(`[data-guide-target="${name}"]:visible`).first()).toBeVisible();
  await expect.poll(() => page.evaluate(({ guideId, names, owners, allowFocusOverlap }) => {
    const rect = element => element.getBoundingClientRect();
    const contains = (outer, inner) => outer.left <= inner.left + 1 && outer.top <= inner.top + 1 &&
      outer.right >= inner.right - 1 && outer.bottom >= inner.bottom - 1;
    const viewport = box => box.left >= -1 && box.top >= -1 && box.right <= innerWidth + 1 && box.bottom <= innerHeight + 1;
    const guide = document.querySelector(`.ui-setup-guide[data-guide-id="${guideId}"]`), box = rect(guide);
    const overlaps = other => Math.min(box.right, other.right) - Math.max(box.left, other.left) > 1 &&
      Math.min(box.bottom, other.bottom) - Math.max(box.top, other.top) > 1;
    const dimmer = document.querySelector('.ui-setup-guide-dimmer');
    const maskId = dimmer.getAttribute('mask').match(/#([^)]*)/)[1];
    const holes = [...document.getElementById(maskId).querySelectorAll('.ui-setup-guide-mask-hole')].map(hole => {
      const left = Number(hole.getAttribute('x')), top = Number(hole.getAttribute('y'));
      return { left, top, right: left + Number(hole.getAttribute('width')), bottom: top + Number(hole.getAttribute('height')) };
    });
    const outlines = [...document.querySelectorAll('.ui-setup-guide-highlight')];
    const targets = names.flatMap(name => [...document.querySelectorAll(`[data-guide-target="${name}"]`)]).filter(element => element.getClientRects().length);
    const badTargets = targets.filter(target => {
      const targetBox = rect(target);
      return !viewport(targetBox) || (!allowFocusOverlap && overlaps(targetBox)) || !outlines.some(outline =>
        outline.dataset.setupGuideTarget === target.id && contains(rect(outline), targetBox));
    });
    const badOwners = owners.flatMap(selector => [...document.querySelectorAll(selector)])
      .filter(owner => {
        const box = rect(owner);
        const visible = { left: Math.max(0, box.left), top: Math.max(0, box.top),
          right: Math.min(innerWidth, box.right), bottom: Math.min(innerHeight, box.bottom) };
        return owner.getClientRects().length && !holes.some(hole => contains(hole, visible));
      });
    const back = [...guide.querySelectorAll('button')].find(button => button.textContent.trim() === 'Back');
    const forward = [...guide.querySelectorAll('button')].find(button => /^(Next|Finish)/.test(button.textContent.trim()));
    const a = rect(back), b = rect(forward);
    return viewport(box) && !badTargets.length && !badOwners.length && Math.abs(a.top - b.top) <= 1 && a.right <= b.left ? null :
      { guide: box.toJSON(), targets: badTargets.map(target => ({ name: target.dataset.guideTarget, box: rect(target).toJSON() })),
        owners: badOwners.map(owner => ({ className: owner.className, box: rect(owner).toJSON() })) };
  }, { guideId, names, owners, allowFocusOverlap }), { message: 'Actual highlighted controls and their owning panel remain clear and recognizable' }).toBeNull();
}

module.exports = { coach, nonGuideStorage, installTour, advance, startTour, isolated, screenshot, focusVisible };
