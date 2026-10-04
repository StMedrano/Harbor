begin;
select plan(18);

create or replace function pg_temp.enqueue_notification(
  p_event_key text,
  p_transport text,
  p_target_ref jsonb,
  p_route_payload jsonb
)
returns uuid
language plpgsql
as $$
declare
  v_id uuid;
begin
  execute 'select private.harbor_enqueue_notification($1, $2, $3, $4)'
    into v_id
    using p_event_key, p_transport, p_target_ref, p_route_payload;
  return v_id;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.claim_notification(p_outbox_id uuid, p_now timestamptz)
returns jsonb
language plpgsql
as $$
declare
  v_result jsonb;
begin
  execute 'select to_jsonb(r) from private.harbor_claim_notification($1, $2) as r'
    into v_result
    using p_outbox_id, p_now;
  return v_result;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.complete_notification(p_outbox_id uuid)
returns text
language plpgsql
as $$
declare
  v_status text;
begin
  execute 'select private.harbor_complete_notification($1)'
    into v_status
    using p_outbox_id;
  return v_status;
exception when others then
  return null;
end;
$$;

create or replace function pg_temp.fail_notification(
  p_outbox_id uuid,
  p_retryable boolean,
  p_error_category text,
  p_next_attempt_at timestamptz
)
returns text
language plpgsql
as $$
declare
  v_status text;
begin
  execute 'select private.harbor_fail_notification($1, $2, $3, $4)'
    into v_status
    using p_outbox_id, p_retryable, p_error_category, p_next_attempt_at;
  return v_status;
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

select has_table('private', 'notification_outbox', 'notification outbox is private');
select has_column('private', 'notification_outbox', 'event_key', 'outbox keeps stable event identity');
select has_column('private', 'notification_outbox', 'target_ref', 'outbox keeps transport target reference');
select has_column('private', 'notification_outbox', 'route_payload', 'outbox keeps minimal route payload');
select has_column('private', 'notification_outbox', 'attempt_count', 'outbox tracks delivery attempts');
select has_column('private', 'notification_outbox', 'next_attempt_at', 'outbox tracks retry timing');
select has_column('private', 'notification_outbox', 'last_error_category', 'outbox tracks error category');
select ok(
  case when to_regclass('private.notification_outbox') is null then false
       else not has_table_privilege('authenticated', 'private.notification_outbox', 'SELECT')
        and not has_table_privilege('authenticated', 'private.notification_outbox', 'INSERT')
        and not has_table_privilege('authenticated', 'private.notification_outbox', 'UPDATE')
        and not has_table_privilege('authenticated', 'private.notification_outbox', 'DELETE')
        and not has_table_privilege('anon', 'private.notification_outbox', 'SELECT') end,
  'clients have no direct grants on notification outbox'
);

select has_function(
  'private', 'harbor_enqueue_notification', array['text','text','jsonb','jsonb'],
  'idempotent enqueue helper exists'
);
select has_function(
  'private', 'harbor_claim_notification', array['uuid','timestamp with time zone'],
  'atomic claim helper exists'
);
select has_function(
  'private', 'harbor_complete_notification', array['uuid'],
  'completion helper exists'
);
select has_function(
  'private', 'harbor_fail_notification', array['uuid','boolean','text','timestamp with time zone'],
  'failure transition helper exists'
);

create temporary table task11_ids(name text primary key, id uuid);

insert into task11_ids(name, id)
values (
  'fcm',
  pg_temp.enqueue_notification(
    'event-task11-1',
    'fcm',
    '{"deviceId":"11111111-1111-4111-8111-111111111111"}'::jsonb,
    '{"version":1,"kind":"device.state.changed","deviceId":"11111111-1111-4111-8111-111111111111"}'::jsonb
  )
);

select ok((select id from task11_ids where name = 'fcm') is not null, 'first durable notification intent can be enqueued');

select ok(
  pg_temp.enqueue_notification(
    'event-task11-1',
    'fcm',
    '{"deviceId":"11111111-1111-4111-8111-111111111111"}'::jsonb,
    '{"version":1,"kind":"device.state.changed","deviceId":"11111111-1111-4111-8111-111111111111"}'::jsonb
  ) = (select id from task11_ids where name = 'fcm')
  and pg_temp.private_count($$select count(*) from private.notification_outbox where event_key = 'event-task11-1' and transport = 'fcm'$$) = 1,
  'duplicate event transport target is idempotent'
);

insert into task11_ids(name, id)
values (
  'web',
  pg_temp.enqueue_notification(
    'event-task11-1',
    'web_push',
    '{"subscriptionId":"22222222-2222-4222-8222-222222222222"}'::jsonb,
    '{"version":1,"kind":"device.state.changed","deviceId":"11111111-1111-4111-8111-111111111111"}'::jsonb
  )
);

select ok(
  (select id from task11_ids where name = 'web') is not null
  and (select id from task11_ids where name = 'web') <> (select id from task11_ids where name = 'fcm')
  and pg_temp.private_count($$select count(*) from private.notification_outbox where event_key = 'event-task11-1'$$) = 2,
  'one logical event may have independent FCM and Web Push rows'
);

select ok(
  (pg_temp.claim_notification((select id from task11_ids where name = 'fcm'), pg_catalog.now()) ->> 'status') = 'processing'
  and pg_temp.private_count($$select count(*) from private.notification_outbox where event_key = 'event-task11-1' and transport = 'fcm' and status = 'processing' and attempt_count = 1$$) = 1
  and pg_temp.claim_notification((select id from task11_ids where name = 'fcm'), pg_catalog.now()) is null,
  'claim is atomic and a processing row cannot be claimed twice'
);

select ok(
  pg_temp.fail_notification(
    (select id from task11_ids where name = 'fcm'),
    true,
    'provider_error',
    pg_catalog.now() + interval '5 minutes'
  ) = 'retry'
  and pg_temp.private_count($$select count(*) from private.notification_outbox where event_key = 'event-task11-1' and transport = 'fcm' and status = 'retry' and last_error_category = 'provider_error' and attempt_count = 1$$) = 1
  and pg_temp.claim_notification((select id from task11_ids where name = 'fcm'), pg_catalog.now()) is null
  and (pg_temp.claim_notification((select id from task11_ids where name = 'fcm'), pg_catalog.now() + interval '6 minutes') ->> 'attempt_count')::integer = 2,
  'retry scheduling prevents early claim and increments the next attempt only when due'
);

select ok(
  pg_temp.complete_notification((select id from task11_ids where name = 'fcm')) = 'sent'
  and pg_temp.complete_notification((select id from task11_ids where name = 'fcm')) = 'sent'
  and pg_temp.claim_notification((select id from task11_ids where name = 'fcm'), pg_catalog.now() + interval '1 day') is null
  and pg_temp.private_count($$select count(*) from private.notification_outbox where event_key = 'event-task11-1' and transport = 'fcm' and status = 'sent' and attempt_count = 2$$) = 1,
  'completion is idempotent and already-sent rows are never reclaimed'
);

select ok(
  (pg_temp.claim_notification((select id from task11_ids where name = 'web'), pg_catalog.now()) ->> 'status') = 'processing'
  and pg_temp.fail_notification(
    (select id from task11_ids where name = 'web'),
    false,
    'invalid_subscription',
    null
  ) = 'dead_letter'
  and pg_temp.private_count($$select count(*) from private.notification_outbox where event_key = 'event-task11-1' and transport = 'web_push' and status = 'dead_letter' and last_error_category = 'invalid_subscription'$$) = 1
  and pg_temp.private_count($$select count(*) from private.notification_outbox where event_key = 'event-task11-1' and transport = 'fcm' and status = 'sent'$$) = 1,
  'permanent failure dead-letters only its transport row and does not undo a sent sibling'
);

select * from finish();
rollback;
