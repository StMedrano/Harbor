# Harbor Vercel + Supabase Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Harbor Subproject 1: the secure Supabase backend and shared-contract foundation consumed by both native Parent Android and the later full-parity Parent PWA, including Auth/MFA, family isolation, child-device trust, desired state, Android FCM, PWA Web Push, Realtime authorization, and release-blocking security tests.

**Architecture:** Supabase is Harbor's backend source of truth. Parent clients use the same Supabase Auth tenant, direct Data API access only where RLS fully expresses authorization, and Edge Functions for privileged/security-sensitive work; child devices use a separate Supabase Auth identity plus Android Keystore ECDSA P-256 proof-of-possession. Vercel remains frontend-only and the full Next.js Parent PWA is deferred to Subproject 2B; this plan creates only the backend interfaces, shared contracts, Web Push support, and environment boundaries that PWA work will consume.

**Tech Stack:** Supabase Auth, PostgreSQL 17+, Row Level Security, Supabase Edge Functions (TypeScript/Deno), Supabase Realtime private channels, Supabase CLI migrations, pgTAP, TypeScript shared contracts, Firebase Cloud Messaging, standard Web Push/VAPID, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-03-harbor-vercel-supabase-production-architecture-design.md`

## Global Constraints

- Native Parent Android and Child Android remain Kotlin + Jetpack Compose; minimum Android 10 / API 29.
- Parent PWA is a later full-parity client hosted on one Vercel project rooted at `apps/web`; Vercel is frontend-only for Harbor business architecture.
- Supabase project `bfvybxkjxilntjgndsrm` is development-only unless the user explicitly changes that designation.
- Parent Android and Parent PWA use the same Supabase Auth tenant/account and the same family authorization model.
- Parent Auth baseline is email/password + email verification + password recovery + TOTP MFA.
- Sensitive parent actions require server-enforced AAL2; the approved high-risk step-up window is 15 minutes.
- Child-device transport identity uses a separate Supabase Auth anonymous/device account; it never reuses parent credentials.
- Protected child operations require both a valid bound device session and ECDSA P-256/SHA-256 proof-of-possession.
- Every exposed Harbor table has RLS enabled before client access is granted; `TO authenticated` is never the complete authorization rule.
- Authorization never trusts editable `raw_user_meta_data` / `user_metadata`.
- Secret/service-role Supabase credentials, VAPID private keys, Firebase service credentials, pairing peppers, and child private keys never ship to clients or source control.
- Revoked child devices are denied immediately by current backend state even when an access token remains unexpired.
- FCM and Web Push are transports, never sources of truth.
- Web Push subscription requires explicit parent opt-in; VAPID private-key operations run only in Supabase Edge Functions.
- Realtime is parent UX acceleration, not child policy authority.
- Delivery is at-least-once; commands and notifications must be idempotent.
- Full child policy enforcement remains local/offline and is outside this subproject except for desired-state transport foundations.
- Migration filenames are created with `supabase migration new <name>`; do not invent timestamped migration filenames.
- Run database tests, `supabase db lint`, Supabase security advisors, and Supabase performance advisors after DDL/RLS changes.
- Development, staging, and production mappings must be explicit; production must not silently point at the development Supabase project.
- This plan does **not** build the full Next.js Parent PWA; that work belongs to Subproject 2B.

## Review Focus

- **Cross-family IDOR/BOLA:** a valid Parent A session using a known Family B/Child B/device UUID must still receive no unauthorized data or mutation capability.
- **Stale MFA:** a high-risk operation with `aal2` but a most-recent MFA event older than 15 minutes must return `MFA_REQUIRED` rather than succeeding.
- **Device credential theft:** a copied child Auth JWT without the enrolled P-256 private key must fail every protected device operation.
- **Replay/revocation race:** a reused signed nonce or a newly revoked device with an otherwise-valid JWT/signature must be rejected immediately.
- **Duplicate/invalid push delivery:** repeated outbox attempts must not duplicate domain effects, and permanently invalid Web Push subscriptions must be disabled/removed without blocking other transports.

---

## File Map

Migration paths use `<CLI-generated>` because the Supabase CLI creates the timestamped prefix.

```text
supabase/
├── config.toml
├── seed.sql
├── migrations/
│   ├── <CLI-generated>_core_family_schema.sql
│   ├── <CLI-generated>_family_rls.sql
│   ├── <CLI-generated>_staff_authorization.sql
│   ├── <CLI-generated>_device_security.sql
│   ├── <CLI-generated>_desired_state_commands.sql
│   ├── <CLI-generated>_parent_web_push.sql
│   ├── <CLI-generated>_notification_outbox.sql
│   └── <CLI-generated>_realtime_authorization.sql
├── tests/
│   ├── rls_family_access.test.sql
│   ├── staff_authorization.test.sql
│   ├── device_security.test.sql
│   ├── desired_state.test.sql
│   ├── web_push.test.sql
│   ├── outbox.test.sql
│   └── realtime_authorization.test.sql
└── functions/
    ├── _shared/
    │   ├── clients.ts
    │   ├── errors.ts
    │   ├── responses.ts
    │   ├── auth.ts
    │   ├── aal.ts
    │   ├── crypto.ts
    │   ├── device-proof.ts
    │   ├── notification.ts
    │   └── web-push.ts
    ├── create-family/index.ts
    ├── create-device-pairing/index.ts
    ├── device-claim/index.ts
    ├── device-sync/index.ts
    ├── register-fcm/index.ts
    ├── revoke-device/index.ts
    ├── update-device-state/index.ts
    ├── register-web-push/index.ts
    ├── remove-web-push/index.ts
    └── dispatch-outbox/index.ts

packages/contracts/
├── package.json
├── tsconfig.json
├── src/
│   ├── index.ts
│   └── v1/
│       ├── family.ts
│       ├── device.ts
│       ├── auth.ts
│       ├── errors.ts
│       └── notifications.ts
└── test/contracts.test.ts

tests/functions/
├── shared-auth.test.ts
├── aal.test.ts
├── create-family.test.ts
├── device-claim.test.ts
├── device-proof.test.ts
├── device-sync.test.ts
├── revoke-device.test.ts
├── web-push.test.ts
├── dispatch-outbox.test.ts
└── security-regression.test.ts

tests/end-to-end/
└── platform-foundation.test.ts

.github/workflows/harbor-foundation-ci.yml
docs/runbooks/supabase-development.md
docs/runbooks/environment-mapping.md
docs/runbooks/device-enrollment.md
docs/runbooks/notifications.md
```

---

### Task 1: Bootstrap Supabase workspace, Auth baseline, and foundation CI

**Files:**
- Create/verify: `supabase/config.toml`
- Create: `supabase/seed.sql`
- Create: `.github/workflows/harbor-foundation-ci.yml`
- Create: `docs/runbooks/supabase-development.md`
- Create: `docs/runbooks/environment-mapping.md`
- Modify: `.gitignore` if required

**Interfaces:**
- Consumes: development project ref `bfvybxkjxilntjgndsrm`.
- Produces: repeatable local Supabase start/reset/test workflow; documented Auth configuration; CI entrypoint; explicit dev/staging/prod mapping contract.

- [ ] **Step 1: Discover the installed Supabase CLI before configuration**

Run `supabase --version`, `supabase --help`, `supabase auth --help` when present, and the relevant subcommand `--help`. Record the tested CLI version and commands in `docs/runbooks/supabase-development.md`.

- [ ] **Step 2: Initialize/link without overwriting an existing project config**

Run `supabase init` only when `supabase/config.toml` is absent, then `supabase link --project-ref bfvybxkjxilntjgndsrm` and `supabase migration list`.

Expected: Harbor has no application migrations in the linked development project before this plan begins.

- [ ] **Step 3: Configure the local Auth baseline**

Using current Supabase CLI/config support, enable email/password flows, email confirmation/recovery behavior appropriate for local testing, TOTP MFA capability, and anonymous sign-in for child-device transport identities. Document dashboard-only production settings instead of inventing unsupported `config.toml` keys.

- [ ] **Step 4: Document environment boundaries**

`docs/runbooks/environment-mapping.md` must define the required mapping fields: Vercel environment, Supabase project/ref, Supabase URL, publishable key identifier, allowed redirect/callback origins, Web Push VAPID configuration identifier, and FCM credential identifier. It must state that the development ref is forbidden for production.

- [ ] **Step 5: Add CI baseline**

`.github/workflows/harbor-foundation-ci.yml` starts local Supabase, applies migrations, runs `supabase db lint --level error`, runs `supabase test db`, runs Deno function tests, runs shared-contract tests, and runs secret scanning. PR CI must not require remote production credentials.

- [ ] **Step 6: Verify clean local startup/reset**

Run `supabase start` then `supabase db reset`.

Expected: clean reset succeeds with Auth enabled and no Harbor schema errors.

- [ ] **Step 7: Commit**

```bash
git add supabase/config.toml supabase/seed.sql .github/workflows/harbor-foundation-ci.yml docs/runbooks/supabase-development.md docs/runbooks/environment-mapping.md .gitignore
git commit -m "chore: bootstrap Harbor Supabase foundation"
```

---

### Task 2: Establish versioned shared parent/backend contracts

**Files:**
- Create: `packages/contracts/package.json`
- Create: `packages/contracts/tsconfig.json`
- Create: `packages/contracts/src/index.ts`
- Create: `packages/contracts/src/v1/family.ts`
- Create: `packages/contracts/src/v1/device.ts`
- Create: `packages/contracts/src/v1/auth.ts`
- Create: `packages/contracts/src/v1/errors.ts`
- Create: `packages/contracts/src/v1/notifications.ts`
- Test: `packages/contracts/test/contracts.test.ts`

**Interfaces:**
- Produces `FamilyV1`, `FamilyMemberV1`, `ChildV1`, `DevicePublicStateV1`, `AalLevel`, `HarborErrorCode`, `NotificationRouteRefV1`, and `ContractVersion = 1`.
- `HarborErrorCode` includes `AUTH_REQUIRED | MFA_REQUIRED | FORBIDDEN | VALIDATION_FAILED | DEVICE_REVOKED | DEVICE_OFFLINE | STALE_VERSION | REPLAY_REJECTED`.
- `NotificationRouteRefV1` contains only `{ version: 1, kind: string, familyId?: string, childId?: string, deviceId?: string, resourceId?: string }` and no sensitive message body.

- [ ] **Step 1: Write failing contract tests**

Tests assert `ContractVersion === 1`, enum/string literal stability for the error codes above, and that the notification route-reference type contains identifiers/routes only and no location/message-content field.

- [ ] **Step 2: Run the tests to verify they fail**

Run the repository's chosen TypeScript test command for `packages/contracts/test/contracts.test.ts`.

Expected: FAIL because the contract exports do not yet exist.

- [ ] **Step 3: Implement the V1 contract package**

Keep each file focused on one domain. Do not encode authorization decisions in the contract package; it defines shapes and stable semantic names only.

- [ ] **Step 4: Run contract tests and typecheck**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add packages/contracts
git commit -m "feat: add Harbor shared contract foundation"
```

---

### Task 3: Create core family schema and private staff-authorization boundary

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_core_family_schema.sql`
- Create via CLI: `supabase/migrations/<CLI-generated>_staff_authorization.sql`
- Modify: `supabase/seed.sql`
- Test: `supabase/tests/rls_family_access.test.sql`
- Test: `supabase/tests/staff_authorization.test.sql`

**Interfaces:**
- Produces public tables: `profiles`, `families`, `family_members`, `children`, `devices_public`.
- Produces private table: `private.staff_authorizations`.
- `family_members.role`: `owner | parent` for V1.
- `family_members.status`: `active | invited | removed`.
- `devices_public.supervision_mode`: `unknown | standard | full`.
- `devices_public.status`: `active | revoked`.
- `private.staff_authorizations.role`: `support | admin`; no client grants.

- [ ] **Step 1: Write failing schema tests**

Assert PK/FK constraints, unique `(family_id,user_id)`, family-scoped children, same-family child/device integrity, and no `anon`/`authenticated` privileges on `private.staff_authorizations`.

- [ ] **Step 2: Create the core migration with `supabase migration new core_family_schema`**

Use UUID PKs, `timestamptz`, explicit check constraints for the values above, and indexes on foreign keys used by normal lookups or RLS.

- [ ] **Step 3: Create the staff-authorization migration with `supabase migration new staff_authorization`**

Create the `private` schema if not already present, revoke default client access, and create the minimal staff authorization record without exposing cross-family data.

- [ ] **Step 4: Add safe profile creation behavior**

`profiles.id` references `auth.users(id)`. Display/profile fields may be copied from signup metadata, but family/staff roles are never derived from editable metadata.

- [ ] **Step 5: Run DB reset/tests/lint**

Run `supabase db reset`, `supabase test db`, and `supabase db lint --level error`.

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add supabase/migrations supabase/seed.sql supabase/tests/rls_family_access.test.sql supabase/tests/staff_authorization.test.sql
git commit -m "feat: add Harbor family and staff authorization schema"
```

---

### Task 4: Enforce family RLS and cross-principal isolation

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_family_rls.sql`
- Modify: `supabase/tests/rls_family_access.test.sql`
- Modify: `supabase/tests/staff_authorization.test.sql`

**Interfaces:**
- Produces parent-safe direct-read policies for approved public resources.
- Direct writes reserved for Edge Functions remain denied.
- Anonymous child-device Auth identities and normal parents receive no staff/private-table access.

- [ ] **Step 1: Write failing pgTAP tests for Parent A, Parent B, a device anonymous Auth user, and a non-staff parent**

Assert Parent A can read Family A/Child A; Parent A cannot read Family B/Child B by known UUID; Parent A cannot mutate Family B; device Auth cannot directly read family resources merely because it has the `authenticated` Postgres role; and a normal parent cannot read `private.staff_authorizations`.

- [ ] **Step 2: Enable RLS on every exposed Harbor table before grants**

Policies use `(select auth.uid())`, active family membership, and role checks. `TO authenticated` alone is never accepted.

- [ ] **Step 3: Add policy-performance indexes**

At minimum index `family_members(user_id,family_id)` and every family/child foreign key used by policy predicates.

- [ ] **Step 4: Run DB tests and lint**

Expected: all cross-family/cross-principal tests pass.

- [ ] **Step 5: Run Supabase security/performance advisors against the linked development project after the migration is intentionally applied**

Expected: no exposed Harbor table missing RLS; review all Harbor-relevant advisor findings before continuing.

- [ ] **Step 6: Commit**

```bash
git add supabase/migrations supabase/tests
git commit -m "feat: enforce Harbor family row level security"
```

---

### Task 5: Build shared Edge Function Auth, role, and recent-AAL2 enforcement

**Files:**
- Create: `supabase/functions/_shared/clients.ts`
- Create: `supabase/functions/_shared/errors.ts`
- Create: `supabase/functions/_shared/responses.ts`
- Create: `supabase/functions/_shared/auth.ts`
- Create: `supabase/functions/_shared/aal.ts`
- Test: `tests/functions/shared-auth.test.ts`
- Test: `tests/functions/aal.test.ts`

**Interfaces:**
- `requireParent(req: Request): Promise<ParentContext>` returns `{ userId: string, accessToken: string, aal: "aal1" | "aal2", amr: readonly AuthMethodRef[] }`.
- `requireFamilyRole(ctx: ParentContext, familyId: string, allowedRoles: readonly ("owner" | "parent")[]): Promise<void>`.
- `requireRecentAal2(ctx: ParentContext, nowEpochSeconds: number, maxAgeSeconds?: number): void` defaults `maxAgeSeconds` to `900`.
- `requireStaffRole(ctx: ParentContext, allowedRoles: readonly ("support" | "admin")[]): Promise<void>` requires server-controlled staff state plus recent AAL2.
- `jsonError(code: HarborErrorCode, status: number, message: string): Response`.

- [ ] **Step 1: Write failing tests for missing/invalid Auth and anonymous-device caller masquerading as parent**

Expected error: `AUTH_REQUIRED` or `FORBIDDEN` as appropriate.

- [ ] **Step 2: Write failing tests for AAL2 recency**

Assert `aal1` fails; `aal2` with most-recent MFA `amr` event at 899 seconds succeeds; 901 seconds fails with `MFA_REQUIRED`; missing verifiable MFA timestamp fails closed for high-risk operations.

- [ ] **Step 3: Implement request-scoped Supabase Auth validation**

Use the caller Authorization header for user-context queries; never reuse a global user-scoped client across requests.

- [ ] **Step 4: Implement database-backed family/staff role checks**

Authorization comes from current database state, not caller-supplied metadata.

- [ ] **Step 5: Implement `requireRecentAal2` against verified JWT `aal` + most-recent MFA `amr` timestamp**

The maximum accepted age is exactly 900 seconds for the approved high-risk window.

- [ ] **Step 6: Run function tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add supabase/functions/_shared tests/functions/shared-auth.test.ts tests/functions/aal.test.ts
git commit -m "feat: add Harbor auth and AAL2 enforcement"
```

---

### Task 6: Implement idempotent atomic family creation

**Files:**
- Create: `supabase/functions/create-family/index.ts`
- Test: `tests/functions/create-family.test.ts`
- Create via CLI if required: service-only transactional helper migration

**Interfaces:**
- Request: `{ name: string, idempotencyKey: string }`.
- Response: `{ familyId: string, name: string, role: "owner" }`.

- [ ] **Step 1: Write failing tests**

Cover unauthenticated caller, anonymous-device caller, blank/oversized family name, duplicate idempotency key, and successful family + owner membership + audit creation.

- [ ] **Step 2: Implement parent validation and normalized input constraints**

Do not require AAL2 for ordinary family creation unless later threat-model work changes the spec.

- [ ] **Step 3: Make family creation atomic and idempotent**

If a DB helper is required, keep it service-only, revoke `EXECUTE` from `PUBLIC`, `anon`, and `authenticated`, and prefer `SECURITY INVOKER` with backend privileges.

- [ ] **Step 4: Run function + RLS regression tests**

Expected: repeated idempotency key returns/reuses the original domain result and does not create a second family.

- [ ] **Step 5: Commit**

```bash
git add supabase/functions/create-family tests/functions/create-family.test.ts supabase/migrations
git commit -m "feat: add secure family creation"
```

---

### Task 7: Add child-device enrollment and one-time pairing

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_device_security.sql`
- Create: `supabase/functions/create-device-pairing/index.ts`
- Create: `supabase/functions/device-claim/index.ts`
- Create: `docs/runbooks/device-enrollment.md`
- Test: `supabase/tests/device_security.test.sql`
- Test: `tests/functions/device-claim.test.ts`

**Interfaces:**
- Private tables: `private.device_security`, `private.device_enrollment_tokens`; Task 8 adds `private.device_request_nonces`.
- Pairing request: `{ childId: string }`.
- Pairing response: `{ code: string, expiresAt: string }`; code is exactly six digits, lifetime exactly 10 minutes.
- Claim request: `{ code: string, publicKeySpki: string, device: { displayName: string, model: string, androidVersion: string, supervisionMode: "unknown" | "standard" | "full" } }`.
- Claim response: `{ deviceId: string, familyId: string, childId: string }`.

- [ ] **Step 1: Write failing tests for pairing/claim invariants**

Cover six-digit format, 10-minute expiry, single use, wrong family/child, repeated claim, brute-force attempt accounting/lockout policy chosen in the migration, malformed SPKI, and already-bound device Auth identity.

- [ ] **Step 2: Create private device-security schema objects**

Revoke `PUBLIC`, `anon`, and `authenticated` access to private tables.

- [ ] **Step 3: Store only keyed pairing-code digests**

Use HMAC-SHA-256 with a server-side pairing pepper secret; never persist the raw six-digit code.

- [ ] **Step 4: Require the caller's separate child-device Supabase Auth identity**

The device Auth principal still receives no direct family Data API authorization.

- [ ] **Step 5: Atomically bind Auth subject + P-256 SPKI public key + public device row and consume the token**

Write the enrollment audit event in the same logical operation.

- [ ] **Step 6: Run DB/function tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add supabase/migrations supabase/functions/create-device-pairing supabase/functions/device-claim supabase/tests/device_security.test.sql tests/functions/device-claim.test.ts docs/runbooks/device-enrollment.md
git commit -m "feat: add Harbor device enrollment"
```

---

### Task 8: Require device proof-of-possession and replay protection

**Files:**
- Create: `supabase/functions/_shared/crypto.ts`
- Create: `supabase/functions/_shared/device-proof.ts`
- Create via CLI migration: `private.device_request_nonces`
- Test: `tests/functions/device-proof.test.ts`
- Modify: `supabase/tests/device_security.test.sql`

**Interfaces:**
- Canonical signature input: `METHOD + "\n" + OPERATION + "\n" + DEVICE_ID + "\n" + BODY_SHA256 + "\n" + UNIX_TIMESTAMP + "\n" + NONCE`.
- Required headers: `X-Harbor-Device-Id`, `X-Harbor-Timestamp`, `X-Harbor-Nonce`, `X-Harbor-Signature`.
- `requireDeviceProof(req: Request, operation: string): Promise<DeviceContext>` returns `{ deviceId: string, familyId: string, childId: string, authUserId: string }`.
- Accepted timestamp skew: 5 minutes.

- [ ] **Step 1: Write failing proof tests**

Cover valid signature, wrong key, modified body, wrong device ID, timestamp outside ±5 minutes, reused nonce, copied JWT without signature, and revoked device.

- [ ] **Step 2: Bind JWT `sub` to `private.device_security.auth_user_id`**

Reject a valid JWT belonging to any other device/user.

- [ ] **Step 3: Verify ECDSA P-256/SHA-256 against stored SPKI public key**

Reject malformed/unsupported key material without falling back to token-only authorization.

- [ ] **Step 4: Atomically claim nonce before accepting the protected operation**

A duplicate `(device_id, nonce)` maps to `REPLAY_REJECTED`.

- [ ] **Step 5: Read current `revoked_at` on every protected request**

Revocation wins even when JWT/signature/timestamp are otherwise valid.

- [ ] **Step 6: Run tests and commit**

```bash
git add supabase/functions/_shared/crypto.ts supabase/functions/_shared/device-proof.ts supabase/migrations supabase/tests/device_security.test.sql tests/functions/device-proof.test.ts
git commit -m "feat: enforce Harbor device proof of possession"
```

---

### Task 9: Add desired state, idempotent commands, FCM registration, signed sync, and immediate revocation

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_desired_state_commands.sql`
- Create: `supabase/functions/device-sync/index.ts`
- Create: `supabase/functions/register-fcm/index.ts`
- Create: `supabase/functions/revoke-device/index.ts`
- Create: `supabase/functions/update-device-state/index.ts`
- Test: `supabase/tests/desired_state.test.sql`
- Test: `tests/functions/device-sync.test.ts`
- Test: `tests/functions/revoke-device.test.ts`

**Interfaces:**
- Private tables: `device_desired_state`, `device_commands`, `device_fcm_registrations`.
- Desired-state version is monotonically increasing per device.
- Device sync request may include `{ acknowledgedDesiredStateVersion?: number, appliedCommandIds?: string[] }`.
- Device sync response: `{ desiredState: unknown, desiredStateVersion: number, commands: DeviceCommandV1[] }`.
- `DeviceCommandV1` contains stable `id`, `kind`, `idempotencyKey`, `createdAt`, optional `expiresAt`, and minimal payload.
- `revoke-device` requires owner/authorized-parent role plus `requireRecentAal2(..., 900)`.

- [ ] **Step 1: Write failing DB/function tests**

Cover monotonic desired-state versions, stale version update rejection, duplicate command idempotency key, duplicate acknowledgement, private FCM token visibility, signed sync, and revoked-device denial.

- [ ] **Step 2: Implement signed `device-sync` with `requireDeviceProof`**

Expired commands are omitted; acknowledgement updates are idempotent.

- [ ] **Step 3: Implement signed FCM registration**

FCM tokens live only in private backend state and can rotate without exposing previous tokens.

- [ ] **Step 4: Implement parent-authorized desired-state update**

Each accepted state change increments version exactly once and records wake intent for later outbox dispatch.

- [ ] **Step 5: Implement AAL2-protected device revocation**

Revocation invalidates private FCM registration/desired-state access and writes audit state; the next device call fails immediately.

- [ ] **Step 6: Run tests and commit**

```bash
git add supabase/migrations supabase/functions/device-sync supabase/functions/register-fcm supabase/functions/revoke-device supabase/functions/update-device-state supabase/tests/desired_state.test.sql tests/functions/device-sync.test.ts tests/functions/revoke-device.test.ts
git commit -m "feat: add Harbor desired state and device revocation"
```

---

### Task 10: Add parent Web Push subscription lifecycle and VAPID boundary

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_parent_web_push.sql`
- Create: `supabase/functions/register-web-push/index.ts`
- Create: `supabase/functions/remove-web-push/index.ts`
- Create: `supabase/functions/_shared/web-push.ts`
- Test: `supabase/tests/web_push.test.sql`
- Test: `tests/functions/web-push.test.ts`
- Modify: `docs/runbooks/notifications.md`

**Interfaces:**
- Private table: `private.parent_web_push_subscriptions`.
- Subscription identity: `(user_id, client_installation_id, endpoint_hash)` with multiple installations allowed per parent.
- Register request: `{ clientInstallationId: string, endpoint: string, keys: { p256dh: string, auth: string } }`.
- Remove request: `{ clientInstallationId: string, endpoint?: string }`.
- `sendWebPush(subscription: WebPushSubscriptionRecord, route: NotificationRouteRefV1): Promise<PushDeliveryResult>`.
- VAPID public key is browser-safe; VAPID private key is read only from Supabase backend secrets.

- [ ] **Step 1: Write failing subscription tests**

Cover unauthenticated caller, another user's installation ID, multiple subscriptions for one parent, endpoint/key rotation, duplicate registration idempotency, explicit removal, and absence of client grants on the private table.

- [ ] **Step 2: Create the Web Push schema migration**

Do not expose raw subscriptions through the parent Data API. Persist only fields needed for delivery/lifecycle and timestamps/status.

- [ ] **Step 3: Implement authenticated register/remove Edge Functions**

The caller may mutate only subscriptions attached to `auth.uid()`.

- [ ] **Step 4: Implement the server-only Web Push adapter**

Use a standards-compatible NPM Web Push/VAPID implementation supported by the current Supabase Edge Runtime, pinned through the repository's Deno dependency configuration/lockfile. Keep `VAPID_PRIVATE_KEY` server-only.

- [ ] **Step 5: Write delivery adapter tests with network mocked**

Assert payload contains only `NotificationRouteRefV1`; 404/410 invalid-subscription responses are classified as permanent; transient 5xx/network failures are retryable.

- [ ] **Step 6: Run tests and commit**

```bash
git add supabase/migrations supabase/functions/register-web-push supabase/functions/remove-web-push supabase/functions/_shared/web-push.ts supabase/tests/web_push.test.sql tests/functions/web-push.test.ts docs/runbooks/notifications.md
git commit -m "feat: add Harbor parent web push foundation"
```

---

### Task 11: Build durable multi-transport notification outbox and dispatcher

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_notification_outbox.sql`
- Create: `supabase/functions/_shared/notification.ts`
- Create: `supabase/functions/dispatch-outbox/index.ts`
- Test: `supabase/tests/outbox.test.sql`
- Test: `tests/functions/dispatch-outbox.test.ts`
- Modify: `docs/runbooks/notifications.md`

**Interfaces:**
- Outbox transports: `fcm | web_push`.
- Delivery statuses: `pending | processing | sent | retry | dead_letter`.
- Each row has stable event/idempotency identity, target reference, minimal route payload, attempt count, next-attempt time, last error category, and timestamps.
- `dispatchOne(outboxId: string): Promise<DeliveryOutcome>` is idempotent with respect to already-sent rows.

- [ ] **Step 1: Write failing DB tests for durable outbox state transitions**

Cover duplicate event/idempotency key, safe claiming, retry scheduling, already-sent no-op, and dead-letter transition.

- [ ] **Step 2: Write failing dispatcher tests for FCM + Web Push fanout**

A single logical event may produce separate transport rows; one transport failure must not roll back a successful other transport after durable outbox creation.

- [ ] **Step 3: Implement transactional outbox helpers**

Domain changes record push intent before external delivery is considered successful.

- [ ] **Step 4: Implement dispatch/retry classification**

Permanent Web Push 404/410 disables the invalid subscription; transient errors schedule retry; duplicate dispatcher invocation does not duplicate domain effects.

- [ ] **Step 5: Verify minimal payload policy**

Tests reject outbox route payload fixtures that include raw location/message content when a route/reference is sufficient.

- [ ] **Step 6: Run tests and commit**

```bash
git add supabase/migrations supabase/functions/_shared/notification.ts supabase/functions/dispatch-outbox supabase/tests/outbox.test.sql tests/functions/dispatch-outbox.test.ts docs/runbooks/notifications.md
git commit -m "feat: add Harbor durable notification outbox"
```

---

### Task 12: Authorize private family Realtime channels

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_realtime_authorization.sql`
- Test: `supabase/tests/realtime_authorization.test.sql`

**Interfaces:**
- Topic format: `family:{family_id}`.
- Parent clients may receive approved family broadcasts only when `auth.uid()` is an active member of that family.
- Device anonymous Auth users and parents from other families cannot join/read another family's private topic.

- [ ] **Step 1: Write failing authorization tests**

Cover active Parent A on Family A, removed Parent A, Parent B on Family A topic, device anonymous Auth on Family A topic, malformed topic, and family UUID not found.

- [ ] **Step 2: Implement `realtime.messages` RLS for private family topics**

Use the current Supabase Realtime authorization model and `realtime.topic()`; constrain the permitted extension(s) to only those Harbor uses.

- [ ] **Step 3: Do not make Realtime authoritative state**

Database events/broadcast payloads contain only non-sensitive change notification fields sufficient for clients to refresh authoritative data.

- [ ] **Step 4: Run DB tests and advisors**

Expected: only active family members can subscribe to their own family topic.

- [ ] **Step 5: Commit**

```bash
git add supabase/migrations supabase/tests/realtime_authorization.test.sql
git commit -m "feat: authorize Harbor family realtime channels"
```

---

### Task 13: Add release-blocking security regression and Subproject 1 acceptance harness

**Files:**
- Create: `tests/functions/security-regression.test.ts`
- Create: `tests/end-to-end/platform-foundation.test.ts`
- Modify: `.github/workflows/harbor-foundation-ci.yml`
- Modify: `docs/runbooks/environment-mapping.md`
- Modify: `docs/runbooks/supabase-development.md`

**Interfaces:**
- Acceptance harness represents two logical parent clients using the same Parent A Supabase account/session model (`parent-android-sim`, `parent-pwa-sim`) plus Parent B and one child-device Auth identity.
- No full Android or Next.js UI is built in this task; the harness exercises the common backend interfaces those clients will later consume.

- [ ] **Step 1: Write the failing cross-cutting security suite**

Tests must prove: Parent A cannot access Parent B known IDs; device Auth cannot access parent family Data API; copied child JWT without signature fails; replayed nonce fails; revoked device fails with unexpired JWT; stale (>900s) AAL2 high-risk action fails; current AAL2 high-risk action succeeds; private staff state is not client-readable; invalid Web Push subscription cleanup does not block FCM delivery.

- [ ] **Step 2: Write the failing foundation acceptance path**

Exercise: create Parent A family/child → create pairing code → claim device with P-256 key → signed device sync → register FCM → register parent Web Push subscription as the same parent identity → update desired state → create/dispatch dual-transport outbox intent → authorize Family A Realtime → verify Parent B denial → replay rejection → AAL2-protected revocation → immediate device denial → audit verification.

- [ ] **Step 3: Run the full local suite until green**

Run clean `supabase db reset`, database tests/lint, Deno function tests, contract tests, and the end-to-end foundation harness.

Expected: all PASS from a clean reset.

- [ ] **Step 4: Apply intentional migrations to the designated development environment and run Supabase security + performance advisors**

Do not use production. Record/remediate Harbor-relevant findings before declaring the task complete.

- [ ] **Step 5: Verify the linked migration list matches repository history**

Expected: every applied Harbor migration is represented in `supabase/migrations/` and no dashboard-only production schema drift exists.

- [ ] **Step 6: Add CI gates for the full foundation suite and environment-mapping check**

CI fails if a production web mapping references development project `bfvybxkjxilntjgndsrm`, if contract tests fail, or if any backend/security suite fails.

- [ ] **Step 7: Update runbooks with the verified commands and non-secret configuration checklist**

Include Supabase Auth configuration, pairing pepper secret name, FCM secret identifiers, VAPID public/private secret placement, redirect-origin checklist, and rollback/reset cautions without recording secret values.

- [ ] **Step 8: Commit**

```bash
git add tests/functions/security-regression.test.ts tests/end-to-end/platform-foundation.test.ts .github/workflows/harbor-foundation-ci.yml docs/runbooks/environment-mapping.md docs/runbooks/supabase-development.md
git commit -m "test: gate Harbor platform foundation release"
```

---

## Subproject 1 Definition of Done

Subproject 1 is complete only when all of the following are true:

- The development Supabase project is linked through reproducible CLI/runbook steps and all Harbor DDL exists in migrations.
- Parent Auth/TOTP MFA capability is configured and recent-AAL2 enforcement is proven server-side for high-risk foundation actions.
- Family/child/device public data is RLS-isolated and cross-family known-ID tests pass.
- Staff authorization state is private, server-controlled, and not implied by parent Auth/profile metadata.
- Child pairing is six-digit, one-time, keyed-digest-only, and expires after 10 minutes.
- Protected device calls require bound Auth identity + P-256 proof; replay and immediate revocation tests pass.
- Desired state is versioned, commands are idempotent, FCM registration is private, and revocation takes effect immediately.
- Parent Web Push subscriptions are private, per-user/per-installation, and VAPID private operations occur only in Supabase.
- Notification outbox is durable, at-least-once, idempotent, and supports both FCM and Web Push independently.
- Private family Realtime authorization allows only active family members.
- Shared V1 contracts exist for the parent/backend foundation and stable backend error semantics.
- Clean-reset database tests, Edge Function tests, contract tests, security regression, and the foundation end-to-end harness are green.
- Supabase security/performance advisors have been reviewed after intentional DDL application.
- Environment mapping prevents Vercel production from silently using the development Supabase project.
- No full Next.js PWA UI has been smuggled into this subproject; Subproject 2B remains independently spec'd/planned.

## Deferred to Later Subprojects

This plan intentionally does **not** implement the full Parent Android UI, Child Android UI, Parent PWA/Next.js UI, screen-time evaluator, Kid Space/DPC enforcement, location-history product flows, web filtering, full Get Help UX, digital-activity product surfaces, safety-monitoring product features, billing, or production rollout. Those remain in their dedicated roadmap subprojects.
