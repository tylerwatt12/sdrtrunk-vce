'use strict';

const { test, expect } = require('@playwright/test');
const { inspectContrast, expectContrast } = require('./fixtures/contrast-audit.cjs');

const foundation = `
  * { box-sizing: border-box; }
  body { margin: 0; padding: 24px; background: #edf1f4; color: #20262b; font: 16px/1.45 sans-serif; }
  .ui-section, .ui-record-card, .ui-metric { margin: 0 0 12px; padding: 16px; border: 0; border-radius: 8px; }
  .ui-section { background: #fff; }
  .ui-record-card, .ui-metric { background: #f5f7f9; }
  input, select { display: block; margin: 8px 0; padding: 8px; color: #20262b; background: #fff; border: 1px solid #20262b; font: inherit; }
`;

test('contrast audit compares a standalone card with effective page paint and a nested tile with its panel', async ({ page }) => {
  await page.setContent(`<style>${foundation}</style>
    <article id="bad-card" class="ui-record-card">Account</article>
    <div id="bad-metric" class="ui-metric">12 calls</div>
    <section id="panel" class="ui-section"><div class="transparent-wrapper">
      <div id="nested-tile" class="ui-metric">12 calls</div>
    </div></section>`);
  const result = await inspectContrast(page);
  expect(result.violations.surfaces.map((item) => item.selector)).toEqual(['#bad-card', '#bad-metric']);
  for (const item of result.violations.surfaces) {
    expect(item.ratio).toBeCloseTo(1.058, 3);
    expect(item.minimum).toBe(1.10);
    expect(item.role).toBe('standalone');
  }
  expect(result.surfaces.find((item) => item.selector === '#panel')).toMatchObject({ pass: true, role: 'standalone' });
  expect(result.surfaces.find((item) => item.selector === '#nested-tile')).toMatchObject({ pass: true,
    role: 'tile', paintedParent: '#panel', minimum: 1.05, background: 'rgb(255 255 255)' });
  await expect(expectContrast(page, { recordOnly: false })).rejects.toThrow('Contrast audit failed');
  await page.locator('#bad-card, #bad-metric').evaluateAll((items) => items.forEach((item) => item.remove()));
  expect((await expectContrast(page, { recordOnly: false })).violations.surfaces).toEqual([]);
});

test('contrast audit composites translucent ink and whole-subtree opacity on both measured pixels', async ({ page }) => {
  await page.setContent(`<style>${foundation}
    body { background: #fff; }
    .group { background: rgb(0 0 0 / 10%); opacity: .5; padding: 12px; }
    #alpha { color: rgb(0 0 0 / 65%); opacity: .5; }
  </style><div class="group"><span id="alpha">Receiver status</span></div>`);
  const result = await inspectContrast(page);
  expect(result.text.find((item) => item.selector === '#alpha')).toMatchObject({
    foreground: 'rgb(205 205 205)', background: 'rgb(242 242 242)', pass: false, minimum: 4.5
  });
});

test('contrast audit includes placeholders and readonly values while reporting disabled controls as exempt', async ({ page }) => {
  await page.setContent(`<style>${foundation}
    input::placeholder { color: #aaa; opacity: 1; }
    #readonly, #static { color: #aaa; opacity: 1; }
  </style>
    <input id="placeholder" type="text" placeholder="Search aliases" aria-label="Alias search">
    <input id="readonly" type="text" value="Private field value" readonly aria-label="Read-only field">
    <input id="disabled" type="text" value="Disabled field value" disabled aria-label="Disabled field">
    <select id="static" class="ui-select-static" disabled aria-label="Read-only choice"><option>Receiver</option></select>`);
  const result = await inspectContrast(page);
  expect(result.violations.text.map((item) => [item.selector, item.source])).toEqual([
    ['#placeholder', 'placeholder'], ['#readonly', 'field-value'], ['#static', 'selected-option']
  ]);
  expect(result.text.find((item) => item.selector === '#placeholder').foreground).toBe('rgb(170 170 170)');
  expect(result.text.find((item) => item.selector === '#readonly').text).toBe('[field value]');
  expect(result.text.some((item) => item.selector === '#disabled')).toBe(false);
  expect(result.skipped).toEqual(expect.arrayContaining([
    expect.objectContaining({ selector: '#disabled', kind: 'text', reason: 'disabled' }),
    expect.objectContaining({ selector: '#static', kind: 'control', reason: 'static-read-only-display' })
  ]));
  expect(JSON.stringify(result)).not.toContain('Private field value');
});

test('contrast audit keeps compact separator rows flat and still audits their text and fields', async ({ page }) => {
  await page.setContent(`<style>${foundation}
    table, tbody, tr, td { display: block; }
    tr { background: #fff; border: 0; border-bottom: 1px solid #c9d1d8; border-radius: 0; padding: 12px; }
    tr:last-child { border-bottom: 0; }
    #faint-text { color: #aaa; }
    #faint-field { border-color: #ccc; }
  </style><section class="ui-section"><table class="ui-mobile-cards"><tbody>
    <tr id="flat-row"><td id="faint-text">Site</td></tr>
    <tr id="last-row"><td><input id="faint-field" aria-label="Channel name" value="Channel"></td></tr>
  </tbody></table></section>`);
  const result = await inspectContrast(page);
  expect(result.violations.surfaces).toEqual([]);
  expect(result.skipped.filter((item) => item.reason === 'flat-data-row-with-structural-separators')
    .map((item) => item.selector)).toEqual(['#flat-row', '#last-row']);
  expect(result.violations.text.map((item) => item.selector)).toEqual(['#faint-text']);
  expect(result.violations.controls.map((item) => item.selector)).toEqual(['#faint-field']);
});

test('contrast audit finishes finite CSS transitions without changing CSS animation state', async ({ page }) => {
  await page.setContent(`<style>${foundation}
    .ui-input { border-color: #7b8b9a; transition: border-color 30s linear, background-color 30s linear; }
    .ui-input:disabled { background: #f5f7f9; border-color: #c9d1d8; }
    @keyframes move { from { transform: translateX(0); } to { transform: translateX(4px); } }
    .animated { width: 4px; height: 4px; background: #20262b; animation: move 30s linear infinite; }
    .finite { animation-iteration-count: 1; }
  </style><input id="transition-field" class="ui-input" aria-label="Channel name" value="Channel" disabled>
    <div class="animated"></div><div class="animated finite"></div>`);
  await page.locator('#transition-field').evaluate(async (field) => {
    getComputedStyle(field).borderColor;
    field.disabled = false;
    await new Promise(requestAnimationFrame);
  });
  expect(await page.evaluate(() => document.getAnimations().filter((animation) => animation instanceof CSSTransition).length)).toBeGreaterThan(0);
  const result = await inspectContrast(page);
  expect(result.context.finishedTransitions).toBeGreaterThan(0);
  expect(result.controls.find((item) => item.selector === '#transition-field')).toMatchObject({ pass: true });
  expect(result.controls.find((item) => item.selector === '#transition-field').edges[0].color).toBe('rgb(123 139 154)');
  expect(await page.evaluate(() => document.getAnimations().filter((animation) => animation instanceof CSSAnimation)
    .map((animation) => animation.playState))).toEqual(['running', 'running']);
});

test('contrast audit exempts dual-range copy only when actual range handles confirm its disabled state', async ({ page }) => {
  await page.setContent(`<style>${foundation}.ui-dual-range-disabled { color: #aaa; }</style>
    <div id="disabled-range" class="ui-dual-range-disabled"><span id="disabled-label">Call length</span>
      <output id="disabled-output">0–60 seconds</output><input type="range" disabled><input type="range" disabled></div>
    <div id="enabled-range" class="ui-dual-range-disabled"><span id="enabled-label">Call length</span>
      <input type="range"><input type="range"></div>
    <div class="ui-dual-range-disabled"><span id="unconfirmed-label">Call length</span></div>`);
  const result = await inspectContrast(page);
  expect(result.skipped).toEqual(expect.arrayContaining([
    expect.objectContaining({ kind: 'text', selector: '#disabled-label', reason: 'disabled-composite' }),
    expect.objectContaining({ kind: 'text', selector: '#disabled-output', reason: 'disabled-composite' })
  ]));
  expect(result.violations.text.map((item) => item.selector)).toEqual(['#enabled-label', '#unconfirmed-label']);
});

test('contrast audit reports gradients and a visible sibling setup-guide dimmer as unresolved paint', async ({ page }) => {
  await page.setContent(`<style>${foundation}
    #gradient { background: linear-gradient(#fff, #edf1f4); }
    .ui-setup-guide-backdrop { position: relative; }
    .ui-setup-guide-shade { position: absolute; inset: 0; width: 100%; height: 100%; }
    .ui-setup-guide-dimmer { fill: rgb(9 18 27 / 90%); }
    .ui-setup-guide { position: relative; color: #fff; background: transparent; padding: 12px; }
  </style><article id="gradient" class="ui-metric">Receiver</article>
    <div class="ui-setup-guide-backdrop">
      <svg class="ui-setup-guide-shade" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <path class="ui-setup-guide-dimmer" d="M0 0H100V100H0Z"></path>
      </svg><div class="ui-setup-guide"><p id="guide-copy">Choose a channel</p></div>
    </div>`);
  const result = await inspectContrast(page);
  expect(result.skipped).toEqual(expect.arrayContaining([
    expect.objectContaining({ kind: 'surface', selector: '#gradient', reason: 'background-image-or-gradient', status: 'needs-review' }),
    expect.objectContaining({ kind: 'text', selector: '#guide-copy', reason: 'non-ancestor-overlay-paint', status: 'needs-review' })
  ]));
  expect(result.violations.text).toEqual([]);
  expect(result.summary.needsReview).toBeGreaterThan(0);
  await page.locator('.ui-setup-guide-shade').evaluate((shade) => { shade.style.display = 'none'; });
  const withoutPaint = await inspectContrast(page);
  expect(withoutPaint.violations.text.map((item) => item.selector)).toEqual(['#guide-copy']);
});

test('contrast audit requires explicit skip reasons and reports missing reasons as review gaps', async ({ page }) => {
  await page.setContent(`<style>${foundation}</style>
    <p id="undocumented" data-contrast-audit="skip">Receiver</p>`);
  const result = await inspectContrast(page);
  expect(result.skipped).toContainEqual(expect.objectContaining({ kind: 'text', selector: '#undocumented',
    reason: 'explicit-exception-without-reason', status: 'needs-review' }));
  await expect(expectContrast(page, { recordOnly: false })).rejects.toThrow('skip requires a reason');
  await page.locator('#undocumented').evaluate((element) => element.setAttribute('data-contrast-audit-reason', 'Test fixture exception'));
  expect((await expectContrast(page, { recordOnly: false })).summary.needsReview).toBe(0);
});

test('contrast audit rejects arbitrary gradient escapes while retaining named reviewed paint as an explicit gap', async ({ page }) => {
  await page.setContent(`<style>${foundation}
    .audio-dock-compact { background: linear-gradient(#fff, #edf1f4); padding: 12px; }
    #unexpected-card { background: linear-gradient(#f5f7f9, #edf1f4); }
  </style><div class="audio-dock-compact"><span id="reviewed-copy">Current call</span>
    <article id="unexpected-card" class="ui-record-card">Account</article></div>`);
  const result = await inspectContrast(page);
  expect(result.skipped).toContainEqual(expect.objectContaining({ selector: '#reviewed-copy',
    reason: 'background-image-or-gradient', reviewed: true, reviewOwner: '.audio-dock-compact' }));
  expect(result.skipped).toContainEqual(expect.objectContaining({ selector: '#unexpected-card', kind: 'surface',
    reason: 'background-image-or-gradient', reviewed: false, paintOwner: '#unexpected-card' }));
  await expect(expectContrast(page, { recordOnly: false })).rejects.toThrow('unreviewed paint');
  await page.locator('#unexpected-card').evaluate((element) => element.remove());
  const known = await expectContrast(page, { recordOnly: false });
  expect(known.summary.needsReview).toBeGreaterThan(0);
  expect(known.summary.unreviewedPaint).toBe(0);
  // Callers may replace the known inventory for a stricter or separately
  // reviewed fixture; no generic SVG/body/range exemption is implied.
  await expect(expectContrast(page, { recordOnly: false, reviewedPaintOwners: {} })).rejects.toThrow('unreviewed paint');
});
