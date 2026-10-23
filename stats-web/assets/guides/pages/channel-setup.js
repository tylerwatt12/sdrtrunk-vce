function targets(documentValue, ...names) {
  return () => names.flatMap((name) =>
    [...(documentValue?.querySelectorAll(`[data-guide-target="${name}"]`) || [])]);
}

function allowed(documentValue, canView, view) {
  if (typeof canView === 'function') return canView(view) !== false;
  const control = documentValue?.querySelector?.(`.primary-nav [data-view="${view}"]`);
  return !control || (!control.hidden && !control.closest?.('[hidden]'));
}

export function channelSetupSteps(documentValue, preparePlayer, preparePage, canView) {
  const prepare = (section) => (context) => preparePage?.('channel-setup', section, context);
  const guideSample = (section) => ({ id: 'channel-guide', value: { section } });
  const catalogSample = (selected = true) => ({ id: 'channel-catalog', value: {
    channels: [{ configuration_id: 'guide-sample-channel', name: 'Sample channel', system: 'Example system',
      protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', processing_state: 'STOPPED',
      frequencies_hz: [851012500], editable: true, alias_list_name: 'Sample aliases',
      alias_list_id: null, auto_start_order: null }],
    selected_channel_ids: selected ? ['guide-sample-channel'] : []
  } });
  const canImport = allowed(documentValue, canView, 'radioreference');
  const canDiscover = allowed(documentValue, canView, 'tuners');
  const canListen = allowed(documentValue, canView, 'scanner');
  const steps = [
    { id: 'new-channel', title: 'Add a channel',
      targets: targets(documentValue, 'channel-new'),
      paragraphs: [canImport ? 'Enter a known frequency or bring in channel information from RadioReference.' :
        'Enter a known frequency to create a channel.'] },
    { id: 'channel-choice', title: 'Choose your source', prepare: prepare('channel-choice'),
      samples: [guideSample('channel-choice')],
      targets: targets(documentValue, 'channel-choice'),
      paragraphs: [canImport ? 'RadioReference brings in system and channel details. Use a known frequency to create a channel from scratch.' :
        'Create a channel from scratch using a known frequency.'] },
    { id: 'channel-choice-close', title: 'Return to your channels', prepare: prepare('channel-choice-close'),
      samples: [guideSample('channel-choice-close')],
      targets: targets(documentValue, 'channel-choice-close'),
      paragraphs: ['Close this window to return to your channel list.'] },
    { id: 'discover', title: 'Search for trunked systems',
      targets: targets(documentValue, 'channel-discover'),
      paragraphs: ['Search supported bands for trunked systems your tuner can receive, then add the systems you choose. Running channels may need to stop temporarily during the search.'] },
    { id: 'select-channel', title: 'Choose a channel to start', samples: [catalogSample(false)],
      targets: targets(documentValue, 'channel-sample-select'),
      paragraphs: ['Select a channel to show its Start action.'] },
    { id: 'receiving', title: 'Start receiving',
      samples: [catalogSample()],
      targets: () => [
        ...(documentValue?.querySelectorAll('[data-guide-sample] .channel-name-summary, [data-guide-sample] td[data-label="State"], [data-guide-target="channel-sample-start"]') || [])
      ],
      paragraphs: ['Start a channel to begin receiving. For a trunked system, VCE receives the control channel and follows voice traffic.'] },
    { id: 'startup', title: 'Ready when you are', samples: [catalogSample()],
      targets: targets(documentValue, 'channel-startup'),
      paragraphs: ['Choose which channels begin receiving when VCE starts, and the order in which they start.'] },
    { id: 'listening-controls', title: 'Open your listening choices', prepare: prepare('listening-controls'),
      samples: [catalogSample(), guideSample('listening-controls')],
      targets: targets(documentValue, 'player-lists'),
      paragraphs: ['Open Lists in the player to choose your listening groups.'] },
    { id: 'listening', title: 'Choose what you hear', prepare: prepare('listening'),
      samples: [catalogSample(), guideSample('listening')],
      targets: targets(documentValue, 'scan-lists-player-lists'),
      paragraphs: ['Select the Scan Lists you want to hear. VCE plays each completed call after the transmission finishes.'] }
  ].filter((step) => (step.id !== 'discover' || canDiscover) &&
    (!['listening-controls', 'listening'].includes(step.id) || canListen));
  return steps.map((step, index) => index === steps.length - 1 ? { ...step, nextLabel: 'Finish' } : step);
}
