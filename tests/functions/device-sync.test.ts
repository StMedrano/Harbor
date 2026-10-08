import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { createDeviceSyncHandler, type DeviceSyncDeps } from "../../supabase/functions/device-sync/index.ts";

const device = {
  deviceId: "11111111-1111-4111-8111-111111111111",
  familyId: "22222222-2222-4222-8222-222222222222",
  childId: "33333333-3333-4333-8333-333333333333",
  authUserId: "44444444-4444-4444-8444-444444444444",
};

function request(body: unknown) {
  return new Request("https://example.test/device-sync", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

Deno.test("device-sync requires proof of possession before reading private state", async () => {
  let read = false;
  const deps = {
    requireDeviceProof: async () => { throw new Error("PROOF_REQUIRED"); },
    syncDevice: async () => { read = true; return { desiredState: {}, desiredStateVersion: 1, commands: [] }; },
  } as unknown as DeviceSyncDeps;
  const handler = createDeviceSyncHandler(deps);
  await assertRejects(() => handler(request({})), Error, "PROOF_REQUIRED");
  assertEquals(read, false);
});

Deno.test("device-sync returns current desired state and only live commands", async () => {
  const deps = {
    requireDeviceProof: async () => device,
    syncDevice: async () => ({
      desiredState: { paused: true },
      desiredStateVersion: 7,
      commands: [{ id: "cmd-1", kind: "sync", idempotencyKey: "wake-7", createdAt: "2026-10-04T11:00:00Z" }],
    }),
  } as unknown as DeviceSyncDeps;
  const response = await createDeviceSyncHandler(deps)(request({ acknowledgedDesiredStateVersion: 6 }));
  assertEquals(await response.json(), {
    desiredState: { paused: true },
    desiredStateVersion: 7,
    commands: [{ id: "cmd-1", kind: "sync", idempotencyKey: "wake-7", createdAt: "2026-10-04T11:00:00Z" }],
  });
});

Deno.test("device-sync forwards duplicate acknowledgements idempotently", async () => {
  let calls = 0;
  const deps = {
    requireDeviceProof: async () => device,
    syncDevice: async (_ctx: unknown, input: unknown) => {
      calls++;
      assertEquals(input, { acknowledgedDesiredStateVersion: 7, appliedCommandIds: ["cmd-1", "cmd-1"] });
      return { desiredState: {}, desiredStateVersion: 7, commands: [] };
    },
  } as unknown as DeviceSyncDeps;
  const handler = createDeviceSyncHandler(deps);
  assertEquals((await handler(request({ acknowledgedDesiredStateVersion: 7, appliedCommandIds: ["cmd-1", "cmd-1"] }))).status, 200);
  assertEquals(calls, 1);
});
