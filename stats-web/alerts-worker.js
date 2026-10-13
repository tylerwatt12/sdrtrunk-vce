importScripts('/assets/features/alerts-worker-storage.js?v=1');
const deviceStore = self.VCEAlertWorkerStorage.createStore(self.indexedDB);
self.addEventListener('install', event => event.waitUntil(self.skipWaiting()));
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

self.addEventListener('message', event => {
  if (event.data?.type !== 'vce-alert-device') return;
  try { if (new URL(event.source?.url).origin !== self.location.origin) return; } catch { return; }
  event.waitUntil((event.data.device ? deviceStore.save(event.data.device) : deviceStore.clear())
    .then(() => event.ports?.[0]?.postMessage({ ok: true }), () => event.ports?.[0]?.postMessage({ ok: false })));
});

self.addEventListener('pushsubscriptionchange', event => {
  event.waitUntil((async () => {
    try {
      const device = await deviceStore.read();
      if (!device) return;
      let subscription = event.newSubscription;
      if (!subscription) {
        const applicationServerKey = event.oldSubscription?.options?.applicationServerKey;
        if (!applicationServerKey) return;
        subscription = await self.registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey });
      }
      const response = await fetch('/api/v1/alerts/subscriptions/me/refresh', { method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${device.manage_token}` },
        body: JSON.stringify({ subscription: subscription.toJSON() }), credentials: 'same-origin', cache: 'no-store' });
      if ([401, 403, 404, 410].includes(response.status)) await deviceStore.clear();
    } catch { /* The next foreground Topics visit retries reconciliation without prompting. */ }
  })());
});

self.addEventListener('push', event => {
  let payload = {};
  try { payload = event.data?.json() || {}; } catch { payload = {}; }
  const title = typeof payload.title === 'string' && payload.title.trim() ? payload.title : 'VCE alert';
  const body = typeof payload.body === 'string' ? payload.body : '';
  let target = '/?view=topics';
  try {
    const requested = new URL(payload.url || target, self.location.origin);
    if (requested.origin === self.location.origin) target = requested.pathname + requested.search;
  } catch { /* Open the public topic page when a target is invalid. */ }
  event.waitUntil(self.registration.showNotification(title, { body,
    icon: '/assets/vce-icon-192.png', badge: '/assets/vce-icon-192.png',
    tag: typeof payload.tag === 'string' ? payload.tag : undefined,
    data: { url: target } }));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  let target = new URL('/?view=topics', self.location.origin).href;
  try {
    const requested = new URL(event.notification.data?.url || target, self.location.origin);
    if (requested.origin === self.location.origin) target = requested.href;
  } catch { /* Keep notification clicks within this VCE installation. */ }
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(async clients => {
    const client = clients.find(value => { try { return new URL(value.url).origin === self.location.origin; } catch { return false; } });
    if (client) { await client.navigate(target); return client.focus(); }
    return self.clients.openWindow(target);
  }));
});
