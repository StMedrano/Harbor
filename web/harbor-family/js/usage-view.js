// Read-only mapping and rendering of a parent's usage report. Pure: no DOM, no network.
// Unknown stays unknown (never 0), every label is escaped, and nothing here offers a control.

const STALE_AFTER_MS = 30 * 60 * 1000;
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const isNum = n => typeof n === 'number' && Number.isFinite(n) && n >= 0;
const isTime = s => typeof s === 'string' && Number.isFinite(Date.parse(s));

export function formatDuration(ms) {
  if (!isNum(ms)) return 'Unknown';
  const m = Math.floor(ms / 60000);
  return m >= 60 ? `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m` : `${m}m`;
}

const shortDate = d => {
  const [y, m, day] = String(d).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, day)).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
};
const weekday = d => {
  const [y, m, day] = String(d).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, day)).toLocaleDateString('en-US', { weekday: 'narrow', timeZone: 'UTC' });
};
const stamp = iso => `<time datetime="${esc(iso)}">${esc(new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }))}</time>`;

const UNAVAILABLE = { status: 'unavailable' };

/** reply: UsageReadReplyV1 | null (null = reporting is not available on this surface). */
export function toUsageView(reply, nowMs, { offline = false } = {}) {
  if (reply === null || reply === undefined) return { ...UNAVAILABLE };
  if (typeof reply !== 'object') return { ...UNAVAILABLE };
  if (reply.state === 'none' || reply.state === 'expired') return { status: reply.state };
  if (reply.state !== 'available') return { ...UNAVAILABLE };
  const r = reply.report;
  if (!r || typeof r !== 'object' || !Array.isArray(r.days) || !Array.isArray(r.inventory ?? []) || !isTime(reply.receivedAt) || !isTime(r.observedAt)) return { ...UNAVAILABLE };
  const permission = ['granted', 'denied', 'unavailable'].includes(r.usagePermission) ? r.usagePermission : 'unavailable';
  const days = r.days.filter(d => d && typeof d.localDate === 'string').map(d => ({
    localDate: d.localDate,
    quality: ['observed', 'partial', 'unavailable'].includes(d.quality) ? d.quality : 'unavailable',
    totalMs: isNum(d.totalMs) ? d.totalMs : null,
    coverageStart: isTime(d.coverageStart) ? d.coverageStart : null,
    startAt: isTime(d.startAt) ? d.startAt : null,
    observedThrough: isTime(d.observedThrough) ? d.observedThrough : null,
    apps: Array.isArray(d.apps) ? d.apps.filter(a => a && typeof a.packageName === 'string') : [],
  })).sort((a, b) => a.localDate.localeCompare(b.localDate));
  const today = days.length ? days[days.length - 1] : null;
  const labels = new Map((r.inventory || []).filter(i => i && typeof i.packageName === 'string').map(i => [i.packageName, i.label]));
  const measured = new Map((today ? today.apps : []).map(a => [a.packageName, isNum(a.foregroundMs) ? a.foregroundMs : null]));
  const names = new Set([...labels.keys(), ...measured.keys()]);
  const apps = [...names].map(packageName => ({
    packageName,
    labelKnown: labels.has(packageName) && typeof labels.get(packageName) === 'string' && labels.get(packageName).length > 0,
    label: labels.get(packageName) || packageName,
    measured: measured.has(packageName),
    foregroundMs: measured.has(packageName) ? measured.get(packageName) : 0,
  })).sort((a, b) => (b.foregroundMs ?? -1) - (a.foregroundMs ?? -1) || a.label.localeCompare(b.label));
  const receivedMs = Date.parse(reply.receivedAt);
  return {
    status: 'available', offline, permission, zoneId: String(r.zoneId || ''), observedAt: r.observedAt, receivedAt: reply.receivedAt,
    stale: nowMs - receivedMs > STALE_AFTER_MS,
    inventoryStatus: ['complete', 'truncated', 'unavailable'].includes(r.inventoryStatus) ? r.inventoryStatus : 'unavailable',
    days, today, apps,
  };
}

const note = (cls, html) => `<div class="note ${cls}" role="status">${html}</div>`;
const SCOPE = 'Apps are the launchable apps visible to this Android profile. This is a measurement only; Harbor does not limit or block apps from this view.';

function bars(days) {
  const known = days.filter(d => d.totalMs !== null).map(d => d.totalMs);
  const mx = Math.max(...known, 1);
  return `<div class="chart" role="img" aria-label="Measured screen time by day">${days.map(d => {
    const label = `${shortDate(d.localDate)}: ${d.totalMs === null ? 'unknown' : formatDuration(d.totalMs)}${d.quality === 'partial' ? ' (partial day)' : ''}`;
    return d.totalMs === null
      ? `<div class="cc unk" aria-label="${esc(label)}"><i></i>${esc(weekday(d.localDate))}</div>`
      : `<div class="cc${d.quality === 'partial' ? ' part' : ''}" aria-label="${esc(label)}"><i style="height:${Math.round(d.totalMs / mx * 104)}px"></i>${esc(weekday(d.localDate))}</div>`;
  }).join('')}</div>`;
}

export function renderUsageView(v) {
  const head = '<div class="head"><h1>Screen time</h1></div>';
  if (v.status === 'unavailable') return head + `<div class="empty"><h2>Usage reporting is not available here</h2><p class="sm">Usage is measured by the Harbor Family Android app on the child’s phone. Nothing is reported from this screen.</p></div>`;
  if (v.status === 'none') return head + `<div class="empty"><h2>No report yet</h2><p class="sm">The child’s phone has not sent a usage report. Sharing starts only after the child’s phone opts in and Usage Access is granted.</p></div>`;
  if (v.status === 'expired') return head + `<div class="empty"><h2>The last report expired</h2><p class="sm">Reports older than 30 days are removed. Open Harbor Family on the child’s phone to share a new one.</p></div>`;
  const when = `<div class="rs" style="margin-bottom:10px">Received ${stamp(v.receivedAt)} · measured ${stamp(v.observedAt)}${v.zoneId ? ` · ${esc(v.zoneId)}` : ''}</div>`;
  const flags = (v.stale ? note('w', '<b>This report is out of date.</b> The last upload was more than 30 minutes ago, so recent use may be missing.') : '')
    + (v.offline ? note('w', '<b>Can’t reach Harbor.</b> Showing the last report received from the phone.') : '');
  const provenance = '<p class="rs" style="margin-top:10px">Reported by the child’s phone and not independently verified. It will not match Digital Wellbeing exactly.</p>';
  if (v.permission !== 'granted') {
    return head + when + flags + note('w', '<b>Usage access is off on the child’s phone.</b> No screen time was measured, so nothing is shown. Totals are not zero; they are unknown.') + provenance;
  }
  const t = v.today;
  const fromStart = t && t.coverageStart && t.startAt && Date.parse(t.coverageStart) === Date.parse(t.startAt);
  const partial = t && t.quality === 'partial' && t.coverageStart ? note('', fromStart
    ? `<b>Partial day.</b> Some activity on ${esc(shortDate(t.localDate))} could not be fully confirmed, so this total may be incomplete.`
    : `<b>Partial day.</b> Measurement for ${esc(shortDate(t.localDate))} starts at ${stamp(t.coverageStart)}; earlier use is not included.`) : '';
  const total = t ? `<div class="panel"><div class="use"><div><div class="muted sm">${esc(shortDate(t.localDate))} · measured total</div><b class="num">${esc(formatDuration(t.totalMs))}</b></div></div>${t.quality === 'unavailable' ? '<div class="rs">Not measured for this day.</div>' : ''}${bars(v.days)}</div>` : '<div class="empty"><h2>Not measured</h2><p class="sm">The report has no measured days.</p></div>';
  const inv = v.inventoryStatus === 'truncated' ? note('w', 'The app list was truncated, so not every launchable app is shown.')
    : v.inventoryStatus === 'unavailable' ? note('w', 'The app list was not available.') : '';
  const rows = v.apps.map(a => `<div class="row"><div class="rm"><div><div class="rt">${esc(a.label)}</div><div class="rs">${esc(a.packageName)}${a.labelKnown ? '' : ' · name unavailable'}</div></div></div><b class="num">${a.measured ? esc(formatDuration(a.foregroundMs)) : '<span class="muted">None measured</span>'}</b></div>`).join('');
  return head + when + flags + partial + total
    + '<p class="rs">Apps used at the same time (split screen) can add up to more than the total, which counts overlapping time once.</p>'
    + '<div class="lab">Apps today</div>' + inv
    + (rows ? `<div class="panel">${rows}</div>` : '<div class="empty"><h2>No apps listed</h2><p class="sm">No launchable apps were reported.</p></div>')
    + `<p class="rs">${esc(SCOPE)}</p>` + provenance;
}
