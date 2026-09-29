package com.example.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.Product
import com.example.ui.screenshots.ScreenshotTestApplication
import com.example.ui.theme.CannsheetTheme
import com.example.ui.theme.PlexMono
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
class LogRedesignTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sampleProducts = listOf(
        Product(id = "p1", name = "Daybreak", type = "Flower", status = 0, grams = 3.5, totalUses = 4.0),
        Product(id = "p2", name = "Stillwater", type = "Cartridge", status = 0, grams = 1.0, totalUses = 12.0),
        Product(id = "p3", name = "Highland", type = "Edible", status = 0, grams = 10.0, totalUses = 2.0),
    )

    @Test
    fun logButtonIsDisplayedWithoutScrollingOnStandardScreen() {
        var formState by mutableStateOf(ConsumptionFormState(selectedProductId = sampleProducts[0].id, quantityText = "1"))

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.0f)) {
                CannsheetTheme(darkTheme = false) {
                    Surface {
                        ConsumptionContent(
                            allProducts = sampleProducts,
                            recentProducts = emptyList(),
                            quantityPresets = listOf(1.0, 2.0, 3.0),
                            includeUnopened = false,
                            formState = formState,
                            onSelectProduct = { formState = formState.copy(selectedProductId = it) },
                            onQuantityChange = { formState = formState.copy(quantityText = it) },
                            onIncludeUnopenedChange = {},
                            onLog = { _, _, _, _, _ -> },
                            onLogBorrowed = { _, _, _, _, _ -> },
                            onFinishWithoutConsumption = {},
                        )
                    }
                }
            }
        }

        // Must be displayed within viewport without scrolling
        composeRule.onNode(hasText("Log consumption") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun selectProductThenTapLogInvokesCallbackOnceWithProductAndQuantityOne() {
        var formState by mutableStateOf(ConsumptionFormState(quantityText = "1"))
        var loggedProductId: String? = null
        var loggedQuantity: Double? = null
        var callCount = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.0f)) {
                CannsheetTheme(darkTheme = false) {
                    Surface {
                        ConsumptionContent(
                            allProducts = sampleProducts,
                            recentProducts = emptyList(),
                            quantityPresets = listOf(1.0, 2.0, 3.0),
                            includeUnopened = false,
                            formState = formState,
                            onSelectProduct = { formState = formState.copy(selectedProductId = it) },
                            onQuantityChange = { formState = formState.copy(quantityText = it) },
                            onIncludeUnopenedChange = {},
                            onLog = { _, _, productId, quantity, _ ->
                                loggedProductId = productId
                                loggedQuantity = quantity
                                callCount++
                            },
                            onLogBorrowed = { _, _, _, _, _ -> },
                            onFinishWithoutConsumption = {},
                        )
                    }
                }
            }
        }

        // Tap the second product row
        composeRule.onNode(hasText("Stillwater") and hasClickAction()).performClick()
        // Tap Log consumption
        composeRule.onNode(hasText("Log consumption") and hasClickAction()).performClick()

        composeRule.runOnIdle {
            assertEquals(1, callCount)
            assertEquals("p2", loggedProductId)
            assertEquals(1.0, loggedQuantity ?: 0.0, 0.0)
        }
    }

    @Test
    fun remainingQuantityUsesPlexMonoOrTabularFigures() {
        val product = sampleProducts[0]
        composeRule.setContent {
            CannsheetTheme(darkTheme = false) {
                Surface {
                    ConsumptionContent(
                        allProducts = listOf(product),
                        recentProducts = emptyList(),
                        quantityPresets = listOf(1.0, 2.0, 3.0),
                        includeUnopened = false,
                        formState = ConsumptionFormState(selectedProductId = product.id),
                        onSelectProduct = {},
                        onQuantityChange = {},
                        onIncludeUnopenedChange = {},
                        onLog = { _, _, _, _, _ -> },
                        onLogBorrowed = { _, _, _, _, _ -> },
                        onFinishWithoutConsumption = {},
                    )
                }
            }
        }

        // Find the remaining quantity node by tag using unmerged tree
        val node = composeRule.onNodeWithTag(
            ConsumptionLedgerTestTags.remainingQuantity(product.id),
            useUnmergedTree = true,
        )
        node.assertIsDisplayed()
        val textLayoutResults = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(textLayoutResults)
        val style = textLayoutResults.first().layoutInput.style
        assertTrue(
            "Expected PlexMono or tnum feature settings, got: fontFamily=${style.fontFamily}, fontFeatureSettings=${style.fontFeatureSettings}",
            style.fontFamily == PlexMono || style.fontFeatureSettings?.contains("tnum") == true,
        )
    }

    @Test
    fun noColorLiteralsRemainInConsumptionScreen() {
        val sourceFile = listOf(
            File("src/main/java/com/example/ui/ConsumptionScreen.kt"),
            File("app/src/main/java/com/example/ui/ConsumptionScreen.kt"),
        ).firstOrNull { it.exists() }
        assertTrue("ConsumptionScreen.kt must exist", sourceFile != null && sourceFile.exists())
        val source = sourceFile!!.readText()
        val matches = Regex("""Color\(0x[0-9a-fA-F]+""").findAll(source).map { it.value }.toList()
        assertTrue(
            "Expected no Color(0x...) literals in ConsumptionScreen.kt, but found ${matches.size}: $matches",
            matches.isEmpty(),
        )
    }
}
