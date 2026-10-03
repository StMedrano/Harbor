# Harbor Vercel + Supabase Production Architecture Design

> **Status:** Approved written architecture. Implementation requires an approved Subproject 1 plan before execution.
>
> This document supersedes `docs/superpowers/specs/2026-10-03-harbor-supabase-production-architecture-design.md` where this document changes client/frontend architecture. All unchanged Android supervision, safety, privacy, device-security, policy-engine, and Supabase backend requirements remain in force.

## Product intent

Harbor is an Android-first family-safety platform with three first-class clients:

1. Native Parent Android app
2. Native Child Android app
3. Parent Progressive Web App (PWA)

The Parent Android app and Parent PWA provide the same parent-facing product capabilities wherever the browser can safely perform the action. The Child Android app remains native because Harbor requires Android platform capabilities including DevicePolicyManager, Lock Task/Kid Space, VpnService, usage enforcement, background location, foreground services, WorkManager, Android Keystore, and local offline policy enforcement.

Harbor is not hidden-surveillance software. Monitoring, management state, and sensitive permissions remain visible and disclosed to the child.

## Controlling platform decision

Harbor uses a strict platform split:

- **Vercel = frontend hosting and delivery**
- **Supabase = backend application platform and source of truth**
- **Native Android = device enforcement and Android-specific capabilities**

Vercel does not become a second Harbor business-logic backend. Harbor does not route normal application logic through Vercel Functions, Server Actions, or Route Handlers when Supabase RLS or Edge Functions are the intended backend boundary.

Supabase owns:

- Auth and session management
- PostgreSQL relational data
- Row Level Security
- Edge Functions for privileged/security-sensitive logic
- Realtime for authorized parent UX
- Storage
- device enrollment/security state
- desired state and command state
- durable notification outbox
- Android FCM dispatch
- Web Push subscription state and VAPID dispatch
- database migrations and backend secrets

Vercel owns:

- hosting the single Next.js web application
- Parent PWA delivery
- public marketing pages
- account/help/privacy pages
- PWA manifest and service worker delivery
- preview and production web deployments

## Primary technology stack

### Parent Android app

- Kotlin
- Jetpack Compose
- Android 10 / API 29 minimum
- Room for local cache/offline UI state
- Supabase Kotlin client for Auth, RLS-safe Data API access, and Realtime
- Supabase Edge Function calls for privileged mutations
- Google Maps/location UI
- Firebase Cloud Messaging for parent notifications

### Child Android app

- Kotlin
- Jetpack Compose
- Android 10 / API 29 minimum
- Room for authoritative local policy state and usage ledger
- Android Keystore ECDSA P-256 key pair
- separate Supabase Auth device identity/session transport
- Edge Function-only protected device operations
- Firebase Cloud Messaging for wake-up transport
- DevicePolicyManager and Lock Task for Full Supervision
- VpnService and Harbor Browser for web protection
- WorkManager/foreground services where Android requires them

### Parent PWA

- Next.js
- TypeScript
- Vercel hosting
- installable PWA manifest and service worker
- Supabase JavaScript client with publishable key
- Supabase Auth
- RLS-safe direct Data API access
- Supabase Realtime private channels
- Supabase Edge Function calls for privileged actions
- standard Web Push using VAPID

### Supabase backend

- Supabase Auth
- PostgreSQL 17+
- RLS on every exposed Harbor table
- TypeScript/Deno Edge Functions
- Supabase Realtime
- private Supabase Storage buckets
- Supabase CLI migrations
- pgTAP/database security tests
- transactional notification outbox

## Repository target structure

```text
Harbor/
├── apps/
│   ├── android-parent/
│   ├── android-child/
│   └── web/
│       ├── app/
│       ├── components/
│       ├── lib/
│       │   ├── supabase/
│       │   ├── auth/
│       │   ├── realtime/
│       │   └── web-push/
│       ├── public/
│       │   ├── manifest.webmanifest
│       │   └── icons/
│       └── service-worker/
├── supabase/
│   ├── config.toml
│   ├── migrations/
│   ├── seed.sql
│   ├── tests/
│   └── functions/
├── packages/
│   ├── contracts/
│   ├── policy-engine-spec/
│   └── test-fixtures/
├── tests/
│   ├── database/
│   ├── functions/
│   ├── web/
│   └── end-to-end/
└── docs/
    ├── architecture/
    ├── privacy/
    └── superpowers/
```

## Parent PWA scope and parity

The Parent PWA is a first-class Harbor client, not a reduced companion dashboard.

Its V1 parent-facing scope matches the native Parent Android app wherever browser security/capability limits permit:

- dashboard
- family and child views
- device online/battery/heartbeat state
- map/location views
- places and check-ins
- alerts
- Get Help events and acknowledgement
- screen-time rules
- app rules
- schedules
- pause controls
- Kid Space controls
- time-request approval and bonus time
- Lost Mode controls
- family and guardian management
- activity summaries and reports
- account/security/MFA settings
- family export/delete entry points

Browser limitations do not change Harbor's backend capabilities. For example, the PWA may issue an authorized device command even though the Android-specific effect executes only on the Child app.

## Single Vercel web application

Harbor uses one Vercel project for the web surface.

The Next.js application contains:

```text
apps/web/app/
├── (public)/
│   ├── page.tsx
│   ├── help/
│   └── privacy/
├── (auth)/
│   ├── sign-in/
│   ├── sign-up/
│   ├── recover/
│   └── mfa/
└── (parent)/
    ├── dashboard/
    ├── children/
    ├── map/
    ├── alerts/
    ├── controls/
    ├── activity/
    ├── family/
    └── settings/
```

The web project is frontend-only from Harbor's architecture perspective. SSR may be used for public/account presentation where helpful, but Harbor business authorization and privileged operations remain in Supabase.

## Shared parent identity

The Parent Android app and Parent PWA use the same Supabase Auth tenant and the same parent account.

V1 supports:

- email/password registration and sign-in
- email verification
- password recovery
- session refresh/sign-out
- TOTP MFA

Family roles live in application database tables. Harbor never authorizes from editable `user_metadata`.

## Staff/admin identity

Parent and staff/admin accounts use the same Supabase Auth tenant, but staff/admin permissions are separate server-controlled application state.

An authenticated account does not become staff merely because a JWT or editable profile field claims that role.

Admin/support access requires:

- server-controlled staff authorization record
- mandatory MFA/AAL2
- explicit audit logging
- no implicit cross-family visibility

The admin/support web experience may later live inside the same Next.js project, but staff capability is a separate authorization domain from ordinary parent access.

## Parent PWA data-access model

The Parent PWA talks directly to Supabase for operations that are safe under RLS.

The browser may contain:

- Supabase project URL
- Supabase publishable key
- parent access/refresh session managed by the supported Supabase client flow

The browser must never contain:

- service-role/secret Supabase key
- VAPID private key
- Firebase service credentials
- pairing pepper
- child device private keys

### Direct Supabase access

RLS-safe reads may go directly through the Supabase Data API, including parent-visible family, child, device, policy, alert, summary, and other explicitly exposed views/tables.

Routine direct writes are permitted only when a narrow RLS policy safely expresses the authorization rule.

### Edge Function access

Privileged, security-sensitive, destructive, or multi-record operations go through Supabase Edge Functions. Examples include:

- family creation
- membership/guardian changes
- child-device enrollment
- policy version publication
- device commands
- bonus-time approval
- Get Help processing
- device revocation
- Lost Mode destructive actions
- family export
- family deletion
- staff/admin actions

## Step-up MFA / AAL2

Sensitive parent actions require AAL2/MFA regardless of whether the request comes from Android or the PWA.

At minimum, require AAL2 for:

- device revocation/unpairing
- guardian/owner role changes
- family export
- family deletion
- destructive Lost Mode operations
- future remote wipe
- staff/admin elevation or privileged support action

Routine parent actions such as changing bedtime, updating screen-time limits, approving ordinary bonus time, or pausing an app do not require an MFA prompt on every action unless later threat-model work elevates them.

High-risk actions use a 15-minute step-up window. If current Supabase session claims expose a verifiable MFA event timestamp, Edge Functions reject challenges older than 15 minutes. If the platform cannot reliably prove the challenge time from the current session, the client performs a fresh MFA challenge immediately before the operation and the Edge Function validates the strongest current-session AAL2 signal supported by the current Supabase Auth implementation.

AAL2 is enforced server-side in Edge Functions, not only in frontend UI guards.

## Trust boundaries

Harbor has separate trust domains for:

1. Parent users
2. Child devices
3. Staff/admin users
4. backend privileged service operations
5. public/unauthenticated web visitors

A parent session is never reused as child-device authentication. A child device never stores parent credentials.

The Parent Android app and Parent PWA are equivalent parent principals from the backend's point of view. Authorization is based on the same family membership and role state.

## Schemas and exposure

Harbor keeps explicit `public` and `private` schema boundaries.

### `public`

Parent-facing resources intentionally exposed through the Data API, with RLS enabled before client grants.

Initial resource classes include:

- profiles
- families
- family_members
- children
- devices_public
- policy_snapshots
- time_requests
- alerts
- check_ins
- places
- activity_summaries

### `private`

Backend-only/security-sensitive state, including:

- device_security
- device_enrollment_tokens
- device_request_nonces
- device_desired_state
- device_commands
- device_fcm_registrations
- parent_web_push_subscriptions
- notification_outbox
- location_events_raw
- device_health_raw
- usage_events_raw
- audit_events
- retention_jobs
- export_jobs
- staff_authorizations

Private state is not granted to normal browser/Android Data API roles.

## Row Level Security

RLS remains mandatory on every exposed Harbor table.

Core rules:

- active family membership is required for family-scoped reads
- role controls mutation eligibility
- known UUIDs from another family do not bypass authorization
- `TO authenticated` is never the complete authorization rule
- anonymous child-device Auth identities do not gain family-table access merely because they receive the `authenticated` Postgres role
- `UPDATE` policies use both `USING` and `WITH CHECK` where direct updates are allowed
- exposed views use security-invoker behavior or equivalent safe design
- no authorization from `raw_user_meta_data`

Cross-family IDOR/BOLA tests are release-blocking for both Parent Android and Parent PWA paths.

## Child-device identity and proof-of-possession

Enrollment:

1. Parent creates a six-digit one-time pairing code for a specific child.
2. Code lifetime is 10 minutes.
3. Backend stores only a secure keyed digest of the code.
4. Child app creates an ECDSA P-256 key pair in Android Keystore.
5. Child app establishes its separate Supabase Auth identity/session.
6. `device-claim` validates pairing state and caller.
7. Backend binds Supabase Auth user ID plus P-256 public key to the Harbor device.
8. Pairing code is consumed and audited.

Protected device operations require both a valid bound Supabase Auth access token and ECDSA P-256 proof-of-possession. The signed canonical request includes method, operation, device ID, body SHA-256, timestamp, and unique nonce/JTI.

The backend verifies current revocation state on every protected device operation. A copied JWT without the device private key is insufficient. Replayed signed requests are rejected.

## Desired state, commands, and offline enforcement

PostgreSQL is the backend source of truth for desired policy and commands.

Each child device has a monotonically increasing desired-state version, current policy version, pending idempotent commands, acknowledgement state, and last-seen/capability state.

FCM is a wake signal only. It does not carry authoritative policy.

The Child app fetches state through signed `device-sync` calls and persists the last valid policy in Room. Offline or temporarily unreachable devices continue enforcing the last valid policy.

## Parent Realtime architecture

Both parent clients may use Supabase Realtime for low-latency UX. Parent subscriptions use private family-scoped topics such as `family:{family_id}` authorized by active family membership.

Realtime may signal location freshness, device health, policy acknowledgement, time requests, Get Help/check-in state, and command state. It is not the source of truth.

## Notification architecture

Harbor has one backend-driven notification pipeline with multiple transports:

- Android parents: Firebase Cloud Messaging
- Parent PWA: standard Web Push with VAPID

Canonical flow:

`domain event -> PostgreSQL/outbox -> Supabase dispatcher -> FCM and/or Web Push -> parent client -> client fetches authoritative state`

Push payloads remain minimal.

## Web Push / VAPID

The PWA registers a browser PushSubscription only after explicit parent opt-in. The subscription is sent to a Supabase Edge Function and stored privately, associated with the authenticated parent and a logical client installation.

The VAPID public key may be shipped to the PWA. The VAPID private key remains only in Supabase backend secrets. Supabase Edge Functions perform delivery.

Subscriptions support creation/update, endpoint/key rotation, explicit sign-out cleanup when possible, invalid/expired endpoint removal after delivery failure, and multiple browser installations per parent.

## Durable notification outbox

`private.notification_outbox` is the durable source for external delivery intent, recording event identity, target, kind, route/payload reference, transport state, attempt count, next attempt, last error category, and timestamps.

Delivery is at-least-once. Consumers and backend effects are idempotent. Urgent Get Help may attempt immediate delivery only after the durable write succeeds.

## Full-parity parent contracts

Versioned shared contracts define concepts such as Family, FamilyMember, Child, DevicePublicState, PolicySnapshot, TimeRequest, Alert, CommandStatus, ActivitySummary, LocationSummary, and notification route/reference payloads.

The TypeScript PWA consumes these directly or through generated TypeScript types. Android may consume generated/mirrored Kotlin models from the same contract definitions. Shared contracts standardize shape/semantics but do not move authorization out of Supabase.

## Screen-time and policy semantics

Child-local policy enforcement remains authoritative and preserves this precedence:

1. safety/control surfaces remain available
2. parent Pause denies regular use
3. active Kid Space denies use unless app/control is allowed
4. explicit blocked state denies
5. restrictive schedule denies
6. Unlimited bypasses time quota only
7. exhausted daily quota denies
8. exhausted per-app quota denies
9. otherwise allow

Policies are versioned. Child stores the last valid policy in Room and rejects malformed/unsupported replacements rather than silently removing restrictions. Bonus time is explicit parent-approved allowance state.

## Kid Space

Full-Supervision Kid Space remains native Android managed-device behavior using DevicePolicyManager/Lock Task, Harbor managed launcher, parent-approved allowlist, parent PIN local exit, authorized remote changes, preserved safety/control surfaces, and required Android emergency/system access.

The PWA and Parent Android app expose equivalent Kid Space controls through the same backend policy/command APIs.

## Get Help

Get Help remains a deliberate approximately three-second child press-and-hold. The Child app submits a signed urgent event with available location/battery/network/timestamp/device context. Backend records it before push delivery.

Parent Android and Parent PWA may both receive the alert. Harbor never automatically calls emergency services and does not intentionally disable Android-native emergency calling. Get Help and Call Parent remain available during Time's Up and Kid Space.

## Web protection

Child web filtering remains native Android through VpnService/domain policy/SafeSearch where supported/Harbor Browser. Harbor does not perform TLS MITM. The PWA is a parent control/reporting surface only.

## Vercel deployment model

Harbor uses one Vercel project rooted at `apps/web` with PR previews and production deployment from the designated production branch. Frontend environment variables contain only browser-safe values and environment-specific Supabase URL/publishable key.

The web deployment is replaceable without risking Harbor data because authoritative state lives in Supabase.

Harbor never commits Vercel tokens, Supabase secret/service keys, VAPID private keys, Firebase service credentials, or other backend secrets.

## Environment isolation

Development, staging, and production must not accidentally share one backend. Each release environment has an explicit mapping of Vercel environment, Supabase project/environment, Supabase URL, publishable key, allowed redirect/callback origins, Web Push VAPID configuration, and FCM backend credentials.

The current Supabase project `bfvybxkjxilntjgndsrm` is development-only. Production uses a separately isolated Supabase environment/project or explicitly approved equivalent.

## PWA offline behavior

The PWA may cache the application shell and non-sensitive static assets. It must not present stale cached family/device state as authoritative and must not make privileged changes appear committed when backend acceptance did not occur.

Child policy enforcement is independent of PWA connectivity.

## Error handling

Parent Android and Parent PWA share stable backend error categories including:

- `AUTH_REQUIRED`
- `MFA_REQUIRED`
- `FORBIDDEN`
- `VALIDATION_FAILED`
- `DEVICE_REVOKED`
- `DEVICE_OFFLINE`
- `STALE_VERSION`
- `REPLAY_REJECTED`

## Testing strategy

### Database/security

- pgTAP/schema tests
- RLS family isolation
- known-ID cross-family denial
- device Auth denied parent Data API
- staff authorization isolation
- private-table grants

### Edge Functions

- parent Auth validation
- AAL2 enforcement
- family-role authorization
- child proof-of-possession
- replay rejection
- immediate revocation
- command idempotency
- outbox idempotency/retries
- Web Push subscription authorization
- Web Push delivery failure cleanup

### Parent PWA

- sign-up/sign-in/recovery/MFA
- route protection
- full parent navigation
- RLS-safe reads
- privileged Edge Function calls
- Realtime refresh behavior
- manifest/installability
- service-worker registration
- Web Push lifecycle/deep links
- browser offline/stale-state UX

### Parent Android

- matching parent-domain flows against the same Supabase backend
- MFA/privileged-action behavior
- FCM
- Realtime
- Room/cache behavior

### Child Android

- enrollment
- P-256 signing
- sync/offline/reboot recovery
- policy semantics
- DPC/Kid Space
- VpnService
- location/safety flows

### End-to-end acceptance

A single test family is controllable from both Parent Android and Parent PWA against the same Supabase state. Representative flow includes shared sign-in, identical family/child state, one-time child pairing, cross-client policy reflection, exactly-once time approval, Get Help through FCM + Web Push, cross-family denial, AAL2 step-up failure/success, and immediate post-revocation child denial.

## CI and release gates

CI fails on migration failure, DB/RLS test failure, Edge Function test failure, web test/build failure, Android test/build failure, secret scanning failure, contract compatibility failure, or production config mapped to the wrong backend environment.

Vercel preview success alone is never sufficient to merge if backend/security gates fail.

## Privacy and data minimization

Default retention targets remain:

- location history: 30 days
- web activity metadata: 30 days
- app usage: 90 days
- safety events: 90 days
- audit/security events: 365 days

Raw high-frequency data remains private backend state. Push notifications avoid sensitive content when a generic route/reference is enough.

## Features explicitly not moved to Vercel

Family authorization, device enrollment, pairing-code verification, child proof-of-possession, child revocation, desired-state authority, command authority, policy publication, outbox state, FCM dispatch, Web Push VAPID private-key operations, audit authority, export/delete destructive workflows, Android screen-time enforcement, Kid Space/DPC behavior, and child VpnService filtering remain outside Vercel application logic.

## Deferred/non-V1 items

Still deferred unless separately approved:

- iOS clients
- full SMS/call-log ingestion
- default SMS/Phone role
- all-message AI analysis
- driving/crash automation
- automatic emergency-service calling
- production remote wipe until separate high-risk review

## Roadmap impact

The 12-subproject decomposition remains, but Subproject 2 is **Parent Client Foundations** with two separately planned tracks:

- **2A — Parent Android Foundation**
- **2B — Parent PWA Foundation**

Both tracks consume the same Supabase backend, shared contracts, authorization rules, Realtime semantics, notification semantics, and parent-feature acceptance matrix. Each track gets its own Superpowers plan.

Subproject 1 remains **Supabase Platform Foundation**. It creates backend interfaces required by both parent clients, including Web Push subscription storage/Edge Function boundaries and shared contract foundations, but it does not build the full Parent PWA.

## Architecture invariants

1. Vercel is frontend-only for Harbor business architecture.
2. Supabase is the backend source of truth.
3. Parent Android and Parent PWA use the same account/family authorization model.
4. Parent PWA targets full parent feature parity.
5. Privileged actions are server-authorized through Supabase Edge Functions.
6. High-risk parent actions require server-enforced recent AAL2.
7. Child devices never reuse parent credentials.
8. Protected child operations require JWT plus P-256 proof-of-possession.
9. Revocation is checked against current backend state.
10. Replayed signed requests are rejected.
11. Child policy enforcement works offline from the last valid Room state.
12. FCM and Web Push are transports, not sources of truth.
13. VAPID private key exists only in backend secrets.
14. Cross-family isolation is release-blocking.
15. Safety surfaces remain available during restrictions.
16. Harbor does not intentionally disable Android emergency calling.
17. Harbor does not use TLS MITM.
18. Monitoring remains visible and disclosed.

## Implementation-plan impact

The replacement Subproject 1 plan is:

`docs/superpowers/plans/2026-10-03-harbor-vercel-supabase-platform-foundation.md`

It covers Web Push subscription/delivery support, shared parent contract foundations, recent-AAL2 enforcement, environment mapping, and the existing Supabase/device-security foundation. It does not implement the full Next.js PWA.

Native execution remains the user's selected execution method. Implementation begins only after that replacement plan is explicitly approved.