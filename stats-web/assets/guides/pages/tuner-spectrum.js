function targets(documentValue, name) {
  return () => [...(documentValue?.querySelectorAll(`[data-guide-target="bs-${name}"]`) || [])];
}

function presentation(section, preparePlayer, preparePage) {
  return {
    samples: [{ id: 'tuner-spectrum-workspace', value: { section } }],
    prepare: async context => {
      const releasePlayer = await preparePlayer?.({ minimized: true });
      try {
        const releasePage = await preparePage?.('tuner-spectrum', section, context);
        return () => { try { releasePage?.(); } finally { releasePlayer?.(); } };
      } catch (error) {
        releasePlayer?.();
        throw error;
      }
    }
  };
}

export function spectrumOverviewPresentation(preparePlayer, preparePage) {
  return { samplePresentation: true, ...presentation('overview', preparePlayer, preparePage) };
}

export function spectrumSteps(documentValue, preparePlayer, preparePage, canView, canAccess) {
  const allowed = capability => typeof canAccess !== 'function' || canAccess(capability) !== false;
  const steps = [
    { id: 'receiver', title: 'Choose your receiver',
      paragraphs: ['Choose the receiver covering the frequencies you want to inspect.'] },
    { id: 'activity', title: 'Follow the activity', compact: true,
      paragraphs: ['Colored markers identify traffic VCE is decoding. The waterfall keeps short transmissions visible after they end.'] },
    { id: 'select-signal', title: 'Select a signal',
      paragraphs: ['Select a signal in the spectrum or waterfall to see its frequency and available actions.'] },
    { id: 'listen', title: 'Preview analog FM', capability: 'call-audio',
      paragraphs: ['Listen to an analog FM signal to hear what is using the frequency.'] },
    { id: 'lookup', title: 'Identify the user', capability: 'admin-settings',
      paragraphs: ['Find matching entries in your region to help identify the signal.'] },
    { id: 'add-channel', title: 'Add what you found', capability: 'admin-channels',
      paragraphs: ['Open the channel wizard to choose the radio type, identify the signal, and review its receiving configuration.'] },
    { id: 'modes', title: 'Explore another frequency range',
      paragraphs: ['Setup temporarily stops this tuner’s channels so you can tune freely. Return to Live to resume them.'] },
    { id: 'display-options', title: 'Adjust the display',
      paragraphs: ['Adjust signal visibility, frequency snapping, and spectrum detail. Higher-detail profiles use more CPU.'] }
  ].filter(step => !step.capability || allowed(step.capability));
  return steps.map(({ capability, ...step }, index) => ({ ...step,
    targets: targets(documentValue, step.id),
    nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
    ...presentation(step.id, preparePlayer, preparePage)
  }));
}
