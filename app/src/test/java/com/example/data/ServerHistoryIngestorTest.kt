package com.example.data

import com.example.domain.currentSubmissionDateTime
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

        override suspend fun upsertConsumptionHistory(entries: List<ConsumptionHistoryEntry>) {
            entries.forEach { entry ->
                rows.removeAll { it.eventId == entry.eventId }
                rows.add(entry)
            }
        }

        override suspend fun deleteConsumptionHistoryByEventIds(eventIds: List<String>) {
            rows.removeAll { it.eventId in eventIds }
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

    private fun historyResponse(events: List<HistoryEventDto>) = HistoryResponseDto(
        success = true,
        analyticsVersion = 2,
        resource = "history",
        environment = "PRODUCTION",
        timeZone = "America/New_York",
        filters = HistoryFilters(),
        sort = "TIMESTAMP_DESC_CANONICAL_ROW_DESC",
        events = events,
        page = HistoryPageDto(limit = 50, hasMore = false, nextCursor = null),
        dataQuality = DataQualityDto(complete = true, warnings = QualityWarningsDto()),
        sourceRevision = SourceRevisionDto(dataVersion = "a".repeat(64), purchaseRowCount = 1, eventRowCount = events.size),
        generatedAtEpochMillis = 1789617669000L,
        serverDurationMs = 1,
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
        val expected = currentSubmissionDateTime(1789617669000L)
        assertEquals(expected.date, row.date)
        assertEquals(expected.time, row.time)
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

        assertEquals(2, count) // one insert, one (no-op) delete
        assertEquals(1, fakeSink.rows.size)
        assertEquals("original-event-id", fakeSink.rows[0].eventId)
    }

    @Test
    fun ingest_voidedEventRemovesTheRowItPreviouslyInserted() = runBlocking {
        val fakeSink = FakeConsumptionHistorySink()
        val ingestor = ServerHistoryIngestor(sink = fakeSink, now = { 1789617669000L })
        val event = createEvent(eventUuid = "later-voided", occurredAtEpochMillis = 1789617669000L)
        ingestor.ingest(listOf(event))
        assertEquals(1, fakeSink.rows.size) // positive control: the row exists first

        ingestor.ingest(listOf(event.copy(lifecycleState = HISTORY_LIFECYCLE_VOIDED)))

        assertEquals(0, fakeSink.rows.size)
    }

    @Test
    fun ingest_correctedEventReplacesTheRowWithTheServerValues() = runBlocking {
        val fakeSink = FakeConsumptionHistorySink()
        val ingestor = ServerHistoryIngestor(sink = fakeSink, now = { 1789617669000L })
        val event = createEvent(eventUuid = "corrected", occurredAtEpochMillis = 1789617669000L, quantity = 1.0)
        ingestor.ingest(listOf(event))
        // An ORIGINAL re-send never rewrites the row...
        ingestor.ingest(listOf(event.copy(quantity = 5.0)))
        assertEquals(1.0, fakeSink.rows.single().uses, 0.0)

        // ...a CORRECTED one does, keeping the same eventId.
        val changed = ingestor.ingest(listOf(event.copy(quantity = 2.5, lifecycleState = HISTORY_LIFECYCLE_CORRECTED)))

        assertEquals(1, changed)
        assertEquals(2.5, fakeSink.rows.single().uses, 0.0)
        assertEquals("corrected", fakeSink.rows.single().eventId)
    }

    @Test
    fun refresh_fetchesTheUnfilteredLookbackWindowAndIngestsIt() = runBlocking {
        val fakeSink = FakeConsumptionHistorySink()
        val now = 1789617669000L
        val ingestor = ServerHistoryIngestor(sink = fakeSink, lookbackMillis = DEFAULT_LOOKBACK_MILLIS, now = { now })
        var requested: HistoryFilters? = null
        val source = object : AnalyticsDataSource {
            override suspend fun fetchInsights(range: InsightsRange): InsightsResponseDto = error("unused")
            override suspend fun fetchHistory(filters: HistoryFilters, cursor: String?): HistoryResponseDto {
                requested = filters
                return historyResponse(listOf(createEvent(eventUuid = "from-server", occurredAtEpochMillis = now)))
            }
            override suspend fun saveHistory(filters: HistoryFilters, response: HistoryResponseDto) = Unit
            override suspend fun readCachedInsights(): InsightsResponseDto? = null
            override suspend fun readCachedHistory(): HistoryResponseDto? = null
        }

        val changed = ingestor.refresh(source)

        assertEquals(1, changed)
        assertEquals("from-server", fakeSink.rows.single().eventId)
        // Only a date window: no product, type or text filter can hide a panel event.
        val filters = requireNotNull(requested)
        assertEquals(null, filters.productUuid)
        assertEquals(null, filters.productId)
        assertEquals(null, filters.type)
        assertEquals(null, filters.query)
        assertEquals(currentSubmissionDateTime(now).date, filters.to)
        assertEquals(currentSubmissionDateTime(now - DEFAULT_LOOKBACK_MILLIS).date, filters.from)
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
    fun ingest_storesDateAndTimeInTheDeviceCalendarNotTheSheetTimezone() = runBlocking {
        val fakeSink = FakeConsumptionHistorySink()
        val ingestor = ServerHistoryIngestor(sink = fakeSink, now = { 1789617669000L })
        // The sheet says one thing; the row must say what the device's clock says for the instant.
        val event = createEvent(
            eventUuid = "e1",
            occurredAtEpochMillis = 1789617669000L,
            localDate = "1999-01-01",
            localTime = "23:59:59",
        )

        ingestor.ingest(listOf(event))

        val expected = currentSubmissionDateTime(1789617669000L)
        assertEquals(expected.date, fakeSink.rows.single().date)
        assertEquals(expected.time, fakeSink.rows.single().time)
        assertEquals(5, expected.time.length) // HH:mm, the layout ConsumptionLogger writes
    }
}
