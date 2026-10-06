package dev.stmedrano.harbor.parent

import org.junit.Assert.*
import org.junit.Test

class EnvironmentConfigTest {
    private val fixture = mapOf(
        "supabaseUrl" to "https://parent-ci.invalid",
        "publishableKey" to "sb_publishable_parent_ci_fixture",
        "firebaseProjectId" to "harbor-parent-ci",
        "firebaseClientProjectId" to "harbor-parent-ci",
        "applicationId" to "dev.stmedrano.harbor.parent",
        "firebasePackage" to "dev.stmedrano.harbor.parent",
        "environment" to "ci",
    )
    @Test fun privateKeyCannotConfigureParentApp() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentConfig.read(fixture + ("publishableKey" to "sb_secret_test_only"), true)
        }
    }
    @Test fun fixtureCannotEnableLiveNetworking() {
        assertFalse(EnvironmentConfig.read(fixture, true).liveNetworkingAllowed)
    }
    @Test fun fixtureRequiresExplicitFlag() {
        assertThrows(IllegalArgumentException::class.java) { EnvironmentConfig.read(fixture, false) }
    }
    @Test fun parentBuildRejectsChildPackage() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentConfig.read(fixture + ("firebasePackage" to "dev.stmedrano.harbor.acceptance"), true)
        }
    }
    @Test fun mismatchedFirebaseProjectIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentConfig.read(fixture + ("firebaseClientProjectId" to "another-project"), true)
        }
    }
    @Test fun productionCannotUseDevelopmentOrUnassignedBackend() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentConfig.read(fixture + ("environment" to "production"), false)
        }
    }
    @Test fun missingLiveConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EnvironmentConfig.read(emptyMap(), false) }
    }
    @Test fun foreignLiveBackendIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentConfig.read(fixture + mapOf("environment" to "development", "supabaseUrl" to "https://foreign.invalid"), false)
        }
    }
}