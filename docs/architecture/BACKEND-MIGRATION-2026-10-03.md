# Backend migration decision — 2026-10-03

Harbor's backend direction changed from ASP.NET Core/.NET 10 + Azure application services to Supabase.

## Current backend source of truth

`docs/superpowers/specs/2026-10-03-harbor-supabase-production-architecture-design.md`

## Superseded work

- `docs/superpowers/specs/2026-10-02-harbor-production-architecture-design.md`
- `docs/superpowers/plans/2026-10-03-harbor-platform-foundation.md`
- Draft PR #2 / branch `feat/platform-foundation-task-1`

The old branch/PR must not be merged or extended as production backend work. It is historical only.

## Replacement direction

- Supabase Auth
- Supabase PostgreSQL + RLS
- Supabase Edge Functions
- Supabase Realtime
- Supabase Storage
- Supabase CLI migrations
- Firebase Cloud Messaging retained for Android wake/push
- Native Kotlin/Compose parent and child apps retained
- Android Keystore ECDSA P-256 device proof-of-possession retained

A new Supabase Platform Foundation implementation plan is required and must be approved before backend implementation resumes.
