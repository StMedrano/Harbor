import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { createRevokeDeviceHandler, type RevokeDeviceDeps } from "../../supabase/functions/revoke-device/index.ts";

const deviceId = "11111111-1111-4111-8111-111111111111";
const familyId = "22222222-2222-4222-8222-222222222222";

function request() {
  return new Request("https://example.test/revoke-device", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ deviceId, familyId }),
  });
}

Deno.test("revoke-device requires current owner/parent family authorization", async () => {
  let revoked = false;
  const deps = {
    requireParent: async () => ({ userId: "parent", aal: "aal2", amr: [{ method: "totp", timestamp: 1_759_577_000 }] }),
    requireFamilyRole: async () => { throw new Error("FORBIDDEN"); },
    requireRecentAal2: () => undefined,
    revokeDevice: async () => { revoked = true; },
    nowEpochSeconds: () => 1_759_577_100,
  } as unknown as RevokeDeviceDeps;
  await assertRejects(() => createRevokeDeviceHandler(deps)(request()), Error, "FORBIDDEN");
  assertEquals(revoked, false);
});

Deno.test("revoke-device requires recent AAL2 before mutation", async () => {
  let revoked = false;
  const deps = {
    requireParent: async () => ({ userId: "parent", aal: "aal1", amr: [] }),
    requireFamilyRole: async () => undefined,
    requireRecentAal2: () => { throw new Error("MFA_REQUIRED"); },
    revokeDevice: async () => { revoked = true; },
    nowEpochSeconds: () => 1_759_577_100,
  } as unknown as RevokeDeviceDeps;
  await assertRejects(() => createRevokeDeviceHandler(deps)(request()), Error, "MFA_REQUIRED");
  assertEquals(revoked, false);
});

Deno.test("revoke-device performs one audited revocation after authorization and AAL2", async () => {
  let calls = 0;
  const deps = {
    requireParent: async () => ({ userId: "parent", aal: "aal2", amr: [{ method: "totp", timestamp: 1_759_577_000 }] }),
    requireFamilyRole: async (_ctx: unknown, id: string, roles: string[]) => {
      assertEquals(id, familyId);
      assertEquals(roles, ["owner", "parent"]);
    },
    requireRecentAal2: () => undefined,
    revokeDevice: async (input: unknown) => { calls++; assertEquals(input, { deviceId, familyId, actorUserId: "parent" }); },
    nowEpochSeconds: () => 1_759_577_100,
  } as unknown as RevokeDeviceDeps;
  const response = await createRevokeDeviceHandler(deps)(request());
  assertEquals(response.status, 204);
  assertEquals(calls, 1);
});
