-- A parent sets the name the rest of their family sees. Until now a profile's name could only be set by the account
-- trigger at sign-up, so a parent who signed up without one (every parent who signed up in the Android app, which
-- never asked) appeared to the others only as "A parent", with no way to correct it.
--
-- A parent may change their own row and nothing else: the grant is on display_name alone, so no client can reach
-- another column, and the policy is their own id on both sides. Who may read a name is unchanged.

grant update (display_name) on table public.profiles to authenticated;

create policy profiles_update_own
on public.profiles
for update
to authenticated
using ((select auth.uid()) = id)
with check ((select auth.uid()) = id);
