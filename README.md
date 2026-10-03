# Harbor

Harbor is an Android-first family-safety platform. This repository contains the current frontend prototype plus the approved product architecture and Superpowers planning documents.

## Current frontend prototype

`index.html` is a lightweight loader for the exact current Harbor demo stored in `frontend-bundle/part-00.txt` through `part-05.txt`. The bundle reconstructs the verified browser demo byte-for-byte and keeps the prototype isolated from future production code.

The prototype includes parent onboarding/dashboard, Sofia and Mateo child-device views, hard screen-time limits, bonus-time requests/approval, parent-controlled Kid Space lockdown, parent-PIN exit, child Get Help, check-ins, schedules, app access, Lost Mode, and sample activity data.

The frontend demo is a **behavior/design reference only**. It does not create real accounts, pair real Android devices, enforce Android DevicePolicyManager policies, contact emergency services, or provide production monitoring.

## Current production architecture

The current controlling backend architecture is:

- `docs/superpowers/specs/2026-10-03-harbor-supabase-production-architecture-design.md`
- GitHub Issue #1 — **Complete Harbor production app — 12-subproject roadmap**

The previous `.NET/Azure` backend architecture and `2026-10-03-harbor-platform-foundation.md` implementation plan are **superseded** and must not be used for new implementation work.

Current production stack:

- Native Kotlin + Jetpack Compose Parent Android app
- Native Kotlin + Jetpack Compose Child/DPC Android app
- Android 10 / API 29 minimum for V1
- Supabase Auth for parent identity and separate child-device session transport
- Supabase PostgreSQL + RLS for family-scoped data
- Supabase Edge Functions for privileged/security-sensitive application logic
- Supabase Realtime for authorized parent-session updates
- Supabase Storage for private export/support artifacts
- Room for Android local/offline state
- Android Keystore ECDSA P-256 proof-of-possession for child devices
- Firebase Cloud Messaging for child-device wake-up/notifications
- Android DevicePolicyManager + Lock Task for Full-Supervision Kid Space
- Android VpnService + Harbor Browser for web protection

The currently connected development Supabase project is `bfvybxkjxilntjgndsrm`. Production must use an appropriately isolated production environment/project before launch.

## Planned repository structure

```text
Harbor/
├── apps/
│   ├── android-parent/
│   ├── android-child/
│   └── admin-web/
├── supabase/
│   ├── config.toml
│   ├── migrations/
│   ├── seed.sql
│   └── functions/
├── packages/
│   ├── contracts/
│   ├── policy-engine-spec/
│   └── test-fixtures/
├── docs/
└── tests/
    ├── database/
    ├── functions/
    └── end-to-end/
```

## Ordered build roadmap

1. Supabase platform foundation
2. Parent Android foundation
3. Child Android foundation
4. Screen-time and policy engine
5. Kid Space / DPC
6. Location and family safety
7. Web protection
8. Digital activity
9. Safety monitoring
10. Security/privacy/compliance hardening
11. Commercial platform
12. Production hardening and staged launch

Each subproject gets its own Superpowers design/spec/plan cycle. **Subproject 1 requires a new Supabase Platform Foundation implementation plan after the current written architecture spec is reviewed and approved.**

## Core safety/security decisions

- Kid Space is parent controlled. In Full Supervision it becomes real managed-device kiosk/Lock Task behavior with parent-approved apps only.
- Allowed Kid Space apps still obey screen-time, schedule, block, and per-app rules.
- The child cannot grant themselves bonus time.
- Get Help is a deliberate press-and-hold urgent alert to parents; Harbor does not automatically call 911/emergency services.
- Harbor must not interfere with Android's own emergency-call capability.
- Monitoring is transparent to the child; Harbor is not intended to be hidden surveillance software.
- Parent family reads may use the Data API only behind tested Row Level Security.
- Child devices never receive parent credentials or direct family-table access.
- Protected child operations require both a separate Supabase Auth session and Android Keystore ECDSA proof-of-possession.
- Revoked devices are checked against current backend state rather than relying only on access-token expiry.
- Secret/service-role credentials never ship in mobile apps or source control.

## Master task

Track the complete production build in GitHub Issue #1. Do not close it until all 12 subprojects are implemented, tested, merged, and have passed staging, security, privacy/compliance, and store-release gates.
