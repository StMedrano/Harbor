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
