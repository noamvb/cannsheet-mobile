package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BorrowedConsumptionDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CannsheetRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CannsheetRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun borrowedConsumptionQueuesLinkedRowsAndCreatesReusableActiveProduct() = runBlocking {
        val purchase = borrowedPurchase()
        val consumption = linkedConsumption(purchase.tempId)

        repository.addBorrowedConsumption(
            purchase = purchase,
            consumption = consumption,
            loggedAtEpochMillis = 123_456L,
        )

        assertEquals(listOf(purchase), repository.getPendingPurchases())
        val queuedConsumption = repository.getPendingConsumptions().single()
        assertEquals(consumption.eventId, queuedConsumption.eventId)
        assertEquals(consumption.productId, queuedConsumption.productId)
        assertEquals(consumption.uses, queuedConsumption.uses, 0.0)

        val product = repository.allProducts.first().single()
        assertEquals(purchase.tempId, product.id)
        assertEquals(purchase.name, product.name)
        assertEquals(ProductStatus.ACTIVE.code, product.status)

        val interaction = repository.productInteractions.first().single()
        assertEquals(purchase.tempId, interaction.productId)
        assertEquals(consumption.uses, interaction.lastQuantity, 0.0)
        assertEquals(123_456L, interaction.lastLoggedAtEpochMillis)
    }

    @Test
    fun purchaseAcknowledgementRemapsLinkedBorrowedConsumptionBeforeDeletingPurchase() = runBlocking {
        val purchase = borrowedPurchase()
        val consumption = linkedConsumption(purchase.tempId)
        repository.addBorrowedConsumption(purchase, consumption, loggedAtEpochMillis = 123_456L)

        repository.applyAcknowledgements(
            SyncAcknowledgementPlan(
                acknowledgedPurchaseActionIds = setOf(purchase.actionId),
                purchaseRemaps = listOf(
                    PurchaseIdentityRemap(
                        actionId = purchase.actionId,
                        tempId = purchase.tempId,
                        legacyProductId = "*F1B",
                        productUuid = "borrowed-product-uuid",
                    ),
                ),
            ),
        )

        assertTrue(repository.getPendingPurchases().isEmpty())
        val remappedConsumption = repository.getPendingConsumptions().single()
        assertEquals("*F1B", remappedConsumption.productId)
        assertEquals("borrowed-product-uuid", remappedConsumption.productUuid)

        val interaction = repository.productInteractions.first().single()
        assertEquals("*F1B", interaction.productId)
        assertNull(repository.allProducts.first().firstOrNull { it.id == purchase.tempId })
    }

    private fun borrowedPurchase() = PurchaseAction(
        tempId = "temp-borrowed",
        actionId = "purchase-borrowed",
        date = "2026-07-22",
        type = "F",
        name = "Borrowed flower",
        cost = null,
        thc = null,
        grams = null,
        borrowed = 1,
        postTax = false,
    )

    private fun linkedConsumption(productId: String) = ConsumptionAction(
        eventId = "consumption-borrowed",
        date = "2026-07-22",
        time = "21:15",
        productId = productId,
        uses = 1.5,
        isFinished = false,
    )
}
