package com.example.data

data class QueuedSyncSnapshot(
    val purchaseActionIds: Set<String>,
    val consumptionEventIds: Set<String>,
    val finishActionIds: Set<String> = emptySet(),
    val purchaseActionIdByTempId: Map<String, String> = emptyMap(),
    val correctionActionIdByTargetEventId: Map<String, String> = emptyMap(),
) {
    fun payloadFingerprint(): String = listOf(
        "p:${purchaseActionIds.sorted().joinToString(",")}",
        "c:${consumptionEventIds.sorted().joinToString(",")}",
        "f:${finishActionIds.sorted().joinToString(",")}",
        "r:${
            correctionActionIdByTargetEventId.toSortedMap().entries.joinToString(",") {
                (targetEventId, actionId) -> "$targetEventId=$actionId"
            }
        }",
    ).joinToString("|")
}

data class ConsumptionCorrectionIdentity(
    val actionId: String,
    val targetEventId: String,
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
    val acknowledgedConsumptionCorrections: Set<ConsumptionCorrectionIdentity> = emptySet(),
    val purchaseRemaps: List<PurchaseIdentityRemap> = emptyList(),
    val rejectedPurchaseCount: Int = 0,
    val rejectedConsumptionCount: Int = 0,
    val rejectedFinishActionCount: Int = 0,
    val rejectedConsumptionCorrections: List<RejectedConsumptionCorrection> = emptyList(),
    val finishCapabilityMissing: Boolean = false,
    val correctionVersion: Int? = null,
    val correctionWritesEnabled: Boolean? = null,
    val correctionCapabilityMissing: Boolean = false,
    val requestWasAudited: Boolean = false,
) {
    val hasAcknowledgements: Boolean
        get() =
            acknowledgedPurchaseActionIds.isNotEmpty() ||
                acknowledgedConsumptionEventIds.isNotEmpty() ||
                acknowledgedFinishActionIds.isNotEmpty() ||
                acknowledgedConsumptionCorrections.isNotEmpty()

    val hasRejections: Boolean
        get() =
            rejectedPurchaseCount > 0 ||
                rejectedConsumptionCount > 0 ||
                rejectedFinishActionCount > 0 ||
                rejectedConsumptionCorrections.isNotEmpty()
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
        val correctionAcks = response.acknowledgedConsumptionCorrections.orEmpty().mapNotNull { acknowledgement ->
            val sentActionId =
                snapshot.correctionActionIdByTargetEventId[acknowledgement.targetEventId]
            if (
                acknowledgement.status !in acceptedStatuses ||
                acknowledgement.actionId != sentActionId
            ) {
                null
            } else {
                ConsumptionCorrectionIdentity(
                    actionId = acknowledgement.actionId,
                    targetEventId = acknowledgement.targetEventId,
                )
            }
        }
        val correctionRejections = response.rejectedConsumptionCorrections.orEmpty()
            .filter { rejection -> rejection.matches(snapshot.correctionActionIdByTargetEventId) }
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
            acknowledgedConsumptionCorrections = correctionAcks.toCollection(linkedSetOf()),
            purchaseRemaps = remaps,
            rejectedPurchaseCount = response.rejectedPurchases.size,
            rejectedConsumptionCount = response.rejectedConsumptions.size,
            rejectedFinishActionCount = response.rejectedFinishActions.orEmpty().size,
            rejectedConsumptionCorrections = correctionRejections,
            finishCapabilityMissing = snapshot.finishActionIds.isNotEmpty() &&
                (
                    response.acknowledgedFinishActions == null ||
                        response.rejectedFinishActions == null ||
                        response.rejectedFinishActions.any { rejection ->
                            rejection.errorCode == "BACKEND_UPDATE_REQUIRED"
                        }
                ),
            correctionVersion = response.correctionVersion,
            correctionWritesEnabled = response.correctionWritesEnabled,
            correctionCapabilityMissing = snapshot.correctionActionIdByTargetEventId.isNotEmpty() &&
                (
                    response.correctionVersion != 1 ||
                        response.acknowledgedConsumptionCorrections == null ||
                        response.rejectedConsumptionCorrections == null
                ),
            requestWasAudited = true,
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
        correctionCapabilityMissing = snapshot.correctionActionIdByTargetEventId.isNotEmpty(),
        requestWasAudited = true,
    )
}

private fun RejectedConsumptionCorrection.matches(
    sentActionIdByTargetEventId: Map<String, String>,
): Boolean {
    if (sentActionIdByTargetEventId.isEmpty()) return false
    val actionId = actionId
    val targetEventId = targetEventId
    return when {
        actionId != null && targetEventId != null ->
            sentActionIdByTargetEventId[targetEventId] == actionId
        targetEventId != null -> targetEventId in sentActionIdByTargetEventId
        actionId != null -> actionId in sentActionIdByTargetEventId.values
        else -> true
    }
}
