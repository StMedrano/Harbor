#!/bin/sh
# Builds the Harbor Family parent app for development, and optionally sends it to Firebase App Distribution.
# Written for a cloud environment or any Linux machine; the PowerShell helper does the same on a Windows PC.
#
# Nothing private is in this file. Everything it needs comes from the environment:
#
#   HARBOR_SUPABASE_URL          the development project, https://<ref>.supabase.co
#   HARBOR_PUBLISHABLE_KEY       its publishable client key, sb_publishable_...
#   HARBOR_FIREBASE_PROJECT      the Firebase project id
#   HARBOR_GOOGLE_SERVICES_BASE64  google-services.json, base64
#   HARBOR_KEYSTORE_BASE64       the signing key, base64 (see docs/runbooks/parent-build-anywhere.md)
#   HARBOR_KEYSTORE_PASSWORD     its password
#   HARBOR_KEY_ALIAS             optional, defaults to "harbor"
#
# To upload as well, pass --distribute and set:
#   GOOGLE_APPLICATION_CREDENTIALS  a service account JSON with the App Distribution Admin role
#   HARBOR_GROUPS                   optional tester groups, defaults to "owner"
#
# Every build signs with the same key, so an update installs over the build a phone already has.
set -eu

distribute=no
[ "${1:-}" = "--distribute" ] && distribute=yes

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
app="$repo/apps/parent-android"
fail() { echo "STOP: $1" >&2; exit 1; }
need() { eval "v=\${$1:-}"; [ -n "$v" ] || fail "Set $1"; }

for name in HARBOR_SUPABASE_URL HARBOR_PUBLISHABLE_KEY HARBOR_FIREBASE_PROJECT HARBOR_GOOGLE_SERVICES_BASE64 \
  HARBOR_KEYSTORE_BASE64 HARBOR_KEYSTORE_PASSWORD; do need "$name"; done
[ -n "${JAVA_HOME:-}" ] || fail "Set JAVA_HOME to a JDK 17 or newer"
[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || fail "Set ANDROID_HOME to the Android SDK"

# --- The configuration the build reads. Both files are git-ignored. ---
printf '%s\n' "$HARBOR_GOOGLE_SERVICES_BASE64" | base64 -d > "$app/app/google-services.json" \
  || fail "HARBOR_GOOGLE_SERVICES_BASE64 is not valid base64"
cat > "$app/parent.properties" <<PROPERTIES
environment=development
supabaseUrl=$HARBOR_SUPABASE_URL
publishableKey=$HARBOR_PUBLISHABLE_KEY
firebaseProjectId=$HARBOR_FIREBASE_PROJECT
applicationId=dev.stmedrano.harbor.parent
PROPERTIES

echo "Commit: $(git -C "$repo" rev-parse --short HEAD)"
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$app/app/build.gradle.kts")
echo "Version: $version"

# --- Build, with the same strict dependency verification as CI. The signing key comes from the environment. ---
cd "$app"
./gradlew --no-daemon --dependency-verification=strict assembleDebug --console=plain

apk="$app/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] || fail "Build finished but no APK was produced"
echo "APK: $apk"
echo "APK SHA256: $(sha256sum "$apk" | cut -d' ' -f1)"

# --- The signature is what lets a phone accept this as an update; show whose key signed it. ---
signer=$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/* 2>/dev/null | sort -r | head -1)
[ -n "$signer" ] && [ -x "$signer/apksigner" ] && "$signer/apksigner" verify --print-certs "$apk" | grep -i "certificate DN\|SHA-256 digest" || true

# --- Optional: upload to Firebase App Distribution with a service account, never a personal login. ---
if [ "$distribute" = yes ]; then
  command -v firebase >/dev/null 2>&1 || fail "Firebase CLI not found. Run: npm install -g firebase-tools"
  need GOOGLE_APPLICATION_CREDENTIALS
  [ -f "$GOOGLE_APPLICATION_CREDENTIALS" ] || fail "GOOGLE_APPLICATION_CREDENTIALS names no file"
  appId=$(sed -n 's/.*"mobilesdk_app_id"[^"]*"\([^"]*\)".*/\1/p' "$app/app/google-services.json" | head -1)
  [ -n "$appId" ] || fail "No Android app id in google-services.json"
  firebase appdistribution:distribute "$apk" --app "$appId" \
    --groups "${HARBOR_GROUPS:-owner}" \
    --release-notes "Harbor parent $version, commit $(git -C "$repo" rev-parse --short HEAD)"
  echo "Uploaded. Open Firebase App Tester on the phone to install it."
fi
