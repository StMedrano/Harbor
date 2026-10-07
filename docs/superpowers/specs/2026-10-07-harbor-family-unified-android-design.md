# Harbor Family — Unified Android App Design

**Status:** Draft for written specification approval. The user has selected one product app and the parent/child entry flow. This document proposes the implementation boundaries; it does not authorize product code, dependency changes or deployment. A written implementation plan follows specification approval.

**Baseline:** Parent Android revision `be539efb3787f31065e56bce3c3fca00812d31f2`, all six CI gates passed in `37676497952`. Issue #1 and the approved backend/security contracts remain authoritative. This is an Android product-structure amendment to the separate Parent/Child app assumption, not a backend authorization rewrite. Parent PWA remains unchanged. Hourly work remains paused.

## User outcome

Users install one native Android app named **Harbor Family** on both parent and child phones. The supplied **Parent and Child Preview.html** is the latest visual reference.

Fresh setup asks whose phone this is:

- **Parent:** sign in or create an account; complete required email verification; successful verified authentication opens the parent dashboard and menu.
- **Child:** enter the fresh pairing code issued by a parent; successful confirmed enrollment opens the child dashboard. Children do not need a parent email/password or a separate email account. Internally, pairing establishes the already-approved child device identity.

Login, account creation, recovery and unpaired child setup show no dashboard menu. A valid restored parent session opens the parent dashboard. A valid restored child enrollment opens the child dashboard. Invalid/expired pairing codes keep the user on pairing; a local role choice alone never opens an authenticated dashboard.

## Approach and alternatives

**Recommended:** evolve the existing native app into one application with isolated parent and child profiles and role-aware entry/navigation. Reuse the implemented parent screens and existing child enrollment/proof transport contracts.

A new Android package would give a cleaner technical name but require new Firebase configuration and an installation/session migration; it adds no needed user-facing capability. Retaining two public APKs would preserve the old structure but would not meet the user's one-app requirement.

For this development transition, retain technical application ID `dev.stmedrano.harbor.parent`, its signing identity and existing callback scheme. Change the product/launcher name to Harbor Family. Existing parent installations can update without intentionally discarding their encrypted sessions or installation identity. Directory/package renaming is not required for the initial slice. A future production package choice is a separate release decision.

The acceptance APK remains an independent development test tool, not a second product users must install. Do not modify its active fixtures or move its private keys into Harbor Family. Existing acceptance-tool enrollments require controlled cleanup/re-pairing to use the new product installation; they cannot be transparently migrated across Android application sandboxes.

## One application, separate security identities

One active product role per installation. Parent and child credentials are never interchangeable:

- Parent profile: existing verified Supabase account, encrypted Auth/session/PKCE managers, family authorization and recent-MFA enforcement.
- Child profile: separate anonymous device Auth identity, newly generated non-exportable Android Keystore ECDSA P-256 key, server-confirmed device/family/child binding, existing signed registration/sync/replay/revocation protocol.
- Isolate client instances, encrypted storage namespaces, key aliases, caches, jobs and in-memory state. Do not reuse a mutable parent Auth client/session manager for child requests.
- Auth links must match the active parent flow or an explicitly initiated temporary parent-approval flow. An incoming link or push cannot change the device role or grant the parent dashboard.
- A stored mode hint is not authorization. Resolve the active dashboard from validated profile state and confirmed enrollment. Corrupt/ambiguous profile records fail closed with recovery guidance.
- After confirmed parent cleanup, erase dormant parent Auth/PKCE credentials before activating the child profile. Separate namespaces do not justify retaining a parent account on a child phone; temporary approval credentials are cleared after success or cancellation.
- Child requests cannot read parent family tables or self-approve parental actions. Backend authorization remains authoritative.
- Never bundle service/worker/provider credentials or private device keys. Preserve backup exclusions, sensitive-screen protection and secret-free logging.

Refactor startup into a small role coordinator that constructs only the active profile's runtime. Reuse the existing parent implementation and fitting child protocol components; do not copy the test harness UI or its plaintext token/receipt preferences into the product. Preserve the approved build pins and strict verification; the implementation plan must prove any shared dependency/build changes.

## Pairing and child dashboard foundation

Reuse the existing pairing-code issuance/claim contract, anonymous child session, Keystore public-key enrollment and device proof/signature implementation. Persist a binding only after a confirmed server response. Failed or interrupted enrollment must not erase an existing confirmed binding, duplicate domain effects, or display a paired dashboard based on a typed code alone.

The initial child dashboard uses the preview's **Today / Apps / About** structure with truthful availability:

- Today: confirmed pairing/device status, last successful signed sync and explicitly stale/offline last-known state. Show a neutral greeting when the existing child-authorized contract does not provide a display name; do not read parent-only tables to obtain it.
- Apps: actual authorized inventory/policy data when implemented; otherwise an explicit unavailable/empty state. No invented app usage, installed-app list, approvals or functional-looking no-op actions.
- About: explain the currently implemented permissions, collection, synchronization and supervision capabilities. Do not repeat preview promises about location, messages, blocking, calls or emergency access before those capabilities exist and are tested.

Screen-time rings, enforcement/paused states, SOS/check-ins, app requests/approvals, maps/location, filtering and safety monitoring remain their approved roadmap subprojects. A desired-state request alone is not proof of local enforcement. This first unification slice does not claim the full child product or anti-bypass protection.

## Notifications and lifecycle

Use one native Firebase messaging entry point. Dispatch work using the validated active profile plus captured owner/enrollment generation, not a role field supplied by a push message. Keep the existing parent and child wire formats and validators unchanged. Wrong-profile, stale-owner/binding and inaccessible hints must not publish UI or mutate state.

Only the active profile owns the installation's current token registration. Profile transitions stop old collectors/jobs, hide old views immediately, fence late callbacks and confirm the required old registration/session cleanup before activating the new profile. Offline or unconfirmed cleanup must be reported honestly and block a potentially mixed registration transition.

Child sync remains authenticated/signed and authoritative. Parent Realtime remains parent UX. Push receipt and provider acceptance remain separate evidence. Cold start, token rotation, offline/reconnect, duplicate delivery and session/enrollment revocation require actual acceptance.

## Role changes and parent approval

Role selection is setup, not a free dashboard toggle on an enrolled child's phone.

- Parent to child: explicitly sign out the current parent installation and confirm relevant remote cleanup before pairing as a child.
- Child to parent or removal/re-pairing: require explicit authorization from an active parent of the enrolled family and fresh MFA for the existing high-risk device revocation action. A narrowly scoped temporary parent approval session must not expose the parent dashboard or overwrite the active child runtime. Clear it after approval/cancellation.
- Confirm server revocation/cleanup before deleting child keys or enrollment locally. Refused/offline approval leaves the existing child profile intact.
- External revocation, key loss or an invalid restored child credential shows an appropriate blocked/recovery state; it does not automatically grant a child access to parent controls.

Supervised Android provisioning, uninstall/data-reset resistance and device-owner permissions remain separately designed capabilities. Do not claim foundation-only standard mode prevents platform-level removal or reset.

## Migration and acceptance

Preserve the parent 0.4 account/session/family cache/pending-operation/notification behavior when upgrading. Introduce the role chooser for unconfigured installations, not as a forced reset for existing parent users. Preserve child identities and wire contracts on the backend; provision a new product installation legitimately rather than copying acceptance-app credentials.

Required proof includes:

1. Parent create/sign-in/verification/recovery/restoration opens only parent content; logout/session loss removes protected UI.
2. Child invalid/expired/consumed code denial; confirmed claim and exact binding; interrupted retry without duplicate effects; signed sync and encrypted cold-start recovery.
3. No cross-profile bearer/key/cache/notification access; wrong-role message denial; old-generation completion cannot repopulate a newer role.
4. Parent-authorized recent-MFA removal; stale/foreign/refused approval causes no child cleanup or role escape; immediate revoked-child denial.
5. Actual API29/36 Keystore, role navigation, Back, backup exclusions, large-text and light/dark native checks; required wider CI.
6. Real designated development parent/child phones with the same Harbor Family APK, exact event/provider-versus-observed-receipt evidence, and independently verified exact disposable-fixture cleanup with audits retained.

Development rollout is limited to separately reviewed changes after matching GREEN and approval gates. Main, production, unrelated designs and the existing notification checkout remain preserved. No new payload/schema/privileged permission change is inferred from the HTML preview. Implementation must stop at actual configuration/device/credential/deployment blockers and never ask for private keys/passwords in chat.

## Approval boundary

Written specification approval permits a focused implementation plan with explicit milestones, migration/rollback, exact pins and test-first acceptance. Written-plan approval is required before implementing this new combined-app/child subsystem. Native inline execution is already the user's preference; do not repeat the execution-method menu.


