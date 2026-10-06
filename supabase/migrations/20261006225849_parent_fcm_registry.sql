-- Parent Android2A: private current-session FCM registration.
create table private.parent_fcm_registrations (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references auth.users(id) on delete cascade,
 session_id uuid not null references auth.sessions(id) on delete cascade,
 client_installation_id text not null check(char_length(btrim(client_installation_id)) between 1 and 200),
 token text not null check(char_length(token) between 1 and 4096),
 token_hash text not null check(token_hash ~ '^[0-9a-f]{64}$'),
 active boolean not null default true,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),
 removed_at timestamptz,
 unique(user_id,client_installation_id)
);
create unique index parent_fcm_active_token_idx on private.parent_fcm_registrations(token_hash) where active;
create index parent_fcm_session_idx on private.parent_fcm_registrations(session_id);
alter table private.parent_fcm_registrations enable row level security;
revoke all on private.parent_fcm_registrations from public,anon,authenticated;
grant select,insert,update,delete on private.parent_fcm_registrations to service_role;
create function private.harbor_parent_fcm_identity_guard() returns trigger language plpgsql security invoker set search_path='' as $$
begin
 if new.id<>old.id or new.user_id<>old.user_id then raise exception 'parent registration identity is immutable' using errcode='22023';end if;
 return new;
end $$;
create trigger parent_fcm_identity_guard before update on private.parent_fcm_registrations for each row execute function private.harbor_parent_fcm_identity_guard();
revoke all on function private.harbor_parent_fcm_identity_guard() from public,anon,authenticated;

create function private.harbor_parent_session_active(actor uuid,session uuid) returns boolean
language sql security invoker set search_path='' as $$
 select exists(select 1 from auth.sessions s join auth.users u on u.id=s.user_id
 where s.id=session and s.user_id=actor and u.is_anonymous is false
 and (s.not_after is null or s.not_after>pg_catalog.clock_timestamp()))
$$;
revoke all on function private.harbor_parent_session_active(uuid,uuid) from public,anon,authenticated;
grant execute on function private.harbor_parent_session_active(uuid,uuid) to service_role;

create function private.harbor_register_parent_fcm(actor uuid,session uuid,installation text,input_token text)
returns table(registration_id uuid,active boolean) language plpgsql security invoker set search_path='' as $$
declare v_install text:=btrim(coalesce(installation,''));v_token text:=btrim(coalesce(input_token,''));v_hash text;v_id uuid;
begin
 if char_length(v_install) not between 1 and 200 or char_length(v_token) not between 1 and 4096
 or installation ~ '[[:cntrl:]]' or input_token ~ '[[:cntrl:]]' then raise exception 'VALIDATION_FAILED' using errcode='22023';end if;
 -- Retain the current session through registration. Logout may then delete it,
 -- cascading this binding before any subsequent dispatch can resolve it.
 perform s.id from auth.sessions s where s.id=session and s.user_id=actor for share;
 if not found or not private.harbor_parent_session_active(actor,session) then raise exception 'current parent session required' using errcode='42501';end if;
 v_hash:=pg_catalog.encode(extensions.digest(v_token,'sha256'),'hex');
 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('parent-fcm-token:'||v_hash,0));
 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('parent-fcm-install:'||actor::text||':'||v_install,0));
 if not private.harbor_parent_session_active(actor,session) then raise exception 'current parent session required' using errcode='42501';end if;
 select r.id into v_id from private.parent_fcm_registrations r where r.user_id=actor and r.client_installation_id=v_install for update;
 update private.parent_fcm_registrations r set active=false,removed_at=clock_timestamp(),updated_at=clock_timestamp()
 where r.active and r.token_hash=v_hash and (v_id is null or r.id<>v_id);
 if v_id is null then
  insert into private.parent_fcm_registrations(user_id,session_id,client_installation_id,token,token_hash)
   values(actor,session,v_install,v_token,v_hash) returning id into v_id;
  insert into private.audit_events(event_kind,actor_user_id,resource_type,resource_id,metadata)
   values('parent.fcm.registered',actor,'parent_fcm_registration',v_id,'{}'::jsonb);
 else
  update private.parent_fcm_registrations r set session_id=session,token=v_token,token_hash=v_hash,active=true,removed_at=null,updated_at=clock_timestamp() where r.id=v_id;
 end if;
 return query select v_id,true;
end $$;
revoke all on function private.harbor_register_parent_fcm(uuid,uuid,text,text) from public,anon,authenticated;
grant execute on function private.harbor_register_parent_fcm(uuid,uuid,text,text) to service_role;

create function private.harbor_remove_parent_fcm(actor uuid,installation text) returns void
language plpgsql security invoker set search_path='' as $$
declare v_id uuid;
begin
 update private.parent_fcm_registrations r set active=false,removed_at=clock_timestamp(),updated_at=clock_timestamp()
 where r.user_id=actor and r.client_installation_id=btrim(installation) and r.active returning r.id into v_id;
 if v_id is not null then insert into private.audit_events(event_kind,actor_user_id,resource_type,resource_id,metadata)
 values('parent.fcm.removed',actor,'parent_fcm_registration',v_id,'{}'::jsonb);end if;
end $$;
revoke all on function private.harbor_remove_parent_fcm(uuid,text) from public,anon,authenticated;
grant execute on function private.harbor_remove_parent_fcm(uuid,text) to service_role;
