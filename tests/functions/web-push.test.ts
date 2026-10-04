import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import type { ParentContext } from "../../supabase/functions/_shared/auth.ts";
import {
  sendWebPushWithDependencies,
  type WebPushDependencies,
  type WebPushSubscription,
} from "../../supabase/functions/_shared/web-push.ts";
import {
  createRegisterWebPushHandler,
  type RegisterWebPushDeps,
} from "../../supabase/functions/register-web-push/index.ts";
import {
  createRemoveWebPushHandler,
  type RemoveWebPushDeps,
} from "../../supabase/functions/remove-web-push/index.ts";

const parent: ParentContext = {
  userId: "71000000-0000-4000-8000-000000000001",
  accessToken: "parent-token",
  aal: "aal1",
  amr: [{ method: "password", timestamp: 2_000_000_000 }],
};

const subscription: WebPushSubscription = {
  endpoint: "https://push.example.test/subscription",
  p256dh: "p256dh-value",
  auth: "auth-value",
};

const route = {
  version: 1 as const,
  kind: "child.location.changed",
  familyId: "72000000-0000-4000-8000-000000000001",
  childId: "73000000-0000-4000-8000-000000000001",
  resourceId: "74000000-0000-4000-8000-000000000001",
};

function request(path: string, body: unknown) {
  return new Request(`https://harbor.test/functions/v1/${path}`, {
    method: "POST",
    headers: {
      Authorization: "Bearer parent-token",
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

Deno.test("register-web-push requires parent authentication before persistence", async () => {
  let persisted = false;
  const deps = {
    requireParent: async () => { throw new Error("AUTH_REQUIRED"); },
    registerWebPush: async () => { persisted = true; },
  } as RegisterWebPushDeps;

  await assertRejects(
    () => createRegisterWebPushHandler(deps)(request("register-web-push", {
      clientInstallationId: "install-1",
      endpoint: "https://push.example.test/subscription",
      keys: { p256dh: "p256dh-value", auth: "auth-value" },
    })),
    Error,
    "AUTH_REQUIRED",
  );
  assertEquals(persisted, false);
});

Deno.test("register-web-push binds persistence to the authenticated parent", async () => {
  let actual: unknown;
  const deps = {
    requireParent: async () => parent,
    registerWebPush: async (input: unknown) => { actual = input; },
  } as RegisterWebPushDeps;

  const response = await createRegisterWebPushHandler(deps)(request("register-web-push", {
    userId: "71000000-0000-4000-8000-000000000099",
    clientInstallationId: " install-1 ",
    endpoint: " https://push.example.test/subscription ",
    keys: { p256dh: " p256dh-value ", auth: " auth-value " },
  }));

  assertEquals(response.status, 204);
  assertEquals(actual, {
    userId: parent.userId,
    clientInstallationId: "install-1",
    endpoint: "https://push.example.test/subscription",
    p256dh: "p256dh-value",
    auth: "auth-value",
  });
});

Deno.test("register-web-push rejects blank lifecycle fields", async () => {
  let persisted = false;
  const deps = {
    requireParent: async () => parent,
    registerWebPush: async () => { persisted = true; },
  } as RegisterWebPushDeps;

  await assertRejects(
    () => createRegisterWebPushHandler(deps)(request("register-web-push", {
      clientInstallationId: " ",
      endpoint: "https://push.example.test/subscription",
      keys: { p256dh: "p256dh-value", auth: "auth-value" },
    })),
    Error,
    "VALIDATION_FAILED",
  );
  assertEquals(persisted, false);
});

Deno.test("remove-web-push requires parent authentication before mutation", async () => {
  let removed = false;
  const deps = {
    requireParent: async () => { throw new Error("AUTH_REQUIRED"); },
    removeWebPush: async () => { removed = true; },
  } as RemoveWebPushDeps;

  await assertRejects(
    () => createRemoveWebPushHandler(deps)(request("remove-web-push", {
      clientInstallationId: "install-1",
    })),
    Error,
    "AUTH_REQUIRED",
  );
  assertEquals(removed, false);
});

Deno.test("remove-web-push binds removal to the authenticated parent and optional endpoint", async () => {
  let actual: unknown;
  const deps = {
    requireParent: async () => parent,
    removeWebPush: async (input: unknown) => { actual = input; },
  } as RemoveWebPushDeps;

  const response = await createRemoveWebPushHandler(deps)(request("remove-web-push", {
    userId: "71000000-0000-4000-8000-000000000099",
    clientInstallationId: " install-1 ",
    endpoint: " https://push.example.test/subscription ",
  }));

  assertEquals(response.status, 204);
  assertEquals(actual, {
    userId: parent.userId,
    clientInstallationId: "install-1",
    endpoint: "https://push.example.test/subscription",
  });
});

Deno.test("remove-web-push may remove all endpoints for one parent installation", async () => {
  let actual: unknown;
  const deps = {
    requireParent: async () => parent,
    removeWebPush: async (input: unknown) => { actual = input; },
  } as RemoveWebPushDeps;

  const response = await createRemoveWebPushHandler(deps)(request("remove-web-push", {
    clientInstallationId: "install-1",
  }));

  assertEquals(response.status, 204);
  assertEquals(actual, {
    userId: parent.userId,
    clientInstallationId: "install-1",
    endpoint: undefined,
  });
});

Deno.test("sendWebPush sends only the route reference with server-side VAPID details", async () => {
  let actual: unknown;
  const deps: WebPushDependencies = {
    readEnv(name) {
      const values: Record<string, string> = {
        VAPID_SUBJECT: "mailto:ops@harbor.test",
        VAPID_PUBLIC_KEY: "public-vapid-key",
        VAPID_PRIVATE_KEY: "private-vapid-key",
      };
      return values[name];
    },
    async sendNotification(pushSubscription, payload, options) {
      actual = { pushSubscription, payload, options };
      return { statusCode: 201 };
    },
  };

  const result = await sendWebPushWithDependencies(subscription, route, deps);

  assertEquals(result, { status: "sent" });
  assertEquals(actual, {
    pushSubscription: {
      endpoint: subscription.endpoint,
      keys: { p256dh: subscription.p256dh, auth: subscription.auth },
    },
    payload: JSON.stringify(route),
    options: {
      vapidDetails: {
        subject: "mailto:ops@harbor.test",
        publicKey: "public-vapid-key",
        privateKey: "private-vapid-key",
      },
    },
  });
});

Deno.test("sendWebPush treats 404 and 410 as permanent invalid subscriptions", async () => {
  for (const statusCode of [404, 410]) {
    const deps: WebPushDependencies = {
      readEnv: () => "configured",
      sendNotification: async () => {
        const error = new Error("gone") as Error & { statusCode: number };
        error.statusCode = statusCode;
        throw error;
      },
    };

    assertEquals(
      await sendWebPushWithDependencies(subscription, route, deps),
      { status: "permanent_failure", reason: "invalid_subscription" },
    );
  }
});

Deno.test("sendWebPush treats rate limits and provider failures as retryable", async () => {
  for (const [statusCode, reason] of [[429, "rate_limited"], [503, "provider_error"]] as const) {
    const deps: WebPushDependencies = {
      readEnv: () => "configured",
      sendNotification: async () => {
        const error = new Error("provider failure") as Error & { statusCode: number };
        error.statusCode = statusCode;
        throw error;
      },
    };

    assertEquals(
      await sendWebPushWithDependencies(subscription, route, deps),
      { status: "retryable_failure", reason },
    );
  }
});

Deno.test("sendWebPush treats network errors as retryable", async () => {
  const deps: WebPushDependencies = {
    readEnv: () => "configured",
    sendNotification: async () => { throw new Error("socket reset"); },
  };

  assertEquals(
    await sendWebPushWithDependencies(subscription, route, deps),
    { status: "retryable_failure", reason: "network_error" },
  );
});
