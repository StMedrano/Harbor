import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  registerDeviceFcmAtomicWithQuery,
  revokeDeviceAtomicWithQuery,
  syncDeviceAtomicWithQuery,
  updateDeviceDesiredStateAtomicWithQuery,
  type DeviceSyncAtomicInput,
} from "../../supabase/functions/_shared/clients.ts";

const deviceId = "11111111-1111-4111-8111-111111111111";
const familyId = "22222222-2222-4222-8222-222222222222";
const actorUserId = "33333333-3333-4333-8333-333333333333";

Deno.test("device sync persistence maps private SQL row to the signed-sync response", async () => {
  const input: DeviceSyncAtomicInput = {
    deviceId,
    acknowledgedDesiredStateVersion: 6,
    appliedCommandIds: ["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"],
  };

  const result = await syncDeviceAtomicWithQuery(input, async (actual) => {
    assertEquals(actual, input);
    return [{
      desired_state: { paused: true },
      desired_state_version: 7,
      commands: [{
        id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        kind: "sync",
        idempotencyKey: "wake-7",
        createdAt: "2026-10-04T14:00:00Z",
        expiresAt: null,
        payload: {},
      }],
    }];
  });

  assertEquals(result, {
    desiredState: { paused: true },
    desiredStateVersion: 7,
    commands: [{
      id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      kind: "sync",
      idempotencyKey: "wake-7",
      createdAt: "2026-10-04T14:00:00Z",
      expiresAt: null,
      payload: {},
    }],
  });
});

Deno.test("device sync persistence fails closed when the private helper returns no row", async () => {
  await assertRejects(
    () => syncDeviceAtomicWithQuery({ deviceId, acknowledgedDesiredStateVersion: null, appliedCommandIds: [] }, async () => []),
    Error,
    "Device sync returned no result",
  );
});

Deno.test("desired-state persistence binds family and actor and returns the new monotonic version", async () => {
  const result = await updateDeviceDesiredStateAtomicWithQuery(
    { deviceId, familyId, actorUserId, desiredState: { paused: true }, expectedVersion: 4 },
    async (actual) => {
      assertEquals(actual, {
        deviceId,
        familyId,
        actorUserId,
        desiredState: { paused: true },
        expectedVersion: 4,
      });
      return [{ desired_state_version: 5 }];
    },
  );
  assertEquals(result.desiredStateVersion, 5);
});

Deno.test("FCM persistence reports successful private token rotation", async () => {
  const result = await registerDeviceFcmAtomicWithQuery(
    { deviceId, token: "fcm-token" },
    async (actual) => {
      assertEquals(actual.token, "fcm-token");
      return [{ registered: true }];
    },
  );
  assertEquals(result, true);
});

Deno.test("revocation persistence fails closed unless the private helper confirms revocation", async () => {
  await assertRejects(
    () => revokeDeviceAtomicWithQuery(
      { deviceId, familyId, actorUserId },
      async () => [{ revoked: false }],
    ),
    Error,
    "Device revocation was not confirmed",
  );
});
