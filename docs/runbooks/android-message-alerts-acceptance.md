# Android message alerts acceptance

Applies to `docs/superpowers/specs/2026-10-10-harbor-android-location-and-message-alerts-design.md` (Feature B) and Task M8 of `docs/superpowers/plans/2026-10-10-harbor-android-location-and-message-alerts.md`. Main and production stay untouched; this runs on the development project only, and only after Part 1 (location) has been accepted on a real phone. Fixture results and real-phone observations are never mixed: if something was not observed on a phone, it is recorded as not observed.

## What it does

On a paired child phone, Harbor reads incoming notifications from a fixed, child-visible list of messaging apps (Messages, WhatsApp, Facebook, Messenger, Instagram, TikTok, Snapchat, Discord, Telegram, X) and incoming SMS. A versioned on-device classifier looks for possible concerns in six categories: bullying, self-harm, adult content, drugs/alcohol, violence/threats and contact from an unknown adult. Only the result leaves the phone: category, app label, rule-set version, time and a yes/no "saved contact" flag. Message text, sender, phone number and excerpts are never logged, stored or sent. The child sees a "What Harbor watches" panel with no off switch; a parent stops it by removing the device. Parents see each item as a "possible concern", never a verdict, and get a generic push ("Harbor flagged a possible concern. Open to review.") at most once per device every five minutes. The server keeps alerts for 30 days.

## Evidence classes

| Class | Proves | Does not prove |
| --- | --- | --- |
| Deno contract/handler tests, pgTAP | wire contract has no text fields, signed-request binding, idempotent replay by alert id, checkpoint and revocation fence, retention, privacy grants | the deployed project, a real phone |
| JVM tests | classifier fixtures and obfuscation handling, output never contains input text, allowlist by exact package, notification-key dedupe, 200-item offline queue, reporter replay and repair | Android notification delivery, OEM policy |
| API 29/36 emulator jobs | build, lint, manifest, panel and alerts-screen wiring and wording | real notifications from real apps |
| Real-phone acceptance (below) | the live feature | classifier quality at scale |

## Real-phone procedure (owned disposable devices only)

Use synthetic test phrases only (take them from the classifier fixture tables), sent from a second phone you own. Do not screenshot or record real conversations, contact names or numbers. Record only whether each step passed.

1. **Disclosure.** Install the build over the existing app. On the child phone, the Today tab shows "What Harbor watches" with the app list, the categories, and the statement that message text is never sent, before any access is requested.
2. **Notification access.** Tap the notification-access action; Android's Notification Access settings open. Enable Harbor. The panel shows access as on.
3. **SMS permission.** Tap the SMS action and grant it. The panel shows SMS as on.
4. **One alert, no text.** From the second phone, send one synthetic bullying phrase by SMS. Within a few minutes the parent phone receives the generic push, and the parent device page lists one alert: category, "Messages", time, saved-contact flag. Confirm no message text, sender or number appears in the push, the alerts list, the detail view or the child panel.
5. **Chat app.** Repeat with one monitored chat app (for example WhatsApp) with notification previews on. One alert with that app's label.
6. **Negative case.** Send an ordinary message ("see you at practice"). No alert.
7. **Push throttle.** Send three flagged phrases within a minute. The parent list shows each alert, but only one push arrives in that five-minute window.
8. **Self-harm guidance.** Send one synthetic self-harm fixture phrase. The parent detail shows the guidance and crisis resources.
9. **Hidden previews (known limit).** Turn off notification previews for the chat app, or mute the conversation, and send a flagged phrase. Expect no alert. Record this as a limit, not a failure.
10. **Offline replay.** On the child phone turn off Wi-Fi and mobile data but keep the cell network on, so SMS still arrives. Send a flagged SMS; the alert is queued on the phone. Turn data back on. The alert reaches the parent once, with no duplicate.
11. **Cold restart.** Reboot the child phone and do not open Harbor. Send a flagged phrase. Record whether an alert arrives; OEM policy may delay the notification listener rebinding.
12. **No child stop.** Confirm there is no disable control on the child phone and the text says only a parent can stop it.
13. **Revocation.** Remove the device from the parent app. The child's next report is denied and the device's stored alerts are gone on the server (verify by count only).

## Hosted rollout checks (development project only)

- Confirm Part 1 is accepted, and verify the approved SHA and green CI first.
- Apply only `20261012120000_device_message_alerts`. Confirm the `harbor-alert-retention` cron entry exists once and the `device_alert_revoke_cleanup` trigger exists.
- Deploy only `report-message-alert`, `clear-message-alerts`, `get-message-alerts` and `get-message-alert-checkpoint` with `verify_jwt=true`. Leave all other functions untouched and confirm their versions are unchanged.
- Confirm `private.device_message_alerts`, `private.device_alert_status` and `private.device_alert_checkpoints` are granted to `service_role` only with RLS on, and the advisors show nothing new beyond the expected "RLS on, no policies" entries.
- If the migration is applied through the SQL editor instead of the migration tool, record it in the development migration history so the repo and the project agree.

## Cleanup

Remove only exact disposable fixtures, verify zero owned rows by count, keep audits. Do not delete active devices to simulate completion.

## Known limits

- Harbor only sees what Android shows in a notification. Hidden previews, muted chats, app-specific summaries ("2 new messages") and messages read while the app is open produce no alert.
- The classifier is English-only rule matching. It will miss concerns phrased in other ways and will sometimes flag jokes; every alert is a possible concern for a parent to talk through, not proof.
- Android may stop or delay the notification listener under battery policy, after a reboot, or after an app update; the child panel shows when access is off.
- No message content is ever available to parents, so an alert cannot be verified from Harbor alone.
