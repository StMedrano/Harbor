-- Harbor Task 10: parent Web Push subscription lifecycle.

create extension if not exists pgcrypto with schema extensions;

create table private.parent_web_push_subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  client_installation_id text not null check (char_length(btrim(client_installation_id)) > 0),
  endpoint text not null check (char_length(btrim(endpoint)) > 0),
  endpoint_hash text not null check (char_length(endpoint_hash) = 64),
  p256dh text not null check (char_length(btrim(p256dh)) > 0),
  auth text not null check (char_length(btrim(auth)) > 0),
  status text not null default 'active' check (status in ('active', 'removed', 'invalid')),
  disabled_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, client_installation_id, endpoint_hash)
);

create unique index parent_web_push_one_active_installation_idx
  on private.parent_web_push_subscriptions(user_id, client_installation_id)
  where status = 'active';

alter table private.parent_web_push_subscriptions enable row level security;

revoke all on table private.parent_web_push_subscriptions from public, anon, authenticated;
grant select, insert, update, delete on table private.parent_web_push_subscriptions to service_role;

create or replace function private.harbor_register_parent_web_push(
  p_user_id uuid,
  p_client_installation_id text,
  p_endpoint text,
  p_p256dh text,
  p_auth text
)
returns table (
  subscription_id uuid,
  registered_endpoint_hash text,
  status text
)
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_installation_id text;
  v_endpoint text;
  v_p256dh text;
  v_auth text;
  v_endpoint_hash text;
  v_subscription_id uuid;
begin
  v_installation_id := btrim(coalesce(p_client_installation_id, ''));
  v_endpoint := btrim(coalesce(p_endpoint, ''));
  v_p256dh := btrim(coalesce(p_p256dh, ''));
  v_auth := btrim(coalesce(p_auth, ''));

  if p_user_id is null or v_installation_id = '' or v_endpoint = '' or v_p256dh = '' or v_auth = '' then
    raise exception 'Web Push registration input is invalid' using errcode = '22023';
  end if;

  v_endpoint_hash := pg_catalog.encode(extensions.digest(v_endpoint, 'sha256'), 'hex');

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_user_id::text || ':' || v_installation_id, 0)
  );

  update private.parent_web_push_subscriptions as subscription
  set status = 'removed',
      disabled_at = pg_catalog.now(),
      updated_at = pg_catalog.now()
  where subscription.user_id = p_user_id
    and subscription.client_installation_id = v_installation_id
    and subscription.status = 'active'
    and subscription.endpoint_hash <> v_endpoint_hash;

  insert into private.parent_web_push_subscriptions (
    user_id,
    client_installation_id,
    endpoint,
    endpoint_hash,
    p256dh,
    auth,
    status,
    disabled_at
  ) values (
    p_user_id,
    v_installation_id,
    v_endpoint,
    v_endpoint_hash,
    v_p256dh,
    v_auth,
    'active',
    null
  )
  on conflict (user_id, client_installation_id, endpoint_hash)
  do update set
    endpoint = excluded.endpoint,
    p256dh = excluded.p256dh,
    auth = excluded.auth,
    status = 'active',
    disabled_at = null,
    updated_at = pg_catalog.now()
  returning id into v_subscription_id;

  return query select v_subscription_id, v_endpoint_hash, 'active'::text;
end;
$$;

create or replace function private.harbor_remove_parent_web_push(
  p_user_id uuid,
  p_client_installation_id text,
  p_endpoint text default null
)
returns bigint
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_installation_id text;
  v_endpoint text;
  v_endpoint_hash text;
  v_removed bigint;
begin
  v_installation_id := btrim(coalesce(p_client_installation_id, ''));

  if p_user_id is null or v_installation_id = '' then
    raise exception 'Web Push removal input is invalid' using errcode = '22023';
  end if;

  if p_endpoint is not null then
    v_endpoint := btrim(p_endpoint);
    if v_endpoint = '' then
      raise exception 'Web Push endpoint is invalid' using errcode = '22023';
    end if;
    v_endpoint_hash := pg_catalog.encode(extensions.digest(v_endpoint, 'sha256'), 'hex');
  end if;

  update private.parent_web_push_subscriptions as subscription
  set status = 'removed',
      disabled_at = pg_catalog.now(),
      updated_at = pg_catalog.now()
  where subscription.user_id = p_user_id
    and subscription.client_installation_id = v_installation_id
    and subscription.status = 'active'
    and (v_endpoint_hash is null or subscription.endpoint_hash = v_endpoint_hash);

  get diagnostics v_removed = row_count;
  return v_removed;
end;
$$;

revoke all on function private.harbor_register_parent_web_push(uuid, text, text, text, text) from public, anon, authenticated;
revoke all on function private.harbor_remove_parent_web_push(uuid, text, text) from public, anon, authenticated;
grant execute on function private.harbor_register_parent_web_push(uuid, text, text, text, text) to service_role;
grant execute on function private.harbor_remove_parent_web_push(uuid, text, text) to service_role;
