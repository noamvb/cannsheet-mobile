package com.example.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val IMMEDIATE_SYNC_WORK_NAME = "cannsheet-sync-now"
    const val PERIODIC_SYNC_WORK_NAME = "cannsheet-sync-periodic"
    const val PREFETCH_ANALYTICS_KEY = "prefetch_analytics"

    fun enqueueImmediate(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            immediateRequest(),
        )
    }

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest(),
        )
    }

    /** Cancels only the two work chains owned by Cannsheet background synchronization. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork(IMMEDIATE_SYNC_WORK_NAME)
            cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
        }
    }

    internal fun immediateRequest() = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(networkConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        .build()

    internal fun periodicRequest() = PeriodicWorkRequestBuilder<SyncWorker>(
        PERIODIC_INTERVAL_HOURS,
        TimeUnit.HOURS,
        PERIODIC_FLEX_HOURS,
        TimeUnit.HOURS,
    )
        .setConstraints(networkConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        .setInputData(workDataOf(PREFETCH_ANALYTICS_KEY to true))
        .build()

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private const val BACKOFF_DELAY_SECONDS = 30L
    private const val PERIODIC_INTERVAL_HOURS = 6L
    private const val PERIODIC_FLEX_HOURS = 1L
}
