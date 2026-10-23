begin;

select plan(14);

insert into auth.users (id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous) values
  ('41000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated', 'invite-owner@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false),
  ('41000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated', 'invite-second@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false),
  ('41000000-0000-4000-8000-000000000003', 'authenticated', 'authenticated', 'invite-guesser@harbor.test', '{}'::jsonb, '{}'::jsonb, now(), now(), false);

select family_id as fid from private.create_family_atomic('41000000-0000-4000-8000-000000000001', 'Invite family', 'invite-test') \gset

select lives_ok(
  format($$select * from private.issue_parent_invite('41000000-0000-4000-8000-000000000001', %L, repeat('a', 64), now() + interval '24 hours')$$, :'fid'),
  'an owner gets an invite'
);
select throws_ok(
  format($$select * from private.issue_parent_invite('41000000-0000-4000-8000-000000000002', %L, repeat('b', 64), now() + interval '24 hours')$$, :'fid'),
  '42501', null, 'someone outside the family cannot make an invite'
);
select throws_ok(
  format($$select * from private.issue_parent_invite('41000000-0000-4000-8000-000000000001', %L, repeat('c', 64), now() + interval '3 days')$$, :'fid'),
  '22023', null, 'an invite cannot outlive a day'
);

select is((select outcome from private.accept_parent_invite('41000000-0000-4000-8000-000000000001', repeat('a', 64))), 'already_member',
  'the parent who made the code is already a member, and the code stays usable');
select is((select outcome from private.accept_parent_invite('41000000-0000-4000-8000-000000000002', repeat('a', 64))), 'joined',
  'another adult joins with the code');
select is((select role || '/' || status from public.family_members where family_id = :'fid' and user_id = '41000000-0000-4000-8000-000000000002'),
  'parent/active', 'they join as an active parent, not an owner');
select is((select outcome from private.accept_parent_invite('41000000-0000-4000-8000-000000000003', repeat('a', 64))), 'invalid',
  'a used code works only once');

-- A new code replaces the parent's earlier one.
select * from private.issue_parent_invite('41000000-0000-4000-8000-000000000001', :'fid', repeat('d', 64), now() + interval '24 hours');
select * from private.issue_parent_invite('41000000-0000-4000-8000-000000000001', :'fid', repeat('e', 64), now() + interval '24 hours');
select is((select outcome from private.accept_parent_invite('41000000-0000-4000-8000-000000000003', repeat('d', 64))), 'invalid',
  'a replaced code no longer works');

-- Wrong codes lock the account that tries them for an hour, even for a right code.
select private.accept_parent_invite('41000000-0000-4000-8000-000000000003', repeat('f', 64)) from generate_series(1, 8);
select is((select count(*)::int from private.parent_invite_failures where user_id = '41000000-0000-4000-8000-000000000003'), 10,
  'every wrong code counts');
select is((select outcome from private.accept_parent_invite('41000000-0000-4000-8000-000000000003', repeat('e', 64))), 'locked',
  'after 10 wrong codes in an hour even a right code is refused');
update private.parent_invite_failures set failed_at = now() - interval '2 hours' where user_id = '41000000-0000-4000-8000-000000000003';
select is((select outcome from private.accept_parent_invite('41000000-0000-4000-8000-000000000003', repeat('e', 64))), 'joined',
  'the lock ends after an hour');

select * from private.issue_parent_invite('41000000-0000-4000-8000-000000000002', :'fid', repeat('9', 64), now() + interval '24 hours');
update private.parent_invites set expires_at = now() - interval '1 minute' where code_digest = repeat('9', 64);
select is((select outcome from private.accept_parent_invite('41000000-0000-4000-8000-000000000001', repeat('9', 64))), 'invalid',
  'an expired code does not work');

set local role authenticated;
select throws_ok($$select count(*) from private.parent_invites$$, '42501', null, 'clients cannot read invites');
select throws_ok($$select * from private.accept_parent_invite('41000000-0000-4000-8000-000000000002', repeat('a', 64))$$, '42501', null,
  'clients cannot call the invite functions directly');
reset role;

select * from finish();
rollback;
