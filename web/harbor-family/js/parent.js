// PARENT experience: Map, Alerts, Controls, Activity, Family — the same five tabs, words and rules as the Android parent
// app, in the Harbor Family design system. Mounted only for role === 'parent'. Nothing is kept after sign-out.
import { api, ApiError } from './api.js';
import { toUsageView } from './usage-view.js';
import { toLocationView, renderLocationView } from './location-view.js';
import { toAlertsView, renderAlertsList, renderAlertDetail } from './alerts-view.js';
import { renderActivity, activityWindow, localDate } from './activity-view.js';
import {
  DeviceControls, GRANT_MS, WEEKDAYS, ruleOf, withAppRule, withPauseNow, withResume, withExtraTime, withDailyLimit, withBedtime,
  withGrant, withoutGrant, liveGrants,
} from './controls.js';
import { $, e, clock, when, ago, minutes, icon, brand, empty, header, btn, note, tag, sw, fld, form, avatar, crew, busy, open, shut, toast, greeting } from './common.js';

const TABS = [['map', 'Map'], ['alerts', 'Alerts'], ['controls', 'Controls'], ['activity', 'Activity'], ['family', 'Family']];
const HEAD = { map: 'Family overview', alerts: ['What needs attention', 'Beacon alerts'], controls: ['Family boundaries', 'Steady guidance'], activity: ['Daily patterns', 'A day at a glance'], family: ['Your circle', 'Your crew'] };
const S = { user: null, families: [], familyId: null, family: null, k: null, tab: 'map', day: null, timelineOpen: false, mapTitle: '' };
let cb = {}, controls = null, timer = null, seq = 0;
const cache = new Map(); // `${kind}|${deviceId}` → { reply, loading, offline, denied }
const kid = () => (S.family && S.family.children.find(c => c.id === S.k)) || null;
const famName = () => (S.families.find(f => f.id === S.familyId) || {}).name || 'Your family';
const possessive = n => `${n}’${/s$/i.test(n) ? '' : 's'}`;

function handle(x) { if (x && x.code === 'unauthenticated') { cb.onExpired && cb.onExpired(); return true; } return false; }
const failText = x => x instanceof ApiError ? x.message : 'Something went wrong. Please try again.';

/* ───────────── loading ───────────── */
async function loadFamily() {
  const mine = ++seq;
  try {
    const fam = await api.loadFamily(S.familyId);
    if (mine !== seq) return;
    S.family = fam;
    if (!fam.children.some(c => c.id === S.k)) S.k = fam.children[0] ? fam.children[0].id : null;
  } catch (x) { if (!handle(x) && !S.family) toast('Couldn’t load your family', { tone: 'review', detail: 'Check your connection and try again.' }); }
  syncControls(); render();
}
const LOADERS = {
  location: d => api.getDeviceLocation(d), alerts: d => api.getDeviceAlerts(d), usage: d => api.getDeviceUsage(d),
  requests: d => api.getRequests(d), checkins: d => api.getCheckIns(d),
  activity: d => { const w = activityWindow(S.day); return api.getActivity(d, w.from, w.to); },
};
const keyOf = (kind, d) => kind === 'activity' ? `${kind}|${d}|${S.day}` : `${kind}|${d}`;
async function load(kind, { force = false } = {}) {
  const k = kid(); const d = k && k.device && k.device.id; if (!d) return;
  const key = keyOf(kind, d), prev = cache.get(key);
  if (prev && (prev.loading || !force)) return;
  cache.set(key, { reply: prev ? prev.reply : null, loading: true });
  try { const reply = await LOADERS[kind](d); cache.set(key, { reply }); }
  catch (x) {
    if (handle(x)) return;
    cache.set(key, ['forbidden', 'not_found', 'device_revoked'].includes(x && x.code) ? { reply: null, denied: true } : { reply: prev ? prev.reply : null, offline: true });
  }
  if (keyOf(kind, (kid() && kid().device && kid().device.id) || '') === key) render();
}
const got = kind => { const k = kid(); return k && k.device ? cache.get(keyOf(kind, k.device.id)) || null : null; };

/** One DeviceControls for the phone on screen; switching child or family drops it. */
function syncControls() {
  const k = kid(), d = k && k.device ? k.device.id : null;
  if (controls && controls.deviceId === d && controls.familyId === S.familyId) return;
  if (controls) controls.dispose();
  controls = d ? new DeviceControls(api, S.familyId, d, () => { if (S.tab === 'controls') render(); }) : null;
}
function loadTab(force = false) {
  const k = kid(); if (!k || !k.device) return;
  if (S.tab === 'map') { load('location', { force }); load('checkins', { force }); }
  if (S.tab === 'alerts') load('alerts', { force });
  if (S.tab === 'controls') { if (controls && (force || controls.view.status === 'loading')) controls.load(); load('requests', { force }); load('usage'); load('activity', { force }); }
  if (S.tab === 'activity') { load('usage', { force }); load('activity', { force }); }
}

/* ───────────── pieces ───────────── */
function kidSwitcher() {
  const kids = S.family ? S.family.children : [];
  if (!kids.length || S.tab === 'family') return '';
  const k = kid();
  const meta = k.device ? `${e(k.device.name || 'Phone')}${k.device.lastSeenAt ? ' · seen ' + e(ago(k.device.lastSeenAt)) : ''}` : 'No phone paired yet';
  return `<div class="hb-ks"><div class="hb-ks-stack">${kids.map(c => `<button class="hb-ks-k${c.id === S.k ? ' hb-ks-on' : ''}" style="background:${crew(c.index)}" data-a="kid" data-id="${e(c.id)}" aria-pressed="${c.id === S.k}" aria-label="${e(c.name)}">${e((c.name.trim()[0] || '?').toUpperCase())}</button>`).join('')}</div>`
    + `<div class="hb-ks-name"><b>${e(k.name)}</b><span>${meta}</span></div><button class="hb-ks-add" data-a="addkid" aria-label="Add a child">${icon('plus', 20)}</button></div>`;
}
function tabHeader(k) {
  if (S.tab === 'map') return header(HEAD.map, S.mapTitle, k && k.device ? `${possessive(k.name)} ${k.device.name || 'phone'}` : famName());
  const [eb, t] = HEAD[S.tab];
  return header(eb, t, S.tab === 'family' ? famName() : k ? (k.device ? `${possessive(k.name)} ${k.device.name || 'phone'}` : `${k.name} has no phone paired yet`) : '');
}
const loadingCard = what => `<div class="hb-card"><div class="hb-loading hb-loading-row"><i class="hb-spin" style="color:var(--acc)"></i><span class="hb-loading-t">${e(what)}</span></div></div>`;
const refreshBtn = (kind, label) => btn(`Refresh ${label}`, `data-a="refresh" data-v="${kind}"`, 'outline', 'hb-btn-block');

function noPhone(k) {
  return `<div class="hb-card">${empty(`Add ${possessive(e(k.name))} phone`, `Pair ${e(k.name)}’s Android phone to see places, alerts, screen time and controls.`,
    { art: 'anchor', action: btn('Pair a phone', `data-a="code" data-id="${e(k.id)}"`) })}</div>`;
}
function deviceSection(kind, label, body) {
  const u = got(kind);
  if (u && u.denied) return `<div class="hb-card">${empty('No access', `You no longer have access to this phone’s ${label}.`, { art: 'anchor' })}</div>`;
  if (!u || (u.loading && !u.reply)) return loadingCard(`Loading ${label}…`);
  if (!u.reply) return note(`<b>Couldn’t load ${label}.</b> Check your connection and try again.`, true) + refreshBtn(kind, label);
  return body(u.reply, !!u.offline);
}

/* ───────────── Map ───────────── */
const CHECKIN_LATE_MS = 10 * 60_000;
function checkIns(k) {
  const u = got('checkins'), name = e(k.name);
  const list = u && u.reply && Array.isArray(u.reply.checkins) ? u.reply.checkins : null;
  const now = Date.now();
  const open = list && list.find(c => c.status === 'pending' && Date.parse(c.expiresAt) > now);
  const latest = list && list.find(c => c.status === 'answered' && c.reply);
  let body = '';
  if (!list) body = u && u.loading ? '<p>Loading check-ins…</p>' : '<p>Check-ins could not be loaded.</p>';
  else if (open) {
    const late = now - Date.parse(open.requestedAt) >= CHECKIN_LATE_MS;
    body = `<div class="hb-ci"><div class="hb-ci-step hb-ci-done"><i class="hb-ci-dot"></i><div><div class="hb-ci-t">Request sent</div></div><span class="hb-ci-time">${e(clock(open.requestedAt))}</span></div>`
      + (late ? `<div class="hb-ci-step hb-ci-warn"><i class="hb-ci-dot"></i><div><div class="hb-ci-t">No response yet</div><div class="hb-ci-s">Asked ${e(ago(open.requestedAt))}. The request stays open until ${e(when(open.expiresAt))}.</div></div></div>`
        : `<div class="hb-ci-step hb-ci-now hb-ci-sonar"><i class="hb-ci-dot"></i><div><div class="hb-ci-t">Waiting for ${name}</div><div class="hb-ci-s">${name} gets a notification and can answer in two taps.</div></div></div>`)
      + `<div class="hb-ci-step hb-ci-pending"><i class="hb-ci-dot"></i><div><div class="hb-ci-t">${name} checked in</div></div></div></div>`;
  } else {
    body = latest ? `<div class="hb-row"><div class="hb-rm"><span class="hb-sum-ic">${icon('check', 16, 2.3)}</span><div><span class="hf-rt">${name} ${latest.reply.preset === 'ok' ? 'is okay' : 'is running late'}</span><span class="hb-rs">Checked in ${e(when(latest.reply.repliedAt))}</span></div></div></div>`
      : `<p>No check-ins yet. When you ask, ${name} answers with a short note and this phone’s place.</p>`;
    body += btn(`Request ${name}’s check-in`, 'data-a="checkin"', 'primary', 'hb-btn-block');
  }
  return `<div class="hb-card"><h3>Check-ins</h3>${body}</div>`;
}
function mapView(k) {
  return deviceSection('location', 'location', (reply, offline) => renderLocationView(toLocationView(reply, Date.now(), { offline }), { name: k.name, color: crew(k.index), initial: (k.name.trim()[0] || '').toUpperCase() }))
    + checkIns(k);
}

/* ───────────── Alerts ───────────── */
function alertsView() {
  return deviceSection('alerts', 'alerts', (reply, offline) => renderAlertsList(toAlertsView([reply], { offline }), { name: kid().name }));
}
function recentAlerts() {
  const u = got('alerts'); if (!u || !u.reply) return 0;
  const v = toAlertsView([u.reply]);
  return v.status === 'available' ? v.alerts.filter(a => Date.now() - Date.parse(a.at) < 86400000).length : 0;
}

/* ───────────── Controls ───────────── */
const DAILY = [30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 480, 600, 720];
const APP_LIMITS = [15, 30, 45, 60, 90, 120, 180];
const CLOCKS = Array.from({ length: 48 }, (_, i) => `${String(Math.floor(i / 2)).padStart(2, '0')}:${i % 2 ? '30' : '00'}`);
const clockLabel = hm => { const [h, m] = hm.split(':').map(Number); return new Date(2000, 0, 1, h, m).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); };
const hm = m => m >= 60 ? `${Math.floor(m / 60)}h${m % 60 ? ' ' + String(m % 60).padStart(2, '0') + 'm' : ''}` : `${m} min`;
const option = (value, label, sel) => `<option value="${e(value)}" ${sel ? 'selected' : ''}>${e(label)}</option>`;

function appCatalog(c) {
  const labels = new Map();
  const u = got('usage'); const uv = u && u.reply ? toUsageView(u.reply, Date.now()) : null;
  if (uv && uv.status === 'available') for (const a of uv.apps) if (a.labelKnown) labels.set(a.packageName, a.label);
  const todayMs = new Map();
  const a = got('activity'); const st = a && a.reply && a.reply.screenTime;
  if (st && st.date === localDate()) for (const x of st.apps) todayMs.set(x.packageName, x.ms);
  const names = new Set([...labels.keys(), ...c.blockedApps, ...c.alwaysAllowed, ...c.appLimits.map(l => l.packageName), ...todayMs.keys()]);
  const label = p => labels.get(p) || p.split('.').pop().replace(/^\w/, s => s.toUpperCase());
  return { list: [...names].map(p => ({ pkg: p, label: label(p), ms: todayMs.get(p) || 0 })).sort((x, y) => y.ms - x.ms || x.label.localeCompare(y.label)), label, screenTime: st && st.date === localDate() ? st : null };
}
function requestTitle(r) {
  if (r.appLabel === 'Remove an app') return 'Remove an app (it could be Harbor)';
  return { limit_reached: `15 more minutes of ${r.appLabel}`, time_up: `15 more minutes of ${r.appLabel} (time’s up today)`, blocked_app: `Use ${r.appLabel} (blocked)`,
    paused: `Use ${r.appLabel} while the phone is paused`, bedtime: `Use ${r.appLabel} at bedtime` }[r.reason] || `Use ${r.appLabel}`;
}
function requestsPanel(k) {
  const u = got('requests'); const reqs = u && u.reply && Array.isArray(u.reply.requests) ? u.reply.requests : [];
  const pending = reqs.filter(r => r.status === 'pending'), done = reqs.filter(r => r.status !== 'pending').slice(0, 3);
  if (!pending.length && !done.length) return '';
  return '<div class="hb-card"><h3>Requests</h3>' + pending.map(r => `<div class="hb-req"><div class="hb-req-h">${avatar(k.name, crew(k.index))}<div><div class="hb-req-t">${e(requestTitle(r))}</div><div class="hb-req-s">${e(k.name)} asked ${e(ago(r.requestedAt))}</div></div>${tag('Request', 'low')}</div>`
    + `<div class="hb-req-a">${btn('Allow 15 min', `data-a="req" data-rid="${e(r.requestId)}" data-ok="1"`, 'primary', 'hb-btn-small')}${btn('Deny', `data-a="req" data-rid="${e(r.requestId)}"`, 'outline', 'hb-btn-small')}</div></div>`).join('<hr class="hf-hr">')
    + (pending.length ? '' : '<p>No requests waiting.</p>')
    + done.map(r => `<div class="hb-req-line">${icon(r.status === 'approved' ? 'check' : 'hourglass', 16)}<span>${{ approved: 'Allowed 15 min', denied: 'Said no' }[r.status] || 'Not answered in time'} · ${e(requestTitle(r))} · ${e(ago(r.requestedAt))}</span></div>`).join('')
    + '</div>';
}
function controlsView(k) {
  if (!controls) return noPhone(k);
  const v = controls.view, name = e(k.name), now = Date.now();
  if (v.status === 'loading') return loadingCard('Loading controls…');
  if (v.status === 'lost') return `<div class="hb-card">${empty('No access', 'You no longer have access to this phone’s controls.', { art: 'anchor' })}</div>`;
  if (v.status === 'failed') return note('<b>Couldn’t load controls.</b> Check your connection and try again.', true) + refreshBtn('controls', 'controls');
  const c = v.controls, cat = appCatalog(c);
  const status = v.saving ? '<div class="hf-applied pending"><i></i>Saving…</div>'
    : v.saveFailed ? `<div class="hb-err" role="alert">That change wasn’t saved. Check your connection and try again.</div>`
    : `<div class="hf-applied${v.applied ? '' : ' pending'}"><i></i>${v.applied ? `In effect on ${name}’s phone` : `Waiting for ${name}’s phone to pick this up`}</div>`;
  const paused = c.pause.active;
  const pause = paused
    ? `<div class="hb-ban"><span>${name}’s phone is paused</span>${btn('Resume phone', 'data-a="resume"', 'outline', 'hb-btn-small')}</div>`
    : `<div class="hf-two">${btn('Pause phone now', 'data-a="pause"')}${btn('Give 15 more min', `data-a="extra" ${c.dailyLimit ? '' : 'disabled title="Set a daily limit first"'}`, 'secondary')}</div>`;
  const grants = liveGrants(c, now);
  const allowed = grants.length ? `<div class="hb-panel">${grants.map(g => `<div class="hb-row"><div><span class="hf-rt">${e(g.appLabel || (g.packageName === 'com.google.android.packageinstaller' || g.packageName === 'com.android.packageinstaller' ? 'Removing apps' : cat.label(g.packageName)))}</span><span class="hb-rs">Until ${e(clock(g.until))} (you said yes)</span></div>${btn('End now', `data-a="endgrant" data-v="${e(g.packageName)}"`, 'outline', 'hb-btn-small')}</div>`).join('')}</div><p class="hf-small">Pausing the phone or changing an app’s rule also ends its extra time.</p>` : '';
  const used = cat.screenTime ? Math.round(cat.screenTime.totalMs / 60000) : null;
  const allowance = c.dailyLimit ? c.dailyLimit.minutes + (c.dailyLimit.extraDate === localDate() ? c.dailyLimit.extraMinutes : 0) : null;
  const dailySel = `<select class="hf-select" data-c="daily" aria-label="Daily screen time">${option('off', 'No daily limit', !c.dailyLimit)}${[...new Set([...DAILY, ...(c.dailyLimit ? [c.dailyLimit.minutes] : [])])].sort((a, b) => a - b).map(m => option(m, hm(m), c.dailyLimit && c.dailyLimit.minutes === m)).join('')}</select>`;
  const daily = `<div class="hb-card"><h3>Daily screen time</h3><div class="hb-use"><div class="hb-big">${used === null ? '—' : e(minutes(used * 60000))}${allowance ? `<small> / ${e(hm(allowance))}</small>` : ''}</div>${dailySel}</div>`
    + (allowance ? `<div class="hb-bar${used !== null && used >= allowance ? ' hb-bar-over' : ''}"><i style="width:${Math.min(100, Math.round(((used || 0) / allowance) * 100))}%"></i></div>` : '')
    + `<p>${c.dailyLimit ? 'When time is up, only apps set to Unlimited work until tomorrow. Calls and texts always work.' : 'Set a total for the day. Apps set to Unlimited don’t count toward it.'}</p>`
    + (c.dailyLimit && c.dailyLimit.extraDate === localDate() && c.dailyLimit.extraMinutes ? `<p class="hf-small">Includes ${e(hm(c.dailyLimit.extraMinutes))} extra for today.</p>` : '') + '</div>';
  const bed = c.bedtime || { enabled: false, start: '21:00', end: '07:00', days: [...WEEKDAYS] };
  const bedtime = `<div class="hb-card"><div class="hb-row" style="padding-top:0"><div><span class="hf-rt">Bedtime</span><span class="hb-rs">${e(clockLabel(bed.start))} – ${e(clockLabel(bed.end))} · phone locks</span></div>${sw('bed', bed.enabled, 'Bedtime')}</div>`
    + (bed.enabled ? `<div class="hf-two" style="margin:6px 0 10px"><select class="hf-select" data-c="bed-start" aria-label="Bedtime starts">${CLOCKS.filter(t => t !== bed.end).map(t => option(t, 'From ' + clockLabel(t), t === bed.start)).join('')}</select><select class="hf-select" data-c="bed-end" aria-label="Bedtime ends">${CLOCKS.filter(t => t !== bed.start).map(t => option(t, 'To ' + clockLabel(t), t === bed.end)).join('')}</select></div>`
      + `<div class="hf-days">${WEEKDAYS.map(d => `<button class="hb-chip" data-a="bed-day" data-v="${d}" aria-pressed="${bed.days.includes(d)}">${d[0].toUpperCase() + d.slice(1)}</button>`).join('')}</div><p class="hf-small">At bedtime every app locks except calls, texts and Harbor.</p>` : '') + '</div>';
  const apps = cat.list.length ? `<div class="hb-panel">${cat.list.map(a => {
    const r = ruleOf(c, a.pkg), value = r.rule === 'limited' ? `limit:${r.minutes}` : r.rule;
    const limits = [...new Set([...APP_LIMITS, ...(r.rule === 'limited' ? [r.minutes] : [])])].sort((x, y) => x - y);
    const summary = `${a.ms ? `${minutes(a.ms)} today` : 'Not used today'}${r.rule === 'limited' ? ` · ${hm(r.minutes)} a day` : ''}`;
    return `<div class="hb-row"><div class="hb-rm">${avatar(a.label, crew([...a.pkg].reduce((n, ch) => n + ch.charCodeAt(0), 0)))}<div style="min-width:0"><span class="hf-rt">${e(a.label)}</span><span class="hb-rs">${e(summary)}</span></div></div>`
      + `<select class="hf-select" data-c="app" data-v="${e(a.pkg)}" aria-label="${e(a.label)} rule">${option('allowed', 'No limit', value === 'allowed')}${option('always', 'Unlimited', value === 'always')}${limits.map(m => option(`limit:${m}`, `${hm(m)} a day`, value === `limit:${m}`)).join('')}${option('blocked', 'Blocked', value === 'blocked')}</select></div>`;
  }).join('')}</div><p class="hf-small">Unlimited apps work even when the day’s time is up, and don’t count toward it. Pause and bedtime still apply.</p>`
    : `<div class="hb-card">${empty('No apps listed yet', `${name}’s phone sends its list of apps with its screen-time report.`, { art: 'buoy' })}</div>`;
  return status + requestsPanel(k) + pause + (grants.length ? '<div class="hf-section">Allowed right now</div>' + allowed : '')
    + '<div class="hf-section">Screen time</div>' + daily + '<div class="hf-section">Schedules</div>' + bedtime + '<div class="hf-section">Apps</div>' + apps;
}

/* ───────────── Activity ───────────── */
function activityView(k) {
  const a = got('activity'), u = got('usage');
  if (a && a.denied) return `<div class="hb-card">${empty('No access', 'You no longer have access to this phone’s activity.', { art: 'anchor' })}</div>`;
  if (!a || (a.loading && !a.reply)) return loadingCard('Loading activity…');
  return renderActivity({ reply: a.reply, offline: !!a.offline, usage: u && u.reply ? toUsageView(u.reply, Date.now()) : null, day: S.day, name: k.name, open: S.timelineOpen });
}

/* ───────────── Family ───────────── */
function familyView() {
  if (!S.family) return loadingCard('Loading your family…');
  const kids = S.family.children, parents = S.family.parents;
  const kidRows = kids.map(c => `<div class="hb-row"><div class="hb-rm">${avatar(c.name, crew(c.index), 'hb-av-lg')}<div><span class="hf-rt">${e(c.name)}</span><span class="hb-rs">${c.device ? `${e(c.device.name || 'Phone')}${c.device.lastSeenAt ? ' · seen ' + e(ago(c.device.lastSeenAt)) : ''}` : 'No phone paired yet'}</span></div></div>${c.device ? tag('Paired', 'low') : btn('Pair a phone', `data-a="code" data-id="${e(c.id)}"`, 'secondary', 'hb-btn-small')}</div>`).join('');
  const parentRows = parents.map(p => `<div class="hb-row"><div class="hb-rm">${avatar(p.name || '?', 'var(--brand)', 'hb-av-lg')}<div><span class="hf-rt">${e(p.name || 'A parent')}${p.userId === S.user.id ? ' (you)' : ''}</span><span class="hb-rs">${p.role === 'owner' ? 'Started this family' : 'Parent'}</span></div></div></div>`).join('');
  const others = S.families.length > 1 ? `<div class="hf-section">Families</div><div class="hb-panel">${S.families.map(f => `<div class="hb-row"><div><span class="hf-rt">${e(f.name)}</span><span class="hb-rs">${f.role === 'owner' ? 'You started it' : 'You joined'}</span></div>${f.id === S.familyId ? tag('Showing', 'low') : btn('Show', `data-a="family" data-id="${e(f.id)}"`, 'outline', 'hb-btn-small')}</div>`).join('')}</div>` : '';
  return `<div class="hf-section">Children</div><div class="hb-panel">${kidRows || `<div class="hb-row"><p>No children yet.</p></div>`}<div class="hb-row">${btn('Add a child', 'data-a="addkid"', 'secondary', 'hb-btn-block')}</div></div>`
    + `<div class="hf-section">Parents</div><div class="hb-panel">${parentRows}<div class="hb-row">${btn('Invite a parent', 'data-a="invite"', 'secondary', 'hb-btn-block')}</div></div>`
    + '<p class="hf-small">Every parent sees the same children and phones and can change the same controls. When two parents change a rule at the same time, both changes are kept.</p>'
    + others
    + `<div class="hf-section">Account</div><div class="hb-panel"><div class="hb-row"><div><span class="hf-rt">${e(S.user.name || 'Signed in')}</span><span class="hb-rs">${e(S.user.email || '')}</span></div>${btn('Sign out', 'data-a="signout"', 'outline', 'hb-btn-small')}</div></div>`;
}

/* ───────────── sheets ───────────── */
function pairSheet(child, code) {
  const exp = code && code.expiresAt ? Date.parse(code.expiresAt) : 0, old = !code || exp <= Date.now();
  open(`<h2>Pair ${e(child.name)}’s phone</h2><ol class="hb-pair-steps"><li>Install <b>Harbor Family</b> on ${e(child.name)}’s Android phone.</li><li>Open it and choose <b>Connect this phone</b>.</li><li>Enter this code. It works once, for 10 minutes.</li></ol>`
    + `<div class="hb-code${old ? ' hb-code-old' : ''}" aria-label="Pairing code">${code ? e(code.code.slice(0, 3) + ' ' + code.code.slice(3)) : '— — —'}</div>`
    + (old ? '<div class="hb-pair-exp">This code has expired.</div>' : `<p class="hb-pair-wait"><i class="hb-pair-dot"></i>Works until ${e(clock(code.expiresAt))}. Waiting for the phone.</p>`)
    + `<div class="hf-acts">${old ? btn('Get a new code', `data-a="newcode" data-id="${e(child.id)}"`) : ''}${btn('Done', 'data-a="donepair"', old ? 'outline' : 'primary', 'hb-btn-block')}</div>`);
}
function inviteSheet(invite) {
  open(`<h2>Invite a parent</h2><p class="hf-muted">The other parent uses their own account and sees everything you see.</p><ol class="hb-pair-steps"><li>They open <b>${e(location.host)}</b> on their phone or computer and create an account with their own email.</li><li>They choose <b>I have an invite code</b>.</li><li>They enter this code. It works once, for one day.</li></ol>`
    + `<div class="hb-code" aria-label="Invite code">${e(invite.code)}</div><p class="hb-pair-wait"><i class="hb-pair-dot"></i>Works until ${e(when(invite.expiresAt))}. A new code replaces this one.</p>`
    + `<div class="hf-acts">${btn('Copy code', `data-a="copy" data-v="${e(invite.code)}"`, 'secondary')}${btn('Done', 'data-a="x"')}</div>`);
}

/* ───────────── render ───────────── */
function render(enter) {
  const k = kid();
  $('#brand').innerHTML = `${brand()}<span class="hf-sub">${e(famName())}</span>`;
  $('#kids').innerHTML = kidSwitcher();
  $('#kids').hidden = !$('#kids').innerHTML;
  const view = $('#view'), keep = window.scrollY;
  let body;
  if (S.tab === 'family') body = familyView();
  else if (!S.family) body = loadingCard('Loading your family…');
  else if (!k) body = `<div class="hb-card">${empty('Add your first child', 'Pair a child’s phone to see places, alerts, screen time and controls.', { art: 'anchor', action: btn('Add a child', 'data-a="addkid"') })}</div>`;
  else if (!k.device) body = noPhone(k);
  else body = { map: mapView, alerts: alertsView, controls: controlsView, activity: activityView }[S.tab](k);
  view.className = 'hf-view' + (enter ? ' in' : '');
  view.innerHTML = tabHeader(k) + body;
  if (!enter) window.scrollTo(0, keep);
  const n = recentAlerts();
  $('#tabs').innerHTML = `<div class="hb-tabs hb-tabs-dock">${TABS.map(([t, label]) => `<button class="hb-tab" data-a="tab" data-v="${t}" ${S.tab === t ? 'aria-current="page"' : ''}>${icon(t, 22, S.tab === t ? 2.3 : 1.8)}<span>${label}</span>${t === 'alerts' && n ? `<i class="hb-badge" style="font-style:normal">${n}</i>` : ''}</button>`).join('')}</div>`;
}

/* ───────────── events ───────────── */
async function writeControls(change, done) {
  if (!controls) return;
  const okay = await controls.write(change);
  if (okay && done) toast(done);
  load('requests', { force: true });
}
const onClick = async ev => {
  const t = ev.target.closest('[data-a]'); if (!t || !$('#shell').contains(t) && !$('#mod').contains(t)) return;
  const a = t.dataset.a, v = t.dataset.v, k = kid();
  try {
    switch (a) {
      case 'x': shut(); return;
      case 'signout': await api.signOut(); cb.onSignOut && cb.onSignOut(); return;
      case 'tab': if (S.tab === v) return; S.tab = v; if (v === 'map') S.mapTitle = greeting(S.user.name); render(true); loadTab(); return;
      case 'kid': S.k = t.dataset.id; S.timelineOpen = false; syncControls(); render(true); loadTab(); return;
      case 'family': api.chooseFamily(t.dataset.id); S.familyId = t.dataset.id; S.family = null; S.k = null; cache.clear(); syncControls(); render(true); await loadFamily(); loadTab(true); return;
      case 'refresh': if (v === 'controls' && controls) controls.load(); else load(v, { force: true }); return;
      case 'day': S.day = v; S.timelineOpen = false; render(); load('activity'); return;
      case 'timeline': S.timelineOpen = !S.timelineOpen; render(); return;
      case 'addkid': open(`<h2>Add a child</h2><p class="hf-muted">You’ll get a code to pair their phone.</p>` + form('addchild', fld('name', 'Name', 'text', 'off'), 'Continue')); return;
      case 'code': case 'newcode': { const c = S.family.children.find(x => x.id === t.dataset.id); busy(t, true); try { pairSheet(c, await api.pairingCode(c.id)); } finally { busy(t, false); } return; }
      case 'donepair': shut(); loadFamily(); return;
      case 'invite': { busy(t, true); try { inviteSheet(await api.createInvite(S.familyId)); } finally { busy(t, false); } return; }
      case 'copy': try { await navigator.clipboard.writeText(v); toast('Code copied'); } catch { toast('Couldn’t copy', { tone: 'info', detail: 'Select the code and copy it instead.' }); } return;
      case 'checkin': { busy(t, true); try { await api.requestCheckIn(k.device.id); toast(`Check-in request sent to ${k.name}`); } finally { busy(t, false); } load('checkins', { force: true }); return; }
      case 'alert': { const u = got('alerts'), x = u && u.reply ? toAlertsView([u.reply]).alerts.find(y => y.id === t.dataset.id) : null; if (x) open(renderAlertDetail(x)); return; }
      case 'pause': writeControls(withPauseNow, `${k.name}’s phone is paused`); return;
      case 'resume': writeControls(withResume, `${k.name}’s phone resumed`); return;
      case 'extra': writeControls(c => withExtraTime(c, localDate()), `15 more minutes for ${k.name} today`); return;
      case 'endgrant': writeControls(c => withoutGrant(c, v)); return;
      case 'bed-day': writeControls(c => { const bed = c.bedtime || { days: [] }; const days = bed.days.includes(v) ? bed.days.filter(d => d !== v) : WEEKDAYS.filter(d => d === v || bed.days.includes(d)); return days.length ? withBedtime(c, { days }) : c; }); return;
      case 'req': {
        const reqs = (got('requests') || {}).reply, r = reqs && reqs.requests.find(x => x.requestId === t.dataset.rid); if (!r) return;
        const approve = !!t.dataset.ok;
        busy(t, true);
        try {
          // Yes = a 15-minute grant for that app, written to the phone's controls before the answer is recorded.
          if (approve && !(controls && await controls.write(c => withGrant(c, r.packageName, Date.now() + GRANT_MS, Date.now())))) { toast('Harbor couldn’t give the extra time', { tone: 'review', detail: 'Check your connection and try again.' }); return; }
          try { await api.decideRequest(r.requestId, approve); toast(approve ? `${k.name} can use ${r.appLabel} for 15 minutes` : 'Request declined'); }
          catch (x) { if (x && x.code === 'stale') toast('That request was already answered or has expired', { tone: 'info' }); else throw x; }
        } finally { busy(t, false); load('requests', { force: true }); }
        return;
      }
      default: return;
    }
  } catch (x) { if (!handle(x)) toast(failText(x), { tone: 'review' }); }
};
const onChange = ev => {
  const c = ev.target.dataset.c; if (!c || !$('#shell').contains(ev.target)) return;
  const val = ev.target.type === 'checkbox' ? ev.target.checked : ev.target.value;
  if (c === 'daily') writeControls(x => withDailyLimit(x, val === 'off' ? null : Number(val)));
  if (c === 'bed') writeControls(x => withBedtime(x, { enabled: val }), val ? 'Bedtime on' : 'Bedtime off');
  if (c === 'bed-start') writeControls(x => withBedtime(x, { start: val }));
  if (c === 'bed-end') writeControls(x => withBedtime(x, { end: val }));
  if (c === 'app') {
    const pkg = ev.target.dataset.v;
    writeControls(x => val.startsWith('limit:') ? withAppRule(x, pkg, 'limited', Number(val.slice(6))) : withAppRule(x, pkg, val));
  }
};
const onSubmit = async ev => {
  const f = ev.target.closest('#mod form[data-form]'); if (!f || f.dataset.form !== 'addchild') return;
  ev.preventDefault();
  const d = Object.fromEntries(new FormData(f)), err = f.querySelector('.hb-err'), b = f.querySelector('[type=submit]');
  if (!(d.name || '').trim()) { err.textContent = 'Enter your child’s name.'; err.hidden = false; return; }
  busy(b, true);
  try {
    const id = await api.addChild(S.familyId, d.name);
    await loadFamily(); S.k = id || S.k; syncControls(); render();
    const c = S.family.children.find(x => x.id === id); if (c) pairSheet(c, await api.pairingCode(c.id)); else shut();
  } catch (x) { if (!handle(x)) { err.textContent = failText(x); err.hidden = false; } }
  finally { busy(b, false); }
};
function tick() {
  if (document.visibilityState !== 'visible' || !$('#mod').hidden) return;
  loadTab(true);
}
const onVisible = () => { if (document.visibilityState === 'visible') { tick(); loadFamily(); } };

/* ───────────── lifecycle ───────────── */
export async function mountParent(session, callbacks) {
  cb = callbacks;
  Object.assign(S, { user: session.user, families: session.families, familyId: session.familyId, family: null, k: null, tab: 'map', day: localDate(), timelineOpen: false, mapTitle: greeting(session.user.name) });
  document.addEventListener('click', onClick); document.addEventListener('change', onChange); document.addEventListener('submit', onSubmit);
  document.addEventListener('visibilitychange', onVisible);
  $('#shell').hidden = false; render(true);
  await loadFamily(); loadTab(true);
  // Controls and requests refresh every 15 seconds while open, so a change from the other parent shows up.
  timer = setInterval(tick, 15000);
}
export function unmountParent() {
  document.removeEventListener('click', onClick); document.removeEventListener('change', onChange); document.removeEventListener('submit', onSubmit); document.removeEventListener('visibilitychange', onVisible);
  clearInterval(timer); seq++; if (controls) controls.dispose(); controls = null; cache.clear();
  Object.assign(S, { user: null, families: [], familyId: null, family: null, k: null, tab: 'map' });
  shut(); $('#shell').hidden = true;
  // Wipe the rendered family data so nothing lingers in the page after sign-out (shared phones).
  for (const id of ['#view', '#kids', '#tabs', '#brand']) $(id).innerHTML = '';
}
/** Android back button: returns true if the app handled it. */
export function parentBack() {
  if (!$('#mod').hidden) { shut(); return true; }
  if (S.tab !== 'map') { S.tab = 'map'; render(true); loadTab(); return true; }
  return false;
}
