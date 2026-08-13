package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QueueAlertSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersEffectiveStateStatusAndToggleCallback() {
        var requested: Boolean? = null
        setContent(
            checked = true,
            pendingActionCount = 2,
            since = NOW - 2 * 60 * 60_000L,
            onCheckedChange = { requested = it },
        )

        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH)
            .assertIsOn()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithContentDescription("Queue alerts").assertIsOn()
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.STATUS)
            .assertTextEquals("2 waiting to sync for 2 hours.")
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.PERMISSION).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(false, requested) }
    }

    @Test
    fun rendersAndroidBlockAndDisablesSwitchWhilePermissionIsPending() {
        setContent(
            checked = false,
            switchEnabled = false,
            pendingActionCount = 0,
            notificationsBlocked = true,
        )

        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH)
            .assertIsOff()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.STATUS)
            .assertTextEquals("Everything is synced.")
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.PERMISSION).assertTextEquals(
            "Android is blocking Cannsheet notifications. Turn them on in Android Settings, " +
                "then try again.",
        )
    }

    @Test
    fun directEnablePathWritesTheOptInWithoutRequestingPermission() {
        var preference by mutableStateOf(false)
        var permissionRequests = 0
        setCoordinatorContent(
            preferenceEnabled = { preference },
            notificationsAvailable = { true },
            runtimePermissionRequired = false,
            runtimePermissionGranted = { true },
            requestRuntimePermission = { permissionRequests += 1 },
            onPreferenceChanged = { preference = it },
        )

        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH).performClick()

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(preference)
            assertEquals(0, permissionRequests)
        }
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH).assertIsOn()
    }

    @Test
    fun deniedRuntimePermissionKeepsPreferenceOffAndShowsBlockingLine() {
        var preference by mutableStateOf(false)
        var permissionResult by mutableStateOf<Boolean?>(null)
        var permissionRequests = 0
        setCoordinatorContent(
            preferenceEnabled = { preference },
            notificationsAvailable = { false },
            runtimePermissionRequired = true,
            runtimePermissionGranted = { false },
            runtimePermissionResult = { permissionResult },
            onRuntimePermissionResultConsumed = { permissionResult = null },
            requestRuntimePermission = {
                permissionRequests += 1
                permissionResult = false
            },
            onPreferenceChanged = { preference = it },
        )

        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH).performClick()

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(false, preference)
            assertEquals(1, permissionRequests)
        }
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH).assertIsOff()
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.PERMISSION).assertTextEquals(
            BLOCKED_COPY,
        )
    }

    @Test
    fun pendingPermissionRequestSurvivesStateRestorationAndAppliesTheResult() {
        var preference by mutableStateOf(false)
        var permissionGranted by mutableStateOf(false)
        var permissionResult by mutableStateOf<Boolean?>(null)
        var permissionRequests = 0
        val writes = mutableListOf<Boolean>()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme {
                Column {
                    QueueAlertSettingsCoordinator(
                        preferenceEnabled = preference,
                        pendingActionCount = 0,
                        queueNonEmptySinceEpochMillis = null,
                        nowEpochMillis = NOW,
                        notificationsAvailable = { permissionGranted },
                        runtimePermissionRequired = true,
                        runtimePermissionGranted = { permissionGranted },
                        runtimePermissionResult = permissionResult,
                        onRuntimePermissionResultConsumed = {
                            permissionResult = null
                        },
                        requestRuntimePermission = { permissionRequests += 1 },
                        onPreferenceChanged = {
                            writes += it
                            preference = it
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH).performClick()
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH).assertIsNotEnabled()
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            permissionGranted = true
            permissionResult = true
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(listOf(true), writes)
            assertTrue(preference)
        }
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH).assertIsOn()
    }

    @Test
    fun latePersistedOptInIsTurnedOffWhenAndroidIsAlreadyBlocking() {
        var preference by mutableStateOf(false)
        val writes = mutableListOf<Boolean>()
        setCoordinatorContent(
            preferenceEnabled = { preference },
            notificationsAvailable = { false },
            runtimePermissionRequired = false,
            runtimePermissionGranted = { true },
            onPreferenceChanged = {
                writes += it
                preference = it
            },
        )

        composeRule.runOnIdle { preference = true }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(listOf(false), writes) }
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.PERMISSION).assertTextEquals(
            BLOCKED_COPY,
        )
    }

    @Test
    fun resumeRechecksARevokedAndroidPermission() {
        val lifecycleOwner = TestLifecycleOwner()
        var preference by mutableStateOf(true)
        var available = true
        val writes = mutableListOf<Boolean>()
        lifecycleOwner.handle(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handle(Lifecycle.Event.ON_START)
        lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
        setCoordinatorContent(
            preferenceEnabled = { preference },
            notificationsAvailable = { available },
            runtimePermissionRequired = true,
            runtimePermissionGranted = { available },
            onPreferenceChanged = {
                writes += it
                preference = it
            },
            lifecycleOwner = lifecycleOwner,
        )

        composeRule.runOnIdle {
            available = false
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(listOf(false), writes) }
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.PERMISSION).assertTextEquals(
            BLOCKED_COPY,
        )
    }

    private fun setContent(
        checked: Boolean,
        switchEnabled: Boolean = true,
        pendingActionCount: Int,
        since: Long? = null,
        notificationsBlocked: Boolean = false,
        onCheckedChange: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    QueueAlertSettingsSection(
                        checked = checked,
                        switchEnabled = switchEnabled,
                        pendingActionCount = pendingActionCount,
                        queueNonEmptySinceEpochMillis = since,
                        nowEpochMillis = NOW,
                        notificationsBlocked = notificationsBlocked,
                        onCheckedChange = onCheckedChange,
                    )
                }
            }
        }
    }

    private fun setCoordinatorContent(
        preferenceEnabled: () -> Boolean,
        notificationsAvailable: () -> Boolean,
        runtimePermissionRequired: Boolean,
        runtimePermissionGranted: () -> Boolean,
        runtimePermissionResult: () -> Boolean? = { null },
        onRuntimePermissionResultConsumed: () -> Unit = {},
        requestRuntimePermission: () -> Unit = {},
        onPreferenceChanged: (Boolean) -> Unit,
        lifecycleOwner: LifecycleOwner? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    QueueAlertSettingsCoordinator(
                        preferenceEnabled = preferenceEnabled(),
                        pendingActionCount = 0,
                        queueNonEmptySinceEpochMillis = null,
                        nowEpochMillis = NOW,
                        notificationsAvailable = notificationsAvailable,
                        runtimePermissionRequired = runtimePermissionRequired,
                        runtimePermissionGranted = runtimePermissionGranted,
                        runtimePermissionResult = runtimePermissionResult(),
                        onRuntimePermissionResultConsumed =
                            onRuntimePermissionResultConsumed,
                        requestRuntimePermission = requestRuntimePermission,
                        onPreferenceChanged = onPreferenceChanged,
                        lifecycleOwner = lifecycleOwner
                            ?: androidx.lifecycle.compose.LocalLifecycleOwner.current,
                    )
                }
            }
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle = registry

        fun handle(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
        const val BLOCKED_COPY =
            "Android is blocking Cannsheet notifications. Turn them on in Android Settings, " +
                "then try again."
    }
}
