// Signed-out experience: sign in, create account (parent or child), reset password, and the child's pairing-code step.
import { api, ApiError } from './api.js';
import { $, e, LOGO, fld, pw, form, link } from './common.js';

let mode = 'signin', onSession = () => {}, pending = null;
const lastEmail = () => { try { return localStorage.getItem('harbor.lastEmail') || ''; } catch { return ''; } };
const MAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const em = v => fld('email', 'Email', 'email', 'username', v, 'inputmode="email" autocapitalize="none"');
const ROLE_HINT = { parent: 'You\u2019ll add your children and get a code to pair each of their phones.', child: 'No email needed. You\u2019ll enter the 6-digit pairing code a parent gives you.' };
const rolePick = `<div class="fld"><label id="rl">This account is for</label><div class="seg full" role="radiogroup" aria-labelledby="rl" style="margin-bottom:6px"><button type="button" role="radio" data-a="role" data-v="parent" aria-checked="true" aria-selected="true">A parent</button><button type="button" role="radio" data-a="role" data-v="child" aria-checked="false" aria-selected="false">A child</button></div><input type="hidden" name="role" value="parent"><p class="hint" id="rh">${ROLE_HINT.parent}</p></div>`;

/** Shows the pairing-code step for a signed-in child who has not paired yet. */
export function showPair(session) { pending = session; showAuth('pair'); }
export function showAuth(m) {
  mode = m || 'signin'; if (mode !== 'pair') pending = null;
  const V = {
    signin: ['Welcome back', 'Sign in to see how your family is doing.',
      form('signin', em(lastEmail()) + pw('password', 'Password', 'current-password') + `<label class="chk"><input type="checkbox" name="remember" checked> Keep me signed in</label>`, 'Sign in', `${link('forgot', 'Forgot password?')}<br>New to Harbor Family? ${link('signup', 'Create an account')}`)],
    signup: ['Create your account', 'Harbor Family keeps parents and kids connected. It takes about a minute.',
      form('signup', rolePick + fld('name', 'Your name', 'text', 'name') + em('') + pw('password', 'Password', 'new-password') + `<p class="hint" style="margin:-8px 0 14px">At least 8 characters, with a letter and a number.</p>` + pw('confirm', 'Confirm password', 'new-password'), 'Create account', `Already have an account? ${link('signin', 'Sign in')}`)],
    confirm: ['Confirm your email', 'We sent you a link. Open it, then come back and sign in.', `<button class="btn p" data-a="auth" data-v="signin" style="width:100%">Go to sign in</button>`],
    forgot: ['Reset your password', 'Enter your email and we\u2019ll send you a reset link.', form('forgot', em(lastEmail()), 'Send reset link', link('signin', 'Back to sign in'))],
    sent: ['Check your email', 'If an account exists for that address, a reset link is on its way.', `<button class="btn p" data-a="auth" data-v="signin" style="width:100%">Back to sign in</button>`],
    pair: ['Pair this phone', 'Ask a parent for the 6-digit code in their Harbor Family app: Family \u203A Pairing code. You only do this once.',
      form('pair', fld('code', 'Pairing code', 'text', 'one-time-code', '', 'inputmode="numeric" maxlength="7" placeholder="000 000" style="font-size:22px;letter-spacing:.2em;text-align:center"'), 'Pair this phone', `Harbor Family shows your parents your location and app time. You can see exactly what they see in the About tab.<br>${link('signup', 'Not a child? Back')}`)],
  }[mode];
  $('#auth').innerHTML = `<div class="brand">${LOGO}Harbor Family</div><div><h1>${V[0]}</h1><p class="sub">${V[1]}</p></div><div>${V[2]}</div>`;
  $('#shell').hidden = true; $('#kshell').hidden = true; $('#auth').hidden = false;
  const f = $('#auth input'); f && f.focus({ preventScroll: true });
}

function validate(name, d) {
  if (name === 'pair') return String(d.code || '').replace(/\D/g, '').length === 6 ? '' : 'Enter the 6-digit code from your parent.';
  if (name === 'signup' && !(d.name || '').trim()) return 'Enter your name.';
  if (!MAIL.test((d.email || '').trim())) return 'Enter a valid email address.';
  if (name === 'signin' && !d.password) return 'Enter your password.';
  if (name === 'signup') {
    if (d.password.length < 8 || !/[A-Za-z]/.test(d.password) || !/\d/.test(d.password)) return 'Password needs 8+ characters with a letter and a number.';
    if (d.password !== d.confirm) return 'Passwords don\u2019t match.';
  }
  return '';
}

/** `cb(session)` is called after any successful sign-in / sign-up / pairing. */
export function initAuth(cb) {
  onSession = cb;
  document.addEventListener('click', async ev => {
    const t = ev.target.closest('#auth [data-a]'); if (!t) return;
    if (t.dataset.a === 'auth') showAuth(t.dataset.v);
    if (t.dataset.a === 'signout') { try { await api.signOut(); } catch { /* ignore */ } showAuth('signin'); }
    if (t.dataset.a === 'role') {
      const v = t.dataset.v; if (v === 'child') return showAuth('pair'); // children don't create accounts: they pair with a code
      t.closest('form').elements.role.value = v;
      t.parentElement.querySelectorAll('button').forEach(b => { const on = b === t; b.setAttribute('aria-selected', on); b.setAttribute('aria-checked', on); });
      $('#rh').textContent = ROLE_HINT[v];
    }
  });
  document.addEventListener('submit', async ev => {
    const f = ev.target.closest('form[data-form]'); if (!f || !['signin', 'signup', 'forgot', 'pair'].includes(f.dataset.form)) return;
    ev.preventDefault();
    const name = f.dataset.form, d = Object.fromEntries(new FormData(f)), err = f.querySelector('.err'), btn = f.querySelector('[type=submit]');
    const fail = t => { err.textContent = t; err.hidden = false; }; err.hidden = true;
    const bad = validate(name, d); if (bad) return fail(bad);
    btn.classList.add('load');
    try {
      if (name === 'forgot') { await api.requestPasswordReset(d.email.trim()); return showAuth('sent'); }
      let s;
      if (name === 'pair') s = await api.pairDevice({ code: d.code, deviceName: 'This phone' });
      else {
        s = name === 'signin' ? await api.signIn({ email: d.email, password: d.password, remember: !!d.remember }) : await api.signUp({ name: d.name, email: d.email, password: d.password, role: d.role });
        try { localStorage.setItem('harbor.lastEmail', d.email.trim()); } catch { /* ignore */ }
      }
      await onSession(s);
    } catch (x) {
      if (x && x.code === 'confirm_email') return showAuth('confirm');
      if (x && x.code === 'unauthenticated' && name === 'pair') return showAuth('signin');
      fail(x instanceof ApiError ? x.message : 'Something went wrong. Please try again.');
    } finally { btn.classList.remove('load'); }
  });
}
