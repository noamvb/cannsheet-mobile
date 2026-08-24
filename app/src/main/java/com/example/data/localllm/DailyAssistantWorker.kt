package com.example.data.localllm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noamv.localllm.client.v2.AssistantClientV2
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantTurnRequest
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DailyAssistantWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val client = AssistantClientV2(applicationContext)
        if (!client.isInstalled()) {
            return Result.success()
        }

        val prefs = applicationContext.getSharedPreferences("assistant_daily_prefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lastRunDate = prefs.getString("last_daily_run_date", null)
        if (lastRunDate == today) {
            return Result.success()
        }

        val request = AssistantTurnRequest(
            requestId = UUID.randomUUID().toString(),
            threadId = "daily_highlight_$today",
            initiatingClient = applicationContext.packageName,
            question = "Provide a concise summary of my recent activity and spend highlights.",
            defaultSource = AppSource.CANNSHEET,
            maxSourcesAllowed = 1,
            allowCrossApp = false,
        )

        try {
            client.submitTurn(request).firstOrNull()
            prefs.edit().putString("last_daily_run_date", today).apply()
            return Result.success()
        } catch (_: Exception) {
            return Result.success() // advisory only, does not fail WorkManager
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "daily_assistant_highlight"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<DailyAssistantWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
