package com.example.data

const val DEFAULT_LOOKBACK_MILLIS: Long = 10L * 24L * 60L * 60L * 1000L

private val TIME_WITH_SECONDS_REGEX = Regex("""^(\d{2}:\d{2}):\d{2}.*$""")

internal fun truncateTimeToMinutes(time: String): String {
    val match = TIME_WITH_SECONDS_REGEX.matchEntire(time)
    return match?.groupValues?.get(1) ?: time
}

fun interface ConsumptionHistorySink {
    suspend fun insertConsumptionHistoryIfAbsent(entries: List<ConsumptionHistoryEntry>): List<Long>
}

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
        sink = dao::insertConsumptionHistoryIfAbsent,
        lookbackMillis = lookbackMillis,
        now = now,
    )

    /** Inserts rows for events not yet in consumption_history. Returns the number inserted. */
    suspend fun ingest(events: List<HistoryEventDto>): Int {
        val cutoffEpochMillis = now() - lookbackMillis
        val candidateEntries = events
            .filter { it.lifecycleState != HISTORY_LIFECYCLE_VOIDED }
            .filter { it.occurredAtEpochMillis >= cutoffEpochMillis }
            .map { event ->
                ConsumptionHistoryEntry(
                    eventId = event.eventUuid,
                    date = event.localDate,
                    time = truncateTimeToMinutes(event.localTime),
                    productId = event.productId,
                    productUuid = event.productUuid,
                    uses = event.quantity,
                    isFinished = event.finished,
                    loggedAtEpochMillis = event.occurredAtEpochMillis,
                )
            }
        if (candidateEntries.isEmpty()) {
            return 0
        }
        val insertedIds = sink.insertConsumptionHistoryIfAbsent(candidateEntries)
        return insertedIds.count { it >= 0L }
    }
}
