package com.example.widget.today

import com.example.data.ConsumptionHistoryEntry
import com.example.domain.formatQuantityInInputUnit
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class TodayUiModel(
    val todayUses: Double,
    val todayDisplay: String,
    val baselineUses: Double?,
    val baselineDisplay: String?,
    val comparison: TodayComparison,
    val streakDays: Int,
    val hasTodayEntries: Boolean,
)

enum class TodayComparison { UNAVAILABLE, ABOVE, BELOW, MATCH }

fun buildTodayUiModel(
    entries: List<ConsumptionHistoryEntry>,
    todayDate: String,
    secondsPerUse: Double?,
): TodayUiModel {
    val today = canonicalIsoDate(todayDate)
        ?: return emptyTodayUiModel()

    val entriesByDate = entries
        .mapNotNull { entry ->
            canonicalIsoDate(entry.date)?.let { date -> date to entry }
        }
        .groupBy({ it.first }, { it.second })

    val todayEntries = entriesByDate[today].orEmpty()
    val todayUses = todayEntries.sumOf { it.uses }
    val hasTodayEntries = todayEntries.isNotEmpty()

    val observedPreviousDayTotals = (1..7).mapNotNull { daysAgo ->
        val date = isoDateMinusDays(today, daysAgo)
        entriesByDate[date]
            ?.takeIf { it.isNotEmpty() }
            ?.let { dateEntries -> dateEntries.sumOf { it.uses } }
    }
    val baselineUses = observedPreviousDayTotals
        .takeIf { it.size >= 3 }
        ?.average()

    val loggedDates = entriesByDate.keys.filterNot { it > today }.toSet()
    val streakStart = if (hasTodayEntries) today else isoDateMinusDays(today, 1)
    var streakDays = 0
    var streakDate = streakStart
    while (streakDate != null && streakDate in loggedDates) {
        streakDays++
        streakDate = isoDateMinusDays(streakDate, 1)
    }

    val comparison = when {
        baselineUses == null -> TodayComparison.UNAVAILABLE
        todayUses > baselineUses -> TodayComparison.ABOVE
        todayUses < baselineUses -> TodayComparison.BELOW
        else -> TodayComparison.MATCH
    }

    return TodayUiModel(
        todayUses = todayUses,
        todayDisplay = if (hasTodayEntries) {
            formatQuantityInInputUnit(todayUses, secondsPerUse)
        } else {
            EMPTY_TODAY_DISPLAY
        },
        baselineUses = baselineUses,
        baselineDisplay = baselineUses?.let { formatQuantityInInputUnit(it, secondsPerUse) },
        comparison = comparison,
        streakDays = streakDays,
        hasTodayEntries = hasTodayEntries,
    )
}

private const val EMPTY_TODAY_DISPLAY = "No logs yet today"

private val ISO_DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

/** Uses UTC only for date-only parsing/arithmetic; the input date is device-local. */
private fun canonicalIsoDate(value: String): String? {
    if (!ISO_DATE_PATTERN.matches(value)) return null
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val position = ParsePosition(0)
    val parsed = format.parse(value, position)
    return parsed
        ?.takeIf { position.index == value.length }
        ?.let(format::format)
}

private fun isoDateMinusDays(value: String, days: Int): String? {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val position = ParsePosition(0)
    val parsed = format.parse(value, position)
        ?.takeIf { position.index == value.length }
        ?: return null
    val shifted = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        time = parsed
        add(Calendar.DAY_OF_YEAR, -days)
    }
    return format.format(shifted.time)
}

private fun emptyTodayUiModel() = TodayUiModel(
    todayUses = 0.0,
    todayDisplay = EMPTY_TODAY_DISPLAY,
    baselineUses = null,
    baselineDisplay = null,
    comparison = TodayComparison.UNAVAILABLE,
    streakDays = 0,
    hasTodayEntries = false,
)
