-- Parent Android2A: server-only atomic child creation.
create table private.child_creation_requests (
  user_id uuid not null references auth.users(id) on delete cascade,
  family_id uuid not null references public.families(id) on delete cascade,
  idempotency_key text not null check(char_length(btrim(idempotency_key)) > 0),
  payload_hash text not null check(payload_hash ~ '^[0-9a-f]{64}$'),
  child_id uuid not null unique,
  created_at timestamptz not null default now(),
  primary key(user_id,family_id,idempotency_key),
  foreign key(family_id,child_id) references public.children(family_id,id) on delete cascade
);
create index child_creation_requests_family_idx on private.child_creation_requests(family_id);
alter table private.child_creation_requests enable row level security;
revoke all on private.child_creation_requests from public,anon,authenticated;
grant select,insert,update,delete on private.child_creation_requests to service_role;

create function private.harbor_create_child(actor uuid,family uuid,name text,key text,fingerprint text)
returns table(id uuid,family_id uuid,display_name text,created_at timestamptz,updated_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare child uuid; stored_hash text; normalized_name text:=btrim(coalesce(name,'')); normalized_key text:=btrim(coalesce(key,''));
begin
  if char_length(normalized_name) not between 1 and 100 or name ~ '[[:cntrl:]]' or normalized_key='' or fingerprint is null or fingerprint !~ '^[0-9a-f]{64}$' then
    raise exception 'VALIDATION_FAILED' using errcode='22023';
  end if;
  -- FOR SHARE conflicts with removal UPDATE, rechecks the updated row after waiting,
  -- and retains authorization through the atomic create/request/audit transaction.
  perform fm.id from public.family_members fm join auth.users u on u.id=fm.user_id
  where fm.family_id=family and fm.user_id=actor and fm.status='active'
    and fm.role in ('owner','parent') and u.is_anonymous is false
  for share of fm;
  if not found then raise exception 'parent membership required' using errcode='42501'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text||':'||family::text||':'||normalized_key,0));
  select r.child_id,r.payload_hash into child,stored_hash from private.child_creation_requests r
  where r.user_id=actor and r.family_id=family and r.idempotency_key=normalized_key;
  if found then
    if stored_hash<>fingerprint then raise exception 'IDEMPOTENCY_CONFLICT' using errcode='P0001'; end if;
  else
    insert into public.children(family_id,display_name) values(family,normalized_name) returning public.children.id into child;
    insert into private.child_creation_requests(user_id,family_id,idempotency_key,payload_hash,child_id)
      values(actor,family,normalized_key,fingerprint,child);
    insert into private.audit_events(event_kind,family_id,actor_user_id,resource_type,resource_id,metadata)
      values('child.created',family,actor,'child',child,'{}'::jsonb);
  end if;
  return query select c.id,c.family_id,c.display_name,c.created_at,c.updated_at from public.children c where c.id=child and c.family_id=family;
end $$;
revoke all on function private.harbor_create_child(uuid,uuid,text,text,text) from public,anon,authenticated;
grant execute on function private.harbor_create_child(uuid,uuid,text,text,text) to service_role;
