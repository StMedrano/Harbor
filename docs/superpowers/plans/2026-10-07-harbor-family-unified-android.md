# Harbor Family Unified Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Native inline execution is already selected. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship one Harbor Family development APK with isolated parent authentication and child pairing, each leading to its authorized dashboard.

**Architecture:** Evolve the existing Compose application through a small validated profile coordinator. Reuse the parent runtime and existing child proof/transport contracts, with separate encrypted child credentials and Keystore aliases. Profile transitions require confirmed cleanup; setup choices and incoming messages never grant authorization.

**Tech Stack:** Kotlin/Compose, existing Supabase 3.8.0, Ktor 3.5.1, coroutines/serialization 1.11.0, Compose BOM 2026.09.00, Activity 1.13.0, Room 2.8.5, Firebase BOM 34.19.0; compile37/AGP9.1.1/Gradle9.3.1/target36/min29/JDK17. Preserve all other checked-in pins.

**Spec:** `docs/superpowers/specs/2026-10-07-harbor-family-unified-android-design.md` (approved 2026-10-07).

## Global Constraints

- Public name Harbor Family; retain `dev.stmedrano.harbor.parent`, existing signing identity and `harbor-parent://auth/callback`.
- Use the supplied Parent and Child Preview HTML and existing native Vercel tokens; preserve Kombai files.
- No menus on setup/login/signup/recovery. Child selection means device enrollment, not child email signup.
- One active role; separate clients, credential namespaces, keys, caches, jobs and generations. Erase parent credentials before child activation.
- Preserve parent 0.4 updates, backend authorization, child wire formats, acceptance tool, PR15, main and production. No automatic acceptance-app credential migration.
- No new backend schema/payload, privileged permissions, dependencies or navigation framework. An unsupported required contract stops implementation for a separately approved amendment.
- No fake data, unsupported enforcement/privacy claims, maps, app approvals, SOS or monitoring. Today/Apps/About report actual capabilities only.
- Stop at actual credential/config/device/approval blockers. Never request passwords/private keys in chat or infer provider/device receipts.
- Hourly work remains paused. Plan approval precedes execution; this document is not execution evidence.

## Review Focus

1. Upgrade or corrupt mode hints: preserve valid parent 0.4 state; ambiguous parent/child records fail closed (Task1).
2. Pairing response lost after server commit: no blind re-claim, duplicate effects or inferred binding (Task2).
3. Token/push/job completes after role change: captured profile generation cannot affect the new profile (Task4).
4. Wrong-family/stale-MFA approval or offline cleanup: child remains enrolled and parent dashboard stays inaccessible (Task5).
5. Revocation/key loss during cached display: child recovery is blocked, stale state is labeled and no parent controls appear (Tasks2/3).

## File and interface map

All native paths below are relative to `apps/parent-android/app/src/main/java/dev/stmedrano/harbor/parent/`; tests mirror packages under `src/test/java/.../parent/` and `src/androidTest/java/.../parent/`.

- New `profile/ProfileState.kt`, `ProfileCoordinator.kt`, `ProfileStore.kt`: validated role/bootstrap/transition state and generation.
- Modify `ParentApplication.kt`, `MainActivity.kt`: active runtime construction and guarded entry; retain existing parent services internally.
- New `child/ChildCredentials.kt`, `EncryptedChildStore.kt`, `ChildDeviceKey.kt`, `ChildApi.kt`, `ChildRepository.kt`: separate device identity, cryptography, exact protocol, confirmed binding and signed state.
- New `ui/FamilyEntryScreen.kt`, `ChildPairingScreen.kt`, `ChildDashboard.kt`: setup and truthful child screens; reuse existing parent AuthScreen/ParentSessionContent/FamilyScreen.
- New `notifications/FamilyMessagingService.kt`, `ProfileNotificationRouter.kt`; modify parent notification job scheduling only at the profile boundary: one Firebase service and generation-fenced dispatch.
- New `profile/ParentApproval.kt`, `RoleTransition.kt`, `ui/ParentApprovalScreen.kt`: temporary isolated parent authorization and confirmed role cleanup.
- Modify manifest, launcher strings, existing backup/extraction rules and native acceptance runner: product label, single service, exclusion/proof.
- New `docs/runbooks/harbor-family-acceptance.md`: real same-APK acceptance, upgrade and rollback evidence.

Shared types defined in Task1: `enum class ProfileRole { PARENT, CHILD }`; `data class ProfileLease(val role: ProfileRole, val ownerId: String, val generation: Long)`; sealed `ProfileState` with `Setup`, `Parent(lease)`, `Child(lease)`, `Blocked(reason)`, `Transitioning`. `Blocked` reasons are safe enum values, never raw network errors. `ProfileCoordinator.state: StateFlow<ProfileState>`; `suspend fun restore(): Unit`; `fun currentLease(): ProfileLease?`; `fun isCurrent(lease: ProfileLease): Boolean`; `suspend fun activateParent(): Unit`; `suspend fun activateChild(): Unit`. Activation validates the respective repository; selection alone cannot activate.

## Execution and verification conventions

At execution, inspect current main/PR16/CI and read both this spec and the parent ledger. Use the worktree skill to create an isolated `feat/harbor-family-unified-android` checkout from the approved plan revision; leave the parent checkout and PR16 intact. Create own `.superpowers/sdd/2026-10-07-harbor-family-unified-android/progress.md` with baseline, exact RED/GREEN logs, commits, blockers and task status. New draft PR targets `feat/parent-android-foundation`; attach it to this chat. No merge is authorized.

Commands run from `apps/parent-android`, using existing JDK17/SDK/Gradle runtime paths. `gradlew.bat -PparentCiFixture=true testDebugUnitTest --tests "<class>"` is the focused JVM command; expected missing-interface compilation RED first, then behavioral assertion RED before fixes when applicable, then GREEN. Shared native gate: `gradlew.bat -PparentCiFixture=true testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`; expected BUILD SUCCESSFUL, zero lint errors, inspect warnings without suppression. Native tests require actual API29 and API36 runs through the existing CI workflow/acceptance runner, not API37 assembly or metadata as proof. Record exact matching SHA, all six required jobs and individual test logs. Existing passing behavior needs regression proof, not manufactured RED.

### Task 1: Validated profile bootstrap and compatible parent upgrade

**Files:** profile map files, ParentApplication/MainActivity; tests `profile/ProfileCoordinatorTest.kt`, native `profile/ProfileUpgradeTest.kt`.

**Interfaces:** shared types above; `interface ProfileStore { suspend fun read(): ProfileRole?; suspend fun write(role: ProfileRole): Unit; suspend fun clear(): Unit }`. Coordinator consumes injected parent/child validation and runtime stop/start adapters; parent validation uses existing `ParentAuthRepository.restore()` and verified identity. Persisted hint never replaces credential validation.

- [ ] Write `freshInstallIsSetup`, `parent04WithoutModeRestoresParent`, `bothProfilesFailClosed`, `hintCannotGrantDashboard`, `lateOldRestoreCannotActivate`. Assert Setup for empty stores; Parent only with valid verified identity; Blocked for contradictory records; stale lease fails `isCurrent`.
- [ ] Run focused `*ProfileCoordinatorTest`; inspect RED before implementation.
- [ ] Implement bootstrap and active-only runtime construction; preserve parent credential/key/database names and stable installation identity. Stop old jobs before incrementing generation. Never eagerly construct child and parent network runtimes together.
- [ ] Run focused GREEN and native upgrade test using seeded existing parent namespace; assert cache/pending keys survive without new pairing. Run shared gate.
- [ ] Commit `feat: add validated Harbor Family profile bootstrap`; record evidence in own ledger.

### Task 2: Encrypted child enrollment and signed synchronization

**Files:** child map files; JVM `child/ChildRepositoryTest.kt`, `ChildProtocolTest.kt`; native `child/ChildCryptoTest.kt`.

**Interfaces:** `data class ChildCredentials(val accessToken: String, val refreshToken: String, val expiresAt: Long)`; `data class ChildBinding(val deviceId: String, val familyId: String, val childId: String)`; `sealed interface PairResult { data class Confirmed(val binding: ChildBinding): PairResult; data object Rejected: PairResult; data object UnknownOutcome: PairResult }`; `ChildRepository.binding: StateFlow<ChildBinding?>`; `suspend fun restore(): Unit`; `suspend fun pair(code: String): PairResult`; `suspend fun sync(): ChildSyncState`; `suspend fun clearAfterConfirmedRevocation(): Unit`. `ChildSyncState` is `Fresh(receivedAt: Long, desiredVersion: Long)`, `Stale(lastSuccessAt: Long?)`, or `Blocked(reason)`; it is never an enforcement assertion. `ChildDeviceKey.publicKeySpki(): String`, `sign(bytes: ByteArray): ByteArray` (64-byte P1363).

- [ ] Read existing `DeviceIdentity`, `AndroidDeviceIdentity`, `DeviceProof`, `HarborApi` and device-claim/device-sync server tests. Pin canonical byte/signature vectors and exact existing body/header fields. Product device display name is Harbor Family; no acceptance fixture label.
- [ ] Write tests `parentSessionRejected`, `claimConfirmsBeforePersist`, `invalidCodeKeepsSetup`, `lostResponseDoesNotReclaim`, `existingBindingSurvivesFailure`, `signedSyncUsesOnlyChildBearerKey`, `refreshKeepsAnonymousOwner`. Assert one claim attempt for unknown outcome, no binding/dashboard on lost response, non-anonymous credentials denied, exact headers and P1363 proof unchanged.
- [ ] Run focused RED; establish whether current contract can recover a lost claim with authenticated evidence. If it cannot, persist a credential-free pending/unknown marker and block re-claim until parent-authorized reconciliation; do not add an unapproved recovery endpoint or consume another code blindly.
- [ ] Implement reuse of protocol algorithms through product-focused child classes; keep acceptance tool unchanged. Use a distinct encrypted Keystore-backed store/alias, separate anonymous client, seconds-based expiry refresh and confirmed binding. Persist session before claim; validate UUID bindings and anonymous owner. No plaintext tokens, receipt preferences or parent SDK session reuse.
- [ ] Run focused GREEN. Native tests prove P256 signing, encrypted reopen/refresh, no plaintext credentials, key-loss Blocked, interrupted writes fail closed, distinct parent/child aliases and backup exclusions on API29/36. Run shared gate.
- [ ] Commit `feat: add isolated child pairing and signed sync` with exact protocol/crypto evidence.

### Task 3: Harbor Family setup and honest dashboards

**Files:** entry/pairing/dashboard UI, MainActivity, launcher strings; JVM `ui/FamilyEntryStateTest.kt`; native `ui/FamilyRoleNavigationTest.kt`.

**Interfaces:** `FamilyEntryScreen(onParent: () -> Unit, onChild: () -> Unit)`; `ChildPairingScreen(repository: ChildRepository, onConfirmed: () -> Unit)`; `ChildDashboard(state: ChildSyncState, binding: ChildBinding, onSync: () -> Unit, onRequestRoleChange: () -> Unit)`. Existing parent AuthScreen callbacks remain the parent path; coordinator validates before activation.

- [ ] Write native tests `setupHasNoMenu`, `parentLoginOpensOnlyParent`, `childCodeCannotOpenParent`, `confirmedChildRestoresToday`, `recoveryBackDoesNotEscape`, `revokedChildCannotOpenParent`. Assert no protected menu before authorization and Today/Apps/About only after confirmed binding.
- [ ] Publish/run actual API29/36 RED before UI changes; JVM state tests also fail for missing guarded routes.
- [ ] Implement preview-themed native role setup, parent Auth flow and child pairing. Rename launcher Harbor Family. Today shows actual signed-sync status/time with stale/offline label; Apps explicitly unavailable; About lists implemented capabilities only. No role toggle on enrolled child, fake app inventory or parent-only name query.
- [ ] Run GREEN including parent restoration/recovery/session-loss regressions; inspect native light/dark screenshots and system-font1.8 scrolling, keyboard/Back, secret erasure and sensitive-window protections on API29/36. Run shared gate.
- [ ] Commit `feat: add Harbor Family role setup and child dashboard`.

### Task 4: One messaging entry and fenced active-profile lifecycle

**Files:** notification map files, ParentApplication, manifest; JVM `notifications/ProfileNotificationRouterTest.kt`; native `notifications/FamilyNotificationLifecycleTest.kt`.

**Interfaces:** `ProfileNotificationRouter.accept(data: Map<String, String>, lease: ProfileLease): Boolean`; `suspend fun syncToken(lease: ProfileLease): Unit`; `fun stop(lease: ProfileLease): Unit`. Router delegates unchanged parent/child envelope validators and registration APIs; child work obtains its signed binding, parent work retains current-session registration logic.

- [ ] Write `wrongProfileDenied`, `rotationPreservesActiveOwner`, `lateTokenAfterTransitionIgnored`, `oldJobCannotRegister`, `tapCannotChangeRole`, `coldStartUsesValidatedBinding`, `offlineRegistrationUnconfirmed`. Assert only matching current lease can publish/register; one supported Firebase service; no tokens serialized into jobs/preferences.
- [ ] Run focused RED before router/service wiring.
- [ ] Implement single native service and supported bounded JobService lifetime using existing job patterns. Stop collectors/jobs and hide prior hints on lease changes; retain independent durable opt-in; parent Realtime stays parent-only, child hints trigger authorized signed sync. Receipt/provider status remains separate.
- [ ] Run focused GREEN, existing parent strict-message/job/logout regressions and native cold-start/rotation/background/tap/offline tests on API29/36; run shared gate.
- [ ] Commit `feat: route notifications through the active Family profile`.

### Task 5: Temporary parent approval and confirmed role transitions

**Files:** approval/transition UI and profile map files; JVM `profile/RoleTransitionTest.kt`; native `profile/ParentApprovalIsolationTest.kt`.

**Interfaces:** `interface ParentApproval { suspend fun authorize(binding: ChildBinding): Boolean; suspend fun revoke(binding: ChildBinding): Unit; suspend fun clear(): Unit }`; `RoleTransition.suspend fun parentToSetup(): Boolean`; `suspend fun childToSetup(approval: ParentApproval): Boolean`. Boolean success means remote cleanup AND local credential erasure confirmed; failure never activates another profile.

- [ ] Write `foreignFamilyDenied`, `staleMfaNoSideEffects`, `cancelClearsTemporaryCredentials`, `offlineRevokeKeepsChild`, `serverSuccessPrecedesKeyDeletion`, `parentCleanupFailureBlocksChild`, `callbackCannotOpenParentDashboard`, `lateApprovalCannotEraseNewBinding`. Assert exact family/device matching and fresh MFA, no child key deletion until revoke response confirmed, temporary Auth isolated and erased, dormant parent PKCE/session cleared before child activation.
- [ ] Run focused RED before implementing transition adapters.
- [ ] Reuse existing supported Auth/MFA and `ParentApi.revokeDevice(familyId: String, deviceId: String)` through a separate temporary client/store. Limit approval UI to this operation, guard callback transaction and FLAG_SECURE; cancel/Back erases approval secrets. Confirm parent registration/session cleanup before parent-to-setup; a failure remains blocked with honest retry guidance.
- [ ] Run focused GREEN and actual API29/36 approval/cancel/Back/credential-isolation tests. Preserve backend stale-MFA/session fencing tests without redoing hosted fixtures. Run shared gate.
- [ ] Commit `feat: require parent approval for enrolled profile changes`.

### Task 6: Whole-feature verification, development APK and real acceptance

**Files:** existing CI/native runner, `docs/runbooks/harbor-family-acceptance.md`, own ledger; no speculative backend changes.

- [ ] Add meaningful composition tests across profile/pairing/messaging/approval seams where not already covered: crash between remote cleanup and local erasure; ambiguous restart stays blocked; revoked child cold start hides protected state; upgrade retains stable pending parent operations. Observe actual RED for a defect before its minimum fix.
- [ ] Run all JVM/lint/APK gates, all existing foundation/web/child-tool gates and actual new API29/36 native tests. Record matching six-job GREEN and inspect exact logs/screenshots. No API37 fallback or canceled newest validation.
- [ ] Perform one fresh review of this unified feature against approved spec, followed by one test-first material fix pass and final matching required GREEN. Do not repeat the completed original parent whole-branch review. Preserve inline execution; final review follows the already selected workflow.
- [ ] Build non-fixture development APK using authorized local public config. Verify CI_FIXTURE=false, technical package, Harbor Family label, min/target, backup/cleartext exclusions, signature matches parent0.4, one service and no privileged credentials. Produce ZIP plus SHA256 and publish authorized development prerelease; do not call it accepted yet.
- [ ] Use same APK on separate real designated parent/child phones. Verify native parent signup/email/verification/recovery, update/restore/cache/reconnect; issue a separate fresh child pairing code and confirm exact product binding/signed sync. No acceptance-app key copying. Stop if a second recipient or secure setup is unavailable.
- [ ] Record exact child/browser/parent event provider acceptance separately from observed receipts; test foreground/background/tap/rotation, removal/current-install logout, fresh/stale/foreign MFA, immediate revoked-child denial. Export only approved receipt evidence; sanitized PR/Issue updates contain stage/revision/CI, no identifiers or endpoints.
- [ ] Independently verify exact disposable operational/Auth fixture cleanup with audits retained; never remove an active fixture to manufacture completion. Record real device dark-mode, large-text and TalkBack behavior. Only then mark unified foundation acceptance complete; full app/Issue1 remains incomplete until all approved roadmap work is actually done.
- [ ] Commit `docs: record Harbor Family acceptance and release evidence` and update sanitized PR/Issue status.

## Upgrade and rollback

Before a live update, verify signing identity and package equality with parent0.4. Do not rename/clear parent namespaces or export credentials. New child/profile stores use distinct aliases and excluded backup paths. No database/backend rollout is planned here; any discovery requiring one stops at the design gate.

Rollback is a separately built prior parent implementation with a higher versionCode and the same signing key, for confirmed parent-only installations. A prior parent APK cannot interpret enrolled child state: do not downgrade a child installation or delete its stores as rollback. Parent-authorized confirmed revoke/cleanup must precede returning that installation to a parent-only build. Preserve blocked state and operational evidence when cleanup cannot complete.

## Plan self-review and handoff

Spec sections map to Tasks1–6: identity/upgrade1, protocol/storage2, navigation/dashboard3, notification lifecycle4, role authorization5, required review/native/live acceptance6. All five Review Focus conditions have named tests; shared types and consumer interfaces match. Deferred policy/enforcement features remain excluded. No product implementation has started under this plan.

Written-plan approval is required. After approval, execute natively in this chat using the isolated checkout and own ledger; do not ask the execution-method menu again.
