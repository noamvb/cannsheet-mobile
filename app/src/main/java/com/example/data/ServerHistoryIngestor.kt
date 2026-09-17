package com.example.data

import com.example.domain.currentSubmissionDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DEFAULT_LOOKBACK_MILLIS: Long = 10L * 24L * 60L * 60L * 1000L

/**
 * The narrow slice of the DAO the ingestor needs, so JVM tests can use an in-memory fake instead
 * of implementing every member of [CannsheetDao].
 */
interface ConsumptionHistorySink {
    /** Room returns -1 for every row the unique eventId index made it ignore. */
    suspend fun insertConsumptionHistoryIfAbsent(entries: List<ConsumptionHistoryEntry>): List<Long>

    suspend fun upsertConsumptionHistory(entries: List<ConsumptionHistoryEntry>)

    suspend fun deleteConsumptionHistoryByEventIds(eventIds: List<String>)
}

/**
 * Mirrors server-side consumption events into `consumption_history`, the table the Today widget
 * reads, so an event logged outside the phone (the panel posts straight to the backend) counts.
 *
 * Lifecycle drives the write: an ORIGINAL event is inserted only if its eventId is unknown, so a
 * locally logged row is never rewritten; a CORRECTED event keeps its eventId and the server is
 * authoritative for its values, so it is upserted; a VOIDED event no longer counts, so its row is
 * removed. Date and time are derived from the instant in the device's calendar, matching the rows
 * [ConsumptionLogger] writes, rather than copied from the sheet's timezone.
 */
class ServerHistoryIngestor(
    private val sink: ConsumptionHistorySink,
    private val lookbackMillis: Long = DEFAULT_LOOKBACK_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        dao: CannsheetDao,
        lookbackMillis: Long = DEFAULT_LOOKBACK_MILLIS,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        sink = object : ConsumptionHistorySink {
            override suspend fun insertConsumptionHistoryIfAbsent(entries: List<ConsumptionHistoryEntry>) =
                dao.insertConsumptionHistoryIfAbsent(entries)

            override suspend fun upsertConsumptionHistory(entries: List<ConsumptionHistoryEntry>) =
                dao.upsertConsumptionHistory(entries)

            override suspend fun deleteConsumptionHistoryByEventIds(eventIds: List<String>) =
                dao.deleteConsumptionHistoryByEventIds(eventIds)
        },
        lookbackMillis = lookbackMillis,
        now = now,
    )

    /**
     * Applies [events] to the local history. Returns the number of rows changed: inserted,
     * upserted, or deleted.
     */
    suspend fun ingest(events: List<HistoryEventDto>): Int {
        val cutoffEpochMillis = now() - lookbackMillis
        val recent = events.filter { it.occurredAtEpochMillis >= cutoffEpochMillis }

        val voidedIds = recent
            .filter { it.lifecycleState == HISTORY_LIFECYCLE_VOIDED }
            .map(HistoryEventDto::eventUuid)
        val corrected = recent
            .filter { it.lifecycleState == HISTORY_LIFECYCLE_CORRECTED }
            .map(::toEntry)
        val original = recent
            .filter { it.lifecycleState != HISTORY_LIFECYCLE_VOIDED && it.lifecycleState != HISTORY_LIFECYCLE_CORRECTED }
            .map(::toEntry)

        var changed = 0
        if (voidedIds.isNotEmpty()) {
            sink.deleteConsumptionHistoryByEventIds(voidedIds)
            changed += voidedIds.size
        }
        if (corrected.isNotEmpty()) {
            sink.upsertConsumptionHistory(corrected)
            changed += corrected.size
        }
        if (original.isNotEmpty()) {
            changed += sink.insertConsumptionHistoryIfAbsent(original).count { it >= 0L }
        }
        return changed
    }

    /** Fetches the unfiltered lookback window from the server and ingests it. */
    suspend fun refresh(source: AnalyticsDataSource): Int {
        val filters = HistoryFilters(
            from = isoDate(now() - lookbackMillis),
            to = isoDate(now()),
        )
        return ingest(source.fetchHistory(filters).events)
    }

    private fun toEntry(event: HistoryEventDto): ConsumptionHistoryEntry {
        val local = currentSubmissionDateTime(event.occurredAtEpochMillis)
        return ConsumptionHistoryEntry(
            eventId = event.eventUuid,
            date = local.date,
            time = local.time,
            productId = event.productId,
            productUuid = event.productUuid,
            uses = event.quantity,
            isFinished = event.finished,
            loggedAtEpochMillis = event.occurredAtEpochMillis,
        )
    }

    private fun isoDate(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))
}
