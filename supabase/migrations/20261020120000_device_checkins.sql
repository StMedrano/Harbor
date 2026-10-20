-- Check-ins: a parent asks, the child answers with a preset and, when available, a recent location fix. No free text.
create table private.device_checkins (
 device_id uuid not null references public.devices_public(id) on delete cascade,
 request_id uuid not null default pg_catalog.gen_random_uuid(),
 requested_by uuid not null,
 requested_at timestamptz not null,
 expires_at timestamptz not null,
 closed_at timestamptz,
 replied_at timestamptz,
 preset text check(preset in ('ok','late')),
 fix_at timestamptz,
 latitude double precision check(latitude between -90 and 90),
 longitude double precision check(longitude between -180 and 180),
 accuracy_m integer check(accuracy_m between 0 and 100000),
 reply_hash text check(reply_hash ~ '^[0-9a-f]{64}$'),
 received_at timestamptz,
 purge_at timestamptz not null,
 primary key(device_id,request_id),
 check((replied_at is null)=(preset is null) and (replied_at is null)=(reply_hash is null) and (replied_at is null)=(received_at is null)),
 check((fix_at is null)=(latitude is null) and (latitude is null)=(longitude is null) and (latitude is null)=(accuracy_m is null)),
 check(replied_at is not null or fix_at is null)
);
-- At most one open request per device; expired ones are closed before a new one is made.
create unique index device_checkins_one_open on private.device_checkins(device_id) where replied_at is null and closed_at is null;
create index device_checkins_page_idx on private.device_checkins(device_id,requested_at desc);
create index device_checkins_purge_idx on private.device_checkins(purge_at);
alter table private.device_checkins enable row level security;
revoke all on private.device_checkins from public,anon,authenticated;
grant select,insert,update,delete on private.device_checkins to service_role;

-- The child's push carries only a route (no content).
create function private.harbor_enqueue_checkin_push(d uuid,r uuid) returns void
language plpgsql security definer set search_path='' as $$
declare dev public.devices_public%rowtype;
begin
 select * into dev from public.devices_public where id=d;
 if not found or dev.status='revoked' or dev.revoked_at is not null then return;end if;
 perform private.harbor_enqueue_notification('checkin:'||r::text,'fcm',jsonb_build_object('deviceId',dev.id),
  jsonb_build_object('version',1,'kind','device.checkin.requested','familyId',dev.family_id,'childId',dev.child_id,'deviceId',dev.id,'resourceId',r));
end $$;

-- Shared parent prelude: current parent session, active owner/parent membership, non-revoked device.
create function private.harbor_checkin_parent_check(actor uuid,sid uuid,d uuid) returns void
language plpgsql security invoker set search_path='' as $$
declare fam uuid; dev public.devices_public%rowtype;
begin
 perform 1 from auth.sessions where id=sid and user_id=actor for share;
 if not found or not private.harbor_parent_session_active(actor,sid) then raise exception 'current parent session required' using errcode='42501';end if;
 select family_id into fam from public.devices_public where id=d;
 perform 1 from public.family_members where family_id=fam and user_id=actor and status='active' and role in ('owner','parent') for share;
 if not found then raise exception 'parent membership required' using errcode='42501';end if;
 select * into dev from public.devices_public where id=d and family_id=fam for share;
 if not found or dev.status='revoked' or dev.revoked_at is not null then raise exception 'DEVICE_REVOKED' using errcode='42501';end if;
end $$;

create function private.harbor_request_device_checkin(actor uuid,sid uuid,d uuid)
returns table(request_id uuid,requested_at timestamptz,expires_at timestamptz,created boolean)
language plpgsql security invoker set search_path='' as $$
declare open_ private.device_checkins%rowtype; stamp timestamptz; fresh uuid;
begin
 perform private.harbor_checkin_parent_check(actor,sid,d);
 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('checkin:'||d::text,0));
 stamp:=pg_catalog.clock_timestamp();
 update private.device_checkins x set closed_at=stamp where x.device_id=d and x.replied_at is null and x.closed_at is null and x.expires_at<=stamp;
 select * into open_ from private.device_checkins x where x.device_id=d and x.replied_at is null and x.closed_at is null;
 if found then return query select open_.request_id,open_.requested_at,open_.expires_at,false;return;end if;
 fresh:=pg_catalog.gen_random_uuid();
 insert into private.device_checkins(device_id,request_id,requested_by,requested_at,expires_at,purge_at)
 values(d,fresh,actor,stamp,stamp+interval '24 hours',stamp+interval '30 days');
 perform private.harbor_enqueue_checkin_push(d,fresh);
 return query select fresh,stamp,stamp+interval '24 hours',true;
end $$;

-- Child read: its own open request and the last ten answers. Binding is the shared revocation fence.
create function private.harbor_get_checkin_state(d uuid,f uuid,c uuid,a uuid)
returns table(pending jsonb,recent jsonb)
language plpgsql security invoker set search_path='' as $$
declare p jsonb; r jsonb; stamp timestamptz:=pg_catalog.clock_timestamp();
begin
 perform private.harbor_usage_binding(d,f,c,a);
 select jsonb_build_object('requestId',x.request_id,'requestedAt',to_char(x.requested_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'expiresAt',to_char(x.expires_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'))
  into p from private.device_checkins x where x.device_id=d and x.replied_at is null and x.closed_at is null and x.expires_at>stamp;
 select coalesce(jsonb_agg(jsonb_build_object('requestId',g.request_id,'requestedAt',to_char(g.requested_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),
   'repliedAt',to_char(g.replied_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'preset',g.preset,'located',g.fix_at is not null) order by g.replied_at desc),'[]'::jsonb) into r
  from (select * from private.device_checkins where device_id=d and replied_at is not null order by replied_at desc limit 10) g;
 return query select p,r;
end $$;

create function private.harbor_reply_device_checkin(d uuid,f uuid,c uuid,a uuid,p jsonb,h text)
returns table(confirmed boolean,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare row_ private.device_checkins%rowtype; stamp timestamptz; rid uuid; fx jsonb;
begin
 perform private.harbor_usage_binding(d,f,c,a);
 if p is null or jsonb_typeof(p)<>'object' or p->>'version'<>'1' or not(p ?& array['requestId','preset','fix'])
  or p->>'preset' not in ('ok','late') or h is null or h !~ '^[0-9a-f]{64}$'
  or pg_catalog.octet_length(p::text)>4096 then raise exception 'invalid check-in reply' using errcode='22023';end if;
 rid:=(p->>'requestId')::uuid; fx:=p->'fix';
 if jsonb_typeof(fx) not in ('object','null') then raise exception 'invalid check-in reply' using errcode='22023';end if;
 select * into row_ from private.device_checkins x where x.device_id=d and x.request_id=rid for update;
 if not found then raise exception 'check-in not found' using errcode='22023';end if;
 if row_.replied_at is not null then
  if row_.reply_hash<>h then raise exception 'IDEMPOTENCY_CONFLICT' using errcode='P0001';end if;
  return query select true,row_.received_at;return;
 end if;
 stamp:=pg_catalog.clock_timestamp();
 if row_.closed_at is not null or row_.expires_at<=stamp then raise exception 'check-in closed' using errcode='22023';end if;
 if jsonb_typeof(fx)='object' then
  if (fx->>'at')::timestamptz>stamp+interval '5 minutes' or (fx->>'at')::timestamptz<stamp-interval '1 hour' then raise exception 'stale check-in location' using errcode='22023';end if;
  update private.device_checkins x set replied_at=stamp,preset=p->>'preset',reply_hash=h,received_at=stamp,
   fix_at=(fx->>'at')::timestamptz,latitude=(fx->>'latitude')::double precision,longitude=(fx->>'longitude')::double precision,accuracy_m=(fx->>'accuracyM')::integer
   where x.device_id=d and x.request_id=rid;
 else
  update private.device_checkins x set replied_at=stamp,preset=p->>'preset',reply_hash=h,received_at=stamp where x.device_id=d and x.request_id=rid;
 end if;
 return query select true,stamp;
end $$;

create function private.harbor_get_device_checkins(actor uuid,sid uuid,d uuid)
returns table(checkins jsonb)
language plpgsql security invoker set search_path='' as $$
declare stamp timestamptz:=pg_catalog.clock_timestamp();
begin
 perform private.harbor_checkin_parent_check(actor,sid,d);
 return query select coalesce(jsonb_agg(jsonb_build_object(
   'requestId',g.request_id,
   'requestedAt',to_char(g.requested_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),
   'expiresAt',to_char(g.expires_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),
   'status',case when g.replied_at is not null then 'answered' when g.closed_at is not null or g.expires_at<=stamp then 'expired' else 'pending' end,
   'reply',case when g.replied_at is null then null else jsonb_build_object('repliedAt',to_char(g.replied_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'preset',g.preset,
     'fix',case when g.fix_at is null then null else jsonb_build_object('at',to_char(g.fix_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'latitude',g.latitude,'longitude',g.longitude,'accuracyM',g.accuracy_m) end) end
  ) order by g.requested_at desc),'[]'::jsonb)
  from (select * from private.device_checkins where device_id=d and purge_at>stamp order by requested_at desc limit 20) g;
end $$;

create function private.harbor_delete_revoked_checkins() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if new.revoked_at is not null or new.status='revoked' then delete from private.device_checkins where device_id=new.id;end if;
 return new;
end $$;
create trigger device_checkin_revoke_cleanup after update of revoked_at,status on public.devices_public for each row execute function private.harbor_delete_revoked_checkins();
create function private.harbor_purge_device_checkins() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint;
begin
 delete from private.device_checkins where purge_at<=pg_catalog.clock_timestamp();get diagnostics n=row_count;
 return n;
end $$;
revoke all on function private.harbor_enqueue_checkin_push(uuid,uuid),private.harbor_checkin_parent_check(uuid,uuid,uuid),private.harbor_request_device_checkin(uuid,uuid,uuid),private.harbor_get_checkin_state(uuid,uuid,uuid,uuid),private.harbor_reply_device_checkin(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_get_device_checkins(uuid,uuid,uuid),private.harbor_delete_revoked_checkins(),private.harbor_purge_device_checkins() from public,anon,authenticated;
grant execute on function private.harbor_enqueue_checkin_push(uuid,uuid),private.harbor_checkin_parent_check(uuid,uuid,uuid),private.harbor_request_device_checkin(uuid,uuid,uuid),private.harbor_get_checkin_state(uuid,uuid,uuid,uuid),private.harbor_reply_device_checkin(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_get_device_checkins(uuid,uuid,uuid),private.harbor_delete_revoked_checkins(),private.harbor_purge_device_checkins() to service_role;
-- Runs as the migration owner; no client role can schedule or invoke cleanup.
select cron.schedule('harbor-checkin-retention','51 3 * * *','select private.harbor_purge_device_checkins();');
