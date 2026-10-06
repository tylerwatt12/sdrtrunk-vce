import { openSetupGuide } from '../core/setup-guide.js?v=1';

const dismissedInMemory = new Set();
export const channelSetupGuideStorageKey = (account) =>
  `sdrtrunk-vce-channel-setup-guide-dismissed:${encodeURIComponent(account || 'anonymous')}`;

export function hasUsableSetupTuner(catalog) {
  return Array.isArray(catalog?.tuners) && catalog.tuners.some((tuner) =>
    tuner?.eligible === true && tuner.source_type === 'receiver');
}

export function createChannelSetupGuide({ ui, account, channels, isCurrent, canPresent, loadTuners,
  newChannelButton, findSystemsButton, storage }) {
  const key = channelSetupGuideStorageKey(account);
  let guide = null;
  let checking = false;
  const dismissed = () => {
    if (dismissedInMemory.has(key)) return true;
    try { return (storage ?? globalThis.localStorage)?.getItem(key) === '1'; } catch (_) { return false; }
  };
  const remember = () => {
    dismissedInMemory.add(key);
    try { (storage ?? globalThis.localStorage)?.setItem(key, '1'); }
    catch (_) { /* Keep this browser session usable without storage. */ }
  };
  const empty = () => { const rows = channels(); return Array.isArray(rows) && rows.length === 0; };
  const close = () => { guide?.close(); guide = null; };
  const refresh = async () => {
    if (guide && !guide.isOpen()) guide = null;
    if (!isCurrent() || !empty()) { close(); return; }
    if (checking || dismissed() || !findSystemsButton || (!guide && !canPresent())) return;
    checking = true;
    try {
      const tuners = await loadTuners();
      if (!isCurrent() || !empty() || !hasUsableSetupTuner(tuners)) { close(); return; }
      if (guide || dismissed() || !canPresent()) return;
      guide = openSetupGuide(ui, { title: 'Add your first channel', onDismiss: remember, choices: [
        { button: findSystemsButton, description: 'Search for trunked radio systems your tuner can receive.' },
        { button: newChannelButton, description: 'Set up a channel manually using a known frequency.' }
      ] });
    } catch (_) {
      // The ordinary channel actions remain available when tuner readiness is unknown.
      close();
    } finally { checking = false; }
  };
  return { refresh, close };
}
