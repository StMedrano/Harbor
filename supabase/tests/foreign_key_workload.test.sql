begin;
select plan(6);

-- Catalog proof: a full valid leading-column index supports FK cleanup.
select ok(exists (
  select 1 from pg_index i join pg_attribute a
    on a.attrelid = i.indrelid and a.attnum = i.indkey[0]
  where i.indrelid = 'private.device_enrollment_tokens'::regclass
    and a.attname = 'family_id' and i.indisvalid and i.indpred is null
), 'enrollment family cleanup has a full leading-column index');
select ok(exists (
  select 1 from pg_index i join pg_attribute a
    on a.attrelid = i.indrelid and a.attnum = i.indkey[0]
  where i.indrelid = 'private.device_enrollment_tokens'::regclass
    and a.attname = 'issued_by_user_id' and i.indisvalid and i.indpred is null
), 'enrollment issuer cleanup has a full leading-column index');

-- Synthetic workload uses copies of the real schema/indexes, never live users.
-- LIKE does not copy foreign keys, triggers or RLS. Everything rolls back.
create temporary table enrollment_workload
  (like private.device_enrollment_tokens including all);
insert into enrollment_workload
  (family_id, child_id, issued_by_user_id, code_digest, expires_at)
select md5('family-' || (n / 10))::uuid, md5('child-' || n)::uuid,
       md5('issuer-' || (n / 20))::uuid, md5('code-' || n),
       now() + interval '1 hour'
from generate_series(1, 20000) n;
analyze enrollment_workload;
create temporary table device_workload
  (like public.devices_public including all);
insert into device_workload (family_id, child_id, display_name)
select md5('family-' || (n / 10))::uuid, md5('child-' || n)::uuid,
       'Synthetic device'
from generate_series(1, 10000) n cross join generate_series(1, 3) d;
analyze device_workload;

create function pg_temp.workload_plan(p_query text)
returns jsonb language plpgsql as $$
declare result jsonb;
begin
  execute 'explain (analyze, buffers, format json) ' || p_query into result;
  return result -> 0 -> 'Plan';
end;
$$;
create temporary table workload_plans as
select 'family' as kind, pg_temp.workload_plan($query$
  select id from enrollment_workload
  where family_id = md5('family-1244')::uuid
$query$) as plan
union all
select 'issuer', pg_temp.workload_plan($query$
  select id from enrollment_workload
  where issued_by_user_id = md5('issuer-622')::uuid
$query$)
union all
select 'device', pg_temp.workload_plan($query$
  select id from device_workload
  where family_id = md5('family-124')::uuid
    and child_id = md5('child-1244')::uuid
$query$);

select ok(plan::text not like '%Seq Scan%' and plan::text like '%Index Cond%'
  and (plan ->> 'Actual Rows')::integer = 10,
  'selective family cleanup uses an index across 20000 enrollment tokens')
from workload_plans where kind = 'family';
select ok(plan::text not like '%Seq Scan%' and plan::text like '%Index Cond%'
  and (plan ->> 'Actual Rows')::integer = 20,
  'selective issuer cleanup uses an index across 20000 enrollment tokens')
from workload_plans where kind = 'issuer';
select ok(plan::text not like '%Seq Scan%' and plan::text like '%Index Cond%'
  and plan::text like '%child_id%' and (plan ->> 'Actual Rows')::integer = 3,
  'existing device child index handles the composite lookup across 30000 devices')
from workload_plans where kind = 'device';
select ok(not has_table_privilege('authenticated',
  'private.device_enrollment_tokens', 'SELECT,INSERT,UPDATE,DELETE'),
  'index tuning preserves private enrollment client denial');
select diag(jsonb_build_object(
  'workload', kind, 'node', plan ->> 'Node Type',
  'actual_rows', plan -> 'Actual Rows',
  'rows_removed', coalesce(plan -> 'Rows Removed by Filter', '0'::jsonb),
  'shared_hit_blocks', plan -> 'Shared Hit Blocks',
  'local_hit_blocks', plan -> 'Local Hit Blocks'
)::text) from workload_plans order by kind;
select * from finish();
rollback;