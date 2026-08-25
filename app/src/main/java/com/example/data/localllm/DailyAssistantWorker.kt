package com.example.data.localllm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noamv.localllm.client.v2.AssistantClientV2
import com.noamv.localllm.client.v2.AssistantTurnEvent
import com.noamv.localllm.contract.v2.AggregateQuery
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.AssistantTurnRequest
import com.noamv.localllm.contract.v2.MetricId
import com.noamv.localllm.contract.v2.QueryComparison
import com.noamv.localllm.contract.v2.QueryPeriod
import com.noamv.localllm.contract.v2.QueryResultMode
import kotlinx.coroutines.flow.first
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
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastRunDate = prefs.getString("last_daily_run_date", null)
        if (lastRunDate == today) {
            return Result.success()
        }

        val query = AggregateQuery(
            grammarVersion = 1,
            sources = listOf(AppSource.CANNSHEET),
            metrics = listOf(MetricId.CANNSHEET_ACTIVE_DAYS, MetricId.CANNSHEET_RECORDED_SPEND, MetricId.CANNSHEET_CONSUMPTION_COUNT),
            period = QueryPeriod.LastDays(30),
            comparison = QueryComparison.PREVIOUS_EQUAL_PERIOD,
            resultMode = QueryResultMode.FACTS,
        )

        val request = AssistantTurnRequest(
            requestId = UUID.randomUUID().toString(),
            threadId = "daily_highlight_$today",
            initiatingClient = applicationContext.packageName,
            defaultSource = AppSource.CANNSHEET,
            maxSourcesAllowed = 1,
            allowCrossApp = false,
            fixedQuery = query,
        )

        try {
            val event = client.submitTurn(request).first { it is AssistantTurnEvent.Complete || it is AssistantTurnEvent.Error }
            if (event is AssistantTurnEvent.Complete && event.result.status == AssistantTerminalStatus.VALIDATED) {
                prefs.edit().putString("last_daily_run_date", today).apply()
            }
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
                .setRequiresCharging(true)
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
