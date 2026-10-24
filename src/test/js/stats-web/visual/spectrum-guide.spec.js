'use strict';

const { expect, test } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { installSiteStyleApplication } = require('./fixtures/site-style-app.cjs');
const { expectContrast } = require('./fixtures/contrast-audit.cjs');

const guide = page => page.locator('.ui-setup-guide[data-guide-id="tuner-spectrum"]');
const steps = ['receiver', 'activity', 'measurements', 'zoom', 'pan', 'reset-zoom', 'channel-marker', 'channel-details',
  'waterfall', 'waterfall-details', 'pause', 'select-signal', 'frequency-actions', 'listen', 'lookup', 'add-channel', 'modes',
  'display-options', 'display-settings', 'plot-settings', 'performance', 'display-close'];
const compactSteps = new Set(['activity', 'zoom', 'pan', 'waterfall']);
const memoryKey = 'sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:tuner-spectrum:5';
const evidence = process.env.SPECTRUM_GUIDE_EVIDENCE ||
  path.resolve(__dirname, '../../../../../build/playwright-results/spectrum-guide-evidence');
const nonGuideStorage = page => page.evaluate(() => Object.fromEntries(Object.entries(localStorage)
  .filter(([key]) => !key.startsWith('sdrtrunk-vce-guide:'))));

async function openSpectrum(page, { theme = 'light', replay = true, firstVisit = false,
  noHardware = false, denied = [], exposeSelection = false } = {}) {
  const effects = { writes: [], samples: [], audio: [], sockets: [], socketWrites: [], controls: [] };
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/')) {
      if (!['GET', 'HEAD'].includes(request.method()) &&
        !(request.method() === 'PUT' && url.pathname === '/api/v1/me/tour-dismissals') && url.pathname !== '/api/v1/live/multiplex/control') {
        effects.writes.push(`${request.method()} ${url.pathname}`);
      }
      if (url.pathname !== '/api/v1/me/tour-dismissals' && /guide-|sample-|tour-/.test(url.pathname + (request.postData() || ''))) effects.samples.push(`${request.method()} ${url.pathname}`);
      if (/audio|listen/.test(url.pathname) && !url.pathname.startsWith('/api/v1/listen/')) effects.audio.push(url.pathname);
      if (url.pathname === '/api/v1/live/multiplex/control') effects.controls.push(request.postDataJSON());
    }
  });
  page.on('websocket', socket => {
    effects.sockets.push(socket.url());
    socket.on('framesent', ({ payload }) => effects.socketWrites.push(payload));
  });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const fixture = await installSiteStyleApplication(page, theme, undefined, { guides: firstVisit });
  await page.addInitScript(({ firstVisit, key }) => {
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:welcome:1', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:tuner-spectrum:2', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:tuner-spectrum:3', '1');
    localStorage.setItem('sdrtrunk-vce-guide:v1:fixture-admin:account-1-100:tuner-spectrum:4', '1');
    if (!firstVisit) localStorage.setItem(key, '1');
  }, { firstVisit, key: memoryKey });
  if (noHardware) {
    await page.route('**/api/v1/admin/tuners', route => route.fulfill({ json: { data: { tuners: [] } } }));
    await page.route('**/api/v1/diagnostics/tuners', route => route.fulfill({ json: { data: { rows: [] } } }));
  }
  if (denied.length) {
    const file = path.resolve(__dirname, '../../../../../src/main/java/io/github/dsheirer/web/auth/WebCapability.java');
    const capabilities = Object.fromEntries([...fs.readFileSync(file, 'utf8').matchAll(/^\s+[A-Z_]+\("([^"]+)",/gm)]
      .map(([, id]) => [id, !denied.includes(id)]));
    await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { data: {
      configured: true, authenticated: true, username: 'fixture-admin', tier: 'admin', primary: true,
      csrf_token: 'fixture-csrf', guide_dismissal_scope: 'account-1-100', capabilities
    } } }));
  }
  if (exposeSelection) {
    const file = path.resolve(__dirname, '../../../../../stats-web/assets/app.js');
    await page.route('**/assets/app.js*', route => route.fulfill({ contentType: 'text/javascript',
      body: `${fs.readFileSync(file, 'utf8')}\nexport { openTunerFrequencyActions };` }));
  }
  await page.goto(`/app.html?view=tuner-spectrum${replay ? '&guide=tuner-spectrum' : ''}`);
  await expect(page.locator('#content')).toHaveAttribute('aria-busy', 'false');
  return { fixture, effects };
}

async function expectInViewport(locator, viewport) {
  await expect.poll(async () => {
    const box = await locator.boundingBox();
    return box && box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width + 1 &&
      box.y + box.height <= viewport.height + 1 ? null : box;
  }, { message: 'The actual control and guide stay fully inside the viewport' }).toBeNull();
}

async function expectOverview(page) {
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide(page).locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await expect(guide(page)).toContainText('Find signals worth receiving');
  await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(0);
  await expect(page.locator('.spectrum-browse-workspace[data-guide-example="tuner-spectrum"]')).toBeVisible();
  await expect(page.locator('#content')).toContainText('Example signals');
  await expect(page.getByRole('combobox', { name: 'Tuner', exact: true }).locator('option')).toHaveCount(2);
  await expect(page.getByRole('combobox', { name: 'Tuner', exact: true })).toContainText('RTL-SDR');
  await expect(page.locator('.spectrum-browse-instrument canvas')).toHaveCount(2);
  await expectInViewport(guide(page), page.viewportSize());
}

async function start(page) {
  await expectOverview(page);
  await guide(page).getByRole('button', { name: 'Start tour', exact: true }).click();
}

async function advance(page, id) {
  await guide(page).getByRole('button', { name: id === steps.at(-1) ? 'Finish' : 'Next', exact: true }).click();
}

async function goTo(page, destination) {
  const current = await guide(page).getAttribute('data-guide-step');
  for (const id of steps.slice(Math.max(0, steps.indexOf(current)))) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    if (id === destination) return;
    await advance(page, id);
  }
  throw new Error(`Unknown Browser Spectrum tour step: ${destination}`);
}

async function expectSpotlight(page, id) {
  const targets = page.locator(`[data-guide-target="bs-${id}"]:visible`);
  await expect(targets.first()).toBeVisible();
  for (const target of await targets.all()) {
    await expectInViewport(target, page.viewportSize());
    await expect.poll(() => target.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const header = document.querySelector('.app-header')?.getBoundingClientRect();
      const card = document.querySelector('.ui-setup-guide').getBoundingClientRect();
      const highlights = [...document.querySelectorAll('.ui-setup-guide-highlight')]
        .map(item => item.getBoundingClientRect());
      const overlap = Math.min(rect.right, card.right) - Math.max(rect.left, card.left) > 1 &&
        Math.min(rect.bottom, card.bottom) - Math.max(rect.top, card.top) > 1;
      const covered = highlights.some(box => box.left <= rect.left + 1 && box.top <= rect.top + 1 &&
        box.right >= rect.right - 1 && box.bottom >= rect.bottom - 1);
      return (!header || rect.top >= header.bottom - 1) && !overlap && covered ? null :
        { target: { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom },
          headerBottom: header?.bottom, overlap, covered };
    }), { message: `bs-${id} highlights its real control, below the header and clear of the guide card` }).toBeNull();
  }
}

async function expectBranchEntriesOnly(page, id) {
  await expect(page.locator('.read-only-modal:not(.ui-setup-guide), .spectrum-discovery-workspace')).toHaveCount(0);
  await expect(page.locator('.primary-nav details[open]')).toHaveCount(0);
  await expect(page.locator('.tuner-spectrum-options[open]')).toHaveCount(
    ['display-settings', 'plot-settings', 'performance', 'display-close'].includes(id) ? 1 : 0);
  await expect(page.getByRole('button', { name: 'Stop listening', exact: true })).toHaveCount(0);
  await expect(page.locator('.spectrum-browse-workspace [data-mode="live"]')).toHaveAttribute('aria-pressed', 'true');
}

async function expectLessonState(page, id) {
  const popup = page.locator('.tuner-spectrum-cursor-popup:visible');
  const reset = page.getByRole('button', { name: 'Reset zoom', exact: true });
  if (['pan', 'reset-zoom'].includes(id)) {
    await expect(reset).toBeEnabled();
    await expect(page.locator('.tuner-spectrum-readouts')).toContainText('2.00×');
  }
  if (id === 'measurements') {
    const measurements = page.locator('[data-guide-target="bs-measurements"]');
    for (const label of ['Visible span', 'Zoom', 'Peak', 'Best SNR']) await expect(measurements).toContainText(label);
    await expect(measurements.locator('summary[aria-label="More measurements"]')).toBeVisible();
    await expect(page.locator('.tuner-spectrum-more-measurements[open]')).toHaveCount(0);
  }
  if (id === 'channel-marker') {
    await expect(reset).toBeDisabled();
    await expect(page.locator('[data-guide-target="bs-channel-marker"]')).toHaveClass(/\bstatus-call\b/);
    await expect(popup).toHaveCount(0);
  }
  if (id === 'channel-details') {
    await expect(popup).toBeVisible();
    await expect(popup).toHaveAttribute('data-guide-target', 'bs-channel-details');
    await expect(popup).toContainText('County P25');
    await expect(popup).toContainText('North Site');
    await expect(popup).toContainText('Sheriff Dispatch');
    await expect(popup).toContainText('Sheriff 214');
    await expect(popup).toContainText('Voice quality');
    await expect(popup).toContainText('TGID');
    await expect(popup.locator('.tuner-spectrum-cursor-field-label')).not.toHaveCount(0);
  }
  if (id === 'waterfall') {
    await expect(page.locator('[data-guide-target="bs-waterfall"]')).toHaveAttribute('aria-label',
      'Tuner spectrum history. Each row keeps the receiver window that created it. Gold dividers mark tuner retunes.');
  }
  if (id === 'waterfall-details') {
    await expect(popup).toBeVisible();
    await expect(popup).toHaveAttribute('data-guide-target', 'bs-waterfall-details');
    await expect(popup).toContainText('History');
    await expect(popup).toContainText('Retuned');
    await expect(popup.locator('.tuner-spectrum-cursor-frequency')).toHaveText(/\d+\.\d{6} MHz/);
    await expect(popup.locator('.tuner-spectrum-cursor-power')).toHaveText(/History · \d{1,2}:\d{2}:\d{2}/);
  }
  if (id === 'pause') {
    await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Pause', exact: true })).toHaveAttribute('aria-pressed', 'false');
  }
  if (id === 'frequency-actions') {
    const panel = page.locator('[data-guide-target="bs-frequency-actions"]');
    await expect(panel).toHaveClass(/\bspectrum-browse-control-rail\b/);
    await expect(panel.locator('.tuner-frequency-action-frequency')).toHaveText('856.412500 MHz');
    for (const name of ['Listen in NBFM', 'Look up on RadioReference', 'Add channel or system']) {
      await expect(panel.getByRole('button', { name, exact: true })).toBeVisible();
    }
    await expect(guide(page)).toContainText('Use this panel to investigate the selected frequency and choose how to receive it.');
  }
  if (id === 'display-options') await expect(page.getByRole('button', { name: 'Display options', exact: true }))
    .toHaveAttribute('aria-expanded', 'false');
  if (['display-settings', 'plot-settings', 'performance', 'display-close'].includes(id)) {
    await expect(page.getByRole('button', { name: 'Display options', exact: true })).toHaveAttribute('aria-expanded', 'true');
    const panel = page.getByRole('group', { name: 'Tuner spectrum options', exact: true });
    await expect(panel).toBeVisible();
    const geometry = await panel.evaluate(element => ({
      theme: document.documentElement.dataset.theme || 'light', panel: element.getBoundingClientRect().toJSON(),
      clientHeight: element.clientHeight, scrollHeight: element.scrollHeight, scrollTop: element.scrollTop,
      cards: [...element.querySelectorAll('.settings-card')].map(card => ({
        title: card.querySelector('h3')?.textContent, box: card.getBoundingClientRect().toJSON()
      }))
    }));
    fs.mkdirSync(evidence, { recursive: true });
    fs.writeFileSync(path.join(evidence, `panel-${id}-${geometry.theme}.json`), `${JSON.stringify(geometry, null, 2)}\n`);
    const actualControls = id === 'display-settings' ? [
      panel.getByRole('checkbox', { name: 'Snap frequency', exact: true }),
      panel.getByRole('checkbox', { name: 'Auto range display range on retune', exact: true })
    ] : id === 'plot-settings' ? [
      panel.getByRole('slider', { name: 'Waterfall speed', exact: true }),
      panel.getByRole('checkbox', { name: 'Smooth FFT', exact: true })
    ] : [panel.getByRole('combobox', { name: 'Profile', exact: true })];
    const panelBox = await panel.boundingBox();
    for (const control of actualControls) {
      await expect(control).toBeVisible();
      const box = await control.boundingBox();
      expect(box.y).toBeGreaterThanOrEqual(panelBox.y);
      expect(box.y + box.height).toBeLessThanOrEqual(panelBox.y + panelBox.height + 1);
    }
  }
}

function expectIsolated(fixture, effects) {
  expect(effects.samples).toEqual([]);
  expect(effects.audio).toEqual([]);
  expect(effects.sockets).toEqual([]);
  expect(effects.socketWrites).toEqual([]);
  expect(fixture.pageErrors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
}

async function screenshot(page, name) {
  await page.screenshot({ path: path.join(evidence, 'screens', `${name}.png`) });
}

for (const { theme, width, height } of [
  { theme: 'light', width: 1280, height: 720 }, { theme: 'dark', width: 1440, height: 1000 }
]) {
  test(`Browser Spectrum tours actual FFT, waterfall, channel details, and branch entries with example signals in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const { fixture, effects } = await openSpectrum(page, { theme, noHardware: true });
    await expectOverview(page);
    await screenshot(page, `spectrum-overview-${theme}`);
    const storageBefore = await nonGuideStorage(page), routeBefore = page.url();
    const controlsBefore = structuredClone(effects.controls);
    await start(page);
    for (const [index, id] of steps.entries()) {
      await expect(guide(page)).toHaveAttribute('data-guide-step', id);
      await expect(guide(page)).toContainText(`Step ${index + 1} of ${steps.length}`);
      if (compactSteps.has(id)) {
        await expect(guide(page)).toHaveClass(/\bui-setup-guide--compact\b/);
        await expect.poll(async () => Math.round((await guide(page).boundingBox()).width)).toBe(260);
      } else {
        await expect(guide(page)).not.toHaveClass(/\bui-setup-guide--compact\b/);
        await expect.poll(async () => Math.round((await guide(page).boundingBox()).width)).toBe(470);
      }
      try { await expectSpotlight(page, id); }
      catch (error) {
        const geometry = await page.evaluate(() => {
          const selectors = ['.tuner-spectrum-cursor-popup', '.tuner-spectrum-active-flag.status-call',
            '.tuner-spectrum-layout', '.ui-setup-guide', '#content', '.app-header'];
          return { scroll: [scrollX, scrollY], elements: selectors.map(selector => {
            const element = document.querySelector(selector), box = element?.getBoundingClientRect();
            return { selector, box: box?.toJSON(), style: element?.getAttribute('style'),
              position: element && getComputedStyle(element).position,
              scroll: element && [element.scrollLeft, element.scrollTop] };
          }) };
        });
        fs.mkdirSync(evidence, { recursive: true });
        fs.writeFileSync(path.join(evidence, `geometry-${id}-${theme}.json`), `${JSON.stringify(geometry, null, 2)}\n`);
        await screenshot(page, `spectrum-failure-${id}-${theme}`);
        throw error;
      }
      await expectInViewport(guide(page), { width, height });
      await expectInViewport(guide(page).getByRole('button', { name: 'Close guide', exact: true }), { width, height });
      await expectInViewport(guide(page).getByRole('button', {
        name: id === steps.at(-1) ? 'Finish' : 'Next', exact: true }), { width, height });
      if (index > 0) await expectInViewport(guide(page).getByRole('button', { name: 'Back', exact: true }), { width, height });
      await expect.poll(() => guide(page).evaluate(element => {
        const header = document.querySelector('.app-header')?.getBoundingClientRect();
        return !header || element.getBoundingClientRect().top >= header.bottom - 1;
      }), { message: 'The guide card clears the application header' }).toBe(true);
      await expectBranchEntriesOnly(page, id);
      if (['frequency-actions', 'listen', 'lookup', 'add-channel', 'modes', 'display-options', 'display-settings',
        'plot-settings', 'performance', 'display-close'].includes(id)) {
        await expect(page.locator('.tuner-frequency-action-frequency')).toHaveText('856.412500 MHz');
      }
      await expectLessonState(page, id);
      if (id === 'activity') {
        const activityTarget = page.locator('[data-guide-target="bs-activity"]');
        await expect(activityTarget).toHaveClass(/\btuner-spectrum-visual-window\b/);
        await expect(activityTarget.locator('canvas')).toHaveCount(2);
        await expect(activityTarget.locator('.tuner-spectrum-active-flag')).not.toHaveCount(0);
        await expect(activityTarget.locator('.tuner-spectrum-active-flag.status-call')).toBeVisible();
        const waterfall = page.locator('.tuner-spectrum-waterfall canvas');
        await expect.poll(() => waterfall.evaluate(canvas => {
          const { data } = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height);
          let populatedRows = 0;
          for (let y = 0; y < canvas.height; y++) {
            const colors = new Set();
            for (let x = 0; x < canvas.width; x += 3) {
              const offset = (y * canvas.width + x) * 4;
              colors.add(`${data[offset]},${data[offset + 1]},${data[offset + 2]}`);
            }
            if (colors.size > 10) populatedRows++;
          }
          return populatedRows;
        }), { message: 'The actual waterfall contains a useful sample signal history at entry' }).toBeGreaterThan(35);
        const waterfallBefore = await waterfall.evaluate(canvas => canvas.toDataURL());
        await expect.poll(() => waterfall.evaluate((canvas, before) => canvas.toDataURL() !== before, waterfallBefore),
          { message: 'The production waterfall keeps scrolling while the activity lesson is visible' }).toBe(true);
        await expect.poll(() => page.locator('.spectrum-browse-instrument canvas').evaluateAll(canvases =>
          canvases.map(canvas => {
            const rgba = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
            const colors = new Set();
            for (let offset = 0; offset < rgba.length; offset += 4) colors.add(rgba.slice(offset, offset + 4).join(','));
            return colors.size;
          }).every(count => count > 15)), { message: 'The production FFT and waterfall paint the sample frames' }).toBe(true);
      }
      await screenshot(page, `spectrum-${id}-${theme}`);
      await advance(page, id);
    }
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-highlight, .ui-setup-guide-layer')).toHaveCount(0);
    await expect(page.locator('.spectrum-browse-feedback')).toContainText('No tuners are available. Connect a tuner and reload.');
    await expect(page.getByRole('combobox', { name: 'Tuner', exact: true }).locator('option')).toHaveCount(0);
    await expect(page).toHaveURL(routeBefore);
    expect(await nonGuideStorage(page)).toEqual(storageBefore);
    expect(effects.controls).toEqual(controlsBefore);
    expect(effects.writes).toEqual([]);
    expectIsolated(fixture, effects);
  });
}

for (const { stop, dismiss } of [
  { stop: 'receiver', dismiss: 'Close guide' }, { stop: 'channel-details', dismiss: 'Escape' },
  { stop: 'display-settings', dismiss: 'shade' }
]) {
  test(`Browser Spectrum restores its original controls, selection, display draft, and player after ${dismiss}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    const { fixture, effects } = await openSpectrum(page, { replay: false, exposeSelection: true });
    if (dismiss === 'Escape') {
      const player = page.locator('#audio-dock');
      await player.getByRole('combobox', { name: 'Audio source', exact: true }).selectOption('recordings');
      await expect(page).toHaveURL(/view=recordings/);
      await player.getByRole('button', { name: 'Expand player', exact: true }).first().click();
      await expect(player).toHaveAttribute('data-state', 'full');
      await page.locator('.primary-nav [data-view="tuner-spectrum"]').evaluate(link => link.click());
    }
    await expect(page.locator('.spectrum-browse-center')).toContainText('0851.01250MHz');
    await page.evaluate(async () => {
      const application = await import(document.querySelector('script[type="module"][src*="/assets/app.js"]').src);
      application.openTunerFrequencyActions({ frequencyHz: 851012500, targetId: 'target-a',
        actionHost: document.querySelector('.spectrum-browse-control-rail') });
      const content = document.getElementById('content');
      const options = content.querySelector('.tuner-spectrum-options');
      options.open = true;
      content.querySelector('#tuner-spectrum-floor').value = '-117';
      window.spectrumGuideOriginal = { content, nodes: [...content.childNodes],
        workspace: content.querySelector('.spectrum-browse-workspace'),
        selection: content.querySelector('.tuner-frequency-action-body'), options };
    });
    await expect(page.locator('.tuner-frequency-action-frequency')).toHaveText('851.012500 MHz');
    const storageBefore = await nonGuideStorage(page), routeBefore = page.url(), titleBefore = await page.title();
    const scrollBefore = await page.evaluate(() => ({ window: [scrollX, scrollY],
      content: [document.getElementById('content').scrollTop, document.getElementById('content').scrollLeft] }));
    const player = page.locator('#audio-dock');
    const playerBefore = await player.evaluate(element => ({ source: element.dataset.source,
      state: element.dataset.state, collapsed: element.dataset.superCollapsed }));
    const writeCount = effects.writes.length;
    await page.locator('#page-guide-button').click();
    await start(page);
    await goTo(page, stop);
    await guide(page).getByRole('button', { name: 'Back', exact: true }).click();
    await guide(page).getByRole('button', { name: stop === 'receiver' ? 'Start tour' : 'Next', exact: true }).click();
    await expect(guide(page)).toHaveAttribute('data-guide-step', stop);
    if (dismiss === 'Escape') await page.keyboard.press('Escape');
    else if (dismiss === 'shade') await page.mouse.click(8, 500);
    else await guide(page).getByRole('button', { name: dismiss, exact: true }).click();
    await expect(guide(page)).toHaveCount(0);
    await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .ui-setup-guide-highlight')).toHaveCount(0);
    expect(await page.locator('#content').evaluate(element => {
      const original = window.spectrumGuideOriginal;
      return element === original.content && element.querySelector('.spectrum-browse-workspace') === original.workspace &&
        element.querySelector('.tuner-frequency-action-body') === original.selection &&
        element.querySelector('.tuner-spectrum-options') === original.options &&
        original.nodes.every((node, index) => element.childNodes[index] === node);
    })).toBe(true);
    await expect(page.locator('.tuner-frequency-action-frequency')).toHaveText('851.012500 MHz');
    await expect(page.locator('.tuner-spectrum-options')).toHaveAttribute('open');
    await expect(page.locator('#tuner-spectrum-floor')).toHaveValue('-117');
    await expect(page).toHaveURL(routeBefore);
    await expect(page).toHaveTitle(titleBefore);
    expect(await nonGuideStorage(page)).toEqual(storageBefore);
    expect(await page.evaluate(() => ({ window: [scrollX, scrollY],
      content: [document.getElementById('content').scrollTop, document.getElementById('content').scrollLeft] }))).toEqual(scrollBefore);
    await expect(player).toHaveAttribute('data-source', playerBefore.source);
    await expect(player).toHaveAttribute('data-state', playerBefore.state);
    await expect(player).toHaveAttribute('data-super-collapsed', playerBefore.collapsed);
    await expect(page.locator('#page-guide-button')).toBeFocused();
    expect(effects.writes.length).toBe(writeCount);
    expectIsolated(fixture, effects);
  });
}

test('Browser Spectrum sample controls cannot start hardware, audio, lookup, channel creation, or persist display preferences', async ({ page }) => {
  const { fixture, effects } = await openSpectrum(page, { noHardware: true });
  await start(page);
  const routeBefore = page.url(), storageBefore = await nonGuideStorage(page);
  const controlsBefore = structuredClone(effects.controls);
  let exercised = 0;
  for (const id of steps) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    exercised += await page.evaluate(() => {
      const roots = [...document.querySelectorAll('[data-guide-sample]')];
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
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expect(page).toHaveURL(routeBefore);
    await expectBranchEntriesOnly(page, id);
    await advance(page, id);
  }
  expect(exercised).toBeGreaterThan(50);
  expect(effects.writes).toEqual([]);
  expect(effects.controls).toEqual(controlsBefore);
  expect(await nonGuideStorage(page)).toEqual(storageBefore);
  expectIsolated(fixture, effects);
});

test('Browser Spectrum first desktop visit runs once without hardware, with Help and named URL replay', async ({ page }) => {
  const { fixture, effects } = await openSpectrum(page, { firstVisit: true, replay: false, noHardware: true });
  await expectOverview(page);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBe('1');
  await page.reload();
  await expect(page.locator('#page-guide-button')).toBeVisible();
  await expect(page.locator('.spectrum-browse-feedback')).toContainText('No tuners are available');
  await expect(guide(page)).toHaveCount(0);
  await page.locator('#page-guide-button').click();
  await start(page);
  await page.keyboard.press('Escape');
  await page.goto('/app.html?view=tuner-spectrum&guide=tuner-spectrum');
  await expectOverview(page);
  await page.keyboard.press('Escape');
  expect(effects.writes).toEqual([]);
  expectIsolated(fixture, effects);
});

test('Browser Spectrum navigation releases sample signals before another page and a fresh real Spectrum visit', async ({ page }) => {
  const { fixture, effects } = await openSpectrum(page, { noHardware: true });
  await start(page);
  await goTo(page, 'lookup');
  await page.locator('.primary-nav [data-view="dashboard"]').evaluate(link => link.click());
  await expect(page.getByRole('heading', { name: 'Main', exact: true })).toBeVisible();
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .ui-setup-guide, .read-only-modal')).toHaveCount(0);
  await page.locator('.primary-nav [data-view="tuner-spectrum"]').evaluate(link => link.click());
  await expect(page.locator('.spectrum-browse-feedback')).toContainText('No tuners are available');
  await expect(page.locator('.tuner-frequency-action-frequency')).toHaveCount(0);
  expect(effects.writes).toEqual([]);
  expectIsolated(fixture, effects);
});

test('phone visits keep the desktop Browser Spectrum tour and sample signals closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { fixture, effects } = await openSpectrum(page, { firstVisit: true, noHardware: true });
  await expect(page.getByRole('heading', { name: 'Browse Spectrum', exact: true })).toBeVisible();
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], .ui-setup-guide-layer')).toHaveCount(0);
  await expect(page.locator('#page-guide-button')).toBeHidden();
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectIsolated(fixture, effects);
});

test('Browser Spectrum omits branch entry points for actions the account cannot use', async ({ page }) => {
  const { fixture, effects } = await openSpectrum(page, { noHardware: true,
    denied: ['call-audio', 'admin-settings', 'admin-channels'] });
  await start(page);
  for (const id of steps.filter(id => !['listen', 'lookup', 'add-channel'].includes(id))) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expectSpotlight(page, id);
    await advance(page, id);
  }
  await expect(guide(page)).toHaveCount(0);
  expect(effects.writes).toEqual([]);
  expectIsolated(fixture, effects);
});

test('Browser Spectrum hides channel-marker lessons without Live access and keeps instrument features available', async ({ page }) => {
  const { fixture, effects } = await openSpectrum(page, { noHardware: true, denied: ['live'] });
  await start(page);
  for (const id of steps.filter(id => !['channel-marker', 'channel-details'].includes(id))) {
    await expect(guide(page)).toHaveAttribute('data-guide-step', id);
    await expectSpotlight(page, id);
    await expect(page.locator('.tuner-spectrum-active-flag')).toHaveCount(0);
    await advance(page, id);
  }
  await expect(guide(page)).toHaveCount(0);
  expect(effects.writes).toEqual([]);
  expectIsolated(fixture, effects);
});

test('Browser Spectrum tuner access denial cannot open its guide, sample page, or receiver APIs', async ({ page }) => {
  const { fixture, effects } = await openSpectrum(page, { noHardware: true, firstVisit: true,
    denied: ['admin-tuners'] });
  await expect(page.locator('.ui-setup-guide, [data-guide-sample], .ui-setup-guide-layer, .spectrum-browse-workspace')).toHaveCount(0);
  expect(fixture.requests.filter(value => value.startsWith('/api/v1/admin/tuners'))).toEqual([]);
  expect(await page.evaluate(key => localStorage.getItem(key), memoryKey)).toBeNull();
  expectIsolated(fixture, effects);
});


test('Browser Spectrum dismissal during example preparation cannot repaint stale signals or reopen a branch', async ({ page }) => {
  const { fixture, effects } = await openSpectrum(page, { replay: false, noHardware: true });
  await expect(page.locator('.spectrum-browse-feedback')).toContainText('No tuners are available');
  await page.locator('#page-guide-button').evaluate(button => {
    button.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  });
  await expect(guide(page)).toHaveCount(0);
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .read-only-modal')).toHaveCount(0);
  await expect(page.locator('.spectrum-browse-feedback')).toContainText('No tuners are available');
  await page.locator('#page-guide-button').click();
  await start(page);
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'receiver');
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .read-only-modal')).toHaveCount(0);
  expect(effects.writes).toEqual([]);
  expectIsolated(fixture, effects);
});

test('Browser Spectrum retains the explained retune observation while reading and resumes sample activity afterward', async ({ page }) => {
  test.setTimeout(45000);
  await page.setViewportSize({ width: 1280, height: 720 });
  const { fixture, effects } = await openSpectrum(page, { noHardware: true });
  await start(page);
  await goTo(page, 'waterfall-details');
  const popup = page.locator('.tuner-spectrum-cursor-popup:visible');
  const waterfall = page.locator('.tuner-spectrum-waterfall canvas');
  await expect(popup).toContainText('Retuned');
  const originalObservation = await popup.textContent();
  await screenshot(page, 'spectrum-retune-history-before-35s');
  await page.waitForTimeout(35000);
  await expect(popup).toHaveText(originalObservation);
  await expectSpotlight(page, 'waterfall-details');
  await screenshot(page, 'spectrum-retune-history-after-35s');
  await advance(page, 'waterfall-details');
  await expect(guide(page)).toHaveAttribute('data-guide-step', 'pause');
  const resumedFrame = await waterfall.evaluate(canvas => canvas.toDataURL());
  await expect.poll(() => waterfall.evaluate((canvas, original) => canvas.toDataURL() !== original, resumedFrame),
    { message: 'The next lesson returns to normal sample waterfall activity' }).toBe(true);
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-guide-sample], .ui-setup-guide-layer, .ui-setup-guide-highlight')).toHaveCount(0);
  expect(effects.writes).toEqual([]);
  expectIsolated(fixture, effects);
});


for (const theme of ['light', 'dark']) {
  test(`Browser Spectrum guide and actual populated controls retain readable contrast in ${theme} and representative hues`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const { fixture, effects } = await openSpectrum(page, { theme, noHardware: true });
    const measurements = [];
    const audit = async (rootSelector, section) => {
      await page.evaluate(() => {
        window.spectrumGuideAuditInert = [...document.querySelectorAll('[inert]')];
        window.spectrumGuideAuditInert.forEach(element => { element.inert = false; });
      });
      try {
        for (const hue of [null, 0, 120, 240]) {
          await page.evaluate(async value => {
            const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
            applyThemeHue(value);
          }, hue);
          const optionsPaint = rootSelector === '.tuner-spectrum-settings-grid';
          const result = await expectContrast(page, { rootSelector,
            reportName: `spectrum-guide-${section}-${theme}-hue-${hue}`,
            ...(optionsPaint ? { reviewedPaintOwners: {
              filter: ['.tuner-spectrum-options-header'],
              'instrument-or-native-painted-internals': ['#tuner-waterfall-speed']
            } } : {}) });
          expect(result.summary.text.checked).toBeGreaterThan(3);
          measurements.push({ section, hue, summary: result.summary });
          if (optionsPaint) await screenshot(page, `spectrum-${section}-${theme}-hue-${hue}`);
        }
      } finally {
        await page.evaluate(async () => {
          const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
          applyThemeHue(null);
          window.spectrumGuideAuditInert.forEach(element => { element.inert = true; });
          delete window.spectrumGuideAuditInert;
        });
      }
    };
    await expectOverview(page);
    await audit('.ui-setup-guide', 'overview');
    await start(page);
    await goTo(page, 'activity');
    await audit('.ui-setup-guide', 'compact-activity');
    await goTo(page, 'channel-details');
    await audit('.tuner-spectrum-cursor-popup', 'actual-channel-details');
    await goTo(page, 'listen');
    await page.mouse.move(0, 0);
    await page.evaluate(() => {
      const backdrop = document.querySelector('.ui-setup-guide-backdrop');
      window.spectrumGuideContrast = { backdrop, visibility: backdrop.style.visibility,
        inert: [...document.querySelectorAll('[inert]')] };
      backdrop.style.visibility = 'hidden';
      window.spectrumGuideContrast.inert.forEach(element => { element.inert = false; });
    });
    try {
      await audit('.spectrum-browse-workspace', 'populated-frequency-actions');
    } finally {
      await page.evaluate(() => {
        const original = window.spectrumGuideContrast;
        original.inert.forEach(element => { element.inert = true; });
        original.backdrop.style.visibility = original.visibility;
        delete window.spectrumGuideContrast;
      });
    }
    await goTo(page, 'display-settings');
    // The floating shell and filtered sticky header paint over the plots rather
    // than their logical toolbar parent. Review those pixels in the captures;
    // measure the normal settings cards, text and controls against their actual fill.
    await audit('.tuner-spectrum-settings-grid', 'actual-display-options');
    await audit('.ui-setup-guide', 'display-settings-guide');
    await goTo(page, 'plot-settings');
    await audit('.tuner-spectrum-settings-grid', 'actual-plot-settings');
    await audit('.ui-setup-guide', 'plot-settings-guide');
    await goTo(page, 'performance');
    await audit('.ui-setup-guide', 'performance-guide');
    fs.mkdirSync(evidence, { recursive: true });
    fs.writeFileSync(path.join(evidence, `contrast-summary-${theme}.json`), `${JSON.stringify(measurements, null, 2)}\n`);
    await page.keyboard.press('Escape');
    expect(effects.writes).toEqual([]);
    expectIsolated(fixture, effects);
  });
}
