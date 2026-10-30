const { expect, test } = require('@playwright/test');
const { inspectContrast, expectContrast } = require('./fixtures/contrast-audit.cjs');
const { expectNoHorizontalOverflow } = require('./fixtures/metric-geometry.cjs');

const presentations = [
  { name: 'light-desktop', theme: 'light', viewport: { width: 1280, height: 900 } },
  { name: 'dark-desktop', theme: 'dark', viewport: { width: 1280, height: 900 } },
  { name: 'light-phone', theme: 'light', viewport: { width: 390, height: 844 } },
  { name: 'dark-phone', theme: 'dark', viewport: { width: 390, height: 844 } }
];

async function auditSharedPlayer(page, selector, name, testInfo) {
  const audits = [{ appearance: 'default', result: await expectContrast(page, { rootSelector: selector }) }];
  for (const hue of [0, 120, 280]) {
    await page.evaluate(async value => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
      applyThemeHue(value);
    }, hue);
    audits.push({ appearance: `hue-${hue}`, result: await expectContrast(page, { rootSelector: selector }) });
  }
  await page.evaluate(async () => {
    const { applyThemeHue } = await import('/assets/core/theme.js?v=2');
    applyThemeHue(null);
  });
  // Preserve unresolved gradient/instrument paint for the human style review.
  await testInfo.attach(`${name}-contrast`, { body: JSON.stringify(audits), contentType: 'application/json' });
}

// The percentage fill is painted behind the label/output with ::before.
// Check both actual possible solid backings rather than call that paint a pass.
async function auditVolumeFill(page, testInfo, appearance = 'default') {
  const inspection = await inspectContrast(page, { rootSelector: '#scanner-volume-popover' });
  const paints = await page.locator('#scanner-volume-popover .ui-audio-volume-fill').evaluate(fill => {
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d');
    const rgba = color => {
      context.clearRect(0, 0, 1, 1); context.fillStyle = color; context.fillRect(0, 0, 1, 1);
      return [...context.getImageData(0, 0, 1, 1).data].map(value => value / 255);
    };
    const over = (front, back) => front.slice(0, 3).map((channel, index) => channel * front[3] + back[index] * (1 - front[3]));
    const luminance = rgb => rgb.slice(0, 3).map(channel => channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4)
      .reduce((sum, channel, index) => sum + channel * [.2126, .7152, .0722][index], 0);
    const ratio = (foreground, background) => {
      const values = [luminance(over(foreground, background)), luminance(background)].sort((a, b) => b - a);
      return (values[0] + .05) / (values[1] + .05);
    };
    const base = getComputedStyle(fill), pseudo = getComputedStyle(fill, '::before');
    const outside = rgba(getComputedStyle(fill.parentElement).backgroundColor);
    const unfilled = rgba(base.backgroundColor), filled = over(rgba(pseudo.backgroundColor), unfilled);
    return { baseOpacity: base.opacity, pseudoOpacity: pseudo.opacity, baseImage: base.backgroundImage,
      pseudoImage: pseudo.backgroundImage, baseAlpha: unfilled[3], outsideAlpha: outside[3],
      boundaries: ['Top', 'Right', 'Bottom', 'Left'].map(edge => ({ edge,
        width: parseFloat(base[`border${edge}Width`]),
        ratio: ratio(rgba(base[`border${edge}Color`]), outside) })),
      text: [...fill.querySelectorAll('.ui-audio-volume-fill-label, output')].map(element => {
        const ink = rgba(getComputedStyle(element).color);
        return { role: element.localName, filled: ratio(ink, filled), unfilled: ratio(ink, unfilled) };
      }) };
  });
  expect(paints).toMatchObject({ baseOpacity: '1', pseudoOpacity: '1', baseImage: 'none', pseudoImage: 'none', baseAlpha: 1, outsideAlpha: 1 });
  for (const boundary of paints.boundaries) {
    expect(boundary.width, `${boundary.edge} input boundary width`).toBeGreaterThanOrEqual(1);
    expect(boundary.ratio, `${boundary.edge} input boundary against popup`).toBeGreaterThanOrEqual(3);
  }
  for (const text of paints.text) {
    expect(text.filled, `${text.role} over percentage fill`).toBeGreaterThanOrEqual(4.5);
    expect(text.unfilled, `${text.role} over unfilled backing`).toBeGreaterThanOrEqual(4.5);
  }
  const audit = await expectContrast(page, { rootSelector: '#scanner-volume-popover',
    reviewedPaintOwners: { ...inspection.reviewedPaintOwners, 'overlapping-pseudo-paint': [
      ...inspection.reviewedPaintOwners['overlapping-pseudo-paint'], '.ui-audio-volume-fill'
    ] } });
  await testInfo.attach(`volume-fill-paint-${appearance}`, { body: JSON.stringify({ paints, audit }), contentType: 'application/json' });
}

for (const { name, theme, viewport } of presentations) {
  test(`shared Scanner presentation ${name}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.goto(`/design-system.html?view=scanner&theme=${theme}`);
    const scanner = page.locator('.visual-scanner-example');
    await expect(scanner.locator('.scanner-page')).toHaveAttribute('data-shared-audio-ready', 'true');
    const transport = scanner.locator('#scanner-playback-bar');
    await expect(transport.locator('.ui-audio-timing')).toHaveAttribute('data-duration-known', 'true');
    await expect(transport.getByRole('progressbar', { name: 'Call progress' })).toBeVisible();
    await expect(transport.locator('[data-volume-value]')).toHaveText('75%');
    await expect(scanner.locator('#scanner-clear-queue')).toHaveCount(0);
    await expect(transport.locator('[data-audio-action="hold"]')).toHaveText('Hd');
    await expect(transport.locator('[data-audio-action="avoid"]')).toHaveText('Av');
    await expectNoHorizontalOverflow(page);
    await auditSharedPlayer(page, '.visual-scanner-example', `scanner-${name}`, testInfo);
    await page.mouse.move(0, 0);
    await expect(scanner).toHaveScreenshot(`scanner-${name}.png`);
    await scanner.getByRole('button', { name: 'Volume, 75 percent', exact: true }).click();
    const popup = page.locator('#scanner-volume-popover');
    await expect(popup).toBeVisible();
    await auditVolumeFill(page, testInfo);
    for (const hue of [0, 120, 280]) {
      await page.evaluate(async value => {
        const { applyThemeHue } = await import('/assets/core/theme.js?v=2'); applyThemeHue(value);
      }, hue);
      await auditVolumeFill(page, testInfo, `hue-${hue}`);
    }
    await page.evaluate(async () => {
      const { applyThemeHue } = await import('/assets/core/theme.js?v=2'); applyThemeHue(null);
    });
    if (name === 'light-phone') {
      await page.mouse.move(0, 0);
      await expect(popup).toHaveScreenshot('scanner-volume-light-phone.png');
    }
    await page.keyboard.press('Escape');
    await expect(scanner.getByRole('button', { name: 'Volume, 75 percent', exact: true })).toBeFocused();
    if (name === 'dark-desktop') {
      await page.goto('/design-system.html?view=scanner&theme=dark&state=idle');
      await expect(scanner.locator('.scanner-page')).toHaveAttribute('data-shared-audio-ready', 'true');
      await expect(scanner.locator('.ui-audio-timing')).toHaveAttribute('data-duration-known', 'false');
      await expect(scanner.getByRole('progressbar', { name: 'Call progress' })).toBeHidden();
      await expectContrast(page, { rootSelector: '.visual-scanner-example' });
      await expect(scanner).toHaveScreenshot('scanner-idle-dark-desktop.png');
    }
  });
}

const dockCases = [
  { name: 'live-expanded-light-desktop', theme: 'light', source: 'live', state: 'full', viewport: { width: 1280, height: 900 } },
  { name: 'live-compact-dark-phone', theme: 'dark', source: 'live', state: 'minimal', viewport: { width: 390, height: 844 } },
  { name: 'recording-expanded-light-phone', theme: 'light', source: 'recordings', state: 'full', viewport: { width: 390, height: 844 } },
  { name: 'recording-collapsed-dark-desktop', theme: 'dark', source: 'recordings', state: 'collapsed', viewport: { width: 1280, height: 900 } }
];
for (const { name, theme, source, state, viewport } of dockCases) {
  test(`shared dock presentation ${name}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.clock.setFixedTime(new Date('2026-09-30T14:24:18Z'));
    await page.goto(`/design-system.html?${new URLSearchParams({ view: 'audio-dock', theme, audioSource: source, audioState: state })}`);
    await expect(page.locator('.visual-audio-dock-example')).toHaveAttribute('data-ready', 'true');
    const dock = page.locator('#audio-dock');
    await expect(dock).toHaveAttribute('data-state', state);
    await expect(dock).toHaveAttribute('data-source', source);
    if (state === 'full') {
      const actions = await dock.locator('.ui-audio-transport-main > button:visible').evaluateAll(buttons =>
        buttons.map(button => button.dataset.audioAction));
      expect(actions).toEqual(source === 'live' ? ['replay', 'play', 'skip', 'stop'] :
        ['previous', 'rewind', 'play', 'forward', 'skip', 'stop']);
      if (source === 'live') {
        await expect(dock.locator('[data-audio-action="hold"]')).toHaveText('Hd');
        await expect(dock.locator('[data-audio-action="avoid"]')).toHaveText('Av');
      }
    }
    await expectNoHorizontalOverflow(page);
    await auditSharedPlayer(page, '#audio-dock', name, testInfo);
    await page.mouse.move(0, 0);
    await expect(dock).toHaveScreenshot(`${name}.png`);
  });
}
