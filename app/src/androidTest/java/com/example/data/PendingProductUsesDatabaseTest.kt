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
class PendingProductUsesDatabaseTest {
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
    fun pendingUsesAreSummedByProductAndRemovedAfterAcknowledgement() = runBlocking {
        val first = consumption("p1", "event-1", 0.25)
        val second = consumption("p1", "event-2", 0.5)
        val other = consumption("p2", "event-3", 1.0)
        repository.addConsumption(first)
        repository.addConsumption(second)
        repository.addConsumption(other)

        val totals = repository.pendingProductUses.first { it.size == 2 }
            .associate { it.productId to it.pendingUses }
        assertEquals(0.75, totals["p1"] ?: 0.0, 0.0)
        assertEquals(1.0, totals["p2"] ?: 0.0, 0.0)

        repository.applyAcknowledgements(
            SyncAcknowledgementPlan(
                acknowledgedConsumptionEventIds = setOf(first.eventId, second.eventId),
            ),
        )

        val remaining = repository.pendingProductUses.first { it.size == 1 }
        assertEquals("p2", remaining.single().productId)
        assertEquals(1.0, remaining.single().pendingUses, 0.0)
    }

    @Test
    fun pendingUsesFollowTemporaryProductRemapping() = runBlocking {
        val pending = consumption("temp-p1", "event-1", 1.5)
        repository.addConsumption(pending)

        database.cannsheetDao().remapPendingConsumptions(
            oldProductId = "temp-p1",
            newProductId = "*P1",
            productUuid = "product-uuid",
        )

        val totals = repository.pendingProductUses.first { it.isNotEmpty() }
        assertEquals("*P1", totals.single().productId)
        assertEquals(1.5, totals.single().pendingUses, 0.0)
        assertTrue(repository.getPendingConsumptions().single().productUuid == "product-uuid")
    }

    private fun consumption(productId: String, eventId: String, uses: Double) = ConsumptionAction(
        eventId = eventId,
        date = "2026-08-09",
        time = "12:00",
        productId = productId,
        uses = uses,
        isFinished = false,
    )
}
