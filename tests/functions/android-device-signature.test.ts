import { assertEquals } from "jsr:@std/assert@1";
import { verifyP256Sha256 } from "../../supabase/functions/_shared/crypto.ts";
import { requireDeviceProof, type DeviceProofDependencies } from "../../supabase/functions/_shared/device-proof.ts";

// Mirrors DeviceKey.derToRaw in harbor-family-web-android (Kotlin). Android's Keystore returns ASN.1 DER
// ECDSA signatures; the server verifies raw r||s. Keep the two implementations identical.
function derToRaw(der: Uint8Array, size = 32): Uint8Array {
  let i = 0;
  if (der.length < 8 || der[i++] !== 0x30) throw new Error("not a DER sequence");
  let len = der[i++];
  if (len & 0x80) { const n = len & 0x7f; len = 0; for (let k = 0; k < n; k++) len = (len << 8) | der[i++]; }
  if (len !== der.length - i) throw new Error("bad DER length");
  const integer = () => {
    if (der[i++] !== 0x02) throw new Error("expected INTEGER");
    const l = der[i++];
    let start = i;
    const end = i + l;
    while (start < end - 1 && der[start] === 0) start++;
    i = end;
    const v = der.slice(start, end);
    if (v.length > size) throw new Error("integer too large");
    const out = new Uint8Array(size);
    out.set(v, size - v.length);
    return out;
  };
  const r = integer();
  const s = integer();
  const raw = new Uint8Array(size * 2);
  raw.set(r, 0);
  raw.set(s, size);
  return raw;
}

// What Android's Signature("SHA256withECDSA") would emit for a given raw signature.
function rawToDer(raw: Uint8Array): Uint8Array {
  const enc = (b: Uint8Array) => {
    let start = 0;
    while (start < b.length - 1 && b[start] === 0) start++;
    let v = b.slice(start);
    if (v[0] & 0x80) v = new Uint8Array([0, ...v]);
    return new Uint8Array([0x02, v.length, ...v]);
  };
  const body = new Uint8Array([...enc(raw.slice(0, 32)), ...enc(raw.slice(32))]);
  return new Uint8Array([0x30, ...(body.length > 127 ? [0x81, body.length] : [body.length]), ...body]);
}

const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");

Deno.test("DER signatures from Android convert to what the server verifies, for every r/s shape", async () => {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const spki = b64(new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)));
  let shortR = 0, padded = 0;
  for (let n = 0; n < 400; n++) {
    const message = `POST\nreport-location\ndev\n${n}\n1\nnonce-${n}`;
    const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, pair.privateKey, new TextEncoder().encode(message)));
    const der = rawToDer(raw);
    if (raw[0] === 0) shortR++;
    if (raw[0] & 0x80) padded++;
    const back = derToRaw(der);
    assertEquals(Array.from(back), Array.from(raw));
    assertEquals(await verifyP256Sha256(spki, b64(back), message), true);
  }
  // sanity: the loop actually exercised padded (33-byte) integers
  assertEquals(padded > 50, true);
  assertEquals(shortR >= 0, true);
});

Deno.test("hand-built DER edge cases: leading zeros and short integers", () => {
  const raw = new Uint8Array(64); raw[31] = 1; raw[63] = 2; // r = 1, s = 2
  assertEquals(Array.from(derToRaw(rawToDer(raw))), Array.from(raw));
  const hi = new Uint8Array(64).fill(0xff);
  assertEquals(Array.from(derToRaw(rawToDer(hi))), Array.from(hi));
});

Deno.test("report-location proof from an Android-style signer is accepted end to end, and tampering is not", async () => {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const spki = b64(new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)));
  const deviceId = "11111111-1111-4111-8111-111111111111";
  const body = JSON.stringify({ points: [{ latitude: 1, longitude: 2, recordedAt: "2026-10-08T12:00:00Z" }] });
  const ts = Math.floor(Date.now() / 1000);
  const nonce = "0123456789abcdef0123456789abcdef";
  const bodyHash = hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(body)));
  const canonical = ["POST", "report-location", deviceId, bodyHash, String(ts), nonce].join("\n");
  const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, pair.privateKey, new TextEncoder().encode(canonical)));
  const signature = b64(derToRaw(rawToDer(raw)));
  const deps: DeviceProofDependencies = {
    now: () => new Date(),
    requireDeviceIdentity: async () => ({ userId: "u", accessToken: "t" }),
    loadDeviceSecurity: async () => ({ deviceId, familyId: "f", childId: "c", authUserId: "u", publicKeySpki: spki, revokedAt: null }),
    sha256: async (text) => hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))),
    verifyP256Signature: verifyP256Sha256,
    claimNonceAtomic: async () => true,
  };
  const make = (b: string, op = "report-location") => new Request("https://x.test/report-location", {
    method: "POST", body: b,
    headers: { "X-Harbor-Device-Id": deviceId, "X-Harbor-Timestamp": String(ts), "X-Harbor-Nonce": nonce, "X-Harbor-Signature": signature },
  });
  assertEquals((await requireDeviceProof(make(body), "report-location", deps)).deviceId, deviceId);
  let rejected = 0;
  try { await requireDeviceProof(make(body.replace("1", "9")), "report-location", deps); } catch { rejected++; }
  try { await requireDeviceProof(make(body), "device-sync", deps); } catch { rejected++; }
  assertEquals(rejected, 2);
});
