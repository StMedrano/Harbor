begin;
select plan(8);

insert into auth.users (id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous)
values
  ('71000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated', 'retention-parent@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false),
  ('71000000-0000-4000-8000-000000000101', 'authenticated', 'authenticated', null, '{}'::jsonb, '{}'::jsonb, now(), now(), true);
insert into public.families (id, name) values ('72000000-0000-4000-8000-000000000001', 'Retention Family');
insert into public.family_members (family_id, user_id, role, status)
values ('72000000-0000-4000-8000-000000000001', '71000000-0000-4000-8000-000000000001', 'owner', 'active');
insert into public.children (id, family_id, display_name)
values ('73000000-0000-4000-8000-000000000001', '72000000-0000-4000-8000-000000000001', 'Retention Child');
insert into public.devices_public (id, family_id, child_id, display_name, supervision_mode, status)
values ('74000000-0000-4000-8000-000000000001', '72000000-0000-4000-8000-000000000001', '73000000-0000-4000-8000-000000000001', 'Retention Device', 'full', 'active');
insert into private.device_security (device_id, auth_user_id, public_key_spki)
values ('74000000-0000-4000-8000-000000000001', '71000000-0000-4000-8000-000000000101', 'retention-p256-spki-fixture');

insert into private.device_request_nonces (device_id, nonce, request_timestamp, claimed_at) values
  ('74000000-0000-4000-8000-000000000001', 'retention-old', 1, now() - interval '25 hours'),
  ('74000000-0000-4000-8000-000000000001', 'retention-recent', 2, now() - interval '10 minutes');

select ok(private.harbor_purge_request_nonces() >= 1, 'an old nonce is purged');
select is((select count(*) from private.device_request_nonces where nonce = 'retention-old'), 0::bigint, 'a nonce past a day is gone');
select is((select count(*) from private.device_request_nonces where nonce = 'retention-recent'), 1::bigint, 'a recent nonce still stops a replay');

insert into private.notification_outbox (event_key, transport, target_ref, route_payload, status, created_at, updated_at) values
  ('retention-sent-old', 'fcm', '{"deviceId":"74000000-0000-4000-8000-000000000001"}'::jsonb, '{}'::jsonb, 'sent', now() - interval '40 days', now() - interval '31 days'),
  ('retention-dead-old', 'fcm', '{"deviceId":"74000000-0000-4000-8000-000000000001"}'::jsonb, '{}'::jsonb, 'dead_letter', now() - interval '40 days', now() - interval '31 days'),
  ('retention-sent-recent', 'fcm', '{"deviceId":"74000000-0000-4000-8000-000000000001"}'::jsonb, '{}'::jsonb, 'sent', now() - interval '40 days', now() - interval '29 days'),
  ('retention-retry-old', 'fcm', '{"deviceId":"74000000-0000-4000-8000-000000000001"}'::jsonb, '{}'::jsonb, 'retry', now() - interval '40 days', now() - interval '31 days');

select ok(private.harbor_purge_finished_notifications() >= 2, 'old finished pushes are purged');
select is((select count(*) from private.notification_outbox where event_key in ('retention-sent-old', 'retention-dead-old')), 0::bigint, 'pushes finished over 30 days ago are gone');
select is((select count(*) from private.notification_outbox where event_key = 'retention-sent-recent'), 1::bigint, 'a push finished within 30 days is kept');
select is((select count(*) from private.notification_outbox where event_key = 'retention-retry-old'), 1::bigint, 'an unfinished push is never purged by age here');

select ok(
  not has_function_privilege('service_role', 'private.harbor_purge_request_nonces()', 'execute')
  and not has_function_privilege('authenticated', 'private.harbor_purge_finished_notifications()', 'execute'),
  'no client role can run the cleanup'
);

select * from finish();
rollback;
