# Harbor Supabase Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Harbor Subproject 1: a secure Supabase backend foundation for parent identity, family/child ownership, cryptographically bound child-device enrollment, signed device sync, desired-state delivery, FCM wake-up, durable notifications, Realtime authorization, and CI/security verification.

**Architecture:** Supabase fully replaces the former .NET/Azure application tier. Parent clients use Supabase Auth and RLS-protected read access where safe; privileged mutations and all child-device operations go through Edge Functions. Child devices use a separate Supabase Auth identity plus Android Keystore ECDSA P-256 proof-of-possession, while PostgreSQL remains the backend source of truth and Room remains the last-valid offline policy source on-device.

**Tech Stack:** Supabase Auth, PostgreSQL 17+, RLS, Edge Functions (TypeScript/Deno), Realtime, Supabase CLI migrations, pgTAP, GitHub Actions, Firebase Cloud Messaging, ECDSA P-256/SHA-256.

**Spec:** `docs/superpowers/specs/2026-10-03-harbor-supabase-production-architecture-design.md`

## Global Constraints

- Android-only V1; minimum Android 10 / API 29.
- Supabase project `bfvybxkjxilntjgndsrm` is development only, not production.
- Parent identity uses Supabase Auth; child devices never store or reuse parent credentials.
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

Files are created as tasks need them. Migration filenames below use `<CLI-generated>` because Supabase requires `supabase migration new <name>` to generate the timestamped path.

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
│   └── outbox.test.sql
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
├── create-family.test.ts
├── device-claim.test.ts
├── device-proof.test.ts
├── device-sync.test.ts
├── revoke-device.test.ts
└── dispatch-outbox.test.ts

.github/workflows/supabase-ci.yml
docs/runbooks/supabase-development.md
docs/runbooks/device-enrollment.md
```

---

### Task 1: Bootstrap the Supabase workspace and CI baseline

**Files:**
- Create: `supabase/config.toml`
- Create: `supabase/seed.sql`
- Create: `.github/workflows/supabase-ci.yml`
- Create: `docs/runbooks/supabase-development.md`
- Modify: `.gitignore` if needed

**Interfaces:**
- Consumes: linked development project ref `bfvybxkjxilntjgndsrm`.
- Produces: repeatable local Supabase start/reset/test commands and CI entrypoint.

- [ ] **Step 1: Initialize the repo with the current Supabase CLI**

Run `supabase --version`, `supabase --help`, then `supabase init` only if `supabase/config.toml` is absent. Record the tested CLI version in `docs/runbooks/supabase-development.md`.

- [ ] **Step 2: Link the development project and verify the empty baseline**

Run `supabase link --project-ref bfvybxkjxilntjgndsrm`, then `supabase migration list`. Expected: no application migrations yet.

- [ ] **Step 3: Add the CI workflow**

CI must start Supabase locally, apply migrations, run `supabase test db`, run Edge Function tests, and fail on migration/test errors. It must not contain project secrets.

- [ ] **Step 4: Verify local reset succeeds**

Run `supabase start` and `supabase db reset`. Expected: clean local project starts with no Harbor schema errors.

- [ ] **Step 5: Commit**

`git commit -m "chore: bootstrap Supabase development workflow"`

---

### Task 2: Create the core parent/family/child schema

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_core_family_schema.sql`
- Modify: `supabase/seed.sql`
- Test: `supabase/tests/rls_family_access.test.sql`

**Interfaces:**
- Produces tables: `public.profiles`, `public.families`, `public.family_members`, `public.children`, `public.devices_public`.
- Produces constrained role/status values used by later Edge Functions.

- [ ] **Step 1: Write failing schema tests**

Assert primary keys/foreign keys exist, `family_members` is unique on `(family_id,user_id)`, child rows require a family, and `devices_public.child_id` belongs to the same family represented by the device row.

- [ ] **Step 2: Create the migration with `supabase migration new core_family_schema`**

Use UUID primary keys, `timestamptz`, explicit check constraints for membership role/status and supervision/status values, and indexes on all foreign-key columns used by RLS or normal lookups.

- [ ] **Step 3: Add profile creation behavior without trusting user metadata for authorization**

`profiles.id` references `auth.users(id)`; profile display data may be copied from signup metadata, but no family role or authorization field comes from editable user metadata.

- [ ] **Step 4: Run database tests**

Run `supabase db reset && supabase test db`. Expected: schema tests pass.

- [ ] **Step 5: Commit**

`git commit -m "feat: add Harbor family data model"`

---

### Task 3: Add RLS and cross-family authorization tests

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_family_rls.sql`
- Modify: `supabase/tests/rls_family_access.test.sql`

**Interfaces:**
- Produces parent-safe direct-read policies for family-scoped resources.
- Later Edge Functions may still use privileged server access after performing their own authorization checks.

- [ ] **Step 1: Write failing pgTAP tests for two parents in two families**

Tests must prove Parent A can read Family A/Child A, cannot read Family B/Child B by known UUID, and cannot directly insert/update/delete another family's resources.

- [ ] **Step 2: Enable RLS on every exposed table and grant only required operations**

Policies must use `(select auth.uid())`, active family membership, and role where mutation is allowed. Direct client writes should remain denied for operations reserved for Edge Functions.

- [ ] **Step 3: Add policy-performance indexes**

Index `family_members(user_id,family_id)` and family foreign keys used in policy predicates.

- [ ] **Step 4: Verify tests and advisors locally where available**

Run `supabase test db`; then run security/performance advisor tooling. Expected: no exposed Harbor table without RLS.

- [ ] **Step 5: Commit**

`git commit -m "feat: enforce family row level security"`

---

### Task 4: Build shared Edge Function runtime/auth utilities

**Files:**
- Create: `supabase/functions/_shared/clients.ts`
- Create: `supabase/functions/_shared/errors.ts`
- Create: `supabase/functions/_shared/auth.ts`
- Create: `supabase/functions/_shared/responses.ts`
- Test: `tests/functions/shared-auth.test.ts`

**Interfaces:**
- `requireParent(req: Request): Promise<ParentContext>` returns `{ userId, accessToken, aal }`.
- `requireFamilyRole(ctx, familyId, allowedRoles): Promise<void>` rejects inactive/non-member users.
- `jsonError(code: string, status: number, message: string): Response` returns the standard error envelope.
- `adminClient()` returns a server-only Supabase client initialized from backend secret configuration.

- [ ] **Step 1: Write failing unit tests for missing/invalid Auth and family membership**
- [ ] **Step 2: Implement request-scoped user validation; do not authorize from caller-provided metadata**
- [ ] **Step 3: Implement stable JSON error codes such as `AUTH_REQUIRED`, `FORBIDDEN`, `VALIDATION_FAILED`, `DEVICE_REVOKED`**
- [ ] **Step 4: Run the function test suite**
- [ ] **Step 5: Commit**

`git commit -m "feat: add Edge Function auth foundation"`

---

### Task 5: Implement atomic family creation

**Files:**
- Create: `supabase/functions/create-family/index.ts`
- Test: `tests/functions/create-family.test.ts`
- Create via CLI if needed: migration containing a service-only transactional database function used by `create-family`.

**Interfaces:**
- Request: `{ name: string }`
- Response: `{ familyId: string, name: string, role: "owner" }`

- [ ] **Step 1: Write failing tests for unauthenticated, blank name, duplicate retry/idempotency behavior, and successful owner membership creation**
- [ ] **Step 2: Implement parent authorization and validation**
- [ ] **Step 3: Make family + owner membership + audit record atomic**

If a database function is used for atomicity, keep it service-only, revoke `EXECUTE` from `PUBLIC/anon/authenticated`, and use `SECURITY INVOKER` with the server role rather than a public `SECURITY DEFINER` shortcut.

- [ ] **Step 4: Verify cross-family RLS still holds after creation**
- [ ] **Step 5: Commit**

`git commit -m "feat: add secure family creation"`

---

### Task 6: Add child-device enrollment schema and one-time pairing

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_device_security.sql`
- Create: `supabase/functions/create-device-pairing/index.ts`
- Create: `supabase/functions/device-claim/index.ts`
- Create: `docs/runbooks/device-enrollment.md`
- Test: `supabase/tests/device_security.test.sql`
- Test: `tests/functions/device-claim.test.ts`

**Interfaces:**
- Private tables: `private.device_security`, `private.device_enrollment_tokens`.
- Pairing request: `{ childId: string }`.
- Pairing response: `{ code: string, expiresAt: string }` where the code is six digits and expires in 10 minutes.
- Claim request: `{ code: string, publicKeySpki: string, device: { displayName, model, androidVersion, supervisionMode } }`.
- Claim response: `{ deviceId: string, familyId: string, childId: string }`.

- [ ] **Step 1: Write failing tests for code format, 10-minute expiry, single-use behavior, wrong child/family, expired code, and repeated claim**
- [ ] **Step 2: Store only a keyed digest of the six-digit pairing code**

Use an Edge Function secret as the HMAC key/pepper; never store the raw code.

- [ ] **Step 3: Require a separate child-device Supabase Auth identity for `device-claim`**
- [ ] **Step 4: Bind that Auth user ID and P-256 public key to one Harbor device, consume the code, and create the public device row atomically**
- [ ] **Step 5: Run DB + function tests**
- [ ] **Step 6: Commit**

`git commit -m "feat: add cryptographic device enrollment"`

---

### Task 7: Implement device proof-of-possession and replay protection

**Files:**
- Create: `supabase/functions/_shared/crypto.ts`
- Create: `supabase/functions/_shared/device-proof.ts`
- Modify via migration: add `private.device_request_nonces`
- Test: `tests/functions/device-proof.test.ts`
- Test: `supabase/tests/device_security.test.sql`

**Interfaces:**
- Signed canonical fields: HTTP method, operation identifier, device ID, SHA-256 body digest, Unix timestamp, unique nonce/JTI.
- `requireDeviceProof(req: Request, operation: string): Promise<DeviceContext>` returns `{ deviceId, familyId, childId, authUserId }`.

- [ ] **Step 1: Write failing tests for valid signature, wrong key, modified body, wrong device ID, stale timestamp, reused nonce, and copied JWT without signature**
- [ ] **Step 2: Verify Supabase JWT subject is bound to `private.device_security.auth_user_id`**
- [ ] **Step 3: Verify ECDSA P-256/SHA-256 against stored SPKI public key**
- [ ] **Step 4: Insert nonce atomically before accepting the protected operation; duplicate nonce must fail**
- [ ] **Step 5: Check `revoked_at` on every protected call**
- [ ] **Step 6: Commit**

`git commit -m "feat: require device proof of possession"`

---

### Task 8: Add desired state, commands, signed device sync, FCM registration, and revocation

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
- Device sync response: `{ desiredState, desiredStateVersion, commands[] }`.
- Command items include stable `id` and `idempotencyKey`.

- [ ] **Step 1: Write failing tests for monotonic desired-state versions and duplicate command idempotency**
- [ ] **Step 2: Implement signed `device-sync` using `requireDeviceProof`**
- [ ] **Step 3: Implement signed FCM registration; tokens remain private and never parent-readable**
- [ ] **Step 4: Implement parent-authorized device revocation and prove the next signed sync fails immediately even with an existing JWT**
- [ ] **Step 5: Implement parent-authorized desired-state update that increments version exactly once and queues wake intent**
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
- Table: `private.notification_outbox` with event ID, target, kind, payload reference, status, attempts, next-attempt time, last-error category, timestamps.
- Dispatcher result reports claimed/sent/retried/dead counts.

- [ ] **Step 1: Write failing tests for two workers claiming the same pending event and for duplicate retry**
- [ ] **Step 2: Add concurrency-safe claim logic using short transactions / `FOR UPDATE SKIP LOCKED` semantics**
- [ ] **Step 3: Send minimal FCM payloads only; no sensitive child content in push payloads**
- [ ] **Step 4: Record retryable vs permanent failures and dead-letter after the configured attempt ceiling**
- [ ] **Step 5: Prove rerunning dispatcher cannot duplicate a completed domain effect**
- [ ] **Step 6: Commit**

`git commit -m "feat: add durable notification outbox"`

---

### Task 10: Add parent Realtime authorization baseline

**Files:**
- Create via CLI: `supabase/migrations/<CLI-generated>_realtime_authorization.sql`
- Test: `supabase/tests/realtime_authorization.test.sql`
- Modify: `docs/runbooks/supabase-development.md`

**Interfaces:**
- Parent channel naming: `family:{family_id}`.
- Only active family members may receive family-scoped Broadcast/Presence events.
- Child devices do not use Realtime as policy authority.

- [ ] **Step 1: Write failing authorization tests for Parent A subscribing to Family B**
- [ ] **Step 2: Implement family-scoped Realtime authorization using current Supabase-supported private-channel/RLS mechanism**
- [ ] **Step 3: Document which events are hints to refresh authoritative data rather than authoritative state themselves**
- [ ] **Step 4: Verify allowed and denied subscription behavior**
- [ ] **Step 5: Commit**

`git commit -m "feat: authorize family realtime channels"`

---

### Task 11: Harden secrets, logging, advisors, and CI

**Files:**
- Modify: `.github/workflows/supabase-ci.yml`
- Create: `tests/functions/security-regression.test.ts`
- Modify: `docs/runbooks/supabase-development.md`

**Interfaces:**
- CI gate must fail on DB tests, function tests, migration failures, or secret scan failures.

- [ ] **Step 1: Add tests proving logs/errors never include pairing codes, FCM tokens, Auth tokens, device nonces, private keys, or Supabase secret keys**
- [ ] **Step 2: Run security advisor and fix all Harbor-created critical findings**
- [ ] **Step 3: Run performance advisor and address missing FK/RLS indexes in the foundation schema**
- [ ] **Step 4: Add CI secret scanning and migration drift checks**
- [ ] **Step 5: Verify full local CI command sequence from a clean reset**
- [ ] **Step 6: Commit**

`git commit -m "chore: harden Supabase security checks"`

---

### Task 12: Prove the development/staging acceptance flow

**Files:**
- Create: `tests/end-to-end/platform-foundation.md`
- Create or modify: `docs/runbooks/supabase-development.md`
- Modify: `README.md` with current foundation status only after successful verification

**Interfaces:**
- End-to-end gate from GitHub Issue #1.

- [ ] **Step 1: Create Parent A and Parent B test accounts and two separate families**
- [ ] **Step 2: Prove Parent A cannot read or mutate Family B by known IDs**
- [ ] **Step 3: Create Child A, issue a six-digit 10-minute pairing code, and enroll a separate child-device Auth identity with a P-256 public key**
- [ ] **Step 4: Perform one valid signed device sync, then replay the same nonce and verify rejection**
- [ ] **Step 5: Register an FCM token, update desired state, verify an outbox wake record, and verify duplicate processing is idempotent**
- [ ] **Step 6: Revoke the device and verify a still-unexpired device Auth session immediately fails the next protected request**
- [ ] **Step 7: Run `supabase test db`, all Edge Function tests, security/performance advisors, and migration-list verification**
- [ ] **Step 8: Document exact passing evidence and remaining non-foundation work in Issue #1 / PR**
- [ ] **Step 9: Commit**

`git commit -m "test: verify Supabase platform foundation"`

---

## Definition of Done for Subproject 1

Subproject 1 is complete only when all of the following are demonstrated against the designated non-production Supabase environment:

- Parent Auth works with the configured email/password baseline and MFA support is documented/configured for later UI integration.
- Family/child/device public resources are protected by tested RLS.
- Cross-family known-ID attacks fail.
- Pairing codes are six digits, one-time, expire after 10 minutes, and are not stored in plaintext.
- Child devices use a separate Supabase Auth identity and Android-compatible P-256 public-key binding.
- Protected device operations require proof-of-possession and replayed signed requests fail.
- Revoked devices fail protected operations immediately even with an unexpired bearer token.
- Desired state is versioned and commands are idempotent.
- FCM registration tokens remain private.
- Notification delivery intent is durable and dispatcher retries are idempotent.
- Parent Realtime authorization cannot cross family boundaries.
- Security/performance advisors have no unresolved foundation-critical findings.
- CI reproduces migration + DB test + Edge Function test verification from a clean checkout.

## Deferred to Later Subprojects

This plan intentionally does **not** implement the Parent Compose UI, Child Compose UI, screen-time evaluator, Kid Space/DPC enforcement, real location history, web filtering, Get Help UX, digital activity, safety monitoring, subscriptions, or production rollout. It only creates the backend/security foundation those subprojects depend on.
