import { strict as assert } from "node:assert";
import fixture from "../../apps/parent-android/app/src/test/resources/access-request-v1.json" with { type: "json" };
import {
  type AccessEndpointDependencies,
  createDecideAccessHandler,
  createGetAccessRequestsHandler,
  createRequestAccessHandler,
} from "../../supabase/functions/_shared/access-requests.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";

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
function deps(overrides: Partial<AccessEndpointDependencies> = {}): AccessEndpointDependencies {
  return {
    requireProof: async () => ctx,
    requireParent: async () => parent,
    requestAccess: async (_c, r) => ({ requestId: r.requestId, status: "pending", requestedAt: "2026-10-21T15:00:00Z" }),
    readRequests: async () => ({ requests: [] }),
    decide: async (_p, d) => ({ requestId: d.requestId, status: d.decision }),
    ...overrides,
  };
}
const req = (body: unknown) => new Request("https://harbor.test/access", { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body) });

Deno.test("child request is signed for its own operation and passes the verified device", async () => {
  let seen: unknown;
  const response = await createRequestAccessHandler(deps({
    requireProof: async (_r, operation) => {
      assert.equal(operation, "request-device-access");
      return ctx;
    },
    requestAccess: async (c, r) => {
      seen = { c, r };
      return { requestId: r.requestId, status: "pending", requestedAt: "2026-10-21T15:00:00Z" };
    },
  }))(req(fixture));
  assert.equal(response.status, 200);
  assert.deepEqual(seen, { c: ctx, r: fixture });
  assert.equal((await response.json()).status, "pending");
});

Deno.test("child request rejects free text, unknown reasons and oversize bodies before any write", async () => {
  let writes = 0;
  const handler = createRequestAccessHandler(deps({ requestAccess: async () => { writes++; throw Error("no"); } }));
  for (const body of [{ ...fixture, message: "pls" }, { ...fixture, reason: "bored" }, JSON.stringify(fixture) + " ".repeat(5000)]) {
    const response = await handler(req(body));
    assert.ok(response.status === 400 || response.status === 413, String(response.status));
  }
  assert.equal(writes, 0);
  assert.equal((await handler(new Request("https://harbor.test/access"))).status, 405);
});

Deno.test("a revoked phone is told so", async () => {
  const response = await createRequestAccessHandler(deps({
    requestAccess: async () => { throw new HarborAuthError("DEVICE_REVOKED", 403, "revoked"); },
  }))(req(fixture));
  assert.equal(response.status, 403);
  assert.equal((await response.json()).code, "DEVICE_REVOKED");
});

Deno.test("parent read and decision need the current session and strict bodies", async () => {
  const noSession = deps({ requireParent: async () => ({ ...parent, sessionId: undefined }) });
  assert.equal((await createGetAccessRequestsHandler(noSession)(req({ version: 1, deviceId: ctx.deviceId }))).status, 401);
  assert.equal((await createGetAccessRequestsHandler(deps())(req({ version: 1, deviceId: "x" }))).status, 400);
  let device = "";
  const read = await createGetAccessRequestsHandler(deps({ readRequests: async (_p, d) => { device = d; return { requests: [] }; } }))(
    req({ version: 1, deviceId: ctx.deviceId }));
  assert.equal(read.status, 200);
  assert.equal(device, ctx.deviceId);
  const decided = await createDecideAccessHandler(deps())(req({ version: 1, requestId: fixture.requestId, decision: "approved" }));
  assert.deepEqual(await decided.json(), { requestId: fixture.requestId, status: "approved" });
  assert.equal((await createDecideAccessHandler(deps())(req({ version: 1, requestId: fixture.requestId, decision: "maybe" }))).status, 400);
  const stale = await createDecideAccessHandler(deps({ decide: async () => { throw { code: "P0001", message: "STALE_VERSION" }; } }))(
    req({ version: 1, requestId: fixture.requestId, decision: "denied" }));
  assert.equal(stale.status, 409);
});
