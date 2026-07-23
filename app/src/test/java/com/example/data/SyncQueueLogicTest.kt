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
}
