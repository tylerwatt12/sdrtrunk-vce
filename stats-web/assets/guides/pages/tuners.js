import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

function targets(documentValue, ...names) {
  return () => names.flatMap(name => [...(documentValue?.querySelectorAll(
    `[data-guide-target="tn-${name}"]`) || [])]);
}

export function tunersSteps(documentValue, preparePlayer, preparePage) {
  const contexts = {
    modes: '.tuners-selected-header', 'setup-button': '.tuners-selected-header', 'enter-setup': '.read-only-modal',
    'receiving-window': '.tuners-readouts', 'center-frequency': '.tuners-readouts', 'lock-center': '.tuners-readouts',
    gain: '.tuners-gain-section', 'frequency-correction': '.ui-section',
    'frequency-limits-button': '.ui-section', 'frequency-limits': '.ui-section',
    'resume-button': '.tuners-recovery', 'resume-channels': '.read-only-modal',
    'disable-button': '.tuners-selected-header', 'disable-tuner': '.read-only-modal',
    disabled: '.tuners-selected-header, .tuner-spectrum-toolbar',
    'restart-setup-button': '.tuners-selected-header', 'restart-channels': '.read-only-modal',
    live: '.tuners-selected-header, .tuners-readouts, .tuners-list-item'
  };
  const steps = [
    { id: 'modes', title: 'Three modes for a tuner', paragraphs: [], items: [
      { label: 'Live', text: 'Available for receiving channels; resumes channels stopped for Setup.' },
      { label: 'Setup', text: 'The receiver stays on for adjustments while its channels stop.' },
      { label: 'Disabled', text: 'Channels stop and the receiver hardware turns off.' }
    ] },
    { id: 'setup-button', title: 'Make receiver adjustments',
      paragraphs: ['Select Setup to adjust this tuner while keeping the receiver on.'] },
    { id: 'enter-setup', title: 'Remember the channels you stopped',
      paragraphs: ['Enter Setup stops this tuner’s channels and keeps the receiver running. VCE remembers which channels were running.'] },
    { id: 'receiving-window', title: 'Choose the receiving window',
      paragraphs: ['Sample rate sets the width of spectrum the tuner receives. Usable bandwidth is the portion VCE can use for receiving channels.'] },
    { id: 'center-frequency', title: 'Position your coverage',
      paragraphs: [
        'Center frequency positions the receiving window over the frequencies you want to receive.',
        'Click the upper half of a digit to increase it, or the lower half to decrease it. To type a frequency, hover over the first digit you want to replace, enter the new digits, and press Enter.'
      ] },
    { id: 'lock-center', title: 'Keep your receiving window in place', paragraphs: [
      'Lock center holds the receiving window in place and disables retuning. Every frequency assigned to that tuner, including trunked voice channels, must fit fully inside its usable bandwidth.',
      'For permanent setups, locking a well-positioned window helps provide stable, consistent coverage. Many SDRs lose sensitivity near the edges, so keep your important frequencies comfortably inside the usable area.'
    ] },
    { id: 'gain', title: 'Adjust reception for your location',
      paragraphs: ['Choose a gain preset for your antenna and local signals. Custom exposes the gain controls available on this receiver. VCE starts with a default gain setting for your tuner, so you may not need to adjust it.'] },
    { id: 'frequency-correction', title: 'Correct frequency error',
      paragraphs: [
        'PPM correction compensates for frequency error in your SDR’s internal clock. Some older or inexpensive dongles need a substantial adjustment, especially models without a temperature-compensated crystal oscillator (TCXO). Their frequency can drift as the dongle warms up or the surrounding temperature changes.',
        'For a large initial error, let the dongle warm up, turn off Automatic PPM correction, and adjust Frequency correction using a known signal. Once reception is working, turn automatic correction back on to follow smaller changes. VCE remembers the latest correction after restarting.'
      ] },
    { id: 'frequency-limits-button', title: 'Choose where this tuner operates',
      paragraphs: ['Open Frequency limits to restrict the frequencies assigned to this tuner.'] },
    { id: 'frequency-limits', title: 'Match each tuner to its antenna',
      paragraphs: [
        'Set the frequency range this tuner should handle. VCE keeps channels assigned to this tuner within that range.',
        'For example, keep a tuner connected to a 700 MHz antenna on your 700 MHz systems, and another connected to an 800 MHz antenna on your 800 MHz systems. Include any additional bands each tuner should cover as a backup.',
        'To favor a tuner for an individual channel, use Channel Setup → Source → Preferred Tuner.'
      ] },
    { id: 'resume-button', title: 'Bring your channels back',
      paragraphs: ['Use Resume & go Live to bring back the channels VCE stopped for Setup.'] },
    { id: 'resume-channels', title: 'Resume reception',
      paragraphs: ['Resume channels restarts the eligible channels and returns the tuner to Live.'] },
    { id: 'disable-button', title: 'Turn off a receiver',
      paragraphs: ['Select Disabled when you want to stop this tuner and its channels.'] },
    { id: 'disable-tuner', title: 'Stop the tuner hardware',
      paragraphs: ['Disable tuner stops its channels and turns off the receiver hardware. VCE remembers the channels for when you return.'] },
    { id: 'disabled', title: 'The receiver is off',
      paragraphs: ['Disabled keeps this receiver off until you start it again in Setup.'] },
    { id: 'restart-setup-button', title: 'Start the receiver again',
      paragraphs: ['Select Setup to turn the receiver on. VCE then offers to resume its previous channels.'] },
    { id: 'restart-channels', title: 'Restore your previous reception',
      paragraphs: ['Resume channels brings back the eligible channels and returns the tuner to Live.'] },
    { id: 'live', title: 'Ready to receive again',
      paragraphs: ['The tuner is back in Live with its previous channels receiving again.'] }
  ];
  return steps.map((step, index) => {
    const focus = targets(documentValue, ...(step.id === 'disabled' ? ['disabled-mode', 'disabled-signal'] :
      step.id === 'live' ? ['live-mode', 'live-count'] : [step.id]));
    return { ...step,
      targets: focus, contextTargets: contextualTargets(focus, contexts[step.id]),
      nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
      ...pageGuidePresentation('tuners', step.id, preparePlayer, preparePage)
    };
  });
}
