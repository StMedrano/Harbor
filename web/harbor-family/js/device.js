/**
 * Child-device identity for the web build, matching the repo's contract
 * (docs/superpowers/specs/2026-10-03-harbor-vercel-supabase-production-architecture-design.md):
 *   anonymous Supabase Auth identity + non-extractable ECDSA P-256 key + signed requests.
 * The private key never leaves the browser's crypto store (IndexedDB holds a CryptoKey handle, not key bytes).
 * NOTE: the approved design makes the child client native Android (Keystore). A browser key is weaker
 * (script on this origin can ask it to sign), so this is a development/preview path, not the production child.
 */
const DB = 'harbor-device', STORE = 'kv';
const idb = () => new Promise((res, rej) => { const r = indexedDB.open(DB, 1); r.onupgradeneeded = () => r.result.createObjectStore(STORE); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
const tx = async (mode, fn) => { const db = await idb(); return new Promise((res, rej) => { const t = db.transaction(STORE, mode), q = fn(t.objectStore(STORE)); t.oncomplete = () => { db.close(); res(q && q.result); }; t.onerror = () => { db.close(); rej(t.error); }; }); };
export const kvGet = k => tx('readonly', s => s.get(k));
export const kvSet = (k, v) => tx('readwrite', s => s.put(v, k));
export const kvDel = k => tx('readwrite', s => s.delete(k));

const b64 = buf => { let s = ''; for (const b of new Uint8Array(buf)) s += String.fromCharCode(b); return btoa(s); };
const hex = buf => [...new Uint8Array(buf)].map(x => x.toString(16).padStart(2, '0')).join('');

/** Generates a fresh keypair (private key non-extractable) and returns { keyPair, publicKeySpki }. */
export async function newDeviceKey() {
  const keyPair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  return { keyPair, publicKeySpki: b64(await crypto.subtle.exportKey('spki', keyPair.publicKey)) };
}
/** Headers proving possession of the device key for one request. canonical = METHOD\noperation\ndeviceId\nsha256(body)\nts\nnonce */
export async function proofHeaders(privateKey, { method = 'POST', operation, deviceId, body }) {
  const ts = String(Math.floor(Date.now() / 1000)), nonce = hex(crypto.getRandomValues(new Uint8Array(16)));
  const bodyHash = hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(body)));
  const canonical = [method.toUpperCase(), operation, deviceId, bodyHash, ts, nonce].join('\n');
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, new TextEncoder().encode(canonical));
  return { 'X-Harbor-Device-Id': deviceId, 'X-Harbor-Timestamp': ts, 'X-Harbor-Nonce': nonce, 'X-Harbor-Signature': b64(sig) };
}
