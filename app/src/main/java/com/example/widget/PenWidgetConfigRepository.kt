package com.example.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

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
 */
class PenWidgetConfigRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.penWidgetConfigDataStore)

    suspend fun read(appWidgetId: Int): PenWidgetInstanceConfig {
        requireValidWidgetId(appWidgetId)
        val preferences = dataStore.data.first()
        if (preferences[migratedKey(appWidgetId)] == true) {
            return readFrom(preferences, appWidgetId)
        }
        return PenWidgetInstanceConfig.DEFAULT
    }

    suspend fun write(appWidgetId: Int, config: PenWidgetInstanceConfig) {
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
            // A write is definitive regardless of whether it originated from the configure
            // activity or from migrateFromLegacyStore: either way this widget id now has a real
            // config here, so future reads must stop falling back to defaults.
            preferences[migratedKey(appWidgetId)] = true
        }
    }

    suspend fun clear(appWidgetId: Int) {
        requireValidWidgetId(appWidgetId)
        dataStore.edit { preferences ->
            preferences.remove(pinnedProductKey(appWidgetId))
            preferences.remove(discreetKey(appWidgetId))
            preferences.remove(stepOverrideKey(appWidgetId))
            // The migrated flag is per-widget bookkeeping, not configuration, but it must go too:
            // leaving it behind orphans one key per deleted widget forever, and a recycled widget
            // id would inherit a "already migrated" marker it never earned.
            preferences.remove(migratedKey(appWidgetId))
        }
    }

    /**
     * Moves every per-widget config key from [oldWidgetIds][i] to [newWidgetIds][i] in one edit.
     * The migrated flag moves along with the values: without that, a restored widget id would
     * look unmigrated and a later [migrateFromLegacyStore] call would overwrite the just-restored
     * configuration with defaults read from an empty (never backed up) legacy store.
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

    /**
     * Copies any pre-v1.5.1 configuration out of the legacy state store. Idempotent: the
     * per-widget migrated flag makes a second call a no-op.
     */
    suspend fun migrateFromLegacyStore(appWidgetId: Int, legacy: PenWidgetStateRepository) {
        requireValidWidgetId(appWidgetId)
        val alreadyMigrated = dataStore.data.first()[migratedKey(appWidgetId)] == true
        if (alreadyMigrated) return
        val legacyConfig = legacy.readLegacyConfig(appWidgetId)
        write(appWidgetId, legacyConfig)
        legacy.clearLegacyConfig(appWidgetId)
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

    private companion object {
        const val MIGRATED_PREFIX = "migrated_"
        const val PINNED_PRODUCT_PREFIX = "pinned_product_"
        const val DISCREET_PREFIX = "discreet_"
        const val STEP_OVERRIDE_PREFIX = "step_override_"

        fun requireValidWidgetId(appWidgetId: Int) {
            require(appWidgetId >= 0) { "Invalid AppWidget id: $appWidgetId" }
        }
    }
}
