/**
 * HARBOR FAMILY DATA LAYER — the ONLY file that talks to a backend.
 *
 * The UI (app.js) calls `api.*` and nothing else. To connect a real backend,
 * implement an object with the same methods and change the last line to
 * `export const api = remoteApi;`. Every method is async. On failure throw
 * `new ApiError(code, message)`; use code 'unauthenticated' for 401/expired
 * sessions and the UI will return to the sign-in screen.
 *
 * Two implementations share this contract: `supabaseApi` (supabase-api.js, used when the
 * VITE_SUPABASE_* env vars are set) and `localApi` below, a browser-only stand-in
 * (localStorage) so the app runs with no server. localApi is NOT secure: never ship it.
 *
 * ── Shapes (all timestamps are ISO-8601 strings) ────────────────────────────
 * User     { id, name, email }
 * Session  { role:'parent', user: User } | { role:'child', user: User, child: {id,name,color} | null }   (null when signed out)
 *            ONE app, one sign-in. The role is chosen at sign-up. A parent goes to the parent dashboard.
 *            A child with child === null has not paired yet: the UI shows the pairing-code screen;
 *            once paired (child !== null) it shows the child dashboard.
 * Child    { id, name, age, color, pairingCode,
 *            device: { model, battery(0-100) } | null,
 *            location: { lat, lng, place, since, updatedAt, accuracyM? } | null,
 *            timeline: [{ time, text, driving? }],
 *            limitMin, bonusMin, usedMin, paused, bedtime, schoolTime,
 *            safeSearch, blockUnknownCallers,
 *            apps: [{ id, name, color, category, usedMin, limitMin|null, allowed }],
 *            requests: [{ id, type:'more_time'|'install'|'other', title, detail, minutes? }],
 *            filters: { [category]: boolean },
 *            history: [{ url, time, blockedCategory|null }],
 *            blockedSites: string[], blockedContacts: string[],
 *            week: number[7]   // minutes per day, oldest → today
 *            pickups, notifications, firstUse|null, afterBedtime|null,
 *            calls:   [{ id, name, number, direction:'in'|'out'|'missed', time, durationSec, isContact }],
 *            threads: [{ id, with, app, count, time, isContact, flagged,
 *                        messages: [{ from, text, mine }] }] }
 * Alert    { id, childId, severity:'h'|'m'|'l', category, app, time, from,
 *            lines: [{ who, text, flagged }], tips: string[], reviewed }
 * Settings { highPriority, arrivals, lowBattery, weekly }   (booleans)
 *
 * ── Methods ─────────────────────────────────────────────────────────────────
 * getSession()                         → Session | null
 * signUp({name,email,password})      → Session (PARENT accounts only; children never create accounts)  ('email_taken', 'confirm_email')
 * signIn({email,password,remember})    → Session     ('invalid_credentials')
 * signOut()                            → void
 * requestPasswordReset(email)          → void  (never reveal if the email exists)
 * listChildren() / listAlerts() / getSettings() / updateSettings(patch)
 * addChild({name,age})                 → Child (with pairingCode)
 * updateChild(id, patch)               → Child  (patch = partial Child fields)
 * removeChild(id)
 * regeneratePairingCode(childId)       → Child
 * setAlertReviewed(alertId, reviewed)
 * respondToRequest(childId, requestId, approve)
 * grantTime(childId, minutes)          → Child  (extra minutes for today only)
 * sendCommand(childId, 'checkin'|'ring')   ('no_device' if not paired)
 * inviteParent(email)
 *
 * CHILD-DEVICE methods (a child session may call ONLY these; parent methods must reject it with 'forbidden'):
 * pairDevice({code,deviceName})        → Session(role:'child', child set)   ('invalid_code', 'too_many_attempts')
 *                                          no account needed; creates the child-device identity. Code is single-use, expires in 10 min
 * getMyStatus()                        → { child:{id,name,color}, paired:true, offline, lastSync|null, desiredStateVersion|null, desiredState|null }
 *                                          (truthful: only what the backend provides; name is '' until a child-authorized contract supplies it)
 * enableLocation() / disableLocation() → { available, enabled, permission:'none'|'foreground'|'background' }
 *                                          (Android app only; shows the consent + permission flow; getMyStatus() also returns `location`)
 * requestMoreTime / requestAccess / requestInstall / checkIn / ackCommand / sendSOS → throw ApiError('unavailable') until the backend supports them
 * unpairDevice({email,password})       → verifies a PARENT; removes monitoring from this phone
 */
import { ApiError } from './errors.js';
import { SUPABASE_ENABLED } from './config.js';
import { supabaseApi } from './supabase-api.js';
export { ApiError };

/* ───────────────────────── local stand-in (replace me) ───────────────────── */
const NS = 'harbor.local.';
const load = (k, d) => { try { const v = localStorage.getItem(NS + k); return v === null ? d : JSON.parse(v); } catch { return d; } };
const save = (k, v) => localStorage.setItem(NS + k, JSON.stringify(v));
const delay = (ms = 250) => new Promise(r => setTimeout(r, ms));
const hex = b => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('');
const sha = async s => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
const rand = n => hex(crypto.getRandomValues(new Uint8Array(n)));
const pairCode = () => { const s = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1e6).padStart(6, '0'); return s.slice(0, 3) + ' ' + s.slice(3); };
const COLORS = ['#c0563a', '#3a6fb0', '#2f8f6b', '#8a5aa8', '#b8860b', '#c2527a'];
const DAY = 864e5;
const norm = e => String(e || '').trim().toLowerCase();

const sess = () => { const s = load('session', null); if (!s || s.expires < Date.now()) { localStorage.removeItem(NS + 'session'); return null; } return s; };
const need = (role = 'parent') => { const s = sess(); if (!s) throw new ApiError('unauthenticated', 'Please sign in again.'); if ((s.role || 'parent') !== role) throw new ApiError('forbidden', 'You don\u2019t have access to that.'); return s; };
const iso = () => new Date().toISOString();
// A child account is linked to one child record (owned by a parent's family data) once it has paired.
const findChild = uid => { for (const u of load('users', [])) { const d = load('data.' + u.id, null), c = d && d.children.find(x => x.childUserId === uid); if (c) return { owner: u, d, c }; } return null; };
const cmutate = fn => { const s = need('child'), f = findChild(s.userId); if (!f) throw new ApiError('not_found', 'This phone isn\u2019t paired yet.'); const r = fn(f.c, f.d); save('data.' + f.owner.id, f.d); return r; };
const childRef = c => ({ id: c.id, name: c.name, color: c.color });
const fam = s => load('data.' + s.userId, { children: [], alerts: [], settings: { highPriority: true, arrivals: true, lowBattery: true, weekly: false } });
const mutate = fn => { const s = need(), d = fam(s), r = fn(d); save('data.' + s.userId, d); return r; };
const child = (d, id) => { const c = d.children.find(x => x.id === id); if (!c) throw new ApiError('not_found', 'Child not found.'); return c; };
const pub = u => ({ id: u.id, name: u.name, email: u.email });
const sessionOf = u => { const f = u.role === 'child' ? findChild(u.id) : null; return { role: u.role, user: pub(u), ...(u.role === 'child' ? { child: f ? childRef(f.c) : null } : {}) }; };
const start = (u, remember) => { save('session', { role: u.role, userId: u.id, expires: Date.now() + (remember ? 30 : 1) * DAY }); return sessionOf(u); };

const newChild = (name, age, i) => ({
  id: crypto.randomUUID(), name, age, color: COLORS[i % COLORS.length], pairingCode: pairCode(),
  device: null, location: null, timeline: [],
  limitMin: 120, bonusMin: 0, usedMin: 0, paused: false, bedtime: true, schoolTime: true, safeSearch: true, blockUnknownCallers: false,
  apps: [], requests: [], filters: { Adult: true, Gambling: true, Violence: true, Drugs: true, 'Social media': false, Gaming: false },
  history: [], blockedSites: [], blockedContacts: [], week: [0, 0, 0, 0, 0, 0, 0], pickups: 0, notifications: 0, firstUse: null, afterBedtime: null, calls: [], threads: [],
});

export const localApi = {
  async getSession() {
    const s = sess(); if (!s) return null;
    if (s.role === 'child') { const f = findChild(s.userId); return f ? { role: 'child', user: { id: s.userId, name: '', email: '' }, child: childRef(f.c) } : null; }
    const u = load('users', []).find(x => x.id === s.userId);
    return u ? sessionOf(u) : null;
  },
  async signUp({ name, email, password }) {
    await delay(); email = norm(email);
    const users = load('users', []);
    if (users.some(u => u.email === email)) throw new ApiError('email_taken', 'An account with that email already exists.');
    const salt = rand(16), u = { id: crypto.randomUUID(), role: 'parent', name: String(name).trim(), email, salt, hash: await sha(salt + password) };
    users.push(u); save('users', users);
    return start(u, true);
  },
  async signIn({ email, password, remember }) {
    await delay(); email = norm(email);
    const u = load('users', []).find(x => x.email === email);
    if (!u || u.hash !== await sha(u.salt + password)) throw new ApiError('invalid_credentials', 'Incorrect email or password.');
    return start(u, remember);
  },
  async signOut() { localStorage.removeItem(NS + 'session'); },
  async requestPasswordReset() { await delay(); },

  async listChildren() { return fam(need()).children; },
  async listAlerts() { return fam(need()).alerts; },
  async getSettings() { return fam(need()).settings; },
  async updateSettings(p) { return mutate(d => Object.assign(d.settings, p)); },
  async addChild({ name, age }) { await delay(150); return mutate(d => { const c = newChild(String(name).trim(), age, d.children.length); d.children.push(c); return c; }); },
  async updateChild(id, p) { return mutate(d => Object.assign(child(d, id), p)); },
  async removeChild(id) { mutate(d => { d.children = d.children.filter(c => c.id !== id); d.alerts = d.alerts.filter(a => a.childId !== id); }); },
  async regeneratePairingCode(id) { return mutate(d => Object.assign(child(d, id), { pairingCode: pairCode() })); },
  async setAlertReviewed(id, reviewed) { mutate(d => { const a = d.alerts.find(x => x.id === id); if (a) a.reviewed = reviewed; }); },
  async respondToRequest(cid, rid, approve) {
    mutate(d => {
      const c = child(d, cid), r = c.requests.find(x => x.id === rid);
      c.requests = c.requests.filter(x => x.id !== rid);
      if (approve && r && r.type === 'more_time') c.bonusMin = (c.bonusMin || 0) + (r.minutes || 15);
    });
  },
  async grantTime(cid, minutes) { return mutate(d => { const c = child(d, cid); c.bonusMin = (c.bonusMin || 0) + minutes; return c; }); },
  async sendCommand(cid, type) { await delay(200); mutate(d => { const c = child(d, cid); if (!c.device) throw new ApiError('no_device', 'This child\u2019s device isn\u2019t paired yet.'); c.pendingCommand = type; }); },
  async inviteParent() { await delay(300); },

  /* ── child device (role: 'child') ── */
  async pairDevice({ code, deviceName }) {
    await delay(300); const digits = String(code || '').replace(/\D/g, '');
    const tries = load('tries.device', { n: 0, at: 0 });
    if (tries.n >= 5 && Date.now() - tries.at < 15 * 60e3) throw new ApiError('too_many_attempts', 'Too many tries. Ask a parent for a new code.');
    for (const u of load('users', [])) {
      const d = load('data.' + u.id, null), c = d && d.children.find(x => !x.childUserId && x.pairingCode && x.pairingCode.replace(/\D/g, '') === digits);
      if (!c) continue;
      const deviceId = crypto.randomUUID();
      c.childUserId = deviceId; c.device = { model: deviceName || 'This phone', battery: 100 }; c.pairingCode = null; c.timeline.push({ time: iso(), text: `${c.name} paired this phone` });
      save('data.' + u.id, d); localStorage.removeItem(NS + 'tries.device');
      save('session', { role: 'child', userId: deviceId, expires: Date.now() + 30 * DAY });
      return { role: 'child', user: { id: deviceId, name: '', email: '' }, child: childRef(c) };
    }
    save('tries.device', { n: Date.now() - tries.at < 15 * 60e3 ? tries.n + 1 : 1, at: Date.now() });
    throw new ApiError('invalid_code', 'That code isn\u2019t valid or has expired. Ask a parent for a new one.');
  },
  /** Same truthful shape as the Supabase adapter: pairing + sync status only. */
  async getMyStatus() {
    return cmutate(c => ({ child: { id: c.id, name: '', color: c.color }, paired: true, offline: false, lastSync: iso(), desiredStateVersion: 0, desiredState: { controls: { paused: !!c.paused, bedtime: { enabled: !!c.bedtime, start: '21:00', end: '07:00' } } } }));
  },
  async enableLocation() { throw new ApiError('unavailable', 'Sharing location needs the Harbor Family Android app.'); },
  async disableLocation() { throw new ApiError('unavailable', 'Sharing location needs the Harbor Family Android app.'); },
  async requestMoreTime() { throw new ApiError('unavailable', 'That isn\u2019t available yet.'); },
  async requestAccess() { throw new ApiError('unavailable', 'That isn\u2019t available yet.'); },
  async requestInstall() { throw new ApiError('unavailable', 'That isn\u2019t available yet.'); },
  async checkIn() { throw new ApiError('unavailable', 'That isn\u2019t available yet.'); },
  async ackCommand() { throw new ApiError('unavailable', 'That isn\u2019t available yet.'); },
  async sendSOS() { throw new ApiError('unavailable', 'That isn\u2019t available yet.'); },
  /** Parent credentials required: a child must not be able to remove monitoring. */
  async unpairDevice({ email, password }) {
    await delay(300); const s = need('child'), f = findChild(s.userId), p = load('users', []).find(x => x.email === norm(email) && x.role === 'parent');
    if (!p || p.hash !== await sha(p.salt + password) || !f || f.owner.id !== p.id) throw new ApiError('invalid_credentials', 'Those parent details don\u2019t match.');
    Object.assign(f.c, { childUserId: null, device: null, pairingCode: pairCode() }); save('data.' + f.owner.id, f.d); localStorage.removeItem(NS + 'session');
  },
};

// Supabase when VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY are set (see .env.example); otherwise the local stand-in.
export const api = SUPABASE_ENABLED ? supabaseApi : localApi;
