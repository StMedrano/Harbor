create or replace function private.harbor_enqueue_notification(
  p_event_key text, p_transport text, p_target_ref jsonb, p_route_payload jsonb
)
returns uuid language plpgsql security definer set search_path = ''
as $$
declare v_id uuid;
begin
  if p_event_key is null or btrim(p_event_key) = '' then
    raise exception 'VALIDATION_FAILED';
  end if;
  if p_transport not in ('fcm', 'web_push') then
    raise exception 'VALIDATION_FAILED';
  end if;
  if p_transport = 'fcm' and (p_target_ref ? 'parentFcmRegistrationId' or p_target_ref ? 'userId') then
    if jsonb_typeof(p_target_ref) <> 'object' or (select count(*) from jsonb_object_keys(p_target_ref)) <> 2
      or not coalesce(jsonb_typeof(p_target_ref->'parentFcmRegistrationId')='string' and (p_target_ref->>'parentFcmRegistrationId') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',false)
      or not coalesce(jsonb_typeof(p_target_ref->'userId')='string' and (p_target_ref->>'userId') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',false) then
      raise exception 'VALIDATION_FAILED' using errcode='22023';
    end if;
  end if;
  insert into private.notification_outbox(event_key, transport, target_ref, route_payload)
  values (p_event_key, p_transport, p_target_ref, p_route_payload)
  on conflict (event_key, transport, target_ref) do update set event_key = excluded.event_key
  returning id into v_id;
  return v_id;
end;
$$;
revoke all on function private.harbor_enqueue_notification(text, text, jsonb, jsonb) from public, anon, authenticated;

create or replace function private.harbor_record_device_notification()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare
  v_event_key text;
  v_kind text;
  v_route jsonb;
  v_device public.devices_public;
  v_subscription record;
  v_rows bigint;
  v_identified bigint;
  v_invalid bigint;
  v_identities bigint;
  v_resource text;
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
  if tg_table_name = 'device_desired_state' then
    select count(*),
      count(*) filter (where route_payload ? 'resourceId'),
      count(*) filter (where route_payload ? 'resourceId' and not coalesce(
        jsonb_typeof(route_payload->'resourceId') = 'string' and
        route_payload->>'resourceId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', false)),
      count(distinct lower(route_payload->>'resourceId')),
      min(lower(route_payload->>'resourceId'))
    into v_rows, v_identified, v_invalid, v_identities, v_resource
    from private.notification_outbox where event_key = v_event_key;
    if v_rows = 0 then
      v_resource := pg_catalog.gen_random_uuid()::text;
    elsif v_identified = 0 then
      v_resource := null; -- Preserve legacy events without backfill or resend.
    elsif v_identified <> v_rows or v_invalid <> 0 or v_identities <> 1 then
      raise exception 'NOTIFICATION_EVENT_IDENTITY_CONFLICT';
    end if;
    if v_resource is not null then
      v_route := v_route || jsonb_build_object('resourceId', v_resource);
    end if;
  end if;
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
  for v_subscription in
    select r.id,r.user_id from private.parent_fcm_registrations r
    join public.family_members fm on fm.user_id=r.user_id
    where fm.family_id=v_device.family_id and fm.status='active' and fm.role in ('owner','parent')
      and r.active and private.harbor_parent_session_active(r.user_id,r.session_id)
  loop
    perform private.harbor_enqueue_notification(v_event_key,'fcm',
      jsonb_build_object('parentFcmRegistrationId',v_subscription.id,'userId',v_subscription.user_id),v_route);
  end loop;
  return new;
end;
$$;
revoke all on function private.harbor_record_device_notification() from public, anon, authenticated;

create function private.harbor_disable_parent_fcm(registration uuid,captured_hash text,outbox uuid,attempt integer) returns void
language sql security invoker set search_path='' as $$
 update private.parent_fcm_registrations r set active=false,removed_at=clock_timestamp(),updated_at=clock_timestamp()
 where r.id=registration and r.active and r.token_hash=captured_hash and exists(
  select 1 from private.notification_outbox o where o.id=outbox and o.transport='fcm' and o.status='processing'
   and o.attempt_count=attempt and o.next_attempt_at>clock_timestamp()
   and o.target_ref->>'parentFcmRegistrationId'=r.id::text and o.target_ref->>'userId'=r.user_id::text)
$$;
revoke all on function private.harbor_disable_parent_fcm(uuid,text,uuid,integer) from public,anon,authenticated;
grant execute on function private.harbor_disable_parent_fcm(uuid,text,uuid,integer) to service_role;
