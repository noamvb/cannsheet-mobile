package com.example.ui

import com.example.data.AnalyticsApiException
import com.example.data.AnalyticsDataSource
import com.example.data.HISTORY_CACHE_LIMIT
import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.HistoryResponseDto
import com.example.data.InsightsRange
import com.example.data.InsightsResponseDto
import com.example.data.MAX_CONSUMPTION_CORRECTION_REASON_LENGTH
import com.example.data.cachedInsightsRange
import com.example.data.runCatchingCancellable
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalyticsUiError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

data class InsightsUiState(
    val data: InsightsResponseDto? = null,
    val displayedRange: InsightsRange = InsightsRange.Default,
    val pendingRange: InsightsRange? = null,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isFromCache: Boolean = false,
    val isStale: Boolean = false,
    val lastUpdatedEpochMillis: Long? = null,
    val error: AnalyticsUiError? = null,
)

data class HistoryUiState(
    val events: List<HistoryEventDto> = emptyList(),
    val appliedFilters: HistoryFilters = HistoryFilters(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val hasFreshCursor: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isFromCache: Boolean = false,
    val isStale: Boolean = false,
    val generatedAtEpochMillis: Long? = null,
    val response: HistoryResponseDto? = null,
    val error: AnalyticsUiError? = null,
    val appendError: AnalyticsUiError? = null,
)

/**
 * A user-entered correction before it is given a durable action ID and queued.
 *
 * Keeping this separate from the Room entity means the History UI can validate
 * the user's choices without ever treating an in-progress edit as committed.
 */
data class HistoryCorrectionDraft(
    val targetEventId: String,
    val expectedHeadId: String?,
    val operation: HistoryCorrectionOperation,
    val replacementDate: String? = null,
    val replacementTime: String? = null,
    val replacementProductId: String? = null,
    val replacementProductUuid: String? = null,
    val replacementQuantity: Double? = null,
    val replacementFinished: Boolean? = null,
    val reopenProduct: Boolean = false,
    val reason: String? = null,
)

enum class HistoryCorrectionOperation {
    REPLACE,
    VOID,
    RESTORE,
}

data class HistoryCorrectionUiState(
    val queuedTargetIds: Set<String> = emptySet(),
    val status: String? = null,
    val statusTargetEventId: String? = null,
)

data class HistoryCorrectionFeedback(
    val targetEventId: String,
    val message: String,
)

data class HistoryCorrectionAvailability(
    val canCorrect: Boolean,
    val canVoid: Boolean,
    val canRestore: Boolean,
    val reason: String? = null,
)

/**
 * Corrections are deliberately restricted to a current server snapshot. A
 * cached page can be useful to read, but its correction head can be stale.
 */
fun historyCorrectionAvailability(
    state: HistoryUiState,
    event: HistoryEventDto,
    queuedTargetIds: Set<String>,
): HistoryCorrectionAvailability {
    val disabledReason = when {
        event.eventUuid in queuedTargetIds ->
            "A correction for this entry is already queued. Wait for it to sync before making another change."
        state.isFromCache || state.isStale || state.response == null || !state.hasFreshCursor ->
            "Refresh History before editing so this entry is current."
        state.response.correctionVersion < 1 ->
            "This server does not support history corrections yet."
        !state.response.correctionWritesEnabled ->
            "History corrections are not enabled on this server yet."
        else -> null
    }
    if (disabledReason != null) {
        return HistoryCorrectionAvailability(false, false, false, disabledReason)
    }
    val isVoided = event.lifecycleState == "VOIDED"
    return HistoryCorrectionAvailability(
        canCorrect = !isVoided,
        canVoid = !isVoided,
        canRestore = isVoided,
    )
}

fun correctionRejectionMessage(code: String, fallback: String? = null): String = when (code) {
    "CORRECTION_CONFLICT" ->
        "This entry changed elsewhere. Refresh History before trying again."
    "REOPEN_NOT_SAFE" ->
        "This product cannot be reopened safely, so the correction was not accepted."
    "BACKEND_UPDATE_REQUIRED", "CORRECTION_WRITES_DISABLED" ->
        "History corrections are not enabled on this server yet."
    else -> fallback ?: "This correction was not accepted. It is still saved locally for review."
}

fun isCorrectionDraftValid(draft: HistoryCorrectionDraft): Boolean {
    val identityValid = isUuid(draft.targetEventId) &&
        (draft.expectedHeadId == null || isUuid(draft.expectedHeadId))
    return identityValid && when (draft.operation) {
        HistoryCorrectionOperation.REPLACE ->
            isCorrectionDate(draft.replacementDate) &&
            draft.replacementTime?.matches(Regex("""(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?""")) == true &&
            !draft.replacementProductId.isNullOrBlank() &&
            draft.replacementProductUuid?.let(::isUuid) == true &&
            draft.replacementQuantity?.let { it.isFinite() && it > 0.0 } == true &&
            draft.replacementFinished != null &&
            (draft.reason?.length ?: 0) <= MAX_CONSUMPTION_CORRECTION_REASON_LENGTH
        HistoryCorrectionOperation.VOID,
        HistoryCorrectionOperation.RESTORE,
        -> (draft.reason?.length ?: 0) <= MAX_CONSUMPTION_CORRECTION_REASON_LENGTH
    }
}

private fun isUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString().equals(value, ignoreCase = true)
}.getOrDefault(false)

private fun isCorrectionDate(value: String?): Boolean = value?.let { date ->
    if (!date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return@let false
    runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(date)
    }.isSuccess
} ?: false

fun canOfferProductReopen(
    event: HistoryEventDto,
    replacementProductId: String,
    replacementFinished: Boolean,
): Boolean = event.reopenEligible && event.finished &&
    (!replacementFinished || replacementProductId != event.productId)

class AnalyticsCoordinator(
    private val repository: AnalyticsDataSource,
    private val scope: CoroutineScope,
) {
    private val _insights = MutableStateFlow(InsightsUiState())
    val insights: StateFlow<InsightsUiState> = _insights

    private val _history = MutableStateFlow(HistoryUiState())
    val history: StateFlow<HistoryUiState> = _history

    private var insightsJob: Job? = null
    private var historyRefreshJob: Job? = null
    private var historyAppendJob: Job? = null
    private var insightsLoaded = false
    private var historyLoaded = false
    private var visible = false
    private var historyVisible = false
    private var historyGeneration = 0L
    private var staleCursorRestarted = false

    fun onVisible() {
        visible = true
        if (!insightsLoaded) loadInsightsCacheThenRefresh()
        else if (_insights.value.isStale) refreshInsights(_insights.value.displayedRange)
    }

    fun onHidden() {
        visible = false
        historyVisible = false
    }

    fun onHistoryVisible() {
        historyVisible = true
        if (!historyLoaded) loadHistoryCacheThenRefresh()
        else if (_history.value.isStale) refreshHistory(_history.value.appliedFilters)
    }

    fun onOverviewVisible() {
        historyVisible = false
        if (_insights.value.isStale) refreshInsights(_insights.value.displayedRange)
    }

    fun refreshInsights(range: InsightsRange = _insights.value.displayedRange) {
        insightsJob?.cancel()
        insightsJob = scope.launch {
            val hadData = _insights.value.data != null
            _insights.update {
                it.copy(
                    pendingRange = range,
                    isInitialLoading = !hadData,
                    isRefreshing = hadData,
                    error = null,
                )
            }
            runCatchingCancellable { repository.fetchInsights(range) }
                .onSuccess { response ->
                    insightsLoaded = true
                    _insights.value = InsightsUiState(
                        data = response,
                        displayedRange = range,
                        isFromCache = false,
                        isStale = false,
                        lastUpdatedEpochMillis = response.generatedAtEpochMillis,
                    )
                }
                .onFailure { error ->
                    _insights.update {
                        it.copy(
                            pendingRange = null,
                            isInitialLoading = false,
                            isRefreshing = false,
                            isStale = it.data != null,
                            error = analyticsUiError(error),
                        )
                    }
                }
        }
    }

    fun refreshHistory(filters: HistoryFilters = _history.value.appliedFilters) {
        refreshHistoryPage(filters, resetCursorRecovery = true)
    }

    private fun refreshHistoryPage(
        filters: HistoryFilters,
        resetCursorRecovery: Boolean,
    ) {
        historyRefreshJob?.cancel()
        historyAppendJob?.cancel()
        val generation = ++historyGeneration
        if (resetCursorRecovery) staleCursorRestarted = false
        historyRefreshJob = scope.launch {
            val hadData = _history.value.events.isNotEmpty()
            _history.update {
                it.copy(
                    nextCursor = null,
                    hasFreshCursor = false,
                    isInitialLoading = !hadData,
                    isRefreshing = hadData,
                    isLoadingMore = false,
                    error = null,
                    appendError = null,
                )
            }
            runCatchingCancellable { repository.fetchHistory(filters) }
                .onSuccess { response ->
                    if (generation != historyGeneration) return@onSuccess
                    historyLoaded = true
                    repository.saveHistory(filters, response)
                    _history.value = stateFromResponse(response, filters)
                }
                .onFailure { error ->
                    if (generation != historyGeneration) return@onFailure
                    _history.update {
                        it.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            isStale = it.events.isNotEmpty(),
                            error = analyticsUiError(error),
                        )
                    }
                }
        }
    }

    fun loadMoreHistory() {
        val current = _history.value
        val cursor = current.nextCursor
        if (
            current.isInitialLoading ||
            current.isRefreshing ||
            current.isLoadingMore ||
            !current.hasMore ||
            !current.hasFreshCursor ||
            cursor.isNullOrBlank()
        ) return
        val generation = historyGeneration
        _history.update { it.copy(isLoadingMore = true, appendError = null) }
        historyAppendJob = scope.launch {
            runCatchingCancellable { repository.fetchHistory(current.appliedFilters, cursor) }
                .onSuccess { response ->
                    if (generation != historyGeneration) return@onSuccess
                    val firstVersion = current.response?.sourceRevision?.dataVersion
                    if (firstVersion != null && firstVersion != response.sourceRevision.dataVersion) {
                        restartStaleCursor()
                        return@onSuccess
                    }
                    val combined = (current.events + response.events)
                        .distinctBy(HistoryEventDto::eventUuid)
                    val combinedResponse = response.copy(events = combined.take(HISTORY_CACHE_LIMIT))
                    repository.saveHistory(current.appliedFilters, combinedResponse)
                    staleCursorRestarted = false
                    _history.update {
                        it.copy(
                            events = combined,
                            nextCursor = response.page.nextCursor,
                            hasMore = response.page.hasMore,
                            hasFreshCursor = true,
                            isLoadingMore = false,
                            generatedAtEpochMillis = response.generatedAtEpochMillis,
                            response = combinedResponse,
                        )
                    }
                }
                .onFailure { error ->
                    if (generation != historyGeneration) return@onFailure
                    if (
                        error is AnalyticsApiException &&
                        error.code in setOf("CURSOR_STALE", "INVALID_CURSOR")
                    ) {
                        restartStaleCursor()
                    } else {
                        _history.update {
                            it.copy(isLoadingMore = false, appendError = analyticsUiError(error))
                        }
                    }
                }
        }
    }

    fun markStale() {
        _insights.update { it.copy(isStale = true) }
        _history.update {
            it.copy(isStale = true, nextCursor = null, hasFreshCursor = false)
        }
        if (visible) {
            if (historyVisible) refreshHistory(_history.value.appliedFilters)
            else refreshInsights(_insights.value.displayedRange)
        }
    }

    private fun loadInsightsCacheThenRefresh() {
        insightsLoaded = true
        scope.launch {
            _insights.update { it.copy(isInitialLoading = true) }
            repository.readCachedInsights()?.let { cached ->
                val range = cached.cachedInsightsRange()
                _insights.value = InsightsUiState(
                    data = cached,
                    displayedRange = range,
                    isFromCache = true,
                    isStale = true,
                    lastUpdatedEpochMillis = cached.generatedAtEpochMillis,
                )
            }
            refreshInsights(_insights.value.displayedRange)
        }
    }

    private fun loadHistoryCacheThenRefresh() {
        historyLoaded = true
        scope.launch {
            _history.update { it.copy(isInitialLoading = true) }
            repository.readCachedHistory()?.let { cached ->
                _history.value = stateFromResponse(cached, cached.filters).copy(
                    isFromCache = true,
                    isStale = true,
                    hasMore = false,
                    hasFreshCursor = false,
                    nextCursor = null,
                )
            }
            refreshHistory(_history.value.appliedFilters)
        }
    }

    private fun stateFromResponse(
        response: HistoryResponseDto,
        filters: HistoryFilters,
    ) = HistoryUiState(
        events = response.events.distinctBy(HistoryEventDto::eventUuid),
        appliedFilters = filters,
        nextCursor = response.page.nextCursor,
        hasMore = response.page.hasMore,
        hasFreshCursor = true,
        isFromCache = false,
        isStale = false,
        generatedAtEpochMillis = response.generatedAtEpochMillis,
        response = response,
    )

    private fun restartStaleCursor() {
        if (staleCursorRestarted) {
            _history.update {
                it.copy(
                    isLoadingMore = false,
                    appendError = AnalyticsUiError(
                        "CURSOR_STALE",
                        "History changed again. Refresh to continue.",
                        true,
                    ),
                )
            }
            return
        }
        staleCursorRestarted = true
        refreshHistoryPage(
            filters = _history.value.appliedFilters,
            resetCursorRecovery = false,
        )
    }
}

fun analyticsUiError(error: Throwable): AnalyticsUiError {
    if (error is AnalyticsApiException) {
        val message = when (error.code) {
            "BACKEND_BUSY" -> "The backend is busy. Try again shortly."
            "DATA_INTEGRITY_ERROR" -> "Some Sheet data must be corrected before analytics can refresh."
            "ENVIRONMENT_MISMATCH", "UNSUPPORTED_ANALYTICS_VERSION", "UNSUPPORTED_RESOURCE" ->
                "This app and the analytics backend do not match."
            "SCHEMA_MISMATCH", "CONFIGURATION_ERROR" ->
                "The analytics backend needs setup attention."
            "RANGE_TOO_LARGE", "INVALID_QUERY" -> "Check the selected dates or filters."
            else -> error.message
        }
        return AnalyticsUiError(error.code, message, error.retryable)
    }
    return when (error) {
        is SocketTimeoutException -> AnalyticsUiError("TIMEOUT", "Analytics took too long. Try again.", true)
        is IOException -> AnalyticsUiError("OFFLINE", "No connection. Showing saved data when available.", true)
        else -> AnalyticsUiError("INTERNAL_ERROR", error.message ?: "Could not load analytics.", true)
    }
}

fun customRangeForDays(days: Int, anchor: String): InsightsRange.Custom {
    require(days in 1..3660)
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        time = requireNotNull(formatter.parse(anchor))
        add(Calendar.DAY_OF_MONTH, -(days - 1))
    }
    return InsightsRange.Custom(formatter.format(calendar.time), anchor)
}
