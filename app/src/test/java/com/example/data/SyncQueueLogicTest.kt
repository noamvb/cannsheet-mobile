package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncQueueLogicTest {
    private val snapshot = QueuedSyncSnapshot(
        purchaseActionIds = setOf("purchase-1", "purchase-2"),
        consumptionEventIds = setOf("event-1", "event-2"),
        finishActionIds = setOf("finish-1", "finish-2"),
        purchaseActionIdByTempId = mapOf("temp-1" to "purchase-1", "temp-2" to "purchase-2"),
    )

    @Test
    fun v2PartialResponseDeletesOnlyCommittedOrDuplicateIds() {
        val response = SyncResponse(
            success = true,
            apiVersion = 2,
            allAccepted = false,
            acknowledgedPurchases = listOf(
                AcknowledgedPurchase("purchase-1", "temp-1", "product-uuid", "*P1", "committed"),
            ),
            rejectedPurchases = listOf(
                RejectedPurchase("purchase-2", "INVALID_ITEM", "bad purchase"),
            ),
            acknowledgedConsumptions = listOf(
                AcknowledgedConsumption("event-1", "duplicate"),
            ),
            rejectedConsumptions = listOf(
                RejectedConsumption("event-2", "UNKNOWN_PRODUCT", "missing product"),
            ),
            acknowledgedFinishActions = listOf(
                AcknowledgedFinishAction("finish-1", "committed"),
            ),
            rejectedFinishActions = listOf(
                RejectedFinishAction("finish-2", "UNKNOWN_PRODUCT", "missing product"),
            ),
        )

        val plan = buildAcknowledgementPlan(snapshot, response)

        assertEquals(setOf("purchase-1"), plan.acknowledgedPurchaseActionIds)
        assertEquals(setOf("event-1"), plan.acknowledgedConsumptionEventIds)
        assertEquals(setOf("finish-1"), plan.acknowledgedFinishActionIds)
        assertEquals("*P1", plan.purchaseRemaps.single().legacyProductId)
        assertTrue(plan.hasRejections)
    }

    @Test
    fun legacySuccessDeletesOnlyTheImmutableRequestSnapshot() {
        val response = SyncResponse(
            success = true,
            productIdMap = mapOf("temp-1" to "*P1"),
        )

        val plan = buildAcknowledgementPlan(snapshot, response)

        assertEquals(snapshot.purchaseActionIds, plan.acknowledgedPurchaseActionIds)
        assertEquals(snapshot.consumptionEventIds, plan.acknowledgedConsumptionEventIds)
        assertTrue(plan.acknowledgedFinishActionIds.isEmpty())
        assertTrue(plan.finishCapabilityMissing)
        assertEquals("purchase-1", plan.purchaseRemaps.single().actionId)
        assertFalse(plan.hasRejections)
    }

    @Test
    fun failedRequestAcknowledgesNothing() {
        val plan = buildAcknowledgementPlan(snapshot, SyncResponse(success = false, message = "LOCK_TIMEOUT"))

        assertFalse(plan.hasAcknowledgements)
        assertTrue(plan.purchaseRemaps.isEmpty())
    }

    @Test
    fun v2ResponseFromOldBackendRetainsFinishQueueAndRequestsBackendUpdate() {
        val response = SyncResponse(
            success = true,
            apiVersion = 2,
            acknowledgedPurchases = emptyList(),
            acknowledgedConsumptions = emptyList(),
        )

        val plan = buildAcknowledgementPlan(snapshot, response)

        assertTrue(plan.acknowledgedFinishActionIds.isEmpty())
        assertTrue(plan.finishCapabilityMissing)
    }

    @Test
    fun backendWithoutRecoverableFinishSupportRetainsQueueAndRequestsUpdate() {
        val response = SyncResponse(
            success = true,
            apiVersion = 2,
            acknowledgedFinishActions = emptyList(),
            rejectedFinishActions = listOf(
                RejectedFinishAction(
                    actionId = "finish-1",
                    errorCode = "BACKEND_UPDATE_REQUIRED",
                    message = "Finish actions require recoverable sync support",
                ),
            ),
        )

        val plan = buildAcknowledgementPlan(snapshot, response)

        assertTrue(plan.acknowledgedFinishActionIds.isEmpty())
        assertEquals(1, plan.rejectedFinishActionCount)
        assertTrue(plan.finishCapabilityMissing)
    }

    @Test
    fun correctionAckRequiresExactSentActionAndTargetPair() {
        val correctionSnapshot = QueuedSyncSnapshot(
            purchaseActionIds = emptySet(),
            consumptionEventIds = emptySet(),
            correctionActionIdByTargetEventId = mapOf("event-1" to "action-1"),
        )
        val response = SyncResponse(
            success = true,
            apiVersion = 2,
            correctionVersion = 1,
            correctionWritesEnabled = true,
            acknowledgedConsumptionCorrections = listOf(
                AcknowledgedConsumptionCorrection("action-1", "event-1", 1, "committed"),
                AcknowledgedConsumptionCorrection("different-action", "event-1", 1, "committed"),
                AcknowledgedConsumptionCorrection("action-1", "different-event", 1, "duplicate"),
            ),
            rejectedConsumptionCorrections = emptyList(),
        )

        val plan = buildAcknowledgementPlan(correctionSnapshot, response)

        assertEquals(
            setOf(ConsumptionCorrectionIdentity("action-1", "event-1")),
            plan.acknowledgedConsumptionCorrections,
        )
        assertFalse(plan.correctionCapabilityMissing)
        assertTrue(plan.requestWasAudited)
    }

    @Test
    fun nonmatchingCorrectionAckDoesNotAcknowledgeQueuedCorrection() {
        val correctionSnapshot = QueuedSyncSnapshot(
            purchaseActionIds = emptySet(),
            consumptionEventIds = emptySet(),
            correctionActionIdByTargetEventId = mapOf("event-1" to "action-new"),
        )
        val response = SyncResponse(
            success = true,
            apiVersion = 2,
            correctionVersion = 1,
            correctionWritesEnabled = true,
            acknowledgedConsumptionCorrections = listOf(
                AcknowledgedConsumptionCorrection("action-old", "event-1", 1, "duplicate"),
            ),
            rejectedConsumptionCorrections = emptyList(),
        )

        val plan = buildAcknowledgementPlan(correctionSnapshot, response)

        assertTrue(plan.acknowledgedConsumptionCorrections.isEmpty())
        assertFalse(plan.hasAcknowledgements)
    }

    @Test
    fun structuredCorrectionRejectionsRemainAvailableWithoutAcknowledgement() {
        val correctionSnapshot = QueuedSyncSnapshot(
            purchaseActionIds = emptySet(),
            consumptionEventIds = emptySet(),
            correctionActionIdByTargetEventId = mapOf(
                "event-conflict" to "action-conflict",
                "event-reopen" to "action-reopen",
            ),
        )
        val conflict = RejectedConsumptionCorrection(
            actionId = "action-conflict",
            targetEventId = "event-conflict",
            errorCode = "CORRECTION_CONFLICT",
            message = "The entry has a newer revision",
            currentHeadId = "head-current",
        )
        val reopen = RejectedConsumptionCorrection(
            actionId = "action-reopen",
            targetEventId = "event-reopen",
            errorCode = "REOPEN_NOT_SAFE",
            message = "The product cannot be reopened safely",
            currentHeadId = "head-reopen",
        )
        val response = SyncResponse(
            success = true,
            apiVersion = 2,
            correctionVersion = 1,
            correctionWritesEnabled = true,
            acknowledgedConsumptionCorrections = emptyList(),
            rejectedConsumptionCorrections = listOf(conflict, reopen),
        )

        val plan = buildAcknowledgementPlan(correctionSnapshot, response)

        assertEquals(listOf(conflict, reopen), plan.rejectedConsumptionCorrections)
        assertEquals("head-current", plan.rejectedConsumptionCorrections.first().currentHeadId)
        assertTrue(plan.acknowledgedConsumptionCorrections.isEmpty())
        assertTrue(plan.hasRejections)
    }

    @Test
    fun payloadFingerprintIsStableForRetryButChangesWithRemainingPayload() {
        val first = QueuedSyncSnapshot(
            purchaseActionIds = setOf("purchase-2", "purchase-1"),
            consumptionEventIds = setOf("event-2", "event-1"),
            correctionActionIdByTargetEventId = mapOf(
                "target-2" to "action-2",
                "target-1" to "action-1",
            ),
        )
        val retry = first.copy(
            purchaseActionIds = first.purchaseActionIds.reversed().toSet(),
            consumptionEventIds = first.consumptionEventIds.reversed().toSet(),
            correctionActionIdByTargetEventId =
                first.correctionActionIdByTargetEventId.entries.reversed().associate { it.toPair() },
        )
        val remaining = first.copy(
            purchaseActionIds = setOf("purchase-2"),
            correctionActionIdByTargetEventId = mapOf("target-2" to "action-2"),
        )

        assertEquals(first.payloadFingerprint(), retry.payloadFingerprint())
        assertFalse(first.payloadFingerprint() == remaining.payloadFingerprint())
    }
}
