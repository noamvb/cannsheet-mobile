package com.example.widget

import android.content.ComponentName
import android.service.quicksettings.TileService
import com.example.wear.WearPenStatePublisher
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
            togglePenTile(appContext)
            WearPenStatePublisher.publish(appContext)
            refreshTileFromState(appContext)
        }
    }

    private fun refreshTile() {
        val appContext = applicationContext
        PenWidgetRuntime.launchSerialized {
            refreshTileFromState(appContext)
        }
    }

    private suspend fun refreshTileFromState(context: android.content.Context) {
        val model = currentPenTileModel(context)
        applyTile(model.label.resolve(context), model.state)
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
