// A day of activity from a child's phone (ActivityReadReplyV1), as the Android parent app shows it: screen time
// first, then the detailed activity with the newest five timeline entries and Show more. Pure: no DOM, no network.
// Only app names, package names and times exist here, never screen content, window titles, URLs or message text.

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const pad = n => String(n).padStart(2, '0');
export const TIMELINE_PREVIEW = 5;
export const REMOVAL_LABEL = 'Remove an app';

/** This device's local date, YYYY-MM-DD. */
export function localDate(d = new Date()) { return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; }
/** The day's window in UTC instants: local midnight to the next midnight (or now, for today). */
export function activityWindow(day, now = new Date()) {
  const [y, m, d] = day.split('-').map(Number);
  const from = new Date(y, m - 1, d), next = new Date(y, m - 1, d + 1);
  const to = next > now ? now : next;
  return { from: from.toISOString(), to: to.toISOString() };
}
export function formatMinutes(ms) {
  const m = Math.max(0, Math.floor(ms / 60000));
  return m >= 60 ? `${Math.floor(m / 60)}h ${pad(m % 60)}m` : `${m} min`;
}
/** One timeline line in plain words, the same as the Android parent app. */
export function activityLine(ev) {
  const app = ev.appLabel || 'An app';
  switch (ev.kind) {
    case 'app_session': return `${app} · ${Math.max(1, Math.floor((Date.parse(ev.endAt) - Date.parse(ev.at)) / 60000))} min`;
    case 'blocked_open':
      if (ev.appLabel === REMOVAL_LABEL) return 'Tried to remove an app (needs your OK)';
      return { paused: `Tried to open ${app} while the phone was paused`, bedtime: `Tried to open ${app} during bedtime`, limit_reached: `Tried to open ${app} after its time ran out` }[ev.reason] || `Tried to open ${app} (blocked)`;
    case 'limit_reached': return `${app} daily time limit reached`;
    case 'pause_started': return 'Phone paused';
    case 'pause_ended': return 'Pause ended';
    case 'bedtime_started': return 'Bedtime started';
    case 'bedtime_ended': return 'Bedtime ended';
    case 'protection_off': return 'App controls were turned off on the phone';
    case 'protection_on': return 'App controls were turned back on';
    default: return 'Activity';
  }
}
/** Newest first; rule events (anything but an app session) are marked for review in amber. */
export function activityTimeline(events) {
  return [...events].sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
    .map(ev => ({ at: ev.at, text: activityLine(ev), review: ev.kind !== 'app_session' }));
}
export const timelinePreview = (items, open) => open ? items : items.slice(0, TIMELINE_PREVIEW);
/** Time in each app from the day's sessions, longest first. */
export function timeByApp(events) {
  const m = new Map();
  for (const ev of events) if (ev.kind === 'app_session' && ev.packageName) {
    const cur = m.get(ev.packageName) || { label: ev.appLabel || ev.packageName, ms: 0 };
    cur.ms += Math.max(0, Date.parse(ev.endAt) - Date.parse(ev.at)); m.set(ev.packageName, cur);
  }
  return [...m.entries()].map(([pkg, v]) => ({ pkg, ...v })).filter(x => x.ms > 0).sort((a, b) => b.ms - a.ms);
}
/** The day's figure: the phone's own count for that day, else the timeline's app time. */
export function dayFigure(reply, day) {
  const st = reply && reply.screenTime;
  if (st && st.date === day) return { value: formatMinutes(st.totalMs), label: 'Screen time' };
  const sum = timeByApp(reply && Array.isArray(reply.events) ? reply.events : []).reduce((n, a) => n + a.ms, 0);
  return { value: sum ? formatMinutes(sum) : '—', label: 'Time in apps' };
}

const clock = iso => new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
const dayLabel = (day, today) => {
  if (day === today) return 'Today';
  const [y, m, d] = day.split('-').map(Number), dt = new Date(y, m - 1, d);
  const yd = new Date(); yd.setDate(yd.getDate() - 1);
  return day === localDate(yd) ? 'Yesterday' : dt.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });
};
const shift = (day, n) => { const [y, m, d] = day.split('-').map(Number); return localDate(new Date(y, m - 1, d + n)); };
const empty = (title, body, art) => `<div class="hb-card"><div class="hb-empty" data-art="${art}"><h3>${esc(title)}</h3><p>${esc(body)}</p></div></div>`;

function weekBars(usage) {
  if (!usage || usage.status !== 'available' || !usage.days.length) return '';
  const known = usage.days.filter(d => d.totalMs !== null).map(d => d.totalMs), mx = Math.max(...known, 1);
  return `<div class="hf-chart" role="img" aria-label="Screen time by day">${usage.days.slice(-7).map(d => {
    const [y, m, dd] = d.localDate.split('-').map(Number), wd = new Date(y, m - 1, dd).toLocaleDateString([], { weekday: 'narrow' });
    return d.totalMs === null ? `<div class="cc unk" title="${esc(d.localDate)}: unknown"><i></i>${esc(wd)}</div>`
      : `<div class="cc${d.quality === 'partial' ? ' part' : ''}" title="${esc(d.localDate)}: ${esc(formatMinutes(d.totalMs))}"><i style="height:${Math.round(d.totalMs / mx * 96)}px"></i>${esc(wd)}</div>`;
  }).join('')}</div>`;
}

/** reply: ActivityReadReplyV1 | null; usage: a toUsageView() result or null. */
export function renderActivity({ reply, offline = false, usage = null, day, name = 'your child', open = false, today = localDate() }) {
  const who = esc(name);
  const st = reply && reply.screenTime && reply.screenTime.date === today ? reply.screenTime : null;
  const screen = `<div class="hb-card"><h3>Screen time</h3><div class="hb-use"><div class="hb-big">${st ? esc(formatMinutes(st.totalMs)) : '—'}</div><span class="hb-rs">${st ? 'Today, counted by ' + who + '’s phone' : 'Today’s count hasn’t arrived yet'}</span></div>${weekBars(usage)}<p>Counted like Digital Wellbeing: time an app is on screen. Apps set to Unlimited and the home screen don’t count.</p></div>`;
  const switcher = `<div class="hb-row" style="border:0;padding:4px 0 10px"><button class="hb-btn hb-btn-outline hb-btn-small" data-a="day" data-v="${shift(day, -1)}" aria-label="Previous day" ${shift(day, -1) < shift(today, -29) ? 'disabled' : ''}>‹</button><b>${esc(dayLabel(day, today))}</b><button class="hb-btn hb-btn-outline hb-btn-small" data-a="day" data-v="${shift(day, 1)}" aria-label="Next day" ${day >= today ? 'disabled' : ''}>›</button></div>`;
  let detail;
  if (!reply) detail = `<div class="hb-note hb-note-warn" role="status"><b>Couldn’t load activity.</b> Check your connection and try again.</div>`;
  else if (reply.state === 'none') detail = empty('No activity yet', `${name}’s phone hasn’t reported any activity yet.`, 'buoy');
  else if (reply.state === 'expired') detail = empty('Nothing kept for this day', 'Activity older than 30 days is deleted.', 'calm');
  else {
    const events = Array.isArray(reply.events) ? reply.events : [];
    const fig = dayFigure(reply, day), blocked = events.filter(ev => ev.kind === 'blocked_open').length;
    const apps = timeByApp(events).slice(0, 8), longest = apps.length ? apps[0].ms : 1;
    const items = activityTimeline(events);
    detail = (offline ? '<div class="hb-note hb-note-warn" role="status">Showing what was last loaded. Couldn’t refresh.</div>' : '')
      + (reply.controlsOn === false ? '<div class="hb-ban">App controls are off on the phone</div>' : '')
      + `<div class="hb-kpi"><div class="hb-stat"><b>${esc(fig.value)}</b><span>${esc(fig.label)}</span></div><div class="hb-stat"><b>${blocked}</b><span>Blocked opens</span></div></div>`
      + (apps.length ? `<div class="hb-card"><h3>Time by app</h3>${apps.map(a => `<div style="margin:10px 0"><div class="hb-row" style="border:0;padding:0"><span class="hf-rt">${esc(a.label)}</span><span class="hf-num hf-muted">${esc(formatMinutes(a.ms))}</span></div><div class="hb-bar" style="margin:6px 0 0"><i style="width:${Math.max(2, Math.round(a.ms / longest * 100))}%"></i></div></div>`).join('')}</div>` : '')
      + `<div class="hb-card"><h3>Timeline</h3>${items.length ? `<ul class="hb-tl">${timelinePreview(items, open).map(i => `<li${i.review ? ' class="hb-drive"' : ''}><time datetime="${esc(i.at)}">${esc(clock(i.at))}</time><div>${esc(i.text)}</div></li>`).join('')}</ul>` : `<p>No activity this day.</p>`}`
      + (items.length > TIMELINE_PREVIEW ? `<button class="hb-btn hb-btn-secondary hb-btn-small hb-btn-block" data-a="timeline" style="margin-top:10px">${open ? 'Show less' : `Show more (${items.length - TIMELINE_PREVIEW} earlier)`}</button>` : '')
      + (reply.truncated && open ? '<p class="hf-small">Showing the first 500 entries for this day.</p>' : '') + '</div>';
  }
  return screen + '<div class="hf-section">Detailed activity</div>' + switcher + detail
    + `<p class="hf-small">Harbor sees which apps were open and when, never what was on the screen.</p><button class="hb-btn hb-btn-outline hb-btn-block" data-a="refresh" data-v="activity">Refresh activity</button>`;
}
