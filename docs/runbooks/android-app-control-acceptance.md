# Android activity and app control acceptance

Applies to `docs/superpowers/specs/2026-10-20-harbor-android-activity-and-app-control-design.md` and Task C8 of `docs/superpowers/plans/2026-10-20-harbor-android-activity-and-app-control.md`. Main and production stay untouched; this runs on the development project only. Fixture results and real-phone observations are never mixed: if something was not observed on a phone, it is recorded as not observed.

## What it does

A parent sets app controls for one child phone on the **Controls** tab: pause, one bedtime window on chosen days, a daily screen-time limit (chosen with a scrolling hours/minutes picker, 5 minutes to 12 hours), **Give 15 more min** for today, and a rule per app (no limit, Unlimited, a time limit a day, or Blocked). Every change is written at once and reaches the phone within seconds (wake push) or a minute (the phone checks every minute). The child phone enforces it with Harbor's accessibility service, which only learns which app is in front (`canRetrieveWindowContent=false`), and covers a blocked app with Harbor's block screen. From that screen the child can ask a parent for 15 more minutes or to use the app; the parent answers **Allow 15 min** or **Deny** under **Requests**.

Time limits count the time an app is on screen, from when it opens until it closes, which includes picture-in-picture (a video playing in a small window). That matches Android's Digital Wellbeing. An app in picture-in-picture that is not allowed is brought back to full screen and covered by the block screen. The dialer and emergency calls, the launcher, System UI, Settings, Harbor and the default SMS app are never blocked. The phone reports an activity timeline (app sessions of at least 60 seconds, blocked opens, limits reached, pause and bedtime changes, protection off/on). Only app names, package names and times leave the phone, never screen content, window titles, URLs or message text.

## Evidence classes

| Class | Proves | Does not prove |
| --- | --- | --- |
| Deno contract/handler tests, pgTAP | strict controls, activity and request contracts with no content fields, signed-request binding, idempotent replay, revocation cleanup, retention, privacy grants | the deployed project, a real phone |
| JVM tests | PolicyEngine (pause, bedtime, blocks, app and daily limits, grants, exempt apps), time on screen (picture-in-picture, moving between screens, screen off, overlap), strict decoding, parent models | Android accessibility delivery, OEM policy |
| Instrumented compile | child panel, block screen and parent screens wiring | live enforcement |
| Real-phone acceptance (below) | the live feature | behaviour on every OEM |

## Development rollout

- 2026-10-20: migration `20261021120000_device_controls_and_activity`; functions `get-device-controls`, `get-device-activity`, `report-device-activity`, `get-device-activity-checkpoint`, `clear-device-activity`; `update-device-state` validates `desiredState.controls`. Cron `harbor-activity-retention`, trigger `device_activity_revoke_cleanup`, RLS on, no client grants or execute.
- 2026-10-21 (0.22): migration `20261021160000_controls_wake_push` (a controls write queues a data-only `device.controls.changed` push).
- 2026-10-21 (0.23): migration `20261021180000_device_access_requests`; functions `request-device-access` (signed child op, in both child allowlists), `get-access-requests`, `decide-access-request`; `update-device-state` v8 and `get-device-controls` v3 accept `grants`. Cron `harbor-access-request-retention`, revoke cleanup trigger, RLS on, no client grants or execute.
- 2026-10-22 (0.24): phone-only changes, nothing new on the server.

## 0.24 fixes (2026-10-22)

From the real-phone run on 0.23 (dev data: YouTube limited to 15 min; limit reached 09:44, then another YouTube session 09:46–09:53):

- **Block screen never appeared.** The service pressed Home and then opened the block screen; Home arrived second and hid it. The block screen now opens straight over the app, with nothing pressed first. If Android does not show it, the phone falls back to the home screen.
- **Video apps dropped into picture-in-picture and kept playing.** Pressing Home is what told YouTube the user was leaving. The block screen now opens without that signal (`FLAG_ACTIVITY_NO_USER_ACTION`), and every 5 seconds the service checks the phone's usage events: an app reopened from picture-in-picture is caught, and an app playing in picture-in-picture that is not allowed is brought back to full screen and covered.
- **Limits counted less than Digital Wellbeing (14 vs 26 min).** Time used to stop when the app was paused, which left out picture-in-picture. It now runs until the app is stopped, for app limits, the daily limit and the activity timeline. The parent's Controls tab shows the larger of the usage report and the timeline, and marks an app "limit reached" when the phone reported it.
- **Time pickers.** The daily limit and each app's limit are set with a scrolling hours/minutes picker instead of 15-minute steps.
- **Wording.** "<app> is blocked by Harbor Family" and "Your phone has been paused by Harbor Family".

## Still to verify on real phones (0.24)

Install build 0.24 (versionCode 24) on both phones. Disclosure, accessibility access and the Controls tab were verified on 2026-10-22 and are not repeated. Record only whether each step passed. "Within a minute" unless a step says otherwise.

1. **Block screen.** Set an app to Blocked. With the child inside that app, it is covered by Harbor's screen: Harbor Family header, the app's icon with a lock, "<app> is blocked by Harbor Family", **Ask to use <app>** and **Go to home screen**. Opening the app again shows the same screen.
2. **Blocked video app.** Block YouTube while a video is playing. The block screen covers it, the video stops, and no picture-in-picture window appears.
3. **Pause screen.** Tap **Pause phone now** while the child is in any app: "Your phone has been paused by Harbor Family" covers it. The dialer, Messages, Settings and Harbor still open. **Resume phone**: apps open again.
4. **Pause with picture-in-picture.** Start a YouTube video, go Home so it plays in picture-in-picture, then pause the phone. Within about 5 seconds YouTube comes back to full screen and is covered by the pause screen; nothing keeps playing.
5. **App limit amount.** Set YouTube's limit with the picker (for example 20 min). Use YouTube, including some time in picture-in-picture. Compare with Digital Wellbeing: Harbor's count on the child panel is within about a minute of it.
6. **App limit reached.** At 5 minutes left a notice appears; at the limit "Time's up for YouTube" covers it (also if it is in picture-in-picture at that moment). The parent's row reads "limit reached".
7. **Daily limit picker.** On the parent, tap the daily limit, scroll the hours and minutes wheels to a time, tap **Set**. The value shows on Controls and the child panel within a minute.
8. **Daily limit reached.** Set the daily limit a few minutes above today's time. Keep using a normal app: a "5 minutes of screen time left" notice, then "Time's up for today" over it. Every app except Unlimited and always-available ones is blocked; the parent shows "Time's up. …'s apps are locked."
9. **Unlimited.** Set one app to Unlimited. It still opens after time is up, and its minutes do not add to "Used today".
10. **Give 15 more min.** Tap it after time is up. Within a minute the child's apps open again, and the child panel shows the new allowance.
11. **Bedtime.** Turn Bedtime on with a start a few minutes away on today's day. At the start apps are blocked and the timeline shows "Bedtime started"; at the end, "Bedtime ended".
12. **Ask a parent.** On a block screen tap the ask button: it changes to "Asked". The parent gets a notification (and a toast if Harbor is open); tapping it opens that child's Controls with the request under **Requests**.
13. **Allow.** Tap **Allow 15 min**. The child's block screen changes to "Your parent said yes" with **Open <app>**; the app works for 15 minutes and is then covered again. The child gets "Your parent said yes" (if Harbor notifications are on).
14. **Deny.** Ask again from another app and tap **Deny**: nothing opens; the child gets "Your parent said no".
15. **Ask limit.** Ask 11 times within an hour (different apps): the last shows "You've asked a lot this hour".
16. **Timeline.** Use an app for over a minute (part of it in picture-in-picture) and another for under a minute. Only the first appears, its duration includes the picture-in-picture time, and "Time by app" matches.
17. **Protection off.** Turn off Harbor's accessibility service on the child phone. The parent receives the generic push once, the timeline shows protection off, and Controls warns that controls are off. Turn it back on; the timeline shows protection on.
18. **Offline.** Turn off data on the child phone: rules already applied keep working. Change a rule on the parent; turn data back on; the phone applies it within a minute.
19. **Two parents.** Change controls from two parent sessions; neither change is lost.
20. **Revocation.** Remove the device from the parent app. The child's next report is denied and the device's stored activity and requests are gone on the server (verify by count only).
