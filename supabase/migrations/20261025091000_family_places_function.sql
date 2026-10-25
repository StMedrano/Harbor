-- The family map as a function rather than a view.
--
-- public.family_places was a security-definer view, which the database advisor rates an error, and it had a real gap
-- behind the warning: it checked that the caller was an active parent of the family, but not that they still held a
-- current session. Every other parent read checks both, so a signed-out parent's token kept answering until it
-- expired. This function checks exactly what the parent functions check, through harbor_family_parent_check, and is
-- refused outright — not answered with nothing — for a family the caller is not in.
--
-- The view is kept for now only because Harbor 0.36 on the phones reads it; it is dropped once 0.37 is installed.
create function public.family_places(p_family_id uuid)
returns table(kind text, person_id uuid, user_id uuid, name text, fix_at text,
              latitude double precision, longitude double precision, accuracy_m integer)
language plpgsql security definer set search_path = '' as $$
#variable_conflict use_column
declare actor uuid := (select auth.uid()); sid uuid;
begin
  begin
    sid := nullif((select auth.jwt()) ->> 'session_id', '')::uuid;
  exception when invalid_text_representation then sid := null;
  end;
  if actor is null or sid is null or p_family_id is null then
    raise exception 'current parent session required' using errcode = '42501';
  end if;
  perform private.harbor_family_parent_check(actor, sid, p_family_id);
  return query
  select 'child'::text, ch.id, null::uuid, ch.display_name,
         to_char(g.fix_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
         g.latitude, g.longitude, g.accuracy_m
  from public.children ch
  left join lateral (
    select f.fix_at, f.latitude, f.longitude, f.accuracy_m
    from private.device_location_fixes f
    join public.devices_public dv on dv.id = f.device_id
    where dv.child_id = ch.id and dv.family_id = ch.family_id
      and dv.status <> 'revoked' and dv.revoked_at is null
      and f.expires_at > pg_catalog.now()
    order by f.fix_at desc
    limit 1
  ) g on true
  where ch.family_id = p_family_id
  union all
  select 'parent'::text, m.id, s.user_id, s.label,
         to_char(s.fix_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
         s.latitude, s.longitude, s.accuracy_m
  from private.parent_location_sharing s
  join public.family_members m
    on m.family_id = s.family_id and m.user_id = s.user_id and m.status = 'active'
  where s.family_id = p_family_id and s.expires_at > pg_catalog.now();
end $$;

revoke all on function public.family_places(uuid) from public, anon;
grant execute on function public.family_places(uuid) to authenticated;
