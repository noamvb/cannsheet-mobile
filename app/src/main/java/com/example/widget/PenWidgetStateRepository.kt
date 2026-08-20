package com.example.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
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

data class PenWidgetCommitClaim(
    val appWidgetId: Int,
    val payload: PenWidgetCommitPayload,
    val claimId: String,
)

private data class RemappedState(
    val newWidgetId: Int,
    val draftSeconds: Int?,
    val pendingCommit: String?,
    val lastQueuedAtMillis: Long?,
    val pinnedProductId: String?,
    val discreet: Boolean?,
    val stepSecondsOverride: Int?,
)

/**
 * Atomic per-widget state. Every read-modify-write mutation happens inside one DataStore edit so
 * two rapid ± taps cannot overwrite one another and undo/commit cannot both win the race.
 */
class PenWidgetStateRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val claimOwnerId: String = PROCESS_CLAIM_OWNER_ID,
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

    suspend fun setDraftSeconds(appWidgetId: Int, seconds: Int): Int {
        requireValidWidgetId(appWidgetId)
        var result = 0
        dataStore.edit { preferences ->
            if (PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]) != null) {
                result = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0
            } else {
                result = seconds.coerceIn(0, MAX_SECONDS)
                preferences[draftKey(appWidgetId)] = result
            }
        }
        return result
    }

    /**
     * Test-only. Production must use the [submitCommit] overload that builds the payload inside
     * the edit, so a concurrent increment cannot be applied and then discarded.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
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
     * Captures the current draft and constructs its immutable payload inside the same edit. A
     * concurrent increment is therefore either included in [buildPayload] or observes the pending
     * commit and becomes a no-op; it can never be applied and then discarded.
     */
    suspend fun submitCommit(
        appWidgetId: Int,
        buildPayload: (seconds: Int) -> PenWidgetCommitPayload?,
    ): PenWidgetCommitPayload? {
        requireValidWidgetId(appWidgetId)
        var accepted: PenWidgetCommitPayload? = null
        dataStore.edit { preferences ->
            val rawPending = preferences[pendingKey(appWidgetId)]
            if (PenWidgetPayloadCodec.decode(rawPending) != null) return@edit

            val seconds = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0
            if (seconds <= 0) return@edit
            val payload = buildPayload(seconds) ?: return@edit
            require(payload.seconds == seconds) {
                "Widget payload seconds must match the atomically captured draft."
            }

            if (rawPending != null) preferences.remove(pendingKey(appWidgetId))
            preferences[pendingKey(appWidgetId)] = PenWidgetPayloadCodec.encode(payload)
            preferences.remove(draftKey(appWidgetId))
            accepted = payload
        }
        return accepted
    }

    /**
     * DataStore edit, rather than WorkManager cancellation, arbitrates undo vs commit. Exactly one
     * caller can observe the matching pending payload; the other caller becomes a no-op.
     */
    suspend fun undo(
        appWidgetId: Int,
        commitId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        requireValidWidgetId(appWidgetId)
        var restored = false
        dataStore.edit { preferences ->
            when (val resolution = resolveUndo(
                PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]),
                commitId,
                nowMillis,
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

    /**
     * Atomically claims a due payload without removing it. The claim stays durable until the Room
     * write succeeds and [completeCommit] removes it; a stale claim can be recovered after process
     * death.
     */
    suspend fun claimCommit(
        appWidgetId: Int,
        commitId: String?,
        nowMillis: Long,
        force: Boolean = false,
    ): PenWidgetCommitClaim? {
        requireValidWidgetId(appWidgetId)
        val newClaimId = "$claimOwnerId:${UUID.randomUUID()}"
        var claim: PenWidgetCommitClaim? = null
        dataStore.edit { preferences ->
            val resolution = resolveCommit(
                payload = PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]),
                commitId = commitId,
                nowMillis = nowMillis,
                force = force,
                claimOwnerId = claimOwnerId,
            )
            if (resolution is PenWidgetCommitResolution.Committed) {
                val claimedPayload = resolution.payload.copy(
                    claimId = newClaimId,
                    claimedAtEpochMillis = nowMillis,
                )
                preferences[pendingKey(appWidgetId)] = PenWidgetPayloadCodec.encode(claimedPayload)
                claim = PenWidgetCommitClaim(appWidgetId, claimedPayload, newClaimId)
            }
        }
        return claim
    }

    suspend fun releaseClaim(appWidgetId: Int, commitId: String, claimId: String): Boolean {
        requireValidWidgetId(appWidgetId)
        var released = false
        dataStore.edit { preferences ->
            val payload = PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)])
            if (payload?.commitId == commitId && payload.claimId == claimId) {
                preferences[pendingKey(appWidgetId)] = PenWidgetPayloadCodec.encode(
                    payload.copy(claimId = null, claimedAtEpochMillis = null),
                )
                released = true
            }
        }
        return released
    }

    /** Removes a payload only after its exact claim has reached durable Room persistence. */
    suspend fun completeCommit(
        appWidgetId: Int,
        commitId: String,
        claimId: String,
        nowMillis: Long,
    ): Boolean {
        requireValidWidgetId(appWidgetId)
        var completed = false
        dataStore.edit { preferences ->
            val payload = PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)])
            if (payload?.commitId == commitId && payload.claimId == claimId) {
                preferences.remove(pendingKey(appWidgetId))
                preferences[lastQueuedKey(appWidgetId)] = nowMillis
                completed = true
            }
        }
        return completed
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

    suspend fun readConfig(appWidgetId: Int): PenWidgetInstanceConfig {
        requireValidWidgetId(appWidgetId)
        val preferences = dataStore.data.first()
        return PenWidgetInstanceConfig(
            pinnedProductId = preferences[pinnedProductKey(appWidgetId)]
                ?.takeIf { it.isNotBlank() },
            discreet = preferences[discreetKey(appWidgetId)] ?: false,
            stepSecondsOverride = preferences[stepOverrideKey(appWidgetId)]
                ?.takeIf { it in 1..MAX_SECONDS },
        )
    }

    /**
     * Moves every per-widget key from [oldWidgetIds][i] to [newWidgetIds][i] in one edit. Android
     * remaps app widget ids on restore; without this the restored widget reads a foreign id's
     * state and any pending commit is stranded.
     */
    suspend fun remapWidgetIds(oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        require(oldWidgetIds.size == newWidgetIds.size) {
            "Restored widget id arrays must be the same length."
        }
        oldWidgetIds.forEach(::requireValidWidgetId)
        newWidgetIds.forEach(::requireValidWidgetId)
        dataStore.edit { preferences ->
            // Snapshot every value first: a restore can map 42 -> 17 while 17 also maps
            // elsewhere, so removing as we go would corrupt the chain.
            val snapshot = oldWidgetIds.mapIndexed { index, oldId ->
                RemappedState(
                    newWidgetId = newWidgetIds[index],
                    draftSeconds = preferences[draftKey(oldId)],
                    pendingCommit = preferences[pendingKey(oldId)],
                    lastQueuedAtMillis = preferences[lastQueuedKey(oldId)],
                    pinnedProductId = preferences[pinnedProductKey(oldId)],
                    discreet = preferences[discreetKey(oldId)],
                    stepSecondsOverride = preferences[stepOverrideKey(oldId)],
                )
            }

            oldWidgetIds.forEach { oldId ->
                preferences.remove(draftKey(oldId))
                preferences.remove(pendingKey(oldId))
                preferences.remove(lastQueuedKey(oldId))
                preferences.remove(pinnedProductKey(oldId))
                preferences.remove(discreetKey(oldId))
                preferences.remove(stepOverrideKey(oldId))
            }

            snapshot.forEach { state ->
                val newId = state.newWidgetId
                state.draftSeconds?.let { preferences[draftKey(newId)] = it }
                state.pendingCommit?.let { preferences[pendingKey(newId)] = it }
                state.lastQueuedAtMillis?.let { preferences[lastQueuedKey(newId)] = it }
                state.pinnedProductId?.let { preferences[pinnedProductKey(newId)] = it }
                state.discreet?.let { preferences[discreetKey(newId)] = it }
                state.stepSecondsOverride?.let { preferences[stepOverrideKey(newId)] = it }
            }
        }
    }

    suspend fun writeConfig(appWidgetId: Int, config: PenWidgetInstanceConfig) {
        requireValidWidgetId(appWidgetId)
        dataStore.edit { preferences ->
            val pinned = config.pinnedProductId?.trim()
            if (pinned.isNullOrBlank()) {
                preferences.remove(pinnedProductKey(appWidgetId))
            } else {
                preferences[pinnedProductKey(appWidgetId)] = pinned
            }
            preferences[discreetKey(appWidgetId)] = config.discreet
            val step = config.stepSecondsOverride?.takeIf { it in 1..MAX_SECONDS }
            if (step == null) {
                preferences.remove(stepOverrideKey(appWidgetId))
            } else {
                preferences[stepOverrideKey(appWidgetId)] = step
            }
        }
    }

    suspend fun clear(appWidgetId: Int) {
        requireValidWidgetId(appWidgetId)
        dataStore.edit { preferences ->
            preferences.remove(draftKey(appWidgetId))
            preferences.remove(pendingKey(appWidgetId))
            preferences.remove(lastQueuedKey(appWidgetId))
            preferences.remove(pinnedProductKey(appWidgetId))
            preferences.remove(discreetKey(appWidgetId))
            preferences.remove(stepOverrideKey(appWidgetId))
        }
    }

    private fun draftKey(appWidgetId: Int) = intPreferencesKey("$DRAFT_PREFIX$appWidgetId")

    private fun pendingKey(appWidgetId: Int) = stringPreferencesKey("$PENDING_PREFIX$appWidgetId")

    private fun lastQueuedKey(appWidgetId: Int) = longPreferencesKey("$LAST_QUEUED_PREFIX$appWidgetId")

    private fun pinnedProductKey(appWidgetId: Int) =
        stringPreferencesKey("$PINNED_PRODUCT_PREFIX$appWidgetId")

    private fun discreetKey(appWidgetId: Int) = booleanPreferencesKey("$DISCREET_PREFIX$appWidgetId")

    private fun stepOverrideKey(appWidgetId: Int) =
        intPreferencesKey("$STEP_OVERRIDE_PREFIX$appWidgetId")

    private companion object {
        val PROCESS_CLAIM_OWNER_ID: String = UUID.randomUUID().toString()
        const val DRAFT_PREFIX = "draft_seconds_"
        const val PENDING_PREFIX = "pending_commit_"
        const val LAST_QUEUED_PREFIX = "last_queued_at_"
        const val PINNED_PRODUCT_PREFIX = "pinned_product_"
        const val DISCREET_PREFIX = "discreet_"
        const val STEP_OVERRIDE_PREFIX = "step_override_"

        fun requireValidWidgetId(appWidgetId: Int) {
            require(appWidgetId >= 0) { "Invalid AppWidget id: $appWidgetId" }
        }
    }
}
