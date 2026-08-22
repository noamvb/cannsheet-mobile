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
    /**
     * The pending slot holds a raw value this build cannot decode — corrupt state, or state
     * written by a newer payload version. The value is deliberately preserved rather than
     * overwritten, so the surface is blocked rather than editable: [pendingCommit] is null but
     * neither a submission nor a draft edit will be accepted until the value is resolved.
     */
    val pendingCommitUnreadable: Boolean = false,
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
        val rawPending = preferences[pendingKey(appWidgetId)]
        val pendingCommit = PenWidgetPayloadCodec.decode(rawPending)
        return PenWidgetStoredState(
            draftSeconds = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0,
            pendingCommit = pendingCommit,
            lastQueuedAtMillis = preferences[lastQueuedKey(appWidgetId)],
            pendingCommitUnreadable = rawPending != null && pendingCommit == null,
        )
    }

    suspend fun adjustDraftSeconds(appWidgetId: Int, delta: Int): Int {
        requireValidWidgetId(appWidgetId)
        var result = 0
        dataStore.edit { preferences ->
            // Any raw pending value blocks a draft edit, decodable or not. Submission uses the
            // same raw test, so an undecodable payload cannot leave the draft editable while
            // every submit is silently refused.
            if (preferences[pendingKey(appWidgetId)] != null) {
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
            // Any raw pending value blocks a draft edit, decodable or not. Submission uses the
            // same raw test, so an undecodable payload cannot leave the draft editable while
            // every submit is silently refused.
            if (preferences[pendingKey(appWidgetId)] != null) {
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
            // Any raw pending value blocks a draft edit, decodable or not. Submission uses the
            // same raw test, so an undecodable payload cannot leave the draft editable while
            // every submit is silently refused.
            if (preferences[pendingKey(appWidgetId)] != null) {
                result = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0
            } else {
                result = seconds.coerceIn(0, MAX_SECONDS)
                preferences[draftKey(appWidgetId)] = result
            }
        }
        return result
    }

    /**
     * Test-only duration seeding. Production duration inputs must use the [submitCommit] builder
     * overload, while direct uses inputs must use [submitDirectCommit].
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    suspend fun submitCommit(appWidgetId: Int, payload: PenWidgetCommitPayload): Boolean {
        requireValidWidgetId(appWidgetId)
        require(
            payload.inputKind == DeferredPenInputKind.DURATION_SECONDS &&
                payload.seconds != null &&
                payload.restoreDraftSeconds == payload.seconds,
        ) { "A widget test payload must contain matching duration and undo seconds." }
        var accepted = false
        dataStore.edit { preferences ->
            val rawPending = preferences[pendingKey(appWidgetId)]
            if (rawPending != null) return@edit
            preferences[pendingKey(appWidgetId)] = PenWidgetPayloadCodec.encode(payload)
            preferences.remove(draftKey(appWidgetId))
            accepted = true
        }
        return accepted
    }

    /**
     * Captures the current draft and constructs its immutable payload inside the same edit. A
     * concurrent increment is therefore either included in [buildPayload] or observes the pending
     * commit and becomes a no-op; it can never be applied and then discarded. An undecodable raw
     * pending value also blocks submission so future-version or corrupt state remains diagnosable.
     */
    suspend fun submitCommit(
        appWidgetId: Int,
        buildPayload: (seconds: Int) -> PenWidgetCommitPayload?,
    ): PenWidgetCommitPayload? {
        requireValidWidgetId(appWidgetId)
        var accepted: PenWidgetCommitPayload? = null
        dataStore.edit { preferences ->
            val rawPending = preferences[pendingKey(appWidgetId)]
            if (rawPending != null) return@edit

            val seconds = preferences[draftKey(appWidgetId)]?.coerceIn(0, MAX_SECONDS) ?: 0
            if (seconds <= 0) return@edit
            val payload = buildPayload(seconds) ?: return@edit
            require(payload.inputKind == DeferredPenInputKind.DURATION_SECONDS) {
                "A draft submission must use duration input."
            }
            require(payload.seconds == seconds) {
                "Widget payload seconds must match the atomically captured draft."
            }
            require(payload.restoreDraftSeconds == seconds) {
                "Widget payload undo must restore the atomically captured draft."
            }

            preferences[pendingKey(appWidgetId)] = PenWidgetPayloadCodec.encode(payload)
            preferences.remove(draftKey(appWidgetId))
            accepted = payload
        }
        return accepted
    }

    /**
     * Atomically stores a uses-native deferred submission for a non-widget producer. Direct
     * submissions have no editable seconds draft: neither submit nor undo mutates a draft key. Any
     * existing raw pending value blocks the builder and remains untouched.
     */
    suspend fun submitDirectCommit(
        surfaceId: Int,
        buildPayload: () -> PenWidgetCommitPayload?,
    ): PenWidgetCommitPayload? {
        requireValidWidgetId(surfaceId)
        var accepted: PenWidgetCommitPayload? = null
        dataStore.edit { preferences ->
            val rawPending = preferences[pendingKey(surfaceId)]
            if (rawPending != null) return@edit

            val payload = buildPayload() ?: return@edit
            require(payload.inputKind == DeferredPenInputKind.DIRECT_USES) {
                "A direct submission must use uses input."
            }
            require(
                payload.seconds == null &&
                    payload.secondsPerUse == null &&
                    payload.restoreDraftSeconds == null,
            ) {
                "A direct submission cannot carry duration or draft state."
            }

            preferences[pendingKey(surfaceId)] = PenWidgetPayloadCodec.encode(payload)
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
        var undone = false
        dataStore.edit { preferences ->
            when (val resolution = resolveUndo(
                PenWidgetPayloadCodec.decode(preferences[pendingKey(appWidgetId)]),
                commitId,
                nowMillis,
            )) {
                is PenWidgetUndoResolution.Restored -> {
                    preferences.remove(pendingKey(appWidgetId))
                    preferences[draftKey(appWidgetId)] = resolution.seconds
                    undone = true
                }

                PenWidgetUndoResolution.Removed -> {
                    preferences.remove(pendingKey(appWidgetId))
                    undone = true
                }

                PenWidgetUndoResolution.NoOp -> Unit
            }
        }
        return undone
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

    /**
     * Reads pre-v1.5.1 configuration that may still be sitting in this (legacy) store. Only
     * [PenWidgetConfigRepository.read] (via its injected `readLegacy` dependency) should call
     * this; every other caller belongs on the new config repository.
     */
    suspend fun readLegacyConfig(appWidgetId: Int): PenWidgetInstanceConfig {
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
     *
     * Configuration no longer lives in this store (see [PenWidgetConfigRepository]), so this remap
     * only moves the draft/pending/last-queued state that is deliberately excluded from backup.
     * Within a single device an id remap can still happen and the draft should follow; it is only
     * cross-device restore that must not carry queue-participating payloads, and that is enforced
     * by keeping this store out of the backup file, not by anything in this method.
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
                )
            }

            oldWidgetIds.forEach { oldId ->
                preferences.remove(draftKey(oldId))
                preferences.remove(pendingKey(oldId))
                preferences.remove(lastQueuedKey(oldId))
            }

            snapshot.forEach { state ->
                val newId = state.newWidgetId
                state.draftSeconds?.let { preferences[draftKey(newId)] = it }
                state.pendingCommit?.let { preferences[pendingKey(newId)] = it }
                state.lastQueuedAtMillis?.let { preferences[lastQueuedKey(newId)] = it }
            }
        }
    }

    /** Removes only the three legacy config keys; used once migration has copied them forward. */
    suspend fun clearLegacyConfig(appWidgetId: Int) {
        requireValidWidgetId(appWidgetId)
        dataStore.edit { preferences ->
            preferences.remove(pinnedProductKey(appWidgetId))
            preferences.remove(discreetKey(appWidgetId))
            preferences.remove(stepOverrideKey(appWidgetId))
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
