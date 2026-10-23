const { test, expect } = require('@playwright/test');

async function installGuideHarness(page) {
  await page.goto('/design-system.html');
  await page.locator('.remote-support-workspace').waitFor({ state: 'attached' });
  await page.evaluate(async () => {
    const { openSetupGuide } = await import('/assets/core/setup-guide.js');
    const node = (tag, className = '', text = '') => {
      const element = document.createElement(tag);
      element.className = className;
      element.textContent = text;
      return element;
    };
    window.guideHarness = { openSetupGuide, node, errors: [], cleanups: [] };
    const openReadOnlyModal = (title, body, options) => {
      const backdrop = node('div', 'modal-backdrop');
      const dialog = node('section', `read-only-modal ${options.className}`);
      const header = node('header', 'modal-header');
      const heading = node('h2', '', title);
      heading.id = 'lifecycle-title';
      const content = node('div', 'modal-content');
      header.append(heading);
      content.append(body);
      dialog.append(header, content);
      backdrop.append(dialog);
      document.body.append(backdrop);
      return {
        dialog, ready: Promise.resolve(true), state: { backdrop, guideTargets: new Set() },
        setTitle(value) { heading.textContent = value; },
        focus(element) { element.focus(); },
        close() { options.cleanup(); backdrop.remove(); return true; }
      };
    };
    window.guideHarness.open = (steps, ui = {}, options = {}) => openSetupGuide({ node, openReadOnlyModal, ...ui }, {
      id: 'lifecycle-test', steps, onError: error => window.guideHarness.errors.push(error.message), ...options
    });
  });
}

test('shared guide awaits preparation and locks navigation until the real target is ready', async ({ page }) => {
  await installGuideHarness(page);
  await page.evaluate(() => {
    const harness = window.guideHarness;
    harness.presentation = harness.open([
      { id: 'first', title: 'First', paragraphs: ['First view'], prepare: () =>
        new Promise(resolve => { harness.resolve = () => resolve(() => harness.cleanups.push('first')); }) },
      { id: 'second', title: 'Second', paragraphs: ['Second view'] }
    ]);
  });
  const guide = page.locator('.ui-setup-guide[data-guide-id="lifecycle-test"], .ui-setup-guide[aria-busy="true"]');
  await expect(guide).toBeVisible();
  await expect(guide.getByRole('button', { name: 'Next' })).toBeDisabled();
  await page.evaluate(() => window.guideHarness.presentation.next());
  await expect(page.locator('.ui-setup-guide h2')).toHaveText('First');
  await page.evaluate(() => window.guideHarness.resolve());
  await expect(guide.getByRole('button', { name: 'Next' })).toBeEnabled();
  await expect(guide).toHaveAttribute('data-guide-step', 'first');
  await guide.getByRole('button', { name: 'Next' }).click();
  await expect(guide).toHaveAttribute('data-guide-step', 'second');
  expect(await page.evaluate(() => window.guideHarness.cleanups)).toEqual(['first']);
  await page.evaluate(() => window.guideHarness.presentation.close());
  expect(await page.evaluate(() => window.guideHarness.errors)).toEqual([]);
});

test('closing a pending guide restores its real popover and cleans late preparation without reopening', async ({ page }) => {
  await installGuideHarness(page);
  await page.evaluate(() => {
    const harness = window.guideHarness;
    const panel = harness.node('div', 'ui-popover', 'Real popover content');
    panel.setAttribute('popover', 'manual');
    panel.style.left = '120px';
    panel.style.top = '150px';
    document.body.append(panel);
    panel.showPopover();
    harness.panel = panel;
    harness.presentation = harness.open([{ id: 'pending', title: 'Pending',
      prepare: ({ registerLayer, signal }) => {
        registerLayer(panel);
        harness.signal = signal;
        return new Promise(resolve => { harness.resolve = () => resolve(() => harness.cleanups.push('late')); });
      }
    }]);
  });
  await expect(page.locator('.ui-popover.ui-setup-guide-layer')).toBeVisible();
  expect(await page.evaluate(() => ({
    topLayer: window.guideHarness.panel.matches(':popover-open'),
    inert: window.guideHarness.panel.inert
  }))).toEqual({ topLayer: false, inert: true });
  await page.evaluate(() => window.guideHarness.presentation.close());
  await expect(page.locator('.ui-setup-guide')).toHaveCount(0);
  expect(await page.evaluate(() => ({
    aborted: window.guideHarness.signal.aborted,
    popover: window.guideHarness.panel.getAttribute('popover'),
    inert: window.guideHarness.panel.inert,
    className: window.guideHarness.panel.className,
    left: window.guideHarness.panel.style.left
  }))).toEqual({ aborted: true, popover: 'manual', inert: false, className: 'ui-popover', left: '120px' });
  await page.evaluate(() => window.guideHarness.resolve());
  expect(await page.evaluate(() => window.guideHarness.cleanups)).toEqual(['late']);
  expect(await page.evaluate(() => window.guideHarness.errors)).toEqual([]);
  await expect(page.locator('.ui-setup-guide')).toHaveCount(0);
});

for (const theme of ['light', 'dark']) {
  test(`page overview precedes numbered steps without changing page state in ${theme}`, async ({ page }) => {
    await installGuideHarness(page);
    await page.evaluate(theme => {
      document.documentElement.dataset.theme = theme;
      const harness = window.guideHarness;
      const target = harness.node('button', 'ui-button', 'Actual opening button');
      document.body.prepend(target);
      harness.sampleActivations = [];
      harness.preparations = [];
      harness.presentation = harness.open([
        { id: 'overview', overview: true, title: 'Choose which calls you hear',
          paragraphs: ['Scan Lists choose which received calls reach your player.'],
          // Overview presentation stays pure even if future metadata supplies hooks.
          samples: [{ id: 'unexpected' }], targets: () => [target],
          prepare: () => harness.preparations.push('overview') },
        { id: 'opener', title: 'Open the listening group', paragraphs: ['Review its members here.'],
          targets: () => [target], samples: [{ id: 'members' }],
          prepare: () => { harness.preparations.push('opener'); return () => harness.cleanups.push('opener'); } },
        { id: 'members', title: 'Follow related traffic', paragraphs: ['Bring related talkgroups together.'] }
      ], { guideSampleData: { activate(samples) {
        harness.sampleActivations.push(samples);
        return () => harness.cleanups.push('samples');
      } } });
    }, theme);
    const guide = page.locator('.ui-setup-guide');
    const counter = guide.locator('.ui-setup-guide-counter');
    await expect(guide).toHaveAttribute('data-guide-step', 'overview');
    await expect(counter).toHaveText('Overview');
    await expect(guide.locator('.ui-setup-guide-dots')).toBeHidden();
    await expect(guide.getByRole('button', { name: 'Back', exact: true })).toBeHidden();
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(0);
    await expect(page.locator('.ui-setup-guide-pointer')).toBeHidden();
    expect(await page.evaluate(() => ({ samples: window.guideHarness.sampleActivations,
      preparations: window.guideHarness.preparations }))).toEqual({ samples: [], preparations: [] });
    const bounds = await guide.boundingBox();
    const viewport = page.viewportSize();
    expect(Math.abs(bounds.x + bounds.width / 2 - viewport.width / 2)).toBeLessThan(2);
    expect(Math.abs(bounds.y + bounds.height / 2 - viewport.height / 2)).toBeLessThan(2);
    await guide.getByRole('button', { name: 'Start tour', exact: true }).click();
    await expect(guide).toHaveAttribute('data-guide-step', 'opener');
    await expect(counter).toHaveText('Step 1 of 2');
    await expect(guide.locator('.ui-setup-guide-dots')).toBeVisible();
    await expect(guide.locator('.ui-setup-guide-dot')).toHaveCount(2);
    await expect(guide.locator('.ui-setup-guide-dot').nth(0)).toHaveClass(/is-current/);
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(1);
    await guide.getByRole('button', { name: 'Back', exact: true }).click();
    await expect(counter).toHaveText('Overview');
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(0);
    expect(await page.evaluate(() => window.guideHarness.cleanups)).toEqual(['opener', 'samples']);
    await guide.getByRole('button', { name: 'Start tour', exact: true }).click();
    await guide.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(counter).toHaveText('Step 2 of 2');
    await expect(guide.locator('.ui-setup-guide-dot').nth(0)).toHaveClass(/is-complete/);
    await expect(guide.locator('.ui-setup-guide-dot').nth(1)).toHaveClass(/is-current/);
    await guide.getByRole('button', { name: 'Done', exact: true }).click();
    await expect(guide).toHaveCount(0);
    expect(await page.evaluate(() => window.guideHarness.errors)).toEqual([]);
  });
}

test('a pending page walkthrough keeps its custom welcome action after the overview', async ({ page }) => {
  await installGuideHarness(page);
  await page.evaluate(() => {
    const harness = window.guideHarness;
    harness.completions = 0;
    harness.presentation = harness.open([
      { id: 'overview', overview: true, title: 'Follow your radio systems',
        paragraphs: ['Explore activity over time.'], nextLabel: 'Continue' },
      { id: 'coming-soon', title: 'Guide coming soon', paragraphs: ['Take the welcome tour.'],
        nextLabel: 'Take the welcome tour' }
    ], {}, { onComplete: () => { harness.completions += 1; } });
  });
  const guide = page.locator('.ui-setup-guide');
  await expect(guide.locator('.ui-setup-guide-counter')).toHaveText('Overview');
  await guide.getByRole('button', { name: 'Continue', exact: true }).click();
  await expect(guide.locator('.ui-setup-guide-counter')).toHaveText('Step 1 of 1');
  await guide.getByRole('button', { name: 'Take the welcome tour', exact: true }).click();
  await expect(guide).toHaveCount(0);
  expect(await page.evaluate(() => window.guideHarness.completions)).toBe(1);
});

for (const viewport of [{ width: 1280, height: 720 }, { width: 1440, height: 1000 }]) {
  test(`central concept diagrams keep navigation visible and release their wide layout at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installGuideHarness(page);
    await page.evaluate(() => {
      const harness = window.guideHarness;
      harness.presentation = harness.open([
        { id: 'overview', overview: true, title: 'Choose which calls you hear', diagram: 'scan-lists',
          paragraphs: ['Combine related calls from your radio systems in Scan Lists.'] },
        { id: 'opener', title: 'Review your listening group', paragraphs: ['Open its members here.'] }
      ]);
    });
    const guide = page.locator('.ui-setup-guide');
    const diagram = guide.locator('[data-guide-diagram="scan-lists"]');
    await expect(diagram.getByRole('img', { name: 'County and city aliases grouped into Scan Lists' })).toBeVisible();
    await expect(guide).toHaveClass(/ui-setup-guide--diagram/);
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(0);
    const inspectLayout = () => page.evaluate(() => {
      const guide = document.querySelector('.ui-setup-guide');
      const footer = guide.querySelector('.ui-setup-guide-footer').getBoundingClientRect();
      const copy = guide.querySelector('.ui-setup-guide-copy');
      return { footerBottom: footer.bottom, viewportHeight: innerHeight,
        width: guide.getBoundingClientRect().width, scrollHeight: copy.scrollHeight, clientHeight: copy.clientHeight };
    });
    const overview = await inspectLayout();
    expect(overview.width).toBeGreaterThan(1100);
    expect(overview.footerBottom).toBeLessThanOrEqual(viewport.height - 20);
    if (viewport.height === 720) expect(overview.scrollHeight).toBeGreaterThan(overview.clientHeight);
    await guide.locator('.ui-setup-guide-copy').evaluate(copy => { copy.scrollTop = copy.scrollHeight; });
    expect((await inspectLayout()).footerBottom).toBeLessThanOrEqual(viewport.height - 20);
    await guide.getByRole('button', { name: 'Start tour', exact: true }).click();
    await expect(diagram).toHaveCount(0);
    await expect(guide).not.toHaveClass(/ui-setup-guide--diagram/);
    expect((await inspectLayout()).width).toBeLessThan(600);
    await guide.getByRole('button', { name: 'Back', exact: true }).click();
    await expect(diagram).toBeVisible();
    expect((await inspectLayout()).width).toBeGreaterThan(1100);
    await page.evaluate(() => window.guideHarness.presentation.close());
    await expect(page.locator('[data-guide-diagram]')).toHaveCount(0);
    expect(await page.evaluate(() => window.guideHarness.errors)).toEqual([]);
  });
}

test('central diagrams scope SVG references for simultaneous renderings and ignore unknown artwork', async ({ page }) => {
  await installGuideHarness(page);
  const result = await page.evaluate(async () => {
    const { createGuideDiagram } = await import('/assets/guides/diagrams.js');
    const first = createGuideDiagram('scan-lists', { document, idPrefix: 'first-guide' });
    const second = createGuideDiagram('scan-lists', { document, idPrefix: 'second-guide' });
    document.body.append(first, second);
    const ids = [...document.querySelectorAll('[data-guide-diagram] [id]')].map(element => element.id);
    const referencesExist = [...document.querySelectorAll('[data-guide-diagram] [marker-end]')]
      .every(path => document.getElementById(path.getAttribute('marker-end').match(/#([^)]*)/)[1]));
    const result = { count: ids.length, unique: new Set(ids).size, referencesExist,
      unknown: createGuideDiagram('unknown-diagram', { document, idPrefix: 'missing' }) };
    first.remove();
    second.remove();
    return result;
  });
  expect(result.count).toBeGreaterThan(0);
  expect(result.unique).toBe(result.count);
  expect(result.referencesExist).toBe(true);
  expect(result.unknown).toBeNull();
});
