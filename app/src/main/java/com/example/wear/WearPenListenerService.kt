package com.example.wear

import android.content.Context
import com.example.widget.PenQuickTileService
import com.example.widget.PenWidgetRuntime
import com.example.widget.togglePenTile
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Receives watch taps. Play services delivers messages on a background thread and may let a cold
 * process die as soon as this callback returns, so the callback waits for the serialized work to
 * finish: by then the pending payload is in DataStore and the WorkManager backstop is scheduled.
 */
class WearPenListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val ctx: Context = applicationContext
        val work = when (event.path) {
            WEAR_PEN_TAP_PATH -> PenWidgetRuntime.launchSerialized {
                togglePenTile(ctx)
                WearPenStatePublisher.publish(ctx)
                PenQuickTileService.requestRefresh(ctx)
            }
            WEAR_PEN_REFRESH_PATH -> PenWidgetRuntime.launchSerialized {
                WearPenStatePublisher.publish(ctx)
            }
            else -> return
        }
        runBlocking { work.join() }
    }
}
