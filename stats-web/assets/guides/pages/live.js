import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

function targets(documentValue, name) {
  return () => [...(documentValue?.querySelectorAll(`[data-guide-target="lv-${name}"]`) || [])];
}

export function liveOverviewPresentation(preparePlayer, preparePage) {
  return { samplePresentation: true, ...pageGuidePresentation('live', 'overview', preparePlayer, preparePage) };
}

export function liveSteps(documentValue, preparePlayer, preparePage) {
  const contexts = {
    reception: 'tr', 'detail-views': '.live-details', pause: '.live-details',
    'event-filters': '.live-details', 'messages-tab': '.live-details', 'channel-tab': '.live-details',
    presentation: '.live-selected-view-header'
  };
  const steps = [
    { id: 'views', title: 'Choose a view',
      paragraphs: ['Choose a trunked site to follow its activity. Conventional brings your running conventional channels together.'] },
    { id: 'activity', title: 'Follow a call',
      paragraphs: ['Source identifies the transmitting radio. Target identifies the talkgroup or radio being called. Rows update as transmissions begin and end.'] },
    { id: 'reception', title: 'Judge reception',
      paragraphs: ['Read signal strength and decoding quality together. CC describes control-channel decoding; VC describes the voice call.'] },
    { id: 'select-channel', title: 'Select a channel to inspect',
      paragraphs: ['Select a voice row to follow that frequency in the lower panel. Select the control row to review the trunked system’s events.'] },
    { id: 'detail-views', title: 'Three views of the selected channel', paragraphs: [], items: [
      { label: 'Events', text: 'Calls and radio activity.' },
      { label: 'Messages', text: 'The decoded radio messages behind that activity.' },
      { label: 'Channel', text: 'Signal and symbol displays for investigating reception.' }
    ] },
    { id: 'events', title: 'Review recent activity',
      paragraphs: ['Review recent calls and activity after the transmission ends, with the radios, talkgroups, timing, and details kept together.'] },
    { id: 'pause', title: 'Hold the details still',
      paragraphs: ['Pause the lower panel while you read. Receiving and the main channel table continue updating. Use Resume to bring new details into view.'] },
    { id: 'event-filters', title: 'Focus on useful events',
      paragraphs: ['Narrow the event types shown, or search for the radio, talkgroup, or detail you’re investigating.'] },
    { id: 'messages-tab', title: 'Open decoded messages',
      paragraphs: ['Choose Messages for a closer look at the radio data decoded on the selected frequency.'] },
    { id: 'messages', title: 'Read the signalling',
      paragraphs: ['Read the message text when investigating what a system or radio is reporting. The context identifies the protocol and timeslot where available.'] },
    { id: 'channel-tab', title: 'Open reception diagnostics',
      paragraphs: ['Choose Channel to inspect the selected signal and its demodulated symbols.'] },
    { id: 'channel', title: 'Investigate decoding quality',
      paragraphs: ['Compare the signal and recovered symbols when investigating poor decoding. These displays follow the channel row selected above.'] },
    { id: 'workspace-size', title: 'Give the details more room',
      paragraphs: ['Drag this divider to give either panel more room. Double-click it to restore the default split.'] },
    { id: 'presentation', title: 'Choose how activity appears',
      paragraphs: ['Choose how active and idle rows appear, their order, and whether the last call stays visible. These choices apply to your sign-in.'] }
  ];
  return steps.map((step, index) => {
    const focus = targets(documentValue, step.id);
    return { ...step, compact: step.id !== 'views',
      targets: focus, contextTargets: contextualTargets(focus, contexts[step.id]),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('live', step.id, preparePlayer, preparePage)
    };
  });
}
