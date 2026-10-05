import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import {
  assertRestrictedAcl,
  cleanupPhase,
  discoverCleanupFixture,
  finalizeCleanup,
  type FixtureManifest,
  rollbackPreparation,
} from "../hosted/notification-acceptance.ts";
Deno.test("failed browser removal retains domain and Auth recovery identities", async () => {
  const calls: string[] = [];
  const result = await finalizeCleanup(manifest, [
    {
      name: "browser",
      run: async () => {
        calls.push("browser");
        throw Error("failure");
      },
    },
    {
      name: "private-rows",
      run: async () => {
        calls.push("private-rows");
      },
    },
    ...["family", "child-auth", "parent-auth"].map((name) => ({
      name,
      run: async () => {
        calls.push(name);
      },
    })),
  ]);
  assertEquals(calls, ["browser", "private-rows"]);
  assertEquals(result.complete, false);
  assertEquals(result.failed, [
    "browser",
    "family",
    "child-auth",
    "parent-auth",
  ]);
});
const manifest: FixtureManifest = {
  runId: "11111111-1111-4111-8111-111111111111",
  projectRef: "bfvybxkjxilntjgndsrm",
  parentUserId: "22222222-2222-4222-8222-222222222222",
  familyId: "33333333-3333-4333-8333-333333333333",
  childId: "44444444-4444-4444-8444-444444444444",
  expectedDesiredStateVersion: 0,
  subscriptionIds: [],
};
const deviceId = "55555555-5555-4555-8555-555555555555",
  authUserId = "66666666-6666-4666-8666-666666666666";
const discovery = {
  projectRef: manifest.projectRef,
  runId: manifest.runId,
  familyId: manifest.familyId,
  childId: manifest.childId,
  complete: true,
  devices: [{ deviceId, authUserId }],
};
Deno.test("cleanup discovers a claimed binding even when change-state never ran", () => {
  assertThrows(() => discoverCleanupFixture(manifest, undefined));
  const found = discoverCleanupFixture(manifest, discovery);
  assertEquals(found.deviceId, deviceId);
  assertEquals(found.childAuthUserId, authUserId);
  assertThrows(() =>
    discoverCleanupFixture({ ...manifest, deviceId }, {
      ...discovery,
      devices: [],
    })
  );
  assertThrows(() =>
    discoverCleanupFixture(manifest, { ...discovery, devices: [{ deviceId }] })
  );
});
Deno.test("unknown family creation preserves parent and exact-run recovery mapping", async () => {
  const calls: string[] = [];
  const result = await rollbackPreparation({
    runId: manifest.runId,
    projectRef: manifest.projectRef,
    parentUserId: manifest.parentUserId,
  }, {
    family: async (id) => {
      calls.push(id);
    },
    parent: async (id) => {
      calls.push(id);
    },
  });
  assertEquals(result.complete, false);
  assertEquals(calls, []);
});
Deno.test("rollback deletes only checkpointed family and retains parent when deletion fails", async () => {
  const calls: string[] = [];
  const result = await rollbackPreparation({ ...manifest }, {
    family: async (id) => {
      calls.push(id);
      throw Error("private");
    },
    parent: async (id) => {
      calls.push(id);
    },
  });
  assertEquals(calls, [manifest.familyId]);
  assertEquals(result.complete, false);
});
Deno.test("revoke phase performs no destructive cleanup and finalization requires both exact denials", async () => {
  const fixture = discoverCleanupFixture(manifest, discovery),
    calls: string[] = [];
  const deps = {
    revoke: async () => {
      calls.push("revoke");
    },
    finalize: async () => {
      calls.push("finalize");
    },
  };
  await cleanupPhase(fixture, "revoke", undefined, deps);
  assertEquals(calls, ["revoke"]);
  await assertRejects(() =>
    cleanupPhase(fixture, "finalize", {
      deviceId,
      sync: { status: 401, code: "DEVICE_REVOKED" },
      registration: { status: 403, code: "DEVICE_REVOKED" },
    }, deps)
  );
  assertEquals(calls, ["revoke"]);
  await cleanupPhase(fixture, "finalize", {
    deviceId,
    sync: { status: 403, code: "DEVICE_REVOKED" },
    registration: { status: 403, code: "DEVICE_REVOKED" },
  }, deps);
  assertEquals(calls, ["revoke", "finalize"]);
});
Deno.test("an explicit extra-reader ACL on a sensitive file is refused", () => {
  const good = {
    owner: "operator",
    current: "operator",
    protected: true,
    readers: ["operator", "S-1-5-18"],
  };
  assertRestrictedAcl(good);
  assertThrows(() =>
    assertRestrictedAcl({ ...good, readers: [...good.readers, "Everyone"] })
  );
  assertThrows(() => assertRestrictedAcl({ ...good, protected: false }));
});
