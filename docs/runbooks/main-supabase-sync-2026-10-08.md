# Main development Supabase reconciliation — 2026-10-08

Source: main revision `b9cf86b9855edc66137620b96f09a0c6da94572f`.

The existing development project was reconciled with main. All 18 migration versions and names already matched; no schema migration or application data mutation was needed. All 13 functions were active.

Nine older function bundles were redeployed from main because they retained previous shared HTTP, domain-error and Auth context helpers: create-family, create-device-pairing, device-claim, device-sync, register-fcm, register-web-push, remove-web-push, revoke-device and update-device-state. Downloaded deployed source was compared after deployment and matched main with line endings normalized. Existing JWT verification remained enabled. The dispatcher and three parent functions already matched and were preserved.

Before deployment, main's focused backend and contracts suite passed: 150 tests, zero failures. Live checks across eight client endpoints returned allowed-origin preflight 204 and unauthenticated POST 401; an unapproved origin returned 403. The live web bundle contains the designated development public configuration.

Development Auth Site URL and exact web callback allowlist were synced to the current frontend. Existing native and acceptance callbacks were retained. After propagation, declared Auth drift was zero; all undeclared configuration values compared unchanged. No production backend changes were made.

Private registry/request tables retain RLS with no client schema/table grants. Public policies retain active-family authorization predicates. Existing advisor notices for intentional private default-deny tables, anonymous Auth roles and disabled leaked-password protection remain; no authorization was weakened to silence them.

This is source/configuration alignment and boundary smoke verification, not real signup, recovery, pairing, MFA or provider delivery acceptance. Main's web client still lacks password-recovery completion and MFA UI. Screen time, app inventory/enforcement, location, alerts, requests, SOS and several settings remain unavailable in its backend adapter. Sync does not implement those subsystems or complete Issue #1.
