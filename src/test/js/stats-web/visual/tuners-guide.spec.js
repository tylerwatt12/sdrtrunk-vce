'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { installSiteStyleApplication, operatorTuner } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="tuners"]');
const graph = page => page.locator('[data-guide-diagram="tuner-coverage"]');
const hardwareExamples = [
  { id: 'rtl-single', label: '1× RTL-SDR (unlocked)', receivers: 1 },
  { id: 'rtl', label: '2× RTL-SDR', receivers: 2 },
  { id: 'rtl-three', label: '3× RTL-SDR', receivers: 3 },
  { id: 'airspy', label: '1× Airspy', receivers: 1 }
];
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
        !['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals')) {
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
  await expect(guide(page).getByRole('heading', { name: 'One receiver, several channels', exact: true })).toBeVisible();
  await expect(guide(page)).toContainText('SDR models have different usable bandwidths.');
  await expect(guide(page)).toContainText('Adding tuners lets VCE cover more frequencies at once, helping prevent missed calls');
  await expect(graph(page)).toContainText('This example follows one trunked radio system.');
  await expect(graph(page)).toBeVisible();
  await expect(graph(page).locator('.ui-setup-guide-coverage-identity strong')).toHaveText(['RTL-SDR 1', 'RTL-SDR 2']);
  await expect(graph(page).locator('.coverage-control')).toHaveCount(1);
  await expect(graph(page)).toHaveAttribute('data-example', 'rtl');
  const examples = graph(page).getByRole('group', { name: 'Example tuner hardware', exact: true });
  await expect(examples.getByRole('button')).toHaveText(hardwareExamples.map(example => example.label));
  await expect(examples.getByRole('button', { name: '2× RTL-SDR', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(examples.getByRole('button', { name: '1× Airspy', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await expect(graph(page).locator('input, select, [data-coverage-time]')).toHaveCount(0);
  await expect(graph(page).locator('.ui-setup-guide-coverage-recent, .ui-setup-guide-coverage-note')).toHaveCount(0);
  await expect(page.locator('.ui-setup-guide-highlight, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
}

async function startTour(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
}

async function freshCoveragePaint(page) {
  const before = await graph(page).getAttribute('data-time');
  for (let frame = 0; frame < 6; frame++) {
    await page.clock.runFor(16);
    if (await graph(page).getAttribute('data-time') !== before) return;
  }
  throw new Error('The playing coverage illustration did not paint a fresh animation frame.');
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

test('the Tuners overview graph loops after a minute, preserves reception history, and releases animation on Start and Back', async ({ page }) => {
  await page.clock.install();
  const { fixture, requests } = await openTuners(page, { reducedMotion: false });
  await expectOverview(page);
  await page.locator('#content').evaluate(element => { window.tunersOverviewOriginal = [...element.childNodes]; });
  const storageBefore = await nonGuideStorage(page);
  await graph(page).evaluate(element => { window.tunersOriginalGraph = element; });
  await expect(graph(page)).toHaveAttribute('data-playing', 'true');
  const { TUNER_COVERAGE_MODEL: model, coverageReceiverFor } = await import(pathToFileURL(path.resolve(__dirname,
    '../../../../../stats-web/assets/guides/tuner-coverage.js')).href);
  const fits = (frequency, center) => Math.abs(frequency - center) + .00625 <= model.rtl.usableBandwidth / 2;
  const historyCase = model.retunes.map(retune => ({ time: retune.end + .5,
    received: model.calls.find(call => call.end < retune.end && call.end > retune.end - model.history + 4 &&
      coverageReceiverFor(call) === 'RTL-SDR 2' && !fits(call.frequency, retune.to)),
    missed: model.calls.find(call => call.start <= retune.end && call.end > retune.end - model.history + 4 &&
      !coverageReceiverFor(call)) })).find(example => example.received && example.missed);
  expect(historyCase, 'The example includes received history behind a later tuner hop').toBeTruthy();
  await page.clock.runFor(historyCase.time / model.speed * 1000);
  await expect(graph(page).locator(`[data-call="${historyCase.missed.id}"]`)).toHaveAttribute('data-received', 'false');
  const receivedTrace = graph(page).locator(`[data-call="${historyCase.received.id}"]`);
  await expect(receivedTrace).toHaveAttribute('data-received', 'true');
  expect(await graph(page).locator('[data-call]').count(), 'The receiving history contains the busy overlapping calls').toBeGreaterThanOrEqual(10);
  await expect(graph(page).locator('svg')).toContainText('24s');
  await screenshot(page, 'tuners-overview-after-retune-light');
  const traceBefore = Number(await receivedTrace.getAttribute('y2'));
  await page.clock.runFor(500);
  const traceAfter = Number(await receivedTrace.getAttribute('y2'));
  await expect(receivedTrace).toHaveAttribute('data-received', 'true');
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
  await graph(page).getByRole('button', { name: '1× Airspy', exact: true }).click();
  await expect(graph(page)).toHaveAttribute('data-example', 'airspy');
  await expect(graph(page)).toHaveAttribute('data-playing', 'false');
  expect(Number(await graph(page).getAttribute('data-time'))).toBe(hiddenTime);
  await graph(page).getByRole('button', { name: '2× RTL-SDR', exact: true }).click();
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
