-- Harbor Task 3: core family schema.
-- Client grants remain closed until Task 4 installs RLS policies.

create schema if not exists private;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.families (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(btrim(name)) between 1 and 100),
  timezone text not null default 'UTC' check (char_length(btrim(timezone)) > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.family_members (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('owner', 'parent')),
  status text not null default 'active' check (status in ('active', 'invited', 'removed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (family_id, user_id)
);

create table public.children (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  display_name text not null check (char_length(btrim(display_name)) between 1 and 100),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (family_id, id)
);

create table public.devices_public (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  child_id uuid not null,
  display_name text not null check (char_length(btrim(display_name)) between 1 and 100),
  model text,
  android_version text,
  supervision_mode text not null default 'unknown'
    check (supervision_mode in ('unknown', 'standard', 'full')),
  status text not null default 'active'
    check (status in ('active', 'revoked')),
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (family_id, child_id)
    references public.children(family_id, id) on delete cascade
);

create index family_members_user_family_idx
  on public.family_members(user_id, family_id);
create index family_members_family_status_idx
  on public.family_members(family_id, status);
create index children_family_idx
  on public.children(family_id);
create index devices_public_family_idx
  on public.devices_public(family_id);
create index devices_public_child_idx
  on public.devices_public(child_id);

-- Public-schema tables are not exposed to clients until Task 4 adds RLS and
-- explicitly restores the minimum required grants.
revoke all on table public.profiles from anon, authenticated;
revoke all on table public.families from anon, authenticated;
revoke all on table public.family_members from anon, authenticated;
revoke all on table public.children from anon, authenticated;
revoke all on table public.devices_public from anon, authenticated;

create or replace function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, display_name)
  values (
    new.id,
    coalesce(
      nullif(btrim(new.raw_user_meta_data ->> 'display_name'), ''),
      nullif(btrim(new.raw_user_meta_data ->> 'full_name'), '')
    )
  )
  on conflict (id) do nothing;

  return new;
end;
$$;

revoke all on function private.handle_new_user() from public, anon, authenticated;

drop trigger if exists harbor_create_profile_after_signup on auth.users;
create trigger harbor_create_profile_after_signup
after insert on auth.users
for each row execute function private.handle_new_user();
