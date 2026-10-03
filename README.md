# Harbor

Harbor is an Android-first family-safety platform currently in frontend prototype and production-architecture planning.

## Current demo

The repository root contains a self-contained frontend prototype in `index.html` with browser-local demo state only. It includes:

- Parent account/family onboarding demo
- Parent dashboard
- Sofia and Mateo child-device views
- Hard screen-time limits
- Bonus-time requests and parent approval
- Parent-controlled Kid Space lockdown demo
- Parent-PIN Kid Space exit
- Child `Get Help` press-and-hold alert to parents only
- Check-ins, schedules, app access, Lost Mode, and sample activity data

The frontend demo does **not** create real accounts, pair real Android devices, enforce Android policies, contact emergency services, or provide production monitoring.

## Product architecture

The approved production direction is documented in:

- `docs/superpowers/specs/2026-10-02-harbor-production-architecture-design.md`
- `docs/superpowers/plans/2026-10-03-harbor-platform-foundation.md`

The production stack is planned as native Kotlin/Jetpack Compose parent and child Android apps, ASP.NET Core .NET 10, PostgreSQL/Supabase, FCM, SignalR, Room, Android DevicePolicyManager/Lock Task for Full Supervision, and Azure for the application tier.

## Roadmap

Harbor is decomposed into 12 production subprojects:

1. Platform foundation
2. Parent Android app
3. Child Android foundation
4. Screen-time/policy engine
5. Kid Space / managed-device system
6. Location + family safety
7. Web protection
8. Digital activity
9. Safety monitoring
10. Production security/privacy
11. Commercial platform
12. Production hardening

See the production architecture spec for the complete design and boundaries.
