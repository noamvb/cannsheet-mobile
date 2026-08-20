package com.example.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenWidgetSizingTest {
    private val breakpoint = 150

    @Test
    fun unreportedSizeFallsBackToBaseSpec() {
        assertEquals(PenWidgetSizing.base, PenWidgetSizing.resolve(0, 0, breakpoint))
    }

    @Test
    fun heightBelowBreakpointSelectsCompactLayout() {
        assertTrue(PenWidgetSizing.resolve(140, 110, breakpoint).compact)
        assertTrue(PenWidgetSizing.resolve(140, 149, breakpoint).compact)
    }

    @Test
    fun breakpointHeightAndAboveSelectsRegularLayout() {
        assertFalse(PenWidgetSizing.resolve(140, 150, breakpoint).compact)
        assertFalse(PenWidgetSizing.resolve(140, 320, breakpoint).compact)
    }

    @Test
    fun compactSpecDoesNotGrowWithWidth() {
        val narrow = PenWidgetSizing.resolve(140, 120, breakpoint)
        val wide = PenWidgetSizing.resolve(360, 120, breakpoint)

        assertEquals(narrow, wide)
    }

    @Test
    fun baseSizeUsesBaseTextSizes() {
        val spec = PenWidgetSizing.resolve(
            PenWidgetSizing.BASE_WIDTH_DP,
            PenWidgetSizing.BASE_HEIGHT_DP,
            breakpoint,
        )

        assertEquals(PenWidgetSizing.base.textSizes, spec.textSizes)
        assertFalse(spec.showPresets)
        assertEquals(24f, spec.textSizes.counterSp, 0f)
    }

    @Test
    fun baseSizeUsesTheDefaultStep() {
        assertEquals(
            STEP_SECONDS,
            PenWidgetSizing.resolve(
                PenWidgetSizing.BASE_WIDTH_DP,
                PenWidgetSizing.BASE_HEIGHT_DP,
                breakpoint,
            ).stepSeconds,
        )
    }

    @Test
    fun largeWidgetUsesTheLargerStep() {
        assertEquals(30, PenWidgetSizing.resolve(210, 240, breakpoint).stepSeconds)
    }

    @Test
    fun compactAlwaysUsesTheDefaultStep() {
        assertEquals(STEP_SECONDS, PenWidgetSizing.resolve(280, 120, breakpoint).stepSeconds)
    }

    @Test
    fun fullScaleSizeReachesLargestTextSizes() {
        val sizes = PenWidgetSizing.resolve(
            PenWidgetSizing.FULL_SCALE_WIDTH_DP,
            PenWidgetSizing.FULL_SCALE_HEIGHT_DP,
            breakpoint,
        ).textSizes

        assertEquals(20f, sizes.nameSp, 0f)
        assertEquals(13f, sizes.subtitleSp, 0f)
        assertEquals(40f, sizes.counterSp, 0f)
        assertEquals(34f, sizes.countdownSp, 0f)
        assertEquals(40f, sizes.submitSp, 0f)
        assertEquals(44f, sizes.stepSp, 0f)
        assertEquals(24f, sizes.presetSp, 0f)
        assertEquals(24f, sizes.messageTitleSp, 0f)
        assertEquals(15f, sizes.messageHintSp, 0f)
    }

    @Test
    fun textStopsGrowingBeyondFullScale() {
        val atFullScale = PenWidgetSizing.resolve(280, 320, breakpoint)
        val beyond = PenWidgetSizing.resolve(600, 900, breakpoint)

        assertEquals(atFullScale, beyond)
    }

    @Test
    fun halfwayBetweenBaseAndFullScaleIsHalfwayBetweenTextSizes() {
        val sizes = PenWidgetSizing.resolve(210, 240, breakpoint).textSizes

        assertEquals(17f, sizes.nameSp, 0f)
        assertEquals(32f, sizes.counterSp, 0f)
        assertEquals(34f, sizes.stepSp, 0f)
    }

    @Test
    fun growthIsLimitedByTheSmallerDimension() {
        val tallButNarrow = PenWidgetSizing.resolve(PenWidgetSizing.BASE_WIDTH_DP, 900, breakpoint)
        val wideButShort = PenWidgetSizing.resolve(900, PenWidgetSizing.BASE_HEIGHT_DP, breakpoint)

        assertEquals(PenWidgetSizing.base, tallButNarrow)
        assertEquals(PenWidgetSizing.base.textSizes, wideButShort.textSizes)
        assertFalse(wideButShort.showPresets)
    }

    @Test
    fun sizesBelowTheBaseNeverShrinkBelowTheBaseSpec() {
        // Heights at or above the breakpoint but below the base size still get
        // the base text; the layout weights absorb the difference instead.
        val spec = PenWidgetSizing.resolve(120, 150, breakpoint)

        assertFalse(spec.compact)
        assertEquals(PenWidgetSizing.base.textSizes, spec.textSizes)
        assertFalse(spec.showPresets)
    }

    @Test
    fun regularLayoutsHidePresetsUntilTheControlFloorFits() {
        assertFalse(PenWidgetSizing.resolve(140, 160, breakpoint).showPresets)
        assertFalse(PenWidgetSizing.resolve(140, 199, breakpoint).showPresets)
        assertTrue(PenWidgetSizing.resolve(140, 200, breakpoint).showPresets)
        assertTrue(PenWidgetSizing.base.showPresets)
    }

    @Test
    fun textSizesGrowMonotonicallyWithTheWidget() {
        var previous = PenWidgetSizing.resolve(140, 160, breakpoint).textSizes
        var widthDp = 150
        var heightDp = 170
        while (widthDp <= 400) {
            val current = PenWidgetSizing.resolve(widthDp, heightDp, breakpoint).textSizes
            assertTrue(
                "counter shrank at ${widthDp}x$heightDp",
                current.counterSp >= previous.counterSp,
            )
            assertTrue("name shrank at ${widthDp}x$heightDp", current.nameSp >= previous.nameSp)
            assertTrue("step shrank at ${widthDp}x$heightDp", current.stepSp >= previous.stepSp)
            previous = current
            widthDp += 10
            heightDp += 10
        }
    }

    @Test
    fun everyTextSizeIsRoundedToHalfPoints() {
        var widthDp = 140
        while (widthDp <= 320) {
            val sizes = PenWidgetSizing.resolve(widthDp, widthDp + 40, breakpoint).textSizes
            listOf(
                sizes.nameSp,
                sizes.subtitleSp,
                sizes.counterSp,
                sizes.countdownSp,
                sizes.submitSp,
                sizes.stepSp,
                sizes.presetSp,
                sizes.messageTitleSp,
                sizes.messageHintSp,
            ).forEach { size ->
                assertEquals("$size at ${widthDp}dp is not a half point", 0f, (size * 2f) % 1f, 0f)
            }
            widthDp += 1
        }
    }
}
