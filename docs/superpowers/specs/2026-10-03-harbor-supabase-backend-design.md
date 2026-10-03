# Harbor Supabase Backend Architecture Design

> **Controlling backend architecture as of 2026-10-03.** This spec supersedes the earlier ASP.NET Core/.NET 10 and Azure application-tier design. Harbor remains a native Android family-safety product, but Supabase is now the primary production backend.

## Product intent

Harbor is an Android-first family-safety platform for parents and children. It combines family location, hard screen-time enforcement, app policy, schedules, parent-approved bonus time, Kid Space lockdown, web protection, device recovery, activity reporting, check-ins, and child-to-parent Get Help alerts while keeping supervision visible to the child.

Harbor is not hidden surveillance software. The child experience must disclose that supervision is active and what categories of data are shared.

## V1 scope

V1 includes parent/family accounts, child profiles, Android device enrollment, Standard and Full Supervision, location/device health, hard daily and per-app limits, Unlimited apps, schedules, bonus-time requests, Kid Space, Lost Mode where supported, web protection, activity summaries, transparent safety alerts, Get Help, audit/privacy/retention/export/delete foundations, and production security hardening.

Deferred from V1: iOS, automatic emergency-service calls, comprehensive SMS/call-log ingestion, driving scores, and other policy-sensitive features requiring a separate approval gate.

## Platform decisions

### Android

- Kotlin + Jetpack Compose
- Android 10 / API 29 minimum
- Room for offline/local state
- WorkManager/background components where appropriate
- Firebase Cloud Messaging for Android wake-up and notification transport
- Android DevicePolicyManager + Lock Task for Full Supervision
- Android VpnService + Harbor Browser for web protection
- Google Maps/location APIs for family location

### Supabase backend

Harbor uses:

- Supabase Auth for parent identity and sessions
- Supabase Postgres for application data
- Row Level Security on every exposed Harbor table
- Supabase Edge Functions for privileged business logic and all child-device authentication/sync endpoints
- Supabase Realtime for parent-facing live updates
- Supabase Storage for private profile assets and family exports
- Supabase CLI migrations as canonical database history
- Supabase project secrets for server-only credentials such as FCM credentials
- Supabase Logs plus external error aggregation as needed

Current development/staging project:

- Project ref: `bfvybxkjxilntjgndsrm`
- URL: `https://bfvybxkjxilntjgndsrm.supabase.co`
- Region: `us-east-1`

This project is development/staging only until a separate production project is created.

## Trust boundaries

Harbor has two separate identity domains.

### Parent identity

Parents authenticate with Supabase Auth. Parent Android clients receive ordinary Supabase sessions and use only the project publishable key. Secret/service-role credentials never appear in Android apps.

Family authorization is based on authoritative database membership, not `user_metadata`. User-editable metadata must never be used for RLS authorization.

### Child-device identity

Child devices never authenticate as parents and never store parent credentials.

During enrollment, each child device generates an ECDSA P-256 key pair in Android Keystore. The private key remains device-local. Harbor stores only the public key and device metadata.

Child enrollment and protected device traffic use custom proof-of-possession through Edge Functions. A copied bearer/session value alone must not be sufficient to authenticate.

## Repository target structure

```text
harbor/
├── apps/
│   ├── android-parent/
│   ├── android-child/
│   └── admin-web/
├── supabase/
│   ├── config.toml
│   ├── migrations/
│   ├── functions/
│   │   ├── _shared/
│   │   ├── parent-family-command/
│   │   ├── device-enroll/
│   │   ├── device-auth-challenge/
│   │   ├── device-auth-verify/
│   │   ├── device-sync/
│   │   ├── device-command-ack/
│   │   ├── push-dispatch/
│   │   └── family-export/
│   └── tests/
│       ├── database/
│       └── functions/
├── packages/
│   ├── contracts/
│   ├── policy-engine-spec/
│   └── test-fixtures/
├── docs/
│   ├── architecture/
│   ├── privacy/
│   └── superpowers/
└── tests/
    └── end-to-end/
```

No .NET backend service is part of Harbor V1 after this revision.

## Parent authentication

Supabase Auth handles:

- email/password sign-up and sign-in
- email verification
- password reset
- session/refresh lifecycle
- TOTP MFA

Sensitive operations may require a fresh or AAL2 session when MFA is enrolled. Examples include family deletion, parent-role changes, destructive device operations, account-security changes, and export/delete workflows.

JWT `app_metadata` may be used only as an optimization. Revocation-sensitive family membership remains database-authoritative because JWT claims can be stale until refresh.

## RLS and family authorization

Every table exposed through the Data API has RLS enabled before client access is granted.

Rules:

- `anon` receives no Harbor family-data privileges.
- `authenticated` receives only explicit required grants.
- Every family-scoped policy checks indexed family membership or direct ownership.
- Client-supplied family/child/device IDs are selectors, never proof of authorization.
- UPDATE policies include both `USING` and `WITH CHECK` and have matching SELECT access where required.
- Views exposed to clients use `security_invoker = true` on Postgres 15+.

If a reusable membership helper requires `SECURITY DEFINER`, it must live in a non-exposed `private` schema, set an explicit safe `search_path`, validate `auth.uid()` itself, grant execution only to required roles, and be covered by security tests/advisors. No `SECURITY DEFINER` Harbor helper is created in `public`.

## Schema boundaries

### `public` — parent-visible, RLS-protected

Initial domain tables/projections include:

- `profiles`
- `families`
- `family_members`
- `children`
- `devices` — sanitized device state only
- `policies`
- `policy_versions`
- `time_requests`
- `bonus_allowances`
- `places`
- `alerts`
- `check_ins`
- `help_events`
- `location_events` or retention-safe location views
- `usage_summaries`
- `web_events`
- `safety_events`

High-volume tables are not partitioned until production measurements justify it.

### `private` — never exposed through Data API

- enrollment token/challenge data
- device public-key registration internals
- device sessions and hashed session tokens
- request replay nonces
- raw push-token internals
- device-command delivery internals
- FCM outbox/retry state
- immutable audit internals
- export/admin job state
- server-only configuration references

Child devices access these only through Edge Functions.

## Device enrollment

1. Parent selects a child and requests pairing.
2. Authenticated Edge Function verifies the parent's family role.
3. Harbor creates a random six-digit, one-time code with a 10-minute lifetime, scoped to one child/family. Store a digest rather than plaintext where feasible.
4. Child creates an ECDSA P-256 key pair in Android Keystore.
5. Child submits the code, public key, app/device metadata, and capability state to `device-enroll`.
6. The function atomically consumes the code, creates the device, stores the public key, records an audit event, and returns a bootstrap response.
7. The code cannot be reused.

Pairing attempts are rate limited. Invalid/expired responses must not reveal family membership.

## Device authentication

Device authentication is separate from Supabase parent Auth.

### Session establishment

1. Device asks `device-auth-challenge` for a short-lived one-time challenge.
2. Device signs the challenge plus canonical context with its Android-Keystore private key.
3. `device-auth-verify` checks the registered public key, signature, revocation state, and challenge expiry, consumes the challenge, and creates a random short-lived device session token.
4. Only a hash of the device session token is stored.
5. Raw session material is returned once and stored securely on the device.

### Protected requests

Sensitive device requests include:

- device session token/identifier
- timestamp
- unique nonce
- canonical HTTP method/path
- body hash
- ECDSA signature over canonical request data

Edge Functions reject revoked devices, expired sessions, invalid signatures, stale timestamps, and replayed nonces. Protected operations check authoritative revocation state so revocation takes effect immediately.

A copied device session token without the private key is insufficient.

## Edge Function boundaries

Functions remain small and share common libraries instead of deeply calling each other.

### `parent-family-command`

Privileged authenticated-parent mutations: pairing-code creation, policy publication, bonus-time approval, Kid Space toggles, Lost Mode, device revocation, parent-role changes, and other multi-row/audited operations.

### `device-enroll`

Consumes a one-time pairing code and registers the device/public key.

### `device-auth-challenge`

Creates a short-lived one-use cryptographic challenge.

### `device-auth-verify`

Verifies ECDSA proof and creates/rotates a device session.

### `device-sync`

Authenticated device endpoint returning authoritative desired policy version, pending commands, server time, and configuration while accepting bounded health/telemetry updates.

### `device-command-ack`

Idempotent device command acknowledgement.

### `push-dispatch`

Server-only FCM send/retry worker. FCM credentials live in Supabase project secrets.

### `family-export`

Authenticated export job creating a private Storage artifact under retention/privacy controls.

Any function that uses service-role access must independently authorize the caller before privileged mutation.

## Parent Data API strategy

The Parent app may use Data API directly for RLS-safe reads and simple low-risk writes. Security-sensitive or multi-row mutations go through Edge Functions.

Direct reads include family/child lists, sanitized device status, current policies, alerts, activity summaries, and permitted recent location/history.

Edge Function mutations include pairing code creation, bonus-time approval, policy publication, Kid Space/Lost Mode, parent-role changes, family delete/export, and device revocation.

## Realtime

Supabase Realtime replaces Azure SignalR.

Parent clients may subscribe to RLS-protected parent-visible resources such as alerts, Get Help, check-ins, device status, time requests, command acknowledgement projections, and permitted location/status projections.

Realtime is a hint channel, not durable state. Parent clients re-read authoritative rows when an event arrives.

Child command delivery does not depend on an open Realtime socket.

## FCM, outbox, and delivery

FCM remains Android wake/notification transport; Postgres remains source of truth.

A durable `push_outbox` row is committed before an external FCM attempt. It carries an idempotency key, destination, event type, minimal/reference payload, attempt state, and retry time.

Urgent flows may attempt immediate FCM delivery after commit. Failed/pending outbox rows remain durable and are retried by a scheduled Supabase invocation. Processing uses short transactions and concurrency-safe row claiming so duplicate workers do not duplicate domain effects.

Delivery is at-least-once. Consumers are idempotent.

For Get Help, the `help_event` and durable parent notification intent are stored before FCM is attempted.

## Storage

Initial private buckets:

- `avatars`
- `family-exports`

Storage access uses explicit RLS policies on `storage.objects`. Paths are user/family scoped. Android clients never receive service-role/secret keys.

Harbor does not store raw child conversation archives by default.

## Policy engine

Policy concepts remain daily limits, per-app limits, Unlimited apps, explicit blocks, schedules, school/bedtime states, Kid Space allowlists, bonus time, pause, and lost state.

Evaluation stays deterministic and local so enforcement continues offline. Typical precedence:

1. Device/lost/pause state
2. Active schedule restriction
3. Kid Space allowlist
4. Explicit app/category block
5. Daily family limit
6. Per-app limit
7. Unlimited exception where policy permits
8. Allow

Policies are versioned in Postgres. The child caches the last valid version in Room. Bonus time is an explicit parent-approved allowance, not a child-editable counter.

## Desired state and commands

Commands use unique IDs, target device, type, payload/version reference, creation/expiry, status, and idempotency key.

FCM wakes the child. The child then authenticates to `device-sync` and fetches authoritative desired state/commands. Applied command IDs and policy versions are retained locally so duplicates/stale updates cannot cause duplicate effects.

## Supervision levels

### Standard Supervision

For already-used Android devices where Harbor is not device owner. Capabilities are limited to what ordinary Android permissions and visible background services support.

### Full Supervision

Harbor Child acts as Device Policy Controller on a managed device. This enables true Kid Space Lock Task/kiosk behavior, stronger allowlisting, controlled launcher behavior, and stronger anti-bypass restrictions.

Backend/UI report actual capabilities and never claim Full-Supervision protection on a Standard device.

## Kid Space

In Full Supervision, enabling Kid Space forces Harbor's managed launcher. Only parent-allowed apps appear, and each app still passes schedule, daily limit, app-limit, and block policy.

The child cannot disable Kid Space. Local exit requires parent verification/PIN. Remote authorized parent policy can disable it. Harbor does not intentionally disable Android-required emergency/system access.

## Get Help

Get Help is a deliberate child-to-parent escalation, not emergency-service dispatch.

The child holds the control for approximately three seconds. Harbor writes an urgent `help_event`, captures available location/device context, creates durable notification intent, and may temporarily increase location freshness.

Get Help never automatically calls 911/emergency services. Harbor must not interfere with Android native emergency calling.

## Location, activity, web, and safety data

Sensitive telemetry is family scoped and retention controlled. Child uploads occur through authenticated device Edge Functions, not unrestricted direct table writes.

Web protection uses Android VpnService and Harbor Browser. Harbor does not use TLS MITM interception.

Notification-based safety monitoring must be transparent. Prefer on-device detection and upload only the minimum parent-relevant alert context. Full SMS/call-log ingestion remains outside V1 unless separately approved.

## Security requirements

- Least privilege everywhere
- RLS on every exposed Harbor table
- Explicit grants, never blanket authenticated access without ownership predicates
- Parent/device trust domains remain separate
- Android private keys remain in Keystore
- Service-role/secret credentials are server-only
- No authorization from user-editable metadata
- No secrets in source/logs/crash analytics
- Short-lived enrollment/challenge/session material
- Device replay protection
- Immediate revocation checks on protected device operations
- Rate limits on auth, enrollment, pairing, Get Help, and sensitive functions
- Immutable/append-only audit treatment for security-critical actions
- Supabase Security and Performance Advisors run before promotion

## Privacy, retention, export, deletion

Harbor stores only data required for enabled features. Location, safety, and communication-derived data receive stricter retention than ordinary configuration.

The product supports family export, account/family deletion, device revocation, Storage cleanup, and required audit-safe tombstones.

Deleting a Supabase Auth user alone is not treated as proof that every previously issued access token is immediately invalid; sensitive workflows validate current session/state as required.

## Observability

Use Supabase function logs, query/database metrics, security/performance advisors, structured correlation IDs, outbox/push retry metrics, enrollment/auth abuse metrics, and command/device-sync health metrics. External error aggregation may be used if it redacts sensitive child data.

Android crash/ANR reporting must also redact child-sensitive content.

## Local development and migrations

Canonical workflow:

1. `supabase init` once.
2. `supabase link --project-ref bfvybxkjxilntjgndsrm` for the development project.
3. `supabase start` for local development.
4. Create migration files with `supabase migration new <name>`.
5. Iterate locally/safely.
6. Run database tests, function tests, and Supabase advisors.
7. Verify migration history.
8. Promote migrations/functions through reviewed CI or explicit reviewed release steps.

Do not use unreviewed manual production SQL as the deployment process.

## CI/CD

Pull requests run migration apply/reset verification, pgTAP/SQL RLS tests, Edge Function tests, secret scanning, Android tests where applicable, and contract checks.

`main` deploys only to the designated development/staging project after CI passes. Production uses a separate Supabase project and explicit deployment protection.

Supabase Branching may be used when available on the chosen plan; otherwise use local CI plus separate dev/staging/production projects.

## Backend acceptance flow

Subproject 1 is complete only when staging proves:

1. Parent creates/verifies a Supabase Auth account.
2. Parent creates Family A and Child A.
3. Parent A cannot access Family B/Child B even with known IDs.
4. Parent creates a six-digit, 10-minute enrollment code.
5. Child generates an ECDSA P-256 key and enrolls once.
6. Reused/expired code is rejected.
7. Device performs challenge/signature authentication and receives a device session.
8. Copied session token without the private key cannot authenticate.
9. Device registers FCM token through device endpoint.
10. Parent publishes simple desired state/command.
11. Durable Postgres state/outbox is committed before FCM attempt.
12. Device wakes/syncs, applies authoritative state idempotently, and acknowledges.
13. Revocation immediately blocks protected device access.
14. Audit/outbox history proves critical transitions.
15. Supabase Security/Performance Advisors show no unaddressed high-severity issue caused by the new schema.

## Ordered Superpowers roadmap

1. **Supabase platform foundation** — Auth, Postgres schemas/RLS, families/children, device enrollment/identity, FCM registration, Edge Functions, outbox, CI, staging acceptance.
2. **Parent Android foundation** — Compose shell, Supabase Auth/session, RLS repositories, Room cache, Realtime, connected dashboard.
3. **Child Android foundation** — Keystore device identity, custom device session, Room, FCM/background sync, transparency, device health.
4. **Screen-time/policy engine** — deterministic local evaluator, usage ledger, hard limits, Unlimited apps, schedules, requests, offline/reboot enforcement.
5. **Kid Space / DPC** — managed-device provisioning, launcher, Lock Task, allowlists, parent exit, anti-bypass testing.
6. **Location and family safety** — maps, location history, places, check-ins, Get Help, Lost Mode, parent acknowledgement.
7. **Web protection** — VpnService, Harbor Browser, domain policy, filter health, bypass tests.
8. **Digital activity** — usage summaries/trends, supported activity signals, retention controls.
9. **Safety monitoring** — transparent notification-based risk alerts and minimum-data handling.
10. **Security/privacy/compliance** — threat model, RLS/revocation, retention/export/delete, disclosures, Play/compliance, Supabase production hardening.
11. **Commercial platform** — subscriptions/entitlements, recovery, support/admin tooling.
12. **Production hardening/staged launch** — OEM/device matrix, bypass/offline/load tests, backups/recovery, observability/runbooks, staged release.

## Superseded elements

The following are no longer part of Harbor V1:

- ASP.NET Core/.NET 10 Harbor API
- EF Core
- ASP.NET Core Identity
- Azure Container Apps
- Azure SignalR
- Azure Key Vault
- Application Insights as primary backend telemetry
- the earlier .NET-specific Platform Foundation implementation plan

A new **Supabase Platform Foundation implementation plan** must be written only after this spec is reviewed and approved.

## Architecture acceptance criteria

The architecture is realized only when Parent A cannot access Parent B's data, every exposed Harbor table has tested RLS, child devices never store parent/server credentials, ECDSA proof-of-possession defeats copied session tokens, revoked devices lose access immediately, offline policy enforcement is safe, limits/Unlimited rules are deterministic, child cannot self-approve time, Full-Supervision Kid Space resists ordinary escape paths, Get Help remains available without calling emergency services, FCM/Realtime loss does not erase authoritative state, monitoring is disclosed, sensitive data stays out of logs, export/delete/retention works, and Supabase migration/backup/security release checks pass before production rollout.
