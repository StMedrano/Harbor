begin;
select plan(8);

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

select * from finish();
rollback;