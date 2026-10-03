# Harbor Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Use TDD and commit each independently testable task.

**Goal:** Build Harbor Subproject 1: the production backend foundation that supports secure parent identity, family/child ownership, cryptographically enrolled Android devices, device authentication, push registration, reliable outbox delivery, security/audit controls, CI/CD, and Azure staging acceptance.

**Architecture:** ASP.NET Core .NET 10 modular monolith backed by PostgreSQL/Supabase. Parent identity and family authorization are separate from device identity. Devices enroll using short-lived one-time pairing tokens and ECDSA P-256 proof-of-possession. FCM is at-least-once wake/delivery transport; PostgreSQL state and the transactional outbox are authoritative.

**Tech Stack:** .NET 10, ASP.NET Core, EF Core, ASP.NET Core Identity, PostgreSQL/Supabase, xUnit, Testcontainers, FluentValidation, OpenAPI, JWT, ECDSA P-256, Firebase Cloud Messaging, OpenTelemetry/Application Insights, Docker, GitHub Actions, Azure Container Apps, Azure Key Vault.

**Spec:** `docs/superpowers/specs/2026-10-02-harbor-production-architecture-design.md`

## Global Constraints

- Android V1 minimum is API 29 / Android 10.
- Backend begins as a modular monolith.
- Public HTTP APIs use `/api/v1`.
- Parent and device credentials are distinct trust domains.
- Mobile clients never receive direct PostgreSQL credentials.
- Every family-scoped operation authorizes the authenticated principal against server-side membership/resource ownership.
- Device enrollment token is six digits, one-time use, and expires after 10 minutes.
- Device proof-of-possession uses ECDSA P-256.
- Refresh credentials rotate and replay must revoke the affected token chain/session.
- Device revocation must take effect immediately on protected device endpoints.
- FCM delivery is at-least-once; consumers are idempotent.
- Sensitive credentials/tokens/PII must not appear in structured logs.
- Production secrets come from Azure Key Vault/environment injection, never source control.
- PostgreSQL migrations are versioned and checked in CI before promotion.
- Staging must prove the complete parent → family → child → device-enrollment → device-auth → push-registration flow.

## Target repository structure for Subproject 1

```text
Harbor/
├── Harbor.sln
├── Directory.Build.props
├── global.json
├── services/
│   ├── Harbor.Api/
│   ├── Harbor.Application/
│   ├── Harbor.Domain/
│   ├── Harbor.Infrastructure/
│   └── Harbor.Worker/
├── packages/
│   └── Harbor.Contracts/
├── tests/
│   ├── Harbor.UnitTests/
│   ├── Harbor.IntegrationTests/
│   └── Harbor.ArchitectureTests/
├── infrastructure/
│   ├── docker/
│   └── azure/
├── .github/workflows/
└── docs/
```

## Review Focus

The final whole-branch review must deliberately verify these high-risk conditions:

1. Malformed/expired/replayed pairing tokens never enroll a device.
2. Parent A cannot read or mutate any Family B child/device/resource even with valid IDs.
3. Refresh-token replay rotates/revokes correctly instead of creating parallel reusable sessions.
4. Revoked device credentials fail immediately rather than surviving until JWT expiry.
5. Duplicate outbox/FCM attempts do not create duplicate domain effects or leak sensitive data in logs.

---

## Task 1: Monorepo, solution, local PostgreSQL, and health baseline

**Deliverable:** A clean .NET 10 solution that boots locally against PostgreSQL, exposes health endpoints, and has unit/integration test projects.

**Create:**

- `Harbor.sln`
- `global.json`
- `Directory.Build.props`
- `services/Harbor.Api/Harbor.Api.csproj`
- `services/Harbor.Application/Harbor.Application.csproj`
- `services/Harbor.Domain/Harbor.Domain.csproj`
- `services/Harbor.Infrastructure/Harbor.Infrastructure.csproj`
- `services/Harbor.Worker/Harbor.Worker.csproj`
- `packages/Harbor.Contracts/Harbor.Contracts.csproj`
- `tests/Harbor.UnitTests/Harbor.UnitTests.csproj`
- `tests/Harbor.IntegrationTests/Harbor.IntegrationTests.csproj`
- `tests/Harbor.ArchitectureTests/Harbor.ArchitectureTests.csproj`
- `infrastructure/docker/compose.yml`

**Interfaces produced:**

- API startup composition root in `Harbor.Api`
- PostgreSQL connection configured through `ConnectionStrings:Harbor`
- `GET /health/live`
- `GET /health/ready`

**TDD/verification:**

- Integration test: live health returns 200 without requiring database query.
- Integration test: ready health is healthy with PostgreSQL running and unhealthy when unavailable.
- `dotnet test Harbor.sln`
- `docker compose -f infrastructure/docker/compose.yml up -d`
- `dotnet run --project services/Harbor.Api`

**Commit:** `chore: scaffold Harbor platform foundation`

---

## Task 2: Domain model, EF Core context, Identity, and initial migration

**Deliverable:** Initial relational schema for parent identity, families, children, devices, enrollment, sessions, audit, and outbox.

**Core entities:**

- `ApplicationUser`
- `Family`
- `FamilyMembership`
- `Child`
- `Device`
- `DevicePublicKey`
- `DeviceEnrollmentToken`
- `ParentRefreshToken`
- `DeviceRefreshToken`
- `PushRegistration`
- `AuditEvent`
- `OutboxMessage`

**Required rules:**

- Family membership uniquely identifies `(FamilyId, UserId)`.
- Child belongs to exactly one family.
- Device belongs to exactly one child/family lineage.
- Enrollment token stores a hash, not the six-digit plaintext code.
- Refresh tokens are stored as hashes.
- Public keys are versioned and associated with device identity.
- Audit/outbox timestamps use UTC.

**TDD/verification:**

- EF model/constraint tests.
- Migration creates schema on a fresh PostgreSQL Testcontainer.
- Duplicate membership and invalid ownership relationships fail.
- Token fields never expose plaintext values in persisted entities.

**Commit:** `feat: add Harbor domain model and database schema`

---

## Task 3: Shared contracts, error envelope, OpenAPI, and validation

**Deliverable:** Stable `/api/v1` contract conventions used by all later endpoints.

**Create contracts for:**

- API error envelope/problem details
- Pagination where needed
- Parent auth responses
- Family/child DTOs
- Enrollment/device DTOs
- Push-registration DTOs

**Rules:**

- JSON contract names are explicit/stable.
- Validation failures return a consistent 400 shape.
- Authentication failures use 401; authorization failures use 403; missing owned resource uses the chosen non-leaking 404/403 rule consistently.
- OpenAPI documents `/api/v1` endpoints and bearer schemes.

**TDD/verification:**

- Contract serialization tests.
- Invalid request integration test proves consistent field errors.
- OpenAPI smoke test includes expected versioned paths and security schemes.

**Commit:** `feat: define Harbor API contracts`

---

## Task 4: Parent authentication, rotating refresh tokens, email flows, and TOTP MFA

**Deliverable:** Production-oriented parent authentication independent of device authentication.

**Endpoints under `/api/v1/auth`:**

- register
- login
- refresh
- logout/revoke session
- verify email
- request/reset password
- TOTP setup/confirm/disable as supported by the approved scope

**Rules:**

- Email normalized/unique according to Identity behavior.
- Passwords handled only by ASP.NET Core Identity hashing.
- Access token is short lived.
- Refresh token rotates on each successful use.
- Refresh replay revokes the affected token chain/session and fails securely.
- Email verification/password reset tokens are never logged.
- MFA secrets are protected at rest and never returned after setup confirmation.

**TDD/verification:**

- Registration/login happy path.
- Invalid password and unverified-email behavior according to configured policy.
- Refresh rotates and old token cannot be reused.
- Explicit replay test proves revocation.
- Logout revokes refresh capability.
- Password reset invalidates relevant sessions according to policy.
- TOTP success/failure tests.
- Sensitive-log capture test proves tokens/passwords are absent.

**Commit:** `feat: add parent authentication and MFA`

---

## Task 5: Families, ownership, children, and server-side resource authorization

**Deliverable:** Parent can create/manage a family and children while cross-family access is impossible.

**Endpoints:**

- `POST /api/v1/families`
- `GET /api/v1/families`
- `GET /api/v1/families/{familyId}`
- membership/parent management required for V1 foundation
- `POST /api/v1/families/{familyId}/children`
- `GET /api/v1/families/{familyId}/children`
- `GET/PATCH /api/v1/children/{childId}`

**Authorization service:**

Use a centralized family-resource authorization abstraction. Controllers/endpoints must not authorize solely from request body IDs.

**TDD/verification:**

- Owner can create/read child.
- Unauthenticated request fails.
- Parent A querying Family B by known GUID is denied without leaking data.
- Parent A cannot mutate Child B by known GUID.
- Membership removal immediately affects authorization.

**Commit:** `feat: add families children and authorization`

---

## Task 6: One-time enrollment tokens and protected device claim

**Deliverable:** Parent can issue a short-lived pairing code for a child and a child device can claim it exactly once using proof-of-possession.

**Parent endpoint:**

- `POST /api/v1/children/{childId}/enrollment-tokens`

Returns a six-digit code to the authorized parent only. Server stores only a secure hash and expiry.

**Device claim endpoint:**

- `POST /api/v1/device-enrollment/claim`

Claim request includes:

- six-digit pairing code
- device-generated ECDSA P-256 public key
- device metadata required by the foundation
- challenge/proof needed to demonstrate possession of the corresponding private key

**Rules:**

- Lifetime: 10 minutes.
- One-time use.
- Parent can create only for owned child.
- Expired/consumed/wrong code fails.
- Rate limit attempts.
- Successful claim atomically creates device/key/refresh credentials and consumes token.

**TDD/verification:**

- Happy-path claim.
- Wrong code.
- Expired code.
- Replay after successful claim.
- Invalid ECDSA proof.
- Concurrent double-claim race: exactly one succeeds.
- Cross-family token creation denied.

**Commit:** `feat: add secure device enrollment`

---

## Task 7: Device JWTs, rotating refresh credentials, and test client

**Deliverable:** Independently authenticated child device sessions using ECDSA proof-of-possession.

**Interfaces:**

- Device access JWT identifies device, child, family, credential/key version, and audience.
- Device refresh credential is random, hashed server-side, rotating.
- Refresh request is signed/proven with the enrolled ECDSA P-256 private key.
- Test client helper generates a real keypair and signs challenges for integration tests.

**Endpoints:**

- `POST /api/v1/device-auth/refresh`
- device session/revocation status endpoint as needed

**TDD/verification:**

- Valid device proof rotates refresh and returns new access token.
- Reusing previous refresh fails and revokes chain/session according to design.
- Copying refresh token without private key cannot authenticate.
- Signature from wrong key fails.
- Wrong audience/device ID fails.

**Commit:** `feat: add device proof-of-possession authentication`

---

## Task 8: Device APIs, push-token registration, revocation, and immediate revocation enforcement

**Deliverable:** Parent can see/revoke devices; authenticated device can update its push registration; revoked devices lose API access immediately.

**Endpoints:**

- Parent: list child/family devices
- Parent: revoke device
- Device: register/update FCM token
- Device: read minimal own identity/capability state

**Critical rule:**

Do not rely only on access-token expiration for revocation. Protected device authorization checks current server-side revocation/credential status so revoked devices fail immediately.

**TDD/verification:**

- Device registers FCM token.
- Token update is idempotent.
- Parent can list owned device.
- Parent cannot list/revoke another family's device.
- After parent revokes device, the same previously valid access JWT immediately receives 401/403 on protected device API.
- Device refresh also fails after revocation.

**Commit:** `feat: add device lifecycle and immediate revocation`

---

## Task 9: Transactional outbox, worker, and FCM wake-up transport

**Deliverable:** Domain/application intent requiring push is committed transactionally and delivered asynchronously with safe retries.

**Components:**

- `IOutboxWriter`
- outbox entity/state machine
- background dispatcher in `Harbor.Worker`
- `IPushTransport`
- FCM implementation
- fake transport for integration tests

**Rules:**

- Business transaction and outbox insertion commit together.
- Dispatcher claims messages safely under concurrency.
- Delivery is at-least-once.
- Retries use bounded backoff.
- Permanent invalid FCM token marks/deactivates registration appropriately.
- Payload carries identifiers/version hints, not unnecessary child data.
- Duplicate dispatch does not create duplicate domain mutation.

**TDD/verification:**

- Rollback proves no orphan push intent.
- Successful transaction creates one outbox row.
- Two workers cannot process the same claimed row concurrently as separate successful effects.
- Transient failure retries.
- Permanent invalid token handled.
- No token/PII leakage in logs.

**Commit:** `feat: add transactional outbox and FCM worker`

---

## Task 10: Security controls, audit guarantees, rate limiting, and sensitive-log tests

**Deliverable:** Foundation endpoints meet the architecture's security baseline before mobile work depends on them.

**Implement:**

- Rate limiting for login/register/password-reset, enrollment-code generation/claim, device refresh, and other abuse-sensitive foundation paths.
- Immutable audit events for registration/security changes, family membership changes, child creation/updates where relevant, enrollment, device credential rotation, FCM registration changes, and revocation.
- Correlation IDs.
- Sensitive-field redaction/logging conventions.
- Secure default headers/configuration appropriate to API deployment.

**TDD/verification:**

- Rate limit triggers deterministic 429.
- Audit event exists for security-sensitive actions.
- Failed unauthorized cross-family attempts are observable without leaking protected content.
- Captured logs contain no password, refresh token, pairing code, reset token, TOTP secret, private key, or raw FCM credential where disallowed.

**Commit:** `security: harden Harbor platform foundation`

---

## Task 11: Developer experience, observability, Docker images, CI, and migration checks

**Deliverable:** Any contributor/Codex worker can build/test the foundation locally and CI blocks unsafe changes.

**Create/update:**

- Dockerfiles for API/worker
- local compose stack
- `.env.example` containing names only, never secrets
- OpenTelemetry tracing/metrics/log correlation
- Application Insights exporter configuration for deployed environments
- GitHub Actions PR workflow
- migration validation script/workflow
- architecture dependency tests
- README developer commands

**PR pipeline:**

- restore/build
- formatting/static analysis
- unit tests
- integration tests with PostgreSQL
- architecture tests
- migration apply-from-zero test
- secret/security scan available to repo
- Docker build

**TDD/verification:**

- Architecture tests enforce Domain/Application/Infrastructure/API dependency direction.
- Container image boots and health endpoint responds.
- Fresh database reaches current migration.
- CI configuration is exercised on PR before merge.

**Commit:** `ci: add Harbor platform build and observability`

---

## Task 12: Azure staging infrastructure, staging smoke flow, and Subproject 1 acceptance gate

**Deliverable:** Platform Foundation runs in staging and proves the complete trust chain before Subproject 2 starts.

**Azure baseline:**

- Azure Container Apps for API and worker
- Azure Key Vault references for secrets
- Application Insights/OpenTelemetry
- Supabase PostgreSQL connection via secret injection
- Azure SignalR may be provisioned now or reserved for the Parent-app subproject; do not build unused realtime application behavior prematurely
- deployment workflow from `main` to staging

**Staging acceptance flow:**

1. Register Parent A.
2. Verify/authenticate Parent A.
3. Create Family A.
4. Create Child A.
5. Generate six-digit enrollment code.
6. Generate ECDSA P-256 keypair in test client.
7. Claim device before the 10-minute expiry using valid proof.
8. Authenticate/refresh as the device using the device private key.
9. Register an FCM token through the device API.
10. Rotate device refresh credential and prove replay of the old credential fails.
11. Register Parent B / Family B and prove Parent A cannot access Family B or its children/devices even with known IDs.
12. Revoke Device A as Parent A.
13. Reuse Device A's still-unexpired access JWT against a protected endpoint and prove immediate rejection.
14. Verify enrollment, refresh, FCM-registration, and revocation audit events exist.
15. Verify expected outbox processing and worker health.
16. Verify health/telemetry surfaces are green and logs do not contain sensitive credentials.

**Acceptance test command:**

Provide a repeatable staging smoke command/script whose non-zero exit fails deployment promotion.

**Commit:** `deploy: add Harbor staging acceptance gate`

---

## Subproject 1 Definition of Done

Platform Foundation is complete only when all of the following are true:

- .NET 10 solution and PostgreSQL schema build from a clean checkout.
- Parent authentication supports verification, secure refresh rotation, replay handling, reset, and MFA foundation.
- Families/children have server-side ownership authorization with explicit cross-family tests.
- Enrollment code is six digits, one-time, hashed at rest, and expires in 10 minutes.
- Device holds an ECDSA P-256 private key and proves possession during device authentication.
- Parent credentials are never installed on the child device.
- Device refresh credentials rotate and are hashed server-side.
- Device revocation blocks an already-issued device access JWT immediately on protected device APIs.
- FCM registration is device scoped.
- Outbox commits with application state and dispatch is idempotent/at-least-once safe.
- Rate limiting, audit, and sensitive-log protections have automated tests.
- Docker and CI build/test/migration checks are operating.
- Azure staging deploys API/worker and the complete acceptance flow passes.

## What does not belong in Subproject 1

Do not expand this plan into production mobile UI or later product features. Specifically defer to their own Superpowers cycles:

- Parent Compose application
- Child Compose application
- DevicePolicyManager/Lock Task/Kid Space
- Screen-time policy engine
- UsageStats enforcement
- Location/map UI
- Get Help product flow beyond any minimal foundation event type needed later
- VpnService/Harbor Browser
- Activity dashboards
- Notification safety analysis
- Billing/admin portal
- Full production-launch hardening

Those are Subprojects 2–12 in the production architecture roadmap.
