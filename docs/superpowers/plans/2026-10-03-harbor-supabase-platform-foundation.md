# Harbor Supabase Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Harbor Subproject 1: a secure Supabase backend foundation for parent identity, family/child ownership, cryptographically bound child-device enrollment, signed device sync, desired-state delivery, FCM wake-up, durable notifications, Realtime authorization, and CI/security verification.

**Architecture:** Supabase fully replaces the former .NET/Azure application tier. Parent clients use Supabase Auth and RLS-protected read access where safe; privileged mutations and all child-device operations go through Edge Functions. Child devices use a separate Supabase Auth identity plus Android Keystore ECDSA P-256 proof-of-possession, while PostgreSQL remains the backend source of truth and Room remains the last-valid offline policy source on-device.

**Tech Stack:** Supabase Auth, PostgreSQL 17+, RLS, Edge Functions (TypeScript/Deno), Realtime Broadcast, Supabase CLI migrations, pgTAP, GitHub Actions, Firebase Cloud Messaging, ECDSA P-256/SHA-256.

**Spec:** `docs/superpowers/specs/2026-10-03-harbor-supabase-production-architecture-design.md`

## Global Constraints

- Android-only V1; minimum Android 10 / API 29.
- Supabase project `bfvybxkjxilntjgndsrm` is development only, not production.
- Parent identity uses Supabase Auth; child devices never store or reuse parent credentials.
- Parent Auth baseline is email/password + email verification + password recovery + TOTP MFA capability.
- Child-device transport identity uses a separate Supabase Auth anonymous/device account; anonymous Auth users receive the `authenticated` Postgres role, so RLS must still deny them family data unless they are real family members.
- Every exposed Harbor table has RLS enabled before client access is granted.
- `TO authenticated` is never the complete authorization rule.
- Do not use `raw_user_meta_data` for authorization.
- Secret/service-role credentials never ship to Android clients or source control.
- Privileged/multi-record/security-sensitive mutations use Edge Functions.
- Protected child operations require both a valid device Supabase session and ECDSA P-256 proof-of-possession.
- Revoked devices must be denied immediately by current backend state.
- FCM is wake/notification transport, not the source of truth.
- Realtime improves parent UX but is not child policy authority.
- Delivery is at-least-once; commands and notifications must be idempotent.
- Migration files are created with `supabase migration new <name>`; do not invent timestamped migration filenames.
- Run Supabase security and performance advisors after DDL/RLS changes.

## Review Focus

- **Cross-family IDOR/BOLA:** known UUIDs from another family must still return no data / authorization failure.
- **Replay attempts:** a valid signed device request nonce must fail on second use.
- **Token theft:** copied child Auth JWT without the device private key must fail protected operations.
- **Revocation race:** a revoked device with an unexpired JWT must fail immediately.
- **Duplicate delivery:** retrying FCM/outbox/commands must not duplicate domain effects.

---

## File Map

Migration filenames below use `<CLI-generated>` because the Supabase CLI must create the timestamped filename.

```text
supabase/
├── config.toml
├── seed.sql
├── migrations/
│   ├── <CLI-generated>_core_family_schema.sql
│   ├── <CLI-generated>_family_rls.sql
│   ├── <CLI-generated>_device_security.sql
│   ├── <CLI-generated>_desired_state_commands.sql
│   ├── <CLI-generated>_notification_outbox.sql
│   └── <CLI-generated>_realtime_authorization.sql
├── tests/
│   ├── rls_family_access.test.sql
│   ├── device_security.test.sql
│   ├── desired_state.test.sql
│   ├── outbox.test.sql
│   └── realtime_authorization.test.sql
└── functions/
    ├── _shared/
    │   ├── clients.ts
    │   ├── errors.ts
    │   ├── auth.ts
    │   ├── device-proof.ts
    │   ├── crypto.ts
    │   └── responses.ts
    ├── create-family/index.ts
    ├── create-device-pairing/index.ts
    ├── device-claim/index.ts
    ├── device-sync/index.ts
    ├── register-fcm/index.ts
    ├── revoke-device/index.ts
    ├── update-device-state/index.ts
    └── dispatch-outbox/index.ts

tests/functions/
├── shared-auth.test.ts
├── create-family.test.ts
├── device-claim.test.ts
├── device-proof.test.ts
├── device-sync.test.ts
├── revoke-device.test.ts
├── dispatch-outbox.test.ts
└── security-regression.test.ts

.github/workflows/supabase-ci.yml
docs/runbooks/supabase-development.md
docs/runbooks/device-enrollment.md
```

---

### Task 1: Bootstrap Supabase, Auth configuration, and CI baseline

**Files:**
- Create: `supabase/config.toml`
- Create: `supabase/seed.sql`
- Create: `.github/workflows/supabase-ci.yml`
- Create: `docs/runbooks/supabase-development.md`
- Modify: `.gitignore` if needed

**Interfaces:**
- Consumes: development project ref `bfvybxkjxilntjgndsrm`.
- Produces: repeatable local Supabase start/reset/test workflow and the documented Auth settings every environment must mirror.

- [ ] **Step 1: Discover the installed CLI before using commands**

Run `supabase --version`, `supabase --help`, `supabase auth --help` when available, and the relevant subcommand `--help` before configuration. Record the tested CLI version.

- [ ] **Step 2: Initialize/link without inventing config**

Run `supabase init` only if `supabase/config.toml` is absent; then `supabase link --project-ref bfvybxkjxilntjgndsrm` and `supabase migration list`. Expected: no Harbor application migrations yet.

- [ ] **Step 3: Configure the Auth baseline**

Using current Supabase config/docs, enable parent email/password, email confirmation/recovery, TOTP MFA capability, and anonymous sign-ins for child-device transport identities. Document dashboard-only settings that cannot be represented in `config.toml`, including production SMTP/CAPTCHA/rate-limit decisions. Do not put secrets in the repo.

- [ ] **Step 4: Add CI**

CI starts Supabase locally, applies migrations, runs `supabase test db`, runs Edge Function tests, and fails on migration/test errors. No remote project credentials are required for PR CI.

- [ ] **Step 5: Verify a clean local reset**

Run `supabase start` then `supabase db reset`. Expected: clean local project starts with Auth enabled and no Harbor schema errors.

- [ ] **Step 6: Commit**

`git commit -m "chore: bootstrap Supabase development workflow"`

---

### Task 2: Create the core parent/family/child schema

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_core_family_schema.sql`
- Modify: `supabase/seed.sql`
- Test: `supabase/tests/rls_family_access.test.sql`

**Interfaces:**
- Produces: `public.profiles`, `public.families`, `public.family_members`, `public.children`, `public.devices_public`.
- `family_members.role`: `owner | parent` for V1.
- `family_members.status`: `active | invited | removed`.
- `devices_public.supervision_mode`: `unknown | standard | full`.
- `devices_public.status`: `active | revoked`.

- [ ] **Step 1: Write failing schema assertions**

Assert required PK/FK constraints, unique `(family_id,user_id)`, family-scoped children, and same-family child/device integrity.

- [ ] **Step 2: Create the migration with `supabase migration new core_family_schema`**

Use UUID PKs, `timestamptz`, explicit checks for the values above, and indexes on every FK/RLS lookup column.

- [ ] **Step 3: Create profile rows safely**

`profiles.id` references `auth.users(id)`. Display name may be copied from signup metadata, but role/family authorization is never copied from editable user metadata.

- [ ] **Step 4: Run `supabase db reset && supabase test db`**

Expected: schema assertions pass.

- [ ] **Step 5: Commit**

`git commit -m "feat: add Harbor family data model"`

---

### Task 3: Enforce RLS and prove family isolation

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_family_rls.sql`
- Modify: `supabase/tests/rls_family_access.test.sql`

**Interfaces:**
- Produces parent-safe direct-read policies only where the spec permits Data API access.
- Direct writes reserved for Edge Functions remain denied.

- [ ] **Step 1: Write failing pgTAP tests for Parent A, Parent B, and a device anonymous Auth user**

Assert Parent A can read Family A/Child A; Parent A cannot read Family B/Child B by known UUID; Parent A cannot mutate Family B; and the device Auth user cannot directly read any family/child/device family data merely because it has the `authenticated` role.

- [ ] **Step 2: Enable RLS on every exposed Harbor table and grant only needed operations**

Policies use `(select auth.uid())`, active `family_members` membership, and owner/parent role where required. `TO authenticated` alone is never accepted.

- [ ] **Step 3: Add RLS-performance indexes**

At minimum index `family_members(user_id,family_id)` and family foreign keys used by policy predicates.

- [ ] **Step 4: Run database tests and advisors**

Expected: all isolation tests pass; no exposed Harbor table lacks RLS.

- [ ] **Step 5: Commit**

`git commit -m "feat: enforce family row level security"`

---

### Task 4: Build shared Edge Function parent-auth utilities

**Files:**
- Create: `supabase/functions/_shared/clients.ts`
- Create: `supabase/functions/_shared/errors.ts`
- Create: `supabase/functions/_shared/auth.ts`
- Create: `supabase/functions/_shared/responses.ts`
- Test: `tests/functions/shared-auth.test.ts`

**Interfaces:**
- `requireParent(req: Request): Promise<ParentContext>` -> `{ userId: string, accessToken: string, aal: "aal1" | "aal2" }`.
- `requireFamilyRole(ctx: ParentContext, familyId: string, allowedRoles: readonly FamilyRole[]): Promise<void>`.
- `jsonError(code: string, status: number, message: string): Response`.
- `adminClient(): SupabaseClient` uses server-only secret configuration.

- [ ] **Step 1: Write failing tests for missing/invalid Auth, anonymous device identity masquerading as parent, and inactive/non-member family access**
- [ ] **Step 2: Implement request-scoped user validation with Supabase Auth**
- [ ] **Step 3: Implement family-role lookup from database state, never caller metadata**
- [ ] **Step 4: Implement stable error codes: `AUTH_REQUIRED`, `FORBIDDEN`, `VALIDATION_FAILED`, `DEVICE_REVOKED`**
- [ ] **Step 5: Run function tests and commit**

`git commit -m "feat: add Edge Function auth foundation"`

---

### Task 5: Implement atomic family creation

**Files:**
- Create: `supabase/functions/create-family/index.ts`
- Test: `tests/functions/create-family.test.ts`
- Create via CLI if needed: service-only transaction helper migration.

**Interfaces:**
- Request: `{ name: string, idempotencyKey: string }`.
- Response: `{ familyId: string, name: string, role: "owner" }`.

- [ ] **Step 1: Write failing tests for unauthenticated caller, anonymous device caller, blank name, repeated idempotency key, and successful owner membership creation**
- [ ] **Step 2: Validate parent identity and input**
- [ ] **Step 3: Atomically create family + owner membership + audit event**

If a DB function is used, make it service-only, revoke `EXECUTE` from `PUBLIC`, `anon`, and `authenticated`, and prefer `SECURITY INVOKER` with the server role.

- [ ] **Step 4: Prove duplicate idempotency key returns/reuses the original domain result rather than creating a second family**
- [ ] **Step 5: Commit**

`git commit -m "feat: add secure family creation"`

---

### Task 6: Add device-security schema and one-time pairing

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_device_security.sql`
- Create: `supabase/functions/create-device-pairing/index.ts`
- Create: `supabase/functions/device-claim/index.ts`
- Create: `docs/runbooks/device-enrollment.md`
- Test: `supabase/tests/device_security.test.sql`
- Test: `tests/functions/device-claim.test.ts`

**Interfaces:**
- Private tables: `private.device_security`, `private.device_enrollment_tokens`, later `private.device_request_nonces`.
- Pairing request: `{ childId: string }`.
- Pairing response: `{ code: string, expiresAt: string }`; code is exactly six digits and lifetime is exactly 10 minutes.
- Claim request: `{ code: string, publicKeySpki: string, device: { displayName: string, model: string, androidVersion: string, supervisionMode: "unknown" | "standard" | "full" } }`.
- Claim response: `{ deviceId: string, familyId: string, childId: string }`.

- [ ] **Step 1: Write failing tests for code format, expiry, single-use, wrong family/child, repeated claim, and already-bound Auth identity**
- [ ] **Step 2: Create the `private` schema and revoke access from `PUBLIC`, `anon`, and `authenticated`**

Only backend secret/service role access is granted. If `private` is added to Data API exposed schemas for Edge Function server access, absence of `anon`/`authenticated` grants is mandatory and tested.

- [ ] **Step 3: Store only an HMAC-SHA-256 digest of the pairing code using a server-side pairing pepper secret**
- [ ] **Step 4: Require a separate anonymous/device Supabase Auth identity for `device-claim`**
- [ ] **Step 5: Atomically bind Auth subject + P-256 SPKI public key + public device row and consume the token**
- [ ] **Step 6: Run DB/function tests and commit**

`git commit -m "feat: add cryptographic device enrollment"`

---

### Task 7: Require ECDSA proof-of-possession and reject replay

**Files:**
- Create: `supabase/functions/_shared/crypto.ts`
- Create: `supabase/functions/_shared/device-proof.ts`
- Modify via CLI migration: add `private.device_request_nonces`
- Test: `tests/functions/device-proof.test.ts`
- Test: `supabase/tests/device_security.test.sql`

**Interfaces:**
- Canonical signature input: `METHOD + "\n" + OPERATION + "\n" + DEVICE_ID + "\n" + BODY_SHA256 + "\n" + UNIX_TIMESTAMP + "\n" + NONCE`.
- Required headers: `X-Harbor-Device-Id`, `X-Harbor-Timestamp`, `X-Harbor-Nonce`, `X-Harbor-Signature`.
- `requireDeviceProof(req: Request, operation: string): Promise<DeviceContext>` -> `{ deviceId, familyId, childId, authUserId }`.

- [ ] **Step 1: Write failing tests for valid signature, wrong key, modified body, wrong device ID, timestamp outside the accepted 5-minute skew window, reused nonce, and copied JWT without a signature**
- [ ] **Step 2: Bind JWT `sub` to `private.device_security.auth_user_id`**
- [ ] **Step 3: Verify P-256/SHA-256 signature against stored SPKI public key**
- [ ] **Step 4: Atomically insert `(device_id, nonce)` before accepting the operation; duplicate insert maps to replay rejection**
- [ ] **Step 5: Read `revoked_at` on every protected request**
- [ ] **Step 6: Commit**

`git commit -m "feat: require device proof of possession"`

---

### Task 8: Add desired state, commands, FCM registration, signed sync, and immediate revocation

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
- Sync response: `{ desiredState: unknown, desiredStateVersion: number, commands: DeviceCommand[] }`.
- `DeviceCommand` contains stable `id`, `idempotencyKey`, `kind`, `payload`, `expiresAt`.

- [ ] **Step 1: Write failing tests for monotonic version and duplicate idempotency key**
- [ ] **Step 2: Implement signed `device-sync` with `requireDeviceProof`**
- [ ] **Step 3: Implement signed FCM registration; FCM token is private backend state**
- [ ] **Step 4: Implement parent-authorized revocation and prove the very next signed sync fails even when the JWT is unexpired**
- [ ] **Step 5: Implement parent-authorized desired-state update that increments once and records wake intent**
- [ ] **Step 6: Commit**

`git commit -m "feat: add device state sync and revocation"`

---

### Task 9: Add durable notification outbox and FCM dispatcher

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_notification_outbox.sql`
- Create: `supabase/functions/dispatch-outbox/index.ts`
- Test: `supabase/tests/outbox.test.sql`
- Test: `tests/functions/dispatch-outbox.test.ts`

**Interfaces:**
- `private.notification_outbox`: event ID, target, kind, minimal payload/reference, status, attempt count, next attempt, lock timestamps, last error, created/sent timestamps.
- Dispatcher result: `{ claimed: number, sent: number, retried: number, dead: number }`.

- [ ] **Step 1: Write failing tests for concurrent workers and duplicate retry**
- [ ] **Step 2: Claim work in short transactions with `FOR UPDATE SKIP LOCKED` semantics**
- [ ] **Step 3: Send minimal FCM wake payloads only; never send sensitive child content in push**
- [ ] **Step 4: Separate retryable/permanent failure categories and dead-letter after 8 attempts**
- [ ] **Step 5: Prove rerunning a completed event does not duplicate domain effects**
- [ ] **Step 6: Commit**

`git commit -m "feat: add durable notification outbox"`

---

### Task 10: Authorize private family Realtime channels

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_realtime_authorization.sql`
- Test: `supabase/tests/realtime_authorization.test.sql`
- Modify: `docs/runbooks/supabase-development.md`

**Interfaces:**
- Parent channel topic: `family:<family_uuid>`.
- Parent client must subscribe with `private = true`.
- Foundation authorizes **receive-only Broadcast** for active family members. Parent-originated Broadcast/Presence can be added later if a subproject needs it.

- [ ] **Step 1: Write failing tests for Parent A joining `family:<FamilyB>` and for a device anonymous Auth user joining `family:<FamilyA>`**
- [ ] **Step 2: Add `SELECT` policy on `realtime.messages`**

Policy must be `TO authenticated`, require `realtime.messages.extension = 'broadcast'`, parse the UUID suffix from `(select realtime.topic())`, and require an active `public.family_members` row where `user_id = (select auth.uid())` and `family_id` equals the topic UUID. No broad `USING (true)` policy is allowed.

- [ ] **Step 3: Disable Realtime public channel access in the development project and document this environment requirement**
- [ ] **Step 4: Verify Parent A can join Family A but not Family B, and device identity cannot join a family channel**
- [ ] **Step 5: Commit**

`git commit -m "feat: authorize family realtime channels"`

---

### Task 11: Harden secrets, logging, advisors, and CI

**Files:**
- Modify: `.github/workflows/supabase-ci.yml`
- Create: `tests/functions/security-regression.test.ts`
- Modify: `docs/runbooks/supabase-development.md`

**Interfaces:**
- CI fails on DB tests, function tests, migration failures, or secret scanning failures.

- [ ] **Step 1: Add regression tests proving errors/logs never include pairing codes, FCM tokens, bearer tokens, nonces, signatures, private keys, or Supabase secret keys**
- [ ] **Step 2: Run Supabase Security Advisor and fix all Harbor-created critical/high findings**
- [ ] **Step 3: Run Performance Advisor and fix foundation missing FK/RLS indexes**
- [ ] **Step 4: Add CI secret scan and clean-reset migration verification**
- [ ] **Step 5: Verify the entire CI command sequence from a clean checkout**
- [ ] **Step 6: Commit**

`git commit -m "chore: harden Supabase security checks"`

---

### Task 12: Prove the development/staging acceptance flow

**Files:**
- Create: `tests/end-to-end/platform-foundation.md`
- Modify: `docs/runbooks/supabase-development.md`
- Modify: `README.md` only after successful verification

**Interfaces:**
- End-to-end gate from GitHub Issue #1.

- [ ] **Step 1: Create Parent A and Parent B test accounts and separate families**
- [ ] **Step 2: Prove Parent A cannot read/mutate Family B by known IDs and a device Auth identity cannot read either family directly**
- [ ] **Step 3: Create Child A, issue a six-digit 10-minute pairing code, and bind a separate child-device Auth identity to a P-256 public key**
- [ ] **Step 4: Perform one valid signed device sync, replay the same nonce, and verify `REPLAY_DETECTED`**
- [ ] **Step 5: Register FCM, update desired state, verify outbox wake intent, and verify duplicate dispatch is idempotent**
- [ ] **Step 6: Revoke the device and verify its still-unexpired Auth session immediately fails the next protected request with `DEVICE_REVOKED`**
- [ ] **Step 7: Verify Parent A private Realtime subscription to Family A succeeds and Family B fails**
- [ ] **Step 8: Run all DB/function tests, security/performance advisors, and migration-list verification**
- [ ] **Step 9: Record exact passing evidence and remaining work in Issue #1 / implementation PR**
- [ ] **Step 10: Commit**

`git commit -m "test: verify Supabase platform foundation"`

---

## Definition of Done for Subproject 1

Subproject 1 is complete only when the designated non-production environment proves:

- Parent email/password/Auth baseline is configured, with email verification/recovery and TOTP MFA capability documented.
- Family/child/device public resources are protected by tested RLS.
- Cross-family known-ID attacks fail.
- A child-device anonymous Auth identity cannot directly access family data.
- Pairing codes are six digits, one-time, expire after 10 minutes, and are never stored plaintext.
- Child devices use a separate Supabase Auth identity and P-256 public-key binding.
- Protected device operations require proof-of-possession; replayed signed requests fail.
- Revoked devices fail protected operations immediately even with an unexpired bearer token.
- Desired state is versioned and commands are idempotent.
- FCM registration tokens remain private.
- Notification delivery intent is durable and retries are idempotent.
- Private family Realtime channels cannot cross family boundaries.
- Security/performance advisors have no unresolved foundation-critical findings.
- CI reproduces migration + DB test + Edge Function test verification from a clean checkout.

## Deferred to Later Subprojects

This plan intentionally does **not** implement Parent Compose UI, Child Compose UI, screen-time enforcement, Kid Space/DPC, production location history, web filtering, Get Help UX, digital activity, safety monitoring, billing, or production rollout. It creates only the backend/security foundation those subprojects consume.
