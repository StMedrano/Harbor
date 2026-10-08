import {
  handleCreateFamily,
  type CreateFamilyDependencies,
  type CreateFamilyPersistenceInput,
} from "../../supabase/functions/create-family/index.ts";
import type { ParentContext } from "../../supabase/functions/_shared/auth.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) {
    throw new Error(message ?? `expected ${String(expected)}, got ${String(actual)}`);
  }
}

const parent: ParentContext = {
  userId: "10000000-0000-4000-8000-000000000001",
  accessToken: "parent-token",
  aal: "aal1",
  amr: [{ method: "password", timestamp: 2_000_000_000 }],
};

function request(body: unknown): Request {
  return new Request("https://harbor.test/functions/v1/create-family", {
    method: "POST",
    headers: {
      Authorization: "Bearer parent-token",
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

function dependencies(
  overrides: Partial<CreateFamilyDependencies> = {},
): CreateFamilyDependencies {
  return {
    requireParent: async () => parent,
    createFamilyAtomic: async (input) => ({
      familyId: "a0000000-0000-4000-8000-000000000001",
      name: input.name,
      role: "owner",
    }),
    ...overrides,
  };
}

async function body(response: Response) {
  return await response.json() as Record<string, unknown>;
}

Deno.test("create-family maps unauthenticated callers to AUTH_REQUIRED", async () => {
  const response = await handleCreateFamily(
    request({ name: "Harbor Family", idempotencyKey: "request-1" }),
    dependencies({
      requireParent: async () => {
        throw new HarborAuthError("AUTH_REQUIRED", 401, "Authentication is required");
      },
    }),
  );

  assertEquals(response.status, 401);
  assertEquals((await body(response)).code, "AUTH_REQUIRED");
});

Deno.test("create-family rejects a child-device caller", async () => {
  const response = await handleCreateFamily(
    request({ name: "Harbor Family", idempotencyKey: "request-2" }),
    dependencies({
      requireParent: async () => {
        throw new HarborAuthError("FORBIDDEN", 403, "Parent access is required");
      },
    }),
  );

  assertEquals(response.status, 403);
  assertEquals((await body(response)).code, "FORBIDDEN");
});

Deno.test("create-family rejects a blank name", async () => {
  const response = await handleCreateFamily(
    request({ name: "   ", idempotencyKey: "request-3" }),
    dependencies(),
  );

  assertEquals(response.status, 400);
  assertEquals((await body(response)).code, "VALIDATION_FAILED");
});

Deno.test("create-family rejects a name longer than 100 Unicode code points", async () => {
  const response = await handleCreateFamily(
    request({ name: "🧡".repeat(101), idempotencyKey: "request-4" }),
    dependencies(),
  );

  assertEquals(response.status, 400);
  assertEquals((await body(response)).code, "VALIDATION_FAILED");
});

Deno.test("create-family rejects a blank idempotency key", async () => {
  const response = await handleCreateFamily(
    request({ name: "Harbor Family", idempotencyKey: "   " }),
    dependencies(),
  );

  assertEquals(response.status, 400);
  assertEquals((await body(response)).code, "VALIDATION_FAILED");
});

Deno.test("create-family trims the family name before persistence", async () => {
  let persisted: CreateFamilyPersistenceInput | undefined;
  const response = await handleCreateFamily(
    request({ name: "  Harbor Family  ", idempotencyKey: " request-5 " }),
    dependencies({
      createFamilyAtomic: async (input) => {
        persisted = input;
        return {
          familyId: "a0000000-0000-4000-8000-000000000001",
          name: input.name,
          role: "owner",
        };
      },
    }),
  );

  assertEquals(response.status, 200);
  assert(persisted);
  assertEquals(persisted.name, "Harbor Family");
  assertEquals(persisted.idempotencyKey, "request-5");
  assertEquals(persisted.userId, parent.userId);
});

Deno.test("create-family returns family id, normalized name, and owner role", async () => {
  const response = await handleCreateFamily(
    request({ name: "Harbor Family", idempotencyKey: "request-6" }),
    dependencies(),
  );

  assertEquals(response.status, 200);
  const payload = await body(response);
  assertEquals(payload.familyId, "a0000000-0000-4000-8000-000000000001");
  assertEquals(payload.name, "Harbor Family");
  assertEquals(payload.role, "owner");
});

Deno.test("duplicate idempotency key returns the original family without a second creation", async () => {
  const store = new Map<string, { familyId: string; name: string; role: "owner" }>();
  let creations = 0;

  const createFamilyAtomic = async (input: CreateFamilyPersistenceInput) => {
    const key = `${input.userId}:${input.idempotencyKey}`;
    const existing = store.get(key);
    if (existing) return existing;

    creations += 1;
    const created = {
      familyId: "a0000000-0000-4000-8000-000000000001",
      name: input.name,
      role: "owner" as const,
    };
    store.set(key, created);
    return created;
  };

  const deps = dependencies({ createFamilyAtomic });
  const first = await handleCreateFamily(
    request({ name: "First Name", idempotencyKey: "same-key" }),
    deps,
  );
  const second = await handleCreateFamily(
    request({ name: "Different Name", idempotencyKey: "same-key" }),
    deps,
  );

  assertEquals(first.status, 200);
  assertEquals(second.status, 200);
  assertEquals(creations, 1);
  const firstBody = await body(first);
  const secondBody = await body(second);
  assertEquals(secondBody.familyId, firstBody.familyId);
  assertEquals(secondBody.name, "First Name");
});
