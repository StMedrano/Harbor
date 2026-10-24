# Building the parent app on any machine

The Harbor Family parent app is built and sent to the owner group from a PC today. This is what a second machine — a
cloud environment, another computer — needs so that it produces the **same app**, not a different one that phones
refuse to install over it.

Nothing private is in this repository. Every private value is named here and supplied by the machine that builds.

## Why the signing key matters

Android only accepts an update that is signed with the same key as the build already on the phone. Until 0.32 the app
was signed with whatever **debug key** happened to be on the machine that built it, which is created per machine. A
build from anywhere else would have been refused as an update, and the only way in would have been to uninstall Harbor
— losing the pairing, the controls and the day's activity on that phone.

From 0.32 there is one Harbor development key. `apps/parent-android/app/build.gradle.kts` reads it from the
environment and signs the debug build with it. Without it the build still works and still signs with the machine's own
debug key, which is what CI does, since CI never installs on a phone.

**The key is not in this repository and must not be.** It is a file on each machine that builds, and an environment
variable in a cloud environment.

## What a machine needs

| Variable | What it is |
| --- | --- |
| `HARBOR_KEYSTORE` | path to the signing key. A PC uses this. |
| `HARBOR_KEYSTORE_BASE64` | the same key as base64, for a machine where no file can be placed. Use one or the other. |
| `HARBOR_KEYSTORE_PASSWORD` | its password |
| `HARBOR_KEY_ALIAS` | the key's name inside it; `harbor` |
| `HARBOR_SUPABASE_URL` | the development project, `https://<ref>.supabase.co` |
| `HARBOR_PUBLISHABLE_KEY` | its publishable client key, `sb_publishable_…` |
| `HARBOR_FIREBASE_PROJECT` | the Firebase project id |
| `HARBOR_GOOGLE_SERVICES_BASE64` | `google-services.json` as base64 |
| `GOOGLE_APPLICATION_CREDENTIALS` | only to upload: a service account JSON with the **Firebase App Distribution Admin** role |

Every value except the last is written out for this family's development project by the helper on the owner's PC, in
`Downloads\harbor-cloud-env.txt`. That file is private: it is not in the repository and does not belong in a chat.

## Building

- **On the PC:** `build-harbor-parent-dev-<version>.ps1`, which reads the key from
  `Downloads\harbor-signing.properties` and refuses to build without it, so a build can never go out signed with the
  wrong key by accident.
- **Anywhere else:** `scripts/build-parent-dev.sh`, and `scripts/build-parent-dev.sh --distribute` to upload as well.
  It needs a JDK 17 or newer (`JAVA_HOME`), the Android SDK (`ANDROID_HOME`) with platform 37 and build-tools, and,
  to upload, the Firebase CLI.

Both print the certificate the APK was signed with. It should read
`CN=Harbor Family development, OU=Harbor Family, O=Harbor Family, C=US` wherever the build ran.

## The service account, for uploading from a cloud environment

The Firebase CLI on the PC uses the owner's own Google login, which a cloud environment cannot have. Instead, in the
Google Cloud console for the Firebase project: create a service account, give it the **Firebase App Distribution
Admin** role, create a JSON key for it, and put that file on the build machine with `GOOGLE_APPLICATION_CREDENTIALS`
naming it. The Firebase CLI picks it up on its own; no `firebase login` is needed.

Only the owner can do this: it is their Google account, and the key it issues can publish builds to testers.

## The one-off cost of the new key

0.32 is the first build with the Harbor key, so it is signed differently from 0.31 and earlier. **Both phones have to
uninstall Harbor Family before installing 0.32**, and the child phone has to be paired again afterwards. Every build
from 0.32 onwards installs over the one before it, from either machine.
