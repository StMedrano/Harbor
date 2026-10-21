-- App controls apply on the child phone at its next desired-state sync. A parent who pauses a phone or blocks an app
-- expects it to take effect now, so a desired-state write that carries controls also queues a data-only wake push to
-- that phone. The push names only the route (kind and ids); the phone fetches the controls with its signed sync.
create or replace function private.harbor_update_device_desired_state(p_device_id uuid, p_family_id uuid, p_actor_user_id uuid, p_desired_state jsonb, p_expected_version bigint)
 returns table(desired_state_version bigint)
 language plpgsql
 set search_path to ''
as $function$
declare
  v_current_version bigint;
  v_next_version bigint;
  v_child_id uuid;
begin
  if p_device_id is null
     or p_family_id is null
     or p_actor_user_id is null
     or p_desired_state is null
     or p_expected_version is null
     or p_expected_version < 0 then
    raise exception 'desired state input is invalid' using errcode = '22023';
  end if;

  if not exists (
    select 1
    from public.family_members fm
    where fm.family_id = p_family_id
      and fm.user_id = p_actor_user_id
      and fm.status = 'active'
      and fm.role in ('owner', 'parent')
  ) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;

  if not exists (
    select 1
    from public.devices_public d
    join private.device_security ds on ds.device_id = d.id
    where d.id = p_device_id
      and d.family_id = p_family_id
  ) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;

  if exists (
    select 1
    from public.devices_public d
    where d.id = p_device_id
      and d.family_id = p_family_id
      and (d.status <> 'active' or d.revoked_at is not null)
  ) then
    raise exception 'DEVICE_REVOKED' using errcode = '42501';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('desired-state:' || p_device_id::text, 0)
  );

  select s.desired_state_version
    into v_current_version
  from private.device_desired_state s
  where s.device_id = p_device_id;

  v_current_version := coalesce(v_current_version, 0);
  if p_expected_version <> v_current_version then
    raise exception 'STALE_VERSION' using errcode = 'P0001';
  end if;

  v_next_version := v_current_version + 1;

  insert into private.device_desired_state (
    device_id,
    desired_state,
    desired_state_version,
    acknowledged_version,
    wake_requested_at,
    updated_at
  ) values (
    p_device_id,
    p_desired_state,
    v_next_version,
    0,
    pg_catalog.now(),
    pg_catalog.now()
  )
  on conflict (device_id) do update
    set desired_state = excluded.desired_state,
        desired_state_version = excluded.desired_state_version,
        wake_requested_at = excluded.wake_requested_at,
        updated_at = excluded.updated_at;

  if p_desired_state ? 'controls' then
    select d.child_id into v_child_id from public.devices_public d where d.id = p_device_id;
    perform private.harbor_enqueue_notification(
      'controls:' || p_device_id::text || ':' || v_next_version::text, 'fcm',
      jsonb_build_object('deviceId', p_device_id),
      jsonb_build_object('version', 1, 'kind', 'device.controls.changed', 'familyId', p_family_id,
        'childId', v_child_id, 'deviceId', p_device_id, 'resourceId', pg_catalog.gen_random_uuid()));
  end if;

  return query select v_next_version;
end;
$function$;
