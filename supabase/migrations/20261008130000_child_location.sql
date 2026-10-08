-- Harbor: child device location reporting (additive).
--
-- * public.child_locations      latest known position per child, readable by
--                               active family parents through RLS.
-- * private.location_history    short-retention trail (7 days), service_role only.
-- * private.harbor_record_locations  atomic, idempotent batch ingest called by
--                               the report-location Edge Function after the
--                               device proof has been verified.

create table public.child_locations (
  child_id uuid primary key,
  family_id uuid not null,
  device_id uuid not null references public.devices_public(id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  accuracy_m real check (accuracy_m is null or (accuracy_m >= 0 and accuracy_m <= 100000)),
  battery_pct smallint check (battery_pct is null or battery_pct between 0 and 100),
  recorded_at timestamptz not null,
  received_at timestamptz not null default pg_catalog.now(),
  foreign key (family_id, child_id)
    references public.children(family_id, id) on delete cascade
);

create index child_locations_family_idx on public.child_locations(family_id);
create index child_locations_device_idx on public.child_locations(device_id);

alter table public.child_locations enable row level security;
revoke all on table public.child_locations from public, anon, authenticated;
grant select on table public.child_locations to authenticated;

create policy child_locations_select_active_family
on public.child_locations
for select
to authenticated
using (
  (select auth.uid()) is not null
  and authz.has_active_family_role(family_id, array['owner', 'parent']::text[])
);

create table private.location_history (
  id bigint generated always as identity primary key,
  device_id uuid not null references public.devices_public(id) on delete cascade,
  child_id uuid not null,
  family_id uuid not null,
  latitude double precision not null,
  longitude double precision not null,
  accuracy_m real,
  battery_pct smallint,
  recorded_at timestamptz not null,
  received_at timestamptz not null default pg_catalog.now(),
  unique (device_id, recorded_at),
  foreign key (family_id, child_id)
    references public.children(family_id, id) on delete cascade
);

create index location_history_child_time_idx
  on private.location_history(child_id, recorded_at desc);

revoke all on table private.location_history from public, anon, authenticated;
grant select, insert, delete on table private.location_history to service_role;

create or replace function private.harbor_record_locations(
  p_device_id uuid,
  p_points jsonb
)
returns table (accepted integer, rejected integer, latest_recorded_at timestamptz)
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_family_id uuid;
  v_child_id uuid;
  v_now timestamptz := pg_catalog.now();
  v_point jsonb;
  v_lat double precision;
  v_lng double precision;
  v_acc real;
  v_bat smallint;
  v_at timestamptz;
  v_accepted integer := 0;
  v_rejected integer := 0;
  v_newest_at timestamptz;
  v_newest record;
begin
  if p_device_id is null
     or p_points is null
     or pg_catalog.jsonb_typeof(p_points) <> 'array'
     or pg_catalog.jsonb_array_length(p_points) not between 1 and 50 then
    raise exception 'location batch is invalid' using errcode = '22023';
  end if;

  select d.family_id, d.child_id
    into v_family_id, v_child_id
  from public.devices_public d
  join private.device_security ds on ds.device_id = d.id
  where d.id = p_device_id
    and d.status = 'active'
    and d.revoked_at is null;

  if not found then
    raise exception 'DEVICE_REVOKED' using errcode = '42501';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('device-location:' || p_device_id::text, 0)
  );

  for v_point in select value from pg_catalog.jsonb_array_elements(p_points) loop
    begin
      if pg_catalog.jsonb_typeof(v_point) <> 'object'
         or pg_catalog.jsonb_typeof(v_point -> 'latitude') <> 'number'
         or pg_catalog.jsonb_typeof(v_point -> 'longitude') <> 'number'
         or pg_catalog.jsonb_typeof(v_point -> 'recordedAt') <> 'string' then
        v_rejected := v_rejected + 1;
        continue;
      end if;

      v_lat := (v_point ->> 'latitude')::double precision;
      v_lng := (v_point ->> 'longitude')::double precision;
      v_at := (v_point ->> 'recordedAt')::timestamptz;
      v_acc := case when pg_catalog.jsonb_typeof(v_point -> 'accuracyM') = 'number'
                    then (v_point ->> 'accuracyM')::real end;
      v_bat := case when pg_catalog.jsonb_typeof(v_point -> 'batteryPct') = 'number'
                    then pg_catalog.round((v_point ->> 'batteryPct')::numeric)::smallint end;

      if v_lat not between -90 and 90
         or v_lng not between -180 and 180
         or v_lat = 'NaN'::double precision
         or v_lng = 'NaN'::double precision
         or (v_acc is not null and (v_acc < 0 or v_acc > 100000))
         or (v_bat is not null and v_bat not between 0 and 100)
         or v_at > v_now + interval '5 minutes'
         or v_at < v_now - interval '7 days' then
        v_rejected := v_rejected + 1;
        continue;
      end if;
    exception when others then
      v_rejected := v_rejected + 1;
      continue;
    end;

    insert into private.location_history
      (device_id, child_id, family_id, latitude, longitude, accuracy_m, battery_pct, recorded_at, received_at)
    values (p_device_id, v_child_id, v_family_id, v_lat, v_lng, v_acc, v_bat, v_at, v_now)
    on conflict (device_id, recorded_at) do nothing;
    -- Duplicates (retries of an already stored point) count as accepted so the
    -- device can safely drop them from its queue.
    v_accepted := v_accepted + 1;
    if v_newest_at is null or v_at > v_newest_at then
      v_newest_at := v_at;
    end if;
  end loop;

  if v_newest_at is not null then
    select h.latitude, h.longitude, h.accuracy_m, h.battery_pct, h.recorded_at
      into v_newest
    from private.location_history h
    where h.device_id = p_device_id and h.recorded_at = v_newest_at;

    insert into public.child_locations
      (child_id, family_id, device_id, latitude, longitude, accuracy_m, battery_pct, recorded_at, received_at)
    values (v_child_id, v_family_id, p_device_id, v_newest.latitude, v_newest.longitude,
            v_newest.accuracy_m, v_newest.battery_pct, v_newest.recorded_at, v_now)
    on conflict (child_id) do update
      set device_id = excluded.device_id,
          latitude = excluded.latitude,
          longitude = excluded.longitude,
          accuracy_m = excluded.accuracy_m,
          battery_pct = excluded.battery_pct,
          recorded_at = excluded.recorded_at,
          received_at = excluded.received_at
      where public.child_locations.recorded_at < excluded.recorded_at;
  end if;

  update public.devices_public
     set last_seen_at = v_now
   where id = p_device_id;

  -- Retention: keep seven days of trail per device.
  delete from private.location_history
   where device_id = p_device_id
     and recorded_at < v_now - interval '7 days';

  return query select v_accepted, v_rejected, v_newest_at;
end;
$$;

revoke all on function private.harbor_record_locations(uuid, jsonb)
  from public, anon, authenticated;
grant execute on function private.harbor_record_locations(uuid, jsonb)
  to service_role;
