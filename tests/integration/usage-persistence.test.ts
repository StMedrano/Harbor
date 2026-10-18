import { strict as assert } from "node:assert";
import postgres from "npm:postgres@3.4.7";
import fixture from "../../apps/parent-android/app/src/test/resources/usage-report-v1.json" with {
  type: "json",
};
import { createUsagePersistence } from "../../supabase/functions/_shared/usage-persistence.ts";
import type { UsageReportV1 } from "../../packages/contracts/src/v1/usage.ts";
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
    api: ReturnType<typeof createUsagePersistence>,
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
    await sql`insert into public.families(id,name) values(${family}::uuid,'Usage fixture')`;
    await sql`insert into public.family_members(family_id,user_id,role,status) values(${family}::uuid,${user}::uuid,'owner','active')`;
    await sql`insert into public.children(id,family_id,display_name) values(${child}::uuid,${family}::uuid,'Usage child')`;
    await sql`insert into public.devices_public(id,family_id,child_id,display_name,supervision_mode) values(${device}::uuid,${family}::uuid,${child}::uuid,'Usage device','standard')`;
    await sql`insert into private.device_security(device_id,auth_user_id,public_key_spki) values(${device}::uuid,${anon}::uuid,'usage-test-public-key')`;
    await run(sql, createUsagePersistence(sql), {
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
function report(sequence: number): UsageReportV1 {
  return { ...structuredClone(fixture), sequence } as UsageReportV1;
}
Deno.test("usage persistence checkpoint/report/no-op/conflict and authorized parent read", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    assert.deepEqual(await api.readUsageCheckpoint(ctx), {
      sequence: 0,
      epochId: null,
    });
    const body = report(1), hash = await sha256Hex(JSON.stringify(body));
    const first = await api.writeUsage(ctx, body, hash);
    assert.equal(first.confirmed, true);
    assert.equal(first.sequence, 1);
    assert.deepEqual(await api.writeUsage(ctx, body, hash), first);
    assert.equal(
      (await sql`select count(*)::int n from private.device_usage_snapshots where device_id=${ctx.deviceId}::uuid`)[
        0
      ].n,
      1,
    );
    assert.equal(
      (await api.readUsage(parent, ctx.deviceId)).report?.sequence,
      1,
    );
    await assert.rejects(
      api.writeUsage(ctx, body, "f".repeat(64)),
      postgresError("P0001", "IDEMPOTENCY_CONFLICT"),
    );
    await assert.rejects(
      api.readUsageCheckpoint({ ...ctx, authUserId: parent.userId }),
      postgresError("42501"),
    );
    await sql`delete from auth.sessions where id=${parent.sessionId}::uuid`;
    await assert.rejects(
      api.readUsage(parent, ctx.deviceId),
      postgresError("42501"),
    );
  }));
Deno.test("usage clear tombstone rejects delayed upload and permits deliberate higher sequence", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    await api.writeUsage(
      ctx,
      report(6),
      await sha256Hex(JSON.stringify(report(6))),
    );
    const clear = {
      version: 1 as const,
      epochId: fixture.epochId,
      sequence: 7,
    };
    const hash = await sha256Hex(JSON.stringify(clear));
    const first = await api.clearUsage(ctx, clear, hash);
    assert.deepEqual(await api.clearUsage(ctx, clear, hash), first);
    await assert.rejects(
      api.writeUsage(
        ctx,
        report(6),
        await sha256Hex(JSON.stringify(report(6))),
      ),
      postgresError("P0001", "STALE_VERSION"),
    );
    assert.equal((await api.readUsage(parent, ctx.deviceId)).report, null);
    assert.equal(
      (await sql`select count(*)::int n from private.device_usage_snapshots where device_id=${ctx.deviceId}::uuid`)[
        0
      ].n,
      0,
    );
    assert.equal((await api.readUsageCheckpoint(ctx)).sequence, 7);
    await api.writeUsage(
      ctx,
      report(8),
      await sha256Hex(JSON.stringify(report(8))),
    );
    assert.equal(
      (await api.readUsage(parent, ctx.deviceId)).report?.sequence,
      8,
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
Deno.test("usage concurrent revoke denies pending upload and deletes payload atomically", () =>
  fixtureTest(async (sql, api, ctx) => {
    await api.writeUsage(
      ctx,
      report(1),
      await sha256Hex(JSON.stringify(report(1))),
    );
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
      pending = api.writeUsage(
        ctx,
        report(2),
        await sha256Hex(JSON.stringify(report(2))),
      );
      pending.catch(() => {});
      await waitBlocked(sql, "harbor_write_device_usage");
      release.resolve();
      await revoke;
      await assert.rejects(pending, postgresError("42501", "DEVICE_REVOKED"));
      assert.equal(
        (await sql`select count(*)::int n from private.device_usage_snapshots where device_id=${ctx.deviceId}::uuid`)[
          0
        ].n,
        0,
      );
      await assert.rejects(
        api.readUsageCheckpoint(ctx),
        postgresError("42501", "DEVICE_REVOKED"),
      );
    } finally {
      release.resolve();
      await revoke.catch(() => {});
      await pending?.catch(() => {});
    }
  }));
Deno.test("usage membership removal denies blocked parent read without cached access", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    await api.writeUsage(
      ctx,
      report(1),
      await sha256Hex(JSON.stringify(report(1))),
    );
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
      pending = api.readUsage(parent, ctx.deviceId);
      pending.catch(() => {});
      await waitBlocked(sql, "harbor_get_device_usage");
      release.resolve();
      await removal;
      await assert.rejects(pending, postgresError("42501"));
    } finally {
      release.resolve();
      await removal.catch(() => {});
      await pending?.catch(() => {});
    }
  }));
Deno.test("usage expiration/purge retains sequence and private default-deny grants", () =>
  fixtureTest(async (sql, api, ctx, parent) => {
    await api.writeUsage(
      ctx,
      report(1),
      await sha256Hex(JSON.stringify(report(1))),
    );
    await sql`update private.device_usage_snapshots set expires_at=clock_timestamp()-interval '1 second' where device_id=${ctx.deviceId}::uuid`;
    assert.equal((await api.readUsage(parent, ctx.deviceId)).state, "expired");
    await sql`select private.harbor_purge_device_usage()`;
    assert.equal(
      (await sql`select count(*)::int n from private.device_usage_snapshots where device_id=${ctx.deviceId}::uuid`)[
        0
      ].n,
      0,
    );
    assert.equal((await api.readUsageCheckpoint(ctx)).sequence, 1);
    const permissions =
      await sql`select has_table_privilege('authenticated','private.device_usage_snapshots','select') as readable,has_table_privilege('anon','private.device_usage_checkpoints','select') as anonymous_read,has_function_privilege('authenticated','private.harbor_purge_device_usage()','execute') as purgeable`;
    assert.equal(permissions[0].readable, false);
    assert.equal(permissions[0].anonymous_read, false);
    assert.equal(permissions[0].purgeable, false);
  }));
