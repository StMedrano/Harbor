begin;

select plan(24);

insert into auth.users (
  id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous
) values
  (
    '51000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated',
    'device-parent-a@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false
  ),
  (
    '51000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated',
    'device-parent-b@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false
  ),
  (
    '51000000-0000-4000-8000-000000000101', 'authenticated', 'authenticated',
    null, '{}'::jsonb, '{}'::jsonb, now(), now(), true
  ),
  (
    '51000000-0000-4000-8000-000000000102', 'authenticated', 'authenticated',
    null, '{}'::jsonb, '{}'::jsonb, now(), now(), true
  );

insert into public.families (id, name) values
  ('52000000-0000-4000-8000-000000000001', 'Device Family A'),
  ('52000000-0000-4000-8000-000000000002', 'Device Family B');

insert into public.family_members (family_id, user_id, role, status) values
  ('52000000-0000-4000-8000-000000000001', '51000000-0000-4000-8000-000000000001', 'owner', 'active'),
  ('52000000-0000-4000-8000-000000000002', '51000000-0000-4000-8000-000000000002', 'owner', 'active');

insert into public.children (id, family_id, display_name) values
  ('53000000-0000-4000-8000-000000000001', '52000000-0000-4000-8000-000000000001', 'Child A'),
  ('53000000-0000-4000-8000-000000000002', '52000000-0000-4000-8000-000000000002', 'Child B');

create or replace function pg_temp.issue_pairing(
  p_parent_user_id uuid,
  p_child_id uuid,
  p_code_digest text,
  p_expires_at timestamptz
)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(result) from private.issue_device_pairing($1, $2, $3, $4) as result'
    into v_result
    using p_parent_user_id, p_child_id, p_code_digest, p_expires_at;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.record_claim_failure(p_code_digest text)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(result) from private.record_device_claim_failure($1) as result'
    into v_result
    using p_code_digest;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.claim_device(
  p_auth_user_id uuid,
  p_code_digest text,
  p_public_key_spki text,
  p_display_name text,
  p_model text,
  p_android_version text,
  p_supervision_mode text
)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(result) from private.claim_device_atomic($1, $2, $3, $4, $5, $6, $7) as result'
    into v_result
    using p_auth_user_id, p_code_digest, p_public_key_spki, p_display_name,
      p_model, p_android_version, p_supervision_mode;
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

select ok(to_regclass('private.device_security') is not null, 'private device security table exists');
select ok(to_regclass('private.device_enrollment_tokens') is not null, 'private enrollment token table exists');

select ok(
  case when to_regclass('private.device_security') is null then false
       else not has_table_privilege('anon', 'private.device_security', 'SELECT') end,
  'anon cannot read device security state'
);
select ok(
  case when to_regclass('private.device_security') is null then false
       else not has_table_privilege('authenticated', 'private.device_security', 'SELECT') end,
  'authenticated cannot read device security state'
);
select ok(
  case when to_regclass('private.device_enrollment_tokens') is null then false
       else not has_table_privilege('anon', 'private.device_enrollment_tokens', 'SELECT') end,
  'anon cannot read enrollment tokens'
);
select ok(
  case when to_regclass('private.device_enrollment_tokens') is null then false
       else not has_table_privilege('authenticated', 'private.device_enrollment_tokens', 'SELECT') end,
  'authenticated cannot read enrollment tokens'
);

select ok(
  to_regprocedure('private.issue_device_pairing(uuid,uuid,text,timestamp with time zone)') is not null,
  'backend pairing issue helper exists'
);
select ok(
  to_regprocedure('private.record_device_claim_failure(text)') is not null,
  'backend claim failure helper exists'
);
select ok(
  to_regprocedure('private.claim_device_atomic(uuid,text,text,text,text,text,text)') is not null,
  'backend atomic claim helper exists'
);

select ok(
  case
    when to_regprocedure('private.issue_device_pairing(uuid,uuid,text,timestamp with time zone)') is null then false
    else not has_function_privilege(
      'authenticated',
      to_regprocedure('private.issue_device_pairing(uuid,uuid,text,timestamp with time zone)'),
      'EXECUTE'
    )
  end,
  'authenticated cannot execute pairing issue helper directly'
);
select ok(
  case
    when to_regprocedure('private.claim_device_atomic(uuid,text,text,text,text,text,text)') is null then false
    else not has_function_privilege(
      'authenticated',
      to_regprocedure('private.claim_device_atomic(uuid,text,text,text,text,text,text)'),
      'EXECUTE'
    )
  end,
  'authenticated cannot execute device claim helper directly'
);

select ok(
  not exists (
    select 1
    from information_schema.columns
    where table_schema = 'private'
      and table_name = 'device_enrollment_tokens'
      and column_name in ('code', 'pairing_code', 'raw_code')
  ),
  'enrollment storage has no raw pairing-code column'
);

create temporary table harbor_pairing_results (
  label text primary key,
  result jsonb
);

insert into harbor_pairing_results(label, result)
values (
  'first',
  pg_temp.issue_pairing(
    '51000000-0000-4000-8000-000000000001',
    '53000000-0000-4000-8000-000000000001',
    'digest-first',
    now() + interval '10 minutes'
  )
);

select ok((select result is not null from harbor_pairing_results where label = 'first'), 'authorized parent can issue pairing token');
select is(
  pg_temp.private_count($$select count(*) from private.device_enrollment_tokens where code_digest = 'digest-first'$$),
  1::bigint,
  'pairing stores only the supplied keyed digest'
);

insert into harbor_pairing_results(label, result)
values (
  'second',
  pg_temp.issue_pairing(
    '51000000-0000-4000-8000-000000000001',
    '53000000-0000-4000-8000-000000000001',
    'digest-second',
    now() + interval '10 minutes'
  )
);

select ok(
  pg_temp.private_count($$select count(*) from private.device_enrollment_tokens where code_digest = 'digest-first' and invalidated_at is not null$$) = 1
  and pg_temp.private_count($$select count(*) from private.device_enrollment_tokens where child_id = '53000000-0000-4000-8000-000000000001'::uuid and consumed_at is null and invalidated_at is null$$) = 1,
  'issuing a new code invalidates the previous unconsumed code for that child'
);

select ok(
  pg_temp.issue_pairing(
    '51000000-0000-4000-8000-000000000001',
    '53000000-0000-4000-8000-000000000002',
    'digest-cross-family',
    now() + interval '10 minutes'
  ) is null,
  'parent cannot issue a pairing token for another family child'
);
select is(
  pg_temp.private_count($$select count(*) from private.device_enrollment_tokens where code_digest = 'digest-cross-family'$$),
  0::bigint,
  'cross-family pairing denial creates no token'
);

select ok(pg_temp.record_claim_failure('digest-second') is not null, 'first matched-token failure is recorded');
select ok(pg_temp.record_claim_failure('digest-second') is not null, 'second matched-token failure is recorded');
select ok(pg_temp.record_claim_failure('digest-second') is not null, 'third matched-token failure is recorded');
select ok(pg_temp.record_claim_failure('digest-second') is not null, 'fourth matched-token failure is recorded');
select ok(
  (pg_temp.record_claim_failure('digest-second') ->> 'invalidated')::boolean is true,
  'fifth matched-token failure invalidates the token'
);
select is(
  pg_temp.private_count($$select count(*) from private.device_enrollment_tokens where code_digest = 'digest-second' and failed_attempts = 5 and invalidated_at is not null$$),
  1::bigint,
  'invalidated token persists the five-failure state'
);

insert into harbor_pairing_results(label, result)
values (
  'claimable',
  pg_temp.issue_pairing(
    '51000000-0000-4000-8000-000000000001',
    '53000000-0000-4000-8000-000000000001',
    'digest-claimable',
    now() + interval '10 minutes'
  )
);

create temporary table harbor_claim_results (
  attempt integer primary key,
  result jsonb
);

insert into harbor_claim_results(attempt, result)
values (
  1,
  pg_temp.claim_device(
    '51000000-0000-4000-8000-000000000101',
    'digest-claimable',
    'valid-p256-spki-fixture',
    'Child A phone',
    'Pixel',
    '17',
    'full'
  )
);

select ok((select result is not null from harbor_claim_results where attempt = 1), 'claim binds an unbound device identity');
select ok(
  pg_temp.private_count($$select count(*) from private.device_security where auth_user_id = '51000000-0000-4000-8000-000000000101'::uuid$$) = 1
  and (select count(*) from public.devices_public where child_id = '53000000-0000-4000-8000-000000000001') = 1,
  'successful claim atomically creates public device and private security binding'
);
select ok(
  pg_temp.private_count($$select count(*) from private.device_enrollment_tokens where code_digest = 'digest-claimable' and consumed_at is not null$$) = 1
  and pg_temp.private_count($$select count(*) from private.audit_events where event_kind = 'device.enrolled' and actor_user_id = '51000000-0000-4000-8000-000000000101'::uuid$$) = 1,
  'successful claim consumes the token and writes one enrollment audit event'
);

insert into harbor_claim_results(attempt, result)
values (
  2,
  pg_temp.claim_device(
    '51000000-0000-4000-8000-000000000101',
    'digest-claimable',
    'valid-p256-spki-fixture',
    'Duplicate phone',
    'Pixel',
    '17',
    'full'
  )
);

select ok(
  (select result is null from harbor_claim_results where attempt = 2)
  and pg_temp.private_count($$select count(*) from private.device_security where auth_user_id = '51000000-0000-4000-8000-000000000101'::uuid$$) = 1,
  'repeated claim is rejected without a second binding'
);

select * from finish();
rollback;
