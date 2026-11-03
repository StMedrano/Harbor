-- Two tables grew forever: every signed child request leaves a nonce, and every queued push leaves a row.
--
-- A nonce only has to outlive its request's time check: device-proof.ts refuses a timestamp more than 5 minutes from
-- now before it looks at the nonce, so an older one can never be replayed. A day is well past that.
--
-- A finished push is kept 30 days to look back on. Nothing reads one that old: the rate limits on alert and
-- controls-off pushes look back 30 minutes at most, and only pushes queued in the last 15 minutes are ever sent.
-- Pushes still pending, retrying or being sent are left alone, however old.
create function private.harbor_purge_request_nonces() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint;
begin
 delete from private.device_request_nonces where claimed_at<=pg_catalog.clock_timestamp()-interval '1 day';
 get diagnostics n=row_count;
 return n;
end $$;

create function private.harbor_purge_finished_notifications() returns bigint
language plpgsql security invoker set search_path='' as $$
declare n bigint;
begin
 delete from private.notification_outbox
 where status in ('sent','dead_letter') and updated_at<=pg_catalog.clock_timestamp()-interval '30 days';
 get diagnostics n=row_count;
 return n;
end $$;

revoke all on function private.harbor_purge_request_nonces(),private.harbor_purge_finished_notifications() from public,anon,authenticated,service_role;
-- Runs as the migration owner; no client role can schedule or invoke cleanup.
select cron.schedule('harbor-nonce-retention','33 3 * * *','select private.harbor_purge_request_nonces();');
select cron.schedule('harbor-outbox-retention','37 3 * * *','select private.harbor_purge_finished_notifications();');
