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
    private val loadPenState: suspend (Context, String?) -> PenQuickLogState = {
        context, pinnedProductId ->
        PenWidgetDataSource.loadPenState(context, pinnedProductId)
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
                val pinnedProductId = state.readConfig(appWidgetId).pinnedProductId
                val penState = loadPenState(context, pinnedProductId) as? PenQuickLogState.Loaded
                    ?: return
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
        val pinnedProductId = state.readConfig(appWidgetId).pinnedProductId
        val loaded = loadPenState(context, pinnedProductId) as? PenQuickLogState.Loaded ?: return
        val seconds = state.read(appWidgetId).draftSeconds
        val payload = submitPenLog(
            context = context,
            appWidgetId = appWidgetId,
            seconds = seconds,
            penState = loaded,
            now = now,
            newId = newId,
            stateRepository = state,
            submissionDateTime = submissionDateTime,
        )
        if (payload != null) {
            scheduleTimer(context, appWidgetId, payload.commitId)
            // The timer is the primary path. Failure to enqueue its durable backstop must not
            // suppress in-process delivery; lazy overdue flushing remains the final recovery tier.
            runCatching { scheduleWork(context, appWidgetId, payload.commitId) }
        }
    }
}

/**
 * Captures the draft through the same atomic DataStore edit used by the widget submit action and
 * builds the immutable deferred payload for either a widget or the Quick Settings tile.
 *
 * [seconds] is the requested value already placed in the draft by the caller. The edit still
 * supplies the authoritative captured value to the payload builder; if another caller changed it
 * between the read and this call, returning null preserves the single-source-of-truth guarantee
 * instead of silently logging a different duration.
 */
internal suspend fun submitPenLog(
    context: Context,
    appWidgetId: Int,
    seconds: Int,
    penState: PenQuickLogState.Loaded,
    now: () -> Long = System::currentTimeMillis,
    newId: () -> String = { UUID.randomUUID().toString() },
    stateRepository: PenWidgetStateRepository = PenWidgetStateRepository(context),
    submissionDateTime: (Long) -> SubmissionDateTime = ::currentSubmissionDateTime,
): PenWidgetCommitPayload? {
    val secondsPerUse = penState.secondsPerUse ?: return null
    val submittedAt = now()
    val at = submissionDateTime(submittedAt)
    val commitId = newId()
    val eventId = newId()
    return stateRepository.submitCommit(appWidgetId) { capturedSeconds ->
        if (capturedSeconds != seconds) {
            null
        } else {
            PenWidgetCommitPayload(
                version = PEN_WIDGET_PAYLOAD_VERSION,
                commitId = commitId,
                eventId = eventId,
                submittedAtEpochMillis = submittedAt,
                commitAtEpochMillis = submittedAt + COMMIT_DELAY_MILLIS,
                productId = penState.product.id,
                productUuid = penState.product.productUuid,
                seconds = capturedSeconds,
                secondsPerUse = secondsPerUse,
                uses = secondsToUses(capturedSeconds.toDouble(), secondsPerUse),
                date = at.date,
                time = at.time,
            )
        }
    }
}
