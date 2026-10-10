# Harbor Android location sharing and message alerts — design

Date: 2026-10-10
Status: written specification awaiting owner approval; no implementation authorized by this document alone.
Base: branch `feat/android-usage-reporting` (draft PR #23, unmerged). This work is stacked on it and must not be merged before it.

## Owner decisions (2026-10-10)

- The earlier exclusion of location and message monitoring (usage-reporting spec, "separate later designs") is lifted for this effort only.
- Message monitoring means SMS plus chat-app notifications, producing alerts for parents. It does not mean a parent reading full message threads.
- As with screen time: sharing starts automatically once a child phone is paired, the child cannot turn it off, and a parent stops it by removing the device. Nothing is hidden from the child.
- Still excluded unless separately approved: app blocking or limits, web filtering, SOS, device-admin or anti-removal behavior, microphone, camera or screen capture, and any covert operation (hidden app icon, hidden notification).
- Design source: the owner's Figma file (`0U0Ibmj63W1rmwJQMsGgRA`). The Figma tool limit was exhausted on 2026-10-10, so screens below follow the web tokens already in the app until the file can be read. Revisit when readable.

## Principles shared by every feature

1. Visible to the child: a first-run disclosure naming exactly what is shared, a persistent status line in the child app, and the platform's own indicator (foreground-service notification for location). The child can see but not stop it.
2. Minimum data: send the least that serves the parent; keep raw material on the phone.
3. Truthful states: permission lost, offline, never reported and "nothing happened" are different. Never show zero or "safe" when the phone could not observe.
4. Same trust boundary as usage reporting: signed child operations (existing P-256 proof, timestamp, body hash, nonce), verified-parent reads scoped to active family membership, private-schema tables with RLS and no direct grants, sequence checkpoints and clear tombstones, atomic revocation fencing. Revocation deletes the device's location and alert data.
5. No inference of safety: the app reports observations and alerts; it never claims a child is safe or that all messages were checked.

## Feature A — Location sharing

Permissions: `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, then `ACCESS_BACKGROUND_LOCATION` (Android 11+ requires a separate Settings step). Foreground service of type `location` with a persistent notification "Harbor is sharing this phone's location with your parents". Boot/relaunch rearm only for a paired, consented child profile. Uses Fused Location Provider (Play services is already present for FCM); fallback to platform LocationManager when unavailable.

Collection: balanced-power priority, requested interval 5 minutes while moving and 15 minutes stationary, batched. Each fix keeps latitude, longitude, accuracy, provider, fix time. Fixes with accuracy worse than 500 m are kept but flagged low-accuracy. No continuous high-accuracy tracking, no route reconstruction beyond retained points.

Upload: new signed ops `report-device-location` (batch of up to 100 fixes, 256 KiB) and `clear-device-location`. Monotonic sequence per device, idempotent replace, tombstone on clear, future-time bound 5 minutes, server `receivedAt` separate from device time. Offline fixes queue encrypted (latest 500). Retention 30 days server-side (configurable; open decision), purged by scheduled job.

Parent read: `get-device-location` (latest fix plus history window), shown on a map with accuracy circle, "last updated" age and a staleness banner after 30 minutes. States: sharing on, location permission lost, background permission missing, location services off, offline, never reported. Map provider is an open decision (Google Maps SDK needs a restricted API key and billing; OpenStreetMap tiles need attribution and a usage-policy-compliant host).

Out of scope here: geofence alerts, check-ins, SOS, driving reports (follow-on designs).

## Feature B — Message alerts (SMS and chat-app notifications)

Capture paths:
- SMS: `RECEIVE_SMS` (incoming) and `READ_SMS` for new-message handling. These are Play-restricted; fine for the current Firebase App Distribution route, a blocker for Google Play unless a declaration is approved. Record this before any Play plan.
- Chat apps: a `NotificationListenerService`. The user must enable Notification Access in Android Settings (explicit trip, rechecked like Usage Access). Only notifications from a bounded, visible list of messaging apps are examined; others are ignored on-device.

Processing is on-device. Notification text is read into memory, classified, then discarded. Version 1 uses reviewable rule sets (keyword and pattern lists per category, languages: English first) in the app, not a remote model. Categories: bullying or harassment, self-harm or wellbeing concern, adult content, drugs or alcohol, violence or threats, contact from an unknown adult. The rule set version is part of each alert.

What leaves the phone (default): alert id, app label, category, rule-set version, time, and whether the sender was a saved contact (yes/no). Not sent by default: message text, sender name or number, attachments. An optional short redacted excerpt (max 120 characters) is an open decision; if approved it is a parent-visible, child-disclosed setting fixed at pairing, not a per-message secret.

Honest limits (shown in both child and parent views): only notification text is seen for chat apps, so muted notifications, message previews hidden by the sender app, or end-to-end encrypted content not shown in a notification are missed; classification makes mistakes both ways; the app does not claim to review every message. A parent view labels alerts as "possible" and never as a verdict.

Upload and read: signed ops `report-message-alert` (idempotent by alert id) and `clear-message-alerts`; parent `get-message-alerts` with paging. Parent push uses the existing FCM notification path with generic text ("Harbor flagged a possible concern. Open to review.") so lock screens do not leak content. Retention 30 days. Self-harm category alerts show parent guidance text and crisis resources, not just a flag.

Child view: a "What Harbor watches" panel listing the monitored apps, the categories, what is sent and what is not, and counts of alerts sent (not their content).

## Backend and contract additions

New private tables: `device_location_fixes`, `device_location_checkpoints`, `device_message_alerts`, `device_alert_checkpoints`. New zod contracts in `packages/contracts/src/v1/` with strict unknown-field rejection. New Edge functions: report/clear/get for each feature (6), all `verify_jwt=true`, reusing the shared proof verification. Retention purge job. Audit events for enable, permission change, revoke and clear (no content).

## Testing and acceptance

Unit and contract tests first (RED then GREEN in CI): payload validation, sequence and tombstone races, revocation fence, foreign-family denial, classifier rule fixtures (positive and negative cases per category), notification-allowlist filtering, staleness and permission-state rendering. Instrumented tests: consent screens, status line cannot be dismissed, no stop control. Real-phone acceptance per a new runbook: grant permissions, location fix reaches parent map, offline queue and replay, permission revocation shown as such, SMS and one chat-app test alert classified without sending text, revoke removes data, fixture cleanup with audits retained. Fixture builds disable these runtimes.

## Rollout order

1. Location (platform permissions are the least ambiguous; delivers parent value fastest).
2. Message alerts (SMS first, then notification listener).
Each ships as its own stacked PR, development-only, no merge or production claim.

## Open decisions for the owner

1. Map provider (Google Maps vs OpenStreetMap).
2. Location retention (default 30 days) and update cadence.
3. Message alerts: send no excerpt (default) or a 120-character redacted excerpt.
4. Which chat apps are on the default monitored list.
5. Languages beyond English for the first rule set.
6. Whether any item in the "Still excluded" list above should become a separate design.
7. Google Play: SMS permissions would need a declaration; confirm distribution stays on Firebase App Distribution for now.
