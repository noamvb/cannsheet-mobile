package com.example.widget

import android.content.ComponentName
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.R
import com.example.domain.PenQuickLogState
import java.lang.ref.WeakReference

/**
 * Quick Settings entry point for the same deferred, undoable pen commit used by the home-screen
 * widget. All DataStore reads and writes run through the process-wide widget serializer so a tile
 * tap cannot race the timer or WorkManager backstop.
 */
class PenQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        activeService = WeakReference(this)
        refreshTile()
    }

    override fun onStopListening() {
        if (activeService?.get() === this) activeService = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val appContext = applicationContext
        PenWidgetRuntime.launchSerialized {
            val state = PenWidgetStateRepository(appContext)
            val pending = state.read(PEN_TILE_WIDGET_ID).pendingCommit
            if (pending != null) {
                if (state.undo(PEN_TILE_WIDGET_ID, pending.commitId)) {
                    PenWidgetRuntime.cancelCommitTimer(PEN_TILE_WIDGET_ID)
                    PenWidgetScheduler.cancelCommit(appContext, PEN_TILE_WIDGET_ID)
                }
            } else {
                submitDefaultPreset(appContext, state)
            }
            refreshTileFromState(appContext, state)
        }
    }

    private fun refreshTile() {
        val appContext = applicationContext
        PenWidgetRuntime.launchSerialized {
            refreshTileFromState(appContext, PenWidgetStateRepository(appContext))
        }
    }

    private suspend fun refreshTileFromState(
        context: android.content.Context,
        state: PenWidgetStateRepository,
    ) {
        val stored = state.read(PEN_TILE_WIDGET_ID)
        val config = state.readConfig(PEN_TILE_WIDGET_ID)
        val penState = PenWidgetDataSource.loadPenState(context, config.pinnedProductId)
        val model = penTileState(penState, stored.pendingCommit)
        val label = when (model.state) {
            Tile.STATE_ACTIVE -> getString(R.string.pen_tile_undo)
            Tile.STATE_UNAVAILABLE -> getString(R.string.pen_tile_no_cart)
            else -> model.label
        }
        applyTile(label, model.state)
    }

    private suspend fun submitDefaultPreset(
        context: android.content.Context,
        state: PenWidgetStateRepository,
    ) {
        val config = state.readConfig(PEN_TILE_WIDGET_ID)
        val loaded = PenWidgetDataSource.loadPenState(context, config.pinnedProductId)
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

    private fun applyTile(label: String, state: Int) {
        qsTile?.apply {
            this.label = label
            this.state = state
            updateTile()
        }
    }

    companion object {
        @Volatile
        private var activeService: WeakReference<PenQuickTileService>? = null

        /** Ask SystemUI to rebind the tile so a completed deferred commit is reflected promptly. */
        fun requestRefresh(context: android.content.Context) {
            activeService?.get()?.refreshTile()
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context, PenQuickTileService::class.java),
            )
        }
    }
}
