package com.example.ui

import com.example.data.SyncAcknowledgementPlan
import com.example.data.SyncOutcome
import com.example.data.QueuedSyncSnapshot
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusMessageTest {
    @Test
    fun nonAppliedOutcomesPreserveExistingMessages() {
        assertEquals("Nothing to sync", syncStatusMessage(SyncOutcome.NothingToSync()))
        assertEquals(
            "Error: HTML received. Ensure URL is correct and deployed for 'Anyone'.",
            syncStatusMessage(SyncOutcome.HtmlResponse(emptyList())),
        )
        assertEquals(
            "Sync failed: BACKEND_BUSY",
            syncStatusMessage(SyncOutcome.Failed("BACKEND_BUSY", "Busy", emptyList())),
        )
        assertEquals(
            "Sync failed: Busy",
            syncStatusMessage(SyncOutcome.Failed(null, "Busy", emptyList())),
        )
        assertEquals(
            "Sync failed: Unknown error",
            syncStatusMessage(SyncOutcome.Failed(null, null, emptyList())),
        )
        assertEquals(
            "Configuration error: sync response environment does not match this app",
            syncStatusMessage(SyncOutcome.EnvironmentMismatch(emptyList())),
        )
        assertEquals(
            "Sync response could not be matched safely. Your entries are still pending.",
            syncStatusMessage(SyncOutcome.RequestIdMismatch(emptyList())),
        )
        assertEquals(
            "Sync error: offline",
            syncStatusMessage(SyncOutcome.TransportError(IOException("offline"), emptyList())),
        )
        assertEquals(
            "Server confirmation is taking longer than expected. Your entry is still pending and will retry safely.",
            syncStatusMessage(SyncOutcome.TransportError(SocketTimeoutException("timeout"), emptyList())),
        )
    }

    @Test
    fun appliedOutcomesPreservePriorityAndCounts() {
        assertEquals(
            "Sync failed: backend update required for history corrections",
            syncStatusMessage(applied(SyncAcknowledgementPlan(correctionCapabilityMissing = true))),
        )
        assertEquals(
            "Sync failed: backend update required for finish actions",
            syncStatusMessage(applied(SyncAcknowledgementPlan(finishCapabilityMissing = true))),
        )
        assertEquals(
            "Sync partial: 6 item(s) need attention",
            syncStatusMessage(
                applied(
                    SyncAcknowledgementPlan(
                        rejectedPurchaseCount = 1,
                        rejectedConsumptionCount = 2,
                        rejectedFinishActionCount = 3,
                    ),
                ),
            ),
        )
        assertEquals(
            "Sync successful",
            syncStatusMessage(
                applied(
                    SyncAcknowledgementPlan(
                        acknowledgedPurchaseActionIds = setOf("purchase-1"),
                    ),
                ),
            ),
        )
        assertEquals(
            "Sync completed without acknowledgements",
            syncStatusMessage(applied(SyncAcknowledgementPlan())),
        )
    }

    private fun applied(plan: SyncAcknowledgementPlan) = SyncOutcome.Applied(
        plan = plan,
        pendingCorrectionsAtSnapshot = emptyList(),
        snapshot = QueuedSyncSnapshot(emptySet(), emptySet()),
    )
}
