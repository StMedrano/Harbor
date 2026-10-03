# Harbor

Harbor is an Android-first family-safety platform with three first-class clients:

- Native Parent Android app
- Native Child Android app
- Full-parity Parent PWA hosted on Vercel

## Current architecture

The approved controlling design is:

`docs/superpowers/specs/2026-10-03-harbor-vercel-supabase-production-architecture-design.md`

Harbor uses a strict platform split:

- **Vercel = frontend hosting/delivery** for one Next.js web app (`apps/web`) containing the Parent PWA plus public/account/help pages.
- **Supabase = backend/source of truth** for Auth, PostgreSQL/RLS, Edge Functions, Realtime, Storage, device security, desired state, commands, notification outbox, FCM dispatch, and Web Push/VAPID dispatch.
- **Native Android = device enforcement** for Parent/Child apps, including DevicePolicyManager, Lock Task/Kid Space, VpnService, WorkManager, Android Keystore, local policy enforcement, and FCM.

The previous .NET/Azure architecture, the earlier Supabase-only architecture, and their old implementation plans are superseded.

## Current implementation gate

Subproject 1 is **Supabase Platform Foundation**. Its replacement implementation plan is:

`docs/superpowers/plans/2026-10-03-harbor-vercel-supabase-platform-foundation.md`

The plan is written and awaiting explicit user approval before the selected **Native** execution begins.

The full Parent PWA is **not** part of Subproject 1. It will be implemented as Parent Client track **2B — Parent PWA Foundation**, coordinated with **2A — Parent Android Foundation** through shared contracts and acceptance tests.

## Locked platform/security decisions

- Minimum Android version: Android 10 / API 29.
- Parent Android and Child Android are native Kotlin + Jetpack Compose.
- Parent PWA is a full-parity Next.js client hosted on Vercel.
- Parent Android and Parent PWA share the same Supabase Auth tenant/account and family authorization model.
- Parent Auth supports email/password, email verification, password recovery, and TOTP MFA.
- High-risk parent actions require server-enforced AAL2 with a 15-minute step-up window.
- Child devices use a separate Supabase Auth device identity plus Android Keystore ECDSA P-256 proof-of-possession.
- Every exposed Harbor table uses tested RLS and least-privilege grants.
- Vercel does not host Harbor business authorization or privileged device logic.
- FCM and Web Push are transports, not sources of truth.
- VAPID private keys, Supabase secret/service credentials, Firebase service credentials, pairing peppers, and child private keys never ship to clients.
- Supabase Realtime is for parent UX; child enforcement uses authenticated sync and local Room state.
- Monitoring remains visible/disclosed; Harbor does not use TLS MITM and does not intentionally disable Android emergency calling.

## Development Supabase project

Current development project ref:

`bfvybxkjxilntjgndsrm`

This project is development-only. Production must use a separately isolated Supabase environment/project or another explicitly approved equivalent isolation strategy.

## Roadmap

Harbor remains divided into 12 production subprojects. Parent Client Foundations now has two separately planned tracks:

- **2A — Parent Android Foundation**
- **2B — Parent PWA Foundation**

GitHub Issue #1 is the master roadmap and execution tracker.
