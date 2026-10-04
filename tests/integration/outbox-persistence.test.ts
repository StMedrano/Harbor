import { assertEquals } from "jsr:@std/assert@1";
import { getPrivateSql } from "../../supabase/functions/_shared/clients.ts";
import { createPersistentDispatchOne, privateOutboxStore } from "../../supabase/functions/_shared/outbox.ts";

Deno.test("private dispatcher SQL persists completion, retry and invalid-subscription cleanup", async () => {
  const sql = getPrivateSql();
  const userId = crypto.randomUUID();
  const subscriptionId = crypto.randomUUID();
  const familyId = crypto.randomUUID();
  const eventKey = `integration-${crypto.randomUUID()}`;
  const route = { version: 1, kind: "device.state.changed", familyId };
  try {
    await sql`insert into auth.users(id, email, raw_app_meta_data, raw_user_meta_data) values (${userId}::uuid, ${`${userId}@harbor.test`}, '{}'::jsonb, '{}'::jsonb)`;
    await sql`insert into private.parent_web_push_subscriptions(id, user_id, client_installation_id, endpoint, endpoint_hash, p256dh, auth) values (${subscriptionId}::uuid, ${userId}::uuid, 'integration', 'https://push.example.test/integration', ${'a'.repeat(64)}, 'public-key', 'auth-key')`;
    await sql`insert into public.families(id, name) values (${familyId}::uuid, 'outbox integration')`;
    await sql`insert into public.family_members(family_id, user_id, role) values (${familyId}::uuid, ${userId}::uuid, 'owner')`;
    const rows = await sql<Array<{ id: string }>>`select private.harbor_enqueue_notification(${eventKey}, 'web_push', ${sql.json({ subscriptionId })}::jsonb, ${sql.json(route)}::jsonb) as id`;
    const id = rows[0].id;
    let sends = 0;
    const dispatch = createPersistentDispatchOne(privateOutboxStore, {
      sendFcm: async () => { throw new Error("wrong transport"); },
      sendWebPush: async (subscription, payload) => {
        assertEquals(subscription.endpoint, "https://push.example.test/integration");
        assertEquals(payload, route);
        sends++;
        return { status: "sent" };
      },
    });
    assertEquals(await dispatch(id), { status: "sent" });
    assertEquals(await dispatch(id), { status: "no_op" });
    assertEquals(sends, 1);
    const sent = await sql<Array<{ status: string; attempt_count: number }>>`select status, attempt_count from private.notification_outbox where id = ${id}::uuid`;
    assertEquals(sent[0], { status: "sent", attempt_count: 1 });

    await sql`update public.family_members set status = 'removed' where family_id = ${familyId}::uuid`;
    assertEquals(await privateOutboxStore.readWebPush(subscriptionId, familyId), null);
    const removed = await sql<Array<{ id: string }>>`select private.harbor_enqueue_notification(${eventKey + '-removed'}, 'web_push', ${sql.json({ subscriptionId })}::jsonb, ${sql.json(route)}::jsonb) as id`;
    assertEquals(await dispatch(removed[0].id), { status: "dead_letter" });
    assertEquals(sends, 1);
    assertEquals((await privateOutboxStore.readWebPush(subscriptionId))?.endpoint, "https://push.example.test/integration");
    await sql`update public.family_members set status = 'active' where family_id = ${familyId}::uuid`;
    // This second intent exercises real private target resolution and cleanup.
    const invalid = await sql<Array<{ id: string }>>`select private.harbor_enqueue_notification(${eventKey + '-invalid'}, 'web_push', ${sql.json({ subscriptionId })}::jsonb, ${sql.json(route)}::jsonb) as id`;
    // An expired worker cannot remove a target or overwrite a recovered attempt.
    await privateOutboxStore.claim(invalid[0].id, new Date().toISOString());
    await sql`update private.notification_outbox set next_attempt_at = now() - interval '1 second' where id = ${invalid[0].id}::uuid`;
    assertEquals(await privateOutboxStore.complete(invalid[0].id, 1), null);
    await privateOutboxStore.disableWebPush(subscriptionId, invalid[0].id, 1);
    assertEquals((await privateOutboxStore.readWebPush(subscriptionId))?.endpoint, "https://push.example.test/integration");
    assertEquals((await privateOutboxStore.claim(invalid[0].id, new Date().toISOString()))[0].attempt_count, 2);
    await privateOutboxStore.disableWebPush(subscriptionId, invalid[0].id, 1);
    assertEquals((await privateOutboxStore.readWebPush(subscriptionId))?.endpoint, "https://push.example.test/integration");
    assertEquals(await privateOutboxStore.fail(invalid[0].id, { retryable: false, errorCategory: "invalid_subscription", nextAttemptAt: null }, 1), null);
    // Release the second lease so the normal dispatcher can recover and clean up.
    await sql`update private.notification_outbox set next_attempt_at = now() - interval '1 second' where id = ${invalid[0].id}::uuid`;
    assertEquals(await createPersistentDispatchOne(privateOutboxStore, {
      sendFcm: async () => { throw new Error("wrong transport"); },
      sendWebPush: async () => ({ status: "permanent_failure", reason: "invalid_subscription" }),
    })(invalid[0].id), { status: "dead_letter" });
    assertEquals(await privateOutboxStore.readWebPush(subscriptionId), null);
    assertEquals(await privateOutboxStore.readFcmToken(crypto.randomUUID()), null);

    const retry = await sql<Array<{ id: string }>>`select private.harbor_enqueue_notification(${eventKey + '-retry'}, 'fcm', '{}'::jsonb, ${sql.json(route)}::jsonb) as id`;
    await privateOutboxStore.claim(retry[0].id, new Date().toISOString());
    assertEquals(await privateOutboxStore.fail(retry[0].id, { retryable: true, errorCategory: "rate_limited", nextAttemptAt: "2099-01-01T00:00:00Z" }, 1), "retry");
    assertEquals((await privateOutboxStore.claim(retry[0].id, new Date().toISOString())).length, 0);
  } finally {
    await sql`delete from private.notification_outbox where event_key in (${eventKey}, ${eventKey + '-invalid'}, ${eventKey + '-retry'}, ${eventKey + '-removed'})`;
    await sql`delete from public.families where id = ${familyId}::uuid`;
    await sql`delete from auth.users where id = ${userId}::uuid`;
    await sql.end();
  }
});
