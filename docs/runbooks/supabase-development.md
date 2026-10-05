# Harbor Supabase Development

## Baseline

- Tested in CI with Supabase CLI **2.119.0**.
- Harbor development Supabase project ref: `bfvybxkjxilntjgndsrm`.
- This project is **development-only**. Do not use it for Harbor production.
- The repository, migrations, and tests are the source of truth for application schema changes.

## First-time local setup

1. Install a current Supabase CLI compatible with the repository.
2. Run `supabase login`.
3. Run `supabase init` only if `supabase/config.toml` does not already exist. Never overwrite the committed config.
4. Link the development project with `supabase link --project-ref bfvybxkjxilntjgndsrm`.
5. Run `supabase migration list` and reconcile any drift before applying changes.

Harbor began this implementation with no application migrations in the linked development project.

## Hosted development checkpoint — 2026-10-04

The reviewed migration set through `20261004185554_realtime_authorization.sql`
was applied to development project `bfvybxkjxilntjgndsrm` using CLI 2.119.0
after a successful dry run. All 13 remote migration versions and names match
the repository. Source revision `dfc3b1374584eb2e01e430ed755827b9b25cdbb9`
passed foundation CI run `37230379057` before application.

Read-only hosted checks confirmed RLS on all five Harbor public tables and the
family broadcast receive policy. Both `anon` and `authenticated` lack private
schema usage and SELECT on device security and parent Web Push subscriptions.

Post-application advisors reported no warning/error findings. Informational
findings were reviewed as follows:

- Web Push RLS without policies is intentional: this private table has no client
  grants and is accessed only through server helpers. Do not add client policies
  merely to silence this notice.
- Eleven unused indexes are expected on the newly initialized database. Preserve
  them until representative usage provides evidence for removal.
- Three foreign keys lack covering indexes: enrollment-token family and issuer,
  and the public device `(family_id, child_id)` relationship. Assess these with
  representative queries/deletion workload and a tested migration before marking
  performance readiness complete.

References: [RLS without policies](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy),
[foreign-key indexes](https://supabase.com/docs/guides/database/database-linter?lint=0001_unindexed_foreign_keys),
[unused indexes](https://supabase.com/docs/guides/database/database-linter?lint=0005_unused_index).

The seven required custom-secret names were confirmed present and all ten
reviewed Edge Functions deployed ACTIVE on 2026-10-04, from source revision
`1b2ba03` after CI run `37232626522` passed. Secret-name verification does not
validate provider credentials or successful delivery. Configure the pairing
pepper, FCM credentials, VAPID configuration, worker key, and exact allowed
frontend origins in the appropriate Supabase secret store.
Never put their values in GitHub comments or this document. Built-in runtime
credentials are separate from the custom-secret inventory. Supabase automatically
injects `SUPABASE_DB_URL`; do not create a custom secret with that reserved name.
See [default runtime secrets](https://supabase.com/docs/guides/functions/secrets).
Verify the injected connection when functions are deployed. Review hosted Auth
settings and Realtime public-channel configuration before live acceptance; local
config and database policy checks do not verify those hosted settings.

Hosted Auth was compared with the baseline. A scoped configuration preview
identified three differences; only those declared properties were updated:
anonymous sign-ins enabled, minimum password length eight, and secure password
changes enabled. A second comparison reported zero declared-setting differences.
Hosted email confirmation and TOTP enrollment/verification were already enabled.
Other settings, including email rate limits, were preserved. Site URL remains
localhost and the redirect allowlist is empty; configure the reviewed callback
routes when testing the deployed frontend's email confirmation/recovery flows.

Live smoke checks confirmed allowed-origin preflight returns 204 for the Harbor
Vercel origin, unapproved-origin preflight returns 403, unauthenticated user
functions return 401, and the worker rejects a missing key with 403. A temporary
anonymous Auth identity received a real ES256 JWT: create-family rejected parent
access (403), device-sync rejected missing proof (403), and device-claim reached
input validation (400). The temporary identity was removed. These checks confirm
runtime startup, live Auth token handling and request boundaries; they do not
verify database access from a successful authenticated operation, parent MFA,
private WebSocket joins, saved provider credential validity, or push delivery.

Then deploy the reviewed functions and verify live Auth/MFA, private Realtime,
and actual FCM/Web Push delivery. The schema deployment alone does not establish
Subproject 1 completion or production readiness.

## Repeatable hosted acceptance

`tests/hosted/platform-acceptance.ts` is opt-in and hardcodes the development
project. It receives CLI key JSON on stdin, keeps credentials in memory, creates
two confirmed fixture parents plus an anonymous child, and removes its created
families/accounts in `finally`. Confirmation uses the admin fixture seam; this
does not test email delivery or a user's email-confirmation flow. Real password
login, MFA enrollment/challenge, deployed functions, Data API RLS, device P-256
proof, and WebSocket joins use hosted services. Audit evidence is retained.

From the repository, using installed CLI/Deno executables:

```powershell
$harborKeyJson = supabase projects api-keys --project-ref bfvybxkjxilntjgndsrm --output json
if ($LASTEXITCODE -ne 0) { throw 'Could not obtain acceptance credentials' }
$harborKeyJson | deno run --frozen --allow-net=bfvybxkjxilntjgndsrm.supabase.co --allow-env=ECE_KEYLOG tests/hosted/platform-acceptance.ts
Remove-Variable harborKeyJson
```

Do not print or persist the key JSON. The harness currently requires legacy
anon/service-role credentials for its fixture setup; it is not a production
client integration. PR CI must not run this hosted workflow or require its keys.

On 2026-10-04, live acceptance passed parent/child login, family creation and
idempotency, cross-family/child isolation, pairing, signed sync, replay denial,
TOTP/AAL2 verification, owner private-channel join, other-parent/child private
join denial, AAL1 revocation denial, fresh-AAL2 revocation, and immediate revoked
device denial. One initial owner join failed; two subsequent runs passed without
a policy change. Treat this initial join failure as unresolved intermittent
behavior until a clean full rerun after configuration repair.

The initial final gate failed consistently because a public-channel probe
subscribed. After the user disabled **Allow public access** in development
Realtime settings, a clean full harness run passed with exit code zero on
2026-10-04 at source revision `d5fa97aa433f6b297baf63f6ac3000d144a8eee9`.
The public probe was rejected with `PrivateOnly`; the owner private join passed,
other-parent/child private joins were denied, and fresh MFA authorized revocation
followed by immediate device denial. Read-only checks confirmed enrollment and
revocation audit events and zero remaining users for this fixture run.
The run identifier was `6d65239e-50e1-4153-b975-bd2390e324db`.
Existing foundation CI run `37235885153` passed on the same source revision.
The initial intermittent owner-join rejection has not reproduced in the next
three runs; retain that observation for investigation if it returns.

Actual FCM/Web Push delivery was verified on 2026-10-05 using the approved
development notification acceptance toolkit. Exact-event foreground/background
phone and browser receipts, redispatch, browser removal, phone-only delivery,
signed revocation denials and exact-fixture cleanup are recorded in
[notification acceptance](notification-acceptance.md). Zero fixture registrations
after cleanup are expected and do not mean live delivery was skipped.
The toolkit is not the production Parent Android, Child Android or Parent PWA.
Email callback acceptance and stale-MFA timing remain separate pending gates;
do not declare the whole backend foundation complete from notification evidence.

## Daily local workflow

```bash
supabase start
supabase db reset
supabase db lint --level error
supabase test db
```

When Edge Function tests exist:

```bash
deno test tests/functions
```

When shared contracts exist:

```bash
deno test packages/contracts/test/contracts.test.ts
deno check packages/contracts/src/index.ts
```

Create migrations with the CLI so migration timestamps are generated consistently:

```bash
supabase migration new <name>
```

Do not invent migration timestamp filenames manually.

## Auth baseline

Local Harbor Auth is configured for:

- email/password parent sign-up and sign-in;
- email confirmation and recovery testing;
- TOTP MFA enrollment/verification capability;
- anonymous Auth identities used only as child-device transport identities.

The child anonymous identity is not family authorization. RLS and Edge Functions must continue to deny direct family-table access unless an actual active family membership exists.

Hosted environment settings that require review outside local `config.toml` include production SMTP, CAPTCHA/abuse controls, Auth rate limits, redirect/callback origins, and email templates. Production email must not depend on the restricted default development sender.

## Secrets

Never commit `.env` files, Supabase secret/service credentials, database passwords, pairing peppers, Firebase service credentials, or the VAPID private key. Browser/native clients may receive only approved public configuration such as the Supabase URL, publishable key, and VAPID public key.

## Remote migration discipline

Do not apply experimental DDL directly to the hosted development project. Build and test migrations locally first. Intentional remote migration application happens only at the plan step that explicitly calls for it, followed by migration-history verification and Supabase security/performance advisors.

## Foundation acceptance scope

CI now runs `tests/end-to-end/platform-foundation.test.ts` against its clean local
PostgreSQL database. It exercises shared Parent Android/PWA subjects, durable
family creation/pairing, real P-256 device proof and persisted nonce rejection,
FCM/Web Push registration, desired-state round trips, independent transport
outcomes, family Data API RLS, Realtime policy reads, private staff-table denial,
stale/fresh MFA through the revocation handler, immediate proof denial after
revocation, and audit persistence.

The harness supplies verified identity fixtures at the Auth seam and controlled
push transports. It does not establish live JWT issuance/validation, an actual
WebSocket handshake, MFA enrollment/challenge against hosted Auth, or external
push delivery. Those hosted checks remain required before foundation completion.
CI uses a disposable local database; do not run reset commands against hosted
projects. Migration application requires reviewed changes and history comparison.

Hosted server secrets include `HARBOR_PAIRING_PEPPER`, `FCM_SERVICE_ACCOUNT_JSON`,
`VAPID_SUBJECT`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, and
`HARBOR_OUTBOX_WORKER_KEY`; store values in Supabase secrets, never this runbook.
Review allowed Auth redirect origins and TOTP/anonymous Auth settings before
live acceptance. Production remains separately unassigned.

## Browser function boundary

Configure `HARBOR_ALLOWED_ORIGINS` as a comma-separated list of exact approved
parent frontend origins (scheme, host, port; no trailing slash). Requests with an
Origin header outside that list are denied. Native/server calls without Origin
continue through normal authentication. Browser preflight does not authenticate;
actual calls always do. No wildcard or cookie credential allowance is enabled.
The shared HTTP wrapper adds CORS to successful and error responses and converts
known SQL domain errors into sanitized stable Harbor error codes.
