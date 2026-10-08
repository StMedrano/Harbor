alter table private.notification_outbox
  drop constraint notification_outbox_event_key_transport_key,
  add constraint notification_outbox_event_transport_target_key unique (event_key, transport, target_ref);

create or replace function private.harbor_enqueue_notification(
  p_event_key text, p_transport text, p_target_ref jsonb, p_route_payload jsonb
)
returns uuid language plpgsql security definer set search_path = ''
as $$
declare v_id uuid;
begin
  if p_event_key is null or btrim(p_event_key) = '' then
    raise exception 'VALIDATION_FAILED';
  end if;
  if p_transport not in ('fcm', 'web_push') then
    raise exception 'VALIDATION_FAILED';
  end if;
  insert into private.notification_outbox(event_key, transport, target_ref, route_payload)
  values (p_event_key, p_transport, p_target_ref, p_route_payload)
  on conflict (event_key, transport, target_ref) do update set event_key = excluded.event_key
  returning id into v_id;
  return v_id;
end;
$$;
revoke all on function private.harbor_enqueue_notification(text, text, jsonb, jsonb) from public, anon, authenticated;
