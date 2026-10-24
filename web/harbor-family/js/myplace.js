/**
 * A parent sharing their own place, from a browser. The same rules as the phone app: off until the parent turns it
 * on, the latest place only, sent only while Harbor is on the screen, and nothing kept in the background. Turning it
 * off deletes the place Harbor holds.
 *
 * While a parent shares, the whole family sees them on the family map: the children and the other parents.
 */
import { api } from './api.js';

/** The backend's own limits, repeated here so a request is never built that it would refuse. */
export const LABEL_MAX = 40;
export const FIX_MAX_AGE_MS = 600_000;
/** How often a fresh place is sent while the page is open. The phone app sends about this often while in use. */
export const EVERY_MS = 120_000;

export const geoSupported = () => typeof navigator !== 'undefined' && !!navigator.geolocation;

/** The name shown on the children's map, trimmed to what the backend accepts. Empty means "not usable". */
export function cleanLabel(name) {
  const collapsed = String(name ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();
  return [...collapsed].slice(0, LABEL_MAX).join('').trim();
}

/**
 * Harbor accepts a coordinate of at most six decimal places (about 10cm), and refuses the whole report otherwise.
 * A browser gives full double precision, so rounding here is required, not tidying.
 */
const places6 = n => Number(n.toFixed(6));

/**
 * One browser position as the report the backend expects, or null when it cannot be used. A cached position can be
 * older than the backend's 10-minute limit, so an old one is dropped here rather than sent and refused.
 */
export function fixFrom(position, nowMs = Date.now()) {
  const c = position && position.coords;
  if (!c || !Number.isFinite(c.latitude) || !Number.isFinite(c.longitude)) return null;
  if (Math.abs(c.latitude) > 90 || Math.abs(c.longitude) > 180) return null;
  const at = Number.isFinite(position.timestamp) ? position.timestamp : nowMs;
  if (at < nowMs - FIX_MAX_AGE_MS || at > nowMs + 60_000) return null;
  const accuracy = Number.isFinite(c.accuracy) ? Math.min(100_000, Math.max(0, Math.round(c.accuracy))) : 0;
  return { at: new Date(at).toISOString(), latitude: places6(c.latitude), longitude: places6(c.longitude), accuracyM: accuracy };
}

let timer = null;
let listening = false;
let state = { on: false, familyId: null, label: '', lastAt: null, error: null };
let announce = () => {};

export const sharingState = () => ({ ...state });
export function onSharingChange(fn) { announce = typeof fn === 'function' ? fn : () => {}; }

/** Reflects what Harbor already holds for this parent, without turning anything on. */
export function showStored(mySharing) {
  if (state.on) return sharingState();
  state = mySharing
    ? { on: false, familyId: null, label: cleanLabel(mySharing.label), lastAt: mySharing.lastAt || null, error: null }
    : { on: false, familyId: null, label: '', lastAt: null, error: null };
  return sharingState();
}

function here() {
  return new Promise((resolve, reject) =>
    navigator.geolocation.getCurrentPosition(resolve, reject, { enableHighAccuracy: false, timeout: 15_000, maximumAge: 60_000 }));
}

async function send() {
  if (!state.on || (typeof document !== 'undefined' && document.visibilityState !== 'visible')) return;
  const fix = fixFrom(await here());
  if (!fix) { state.error = 'No usable place from this device yet.'; announce(sharingState()); return; }
  await api.reportMyLocation({ familyId: state.familyId, label: state.label, fix });
  state.lastAt = fix.at;
  state.error = null;
  announce(sharingState());
}

function trySend() {
  send().catch(failure => {
    state.error = (failure && failure.message) || 'Harbor could not take your place just now.';
    announce(sharingState());
  });
}

function listen() {
  if (listening || typeof document === 'undefined') return;
  listening = true;
  // Only while Harbor is on the screen: a hidden tab sends nothing, and coming back sends a fresh place at once.
  document.addEventListener('visibilitychange', () => { if (state.on && document.visibilityState === 'visible') trySend(); });
}

/**
 * Turns sharing on. Call from a click: the browser's location prompt opens on the first send, and the first place
 * goes out before this resolves, so the parent sees the result of what they just pressed.
 */
export async function startSharing(familyId, label) {
  const name = cleanLabel(label);
  if (!geoSupported()) throw new Error('Location is not supported in this browser.');
  if (!familyId) throw new Error('Choose a family first.');
  if (!name) throw new Error('Enter the name your children will see.');
  state = { on: true, familyId, label: name, lastAt: null, error: null };
  listen();
  announce(sharingState());
  try {
    await send();
  } catch (failure) {
    // A first send that fails means nothing is being shared, so the switch must go back rather than claim it is on.
    state = { on: false, familyId: null, label: name, lastAt: null, error: null };
    announce(sharingState());
    throw failure;
  }
  clearInterval(timer);
  timer = setInterval(trySend, EVERY_MS);
  return sharingState();
}

/** Turns sharing off and deletes the place Harbor holds. */
export async function stopSharing() {
  const familyId = state.familyId;
  clearInterval(timer);
  timer = null;
  const label = state.label;
  state = { on: false, familyId: null, label, lastAt: null, error: null };
  announce(sharingState());
  if (familyId) await api.stopMyLocation(familyId);
  return sharingState();
}

/**
 * Picks sharing back up after a reload, but only when this browser has already been given location permission, so
 * returning to the app never raises a prompt on its own.
 */
export async function resumeIfGranted(familyId, label) {
  if (state.on || !geoSupported() || !familyId || !cleanLabel(label)) return sharingState();
  let granted = false;
  try { granted = (await navigator.permissions.query({ name: 'geolocation' })).state === 'granted'; } catch { granted = false; }
  if (!granted) return sharingState();
  return startSharing(familyId, label);
}
