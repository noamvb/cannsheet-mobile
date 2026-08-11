package com.example.ui

import kotlinx.coroutines.CancellationException

internal sealed interface PurchasePersistenceResult {
    data class PurchaseFailed(val error: Throwable) : PurchasePersistenceResult

    data object PurchaseSaved : PurchasePersistenceResult

    data object PurchaseAndDefaultSaved : PurchasePersistenceResult

    data class PurchaseSavedDefaultFailed(val error: Throwable) : PurchasePersistenceResult
}

/**
 * Persists the local purchase before attempting the optional local default.
 *
 * The two writes are intentionally not one transaction: the purchase is the
 * durable queue item and must remain available even if the separate DataStore
 * write fails. DataStore's own edit operation remains atomic for the defaults
 * map.
 */
internal suspend fun persistPurchaseAndDefault(
    saveAsDefault: Boolean,
    persistPurchase: suspend () -> Unit,
    persistDefault: suspend () -> Unit,
): PurchasePersistenceResult {
    try {
        persistPurchase()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        return PurchasePersistenceResult.PurchaseFailed(error)
    }

    if (!saveAsDefault) {
        return PurchasePersistenceResult.PurchaseSaved
    }

    return try {
        persistDefault()
        PurchasePersistenceResult.PurchaseAndDefaultSaved
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        PurchasePersistenceResult.PurchaseSavedDefaultFailed(error)
    }
}
