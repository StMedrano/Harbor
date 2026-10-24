-- family_places handed back a raw timestamptz. Both apps parse an instant strictly — the regex and an exact
-- Instant.parse round-trip — so they refuse postgrest's "+00:00" form. The child-facing harbor_get_family_map
-- already emits YYYY-MM-DDTHH:MM:SSZ, so this does the same and the two reads agree. A view column cannot change
-- type in place, so the view is dropped and recreated.
drop view if exists public.family_places;

create view public.family_places
with (security_invoker = false) as
select
  ch.family_id,
  'child'::text as kind,
  ch.id as person_id,
  null::uuid as user_id,
  ch.display_name as name,
  to_char(g.fix_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as fix_at,
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
where authz.has_active_family_role(ch.family_id, array['owner','parent']::text[])
union all
select
  s.family_id,
  'parent'::text,
  m.id,
  s.user_id,
  s.label,
  to_char(s.fix_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
  s.latitude, s.longitude, s.accuracy_m
from private.parent_location_sharing s
join public.family_members m
  on m.family_id = s.family_id and m.user_id = s.user_id and m.status = 'active'
where s.expires_at > pg_catalog.now()
  and authz.has_active_family_role(s.family_id, array['owner','parent']::text[]);

revoke all on public.family_places from public, anon;
grant select on public.family_places to authenticated;
