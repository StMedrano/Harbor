-- App controls and device activity.
-- Controls themselves live in private.device_desired_state.desired_state->'controls' (written by update-device-state);
-- this migration adds the parent read of them, and the private activity timeline a child phone reports.
-- An activity row says which app was used or what a control did, and when. No column holds screen content, a window
-- title, a web address or any text. Checkpoints survive clear, expiry and revocation (same model as alerts).
create table private.device_activity_events (
 device_id uuid not null references public.devices_public(id) on delete cascade,
 event_id uuid not null,
 kind text not null check(kind in ('app_session','blocked_open','limit_reached','pause_started','pause_ended','bedtime_started','bedtime_ended','protection_off','protection_on')),
 at timestamptz not null,
 end_at timestamptz,
 package_name text check(package_name ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$' and char_length(package_name)<=255),
 app_label text check(char_length(app_label) between 1 and 64 and app_label !~ '[[:cntrl:]]'),
 reason text check(reason in ('paused','bedtime','blocked_app','limit_reached')),
 received_at timestamptz not null, expires_at timestamptz not null,
 primary key(device_id,event_id),
 check((kind in ('app_session','blocked_open','limit_reached')) = (package_name is not null and app_label is not null)),
 check((kind='app_session') = (end_at is not null)),
 check(end_at is null or end_at-at between interval '60 seconds' and interval '24 hours'),
 check((kind='blocked_open') = (reason is not null))
);
create index device_activity_page_idx on private.device_activity_events(device_id,at,event_id);
create index device_activity_expiry_idx on private.device_activity_events(expires_at);
create table private.device_activity_status (
 device_id uuid primary key references public.devices_public(id) on delete cascade,
 controls_on boolean not null, policy_version bigint not null check(policy_version between 0 and 9007199254740991),
 observed_at timestamptz not null, received_at timestamptz not null, expires_at timestamptz not null
);
create index device_activity_status_expiry_idx on private.device_activity_status(expires_at);
create table private.device_activity_checkpoints (
 device_id uuid primary key references public.devices_public(id) on delete cascade,
 epoch_id uuid not null, sequence bigint not null check(sequence between 1 and 9007199254740991),
 payload_hash text not null check(payload_hash ~ '^[0-9a-f]{64}$'),
 is_clear boolean not null, received_at timestamptz not null
);
alter table private.device_activity_events enable row level security;
alter table private.device_activity_status enable row level security;
alter table private.device_activity_checkpoints enable row level security;
revoke all on private.device_activity_events,private.device_activity_status,private.device_activity_checkpoints from public,anon,authenticated;
grant select,insert,update,delete on private.device_activity_events,private.device_activity_status,private.device_activity_checkpoints to service_role;

-- Parent push when app controls stop being enforced on a phone. Route only, no content; at most one per device per 30 minutes.
create function private.harbor_enqueue_controls_off_push(d uuid,e uuid,q bigint) returns void
language plpgsql security definer set search_path='' as $$
declare dev public.devices_public%rowtype; key text; route jsonb; r record;
begin
 select * into dev from public.devices_public where id=d;
 if not found or dev.status='revoked' or dev.revoked_at is not null then return;end if;
 if exists(select 1 from private.notification_outbox o where o.event_key like 'device-controls-off:'||d::text||':%' and o.created_at>pg_catalog.clock_timestamp()-interval '30 minutes') then return;end if;
 key:='device-controls-off:'||d::text||':'||e::text||':'||q::text;
 route:=jsonb_build_object('version',1,'kind','device.controls.off','familyId',dev.family_id,'childId',dev.child_id,'deviceId',dev.id,'resourceId',pg_catalog.gen_random_uuid());
 for r in
  select g.id,g.user_id from private.parent_fcm_registrations g
  join public.family_members fm on fm.user_id=g.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and g.active and private.harbor_parent_session_active(g.user_id,g.session_id)
 loop
  perform private.harbor_enqueue_notification(key,'fcm',jsonb_build_object('parentFcmRegistrationId',r.id,'userId',r.user_id),route);
 end loop;
end $$;

-- Binding/revocation fence shared with usage reporting (private.harbor_usage_binding).
create function private.harbor_store_device_activity(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text,cleared boolean,p jsonb)
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
  insert into private.device_activity_status values(d,now_on,(p->'status'->>'policyVersion')::bigint,(p->>'observedAt')::timestamptz,stamp,stamp+interval '30 days')
  on conflict(device_id) do update set controls_on=excluded.controls_on,policy_version=excluded.policy_version,observed_at=excluded.observed_at,received_at=excluded.received_at,expires_at=excluded.expires_at;
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

create function private.harbor_write_device_activity(d uuid,f uuid,c uuid,a uuid,p jsonb,h text)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
begin
 if p is null or jsonb_typeof(p)<>'object' or p->>'version'<>'1' or not(p ?& array['epochId','sequence','observedAt','status','events'])
 or jsonb_typeof(p->'events')<>'array' or jsonb_array_length(p->'events')>100
 or jsonb_typeof(p->'status')<>'object' or jsonb_typeof(p->'status'->'controlsOn')<>'boolean' or jsonb_typeof(p->'status'->'policyVersion')<>'number'
 or pg_catalog.octet_length(p::text)>131072 then raise exception 'invalid activity report' using errcode='22023';end if;
 return query select * from private.harbor_store_device_activity(d,f,c,a,(p->>'epochId')::uuid,(p->>'sequence')::bigint,h,false,p);
end $$;
create function private.harbor_clear_device_activity(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language sql security invoker set search_path='' as $$
 select * from private.harbor_store_device_activity(d,f,c,a,e,q,h,true,null)
$$;
create function private.harbor_get_activity_checkpoint(d uuid,f uuid,c uuid,a uuid)
returns table(sequence bigint,epoch_id uuid)
language plpgsql security invoker set search_path='' as $$
begin
 perform private.harbor_usage_binding(d,f,c,a);
 return query select x.sequence,x.epoch_id from private.device_activity_checkpoints x where x.device_id=d;
 if not found then return query select 0::bigint,null::uuid;end if;
end $$;

-- Parent read of one time window, oldest first. A session that started before the window but ran into it is included.
create function private.harbor_get_device_activity(actor uuid,sid uuid,d uuid,p_from timestamptz,p_to timestamptz)
returns table(state text,controls_on boolean,policy_version bigint,observed_at timestamptz,received_at timestamptz,events jsonb,truncated boolean)
language plpgsql security invoker set search_path='' as $$
declare st private.device_activity_status%rowtype; rows_ jsonb; n integer; stamp timestamptz:=pg_catalog.clock_timestamp();
begin
 if p_from is null or p_to is null or p_from>=p_to or p_to-p_from>interval '48 hours' then raise exception 'invalid activity window' using errcode='22023';end if;
 perform private.harbor_checkin_parent_check(actor,sid,d);
 select * into st from private.device_activity_status where device_id=d;
 if found then
  if st.expires_at<=stamp then return query select 'expired'::text,null::boolean,null::bigint,null::timestamptz,null::timestamptz,'[]'::jsonb,false;return;end if;
  select count(*) into n from (select 1 from private.device_activity_events g where g.device_id=d and g.expires_at>stamp
   and coalesce(g.end_at,g.at)>=p_from and g.at<p_to limit 501) z;
  select coalesce(jsonb_agg(jsonb_build_object('id',g.event_id,'kind',g.kind,
    'at',to_char(g.at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    'endAt',case when g.end_at is null then null else to_char(g.end_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"') end,
    'packageName',g.package_name,'appLabel',g.app_label,'reason',g.reason) order by g.at,g.event_id),'[]'::jsonb) into rows_
  from (select * from private.device_activity_events where device_id=d and expires_at>stamp and coalesce(end_at,at)>=p_from and at<p_to order by at,event_id limit 500) g;
  return query select 'available'::text,st.controls_on,st.policy_version,st.observed_at,st.received_at,rows_,n>500;
 else
  if exists(select 1 from private.device_activity_checkpoints x where x.device_id=d and not x.is_clear and x.received_at<=stamp-interval '30 days') then
   return query select 'expired'::text,null::boolean,null::bigint,null::timestamptz,null::timestamptz,'[]'::jsonb,false;
  else return query select 'none'::text,null::boolean,null::bigint,null::timestamptz,null::timestamptz,'[]'::jsonb,false;end if;
 end if;
end $$;

-- Parent read of the controls a device should be enforcing, and whether the phone has applied that version.
create function private.harbor_get_device_controls(actor uuid,sid uuid,d uuid)
returns table(controls jsonb,desired_state_version bigint,acknowledged_version bigint)
language plpgsql security invoker set search_path='' as $$
begin
 perform private.harbor_checkin_parent_check(actor,sid,d);
 return query select s.desired_state->'controls',s.desired_state_version,s.acknowledged_version from private.device_desired_state s where s.device_id=d;
 if not found then return query select null::jsonb,0::bigint,0::bigint;end if;
end $$;

create function private.harbor_delete_revoked_activity() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if new.revoked_at is not null or new.status='revoked' then
  delete from private.device_activity_events where device_id=new.id;
  delete from private.device_activity_status where device_id=new.id;
 end if;
 return new;
end $$;
create trigger device_activity_revoke_cleanup after update of revoked_at,status on public.devices_public for each row execute function private.harbor_delete_revoked_activity();
create function private.harbor_purge_device_activity() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint; m bigint;
begin
 delete from private.device_activity_events where expires_at<=pg_catalog.clock_timestamp();get diagnostics n=row_count;
 delete from private.device_activity_status where expires_at<=pg_catalog.clock_timestamp();get diagnostics m=row_count;
 return n+m;
end $$;
revoke all on function private.harbor_enqueue_controls_off_push(uuid,uuid,bigint),private.harbor_store_device_activity(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),private.harbor_write_device_activity(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_clear_device_activity(uuid,uuid,uuid,uuid,uuid,bigint,text),private.harbor_get_activity_checkpoint(uuid,uuid,uuid,uuid),private.harbor_get_device_activity(uuid,uuid,uuid,timestamptz,timestamptz),private.harbor_get_device_controls(uuid,uuid,uuid),private.harbor_delete_revoked_activity(),private.harbor_purge_device_activity() from public,anon,authenticated;
grant execute on function private.harbor_enqueue_controls_off_push(uuid,uuid,bigint),private.harbor_store_device_activity(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),private.harbor_write_device_activity(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_clear_device_activity(uuid,uuid,uuid,uuid,uuid,bigint,text),private.harbor_get_activity_checkpoint(uuid,uuid,uuid,uuid),private.harbor_get_device_activity(uuid,uuid,uuid,timestamptz,timestamptz),private.harbor_get_device_controls(uuid,uuid,uuid),private.harbor_delete_revoked_activity(),private.harbor_purge_device_activity() to service_role;
-- Runs as the migration owner; no client role can schedule or invoke cleanup.
select cron.schedule('harbor-activity-retention','47 3 * * *','select private.harbor_purge_device_activity();');
