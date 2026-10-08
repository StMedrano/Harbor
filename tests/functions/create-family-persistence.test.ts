import {
  createFamilyAtomicWithQuery,
  type CreateFamilyAtomicInput,
  type CreateFamilyAtomicRow,
} from "../../supabase/functions/_shared/clients.ts";

function assertEquals<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) {
    throw new Error(message ?? `expected ${String(expected)}, got ${String(actual)}`);
  }
}

const input: CreateFamilyAtomicInput = {
  userId: "10000000-0000-4000-8000-000000000001",
  name: "Harbor Family",
  idempotencyKey: "request-1",
};

Deno.test("family persistence adapter maps the private helper row to the public response contract", async () => {
  let received: CreateFamilyAtomicInput | undefined;
  const result = await createFamilyAtomicWithQuery(input, async (actual) => {
    received = actual;
    return [{
      family_id: "a0000000-0000-4000-8000-000000000001",
      name: "Harbor Family",
      role: "owner",
    } satisfies CreateFamilyAtomicRow];
  });

  assertEquals(received?.userId, input.userId);
  assertEquals(received?.name, input.name);
  assertEquals(received?.idempotencyKey, input.idempotencyKey);
  assertEquals(result.familyId, "a0000000-0000-4000-8000-000000000001");
  assertEquals(result.name, "Harbor Family");
  assertEquals(result.role, "owner");
});

Deno.test("family persistence adapter fails closed when the private helper returns no row", async () => {
  let error: unknown;
  try {
    await createFamilyAtomicWithQuery(input, async () => []);
  } catch (caught) {
    error = caught;
  }

  assertEquals(error instanceof Error, true);
  assertEquals((error as Error).message, "Atomic family creation returned no result");
});

Deno.test("family persistence adapter fails closed on an unexpected role", async () => {
  let error: unknown;
  try {
    await createFamilyAtomicWithQuery(input, async () => [{
      family_id: "a0000000-0000-4000-8000-000000000001",
      name: "Harbor Family",
      role: "parent" as unknown as "owner",
    }]);
  } catch (caught) {
    error = caught;
  }

  assertEquals(error instanceof Error, true);
  assertEquals((error as Error).message, "Atomic family creation returned an invalid role");
});
