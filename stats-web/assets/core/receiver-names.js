/** Receiver labels never replace the stable ID or configured preferred-tuner value. */
export function receiverDisplayName(receiver, fallback = 'Receiver') {
  return String(receiver?.nickname || '').trim() || String(receiver?.name || '').trim() || fallback;
}
