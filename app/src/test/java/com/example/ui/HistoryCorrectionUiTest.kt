package com.example.ui

import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.HistoryPageDto
import com.example.data.HistoryResponseDto
import com.example.data.DataQualityDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCorrectionUiTest {
    @Test
    fun editingRequiresFreshEnabledServerHistoryAndNoQueuedCorrection() {
        val event = historyEvent()
        val fresh = historyState()

        assertTrue(historyCorrectionAvailability(fresh, event, emptySet()).canCorrect)
        assertFalse(historyCorrectionAvailability(fresh.copy(isFromCache = true), event, emptySet()).canCorrect)
        assertFalse(historyCorrectionAvailability(fresh, event, setOf(event.eventUuid)).canCorrect)
        assertEquals(
            "A correction for this entry is already queued. Wait for it to sync before making another change.",
            historyCorrectionAvailability(fresh, event, setOf(event.eventUuid)).reason,
        )
    }

    @Test
    fun voidedEventOffersRestoreInsteadOfReplaceOrVoid() {
        val availability = historyCorrectionAvailability(
            state = historyState(),
            event = historyEvent(lifecycleState = "VOIDED"),
            queuedTargetIds = emptySet(),
        )

        assertFalse(availability.canCorrect)
        assertFalse(availability.canVoid)
        assertTrue(availability.canRestore)
    }

    @Test
    fun replacementValidationRequiresStableProductAndDoesNotSilentlyAllowLongReason() {
        val valid = HistoryCorrectionDraft(
            targetEventId = "00000000-0000-4000-8000-000000000001",
            expectedHeadId = null,
            operation = HistoryCorrectionOperation.REPLACE,
            replacementDate = "2026-07-29",
            replacementTime = "13:30",
            replacementProductId = "product-1",
            replacementProductUuid = "00000000-0000-4000-8000-000000000002",
            replacementQuantity = 1.0,
            replacementFinished = false,
        )

        assertTrue(isCorrectionDraftValid(valid))
        assertFalse(isCorrectionDraftValid(valid.copy(replacementProductUuid = null)))
        assertFalse(isCorrectionDraftValid(valid.copy(reason = "x".repeat(201))))
    }

    @Test
    fun reopenChoiceIsOnlyOfferedWhenTheCurrentFinishMarkerIsRemoved() {
        val finishedEvent = historyEvent(finished = true, reopenEligible = true)

        assertFalse(canOfferProductReopen(finishedEvent, finishedEvent.productId, replacementFinished = true))
        assertTrue(canOfferProductReopen(finishedEvent, finishedEvent.productId, replacementFinished = false))
        assertTrue(canOfferProductReopen(finishedEvent, "different-product", replacementFinished = true))
    }

    @Test
    fun conflictUsesPlainRefreshMessage() {
        assertEquals(
            "This entry changed elsewhere. Refresh History before trying again.",
            correctionRejectionMessage("CORRECTION_CONFLICT"),
        )
    }

    private fun historyState() = HistoryUiState(
        events = listOf(historyEvent()),
        hasFreshCursor = true,
        response = HistoryResponseDto(
            success = true,
            analyticsVersion = 2,
            resource = "history",
            environment = "SANDBOX",
            timeZone = "America/New_York",
            filters = HistoryFilters(),
            sort = "TIMESTAMP_DESC_CANONICAL_ROW_DESC",
            events = listOf(historyEvent()),
            page = HistoryPageDto(limit = 50, hasMore = false),
            dataQuality = DataQualityDto(complete = true, warnings = QualityWarningsDto()),
            sourceRevision = SourceRevisionDto("a".repeat(64), 1, 1),
            generatedAtEpochMillis = 1L,
            serverDurationMs = 1L,
            correctionVersion = 1,
            correctionWritesEnabled = true,
        ),
    )

    private fun historyEvent(
        lifecycleState: String = "ORIGINAL",
        finished: Boolean = false,
        reopenEligible: Boolean = false,
    ) = HistoryEventDto(
        eventUuid = "00000000-0000-4000-8000-000000000001",
        occurredAtEpochMillis = 1L,
        localDate = "2026-07-29",
        localTime = "13:30:00",
        productUuid = "00000000-0000-4000-8000-000000000002",
        productId = "product-1",
        productName = "Product",
        productType = "P",
        quantity = 1.0,
        weightCode = null,
        finished = finished,
        source = "ANDROID",
        lifecycleState = lifecycleState,
        correctionHeadId = null,
        revision = 0,
        reopenEligible = reopenEligible,
    )
}
