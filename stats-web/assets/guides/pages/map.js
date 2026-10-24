import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

export function mapOverviewPresentation(preparePlayer, preparePage) {
  return { samplePresentation: true,
    ...pageGuidePresentation('map', 'overview', preparePlayer, preparePage, { restoreScroll: true }) };
}

export function mapSteps(documentValue, preparePlayer, preparePage) {
  const steps = [
    { id: 'explore', title: 'Explore reported locations',
      paragraphs: ['Drag the map to move around. Use + and − to zoom, and Show all to bring every plotted radio into view.'],
      names: ['explore', 'show-all'], owners: '.listen-map-panel, .listen-map-toolbar' },
    { id: 'select-radio', title: 'Choose a radio',
      paragraphs: ['Select a marker or a name in Tracks to see that radio’s latest position and recent history.'],
      owners: '.listen-map-panel, .listen-map-sidebar' },
    { id: 'history', title: 'Trace recent movement',
      paragraphs: ['The trail links recent positions. History shows when each position was reported; select a time to center the map there.'],
      owners: '.listen-map-sidebar' },
    { id: 'follow', title: 'Keep a radio in view',
      paragraphs: ['Follow keeps the map centered on this radio as new positions arrive.'],
      owners: '.listen-map-sidebar' }
  ];
  return steps.map(({ names, owners, ...step }, index) => {
    const selector = (names || [step.id]).map(name => `[data-guide-target="map-${name}"]`).join(', ');
    const focus = () => [...(documentValue?.querySelectorAll(selector) || [])];
    return { ...step, compact: true, targets: focus,
      contextTargets: contextualTargets(focus, owners),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('map', step.id, preparePlayer, preparePage, { restoreScroll: true }) };
  });
}
