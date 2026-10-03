# Harbor Vercel + Supabase Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Harbor Subproject 1: the secure Supabase backend and shared-contract foundation used by native Parent Android and the later full-parity Parent PWA, including Auth/MFA, family isolation, child-device trust, desired state, Android FCM, PWA Web Push, Realtime authorization, and release-blocking security tests.

**Architecture:** Supabase is Harbor's backend source of truth. Parent clients use the same Supabase Auth tenant, direct Data API access only where RLS fully expresses authorization, and Edge Functions for privileged/security-sensitive work; child devices use a separate Supabase Auth identity plus Android Keystore ECDSA P-256 proof-of-possession. Vercel remains frontend-only and the full Next.js Parent PWA is deferred to Subproject 2B; this plan creates only the backend interfaces, shared contracts, Web Push support, and environment boundaries that PWA work will consume.

**Tech Stack:** Supabase Auth, PostgreSQL 17+, Row Level Security, Supabase Edge Functions (TypeScript/Deno), Supabase Realtime private channels, Supabase CLI migrations, pgTAP, TypeScript shared contracts, Firebase Cloud Messaging, standard Web Push/VAPID, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-03-harbor-vercel-supabase-production-architecture-design.md`

## Global Constraints

- Native Parent Android and Child Android remain Kotlin + Jetpack Compose; minimum Android 10 / API 29.
- Parent PWA is a later full-parity client hosted on one Vercel project rooted at `apps/web`; Vercel is frontend-only for Harbor business architecture.
- Supabase project `bfvybxkjxilntjgndsrm` is development-only unless the user explicitly changes that designation.
- Parent Android and Parent PWA use the same Supabase Auth tenant/account and family authorization model.
- Parent Auth baseline is email/password + email verification + password recovery + TOTP MFA.
- Sensitive parent actions require server-enforced AAL2; the high-risk step-up window is exactly 15 minutes / 900 seconds.
- Child-device transport identity uses a separate Supabase Auth anonymous/device account and never reuses parent credentials.
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
- Run database tests, `supabase db lint`, Supabase security advisors, and Supabase performance advisors after intentional DDL/RLS application.
- Development, staging, and production mappings must be explicit; production must not silently point at the development Supabase project.
- This plan does **not** build the full Next.js Parent PWA; that work belongs to Subproject 2B.

## Review Focus

- **Cross-family IDOR/BOLA:** Parent A using a known Family B/Child B/device UUID must still receive no unauthorized data or mutation capability.
- **Stale MFA:** a high-risk operation with `aal2` but a most-recent MFA event older than 900 seconds must return `MFA_REQUIRED`.
- **Device credential theft:** a copied child Auth JWT without the enrolled P-256 private key must fail every protected device operation.
- **Replay/revocation race:** a reused signed nonce or a newly revoked device with an otherwise-valid JWT/signature must be rejected immediately.
- **Duplicate/invalid push delivery:** repeated outbox attempts must not duplicate domain effects, and permanently invalid Web Push subscriptions must be disabled without blocking other transports.

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

tests/end-to-end/platform-foundation.test.ts
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
- Produces: repeatable local start/reset/test workflow; documented Auth configuration; CI entrypoint; explicit dev/staging/prod mapping contract.

- [ ] **Step 1: Discover the installed Supabase CLI**

Run `supabase --version`, `supabase --help`, `supabase auth --help` when present, and each relevant subcommand `--help`. Record the tested CLI version in `docs/runbooks/supabase-development.md`.

- [ ] **Step 2: Initialize/link without overwriting an existing config**

Run `supabase init` only if `supabase/config.toml` is absent, then `supabase link --project-ref bfvybxkjxilntjgndsrm` and `supabase migration list`.

Expected: no Harbor application migrations before implementation begins.

- [ ] **Step 3: Configure the local Auth baseline**

Using only supported current Supabase settings, enable parent email/password, email confirmation/recovery behavior for local testing, TOTP MFA capability, and anonymous sign-in for child-device transport identities. Document dashboard-only production settings instead of inventing `config.toml` keys.

- [ ] **Step 4: Document environment boundaries**

`environment-mapping.md` must define: Vercel environment, Supabase project/ref, Supabase URL, publishable-key identifier, allowed redirect/callback origins, VAPID configuration identifier, and FCM credential identifier. It must explicitly forbid development ref `bfvybxkjxilntjgndsrm` in production.

- [ ] **Step 5: Add CI baseline**

CI starts local Supabase, applies migrations, runs `supabase db lint --level error`, `supabase test db`, `deno test tests/functions`, `deno test packages/contracts/test/contracts.test.ts`, `deno check packages/contracts/src/index.ts`, and secret scanning. PR CI must not require remote production credentials.

- [ ] **Step 6: Verify clean local startup/reset**

Run `supabase start` then `supabase db reset`.

Expected: reset succeeds with Auth enabled and no Harbor schema errors.

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
- `HarborErrorCode`: `AUTH_REQUIRED | MFA_REQUIRED | FORBIDDEN | VALIDATION_FAILED | DEVICE_REVOKED | DEVICE_OFFLINE | STALE_VERSION | REPLAY_REJECTED`.
- `NotificationRouteRefV1`: `{ version: 1, kind: string, familyId?: string, childId?: string, deviceId?: string, resourceId?: string }`; no sensitive message body.

- [ ] **Step 1: Write the failing contract tests**

Assert `ContractVersion === 1`, exact error-code literals, and that route-reference fixtures contain identifiers only and no location/message-content field.

- [ ] **Step 2: Run tests to verify failure**

Run `deno test packages/contracts/test/contracts.test.ts`.

Expected: FAIL because exports do not yet exist.

- [ ] **Step 3: Implement the V1 contracts**

Keep authorization out of the package; it defines stable data shapes and semantics only.

- [ ] **Step 4: Verify contracts**

Run `deno test packages/contracts/test/contracts.test.ts && deno check packages/contracts/src/index.ts`.

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
- Public: `profiles`, `families`, `family_members`, `children`, `devices_public`.
- Private: `private.staff_authorizations`.
- `family_members.role`: `owner | parent`; `status`: `active | invited | removed`.
- `devices_public.supervision_mode`: `unknown | standard | full`; `status`: `active | revoked`.
- `private.staff_authorizations.role`: `support | admin`; no client grants.

- [ ] **Step 1: Write failing schema tests**

Assert required PK/FK constraints, unique `(family_id,user_id)`, family-scoped children, same-family child/device integrity, and no `anon`/`authenticated` privileges on staff authorization state.

- [ ] **Step 2: Create `core_family_schema` migration**

Run `supabase migration new core_family_schema`. Use UUID PKs, `timestamptz`, explicit checks for the values above, and indexes on lookup/RLS FKs.

- [ ] **Step 3: Create `staff_authorization` migration**

Run `supabase migration new staff_authorization`. Create `private` if needed, revoke client access, and create minimal staff authorization without implicit family visibility.

- [ ] **Step 4: Add safe profile creation**

`profiles.id` references `auth.users(id)`. Profile display data may come from signup metadata, but family/staff roles never do.

- [ ] **Step 5: Verify**

Run `supabase db reset && supabase test db && supabase db lint --level error`.

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
- Produces parent-safe direct-read policies only for approved public resources.
- Direct writes reserved for Edge Functions remain denied.
- Device Auth identities and normal parents receive no private/staff-table access.

- [ ] **Step 1: Write failing pgTAP tests for Parent A, Parent B, a device anonymous Auth user, and a non-staff parent**

Assert Parent A can read Family A/Child A; Parent A cannot read or mutate Family B/Child B by known UUID; device Auth cannot read family resources merely because it has the `authenticated` role; normal parents cannot read staff state.

- [ ] **Step 2: Enable RLS and least-privilege grants**

Policies use `(select auth.uid())`, active membership, and role checks. No policy uses `TO authenticated` as the complete rule.

- [ ] **Step 3: Add policy-performance indexes**

At minimum index `family_members(user_id,family_id)` and every family/child FK used by policy predicates.

- [ ] **Step 4: Verify locally**

Run `supabase test db && supabase db lint --level error`.

Expected: all isolation tests PASS.

- [ ] **Step 5: Commit**

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
- `requireParent(req: Request): Promise<ParentContext>` -> `{ userId, accessToken, aal, amr }`.
- `requireFamilyRole(ctx, familyId, allowedRoles): Promise<void>` for `owner | parent`.
- `requireRecentAal2(ctx, nowEpochSeconds, maxAgeSeconds = 900): void`.
- `requireStaffRole(ctx, allowedRoles): Promise<void>` for `support | admin`, always requiring recent AAL2.
- `jsonError(code: HarborErrorCode, status: number, message: string): Response`.

- [ ] **Step 1: Write failing parent-auth tests**

Cover missing/invalid Auth, anonymous-device caller masquerading as parent, inactive membership, and non-staff caller.

- [ ] **Step 2: Write failing AAL2-recency tests**

Assert `aal1` fails; `aal2` with most-recent MFA `amr` event age 899 seconds succeeds; age 901 seconds fails `MFA_REQUIRED`; missing verifiable MFA timestamp fails closed.

- [ ] **Step 3: Implement request-scoped user validation**

Use the caller Authorization header for the user-scoped Supabase client; never reuse a global user-scoped client across requests.

- [ ] **Step 4: Implement database-backed family/staff role checks**

Read current application state; never authorize from editable metadata.

- [ ] **Step 5: Implement 900-second step-up enforcement**

Require verified JWT `aal2` plus a most-recent MFA `amr` event no older than 900 seconds.

- [ ] **Step 6: Verify**

Run `deno test tests/functions/shared-auth.test.ts tests/functions/aal.test.ts`.

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
- Create via CLI if required: service-only transaction helper migration

**Interfaces:**
- Request: `{ name: string, idempotencyKey: string }`.
- Response: `{ familyId: string, name: string, role: "owner" }`.

- [ ] **Step 1: Write failing tests**

Cover unauthenticated caller, device caller, blank name, name longer than 100 Unicode code points, duplicate idempotency key, and successful family + owner membership + audit creation.

- [ ] **Step 2: Implement parent/input validation**

Normalize surrounding whitespace; accepted name length is 1–100 Unicode code points after trimming.

- [ ] **Step 3: Make creation atomic and idempotent**

If a DB helper is required, keep it service-only, revoke `EXECUTE` from `PUBLIC`, `anon`, and `authenticated`, and prefer `SECURITY INVOKER` with backend privileges.

- [ ] **Step 4: Verify**

Run `deno test tests/functions/create-family.test.ts` plus the family RLS tests.

Expected: repeated idempotency key reuses the original result and creates no second family.

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
- Pairing response: `{ code: string, expiresAt: string }`; code exactly six digits, lifetime exactly 10 minutes.
- Only one unconsumed pairing token may be active per child; issuing a new code invalidates the old one.
- Each token allows at most 5 failed claim attempts; the fifth failure invalidates it.
- Claim request: `{ code, publicKeySpki, device: { displayName, model, androidVersion, supervisionMode } }`.
- Claim response: `{ deviceId, familyId, childId }`.

- [ ] **Step 1: Write failing pairing/claim tests**

Cover six-digit format, 10-minute expiry, new-code invalidation of the old code, single use, wrong family/child, five-failure invalidation, malformed SPKI, repeated claim, and already-bound Auth identity.

- [ ] **Step 2: Create private device-security objects**

Run `supabase migration new device_security`; revoke `PUBLIC`, `anon`, and `authenticated` access.

- [ ] **Step 3: Store only keyed pairing-code digests**

Use HMAC-SHA-256 with a server-side pairing pepper secret; never store the raw code.

- [ ] **Step 4: Require the caller's separate child-device Supabase Auth identity**

The device principal receives no direct family Data API access.

- [ ] **Step 5: Atomically bind Auth subject + P-256 SPKI + device row and consume the token**

Write the enrollment audit event in the same logical transaction.

- [ ] **Step 6: Verify**

Run DB tests and `deno test tests/functions/device-claim.test.ts`.

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
- Headers: `X-Harbor-Device-Id`, `X-Harbor-Timestamp`, `X-Harbor-Nonce`, `X-Harbor-Signature`.
- `requireDeviceProof(req, operation): Promise<DeviceContext>` -> `{ deviceId, familyId, childId, authUserId }`.
- Accepted timestamp skew: ±5 minutes.

- [ ] **Step 1: Write failing proof tests**

Cover valid signature, wrong key, modified body, wrong device ID, ±5-minute boundary, stale timestamp, reused nonce, copied JWT without signature, and revoked device.

- [ ] **Step 2: Bind JWT `sub` to `device_security.auth_user_id`**

Reject any other valid user/device token.

- [ ] **Step 3: Verify ECDSA P-256/SHA-256 against stored SPKI**

Malformed/unsupported key material fails closed.

- [ ] **Step 4: Atomically claim nonce**

Duplicate `(device_id, nonce)` returns `REPLAY_REJECTED`.

- [ ] **Step 5: Check current `revoked_at` on every protected request**

Revocation wins over an otherwise-valid JWT/signature.

- [ ] **Step 6: Verify and commit**

Run `deno test tests/functions/device-proof.test.ts` plus device DB tests, then commit `feat: enforce Harbor device proof of possession`.

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
- Private: `device_desired_state`, `device_commands`, `device_fcm_registrations`.
- Desired-state version increases monotonically per device.
- Sync request: `{ acknowledgedDesiredStateVersion?: number, appliedCommandIds?: string[] }`.
- Sync response: `{ desiredState: unknown, desiredStateVersion: number, commands: DeviceCommandV1[] }`.
- `DeviceCommandV1`: stable `id`, `kind`, `idempotencyKey`, `createdAt`, optional `expiresAt`, minimal payload.
- `revoke-device` requires family authorization plus `requireRecentAal2(..., 900)`.

- [ ] **Step 1: Write failing tests**

Cover monotonic versions, stale version rejection, duplicate command idempotency, duplicate acknowledgement, private FCM token visibility, signed sync, and revoked-device denial.

- [ ] **Step 2: Implement signed `device-sync`**

Use `requireDeviceProof`; omit expired commands and make acknowledgements idempotent.

- [ ] **Step 3: Implement signed FCM registration**

Tokens remain private and can rotate without exposing old values.

- [ ] **Step 4: Implement parent-authorized desired-state update**

Each accepted state change increments the version exactly once and records wake intent.

- [ ] **Step 5: Implement recent-AAL2 device revocation**

Revoke private FCM/desired-state access and audit the action; the next child call fails immediately.

- [ ] **Step 6: Verify and commit**

Run DB/function tests and commit `feat: add Harbor desired state and device revocation`.

---

### Task 10: Add parent Web Push subscription lifecycle and VAPID boundary

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_parent_web_push.sql`
- Create: `supabase/functions/register-web-push/index.ts`
- Create: `supabase/functions/remove-web-push/index.ts`
- Create: `supabase/functions/_shared/web-push.ts`
- Test: `supabase/tests/web_push.test.sql`
- Test: `tests/functions/web-push.test.ts`
- Create/modify: `docs/runbooks/notifications.md`

**Interfaces:**
- Private table: `private.parent_web_push_subscriptions`.
- Subscription identity: `(user_id, client_installation_id, endpoint_hash)`; multiple installations per parent allowed.
- Register request: `{ clientInstallationId, endpoint, keys: { p256dh, auth } }`.
- Remove request: `{ clientInstallationId, endpoint?: string }`.
- `sendWebPush(subscription, route: NotificationRouteRefV1): Promise<PushDeliveryResult>`.
- VAPID public key is browser-safe; private key exists only in Supabase secrets.

- [ ] **Step 1: Write failing lifecycle tests**

Cover unauthenticated caller, another user's installation ID, multiple installations, endpoint/key rotation, duplicate registration idempotency, explicit removal, and no client grants on the private table.

- [ ] **Step 2: Create the Web Push migration**

Run `supabase migration new parent_web_push`. Persist only delivery/lifecycle fields and status/timestamps.

- [ ] **Step 3: Implement authenticated register/remove functions**

A caller may mutate only subscriptions attached to `auth.uid()`.

- [ ] **Step 4: Implement the server-only Web Push adapter**

Use `npm:web-push` through Supabase Edge Runtime NPM compatibility; do not hand-roll Web Push encryption. Pin the resolved package version/lockfile used by the implementation. Read `VAPID_PRIVATE_KEY` only from Edge Function secrets.

- [ ] **Step 5: Write mocked delivery tests**

Assert payload is only `NotificationRouteRefV1`; HTTP 404/410 is permanent-invalid-subscription; 429/5xx/network error is retryable.

- [ ] **Step 6: Verify and commit**

Run DB tests and `deno test tests/functions/web-push.test.ts`; commit `feat: add Harbor parent web push foundation`.

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
- Transports: `fcm | web_push`.
- Statuses: `pending | processing | sent | retry | dead_letter`.
- Each row has stable event/idempotency identity, target reference, minimal route payload, attempt count, next-attempt time, last error category, timestamps.
- `dispatchOne(outboxId: string): Promise<DeliveryOutcome>` is idempotent for already-sent rows.

- [ ] **Step 1: Write failing DB state-machine tests**

Cover duplicate event key, safe claiming, retry scheduling, already-sent no-op, and dead-letter transition.

- [ ] **Step 2: Write failing dual-transport dispatcher tests**

One logical event may create separate FCM/Web Push rows; one transport failure does not undo another successful transport.

- [ ] **Step 3: Implement durable outbox helpers**

Domain changes record delivery intent before external delivery counts as successful.

- [ ] **Step 4: Implement retry/permanent-failure classification**

Web Push 404/410 disables the subscription; transient failures schedule retry; duplicate dispatcher invocation does not duplicate domain effects.

- [ ] **Step 5: Enforce minimal push payloads in tests**

Reject raw location/message content when a route/reference is sufficient.

- [ ] **Step 6: Verify and commit**

Run DB/function tests and commit `feat: add Harbor durable notification outbox`.

---

### Task 12: Authorize private family Realtime channels

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_realtime_authorization.sql`
- Test: `supabase/tests/realtime_authorization.test.sql`

**Interfaces:**
- Topic: `family:{family_id}`.
- Only active family members may receive Harbor family broadcasts.
- Device Auth users and other-family parents cannot join/read the topic.

- [ ] **Step 1: Write failing authorization tests**

Cover active Parent A, removed Parent A, Parent B, device Auth, malformed topic, and nonexistent family UUID.

- [ ] **Step 2: Implement `realtime.messages` RLS**

Use current Supabase private-channel authorization with `realtime.topic()` and constrain allowed Realtime extensions to Harbor's chosen broadcast use.

- [ ] **Step 3: Keep broadcasts non-authoritative**

Broadcast payloads contain only non-sensitive change notifications sufficient to trigger refresh.

- [ ] **Step 4: Verify and commit**

Run DB tests and commit `feat: authorize Harbor family realtime channels`.

---

### Task 13: Add release-blocking security regression and Subproject 1 acceptance harness

**Files:**
- Create: `tests/functions/security-regression.test.ts`
- Create: `tests/end-to-end/platform-foundation.test.ts`
- Modify: `.github/workflows/harbor-foundation-ci.yml`
- Modify: `docs/runbooks/environment-mapping.md`
- Modify: `docs/runbooks/supabase-development.md`

**Interfaces:**
- Harness represents two logical clients for Parent A (`parent-android-sim`, `parent-pwa-sim`), Parent B, and one child-device Auth identity.
- No full Android or Next.js UI is built; tests exercise the common backend interfaces those clients later consume.

- [ ] **Step 1: Write the failing cross-cutting security suite**

Prove: Parent A cannot access Parent B known IDs; device Auth cannot access family Data API; copied child JWT without signature fails; replayed nonce fails; revoked device fails with unexpired JWT; stale (>900s) AAL2 high-risk action fails; fresh AAL2 succeeds; private staff state is not client-readable; invalid Web Push cleanup does not block FCM delivery.

- [ ] **Step 2: Write the failing foundation acceptance path**

Exercise: create Parent A family/child → pairing code → P-256 device claim → signed sync → FCM registration → parent Web Push registration using the same parent identity → desired-state update → dual-transport outbox intent → Family A Realtime authorization → Parent B denial → replay rejection → AAL2-protected revocation → immediate child denial → audit verification.

- [ ] **Step 3: Run the full clean local suite**

Run `supabase db reset`, `supabase test db`, `supabase db lint --level error`, `deno test tests/functions`, `deno test tests/end-to-end/platform-foundation.test.ts`, `deno test packages/contracts/test/contracts.test.ts`, and `deno check packages/contracts/src/index.ts`.

Expected: all PASS from a clean reset.

- [ ] **Step 4: Intentionally apply the reviewed migration set to the designated development project**

Do not use production. Verify `supabase migration list` matches repository history.

- [ ] **Step 5: Run Supabase security and performance advisors against the development project**

Expected: no unreviewed Harbor security findings; remediate relevant findings before completion.

- [ ] **Step 6: Finalize CI gates**

CI fails on backend/security/contract tests, secret scanning, or any production environment mapping that references development ref `bfvybxkjxilntjgndsrm`.

- [ ] **Step 7: Finalize non-secret runbooks**

Document Auth settings, secret names/placement for pairing pepper, FCM, and VAPID, redirect-origin checklist, migration commands, and reset/rollback cautions without secret values.

- [ ] **Step 8: Commit**

```bash
git add tests/functions/security-regression.test.ts tests/end-to-end/platform-foundation.test.ts .github/workflows/harbor-foundation-ci.yml docs/runbooks/environment-mapping.md docs/runbooks/supabase-development.md
git commit -m "test: gate Harbor platform foundation release"
```

---

## Subproject 1 Definition of Done

Subproject 1 is complete only when:

- all Harbor DDL is migration-backed and reproducible from a clean reset;
- Auth/TOTP capability is configured and recent-AAL2 enforcement is proven server-side;
- family/child/device public data is RLS-isolated with cross-family known-ID tests;
- staff authorization is private/server-controlled;
- pairing is six-digit, one-time, 10-minute, keyed-digest-only, one-active-token-per-child, and invalidates after five failed claims;
- device calls require bound Auth + P-256 proof, replay rejection, and immediate revocation;
- desired state is versioned, commands idempotent, and FCM registrations private;
- Web Push subscriptions are private/per-installation and VAPID private operations stay in Supabase;
- the outbox supports independent idempotent FCM + Web Push delivery;
- private family Realtime allows only active family members;
- shared V1 contracts and stable backend error semantics exist;
- the clean-reset DB/function/contract/security/end-to-end suites are green;
- Supabase security/performance advisors are reviewed after intentional DDL application;
- environment mapping blocks Vercel production from using the development Supabase project;
- the full Next.js Parent PWA remains deferred to Subproject 2B.

## Deferred to Later Subprojects

This plan intentionally does **not** implement the full Parent Android UI, Child Android UI, Parent PWA/Next.js UI, screen-time evaluator, Kid Space/DPC enforcement, location-history product flows, web filtering, full Get Help UX, digital-activity product surfaces, safety-monitoring product features, billing, or production rollout. Those remain in their dedicated roadmap subprojects.
