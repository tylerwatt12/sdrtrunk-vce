import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

export function topicsSteps(documentValue, preparePlayer, preparePage) {
  const steps = [
    { id: 'read-alerts', title: 'Follow a topic',
      paragraphs: ['Catch up on activity published to this topic.'], owner: '.alerts-topic-card' },
    { id: 'recent-alerts', title: 'Review recent activity',
      paragraphs: ['See what happened and when. Select an alert’s title to explore its details.'] },
    { id: 'notifications', title: 'Choose your notifications',
      paragraphs: ['Choose which topics send you notifications. Return here to change your subscriptions.'],
      owner: '.alerts-public-sidebar > header' }
  ];
  return steps.map(({ owner, ...step }, index) => {
    const focus = () => [...(documentValue?.querySelectorAll(`[data-guide-target="topic-${step.id}"]`) || [])];
    return { ...step, compact: true, targets: focus,
      contextTargets: contextualTargets(focus, owner),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('topics', step.id, preparePlayer, preparePage, { restoreScroll: true })
    };
  });
}
