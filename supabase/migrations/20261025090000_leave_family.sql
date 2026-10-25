-- Leaving a family, or deleting one nobody else is in.
--
-- A parent who joined a family, or started one by mistake, had no way out. This lets a parent leave: their
-- membership becomes 'removed', so they stop seeing its children, and the existing membership trigger deletes any
-- place they were sharing with it. The family, its children and their phones stay with the other parents, and a new
-- invite brings the parent back (accept_parent_invite reactivates the row). "Owner" is not handed on: it records who
-- started the family, and moving it would make that untrue.
--
-- When the caller is the last parent, leaving would strand the family, so instead:
--  - with no children and no phones, the family is deleted outright — there is nothing in it to lose;
--  - with children or phones, nothing changes and the answer says so. Deleting would end the monitoring of a child's
--    phone from a single tap, and that needs its own deliberate path, not this one.
--
-- Called by the apps through PostgREST, the first function to be. It is security definer and checks exactly what the
-- parent functions check — a current parent session and an active membership of this family — through
-- harbor_family_parent_check. The family row is locked first, so two parents leaving at the same moment are taken
-- one after the other and can never both conclude that the other one is staying.
create function public.leave_family(p_family_id uuid)
returns text
language plpgsql security definer set search_path = '' as $$
declare
  actor uuid := (select auth.uid());
  sid uuid;
  others integer;
  kids integer;
  phones integer;
begin
  begin
    sid := nullif((select auth.jwt()) ->> 'session_id', '')::uuid;
  exception when invalid_text_representation then sid := null;
  end;
  if actor is null or sid is null or p_family_id is null then
    raise exception 'current parent session required' using errcode = '42501';
  end if;
  perform 1 from public.families f where f.id = p_family_id for update;
  if not found then raise exception 'parent membership required' using errcode = '42501'; end if;
  perform private.harbor_family_parent_check(actor, sid, p_family_id);
  select count(*) into others from public.family_members fm
   where fm.family_id = p_family_id and fm.status = 'active' and fm.role in ('owner','parent') and fm.user_id <> actor;
  if others > 0 then
    update public.family_members fm set status = 'removed', updated_at = pg_catalog.now()
     where fm.family_id = p_family_id and fm.user_id = actor;
    return 'left';
  end if;
  select count(*) into kids from public.children c where c.family_id = p_family_id;
  select count(*) into phones from public.devices_public d
   where d.family_id = p_family_id and d.status <> 'revoked' and d.revoked_at is null;
  if kids > 0 or phones > 0 then return 'last_parent_with_children'; end if;
  delete from public.families f where f.id = p_family_id;
  return 'deleted';
end $$;

revoke all on function public.leave_family(uuid) from public, anon;
grant execute on function public.leave_family(uuid) to authenticated;
