-- Harbor Task 3: backend-only staff authorization and audit boundary.

create schema if not exists private;

revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

create table private.staff_authorizations (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null check (role in ('support', 'admin')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table private.audit_events (
  id uuid primary key default gen_random_uuid(),
  event_kind text not null,
  family_id uuid references public.families(id) on delete set null,
  actor_user_id uuid references auth.users(id) on delete set null,
  resource_type text,
  resource_id uuid,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index audit_events_family_created_idx
  on private.audit_events(family_id, created_at desc);
create index audit_events_actor_created_idx
  on private.audit_events(actor_user_id, created_at desc);

revoke all on table private.staff_authorizations from public, anon, authenticated;
revoke all on table private.audit_events from public, anon, authenticated;

grant select, insert, update, delete on table private.staff_authorizations to service_role;
grant select, insert, update, delete on table private.audit_events to service_role;

alter default privileges in schema private
  revoke all on tables from public, anon, authenticated;
alter default privileges in schema private
  grant select, insert, update, delete on tables to service_role;
