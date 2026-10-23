// Signed-out experience and first steps: sign in, create a parent account, reset password, a child's pairing code,
// and, for a new parent with no family yet, starting a family or joining one with another parent's invite code.
import { api, ApiError } from './api.js';
import { $, e, fld, pw, form, link, busy } from './common.js';

let mode = 'signin', onSession = () => {}, pendingSession = null;
const lastEmail = () => { try { return localStorage.getItem('harbor.lastEmail') || ''; } catch { return ''; } };
const MAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const em = v => fld('email', 'Email', 'email', 'username', v, 'inputmode="email" autocapitalize="none"');
const LOCKUP = '<img class="hf-lockup" src="logo-night.svg" alt="Harbor Family" width="150" height="158" data-theme-logo>';

/** Shows the pairing-code step for a signed-in child who has not paired yet. */
export function showPair() { showAuth('pair'); }
/** A parent signed in without a family: start one or join one with an invite code. */
export function showFamilySetup(session) { pendingSession = session; showAuth('family'); }

export function showAuth(m) {
  mode = m || 'signin'; if (mode !== 'family' && mode !== 'join') pendingSession = null;
  const first = pendingSession && pendingSession.user.name ? pendingSession.user.name.trim().split(/\s+/)[0] : '';
  const V = {
    signin: ['Parent account', 'Welcome back', 'Sign in to see how your family is doing.',
      form('signin', em(lastEmail()) + pw('password', 'Password', 'current-password') + `<label class="hf-check"><input type="checkbox" name="remember" checked> Keep me signed in</label>`, 'Sign in',
        `${link('forgot', 'Forgot password?')}<br>New to Harbor Family? ${link('signup', 'Create an account')}<br>Setting up a child’s phone? ${link('pair', 'Enter a pairing code')}`)],
    signup: ['Parent account', 'Create your account', 'For a parent or another adult who helps look after the children.',
      form('signup', fld('name', 'Your name', 'text', 'name') + em('') + pw('password', 'Password', 'new-password') + '<p class="hb-hint" style="margin:-10px 0 16px">At least 8 characters, with a letter and a number.</p>' + pw('confirm', 'Confirm password', 'new-password'), 'Create account', `Already have an account? ${link('signin', 'Sign in')}`)],
    confirm: ['Parent account', 'Confirm your email', 'We sent you a link. Open it, then come back and sign in.', `<button type="button" class="hb-btn hb-btn-primary hb-btn-block" data-a="auth" data-v="signin">Go to sign in</button>`],
    forgot: ['Parent account', 'Reset your password', 'Enter your email and we’ll send you a reset link.', form('forgot', em(lastEmail()), 'Send reset link', link('signin', 'Back to sign in'))],
    sent: ['Parent account', 'Check your email', 'If an account exists for that address, a reset link is on its way.', `<button type="button" class="hb-btn hb-btn-primary hb-btn-block" data-a="auth" data-v="signin">Back to sign in</button>`],
    pair: ['Connect this phone', 'Enter pairing code', 'Six digits from your parent’s Harbor app: Family › Pairing code. A code works for 10 minutes.',
      form('pair', `<div class="hf-code">${fld('code', 'Pairing code', 'text', 'one-time-code', '', 'inputmode="numeric" maxlength="7" placeholder="000 000"')}</div>`, 'Connect this phone', `Not a child’s phone? ${link('signin', 'Back to sign in')}`)],
    family: ['Your circle', first ? `Welcome, ${first}` : 'Welcome to Harbor', 'Start your own family, or join one another parent already set up.',
      `<div class="hf-gap"><button type="button" class="hb-btn hb-btn-primary hb-btn-block" data-a="auth" data-v="join">I have an invite code</button><button type="button" class="hb-btn hb-btn-secondary hb-btn-block" data-a="auth" data-v="start">Start a new family</button></div><p class="hf-alt">${link('out', 'Sign out')}</p>`],
    join: ['Your circle', 'Join a family', 'Ask the other parent for a code: in Harbor Family, Family › Invite a parent. A code works once, for one day.',
      form('join', `<div class="hf-code">${fld('code', 'Invite code', 'text', 'off', '', 'autocapitalize="characters" maxlength="12" placeholder="ABCD-EFGH" spellcheck="false"')}</div>`, 'Join family', link('family', 'Back'))],
    start: ['Your circle', 'Start a family', 'You can add your children and invite another parent next.',
      form('start', fld('fname', 'Family name', 'text', 'off', first ? `${first}’s family` : ''), 'Start family', link('family', 'Back'))],
  }[mode];
  $('#auth').innerHTML = `${LOCKUP}<div class="hb-card"><header class="hb-sh"><div class="hb-eyebrow">${e(V[0])}</div><h1>${e(V[1])}</h1><p>${e(V[2])}</p></header>${V[3]}</div>`;
  themeLogo();
  $('#shell').hidden = true; $('#kshell').hidden = true; $('#auth').hidden = false;
  const f = $('#auth input:not([type=checkbox])'); f && f.focus({ preventScroll: true });
}

/** The lockup in the theme the page is showing. */
function themeLogo() {
  const img = $('#auth [data-theme-logo]'); if (!img) return;
  const day = matchMedia('(prefers-color-scheme: light)').matches;
  img.src = day ? 'logo-daybreak.svg' : 'logo-night.svg';
}

function validate(name, d) {
  if (name === 'pair') return String(d.code || '').replace(/\D/g, '').length === 6 ? '' : 'Enter the 6-digit code from your parent.';
  if (name === 'join') return String(d.code || '').replace(/[\s-]/g, '').length === 8 ? '' : 'Enter the 8-character code, like ABCD-EFGH.';
  if (name === 'start') return (d.fname || '').trim() ? '' : 'Enter a name for your family.';
  if (name === 'signup' && !(d.name || '').trim()) return 'Enter your name.';
  if (!MAIL.test((d.email || '').trim())) return 'Enter a valid email address.';
  if (name === 'signin' && !d.password) return 'Enter your password.';
  if (name === 'signup') {
    if (d.password.length < 8 || !/[A-Za-z]/.test(d.password) || !/\d/.test(d.password)) return 'Password needs 8+ characters with a letter and a number.';
    if (d.password !== d.confirm) return 'Passwords don’t match.';
  }
  return '';
}

/** `cb(session)` is called after any successful sign-in, sign-up, pairing or family step. */
export function initAuth(cb) {
  onSession = cb;
  matchMedia('(prefers-color-scheme: light)').addEventListener('change', themeLogo);
  document.addEventListener('click', async ev => {
    const t = ev.target.closest('#auth [data-a]'); if (!t) return;
    if (t.dataset.a === 'auth') showAuth(t.dataset.v);
    if (t.dataset.a === 'out') { try { await api.signOut(); } catch { /* ignore */ } showAuth('signin'); }
  });
  document.addEventListener('submit', async ev => {
    const f = ev.target.closest('#auth form[data-form]'); if (!f) return;
    ev.preventDefault();
    const name = f.dataset.form, d = Object.fromEntries(new FormData(f)), err = f.querySelector('.hb-err'), btn = f.querySelector('[type=submit]');
    const fail = t => { err.textContent = t; err.hidden = false; }; err.hidden = true;
    const bad = validate(name, d); if (bad) return fail(bad);
    busy(btn, true);
    try {
      if (name === 'forgot') { await api.requestPasswordReset(d.email.trim()); return showAuth('sent'); }
      let s;
      if (name === 'pair') s = await api.pairDevice({ code: d.code, deviceName: 'This phone' });
      else if (name === 'join') s = await api.joinFamily(d.code);
      else if (name === 'start') s = await api.createFamily(d.fname);
      else {
        s = name === 'signin' ? await api.signIn({ email: d.email, password: d.password }) : await api.signUp({ name: d.name, email: d.email, password: d.password });
        try { localStorage.setItem('harbor.lastEmail', d.email.trim()); } catch { /* ignore */ }
      }
      await onSession(s);
    } catch (x) {
      if (x && x.code === 'confirm_email') return showAuth('confirm');
      if (x && x.code === 'unauthenticated') return showAuth('signin');
      fail(x instanceof ApiError ? x.message : 'Something went wrong. Please try again.');
    } finally { busy(btn, false); }
  });
}
