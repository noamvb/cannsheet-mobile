package com.example.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object PenWidgetScheduler {
    fun scheduleCommit(context: Context, appWidgetId: Int, commitId: String) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueWorkName(appWidgetId),
            ExistingWorkPolicy.REPLACE,
            commitRequest(appWidgetId, commitId),
        )
    }

    fun cancelCommit(context: Context, appWidgetId: Int) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(uniqueWorkName(appWidgetId))
    }

    internal fun uniqueWorkName(appWidgetId: Int): String =
        "cannsheet-pen-widget-commit-$appWidgetId"

    internal fun commitRequest(appWidgetId: Int, commitId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<PenWidgetCommitWorker>()
            .setInitialDelay(UNDO_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    INPUT_APP_WIDGET_ID to appWidgetId,
                    INPUT_COMMIT_ID to commitId,
                ),
            )
            .build()
}
