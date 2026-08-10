package com.example.data

import com.squareup.moshi.Moshi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SyncQueueGateway {
    suspend fun getPendingPurchases(): List<PurchaseAction>

    suspend fun getPendingConsumptions(): List<ConsumptionAction>

    suspend fun getPendingFinishActions(): List<FinishAction>

    suspend fun getPendingConsumptionCorrections(): List<PendingConsumptionCorrection>

    suspend fun getOrCreateSyncRequestId(snapshot: QueuedSyncSnapshot): String

    suspend fun applyAcknowledgements(plan: SyncAcknowledgementPlan)
}

sealed interface SyncOutcome {
    val pendingCorrectionsAtSnapshot: List<PendingConsumptionCorrection>

    data class NothingToSync(
        override val pendingCorrectionsAtSnapshot: List<PendingConsumptionCorrection> = emptyList(),
    ) : SyncOutcome

    data class HtmlResponse(
        override val pendingCorrectionsAtSnapshot: List<PendingConsumptionCorrection>,
    ) : SyncOutcome

    data class EnvironmentMismatch(
        override val pendingCorrectionsAtSnapshot: List<PendingConsumptionCorrection>,
    ) : SyncOutcome

    data class RequestIdMismatch(
        override val pendingCorrectionsAtSnapshot: List<PendingConsumptionCorrection>,
    ) : SyncOutcome

    data class Failed(
        val errorCode: String?,
        val message: String?,
        override val pendingCorrectionsAtSnapshot: List<PendingConsumptionCorrection>,
    ) : SyncOutcome

    data class TransportError(
        val error: Exception,
        override val pendingCorrectionsAtSnapshot: List<PendingConsumptionCorrection>,
    ) : SyncOutcome

    data class Applied(
        val plan: SyncAcknowledgementPlan,
        override val pendingCorrectionsAtSnapshot: List<PendingConsumptionCorrection>,
        val snapshot: QueuedSyncSnapshot,
    ) : SyncOutcome
}

class SyncEngine(
    private val api: GasApiService,
    private val moshi: Moshi,
    private val gateway: SyncQueueGateway,
    private val expectedEnvironment: String,
    private val mutex: Mutex,
) {
    suspend fun sync(
        endpoint: String,
        onLockAcquired: suspend () -> Unit = {},
        onOutcome: suspend (SyncOutcome) -> Unit = {},
        onLockReleasing: suspend () -> Unit = {},
    ): SyncOutcome = mutex.withLock {
        onLockAcquired()
        try {
            val outcome = performSync(endpoint)
            onOutcome(outcome)
            outcome
        } finally {
            onLockReleasing()
        }
    }

    private suspend fun performSync(endpoint: String): SyncOutcome {
        var pendingCorrections: List<PendingConsumptionCorrection> = emptyList()
        return try {
            val pendingPurchases = gateway.getPendingPurchases()
            val pendingConsumptions = gateway.getPendingConsumptions()
            val pendingFinishActions = gateway.getPendingFinishActions()
            pendingCorrections = gateway.getPendingConsumptionCorrections()
            if (
                pendingPurchases.isEmpty() &&
                pendingConsumptions.isEmpty() &&
                pendingFinishActions.isEmpty() &&
                pendingCorrections.isEmpty()
            ) {
                return SyncOutcome.NothingToSync()
            }

            // These exact UUIDs are the immutable request snapshot. Anything
            // queued after this point is intentionally left for the next sync.
            val snapshot = QueuedSyncSnapshot(
                purchaseActionIds = pendingPurchases.mapTo(linkedSetOf(), PurchaseAction::actionId),
                consumptionEventIds = pendingConsumptions.mapTo(linkedSetOf(), ConsumptionAction::eventId),
                finishActionIds = pendingFinishActions.mapTo(linkedSetOf(), FinishAction::actionId),
                purchaseActionIdByTempId = pendingPurchases.associate { it.tempId to it.actionId },
                correctionActionIdByTargetEventId = pendingCorrections.associate {
                    it.targetEventId to it.actionId
                },
            )
            val requestId = gateway.getOrCreateSyncRequestId(snapshot)
            val payload = SyncPayload(
                requestId = requestId,
                environment = expectedEnvironment,
                purchases = pendingPurchases.map { action ->
                    SyncPurchase(
                        actionId = action.actionId,
                        tempId = action.tempId,
                        date = action.date,
                        type = action.type,
                        name = action.name,
                        cost = action.cost,
                        thc = action.thc,
                        grams = action.grams,
                        borrowed = action.borrowed,
                        postTax = action.postTax,
                        productUuid = action.productUuid,
                    )
                },
                consumptions = pendingConsumptions.map { action ->
                    SyncConsumption(
                        eventId = action.eventId,
                        date = action.date,
                        time = action.time,
                        productId = action.productId,
                        uses = action.uses,
                        isFinished = action.isFinished,
                        productUuid = action.productUuid,
                    )
                },
                finishActions = pendingFinishActions.map { action ->
                    SyncFinishAction(
                        actionId = action.actionId,
                        date = action.date,
                        time = action.time,
                        productId = action.productId,
                        productUuid = action.productUuid,
                    )
                },
                consumptionCorrections = pendingCorrections.map(
                    PendingConsumptionCorrection::toSyncConsumptionCorrection,
                ),
            )

            val rawResponse = api.syncData(endpoint, payload).string()
            if (rawResponse.trimStart().startsWith("<")) {
                return SyncOutcome.HtmlResponse(pendingCorrections)
            }

            val response = moshi.adapter(SyncResponse::class.java).lenient().fromJson(rawResponse)
            if (response?.success != true) {
                return SyncOutcome.Failed(
                    errorCode = response?.errorCode,
                    message = response?.message,
                    pendingCorrectionsAtSnapshot = pendingCorrections,
                )
            }
            if (!environmentMatches(expectedEnvironment, response.environment)) {
                return SyncOutcome.EnvironmentMismatch(pendingCorrections)
            }
            if (
                (response.apiVersion == 2 || pendingCorrections.isNotEmpty()) &&
                response.requestId != requestId
            ) {
                return SyncOutcome.RequestIdMismatch(pendingCorrections)
            }

            val plan = buildAcknowledgementPlan(snapshot, response)
            gateway.applyAcknowledgements(plan)
            SyncOutcome.Applied(
                plan = plan,
                pendingCorrectionsAtSnapshot = pendingCorrections,
                snapshot = snapshot,
            )
        } catch (error: Exception) {
            // Network, parsing, and acknowledgement failures keep every
            // unproven queue row available for a duplicate-safe retry.
            SyncOutcome.TransportError(error, pendingCorrections)
        }
    }
}
