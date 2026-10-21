# Android activity and app control acceptance

Applies to `docs/superpowers/specs/2026-10-20-harbor-android-activity-and-app-control-design.md` and Task C8 of `docs/superpowers/plans/2026-10-20-harbor-android-activity-and-app-control.md`. Main and production stay untouched; this runs on the development project only. Fixture results and real-phone observations are never mixed: if something was not observed on a phone, it is recorded as not observed.

## What it does

A parent sets app controls for one child phone: pause (now, or for one hour), one bedtime window on chosen days, and a rule per app (blocked, a daily limit in minutes, or always allowed). The controls travel in the device's desired state and the child phone enforces them with Harbor's accessibility service, which only reads which app is in the foreground (`canRetrieveWindowContent=false`). The dialer and emergency calls, the launcher, System UI, Settings, Harbor and the default SMS app are never blocked. The child phone reports an activity timeline: app sessions of at least 60 seconds, blocked opens, limits reached, pause and bedtime changes, and protection turning off or on. Only app names, package names and times leave the phone, never screen content, window titles, URLs or message text. When protection turns off, the parent gets a generic push ("App controls were turned off on a child's phone. Open Harbor to check."), at most once per device every 30 minutes. The server keeps activity for 30 days.

## Evidence classes

| Class | Proves | Does not prove |
| --- | --- | --- |
| Deno contract/handler tests, pgTAP | strict controls and activity contracts with no content fields, signed-request binding, idempotent replay, checkpoint and revocation cleanup, 48 h read window, retention, privacy grants | the deployed project, a real phone |
| JVM tests | PolicyEngine (pause, bedtime across midnight, blocked, limits, exempt apps), strict decoding, session building and dedupe, parent model versioning and stale-write retry | Android accessibility delivery, OEM policy |
| Instrumented compile (ControlsUiTest) | child panel and parent screens wiring and wording | live enforcement |
| Real-phone acceptance (below) | the live feature | behaviour on every OEM |

## Development rollout (done 2026-10-20)

- Migration `20261021120000_device_controls_and_activity` applied and recorded under the repo version.
- Functions deployed with `verify_jwt=true`: `get-device-controls`, `get-device-activity`, `report-device-activity`, `get-device-activity-checkpoint`, `clear-device-activity`, and `update-device-state` (v6, now validates `desiredState.controls`).
- Verified: cron `harbor-activity-retention` (`47 3 * * *`) active once; trigger `device_activity_revoke_cleanup` present; RLS on all three `private.device_activity_*` tables; no grants to `anon`/`authenticated`/`public`; `authenticated` cannot execute any of the new `private` functions. Security advisors show only the pre-existing patterns (private tables with RLS and no policies by design; existing anonymous-role notices; leaked-password protection).

## Real-phone procedure (owned disposable devices only)

Install build 0.21 (versionCode 21) on both phones. Record only whether each step passed.

1. **Disclosure.** On the child phone, the Today tab shows "What Harbor controls" explaining that Harbor sees which apps are open and when, never what is on the screen, before any access is requested.
2. **Accessibility access.** Tap the action; Android's Accessibility settings open. Turn on Harbor. The panel shows controls as on.
3. **Block an app.** On the parent phone, open the child, Activity tab, App controls; set one app to Blocked and save. Within a minute the status reads "In force on the phone." Opening that app on the child phone shows Harbor's block screen; the parent timeline shows "Tried to open … (blocked)".
4. **Daily limit.** Set an app to 15 min/day. Use it past 15 minutes. It is blocked for the rest of the day and the timeline shows "… daily time limit reached".
5. **Always allowed.** Set the limited app to Always allowed and pause the phone. That app still opens; other apps are blocked.
6. **Pause.** Pause 1 hour. Non-exempt apps are blocked; the dialer, Settings, Messages and Harbor still open. Resume; apps open again.
7. **Bedtime.** Set a bedtime starting in a few minutes on today's day. At the start, apps are blocked and the timeline shows "Bedtime started"; at the end, "Bedtime ended".
8. **Emergency.** During pause, place an emergency-call screen (do not dial) and confirm the dialer is never blocked.
9. **Timeline.** Use an app for over a minute and another for under a minute. Only the first appears, with its duration; "Time by app" totals match.
10. **Protection off.** Turn off Harbor's accessibility service on the child phone. The parent receives the generic push once, the timeline shows protection off, and the controls screen warns that controls are off. Turn it back on; the timeline shows protection on.
11. **Offline replay.** Turn off data on the child phone, use apps, turn data back on. The events arrive once, with no duplicates.
12. **Stale write.** Edit controls from two parent sessions; the second save retries against the newer version and does not overwrite silently.
13. **Revocation.** Remove the device from the parent app. The child's next activity report is denied and the device's stored activity is gone on the server (verify by count only).
