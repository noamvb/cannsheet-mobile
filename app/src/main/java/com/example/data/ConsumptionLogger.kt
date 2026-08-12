package com.example.data

import java.util.UUID

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

/**
 * The one place that turns a chosen product and quantity into a queued consumption action.
 * The Log screen and the home-screen widget both use it so loaded-pen bookkeeping cannot drift
 * between entry points.
 */
class ConsumptionLogger(
    private val repository: ConsumptionLogRepository,
    private val consumptionPreferences: LoadedPenProductStore,
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
    ) {
        val action = ConsumptionAction(
            eventId = UUID.randomUUID().toString(),
            date = date,
            time = time,
            productId = productId,
            uses = uses,
            isFinished = isFinished,
            productUuid = productUuid,
        )
        repository.addConsumption(action, loggedAtEpochMillis)
        if (productType?.let(ProductTypeCodes::normalize) == ProductTypeCodes.PEN) {
            if (isFinished) {
                consumptionPreferences.clearLoadedPenProductId()
            } else {
                consumptionPreferences.setLoadedPenProductId(productId)
            }
        }
    }
}
