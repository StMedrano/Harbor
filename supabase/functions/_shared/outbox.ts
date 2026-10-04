import type { NotificationRouteRefV1 } from "../../../packages/contracts/src/v1/notifications.ts";
import { createDispatchOne, type OutboxFailure } from "./outbox-dispatch.ts";
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
  complete(id: string, attempt: number): Promise<string | null>;
  fail(id: string, failure: OutboxFailure, attempt: number): Promise<string | null>;
  readWebPush(id: string, familyId?: string): Promise<WebPushSubscription | null>;
  readFcmToken(deviceId: string): Promise<string | null>;
  disableWebPush(id: string, outboxId: string, attempt: number): Promise<void>;
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
    async complete(id, attempt) {
      if (await store.complete(id, attempt) !== "sent") throw new Error("Outbox completion was not confirmed");
    },
    async fail(id, failure, attempt) {
      if (await store.fail(id, failure, attempt) !== (failure.retryable ? "retry" : "dead_letter")) throw new Error("Outbox failure transition was not confirmed");
    },
    async sendWebPush(target, payload) {
      const subscription = target.subscriptionId ? await store.readWebPush(target.subscriptionId, payload.familyId) : null;
      return subscription
        ? await (transports.sendWebPush ?? sendWebPush)(subscription, payload)
        : { status: "permanent_failure", reason: "provider_rejected" };
    },
    async sendFcm(target, payload) {
      const token = target.deviceId ? await store.readFcmToken(target.deviceId) : null;
      return token
        ? await transports.sendFcm(token, payload)
        : { status: "permanent_failure", reason: "provider_rejected" };
    },
    async disableWebPush(id, outboxId, attempt) { if (id) await store.disableWebPush(id, outboxId, attempt); },
  });
}

export const privateOutboxStore: OutboxStore = {
  async claim(id, now) {
    return await getPrivateSql()<OutboxRow[]>`select id, transport, target_ref, route_payload, attempt_count from private.harbor_claim_notification(${id}::uuid, ${now}::timestamptz)`;
  },
  async complete(id, attempt) {
    const rows = await getPrivateSql()<Array<{ status: string | null }>>`select private.harbor_complete_notification(${id}::uuid, ${attempt}::integer) as status`;
    return rows[0]?.status ?? null;
  },
  async fail(id, failure, attempt) {
    const rows = await getPrivateSql()<Array<{ status: string | null }>>`select private.harbor_fail_notification(${id}::uuid, ${attempt}::integer, ${failure.retryable}::boolean, ${failure.errorCategory}::text, ${failure.nextAttemptAt}::timestamptz) as status`;
    return rows[0]?.status ?? null;
  },
  async readWebPush(id, familyId) {
    const rows = await getPrivateSql()<WebPushSubscription[]>`select s.endpoint, s.p256dh, s.auth from private.parent_web_push_subscriptions s where s.id = ${id}::uuid and s.status = 'active' and (${familyId ?? null}::uuid is null or exists (select 1 from public.family_members fm where fm.user_id = s.user_id and fm.family_id = ${familyId ?? null}::uuid and fm.status = 'active' and fm.role in ('owner', 'parent')))`;
    return rows[0] ?? null;
  },
  async readFcmToken(deviceId) {
    const rows = await getPrivateSql()<Array<{ token: string }>>`select f.token from private.device_fcm_registrations f join public.devices_public d on d.id = f.device_id where f.device_id = ${deviceId}::uuid and d.status = 'active' and d.revoked_at is null`;
    return rows[0]?.token ?? null;
  },
  async disableWebPush(id, outboxId, attempt) {
    await getPrivateSql()`update private.parent_web_push_subscriptions set status = 'invalid', disabled_at = now(), updated_at = now() where id = ${id}::uuid and status = 'active' and exists (select 1 from private.notification_outbox o where o.id = ${outboxId}::uuid and o.attempt_count = ${attempt}::integer and o.status = 'processing' and o.next_attempt_at > now() and o.target_ref->>'subscriptionId' = ${id})`;
  },
};
