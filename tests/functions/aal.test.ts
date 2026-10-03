import { requireRecentAal2 } from "../../supabase/functions/_shared/aal.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
import type { ParentContext } from "../../supabase/functions/_shared/auth.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) {
    throw new Error(message ?? `expected ${String(expected)}, got ${String(actual)}`);
  }
}

function assertThrowsCode(
  fn: () => unknown,
  code: HarborAuthError["code"],
) {
  try {
    fn();
  } catch (error) {
    assert(error instanceof HarborAuthError, `expected HarborAuthError, got ${String(error)}`);
    assertEquals(error.code, code);
    return;
  }
  throw new Error(`expected throw with ${code}`);
}

function context(aal: ParentContext["aal"], amr: ParentContext["amr"]): ParentContext {
  return {
    userId: "parent-a",
    accessToken: "token",
    aal,
    amr,
  };
}

const now = 2_000_000_000;

Deno.test("requireRecentAal2 rejects aal1", () => {
  assertThrowsCode(
    () => requireRecentAal2(
      context("aal1", [{ method: "password", timestamp: now - 10 }]),
      now,
    ),
    "MFA_REQUIRED",
  );
});

Deno.test("requireRecentAal2 accepts a TOTP MFA event 899 seconds old", () => {
  requireRecentAal2(
    context("aal2", [
      { method: "token_refresh", timestamp: now - 20 },
      { method: "totp", timestamp: now - 899 },
      { method: "password", timestamp: now - 1_000 },
    ]),
    now,
  );
});

Deno.test("requireRecentAal2 accepts the exact 900 second boundary", () => {
  requireRecentAal2(
    context("aal2", [{ method: "totp", timestamp: now - 900 }]),
    now,
  );
});

Deno.test("requireRecentAal2 rejects a TOTP MFA event 901 seconds old", () => {
  assertThrowsCode(
    () => requireRecentAal2(
      context("aal2", [{ method: "totp", timestamp: now - 901 }]),
      now,
    ),
    "MFA_REQUIRED",
  );
});

Deno.test("requireRecentAal2 fails closed when there is no verifiable TOTP timestamp", () => {
  assertThrowsCode(
    () => requireRecentAal2(
      context("aal2", [
        { method: "password", timestamp: now - 10 },
        { method: "totp", timestamp: Number.NaN },
      ]),
      now,
    ),
    "MFA_REQUIRED",
  );
});

Deno.test("requireRecentAal2 uses the newest TOTP event, not array position", () => {
  requireRecentAal2(
    context("aal2", [
      { method: "totp", timestamp: now - 1_200 },
      { method: "password", timestamp: now - 5 },
      { method: "totp", timestamp: now - 100 },
    ]),
    now,
  );
});
