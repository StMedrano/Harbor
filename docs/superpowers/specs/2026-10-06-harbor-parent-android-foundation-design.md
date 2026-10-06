# Harbor Parent Android Foundation Design — Subproject 2A

**Status:** Written specification for user review. The user selected Parent Android,
accepted continuation of the proposed foundation scope, and replaced Kombai as
visual source with Harbor's Vercel frontend. This document is not yet approved
for implementation or implementation planning.

**Authority:** The approved Vercel/Supabase production architecture and Issue #1.
Backend foundation baseline: `c7e19cfbe29f62ba8c2c9369cef570384a60dc09`, all required
CI gates passed in run `37507742050`. Main and production remain unchanged.

## Outcome and approach

Deliver the first real parent client: a parent can sign in, create a family and
child, issue a pairing code, see the enrolled child's devices, receive a parent
notification, and revoke a device after fresh MFA. Android and the later PWA
share the same account, families, backend authorization and reference contracts.

The selected approach is one complete foundation lifecycle implemented in small
TDD milestones. An auth/dashboard shell alone is simpler initially but leaves
pairing and notification integration unproven. A full parent product would add
policy, maps and activity features from later roadmap subprojects. This design
keeps the complete foundation path and defers those later product capabilities.

## Architecture and boundaries

- New native app under `apps/parent-android`, package
  `dev.stmedrano.harbor.parent`; Kotlin, Jetpack Compose, minimum API 29, Room,
  Supabase Kotlin Auth/Data API/Realtime, and Firebase Messaging.
- Use the existing backend and contracts. RLS-safe reads use the Data API;
  privileged writes use parent-authorized Edge Functions.
- Supabase remains authoritative. Room stores a clearly stale last-read view,
  scoped to the authenticated subject and family; it is not policy authority.
- Keep screen state, parent Auth/session ownership, family repository, device
  enrollment actions and notification lifecycle in small focused components.
  Reuse fitting transport/error/testing patterns before adding abstractions.
- The acceptance APK remains an independent development tool. Its anonymous
  child identity, Keystore signing and device registration APIs must not be
  copied into parent Auth or passed parent credentials.
- Only public development configuration enters the APK. No service-role keys,
  worker keys, Firebase service-account keys, pairing peppers or child keys.
- Pin and lock reviewed dependencies in the implementation plan; use strict
  dependency verification. No dependency installation is authorized by this spec.

## Native visual design

Source: the existing Harbor Vercel frontend, represented by repository
`index.html` and its decoded `frontend-bundle` assets. The public frontend was
reachable with HTTP 200 during design preparation. Inspect the rendered source
again during implementation for visual verification; do not modify or redeploy it.
Kombai designs are not this app's design source.

Translate these existing tokens into native Compose theme roles:

| Role | Light | Dark |
| --- | --- | --- |
| Background | `#ece6da` | `#0f1514` |
| Surface | `#fbf8f2` | `#171f1e` |
| Secondary surface | `#f3eee4` | `#1f2927` |
| Divider | `#e2dacb` | `#2b3633` |
| Primary text | `#1c2422` | `#ecebe5` |
| Secondary text | `#56605c` | `#aab3ae` |
| Brand | `#163a3a` | `#cfe5dc` |
| Accent | `#1f6b5c` | `#6cc2a8` |
| Danger | `#b4382c` | `#f0796c` |

Reuse the source's 8/14/22 corner hierarchy, restrained shadows, rounded child
selector, cards, readable row hierarchy and contextual bottom sheets. Foundation
navigation exposes Family, Security and Settings only. It does not show demo
maps, screen-time controls, fabricated alerts or unsupported action buttons.
Use system sans initially with the source's typography hierarchy; bundle General
Sans only after its font license and assets have been verified. Support system
light/dark preference, TalkBack, large text, scrolling, native back behavior and
minimum 48dp interactive targets. Verify contrast and supported font scales rather
than assume all web colors are suitable for every native text size.

## Parent Auth and session lifecycle

Support email/password signup, email confirmation, sign-in, password recovery,
refresh, current-device sign-out and TOTP enrollment/challenge. Render explicit
loading, confirmed, rejected and retryable failure states; never report success
from a local button press alone.

Use the SDK's supported one-time Auth exchange and exact callback handling. The
native callback is `harbor-parent://auth/callback`; allowlist only this exact
callback in development when the implementation and live acceptance are ready.
Confirmation/recovery requests retain their PKCE transaction context across
process recreation. Reject unsolicited, wrong-flow, expired or reused callbacks;
check the server-verified parent subject before accepting account state or a
password update. An ordinary signed-in session must not unlock recovery.
The plan must verify SDK support for these invariants before selecting versions;
if a supported flow cannot enforce them, stop and revise the spec, rather than
fallback to trusting URL claims or inventing token verification.

Persist session credentials and PKCE verifier in app-private encrypted storage
whose key is protected by Android Keystore; disable backup of credentials.
Do not put credentials, email links or passwords in Room, logs, crash reports,
notifications or shared files. Handle invalid/expired refresh by returning to
sign-in. Cancel authenticated work and clear per-user caches on sign-out or
account switch. Keep refresh serialized to avoid conflicting session updates.

## Family, child and pairing lifecycle

Use `create-family` with its existing idempotency contract. Read profiles,
families, active membership, children and public device metadata through the
existing RLS-safe tables. Bind selected family/child to current server-returned
membership, not editable metadata or a saved navigation identifier.

Add the necessary `create-child` Edge Function and an atomic server helper:

- Request: `familyId`, trimmed `displayName` and `idempotencyKey`.
- Authenticate a non-anonymous parent; require active owner/parent membership in
  the requested family. Recheck membership inside the creation transaction with a lock that serializes membership removal. Direct client INSERT/UPDATE/DELETE grants stay denied.
- Validate a nonblank display name of at most 100 characters, rejecting control
  characters. Never derive authorization from names or user metadata.
- Create the child and record request identity in one transaction. Server request
  uniqueness is `(user_id, family_id, idempotency_key)` with a normalized payload
  fingerprint. Retries return the same `ChildV1`; conflicting payload reuse
  returns additive `IDEMPOTENCY_CONFLICT` / HTTP 409.
- Store request identity privately with family/user/child foreign keys and cleanup
  cascades. Add only indexes required by its lookup/foreign-key paths. Audit the
  creation without names or sensitive payloads in audit metadata.
- Retain the operation key for retries/process recreation; disable duplicate UI
  submissions but rely on server idempotency for correctness.

Issue codes through existing `create-device-pairing`. Show the six-digit code,
expiry and renewal state; never expose stored digests or enroll a child using the
parent session. Pairing success comes from an authorized device read/refresh
showing the actual enrolled device, not from code generation. The real Child
Android product remains Subproject 3; the existing child acceptance recipient
may exercise this foundation pairing seam during development acceptance.

## Device read model, Realtime and cache

Show authorized child/device identity, supervision/status, and last-seen fields
that actually exist in public device metadata. Do not present desired state as
confirmed applied policy or fabricate data absent from backend read interfaces.
Policy configuration and its richer read model belong to later subprojects.

Join existing private family channels using the parent's current session.
Realtime and FCM are invalidation hints: perform authorized reads and update
Room after successful responses. Refresh on app foreground and reconnect as
well, so missed notifications do not prevent eventual state refresh. Disconnect
old channels on family/account/session changes. Treat access removal as a signal
to clear that family's visible cache. Offline cached views show their last
successful refresh time; pairing, creation and revocation require connectivity.
Do not queue destructive revocations or reinterpret a cached role as permission.

## Parent FCM lifecycle and backend extension

Parent Android needs its own parent-authenticated registration. Existing
`register-fcm` requires bound child Auth plus device proof and is not reusable as
parent registration. Add `register-parent-fcm` and `remove-parent-fcm` with shared
request contracts for a random per-installation identifier and current Firebase
token. Derive owner identity exclusively from verified parent Auth.

Add private `parent_fcm_registrations`: immutable registration ID, owner subject,
client installation ID, current Auth session binding, token/token hash, active
state and lifecycle timestamps. Uniqueness per `(user_id, client_installation_id)`
supports multiple parent installations. One token may have only one active owner;
rotation/account switch deactivates its old binding atomically. Token values
are server-only, excluded from responses/audits/logs; no client table grants. Registration returns only its non-secret immutable registration ID and confirmed active state, which the app stores scoped to the current user.
Require a current owner Auth session for registration and revalidate that session
binding when resolving a queued recipient. Session refresh keeps the session
binding; a new sign-in replaces it through authenticated registration.

Extend existing outbox fanout/target resolution additively for parent FCM
registration IDs. Preserve child FCM and browser Web Push targets and event
correlation. Resolve active family membership, immutable registration owner,
current session and active token at dispatch time. Removed family members,
remotely invalidated Auth sessions and inactive registrations must not receive future sends. Offline local sign-out cannot immediately prove remote invalidation; do not claim provider delivery has stopped until that state is confirmed.
Use a parent-specific registration target, not a child device target or guessed
recipient user ID. A permanently invalid token disables that registration;
retryable failures and other transports retain existing durable behavior.

Ask notification permission in context. Register only after successful parent
sign-in and explicit opt-in; confirm backend success before showing enabled.
Handle token rotation, failed registration/removal and process recreation with
bounded retry of the same installation operation. Sign-out removes registration
while credentials are usable, deletes the local Firebase token, invalidates the
current Auth session and clears local identity. Offline sign-out still clears
local secrets/views and cancels old-account retries; session-bound resolution and generic
route-only data messages provide defense while remote cleanup is pending. A failed offline token deletion remains unconfirmed; preserve no old account credentials merely to retry cleanup.
On a new account, fetch a fresh token and establish a new verified binding.

Use data-only parent FCM messages so Android cannot automatically display a stale-account notification. The envelope carries the existing minimal route and the immutable parent registration ID; the receiver matches that ID to its current user-scoped registration before rendering a notification. Keep existing route/event contracts unchanged. Push contains existing minimal event/reference routes, never child names,
locations, message content or Auth tokens. The Android receiver ignores hints
for a different signed-in account/family and uses generic notification text.
A notification tap opens the referenced authorized child/device after a fresh
read; missing/revoked access shows a safe unavailable state. Local receipt hints
are development evidence, not delivery or backend-state authority.

A matching Firebase Android client configuration for the new parent package is
required for live builds, using the existing development FCM project. CI uses an
explicit non-live fixture configuration. Do not call a fixture APK live-ready or
ship backend credentials to compensate for missing Firebase client configuration.

## MFA-protected revocation

TOTP setup/challenge uses Supabase Auth. For revocation, confirm the selected
server-authorized device, obtain recent AAL2, then call existing `revoke-device`.
The server remains responsible for active family role and the 900-second window.
On `MFA_REQUIRED`, show step-up and require the parent's deliberate retry after
challenge; do not automatically repeat destructive actions on re-authentication.
Show revocation complete only after backend acceptance and refreshed metadata.
Handle other authorization, network and validation failures distinctly. Never
collect a child's private key or impersonate the child to revoke it.

## Verification and completion contract

TDD milestones cover Auth/session/callback handling, theme/navigation, family and
child creation, pairing/read/cache, Realtime/parent FCM, MFA and full acceptance.
The written implementation plan defines exact tasks/files and pinned dependencies.

Required proof before Subproject 2A completion:

- JVM/Compose tests for loading/errors, stale/empty/denied states, navigation,
  account-switch cache isolation, refresh serialization and callback rejection, including data-only messages for old registration IDs and offline sign-out.
- Database/function tests for child-creation idempotency/conflicts, parent-only
  authorization, known-ID cross-family denial, private FCM grants, multiple
  installations, rotation/removal and current-session recipient resolution.
- Real persisted outbox tests preserve child FCM/Web Push fanout and correlation;
  invalid parent FCM does not block other recipients, and redispatch is idempotent.
- Fresh/stale MFA denial, revocation acceptance and subsequent child denial remain
  server-enforced. Reuse current foundation results unless new changes touch them.
- Clean backend/contracts CI plus Android unit/instrumented tests, lint, assembly,
  dependency verification, public-only APK configuration and environment guards.
- Real development APK acceptance on the user's Pixel 9a: parent login, family/
  child creation, pairing to a separate child identity, authorized refresh,
  parent FCM registration and actual foreground/background receipt, fresh-MFA
  revocation, current-device sign-out and exact disposable fixture cleanup.
- Separate provider acceptance from observed receipt; verify route/event identity,
  preserve audit records and report any missing physical-device/credential proof.
- Native visual inspection against the Vercel reference in light/dark and large
  text; verify supported API 29 minimum behavior and accessibility.

No production backend or deployment is created by this foundation. Parent PWA,
Child Android enforcement, maps/location, policies/usage/Kid Space, monitoring,
billing, export/delete workflows and commercial release remain later roadmap work.

## Approval and handoff

User review of this written spec precedes implementation planning. The plan must
then receive its own approval and execution-method selection before scaffolding,
product dependencies, migrations or product code. Native inline/TDD execution is
the recommended continuation of the existing workflow. Preserve the existing
foundation PR stack, main, production deployments and unrelated files.