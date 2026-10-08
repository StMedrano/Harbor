create or replace function private.harbor_claim_notification(p_outbox_id uuid, p_now timestamptz)
returns setof private.notification_outbox
language sql security definer set search_path = ''
as $$
  update private.notification_outbox
  set status = 'processing', attempt_count = attempt_count + 1,
      next_attempt_at = p_now + interval '5 minutes', updated_at = p_now
  where id = p_outbox_id and (
    status = 'pending' or (status = 'retry' and next_attempt_at <= p_now)
    or (status = 'processing' and (next_attempt_at is null or next_attempt_at <= p_now))
  ) returning *;
$$;

drop function private.harbor_complete_notification(uuid);
drop function private.harbor_fail_notification(uuid, boolean, text, timestamptz);

create function private.harbor_complete_notification(p_outbox_id uuid, p_attempt integer)
returns text language plpgsql security definer set search_path = ''
as $$
declare v_status text;
begin
  update private.notification_outbox
  set status = 'sent', next_attempt_at = null, last_error_category = null, updated_at = now()
  where id = p_outbox_id and attempt_count = p_attempt
    and status = 'processing' and next_attempt_at > now()
  returning status into v_status;
  if v_status is null then
    select status into v_status from private.notification_outbox
    where id = p_outbox_id and attempt_count = p_attempt and status = 'sent';
  end if;
  return v_status;
end;
$$;

create function private.harbor_fail_notification(p_outbox_id uuid, p_attempt integer,
  p_retryable boolean, p_error_category text, p_next_attempt_at timestamptz)
returns text language plpgsql security definer set search_path = ''
as $$
declare v_status text;
begin
  update private.notification_outbox
  set status = case when p_retryable then 'retry' else 'dead_letter' end,
      next_attempt_at = case when p_retryable then p_next_attempt_at else null end,
      last_error_category = p_error_category, updated_at = now()
  where id = p_outbox_id and attempt_count = p_attempt
    and status = 'processing' and next_attempt_at > now()
  returning status into v_status;
  if v_status is null then
    select status into v_status from private.notification_outbox
    where id = p_outbox_id and attempt_count = p_attempt
      and status = case when p_retryable then 'retry' else 'dead_letter' end;
  end if;
  return v_status;
end;
$$;

revoke all on function private.harbor_claim_notification(uuid, timestamptz) from public, anon, authenticated;
revoke all on function private.harbor_complete_notification(uuid, integer) from public, anon, authenticated;
revoke all on function private.harbor_fail_notification(uuid, integer, boolean, text, timestamptz) from public, anon, authenticated;
