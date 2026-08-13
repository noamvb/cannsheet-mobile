package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAlertEnableDecisionTest {
    @Test
    fun preRuntimePermissionDevicesEnableOnlyWhenAndroidIsAvailable() {
        assertEquals(
            QueueAlertEnableDecision.ENABLE,
            queueAlertEnableDecision(
                runtimePermissionRequired = false,
                runtimePermissionGranted = true,
                notificationsAvailable = true,
            ),
        )
        assertEquals(
            QueueAlertEnableDecision.BLOCKED,
            queueAlertEnableDecision(
                runtimePermissionRequired = false,
                runtimePermissionGranted = true,
                notificationsAvailable = false,
            ),
        )
    }

    @Test
    fun missingRuntimePermissionIsRequestedBeforeAvailabilityIsConsidered() {
        assertEquals(
            QueueAlertEnableDecision.REQUEST_PERMISSION,
            queueAlertEnableDecision(
                runtimePermissionRequired = true,
                runtimePermissionGranted = false,
                notificationsAvailable = false,
            ),
        )
    }

    @Test
    fun grantedRuntimePermissionStillHonorsAppAndChannelAvailability() {
        assertEquals(
            QueueAlertEnableDecision.ENABLE,
            queueAlertEnableDecision(true, true, true),
        )
        assertEquals(
            QueueAlertEnableDecision.BLOCKED,
            queueAlertEnableDecision(true, true, false),
        )
    }

    @Test
    fun effectiveStateRequiresBothTheOptInAndAndroidAvailability() {
        assertTrue(queueAlertsEffectivelyEnabled(true, true))
        assertFalse(queueAlertsEffectivelyEnabled(true, false))
        assertFalse(queueAlertsEffectivelyEnabled(false, true))
        assertFalse(queueAlertsEffectivelyEnabled(false, false))
    }
}
