create table private.parent_web_push_subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  client_installation_id text not null,
  endpoint text not null,
  endpoint_hash text not null,
  p256dh text not null,
  auth_key text not null,
  status text not null default 'active' check (status in ('active', 'removed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  removed_at timestamptz,
  unique (user_id, client_installation_id, endpoint_hash)
);

revoke all on private.parent_web_push_subscriptions from anon, authenticated;

create or replace function private.harbor_register_parent_web_push(
  p_user_id uuid,
  p_client_installation_id text,
  p_endpoint text,
  p_p256dh text,
  p_auth text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_endpoint_hash text := encode(extensions.digest(p_endpoint, 'sha256'), 'hex');
begin
  if p_client_installation_id is null or btrim(p_client_installation_id) = ''
     or p_endpoint is null or btrim(p_endpoint) = ''
     or p_p256dh is null or btrim(p_p256dh) = ''
     or p_auth is null or btrim(p_auth) = '' then
    raise exception 'invalid web push subscription';
  end if;

  update private.parent_web_push_subscriptions
  set status = 'removed', removed_at = now(), updated_at = now()
  where user_id = p_user_id
    and client_installation_id = p_client_installation_id
    and endpoint_hash <> v_endpoint_hash
    and status = 'active';

  insert into private.parent_web_push_subscriptions (
    user_id, client_installation_id, endpoint, endpoint_hash, p256dh, auth_key
  ) values (
    p_user_id, p_client_installation_id, p_endpoint, v_endpoint_hash, p_p256dh, p_auth
  )
  on conflict (user_id, client_installation_id, endpoint_hash)
  do update set
    endpoint = excluded.endpoint,
    p256dh = excluded.p256dh,
    auth_key = excluded.auth_key,
    status = 'active',
    removed_at = null,
    updated_at = now();

  return true;
end;
$$;

create or replace function private.harbor_remove_parent_web_push(
  p_user_id uuid,
  p_client_installation_id text,
  p_endpoint text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  update private.parent_web_push_subscriptions
  set status = 'removed', removed_at = now(), updated_at = now()
  where user_id = p_user_id
    and client_installation_id = p_client_installation_id
    and (p_endpoint is null or endpoint_hash = encode(extensions.digest(p_endpoint, 'sha256'), 'hex'))
    and status = 'active';
  return true;
end;
$$;

revoke all on function private.harbor_register_parent_web_push(uuid,text,text,text,text) from public, anon, authenticated;
revoke all on function private.harbor_remove_parent_web_push(uuid,text,text) from public, anon, authenticated;