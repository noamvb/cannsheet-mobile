package com.example.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.penWidgetConfigDataStore by preferencesDataStore(name = "pen_widget_config")

private data class RemappedConfig(
    val newWidgetId: Int,
    val pinnedProductId: String?,
    val discreet: Boolean?,
    val stepSecondsOverride: Int?,
    val migrated: Boolean?,
)

/**
 * Per-instance widget configuration, deliberately kept in a different DataStore file from
 * [PenWidgetStateRepository]. Configuration is safe to restore onto a new device; drafts and
 * pending commit payloads are not, because the Room queue they belong to is excluded from backup
 * and the application flushes overdue payloads on every start.
 *
 * [readLegacy] and [clearLegacy] are injected so this class stays unit-testable without a real
 * [PenWidgetStateRepository]; the [Context] constructor wires them to the pre-v1.5.1 legacy store.
 */
class PenWidgetConfigRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val readLegacy: suspend (Int) -> PenWidgetInstanceConfig = { PenWidgetInstanceConfig.DEFAULT },
    private val clearLegacy: suspend (Int) -> Unit = { },
) {
    constructor(context: Context) : this(
        context.applicationContext.penWidgetConfigDataStore,
        readLegacy = { PenWidgetStateRepository(context).readLegacyConfig(it) },
        clearLegacy = { PenWidgetStateRepository(context).clearLegacyConfig(it) },
    )

    /**
     * Every caller reads configuration through this one method - there is no separate explicit
     * migration step for a caller to forget to invoke. An unmigrated widget id adopts its legacy
     * configuration (if any) atomically inside a single DataStore edit before this returns, so a
     * caller can never observe stale defaults just because it happened to run before
     * [PenWidgetUpdater.update] fired for that id - e.g. a button tap on a widget still showing
     * its pre-upgrade RemoteViews, routed straight through [PenWidgetActionRouter.handle].
     */
    suspend fun read(appWidgetId: Int): PenWidgetInstanceConfig {
        requireValidWidgetId(appWidgetId)
        val preferences = dataStore.data.first()
        if (preferences[migratedKey(appWidgetId)] == true) {
            return readFrom(preferences, appWidgetId)
        }

        val legacyConfig = readLegacy(appWidgetId)
        var result = legacyConfig
        dataStore.edit { mutable ->
            // Re-check inside the edit: a concurrent save may have won the race, and its values
            // are newer than anything in the legacy store, so they must not be overwritten.
            if (mutable[migratedKey(appWidgetId)] == true) {
                result = readFrom(mutable, appWidgetId)
                return@edit
            }
            applyConfig(mutable, appWidgetId, legacyConfig)
            mutable[migratedKey(appWidgetId)] = true
        }
        // Tidying the legacy store is best-effort: the adopted config above is already durable
        // regardless of whether this succeeds, and a failure to clean up old keys must not fail
        // a widget render. Cancellation still propagates, matching ConsumptionLogger.log.
        try {
            clearLegacy(appWidgetId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Stale legacy keys are harmless; the migrated marker already routes reads here.
        }
        return result
    }

    suspend fun write(appWidgetId: Int, config: PenWidgetInstanceConfig) {
        requireValidWidgetId(appWidgetId)
        dataStore.edit { preferences ->
            applyConfig(preferences, appWidgetId, config)
            // A write is definitive regardless of whether it originated from the configure
            // activity or from legacy adoption inside read(): either way this widget id now has a
            // real config here, so future reads must stop falling back to legacy.
            preferences[migratedKey(appWidgetId)] = true
        }
    }

    suspend fun readDefaultStepSeconds(): Int =
        dataStore.data.first().defaultStepSeconds()

    suspend fun writeDefaultStepSeconds(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[DEFAULT_STEP_SECONDS_KEY] = seconds.coerceIn(1, MAX_SECONDS)
        }
    }

    fun defaultStepSecondsFlow(): Flow<Int> =
        dataStore.data.map { preferences -> preferences.defaultStepSeconds() }

    suspend fun effectiveStepSeconds(appWidgetId: Int): Int =
        (read(appWidgetId).stepSecondsOverride ?: readDefaultStepSeconds())
            .coerceIn(1, MAX_SECONDS)

    suspend fun clear(appWidgetId: Int) {
        requireValidWidgetId(appWidgetId)
        dataStore.edit { preferences ->
            preferences.remove(pinnedProductKey(appWidgetId))
            preferences.remove(discreetKey(appWidgetId))
            preferences.remove(stepOverrideKey(appWidgetId))
            // The migrated flag is per-widget bookkeeping, not configuration, but it must go too:
            // leaving it behind orphans one key per deleted widget forever, and a recycled widget
            // id would inherit an "already migrated" marker it never earned.
            preferences.remove(migratedKey(appWidgetId))
        }
    }

    /**
     * Moves every per-widget config key from [oldWidgetIds][i] to [newWidgetIds][i] in one edit.
     * The migrated flag moves along with the values: without that, a restored widget id would
     * look unmigrated and the next [read] would overwrite the just-restored configuration with
     * defaults read from an empty (never backed up) legacy store.
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
                RemappedConfig(
                    newWidgetId = newWidgetIds[index],
                    pinnedProductId = preferences[pinnedProductKey(oldId)],
                    discreet = preferences[discreetKey(oldId)],
                    stepSecondsOverride = preferences[stepOverrideKey(oldId)],
                    migrated = preferences[migratedKey(oldId)],
                )
            }

            oldWidgetIds.forEach { oldId ->
                preferences.remove(pinnedProductKey(oldId))
                preferences.remove(discreetKey(oldId))
                preferences.remove(stepOverrideKey(oldId))
                preferences.remove(migratedKey(oldId))
            }

            snapshot.forEach { state ->
                val newId = state.newWidgetId
                state.pinnedProductId?.let { preferences[pinnedProductKey(newId)] = it }
                state.discreet?.let { preferences[discreetKey(newId)] = it }
                state.stepSecondsOverride?.let { preferences[stepOverrideKey(newId)] = it }
                state.migrated?.let { preferences[migratedKey(newId)] = it }
            }
        }
    }

    /** The one place that serializes a [PenWidgetInstanceConfig]; used by both [write] and [read]. */
    private fun applyConfig(
        preferences: MutablePreferences,
        appWidgetId: Int,
        config: PenWidgetInstanceConfig,
    ) {
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

    private fun readFrom(preferences: Preferences, appWidgetId: Int) = PenWidgetInstanceConfig(
        pinnedProductId = preferences[pinnedProductKey(appWidgetId)]?.takeIf { it.isNotBlank() },
        discreet = preferences[discreetKey(appWidgetId)] ?: false,
        stepSecondsOverride = preferences[stepOverrideKey(appWidgetId)]
            ?.takeIf { it in 1..MAX_SECONDS },
    )

    private fun migratedKey(appWidgetId: Int) = booleanPreferencesKey("$MIGRATED_PREFIX$appWidgetId")

    private fun pinnedProductKey(appWidgetId: Int) =
        stringPreferencesKey("$PINNED_PRODUCT_PREFIX$appWidgetId")

    private fun discreetKey(appWidgetId: Int) = booleanPreferencesKey("$DISCREET_PREFIX$appWidgetId")

    private fun stepOverrideKey(appWidgetId: Int) =
        intPreferencesKey("$STEP_OVERRIDE_PREFIX$appWidgetId")

    private fun Preferences.defaultStepSeconds(): Int =
        this[DEFAULT_STEP_SECONDS_KEY]?.takeIf { it in 1..MAX_SECONDS } ?: STEP_SECONDS

    private companion object {
        val DEFAULT_STEP_SECONDS_KEY = intPreferencesKey("default_step_seconds")
        const val MIGRATED_PREFIX = "migrated_"
        const val PINNED_PRODUCT_PREFIX = "pinned_product_"
        const val DISCREET_PREFIX = "discreet_"
        const val STEP_OVERRIDE_PREFIX = "step_override_"

        fun requireValidWidgetId(appWidgetId: Int) {
            require(appWidgetId >= 0) { "Invalid AppWidget id: $appWidgetId" }
        }
    }
}
