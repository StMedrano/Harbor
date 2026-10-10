# Harbor Android usage and app reporting — design

Date: 2026-10-08
Status: written specification awaiting approval; no implementation authorized by this document alone.
Base: main `b9cf86b9855edc66137620b96f09a0c6da94572f`.

## Intent and first slice

The user selected real screen-time tracking and installed-app reporting as the first actual Android feature slice. Build on the existing unified native Harbor Family app, keeping its technical package, signing continuity, parent/child identity separation, pairing, encrypted storage, signed requests, notification lifecycle and MFA boundaries. Use the supplied/new web frontend's Harbor visual language for the corresponding native reporting surfaces. Parents can read the same reports through the existing web dashboard.

Deliver child-phone collection, permission/setup UX, truthful local summaries, authenticated development-backend reporting, and parent read-only usage/app views. App limits, blocking, location, check-ins, SOS, content/message monitoring and device-owner/anti-removal capabilities remain separate later designs. Measurement never implies enforcement.

## Approach and alternatives

Chosen: extend the native foundation and its Compose Today/Apps surfaces; add a narrow telemetry contract and read-only web adapter. This reuses the verified Keystore, identity and profile lifecycle instead of moving security state into a web bridge.

A JavaScript bridge in the remotely loaded WebView would require a separate trusted-origin/auth/bridge lifecycle and would duplicate the native foundation. A local-only usage screen is smaller but does not meet parent reporting. Neither is the selected approach. Preserve the existing web shell as a site viewer; this slice produces an update to the native Harbor Family APK, not an interchangeable update to its differently packaged WebView APK.

## Platform and permission boundary

Retain min29/target36/compile37, JDK17 and the approved pinned native build graph. Reuse platform UsageStatsManager, PackageManager and JobScheduler; no new runtime library is required.

Only a validated, paired child profile with explicit reporting opt-in collects or sends reports. Before opting in, show on the child phone that Harbor shares app names and measured app-use durations with current parents of its family. Show reporting status and a stop-sharing control. Setup and parent profiles do not collect phone usage. Usage Access requires an explicit trip to Android Settings using ACTION_USAGE_ACCESS_SETTINGS; a manifest declaration alone is not a grant. Recheck the AppOps permission after return and before every collection.

Declare PACKAGE_USAGE_STATS and a narrowly scoped MAIN/LAUNCHER package-visibility query. Do not request QUERY_ALL_PACKAGES, accessibility access, overlays, VPN, location, notification-listener, device-admin or device-owner privileges. Inventory means launchable apps visible to this Android user/profile, not every package, hidden/system component or work-profile app. Label this scope in both parent and child views.

Denying/revoking Usage Access stops usage collection immediately and shows permission-required/permission-lost, not zero minutes. Inventory sharing is separately described and can remain enabled while usage permission is absent. Stop sharing disables both collectors/uploads and clears locally retained reports; an authenticated deletion request clears hosted telemetry. Offline deletion is explicitly pending until confirmed, without granting a role change or claiming remote erasure.

## Measurements and accuracy

Report Today plus the preceding six local calendar days, with per-app foreground-use duration and an observed total. The total is the union of observed active-app intervals while the display is interactive and unlocked. Individual app durations may overlap in multi-window mode; their sum is not the total. Exclude the home launcher and system UI from the app-use total; do not classify apps into invented categories. Harbor's own foreground use remains measurable like another app.

Use queryEvents, ACTIVITY_RESUMED/PAUSED/STOPPED, SCREEN_INTERACTIVE/NON_INTERACTIVE, KEYGUARD_SHOWN/HIDDEN and shutdown/startup boundaries. Distinguish activity instances where supported; merge intervals per package and then union packages for the total. Sort events deterministically, deduplicate repeated events, clamp to the observation window and close at collection time only when the observed state supports doing so. Reboot, clock rollback, missing initial state and unknown gaps terminate or invalidate the affected segment; never carry an open interval through an unknown boundary.

Do not present queryUsageStats daily totals as exact local-day measurements: Android may expand that query to its own interval boundaries. Do not upload activity class names, raw event timelines or screen contents. Raw events are read into memory, aggregated, then discarded.

Persist only bounded aggregate intervals and coverage metadata in encrypted, child-binding-scoped local storage. Recompute overlapping ranges before replacing summaries rather than incrementing totals on each poll. Mark initial backfill and gaps as partial: Android retains events only for a few days and does not guarantee seven complete historical days. Empty/locked/null/denied results cannot establish zero usage. Measured zero is rendered only for an observed valid interval, accompanied by its coverage range.

Calendar buckets carry zone ID, local date, UTC start/end, observation-through timestamp and quality/coverage. DST uses actual elapsed time, not a fixed 24-hour denominator. On timezone or wall-clock discontinuity, start a new reporting epoch, rebuild only observable ranges and mark affected dates partial. Never silently relabel old buckets or merge overlapping epochs as complete history.

Inventory contains package identifier and bounded display label, deduplicated per package, with a capture timestamp and completeness/truncation flag. Icons are resolved locally; remote views use existing generic icons. No APKs, signatures, contacts, messages or advertising identifiers are uploaded. A package seen in usage but absent from launchable inventory has an unknown label; it is not declared installed or uninstall-confirmed. Inventory changes are known only at the next successful enumeration.

## Collection, lifecycle and offline behavior

Collect on explicit refresh, after consent/permission return, and child foreground resume. Schedule a platform periodic job with a requested 15-minute interval while opted in. Jobs are opportunistic: Doze, battery limits, force-stop and reboot can delay them. Do not promise continuous or real-time reporting. Rearm on legitimate app startup/foreground; do not add a persistent foreground service or reboot receiver in this slice.

Use the existing profile runtime and captured role/owner/enrollment-generation fencing. Cancel/join collectors and jobs before profile transition; revalidate permission, opt-in and signed child binding before upload. Late work cannot update a newer child or parent profile. Queue only one encrypted latest aggregate snapshot plus deletion state, with no bearer or Firebase token in job extras. Offline collection may update the child's own dated view, but a parent sees only the last server-confirmed report and its age. Retry replaces snapshots idempotently; it never creates duplicate duration totals.

Existing revocation, logout, key-loss and temporary parent-approval behavior remains authoritative. Known child revocation hides reports, stops work and erases the local telemetry namespace. An ambiguous/unreadable binding stays blocked rather than creating a new identity. Sharing controls do not remove enrollment or bypass fresh-MFA parent revocation.

## Additive backend contract

Add two signed child operations, `report-device-usage` and `clear-device-usage`, using the existing anonymous identity, P-256 proof, timestamp, operation/body hash and nonce machinery. Existing claim/sync/FCM operations and notification payloads are unchanged. Derive family/child/device/auth owner from verified security records; reject client-supplied authority fields and unexpected payload fields. Recheck non-revoked binding inside the same database transaction that writes/deletes telemetry to fence concurrent revocation.

The versioned report includes reporting epoch, monotonically increasing persisted sequence, permission/reporting states, observed/captured timestamps, bounded daily aggregate rows and current launchable inventory. Limit JSON to 1 MiB, 500 inventory entries and 3,500 package/day rows. Longer inventories report an explicit truncated flag; deterministic selection never implies completeness. Validate package/label lengths, supported quality enums, zone/date/range consistency, finite nonnegative integer durations, elapsed-range bounds, unique rows and timestamps. Device times are observations, not trusted server time. Refuse implausible future reports beyond five minutes; record server receivedAt separately.

One current snapshot per enrolled device replaces its predecessor atomically. Lower sequence numbers cannot replace newer data; repeated sequence with the same payload hash is a no-op, and a changed hash conflicts. Epoch changes require a higher sequence under the same binding. Clear establishes a sequence tombstone so a delayed older upload cannot recreate deleted data. New opt-in must advance that checkpoint. Retain checkpoints without app names or durations until enrollment cleanup; never reset sequence during an ordinary cold start.

Add telemetry tables under a private schema with RLS and no direct anon/authenticated grants. Expose one parent read operation, `get-device-usage`, using verified non-anonymous parent Auth and current active family membership on every request. It resolves the requested device and denies foreign/revoked/inaccessible bindings. Return only that scoped device report, capture/receipt/coverage states and report age; do not expose child keys, Auth IDs, tokens or proof material.

Latest snapshots have a 30-day server-received TTL. Parent reads exclude expired telemetry. A daily development pg_cron janitor deletes expired snapshots; it does not delete active enrollments or audit records. The implementation plan must prove the extension/job ownership and grant boundary; if this capability is unavailable, stop before rollout rather than silently leaving unlimited retention. Local summaries retain seven calendar days and at most one pending snapshot. Confirmed opt-out or existing device revocation deletes hosted app/usage payloads transactionally; audit and replay/sequence checkpoints retain no report contents.

No usage-change push or new domain-state/outbox event is introduced. Existing notification routes remain unchanged. Parent data refreshes explicitly and on foreground resume.

## UI and web integration

Native child Today shows measured observed total, coverage, collection permission, last local collection and last confirmed upload. Apps shows launchable inventory and per-app measured durations where available. All statuses distinguish no report, permission required/lost, collecting, partial, offline/pending, stale and revoked. A first run has no invented history, usage bars, battery, restrictions or progress toward a fictional daily limit.

Native parent child/device detail and web parent usage/apps views consume the scoped report contract. Preserve Harbor typography/colors/layout from the supplied web design. Replace mock zeros and unavailable reporting placeholders only with returned observations. UI charts omit unknown days or visibly mark them unknown, rather than plotting zero. Installed-app rows are read-only: remove the existing web copy/buttons promising hard stops, blocked apps or guaranteed limits from this reporting surface until enforcement exists. No optimistic success message accompanies an unsupported action.

Browser/WebView-only child profiles show native Android reporting unavailable. They cannot invoke native collectors or attest that browser timing is Android usage. Existing web signup/recovery/MFA gaps are recorded separately and do not authorize unrelated Auth work in this slice.

## Verification and rollout

Test first: deterministic interval/coverage/union/DST/reboot/clock-gap reducers; denied/empty/locked results; inventory visibility/dedup/truncation; permission loss and opt-out; cold encrypted aggregate/sequence restore; account/enrollment transitions and late-job denial; bounded offline retries and deletion tombstones.

Backend tests include signed-owner/nonce/operation denial, missing/anonymous parent denial, foreign membership, removal/revocation races, sequence/hash replay, payload limits, malformed durations/time ranges, expired reads and retention cleanup. Real local PostgreSQL proves RLS/private grants, atomic replacements, tombstone ordering and revoke/delete races. Keep existing foundation/notification contracts GREEN.

Actual API29/36 instrumentation proves Usage Access Settings return and denial, launchable-package queries, lifecycle fencing, cold persistence and large-font/light-dark accessible reporting UI. A controlled test app on a real child phone produces genuine foreground/screen-lock intervals; compare measured windows with observed timing, disclose tolerance/partial history, then verify exact parent reads on native and web. Test network loss/reconnect, permission removal, opt-out, remote revocation and independent exact fixture cleanup with audits retained. Emulator/mocked events are not real-device reporting acceptance.

After focused and required wider CI plus one fresh feature review and one test-first material fix pass, review migration dry-run and rollback, deploy only the new reviewed schema/functions to designated development, verify grants/Auth/CORS/TTL and publish a signed non-fixture native APK/ZIP. Preserve main and production; no merge is inferred from specification approval. Never put credentials, private keys, inboxes, endpoints or fixture identifiers in PR/Issue status. No anti-bypass, exact platform-wide Digital Wellbeing equivalence, continuous monitoring, app blocking or security/legal-readiness claim.

## References and approval boundary

- Android UsageStatsManager: https://developer.android.com/reference/android/app/usage/UsageStatsManager
- Android UsageEvents.Event: https://developer.android.com/reference/android/app/usage/UsageEvents.Event
- Package visibility declarations: https://developer.android.com/training/package-visibility/declaring
- Android JobInfo.Builder: https://developer.android.com/reference/android/app/job/JobInfo.Builder
- Existing approved unified design: docs/superpowers/specs/2026-10-07-harbor-family-unified-android-design.md

The user approved this first feature scope. Written-spec approval is still required before the implementation plan; the written plan must then be approved before product code, new dependencies, migrations or deployment. Native inline execution remains the chosen method. Hourly automation remains paused.
