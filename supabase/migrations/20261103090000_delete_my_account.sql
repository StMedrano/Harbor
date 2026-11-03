-- Deleting a parent's account, which Google Play requires of any app that lets people create one, and asks for by name:
-- contacts, device location, messages and the inventory of apps on a phone are all associated data.
--
-- The parent's login, profile, push registrations and shared place go. A family with another parent stays with that parent,
-- children and phones included. A family where this was the last parent is deleted, with its children and everything their
-- phones sent, and those phones' anonymous logins go too, since nothing else would ever remove them. That differs from
-- leave_family on purpose: leaving is routine and keeps a family with children from being stranded by one tap, while this is
-- a deliberate, typed request to delete everything, and it has to be honoured.
--
-- It checks a current parent session as the parent functions do, refuses a child phone's anonymous login, and needs the word
-- DELETE. Families are locked in a fixed order, so two parents deleting at once are taken in turn and neither can conclude the
-- other is staying. The audit trail keeps its rows with the actor removed (its foreign key is ON DELETE SET NULL).
create function public.delete_my_account(p_confirmation text)
returns text
language plpgsql security definer set search_path = '' as $$
declare
  actor uuid := (select auth.uid());
  sid uuid;
  fam uuid;
  phones uuid[];
  heir uuid;
begin
  begin
    sid := nullif((select auth.jwt()) ->> 'session_id', '')::uuid;
  exception when invalid_text_representation then sid := null;
  end;
  if actor is null or sid is null then raise exception 'current parent session required' using errcode = '42501'; end if;
  perform 1 from auth.sessions s where s.id = sid and s.user_id = actor for share;
  if not found or not private.harbor_parent_session_active(actor, sid) then raise exception 'current parent session required' using errcode = '42501'; end if;
  if exists (select 1 from auth.users u where u.id = actor and u.is_anonymous) then raise exception 'parent account required' using errcode = '42501'; end if;
  if p_confirmation is distinct from 'DELETE' then raise exception 'confirmation required' using errcode = '22023'; end if;
  for fam in
    select fm.family_id from public.family_members fm
    where fm.user_id = actor and fm.status = 'active' and fm.role in ('owner','parent')
    order by fm.family_id
  loop
    perform 1 from public.families f where f.id = fam for update;
    select o.user_id into heir from public.family_members o
      where o.family_id = fam and o.user_id <> actor and o.status = 'active' and o.role in ('owner','parent')
      order by o.created_at limit 1;
    if heir is null then
      select coalesce(array_agg(ds.auth_user_id), '{}') into phones
        from private.device_security ds join public.devices_public d on d.id = ds.device_id where d.family_id = fam;
      delete from public.families f where f.id = fam;
      delete from auth.users u where u.id = any(phones) and u.is_anonymous;
    else
      -- This column has no foreign key, so it would otherwise keep the deleted parent's id.
      update private.family_map_settings m set updated_by = heir where m.family_id = fam and m.updated_by = actor;
    end if;
  end loop;
  -- Pushes still queued for this parent name them in their target, which has no foreign key either.
  delete from private.notification_outbox o where o.target_ref ->> 'userId' = actor::text;
  delete from auth.users u where u.id = actor;
  return 'deleted';
end $$;

revoke all on function public.delete_my_account(text) from public, anon;
grant execute on function public.delete_my_account(text) to authenticated;
