package com.example.widget

import android.content.Context
import com.example.data.WidgetRefresher

class PenWidgetRefresher(context: Context) : WidgetRefresher {
    private val appContext = context.applicationContext

    override fun refreshAll() {
        runCatching { PenWidgetUpdater.updateAll(appContext) }
    }
}
