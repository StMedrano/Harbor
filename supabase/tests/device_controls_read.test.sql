begin;
select plan(9);

insert into auth.users (id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous) values
  ('81000000-0000-4000-8000-000000000001','authenticated','authenticated','ctl-parent@harbor.test','{}','{}',now(),now(),false),
  ('81000000-0000-4000-8000-000000000002','authenticated','authenticated','ctl-outsider@harbor.test','{}','{}',now(),now(),false),
  ('81000000-0000-4000-8000-000000000101','authenticated','authenticated',null,'{}','{}',now(),now(),true),
  ('81000000-0000-4000-8000-000000000102','authenticated','authenticated',null,'{}','{}',now(),now(),true);
insert into public.families (id, name) values ('82000000-0000-4000-8000-000000000001','Ctl Family'),('82000000-0000-4000-8000-000000000002','Other');
insert into public.family_members (family_id, user_id, role, status) values
  ('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','owner','active'),
  ('82000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000002','owner','active');
insert into public.children (id, family_id, display_name) values
  ('83000000-0000-4000-8000-000000000001','82000000-0000-4000-8000-000000000001','Ada'),
  ('83000000-0000-4000-8000-000000000002','82000000-0000-4000-8000-000000000001','Ben');
insert into public.devices_public (id, family_id, child_id, display_name, supervision_mode, status) values
  ('84000000-0000-4000-8000-000000000001','82000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001','Ada phone','full','active'),
  ('84000000-0000-4000-8000-000000000002','82000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000002','Ben phone','full','active');
insert into private.device_security (device_id, auth_user_id, public_key_spki) values
  ('84000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000101','fx'),
  ('84000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000102','fx');

create function pg_temp.call(sql text) returns jsonb language plpgsql as $$
declare r jsonb;
begin execute 'select coalesce(jsonb_agg(to_jsonb(t)), ''[]'') from (' || sql || ') t' into r; return r;
exception when others then return jsonb_build_object('error', SQLSTATE, 'message', SQLERRM); end $$;

select is(jsonb_array_length(pg_temp.call($$select * from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001')$$)),2,'parent sees both devices, even before any state exists');
select is((pg_temp.call($$select desired_state_version from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001') order by device_id$$))->0->>'desired_state_version','0','devices without state report version 0');

select private.harbor_update_device_desired_state('84000000-0000-4000-8000-000000000001','82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','{"controls":{"paused":true}}'::jsonb,0);
select is((pg_temp.call($$select desired_state from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001') where device_id='84000000-0000-4000-8000-000000000001'$$))->0->'desired_state'->'controls'->>'paused','true','written controls read back');
select is((pg_temp.call($$select desired_state_version from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001') where device_id='84000000-0000-4000-8000-000000000001'$$))->0->>'desired_state_version','1','version advanced');
select is((pg_temp.call($$select acknowledged_version from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001') where device_id='84000000-0000-4000-8000-000000000001'$$))->0->>'acknowledged_version','0','not acknowledged until the phone syncs');

select private.harbor_sync_device('84000000-0000-4000-8000-000000000001', 1, '{}'::uuid[]);
select is((pg_temp.call($$select acknowledged_version from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001') where device_id='84000000-0000-4000-8000-000000000001'$$))->0->>'acknowledged_version','1','phone acknowledgement is visible to parents');

select is(pg_temp.call($$select * from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000002')$$)->>'error','42501','another family cannot read');
select is(pg_temp.call($$select * from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000101')$$)->>'error','42501','a child device identity cannot read');

update public.devices_public set status='revoked', revoked_at=now() where id='84000000-0000-4000-8000-000000000002';
select is(jsonb_array_length(pg_temp.call($$select * from private.harbor_list_family_device_states('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001')$$)),1,'revoked devices are not listed');

select * from finish();
rollback;
