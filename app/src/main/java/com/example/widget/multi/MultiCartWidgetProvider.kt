package com.example.widget.multi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.data.CannsheetGraph
import com.example.domain.currentSubmissionDateTime
import com.example.domain.secondsToUses
import com.example.widget.ACTION_OPEN_LOG
import com.example.widget.ACTION_UNDO
import com.example.widget.COMMIT_DELAY_MILLIS
import com.example.widget.EXTRA_APP_WIDGET_ID
import com.example.widget.EXTRA_COMMIT_ID
import com.example.widget.PEN_WIDGET_PAYLOAD_VERSION
import com.example.widget.PenWidgetCommitCoordinator
import com.example.widget.PenWidgetCommitPayload
import com.example.widget.PenWidgetRuntime
import com.example.widget.PenWidgetScheduler
import com.example.widget.PenWidgetStateRepository
import java.util.UUID
import kotlinx.coroutines.flow.first

const val ACTION_CART_1 = "com.noamv.cannsheet.mobile.widget.CART_1"
const val ACTION_CART_2 = "com.noamv.cannsheet.mobile.widget.CART_2"
const val ACTION_CART_3 = "com.noamv.cannsheet.mobile.widget.CART_3"
const val ACTION_CART_4 = "com.noamv.cannsheet.mobile.widget.CART_4"

val CART_ACTIONS: List<String> = listOf(
    ACTION_CART_1,
    ACTION_CART_2,
    ACTION_CART_3,
    ACTION_CART_4,
)

private val HANDLED_ACTIONS: Set<String> = CART_ACTIONS.toSet() + ACTION_UNDO

class MultiCartWidgetProvider : AppWidgetProvider() {
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
            appWidgetIds.forEach { MultiCartUpdater.update(appContext, it) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val appWidgetId = intent.getIntExtra(EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (action == null ||
            action !in HANDLED_ACTIONS ||
            appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID
        ) {
            super.onReceive(context, intent)
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        PenWidgetRuntime.launchReceiver(pendingResult) {
            PenWidgetCommitCoordinator.flushOverdue(appContext, System.currentTimeMillis())
            when (action) {
                ACTION_UNDO -> undo(appContext, appWidgetId, intent)
                else -> submitCart(appContext, appWidgetId, CART_ACTIONS.indexOf(action))
            }
            MultiCartUpdater.update(appContext, appWidgetId)
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

    private suspend fun undo(context: Context, appWidgetId: Int, intent: Intent) {
        val commitId = intent.getStringExtra(EXTRA_COMMIT_ID) ?: return
        val state = PenWidgetStateRepository(context)
        if (state.undo(appWidgetId, commitId, nowMillis = System.currentTimeMillis())) {
            PenWidgetRuntime.cancelCommitTimer(appWidgetId)
            PenWidgetScheduler.cancelCommit(context, appWidgetId)
        }
    }

    private suspend fun submitCart(context: Context, appWidgetId: Int, index: Int) {
        val graph = CannsheetGraph.get(context)
        val preferences = graph.consumptionPreferences.preferences.first()
        val state = PenWidgetStateRepository(context)
        val model = buildMultiCartUiModel(
            products = graph.repository.allProducts.first(),
            interactions = graph.repository.productInteractions.first(),
            globalPresets = preferences.quantityPresets,
            presetOverrides = preferences.quantityPresetOverrides,
            secondsPerUseOverrides = preferences.secondsPerUseOverrides,
            pending = state.read(appWidgetId).pendingCommit,
        )
        val entry = model.entries.getOrNull(index) ?: return

        state.setDraftSeconds(appWidgetId, entry.seconds)
        val submittedAt = System.currentTimeMillis()
        val at = currentSubmissionDateTime(submittedAt)
        val commitId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val payload = state.submitCommit(appWidgetId) { seconds ->
            PenWidgetCommitPayload(
                version = PEN_WIDGET_PAYLOAD_VERSION,
                commitId = commitId,
                eventId = eventId,
                submittedAtEpochMillis = submittedAt,
                commitAtEpochMillis = submittedAt + COMMIT_DELAY_MILLIS,
                productId = entry.productId,
                productUuid = entry.productUuid,
                seconds = seconds,
                secondsPerUse = entry.secondsPerUse,
                uses = secondsToUses(seconds.toDouble(), entry.secondsPerUse),
                date = at.date,
                time = at.time,
            )
        }
        if (payload != null) {
            PenWidgetRuntime.scheduleCommitTimer(context, appWidgetId, payload.commitId)
            // The timer is the primary path. Failure to enqueue its durable backstop must not
            // suppress in-process delivery; lazy overdue flushing remains the final recovery tier.
            runCatching { PenWidgetScheduler.scheduleCommit(context, appWidgetId, payload.commitId) }
        }
    }
}

internal fun multiCartPendingIntent(
    context: Context,
    appWidgetId: Int,
    action: String,
    commitId: String? = null,
): PendingIntent {
    val requestCode = 31 * appWidgetId + action.hashCode()
    if (action == ACTION_OPEN_LOG) {
        return com.example.widget.pendingIntent(context, appWidgetId, action)
    }

    val intent = Intent(context, MultiCartWidgetProvider::class.java).apply {
        this.action = action
        putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
        commitId?.let { putExtra(EXTRA_COMMIT_ID, it) }
        data = Uri.parse("cannsheet://multi-cart-widget/$appWidgetId/$action")
    }
    return PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
