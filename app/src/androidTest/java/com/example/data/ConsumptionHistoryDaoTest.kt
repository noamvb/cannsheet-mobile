package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsumptionHistoryDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: CannsheetDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.cannsheetDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertingTheSameEventIdTwiceKeepsOneRow() = runBlocking {
        val original = entry(eventId = "event-1", loggedAtEpochMillis = 100L, uses = 1.0)
        val replay = original.copy(loggedAtEpochMillis = 900L, uses = 9.0)

        dao.insertConsumptionHistory(original)
        dao.insertConsumptionHistory(replay)

        val rows = dao.consumptionHistorySince(0L).first()
        assertEquals(1, rows.size)
        assertEquals(original, rows.single())
    }

    @Test
    fun historySinceReturnsOnlyEntriesAtOrAfterTheCutoff() = runBlocking {
        dao.insertConsumptionHistory(entry("before", loggedAtEpochMillis = 100L))
        dao.insertConsumptionHistory(entry("at-cutoff", loggedAtEpochMillis = 200L))
        dao.insertConsumptionHistory(entry("after", loggedAtEpochMillis = 300L))

        val rows = dao.consumptionHistorySince(200L).first()

        assertEquals(listOf("after", "at-cutoff"), rows.map(ConsumptionHistoryEntry::eventId))
    }

    @Test
    fun historyIsOrderedNewestFirst() = runBlocking {
        dao.insertConsumptionHistory(entry("oldest", loggedAtEpochMillis = 100L))
        dao.insertConsumptionHistory(entry("newest", loggedAtEpochMillis = 300L))
        dao.insertConsumptionHistory(entry("middle", loggedAtEpochMillis = 200L))

        val rows = dao.consumptionHistorySince(0L).first()

        assertEquals(listOf("newest", "middle", "oldest"), rows.map(ConsumptionHistoryEntry::eventId))
    }

    @Test
    fun pruneRemovesOnlyOlderEntries() = runBlocking {
        dao.insertConsumptionHistory(entry("old", loggedAtEpochMillis = 100L))
        dao.insertConsumptionHistory(entry("at-cutoff", loggedAtEpochMillis = 200L))
        dao.insertConsumptionHistory(entry("new", loggedAtEpochMillis = 300L))

        val removedCount = dao.pruneConsumptionHistoryBefore(200L)
        val rows = dao.consumptionHistorySince(0L).first()

        assertEquals(1, removedCount)
        assertEquals(listOf("new", "at-cutoff"), rows.map(ConsumptionHistoryEntry::eventId))
    }

    private fun entry(
        eventId: String,
        loggedAtEpochMillis: Long,
        uses: Double = 1.0,
    ) = ConsumptionHistoryEntry(
        eventId = eventId,
        date = "2026-08-20",
        time = "12:00",
        productId = "*P1",
        productUuid = "product-uuid",
        uses = uses,
        isFinished = false,
        loggedAtEpochMillis = loggedAtEpochMillis,
    )
}
