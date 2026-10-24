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
- 2026-10-23 (0.26 to 0.29): phone-only changes (the Harbor Family design system, the fixes and the removal guard below), nothing new on the server.
- 2026-10-24 (0.30): migration `20261023200000_parent_invites` (one-time invite codes, 8 characters, one day, stored only as a keyed digest; 10 wrong codes in an hour lock the account that tried them). Functions `create-parent-invite` and `accept-parent-invite`, both parent-authenticated, verify_jwt on. A joined parent is an ordinary `parent` row in `family_members`, so every existing rule already gives them the same access as the owner; nothing else on the server changed. The web app (`web/harbor-family`) is now the site this branch's preview deploys, and reaches Harbor's functions through a same-site proxy on that site, so the backend's list of allowed web origins is untouched.

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

## Results on 0.33 (2026-10-24)

Passed on real phones, reported by the owner: **step 21 (two parents)** and **step 22 (revocation)** — the two the 0.27 round never reached. Parent invites therefore work end to end as well, since step 21 needs a second parent in the family: an invited account joined, and both parents' changes to the same child's Controls held without either reverting.

Still not reported: steps 11, 12 and 19 (fixed in 0.28, never re-run), the RCS alert check, and 0.33's names and joining a family later.

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

## 0.29: removing an app needs a parent (2026-10-23)

While app controls run, Android's "Do you want to uninstall this app?" screen is covered by Harbor's block screen: "Ask a parent to remove apps", with **Ask to remove an app**. The parent's request reads "Remove an app (it could be Harbor)"; **Allow 15 min** lets the child remove apps for 15 minutes (it shows under **Allowed right now** as "Removing apps", with **End now**), and the timeline shows "Tried to remove an app (needs your OK)". This covers removal from the home screen, from Settings > Apps and from any other app that uses Android's confirmation. Installing apps is not affected.

How it knows: the uninstall confirmation is a screen of the phone's app installer. For the installer only, the controls service reads the class name of the screen that came to the front (for example `UninstallerActivity`), never what it shows, so it cannot tell which app is being removed and asks a parent for any app. The installer that handles "remove this package" is looked up on the phone (manifest `<queries>` for `DELETE` and `UNINSTALL_PACKAGE`), so a phone maker's own installer is covered too. The service's description in Settings > Accessibility, the child's Controls panel and About page say that removing an app needs a parent.

Not covered (the parent's notification "App controls were turned off" covers the first three): turning off Harbor's accessibility service, Force stop, safe mode, a factory reset, and removal from a computer with developer tools. Removal from inside the Play Store uses the Play Store's own screen, but Harbor installed from App Distribution is not managed by the Play Store.

## 0.30: a second parent, and the web app (2026-10-24)

**Parents.** The **Family** tab lists everyone who looks after the family, on the phone and in the web app: their name, and whether they started the family or joined it. The parent reading it is marked. Below the list it says that every parent sees the same children and phones and can change the same rules.

**Invite a parent.** On either phone, **Family › Invite a parent** gives a code of 8 characters, shown as ABCD-EFGH, that works once and for one day. The other adult creates their own account and enters it under **I have an invite code**; they then see the same children, phones, places, alerts, activity and controls, and can change the same rules. A new code replaces the parent's earlier one. The code is stored only as a keyed digest, and after 10 wrong codes in an hour that account is refused for the rest of the hour. Harbor never emails anyone: the code is read out or sent by the parents themselves.

**The web app** is the same Harbor Family parent app in a browser, at **harbor-family-dev.vercel.app**, which always serves the newest build of this branch. It has the same five tabs, the same words and the same rules as the phone app, in the Harbor Family design system: Map with check-ins, Alerts, Controls (pause, 15 more minutes, the daily limit, bedtime, a rule per app, Allowed right now, Requests), Activity, and Family with Add a child, pairing codes and Invite a parent. It can be installed to the home screen. What it does **not** do: it never measures, shares or enforces anything itself (only a child's Android phone does), and removing a phone still needs the two-step verification the web app has no screen for.

**Two parents changing the same rule.** Each change is written on top of the version the server holds. If the other parent changed the same phone's controls first, the change is applied again on top of theirs rather than overwriting them, so neither parent's change is lost. Both apps refresh Controls every 15 seconds while the tab is open.

## Web app: notifications, and sharing your own place (2026-10-24)

**Android is unchanged by this; there is no new APK.** It is a web-app release, so it arrives at **harbor-family-dev.vercel.app** on the next deployment. The phone app stays at 0.34.

Until now the web app had no service worker at all, so a parent working in a browser saw a request or an alert only while sitting on the right screen, and could not share their own place as they can on the phone. Four things stood in the way, and all four are done: there was no service worker and nothing asked for notification permission; the page's `default-src 'none'` content-security policy had no `worker-src`, so registering one would have been refused outright; and the gateway's allowlist did not name the functions the browser needs.

**Notifications.** `public/sw.js` shows them. A Harbor push carries a route and no content, so the worker chooses the words from the kind alone — the same words the Android parent app uses — and a lock screen still never shows a child's name, an app, an alert category or any part of a message. Tapping one focuses an open tab and lands on the screen that answers it (an alert on Alerts, a request on Controls). It caches nothing: the app is online-only, and a stale cached shell would be worse than no offline support.

**Your own place.** **Family › On this device › Share my location**, off until a parent turns it on, matching the phone: the parent names what their children see, only the latest place is kept, it is sent only while Harbor is on the screen, and turning it off deletes the place Harbor holds. Switching to another family stops it rather than leaving a place behind, and signing out stops it too. Other parents never see it; children do.

Both switches are per browser, not per account, and both are off until pressed. The browser's own permission prompts are only ever opened from a press.

**One step by hand before notifications can work:** the Vercel project needs `VITE_VAPID_PUBLIC_KEY` set to the **public** half of the backend's VAPID pair — the same value as the `VAPID_PUBLIC_KEY` function secret. It is a public key by design and belongs in the browser; the private half stays a secret. Without it the app runs normally and the Notifications row reads "Not set up on this site yet" instead of failing when a parent presses it.

Check in a browser, signed in as a parent, with a child's phone paired:

- **Notifications.** **Family › On this device**: turn **Notifications** on and allow the browser's prompt. The row reads "On in this browser". From the child's phone, tap **Ask to use <app>** on a block screen. The browser shows a notification titled "Request from your child" with no app name and no child's name in it. Tap it: the tab comes forward on that child's **Controls**, with the request under **Requests**. Turn the switch off: the row reads off, and a further request raises no notification.
- **Notifications with the tab closed.** With notifications on, close the tab (leave the browser running) and ask again from the phone. The notification still arrives — that is the whole point of the service worker. Installed to the home screen, the same holds with the app closed.
- **Nothing leaks.** No notification text names a child, an app, an alert category or any part of a message. An alert reads only "Harbor flagged a possible concern. Open to review."
- **Share my location.** Turn **Share my location** on, allow the browser's prompt, and keep the name offered or change it. The row reads "Sharing as <name> · sent just now". On the child's phone, **Family** shows that name on the map. Leave the tab in the background for a few minutes: the child's map stops getting new places, and returning to the tab sends a fresh one at once.
- **Stopping deletes it.** Turn the switch off: the child's map no longer shows that parent. Sign out while sharing: the same.
- **Refused permission is honest.** Block notifications (or location) for the site in the browser, reload, and open **Family**: the row says it is blocked in the browser's settings rather than offering a switch that cannot work.

## 0.34: the app's own documents match what it does (2026-10-24)

Harbor's About pages, privacy policy and terms all said it never blocks or limits apps. That stopped being true in 0.28 and 0.29, which added blocked apps, per-app and daily limits, bedtime, and the guard over removing an app. The child's About page was the worst of the four: it opens by promising to explain everything Harbor does, and then told the child no app would ever be blocked.

All four now describe app control instead of denying it. The child's page gains **What your parents can control**: the rules a parent can set, that a blocked app says which rule stopped it and offers to ask a parent, that the phone, messages, Settings, the keyboard and the home screen are never blocked so a call or a text always works, and that removing any app needs a parent's OK. The parent's page gains a **Controls** entry and a **Removing apps** section. The privacy policy now discloses what app control reads on the phone — which app is in the foreground, and the name of the screen the phone's app installer brought to the front, which is used on the phone and sent nowhere — and that it reads the contents of no screen.

Check on the phones: **child phone › About Harbor**, and on a parent phone **About Harbor**, **Privacy policy** and **Terms of service**. Each reads as described above, and none of them claims Harbor does not block apps.

## 0.33: a name to be known by, and joining a family later (2026-10-24)

**A name.** Creating an account on the phone now asks for the name the rest of the family sees, and a parent can change it afterwards: **Family › Parents**, tap your own row. Until now the Android sign-up asked only for an email and a password, so every parent who made their account there had no name at all and read to the others only as "A parent". The web app asks for a name as it always did. A parent may change their own name and nobody else's (migration `20261024170000_profile_display_name`).

**Joining a family later.** The invite code used to be enterable only on the way in, by an account with no family. An adult who already had an account — or who started a family of their own by mistake — had no way to accept an invite. **Family › Join another family with a code** now takes one at any time, on the phone and in the web app. A parent can belong to more than one family; the Family tab lists them and switches between them.

## 0.32: one signing key, so the app can be built anywhere (2026-10-24)

Until now the app was signed with whatever debug key sat on the machine that built it, one made per machine. A build from anywhere else would have been refused by a phone as an update. From 0.32 there is one Harbor development key, supplied to the build from outside the repository, so a build from the PC and a build from a cloud environment install over each other. `docs/runbooks/parent-build-anywhere.md` says what a machine needs.

**0.32 installs once as a new app.** It is signed differently from 0.31, so **uninstall Harbor Family from both phones before installing it**, and pair the child phone again afterwards. Every build after 0.32 updates in place as before. Check the parent app is signed as expected: the build prints `CN=Harbor Family development`.

## Still to verify on real phones (0.32)

Uninstall Harbor Family from both phones, install build 0.32 (versionCode 32), and pair the child phone again. Then:

- **Parents.** Before inviting anyone, **Family** lists one parent: you, marked as having started the family. After the second account joins, both phones list both parents, and each marks the one reading it.
- **A name.** Create an account on a phone: it asks for a name, and the other parent sees that name under **Parents**. Then **Family › Parents › your own row**: change it, and the other parent sees the new one.
- **Joining later.** With an account that already has its own family, use **Family › Join another family with a code** with a code from the first parent. Both families are then listed, and switching between them shows each one's children.
- **Invite a parent.** On the first phone, **Family › Invite a parent**: a code appears. On the second phone (or in the web app), create an account with a different email, choose **I have an invite code**, and enter it. That account lands in the family and sees the same children and phones. Enter the same code again from a third account: it is refused. Ask for a new code on the first phone: the earlier code no longer works.
- **Two parents, step 21.** With both parents on the same child's **Controls** tab: one sets YouTube to Blocked while the other sets the daily limit to a different value, within a few seconds of each other. Both changes are there afterwards on both screens and on the child's phone; neither reverts. Repeat with one parent on the phone app and one in the web app.
- **A second parent can do everything.** From the second account: answer a request (Allow 15 min), pause and resume the phone, request a check-in, and open Activity. All of it works and shows on the first parent's screen.
- **The web app.** Sign in, pair nothing new, and check each tab against the phone app for the same child: the same screen time, the same timeline, the same rules. Install it to the home screen and confirm it opens full screen.

## Still to verify on real phones (0.29)

Install build 0.29 (versionCode 29) on both phones, then:

- **Removal guard.** On the child phone, long-press Harbor (then another app) on the home screen and choose Uninstall: Harbor's "Ask a parent to remove apps" covers the confirmation each time. Do the same from Settings > Apps > Harbor > Uninstall. Tap **Ask to remove an app**; the parent gets the request "Remove an app (it could be Harbor)". **Deny**: nothing is removed. Ask again and **Allow 15 min**: the child sees "Your parent said yes" and **Continue**, and an app can be removed. After 15 minutes (or **End now** on the parent), the confirmation is covered again. Installing an app is not blocked.

Then repeat steps 11, 12 (new layout), 19, 21 and 22 of the list below, and this alert check:

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
