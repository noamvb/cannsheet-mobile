package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.screenshots.ScreenshotTestApplication
import org.junit.Assert.assertEquals
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
class ThemeTokensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightPrimaryMatchesDesignHex() {
        assertEquals(Color(0xFF145F58), LedgerLightColors.primary)
    }

    @Test
    fun darkPrimaryMatchesDesignHex() {
        assertEquals(Color(0xFF83D0C2), LedgerDarkColors.primary)
    }

    @Test
    fun lightSurfaceMatchesDesignHex() {
        assertEquals(Color(0xFFFFFFFF), LedgerLightColors.surface)
    }

    @Test
    fun darkOnSurfaceMatchesDesignHex() {
        assertEquals(Color(0xFFE5EFEB), LedgerDarkColors.onSurface)
    }

    @Test
    fun bodyMediumUsesPlexSans() {
        assertEquals(PlexSans, LedgerTypography.bodyMedium.fontFamily)
    }

    @Test
    fun labelMediumUsesDesignWeight() {
        assertEquals(FontWeight(600), LedgerTypography.labelMedium.fontWeight)
        assertEquals(12.sp, LedgerTypography.labelMedium.fontSize)
    }

    @Test
    fun mediumShapeUsesDesignRadius() {
        assertEquals(RoundedCornerShape(14.dp), LedgerShapes.medium)
    }

    @Test
    fun darkThemeUsesFixedDarkSchemeWithoutDynamicColorParameter() {
        var renderedPrimary = Color.Unspecified

        composeRule.setContent {
            CannsheetTheme(darkTheme = true) {
                renderedPrimary = MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()

        assertEquals(LedgerDarkColors.primary, renderedPrimary)
    }
}
