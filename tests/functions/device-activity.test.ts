import { strict as assert } from "node:assert";
import fixture from "../../apps/parent-android/app/src/test/resources/activity-report-v1.json" with { type: "json" };
import controlsFixture from "../../apps/parent-android/app/src/test/resources/controls-v1.json" with { type: "json" };
import {
  type ActivityEndpointDependencies,
  createActivityCheckpointHandler,
  createClearActivityHandler,
  createGetActivityHandler,
  createGetDeviceControlsHandler,
  createReportActivityHandler,
} from "../../supabase/functions/_shared/activity.ts";
import { createUpdateDeviceStateHandler } from "../../supabase/functions/update-device-state/index.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
import { sha256Hex } from "../../supabase/functions/_shared/crypto.ts";

const now = Date.parse("2026-10-20T18:00:30Z");
const ctx = {
  deviceId: "22222222-2222-4222-8222-222222222222",
  familyId: "33333333-3333-4333-8333-333333333333",
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
const none = { state: "none" as const, controlsOn: null, policyVersion: null, observedAt: null, receivedAt: null, events: [], truncated: false };
const window = { version: 1, from: "2026-10-20T05:00:00Z", to: "2026-10-21T05:00:00Z" };
function deps(overrides: Partial<ActivityEndpointDependencies> = {}): ActivityEndpointDependencies {
  const ok = (sequence: number) => ({ confirmed: true as const, sequence, receivedAt: new Date(now).toISOString() });
  return {
    now: () => now,
    requireProof: async () => ctx,
    requireParent: async () => parent,
    sha256: sha256Hex,
    writeActivity: async (_c, r) => ok(r.sequence),
    clearActivity: async (_c, c) => ok(c.sequence),
    readActivity: async () => none,
    readActivityCheckpoint: async () => ({ sequence: 0, epochId: null }),
    readControls: async () => ({ controls: null, desiredStateVersion: 0, acknowledgedVersion: 0 }),
    ...overrides,
  };
}
const req = (body: unknown) => new Request("https://harbor.test/activity", { method: "POST", body: JSON.stringify(body) });

Deno.test("activity report binds verified context and hashes the exact raw bytes", async () => {
  let written = false;
  const body = JSON.stringify(fixture) + " ";
  const response = await createReportActivityHandler(deps({
    requireProof: async (r, operation) => {
      assert.equal(operation, "report-device-activity");
      assert.equal(await r.clone().text(), body);
      return ctx;
    },
    writeActivity: async (c, v, hash) => {
      written = true;
      assert.deepEqual(c, ctx);
      assert.deepEqual(v, fixture);
      assert.equal(hash, await sha256Hex(body));
      return { confirmed: true, sequence: v.sequence, receivedAt: new Date(now).toISOString() };
    },
  }))(new Request("https://harbor.test/activity", { method: "POST", body }));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.ok(written);
});

Deno.test("activity proof errors never reach persistence", async () => {
  for (const [code, status] of [["AUTH_REQUIRED", 401], ["FORBIDDEN", 403], ["REPLAY_REJECTED", 409], ["DEVICE_REVOKED", 403]] as const) {
    let calls = 0;
    const response = await createReportActivityHandler(deps({
      requireProof: async () => {
        throw new HarborAuthError(code, status, "denied");
      },
      writeActivity: async () => {
        calls++;
        throw Error("must not write");
      },
    }))(req(fixture));
    assert.equal(response.status, status);
    assert.equal(calls, 0);
  }
});

Deno.test("activity report rejects authority and content fields before the database", async () => {
  let calls = 0;
  const handler = createReportActivityHandler(deps({
    writeActivity: async () => {
      calls++;
      throw Error("must not write");
    },
  }));
  assert.equal((await handler(req({ ...fixture, familyId: ctx.familyId }))).status, 400);
  for (const key of ["title", "text", "url", "windowTitle", "content"]) {
    const bad = structuredClone(fixture) as Record<string, unknown>;
    (bad.events as Record<string, unknown>[])[0][key] = "must never be accepted";
    assert.equal((await handler(req(bad))).status, 400, key);
  }
  assert.equal((await handler(req({ ...structuredClone(fixture), observedAt: "2026-10-20T18:06:00Z" }))).status, 400);
  assert.equal(calls, 0);
});

Deno.test("clear and checkpoint use their own signed operation names", async () => {
  const seen: string[] = [];
  const d = deps({
    requireProof: async (_r, operation) => {
      seen.push(operation);
      return ctx;
    },
  });
  assert.equal((await createClearActivityHandler(d)(req({ version: 1, epochId: fixture.epochId, sequence: 4 }))).status, 200);
  assert.equal((await createActivityCheckpointHandler(d)(req({ version: 1 }))).status, 200);
  assert.deepEqual(seen, ["clear-device-activity", "get-device-activity-checkpoint"]);
  assert.equal((await createActivityCheckpointHandler(d)(req({ version: 1, deviceId: ctx.deviceId }))).status, 400);
});

Deno.test("parent activity read needs a current session and a bounded window", async () => {
  let asked: unknown;
  const ok = createGetActivityHandler(deps({
    readActivity: async (p, deviceId, w) => {
      asked = { p, deviceId, w };
      return none;
    },
  }));
  const response = await ok(req({ ...window, deviceId: ctx.deviceId }));
  assert.equal(response.status, 200);
  assert.deepEqual(asked, { p: parent, deviceId: ctx.deviceId, w: window });
  assert.equal((await ok(req({ ...window, to: "2026-10-23T05:00:00Z", deviceId: ctx.deviceId }))).status, 400);
  assert.equal((await ok(req({ ...window, deviceId: "not-a-device" }))).status, 400);
  const noSession = createGetActivityHandler(deps({ requireParent: async () => ({ ...parent, sessionId: undefined }) }));
  assert.equal((await noSession(req({ ...window, deviceId: ctx.deviceId }))).status, 401);
});

Deno.test("parent controls read is strict about its request", async () => {
  const handler = createGetDeviceControlsHandler(deps({
    readControls: async (_p, deviceId) => {
      assert.equal(deviceId, ctx.deviceId);
      return { controls: controlsFixture as never, desiredStateVersion: 3, acknowledgedVersion: 3 };
    },
  }));
  const response = await handler(req({ version: 1, deviceId: ctx.deviceId }));
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).controls, controlsFixture);
  assert.equal((await handler(req({ version: 1, deviceId: ctx.deviceId, familyId: ctx.familyId }))).status, 400);
});

Deno.test("update-device-state writes only valid controls", async () => {
  let writes = 0;
  const handler = createUpdateDeviceStateHandler({
    requireParent: async () => parent,
    requireFamilyRole: async () => {},
    updateDesiredState: async () => {
      writes++;
      return { desiredStateVersion: 2 };
    },
  });
  const body = (controls: unknown) => req({ deviceId: ctx.deviceId, familyId: ctx.familyId, expectedVersion: 1, desiredState: { controls } });
  assert.equal((await handler(body(controlsFixture))).status, 200);
  for (const bad of [{ ...controlsFixture, webFilter: true }, { ...controlsFixture, blockedApps: ["not a package"] }, null]) {
    await assert.rejects(() => handler(body(bad)), (e: unknown) => e instanceof HarborAuthError && e.status === 400);
  }
  assert.equal((await handler(req({ deviceId: ctx.deviceId, familyId: ctx.familyId, expectedVersion: 1, desiredState: {} }))).status, 200);
  assert.equal(writes, 2);
});
