import { assertEquals } from "jsr:@std/assert@1";
import {
  checkPassword,
  type PasswordAuth,
  type PasswordResult,
} from "../../tools/notification-acceptance/web/password-check.ts";
function auth(error: unknown, session = true): PasswordAuth {
  return {
    signInWithPassword: async () => ({
      data: {
        user: session ? { id: "fixture" } : null,
        session: session ? { user: { id: "fixture" } } : null,
      },
      error,
    }),
    signOut: async () => ({ error: null }),
  } as unknown as PasswordAuth;
}
Deno.test("fresh server password rejection replaces stale accepted evidence", async () => {
  const results: PasswordResult[] = ["accepted"];
  assertEquals(
    await checkPassword(
      auth({ code: "invalid_credentials" }),
      "fixture@example.test",
      "private",
      (result) => results.push(result),
    ),
    false,
  );
  assertEquals(results, ["accepted", "checking", "rejected"]);
});
Deno.test("accepted password result survives an independent notification setup failure", async () => {
  const results: PasswordResult[] = [];
  assertEquals(
    await checkPassword(
      auth(null),
      "fixture@example.test",
      "private",
      (result) => results.push(result),
    ),
    true,
  );
  try {
    await Promise.reject(Error("notification setup failed"));
  } catch { /* separate boundary */ }
  assertEquals(results, ["checking", "accepted"]);
});
Deno.test("missing session, network failure and other auth errors never establish password rejection or acceptance", async () => {
  for (
    const client of [
      auth(null, false),
      auth({ code: "over_request_rate_limit", message: "private" }),
      {
        signInWithPassword: async () => {
          throw Error("private-token");
        },
        signOut: async () => ({ error: null }),
      } as unknown as PasswordAuth,
    ]
  ) {
    const results: PasswordResult[] = [];
    assertEquals(
      await checkPassword(
        client,
        "fixture@example.test",
        "private",
        (result) => results.push(result),
      ),
      false,
    );
    assertEquals(results, ["checking", "unverified"]);
  }
});
