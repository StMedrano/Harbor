# Architecture note

This web frontend is additive (`web/harbor-family/` only). It follows the approved Supabase contracts: no migrations, no backend changes.

Differences from the specs to review:
- Spec: the child client is native Android with Keystore. This web child path uses a non-extractable WebCrypto P-256 key; it is a development/preview path.
- Spec: Vercel project rooted at `apps/web` (Next.js PWA). This is a Vite app under `web/`; move or port it when 2B starts.
- Parent MFA/AAL2 UI is not built, so web device removal is refused by `revoke-device`.
- Screen time, apps, alerts, location, requests and SOS have no backend yet; the UI shows unavailable states.
- Tested against a fake backend only; never run against the dev Supabase project `bfvybxkjxilntjgndsrm`.
