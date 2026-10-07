// PARENT experience: map, alerts, controls, activity, family. Mounted only for role === 'parent'.
import { api, ApiError } from './api.js';
import { mountMap } from './map.js';
import { $, e, fmt, clock, when, ago, LOGO, toast, open, shut, sw, fld, form, emptyCard } from './common.js';

const lim = k => (k.limitMin || 0) + (k.bonusMin || 0);
const SEV = { h: 'High', m: 'Medium', l: 'Low' };
const ico = { map: '<path d="M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z"/><circle cx="12" cy="9.5" r="2.5"/>', alerts: '<path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>', controls: '<rect x="6" y="3" width="12" height="18" rx="2.5"/><path d="M12 8v4l2.5 1.5"/>', activity: '<path d="M3 12h4l2.5-6 5 12 2.5-6h4"/>', family: '<circle cx="9" cy="8" r="3"/><circle cx="17" cy="9.5" r="2.3"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0M14.5 19a4 4 0 0 1 6.5-3.1"/>' };
const TABS = { map: 'Map', alerts: 'Alerts', controls: 'Controls', activity: 'Activity', family: 'Family' };

const S = { user: null, children: [], alerts: [], settings: {}, k: null, tab: 'map', af: 'open', act: 'overview' };
let cb = {};
const kid = () => S.children.find(c => c.id === S.k) || null;

/* ───────────── data loading ───────────── */
function handle(x) { if (x && x.code === 'unauthenticated') { cb.onExpired && cb.onExpired(); return true; } return false; }
async function loadApp() {
  try { [S.children, S.alerts, S.settings] = await Promise.all([api.listChildren(), api.listAlerts(), api.getSettings()]); }
  catch (x) { if (!handle(x)) toast('Couldn\u2019t load your family. Check your connection.'); return; }
  if (!S.children.some(c => c.id === S.k)) S.k = S.children[0] ? S.children[0].id : null;
  S.tab = S.children.length ? 'map' : 'family';
  $('#shell').hidden = false; render(1);
}
async function refresh() { try { S.children = await api.listChildren(); S.alerts = await api.listAlerts(); } catch (x) { handle(x); } }
function patch(k, p) { Object.assign(k, p); api.updateChild(k.id, p).catch(async x => { if (!handle(x)) { toast('Couldn\u2019t save that change.'); await refresh(); render(); } }); }

/* ───────────── views ───────────── */
function noKids() {
  return `<div class="empty"><h2>Add your first child</h2><p class="sm" style="margin:6px 0 16px">Pair a child\u2019s phone to see location, set limits and get safety alerts.</p><button class="btn p" data-a="addkid">Add a child</button></div>`;
}

function mapView() {
  const k = kid(), L = k.location, D = k.device, low = D && D.battery != null && D.battery <= 20;
  return `<div class="head"><h1>Location</h1></div><div class="mapbox" id="map" role="region" aria-label="Map"></div>
<div class="lh"><span class="av" style="background:${e(k.color)}">${e(k.name[0])}</span><div><h2>${L ? `${e(k.name)} is at ${e(L.place || 'an unnamed place')}` : `No location for ${e(k.name)} yet`}</h2><div class="muted sm">${L ? `Updated ${ago(L.updatedAt)}` : D ? 'Waiting for the first location update' : 'Pair their device to get started'}</div></div></div>
${D ? `<div class="stats">${D.battery != null ? `<span class="${low ? 'low' : ''}"><span class="batt"><i style="width:${+D.battery}%"></i></span>${+D.battery}%${low ? ' · low' : ''}</span>` : ''}<span>${e(D.model)}</span>${D.lastSeenAt ? `<span>Last seen ${ago(D.lastSeenAt)}</span>` : ''}</div>` : '<div class="stats"></div>'}
<div class="two"><button class="btn p" data-a="cmd" data-v="checkin" ${D ? '' : 'disabled'}>Ask to check in</button><button class="btn o" data-a="cmd" data-v="ring" ${D ? '' : 'disabled'}>Ring phone</button></div>
${k.timeline.length ? `<div class="lab">Today</div><ul class="tl">${k.timeline.map(x => `<li class="${x.driving ? 'dr' : ''}"><time>${clock(x.time)}</time><div>${e(x.text)}</div></li>`).join('')}</ul>` : ''}`;
}

function alertsView() {
  const k = kid(), l = S.alerts.filter(a => a.childId === k.id && (S.af === 'all' || !a.reviewed)).sort((a, b) => a.reviewed - b.reviewed || 'hml'.indexOf(a.severity) - 'hml'.indexOf(b.severity) || new Date(b.time) - new Date(a.time));
  return `<div class="head"><h1>Alerts</h1><div class="seg">${[['open', 'Needs review'], ['all', 'All']].map(([v, t]) => `<button data-a="af" data-v="${v}" aria-selected="${S.af === v}">${t}</button>`).join('')}</div></div>` +
    (l.length ? l.map(a => `<button class="al ${a.reviewed ? 'done' : ''}" data-a="alert" data-id="${e(a.id)}"><span class="sev ${e(a.severity)}"></span><span><span class="at"><span>${e(a.app)} · ${when(a.time)}</span><span class="tag ${e(a.severity)}">${a.reviewed ? 'Reviewed' : SEV[a.severity] || ''}</span></span><b style="display:block;margin:2px 0">${e(a.category)}</b><span class="ab">${a.lines.map(x => e(x.text)).join(' · ')}</span></span></button>`).join('')
      : emptyCard('All caught up', `No alerts need review for ${e(k.name)}. Harbor will tell you if something comes up.`));
}

function appStatus(k, a) {
  if (!a.allowed) return ['Blocked', 'blk'];
  if (a.limitMin == null) return [`${a.usedMin ? a.usedMin + 'm today · ' : ''}Unlimited, works after time\u2019s up`, ''];
  if (k.usedMin >= lim(k)) return [`${a.usedMin}m today · locked, time\u2019s up`, 'blk'];
  if (a.usedMin >= a.limitMin) return [`${a.usedMin}m today · limit reached`, 'blk'];
  return [`${a.usedMin}m of ${a.limitMin}m today`, ''];
}
function controlsView() {
  const k = kid(), L = lim(k), over = k.usedMin >= L;
  return `<div class="head"><h1>Screen time</h1></div>
${k.paused ? `<div class="ban"><span>${e(k.name)}\u2019s phone is paused</span><button class="btn s d" data-a="pause">Resume</button></div>` : ''}
<div class="panel"><div class="use"><div><div class="muted sm">Used today</div><b class="num">${fmt(k.usedMin)}</b></div><div style="text-align:right"><div class="muted sm">Daily limit</div><div class="step"><button data-a="lim" data-v="-15" aria-label="Decrease limit">−</button><output class="num">${fmt(L)}</output><button data-a="lim" data-v="15" aria-label="Increase limit">+</button></div></div></div>
<div class="bar ${over ? 'o' : ''}"><i style="width:${Math.min(100, k.usedMin / L * 100)}%"></i></div><div class="sm ${over ? '' : 'muted'}" style="${over ? 'color:var(--warni);font-weight:600' : ''}">${over ? 'Time\u2019s up' : fmt(L - k.usedMin) + ' left today'}</div></div>
${over ? `<div class="ban" style="display:block"><b>Time\u2019s up. ${e(k.name)}\u2019s apps are locked.</b><div class="sm muted" style="font-weight:400">Only unlimited apps work until midnight. Calls to parents and SOS always work.</div></div>` : ''}
<div class="two"><button class="btn ${k.paused ? 'o' : 'd'}" data-a="pause">${k.paused ? 'Resume phone' : 'Pause phone now'}</button><button class="btn o" data-a="bonus">Give 15 more min</button></div>
${k.requests.length ? `<div class="lab">Requests</div><div class="panel">${k.requests.map(r => `<div class="row" style="display:block"><div class="rt">${e(r.title)}</div><div class="rs">${e(r.detail)}</div><div style="display:flex;gap:8px;margin-top:8px"><button class="btn s p" data-a="req" data-rid="${e(r.id)}" data-ok="1">Approve</button><button class="btn s o" data-a="req" data-rid="${e(r.id)}">Deny</button></div></div>`).join('')}</div>` : ''}
<div class="lab">Schedules</div><div class="panel"><div class="row"><div><div class="rt">Bedtime</div><div class="rs">Phone locks overnight</div></div>${sw('bed', k.bedtime, 'Bedtime')}</div><div class="row"><div><div class="rt">School time</div><div class="rs">School apps only during class hours</div></div>${sw('school', k.schoolTime, 'School time')}</div></div>
<div class="lab">App limits</div>
${k.apps.length ? `<p class="rs" style="margin:-4px 0 8px">Hard stops. Kids can\u2019t go over; they can only ask you for more time.</p><div class="panel">${k.apps.map((a, i) => { const s = appStatus(k, a); return `<div class="row"><div class="rm"><span class="ic" style="background:${e(a.color || '#56605c')}">${e(a.name[0])}</span><div><div class="rt">${e(a.name)}</div><div class="rs ${s[1]}">${s[0]}</div></div></div><button class="btn s o" data-a="app" data-i="${i}">${!a.allowed ? 'Blocked' : a.limitMin == null ? 'Unlimited' : a.limitMin + 'm/day'}</button></div>`; }).join('')}</div>` : emptyCard('No apps yet', 'Apps appear here after the device syncs.')}`;
}

function activityView() {
  const k = kid(), T = [['overview', 'Overview'], ['calls', 'Calls'], ['msgs', 'Messages'], ['web', 'Web']]; let b = '';
  if (S.act === 'overview') {
    const L = lim(k), mx = Math.max(...k.week, L, 1), avg = Math.round(k.week.reduce((a, c) => a + c, 0) / 7), by = {};
    k.apps.forEach(a => { by[a.category || 'Other'] = (by[a.category || 'Other'] || 0) + a.usedMin; });
    const unk = k.calls.filter(c => !c.isContact).length + k.threads.filter(t => !t.isContact).length, tot = Object.values(by).reduce((a, c) => a + c, 0);
    const days = [...Array(7)].map((_, i) => 'SMTWTFS'[new Date(Date.now() - (6 - i) * 864e5).getDay()]);
    b = `<div class="panel"><div class="use"><div><div class="muted sm">Daily average · 7 days</div><b class="num">${fmt(avg)}</b></div><div class="sm muted" style="text-align:right">Limit<br><b class="num">${fmt(L)}</b></div></div>
<div class="chart" role="img" aria-label="Screen time, last 7 days">${k.week.map((m, i) => `<div class="cc"><i class="${m > L ? 'o' : ''}" style="height:${Math.round(m / mx * 104)}px"></i>${days[i]}</div>`).join('')}<div class="lim" style="bottom:${20 + Math.round(L / mx * 104)}px"></div></div></div>
<div class="kpi"><div><b class="num">${+k.pickups}</b><span>Phone pickups</span></div><div><b class="num">${+k.notifications}</b><span>Notifications</span></div><div><b>${k.firstUse ? clock(k.firstUse) : '–'}</b><span>First use today</span></div><div><b style="font-size:15px">${k.afterBedtime == null ? '–' : e(k.afterBedtime)}</b><span>After bedtime</span></div></div>
${unk ? `<button class="note w" data-a="act" data-v="msgs"><b>${unk} new contact${unk > 1 ? 's' : ''}</b> not in ${e(k.name)}\u2019s contacts. Review calls and messages</button>` : ''}
<div class="lab">Time by category</div>${tot ? `<div class="panel">${Object.entries(by).filter(x => x[1]).sort((a, c) => c[1] - a[1]).map(([c, m]) => `<div class="cat"><span>${e(c)}</span><div class="bar"><i style="width:${m / tot * 100}%"></i></div><b class="num">${m}m</b></div>`).join('')}</div>` : emptyCard('No usage yet', 'Usage shows up once the device reports.')}`;
  }
  if (S.act === 'calls') b = `<div class="panel"><div class="row"><div><div class="rt">Block unknown callers</div><div class="rs">Only contacts you approve can call</div></div>${sw('unk', k.blockUnknownCallers, 'Block unknown callers')}</div></div><div class="lab">Recent calls</div>` +
    (k.calls.length ? `<div class="panel">${k.calls.map((c, i) => { const id = c.number || c.name, blocked = k.blockedContacts.includes(id); return `<div class="row"><div><div class="rt">${e(c.name || c.number)} ${c.isContact ? '' : '<span class="tag m">Not a contact</span>'}</div><div class="rs">${{ in: 'Incoming', out: 'Outgoing', missed: 'Missed' }[c.direction] || ''} · ${when(c.time)}${c.durationSec ? ' · ' + Math.floor(c.durationSec / 60) + 'm ' + String(c.durationSec % 60).padStart(2, '0') + 's' : ''}</div></div>${c.isContact ? '' : blocked ? '<span class="blk">Blocked</span>' : `<button class="btn s o" data-a="bcall" data-i="${i}">Block</button>`}</div>`; }).join('')}</div>` : emptyCard('No calls yet', 'Recent calls will appear here.'));
  if (S.act === 'msgs') b = `<p class="note">Harbor surfaces flagged parts of conversations, not everything.</p>` +
    (k.threads.length ? `<div class="panel">${k.threads.map((t, i) => `<button class="row" data-a="th" data-i="${i}"><div class="rm"><span class="av" style="background:${t.flagged ? '#b4382c' : '#56605c'}">${e((t.with.replace(/[^A-Za-z]/g, '')[0] || '#').toUpperCase())}</span><div><div class="rt">${e(t.with)} ${t.flagged ? '<span class="tag h">Flagged</span>' : t.isContact ? '' : '<span class="tag m">Not a contact</span>'}</div><div class="rs">${e(t.app)} · ${+t.count} today · ${clock(t.time)}</div></div></div><span class="muted">›</span></button>`).join('')}</div>` : emptyCard('No messages yet', 'Flagged conversations will appear here.'));
  if (S.act === 'web') b = `<div class="panel"><div class="row"><div><div class="rt">SafeSearch</div><div class="rs">Google, Bing and YouTube restricted mode</div></div>${sw('safe', k.safeSearch, 'SafeSearch')}</div><div class="row"><div><div class="rt">Blocked today</div><div class="rs">Attempts to open filtered sites</div></div><b class="num" style="font-size:20px">${k.history.filter(h => h.blockedCategory).length}</b></div></div>
<div class="lab">Block these categories</div><div class="chips">${Object.entries(k.filters).map(([c, v]) => `<button class="chip" aria-pressed="${!!v}" data-a="cat" data-v="${e(c)}">${e(c)}</button>`).join('')}</div>
<div class="lab">Recent history</div>${k.history.length ? `<div class="panel">${k.history.map((h, i) => { const blocked = h.blockedCategory || k.blockedSites.includes(h.url); return `<div class="row"><div style="min-width:0"><div class="rt" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${e(h.url)}</div><div class="rs">${clock(h.time)}${blocked ? ` · <span class="blk">Blocked${h.blockedCategory ? ' · ' + e(h.blockedCategory) : ''}</span>` : ''}</div></div>${blocked ? '' : `<button class="btn s o" data-a="bsite" data-i="${i}">Block</button>`}</div>`; }).join('')}</div>` : emptyCard('No history yet', 'Visited sites will appear here.')}`;
  return `<div class="head"><h1>Activity</h1></div><div class="seg full">${T.map(([v, t]) => `<button data-a="act" data-v="${v}" aria-selected="${S.act === v}">${t}</button>`).join('')}</div>${b}`;
}

function familyView() {
  const N = [['highPriority', 'High-priority message alerts'], ['arrivals', 'Arrivals and departures'], ['lowBattery', 'Low battery (under 20%)'], ['weekly', 'Weekly summary email']];
  return `<div class="head"><h1>Family</h1></div>
<p class="note"><b>Kids can see they\u2019re protected.</b> Harbor shows a visible icon and notification on each child\u2019s phone. Being open about it builds trust.</p>
<div class="lab">Children</div><div class="panel">${S.children.map(k => `<div class="row"><div class="rm"><span class="av" style="background:${e(k.color)}">${e(k.name[0])}</span><div><div class="rt">${e(k.name)}${k.age ? ', ' + +k.age : ''}</div><div class="rs">${k.device ? e(k.device.model) + ' · connected' : 'Waiting for device'}</div></div></div>${k.device ? '<span class="tag" style="color:var(--acc)">Active</span>' : `<button class="btn s o" data-a="code" data-id="${e(k.id)}">Pairing code</button>`}</div>`).join('')}<div class="row"><button class="btn o" style="width:100%" data-a="addkid">+ Add a child</button></div></div>
<div class="lab">Parents</div><div class="panel"><div class="row"><div class="rm"><span class="av" style="background:#163a3a">${e((S.user.name || '?')[0])}</span><div><div class="rt">${e(S.user.name)}</div><div class="rs">${e(S.user.email)} · You</div></div></div></div><div class="row"><button class="btn o" style="width:100%" data-a="invite">+ Invite another parent</button></div></div>
<div class="lab">Notify me about</div><div class="panel">${N.map(([key, l]) => `<div class="row"><div class="rt">${l}</div>${sw('n:' + key, S.settings[key], l)}</div>`).join('')}</div>
<div class="lab">Account</div><div class="panel"><div class="row"><button class="btn g" style="width:100%" data-a="signout">Sign out</button></div></div>`;
}

const VIEWS = { map: mapView, alerts: alertsView, controls: controlsView, activity: activityView, family: familyView };

function pairModal(c) {
  open(`<h2 style="font-size:19px">Pair ${e(c.name)}\u2019s device</h2><ol class="tips" style="margin-top:10px"><li>Install Harbor Family on ${e(c.name)}\u2019s Android phone.</li><li>Open it, choose <b>A child</b> and enter this code. It works once and expires in 10 minutes:</li></ol><div class="code num" aria-label="Pairing code">${e(c.pairingCode || '\u2014')}</div><div class="ma"><button class="btn p" data-a="x">Done</button><button class="btn o" data-a="newcode" data-id="${e(c.id)}">Get a new code</button></div>`);
}

function render(en) {
  $('#brand').innerHTML = LOGO + 'Harbor Family';
  $('#kids').innerHTML = S.children.map(k => `<button class="kid" aria-pressed="${k.id === S.k}" data-a="kid" data-id="${e(k.id)}"><span class="av" style="background:${e(k.color)}">${e(k.name[0])}</span>${e(k.name)}</button>`).join('') + `<button class="kid" data-a="addkid" style="padding-left:13px">+ Add child</button>`;
  const v = $('#view'), keep = v.scrollTop; v.className = en ? 'in' : '';
  v.innerHTML = S.tab === 'family' ? familyView() : kid() ? VIEWS[S.tab]() : noKids();
  v.scrollTop = keep;
  if (S.tab === 'map' && kid()) mountMap($('#map'), kid());
  const n = S.alerts.filter(a => !a.reviewed).length;
  $('#tabs').innerHTML = Object.keys(TABS).map(t => `<button class="tab" data-a="tab" data-v="${t}" ${S.tab === t ? 'aria-current="page"' : ''}><svg viewBox="0 0 24 24" aria-hidden="true">${ico[t]}</svg><span>${TABS[t]}</span>${t === 'alerts' && n ? `<i class="badge" style="font-style:normal">${n}</i>` : ''}</button>`).join('');
}


/* ───────────── events ───────────── */
const onClick = async ev => {
  const t = ev.target.closest('[data-a]'); if (!t) return;
  const a = t.dataset.a, v = t.dataset.v, i = +t.dataset.i, k = kid();
  try {
    switch (a) {
      case 'x': shut(); return;
      case 'signout': await api.signOut(); cb.onSignOut && cb.onSignOut(); return;
      case 'tab': S.tab = v; break;
      case 'kid': S.k = t.dataset.id; break;
      case 'af': S.af = v; break;
      case 'act': S.act = v; break;
      case 'addkid': open(`<h2 style="font-size:19px">Add a child</h2><p class="muted sm">You\u2019ll get a code to pair their phone.</p>` + form('addchild', `<div style="margin-top:14px">${fld('name', 'Name', 'text', 'off')}</div>`, 'Continue')); return;
      case 'code': pairModal(S.children.find(c => c.id === t.dataset.id)); return;
      case 'newcode': { const c = await api.regeneratePairingCode(t.dataset.id); Object.assign(S.children.find(x => x.id === c.id), c); pairModal(c); return; }
      case 'invite': open(`<h2 style="font-size:19px">Invite another parent</h2><p class="muted sm">They\u2019ll get an email to join your family.</p>` + form('invite', `<div style="margin-top:14px">${fld('email', 'Email', 'email', 'off', '', 'inputmode="email"')}</div>`, 'Send invite')); return;
      case 'cmd': await api.sendCommand(k.id, v); toast(v === 'ring' ? `Ringing ${k.name}\u2019s phone\u2026` : `Check-in request sent to ${k.name}`); return;
      case 'alert': { const x = S.alerts.find(y => y.id === t.dataset.id); open(`<span class="tag ${e(x.severity)}">${SEV[x.severity] || ''} priority</span><h2 style="margin-top:8px;font-size:19px">${e(x.category)}</h2><p class="muted sm">${e(x.app)} · ${when(x.time)} · ${e(x.from)}</p><div class="msg">${x.lines.map(l => `<p class="${l.flagged ? 'flag' : ''}"><span class="fr">${e(l.who)}</span><br>${e(l.text)}</p>`).join('')}</div><p class="sm muted">Harbor only shows the flagged part of the conversation.</p>${x.tips.length ? `<div class="lab">Suggested next steps</div><ul class="tips">${x.tips.map(q => `<li>${e(q)}</li>`).join('')}</ul>` : ''}<div class="ma"><button class="btn p" data-a="rev" data-id="${e(x.id)}">${x.reviewed ? 'Mark as needs review' : 'Mark as reviewed'}</button><button class="btn o" data-a="x">Close</button></div>`); return; }
      case 'rev': { const x = S.alerts.find(y => y.id === t.dataset.id); x.reviewed = !x.reviewed; await api.setAlertReviewed(x.id, x.reviewed); shut(); toast(x.reviewed ? 'Marked as reviewed' : 'Moved back to review'); break; }
      case 'lim': patch(k, { limitMin: Math.max(15, Math.min(600, k.limitMin + +v)) }); break;
      case 'pause': patch(k, { paused: !k.paused }); toast(k.paused ? `${k.name}\u2019s phone is paused` : `${k.name}\u2019s phone resumed`); break;
      case 'bonus': Object.assign(k, await api.grantTime(k.id, 15)); toast(`${k.name} got 15 extra minutes today`); break;
      case 'req': { await api.respondToRequest(k.id, t.dataset.rid, !!t.dataset.ok); await refresh(); toast(t.dataset.ok ? 'Approved' : 'Denied'); break; }
      case 'app': { const x = k.apps[i], cur = !x.allowed ? 'b' : x.limitMin == null ? 'u' : String(x.limitMin);
        open(`<h2 style="font-size:19px">${e(x.name)} for ${e(k.name)}</h2><p class="muted sm">${x.usedMin}m used today. Limits are hard stops.</p><div class="panel" style="margin-top:12px" role="radiogroup">${[['u', 'Unlimited', 'Keeps working after daily time is up'], ...[15, 30, 45, 60, 90, 120].map(m => [String(m), m + ' min a day', 'Locks when used up']), ['b', 'Blocked', 'Can\u2019t be opened']].map(([o, l, d]) => `<button class="row" role="radio" aria-checked="${cur === o}" data-a="setlim" data-i="${i}" data-v="${o}"><div><div class="rt">${l}</div><div class="rs">${d}</div></div><span style="width:22px;height:22px;border-radius:50%;flex:none;border:${cur === o ? '7px solid var(--acc)' : '2px solid var(--line)'}"></span></button>`).join('')}</div><button class="btn o" style="width:100%" data-a="x">Cancel</button>`); return; }
      case 'setlim': { const apps = k.apps.map((x, n) => n === i ? { ...x, allowed: v !== 'b', limitMin: v === 'u' || v === 'b' ? null : +v } : x); patch(k, { apps }); shut(); toast(`${k.apps[i].name}: ${v === 'b' ? 'blocked' : v === 'u' ? 'unlimited' : v + ' min a day'}`); break; }
      case 'bcall': { const c = k.calls[i]; patch(k, { blockedContacts: [...k.blockedContacts, c.number || c.name] }); toast(`Blocked ${c.name || c.number}`); break; }
      case 'bsite': { const h = k.history[i]; patch(k, { blockedSites: [...k.blockedSites, h.url] }); toast(`Blocked ${h.url}`); break; }
      case 'cat': patch(k, { filters: { ...k.filters, [v]: !k.filters[v] } }); toast(`${v} ${k.filters[v] ? 'blocked' : 'allowed'}`); break;
      case 'th': { const x = k.threads[i]; open(`<h2 style="font-size:19px">${e(x.with)}</h2><p class="muted sm">${e(x.app)} · ${+x.count} messages today${x.isContact ? '' : ' · not in contacts'}</p><div class="msg">${x.messages.map(m => `<p class="${m.mine ? 'me' : ''}"><span class="fr">${e(m.from)}</span><br>${e(m.text)}</p>`).join('')}</div><div class="ma">${x.isContact || k.blockedContacts.includes(x.with) ? '' : `<button class="btn d" data-a="bthread" data-i="${i}">Block this contact</button>`}<button class="btn o" data-a="x">Close</button></div>`); return; }
      case 'bthread': { const x = k.threads[i]; patch(k, { blockedContacts: [...k.blockedContacts, x.with] }); shut(); toast(`${x.with} blocked`); break; }
      default: return;
    }
  } catch (x) { if (!handle(x)) toast(x instanceof ApiError ? x.message : 'Something went wrong. Please try again.'); return; }
  const en = ['tab', 'kid', 'af', 'act'].includes(a);
  a === 'tab' ? render(en) : setTimeout(() => render(en), 130);
};

const onChange = ev => {
  const c = ev.target.dataset.c; if (!c) return; const on = ev.target.checked, k = kid();
  if (c.startsWith('n:')) { const key = c.slice(2); S.settings[key] = on; api.updateSettings({ [key]: on }).catch(x => handle(x)); toast('Notification setting saved'); return; }
  const prop = { bed: 'bedtime', school: 'schoolTime', safe: 'safeSearch', unk: 'blockUnknownCallers' }[c];
  if (prop && k) { patch(k, { [prop]: on }); toast(`${{ bedtime: 'Bedtime', schoolTime: 'School time', safeSearch: 'SafeSearch', blockUnknownCallers: 'Block unknown callers' }[prop]} ${on ? 'on' : 'off'}`); }
  navigator.vibrate && navigator.vibrate(10);
};

const onSubmit = async ev => {
  const f = ev.target.closest('form[data-form]'); if (!f || !['addchild', 'invite'].includes(f.dataset.form)) return;
  ev.preventDefault();
  const name = f.dataset.form, d = Object.fromEntries(new FormData(f)), err = f.querySelector('.err'), btn = f.querySelector('[type=submit]');
  const fail = t => { err.textContent = t; err.hidden = false; }; err.hidden = true;
  if (name === 'addchild') { if (!(d.name || '').trim()) return fail('Enter your child\u2019s name.'); }
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test((d.email || '').trim())) return fail('Enter a valid email address.');
  btn.classList.add('load');
  try {
    if (name === 'addchild') { const c = await api.addChild({ name: d.name }); S.children.push(c); S.k = c.id; S.tab = 'family'; pairModal(c); render(1); }
    else { await api.inviteParent(d.email.trim()); shut(); toast('Invitation sent'); }
  } catch (x) { if (!handle(x)) fail(x instanceof ApiError ? x.message : 'Something went wrong. Please try again.'); }
  finally { btn.classList.remove('load'); }
};

/* ───────────── lifecycle ───────────── */
export async function mountParent(session, callbacks) {
  cb = callbacks; S.user = session.user;
  document.addEventListener('click', onClick); document.addEventListener('change', onChange); document.addEventListener('submit', onSubmit);
  await loadApp();
}
export function unmountParent() {
  document.removeEventListener('click', onClick); document.removeEventListener('change', onChange); document.removeEventListener('submit', onSubmit);
  Object.assign(S, { user: null, children: [], alerts: [], settings: {}, k: null, tab: 'map', af: 'open', act: 'overview' });
  shut(); $('#shell').hidden = true;
  // Wipe the rendered family data so nothing lingers in the page after sign-out (shared phones).
  for (const id of ['#view', '#kids', '#tabs', '#brand']) $(id).innerHTML = '';
}
/** Android back button: returns true if the app handled it. */
export function parentBack() {
  if (!$('#mod').hidden) { shut(); return true; }
  if (S.tab !== 'map' && S.children.length) { S.tab = 'map'; render(1); return true; }
  return false;
}
