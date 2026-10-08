begin;
select plan(17);

insert into auth.users (id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous) values
  ('71000000-0000-4000-8000-000000000001','authenticated','authenticated','loc-parent@harbor.test','{}','{}',now(),now(),false),
  ('71000000-0000-4000-8000-000000000002','authenticated','authenticated','loc-outsider@harbor.test','{}','{}',now(),now(),false),
  ('71000000-0000-4000-8000-000000000101','authenticated','authenticated',null,'{}','{}',now(),now(),true);

insert into public.families (id, name) values ('72000000-0000-4000-8000-000000000001','Loc Family'),('72000000-0000-4000-8000-000000000002','Other Family');
insert into public.family_members (family_id, user_id, role, status) values
  ('72000000-0000-4000-8000-000000000001','71000000-0000-4000-8000-000000000001','owner','active'),
  ('72000000-0000-4000-8000-000000000002','71000000-0000-4000-8000-000000000002','owner','active');
insert into public.children (id, family_id, display_name) values ('73000000-0000-4000-8000-000000000001','72000000-0000-4000-8000-000000000001','Loc Child');
insert into public.devices_public (id, family_id, child_id, display_name, supervision_mode, status)
  values ('74000000-0000-4000-8000-000000000001','72000000-0000-4000-8000-000000000001','73000000-0000-4000-8000-000000000001','Loc Phone','full','active');
insert into private.device_security (device_id, auth_user_id, public_key_spki)
  values ('74000000-0000-4000-8000-000000000001','71000000-0000-4000-8000-000000000101','fixture');

create function pg_temp.rec(p jsonb) returns jsonb language plpgsql as $$
declare r jsonb;
begin
  select to_jsonb(t) into r from private.harbor_record_locations('74000000-0000-4000-8000-000000000001', p) t;
  return r;
exception when others then return jsonb_build_object('error', SQLSTATE, 'message', SQLERRM);
end $$;

-- 1: basic accept
select is(pg_temp.rec(jsonb_build_array(jsonb_build_object('latitude',40.7128,'longitude',-74.006,'accuracyM',12.5,'batteryPct',80,'recordedAt',to_jsonb(now() - interval '2 minutes'))))->>'accepted','1','valid point accepted');
select is((select latitude from public.child_locations where child_id='73000000-0000-4000-8000-000000000001'),40.7128::double precision,'latest row created');
select is((select battery_pct from public.child_locations),80::smallint,'battery stored');
select ok((select last_seen_at is not null from public.devices_public where id='74000000-0000-4000-8000-000000000001'),'last_seen_at touched');

-- 2: newer wins, older does not overwrite latest but is kept in history
select pg_temp.rec(jsonb_build_array(jsonb_build_object('latitude',41,'longitude',-74,'recordedAt',to_jsonb(now() - interval '1 minute'))));
select is((select latitude from public.child_locations),41::double precision,'newer point replaces latest');
select pg_temp.rec(jsonb_build_array(jsonb_build_object('latitude',10,'longitude',10,'recordedAt',to_jsonb(now() - interval '1 hour'))));
select is((select latitude from public.child_locations),41::double precision,'older batch does not overwrite latest');
select is((select count(*) from private.location_history),3::bigint,'older point still stored in history');

-- 3: idempotent retry
select is(pg_temp.rec(jsonb_build_array(jsonb_build_object('latitude',10,'longitude',10,'recordedAt',(select to_jsonb(recorded_at) from private.location_history order by recorded_at limit 1))))->>'accepted','1','retry counts as accepted');
select is((select count(*) from private.location_history),3::bigint,'retry adds no duplicate');

-- 4: invalid points are rejected individually
select is(pg_temp.rec(jsonb_build_array(
  jsonb_build_object('latitude',91,'longitude',0,'recordedAt',to_jsonb(now())),
  jsonb_build_object('latitude',0,'longitude',181,'recordedAt',to_jsonb(now())),
  jsonb_build_object('latitude','x','longitude',0,'recordedAt',to_jsonb(now())),
  jsonb_build_object('latitude',0,'longitude',0,'recordedAt','not-a-date'),
  jsonb_build_object('latitude',0,'longitude',0,'recordedAt',to_jsonb(now() + interval '1 day')),
  jsonb_build_object('latitude',0,'longitude',0,'recordedAt',to_jsonb(now() - interval '8 days')),
  jsonb_build_object('latitude',0,'longitude',0,'accuracyM',-1,'recordedAt',to_jsonb(now())),
  jsonb_build_object('latitude',1,'longitude',1,'recordedAt',to_jsonb(now() - interval '30 seconds'))
))->>'rejected','7','bad coordinates, dates and accuracy are rejected one by one');
select is((select latitude from public.child_locations),1::double precision,'the one valid point in a mixed batch landed');

-- 5: batch shape
select is(pg_temp.rec('[]'::jsonb)->>'error','22023','empty batch rejected');
select is(pg_temp.rec('{"a":1}'::jsonb)->>'error','22023','non-array rejected');
select is(pg_temp.rec((select jsonb_agg(jsonb_build_object('latitude',1,'longitude',1,'recordedAt',to_jsonb(now() - (g||' seconds')::interval))) from generate_series(1,51) g))->>'error','22023','more than 50 points rejected');

-- 6: revoked device
update public.devices_public set status='revoked', revoked_at=now() where id='74000000-0000-4000-8000-000000000001';
select is(pg_temp.rec(jsonb_build_array(jsonb_build_object('latitude',1,'longitude',1,'recordedAt',to_jsonb(now()))))->>'message','DEVICE_REVOKED','revoked device cannot report');
update public.devices_public set status='active', revoked_at=null where id='74000000-0000-4000-8000-000000000001';

-- 7: RLS
set local role authenticated;
select set_config('request.jwt.claim.sub','71000000-0000-4000-8000-000000000001',true);
select is((select count(*) from public.child_locations),1::bigint,'parent reads own child location');
select set_config('request.jwt.claim.sub','71000000-0000-4000-8000-000000000002',true);
select is((select count(*) from public.child_locations),0::bigint,'other family sees nothing');
reset role;

select * from finish();
rollback;
