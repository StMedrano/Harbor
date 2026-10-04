begin;
select plan(7);

select has_table('private', 'device_desired_state', 'desired state is private');
select has_table('private', 'device_commands', 'device commands are private');
select has_table('private', 'device_fcm_registrations', 'FCM registrations are private');

select has_function('private', 'harbor_update_device_desired_state', array['uuid','jsonb','bigint'], 'desired-state update helper exists');
select has_function('private', 'harbor_sync_device', array['uuid','bigint','uuid[]'], 'device sync helper exists');
select has_function('private', 'harbor_register_device_fcm', array['uuid','text'], 'FCM rotation helper exists');

select ok(
  not has_table_privilege('authenticated', 'private.device_fcm_registrations', 'SELECT'),
  'authenticated clients cannot read private FCM tokens'
);

select * from finish();
rollback;
