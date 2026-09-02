package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal const val TAX_RATE_DATA_STORE_NAME = "tax_rate"
internal const val CATALOG_TAX_RATE_KEY = "catalog_tax_rate"

private val Context.taxRateDataStore by preferencesDataStore(
    name = TAX_RATE_DATA_STORE_NAME,
)

private val TAX_RATE = doublePreferencesKey(CATALOG_TAX_RATE_KEY)

internal fun isStorableTaxRate(rate: Double?): Boolean =
    rate != null && rate.isFinite() && rate >= 0.0 && rate < 1.0

class TaxRateRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.taxRateDataStore)

    val taxRate: Flow<Double?> = dataStore.data
        .map { stored -> stored[TAX_RATE].takeIf(::isStorableTaxRate) }
        .distinctUntilChanged()

    /**
     * Records only valid rates. An older backend sends no rate, and clearing a rate already
     * learned would silently remove the preview from a screen that was working moments earlier.
     */
    suspend fun record(rate: Double?) {
        val storable = rate?.takeIf(::isStorableTaxRate) ?: return
        dataStore.edit { stored -> stored[TAX_RATE] = storable }
    }
}
