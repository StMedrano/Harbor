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

This module is currently a tested dispatcher library, not a deployed HTTP endpoint or scheduled worker. Production SQL adapters, authenticated worker invocation, FCM credential/transport integration, and crash recovery for processing claims remain required before enabling delivery. Tests use controlled external transport and persistence boundaries; they do not claim real provider delivery.

## Production checks

Before enabling parent Web Push in an environment, confirm that all three VAPID values are configured in Supabase secrets, `VAPID_PRIVATE_KEY` is absent from public/client configuration, register/remove function tests pass, Web Push delivery tests pass, and the durable notification dispatcher is using only route-reference payloads.
