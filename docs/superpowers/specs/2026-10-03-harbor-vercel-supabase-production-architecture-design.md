# Harbor Vercel + Supabase Production Architecture Design

> **Status:** Approved written architecture. Implementation requires explicit approval of the replacement Subproject 1 plan before Native execution.
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

Supabase owns Auth/session management, PostgreSQL, RLS, Edge Functions, Realtime, Storage, device security, desired state/commands, durable notification outbox, FCM dispatch, Web Push/VAPID dispatch, migrations, and backend secrets.

Vercel owns the single Next.js web application, Parent PWA delivery, public/account/help/privacy pages, service-worker/manifest delivery, and preview/production web deployments.

## Primary technology stack

### Parent Android app

- Kotlin
- Jetpack Compose
- Android 10 / API 29 minimum
- Room
- Supabase Kotlin client for Auth/RLS-safe Data API/Realtime
- Supabase Edge Function calls for privileged mutations
- Google Maps/location UI
- FCM

### Child Android app

- Kotlin
- Jetpack Compose
- Android 10 / API 29 minimum
- Room for authoritative local policy state
- Android Keystore ECDSA P-256
- separate Supabase Auth device identity/session
- Edge Function-only protected operations
- FCM wake transport
- DevicePolicyManager + Lock Task
- VpnService + Harbor Browser
- WorkManager/foreground services where required

### Parent PWA

- Next.js
- TypeScript
- Vercel hosting
- installable manifest/service worker
- Supabase JavaScript client with publishable key
- Supabase Auth
- RLS-safe Data API
- private Realtime channels
- Edge Functions for privileged actions
- standard Web Push/VAPID

### Supabase backend

- Supabase Auth
- PostgreSQL 17+
- RLS on every exposed Harbor table
- TypeScript/Deno Edge Functions
- Supabase Realtime
- private Storage
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

The Parent PWA is a first-class Harbor client, not a reduced dashboard. Its V1 scope matches native Parent Android wherever browser security/capability limits permit: dashboard, family/child/device state, maps, places/check-ins, alerts/Get Help acknowledgements, screen-time/app/schedule/pause/Kid Space controls, time-request/bonus approval, Lost Mode controls, family/guardian management, activity reports, security/MFA settings, and export/delete entry points.

Browser limitations do not change backend capabilities; Android-specific effects still execute on the Child app.

## Single Vercel web application

Harbor uses one Vercel project rooted at `apps/web` for public, auth, and parent routes. The web project remains frontend-only from Harbor's business-architecture perspective. SSR may be used for presentation where helpful, but authorization and privileged operations remain in Supabase.

## Shared parent identity

Parent Android and Parent PWA use the same Supabase Auth tenant/account.

V1 supports email/password, email verification, password recovery, session refresh/sign-out, and TOTP MFA. Family roles live in application tables; Harbor never authorizes from editable `user_metadata`.

## Staff/admin identity

Parent and staff/admin accounts use the same Supabase Auth tenant, but staff authorization is separate server-controlled state. Admin/support access requires server-controlled staff authorization, mandatory MFA/AAL2, explicit audit logging, and no implicit cross-family visibility.

## Parent PWA data-access model

The Parent PWA talks directly to Supabase for RLS-safe operations. Browser-safe values may include the project URL, publishable key, and supported parent session state.

The browser must never contain service-role/secret Supabase keys, VAPID private key, Firebase service credentials, pairing pepper, or child private keys.

RLS-safe reads may use the Data API directly. Privileged, destructive, security-sensitive, or multi-record operations use Edge Functions.

## Step-up MFA / AAL2

Sensitive parent actions require AAL2 regardless of Android or PWA origin. At minimum this includes device revocation/unpairing, guardian/owner role changes, family export/delete, destructive Lost Mode operations, future remote wipe, and privileged staff/support actions.

Routine actions such as bedtime/screen-time changes, ordinary bonus approval, or pause do not require repeated MFA unless later threat modeling elevates them.

High-risk actions use a 15-minute step-up window. If a verifiable MFA event timestamp is present in current session claims, Edge Functions reject events older than 15 minutes. If the platform cannot reliably prove challenge time, the client performs a fresh MFA challenge immediately before the operation and the Edge Function validates the strongest current AAL2 signal supported by Supabase Auth.

AAL2 is enforced server-side.

## Trust boundaries

Harbor separates parent users, child devices, staff/admin users, backend privileged service operations, and public/unauthenticated web visitors. Parent sessions are never reused as child-device credentials. Parent Android and PWA are equivalent parent principals from the backend's perspective.

## Schemas and exposure

### `public`

Parent-facing resources intentionally exposed through the Data API with RLS enabled before grants. Initial resource classes include profiles, families, family_members, children, devices_public, policy_snapshots, time_requests, alerts, check_ins, places, and activity_summaries.

### `private`

Backend/security state includes device_security, device_enrollment_tokens, device_request_nonces, device_desired_state, device_commands, device_fcm_registrations, parent_web_push_subscriptions, notification_outbox, raw telemetry tables, audit_events, retention_jobs, export_jobs, and staff_authorizations.

Normal browser/Android client roles receive no direct private-table grants.

## Row Level Security

Every exposed table uses RLS. Active family membership gates family reads; role gates mutation; known cross-family UUIDs do not bypass authorization; `TO authenticated` is never a complete rule; child anonymous Auth identities receive no family access merely because they have the authenticated Postgres role; direct UPDATE policies use both `USING` and `WITH CHECK`; exposed views use safe security-invoker behavior; authorization never uses `raw_user_meta_data`.

Cross-family IDOR/BOLA tests are release-blocking for both parent client paths.

## Child-device identity and proof-of-possession

Enrollment uses a six-digit one-time code for a specific child, 10-minute lifetime, keyed digest storage, an Android Keystore ECDSA P-256 key pair, a separate Supabase Auth device identity, and an atomic `device-claim` that binds Auth subject + public key + device state and consumes/audits the token.

Protected child operations require both the bound Auth token and P-256 proof-of-possession. The canonical signed request includes method, operation, device ID, body SHA-256, timestamp, and nonce/JTI. The backend checks current revocation state on every call. Copied JWTs without the private key fail; replayed signatures fail.

## Desired state, commands, and offline enforcement

PostgreSQL is the backend source of truth. Each child device has monotonically increasing desired-state versioning, current policy version, idempotent pending commands, acknowledgements, and last-seen/capability state.

FCM is wake-only. Child fetches state through signed `device-sync` and keeps the last valid policy in Room for offline enforcement.

## Parent Realtime architecture

Both parent clients use private family-scoped topics like `family:{family_id}` authorized by active family membership. Realtime signals freshness/state changes but never becomes the source of truth.

## Notification architecture

One durable backend notification pipeline fans out to Android FCM and Parent-PWA Web Push. Canonical flow:

`domain event -> PostgreSQL/outbox -> Supabase dispatcher -> FCM and/or Web Push -> parent client -> authoritative Supabase refresh`

Push payloads stay minimal.

## Web Push / VAPID

The PWA registers PushSubscription only after explicit parent opt-in. The subscription is stored privately in Supabase and tied to authenticated parent + logical client installation. The VAPID public key may ship to browser code; the VAPID private key stays only in Supabase secrets. Edge Functions perform Web Push delivery and support rotation, multiple installations, explicit cleanup, and invalid-endpoint cleanup.

## Durable notification outbox

`private.notification_outbox` records durable delivery intent, event identity, target, route/payload reference, transport state, attempt count, retry timing, error class, and timestamps. Delivery is at-least-once and idempotent. Urgent Get Help delivery is attempted only after durable persistence.

## Full-parity parent contracts

Versioned shared contracts standardize Family, FamilyMember, Child, DevicePublicState, PolicySnapshot, TimeRequest, Alert, CommandStatus, ActivitySummary, LocationSummary, notification route/reference payloads, and stable error semantics. Contracts do not replace backend authorization.

## Screen-time and policy semantics

Child-local enforcement remains authoritative and preserves this precedence:

1. safety/control surfaces remain available
2. parent Pause denies regular use
3. active Kid Space denies unless app/control is allowed
4. explicit blocked state denies
5. restrictive schedule denies
6. Unlimited bypasses time quota only
7. exhausted daily quota denies
8. exhausted per-app quota denies
9. otherwise allow

Policies are versioned and the child rejects malformed/unsupported replacements rather than silently removing restrictions. Bonus time is explicit parent-approved state.

## Kid Space

Full-Supervision Kid Space remains native Android DevicePolicyManager/Lock Task behavior with Harbor launcher, parent allowlist, parent PIN local exit, remote authorized changes, preserved safety surfaces, and required Android emergency/system access.

## Get Help

Get Help is a deliberate ~3-second child hold. Signed child context is durably recorded before push. Both parent clients may receive the alert. Harbor does not automatically call emergency services and does not intentionally disable Android emergency calling. Get Help/Call Parent remain available during restrictions.

## Web protection

Child filtering remains native Android via VpnService/domain policy/SafeSearch where supported/Harbor Browser. No TLS MITM. The PWA is parent control/reporting only.

## Vercel deployment model

One Vercel project rooted at `apps/web` supports PR previews and production deployment. Frontend env vars contain only browser-safe, environment-specific values. Replacing a Vercel deployment cannot endanger authoritative Harbor state in Supabase.

## Environment isolation

Development/staging/production must have explicit Vercel↔Supabase mappings covering project/ref, URL, publishable key, redirect origins, VAPID configuration, and FCM credentials. Development ref `bfvybxkjxilntjgndsrm` is development-only. Production uses isolated Supabase infrastructure or an explicitly approved equivalent.

## PWA offline behavior

The PWA may cache shell/static assets but must not present stale sensitive state as authoritative or pretend privileged offline changes succeeded. Child enforcement is independent of PWA connectivity.

## Error handling

Stable backend errors include `AUTH_REQUIRED`, `MFA_REQUIRED`, `FORBIDDEN`, `VALIDATION_FAILED`, `DEVICE_REVOKED`, `DEVICE_OFFLINE`, `STALE_VERSION`, and `REPLAY_REJECTED`.

## Testing strategy

Database/security: pgTAP, RLS isolation, known-ID cross-family denial, device Auth denial, staff isolation, private grants.

Edge Functions: parent Auth, recent AAL2, family role, child proof, replay, revocation, command/outbox idempotency, Web Push authorization, invalid-subscription cleanup.

Parent PWA: auth/MFA, route guards, full navigation, RLS reads, privileged Edge calls, Realtime, manifest/service worker, Web Push lifecycle/deep links, offline/stale-state UX.

Parent Android: matching parent-domain flows, MFA/privileged actions, FCM, Realtime, Room/cache.

Child Android: enrollment, P-256 signing, sync/offline/reboot, policy semantics, DPC/Kid Space, VpnService, location/safety.

End-to-end: one family controlled from both parent clients against the same Supabase state, including exactly-once approval, FCM + Web Push Get Help, cross-family denial, AAL2 step-up, and immediate revocation.

## CI and release gates

CI fails on migrations, DB/RLS tests, Edge Function tests, web build/tests, Android build/tests, secret scanning, contract compatibility, or wrong production-backend mapping. Vercel preview success alone is insufficient.

## Privacy and data minimization

Default retention targets remain 30 days location, 30 days web metadata, 90 days app usage, 90 days safety events, 365 days audit/security events. Raw high-frequency data remains private. Push avoids sensitive content when route/reference is enough.

## Features explicitly not moved to Vercel

Family authorization, device enrollment, pairing verification, child proof-of-possession, revocation, desired-state authority, command authority, policy publication, outbox state, FCM dispatch, VAPID-private-key operations, audit authority, export/delete destructive workflows, Android screen-time enforcement, Kid Space/DPC, and child VpnService filtering stay outside Vercel business logic.

## Deferred/non-V1 items

Still deferred unless separately approved: iOS, full SMS/call-log ingestion, default SMS/Phone role, all-message AI analysis, driving/crash automation, automatic emergency-service calling, and production remote wipe until separate high-risk review.

## Roadmap impact

The 12-subproject decomposition remains. Subproject 2 is now **Parent Client Foundations** with separate tracks:

- **2A — Parent Android Foundation**
- **2B — Parent PWA Foundation**

Both consume the same Supabase backend, contracts, authorization, Realtime, notification semantics, and acceptance matrix. Each gets its own plan.

Subproject 1 remains **Supabase Platform Foundation**. It creates the backend interfaces needed by both parent clients, including Web Push subscription/Edge Function boundaries and shared-contract foundations, but not the full PWA.

## Architecture invariants

1. Vercel is frontend-only for Harbor business architecture.
2. Supabase is the backend source of truth.
3. Parent Android and Parent PWA share account/family authorization.
4. Parent PWA targets full parent feature parity.
5. Privileged actions use Supabase Edge Functions.
6. High-risk actions require server-enforced recent AAL2.
7. Child devices never reuse parent credentials.
8. Protected child operations require JWT + P-256 proof.
9. Revocation checks current backend state.
10. Replays are rejected.
11. Child policy works offline from last valid Room state.
12. FCM/Web Push are transports, not truth.
13. VAPID private key is backend-only.
14. Cross-family isolation is release-blocking.
15. Safety surfaces remain available.
16. Android emergency calling is not intentionally disabled.
17. Harbor does not use TLS MITM.
18. Monitoring remains visible/disclosed.

## Implementation-plan impact

The replacement Subproject 1 plan is:

`docs/superpowers/plans/2026-10-03-harbor-vercel-supabase-platform-foundation.md`

It covers Web Push subscription/delivery support, shared-contract foundations, recent-AAL2 enforcement, environment mapping, and the existing Supabase/device-security foundation. It does not implement the full Next.js PWA.

Native (`superpowers:executing-plans`) remains the selected execution method. Implementation starts only after the replacement plan is explicitly approved.