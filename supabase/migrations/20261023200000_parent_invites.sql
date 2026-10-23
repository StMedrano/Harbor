-- A parent invites another adult into the family. The parent gets a one-time code (8 letters and digits, 24 hours);
-- the other adult signs in with their own account and enters it, and joins as a parent with the same access as the
-- owner. Only a keyed digest of the code is stored. Wrong codes count against the account trying them: after 10 in
-- an hour every code is refused for that account until the hour is over, so codes cannot be guessed.

create table private.parent_invites (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  created_by uuid not null references auth.users(id) on delete cascade,
  code_digest text not null unique check (code_digest ~ '^[0-9a-f]{64}$'),
  expires_at timestamptz not null,
  consumed_at timestamptz,
  consumed_by uuid references auth.users(id) on delete set null,
  revoked_at timestamptz,
  created_at timestamptz not null default pg_catalog.now()
);
create index parent_invites_family_idx on private.parent_invites(family_id, created_at desc);

create table private.parent_invite_failures (
  user_id uuid not null references auth.users(id) on delete cascade,
  failed_at timestamptz not null default pg_catalog.now()
);
create index parent_invite_failures_user_idx on private.parent_invite_failures(user_id, failed_at desc);

alter table private.parent_invites enable row level security;
alter table private.parent_invite_failures enable row level security;
revoke all on table private.parent_invites, private.parent_invite_failures from public, anon, authenticated;
grant select, insert, update, delete on table private.parent_invites, private.parent_invite_failures to service_role;

-- One live code per parent per family: a new code replaces the parent's earlier ones.
create function private.issue_parent_invite(p_user_id uuid, p_family_id uuid, p_code_digest text, p_expires_at timestamptz)
returns table(family_id uuid, expires_at timestamptz)
language plpgsql security invoker set search_path = '' as $$
declare v_id uuid;
begin
  if p_user_id is null or p_family_id is null or coalesce(p_code_digest, '') !~ '^[0-9a-f]{64}$' then
    raise exception 'invite input is invalid' using errcode = '22023';
  end if;
  if p_expires_at is null or p_expires_at <= pg_catalog.now() or p_expires_at > pg_catalog.now() + interval '25 hours' then
    raise exception 'invite expiry is invalid' using errcode = '22023';
  end if;
  if not exists (select 1 from public.family_members fm where fm.family_id = p_family_id and fm.user_id = p_user_id
      and fm.status = 'active' and fm.role in ('owner', 'parent')) then
    raise exception 'family is not authorized for parent' using errcode = '42501';
  end if;
  update private.parent_invites i set revoked_at = pg_catalog.now()
  where i.family_id = p_family_id and i.created_by = p_user_id and i.consumed_at is null and i.revoked_at is null;
  insert into private.parent_invites (family_id, created_by, code_digest, expires_at)
  values (p_family_id, p_user_id, p_code_digest, p_expires_at) returning id into v_id;
  insert into private.audit_events (event_kind, family_id, actor_user_id, resource_type, resource_id, metadata)
  values ('family.parent_invite_created', p_family_id, p_user_id, 'parent_invite', v_id, '{}'::jsonb);
  return query select p_family_id, p_expires_at;
end $$;

-- outcome: joined, already_member, invalid (wrong, used, revoked or expired code) or locked (too many wrong codes).
create function private.accept_parent_invite(p_user_id uuid, p_code_digest text)
returns table(family_id uuid, family_name text, outcome text)
language plpgsql security invoker set search_path = '' as $$
declare v_invite private.parent_invites%rowtype; v_member public.family_members%rowtype;
begin
  if p_user_id is null then raise exception 'user id is required' using errcode = '22023'; end if;
  -- One account's attempts run one at a time, so the count below cannot be raced.
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('parent-invite:' || p_user_id::text, 0));
  delete from private.parent_invite_failures f where f.user_id = p_user_id and f.failed_at < pg_catalog.now() - interval '1 day';
  if (select count(*) from private.parent_invite_failures f
      where f.user_id = p_user_id and f.failed_at > pg_catalog.now() - interval '1 hour') >= 10 then
    return query select null::uuid, null::text, 'locked'::text; return;
  end if;
  select * into v_invite from private.parent_invites i
  where i.code_digest = coalesce(p_code_digest, '') and i.consumed_at is null and i.revoked_at is null and i.expires_at > pg_catalog.now()
  for update;
  if not found then
    insert into private.parent_invite_failures (user_id) values (p_user_id);
    return query select null::uuid, null::text, 'invalid'::text; return;
  end if;
  select * into v_member from public.family_members fm where fm.family_id = v_invite.family_id and fm.user_id = p_user_id;
  if found and v_member.status = 'active' then
    -- Already in this family (for example the parent who made the code): the code stays usable for the person it is for.
    return query select v_invite.family_id, (select f.name from public.families f where f.id = v_invite.family_id), 'already_member'::text;
    return;
  end if;
  if found then
    update public.family_members fm set status = 'active', role = 'parent', updated_at = pg_catalog.now() where fm.id = v_member.id;
  else
    insert into public.family_members (family_id, user_id, role, status) values (v_invite.family_id, p_user_id, 'parent', 'active');
  end if;
  update private.parent_invites i set consumed_at = pg_catalog.now(), consumed_by = p_user_id where i.id = v_invite.id;
  insert into private.audit_events (event_kind, family_id, actor_user_id, resource_type, resource_id, metadata)
  values ('family.parent_joined', v_invite.family_id, p_user_id, 'parent_invite', v_invite.id, '{}'::jsonb);
  return query select v_invite.family_id, (select f.name from public.families f where f.id = v_invite.family_id), 'joined'::text;
end $$;

revoke all on function private.issue_parent_invite(uuid, uuid, text, timestamptz), private.accept_parent_invite(uuid, text) from public, anon, authenticated;
grant execute on function private.issue_parent_invite(uuid, uuid, text, timestamptz), private.accept_parent_invite(uuid, text) to service_role;
