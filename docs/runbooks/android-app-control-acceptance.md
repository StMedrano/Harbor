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

## 0.22 fixes (2026-10-21)

The first real-phone run on 0.21 showed accessibility turning on but nothing blocked. Two causes, both fixed:

- **Nothing was saved.** Rules waited for a "Save changes" button at the bottom of the Activity tab; the development logs show no `update-device-state` call and no desired-state row. Controls now have their own **Controls** tab, laid out like the web app, and every change is written the moment it is made.
- **The child phone only fetched controls when Harbor was opened on it.** It now checks every minute while its controls run, and a parent's change also queues a data-only `device.controls.changed` push (migration `20261021160000_controls_wake_push`) that makes the phone sync within seconds when it arrives. A new policy is applied to the app already on screen, not only at the next app switch.

Also added, matching the web app: a **daily screen-time limit** (−/+ in 15-minute steps; when it is used up only Unlimited apps work until midnight), **Give 15 more min** (today only), and a per-app sheet with **No limit / Unlimited / 15–120 min a day / Blocked**. Unlimited apps do not count toward the daily limit. Both phones must run 0.22: a 0.21 child phone cannot read controls that carry a daily limit.

## Real-phone procedure (owned disposable devices only)

Install build 0.22 (versionCode 22) on both phones. Record only whether each step passed. Times are "within about a minute" unless a step says otherwise.

1. **Disclosure.** On the child phone, the Today tab shows "What Harbor controls" explaining that Harbor sees which apps are open and when, never what is on the screen, before any access is requested.
2. **Accessibility access.** Tap the action; Android's Accessibility settings open. Turn on Harbor. The panel shows controls as on.
3. **Controls tab.** On the parent phone the bottom bar reads Map · Alerts · Controls · Activity · Family. Open Controls for the child: "Used today", "Daily limit", Pause, Schedules and App limits are shown, with the child's reported apps listed.
4. **Block an app.** Tap an app's button and choose Blocked. No Save button is needed: the line at the top goes from "Saving…" to "Sending to …'s phone…" and then "In force on …'s phone." With the child already inside that app, it closes to Harbor's block screen; opening it again is blocked. The Activity tab shows "Tried to open … (blocked)".
5. **Pause now.** Tap Pause phone now while the child is in any non-exempt app. It closes within seconds (push) or within a minute (poll). The dialer, Messages, Settings and Harbor still open. Resume phone; apps open again.
6. **App limit.** Set an app to 15 min a day. Use it past 15 minutes: a "5 minutes left" notice, then the block screen "Time's up for …". The parent row reads "limit reached".
7. **Daily limit.** Set the daily limit just above today's "Used today". Keep using a normal app: a "5 minutes of screen time left" notice, then "Time's up for today". Every non-Unlimited app is now blocked; the parent shows "Time's up. …'s apps are locked."
8. **Unlimited.** Set one app to Unlimited. It still opens after time is up, and its minutes do not add to "Used today".
9. **Give 15 more min.** Tap it on the parent phone. Within a minute the child's apps open again, and the child panel shows the new allowance.
10. **Bedtime.** Turn Bedtime on with a start a few minutes away on today's day. At the start, apps are blocked and the timeline shows "Bedtime started"; at the end, "Bedtime ended".
11. **Timeline.** Use an app for over a minute and another for under a minute. Only the first appears, with its duration; "Time by app" totals match.
12. **Protection off.** Turn off Harbor's accessibility service on the child phone. The parent receives the generic push once, the timeline shows protection off, and the Controls tab warns that controls are off. Turn it back on; the timeline shows protection on.
13. **Offline.** Turn off data on the child phone: rules already applied keep working. Change a rule on the parent; turn data back on; the phone applies it within a minute.
14. **Two parents.** Change controls from two parent sessions; neither change is lost.
15. **Revocation.** Remove the device from the parent app. The child's next report is denied and the device's stored activity is gone on the server (verify by count only).


