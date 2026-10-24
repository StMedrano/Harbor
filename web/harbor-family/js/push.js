/**
 * Notifications for a parent using Harbor in a browser. The phone app gets these through FCM; here the browser's own
 * push service delivers them, and sw.js shows them.
 *
 * Turning it on is always a deliberate act: the browser's permission prompt is only ever opened from a click, never
 * on load. Nothing is registered until permission is granted, and turning it off both unsubscribes here and tells
 * Harbor to forget the subscription.
 */
import { api } from './api.js';
import { BASE_URL, VAPID_PUBLIC_KEY } from './config.js';

const ID_KEY = 'harbor.install';

/**
 * This browser's own id, so Harbor can replace an earlier subscription from the same browser instead of piling up
 * dead ones. Kept in localStorage; when storage is unavailable a fresh id is used for this visit, which costs a
 * duplicate row the backend prunes once the old endpoint stops accepting pushes.
 */
export function installationId() {
  const made = () => (typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : String(Date.now()) + Math.random().toString(16).slice(2));
  try {
    const found = localStorage.getItem(ID_KEY);
    if (found) return found;
    const fresh = made();
    localStorage.setItem(ID_KEY, fresh);
    return fresh;
  } catch { return made(); }
}

export const pushSupported = () =>
  typeof navigator !== 'undefined' && 'serviceWorker' in navigator &&
  typeof window !== 'undefined' && 'PushManager' in window && 'Notification' in window;

let fetchedKey;

/**
 * The public VAPID key this browser subscribes with. A build may carry one (VITE_VAPID_PUBLIC_KEY), but normally the
 * backend serves its own through the gateway, so the key the browser subscribes with can never drift from the key
 * the pushes are signed with. Asked for once per load; an empty answer means notifications are not set up.
 */
export async function publicKey() {
  if (VAPID_PUBLIC_KEY) return VAPID_PUBLIC_KEY;
  if (fetchedKey !== undefined) return fetchedKey;
  try { fetchedKey = await api.vapidPublicKey(); } catch { fetchedKey = ''; }
  return fetchedKey;
}

/** Whether Harbor can offer notifications here at all. */
export const pushConfigured = async () => !!(await publicKey());

/** The public VAPID key as the subscription API wants it. Pure, so it is tested directly. */
export function applicationServerKey(base64) {
  const padded = String(base64 || '').trim();
  if (!padded) throw new Error('No application server key');
  const pad = '='.repeat((4 - padded.length % 4) % 4);
  const raw = atob((padded + pad).replace(/-/g, '+').replace(/_/g, '/'));
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

const worker = () => navigator.serviceWorker.getRegistration(BASE_URL);

/** What the Family tab shows for the Notifications row. Never prompts. */
export async function pushState() {
  if (!pushSupported()) return { supported: false, configured: false, permission: 'unsupported', on: false };
  let on = false;
  try {
    const registration = await worker();
    on = !!(registration && await registration.pushManager.getSubscription());
  } catch { on = false; }
  return { supported: true, configured: await pushConfigured(), permission: Notification.permission, on };
}

/** Asks permission, subscribes, and tells Harbor. Call only from a click. */
export async function enablePush() {
  if (!pushSupported()) throw new Error('Notifications are not supported in this browser.');
  // Fail before the browser's prompt rather than after it: asking and then failing is the worse order.
  const key = await publicKey();
  if (!key) throw new Error('Notifications are not set up for this site yet.');
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return { supported: true, configured: true, permission, on: false };
  const registration = await navigator.serviceWorker.register(BASE_URL + 'sw.js', { scope: BASE_URL });
  await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.getSubscription() ||
    await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: applicationServerKey(key),
    });
  const { endpoint, keys } = subscription.toJSON();
  await api.registerWebPush({ clientInstallationId: installationId(), endpoint, keys });
  return { supported: true, configured: true, permission, on: true };
}

/**
 * Stops notifications in this browser. Harbor is told first, so a failure there leaves a subscription that still
 * works rather than a silent one Harbor keeps pushing to.
 */
export async function disablePush() {
  const registration = await worker();
  const subscription = registration && await registration.pushManager.getSubscription();
  if (subscription) {
    await api.removeWebPush({ clientInstallationId: installationId(), endpoint: subscription.endpoint });
    await subscription.unsubscribe();
  }
  return { supported: true, configured: await pushConfigured(), permission: Notification.permission, on: false };
}
