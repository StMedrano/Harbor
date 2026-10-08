/**
 * Supabase implementation of the Harbor Family data contract (see api.js), written against the
 * repo's approved backend (supabase/migrations + supabase/functions on feat/parent-android-foundation).
 *
 *  PARENT  Supabase Auth email/password. Family comes from family_members (RLS). Privileged actions go
 *          through Edge Functions: create-family, create-child, create-device-pairing, revoke-device.
 *  CHILD   No email/password. Anonymous Supabase identity + P-256 device key. A parent-issued 6-digit code
 *          (10 min, single use) is claimed via the device-claim function; afterwards the device signs
 *          device-sync requests. A role is never trusted from metadata: anonymous identity = child device,
 *          verified account with an active family membership = parent.
 *
 * Not in the backend yet (screen time, apps, alerts, location, requests): those methods return empty
 * data or throw ApiError('unavailable') so the UI can say so honestly instead of showing invented data.
 */
import { createClient } from '@supabase/supabase-js';
import { ApiError } from './errors.js';
import { SUPABASE_URL, SUPABASE_ANON_KEY } from './config.js';
import { newDeviceKey, proofHeaders, kvGet, kvSet, kvDel } from './device.js';
import { hasNativeDevice, nativeCall } from './native-device.js';
import { DEFAULT_BEDTIME } from './controls.js';

let _sb = null;
const sb = () => (_sb ||= createClient(SUPABASE_URL, SUPABASE_ANON_KEY, { auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false } }));

const MSG = {
  invalid_credentials: 'Incorrect email or password.', email_taken: 'An account with that email already exists.',
  invalid_code: 'That code isn’t valid or has expired. Ask a parent for a new one.', too_many_attempts: 'Too many tries. Ask a parent for a new code.',
  forbidden: 'You don’t have access to that.', not_found: 'Not found.', unauthenticated: 'Please sign in again.',
  mfa_required: 'This needs a fresh two-step verification, which isn’t in the web app yet. Use the Harbor Family Android app.',
  unavailable: 'That isn’t available yet.', device_revoked: 'This phone was removed by a parent.',
};
const fail = (code, m) => new ApiError(code, m || MSG[code] || 'Something went wrong. Please try again.');
const unavailable = () => fail('unavailable');

function wrap(err) {
  if (!err) return null;
  if (err instanceof ApiError) return err;
  const m = String(err.message || ''), st = err.status;
  if (/invalid login credentials/i.test(m)) return fail('invalid_credentials');
  if (/already registered|already been registered|user_already_exists/i.test(m + (err.code || ''))) return fail('email_taken');
  if (/password/i.test(m) && /(weak|short|least)/i.test(m)) return new ApiError('weak_password', 'Password is too weak. Use 8+ characters with a letter and a number.');
  if (/rate limit|too many/i.test(m)) return new ApiError('rate_limited', 'Too many attempts. Please wait a minute and try again.');
  if (/jwt|refresh_token|session.*missing/i.test(m) || st === 401) return fail('unauthenticated');
  if (err.code === '42501' || st === 403) return fail('forbidden');
  return new ApiError('unknown', 'Something went wrong. Please try again.');
}
const ok = ({ data, error }) => { if (error) throw wrap(error); return data; };
const clean = s => String(s ?? '').trim();

/** Calls an Edge Function. Backend errors are { code, message } with HarborErrorCode codes. */
async function fn(name, body, { token, headers = {} } = {}) {
  let t = token;
  if (!t) { const { data: { session } } = await sb().auth.getSession(); t = session && session.access_token; }
  if (!t) throw fail('unauthenticated');
  let r;
  try { r = await fetch(`${SUPABASE_URL}/functions/v1/${name}`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${t}`, apikey: SUPABASE_ANON_KEY, ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body) }); }
  catch { throw new ApiError('network', 'Can’t reach Harbor. Check your connection.'); }
  const j = await r.json().catch(() => ({}));
  if (r.ok) return j;
  const map = { AUTH_REQUIRED: 'unauthenticated', FORBIDDEN: 'forbidden', MFA_REQUIRED: 'mfa_required', DEVICE_REVOKED: 'device_revoked', NOT_FOUND: 'not_found' };
  const code = map[j.code] || (r.status === 401 ? 'unauthenticated' : null);
  if (code) throw fail(code);
  throw new ApiError(String(j.code || 'unknown').toLowerCase(), j.message || 'Something went wrong. Please try again.');
}

/* ── parent helpers ── */
const COLORS = ['#c0563a', '#3a6fb0', '#2f8f6b', '#8a5aa8', '#b8860b', '#c2527a'];
const colorOf = id => COLORS[[...id].reduce((a, c) => a + c.charCodeAt(0), 0) % COLORS.length];
const fmtCode = c => c.slice(0, 3) + ' ' + c.slice(3);
const blank = (c, dev) => ({
  id: c.id, name: c.display_name, age: null, color: colorOf(c.id), pairingCode: null,
  device: dev ? { model: dev.model || dev.display_name, battery: null, status: dev.status, lastSeenAt: dev.last_seen_at, id: dev.id } : null,
  location: null, timeline: [], limitMin: null, bonusMin: 0, usedMin: 0, paused: false, bedtime: false, schoolTime: false, safeSearch: false, blockUnknownCallers: false,
  apps: [], requests: [], filters: {}, history: [], blockedSites: [], blockedContacts: [], week: [0, 0, 0, 0, 0, 0, 0], pickups: 0, notifications: 0, firstUse: null, afterBedtime: null, calls: [], threads: [],
});

async function familyId() {
  const { data: { user } } = await sb().auth.getUser(); if (!user) throw fail('unauthenticated');
  const m = ok(await sb().from('family_members').select('family_id,role,status').eq('user_id', user.id).eq('status', 'active').limit(1));
  return m[0] ? m[0].family_id : null;
}
/** Parents get a family on first sign-in (idempotent on the backend). */
async function ensureFamily(name) {
  let id = await familyId(); if (id) return id;
  const key = 'family-' + (await sb().auth.getUser()).data.user.id;
  const r = await fn('create-family', { name: `${clean(name) || 'My'} family`, idempotencyKey: key });
  return r.familyId;
}

/* ── native (Android shell) child identity ── */
const nativeError = r => {
  const code = r && r.code ? String(r.code) : 'unknown';
  return new ApiError(code, (r && r.message) || MSG[code] || 'Something went wrong. Please try again.');
};
async function nativeStatus() { const r = await nativeCall('status'); return r.status === 'ok' ? r : null; }

/* ── child device state (IndexedDB) ── */
const binding = () => kvGet('binding');
async function childSession() {
  const b = await binding(); if (!b) return null;
  const { data: { session } } = await sb().auth.getSession();
  if (!session || session.user.id !== b.authUserId) return null;
  return { role: 'child', user: { id: session.user.id, name: '', email: '' }, child: { id: b.childId, name: '', color: colorOf(b.childId) } };
}
async function currentSession() {
  if (hasNativeDevice()) {
    const n = await nativeStatus();
    if (n && n.paired) return { role: 'child', user: { id: n.authUserId, name: '', email: '' }, child: { id: n.childId, name: '', color: colorOf(n.childId) } };
  }
  const { data: { session }, error } = await sb().auth.getSession();
  if (error) throw wrap(error);
  if (!session) return null;
  if (session.user.is_anonymous) return childSession();
  const name = (session.user.user_metadata && session.user.user_metadata.name) || '';
  await ensureFamily(name);
  return { role: 'parent', user: { id: session.user.id, name, email: session.user.email } };
}

let lastDesiredVersion = null; // newest controls version this phone has received (acknowledged on the next sync)
async function signedSync() {
  const b = await binding(); const key = await kvGet('key');
  if (!b || !key) throw fail('not_found', 'This phone isn’t paired yet.');
  const { data: { session } } = await sb().auth.getSession(); if (!session) throw fail('unauthenticated');
  const body = lastDesiredVersion === null ? '{}' : JSON.stringify({ acknowledgedDesiredStateVersion: lastDesiredVersion });
  const headers = await proofHeaders(key.privateKey, { operation: 'device-sync', deviceId: b.deviceId, body });
  return fn('device-sync', body, { token: session.access_token, headers });
}

/** Adds each child's pause/bedtime controls (from get-device-controls). Failure leaves the defaults. */
async function withControls(kids) {
  if (!kids.some(k => k.device)) return kids;
  try {
    const fid = await familyId(); if (!fid) return kids;
    const { devices } = await fn('get-device-controls', { familyId: fid });
    for (const k of kids) {
      const d = (devices || []).find(x => x.childId === k.id); if (!d) continue;
      const c = (d.desiredState && d.desiredState.controls) || {};
      k.paused = c.paused === true; k.bedtime = !!(c.bedtime && c.bedtime.enabled);
      k.controls = { ...c, bedtime: { ...DEFAULT_BEDTIME, ...(c.bedtime || {}) } };
      if (k.device) k.device.controlsApplied = d.acknowledgedVersion >= d.desiredStateVersion;
    }
  } catch (x) { if (x instanceof ApiError && x.code === 'unauthenticated') throw x; }
  return kids;
}

export const supabaseApi = {
  async getSession() { return currentSession(); },
  /** Parent accounts only. Children never create accounts: they pair with a code (pairDevice). */
  async signUp({ name, email, password }) {
    const { data, error } = await sb().auth.signUp({ email: clean(email).toLowerCase(), password, options: { data: { name: clean(name) } } });
    if (error) throw wrap(error);
    if (data.user && Array.isArray(data.user.identities) && data.user.identities.length === 0) throw fail('email_taken');
    if (!data.session) throw new ApiError('confirm_email', 'Check your email to confirm your account, then sign in.');
    return currentSession();
  },
  async signIn({ email, password }) {
    const { error } = await sb().auth.signInWithPassword({ email: clean(email).toLowerCase(), password });
    if (error) throw wrap(error);
    const s = await currentSession(); if (!s) throw fail('unauthenticated'); return s;
  },
  async signOut() { await sb().auth.signOut(); },
  async requestPasswordReset(email) { try { await sb().auth.resetPasswordForEmail(clean(email).toLowerCase(), { redirectTo: window.location.origin }); } catch { /* never reveal whether the email exists */ } },

  /* ── parent ── */
  async listChildren() {
    const [kids, devs, locs] = [
      ok(await sb().from('children').select('id,display_name,created_at').order('created_at')),
      ok(await sb().from('devices_public').select('id,child_id,display_name,model,status,last_seen_at,created_at').order('created_at', { ascending: false })),
      ok(await sb().from('child_locations').select('child_id,latitude,longitude,accuracy_m,battery_pct,recorded_at')),
    ];
    const kids2 = kids.map(c => {
      const k = blank(c, devs.find(d => d.child_id === c.id && d.status === 'active') || null);
      const l = locs.find(x => x.child_id === c.id);
      if (l) {
        k.location = { lat: l.latitude, lng: l.longitude, place: null, since: null, accuracyM: l.accuracy_m, updatedAt: l.recorded_at };
        if (k.device && l.battery_pct != null) k.device.battery = l.battery_pct;
      }
      return k;
    });
    return withControls(kids2);
  },
  async listAlerts() { return []; },
  async getSettings() { return { highPriority: true, arrivals: true, lowBattery: true, weekly: false }; },
  async updateSettings() { throw unavailable(); },
  async addChild({ name }) {
    const fid = await familyId(); if (!fid) throw fail('not_found');
    const r = await fn('create-child', { familyId: fid, displayName: clean(name), idempotencyKey: crypto.randomUUID() });
    const id = r.id || r.childId || (r.child && r.child.id);
    const kids = await this.listChildren(); const c = kids.find(k => k.id === id) || kids[kids.length - 1];
    return this.regeneratePairingCode(c.id).catch(() => c);
  },
  /** Pause and bedtime are real controls; every other child setting is not available yet. */
  async updateChild(id, patch = {}) {
    const keys = Object.keys(patch);
    if (!keys.length || keys.some(k => k !== 'paused' && k !== 'bedtime')) throw unavailable();
    const fid = await familyId(); if (!fid) throw fail('not_found');
    for (let attempt = 0; ; attempt++) {
      const { devices } = await fn('get-device-controls', { familyId: fid });
      const d = (devices || []).find(x => x.childId === id);
      if (!d) throw new ApiError('no_device', 'This child’s device isn’t paired yet.');
      const cur = d.desiredState || {}, controls = { ...(cur.controls || {}) };
      if ('paused' in patch) controls.paused = !!patch.paused;
      if ('bedtime' in patch) controls.bedtime = { ...DEFAULT_BEDTIME, ...(controls.bedtime || {}), enabled: !!patch.bedtime };
      try { await fn('update-device-state', { deviceId: d.deviceId, familyId: fid, desiredState: { ...cur, controls }, expectedVersion: d.desiredStateVersion }); return; }
      catch (x) { if (!(x instanceof ApiError && x.code === 'stale_version' && attempt === 0)) throw x; } // someone else changed it: re-read once
    }
  },
  async removeChild() { throw unavailable(); },
  async regeneratePairingCode(id) {
    const r = await fn('create-device-pairing', { childId: id });
    const kids = await this.listChildren(); const c = kids.find(k => k.id === id);
    return { ...c, pairingCode: fmtCode(r.code), pairingExpiresAt: r.expiresAt };
  },
  async setAlertReviewed() { throw unavailable(); },
  async respondToRequest() { throw unavailable(); },
  async grantTime() { throw unavailable(); },
  async sendCommand() { throw unavailable(); },
  async inviteParent() { throw unavailable(); },

  /* ── child ── */
  async pairDevice({ code, deviceName }) {
    const digits = String(code || '').replace(/\D/g, ''); if (digits.length !== 6) throw fail('invalid_code');
    if (hasNativeDevice()) {
      // The Android app owns the identity: key, anonymous session and the claim all happen natively.
      const { data: { session: parent } } = await sb().auth.getSession();
      if (parent) await sb().auth.signOut().catch(() => {});
      const r = await nativeCall('pair', { code: digits, deviceName: deviceName || 'This phone', supabaseUrl: SUPABASE_URL, anonKey: SUPABASE_ANON_KEY });
      if (r.status !== 'ok') throw nativeError(r);
      return currentSession();
    }
    // Reuse an existing anonymous identity if there is one; never reuse a parent session.
    let { data: { session } } = await sb().auth.getSession();
    if (session && !session.user.is_anonymous) await sb().auth.signOut();
    if (!session || !session.user.is_anonymous) { session = ok(await sb().auth.signInAnonymously()).session; }
    const { keyPair, publicKeySpki } = await newDeviceKey();
    let r;
    try { r = await fn('device-claim', { code: digits, publicKeySpki, device: { displayName: deviceName || 'This phone', model: null, androidVersion: null, supervisionMode: 'unknown' } }, { token: session.access_token }); }
    catch (x) { if (x instanceof ApiError && ['validation_failed', 'forbidden'].includes(x.code)) throw fail('invalid_code'); throw x; }
    // Persist only after the server confirmed the claim.
    await kvSet('key', { privateKey: keyPair.privateKey });
    await kvSet('binding', { deviceId: r.deviceId, familyId: r.familyId, childId: r.childId, authUserId: session.user.id });
    return currentSession();
  },
  /** Truthful status from a signed device-sync. Fields the backend doesn't provide yet are left empty. */
  async getMyStatus() {
    if (hasNativeDevice()) {
      const r = await nativeCall('sync', lastDesiredVersion === null ? undefined : { ack: lastDesiredVersion });
      if (r.status !== 'ok') { if (r.code === 'device_revoked') await nativeCall('clear'); throw nativeError(r); }
      if (Number.isSafeInteger(r.desiredStateVersion)) lastDesiredVersion = r.desiredStateVersion;
      return { child: { id: r.childId, name: '', color: colorOf(r.childId) }, paired: true, offline: !!r.offline, lastSync: r.lastSync || null, desiredStateVersion: r.desiredStateVersion ?? null, desiredState: r.desiredState ?? null, location: r.location || { available: false, enabled: false, permission: 'none' } };
    }
    const b = await binding(); if (!b) throw fail('not_found', 'This phone isn’t paired yet.');
    let sync = null, offline = false;
    try { sync = await signedSync(); if (Number.isSafeInteger(sync.desiredStateVersion)) lastDesiredVersion = sync.desiredStateVersion; await kvSet('lastSync', new Date().toISOString()); } catch (x) { if (x instanceof ApiError && ['device_revoked', 'unauthenticated'].includes(x.code)) { if (x.code === 'device_revoked') { await this.signOut(); await kvDel('binding'); await kvDel('key'); } throw x; } offline = true; }
    return { child: { id: b.childId, name: '', color: colorOf(b.childId) }, paired: true, offline, lastSync: (await kvGet('lastSync')) || null, desiredStateVersion: sync ? sync.desiredStateVersion : null, desiredState: sync ? sync.desiredState : null };
  },
  /** Location sharing is only possible inside the Android app (it needs the device's location permission). */
  async enableLocation() {
    if (!hasNativeDevice()) throw fail('unavailable', 'Sharing location needs the Harbor Family Android app.');
    const r = await nativeCall('enableLocation', undefined, { timeoutMs: 0 });
    if (r.status !== 'ok') throw nativeError(r);
    return r.location;
  },
  async disableLocation() {
    if (!hasNativeDevice()) throw fail('unavailable', 'Sharing location needs the Harbor Family Android app.');
    const r = await nativeCall('disableLocation'); if (r.status !== 'ok') throw nativeError(r);
    return r.location;
  },
  async requestMoreTime() { throw unavailable(); },
  async requestAccess() { throw unavailable(); },
  async requestInstall() { throw unavailable(); },
  async checkIn() { throw unavailable(); },
  async ackCommand() { throw unavailable(); },
  async sendSOS() { throw unavailable(); },
  /**
   * Parent approval to remove monitoring: re-authenticates a PARENT on a separate, non-persisted client
   * and calls revoke-device, which requires a recent AAL2 (MFA) step-up. Without MFA the server refuses
   * and this phone stays enrolled.
   */
  async unpairDevice({ email, password }) {
    const native = hasNativeDevice();
    const b = native ? await nativeStatus() : await binding(); if (!b || (native && !b.paired)) throw fail('not_found');
    const tmp = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, { auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false } });
    const mismatch = new ApiError('invalid_credentials', 'Those parent details don’t match.');
    const { data, error } = await tmp.auth.signInWithPassword({ email: clean(email).toLowerCase(), password });
    if (error || !data.session) throw mismatch;
    try { await fn('revoke-device', { deviceId: b.deviceId, familyId: b.familyId }, { token: data.session.access_token }); }
    catch (x) { if (x instanceof ApiError && x.code === 'forbidden') throw mismatch; throw x; }
    finally { await tmp.auth.signOut().catch(() => {}); }
    if (native) { await nativeCall('clear'); return; }
    await kvDel('binding'); await kvDel('key'); await sb().auth.signOut();
  },
};
