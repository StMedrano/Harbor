/**
 * Bridge to the Harbor Family Android shell (window.HarborDevice, see harbor-family-web-android).
 * Inside the app the child-device identity lives in native code: an Android Keystore P-256 key and the
 * anonymous Supabase session, so a background service can sign location uploads while the page is closed.
 * Every bridge method is asynchronous: it returns immediately and the result arrives on
 * window.harborDeviceResult(id, json). In a normal browser (no bridge) hasNativeDevice() is false and
 * the web implementation in supabase-api.js is used instead.
 */
let seq = 0;
const pending = new Map();
const bridge = () => (typeof window !== 'undefined' ? window.HarborDevice : undefined);
export const hasNativeDevice = () => !!bridge();

if (typeof window !== 'undefined') {
  window.harborDeviceResult = (id, json) => {
    const done = pending.get(id); if (!done) return;
    pending.delete(id);
    let value; try { value = JSON.parse(json); } catch { value = { status: 'error', code: 'unknown' }; }
    done(value);
  };
}

/** Resolves with { status:'ok', ... } or { status:'error', code, message }. Never rejects. */
export function nativeCall(method, args, { timeoutMs = 45000 } = {}) {
  return new Promise(resolve => {
    const b = bridge();
    if (!b || typeof b[method] !== 'function') return resolve({ status: 'error', code: 'unavailable' });
    const id = 'd' + (++seq);
    let timer = null;
    pending.set(id, v => { if (timer) clearTimeout(timer); resolve(v); });
    if (timeoutMs) timer = setTimeout(() => { if (pending.delete(id)) resolve({ status: 'error', code: 'network' }); }, timeoutMs);
    try { b[method](id, args === undefined ? '' : JSON.stringify(args)); }
    catch { pending.delete(id); if (timer) clearTimeout(timer); resolve({ status: 'error', code: 'unavailable' }); }
  });
}
