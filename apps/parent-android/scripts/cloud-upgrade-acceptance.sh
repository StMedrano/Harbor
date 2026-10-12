#!/usr/bin/env bash
# Synthetic APK replacement evidence. Refuses physical devices and non-GitHub runtimes.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${CI:-}" == true ]] || { echo 'Requires isolated GitHub Actions CI.' >&2; exit 2; }
[[ "${ANDROID_SERIAL:-}" =~ ^emulator-[0-9]+$ ]] || { echo 'Requires explicit emulator serial.' >&2; exit 2; }
adb() { command adb -s "$ANDROID_SERIAL" "$@"; }
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Not an Android emulator.' >&2; exit 2; }
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api" == 29 || "$api" == 36 ]]
app=dev.stmedrano.harbor.parent
[[ -z "$(adb shell pm path "$app" | tr -d '\r')" ]] || { echo 'Requires a fresh emulator; refusing existing app.' >&2; exit 2; }
cd "$(dirname "$0")/.."
root="$(git rev-parse --show-toplevel)"
evidence="$PWD/upgrade-evidence"
mkdir -p "$evidence"
export PATH="${ANDROID_HOME:?}/build-tools/36.0.0:$PATH"
# Last v0.16 PR29 revision; build its unchanged product sources, adding only the seed test.
baseline=4ef2c4adcf7629161476c60124dd58c81af084e9
base_dir="$(mktemp -d)"
rmdir "$base_dir"
git worktree add --detach "$base_dir" "$baseline"
trap 'git -C "$root" worktree remove --force "$base_dir"' EXIT
relative=app/src/androidTest/java/dev/stmedrano/harbor/parent/profile/ApkUpgradePersistenceTest.kt
cp "$relative" "$base_dir/apps/parent-android/$relative"
build() {
  local dir="$1" log="$2"
  (cd "$dir"; chmod +x gradlew; cp config/ci-fixture-google-services.json app/google-services.json
   ./gradlew -PparentCiFixture=true --no-daemon --dependency-verification=strict assembleDebug assembleDebugAndroidTest) 2>&1 | tee "$log"
}
build "$base_dir/apps/parent-android" "$evidence/baseline-build.log"
cp "$base_dir/apps/parent-android/app/build/outputs/apk/debug/app-debug.apk" "$evidence/v016-offline.apk"
cp "$base_dir/apps/parent-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$evidence/v016-test.apk"
build "$PWD" "$evidence/candidate-build.log"
cp app/build/outputs/apk/debug/app-debug.apk "$evidence/v017-offline.apk"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$evidence/v017-test.apk"
for version in 016 017; do
  apk="$evidence/v${version}-offline.apk"
  aapt dump badging "$apk" > "$evidence/v${version}-metadata.txt"
  grep -F "package: name='$app' versionCode='$((10#$version))'" "$evidence/v${version}-metadata.txt"
  if grep -F 'android.permission.INTERNET' "$evidence/v${version}-metadata.txt"; then exit 1; fi
  apksigner verify --print-certs "$apk" > "$evidence/v${version}-signature.txt"
done
sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$evidence/v016-signature.txt" > "$evidence/baseline-cert.txt"
sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$evidence/v017-signature.txt" > "$evidence/candidate-cert.txt"
[[ -s "$evidence/baseline-cert.txt" ]]
cmp "$evidence/baseline-cert.txt" "$evidence/candidate-cert.txt"
# CI's temporary debug key is shared by these two builds only. No user key is assumed.
adb install "$evidence/v016-offline.apk"
adb install "$evidence/v016-test.apk"
run_test() {
  local method="$1" log="$2"
  adb shell am instrument -w -e class "dev.stmedrano.harbor.parent.profile.ApkUpgradePersistenceTest#$method" \
    "$app.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/$log"
  grep -F 'OK (1 test)' "$evidence/$log"
}
run_test seedVersion16 seed.log
adb shell am force-stop "$app"
bash scripts/check-in-place-upgrade.sh "$evidence/v017-offline.apk" | tee "$evidence/upgrade-guard.log"
adb install -r "$evidence/v017-offline.apk"
adb install -r "$evidence/v017-test.apk"
adb shell am start -W -n "$app/.MainActivity" | tee "$evidence/launch.log"
grep -F 'Status: ok' "$evidence/launch.log"
# am start's status alone is not evidence that the process remained alive.
sleep 2
adb shell pidof "$app" | tee "$evidence/launched-pid.txt"
run_test version17RetainsVersion16Data restore.log
adb shell am force-stop "$app"
run_test version17RetainsVersion16Data cold-restore.log
(cd "$evidence"; sha256sum v016-offline.apk v017-offline.apk > SHA256SUMS.txt)
echo "PASS: API $api offline fixture v16 -> v17 replacement, launch, encrypted Auth/PKCE, profile, Room snapshot and pending key retention." | tee "$evidence/result.txt"
