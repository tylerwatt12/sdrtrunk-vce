'use strict';

  function alert(id, name, description) {
    return Object.freeze({ id, name, description });
  }

  function group(id, name, description, alerts) {
    return Object.freeze({ id, name, description, alerts: Object.freeze(alerts) });
  }

  const receiverHealthAlertGroups = Object.freeze([
    group('receiver', 'Tuners and radio data',
      'Problems receiving data from a tuner or assigning a tuner to a channel.', [
      alert('tuner-error', 'Tuner stopped working',
        'This tuner cannot receive its assigned channels. Check its reported error, USB connection, power, and driver.'),
      alert('receiver-iq-drop', 'Radio data was lost before decoding',
        'The app could not process all data from this tuner. Calls may have gaps or be missed.'),
      alert('receiver-ingress-drop', 'Radio data was lost entering the receiver',
        'The tuner delivered data, but the app could not pass it into receiver processing fast enough.'),
      alert('receiver-listener-failure', 'A receiver component missed radio data',
        'One component could not accept tuner data. Other receiver components continued running.'),
      alert('receiver-queue-pressure', 'Receiver processing is falling behind',
        'Incoming radio data is building up. If this continues, every channel using this tuner may lose data.'),
      alert('tuner-allocation-failure', 'No tuner was available for a channel',
        'No enabled tuner could receive the requested frequency, so the channel may not start.')
    ]),
    group('usb', 'USB connection', 'Problems moving radio data from USB tuners into sdrtrunk-vce.', [
      alert('usb-sample-loss', 'USB tuner data is incomplete',
        'The tuner sent missing or unusable radio data. Signal strength may still look normal while decoding fails.'),
      alert('usb-delivery-rate-low', 'USB tuner data is arriving too slowly',
        'Radio data arrived more slowly than expected. Repeated slowdowns can interrupt decoding.'),
      alert('usb-transfer-gap', 'USB tuner data paused',
        'No radio data arrived for a noticeable time, which can interrupt decoding or clip call audio.'),
      alert('usb-transfer-pool-degraded', 'USB tuner has reduced transfer capacity',
        'The app could not keep all USB transfers running, making data gaps more likely.')
    ]),
    group('channels', 'Channel decoding',
      'Problems separating tuner data into channels and decoding the radio system.', [
      alert('channelizer-drop', 'Channels lost radio data',
        'Data was lost while the app separated one tuner signal into individual channels.'),
      alert('channelizer-queue-pressure', 'Channel separation is falling behind',
        'The app is close to running out of room while separating channels.'),
      alert('channel-queue-pressure', 'One channel is falling behind',
        'Processing for one channel is close to its limit, putting its decoding or audio at risk.'),
      alert('channel-output-drop', 'One or more channels lost radio data',
        'A decoder did not receive all of its data, which can interrupt decoding or audio.'),
      alert('control-channel-lock-lost', 'Control channel stopped decoding',
        'The receiver is no longer getting valid control messages and may miss new calls.')
    ]),
    group('host', 'Computer resources',
      'Processor, memory, and storage problems that can interrupt receiving.', [
      alert('host-cpu-pressure', 'Computer is overloaded',
        'Processor use has stayed high enough that radio processing may fall behind.'),
      alert('heap-pressure', 'sdrtrunk-vce is low on memory',
        'The app is using almost all the memory available to it, which can interrupt receiving.'),
      alert('gc-pause', 'sdrtrunk-vce spent extra time freeing memory',
        'The app spent an unusually long time freeing memory, so receiving may fall behind.'),
      alert('disk-space', 'Storage space is low',
        'The drive holding sdrtrunk-vce application data has little free space remaining.')
    ]),
    group('outputs', 'Recordings and listening',
      'Problems saving calls, sending calls to a streaming service, or preparing browser audio.', [
      alert('audio-coordinator-ingress', 'A call could not finish all output steps',
        'The app could not queue part of the work needed to finish a call for recording, streaming, or browser audio.'),
      alert('audio-coordinator-aborted', 'Output processing stopped for a call',
        'Output processing was overloaded and stopped handling a call.'),
      alert('audio-output-pressure', 'Call outputs are falling behind',
        'Finished calls are arriving faster than recording, streaming, or browser audio processing can handle them.'),
      alert('recording', 'A call recording was not saved',
        'A completed call could not be written to disk.'),
      alert('recording-output-pressure', 'Saving recordings is falling behind',
        'Too many completed calls are waiting to be saved.'),
      alert('streaming', 'A call was not sent to the streaming service',
        'A completed call could not be prepared or delivered to a configured streaming service.'),
      alert('streaming-output-pressure', 'Streaming is falling behind',
        'Too many completed calls are waiting to be sent.'),
      alert('web-audio-drop', 'Browser audio was not available for a call',
        'The app could not prepare one completed call for browser listening.')
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
