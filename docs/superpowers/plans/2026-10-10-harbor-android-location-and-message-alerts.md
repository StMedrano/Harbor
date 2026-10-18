# Harbor Android Location Sharing and Message Alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native inline). Steps use checkbox (`- [ ]`) syntax. Test first: write the failing test, see it fail (locally where possible, otherwise in CI), then implement.

**Goal:** A paired child phone shares its location and sends on-device-classified message alerts to its parents, with the child shown exactly what is shared and unable to stop it; parents read both in native Harbor.

**Architecture:** Mirror the usage-reporting pattern already proven on a real phone: pure on-device logic, an encrypted child-scoped queue, signed child ops (`report-*`, `clear-*`), private tables with RLS and sequence checkpoints/tombstones, and verified-parent read ops (`get-*`). Location uses a foreground service; message alerts use SMS receivers and a NotificationListenerService. Parent map uses OpenStreetMap through a client library.

**Spec:** `docs/superpowers/specs/2026-10-10-harbor-android-location-and-message-alerts-design.md`.

**Stack:** Kotlin/Compose, existing coroutines/serialization/Keystore graph, Fused Location Provider (Play services already present), osmdroid or MapLibre (chosen in L7), pinned Deno/Supabase/PostgreSQL, existing contracts package.

## Global Constraints

- Package `dev.stmedrano.harbor.parent`, min29/target36/compile37, JDK17, pinned build graph. New dependencies are limited to the location client and the map library; each must pass the repository's strict dependency verification (update verification metadata, never disable it).
- Excluded and not to be added: app blocking/limits, web filtering, SOS, device-admin/anti-removal, microphone/camera/screen capture, hidden icon or hidden notification, geofence alerts, check-ins.
- The child cannot stop sharing; no stop control, no in-app opt-out. Sharing starts automatically after pairing and permission grants. A parent stops it by revoking the device, which deletes that device's location and alert data.
- No message text, sender name/number, attachment or excerpt leaves the phone. Alerts carry app label, category, rule-set version, time, saved-contact flag only.
- Truthful states everywhere: permission lost, offline, never reported are never rendered as zero, safe or empty.
- Same fences as usage: role/owner/enrollment-generation checks on every completion; one encrypted pending queue per feature; sequence + tombstone; transaction-level revocation recheck; RLS with no client grants; verified non-anonymous parent with active membership for reads.
- Main/production and existing designs preserved. Deploy only to the development Supabase project after required GREEN; no merge inferred; PR #23 and the spec PR stay draft. Hourly automation stays paused.
- Keep credentials, endpoints, phone inventory, fixture identifiers and any location or alert content out of PR/Issue status and logs. Fixtures use synthetic coordinates and synthetic alert text only in tests.
- Fixture CI builds disable these runtimes. Never substitute CI fixtures for real-phone evidence.

## Review Focus

1. Foreground-service lifecycle: Android 12+ start restrictions, 14+ foreground-service-type permission, process death, and rearm only for a paired, consented child.
2. Permission truth: background location denied, location services off, precise vs approximate; each must show its own state.
3. Location and alert resurrection: opt-out is gone for children, but revoke/clear followed by a delayed upload must not recreate data (tombstone).
4. Classifier honesty and safety: false positives/negatives, rule-set versioning, generic push text, self-harm guidance copy.
5. Notification listener scope: only allowlisted packages inspected; text never logged, persisted or uploaded.
6. Map privacy: tiles only through the chosen host; no coordinates in URLs beyond tile indices; attribution shown.

## File Map and Shared Interfaces

Aliases: `A = apps/parent-android/app/src`; `K = A/main/java/dev/stmedrano/harbor/parent`; JVM tests `A/test/java/dev/stmedrano/harbor/parent`; native tests `A/androidTest/java/dev/stmedrano/harbor/parent`.

- `packages/contracts/src/v1/location.ts`, `alerts.ts` (strict versioned report/clear/read replies); exported from `src/index.ts`; tests in `packages/contracts/test/`.
- `supabase/migrations/<ts>_device_location.sql`, `<ts>_device_message_alerts.sql`; functions `report-device-location`, `clear-device-location`, `get-device-location`, `report-message-alert`, `clear-message-alerts`, `get-message-alerts` reusing `supabase/functions/_shared`.
- `K/location/`: `LocationModels.kt`, `LocationSource.kt` (platform), `LocationRuntime.kt`, `EncryptedLocationStore.kt`, `LocationReporter.kt`, `LocationForegroundService.kt`, `LocationPermission.kt`, `ParentLocationModel.kt`.
- `K/messages/`: `AlertModels.kt`, `MessageClassifier.kt` + `rules/` (versioned rule sets), `SmsReceiver.kt`, `HarborNotificationListener.kt`, `MonitoredApps.kt`, `EncryptedAlertStore.kt`, `AlertReporter.kt`, `AlertRuntime.kt`, `ParentAlertsModel.kt`.
- `K/ui/`: child consent/status panels, parent map and alerts screens, using HarborTheme/HarborComponents. Screens follow the owner's Figma file once readable; until then the web tokens.
- Docs: `docs/runbooks/android-location-acceptance.md`, `docs/runbooks/android-message-alerts-acceptance.md`.

---

## Part 1 — Location sharing (own stacked PR)

### Task L1: Contracts and wire models
- [ ] Write failing contract tests: valid batch (<=100 fixes, <=256 KiB), unknown fields rejected, lat/lon ranges, accuracy >=0 finite, fix times not >5 min in the future, sequence monotonic fields, clear request/reply, read reply shape (latest + window + report age).
- [ ] Implement `location.ts`; Kotlin `LocationModels.kt` with identical limits; round-trip serialization test.
- [ ] CI: contracts job GREEN. Commit.

### Task L2: Database and Edge functions
- [ ] Write failing pgTAP/Deno tests: report atomically appends and dedupes by (device, fix time); lower sequence rejected; same sequence+hash no-op, changed hash conflicts; clear creates tombstone and a delayed older upload cannot recreate; revoked device rejected inside the transaction; foreign family and lost membership denied on read; no direct anon/authenticated grants; 30-day retention purge; revoke deletes fixes but keeps checkpoint.
- [ ] Implement migration (private tables `device_location_fixes`, `device_location_checkpoints`, RLS, purge job) and the three functions (`verify_jwt=true`), reusing P-256 proof and nonce machinery.
- [ ] Extend the composed end-to-end Deno test (report -> parent read, replay, conflict, revoke). CI foundation job GREEN. Commit. No hosted rollout yet.

### Task L3: Pure on-device logic
- [ ] Write failing JVM tests for: batching and dedupe, low-accuracy flagging (>500 m), cadence selection (5 min moving / 15 min stationary), queue cap (latest 500), clock-skew rejection, permission/state mapping (`SHARING`, `PERMISSION_LOST`, `BACKGROUND_MISSING`, `SERVICES_OFF`, `OFFLINE`, `NEVER`).
- [ ] Implement the reducer/state types. Commit.

### Task L4: Platform source, store, reporter, runtime
- [ ] Failing JVM tests (fakes) for `LocationRuntime`: starts only for a valid consented child lease; cancels/joins before profile transition; late upload cannot touch a newer profile; offline queue replays idempotently; clear/revoke fence; no bearer or token in service extras.
- [ ] Implement `LocationSource` (Fused provider with LocationManager fallback), `EncryptedLocationStore` (child-binding scoped), `LocationReporter` (signed ops), `LocationRuntime`. Reuse `UsageReporter` proof plumbing via a shared interface rather than copying.
- [ ] CI JVM + lint + APKs GREEN. Commit.

### Task L5: Foreground service, manifest, permissions
- [ ] Failing instrumented tests (API 29 and 36): service starts with `foregroundServiceType=location`, shows the non-dismissible notification, rearms on app start for a paired child only, never starts on parent/setup profiles.
- [ ] Manifest: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, service entry. Permission flow: foreground grant first, then the separate background step with an in-app explanation; recheck on resume and before every collection.
- [ ] Implement `LocationForegroundService` and `LocationPermission`. CI GREEN. Commit.

### Task L6: Child UI
- [ ] Failing Compose tests: first-run disclosure text (what is shared, how often, who sees it, "only your parents can turn this off"), permission steps with truthful states, persistent status line, no stop/disable control, status never claims sharing when permission is lost.
- [ ] Implement panels in the child Today/About tabs; auto-start after pairing as with usage. Commit.

### Task L7: Parent map
- [ ] Choose osmdroid vs MapLibre (record the choice and why in this plan); verify dependency metadata and OSM attribution/User-Agent requirements.
- [ ] Failing tests: `ParentLocationModel` generation fence (a late response for another child/device is dropped), staleness banner after 30 minutes, state labels, access lost hides data immediately, last-update age.
- [ ] Implement the parent map screen: latest fix, accuracy circle, history window list, empty/never-reported states. Provide a tile-host config value (development default documented, production blocked until a permitted host is chosen). Commit.

### Task L8: Rollout and real-phone acceptance
- [ ] Apply migration and deploy the three functions to the development Supabase project only; verify versions, existing functions untouched.
- [ ] Write `docs/runbooks/android-location-acceptance.md`: grant permissions, first fix reaches the parent map, move/stationary cadence, airplane-mode queue and replay, location-off and background-permission-revoked states, revoke removes server data, cold restart with service rearm, fixture cleanup (exact rows, audits retained, zero owned rows verified).
- [ ] Build a new versionCode via the existing Windows build script; owner installs over the existing app (no uninstall). Run acceptance on the real phones; record only non-identifying results.
- [ ] Final whole-feature review by a fresh reviewer; fix in one test-first pass. Report CI SHAs/runs and honest limits (for example: location accuracy depends on the phone, cadence is opportunistic under battery limits).

---

## Part 2 — Message alerts (second stacked PR, after Part 1 is accepted)

### Task M1: Contracts and wire models
- [ ] Failing tests: alert shape (id, app label <=64 chars, category enum, rule-set version, time, savedContact boolean), unknown fields rejected, explicit tests that no text/sender/excerpt field exists; read paging reply.
- [ ] Implement `alerts.ts` and `AlertModels.kt`. Commit.

### Task M2: Database and Edge functions
- [ ] Failing tests: idempotent by alert id, checkpoint/tombstone as in L2, revocation fence, foreign family denied, retention 30 days, parent read paging, no direct grants, generic push payload contains no alert content.
- [ ] Implement migration and functions `report-message-alert`, `clear-message-alerts`, `get-message-alerts`; parent push via the existing FCM fan-out with generic text. CI GREEN. Commit.

### Task M3: Classifier (pure, highest scrutiny)
- [ ] Write fixture tables first (positive and negative cases per category: bullying/harassment, self-harm/wellbeing, adult content, drugs/alcohol, violence/threats, unknown-adult contact), English only, synthetic text. Include negative cases that must not alert (song lyrics, school topics, jokes) and obfuscation cases (spacing, leetspeak).
- [ ] Implement `MessageClassifier` with versioned rule sets in `rules/`; output is category + rule-set version only. Property test: classifier output never contains input text. Commit.

### Task M4: Capture paths and allowlist
- [ ] Failing tests: `MonitoredApps` allowlist (Messages, WhatsApp, Facebook, Messenger, Instagram, TikTok, Snapchat, Discord, Telegram, X) matches package names exactly and ignores everything else before any text is read; notification text is never logged or persisted; group-summary and duplicate notifications deduped by key.
- [ ] Implement `HarborNotificationListener` (explicit trip to Notification Access settings, recheck like Usage Access, truthful `ACCESS_OFF` state) and `SmsReceiver` (`RECEIVE_SMS`, `READ_SMS`); saved-contact lookup uses a boolean-only check and no contacts leave the phone. Manifest additions; confirm the listener cannot be bound by other apps.
- [ ] CI JVM + instrumented GREEN. Commit.

### Task M5: Runtime, store, reporter
- [ ] Failing JVM tests as in L4: lease/fence, offline queue (cap 200 alerts), idempotent replay by alert id, no text in stored or queued data, revoke/clear fence.
- [ ] Implement `EncryptedAlertStore`, `AlertReporter`, `AlertRuntime`. Commit.

### Task M6: Child UI
- [ ] Failing Compose tests: "What Harbor watches" panel lists monitored apps, categories, what is sent and not sent, honest limits (muted/hidden/E2EE content missed, mistakes possible), alert counts only, Notification Access status, no disable control.
- [ ] Implement the panel and the first-run disclosure; auto-start after pairing and permission grants. Commit.

### Task M7: Parent alerts
- [ ] Failing tests: `ParentAlertsModel` fence, "possible concern" wording (never a verdict), self-harm category shows guidance and crisis-resource copy, lock-screen notification text is generic, access lost hides data.
- [ ] Implement alerts list and detail (app, category, time, saved-contact flag) with the standing limits notice. Commit.

### Task M8: Rollout and real-phone acceptance
- [ ] Deploy migration/functions to development only. Write `docs/runbooks/android-message-alerts-acceptance.md`: enable Notification Access and SMS permission, a synthetic test message from a second phone triggers one alert in the parent view with no text anywhere, a muted/hidden-preview case documented as a limit, offline replay, revoke deletes alerts, cold restart, cleanup with audits retained.
- [ ] Build, install over existing app, run acceptance, final fresh review, one fix pass, honest report of limits and CI SHAs.

---

## Cross-cutting checks (each part)

- [ ] Existing usage reporting, pairing, notifications and parent flows still pass their unchanged tests.
- [ ] Lint and strict dependency verification GREEN in CI; APK package/signing continuity and versionCode > 14 verified on the owner's build; deliver ZIP + SHA256 only after acceptance; development prerelease only.
- [ ] Update `docs/runbooks`, private ledger and PR text without identifiers; PRs remain draft; nothing merged without explicit owner authorization.
