package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerHistoryIngestorTest {

    private class FakeConsumptionHistorySink : ConsumptionHistorySink {
        val rows = mutableListOf<ConsumptionHistoryEntry>()
        private var nextRowId = 1L

        override suspend fun insertConsumptionHistoryIfAbsent(entries: List<ConsumptionHistoryEntry>): List<Long> {
            return entries.map { entry ->
                if (rows.any { it.eventId == entry.eventId }) {
                    -1L
                } else {
                    rows.add(entry)
                    nextRowId++
                }
            }
        }
    }

    private fun createEvent(
        eventUuid: String,
        occurredAtEpochMillis: Long = 1789617669000L,
        localDate: String = "2026-09-17",
        localTime: String = "00:01:09",
        productId: String = "*P115",
        productUuid: String? = "70cd8751-ed77-4979-9392-c1c0b5b8792b",
        quantity: Double = 1.0,
        finished: Boolean = false,
        source: String = "ANDROID_V2",
        lifecycleState: String = HISTORY_LIFECYCLE_ORIGINAL,
    ): HistoryEventDto = HistoryEventDto(
        eventUuid = eventUuid,
        occurredAtEpochMillis = occurredAtEpochMillis,
        localDate = localDate,
        localTime = localTime,
        productId = productId,
        productUuid = productUuid,
        productName = "Product $productId",
        productType = "PEN",
        quantity = quantity,
        finished = finished,
        source = source,
        lifecycleState = lifecycleState,
    )

    @Test
    fun ingest_twoUnknownEvents_insertsBothAndMapsFieldsExactly() = runBlocking {
        val fakeSink = FakeConsumptionHistorySink()
        val ingestor = ServerHistoryIngestor(sink = fakeSink, now = { 1789617669000L })
        val event1 = createEvent(
            eventUuid = "1f9c1d43-531b-4ab0-85c2-f0b408505e92",
            occurredAtEpochMillis = 1789617669000L,
            localDate = "2026-09-17",
            localTime = "00:01:09",
            productId = "*P115",
            productUuid = "70cd8751-ed77-4979-9392-c1c0b5b8792b",
            quantity = 1.0,
            finished = false,
        )
        val event2 = createEvent(
            eventUuid = "2a8b3c4d-531b-4ab0-85c2-f0b408505e93",
            occurredAtEpochMillis = 1789617669000L,
            localDate = "2026-09-17",
            localTime = "00:02:15",
            productId = "*P116",
            productUuid = "80cd8751-ed77-4979-9392-c1c0b5b8792c",
            quantity = 2.0,
            finished = true,
        )

        val count = ingestor.ingest(listOf(event1, event2))

        assertEquals(2, count)
        assertEquals(2, fakeSink.rows.size)
        val row = fakeSink.rows.first { it.eventId == "1f9c1d43-531b-4ab0-85c2-f0b408505e92" }
        assertEquals("1f9c1d43-531b-4ab0-85c2-f0b408505e92", row.eventId)
        assertEquals("2026-09-17", row.date)
        assertEquals("00:01", row.time)
        assertEquals("*P115", row.productId)
        assertEquals("70cd8751-ed77-4979-9392-c1c0b5b8792b", row.productUuid)
        assertEquals(1.0, row.uses, 0.0)
        assertEquals(false, row.isFinished)
        assertEquals(1789617669000L, row.loggedAtEpochMillis)
    }

    @Test
    fun ingest_sameTwoEventsAgain_returnsZeroAndSinkStillHoldsTwoRows() = runBlocking {
        val fakeSink = FakeConsumptionHistorySink()
        val ingestor = ServerHistoryIngestor(sink = fakeSink, now = { 1789617669000L })
        val event1 = createEvent(
            eventUuid = "1f9c1d43-531b-4ab0-85c2-f0b408505e92",
            occurredAtEpochMillis = 1789617669000L,
        )
        val event2 = createEvent(
            eventUuid = "2a8b3c4d-531b-4ab0-85c2-f0b408505e93",
            occurredAtEpochMillis = 1789617669000L,
        )
        val events = listOf(event1, event2)

        val firstResult = ingestor.ingest(events)
        assertEquals(2, firstResult)
        assertEquals(2, fakeSink.rows.size)

        val secondResult = ingestor.ingest(events)
        assertEquals(0, secondResult)
        assertEquals(2, fakeSink.rows.size)
    }

    @Test
    fun ingest_voidedEventSkipped_originalEventInserted() = runBlocking {
        val fakeSink = FakeConsumptionHistorySink()
        val ingestor = ServerHistoryIngestor(sink = fakeSink, now = { 1789617669000L })
        val voidedEvent = createEvent(
            eventUuid = "voided-event-id",
            occurredAtEpochMillis = 1789617669000L,
            lifecycleState = HISTORY_LIFECYCLE_VOIDED,
        )
        val originalEvent = createEvent(
            eventUuid = "original-event-id",
            occurredAtEpochMillis = 1789617669000L,
            lifecycleState = HISTORY_LIFECYCLE_ORIGINAL,
        )

        val count = ingestor.ingest(listOf(voidedEvent, originalEvent))

        assertEquals(1, count)
        assertEquals(1, fakeSink.rows.size)
        assertEquals("original-event-id", fakeSink.rows[0].eventId)
    }

    @Test
    fun ingest_olderThanLookbackSkipped_insideLookbackInserted() = runBlocking {
        val fakeSink = FakeConsumptionHistorySink()
        val nowMillis = 1789617669000L
        val lookbackMillis = 10L * 24L * 60L * 60L * 1000L
        val ingestor = ServerHistoryIngestor(
            sink = fakeSink,
            lookbackMillis = lookbackMillis,
            now = { nowMillis },
        )
        val cutoff = nowMillis - lookbackMillis
        val olderEvent = createEvent(
            eventUuid = "older-event-id",
            occurredAtEpochMillis = cutoff - 1L,
        )
        val recentEvent = createEvent(
            eventUuid = "recent-event-id",
            occurredAtEpochMillis = cutoff + 1000L,
        )

        val count = ingestor.ingest(listOf(olderEvent, recentEvent))

        assertEquals(1, count)
        assertEquals(1, fakeSink.rows.size)
        assertEquals("recent-event-id", fakeSink.rows[0].eventId)
    }

    @Test
    fun runHistorySavedHook_forwardsEventsToHook() = runBlocking {
        var receivedEvents: List<HistoryEventDto>? = null
        val events = listOf(createEvent(eventUuid = "event-5a"))

        runHistorySavedHook(
            hook = { receivedEvents = it },
            events = events,
        )

        assertEquals(events, receivedEvents)
    }

    @Test
    fun runHistorySavedHook_swallowsIllegalStateException() = runBlocking {
        val events = listOf(createEvent(eventUuid = "event-5b"))

        runHistorySavedHook(
            hook = { throw IllegalStateException("Hook crashed") },
            events = events,
        )
    }

    @Test
    fun runHistorySavedHook_propagatesCancellationException() {
        val events = listOf(createEvent(eventUuid = "event-5c"))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                runHistorySavedHook(
                    hook = { throw CancellationException("Coroutine cancelled") },
                    events = events,
                )
            }
        }
    }

    @Test
    fun truncateTimeToMinutes_truncatesSecondsAndLeavesShortOrMalformedUnchanged() = runBlocking {
        assertEquals("23:59", truncateTimeToMinutes("23:59:59"))
        assertEquals("07:05", truncateTimeToMinutes("07:05"))
        assertEquals("7:05:00", truncateTimeToMinutes("7:05:00"))

        val fakeSink = FakeConsumptionHistorySink()
        val ingestor = ServerHistoryIngestor(sink = fakeSink, now = { 1789617669000L })
        val events = listOf(
            createEvent(eventUuid = "e1", localTime = "23:59:59", occurredAtEpochMillis = 1789617669000L),
            createEvent(eventUuid = "e2", localTime = "07:05", occurredAtEpochMillis = 1789617669000L),
            createEvent(eventUuid = "e3", localTime = "7:05:00", occurredAtEpochMillis = 1789617669000L),
        )
        val inserted = ingestor.ingest(events)
        assertEquals(3, inserted)
        assertEquals("23:59", fakeSink.rows[0].time)
        assertEquals("07:05", fakeSink.rows[1].time)
        assertEquals("7:05:00", fakeSink.rows[2].time)
    }
}
