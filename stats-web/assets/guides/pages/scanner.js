import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

function targets(documentValue, name) {
  return () => [...(documentValue?.querySelectorAll(`[data-guide-target="sc-${name}"]`) || [])];
}

export function scannerSteps(documentValue, preparePlayer, preparePage, canView, canAccess) {
  const radio = typeof canAccess !== 'function' || canAccess('radio') !== false;
  const contexts = {
    'lists-button': '.scanner-playback-transport', 'lists-menu': '.audio-dock', lists: '.audio-dock',
    'lists-back': '.audio-dock', 'lists-menu-close': '.audio-dock', call: '.scanner-display',
    playback: '.scanner-playback-transport', hold: '.scanner-playback-transport', avoid: '.scanner-playback-transport',
    'detail-level': '.scanner-header-actions', 'settings-button': '.scanner-playback-transport',
    'settings-menu': '.audio-dock', 'playback-order': '.audio-dock', 'settings-close': '.audio-dock',
    'avoids-button': '.audio-dock', avoids: '.audio-dock', 'avoids-close': '.audio-dock', 'player-close': '.audio-dock'
  };
  const steps = [
    { id: 'lists-button', title: 'Choose your listening mix',
      paragraphs: ['Open Player menu to choose what you hear and adjust playback.'] },
    { id: 'lists-menu', title: 'Combine listening groups',
      paragraphs: ['Open Lists to choose the groups whose calls reach your player.'] },
    { id: 'lists', title: 'Choose what you hear',
      paragraphs: ['Select one or more Scan Lists to listen to their calls together.'] },
    { id: 'lists-back', title: 'Return to Player menu',
      paragraphs: ['Use Back to return to Player menu.'] },
    { id: 'lists-menu-close', title: 'Return to Scanner',
      paragraphs: ['Back returns to the call display.'] },
    { id: 'call', title: 'Follow who’s talking',
      paragraphs: ['The large heading identifies the talkgroup. Transmitting identifies the radio speaking, with the system and site shown alongside the call.'] },
    { id: 'queue', title: 'Hear calls in turn',
      paragraphs: ['When several calls arrive together, VCE reorders them in queue so you hear them in turn.'] },
    { id: 'playback', title: 'Control playback', paragraphs: [], items: [
      { label: 'Pause', text: 'Pause audio while calls continue to queue.' },
      { label: 'Stop', text: 'Clear waiting calls. Play starts listening again.' },
      { label: 'Replay', text: 'Repeat the last call that finished playing.' }
    ] },
    { id: 'hold', title: 'Hold',
      paragraphs: ['Select Hold to hear only the current talkgroup or channel. Select Hold again to resume your Scan Lists.'] },
    { id: 'avoid', title: 'Set a talkgroup aside',
      paragraphs: ['Avoid skips the current talkgroup or channel and leaves it out of listening for this browser session.'] },
    { id: 'detail-level', title: 'Choose your level of detail',
      paragraphs: ['Use Simple or Normal for everyday listening. Advanced adds radio identifiers and system details. Engineer adds call timing and detailed voice decoding results.'] },
    { id: 'details', title: 'Investigate a call', radio: true,
      paragraphs: ['Select a linked talkgroup or radio name to explore its calls and activity.'] },
    { id: 'settings-button', title: 'Listening preferences',
      paragraphs: ['Open Player menu to adjust playback order and page title options.'] },
    { id: 'settings-menu', title: 'Open listening preferences',
      paragraphs: ['Listening settings controls how queued calls are grouped and how the playing call appears in page titles.'] },
    { id: 'playback-order', title: 'Playback order',
      paragraphs: ['Group queued calls by talkgroup or channel and set how many play before switching.'] },
    { id: 'settings-close', title: 'Return to Player menu',
      paragraphs: ['Back returns to your listening choices.'] },
    { id: 'avoids-button', title: 'Bring back avoided traffic',
      paragraphs: ['Open Avoid List when you want to hear an avoided talkgroup or channel again.'] },
    { id: 'avoids', title: 'Hear it again',
      paragraphs: ['Select Remove beside Sheriff Dispatch to include its calls in your listening again.'] },
    { id: 'avoids-close', title: 'Return to Player menu',
      paragraphs: ['Back returns to Player menu.'] },
    { id: 'player-close', title: 'Return to Scanner',
      paragraphs: ['Back closes Player menu and returns to the call display.'] }
  ].filter(step => !step.radio || radio);
  return steps.map(({ radio: requiresRadio, ...step }, index) => {
    const focus = targets(documentValue, step.id);
    return { ...step,
      compact: !['call', 'playback', 'details'].includes(step.id),
      targets: focus, contextTargets: contextualTargets(focus, contexts[step.id]),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('scanner', step.id, preparePlayer, preparePage, { restoreScroll: true })
    };
  });
}
