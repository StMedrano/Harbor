import { sendNotification } from "npm:web-push@3.6.7";
import type { NotificationRouteRefV1 } from "../../../packages/contracts/src/v1/notifications.ts";

export type WebPushSubscription = {
  endpoint: string;
  p256dh: string;
  auth: string;
};

export type PushDeliveryResult =
  | { status: "sent" }
  | { status: "permanent_failure"; reason: "invalid_subscription" | "invalid_token" | "provider_rejected" }
  | { status: "retryable_failure"; reason: "rate_limited" | "provider_error" | "network_error" };

type PushLibrarySubscription = {
  endpoint: string;
  keys: { p256dh: string; auth: string };
};

type PushLibraryOptions = {
  vapidDetails: {
    subject: string;
    publicKey: string;
    privateKey: string;
  };
};

export type WebPushDependencies = {
  readEnv(name: string): string | undefined;
  sendNotification(
    subscription: PushLibrarySubscription,
    payload: string,
    options: PushLibraryOptions,
  ): Promise<{ statusCode?: number }>;
};

function requiredEnv(dependencies: WebPushDependencies, name: string): string {
  const value = dependencies.readEnv(name)?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function statusCodeOf(error: unknown): number | undefined {
  if (!error || typeof error !== "object" || !("statusCode" in error)) return undefined;
  const statusCode = (error as { statusCode?: unknown }).statusCode;
  return typeof statusCode === "number" && Number.isFinite(statusCode) ? statusCode : undefined;
}

export async function sendWebPushWithDependencies(
  subscription: WebPushSubscription,
  route: NotificationRouteRefV1,
  dependencies: WebPushDependencies,
): Promise<PushDeliveryResult> {
  const vapidDetails = {
    subject: requiredEnv(dependencies, "VAPID_SUBJECT"),
    publicKey: requiredEnv(dependencies, "VAPID_PUBLIC_KEY"),
    privateKey: requiredEnv(dependencies, "VAPID_PRIVATE_KEY"),
  };

  try {
    await dependencies.sendNotification(
      {
        endpoint: subscription.endpoint,
        keys: { p256dh: subscription.p256dh, auth: subscription.auth },
      },
      JSON.stringify(route),
      { vapidDetails },
    );
    return { status: "sent" };
  } catch (error) {
    const statusCode = statusCodeOf(error);
    if (statusCode === 404 || statusCode === 410) {
      return { status: "permanent_failure", reason: "invalid_subscription" };
    }
    if (statusCode === 429) {
      return { status: "retryable_failure", reason: "rate_limited" };
    }
    if (statusCode !== undefined && statusCode >= 500) {
      return { status: "retryable_failure", reason: "provider_error" };
    }
    if (statusCode !== undefined) {
      return { status: "permanent_failure", reason: "provider_rejected" };
    }
    return { status: "retryable_failure", reason: "network_error" };
  }
}

const defaultWebPushDependencies: WebPushDependencies = {
  readEnv: (name) => Deno.env.get(name),
  sendNotification,
};

export function sendWebPush(
  subscription: WebPushSubscription,
  route: NotificationRouteRefV1,
): Promise<PushDeliveryResult> {
  return sendWebPushWithDependencies(subscription, route, defaultWebPushDependencies);
}
