package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.MainActivity
import com.example.ui.PenQuickLogState
import com.example.ui.currentSubmissionDateTime
import com.example.ui.secondsToUses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
                appWidgetIds.forEach { PenWidgetUpdater.update(appContext, it) }
            } finally {
                pendingResult.finish()
            }
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
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
                val state = PenWidgetStateRepository(appContext)
                appWidgetIds.forEach { state.clear(it) }
            } finally {
                pendingResult.finish()
            }
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
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
                handleAction(appContext, action, appWidgetId, intent)
                PenWidgetUpdater.update(appContext, appWidgetId)
            } finally {
                pendingResult.finish()
            }
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
                    PenWidgetScheduler.cancelCommit(context, appWidgetId)
                }
            }
            ACTION_OPEN_LOG -> openMainActivity(context, startRoute = "consumption")
            ACTION_OPEN_SETTINGS -> openMainActivity(context, startRoute = "settings")
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
        val draft = state.read(appWidgetId)
        if (draft.pendingCommit != null || draft.draftSeconds <= 0) return

        val now = System.currentTimeMillis()
        val at = currentSubmissionDateTime(now)
        val payload = PenWidgetCommitPayload(
            version = PEN_WIDGET_PAYLOAD_VERSION,
            commitId = UUID.randomUUID().toString(),
            commitAtEpochMillis = now + UNDO_WINDOW_MILLIS,
            productId = loaded.product.id,
            productUuid = loaded.product.productUuid,
            seconds = draft.draftSeconds,
            secondsPerUse = secondsPerUse,
            uses = secondsToUses(draft.draftSeconds.toDouble(), secondsPerUse),
            date = at.date,
            time = at.time,
        )
        if (state.submitCommit(appWidgetId, payload)) {
            PenWidgetScheduler.scheduleCommit(context, appWidgetId, payload.commitId)
        }
    }

    private fun openMainActivity(context: Context, startRoute: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_START_ROUTE, startRoute)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }

    private fun launchForWidget(
        context: Context,
        appWidgetId: Int,
        action: suspend (Context) -> Unit,
    ) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
                action(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
