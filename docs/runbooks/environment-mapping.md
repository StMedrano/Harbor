# Harbor Environment Mapping

Harbor keeps web delivery and backend state explicitly paired per environment. Vercel is frontend-only; Supabase is the backend/source of truth.

## Required mapping fields

Every environment record must identify:

- Vercel environment (`development`, `preview`, or `production`)
- Supabase project name/ref
- Supabase project URL
- browser-safe Supabase publishable-key identifier (never the key value in this file)
- allowed Auth redirect/callback origins
- Web Push VAPID configuration identifier (public/private values remain in their proper secret stores)
- Firebase/FCM credential identifier

## Current mapping

| Harbor environment | Vercel environment | Supabase project ref | Supabase URL | Publishable-key identifier | Allowed redirect/callback origins | VAPID config identifier | FCM credential identifier |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Development | development / local | `bfvybxkjxilntjgndsrm` | `https://bfvybxkjxilntjgndsrm.supabase.co` | `HARBOR_DEV_SUPABASE_PUBLISHABLE_KEY` | localhost/approved development origins only | `HARBOR_DEV_VAPID` | `HARBOR_DEV_FCM` |
| Staging | preview | `UNASSIGNED` | `UNASSIGNED` | `HARBOR_STAGING_SUPABASE_PUBLISHABLE_KEY` | assigned staging/preview origins only | `HARBOR_STAGING_VAPID` | `HARBOR_STAGING_FCM` |
| Production | production | `UNASSIGNED` | `UNASSIGNED` | `HARBOR_PROD_SUPABASE_PUBLISHABLE_KEY` | production origins only | `HARBOR_PROD_VAPID` | `HARBOR_PROD_FCM` |

## Hard safety rule

**Production must never use Supabase project ref `bfvybxkjxilntjgndsrm`.**

Before any production deployment, CI/release checks must fail if a production Vercel environment or production configuration references that development project ref or its development project URL.

## Configuration ownership

- Browser-safe values may be configured in Vercel for the Parent PWA: Supabase URL, Supabase publishable key, and VAPID public key.
- Supabase backend secrets hold pairing pepper material, VAPID private key material, Firebase service credentials, and any Supabase secret/server credential used by Edge Functions.
- GitHub Actions PR CI must run against local Supabase and must not need production credentials.
- Redirect/callback origins must be reviewed whenever a Vercel domain changes; preview origins must not silently become production-allowed origins.

Staging and production remain `UNASSIGNED` until separately provisioned. Do not invent project refs or secret values to fill those cells.

## Enforced repository and release checks

PR CI runs `tests/release` and `scripts/check-environment-mapping.ts`. The current
production record may remain explicitly `UNASSIGNED`, but missing/duplicate records,
inconsistent project URLs, and the development project in the production record fail.
This checks repository mapping; it does not inspect remote Vercel settings.

Before any production build/deployment, run the strict check in that environment:

```sh
deno run --frozen --allow-read=docs/runbooks/environment-mapping.md --allow-env=HARBOR_SUPABASE_PROJECT_REF,NEXT_PUBLIC_SUPABASE_URL scripts/check-environment-mapping.ts --production
```

Set `HARBOR_SUPABASE_PROJECT_REF` and `NEXT_PUBLIC_SUPABASE_URL` to the reviewed
production backend. The strict check refuses unassigned or development configuration.
Production is currently unassigned, so this gate intentionally blocks a production
release. Integration into the future Parent PWA build is required when `apps/web`
is implemented; no production deployment is enabled by this foundation change.
