begin;
select plan(17);

insert into auth.users (
  id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous
) values
  (
    '71000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated',
    'task10-parent-a@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false
  ),
  (
    '71000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated',
    'task10-parent-b@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false
  );

create or replace function pg_temp.call_register_web_push(
  p_user_id uuid,
  p_client_installation_id text,
  p_endpoint text,
  p_p256dh text,
  p_auth text
)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute
    'select to_jsonb(r) from private.harbor_register_parent_web_push($1, $2, $3, $4, $5) as r'
    into v_result
    using p_user_id, p_client_installation_id, p_endpoint, p_p256dh, p_auth;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.call_remove_web_push(
  p_user_id uuid,
  p_client_installation_id text,
  p_endpoint text default null
)
returns bigint
language plpgsql
as $$
declare
  v_result bigint;
begin
  execute
    'select private.harbor_remove_parent_web_push($1, $2, $3)'
    into v_result
    using p_user_id, p_client_installation_id, p_endpoint;
  return v_result;
exception when others then
  return -1;
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

select has_table('private', 'parent_web_push_subscriptions', 'parent Web Push subscriptions are private');
select has_column('private', 'parent_web_push_subscriptions', 'endpoint_hash', 'subscription identity includes endpoint hash');
select ok(
  case when to_regclass('private.parent_web_push_subscriptions') is null then false
       else not has_table_privilege('authenticated', 'private.parent_web_push_subscriptions', 'SELECT')
        and not has_table_privilege('authenticated', 'private.parent_web_push_subscriptions', 'INSERT')
        and not has_table_privilege('authenticated', 'private.parent_web_push_subscriptions', 'UPDATE')
        and not has_table_privilege('authenticated', 'private.parent_web_push_subscriptions', 'DELETE')
        and not has_table_privilege('anon', 'private.parent_web_push_subscriptions', 'SELECT')
        and not has_table_privilege('anon', 'private.parent_web_push_subscriptions', 'INSERT')
        and not has_table_privilege('anon', 'private.parent_web_push_subscriptions', 'UPDATE')
        and not has_table_privilege('anon', 'private.parent_web_push_subscriptions', 'DELETE') end,
  'clients have no direct grants on private Web Push subscriptions'
);
select has_function(
  'private',
  'harbor_register_parent_web_push',
  array['uuid','text','text','text','text'],
  'backend registration helper exists'
);
select has_function(
  'private',
  'harbor_remove_parent_web_push',
  array['uuid','text','text'],
  'backend removal helper exists'
);

select ok(
  pg_temp.call_register_web_push(
    '71000000-0000-4000-8000-000000000001',
    'parent-a-install-1',
    'https://push.example.test/a-one',
    'a-p256dh-one',
    'a-auth-one'
  ) is not null,
  'authenticated parent registration can be persisted by the backend helper'
);

select ok(
  pg_temp.call_register_web_push(
    '71000000-0000-4000-8000-000000000001',
    'parent-a-install-1',
    'https://push.example.test/a-one',
    'a-p256dh-one',
    'a-auth-one'
  ) is not null,
  'duplicate registration is accepted idempotently'
);

select is(
  pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'parent-a-install-1' and status = 'active'$$),
  1::bigint,
  'duplicate registration creates only one active subscription'
);

select ok(
  pg_temp.call_register_web_push(
    '71000000-0000-4000-8000-000000000001',
    'parent-a-install-2',
    'https://push.example.test/a-two',
    'a-p256dh-two',
    'a-auth-two'
  ) is not null,
  'a parent can register a second client installation'
);

select is(
  pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and status = 'active'$$),
  2::bigint,
  'multiple installations remain active for one parent'
);

select ok(
  pg_temp.call_register_web_push(
    '71000000-0000-4000-8000-000000000002',
    'parent-a-install-1',
    'https://push.example.test/b-one',
    'b-p256dh-one',
    'b-auth-one'
  ) is not null,
  'another parent may use the same installation identifier without sharing ownership'
);

select ok(
  pg_temp.call_register_web_push(
    '71000000-0000-4000-8000-000000000001',
    'parent-a-install-1',
    'https://push.example.test/a-one-rotated',
    'a-p256dh-rotated',
    'a-auth-rotated'
  ) is not null,
  'endpoint and keys can rotate for an existing installation'
);

select ok(
  pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'parent-a-install-1' and status = 'active'$$) = 1
  and pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'parent-a-install-1' and status = 'active' and endpoint = 'https://push.example.test/a-one-rotated' and p256dh = 'a-p256dh-rotated' and auth = 'a-auth-rotated'$$) = 1
  and pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'parent-a-install-1' and endpoint = 'https://push.example.test/a-one' and status <> 'active'$$) = 1,
  'rotation activates only the new endpoint and retires the previous endpoint'
);

select is(
  pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000002'::uuid and client_installation_id = 'parent-a-install-1' and endpoint = 'https://push.example.test/b-one' and status = 'active'$$),
  1::bigint,
  'one parent cannot rotate another parent subscription with the same installation id'
);

select is(
  pg_temp.call_remove_web_push(
    '71000000-0000-4000-8000-000000000001',
    'parent-a-install-2',
    'https://push.example.test/a-two'
  ),
  1::bigint,
  'explicit endpoint removal disables the matching parent subscription'
);

select ok(
  pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'parent-a-install-2' and status = 'active'$$) = 0
  and pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'parent-a-install-1' and status = 'active'$$) = 1,
  'explicit removal does not disable another installation owned by the same parent'
);

select ok(
  pg_temp.call_remove_web_push(
    '71000000-0000-4000-8000-000000000001',
    'parent-a-install-1',
    null
  ) = 1
  and pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and status = 'active'$$) = 0
  and pg_temp.private_count($$select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000002'::uuid and client_installation_id = 'parent-a-install-1' and status = 'active'$$) = 1,
  'installation removal is owner-scoped and leaves another parent untouched'
);

select * from finish();
rollback;
