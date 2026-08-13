package com.example.ui

import com.example.data.ProductTypeCodes
import com.example.domain.MIN_BURN_RATE_DAYS
import com.example.domain.MIN_CAPACITY_SAMPLE
import com.example.domain.ProductRunway
import com.example.domain.SpendRunRate
import com.example.domain.TypeCapacityEvidence
import com.example.domain.buildProductRunway
import com.example.domain.buildTypeCapacityEvidence
import com.example.domain.buildTypeCapacityModels
import com.example.domain.projectCurrentMonthSpend

data class RunwayPresentationState(
    val insights: InsightsUiState = InsightsUiState(),
    /** Null until Room has emitted the first real queue count. */
    val pendingActionCount: Int? = null,
    val estimates: RunwayEstimateState = RunwayEstimateState.Suppressed,
)

sealed interface RunwayEstimateState {
    data object Suppressed : RunwayEstimateState

    data class Ready(
        val runwayByProductId: Map<String, ProductRunway>,
        val evidenceByType: Map<String, TypeCapacityEvidence>,
        val spendRunRate: SpendRunRate?,
        val diagnostics: List<RunwayDiagnostic>,
        val selectedRangeDayCount: Int = Int.MAX_VALUE,
    ) : RunwayEstimateState
}

sealed interface RunwayDiagnostic {
    val type: String

    data object SelectedRangeTooShort : RunwayDiagnostic {
        override val type: String = "range"
    }

    data class InsufficientFinishEvidence(
        override val type: String,
        val availableSampleSize: Int,
    ) : RunwayDiagnostic

    data class NoUseInRange(
        override val type: String,
        val productCount: Int,
    ) : RunwayDiagnostic

    data class UnavailableForType(
        override val type: String,
        val productCount: Int,
    ) : RunwayDiagnostic
}

internal fun deriveRunwayPresentationState(
    insights: InsightsUiState,
    pendingActionCount: Int?,
    nowEpochMillis: Long,
): RunwayPresentationState {
    val data = insights.data
    if (
        data == null ||
        insights.isStale ||
        insights.isFromCache ||
        insights.pendingRange != null ||
        pendingActionCount == null ||
        pendingActionCount > 0
    ) {
        return RunwayPresentationState(
            insights = insights,
            pendingActionCount = pendingActionCount,
            estimates = RunwayEstimateState.Suppressed,
        )
    }

    val evidenceByType = buildTypeCapacityEvidence(data.products)
    val models = buildTypeCapacityModels(data.products)
    val activeProducts = data.products
        .filter { it.status == STATUS_ACTIVE }
        .filter { ProductTypeCodes.normalize(it.type).isEligibleRunwayType() }
    val runwayByProductId = activeProducts
        .mapNotNull { product ->
            buildProductRunway(
                product = product,
                models = models,
                range = data.range,
                analyticsTimeZone = data.timeZone,
            )
        }
        .associateBy(ProductRunway::productId)

    val diagnostics = buildList {
        if (data.range.dayCount < MIN_BURN_RATE_DAYS) {
            add(RunwayDiagnostic.SelectedRangeTooShort)
        }
        activeProducts
            .groupBy { ProductTypeCodes.normalize(it.type) }
            .toSortedMap()
            .forEach { (type, products) ->
                val evidence = evidenceByType[type]
                if ((evidence?.sampleSize ?: 0) < MIN_CAPACITY_SAMPLE) {
                    add(
                        RunwayDiagnostic.InsufficientFinishEvidence(
                            type = type,
                            availableSampleSize = evidence?.sampleSize ?: 0,
                        ),
                    )
                    return@forEach
                }

                if (data.range.dayCount < MIN_BURN_RATE_DAYS) return@forEach

                val unavailable = products.filterNot {
                    runwayByProductId.containsKey(it.productId)
                }
                val noUseCount = unavailable.count {
                    it.range.quantity.isFinite() && it.range.quantity <= 0.0
                }
                val otherwiseUnavailableCount = unavailable.size - noUseCount
                if (noUseCount > 0) {
                    add(
                        RunwayDiagnostic.NoUseInRange(
                            type = type,
                            productCount = noUseCount,
                        ),
                    )
                }
                if (otherwiseUnavailableCount > 0) {
                    add(
                        RunwayDiagnostic.UnavailableForType(
                            type = type,
                            productCount = otherwiseUnavailableCount,
                        ),
                    )
                }
            }
    }

    return RunwayPresentationState(
        insights = insights,
        pendingActionCount = pendingActionCount,
        estimates = RunwayEstimateState.Ready(
            runwayByProductId = runwayByProductId,
            evidenceByType = evidenceByType,
            spendRunRate = projectCurrentMonthSpend(data, nowEpochMillis),
            diagnostics = diagnostics,
            selectedRangeDayCount = data.range.dayCount,
        ),
    )
}

private fun String.isEligibleRunwayType(): Boolean = isNotBlank() && this != TYPE_UNKNOWN

internal fun runwayDiagnosticText(diagnostic: RunwayDiagnostic): String = when (diagnostic) {
    RunwayDiagnostic.SelectedRangeTooShort ->
        "Pick a range of at least $MIN_BURN_RATE_DAYS days to estimate a recorded-use pace."
    is RunwayDiagnostic.InsufficientFinishEvidence -> {
        val type = ProductTypes.label(diagnostic.type)
        "Not enough recorded finished $type products yet — " +
            "$MIN_CAPACITY_SAMPLE needed, ${diagnostic.availableSampleSize} available."
    }
    is RunwayDiagnostic.NoUseInRange -> {
        val label = ProductTypes.label(diagnostic.type)
        val noun = if (diagnostic.productCount == 1) "product" else "products"
        "No $label runway estimate for ${diagnostic.productCount} active $noun with no " +
            "recorded use in this range."
    }
    is RunwayDiagnostic.UnavailableForType -> {
        val label = ProductTypes.label(diagnostic.type)
        val noun = if (diagnostic.productCount == 1) "product" else "products"
        "No reliable $label runway estimate for ${diagnostic.productCount} other active $noun " +
            "from this snapshot."
    }
}

private const val STATUS_ACTIVE = "ACTIVE"
private const val TYPE_UNKNOWN = "UNKNOWN"
