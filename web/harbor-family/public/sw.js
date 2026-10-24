/**
 * Harbor Family's service worker. It exists to show a parent a notification when Harbor pushes one, and does nothing
 * else: it caches no page and stores no data, because the app is online-only and a stale shell would be worse than
 * no offline support.
 *
 * A Harbor push carries a route and no content — { version, kind, familyId, childId, deviceId, resourceId } — so the
 * words below are chosen here, from the kind alone. They are the same words the Android parent app shows. A lock
 * screen therefore never reveals a child's name, an app, an alert category, or any part of a message.
 *
 * Served from the site root, so its scope is the whole app.
 */
const TEXT = {
  'device.alert.created': 'Harbor flagged a possible concern. Open to review.',
  'device.controls.off': 'App controls were turned off on a child’s phone. Open Harbor to check.',
  'device.access.requested': 'Your child is asking for more time. Open Harbor to answer.',
};
const TITLE = { 'device.access.requested': 'Request from your child' };
const FALLBACK = 'Open Harbor to check your family.';

self.addEventListener('install', event => event.waitUntil(self.skipWaiting()));
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

/** The route a push carries, or null when it is not one. Shape only; the kind is never trusted to be known. */
function routeOf(raw) {
  try {
    const r = JSON.parse(raw);
    if (!r || typeof r !== 'object' || r.version !== 1 || typeof r.kind !== 'string') return null;
    return r;
  } catch { return null; }
}

self.addEventListener('push', event => {
  const route = routeOf(event.data && event.data.text());
  const kind = route ? route.kind : null;
  // A push always shows something: the subscription is userVisibleOnly, and staying silent makes the browser post its
  // own "site updated in the background" notice instead. An unknown kind gets the generic line, which says nothing.
  event.waitUntil((async () => {
    await self.registration.showNotification((kind && TITLE[kind]) || 'Harbor', {
      body: (kind && TEXT[kind]) || FALLBACK,
      tag: kind || 'harbor',
      renotify: true,
      data: { kind, childId: (route && route.childId) || null },
    });
    for (const client of await self.clients.matchAll({ type: 'window' })) {
      client.postMessage({ type: 'harbor-push', kind, childId: (route && route.childId) || null });
    }
  })());
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const data = event.notification.data || {};
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const existing = windows.find(client => client.url.startsWith(self.registration.scope));
    if (existing) {
      await existing.focus();
      existing.postMessage({ type: 'harbor-open', kind: data.kind || null, childId: data.childId || null });
      return;
    }
    await self.clients.openWindow(self.registration.scope);
  })());
});
