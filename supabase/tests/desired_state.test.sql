begin;
select plan(31);

insert into auth.users (
  id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous
) values
  (
    '61000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated',
    'task9-parent@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false
  ),
  (
    '61000000-0000-4000-8000-000000000101', 'authenticated', 'authenticated',
    null, '{}'::jsonb, '{}'::jsonb, now(), now(), true
  );

insert into public.families (id, name)
values ('62000000-0000-4000-8000-000000000001', 'Task 9 Family');

insert into public.family_members (family_id, user_id, role, status)
values (
  '62000000-0000-4000-8000-000000000001',
  '61000000-0000-4000-8000-000000000001',
  'owner',
  'active'
);

insert into public.children (id, family_id, display_name)
values (
  '63000000-0000-4000-8000-000000000001',
  '62000000-0000-4000-8000-000000000001',
  'Task 9 Child'
);

insert into public.devices_public (
  id, family_id, child_id, display_name, supervision_mode, status
) values (
  '64000000-0000-4000-8000-000000000001',
  '62000000-0000-4000-8000-000000000001',
  '63000000-0000-4000-8000-000000000001',
  'Task 9 Device',
  'full',
  'active'
);

insert into private.device_security (device_id, auth_user_id, public_key_spki)
values (
  '64000000-0000-4000-8000-000000000001',
  '61000000-0000-4000-8000-000000000101',
  'task9-p256-spki-fixture'
);

create or replace function pg_temp.call_update_state(
  p_expected_version bigint,
  p_state jsonb
)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select jsonb_build_object(''desired_state_version'', r.desired_state_version) from private.harbor_update_device_desired_state($1, $2, $3) as r'
    into v_result
    using '64000000-0000-4000-8000-000000000001'::uuid, p_state, p_expected_version;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.call_enqueue_command(
  p_idempotency_key text,
  p_kind text default 'sync',
  p_expires_at timestamptz default null
)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(r) from private.harbor_enqueue_device_command($1, $2, $3, $4, $5) as r'
    into v_result
    using
      '64000000-0000-4000-8000-000000000001'::uuid,
      p_kind,
      p_idempotency_key,
      '{"reason":"policy-change"}'::jsonb,
      p_expires_at;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.call_sync(
  p_acknowledged_version bigint,
  p_applied_command_ids uuid[]
)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(r) from private.harbor_sync_device($1, $2, $3) as r'
    into v_result
    using
      '64000000-0000-4000-8000-000000000001'::uuid,
      p_acknowledged_version,
      p_applied_command_ids;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.call_register_fcm(p_token text)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(private.harbor_register_device_fcm($1, $2))'
    into v_result
    using '64000000-0000-4000-8000-000000000001'::uuid, p_token;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.call_revoke()
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(private.harbor_revoke_device($1, $2, $3))'
    into v_result
    using
      '64000000-0000-4000-8000-000000000001'::uuid,
      '62000000-0000-4000-8000-000000000001'::uuid,
      '61000000-0000-4000-8000-000000000001'::uuid;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.private_count(p_sql text)
returns bigint
language plpgsql
as $$
declare
  v_count bigint;
begin
  execute p_sql into v_count;
  return v_count;
exception when others then
  return -1;
end;
$$;

select has_table('private', 'device_desired_state', 'desired state is private');
select has_table('private', 'device_commands', 'device commands are private');
select has_table('private', 'device_fcm_registrations', 'FCM registrations are private');
select has_column('public', 'devices_public', 'revoked_at', 'public device state records revocation time');

select has_function('private', 'harbor_update_device_desired_state', array['uuid','jsonb','bigint'], 'desired-state update helper exists');
select has_function('private', 'harbor_sync_device', array['uuid','bigint','uuid[]'], 'device sync helper exists');
select has_function('private', 'harbor_register_device_fcm', array['uuid','text'], 'FCM rotation helper exists');
select has_function('private', 'harbor_enqueue_device_command', array['uuid','text','text','jsonb','timestamp with time zone'], 'idempotent command helper exists');
select has_function('private', 'harbor_revoke_device', array['uuid','uuid','uuid'], 'atomic revocation helper exists');

select ok(
  case when to_regclass('private.device_fcm_registrations') is null then false
       else not has_table_privilege('authenticated', 'private.device_fcm_registrations', 'SELECT') end,
  'authenticated clients cannot read private FCM tokens'
);
select ok(
  case when to_regclass('private.device_desired_state') is null then false
       else not has_table_privilege('authenticated', 'private.device_desired_state', 'SELECT') end,
  'authenticated clients cannot read private desired state directly'
);
select ok(
  case when to_regclass('private.device_commands') is null then false
       else not has_table_privilege('authenticated', 'private.device_commands', 'SELECT') end,
  'authenticated clients cannot read private device commands directly'
);

create temporary table harbor_task9_results (
  label text primary key,
  result jsonb
);

insert into harbor_task9_results(label, result)
values ('state-v1', pg_temp.call_update_state(0, '{"paused":true}'::jsonb));

select is(
  (select (result ->> 'desired_state_version')::bigint from harbor_task9_results where label = 'state-v1'),
  1::bigint,
  'first accepted desired-state update advances version from zero to one'
);

insert into harbor_task9_results(label, result)
values ('state-v2', pg_temp.call_update_state(1, '{"paused":false}'::jsonb));

select is(
  (select (result ->> 'desired_state_version')::bigint from harbor_task9_results where label = 'state-v2'),
  2::bigint,
  'second accepted desired-state update advances version exactly once'
);

insert into harbor_task9_results(label, result)
values ('stale-state', pg_temp.call_update_state(0, '{"paused":true,"stale":true}'::jsonb));

select ok(
  (select result is null from harbor_task9_results where label = 'stale-state')
  and pg_temp.private_count($$select count(*) from private.device_desired_state where device_id = '64000000-0000-4000-8000-000000000001'::uuid and desired_state_version = 2$$) = 1,
  'stale expected version is rejected without mutating current desired state'
);

select is(
  pg_temp.private_count($$select count(*) from private.device_desired_state where device_id = '64000000-0000-4000-8000-000000000001'::uuid and wake_requested_at is not null$$),
  1::bigint,
  'accepted desired-state change records wake intent'
);

insert into harbor_task9_results(label, result)
values ('command-first', pg_temp.call_enqueue_command('policy-v2'));
insert into harbor_task9_results(label, result)
values ('command-duplicate', pg_temp.call_enqueue_command('policy-v2'));

select ok(
  (select result ->> 'id' from harbor_task9_results where label = 'command-first') is not null,
  'first command enqueue returns a stable command id'
);
select is(
  (select result ->> 'id' from harbor_task9_results where label = 'command-duplicate'),
  (select result ->> 'id' from harbor_task9_results where label = 'command-first'),
  'duplicate idempotency key returns the original command id'
);
select is(
  pg_temp.private_count($$select count(*) from private.device_commands where device_id = '64000000-0000-4000-8000-000000000001'::uuid and idempotency_key = 'policy-v2'$$),
  1::bigint,
  'duplicate command enqueue creates only one persisted command'
);

insert into harbor_task9_results(label, result)
values ('sync-before-ack', pg_temp.call_sync(1, array[]::uuid[]));

select ok(
  (select (result ->> 'desired_state_version')::bigint = 2 from harbor_task9_results where label = 'sync-before-ack')
  and (select result -> 'desired_state' = '{"paused":false}'::jsonb from harbor_task9_results where label = 'sync-before-ack')
  and (select jsonb_array_length(result -> 'commands') = 1 from harbor_task9_results where label = 'sync-before-ack'),
  'signed-sync persistence returns current desired state and the live unacknowledged command'
);

insert into harbor_task9_results(label, result)
select
  'sync-duplicate-ack',
  pg_temp.call_sync(
    2,
    array[(select (result ->> 'id')::uuid from harbor_task9_results where label = 'command-first'),
          (select (result ->> 'id')::uuid from harbor_task9_results where label = 'command-first')]
  );

select ok(
  (select jsonb_array_length(result -> 'commands') = 0 from harbor_task9_results where label = 'sync-duplicate-ack')
  and pg_temp.private_count($$select count(*) from private.device_commands where device_id = '64000000-0000-4000-8000-000000000001'::uuid and acknowledged_at is not null$$) = 1,
  'duplicate command acknowledgements are idempotent and remove the applied command from future sync results'
);

insert into harbor_task9_results(label, result)
select
  'sync-repeat-ack',
  pg_temp.call_sync(
    2,
    array[(select (result ->> 'id')::uuid from harbor_task9_results where label = 'command-first')]
  );

select ok(
  (select result is not null from harbor_task9_results where label = 'sync-repeat-ack')
  and pg_temp.private_count($$select count(*) from private.device_commands where device_id = '64000000-0000-4000-8000-000000000001'::uuid and acknowledged_at is not null$$) = 1,
  'repeating an already-applied command acknowledgement has no duplicate domain effect'
);

select ok(pg_temp.call_register_fcm('fcm-token-one') is not null, 'active device can register an FCM token');
select ok(pg_temp.call_register_fcm('fcm-token-two') is not null, 'active device can rotate its FCM token');
select ok(
  pg_temp.private_count($$select count(*) from private.device_fcm_registrations where device_id = '64000000-0000-4000-8000-000000000001'::uuid$$) = 1
  and pg_temp.private_count($$select count(*) from private.device_fcm_registrations where device_id = '64000000-0000-4000-8000-000000000001'::uuid and token = 'fcm-token-two'$$) = 1,
  'FCM rotation retains only the current private token'
);

select ok(pg_temp.call_revoke() is not null, 'authorized backend revocation succeeds once');
select ok(
  (select status = 'revoked' from public.devices_public where id = '64000000-0000-4000-8000-000000000001')
  and (select revoked_at is not null from public.devices_public where id = '64000000-0000-4000-8000-000000000001'),
  'revocation marks the public device revoked with a current revocation timestamp'
);
select is(
  pg_temp.private_count($$select count(*) from private.device_fcm_registrations where device_id = '64000000-0000-4000-8000-000000000001'::uuid$$),
  0::bigint,
  'revocation removes private FCM delivery registration immediately'
);
select is(
  pg_temp.private_count($$select count(*) from private.audit_events where event_kind = 'device.revoked' and resource_id = '64000000-0000-4000-8000-000000000001'::uuid$$),
  1::bigint,
  'revocation writes one audit event'
);
select ok(pg_temp.call_sync(2, array[]::uuid[]) is null, 'revoked device loses desired-state sync access immediately');
select ok(pg_temp.call_register_fcm('fcm-token-after-revoke') is null, 'revoked device cannot register a new FCM token');

select * from finish();
rollback;
