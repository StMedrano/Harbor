// CHILD experience on the web (development preview): open about what parents can see. Mounted only for role === 'child'.
// The Harbor Family Android app is what measures, shares and enforces; this browser shares nothing.
import { api, ApiError } from './api.js';
import { $, e, icon, brand, header, note, btn, fld, form, open, shut, toast, busy, empty } from './common.js';
import { renderDisclosure } from './child-disclosure.js';

const TABS = [['today', 'Today'], ['apps', 'Apps'], ['about', 'About']];
let cb = {}, st = null, tab = 'today', timer = null;
const when = iso => iso ? new Date(iso).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' }) : null;

function today() {
  return header('Today', 'Hi there', new Date().toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' }))
    + `<div class="hb-card"><div class="hb-share-h"><span class="hb-share-ic">${icon('phone', 18)}</span><h3>This phone is paired</h3><span class="hb-tag ${st.offline ? 'hb-tag-medium' : 'hb-tag-low'}">${st.offline ? 'Offline' : 'Connected'}</span></div>`
    + `<p>Connected to your family’s Harbor Family account.</p><p class="hf-small">Last sync: ${st.lastSync ? e(when(st.lastSync)) : 'not synced yet'}${st.offline ? ' (showing the last known state)' : ''}</p></div>`
    + note('Screen time, location and message checks come only from the Harbor Family Android app. This browser shares none of them.');
}
const apps = () => header('Your apps', 'Apps and time') + `<div class="hb-card">${empty('Not measured here', 'App use is measured only by the Harbor Family Android app. This browser does not report usage.', { art: 'calm' })}</div>`;
const about = () => header('What your parents see', 'About Harbor on your phone')
  + note('<b>Nothing is hidden.</b> Harbor Family is on this phone so your parents can help keep you safe. This is everything it can do.')
  + renderDisclosure()
  + `<div class="hf-section">Parents only</div>${btn('Remove Harbor Family from this phone', 'data-a="unpair"', 'outline', 'hb-btn-block')}`;

function render(enter) {
  $('#kbrand').innerHTML = brand();
  const v = $('#kview'); v.className = 'hf-view' + (enter ? ' in' : '');
  v.innerHTML = { today, apps, about }[tab]();
  $('#ktabs').innerHTML = `<div class="hb-tabs hb-tabs-dock">${TABS.map(([t, l]) => `<button class="hb-tab" data-a="tab" data-v="${t}" ${tab === t ? 'aria-current="page"' : ''}>${icon(t, 22, tab === t ? 2.3 : 1.8)}<span>${l}</span></button>`).join('')}</div>`;
}

function handle(x) { if (x && x.code === 'unauthenticated') { cb.onExpired && cb.onExpired(); return true; } return false; }
async function load(enter) { try { st = await api.getMyStatus(); render(enter); } catch (x) { if (!handle(x)) toast('Couldn’t refresh', { tone: 'info', detail: 'Check your connection.' }); } }
const poll = () => { if ($('#mod').hidden && !document.hidden) load(); };

const onClick = ev => {
  const t = ev.target.closest('[data-a]'); if (!t || !$('#kshell').contains(t) && !$('#mod').contains(t)) return;
  if (t.dataset.a === 'x') shut();
  if (t.dataset.a === 'tab') { tab = t.dataset.v; render(true); }
  if (t.dataset.a === 'unpair') open(`<h2>Parents only</h2><p class="hf-muted">A parent signs in to remove Harbor Family from this phone.</p>` + form('unpair', fld('email', 'Parent email', 'email', 'off', '', 'inputmode="email" autocapitalize="none"') + `<div class="hb-fld"><label for="upw">Parent password</label><input id="upw" name="password" type="password" autocomplete="off" required></div>`, 'Remove Harbor Family'));
};
const onSubmit = async ev => {
  const f = ev.target.closest('#mod form[data-form="unpair"]'); if (!f) return;
  ev.preventDefault();
  const d = Object.fromEntries(new FormData(f)), err = f.querySelector('.hb-err'), b = f.querySelector('[type=submit]');
  const fail = t => { err.textContent = t; err.hidden = false; }; err.hidden = true;
  if (!d.email || !d.password) return fail('Enter your parent’s email and password.');
  busy(b, true);
  try { await api.unpairDevice({ email: d.email, password: d.password }); shut(); cb.onSignOut && cb.onSignOut(); }
  catch (x) { if (!handle(x)) fail(x instanceof ApiError ? x.message : 'Something went wrong. Please try again.'); }
  finally { busy(b, false); }
};

export async function mountChild(session, callbacks) {
  cb = callbacks; tab = 'today';
  document.addEventListener('click', onClick); document.addEventListener('submit', onSubmit); document.addEventListener('visibilitychange', poll);
  await load(true); $('#kshell').hidden = false;
  timer = setInterval(poll, 15000);
}
export function unmountChild() {
  document.removeEventListener('click', onClick); document.removeEventListener('submit', onSubmit); document.removeEventListener('visibilitychange', poll);
  clearInterval(timer); st = null; shut(); $('#kshell').hidden = true;
  for (const id of ['#kview', '#ktabs', '#kbrand']) $(id).innerHTML = '';
}
export function childBack() { if (!$('#mod').hidden) { shut(); return true; } if (tab !== 'today') { tab = 'today'; render(true); return true; } return false; }
