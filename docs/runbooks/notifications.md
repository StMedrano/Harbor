# Harbor notification runbook

This runbook covers the Subproject 1 notification foundation. It does not make push delivery authoritative; clients must refresh Harbor state after receiving a route notification.

## Parent Web Push lifecycle

Parent browser subscriptions are stored only in `private.parent_web_push_subscriptions`. Clients register through `register-web-push` and remove through `remove-web-push`; `anon` and `authenticated` roles have no direct table grants.

Registration accepts only:

```json
{
  "clientInstallationId": "stable-client-installation-id",
  "endpoint": "https://push-provider.example/subscription",
  "keys": {
    "p256dh": "browser-public-key",
    "auth": "browser-auth-secret"
  }
}
```

Removal accepts the installation ID and optionally one endpoint. Omitting `endpoint` removes the active subscription for that authenticated parent's installation. The caller cannot choose a user ID; ownership is always derived from the authenticated parent.

## VAPID boundary

Harbor uses the official `web-push` package from the server-only Supabase Edge Function layer. The package import is pinned to `web-push@3.6.7`; do not replace it with custom Web Push encryption.

Configure these Supabase Edge Function secrets in every environment that sends parent Web Push:

- `VAPID_SUBJECT` — an externally valid `mailto:` or `https:` contact URI.
- `VAPID_PUBLIC_KEY` — browser-safe VAPID public key. This may also be exposed to the parent web client so it can create a Push subscription.
- `VAPID_PRIVATE_KEY` — server-only VAPID private key. Never expose it through Vercel public environment variables, browser bundles, logs, database rows, or notification payloads.

Generate one VAPID key pair for an environment and keep it stable. Rotating the pair requires deliberate client subscription handling because existing browser subscriptions were created against the previous application server key.

## Payload boundary

Web Push payloads contain only `NotificationRouteRefV1` data: a version, route kind, and optional Harbor IDs needed to route the parent client to refreshed state. Do not put raw location history, message bodies, child telemetry, access tokens, VAPID material, or other sensitive domain content in the push payload.

A push notification is a wake/change hint, not the source of truth. After receipt, the parent client authenticates normally and reloads authorized Harbor state.

## Delivery classification

`sendWebPush` classifies delivery results for the durable outbox layer:

- Successful provider response: `sent`.
- HTTP `404` or `410`: permanent `invalid_subscription`; the dispatcher should disable that subscription.
- HTTP `429`: retryable `rate_limited`.
- HTTP `5xx`: retryable `provider_error`.
- Network/transport error without an HTTP status: retryable `network_error`.
- Other non-success HTTP responses: permanent `provider_rejected`.

Task 11 owns retry scheduling, dead-letter behavior, and invalid-subscription cleanup. A Web Push failure must never roll back a successful FCM delivery for the same logical Harbor event.

## Dispatcher lifecycle

`createDispatchOne` provides the Task 11 dispatcher lifecycle. Its persistence dependencies must use the private outbox helpers: claim before delivery, complete after success, and record retry or dead-letter state after failure. An unavailable claim returns `no_op`, so duplicate dispatch and already-sent rows do not send again.

Only version-1 route references with the approved keys are accepted. Unknown fields, location/message content, and malformed identifier fields are dead-lettered before transport delivery. Provider exception messages are not recorded; network errors use a fixed error category.

Retry delay starts at 60 seconds and doubles per attempt, capped at one hour. A permanent invalid Web Push subscription is disabled before its outbox row is dead-lettered. Each invocation handles one transport row; it never changes a sibling row for the other transport.

`createPersistentDispatchOne(privateOutboxStore, transports)` connects the dispatcher to the existing private SQL helpers. It reads only active Web Push subscriptions and FCM registrations for active, non-revoked devices. Queued target references contain IDs; credentials and tokens are resolved privately at delivery time. The existing server-only Web Push adapter is the default Web Push transport. The FCM sender must be supplied by the worker.

The `dispatch-outbox` Edge Function now accepts a server-only worker call and wires private persistence to FCM and Web Push. It is not deployed or scheduled yet. Processing claims have bounded recovery leases, and device desired-state/command writes persist delivery intent in their database transaction. Production scheduling and the cross-cutting foundation acceptance gate remain required before enabling delivery. CI tests exercise real local PostgreSQL persistence and controlled transport boundaries; they do not claim real provider delivery.

## FCM and worker secrets

Configure `FCM_SERVICE_ACCOUNT_JSON` only in Supabase Edge Function secrets. It must contain a Firebase service account with `type: service_account`, `project_id`, `client_email`, and `private_key`. Harbor uses pinned `google-auth-library@10.5.0` to obtain and refresh OAuth access tokens with the `https://www.googleapis.com/auth/firebase.messaging` scope. FCM sends use HTTP v1, data-only route references, and a 15-second send timeout. Credential failures and HTTP 401/403 remain retryable configuration/provider errors; provider response bodies are not logged or persisted.

Configure an independent random `HARBOR_OUTBOX_WORKER_KEY` of at least 32 random bytes in Supabase secrets and the trusted scheduler. Send it in the `apikey` header with `POST {"outboxId":"<durable-outbox-uuid>"}`. The worker never accepts client-supplied delivery content. Parent/child credentials, missing keys, and incorrect keys cannot trigger dispatch. Key comparison uses constant-time comparison of SHA-256 digests.

`verify_jwt = false` applies only to this worker because it authenticates its dedicated server key itself before parsing or accessing outbox state. Missing server configuration returns 503; wrong credentials return 403; invalid IDs return 400. Keep the worker key out of all browser/Android configuration and logs. Do not enable production scheduling until complete foundation acceptance is verified.

## Production checks

Before enabling parent Web Push in an environment, confirm that all three VAPID values are configured in Supabase secrets, `VAPID_PRIVATE_KEY` is absent from public/client configuration, register/remove function tests pass, Web Push delivery tests pass, and the durable notification dispatcher is using only route-reference payloads.

Processing claims expire after five minutes. The server worker can reclaim an expired
claim by dispatching its existing outbox ID; recovery increments `attempt_count`.
Completion, failure, and invalid Web Push cleanup require that attempt and a live
lease. Old processing rows without a lease are also recoverable. Delivery remains
at least once: interruption after provider acceptance can cause a duplicate send.
Automated production scheduling remains deferred until foundation acceptance.

## Transactional device event intent

Desired-state version changes and new device commands create outbox rows in the
same database transaction. Stable event identities use the device/version or
command ID; recipient identity includes the transport and target reference.
Each event records an FCM device wake and Web Push hints for every active
installation belonging to an active family parent. Acknowledgments and duplicate
command insertion do not emit another event. Rolled-back domain mutations leave
no intent. Payloads contain only version, kind, and route IDs, never desired-state
or command content.

Family membership is rechecked when resolving a queued Web Push target. Removal
prevents delivery of already-queued family hints without disabling the parent's
subscription for other families. Only provider invalid-subscription responses
perform invalid-subscription cleanup. New domain features must record their own
stable event intent; these triggers cover the existing desired-state/command path.
