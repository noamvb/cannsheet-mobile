package com.example.widget.projection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.projectionWidgetConfigurationDataStore by preferencesDataStore(
    name = ProjectionWidgetConfiguration.DATASTORE_NAME,
)

/** Durable per-instance mode state, including the Android restore remapping boundary. */
class ProjectionWidgetStateRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.projectionWidgetConfigurationDataStore)

    suspend fun readMode(appWidgetId: Int): String? {
        requireValidWidgetId(appWidgetId)
        return dataStore.data.first()[ProjectionWidgetConfiguration.modeKey(appWidgetId)]
    }

    suspend fun writeMode(appWidgetId: Int, mode: String) {
        requireValidWidgetId(appWidgetId)
        require(mode == ProjectionWidgetConfiguration.MODE_RUNWAY || mode == ProjectionWidgetConfiguration.MODE_SPEND) {
            "Unknown projection widget mode: $mode"
        }
        dataStore.edit { preferences ->
            preferences[ProjectionWidgetConfiguration.modeKey(appWidgetId)] = mode
        }
    }

    suspend fun clear(appWidgetIds: IntArray) {
        appWidgetIds.forEach(::requireValidWidgetId)
        dataStore.edit { preferences ->
            appWidgetIds.forEach { appWidgetId ->
                preferences.remove(ProjectionWidgetConfiguration.modeKey(appWidgetId))
            }
        }
    }

    /** Moves all per-widget mode keys in one DataStore edit so overlapping restore IDs are safe. */
    suspend fun remapWidgetIds(oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        require(oldWidgetIds.size == newWidgetIds.size) {
            "Restored widget id arrays must be the same length."
        }
        oldWidgetIds.forEach(::requireValidWidgetId)
        newWidgetIds.forEach(::requireValidWidgetId)
        dataStore.edit { preferences ->
            val snapshot = oldWidgetIds.mapIndexed { index, oldId ->
                newWidgetIds[index] to preferences[ProjectionWidgetConfiguration.modeKey(oldId)]
            }
            oldWidgetIds.forEach { oldId ->
                preferences.remove(ProjectionWidgetConfiguration.modeKey(oldId))
            }
            snapshot.forEach { (newId, mode) ->
                mode?.let { preferences[ProjectionWidgetConfiguration.modeKey(newId)] = it }
            }
        }
    }

    private fun requireValidWidgetId(appWidgetId: Int) {
        require(appWidgetId >= 0) { "Widget ID must be non-negative." }
    }
}
