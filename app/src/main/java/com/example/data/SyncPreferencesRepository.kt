package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal const val SYNC_PREFERENCES_DATA_STORE_NAME = "sync_preferences"
internal const val BACKGROUND_SYNC_ENABLED_KEY = "background_sync_enabled"
internal const val LAST_BACKGROUND_SYNC_EPOCH_MILLIS_KEY = "last_background_sync_epoch_millis"
internal const val LAST_BACKGROUND_SYNC_RESULT_KEY = "last_background_sync_result"

private val Context.syncPreferencesDataStore by preferencesDataStore(
    name = SYNC_PREFERENCES_DATA_STORE_NAME,
)

/**
 * The terminal outcome worth showing to a person. Retryable failures deliberately do not
 * overwrite this value: WorkManager may still repair those without user action.
 */
enum class BackgroundSyncResult {
    SUCCESS,
    PARTIAL_REJECTIONS,
    BACKEND_CAPABILITY_PENDING,
    COMPLETED_WITHOUT_ACK,
    RETRY_EXHAUSTED,
    ENVIRONMENT_MISMATCH,
}

data class SyncPreferences(
    val enabled: Boolean = true,
    val lastMeaningfulSyncAtEpochMillis: Long? = null,
    val lastResult: BackgroundSyncResult? = null,
)

class SyncPreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.syncPreferencesDataStore

    val preferences: Flow<SyncPreferences> = dataStore.data
        .map { stored ->
            SyncPreferences(
                enabled = stored[ENABLED] ?: true,
                lastMeaningfulSyncAtEpochMillis = stored[LAST_MEANINGFUL_SYNC_AT_EPOCH_MILLIS],
                lastResult = stored[LAST_RESULT]
                    ?.let { storedResult -> BackgroundSyncResult.entries.firstOrNull { it.name == storedResult } },
            )
        }
        .distinctUntilChanged()

    suspend fun isEnabled(): Boolean = preferences.first().enabled

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { stored -> stored[ENABLED] = enabled }
    }

    suspend fun recordMeaningfulResult(
        result: BackgroundSyncResult,
        completedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        dataStore.edit { stored ->
            stored[LAST_MEANINGFUL_SYNC_AT_EPOCH_MILLIS] = completedAtEpochMillis
            stored[LAST_RESULT] = result.name
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey(BACKGROUND_SYNC_ENABLED_KEY)
        val LAST_MEANINGFUL_SYNC_AT_EPOCH_MILLIS =
            longPreferencesKey(LAST_BACKGROUND_SYNC_EPOCH_MILLIS_KEY)
        val LAST_RESULT = stringPreferencesKey(LAST_BACKGROUND_SYNC_RESULT_KEY)
    }
}
