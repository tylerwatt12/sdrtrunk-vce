import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

function targets(documentValue, name) {
  return () => [...(documentValue?.querySelectorAll(`[data-guide-target="mn-${name}"]`) || [])];
}

export function mainOverviewItems(canAccess) {
  const radio = typeof canAccess !== 'function' || canAccess('radio') !== false;
  return [
    { label: 'Overview', text: 'Check reception across your systems and channels.' },
    { label: 'Call Statistics', text: 'Compare call totals and find busy talkgroups and radios.' },
    { label: 'Radio Activity', text: radio ? 'Find the radios involved in notable events.' : 'Explore decoded system events.' },
    ...(radio ? [{ label: 'Alias Inventory', text: 'Review alias activity and find unknown talkgroups.' }] : [])
  ];
}

export function mainSteps(documentValue, preparePlayer, preparePage, canView, canAccess) {
  const radio = typeof canAccess !== 'function' || canAccess('radio') !== false;
  const contexts = {
    reception: '[data-directory-card-key]', 'calls-tab': '.ui-page-nav-tabs',
    'call-history': '.dashboard-call-activity', 'activity-tab': '.ui-page-nav-tabs',
    'event-type': '.dashboard-activity-layout', 'inventory-tab': '.ui-page-nav-tabs',
    'alias-list': '.alias-coverage-header', 'unknown-tab': '.alias-coverage-table-header'
  };
  const steps = [
    { id: 'systems', title: 'Your systems and sites',
      paragraphs: ['Sites are grouped under their radio system.' + (radio ?
        ' Select a system or site name to explore its activity and details.' : '')] },
    { id: 'reception', title: 'Check reception',
      paragraphs: ['Review your channels and compare reception across your systems. Signal level and decoding quality help you find channels that need attention.'] },
    { id: 'calls-tab', title: 'Open Call Statistics',
      paragraphs: ['Find patterns in the traffic VCE has heard, including busy periods and frequently used talkgroups and radios.'] },
    { id: 'call-totals', title: 'Compare call totals',
      paragraphs: ['Compare total calls with those saved as recordings or sent for streaming. A call can count in both.',
        'Here, 80% of calls were recorded and 25% were sent for streaming.'] },
    { id: 'call-history', title: 'Compare activity over time',
      paragraphs: ['See how call volume changes through the day. Hover over the chart to inspect the counts for each hour.'] },
    { id: 'identities', title: 'Find the most active users', radio: true,
      paragraphs: ['Find your busiest talkgroups and radios. Select a name to explore its calls and activity in more detail.'] },
    { id: 'activity-tab', title: 'Open Radio Activity', radio: true,
      paragraphs: ['Investigate events such as Emergency, Denied, Busy, Stun, Kill, and Radio inhibit. Choose an activity type to find the source radios involved.'] },
    { id: 'event-type', title: 'Choose an event type', radio: true,
      paragraphs: ['Select Emergency to find radios that sent emergency signaling.'] },
    { id: 'activity-radios', title: 'Find the radios involved', radio: true,
      paragraphs: ['See which radios sent these events, how often, and when they were last heard. Select a radio to investigate its activity.'] },
    { id: 'inventory-tab', title: 'Open Alias Inventory', radio: true,
      paragraphs: ['Browse alias names and statistics, see when they were last heard, and find unknown talkgroups worth identifying.'] },
    { id: 'alias-list', title: 'Choose an Alias List', radio: true,
      paragraphs: ['Choose an Alias List to compare its configured names with the activity VCE has heard.'] },
    { id: 'configured', title: 'See which aliases are used', radio: true,
      paragraphs: ['Calls and Signals show totals since VCE began tracking the alias. Last heard helps you find active and quiet entries.'] },
    { id: 'unknown-tab', title: 'Find traffic needing an alias', radio: true,
      paragraphs: ['Find received talkgroups that need a matching alias.'] },
    { id: 'unknown', title: 'Identify traffic worth naming', radio: true,
      paragraphs: ['Review the talkgroup IDs, calls, and channels to help identify traffic worth naming. Select an identity to explore its history.'] }
  ].filter(step => !step.radio || radio);
  return steps.map(({ radio: requiresRadio, ...step }, index) => {
    const focus = targets(documentValue, step.id);
    return { ...step,
      compact: !['systems', 'call-totals', 'call-history', 'identities', 'configured', 'unknown'].includes(step.id),
      targets: focus, contextTargets: contextualTargets(focus, contexts[step.id]),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('dashboard', step.id, preparePlayer, preparePage, { restoreScroll: true })
    };
  });
}
