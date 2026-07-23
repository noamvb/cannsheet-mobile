package com.example.data

data class QueuedSyncSnapshot(
    val purchaseActionIds: Set<String>,
    val consumptionEventIds: Set<String>,
    val finishActionIds: Set<String> = emptySet(),
    val purchaseActionIdByTempId: Map<String, String> = emptyMap(),
)

data class PurchaseIdentityRemap(
    val actionId: String,
    val tempId: String,
    val legacyProductId: String,
    val productUuid: String?,
)

data class SyncAcknowledgementPlan(
    val acknowledgedPurchaseActionIds: Set<String> = emptySet(),
    val acknowledgedConsumptionEventIds: Set<String> = emptySet(),
    val acknowledgedFinishActionIds: Set<String> = emptySet(),
    val purchaseRemaps: List<PurchaseIdentityRemap> = emptyList(),
    val rejectedPurchaseCount: Int = 0,
    val rejectedConsumptionCount: Int = 0,
    val rejectedFinishActionCount: Int = 0,
    val finishCapabilityMissing: Boolean = false,
) {
    val hasAcknowledgements: Boolean
        get() =
            acknowledgedPurchaseActionIds.isNotEmpty() ||
                acknowledgedConsumptionEventIds.isNotEmpty() ||
                acknowledgedFinishActionIds.isNotEmpty()

    val hasRejections: Boolean
        get() = rejectedPurchaseCount > 0 || rejectedConsumptionCount > 0 || rejectedFinishActionCount > 0
}

fun buildAcknowledgementPlan(
    snapshot: QueuedSyncSnapshot,
    response: SyncResponse,
): SyncAcknowledgementPlan {
    if (!response.success) return SyncAcknowledgementPlan()

    if (response.apiVersion == 2) {
        val acceptedStatuses = setOf("committed", "duplicate")
        val purchaseAcks = response.acknowledgedPurchases.filter { acknowledgement ->
            acknowledgement.status in acceptedStatuses && acknowledgement.actionId in snapshot.purchaseActionIds
        }
        val consumptionAcks = response.acknowledgedConsumptions.filter { acknowledgement ->
            acknowledgement.status in acceptedStatuses && acknowledgement.eventId in snapshot.consumptionEventIds
        }
        val finishAcks = response.acknowledgedFinishActions.orEmpty().filter { acknowledgement ->
            acknowledgement.status in acceptedStatuses && acknowledgement.actionId in snapshot.finishActionIds
        }
        val remaps = purchaseAcks.mapNotNull { acknowledgement ->
            val tempId = acknowledgement.tempId ?: return@mapNotNull null
            val legacyProductId = acknowledgement.legacyProductId ?: return@mapNotNull null
            PurchaseIdentityRemap(
                actionId = acknowledgement.actionId,
                tempId = tempId,
                legacyProductId = legacyProductId,
                productUuid = acknowledgement.productUuid,
            )
        }
        return SyncAcknowledgementPlan(
            acknowledgedPurchaseActionIds = purchaseAcks.mapTo(linkedSetOf(), AcknowledgedPurchase::actionId),
            acknowledgedConsumptionEventIds = consumptionAcks.mapTo(linkedSetOf(), AcknowledgedConsumption::eventId),
            acknowledgedFinishActionIds = finishAcks.mapTo(linkedSetOf(), AcknowledgedFinishAction::actionId),
            purchaseRemaps = remaps,
            rejectedPurchaseCount = response.rejectedPurchases.size,
            rejectedConsumptionCount = response.rejectedConsumptions.size,
            rejectedFinishActionCount = response.rejectedFinishActions.orEmpty().size,
            finishCapabilityMissing = snapshot.finishActionIds.isNotEmpty() &&
                (
                    response.acknowledgedFinishActions == null ||
                        response.rejectedFinishActions == null ||
                        response.rejectedFinishActions.any { rejection ->
                            rejection.errorCode == "BACKEND_UPDATE_REQUIRED"
                        }
                ),
        )
    }

    val remaps = response.productIdMap.mapNotNull { (tempId, legacyProductId) ->
        val actionId = snapshot.purchaseActionIdByTempId[tempId] ?: return@mapNotNull null
        PurchaseIdentityRemap(actionId, tempId, legacyProductId, null)
    }
    return SyncAcknowledgementPlan(
        acknowledgedPurchaseActionIds = snapshot.purchaseActionIds,
        acknowledgedConsumptionEventIds = snapshot.consumptionEventIds,
        purchaseRemaps = remaps,
        finishCapabilityMissing = snapshot.finishActionIds.isNotEmpty(),
    )
}
