package com.example.widget

import android.content.Context
import com.example.data.WidgetRefresher
import com.example.widget.multi.MultiCartUpdater
import com.example.widget.projection.ProjectionWidgetUpdater
import com.example.widget.sync.SyncStatusUpdater
import com.example.widget.today.TodayUpdater

class CannsheetWidgetRefresher(context: Context) : WidgetRefresher {
    private val appContext = context.applicationContext

    override fun refreshAll() {
        runCatching { PenWidgetUpdater.updateAll(appContext) }
        runCatching { SyncStatusUpdater.updateAll(appContext) }
        runCatching { MultiCartUpdater.updateAll(appContext) }
        runCatching { TodayUpdater.updateAll(appContext) }
        runCatching { ProjectionWidgetUpdater.updateAll(appContext) }
    }
}
