// Shared helpers used by the auth, parent and child modules.
export const $ = s => document.querySelector(s);
// ALWAYS escape backend data before putting it in a template string.
export const e = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const fmt = m => `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
export const clock = iso => iso ? new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '';
export const when = iso => { if (!iso) return ''; const d = new Date(iso); return d.toDateString() === new Date().toDateString() ? clock(iso) : d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ' ' + clock(iso); };
export const ago = iso => { const m = Math.max(0, Math.round((Date.now() - new Date(iso)) / 6e4)); return m < 1 ? 'just now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : when(iso); };
export const LOGO = '<svg class="logo" viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="8" fill="currentColor"/><path d="M8 15 16 8l8 7v9H8z" fill="none" stroke="var(--bi)" stroke-width="2.4" stroke-linejoin="round"/><circle cx="16" cy="18" r="2.6" fill="#e9a23b"/></svg>';
export function toast(m) { const t = $('#toast'); t.textContent = m; t.classList.add('on'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('on'), 2400); }
export function open(h) { $('#card').innerHTML = h; $('#mod').hidden = false; const f = $('#card input,#card button'); f && f.focus(); }
export const shut = () => { $('#mod').hidden = true; };
export const sw = (id, on, l) => `<label class="sw"><input type="checkbox" data-c="${id}" ${on ? 'checked' : ''} aria-label="${e(l)}"><span></span></label>`;
export const fld = (id, label, type, auto, v = '', extra = '') => `<div class="fld"><label for="${id}">${label}</label><input id="${id}" name="${id}" type="${type}" autocomplete="${auto}" value="${e(v)}" required ${extra}></div>`;
export const pw = (id, label, auto) => `<div class="fld"><label for="${id}">${label}</label><div class="pw"><input id="${id}" name="${id}" type="password" autocomplete="${auto}" required minlength="8"><button type="button" class="eye" data-a="eye" aria-label="Show password" aria-pressed="false">Show</button></div></div>`;
export const form = (name, body, cta, foot) => `<form data-form="${name}" novalidate>${body}<p class="err" role="alert" hidden></p><button class="btn p" type="submit" style="width:100%">${cta}</button></form>${foot ? `<p class="alt">${foot}</p>` : ''}`;
export const link = (v, t) => `<button type="button" class="lnk" data-a="auth" data-v="${v}">${t}</button>`;
export function emptyCard(t, d) { return `<div class="empty"><h2>${t}</h2><p class="sm">${d}</p></div>`; }

/** Registers global behaviours shared by every role: tap ripple, Esc closes sheets, show/hide password. */
export function initCommon() {
  document.addEventListener('pointerdown', ev => {
    const b = ev.target.closest('.btn,.chip,.kid,.seg button,.al,.row[data-a],.step button'); if (!b || b.disabled) return;
    const q = b.getBoundingClientRect(), d = Math.max(q.width, q.height) * 2, i = document.createElement('i');
    i.className = 'rip'; i.style.cssText = `width:${d}px;height:${d}px;left:${ev.clientX - q.left - d / 2}px;top:${ev.clientY - q.top - d / 2}px`;
    b.appendChild(i); setTimeout(() => i.remove(), 600);
  });
  document.addEventListener('keydown', ev => { if (ev.key === 'Escape') shut(); });
  document.addEventListener('click', ev => {
    const t = ev.target.closest('[data-a="eye"]'); if (!t) return;
    const inp = t.previousElementSibling, on = inp.type === 'password';
    inp.type = on ? 'text' : 'password'; t.textContent = on ? 'Hide' : 'Show'; t.setAttribute('aria-pressed', on); t.setAttribute('aria-label', on ? 'Hide password' : 'Show password');
  });
}
