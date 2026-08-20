package com.example.widget

import android.content.Context
import com.example.data.WidgetRefresher
import com.example.widget.sync.SyncStatusUpdater

class CannsheetWidgetRefresher(context: Context) : WidgetRefresher {
    private val appContext = context.applicationContext

    override fun refreshAll() {
        runCatching { PenWidgetUpdater.updateAll(appContext) }
        runCatching { SyncStatusUpdater.updateAll(appContext) }
    }
}
