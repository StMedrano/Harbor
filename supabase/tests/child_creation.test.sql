begin;
select plan(16);
insert into auth.users(id,is_anonymous) values
('20000000-0000-4000-8000-000000000101',false),
('20000000-0000-4000-8000-000000000102',false),
('20000000-0000-4000-8000-000000000103',true);
insert into public.families(id,name) values
('20000000-0000-4000-8000-000000000111','Child creation test'),
('20000000-0000-4000-8000-000000000112','Foreign child test');
insert into public.family_members(family_id,user_id,role,status) values
('20000000-0000-4000-8000-000000000111','20000000-0000-4000-8000-000000000101','owner','active'),
('20000000-0000-4000-8000-000000000111','20000000-0000-4000-8000-000000000102','parent','removed'),
('20000000-0000-4000-8000-000000000111','20000000-0000-4000-8000-000000000103','owner','active');
create function pg_temp.child_call(actor uuid,family uuid,name text,key text,fingerprint text) returns jsonb language plpgsql as $$
declare result jsonb;
begin
 execute 'select to_jsonb(c) from private.harbor_create_child($1,$2,$3,$4,$5) c' into result using actor,family,name,key,fingerprint;
 return result;
exception when others then return jsonb_build_object('error',SQLSTATE,'message',SQLERRM);
end $$;
create function pg_temp.child_request_count() returns bigint language plpgsql as $$
declare result bigint;
begin execute 'select count(*) from private.child_creation_requests' into result; return result;
exception when others then return -1; end $$;
select ok(to_regclass('private.child_creation_requests') is not null,'durable private child request identity exists');
select ok(to_regprocedure('private.harbor_create_child(uuid,uuid,text,text,text)') is not null,'atomic child helper exists');
select ok(case when to_regclass('private.child_creation_requests') is null then false else not has_table_privilege('authenticated',to_regclass('private.child_creation_requests'),'SELECT,INSERT,UPDATE,DELETE') end,'no authenticated request grants');
select ok(case when to_regprocedure('private.harbor_create_child(uuid,uuid,text,text,text)') is null then false else not has_function_privilege('authenticated',to_regprocedure('private.harbor_create_child(uuid,uuid,text,text,text)'),'EXECUTE') end,'no authenticated helper grant');
select ok(case when to_regprocedure('private.harbor_create_child(uuid,uuid,text,text,text)') is null then false else not has_function_privilege('anon',to_regprocedure('private.harbor_create_child(uuid,uuid,text,text,text)'),'EXECUTE') end,'no anon helper grant');
create temporary table results(attempt integer,result jsonb);
insert into results values(1,pg_temp.child_call('20000000-0000-4000-8000-000000000101','20000000-0000-4000-8000-000000000111','Alex','child-one',repeat('a',64)));
insert into results values(2,pg_temp.child_call('20000000-0000-4000-8000-000000000101','20000000-0000-4000-8000-000000000111','Alex','child-one',repeat('a',64)));
select ok((select result->>'id' is not null from results where attempt=1),'created result has canonical child ID');
select is((select result from results where attempt=1),(select result from results where attempt=2),'retry returns the same canonical child');
select is((select count(*) from public.children where family_id='20000000-0000-4000-8000-000000000111'),1::bigint,'one child across duplicate requests');
select is(pg_temp.child_request_count(),1::bigint,'one durable request across retries');
select is((select count(*) from private.audit_events where event_kind='child.created' and family_id='20000000-0000-4000-8000-000000000111'),1::bigint,'one creation audit');
select ok((select bool_and(metadata='{}'::jsonb) from private.audit_events where event_kind='child.created' and family_id='20000000-0000-4000-8000-000000000111'),'audit excludes names and payloads');
select is(pg_temp.child_call('20000000-0000-4000-8000-000000000101','20000000-0000-4000-8000-000000000111','Changed','child-one',repeat('b',64))->>'message','IDEMPOTENCY_CONFLICT','changed payload conflicts');
select is(pg_temp.child_call('20000000-0000-4000-8000-000000000101','20000000-0000-4000-8000-000000000112','Alex','foreign',repeat('a',64))->>'error','42501','known foreign family denied');
select is(pg_temp.child_call('20000000-0000-4000-8000-000000000102','20000000-0000-4000-8000-000000000111','Alex','removed',repeat('a',64))->>'error','42501','removed parent denied');
select is(pg_temp.child_call('20000000-0000-4000-8000-000000000103','20000000-0000-4000-8000-000000000111','Alex','anonymous',repeat('a',64))->>'error','42501','anonymous identity denied despite fabricated membership');
select is((select count(*) from public.children where family_id in ('20000000-0000-4000-8000-000000000111','20000000-0000-4000-8000-000000000112')),1::bigint,'denied calls leave no child side effects');
select * from finish();
rollback;
