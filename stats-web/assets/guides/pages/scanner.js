function targets(documentValue, name) {
  return () => [...(documentValue?.querySelectorAll(`[data-guide-target="sc-${name}"]`) || [])];
}

function presentation(section, preparePlayer, preparePage) {
  return {
    samples: [{ id: 'scanner-workspace', value: { section } }],
    prepare: async context => {
      const windowValue = globalThis.window;
      const scroll = windowValue ? { left: windowValue.scrollX, top: windowValue.scrollY,
        href: windowValue.location.href } : null;
      const restoreScroll = () => {
        if (scroll && windowValue.location.href === scroll.href)
          windowValue.scrollTo({ left: scroll.left, top: scroll.top, behavior: 'instant' });
      };
      const releasePlayer = await preparePlayer?.({ minimized: true });
      try {
        const releasePage = await preparePage?.('scanner', section, context);
        return () => { try { releasePage?.(); } finally {
          try { releasePlayer?.(); } finally { restoreScroll(); }
        } };
      } catch (error) {
        try { releasePlayer?.(); } finally { restoreScroll(); }
        throw error;
      }
    }
  };
}

export function scannerSteps(documentValue, preparePlayer, preparePage, canView, canAccess) {
  const radio = typeof canAccess !== 'function' || canAccess('radio') !== false;
  const steps = [
    { id: 'lists-button', title: 'Choose your listening groups',
      paragraphs: ['Open Scan Lists to choose the groups you want to hear, including traffic from different radio systems.'] },
    { id: 'lists', title: 'Bring related traffic together',
      paragraphs: ['Law Enforcement brings County Sheriff and City Police together. Fire/EMS combines Regional Fire and City Fire. Select the groups you want to follow.'] },
    { id: 'lists-close', title: 'Return to the call display',
      paragraphs: ['Select Scan Lists again to return to the call display.'] },
    { id: 'call', title: 'Follow who’s talking',
      paragraphs: ['The large heading identifies the talkgroup. Transmitting identifies the radio speaking, with the system and site shown alongside the call.'] },
    { id: 'queue', title: 'Hear calls in turn',
      paragraphs: ['When several calls arrive together, VCE reorders them in queue so you hear them in turn.'] },
    { id: 'playback', title: 'Control playback', paragraphs: [], items: [
      { label: 'Pause', text: 'Hold your place while more calls collect in the limited queue.' },
      { label: 'Stop', text: 'Clear waiting calls. Play starts listening again.' },
      { label: 'Replay', text: 'Repeat the last call that finished playing.' }
    ] },
    { id: 'hold', title: 'Stay with a conversation',
      paragraphs: ['H stands for Hold. Use it to stay with the current talkgroup or conventional channel. Select H again to return to your selected Scan Lists.'] },
    { id: 'avoid', title: 'Set a talkgroup aside',
      paragraphs: ['Avoid skips the current talkgroup or channel and leaves it out of listening for this browser session.'] },
    { id: 'detail-level', title: 'Choose your level of detail',
      paragraphs: ['Use Simple or Normal for everyday listening. Advanced adds radio identifiers and system details. Engineer adds call timing and detailed voice decoding results.'] },
    { id: 'details', title: 'Investigate a call', radio: true,
      paragraphs: ['Select a linked talkgroup or radio name to explore its calls and activity.'] },
    { id: 'settings-button', title: 'Choose how conversations take turns',
      paragraphs: ['Open Scanner settings to choose how waiting calls take turns.'] },
    { id: 'playback-order', title: 'Keep related calls together',
      paragraphs: ['Keep waiting calls on the same talkgroup together. Calls before switching targets sets how many play before another talkgroup gets a turn.'] },
    { id: 'settings-close', title: 'Return to listening',
      paragraphs: ['Close Scanner settings to return to the call display.'] },
    { id: 'avoids-button', title: 'Bring back avoided traffic',
      paragraphs: ['Open Avoids when you want to hear an avoided talkgroup or channel again.'] },
    { id: 'avoids', title: 'Hear it again',
      paragraphs: ['Select Remove beside Sheriff Dispatch to include its calls in your listening again.'] },
    { id: 'avoids-close', title: 'Return to Scanner',
      paragraphs: ['Close the Avoid List to return to Scanner.'] }
  ].filter(step => !step.radio || radio);
  return steps.map(({ radio: requiresRadio, ...step }, index) => ({ ...step,
    compact: !['call', 'playback', 'details', 'playback-order'].includes(step.id),
    targets: targets(documentValue, step.id),
    nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
    ...presentation(step.id, preparePlayer, preparePage)
  }));
}
