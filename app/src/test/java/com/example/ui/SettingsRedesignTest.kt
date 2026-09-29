package com.example.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.ProductTypeKey
import com.example.data.SyncPreferences
import com.example.domain.PenQuickLogState
import com.example.ui.theme.CannsheetTheme
import com.example.ui.theme.tabular
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = SettingsRedesignTestApplication::class, sdk = [35])
class SettingsRedesignTest {
    @get:Rule val composeRule = createComposeRule()
    private var contentScrollState: ScrollState? = null

    @Test
    fun groupHeadingsAppearInSpecifiedOrder() {
        render()
        val headings = listOf(
            "SYNC & QUEUE",
            "LOGGING DEFAULTS",
            "PRODUCT TYPES",
            "QUICK LOG",
            "NOTIFICATIONS",
            "DATA",
            "ABOUT",
        )
        val topPositions = headings.map { heading ->
            val node = composeRule.onNodeWithText(heading, useUnmergedTree = true)
            node.performScrollTo()
            contentScrollState!!.value + node.fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Headings must follow the requested order: $topPositions", topPositions.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun queueAlertSwitchKeepsTagAndCallsItsCallback() {
        var changed: Boolean? = null
        render(onQueueAlertsChanged = { changed = it })
        composeRule.onNodeWithTag(QueueAlertSettingsTestTags.SWITCH)
            .performScrollTo()
            .performClick()
        assertEquals(true, changed)
    }

    @Test
    fun settingsSourceContainsNoCardWrappers() {
        val source = File("src/main/java/com/example/ui/SettingsScreen.kt").readText()
        assertTrue(source.contains("internal fun SettingsContent"))
        assertTrue(!source.contains("Card(") && !source.contains("ElevatedCard("))
    }

    @Test
    fun valueFigureStyleUsesTabularFigures() {
        render()
        composeRule.onNodeWithTag("settings-pending-count").assertIsDisplayed()
        val source = File("src/main/java/com/example/ui/SettingsScreen.kt").readText()
        assertTrue(
            source.contains("settings-pending-count") &&
                source.contains("style = MaterialTheme.typography.bodyMedium.tabular()"),
        )
        assertTrue(TextStyle.Default.tabular().fontFeatureSettings.orEmpty().contains("tnum"))
    }

    private fun render(onQueueAlertsChanged: (Boolean) -> Unit = {}) {
        composeRule.setContent {
            val scrollState = rememberScrollState()
            SideEffect { contentScrollState = scrollState }
            CannsheetTheme {
                Surface { settingsContent(onQueueAlertsChanged, scrollState) }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun settingsContent(
        onQueueAlertsChanged: (Boolean) -> Unit,
        scrollState: ScrollState,
    ) {
        SettingsContent(
            gasUrl = "https://script.google.com/macros/s/example/exec",
            syncStatus = null,
            pendingCount = 2,
            quantityPresets = listOf(0.25, 0.5, 1.0),
            quantityPresetOverrides = emptyMap<ProductTypeKey, List<Double>>(),
            secondsPerUseOverrides = emptyMap(),
            productTypeOptions = listOf("F", "P"),
            timerValue = 5,
            backgroundSyncPreferences = SyncPreferences(queueAlertsEnabled = false),
            penQuickLogState = PenQuickLogState.Unavailable,
            loadedPenProductId = null,
            runtimePermissionResult = null,
            backgroundSyncLastRunLabel = "Not run yet",
            nowMillisProvider = { 1_800_000_000_000L },
            onSetSubmissionTimer = {},
            onSaveQuantityPresets = { Result.success(Unit) },
            onSaveQuantityPresetsForType = { _, _ -> Result.success(Unit) },
            onClearQuantityPresetsForType = {},
            onSaveSecondsPerUseForType = { _, _ -> Result.success(Unit) },
            onClearSecondsPerUseForType = {},
            onSetBackgroundSyncEnabled = {},
            notificationsAvailable = { true },
            runtimePermissionGranted = { true },
            onRuntimePermissionResultConsumed = {},
            requestRuntimePermission = {},
            onQueueAlertsChanged = onQueueAlertsChanged,
            onSyncNow = {},
            onFetchProducts = {},
            scrollState = scrollState,
        )
    }
}

class SettingsRedesignTestApplication : Application()
