import type { NotificationRouteRefV1 } from "../../../packages/contracts/src/v1/notifications.ts";
import { createDispatchOne, type OutboxFailure } from "../dispatch-outbox/index.ts";
import { getPrivateSql } from "./clients.ts";
import { sendWebPush, type PushDeliveryResult, type WebPushSubscription } from "./web-push.ts";

type OutboxRow = {
  id: string;
  transport: "fcm" | "web_push";
  target_ref: Record<string, string>;
  route_payload: unknown;
  attempt_count: number;
};

export type OutboxStore = {
  claim(id: string, now: string): Promise<OutboxRow[]>;
  complete(id: string): Promise<string | null>;
  fail(id: string, failure: OutboxFailure): Promise<string | null>;
  readWebPush(id: string): Promise<WebPushSubscription | null>;
  readFcmToken(deviceId: string): Promise<string | null>;
  disableWebPush(id: string): Promise<void>;
};

type OutboxTransports = {
  sendFcm(token: string, payload: NotificationRouteRefV1): Promise<PushDeliveryResult>;
  sendWebPush?(subscription: WebPushSubscription, payload: NotificationRouteRefV1): Promise<PushDeliveryResult>;
  now?(): Date;
};

export function createPersistentDispatchOne(store: OutboxStore, transports: OutboxTransports) {
  const now = transports.now ?? (() => new Date());
  return createDispatchOne({
    now,
    async claim(id) {
      const row = (await store.claim(id, now().toISOString()))[0];
      return row ? { id: row.id, transport: row.transport, targetRef: row.target_ref, routePayload: row.route_payload, attemptCount: row.attempt_count } : null;
    },
    async complete(id) {
      if (await store.complete(id) !== "sent") throw new Error("Outbox completion was not confirmed");
    },
    async fail(id, failure) {
      if (await store.fail(id, failure) !== (failure.retryable ? "retry" : "dead_letter")) throw new Error("Outbox failure transition was not confirmed");
    },
    async sendWebPush(target, payload) {
      const subscription = target.subscriptionId ? await store.readWebPush(target.subscriptionId) : null;
      return subscription
        ? await (transports.sendWebPush ?? sendWebPush)(subscription, payload)
        : { status: "permanent_failure", reason: "invalid_subscription" };
    },
    async sendFcm(target, payload) {
      const token = target.deviceId ? await store.readFcmToken(target.deviceId) : null;
      return token
        ? await transports.sendFcm(token, payload)
        : { status: "permanent_failure", reason: "provider_rejected" };
    },
    async disableWebPush(id) { if (id) await store.disableWebPush(id); },
  });
}

export const privateOutboxStore: OutboxStore = {
  async claim(id, now) {
    return await getPrivateSql()<OutboxRow[]>`select id, transport, target_ref, route_payload, attempt_count from private.harbor_claim_notification(${id}::uuid, ${now}::timestamptz)`;
  },
  async complete(id) {
    const rows = await getPrivateSql()<Array<{ status: string | null }>>`select private.harbor_complete_notification(${id}::uuid) as status`;
    return rows[0]?.status ?? null;
  },
  async fail(id, failure) {
    const rows = await getPrivateSql()<Array<{ status: string | null }>>`select private.harbor_fail_notification(${id}::uuid, ${failure.retryable}::boolean, ${failure.errorCategory}::text, ${failure.nextAttemptAt}::timestamptz) as status`;
    return rows[0]?.status ?? null;
  },
  async readWebPush(id) {
    const rows = await getPrivateSql()<WebPushSubscription[]>`select endpoint, p256dh, auth from private.parent_web_push_subscriptions where id = ${id}::uuid and status = 'active'`;
    return rows[0] ?? null;
  },
  async readFcmToken(deviceId) {
    const rows = await getPrivateSql()<Array<{ token: string }>>`select f.token from private.device_fcm_registrations f join public.devices_public d on d.id = f.device_id where f.device_id = ${deviceId}::uuid and d.status = 'active' and d.revoked_at is null`;
    return rows[0]?.token ?? null;
  },
  async disableWebPush(id) {
    await getPrivateSql()`update private.parent_web_push_subscriptions set status = 'invalid', disabled_at = now(), updated_at = now() where id = ${id}::uuid and status = 'active'`;
  },
};
