import { strict as assert } from "node:assert";
import fixture from "../../apps/parent-android/app/src/test/resources/location-report-v1.json" with {
  type: "json",
};
import {
  createClearLocationHandler,
  createGetLocationHandler,
  createLocationCheckpointHandler,
  createReportLocationHandler,
  type LocationEndpointDependencies,
} from "../../supabase/functions/_shared/location.ts";
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
  status: null,
  observedAt: null,
  receivedAt: null,
  fixes: [],
};
function deps(
  overrides: Partial<LocationEndpointDependencies> = {},
): LocationEndpointDependencies {
  return {
    now: () => now,
    requireProof: async () => ctx,
    requireParent: async () => parent,
    sha256: sha256Hex,
    writeLocation: async (_ctx, report) => ({
      confirmed: true,
      sequence: report.sequence,
      receivedAt: new Date(now).toISOString(),
    }),
    clearLocation: async (_ctx, clear) => ({
      confirmed: true,
      sequence: clear.sequence,
      receivedAt: new Date(now).toISOString(),
    }),
    readLocation: async () => none,
    readLocationCheckpoint: async () => ({ sequence: 0, epochId: null }),
    ...overrides,
  };
}
function req(body: unknown) {
  return new Request("https://harbor.test/location", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
Deno.test("location report binds verified context and hashes exact raw bytes", async () => {
  let written = false;
  const body = JSON.stringify(fixture) + " ";
  const response = await createReportLocationHandler(deps({
    requireProof: async (r, operation) => {
      assert.equal(operation, "report-device-location");
      assert.equal(await r.clone().text(), body);
      return ctx;
    },
    writeLocation: async (c, v, hash) => {
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
  }))(new Request("https://harbor.test/location", { method: "POST", body }));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(written, true);
});
Deno.test("location proof errors do not call persistence", async () => {
  for (
    const [code, status] of [["AUTH_REQUIRED", 401], ["FORBIDDEN", 403], [
      "REPLAY_REJECTED",
      409,
    ], ["DEVICE_REVOKED", 403]] as const
  ) {
    let calls = 0;
    const response = await createReportLocationHandler(deps({
      requireProof: async () => {
        throw new HarborAuthError(code, status, "denied");
      },
      writeLocation: async () => {
        calls++;
        throw Error("must not write");
      },
    }))(req(fixture));
    assert.equal(response.status, status);
    assert.equal(calls, 0);
  }
});
Deno.test("location rejects client authority and invalid coordinates before database write", async () => {
  let calls = 0;
  const handler = createReportLocationHandler(deps({
    writeLocation: async () => {
      calls++;
      throw Error("must not write");
    },
  }));
  assert.equal(
    (await handler(req({ ...fixture, familyId: ctx.familyId }))).status,
    400,
  );
  const bad = structuredClone(fixture);
  bad.fixes[0].latitude = 91;
  assert.equal((await handler(req(bad))).status, 400);
  const future = {
    ...structuredClone(fixture),
    observedAt: "2026-10-10T18:06:00Z",
  };
  assert.equal((await handler(req(future))).status, 400);
  assert.equal(calls, 0);
});
Deno.test("location cancels oversized stream before proof and database", async () => {
  let cancelled = false, proofs = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(c) {
      c.enqueue(new Uint8Array(150000));
    },
    cancel() {
      cancelled = true;
    },
  });
  const response = await createReportLocationHandler(deps({
    requireProof: async () => {
      proofs++;
      return ctx;
    },
  }))(
    new Request("https://harbor.test/location", {
      method: "POST",
      body: stream,
    }),
  );
  assert.equal(response.status, 413);
  assert.equal(cancelled, true);
  assert.equal(proofs, 0);
});
Deno.test("location clear and checkpoint have separate fixed operations", async () => {
  const operations: string[] = [];
  const d = deps({
    requireProof: async (_r, op) => {
      operations.push(op);
      return ctx;
    },
  });
  assert.equal(
    (await createClearLocationHandler(d)(
      req({ version: 1, epochId: fixture.epochId, sequence: 8 }),
    )).status,
    200,
  );
  const c = await createLocationCheckpointHandler(d)(req({ version: 1 }));
  assert.deepEqual(await c.json(), { sequence: 0, epochId: null });
  assert.deepEqual(operations, [
    "clear-device-location",
    "get-device-location-checkpoint",
  ]);
  assert.equal(
    (await createLocationCheckpointHandler(d)(
      req({ version: 1, deviceId: ctx.deviceId }),
    )).status,
    400,
  );
});
Deno.test("location parent reads require current verified session and exact device", async () => {
  let calls = 0;
  const handler = createGetLocationHandler(deps({
    readLocation: async (p, id) => {
      calls++;
      assert.deepEqual(p, parent);
      assert.equal(id, ctx.deviceId);
      return none;
    },
  }));
  assert.equal((await handler(req({ deviceId: ctx.deviceId }))).status, 200);
  assert.equal(calls, 1);
  for (const code of ["AUTH_REQUIRED", "FORBIDDEN"] as const) {
    assert.equal(
      (await createGetLocationHandler(deps({
        requireParent: async () => {
          throw new HarborAuthError(
            code,
            code === "AUTH_REQUIRED" ? 401 : 403,
            "denied",
          );
        },
      }))(req({ deviceId: ctx.deviceId }))).status,
      code === "AUTH_REQUIRED" ? 401 : 403,
    );
  }
  assert.equal(
    (await createGetLocationHandler(deps({
      requireParent: async () => ({ ...parent, sessionId: undefined }),
    }))(req({ deviceId: ctx.deviceId }))).status,
    401,
  );
  assert.equal(
    (await handler(req({ deviceId: ctx.deviceId, familyId: ctx.familyId })))
      .status,
    400,
  );
  assert.equal((await handler(req({ deviceId: "invalid" }))).status, 400);
  assert.equal(calls, 1);
});
Deno.test("location preserves domain conflicts, refuses other methods and never caches", async () => {
  const response = await createReportLocationHandler(deps({
    writeLocation: async () => {
      throw { code: "P0001", message: "IDEMPOTENCY_CONFLICT" };
    },
  }))(req(fixture));
  assert.equal(response.status, 409);
  assert.equal((await response.json()).code, "IDEMPOTENCY_CONFLICT");
  assert.equal(
    (await createReportLocationHandler(deps())(
      new Request("https://harbor.test/location"),
    )).status,
    405,
  );
  const read = await createGetLocationHandler(deps())(
    req({ deviceId: ctx.deviceId }),
  );
  assert.equal(read.headers.get("cache-control"), "no-store");
});
