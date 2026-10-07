# Parent Android development acceptance

This is the approved roadmap 2A acceptance boundary. It does not complete the full app or Subproject 2B/3. Main and production remain separate from the draft parent branch.

## Existing evidence

- Tasks 1–8 are complete in the plan's own ignored progress ledger.
- Security integration `bf76e1b560254ea48ca43bee9b83564d48ae1b8d` passed all six required CI jobs in run `37607139640`, 72 JVM tests, strict dependency verification, lint with zero errors, and both APK assemblies.
- Actual API 29 and 36 instrumentation proves encrypted persistence/key loss, callback scrubbing, Room isolation, native font scale 1.8, notification controls and offline cleanup, explicit MFA/revocation retry, sensitive-window protection and Back secret erasure. SDK HTTP fixtures prove the supported Auth/MFA/session interfaces; they are not live Auth or provider receipts.
- Persisted integration `d30c5a99172da2ea088d828fdf9297fab19f6667` passed all six CI jobs in run `37609765475`. Real PostgreSQL proves one atomic child, session-bound registration, exactly child/browser/parent recipients, stable intent identities, no-op redispatch, one state mutation and removed-session denial. Provider callbacks and Auth subjects in this test are controlled fixtures.

The one fresh whole-parent review and its single test-first fix pass are verified at `ae0e3ae5bb16bea08f6eb92ed80b54c654afa24f`, all six CI jobs in run `37650898800`. Local 81 JVM tests, strict lint with zero errors and 19 warnings, and both APK assemblies passed. Matching API 29/36 runtime evidence and real PostgreSQL lifecycle/race proof passed. Never label a CI fixture APK as a live APK.

Development rollout applied exactly the three reviewed parent migrations; all 18 hosted migration versions and names match the repository. Only the three new parent functions and reviewed dispatcher were deployed. Existing gateway settings and unrelated function versions were preserved. The native callback was added through one declared configuration property; existing redirects and all undeclared remote settings were verified unchanged. Private table/function grants, RLS, indexes, session cascade and absent-session denial were verified. The parent endpoints deny missing authentication with 401; the worker dispatcher retains its existing 403 denial for a missing worker key.

The live APK and operational acceptance remain blocked by missing matching public Firebase Android client configuration for the parent package. Five public client configuration files in the authorized workspace/download locations were checked; none matches the non-CI parent package. No live native Auth/provider receipt or hosted fixture cleanup is claimed, and no operational fixtures were created during this rollout.

Post-DDL advisor information was reviewed. Private RLS without client policies is intentional default-deny with no client grants; retain the required anonymous child Auth and existing authorization predicates. Preserve needed unused indexes. The new composite child-request FK notice does not account for the unique child-ID index: a read-only development query plan uses that index with an estimated single-row lookup and family filter. This is not production-load verification. See the [RLS policy notice](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy), [FK index notice](https://supabase.com/docs/guides/database/database-linter?lint=0001_unindexed_foreign_keys), and [unused-index notice](https://supabase.com/docs/guides/database/database-linter?lint=0005_unused_index).

The review fixes preserve current-owner restart cache and pending keys, retain explicit notification opt-in independently of temporary confirmation, separate confirmed creation from failed refresh, require both supported kinds and complete child/device/event references, and fence removal with the server-verified session. Native stale-device and logout-availability regressions must pass on both required Android versions before rollout.

FCM callbacks enqueue reference-only work through the existing platform JobScheduler. Token work restores the captured opted-in owner and obtains the current provider token; it never queues a Firebase token. JobService ties coroutine execution to Android's job lifetime and cancels it when stopped. Per-item work is bounded; failed registration remains unconfirmed and explicit/foreground recovery stays available. This uses the existing pinned graph. See the [Firebase lifecycle guidance](https://firebase.google.com/docs/cloud-messaging/android/receive-messages) and [Android JobService contract](https://developer.android.com/reference/android/app/job/JobService). Native construction and mocked cold-owner tests do not prove real background receipt.

## Rollout gate

1. Complete the final fresh-context review of the entire parent branch against the approved specification/plan. Resolve material findings test first in one fix pass and run every required CI job on the resulting revision.
2. Compare development migration versions and names with the repository. Confirm the exact dry run contains only the pending parent migrations `20261006222258_parent_child_creation`, `20261006225849_parent_fcm_registry` and `20261006233238_parent_fcm_fanout`. Apply them only to the designated development project after the GREEN/review gate.
3. Deploy the reviewed new/changed functions, including child creation, parent registration/removal and the dispatcher. Preserve existing JWT, CORS and child wire behavior. Add only `harbor-parent://auth/callback` to development Auth redirects while retaining existing redirects/settings.
4. Inspect schema/grants/indexes/advisors and current-session behavior after rollout. Preserve required indexes and anonymous child Auth. Synthetic workload results are not production-load proof.
5. Obtain public Firebase Android configuration matching `dev.stmedrano.harbor.parent` and the designated development Firebase project. Do not substitute the child configuration or put server/service/worker keys in the APK. Build with live development public inputs, inspect manifest/provenance, and use the already authorized secure development download workflow.

Stop at any exact missing configuration, credential, permission, deployment or decision gate. Do not create live fixtures until the required environment and recipient are available.

## Real native checklist

Use Pixel 9a and an owned disposable development inbox. Keep passwords, tokens, TOTP setup/code and worker keys out of chat, GitHub, screenshots and the public artifact. Auth/MFA screens intentionally prevent capture.

- Fresh native signup/confirmation, sign-in, cold restore, password recovery and rejection of the previous password.
- Same-user family/atomic child setup, stable-key retry and pairing to a separate anonymous child acceptance identity. The child acceptance app tests the pairing seam; it is not the child product.
- Actual family/child/device reads, explicit stale cache and disabled mutations, family switching, foreground recovery and private Realtime reconnect.
- Explicit notification permission and confirmed current-session registration. Separately prove actual foreground and background parent receipt and an authorized tap. Confirm child/browser delivery remains intact for the exact same event.
- Token rotation/retry/removal, duplicate hints, old binding rejection, remote current-device logout/queued resolution denial, and offline local hiding with remote cleanup unconfirmed.
- Native TOTP verification never automatically revokes. A separate confirmation uses fresh server-enforced AAL2; verify revocation and immediate signed child denial.

## Exact event and receipt evidence

Reuse the existing protected notification operator directory and credential handling. The parent acceptance helper validates the complete child/browser/parent batch and checkpoints every durable identity before dispatch. Worker credentials remain in memory; the canonical private journal contains only fixture and event references.

`tests/hosted/parent-android-acceptance.ts` adds parent binding validation and registration-specific receipt correlation to the existing exact-event/time-window helpers. Structural manifest validation is not cleanup authority: use current trusted SQL/Auth discovery and exact-run fixture provenance before destructive operations.

For a controlled delivery, record the pre-dispatch observation boundary, exact three recipient identities, expected route/resource and current parent registration. Dispatch only that checkpointed batch. A worker `sent` response means provider acceptance. It does not prove phone receipt.

The live development APK exposes the latest authorized local receipt in Settings and can export it through the Android document picker. This contains route/registration/time references, never Auth or Firebase tokens. Save each observation before sending the next controlled event. It is non-authoritative client evidence and is erased on binding invalidation; do not fabricate it or substitute emulator callbacks.

Require matching registration, family/child/device/resource references and observation window through `correlateParentReceipts`. Reuse `classifyDelivery` to keep provider acceptance distinct from observed receipt. A mismatched, old, missing or sensitive-field observation remains unverified.

## Cleanup and integration

After required live evidence is collected, use trusted exact-run discovery and the existing guarded cleanup phases. Verify operational/Auth fixture rows are zero independently, including parent registration/session rows, browser/child subscriptions and outbox. Retain audits. Clear only the exact local test enrollment/cache after confirmed hosted cleanup.

Publish sanitized stage outcomes, revision and CI only. Keep credentials, endpoints, inboxes and fixture identifiers in restricted local evidence. Parent PR integration still awaits explicit merge authorization. Issue #1 and later roadmap products remain incomplete.
