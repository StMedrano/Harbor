# Harbor child-device enrollment runbook

This runbook documents the Task 7 one-time child-device enrollment flow. It describes the security boundary implemented by the `create-device-pairing` and `device-claim` Edge Functions and the private `device_security` persistence objects.

## Preconditions

- A parent is signed in through Harbor and is an active owner/parent of the family that owns the child profile.
- The child device has its own separate Supabase Auth identity. Do not reuse the parent's session on the child device.
- `HARBOR_DEVICE_PAIRING_PEPPER` is configured only in the Edge Function/server environment. It must not be exposed to either web or child clients.
- The child device has generated a P-256 key pair locally and retains the private key in device-protected storage. Enrollment sends only the public SPKI.

## Issue a pairing code

The parent client calls `create-device-pairing` with:

```json
{ "childId": "<child UUID>" }
```

The function verifies the current parent and family relationship, then returns a six-digit code and `expiresAt`. The code lifetime is exactly ten minutes. Only the keyed HMAC-SHA-256 digest is persisted; the raw code is returned once to the parent and is never stored in the database.

Issuing a replacement code invalidates any previous unconsumed token for the same child. Treat the code as a short-lived secret: display it only while pairing and do not place it in logs, analytics, URLs, crash reports, or support screenshots.

## Claim from the child device

While authenticated as the child device's separate anonymous/device Supabase identity, call `device-claim` with:

```json
{
  "code": "123456",
  "publicKeySpki": "<base64 P-256 SubjectPublicKeyInfo>",
  "device": {
    "displayName": "Child phone",
    "model": "<model>",
    "androidVersion": "<version>",
    "supervisionMode": "<mode>"
  }
}
```

The claim path validates the six-digit code and P-256 SPKI, checks that the caller identity is not already bound, verifies the active enrollment token, and atomically binds the Auth subject, public key, and device record while consuming the token and writing the enrollment audit event. A successful response contains `deviceId`, `familyId`, and `childId`.

A token is single-use. Each failed claim consumes one of at most five attempts; the fifth failure invalidates the token. After invalidation or expiry, issue a new code from the parent side rather than retrying the old one.

## Expected failure handling

- Expired, replaced, consumed, or five-times-failed code: request a new pairing code.
- Malformed/unsupported SPKI: regenerate/repair the local P-256 key material before requesting another claim.
- Already-bound child Auth identity: do not silently rebind it. Treat this as an enrollment-state error and investigate the existing device binding.
- Wrong-family/child attempt: fail closed. Never disclose another family's enrollment state.

Do not add direct Data API permissions for the child-device principal to work around enrollment failures. The private enrollment/security tables and service helpers remain inaccessible to `PUBLIC`, `anon`, and `authenticated`; Edge Functions mediate these operations.

## Verification

Before merging Task 7, run the repository's normal Supabase verification and the device-enrollment tests. The required checks are:

```bash
supabase db reset
supabase db lint --level warning
supabase test db
deno test tests/functions/device-claim.test.ts
```

The Harbor Foundation CI workflow runs the integrated database and Edge Function checks. Do not mark enrollment complete until that workflow is green on the Task 7 head commit.

## Operational recovery

If pairing is suspected to be exposed, issue a replacement code; this invalidates the previous active code. If a device was enrolled incorrectly or is no longer trusted, use the approved device-revocation path once Task 9 is implemented rather than modifying private rows manually. Never recover by sharing the pairing pepper, storing raw pairing codes, disabling family authorization, or granting clients access to the private schema.
