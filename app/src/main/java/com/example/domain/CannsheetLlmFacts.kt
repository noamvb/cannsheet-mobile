package com.example.domain

import com.example.data.InsightsResponseDto
import com.example.ui.InsightsUiState
import com.example.ui.formatCadCents
import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.Period

/**
 * Turns an analytics snapshot into the facts the LocalLLM service is allowed to narrate.
 *
 * Two rules from `AGENTS.md` shape everything here.
 *
 * First, runway and spend **projections** are presentation-only estimates that must not be
 * persisted, transmitted, or treated as confirmed values. Nothing derived or forecast is
 * sent. Only figures the backend actually recorded appear below: counts, recorded spend,
 * and inventory states. `InventoryRunway` output is deliberately absent.
 *
 * Second, month and day arithmetic uses the response's own `timeZone` and `range`, never a
 * device-local clock. This file reads dates only out of the response, and does not call
 * `LocalDate.now()` or construct a `Calendar` at all.
 *
 * The wider rule that governs the whole feature is that the model narrates and never
 * calculates. Every value here is already computed and formatted; a 2B-parameter model is a
 * capable writer and an unreliable arithmetician.
 */
object CannsheetLlmFacts {

    /** Below this many logs a summary claims more than the range supports. */
    const val MINIMUM_LOGS = 3

    /**
     * Whether a written summary may be produced for this snapshot.
     *
     * This mirrors the conditions under which `deriveRunwayPresentationState` suppresses the
     * runway estimates, and for the same reason. A cached or stale snapshot is perfectly
     * useful to read as labelled numbers under a visible "not current" notice, but prose
     * reads as authoritative in a way a number under a banner does not. "You logged 42 times
     * this month" is misleading when three logs are still sitting in the offline queue, even
     * though the underlying figure is exactly what the screen shows.
     *
     * So the summary is strictly more conservative than the statistics it describes: it
     * appears only for a live, current, settled snapshot.
     */
    fun shouldSummarise(state: InsightsUiState, pendingActionCount: Int?): Boolean {
        val data = state.data ?: return false
        if (pendingActionCount == null || pendingActionCount > 0) return false
        if (state.pendingRange != null) return false
        if (state.isFromCache || state.isStale) return false
        if (state.isRefreshing || state.isInitialLoading) return false
        if (state.error != null) return false
        return data.overview.logCount >= MINIMUM_LOGS
    }

    /** The period the facts describe, taken from the response rather than the device. */
    fun period(response: InsightsResponseDto): Period = Period(
        label = when (response.range.scope.lowercase()) {
            "all", "alltime", "all_time" -> "all recorded activity"
            else -> "the last ${response.range.dayCount} days"
        },
        start = response.range.from,
        end = response.range.to,
    )

    fun from(response: InsightsResponseDto): List<Fact> {
        val facts = mutableListOf<Fact>()
        val overview = response.overview
        val warnings = response.dataQuality.warnings

        facts += Fact("Consumption entries recorded", overview.logCount.toString())

        facts += Fact(
            label = "Days with any activity",
            value = "${overview.activeDayCount} of ${response.range.dayCount}",
        )

        facts += Fact("Distinct products used", overview.distinctProductCount.toString())

        overview.daysSinceLastLog?.let {
            facts += Fact("Days since the last entry", it.toString())
        }

        response.byType
            .filter { it.rangeLogCount > 0 }
            .maxByOrNull { it.rangeLogCount }
            ?.let { facts += Fact("Most used product type", it.type, "${it.rangeLogCount} entries") }

        response.byWeekday
            .filter { it.logCount > 0 }
            .maxByOrNull { it.logCount }
            ?.let { facts += Fact("Busiest day of the week", weekdayName(it.isoDay), "${it.logCount} entries") }

        response.byHour
            .filter { it.logCount > 0 }
            .maxByOrNull { it.logCount }
            ?.let { facts += Fact("Most common time of day", hourBand(it.hour), "${it.logCount} entries") }

        // Recorded spend only. Nothing projected, nothing per-day, nothing extrapolated.
        val spend = response.spending.range
        if (spend.personalPurchaseCount > 0) {
            facts += Fact(
                label = "Recorded spend in this range",
                value = formatCadCents(spend.personalSpendCents),
                note = buildString {
                    append("across ${spend.personalPurchaseCount} purchases")
                    if (spend.unknownPersonalCostCount > 0) {
                        append("; ${spend.unknownPersonalCostCount} more have no recorded cost")
                    }
                },
            )
        }

        val inventory = response.inventory
        if (inventory.activeCount > 0 || inventory.unopenedCount > 0) {
            facts += Fact(
                label = "Products currently open",
                value = inventory.activeCount.toString(),
                note = "${inventory.unopenedCount} unopened",
            )
        }

        // Surfacing incompleteness lets the model qualify rather than overstate. Without it a
        // confident summary can be built on a range that silently dropped rows.
        if (!response.dataQuality.complete) {
            val gaps = listOfNotNull(
                warnings.unknownPurchaseDateCount.takeIf { it > 0 }?.let { "$it purchases have no date" },
                warnings.estimatedPurchaseDateCount.takeIf { it > 0 }?.let { "$it purchase dates are estimated" },
                warnings.unknownStatusCount.takeIf { it > 0 }?.let { "$it products have unknown status" },
            )
            facts += Fact(
                label = "Data completeness",
                value = "incomplete",
                note = gaps.takeIf { it.isNotEmpty() }?.joinToString("; "),
            )
        }

        return facts
    }

    private fun weekdayName(isoDay: Int): String = when (isoDay) {
        1 -> "Monday"; 2 -> "Tuesday"; 3 -> "Wednesday"; 4 -> "Thursday"
        5 -> "Friday"; 6 -> "Saturday"; 7 -> "Sunday"
        else -> "an unrecognised day"
    }

    private fun hourBand(hour: Int): String = when (hour) {
        in 0..5 -> "night"
        in 6..11 -> "morning"
        in 12..17 -> "afternoon"
        else -> "evening"
    }
}
