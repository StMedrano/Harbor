import { handleCreateParentInvite, type CreateParentInviteDependencies } from "../../supabase/functions/create-parent-invite/index.ts";
import { handleAcceptParentInvite, type AcceptParentInviteDependencies } from "../../supabase/functions/accept-parent-invite/index.ts";
import { formatInviteCode, generateInviteCode, normalizeInviteCode } from "../../supabase/functions/_shared/parent-invites.ts";
import type { ParentContext } from "../../supabase/functions/_shared/auth.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}
function assertEquals<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) throw new Error(message ?? `expected ${String(expected)}, got ${String(actual)}`);
}

const parent: ParentContext = { userId: "10000000-0000-4000-8000-000000000001", accessToken: "parent-token", aal: "aal1", amr: [] };
const FAMILY = "a0000000-0000-4000-8000-000000000001";
const request = (name: string, body: unknown) => new Request(`https://harbor.test/functions/v1/${name}`, {
  method: "POST", headers: { Authorization: "Bearer parent-token", "content-type": "application/json" }, body: JSON.stringify(body),
});

function createDeps(overrides: Partial<CreateParentInviteDependencies> = {}) {
  const issued: Array<{ userId: string; familyId: string; codeDigest: string; expiresAt: string }> = [];
  const deps: CreateParentInviteDependencies = {
    requireParent: async () => parent,
    now: () => new Date("2026-10-23T12:00:00.000Z"),
    generateCode: () => "7K3MQ9XZ",
    digestCode: async (code) => `digest:${code}`,
    issue: async (input) => { issued.push(input); return { familyId: input.familyId, expiresAt: input.expiresAt }; },
    ...overrides,
  };
  return { deps, issued };
}

Deno.test("create-parent-invite returns a readable one-day code and stores only its digest", async () => {
  const { deps, issued } = createDeps();
  const response = await handleCreateParentInvite(request("create-parent-invite", { familyId: FAMILY }), deps);
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.code, "7K3M-Q9XZ");
  assertEquals(body.expiresAt, "2026-10-24T12:00:00.000Z");
  assertEquals(issued.length, 1);
  assertEquals(issued[0].codeDigest, "digest:7K3MQ9XZ");
  assertEquals(issued[0].userId, parent.userId);
  assert(!JSON.stringify(issued).includes("7K3M-Q9XZ"));
});

Deno.test("create-parent-invite refuses a missing family, extra fields and a non-member", async () => {
  const { deps } = createDeps();
  assertEquals((await handleCreateParentInvite(request("create-parent-invite", {}), deps)).status, 400);
  assertEquals((await handleCreateParentInvite(request("create-parent-invite", { familyId: FAMILY, role: "owner" }), deps)).status, 400);
  const denied = createDeps({ issue: async () => { throw Object.assign(new Error("family is not authorized for parent"), { code: "42501" }); } });
  assertEquals((await handleCreateParentInvite(request("create-parent-invite", { familyId: FAMILY }), denied.deps)).status, 403);
  const child = createDeps({ requireParent: async () => { throw new HarborAuthError("FORBIDDEN", 403, "Parent access is required"); } });
  assertEquals((await handleCreateParentInvite(request("create-parent-invite", { familyId: FAMILY }), child.deps)).status, 403);
});

function acceptDeps(outcome: "joined" | "already_member" | "invalid" | "locked") {
  const seen: string[] = [];
  const deps: AcceptParentInviteDependencies = {
    requireParent: async () => parent,
    digestCode: async (code) => `digest:${code}`,
    accept: async (_userId, digest) => {
      seen.push(digest);
      return outcome === "invalid" || outcome === "locked"
        ? { familyId: null, familyName: null, outcome }
        : { familyId: FAMILY, familyName: "Rivera family", outcome };
    },
  };
  return { deps, seen };
}

Deno.test("accept-parent-invite joins with a code typed loosely and says which family", async () => {
  const { deps, seen } = acceptDeps("joined");
  const response = await handleAcceptParentInvite(request("accept-parent-invite", { code: " 7k3m-q9xz " }), deps);
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.familyId, FAMILY);
  assertEquals(body.familyName, "Rivera family");
  assertEquals(body.joined, true);
  assertEquals(seen[0], "digest:7K3MQ9XZ");
});

Deno.test("accept-parent-invite refuses wrong codes alike and locks after too many", async () => {
  const invalid = acceptDeps("invalid");
  assertEquals((await handleAcceptParentInvite(request("accept-parent-invite", { code: "7K3M-Q9XZ" }), invalid.deps)).status, 400);
  // A code that cannot exist still counts as a try.
  assertEquals((await handleAcceptParentInvite(request("accept-parent-invite", { code: "nope" }), invalid.deps)).status, 400);
  assertEquals(invalid.seen.length, 2);
  const locked = acceptDeps("locked");
  assertEquals((await handleAcceptParentInvite(request("accept-parent-invite", { code: "7K3M-Q9XZ" }), locked.deps)).status, 403);
  assertEquals((await handleAcceptParentInvite(request("accept-parent-invite", { code: "7K3M-Q9XZ", familyId: FAMILY }), invalid.deps)).status, 400);
});

Deno.test("invite codes are eight readable characters and confusable letters read as digits", () => {
  for (let i = 0; i < 50; i++) {
    const code = generateInviteCode();
    assertEquals(normalizeInviteCode(code), code);
    assert(!/[ILOU]/.test(code));
  }
  assertEquals(normalizeInviteCode("abcd-efgh"), "ABCDEFGH");
  assertEquals(normalizeInviteCode("o1il 2345"), "01112345");
  assertEquals(normalizeInviteCode("ABCDEFG"), null);
  assertEquals(normalizeInviteCode("ABCDEFGU"), null);
  assertEquals(normalizeInviteCode(12345678), null);
  assertEquals(formatInviteCode("7K3MQ9XZ"), "7K3M-Q9XZ");
});
