package com.example.widget.today

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Arms a one-shot [TodayRolloverWorker] job timed to the next local midnight, so the today widget
 * rolls its total, comparison, and streak over even if nothing else wakes the app.
 *
 * This exists because `android.intent.action.DATE_CHANGED` is not on Android's implicit-broadcast
 * exception list on API 26+, so a manifest-declared receiver for it is never delivered — only
 * resolvable, which is not the same as delivered. `TIME_SET` and `TIMEZONE_CHANGED` are exempt and
 * are still handled directly by [TodayWidgetProvider.onReceive], which re-arms this schedule
 * afterward since either broadcast can move when midnight falls.
 */
object TodayRolloverScheduler {
    const val ROLLOVER_WORK_NAME = "cannsheet-today-widget-rollover"

    fun scheduleNext(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val request = OneTimeWorkRequestBuilder<TodayRolloverWorker>()
            .setInitialDelay(millisUntilNextMidnight(nowMillis), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ROLLOVER_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(ROLLOVER_WORK_NAME)
    }

    /**
     * Re-arms the schedule on app start, but only when a today widget actually exists, so users
     * without one pay nothing.
     *
     * [TodayRolloverWorker] re-arms itself, which is what keeps the schedule anchored to midnight.
     * That self-replacement is the only link in the chain with no external trigger behind it, and
     * `updatePeriodMillis` is 0 for this widget, so nothing else would notice if it ever failed.
     * This makes any lost chain recover on the next app launch instead of staying dead until the
     * user re-adds the widget.
     */
    fun scheduleIfWidgetsExist(context: Context) {
        val appContext = context.applicationContext
        val appWidgetIds = AppWidgetManager.getInstance(appContext)
            .getAppWidgetIds(ComponentName(appContext, TodayWidgetProvider::class.java))
        if (appWidgetIds.isEmpty()) return
        scheduleNext(appContext)
    }

    /** Milliseconds from [nowMillis] until the next local midnight. Always > 0. */
    internal fun millisUntilNextMidnight(
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val nextMidnight = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        var delayMillis = nextMidnight.timeInMillis - nowMillis
        if (delayMillis <= 0L) {
            // Possible around a DST transition; push out one more day so the delay stays positive.
            nextMidnight.add(Calendar.DAY_OF_YEAR, 1)
            delayMillis = nextMidnight.timeInMillis - nowMillis
        }
        return delayMillis
    }
}
