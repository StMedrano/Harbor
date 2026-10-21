-- A child's request for more time or access, sent from Harbor's block screen, and a parent's answer.
-- A row names the app (package and label) and why it was blocked. There is no free-text column, so nothing a child
-- typed or read is stored. A parent's yes is written as a temporary grant in the device's controls by the parent app;
-- this table records the decision, alerts the parents when a request arrives, and tells the phone the answer.
create table private.device_access_requests (
 id uuid primary key,
 device_id uuid not null references public.devices_public(id) on delete cascade,
 family_id uuid not null, child_id uuid not null,
 reason text not null check(reason in ('paused','bedtime','blocked_app','limit_reached','time_up')),
 package_name text not null check(package_name ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$' and char_length(package_name)<=255),
 app_label text not null check(char_length(app_label) between 1 and 64 and app_label !~ '[[:cntrl:]]'),
 requested_at timestamptz not null,
 status text not null default 'pending' check(status in ('pending','approved','denied')),
 decided_at timestamptz, decided_by uuid,
 check((status='pending') = (decided_at is null and decided_by is null))
);
create index device_access_requests_device_idx on private.device_access_requests(device_id,requested_at desc);
alter table private.device_access_requests enable row level security;
revoke all on private.device_access_requests from public,anon,authenticated;
grant select,insert,update,delete on private.device_access_requests to service_role;

-- Parent push for a new request: route only (kind and ids), so a lock screen shows a generic line.
create function private.harbor_enqueue_access_request_push(d uuid,r uuid) returns void
language plpgsql security definer set search_path='' as $$
declare dev public.devices_public%rowtype; route jsonb; g record;
begin
 select * into dev from public.devices_public where id=d;
 if not found or dev.status='revoked' or dev.revoked_at is not null then return;end if;
 route:=jsonb_build_object('version',1,'kind','device.access.requested','familyId',dev.family_id,'childId',dev.child_id,'deviceId',dev.id,'resourceId',r);
 for g in
  select p.id,p.user_id from private.parent_fcm_registrations p
  join public.family_members fm on fm.user_id=p.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and p.active and private.harbor_parent_session_active(p.user_id,p.session_id)
 loop
  perform private.harbor_enqueue_notification('access-request:'||r::text,'fcm',jsonb_build_object('parentFcmRegistrationId',g.id,'userId',g.user_id),route);
 end loop;
end $$;

-- Child request (signed). A retry with the same id returns the same request; one open request per app; at most 10 an hour.
create function private.harbor_request_device_access(d uuid,f uuid,c uuid,a uuid,r uuid,why text,pkg text,label text)
returns table(request_id uuid,status text,requested_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare old private.device_access_requests%rowtype; stamp timestamptz:=pg_catalog.clock_timestamp();
begin
 perform private.harbor_usage_binding(d,f,c,a);
 if r is null or why is null or pkg is null or label is null then raise exception 'invalid access request' using errcode='22023';end if;
 select * into old from private.device_access_requests x where x.id=r;
 if found then
  if old.device_id<>d or old.reason<>why or old.package_name<>pkg then raise exception 'IDEMPOTENCY_CONFLICT' using errcode='P0001';end if;
  return query select old.id,
   case when old.status='pending' and old.requested_at<=stamp-interval '60 minutes' then 'expired' else old.status end,old.requested_at;
  return;
 end if;
 select * into old from private.device_access_requests x
 where x.device_id=d and x.package_name=pkg and x.status='pending' and x.requested_at>stamp-interval '60 minutes'
 order by x.requested_at desc limit 1;
 if found then return query select old.id,'pending'::text,old.requested_at;return;end if;
 if (select count(*) from private.device_access_requests x where x.device_id=d and x.requested_at>stamp-interval '1 hour')>=10 then
  return query select r,'limited'::text,stamp;return;
 end if;
 insert into private.device_access_requests(id,device_id,family_id,child_id,reason,package_name,app_label,requested_at)
 values (r,d,f,c,why,pkg,label,stamp);
 perform private.harbor_enqueue_access_request_push(d,r);
 return query select r,'pending'::text,stamp;
end $$;

-- Parent read: the last day's requests for one phone, newest first. An unanswered request older than an hour reads as expired.
create function private.harbor_get_access_requests(actor uuid,sid uuid,d uuid)
returns table(request_id uuid,reason text,package_name text,app_label text,requested_at timestamptz,status text,decided_at timestamptz)
language plpgsql security invoker set search_path='' as $$
begin
 perform private.harbor_checkin_parent_check(actor,sid,d);
 return query select x.id,x.reason,x.package_name,x.app_label,x.requested_at,
  case when x.status='pending' and x.requested_at<=pg_catalog.clock_timestamp()-interval '60 minutes' then 'expired' else x.status end,
  x.decided_at
 from private.device_access_requests x
 where x.device_id=d and x.requested_at>pg_catalog.clock_timestamp()-interval '24 hours'
 order by x.requested_at desc, x.id limit 50;
end $$;

-- Parent answer. Repeating the same answer is a no-op; changing it, or answering an expired request, is stale.
-- The phone gets a route-only push (device.access.approved / device.access.denied) naming the request.
create function private.harbor_decide_access_request(actor uuid,sid uuid,r uuid,decision text)
returns table(request_id uuid,status text)
language plpgsql security invoker set search_path='' as $$
declare req private.device_access_requests%rowtype;
begin
 if decision is null or decision not in ('approved','denied') then raise exception 'invalid decision' using errcode='22023';end if;
 select * into req from private.device_access_requests x where x.id=r for update;
 if not found then raise exception 'access request not found' using errcode='42501';end if;
 perform private.harbor_checkin_parent_check(actor,sid,req.device_id);
 if req.status<>'pending' then
  if req.status=decision then return query select r,req.status;return;end if;
  raise exception 'STALE_VERSION' using errcode='P0001';
 end if;
 if req.requested_at<=pg_catalog.clock_timestamp()-interval '60 minutes' then raise exception 'STALE_VERSION' using errcode='P0001';end if;
 update private.device_access_requests x set status=decision,decided_at=pg_catalog.clock_timestamp(),decided_by=actor where x.id=r;
 perform private.harbor_enqueue_notification('access-answer:'||r::text,'fcm',jsonb_build_object('deviceId',req.device_id),
  jsonb_build_object('version',1,'kind','device.access.'||decision,'familyId',req.family_id,'childId',req.child_id,'deviceId',req.device_id,'resourceId',r));
 return query select r,decision;
end $$;

create function private.harbor_delete_revoked_access_requests() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if new.revoked_at is not null or new.status='revoked' then delete from private.device_access_requests where device_id=new.id;end if;
 return new;
end $$;
create trigger device_access_requests_revoke_cleanup after update of revoked_at,status on public.devices_public
 for each row execute function private.harbor_delete_revoked_access_requests();
create function private.harbor_purge_access_requests() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint;
begin
 delete from private.device_access_requests where requested_at<=pg_catalog.clock_timestamp()-interval '7 days';
 get diagnostics n=row_count;
 return n;
end $$;
revoke all on function private.harbor_enqueue_access_request_push(uuid,uuid),private.harbor_request_device_access(uuid,uuid,uuid,uuid,uuid,text,text,text),private.harbor_get_access_requests(uuid,uuid,uuid),private.harbor_decide_access_request(uuid,uuid,uuid,text),private.harbor_delete_revoked_access_requests(),private.harbor_purge_access_requests() from public,anon,authenticated;
grant execute on function private.harbor_enqueue_access_request_push(uuid,uuid),private.harbor_request_device_access(uuid,uuid,uuid,uuid,uuid,text,text,text),private.harbor_get_access_requests(uuid,uuid,uuid),private.harbor_decide_access_request(uuid,uuid,uuid,text),private.harbor_delete_revoked_access_requests(),private.harbor_purge_access_requests() to service_role;
-- Runs as the migration owner; no client role can schedule or invoke cleanup.
select cron.schedule('harbor-access-request-retention','53 3 * * *','select private.harbor_purge_access_requests();');
