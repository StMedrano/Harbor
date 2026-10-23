// Router: ONE app. The signed-in role decides the screen:
//   parent, no family yet → start a family or join one with an invite code
//   parent                → parent dashboard
//   child, not paired yet → pairing-code screen
//   child, paired         → child dashboard
import { api } from './api.js';
import { $, toast, initCommon } from './common.js';
import { initAuth, showAuth, showPair, showFamilySetup } from './auth.js';
import { mountParent, unmountParent, parentBack } from './parent.js';
import { mountChild, unmountChild, childBack } from './child.js';

let role = null;
const hooks = {
  onSignOut: () => { unmountAll(); showAuth('signin'); },
  onExpired: () => { unmountAll(); showAuth('signin'); toast('Please sign in again', { tone: 'info', detail: 'Your session ended.' }); },
};
function unmountAll() { unmountParent(); unmountChild(); role = null; }

async function route(session) {
  unmountAll(); role = session.role;
  if (role === 'child' && !session.child) { showPair(); return; }
  if (role === 'parent' && !session.familyId) { showFamilySetup(session); return; }
  $('#auth').hidden = true;
  if (role === 'child') await mountChild(session, hooks); else await mountParent(session, hooks);
  if (session.joined) toast(`You joined ${session.joined}`, { detail: 'You can see and change everything the other parent can.' });
}

initCommon();
initAuth(route);
// Android WebView back button hook (used by the Android wrapper if you embed this).
window.harborBack = () => role === 'child' ? childBack() : role === 'parent' ? parentBack() : false;

(async () => {
  try { const s = await api.getSession(); if (s) return await route(s); } catch { /* fall through to sign-in */ }
  showAuth('signin');
})();
