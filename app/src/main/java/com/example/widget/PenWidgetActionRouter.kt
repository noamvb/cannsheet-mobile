package com.example.widget

import android.content.Context
import android.content.Intent
import com.example.domain.PenQuickLogState
import com.example.domain.SubmissionDateTime
import com.example.domain.currentSubmissionDateTime
import com.example.domain.secondsToUses
import java.util.UUID

/**
 * Action routing for [PenConsumptionWidgetProvider], separated so it can be exercised without a
 * live broadcast dispatch. Collaborators are constructor parameters purely so tests can substitute
 * them; production uses the defaults.
 */
internal class PenWidgetActionRouter(
    private val stateRepository: (Context) -> PenWidgetStateRepository = {
        PenWidgetStateRepository(it)
    },
    private val loadPenState: suspend (Context) -> PenQuickLogState = {
        PenWidgetDataSource.loadPenState(it)
    },
    private val scheduleTimer: (Context, Int, String) -> Unit = PenWidgetRuntime::scheduleCommitTimer,
    private val cancelTimer: (Int) -> Unit = PenWidgetRuntime::cancelCommitTimer,
    private val scheduleWork: (Context, Int, String) -> Unit = PenWidgetScheduler::scheduleCommit,
    private val cancelWork: (Context, Int) -> Unit = PenWidgetScheduler::cancelCommit,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val submissionDateTime: (Long) -> SubmissionDateTime = ::currentSubmissionDateTime,
) {
    suspend fun handle(context: Context, action: String, appWidgetId: Int, intent: Intent) {
        val state = stateRepository(context)
        val step = intent.getIntExtra(EXTRA_STEP_SECONDS, STEP_SECONDS)
            .coerceIn(1, MAX_SECONDS)
        when (action) {
            ACTION_DECREMENT -> state.adjustDraftSeconds(appWidgetId, -step)
            ACTION_INCREMENT -> state.adjustDraftSeconds(appWidgetId, step)
            ACTION_RESET -> state.resetDraftSeconds(appWidgetId)
            in PRESET_ACTIONS -> {
                val index = PRESET_ACTIONS.indexOf(action)
                val penState = loadPenState(context) as? PenQuickLogState.Loaded ?: return
                val seconds = penWidgetPresetSeconds(penState).getOrNull(index) ?: return
                state.setDraftSeconds(appWidgetId, seconds)
            }
            ACTION_SUBMIT -> submit(context, appWidgetId, state)
            ACTION_UNDO -> {
                val commitId = intent.getStringExtra(EXTRA_COMMIT_ID) ?: return
                if (state.undo(appWidgetId, commitId, nowMillis = now())) {
                    cancelTimer(appWidgetId)
                    cancelWork(context, appWidgetId)
                }
            }
        }
    }

    private suspend fun submit(
        context: Context,
        appWidgetId: Int,
        state: PenWidgetStateRepository,
    ) {
        val penState = loadPenState(context)
        val loaded = penState as? PenQuickLogState.Loaded ?: return
        val secondsPerUse = loaded.secondsPerUse ?: return

        val submittedAt = now()
        val at = submissionDateTime(submittedAt)
        val commitId = newId()
        val eventId = newId()
        val payload = state.submitCommit(appWidgetId) { seconds ->
            PenWidgetCommitPayload(
                version = PEN_WIDGET_PAYLOAD_VERSION,
                commitId = commitId,
                eventId = eventId,
                submittedAtEpochMillis = submittedAt,
                commitAtEpochMillis = submittedAt + COMMIT_DELAY_MILLIS,
                productId = loaded.product.id,
                productUuid = loaded.product.productUuid,
                seconds = seconds,
                secondsPerUse = secondsPerUse,
                uses = secondsToUses(seconds.toDouble(), secondsPerUse),
                date = at.date,
                time = at.time,
            )
        }
        if (payload != null) {
            scheduleTimer(context, appWidgetId, payload.commitId)
            // The timer is the primary path. Failure to enqueue its durable backstop must not
            // suppress in-process delivery; lazy overdue flushing remains the final recovery tier.
            runCatching { scheduleWork(context, appWidgetId, payload.commitId) }
        }
    }
}
