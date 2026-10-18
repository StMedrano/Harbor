-- Usage payloads are private; checkpoints survive clear, expiry and revocation.
create table private.device_usage_snapshots (
 device_id uuid primary key references public.devices_public(id) on delete cascade,
 report jsonb not null check(jsonb_typeof(report)='object'),
 received_at timestamptz not null, expires_at timestamptz not null
);
create index device_usage_expiry_idx on private.device_usage_snapshots(expires_at);
create table private.device_usage_checkpoints (
 device_id uuid primary key references public.devices_public(id) on delete cascade,
 epoch_id uuid not null, sequence bigint not null check(sequence between 1 and 9007199254740991),
 payload_hash text not null check(payload_hash ~ '^[0-9a-f]{64}$'),
 is_clear boolean not null, received_at timestamptz not null
);
alter table private.device_usage_snapshots enable row level security;
alter table private.device_usage_checkpoints enable row level security;
revoke all on private.device_usage_snapshots,private.device_usage_checkpoints from public,anon,authenticated;
grant select,insert,update,delete on private.device_usage_snapshots,private.device_usage_checkpoints to service_role;

create function private.harbor_usage_binding(d uuid,f uuid,c uuid,a uuid) returns void
language plpgsql security invoker set search_path='' as $$
declare v public.devices_public%rowtype;
begin
 select * into v from public.devices_public where id=d for update;
 if not found or v.family_id<>f or v.child_id<>c then raise exception 'device binding denied' using errcode='42501';end if;
 if v.status='revoked' or v.revoked_at is not null then raise exception 'DEVICE_REVOKED' using errcode='42501';end if;
 perform 1 from private.device_security s join auth.users u on u.id=s.auth_user_id
 where s.device_id=d and s.auth_user_id=a and u.is_anonymous is true for share of s,u;
 if not found then raise exception 'device binding denied' using errcode='42501';end if;
end $$;

create function private.harbor_store_device_usage(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text,cleared boolean,p jsonb)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare old private.device_usage_checkpoints%rowtype; stamp timestamptz;
begin
 perform private.harbor_usage_binding(d,f,c,a);
 if e is null or q is null or q<1 or q>9007199254740991 or h is null or h !~ '^[0-9a-f]{64}$' then raise exception 'invalid usage checkpoint' using errcode='22023';end if;
 select * into old from private.device_usage_checkpoints where device_id=d;
 if found then
  if q<old.sequence then raise exception 'STALE_VERSION' using errcode='P0001';end if;
  if q=old.sequence then
   if h<>old.payload_hash or e<>old.epoch_id or cleared<>old.is_clear then raise exception 'IDEMPOTENCY_CONFLICT' using errcode='P0001';end if;
   return query select true,old.sequence,old.received_at;return;
  end if;
 end if;
 stamp:=pg_catalog.clock_timestamp();
 insert into private.device_usage_checkpoints values(d,e,q,h,cleared,stamp)
 on conflict(device_id) do update set epoch_id=excluded.epoch_id,sequence=excluded.sequence,payload_hash=excluded.payload_hash,is_clear=excluded.is_clear,received_at=excluded.received_at;
 if cleared then delete from private.device_usage_snapshots where device_id=d;
 else
  insert into private.device_usage_snapshots values(d,p,stamp,stamp+interval '30 days')
  on conflict(device_id) do update set report=excluded.report,received_at=excluded.received_at,expires_at=excluded.expires_at;
 end if;
 return query select true,q,stamp;
end $$;

create function private.harbor_write_device_usage(d uuid,f uuid,c uuid,a uuid,p jsonb,h text)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
begin
 if p is null or jsonb_typeof(p)<>'object' or p->>'version'<>'1' or not(p ?& array['epochId','sequence','observedAt','zoneId','usagePermission','inventoryStatus','inventory','days'])
 or jsonb_typeof(p->'inventory')<>'array' or jsonb_typeof(p->'days')<>'array'
 or jsonb_array_length(p->'inventory')>500 or jsonb_array_length(p->'days')>7
 or pg_catalog.octet_length(p::text)>2097152 then raise exception 'invalid usage report' using errcode='22023';end if;
 return query select * from private.harbor_store_device_usage(d,f,c,a,(p->>'epochId')::uuid,(p->>'sequence')::bigint,h,false,p);
end $$;
create function private.harbor_clear_device_usage(d uuid,f uuid,c uuid,a uuid,e uuid,q bigint,h text)
returns table(confirmed boolean,sequence bigint,received_at timestamptz)
language sql security invoker set search_path='' as $$
 select * from private.harbor_store_device_usage(d,f,c,a,e,q,h,true,null)
$$;
create function private.harbor_get_usage_checkpoint(d uuid,f uuid,c uuid,a uuid)
returns table(sequence bigint,epoch_id uuid)
language plpgsql security invoker set search_path='' as $$
begin
 perform private.harbor_usage_binding(d,f,c,a);
 return query select x.sequence,x.epoch_id from private.device_usage_checkpoints x where x.device_id=d;
 if not found then return query select 0::bigint,null::uuid;end if;
end $$;
create function private.harbor_get_device_usage(actor uuid,sid uuid,d uuid)
returns table(state text,report jsonb,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare fam uuid; dev public.devices_public%rowtype; snap private.device_usage_snapshots%rowtype;
begin
 perform 1 from auth.sessions where id=sid and user_id=actor for share;
 if not found or not private.harbor_parent_session_active(actor,sid) then raise exception 'current parent session required' using errcode='42501';end if;
 select family_id into fam from public.devices_public where id=d;
 perform 1 from public.family_members where family_id=fam and user_id=actor and status='active' and role in ('owner','parent') for share;
 if not found then raise exception 'parent membership required' using errcode='42501';end if;
 select * into dev from public.devices_public where id=d and family_id=fam for share;
 if not found or dev.status='revoked' or dev.revoked_at is not null then raise exception 'DEVICE_REVOKED' using errcode='42501';end if;
 select * into snap from private.device_usage_snapshots where device_id=d;
 if found then
  if snap.expires_at<=pg_catalog.clock_timestamp() then return query select 'expired'::text,null::jsonb,null::timestamptz;
  else return query select 'available'::text,snap.report,snap.received_at;end if;
 else
  if exists(select 1 from private.device_usage_checkpoints x where x.device_id=d and not x.is_clear and x.received_at<=pg_catalog.clock_timestamp()-interval '30 days') then return query select 'expired'::text,null::jsonb,null::timestamptz;
  else return query select 'none'::text,null::jsonb,null::timestamptz;end if;
 end if;
end $$;
create function private.harbor_delete_revoked_usage() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if new.revoked_at is not null or new.status='revoked' then delete from private.device_usage_snapshots where device_id=new.id;end if;
 return new;
end $$;
create trigger device_usage_revoke_cleanup after update of revoked_at,status on public.devices_public for each row execute function private.harbor_delete_revoked_usage();
create function private.harbor_purge_device_usage() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint;
begin delete from private.device_usage_snapshots where expires_at<=pg_catalog.clock_timestamp();get diagnostics n=row_count;return n;end $$;
revoke all on function private.harbor_usage_binding(uuid,uuid,uuid,uuid),private.harbor_store_device_usage(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),private.harbor_write_device_usage(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_clear_device_usage(uuid,uuid,uuid,uuid,uuid,bigint,text),private.harbor_get_usage_checkpoint(uuid,uuid,uuid,uuid),private.harbor_get_device_usage(uuid,uuid,uuid),private.harbor_delete_revoked_usage(),private.harbor_purge_device_usage() from public,anon,authenticated;
grant execute on function private.harbor_usage_binding(uuid,uuid,uuid,uuid),private.harbor_store_device_usage(uuid,uuid,uuid,uuid,uuid,bigint,text,boolean,jsonb),private.harbor_write_device_usage(uuid,uuid,uuid,uuid,jsonb,text),private.harbor_clear_device_usage(uuid,uuid,uuid,uuid,uuid,bigint,text),private.harbor_get_usage_checkpoint(uuid,uuid,uuid,uuid),private.harbor_get_device_usage(uuid,uuid,uuid),private.harbor_delete_revoked_usage(),private.harbor_purge_device_usage() to service_role;
