package dev.stmedrano.harbor.parent.location

import org.junit.Assert.*
import org.junit.Test

class LocationAccessTest {
    private fun fix(at: String, accuracy: Int = 20) = LocationFix(at, 40.0, -74.0, accuracy, LocationProvider.GPS)

    @Test fun statusPrefersPermissionThenServicesThenBackground() {
        assertEquals(LocationStatus.PERMISSION_LOST, locationStatus(LocationAccess(false, true, false)))
        assertEquals(LocationStatus.SERVICES_OFF, locationStatus(LocationAccess(true, false, false)))
        assertEquals(LocationStatus.BACKGROUND_MISSING, locationStatus(LocationAccess(true, false, true)))
        assertEquals(LocationStatus.SHARING, locationStatus(LocationAccess(true, true, true)))
    }

    @Test fun serviceNeedsFineLocationAndSystemSwitch() {
        assertFalse(locationServiceAllowed(LocationAccess(false, true, true)))
        assertFalse(locationServiceAllowed(LocationAccess(true, true, false)))
        assertTrue(locationServiceAllowed(LocationAccess(true, false, true)))
    }

    @Test fun movingFixesNeedFiveMinutesAndStationaryFixesNeedFifteen() {
        val last = fix("2026-10-10T18:00:00Z")
        val moved = last.copy(latitude = 40.002)
        assertEquals(5 * 60_000L, LocationCadence.MOVING_INTERVAL_MS)
        assertEquals(15 * 60_000L, LocationCadence.STATIONARY_INTERVAL_MS)
        assertTrue(LocationCadence.shouldRecord(null, last))
        assertFalse(LocationCadence.shouldRecord(last, moved.copy(at = "2026-10-10T18:04:59Z")))
        assertTrue(LocationCadence.shouldRecord(last, moved.copy(at = "2026-10-10T18:05:00Z")))
        assertFalse(LocationCadence.shouldRecord(last, last.copy(at = "2026-10-10T18:14:59Z")))
        assertTrue(LocationCadence.shouldRecord(last, last.copy(at = "2026-10-10T18:15:00Z")))
        assertFalse(LocationCadence.shouldRecord(last, moved.copy(at = "2026-10-10T17:59:00Z")))
    }

    @Test fun gpsAccuracyJitterDoesNotMakeAStationaryPhoneLookMoving() {
        val last = fix("2026-10-10T18:00:00Z", accuracy = 80)
        val jitter = last.copy(latitude = 40.0004, accuracyM = 90)
        assertFalse(LocationCadence.shouldRecord(last, jitter.copy(at = "2026-10-10T18:06:00Z")))
        assertTrue(LocationCadence.shouldRecord(last, jitter.copy(at = "2026-10-10T18:15:00Z")))
    }

    @Test fun weakFixDoesNotEvadeFiveMinuteMovementThrottle() {
        val last = fix("2026-10-10T18:00:00Z")
        val weak = last.copy(latitude = 40.05, accuracyM = 5000)
        assertFalse(LocationCadence.shouldRecord(last, weak.copy(at = "2026-10-10T18:05:00Z")))
        assertFalse(LocationCadence.shouldRecord(last, weak.copy(at = "2026-10-10T18:11:00Z")))
        assertTrue(LocationCadence.shouldRecord(last, weak.copy(at = "2026-10-10T18:15:00Z")))
    }

    @Test fun androidLocationsBecomeContractFixes() {
        val value = toLocationFix(1_791_655_200_999L, 40.12345678, -74.9999995, 12.2f, "gps")!!
        assertEquals("2026-10-10T18:00:00Z", value.at)
        assertEquals(40.123457, value.latitude, 0.0)
        assertEquals(-75.0, value.longitude, 0.0)
        assertEquals(13, value.accuracyM)
        validateLocationFix(value)
        assertNull(toLocationFix(0, 1.0, 1.0, 5f, "passive"))
        assertNull(toLocationFix(0, 1.0, 1.0, null, "gps"))
        assertNull(toLocationFix(0, 91.0, 1.0, 5f, "gps"))
        assertNull(toLocationFix(0, Double.NaN, 1.0, 5f, "gps"))
        assertEquals(LocationProvider.NETWORK, toLocationFix(0, 1.0, 1.0, 5f, "network")!!.provider)
    }
}
