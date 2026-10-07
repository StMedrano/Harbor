import { assertEquals, assertMatch } from "jsr:@std/assert@1";
import {
  type CreateChildDependencies,
  createCreateChildHandler,
} from "../../supabase/functions/create-child/index.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";

const actor = "10000000-0000-4000-8000-000000000101";
const familyId = "10000000-0000-4000-8000-000000000102";
const childId = "10000000-0000-4000-8000-000000000103";
const child = {
  version: 1 as const,
  id: childId,
  familyId,
  displayName: "Alex",
  createdAt: "2026-10-06T00:00:00Z",
  updatedAt: "2026-10-06T00:00:00Z",
};
const payload = { familyId, displayName: "Alex", idempotencyKey: "request-1" };
function request(body: unknown) {
  return new Request("https://harbor.test/functions/v1/create-child", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}
function deps(
  overrides: Partial<CreateChildDependencies> = {},
): CreateChildDependencies {
  return {
    requireParent: async () => ({
      userId: actor,
      accessToken: "test-only",
      aal: "aal1",
      amr: [],
    }),
    createChildAtomic: async () => child,
    ...overrides,
  };
}
Deno.test("create-child binds actor, normalizes name and hashes canonical payload", async () => {
  let input: Record<string, unknown> | undefined;
  const handler = createCreateChildHandler(
    deps({
      createChildAtomic: async (value) => {
        input = value;
        return child;
      },
    }),
  );
  const response = await handler(
    request({
      ...payload,
      displayName: "  Alex  ",
      userId: crypto.randomUUID(),
    }),
  );
  assertEquals(response.status, 200);
  assertEquals(await response.json(), child);
  assertEquals(input?.userId, actor);
  assertEquals(input?.displayName, "Alex");
  assertMatch(String(input?.payloadHash), /^[0-9a-f]{64}$/);
});
Deno.test("create-child retries equivalent normalized payload with the same fingerprint", async () => {
  const hashes: string[] = [];
  const handler = createCreateChildHandler(
    deps({
      createChildAtomic: async (value) => {
        hashes.push(value.payloadHash);
        return child;
      },
    }),
  );
  await handler(request(payload));
  await handler(request({ ...payload, displayName: " Alex " }));
  assertEquals(hashes.length, 2);
  assertEquals(hashes[0], hashes[1]);
});
for (
  const displayName of [
    "",
    " ",
    "a".repeat(101),
    "Alex\n",
    "A\u0000B",
    "A\u007fB",
    "A\u0085B",
  ]
) {
  Deno.test(`create-child rejects invalid name ${JSON.stringify(displayName)}`, async () => {
    let calls = 0;
    const response = await createCreateChildHandler(
      deps({
        createChildAtomic: async () => {
          calls++;
          return child;
        },
      }),
    )(request({ ...payload, displayName }));
    assertEquals(response.status, 400);
    assertEquals(calls, 0);
  });
}
Deno.test("create-child accepts 100 Unicode code points", async () => {
  const response = await createCreateChildHandler(deps())(
    request({ ...payload, displayName: "👨".repeat(100) }),
  );
  assertEquals(response.status, 200);
});
for (
  const body of [null, [], { ...payload, familyId: "invalid" }, {
    ...payload,
    idempotencyKey: " ",
  }, { ...payload, displayName: 1 }]
) {
  Deno.test(`create-child rejects invalid request ${JSON.stringify(body)}`, async () => {
    const response = await createCreateChildHandler(deps())(request(body));
    assertEquals(response.status, 400);
  });
}
for (
  const [code, status] of [["AUTH_REQUIRED", 401], ["FORBIDDEN", 403]] as const
) {
  Deno.test(`create-child preserves verified Auth denial ${code}`, async () => {
    let called = false;
    const response = await createCreateChildHandler(
      deps({
        requireParent: async () => {
          throw new HarborAuthError(code, status, "Denied");
        },
        createChildAtomic: async () => {
          called = true;
          return child;
        },
      }),
    )(request(payload));
    assertEquals(response.status, status);
    assertEquals(called, false);
  });
}
Deno.test("create-child maps changed idempotency payload to409 without leaking details", async () => {
  const response = await createCreateChildHandler(
    deps({
      createChildAtomic: async () => {
        throw { code: "P0001", message: "IDEMPOTENCY_CONFLICT" };
      },
    }),
  )(request(payload));
  assertEquals(response.status, 409);
  assertEquals((await response.json()).code, "IDEMPOTENCY_CONFLICT");
});
Deno.test("create-child maps removed or cross-family membership to403", async () => {
  const response = await createCreateChildHandler(
    deps({
      createChildAtomic: async () => {
        throw { code: "42501", message: "parent membership required" };
      },
    }),
  )(request(payload));
  assertEquals(response.status, 403);
});
