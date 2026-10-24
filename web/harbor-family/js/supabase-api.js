/**
 * Harbor Family data layer on the development backend (Supabase). The UI calls `api.*` (api.js) and nothing else.
 *
 *  PARENT  Supabase Auth email/password. Families, members, children and phones are read through Row Level Security.
 *          Everything else goes through Harbor's functions, reached through the backend's own `harbor-web` gateway,
 *          which names this app's origins and forwards server side. Each function still checks the caller's token.
 *          A new account starts a family or joins one with a parent's invite code; it never gets one by surprise.
 *  CHILD   (development preview only) anonymous identity + P-256 device key, paired with a parent's 6-digit code.
 *
 * Every failure is an ApiError(code, message). `unauthenticated` returns the UI to sign-in; `stale` means another
 * parent changed the same controls first.
 */
import { createClient } from '@supabase/supabase-js';
import { ApiError } from './errors.js';
import { SUPABASE_URL, SUPABASE_ANON_KEY } from './config.js';
import { newDeviceKey, proofHeaders, kvGet, kvSet, kvDel } from './device.js';

let _sb = null;
const sb = () => (_sb ||= createClient(SUPABASE_URL, SUPABASE_ANON_KEY, { auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false } }));

const MSG = {
  invalid_credentials: 'Incorrect email or password.', email_taken: 'An account with that email already exists.',
  invalid_code: 'That code isn’t valid or has expired. Ask a parent for a new one.', too_many_attempts: 'Too many tries. Ask a parent for a new code.',
  invite_invalid: 'That code isn’t valid or has expired. Ask the parent for a new one.',
  invite_locked: 'Too many tries. Wait an hour, then ask the parent for a new code.',
  forbidden: 'You don’t have access to that.', not_found: 'Not found.', unauthenticated: 'Please sign in again.',
  mfa_required: 'This needs a fresh two-step verification, which isn’t in the web app yet. Use the Harbor Family Android app.',
  unavailable: 'That isn’t available yet.', device_revoked: 'This phone was removed by a parent.',
  stale: 'Another parent changed this at the same time.', network: 'Can’t reach Harbor. Check your connection.',
};
const fail = (code, m) => new ApiError(code, m || MSG[code] || 'Something went wrong. Please try again.');

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
  if (/fetch|network/i.test(m)) return fail('network');
  return new ApiError('unknown', 'Something went wrong. Please try again.');
}
const ok = ({ data, error }) => { if (error) throw wrap(error); return data; };
const clean = s => String(s ?? '').trim();

/** Calls one of Harbor's functions through this site. Backend errors are { code, message }. */
async function fn(name, body, { token, headers = {} } = {}) {
  let t = token;
  if (!t) { const { data: { session } } = await sb().auth.getSession(); t = session && session.access_token; }
  if (!t) throw fail('unauthenticated');
  let r;
  try { r = await fetch(`${SUPABASE_URL}/functions/v1/harbor-web/${name}`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${t}`, apikey: SUPABASE_ANON_KEY, ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body) }); }
  catch { throw fail('network'); }
  const j = await r.json().catch(() => ({}));
  if (r.ok) return j;
  const map = { AUTH_REQUIRED: 'unauthenticated', FORBIDDEN: 'forbidden', MFA_REQUIRED: 'mfa_required', DEVICE_REVOKED: 'device_revoked', NOT_FOUND: 'not_found', STALE_VERSION: 'stale' };
  const code = map[j.code] || (r.status === 401 ? 'unauthenticated' : r.status === 409 ? 'stale' : null);
  if (code) throw fail(code);
  throw new ApiError(String(j.code || 'unknown').toLowerCase(), j.message || 'Something went wrong. Please try again.');
}

/* ── parent ── */
const FAMILY_KEY = 'harbor.family';
const remembered = () => { try { return localStorage.getItem(FAMILY_KEY); } catch { return null; } };
const remember = id => { try { id ? localStorage.setItem(FAMILY_KEY, id) : localStorage.removeItem(FAMILY_KEY); } catch { /* per-viewer convenience only */ } };

async function parentSession(user) {
  const rows = ok(await sb().from('family_members').select('family_id,role,status').eq('user_id', user.id).eq('status', 'active'));
  const ids = rows.map(r => r.family_id);
  const fams = ids.length ? ok(await sb().from('families').select('id,name').in('id', ids)) : [];
  const families = fams.map(f => ({ id: f.id, name: f.name, role: (rows.find(r => r.family_id === f.id) || {}).role || 'parent' }));
  const pick = families.find(f => f.id === remembered()) || families[0] || null;
  const name = (user.user_metadata && (user.user_metadata.name || user.user_metadata.display_name)) || '';
  return { role: 'parent', user: { id: user.id, name, email: user.email }, families, familyId: pick ? pick.id : null };
}

/* ── child device state (IndexedDB) ── */
const binding = () => kvGet('binding');
async function childSession() {
  const b = await binding(); if (!b) return null;
  const { data: { session } } = await sb().auth.getSession();
  if (!session || session.user.id !== b.authUserId) return null;
  return { role: 'child', user: { id: session.user.id, name: '', email: '' }, child: { id: b.childId, name: '' } };
}
async function currentSession() {
  const { data: { session }, error } = await sb().auth.getSession();
  if (error) throw wrap(error);
  if (!session) return null;
  if (session.user.is_anonymous) return childSession();
  return parentSession(session.user);
}
async function signedSync() {
  const b = await binding(); const key = await kvGet('key');
  if (!b || !key) throw fail('not_found', 'This phone isn’t paired yet.');
  const { data: { session } } = await sb().auth.getSession(); if (!session) throw fail('unauthenticated');
  const body = '{}';
  const headers = await proofHeaders(key.privateKey, { operation: 'device-sync', deviceId: b.deviceId, body });
  return fn('device-sync', body, { token: session.access_token, headers });
}

export const supabaseApi = {
  async getSession() { return currentSession(); },
  /** Parent accounts only. Children never create accounts: they pair with a code (pairDevice). */
  async signUp({ name, email, password }) {
    const { data, error } = await sb().auth.signUp({ email: clean(email).toLowerCase(), password, options: { data: { name: clean(name), display_name: clean(name) } } });
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
  async signOut() { remember(null); await sb().auth.signOut(); },
  async requestPasswordReset(email) { try { await sb().auth.resetPasswordForEmail(clean(email).toLowerCase(), { redirectTo: window.location.origin }); } catch { /* never reveal whether the email exists */ } },
  chooseFamily(id) { remember(id); },

  /* ── a family: start one, or join one with a parent's invite ── */
  async createFamily(name) {
    const { data: { user } } = await sb().auth.getUser(); if (!user) throw fail('unauthenticated');
    const r = await fn('create-family', { name: clean(name) || 'My family', idempotencyKey: `family-${user.id}` });
    remember(r.familyId); return currentSession();
  },
  async joinFamily(code) {
    try { const r = await fn('accept-parent-invite', { code: clean(code) }); remember(r.familyId); return { ...(await currentSession()), joined: r.familyName || 'the family' }; }
    catch (x) { if (x instanceof ApiError && x.code === 'validation_failed') throw fail('invite_invalid'); if (x instanceof ApiError && x.code === 'forbidden') throw fail('invite_locked'); throw x; }
  },
  /** The name the rest of the family sees on this parent. A parent may set only their own. */
  async setMyName(name) {
    const { data: { user } } = await sb().auth.getUser(); if (!user) throw fail('unauthenticated');
    const wanted = clean(name);
    if (!wanted || wanted.length > 100) throw new ApiError('validation_failed', 'Enter the name your family will see.');
    ok(await sb().from('profiles').update({ display_name: wanted }).eq('id', user.id));
    return wanted;
  },

  /** A one-day code another adult enters to join this family as a parent. */
  async createInvite(familyId) { return fn('create-parent-invite', { familyId }); },

  /** Everyone and every phone in one family, as this parent may see them. */
  async loadFamily(familyId) {
    const [members, kids, devs] = await Promise.all([
      sb().from('family_members').select('user_id,role,status,created_at').eq('family_id', familyId).eq('status', 'active').order('created_at'),
      sb().from('children').select('id,display_name,created_at').eq('family_id', familyId).order('created_at'),
      sb().from('devices_public').select('id,child_id,display_name,model,status,last_seen_at,created_at').eq('family_id', familyId).order('created_at', { ascending: false }),
    ]).then(rs => rs.map(ok));
    const profiles = members.length ? ok(await sb().from('profiles').select('id,display_name').in('id', members.map(m => m.user_id))) : [];
    return {
      parents: members.map(m => ({ userId: m.user_id, role: m.role, name: (profiles.find(p => p.id === m.user_id) || {}).display_name || '' })),
      children: kids.map((c, i) => {
        const d = devs.find(x => x.child_id === c.id && x.status === 'active') || null;
        return { id: c.id, name: c.display_name, index: i, device: d ? { id: d.id, name: d.display_name, model: d.model, lastSeenAt: d.last_seen_at } : null };
      }),
    };
  },
  async addChild(familyId, name) {
    const r = await fn('create-child', { familyId, displayName: clean(name), idempotencyKey: crypto.randomUUID() });
    return r.childId || r.id || (r.child && r.child.id);
  },
  /** A six-digit code the child's phone enters; it works once, for 10 minutes. */
  async pairingCode(childId) { return fn('create-device-pairing', { childId }); },

  /* ── one phone ── */
  async getDeviceUsage(deviceId) { return fn('get-device-usage', { deviceId }); },
  async getDeviceLocation(deviceId) { return fn('get-device-location', { deviceId }); },
  async getDeviceAlerts(deviceId, before = null) { return fn('get-message-alerts', { deviceId, version: 1, before, limit: 50 }); },
  async getActivity(deviceId, from, to) { return fn('get-device-activity', { version: 1, deviceId, from, to }); },
  async getCheckIns(deviceId) { return fn('get-device-checkins', { version: 1, deviceId }); },
  async requestCheckIn(deviceId) { return fn('request-device-checkin', { version: 1, deviceId }); },
  /** { controls | null, desiredStateVersion, acknowledgedVersion } */
  async getControls(deviceId) { return fn('get-device-controls', { version: 1, deviceId }); },
  /** Writes the controls if the server is still at expectedVersion; ApiError('stale') when another parent was first. */
  async writeControls({ familyId, deviceId, controls, expectedVersion }) {
    const r = await fn('update-device-state', { deviceId, familyId, expectedVersion, desiredState: { controls } });
    return r.desiredStateVersion;
  },
  async getRequests(deviceId) { return fn('get-access-requests', { version: 1, deviceId }); },
  async decideRequest(requestId, approve) { return fn('decide-access-request', { version: 1, requestId, decision: approve ? 'approved' : 'denied' }); },

  /* ── child (development preview) ── */
  async pairDevice({ code, deviceName }) {
    const digits = String(code || '').replace(/\D/g, ''); if (digits.length !== 6) throw fail('invalid_code');
    let { data: { session } } = await sb().auth.getSession();
    if (session && !session.user.is_anonymous) await sb().auth.signOut();
    if (!session || !session.user.is_anonymous) { session = ok(await sb().auth.signInAnonymously()).session; }
    const { keyPair, publicKeySpki } = await newDeviceKey();
    let r;
    try { r = await fn('device-claim', { code: digits, publicKeySpki, device: { displayName: deviceName || 'This phone', model: null, androidVersion: null, supervisionMode: 'unknown' } }, { token: session.access_token }); }
    catch (x) { if (x instanceof ApiError && ['validation_failed', 'forbidden'].includes(x.code)) throw fail('invalid_code'); throw x; }
    await kvSet('key', { privateKey: keyPair.privateKey });
    await kvSet('binding', { deviceId: r.deviceId, familyId: r.familyId, childId: r.childId, authUserId: session.user.id });
    return currentSession();
  },
  async getMyStatus() {
    const b = await binding(); if (!b) throw fail('not_found', 'This phone isn’t paired yet.');
    let sync = null, offline = false;
    try { sync = await signedSync(); await kvSet('lastSync', new Date().toISOString()); } catch (x) { if (x instanceof ApiError && ['device_revoked', 'unauthenticated'].includes(x.code)) { if (x.code === 'device_revoked') { await this.signOut(); await kvDel('binding'); await kvDel('key'); } throw x; } offline = true; }
    return { child: { id: b.childId, name: '' }, paired: true, offline, lastSync: (await kvGet('lastSync')) || null, desiredStateVersion: sync ? sync.desiredStateVersion : null };
  },
  /** Removing monitoring needs a parent and a recent two-step verification (revoke-device); without it the phone stays. */
  async unpairDevice({ email, password }) {
    const b = await binding(); if (!b) throw fail('not_found');
    const tmp = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, { auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false } });
    const mismatch = new ApiError('invalid_credentials', 'Those parent details don’t match.');
    const { data, error } = await tmp.auth.signInWithPassword({ email: clean(email).toLowerCase(), password });
    if (error || !data.session) throw mismatch;
    try { await fn('revoke-device', { deviceId: b.deviceId, familyId: b.familyId }, { token: data.session.access_token }); }
    catch (x) { if (x instanceof ApiError && x.code === 'forbidden') throw mismatch; throw x; }
    finally { await tmp.auth.signOut().catch(() => {}); }
    await kvDel('binding'); await kvDel('key'); await sb().auth.signOut();
  },
};
