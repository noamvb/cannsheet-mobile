package com.example.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.penWidgetStateDataStore by preferencesDataStore(name = "pen_widget_state")

data class PenWidgetStoredState(
    val draftSeconds: Int,
    val pendingCommit: PenWidgetCommitPayload?,
    val lastQueuedAtMillis: Long?,
)

data class PendingPenWidgetCommit(
    val appWidgetId: Int,
    val payload: PenWidgetCommitPayload,
)

/**
 * Atomic per-widget state. Every read-modify-write mutation happens inside one DataStore edit so
 * two rapid ± taps cannot overwrite one another and undo/commit cannot both win the race.
 */
class PenWidgetStateRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.penWidgetStateDataStore)

    suspend fun read(appWidgetId: Int): PenWidgetStoredState {
        requireValidWidgetId(appWidgetId)
        val preferences = dataStore.data.first()
        return PenWidgetStoredState(
            draftSeconds = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0,
            pendingCommit = PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]),
            lastQueuedAtMillis = preferences[lastQueuedKey(appWidgetId)],
        )
    }

    suspend fun adjustDraftSeconds(appWidgetId: Int, delta: Int): Int {
        requireValidWidgetId(appWidgetId)
        var result = 0
        dataStore.edit { preferences ->
            if (PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]) != null) {
                result = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0
            } else {
                result = stepSeconds(preferences[draftKey(appWidgetId)] ?: 0, delta)
                preferences[draftKey(appWidgetId)] = result
            }
        }
        return result
    }

    suspend fun resetDraftSeconds(appWidgetId: Int): Int {
        requireValidWidgetId(appWidgetId)
        var result = 0
        dataStore.edit { preferences ->
            if (PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]) != null) {
                result = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0
            } else {
                preferences[draftKey(appWidgetId)] = 0
                result = 0
            }
        }
        return result
    }

    /** Captures a payload and clears the draft in the same transaction. */
    suspend fun submitCommit(appWidgetId: Int, payload: PenWidgetCommitPayload): Boolean {
        requireValidWidgetId(appWidgetId)
        var accepted = false
        dataStore.edit { preferences ->
            val rawPending = preferences[pendingKey(appWidgetId)]
            val pending = PenWidgetPayloadCodec.decode(rawPending)
            if (pending != null) return@edit
            if (rawPending != null) preferences.remove(pendingKey(appWidgetId))
            preferences[pendingKey(appWidgetId)] = PenWidgetPayloadCodec.encode(payload)
            preferences.remove(draftKey(appWidgetId))
            accepted = true
        }
        return accepted
    }

    /**
     * DataStore edit, rather than WorkManager cancellation, arbitrates undo vs commit. Exactly one
     * caller can observe the matching pending payload; the other caller becomes a no-op.
     */
    suspend fun undo(appWidgetId: Int, commitId: String): Boolean {
        requireValidWidgetId(appWidgetId)
        var restored = false
        dataStore.edit { preferences ->
            when (val resolution = resolveUndo(
                PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]),
                commitId,
            )) {
                is PenWidgetUndoResolution.Restored -> {
                    preferences.remove(pendingKey(appWidgetId))
                    preferences[draftKey(appWidgetId)] = resolution.seconds
                    restored = true
                }

                PenWidgetUndoResolution.NoOp -> Unit
            }
        }
        return restored
    }

    /** Atomically takes a payload for a worker or overdue flush. */
    suspend fun takeCommit(
        appWidgetId: Int,
        commitId: String?,
        nowMillis: Long,
    ): PenWidgetCommitPayload? {
        requireValidWidgetId(appWidgetId)
        var payload: PenWidgetCommitPayload? = null
        dataStore.edit { preferences ->
            val resolution = resolveCommit(
                payload = PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]),
                commitId = commitId,
                nowMillis = nowMillis,
            )
            if (resolution is PenWidgetCommitResolution.Committed) {
                payload = resolution.payload
                preferences.remove(pendingKey(appWidgetId))
                preferences[lastQueuedKey(appWidgetId)] = nowMillis
            }
        }
        return payload
    }

    suspend fun pendingCommits(): List<PendingPenWidgetCommit> {
        val preferences = dataStore.data.first()
        return preferences.asMap()
            .asSequence()
            .filter { (key, value) ->
                key.name.startsWith(PENDING_PREFIX) && value is String
            }
            .mapNotNull { (key, value) ->
                val appWidgetId = key.name.removePrefix(PENDING_PREFIX).toIntOrNull()
                    ?: return@mapNotNull null
                val payload = PenWidgetPayloadCodec.decode(value as String)
                    ?: return@mapNotNull null
                PendingPenWidgetCommit(appWidgetId, payload)
            }
            .sortedBy { it.appWidgetId }
            .toList()
    }

    suspend fun clear(appWidgetId: Int) {
        requireValidWidgetId(appWidgetId)
        dataStore.edit { preferences ->
            preferences.remove(draftKey(appWidgetId))
            preferences.remove(pendingKey(appWidgetId))
            preferences.remove(lastQueuedKey(appWidgetId))
        }
    }

    private fun draftKey(appWidgetId: Int) = intPreferencesKey("$DRAFT_PREFIX$appWidgetId")

    private fun pendingKey(appWidgetId: Int) = stringPreferencesKey("$PENDING_PREFIX$appWidgetId")

    private fun lastQueuedKey(appWidgetId: Int) = longPreferencesKey("$LAST_QUEUED_PREFIX$appWidgetId")

    private companion object {
        const val DRAFT_PREFIX = "draft_seconds_"
        const val PENDING_PREFIX = "pending_commit_"
        const val LAST_QUEUED_PREFIX = "last_queued_at_"

        fun requireValidWidgetId(appWidgetId: Int) {
            require(appWidgetId >= 0) { "Invalid AppWidget id: $appWidgetId" }
        }
    }
}
