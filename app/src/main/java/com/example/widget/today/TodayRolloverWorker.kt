package com.example.widget.today

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Fires once at local midnight, refreshes every today widget, and immediately re-arms
 * [TodayRolloverScheduler] for the following day. A failed render must not end the daily rollover
 * chain permanently, so the refresh is caught and the re-arm always runs.
 *
 * Re-arming from inside the worker, rather than using periodic work, is deliberate: each run
 * recomputes the delay to the next local midnight, so the schedule cannot drift away from midnight
 * the way a fixed 24-hour period does after a Doze deferral.
 */
class TodayRolloverWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            TodayUpdater.updateAllSuspending(applicationContext)
        } catch (error: CancellationException) {
            // Never swallow cancellation: WorkManager stopping this worker must propagate, and
            // the re-arm below would otherwise run against a cancelled job.
            throw error
        } catch (_: Throwable) {
            // A failed render is recoverable; the re-arm below keeps tomorrow's rollover alive.
        }
        TodayRolloverScheduler.scheduleNext(applicationContext)
        return Result.success()
    }
}
