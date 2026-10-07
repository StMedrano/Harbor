import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import type { NotificationRouteRefV1 } from "../../packages/contracts/src/v1/notifications.ts";
import { createChildAtomic } from "../../supabase/functions/_shared/child-creation.ts";
import {
  createFamilyAtomic,
  getPrivateSql,
  registerDeviceFcmAtomic,
  registerParentWebPushAtomic,
  updateDeviceDesiredStateAtomic,
} from "../../supabase/functions/_shared/clients.ts";
import { registerParentFcmAtomic } from "../../supabase/functions/_shared/parent-fcm.ts";
import {
  createPersistentDispatchOne,
  privateOutboxStore,
} from "../../supabase/functions/_shared/outbox.ts";

Deno.test("parent_lifecycle_preserves_three_transport_identity", async () => {
  const sql = getPrivateSql();
  const actor = crypto.randomUUID(),
    outsider = crypto.randomUUID(),
    childActor = crypto.randomUUID();
  const session = crypto.randomUUID(), device = crypto.randomUUID();
  const families: string[] = [];
  const outbox: string[] = [];
  // Controlled SQL/Auth subjects and provider callbacks prove durable integration.
  // Real Auth issuance, provider acceptance and observed phone receipt are separate hosted gates.
  try {
    await sql`insert into auth.users(id,is_anonymous) values(${actor}::uuid,false),(${outsider}::uuid,false),(${childActor}::uuid,true)`;
    await sql`insert into auth.sessions(id,user_id,created_at,updated_at) values(${session}::uuid,${actor}::uuid,now(),now())`;
    const family = await createFamilyAtomic({
      userId: actor,
      name: "Parent foundation fixture",
      idempotencyKey: "family",
    });
    families.push(family.familyId);
    const other = await createFamilyAtomic({
      userId: outsider,
      name: "Other foundation fixture",
      idempotencyKey: "other",
    });
    families.push(other.familyId);
    const input = {
      userId: actor,
      familyId: family.familyId,
      displayName: "Fixture child",
      idempotencyKey: "child",
      payloadHash: "a".repeat(64),
    };
    const child = await createChildAtomic(input);
    assertEquals(await createChildAtomic(input), child);
    await assertRejects(() =>
      createChildAtomic({
        ...input,
        userId: outsider,
        idempotencyKey: "foreign",
      })
    );
    await sql`insert into public.devices_public(id,family_id,child_id,display_name) values(${device}::uuid,${family.familyId}::uuid,${child.id}::uuid,'Fixture phone')`;
    await sql`insert into private.device_security(device_id,auth_user_id,public_key_spki) values(${device}::uuid,${childActor}::uuid,'synthetic-key')`;
    await registerDeviceFcmAtomic({
      deviceId: device,
      token: `synthetic-child-${device}`,
    });
    await registerParentWebPushAtomic({
      userId: actor,
      clientInstallationId: "browser",
      endpoint: `https://push.test/${device}`,
      p256dh: "synthetic-public",
      auth: "synthetic-auth",
    });
    const registration = await registerParentFcmAtomic({
      userId: actor,
      sessionId: session,
      clientInstallationId: "parent-android",
      token: `synthetic-parent-${device}`,
    });
    assertEquals(
      await registerParentFcmAtomic({
        userId: actor,
        sessionId: session,
        clientInstallationId: "parent-android",
        token: `synthetic-parent-${device}`,
      }),
      registration,
    );
    assertEquals(
      await privateOutboxStore.readParentFcm(
        registration.registrationId,
        other.familyId,
      ),
      null,
    );
    await assertRejects(() =>
      updateDeviceDesiredStateAtomic({
        deviceId: device,
        familyId: family.familyId,
        actorUserId: outsider,
        desiredState: {},
        expectedVersion: 0,
      })
    );
    await updateDeviceDesiredStateAtomic({
      deviceId: device,
      familyId: family.familyId,
      actorUserId: actor,
      desiredState: { paused: true },
      expectedVersion: 0,
    });
    const event = `desired-state:${device}:1`;
    const read = async () =>
      Array.from(
        await sql<
          Array<
            {
              id: string;
              transport: string;
              target_ref: Record<string, string>;
              route_payload: NotificationRouteRefV1;
            }
          >
        >`
      select id,transport,target_ref,route_payload from private.notification_outbox where event_key=${event} order by id`,
      );
    const first = await read();
    outbox.push(...first.map((row) => row.id));
    assertEquals(first.length, 3);
    assertEquals(first.filter((row) => row.transport === "web_push").length, 1);
    assertEquals(
      first.filter((row) => row.target_ref.deviceId === device).length,
      1,
    );
    assertEquals(
      first.filter((row) =>
        row.target_ref.parentFcmRegistrationId === registration.registrationId
      ).length,
      1,
    );
    let childSends = 0, parentSends = 0, browserSends = 0;
    const dispatch = createPersistentDispatchOne(privateOutboxStore, {
      sendFcm: async () => {
        childSends++;
        return { status: "sent" };
      },
      sendWebPush: async () => {
        browserSends++;
        return { status: "sent" };
      },
      sendParentFcm: async (_token, route, id) => {
        assertEquals(id, registration.registrationId);
        assertEquals(
          route,
          first.find((row) => row.target_ref.parentFcmRegistrationId)
            ?.route_payload,
        );
        parentSends++;
        return { status: "sent" };
      },
    });
    for (const row of first) {
      assertEquals(await dispatch(row.id), { status: "sent" });
    }
    for (const row of first) {
      assertEquals(await dispatch(row.id), { status: "no_op" });
    }
    assertEquals([childSends, parentSends, browserSends], [1, 1, 1]);
    assertEquals(await read(), first);
    assertEquals(
      Number(
        (await sql`select count(*) as n from public.children where family_id=${family.familyId}::uuid`)[
          0
        ].n,
      ),
      1,
    );
    assertEquals(
      Number(
        (await sql`select desired_state_version as n from private.device_desired_state where device_id=${device}::uuid`)[
          0
        ].n,
      ),
      1,
    );
    // Session deletion models current-device logout independently of JWT expiry.
    await sql`delete from auth.sessions where id=${session}::uuid`;
    assertEquals(
      await privateOutboxStore.readParentFcm(
        registration.registrationId,
        family.familyId,
      ),
      null,
    );
    await assertRejects(() =>
      registerParentFcmAtomic({
        userId: actor,
        sessionId: session,
        clientInstallationId: "parent-android",
        token: "synthetic-after-logout",
      })
    );
  } finally {
    if (outbox.length) {
      await sql`delete from private.notification_outbox where id in ${
        sql(outbox)
      }`;
    }
    if (families.length) {
      await sql`delete from public.families where id in ${sql(families)}`;
    }
    await sql`delete from auth.users where id in (${actor}::uuid,${outsider}::uuid,${childActor}::uuid)`;
    // Audit records are retained; these exact subjects/families are disposable CI fixtures.
    assertEquals(
      Number(
        (await sql`select count(*) as n from auth.users where id in (${actor}::uuid,${outsider}::uuid,${childActor}::uuid)`)[
          0
        ].n,
      ),
      0,
    );
    await sql.end();
  }
});
