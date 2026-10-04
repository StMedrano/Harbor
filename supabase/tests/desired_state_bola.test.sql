begin;
select plan(3);

insert into auth.users (
  id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous
) values (
  '71000000-0000-4000-8000-000000000001',
  'authenticated',
  'authenticated',
  'task9-bola-parent@harbor.test',
  '{}'::jsonb,
  '{}'::jsonb,
  now(),
  now(),
  false
);

insert into public.families (id, name) values
  ('72000000-0000-4000-8000-000000000001', 'Task 9 Family A'),
  ('72000000-0000-4000-8000-000000000002', 'Task 9 Family B');

insert into public.family_members (family_id, user_id, role, status)
values (
  '72000000-0000-4000-8000-000000000001',
  '71000000-0000-4000-8000-000000000001',
  'owner',
  'active'
);

insert into public.children (id, family_id, display_name)
values (
  '73000000-0000-4000-8000-000000000002',
  '72000000-0000-4000-8000-000000000002',
  'Family B Child'
);

insert into public.devices_public (
  id, family_id, child_id, display_name, supervision_mode, status
) values (
  '74000000-0000-4000-8000-000000000002',
  '72000000-0000-4000-8000-000000000002',
  '73000000-0000-4000-8000-000000000002',
  'Family B Device',
  'full',
  'active'
);

insert into private.device_security (device_id, auth_user_id, public_key_spki)
values (
  '74000000-0000-4000-8000-000000000002',
  '71000000-0000-4000-8000-000000000001',
  'task9-bola-spki-fixture'
);

select has_function(
  'private',
  'harbor_update_device_desired_state',
  array['uuid','uuid','uuid','jsonb','bigint'],
  'desired-state update helper binds device, family, and authorized parent subject'
);

select ok(
  to_regprocedure('private.harbor_update_device_desired_state(uuid,jsonb,bigint)') is null,
  'no service-only legacy helper can update desired state without a family/actor binding'
);

create or replace function pg_temp.cross_family_update_was_denied()
returns boolean
language plpgsql
as $$
begin
  execute $sql$
    select *
    from private.harbor_update_device_desired_state($1, $2, $3, $4, $5)
  $sql$
  using
    '74000000-0000-4000-8000-000000000002'::uuid,
    '72000000-0000-4000-8000-000000000001'::uuid,
    '71000000-0000-4000-8000-000000000001'::uuid,
    '{"paused":true}'::jsonb,
    0::bigint;
  return false;
exception when others then
  return true;
end;
$$;

select ok(
  pg_temp.cross_family_update_was_denied()
  and not exists (
    select 1
    from private.device_desired_state s
    where s.device_id = '74000000-0000-4000-8000-000000000002'::uuid
  ),
  'Family A parent cannot mutate a known Family B device UUID'
);

select * from finish();
rollback;
