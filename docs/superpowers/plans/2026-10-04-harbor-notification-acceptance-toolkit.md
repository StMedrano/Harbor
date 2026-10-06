# Harbor Notification Acceptance Toolkit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove real browser Web Push and Android FCM receipt through Harbor's existing registration and durable-outbox interfaces.

**Architecture:** Build development-only recipient clients and an opt-in operator runner. Supabase continues to own Auth, device proof, authorization, transactional intent and dispatch. Use localhost for the browser and a debug Android APK; neither is a product-client foundation.

**Tech Stack:** Existing Deno 2.9.7, TypeScript, Supabase JS 2.105.0, browser service worker/IndexedDB, Kotlin and Android Keystore, Firebase Messaging, native Android widgets. Pin Android Gradle plugin 9.0.1 (built-in Kotlin), Gradle 9.1.0, JDK 17, compile/target SDK 36, Google Services plugin 4.5.0, Firebase BoM 34.19.0 and JUnit 4.13.2; use Messaging only, without Analytics.

**Spec:** `docs/superpowers/specs/2026-10-04-harbor-notification-acceptance-toolkit-design.md` — explicitly approved by the user on 2026-10-04.

**Status:** Written plan explicitly approved by the user on 2026-10-04. Preserve the roadmap's selected **Native** execution method (`superpowers:executing-plans`). Execution started in draft PR #15; live acceptance remains pending.

## Global Constraints

- Guard all live operations to project `bfvybxkjxilntjgndsrm` and its exact HTTPS URL; production remains unassigned.
- Android minimum API 29; debug application ID `dev.stmedrano.harbor.acceptance`.
- Use `tools/notification-acceptance/web` and `tools/notification-acceptance/android`; do not alter Kombai or Vercel product deployments.
- Supabase server credentials, pairing pepper, worker key and VAPID private key never enter either client.
- Browser Auth session stays in memory; only installation UUID and minimal receipts persist.
- Device identity is anonymous Auth plus non-exportable Keystore P-256; signatures use 64-byte P1363 and fresh nonces.
- Browser/Android permission prompts require the user's explicit interaction.
- Provider acceptance and observed receipt are distinct; observe each send for up to two minutes and report missing receipt as unverified.
- Preserve at-least-once transport semantics and existing idempotent domain/outbox behavior.
- Fixture credentials stay in memory or a restricted ignored handoff file, never captured output; remove fixtures on failure too.
- No production schema, new privileged endpoints, full clients, policy enforcement or automatic production scheduling.
- Tests first; reproduce meaningful RED before changing production/client behavior. Commit focused slices and run focused checks plus required CI.

## Review Focus

- A reload after permission/subscription creation must preserve installation identity and require sign-in again: Task 1 retry/reload tests.
- A malicious push body must not persist credentials/content or navigate to an arbitrary URL: Task 2 route and click tests.
- A rotated FCM token arriving before pairing must wait for binding; failed registration must not report success: Task 4 state tests.
- A partial fixture/setup/dispatch failure must clean only this run's records and retain evidence of failed cleanup: Task 5 failure tests.
- A provider success without receipt, or duplicate receipt, must not pass delivery or imply another domain mutation: Task 6 evidence tests.

## File map and prerequisites

- Browser: `web/config.ts`, `web/lifecycle.ts`, `web/app.ts`, `web/index.html`, `web/receipts.ts`, `web/service-worker.ts`, `web/build.ts`, `web/serve.ts` under `tools/notification-acceptance`.
- Browser tests: `tests/notification-acceptance/web-{lifecycle,receipts}.test.ts`.
- Android project: Gradle wrapper/build/settings and `app/src/main` under `tools/notification-acceptance/android`; Kotlin package path `dev/stmedrano/harbor/acceptance`.
- Android units: `DeviceProof.kt`, `DeviceIdentity.kt`, `HarborApi.kt`, `Registration.kt`, `ReceiptStore.kt`, `AcceptanceMessagingService.kt`, `MainActivity.kt`; matching unit tests under `app/src/test/java/dev/stmedrano/harbor/acceptance`.
- Operator: `tests/hosted/notification-acceptance.ts`, `tests/notification-acceptance/operator.test.ts`, `docs/runbooks/notification-acceptance.md`.
- CI: add `notification-web` and `notification-android` jobs in `.github/workflows/harbor-foundation-ci.yml`; preserve the existing foundation job.
- Ignore generated dist/build, Android local properties/real Firebase config, local handoff/evidence inputs; retain a non-secret example and fixture config.

Current command-path discovery found no Java, adb or Gradle. Use GitHub CI's JDK/Android SDK for tests/APK builds; missing local tools do not block the web slice. Real Android delivery requires operator device/emulator access. Obtain official Gradle wrapper files with distribution SHA-256 verification, not handwritten wrapper binaries; verify official dependency compatibility before setup.

Reference commands below assume repository root, except Android commands which run in its project directory. Use the already installed portable Deno/Supabase executables on this Windows host.

## Task 1: Browser registration lifecycle

**Files:** Create `web/config.ts`, `web/lifecycle.ts`, `web/app.ts`, `web/index.html`, `web/build.ts`, `web/serve.ts`; test `tests/notification-acceptance/web-lifecycle.test.ts`; modify `.gitignore` and CI web job.

**Interfaces:**
- `parsePublicConfig(value: unknown): { supabaseUrl: string; publishableKey: string; vapidPublicKey: string }` rejects wrong URL/ref and server-key prefixes.
- `enablePush(deps: PushLifecycleDependencies): Promise<{ installationId: string; endpoint: string }>` obtains/reuses subscription and calls existing `register-web-push`.
- `removePush(deps: PushLifecycleDependencies): Promise<void>` completes existing `remove-web-push` before local unsubscribe.
- `PushLifecycleDependencies` supplies session check, installation-ID store, permission request, subscription lookup/creation, register/remove callbacks and unsubscribe. The browser adapter uses existing Supabase SDK; tests inject those interfaces.
- Serve built assets only at `http://localhost:3000`; refuse other binds. Runtime public config is entered by the operator and held in memory, not compiled from secret env files.

- [ ] **Step 1:** Write failing tests with these exact assertions: wrong project/secret key throws; permission denial invokes no registration; existing subscription and installation UUID reused on retry; backend failure rejects and leaves UI unregistered; failed removal never unsubscribes; reload retains only installation UUID and no Auth session.
  Example: `await assertRejects(() => removePush(failingRemoval)); assertEquals(unsubscribeCalls, 0);` and `assertEquals(secondEnable.installationId, firstEnable.installationId);`.
- [ ] **Step 2:** Run `deno test --frozen tests/notification-acceptance/web-lifecycle.test.ts`; expect missing implementation/export RED, with each assertion reviewed against the spec.
- [ ] **Step 3:** Implement guarded config and lifecycle, memory-only SDK Auth, explicit permission button, success/error status, removal retry and fixed-origin static server. Restrict served paths to built assets; never serve repository/handoff files. Bundle existing SDK using `deno bundle --platform=browser` via `web/build.ts`; add no framework/bundler dependency. Keep generated assets ignored.
- [ ] **Step 4:** Run the focused tests, `deno check --frozen tools/notification-acceptance/web/app.ts`, and `deno run --frozen --allow-read --allow-write --allow-run=deno tools/notification-acceptance/web/build.ts`. Inspect browser output for absence of secret/session values. CI web job uses Deno 2.9.7 and runs these checks without hosted keys. Live prerequisite: preserve existing allowed origins and add exact localhost origin if absent; do not overwrite the existing Vercel origin.
- [ ] **Step 5:** Commit `feat: add development Web Push registration recipient` after focused PASS and CI, push to a focused toolkit branch/PR based on the reviewed foundation head.

## Task 2: Browser receipt service worker

**Files:** Create `web/receipts.ts`, `web/service-worker.ts`; modify `web/app.ts`, `web/build.ts`; test `tests/notification-acceptance/web-receipts.test.ts`.

**Interfaces:**
- `parseReceipt(value: unknown, receivedAt: string): { route: NotificationRouteRefV1; receivedAt: string } | null` reuses `notificationRoute` from `supabase/functions/_shared/notification.ts`, which has no privileged dependencies.
- `saveReceipt(receipt: Receipt): Promise<void>` and `listReceipts(): Promise<Receipt[]>` use a versioned browser-local IndexedDB store; `Receipt` is the result of `parseReceipt`.
- Service worker shows generic "Harbor test notification received" and opens only its own `/` page on click; message-to-page updates contain no Auth tokens.

- [ ] **Step 1:** Write failing tests: `assertEquals(parseReceipt({version:1,kind:"changed",accessToken:"secret"}, now), null);` and reject raw location/message content, invalid version/UUID, malformed JSON; accept minimal route; duplicate hints do not trigger mutation; clicks choose own page regardless of untrusted URL input.
- [ ] **Step 2:** Run `deno test --frozen tests/notification-acceptance/web-receipts.test.ts`; expect missing receipt implementation RED.
- [ ] **Step 3:** Implement route validation, receipt persistence/readout and worker `push`/`notificationclick` handlers with `event.waitUntil`. Bundle worker separately, register it from own origin, and display validated receipts after reload. Tests use storage/notification/navigation seams; do not add a backend receipt endpoint.
- [ ] **Step 4:** Run all web tests and rebuild; browser-check registration, denied-permission recovery and worker lifecycle. Actual provider receipt stays pending until Task 6; do not call a synthetic event live delivery.
- [ ] **Step 5:** Commit `feat: record browser notification acceptance receipts` after focused PASS and required CI.

## Task 3: Android identity and proof registration client

**Files:** Create Android build/settings/wrapper/manifest/config example, `DeviceProof.kt`, `DeviceIdentity.kt`, `HarborApi.kt`; tests `DeviceProofTest.kt`, `DeviceIdentityTest.kt`; add CI Android job.

**Interfaces:**
- `canonicalProof(method: String, operation: String, deviceId: String, body: ByteArray, timestamp: Long, nonce: String): ByteArray` uses six newline-separated fields and SHA-256 lowercase body hash.
- `derToP1363(signature: ByteArray): ByteArray` validates ASN.1 sequence and two positive P-256 integers, strips DER sign padding and outputs exactly 64 bytes; reject malformed/trailing/oversized values.
- `DeviceIdentity` owns anonymous session/refresh and claimed binding. `HarborApi.claim(code: String, spkiBase64: String): DeviceBinding` and `HarborApi.registerFcm(binding: DeviceBinding, token: String): Unit` use existing JSON/HTTP interfaces and proof headers.
- `DeviceBinding(deviceId, familyId, childId)` contains IDs only. Inject clock/nonce generator/HTTP transport into proof/API tests; real signing uses non-exportable Android Keystore alias local to this app.

- [ ] **Step 1:** Add build configuration and test sources needed to run a RED JVM test, without implementing the client. Pin the versions above; SDK public env file/Firebase JSON must match expected development Firebase project ID and package ID. Add non-secret CI fixtures, distribution checksum, dependency locking/verification, debug-only variant, INTERNET permission and `allowBackup=false`.
- [ ] **Step 2:** Write tests for canonical bytes, empty/object body hashes, fresh nonce, valid DER padding conversion and invalid encoding. Use a JCA-generated P-256 signature to prove round trip to a verifying public key; pin 64-byte length and expected canonical text. Identity tests assert refresh precedes expired-session request and invalid claim leaves binding empty. CI `testDebugUnitTest` must show expected missing-client RED before implementation.
- [ ] **Step 3:** Implement the exact proof/identity/API interfaces with platform crypto and HTTP; no Supabase Kotlin SDK or custom server auth. Include anonymous signup, child-only session persistence in app-private storage, refresh, pairing claim and canonical signed register/sync requests. Reject wrong backend ref, missing public config, wrong Firebase/package identity and parent/server credentials. Refresh failure leaves the app unregistered and asks for retry/re-enrollment.
- [ ] **Step 4:** CI runs `./gradlew testDebugUnitTest lintDebug assembleDebug` with JDK 17 and SDK 36, non-secret fixture config; expect PASS and downloadable debug APK. Locally, run wrapper only if prerequisites exist. Real Keystore signing is verified on the operator device against hosted `device-sync` in Task 6.
- [ ] **Step 5:** Commit `feat: add debug Android Harbor device proof client` after focused PASS and foundation/Android CI.

## Task 4: Android FCM registration and receipts

**Files:** Create `Registration.kt`, `ReceiptStore.kt`, `AcceptanceMessagingService.kt`, `MainActivity.kt`; update manifest/build config; tests `RegistrationTest.kt`, `ReceiptStoreTest.kt`.

**Interfaces:**
- `Registration` tracks pending current token, binding and backend-confirmed token; `onToken(token: String)` never registers without binding; `registerCurrentToken(): RegistrationResult` refreshes child Auth and submits proof.
- `RegistrationResult` is `Registered | NeedsBinding | RetryableFailure`; failed request never becomes `Registered`.
- `ReceiptStore.record(routeJson: String, receivedAt: Long): Boolean` accepts only the V1 minimal route shape and keeps local receipt evidence. `AcceptanceMessagingService` delegates Firebase `onNewToken` and `onMessageReceived`; foreground UI reads state and permission status.

- [ ] **Step 1:** Write RED tests: token before binding stays pending with zero HTTP calls; latest rotated token supersedes old token; backend rejection retains retry state; valid route persists, malformed/extra-sensitive fields do not; receipt is read-only and never invokes a domain update. Permission refusal remains a visible setup state.
- [ ] **Step 2:** Run CI `testDebugUnitTest`; verify expected missing lifecycle/receipt implementation failures.
- [ ] **Step 3:** Implement Messaging-only Firebase initialization, token callbacks, signed foreground registration, app-private route receipts, generic status UI and explicit API-33+ notification permission. Do not log tokens/proof/session. Update foreground status after background callback; do not add policy enforcement/WorkManager.
- [ ] **Step 4:** Run Android unit tests/lint/APK build and existing gates. Inspect APK manifest for no unexpected exported components, backup or production configuration. Device installation/token/foreground-background delivery remain live checks in Task 6.
- [ ] **Step 5:** Commit `feat: add Android FCM acceptance recipient` after focused PASS and required CI.

## Task 5: Secure operator fixture and dispatch runner

**Files:** Create `tests/hosted/notification-acceptance.ts`, `tests/notification-acceptance/operator.test.ts`, `docs/runbooks/notification-acceptance.md`; modify `.gitignore` and CI web/test job. Reuse hosted fixture conventions without unrelated refactoring of the existing harness.

**Interfaces:**
- Runner commands are `prepare`, `change-state`, `dispatch`, `cleanup`; every command reads validated fixture manifest and guarded development config.
- `FixtureManifest` contains runId, projectRef, parentUserId, familyId, childId, deviceId when claimed, and expected desired-state version; no service/worker credentials.
- `OutboxRow` contains `id: string`, `eventKey: string`, `transport: "fcm" | "web_push"`, `targetRef: Record<string, unknown>` and `route: NotificationRouteRefV1`; parse the trusted SQL row names at the input boundary.
- `validateDispatchRows(manifest: FixtureManifest, rows: unknown): OutboxRow[]` admits only exact fixture route/target IDs, expected event and `fcm|web_push`; fails closed on missing/foreign rows.
- Private operator inputs arrive via stdin/ignored local file; readiness reports input names only. Backend/worker keys are never included in client handoff. Temporary parent credentials/pairing code use a restricted local handoff, deleted on cleanup.
- Trusted private-row reads/deletion use the connected Supabase database tools, explicitly filtered by manifest IDs; runner is not a new private Data API endpoint.

- [ ] **Step 1:** Write RED tests proving wrong project refused before any call, unrelated fixture IDs rejected, foreign outbox targets rejected, missing worker key does not call dispatcher, captured output excludes credentials, and a partial failure attempts all cleanup stages rather than aborting at the first error. Assert failed cleanup reports remaining fixture IDs and does not claim success.
- [ ] **Step 2:** Run `deno test --frozen tests/notification-acceptance/operator.test.ts`; expect missing runner helpers RED.
- [ ] **Step 3:** Implement preparation through real hosted APIs with admin-only fixture child/account setup; create family/pairing through parent endpoints. Write restricted handoff outside served roots; fail if permissions cannot be established. `change-state` uses harmless `{ acceptanceRun: runId }` state and exact expected version. `dispatch` invokes existing worker only for validated supplied outbox rows, reports sent/retry/dead-letter/no-op separately. Cleanup removes browser registration, unsubscribes browser locally, revokes via real fresh MFA, then deletes only this run's registration/outbox/domain/Auth fixtures through trusted operator access. Reuse the existing hosted harness's real TOTP enrollment/challenge approach for this temporary parent; keep factor secrets on the operator side and never forge JWT claims. Retain minimal audit records.
- [ ] **Step 4:** Run operator tests and static Deno checks with no live credentials, add to CI without hosted execution. Document exact commands, secure credential handoff, worker-key input, public VAPID and matching Firebase configuration, origin setup, APK install, permission prompts and cleanup recovery. Do not request secret values in chat.
- [ ] **Step 5:** Commit `test: add secure notification acceptance operator workflow` after focused PASS and required CI.

## Task 6: Combined live delivery acceptance and evidence

**Files:** Create `tests/notification-acceptance/evidence.test.ts`; extend runner evidence helper; update notification acceptance/development runbooks and Issue #1/PR.

**Interfaces:**
- `ProviderOutcome` is the existing `DeliveryOutcome` imported from `supabase/functions/_shared/outbox-dispatch.ts`. `Receipt` for this evidence helper is `{ route: NotificationRouteRefV1; receivedAt: string }`, matching the browser receipt and normalized Android evidence.
- `classifyDelivery(provider: ProviderOutcome, matchingReceipts: Receipt[], elapsedSeconds: number): "received" | "unverified" | "provider_failure"` never treats `sent` alone as `received`.
- Receipt correlation uses fixture device ID/route kind and trusted outbox event identity; each send is serialized so earlier receipts cannot satisfy a later version. Record observation start/time; clear or baseline client receipt history before each send.

- [ ] **Step 1:** Write RED evidence tests: sent with no receipt is unverified; foreign/old receipt does not pass; matching receipt passes; duplicate receipts record multiple hints but one authoritative event; 120-second timeout remains unverified, not permanent provider failure.
- [ ] **Step 2:** Run focused evidence tests; observe expected missing classifier RED, implement minimal classification and run PASS. Run all toolkit tests/builds and required foundation CI on exact source head.
- [ ] **Step 3:** Check live prerequisites by name: matching Firebase Android config, existing public VAPID key, worker-key input, allowed localhost origin, installed debug APK on Google Play-capable device/emulator, browser/device notification permissions. If unavailable, stop at the exact prerequisite and preserve built artifacts; do not invent credentials or claim delivery verified.
- [ ] **Step 4:** Prepare fixtures, sign browser in and register PushSubscription, pair Android with Keystore identity, register FCM using proof and verify signed sync. Run one harmless state change, inspect exact two transport rows, dispatch each, and observe matched browser/Android receipts within two minutes. Record foreground and deliberate Android background send separately with new versions and receipt baselines.
- [ ] **Step 5:** Re-dispatch sent IDs and confirm no-op; remove browser registration, make a new version change and prove only FCM intent/receipt continues. Verify fresh-MFA revocation and subsequent signed sync/FCM registration denial. Check private audit events and final fixture/registration/outbox cleanup by exact IDs. If a provider or receipt fails, preserve sanitized evidence and investigate root cause without declaring the gate complete.
- [ ] **Step 6:** Record source revision, CI run, device/browser versions, provider outcome and actual receipt evidence separately in runbook/PR/Issue #1. Commit `docs: record Harbor live notification acceptance`. Do not mark backend foundation done while email callbacks, stale-MFA timing or relevant advisor assessment remain open.

## Plan review and execution handoff

Self-review covers every approved spec section, component interfaces, all five Review Focus cases, failure cleanup and real-device prerequisites. No dependencies/projects/product code are installed or scaffolded during planning.

After user approves this written plan, use preserved Native execution, a focused branch/worktree as appropriate, a progress ledger and small test-first commits. Apply the executing-plans whole-branch fresh-review gate once implementation is ready; no repeated review loops or unrelated cleanup.

Reference sources for pinned build choices: [AGP 9.0.1 and its Gradle/JDK requirements](https://developer.android.com/build/releases/agp-9-0-0-release-notes), [Firebase Android configuration and BoM](https://firebase.google.com/docs/android/setup), [Deno browser bundling](https://docs.deno.com/runtime/reference/cli/bundle/). Recheck version compatibility if setup evidence contradicts these pins; report the exact change before substituting a materially different toolchain.
