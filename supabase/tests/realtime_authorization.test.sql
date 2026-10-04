begin;
select plan(8);
insert into auth.users(id, is_anonymous) values
('81000000-0000-4000-8000-000000000001',false),
('81000000-0000-4000-8000-000000000002',false),
('81000000-0000-4000-8000-000000000003',true);
insert into public.families(id,name) values ('82000000-0000-4000-8000-000000000001','Realtime A');
insert into public.family_members(family_id,user_id,role) values
('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','owner');
insert into realtime.messages(topic, extension, payload) values
('family:82000000-0000-4000-8000-000000000001','broadcast','{"version":1,"kind":"changed"}'),
('family:82000000-0000-4000-8000-000000000001','presence','{}');
create function pg_temp.realtime_count(p_user uuid, p_topic text, p_extension text default 'broadcast')
returns bigint language plpgsql as $$
declare v_count bigint;
begin
  perform set_config('request.jwt.claim.sub',p_user::text,true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',p_user,'role','authenticated')::text,true);
  perform set_config('realtime.topic',p_topic,true);
  perform set_config('role','authenticated',true);
  select count(*) into v_count from realtime.messages where extension::text = p_extension;
  perform set_config('role','postgres',true);
  return v_count;
exception when others then
  perform set_config('role','postgres',true);
  raise;
end;
$$;
select is(pg_temp.realtime_count('81000000-0000-4000-8000-000000000001','family:82000000-0000-4000-8000-000000000001'),1::bigint,'active parent may receive family broadcasts');
select is(pg_temp.realtime_count('81000000-0000-4000-8000-000000000002','family:82000000-0000-4000-8000-000000000001'),0::bigint,'other parent cannot receive family broadcasts');
select is(pg_temp.realtime_count('81000000-0000-4000-8000-000000000003','family:82000000-0000-4000-8000-000000000001'),0::bigint,'device Auth cannot receive family broadcasts');
select is(pg_temp.realtime_count('81000000-0000-4000-8000-000000000001','family:not-a-uuid'),0::bigint,'malformed topic fails closed without cast errors');
select is(pg_temp.realtime_count('81000000-0000-4000-8000-000000000001','family:82000000-0000-4000-8000-000000000099'),0::bigint,'nonexistent family topic denied');
select is(pg_temp.realtime_count('81000000-0000-4000-8000-000000000001','family:82000000-0000-4000-8000-000000000001','presence'),0::bigint,'presence is outside broadcast authorization');
update public.family_members set status='removed';
select is(pg_temp.realtime_count('81000000-0000-4000-8000-000000000001','family:82000000-0000-4000-8000-000000000001'),0::bigint,'removed parent denied on authorization refresh');
select ok(exists(select 1 from pg_policies where schemaname='realtime' and tablename='messages' and policyname='harbor_family_broadcast_receive'), 'Harbor broadcast receive policy exists');
select * from finish();
rollback;
