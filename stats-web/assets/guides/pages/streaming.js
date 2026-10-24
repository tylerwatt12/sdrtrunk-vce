import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

function targets(documentValue, ...names) {
  return () => names.flatMap(name => [...(documentValue?.querySelectorAll(
    `[data-guide-target="st-${name}"]`) || [])]);
}

export function streamingSteps(documentValue, preparePlayer, preparePage, canView, canAccess) {
  const contexts = {
    'add-button': '.streaming-page-toolbar', provider: '.read-only-modal', 'add-cancel': '.read-only-modal',
    'feeds-button': '.streaming-page-toolbar', feeds: '.read-only-modal', 'feeds-close': '.read-only-modal',
    'manage-button': '.streaming-destination-card', sections: '.read-only-modal',
    'aliases-button': '.read-only-modal', 'assign-alias': '.read-only-modal', 'save-assignments': '.read-only-modal',
    'status-button': '.read-only-modal', delivery: '.read-only-modal', recovery: '.read-only-modal',
    'destination-close': '.read-only-modal'
  };
  const feedButton = documentValue?.querySelector?.('[data-guide-target="st-feeds-button"]');
  const administrationAvailable = typeof canAccess === 'function' ? canAccess('admin-settings') !== false :
    !feedButton || (!feedButton.disabled && !feedButton.hidden && !feedButton.closest?.('[hidden]'));
  const administrationSteps = new Set(['feeds-button', 'feeds', 'feeds-close', 'patch-routing']);
  const steps = [
    { id: 'destinations', title: 'Your destinations',
      paragraphs: ['A destination can bring together traffic from several Alias Lists. Each destination has its own connection and delivery totals.'] },
    { id: 'add-button', title: 'Add another service',
      paragraphs: ['Open Add destination to choose the service that will receive your audio.'] },
    { id: 'provider', title: 'Choose the delivery format',
      paragraphs: ['Feed providers carry a continuous audio stream. Call providers receive individual completed calls.'] },
    { id: 'add-cancel', title: 'Return to your destinations',
      paragraphs: ['Return to your configured destinations.'] },
    { id: 'feeds-button', title: 'Find your Broadcastify feeds',
      paragraphs: ['Bring in connection details for feeds assigned to your RadioReference account.'] },
    { id: 'feeds', title: 'Your assigned feeds',
      paragraphs: ['Added feeds start disabled, ready for you to review their connection settings before enabling them.'] },
    { id: 'feeds-close', title: 'Return to Streaming',
      paragraphs: ['Close this window to return to your destinations.'] },
    { id: 'manage-button', title: 'Review a destination',
      paragraphs: ['Open County Calls to review its connection, assigned traffic, and delivery.'] },
    { id: 'sections', title: 'Three parts of a destination', paragraphs: [], items: [
      { label: 'Settings', text: 'Connection details. Saving changes restarts the sender and may discard queued calls.' },
      { label: 'Aliases', text: 'Choose the traffic sent here.' },
      { label: 'Status', text: 'Follow delivery and investigate problems.' }
    ] },
    { id: 'aliases-button', title: 'Choose the traffic',
      paragraphs: ['Open Aliases to choose which talkgroups and radios send calls to this destination.'] },
    { id: 'assign-alias', title: 'Assign an alias',
      paragraphs: ['Select the aliases whose calls you want to send here. Add more in the alias editor.'] },
    { id: 'save-assignments', title: 'Apply your choices',
      paragraphs: ['Save assignments applies your traffic choices. These same assignments also appear in the Alias Editor.'] },
    { id: 'status-button', title: 'Check delivery',
      paragraphs: ['Open Status to check the connection and follow calls through delivery.'] },
    { id: 'delivery', title: 'Read the delivery totals',
      paragraphs: ['Counters reset when this sender restarts.'], items: [
        { label: 'Queued', text: 'Calls waiting for delivery.' },
        { label: 'Sent / uploaded', text: 'Completed sends or uploads.' },
        { label: 'Aged off', text: 'Calls that became too old to send.' },
        { label: 'Errors', text: 'Problems encountered while sending.' }
      ] },
    { id: 'recovery', title: 'Follow recovery',
      paragraphs: ['Last error records the most recent problem. Watch the current state and delivery counts to follow recovery.'] },
    { id: 'destination-close', title: 'Return to your destinations',
      paragraphs: ['Close this window to return to all destinations.'] },
    { id: 'patch-routing', title: 'Identify patched conversations',
      paragraphs: ['Choose whether patched conversations are identified by the patch ID or individual member talkgroup IDs.'] }
  ].filter(step => administrationAvailable || !administrationSteps.has(step.id));
  return steps.map((step, index) => {
    const focus = targets(documentValue, ...(step.id === 'recovery' ? ['recovery-state', 'recovery-error'] : [step.id]));
    return { ...step,
      targets: focus, contextTargets: contextualTargets(focus, contexts[step.id]),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('streaming', step.id, preparePlayer, preparePage)
    };
  });
}
