-- 1. Prompt push delivery on development.
-- Pushes were queued in private.notification_outbox but nothing sent them (the dispatcher had no scheduler), so no
-- parent or child ever got one. Edge functions that queue a push now deliver due ones right after they answer; these
-- helpers pick what to send and retire what should never be sent.
--
-- Delivered: the kinds a person needs to see or a phone needs to act on. Not delivered: the generic
-- device.state.changed fan-out queued on every desired-state write (it would notify the parent about their own change
-- and duplicate device.controls.changed for the child). Anything not sent within 15 minutes is stale and retired.
create function private.harbor_due_notifications(p_limit integer)
returns table(outbox_id uuid)
language sql security invoker set search_path='' as $$
 select o.id from private.notification_outbox o
 where o.status in ('pending','retry')
  and (o.next_attempt_at is null or o.next_attempt_at<=pg_catalog.now())
  and o.created_at>pg_catalog.now()-interval '15 minutes'
  and o.route_payload->>'kind' in ('device.controls.changed','device.access.requested','device.access.approved',
   'device.access.denied','device.checkin.requested','device.controls.off','device.alert.created')
 order by o.created_at
 limit greatest(1,least(coalesce(p_limit,20),50))
$$;

create function private.harbor_retire_notifications() returns bigint
language plpgsql security invoker set search_path='' as $$
declare superseded bigint; expired bigint;
begin
 update private.notification_outbox set status='dead_letter',last_error_category='superseded',next_attempt_at=null,updated_at=pg_catalog.now()
 where status in ('pending','retry') and route_payload->>'kind'='device.state.changed';
 get diagnostics superseded=row_count;
 update private.notification_outbox set status='dead_letter',last_error_category='expired',next_attempt_at=null,updated_at=pg_catalog.now()
 where status in ('pending','retry') and created_at<=pg_catalog.now()-interval '15 minutes';
 get diagnostics expired=row_count;
 return superseded+expired;
end $$;

-- The backlog queued while nothing delivered is stale: retire it now so it is never sent late.
select private.harbor_retire_notifications();

-- 2. Screen time in the activity status.
-- The child phone sends today's time on screen as it counts it for its limits (date, total, up to 50 apps' minutes),
-- so the parent sees the same numbers the phone enforces. App names and minutes only.
alter table private.device_activity_status add column screen_time jsonb
 check (screen_time is null or (jsonb_typeof(screen_time)='object' and pg_catalog.octet_length(screen_time::text)<=8192));

create or replace function private.harbor_store_device_activity(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text,cleared boolean,p jsonb)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare old private.device_activity_checkpoints%rowtype; prior private.device_activity_status%rowtype; had_prior boolean; stamp timestamptz; now_on boolean;
begin
 perform private.harbor_usage_binding(d,f,c,a);
 if e is null or q is null or q<1 or q>9007199254740991 or h is null or h !~ '^[0-9a-f]{64}$' then raise exception 'invalid activity checkpoint' using errcode='22023';end if;
 select * into old from private.device_activity_checkpoints where device_id=d;
 if found then
  if q<old.sequence then raise exception 'STALE_VERSION' using errcode='P0001';end if;
  if q=old.sequence then
   if h<>old.payload_hash or e<>old.epoch_id or cleared<>old.is_clear then raise exception 'IDEMPOTENCY_CONFLICT' using errcode='P0001';end if;
   return query select true,old.sequence,old.received_at;return;
  end if;
 end if;
 stamp:=pg_catalog.clock_timestamp();
 insert into private.device_activity_checkpoints values(d,e,q,h,cleared,stamp)
 on conflict(device_id) do update set epoch_id=excluded.epoch_id,sequence=excluded.sequence,payload_hash=excluded.payload_hash,is_clear=excluded.is_clear,received_at=excluded.received_at;
 if cleared then
  delete from private.device_activity_events where device_id=d;
  delete from private.device_activity_status where device_id=d;
 else
  select * into prior from private.device_activity_status where device_id=d;
  had_prior:=found;
  now_on:=(p->'status'->>'controlsOn')::boolean;
  insert into private.device_activity_status(device_id,controls_on,policy_version,observed_at,received_at,expires_at,screen_time)
  values(d,now_on,(p->'status'->>'policyVersion')::bigint,(p->>'observedAt')::timestamptz,stamp,stamp+interval '30 days',p->'status'->'screenTime')
  on conflict(device_id) do update set controls_on=excluded.controls_on,policy_version=excluded.policy_version,observed_at=excluded.observed_at,
   received_at=excluded.received_at,expires_at=excluded.expires_at,screen_time=excluded.screen_time;
  insert into private.device_activity_events(device_id,event_id,kind,at,end_at,package_name,app_label,reason,received_at,expires_at)
  select d,x.id,x.kind,x."at"::timestamptz,x."endAt"::timestamptz,x."packageName",x."appLabel",x.reason,stamp,x."at"::timestamptz+interval '30 days'
  from jsonb_to_recordset(p->'events') as x(id uuid,kind text,"at" text,"endAt" text,"packageName" text,"appLabel" text,reason text)
  on conflict(device_id,event_id) do nothing;
  -- Bound storage per device: keep the newest 5000 events.
  delete from private.device_activity_events x where x.device_id=d and (x.device_id,x.event_id) not in
   (select y.device_id,y.event_id from private.device_activity_events y where y.device_id=d order by y.at desc,y.event_id desc limit 5000);
  -- Tell parents when enforcement stops: on the first report that says controls are off after they were on (or never reported).
  if not now_on and (not had_prior or prior.controls_on) then perform private.harbor_enqueue_controls_off_push(d,e,q);end if;
 end if;
 return query select true,q,stamp;
end $$;

drop function private.harbor_get_device_activity(uuid,uuid,uuid,timestamptz,timestamptz);
-- Parent read of one time window, oldest first. A session that started before the window but ran into it is included.
create function private.harbor_get_device_activity(actor uuid,sid uuid,d uuid,p_from timestamptz,p_to timestamptz)
returns table(state text,controls_on boolean,policy_version bigint,screen_time jsonb,observed_at timestamptz,received_at timestamptz,events jsonb,truncated boolean)
language plpgsql security invoker set search_path='' as $$
declare st private.device_activity_status%rowtype; rows_ jsonb; n integer; stamp timestamptz:=pg_catalog.clock_timestamp();
begin
 if p_from is null or p_to is null or p_from>=p_to or p_to-p_from>interval '48 hours' then raise exception 'invalid activity window' using errcode='22023';end if;
 perform private.harbor_checkin_parent_check(actor,sid,d);
 select * into st from private.device_activity_status where device_id=d;
 if found then
  if st.expires_at<=stamp then return query select 'expired'::text,null::boolean,null::bigint,null::jsonb,null::timestamptz,null::timestamptz,'[]'::jsonb,false;return;end if;
  select count(*) into n from (select 1 from private.device_activity_events g where g.device_id=d and g.expires_at>stamp
   and coalesce(g.end_at,g.at)>=p_from and g.at<p_to limit 501) z;
  select coalesce(jsonb_agg(jsonb_build_object('id',g.event_id,'kind',g.kind,
    'at',to_char(g.at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    'endAt',case when g.end_at is null then null else to_char(g.end_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"') end,
    'packageName',g.package_name,'appLabel',g.app_label,'reason',g.reason) order by g.at,g.event_id),'[]'::jsonb) into rows_
  from (select * from private.device_activity_events where device_id=d and expires_at>stamp and coalesce(end_at,at)>=p_from and at<p_to order by at,event_id limit 500) g;
  return query select 'available'::text,st.controls_on,st.policy_version,st.screen_time,st.observed_at,st.received_at,rows_,n>500;
 else
  if exists(select 1 from private.device_activity_checkpoints x where x.device_id=d and not x.is_clear and x.received_at<=stamp-interval '30 days') then
   return query select 'expired'::text,null::boolean,null::bigint,null::jsonb,null::timestamptz,null::timestamptz,'[]'::jsonb,false;
  else return query select 'none'::text,null::boolean,null::bigint,null::jsonb,null::timestamptz,null::timestamptz,'[]'::jsonb,false;end if;
 end if;
end $$;

revoke all on function private.harbor_due_notifications(integer),private.harbor_retire_notifications(),
 private.harbor_store_device_activity(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),
 private.harbor_get_device_activity(uuid,uuid,uuid,timestamptz,timestamptz) from public,anon,authenticated;
grant execute on function private.harbor_due_notifications(integer),private.harbor_retire_notifications(),
 private.harbor_store_device_activity(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),
 private.harbor_get_device_activity(uuid,uuid,uuid,timestamptz,timestamptz) to service_role;
