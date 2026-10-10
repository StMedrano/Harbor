import { strict as assert } from "node:assert";
import fixture from "../../apps/parent-android/app/src/test/resources/usage-report-v1.json" with {
  type: "json",
};
import {
  createClearUsageHandler,
  createGetUsageHandler,
  createReportUsageHandler,
  createUsageCheckpointHandler,
  type UsageEndpointDependencies,
} from "../../supabase/functions/_shared/usage.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
import {
  type DeviceProofDependencies,
  requireDeviceProof,
} from "../../supabase/functions/_shared/device-proof.ts";
import {
  sha256Hex,
  verifyP256Sha256,
} from "../../supabase/functions/_shared/crypto.ts";
const now = Date.parse("2026-10-08T12:00:00Z");
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
  overrides: Partial<UsageEndpointDependencies> = {},
): UsageEndpointDependencies {
  return {
    now: () => now,
    requireProof: async () => ctx,
    requireParent: async () => parent,
    sha256: sha256Hex,
    writeUsage: async (_ctx, report) => ({
      confirmed: true,
      sequence: report.sequence,
      receivedAt: new Date(now).toISOString(),
    }),
    clearUsage: async (_ctx, clear) => ({
      confirmed: true,
      sequence: clear.sequence,
      receivedAt: new Date(now).toISOString(),
    }),
    readUsage: async () => ({ state: "none", report: null, receivedAt: null }),
    readUsageCheckpoint: async () => ({ sequence: 0, epochId: null }),
    ...overrides,
  };
}
function req(body: unknown) {
  return new Request("https://harbor.test/usage", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
Deno.test("usage report binds verified context and hashes exact raw bytes", async () => {
  let written = false;
  const body = JSON.stringify(fixture) + " ";
  const response = await createReportUsageHandler(deps({
    requireProof: async (r, operation) => {
      assert.equal(operation, "report-device-usage");
      assert.equal(await r.clone().text(), body);
      return ctx;
    },
    writeUsage: async (c, v, hash) => {
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
  }))(new Request("https://harbor.test/usage", { method: "POST", body }));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(written, true);
  assert.equal((await response.json()).confirmed, true);
});
Deno.test("usage proof errors do not call persistence", async () => {
  for (
    const [code, status] of [["AUTH_REQUIRED", 401], ["FORBIDDEN", 403], [
      "REPLAY_REJECTED",
      409,
    ], ["DEVICE_REVOKED", 403]] as const
  ) {
    let calls = 0;
    const response = await createReportUsageHandler(
      deps({
        requireProof: async () => {
          throw new HarborAuthError(code, status, "denied");
        },
        writeUsage: async () => {
          calls++;
          throw Error("must not write");
        },
      }),
    )(req(fixture));
    assert.equal(response.status, status);
    assert.equal(calls, 0);
  }
});
Deno.test("usage rejects client authority and invalid durations before database write", async () => {
  let calls = 0;
  const handler = createReportUsageHandler(deps({
    writeUsage: async () => {
      calls++;
      throw Error("must not write");
    },
  }));
  assert.equal(
    (await handler(req({ ...fixture, familyId: ctx.familyId }))).status,
    400,
  );
  const bad = structuredClone(fixture);
  bad.days[0].totalMs = -1;
  assert.equal((await handler(req(bad))).status, 400);
  assert.equal(calls, 0);
});
Deno.test("usage cancels oversized stream before proof and database", async () => {
  let cancelled = false, proofs = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(c) {
      c.enqueue(new Uint8Array(600000));
    },
    cancel() {
      cancelled = true;
    },
  });
  const response = await createReportUsageHandler(
    deps({
      requireProof: async () => {
        proofs++;
        return ctx;
      },
    }),
  )(new Request("https://harbor.test/usage", { method: "POST", body: stream }));
  assert.equal(response.status, 413);
  assert.equal(cancelled, true);
  assert.equal(proofs, 0);
});
Deno.test("usage clear and checkpoint have separate fixed operations", async () => {
  const operations: string[] = [];
  const d = deps({
    requireProof: async (_r, op) => {
      operations.push(op);
      return ctx;
    },
  });
  assert.equal(
    (await createClearUsageHandler(d)(
      req({ version: 1, epochId: fixture.epochId, sequence: 2 }),
    )).status,
    200,
  );
  const c = await createUsageCheckpointHandler(d)(req({ version: 1 }));
  assert.deepEqual(await c.json(), { sequence: 0, epochId: null });
  assert.deepEqual(operations, [
    "clear-device-usage",
    "get-device-usage-checkpoint",
  ]);
  assert.equal(
    (await createUsageCheckpointHandler(d)(
      req({ version: 1, deviceId: ctx.deviceId }),
    )).status,
    400,
  );
});
Deno.test("usage parent reads require current verified session and exact device", async () => {
  let calls = 0;
  const handler = createGetUsageHandler(deps({
    readUsage: async (p, id) => {
      calls++;
      assert.deepEqual(p, parent);
      assert.equal(id, ctx.deviceId);
      return { state: "none", report: null, receivedAt: null };
    },
  }));
  assert.equal((await handler(req({ deviceId: ctx.deviceId }))).status, 200);
  assert.equal(calls, 1);
  for (const code of ["AUTH_REQUIRED", "FORBIDDEN"] as const) {
    assert.equal(
      (await createGetUsageHandler(deps({
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
    (await createGetUsageHandler(
      deps({
        requireParent: async () => ({ ...parent, sessionId: undefined }),
      }),
    )(req({ deviceId: ctx.deviceId }))).status,
    401,
  );
  assert.equal(
    (await handler(req({ deviceId: ctx.deviceId, familyId: ctx.familyId })))
      .status,
    400,
  );
  assert.equal((await handler(req({ deviceId: "invalid" }))).status, 400);
});
Deno.test("usage preserves domain conflicts and refuses other methods", async () => {
  const response = await createReportUsageHandler(
    deps({
      writeUsage: async () => {
        throw { code: "P0001", message: "IDEMPOTENCY_CONFLICT" };
      },
    }),
  )(req(fixture));
  assert.equal(response.status, 409);
  assert.equal((await response.json()).code, "IDEMPOTENCY_CONFLICT");
  assert.equal(
    (await createReportUsageHandler(deps())(
      new Request("https://harbor.test/usage"),
    )).status,
    405,
  );
});
Deno.test("usage uses real P256 operation and nonce binding", async () => {
  const keys = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign", "verify"],
  );
  const b64 = (v: ArrayBuffer) =>
    btoa(String.fromCharCode(...new Uint8Array(v)));
  const spki = b64(await crypto.subtle.exportKey("spki", keys.publicKey));
  const used = new Set<string>();
  const proof: DeviceProofDependencies = {
    now: () => new Date(now),
    requireDeviceIdentity: async () => ({
      userId: ctx.authUserId,
      accessToken: "test-only",
    }),
    loadDeviceSecurity: async () => ({
      ...ctx,
      publicKeySpki: spki,
      revokedAt: null,
    }),
    sha256: sha256Hex,
    verifyP256Signature: verifyP256Sha256,
    claimNonceAtomic: async (_id, nonce) => {
      if (used.has(nonce)) return false;
      used.add(nonce);
      return true;
    },
  };
  const d = deps({ requireProof: (r, op) => requireDeviceProof(r, op, proof) });
  async function signed(operation: string, nonce: string) {
    const body = JSON.stringify(fixture);
    const timestamp = now / 1000;
    const canonical = [
      "POST",
      operation,
      ctx.deviceId,
      await sha256Hex(body),
      timestamp,
      nonce,
    ].join("\n");
    const signature = b64(
      await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        keys.privateKey,
        new TextEncoder().encode(canonical),
      ),
    );
    return new Request("https://harbor.test/usage", {
      method: "POST",
      body,
      headers: {
        Authorization: "Bearer test-only",
        "X-Harbor-Device-Id": ctx.deviceId,
        "X-Harbor-Timestamp": String(timestamp),
        "X-Harbor-Nonce": nonce,
        "X-Harbor-Signature": signature,
      },
    });
  }
  assert.equal(
    (await createReportUsageHandler(d)(
      await signed("report-device-usage", "nonce-report"),
    )).status,
    200,
  );
  assert.equal(
    (await createReportUsageHandler(d)(
      await signed("report-device-usage", "nonce-report"),
    )).status,
    409,
  );
  assert.equal(
    (await createReportUsageHandler(d)(
      await signed("clear-device-usage", "nonce-swapped"),
    )).status,
    403,
  );
});
