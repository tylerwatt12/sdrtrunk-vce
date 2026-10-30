import { DEFAULT_CALL_DISPLAY_HANGTIME_SECONDS, MAXIMUM_CALL_DISPLAY_HANGTIME_SECONDS } from './call-display-retention.js?v=1';

export function createCallDisplayHangtimeField(node, { id, value, onChange }) {
  const field = node('label', 'ui-field');
  field.htmlFor = id;
  const input = node('input', 'ui-input');
  input.id = id; input.name = 'call-display-hangtime-seconds'; input.type = 'number';
  input.min = '0'; input.max = String(MAXIMUM_CALL_DISPLAY_HANGTIME_SECONDS);
  input.step = '1'; input.required = true;
  input.value = String(value ?? DEFAULT_CALL_DISPLAY_HANGTIME_SECONDS);
  const help = node('small', 'ui-field-detail',
    'Keep the last call and its details visible after playback ends. Applies to Scanner and the player in Live and Recordings. Set to 0 to clear immediately.');
  help.id = `${id}-help`; input.setAttribute('aria-describedby', help.id);
  const label = node('span', 'ui-field-label', 'Call display hangtime (seconds)');
  label.id = `${id}-label`; input.setAttribute('aria-labelledby', label.id);
  field.append(label, input, help);
  input.addEventListener('input', () => onChange?.(Number(input.value), input));
  return { field, input };
}
