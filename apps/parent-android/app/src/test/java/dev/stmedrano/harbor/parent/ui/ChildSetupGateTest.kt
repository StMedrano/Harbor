package dev.stmedrano.harbor.parent.ui

import dev.stmedrano.harbor.parent.alerts.AlertAccess
import dev.stmedrano.harbor.parent.location.LocationAccess
import org.junit.Assert.*
import org.junit.Test

class ChildSetupGateTest {
    private val allOn = PermissionSnapshot(LocationAccess(fineGranted = true, backgroundGranted = true, servicesOn = true), notificationsOn = true, usageGranted = true,
        alerts = AlertAccess(notificationAccess = true, smsPermission = true))
    private fun on(s: PermissionSnapshot) = childSetupSteps(s).associate { it.id to it.on }

    @Test fun everythingOnCompletesSetup() {
        assertTrue(setupComplete(childSetupSteps(allOn)))
        assertEquals(SetupStepId.values().toSet(), childSetupSteps(allOn).map { it.id }.toSet())
    }
    @Test fun eachMissingPermissionBlocksSetupAndNamesItself() {
        val cases = mapOf(
            SetupStepId.LOCATION to allOn.copy(location = allOn.location.copy(fineGranted = false)),
            SetupStepId.LOCATION_ALWAYS to allOn.copy(location = allOn.location.copy(backgroundGranted = false)),
            SetupStepId.NOTIFICATIONS to allOn.copy(notificationsOn = false),
            SetupStepId.USAGE_ACCESS to allOn.copy(usageGranted = false),
            SetupStepId.NOTIFICATION_ACCESS to allOn.copy(alerts = AlertAccess(notificationAccess = false, smsPermission = true)),
            SetupStepId.SMS to allOn.copy(alerts = AlertAccess(notificationAccess = true, smsPermission = false)),
        )
        for ((missing, snapshot) in cases) {
            val steps = childSetupSteps(snapshot)
            assertFalse("$missing", setupComplete(steps))
            assertFalse("$missing", steps.first { it.id == missing }.on)
        }
    }
    @Test fun locationServicesOffMeansLocationIsNotOn() {
        assertFalse(on(allOn.copy(location = allOn.location.copy(servicesOn = false))).getValue(SetupStepId.LOCATION))
    }
    @Test fun backgroundGrantWithoutFineIsNeverCountedAsOn() {
        val steps = on(allOn.copy(location = LocationAccess(fineGranted = false, backgroundGranted = true, servicesOn = true)))
        assertFalse(steps.getValue(SetupStepId.LOCATION))
        assertFalse(steps.getValue(SetupStepId.LOCATION_ALWAYS))
    }
    @Test fun buildsWithoutMessageAlertsAskForTheFourBasics() {
        val steps = childSetupSteps(allOn.copy(alerts = null))
        assertEquals(listOf(SetupStepId.LOCATION, SetupStepId.LOCATION_ALWAYS, SetupStepId.NOTIFICATIONS, SetupStepId.USAGE_ACCESS), steps.map { it.id })
        assertTrue(setupComplete(steps))
    }
    @Test fun messageAlertPermissionsAreStepsOfTheirOwnWithPlainReasons() {
        val steps = childSetupSteps(allOn)
        assertEquals(6, steps.size)
        val reasons = steps.filter { it.id == SetupStepId.NOTIFICATION_ACCESS || it.id == SetupStepId.SMS }.map { it.why }
        assertTrue(reasons.all { it.contains("never the words") || it.contains("never get the words") })
    }
    @Test fun emptyListIsNeverComplete() = assertFalse(setupComplete(emptyList()))
    @Test fun everyStepExplainsItself() {
        childSetupSteps(allOn).forEach { assertTrue(it.title.isNotBlank() && it.why.length > 20) }
    }

    @Test fun gateShowsWhileIncompleteAndFinishIsDeliberate() {
        var memory = SetupGateMemory()
        assertTrue(memory.visible(complete = false))
        memory = memory.observe(complete = false)
        // All permissions get turned on: the gate stays until the child finishes.
        memory = memory.observe(complete = true)
        assertTrue(memory.visible(complete = true))
        memory = memory.finish(complete = true)
        assertFalse(memory.visible(complete = true))
    }
    @Test fun finishDoesNothingWhileIncomplete() {
        val memory = SetupGateMemory().observe(false).finish(complete = false)
        assertFalse(memory.finished)
        assertTrue(memory.visible(false))
    }
    @Test fun aLaterRevocationBringsTheGateBack() {
        var memory = SetupGateMemory().observe(false).observe(true).finish(true)
        assertFalse(memory.visible(true))
        memory = memory.observe(complete = false)
        assertTrue(memory.visible(false))
        memory = memory.observe(complete = true)
        assertTrue("finishing again is required", memory.visible(true))
    }
    @Test fun aPhoneThatWasAlreadySetUpGoesStraightToTheApp() {
        // Nothing has ever been missing in this session, so the gate never appeared.
        assertFalse(SetupGateMemory().visible(complete = true))
    }
}
