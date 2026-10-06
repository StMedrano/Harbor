# Harbor Parent Android Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Subproject 2A's real parent Auth → family/child setup → pairing → authorized state/notification → recent-MFA revocation lifecycle.

**Architecture:** A native Compose parent app consumes the verified Supabase backend and shared contracts. Add only the missing parent create-child and private parent FCM interfaces; preserve child identity/proof and transport behavior. Room is a scoped stale read cache, and server Auth/authorization remains authoritative.

**Tech Stack:** Kotlin/Compose, Room/KSP, Supabase Kotlin, Ktor OkHttp, Firebase Messaging; existing TypeScript/Deno, PostgreSQL/pgTAP and GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-06-harbor-parent-android-foundation-design.md` — approved by the user on 2026-10-06.

**Status:** Awaiting this written plan's approval. No implementation is authorized yet.

**Execution method:** Preserve the user's selected Native (`superpowers:executing-plans`) method: inline TDD, one fresh whole-branch review and one test-first fix pass at the end. Do not redispatch completed foundation reviews.

## Global Constraints

- App path `apps/parent-android`; package `dev.stmedrano.harbor.parent`; minimum API 29. Compile/target API 36 initially, JDK 17.
- Visual source is Harbor's existing Vercel frontend; use the approved spec's exact light/dark tokens, 8/14/22 corners, system sans, 48dp targets and native Family/Security/Settings navigation. Kombai is not the source; preserve its files.
- Supabase is authoritative; Vercel is frontend-only. Same parent account/family model as the later PWA. No production/staging backend provisioning or Vercel changes.
- Public-only APK config; separate parent/child identities. No client private-table grants or server credentials. Disable backup/cleartext traffic.
- Parent credentials and PKCE verifier use Keystore-protected encrypted storage, not Room, default SDK settings or logs. One pending PKCE transaction at a time; callback exactly `harbor-parent://auth/callback`.
- MFA window remains exactly 900 seconds, enforced server-side. Destructive actions require a deliberate retry after challenge, never an automatic retry.
- Room cache keyed by current subject/family; show fetched time/staleness and clear access-removed/account-switched views. No offline privileged mutation queue.
- Parent FCM is data-only with exactly two string fields: `route` (JSON `NotificationRouteRefV1`) and `parentRegistrationId` (UUID). Child wire format is unchanged.
- Parent registrations require current verified parent Auth/session, current membership at dispatch, immutable owner/registration ID, and generation-safe invalid-token cleanup.
- Migration filenames come from `supabase migration new`; RED before implementation; focused GREEN then required CI before hosted DDL/functions.
- Never print credentials, email links, provider tokens or active fixture identifiers. Exact fixture journals stay protected/ignored; preserve audit records.

## Dependency decisions and first compile gate

Reuse the acceptance project's Gradle 9.1.0 wrapper and its recorded SHA-256, AGP 9.0.1 and Google Services 4.5.0 without changing that project. New parent pins:

| Dependency | Version / decision |
| --- | --- |
| Supabase BOM; auth/postgrest/realtime/functions modules | 3.8.0 |
| Kotlin, Compose compiler and serialization plugin | 2.4.0 |
| Ktor OkHttp/MockEngine | 3.5.1 |
| Coroutines / serialization JSON | 1.11.0 / 1.11.0 |
| Compose BOM | 2026.09.00 |
| Activity Compose | 1.13.0 |
| Room runtime/ktx/compiler | 2.8.5 |
| KSP | 2.3.12 |
| Firebase BOM | 34.19.0, matching the verified acceptance build |
| JUnit / Android test runner / Android test JUnit | 4.13.2 / 1.7.0 / 1.3.0 |

Versions were read from [SDK tagged dependency metadata](https://github.com/supabase-community/supabase-kt/blob/3.8.0/gradle/libs.versions.toml), Google Maven release metadata and [KSP releases](https://github.com/google/ksp/releases/tag/2.3.12). Use AGP built-in Kotlin with the documented higher-KGP buildscript dependency; do not also apply `org.jetbrains.kotlin.android`. See [AGP guidance](https://developer.android.com/build/releases/agp-9-0-0-release-notes).

Task 1 must prove this exact dependency graph/minCompileSdk/plugin combination before further implementation. Metadata verification is not compilation evidence. If pins need a newer compile SDK or incompatible plugins, stop and record the precise incompatibility; revise the plan before changing the supported build stack. Never suppress metadata checks or upgrade the separate acceptance app to hide it. Generate lockfiles/checksums after approved resolution and then use strict verification.

## Review Focus

1. Process death or a second email operation must not overwrite an active recovery verifier or let an ordinary session unlock recovery (Task 5).
2. Membership removal racing child creation must serialize with the creation transaction; known IDs never bypass membership (Task 2).
3. Logout with an otherwise-valid JWT must prevent new parent registration/queued sends; offline local logout must not falsely claim remote invalidation (Tasks 3, 7, 9).
4. Token rotation/account switch during an in-flight invalid-token response must not disable the new token or display an old-account message (Tasks 4, 7).
5. Duplicate taps, timeout-after-commit and denied/stale cached views must not create duplicate children or authorize actions offline (Tasks 2, 6, 8).

## Workspace and file map

After plan approval, inspect attached worktrees and reuse a suitable parent checkout. Otherwise create an isolated checkout/branch `feat/parent-android-foundation` from the latest verified current foundation/spec/plan branch, preserving `work/Harbor-notifications`. Base the focused draft parent PR on `feat/notification-acceptance-toolkit` while the foundation stack remains unmerged. Do not merge/close/reset existing PRs or main. Attach the created PR to this task.

Backend additions live alongside existing handlers/tests; do not refactor unrelated shared files. The parent app is one app module with focused `auth`, `family`, `notifications`, `security`, `data` and `ui` packages. No DI framework or new generic repository/event-bus layer.

Commands below use the available pinned Deno/Supabase executables. Android commands run in `apps/parent-android`; use `./gradlew` in CI/Linux and `./gradlew.bat` on Windows. When local Docker is unavailable, publish the isolated tests to CI to establish actual RED before implementing SQL; do not substitute inspection for a failed test.

---

### Task 1: Test-host build, environment boundary and native shell

**Files:** Create `apps/parent-android/{settings.gradle.kts,build.gradle.kts,gradle.properties,gradlew,gradlew.bat}`, wrapper files, `gradle/libs.versions.toml`, verification metadata; `app/build.gradle.kts`, manifest, `config/ci-fixture.properties`, `config/ci-fixture-google-services.json`, public `parent.properties.example`; parent `.gitignore`; `ui/HarborTheme.kt`, `ui/ParentApp.kt`, `MainActivity.kt`, `EnvironmentConfig.kt`; `app/src/test/.../EnvironmentConfigTest.kt`.

**Interfaces:** `EnvironmentConfig.read(values: Map<String,String>, ciFixture: Boolean): EnvironmentConfig`; `HarborTheme(dark: Boolean, content: @Composable () -> Unit)`; `ParentApp(content: @Composable () -> Unit)`. Later tasks replace the initial signed-out state, not the theme/build boundary.

- [ ] Build only the minimum test-host Gradle/manifest/wrapper and write `EnvironmentConfigTest`: reject secret/service-role keys, foreign backend, wrong parent package/Firebase client, absent live config and unassigned production; explicit CI fixture never enables live networking. Use the existing validation pattern, not its child package.
- [ ] Run `./gradlew.bat -PparentCiFixture=true testDebugUnitTest`; expected RED for missing `EnvironmentConfig`, or a precise dependency incompatibility that must be resolved through the compile gate above.
- [ ] Implement the guard and minimal signed-out Compose shell. Explicit fixture flag is required; live debug needs ignored matching parent config. Disable release/live production builds until separately assigned. Apply spec tokens/system light-dark, scroll/insets/back behavior and 48dp targets.
- [ ] Run unit tests, `lintDebug assembleDebug` with strict verification; run `help` without live config and expect a bounded missing-config rejection. Inspect merged manifest/public config; no Firebase service-account file may be accepted.
- [ ] Add `parent-android` CI build/unit/lint job using the explicit fixture. Keep existing jobs untouched. Commit `feat: add guarded Parent Android shell`; require GREEN before Task 5 uses the SDK.

### Task 2: Atomic parent-authorized child creation

**Files:** Modify `packages/contracts/src/v1/{family,errors}.ts`, contract tests, `_shared/errors.ts`; create `supabase/functions/create-child/index.ts`, `_shared/child-creation.ts`, `tests/functions/create-child.test.ts`, `supabase/tests/child_creation.test.sql`; CLI-generated `parent_child_creation` migration.

**Interfaces:** `CreateChildRequestV1 {familyId, displayName, idempotencyKey}` → existing `ChildV1`. `createCreateChildHandler(deps)(request): Promise<Response>`. `createChildAtomic({userId,familyId,displayName,idempotencyKey,payloadHash}): Promise<ChildV1>`. SQL `private.harbor_create_child(actor uuid,family uuid,name text,key text,fingerprint text)` returns that canonical child's fields. Add `IDEMPOTENCY_CONFLICT` / 409.

- [ ] Write tests asserting one child/one creation audit across duplicate/concurrent requests, retry after response loss returns same child, changed normalized payload returns conflict, 1–100 Unicode-codepoint name limit and no control characters, anonymous/cross-family/removed-role denial, no client writes.
- [ ] Add a two-session DB race test: hold membership removal uncommitted, start creation, commit removal; creation must wait and deny, leaving no child/request/audit. Run focused Deno/DB tests; expected RED on missing handler/helper/table.
- [ ] Create the migration with CLI. Add private child-creation request identity unique on actor/family/key, normalized fingerprint, child reference and FK lookup indexes. Check active owner/parent membership with `FOR SHARE` in the creation transaction; create child/request/audit atomically. Grants remain server-only. Canonicalize names/fingerprint in the handler and reuse Auth/CORS/error boundaries.
- [ ] Run `supabase db reset`, focused pgTAP and `deno test --frozen tests/functions/create-child.test.ts`, then contracts/full required CI. Verify runtime conflict mapping and audit metadata excludes names.
- [ ] Commit `feat: add idempotent parent child creation`. Do not deploy it yet; rollout belongs to Task 9.

### Task 3: Current-session private parent FCM registry

**Files:** Modify `_shared/auth.ts` to preserve an optional `sessionId` from already-verified claims; create `_shared/parent-session.ts`, `_shared/parent-fcm.ts`, `register-parent-fcm/index.ts`, `remove-parent-fcm/index.ts`; create `packages/contracts/src/v1/parent-notifications.ts` and export it; tests `parent-session.test.ts`, `parent-fcm.test.ts`, `supabase/tests/parent_fcm.test.sql`; CLI-generated `parent_fcm_registry` migration.

**Interfaces:** `requireActiveParentSession(req, deps?: ParentSessionDependencies): Promise<ParentContext & {sessionId:string}>`; test dependency `ParentSessionDependencies` supplies verified Auth and `isActive(userId,sessionId): Promise<boolean>`; `registerParentFcmAtomic({userId,sessionId,clientInstallationId,token}): Promise<{registrationId:string,active:true}>`; `removeParentFcmAtomic({userId,clientInstallationId}): Promise<void>`; register HTTP 200/remove 204. Request bodies cannot supply owner/session IDs.

- [ ] Write RED tests for missing/invalid verified session ID, anonymous identity, session owned by another subject, remotely logged-out/expired session, idempotent registration, two installations, token rotation, repeated removal, wrong-owner removal, absent client grants and no token values in results/audits.
- [ ] Run focused Deno/DB tests; expected missing helpers/registry failures. Existing Auth tests still establish no changes to old parent/device behavior.
- [ ] Add registry: immutable ID/owner, session FK, installation, token/hash, active/lifecycle fields; unique user/installation and one active owner per token hash. Lock token-ownership changes atomically; deactivate an old binding on account switch. Session FK/index and current-session checks follow actual Auth schema. Keep optional context field backward-compatible; reject missing current session only for the new endpoints. Never decode unverified JWT claims as a fallback.
- [ ] Validate current Auth-session semantics against pinned/local Auth logout behavior; session/user/time-box checks and live logout proof must agree. If the hosted lifecycle cannot prove session invalidation, stop rather than claim it. Reuse verified claims/Auth and private SQL; no Auth table grants to clients.
- [ ] Run focused/full backend/contracts CI; commit `feat: add session-bound parent FCM registry`. Leave hosted rollout pending.

### Task 4: Additive parent fanout and generation-safe delivery

**Files:** Modify `_shared/{outbox,outbox-dispatch,fcm,web-push}.ts`, `dispatch-outbox/index.ts`; add `_shared/parent-fcm-delivery.ts`; create `tests/functions/parent-fcm-delivery.test.ts`, `tests/integration/parent-fcm-persistence.test.ts`, `supabase/tests/parent_fcm_fanout.test.sql`; CLI-generated `parent_fcm_fanout` migration.

**Interfaces:** `readParentFcm(registrationId,familyId): Promise<{token,tokenHash,registrationId,userId,sessionId}|null>`; `disableParentFcm(registrationId,tokenHash,outboxId,attempt): Promise<void>`; `sendParentFcm(token,route,registrationId): Promise<PushDeliveryResult>`. Extend FCM target routing with exactly `{parentFcmRegistrationId,userId}`; reject mixed child/parent target keys. Preserve child `{deviceId}` and Web Push targets.

- [ ] Write RED persisted tests: same authoritative event creates independent child FCM, browser Web Push and each authorized parent FCM intent; duplicates preserve durable identities; membership/session removal prevents send; invalid parent token cannot block other transports; expired dispatch lease cannot disable or finish anything.
- [ ] Add rotation race assertions: resolve old token, rotate registration, return old `UNREGISTERED`; old-token cleanup must not disable the new generation. Old-account registration IDs stay inactive and never resolve to a new owner.
- [ ] Implement additive fanout in the existing domain-intent transaction. Resolve current owner/session/membership/token at dispatch, not enqueue time. Parent wire body is data-only with `route` JSON string and `parentRegistrationId`; legacy child body remains byte/field compatible. Reuse provider auth/request/error code paths.
- [ ] Add internal `invalid_token` delivery reason for an explicit FCM `UNREGISTERED` response; generic `INVALID_ARGUMENT`/sender/payload failures must not delete a token. Guard parent invalidation by captured token hash, claimed outbox ID/attempt and current lease. See [FCM error guidance](https://firebase.google.com/docs/cloud-messaging/error-codes).
- [ ] Run focused functions + real PostgreSQL integration, all existing outbox/contract tests and required CI. Commit `feat: deliver session-bound parent FCM hints`; no hosted mutation yet.

### Task 5: Encrypted parent Auth and one-time callback lifecycle

**Files:** Create `auth/{ParentIdentity,SecureAuthStore,KeystoreCipher,EncryptedSessionManager,EncryptedCodeVerifierCache,AuthGateway,SupabaseAuthGateway,AuthTransaction,AuthCallbackRouter,ParentAuthRepository}.kt`, `ui/AuthScreen.kt`; tests `auth/{ParentAuthRepositoryTest,AuthCallbackRouterTest,SupabaseAuthGatewayTest}.kt` and instrumented `auth/KeystorePersistenceTest.kt`.

**Interfaces:** `ParentIdentity(userId:String,sessionId:String)`; `AuthGateway` methods: `signIn(email,password): UserSession`, `signUp(email,password): String` (returned subject), `requestRecovery(email): Unit`, `exchangeCode(code): UserSession`, `fetchVerifiedIdentity(session,expectedEmail:String?,expectedUserId:String?): ParentIdentity`, `changePassword(password): Unit`, `restoreStoredSession(): UserSession?`, `refresh(): UserSession`, `signOutCurrent(): Unit`, all suspend. Restore imports the custom encrypted manager session into the SDK before refresh; default auto-load remains disabled. `ParentAuthRepository` exposes `identity: StateFlow<ParentIdentity?>`, `suspend fun <T> withAccessToken(block: suspend (String) -> T): T`, `restore()`, `beginSignup(email,password)`, `beginRecovery(email)`, `consumeCallback(uri: String)`, `changePassword(password)`, `clearLocal()`. Passwords are memory-only Strings. `AuthCallbackRouter.parse(uri: String,pending: AuthTransaction?): CallbackCode?` is pure/JVM-testable; `AuthTransaction` stores kind, normalized expected email, optional known subject, start time and accepted-recovery subject under encryption; `CallbackCode` contains ephemeral code and its stored transaction. Signed-out recovery binds the requested email/PKCE flow, checks server-returned email and non-anonymous confirmed identity, then pins the returned subject before password change; it does not require an exposed email-to-user lookup. The client session marker is cache context only, never an owner/session request-body authority.

- [ ] Write RED assertions: process recreation retains verifier/transaction; second pending flow is refused; exact callback/code only, duplicate/expired/wrong-flow/fragment credentials rejected and scrubbed; ordinary sessions cannot unlock password update; wrong/unconfirmed/anonymous identity rejected; invalid refresh signs out; simultaneous refresh does not race; key loss clears storage safely.
- [ ] Run parent unit tests; use Ktor MockEngine to assert SDK PKCE requests/code exchange and MFA/Auth APIs, not hand-written substitute Auth. Expected missing implementations or rejected lifecycle assertions.
- [ ] Implement encrypted SDK `SessionManager` (`saveSession/loadSession/deleteSession`) and `CodeVerifierCache` (`saveCodeVerifier/loadCodeVerifier/deleteCodeVerifier`) from the tagged 3.8.0 interfaces. Do not use plaintext default Settings persistence. Set PKCE, custom managers and explicit restore; disable SDK auto-refresh and serialize repository refresh/requests. Keep passwords/email links in memory; scrub Intent URI before accepting it.
- [ ] Persist only encrypted transaction kind/expected subject/verifier and validated session data. Record recovery state only after its supported request succeeds, exchange one-time code, verify identity server-side, consume the gate after password update. No URL `type` label or existing JWT unlocks recovery. Handle confirmed-signup resume through fresh sign-in without weakening recovery gates.
- [ ] Run focused unit and Keystore instrumented tests on API 29/36; verify cold start/key invalidation and no secret logging/backup. Add sign-up/confirmation/recovery/session UI with explicit outcomes. Commit `feat: add secure parent Auth lifecycle`.

### Task 6: Family creation, cached authorized reads and pairing UI

**Files:** Create `family/{ParentApi,FamilyRepository,FamilyViewModel,PendingChildCreation}.kt`, `data/{ParentDatabase,FamilyCacheDao,FamilySnapshot}.kt`, `ui/{FamilyScreen,ChildSetupSheet,PairingSheet,DeviceScreen}.kt`; tests `family/{FamilyRepositoryTest,ChildSetupTest,PairingTest}.kt`, instrumented `data/FamilyCacheTest.kt`.

**Interfaces:** `ParentApi.createFamily(name,key)`, `createChild(request): ChildV1`, `createPairing(childId): PairingCode`, `readFamily(familyId): FamilySnapshot`; `FamilyRepository.refresh(identity,familyId)`, `cached(identity,familyId)`, `clearFamily(identity,familyId)`, `clearAll()`; snapshot contains actual public fields and `fetchedAt`.

- [ ] Write RED tests for empty/loading/failure states, subject/family cache isolation, denied membership clearing, timeout-after-create retry with the same persisted key, restart while request is pending, code expiry/renewal and no fake pairing-success state.
- [ ] Run parent unit/Room instrumented tests; expected missing repository/UI or incorrect isolation/idempotency behavior.
- [ ] Implement current-subject RLS reads and existing create-family/new create-child calls through `withAccessToken`. Persist the child operation key/fingerprint until accepted/cancelled, scoped to subject/family. Room stores no credentials and no authoritative policy. Display real status/supervision/last seen, never fabricated desired-policy application.
- [ ] Build native child-switcher/cards/setup and pairing sheet from spec tokens. Mark cached/offline views with last successful fetch time; disable privileged mutations offline. Confirm enrollment through a fresh authorized device read, not issuance of a code.
- [ ] Run focused tests/Compose interactions, large-text/scroll/back inspection and full parent unit/lint build. Commit `feat: add authorized parent family and pairing flow`.

### Task 7: Private Realtime, parent FCM and current-device sign-out

**Files:** Create `notifications/{FamilyRealtime,ParentRegistrationStore,ParentNotifications,FirebaseTokenProvider,ParentMessagingService,ParentMessageParser}.kt`, `ParentRuntime.kt`, `ui/SettingsScreen.kt`; tests `notifications/{ParentNotificationsTest,ParentMessageParserTest,SignOutTest}.kt` and instrumented `notifications/ParentNotificationUiTest.kt`.

**Interfaces:** `FamilyRealtime.connect(identity,familyId)/disconnect()`; `ParentNotifications.enable()/remove()/onTokenChanged(token)/onMessage(data)`; `ParentMessageParser.parse(data): ParentHint?`; `ParentRuntime.signOutCurrent()` orchestrates notifications, Auth, channels and caches. Set a signing-out state and clear the current rendering/registration marker immediately, before bounded best-effort remote cleanup with captured in-memory credentials; invalidate old async generations. Do not persist credentials for later cleanup. All async completions carry the identity/registration generation they started with.

- [ ] Write RED tests: permission denial never registers; backend failure stays unconfirmed; rotation/retry reuses installation identity; old registration IDs/wrong-family/unknown fields/sensitive payloads rejected; account switch during a request cannot commit old state; offline sign-out clears local state without claiming backend/provider cleanup.
- [ ] Test exact two-string parent envelope, duplicate event hints and taps on inaccessible devices. Realtime disconnects on identity/family change; missed events recover on foreground/reconnect. Run unit tests to observe RED.
- [ ] Implement registration only for authenticated explicit opt-in, confirmed backend ID saved under current subject; bounded same-operation retries stop on logout. Receive data-only messages, check current registration/family, then render generic text and authorized refresh. No SDK system notification payload is sent for parents.
- [ ] Sign-out order: stop account work/channels; attempt registration removal and Firebase token deletion while authenticated; call Auth current-session sign-out; always erase local credentials/verifier/registration/views and cancel old retries. Failed offline cleanup remains unconfirmed and must not retain old credentials. New login gets a fresh token/binding. Preserve current-account callbacks against stale async completion.
- [ ] Run notification/instrumented tests and parent build. Commit `feat: add parent notification and sign-out lifecycle`.

### Task 8: TOTP and deliberate recent-AAL2 revocation

**Files:** Create `security/{MfaGateway,SecurityViewModel}.kt`, `ui/SecurityScreen.kt`, tests `security/{MfaRevocationTest,SecurityUiTest}.kt`; wire existing device view/revoke API.

**Interfaces:** `TotpEnrollment(factorId:String,secret:String,qrUri:String)` (memory-only); `MfaGateway.enrollTotp(): TotpEnrollment`, `challenge(factorId:String,code:String): Unit`, `listFactors(): List<String>`; `ParentApi.revokeDevice(familyId,deviceId): Unit`; security state separates confirmation, challenge, ready-to-retry, pending, accepted and denied. `SecurityViewModel.challenge(factorId:String,code:String): Unit` changes only assurance/retry readiness; `requestRevocation(familyId:String,deviceId:String)` opens confirmation, and `confirmRetry(): Unit` invokes the deliberate backend operation.

- [ ] Write RED tests: AAL1/stale-MFA denial shows step-up; successful challenge never automatically revokes; deliberate retry calls backend; wrong device/family and network failures never show completion; offline/cache roles cannot permit revocation; setup secret/password not in logs/screenshots.
- [ ] Run focused unit/Compose tests and observe RED.
- [ ] Implement SDK TOTP adapter and native setup/challenge/revoke screens. Protect sensitive Auth/MFA screens from capture/backup; retain no TOTP enrollment secret after setup. Reuse existing 900-second server enforcement and refresh metadata after backend acceptance.
- [ ] Add Android instrumentation CI on API 29 and API 36 (managed virtual devices with explicit system-image packages/KVM support); include cold-start/encrypted store, navigation, text-scale, notifications and revocation tests. Emulator evidence does not prove real FCM delivery.
- [ ] Run parent unit/instrumented/lint/assembly and all required backend CI after this integration. Commit `feat: add parent MFA-protected revocation`.

### Task 9: Whole-branch review, development rollout and real acceptance

**Files:** Create `tests/end-to-end/parent-android-foundation.test.ts`, `tests/hosted/parent-android-acceptance.ts`, `docs/runbooks/parent-android-acceptance.md`; modify foundation CI and environment/runbook entries only where required.

**Interfaces:** Shared backend test exercises the new parent lifecycle with durable PostgreSQL; hosted operator receives secrets in memory and protects exact fixture journal using existing operator patterns. Acceptance report records stage outcomes/revision/CI, not credentials or fixture identifiers.

- [ ] Write/run RED persisted acceptance for parent child creation, session-bound registrations, three transport recipients, cross-family denial, current-session logout, invalid-token lease/rotation behavior and old-registration message rejection. Implement only missing integration seams after the failure is understood; do not duplicate functional layers.
- [ ] Run full clean DB reset/tests/lint, function/contracts/release/persistence/end-to-end suites and parent/acceptance Android gates. Request one fresh whole-parent-branch review; give raw diff/spec/plan and proof. Do one test-first fix pass for material findings, then required GREEN proof; record minor deferrals without review loops.
- [ ] Only after GREEN/review, compare designated development migration history, exact dry run and apply these new migrations. Deploy only new/changed reviewed functions; retain old JWT/CORS/config behavior. Add exactly the reviewed native callback while preserving existing redirects/Auth settings. Check schema/grants/indexes/advisors and parent session/logout behavior; production untouched.
- [ ] Obtain matching public Firebase parent client config for the new package through authorized development setup. Do not repurpose the child config, transfer private keys, or release a CI fixture as a live APK. Build live development APK; use existing secure remote download workflow only if authorized.
- [ ] On Pixel 9a, prove fresh parent login/signup confirmation/recovery, same-user family/child creation/pairing to a separate child identity, reads/cache/reconnect, parent FCM registration and actual foreground/background receipt/tap, rotation/removal, MFA revocation and child denial. A separate child acceptance app may exercise the pairing seam; it is not Subproject 3. Preserve browser/child transport behavior when new fanout touches them. Provider success alone does not pass receipt acceptance.
- [ ] Exercise real current-device Auth sign-out and queued recipient resolution; verify old registration cannot send after confirmed remote invalidation. Test offline sign-out local hiding separately and report remote cleanup as unverified while offline. Never fabricate a completed logout/provider result.
- [ ] Verify exact disposable Auth/family/child/device/parent-registry/Web Push/outbox cleanup, preserve audit records, then clear local test enrollment/cache. Stop at exact missing config/credential/device/permission/deployment/decision gate; do not simulate completion.
- [ ] Commit sanitized runbook/CI evidence, update parent PR and Issue #1; keep PR integration awaiting explicit merge authorization. Subproject 2B and later roadmap products remain incomplete.


## Named RED assertions for the owning test files

These are the required test names and minimal assertions; define synthetic fixtures/fakes in their owning test files and use the interfaces above. No fixture value is a live credential. The descriptive steps above specify the remaining assertions, transaction setup and full checks.

```kotlin
// Task 1: EnvironmentConfigTest
@Test fun privateKeyCannotConfigureParentApp() {
    assertThrows(IllegalArgumentException::class.java) {
        EnvironmentConfig.read(mapOf("publishableKey" to "sb_secret_test_only"), false)
    }
}
// Task 5: AuthCallbackRouterTest (no active transaction, no recovery gate)
@Test fun ordinaryTokenLabelCannotUnlockRecovery() {
    assertNull(AuthCallbackRouter.parse(
        "harbor-parent://auth/callback?type=recovery#access_token=test_only", null))
}
// Task 6: FamilyCacheTest; fixture inserts only under subject A/family A
@Test fun otherSubjectCannotReadCachedFamily() = runTest {
    assertNull(repository.cached(parentB, familyA))
}
// Task 7: ParentMessageParserTest; unknown data must be refused
@Test fun unknownEnvelopeFieldIsRejected() {
    assertNull(ParentMessageParser.parse(mapOf("route" to "{}", "password" to "test_only")))
}
// Task 8: MfaRevocationTest; fake challenges succeed, backend calls are recorded
@Test fun successfulChallengeDoesNotAutomaticallyRevoke() = runTest {
    viewModel.challenge(factorId, "123456")
    assertEquals(0, fakeApi.revocationCalls)
}
```

Task 2 test name `duplicate_child_request_returns_same_id`: call `createChildAtomic` twice with the same normalized input; assert equal IDs, exactly one child/request and one creation audit with no name metadata. Task 3 `unexpired_jwt_with_removed_session_cannot_register`: remove the fixture session while retaining the valid JWT, call the new handler, assert HTTP 401 and zero active registrations. Task 4 `late_invalid_token_cannot_disable_rotated_registration`: resolve token generation A, rotate to B, invoke invalidation for A's hash/current lease, assert B remains active and other transports still send. Task 9 `parent_lifecycle_preserves_three_transport_identity`: repeat the exact event/dispatch, assert stable three-target intent identities and no duplicate domain mutation.

The parent receiver supports the current domain kinds `device.state.changed` and `device.command.created`; other kinds are refused until their separately approved product flows exist. Parent hints require current family/child/device/resource references and the stored registration ID, validated before rendering. Confirmed remote sign-out blocks new recipient resolutions; already in-flight/provider-accepted packets cannot be recalled and must be filtered by the current rendering marker. Generated Room entities use `(subjectId,familyId,resourceId)` keys. FamilySnapshot contains actual child/public-device fields plus fetch time, and no Auth credentials.

```typescript
// Task 2: create-child.test.ts; input/parents are owned synthetic DB fixtures.
Deno.test("duplicate_child_request_returns_same_id", async () => {
  const first = await createChildAtomic(input);
  const second = await createChildAtomic(input);
  assertEquals(second.id, first.id);
}); // pgTAP additionally asserts one child/request/audit and payload-conflict denial.
// Task 3: parent-session.test.ts; verified Auth fixture has a removed session.
Deno.test("unexpired_jwt_with_removed_session_cannot_register", async () => {
  await assertRejects(() => requireActiveParentSession(request, removedSessionDeps),
    HarborAuthError, "Authentication is required");
});
// Task 4: parent-fcm-persistence.test.ts; rotate the fixture to hashB first.
Deno.test("late_invalid_token_cannot_disable_rotated_registration", async () => {
  await store.disableParentFcm(registrationId, hashA, outboxId, attempt);
  assertEquals((await store.readParentFcm(registrationId, familyId))?.tokenHash, hashB);
});
// Task 9: parent-android-foundation.test.ts; first dispatches already completed.
Deno.test("parent_lifecycle_preserves_three_transport_identity", async () => {
  assertEquals(await dispatch(childIntentId), { status: "no_op" });
  assertEquals(await dispatch(parentIntentId), { status: "no_op" });
  assertEquals(await dispatch(webIntentId), { status: "no_op" });
}); // assert the same three durable IDs and unchanged domain rows in PostgreSQL.
```
## Plan self-review and execution handoff

Coverage: Tasks 1/5 implement secure build/Auth; Task 2 child creation; Tasks 3/4 parent FCM backend; Task 6 native setup/read/cache; Task 7 realtime/push/sign-out; Task 8 MFA/accessibility/platform evidence; Task 9 review/rollout/real cleanup. All five Review Focus conditions have owning tests. Interface names/ownership are consistent across tasks; encrypted Auth owns secrets, Room owns stale reads, ParentRuntime owns cross-component logout.

The dependency compatibility proof and real current-session logout proof are required, not assumed. Firebase client configuration, physical receipt and credentials remain operational gates at live acceptance. No product code/dependencies/migrations/scaffolding precede written-plan approval. After approval, use the preserved Native executor with a ledger in this plan's own ignored workspace, not the completed foundation ledger.