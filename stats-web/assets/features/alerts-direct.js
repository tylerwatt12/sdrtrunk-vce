export async function syncDirectWorkerDevice(navigatorValue, device, Channel = MessageChannel) {
  if (!navigatorValue?.serviceWorker) return;
  const registration = await navigatorValue.serviceWorker.getRegistration('/');
  const worker = registration?.active || registration?.waiting;
  if (!worker) return;
  const value = device?.mode === 'direct' && device.status === 'connected' ?
    { device_id: device.id, manage_token: device.manage_token } : null;
  await new Promise((resolve, reject) => {
    const channel = new Channel();
    let finished = false;
    const timeout = setTimeout(() => finish(false), 2000);
    function finish(ok) {
      if (finished) return;
      finished = true;
      clearTimeout(timeout); channel.port1.close(); channel.port2.close();
      if (ok) resolve();
      else reject(new Error('Notification renewal settings could not be saved. Reopen Topics to try again.'));
    }
    channel.port1.onmessage = event => finish(event.data?.ok === true);
    try { worker.postMessage({ type: 'vce-alert-device', device: value }, [channel.port2]); }
    catch { finish(false); }
  });
}

// Reconciliation observes existing permission/subscriptions. Only an explicit Connect action may prompt or subscribe.
export async function reconcileDirectSubscription(windowValue, device, refresh) {
  if (!device || device.mode !== 'direct' || device.status !== 'connected') return { state: 'none' };
  if (windowValue.Notification?.permission !== 'granted' || !windowValue.navigator?.serviceWorker) {
    return { state: 'reconnect', message: 'Notifications need to be reconnected on this device. Open Notification settings to continue.' };
  }
  try {
    const registration = await windowValue.navigator.serviceWorker.getRegistration('/');
    const subscription = await registration?.pushManager.getSubscription();
    if (!subscription || subscription.expirationTime != null && subscription.expirationTime <= Date.now()) {
      return { state: 'reconnect', message: 'The notification connection expired. Open Notification settings to reconnect this device.' };
    }
    const result = await refresh({ subscription: subscription.toJSON() });
    await syncDirectWorkerDevice(windowValue.navigator, device, windowValue.MessageChannel);
    if (result?.status === 'reconnect_required') return { state: 'reconnect',
      message: 'The notification connection expired. Open Notification settings to reconnect this device.' };
    return { state: 'connected' };
  } catch (error) {
    if (error?.name === 'AbortError') throw error;
    if ([401, 403, 404, 410, 422].includes(error?.status)) return {
      state: 'reconnect', message: 'The notification connection is unavailable. Open Notification settings to reconnect this device.' };
    return { state: 'retry', message: 'Notifications could not be checked. Open Topics again to retry.' };
  }
}
