import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import postgres from "npm:postgres@3.4.7";

Deno.test("membership removal holds child creation until commit, then denies without side effects", async () => {
  const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, {
    max: 4,
    prepare: false,
  });
  const actor = crypto.randomUUID(), family = crypto.randomUUID();
  let unlock = () => {};
  let removal: Promise<unknown> | undefined;
  let creation: Promise<unknown> | undefined;
  const lockHeld = Promise.withResolvers<void>();
  const commit = Promise.withResolvers<void>();
  unlock = () => commit.resolve();
  const worker = await sql.reserve();
  try {
    await sql`insert into auth.users(id,is_anonymous) values(${actor}::uuid,false)`;
    await sql`insert into public.families(id,name) values(${family}::uuid,'Creation race test')`;
    await sql`insert into public.family_members(family_id,user_id,role,status) values(${family}::uuid,${actor}::uuid,'owner','active')`;
    const [{ pid }] = await worker<
      { pid: number }[]
    >`select pg_backend_pid() as pid`;
    removal = sql.begin(async (tx) => {
      await tx`update public.family_members set status='removed' where family_id=${family}::uuid and user_id=${actor}::uuid`;
      lockHeld.resolve();
      await commit.promise;
    });
    await lockHeld.promise;
    // Capture rejection immediately so an unexpected missing helper cannot become unhandled.
    creation =
      worker`select * from private.harbor_create_child(${actor}::uuid,${family}::uuid,'Alex','race',${
        "a".repeat(64)
      })`.then((rows) => ({ rows }), (error) => ({ error }));
    let waiting = false;
    for (let i = 0; i < 100; i++) {
      const rows = await sql<
        { waiting: boolean }[]
      >`select wait_event_type='Lock' as waiting from pg_stat_activity where pid=${pid}`;
      if (rows[0]?.waiting) {
        waiting = true;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assertEquals(
      waiting,
      true,
      "creation must wait on the membership row lock",
    );
    unlock();
    await removal;
    const result = await creation as { error?: { code?: string } };
    assertEquals(result.error?.code, "42501");
    assertEquals(
      (await sql`select count(*)::int n from public.children where family_id=${family}::uuid`)[
        0
      ].n,
      0,
    );
    assertEquals(
      (await sql`select count(*)::int n from private.child_creation_requests where family_id=${family}::uuid`)[
        0
      ].n,
      0,
    );
    assertEquals(
      (await sql`select count(*)::int n from private.audit_events where family_id=${family}::uuid`)[
        0
      ].n,
      0,
    );
  } finally {
    unlock();
    await removal?.catch(() => {});
    await creation?.catch(() => {});
    worker.release();
    await sql`delete from public.families where id=${family}::uuid`;
    await sql`delete from auth.users where id=${actor}::uuid`;
    await sql.end();
  }
});

Deno.test("concurrent identical child requests persist one child/request/audit", async () => {
  const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, {
    max: 3,
    prepare: false,
  });
  const actor = crypto.randomUUID(), family = crypto.randomUUID();
  try {
    await sql`insert into auth.users(id,is_anonymous) values(${actor}::uuid,false)`;
    await sql`insert into public.families(id,name) values(${family}::uuid,'Concurrent creation test')`;
    await sql`insert into public.family_members(family_id,user_id,role,status) values(${family}::uuid,${actor}::uuid,'parent','active')`;
    const create = () =>
      sql`select * from private.harbor_create_child(${actor}::uuid,${family}::uuid,'Alex','same',${
        "a".repeat(64)
      })`;
    const [first, second] = await Promise.all([create(), create()]);
    assertEquals(first, second);
    assertEquals(
      (await sql`select count(*)::int n from public.children where family_id=${family}::uuid`)[
        0
      ].n,
      1,
    );
    assertEquals(
      (await sql`select count(*)::int n from private.child_creation_requests where family_id=${family}::uuid`)[
        0
      ].n,
      1,
    );
    assertEquals(
      (await sql`select count(*)::int n from private.audit_events where family_id=${family}::uuid and event_kind='child.created'`)[
        0
      ].n,
      1,
    );
  } finally {
    await sql`delete from private.audit_events where family_id=${family}::uuid`;
    await sql`delete from public.families where id=${family}::uuid`;
    await sql`delete from auth.users where id=${actor}::uuid`;
    await sql.end();
  }
});
