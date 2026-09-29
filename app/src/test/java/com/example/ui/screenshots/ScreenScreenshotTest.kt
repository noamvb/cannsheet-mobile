package com.example.ui.screenshots

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application
import com.example.data.InsightsRange
import com.example.domain.PenQuickLogState
import com.example.nfc.NfcQuickLogSettingsSection
import com.example.ui.ConsumptionContent
import com.example.ui.InsightsContent
import com.example.ui.InsightsUiState
import com.example.ui.PurchaseContent
import com.example.ui.SettingsContent
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.assistant.AssistantEmptyState
import com.example.data.PurchaseDefaultsState
import com.example.data.SyncPreferences
import com.example.ui.AnalyticsUiError
import com.example.ui.WindowWidth
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
@Config(
    application = ScreenshotTestApplication::class,
    sdk = [35],
    qualifiers = "w411dp-h891dp-xxhdpi",
)
class ScreenScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var oldLocale: Locale
    private lateinit var oldTimeZone: TimeZone

    @Before
    fun setUp() {
        oldLocale = Locale.getDefault()
        oldTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(oldLocale)
        TimeZone.setDefault(oldTimeZone)
    }

    private fun render(dark: Boolean, content: @Composable () -> Unit) {
        composeRule.setContent {
            MyApplicationTheme(darkTheme = dark, dynamicColor = false) {
                Surface { content() }
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String, dark: Boolean) {
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png",
        )
    }

    @Test fun consumption_with_entries_light() {
        render(false) { consumption() }
        capture("consumption_with_entries", false)
    }

    @Test fun consumption_with_entries_dark() {
        render(true) { consumption() }
        capture("consumption_with_entries", true)
    }

    @Test fun purchase_form_light() {
        render(false) { purchase() }
        capture("purchase_form", false)
    }

    @Test fun purchase_form_dark() {
        render(true) { purchase() }
        capture("purchase_form", true)
    }

    @Test fun insights_with_data_light() {
        render(false) { insights(empty = false) }
        capture("insights_with_data", false)
    }

    @Test fun insights_with_data_dark() {
        render(true) { insights(empty = false) }
        capture("insights_with_data", true)
    }

    @Test fun insights_empty_light() {
        render(false) { insights(empty = true) }
        capture("insights_empty", false)
    }

    @Test fun insights_empty_dark() {
        render(true) { insights(empty = true) }
        capture("insights_empty", true)
    }

    @Test fun assistant_empty_light() {
        render(false) { AssistantEmptyState(onSuggestedPrompt = {}) }
        capture("assistant_empty", false)
    }

    @Test fun assistant_empty_dark() {
        render(true) { AssistantEmptyState(onSuggestedPrompt = {}) }
        capture("assistant_empty", true)
    }

    @Test fun settings_light() {
        render(false) { settings() }
        capture("settings", false)
    }

    @Test fun settings_dark() {
        render(true) { settings() }
        capture("settings", true)
    }

    @Test fun nfc_quick_log_light() {
        render(false) {
            NfcQuickLogSettingsSection(
                resolverDescription = "Current tap target: Citrus Cartridge (loaded Pen cart).",
            )
        }
        capture("nfc_quick_log", false)
    }

    @Test fun nfc_quick_log_dark() {
        render(true) {
            NfcQuickLogSettingsSection(
                resolverDescription = "Current tap target: Citrus Cartridge (loaded Pen cart).",
            )
        }
        capture("nfc_quick_log", true)
    }

    @Composable
    private fun consumption() {
        ConsumptionContent(
            allProducts = screenshotProducts,
            recentProducts = screenshotRecentProducts,
            quantityPresets = listOf(0.25, 0.5, 1.0),
            includeUnopened = false,
            formState = screenshotConsumptionForm,
            pendingUsesByProduct = mapOf("fixture-pen-1" to 5.0),
            onSelectProduct = {},
            onQuantityChange = {},
            onIncludeUnopenedChange = {},
            onLog = { _, _, _, _, _ -> },
            onLogBorrowed = { _, _, _, _, _ -> },
            onFinishWithoutConsumption = {},
            penQuickLog = PenQuickLogState.Unavailable,
            secondsPerUse = 5.0,
            onQuickLogPen = {},
            onChooseLoadedPen = {},
            nowProvider = { SCREENSHOT_NOW },
        )
    }

    @Composable
    private fun purchase() {
        PurchaseContent(
            products = screenshotProducts,
            purchaseDefaultsState = PurchaseDefaultsState.Loaded(emptyMap()),
            formState = screenshotPurchaseForm,
            taxRate = 0.13,
            onFormChange = {},
            onQueuePurchase = {},
        )
    }

    @Composable
    private fun insights(empty: Boolean) {
        val data = screenshotInsights(empty)
        InsightsContent(
            state = InsightsUiState(
                data = data,
                displayedRange = InsightsRange.LastDays(28),
                lastUpdatedEpochMillis = SCREENSHOT_NOW.toEpochMilli(),
            ),
            pendingCount = 0,
            isSyncing = false,
            onSync = {},
            onRefresh = {},
            windowWidth = WindowWidth.COMPACT,
        )
    }

    @Composable
    private fun settings() {
        SettingsContent(
            gasUrl = "https://script.google.com/macros/s/example/exec",
            syncStatus = null,
            pendingCount = 2,
            quantityPresets = listOf(0.25, 0.5, 1.0),
            quantityPresetOverrides = emptyMap(),
            secondsPerUseOverrides = emptyMap(),
            productTypeOptions = listOf("F", "P"),
            timerValue = 5,
            backgroundSyncPreferences = screenshotSyncPreferences,
            penQuickLogState = PenQuickLogState.Unavailable,
            loadedPenProductId = null,
            runtimePermissionResult = null,
            backgroundSyncLastRunLabel = "Last background sync: 1 hour ago (Success)",
            nowProvider = { SCREENSHOT_NOW },
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
            onQueueAlertsChanged = {},
            onSyncNow = {},
            onFetchProducts = {},
        )
    }
}

class ScreenshotTestApplication : Application()
