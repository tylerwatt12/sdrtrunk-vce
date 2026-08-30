'use strict';

  function alert(id, name, description) {
    return Object.freeze({ id, name, description });
  }

  function group(id, name, description, alerts) {
    return Object.freeze({ id, name, description, alerts: Object.freeze(alerts) });
  }

  const receiverHealthAlertGroups = Object.freeze([
    group('receiver', 'Receiver and tuners', 'Tuner availability and the first receiver sample queue.', [
      alert('tuner-error', 'Tuner error', 'A tuner reports that it cannot continue receiving normally.'),
      alert('receiver-iq-drop', 'Receiver samples discarded',
        'Incoming tuner samples were discarded before channel processing.'),
      alert('receiver-queue-pressure', 'Receiver queue nearly full',
        'The receiver sample queue stayed close to its limit.'),
      alert('tuner-allocation-failure', 'Tuner allocation failed',
        'A control or traffic channel could not obtain a tuner source.')
    ]),
    group('usb', 'USB delivery', 'Sample transfer between USB tuners and the receiver.', [
      alert('usb-sample-loss', 'USB sample delivery incomplete',
        'A USB tuner delivered missing, malformed, or substantially incomplete sample data.'),
      alert('usb-transfer-gap', 'USB transfer paused',
        'A USB tuner paused long enough to put decoding at risk.'),
      alert('usb-transfer-pool-degraded', 'USB transfer capacity degraded',
        'One or more USB transfer buffers could not be kept active.')
    ]),
    group('channels', 'Channel processing', 'Channelizer, decoder, and control-channel continuity.', [
      alert('channelizer-drop', 'Channelizer output discarded',
        'The channelizer discarded output before it reached extracted channels.'),
      alert('channelizer-queue-pressure', 'Channelizer queue nearly full',
        'The channelizer worker stayed close to its queue limit.'),
      alert('channel-queue-pressure', 'Channel queue nearly full',
        'An individual channel output queue stayed close to its limit.'),
      alert('channel-output-drop', 'Channel decoder missed samples',
        'An individual channel decoder missed sample batches.'),
      alert('control-channel-lock-lost', 'Control-channel lock lost',
        'An active control channel stopped producing valid control frames.')
    ]),
    group('host', 'Host resources', 'CPU, memory, garbage collection, and storage.', [
      alert('host-cpu-pressure', 'Host CPU saturated',
        'Receiver host CPU usage stayed high enough to threaten processing deadlines.'),
      alert('heap-pressure', 'JVM heap nearly full',
        'The application used nearly all of its configured Java memory.'),
      alert('gc-pause', 'Long garbage collection',
        'Garbage collection consumed substantial time during a health sample.'),
      alert('disk-space', 'Storage running low',
        'The application data volume has little free space remaining.')
    ]),
    group('outputs', 'Audio and outputs', 'Completed-call recording, streaming, and browser audio.', [
      alert('audio-coordinator-ingress', 'Completed-call handoff dropped',
        'A completed-call or lifecycle event could not enter the output coordinator.'),
      alert('audio-coordinator-aborted', 'Completed call aborted',
        'The output coordinator rejected or abandoned a completed call.'),
      alert('audio-output-pressure', 'Completed-call coordinator nearly full',
        'Recording, streaming, or browser-call completion work is falling behind.'),
      alert('recording', 'Call recording dropped',
        'A completed call could not be written to the recording output.'),
      alert('recording-output-pressure', 'Recording queue nearly full',
        'The recording writer is close to exhausting its bounded queue.'),
      alert('streaming', 'Call streaming output lost',
        'A completed call could not be encoded or delivered to a configured streamer.'),
      alert('streaming-output-pressure', 'Streaming queue nearly full',
        'The streaming writer is close to exhausting its bounded queue.'),
      alert('web-audio-drop', 'Browser call audio lost',
        'A completed call could not be encoded for browser listeners.')
    ])
  ]);

  const receiverHealthAlertIds = Object.freeze(receiverHealthAlertGroups.flatMap(({ alerts }) =>
    alerts.map(({ id }) => id)));

  function isReceiverHealthAlertEnabled(preferences, incidentCode) {
    const disabledCodes = preferences?.health_alerts?.disabled_codes;
    return !Array.isArray(disabledCodes) || typeof incidentCode !== 'string' ||
      !disabledCodes.includes(incidentCode);
  }

export { receiverHealthAlertGroups, receiverHealthAlertIds, isReceiverHealthAlertEnabled };
