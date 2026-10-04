create table private.notification_outbox (
  id uuid primary key default gen_random_uuid(),
  event_key text not null,
  transport text not null check (transport in ('fcm', 'web_push')),
  target_ref jsonb not null,
  route_payload jsonb not null,
  status text not null default 'pending' check (status in ('pending', 'processing', 'sent', 'retry', 'dead_letter')),
  attempt_count integer not null default 0 check (attempt_count >= 0),
  next_attempt_at timestamptz,
  last_error_category text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (event_key, transport)
);

revoke all on private.notification_outbox from anon, authenticated;

create or replace function private.harbor_enqueue_notification(
  p_event_key text,
  p_transport text,
  p_target_ref jsonb,
  p_route_payload jsonb
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id uuid;
begin
  if p_event_key is null or btrim(p_event_key) = '' then
    raise exception 'VALIDATION_FAILED';
  end if;
  if p_transport not in ('fcm', 'web_push') then
    raise exception 'VALIDATION_FAILED';
  end if;

  insert into private.notification_outbox(event_key, transport, target_ref, route_payload)
  values (p_event_key, p_transport, p_target_ref, p_route_payload)
  on conflict (event_key, transport) do update
    set event_key = excluded.event_key
  returning id into v_id;

  return v_id;
end;
$$;

create or replace function private.harbor_claim_notification(
  p_outbox_id uuid,
  p_now timestamptz
)
returns private.notification_outbox
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_row private.notification_outbox;
begin
  update private.notification_outbox
  set status = 'processing',
      attempt_count = attempt_count + 1,
      updated_at = p_now
  where id = p_outbox_id
    and (
      status = 'pending'
      or (status = 'retry' and next_attempt_at <= p_now)
    )
  returning * into v_row;

  return v_row;
end;
$$;

create or replace function private.harbor_complete_notification(p_outbox_id uuid)
returns text
language plpgsql
security definer
set search_path = ''
as $$;
declare
  v_status text;
begin
  update private.notification_outbox
  set status = 'sent',
      next_attempt_at = null,
      last_error_category = null,
      updated_at = now()
  where id = p_outbox_id
    and status = 'processing'
  returning status into v_status;

  if v_status is null then
    select status into v_status from private.notification_outbox where id = p_outbox_id;
  end if;
  return v_status;
end;
$$;

create or replace function private.harbor_fail_notification(
  p_outbox_id uuid,
  p_retryable boolean,
  p_error_category text,
  p_next_attempt_at timestamptz
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_status text;
begin
  update private.notification_outbox
  set status = case when p_retryable then 'retry' else 'dead_letter' end,
      next_attempt_at = case when p_retryable then p_next_attempt_at else null end,
      last_error_category = p_error_category,
      updated_at = now()
  where id = p_outbox_id
    and status = 'processing'
  returning status into v_status;

  if v_status is null then
    select status into v_status from private.notification_outbox where id = p_outbox_id;
  end if;
  return v_status;
end;
$$;

revoke all on function private.harbor_enqueue_notification(text, text, jsonb, jsonb) from public, anon, authenticated;
revoke all on function private.harbor_claim_notification(uuid, timestamptz) from public, anon, authenticated;
revoke all on function private.harbor_complete_notification(uuid) from public, anon, authenticated;
revoke all on function private.harbor_fail_notification(uuid, boolean, text, timestamptz) from public, anon, authenticated;
