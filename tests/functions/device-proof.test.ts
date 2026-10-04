import {
  requireDeviceProof,
  type DeviceProofDependencies,
} from "../../supabase/functions/_shared/device-proof.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) throw new Error(message ?? `expected ${String(expected)}, got ${String(actual)}`);
}

async function assertRejectsCode(promise: Promise<unknown>, code: string) {
  try {
    await promise;
  } catch (error) {
    assert(error instanceof Error);
    assertEquals((error as Error & { code?: string }).code, code);
    return;
  }
  throw new Error(`expected rejection with ${code}`);
}

const device = {
  deviceId: "54000000-0000-4000-8000-000000000001",
  familyId: "52000000-0000-4000-8000-000000000001",
  childId: "53000000-0000-4000-8000-000000000001",
  authUserId: "51000000-0000-4000-8000-000000000101",
  publicKeySpki: "fixture-p256-spki",
  revokedAt: null as string | null,
};

const now = new Date("2026-10-04T08:00:00.000Z");

function request(overrides: { body?: string; deviceId?: string; timestamp?: number; nonce?: string; signature?: string } = {}) {
  const body = overrides.body ?? JSON.stringify({ acknowledgedDesiredStateVersion: 7 });
  return new Request("https://harbor.test/functions/v1/device-sync", {
    method: "POST",
    headers: {
      Authorization: "Bearer device-jwt",
      "content-type": "application/json",
      "X-Harbor-Device-Id": overrides.deviceId ?? device.deviceId,
      "X-Harbor-Timestamp": String(overrides.timestamp ?? Math.floor(now.getTime() / 1000)),
      "X-Harbor-Nonce": overrides.nonce ?? "nonce-001",
      "X-Harbor-Signature": overrides.signature ?? "valid-signature",
    },
    body,
  });
}

function dependencies(overrides: Partial<DeviceProofDependencies> = {}): DeviceProofDependencies {
  return {
    now: () => now,
    requireDeviceIdentity: async () => ({ userId: device.authUserId, accessToken: "device-jwt" }),
    loadDeviceSecurity: async () => device,
    sha256: async () => "body-sha256",
    verifyP256Signature: async (_spki, _signature, canonical) => canonical.includes("body-sha256"),
    claimNonceAtomic: async () => true,
    ...overrides,
  };
}

Deno.test("device proof accepts a valid P-256 signature and returns bound context", async () => {
  const context = await requireDeviceProof(request(), "device-sync", dependencies());
  assertEquals(context.deviceId, device.deviceId);
  assertEquals(context.familyId, device.familyId);
  assertEquals(context.childId, device.childId);
  assertEquals(context.authUserId, device.authUserId);
});

Deno.test("device proof rejects a signature made by the wrong key", async () => {
  await assertRejectsCode(requireDeviceProof(request(), "device-sync", dependencies({ verifyP256Signature: async () => false })), "FORBIDDEN");
});

Deno.test("device proof binds the signature to the request body", async () => {
  let canonical = "";
  await requireDeviceProof(request({ body: JSON.stringify({ acknowledgedDesiredStateVersion: 8 }) }), "device-sync", dependencies({
    sha256: async () => "modified-body-sha256",
    verifyP256Signature: async (_spki, _signature, input) => { canonical = input; return true; },
  }));
  assert(canonical.includes("modified-body-sha256"));
});

Deno.test("device proof rejects a device id that does not match the bound JWT subject", async () => {
  await assertRejectsCode(requireDeviceProof(request({ deviceId: "54000000-0000-4000-8000-000000000099" }), "device-sync", dependencies()), "FORBIDDEN");
});

Deno.test("device proof accepts timestamps exactly five minutes from server time", async () => {
  const boundary = Math.floor(now.getTime() / 1000) - 300;
  const context = await requireDeviceProof(request({ timestamp: boundary }), "device-sync", dependencies());
  assertEquals(context.deviceId, device.deviceId);
});

Deno.test("device proof rejects timestamps outside the five-minute window", async () => {
  const stale = Math.floor(now.getTime() / 1000) - 301;
  await assertRejectsCode(requireDeviceProof(request({ timestamp: stale }), "device-sync", dependencies()), "FORBIDDEN");
});

Deno.test("device proof rejects a reused nonce", async () => {
  await assertRejectsCode(requireDeviceProof(request(), "device-sync", dependencies({ claimNonceAtomic: async () => false })), "REPLAY_REJECTED");
});

Deno.test("a copied valid JWT without a device signature is insufficient", async () => {
  const unsigned = request();
  unsigned.headers.delete("X-Harbor-Signature");
  await assertRejectsCode(requireDeviceProof(unsigned, "device-sync", dependencies()), "FORBIDDEN");
});

Deno.test("device proof rejects a currently revoked device even with a valid JWT and signature", async () => {
  await assertRejectsCode(requireDeviceProof(request(), "device-sync", dependencies({
    loadDeviceSecurity: async () => ({ ...device, revokedAt: "2026-10-04T07:59:00.000Z" }),
  })), "DEVICE_REVOKED");
});
