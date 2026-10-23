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
    window.guideHarness.open = steps => openSetupGuide({ node, openReadOnlyModal }, {
      id: 'lifecycle-test', steps, onError: error => window.guideHarness.errors.push(error.message)
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
