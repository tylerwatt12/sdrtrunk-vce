import { pageGuidePresentation } from '../presentation.js?v=1';

export function recordingsSteps(documentValue, preparePlayer, preparePage) {
  const steps = [
    { id: 'targets-button', title: 'Find the traffic you want',
      paragraphs: ['Narrow your search to particular talkgroups or conventional channels.'] },
    { id: 'targets', title: 'Bring related traffic together',
      paragraphs: ['Select several talkgroups or channels to search them together. Use selection brings your choices back to the search panel.'] },
    { id: 'time-range', title: 'Search a particular period',
      paragraphs: ['Choose the time period you want to hear.'] },
    { id: 'search', title: 'Apply your choices',
      paragraphs: ['Select Search recordings to apply your time period and traffic choices.'] },
    { id: 'transcript-search', title: 'Search what was said',
      paragraphs: ['Enter words or put an exact phrase in quotes. Transcript searches use the past 24 hours by default. Choose Use Time filter to search your selected period.'] },
    { id: 'transcript-submit', title: 'Find matching calls',
      paragraphs: ['Select Search recordings to find calls containing those words.'] },
    { id: 'details-button', title: 'Take a closer look',
      paragraphs: ['Open Call details to inspect this transmission.'] },
    { id: 'details', title: 'Follow the details', paragraphs: [], items: [
      { label: 'Transcript', text: 'The saved conversation text.' },
      { label: 'Identities', text: 'The talkgroup and radio IDs.' },
      { label: 'Technical', text: 'Reception and signaling details.' }
    ] },
    { id: 'details-close', title: 'Back to your search',
      paragraphs: ['Close Call details to return to the matching calls.'] },
    { id: 'actions-button', title: 'Choose where playback begins',
      paragraphs: ['Open Call actions for playback and conversation search options.'] },
    { id: 'playback', title: 'Replay one call or follow the exchange', paragraphs: [], items: [
      { label: 'Play once', text: 'Start a queue containing only this call.' },
      { label: 'Continue from here', text: 'Play this call, then newer calls matching your search in time order.' },
      { label: 'Add to queue', text: 'Put this call at the end of your queue.' }
    ] },
    { id: 'player-button', title: 'See what’s coming next',
      paragraphs: ['Expand the player to see the current call and the calls waiting to play.'] },
    { id: 'click-mode', title: 'Choose what the Play buttons do',
      paragraphs: ['Set whether selecting a call adds it to your queue, plays it alone, or continues through matching calls.'] }
  ];
  return steps.map((step, index) => ({ ...step,
    compact: !['details', 'playback'].includes(step.id),
    targets: () => {
      const names = step.id === 'transcript-search' ? ['transcript-search', 'transcript-range'] :
        step.id === 'transcript-submit' ? ['search'] : [step.id];
      return names.flatMap(name => [...(documentValue?.querySelectorAll(`[data-guide-target="rec-${name}"]`) || [])]);
    },
    nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
    ...pageGuidePresentation('recordings', step.id, preparePlayer, preparePage, { restoreScroll: true })
  }));
}
