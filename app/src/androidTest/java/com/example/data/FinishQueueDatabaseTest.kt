package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinishQueueDatabaseTest {
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
    fun pendingFinishSurvivesRefreshAndIsDeletedOnlyAfterAcknowledgement() = runBlocking {
        val product = Product("p1", "Blue Dream", "F", ProductStatus.ACTIVE.code)
        database.cannsheetDao().insertProducts(listOf(product))
        repository.addFinishAction(
            FinishAction("finish-1", "2026-07-22", "12:34", product.id),
        )

        assertEquals(ProductStatus.FINISHED.code, database.cannsheetDao().getAllProducts().first().single().status)

        repository.refreshProducts(listOf(product))

        assertEquals(ProductStatus.FINISHED.code, database.cannsheetDao().getAllProducts().first().single().status)
        assertEquals(listOf("finish-1"), repository.getPendingFinishActions().map(FinishAction::actionId))

        repository.applyAcknowledgements(
            SyncAcknowledgementPlan(acknowledgedFinishActionIds = setOf("finish-1")),
        )

        assertTrue(repository.getPendingFinishActions().isEmpty())
    }

    @Test
    fun acknowledgedPendingPurchaseRemapsQueuedFinishBeforePurchaseDeletion() = runBlocking {
        val purchase = PurchaseAction(
            tempId = "temp-1",
            actionId = "purchase-1",
            date = "2026-07-22",
            type = "F",
            name = "Blue Dream",
            cost = 0.0,
            thc = 0.0,
            grams = 0.0,
            borrowed = 0,
            postTax = false,
        )
        repository.addPurchase(purchase)
        repository.addFinishAction(
            FinishAction("finish-1", "2026-07-22", "12:34", purchase.tempId),
        )

        repository.applyAcknowledgements(
            SyncAcknowledgementPlan(
                acknowledgedPurchaseActionIds = setOf(purchase.actionId),
                purchaseRemaps = listOf(
                    PurchaseIdentityRemap(
                        actionId = purchase.actionId,
                        tempId = purchase.tempId,
                        legacyProductId = "*F1",
                        productUuid = "product-uuid",
                    ),
                ),
            ),
        )

        val queuedFinish = repository.getPendingFinishActions().single()
        assertEquals("*F1", queuedFinish.productId)
        assertEquals("product-uuid", queuedFinish.productUuid)
    }
}
