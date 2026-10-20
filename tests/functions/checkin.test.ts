import { strict as assert } from "node:assert";
import fixture from "../../apps/parent-android/app/src/test/resources/checkin-reply-v1.json" with {
  type: "json",
};
import {
  type CheckInEndpointDependencies,
  createCheckInStateHandler,
  createReadCheckInsHandler,
  createReplyCheckInHandler,
  createRequestCheckInHandler,
} from "../../supabase/functions/_shared/checkin.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
import { sha256Hex } from "../../supabase/functions/_shared/crypto.ts";
const now = Date.parse("2026-10-20T16:00:00Z");
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
function deps(
  overrides: Partial<CheckInEndpointDependencies> = {},
): CheckInEndpointDependencies {
  return {
    now: () => now,
    requireProof: async () => ctx,
    requireParent: async () => parent,
    sha256: sha256Hex,
    requestCheckIn: async () => ({
      requestId: fixture.requestId,
      requestedAt: "2026-10-20T16:00:00Z",
      expiresAt: "2026-10-21T16:00:00Z",
      created: true,
    }),
    readCheckIns: async () => ({ checkins: [] }),
    readChildState: async () => ({ pending: null, recent: [] }),
    replyCheckIn: async () => ({
      confirmed: true,
      receivedAt: "2026-10-20T16:00:01Z",
    }),
    ...overrides,
  };
}
const req = (body: unknown) =>
  new Request("https://harbor.test/checkin", {
    method: "POST",
    body: JSON.stringify(body),
  });
const target = { version: 1, deviceId: ctx.deviceId };

Deno.test("parent request needs a current session and names only a device", async () => {
  let calls = 0;
  const h = createRequestCheckInHandler(deps({
    requestCheckIn: async (p, id) => {
      calls++;
      assert.deepEqual(p, parent);
      assert.equal(id, ctx.deviceId);
      return {
        requestId: fixture.requestId,
        requestedAt: "2026-10-20T16:00:00Z",
        expiresAt: "2026-10-21T16:00:00Z",
        created: true,
      };
    },
  }));
  assert.equal((await h(req(target))).status, 200);
  assert.equal(calls, 1);
  for (const bad of [{ version: 1 }, { ...target, note: "hi" }, { ...target, deviceId: "x" }]) {
    assert.equal((await h(req(bad))).status, 400);
  }
  assert.equal(calls, 1);
  assert.equal(
    (await createRequestCheckInHandler(deps({
      requireParent: async () => ({ ...parent, sessionId: undefined }),
    }))(req(target))).status,
    401,
  );
  for (const code of ["AUTH_REQUIRED", "FORBIDDEN"] as const) {
    const status = code === "AUTH_REQUIRED" ? 401 : 403;
    assert.equal(
      (await createRequestCheckInHandler(deps({
        requireParent: async () => {
          throw new HarborAuthError(code, status, "denied");
        },
        requestCheckIn: async () => {
          throw Error("must not run");
        },
      }))(req(target))).status,
      status,
    );
  }
});
Deno.test("parent read is bound to the verified session", async () => {
  let seen = "";
  const h = createReadCheckInsHandler(deps({
    readCheckIns: async (p, id) => {
      assert.deepEqual(p, parent);
      seen = id;
      return { checkins: [] };
    },
  }));
  const r = await h(req(target));
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("cache-control"), "no-store");
  assert.equal(seen, ctx.deviceId);
  assert.equal((await h(req({ ...target, familyId: ctx.familyId }))).status, 400);
});
Deno.test("child ops use fixed operation names and hash the exact bytes", async () => {
  const operations: string[] = [];
  const d = deps({
    requireProof: async (_r, op) => {
      operations.push(op);
      return ctx;
    },
    replyCheckIn: async (c, v, hash) => {
      assert.deepEqual(c, ctx);
      assert.deepEqual(v, fixture);
      assert.equal(hash, await sha256Hex(body));
      return { confirmed: true, receivedAt: "2026-10-20T16:00:01Z" };
    },
  });
  const body = JSON.stringify(fixture) + " ";
  assert.equal(
    (await createReplyCheckInHandler(d)(
      new Request("https://harbor.test/checkin", { method: "POST", body }),
    )).status,
    200,
  );
  assert.equal(
    (await createCheckInStateHandler(d)(req({ version: 1 }))).status,
    200,
  );
  assert.deepEqual(operations, ["reply-checkin", "get-checkin-state"]);
  assert.equal(
    (await createCheckInStateHandler(d)(req({ version: 1, deviceId: ctx.deviceId }))).status,
    400,
  );
});
Deno.test("child reply rejects free text, SOS and authority before the database", async () => {
  let calls = 0;
  const h = createReplyCheckInHandler(deps({
    replyCheckIn: async () => {
      calls++;
      throw Error("must not write");
    },
  }));
  for (const extra of [{ text: "hello" }, { note: "x" }, { deviceId: ctx.deviceId }, { sos: true }]) {
    assert.equal((await h(req({ ...fixture, ...extra }))).status, 400);
  }
  for (const preset of ["help", "sos", "emergency", "OK"]) {
    assert.equal((await h(req({ ...fixture, preset }))).status, 400);
  }
  assert.equal((await h(req({ ...fixture, fix: { ...fixture.fix, latitude: 99 } }))).status, 400);
  assert.equal((await h(req({ ...fixture, fix: { ...fixture.fix, at: "2026-10-20T16:30:00Z" } }))).status, 400);
  assert.equal(calls, 0);
});
Deno.test("child proof errors never reach persistence", async () => {
  for (
    const [code, status] of [["AUTH_REQUIRED", 401], ["DEVICE_REVOKED", 403], ["REPLAY_REJECTED", 409]] as const
  ) {
    let calls = 0;
    const r = await createReplyCheckInHandler(deps({
      requireProof: async () => {
        throw new HarborAuthError(code, status, "denied");
      },
      replyCheckIn: async () => {
        calls++;
        throw Error("must not write");
      },
    }))(req(fixture));
    assert.equal(r.status, status);
    assert.equal(calls, 0);
  }
});
Deno.test("domain conflicts surface and methods are restricted", async () => {
  const r = await createReplyCheckInHandler(deps({
    replyCheckIn: async () => {
      throw { code: "P0001", message: "IDEMPOTENCY_CONFLICT" };
    },
  }))(req(fixture));
  assert.equal(r.status, 409);
  const closed = await createReplyCheckInHandler(deps({
    replyCheckIn: async () => {
      throw { code: "22023", message: "check-in closed" };
    },
  }))(req(fixture));
  assert.equal(closed.status, 400);
  assert.equal(
    (await createReplyCheckInHandler(deps())(new Request("https://harbor.test/checkin"))).status,
    405,
  );
});
