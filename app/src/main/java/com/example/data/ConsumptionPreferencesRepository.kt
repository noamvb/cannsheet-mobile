package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.consumptionPreferencesDataStore by preferencesDataStore(
    name = "consumption_preferences"
)

data class ConsumptionPreferences(
    val quantityPresets: List<Double>,
    val includeUnopened: Boolean,
    val quantityPresetOverrides: Map<ProductTypeKey, List<Double>> = emptyMap(),
)

@ConsistentCopyVisibility
data class ProductTypeKey private constructor(val type: String) {
    companion object {
        operator fun invoke(type: String): ProductTypeKey =
            ProductTypeKey(type.trim().uppercase(Locale.ROOT))
    }
}

internal const val QUANTITY_PRESET_OVERRIDES_JSON_KEY = "quantity_preset_overrides_json"
internal const val QUANTITY_PRESET_OVERRIDES_PAYLOAD_VERSION = 1

class ConsumptionPreferencesRepository private constructor(
    private val dataStore: DataStore<Preferences>,
    private val moshi: Moshi,
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.consumptionPreferencesDataStore,
        moshi = Moshi.Builder().build(),
    )

    /** Injectable for focused JVM tests. */
    internal constructor(dataStore: DataStore<Preferences>) : this(
        dataStore = dataStore,
        moshi = Moshi.Builder().build(),
    )

    val preferences: Flow<ConsumptionPreferences> = dataStore.data
        .map { storedPreferences ->
            val presets = resolveQuantityPresets(
                storedCount = storedPreferences[QUANTITY_PRESET_COUNT],
                valueAt = { index -> storedPreferences[quantityPresetKey(index)] },
            )

            ConsumptionPreferences(
                quantityPresets = presets,
                includeUnopened = storedPreferences[INCLUDE_UNOPENED] ?: false,
                quantityPresetOverrides = decodeQuantityPresetOverrides(
                    storedPreferences[QUANTITY_PRESET_OVERRIDES_JSON],
                ),
            )
        }
        .distinctUntilChanged()

    val quantityPresets: Flow<List<Double>> = preferences
        .map { it.quantityPresets }
        .distinctUntilChanged()

    val includeUnopened: Flow<Boolean> = preferences
        .map { it.includeUnopened }
        .distinctUntilChanged()

    val quantityPresetOverrides: Flow<Map<ProductTypeKey, List<Double>>> = preferences
        .map { it.quantityPresetOverrides }
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

    suspend fun setQuantityPresetsForType(
        type: ProductTypeKey,
        presets: List<Double>,
    ) {
        require(type.type.isNotBlank()) { "Product type is required." }
        require(isValidQuantityPresets(presets)) {
            "Quantity presets must contain 1 to 10 positive, finite, distinct values."
        }

        dataStore.edit { storedPreferences ->
            val updatedOverrides = decodeQuantityPresetOverrides(
                storedPreferences[QUANTITY_PRESET_OVERRIDES_JSON],
            ).toMutableMap().apply {
                this[type] = presets
            }
            storedPreferences[QUANTITY_PRESET_OVERRIDES_JSON] =
                encodeQuantityPresetOverrides(updatedOverrides, moshi)
        }
    }

    suspend fun clearQuantityPresetsForType(type: ProductTypeKey) {
        dataStore.edit { storedPreferences ->
            val updatedOverrides = decodeQuantityPresetOverrides(
                storedPreferences[QUANTITY_PRESET_OVERRIDES_JSON],
            ).toMutableMap().apply {
                remove(type)
            }
            storedPreferences[QUANTITY_PRESET_OVERRIDES_JSON] =
                encodeQuantityPresetOverrides(updatedOverrides, moshi)
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

        fun effectiveQuantityPresets(
            globalPresets: List<Double>,
            overrides: Map<ProductTypeKey, List<Double>>,
            productType: String?,
        ): List<Double> {
            if (productType.isNullOrBlank()) return globalPresets
            val override = overrides[ProductTypeKey(productType)] ?: return globalPresets
            return override.takeIf(::isValidQuantityPresets) ?: globalPresets
        }

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
        private val QUANTITY_PRESET_OVERRIDES_JSON =
            stringPreferencesKey(QUANTITY_PRESET_OVERRIDES_JSON_KEY)
    }
}

internal fun encodeQuantityPresetOverrides(
    overrides: Map<ProductTypeKey, List<Double>>,
    moshi: Moshi = Moshi.Builder().build(),
): String = moshi.adapter(PersistedQuantityPresetOverridesPayload::class.java).toJson(
    PersistedQuantityPresetOverridesPayload(
        version = QUANTITY_PRESET_OVERRIDES_PAYLOAD_VERSION,
        overrides = overrides.entries
            .sortedBy { it.key.type }
            .map { (key, presets) ->
                PersistedQuantityPresetOverride(
                    type = key.type,
                    presets = presets,
                )
            },
    ),
)

internal fun decodeQuantityPresetOverrides(
    payload: String?,
): Map<ProductTypeKey, List<Double>> = runCatching {
    val decoded = QuantityPresetOverridesJson.valueAdapter.fromJson(payload.orEmpty()) as? Map<*, *>
        ?: return emptyMap()
    val version = (decoded["version"] as? Number)?.toDouble()
    if (version != QUANTITY_PRESET_OVERRIDES_PAYLOAD_VERSION.toDouble()) return emptyMap()
    val records = decoded["overrides"] as? List<*> ?: return emptyMap()

    records
        .fold(linkedMapOf<ProductTypeKey, List<Double>>()) { overrides, record ->
            val fields = record as? Map<*, *> ?: return@fold overrides
            val rawType = fields["type"] as? String ?: return@fold overrides
            val key = ProductTypeKey(rawType)
            if (key.type.isBlank()) return@fold overrides

            val rawPresets = fields["presets"] as? List<*> ?: return@fold overrides
            if (rawPresets.any { it !is Number }) return@fold overrides
            val presets = rawPresets.map { (it as Number).toDouble() }
            if (!ConsumptionPreferencesRepository.isValidQuantityPresets(presets)) {
                return@fold overrides
            }
            overrides[key] = presets
            overrides
        }
        .entries
        .sortedBy { it.key.type }
        .associateTo(linkedMapOf()) { it.toPair() }
}.getOrDefault(emptyMap())

private object QuantityPresetOverridesJson {
    val valueAdapter = Moshi.Builder().build().adapter(Any::class.java)
}

@JsonClass(generateAdapter = true)
internal data class PersistedQuantityPresetOverridesPayload(
    val version: Int? = null,
    val overrides: List<PersistedQuantityPresetOverride>? = null,
)

@JsonClass(generateAdapter = true)
internal data class PersistedQuantityPresetOverride(
    val type: String? = null,
    val presets: List<Double>? = null,
)
