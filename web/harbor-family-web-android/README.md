# Harbor Family Web (Android)

A small native Android app that opens the live Harbor Family site over HTTPS. The app holds no copy of the site, so every Vercel deployment appears automatically: open the app (or pull down to refresh). You only rebuild the APK to change the app itself (icon, name, URL).

## Build the APK (Android Studio)
1. File > Open > this folder. Let Gradle sync (it downloads what it needs and creates the wrapper).
2. Build > Build Bundle(s) / APK(s) > Build APK(s). The file is `app/build/outputs/apk/debug/app-debug.apk`.
3. Copy it to the phone and open it (allow "install unknown apps" for the source).

## Change the site address
Default: `https://harbor-lyart-nu.vercel.app/`. Edit `SITE_URL` in `app/build.gradle.kts`, or build with `-PharborUrl=https://your-domain/`. Only that host opens inside the app; other links open in the browser.

## Notes
- The Vercel project must be publicly reachable. If Vercel Deployment Protection is on for the address, the app shows a Vercel sign-in page instead of Harbor Family.
- Package: `app.harbor.family.web`. It can be installed next to the older offline wrapper.
- Offline: shows a "Can't reach Harbor Family" screen with a retry button.
- Not built or run in the authoring environment (no Android SDK there). Compile it in Android Studio first.

## Location sharing (child phones)
Inside this app a child phone can share its location with the parents' Harbor Family map.
- **Pairing is native.** `DeviceClient` creates the anonymous Supabase identity, generates an ECDSA P-256 key in the Android Keystore (`DeviceKey`), and claims the parent's pairing code. The page talks to it through `window.HarborDevice` (`DeviceBridge`); the web app falls back to its browser implementation when the bridge is absent. Session tokens and queued fixes are stored AES-GCM encrypted (`Vault`).
- **Signatures.** Keystore signs in ASN.1 DER; the server verifies raw r||s (WebCrypto), so `DeviceKey.derToRaw` converts.
- **Consent.** Turning sharing on shows a plain-language disclosure (`LocationConsent`), then the system location permission, then an optional "Allow all the time" step so sharing restarts after a reboot. A notification stays visible while sharing is on.
- **Collection.** `LocationService` (foreground service, type `location`) asks Fused Location for a balanced-power fix about every 5 minutes or 25 m, queues it (`LocationQueue`, up to 1000, 6 days) and uploads batches of up to 50 to the `report-location` function; failures retry every 2 minutes.
- **Play Console.** Background location needs the "Location permissions" declaration, a prominent-disclosure screenshot/video and a privacy policy that describes this collection. `targetSdk` must also be raised before a Play release.
- Not compiled or run in the authoring environment (no Android SDK). Build and test on a real phone, with a paired child, before relying on it.
