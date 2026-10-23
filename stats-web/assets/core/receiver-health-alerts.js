'use strict';

  function alert(id, name, description) {
    return Object.freeze({ id, name, description });
  }

  function group(id, name, description, alerts) {
    return Object.freeze({ id, name, description, alerts: Object.freeze(alerts) });
  }

  const receiverHealthAlertGroups = Object.freeze([
    group('receiver', 'Tuners and radio data', '', [
      alert('tuner-error', 'Tuner is not working',
        'A channel needs this tuner, but the tuner cannot provide radio data.'),
      alert('receiver-iq-drop', 'Radio data keeps being lost',
        'VCE keeps losing incoming radio data. Calls may be missed or interrupted.'),
      alert('receiver-ingress-drop', 'Radio data from the tuner keeps being lost',
        'VCE keeps losing data after it arrives from the USB tuner. Calls may be missed or interrupted.')
    ]),
    group('usb', 'USB connection', '', [
      alert('usb-sample-loss', 'Radio data from the USB tuner is incomplete',
        'USB radio data keeps arriving incomplete, or has stopped arriving. Calls may be missed or interrupted.'),
      alert('usb-delivery-rate-low', 'Too little radio data is arriving from the USB tuner',
        'VCE keeps receiving less radio data than the tuner should provide. Calls may be missed or interrupted.')
    ]),
    group('channels', 'Channel decoding', '', [
      alert('channelizer-drop', 'Radio data keeps being lost while separating channels',
        'VCE keeps losing data as it separates channels from the tuner signal. Calls may be missed or interrupted.'),
      alert('channel-output-drop', 'A channel keeps losing radio data',
        'Missing radio data may interrupt this channel or cause missed calls.'),
      alert('control-channel-lock-lost', 'Control channel is not receiving messages',
        'VCE cannot read the messages that tell it which calls to receive.')
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
