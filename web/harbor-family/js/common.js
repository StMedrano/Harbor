// Shared helpers and Harbor Family design-system pieces (icons, logo, illustrations, fields, sheets, toasts) for every
// role. Markup uses the design system's hb-* classes (css/harbor.css); colors come only from its tokens.
export const $ = s => document.querySelector(s);
// ALWAYS escape backend data before putting it in a template string.
export const e = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const clock = iso => iso ? new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '';
export const when = iso => { if (!iso) return ''; const d = new Date(iso); return d.toDateString() === new Date().toDateString() ? clock(iso) : d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ' ' + clock(iso); };
export const ago = iso => { const m = Math.max(0, Math.round((Date.now() - new Date(iso)) / 6e4)); return m < 1 ? 'just now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : when(iso); };
export const minutes = ms => { const m = Math.max(0, Math.floor(ms / 60000)); return m >= 60 ? `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m` : `${m}m`; };

/* ── Harbor's line icons (24px grid, 1.8 stroke) ── */
const ICONS = {
  map: '<path d="M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z"/><circle cx="12" cy="9.5" r="2.5"/>',
  alerts: '<path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
  controls: '<rect x="6" y="3" width="12" height="18" rx="2.5"/><path d="M12 8v4l2.5 1.5"/>',
  activity: '<path d="M3 12h4l2.5-6 5 12 2.5-6h4"/>',
  family: '<circle cx="9" cy="8" r="3"/><circle cx="17" cy="9.5" r="2.3"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0M14.5 19a4 4 0 0 1 6.5-3.1"/>',
  today: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  apps: '<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><rect x="13" y="13" width="7" height="7" rx="2"/>',
  about: '<path d="M12 21s-7-4.4-7-10V6l7-3 7 3v5c0 5.6-7 10-7 10z"/><path d="m9 12 2 2 4-4"/>',
  check: '<circle cx="12" cy="12" r="8.5"/><path d="m8.5 12.2 2.4 2.4 4.8-5"/>',
  send: '<path d="M20.5 3.5 3.5 10.5l7 3 3 7z"/><path d="m10.5 13.5 4-4"/>',
  hourglass: '<path d="M7 3.5h10M7 20.5h10M8 3.5V6l4 6 4-6V3.5M8 20.5V18l4-6 4 6v2.5"/>',
  lock: '<rect x="5" y="10.5" width="14" height="10" rx="2.5"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  phone: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
  anchor: '<circle cx="12" cy="5.5" r="2"/><path d="M12 7.5v13M8.5 11h7M4.5 13.5a7.5 7 0 0 0 15 0"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 8h.01"/>',
  warn: '<path d="M12 4 21 19.5H3z"/><path d="M12 10v4M12 17h.01"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  back: '<path d="m15 6-6 6 6 6"/>',
};
export const icon = (name, size = 24, sw = 1.8) => `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ''}</svg>`;

/* ── Logo: the shield glyph (icon) for the app bar, the lockup for sign-in ── */
export const logoIcon = (size = 26) => `<svg class="hb-logo" viewBox="0 0 64 64" width="${size}" height="${size}" aria-hidden="true"><path d="M32 4 56 11.5V33C56 47 46 56 32 61 18 56 8 47 8 33V11.5Z" fill="var(--ink)"/><path d="M26.5 51 28.5 26h7l2 25z" fill="var(--hb-ground)"/><path d="M26.2 18h11.6L32 12z" fill="var(--hb-ground)"/><rect x="27.5" y="18" width="9" height="7" rx="1.2" fill="var(--logo-lamp)"/><path d="M12.5 49c6-4.5 12 2 19.5-1s12.5-4 19.5-1" fill="none" stroke="var(--logo-wave)" stroke-width="3.8" stroke-linecap="round"/></svg>`;
export const brand = () => `<div class="hb-brand">${logoIcon()}<span>harbor</span></div>`;

/* ── Harbor illustrations for empty states: lighthouse (all clear), buoy (waiting), anchor (setup), calm (quiet day) ── */
const WATER = '<path d="M6 74c8-4 16-4 24 0s16 4 24 0 16-4 24 0 16 4 24 0 8-2 10-2" stroke="var(--acc)" stroke-opacity=".55"/><path d="M20 84c8-3 14-3 20 0s14 3 20 0 14-3 20 0 14 3 20 0" stroke="var(--acc)" stroke-opacity=".3"/>';
const ART = {
  lighthouse: '<circle cx="60" cy="40" r="30" fill="var(--accs)" stroke="none"/><path d="M60 26 22 14v20z" fill="var(--warn)" fill-opacity=".18" stroke="none"/><path d="M60 26l38-12v20z" fill="var(--warn)" fill-opacity=".18" stroke="none"/><path d="M52 70 55 32h10l3 38z" fill="var(--s2)" stroke="var(--ink2)"/><path d="M54 46h12M53 58h14" stroke="var(--dan)" stroke-width="3" stroke-opacity=".85"/><rect x="54" y="22" width="12" height="10" rx="2" fill="var(--warn)" stroke="var(--ink2)"/><path d="M52 22h16L60 15z" fill="var(--s2)" stroke="var(--ink2)"/><path d="M40 72c6-5 34-5 40 0" fill="var(--land)" stroke="var(--shore)"/>' + WATER,
  buoy: '<circle cx="60" cy="66" r="24" stroke="var(--acc)" stroke-opacity=".18"/><circle cx="60" cy="66" r="16" stroke="var(--acc)" stroke-opacity=".32"/><path d="M50 66 54 38h12l4 28z" fill="var(--s2)" stroke="var(--ink2)"/><path d="M52.5 50h15" stroke="var(--warn)" stroke-width="4"/><path d="M60 38V26" stroke="var(--ink2)"/><circle cx="60" cy="23" r="3.5" fill="var(--warn)" stroke="none"/><path d="M42 68c6 3 12 3 18 0s12-3 18 0" stroke="var(--acc)"/><path d="M20 84c8-3 14-3 20 0s14 3 20 0 14-3 20 0 14 3 20 0" stroke="var(--acc)" stroke-opacity=".3"/>',
  anchor: '<circle cx="60" cy="44" r="30" fill="var(--accs)" stroke="none"/><circle cx="60" cy="24" r="5" stroke="var(--acc)" stroke-width="3"/><path d="M60 29v36M50 37h20M40 50c2 10 10 16 20 16s18-6 20-16" stroke="var(--acc)" stroke-width="3"/><path d="M36 54l4-5 5 3M84 54l-4-5-5 3" stroke="var(--acc)" stroke-width="3"/>' + WATER,
  calm: '<mask id="hb-art-moon"><rect width="120" height="92" fill="#fff"/><circle cx="84" cy="24" r="10" fill="#000"/></mask><circle cx="78" cy="28" r="12" fill="var(--warn)" fill-opacity=".9" stroke="none" mask="url(#hb-art-moon)"/><circle cx="32" cy="22" r="1.6" fill="var(--ink3)" stroke="none"/><circle cx="48" cy="34" r="1.2" fill="var(--ink3)" stroke="none"/><circle cx="22" cy="40" r="1.2" fill="var(--ink3)" stroke="none"/><path d="M38 66h28l-5 6H43zM52 66V48l10 14H52" fill="var(--s2)" stroke="var(--ink2)"/>' + WATER,
};
export const art = name => `<svg class="hb-art" viewBox="0 0 120 92" width="120" height="92" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ART[name] || ART.lighthouse}</svg>`;
/** An empty state says what will appear here and, only when the person has to act, the one next step. */
export const empty = (title, body, { art: a = 'lighthouse', action = '' } = {}) => `<div class="hb-empty">${art(a)}<h3>${title}</h3>${body ? `<p>${body}</p>` : ''}${action}</div>`;

/* ── Building blocks ── */
export const header = (eyebrow, title, sub = '') => `<header class="hb-sh"><div class="hb-eyebrow">${e(eyebrow)}</div><h1>${e(title)}</h1>${sub ? `<p>${e(sub)}</p>` : ''}</header>`;
export const btn = (label, attrs = '', kind = 'primary', extra = '') => `<button type="button" class="hb-btn hb-btn-${kind} ${extra}" ${attrs}>${label}</button>`;
export const note = (html, warn = false) => `<div class="hb-note${warn ? ' hb-note-warn' : ''}" role="status">${html}</div>`;
export const tag = (text, tone = '') => `<span class="hb-tag${tone ? ' hb-tag-' + tone : ''}">${e(text)}</span>`;
export const sw = (id, on, label, disabled = false) => `<label class="hb-sw"><input type="checkbox" data-c="${id}" ${on ? 'checked' : ''} ${disabled ? 'disabled' : ''} aria-label="${e(label)}"><span></span></label>`;
export const fld = (id, label, type, auto, v = '', extra = '', hint = '') => `<div class="hb-fld"><label for="${id}">${label}</label><input id="${id}" name="${id}" type="${type}" autocomplete="${auto}" value="${e(v)}" required ${extra}>${hint ? `<p class="hb-hint">${hint}</p>` : ''}</div>`;
export const pw = (id, label, auto) => `<div class="hb-fld"><label for="${id}">${label}</label><div class="hb-pw"><input id="${id}" name="${id}" type="password" autocomplete="${auto}" required minlength="8"><button type="button" class="hb-eye" data-a="eye" aria-label="Show password" aria-pressed="false">Show</button></div></div>`;
export const form = (name, body, cta, foot) => `<form data-form="${name}" novalidate><p class="hb-err" role="alert" hidden></p>${body}<button class="hb-btn hb-btn-primary hb-btn-block" type="submit">${cta}</button></form>${foot ? `<p class="hf-alt">${foot}</p>` : ''}`;
export const link = (v, t) => `<button type="button" class="hf-link" data-a="auth" data-v="${v}">${t}</button>`;
/** Crew colors identify a person only (kid-1 … kid-6), in the order children were added. */
export const crew = i => `var(--kid-${(i % 6) + 1})`;
export const avatar = (name, color, cls = '') => `<span class="hb-av ${cls}" style="background:${color}">${e((String(name || '?').trim()[0] || '?').toUpperCase())}</span>`;
/** A working button keeps its label and shows the small spinner. */
export function busy(button, on) { if (!button) return; button.disabled = on; button.classList.toggle('hb-btn-loading', on); const s = button.querySelector('.hb-spin'); if (on && !s) button.insertAdjacentHTML('afterbegin', '<i class="hb-spin" aria-hidden="true"></i>'); if (!on && s) s.remove(); }

/* ── Sheets ── */
export function open(h) { $('#card').innerHTML = h; $('#mod').hidden = false; const f = $('#card input,#card button'); f && f.focus(); }
export const shut = () => { $('#mod').hidden = true; $('#card').innerHTML = ''; };

/* ── Toasts: one at a time, a new one replaces only the same or lower priority (urgent > review > info > success) ── */
const RANK = { success: 0, info: 1, review: 2, urgent: 3 };
const TONE_ICON = { success: 'check', info: 'info', review: 'warn', urgent: 'warn' };
let current = null;
export function toast(title, { tone = 'success', detail = '', action = '', onAction = null } = {}) {
  const host = $('#toast'); if (!host) return;
  if (current && RANK[current.tone] > RANK[tone]) return;
  clearTimeout(toast.t);
  current = { tone, onAction };
  host.innerHTML = `<div class="hb-toast hb-toast-${tone}" role="${tone === 'urgent' ? 'alert' : 'status'}"><span class="hb-toast-ic">${icon(TONE_ICON[tone], 18, 2.2)}</span><span class="hb-toast-tx"><span class="hb-toast-t">${e(title)}</span>${detail ? `<span class="hb-toast-s">${e(detail)}</span>` : ''}</span>${action ? `<button class="hb-toast-act" data-toast="act">${e(action)}</button>` : ''}<button class="hb-toast-x" data-toast="x" aria-label="Dismiss">${icon('close', 18, 2)}</button></div>`;
  if (tone !== 'urgent') toast.t = setTimeout(dismissToast, tone === 'review' ? 6000 : 4000);
}
export function dismissToast() { clearTimeout(toast.t); current = null; const h = $('#toast'); if (h) h.innerHTML = ''; }

/* ── Greetings for Map: picked when the screen opens, never the same one twice in a row ── */
const GREETINGS = {
  morning: ['Good morning, {name}', 'First light over the harbor', 'Morning, {name}. Fresh tide today'],
  afternoon: ['Good afternoon, {name}', 'Afternoon on the water', 'Welcome back, {name}'],
  evening: ['Good evening, {name}', 'Evening in the harbor', 'Lanterns on, {name}'],
  night: ['Still up, {name}?', 'Night watch, {name}', 'Quiet hours in the harbor'],
  any: ['Your safe harbor', 'Welcome back to the harbor', 'Back in port, {name}', 'Your crew, at a glance', 'Ahoy, {name}'],
};
let lastGreeting = '';
export function greeting(name, date = new Date()) {
  const h = date.getHours(), pod = h >= 5 && h < 12 ? 'morning' : h < 17 ? 'afternoon' : h < 22 ? 'evening' : 'night';
  const first = String(name || '').trim().split(/\s+/)[0];
  const pool = GREETINGS[pod].concat(GREETINGS.any).filter(g => first || !g.includes('{name}')).map(g => g.replace('{name}', first));
  const choices = pool.filter(g => g !== lastGreeting);
  lastGreeting = choices[Math.floor(Math.random() * choices.length)] || pool[0];
  return lastGreeting;
}

/** Registers global behaviours shared by every role: Esc closes sheets, show/hide password, toast buttons. */
export function initCommon() {
  document.addEventListener('keydown', ev => { if (ev.key === 'Escape' && !$('#mod').hidden) shut(); });
  document.addEventListener('click', ev => {
    const tb = ev.target.closest('[data-toast]');
    if (tb) { const a = current && current.onAction; dismissToast(); if (tb.dataset.toast === 'act' && a) a(); return; }
    if (ev.target.closest('#mod .hf-scrim')) { shut(); return; }
    const t = ev.target.closest('[data-a="eye"]'); if (!t) return;
    const inp = t.previousElementSibling, on = inp.type === 'password';
    inp.type = on ? 'text' : 'password'; t.textContent = on ? 'Hide' : 'Show'; t.setAttribute('aria-pressed', on); t.setAttribute('aria-label', on ? 'Hide password' : 'Show password');
  });
}
