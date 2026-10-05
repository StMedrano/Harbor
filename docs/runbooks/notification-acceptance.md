# Development notification acceptance

Use only disposable development project `bfvybxkjxilntjgndsrm`. Provider acceptance and recipient receipt are separate results. Tests and the CI fixture APK do not prove live delivery.

## Automated checks

From repository root, with Deno 2.9.7:

```sh
deno test --frozen tests/notification-acceptance
deno check --frozen tests/hosted/notification-acceptance.ts tools/notification-acceptance/web/app.ts tools/notification-acceptance/web/service-worker.ts
deno run --frozen --allow-read --allow-write --allow-run=deno tools/notification-acceptance/web/build.ts
deno run --frozen --allow-read=tools/notification-acceptance/web/dist --allow-net=localhost:3000 tools/notification-acceptance/web/serve.ts
```

On this Windows workspace replace `deno` with `../deno-runtime/deno.exe`, including the build permission. CI verifies Android unit tests, lint, APK assembly, strict dependencies and manifest with JDK 17, Gradle 9.1.0 and SDK 36. Its synthetic APK refuses hosted calls.

## Secure operator preparation

The operator accepts JSON only through stdin. Use an in-memory pipe or an ignored owner-only file, outside served roots. Never put private input in arguments, screenshots, GitHub or chat. Server/worker keys never enter either client.

Every command needs `projectRef`, `supabaseUrl` (the exact development URL) and `publishableKey`. `prepare`, `change-state` and `cleanup` also need `serviceRoleKey`; `dispatch` needs `workerKey` instead. Obtain the existing development admin access and saved `HARBOR_OUTBOX_WORKER_KEY` securely; do not overwrite secrets.

```sh
# Pipe protected JSON into this command; do not paste secrets into arguments.
deno run --frozen --allow-read --allow-write --allow-net=bfvybxkjxilntjgndsrm.supabase.co --allow-run=powershell.exe tests/hosted/notification-acceptance.ts prepare
```

The same invocation supports `change-state`, `dispatch` and `cleanup`. Unix operators omit `--allow-run`; directory/files use 0700/0600. Windows establishes an ACL for the current operator and SYSTEM before creating fixtures. Permission failure stops setup. Hosted commands never run in PR CI.

Ignored `.superpowers/notification-operator/` stores:

- `journal.json`: allocated identifiers and recovery checkpoint.
- `manifest.json`: run/project/parent/family/child/device, expected version and trusted subscription whitelist; no credentials.
- `handoff.json`: parent email/password, public configuration and expiring pairing code; open only locally.
- `change.json`, `dispatch.json`, `cleanup.json`: minimal local evidence. Publish only status, counts, source revision and CI to GitHub.

Preparation creates an email-confirmed disposable parent with a real password session, creates a family through `create-family`, inserts the fixture child with admin access and obtains a real pairing code. Failure attempts rollback and retains recovery evidence. Lost API responses require trusted SQL reconciliation; an empty ID list does not prove cleanup.

Reuse the already created, unassigned parent by including `parent: {runId, userId, email, password}` in protected input, copied locally from `.superpowers/parent-handoff/test-parent.txt`. The runner verifies the fixture marker and refuses existing memberships. This preserves the browser's registered identity. Delete the earlier protected handoff after its account is removed.

## Browser recipient

Open `http://localhost:3000/`. Enter the public publishable key and existing public VAPID key matching the deployed private key. Sign in from the restricted parent handoff, click Enable notifications and grant permission. Backend success proves registration only. Reload requires sign-in again; installation identity and minimal receipts persist locally.

`HARBOR_ALLOWED_ORIGINS` must include exact `http://localhost:3000` alongside the existing Vercel origin, preserving list syntax/values. Secret-name inventory cannot verify its value. Verify matching OPTIONS allow-origin before registration.

Live browser registration against development was verified. No real recipient receipt has been verified.

## Android recipient

In the Firebase project matching the server FCM service account, register Android package `dev.stmedrano.harbor.acceptance`. Download its client `google-services.json` into ignored `tools/notification-acceptance/android/app/google-services.json`. Copy `acceptance.properties.example` to ignored `acceptance.properties`, supplying public development key and matching Firebase project ID. Never put a service-account JSON in the APK project. The build rejects wrong project/package or missing config.

On a workstation with JDK 17 and Android SDK 36:

```sh
cd tools/notification-acceptance/android
./gradlew --no-daemon --dependency-verification=strict testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows uses `gradlew.bat`. Use a physical device or Google Play-enabled emulator. Open the acceptance app, grant permission, enter the expiring pairing code, claim the device, get an FCM token and register it. The child uses real anonymous Auth and a non-exportable Keystore P-256 key. Registration failure retains retry state.

Through connected Supabase SQL, read `private.device_security` joined to `public.devices_public`, filtering by fixture family, child and claimed device. Read only the Auth binding ID, never key/token/session. Include `deviceId`, `childAuthUserId` and `bindingEvidence: {projectRef, familyId, childId, deviceId, authUserId}` in protected `change-state` input. This must be a trusted operator lookup, not a client assertion.

Read active subscription IDs for the exact fixture parent from `private.parent_web_push_subscriptions`. Supply these as `subscriptionIds`; never copy endpoints or keys. This trusted whitelist lets dispatch reject foreign targets.

## State change, dispatch and receipt

`change-state` calls `update-device-state` with harmless `{acceptanceRun: runId}` and the exact expected version, then checkpoints the new version. If its response is lost, inspect trusted desired state before retrying; do not assume failure.

Through connected SQL, select only `id, event_key, transport, target_ref, route_payload, status` from `private.notification_outbox` for exact event `desired-state:<fixture-device-id>:<manifest-version>` and route family/child/device. Pass these as `rows` to `dispatch`. The runner requires one FCM row and exactly whitelisted Web Push rows, rejects incomplete/foreign/duplicate batches and invokes existing `dispatch-outbox`. Outcomes are `sent`, `retry`, `dead_letter`, `no_op` or `unverified`, separately by transport.

Capture receipt baselines before each serialized change. Observe up to two minutes for the matching minimal route in browser worker and Android callback. Record time and foreground/background mode; match route kind/device with the operator event/version. Provider 2xx alone is incomplete. Duplicate hints remain read-only. Re-dispatch sent rows should return `no_op`; a second change uses a new version.

Click Remove registration in the signed-in browser: backend removal precedes local unsubscribe. Confirm filtered active subscriptions are zero. Supply `subscriptionIds: []` for the next change; it should produce FCM only.

## Cleanup and recovery

`cleanup` has separate revoke and finalize phases, described below. Verify actual signed registration/sync denial on Android while its account and binding still exist; the CLI cannot prove that observation itself.

Private-row deletion stays in connected SQL. Delete only outbox rows matching exact route family/child/device and desired-state device event prefix, and subscriptions belonging to exact fixture parent. Verify zero remaining rows; retain audit records. Pass the actual trusted result as `databaseCleanup: {projectRef, runId, familyId, deviceId, parentUserId, remainingOutbox: 0, remainingSubscriptions: 0}` plus `browserUnsubscribed: true`. These operator attestations must come from actual checks. The runner attempts all stages, deletes exact fixture family/Auth identities and removes its handoff only when every stage passes.

Any failed stage yields `complete: false`; local cleanup evidence retains failures and conservative remaining IDs. Retain journal/manifest and reconcile database/Auth state through trusted access. Retries handle absent deleted Auth/family records; uncertain API results or an orphan family require manual exact-run recovery. Keep credentials while recovery remains pending, then delete the earlier protected parent handoff and local receipts after successful cleanup.

Remaining live prerequisites: matching Firebase client config, a Google Play-capable Android recipient and secure worker-key input. Combined receipts, re-dispatch, browser removal and revoked-device denial remain unverified. No production deployment or roadmap completion is claimed.

## Cleanup safety and evidence limits

Cleanup is explicitly two-phase. Before either phase, enumerate **all** devices for the exact fixture family/child through connected SQL, left-joining `private.device_security`. A missing Auth binding is an unresolved recovery condition, not an empty fixture. Supply `fixtureDiscovery: {projectRef, runId, familyId, childId, complete: true, devices: [{deviceId, authUserId}]}`. This one-device toolkit refuses multiple bindings; a genuinely unclaimed fixture uses `devices: []`. The runner checkpoints discovery before any domain deletion. On retry after domain deletion, reuse the protected discovery checkpoint rather than interpreting vanished bindings as never claimed.

Run `cleanup` with `phase: "revoke"` first (also the safe default). It performs fresh MFA revocation and leaves Auth/domain records intact. In Android click Verify signed sync and Verify signed FCM denial; both must display **HTTP 403 DEVICE_REVOKED** while the binding/account still exist. Network failure, 401 or generic failure is not accepted. Preserve these actual results as `revocationEvidence: {deviceId, sync: {status: 403, code: "DEVICE_REVOKED"}, registration: {status: 403, code: "DEVICE_REVOKED"}}`.

After browser removal/local unsubscribe and trusted private-row deletion/zero checks, run `cleanup` with `phase: "finalize"`, the checkpointed discovery, denial evidence, databaseCleanup and browserUnsubscribed inputs. Browser/private-row failure retains domain/Auth identities. Domain deletion failure retains Auth recovery. Every stage reports its failure; complete remains false until all pass.

If a family-creation response was lost, preparation retains the parent and its idempotency mapping. Recover only the exact parent/run via connected SQL:

```sql
select family_id
from private.family_creation_requests
where user_id = 'EXACT_PARENT_UUID'::uuid
  and idempotency_key = 'EXACT_RUN_UUID';
```

Checkpoint the recovered family ID in the protected journal before exact-fixture recovery. Never enumerate and delete all parent memberships. Never delete the parent while this lookup/removal remains uncertain.

After operator cleanup and checkpointing the old binding, use Android's Reset enrollment after cleanup action, then a fresh pairing code. It clears the unusable anonymous session, binding and registration confirmation while preserving receipt evidence. Installing with `adb install -r` alone does not clear state.

Windows applies/verifies owner/SYSTEM-only permissions on each handoff/evidence file before writing, including existing files. Run the native synthetic-file check explicitly on Windows:

```sh
deno test --frozen --allow-read --allow-write --allow-run=powershell.exe tests/notification-acceptance/windows-acl.test.ts
```

Default tests omit that filesystem/process check; it was also run successfully on this Windows host. PowerShell uses its own modules directory to avoid inheriting an incompatible PowerShell 7 module path.

**Correlation limitation:** existing V1 routes contain no desired-state event/version ID. A late duplicate can look identical to a new version's receipt even with serialized sends and timestamp baselines. The classifier therefore admits only the first desired-state event on a fresh fixture device; later version hints remain **unverified**. `no_op` also remains unverified unless separate trusted persisted sent evidence establishes prior provider acceptance. The current classifier does not promote no-op to receipt success. Foreground/background and browser-removal checks requiring later versions cannot be declared complete under this protocol. A supported uniquely correlatable strategy requires a separately approved design change; this toolkit adds no backend payload/schema change silently.
