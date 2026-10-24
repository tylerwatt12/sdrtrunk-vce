'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="tuners"]');
const graph = page => page.locator('[data-guide-diagram="tuner-coverage"]');
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:tuners:3';
const steps = ['modes', 'setup-button', 'enter-setup', 'receiving-window', 'center-frequency',
  'lock-center', 'gain', 'frequency-correction', 'frequency-limits-button', 'frequency-limits',
  'resume-button', 'resume-channels', 'disable-button', 'disable-tuner', 'disabled',
  'restart-setup-button', 'restart-channels', 'live'].map(id => ({ id,
  targets: id === 'disabled' ? ['tn-disabled-mode', 'tn-disabled-signal'] :
    id === 'live' ? ['tn-live-mode', 'tn-live-count'] : [`tn-${id}`] }));
const modalSteps = new Map([['enter-setup', 'Enter Setup'], ['resume-channels', 'Resume previous channels'],
  ['disable-tuner', 'Disable tuner'], ['restart-channels', 'Resume previous channels']]);
const evidence = process.env.TUNERS_GUIDE_EVIDENCE ||
  path.resolve(__dirname, '../../../../../build/playwright-results/tuners-guide-evidence');
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

async function openTuners(page, { theme = 'light', replay = true, firstVisit = false,
  denied = false, reducedMotion = true } = {}) {
  const requests = { writes: [], samples: [], audio: [], sockets: [], socketWrites: [], liveControls: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/') && url.pathname !== '/api/v1/live/multiplex/control' &&
        !['GET', 'HEAD'].includes(request.method())) {
      requests.writes.push(`${request.method()} ${url.pathname}`);
    }
    if (url.pathname === '/api/v1/live/multiplex/control') requests.liveControls.push(request.postDataJSON());
    if (url.pathname.startsWith('/api/v1/') &&
        /guide-(?:airspy|rtl|county|city)|guide-tuner|sample-tuner|tour-tuner/.test(url.pathname + (request.postData() || ''))) {
      requests.samples.push(`${request.method()} ${url.pathname}`);
    }
    if (/\/api\/v1\/.*\/audio(?:\?|$)/.test(url.pathname)) requests.audio.push(url.pathname);
  });
  page.on('websocket', socket => {
    requests.sockets.push(socket.url());
    socket.on('framesent', ({ payload }) => requests.socketWrites.push(payload));
  });
  await page.emulateMedia({ reducedMotion: reducedMotion ? 'reduce' : 'no-preference' });
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: firstVisit });
  if (firstVisit) await page.addInitScript(() => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:tuners:2', '1');
  });
  if (denied) {
    const file = path.resolve(__dirname, '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java');
    const capabilities = Object.fromEntries([...fs.readFileSync(file, 'utf8')
      .matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)].map(([, id]) => [id, id !== 'admin-tuners']));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  await page.goto(`/app.html?view=tuners${replay ? '&guide=tuners' : ''}`);
  if (!denied) await expect(page.getByRole('heading', { name: 'Tuners', exact: true })).toBeVisible();
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  return { fixture, requests };
}

async function expectOverview(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(graph(page)).toBeVisible();
  await expect(graph(page).locator('.ui-setup-guide-coverage-identity strong')).toHaveText(['RTL-SDR 1', 'RTL-SDR 2']);
  await expect(graph(page).locator('.coverage-control')).toHaveCount(1);
  await expect(graph(page)).not.toContainText('Airspy');
  await expect(graph(page).locator('button, input, select, [data-coverage-time]')).toHaveCount(0);
  await expect(graph(page).locator('.ui-setup-guide-coverage-recent, .ui-setup-guide-coverage-note')).toHaveCount(0);
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
}

async function startTour(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
}

async function next(page, id) {
  await guide(page).getByRole('button', { name: id === steps.at(-1).id ? 'Finish' : 'Next', exact: true }).click();
}

async function goToStep(page, id) {
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    if (step.id === id) return;
    await next(page, step.id);
  }
  throw new Error(`Unknown Tuners tour step: ${id}`);
}

async function expectWithinViewport(locator, viewport) {
  await expect.poll(async () => {
    const box = await locator.boundingBox();
    return box && box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width + 1 &&
      box.y + box.height <= viewport.height + 1 ? null : box;
  }, { message: `${await locator.getAttribute('data-guide-target') || 'Guide control'} fits the viewport` }).toBeNull();
}

async function expectSpotlight(page, id) {
  const targets = page.locator(`[data-guide-target="${id}"]:visible`);
  await expect(targets.first()).toBeVisible();
  for (const target of await targets.all()) {
    await expectWithinViewport(target, page.viewportSize());
    await expect.poll(() => target.evaluate(element => {
      if (element.closest('.read-only-modal')) return null;
      const rect = element.getBoundingClientRect();
      const header = document.querySelector('.app-header')?.getBoundingClientRect();
      return !header || rect.top >= header.bottom - 1 ? null :
        { targetTop: rect.top, headerBottom: header.bottom };
    }), { message: `${id} remains below the sticky application header` }).toBeNull();
    await expect.poll(() => target.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const highlights = [...document.querySelectorAll('.ui-setup-guide-highlight')].map(highlight => {
        const box = highlight.getBoundingClientRect();
        return { left: box.left, top: box.top, right: box.right, bottom: box.bottom };
      });
      return highlights.some(box => box.left <= rect.left + 1 && box.top <= rect.top + 1 &&
        box.right >= rect.right - 1 && box.bottom >= rect.bottom - 1) ? null :
        { target: { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom }, highlights };
    }), { message: `${id} highlights its actual production control` }).toBeNull();
    await expect.poll(() => target.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      return Math.min(rect.right, card.right) - Math.max(rect.left, card.left) <= 1 ||
        Math.min(rect.bottom, card.bottom) - Math.max(rect.top, card.top) <= 1 ? null :
        { target: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
          guide: { x: card.x, y: card.y, width: card.width, height: card.height } };
    }), { message: `${id} stays clear of the guide card` }).toBeNull();
  }
}

function expectNoEffects(fixture, requests) {
  expect(requests.writes).toEqual([]);
  expect(requests.samples).toEqual([]);
  expect(requests.audio).toEqual([]);
  expect(requests.socketWrites).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
}

async function screenshot(page, name) {
  await page.screenshot({ path: path.join(evidence, 'screens', `${name}.png`) });
}

async function surfaceSignature(locator) {
  await expect(locator).toBeVisible();
  return locator.evaluate(root => {
    const style = getComputedStyle(root);
    return {
      classes: [...root.classList].filter(name => name !== 'ui-setup-guide-layer').sort(),
      title: root.querySelector('.modal-header h2')?.textContent,
      text: root.matches('.read-only-modal') ? root.textContent : undefined,
      structure: [...root.querySelectorAll('section, form, fieldset, legend, details, summary, .tuners-mode, .tuners-readouts, .tuners-controls, .tuners-setting-group-body, .ui-modal-footer')]
        .map(element => [element.tagName, element.className, element.hidden]),
      fields: [...root.querySelectorAll('input, select, textarea')]
        // The populated sample FFT adjusts its own display range; hardware fields
        // and the ordinary spectrum structure remain the parity contract.
        .filter(element => !element.closest('.tuners-spectrum')).map(element => [element.tagName,
        element.getAttribute('name'), element.getAttribute('type'), element.className, element.value,
        element.type === 'checkbox' || element.type === 'radio' ? element.checked : null, element.disabled]),
      actions: [...root.querySelectorAll('.ui-modal-footer button')].map(element => [element.textContent, element.hidden, element.disabled]),
      paint: { font: style.fontFamily, background: style.backgroundColor, color: style.color,
        radius: style.borderRadius, shadow: style.boxShadow }
    };
  });
}

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 }, { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Tuners shows every actual opening control, receiver setting, and return in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const { fixture, requests } = await openTuners(page, { theme });
    await expectOverview(page);
    await expectWithinViewport(guide(page), { width, height });
    await expectWithinViewport(graph(page), { width, height });
    await expectWithinViewport(guide(page).getByRole('button', { name: 'Start tour', exact: true }), { width, height });
    await screenshot(page, `tuners-overview-${theme}`);
    const playerBefore = await page.locator('#audio-dock').evaluate(element => ({
      source: element.dataset.source, state: element.dataset.state, collapsed: element.dataset.superCollapsed }));
    await startTour(page);
    const routeBefore = page.url();
    for (const [index, step] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      await expect(guide(page)).toContainText(`Step ${index + 1} of ${steps.length}`);
      for (const id of step.targets) await expectSpotlight(page, id);
      if (theme === 'light' && step.id === 'modes') {
        const target = page.locator('[data-guide-target="tn-modes"]');
        const originalBounds = await target.boundingBox();
        const originalPadding = await page.locator('#content').evaluate(element => ({
          value: element.style.getPropertyValue('padding-top'),
          priority: element.style.getPropertyPriority('padding-top'),
          computed: Number.parseFloat(getComputedStyle(element).paddingTop)
        }));
        try {
          await page.locator('#content').evaluate((element, padding) => {
            element.style.setProperty('padding-top', `${padding.computed + 24}px`);
          }, originalPadding);
          await expect.poll(async () => (await target.boundingBox()).y - originalBounds.y,
            { message: 'Page reflow moves the actual mode control without resizing it' }).toBeGreaterThan(23);
          const movedBounds = await target.boundingBox();
          expect(movedBounds.width).toBeCloseTo(originalBounds.width, 2);
          expect(movedBounds.height).toBeCloseTo(originalBounds.height, 2);
          await expectSpotlight(page, 'tn-modes');
        } finally {
          await page.locator('#content').evaluate((element, padding) => {
            if (padding.value) element.style.setProperty('padding-top', padding.value, padding.priority);
            else element.style.removeProperty('padding-top');
          }, originalPadding);
        }
        await expect.poll(async () => Math.abs((await target.boundingBox()).y - originalBounds.y)).toBeLessThan(1);
        await expectSpotlight(page, 'tn-modes');
      }
      await expectWithinViewport(guide(page), { width, height });
      await expectWithinViewport(guide(page).getByRole('button', { name: 'Close guide', exact: true }), { width, height });
      await expectWithinViewport(guide(page).getByRole('button', {
        name: index === steps.length - 1 ? 'Finish' : 'Next', exact: true }), { width, height });
      await expect(graph(page)).toHaveCount(0);
      await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
      if (modalSteps.has(step.id)) {
        const dialog = page.getByRole('dialog', { name: modalSteps.get(step.id), exact: true });
        await expect(dialog).toBeVisible();
        await expect(page.locator('.tuners-workspace')).toBeVisible();
        await expect(dialog.getByRole('button', { name: step.id.includes('channels') ? 'Resume channels' : modalSteps.get(step.id), exact: true })).toBeVisible();
      } else await expect(page.locator('.read-only-modal:not(.ui-setup-guide)')).toHaveCount(0);
      if (step.id === 'frequency-limits-button') await expect(page.locator('.tuners-common-details')).not.toHaveAttribute('open');
      if (step.id === 'frequency-limits') await expect(page.locator('.tuners-common-details')).toHaveAttribute('open');
      if (step.id === 'center-frequency') {
        await expect(guide(page)).toContainText('Click the upper half of a digit to increase it, or the lower half to decrease it.');
        await expect(guide(page)).toContainText('To type a frequency, hover over the first digit you want to replace, enter the new digits, and press Enter.');
        await expect(page.locator('.tuners-center-frequency')).toHaveAttribute('tabindex', '0');
      }
      if (['receiving-window', 'center-frequency', 'lock-center', 'gain', 'frequency-correction', 'frequency-limits'].includes(step.id)) {
        await expect(page.locator('[data-mode="setup"]')).toHaveAttribute('aria-pressed', 'true');
        await expect(page.locator('select[data-tuner-setting="sample_rate"]')).toBeEnabled();
        await expect(page.locator('.tuners-spectrum canvas')).toHaveCount(2);
        await expect(page.locator('.tuners-recovery')).toContainText('2 channels stopped');
      }
      if (['modes', 'receiving-window', 'live'].includes(step.id)) {
        const signal = page.locator('.tuners-spectrum');
        await expect(signal.locator('.badge.state-current')).toHaveText('Live');
        await expect(signal.locator('.channel-diagnostic-overlay:visible')).toHaveCount(0);
        await expect.poll(() => signal.locator('canvas').evaluateAll(canvases => {
          const counts = canvases.map(canvas => {
          const rgba = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
          const colors = new Set();
          for (let index = 0; index < rgba.length; index += 4) colors.add(rgba.slice(index, index + 4).join(','));
          return colors.size;
          });
          return counts.length === 2 && counts[0] > 50 && counts[1] > 15;
        }), { message: 'Actual FFT and waterfall paint the sample diagnostic frames' }).toBe(true);
      }
      if (step.id === 'disabled') {
        await expect(page.locator('[data-mode="disabled"]')).toHaveAttribute('aria-pressed', 'true');
        await expect(page.locator('.tuners-channel-readout .tuners-readout-value')).toHaveText('0');
        await expect(page.locator('.tuners-spectrum .badge')).toHaveText('Idle');
      }
      if (step.id === 'live') {
        await expect(page.locator('[data-mode="live"]')).toHaveAttribute('aria-pressed', 'true');
        await expect(page.locator('.tuners-channel-readout .tuners-readout-value')).toHaveText('2');
        await expect(page.locator('.tuners-recovery')).toBeHidden();
      }
      await screenshot(page, `tuners-${step.id}-${theme}`);
      await next(page, step.id);
    }
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .read-only-modal')).toHaveCount(0);
    await expect(page).toHaveURL(routeBefore);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-source', playerBefore.source);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-state', playerBefore.state);
    await expect(page.locator('#audio-dock')).toHaveAttribute('data-super-collapsed', playerBefore.collapsed);
    expectNoEffects(fixture, requests);
  });
}

test('Tuners uses the ordinary renderer and its Enter Setup, Resume channels, and Disable tuner dialogs', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const { fixture } = await openTuners(page, { replay: false });
  const file = path.resolve(__dirname, '../../../../../stats-web/assets/guides/tuners-presentation.js');
  const { createTunersGuideData } = await import(pathToFileURL(file).href);
  const data = createTunersGuideData();
  const respond = async route => {
    const request = route.request(), url = new URL(request.url());
    const response = await data.requestJson(url.pathname + url.search, { method: request.method(),
      body: request.postData() ? request.postDataJSON() : undefined });
    await route.fulfill({ json: { data: response } });
  };
  await page.route('**/api/v1/admin/tuners**', respond);
  await page.route('**/api/v1/diagnostics/tuners', respond);
  await page.reload();
  await expect(page.locator('.tuners-list-item')).toHaveCount(2);
  const originals = { modes: await surfaceSignature(page.locator('.tuners-workspace')) };
  await page.locator('[data-mode="setup"]').click();
  originals['enter-setup'] = await surfaceSignature(page.getByRole('dialog', { name: 'Enter Setup', exact: true }));
  await page.getByRole('dialog', { name: 'Enter Setup', exact: true }).getByRole('button', { name: 'Enter Setup', exact: true }).click();
  await expect(page.locator('[data-mode="setup"]')).toHaveAttribute('aria-pressed', 'true');
  originals['receiving-window'] = await surfaceSignature(page.locator('.tuners-workspace'));
  await page.getByRole('button', { name: 'Resume & go Live', exact: true }).click();
  originals['resume-channels'] = await surfaceSignature(page.getByRole('dialog', { name: 'Resume previous channels', exact: true }));
  await page.getByRole('dialog', { name: 'Resume previous channels', exact: true }).getByRole('button', { name: 'Resume channels', exact: true }).click();
  await expect(page.locator('[data-mode="live"]')).toHaveAttribute('aria-pressed', 'true');
  await page.locator('[data-mode="disabled"]').click();
  originals['disable-tuner'] = await surfaceSignature(page.getByRole('dialog', { name: 'Disable tuner', exact: true }));
  await page.getByRole('dialog', { name: 'Disable tuner', exact: true }).getByRole('button', { name: 'Disable tuner', exact: true }).click();
  await expect(page.locator('[data-mode="disabled"]')).toHaveAttribute('aria-pressed', 'true');
  originals.disabled = await surfaceSignature(page.locator('.tuners-workspace'));
  await page.locator('[data-mode="setup"]').click();
  originals['restart-channels'] = await surfaceSignature(page.getByRole('dialog', { name: 'Resume previous channels', exact: true }));
  await page.getByRole('dialog', { name: 'Resume previous channels', exact: true }).getByRole('button', { name: 'Resume channels', exact: true }).click();
  await expect(page.locator('[data-mode="live"]')).toHaveAttribute('aria-pressed', 'true');
  await page.locator('#page-guide-button').click();
  await startTour(page);
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    if (originals[step.id]) {
      const surface = modalSteps.has(step.id) ? page.getByRole('dialog', { name: modalSteps.get(step.id), exact: true }) : page.locator('.tuners-workspace');
      expect(await surfaceSignature(surface), `${step.id} matches the ordinary interface`).toEqual(originals[step.id]);
    }
    await next(page, step.id);
  }
  expect(fixture.pageErrors).toEqual([]);
});

test('all Tuners sample actions protect receiver modes, settings, rescan, recordings, preferences, and audio', async ({ page }) => {
  const { fixture, requests } = await openTuners(page);
  await startTour(page);
  const routeBefore = page.url(), storageBefore = await nonGuideStorage(page);
  const socketsBefore = [...requests.sockets];
  let exercised = 0;
  for (const step of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample], .read-only-modal:not(.ui-setup-guide)')];
      const controls = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, a, input, select, form, summary, canvas')]))];
      for (const control of controls) {
        control.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        control.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }));
        if (control.tagName === 'FORM') control.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
      return controls.length;
    });
    await expect(page).toHaveURL(routeBefore);
    await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
    await next(page, step.id);
  }
  expect(exercised).toBeGreaterThan(100);
  expect(requests.sockets).toEqual(socketsBefore);
  expect(await nonGuideStorage(page)).toEqual(storageBefore);
  expectNoEffects(fixture, requests);
});

for (const { stop, dismiss } of [
  { stop: 'enter-setup', dismiss: 'Close guide' },
  { stop: 'frequency-correction', dismiss: 'Escape' },
  { stop: 'frequency-limits', dismiss: 'shade' },
  { stop: 'resume-channels', dismiss: 'Escape' },
  { stop: 'disable-tuner', dismiss: 'Close guide' },
  { stop: 'restart-channels', dismiss: 'shade' }
]) {
  test(`Tuners restores the original nodes, setting draft, scroll, and player after ${dismiss} from ${stop}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const { fixture, requests } = await openTuners(page, { replay: false });
    const ppm = page.locator('[data-tuner-setting="frequency_correction_ppm"]');
    await ppm.fill('1.23');
    await expect(page.locator('[data-setting-id="frequency_correction_ppm"]')).toContainText('Save');
    await page.locator('#content').evaluate(element => {
      window.tunersGuideOriginal = { content: element, nodes: [...element.childNodes],
        workspace: element.querySelector('.tuners-workspace') };
    });
    const storageBefore = await nonGuideStorage(page), routeBefore = page.url(), titleBefore = await page.title();
    const scrollBefore = await page.evaluate(() => ({ window: [scrollX, scrollY],
      content: [document.getElementById('content').scrollTop, document.getElementById('content').scrollLeft] }));
    const player = page.locator('#audio-dock');
    const playerBefore = await player.evaluate(element => ({ source: element.dataset.source,
      state: element.dataset.state, collapsed: element.dataset.superCollapsed }));
    const helpBounds = await page.locator('#page-guide-button').boundingBox();
    await page.mouse.click(helpBounds.x + helpBounds.width / 2, helpBounds.y + helpBounds.height / 2);
    await startTour(page);
    await goToStep(page, stop);
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await guide(page).getByRole('button', { name: 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else if (dismiss === 'shade') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .read-only-modal')).toHaveCount(0);
    expect(await page.locator('#content').evaluate(element => {
      const original = window.tunersGuideOriginal;
      return element === original.content && element.querySelector('.tuners-workspace') === original.workspace &&
        original.nodes.every((node, index) => element.childNodes[index] === node);
    })).toBe(true);
    await expect(ppm).toHaveValue('1.23');
    await expect(page.locator('[data-setting-id="frequency_correction_ppm"]')).toContainText('Save');
    await expect(page).toHaveURL(routeBefore);
    await expect(page).toHaveTitle(titleBefore);
    expect(await nonGuideStorage(page)).toEqual(storageBefore);
    expect(await page.evaluate(() => ({ window: [scrollX, scrollY],
      content: [document.getElementById('content').scrollTop, document.getElementById('content').scrollLeft] }))).toEqual(scrollBefore);
    await expect(player).toHaveAttribute('data-source', playerBefore.source);
    await expect(player).toHaveAttribute('data-state', playerBefore.state);
    await expect(player).toHaveAttribute('data-super-collapsed', playerBefore.collapsed);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    expectNoEffects(fixture, requests);
  });
}

test('Tuners returns an expanded Recordings player after all sample receiver modes', async ({ page }) => {
  const { fixture, requests } = await openTuners(page, { replay: false });
  const player = page.locator('#audio-dock');
  await player.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
  await expect(page).toHaveURL(/view=recordings/);
  await player.getByRole('button', { name: 'Expand player', exact: true }).first().click();
  await expect(player).toHaveAttribute('data-state', 'full');
  await page.locator('.primary-nav [data-view="tuners"]').evaluate(link => link.click());
  await expect(page.locator('.tuners-workspace')).toBeVisible();
  await page.locator('#page-guide-button').click();
  await startTour(page);
  await goToStep(page, 'restart-channels');
  await page.keyboard.press('Escape');
  await expect(player).toHaveAttribute('data-source', 'recordings');
  await expect(player).toHaveAttribute('data-state', 'full');
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .read-only-modal')).toHaveCount(0);
  expectNoEffects(fixture, requests);
});

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 }, { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Tuners keeps one control channel on its locked RTL-SDR while the second hops often in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    await page.clock.install();
    const { fixture, requests } = await openTuners(page, { theme });
    await expectOverview(page);
    await expectWithinViewport(guide(page), { width, height });
    await expectWithinViewport(graph(page), { width, height });
    const fixedCenter = await graph(page).getAttribute('data-rtl-center');
    const controlBefore = await graph(page).locator('.coverage-control').evaluate(element => ({
      x: Number(element.getAttribute('x1')), width: Number.parseFloat(getComputedStyle(element).strokeWidth)
    }));
    expect(controlBefore.width).toBe(8);
    const positions = [], geometry = [];
    for (let index = 0; index < 120; index += 1) {
      await page.clock.fastForward(500);
      positions.push(Number(await graph(page).getAttribute('data-secondary-center')));
      await expect(graph(page)).toHaveAttribute('data-rtl-center', fixedCenter);
      if (index === 7) await expect(graph(page).locator('.ui-setup-guide-coverage-identity').last()).toContainText('Unlocked');
      if ([7, 19, 39].includes(index)) {
        const measure = await graph(page).evaluate(root => {
          const svg = root.querySelector('svg'), control = root.querySelector('.coverage-control');
          const names = [...svg.querySelectorAll('text')].filter(element => /^RTL-SDR [12]$/.test(element.textContent));
          const bounds = names.map(element => ({ text: element.textContent, x: element.getBoundingClientRect().x,
            right: element.getBoundingClientRect().right, y: element.getBoundingClientRect().y,
            bottom: element.getBoundingClientRect().bottom }));
          const chart = svg.getBoundingClientRect();
          return { bounds, chart: { x: chart.x, right: chart.right },
            controlX: Number(control.getAttribute('x1')), primaryCenter: Number(names[0].getAttribute('x')),
            band: root.querySelector('.coverage-band-secondary').getAttribute('d') };
        });
        expect(measure.bounds).toHaveLength(2);
        expect(Math.abs(measure.controlX - measure.primaryCenter), 'The control channel stays visibly off center').toBeGreaterThan(15);
        for (const label of measure.bounds) {
          expect(label.x, `${label.text} fits inside the graph`).toBeGreaterThanOrEqual(measure.chart.x);
          expect(label.right, `${label.text} fits inside the graph`).toBeLessThanOrEqual(measure.chart.right);
        }
        expect(Math.min(measure.bounds[0].right, measure.bounds[1].right) - Math.max(measure.bounds[0].x, measure.bounds[1].x),
          'The two tuner captions remain separate').toBeLessThanOrEqual(0);
        expect((measure.band.match(/L/g) || []).length, 'The history shows several discrete tuner positions').toBeGreaterThan(6);
        geometry.push(measure);
        await screenshot(page, `tuners-two-rtl-hopping-${index}-${theme}`);
      }
    }
    const jumps = positions.slice(1).filter((position, index) => position !== positions[index]).length;
    expect(jumps, 'The second RTL-SDR hops frequently throughout the one-minute illustration').toBeGreaterThanOrEqual(30);
    expect(new Set(positions).size, 'The second RTL-SDR visits several parts of the band').toBeGreaterThanOrEqual(4);
    await expect(graph(page).locator('.coverage-control')).toHaveAttribute('x1', String(controlBefore.x));
    await expect(graph(page).locator('.ui-setup-guide-coverage-identity').first()).toContainText('Locked');
    fs.mkdirSync(evidence, { recursive: true });
    fs.writeFileSync(path.join(evidence, `two-rtl-measurements-${theme}.json`), JSON.stringify({ jumps,
      positions: [...new Set(positions)], control: controlBefore, geometry }, null, 2));
    await page.keyboard.press('Escape');
    expectNoEffects(fixture, requests);
  });
}

test('the Tuners overview graph loops after a minute, preserves reception history, and releases animation on Start and Back', async ({ page }) => {
  await page.clock.install();
  const { fixture, requests } = await openTuners(page, { reducedMotion: false });
  await expectOverview(page);
  await page.locator('#content').evaluate(element => { window.tunersOverviewOriginal = [...element.childNodes]; });
  const storageBefore = await nonGuideStorage(page);
  await graph(page).evaluate(element => { window.tunersOriginalGraph = element; });
  await expect(graph(page)).toHaveAttribute('data-playing', 'true');
  await page.clock.runFor(3400);
  await expect(graph(page).locator('[data-call="regional-missed"]')).toHaveAttribute('data-received', 'false');
  await expect(graph(page).locator('[data-call="regional-later"]')).toHaveAttribute('data-received', 'true');
  expect(await graph(page).locator('[data-call]').count(), 'The receiving history contains several calls').toBeGreaterThanOrEqual(5);
  await expect(graph(page).locator('svg')).toContainText('24s');
  await screenshot(page, 'tuners-overview-after-retune-light');
  const traceBefore = Number(await graph(page).locator('[data-call="regional-later"]').getAttribute('y2'));
  await page.clock.runFor(500);
  const traceAfter = Number(await graph(page).locator('[data-call="regional-later"]').getAttribute('y2'));
  expect(traceAfter - traceBefore, 'The 24-second history scrolls at six simulated seconds per second').toBeGreaterThan(14);
  expect(traceAfter - traceBefore).toBeLessThan(19);
  const beforeLoop = Number(await graph(page).getAttribute('data-time'));
  await page.clock.fastForward(61000);
  await page.clock.runFor(80);
  await expect(graph(page)).toHaveAttribute('data-playing', 'true');
  await expect(graph(page)).toHaveAttribute('data-cycle', '1');
  const afterLoop = Number(await graph(page).getAttribute('data-time'));
  expect(afterLoop).toBeGreaterThanOrEqual(0);
  expect(afterLoop).toBeLessThan(360);
  expect(Math.abs(afterLoop - (beforeLoop + 61 * 6 - 360))).toBeLessThan(1);
  expect(await graph(page).locator('[data-call]').count()).toBeGreaterThanOrEqual(5);
  await screenshot(page, 'tuners-overview-after-one-minute-light');
  expect(await page.locator('#content').evaluate(element => window.tunersOverviewOriginal
    .every((node, index) => element.childNodes[index] === node))).toBe(true);
  await startTour(page);
  expect(await page.evaluate(() => ({ disposed: window.tunersOriginalGraph.dataset.disposed,
    playing: window.tunersOriginalGraph.dataset.playing }))).toEqual({ disposed: 'true', playing: 'false' });
  await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
  await expectOverview(page);
  await graph(page).evaluate(element => { window.tunersSecondGraph = element; });
  expect(await page.evaluate(() => window.tunersSecondGraph !== window.tunersOriginalGraph)).toBe(true);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(() => window.tunersSecondGraph.dataset.disposed)).toBe('true');
  const time = await page.evaluate(() => window.tunersSecondGraph.dataset.time);
  await page.clock.runFor(1000);
  expect(await page.evaluate(() => window.tunersSecondGraph.dataset.time)).toBe(time);
  expect(await nonGuideStorage(page)).toEqual(storageBefore);
  expectNoEffects(fixture, requests);
});

for (const dismiss of ['Close guide', 'Escape', 'shade', 'navigation']) {
  test(`Tuners overview cancels its graph after ${dismiss}`, async ({ page }) => {
    const { fixture, requests } = await openTuners(page, { reducedMotion: false });
    await expectOverview(page);
    await graph(page).evaluate(element => { window.tunersDismissedGraph = element; });
    if (dismiss === 'Close guide') await guide(page).getByRole('button', { name: dismiss, exact: true }).click();
    else if (dismiss === 'Escape') {
      await guide(page).getByRole('button', { name: 'Start tour', exact: true }).focus();
      await page.keyboard.press('Escape');
    } else if (dismiss === 'shade') await page.mouse.click(8, 500);
    else await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
    await expect(guide(page)).toHaveCount(0);
    await expect(graph(page)).toHaveCount(0);
    expect(await page.evaluate(() => ({ disposed: window.tunersDismissedGraph.dataset.disposed,
      playing: window.tunersDismissedGraph.dataset.playing }))).toEqual({ disposed: 'true', playing: 'false' });
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
    expectNoEffects(fixture, requests);
  });
}

test('Tuners automatic graph starts under reduced motion and pauses and resumes with page visibility', async ({ page }) => {
  await page.clock.install();
  const { fixture, requests } = await openTuners(page);
  await expectOverview(page);
  await expect(graph(page)).toHaveAttribute('data-playing', 'true');
  const initialTime = Number(await graph(page).getAttribute('data-time'));
  await page.clock.runFor(1000);
  expect(Number(await graph(page).getAttribute('data-time')) - initialTime).toBeGreaterThan(4);
  const traceStyles = await graph(page).locator('.coverage-control, .coverage-call').evaluateAll(elements =>
    elements.map(element => ({ classes: element.getAttribute('class'), width: Number.parseFloat(getComputedStyle(element).strokeWidth) })));
  expect(traceStyles.some(trace => trace.classes.includes('coverage-control'))).toBe(true);
  expect(traceStyles.some(trace => trace.classes.includes('conventional'))).toBe(true);
  expect(traceStyles.some(trace => trace.classes.includes('voice'))).toBe(true);
  for (const trace of traceStyles) expect(trace.width, trace.classes).toBe(8);
  const legendWidths = await graph(page).locator('.ui-setup-guide-coverage-mark').evaluateAll(elements =>
    Object.fromEntries(elements.map(element => [element.classList.contains('control') ? 'control' :
      element.classList.contains('received') ? 'received' : element.classList.contains('missed') ? 'missed' : 'edge',
    Number.parseFloat(getComputedStyle(element).width)])));
  expect(legendWidths.control).toBe(legendWidths.received);
  expect(legendWidths.control).toBe(legendWidths.missed);
  expect(legendWidths.control).toBe(8);
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await expect(graph(page)).toHaveAttribute('data-playing', 'true');
  const preferenceTime = Number(await graph(page).getAttribute('data-time'));
  await page.clock.runFor(500);
  expect(Number(await graph(page).getAttribute('data-time')) - preferenceTime).toBeGreaterThan(2);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(graph(page)).toHaveAttribute('data-playing', 'true');
  const reducedTime = Number(await graph(page).getAttribute('data-time'));
  await page.clock.runFor(1000);
  const visibleTime = Number(await graph(page).getAttribute('data-time'));
  expect(visibleTime - reducedTime).toBeGreaterThan(4);
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(graph(page)).toHaveAttribute('data-playing', 'false');
  const hiddenTime = Number(await graph(page).getAttribute('data-time'));
  await page.clock.runFor(5000);
  expect(Number(await graph(page).getAttribute('data-time'))).toBe(hiddenTime);
  await page.evaluate(() => {
    delete document.hidden;
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(graph(page)).toHaveAttribute('data-playing', 'true');
  await page.clock.runFor(1000);
  const resumedTime = Number(await graph(page).getAttribute('data-time'));
  expect(resumedTime - hiddenTime, 'Hidden time does not jump the animation forward').toBeGreaterThan(4);
  expect(resumedTime - hiddenTime).toBeLessThan(8);
  await graph(page).evaluate(element => { window.tunersAutoplayGraph = element; });
  await page.keyboard.press('Escape');
  expect(await page.evaluate(() => ({ disposed: window.tunersAutoplayGraph.dataset.disposed,
    playing: window.tunersAutoplayGraph.dataset.playing }))).toEqual({ disposed: 'true', playing: 'false' });
  const closedTime = await page.evaluate(() => window.tunersAutoplayGraph.dataset.time);
  await page.clock.runFor(1000);
  expect(await page.evaluate(() => window.tunersAutoplayGraph.dataset.time)).toBe(closedTime);
  expectNoEffects(fixture, requests);
});

test('Tuners first desktop visit replaces placeholder memory and Help and named URL replay the tour', async ({ page }) => {
  const { fixture, requests } = await openTuners(page, { firstVisit: true, replay: false });
  await expectOverview(page);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await startTour(page);
  await page.keyboard.press('Escape');
  await page.goto('/app.html?view=tuners&guide=tuners');
  await expectOverview(page);
  await page.keyboard.press('Escape');
  expectNoEffects(fixture, requests);
});

test('Tuners navigation releases its sample receiver, spectrum, and modal before another route', async ({ page }) => {
  const { fixture, requests } = await openTuners(page);
  await startTour(page);
  await goToStep(page, 'disable-tuner');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .ui-setup-guide, .read-only-modal')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="tuners"]').evaluate(link => link.click());
  await expect(page.locator('.tuners-workspace')).toBeVisible();
  await expect(page.locator('.tuners-list-item')).toHaveCount(1);
  expectNoEffects(fixture, requests);
});

test('phone visits keep the desktop Tuners overview, graph, and sample receiver closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { fixture, requests } = await openTuners(page, { firstVisit: true });
  await expect(page.locator('.tuners-workspace')).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await expect(graph(page)).toHaveCount(0);
  await expect(page.locator('#page-guide-button')).toBeHidden();
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectNoEffects(fixture, requests);
});

test('Tuners access denial cannot open its guide, graph, sample data, or tuner APIs', async ({ page }) => {
  const { fixture, requests } = await openTuners(page, { denied: true, firstVisit: true });
  await expect(page.locator('.tuners-workspace, .ui-setup-guide, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await expect(graph(page)).toHaveCount(0);
  expect(fixture.requests.filter(value => value.startsWith('/api/v1/admin/tuners'))).toEqual([]);
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectNoEffects(fixture, requests);
});

test('dismissal during Tuners sample preparation cannot reopen a receiver confirmation', async ({ page }) => {
  const { fixture, requests } = await openTuners(page);
  await startTour(page);
  await goToStep(page, 'setup-button');
  await guide(page).getByRole('button', { name: 'Next', exact: true }).evaluate(button => {
    button.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  });
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .read-only-modal')).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await startTour(page);
  await expect(guide(page)).toHaveAttribute('data-guide-step', steps[0].id);
  await page.keyboard.press('Escape');
  expectNoEffects(fixture, requests);
});

for (const theme of ['light', 'dark']) {
  test(`Tuners overview and populated production surfaces retain readable contrast in ${theme} and representative hues`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, requests } = await openTuners(page, { theme });
    const audit = async (rootSelector, label) => {
      for (const hue of [null, 0, 120, 240]) {
        await page.evaluate(async value => {
          const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
          applyThemeHue(value);
        }, hue);
        const result = await expectContrast(page, { rootSelector,
          reportName: `tuners-guide-${label}-${theme}-hue-${hue}` });
        expect(result.summary.text.checked).toBeGreaterThan(3);
      }
      await page.evaluate(async () => { const { applyThemeHue } = await import('/assets/core/theme.js?v=2'); applyThemeHue(null); });
    };
    await expectOverview(page);
    await audit('.ui-setup-guide', 'overview');
    await startTour(page);
    for (const step of steps) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', step.id);
      if (['modes', 'enter-setup', 'receiving-window', 'gain', 'frequency-correction', 'frequency-limits',
        'resume-channels', 'disable-tuner', 'disabled', 'restart-channels', 'live'].includes(step.id)) {
        await page.mouse.move(0, 0);
        await page.evaluate(() => {
          const backdrop = document.querySelector('.ui-setup-guide-backdrop');
          window.tunersGuideContrast = { backdrop, visibility: backdrop.style.visibility,
            inert: [...document.querySelectorAll('[inert]')] };
          backdrop.style.visibility = 'hidden';
          window.tunersGuideContrast.inert.forEach(element => { element.inert = false; });
        });
        try {
          await audit(modalSteps.has(step.id) ? '.read-only-modal:not(.ui-setup-guide)' : '.tuners-workspace', step.id);
        } finally {
          await page.evaluate(() => {
            const value = window.tunersGuideContrast;
            value.inert.forEach(element => { element.inert = true; });
            value.backdrop.style.visibility = value.visibility;
            delete window.tunersGuideContrast;
          });
        }
      }
      await next(page, step.id);
    }
    expectNoEffects(fixture, requests);
  });
}

// Coverage labels sit outside the colored, clipped instrument plot. Verify that
// geometry first, then measure their actual SVG fill against the composited
// ancestor paint instead of treating the instrument's labels as unmeasured text.
async function inspectCoverageSvgText(page) {
  return graph(page).evaluate(async root => {
    const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
    const svg = root.querySelector('svg');
    const labels = [...svg.querySelectorAll('text')];
    const plot = svg.querySelector('clipPath rect');
    const screenBounds = (element, bounds) => {
      const matrix = element.getScreenCTM();
      const points = [[bounds.x, bounds.y], [bounds.x + bounds.width, bounds.y],
        [bounds.x, bounds.y + bounds.height], [bounds.x + bounds.width, bounds.y + bounds.height]]
        .map(([x, y]) => new DOMPoint(x, y).matrixTransform(matrix));
      return { left: Math.min(...points.map(point => point.x)), right: Math.max(...points.map(point => point.x)),
        top: Math.min(...points.map(point => point.y)), bottom: Math.max(...points.map(point => point.y)) };
    };
    const plotBounds = screenBounds(svg, { x: Number(plot.getAttribute('x')), y: Number(plot.getAttribute('y')),
      width: Number(plot.getAttribute('width')), height: Number(plot.getAttribute('height')) });
    const geometry = labels.map(label => {
      const bounds = screenBounds(label, label.getBBox());
      return { text: label.textContent, bounds, outsidePlot: bounds.right <= plotBounds.left ||
        bounds.left >= plotBounds.right || bounds.bottom <= plotBounds.top || bounds.top >= plotBounds.bottom };
    });
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d', { willReadFrequently: true, colorSpace: 'srgb' });
    const colors = new Map();
    const rgba = value => {
      if (!CSS.supports('color', value)) throw new Error(`Unresolved SVG paint: ${value}`);
      if (!colors.has(value)) {
        context.clearRect(0, 0, 1, 1); context.fillStyle = value; context.fillRect(0, 0, 1, 1);
        const [red, green, blue, alpha] = context.getImageData(0, 0, 1, 1).data;
        colors.set(value, [red / 255, green / 255, blue / 255, alpha / 255]);
      }
      return [...colors.get(value)];
    };
    const over = (front, back) => {
      const alpha = front[3] + back[3] * (1 - front[3]);
      return alpha ? [0, 1, 2].map(index => (front[index] * front[3] +
        back[index] * back[3] * (1 - front[3])) / alpha).concat(alpha) : [0, 0, 0, 0];
    };
    const paint = (element, foreground = [0, 0, 0, 0]) => {
      let pixel = foreground;
      for (let owner = element; owner; owner = owner.parentElement) {
        const css = getComputedStyle(owner);
        if (css.backgroundImage !== 'none' || css.filter !== 'none' || css.mixBlendMode !== 'normal') {
          throw new Error('SVG labels acquired non-solid ancestor paint that needs a new pixel review.');
        }
        pixel = over(pixel, rgba(css.backgroundColor));
        pixel[3] *= Number(css.opacity);
      }
      return over(pixel, [1, 1, 1, 1]);
    };
    const luminance = color => color.slice(0, 3).reduce((sum, component, index) => sum +
      (component <= .04045 ? component / 12.92 : ((component + .055) / 1.055) ** 2.4) *
      [.2126, .7152, .0722][index], 0);
    root.closest('.ui-setup-guide').querySelectorAll('*').forEach(element => { element.style.transition = 'none'; });
    const failures = [];
    let worst = { ratio: Infinity }, checked = 0;
    try {
      for (const hue of [null, ...Array.from({ length: 360 }, (_, index) => index)]) {
        applyThemeHue(hue);
        for (const label of labels) {
          const css = getComputedStyle(label);
          if (css.stroke !== 'none') throw new Error('SVG label strokes require a new paint review.');
          const ink = rgba(css.fill);
          ink[3] *= Number(css.fillOpacity);
          const foreground = paint(label, ink), background = paint(label);
          const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
          const ratio = (values[0] + .05) / (values[1] + .05);
          const finding = { hue, text: label.textContent, ratio, foreground, background };
          checked += 1;
          if (ratio < worst.ratio) worst = finding;
          if (ratio < 4.5) failures.push(finding);
        }
      }
    } finally { applyThemeHue(null); }
    return { labels: labels.length, checked, geometry, plotBounds, failures, worst };
  });
}

for (const theme of ['light', 'dark']) {
  test(`Tuners coverage SVG labels retain measured contrast across all 360 hues in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const { fixture, requests } = await openTuners(page, { theme });
    await expectOverview(page);
    const result = await inspectCoverageSvgText(page);
    fs.mkdirSync(evidence, { recursive: true });
    fs.writeFileSync(path.join(evidence, `coverage-svg-${theme}-360-hues.json`), `${JSON.stringify(result, null, 2)}\n`);
    expect(result.labels).toBe(15);
    expect(result.checked).toBe(15 * 361);
    expect(result.geometry.filter(label => !label.outsidePlot), 'Each label has the actual solid modal paint behind it.').toEqual([]);
    expect(result.failures).toEqual([]);
    expect(result.worst.ratio).toBeGreaterThanOrEqual(4.5);
    await page.keyboard.press('Escape');
    expectNoEffects(fixture, requests);
  });
}
