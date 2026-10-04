import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  requireDeviceIdentity,
  type AuthDependencies,
} from "../../supabase/functions/_shared/auth.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";

function deps(isAnonymous: boolean): AuthDependencies {
  return {
    validateAccessToken: async () => ({
      user: { id: isAnonymous ? "device-auth" : "parent-auth", isAnonymous },
      claims: { aal: "aal1", amr: [] },
    }),
    getFamilyMembership: async () => null,
    getStaffAuthorization: async () => null,
    nowEpochSeconds: () => 2_000_000_000,
  };
}

Deno.test("requireDeviceIdentity accepts the separate anonymous child-device Auth identity", async () => {
  const request = new Request("https://harbor.test/functions/v1/device-sync", {
    headers: { Authorization: "Bearer child-device-token" },
  });

  const context = await requireDeviceIdentity(request, deps(true));
  assertEquals(context, { userId: "device-auth", accessToken: "child-device-token" });
});

Deno.test("requireDeviceIdentity rejects a normal parent identity", async () => {
  const request = new Request("https://harbor.test/functions/v1/device-sync", {
    headers: { Authorization: "Bearer parent-token" },
  });

  await assertRejects(
    () => requireDeviceIdentity(request, deps(false)),
    HarborAuthError,
    "Child-device access is required",
  );
});

Deno.test("requireDeviceIdentity rejects a missing bearer token", async () => {
  await assertRejects(
    () => requireDeviceIdentity(new Request("https://harbor.test/functions/v1/device-sync"), deps(true)),
    HarborAuthError,
    "Authentication is required",
  );
});
