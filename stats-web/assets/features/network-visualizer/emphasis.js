'use strict';

// Keep automatic attention and label interaction in one policy. New activity must
// not bypass event focus; explicit interaction promotes a name, never its mesh.
const P25_LABEL_TIER = Object.freeze({ depth: 0, activity: 1, eventFocus: 2, keyboard: 3, pointer: 4 });

function resolveP25NodeAttention(node, focusKeys, movementParticipants) {
  const key = String(node.id);
  const participant = movementParticipants.has(key);
  const eventFocused = focusKeys.has(key);
  const activity = Boolean(node.active || node.signalAction || participant);
  return { participant, eventFocused, activity,
    // Keep the ordinary activity paint as the fade's starting point and restore it smoothly.
    clear: activity || eventFocused,
    dimmed: focusKeys.size > 0 && !eventFocused };
}

function resolveP25LabelTier(attention, { pointed = false, pressing = false, focused = false } = {}) {
  if (pointed || pressing) return P25_LABEL_TIER.pointer;
  if (focused) return P25_LABEL_TIER.keyboard;
  if (attention.eventFocused) return P25_LABEL_TIER.eventFocus;
  if (attention.activity && !attention.dimmed) return P25_LABEL_TIER.activity;
  return P25_LABEL_TIER.depth;
}

function isP25LinkDimmed(link, focusKeys) {
  const key = endpoint => String(typeof endpoint === 'object' ? endpoint.id : endpoint);
  return focusKeys.size > 0 && !(focusKeys.has(key(link.source)) && focusKeys.has(key(link.target)));
}

export { P25_LABEL_TIER, resolveP25NodeAttention, resolveP25LabelTier, isP25LinkDimmed };
