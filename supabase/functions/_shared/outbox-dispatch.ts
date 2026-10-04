import type { NotificationRouteRefV1 } from "../../../packages/contracts/src/v1/notifications.ts";
import { notificationRoute } from "./notification.ts";
import type { PushDeliveryResult } from "./web-push.ts";

export type OutboxNotification = {
  id: string;
  transport: "fcm" | "web_push";
  targetRef: Record<string, string>;
  routePayload: unknown;
  attemptCount: number;
};

export type DeliveryOutcome = { status: "sent" | "retry" | "dead_letter" | "no_op" };
export type OutboxFailure = { retryable: boolean; errorCategory: string; nextAttemptAt: string | null };
export type DispatchDependencies = {
  now(): Date;
  claim(id: string): Promise<OutboxNotification | null>;
  complete(id: string): Promise<void>;
  fail(id: string, failure: OutboxFailure): Promise<void>;
  sendFcm(target: Record<string, string>, payload: NotificationRouteRefV1): Promise<PushDeliveryResult>;
  sendWebPush(target: Record<string, string>, payload: NotificationRouteRefV1): Promise<PushDeliveryResult>;
  disableWebPush(subscriptionId: string): Promise<void>;
};

export function createDispatchOne(dependencies: DispatchDependencies) {
  return async function dispatchOne(outboxId: string): Promise<DeliveryOutcome> {
    const row = await dependencies.claim(outboxId);
    if (!row) return { status: "no_op" };
    const payload = notificationRoute(row.routePayload);
    if (!payload) {
      await dependencies.fail(row.id, { retryable: false, errorCategory: "invalid_payload", nextAttemptAt: null });
      return { status: "dead_letter" };
    }

    let result: PushDeliveryResult;
    try {
      result = row.transport === "fcm"
        ? await dependencies.sendFcm(row.targetRef, payload)
        : await dependencies.sendWebPush(row.targetRef, payload);
    } catch {
      result = { status: "retryable_failure", reason: "network_error" };
    }

    if (result.status === "sent") {
      await dependencies.complete(row.id);
      return { status: "sent" };
    }
    if (row.transport === "web_push" && result.reason === "invalid_subscription") {
      await dependencies.disableWebPush(row.targetRef.subscriptionId);
    }
    const retryable = result.status === "retryable_failure";
    const delaySeconds = Math.min(3600, 60 * 2 ** Math.min(6, Math.max(0, row.attemptCount - 1)));
    await dependencies.fail(row.id, {
      retryable,
      errorCategory: result.reason,
      nextAttemptAt: retryable ? new Date(dependencies.now().getTime() + delaySeconds * 1000).toISOString() : null,
    });
    return { status: retryable ? "retry" : "dead_letter" };
  };
}
