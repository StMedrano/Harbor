-- Harbor Task 6: durable, idempotent, atomic family creation.

create table private.family_creation_requests (
  user_id uuid not null references auth.users(id) on delete cascade,
  idempotency_key text not null check (char_length(btrim(idempotency_key)) > 0),
  family_id uuid not null unique references public.families(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, idempotency_key)
);

revoke all on table private.family_creation_requests from public, anon, authenticated;
grant select, insert, update, delete on table private.family_creation_requests to service_role;

create or replace function private.create_family_atomic(
  p_user_id uuid,
  p_name text,
  p_idempotency_key text
)
returns table (
  family_id uuid,
  name text,
  role text
)
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_family_id uuid;
  v_name text;
  v_idempotency_key text;
begin
  if p_user_id is null then
    raise exception 'user id is required' using errcode = '22023';
  end if;

  v_name := btrim(coalesce(p_name, ''));
  v_idempotency_key := btrim(coalesce(p_idempotency_key, ''));

  if char_length(v_name) < 1 or char_length(v_name) > 100 then
    raise exception 'family name must contain 1 to 100 characters' using errcode = '22023';
  end if;

  if char_length(v_idempotency_key) < 1 then
    raise exception 'idempotency key is required' using errcode = '22023';
  end if;

  -- Serialize the same caller/key pair so concurrent retries cannot create
  -- duplicate families. Hash collisions only serialize unrelated requests;
  -- the durable row remains the authoritative idempotency check.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_user_id::text || ':' || v_idempotency_key, 0)
  );

  select request.family_id
    into v_family_id
  from private.family_creation_requests as request
  where request.user_id = p_user_id
    and request.idempotency_key = v_idempotency_key;

  if v_family_id is not null then
    select family.name
      into v_name
    from public.families as family
    where family.id = v_family_id;

    return query
    select v_family_id, v_name, 'owner'::text;
    return;
  end if;

  insert into public.families (name)
  values (v_name)
  returning id, public.families.name into v_family_id, v_name;

  insert into public.family_members (family_id, user_id, role, status)
  values (v_family_id, p_user_id, 'owner', 'active');

  insert into private.family_creation_requests (user_id, idempotency_key, family_id)
  values (p_user_id, v_idempotency_key, v_family_id);

  insert into private.audit_events (
    event_kind,
    family_id,
    actor_user_id,
    resource_type,
    resource_id,
    metadata
  ) values (
    'family.created',
    v_family_id,
    p_user_id,
    'family',
    v_family_id,
    '{}'::jsonb
  );

  return query
  select v_family_id, v_name, 'owner'::text;
end;
$$;

revoke all on function private.create_family_atomic(uuid, text, text)
  from public, anon, authenticated;
grant execute on function private.create_family_atomic(uuid, text, text)
  to service_role;
