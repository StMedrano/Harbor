# Android usage reporting acceptance

Applies to the approved design `docs/superpowers/specs/2026-10-08-harbor-android-usage-reporting-design.md` and plan `docs/superpowers/plans/2026-10-08-harbor-android-usage-reporting.md` (Issue #1). Main and production stay untouched. This runbook separates deterministic fixtures from real phone observation; never present one as the other.

## What the measurements are

Usage is read on the child's phone from Android's UsageStats and sent as a signed, device-observed report. The server stores the latest report per device, confirms receipt, and lets active owner/parent members read it. Reports are not server-attested and make no claim to equal Digital Wellbeing. Overlapping app time is counted once in the day total, so app times may add up to more than the total. Unknown is never shown as zero: no report, absent Usage Access, unavailable days and expired data each have their own state. Harbor measures only; it adds no limits, blocking, location, messaging, SOS or anti-removal behavior.

## Evidence classes

| Class | Proves | Does not prove |
| --- | --- | --- |
| JVM tests | contracts, collection arithmetic, queue/lease/consent/stop ordering, transport, view state | Android platform behavior |
| CI fixture APK (`-PparentCiFixture=true`) | UI states, font scale 1.8, light/dark, scrolling, Usage Access intent, JobScheduler registration | Real collection: the fixture build **disables the usage runtime** and never touches the network |
| API 29/36 emulator jobs | the native UI and job service wiring on two Android versions | Background timing, OEM battery policy, real UsageStats |
| `tests/end-to-end/usage-reporting.test.ts` (real PostgreSQL) | signed report → parent read, replay/nonce, clear tombstone and delayed upload, latest-only offline catch-up, denied permission kept as unknown, expiry, membership loss, revoke, checkpoint retention | live Auth issuance, deployed functions, a real phone |
| Real-phone acceptance (below) | the live feature | production load |

Provider or fixture receipts are never evidence of phone observation. If something cannot be observed, record it as not observed.

## Required automated gates

All run on the exact final SHA: `foundation`, `notification-web`, `notification-android`, `family-web` (new web tests and build), `parent-android` (strict dependency verification, `testDebugUnitTest`, `lintDebug`, `assembleDebug assembleDebugAndroidTest`) and `parent-keystore` on API 29 and 36. A failing emulator job copies the tail of its log into check-run annotations so the failure is readable without downloading artifacts.

## Real-phone procedure (Task 9, owned disposable devices only)

Use two Android roles: a parent device (or the parent browser) and a child device with a disposable test app. Do not use or publish an unrelated personal installation or its package inventory. Keep credentials, tokens and fixture identifiers out of chat, GitHub and screenshots.

1. **Consent first.** Install the live APK on the child phone. Confirm nothing is sent before the child taps *Start sharing screen time*, and that the disclosure matches what is collected.
2. **Permission denied.** With Usage Access off, parent native and web show "Usage Access is off", not zero. Grant it from the in-app button; confirm the first report arrives.
   - If Android shows "App was denied access" for Usage Access, the build was sideloaded (for example via an App Distribution tester app) and Android is restricting the permission. On the phone: Settings > Apps > Harbor > the three-dot menu > **Allow restricted settings** (confirm with the screen lock), then open Usage Access again and enable Harbor. Harbor does not work around this and it is an expected step for sideloaded development builds only.
3. **Controlled foreground intervals.** Use the test app for known intervals (for example 2 min, 5 min), lock the screen for part of one interval, and try multi-window. Record wall-clock start/end for each. Allow the periodic job to run (the platform may delay it; background delay is expected and is recorded, not hidden).
4. **Compare with recorded tolerance.** Use `evaluateObservation` from `tests/hosted/usage-reporting-acceptance.ts` with the tolerance you observed for this phone. Do not widen the tolerance to make a result pass. State that results are not Digital Wellbeing equivalence.
5. **Partial first day / day boundary.** Start sharing mid-day and confirm the day is labeled partial. Cross local midnight (or change the device time zone) and confirm day rows keep their own dates and zone.
6. **Offline / reconnect.** Disable the network, produce usage, re-enable it. Only the latest report is sent; the parent shows the previous confirmed report as out of date or "can't reach Harbor" until then.
7. **Inventory.** Confirm launchable apps appear with labels, unknown labels read "name unavailable", and a truncated inventory is called out. Do not publish the inventory.
8. **Parent reads.** Native and browser show the same confirmed report, read-only. Check dark mode, font scale 1.8, TalkBack reading of the day bars, and Back behavior.
9. **No child opt-out.** Confirm the child phone has no stop control and says only parents can turn sharing off. (Parents stop sharing by removing the device, step 10. The earlier child opt-out path was exercised on a real phone on 2026-10-10 before this product decision.)
10. **Revocation.** Revoke the child device from the parent. The child's next upload and the parent's read are both denied, and the stored payload is gone.
11. **Cold restart.** Force-stop and reboot the child phone with sharing on; confirm the job resumes only while consent is still on and the profile lease matches.

## Hosted rollout checks (Task 9, development project only)

- Verify the approved SHA, GREEN CI and the completed review first. Compare hosted migration versions/names with the repository; the dry run must contain only `20261008221353_device_usage_snapshots` and `20261008221810_device_usage_retention`.
- Confirm `pg_cron` is available and the single `harbor-usage-retention` job (`17 3 * * *`) exists. If unsupported, stop at that blocker rather than deploying incomplete retention.
- Deploy only `report-device-usage`, `clear-device-usage`, `get-device-usage` and `get-device-usage-checkpoint` plus any shared bundle proven necessary. Preserve `verify_jwt`/custom signed verification, CORS, the native callback, anonymous child Auth and the older child/browser/parent routes.
- Inspect that `private.device_usage_*` tables and helper functions are granted to `service_role` only, RLS is enabled, parent membership denial holds, and the retention job is unique. Review advisors after DDL. Private default-deny tables with no client policies are intentional; keep needed indexes.

## Cleanup and sanitized reporting

Remove only the exact disposable operational and Auth fixtures through trusted discovery of the exact run, then verify zero owned rows independently while keeping audits. Disable collection on the acceptance phone and record the real outcome. Do not delete active fixtures to simulate completion.

Public progress uses `publicStageSummary` (fixed stage names and `passed`/`failed`/`blocked`/`not-run`). Private journals pass through `redactInventory` before any excerpt is shared.

## Known limits

- Fixture CI proves UI and wiring, not real collection or background cadence.
- Real UsageStats granularity, OEM battery restrictions and doze can delay or skip jobs.
- A report is the latest phone-observed snapshot, up to the periodic interval old, kept 30 days, then reported as expired.
- Package/label names come from the child's launchable app list and may be missing or truncated.
