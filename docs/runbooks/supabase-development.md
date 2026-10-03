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
