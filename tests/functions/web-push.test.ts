import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  createRegisterWebPushHandler,
  type RegisterWebPushDeps,
} from "../../supabase/functions/register-web-push/index.ts";
import {
  createRemoveWebPushHandler,
  type RemoveWebPushDeps,
} from "../../supabase/functions/remove-web-push/index.ts";
import {
  sendWebPush,
  type WebPushTransport,
} from "../../supabase/functions/_shared/web-push.ts";

const parent = {
  userId: "11111111-1111-4111-8111-111111111111",
  accessToken: "parent-token",
  aal: "aal1" as const,
  amr: [],
};

const subscription = {
  clientInstallationId: "parent-browser-1",
  endpoint: "https://push.example.test/subscription/1",
  keys: { p256dh: "p256dh-key", auth: "auth-key" },
};

function registerRequest(body: unknown = subscription) {
  return new Request("https://harbor.test/functions/v1/register-web-push", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

function removeRequest(body: unknown = {
  clientInstallationId: subscription.clientInstallationId,
  endpoint: subscription.endpoint,
}) {
  return new Request("https://harbor.test/functions/v1/remove-web-push", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

Deno.test("register-web-push requires an authenticated parent before mutation", async () => {
  let registered = false;
  const deps = {
    requireParent: async () => { throw new Error("AUTH_REQUIRED"); },
    registerSubscription: async () => { registered = true; },
  } as RegisterWebPushDeps;

  await assertRejects(() => createRegisterWebPushHandler(deps)(registerRequest()), Error, "AUTH_REQUIRED");
  assertEquals(registered, false);
});

Deno.test("register-web-push binds installation and endpoint to authenticated user", async () => {
  let actual: unknown;
  const deps = {
    requireParent: async () => parent,
    registerSubscription: async (input: unknown) => { actual = input; },
  } as RegisterWebPushDeps;

  const response = await createRegisterWebPushHandler(deps)(registerRequest());
  assertEquals(response.status, 204);
  assertEquals(actual, { userId: parent.userId, ...subscription });
});

Deno.test("register-web-push ignores a client supplied user id", async () => {
  let actual: unknown;
  const deps = {
    requireParent: async () => parent,
    registerSubscription: async (input: unknown) => { actual = input; },
  } as RegisterWebPushDeps;

  const response = await createRegisterWebPushHandler(deps)(registerRequest({
    ...subscription,
    userId: "22222222-2222-4222-8222-222222222222",
  }));

  assertEquals(response.status, 204);
  assertEquals(actual, { userId: parent.userId, ...subscription });
});

Deno.test("duplicate web-push registration is accepted idempotently", async () => {
  let calls = 0;
  const deps = {
    requireParent: async () => parent,
    registerSubscription: async () => { calls++; },
  } as RegisterWebPushDeps;
  const handler = createRegisterWebPushHandler(deps);

  assertEquals((await handler(registerRequest())).status, 204);
  assertEquals((await handler(registerRequest())).status, 204);
  assertEquals(calls, 2);
});

Deno.test("remove-web-push binds removal to authenticated user installation", async () => {
  let actual: unknown;
  const deps = {
    requireParent: async () => parent,
    removeSubscription: async (input: unknown) => { actual = input; },
  } as RemoveWebPushDeps;

  const response = await createRemoveWebPushHandler(deps)(removeRequest());
  assertEquals(response.status, 204);
  assertEquals(actual, {
    userId: parent.userId,
    clientInstallationId: subscription.clientInstallationId,
    endpoint: subscription.endpoint,
  });
});

Deno.test("web-push delivery serializes only NotificationRouteRefV1", async () => {
  let payload = "";
  const transport: WebPushTransport = async (_target, body) => {
    payload = body;
    return { statusCode: 201 };
  };
  const route = { route: "/alerts", notificationId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" };

  const result = await sendWebPush(subscription, route, transport);
  assertEquals(result, { kind: "sent" });
  assertEquals(JSON.parse(payload), route);
});

Deno.test("web-push classifies 404 and 410 as permanent invalid subscription", async () => {
  for (const statusCode of [404, 410]) {
    const result = await sendWebPush(subscription, { route: "/alerts", notificationId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }, async () => ({ statusCode }));
    assertEquals(result, { kind: "permanent_invalid_subscription" });
  }
});

Deno.test("web-push classifies 429 5xx and network failure as retryable", async () => {
  for (const statusCode of [429, 500, 503]) {
    const result = await sendWebPush(subscription, { route: "/alerts", notificationId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }, async () => ({ statusCode }));
    assertEquals(result, { kind: "retryable" });
  }
  const network = await sendWebPush(subscription, { route: "/alerts", notificationId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }, async () => { throw new Error("network"); });
  assertEquals(network, { kind: "retryable" });
});
