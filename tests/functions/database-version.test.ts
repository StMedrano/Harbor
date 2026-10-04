import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { syncDeviceAtomicWithQuery, updateDeviceDesiredStateAtomicWithQuery } from "../../supabase/functions/_shared/clients.ts";
Deno.test("database bigint versions retain numeric API contract and reject unsafe values", async () => {
  const syncInput = { deviceId: "fixture", acknowledgedDesiredStateVersion: null, appliedCommandIds: [] };
  const stateInput = { deviceId: "fixture", familyId: "fixture", actorUserId: "fixture", desiredState: {}, expectedVersion: 0 };
  const postgresVersion = "1" as unknown as number;
  assertEquals((await syncDeviceAtomicWithQuery(syncInput, async () => [{ desired_state: {}, desired_state_version: postgresVersion, commands: [] }])).desiredStateVersion, 1);
  assertEquals((await updateDeviceDesiredStateAtomicWithQuery(stateInput, async () => [{ desired_state_version: postgresVersion }])).desiredStateVersion, 1);
  await assertRejects(() => syncDeviceAtomicWithQuery(syncInput, async () => [{ desired_state: {}, desired_state_version: "9007199254740992" as unknown as number, commands: [] }]));
});
