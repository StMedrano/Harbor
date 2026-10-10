import { strict as assert } from "node:assert";
import postgres from "npm:postgres@3.4.7";
import fixture from "../../apps/parent-android/app/src/test/resources/location-report-v1.json" with {
  type: "json",
};
import { createLocationPersistence } from "../../supabase/functions/_shared/location-persistence.ts";
import type { LocationReportV1 } from "../../packages/contracts/src/v1/location.ts";
import { sha256Hex } from "../../supabase/functions/_shared/crypto.ts";
const PG_URL = Deno.env.get("SUPABASE_DB_URL")!;
function postgresError(code: string, message?: string) {
  return (e: unknown) =>
    typeof e === "object" && e !== null &&
    (e as { code?: string }).code === code &&
    (!message || (e as { message?: string }).message === message);
}
async function fixtureTest(
  run: (
    sql: ReturnType<typeof postgres>,
    api: ReturnType<typeof createLocationPersistence>,
    ctx: {
      deviceId: string;
      familyId: string;
      childId: string;
      authUserId: string;
    },
    parent: {
      userId: string;
      sessionId: string;
      accessToken: string;
      aal: "aal1";
      amr: [];
    },
  ) => Promise<void>,
) {
  const sql = postgres(PG_URL, { max: 4, prepare: false, idle_timeout: 20 });
  const user = crypto.randomUUID(),
    anon = crypto.randomUUID(),
    family = crypto.randomUUID(),
    child = crypto.randomUUID(),
    device = crypto.randomUUID(),
    session = crypto.randomUUID();
  try {
    await sql`insert into auth.users(id,is_anonymous) values(${user}::uuid,false),(${anon}::uuid,true)`;
    await sql`insert into auth.sessions(id,user_id,created_at,updated_at) values(${session}::uuid,${user}::uuid,now(),now())`;
    await sql`insert into public.families(id,name) values(${family}::uuid,'Location fixture')`;
    await sql`insert into public.family_members(family_id,user_id,role,status) values(${family}::uuid,${user}::uuid,'owner','active')`;
    await sql`insert into public.children(id,family_id,display_name) values(${child}::uuid,${family}::uuid,'Location child')`;
    await sql`insert into public.devices_public(id,family_id,child_id,display_name,supervision_mode) values(${device}::uuid,${family}::uuid,${child}::uuid,'Location device','standard')`;
    await sql`insert into private.device_security(device_id,auth_user_id,public_key_spki) values(${device}::uuid,${anon}::uuid,'location-test-public-key')`;
    await run(sql, createLocationPersistence(sql), {
      deviceId: device,
      familyId: family,
      childId: child,
      authUserId: anon,
    }, {
      userId: user,
      sessionId: session,
      accessToken: "fixture-only",
      aal: "aal1",
      amr: [],
    });
  } finally {
    await sql`delete from public.families where id=${family}::uuid`;
    await sql`delete from auth.users where id in (${user}::uuid,${anon}::uuid)`;
    assert.equal(
      (await sql`select count(*)::int n from public.devices_public where id=${device}::uuid`)[
        0
      ].n,
      0,
    );
    await sql.end(); // Own pool only; audits retained, never close the shared helper pool.
  }
}
function minutesAgo(m: number) {
  return new Date(Date.now() - m * 60000).toISOString().replace(
    /\.\d{3}Z$/,
    "Z",
  );
}
// Fix times are relative to the clock so retention (fix time + 30 days) never expires the fixture.
function report(sequence: number, fixes = 2): LocationReportV1 {
  return {
    ...structuredClone(fixture),
    sequence,
    observedAt: minutesAgo(0),
    fixes: Array.from({ length: fixes }, (_, i) => ({
      at: minutesAgo((fixes - i) * 5),
      latitude: 41.878114 + i / 100000,
      longitude: -87.629798,
      accuracyM: 12 + i,
      provider: "fused" as const,
    })),
  } as LocationReportV1;
}
const hashOf = (r: unknown) => sha256Hex(JSON.stringify(r));
Deno.test("location checkpoint/report/no-op/conflict and authorized parent read", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    assert.deepEqual(await api.readLocationCheckpoint(ctx), {
      sequence: 0,
      epochId: null,
    });
    const body = report(1), hash = await hashOf(body);
    const first = await api.writeLocation(ctx, body, hash);
    assert.equal(first.confirmed, true);
    assert.equal(first.sequence, 1);
    assert.deepEqual(await api.writeLocation(ctx, body, hash), first);
    assert.equal(
      (await sql`select count(*)::int n from private.device_location_fixes where device_id=${ctx.deviceId}::uuid`)[
        0
      ].n,
      2,
    );
    const read = await api.readLocation(parent, ctx.deviceId);
    assert.equal(read.state, "available");
    assert.equal(read.status, "sharing");
    assert.equal(read.fixes.length, 2);
    assert.ok(read.fixes[0].at > read.fixes[1].at, "newest first");
    assert.deepEqual(Object.keys(read.fixes[0]).sort(), [
      "accuracyM",
      "at",
      "latitude",
      "longitude",
      "provider",
    ]);
    await assert.rejects(
      api.writeLocation(ctx, body, "f".repeat(64)),
      postgresError("P0001", "IDEMPOTENCY_CONFLICT"),
    );
    await assert.rejects(
      api.readLocationCheckpoint({ ...ctx, authUserId: parent.userId }),
      postgresError("42501"),
    );
    await sql`delete from auth.sessions where id=${parent.sessionId}::uuid`;
    await assert.rejects(
      api.readLocation(parent, ctx.deviceId),
      postgresError("42501"),
    );
  }));
Deno.test("location later batches append, overlapping fixes dedupe, status-only report keeps history and updates state", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    const a = report(1, 3);
    await api.writeLocation(ctx, a, await hashOf(a));
    const overlap = {
      ...report(2, 3),
      fixes: [...a.fixes.slice(1), { ...a.fixes[2], at: minutesAgo(0) }],
    };
    overlap.fixes[overlap.fixes.length - 1].at = minutesAgo(0);
    await api.writeLocation(ctx, overlap, await hashOf(overlap));
    const afterOverlap =
      await sql`select count(*)::int n from private.device_location_fixes where device_id=${ctx.deviceId}::uuid`;
    assert.equal(afterOverlap[0].n, 4);
    const lost = { ...report(3, 0), status: "permission_lost" as const };
    await api.writeLocation(ctx, lost, await hashOf(lost));
    const read = await api.readLocation(parent, ctx.deviceId);
    assert.equal(read.status, "permission_lost");
    assert.equal(
      read.fixes.length,
      4,
      "earlier fixes remain visible, status says why nothing is new",
    );
    await assert.rejects(
      api.writeLocation(ctx, a, await hashOf(a)),
      postgresError("P0001", "STALE_VERSION"),
    );
  }));
Deno.test("location clear tombstone rejects delayed upload and permits deliberate higher sequence", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    const six = report(6);
    await api.writeLocation(ctx, six, await hashOf(six));
    const clear = {
      version: 1 as const,
      epochId: fixture.epochId,
      sequence: 7,
    };
    const hash = await sha256Hex(JSON.stringify(clear));
    const first = await api.clearLocation(ctx, clear, hash);
    assert.deepEqual(await api.clearLocation(ctx, clear, hash), first);
    await assert.rejects(
      api.writeLocation(ctx, six, await hashOf(six)),
      postgresError("P0001", "STALE_VERSION"),
    );
    const afterClear = await api.readLocation(parent, ctx.deviceId);
    assert.equal(afterClear.state, "none");
    assert.deepEqual(afterClear.fixes, []);
    for (const table of ["device_location_fixes", "device_location_status"]) {
      assert.equal(
        (await sql`select count(*)::int n from private.${
          sql(table)
        } where device_id=${ctx.deviceId}::uuid`)[0].n,
        0,
      );
    }
    assert.equal((await api.readLocationCheckpoint(ctx)).sequence, 7);
    const eight = report(8);
    await api.writeLocation(ctx, eight, await hashOf(eight));
    assert.equal(
      (await api.readLocation(parent, ctx.deviceId)).state,
      "available",
    );
  }));
async function waitBlocked(sql: ReturnType<typeof postgres>, name: string) {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (
      (await sql`select count(*)::int n from pg_stat_activity where wait_event_type='Lock' and query like ${
        "%" + name + "%"
      }`)[0].n > 0
    ) return;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw Error("Expected transaction lock not observed");
}
Deno.test("location concurrent revoke denies pending upload and deletes data atomically", () =>
  fixtureTest(async (sql, api, ctx) => {
    const one = report(1);
    await api.writeLocation(ctx, one, await hashOf(one));
    const locked = Promise.withResolvers<void>(),
      release = Promise.withResolvers<void>();
    let pending: Promise<unknown> | undefined;
    const revoke = sql.begin(async (tx) => {
      await tx`update public.devices_public set status='revoked',revoked_at=now() where id=${ctx.deviceId}::uuid`;
      locked.resolve();
      await release.promise;
    });
    try {
      await locked.promise;
      const two = report(2);
      pending = api.writeLocation(ctx, two, await hashOf(two));
      pending.catch(() => {});
      await waitBlocked(sql, "harbor_write_device_location");
      release.resolve();
      await revoke;
      await assert.rejects(pending, postgresError("42501", "DEVICE_REVOKED"));
      for (const table of ["device_location_fixes", "device_location_status"]) {
        assert.equal(
          (await sql`select count(*)::int n from private.${
            sql(table)
          } where device_id=${ctx.deviceId}::uuid`)[0].n,
          0,
        );
      }
      await assert.rejects(
        api.readLocationCheckpoint(ctx),
        postgresError("42501", "DEVICE_REVOKED"),
      );
    } finally {
      release.resolve();
      await revoke.catch(() => {});
      await pending?.catch(() => {});
    }
  }));
Deno.test("location membership removal denies blocked parent read without cached access", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    const one = report(1);
    await api.writeLocation(ctx, one, await hashOf(one));
    const locked = Promise.withResolvers<void>(),
      release = Promise.withResolvers<void>();
    let pending: Promise<unknown> | undefined;
    const removal = sql.begin(async (tx) => {
      await tx`update public.family_members set status='removed' where family_id=${ctx.familyId}::uuid and user_id=${parent.userId}::uuid`;
      locked.resolve();
      await release.promise;
    });
    try {
      await locked.promise;
      pending = api.readLocation(parent, ctx.deviceId);
      pending.catch(() => {});
      await waitBlocked(sql, "harbor_get_device_location");
      release.resolve();
      await removal;
      await assert.rejects(pending, postgresError("42501"));
    } finally {
      release.resolve();
      await removal.catch(() => {});
      await pending?.catch(() => {});
    }
  }));
Deno.test("location foreign parents are denied", () =>
  fixtureTest(async (sql, api, ctx) => {
    const one = report(1);
    await api.writeLocation(ctx, one, await hashOf(one));
    const outsider = crypto.randomUUID(), session = crypto.randomUUID();
    try {
      await sql`insert into auth.users(id,is_anonymous) values(${outsider}::uuid,false)`;
      await sql`insert into auth.sessions(id,user_id,created_at,updated_at) values(${session}::uuid,${outsider}::uuid,now(),now())`;
      await assert.rejects(
        api.readLocation({
          userId: outsider,
          sessionId: session,
          accessToken: "fixture-only",
          aal: "aal1",
          amr: [],
        }, ctx.deviceId),
        postgresError("42501"),
      );
    } finally {
      await sql`delete from auth.users where id=${outsider}::uuid`;
    }
  }));
Deno.test("location expiration/purge retains sequence and private default-deny grants", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    const one = report(1);
    await api.writeLocation(ctx, one, await hashOf(one));
    await sql`update private.device_location_status set expires_at=clock_timestamp()-interval '1 second' where device_id=${ctx.deviceId}::uuid`;
    await sql`update private.device_location_fixes set expires_at=clock_timestamp()-interval '1 second' where device_id=${ctx.deviceId}::uuid`;
    assert.equal(
      (await api.readLocation(parent, ctx.deviceId)).state,
      "expired",
    );
    await sql`select private.harbor_purge_device_location()`;
    for (const table of ["device_location_fixes", "device_location_status"]) {
      assert.equal(
        (await sql`select count(*)::int n from private.${
          sql(table)
        } where device_id=${ctx.deviceId}::uuid`)[0].n,
        0,
      );
    }
    assert.equal((await api.readLocationCheckpoint(ctx)).sequence, 1);
    const permissions =
      await sql`select has_table_privilege('authenticated','private.device_location_fixes','select') as readable,has_table_privilege('anon','private.device_location_checkpoints','select') as anonymous_read,has_function_privilege('authenticated','private.harbor_purge_device_location()','execute') as purgeable`;
    assert.equal(permissions[0].readable, false);
    assert.equal(permissions[0].anonymous_read, false);
    assert.equal(permissions[0].purgeable, false);
  }));
