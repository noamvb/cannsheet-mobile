package com.example.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class PenWidgetCommitWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val appWidgetId = inputData.getInt(INPUT_APP_WIDGET_ID, -1)
        val commitId = inputData.getString(INPUT_COMMIT_ID)
        if (appWidgetId >= 0 && !commitId.isNullOrBlank()) {
            PenWidgetRuntime.withSerialized {
                PenWidgetCommitCoordinator.commit(applicationContext, appWidgetId, commitId)
            }
        }
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
