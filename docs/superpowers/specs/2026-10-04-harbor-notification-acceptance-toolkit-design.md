# Harbor development notification acceptance toolkit

## Status and intent

The user approved the toolkit scope on 2026-10-04. This written spec awaits
review and approval; scope approval does not authorize implementation.

The toolkit provides real browser and Android notification recipients so the
existing Harbor notification backend can be verified end to end. Success means
a real browser receives Web Push and a real Android device receives FCM from
Harbor's durable outbox, with independent transport outcomes and safe cleanup.
It supplements Subproject 1 acceptance. It does not mark the full roadmap or
Parent/Child Client Foundations complete.

Source of truth remains Issue #1 and the approved 2026-10-03 Vercel/Supabase
architecture and platform-foundation plan. Existing hosted Auth, MFA, device
proof, revocation and private Realtime acceptance already passed. Development
currently has no registered notification recipients.

## Chosen approach and alternatives

Use two small development-only recipient clients plus an operator test runner.
They consume existing contracts and endpoints; Supabase retains authorization,
device binding, notification intent, dispatch and private credentials.

Starting the full Parent PWA and Android foundations would add product flows,
navigation and lifecycle work beyond the delivery gate. Provider-console test
sends are useful diagnostics but cannot prove Harbor registration, transactional
outbox intent or dispatcher behavior. Neither is a substitute for this toolkit.

## Components

### Browser recipient

Place a standalone static page and service worker under
`tools/notification-acceptance/web`. Use plain HTML/TypeScript and the existing
pinned Supabase JavaScript client. A minimal build may bundle that dependency;
do not introduce a product UI framework or a second business backend.

The page accepts public development configuration: Supabase URL/publishable key
and VAPID public key. It rejects a Supabase project other than
`bfvybxkjxilntjgndsrm`. Serve on a fixed localhost port with an explicitly allowed
origin. A secure localhost context supports service workers and avoids changing
the existing Vercel project or Kombai preview. Any hosted preview is a separate
deployment decision and must have its exact origin approved/configured first.

The operator signs in using a temporary parent account from the test runner.
Auth sessions stay in memory; no tokens/passwords enter URLs, logs, service
workers or browser storage. An installation UUID may persist in local storage.
The service worker receives only minimal notification routes and stores receipt
evidence in browser-local storage accessible to the page. It shows a generic
notification such as "Harbor test notification received". Notification clicks
open this test page; they do not fetch family data or perform privileged actions.

"Enable notifications" is an explicit user action. Reuse an existing matching
PushSubscription or subscribe with the configured VAPID public key, then call
`register-web-push`. Do not show "Registered" until the backend returns success.
Permission denial, unsupported browser, missing public config, subscription
failure and backend failure have distinct recoverable messages. On reload, the
operator signs in again before any registration/removal call.

"Remove registration" calls `remove-web-push` with the same installation ID and
endpoint before unsubscribing locally. Failed backend removal remains visible
and retryable. Do not silently generate another installation ID during retries.

### Android recipient

Place a debug-only native Kotlin app under
`tools/notification-acceptance/android`, separate from future product apps.
Minimum API level remains 29. A simple native Activity shows setup/registration
status and receipt evidence; full Compose navigation is unnecessary for this
test. The debug application ID is `dev.stmedrano.harbor.acceptance`.

Use Firebase Messaging for a real registration token and message callback.
Firebase client configuration must belong to the development Firebase project
used by the server's configured FCM credentials. The operator supplies that
project's matching Android-app configuration; no Firebase project or production
app is created implicitly. Keep environment configuration out of committed
source and reject missing/mismatched configuration before building/running.

Sign in anonymously to Harbor Auth, generate a non-exportable P-256 key in
Android Keystore, and claim the device using the runner's temporary pairing code.
Persist only the child device session and binding in app-private storage; never
accept parent or server credentials in this app. Request Android notification
permission when the platform requires it, with explicit user interaction.

Register the real token through `register-fcm` using the existing device-proof
headers and canonical operation/body hash/timestamp/nonce format. Android's DER
ECDSA signature must be converted to the 64-byte P1363 representation expected
by the existing backend. Test that conversion and exact canonical bytes before
live calls. JWT refresh must use the child session; every protected request gets
a fresh nonce. No proof or enrollment bypass is allowed for acceptance.

`onNewToken` records a pending registration in app-private storage. Foreground
setup refreshes the child session and submits the current token; status only
advances after server confirmation. Received FCM data-only routes are validated,
recorded locally with receive time, and displayed as generic receipt evidence.
The test covers foreground delivery and a deliberate background receipt check;
it does not promise production offline/reboot/OEM reliability or introduce
WorkManager/policy enforcement. Those belong to Child Android Foundation.

### Operator runner

Extend/reuse the hosted acceptance fixture conventions under `tests/hosted`.
Guard every run to the designated development project. Create only temporary
parent/family/child fixtures, use admin credentials only for fixture setup and
cleanup, and use real parent/device credentials for product operations.

Provide temporary parent sign-in details and a short-lived pairing code only
through an operator-controlled local handoff. Do not print credentials into
captured agent/CI output or write them to tracked files. Any temporary handoff
file is ignored, restricted locally, and deleted during cleanup.

After both recipients register, call `update-device-state` with a harmless test
state change and expected version. This uses the existing transactional trigger
to create FCM and Web Push outbox rows. Do not fabricate sent rows, inject push
content directly into the worker, or add a public test-send endpoint.

Dispatch only the exact fixture outbox IDs through `dispatch-outbox`, using the
existing worker key from a secure operator-provided local input. The key stays
on the server/operator side; browser and Android never receive it. Inspect
private outbox/audit state through trusted operator access. Existing Supabase
database tools can provide this evidence without exposing a privileged API to
the clients. No new production secrets or schema are needed by this design.

## Configuration and external prerequisites

Required public inputs are the development Supabase URL/publishable key, the
existing VAPID public key, and matching Firebase Android client configuration.
Required private operator inputs are fixture-admin access and the existing
outbox worker key. Readiness checks report missing input names, never values.

The operator must provide a physical Android device or Google Play-enabled
emulator with a usable Firebase connection, install the debug APK, and grant
notification permissions. The browser user must grant notification permission.
If these prerequisites are unavailable, report the exact blocked acceptance
step instead of substituting mocked receipt or unrelated credentials.

## Delivery acceptance and evidence

1. Browser registers a real subscription against the temporary parent identity.
2. Claimed Android device registers a real FCM token with valid P-256 proof.
3. One desired-state change creates independent transport rows transactionally.
4. The worker reports provider acceptance separately for each transport.
5. Browser service worker and Android callback each record the matching route.
   Correlate the fixture device ID and route kind with the operator's outbox
   event identity/version; the push route need not contain the desired-state
   version. Record receive time and foreground/background mode.
6. Re-dispatch of a sent row is a no-op. A second domain change uses a new version.
7. Removing the browser registration prevents new Web Push intent for that
   installation while the next FCM event can still reach the Android recipient.
8. Revoke the device with fresh parent MFA, verify signed registration/sync is
   denied, and clean up all fixture registrations, outbox rows and domain/Auth
   fixtures. Retain minimal audit evidence without credentials or payload content.

Provider acceptance and recipient receipt are separate results. A provider 2xx
without a receipt remains incomplete. Observe for up to two minutes per send;
absence at that limit is recorded as unverified receipt and investigated, rather
than proof of a provider's permanent failure. Permanent and
retryable errors use existing classifications. At-least-once transport delivery
is expected; duplicate hints must not imply duplicate authoritative mutations.

## Verification and delivery discipline

Write failing tests for subscription register/remove failure handling, receipt
validation, configuration guards, canonical proof bytes/signature conversion,
and token rotation before implementation. Use established fixtures/contracts
and focused tests; do not mirror every UI line with a test.

CI builds/checks both clients using non-secret fixture configuration and keeps
all existing required foundation gates. Hosted tests and real provider sends
remain opt-in; PR CI never receives production credentials. A debug APK build
alone is not device-delivery evidence. Use a focused branch/PR based on the
reviewed backend foundation, preserve main, and update Issue #1 with exact
source revision, CI run, provider outcomes and observed receipts.

Implementation order is browser recipient, Android recipient, then combined
dispatch/cleanup acceptance. Each is a small coherent slice with independent
checks. A written implementation plan and explicit execution selection are
required after this spec is approved.

## Scope exclusions

No full Parent PWA/Android product screens, Child policy enforcement, billing,
location, Get Help product workflow, production scheduling/deployment, new
authorization paths, public staff/admin surfaces, or Kombai design changes.
Production Supabase remains unassigned and must never use development config.
This toolkit does not close the other remaining foundation gates, including
email confirmation/recovery callbacks, live stale-MFA timing and relevant
performance-advisor assessment.
