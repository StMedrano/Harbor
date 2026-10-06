begin;
select plan(26);
insert into auth.users(id,is_anonymous) values('30000000-0000-4000-8000-000000000101',false),('30000000-0000-4000-8000-000000000102',false);
insert into auth.sessions(id,user_id,created_at,updated_at,not_after) values
('30000000-0000-4000-8000-000000000111','30000000-0000-4000-8000-000000000101',now(),now(),now()+interval '1 hour'),
('30000000-0000-4000-8000-000000000112','30000000-0000-4000-8000-000000000102',now(),now(),null),
('30000000-0000-4000-8000-000000000113','30000000-0000-4000-8000-000000000101',now(),now(),now()-interval '1 minute');
create function pg_temp.fcm_register(actor uuid,session uuid,installation text,token text) returns jsonb language plpgsql as $$
declare result jsonb;
begin execute 'select to_jsonb(r) from private.harbor_register_parent_fcm($1,$2,$3,$4) r' into result using actor,session,installation,token;return result;
exception when others then return jsonb_build_object('error',SQLSTATE);end $$;
create function pg_temp.session_active(actor uuid,session uuid) returns boolean language plpgsql as $$
declare result boolean;
begin execute 'select private.harbor_parent_session_active($1,$2)' into result using actor,session;return result;exception when others then return false;end $$;
create function pg_temp.fcm_count(query text) returns bigint language plpgsql as $$
declare result bigint;begin execute query into result;return result;exception when others then return -1;end $$;
create function pg_temp.fcm_remove(actor uuid,installation text) returns boolean language plpgsql as $$
begin execute 'select private.harbor_remove_parent_fcm($1,$2)' using actor,installation;return true;exception when others then return false;end $$;
create function pg_temp.fcm_mutation_rejected(query text) returns boolean language plpgsql as $$
begin execute query;return false;exception when others then return SQLSTATE='22023';end $$;
select ok(to_regclass('private.parent_fcm_registrations') is not null,'parent FCM registry is private and durable');
select ok(to_regprocedure('private.harbor_register_parent_fcm(uuid,uuid,text,text)') is not null,'server-only parent registration helper exists');
select ok(case when to_regclass('private.parent_fcm_registrations') is null then false else not has_table_privilege('anon',to_regclass('private.parent_fcm_registrations'),'SELECT,INSERT,UPDATE,DELETE') end,'anon has no registry grants');
select ok(case when to_regclass('private.parent_fcm_registrations') is null then false else not has_table_privilege('authenticated',to_regclass('private.parent_fcm_registrations'),'SELECT,INSERT,UPDATE,DELETE') end,'authenticated has no registry grants');
select ok(case when to_regprocedure('private.harbor_register_parent_fcm(uuid,uuid,text,text)') is null then false else not has_function_privilege('authenticated',to_regprocedure('private.harbor_register_parent_fcm(uuid,uuid,text,text)'),'EXECUTE') end,'no direct authenticated registration execution');
select ok(pg_temp.session_active('30000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000111'),'verified current actor/session is active');
select ok(not pg_temp.session_active('30000000-0000-4000-8000-000000000102','30000000-0000-4000-8000-000000000111'),'session cannot belong to another subject');
select ok(not pg_temp.session_active('30000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000113'),'time-boxed expired session is denied despite retained row');
select ok(not pg_temp.session_active('30000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000119'),'removed session is denied');
create temporary table results(n int,result jsonb);
insert into results values(1,pg_temp.fcm_register('30000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000111','one','parent-fcm-fixture-one'));
insert into results values(2,pg_temp.fcm_register('30000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000111','one','parent-fcm-fixture-one'));
select ok((select result->>'registration_id' is not null and result->>'active'='true' from results where n=1),'registration confirms durable immutable ID');
select is((select result from results where n=1),(select result from results where n=2),'identical registration is idempotent');
select is(pg_temp.fcm_count('select count(*) from private.parent_fcm_registrations'),1::bigint,'one registration across repeated requests');
insert into results values(3,pg_temp.fcm_register('30000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000111','two','parent-fcm-fixture-two'));
select is(pg_temp.fcm_count('select count(*) from private.parent_fcm_registrations where active'),2::bigint,'two installations can remain independently active');
insert into results values(4,pg_temp.fcm_register('30000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000111','one','parent-fcm-fixture-rotated'));
select is((select result->>'registration_id' from results where n=1),(select result->>'registration_id' from results where n=4),'token rotation preserves same owner/registration ID');
select is(pg_temp.fcm_count($q$select count(*) from private.parent_fcm_registrations where token='parent-fcm-fixture-rotated' and active$q$),1::bigint,'rotation persists current token');
select is(pg_temp.fcm_register('30000000-0000-4000-8000-000000000102','30000000-0000-4000-8000-000000000111','wrong','unused')->>'error','42501','registration rechecks session owner atomically');
select is(pg_temp.fcm_register('30000000-0000-4000-8000-000000000101','30000000-0000-4000-8000-000000000113','expired','unused')->>'error','42501','registration rejects time-box expiry');
insert into results values(5,pg_temp.fcm_register('30000000-0000-4000-8000-000000000102','30000000-0000-4000-8000-000000000112','one','parent-fcm-fixture-rotated'));
select is(pg_temp.fcm_count($q$select count(*) from private.parent_fcm_registrations where token='parent-fcm-fixture-rotated' and active$q$),1::bigint,'one active token owner after account switch');
select is(pg_temp.fcm_count($q$select count(*) from private.parent_fcm_registrations where user_id='30000000-0000-4000-8000-000000000101' and client_installation_id='one' and not active$q$),1::bigint,'old owner binding stays inactive');
select ok(pg_temp.fcm_mutation_rejected($q$update private.parent_fcm_registrations set user_id='30000000-0000-4000-8000-000000000102' where client_installation_id='two'$q$),'owner identity cannot be rewritten');
select ok(pg_temp.fcm_mutation_rejected($q$update private.parent_fcm_registrations set id=gen_random_uuid() where client_installation_id='two'$q$),'registration identity cannot be rewritten');
select ok(pg_temp.fcm_remove('30000000-0000-4000-8000-000000000102','two') and pg_temp.fcm_count($q$select count(*) from private.parent_fcm_registrations where client_installation_id='two' and active$q$)=1,'wrong-owner removal has no side effects');
select ok(pg_temp.fcm_remove('30000000-0000-4000-8000-000000000101','two') and pg_temp.fcm_remove('30000000-0000-4000-8000-000000000101','two') and pg_temp.fcm_count($q$select count(*) from private.parent_fcm_registrations where client_installation_id='two' and active$q$)=0,'explicit/repeated removal is idempotent');
select ok((select bool_and(not(result ? 'token') and not(result ? 'token_hash')) from results),'registration replies expose no token or hash');
select ok((select bool_and(metadata::text not like '%parent-fcm-fixture%') from private.audit_events where event_kind like 'parent.fcm.%'),'audit metadata excludes token material');
delete from auth.sessions where id='30000000-0000-4000-8000-000000000111';
select is(pg_temp.fcm_count($q$select count(*) from private.parent_fcm_registrations where user_id='30000000-0000-4000-8000-000000000101'$q$),0::bigint,'session FK removes bindings when Auth session is deleted');
select * from finish();rollback;
