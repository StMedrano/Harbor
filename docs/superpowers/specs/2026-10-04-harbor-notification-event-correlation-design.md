# Harbor notification event correlation

## Status and agreed intent

The user approved the approach on 2026-10-04: reuse the existing V1
`resourceId` field as a stable UUID for each desired-state notification event.
This written spec awaits explicit approval. No implementation plan or backend
change is authorized by approach approval alone. Native execution remains the
selected method once the written spec and subsequent plan are approved.

Issue #1, the approved Vercel/Supabase production architecture, and the approved
notification acceptance toolkit remain the source of truth. This spec amends
the toolkit's receipt-correlation design and its Task 6 acceptance requirements.
It does not replace the rest of those documents or close the roadmap.

The observable outcome is exact correlation between a trusted outbox event and
browser/Android receipt hints across successive desired-state versions. A late
duplicate from an earlier version must never satisfy a later version's receipt
gate. Push remains a read-only hint; authenticated signed sync remains the
authority for desired state.

## Current problem and selected seam

At toolkit revision `b7fa0f2`, desired-state routes include `version`, `kind`,
`familyId`, `childId`, and `deviceId`. Successive changes have identical routes.
Receive timestamps and receipt-count baselines cannot distinguish a delayed
duplicate from a new event. The classifier consequently leaves later versions
unverified. An outbox worker `no_op` also does not establish provider acceptance.

`NotificationRouteRefV1` already permits an optional UUID `resourceId`. Shared
route validation, the browser receipt store, and the Android receipt store
already accept that field. Command notifications use it for the command UUID.
Reuse this seam without adding a contract field, envelope version, public API,
table, column, extension, dependency, or secret.

The alternative of adding an explicit `eventId` or a V2 envelope would require
broader parser and compatibility changes. Timestamp-only correlation cannot
meet the acceptance requirement. The approved approach is the smaller change.

## Routing semantics

For `kind: "device.state.changed"`, `resourceId` identifies the immutable
notification event associated with one desired-state version. It is an opaque
UUID, not the current desired-state object, an outbox row ID, or a capability.
The same event UUID appears in every transport/recipient row for that event.
Different desired-state versions have different event UUIDs.

For `kind: "device.command.created"`, existing command-ID semantics remain
unchanged. The route kind defines what the resource UUID identifies. Clients
must not infer authorization, fetch a new resource endpoint, or apply a domain
mutation from this identifier.

A new desired-state route has exactly these fields:

```ts
{
  version: 1,
  kind: "device.state.changed",
  familyId: "<family UUID>",
  childId: "<child UUID>",
  deviceId: "<device UUID>",
  resourceId: "<notification event UUID>"
}
```

Only minimal routing identifiers are carried. Desired-state content, account
credentials, provider tokens, keys, timestamps, and worker credentials remain
outside the push payload. UUID validation and unknown-field rejection remain.

## Backend ownership and persistence

Append a migration replacing `private.harbor_record_device_notification()`.
Do not edit applied migrations. Preserve its security-definer search path,
private grants, transactional trigger behavior, event-key construction,
recipient selection, and existing command routing.

For a new desired-state event, generate one UUID with the existing PostgreSQL
UUID facility before fanout. Add it to the common route sent to the existing
private enqueue helper. Persist it in `notification_outbox.route_payload`.
FCM and all Web Push recipient rows share that UUID even though their outbox
row IDs differ. Event generation and all intended transport rows remain in the
same domain transaction; rollback must leave no partial committed event.

The authoritative mapping is the existing event key
`desired-state:<device UUID>:<desired-state version>` plus the persisted route.
The version does not need to become a new payload field.

If intent creation encounters existing rows for that same event key, reuse their
common valid resource UUID. Never generate a second identity for an existing
identified event. If existing rows have conflicting IDs, invalid IDs, or a mix
of legacy and identified routes, fail the transaction rather than add ambiguous
rows. If all existing rows are legacy routes without `resourceId`, preserve the
legacy shape when reusing that event; it remains unverified for exact receipt
correlation. Normal unchanged-version updates continue to create no new intent.

Preserve the enqueue helper's conflict behavior: duplicate event/transport/target
insertion does not overwrite the existing route. Worker retries and redispatch
use the stored route and cannot regenerate or change its event UUID. No backfill,
rewrite, or forced resend of existing queued/sent legacy rows is included.

## Operator and recipient evidence

After a real harmless state change, the trusted operator reads only the exact
fixture event's outbox metadata through connected Supabase SQL. Validate the
event key/version, family/child/device, each recipient target, and one shared
valid resource UUID across all expected transport rows before dispatch.

Checkpoint that mapping in restricted, ignored operator evidence. If a mapping
already exists for the version, conflicting input is refused. A different
version cannot reuse a prior event UUID. Subscription IDs remain the trusted
whitelist; no endpoints, tokens or keys enter evidence. No new private Data API
or arbitrary worker dispatch path is introduced.

The existing browser and Android stores retain the full validated minimal route,
including `resourceId`, with receive time. Add focused recipient checks proving
the field survives persistence and malformed/extra-sensitive fields are refused.
No product UI, navigation, policy application or background scheduler is added.

Correlation requires the trusted exact-event mapping and exact route equality,
including `resourceId`. Preserve the observation start, baseline count and
120-second observation window. These remain supplementary boundaries; they do
not substitute for event identity. A receipt from an older event fails even if
it arrives after the new baseline and within the new observation window.

New identified events can be correlated on any desired-state version. Legacy
routes and missing, conflicting or mismatched event mappings remain unverified.
Duplicate hints may produce multiple local receipts for the same event. They
do not create another authoritative mutation or inflate the event count.

Provider acceptance and receipt remain separate. Only a verified `sent` outcome
can establish provider acceptance for classification. `retry` and `dead_letter`
retain their existing classifications. `no_op` remains unverified by itself;
for the redispatch gate, inspect trusted persisted `sent` state and verify the
original identity is unchanged. A timeout without receipt remains unverified.

## Compatibility and rollout

Keep V1 and the existing optional UUID field. Existing generic route parsers
continue accepting both legacy and identified messages. Acceptance dispatch and
correlation helpers must explicitly recognize the new six-field state route;
their current five-field exact-match check cannot be left in place.

Land recipient/operator compatibility and tests before applying the new trigger
in development. Verify the appended migration with a fresh database reset and
the existing required CI gates. Confirm the development project's migration
state before applying the authorized change. Do not infer a successful migration
from a recorded filename or secret-name inventory.

This plan covers development only. No production migration, main merge, Vercel
product deployment, Supabase credential change, or production rollout is
included. Existing legacy rows remain intact and observable, with weaker receipt
evidence explicitly reported.

## Test-first acceptance

The written plan must name meaningful RED-before-GREEN tests for:

- One state change produces a shared valid event UUID in FCM and every expected
  Web Push route; the next state version produces a different UUID.
- Repeated intent/enqueue operations and worker retries preserve the persisted
  event UUID; unchanged-version updates create no additional event.
- Existing identified rows reuse their UUID; all-legacy rows remain legacy;
  conflicting/malformed/mixed identities fail without partial fanout.
- Command routes retain their existing command resource UUID.
- Browser and Android receipt persistence preserve the event UUID and continue
  rejecting malformed routes and extra sensitive content.
- Operator dispatch rejects wrong event keys, foreign routes/targets, mixed
  resource UUIDs, missing recipient rows, and reused identities across versions.
- A delayed earlier receipt cannot satisfy a later event, even after baseline
  capture; a matching current-event receipt can pass on a later version.
- `sent` without receipt, missing mapping, legacy receipt, timeout and `no_op`
  without prior persisted evidence never establish verified delivery.
- Duplicate receipt hints represent one authoritative event.

Run focused SQL/function/operator/recipient tests first, then all required
foundation and toolkit CI on the exact source head. Preserve recent-MFA,
cross-family authorization, device proof, replay rejection and revocation gates.
Document source revision, CI and any development migration result separately
from live provider/receipt observations.

## Live prerequisites and stopping conditions

This design resolves the event-identity limitation. It does not supply matching
Firebase Android client configuration, a Google Play-capable Android recipient,
notification permissions, or secure local input for the existing worker key.
These remain explicit Task 6 prerequisites.

Once available, resume the approved real foreground/background, redispatch,
browser-removal and revoked-device acceptance with new identified versions.
Keep the existing discovery-first, revoke-then-finalize cleanup workflow and
exact-fixture recovery evidence. Never replace live receipts with injected
messages or claim that CI alone proves transport delivery.

Stop at missing credentials, permissions, deployment/migration failure, an
approval gate or unresolved decision. Keep Issue #1 open until all approved
roadmap work and its required gates are complete.
