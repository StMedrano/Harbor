import { assertEquals } from "jsr:@std/assert@1";
import { sendParentFcmWithDependencies } from "../../supabase/functions/_shared/parent-fcm-delivery.ts";
import { createPersistentDispatchOne } from "../../supabase/functions/_shared/outbox.ts";
const reg = "40000000-0000-4000-8000-000000000121",
  user = "40000000-0000-4000-8000-000000000101",
  session = "40000000-0000-4000-8000-000000000111",
  outbox = "40000000-0000-4000-8000-000000000131";
const route = {
  version: 1 as const,
  kind: "device.state.changed",
  familyId: "40000000-0000-4000-8000-000000000141",
};
const recipient = {
  registrationId: reg,
  userId: user,
  sessionId: session,
  token: "private-fixture-token",
  tokenHash: "a".repeat(64),
};
Deno.test("parent FCM envelope is data-only with exactly route and registration ID strings", async () => {
  let body: unknown;
  assertEquals(
    await sendParentFcmWithDependencies(recipient.token, route, reg, {
      projectId: "harbor-test",
      getAccessToken: async () => "oauth",
      fetch: async (_url: string, options: RequestInit) => {
        body = JSON.parse(options.body as string);
        return new Response("{}", { status: 200 });
      },
    }),
    { status: "sent" },
  );
  assertEquals(body, {
    message: {
      token: recipient.token,
      data: { route: JSON.stringify(route), parentRegistrationId: reg },
      android: { priority: "high" },
    },
  });
});
function harness(
  target: Record<string, string> = {
    parentFcmRegistrationId: reg,
    userId: user,
  },
) {
  const cleanup: unknown[] = [];
  const store = {
    claim: async () => [{
      id: outbox,
      transport: "fcm" as const,
      target_ref: target,
      route_payload: route,
      attempt_count: 3,
    }],
    complete: async () => "sent",
    fail: async (_id: string, failure: { retryable: boolean }) =>
      failure.retryable ? "retry" : "dead_letter",
    readFcmToken: async () => "child-token",
    readWebPush: async () => null,
    disableWebPush: async () => {},
    readParentFcm: async () => recipient,
    disableParentFcm: async (...args: unknown[]) => {
      cleanup.push(args);
    },
  };
  return { store, cleanup };
}
Deno.test("parent dispatcher resolves current private owner/token and routes to parent transport", async () => {
  const h = harness();
  let sent: unknown;
  const dispatch = createPersistentDispatchOne(h.store, {
    sendFcm: async () => {
      throw Error("child transport must not run");
    },
    sendParentFcm: async (token: string, payload: unknown, id: string) => {
      sent = { token, payload, id };
      return { status: "sent" as const };
    },
  });
  assertEquals(await dispatch(outbox), { status: "sent" });
  assertEquals(sent, { token: recipient.token, payload: route, id: reg });
});
Deno.test("mixed child/parent target is rejected without delivery", async () => {
  const h = harness({
    deviceId: "child-device",
    parentFcmRegistrationId: reg,
    userId: user,
  });
  let calls = 0;
  const dispatch = createPersistentDispatchOne(h.store, {
    sendFcm: async () => {
      calls++;
      return { status: "sent" as const };
    },
    sendParentFcm: async () => {
      calls++;
      return { status: "sent" as const };
    },
  });
  assertEquals(await dispatch(outbox), { status: "dead_letter" });
  assertEquals(calls, 0);
});
Deno.test("foreign owner and removed membership/session cannot receive parent delivery", async () => {
  for (
    const value of [null, {
      ...recipient,
      userId: "40000000-0000-4000-8000-000000000199",
    }]
  ) {
    const h = harness();
    h.store.readParentFcm = async () => value as typeof recipient;
    let calls = 0;
    const dispatch = createPersistentDispatchOne(h.store, {
      sendFcm: async () => ({ status: "sent" as const }),
      sendParentFcm: async () => {
        calls++;
        return { status: "sent" as const };
      },
    });
    assertEquals(await dispatch(outbox), { status: "dead_letter" });
    assertEquals(calls, 0);
  }
});
Deno.test("only explicit invalid token cleanup uses the captured hash and exact attempt lease", async () => {
  for (const reason of ["invalid_token", "provider_rejected"] as const) {
    const h = harness();
    const dispatch = createPersistentDispatchOne(h.store, {
      sendFcm: async () => ({ status: "sent" as const }),
      sendParentFcm: async () => ({
        status: "permanent_failure" as const,
        reason,
      }),
    });
    assertEquals(await dispatch(outbox), { status: "dead_letter" });
    assertEquals(
      h.cleanup,
      reason === "invalid_token" ? [[reg, recipient.tokenHash, outbox, 3]] : [],
    );
  }
});
Deno.test("rotation during send cannot replace captured cleanup generation", async () => {
  const h = harness();
  let captured = recipient.tokenHash;
  const dispatch = createPersistentDispatchOne(h.store, {
    sendFcm: async () => ({ status: "sent" as const }),
    sendParentFcm: async () => {
      h.store.readParentFcm = async () => ({
        ...recipient,
        tokenHash: "b".repeat(64),
      });
      return {
        status: "permanent_failure" as const,
        reason: "invalid_token" as const,
      };
    },
  });
  assertEquals(await dispatch(outbox), { status: "dead_letter" });
  assertEquals(h.cleanup, [[reg, captured, outbox, 3]]);
});
