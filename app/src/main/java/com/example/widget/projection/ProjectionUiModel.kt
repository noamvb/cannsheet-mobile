package com.example.widget.projection

import com.example.data.AnalyticsProductDto
import com.example.data.InsightsResponseDto
import com.example.domain.ProductRunway
import com.example.domain.buildProductRunway
import com.example.domain.buildTypeCapacityModels
import com.example.domain.projectCurrentMonthSpend
import com.example.ui.runwaySummaryText
import com.example.ui.spendRunRateText
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** The two projection surfaces supported by the single projection widget. */
enum class ProjectionMode {
    RUNWAY,
    SPEND,
}

/** Why the projection widget has no figure to render. */
enum class ProjectionSuppressionReason {
    NO_SNAPSHOT,
    INCOMPLETE_SNAPSHOT,
    STRUCTURALLY_UNUSABLE_SNAPSHOT,
    NO_USABLE_FIGURE,
}

sealed interface ProjectionUiModel {
    data class Suppressed(val reason: ProjectionSuppressionReason) : ProjectionUiModel

    data class Ready(
        val mode: ProjectionMode,
        val figures: List<ProjectionFigure>,
    ) : ProjectionUiModel
}

/**
 * A complete render unit. [valueText] and [asOfDate] are plain data here; the invariant ADR-039
 * requires — that a cached projection is never shown without its as-of date — is enforced where
 * this figure actually reaches a view, by `ProjectionWidgetRenderer`'s private `setFigure`
 * helper, the single place both fields are written into the widget's views together.
 */
data class ProjectionFigure(
    val key: String,
    val valueText: String,
    val asOfDate: String,
)

/**
 * Builds the pure projection-widget state from one cached snapshot.
 *
 * No freshness check is performed here: B7 allows a widget to show a cached projection when the
 * snapshot itself is complete, provided every figure carries the snapshot's own as-of date.
 */
fun buildProjectionUiModel(
    insights: InsightsResponseDto?,
    mode: ProjectionMode,
): ProjectionUiModel {
    if (insights == null) {
        return ProjectionUiModel.Suppressed(ProjectionSuppressionReason.NO_SNAPSHOT)
    }
    if (!insights.dataQuality.complete) {
        return ProjectionUiModel.Suppressed(ProjectionSuppressionReason.INCOMPLETE_SNAPSHOT)
    }
    if (!insights.success || insights.products.isEmpty()) {
        return ProjectionUiModel.Suppressed(
            ProjectionSuppressionReason.STRUCTURALLY_UNUSABLE_SNAPSHOT,
        )
    }

    val asOfDate = insights.range.to
    if (!isIsoDate(asOfDate) || insights.range.dayCount <= 0) {
        return ProjectionUiModel.Suppressed(
            ProjectionSuppressionReason.STRUCTURALLY_UNUSABLE_SNAPSHOT,
        )
    }

    val figures = when (mode) {
        ProjectionMode.RUNWAY -> buildRunwayFigures(insights, asOfDate)
        ProjectionMode.SPEND -> buildSpendFigures(insights, asOfDate)
    }
    return figures.takeIf { it.isNotEmpty() }?.let {
        ProjectionUiModel.Ready(mode = mode, figures = it)
    } ?: ProjectionUiModel.Suppressed(ProjectionSuppressionReason.NO_USABLE_FIGURE)
}

private fun buildRunwayFigures(
    insights: InsightsResponseDto,
    asOfDate: String,
): List<ProjectionFigure> {
    val models = buildTypeCapacityModels(insights.products)
    return insights.products
        .asSequence()
        .filter { it.status == STATUS_ACTIVE }
        .mapNotNull { product ->
            buildProductRunway(
                product = product,
                models = models,
                range = insights.range,
                analyticsTimeZone = insights.timeZone,
            )?.let { runway -> runwayFigure(product, runway, asOfDate) }
        }
        .toList()
}

private fun runwayFigure(
    product: AnalyticsProductDto,
    runway: ProductRunway,
    asOfDate: String,
): ProjectionFigure = ProjectionFigure(
    key = runway.productId,
    valueText = "${product.name}: ${runwaySummaryText(runway)}",
    asOfDate = asOfDate,
)

private fun buildSpendFigures(
    insights: InsightsResponseDto,
    asOfDate: String,
): List<ProjectionFigure> {
    // projectCurrentMonthSpend intentionally requires an instant. Noon on the snapshot's own
    // range.to is deterministic and avoids letting a device clock change the rendered result.
    val asOfEpochMillis = epochAtLocalNoon(asOfDate, insights.timeZone) ?: return emptyList()
    val spend = projectCurrentMonthSpend(insights, asOfEpochMillis) ?: return emptyList()
    return listOf(
        ProjectionFigure(
            key = spend.month,
            valueText = spendRunRateText(spend),
            asOfDate = asOfDate,
        ),
    )
}

private fun isIsoDate(value: String): Boolean {
    if (value.length != ISO_DATE_LENGTH || value[4] != '-' || value[7] != '-') return false
    return value.indices
        .filterNot { it == 4 || it == 7 }
        .all { value[it].isDigit() }
}

private fun epochAtLocalNoon(date: String, timeZoneId: String): Long? {
    if (!isIsoDate(date)) return null
    val timeZone = TimeZone.getTimeZone(timeZoneId)
    if (timeZoneId !in TimeZone.getAvailableIDs()) return null
    val calendar = GregorianCalendar(timeZone, Locale.ROOT).apply {
        isLenient = false
        clear()
        set(Calendar.YEAR, date.substring(0, 4).toIntOrNull() ?: return null)
        set(Calendar.MONTH, date.substring(5, 7).toIntOrNull()?.minus(1) ?: return null)
        set(Calendar.DAY_OF_MONTH, date.substring(8, 10).toIntOrNull() ?: return null)
        set(Calendar.HOUR_OF_DAY, 12)
    }
    return try {
        calendar.timeInMillis
    } catch (_: IllegalArgumentException) {
        null
    }
}

private const val STATUS_ACTIVE = "ACTIVE"
private const val ISO_DATE_LENGTH = 10
