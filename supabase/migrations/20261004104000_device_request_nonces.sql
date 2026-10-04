-- Harbor Task 8: private replay-protection nonce storage.

create table private.device_request_nonces (
  device_id uuid not null references private.device_security(device_id) on delete cascade,
  nonce text not null check (char_length(btrim(nonce)) between 1 and 256),
  request_timestamp bigint not null,
  claimed_at timestamptz not null default pg_catalog.now(),
  primary key (device_id, nonce)
);

create index device_request_nonces_claimed_at_idx
  on private.device_request_nonces(claimed_at);

revoke all on table private.device_request_nonces from public, anon, authenticated;
grant select, insert, delete on table private.device_request_nonces to service_role;

create or replace function private.claim_device_request_nonce(
  p_device_id uuid,
  p_nonce text,
  p_request_timestamp bigint
)
returns boolean
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_nonce text := btrim(coalesce(p_nonce, ''));
begin
  if p_device_id is null or v_nonce = '' or char_length(v_nonce) > 256 or p_request_timestamp is null then
    raise exception 'device request nonce input is invalid' using errcode = '22023';
  end if;

  insert into private.device_request_nonces (device_id, nonce, request_timestamp)
  values (p_device_id, v_nonce, p_request_timestamp)
  on conflict (device_id, nonce) do nothing;

  return found;
end;
$$;

revoke all on function private.claim_device_request_nonce(uuid, text, bigint) from public, anon, authenticated;
grant execute on function private.claim_device_request_nonce(uuid, text, bigint) to service_role;
