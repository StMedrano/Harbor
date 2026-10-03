# Harbor

Harbor is an Android-first family-safety platform. This repository contains the current frontend prototype plus the approved production architecture and the first production implementation plan.

## Current frontend prototype

`index.html` is a lightweight loader for the exact current Harbor demo stored in `frontend-bundle/part-00.txt` through `part-05.txt`. The bundle reconstructs the verified browser demo byte-for-byte and keeps the prototype isolated from future production code.

The prototype includes:

- Parent account/family onboarding demo
- Parent dashboard
- Sofia and Mateo child-device views
- Hard screen-time limits
- Bonus-time requests and parent approval
- Parent-controlled Kid Space lockdown demo
- Parent-PIN Kid Space exit
- Child `Get Help` press-and-hold alert to parents only
- Check-ins, schedules, app access, Lost Mode, and sample activity data

The frontend demo is a **behavior/design reference only**. It does not create real accounts, pair real Android devices, enforce Android DevicePolicyManager policies, contact emergency services, or provide production monitoring.

## Production architecture

Read:

- `docs/superpowers/specs/2026-10-02-harbor-production-architecture-design.md`
- `docs/superpowers/plans/2026-10-03-harbor-platform-foundation.md`
- GitHub Issue #1 — **Complete Harbor production app — 12-subproject roadmap**

Approved production stack:

- Native Kotlin + Jetpack Compose Parent Android app
- Native Kotlin + Jetpack Compose Child/DPC Android app
- Android 10 / API 29 minimum for V1
- ASP.NET Core .NET 10 modular-monolith backend
- EF Core + PostgreSQL hosted by Supabase
- Room for Android local state
- Firebase Cloud Messaging for wake-up/notifications
- SignalR for realtime parent sessions
- Android DevicePolicyManager + Lock Task for Full-Supervision Kid Space
- Android VpnService + Harbor Browser for web protection
- Azure Container Apps, Azure Key Vault, and Application Insights/OpenTelemetry for the application tier

## Planned repository structure

Production code should grow into this structure instead of modifying the compressed prototype bundle:

```text
Harbor/
├── apps/
│   ├── android-parent/
│   ├── android-child/
│   └── admin-web/
├── services/
│   ├── Harbor.Api/
│   ├── Harbor.Application/
│   ├── Harbor.Domain/
│   ├── Harbor.Infrastructure/
│   └── Harbor.Worker/
├── packages/
│   ├── Harbor.Contracts/
│   └── policy-engine-spec/
├── infrastructure/
│   ├── docker/
│   ├── database/
│   ├── migrations/
│   └── azure/
├── docs/
└── tests/
```

## Ordered build roadmap

1. Platform foundation
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

Each subproject gets its own Superpowers design/spec/plan cycle. Subproject 1 already has its approved implementation plan in `docs/superpowers/plans/2026-10-03-harbor-platform-foundation.md`.

## Safety behavior already decided

- Kid Space is parent controlled. In Full Supervision it becomes real managed-device kiosk/Lock Task behavior with parent-approved apps only.
- Allowed Kid Space apps still obey screen-time, schedule, block, and per-app rules.
- The child cannot grant themselves bonus time.
- `Get Help` is a deliberate press-and-hold urgent alert to parents; Harbor does not automatically call 911/emergency services.
- Harbor must not interfere with Android's own emergency-call capability.
- Monitoring is transparent to the child; Harbor is not intended to be hidden surveillance software.

## Master task

Track the complete production build in GitHub Issue #1. Do not close it until all 12 subprojects are implemented, tested, merged, and have passed staging, security, privacy/compliance, and store-release gates.
