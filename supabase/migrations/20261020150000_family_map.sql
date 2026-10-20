-- Family map: a child's phone sees the family's children and the parents who chose to share. A parent shares only after turning it
-- on in their own app, only their latest place is kept, and it is removed after 24 hours or as soon as they stop.
-- One family switch decides whether children see the map at all (default: they do).
create table private.family_map_settings (
 family_id uuid primary key references public.families(id) on delete cascade,
 children_can_view boolean not null default true,
 updated_by uuid not null,
 updated_at timestamptz not null
);
create table private.parent_location_sharing (
 family_id uuid not null,
 user_id uuid not null,
 label text not null check(char_length(label) between 1 and 40 and label=btrim(label)),
 fix_at timestamptz not null,
 latitude double precision not null check(latitude between -90 and 90),
 longitude double precision not null check(longitude between -180 and 180),
 accuracy_m integer not null check(accuracy_m between 0 and 100000),
 received_at timestamptz not null,
 expires_at timestamptz not null,
 primary key(family_id,user_id),
 foreign key(family_id,user_id) references public.family_members(family_id,user_id) on delete cascade
);
create index parent_location_sharing_expiry_idx on private.parent_location_sharing(expires_at);
alter table private.family_map_settings enable row level security;
alter table private.parent_location_sharing enable row level security;
revoke all on private.family_map_settings,private.parent_location_sharing from public,anon,authenticated;
grant select,insert,update,delete on private.family_map_settings,private.parent_location_sharing to service_role;

-- Shared parent prelude: current parent session and an active owner/parent membership in this family.
create function private.harbor_family_parent_check(actor uuid,sid uuid,fam uuid) returns void
language plpgsql security invoker set search_path='' as $$
begin
 perform 1 from auth.sessions where id=sid and user_id=actor for share;
 if not found or not private.harbor_parent_session_active(actor,sid) then raise exception 'current parent session required' using errcode='42501';end if;
 perform 1 from public.family_members where family_id=fam and user_id=actor and status='active' and role in ('owner','parent') for share;
 if not found then raise exception 'parent membership required' using errcode='42501';end if;
end $$;

create function private.harbor_report_parent_location(actor uuid,sid uuid,fam uuid,p jsonb)
returns table(confirmed boolean,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare stamp timestamptz:=pg_catalog.clock_timestamp(); fx jsonb; at_ timestamptz; lbl text;
begin
 perform private.harbor_family_parent_check(actor,sid,fam);
 if p is null or jsonb_typeof(p)<>'object' or not(p ?& array['label','fix']) or jsonb_typeof(p->'fix')<>'object'
  or pg_catalog.octet_length(p::text)>4096 then raise exception 'invalid parent location' using errcode='22023';end if;
 lbl:=p->>'label'; fx:=p->'fix';
 if lbl is null or lbl<>pg_catalog.btrim(lbl) or pg_catalog.char_length(lbl) not between 1 and 40 then raise exception 'invalid parent location' using errcode='22023';end if;
 at_:=(fx->>'at')::timestamptz;
 if at_>stamp+interval '5 minutes' or at_<stamp-interval '10 minutes' then raise exception 'stale parent location' using errcode='22023';end if;
 insert into private.parent_location_sharing(family_id,user_id,label,fix_at,latitude,longitude,accuracy_m,received_at,expires_at)
 values(fam,actor,lbl,at_,(fx->>'latitude')::double precision,(fx->>'longitude')::double precision,(fx->>'accuracyM')::integer,stamp,at_+interval '24 hours')
 on conflict(family_id,user_id) do update set label=excluded.label,fix_at=excluded.fix_at,latitude=excluded.latitude,longitude=excluded.longitude,
  accuracy_m=excluded.accuracy_m,received_at=excluded.received_at,expires_at=excluded.expires_at
  where private.parent_location_sharing.fix_at<excluded.fix_at;
 return query select true,stamp;
end $$;

create function private.harbor_stop_parent_location(actor uuid,sid uuid,fam uuid)
returns table(confirmed boolean,received_at timestamptz)
language plpgsql security invoker set search_path='' as $$
begin
 perform private.harbor_family_parent_check(actor,sid,fam);
 delete from private.parent_location_sharing where family_id=fam and user_id=actor;
 return query select true,pg_catalog.clock_timestamp();
end $$;

create function private.harbor_get_family_map_settings(actor uuid,sid uuid,fam uuid)
returns table(children_can_view boolean,my_label text,my_last_at timestamptz)
language plpgsql security invoker set search_path='' as $$
declare s private.parent_location_sharing%rowtype; has boolean;
begin
 perform private.harbor_family_parent_check(actor,sid,fam);
 select * into s from private.parent_location_sharing x where x.family_id=fam and x.user_id=actor and x.expires_at>pg_catalog.clock_timestamp();
 has:=found;
 return query select coalesce((select m.children_can_view from private.family_map_settings m where m.family_id=fam),true),
  case when has then s.label else null end,case when has then s.fix_at else null end;
end $$;

create function private.harbor_set_family_map_settings(actor uuid,sid uuid,fam uuid,visible boolean)
returns table(children_can_view boolean)
language plpgsql security invoker set search_path='' as $$
begin
 perform private.harbor_family_parent_check(actor,sid,fam);
 if visible is null then raise exception 'invalid family map setting' using errcode='22023';end if;
 insert into private.family_map_settings(family_id,children_can_view,updated_by,updated_at) values(fam,visible,actor,pg_catalog.clock_timestamp())
 on conflict(family_id) do update set children_can_view=excluded.children_can_view,updated_by=excluded.updated_by,updated_at=excluded.updated_at;
 return query select visible;
end $$;

-- Child read. The binding is the shared revocation fence. A child sees every child in the family (a child with no place yet has fix null)
-- and only the parents who are sharing right now. Nothing else about anyone is returned: no email, no ids from the auth system.
create function private.harbor_get_family_map(d uuid,f uuid,c uuid,a uuid)
returns table(enabled boolean,people jsonb)
language plpgsql security invoker set search_path='' as $$
declare on_ boolean; stamp timestamptz:=pg_catalog.clock_timestamp(); kids jsonb; adults jsonb;
begin
 perform private.harbor_usage_binding(d,f,c,a);
 select coalesce((select m.children_can_view from private.family_map_settings m where m.family_id=f),true) into on_;
 if not on_ then return query select false,'[]'::jsonb;return;end if;
 select coalesce(jsonb_agg(jsonb_build_object('id',ch.id,'kind','child','name',ch.display_name,'self',ch.id=c,'fix',lf.fix) order by ch.display_name,ch.id),'[]'::jsonb) into kids
 from public.children ch
 left join lateral (
  select jsonb_build_object('at',to_char(g.fix_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'latitude',g.latitude,'longitude',g.longitude,'accuracyM',g.accuracy_m) as fix
  from private.device_location_fixes g join public.devices_public dv on dv.id=g.device_id
  where dv.child_id=ch.id and dv.family_id=f and dv.status<>'revoked' and dv.revoked_at is null and g.expires_at>stamp
  order by g.fix_at desc limit 1) lf on true
 where ch.family_id=f;
 select coalesce(jsonb_agg(jsonb_build_object('id',m.id,'kind','parent','name',s.label,'self',false,
   'fix',jsonb_build_object('at',to_char(s.fix_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),'latitude',s.latitude,'longitude',s.longitude,'accuracyM',s.accuracy_m)) order by s.label,m.id),'[]'::jsonb) into adults
 from private.parent_location_sharing s join public.family_members m on m.family_id=s.family_id and m.user_id=s.user_id and m.status='active'
 where s.family_id=f and s.expires_at>stamp;
 return query select true,kids||adults;
end $$;

-- A parent who is removed, or whose membership is no longer active, stops appearing immediately.
create function private.harbor_drop_inactive_parent_sharing() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if new.status<>'active' then delete from private.parent_location_sharing where family_id=new.family_id and user_id=new.user_id;end if;
 return new;
end $$;
create trigger parent_location_sharing_membership_cleanup after update of status on public.family_members for each row execute function private.harbor_drop_inactive_parent_sharing();
create function private.harbor_purge_parent_location_sharing() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint;
begin
 delete from private.parent_location_sharing where expires_at<=pg_catalog.clock_timestamp();get diagnostics n=row_count;
 return n;
end $$;
revoke all on function private.harbor_family_parent_check(uuid,uuid,uuid),private.harbor_report_parent_location(uuid,uuid,uuid,jsonb),private.harbor_stop_parent_location(uuid,uuid,uuid),private.harbor_get_family_map_settings(uuid,uuid,uuid),private.harbor_set_family_map_settings(uuid,uuid,uuid,boolean),private.harbor_get_family_map(uuid,uuid,uuid,uuid),private.harbor_drop_inactive_parent_sharing(),private.harbor_purge_parent_location_sharing() from public,anon,authenticated;
grant execute on function private.harbor_family_parent_check(uuid,uuid,uuid),private.harbor_report_parent_location(uuid,uuid,uuid,jsonb),private.harbor_stop_parent_location(uuid,uuid,uuid),private.harbor_get_family_map_settings(uuid,uuid,uuid),private.harbor_set_family_map_settings(uuid,uuid,uuid,boolean),private.harbor_get_family_map(uuid,uuid,uuid,uuid),private.harbor_drop_inactive_parent_sharing(),private.harbor_purge_parent_location_sharing() to service_role;
-- Runs as the migration owner; no client role can schedule or invoke cleanup.
select cron.schedule('harbor-parent-location-retention','43 3 * * *','select private.harbor_purge_parent_location_sharing();');
