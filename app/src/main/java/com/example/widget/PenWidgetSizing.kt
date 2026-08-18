package com.example.widget

import kotlin.math.min
import kotlin.math.roundToInt

/** Text sizes, in scale-independent pixels, for one rendered widget size. */
data class PenWidgetTextSizes(
    val nameSp: Float,
    val subtitleSp: Float,
    val counterSp: Float,
    val countdownSp: Float,
    val submitSp: Float,
    val stepSp: Float,
    val messageTitleSp: Float,
    val messageHintSp: Float,
)

/** Which interactive layout to inflate, and how large its text should be. */
data class PenWidgetLayoutSpec(
    val compact: Boolean,
    val textSizes: PenWidgetTextSizes,
)

/**
 * Turns the launcher-reported widget size into a layout choice and text sizes.
 *
 * The layouts spend spare height on the counter and step rows, so the panels
 * grow with the widget on their own. Text does not: an `sp` value is fixed when
 * the layout inflates, so without this a stretched widget draws base-size
 * glyphs floating inside large panels.
 */
object PenWidgetSizing {
    /** Size the regular layout is drawn for; text starts growing from here. */
    const val BASE_WIDTH_DP: Int = 140
    const val BASE_HEIGHT_DP: Int = 160

    /** Size at which text reaches its largest values; it does not grow past this. */
    const val FULL_SCALE_WIDTH_DP: Int = 280
    const val FULL_SCALE_HEIGHT_DP: Int = 320

    private val compactSizes = PenWidgetTextSizes(
        nameSp = 13f,
        subtitleSp = 10f,
        counterSp = 19f,
        countdownSp = 17f,
        submitSp = 15f,
        stepSp = 20f,
        messageTitleSp = 14f,
        messageHintSp = 10f,
    )

    private val baseSizes = PenWidgetTextSizes(
        nameSp = 14f,
        subtitleSp = 10f,
        counterSp = 24f,
        countdownSp = 22f,
        submitSp = 20f,
        stepSp = 24f,
        messageTitleSp = 16f,
        messageHintSp = 11f,
    )

    private val largestSizes = PenWidgetTextSizes(
        nameSp = 20f,
        subtitleSp = 13f,
        counterSp = 40f,
        countdownSp = 34f,
        submitSp = 40f,
        stepSp = 44f,
        messageTitleSp = 24f,
        messageHintSp = 15f,
    )

    /** Used before the launcher has published options for a widget instance. */
    val base: PenWidgetLayoutSpec = PenWidgetLayoutSpec(compact = false, textSizes = baseSizes)

    /**
     * [widthDp] and [heightDp] are the launcher's reported minimums for the
     * instance, which are `0` until the host publishes options; that case falls
     * back to [base] rather than guessing.
     */
    fun resolve(
        widthDp: Int,
        heightDp: Int,
        compactBreakpointHeightDp: Int,
    ): PenWidgetLayoutSpec {
        if (heightDp in 1 until compactBreakpointHeightDp) {
            return PenWidgetLayoutSpec(compact = true, textSizes = compactSizes)
        }
        val fraction = growthFraction(widthDp, heightDp)
        if (fraction <= 0f) return base
        return PenWidgetLayoutSpec(
            compact = false,
            textSizes = interpolate(baseSizes, largestSizes, fraction),
        )
    }

    /** `0` at the base size, `1` once both dimensions reach the full-scale size. */
    private fun growthFraction(widthDp: Int, heightDp: Int): Float {
        if (widthDp <= 0 || heightDp <= 0) return 0f
        return min(
            fraction(widthDp, BASE_WIDTH_DP, FULL_SCALE_WIDTH_DP),
            fraction(heightDp, BASE_HEIGHT_DP, FULL_SCALE_HEIGHT_DP),
        )
    }

    private fun fraction(value: Int, base: Int, full: Int): Float =
        ((value - base).toFloat() / (full - base).toFloat()).coerceIn(0f, 1f)

    private fun interpolate(
        from: PenWidgetTextSizes,
        to: PenWidgetTextSizes,
        fraction: Float,
    ): PenWidgetTextSizes = PenWidgetTextSizes(
        nameSp = lerp(from.nameSp, to.nameSp, fraction),
        subtitleSp = lerp(from.subtitleSp, to.subtitleSp, fraction),
        counterSp = lerp(from.counterSp, to.counterSp, fraction),
        countdownSp = lerp(from.countdownSp, to.countdownSp, fraction),
        submitSp = lerp(from.submitSp, to.submitSp, fraction),
        stepSp = lerp(from.stepSp, to.stepSp, fraction),
        messageTitleSp = lerp(from.messageTitleSp, to.messageTitleSp, fraction),
        messageHintSp = lerp(from.messageHintSp, to.messageHintSp, fraction),
    )

    /** Rounded to half a point so dragging the resize handle does not jitter text. */
    private fun lerp(from: Float, to: Float, fraction: Float): Float =
        ((from + (to - from) * fraction) * 2f).roundToInt() / 2f
}
