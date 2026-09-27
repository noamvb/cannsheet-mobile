package com.example.widget

import android.content.Context
import com.example.domain.PenQuickLogState

/** Toggle the shared Quick Settings/watch pen surface. Caller must hold the widget serializer. */
internal suspend fun togglePenTile(context: Context) {
    val state = PenWidgetStateRepository(context)
    val config = PenWidgetConfigRepository(context)
    val pending = state.read(PEN_TILE_WIDGET_ID).pendingCommit
    if (pending != null) {
        if (state.undo(PEN_TILE_WIDGET_ID, pending.commitId)) {
            PenWidgetRuntime.cancelCommitTimer(PEN_TILE_WIDGET_ID)
            PenWidgetScheduler.cancelCommit(context, PEN_TILE_WIDGET_ID)
        }
    } else {
        submitDefaultPreset(context, state, config)
    }
}

/** Compute the model rendered by the Quick Settings/watch pen surface. Caller holds serializer. */
internal suspend fun currentPenTileModel(context: Context): PenTileModel {
    val state = PenWidgetStateRepository(context)
    val config = PenWidgetConfigRepository(context)
    val stored = state.read(PEN_TILE_WIDGET_ID)
    val instanceConfig = config.read(PEN_TILE_WIDGET_ID)
    val penState = PenWidgetDataSource.loadPenState(context, instanceConfig.pinnedProductId)
    return penTileState(penState, stored.pendingCommit)
}

internal fun PenWidgetText.resolve(context: Context): String = when (this) {
    is PenWidgetText.Literal -> value
    is PenWidgetText.Resource -> context.getString(resourceId, *arguments.toTypedArray())
}

private suspend fun submitDefaultPreset(
    context: Context,
    state: PenWidgetStateRepository,
    config: PenWidgetConfigRepository,
) {
    val instanceConfig = config.read(PEN_TILE_WIDGET_ID)
    val loaded = PenWidgetDataSource.loadPenState(context, instanceConfig.pinnedProductId)
        as? PenQuickLogState.Loaded
        ?: return
    if (loaded.secondsPerUse == null) return

    val seconds = penWidgetPresetSeconds(loaded).firstOrNull() ?: STEP_SECONDS
    state.setDraftSeconds(PEN_TILE_WIDGET_ID, seconds)
    val payload = submitPenLog(
        context = context,
        appWidgetId = PEN_TILE_WIDGET_ID,
        seconds = seconds,
        penState = loaded,
        stateRepository = state,
    ) ?: return

    PenWidgetRuntime.scheduleCommitTimer(context, PEN_TILE_WIDGET_ID, payload.commitId)
    // The timer is the primary path. The durable worker remains the recovery path after
    // process death and must not be allowed to suppress the timer if enqueueing fails.
    runCatching {
        PenWidgetScheduler.scheduleCommit(context, PEN_TILE_WIDGET_ID, payload.commitId)
    }
}
