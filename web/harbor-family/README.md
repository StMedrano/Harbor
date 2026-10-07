# Harbor Family — web frontend

One app. A parent creates an account and gets the parent dashboard. A child does **not** create an account: they choose "A child", enter the 6-digit code a parent issued, and (once the claim succeeds) get the child dashboard.

Written against the repo's approved backend on `feat/parent-android-foundation` (Supabase Auth, RLS, Edge Functions `create-family`, `create-child`, `create-device-pairing`, `device-claim`, `device-sync`, `revoke-device`). It adds **no migrations** and changes no backend.

## Run
```
npm install
npm run dev        # local stand-in (no server, NOT secure) unless env vars are set
```
For Supabase: copy `.env.example` to `.env.local` and set `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` (publishable key only; never a service-role key).

## How it maps to the backend
| UI | Backend |
|---|---|
| Parent sign-up/sign-in | Supabase Auth email+password; first sign-in calls `create-family` (idempotent) |
| Role | Never from metadata. Anonymous identity = child device; account with an active `family_members` row = parent |
| Add child / pairing code | `create-child`, `create-device-pairing` (10 min, single use) |
| Child pairing | anonymous sign-in + non-extractable P-256 key (WebCrypto, IndexedDB) → `device-claim`; binding saved only after the server confirms |
| Child status | signed `device-sync` (method, operation, device id, body SHA-256, timestamp, nonce) |
| Remove monitoring | parent re-auths on a separate client → `revoke-device`, which needs recent MFA (AAL2) |

## Not available yet (shown honestly, never faked)
Screen time, app lists, alerts, location, requests, SOS/check-in, settings and removing a child: the backend has no tables/functions for them. The adapter returns empty data or `ApiError('unavailable')`.

## Known gaps
- The approved design makes the child client native Android (Keystore). A browser key is weaker, so the web child path is for development/preview.
- The web app has no MFA enrolment/challenge UI, so removing a device from the web is refused by the server until that is built.
- A child's display name isn't available through any child-authorized contract yet; the child sees a neutral greeting.
- `localApi` (localStorage) is a dev stand-in only.

## Tests run (all passed)
- Local stand-in UI flow in jsdom (parent, child pairing, lockout, reload).
- Adapter against a **fake** backend that verifies claim and signed requests using the same canonical form as `_shared/device-proof.ts`.
- Device signature verified with the backend's verification algorithm (P-256/SHA-256).
**Not run:** against a real Supabase project, Deno Edge Function tests, a browser build on a phone.
