import type { ParentContext } from "../../supabase/functions/_shared/auth.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
import {
  handleCreateDevicePairing,
  type CreateDevicePairingDependencies,
  type PairingPersistenceInput,
} from "../../supabase/functions/create-device-pairing/index.ts";
import {
  handleDeviceClaim,
  type DeviceClaimDependencies,
  type DeviceClaimPersistenceInput,
} from "../../supabase/functions/device-claim/index.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) {
    throw new Error(message ?? `expected ${String(expected)}, got ${String(actual)}`);
  }
}

async function jsonBody(response: Response) {
  return await response.json() as Record<string, unknown>;
}

const parent: ParentContext = {
  userId: "51000000-0000-4000-8000-000000000001",
  accessToken: "parent-token",
  aal: "aal1",
  amr: [{ method: "password", timestamp: 2_000_000_000 }],
};

function pairingRequest(childId = "53000000-0000-4000-8000-000000000001") {
  return new Request("https://harbor.test/functions/v1/create-device-pairing", {
    method: "POST",
    headers: {
      Authorization: "Bearer parent-token",
      "content-type": "application/json",
    },
    body: JSON.stringify({ childId }),
  });
}

function pairingDependencies(
  overrides: Partial<CreateDevicePairingDependencies> = {},
): CreateDevicePairingDependencies {
  return {
    requireParent: async () => parent,
    now: () => new Date("2026-10-04T02:00:00.000Z"),
    generatePairingCode: () => "042381",
    digestPairingCode: async (code) => `digest:${code}`,
    issuePairingAtomic: async (input) => ({
      familyId: "52000000-0000-4000-8000-000000000001",
      childId: input.childId,
      expiresAt: input.expiresAt,
    }),
    ...overrides,
  };
}

Deno.test("create-device-pairing returns exactly six digits with an exact ten-minute lifetime", async () => {
  const response = await handleCreateDevicePairing(pairingRequest(), pairingDependencies());

  assertEquals(response.status, 200);
  const body = await jsonBody(response);
  assertEquals(body.code, "042381");
  assert(/^\d{6}$/.test(String(body.code)));
  assertEquals(body.expiresAt, "2026-10-04T02:10:00.000Z");
});

Deno.test("create-device-pairing persists a keyed digest and never the raw code", async () => {
  let persisted: PairingPersistenceInput | undefined;
  const response = await handleCreateDevicePairing(
    pairingRequest(),
    pairingDependencies({
      issuePairingAtomic: async (input) => {
        persisted = input;
        return {
          familyId: "52000000-0000-4000-8000-000000000001",
          childId: input.childId,
          expiresAt: input.expiresAt,
        };
      },
    }),
  );

  assertEquals(response.status, 200);
  assert(persisted);
  assertEquals(persisted.parentUserId, parent.userId);
  assertEquals(persisted.childId, "53000000-0000-4000-8000-000000000001");
  assertEquals(persisted.codeDigest, "digest:042381");
  assertEquals("code" in (persisted as unknown as Record<string, unknown>), false);
});

Deno.test("create-device-pairing hides cross-family child access behind FORBIDDEN", async () => {
  const response = await handleCreateDevicePairing(
    pairingRequest("53000000-0000-4000-8000-000000000002"),
    pairingDependencies({
      issuePairingAtomic: async () => {
        throw new HarborAuthError("FORBIDDEN", 403, "Active family authorization is required");
      },
    }),
  );

  assertEquals(response.status, 403);
  assertEquals((await jsonBody(response)).code, "FORBIDDEN");
});

function claimRequest(overrides: Record<string, unknown> = {}) {
  return new Request("https://harbor.test/functions/v1/device-claim", {
    method: "POST",
    headers: {
      Authorization: "Bearer device-token",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      code: "042381",
      publicKeySpki: "valid-p256-spki",
      device: {
        displayName: "Child phone",
        model: "Pixel",
        androidVersion: "17",
        supervisionMode: "full",
      },
      ...overrides,
    }),
  });
}

function claimDependencies(
  overrides: Partial<DeviceClaimDependencies> = {},
): DeviceClaimDependencies {
  return {
    requireDeviceIdentity: async () => ({
      userId: "51000000-0000-4000-8000-000000000101",
      accessToken: "device-token",
    }),
    digestPairingCode: async (code) => `digest:${code}`,
    validateP256Spki: async () => true,
    recordClaimFailure: async () => ({ failedAttempts: 1, invalidated: false }),
    claimDeviceAtomic: async () => ({
      deviceId: "54000000-0000-4000-8000-000000000001",
      familyId: "52000000-0000-4000-8000-000000000001",
      childId: "53000000-0000-4000-8000-000000000001",
    }),
    ...overrides,
  };
}

Deno.test("device-claim requires the caller's separate child-device identity", async () => {
  const response = await handleDeviceClaim(
    claimRequest(),
    claimDependencies({
      requireDeviceIdentity: async () => {
        throw new HarborAuthError("AUTH_REQUIRED", 401, "Device authentication is required");
      },
    }),
  );

  assertEquals(response.status, 401);
  assertEquals((await jsonBody(response)).code, "AUTH_REQUIRED");
});

Deno.test("device-claim rejects a non-six-digit code", async () => {
  const response = await handleDeviceClaim(
    claimRequest({ code: "12345" }),
    claimDependencies(),
  );

  assertEquals(response.status, 400);
  assertEquals((await jsonBody(response)).code, "VALIDATION_FAILED");
});

Deno.test("device-claim records a matched-token failure for malformed P-256 SPKI", async () => {
  let failedDigest: string | undefined;
  const response = await handleDeviceClaim(
    claimRequest({ publicKeySpki: "not-a-p256-spki" }),
    claimDependencies({
      validateP256Spki: async () => false,
      recordClaimFailure: async (codeDigest) => {
        failedDigest = codeDigest;
        return { failedAttempts: 1, invalidated: false };
      },
    }),
  );

  assertEquals(response.status, 400);
  assertEquals((await jsonBody(response)).code, "VALIDATION_FAILED");
  assertEquals(failedDigest, "digest:042381");
});

Deno.test("device-claim passes only the digest and bound Auth identity to atomic persistence", async () => {
  let persisted: DeviceClaimPersistenceInput | undefined;
  const response = await handleDeviceClaim(
    claimRequest(),
    claimDependencies({
      claimDeviceAtomic: async (input) => {
        persisted = input;
        return {
          deviceId: "54000000-0000-4000-8000-000000000001",
          familyId: "52000000-0000-4000-8000-000000000001",
          childId: "53000000-0000-4000-8000-000000000001",
        };
      },
    }),
  );

  assertEquals(response.status, 200);
  assert(persisted);
  assertEquals(persisted.authUserId, "51000000-0000-4000-8000-000000000101");
  assertEquals(persisted.codeDigest, "digest:042381");
  assertEquals(persisted.publicKeySpki, "valid-p256-spki");
  assertEquals(persisted.displayName, "Child phone");
  assertEquals(persisted.supervisionMode, "full");
  assertEquals("code" in (persisted as unknown as Record<string, unknown>), false);
});

Deno.test("device-claim returns the bound device, family, and child identifiers", async () => {
  const response = await handleDeviceClaim(claimRequest(), claimDependencies());

  assertEquals(response.status, 200);
  const body = await jsonBody(response);
  assertEquals(body.deviceId, "54000000-0000-4000-8000-000000000001");
  assertEquals(body.familyId, "52000000-0000-4000-8000-000000000001");
  assertEquals(body.childId, "53000000-0000-4000-8000-000000000001");
});

Deno.test("device-claim rejects an already-bound Auth identity without creating another device", async () => {
  let calls = 0;
  const response = await handleDeviceClaim(
    claimRequest(),
    claimDependencies({
      claimDeviceAtomic: async () => {
        calls += 1;
        throw new HarborAuthError("FORBIDDEN", 403, "Device identity is already bound");
      },
    }),
  );

  assertEquals(calls, 1);
  assertEquals(response.status, 403);
  assertEquals((await jsonBody(response)).code, "FORBIDDEN");
});

Deno.test("device-claim counts metadata and rejected identity failures for a matched code", async () => {
  let failures = 0;
  const persistence = { recordClaimFailure: async () => ({ failedAttempts: ++failures, invalidated: failures >= 5 }) };
  for (let attempt = 0; attempt < 5; attempt++) {
    assertEquals((await handleDeviceClaim(claimRequest({ device: { displayName: "", supervisionMode: "full" } }), claimDependencies(persistence))).status, 400);
  }
  assertEquals(failures, 5);
  const response = await handleDeviceClaim(claimRequest(), claimDependencies({ ...persistence, claimDeviceAtomic: async () => { throw new HarborAuthError("FORBIDDEN",403,"identity already bound"); } }));
  assertEquals(response.status, 403);
  assertEquals(failures, 6);
});
