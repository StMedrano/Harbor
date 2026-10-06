# Platform Foundation Acceptance — 2026-10-06

Subproject 1 implementation and development acceptance are verified on
`feat/notification-acceptance-toolkit` at source revision
`3aa25b04a72cb5dd9adc91a451e24fcaec0a6a37`.
[CI run 37501908420](https://github.com/StMedrano/Harbor/actions/runs/37501908420)
passed all foundation, notification-web and notification-android gates.
The database suite passed 221 assertions across 16 files from a clean reset.
This record reconciles the approved foundation plan's definition of done;
it does not declare the whole Harbor app complete or authorize a merge.

## Definition of done

| Requirement | Verified evidence |
| --- | --- |
| Migration-backed, reproducible schema | Clean-reset CI; all 15 development migration versions/names match the repository. |
| Configured Auth/TOTP and recent AAL2 | Hosted parent/child login, real TOTP challenge and fresh-MFA revocation; real unexpired AAL2 older than 900 seconds denied without revocation, then fresh TOTP succeeded. |
| Family/child/device RLS and known-ID isolation | Database RLS suites and shared backend end-to-end test; hosted cross-family/child Data API denial; forced RLS and read-only client grants independently inspected. |
| Private staff authorization | Staff database/Auth tests and shared acceptance deny client reads of private staff state. |
| Pairing boundaries | Device-security database/function tests cover six-digit input, one-time/ten-minute/keyed-digest storage, one active code and five-attempt invalidation. |
| Bound device Auth, P-256 proof, replay and immediate revocation | Real cryptographic regression, persisted lifecycle acceptance, hosted signed sync/replay/revocation and actual Android Keystore recipient. |
| Versioned desired state, idempotent commands and private FCM | Desired-state/BOLA tests and shared persisted lifecycle; real Android signed sync and backend FCM registration. |
| Private per-installation Web Push and server VAPID | Web Push database/function/lifecycle tests; real browser registration, receipt and removal. |
| Independent, idempotent FCM and Web Push outbox | Durable outbox/integration tests include invalid Web Push cleanup without blocking FCM; actual dual-transport receipts, redispatch and phone-only delivery. |
| Private family Realtime | Database authorization and hosted owner join, other-parent/child denial, public-channel rejection. |
| Shared V1 contracts and stable error semantics | Contract/function/security suites and type-checks in successful CI. |
| Clean DB/function/contract/security/end-to-end suites | All required CI jobs passed on the exact source revision above. |
| Reviewed security/performance advisors after development DDL | Two measured enrollment lookup indexes applied after RED/GREEN and exact dry run; remaining findings reviewed below. |
| Production/development separation | Environment mapping tests and strict release guard; acceptance preview build refuses production; staging/production backends remain explicitly unassigned. |
| Full Parent PWA deferred | No production Parent Android, Child Android or Next.js Parent PWA is claimed by the acceptance toolkit. |

Hosted email confirmation and password recovery were also verified using an
owned disposable account. Old-password rejection and new-password acceptance
were independently reported, and exact account/session cleanup was checked.
Notification and stale-MFA fixture cleanup left zero operational/Auth fixture
rows; enrollment/revocation audit records were retained. No credentials,
personal inboxes or fixture identifiers are part of this record.

## Reviewed findings and limits

- Private Web Push RLS has no client policies intentionally: client grants are
  absent and server helpers own access.
- Generic anonymous-role notices are reviewed against actual family/self
  predicates, denied direct client writes and device-isolation tests. Preserve
  anonymous device Auth and authorization rather than weaken either to hide a
  notice.
- Enrollment family/issuer lookups changed from full scans of 20,000 synthetic
  rows to indexed lookups. Existing device indexes handled the composite
  lookup across 30,000 synthetic rows. The residual composite FK notice is
  reviewed for this workload; production skew/scale and complete cascade
  latency still need later production performance testing.
- Preserve the nonce cleanup and newly added enrollment indexes despite
  unused-index notices on the empty development tables.
- Live delayed-duplicate injection was not performed. Automated event-correlation
  tests cover delayed duplicate rejection; do not invent additional receipt
  evidence.
- The earlier intermittent hosted owner-join failure did not recur in subsequent
  accepted runs. Investigate if it returns; this is not a reliability SLA.

See [development operations](supabase-development.md),
[notification acceptance](notification-acceptance.md) and
[environment mapping](environment-mapping.md) for boundaries and procedures.

## Integration and next scope

The approved integration path is the existing focused draft PR workflow.
[PR #15](https://github.com/StMedrano/Harbor/pull/15) targets the foundation branch;
[PR #13](https://github.com/StMedrano/Harbor/pull/13) targets the approved-plan branch;
[PR #3](https://github.com/StMedrano/Harbor/pull/3) targets main.
[PR #14](https://github.com/StMedrano/Harbor/pull/14) remains a separate open draft.
Do not infer permission to merge/close any of these or overwrite their branches.
Review the actual stack and any overlap when integration is explicitly requested.
Main and production deployments remain unchanged.

Issue #1 remains open. Next roadmap work is Subproject 2A Parent Android and
2B Parent PWA foundations, each with a separately reviewed design and approved
implementation plan. Preserve the same backend/account and authorization model,
API 29 minimum for native Android, frontend-only Vercel and existing Kombai
artifacts. Do not start product scaffolding or dependencies before those gates.
The later product/security/privacy/legal/store/launch subprojects remain pending.