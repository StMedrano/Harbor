# Harbor Android activity and app control — design

Date: 2026-10-20
Status: draft for owner review. The owner supplied the decisions below on 2026-10-20. The implementation plan is written only after the open questions at the end are answered, and implementation starts only on the owner's go.
Base: branch `fix/message-alert-allowlist` (stacked on `feat/checkin-alert`). This work must not be merged before those.

## Owner decisions (2026-10-20)

- The earlier exclusion of "app blocking or limits" (location and message-alerts spec) is lifted for this effort.
- Enforcement uses an Android **accessibility service** on the child phone. Not device owner, not device admin, no factory reset.
- First version controls: **block specific apps**, **daily per-app time limits**, **pause the phone now**, **bedtime window**, and **always-allowed apps**.
- "Activity" means a **per-day timeline** for parents: which apps were used and when (sessions), plus control events (limit reached, blocked app opened, protection turned off).
- Still excluded unless separately approved: web/content filtering, live "what is on screen now", reading screen content or window titles, device-admin or anti-removal behavior, hiding Harbor from the child, and any covert operation.

## Principles (shared with earlier features)

1. Visible to the child. The child always sees the current rules, time left, and why something is blocked. Nothing is hidden. Only a parent changes rules; the child has no off switch inside Harbor.
2. Truthful states. If enforcement is off (accessibility disabled, policy not yet applied, phone offline), parents are told so. Harbor never claims a rule is enforced when it cannot be.
3. Minimum data. Activity carries app package, label and times only. No screen content, window titles, URLs, notification text or keystrokes are read or sent. The accessibility service is configured so it cannot read window content.
4. Same trust boundary. Parent writes need a current parent session and active owner/parent membership. Child reads and reports use the existing signed child operations (P-256 proof, nonce, body hash) with sequence checkpoints. Revocation removes the device's policy and activity.
5. Works offline. The child phone enforces the last applied policy without a network connection.

## Parts

### 1. Policy (parent to child)

The policy travels on the existing desired-state channel (`update-device-state` write, `device-sync` read and acknowledgment). It is a `controls` object inside the desired state, versioned by the existing `desired_state_version`:

```
controls: {
  version: 1,
  pause: { active: boolean, until: instant | null },          // until = null means "until a parent resumes"
  bedtime: { enabled: boolean, start: "HH:MM", end: "HH:MM", days: ["mon",...], } | null,
  blockedApps: [packageName],                                   // max 200
  appLimits: [{ packageName, minutesPerDay: 1..1440 }],         // max 200
  alwaysAllowed: [packageName]                                  // max 50
}
```

- A new contract `packages/contracts/src/v1/controls.ts` with matching Kotlin models. `update-device-state` validates `controls` against it (unknown keys rejected, a package cannot be both blocked and always-allowed, package names match Android's format).
- Bedtime and the daily reset use the **child phone's local time zone**. The policy stores wall-clock times only.
- On every write the server already sends the FCM nudge used by desired-state commands; the child then syncs. The child acknowledges the version once it is enforcing it. Parents see "Applied on the phone at …" or "Waiting for the phone" from the acknowledged version, as the old device-controls branch did (`get-device-controls`; reuse its migration and read path rather than rewriting).
- Parents pick apps from the existing usage inventory (package name and label already reported). An app that is not in the inventory cannot be chosen.

### 2. Enforcement on the child phone

- `HarborControlService` (AccessibilityService) subscribes only to `TYPE_WINDOW_STATE_CHANGED`, with `canRetrieveWindowContent=false` and no gesture or key-event flags. From each event it takes only the foreground package name.
- A pure `PolicyEngine.decide(package, now, policy, usedTodayMs)` returns `Allow` or `Block(reason)` where reason is `PAUSED`, `BEDTIME`, `BLOCKED_APP` or `LIMIT_REACHED`. It is pure Kotlin so every rule and edge case is JVM-tested.
- Always exempt, regardless of policy: Harbor itself, the default dialer and emergency calls, the launcher, System UI, and Settings (so the phone stays recoverable). Parents' `alwaysAllowed` list adds to this.
- On `Block`, Harbor opens `BlockActivity`: full screen, excluded from recents, it states the reason in plain words ("Instagram is blocked by your parent", "Your 60 minutes of YouTube for today are used up", "It's bedtime until 7:00") and offers only "Go to home screen". It never shows a fake system screen.
- Daily limits: time used today per app comes from `UsageStatsManager` (already granted for usage reporting), cross-checked with the service's own foreground sessions. The child gets a notification at 5 minutes and 1 minute remaining. Limits reset at local midnight.
- Policy, today's usage counters and pending events are stored encrypted on the phone and scoped to the child binding, like the alerts and location stores.
- Pause and bedtime block every app except the exempt and always-allowed ones.

### 3. Protection status and tamper reporting

- The child reports a small status with each activity upload: accessibility on/off, applied policy version, and the time it was observed.
- Turning off Harbor's accessibility service cannot be prevented without device owner. Harbor detects it the next time the app or its jobs run (and on boot), shows the child that controls are off, records a `protection_off` event, and the parent gets a generic push: "App controls are off on [child]'s phone." The parent view says "Controls are not being enforced" until it is turned back on.
- The child panel offers "Turn controls back on" (an explicit trip to Accessibility settings), the same pattern as Notification Access.

### 4. Activity timeline

- New signed child operations `report-device-activity` and `get-device-activity-checkpoint`, plus a parent read `get-device-activity`, following the alerts pattern: idempotent by event id, sequence checkpoint with clear/tombstone, revocation fence, 30-day retention, parent read paged by day.
- Event kinds: `app_session` (package, label, start, end; sessions shorter than 60 seconds are merged or dropped), `blocked_open` (package, reason), `limit_reached` (package), `pause_started`/`pause_ended`, `bedtime_started`/`bedtime_ended`, `protection_off`/`protection_on`.
- No screen content, window titles, URLs or text. Labels come from the package manager.
- Parent view: a day picker, a vertical timeline of sessions and events, and the day's totals per app (consistent with, but not replacing, the existing usage report).

### 5. Child UI

A "What Harbor controls" panel on the Today tab: the current rules in plain words (paused, bedtime hours, blocked apps, each limited app with time left today), whether controls are on, and "Only your parents can change these rules." There is no disable control. The first-run disclosure explains that Harbor will block apps the parent chooses and will tell the parent when an app is opened while blocked.

### 6. Parent UI

On the device page: a **Controls** screen (pause now with an end time, bedtime, app list with Block / Limit / Always allow, applied status) and an **Activity** screen (timeline). Writes are optimistic with the existing version check and one retry on a stale version, as the old device-controls branch did.

## Honest limits (shown to parents)

- A child can turn off the accessibility service in Settings, start in safe mode, or factory-reset the phone. Harbor reports this; it cannot prevent it.
- Blocking happens after the app opens; a blocked app can be visible for a fraction of a second.
- Some phone makers stop background services; enforcement may pause until Harbor is opened again. The status line shows this.
- Time used is measured on the phone and can differ from Digital Wellbeing by a minute or two.
- Changing the phone's clock or time zone affects bedtime and the daily reset. Harbor records time-zone changes as an event but does not override the phone's clock.
- Apps inside other apps (for example a browser opened from a chat app) count as the app shown on screen.
- Google Play would require an accessibility-use declaration; distribution stays on Firebase App Distribution for now.

## Testing and acceptance

- Contract tests for `controls.ts` and the activity contract (unknown keys, limits, conflicts, no content fields).
- pgTAP/Deno tests for the policy read path, activity write/read, idempotency, checkpoint, revocation fence, retention and grants (same set as alerts).
- JVM tests for `PolicyEngine` (every reason, exemptions, midnight reset, bedtime across midnight, pause with and without an end time, time-zone change) and for the activity reporter (offline queue cap, replay, no content in stored events).
- Instrumented tests: block screen wording, child panel has no disable control, accessibility service config cannot retrieve window content.
- Real-phone runbook: block an app, hit a limit, bedtime across midnight, pause and resume, offline enforcement, turning accessibility off reaches the parent, reboot, revocation clears policy and activity.

## Open questions for the owner

1. **Exempt apps:** besides the dialer, launcher, System UI, Settings and Harbor, should SMS/Messages and the camera be always allowed by default during pause and bedtime?
2. **Bedtime:** one window for every chosen day, or a different window per weekday (for example later on weekends)?
3. **"Ask for more time":** should the child be able to send a request from the block screen (parent approves with +15/+30 minutes)? This is useful but adds a request/approve flow; it can be phase 2.
4. **Web parent app:** should the browser parent app get Controls and Activity in this effort, or Android parent first?
5. **Short sessions:** is dropping app sessions under 60 seconds from the timeline acceptable (they still count toward limits and totals)?
