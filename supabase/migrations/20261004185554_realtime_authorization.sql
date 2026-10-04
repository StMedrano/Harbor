-- Supabase owns realtime.messages and already enables its RLS.
-- Receive-only broadcast authorization; no client publish/Presence policy.
create policy harbor_family_broadcast_receive
on realtime.messages for select to authenticated
using (
  extension = 'broadcast'
  and topic = (select realtime.topic())
  and coalesce((select auth.jwt())->>'is_anonymous', 'false') = 'false'
  and exists (
    select 1 from public.family_members fm
    where fm.user_id = (select auth.uid())
      and fm.status = 'active' and fm.role in ('owner', 'parent')
      and 'family:' || fm.family_id::text = (select realtime.topic())
  )
);
