# Harbor Android activity and app control — implementation plan

Spec: `docs/superpowers/specs/2026-10-20-harbor-android-activity-and-app-control-design.md`
Branch: `feat/activity-app-control` (stacked on `fix/message-alert-allowlist` → `feat/checkin-alert`). Draft PR only; nothing merged without the owner's authorization. Development Supabase project only.

## Global constraints

- Test first for every task; each task ends GREEN (contracts, functions, JVM with `-PparentCiFixture=true`) and is committed.
- No screen content, window titles, URLs or text anywhere in contracts, storage, logs or uploads.
- Every new signed child operation goes into BOTH allowlists (`ChildApi.signedUsage`, `UsageHttpTransport.execute`) and the `everySignedChildOperationReachesHttp` test.
- Every new function/migration is deployed to development in the rollout task and verified (versions, grants, RLS, advisors) — not left for later.

## Tasks

### C1: Contracts
- [ ] `packages/contracts/src/v1/controls.ts`: `ControlsV1` (pause, bedtime, blockedApps, appLimits, alwaysAllowed), `validateControls`; limits and conflict rules from the spec. Tests: valid policy, unknown keys, bad clock, start=end, package format, duplicates, blocked∩alwaysAllowed, limit ranges, list caps.
- [ ] `packages/contracts/src/v1/activity.ts`: activity report (epoch, sequence, observedAt, status {controlsOn, policyVersion}, events[]), event kinds, clear, checkpoint, parent read request/reply paged by day. Tests incl. "no text field exists".
- [ ] Kotlin `controls/ControlsModels.kt`, `activity/ActivityModels.kt` with identical limits; shared JSON fixtures used by both test suites.

### C2: Backend
- [ ] `update-device-state` validates `desiredState.controls` with `validateControls`.
- [ ] Migration `device_controls_and_activity`: `harbor_list_family_device_states` (from the old controls branch), `private.device_activity_events`, `private.device_activity_status`, `private.device_activity_checkpoints`, store/clear/checkpoint/read functions (alerts pattern), revocation trigger, 30-day retention cron, `protection_off` parent push (generic text, throttled).
- [ ] Functions: `get-device-controls`, `report-device-activity`, `clear-device-activity`, `get-device-activity-checkpoint`, `get-device-activity`. Deno tests mirroring `message-alerts.test.ts`.

### C3: Policy engine (pure Kotlin)
- [ ] `PolicyEngine.decide(pkg, nowLocal, policy, usedTodayMs, exempt)`; tests for each reason, exemptions, always-allowed, bedtime across midnight, day selection, pause with/without end, midnight reset, limit boundary.

### C4: Child enforcement
- [ ] Child sync reads `desiredState.controls` and acknowledges only after the policy is stored; `EncryptedControlsStore`.
- [ ] `HarborControlService` (window-state events only, `canRetrieveWindowContent=false`), `BlockActivity`, usage counters from `UsageStatsManager`, 5/1-minute warnings, exempt-app resolution (default dialer, SMS, launcher).
- [ ] Manifest + service config; instrumented test that config cannot read window content.

### C5: Child activity reporter
- [ ] `ActivityRecorder` (sessions ≥60 s, control events), `EncryptedActivityStore` (queue cap 500), `ActivityReporter` (signed ops, checkpoint repair), runtime wired like alerts. Allowlists updated.

### C6: Child UI
- [ ] "What Harbor controls" panel: rules, time left, controls-on status, "Turn controls back on", no disable control. Compose tests.

### C7: Parent UI
- [ ] Controls screen (pause, bedtime, app list from inventory with Block/Limit/Always allow, applied status) with versioned writes.
- [ ] Activity screen (day picker, timeline, totals). Model fences as in `ParentAlertsModel`.

### C8: Rollout and acceptance
- [ ] Deploy migration + functions to development; verify. Runbook `docs/runbooks/android-app-control-acceptance.md`. Version bump, build, owner acceptance on real phones.
