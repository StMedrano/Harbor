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
profile_status=0
run_test parent04StoresSurviveProfileBootstrap dev.stmedrano.harbor.parent.profile.ProfileUpgradeTest || profile_status=1
run_test corruptModeHintDoesNotStartRuntime dev.stmedrano.harbor.parent.profile.ProfileUpgradeTest || profile_status=1
run_test offlineFixtureBootstrapsWithoutProtectedRuntime dev.stmedrano.harbor.parent.profile.ProfileStartupTest || profile_status=1
child_crypto_status=0
run_test encryptedChildReopenAndRefreshPreserveParentCredentials dev.stmedrano.harbor.parent.child.ChildCryptoTest || child_crypto_status=1
run_test nonExportableChildP256ProofVerifiesAfterKeyReopen dev.stmedrano.harbor.parent.child.ChildCryptoTest || child_crypto_status=1
run_test lostChildEncryptionKeyStaysBlockedAcrossReopen dev.stmedrano.harbor.parent.child.ChildCryptoTest || child_crypto_status=1
run_test interruptedEnrollmentMarkerSurvivesProcessReopen dev.stmedrano.harbor.parent.child.ChildCryptoTest || child_crypto_status=1
[[ "$child_crypto_status" == 0 ]]
family_ui_status=0
for method in setupHasNoMenu parentLoginOpensOnlyParent childCodeCannotOpenParent confirmedChildRestoresToday recoveryBackDoesNotEscape revokedChildCannotOpenParent; do
  run_test "$method" dev.stmedrano.harbor.parent.ui.FamilyRoleNavigationTest || family_ui_status=1
done
[[ "$family_ui_status" == 0 ]]
pairing_status=0
run_test confirmedClaimAloneOpensChildAndClearsCode dev.stmedrano.harbor.parent.ui.ChildPairingScreenTest || pairing_status=1
run_test lostReplyDoesNotOpenDashboardOrPermitBlindRetry dev.stmedrano.harbor.parent.ui.ChildPairingScreenTest || pairing_status=1
run_test backDuringPairingPreservesRecoveryGuardAndProtectsCode dev.stmedrano.harbor.parent.ui.ChildPairingScreenTest || pairing_status=1
[[ "$pairing_status" == 0 ]]
[[ "$profile_status" == 0 ]]
adb shell settings put system font_scale 1.8
child_visual_status=0
run_test largeTextChildDashboardShowsHonestTabsInBothThemes dev.stmedrano.harbor.parent.ui.FamilyRoleNavigationTest || child_visual_status=1
adb shell settings put system font_scale 1.0
[[ "$child_visual_status" == 0 ]]
adb pull /sdcard/Android/data/dev.stmedrano.harbor.parent/files/family-child-light.png keystore-evidence/
adb pull /sdcard/Android/data/dev.stmedrano.harbor.parent/files/family-child-dark.png keystore-evidence/
family_notification_status=0
for method in exactlyOneFamilyServiceAndProtectedJobAreInstalled queuedFamilyWorkContainsOnlyCapturedLeaseAndScopedReferences inactiveProfileMustNotPoisonParentRuntimeCache childTapIsGenericImmutableAndRejectsRoleOrTokenHints; do
  run_test "$method" dev.stmedrano.harbor.parent.notifications.FamilyNotificationLifecycleTest || family_notification_status=1
done
[[ "$family_notification_status" == 0 ]]
run_test childControlsGateNativeProviderAndReportUnconfirmedBackend dev.stmedrano.harbor.parent.notifications.ChildNotificationUiTest
approval_status=0
run_test temporaryApprovalErasurePreservesBothPrimaryNamespaces dev.stmedrano.harbor.parent.profile.ParentApprovalIsolationTest || approval_status=1
run_test approvalRequiresSeparateSignInMfaAndDeliberateRemoval dev.stmedrano.harbor.parent.profile.ParentApprovalUiTest || approval_status=1
run_test backCancelsPendingApprovalAndRemovesSecretFields dev.stmedrano.harbor.parent.profile.ParentApprovalUiTest || approval_status=1
run_test confirmedRemovalReopensChooserAndParentAuthentication dev.stmedrano.harbor.parent.profile.ConfirmedRoleCleanupTest || approval_status=1
run_test blockedNetworkShowsRetryWithoutGrantingMenu dev.stmedrano.harbor.parent.profile.ProfileRetryUiTest || approval_status=1
composition_status=0
run_test seedConfirmedRevocationBeforeLocalErasure dev.stmedrano.harbor.parent.profile.FamilyCompositionPersistenceTest || composition_status=1
adb shell am force-stop dev.stmedrano.harbor.parent
run_test coldRevocationCheckpointCannotRestoreProtectedChild dev.stmedrano.harbor.parent.profile.FamilyCompositionPersistenceTest || composition_status=1
run_test seedAmbiguousPrimaryNamespaces dev.stmedrano.harbor.parent.profile.FamilyCompositionPersistenceTest || composition_status=1
adb shell am force-stop dev.stmedrano.harbor.parent
run_test coldAmbiguousStartupPreservesStoresAndHidesRuntime dev.stmedrano.harbor.parent.profile.FamilyCompositionPersistenceTest || composition_status=1

frontend_status=0
run_test offlineFixtureCannotSendCredentialsAndRecoveryControlsAreLabelled dev.stmedrano.harbor.parent.ui.AuthScreenTest || frontend_status=1
run_test frontendChildSelectorTracksSelectionWithoutEnablingCachedMutation dev.stmedrano.harbor.parent.ui.FamilyScreenTest || frontend_status=1
[[ "$frontend_status" == 0 ]]
adb pull /sdcard/Android/data/dev.stmedrano.harbor.parent/files/frontend-family-light.png keystore-evidence/
adb pull /sdcard/Android/data/dev.stmedrano.harbor.parent/files/frontend-family-dark.png keystore-evidence/
run_test recoveryScreenRequiresVerifiedCallbackAndBackClearsAuthorization dev.stmedrano.harbor.parent.ui.AuthScreenTest
run_test signInAndSessionLossSeparateLoginFromParentMenu dev.stmedrano.harbor.parent.ui.ParentSessionNavigationTest
run_test restoredSessionOpensParentAreaWithoutLogin dev.stmedrano.harbor.parent.ui.ParentSessionNavigationTest
run_test recoveryKeepsMenuHiddenUntilDeliberateBack dev.stmedrano.harbor.parent.ui.ParentSessionNavigationTest

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
run_test largeTextAuthNavigationKeepsRecoverySeparate dev.stmedrano.harbor.parent.ui.AuthScreenTest || visual_status=1
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
[[ "$approval_status" == 0 ]]
[[ "$composition_status" == 0 ]]


usage_platform_status=0
for method in permissionManifestAndSettingsRoundTrip nativePermissionDenialBlocksReadAndGrantIsObserved onlyVisibleLaunchableInventoryIsReported; do
  run_test "$method" dev.stmedrano.harbor.parent.usage.UsagePlatformTest || usage_platform_status=1
done
[[ "$usage_platform_status" == 0 ]]
usage_storage_status=0
run_test seedUsageColdStart dev.stmedrano.harbor.parent.usage.UsagePersistenceTest || usage_storage_status=1
adb shell am force-stop dev.stmedrano.harbor.parent
run_test restoreUsageColdStartAndClearPreservesOtherNamespace dev.stmedrano.harbor.parent.usage.UsagePersistenceTest || usage_storage_status=1
run_test usageKeyLossRetainsCheckpointAndDoesNotEraseParent dev.stmedrano.harbor.parent.usage.UsagePersistenceTest || usage_storage_status=1
[[ "$usage_storage_status" == 0 ]]
run_test protectedPeriodicJobContainsOnlyValidatedReferences dev.stmedrano.harbor.parent.usage.UsageJobTest
