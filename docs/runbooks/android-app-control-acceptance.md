# Android activity and app control acceptance

Applies to `docs/superpowers/specs/2026-10-20-harbor-android-activity-and-app-control-design.md` and Task C8 of `docs/superpowers/plans/2026-10-20-harbor-android-activity-and-app-control.md`. Main and production stay untouched; this runs on the development project only. Fixture results and real-phone observations are never mixed: if something was not observed on a phone, it is recorded as not observed.

## What it does

A parent sets app controls for one child phone on the **Controls** tab: pause, one bedtime window on chosen days, a daily screen-time limit (chosen with a scrolling hours/minutes picker, 5 minutes to 12 hours), **Give 15 more min** for today, and a rule per app (no limit, Unlimited, a time limit a day, or Blocked). Every change is written at once and reaches the phone within seconds (wake push) or 30 seconds (the phone checks every 30 seconds). The child phone enforces it with Harbor's accessibility service, which only learns which app is in front (`canRetrieveWindowContent=false`), and covers a blocked app with Harbor's block screen. From that screen the child can ask a parent for 15 more minutes or to use the app; the parent answers **Allow 15 min** or **Deny** under **Requests**. A yes ends early if the parent pauses the phone, changes that app's rule, or taps **End now**.

Time limits count the time an app is on screen, from when it opens until it closes, which includes picture-in-picture (a video playing in a small window). That matches Android's Digital Wellbeing. An app in picture-in-picture that is not allowed is brought back to full screen and covered by the block screen. The dialer and emergency calls, the launcher, System UI, Settings, Harbor and the default SMS app are never blocked. Their time still counts toward the daily limit, as in Digital Wellbeing, except the launcher, keyboards, System UI and Harbor itself; Unlimited apps do not count. The phone sends its own count for today (total and up to 50 apps' minutes) about once a minute, and the parent shows that count. The phone also reports an activity timeline (app sessions of at least 60 seconds, blocked opens, limits reached, pause and bedtime changes, protection off/on). Only app names, package names and times leave the phone, never screen content, window titles, URLs or message text.

Pushes are high-priority FCM data messages. They reach Harbor when it is closed (swiped away from recent apps), but not after **Force stop** until Harbor is opened again. Requests, alerts, answers and time warnings use high-importance notification channels, so they pop up on screen.

## Evidence classes

| Class | Proves | Does not prove |
| --- | --- | --- |
| Deno contract/handler tests, pgTAP | strict controls, activity and request contracts with no content fields, signed-request binding, idempotent replay, revocation cleanup, retention, privacy grants, push delivery after a successful answer | the deployed project, a real phone |
| JVM tests | PolicyEngine (pause, bedtime, blocks, app and daily limits, grants, exempt apps), time on screen (picture-in-picture, moving between screens, screen off, overlap), the phone's screen-time count, grants ending on a later pause or rule, asking again, strict decoding, parent models | Android accessibility delivery, OEM policy |
| Instrumented compile | child panel, block screen and parent screens wiring | live enforcement |
| Real-phone acceptance (below) | the live feature | behaviour on every OEM |

## Development rollout

- 2026-10-20: migration `20261021120000_device_controls_and_activity`; functions `get-device-controls`, `get-device-activity`, `report-device-activity`, `get-device-activity-checkpoint`, `clear-device-activity`; `update-device-state` validates `desiredState.controls`. Cron `harbor-activity-retention`, trigger `device_activity_revoke_cleanup`, RLS on, no client grants or execute.
- 2026-10-21 (0.22): migration `20261021160000_controls_wake_push` (a controls write queues a data-only `device.controls.changed` push).
- 2026-10-21 (0.23): migration `20261021180000_device_access_requests`; functions `request-device-access` (signed child op, in both child allowlists), `get-access-requests`, `decide-access-request`; `update-device-state` v8 and `get-device-controls` v3 accept `grants`. Cron `harbor-access-request-retention`, revoke cleanup trigger, RLS on, no client grants or execute.
- 2026-10-22 (0.24): phone-only changes, nothing new on the server.
- 2026-10-22 (0.25): migration `20261022160000_push_delivery_and_screen_time` (`private.harbor_due_notifications`, `private.harbor_retire_notifications`, a `screen_time` column on `private.device_activity_status`, stored by `harbor_store_device_activity` and returned by `harbor_get_device_activity`; service role only). Functions that queue pushes now deliver due ones right after they answer: `device-sync` v6, `update-device-state` v9, `request-device-access` v2, `decide-access-request` v2, `report-device-activity` v2. `get-device-activity` v2 returns `screenTime`, so a 0.24 parent app can no longer read Activity: install 0.25 on both phones. The backlog queued while nothing delivered was retired (194 `device.state.changed` superseded, the rest expired). Observed on dev: a `device.controls.changed` push sent at 17:36 UTC, and `device.access.requested` and `device.access.denied` pushes sent at 19:36 and 19:37 UTC (outbox status only; whether they showed on the phones is step 1 and 2 below).
- 2026-10-23 (0.26 to 0.28): phone-only changes (the Harbor Family design system and the fixes below), nothing new on the server.

## 0.25 fixes (2026-10-22)

From the real-phone run on 0.24:

- **No device notifications for parent or child.** Development had never sent a push: pushes were queued, but the dispatcher had no schedule and only ran by hand. The functions that queue a push now send the due ones right after they answer, and the child phone's 30-second sync picks up anything left over. The generic "device state changed" push to parents is retired (it announced the parent's own change and duplicated the child's wake push). A push not sent within 15 minutes is dropped rather than arriving late.
- **Notices did not pop up.** Requests and alerts to the parent, answers to the child and the 5-minute warnings now use high-importance channels.
- **No "5 minutes left" notice.** It was not checked while the app was in picture-in-picture, and its channel did not pop up. Both are fixed.
- **A yes outlasted a later pause or block.** **Pause phone now** ends every yes, and changing an app's rule ends that app's yes. **Allowed right now** on the parent lists open yeses, each with **End now**.
- **Ask a parent and Allow only worked once.** The phone kept "Asked" for an hour. It now offers the button again as soon as the yes takes effect, the answer arrives, or the block ends.
- **"Time limit reached" after a limit was removed and set again.** The parent used a "limit reached" flag from earlier in the day. The flag is gone: the parent shows the phone's own count, and the child's block screen lifts by itself when a raised or removed limit allows the app again.
- **Daily total below Digital Wellbeing (2 h 2 min vs 2 h 13 min).** Phone, Messages, Settings and the other never-blocked apps were left out of the total. They still are never blocked, but now count, as in Digital Wellbeing.
- **Parent and phone showed different totals.** The parent now shows the count the phone enforces (sent about once a minute), not a recount from the timeline.
- **Faster sync.** Rule changes wake the phone by push within seconds; without a push the phone checks every 30 seconds (was 60). The parent's Controls tab refreshes every 15 seconds and Activity every 30 seconds.

## Results on 0.27 (2026-10-23)

Passed on real phones: steps 1 to 10, 13 to 18 and 20 of the list below. Step 16 also showed Unlimited apps blocked during bedtime, which is the design: Unlimited means no time limit, not exempt from pause or bedtime. Not reported: 21 (two parents) and 22 (revocation).

- **Step 11 failed in part.** After YouTube's limit was removed, the block screen stayed up and said "Your parent said yes" until the child tapped **Open YouTube**.
- **Step 12, layout.** Totals matched. The parent asked for screen time above the detailed activity, and for the timeline to be shorter with a way to see all of it.
- **Step 19 failed in part.** Protection off showed on the timeline and in Controls, but no notification reached the parent. The server sent the push (outbox `device.controls.off`, status `sent` at 17:32:44 UTC).
- **Message alerts.** An RCS message did not raise an alert. The child phone has sent no alert report since 2026-10-20 and the server holds no alerts for it, so nothing was captured: the message did not match a rule, or Messages posted no notification for it.

## 0.28 fixes (2026-10-23)

- **Push dropped while the parent app started.** A push that wakes a closed parent app arrives while the app re-registers its push token, which clears the saved registration until the server confirms it. The push was judged during that gap and thrown away (the server log shows two re-registrations 10 and 13 seconds after the protection-off push). The app now waits for a registration in progress and then checks the push against the settled registration. Sign-out and switching accounts still clear the registration, so a push for an old account is still refused. This applied to every parent push, not only protection off.
- **The block screen lifts by itself after a rule change.** When a limit is removed or raised, an app is unblocked, or a pause or bedtime ends, the block screen opens the app by itself. At most once a minute per app, so a disagreement with enforcement cannot bounce between the app and the block screen; after that it shows "<app> is allowed again" with **Open**. A block screen the child already left just closes. A parent's yes still shows "Your parent said yes" with **Open**.
- **Activity tab.** Screen time comes first, then **Detailed activity**. The timeline shows the newest 5 entries and **Show more (N earlier)** for the whole day (**Show less** folds it again).
- **Message alerts.** If Android drops Harbor's notification-listener connection (seen after app updates on some phones), Harbor asks Android to connect it again, checked every 5 minutes while the child profile runs.

## Still to verify on real phones (0.28)

Install build 0.28 (versionCode 28) on both phones, then repeat steps 11, 12 (new layout), 19, 21 and 22 of the list below, and this alert check:

- **Message alert from RCS.** Alerts flag specific phrases only, never ordinary messages. From another phone, send the child an RCS message (Google Messages shows "RCS message" in the text box) that says "everyone hates you", while the child does not have that conversation open (an open conversation posts no notification, so nothing can be seen). Within a minute the parent's Alerts tab shows a Bullying alert from Messages, and the parent gets a pop-up "Harbor flagged a possible concern. Open to review." The alert carries no message text.

## Real-phone checklist (from 0.25)

Install build 0.25 (versionCode 25) on both phones. On the child phone, Harbor notifications must be allowed (Android's notification permission and Harbor's own switch). Already verified on 2026-10-22 and not repeated: disclosure, accessibility access, the Controls tab, the block screen, a blocked video app, the pause screen, pause with picture-in-picture, the daily limit picker, and a first ask and allow. Record only whether each step passed. "Within a minute" unless a step says otherwise.

1. **Parent notification, Harbor closed.** On the parent phone, swipe Harbor away from recent apps. On the child, tap **Ask to use <app>** on a block screen. Within about 10 seconds the parent phone shows a pop-up notification "Request from your child"; tapping it opens that child's Controls with the request under **Requests**.
2. **Child notification.** Tap **Allow 15 min**. Within about 10 seconds the child phone shows "Your parent said yes" as a pop-up, and the block screen changes to "Your parent said yes" with **Open <app>**.
3. **Ask again.** When the 15 minutes end and the app is covered again, the block screen offers **Ask to use <app>** again (not "Asked"). Ask, allow, and the app opens a second time.
4. **Deny.** Ask from another app and tap **Deny**: the child gets "Your parent said no" as a pop-up, nothing opens, and the ask button is offered again.
5. **A yes ends on pause.** Allow 15 minutes for an app, then tap **Pause phone now**. Within about 30 seconds the app is covered by the pause screen, and **Allowed right now** on the parent is empty. **Resume phone**: the app does not reopen early under the old yes.
6. **A yes ends on a rule change.** Allow 15 minutes for an app, then set that app to Blocked. It is covered within about 30 seconds.
7. **End now.** Allow 15 minutes for an app; under **Allowed right now** tap **End now**. It is covered within about 30 seconds.
8. **Sync speed.** With the child phone's screen on, set an app to Blocked on the parent while the child is in it. It is covered within about 10 seconds.
9. **App limit amount.** Set YouTube's limit with the picker (for example 20 min). Use YouTube, including some time in picture-in-picture. Harbor's count on the child panel and on the parent is within about a minute of Digital Wellbeing's YouTube time.
10. **App limit warning and reached.** At 5 minutes left a pop-up "About 5 minutes of YouTube left today." appears, and another at 1 minute (also when YouTube is in picture-in-picture); at the limit "Time's up for YouTube" covers it. The parent's row reads "limit reached".
11. **Limit removed and set again.** After step 10, set YouTube to no limit: the child's block screen lifts by itself and YouTube opens. Then set a limit above today's YouTube time: the parent does not show "limit reached" and YouTube keeps working until the new limit.
12. **Daily total.** Compare "Used today" on the parent and the child panel with Digital Wellbeing's total screen time for today. They are within about 2 minutes (Digital Wellbeing also counts the home screen; Harbor does not, and Harbor leaves out Unlimited apps).
13. **Daily limit reached.** Set the daily limit a few minutes above today's time. Keep using a normal app: an "About 5 minutes of screen time left today." pop-up, then "Time's up for today" over it. Every app except Unlimited and always-available ones is blocked; the parent shows "Time's up. …'s apps are locked."
14. **Unlimited.** Set one app to Unlimited. It still opens after time is up, and its minutes do not add to "Used today".
15. **Give 15 more min.** Tap it after time is up. Within about 30 seconds the child's apps open again, and the child panel shows the new allowance.
16. **Bedtime.** Turn Bedtime on with a start a few minutes away on today's day. At the start apps are blocked and the timeline shows "Bedtime started"; at the end, "Bedtime ended".
17. **Ask limit.** Ask 11 times within an hour (different apps, no answers): the last shows "You've asked a lot this hour".
18. **Timeline.** Use an app for over a minute (part of it in picture-in-picture) and another for under a minute. Only the first appears, its duration includes the picture-in-picture time, and "Time by app" matches.
19. **Protection off.** Turn off Harbor's accessibility service on the child phone. The parent gets a pop-up notification once, the timeline shows protection off, and Controls warns that controls are off. Turn it back on; the timeline shows protection on.
20. **Offline.** Turn off data on the child phone: rules already applied keep working. Change a rule on the parent; turn data back on; the phone applies it within about 30 seconds.
21. **Two parents.** Change controls from two parent sessions; neither change is lost.
22. **Revocation.** Remove the device from the parent app. The child's next report is denied and the device's stored activity and requests are gone on the server (verify by count only).
