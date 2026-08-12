package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.domain.PenQuickLogState
import com.example.domain.currentSubmissionDateTime
import com.example.domain.secondsToUses
import java.util.UUID

class PenConsumptionWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        PenWidgetRuntime.launchReceiver(pendingResult) {
            PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
            appWidgetIds.forEach { PenWidgetUpdater.update(appContext, it) }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        launchForWidget(context, appWidgetId) {
            PenWidgetUpdater.update(it, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        PenWidgetRuntime.launchReceiver(pendingResult) {
            val state = PenWidgetStateRepository(appContext)
            var firstFailure: Throwable? = null
            appWidgetIds.forEach { appWidgetId ->
                val commitResult = runCatching {
                    PenWidgetCommitCoordinator.commit(
                        context = appContext,
                        appWidgetId = appWidgetId,
                        commitId = null,
                        force = true,
                    )
                }
                commitResult.exceptionOrNull()?.let { error ->
                    if (firstFailure == null) firstFailure = error
                }
                val stateReadResult = runCatching { state.read(appWidgetId).pendingCommit }
                stateReadResult.exceptionOrNull()?.let { error ->
                    if (firstFailure == null) firstFailure = error
                }
                if (stateReadResult.isSuccess && stateReadResult.getOrNull() == null) {
                    PenWidgetRuntime.cancelCommitTimer(appWidgetId)
                    PenWidgetScheduler.cancelCommit(appContext, appWidgetId)
                    state.clear(appWidgetId)
                }
            }
            firstFailure?.let { throw it }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val appWidgetId = intent.getIntExtra(EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (action == null ||
            !HANDLED_ACTIONS.contains(action) ||
            appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID
        ) {
            super.onReceive(context, intent)
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        PenWidgetRuntime.launchReceiver(pendingResult) {
            PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
            handleAction(appContext, action, appWidgetId, intent)
            PenWidgetUpdater.update(appContext, appWidgetId)
        }
    }

    private suspend fun handleAction(
        context: Context,
        action: String?,
        appWidgetId: Int,
        intent: Intent,
    ) {
        val state = PenWidgetStateRepository(context)
        when (action) {
            ACTION_DECREMENT -> state.adjustDraftSeconds(appWidgetId, -STEP_SECONDS)
            ACTION_INCREMENT -> state.adjustDraftSeconds(appWidgetId, STEP_SECONDS)
            ACTION_RESET -> state.resetDraftSeconds(appWidgetId)
            ACTION_SUBMIT -> submit(context, appWidgetId, state)
            ACTION_UNDO -> {
                val commitId = intent.getStringExtra(EXTRA_COMMIT_ID) ?: return
                if (state.undo(appWidgetId, commitId)) {
                    PenWidgetRuntime.cancelCommitTimer(appWidgetId)
                    PenWidgetScheduler.cancelCommit(context, appWidgetId)
                }
            }
        }
    }

    private suspend fun submit(
        context: Context,
        appWidgetId: Int,
        state: PenWidgetStateRepository,
    ) {
        val penState = PenWidgetDataSource.loadPenState(context)
        val loaded = penState as? PenQuickLogState.Loaded ?: return
        val secondsPerUse = loaded.secondsPerUse ?: return

        val now = System.currentTimeMillis()
        val at = currentSubmissionDateTime(now)
        val commitId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val payload = state.submitCommit(appWidgetId) { seconds ->
            PenWidgetCommitPayload(
                version = PEN_WIDGET_PAYLOAD_VERSION,
                commitId = commitId,
                eventId = eventId,
                submittedAtEpochMillis = now,
                commitAtEpochMillis = now + COMMIT_DELAY_MILLIS,
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
            PenWidgetRuntime.scheduleCommitTimer(context, appWidgetId, payload.commitId)
            // The timer is the primary path. Failure to enqueue its durable backstop must not
            // suppress in-process delivery; lazy overdue flushing remains the final recovery tier.
            runCatching { PenWidgetScheduler.scheduleCommit(context, appWidgetId, payload.commitId) }
        }
    }

    private fun launchForWidget(
        context: Context,
        appWidgetId: Int,
        action: suspend (Context) -> Unit,
    ) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        PenWidgetRuntime.launchReceiver(pendingResult) {
            PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
            action(appContext)
        }
    }
}
