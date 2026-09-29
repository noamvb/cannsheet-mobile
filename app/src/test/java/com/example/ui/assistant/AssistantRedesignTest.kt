package com.example.ui.assistant

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.screenshots.ScreenshotTestApplication
import com.example.ui.theme.CannsheetTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
@Config(
    application = ScreenshotTestApplication::class,
    sdk = [35],
    qualifiers = "w411dp-h891dp-xxhdpi",
)
class AssistantRedesignTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenSource_usesThemeTokensInsteadOfLegacyOverrides() {
        val source = File("src/main/java/com/example/ui/assistant/AssistantScreen.kt")
            .readText()
        val forbidden = listOf("Color.Gray", "Color(0x", "ElevatedCard", "FontWeight.")
        val matches = forbidden.filter(source::contains)

        assertTrue("Forbidden source tokens remain: $matches", matches.isEmpty())
    }

    @Test
    fun oneLineSuggestion_hasAtLeast56DpTouchHeight() {
        setEmptyState()

        composeRule.onNodeWithText("Summarize recent activity")
            .assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun tappingSuggestion_invokesPromptCallback() {
        var selectedPrompt: String? = null
        setEmptyState(onSuggestedPrompt = { selectedPrompt = it })

        composeRule.onNodeWithText("Summarize recent activity").performClick()

        assertEquals("Summarize recent activity", selectedPrompt)
    }

    private fun setEmptyState(onSuggestedPrompt: (String) -> Unit = {}) {
        composeRule.setContent {
            CannsheetTheme {
                AssistantEmptyState(onSuggestedPrompt = onSuggestedPrompt)
            }
        }
    }
}
