import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

export function networkVisualizerOverviewPresentation(preparePlayer, preparePage) {
  return { samplePresentation: true,
    ...pageGuidePresentation('network-visualizer', 'overview', preparePlayer, preparePage) };
}

export function networkVisualizerSteps(documentValue, preparePlayer, preparePage) {
  const steps = [
    { id: 'shapes', title: 'Read the system', paragraphs: [], items: [
      { label: 'Shapes', text: 'The sphere represents the system, cubes are talkgroups, and pyramids are radios.' },
      { label: 'Connections', text: 'Solid lines show the last accepted affiliation. Faint lines show earlier affiliations; dashed lines show call activity.' }
    ], owner: '.network-visualizer-stage' },
    { id: 'manual-button', title: 'Take the camera',
      paragraphs: ['Select Manual to explore the system from your own viewpoint.'], owner: '.network-visualizer-toolbar' },
    { id: 'manual-camera', title: 'Rotate and look closer',
      paragraphs: ['Drag to rotate around the system. Use the wheel to zoom toward the pointer.'] },
    { id: 'auto-camera', title: 'Let the view follow activity',
      paragraphs: ['Select Auto to center the system and rotate the view. Noteworthy activity can draw the camera toward the radios involved.'],
      owner: '.network-visualizer-toolbar' }
  ];
  return steps.map(({ owner, ...step }, index) => {
    const focus = () => [...(documentValue?.querySelectorAll(`[data-guide-target="p25-${step.id}"]`) || [])];
    return { ...step, compact: true, targets: focus, contextTargets: contextualTargets(focus, owner),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('network-visualizer', step.id, preparePlayer, preparePage) };
  });
}
