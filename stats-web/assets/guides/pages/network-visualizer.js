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
    { id: 'actions-button', title: 'Choose which actions draw attention',
      paragraphs: ['Open Actions to see how often each action occurs and choose what the view follows.'],
      owner: '.network-visualizer-toolbar' },
    { id: 'actions', title: 'See the busy and rare actions', paragraphs: [
      'Each count starts when this page opens. Choose Zoom to for automatic camera movement and Highlight for a brief emphasis, then Save choices.'
    ], owner: '.network-visualizer-events' },
    { id: 'actions-close', title: 'Return to the system',
      paragraphs: ['Close Actions to give the system the full view.'], owner: '.network-visualizer-events' },
    { id: 'manual-button', title: 'Take the camera',
      paragraphs: ['Select Manual to explore the system from your own viewpoint.'], owner: '.network-visualizer-toolbar' },
    { id: 'manual-camera', title: 'Rotate and look closer',
      paragraphs: ['Drag to rotate around the system. Use the wheel to zoom toward the pointer.'] },
    { id: 'auto-camera', title: 'Let the view follow activity',
      paragraphs: ['Select Auto to center the system and rotate the view. Actions selected for Zoom to bring the radios into view. Affiliation changes keep both talkgroups visible as the radio moves.'],
      owner: '.network-visualizer-toolbar' }
  ];
  return steps.map(({ owner, ...step }, index) => {
    const focus = () => [...(documentValue?.querySelectorAll(`[data-guide-target="p25-${step.id}"]`) || [])];
    return { ...step, compact: true, targets: focus, contextTargets: contextualTargets(focus, owner),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('network-visualizer', step.id, preparePlayer, preparePage) };
  });
}
