import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

function targets(documentValue, name) {
  return () => [...(documentValue?.querySelectorAll(`[data-guide-target="bs-${name}"]`) || [])];
}

export function spectrumOverviewPresentation(preparePlayer, preparePage) {
  return { samplePresentation: true, ...pageGuidePresentation('tuner-spectrum', 'overview', preparePlayer, preparePage) };
}

export function spectrumSteps(documentValue, preparePlayer, preparePage, canView, canAccess) {
  const allowed = capability => typeof canAccess !== 'function' || canAccess(capability) !== false;
  const contexts = {
    receiver: '.spectrum-browse-toolbar', 'reset-zoom': '.spectrum-browse-toolbar',
    'channel-marker': '.tuner-spectrum-visual-window', pause: '.spectrum-browse-toolbar',
    'frequency-actions': '.spectrum-browse-control-rail',
    listen: '.spectrum-browse-control-rail', lookup: '.spectrum-browse-control-rail',
    'add-channel': '.spectrum-browse-control-rail', modes: '.spectrum-browse-toolbar',
    'display-options': '.spectrum-browse-toolbar', 'display-settings': '.tuner-spectrum-options-panel',
    'plot-settings': '.tuner-spectrum-options-panel', performance: '.tuner-spectrum-options-panel',
    'display-close': { owner: '.tuner-spectrum-options', surfaces: '.tuner-spectrum-options-panel' }
  };
  const steps = [
    { id: 'receiver', title: 'Choose your receiver',
      paragraphs: ['Choose the receiver covering the frequencies you want to inspect.'] },
    { id: 'activity', title: 'Read the signals', compact: true,
      paragraphs: ['The FFT shows signal strength now. The waterfall keeps a history, with new transmissions appearing at the top.',
        'The strip above the FFT identifies the frequency band in view.'] },
    { id: 'measurements', title: 'Compare the signals in view',
      paragraphs: ['Visible span and Zoom describe the view. Peak shows the strongest signal level. Best SNR estimates how far received channels stand above the noise.',
        'More measurements opens finer FFT and timing details.'] },
    { id: 'zoom', title: 'Look closer', compact: true,
      paragraphs: ['Point at a signal and roll the mouse wheel to zoom around it. The zoom buttons also change the view.'],
      hint: 'Keyboard: + and − zoom, arrow keys pan, and R resets the view.' },
    { id: 'pan', title: 'Move around the view', compact: true,
      paragraphs: ['When zoomed in, click and drag either plot to inspect nearby frequencies within this tuner’s receiving window.'] },
    { id: 'reset-zoom', title: 'Return to the full window',
      paragraphs: ['Use Reset zoom to see the full receiving window again.'] },
    { id: 'channel-marker', title: 'See who is using a signal', capability: 'live',
      paragraphs: ['Hover over a colored channel marker to see the system, talkgroup, radio, and reception details. These markers signify a frequency that VCE knows about.'] },
    { id: 'channel-details', title: 'Read the channel details', capability: 'live',
      paragraphs: ['See the channel, talkgroup, and radio behind this signal. Quality readings help you judge how well VCE is decoding it.',
        'Move away from the marker to return to the plots.'] },
    { id: 'waterfall', title: 'Inspect an earlier transmission', compact: true,
      paragraphs: ['Hover over a waterfall trace to read its frequency and the time it was received. Gold dividers mark tuner retunes.'] },
    { id: 'waterfall-details', title: 'Follow the receiving history',
      paragraphs: ['Each row keeps its original frequency and time. Hovering over a gold divider also shows the old and new center frequencies.'] },
    { id: 'pause', title: 'Hold the display still',
      paragraphs: ['Pause holds the FFT and waterfall still for inspection. Receiving continues. Resume brings the display back to live activity.'] },
    { id: 'select-signal', title: 'Select a signal',
      paragraphs: ['Hover over the FFT to read a signal’s frequency and level. Click a signal in either plot to open its frequency actions.'] },
    { id: 'frequency-actions', title: 'Work with this frequency',
      paragraphs: ['Use this panel to investigate the selected frequency and choose how to receive it.'] },
    { id: 'listen', title: 'Preview analog FM', capability: 'call-audio',
      paragraphs: ['Listen to an analog FM signal to hear what is using the frequency.'] },
    { id: 'lookup', title: 'Identify the user', capability: 'admin-settings',
      paragraphs: ['Find matching entries in your region to help identify the signal.'] },
    { id: 'add-channel', title: 'Add what you found', capability: 'admin-channels',
      paragraphs: ['Detect a trunked system automatically, or open Create Channel in a new tab with this frequency to choose the protocol yourself.'] },
    { id: 'modes', title: 'Tune through the band',
      paragraphs: ['Setup temporarily stops this tuner’s channels so you can tune freely. In the full view, click and drag to move the receiving window, or enter a center frequency above.',
        'Return to Live to resume the channels this session stopped.'] },
    { id: 'display-options', title: 'Adjust the display',
      paragraphs: ['Open Display options to adjust signal visibility, frequency selection, and the detail shown in the plots.'] },
    { id: 'display-settings', title: 'Set the contrast and frequency spacing',
      paragraphs: ['Auto range adjusts the contrast as you tune. Turn it off to set the display limits yourself.',
        'Snap frequency follows the channel spacing used in supported bands.'] },
    { id: 'plot-settings', title: 'Make the signals easier to inspect', paragraphs: [], items: [
      { label: 'FFT', text: allowed('live') ? 'Smooth FFT makes it easier to distinguish signals from noise by averaging the signals on screen. Idle channel markers keep channel positions visible between calls.' : 'Smooth FFT makes it easier to distinguish signals from noise by averaging the signals on screen.' },
      { label: 'Waterfall', text: 'Choose how quickly recent activity moves down the display.' }
    ] },
    { id: 'performance', title: 'Separate closely spaced signals',
      paragraphs: ['Higher-detail profiles show more frequency detail and use more CPU. Choose a profile that keeps receiving smoothly on your computer.'] },
    { id: 'display-close', title: 'Return to browsing',
      paragraphs: ['Close Display options to return to the plots and your selected frequency.'] }
  ].filter(step => !step.capability || allowed(step.capability));
  return steps.map(({ capability, ...step }, index) => {
    const focus = targets(documentValue, step.id);
    return { ...step,
      targets: focus, contextTargets: contextualTargets(focus, contexts[step.id]),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('tuner-spectrum', step.id, preparePlayer, preparePage)
    };
  });
}
