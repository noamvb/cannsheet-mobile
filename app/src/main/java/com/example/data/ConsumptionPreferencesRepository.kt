package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.consumptionPreferencesDataStore by preferencesDataStore(
    name = "consumption_preferences"
)

data class ConsumptionPreferences(
    val quantityPresets: List<Double>,
    val includeUnopened: Boolean
)

class ConsumptionPreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.consumptionPreferencesDataStore

    val preferences: Flow<ConsumptionPreferences> = dataStore.data
        .map { storedPreferences ->
            val presets = resolveQuantityPresets(
                storedCount = storedPreferences[QUANTITY_PRESET_COUNT],
                valueAt = { index -> storedPreferences[quantityPresetKey(index)] },
            )

            ConsumptionPreferences(
                quantityPresets = presets,
                includeUnopened = storedPreferences[INCLUDE_UNOPENED] ?: false
            )
        }
        .distinctUntilChanged()

    val quantityPresets: Flow<List<Double>> = preferences
        .map { it.quantityPresets }
        .distinctUntilChanged()

    val includeUnopened: Flow<Boolean> = preferences
        .map { it.includeUnopened }
        .distinctUntilChanged()

    suspend fun setQuantityPresets(presets: List<Double>) {
        require(isValidQuantityPresets(presets)) {
            "Quantity presets must contain 1 to 10 positive, finite, distinct values."
        }

        dataStore.edit { storedPreferences ->
            storedPreferences[QUANTITY_PRESET_COUNT] = presets.size
            presets.forEachIndexed { index, preset ->
                storedPreferences[quantityPresetKey(index + 1)] = preset
            }
            ((presets.size + 1)..MAX_QUANTITY_PRESETS).forEach { index ->
                storedPreferences.remove(quantityPresetKey(index))
            }
        }
    }

    suspend fun setIncludeUnopened(include: Boolean) {
        dataStore.edit { storedPreferences ->
            storedPreferences[INCLUDE_UNOPENED] = include
        }
    }

    companion object {
        const val MIN_QUANTITY_PRESETS = 1
        const val MAX_QUANTITY_PRESETS = 10
        private const val LEGACY_QUANTITY_PRESET_COUNT = 3

        val DEFAULT_QUANTITY_PRESETS: List<Double> = listOf(0.5, 1.0, 2.0)

        fun isValidQuantityPresets(presets: List<Double>): Boolean =
            presets.size in MIN_QUANTITY_PRESETS..MAX_QUANTITY_PRESETS &&
                presets.all { it.isFinite() && it > 0.0 } &&
                presets.distinct().size == presets.size

        internal fun resolveQuantityPresets(
            storedCount: Int?,
            valueAt: (Int) -> Double?,
        ): List<Double> {
            val count = when {
                storedCount == null -> LEGACY_QUANTITY_PRESET_COUNT
                storedCount in MIN_QUANTITY_PRESETS..MAX_QUANTITY_PRESETS -> storedCount
                else -> return DEFAULT_QUANTITY_PRESETS
            }
            val storedPresets = (1..count).map(valueAt)
            if (storedPresets.any { it == null }) return DEFAULT_QUANTITY_PRESETS

            val presets = storedPresets.filterNotNull()
            return presets.takeIf(::isValidQuantityPresets) ?: DEFAULT_QUANTITY_PRESETS
        }

        private fun quantityPresetKey(index: Int) =
            doublePreferencesKey("quantity_preset_$index")

        private val QUANTITY_PRESET_COUNT = intPreferencesKey("quantity_preset_count")
        private val INCLUDE_UNOPENED = booleanPreferencesKey("include_unopened")
    }
}
