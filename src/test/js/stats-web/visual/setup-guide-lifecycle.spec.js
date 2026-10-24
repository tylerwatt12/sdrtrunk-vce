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
      const state = { backdrop, guideTargets: new Set() };
      window.guideHarness.modal = { dialog, state };
      return {
        dialog, ready: Promise.resolve(true), state,
        setTitle(value) { heading.textContent = value; },
        focus(element) { element.focus(); },
        close() { options.cleanup(); backdrop.remove(); options.onDismiss?.(); return true; }
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

test('shared guide anchors an instrument cursor in viewport space and restores its local positioning', async ({ page }) => {
  await installGuideHarness(page);
  await page.evaluate(() => {
    const harness = window.guideHarness;
    const instrument = harness.node('div');
    instrument.style.cssText = 'position:fixed;left:330px;top:170px;width:300px;height:180px';
    const anchor = harness.node('button', 'ui-button', 'Channel marker');
    const cursor = harness.node('div', 'ui-popover', 'Actual instrument detail');
    cursor.style.cssText = 'position:absolute;left:30px;top:40px;width:200px;height:60px';
    instrument.append(anchor, cursor);
    document.body.append(instrument);
    harness.panel = cursor;
    harness.originalRectangle = cursor.getBoundingClientRect().toJSON();
    harness.presentation = harness.open([{ id: 'cursor', title: 'Channel details', targets: () => [cursor],
      prepare: ({ registerLayer }) => registerLayer(cursor, { anchor }) }]);
  });
  await expect(page.locator('.ui-setup-guide[data-guide-step="cursor"]')).toBeVisible();
  expect(await page.evaluate(() => {
    const harness = window.guideHarness;
    const rectangle = harness.panel.getBoundingClientRect();
    return { left: rectangle.left, top: rectangle.top,
      expectedLeft: harness.originalRectangle.left, expectedTop: harness.originalRectangle.top };
  })).toEqual({ left: 360, top: 210, expectedLeft: 360, expectedTop: 210 });
  await page.evaluate(() => window.guideHarness.presentation.close());
  expect(await page.evaluate(() => {
    const { panel, errors } = window.guideHarness;
    return { position: panel.style.position, left: panel.style.left, top: panel.style.top,
      registered: panel.classList.contains('ui-setup-guide-layer'), inert: panel.inert, errors };
  })).toEqual({ position: 'absolute', left: '30px', top: '40px', registered: false, inert: false, errors: [] });
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

for (const viewport of [
  { width: 1280, height: 720 },
  { width: 1440, height: 1000 },
  { width: 390, height: 844 }
]) {
  for (const theme of ['light', 'dark']) {
    test(`all authored guide counts keep Back and Next together at ${viewport.width} in ${theme}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await installGuideHarness(page);
      const layouts = await page.evaluate(async theme => {
        document.documentElement.dataset.theme = theme;
        const { createGuideDefinition, pageGuideTemplates } = await import('/assets/guides/catalog.js');
        const ids = ['welcome', ...Object.values(pageGuideTemplates).filter(value => value.authored).map(value => value.id)];
        const harness = window.guideHarness;
        const results = [];
        const ready = async () => {
          // The production engine awaits each preparation, even when this footer-only
          // harness leaves the real page's sample-data preparation out of the fixture.
          for (let attempt = 0; attempt < 10; attempt += 1) {
            await Promise.resolve();
            if (!document.querySelector('.ui-setup-guide[aria-busy="true"]')) return;
          }
          throw new Error('The guide did not finish preparing its footer');
        };
        const rectangle = element => {
          const { left, top, right, bottom, width, height } = element.getBoundingClientRect();
          return { left, top, right, bottom, width, height };
        };
        for (const id of ids) {
          const definition = createGuideDefinition(id, { documentValue: document, canView: () => true, canAccess: () => true });
          const numberedCount = definition.steps.filter(step => !step.overview).length;
          for (const variant of ['standard', 'compact']) {
            // Counts, text, and action labels come from every authored guide. Only
            // the compact count case is forced, using Spectrum's Next/Finish
            // labels. Wide concepts and Welcome's illustration remain their
            // authored production compositions in the standard cases.
            const steps = definition.steps.map(({ id, title, paragraphs, items, hint, nextLabel, overview, illustration, diagram }, index) => ({
              id, title, paragraphs, items, hint, overview,
              nextLabel: variant === 'compact' && !overview ? (index === definition.steps.length - 1 ? 'Finish' : undefined) : nextLabel,
              illustration: variant === 'standard' && illustration,
              diagram: variant === 'standard' ? diagram : undefined,
              compact: variant === 'compact'
            }));
            harness.presentation = harness.open(steps);
            await ready();
            // An overview has a single action. Inspect the first numbered footer
            // that offers Back, and the final footer's actual completion label.
            const positions = ['opening', 'first', 'last'];
            for (const position of positions) {
              if (position === 'first') {
                harness.presentation.next();
                await ready();
              }
              if (position === 'last') {
                for (let index = 2; index < steps.length; index += 1) {
                  harness.presentation.next();
                  await ready();
                }
              }
              const guide = document.querySelector('.ui-setup-guide');
              const footer = guide.querySelector('.ui-setup-guide-footer');
              const progress = guide.querySelector('.ui-setup-guide-progress');
              const actions = footer.querySelector('.ui-action-row');
              const [back, next] = actions.querySelectorAll('button');
              const dots = [...guide.querySelectorAll('.ui-setup-guide-dot')];
              results.push({ id, variant, position, numberedCount,
                counter: guide.querySelector('.ui-setup-guide-counter').textContent,
                nextLabel: next.textContent.trim(), backHidden: back.hidden, dotsHidden: guide.querySelector('.ui-setup-guide-dots').hidden,
                guide: rectangle(guide), footer: rectangle(footer), progress: rectangle(progress),
                actions: rectangle(actions), back: rectangle(back), next: rectangle(next),
                dots: dots.map(rectangle), footerPaddingRight: parseFloat(getComputedStyle(footer).paddingRight),
                footerScrollWidth: footer.scrollWidth, footerClientWidth: footer.clientWidth });
            }
            harness.presentation.close();
          }
        }
        return { results, errors: harness.errors, ids };
      }, theme);
      expect(layouts.ids).toEqual(expect.arrayContaining([
        'welcome', 'channel-setup', 'aliases', 'scan-lists', 'radioreference', 'streaming', 'tuners', 'tuner-spectrum'
      ]));
      expect(layouts.errors).toEqual([]);
      for (const layout of layouts.results) {
        const detail = `${layout.id}/${layout.variant}/${layout.position} (${layout.counter}, ${layout.nextLabel})`;
        expect(layout.backHidden, detail).toBe(layout.position === 'opening');
        if (!layout.backHidden) {
          expect(Math.abs(layout.back.top - layout.next.top), detail).toBeLessThan(1);
          expect(layout.next.left, detail).toBeGreaterThanOrEqual(layout.back.right + 1);
        }
        expect(layout.footerScrollWidth, detail).toBeLessThanOrEqual(layout.footerClientWidth + 1);
        expect(Math.abs(layout.actions.right - (layout.footer.right - layout.footerPaddingRight)), detail).toBeLessThan(1);
        for (const box of [layout.footer, layout.progress, layout.actions, ...(!layout.backHidden ? [layout.back] : []), layout.next,
          ...(!layout.dotsHidden ? layout.dots : [])]) {
          expect(box.left, detail).toBeGreaterThanOrEqual(layout.guide.left - 1);
          expect(box.right, detail).toBeLessThanOrEqual(layout.guide.right + 1);
          expect(box.top, detail).toBeGreaterThanOrEqual(layout.guide.top - 1);
          expect(box.bottom, detail).toBeLessThanOrEqual(layout.guide.bottom + 1);
        }
        expect(layout.guide.left, detail).toBeGreaterThanOrEqual(0);
        expect(layout.guide.right, detail).toBeLessThanOrEqual(viewport.width);
        expect(layout.guide.top, detail).toBeGreaterThanOrEqual(0);
        expect(layout.guide.bottom, detail).toBeLessThanOrEqual(viewport.height);
        const overlapWidth = Math.max(0, Math.min(layout.progress.right, layout.actions.right) - Math.max(layout.progress.left, layout.actions.left));
        const overlapHeight = Math.max(0, Math.min(layout.progress.bottom, layout.actions.bottom) - Math.max(layout.progress.top, layout.actions.top));
        expect(overlapWidth * overlapHeight, detail).toBeLessThan(1);
        expect(layout.dots, detail).toHaveLength(layout.numberedCount);
        for (const dot of layout.dotsHidden ? [] : layout.dots) {
          expect(dot.width, detail).toBeGreaterThan(0);
          expect(dot.height, detail).toBeGreaterThan(0);
        }
        if (layout.position === 'last') expect(layout.counter, detail).toBe(`Step ${layout.numberedCount} of ${layout.numberedCount}`);
      }
    });
  }
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


test('only opted-in guide dialogs keep their centered production geometry', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await installGuideHarness(page);
  await page.evaluate(() => {
    const harness = window.guideHarness;
    const step = placement => ({ id: placement, compact: true, title: 'Actual dialog controls',
      paragraphs: ['Inspect the existing controls.'], targets: () => [harness.targetButton],
      prepare() {
        const backdrop = harness.node('div', 'modal-backdrop ui-setup-guide-underlay');
        if (placement === 'center') backdrop.classList.add('ui-setup-guide-underlay-centered');
        const dialog = harness.node('section', 'read-only-modal modal-size-small');
        const header = harness.node('header', 'modal-header');
        header.append(harness.node('h2', '', 'Original dialog'));
        const body = harness.node('div', 'modal-content');
        harness.targetButton = harness.node('button', 'ui-button ui-button-secondary', 'Actual opening control');
        body.append(harness.targetButton);
        dialog.append(header, body); backdrop.append(dialog); backdrop.inert = true;
        harness.modal.state.backdrop.before(backdrop);
        backdrop.dataset.testGuideTarget = placement;
        const target = { dialog, backdrop, guideTargetPlacement: placement === 'center' ? 'center' : null };
        harness.modal.state.guideTargets.add(target);
        return () => { harness.modal.state.guideTargets.delete(target); backdrop.remove(); };
      }
    });
    harness.presentation = harness.open([step('default'), step('center')]);
  });
  const coach = page.locator('.ui-setup-guide');
  const target = page.locator('[data-test-guide-target] .read-only-modal');
  await expect(coach).toHaveAttribute('data-guide-step', 'default');
  await expect.poll(() => target.evaluate(element => element.style.maxWidth)).not.toBe('');
  expect((await target.boundingBox()).x).toBeCloseTo(24, 0);
  expect(await target.locator('..').evaluate(element => element.style.getPropertyValue('--guide-target-reserved-width'))).not.toBe('');
  await coach.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(coach).toHaveAttribute('data-guide-step', 'center');
  const box = await target.boundingBox();
  expect(box.width).toBeCloseTo(540, 0);
  expect(box.x + box.width / 2).toBeCloseTo(640, 0);
  expect(box.y + box.height / 2).toBeCloseTo(360, 0);
  expect(await target.evaluate(element => element.style.maxWidth)).toBe('');
  expect(await target.locator('..').evaluate(element => element.style.getPropertyValue('--guide-target-reserved-width'))).toBe('');
  const popup = await coach.boundingBox();
  expect(Math.min(box.x + box.width, popup.x + popup.width) <= Math.max(box.x, popup.x) + 1 ||
    Math.min(box.y + box.height, popup.y + popup.height) <= Math.max(box.y, popup.y) + 1).toBe(true);
  await page.evaluate(() => window.guideHarness.presentation.close());
  await expect(page.locator('[data-test-guide-target], .ui-setup-guide')).toHaveCount(0);
  expect(await page.evaluate(() => window.guideHarness.errors)).toEqual([]);
});

for (const theme of ['light', 'dark']) {
  test(`overview chapter choices use shared buttons and close before selecting in ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    await installGuideHarness(page);
    await page.evaluate(async theme => {
      document.documentElement.dataset.theme = theme;
      const harness = window.guideHarness;
      const { createGuideDefinition } = await import('/assets/guides/catalog.js');
      const definition = createGuideDefinition('admin', { adminTab: 'health' });
      harness.choices = [];
      harness.dismissals = 0;
      harness.presentation = harness.open(definition.steps, {}, {
        onChoice: id => harness.choices.push({ id, closed: !harness.presentation.isOpen() }),
        onDismiss: () => { harness.dismissals += 1; }
      });
    }, theme);
    const guide = page.locator('.ui-setup-guide');
    await expect(guide).toHaveClass(/ui-setup-guide--choices/);
    await expect(guide.locator('[data-guide-choice]')).toHaveCount(19);
    await expect(guide.locator('.ui-setup-guide-choice-group')).toHaveCount(7);
    await expect(guide.getByRole('button', { name: 'Receiver status', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.ui-setup-guide-highlight')).toHaveCount(0);
    await expect(guide.getByRole('button', { name: 'Start tour', exact: true })).toBeInViewport();
    const size = await guide.boundingBox();
    expect(size.width).toBeGreaterThan(800);
    expect(size.height).toBeLessThanOrEqual(672);
    const choice = guide.getByRole('button', { name: 'Page access', exact: true });
    await choice.scrollIntoViewIfNeeded();
    await expect(choice).toBeVisible();
    await choice.focus();
    await page.keyboard.press('Enter');
    await expect(guide).toHaveCount(0);
    expect(await page.evaluate(() => ({ choices: window.guideHarness.choices,
      dismissals: window.guideHarness.dismissals, errors: window.guideHarness.errors })))
      .toEqual({ choices: [{ id: 'access', closed: true }], dismissals: 0, errors: [] });
  });
}

test('chapter chooser releases its wide scrollable layout before the opening lesson', async ({ page }) => {
  await installGuideHarness(page);
  await page.evaluate(async () => {
    const harness = window.guideHarness;
    const { createGuideDefinition } = await import('/assets/guides/catalog.js');
    harness.presentation = harness.open(createGuideDefinition('admin', { adminTab: 'computer' }).steps,
      {}, { onChoice() {} });
  });
  const guide = page.locator('.ui-setup-guide');
  await expect(guide).toHaveClass(/ui-setup-guide--choices/);
  await guide.getByRole('button', { name: 'Start tour', exact: true }).click();
  await expect(guide).toHaveAttribute('data-guide-step', 'computer-open');
  await expect(guide).not.toHaveClass(/ui-setup-guide--choices/);
  await expect(guide.locator('[data-guide-choice]')).toHaveCount(0);
  const size = await guide.boundingBox();
  expect(size.width).toBeLessThan(500);
  await page.evaluate(() => window.guideHarness.presentation.close());
  expect(await page.evaluate(() => window.guideHarness.errors)).toEqual([]);
});


test('a chosen chapter starts at its opener and Back returns to the common overview', async ({ page }) => {
  await installGuideHarness(page);
  await page.evaluate(async () => {
    const harness = window.guideHarness;
    const { createGuideDefinition } = await import('/assets/guides/catalog.js');
    harness.presentation = harness.open(createGuideDefinition('admin', { adminTab: 'recordings' }).steps,
      {}, { initialStep: 1, onChoice() {} });
  });
  const guide = page.locator('.ui-setup-guide');
  await expect(guide).toHaveAttribute('data-guide-step', 'recordings-open');
  await expect(guide.locator('.ui-setup-guide-counter')).toHaveText('Step 1 of 3');
  await guide.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(guide).toHaveAttribute('data-guide-step', 'overview');
  await expect(guide.locator('[data-guide-choice]')).toHaveCount(19);
  await page.evaluate(() => window.guideHarness.presentation.close());
  expect(await page.evaluate(() => window.guideHarness.errors)).toEqual([]);
});
