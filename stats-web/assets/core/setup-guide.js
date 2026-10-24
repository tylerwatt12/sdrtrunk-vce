// Shared page guidance: the modal foundation owns focus, dismissal and replacement.
// Steps only describe copy, existing targets and temporary presentation work.
import { createGuideDiagram } from '../guides/diagrams.js?v=1';
import { createTunerCoverageDiagram } from '../guides/tuner-coverage.js?v=4';

const dynamicGuideDiagrams = Object.freeze({ 'tuner-coverage': createTunerCoverageDiagram });

export function openSetupGuide({ node, openReadOnlyModal, mountAccessWireframe, guideSampleData }, options) {
  const steps = options.steps || [];
  if (!steps.length) return null;
  const numberedSteps = steps.filter(step => !step.overview);

  const body = node('div', 'ui-setup-guide-surface');
  const copy = node('div', 'ui-setup-guide-copy');
  const footer = node('div', 'ui-modal-footer ui-modal-footer-flush ui-setup-guide-footer');
  const progress = node('div', 'ui-setup-guide-progress');
  const counter = node('span', 'ui-setup-guide-counter');
  counter.setAttribute('role', 'status');
  counter.setAttribute('aria-live', 'polite');
  const dots = node('div', 'ui-setup-guide-dots');
  dots.setAttribute('aria-hidden', 'true');
  numberedSteps.forEach(() => dots.append(node('span', 'ui-setup-guide-dot')));
  progress.append(counter, dots);
  const actions = node('div', 'ui-action-row');
  const back = node('button', 'ui-button ui-button-secondary', 'Back');
  const next = node('button', 'ui-button ui-button-primary', 'Next');
  back.type = next.type = 'button';
  actions.append(back, next);
  footer.append(progress, actions);
  body.append(copy, footer);

  let index = 0;
  let closed = false;
  let programmaticClose = false;
  let pendingClose = null;
  let frame = null;
  let observer = null;
  let stepCleanup = null;
  let sampleCleanup = null;
  let illustrationCleanup = null;
  let diagramCleanup = null;
  let stepGeneration = 0;
  let stepController = null;
  let preparing = false;
  const layers = new Map();
  const layerAnchors = new Map();
  let hero = null;
  const targetIds = new Map();
  let update = () => {};
  const schedule = () => {
    if (closed || frame !== null) return;
    frame = requestAnimationFrame(() => { frame = null; update(); });
  };
  const reportError = error => {
    if (options.onError) options.onError(error);
    else console.error('Unable to show welcome guide', error);
  };
  const cleanStep = () => {
    stepController?.abort();
    const cleanups = [stepCleanup, ...layers.values(), sampleCleanup, illustrationCleanup, diagramCleanup, () => hero?.remove()];
    stepCleanup = null;
    sampleCleanup = null;
    illustrationCleanup = null;
    diagramCleanup = null;
    let failure = null;
    cleanups.forEach(clean => {
      try { clean?.(); } catch (error) { failure ||= error; }
    });
    hero = null;
    return failure;
  };
  const cleanup = () => {
    closed = true;
    stepGeneration += 1;
    if (frame !== null) cancelAnimationFrame(frame);
    observer?.disconnect();
    const failure = cleanStep();
    targetIds.forEach((original, target) => {
      if (original === null) target.removeAttribute('id');
      else target.id = original;
    });
    window.removeEventListener('resize', schedule);
    document.removeEventListener('scroll', schedule, true);
    window.visualViewport?.removeEventListener('resize', schedule);
    window.visualViewport?.removeEventListener('scroll', schedule);
    if (failure) reportError(failure);
  };
  const modal = openReadOnlyModal(steps[0].title || options.title || 'Welcome guide', body, {
    id: options.id || 'setup-guide', className: 'ui-setup-guide', closeLabel: 'Close guide',
    isGuide: true, lockScroll: false, cleanup,
    onDismiss: () => { if (!programmaticClose) options.onDismiss?.(); }
  });
  if (!modal) return null;

  const backdrop = modal.state.backdrop;
  backdrop.classList.add('ui-setup-guide-backdrop');
  const heading = modal.dialog.querySelector('h2');
  const header = modal.dialog.querySelector('.modal-header');
  const headingGroup = node('div', 'ui-setup-guide-heading');
  const eyebrow = node('p', 'ui-setup-guide-eyebrow', options.eyebrow || 'WELCOME GUIDE');
  heading.before(headingGroup);
  headingGroup.append(eyebrow, heading);

  const svgNode = tag => document.createElementNS('http://www.w3.org/2000/svg', tag);
  const shade = svgNode('svg');
  shade.classList.add('ui-setup-guide-shade');
  shade.setAttribute('aria-hidden', 'true');
  const definitions = svgNode('defs');
  const mask = svgNode('mask');
  const maskId = `${heading.id}-spotlight`;
  mask.id = maskId;
  mask.setAttribute('maskUnits', 'userSpaceOnUse');
  mask.setAttribute('x', '0');
  mask.setAttribute('y', '0');
  const maskSurface = svgNode('rect');
  maskSurface.classList.add('ui-setup-guide-mask-surface');
  mask.append(maskSurface);
  definitions.append(mask);
  const dimmer = svgNode('path');
  dimmer.classList.add('ui-setup-guide-dimmer');
  dimmer.setAttribute('mask', `url(#${maskId})`);
  dimmer.setAttribute('data-modal-dismiss-surface', '');
  const highlights = svgNode('g');
  shade.append(definitions, dimmer, highlights);
  backdrop.prepend(shade);
  const pointer = node('div', 'ui-setup-guide-pointer');
  pointer.setAttribute('aria-hidden', 'true');
  pointer.hidden = true;
  modal.dialog.before(pointer);

  const numericToken = (name, fallback) => {
    const value = parseFloat(getComputedStyle(backdrop).getPropertyValue(name));
    return Number.isFinite(value) ? value : fallback;
  };
  // Preserve the production popover node and its anchored geometry, but leave the
  // browser's top layer while the guide owns focus and pointer protection.
  const registerLayer = (element, { anchor = null } = {}) => {
    if (!element?.isConnected || closed) return () => {};
    if (layers.has(element)) return layers.get(element);
    const attributes = new Map(['popover', 'hidden', 'inert'].map(name => [name, element.getAttribute(name)]));
    const geometry = new Map(['left', 'top', 'maxHeight', 'display'].map(name => [name, element.style[name]]));
    const rectangle = element.getBoundingClientRect();
    const display = getComputedStyle(element).display;
    if (element.matches(':popover-open')) element.hidePopover();
    element.removeAttribute('popover');
    element.hidden = false;
    element.inert = true;
    element.classList.add('ui-setup-guide-layer');
    element.style.display = display === 'none' ? 'block' : display;
    element.style.left = `${rectangle.left}px`;
    element.style.top = `${rectangle.top}px`;
    if (anchor?.isConnected) {
      const anchorRectangle = anchor.getBoundingClientRect();
      layerAnchors.set(element, { element: anchor,
        rightOffset: rectangle.right - anchorRectangle.right,
        topOffset: rectangle.top - anchorRectangle.bottom });
    }
    let restored = false;
    const restore = () => {
      if (restored) return;
      restored = true;
      layers.delete(element);
      layerAnchors.delete(element);
      element.classList.remove('ui-setup-guide-layer');
      attributes.forEach((value, name) => {
        if (value === null) element.removeAttribute(name);
        else element.setAttribute(name, value);
      });
      geometry.forEach((value, name) => { element.style[name] = value; });
    };
    layers.set(element, restore);
    schedule();
    return restore;
  };
  const targets = () => steps[index].overview ? [] : [...(steps[index].targets?.() || [])]
    .filter(target => target?.isConnected && target.getClientRects().length);
  const paintRect = (element, rectangle) => {
    for (const [name, value] of Object.entries({
      x: rectangle.left, y: rectangle.top, width: rectangle.right - rectangle.left,
      height: rectangle.bottom - rectangle.top
    })) element.setAttribute(name, String(value));
  };
  const close = (onClosed) => {
    if (closed) return;
    programmaticClose = true;
    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      onClosed?.();
    };
    if (!backdrop.isConnected) {
      pendingClose = finish;
      return;
    }
    if (modal.close(finish)) finish();
  };

  update = () => {
    if (closed || !backdrop.isConnected) return;
    const width = document.documentElement.clientWidth;
    const height = document.documentElement.clientHeight;
    const gutter = numericToken('--guide-viewport-gap', 24);
    const padding = numericToken('--guide-spotlight-padding', 8);
    const gap = numericToken('--guide-anchor-gap', 24);
    const radius = numericToken('--guide-spotlight-radius', 12);
    const coachWidth = modal.dialog.getBoundingClientRect().width;
    modal.state.guideTargets?.forEach(target => {
      const available = Math.max(coachWidth, width - gutter * 2 - coachWidth - gap - padding * 2);
      target.backdrop.style.setProperty('--guide-target-reserved-width', `${coachWidth + gap + padding * 2}px`);
      target.dialog.style.maxWidth = `${Math.min(width - gutter * 2, available)}px`;
    });
    layers.forEach((_restore, element) => {
      const alignment = layerAnchors.get(element);
      const anchor = alignment?.element.getBoundingClientRect();
      if (!anchor) return;
      const rectangle = element.getBoundingClientRect();
      const popoverGutter = numericToken('--space-2', 8);
      element.style.left = `${Math.max(popoverGutter, Math.min(anchor.right + alignment.rightOffset - rectangle.width,
        width - popoverGutter - rectangle.width))}px`;
      element.style.top = `${Math.max(popoverGutter, Math.min(anchor.bottom + alignment.topOffset,
        height - popoverGutter - rectangle.height))}px`;
    });
    const bounds = targets().map(target => {
      if (!targetIds.has(target)) targetIds.set(target, target.getAttribute('id'));
      if (!target.id) target.id = `${heading.id}-target-${targetIds.size}`;
      const rectangle = target.getBoundingClientRect();
      return { left: rectangle.left, top: rectangle.top, right: rectangle.right,
        bottom: rectangle.bottom, target };
    })
      .filter(rect => rect.right > 0 && rect.bottom > 0 && rect.left < width && rect.top < height)
      .map(rect => ({ left: Math.max(0, rect.left - padding), top: Math.max(0, rect.top - padding),
        right: Math.min(width, rect.right + padding), bottom: Math.min(height, rect.bottom + padding),
        target: rect.target }));
    shade.setAttribute('viewBox', `0 0 ${width} ${height}`);
    mask.setAttribute('width', String(width));
    mask.setAttribute('height', String(height));
    paintRect(maskSurface, { left: 0, top: 0, right: width, bottom: height });
    dimmer.setAttribute('d', `M0 0H${width}V${height}H0Z`);
    // Black mask regions form a union: overlapping targets never dim one another.
    mask.replaceChildren(maskSurface);
    definitions.replaceChildren(mask);
    highlights.replaceChildren();
    bounds.forEach((rect, rectIndex) => {
      const hole = svgNode('rect');
      hole.classList.add('ui-setup-guide-mask-hole');
      hole.setAttribute('rx', String(radius));
      paintRect(hole, rect);
      mask.append(hole);
      const highlight = svgNode('rect');
      highlight.classList.add('ui-setup-guide-highlight');
      highlight.setAttribute('data-setup-guide-target', rect.target.id);
      highlight.setAttribute('rx', String(radius));
      paintRect(highlight, rect);
      if (bounds.length > 1) {
        // Hide each outline wherever another spotlight covers it. The remaining
        // strokes trace the union's silhouette without exposing its bounding box.
        const outlineMask = svgNode('mask');
        outlineMask.id = `${maskId}-outline-${rectIndex}`;
        for (const [name, value] of Object.entries({ maskUnits: 'userSpaceOnUse', x: 0, y: 0, width, height })) {
          outlineMask.setAttribute(name, String(value));
        }
        const surface = svgNode('rect');
        surface.classList.add('ui-setup-guide-mask-surface');
        paintRect(surface, { left: 0, top: 0, right: width, bottom: height });
        outlineMask.append(surface);
        bounds.forEach((other, otherIndex) => {
          if (otherIndex === rectIndex) return;
          const cutout = svgNode('rect');
          cutout.classList.add('ui-setup-guide-mask-hole');
          cutout.setAttribute('rx', String(radius));
          paintRect(cutout, other);
          outlineMask.append(cutout);
        });
        definitions.append(outlineMask);
        highlight.setAttribute('mask', `url(#${outlineMask.id})`);
      }
      // Transparent filled hit regions shield the live controls underneath.
      highlights.append(highlight);
    });
    bounds.forEach(rect => {
      const hitRegion = svgNode('rect');
      hitRegion.classList.add('ui-setup-guide-hit-region');
      hitRegion.setAttribute('rx', String(radius));
      paintRect(hitRegion, rect);
      highlights.append(hitRegion);
    });

    modal.dialog.style.maxHeight = `${Math.max(0, height - gutter * 2)}px`;
    const panel = modal.dialog.getBoundingClientRect();
    let left = (width - panel.width) / 2;
    let top = (height - panel.height) / 2;
    let anchor = null;
    if (bounds.length) {
      anchor = { left: Math.min(...bounds.map(rect => rect.left)),
        top: Math.min(...bounds.map(rect => rect.top)),
        right: Math.max(...bounds.map(rect => rect.right)),
        bottom: Math.max(...bounds.map(rect => rect.bottom)) };
      const targetDialog = [...(modal.state.guideTargets || [])].at(-1)?.dialog;
      if (targetDialog?.isConnected) {
        const rectangle = targetDialog.getBoundingClientRect();
        anchor = { left: rectangle.left, top: rectangle.top, right: rectangle.right, bottom: rectangle.bottom };
      }
      const headerBottom = document.querySelector('.app-header')?.getBoundingClientRect().bottom || 0;
      const sideTop = Math.max(gutter, anchor.top, headerBottom ? headerBottom + gap / 2 : 0);
      const candidates = [
        { left: anchor.right + gap, top: sideTop },
        { left: anchor.left, top: anchor.bottom + gap },
        { left: anchor.left - panel.width - gap, top: sideTop },
        { left: anchor.left, top: anchor.top - panel.height - gap }
      ];
      // Separate targets can leave usable space inside their combined bounds.
      // Consider those gaps before accepting a card over a highlighted control.
      const obstacles = targetDialog?.isConnected ? [anchor] : bounds;
      const horizontal = [gutter, width - gutter - panel.width,
        ...obstacles.flatMap(rect => [rect.left, rect.right + gap, rect.left - panel.width - gap])];
      const vertical = [Math.max(gutter, headerBottom + gap / 2), height - gutter - panel.height,
        ...obstacles.flatMap(rect => [rect.top, rect.bottom + gap, rect.top - panel.height - gap])];
      horizontal.forEach(left => vertical.forEach(top => candidates.push({ left, top })));
      const overflow = candidate => Math.max(0, gutter - candidate.left) + Math.max(0, gutter - candidate.top) +
        Math.max(0, candidate.left + panel.width + gutter - width) +
        Math.max(0, candidate.top + panel.height + gutter - height);
      const position = candidate => ({
        left: Math.max(gutter, Math.min(candidate.left, width - gutter - panel.width)),
        top: Math.max(gutter, Math.min(candidate.top, height - gutter - panel.height))
      });
      const overlap = candidate => {
        const placed = position(candidate);
        return obstacles.reduce((area, rect) => area +
          Math.max(0, Math.min(placed.left + panel.width, rect.right) - Math.max(placed.left, rect.left)) *
          Math.max(0, Math.min(placed.top + panel.height, rect.bottom) - Math.max(placed.top, rect.top)), 0);
      };
      const candidate = candidates.reduce((best, value) => {
        const difference = overlap(value) - overlap(best);
        return difference < 0 || difference === 0 && overflow(value) < overflow(best) ? value : best;
      });
      left = candidate.left;
      top = candidate.top;
      if (!targetDialog?.isConnected && bounds.length > 1) {
        const placed = position(candidate);
        const distance = rect => Math.max(0, rect.left - placed.left - panel.width, placed.left - rect.right) +
          Math.max(0, rect.top - placed.top - panel.height, placed.top - rect.bottom);
        anchor = bounds.reduce((closest, rect) => distance(rect) < distance(closest) ? rect : closest);
      }
    }
    left = Math.max(gutter, Math.min(left, width - gutter - panel.width));
    top = Math.max(gutter, Math.min(top, height - gutter - panel.height));
    modal.dialog.style.left = `${left}px`;
    modal.dialog.style.top = `${top}px`;
    pointer.hidden = true;
    if (anchor) {
      const half = numericToken('--guide-pointer-size', 16) / 2;
      const inset = numericToken('--guide-card-radius', 20) + half;
      const clamp = (value, start, length) => Math.max(start + Math.min(inset, length / 2),
        Math.min(value, start + length - Math.min(inset, length / 2)));
      let x;
      let y;
      if (left >= anchor.right || left + panel.width <= anchor.left) {
        x = left >= anchor.right ? left : left + panel.width;
        y = clamp((anchor.top + anchor.bottom) / 2, top, panel.height);
      } else if (top >= anchor.bottom || top + panel.height <= anchor.top) {
        x = clamp((anchor.left + anchor.right) / 2, left, panel.width);
        y = top >= anchor.bottom ? top : top + panel.height;
      }
      if (x !== undefined) {
        pointer.style.left = `${x - half}px`;
        pointer.style.top = `${y - half}px`;
        pointer.hidden = false;
      }
    }
  };

  const showStep = async () => {
    if (closed) return;
    const generation = ++stepGeneration;
    preparing = true;
    back.disabled = next.disabled = true;
    modal.dialog.setAttribute('aria-busy', 'true');
    const cleanupFailure = cleanStep();
    observer?.disconnect();
    if (cleanupFailure) {
      close();
      reportError(cleanupFailure);
      return;
    }
    const step = steps[index];
    stepController = new AbortController();
    const signal = stepController.signal;
    const isCurrent = () => !closed && generation === stepGeneration && !signal.aborted;
    try {
      // Overviews have no spotlights. An explicit sample presentation may show
      // the real page with fictional data even when hardware is unavailable.
      // Ordinary overview hooks stay inactive unless this capability is chosen.
      const preparePresentation = !step.overview || step.samplePresentation === true;
      sampleCleanup = preparePresentation ? guideSampleData?.activate(step.samples || []) || null : null;
      const prepared = preparePresentation ? await step.prepare?.({ modal, registerLayer: (...args) => {
        if (!isCurrent()) return () => {};
        return registerLayer(...args);
      }, signal, isCurrent }) : null;
      if (!isCurrent()) {
        if (typeof prepared === 'function') prepared();
        return;
      }
      stepCleanup = typeof prepared === 'function' ? prepared : null;
      modal.setTitle(step.title || options.title || 'Welcome guide');
      modal.dialog.dataset.guideId = options.id || 'setup-guide';
      modal.dialog.dataset.guideStep = step.id || String(index + 1);
      modal.dialog.classList.toggle('ui-setup-guide--illustrated', Boolean(step.illustration));
      modal.dialog.classList.toggle('ui-setup-guide--compact', step.compact === true);
      copy.replaceChildren();
      const diagramOptions = { document, idPrefix: `${heading.id}-diagram-${generation}` };
      const dynamicDiagram = dynamicGuideDiagrams[step.diagram]?.(diagramOptions);
      const diagram = dynamicDiagram?.element || (step.diagram ? createGuideDiagram(step.diagram, diagramOptions) : null);
      modal.dialog.classList.toggle('ui-setup-guide--diagram', Boolean(diagram));
      modal.dialog.classList.toggle('ui-setup-guide--coverage', Boolean(dynamicDiagram));
      (step.paragraphs || []).forEach(text => copy.append(node('p', 'ui-setup-guide-description', text)));
      if (diagram) copy.append(diagram);
      diagramCleanup = dynamicDiagram?.mount({ signal }) || null;
      if (step.items?.length) {
        const items = node('div', 'ui-setup-guide-items');
        step.items.forEach(({ label, text }) => {
          const item = node('div', 'ui-setup-guide-item');
          item.append(node('strong', '', label), node('p', 'ui-setup-guide-description', text));
          items.append(item);
        });
        copy.append(items);
      }
      if (step.hint) copy.append(node('p', 'ui-setup-guide-hint', step.hint));
      if (step.illustration) {
        hero = node('div', 'ui-setup-guide-illustration');
        const canvas = node('canvas', 'ui-setup-guide-wireframe');
        canvas.setAttribute('aria-hidden', 'true');
        const mark = node('img', 'ui-setup-guide-wordmark');
        mark.src = '/assets/vce-wordmark.svg?v=3';
        mark.alt = '';
        hero.append(canvas, mark);
        header.before(hero);
        illustrationCleanup = mountAccessWireframe?.(canvas, { compact: true }) || null;
      }
      back.hidden = index === 0;
      next.replaceChildren(node('span', '', step.nextLabel ||
        (step.overview ? 'Start tour' : index === steps.length - 1 ? 'Done' : 'Next')));
      const nextIcon = svgNode('svg');
      nextIcon.setAttribute('aria-hidden', 'true');
      const nextUse = svgNode('use');
      nextUse.setAttribute('href', '#icon-chevron-right');
      nextIcon.append(nextUse);
      next.append(nextIcon);
      const numberedIndex = steps.slice(0, index + 1).filter(value => !value.overview).length - 1;
      counter.textContent = step.overview ? 'Overview' : `Step ${numberedIndex + 1} of ${numberedSteps.length}`;
      dots.hidden = Boolean(step.overview);
      [...dots.children].forEach((dot, dotIndex) => {
        dot.classList.toggle('is-current', !step.overview && dotIndex === numberedIndex);
        dot.classList.toggle('is-complete', !step.overview && dotIndex < numberedIndex);
      });
      update();
      if (typeof ResizeObserver !== 'undefined') {
        observer ||= new ResizeObserver(schedule);
        observer.observe(modal.dialog);
        // Page reflow can move a target without changing the target's size.
        const pageContent = document.getElementById('content');
        if (pageContent) observer.observe(pageContent, { box: 'border-box' });
        targets().forEach(target => observer.observe(target));
      }
      options.onStepChange?.(index, step);
      preparing = false;
      back.disabled = next.disabled = false;
      modal.dialog.removeAttribute('aria-busy');
      modal.focus(next);
    } catch (error) {
      if (!isCurrent()) return;
      cleanStep();
      close();
      reportError(error);
    }
  };
  const advance = () => {
    if (closed || preparing) return;
    if (index === steps.length - 1) close(() => options.onComplete?.());
    else { index += 1; showStep(); }
  };
  const retreat = () => {
    if (closed || preparing || index === 0) return;
    index -= 1;
    showStep();
  };
  next.addEventListener('click', advance);
  back.addEventListener('click', retreat);
  void modal.ready.then(shown => {
    if (!shown || closed) return;
    if (pendingClose) {
      const finish = pendingClose;
      pendingClose = null;
      close(finish);
      return;
    }
    window.addEventListener('resize', schedule);
    document.addEventListener('scroll', schedule, true);
    window.visualViewport?.addEventListener('resize', schedule);
    window.visualViewport?.addEventListener('scroll', schedule);
    showStep();
  });
  return { ready: modal.ready, isOpen: () => !closed && backdrop.isConnected, close,
    next: advance, back: retreat };
}
