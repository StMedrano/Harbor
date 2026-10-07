import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  type ParentSessionDependencies,
  requireActiveParentSession,
} from "../../supabase/functions/_shared/parent-session.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
const userId = "30000000-0000-4000-8000-000000000101",
  sessionId = "30000000-0000-4000-8000-000000000111";
const base = {
  userId,
  accessToken: "test-only",
  aal: "aal1" as const,
  amr: [],
};
function request(token = "test-only") {
  return new Request("https://harbor.test", {
    headers: { Authorization: `Bearer ${token}` },
  });
}
function deps(
  overrides: Partial<ParentSessionDependencies> = {},
): ParentSessionDependencies {
  return {
    requireParent: async () => ({ ...base, sessionId }),
    isActive: async () => true,
    ...overrides,
  };
}
Deno.test("active parent session binds verified subject/session to database check", async () => {
  let args: unknown;
  const result = await requireActiveParentSession(
    request(),
    deps({
      isActive: async (user: string, session: string) => {
        args = [user, session];
        return true;
      },
    }),
  );
  assertEquals(args, [userId, sessionId]);
  assertEquals(result.sessionId, sessionId);
});
for (const value of [undefined, "", "not-a-uuid"]) {
  Deno.test(`missing/invalid verified session ${value} denied before SQL`, async () => {
    let calls = 0;
    await assertRejects(
      () =>
        requireActiveParentSession(
          request(),
          deps({
            requireParent: async () => ({ ...base, sessionId: value }),
            isActive: async () => {
              calls++;
              return true;
            },
          }),
        ),
      HarborAuthError,
    );
    assertEquals(calls, 0);
  });
}
for (const reason of ["wrong-owner", "logged-out", "expired"]) {
  Deno.test(`parent session ${reason} denied`, async () => {
    await assertRejects(
      () =>
        requireActiveParentSession(
          request(),
          deps({ isActive: async () => false }),
        ),
      HarborAuthError,
    );
  });
}
Deno.test("unverified JWT payload is never a missing-session fallback", async () => {
  const token = `${btoa("{}")}.${
    btoa(JSON.stringify({ session_id: sessionId }))
  }.fake`;
  await assertRejects(
    () =>
      requireActiveParentSession(
        request(token),
        deps({ requireParent: async () => base }),
      ),
    HarborAuthError,
  );
});
Deno.test("anonymous Auth rejection remains authoritative", async () => {
  await assertRejects(
    () =>
      requireActiveParentSession(
        request(),
        deps({
          requireParent: async () => {
            throw new HarborAuthError("FORBIDDEN", 403, "Parent required");
          },
        }),
      ),
    HarborAuthError,
  );
});
