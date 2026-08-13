package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class PenConsumptionWidgetProvider : AppWidgetProvider() {
    private val router = PenWidgetActionRouter()

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
            router.handle(appContext, action, appWidgetId, intent)
            PenWidgetUpdater.update(appContext, appWidgetId)
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
