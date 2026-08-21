package com.example.nfc

import android.content.Context
import com.example.data.PenQuickLogDataSource
import com.example.domain.PenQuickLogState
import com.example.domain.SubmissionDateTime
import com.example.domain.currentSubmissionDateTime
import com.example.widget.DeferredPenInputKind
import com.example.widget.PenWidgetCommitCoordinator
import com.example.widget.PenWidgetCommitPayload
import com.example.widget.PenWidgetRuntime
import com.example.widget.PenWidgetScheduler
import com.example.widget.PenWidgetStateRepository
import java.util.UUID

data class NfcQuickLogAccepted(
    val payload: PenWidgetCommitPayload,
    val uses: Int,
    val cartName: String,
)

/**
 * Cold-start-safe NFC entry point. This class owns no UI state and never calls the ViewModel;
 * it loads a fresh Pen state, captures the product and timestamp, and writes the direct-uses
 * payload before the caller displays a queued result.
 */
class NfcQuickLogCoordinator(
    private val context: Context,
    private val registry: NfcQuickLogRegistryRepository =
        NfcQuickLogRegistryRepository(context.applicationContext),
    private val loadPenState: suspend () -> PenQuickLogState = {
        PenQuickLogDataSource.load(context.applicationContext)
    },
    private val stateRepository: PenWidgetStateRepository =
        PenWidgetStateRepository(context.applicationContext),
    private val commitCoordinator: PenWidgetCommitCoordinator =
        PenWidgetCommitCoordinator.forNfc(context.applicationContext),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val submissionDateTime: (Long) -> SubmissionDateTime = ::currentSubmissionDateTime,
) {
    private val appContext = context.applicationContext

    suspend fun accept(tag: NfcQuickLogTagData): NfcQuickLogResultState {
        when (val verification = registry.verify(tag)) {
            NfcQuickLogRegistryVerification.RegistryCorrupt ->
                return NfcQuickLogResultState.RegistryCorrupt

            NfcQuickLogRegistryVerification.Unregistered ->
                return NfcQuickLogResultState.UnregisteredTag

            is NfcQuickLogRegistryVerification.UsesMismatch ->
                return NfcQuickLogResultState.RegistryMismatch

            NfcQuickLogRegistryVerification.InvalidTag ->
                return NfcQuickLogResultState.InvalidTag

            is NfcQuickLogRegistryVerification.Verified -> Unit
        }

        val oldPending = stateRepository.read(PEN_NFC_SURFACE_ID).pendingCommit
        if (oldPending != null) {
            runCatching {
                commitCoordinator.commit(
                    context = appContext,
                    appWidgetId = PEN_NFC_SURFACE_ID,
                    commitId = oldPending.commitId,
                    nowMillis = now(),
                    force = true,
                )
            }.getOrElse { return NfcQuickLogResultState.PreviousTapBusy }
            // A concurrent timer may have completed the same claim between our read and force
            // attempt, making `commit` return false even though the durable payload is already
            // gone. Only a still-present payload is a busy/error boundary; do not reject a normal
            // remove-and-retap merely because another path won the same claim race.
            if (stateRepository.read(PEN_NFC_SURFACE_ID).pendingCommit != null) {
                return NfcQuickLogResultState.PreviousTapBusy
            }
        }

        val receiptAt = now()
        val loaded = loadPenState() as? PenQuickLogState.Loaded
            ?: return NfcQuickLogResultState.NoCart
        val at = submissionDateTime(receiptAt)
        val payload = stateRepository.submitDirectCommit(PEN_NFC_SURFACE_ID) {
            PenWidgetCommitPayload(
                version = com.example.widget.PEN_WIDGET_PAYLOAD_VERSION,
                commitId = newId(),
                eventId = newId(),
                submittedAtEpochMillis = receiptAt,
                commitAtEpochMillis = receiptAt + com.example.widget.UNDO_WINDOW_MILLIS,
                productId = loaded.product.id,
                productUuid = loaded.product.productUuid,
                inputKind = DeferredPenInputKind.DIRECT_USES,
                seconds = null,
                secondsPerUse = null,
                restoreDraftSeconds = null,
                uses = tag.uses.toDouble(),
                date = at.date,
                time = at.time,
            )
        } ?: return NfcQuickLogResultState.PreviousTapBusy

        schedule(payload)
        return NfcQuickLogResultState.AwaitingUndo(
            commitId = payload.commitId,
            uses = tag.uses,
            cartName = loaded.product.name,
            submittedAtEpochMillis = receiptAt,
        )
    }

    suspend fun undo(commitId: String): Boolean {
        val undone = stateRepository.undo(PEN_NFC_SURFACE_ID, commitId, nowMillis = now())
        if (undone) {
            PenWidgetRuntime.cancelCommitTimer(PEN_NFC_SURFACE_ID)
            PenWidgetScheduler.cancelCommit(appContext, PEN_NFC_SURFACE_ID)
        }
        return undone
    }

    suspend fun submitNow(commitId: String): Boolean = runCatching {
        commitCoordinator.commit(
            context = appContext,
            appWidgetId = PEN_NFC_SURFACE_ID,
            commitId = commitId,
            nowMillis = now(),
            force = true,
        )
    }.getOrDefault(false)

    suspend fun pending(): PenWidgetCommitPayload? =
        stateRepository.read(PEN_NFC_SURFACE_ID).pendingCommit

    private fun schedule(payload: PenWidgetCommitPayload) {
        PenWidgetRuntime.scheduleCommitTimer(appContext, PEN_NFC_SURFACE_ID, payload.commitId)
        runCatching {
            PenWidgetScheduler.scheduleCommit(appContext, PEN_NFC_SURFACE_ID, payload.commitId)
        }
    }

    companion object {
        fun forContext(context: Context): NfcQuickLogCoordinator =
            NfcQuickLogCoordinator(context.applicationContext)
    }
}
