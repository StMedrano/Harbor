# Harbor Supabase Production Architecture Design

> **Status:** Approved backend direction, written-spec review required before implementation planning.
>
> This document supersedes the ASP.NET Core/Azure backend portions of `2026-10-02-harbor-production-architecture-design.md`. Product behavior, Android architecture, supervision rules, Kid Space, Get Help, offline policy enforcement, privacy boundaries, and the 12-subproject decomposition remain in force unless explicitly changed here.

## Product intent

Harbor is an Android-first family-safety platform for parents and children. It combines family location, screen-time enforcement, app policy, schedules, Kid Space lockdown, web protection, device recovery, activity reporting, check-ins, and child-to-parent Get Help alerts while keeping supervision visible to the child.

Harbor is not hidden-surveillance software. Monitoring, device-management state, and sensitive permissions must be visible and disclosed in the product experience.

## Superseding architecture decision

Harbor will use Supabase as the production backend platform instead of the earlier ASP.NET Core/.NET 10 and Azure application tier.

Supabase owns:

- Parent authentication and session management through Supabase Auth
- PostgreSQL relational data
- Row Level Security for parent-facing Data API access
- Edge Functions for privileged or security-sensitive application logic
- Realtime for authorized parent-session updates
- Storage for private exports/support artifacts and later approved media use
- Database migrations through the Supabase CLI
- Scheduled database/backend maintenance through Supabase Cron where appropriate
- Backend secrets through Supabase project/Edge Function secrets

Firebase Cloud Messaging remains the Android push and wake-up transport. Android devices still enforce the last valid policy locally and do not depend on realtime connectivity to remain restricted.

The currently connected development Supabase project is `bfvybxkjxilntjgndsrm` in `us-east-1`. It is an initial development target only. Production release must use separate environment/project boundaries or an approved equivalent isolation strategy.

## Primary technology stack

### Parent Android app

- Kotlin
- Jetpack Compose
- Android 10 / API 29 minimum
- Room for cache/offline UI state
- Supabase Kotlin client for Auth/Data API/Realtime where appropriate
- Edge Function calls for privileged mutations
- Google Maps/location APIs
- Firebase Cloud Messaging for parent notifications where useful

### Child Android app

- Kotlin
- Jetpack Compose
- Android 10 / API 29 minimum
- Room for authoritative local policy state and usage ledger
- Android Keystore for device ECDSA P-256 key pair
- Supabase Auth anonymous/device-bound session transport
- Edge Function-only access for device enrollment, sync, telemetry, safety events, and commands
- Firebase Cloud Messaging for wake-up/notification transport
- DevicePolicyManager/Lock Task for Full Supervision
- VpnService + Harbor Browser for web protection
- WorkManager/foreground services where Android requires them

### Supabase backend

- Supabase Auth
- PostgreSQL 17+
- Row Level Security on every exposed table
- Edge Functions in TypeScript/Deno
- Supabase Realtime with private family-scoped channels
- Supabase Storage private buckets
- Supabase CLI migrations
- Database triggers/functions only when they have a narrowly documented purpose
- Transactional outbox table plus Edge Function dispatcher/retry path for durable push delivery

## Repository target structure

```text
Harbor/
├── apps/
│   ├── android-parent/
│   ├── android-child/
│   └── admin-web/
├── supabase/
│   ├── config.toml
│   ├── migrations/
│   ├── seed.sql
│   └── functions/
│       ├── _shared/
│       ├── create-family/
│       ├── device-claim/
│       ├── device-sync/
│       ├── submit-telemetry/
│       ├── update-policy/
│       ├── approve-time-request/
│       ├── get-help/
│       ├── device-command/
│       ├── dispatch-outbox/
│       ├── export-family/
│       └── delete-family/
├── packages/
│   ├── contracts/
│   ├── policy-engine-spec/
│   └── test-fixtures/
├── docs/
│   ├── architecture/
│   ├── privacy/
│   └── superpowers/
└── tests/
    ├── database/
    ├── functions/
    └── end-to-end/
```

No .NET service layer is required by this architecture.

## Trust boundaries

Harbor has separate trust domains for:

1. Parent users
2. Child devices
3. Administrative/support operators
4. Backend privileged service operations

A parent user session is never reused as child-device authentication. A child device never stores parent credentials.

The Supabase publishable key may exist in the Android clients. Secret/service-role credentials must never be embedded in either Android app, the frontend prototype, GitHub source, logs, analytics, or crash reports.

## Parent authentication

Parent accounts use Supabase Auth.

V1 supports:

- Email/password registration and sign-in
- Email verification
- Password recovery
- Session refresh and sign-out
- TOTP MFA enrollment/challenge support

Authorization never relies on editable `user_metadata`. Family roles are stored in application tables; any JWT-carried authorization claim must originate from server-controlled application metadata and must not be the only family authorization check.

Sensitive parent operations may require AAL2 when the parent has MFA enrolled. At minimum, device revocation, family deletion/export, parent-role changes, destructive Lost Mode actions, and support/admin elevation must be designed for step-up verification.

Deleting a parent account is not considered immediate token revocation by itself. Sensitive operations continue to validate current authorization and family state in the database.

## Parent-facing database access

Parent Android clients may use the Supabase Data API directly for read-heavy, non-privileged family views when RLS fully expresses the authorization rule.

Examples include:

- Parent profile
- Families in which `auth.uid()` is an active member
- Children belonging to those families
- Parent-visible device status
- Current policy snapshots
- Activity summaries
- Parent-visible alerts and acknowledgements

Security-sensitive or multi-record mutations go through Edge Functions instead of direct table writes. This includes family creation, invites/membership changes, device enrollment, device commands, policy version changes, bonus-time approval, Get Help processing, export/delete workflows, and admin/support actions.

## Schemas and exposure

Harbor uses explicit schema boundaries.

### `public`

Contains only parent-facing resources intentionally available through the Data API. Every table has RLS enabled before client access is granted.

Initial public domain tables include:

- `profiles`
- `families`
- `family_members`
- `children`
- `devices_public`
- `policy_snapshots`
- `time_requests`
- `alerts`
- `check_ins`
- `places`
- `activity_summaries`

`devices_public` is a parent-safe projection/table and must not contain refresh/session material, FCM tokens, nonce data, or security-internal fields.

### `private`

Contains backend-only state and is not granted to `anon` or `authenticated` Data API roles.

Initial private domain tables include:

- `device_security`
- `device_enrollment_tokens`
- `device_request_nonces`
- `device_desired_state`
- `device_commands`
- `device_fcm_registrations`
- `notification_outbox`
- `location_events_raw`
- `device_health_raw`
- `usage_events_raw`
- `audit_events`
- `retention_jobs`
- `export_jobs`

If current Supabase Data API settings do not automatically expose newly created tables, Harbor explicitly grants only the required privileges. RLS and SQL grants are treated as separate controls.

## Row Level Security rules

RLS is mandatory on every exposed table.

Core rules:

- A parent can read a family only when an active `family_members` row links `auth.uid()` to that family.
- A parent can read child/device/policy/activity/alert rows only through active membership in the owning family.
- Membership role controls mutation eligibility; membership presence alone does not imply owner/admin privileges.
- `TO authenticated` is never used as the complete authorization rule.
- `UPDATE` policies include both `USING` and `WITH CHECK` where direct updates are allowed.
- Views exposed to clients use `security_invoker = true` or are otherwise protected from bypassing RLS.
- Harbor does not use `raw_user_meta_data` for authorization.
- `SECURITY DEFINER` functions are avoided unless the requirement cannot be met safely otherwise. Any required definer function lives outside exposed schemas, has a fixed safe `search_path`, performs its own authorization checks, and has tightly scoped execute grants.

Cross-family IDOR/BOLA tests are release-blocking tests for every public resource class.

## Family and child model

A family contains one or more parent memberships and child profiles.

`family_members` records:

- `family_id`
- `user_id`
- role (`owner`, `parent`, later narrowly scoped roles if approved)
- status
- created/updated timestamps

Children are owned by a family, never directly by a single parent account. This allows multiple authorized parents without duplicating child/device ownership.

Family creation is an Edge Function transaction that creates the family, creator membership, audit event, and initial defaults atomically.

## Child-device identity

Child devices do not use parent credentials.

Each Harbor Child installation obtains a separate Supabase Auth session using an anonymous/device identity flow. That Auth user is only a transport identity and does not itself grant family data access.

During enrollment:

1. Parent creates a six-digit one-time pairing code for one child.
2. The code expires after 10 minutes and is stored only as a secure hash in backend state.
3. Harbor Child creates an ECDSA P-256 key pair in Android Keystore.
4. Harbor Child establishes its separate Supabase Auth session.
5. `device-claim` receives the pairing code, device metadata, and public key.
6. The Edge Function validates the code, caller identity, expiry, single-use status, and enrollment constraints.
7. It binds the Supabase Auth user ID and public key to the new device.
8. It marks the pairing token consumed and writes an audit event in the same logical transaction.

An anonymous/device Auth JWT alone is not sufficient to read Harbor family data through the Data API. Device principals use Edge Functions for protected operations.

## Device proof-of-possession

Sensitive child-device Edge Function calls require both:

- A valid Supabase Auth access token for the bound device identity
- ECDSA P-256 proof-of-possession from the enrolled Android Keystore key

The signed canonical request includes at least:

- HTTP method
- function/operation identifier
- device ID
- request body SHA-256
- timestamp
- unique request nonce/JTI

The backend verifies:

- Supabase JWT is valid
- JWT subject is bound to the requested device
- device is not revoked
- signature matches the stored public key
- timestamp is inside the accepted clock-skew window
- nonce/JTI has not been used before

`private.device_request_nonces` enforces replay protection and is periodically pruned. High-volume telemetry is batched so proof-of-possession does not create unnecessary per-event overhead.

## Immediate device revocation

Supabase access-token expiry is not treated as sufficient revocation.

Every protected child-device Edge Function checks the current `device_security.revoked_at` state before performing the operation. Revoked devices receive a stable authorization error immediately even if a previously issued Supabase access token has not expired.

The parent revocation flow also invalidates FCM registration and desired-state access and creates an immutable audit event.

## Device desired state and commands

PostgreSQL is the source of truth for current desired policy and pending commands.

Each device has:

- a monotonically increasing desired-state version
- current policy snapshot/version
- pending idempotent command records
- last acknowledged desired-state version
- last seen timestamp/capabilities

FCM contains only a minimal wake-up signal such as `sync_required`; it does not carry authoritative policy or sensitive child data.

When awakened, Harbor Child calls `device-sync`, authenticates with JWT + proof-of-possession, and receives the latest desired state plus pending commands.

Commands carry stable IDs and idempotency keys. The child persists applied command IDs locally so duplicate delivery cannot duplicate effects.

Offline devices continue enforcing the last valid locally stored policy and reconcile when connectivity returns.

## Realtime architecture

Realtime is primarily for parent experience, not child policy authority.

Parent clients subscribe only to private family-scoped channels such as `family:{family_id}`. Channel authorization is based on active family membership.

Realtime events include non-sensitive change notifications for:

- location freshness/status
- device health
- policy acknowledgement
- time requests
- Get Help/check-in alerts
- command state

A Realtime event is a prompt to refresh authoritative data when necessary. It is not the source of truth.

Child devices do not depend on Realtime for enforcement. FCM + authenticated sync is the child-device control path.

## Edge Function boundaries

Initial function responsibilities are:

### `create-family`
Creates a family and creator membership atomically and emits audit/realtime state.

### `device-claim`
Consumes the short-lived pairing code, binds the device Auth identity and ECDSA public key, creates public/private device state, and audits enrollment.

### `device-sync`
Authenticates device JWT + proof-of-possession, checks revocation, returns desired state/pending commands, accepts acknowledgements, and updates safe health metadata.

### `submit-telemetry`
Accepts batched signed child telemetry, validates capability and size limits, stores raw data privately, updates parent-safe summaries, and emits family realtime changes.

### `update-policy`
Authorizes parent role, validates policy schema, creates the next version, updates desired state, writes audit/outbox rows, and triggers child wake-up.

### `approve-time-request`
Atomically approves a pending child request exactly once and creates explicit bonus-time allowance state.

### `get-help`
Accepts signed child Get Help event, records location/device context available at that moment, creates urgent parent alert, writes outbox entries, and attempts immediate push dispatch. It never calls emergency services automatically.

### `device-command`
Authorizes parent command issuance, validates supervision capability, creates idempotent one-shot command, and schedules device wake-up.

### `dispatch-outbox`
Claims pending notification rows safely, sends FCM/realtime work, records attempts/results, and supports retry/dead-letter behavior.

### `export-family`
Creates an authorized family export job and stores the resulting artifact in a private Storage bucket.

### `delete-family`
Runs the approved destructive family deletion/anonymization workflow with step-up authorization, audit trail, and retention constraints.

## Durable push and outbox behavior

Domain changes and push intent are recorded durably before external delivery is considered successful.

`private.notification_outbox` records:

- event ID
- family/device target
- notification kind
- payload reference/minimal payload
- status
- attempt count
- next attempt time
- last error category
- created/sent timestamps

For low-latency events such as Get Help, the initiating Edge Function may attempt delivery immediately after the durable event/outbox write. A scheduled/retry dispatcher processes unsent records and recovers transient failures.

Delivery is at-least-once. FCM consumers and backend effects are idempotent.

## Firebase Cloud Messaging

Firebase service credentials are stored only as Supabase Edge Function/project secrets.

FCM registration tokens are private backend state. Parent clients cannot read child FCM tokens. Child tokens are rotated/updated through an authenticated signed device operation.

Push payloads avoid sensitive child content. The device fetches protected details after authenticating.

## Screen-time and policy engine

The policy engine remains native/local on the child device so restrictions work offline.

Policy inputs include:

1. Pause/Lost state
2. Active schedule restrictions
3. Kid Space allowlist when Kid Space is active
4. Explicit app/category blocks
5. Daily family limit
6. Per-app limit
7. Unlimited exception where policy allows it
8. Allow

Policies are versioned and validated before becoming desired state. The child stores the last valid policy in Room and rejects malformed or unsupported versions rather than silently removing restrictions.

Bonus time is represented as an explicit parent-approved allowance record. The child cannot create or approve its own bonus allowance.

## Kid Space

Kid Space behavior is unchanged by the backend migration.

On Full-Supervision devices it is true managed-device kiosk/Lock Task behavior:

- parent controlled
- Harbor managed launcher
- only parent-allowed apps shown
- allowed apps still evaluated against all policy rules
- child cannot disable it
- local exit requires parent PIN
- remote exit comes from authorized parent desired-state change
- emergency/system access required by Android is not intentionally blocked

Standard-Supervision devices must never claim this stronger capability.

## Get Help

Get Help remains a deliberate child-to-parent escalation.

- approximately three-second press-and-hold
- signed child-device request
- urgent parent alert
- current location/battery/device context attached when available
- temporary location-freshness escalation may be requested
- parent acknowledgement supported
- no automatic 911/emergency-service call
- Android native emergency calling is not intentionally disabled

Get Help and Call Parent remain available during Time's Up and Kid Space.

## Location and telemetry

Raw high-frequency location/usage/device events live in private backend tables with strict retention.

Parent-facing Data API tables contain current state and aggregated summaries rather than unrestricted raw event history.

Child telemetry is batched, size-limited, signed, revocation-checked, and capability-aware.

Location freshness is explicit. The UI distinguishes fresh, stale, unavailable, permission-disabled, and offline states instead of presenting old data as current.

## Storage

All Harbor Storage buckets are private unless a later approved feature explicitly requires public content.

Initial uses:

- family export archives
- support attachments approved for collection
- later optional profile media

Access uses signed URLs or RLS-backed authenticated access. Upload/upsert policies grant only the exact operations required; upsert is not assumed to work with INSERT alone.

## Web protection

The backend migration does not change Android web-protection architecture.

- VpnService performs supported domain filtering
- Harbor Browser provides controlled in-app browsing
- no TLS interception/MITM
- filter health is reported to the parent
- failure/degraded state is visible rather than silently appearing protected

## Safety monitoring

V1 continues to prefer transparent, on-device risk detection and minimum necessary alert context.

NotificationListenerService or other sensitive Android permissions remain behind Play-policy/legal review. Full SMS/call-log ingestion is not required for V1.

Sensitive communication-derived content is not uploaded wholesale by default.

## Audit model

Important actions create immutable application audit events, including:

- family creation/membership changes
- child creation/deletion
- device enrollment/revocation
- policy changes
- Kid Space enable/disable
- bonus-time approval
- Lost Mode/destructive device actions
- Get Help acknowledgement
- export/delete requests
- admin/support access

Audit tables are private and not directly writable by mobile clients.

## Privacy and retention

Harbor collects only data required for active features.

Retention is defined by data class. Raw location and communication-derived safety data have stricter retention than configuration state. Export/delete workflows are first-class production requirements.

Child monitoring must be disclosed in-app. Store/privacy/legal release review remains a launch gate.

## Error handling and reconciliation

The system explicitly handles:

- device offline
- parent offline
- FCM delayed/unavailable/duplicated
- Realtime disconnected
- Supabase Auth session refresh failure
- revoked device with still-valid bearer token
- duplicate Edge Function request
- duplicate outbox dispatch
- malformed/stale desired-state version
- device clock skew
- app killed/rebooted
- partial Android capability
- database/Edge Function transient failure

The child always boots from the last valid local policy, not from an unrestricted default caused by backend unavailability.

## Database migration workflow

Schema is managed from Git through Supabase migrations.

Required workflow:

1. Link the local repository to the intended Supabase environment.
2. Create migration files with the Supabase CLI rather than inventing filenames manually.
3. Iterate/test SQL safely.
4. Run security/performance advisors.
5. Verify RLS policies and explicit grants.
6. Verify migration list and local/preview application.
7. Commit migrations with the associated tests.
8. Promote through non-production before production.

The currently empty connected project must not be treated as permission to skip migration history.

## Security testing requirements

At minimum, automated tests prove:

- Parent A cannot select Parent B's family by known UUID.
- Parent A cannot mutate Parent B's child/policy/device/alert rows.
- Device Auth user cannot access parent Data API tables directly.
- Device request with copied JWT but no private key fails proof-of-possession.
- Replayed signed device request is rejected.
- Revoked device is rejected immediately even with unexpired Supabase JWT.
- Consumed/expired pairing code cannot be reused.
- Bonus-time approval is exactly once.
- Duplicate outbox/FCM processing is idempotent.
- Storage object access cannot cross family boundaries.
- Sensitive secrets/tokens/content are absent from logs.

## Observability

Harbor records privacy-safe operational telemetry for:

- Edge Function latency/error rates
- Auth/enrollment abuse rates
- device-sync success/failure
- outbox backlog/retry/dead-letter state
- FCM dispatch outcomes
- Realtime connection health where observable
- migration/advisor status
- child app crash/ANR health without sensitive child content

Observability must not become a second copy of sensitive child data.

## CI/CD

Pull requests run:

- SQL formatting/linting as adopted by the project
- migration application checks
- RLS/security tests
- Edge Function tests
- Android unit/instrumented tests where applicable
- policy-engine tests
- secret scanning
- build checks

Schema/function promotion uses the Supabase/GitHub deployment workflow approved for the target environment. Production schema changes are never made only by manual dashboard edits.

## Environment strategy

Harbor ultimately requires at least development/staging and production separation.

The existing project `bfvybxkjxilntjgndsrm` is the current development target. Production must not share the same project/database/auth tenant unless a later explicit architecture review approves that risk.

Environment-specific values such as project URL and publishable key are configuration, not secrets. Secret/service-role values remain backend-only.

## Ordered Superpowers roadmap

The 12-subproject product roadmap remains intact, but Subproject 1 is replaced.

### Subproject 1 — Supabase platform foundation

Supabase project/repository linkage, migration baseline, Auth configuration, core family/child schema, RLS, Edge Function conventions, family creation, device enrollment/identity, device proof-of-possession, FCM registration, durable outbox, Realtime authorization baseline, security tests, CI, and staging/dev acceptance.

### Subproject 2 — Parent Android foundation

Native Kotlin/Compose shell, Supabase Auth/session/MFA integration, family/device navigation, Data API reads, Edge Function privileged mutations, Room cache, and initial connected dashboard.

### Subproject 3 — Child Android foundation

Enrollment, anonymous/device Supabase session, Android Keystore P-256 identity, signed Edge Function client, Room, FCM wake-up, background sync, monitoring disclosure, location/device health, and capability reporting.

### Subproject 4 — Screen-time and policy engine

Deterministic local policy evaluation, usage ledger, daily/per-app limits, Unlimited apps, schedules, bonus-time requests, and offline/reboot enforcement.

### Subproject 5 — Kid Space / DPC

Full-Supervision provisioning, Harbor launcher, Lock Task, allowlists, parent PIN, remote desired-state enable/disable, and anti-bypass tests.

### Subproject 6 — Location and family safety

Maps, places, location history, check-ins, Get Help, battery/device state, acknowledgement, Lost Mode, and recovery flows.

### Subproject 7 — Web protection

VpnService filtering, Harbor Browser, SafeSearch/domain policy, blocked events, filter-health reporting, and bypass testing.

### Subproject 8 — Digital activity

Usage summaries, activity analytics, pickups/notifications where appropriate, reports, and retention controls.

### Subproject 9 — Safety monitoring

Transparent notification-based risk analysis, minimum-data alerting, acknowledgement, and Play/legal review.

### Subproject 10 — Security/privacy/compliance hardening

Threat-model closure, RLS/function/device authorization testing, retention/export/delete, disclosures, abuse controls, audit guarantees, and Play/compliance launch gate.

### Subproject 11 — Commercial platform

Entitlements/subscriptions, account recovery, support/admin tooling, family-plan operations, and store/billing integration.

### Subproject 12 — Production hardening and staged launch

Android/OEM matrix, provisioning matrix, offline/reboot/clock/bypass tests, Supabase load/performance testing, observability, backup/restore/runbooks, beta, and release gates.

## Superseded implementation work

The earlier Platform Foundation plan at `docs/superpowers/plans/2026-10-03-harbor-platform-foundation.md` targets ASP.NET Core/Azure and is obsolete under this architecture.

Any open branch/PR implementing the .NET backend must not be merged into the new backend architecture. It may remain available as historical work until closed/archived.

A new Supabase Platform Foundation implementation plan must be written and approved before backend implementation begins.

## Architecture acceptance criteria

This architecture is realized only when all of the following are true:

- Parent identity uses Supabase Auth and family authorization is enforced by RLS/privileged functions.
- Every exposed table has tested RLS and explicit required privileges only.
- Child devices use a separate Supabase Auth identity and never parent credentials.
- Child protected requests require device-bound ECDSA proof-of-possession.
- Revoked devices are denied immediately on protected functions.
- Desired state is versioned and authoritative in PostgreSQL while enforcement remains local/offline on device.
- FCM is only wake/notification transport and duplicate delivery is safe.
- Realtime channels cannot cross family boundaries.
- Kid Space, Get Help, web protection, and safety behavior remain consistent with the approved product decisions.
- Sensitive backend secrets never ship in mobile clients.
- Export/delete, audit, retention, and launch compliance gates are implemented before production release.

## Architectural rulings

- Supabase fully replaces ASP.NET Core/Azure as Harbor's application backend platform.
- Native Kotlin/Compose parent and child apps remain unchanged.
- Android V1 minimum remains API 29 / Android 10.
- Parent read access may use Supabase Data API only under tested RLS.
- Privileged/multi-record/security-sensitive mutations use Edge Functions.
- Child devices use Edge Functions, not direct family-table Data API access.
- Supabase Auth session and device ECDSA proof-of-possession are both required for protected child operations.
- PostgreSQL remains source of truth for desired state; Room remains source of last-valid offline enforcement state.
- FCM remains wake/push transport.
- Realtime improves parent UX but is not policy authority.
- No TLS interception.
- Get Help alerts parents only and never automatically calls emergency services.
- Full SMS/call-log ingestion is not a V1 dependency.
- Security/privacy/compliance remain release-blocking architecture concerns.
