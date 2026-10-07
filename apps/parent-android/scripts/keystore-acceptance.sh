#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api" == 29 || "$api" == 36 ]]
./gradlew -PparentCiFixture=true --no-daemon --dependency-verification=strict assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p keystore-evidence
run_test() {
  local method="$1"; local class="${2:-dev.stmedrano.harbor.parent.auth.KeystorePersistenceTest}"
  adb shell am instrument -w -e class "$class#$method" \
    dev.stmedrano.harbor.parent.test/androidx.test.runner.AndroidJUnitRunner | tee "keystore-evidence/$method.log"
  grep -F 'OK (1 test)' "keystore-evidence/$method.log"
}
run_test seedColdStart
adb shell am force-stop dev.stmedrano.harbor.parent
run_test restoreColdStart
run_test keyLossClearsCiphertextAndAllowsFreshSignIn
run_test callbackIsScrubbedBeforeActivityCanAcceptIt

run_test offlineFixtureCannotSendCredentialsAndRecoveryControlsAreLabelled dev.stmedrano.harbor.parent.ui.AuthScreenTest

run_test credentialsAreExcludedFromLegacyCloudAndDeviceTransfer
