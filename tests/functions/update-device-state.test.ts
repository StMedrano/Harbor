import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  createUpdateDeviceStateHandler,
  type UpdateDeviceStateDeps,
} from "../../supabase/functions/update-device-state/index.ts";

const deviceId = "11111111-1111-4111-8111-111111111111";
const familyId = "22222222-2222-4222-8222-222222222222";
const parent = {
  userId: "33333333-3333-4333-8333-333333333333",
  accessToken: "parent-token",
  aal: "aal1" as const,
  amr: [],
};

function request(body: Record<string, unknown> = {
  deviceId,
  familyId,
  desiredState: { paused: true },
  expectedVersion: 4,
}) {
  return new Request("https://harbor.test/functions/v1/update-device-state", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

Deno.test("update-device-state requires current owner/parent family authorization before mutation", async () => {
  let updated = false;
  const deps = {
    requireParent: async () => parent,
    requireFamilyRole: async () => { throw new Error("FORBIDDEN"); },
    updateDesiredState: async () => { updated = true; return { desiredStateVersion: 5 }; },
  } as UpdateDeviceStateDeps;

  await assertRejects(() => createUpdateDeviceStateHandler(deps)(request()), Error, "FORBIDDEN");
  assertEquals(updated, false);
});

Deno.test("update-device-state binds device family actor state and expected version in persistence", async () => {
  let actual: unknown;
  const deps = {
    requireParent: async () => parent,
    requireFamilyRole: async (_ctx: unknown, id: string, roles: string[]) => {
      assertEquals(id, familyId);
      assertEquals(roles, ["owner", "parent"]);
    },
    updateDesiredState: async (input: unknown) => {
      actual = input;
      return { desiredStateVersion: 5 };
    },
  } as UpdateDeviceStateDeps;

  const response = await createUpdateDeviceStateHandler(deps)(request());
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { desiredStateVersion: 5 });
  assertEquals(actual, {
    deviceId,
    familyId,
    actorUserId: parent.userId,
    desiredState: { paused: true },
    expectedVersion: 4,
  });
});

Deno.test("update-device-state rejects malformed desired-state/version input before mutation", async () => {
  let updated = false;
  const deps = {
    requireParent: async () => parent,
    requireFamilyRole: async () => undefined,
    updateDesiredState: async () => { updated = true; return { desiredStateVersion: 1 }; },
  } as UpdateDeviceStateDeps;

  await assertRejects(
    () => createUpdateDeviceStateHandler(deps)(request({ deviceId, familyId, desiredState: null, expectedVersion: -1 })),
    Error,
    "VALIDATION_FAILED",
  );
  assertEquals(updated, false);
});
