-- Harbor Task 4: family-scoped read authorization.
-- Direct client mutations remain denied; privileged writes use Edge Functions.

create schema if not exists authz;
revoke all on schema authz from public, anon;
grant usage on schema authz to authenticated;

create or replace function authz.has_active_family_role(
  p_family_id uuid,
  p_allowed_roles text[]
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.family_members fm
    where fm.family_id = p_family_id
      and fm.user_id = (select auth.uid())
      and fm.status = 'active'
      and fm.role = any(p_allowed_roles)
  );
$$;

create or replace function authz.shares_active_family_with(p_other_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select
    p_other_user_id = (select auth.uid())
    or exists (
      select 1
      from public.family_members me
      join public.family_members other_member
        on other_member.family_id = me.family_id
      where me.user_id = (select auth.uid())
        and me.status = 'active'
        and me.role in ('owner', 'parent')
        and other_member.user_id = p_other_user_id
        and other_member.status = 'active'
    );
$$;

revoke all on function authz.has_active_family_role(uuid, text[]) from public, anon;
revoke all on function authz.shares_active_family_with(uuid) from public, anon;
grant execute on function authz.has_active_family_role(uuid, text[]) to authenticated;
grant execute on function authz.shares_active_family_with(uuid) to authenticated;

alter table public.profiles enable row level security;
alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.children enable row level security;
alter table public.devices_public enable row level security;

alter table public.profiles force row level security;
alter table public.families force row level security;
alter table public.family_members force row level security;
alter table public.children force row level security;
alter table public.devices_public force row level security;

-- Re-state the least-privilege client grants. Anonymous callers get no Harbor
-- family rows; authenticated principals receive read access only, gated by RLS.
revoke all on table public.profiles from anon, authenticated;
revoke all on table public.families from anon, authenticated;
revoke all on table public.family_members from anon, authenticated;
revoke all on table public.children from anon, authenticated;
revoke all on table public.devices_public from anon, authenticated;

grant select on table public.profiles to authenticated;
grant select on table public.families to authenticated;
grant select on table public.family_members to authenticated;
grant select on table public.children to authenticated;
grant select on table public.devices_public to authenticated;

create policy profiles_select_active_family
on public.profiles
for select
to authenticated
using (
  (select auth.uid()) is not null
  and authz.shares_active_family_with(id)
);

create policy families_select_active_member
on public.families
for select
to authenticated
using (
  (select auth.uid()) is not null
  and authz.has_active_family_role(id, array['owner', 'parent']::text[])
);

create policy family_members_select_active_family
on public.family_members
for select
to authenticated
using (
  (select auth.uid()) is not null
  and authz.has_active_family_role(family_id, array['owner', 'parent']::text[])
);

create policy children_select_active_family
on public.children
for select
to authenticated
using (
  (select auth.uid()) is not null
  and authz.has_active_family_role(family_id, array['owner', 'parent']::text[])
);

create policy devices_public_select_active_family
on public.devices_public
for select
to authenticated
using (
  (select auth.uid()) is not null
  and authz.has_active_family_role(family_id, array['owner', 'parent']::text[])
);

-- Policy predicates are dominated by active-member lookup. Keep the partial
-- index small and aligned with the helper's user/family/role access pattern.
create index family_members_active_user_family_role_idx
  on public.family_members(user_id, family_id, role)
  where status = 'active';
