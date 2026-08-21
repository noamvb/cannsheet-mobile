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
    val estimates: RunwayEstimateState =
        RunwayEstimateState.Suppressed(RunwaySuppressionReason.NO_SNAPSHOT),
)

/** Why estimates are withheld. Every value must produce honest user-facing copy. */
enum class RunwaySuppressionReason {
    /** No snapshot at all; the screen renders loading or error instead. */
    NO_SNAPSHOT,

    /** The snapshot came from analytics_cache and has not been confirmed live. */
    CACHED_SNAPSHOT,

    /** A live snapshot exists but a local action invalidated it. */
    STALE_SNAPSHOT,

    /** A range change is in flight; the displayed data does not match the request. */
    RANGE_CHANGING,

    /** Room has queued actions the snapshot cannot include. */
    PENDING_ACTIONS,

    /** Room has not emitted a real queue count yet; a synthetic zero must not estimate. */
    QUEUE_COUNT_UNKNOWN,
}

sealed interface RunwayEstimateState {
    data class Suppressed(val reason: RunwaySuppressionReason) : RunwayEstimateState

    data class Ready(
        val runwayByProductId: Map<String, ProductRunway>,
        val evidenceByType: Map<String, TypeCapacityEvidence>,
        val spendRunRate: SpendRunRate?,
        val diagnostics: List<RunwayDiagnostic>,
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
    val suppressionReason = when {
        data == null -> RunwaySuppressionReason.NO_SNAPSHOT
        pendingActionCount == null -> RunwaySuppressionReason.QUEUE_COUNT_UNKNOWN
        pendingActionCount > 0 -> RunwaySuppressionReason.PENDING_ACTIONS
        insights.pendingRange != null -> RunwaySuppressionReason.RANGE_CHANGING
        insights.isFromCache -> RunwaySuppressionReason.CACHED_SNAPSHOT
        insights.isStale -> RunwaySuppressionReason.STALE_SNAPSHOT
        else -> null
    }
    if (data == null || suppressionReason != null) {
        return RunwayPresentationState(
            insights = insights,
            pendingActionCount = pendingActionCount,
            estimates = RunwayEstimateState.Suppressed(
                suppressionReason ?: RunwaySuppressionReason.NO_SNAPSHOT,
            ),
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
        if (
            data.range.dayCount < MIN_BURN_RATE_DAYS &&
            runwayByProductId.isEmpty()
        ) {
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

/** Null means the surface renders nothing, because the screen already explains itself. */
internal fun runwaySuppressionText(
    reason: RunwaySuppressionReason,
    pendingActionCount: Int,
): String? = when (reason) {
    RunwaySuppressionReason.NO_SNAPSHOT,
    RunwaySuppressionReason.QUEUE_COUNT_UNKNOWN,
    -> null

    RunwaySuppressionReason.PENDING_ACTIONS -> {
        // Build the whole clause, not a verb fragment: "1 entry is" and
        // "2 entries are" disagree in both number and verb.
        val clause = if (pendingActionCount == 1) {
            "1 entry is waiting"
        } else {
            "$pendingActionCount entries are waiting"
        }
        "Runway estimates pause while $clause to sync, because this snapshot " +
            "cannot include them."
    }

    RunwaySuppressionReason.RANGE_CHANGING ->
        "Runway estimates pause while the range changes."

    RunwaySuppressionReason.CACHED_SNAPSHOT ->
        "Runway estimates pause until Insights refreshes from the sheet; " +
            "this is a cached snapshot."

    RunwaySuppressionReason.STALE_SNAPSHOT ->
        "Runway estimates pause until Insights refreshes from the sheet."
}

private const val STATUS_ACTIVE = "ACTIVE"
private const val TYPE_UNKNOWN = "UNKNOWN"
