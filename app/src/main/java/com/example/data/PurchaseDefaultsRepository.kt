package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal const val PURCHASE_DEFAULTS_DATA_STORE_NAME = "purchase_defaults"
internal const val PURCHASE_DEFAULTS_JSON_KEY = "purchase_defaults_json"
internal const val PURCHASE_DEFAULTS_PAYLOAD_VERSION = 1

private val Context.purchaseDefaultsDataStore by preferencesDataStore(
    name = PURCHASE_DEFAULTS_DATA_STORE_NAME,
)

/** A case-insensitive saved-default identity, normalized independently of the device locale. */
@ConsistentCopyVisibility
data class PurchaseDefaultKey private constructor(
    val name: String,
    val type: String,
) {
    companion object {
        operator fun invoke(name: String, type: String): PurchaseDefaultKey =
            PurchaseDefaultKey(
                name = name.trim().lowercase(Locale.ROOT),
                type = type.trim().uppercase(Locale.ROOT),
            )
    }
}

/** Values that can be reused for a matching purchase. THC is stored as a fraction, not a percent. */
data class PurchaseDefaultValues(
    val cost: Double,
    val thc: Double,
    val grams: Double,
    val postTax: Boolean? = null,
)

/**
 * The state stays visibly not-loaded until DataStore has supplied its first snapshot. This avoids
 * treating an initial empty map as proof that no saved defaults exist.
 */
sealed interface PurchaseDefaultsState {
    data object NotLoaded : PurchaseDefaultsState

    data class Loaded(
        val defaults: Map<PurchaseDefaultKey, PurchaseDefaultValues>,
    ) : PurchaseDefaultsState
}

/** Immutable purchase-form data used by the UI and view model before an offline action is queued. */
data class PurchaseSubmission(
    val date: String,
    val type: String,
    val name: String,
    val cost: Double,
    val thc: Double,
    val grams: Double,
    val borrowed: Boolean,
    val postTax: Boolean,
    val saveAsDefault: Boolean,
)

/** Returns a canonical THC fraction, or null when [value] cannot be represented safely. */
fun canonicalThcFraction(value: Double): Double? =
    value.takeIf { it.isFinite() && it in 0.0..1.0 }
        ?.let { if (it == 0.0) 0.0 else it }

fun isValidPurchaseDefaultKey(key: PurchaseDefaultKey): Boolean =
    key.name.isNotBlank() && key.type.isNotBlank()

fun isValidPurchaseDefaultValues(values: PurchaseDefaultValues): Boolean =
    values.cost.isFinite() &&
        values.cost >= 0.0 &&
        canonicalThcFraction(values.thc) != null &&
        values.grams.isFinite() &&
        values.grams > 0.0

fun isValidPurchaseSubmission(submission: PurchaseSubmission): Boolean =
    submission.date.trim().isNotEmpty() &&
        isValidPurchaseDefaultKey(PurchaseDefaultKey(submission.name, submission.type)) &&
        isValidPurchaseDefaultValues(submission.defaultValues())

fun PurchaseSubmission.defaultKey(): PurchaseDefaultKey = PurchaseDefaultKey(name, type)

fun PurchaseSubmission.defaultValues(): PurchaseDefaultValues =
    PurchaseDefaultValues(cost = cost, thc = thc, grams = grams, postTax = postTax)

class PurchaseDefaultsRepository private constructor(
    private val dataStore: DataStore<Preferences>,
    private val moshi: Moshi,
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.purchaseDefaultsDataStore,
        moshi = Moshi.Builder().build(),
    )

    /** Injectable for focused JVM tests and callers that provide their own Preferences DataStore. */
    internal constructor(dataStore: DataStore<Preferences>) : this(
        dataStore = dataStore,
        moshi = Moshi.Builder().build(),
    )

    val state: Flow<PurchaseDefaultsState> = flow {
        emit(PurchaseDefaultsState.NotLoaded)
        emitAll(
            dataStore.data.map { preferences ->
                PurchaseDefaultsState.Loaded(
                    decodePurchaseDefaults(preferences[PURCHASE_DEFAULTS_JSON]),
                )
            },
        )
    }.distinctUntilChanged()

    /**
     * Replaces only [key]'s values while rewriting the one complete, normalized map in one
     * DataStore edit. Any DataStore write error deliberately reaches the caller.
     */
    suspend fun saveDefault(
        key: PurchaseDefaultKey,
        values: PurchaseDefaultValues,
    ) {
        require(isValidPurchaseDefaultKey(key)) { "Purchase default name and type are required." }
        require(isValidPurchaseDefaultValues(values)) {
            "Purchase defaults require a finite non-negative cost, a THC fraction from 0 to 1, and positive grams."
        }

        val canonicalValues = values.copy(thc = requireNotNull(canonicalThcFraction(values.thc)))
        dataStore.edit { preferences ->
            val updatedDefaults = decodePurchaseDefaults(preferences[PURCHASE_DEFAULTS_JSON])
                .toMutableMap()
                .apply { put(key, canonicalValues) }
            preferences[PURCHASE_DEFAULTS_JSON] = encodePurchaseDefaults(updatedDefaults)
        }
    }

    suspend fun saveDefault(submission: PurchaseSubmission) {
        require(isValidPurchaseSubmission(submission)) { "Purchase submission is not valid for a saved default." }
        saveDefault(submission.defaultKey(), submission.defaultValues())
    }

    private fun encodePurchaseDefaults(
        defaults: Map<PurchaseDefaultKey, PurchaseDefaultValues>,
    ): String = purchaseDefaultsJsonAdapter.toJson(
        PersistedPurchaseDefaultsPayload(
            version = PURCHASE_DEFAULTS_PAYLOAD_VERSION,
            defaults = defaults.entries
                .sortedWith(
                    compareBy<Map.Entry<PurchaseDefaultKey, PurchaseDefaultValues>> { it.key.type }
                        .thenBy { it.key.name },
                )
                .map { (key, values) ->
                    PersistedPurchaseDefault(
                        type = key.type,
                        name = key.name,
                        cost = values.cost,
                        thc = values.thc,
                        grams = values.grams,
                        postTax = values.postTax,
                    )
                },
        ),
    )

    private val purchaseDefaultsJsonAdapter = moshi.adapter(PersistedPurchaseDefaultsPayload::class.java)

    private companion object {
        val PURCHASE_DEFAULTS_JSON = stringPreferencesKey(PURCHASE_DEFAULTS_JSON_KEY)
    }
}

internal fun decodePurchaseDefaults(payload: String?): Map<PurchaseDefaultKey, PurchaseDefaultValues> =
    runCatching {
        val decoded = PurchaseDefaultsJson.valueAdapter.fromJson(payload.orEmpty()) as? Map<*, *>
            ?: return emptyMap()
        val version = (decoded["version"] as? Number)?.toDouble()
        if (version != PURCHASE_DEFAULTS_PAYLOAD_VERSION.toDouble()) return emptyMap()
        val records = decoded["defaults"] as? List<*> ?: return emptyMap()

        records
            .fold(linkedMapOf<PurchaseDefaultKey, PurchaseDefaultValues>()) { defaults, record ->
                val fields = record as? Map<*, *> ?: return@fold defaults
                val key = PurchaseDefaultKey(
                    name = fields["name"] as? String ?: return@fold defaults,
                    type = fields["type"] as? String ?: return@fold defaults,
                )
                val thc = (fields["thc"] as? Number)?.toDouble()?.let(::canonicalThcFraction)
                val values = PurchaseDefaultValues(
                    cost = (fields["cost"] as? Number)?.toDouble() ?: Double.NaN,
                    thc = thc ?: Double.NaN,
                    grams = (fields["grams"] as? Number)?.toDouble() ?: Double.NaN,
                    postTax = fields["postTax"] as? Boolean,
                )
                if (isValidPurchaseDefaultKey(key) && isValidPurchaseDefaultValues(values)) {
                    defaults[key] = values
                }
                defaults
            }
            .entries
            .sortedWith(
                compareBy<Map.Entry<PurchaseDefaultKey, PurchaseDefaultValues>> { it.key.type }
                    .thenBy { it.key.name },
            )
            .associateTo(linkedMapOf()) { it.toPair() }
    }.getOrDefault(emptyMap())

private object PurchaseDefaultsJson {
    val valueAdapter = Moshi.Builder().build().adapter(Any::class.java)
}

@JsonClass(generateAdapter = true)
internal data class PersistedPurchaseDefaultsPayload(
    val version: Int? = null,
    val defaults: List<PersistedPurchaseDefault>? = null,
)

@JsonClass(generateAdapter = true)
internal data class PersistedPurchaseDefault(
    val type: String? = null,
    val name: String? = null,
    val cost: Double? = null,
    val thc: Double? = null,
    val grams: Double? = null,
    val postTax: Boolean? = null,
)
