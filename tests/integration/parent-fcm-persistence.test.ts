import { assertEquals } from "jsr:@std/assert@1";
import { getPrivateSql } from "../../supabase/functions/_shared/clients.ts";
import {
  createPersistentDispatchOne,
  privateOutboxStore,
} from "../../supabase/functions/_shared/outbox.ts";
Deno.test("parent fanout persists independent delivery, current authorization and generation/lease-safe cleanup", async () => {
  const sql = getPrivateSql();
  const user = crypto.randomUUID(),
    childUser = crypto.randomUUID(),
    other = crypto.randomUUID(),
    session = crypto.randomUUID(),
    otherSession = crypto.randomUUID(),
    family = crypto.randomUUID(),
    child = crypto.randomUUID(),
    device = crypto.randomUUID();
  let ids: string[] = [];
  try {
    await sql`insert into auth.users(id,is_anonymous) values(${user}::uuid,false),(${childUser}::uuid,true),(${other}::uuid,false)`;
    await sql`insert into auth.sessions(id,user_id,created_at,updated_at) values(${session}::uuid,${user}::uuid,now(),now()),(${otherSession}::uuid,${other}::uuid,now(),now())`;
    await sql`insert into public.families(id,name) values(${family}::uuid,'Parent fanout integration')`;
    await sql`insert into public.family_members(family_id,user_id,role,status) values(${family}::uuid,${user}::uuid,'owner','active'),(${family}::uuid,${other}::uuid,'parent','active')`;
    await sql`insert into public.children(id,family_id,display_name) values(${child}::uuid,${family}::uuid,'Fixture child')`;
    await sql`insert into public.devices_public(id,family_id,child_id,display_name) values(${device}::uuid,${family}::uuid,${child}::uuid,'Fixture device')`;
    await sql`insert into private.device_security(device_id,auth_user_id,public_key_spki) values(${device}::uuid,${childUser}::uuid,'fixture-key')`;
    await sql`insert into private.device_fcm_registrations(device_id,token) values(${device}::uuid,'child-fixture-token')`;
    await sql`insert into private.parent_web_push_subscriptions(user_id,client_installation_id,endpoint,endpoint_hash,p256dh,auth) values(${user}::uuid,'browser','https://push.test/parent-fanout',${
      "a".repeat(64)
    },'key','auth')`;
    const register = (
      actor: string,
      sid: string,
      install: string,
      token: string,
    ) =>
      sql`select * from private.harbor_register_parent_fcm(${actor}::uuid,${sid}::uuid,${install},${token})`;
    const first =
      (await register(user, session, "one", "parent-fixture-one"))[0]
        .registration_id as string;
    const second =
      (await register(user, session, "two", "parent-fixture-two"))[0]
        .registration_id as string;
    await sql`select * from private.harbor_update_device_desired_state(${device}::uuid,${family}::uuid,${user}::uuid,'{}'::jsonb,0)`;
    const event = `desired-state:${device}:1`;
    const rows = await sql<
      Array<
        {
          id: string;
          target_ref: Record<string, string>;
          route_payload: unknown;
        }
      >
    >`select id,target_ref,route_payload from private.notification_outbox where event_key=${event}`;
    ids = rows.map((r) => r.id);
    assertEquals(rows.length, 4);
    let sent = 0;
    const dispatch = createPersistentDispatchOne(privateOutboxStore, {
      sendFcm: async () => {
        sent++;
        return { status: "sent" };
      },
      sendWebPush: async () => {
        sent++;
        return { status: "sent" };
      },
      sendParentFcm: async (_token: string, _route: unknown, id: string) => {
        if (id === first) {
          await register(user, session, "one", "parent-fixture-rotated");
          return { status: "permanent_failure", reason: "invalid_token" };
        }
        sent++;
        return { status: "sent" };
      },
    });
    for (const row of rows) await dispatch(row.id);
    assertEquals(sent, 3);
    assertEquals(
      (await sql`select active,token from private.parent_fcm_registrations where id=${first}::uuid`)[
        0
      ],
      { active: true, token: "parent-fixture-rotated" },
    );
    assertEquals(await dispatch(rows[0].id), { status: "no_op" });
    const sample = rows[0].route_payload;
    const enqueue = async (key: string, id: string, owner = user) => {
      const row =
        (await sql`select private.harbor_enqueue_notification(${key},'fcm',${
          sql.json({ parentFcmRegistrationId: id, userId: owner })
        }::jsonb,${sql.json(sample)}::jsonb) as id`)[0];
      ids.push(row.id);
      return row.id as string;
    };
    const stale = await enqueue("parent-stale-" + device, first);
    const hash =
      (await privateOutboxStore.readParentFcm(first, family))!.tokenHash;
    await privateOutboxStore.claim(stale, new Date().toISOString());
    await sql`update private.notification_outbox set next_attempt_at=now()-interval '1 second' where id=${stale}::uuid`;
    await privateOutboxStore.disableParentFcm(first, hash, stale, 1);
    assertEquals(await privateOutboxStore.complete(stale, 1), null);
    assertEquals(
      (await privateOutboxStore.readParentFcm(first, family))?.token,
      "parent-fixture-rotated",
    );
    await privateOutboxStore.claim(stale, new Date().toISOString());
    await privateOutboxStore.disableParentFcm(first, hash, stale, 1);
    assertEquals(
      (await privateOutboxStore.readParentFcm(first, family))?.token,
      "parent-fixture-rotated",
    );
    const invalid = await enqueue("parent-invalid-" + device, second);
    assertEquals(
      await createPersistentDispatchOne(privateOutboxStore, {
        sendFcm: async () => ({ status: "sent" }),
        sendParentFcm: async () => ({
          status: "permanent_failure",
          reason: "invalid_token",
        }),
      })(invalid),
      { status: "dead_letter" },
    );
    assertEquals(await privateOutboxStore.readParentFcm(second, family), null);
    await sql`update public.family_members set status='removed' where family_id=${family}::uuid and user_id=${user}::uuid`;
    assertEquals(await privateOutboxStore.readParentFcm(first, family), null);
    await sql`update public.family_members set status='active' where family_id=${family}::uuid and user_id=${user}::uuid`;
    await sql`update auth.sessions set not_after=now()-interval '1 second' where id=${session}::uuid`;
    assertEquals(await privateOutboxStore.readParentFcm(first, family), null);
    await sql`update auth.sessions set not_after=null where id=${session}::uuid`;
    assertEquals(
      (await privateOutboxStore.readParentFcm(first, family))?.userId,
      user,
    );
    const replacement =
      (await register(other, otherSession, "one", "parent-fixture-rotated"))[0]
        .registration_id as string;
    assertEquals(await privateOutboxStore.readParentFcm(first, family), null);
    assertEquals(
      (await privateOutboxStore.readParentFcm(replacement, family))?.userId,
      other,
    );
    await sql`delete from auth.sessions where id=${otherSession}::uuid`;
    assertEquals(
      await privateOutboxStore.readParentFcm(replacement, family),
      null,
    );
  } finally {
    if (ids.length) {
      await sql`delete from private.notification_outbox where id in ${
        sql(ids)
      }`;
    }
    await sql`delete from private.audit_events where actor_user_id in (${user}::uuid,${other}::uuid) or family_id=${family}::uuid`;
    await sql`delete from public.families where id=${family}::uuid`;
    await sql`delete from auth.users where id in (${user}::uuid,${childUser}::uuid,${other}::uuid)`;
    await sql.end();
  }
});
