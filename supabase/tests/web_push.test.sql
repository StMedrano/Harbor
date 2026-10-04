begin;
select plan(14);

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

select has_table(
  'private',
  'parent_web_push_subscriptions',
  'parent web push subscriptions are private'
);

select ok(
  case when to_regclass('private.parent_web_push_subscriptions') is null then false
       else not has_table_privilege('authenticated', 'private.parent_web_push_subscriptions', 'SELECT') end,
  'authenticated clients cannot read private web push subscriptions'
);

select has_function(
  'private',
  'harbor_register_parent_web_push',
  array['uuid','text','text','text','text'],
  'registration helper binds subscription to authenticated user and installation'
);

select has_function(
  'private',
  'harbor_remove_parent_web_push',
  array['uuid','text','text'],
  'removal helper binds subscription to authenticated user and installation'
);

select ok(
  exists (
    select 1 from information_schema.table_constraints
    where table_schema = 'private'
      and table_name = 'parent_web_push_subscriptions'
      and constraint_type = 'UNIQUE'
  ),
  'subscription table has stable uniqueness for idempotent registration'
);

select has_column(
  'private',
  'parent_web_push_subscriptions',
  'client_installation_id',
  'multiple client installations can be tracked independently'
);

select has_column(
  'private',
  'parent_web_push_subscriptions',
  'endpoint_hash',
  'subscription identity stores endpoint hash'
);

select has_column(
  'private',
  'parent_web_push_subscriptions',
  'updated_at',
  'subscription lifecycle records updates for rotation'
);

do $$
begin
  perform private.harbor_register_parent_web_push(
    '71000000-0000-4000-8000-000000000001'::uuid,
    'shared-installation',
    'https://push.example.test/a-1',
    'a-p256dh-1',
    'a-auth-1'
  );
  perform private.harbor_register_parent_web_push(
    '71000000-0000-4000-8000-000000000002'::uuid,
    'shared-installation',
    'https://push.example.test/a-1',
    'b-p256dh-1',
    'b-auth-1'
  );
end;
$$;

select is(
  (select count(*) from private.parent_web_push_subscriptions where client_installation_id = 'shared-installation'),
  2::bigint,
  'the same installation id and endpoint remain isolated per authenticated parent'
);

do $$
begin
  perform private.harbor_register_parent_web_push(
    '71000000-0000-4000-8000-000000000001'::uuid,
    'parent-a-browser-2',
    'https://push.example.test/a-2',
    'a-p256dh-2',
    'a-auth-2'
  );
end;
$$;

select is(
  (select count(*) from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and status = 'active'),
  2::bigint,
  'one parent can keep multiple browser installations active'
);

do $$
begin
  perform private.harbor_register_parent_web_push(
    '71000000-0000-4000-8000-000000000001'::uuid,
    'shared-installation',
    'https://push.example.test/a-1-rotated',
    'a-p256dh-rotated',
    'a-auth-rotated'
  );
end;
$$;

select ok(
  (select count(*) = 1 from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'shared-installation' and endpoint = 'https://push.example.test/a-1' and status = 'removed')
  and (select count(*) = 1 from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'shared-installation' and endpoint = 'https://push.example.test/a-1-rotated' and status = 'active'),
  'endpoint rotation retires the old endpoint and activates the replacement'
);

do $$
begin
  perform private.harbor_register_parent_web_push(
    '71000000-0000-4000-8000-000000000001'::uuid,
    'shared-installation',
    'https://push.example.test/a-1-rotated',
    'a-p256dh-key-rotated',
    'a-auth-key-rotated'
  );
end;
$$;

select ok(
  (select count(*) = 1 from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'shared-installation' and endpoint = 'https://push.example.test/a-1-rotated' and status = 'active')
  and (select p256dh = 'a-p256dh-key-rotated' and auth_key = 'a-auth-key-rotated' from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'shared-installation' and endpoint = 'https://push.example.test/a-1-rotated' and status = 'active'),
  'key rotation updates the active subscription without creating a duplicate'
);

do $$
begin
  perform private.harbor_register_parent_web_push(
    '71000000-0000-4000-8000-000000000001'::uuid,
    'shared-installation',
    'https://push.example.test/a-1-rotated',
    'a-p256dh-key-rotated',
    'a-auth-key-rotated'
  );
end;
$$;

select ok(
  (select count(*) = 2 from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'shared-installation')
  and (select count(*) = 1 from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'shared-installation' and status = 'active'),
  'duplicate registration is idempotent for the current endpoint identity'
);

do $$
begin
  perform private.harbor_remove_parent_web_push(
    '71000000-0000-4000-8000-000000000001'::uuid,
    'parent-a-browser-2',
    'https://push.example.test/a-2'
  );
end;
$$;

select ok(
  (select status = 'removed' from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000001'::uuid and client_installation_id = 'parent-a-browser-2')
  and (select status = 'active' from private.parent_web_push_subscriptions where user_id = '71000000-0000-4000-8000-000000000002'::uuid and client_installation_id = 'shared-installation'),
  'explicit removal affects only the authenticated parent installation target'
);

select * from finish();
rollback;
