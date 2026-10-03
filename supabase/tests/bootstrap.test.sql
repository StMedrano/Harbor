begin;

select plan(1);

select pass('Harbor Supabase pgTAP harness is running');

select * from finish();

rollback;
