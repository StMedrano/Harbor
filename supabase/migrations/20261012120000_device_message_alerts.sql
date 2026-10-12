-- Message-concern alerts are private. A row says THAT something was flagged (category, app label, time);
-- no message text, sender, number or excerpt exists in any column. Checkpoints survive clear, expiry and
-- revocation (same model as usage and location reporting).
create table private.device_message_alerts (
 device_id uuid not null references public.devices_public(id) on delete cascade,
 alert_id uuid not null,
 app_label text not null check(char_length(app_label) between 1 and 64 and app_label !~ '[[:cntrl:]]'),
 category text not null check(category in ('bullying','self_harm','adult_content','drugs_alcohol','violence_threat','unknown_adult_contact')),
 rule_set_version integer not null check(rule_set_version between 1 and 1000000),
 alert_at timestamptz not null,
 saved_contact boolean not null,
 received_at timestamptz not null, expires_at timestamptz not null,
 primary key(device_id,alert_id)
);
create index device_message_alert_page_idx on private.device_message_alerts(device_id,alert_at desc,alert_id desc);
create index device_message_alert_expiry_idx on private.device_message_alerts(expires_at);
create table private.device_alert_status (
 device_id uuid primary key references public.devices_public(id) on delete cascade,
 notification_access boolean not null, sms_permission boolean not null,
 observed_at timestamptz not null, received_at timestamptz not null, expires_at timestamptz not null
);
create index device_alert_status_expiry_idx on private.device_alert_status(expires_at);
create table private.device_alert_checkpoints (
 device_id uuid primary key references public.devices_public(id) on delete cascade,
 epoch_id uuid not null, sequence bigint not null check(sequence between 1 and 9007199254740991),
 payload_hash text not null check(payload_hash ~ '^[0-9a-f]{64}$'),
 is_clear boolean not null, received_at timestamptz not null
);
alter table private.device_message_alerts enable row level security;
alter table private.device_alert_status enable row level security;
alter table private.device_alert_checkpoints enable row level security;
revoke all on private.device_message_alerts,private.device_alert_status,private.device_alert_checkpoints from public,anon,authenticated;
grant select,insert,update,delete on private.device_message_alerts,private.device_alert_status,private.device_alert_checkpoints to service_role;

-- Parent push for a new alert carries only a route (no alert content). At most one push per device per five minutes.
create function private.harbor_enqueue_alert_push(d uuid,e uuid,q bigint) returns void
language plpgsql security definer set search_path='' as $$
declare dev public.devices_public%rowtype; key text; route jsonb; r record;
begin
 select * into dev from public.devices_public where id=d;
 if not found or dev.status='revoked' or dev.revoked_at is not null then return;end if;
 if exists(select 1 from private.notification_outbox o where o.event_key like 'device-alert:'||d::text||':%' and o.created_at>pg_catalog.clock_timestamp()-interval '5 minutes') then return;end if;
 key:='device-alert:'||d::text||':'||e::text||':'||q::text;
 route:=jsonb_build_object('version',1,'kind','device.alert.created','familyId',dev.family_id,'childId',dev.child_id,'deviceId',dev.id,'resourceId',pg_catalog.gen_random_uuid());
 for r in
  select g.id,g.user_id from private.parent_fcm_registrations g
  join public.family_members fm on fm.user_id=g.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and g.active and private.harbor_parent_session_active(g.user_id,g.session_id)
 loop
  perform private.harbor_enqueue_notification(key,'fcm',jsonb_build_object('parentFcmRegistrationId',r.id,'userId',r.user_id),route);
 end loop;
end $$;

-- Binding/revocation fence is shared with usage reporting: private.harbor_usage_binding checks family, child, non-revoked device and the anonymous child identity under row locks.
create function private.harbor_store_device_alerts(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text,cleared boolean,p jsonb)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare old private.device_alert_checkpoints%rowtype; stamp timestamptz; fresh integer;
begin
 perform private.harbor_usage_binding(d,f,c,a);
 if e is null or q is null or q<1 or q>9007199254740991 or h is null or h !~ '^[0-9a-f]{64}$' then raise exception 'invalid alert checkpoint' using errcode='22023';end if;
 select * into old from private.device_alert_checkpoints where device_id=d;
 if found then
  if q<old.sequence then raise exception 'STALE_VERSION' using errcode='P0001';end if;
  if q=old.sequence then
   if h<>old.payload_hash or e<>old.epoch_id or cleared<>old.is_clear then raise exception 'IDEMPOTENCY_CONFLICT' using errcode='P0001';end if;
   return query select true,old.sequence,old.received_at;return;
  end if;
 end if;
 stamp:=pg_catalog.clock_timestamp();
 insert into private.device_alert_checkpoints values(d,e,q,h,cleared,stamp)
 on conflict(device_id) do update set epoch_id=excluded.epoch_id,sequence=excluded.sequence,payload_hash=excluded.payload_hash,is_clear=excluded.is_clear,received_at=excluded.received_at;
 if cleared then
  delete from private.device_message_alerts where device_id=d;
  delete from private.device_alert_status where device_id=d;
 else
  insert into private.device_alert_status values(d,(p->>'notificationAccess')::boolean,(p->>'smsPermission')::boolean,(p->>'observedAt')::timestamptz,stamp,stamp+interval '30 days')
  on conflict(device_id) do update set notification_access=excluded.notification_access,sms_permission=excluded.sms_permission,observed_at=excluded.observed_at,received_at=excluded.received_at,expires_at=excluded.expires_at;
  with ins as (
   insert into private.device_message_alerts(device_id,alert_id,app_label,category,rule_set_version,alert_at,saved_contact,received_at,expires_at)
   select d,x.id,x."appLabel",x.category,x."ruleSetVersion",x."at"::timestamptz,x."savedContact",stamp,x."at"::timestamptz+interval '30 days'
   from jsonb_to_recordset(p->'alerts') as x(id uuid,"appLabel" text,category text,"ruleSetVersion" integer,"at" text,"savedContact" boolean)
   on conflict(device_id,alert_id) do nothing returning 1)
  select count(*) into fresh from ins;
  -- Bound storage per device: keep the newest 1000 alerts.
  delete from private.device_message_alerts x where x.device_id=d and (x.device_id,x.alert_id) not in
   (select y.device_id,y.alert_id from private.device_message_alerts y where y.device_id=d order by y.alert_at desc,y.alert_id desc limit 1000);
  if fresh>0 then perform private.harbor_enqueue_alert_push(d,e,q);end if;
 end if;
 return query select true,q,stamp;
end $$;

create function private.harbor_write_device_alerts(d uuid,f uuid,c uuid,a uuid,p jsonb,h text)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
begin
 if p is null or jsonb_typeof(p)<>'object' or p->>'version'<>'1' or not(p ?& array['epochId','sequence','observedAt','notificationAccess','smsPermission','alerts'])
 or jsonb_typeof(p->'alerts')<>'array' or jsonb_array_length(p->'alerts')>50
 or jsonb_typeof(p->'notificationAccess')<>'boolean' or jsonb_typeof(p->'smsPermission')<>'boolean'
 or pg_catalog.octet_length(p::text)>131072 then raise exception 'invalid alert report' using errcode='22023';end if;
 return query select * from private.harbor_store_device_alerts(d,f,c,a,(p->>'epochId')::uuid,(p->>'sequence')::bigint,h,false,p);
end $$;
create function private.harbor_clear_device_alerts(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language sql security invoker set search_path='' as $$
 select * from private.harbor_store_device_alerts(d,f,c,a,e,q,h,true,null)
$$;
create function private.harbor_get_alert_checkpoint(d uuid,f uuid,c uuid,a uuid)
returns table(sequence bigint,epoch_id uuid)
language plpgsql security invoker set search_path='' as $$
begin
 perform private.harbor_usage_binding(d,f,c,a);
 return query select x.sequence,x.epoch_id from private.device_alert_checkpoints x where x.device_id=d;
 if not found then return query select 0::bigint,null::uuid;end if;
end $$;

-- Parent read: current parent session, active owner/parent membership, non-revoked device.
-- Newest first. `before` is inclusive ("at or before"); the cursor is the first alert NOT on the page, so ties are never skipped (clients dedupe by id).
create function private.harbor_get_device_alerts(actor uuid,sid uuid,d uuid,p_before timestamptz,p_limit integer)
returns table(state text,notification_access boolean,sms_permission boolean,observed_at timestamptz,received_at timestamptz,alerts jsonb,next_before timestamptz)
language plpgsql security invoker set search_path='' as $$
declare fam uuid; dev public.devices_public%rowtype; st private.device_alert_status%rowtype; rows_ jsonb; n integer; cursor_ timestamptz;
begin
 if p_limit is null or p_limit<1 or p_limit>50 then raise exception 'invalid alert page' using errcode='22023';end if;
 perform 1 from auth.sessions where id=sid and user_id=actor for share;
 if not found or not private.harbor_parent_session_active(actor,sid) then raise exception 'current parent session required' using errcode='42501';end if;
 select family_id into fam from public.devices_public where id=d;
 perform 1 from public.family_members where family_id=fam and user_id=actor and status='active' and role in ('owner','parent') for share;
 if not found then raise exception 'parent membership required' using errcode='42501';end if;
 select * into dev from public.devices_public where id=d and family_id=fam for share;
 if not found or dev.status='revoked' or dev.revoked_at is not null then raise exception 'DEVICE_REVOKED' using errcode='42501';end if;
 select * into st from private.device_alert_status where device_id=d;
 if found then
  if st.expires_at<=pg_catalog.clock_timestamp() then return query select 'expired'::text,null::boolean,null::boolean,null::timestamptz,null::timestamptz,'[]'::jsonb,null::timestamptz;return;end if;
  select count(*) into n from (select 1 from private.device_message_alerts g where g.device_id=d and g.expires_at>pg_catalog.clock_timestamp() and (p_before is null or g.alert_at<=p_before) limit p_limit+1) z;
  select coalesce(jsonb_agg(jsonb_build_object('id',g.alert_id,'appLabel',g.app_label,'category',g.category,'ruleSetVersion',g.rule_set_version,
    'at',to_char(g.alert_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'savedContact',g.saved_contact) order by g.alert_at desc,g.alert_id desc),'[]'::jsonb) into rows_
   from (select * from private.device_message_alerts where device_id=d and expires_at>pg_catalog.clock_timestamp() and (p_before is null or alert_at<=p_before) order by alert_at desc,alert_id desc limit p_limit) g;
  cursor_:=null;
  if n>p_limit then
   select g.alert_at into cursor_ from private.device_message_alerts g where g.device_id=d and g.expires_at>pg_catalog.clock_timestamp() and (p_before is null or g.alert_at<=p_before)
    order by g.alert_at desc,g.alert_id desc offset p_limit limit 1;
  end if;
  return query select 'available'::text,st.notification_access,st.sms_permission,st.observed_at,st.received_at,rows_,cursor_;
 else
  if exists(select 1 from private.device_alert_checkpoints x where x.device_id=d and not x.is_clear and x.received_at<=pg_catalog.clock_timestamp()-interval '30 days') then return query select 'expired'::text,null::boolean,null::boolean,null::timestamptz,null::timestamptz,'[]'::jsonb,null::timestamptz;
  else return query select 'none'::text,null::boolean,null::boolean,null::timestamptz,null::timestamptz,'[]'::jsonb,null::timestamptz;end if;
 end if;
end $$;

create function private.harbor_delete_revoked_alerts() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if new.revoked_at is not null or new.status='revoked' then
  delete from private.device_message_alerts where device_id=new.id;
  delete from private.device_alert_status where device_id=new.id;
 end if;
 return new;
end $$;
create trigger device_alert_revoke_cleanup after update of revoked_at,status on public.devices_public for each row execute function private.harbor_delete_revoked_alerts();
create function private.harbor_purge_device_alerts() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint; m bigint;
begin
 delete from private.device_message_alerts where expires_at<=pg_catalog.clock_timestamp();get diagnostics n=row_count;
 delete from private.device_alert_status where expires_at<=pg_catalog.clock_timestamp();get diagnostics m=row_count;
 return n+m;
end $$;
revoke all on function private.harbor_enqueue_alert_push(uuid,uuid,bigint),private.harbor_store_device_alerts(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),private.harbor_write_device_alerts(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_clear_device_alerts(uuid,uuid,uuid,uuid,uuid,bigint,text),private.harbor_get_alert_checkpoint(uuid,uuid,uuid,uuid),private.harbor_get_device_alerts(uuid,uuid,uuid,timestamptz,integer),private.harbor_delete_revoked_alerts(),private.harbor_purge_device_alerts() from public,anon,authenticated;
grant execute on function private.harbor_enqueue_alert_push(uuid,uuid,bigint),private.harbor_store_device_alerts(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),private.harbor_write_device_alerts(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_clear_device_alerts(uuid,uuid,uuid,uuid,uuid,bigint,text),private.harbor_get_alert_checkpoint(uuid,uuid,uuid,uuid),private.harbor_get_device_alerts(uuid,uuid,uuid,timestamptz,integer),private.harbor_delete_revoked_alerts(),private.harbor_purge_device_alerts() to service_role;
-- Runs as the migration owner; no client role can schedule or invoke cleanup.
select cron.schedule('harbor-alert-retention','41 3 * * *','select private.harbor_purge_device_alerts();');
