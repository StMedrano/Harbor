begin;
select no_plan();

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
'61000000-0000-4000-8000-000000000001','{"acceptance":"harmless"}',0);
select is((select count(*) from private.notification_outbox), 3::bigint, 'one event fans out to FCM and both installations');
select is((select count(distinct route_payload->>'resourceId') from private.notification_outbox), 1::bigint, 'fanout shares one event UUID');
select ok(not exists(select 1 from private.notification_outbox where not (route_payload ? 'resourceId') or route_payload->>'resourceId' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'), 'all recipients carry valid event UUID');
select ok(not exists(select 1 from private.notification_outbox o where (select count(*) from jsonb_object_keys(o.route_payload)) <> 6), 'new state route has exactly six fields');
update private.device_desired_state set desired_state_version = 2;
select is((select count(distinct route_payload->>'resourceId') from private.notification_outbox), 2::bigint, 'next state version has a different identity');
update private.device_desired_state set desired_state_version = 2;
select is((select count(*) from private.notification_outbox), 6::bigint, 'unchanged version adds no intent');
select * from private.harbor_enqueue_device_command('64000000-0000-4000-8000-000000000001','sync','correlation-command','{}',null);
select ok(not exists(select 1 from private.notification_outbox where event_key like 'device-command:%' and route_payload->>'resourceId' <> split_part(event_key, ':', 2)), 'command keeps command resource UUID');
select ok(not has_function_privilege('authenticated','private.harbor_record_device_notification()','execute'), 'authenticated cannot execute private trigger');
select ok(not has_function_privilege('anon','private.harbor_record_device_notification()','execute'), 'anon cannot execute private trigger');

create function pg_temp.seed_event(p_version bigint, p_transport text, p_resource jsonb)
returns uuid language sql as $$
select private.harbor_enqueue_notification('desired-state:64000000-0000-4000-8000-000000000001:' || p_version,
p_transport, case when p_transport='fcm' then '{"deviceId":"64000000-0000-4000-8000-000000000001"}'::jsonb else jsonb_build_object('subscriptionId', (select id from private.parent_web_push_subscriptions order by id limit 1)) end,
'{"version":1,"kind":"device.state.changed","familyId":"62000000-0000-4000-8000-000000000001","childId":"63000000-0000-4000-8000-000000000001","deviceId":"64000000-0000-4000-8000-000000000001"}'::jsonb || p_resource);
$$;
select pg_temp.seed_event(3,'fcm','{"resourceId":"abcdefab-abcd-4abc-8abc-abcdefabcdef"}');
update private.device_desired_state set desired_state_version = 3;
select is((select count(*) from private.notification_outbox where event_key like '%:3' and route_payload->>'resourceId' = 'abcdefab-abcd-4abc-8abc-abcdefabcdef'), 3::bigint, 'existing identified event reused across missing recipients');
select pg_temp.seed_event(3,'fcm','{"resourceId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}');
select is((select count(*) from private.notification_outbox where event_key like '%:3' and route_payload->>'resourceId' = 'abcdefab-abcd-4abc-8abc-abcdefabcdef'), 3::bigint, 'duplicate enqueue preserves stored identity');
select pg_temp.seed_event(4,'fcm','{}');
update private.device_desired_state set desired_state_version = 4;
select is((select count(*) from private.notification_outbox where event_key like '%:4'), 3::bigint, 'legacy event still fills intended recipients');
select ok(not exists(select 1 from private.notification_outbox where event_key like '%:4' and route_payload ? 'resourceId'), 'existing legacy routes remain legacy');
select pg_temp.seed_event(5,'fcm','{"resourceId":"abcdefab-abcd-4abc-8abc-abcdefabcdea"}');
select pg_temp.seed_event(5,'web_push','{"resourceId":"ABCDEFAB-ABCD-4ABC-8ABC-ABCDEFABCDEA"}');
update private.device_desired_state set desired_state_version = 5;
select is((select count(distinct lower(route_payload->>'resourceId')) from private.notification_outbox where event_key like '%:5'), 1::bigint, 'UUID case differences represent one identity');
select is((select count(*) from private.notification_outbox where event_key like '%:5'), 3::bigint, 'case-compatible replay fills missing recipient');

select pg_temp.seed_event(6,'fcm','{"resourceId":"abcdefab-abcd-4abc-8abc-abcdefabcdef"}');
select pg_temp.seed_event(6,'web_push','{"resourceId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}');
select throws_ok('update private.device_desired_state set desired_state_version = 6','P0001','NOTIFICATION_EVENT_IDENTITY_CONFLICT','conflicting identities fail transaction');
select is((select desired_state_version from private.device_desired_state),5::bigint,'conflict rolls back state version');
select is((select count(*) from private.notification_outbox where event_key like '%:6'),2::bigint,'conflict commits no partial fanout');
delete from private.notification_outbox where event_key like '%:6';
select pg_temp.seed_event(6,'fcm','{"resourceId":"bad"}');
select throws_ok('update private.device_desired_state set desired_state_version = 6','P0001','NOTIFICATION_EVENT_IDENTITY_CONFLICT','malformed identity fails transaction');
select is((select desired_state_version from private.device_desired_state),5::bigint,'malformed identity rolls back state version');
select is((select count(*) from private.notification_outbox where event_key like '%:6'),1::bigint,'malformed identity commits no partial fanout');
delete from private.notification_outbox where event_key like '%:6';
select pg_temp.seed_event(6,'fcm','{}');
select pg_temp.seed_event(6,'web_push','{"resourceId":"abcdefab-abcd-4abc-8abc-abcdefabcdef"}');
select throws_ok('update private.device_desired_state set desired_state_version = 6','P0001','NOTIFICATION_EVENT_IDENTITY_CONFLICT','mixed legacy and identified event fails transaction');
select is((select desired_state_version from private.device_desired_state),5::bigint,'mixed event rolls back state version');
select is((select count(*) from private.notification_outbox where event_key like '%:6'),2::bigint,'mixed event commits no partial fanout');
select * from finish();
rollback;
