package com.example.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchasePersistenceTest {
    @Test
    fun purchaseIsPersistedBeforeDefault() = runBlocking {
        val events = mutableListOf<String>()

        val result = persistPurchaseAndDefault(
            saveAsDefault = true,
            persistPurchase = { events += "purchase" },
            persistDefault = { events += "default" },
        )

        assertEquals(listOf("purchase", "default"), events)
        assertTrue(result is PurchasePersistenceResult.PurchaseAndDefaultSaved)
    }

    @Test
    fun uncheckedPurchaseDoesNotWriteDefault() = runBlocking {
        val events = mutableListOf<String>()

        val result = persistPurchaseAndDefault(
            saveAsDefault = false,
            persistPurchase = { events += "purchase" },
            persistDefault = { events += "default" },
        )

        assertEquals(listOf("purchase"), events)
        assertTrue(result is PurchasePersistenceResult.PurchaseSaved)
    }

    @Test
    fun purchaseFailurePreventsDefaultAndReportsFailure() = runBlocking {
        val events = mutableListOf<String>()
        val error = IllegalStateException("room unavailable")

        val result = persistPurchaseAndDefault(
            saveAsDefault = true,
            persistPurchase = {
                events += "purchase"
                throw error
            },
            persistDefault = { events += "default" },
        )

        assertEquals(listOf("purchase"), events)
        assertTrue(result is PurchasePersistenceResult.PurchaseFailed)
        assertEquals(error, (result as PurchasePersistenceResult.PurchaseFailed).error)
    }

    @Test
    fun defaultFailureDoesNotRetryPurchase() = runBlocking {
        val events = mutableListOf<String>()
        val error = IllegalStateException("datastore unavailable")

        val result = persistPurchaseAndDefault(
            saveAsDefault = true,
            persistPurchase = { events += "purchase" },
            persistDefault = {
                events += "default"
                throw error
            },
        )

        assertEquals(listOf("purchase", "default"), events)
        assertTrue(result is PurchasePersistenceResult.PurchaseSavedDefaultFailed)
        assertEquals(error, (result as PurchasePersistenceResult.PurchaseSavedDefaultFailed).error)
    }
}
