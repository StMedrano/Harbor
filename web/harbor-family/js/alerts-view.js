// Read-only mapping and rendering of a parent's message alerts (AlertReadReplyV1). Pure: no DOM, no network.
// An alert says THAT something was flagged: category, app, time, saved-contact flag. There is no message text,
// sender or excerpt in the contract, and every item reads as a possible concern, never a verdict.

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const isTime = s => typeof s === 'string' && Number.isFinite(Date.parse(s));

export const CATEGORY_LABEL = {
  bullying: 'Possible bullying or harassment',
  self_harm: 'Possible self-harm or wellbeing concern',
  adult_content: 'Possible adult content',
  drugs_alcohol: 'Possible drugs or alcohol',
  violence_threat: 'Possible violence or threat',
  unknown_adult_contact: 'Possible contact from an unknown adult',
};
export const SELF_HARM_GUIDANCE = 'Harbor flagged wording that may relate to self-harm. It can be wrong, and it is not a diagnosis. If you are worried about your child’s safety, reach out to them and to professional support. In the US you can call or text 988 (Suicide & Crisis Lifeline) any time, or text HOME to 741741 (Crisis Text Line). If someone is in immediate danger, contact your local emergency number.';
export const LIMITS_NOTICE = 'Harbor only sees notifications it is allowed to read and the text of incoming SMS. Muted or hidden previews and end-to-end encrypted messages can be missed, and the rules make mistakes in both directions. Harbor does not read full conversations and never claims a message is safe.';

const UNAVAILABLE = { status: 'unavailable' };

/** replies: the AlertReadReplyV1 pages read so far, newest page first (null = not available on this surface). */
export function toAlertsView(replies, { offline = false } = {}) {
  const pages = (Array.isArray(replies) ? replies : [replies]).filter(r => r && typeof r === 'object');
  const first = pages[0];
  if (!first) return { ...UNAVAILABLE };
  if (first.state === 'none' || first.state === 'expired') return { status: first.state };
  if (first.state !== 'available') return { ...UNAVAILABLE };
  const seen = new Set(), alerts = [];
  for (const p of pages) for (const a of Array.isArray(p.alerts) ? p.alerts : []) {
    if (!a || typeof a.id !== 'string' || seen.has(a.id) || !isTime(a.at) || !CATEGORY_LABEL[a.category]) continue;
    seen.add(a.id);
    alerts.push({ id: a.id, category: a.category, label: CATEGORY_LABEL[a.category], app: typeof a.appLabel === 'string' && a.appLabel ? a.appLabel : 'An app', at: a.at, savedContact: a.savedContact === true });
  }
  alerts.sort((a, b) => Date.parse(b.at) - Date.parse(a.at) || a.id.localeCompare(b.id));
  const last = pages[pages.length - 1];
  return {
    status: 'available', offline, alerts,
    notificationAccess: typeof first.notificationAccess === 'boolean' ? first.notificationAccess : null,
    smsPermission: typeof first.smsPermission === 'boolean' ? first.smsPermission : null,
    receivedAt: isTime(first.receivedAt) ? first.receivedAt : null,
    nextBefore: isTime(last.nextBefore) ? last.nextBefore : null,
  };
}

const stamp = iso => `<time datetime="${esc(iso)}">${esc(new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }))}</time>`;
const note = (cls, html) => `<div class="hb-note${cls === 'w' ? ' hb-note-warn' : ''}" role="status">${html}</div>`;
const card = (title, body) => `<div class="hb-card"><div class="hb-empty"><h3>${title}</h3><p>${body}</p></div></div>`;
const contact = a => a.savedContact ? 'From a saved contact' : 'Not from a saved contact';

export function renderAlertsList(v, { name = 'your child' } = {}) {
  const who = esc(name), limits = `<p class="hf-small">${esc(LIMITS_NOTICE)}</p>`;
  if (v.status === 'unavailable') return card('Alerts aren’t available here', 'Message alerts come from the Harbor Family Android app on the child’s phone.');
  if (v.status === 'none') return card('No alerts set up yet', `${who}’s phone hasn’t reported. Alerts start once notification access is allowed in Harbor Family on that phone.`) + limits;
  if (v.status === 'expired') return card('No recent report', `Nothing has arrived from ${who}’s phone for 30 days, so earlier alerts were removed.`) + limits;
  const flags = (v.offline ? note('w', '<b>Can’t reach Harbor.</b> Showing the alerts received so far.') : '')
    + (v.notificationAccess === false ? note('w', `<b>Notification access is off on ${who}’s phone.</b> Chat apps aren’t being checked, so no alerts can come from them.`) : '')
    + (v.smsPermission === false ? note('w', `<b>SMS permission is off on ${who}’s phone.</b> Text messages aren’t being checked.`) : '');
  // Design system AlertCard: amber REVIEW for most, coral URGENT for self-harm and threats, always with the word.
  const urgent = a => a.category === 'self_harm' || a.category === 'violence_threat';
  const rows = v.alerts.map(a => `<button class="hb-al${urgent(a) ? ' hb-al-high' : ''}" data-a="alert" data-id="${esc(a.id)}"><span class="hb-tag ${urgent(a) ? 'hb-tag-high' : 'hb-tag-medium'}">${urgent(a) ? 'Urgent' : 'Review'}</span><span class="hb-ah">${esc(a.label)}</span><span class="hb-ab">${contact(a)}</span><span class="hb-at">${esc(a.app)} · ${stamp(a.at)}</span></button>`).join('');
  return flags
    + (rows || card('Nothing flagged', `No possible concerns were flagged for ${who} in the last 30 days. That doesn’t mean nothing happened.`))
    + (v.nextBefore ? '<button class="hb-btn hb-btn-outline hb-btn-block" data-a="alertsmore">Show older alerts</button>' : '')
    + limits;
}

export function renderAlertDetail(a) {
  return `<h2>${esc(a.label)}</h2><p class="hf-muted">${esc(a.app)} · ${stamp(a.at)} · ${contact(a)}</p>`
    + `<p>A message was flagged for your review. A flag is not a confirmed issue: Harbor never sees or sends the message itself, so talk with your child before drawing conclusions.</p>`
    + (a.category === 'self_harm' ? note('w', esc(SELF_HARM_GUIDANCE)) : '')
    + `<p class="hf-small">${esc(LIMITS_NOTICE)}</p><div class="hf-acts"><button class="hb-btn hb-btn-primary hb-btn-block" data-a="x">Close</button></div>`;
}
