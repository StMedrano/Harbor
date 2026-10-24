-- A parent reading Harbor in a browser was never notified of anything. The three pushes a parent cares about are
-- enqueued by these functions, and every one of them fanned out to parent FCM only, so a web push subscription could
-- be perfectly healthy and still never receive a single row. Android worked; the browser never had a chance.
--
-- A subscription stands on its own: unlike an FCM registration there is no app session to be active, which is the
-- point of a browser whose tab may be closed. The target shape is {subscriptionId}, which the dispatcher resolves.

create or replace function private.harbor_enqueue_alert_push(d uuid, e uuid, q bigint)
returns void language plpgsql security definer set search_path to '' as $function$
declare dev public.devices_public%rowtype; key text; route jsonb; r record; w record;
begin
 select * into dev from public.devices_public where id=d;
 if not found or dev.status='revoked' or dev.revoked_at is not null then return;end if;
 if exists(select 1 from private.notification_outbox o where o.event_key like 'device-alert:'||d::text||':%' and o.created_at>pg_catalog.clock_timestamp()-interval '5 minutes') then return;end if;
 key:='device-alert:'||d::text||':'||e::text||':'||q::text;
 route:=jsonb_build_object('version',1,'kind','device.alert.created','familyId',dev.family_id,'childId',dev.child_id,'deviceId',dev.id,'resourceId',pg_catalog.gen_random_uuid());
 for r in
  select g.id,g.user_id from private.parent_fcm_registrations g
  join public.family_members fm on fm.user_id=g.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and g.active and private.harbor_parent_session_active(g.user_id,g.session_id)
 loop
  perform private.harbor_enqueue_notification(key,'fcm',jsonb_build_object('parentFcmRegistrationId',r.id,'userId',r.user_id),route);
 end loop;
 for w in
  select s.id from private.parent_web_push_subscriptions s
  join public.family_members fm on fm.user_id=s.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and s.status='active'
 loop
  perform private.harbor_enqueue_notification(key,'web_push',jsonb_build_object('subscriptionId',w.id),route);
 end loop;
end $function$;

create or replace function private.harbor_enqueue_access_request_push(d uuid, r uuid)
returns void language plpgsql security definer set search_path to '' as $function$
declare dev public.devices_public%rowtype; route jsonb; g record; w record;
begin
 select * into dev from public.devices_public where id=d;
 if not found or dev.status='revoked' or dev.revoked_at is not null then return;end if;
 route:=jsonb_build_object('version',1,'kind','device.access.requested','familyId',dev.family_id,'childId',dev.child_id,'deviceId',dev.id,'resourceId',r);
 for g in
  select p.id,p.user_id from private.parent_fcm_registrations p
  join public.family_members fm on fm.user_id=p.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and p.active and private.harbor_parent_session_active(p.user_id,p.session_id)
 loop
  perform private.harbor_enqueue_notification('access-request:'||r::text,'fcm',jsonb_build_object('parentFcmRegistrationId',g.id,'userId',g.user_id),route);
 end loop;
 for w in
  select s.id from private.parent_web_push_subscriptions s
  join public.family_members fm on fm.user_id=s.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and s.status='active'
 loop
  perform private.harbor_enqueue_notification('access-request:'||r::text,'web_push',jsonb_build_object('subscriptionId',w.id),route);
 end loop;
end $function$;

create or replace function private.harbor_enqueue_controls_off_push(d uuid, e uuid, q bigint)
returns void language plpgsql security definer set search_path to '' as $function$
declare dev public.devices_public%rowtype; key text; route jsonb; r record; w record;
begin
 select * into dev from public.devices_public where id=d;
 if not found or dev.status='revoked' or dev.revoked_at is not null then return;end if;
 if exists(select 1 from private.notification_outbox o where o.event_key like 'device-controls-off:'||d::text||':%' and o.created_at>pg_catalog.clock_timestamp()-interval '30 minutes') then return;end if;
 key:='device-controls-off:'||d::text||':'||e::text||':'||q::text;
 route:=jsonb_build_object('version',1,'kind','device.controls.off','familyId',dev.family_id,'childId',dev.child_id,'deviceId',dev.id,'resourceId',pg_catalog.gen_random_uuid());
 for r in
  select g.id,g.user_id from private.parent_fcm_registrations g
  join public.family_members fm on fm.user_id=g.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and g.active and private.harbor_parent_session_active(g.user_id,g.session_id)
 loop
  perform private.harbor_enqueue_notification(key,'fcm',jsonb_build_object('parentFcmRegistrationId',r.id,'userId',r.user_id),route);
 end loop;
 for w in
  select s.id from private.parent_web_push_subscriptions s
  join public.family_members fm on fm.user_id=s.user_id
  where fm.family_id=dev.family_id and fm.status='active' and fm.role in ('owner','parent')
   and s.status='active'
 loop
  perform private.harbor_enqueue_notification(key,'web_push',jsonb_build_object('subscriptionId',w.id),route);
 end loop;
end $function$;
