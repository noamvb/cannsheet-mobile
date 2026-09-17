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

/** Bridges the phone's loaded-pen DataStore state into the ordinary sync request. */
interface LoadedPenSyncSource {
    suspend fun pendingLoadedPenState(): PendingLoadedPenState?

    suspend fun markLoadedPenStateSynced(updatedAtEpochMillis: Long)
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
        val clientStateStatus: String? = null,
    ) : SyncOutcome
}

class SyncEngine(
    private val api: GasApiService,
    private val moshi: Moshi,
    private val gateway: SyncQueueGateway,
    private val expectedEnvironment: String,
    private val mutex: Mutex,
    private val loadedPenSource: LoadedPenSyncSource? = null,
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
            val pendingLoadedPen = loadedPenSource?.pendingLoadedPenState()
            if (
                pendingPurchases.isEmpty() &&
                pendingConsumptions.isEmpty() &&
                pendingFinishActions.isEmpty() &&
                pendingCorrections.isEmpty() &&
                pendingLoadedPen == null
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
                loadedPenUpdatedAtEpochMillis = pendingLoadedPen?.updatedAtEpochMillis,
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
                clientState = pendingLoadedPen?.let {
                    SyncClientState(it.loadedPenProductId, it.updatedAtEpochMillis)
                },
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
            val acknowledgedClientState = response.acknowledgedClientState
            if (
                pendingLoadedPen != null &&
                acknowledgedClientState != null &&
                acknowledgedClientState.loadedPenUpdatedAtEpochMillis == pendingLoadedPen.updatedAtEpochMillis
            ) {
                loadedPenSource?.markLoadedPenStateSynced(acknowledgedClientState.loadedPenUpdatedAtEpochMillis)
            }
            SyncOutcome.Applied(
                plan = plan,
                pendingCorrectionsAtSnapshot = pendingCorrections,
                snapshot = snapshot,
                clientStateStatus = acknowledgedClientState?.status,
            )
        } catch (error: Exception) {
            // Network, parsing, and acknowledgement failures keep every
            // unproven queue row available for a duplicate-safe retry.
            SyncOutcome.TransportError(error, pendingCorrections)
        }
    }
}
