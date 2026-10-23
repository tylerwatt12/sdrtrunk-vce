'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');

// These named production paint owners need representative pixel/visual review.
// Permission follows the actual painter and reason, not an arbitrary descendant
// of a reviewed workspace. New gradients/filters on ordinary cards fail strict
// mode instead of silently removing those cards from measured coverage.
const DEFAULT_REVIEWED_PAINT_OWNERS = {
  'background-image-or-gradient': ['.audio-dock-compact', '.audio-dock-player-main', '.audio-dock-slim',
    '.scanner-now-playing.ui-listening-stage-main', '.network-visualizer-stage',
    '.about-page', '.access-landing', '.access-login-scene'],
  filter: ['.scanner-page .ui-content-idle', '.network-visualizer-label', '.network-visualizer-scope-title',
    '.network-visualizer-auto-focus', '.network-visualizer-manual-guide', '.network-visualizer-notice',
    '.network-visualizer-legend', '.access-landing-card'],
  'overlapping-pseudo-paint': ['.network-visualizer-stage', '.access-login-scene', '.access-login-modal'],
  'non-ancestor-overlay-paint': ['.ui-setup-guide-dimmer'],
  'instrument-or-native-painted-internals': ['.count-time-chart-svg text', '.chart-frequency-windows text',
    '.quality-chart-svg text', '.dashboard-activity-donut text', '.channel-diagnostic-canvas',
    '.network-visualizer-stage canvas', '.access-scene-canvas', 'canvas.ui-qr-code',
    '#scanner-volume', '.tuners-gain-control input.ui-range', 'input.ui-dual-range-handle',
    'input.ui-audio-range', '.ui-audio-volume input[type="range"]'],
  'native-control-internals': ['.admin-toggle-control > input', '.recordings-call-select']
};

// Surface floors are project visual-grouping checks, not WCAG requirements.
// Text and required control boundaries use WCAG contrast ratios. This helper
// deliberately reports paint it cannot resolve instead of calling it a pass.
async function inspectContrast(page, { rootSelector = 'body', reviewedPaintOwners = DEFAULT_REVIEWED_PAINT_OWNERS } = {}) {
  return page.evaluate(({ rootSelector, reviewedPaintOwners }) => {
    const root = document.querySelector(rootSelector);
    if (!root) throw new Error(`Contrast audit root does not exist: ${rootSelector}`);
    // Controls may have become enabled just before the page's ready signal.
    // Sample the resting endpoint of finite property transitions, while
    // leaving CSS animations (spinners, playback, attention) in their state.
    let finishedTransitions = 0;
    for (const animation of document.getAnimations()) {
      if (typeof CSSTransition !== 'undefined' && animation instanceof CSSTransition &&
          Number.isFinite(animation.effect?.getComputedTiming().endTime)) {
        animation.finish();
        finishedTransitions += 1;
      }
    }
    const criteria = { surface: { standalone: 1.10, tile: 1.05, inset: 1.03 },
      text: { normal: 4.5, large: 3 }, control: 3 };
    const surfaces = [], text = [], controls = [], skipped = [];
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d', { willReadFrequently: true, colorSpace: 'srgb' });
    const colorCache = new Map(), styleCache = new WeakMap();
    const style = (element, pseudo) => pseudo ? getComputedStyle(element, pseudo) :
      (styleCache.get(element) || (styleCache.set(element, getComputedStyle(element)), styleCache.get(element)));
    const color = (value) => {
      if (!value || !CSS.supports('color', value)) return null;
      if (!colorCache.has(value)) {
        context.clearRect(0, 0, 1, 1);
        context.fillStyle = value;
        context.fillRect(0, 0, 1, 1);
        const [r, g, b, a] = context.getImageData(0, 0, 1, 1).data;
        colorCache.set(value, [r / 255, g / 255, b / 255, a / 255]);
      }
      return colorCache.get(value);
    };
    const clear = [0, 0, 0, 0], white = [1, 1, 1, 1];
    const over = (front, back) => {
      const alpha = front[3] + back[3] * (1 - front[3]);
      return alpha ? [0, 1, 2].map((i) =>
        (front[i] * front[3] + back[i] * back[3] * (1 - front[3])) / alpha).concat(alpha) : clear;
    };
    const luminance = (rgba) => rgba.slice(0, 3).reduce((total, channel, i) =>
      total + (channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4) *
        [.2126, .7152, .0722][i], 0);
    const contrast = (a, b) => {
      const values = [luminance(a), luminance(b)].sort((x, y) => y - x);
      return (values[0] + .05) / (values[1] + .05);
    };
    const formatted = (rgba) => rgba && `rgb(${rgba.slice(0, 3).map((v) => Math.round(v * 255)).join(' ')})`;
    const rounded = (value) => Math.round(value * 1000) / 1000;
    const selector = (element) => {
      if (!element) return 'browser canvas';
      if (element.id) return `#${CSS.escape(element.id)}`;
      const parts = [];
      for (let item = element; item && item !== document.body && parts.length < 4; item = item.parentElement) {
        let part = item.localName;
        const classes = [...item.classList].filter((name) => !/^is-|^active$|^selected$/.test(name)).slice(0, 3);
        if (classes.length) part += classes.map((name) => `.${CSS.escape(name)}`).join('');
        const peers = item.parentElement ? [...item.parentElement.children].filter((sibling) =>
          sibling.localName === item.localName) : [];
        if (peers.length > 1) part += `:nth-of-type(${peers.indexOf(item) + 1})`;
        parts.unshift(part);
      }
      return parts.join(' > ') || element.localName;
    };
    const describe = (element) => ({ selector: selector(element),
      label: (element.getAttribute('aria-label') || element.getAttribute('title') ||
        element.getAttribute('name') || (element.matches('input, textarea') ? '' : element.textContent) || '')
        .replace(/\s+/g, ' ').trim().slice(0, 100) });
    const skip = (kind, element, reason, extra = {}) => {
      const { paintOwner = element, ...details } = extra;
      const reviewOwner = details.status === 'needs-review' ?
        (reviewedPaintOwners[reason] || []).find((owner) => paintOwner.matches(owner)) : null;
      skipped.push({ kind, ...describe(element), reason, ...details,
        ...(details.status === 'needs-review' ? { paintOwner: selector(paintOwner),
          reviewOwner: reviewOwner || null, reviewed: Boolean(reviewOwner) } : {}) });
    };
    const hiddenReason = (element) => {
      if (!element.getClientRects().length) return 'not-rendered';
      for (let item = element; item; item = item.parentElement) {
        const css = style(item);
        if (css.display === 'none' || ['hidden', 'collapse'].includes(css.visibility) || Number(css.opacity) === 0)
          return 'not-visible';
        if (item.inert) return 'inert';
        if (item.classList.contains('visually-hidden') || css.clip === 'rect(0px, 0px, 0px, 0px)' ||
            css.clipPath === 'inset(50%)') return 'visually-hidden';
        if (item.localName === 'details' && !item.open) {
          const summary = [...item.children].find((child) => child.localName === 'summary');
          if (item !== element && summary && !summary.contains(element) && summary !== element) return 'closed-disclosure';
        }
      }
      const bounds = element.getBoundingClientRect();
      return bounds.width > 0 && bounds.height > 0 ? null : 'empty-bounds';
    };
    const disabledReason = (element) => {
      // A static select is read-only display; its visible value still matters.
      if (element.closest('.ui-select-static')) return null;
      if (element.closest(':disabled, [aria-disabled="true"]') ||
          element.closest('.ui-toggle')?.querySelector('input:disabled')) return 'disabled';
      const dualRange = element.closest('.ui-dual-range-disabled');
      const handles = dualRange ? [...dualRange.querySelectorAll('input[type="range"]')] : [];
      if (handles.length && handles.every((handle) => handle.matches(':disabled'))) return 'disabled-composite';
      return null;
    };
    const explicitException = (element) => {
      const owner = element.closest('[data-contrast-audit="skip"]');
      return owner && (owner.getAttribute('data-contrast-audit-reason')?.trim() ||
        'explicit-exception-without-reason');
    };
    const opaque = (rgba) => rgba && rgba[3] >= 1;
    // Build the two pixels (with and without foreground) independently. Each
    // opacity applies to its entire combined subtree, once, not just the ink.
    const pixel = (element, foreground = clear, { border = false } = {}) => {
      let paint = foreground;
      for (let item = element; item; item = item.parentElement) {
        const css = style(item), background = color(css.backgroundColor);
        if (!background) return null;
        if (!(border && item === element && css.backgroundClip === 'padding-box')) paint = over(paint, background);
        paint = [...paint.slice(0, 3), paint[3] * Number(css.opacity)];
      }
      return over(paint, white);
    };
    const paintedParent = (element) => {
      for (let item = element.parentElement; item; item = item.parentElement) {
        if ((color(style(item).backgroundColor)?.[3] || 0) > 0) return item;
      }
      return null;
    };
    const unresolvedPaint = (element) => {
      const guide = element.closest('.ui-setup-guide');
      const backdrop = guide?.closest('.ui-setup-guide-backdrop');
      const dimmer = backdrop && [...backdrop.querySelectorAll('.ui-setup-guide-dimmer')].find((item) =>
        !hiddenReason(item) && (color(style(item).fill)?.[3] || 0) > 0);
      // A solid tour card paints above the sibling scrim. Transparent guidance
      // still needs manual sampling; ordinary card copy can be audited normally.
      const guidePaint = guide && color(style(guide).backgroundColor);
      const opaqueGuide = guidePaint?.[3] === 1 && Number(style(guide).opacity) === 1;
      if (dimmer && !opaqueGuide) return { reason: 'non-ancestor-overlay-paint', owner: dimmer };
      let backgroundBlocked = false;
      for (let item = element; item; item = item.parentElement) {
        const css = style(item);
        if (css.mixBlendMode !== 'normal') return { reason: 'blend-mode', owner: item };
        if (css.filter !== 'none' || (css.backdropFilter && css.backdropFilter !== 'none')) return { reason: 'filter', owner: item };
        // The shared select's right-aligned chevron does not paint beneath its label.
        if (!backgroundBlocked && css.backgroundImage !== 'none' && !item.matches('select.ui-select'))
          return { reason: 'background-image-or-gradient', owner: item };
        for (const pseudo of ['::before', '::after']) {
          const decoration = style(item, pseudo);
          if (!['none', 'normal'].includes(decoration.content) &&
              (decoration.backgroundImage !== 'none' || (color(decoration.backgroundColor)?.[3] || 0) > 0) &&
              Number(decoration.opacity) > 0 && ['absolute', 'fixed'].includes(decoration.position))
            return { reason: 'overlapping-pseudo-paint', owner: item };
        }
        // Opaque fill hides ancestor backgrounds, but an ancestor's filter or
        // blend mode still changes its descendants' entire composed pixels.
        if (opaque(color(css.backgroundColor)) && Number(css.opacity) === 1) backgroundBlocked = true;
      }
      return null;
    };
    const commonException = (kind, element) => {
      const reason = hiddenReason(element) || explicitException(element) || disabledReason(element);
      if (reason) {
        skip(kind, element, reason, reason === 'explicit-exception-without-reason' ? { status: 'needs-review' } : {});
        return true;
      }
      return false;
    };
    const finding = (kind, element, foreground, background, minimum, extra = {}) => {
      const ratio = contrast(foreground, background);
      return { kind, ...describe(element), foreground: formatted(foreground), background: formatted(background),
        ratio: rounded(ratio), minimum, pass: ratio + .005 >= minimum, ...extra };
    };
    const surfaceSelector = [
      '.ui-section', '.ui-surface', '.ui-settings-panel', '.settings-card', '.ui-record-card',
      '.ui-metric', '.ui-facts > .ui-fact', '.ui-summary-card', '.ui-catalog-toolbar',
      '.ui-form-section', '.ui-section-disclosure', '.ui-surface-inset', '.ui-link-tile',
      '.ui-toggle-field', '.ui-notice', '.ui-empty-state', '.ui-feedback-loading', '.ui-feedback-error', '.ui-pager-surface',
      '.ui-settings-preview', '.ui-settings-disclosure', '.ui-settings-destination',
      '.ui-catalog-table-wrap', '.ui-mobile-cards tbody > tr:not(.ui-table-row-group)',
      '[data-contrast-surface]'
    ].join(',');
    const surfaceElements = [...root.querySelectorAll(surfaceSelector)];
    if (root.matches(surfaceSelector)) surfaceElements.unshift(root);
    const insetSelector = '.ui-surface-inset, .ui-form-section, .ui-section-disclosure';
    const pagePaint = (element) => !element || element === document.body || element === document.documentElement ||
      // A page-sized painted layout shell does not turn a standalone card into
      // an embedded tile. Compare the *effective* paint, not class ancestry.
      contrast(pixel(element), pixel(document.body)) < 1.005;
    const zeroRadius = (element) => ['borderTopLeftRadius', 'borderTopRightRadius',
      'borderBottomLeftRadius', 'borderBottomRightRadius'].every((property) => parseFloat(style(element)[property]) === 0);
    const flatDataRow = (element) => {
      if (!element.matches('.ui-mobile-cards tbody > tr:not(.ui-table-row-group)') || !zeroRadius(element)) return false;
      const ownFill = color(style(element).backgroundColor);
      if (ownFill?.[3] && contrast(pixel(element), pixel(element.parentElement)) >= 1.005) return false;
      const rows = [...element.parentElement.children].filter((row) => row.matches('tr:not(.ui-table-row-group)') && !hiddenReason(row));
      // A flat compact table keeps structural rules, including a final row
      // whose last separator is omitted. Card-like rows still need tonal fill.
      return rows.length > 0 && rows.every((row) => zeroRadius(row) &&
        ['Top', 'Right', 'Left'].every((edge) => parseFloat(style(row)[`border${edge}Width`]) === 0)) &&
        rows.some((row) => parseFloat(style(row).borderBottomWidth) > 0 &&
          !['none', 'hidden'].includes(style(row).borderBottomStyle) && (color(style(row).borderBottomColor)?.[3] || 0) > 0);
    };
    for (const element of surfaceElements) {
      if (commonException('surface', element)) continue;
      const roleOverride = element.getAttribute('data-contrast-surface');
      if (roleOverride === 'flat') { skip('surface', element, 'explicit-flat-role'); continue; }
      const unsupported = unresolvedPaint(element) || unresolvedPaint(element.parentElement || element);
      if (unsupported) { skip('surface', element, unsupported.reason, { status: 'needs-review', paintOwner: unsupported.owner }); continue; }
      if (flatDataRow(element)) { skip('surface', element, 'flat-data-row-with-structural-separators'); continue; }
      if (element.matches('.ui-notice-warning, .ui-notice-danger, .ui-feedback-error') &&
          parseFloat(style(element).borderTopWidth) > 0 && (color(style(element).borderTopColor)?.[3] || 0) > 0) {
        // A permitted semantic state border supplies an additional grouping
        // cue. Its text still passes through the ordinary contrast audit.
        skip('surface', element, 'semantic-status-border'); continue;
      }
      if ((color(style(element).backgroundColor)?.[3] || 0) === 0) {
        skip('surface', element, 'transparent-or-flat'); continue;
      }
      const parent = paintedParent(element);
      const role = roleOverride && ['standalone', 'tile', 'inset'].includes(roleOverride) ? roleOverride :
        pagePaint(parent) ? 'standalone' : element.matches(insetSelector) ? 'inset' : 'tile';
      const foreground = pixel(element), background = pixel(element.parentElement);
      if (!foreground || !background) { skip('surface', element, 'unresolved-color', { status: 'needs-review' }); continue; }
      surfaces.push(finding('surface', element, foreground, background, criteria.surface[role],
        { role, paintedParent: selector(parent), standard: 'internal-visual-grouping' }));
    }

    const addText = (element, value, { pseudo = null, source = 'text-node' } = {}) => {
      if (!value?.trim() || commonException('text', element)) return;
      const unsupported = unresolvedPaint(element);
      if (unsupported) { skip('text', element, unsupported.reason, { status: 'needs-review', source, paintOwner: unsupported.owner }); return; }
      const css = style(element, pseudo), ink = color(css.webkitTextFillColor || css.color);
      if (!ink || ink[3] === 0) { skip('text', element, 'unresolved-or-transparent-ink', { status: 'needs-review', source }); return; }
      const foreground = pixel(element, [...ink.slice(0, 3), ink[3] * (pseudo ? Number(css.opacity) : 1)]);
      const background = pixel(element), size = parseFloat(css.fontSize), weight = parseFloat(css.fontWeight) || 400;
      if (!foreground || !background) { skip('text', element, 'unresolved-color', { status: 'needs-review', source }); return; }
      const large = size >= 24 || (size >= 18.666 && weight >= 700);
      text.push(finding('text', element, foreground, background, large ? criteria.text.large : criteria.text.normal,
        { text: source === 'field-value' ? '[field value]' : value.replace(/\s+/g, ' ').trim().slice(0, 160),
          source, fontSize: size, fontWeight: weight, large }));
    };
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const node = walker.currentNode, element = node.parentElement;
      if (!node.textContent.trim() || !element || element.closest('script, style, template, option, optgroup, textarea, svg, canvas')) continue;
      const range = document.createRange(); range.selectNodeContents(node);
      if (![...range.getClientRects()].some((bounds) => bounds.width > 0 && bounds.height > 0)) continue;
      addText(element, node.textContent);
    }
    for (const element of root.querySelectorAll('input, textarea, select')) {
      if (element.matches('select')) addText(element, element.selectedOptions[0]?.label || '', { source: 'selected-option' });
      else if (!element.matches('input[type="hidden"], input[type="checkbox"], input[type="radio"], input[type="range"], input[type="color"], input[type="file"]')) {
        if (element.value) addText(element, element.value, { source: 'field-value' });
        else if (element.getAttribute('placeholder')) addText(element, element.getAttribute('placeholder'),
          { pseudo: '::placeholder', source: 'placeholder' });
      }
    }

    const fieldSelector = ['.ui-input', '.ui-textarea', '.ui-select', '.ui-selection-check', '.ui-choice-radio',
      '.ui-toggle-track', 'textarea', 'select', 'input:not([type])',
      ...['text', 'search', 'password', 'email', 'url', 'tel', 'number', 'date', 'time', 'datetime-local',
        'month', 'week', 'checkbox', 'radio'].map((type) => `input[type="${type}"]`)].join(',');
    for (const element of root.querySelectorAll(fieldSelector)) {
      if (commonException('control', element)) continue;
      if (element.classList.contains('ui-select-static')) { skip('control', element, 'static-read-only-display'); continue; }
      const css = style(element);
      if (element.matches('input[type="checkbox"], input[type="radio"]') && css.appearance !== 'none') {
        skip('control', element, 'native-control-internals', { status: 'needs-review' }); continue;
      }
      const unsupported = unresolvedPaint(element) || unresolvedPaint(element.parentElement || element);
      if (unsupported) { skip('control', element, unsupported.reason, { status: 'needs-review', paintOwner: unsupported.owner }); continue; }
      const fill = pixel(element), outside = pixel(element.parentElement);
      if (!fill || !outside) { skip('control', element, 'unresolved-color', { status: 'needs-review' }); continue; }
      const edges = ['Top', 'Right', 'Bottom', 'Left'].map((edge) => {
        const ink = color(css[`border${edge}Color`]);
        const visible = parseFloat(css[`border${edge}Width`]) > 0 && !['none', 'hidden'].includes(css[`border${edge}Style`]) && ink?.[3] > 0;
        const paint = visible ? pixel(element, ink, { border: true }) : fill;
        return { edge: edge.toLowerCase(), visible, color: formatted(paint),
          outside: rounded(contrast(paint, outside)), inside: rounded(contrast(paint, fill)) };
      });
      const fillRatio = contrast(fill, outside);
      // The outer boundary identifies the control. Its contrast against the
      // inner fill is useful evidence, but is not an extra WCAG requirement.
      const borderRatio = Math.min(...edges.map((edge) => edge.visible ? edge.outside : 1));
      const ratio = Math.max(fillRatio, borderRatio);
      controls.push({ kind: 'control', ...describe(element), foreground: formatted(fill), background: formatted(outside),
        ratio: rounded(ratio), minimum: criteria.control, pass: ratio + .005 >= criteria.control,
        fillRatio: rounded(fillRatio), borderRatio: rounded(borderRatio), edges,
        boundary: fillRatio >= criteria.control ? 'fill' : 'border' });
    }
    // These need specialized paint/state checks; they are coverage gaps, not passes.
    for (const element of root.querySelectorAll('canvas, svg text, input[type="range"], input[type="file"], input[type="color"]')) {
      if (!commonException('specialized', element)) skip('specialized', element, 'instrument-or-native-painted-internals', { status: 'needs-review' });
    }
    const violations = { surfaces: surfaces.filter((item) => !item.pass), text: text.filter((item) => !item.pass),
      controls: controls.filter((item) => !item.pass) };
    const counts = (items) => ({ checked: items.length, passed: items.filter((item) => item.pass).length,
      failed: items.filter((item) => !item.pass).length });
    return { version: 1, context: { url: location.href, title: document.title, rootSelector,
      theme: document.documentElement.dataset.theme || 'system', hue: document.documentElement.style.getPropertyValue('--theme-hue') ||
        document.documentElement.dataset.themeHue || style(document.documentElement).getPropertyValue('--theme-chrome-hue').trim(),
      viewport: { width: innerWidth, height: innerHeight }, finishedTransitions }, criteria, reviewedPaintOwners,
      surfaces, text, controls, skipped, violations,
      summary: { surfaces: counts(surfaces), text: counts(text), controls: counts(controls),
        exempt: skipped.filter((item) => item.status !== 'needs-review').length,
        needsReview: skipped.filter((item) => item.status === 'needs-review').length,
        unreviewedPaint: skipped.filter((item) => item.status === 'needs-review' && !item.reviewed).length },
      limitations: ['Computed solid paint only; unresolved images/gradients, filters, blend modes, positioned pseudo paint and native/instrument internals require review.',
        'Sibling overlaps, background position/clip geometry, text antialiasing, text shadows and generated text are not pixel-sampled.',
        'This resting-state scan does not establish keyboard focus, hover, selection, native popup or disabled readability contrast.',
        'Invisible/inert/disabled content is reported as exempt; readonly values remain included. Transparent and explicit flat surfaces do not require a grouping fill.'] };
  }, { rootSelector, reviewedPaintOwners });
}

async function expectContrast(page, options = {}) {
  const result = await inspectContrast(page, options);
  if (process.env.CONTRAST_AUDIT_REPORT) {
    fs.mkdirSync(process.env.CONTRAST_AUDIT_REPORT, { recursive: true });
    const name = (options.reportName || `${new URL(result.context.url).pathname}-${result.context.theme}-${result.context.hue}-${result.context.viewport.width}`)
      .replace(/[^a-zA-Z0-9_.-]+/g, '-').slice(0, 140);
    const filename = `${name}-${process.pid}-${randomUUID().slice(0, 8)}.json`;
    fs.writeFileSync(path.join(process.env.CONTRAST_AUDIT_REPORT, filename), `${JSON.stringify(result, null, 2)}\n`);
  }
  const recordOnly = options.recordOnly ?? /^(1|true|yes)$/i.test(process.env.CONTRAST_AUDIT_RECORD_ONLY || '');
  if (!recordOnly) {
    const undocumented = result.skipped.filter((item) => item.reason === 'explicit-exception-without-reason');
    assert.equal(undocumented.length, 0, `Contrast audit skip requires a reason: ${undocumented.map((item) => item.selector).join(', ')}`);
    const unreviewed = result.skipped.filter((item) => item.status === 'needs-review' && !item.reviewed);
    assert.equal(unreviewed.length, 0, `Contrast audit found unreviewed paint at ${result.context.url}\n${unreviewed.slice(0, 30)
      .map((item) => `${item.reason}: ${item.selector} (painted by ${item.paintOwner})`).join('\n')}`);
    const failures = Object.values(result.violations).flat();
    assert.equal(failures.length, 0, `Contrast audit failed at ${result.context.url}\n${failures.slice(0, 30)
      .map((item) => `${item.kind} ${item.selector}: ${item.ratio}:1 < ${item.minimum}:1 ${item.text || item.label}`).join('\n')}`);
  }
  return result;
}

module.exports = { inspectContrast, expectContrast };
