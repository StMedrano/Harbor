# Harbor Supabase Production Architecture Design — Superseded

> **Status:** Superseded. Do not use this document as the controlling architecture.

Harbor's client/frontend architecture was expanded after this document was approved.

Use the current design instead:

`docs/superpowers/specs/2026-10-03-harbor-vercel-supabase-production-architecture-design.md`

The current architecture keeps Supabase as Harbor's backend/source of truth while adding a full-parity Parent PWA hosted on Vercel, alongside the native Parent Android and Child Android apps. Vercel remains frontend-only; privileged business/device logic remains in Supabase Edge Functions and the native Child app.