/**
 * Parent controls carried in a device's desired state: desiredState.controls =
 *   { paused?: boolean, bedtime?: { enabled, start: 'HH:MM', end: 'HH:MM' }, requireLocation?: boolean }
 * (validated server-side by update-device-state). The child app turns them into a full-screen block.
 */
export const DEFAULT_BEDTIME = { enabled: false, start: '21:00', end: '07:00' };

const minutes = hhmm => { const [h, m] = String(hhmm).split(':').map(Number); return h * 60 + m; };
const clock12 = hhmm => { const [h, m] = String(hhmm).split(':').map(Number); return `${((h + 11) % 12) + 1}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`; };

/** True when `now` (local time) falls inside the bedtime window; windows may cross midnight. */
export function inBedtime(bedtime, now = new Date()) {
  if (!bedtime || !bedtime.enabled) return false;
  const s = minutes(bedtime.start), e = minutes(bedtime.end), n = now.getHours() * 60 + now.getMinutes();
  if (!Number.isFinite(s) || !Number.isFinite(e) || s === e) return false;
  return s < e ? n >= s && n < e : n >= s || n < e;
}

/** What the child should see right now: null (nothing) or { kind, title, text }. Pause wins over bedtime. */
export function blockReason(desiredState, now = new Date()) {
  const c = desiredState && typeof desiredState === 'object' && desiredState.controls;
  if (!c || typeof c !== 'object') return null;
  if (c.paused === true) return { kind: 'paused', title: 'Paused by your parents', text: 'Harbor Family is paused on this phone. Ask a parent to resume it.' };
  if (inBedtime(c.bedtime, now)) return { kind: 'bedtime', title: 'Bedtime', text: `Time to rest. This phone is back at ${clock12(c.bedtime.end)}.` };
  return null;
}
