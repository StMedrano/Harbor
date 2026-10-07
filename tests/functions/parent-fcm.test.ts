import { assertEquals } from "jsr:@std/assert@1";
import {
  createRegisterParentFcmHandler,
  type RegisterParentFcmDependencies,
} from "../../supabase/functions/register-parent-fcm/index.ts";
import {
  createRemoveParentFcmHandler,
  type RemoveParentFcmDependencies,
} from "../../supabase/functions/remove-parent-fcm/index.ts";
import { HarborAuthError } from "../../supabase/functions/_shared/errors.ts";
const userId = "30000000-0000-4000-8000-000000000101",
  sessionId = "30000000-0000-4000-8000-000000000111",
  registrationId = "30000000-0000-4000-8000-000000000121";
const parent = {
  userId,
  sessionId,
  accessToken: "test-only",
  aal: "aal1" as const,
  amr: [],
};
const body = {
  clientInstallationId: "parent-install-one",
  token: "parent-fcm-fixture-one",
};
function request(body: unknown) {
  return new Request("https://harbor.test", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
function register(
  overrides: Partial<RegisterParentFcmDependencies> = {},
): RegisterParentFcmDependencies {
  return {
    requireActiveParentSession: async () => parent,
    registerParentFcmAtomic: async () => ({ registrationId, active: true }),
    ...overrides,
  };
}
function remove(
  overrides: Partial<RemoveParentFcmDependencies> = {},
): RemoveParentFcmDependencies {
  return {
    requireActiveParentSession: async () => parent,
    removeParentFcmAtomic: async () => {},
    ...overrides,
  };
}
Deno.test("parent FCM register binds verified owner/session, ignoring body authority", async () => {
  let seen: unknown;
  const response = await createRegisterParentFcmHandler(
    register({
      registerParentFcmAtomic: async (input) => {
        seen = input;
        return { registrationId, active: true };
      },
    }),
  )(request({
    ...body,
    userId: crypto.randomUUID(),
    sessionId: crypto.randomUUID(),
  }));
  assertEquals(seen, { ...body, userId, sessionId });
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { registrationId, active: true });
});
for (
  const payload of [
    null,
    [],
    { ...body, token: "" },
    { ...body, clientInstallationId: " " },
    { ...body, token: "fcm\ninvalid" },
    { ...body, token: 1 },
  ]
) {
  Deno.test(`invalid parent FCM register input ${JSON.stringify(payload)}`, async () => {
    let calls = 0;
    const response = await createRegisterParentFcmHandler(
      register({
        registerParentFcmAtomic: async () => {
          calls++;
          return { registrationId, active: true };
        },
      }),
    )(request(payload));
    assertEquals(response.status, 400);
    assertEquals(calls, 0);
  });
}
Deno.test("inactive session cannot register and leaves persistence untouched", async () => {
  let calls = 0;
  const response = await createRegisterParentFcmHandler(register({
    requireActiveParentSession: async () => {
      throw new HarborAuthError("AUTH_REQUIRED", 401, "Session required");
    },
    registerParentFcmAtomic: async () => {
      calls++;
      return { registrationId, active: true };
    },
  }))(request(body));
  assertEquals(response.status, 401);
  assertEquals(calls, 0);
});
Deno.test("parent FCM remove binds verified owner/session and returns204 without body authority", async () => {
  let seen: unknown;
  const response = await createRemoveParentFcmHandler(
    remove({
      removeParentFcmAtomic: async (input) => {
        seen = input;
      },
    }),
  )(request({
    clientInstallationId: body.clientInstallationId,
    userId: crypto.randomUUID(),
    sessionId: crypto.randomUUID(),
  }));
  assertEquals(seen, {
    userId,
    sessionId,
    clientInstallationId: body.clientInstallationId,
  });
  assertEquals(response.status, 204);
  assertEquals(await response.text(), "");
});
Deno.test("inactive session cannot remove registrations", async () => {
  const response = await createRemoveParentFcmHandler(
    remove({
      requireActiveParentSession: async () => {
        throw new HarborAuthError("AUTH_REQUIRED", 401, "Session required");
      },
    }),
  )(request(body));
  assertEquals(response.status, 401);
});
