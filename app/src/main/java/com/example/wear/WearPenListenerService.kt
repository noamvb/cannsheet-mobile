package com.example.wear

import android.content.Context
import com.example.widget.PenQuickTileService
import com.example.widget.PenWidgetRuntime
import com.example.widget.togglePenTile
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearPenListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val ctx: Context = applicationContext
        when (event.path) {
            WEAR_PEN_TAP_PATH -> PenWidgetRuntime.launchSerialized {
                togglePenTile(ctx)
                WearPenStatePublisher.publish(ctx)
                PenQuickTileService.requestRefresh(ctx)
            }
            WEAR_PEN_REFRESH_PATH -> PenWidgetRuntime.launchSerialized {
                WearPenStatePublisher.publish(ctx)
            }
        }
    }
}
