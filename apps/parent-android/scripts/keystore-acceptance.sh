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
run_test failedSignInCannotLeaveVerifiedSessionTitleVisible dev.stmedrano.harbor.parent.ui.AuthScreenTest
run_test otherSubjectCannotReadCachedFamily dev.stmedrano.harbor.parent.data.FamilyCacheTest
run_test concurrentPendingOperationsKeepOneDurableKeyAcrossReopen dev.stmedrano.harbor.parent.data.FamilyCacheTest
run_test cachedFamilyLabelsStalenessAndDisablesMutations dev.stmedrano.harbor.parent.ui.FamilyScreenTest
run_test deviceViewReportsActualMetadataWithoutClaimingAppliedPolicy dev.stmedrano.harbor.parent.ui.FamilyScreenTest
run_test notificationControlsReportUnconfirmedCleanupAndDisableDuringLogout dev.stmedrano.harbor.parent.notifications.ParentNotificationUiTest
notification_status=0
run_test signOutRemainsAvailableDuringNotificationRegistration dev.stmedrano.harbor.parent.notifications.ParentNotificationUiTest || notification_status=1
run_test nativeJobUsesProtectedServiceAndQueuesReferencesWithoutProviderOrAuthTokens dev.stmedrano.harbor.parent.notifications.ParentNotificationJobTest
run_test nativeControlsUsePermissionGateAndOfflineLogoutErasesVerifiedSession dev.stmedrano.harbor.parent.notifications.ParentNotificationUiTest
run_test developmentReceiptViewExportsOnlyAnObservedReferenceOnExplicitTap dev.stmedrano.harbor.parent.notifications.ParentNotificationUiTest
run_test tapEnvelopeIsStrictAndClearedBeforeRouting dev.stmedrano.harbor.parent.notifications.ParentNotificationAndroidTest
run_test notificationContainsGenericTextAndAnImmutableExplicitTap dev.stmedrano.harbor.parent.notifications.ParentNotificationAndroidTest
visual_status=0
run_test pairingDialogUsesVercelSurface dev.stmedrano.harbor.parent.ui.FamilyRouteTest || visual_status=1
adb shell settings put system font_scale 1.8
run_test largeTextPairingRequiresFreshReadAndBackReturnsToFamily dev.stmedrano.harbor.parent.ui.FamilyRouteTest || visual_status=1
adb shell settings put system font_scale 1.0
[[ "$visual_status" == 0 ]]
[[ "$notification_status" == 0 ]]
adb pull /sdcard/Android/data/dev.stmedrano.harbor.parent/files/family-pairing-large-text.png keystore-evidence/
adb pull /sdcard/Android/data/dev.stmedrano.harbor.parent/files/family-device-large-text.png keystore-evidence/
adb pull /sdcard/Android/data/dev.stmedrano.harbor.parent/files/family-large-text.png keystore-evidence/
security_status=0
adb shell settings put system font_scale 1.8
run_test nativeLargeTextMfaRequiresDeliberateRetryAndSensitiveWindowProtection dev.stmedrano.harbor.parent.security.SecurityUiTest || security_status=1
adb shell settings put system font_scale 1.0
[[ "$security_status" == 0 ]]
