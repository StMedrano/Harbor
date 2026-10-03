import {
  requireFamilyRole,
  requireParent,
  requireStaffRole,
  type AuthDependencies,
  type ParentContext,
} from "../../supabase/functions/_shared/auth.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
import { jsonError } from "../../supabase/functions/_shared/responses.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) {
    throw new Error(message ?? `expected ${String(expected)}, got ${String(actual)}`);
  }
}

async function assertRejectsCode(
  fn: () => Promise<unknown>,
  code: HarborAuthError["code"],
) {
  try {
    await fn();
  } catch (error) {
    assert(error instanceof HarborAuthError, `expected HarborAuthError, got ${String(error)}`);
    assertEquals(error.code, code);
    return;
  }
  throw new Error(`expected rejection with ${code}`);
}

const baseClaims = {
  aal: "aal2" as const,
  amr: [{ method: "totp", timestamp: 2_000_000_000 }],
};

function deps(overrides: Partial<AuthDependencies> = {}): AuthDependencies {
  return {
    validateAccessToken: async () => ({
      user: { id: "parent-a", isAnonymous: false },
      claims: baseClaims,
    }),
    getFamilyMembership: async () => ({ role: "owner", status: "active" }),
    getStaffAuthorization: async () => ({ role: "admin" }),
    nowEpochSeconds: () => 2_000_000_100,
    ...overrides,
  };
}

Deno.test("requireParent rejects a missing Authorization header", async () => {
  await assertRejectsCode(
    () => requireParent(new Request("https://harbor.test"), deps()),
    "AUTH_REQUIRED",
  );
});

Deno.test("requireParent rejects an invalid bearer token", async () => {
  const request = new Request("https://harbor.test", {
    headers: { Authorization: "Bearer invalid" },
  });

  await assertRejectsCode(
    () => requireParent(request, deps({ validateAccessToken: async () => null })),
    "AUTH_REQUIRED",
  );
});

Deno.test("requireParent rejects an anonymous child-device identity", async () => {
  const request = new Request("https://harbor.test", {
    headers: { Authorization: "Bearer child-device-token" },
  });

  await assertRejectsCode(
    () => requireParent(
      request,
      deps({
        validateAccessToken: async () => ({
          user: { id: "device-auth", isAnonymous: true },
          claims: {
            aal: "aal1",
            amr: [{ method: "anonymous", timestamp: 1_999_999_999 }],
          },
        }),
      }),
    ),
    "FORBIDDEN",
  );
});

Deno.test("requireParent returns verified parent context", async () => {
  const request = new Request("https://harbor.test", {
    headers: { Authorization: "Bearer verified-parent-token" },
  });

  const context = await requireParent(request, deps());
  assertEquals(context.userId, "parent-a");
  assertEquals(context.accessToken, "verified-parent-token");
  assertEquals(context.aal, "aal2");
  assertEquals(context.amr[0]?.method, "totp");
});

Deno.test("requireFamilyRole rejects inactive membership", async () => {
  const context: ParentContext = {
    userId: "parent-a",
    accessToken: "token",
    aal: "aal2",
    amr: baseClaims.amr,
  };

  await assertRejectsCode(
    () => requireFamilyRole(
      context,
      "family-a",
      ["owner", "parent"],
      deps({ getFamilyMembership: async () => ({ role: "owner", status: "removed" }) }),
    ),
    "FORBIDDEN",
  );
});

Deno.test("requireFamilyRole rejects a role outside the allowed set", async () => {
  const context: ParentContext = {
    userId: "parent-a",
    accessToken: "token",
    aal: "aal2",
    amr: baseClaims.amr,
  };

  await assertRejectsCode(
    () => requireFamilyRole(
      context,
      "family-a",
      ["owner"],
      deps({ getFamilyMembership: async () => ({ role: "parent", status: "active" }) }),
    ),
    "FORBIDDEN",
  );
});

Deno.test("requireStaffRole rejects a non-staff parent", async () => {
  const context: ParentContext = {
    userId: "parent-a",
    accessToken: "token",
    aal: "aal2",
    amr: baseClaims.amr,
  };

  await assertRejectsCode(
    () => requireStaffRole(
      context,
      ["support", "admin"],
      deps({ getStaffAuthorization: async () => null }),
    ),
    "FORBIDDEN",
  );
});

Deno.test("requireStaffRole enforces recent AAL2 before staff authorization", async () => {
  const context: ParentContext = {
    userId: "parent-a",
    accessToken: "token",
    aal: "aal2",
    amr: [{ method: "totp", timestamp: 2_000_000_000 - 901 }],
  };

  await assertRejectsCode(
    () => requireStaffRole(context, ["admin"], deps({ nowEpochSeconds: () => 2_000_000_000 })),
    "MFA_REQUIRED",
  );
});

Deno.test("jsonError emits stable Harbor error JSON", async () => {
  const response = jsonError("FORBIDDEN", 403, "Denied");
  assertEquals(response.status, 403);
  assertEquals(response.headers.get("content-type"), "application/json; charset=utf-8");
  const body = await response.json();
  assertEquals(body.code, "FORBIDDEN");
  assertEquals(body.message, "Denied");
});
