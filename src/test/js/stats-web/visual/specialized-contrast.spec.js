'use strict';

const { expect, test } = require('@playwright/test');

// The general audit deliberately reports gradients, translucent instrument
// paint and native internals for review. These two fixed compositions need
// measured bounds as well: a solid CSS color cannot describe their backing.
async function measureSpecializedPaint(page, palettes) {
  return page.evaluate((palettes) => {
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    const color = (value) => {
      if (!CSS.supports('color', value)) throw new Error(`Unsupported test color: ${value}`);
      context.clearRect(0, 0, 1, 1);
      context.fillStyle = value;
      context.fillRect(0, 0, 1, 1);
      return [...context.getImageData(0, 0, 1, 1).data].map((channel) => channel / 255);
    };
    const over = (front, back) => front.slice(0, 3).map((channel, index) =>
      channel * front[3] + back[index] * (1 - front[3]));
    const luminance = (value) => value.slice(0, 3).reduce((sum, channel, index) => sum +
      [0.2126, 0.7152, 0.0722][index] * (channel <= 0.04045 ? channel / 12.92 :
        ((channel + 0.055) / 1.055) ** 2.4), 0);
    const contrast = (front, back) => {
      const levels = [luminance(front), luminance(back)].sort((a, b) => a - b);
      return (levels[1] + 0.05) / (levels[0] + 0.05);
    };
    const colorsIn = (value) => {
      const terms = [];
      const start = /\b(?:rgba?|oklch|color(?:-mix)?)\(/g;
      let match;
      while ((match = start.exec(value))) {
        let depth = 1;
        let end = start.lastIndex;
        while (depth && end < value.length) {
          if (value[end] === '(') depth += 1;
          if (value[end] === ')') depth -= 1;
          end += 1;
        }
        terms.push(color(value.slice(match.index, end)));
        start.lastIndex = end;
      }
      if (!terms.length) throw new Error(`No gradient stops in ${value}`);
      return terms;
    };
    const root = document.documentElement;
    const input = document.querySelector('[data-specialized-access] input');
    const card = input.closest('.access-landing-card');
    const access = card.closest('.access-landing');
    const stage = document.querySelector('[data-specialized-network] .network-visualizer-stage');
    const results = [];
    for (const hue of palettes) {
      if (hue === null) {
        root.removeAttribute('data-custom-hue');
        root.style.removeProperty('--theme-hue');
      } else {
        root.setAttribute('data-custom-hue', '');
        root.style.setProperty('--theme-hue', String(hue));
      }
      const inputStyle = getComputedStyle(input);
      const sceneStops = colorsIn(getComputedStyle(access).backgroundImage);
      const panelStops = colorsIn(getComputedStyle(card).backgroundImage);
      // Bound translucent panel stops over black and white canvas pixels.
      // A gradient between these endpoints cannot create a brighter backing.
      const accessBacking = [color(inputStyle.backgroundColor), ...sceneStops,
        ...panelStops.flatMap((stop) => [[0, 0, 0], [1, 1, 1]].map((back) => over(stop, back)))];
      const accessBorder = Math.min(...accessBacking.map((back) =>
        contrast(color(inputStyle.borderTopColor), back)));
      const networkText = [...stage.querySelectorAll('[data-specialized-text]')].map((element) => {
        const text = getComputedStyle(element);
        const owner = element.closest('[data-specialized-backing]');
        const fill = color(getComputedStyle(owner).backgroundColor);
        return { name: element.getAttribute('data-specialized-text'),
          ratio: Math.min(...[[0, 0, 0], [1, 1, 1]].map((back) =>
            contrast(color(text.color), over(fill, back)))) };
      });
      results.push({ hue, accessBorder, networkText });
    }
    return results;
  }, palettes);
}

async function openSpecializedPaint(page, theme) {
  await page.goto(`/design-system.html?view=health&theme=${theme}`);
  // A synchronous palette sweep measures settled semantic colors. Otherwise
  // the field border can retain its previous hue while its backing updates.
  await page.addStyleTag({ content: '[data-specialized-access] *, [data-specialized-network] * { transition: none !important; }' });
  await page.evaluate(() => {
    const host = document.createElement('div');
    host.innerHTML = `
      <section class="access-landing" data-specialized-access>
        <div class="access-landing-layout"><div class="access-landing-card ui-surface">
          <form class="admin-form login-form access-login-form">
            <label class="admin-form-field ui-field"><span class="ui-field-label">Username</span>
              <input class="ui-input" aria-label="Username" autocomplete="username"></label>
          </form>
        </div></div>
      </section>
      <section class="network-visualizer-layout" data-specialized-network>
        <div class="network-visualizer-stage">
          <div class="network-visualizer-scope-title" data-specialized-backing>
            <a class="ui-link-text" href="#system">Regional P25</a>
            <div class="muted" data-specialized-text="system scope identity">00001-001</div>
          </div>
          <div class="network-visualizer-scope-title" data-specialized-backing>
            <strong>Dispatch</strong>
            <div class="muted" data-specialized-text="talkgroup scope context">System:
              <a class="ui-link-text" href="#system" data-specialized-text="talkgroup context link">Regional P25</a> · 00001-001
            </div>
          </div>
          <button type="button" class="network-visualizer-label network-visualizer-label-text" data-type="system" data-specialized-backing data-specialized-text="normal">System</button>
          <span class="network-visualizer-label network-visualizer-label-text" data-type="radio" data-signal="emergency" data-specialized-backing data-specialized-text="emergency">Emergency</span>
          <span class="network-visualizer-label network-visualizer-label-text" data-type="radio" data-signal="denial" data-specialized-backing data-specialized-text="denial">Denied</span>
          <span class="network-visualizer-label network-visualizer-label-text" data-type="radio" data-signal="movement" data-specialized-backing data-specialized-text="movement">Moved</span>
          <span class="network-visualizer-label network-visualizer-label-text" data-type="radio" data-signal="call" data-specialized-backing data-specialized-text="call">Call</span>
          <aside class="network-visualizer-panel network-visualizer-events ui-surface" data-specialized-backing>
            <ul class="network-visualizer-event-list">
              <li class="network-visualizer-action" data-category="check"><div class="network-visualizer-action-heading"><strong data-specialized-text="check">Radio check</strong></div></li>
              <li class="network-visualizer-action" data-category="page"><div class="network-visualizer-action-heading"><strong data-specialized-text="page">Page</strong></div></li>
            </ul>
          </aside>
          <div class="network-visualizer-legend" data-specialized-backing><span data-specialized-text="legend">Activity</span></div>
          <div class="network-visualizer-manual-guide" data-specialized-backing data-specialized-text="manual guide">Move the camera</div>
          <div class="network-visualizer-auto-focus" data-specialized-backing><span class="network-visualizer-auto-focus-kicker" data-specialized-text="focus">Activity</span></div>
          <div class="network-visualizer-notice" data-specialized-backing data-specialized-text="notice">Connection interrupted</div>
        </div>
      </section>`;
    document.body.append(host);
  });
}

for (const theme of ['light', 'dark']) {
  test(`access boundaries and fixed-dark scene text retain contrast across ${theme} palettes`, async ({ page }) => {
    await openSpecializedPaint(page, theme);
    const results = await measureSpecializedPaint(page, [null, ...Array.from({ length: 360 }, (_, hue) => hue)]);
    for (const { hue, accessBorder, networkText } of results) {
      expect(accessBorder, `${theme} hue ${hue}: access field boundary`).toBeGreaterThanOrEqual(3);
      for (const { name, ratio } of networkText) {
        expect(ratio, `${theme} hue ${hue}: network ${name}`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  test(`access field hover and focus preserve the boundary in ${theme}`, async ({ page }) => {
    await openSpecializedPaint(page, theme);
    const field = page.locator('[data-specialized-access] input');
    await field.hover();
    expect((await measureSpecializedPaint(page, [null]))[0].accessBorder).toBeGreaterThanOrEqual(3);
    await field.focus();
    await expect(field).toBeFocused();
    expect((await measureSpecializedPaint(page, [null]))[0].accessBorder).toBeGreaterThanOrEqual(3);
  });
}
