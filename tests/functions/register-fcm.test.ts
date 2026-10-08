import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  createRegisterFcmHandler,
  type RegisterFcmDeps,
} from "../../supabase/functions/register-fcm/index.ts";

const deviceContext = {
  deviceId: "11111111-1111-4111-8111-111111111111",
  familyId: "22222222-2222-4222-8222-222222222222",
  childId: "33333333-3333-4333-8333-333333333333",
  authUserId: "44444444-4444-4444-8444-444444444444",
};

function request(token: unknown = "fcm-token-current") {
  return new Request("https://harbor.test/functions/v1/register-fcm", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token }),
  });
}

Deno.test("register-fcm requires device proof before private token mutation", async () => {
  let registered = false;
  const deps = {
    requireDeviceProof: async () => { throw new Error("FORBIDDEN"); },
    registerFcm: async () => { registered = true; },
  } as RegisterFcmDeps;

  await assertRejects(() => createRegisterFcmHandler(deps)(request()), Error, "FORBIDDEN");
  assertEquals(registered, false);
});

Deno.test("register-fcm persists only against the proof-bound device", async () => {
  let actual: unknown;
  const deps = {
    requireDeviceProof: async () => deviceContext,
    registerFcm: async (input: unknown) => { actual = input; },
  } as RegisterFcmDeps;

  const response = await createRegisterFcmHandler(deps)(request());
  assertEquals(response.status, 204);
  assertEquals(actual, { deviceId: deviceContext.deviceId, token: "fcm-token-current" });
});

Deno.test("register-fcm rejects a missing or blank token", async () => {
  let registered = false;
  const deps = {
    requireDeviceProof: async () => deviceContext,
    registerFcm: async () => { registered = true; },
  } as RegisterFcmDeps;

  await assertRejects(() => createRegisterFcmHandler(deps)(request("   ")), Error, "VALIDATION_FAILED");
  assertEquals(registered, false);
});
