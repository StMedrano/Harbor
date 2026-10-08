# Harbor Android Usage and App Reporting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user has already selected native inline execution; use executing-plans, not a new execution-method menu.

**Goal:** Collect genuine Android foreground usage and launchable-app inventory on a paired child phone and show scoped, timestamped, read-only parent reports in native/web Harbor.

**Architecture:** Extend the existing native profile/Keystore foundation with a pure measurement reducer, platform collectors and an encrypted child-scoped latest-report queue. Add signed report/clear operations and a scoped parent read API backed by private atomic snapshots/checkpoints. Reuse existing platform jobs, Auth/proof and Harbor visual tokens; no remotely loaded JavaScript bridge.

**Tech Stack:** Kotlin/Compose, existing coroutines/serialization/Keystore graph, Android UsageStatsManager/PackageManager/JobScheduler; pinned Deno/Supabase/PostgreSQL; existing Vite web frontend and Node built-in tests.

**Spec:** `docs/superpowers/specs/2026-10-08-harbor-android-usage-reporting-design.md`, approved by the user after commit `6ddf67a`.

## Global Constraints

- Native package `dev.stmedrano.harbor.parent`, min29/target36/compile37, JDK17, existing AGP9.1.1/Gradle9.3.1 and locked dependencies; no new runtime library.
- Scope: measured foreground app use and launchable apps in the current Android user/profile. No limits, blocking, location, SOS, device-owner, accessibility/overlay/VPN privileges or QUERY_ALL_PACKAGES.
- Add only PACKAGE_USAGE_STATS and MAIN/LAUNCHER visibility. Explicit child reporting opt-in; grant via Usage Access Settings and recheck AppOps before collection.
- Seven local calendar days; coverage/zone/UTC bounds/partial history mandatory. Aggregate total is interval union, not sum of per-app times. No invented zero, seven-day completeness or Digital Wellbeing equivalence.
- Raw events/class names never persisted/uploaded. Reports contain package identifiers, bounded labels and durations; icons remain local.
- Limits: 1 MiB UTF-8 JSON, 500 inventory packages, 3,500 package/day rows, timestamps no more than five minutes ahead; permission/quality states and truncation are explicit.
- Exactly one encrypted pending snapshot plus clear state. Role/owner/enrollment generation fence every completion. Preserve parent/child Auth, proof, notification, pairing and MFA contracts.
- Platform periodic interval requested at 15 minutes; no real-time guarantee, foreground service or reboot receiver. Foreground re-arms jobs.
- Private backend tables/RLS/no client grants; current verified parent membership required. Snapshot TTL 30 days; daily purge, no audit/active-fixture deletion. Sequence tombstone survives opt-out and ordinary restart.
- Main/production and existing checkouts remain preserved. Deployment only to designated development after required GREEN and final review; no merge inferred. Hourly task stays paused.
- Keep credentials/endpoints/inboxes/fixture identifiers out of PR/Issue status; never ask for private keys/passwords in chat or substitute CI fixtures for real measurements.

## Review Focus

1. Activity-instance collisions/multi-window and launcher transitions: observed total must not double-count or silently keep stopped activities active (Task 2).
2. OEM empty history, locked users and mid-day timezone/clock changes: unknown coverage must remain distinguishable from zero (Tasks 2–3).
3. Long/hostile application labels or more than 500 packages: safe rendering and explicit deterministic truncation, no remote HTML/icons (Tasks 1, 3, 7).
4. Opt-out followed by a delayed upload or rapid new opt-in: persistent sequence/tombstone must prevent resurrection while permitting a deliberate newer report (Tasks 4–6).
5. Parent access lost while a report is loading, or revoked child concurrently uploading: transaction and UI fences must immediately deny/hide data (Tasks 5–7).

## File Map and Shared Interfaces

Android paths below use `A = apps/parent-android/app/src`; `K = A/main/java/dev/stmedrano/harbor/parent`; JVM tests use `A/test/java/dev/stmedrano/harbor/parent`; native tests use `A/androidTest/java/dev/stmedrano/harbor/parent`. Expand these aliases literally; they are not new directories.

- `packages/contracts/src/v1/usage.ts`: versioned report, clear/read replies and strict validation; `src/index.ts` exports them.
- `K/usage/UsageModels.kt`: Kotlin wire/local types matching the contract. `UsageReducer.kt`: pure interval/coverage/calendar logic. `AndroidUsageSource.kt`, `AndroidAppInventory.kt`: platform reads only.
- `K/usage/EncryptedUsageStore.kt`: aggregate/checkpoint/queue persistence in a new `harbor-child-usage` namespace using existing SecureAuthStore/KeystoreCipher. `UsageReporter.kt`: signed upload/clear and parent read adapter. `UsageRuntime.kt`, `UsageJobService.kt`: profile lifecycle and platform jobs.
- `K/ui/UsageSetupScreen.kt`, `UsageReportScreen.kt`: permission/consent and read-only views. Modify existing ChildDashboard, DeviceScreen, ParentApplication and MainActivity only at integration seams.
- `supabase/functions/_shared/usage.ts`: strict payload/domain validation; `usage-persistence.ts`: private SQL calls. Four new function entrypoints: `report-device-usage/index.ts`, `clear-device-usage/index.ts`, `get-device-usage/index.ts`, `get-device-usage-checkpoint/index.ts`.
- New migrations generated with the existing CLI at execution time: `device_usage_snapshots` and `device_usage_retention`. Do not invent timestamp filenames. No migration is applied while implementing tasks 1–8.
- `web/harbor-family/js/usage-view.js`: pure read-only render/view mapping. Modify supabase-api.js, api.js, parent.js and device-scoped view loading without unrelated Auth changes.

`UsageReportV1` fields: version:1, epochId:UUID, sequence:safe positive integer, observedAt:ISO UTC, zoneId:IANA zone, usagePermission:'granted'|'denied'|'unavailable', inventoryStatus:'complete'|'truncated'|'unavailable', inventory:[{packageName,label}], days:[{localDate,startAt,endAt,observedThrough,coverageStart,quality:'partial'|'observed'|'unavailable',totalMs,apps:[{packageName,foregroundMs}]}]. Null durations represent unavailable; they never become zero. Observed timestamps and elapsed durations use integer milliseconds. Day package rows plus inventory obey the global caps. Every report is marked device-observed, not server-attested.

`ClearUsageV1 = {version:1,epochId:UUID,sequence:positive integer}`. `UsageWriteReplyV1 = {confirmed:true,sequence,receivedAt}`. `UsageReadReplyV1 = {state:'available'|'none'|'expired',report:UsageReportV1|null,receivedAt:string|null}`; inaccessible devices return authorization denial, not an empty authorized report. The server derives all binding authority; none of these bodies carries family/child/auth IDs.

Checkpoint types: `UsageCheckpointRequestV1 = {version:1}` and `UsageCheckpointReplyV1 = {sequence:nonnegative safe integer,epochId:UUID|null}`. A missing checkpoint returns sequence0/epochId:null only for the exact non-revoked signed binding. Export and validate these in Task1; implement the signed read-only endpoint in Task5. It returns no report contents or client authority fields.

Pure local types: `UsageEvent(atMs:Long,kind:UsageEventKind,packageName:String?,instanceId:Int?)`; kinds RESUMED, PAUSED, STOPPED, SCREEN_ON, SCREEN_OFF, UNLOCKED, LOCKED, STARTUP, SHUTDOWN, CLOCK_GAP. `UsageWindow(startMs:Long,endMs:Long,zoneId:String)`. `UsageReduction(days:List<UsageDay>,coverage:UsageCoverage)` mirrors wire day/quality fields. `reduceUsage(events:List<UsageEvent>,window:UsageWindow):UsageReduction`.

`UsageSource.read(window:UsageWindow):UsageSourceResult` returns Observed(events), PermissionDenied, UserLocked or Unavailable; it never infers coverage merely from an empty list. `AppInventorySource.read():InventoryResult` returns timestamped complete/truncated/unavailable entries. `UsageStore.read(bindingId:String):UsageStoredState?`, `update(bindingId:String,change:(UsageStoredState)->UsageStoredState):UsageStoredState`, `clearPayload(bindingId:String)` retain checkpoint/sequence but erase metric content; `eraseBinding(bindingId:String)` clears the namespace after authoritative enrollment cleanup.

`UsageStoredState` contains bindingId, epochId, nextSequence, consent, latest aggregate, one PendingReport(report,bodyUtf8,hash) or PendingClear(clear,bodyUtf8,hash), lastConfirmedSequence/receivedAt, and coverage checkpoint. Persist sequence and pending exact bytes atomically before sending. Reuse an existing sequence/body on retry; new desired state advances sequence. No Auth/provider token is persisted here.

## Execution Setup (after written-plan approval)

- [ ] Read the spec and this plan; fetch actual main/open PRs/latest CI. Use an isolated feature branch based on approved spec/plan ancestry and current main after reconciliation. Do not reset or reuse the original parent/notification checkout. Record exact task-start BASEs in `.superpowers/sdd/2026-10-08-harbor-android-usage-reporting/progress.md` and preserve native inline execution.
- [ ] Run baseline focused Deno backend/contracts and native JVM/lint/assembly with strict verification and CI fixtures. Record a pre-existing failure rather than suppressing it. Inspect current official Android/Supabase documentation before API/schema implementation.
- [ ] Do not install product dependencies, create schema, write product scaffolding or run rollout before approval. Plan artifacts themselves are the only current changes.

### Task 1: Versioned bounded report contracts

**Files:** create `packages/contracts/src/v1/usage.ts`, `packages/contracts/test/usage.test.ts`, `K/usage/UsageModels.kt`, JVM `usage/UsageContractTest.kt`; modify `packages/contracts/src/index.ts`.
**Interfaces:** produce all wire types above and `validateUsageReport(value:unknown,nowMs:number):UsageReportV1`, `validateClearUsage(value:unknown):ClearUsageV1`; Kotlin serializers reject unknown fields and preserve nulls/quality.

- [ ] Write RED contract tests `rejectsAuthorityAndOversize`, `preservesUnknownDurations`, `boundsDailyElapsed`, `rejectsDuplicatePackages`, `truncationIsExplicit`, `crossLanguageRoundTrip`. Assertions: encoded bytes 1,048,577 rejected; 501 inventory entries and 3,501 day rows rejected; a 1,048,576-byte otherwise valid report permitted; totalMs:null stays null; totalMs > observedThrough-startAt rejected; unknown familyId rejected; label HTML round-trips as text; invalid/duplicate date/zone/package rows rejected.
- [ ] Run `deno test --frozen packages/contracts/test/usage.test.ts`; run `./gradlew -PparentCiFixture=true --dependency-verification=strict testDebugUnitTest --tests '*UsageContractTest'` from the native project. Observe behavioral RED, not only compilation absence.
- [ ] Implement report/clear/checkpoint strict types/validators/serialization: labels <=200 Unicode code points, packageName <=255 ASCII characters following Android-style dotted identifiers, no control characters; omit unsafe labels in favor of package text. Require day boundaries/zone to agree, unique dates in an epoch, oldest date no earlier than six days before observedAt local date; quality unavailable requires null totals and no fabricated app times.
- [ ] Run the focused tests then existing contracts; GREEN means explicit cross-language wire fixtures pass without relaxing payload limits or existing contracts.
- [ ] Commit `feat: define bounded usage reporting contracts` and record proof.

### Task 2: Deterministic usage measurement and coverage

**Files:** create `K/usage/UsageReducer.kt`, JVM `usage/UsageReducerTest.kt`.
**Interfaces:** consumes Task1 local models; produces `reduceUsage` and `reconcileUsage(previous:UsageReduction?,events:List<UsageEvent>,window:UsageWindow):UsageReduction`. Reconcile replaces overlapping observed ranges, never increments the same observation twice.

- [ ] Write RED `unionDoesNotDoubleCount`: A active [0,60000), B active [30000,90000), screen unlocked/interacting => total 90000, each app 60000. Add distinct activity-instance pauses, duplicate events, launcher/system exclusion, screen lock and stop without pause. Assertions pin unions rather than app-duration sum.
- [ ] Add `unknownStartIsPartialNotZero`, `midnightSplitsCorrectly`, `dstUsesElapsedBounds`, `timezoneStartsPartialEpoch`, `rebootAndClockRollbackCloseCoverage`, `repeatWindowDoesNotAccumulate`. US DST fixture must create a 23-hour date boundary; no bucket receives duration across shutdown/unknown coverage.
- [ ] Run `./gradlew ... testDebugUnitTest --tests '*UsageReducerTest'` with the same CI/strict flags; observe exact arithmetic/quality RED.
- [ ] Implement a stable event sweep: track active instances, interactive/keyguard state, merge package intervals, union for total, split only at zone-aware local midnights and coverage boundaries. Unknown state remains unknown until observable transitions. Bound to seven-day window; later timezone/discontinuity creates a new epoch and partial rebuild.
- [ ] Run focused reducer/contract tests GREEN, then commit `feat: measure observed foreground usage without double counting`.

### Task 3: Android permission and launchable inventory adapters

**Files:** create `K/usage/AndroidUsageSource.kt`, `AndroidAppInventory.kt`, `UsagePermission.kt`; modify `A/main/AndroidManifest.xml` and `apps/parent-android/config/ci-manifest.xml`; create JVM `usage/UsageSourceTest.kt`, native `usage/UsagePlatformTest.kt`.
**Interfaces:** implement UsageSource/AppInventorySource; `UsagePermission.isGranted():Boolean`, `settingsIntent():Intent`; testable constructor dependencies separate platform access from the reducer.

- [ ] Write RED adapter tests for AppOps denied, locked-user null, SecurityException, empty event results, invisible/non-launchable packages and duplicate launch activities. Assertions: no permission => no platform read; null/empty never yields measured-zero coverage; 501 visible packages => 500 deterministic entries and truncated status; hostile labels remain text.
- [ ] Run focused JVM tests, then publish test-only instrumentation for API29/36 if an actual device host is required to observe RED. Do not implement platform behavior merely from shape stubs.
- [ ] Implement explicit ACTION_USAGE_ACCESS_SETTINGS intent and granted AppOps checks, API29-compatible events with instance IDs, UserManager unlocked guard, MAIN/LAUNCHER PackageManager query scoped by manifest queries. Extract raw class names only transiently if needed for instance correlation; do not persist/upload them.
- [ ] Run actual API29/36 Settings round-trip/permission-denial/package visibility tests; packaged manifest must contain only the approved permission/query additions, no QUERY_ALL_PACKAGES or privileged services. CI fixture INTERNET isolation remains intact.
- [ ] Commit `feat: read Android usage and launchable apps with explicit access`.

### Task 4: Encrypted aggregate/checkpoint and latest queue

**Files:** create `K/usage/EncryptedUsageStore.kt`, JVM `usage/UsageStoreTest.kt`, native `usage/UsagePersistenceTest.kt`; use existing AuthValues, SecureAuthStore and KeystoreCipher interfaces, with a separate alias/namespace.
**Interfaces:** implement UsageStore and UsageStoredState above; `newPendingReport(bindingId:String,report:UsageReportV1):PendingReport`, `newPendingClear(bindingId:String):PendingClear` atomically allocate/persist sequence and bytes.

- [ ] Write RED `restartKeepsSequenceAndExactBytes`, `newReportReplacesOnePending`, `clearPreservesHigherCheckpointButErasesMetrics`, `foreignBindingCannotRead`, `keyLossDoesNotResetToSequenceOne`, `sevenDayPruning`, `newConsentAdvancesClearSequence`. Assert at most one pending object, no tokens in serialized state, retry bytes/hash identical and sequence strictly advances after clear.
- [ ] Run JVM then actual force-stop/reopen/API29/36 Keystore RED. Do not read/write the existing parent/child Auth namespace to make a test pass.
- [ ] Implement atomic one-record encrypted persistence, seven-day aggregate pruning and durable deletion state. Unreadable checkpoint is blocked for this binding until authenticated server checkpoint recovery; it must not blind-reset. Clear payload on opt-out while retaining encrypted pending clear/checkpoint. Erase alias only after authoritative enrollment cleanup.
- [ ] GREEN requires actual cold process restore, key loss, old-binding isolation and no secret/raw-event fields. Existing backup exclusions cover the namespace and are verified in the APK.
- [ ] Commit `feat: persist scoped usage aggregates and replay-safe queue`.

### Task 5: Private atomic backend reporting, read authorization and TTL

**Files:** create `_shared/usage.ts`, `_shared/usage-persistence.ts`, four new function entrypoints under `supabase/functions`, `tests/functions/usage.test.ts`, `tests/integration/usage-persistence.test.ts`, `supabase/tests/database/usage.test.sql`; generate two migration files with `supabase migration new device_usage_snapshots` / `device_usage_retention`. Modify CI to include the new integration test in the existing local PostgreSQL job.
**Interfaces:** TS functions `writeUsage(ctx:DeviceProofContext,report:UsageReportV1,hash:string):Promise<UsageWriteReplyV1>`, `clearUsage(ctx:DeviceProofContext,input:ClearUsageV1,hash:string):Promise<UsageWriteReplyV1>`, `readUsage(parent:ParentContext,deviceId:string):Promise<UsageReadReplyV1>`, `readUsageCheckpoint(ctx:DeviceProofContext):Promise<UsageCheckpointReplyV1>`.

- [ ] Write handler RED for missing/foreign signature, replay nonce, signed operation mismatch, rejected authority fields, malformed/capped report and anonymous/foreign parent. Write real PostgreSQL RED: seq7 clear then delayed seq6 upload leaves payload absent; identical seq7 hash no-op; signed foreign/revoked checkpoint read denied and absent authorized checkpoint returns0/null; changed seq7 hash conflicts; concurrent revoke prevents report and removes payload; parent membership removal denies a delayed read.
- [ ] Run focused Deno tests plus real local PostgreSQL under existing CI; inspect actual persistence RED before SQL repairs. Use isolated fixtures and close shared SQL pool once; do not repeat the old pool-closing harness bug.
- [ ] Bound request-stream reading to 1 MiB before JSON parsing; oversized streams fail without buffering the full body. Implement private `device_usage_snapshots` (device PK/current report/receivedAt/expiresAt) and `device_usage_checkpoints` (device PK/epoch/last sequence/hash/clear marker). Restrict schema/table/function grants, RLS and fixed search paths. Lock the public device row before rechecking binding/revocation, then checkpoint/snapshot in one transaction. Parent read checks current verified membership and device authorization in that transaction. Existing revoke transaction uses an additive AFTER UPDATE revoked_at trigger to delete telemetry payloads while preserving baseline revoke/domain/outbox behavior.
- [ ] Add server-received 30-day TTL, parent expired state and daily named cron job `harbor-usage-retention` at `17 3 * * *`. Janitor deletes expired snapshots only, retains sequence checkpoints/audits, returns deleted count; pg_cron/ownership/grants capability is a rollout prerequisite. Generate migration IDs using CLI, not manual timestamps. Snapshot JSON must satisfy the validated contract; SQL still enforces sequence/race/ownership constraints independently.
- [ ] Run function/contracts/pgTAP and real PG tests GREEN. Assert private grants absent, anonymous enabled but no active-parent role, baseline revocation and existing fanout tests unchanged. Commit `feat: add signed scoped usage snapshot APIs and retention`.

### Task 6: Signed native runtime, consent and platform jobs

**Files:** create `K/usage/UsageReporter.kt`, `UsageRuntime.kt`, `UsageJobService.kt`, JVM `usage/UsageRuntimeTest.kt`, `UsageReporterTest.kt`; modify ParentApplication, ChildRepository integration seams and manifests/job lifecycle. Use a new constant job ID 4103 after proving it does not collide with current IDs.
**Interfaces:** `UsageReporter.upload(lease:ProfileLease,pending:PendingReport):UsageWriteReplyV1`, `clear(lease:ProfileLease,pending:PendingClear):UsageWriteReplyV1`, `recoverCheckpoint(lease:ProfileLease):Long`; `UsageRuntime.start(lease:ProfileLease)`, `stop()`, `refresh(lease:ProfileLease)`, `setSharing(lease:ProfileLease,enabled:Boolean)`. Consume the signed `get-device-usage-checkpoint` endpoint and exact Task1 checkpoint types implemented by Task5. Recovery returns the server checkpoint only for the verified non-revoked binding. Advance above that sequence and require explicit consent before a new report; never reuse unknown old pending bytes.

- [ ] Write RED owner/generation-change before send and after reply; Settings return without grant; permission revoked between enumeration/upload (old metric upload discarded, higher-sequence permission-lost report queued); cancellation; job cold start; opt-out with queued upload; same-binding new opt-in; key-loss checkpoint recovery; valid provider-independent signed bytes. Assert job extras contain only role/owner/generation references and no token/report/raw data; parent/setup never reads usage.
- [ ] Run `./gradlew ... testDebugUnitTest --tests '*UsageRuntimeTest' --tests '*UsageReporterTest'`; use actual HTTP MockEngine verification of one captured bearer and canonicalChildProof operation/body, not only mocked method calls.
- [ ] Implement bounded 15s collection/report attempts under the existing profile-owned SupervisorJob. Persist exact body before signing/upload; fresh request timestamp/nonce signs unchanged retry body. Revalidate opt-in/AppOps/binding and cancellation after every suspend. On clear, cancel/join collector first, persist higher tombstone, erase payload, attempt remote clear; pending remains honest offline. No clear acknowledgement can erase a newer report. Missing checkpoint recovery remains blocked, never guessed.
- [ ] Platform JobService job4103 requests 15-minute periodic work; exact extras whitelist, current opted-in child only, jobFinished/cancellation lifetime, no persisted tokens. On stop/revoke/profile change cancel jobs and clear/hide telemetry; opt-in and valid lease re-arm on foreground. Existing notification job4102/service wire formats remain unchanged.
- [ ] Run focused tests and native cold-job/reconnect/opt-out assertions GREEN; commit `feat: collect and sync usage under the validated child lifecycle`.

### Task 7: Native reporting surfaces and read-only web dashboard

**Files:** create `K/ui/UsageSetupScreen.kt`, `UsageReportScreen.kt`, native `usage/UsageUiTest.kt`, JVM `usage/UsageViewStateTest.kt`; modify ChildDashboard, DeviceScreen, MainActivity and ParentApplication. Create `web/harbor-family/js/usage-view.js`, `web/harbor-family/test/usage-view.test.js`; modify js/supabase-api.js, api.js, parent.js and package.json test script.
**Interfaces:** `UsageViewState` holds only scoped report/status/collection/upload/coverage times; native `UsageReportScreen(state:UsageViewState,onRefresh:()->Unit)`. JS `toUsageView(reply,nowMs)` and `renderUsageView(view):string` escape all labels; API `getDeviceUsage(deviceId):Promise<UsageReadReplyV1>` and no-op unavailable value only for browser-child collection. Show stale when server-confirmed receipt age exceeds 30 minutes, always show timestamps and quality.

- [ ] Write RED native Today/Apps views for no data, denied, partial/null, measured totals, stale/offline and revocation. Parent access loss during load hides prior report. Web assertions: unknown date is not bar0, malicious label is escaped, app inventory exposes no set-limit/blocked/hard-stop UI and overlapping app sums do not replace total. Under denied/missing report, no optimistic restriction or usage claim is visible.
- [ ] Run focused JVM/UI and `node --test web/harbor-family/test/usage-view.test.js` RED, then implement the smallest mapping/rendering. Preserve verified menu/role/Auth boundaries. Explicit native consent/Usage Access/stop-sharing controls; inventory disclosure separately visible. Web child shows native reporting unavailable.
- [ ] Reuse existing Harbor theme/components and supplied web hierarchy; only reporting surfaces change. Do not transplant unimplemented location/SOS/filter controls or rewrite Auth. Parent telemetry stays in owner/device-scoped memory only; no plaintext Room/localStorage cache. Clear it on logout, membership loss or device switch, and fence loading replies by captured identity/device. Parent native/web refresh explicit/on foreground; no telemetry push/event version changes.
- [ ] Run actual API29/36 font1.8/light-dark/scroll/Back/TalkBack-label tests and web renderer tests; `npm ci && npm run build` in web project. Inspect screenshots and exact native logs rather than infer appearance from code.
- [ ] Commit `feat: show measured usage and launchable apps without enforcement claims`.

### Task 8: Composed acceptance, full CI and one feature review

**Files:** add `tests/end-to-end/usage-reporting.test.ts`, `tests/hosted/usage-reporting-acceptance.ts`, `docs/runbooks/android-usage-reporting-acceptance.md`; extend `apps/parent-android/scripts/keystore-acceptance.sh` and CI artifact collection for new native cases. Reuse current hosted exact-fixture/private ACL helpers.
**Interfaces:** operator accepts private local fixture configuration via existing protected-input conventions and exposes only sanitized stage counts/outcomes. Journal/report payloads are private, credential-free where exported and bound to exact fixture ownership; no actual personal phone inventory in public artifacts.

- [ ] Write RED composed tests: real signed child report -> matching parent read; cross-family denial; clear/new-report/delayed replay; membership loss/revoke races; retention expiration; absent permission/status not zero; offline latest-only restore. Preserve independent exact cleanup and audits.
- [ ] Run focused composition tests with real local PG; do not manufacture a RED where existing helpers already implement the required behavior. New production changes still require the responsible failing regression first.
- [ ] Complete runbook and controlled timing procedure: two Android roles/devices, observed test-app foreground intervals, lock/multi-window/day boundaries, explicit permission/opt-out, parent native/browser reads. Distinguish deterministic fixtures from real phone observation and record actual tolerances; no exact Digital Wellbeing claim.
- [ ] Run required full foundation/notification/parent/native29/native36 CI plus new web tests/build on the exact final SHA. Inspect PG and native logs. Conduct ONE fresh whole-feature review and ONE test-first material fix pass; follow receiving-review rules, no repeated reviewer/polish loops.
- [ ] Matching GREEN/review completion commits evidence/runbook and marks Task8 done. Real rollout/phone proof remain Task9; do not label the whole feature complete here.

### Task 9: Reviewed development rollout, real phones and APK delivery

**Files:** update acceptance runbook and own private progress ledger; release assets stay outside Git source. No implementation expansion during rollout.
**Interfaces:** deployment consumes exact reviewed SHA and generated migration names. Auth/CORS/site/backend/provider inputs remain existing designated development settings; no production target inferred.

- [ ] Verify main/PR/latest CI and approved exact SHA; compare hosted migration versions/names. Dry run must contain only this feature's two migrations. Check pg_cron availability/ownership and declared daily TTL job; if unsupported, stop at that capability blocker instead of deploying an incomplete retention contract.
- [ ] Apply the reviewed development migrations and deploy only report/clear/read/checkpoint functions and any changed shared dependency bundle proven necessary. Preserve verify_jwt/custom signed verification, CORS, existing native callback, anonymous child Auth and old child/browser/parent routes. Inspect private grants, parent membership denial, TTL job uniqueness and post-DDL advisors; preserve intentional default-deny/needed indexes.
- [ ] Real controlled child-phone usage -> server-confirmed parent native/web reads. Verify permission denied/lost, partial start, background delay, offline/reconnect, two apps and lock intervals, inventory capture/truncation, dark/large-font/TalkBack, opt-out and immediate revoked-child denial. Do not collect an unrelated personal installation or publish package inventory. No phone availability/consent/secure-fixture input means stop at that exact blocker, with no invented evidence.
- [ ] Independently remove only the exact disposable operational/Auth fixtures and telemetry, verify zero owned rows while preserving audits. Confirm job/alias/cache cleanup. Disable collection on acceptance phone and record real cleanup outcomes; no active fixture deletion to simulate completion.
- [ ] Build live non-fixture native APK with matching public development Firebase config; verify package/version/signing continuity/backup exclusions/approved permission list. Increment versionCode above5 and versionName to0.6-development without repackaging the WebView app. Publish authorized development prerelease APK/ZIP+SHA256; ZIP contains no configuration, credentials, telemetry or fixtures. Record sanitized revision/CI/stage outcomes; no merge/production claim.

## Required Checks and Rollback

JVM command shorthand above means `./gradlew -PparentCiFixture=true --dependency-verification=strict testDebugUnitTest` plus the stated --tests filters from apps/parent-android. Final native check includes `lintDebug assembleDebug assembleDebugAndroidTest`; all existing six required CI jobs plus added web tests/build remain mandatory. Test-only CI fixtures remain isolated from real networking. Supabase migrations are generated with the repository CLI and tested locally before hosted use.

Rollback order: stop collectors/report UI, cancel usage jobs, preserve old native Auth/profile/notification behavior, disable new ingestion/read routes, unschedule only `harbor-usage-retention`, then remove additive usage tables/trigger/functions using reviewed rollback SQL after explicit authorization if data deletion would be destructive. Never restore an old revoke function body that omits newer authorization; the telemetry trigger is additive. Record purge/rollback implications. Old APKs ignore new endpoints; new clients show unavailable/last-confirmed states if endpoints are absent. No schema weakening, disabling child Auth or deleting unrelated records.

## Plan Self-Review and Approval

Coverage: Tasks1–2 contract/measurement;3 permissions/inventory;4 privacy/persistence;5 backend/security/TTL;6 lifecycle/queue;7 native+web UX;8 integration/review;9 real acceptance/rollout/release. Five Review Focus cases map to explicit task tests. Exact checkpoint recovery endpoint is intentionally additive and sequence-only, preserving the approved specification's blocked-until-recovered behavior; no existing child wire format changes. Every new interface is owned by a task. No product code/dependency/migration/deployment has been created for this plan yet.

Written specification approved. This written implementation plan awaits approval. Execute inline only after that approval; do not repeat the execution-method selection. Hourly automation remains paused.
