-- Harbor: parent read path for device controls (additive).
-- Parents set controls through update-device-state (private.device_desired_state). They could not
-- read them back, because that table is service-role only. This function returns, for one family,
-- the desired state of each active device plus whether the phone has acknowledged it.

create or replace function private.harbor_list_family_device_states(
  p_family_id uuid,
  p_actor_user_id uuid
)
returns table (
  device_id uuid,
  child_id uuid,
  desired_state jsonb,
  desired_state_version bigint,
  acknowledged_version bigint
)
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if p_family_id is null or p_actor_user_id is null then
    raise exception 'family state input is invalid' using errcode = '22023';
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

  return query
  select d.id,
         d.child_id,
         coalesce(s.desired_state, '{}'::jsonb),
         coalesce(s.desired_state_version, 0),
         coalesce(s.acknowledged_version, 0)
  from public.devices_public d
  join private.device_security ds on ds.device_id = d.id
  left join private.device_desired_state s on s.device_id = d.id
  where d.family_id = p_family_id
    and d.status = 'active'
    and d.revoked_at is null;
end;
$$;

revoke all on function private.harbor_list_family_device_states(uuid, uuid)
  from public, anon, authenticated;
grant execute on function private.harbor_list_family_device_states(uuid, uuid)
  to service_role;
