-- Location payloads are private; checkpoints survive clear, expiry and revocation (same model as usage reporting).
create table private.device_location_fixes (
 device_id uuid not null references public.devices_public(id) on delete cascade,
 fix_at timestamptz not null,
 latitude double precision not null check(latitude between -90 and 90),
 longitude double precision not null check(longitude between -180 and 180),
 accuracy_m integer not null check(accuracy_m between 0 and 100000),
 provider text not null check(provider in ('fused','gps','network')),
 received_at timestamptz not null, expires_at timestamptz not null,
 primary key(device_id,fix_at)
);
create index device_location_fix_expiry_idx on private.device_location_fixes(expires_at);
create table private.device_location_status (
 device_id uuid primary key references public.devices_public(id) on delete cascade,
 status text not null check(status in ('sharing','permission_lost','background_missing','services_off')),
 observed_at timestamptz not null, received_at timestamptz not null, expires_at timestamptz not null
);
create index device_location_status_expiry_idx on private.device_location_status(expires_at);
create table private.device_location_checkpoints (
 device_id uuid primary key references public.devices_public(id) on delete cascade,
 epoch_id uuid not null, sequence bigint not null check(sequence between 1 and 9007199254740991),
 payload_hash text not null check(payload_hash ~ '^[0-9a-f]{64}$'),
 is_clear boolean not null, received_at timestamptz not null
);
alter table private.device_location_fixes enable row level security;
alter table private.device_location_status enable row level security;
alter table private.device_location_checkpoints enable row level security;
revoke all on private.device_location_fixes,private.device_location_status,private.device_location_checkpoints from public,anon,authenticated;
grant select,insert,update,delete on private.device_location_fixes,private.device_location_status,private.device_location_checkpoints to service_role;

-- Binding/revocation fence is shared with usage reporting: private.harbor_usage_binding checks family, child, non-revoked device and the anonymous child identity under row locks.
create function private.harbor_store_device_location(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text,cleared boolean,p jsonb)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare old private.device_location_checkpoints%rowtype; stamp timestamptz;
begin
 perform private.harbor_usage_binding(d,f,c,a);
 if e is null or q is null or q<1 or q>9007199254740991 or h is null or h !~ '^[0-9a-f]{64}$' then raise exception 'invalid location checkpoint' using errcode='22023';end if;
 select * into old from private.device_location_checkpoints where device_id=d;
 if found then
  if q<old.sequence then raise exception 'STALE_VERSION' using errcode='P0001';end if;
  if q=old.sequence then
   if h<>old.payload_hash or e<>old.epoch_id or cleared<>old.is_clear then raise exception 'IDEMPOTENCY_CONFLICT' using errcode='P0001';end if;
   return query select true,old.sequence,old.received_at;return;
  end if;
 end if;
 stamp:=pg_catalog.clock_timestamp();
 insert into private.device_location_checkpoints values(d,e,q,h,cleared,stamp)
 on conflict(device_id) do update set epoch_id=excluded.epoch_id,sequence=excluded.sequence,payload_hash=excluded.payload_hash,is_clear=excluded.is_clear,received_at=excluded.received_at;
 if cleared then
  delete from private.device_location_fixes where device_id=d;
  delete from private.device_location_status where device_id=d;
 else
  insert into private.device_location_status values(d,p->>'status',(p->>'observedAt')::timestamptz,stamp,stamp+interval '30 days')
  on conflict(device_id) do update set status=excluded.status,observed_at=excluded.observed_at,received_at=excluded.received_at,expires_at=excluded.expires_at;
  insert into private.device_location_fixes(device_id,fix_at,latitude,longitude,accuracy_m,provider,received_at,expires_at)
  select d,x."at"::timestamptz,x.latitude,x.longitude,x."accuracyM",x.provider,stamp,x."at"::timestamptz+interval '30 days'
  from jsonb_to_recordset(p->'fixes') as x("at" text,latitude double precision,longitude double precision,"accuracyM" integer,provider text)
  on conflict(device_id,fix_at) do nothing;
 end if;
 return query select true,q,stamp;
end $$;

create function private.harbor_write_device_location(d uuid,f uuid,c uuid,a uuid,p jsonb,h text)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
begin
 if p is null or jsonb_typeof(p)<>'object' or p->>'version'<>'1' or not(p ?& array['epochId','sequence','observedAt','status','fixes'])
 or jsonb_typeof(p->'fixes')<>'array' or jsonb_array_length(p->'fixes')>100
 or p->>'status' not in ('sharing','permission_lost','background_missing','services_off')
 or pg_catalog.octet_length(p::text)>524288 then raise exception 'invalid location report' using errcode='22023';end if;
 return query select * from private.harbor_store_device_location(d,f,c,a,(p->>'epochId')::uuid,(p->>'sequence')::bigint,h,false,p);
end $$;
create function private.harbor_clear_device_location(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language sql security invoker set search_path='' as $$
 select * from private.harbor_store_device_location(d,f,c,a,e,q,h,true,null)
$$;
create function private.harbor_get_location_checkpoint(d uuid,f uuid,c uuid,a uuid)
returns table(sequence bigint,epoch_id uuid)
language plpgsql security invoker set search_path='' as $$
begin
 perform private.harbor_usage_binding(d,f,c,a);
 return query select x.sequence,x.epoch_id from private.device_location_checkpoints x where x.device_id=d;
 if not found then return query select 0::bigint,null::uuid;end if;
end $$;

-- Parent read: current parent session, active owner/parent membership, non-revoked device. Newest 500 fixes, newest first.
create function private.harbor_get_device_location(actor uuid,sid uuid,d uuid)
returns table(state text,loc_status text,observed_at timestamptz,received_at timestamptz,fixes jsonb)
language plpgsql security invoker set search_path='' as $$
declare fam uuid; dev public.devices_public%rowtype; st private.device_location_status%rowtype;
begin
 perform 1 from auth.sessions where id=sid and user_id=actor for share;
 if not found or not private.harbor_parent_session_active(actor,sid) then raise exception 'current parent session required' using errcode='42501';end if;
 select family_id into fam from public.devices_public where id=d;
 perform 1 from public.family_members where family_id=fam and user_id=actor and status='active' and role in ('owner','parent') for share;
 if not found then raise exception 'parent membership required' using errcode='42501';end if;
 select * into dev from public.devices_public where id=d and family_id=fam for share;
 if not found or dev.status='revoked' or dev.revoked_at is not null then raise exception 'DEVICE_REVOKED' using errcode='42501';end if;
 select * into st from private.device_location_status where device_id=d;
 if found then
  if st.expires_at<=pg_catalog.clock_timestamp() then return query select 'expired'::text,null::text,null::timestamptz,null::timestamptz,'[]'::jsonb;
  else return query select 'available'::text,st.status,st.observed_at,st.received_at,
   coalesce((select jsonb_agg(jsonb_build_object('at',to_char(g.fix_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'latitude',g.latitude,'longitude',g.longitude,'accuracyM',g.accuracy_m,'provider',g.provider) order by g.fix_at desc)
    from (select * from private.device_location_fixes where device_id=d and expires_at>pg_catalog.clock_timestamp() order by fix_at desc limit 500) g),'[]'::jsonb);end if;
 else
  if exists(select 1 from private.device_location_checkpoints x where x.device_id=d and not x.is_clear and x.received_at<=pg_catalog.clock_timestamp()-interval '30 days') then return query select 'expired'::text,null::text,null::timestamptz,null::timestamptz,'[]'::jsonb;
  else return query select 'none'::text,null::text,null::timestamptz,null::timestamptz,'[]'::jsonb;end if;
 end if;
end $$;

create function private.harbor_delete_revoked_location() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if new.revoked_at is not null or new.status='revoked' then
  delete from private.device_location_fixes where device_id=new.id;
  delete from private.device_location_status where device_id=new.id;
 end if;
 return new;
end $$;
create trigger device_location_revoke_cleanup after update of revoked_at,status on public.devices_public for each row execute function private.harbor_delete_revoked_location();
create function private.harbor_purge_device_location() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint; m bigint;
begin
 delete from private.device_location_fixes where expires_at<=pg_catalog.clock_timestamp();get diagnostics n=row_count;
 delete from private.device_location_status where expires_at<=pg_catalog.clock_timestamp();get diagnostics m=row_count;
 return n+m;
end $$;
revoke all on function private.harbor_store_device_location(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),private.harbor_write_device_location(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_clear_device_location(uuid,uuid,uuid,uuid,uuid,bigint,text),private.harbor_get_location_checkpoint(uuid,uuid,uuid,uuid),private.harbor_get_device_location(uuid,uuid,uuid),private.harbor_delete_revoked_location(),private.harbor_purge_device_location() from public,anon,authenticated;
grant execute on function private.harbor_store_device_location(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),private.harbor_write_device_location(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_clear_device_location(uuid,uuid,uuid,uuid,uuid,bigint,text),private.harbor_get_location_checkpoint(uuid,uuid,uuid,uuid),private.harbor_get_device_location(uuid,uuid,uuid),private.harbor_delete_revoked_location(),private.harbor_purge_device_location() to service_role;
-- Runs as the migration owner; no client role can schedule or invoke cleanup.
create extension if not exists pg_cron with schema pg_catalog;
select cron.schedule('harbor-location-retention','27 3 * * *','select private.harbor_purge_device_location();');
