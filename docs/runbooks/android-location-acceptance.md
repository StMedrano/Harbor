# Android location sharing acceptance

Applies to `docs/superpowers/specs/2026-10-10-harbor-android-location-and-message-alerts-design.md` (Feature A) and the Part 1 tasks of `docs/superpowers/plans/2026-10-10-harbor-android-location-and-message-alerts.md`. Main and production stay untouched; this runs on the development project only. Fixture results and real-phone observations are never mixed: if something was not observed on a phone, it is recorded as not observed.

## What it does

A paired child phone sends its position (latitude, longitude, accuracy, provider, time) to its own parents. Sharing follows the phone's permission state and starts by itself once the child grants location; the child has no stop control, and a parent ends sharing by removing the device. A persistent Android notification is shown while the background service runs. The server keeps the newest 500 places for up to 30 days, plus a status (sharing / permission lost / background missing / location switched off). Parents read the last server-confirmed places on an OpenStreetMap map; a place older than 30 minutes since the last upload is labelled "last known" and drawn grey. No messages, geofences, alerts or location history beyond the retained places are involved.

## Evidence classes

| Class | Proves | Does not prove |
| --- | --- | --- |
| Deno contract/handler tests, real-PostgreSQL tests, pgTAP | wire contract, signed-request binding, sequence/checkpoint rules, revocation fence, retention, privacy grants | the deployed project, a real phone |
| JVM tests | engine ordering and exact-byte retry, repair from the checkpoint, status mapping, cadence rules, map maths, parent view fences | Android platform behaviour |
| Fixture APK and API 29/36 emulator jobs | build, lint, manifest, UI wiring | real GPS, background limits, OEM battery policy |
| Real-phone acceptance (below) | the live feature | production load |

## Real-phone procedure (owned disposable devices only)

Do not publish locations, screenshots of the map or identifiers. Use a child phone and a parent phone (or the parent app on a second device).

1. **Disclosure.** On the child phone the Today tab shows the location panel with the plain-language disclosure before any permission is requested.
2. **Permission steps.** *Allow location* opens Android's dialog; grant "while using the app". The panel then says to allow "all the time"; *Allow all the time* opens Android's setting. Grant it. Confirm the persistent notification appears.
3. **First place.** The parent device page shows the Location section with a map and "Seen … ago" within a few minutes.
4. **Movement.** Walk or drive a short distance with the screen off for several minutes. The parent's trail extends. Record only that it did or did not, not the route.
5. **Permission lost.** Revoke location in Android settings. The child panel returns to "Location is not allowed"; the parent shows the permission message within the periodic interval and no new places.
6. **Location switched off.** Turn the system location toggle off, then on. The parent shows the "switched off" message and then recovers.
7. **Offline.** Airplane mode for several minutes while moving, then back online. The parent receives the queued places in order; the parent's "last known" label appears while offline.
8. **Restart.** Reboot the child phone. Without opening Harbor, check whether sharing resumes (it needs "all the time" access). Record what happens; OEM policies may block it.
9. **No child stop.** Confirm there is no stop control on the child phone and the text says only parents can turn sharing off.
10. **Revocation.** Remove the device from the parent app. The child's next upload is denied, the notification and updates stop, and the stored places on the server are gone (verify by count only).
11. **Map.** Zoom in and out, check the OpenStreetMap attribution link, dark mode and font scale 1.8.

## Hosted rollout checks (development project only)

- Verify the approved SHA and green CI first. Apply only `20261010230000_device_location`; confirm the `harbor-location-retention` cron entry exists once.
- Deploy only `report-device-location`, `clear-device-location`, `get-device-location` and `get-device-location-checkpoint` with `verify_jwt=true`. Leave all other functions untouched.
- Confirm `private.device_location_*` are granted to `service_role` only, RLS is enabled, and advisors show nothing new.

## Cleanup

Remove only exact disposable fixtures, verify zero owned rows by count, keep audits. Do not delete active devices to simulate completion.

## Known limits

- Cadence and background resumption are at the mercy of Android battery/background policy; "background missing" is reported honestly rather than hidden.
- Standard OpenStreetMap tiles are for development only; a hosted tile provider replaces them before any wider release. A tile request reveals the viewed area to the tile server.
- Positions come from the phone and are not attested; mock locations are ignored on Android 12+ (and flagged on older versions).
