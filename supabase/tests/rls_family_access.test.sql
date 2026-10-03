begin;

select plan(21);

select has_table('public', 'profiles', 'profiles table exists');
select has_table('public', 'families', 'families table exists');
select has_table('public', 'family_members', 'family_members table exists');
select has_table('public', 'children', 'children table exists');
select has_table('public', 'devices_public', 'devices_public table exists');

select ok(
  exists (
    select 1 from pg_constraint
    where conrelid = to_regclass('public.profiles') and contype = 'p'
  ),
  'profiles has a primary key'
);
select ok(
  exists (
    select 1 from pg_constraint
    where conrelid = to_regclass('public.families') and contype = 'p'
  ),
  'families has a primary key'
);
select ok(
  exists (
    select 1 from pg_constraint
    where conrelid = to_regclass('public.family_members') and contype = 'p'
  ),
  'family_members has a primary key'
);
select ok(
  exists (
    select 1 from pg_constraint
    where conrelid = to_regclass('public.children') and contype = 'p'
  ),
  'children has a primary key'
);
select ok(
  exists (
    select 1 from pg_constraint
    where conrelid = to_regclass('public.devices_public') and contype = 'p'
  ),
  'devices_public has a primary key'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('public.profiles')
      and c.contype = 'f'
      and c.confrelid = to_regclass('auth.users')
  ),
  'profiles id references auth.users'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('public.family_members')
      and c.contype = 'u'
      and pg_get_constraintdef(c.oid) = 'UNIQUE (family_id, user_id)'
  ),
  'family membership is unique per family and user'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('public.family_members')
      and c.contype = 'f'
      and c.confrelid = to_regclass('public.families')
      and pg_get_constraintdef(c.oid) like 'FOREIGN KEY (family_id)%'
  ),
  'family_members is scoped to a family'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('public.family_members')
      and c.contype = 'f'
      and c.confrelid = to_regclass('auth.users')
      and pg_get_constraintdef(c.oid) like 'FOREIGN KEY (user_id)%'
  ),
  'family_members user_id references auth.users'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('public.children')
      and c.contype = 'f'
      and c.confrelid = to_regclass('public.families')
      and pg_get_constraintdef(c.oid) like 'FOREIGN KEY (family_id)%'
  )
  and exists (
    select 1 from pg_attribute a
    where a.attrelid = to_regclass('public.children')
      and a.attname = 'family_id'
      and a.attnotnull
  ),
  'children are required to belong to a family'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('public.children')
      and c.contype = 'u'
      and pg_get_constraintdef(c.oid) = 'UNIQUE (family_id, id)'
  ),
  'children expose a unique family/id pair for same-family device integrity'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('public.devices_public')
      and c.contype = 'f'
      and c.confrelid = to_regclass('public.families')
      and pg_get_constraintdef(c.oid) like 'FOREIGN KEY (family_id)%'
  ),
  'devices_public is scoped to a family'
);

select ok(
  exists (
    select 1 from pg_constraint c
    where c.conrelid = to_regclass('public.devices_public')
      and c.contype = 'f'
      and c.confrelid = to_regclass('public.children')
      and pg_get_constraintdef(c.oid) like 'FOREIGN KEY (family_id, child_id)%'
  ),
  'device family_id and child_id must resolve to the same child family'
);

select ok(
  (
    select count(*) >= 2
    from pg_constraint c
    where c.conrelid = to_regclass('public.family_members')
      and c.contype = 'c'
      and (
        pg_get_constraintdef(c.oid) like '%owner%parent%'
        or pg_get_constraintdef(c.oid) like '%active%invited%removed%'
      )
  ),
  'family member role and status values are constrained'
);

select ok(
  (
    select count(*) >= 2
    from pg_constraint c
    where c.conrelid = to_regclass('public.devices_public')
      and c.contype = 'c'
      and (
        pg_get_constraintdef(c.oid) like '%unknown%standard%full%'
        or pg_get_constraintdef(c.oid) like '%active%revoked%'
      )
  ),
  'device supervision mode and status values are constrained'
);

select ok(
  exists (
    select 1
    from pg_trigger t
    where t.tgrelid = to_regclass('auth.users')
      and not t.tgisinternal
      and t.tgname = 'harbor_create_profile_after_signup'
  ),
  'auth signup safely provisions a Harbor profile'
);

select * from finish();
rollback;
