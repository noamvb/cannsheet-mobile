package com.example

import android.app.Application
import com.example.data.CannsheetGraph
import com.example.data.sync.SyncScheduler
import com.example.widget.PenWidgetCommitCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CannsheetApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val graph = CannsheetGraph.get(this)
        applicationScope.launch {
            PenWidgetCommitCoordinator.flushOverdue(this@CannsheetApplication, System.currentTimeMillis())
        }
        applicationScope.launch {
            graph.syncPreferences.preferences
                .map { preferences -> preferences.enabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        SyncScheduler.schedulePeriodic(this@CannsheetApplication)
                        if (graph.repository.hasPendingActions()) {
                            SyncScheduler.enqueueImmediate(this@CannsheetApplication)
                        }
                    } else {
                        SyncScheduler.cancel(this@CannsheetApplication)
                    }
                }
        }
    }
}
