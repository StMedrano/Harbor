# Child location sharing

What it does: a paired child phone running the Harbor Family Android app (web shell) records its location about every 5 minutes (and after moving 25 m), signs each upload with its Keystore key, and the parent's **Location** tab shows the latest fix (coordinates, accuracy, battery, time) refreshed every 30 seconds. The last 7 days of fixes are kept server-side; only the latest is readable by parents today.

## Pieces
| Piece | Where |
|---|---|
| Tables + atomic ingest | `supabase/migrations/20261008130000_child_location.sql` (`public.child_locations` with RLS, `private.location_history`, `private.harbor_record_locations`) |
| Edge Function | `supabase/functions/report-location` (device proof, operation name `report-location`, max 50 points / 32 KB) |
| Parent UI | `web/harbor-family/js/map.js`, `supabase-api.js` (`listChildren` reads `child_locations`), `parent.js` (30 s refresh while the Map tab is open) |
| Child UI | `web/harbor-family/js/child.js` ("Sharing my location" panel), `native-device.js` (bridge) |
| Android | `web/harbor-family-web-android/.../DeviceClient`, `DeviceKey`, `Vault`, `LocationService`, `LocationConsent`, `DeviceBridge`, `BootReceiver` |

## Deploy order (nothing here has been applied to any Supabase project)
1. Apply the migration (additive; creates two tables and one function, touches no existing table).
2. `supabase functions deploy report-location` (JWT verification stays on: child devices authenticate with their anonymous session).
3. Deploy the web app (Vercel).
4. Build and install the Android app on the child phone. Old installs keep working; location only appears after the child opens the app, goes to **Today** and chooses **Turn on location sharing**.
5. Pair note: phones paired with the *browser* implementation hold their key in the page, not in the app. Re-pair them inside the Android app (remove, then pair with a new code) before turning location on.

## Important behaviour
- Anonymous sign-ins must be enabled on the Supabase project (already required for pairing).
- Sharing is **opt-in on the phone**: the child sees a disclosure, grants the permission, and a notification stays on screen while it runs. The child can switch it off from the app. A parent-side "require location sharing" control is a later Controls feature.
- A parent removing the device (`revoke-device`) makes the next upload fail with `DEVICE_REVOKED`; the phone then wipes its key and stops.
- Fixes older than 7 days or more than 5 minutes in the future are rejected per point; retries of stored points are idempotent (`device_id, recorded_at`).
- No map tiles: the page's CSP allows no third-party hosts, so the Location tab draws a locator card and an **Open in Maps** link (OpenStreetMap, opens outside the app). Embedding a real map means adding a tile host to `img-src` and picking a map library; it is a separate decision.
- Retention is enforced on every upload for that device; a phone that stops reporting keeps its last 7 days until the family is deleted (cascade) or the child is removed.

## Google Play
Background location is a restricted permission. Before a Play release: raise `targetSdk`, complete the Location permissions declaration (use case: parental control / family safety), record the in-app disclosure, update the privacy policy, and choose the correct Data safety answers (precise location, collected, shared with the child's parents).

## Tested vs not tested
Tested here: migration + 17 SQL tests on Postgres 16, 8 Deno tests (including DER to raw signature conversion verified by the server's real verifier), bridge and parent-map behaviour in jsdom with a fake bridge. **Not tested:** any Kotlin (no Android SDK here), real Fused Location behaviour, battery impact, Android 12+ background-start limits, a real Supabase project, the Android Keystore itself.
