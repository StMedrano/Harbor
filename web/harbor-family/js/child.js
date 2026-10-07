// CHILD experience: simple, friendly, and open about what parents can see. Mounted only for role === 'child'.
import { api, ApiError } from './api.js';
import { $, e, LOGO, toast, open, shut, fld, form } from './common.js';

const TABS = { today: 'Today', apps: 'Apps', about: 'About me' };
const ico = {
  today: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  apps: '<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><rect x="13" y="13" width="7" height="7" rx="2"/>',
  about: '<path d="M12 21s-7-4.4-7-10V6l7-3 7 3v5c0 5.6-7 10-7 10z"/><path d="m9 12 2 2 4-4"/>',
};
let cb = {}, st = null, tab = 'today', timer = null;

const emptyKid = (t, d) => `<div class="empty"><h2>${t}</h2><p class="sm">${d}</p></div>`;
const when = iso => iso ? new Date(iso).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' }) : null;

function today() {
  return `<div class="hello"><h1>Hi${st.child.name ? ' ' + e(st.child.name) : ' there'}</h1><p>${new Date().toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' })}</p></div>
<div class="panel"><div class="row"><div><div class="rt">This phone is paired</div><div class="rs">Connected to your family\u2019s Harbor Family account</div></div><span class="tag ${st.offline ? 'm' : ''}">${st.offline ? 'Offline' : 'Connected'}</span></div>
<div class="row"><div><div class="rt">Last sync</div><div class="rs">${st.lastSync ? e(when(st.lastSync)) : 'Not synced yet'}${st.offline ? ' (showing the last known state)' : ''}</div></div></div></div>
<p class="rs" style="text-align:center;margin-top:12px">Screen time, limits and requests will appear here when they are available.</p>`;
}
function apps() {
  return `<div class="head"><h1>Apps</h1></div>${emptyKid('Not available yet', 'App time and limits will show up here once Harbor Family supports them on this phone.')}`;
}
function about() {
  return `<div class="head"><h1>About me</h1></div>
<p class="note"><b>Nothing is hidden.</b> Harbor Family is on this phone so your parents can help keep you safe. Right now it can do this:</p>
<div class="panel"><div class="row"><div><div class="rt">Connect this phone to your family</div><div class="rs">Your parents can see that it is paired and when it last synced</div></div></div></div>
<p class="rs">More features, like screen time and location, will be explained here before they are turned on.</p>
<div class="lab">Parents only</div><button class="btn g" style="width:100%" data-a="unpair">Remove Harbor Family from this phone</button>`;
}

function render(en) {
  $('#kshell').style.setProperty('--kc', st.child.color);
  $('#kbrand').innerHTML = LOGO + 'Harbor Family'; $('#kwho').textContent = st.child.name || 'This phone';
  const v = $('#kview'), keep = v.scrollTop; v.className = en ? 'in' : '';
  v.innerHTML = { today, apps, about }[tab](); v.scrollTop = keep;
  $('#ktabs').innerHTML = Object.keys(TABS).map(t => `<button class="tab" data-a="tab" data-v="${t}" ${tab === t ? 'aria-current="page"' : ''}><svg viewBox="0 0 24 24" aria-hidden="true">${ico[t]}</svg><span>${TABS[t]}</span></button>`).join('');
}

function handle(x) { if (x && x.code === 'unauthenticated') { cb.onExpired && cb.onExpired(); return true; } return false; }
async function load(en) { try { st = await api.getMyStatus(); render(en); } catch (x) { if (!handle(x)) toast('Couldn\u2019t refresh. Check your connection.'); } }
const poll = () => { if ($('#mod').hidden && !document.hidden) load(); };

const onClick = async ev => {
  const t = ev.target.closest('[data-a]'); if (!t) return;
  const a = t.dataset.a, v = t.dataset.v, i = +t.dataset.i;
  try {
    switch (a) {
      case 'x': shut(); return;
      case 'tab': tab = v; render(true); return;
      case 'unpair': open(`<h2 style="font-size:19px">Parents only</h2><p class="muted sm">A parent must sign in to remove Harbor Family from this phone.</p>` + form('unpair', `<div style="margin-top:14px">${fld('email', 'Parent email', 'email', 'off', '', 'inputmode="email" autocapitalize="none"')}<div class="fld"><label for="upw">Parent password</label><input id="upw" name="password" type="password" autocomplete="off" required></div></div>`, 'Remove Harbor Family')); return;
      default: return;
    }
  } catch (x) { if (!handle(x)) toast(x instanceof ApiError ? x.message : 'Something went wrong. Please try again.'); return; }
  await load();
};

const onSubmit = async ev => {
  const f = ev.target.closest('form[data-form]'); if (!f || !['unpair'].includes(f.dataset.form)) return;
  ev.preventDefault();
  const name = f.dataset.form, d = Object.fromEntries(new FormData(f)), err = f.querySelector('.err'), btn = f.querySelector('[type=submit]');
  const fail = t => { err.textContent = t; err.hidden = false; }; err.hidden = true;
  if (name === 'unpair' && (!d.email || !d.password)) return fail('Enter your parent\u2019s email and password.');
  btn.classList.add('load');
  try {
    await api.unpairDevice({ email: d.email, password: d.password }); cb.onSignOut && cb.onSignOut();
  } catch (x) { if (!handle(x)) fail(x instanceof ApiError ? x.message : 'Something went wrong. Please try again.'); }
  finally { btn.classList.remove('load'); }
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
