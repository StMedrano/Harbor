begin;
select plan(9);

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
insert into private.parent_web_push_subscriptions (user_id, client_installation_id, endpoint, endpoint_hash, p256dh, auth)
values
('61000000-0000-4000-8000-000000000001','installation-a','https://push.test/a',repeat('a',64),'key','auth'),
('61000000-0000-4000-8000-000000000001','installation-b','https://push.test/b',repeat('b',64),'key','auth');
select * from private.harbor_update_device_desired_state(
'64000000-0000-4000-8000-000000000001','62000000-0000-4000-8000-000000000001',
'61000000-0000-4000-8000-000000000001','{"message":"sensitive domain content"}',0);
select is((select count(*) from private.notification_outbox), 3::bigint, 'desired-state write persists device and two parent installation intents');
select is((select count(*) from private.notification_outbox where transport = 'fcm'), 1::bigint, 'desired-state change records device wake intent');
select is((select count(*) from private.notification_outbox where transport = 'web_push'), 2::bigint, 'desired-state change fans out to active parent installations');
select ok(not exists (select 1 from private.notification_outbox where route_payload::text like '%sensitive%' or route_payload ? 'message'), 'domain content is excluded from routes');
update private.device_desired_state set acknowledged_version = 1;
select is((select count(*) from private.notification_outbox), 3::bigint, 'acknowledgment does not create another domain event');
select * from private.harbor_enqueue_device_command('64000000-0000-4000-8000-000000000001','sync','domain-command','{"message":"private command"}',null);
select is((select count(*) from private.notification_outbox), 6::bigint, 'new command transaction also persists notification intent');
select * from private.harbor_enqueue_device_command('64000000-0000-4000-8000-000000000001','sync','domain-command','{}',null);
select is((select count(*) from private.notification_outbox), 6::bigint, 'duplicate command does not duplicate notification intent');
update public.family_members set status = 'removed';
select * from private.harbor_enqueue_device_command('64000000-0000-4000-8000-000000000001','sync','removed-parent-command','{}',null);
select is((select count(*) from private.notification_outbox), 7::bigint, 'removed parent gets no new subscription intent');
savepoint atomic_intent;
select * from private.harbor_enqueue_device_command('64000000-0000-4000-8000-000000000001','sync','rolled-back-command','{}',null);
rollback to savepoint atomic_intent;
select is((select count(*) from private.notification_outbox), 7::bigint, 'rollback removes domain mutation and its delivery intent together');
select * from finish();
rollback;
