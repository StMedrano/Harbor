import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { createGetDeviceControlsHandler, type GetDeviceControlsDeps } from "../../supabase/functions/get-device-controls/index.ts";
import { createUpdateDeviceStateHandler, validateControls } from "../../supabase/functions/update-device-state/index.ts";

const FAMILY = "22222222-2222-4222-8222-222222222222";
const parent = { userId: "44444444-4444-4444-8444-444444444444" } as never;
const post = (body: unknown) => new Request("https://x.test/f", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });

Deno.test("get-device-controls requires a parent and family membership before reading", async () => {
  let read = false;
  const deps: GetDeviceControlsDeps = {
    requireParent: async () => { throw new Error("AUTH"); },
    requireFamilyRole: async () => {},
    listStates: async () => { read = true; return []; },
  };
  await assertRejects(() => createGetDeviceControlsHandler(deps)(post({ familyId: FAMILY })), Error, "AUTH");
  const deps2 = { ...deps, requireParent: async () => parent, requireFamilyRole: async () => { throw new Error("NOT_MEMBER"); } };
  await assertRejects(() => createGetDeviceControlsHandler(deps2)(post({ familyId: FAMILY })), Error, "NOT_MEMBER");
  assertEquals(read, false);
});

Deno.test("get-device-controls returns the family's device states for the calling parent", async () => {
  const seen: unknown[] = [];
  const deps: GetDeviceControlsDeps = {
    requireParent: async () => parent,
    requireFamilyRole: async () => {},
    listStates: async (input) => { seen.push(input); return [{ deviceId: "d", childId: "c", desiredState: { controls: { paused: true } }, desiredStateVersion: 2, acknowledgedVersion: 1 }]; },
  };
  const res = await createGetDeviceControlsHandler(deps)(post({ familyId: FAMILY }));
  assertEquals(res.status, 200);
  assertEquals((await res.json()).devices[0].desiredStateVersion, 2);
  assertEquals(seen, [{ familyId: FAMILY, actorUserId: (parent as { userId: string }).userId }]);
  for (const bad of [{}, { familyId: "nope" }, null]) {
    assertEquals((await createGetDeviceControlsHandler(deps)(post(bad))).status, 400);
  }
});

Deno.test("update-device-state accepts valid controls and rejects malformed ones", async () => {
  const saved: unknown[] = [];
  const handler = createUpdateDeviceStateHandler({
    requireParent: async () => parent,
    requireFamilyRole: async () => {},
    updateDesiredState: async (input) => { saved.push(input.desiredState); return { desiredStateVersion: 1 }; },
  });
  const base = { deviceId: "d", familyId: FAMILY, expectedVersion: 0 };
  const ok = await handler(post({ ...base, desiredState: { controls: { paused: true, bedtime: { enabled: true, start: "21:00", end: "07:00" }, requireLocation: false } } }));
  assertEquals(ok.status, 200);
  const bads: unknown[] = [
    { controls: "x" }, { controls: { paused: "yes" } }, { controls: { admin: true } },
    { controls: { bedtime: { enabled: true, start: "25:00", end: "07:00" } } },
    { controls: { bedtime: { enabled: true, start: "21:00", end: "21:00" } } },
    { controls: { bedtime: { enabled: "y", start: "21:00", end: "07:00" } } },
  ];
  for (const desiredState of bads) {
    await assertRejects(() => handler(post({ ...base, desiredState })), Error, "VALIDATION_FAILED");
  }
  assertEquals(saved.length, 1);
  validateControls(undefined);
});
