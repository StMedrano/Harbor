# Device controls: pause and bedtime

Parents flip **Pause phone** or **Bedtime** on the child's Location tab. The setting is stored in the device's desired state (`desiredState.controls`), the phone fetches it on its next `device-sync` (every ~15 s while the app is open) and shows a full-screen block until it is lifted.

```
controls = { paused?: boolean, bedtime?: { enabled, start: "HH:MM", end: "HH:MM" }, requireLocation?: boolean }
```
Shape is validated by `update-device-state`; other desired-state keys pass through untouched. Bedtime defaults to 21:00 to 07:00 (no editor yet) and is evaluated in the phone's local time, including windows that cross midnight. Pause wins over bedtime.

## Backend
- Migration `20261008140000_device_controls_read.sql` adds `private.harbor_list_family_device_states` (parent read path; owner/parent only).
- New function `get-device-controls` returns each active device's controls, version and whether the phone acknowledged it.
- Existing `update-device-state` writes (optimistic version check); the parent app re-reads once on a concurrent change.

## What this does and does not do
This is an in-app block, not a device lock. While paused or in bedtime the Harbor Family app is covered by a block screen, but the child can still open other apps. A true lock needs Android Device Owner or Accessibility-service enforcement, which is a separate, heavier design with Play policy limits. The block only updates while the phone can reach Harbor (it keeps the last known state offline).

## Deploy (not applied anywhere yet)
Order matters because this builds on the location work:
1. `supabase db push` applies `20261008130000_child_location.sql` then `20261008140000_device_controls_read.sql`.
2. `supabase functions deploy report-location get-device-controls update-device-state`
3. Deploy the web app.
