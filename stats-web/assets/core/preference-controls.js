// Shared personal-preference controls leave draft or immediate saving to their caller.
export const recordingClickModeChoices = Object.freeze([
  Object.freeze(['queue', 'Queue clicked calls']),
  Object.freeze(['once', 'Play once']),
  Object.freeze(['continue', 'Continue matching calls'])
]);

export const targetBurstLimits = Object.freeze({ minimum: 1, maximum: 20 });

export const listeningFieldDefinitions = Object.freeze({
  targetGrouping: Object.freeze({
    label: 'Group calls by target',
    dockDetail: 'Keeps calls for the same group together to preserve conversationality'
  }),
  targetBurstLimit: Object.freeze({
    label: 'Calls per target',
    detail: 'When grouping is on, switch to another waiting target after this many consecutive calls.',
    limits: targetBurstLimits
  }),
  prependPlayingCall: Object.freeze({
    label: 'Playing call in page title',
    dockDetail: 'Keep the Live call in browser tab titles until its display hangtime ends.'
  }),
  recordingClickMode: Object.freeze({ label: 'When a call is clicked' })
});

export function createRecordingClickModeSelect(node, { id, value, labelledBy } = {}) {
  const select = node('select', 'ui-select');
  if (id) select.id = id;
  select.setAttribute(labelledBy ? 'aria-labelledby' : 'aria-label',
    labelledBy || listeningFieldDefinitions.recordingClickMode.label);
  recordingClickModeChoices.forEach(([mode, label]) => {
    const option = node('option', '', label);
    option.value = mode;
    select.append(option);
  });
  if (value !== undefined) select.value = value;
  return select;
}

export function createPreferenceNumberInput(node, {
  value, minimum, maximum, step, required = false, id, label, labelledBy, describedBy
}) {
  const input = node('input', 'ui-input ui-input-number');
  input.type = 'number';
  input.min = String(minimum); input.max = String(maximum);
  if (step !== undefined) input.step = String(step);
  if (required) input.required = true;
  if (id) input.id = id;
  if (label) input.setAttribute('aria-label', label);
  if (labelledBy) input.setAttribute('aria-labelledby', labelledBy);
  if (describedBy) input.setAttribute('aria-describedby', describedBy);
  input.value = String(value);
  return input;
}

export function createStatusAlertControl(node, uiToggle, alert, { checked = true, compact = false } = {}) {
  const toggle = uiToggle(checked, alert.name);
  const input = toggle.querySelector('input');
  input.dataset.alertCode = alert.id;
  const copy = node('span', compact ? 'ui-toggle-copy' : 'admin-toggle-copy');
  copy.append(node(compact ? 'span' : 'strong', '', alert.name));
  if (compact) {
    toggle.classList.add('ui-toggle-compact');
    toggle.querySelector('.ui-toggle-state')?.classList.add('visually-hidden');
  } else {
    input.name = `health-alert-${alert.id}`;
    if (alert.description) copy.append(node('span', '', alert.description));
  }
  const control = node('div', compact ? 'ui-toggle-field' : 'admin-toggle-control ui-field-row');
  control.append(copy, toggle);
  return { control, input };
}

export function mergeStatusAlertDisabledCodes(disabledCodes, knownAlertIds, disabledKnownAlertIds) {
  const known = new Set(knownAlertIds);
  return [
    ...disabledCodes.filter(code => !known.has(code)),
    ...disabledKnownAlertIds.filter(code => known.has(code))
  ];
}

export function receiverHealthDisabledCodesForSave(preferences, controls) {
  return mergeStatusAlertDisabledCodes(preferences?.health_alerts?.disabled_codes || [], controls.keys(),
    [...controls].filter(([, input]) => !input.checked).map(([id]) => id));
}
