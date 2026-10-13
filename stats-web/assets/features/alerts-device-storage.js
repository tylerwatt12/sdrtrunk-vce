const KEY = 'vce-alert-device-v1';

// Device credentials stay in this browser and are never presented in the UI.
export function readAlertDevice(storage) {
  try {
    const value = JSON.parse(storage.getItem(KEY) || 'null');
    if (!value || typeof value.id !== 'string' || !value.id ||
      typeof value.manage_token !== 'string' || !value.manage_token) return null;
    return { id: value.id, manage_token: value.manage_token,
      mode: value.mode === 'direct' ? 'direct' : 'proxy',
      status: value.status === 'pending' ? 'pending' : 'connected',
      expires_at_ms: Number(value.expires_at_ms) || 0 };
  } catch { return null; }
}
export function saveAlertDevice(storage, value) {
  if (!value || typeof value.id !== 'string' || !value.id ||
    typeof value.manage_token !== 'string' || !value.manage_token) {
    throw new Error('The device registration was incomplete. Try connecting again.');
  }
  const saved = { id: value.id, manage_token: value.manage_token, mode: value.mode,
    status: value.status, expires_at_ms: Number(value.expires_at_ms) || 0 };
  try { storage.setItem(KEY, JSON.stringify(saved)); }
  catch { throw new Error('This browser could not save the device registration. Allow site storage and try again.'); }
  return saved;
}
export function forgetAlertDevice(storage) {
  try { storage.removeItem(KEY); } catch { /* A revoked registration remains unusable. */ }
}
