package com.example.data

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionLoggerTest {
    @Test
    fun penConsumptionSetsLoadedProductId() = runBlocking {
        val repository = RecordingRepository()
        val preferences = RecordingLoadedPenStore()

        ConsumptionLogger(repository, preferences).log(
            date = "2026-08-12",
            time = "12:00",
            productId = "pen-1",
            productUuid = "uuid-1",
            productType = " p ",
            uses = 3.0,
            isFinished = false,
        )

        assertEquals("pen-1", preferences.loadedProductId)
        assertEquals(3.0, repository.actions.single().uses, 0.0)
        assertTrue(UUID.fromString(repository.actions.single().eventId).version() == 4)
    }

    @Test
    fun finishedPenConsumptionClearsLoadedProductId() = runBlocking {
        val preferences = RecordingLoadedPenStore().apply { loadedProductId = "pen-1" }

        ConsumptionLogger(RecordingRepository(), preferences).log(
            date = "2026-08-12",
            time = "12:00",
            productId = "pen-1",
            productUuid = null,
            productType = "P",
            uses = 1.0,
            isFinished = true,
        )

        assertNull(preferences.loadedProductId)
    }

    @Test
    fun nonPenConsumptionDoesNotTouchLoadedProduct() = runBlocking {
        val preferences = RecordingLoadedPenStore().apply { loadedProductId = "pen-1" }

        ConsumptionLogger(RecordingRepository(), preferences).log(
            date = "2026-08-12",
            time = "12:00",
            productId = "flower-1",
            productUuid = null,
            productType = "F",
            uses = 2.0,
            isFinished = false,
        )

        assertEquals("pen-1", preferences.loadedProductId)
        assertEquals(0, preferences.mutationCount)
    }

    @Test
    fun eachLogGetsAFreshEventId() = runBlocking {
        val repository = RecordingRepository()
        val logger = ConsumptionLogger(repository, RecordingLoadedPenStore())

        repeat(2) {
            logger.log(
                date = "2026-08-12",
                time = "12:00",
                productId = "flower-1",
                productUuid = null,
                productType = "F",
                uses = 1.0,
                isFinished = false,
            )
        }

        assertNotEquals(repository.actions[0].eventId, repository.actions[1].eventId)
    }

    private class RecordingRepository : ConsumptionLogRepository {
        val actions = mutableListOf<ConsumptionAction>()

        override suspend fun addConsumption(action: ConsumptionAction, loggedAtEpochMillis: Long) {
            actions += action
        }
    }

    private class RecordingLoadedPenStore : LoadedPenProductStore {
        var loadedProductId: String? = null
        var mutationCount = 0

        override suspend fun setLoadedPenProductId(productId: String) {
            mutationCount += 1
            loadedProductId = productId
        }

        override suspend fun clearLoadedPenProductId() {
            mutationCount += 1
            loadedProductId = null
        }
    }
}
