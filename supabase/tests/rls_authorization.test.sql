begin;

select plan(17);

-- Fixed fixture identities keep known-ID isolation assertions deterministic.
insert into auth.users (
  id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous
) values
  ('10000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated', 'parent-a@harbor.test', '{}'::jsonb, '{"display_name":"Parent A"}'::jsonb, now(), now(), false),
  ('20000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated', 'parent-b@harbor.test', '{}'::jsonb, '{"display_name":"Parent B"}'::jsonb, now(), now(), false),
  ('30000000-0000-4000-8000-000000000003', 'authenticated', 'authenticated', null, '{}'::jsonb, '{}'::jsonb, now(), now(), true);

insert into public.families (id, name, timezone) values
  ('a0000000-0000-4000-8000-000000000001', 'Family A', 'America/Chicago'),
  ('b0000000-0000-4000-8000-000000000002', 'Family B', 'America/New_York');

insert into public.family_members (family_id, user_id, role, status) values
  ('a0000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'owner', 'active'),
  ('b0000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002', 'parent', 'active');

insert into public.children (id, family_id, display_name) values
  ('c0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 'Child A'),
  ('d0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002', 'Child B');

insert into public.devices_public (id, family_id, child_id, display_name) values
  ('e0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000001', 'Child A device'),
  ('f0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000002', 'Child B device');

-- Execute a SELECT as a Supabase authenticated principal while converting
-- permission errors into a deterministic negative sentinel. That lets RED
-- fail as an assertion instead of aborting the pgTAP file.
create or replace function pg_temp.harbor_count_as(p_user_id uuid, p_sql text)
returns bigint
language plpgsql
as $$
declare
  v_count bigint;
begin
  perform set_config('request.jwt.claim.sub', p_user_id::text, true);
  perform set_config('request.jwt.claim.role', 'authenticated', true);
  perform set_config(
    'request.jwt.claims',
    jsonb_build_object('sub', p_user_id::text, 'role', 'authenticated')::text,
    true
  );
  perform set_config('role', 'authenticated', true);

  begin
    execute 'select count(*) from (' || p_sql || ') harbor_rows' into v_count;
  exception when others then
    v_count := -999;
  end;

  perform set_config('role', 'postgres', true);
  return v_count;
exception when others then
  begin
    perform set_config('role', 'postgres', true);
  exception when others then
    null;
  end;
  return -998;
end;
$$;

create or replace function pg_temp.harbor_can_update_family_as(p_user_id uuid, p_family_id uuid)
returns boolean
language plpgsql
as $$
declare
  v_rows bigint := 0;
begin
  perform set_config('request.jwt.claim.sub', p_user_id::text, true);
  perform set_config('request.jwt.claim.role', 'authenticated', true);
  perform set_config(
    'request.jwt.claims',
    jsonb_build_object('sub', p_user_id::text, 'role', 'authenticated')::text,
    true
  );
  perform set_config('role', 'authenticated', true);

  begin
    update public.families set name = name where id = p_family_id;
    get diagnostics v_rows = row_count;
  exception when others then
    v_rows := 0;
  end;

  perform set_config('role', 'postgres', true);
  return v_rows > 0;
exception when others then
  begin
    perform set_config('role', 'postgres', true);
  exception when others then
    null;
  end;
  return false;
end;
$$;

select is(
  (select count(*)::integer from pg_class where oid in (
    'public.profiles'::regclass,
    'public.families'::regclass,
    'public.family_members'::regclass,
    'public.children'::regclass,
    'public.devices_public'::regclass
  ) and relrowsecurity),
  5,
  'RLS is enabled on every exposed Harbor family table'
);

select ok(
  (select bool_and(has_table_privilege('authenticated', oid, 'SELECT')) from pg_class where oid in (
    'public.profiles'::regclass, 'public.families'::regclass, 'public.family_members'::regclass,
    'public.children'::regclass, 'public.devices_public'::regclass
  )),
  'authenticated receives SELECT only through RLS-protected public tables'
);

select ok(
  not (select bool_or(has_table_privilege('authenticated', oid, 'INSERT')) from pg_class where oid in (
    'public.profiles'::regclass, 'public.families'::regclass, 'public.family_members'::regclass,
    'public.children'::regclass, 'public.devices_public'::regclass
  )),
  'authenticated has no direct INSERT privileges'
);

select ok(
  not (select bool_or(has_table_privilege('authenticated', oid, 'UPDATE')) from pg_class where oid in (
    'public.profiles'::regclass, 'public.families'::regclass, 'public.family_members'::regclass,
    'public.children'::regclass, 'public.devices_public'::regclass
  )),
  'authenticated has no direct UPDATE privileges'
);

select ok(
  not (select bool_or(has_table_privilege('authenticated', oid, 'DELETE')) from pg_class where oid in (
    'public.profiles'::regclass, 'public.families'::regclass, 'public.family_members'::regclass,
    'public.children'::regclass, 'public.devices_public'::regclass
  )),
  'authenticated has no direct DELETE privileges'
);

select ok(
  not (select bool_or(has_table_privilege('anon', oid, 'SELECT')) from pg_class where oid in (
    'public.profiles'::regclass, 'public.families'::regclass, 'public.family_members'::regclass,
    'public.children'::regclass, 'public.devices_public'::regclass
  )),
  'anon has no direct Harbor family reads'
);

select is(
  pg_temp.harbor_count_as(
    '10000000-0000-4000-8000-000000000001',
    $$select id from public.families where id = 'a0000000-0000-4000-8000-000000000001'$$
  ),
  1::bigint,
  'Parent A can read Family A'
);

select is(
  pg_temp.harbor_count_as(
    '10000000-0000-4000-8000-000000000001',
    $$select id from public.children where id = 'c0000000-0000-4000-8000-000000000001'$$
  ),
  1::bigint,
  'Parent A can read Child A'
);

select is(
  pg_temp.harbor_count_as(
    '10000000-0000-4000-8000-000000000001',
    $$select id from public.families where id = 'b0000000-0000-4000-8000-000000000002'$$
  ),
  0::bigint,
  'Parent A cannot read Family B by known UUID'
);

select is(
  pg_temp.harbor_count_as(
    '10000000-0000-4000-8000-000000000001',
    $$select id from public.children where id = 'd0000000-0000-4000-8000-000000000002'$$
  ),
  0::bigint,
  'Parent A cannot read Child B by known UUID'
);

select is(
  pg_temp.harbor_count_as(
    '30000000-0000-4000-8000-000000000003',
    $$select id from public.families where id = 'a0000000-0000-4000-8000-000000000001'$$
  ),
  0::bigint,
  'device Auth identity cannot read family data merely because it is authenticated'
);

select is(
  pg_temp.harbor_count_as(
    '30000000-0000-4000-8000-000000000003',
    $$select id from public.children where id = 'c0000000-0000-4000-8000-000000000001'$$
  ),
  0::bigint,
  'device Auth identity cannot read child data'
);

select is(
  pg_temp.harbor_count_as(
    '20000000-0000-4000-8000-000000000002',
    $$select id from public.families where id = 'b0000000-0000-4000-8000-000000000002'$$
  ),
  1::bigint,
  'Parent B parent-role membership can read Family B'
);

select is(
  pg_temp.harbor_count_as(
    '20000000-0000-4000-8000-000000000002',
    $$select id from public.families where id = 'a0000000-0000-4000-8000-000000000001'$$
  ),
  0::bigint,
  'Parent B cannot read Family A'
);

select ok(
  not pg_temp.harbor_can_update_family_as(
    '10000000-0000-4000-8000-000000000001',
    'a0000000-0000-4000-8000-000000000001'
  ),
  'parent direct family mutation remains denied'
);

select is(
  pg_temp.harbor_count_as(
    '10000000-0000-4000-8000-000000000001',
    $$select user_id from private.staff_authorizations$$
  ),
  (-999)::bigint,
  'normal parent cannot read private staff authorization state'
);

update public.family_members
set status = 'removed'
where family_id = 'a0000000-0000-4000-8000-000000000001'
  and user_id = '10000000-0000-4000-8000-000000000001';

select is(
  pg_temp.harbor_count_as(
    '10000000-0000-4000-8000-000000000001',
    $$select id from public.families where id = 'a0000000-0000-4000-8000-000000000001'$$
  ),
  0::bigint,
  'removed membership immediately loses family visibility'
);

select * from finish();
rollback;
