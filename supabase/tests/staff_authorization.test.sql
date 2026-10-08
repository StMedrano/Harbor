begin;

select plan(15);

select ok(to_regnamespace('private') is not null, 'private schema exists');
select has_table('private', 'staff_authorizations', 'private staff authorization table exists');

select ok(
  exists (
    select 1 from pg_constraint
    where conrelid = to_regclass('private.staff_authorizations') and contype = 'p'
  ),
  'staff_authorizations has a primary key'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('private.staff_authorizations')
      and c.contype = 'f'
      and c.confrelid = to_regclass('auth.users')
  ),
  'staff authorization belongs to a Supabase Auth user'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('private.staff_authorizations')
      and c.contype = 'c'
      and pg_get_constraintdef(c.oid) like '%support%admin%'
  ),
  'staff role is constrained to support or admin'
);

select ok(
  case when to_regnamespace('private') is null then false
       else not has_schema_privilege('anon', 'private', 'USAGE') end,
  'anon has no private schema usage'
);
select ok(
  case when to_regnamespace('private') is null then false
       else not has_schema_privilege('authenticated', 'private', 'USAGE') end,
  'authenticated has no private schema usage'
);

select ok(
  case when to_regclass('private.staff_authorizations') is null then false
       else not has_table_privilege('anon', 'private.staff_authorizations', 'SELECT') end,
  'anon cannot select staff authorization state'
);
select ok(
  case when to_regclass('private.staff_authorizations') is null then false
       else not has_table_privilege('anon', 'private.staff_authorizations', 'INSERT') end,
  'anon cannot insert staff authorization state'
);
select ok(
  case when to_regclass('private.staff_authorizations') is null then false
       else not has_table_privilege('anon', 'private.staff_authorizations', 'UPDATE') end,
  'anon cannot update staff authorization state'
);
select ok(
  case when to_regclass('private.staff_authorizations') is null then false
       else not has_table_privilege('anon', 'private.staff_authorizations', 'DELETE') end,
  'anon cannot delete staff authorization state'
);

select ok(
  case when to_regclass('private.staff_authorizations') is null then false
       else not has_table_privilege('authenticated', 'private.staff_authorizations', 'SELECT') end,
  'authenticated cannot select staff authorization state'
);
select ok(
  case when to_regclass('private.staff_authorizations') is null then false
       else not has_table_privilege('authenticated', 'private.staff_authorizations', 'INSERT') end,
  'authenticated cannot insert staff authorization state'
);
select ok(
  case when to_regclass('private.staff_authorizations') is null then false
       else not has_table_privilege('authenticated', 'private.staff_authorizations', 'UPDATE') end,
  'authenticated cannot update staff authorization state'
);
select ok(
  case when to_regclass('private.staff_authorizations') is null then false
       else not has_table_privilege('authenticated', 'private.staff_authorizations', 'DELETE') end,
  'authenticated cannot delete staff authorization state'
);

select * from finish();
rollback;
