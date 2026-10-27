import { contextualTargets } from '../context.js?v=1';

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
  const contexts = {
    'new-channel': '.channel-catalog-overview', 'channel-choice': '.read-only-modal',
    'channel-choice-close': '.read-only-modal', discover: '.channel-catalog-overview',
    'select-channel': 'tr', receiving: 'tr, .channel-selection-bar', startup: '.channel-admin-toolbar',
    'player-overview': '.audio-dock', 'listening-controls': '.audio-dock', 'listening-menu': '.audio-dock',
    listening: '.audio-dock', 'listening-back': '.audio-dock', 'player-back': '.audio-dock'
  };
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
    { id: 'player-overview', title: 'Listen as you browse', prepare: prepare('player-overview'),
      samples: [catalogSample(), guideSample('player-overview')],
      targets: targets(documentValue, 'player'),
      paragraphs: ['The player plays completed calls from the Scan Lists you choose.'] },
    { id: 'listening-controls', title: 'Open your listening choices', prepare: prepare('listening-controls'),
      samples: [catalogSample(), guideSample('listening-controls')],
      targets: targets(documentValue, 'player-menu'),
      paragraphs: ['Open Player menu for your listening choices and playback preferences.'] },
    { id: 'listening-menu', title: 'Choose your listening groups', prepare: prepare('listening-menu'),
      samples: [catalogSample(), guideSample('listening-menu')],
      targets: targets(documentValue, 'player-menu-lists'),
      paragraphs: ['Open Lists to choose the groups whose calls reach your player.'] },
    { id: 'listening', title: 'Choose what you hear', prepare: prepare('listening'),
      samples: [catalogSample(), guideSample('listening')],
      targets: targets(documentValue, 'scan-lists-player-lists'),
      paragraphs: ['Select the Scan Lists you want to hear. VCE plays each completed call after the transmission finishes.'] },
    { id: 'listening-back', title: 'Return to Player menu', prepare: prepare('listening-back'),
      samples: [catalogSample(), guideSample('listening-back')],
      targets: targets(documentValue, 'player-menu-back'),
      paragraphs: ['Back returns to Player menu.'] },
    { id: 'player-back', title: 'Return to your channels', prepare: prepare('player-back'),
      samples: [catalogSample(), guideSample('player-back')],
      targets: targets(documentValue, 'player-menu-back'),
      paragraphs: ['Back closes Player menu and returns you to your channels.'] }
  ].filter((step) => (step.id !== 'discover' || canDiscover) &&
    (!['player-overview', 'listening-controls', 'listening-menu', 'listening', 'listening-back', 'player-back'].includes(step.id) || canListen));
  return steps.map((step, index) => ({ ...step,
    contextTargets: contextualTargets(step.targets, contexts[step.id]),
    ...(index === steps.length - 1 ? { nextLabel: 'Finish' } : {})
  }));
}
