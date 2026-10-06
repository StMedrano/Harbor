# Harbor Notification Event Correlation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correlate browser and Android receipts with the exact desired-state event, including successive versions and delayed duplicates.

**Architecture:** Reuse optional V1 `resourceId` as an immutable event UUID. A private trigger persists one identity across recipient rows; the operator checkpoints trusted event mappings and compares full routes. Signed sync remains authoritative.

**Tech Stack:** PostgreSQL/pgTAP, Supabase, Deno/TypeScript, existing Android Kotlin/JUnit toolkit.

**Spec:** `docs/superpowers/specs/2026-10-04-harbor-notification-event-correlation-design.md` (approved 2026-10-04).

**Status:** User approved this plan on 2026-10-04. Tasks 1–3 implemented and verified; development migration applied. Task 4 live acceptance is blocked by missing Firebase client/build configuration, Android recipient and secure worker-key input.

## Global Constraints

- Keep V1 and the existing optional UUID field.
- No new contract field, envelope version, public API, table, column, extension, dependency, or secret.
- Append a migration replacing `private.harbor_record_device_notification()`; do not edit applied migrations.
- Preserve command-ID semantics for `device.command.created`.
- Preserve the observation start, baseline count and 120-second observation window.
- `no_op` remains unverified by itself. Provider acceptance and receipt remain separate.
- No backfill, rewrite, or forced resend of existing queued/sent legacy rows.
- Development only: no production migration, main merge or Vercel product deployment.
- Keep recent-MFA, cross-family authorization, device proof, replay rejection, revocation and cleanup gates.
- Stop at missing credentials, permissions, deployment/migration failure, an approval gate or unresolved decision.

## Review Focus

- An old duplicate arrives inside the new observation window: refuse it by event UUID (Task 2).
- Existing event rows mix legacy and identified routes: fail atomically before adding recipients (Task 3).
- A checkpoint is replayed with changed recipient targets or a reused UUID: refuse dispatch before any worker call (Task 2).
- A previously sent event is redispatched and returns `no_op`: retain identity and inspect trusted persisted status separately (Tasks 2 and 4).
- UUID case differs between otherwise equal routes: normalize UUID comparisons consistently; retain the original validated payload (Tasks 2 and 3).

## File Map

- Recipient persistence checks: `tests/notification-acceptance/web-receipts.test.ts` and `tools/notification-acceptance/android/app/src/test/java/dev/stmedrano/harbor/acceptance/ReceiptStoreTest.kt`. Modify receipt implementation only if a meaningful regression fails.
- Operator validation, checkpoint and correlation: `tests/hosted/notification-acceptance.ts`; tests in `tests/notification-acceptance/operator.test.ts`, `evidence.test.ts`, and `recovery.test.ts`.
- Backend intent identity: new `supabase/migrations/20261005014628_notification_event_correlation.sql`; checks in new `supabase/tests/notification_event_correlation.test.sql` using established fixture conventions.
- Compatibility regressions: existing contract/function suites and `tests/integration/outbox-persistence.test.ts` where stored-route retry behavior needs an assertion.
- Operator instructions and live evidence limitations: `docs/runbooks/notification-acceptance.md`.
- Execution tracking: ignored `.superpowers/sdd/2026-10-04-harbor-notification-event-correlation/progress.md`.

Check the migration timestamp is unused and later than every existing migration before creating it; advance it if necessary. Do not restructure the toolkit or change CI dependencies.

### Task 1: Preserve event identity in recipient receipts

**Interfaces:** Consume the existing `NotificationRouteRefV1`, browser `parseReceipt`/`receivePush`, and Android `ReceiptStore`. Produce unchanged receipt formats containing validated `resourceId`.

- [x] **Step 1: Add focused persistence assertions.** Use a complete `device.state.changed` route with a fixed valid event UUID. Assert browser save/read retains all six fields and duplicate delivery records the same UUID twice without domain calls. In `ReceiptStoreTest.kt`, use the existing storage fake to save, recreate the store and read the full route. Assert invalid UUIDs and an added `accessToken` are rejected; legacy routes remain readable.
- [x] **Step 2: Run focused tests before changes.** `deno test --frozen tests/notification-acceptance/web-receipts.test.ts`; Android `./gradlew -PacceptanceCiFixture=true --no-daemon --dependency-verification=strict testDebugUnitTest --tests '*ReceiptStoreTest'` with the existing CI fixture configuration. These compatibility assertions may already pass because the feature exists; do not invent a RED or change working parsers. Any failure must be understood before editing.
- [x] **Step 3: Fix only a proven persistence defect.** Preserve existing APIs and unknown-field/UUID validation. If Step 2 passes, no implementation edit is needed.
- [x] **Step 4: Run the full browser receipt suite and Android receipt tests.** Expected all pass; Android execution uses CI when local Java/SDK is absent.
- [x] **Step 5: Commit the recipient compatibility evidence.** Commit message `test: preserve notification event identity in receipts`.

### Task 2: Validate trusted mappings and correlate exact events

**Files:** Operator, operator/evidence/recovery tests and runbook listed above.

**Interfaces:** Keep `validateDispatchRows(manifest: FixtureManifest, input: unknown): OutboxRow[]` and `dispatchRows` existing arguments. Add exported `EventMapping` with `eventKey: string`, `desiredStateVersion: number`, `route: NotificationRouteRefV1`, `recipients: { id: string; transport: 'fcm' | 'web_push'; targetRef: Record<string,string> }[]`. Add `checkpointEvent(manifest: FixtureManifest, rows: OutboxRow[], existing: EventMapping[]): EventMapping[]`. Extend `correlateReceipts` with final optional `mapping?: EventMapping`; absence returns no matches. Existing observation-boundary validation still throws for malformed boundaries.

- [x] **Step 1: Add RED tests for identified dispatch.** Exact six-field fixture routes sharing one valid UUID pass; wrong event key, foreign family/device/child, malformed UUID, mixed IDs, extra fields, duplicate targets and missing expected recipients throw. All-legacy rows remain dispatch-compatible but cannot create an identified checkpoint. Reject a mixture of legacy and identified routes. Assert a rejected batch makes zero worker calls.
- [x] **Step 2: Add RED checkpoint/correlation tests.** Same mapping replay is idempotent; changed route/recipient identity for the version and reuse of its UUID by another version throw. Case-only UUID differences compare equal. For version 2, a late version-1 UUID after baseline yields `[]`; the version-2 UUID yields its receipt. Missing mapping, legacy route, wrong fixture mapping, extra fields, pre-baseline receipt and times outside `[start, start + 120000]` yield no verified match. Multiple matching hints stay multiple receipts for one mapping, not multiple events. Preserve `sent` without receipt and `no_op` with receipt as unverified; `retry`/`dead_letter` remain provider failures.
- [x] **Step 3: Confirm focused RED.** `deno test --frozen tests/notification-acceptance/operator.test.ts tests/notification-acceptance/evidence.test.ts tests/notification-acceptance/recovery.test.ts`. Record the failed behavioral assertions; missing imports may be resolved with minimal empty signatures, then rerun to see actual assertions fail. Commit/push RED before changing behavior.
- [x] **Step 4: Implement the named interfaces.** Reuse existing route parser, manifest and exact recipient whitelist. Require one shared UUID for identified batches. Validate stored mappings when loading; never trust a TypeScript cast of external evidence. Compare normalized UUID values and every allowed route field. `checkpointEvent` accepts identified rows only and checks current fixture/event/version/recipient identities against prior records. Legacy dispatch remains possible but yields no exact mapping.
- [x] **Step 5: Wire checkpoint persistence into the existing dispatch command.** Load restricted `event-mappings.json` (missing file means `[]`; malformed/unreadable file stops). Validate the trusted SQL row batch, checkpoint before worker calls, and store the mapping with existing protected-file helpers. Preserve mappings across `change-state`. Save the mapping alongside dispatch results; refuse replacement of a conflicting checkpoint. Persist no endpoints, tokens or keys. Do not add hosted credential discovery or a private Data API.
- [x] **Step 6: Update the runbook observation procedure.** Explain how trusted exact-event SQL metadata enters dispatch, how to select its persisted mapping for `correlateReceipts`, and why legacy/missing mapping cannot pass. Redispatch must compare its trusted stored route and `sent` status with the original checkpoint; a `no_op` response alone stays unverified. Keep the existing removal/revocation/cleanup sequence.
- [x] **Step 7: Verify GREEN.** Run focused tests, then `deno test --frozen tests/notification-acceptance` and `deno check --frozen tests/hosted/notification-acceptance.ts`. Use `--allow-read --allow-write --allow-run=powershell.exe` only for the existing Windows ACL test. All behavioral assertions must pass; permission-gated skips must be reported accurately.
- [x] **Step 8: Commit/push.** Commit message `feat: correlate receipts with trusted notification events`.

### Task 3: Persist a stable event UUID across backend fanout

**Interfaces:** Replace only `private.harbor_record_device_notification() returns trigger`, retaining `security definer set search_path = ''` and private grants. Consume existing event keys, outbox rows and enqueue helper. Produce six-field new state routes; commands retain command resource IDs; existing legacy events retain five-field routes.

- [x] **Step 1: Add pgTAP RED assertions.** Build a parent/device with two active Web Push installations using the existing outbox domain test fixture pattern. Assert one state write yields three rows with one UUID and exactly six route fields, the next version has a different UUID, unchanged-version update adds no row, and command `resourceId` equals its command ID. Assert UUIDs match the existing route-validator UUID form and private grants remain denied to public clients.
- [x] **Step 2: Add replay/atomicity RED assertions.** Preseed the exact upcoming event key with one identified recipient, invoke the normal state write, and assert remaining recipients reuse its UUID. Repeat with all-legacy rows and assert every row remains legacy. For conflicting IDs, invalid IDs and mixed legacy/identified rows, use `throws_ok` around the state write and assert state/version and recipient counts are unchanged. Test case-only UUID differences as one identity. Assert duplicate enqueue never overwrites an existing route; add a retry persisted-route assertion to the integration suite if existing coverage does not prove it.
- [x] **Step 3: Commit/push tests and confirm CI RED.** Required foundation job runs fresh `supabase db reset`, `supabase db lint --level error`, `supabase test db` and integration tests. Verify failures come from the new identity assertions while established suites pass. Do not add the migration before observing expected RED.
- [x] **Step 4: Add the append-only migration.** Preserve unchanged-version early return, event key generation, recipient selection and command branch. For state events, inspect every existing row for the exact event key before any enqueue: no rows generates one `gen_random_uuid()`; all legacy omits `resourceId`; all identified requires valid UUIDs and one normalized value, then reuses it. Mixed/conflicting/invalid identities raise an exception. Add the identity to the common route once, before fanout. Retain transactional execution and existing enqueue conflict behavior.
- [x] **Step 5: Verify GREEN in CI.** All new pgTAP assertions and existing DB/function/contract/release/integration/end-to-end suites must pass. Inspect failures at their source; do not alter security controls or historical migrations to make assertions pass.
- [x] **Step 6: Commit/push.** Commit message `feat: persist stable desired-state notification identities`.

### Task 4: Verify the full slice and authorized development rollout

**Interfaces:** Consume Tasks 1–3, exact-head CI, connected development Supabase migration state, and restricted operator evidence. Produce separate source/CI/migration/live status in the runbook, PR and Issue #1.

- [ ] **Step 1: Verify the exact source head.** Require `notification-web`, `notification-android` and `foundation` success. Android runs strict dependency verification, unit tests, lint, APK and manifest checks; browser runs tests, type checks and bundle build. Do not treat older green CI as evidence for the new head.
- [ ] **Step 2: Perform the native execution whole-branch review required by executing-plans.** Address meaningful findings with focused regressions, repeat only affected verification plus required gates when code changes, and record review rulings. Do not clean up unrelated code.
- [ ] **Step 3: Inspect development before migration.** Confirm connected project `bfvybxkjxilntjgndsrm`, compare recorded/applied migration versions with the repository, and inspect the current trigger definition. Stop on divergent history or unavailable permission. Ensure recipient/operator compatibility is committed and verified first.
- [ ] **Step 4: Apply only the new development migration using the connected Supabase migration capability.** Verify the applied version and actual function definition/private grants afterward; report any failure and stop. Never run a remote database reset. Do not manufacture a live fixture solely to claim receipt delivery.
- [ ] **Step 5: Check live prerequisites without printing secrets.** Matching Firebase Android client configuration, Google Play-capable recipient, notification permission and secure worker-key input must exist. If absent, record the precise prerequisite and stop live work with automated results intact.
- [ ] **Step 6: If prerequisites exist, resume the approved toolkit live procedure.** Use its exact fixture and harmless real state changes; observe real foreground/background receipts with current mappings. Recheck delayed duplicate exclusion, redispatch unchanged identity with persisted `sent`, browser removal and signed revoked-device denial. Keep provider outcomes distinct from receipts. Cleanup follows discovery, revoke, observed denial, then finalize, preserving recovery evidence on failure.
- [ ] **Step 7: Update runbook, PR #15 and Issue #1.** Include revision, exact CI and development migration result; publish no fixture identifiers or secrets. Distinguish live observed results from blocked/unverified results. Issue #1 remains open. Commit documentation and push the focused branch; do not merge main or deploy production.

## Self-review and handoff

Spec coverage checked: routing/persistence/retry/legacy/atomicity (Task 3), recipient compatibility (Task 1), checkpoint and correlation/provider semantics (Task 2), rollout/required gates/live prerequisites/cleanup (Task 4). Review Focus cases each have owning tests. Interfaces retain established APIs except the explicit optional mapping argument and two new operator exports; no new package or platform surface is required.

The user approved the plan. Use the preserved native execution method with the ignored progress ledger; implementation begins with compatibility evidence and RED tests. Any absent live prerequisites stop live acceptance without claiming Task 6 complete.
