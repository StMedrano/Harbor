-- A parent's own view of the family map: every child in their family, and every parent who is sharing right now.
-- Until now only a child's phone could see this (private.harbor_get_family_map, behind a device proof), so a parent
-- had no way to see their family on one map. A parent who shares is now visible to the whole family, parents
-- included; a parent who is not sharing simply does not appear. Children never choose: their phones report.
--
-- The view is security definer so it can reach private.parent_location_sharing and private.device_location_fixes,
-- and every branch is gated on the caller being an active owner/parent of that same family. A child cannot read it
-- (a child is not a family member) and goes on using harbor_get_family_map behind its device proof.
create view public.family_places
with (security_invoker = false) as
select
  ch.family_id,
  'child'::text as kind,
  ch.id as person_id,
  null::uuid as user_id,
  ch.display_name as name,
  g.fix_at, g.latitude, g.longitude, g.accuracy_m
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
  s.fix_at, s.latitude, s.longitude, s.accuracy_m
from private.parent_location_sharing s
join public.family_members m
  on m.family_id = s.family_id and m.user_id = s.user_id and m.status = 'active'
where s.expires_at > pg_catalog.now()
  and authz.has_active_family_role(s.family_id, array['owner','parent']::text[]);

revoke all on public.family_places from public, anon;
grant select on public.family_places to authenticated;
