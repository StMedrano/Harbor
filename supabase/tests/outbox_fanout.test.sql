begin;
select plan(5);
create temporary table fanout_ids as
select private.harbor_enqueue_notification('fanout-event', 'web_push', '{"subscriptionId":"11111111-1111-4111-8111-111111111111"}', '{"version":1,"kind":"changed"}') as first_id,
       private.harbor_enqueue_notification('fanout-event', 'web_push', '{"subscriptionId":"22222222-2222-4222-8222-222222222222"}', '{"version":1,"kind":"changed"}') as second_id;
select isnt((select first_id from fanout_ids), (select second_id from fanout_ids), 'one event retains independent Web Push recipients');
select is((select count(*) from private.notification_outbox where event_key = 'fanout-event'), 2::bigint, 'both recipient intents persist');
select is(private.harbor_enqueue_notification('fanout-event', 'web_push', '{"subscriptionId":"11111111-1111-4111-8111-111111111111"}', '{"version":1,"kind":"changed"}'), (select first_id from fanout_ids), 'duplicate recipient enqueue retains original intent');
create temporary table fcm_ids as
select private.harbor_enqueue_notification('fanout-event', 'fcm', '{"deviceId":"11111111-1111-4111-8111-111111111111"}', '{"version":1,"kind":"changed"}') as first_id,
       private.harbor_enqueue_notification('fanout-event', 'fcm', '{"deviceId":"22222222-2222-4222-8222-222222222222"}', '{"version":1,"kind":"changed"}') as second_id;
select isnt((select first_id from fcm_ids), (select second_id from fcm_ids), 'one event retains independent FCM recipients');
select is((select count(*) from private.notification_outbox where event_key = 'fanout-event'), 4::bigint, 'recipient fanout retains both transports');
select * from finish();
rollback;
