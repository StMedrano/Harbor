import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { requireDeviceProof, type DeviceProofDependencies } from "../../supabase/functions/_shared/device-proof.ts";
import { sha256Hex, verifyP256Sha256 } from "../../supabase/functions/_shared/crypto.ts";

Deno.test("real P-256 proof rejects copied identity, replay and revocation across one device lifecycle", async () => {
  const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const encode = (bytes: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
  const device = {
    deviceId: "54000000-0000-4000-8000-000000000001",
    familyId: "52000000-0000-4000-8000-000000000001",
    childId: "53000000-0000-4000-8000-000000000001",
    authUserId: "51000000-0000-4000-8000-000000000101",
    publicKeySpki: encode(await crypto.subtle.exportKey("spki", keys.publicKey)),
    revokedAt: null as string | null,
  };
  const now = new Date();
  const timestamp = Math.floor(now.getTime() / 1000);
  const body = JSON.stringify({ acknowledgedDesiredStateVersion: 1 });
  const nonces = new Set<string>();
  const dependencies: DeviceProofDependencies = {
    now: () => now,
    // Auth is supplied at its verified identity seam; this is not live JWT validation.
    requireDeviceIdentity: async () => ({ userId: device.authUserId, accessToken: "same-unexpired-token" }),
    loadDeviceSecurity: async () => device,
    sha256: sha256Hex,
    verifyP256Signature: verifyP256Sha256,
    claimNonceAtomic: async (_id, nonce) => {
      if (nonces.has(nonce)) return false;
      nonces.add(nonce);
      return true;
    },
  };
  const request = (nonce: string, signature: string) => new Request("https://harbor.test/functions/v1/device-sync", {
    method: "POST", body, headers: {
      Authorization: "Bearer same-unexpired-token",
      "X-Harbor-Device-Id": device.deviceId,
      "X-Harbor-Timestamp": String(timestamp),
      "X-Harbor-Nonce": nonce,
      "X-Harbor-Signature": signature,
    },
  });
  const rejected = async (call: Promise<unknown>, code: string) => {
    const error = await assertRejects(() => call);
    assertEquals((error as Error & { code: string }).code, code);
  };
  await rejected(requireDeviceProof(request("nonce-a", "copied-token-without-key"), "device-sync", dependencies), "FORBIDDEN");
  assertEquals(nonces.size, 0);
  const canonical = ["POST", "device-sync", device.deviceId, await sha256Hex(body), String(timestamp), "nonce-a"].join("\n");
  const signature = encode(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, keys.privateKey, new TextEncoder().encode(canonical)));
  assertEquals((await requireDeviceProof(request("nonce-a", signature), "device-sync", dependencies)).deviceId, device.deviceId);
  await rejected(requireDeviceProof(request("nonce-a", signature), "device-sync", dependencies), "REPLAY_REJECTED");
  device.revokedAt = now.toISOString();
  await rejected(requireDeviceProof(request("nonce-a", signature), "device-sync", dependencies), "DEVICE_REVOKED");
});
