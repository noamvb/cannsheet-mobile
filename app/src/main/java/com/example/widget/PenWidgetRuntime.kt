package com.example.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class WidgetWorkSerializer {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

internal class PenWidgetCommitTimer<T>(
    private val scope: CoroutineScope,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val renderSaving: suspend (T, Int) -> Unit,
    private val commit: suspend (T, Int, String) -> Unit,
) {
    private val jobs = ConcurrentHashMap<Int, Job>()

    fun schedule(context: T, appWidgetId: Int, commitId: String) {
        cancel(appWidgetId)
        val job = scope.launch {
            wait(UNDO_WINDOW_MILLIS)
            runCatching { renderSaving(context, appWidgetId) }
            wait(COMMIT_GRACE_MILLIS)
            commit(context, appWidgetId, commitId)
        }
        jobs[appWidgetId] = job
        job.invokeOnCompletion { jobs.remove(appWidgetId, job) }
    }

    fun cancel(appWidgetId: Int) {
        jobs.remove(appWidgetId)?.cancel()
    }
}

/**
 * One process-wide widget execution boundary. Provider mutations and renders are serialized, while
 * the WorkManager path joins the same mutex before it claims a payload.
 */
object PenWidgetRuntime {
    private const val TAG = "PenWidget"
    private val exceptionHandler = CoroutineExceptionHandler { _, error -> logFailure(error) }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + exceptionHandler,
    )
    private val serializer = WidgetWorkSerializer()
    private val commitTimer = PenWidgetCommitTimer<Context>(
        scope = scope,
        renderSaving = { context, appWidgetId ->
            withSerialized { PenWidgetSurfaceRouter.refresh(context, appWidgetId) }
        },
        commit = { context, appWidgetId, commitId ->
            withSerialized {
                PenWidgetCommitCoordinator.commit(context, appWidgetId, commitId)
            }
        },
    )

    fun launchSerialized(block: suspend () -> Unit): Job = scope.launch {
        try {
            withSerialized(block)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logFailure(error)
        }
    }

    fun launchReceiver(
        pendingResult: BroadcastReceiver.PendingResult,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                withSerialized(block)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logFailure(error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    suspend fun <T> withSerialized(block: suspend () -> T): T = serializer.run(block)

    fun scheduleCommitTimer(context: Context, appWidgetId: Int, commitId: String) {
        commitTimer.schedule(context.applicationContext, appWidgetId, commitId)
    }

    fun cancelCommitTimer(appWidgetId: Int) {
        commitTimer.cancel(appWidgetId)
    }

    private fun logFailure(error: Throwable) {
        Log.e(TAG, "Widget operation failed", error)
    }
}
