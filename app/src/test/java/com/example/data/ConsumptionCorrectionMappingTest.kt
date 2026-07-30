package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ConsumptionCorrectionMappingTest {
    @Test
    fun replaceMappingPreservesEmptyOriginalWeightCode() {
        val pending = replacement(weightCode = "")

        val request = pending.toSyncConsumptionCorrection()

        assertEquals(ConsumptionCorrectionOperation.REPLACE, request.operation)
        assertEquals("", request.replacement?.weightCode)
        assertEquals(pending.actionId, request.actionId)
        assertEquals(pending.targetEventId, request.targetEventId)
    }

    @Test
    fun voidMappingDoesNotInventReplacementValues() {
        val pending = PendingConsumptionCorrection(
            targetEventId = TARGET_EVENT_ID,
            actionId = ACTION_ID,
            expectedCorrectionHeadId = "",
            operation = ConsumptionCorrectionOperation.VOID,
            reopenProduct = false,
            reason = "Logged by mistake",
        )

        val request = pending.toSyncConsumptionCorrection()

        assertNull(request.replacement)
        assertEquals("", request.expectedCorrectionHeadId)
    }

    @Test
    fun mappingRejectsReasonLongerThanBackendLimitWithoutTruncating() {
        val pending = replacement(
            reason = "x".repeat(MAX_CONSUMPTION_CORRECTION_REASON_LENGTH + 1),
        )

        assertThrows(IllegalArgumentException::class.java) {
            pending.toSyncConsumptionCorrection()
        }
    }

    @Test
    fun mappingRejectsIncompleteReplacementSnapshot() {
        val pending = replacement().copy(replacementWeightCode = null)

        assertThrows(IllegalArgumentException::class.java) {
            pending.toSyncConsumptionCorrection()
        }
    }

    private fun replacement(
        reason: String? = null,
        weightCode: String = "",
    ) = PendingConsumptionCorrection(
        targetEventId = TARGET_EVENT_ID,
        actionId = ACTION_ID,
        expectedCorrectionHeadId = CORRECTION_HEAD_ID,
        operation = ConsumptionCorrectionOperation.REPLACE,
        reopenProduct = false,
        reason = reason,
        replacementDate = "2026-07-28",
        replacementTime = "20:15:00",
        replacementProductUuid = PRODUCT_UUID,
        replacementProductId = "*P1",
        replacementUses = 2.0,
        replacementWeightCode = weightCode,
        replacementFinished = false,
    )

    private companion object {
        const val TARGET_EVENT_ID = "10000000-0000-4000-8000-000000000001"
        const val ACTION_ID = "20000000-0000-4000-8000-000000000001"
        const val CORRECTION_HEAD_ID = "30000000-0000-4000-8000-000000000001"
        const val PRODUCT_UUID = "40000000-0000-4000-8000-000000000001"
    }
}
