# Harbor Production Architecture Design

> Repository execution copy of the approved Harbor production architecture. The full Superpowers design was approved before implementation planning; this file preserves the production decisions, boundaries, and ordered roadmap needed by contributors working from GitHub.

## Product intent

Harbor is an Android-first family-safety platform for parents and children. It combines family location, screen-time enforcement, app policy, schedules, Kid Space lockdown, web protection, device recovery, activity reporting, check-ins, and child-to-parent Get Help alerts while keeping supervision visible to the child.

Harbor is not intended to be hidden surveillance software. Child supervision must be disclosed in the product experience and Android implementation.

## V1 scope

The first beta includes:

- Parent account and family management
- Child profiles and Android-device enrollment
- Parent and child Android applications
- Standard and Full Supervision modes
- Location, battery, device-health, places, and check-ins
- Hard daily screen-time limits
- Per-app limits and Unlimited apps
- Schedules and bedtime/school-time rules
- Parent-approved bonus-time requests
- Parent-controlled Kid Space
- Lost Mode and device recovery controls appropriate to the supervision level
- Web-domain filtering and Harbor Browser controls
- Digital-activity summaries
- Notification-based safety alerts where Android/Google Play policy permits them
- Get Help alerts to parents
- Audit, privacy, retention, export/delete, security, and observability foundations

Deferred from the first beta include iOS, automatic emergency-service calling, comprehensive SMS/call-log ingestion, driving scores, and other policy-sensitive features that require a separate review gate.

## Primary stack

### Android

- Kotlin
- Jetpack Compose
- Android 10 / API 29 minimum
- Room for local state
- WorkManager/background components where appropriate
- Firebase Cloud Messaging for device wake-up/notifications
- Android DevicePolicyManager and Lock Task for Full Supervision
- Android VpnService plus Harbor Browser for web protection
- Google Maps/location APIs for family location

### Backend

- ASP.NET Core .NET 10
- Modular monolith first; do not split into microservices prematurely
- EF Core
- ASP.NET Core Identity for parent identity
- PostgreSQL hosted by Supabase
- SignalR for live parent updates
- Transactional outbox for reliable event/push dispatch
- Azure Container Apps for the application tier
- Azure SignalR
- Azure Key Vault
- Application Insights / OpenTelemetry

## Repository target structure

```text
harbor/
├── apps/
│   ├── android-parent/
│   ├── android-child/
│   └── admin-web/
├── services/
│   ├── harbor-api/
│   ├── notification-worker/
│   └── safety-analysis/
├── packages/
│   ├── contracts/
│   ├── policy-engine-spec/
│   └── test-fixtures/
├── infrastructure/
│   ├── docker/
│   ├── database/
│   ├── migrations/
│   └── deployment/
├── docs/
│   ├── architecture/
│   ├── privacy/
│   └── superpowers/
└── tests/
    └── end-to-end/
```

## Roles and trust boundaries

Primary roles are Parent, Child Device, and administrative/support roles with narrowly scoped capabilities. A parent must only access families in which that account has membership. A child device receives device-scoped credentials and must never possess reusable parent credentials.

All authorization is enforced by the server from authenticated identity and resource ownership; client-supplied family/child IDs are never trusted as authorization proof.

## Supervision levels

### Standard Supervision

Designed for an already-used Android device where Harbor cannot become full device owner. Capabilities include the subset Android permits through ordinary application permissions and visible background services, such as location, usage reporting, schedules, requests, web filtering, Get Help, device health, and available app controls.

### Full Supervision

Uses Harbor Child as the Device Policy Controller on a managed Android device. It is the required mode for strong anti-bypass controls such as true Kid Space lockdown/Lock Task, stronger allowlisting, controlled launcher behavior, and tighter managed-device recovery restrictions.

The UI and backend must expose actual capability state and must not promise Full-Supervision behavior to a Standard-Supervision device.

## Parent Android application

Primary navigation remains aligned with the approved prototype:

- Map/Home
- Alerts
- Controls
- Activity
- Family/Devices

Parent capabilities include family setup, device pairing, map/location, screen-time rules, app policies, schedules, bonus-time approval, Kid Space management, web controls, device health, Get Help/check-in handling, Lost Mode, and family/device management.

## Child Android application

The child app has three major experiences:

1. Enrollment/transparency setup
2. Normal Harbor Kids experience
3. Forced Kid Space experience when enabled by the parent on a capable Full-Supervision device

Normal Harbor Kids displays the child's remaining time, schedules, app access, requests, Check In, Call Parent, and Get Help.

### Time's Up

When effective daily time reaches zero, normal apps are blocked. Apps marked Unlimited may continue according to the policy engine. The child can still request more time, Call Parent, and use Get Help.

### Kid Space

When enabled by the parent, Kid Space is a forced managed launcher in Full Supervision. The child cannot return to the ordinary launcher or disable Kid Space. Only parent-allowed apps appear. App access still passes through all schedule, app-limit, block, and daily-time rules. A local parent exit requires the parent PIN; remote parent policy can also exit Kid Space.

## Get Help, Check In, and Call Parent

Get Help is a deliberate child-to-parent escalation, not a one-tap emergency-services call. The child presses and holds for approximately three seconds. Harbor creates an urgent parent-visible event, includes current device/location context when available, and may temporarily increase location freshness. It does not automatically call 911/emergency services.

Check In records a child safety event indicating the child is okay. Call Parent remains available as an explicit parent contact action.

Harbor must not interfere with Android's own emergency-call capability.

## Screen-time and policy engine

Policy concepts include daily limits, per-app limits, Unlimited apps, explicit blocks, schedules, school/bedtime states, Kid Space allowlists, temporary bonus time, and device pause/lost states.

Evaluation must be deterministic and local so limits continue to work offline. A typical precedence is:

1. Device/lost/pause state
2. Active schedule restriction
3. Kid Space allowlist when Kid Space is active
4. Explicit app/category block
5. Daily family limit
6. Per-app limit
7. Unlimited exception where policy allows it
8. Allow

The device keeps a local usage ledger and reconciles summaries to the backend. Bonus time is represented as an explicit parent-approved allowance, not a child-editable counter.

## Desired state and device commands

The backend maintains versioned desired policy state for each child/device. Discrete commands cover operations such as wake/sync, Lost Mode, ring, and other one-shot actions.

Commands are idempotent. Delivery may occur more than once. The child records command IDs and desired-policy versions so duplicate or stale delivery cannot cause duplicate effects.

FCM is a wake-up/delivery transport, not the source of truth. When notified, the child authenticates to the backend and fetches current desired state/commands.

Offline devices retain and enforce the last valid policy and reconcile when connectivity returns.

## Device enrollment and cryptographic identity

The parent initiates pairing for a child. Enrollment uses a short-lived one-time token/code; the Platform Foundation plan fixes the first implementation to a six-digit token with a 10-minute lifetime.

During claim, the child device generates an ECDSA P-256 key pair. The private key remains device-local. The public key is registered with Harbor. Device authentication and refresh use proof-of-possession so possession of a copied bearer value alone is insufficient.

Revoked devices must lose access immediately on protected device endpoints.

## Parent authentication

Parent identity uses ASP.NET Core Identity with email verification, password reset, rotating refresh tokens, replay protection, and TOTP MFA support. Tokens are short-lived and server-side authorization is family/resource scoped.

## Core backend domains

The production data model includes:

- Accounts/parents
- Families and memberships
- Children
- Devices and device credentials
- Enrollment tokens
- Refresh-token/session records
- Screen-time, app, schedule, Kid Space, and web policies
- Bonus-time requests/approvals
- Device desired state and commands
- FCM registrations
- Location/device-health/usage summaries
- Places
- Alerts, Get Help, check-ins, and safety events
- Immutable audit events
- Transactional outbox records
- Retention/export/delete metadata

## API boundaries

All public APIs are versioned under `/api/v1`.

Major groups:

- Parent authentication
- Families/memberships/children
- Device enrollment and lifecycle
- Policies
- Requests/approvals
- Device sync and commands
- Telemetry/location/device health
- Places, safety events, alerts, and acknowledgement

The OpenAPI contract is part of the build and must remain compatible with supported mobile app versions.

## Web protection

Device-level filtering uses Android VpnService without TLS interception. Harbor Browser provides a controlled in-app browsing path for allow/block policy, SafeSearch, downloads, and blocked-page UX. Full-Supervision devices should report VPN/filter-health state to the parent.

Harbor must not implement a man-in-the-middle HTTPS inspection system.

## Digital activity and safety monitoring

V1 digital activity focuses on app usage, pickups/usage summaries, device health, web events, and other data Android permits with transparent permissions.

NotificationListenerService may support explicitly disclosed notification-based safety analysis. Prefer on-device risk detection and upload only the minimum parent-relevant alert context rather than uploading all child conversations.

Full SMS/call-log features are a later policy/legal/store-review decision and are not a V1 dependency.

## Lost Mode

Lost Mode is policy-driven, capability-aware, and reversible by an authorized parent. Full-Supervision devices can receive stronger restrictions than Standard-Supervision devices. Remote wipe is a separate high-risk/destructive action and must require explicit confirmation and appropriate device-management capability.

## Realtime and notifications

- SignalR: live parent dashboard/session updates
- FCM: child-device wake-up and notification transport
- Transactional outbox: guarantees durable backend intent before external dispatch

Delivery is at-least-once. Consumers must be idempotent.

## Error handling and reconciliation

Required conditions include:

- Network unavailable
- FCM delayed/unavailable
- Conflicting policy updates
- Duplicate command delivery
- Device clock changes
- Partial device capability
- App killed or device rebooted

The child must boot into a safe state using the last valid local policy and reconcile with the backend as soon as possible.

## Security principles

- Least privilege
- Family/resource authorization on every protected operation
- Device credentials are separate from parent credentials
- No secrets in source/logs
- TLS in transit and platform-managed encryption at rest
- Key Vault for production secrets
- Rate limiting on authentication, enrollment, Get Help, and other abuse-sensitive endpoints
- Sensitive fields redacted from structured logs
- Immutable security/audit events for important actions
- Revocation checked strongly enough to take effect immediately where required

## Privacy and retention

Harbor stores only data required for the feature. Location, safety, and communication-derived data receive stricter retention/access rules than ordinary app configuration. The product supports family data export and account/family deletion workflows. Message-safety processing should default to minimum necessary context.

## Observability and operations

Backend services emit structured logs, traces, metrics, request correlation IDs, outbox/worker health, command-delivery health, API latency/error rates, and authentication/enrollment abuse metrics through OpenTelemetry/Application Insights. Mobile crash/ANR reporting must avoid sensitive child data.

## Compliance and store-review launch gate

No production release proceeds until the Android/Google Play monitoring requirements, privacy disclosures, permissions, persistent-monitoring indicators, data-safety declarations, child/family privacy obligations, and jurisdictional requirements have been reviewed for the release scope.

Policy-sensitive capabilities must remain feature gated until approved.

## Deployment topology

Production baseline:

- Azure Container Apps — Harbor API and workers
- Azure SignalR — realtime parent sessions
- Azure Key Vault — secrets
- Application Insights/OpenTelemetry — application telemetry
- Supabase PostgreSQL — relational data
- FCM — Android notification/wake transport

Database migrations are versioned and forward-safe. Production promotion is staged and requires health checks and migration verification.

## CI/CD

Pull requests run formatting/static analysis, .NET tests, Android tests where applicable, migration checks, security checks, and build artifacts. Main-branch builds deploy to staging. Production promotion is explicit and gated by acceptance tests.

## Testing strategy

Required layers:

- Policy-engine unit tests
- Backend unit tests
- API integration tests against PostgreSQL
- Android unit/instrumented tests
- Managed-device/Lock Task tests on supported Android versions/OEMs
- Multi-device end-to-end tests
- Security and authorization tests
- Offline/reboot/duplicate-command/time-change tests
- Staged production smoke tests

## Frontend design rules

The existing frontend prototype is the design reference, not production application code. Parent and child production UIs should preserve Harbor's visual language while becoming native Compose applications.

Parent UX prioritizes clear status, explainable policy, and fast handling of requests/alerts. Child UX uses large touch targets, transparent supervision language, clear remaining-time/schedule state, and safety actions that remain available during restrictions.

Accessibility is a release requirement, including semantics, contrast, scalable text, focus/touch targets, and non-color-only status.

## Versioning

- API version: `/api/v1`
- Policy documents carry a schema version
- Desired-state changes carry monotonically increasing versions
- Android minimum for V1: API 29 / Android 10
- Server remains backward compatible across the supported mobile-app window

## Ordered Superpowers implementation roadmap

### Subproject 1 — Platform foundation

Monorepo, .NET 10 API, PostgreSQL/EF Core, parent authentication, families/children, device enrollment/identity, FCM registration, outbox, security baseline, CI, and Azure staging.

### Subproject 2 — Parent Android foundation

Native Kotlin/Compose shell, authentication, family/device navigation, networking/cache, and initial connected dashboard.

### Subproject 3 — Child Android foundation

Enrollment, device identity, Room, background sync, monitoring disclosure/notification, location/device health, and capability reporting.

### Subproject 4 — Screen-time and policy engine

Deterministic local policy evaluation, usage ledger, daily/per-app limits, Unlimited apps, schedules, bonus-time requests, and offline/reboot enforcement.

### Subproject 5 — Kid Space / DPC

Full-Supervision provisioning, Harbor launcher, Lock Task, allowlists, parent PIN, remote enable/disable, and anti-bypass tests.

### Subproject 6 — Location and family safety

Maps, places, location history, check-ins, Get Help, battery/device state, acknowledgement, Lost Mode, and recovery flows.

### Subproject 7 — Web protection

VpnService filtering, Harbor Browser, SafeSearch/domain policy, blocked events, health reporting, and bypass testing.

### Subproject 8 — Digital activity

Usage summaries, app/activity analytics, pickups/notifications where appropriate, reports, and retention controls.

### Subproject 9 — Safety monitoring

Transparent notification-based risk analysis, unknown-contact/safety alerts, minimum-data handling, acknowledgement, and policy/store review.

### Subproject 10 — Security/privacy/compliance hardening

Threat-model closure, authorization/revocation testing, retention/export/delete, disclosure/consent, abuse controls, audit guarantees, and Play/compliance launch gate.

### Subproject 11 — Commercial platform

Entitlements/subscriptions, account recovery, support/admin portal, family-plan operations, and billing/store integration.

### Subproject 12 — Production hardening and staged launch

OEM/device matrix, managed-device provisioning matrix, offline/reboot/clock/bypass tests, load/performance, observability dashboards, incident/runbooks, staged beta, and production release gates.

## Milestones

### Milestone A — Connected alpha

Real parent authentication, family/child records, device pairing/identity, parent/child connectivity, and basic telemetry.

### Milestone B — Safety alpha

Screen-time enforcement, requests, Kid Space, location, Get Help/check-ins, and desired-state reconciliation.

### Milestone C — Private beta

Web protection, activity views, Lost Mode, security/privacy hardening, operational telemetry, and multi-device testing.

### Milestone D — Commercial release

Compliance/store gates, subscriptions/entitlements, support/admin tooling, production hardening, staged rollout, and release readiness.

## Architectural rulings

- Android-only V1; API 29 minimum.
- Two native Kotlin/Compose applications rather than forcing the child-control surface through a cross-platform runtime.
- Modular monolith backend first.
- Supabase PostgreSQL behind the Harbor API; mobile apps do not directly become the database security boundary.
- Azure application tier with Container Apps, SignalR, Key Vault, and Application Insights.
- Offline policy enforcement is authoritative on-device using the last valid policy.
- FCM is wake/delivery transport, not source of truth.
- Device commands are idempotent and delivery is at-least-once.
- Full-Supervision Kid Space uses Android managed-device capabilities/Lock Task.
- Get Help alerts parents only; it does not automatically call emergency services.
- No TLS interception.
- Full SMS/call-log access is not a V1 dependency.
- Security/privacy/compliance are architecture concerns, not a final-week checklist.

## Architecture acceptance criteria

The architecture is considered realized only when:

- A parent can securely create/sign in to an account and manage a family.
- A child Android device can securely enroll without receiving parent credentials.
- Parent A cannot access Family B resources.
- Revoking a device actually terminates its protected backend access.
- Desired policy reaches the child, is persisted locally, and survives offline/reboot states.
- Screen-time limits are enforced locally and cannot be self-granted by the child.
- Full-Supervision Kid Space produces true managed lockdown behavior on supported devices.
- Get Help reliably creates parent-visible urgent events without automatically calling emergency services.
- Web protection and device capability are visible/health-checked rather than silently failing.
- Sensitive data is access-controlled, redacted from logs, retained according to policy, and export/delete capable.
- CI/CD, observability, recovery, security testing, and staged deployment are operating before production launch.
