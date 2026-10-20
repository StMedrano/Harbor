import { strict as assert } from "node:assert";
import fixture from "../../apps/parent-android/app/src/test/resources/family-map-reply-v1.json" with {
  type: "json",
};
import {
  createFamilyMapHandler,
  createGetFamilyMapSettingsHandler,
  createReportParentLocationHandler,
  createSetFamilyMapSettingsHandler,
  createStopParentLocationHandler,
  type FamilyMapEndpointDependencies,
} from "../../supabase/functions/_shared/familymap.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
const now = Date.parse("2026-10-20T16:00:00Z");
const fam = "3b8d2c40-7e19-4a63-9f05-1c3e6a8b2d74";
const ctx = {
  deviceId: "22222222-2222-4222-8222-222222222222",
  familyId: fam,
  childId: "44444444-4444-4444-8444-444444444444",
  authUserId: "55555555-5555-4555-8555-555555555555",
};
const parent = {
  userId: "66666666-6666-4666-8666-666666666666",
  sessionId: "77777777-7777-4777-8777-777777777777",
  accessToken: "test-only",
  aal: "aal1" as const,
  amr: [],
};
const receipt = { confirmed: true as const, receivedAt: "2026-10-20T16:00:01Z" };
function deps(o: Partial<FamilyMapEndpointDependencies> = {}): FamilyMapEndpointDependencies {
  return {
    now: () => now,
    requireProof: async () => ctx,
    requireParent: async () => parent,
    readFamilyMap: async () => fixture as never,
    reportParentLocation: async () => receipt,
    stopParentLocation: async () => receipt,
    readSettings: async () => ({ childrenCanView: true, mySharing: null }),
    writeSettings: async (_p, _f, v) => ({ childrenCanView: v, mySharing: null }),
    ...o,
  };
}
const req = (body: unknown) =>
  new Request("https://harbor.test/map", { method: "POST", body: JSON.stringify(body) });
const report = {
  version: 1,
  familyId: fam,
  label: "Alex",
  fix: { at: "2026-10-20T15:59:00Z", latitude: 41.5, longitude: -87.6, accuracyM: 20 },
};

Deno.test("child read uses its fixed operation name and returns only the shared shape", async () => {
  const ops: string[] = [];
  const h = createFamilyMapHandler(deps({
    requireProof: async (_r, op) => {
      ops.push(op);
      return ctx;
    },
  }));
  const r = await h(req({ version: 1 }));
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("cache-control"), "no-store");
  assert.deepEqual(await r.json(), fixture);
  assert.deepEqual(ops, ["get-family-map"]);
  for (const bad of [{}, { version: 1, familyId: fam }, { version: 2 }]) {
    assert.equal((await h(req(bad))).status, 400);
  }
});
Deno.test("child read is refused without a valid device proof", async () => {
  let reads = 0;
  const h = createFamilyMapHandler(deps({
    requireProof: async () => {
      throw new HarborAuthError("AUTH_REQUIRED", 401, "denied");
    },
    readFamilyMap: async () => {
      reads++;
      return fixture as never;
    },
  }));
  assert.equal((await h(req({ version: 1 }))).status, 401);
  assert.equal(reads, 0);
});
Deno.test("parent report is bound to the verified session and a fresh place", async () => {
  let seen: unknown;
  const h = createReportParentLocationHandler(deps({
    reportParentLocation: async (p, r) => {
      assert.deepEqual(p, parent);
      seen = r;
      return receipt;
    },
  }));
  assert.equal((await h(req(report))).status, 200);
  assert.deepEqual(seen, report);
  seen = undefined;
  const old = { ...report, fix: { ...report.fix, at: "2026-10-20T15:00:00Z" } };
  for (const bad of [old, { ...report, label: "" }, { ...report, extra: 1 }, { version: 1 }]) {
    assert.equal((await h(req(bad))).status, 400);
  }
  assert.equal(seen, undefined);
  assert.equal(
    (await createReportParentLocationHandler(deps({
      requireParent: async () => ({ ...parent, sessionId: undefined }),
    }))(req(report))).status,
    401,
  );
});
Deno.test("stop, read and change settings all name only a family", async () => {
  const calls: string[] = [];
  const d = deps({
    stopParentLocation: async (_p, id) => {
      calls.push("stop:" + id);
      return receipt;
    },
    readSettings: async (_p, id) => {
      calls.push("read:" + id);
      return { childrenCanView: true, mySharing: { label: "Alex", lastAt: "2026-10-20T15:00:00Z" } };
    },
    writeSettings: async (_p, id, v) => {
      calls.push(`write:${id}:${v}`);
      return { childrenCanView: v, mySharing: null };
    },
  });
  const target = { version: 1, familyId: fam };
  assert.equal((await createStopParentLocationHandler(d)(req(target))).status, 200);
  assert.equal((await createGetFamilyMapSettingsHandler(d)(req(target))).status, 200);
  const w = await createSetFamilyMapSettingsHandler(d)(req({ ...target, childrenCanView: false }));
  assert.equal(w.status, 200);
  assert.deepEqual(await w.json(), { childrenCanView: false, mySharing: null });
  assert.deepEqual(calls, [`stop:${fam}`, `read:${fam}`, `write:${fam}:false`]);
  assert.equal((await createStopParentLocationHandler(d)(req({ version: 1, familyId: "x" }))).status, 400);
  assert.equal((await createSetFamilyMapSettingsHandler(d)(req({ ...target, childrenCanView: "no" }))).status, 400);
  assert.equal((await createSetFamilyMapSettingsHandler(d)(req(target))).status, 400);
});
Deno.test("a forbidden parent never reaches the database", async () => {
  let ran = 0;
  const forbidden = async () => {
    throw new HarborAuthError("FORBIDDEN", 403, "no");
  };
  const d = deps({
    requireParent: forbidden,
    reportParentLocation: async () => { ran++; return receipt; },
    stopParentLocation: async () => { ran++; return receipt; },
    readSettings: async () => { ran++; return { childrenCanView: true, mySharing: null }; },
    writeSettings: async () => { ran++; return { childrenCanView: true, mySharing: null }; },
  });
  const target = { version: 1, familyId: fam };
  assert.equal((await createReportParentLocationHandler(d)(req(report))).status, 403);
  assert.equal((await createStopParentLocationHandler(d)(req(target))).status, 403);
  assert.equal((await createGetFamilyMapSettingsHandler(d)(req(target))).status, 403);
  assert.equal((await createSetFamilyMapSettingsHandler(d)(req({ ...target, childrenCanView: true }))).status, 403);
  assert.equal(ran, 0);
});
