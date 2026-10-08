# Harbor Supabase Platform Foundation Implementation Plan — Superseded

> **Status:** Superseded. Do not execute this plan.

This plan predates Harbor's approved Vercel + Supabase architecture and must not be resumed or implemented unchanged.

Use these documents instead:

- Architecture: `docs/superpowers/specs/2026-10-03-harbor-vercel-supabase-production-architecture-design.md`
- Replacement Subproject 1 plan: `docs/superpowers/plans/2026-10-03-harbor-vercel-supabase-platform-foundation.md`

The replacement plan preserves Supabase as Harbor's backend/source of truth, Vercel as frontend-only hosting for the later full-parity Parent PWA, native Android Parent/Child apps, server-enforced recent AAL2 for high-risk actions, Web Push/VAPID support from Supabase, shared parent contracts, and explicit environment mapping.

Native execution remains the selected execution method, but implementation starts only after the replacement plan is explicitly approved.