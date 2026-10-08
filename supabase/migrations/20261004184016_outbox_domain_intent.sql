create function private.harbor_record_device_notification()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare
  v_event_key text;
  v_kind text;
  v_route jsonb;
  v_device public.devices_public;
  v_subscription record;
begin
  select * into strict v_device from public.devices_public where id = new.device_id;
  if tg_table_name = 'device_desired_state' then
    if tg_op = 'UPDATE' and new.desired_state_version = old.desired_state_version then
      return new;
    end if;
    v_kind := 'device.state.changed';
    v_event_key := 'desired-state:' || new.device_id::text || ':' || new.desired_state_version::text;
  else
    v_kind := 'device.command.created';
    v_event_key := 'device-command:' || new.id::text;
  end if;
  v_route := jsonb_build_object('version', 1, 'kind', v_kind,
    'familyId', v_device.family_id, 'childId', v_device.child_id, 'deviceId', v_device.id);
  if tg_table_name = 'device_commands' then
    v_route := v_route || jsonb_build_object('resourceId', new.id);
  end if;
  perform private.harbor_enqueue_notification(v_event_key, 'fcm',
    jsonb_build_object('deviceId', v_device.id), v_route);
  for v_subscription in
    select s.id from private.parent_web_push_subscriptions s
    join public.family_members fm on fm.user_id = s.user_id
    where fm.family_id = v_device.family_id and fm.status = 'active'
      and fm.role in ('owner', 'parent') and s.status = 'active'
  loop
    perform private.harbor_enqueue_notification(v_event_key, 'web_push',
      jsonb_build_object('subscriptionId', v_subscription.id), v_route);
  end loop;
  return new;
end;
$$;
revoke all on function private.harbor_record_device_notification() from public, anon, authenticated;

create trigger harbor_desired_state_notification
  after insert or update of desired_state_version on private.device_desired_state
  for each row execute function private.harbor_record_device_notification();
create trigger harbor_device_command_notification
  after insert on private.device_commands
  for each row execute function private.harbor_record_device_notification();
