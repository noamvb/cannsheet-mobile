package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
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
            val storedPresets = listOf(
                storedPreferences[QUANTITY_PRESET_1],
                storedPreferences[QUANTITY_PRESET_2],
                storedPreferences[QUANTITY_PRESET_3]
            )
            val presets = if (
                storedPresets.all { it != null } &&
                isValidQuantityPresets(storedPresets.filterNotNull())
            ) {
                storedPresets.filterNotNull()
            } else {
                DEFAULT_QUANTITY_PRESETS
            }

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
            "Quantity presets must contain exactly three positive, finite, distinct values."
        }

        dataStore.edit { storedPreferences ->
            storedPreferences[QUANTITY_PRESET_1] = presets[0]
            storedPreferences[QUANTITY_PRESET_2] = presets[1]
            storedPreferences[QUANTITY_PRESET_3] = presets[2]
        }
    }

    suspend fun setIncludeUnopened(include: Boolean) {
        dataStore.edit { storedPreferences ->
            storedPreferences[INCLUDE_UNOPENED] = include
        }
    }

    companion object {
        val DEFAULT_QUANTITY_PRESETS: List<Double> = listOf(0.5, 1.0, 2.0)

        fun isValidQuantityPresets(presets: List<Double>): Boolean =
            presets.size == 3 &&
                presets.all { it.isFinite() && it > 0.0 } &&
                presets.distinct().size == presets.size

        private val QUANTITY_PRESET_1 = doublePreferencesKey("quantity_preset_1")
        private val QUANTITY_PRESET_2 = doublePreferencesKey("quantity_preset_2")
        private val QUANTITY_PRESET_3 = doublePreferencesKey("quantity_preset_3")
        private val INCLUDE_UNOPENED = booleanPreferencesKey("include_unopened")
    }
}
