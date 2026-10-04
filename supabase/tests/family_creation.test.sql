begin;

select plan(10);

insert into auth.users (
  id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous
) values (
  '40000000-0000-4000-8000-000000000004',
  'authenticated',
  'authenticated',
  'family-create@harbor.test',
  '{}'::jsonb,
  '{"display_name":"Family Creator"}'::jsonb,
  now(),
  now(),
  false
);

create or replace function pg_temp.harbor_create_family(
  p_user_id uuid,
  p_name text,
  p_idempotency_key text
)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(result) from private.create_family_atomic($1, $2, $3) as result'
    into v_result
    using p_user_id, p_name, p_idempotency_key;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.harbor_private_count(p_sql text)
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

select ok(
  to_regclass('private.family_creation_requests') is not null,
  'family creation idempotency state is private and durable'
);

select ok(
  to_regprocedure('private.create_family_atomic(uuid,text,text)') is not null,
  'atomic family creation helper exists'
);

select ok(
  case
    when to_regprocedure('private.create_family_atomic(uuid,text,text)') is null then false
    else not has_function_privilege(
      'anon',
      to_regprocedure('private.create_family_atomic(uuid,text,text)'),
      'EXECUTE'
    )
  end,
  'anon cannot execute atomic family creation directly'
);

select ok(
  case
    when to_regprocedure('private.create_family_atomic(uuid,text,text)') is null then false
    else not has_function_privilege(
      'authenticated',
      to_regprocedure('private.create_family_atomic(uuid,text,text)'),
      'EXECUTE'
    )
  end,
  'authenticated cannot execute atomic family creation directly'
);

create temporary table harbor_family_results (
  attempt integer primary key,
  result jsonb
);

insert into harbor_family_results(attempt, result)
values (
  1,
  pg_temp.harbor_create_family(
    '40000000-0000-4000-8000-000000000004',
    '  First Harbor Family  ',
    ' same-request '
  )
);

select ok(
  (select result is not null from harbor_family_results where attempt = 1),
  'first atomic request creates a family result'
);

select is(
  (select count(*)::bigint from public.families where name = 'First Harbor Family'),
  1::bigint,
  'family creation persists the normalized family exactly once'
);

select is(
  (
    select count(*)::bigint
    from public.family_members fm
    join public.families f on f.id = fm.family_id
    where f.name = 'First Harbor Family'
      and fm.user_id = '40000000-0000-4000-8000-000000000004'
      and fm.role = 'owner'
      and fm.status = 'active'
  ),
  1::bigint,
  'family creation atomically adds the caller as active owner'
);

select is(
  pg_temp.harbor_private_count(
    $$select count(*) from private.audit_events
      where event_kind = 'family.created'
        and actor_user_id = '40000000-0000-4000-8000-000000000004'::uuid$$
  ),
  1::bigint,
  'family creation records one audit event'
);

insert into harbor_family_results(attempt, result)
values (
  2,
  pg_temp.harbor_create_family(
    '40000000-0000-4000-8000-000000000004',
    'Different Name Must Not Win',
    'same-request'
  )
);

select ok(
  (select result ->> 'family_id' from harbor_family_results where attempt = 1) is not null
  and (select result ->> 'family_id' from harbor_family_results where attempt = 1)
      = (select result ->> 'family_id' from harbor_family_results where attempt = 2)
  and (select result ->> 'name' from harbor_family_results where attempt = 2)
      = 'First Harbor Family',
  'duplicate idempotency key reuses the original result'
);

select ok(
  (select count(*) from public.families where name in ('First Harbor Family', 'Different Name Must Not Win')) = 1
  and pg_temp.harbor_private_count(
    $$select count(*) from private.family_creation_requests
      where user_id = '40000000-0000-4000-8000-000000000004'::uuid
        and idempotency_key = 'same-request'$$
  ) = 1
  and pg_temp.harbor_private_count(
    $$select count(*) from private.audit_events
      where event_kind = 'family.created'
        and actor_user_id = '40000000-0000-4000-8000-000000000004'::uuid$$
  ) = 1,
  'duplicate request creates no second family, idempotency row, or audit event'
);

select * from finish();
rollback;
