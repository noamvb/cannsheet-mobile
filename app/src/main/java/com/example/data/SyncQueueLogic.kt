package com.example.data

data class QueuedSyncSnapshot(
    val purchaseActionIds: Set<String>,
    val consumptionEventIds: Set<String>,
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
    val purchaseRemaps: List<PurchaseIdentityRemap> = emptyList(),
    val rejectedPurchaseCount: Int = 0,
    val rejectedConsumptionCount: Int = 0,
) {
    val hasAcknowledgements: Boolean
        get() = acknowledgedPurchaseActionIds.isNotEmpty() || acknowledgedConsumptionEventIds.isNotEmpty()

    val hasRejections: Boolean
        get() = rejectedPurchaseCount > 0 || rejectedConsumptionCount > 0
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
            purchaseRemaps = remaps,
            rejectedPurchaseCount = response.rejectedPurchases.size,
            rejectedConsumptionCount = response.rejectedConsumptions.size,
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
    )
}
