-- Runs as the migration owner; no client role can schedule or invoke cleanup.
create extension if not exists pg_cron with schema pg_catalog;
select cron.schedule('harbor-usage-retention','17 3 * * *','select private.harbor_purge_device_usage();');
