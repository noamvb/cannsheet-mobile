package com.example.domain

import com.example.data.InsightsResponseDto
import com.example.data.ProductTypeCodes
import com.example.ui.formatCadCents
import com.noamv.localllm.contract.v2.AggregateQuery
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.FactEvidence
import com.noamv.localllm.contract.v2.MetricId
import com.noamv.localllm.contract.v2.ProviderCapabilities
import com.noamv.localllm.contract.v2.ProviderFactsResult
import java.security.MessageDigest

object CannsheetFactsCalculator {

    val SUPPORTED_METRICS = listOf(
        MetricId.CANNSHEET_RECORDED_SPEND.wireName,
        MetricId.CANNSHEET_RECORDED_SPEND_COVERAGE.wireName,
        MetricId.CANNSHEET_PURCHASE_COUNT.wireName,
        MetricId.CANNSHEET_CONSUMPTION_COUNT.wireName,
        MetricId.CANNSHEET_ACTIVE_DAYS.wireName,
        MetricId.CANNSHEET_TIME_BAND_COUNTS.wireName,
        MetricId.CANNSHEET_WEEKDAY_COUNTS.wireName,
        MetricId.CANNSHEET_PRODUCT_LOG_COUNTS.wireName,
        MetricId.CANNSHEET_INVENTORY_REMAINING.wireName,
    )

    val SUPPORTED_GROUPINGS = listOf("WEEKDAY", "TIME_OF_DAY", "PRODUCT_TYPE", "NONE")
    val SUPPORTED_FILTERS = listOf("cannsheet.product_name", "cannsheet.product_type")

    fun getCapabilities(): ProviderCapabilities = ProviderCapabilities(
        providerVersion = AssistantContractV2.VERSION,
        sourceApp = AppSource.CANNSHEET,
        supportedMetrics = SUPPORTED_METRICS,
        supportedGroupings = SUPPORTED_GROUPINGS,
        supportedFilters = SUPPORTED_FILTERS,
    )

    fun calculateFacts(
        query: AggregateQuery,
        snapshot: InsightsResponseDto?,
        pendingActionCount: Int,
        asOfTime: Long = System.currentTimeMillis(),
    ): ProviderFactsResult {
        if (snapshot == null) {
            return ProviderFactsResult(
                sourceApp = AppSource.CANNSHEET,
                facts = emptyList(),
                revision = "none",
                asOfTime = asOfTime,
                timezone = "UTC",
                warnings = listOf("No settled analytics snapshot available"),
            )
        }

        val warnings = mutableListOf<String>()
        if (pendingActionCount > 0) {
            warnings += "$pendingActionCount local actions pending sync"
        }
        if (!snapshot.dataQuality.complete) {
            warnings += "Data quality report indicates incomplete history"
        }

        val tz = snapshot.timeZone.ifBlank { "UTC" }
        val rev = snapshot.sourceRevision.dataVersion.ifBlank { "rev-${snapshot.range.from}-${snapshot.range.to}" }
        val facts = mutableListOf<FactEvidence>()

        val metricsToCompute = if (query.metrics.isEmpty()) {
            MetricId.entries.filter { it.wireName.startsWith("cannsheet.") }
        } else {
            query.metrics.filter { it.wireName.startsWith("cannsheet.") }
        }

        val overview = snapshot.overview
        val spend = snapshot.spending.range
        val personalPurchaseCount = spend.personalPurchaseCount.coerceAtLeast(0)
        val unknownCostPurchaseCount = spend.unknownPersonalCostCount.coerceIn(0, personalPurchaseCount)
        val knownCostPurchaseCount = personalPurchaseCount - unknownCostPurchaseCount

        for (metric in metricsToCompute) {
            when (metric) {
                MetricId.CANNSHEET_RECORDED_SPEND -> {
                    if (knownCostPurchaseCount > 0) {
                        facts += FactEvidence(
                            factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "spend"),
                            sourceApp = AppSource.CANNSHEET,
                            sourceContractVersion = AssistantContractV2.VERSION,
                            metricId = metric.wireName,
                            displayLabel = "Recorded spend in range",
                            displayValue = formatCadCents(spend.personalSpendCents),
                            qualifier = "across $knownCostPurchaseCount of $personalPurchaseCount purchases with recorded costs",
                            denominator = personalPurchaseCount.toLong(),
                            coveragePercent = if (personalPurchaseCount > 0) (knownCostPurchaseCount * 100) / personalPurchaseCount else null,
                            timezone = tz,
                            asOfTime = asOfTime,
                            sourceRevision = rev,
                        )
                    }
                }
                MetricId.CANNSHEET_RECORDED_SPEND_COVERAGE -> {
                    if (personalPurchaseCount > 0) {
                        val pct = (knownCostPurchaseCount * 100) / personalPurchaseCount
                        facts += FactEvidence(
                            factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "coverage"),
                            sourceApp = AppSource.CANNSHEET,
                            sourceContractVersion = AssistantContractV2.VERSION,
                            metricId = metric.wireName,
                            displayLabel = "Recorded spend coverage",
                            displayValue = "$pct%",
                            qualifier = "$knownCostPurchaseCount of $personalPurchaseCount purchases have recorded costs",
                            denominator = personalPurchaseCount.toLong(),
                            coveragePercent = pct,
                            timezone = tz,
                            asOfTime = asOfTime,
                            sourceRevision = rev,
                        )
                    }
                }
                MetricId.CANNSHEET_PURCHASE_COUNT -> {
                    facts += FactEvidence(
                        factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "purchases"),
                        sourceApp = AppSource.CANNSHEET,
                        sourceContractVersion = AssistantContractV2.VERSION,
                        metricId = metric.wireName,
                        displayLabel = "Purchases recorded",
                        displayValue = personalPurchaseCount.toString(),
                        denominator = personalPurchaseCount.toLong(),
                        timezone = tz,
                        asOfTime = asOfTime,
                        sourceRevision = rev,
                    )
                }
                MetricId.CANNSHEET_CONSUMPTION_COUNT -> {
                    facts += FactEvidence(
                        factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "consumption"),
                        sourceApp = AppSource.CANNSHEET,
                        sourceContractVersion = AssistantContractV2.VERSION,
                        metricId = metric.wireName,
                        displayLabel = "Consumption entries recorded",
                        displayValue = overview.logCount.toString(),
                        denominator = overview.logCount.toLong(),
                        timezone = tz,
                        asOfTime = asOfTime,
                        sourceRevision = rev,
                    )
                }
                MetricId.CANNSHEET_ACTIVE_DAYS -> {
                    facts += FactEvidence(
                        factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "active_days"),
                        sourceApp = AppSource.CANNSHEET,
                        sourceContractVersion = AssistantContractV2.VERSION,
                        metricId = metric.wireName,
                        displayLabel = "Days with activity",
                        displayValue = "${overview.activeDayCount} of ${snapshot.range.dayCount}",
                        denominator = snapshot.range.dayCount.toLong(),
                        timezone = tz,
                        asOfTime = asOfTime,
                        sourceRevision = rev,
                    )
                }
                MetricId.CANNSHEET_TIME_BAND_COUNTS -> {
                    val timeBands = snapshot.byHour
                        .filter { it.hour in 0..23 && it.logCount > 0 }
                        .groupBy({ hourBand(it.hour) }, { it.logCount })
                        .mapValues { (_, counts) -> counts.sum() }
                    for ((band, count) in timeBands) {
                        facts += FactEvidence(
                            factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "time_band_$band"),
                            sourceApp = AppSource.CANNSHEET,
                            sourceContractVersion = AssistantContractV2.VERSION,
                            metricId = metric.wireName,
                            displayLabel = "Entries logged in $band",
                            displayValue = count.toString(),
                            denominator = overview.logCount.toLong(),
                            timezone = tz,
                            asOfTime = asOfTime,
                            sourceRevision = rev,
                        )
                    }
                }
                MetricId.CANNSHEET_WEEKDAY_COUNTS -> {
                    for (weekday in snapshot.byWeekday.filter { it.logCount > 0 }) {
                        val dayName = weekdayName(weekday.isoDay)
                        facts += FactEvidence(
                            factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "weekday_${weekday.isoDay}"),
                            sourceApp = AppSource.CANNSHEET,
                            sourceContractVersion = AssistantContractV2.VERSION,
                            metricId = metric.wireName,
                            displayLabel = "Entries logged on $dayName",
                            displayValue = weekday.logCount.toString(),
                            denominator = overview.logCount.toLong(),
                            timezone = tz,
                            asOfTime = asOfTime,
                            sourceRevision = rev,
                        )
                    }
                }
                MetricId.CANNSHEET_PRODUCT_LOG_COUNTS -> {
                    val typeCounts = snapshot.byType
                        .filter { it.rangeLogCount > 0 }
                        .groupBy({ productTypeLabel(it.type) }, { it.rangeLogCount })
                        .mapValues { (_, counts) -> counts.sum() }
                    tiedHighest(typeCounts.entries.toList(), { it.value })?.let { (types, count) ->
                        facts += FactEvidence(
                            factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "top_product_types"),
                            sourceApp = AppSource.CANNSHEET,
                            sourceContractVersion = AssistantContractV2.VERSION,
                            metricId = metric.wireName,
                            displayLabel = "Most frequently logged product type",
                            displayValue = formatNames(types.map { it.key }.sorted()),
                            qualifier = tieNote(count, types.size),
                            denominator = overview.logCount.toLong(),
                            timezone = tz,
                            asOfTime = asOfTime,
                            sourceRevision = rev,
                        )
                    }
                }
                MetricId.CANNSHEET_INVENTORY_REMAINING -> {
                    val inv = snapshot.inventory
                    if (inv.activeCount > 0 || inv.unopenedCount > 0) {
                        facts += FactEvidence(
                            factId = generateFactId(AppSource.CANNSHEET, metric.wireName, "inventory"),
                            sourceApp = AppSource.CANNSHEET,
                            sourceContractVersion = AssistantContractV2.VERSION,
                            metricId = metric.wireName,
                            displayLabel = "Products currently open",
                            displayValue = inv.activeCount.toString(),
                            qualifier = "${inv.unopenedCount} unopened",
                            timezone = tz,
                            asOfTime = asOfTime,
                            sourceRevision = rev,
                        )
                    }
                }
                else -> Unit
            }
        }

        return ProviderFactsResult(
            sourceApp = AppSource.CANNSHEET,
            facts = facts,
            revision = rev,
            asOfTime = asOfTime,
            timezone = tz,
            warnings = warnings,
        )
    }

    private fun generateFactId(source: AppSource, metricWireName: String, detail: String): String {
        val raw = "${source.name}_${metricWireName}_$detail"
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
        return "fact_${source.name.lowercase()}_$hash"
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

    private fun <T> tiedHighest(values: List<T>, count: (T) -> Int): Pair<List<T>, Int>? {
        val maximum = values.maxOfOrNull(count) ?: return null
        return values.filter { count(it) == maximum } to maximum
    }

    private fun tieNote(count: Int, tieCount: Int): String =
        if (tieCount == 1) "$count entries" else "tied at $count entries each"

    private fun productTypeLabel(type: String): String {
        val normalized = ProductTypeCodes.normalize(type)
        val displayLabel = ProductTypeCodes.displayLabel(type)
        return if (displayLabel == normalized) type.trim() else displayLabel
    }

    private fun formatNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names.single()
        2 -> names.joinToString(" and ")
        else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
    }
}
