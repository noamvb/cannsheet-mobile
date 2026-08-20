package com.example.data

import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Narrow persistence boundary used by all local consumption entry points. */
interface ConsumptionLogRepository {
    suspend fun addConsumption(
        action: ConsumptionAction,
        loggedAtEpochMillis: Long = System.currentTimeMillis(),
    )
}

/** Narrow loaded-pen preference boundary used by the shared consumption logger. */
interface LoadedPenProductStore {
    suspend fun setLoadedPenProductId(productId: String)

    suspend fun clearLoadedPenProductId()
}

/** Narrow local-history boundary used by the shared consumption logger. */
interface ConsumptionHistoryRecorder {
    suspend fun record(entry: ConsumptionHistoryEntry)
}

private object NoOpConsumptionHistoryRecorder : ConsumptionHistoryRecorder {
    override suspend fun record(entry: ConsumptionHistoryEntry) = Unit
}

/**
 * The one place that turns a chosen product and quantity into a queued consumption action.
 * The Log screen and the home-screen widget both use it so loaded-pen bookkeeping cannot drift
 * between entry points.
 */
class ConsumptionLogger(
    private val repository: ConsumptionLogRepository,
    private val consumptionPreferences: LoadedPenProductStore,
    private val historyRecorder: ConsumptionHistoryRecorder = NoOpConsumptionHistoryRecorder,
) {
    suspend fun log(
        date: String,
        time: String,
        productId: String,
        productUuid: String?,
        productType: String?,
        uses: Double,
        isFinished: Boolean,
        loggedAtEpochMillis: Long = System.currentTimeMillis(),
        eventId: String = UUID.randomUUID().toString(),
        updateLoadedCart: Boolean = true,
    ) {
        val action = ConsumptionAction(
            eventId = eventId,
            date = date,
            time = time,
            productId = productId,
            uses = uses,
            isFinished = isFinished,
            productUuid = productUuid,
        )
        repository.addConsumption(action, loggedAtEpochMillis)
        try {
            historyRecorder.record(
                ConsumptionHistoryEntry(
                    eventId = eventId,
                    date = date,
                    time = time,
                    productId = productId,
                    productUuid = productUuid,
                    uses = uses,
                    isFinished = isFinished,
                    loggedAtEpochMillis = loggedAtEpochMillis,
                ),
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // History is derived convenience data; it must never roll back the queued write.
        }
        if (updateLoadedCart && productType?.let(ProductTypeCodes::normalize) == ProductTypeCodes.PEN) {
            if (isFinished) {
                consumptionPreferences.clearLoadedPenProductId()
            } else {
                consumptionPreferences.setLoadedPenProductId(productId)
            }
        }
    }
}
