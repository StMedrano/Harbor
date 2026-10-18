import { strict as assert } from "node:assert";
import fixture from "../../apps/parent-android/app/src/test/resources/alert-report-v1.json" with {
  type: "json",
};
import {
  type AlertEndpointDependencies,
  createAlertCheckpointHandler,
  createClearAlertsHandler,
  createGetAlertsHandler,
  createReportAlertsHandler,
} from "../../supabase/functions/_shared/message-alerts.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
import { sha256Hex } from "../../supabase/functions/_shared/crypto.ts";
const now = Date.parse("2026-10-10T18:00:30Z");
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
const none = {
  state: "none" as const,
  notificationAccess: null,
  smsPermission: null,
  observedAt: null,
  receivedAt: null,
  alerts: [],
  nextBefore: null,
};
function deps(
  overrides: Partial<AlertEndpointDependencies> = {},
): AlertEndpointDependencies {
  return {
    now: () => now,
    requireProof: async () => ctx,
    requireParent: async () => parent,
    sha256: sha256Hex,
    writeAlerts: async (_ctx, report) => ({
      confirmed: true,
      sequence: report.sequence,
      receivedAt: new Date(now).toISOString(),
    }),
    clearAlerts: async (_ctx, clear) => ({
      confirmed: true,
      sequence: clear.sequence,
      receivedAt: new Date(now).toISOString(),
    }),
    readAlerts: async () => none,
    readAlertCheckpoint: async () => ({ sequence: 0, epochId: null }),
    ...overrides,
  };
}
function req(body: unknown) {
  return new Request("https://harbor.test/alerts", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
Deno.test("alert report binds verified context and hashes exact raw bytes", async () => {
  let written = false;
  const body = JSON.stringify(fixture) + " ";
  const response = await createReportAlertsHandler(deps({
    requireProof: async (r, operation) => {
      assert.equal(operation, "report-message-alert");
      assert.equal(await r.clone().text(), body);
      return ctx;
    },
    writeAlerts: async (c, v, hash) => {
      written = true;
      assert.deepEqual(c, ctx);
      assert.deepEqual(v, fixture);
      assert.equal(hash, await sha256Hex(body));
      return {
        confirmed: true,
        sequence: v.sequence,
        receivedAt: new Date(now).toISOString(),
      };
    },
  }))(new Request("https://harbor.test/alerts", { method: "POST", body }));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(written, true);
});
Deno.test("alert proof errors do not call persistence", async () => {
  for (
    const [code, status] of [["AUTH_REQUIRED", 401], ["FORBIDDEN", 403], [
      "REPLAY_REJECTED",
      409,
    ], ["DEVICE_REVOKED", 403]] as const
  ) {
    let calls = 0;
    const response = await createReportAlertsHandler(deps({
      requireProof: async () => {
        throw new HarborAuthError(code, status, "denied");
      },
      writeAlerts: async () => {
        calls++;
        throw Error("must not write");
      },
    }))(req(fixture));
    assert.equal(response.status, status);
    assert.equal(calls, 0);
  }
});
Deno.test("alert report rejects authority, content fields and bad values before the database", async () => {
  let calls = 0;
  const handler = createReportAlertsHandler(deps({
    writeAlerts: async () => {
      calls++;
      throw Error("must not write");
    },
  }));
  assert.equal(
    (await handler(req({ ...fixture, familyId: ctx.familyId }))).status,
    400,
  );
  for (const key of ["text", "sender", "excerpt", "body", "contact"]) {
    const bad = structuredClone(fixture) as Record<string, unknown>;
    (bad.alerts as Record<string, unknown>[])[0][key] = "must never be accepted";
    assert.equal((await handler(req(bad))).status, 400, key);
  }
  const category = structuredClone(fixture) as Record<string, unknown>;
  (category.alerts as Record<string, unknown>[])[0].category = "spam";
  assert.equal((await handler(req(category))).status, 400);
  const future = {
    ...structuredClone(fixture),
    observedAt: "2026-10-10T18:06:00Z",
  };
  assert.equal((await handler(req(future))).status, 400);
  assert.equal(calls, 0);
});
Deno.test("alert report cancels oversized stream before proof and database", async () => {
  let cancelled = false, proofs = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(c) {
      c.enqueue(new Uint8Array(40000));
    },
    cancel() {
      cancelled = true;
    },
  });
  const response = await createReportAlertsHandler(deps({
    requireProof: async () => {
      proofs++;
      return ctx;
    },
  }))(
    new Request("https://harbor.test/alerts", {
      method: "POST",
      body: stream,
    }),
  );
  assert.equal(response.status, 413);
  assert.equal(cancelled, true);
  assert.equal(proofs, 0);
});
Deno.test("alert clear and checkpoint have separate fixed operations", async () => {
  const operations: string[] = [];
  const d = deps({
    requireProof: async (_r, op) => {
      operations.push(op);
      return ctx;
    },
  });
  assert.equal(
    (await createClearAlertsHandler(d)(
      req({ version: 1, epochId: fixture.epochId, sequence: 8 }),
    )).status,
    200,
  );
  const c = await createAlertCheckpointHandler(d)(req({ version: 1 }));
  assert.deepEqual(await c.json(), { sequence: 0, epochId: null });
  assert.deepEqual(operations, [
    "clear-message-alerts",
    "get-message-alert-checkpoint",
  ]);
  assert.equal(
    (await createAlertCheckpointHandler(d)(
      req({ version: 1, deviceId: ctx.deviceId }),
    )).status,
    400,
  );
});
Deno.test("alert parent reads require current verified session, exact device and a valid page", async () => {
  let calls = 0;
  const handler = createGetAlertsHandler(deps({
    readAlerts: async (p, id, page) => {
      calls++;
      assert.deepEqual(p, parent);
      assert.equal(id, ctx.deviceId);
      assert.deepEqual(page, { version: 1, before: null, limit: 20 });
      return none;
    },
  }));
  const ok = { deviceId: ctx.deviceId, version: 1, before: null, limit: 20 };
  assert.equal((await handler(req(ok))).status, 200);
  assert.equal(calls, 1);
  for (const code of ["AUTH_REQUIRED", "FORBIDDEN"] as const) {
    assert.equal(
      (await createGetAlertsHandler(deps({
        requireParent: async () => {
          throw new HarborAuthError(
            code,
            code === "AUTH_REQUIRED" ? 401 : 403,
            "denied",
          );
        },
      }))(req(ok))).status,
      code === "AUTH_REQUIRED" ? 401 : 403,
    );
  }
  assert.equal(
    (await createGetAlertsHandler(deps({
      requireParent: async () => ({ ...parent, sessionId: undefined }),
    }))(req(ok))).status,
    401,
  );
  assert.equal((await handler(req({ ...ok, familyId: ctx.familyId }))).status, 400);
  assert.equal((await handler(req({ ...ok, deviceId: "invalid" }))).status, 400);
  assert.equal((await handler(req({ ...ok, limit: 51 }))).status, 400);
  assert.equal((await handler(req({ ...ok, before: "yesterday" }))).status, 400);
  assert.equal((await handler(req({ deviceId: ctx.deviceId }))).status, 400);
  assert.equal(calls, 1);
});
Deno.test("alert preserves domain conflicts, refuses other methods and never caches", async () => {
  const response = await createReportAlertsHandler(deps({
    writeAlerts: async () => {
      throw { code: "P0001", message: "IDEMPOTENCY_CONFLICT" };
    },
  }))(req(fixture));
  assert.equal(response.status, 409);
  assert.equal((await response.json()).code, "IDEMPOTENCY_CONFLICT");
  assert.equal(
    (await createReportAlertsHandler(deps())(
      new Request("https://harbor.test/alerts"),
    )).status,
    405,
  );
  const read = await createGetAlertsHandler(deps())(
    req({ deviceId: ctx.deviceId, version: 1, before: null, limit: 10 }),
  );
  assert.equal(read.headers.get("cache-control"), "no-store");
});
