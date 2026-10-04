begin;
select plan(8);

create temporary table recovery_ids as
select private.harbor_enqueue_notification('recovery-test', 'web_push', '{"subscriptionId":"22222222-2222-4222-8222-222222222222"}'::jsonb, '{"version":1,"kind":"changed"}'::jsonb) as id;
create temporary table recovery_claims as
select * from private.harbor_claim_notification((select id from recovery_ids), now());
select ok((select next_attempt_at > now() from recovery_claims), 'processing claim has a bounded recovery lease');
select is((select count(*) from private.harbor_claim_notification((select id from recovery_ids), now())), 0::bigint, 'live lease cannot be reclaimed');

update private.notification_outbox set next_attempt_at = now() - interval '1 second' where id = (select id from recovery_ids);
create temporary table recovered_claims as
select * from private.harbor_claim_notification((select id from recovery_ids), now());
select is((select count(*) from recovered_claims), 1::bigint, 'expired processing lease is recoverable');
select is((select attempt_count from recovered_claims), 2, 'recovery increments the fencing attempt');

create function pg_temp.complete_recovery(p_id uuid, p_attempt integer) returns text language plpgsql as $$
declare result text;
begin
  execute 'select private.harbor_complete_notification($1, $2)' into result using p_id, p_attempt;
  return result;
exception when undefined_function then return null;
end;
$$;
create function pg_temp.fail_recovery(p_id uuid, p_attempt integer) returns text language plpgsql as $$
declare result text;
begin
  execute 'select private.harbor_fail_notification($1, $2, true, ''provider_error'', now())' into result using p_id, p_attempt;
  return result;
exception when undefined_function then return null;
end;
$$;
select is(pg_temp.complete_recovery((select id from recovery_ids), 1), null::text, 'stale claimant cannot complete recovered work');
select is(pg_temp.fail_recovery((select id from recovery_ids), 1), null::text, 'stale claimant cannot change recovered retry state');
select is(pg_temp.complete_recovery((select id from recovery_ids), 2), 'sent', 'current claimant can complete recovered work');
select is(pg_temp.complete_recovery((select id from recovery_ids), 2), 'sent', 'current completion remains idempotent');

select * from finish();
rollback;
