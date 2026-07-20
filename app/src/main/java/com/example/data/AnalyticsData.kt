package com.example.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

const val ANALYTICS_VERSION = 1
const val HISTORY_PAGE_SIZE = 50
const val HISTORY_CACHE_LIMIT = 200

sealed interface InsightsRange {
    data object Default : InsightsRange
    data object All : InsightsRange
    data class Custom(val from: String, val to: String) : InsightsRange
}

@JsonClass(generateAdapter = true)
data class HistoryFilters(
    val from: String? = null,
    val to: String? = null,
    val productUuid: String? = null,
    val productId: String? = null,
    val type: String? = null,
    @Json(name = "q") val query: String? = null,
)

@JsonClass(generateAdapter = true)
data class AnalyticsEnvelope(
    val success: Boolean = false,
    val analyticsVersion: Int? = null,
    val resource: String? = null,
    val environment: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class AnalyticsRangeDto(val scope: String, val from: String, val to: String, val dayCount: Int)

@JsonClass(generateAdapter = true)
data class OverviewDto(
    val logCount: Int,
    val activeDayCount: Int,
    val distinctProductCount: Int,
    val firstLogAtEpochMillis: Long? = null,
    val lastLogAtEpochMillis: Long? = null,
    val daysSinceLastLog: Int? = null,
)

@JsonClass(generateAdapter = true)
data class DailyActivityDto(val date: String, val logCount: Int, val distinctProductCount: Int)

@JsonClass(generateAdapter = true)
data class WeekdayActivityDto(val isoDay: Int, val logCount: Int)

@JsonClass(generateAdapter = true)
data class HourActivityDto(val hour: Int, val logCount: Int)

@JsonClass(generateAdapter = true)
data class InventoryDto(
    val activeCount: Int,
    val unopenedCount: Int,
    val finishedCount: Int,
    val unknownStatusCount: Int,
    val currentPersonalOriginalCostCents: Long,
    val currentBorrowedRecordedValueCents: Long,
    val unknownCurrentCostCount: Int,
)

@JsonClass(generateAdapter = true)
data class TypeBreakdownDto(
    val type: String,
    val rangeLogCount: Int,
    val rangeDistinctProductCount: Int,
    val activeCount: Int,
    val unopenedCount: Int,
    val finishedCount: Int,
    val unknownStatusCount: Int,
    val personalSpendCents: Long,
    val personalPurchaseCount: Int,
    val borrowedRecordedValueCents: Long,
    val borrowedPurchaseCount: Int,
    val unknownCostCount: Int,
)

@JsonClass(generateAdapter = true)
data class ProductActivityDto(
    val logCount: Int,
    val quantity: Double,
    val activeDayCount: Int,
    val firstLogAtEpochMillis: Long? = null,
    val lastLogAtEpochMillis: Long? = null,
    val lastQuantity: Double? = null,
)

@JsonClass(generateAdapter = true)
data class ProductRangeActivityDto(val logCount: Int, val quantity: Double, val activeDayCount: Int)

@JsonClass(generateAdapter = true)
data class AnalyticsProductDto(
    val productUuid: String? = null,
    val productId: String,
    val name: String,
    val type: String,
    val status: String,
    val borrowed: Boolean? = null,
    val purchaseDate: String? = null,
    val purchaseDateSource: String,
    val preTaxCostCents: Long? = null,
    val finalCostCents: Long? = null,
    val grams: Double? = null,
    val thcRaw: Double? = null,
    val thcQuality: String,
    val latestFinishedLogAtEpochMillis: Long? = null,
    val daysSinceLastLog: Int? = null,
    val allTime: ProductActivityDto,
    val range: ProductRangeActivityDto,
    val costPerLogToDateCents: Long? = null,
    val costPerRecordedUnitToDateCents: Long? = null,
    val completedValueComparisonEligible: Boolean,
)

@JsonClass(generateAdapter = true)
data class SpendBucketDto(
    val personalSpendCents: Long,
    val personalPurchaseCount: Int,
    val borrowedRecordedValueCents: Long,
    val borrowedPurchaseCount: Int,
    val unknownPersonalCostCount: Int,
    val unknownBorrowedCostCount: Int,
    val estimatedDateCount: Int,
    val unknownDateCount: Int,
)

@JsonClass(generateAdapter = true)
data class MonthlySpendDto(
    val month: String,
    val personalSpendCents: Long,
    val personalPurchaseCount: Int,
    val borrowedRecordedValueCents: Long,
    val borrowedPurchaseCount: Int,
    val unknownPersonalCostCount: Int,
    val unknownBorrowedCostCount: Int,
    val estimatedDateCount: Int,
    val unknownDateCount: Int,
)

@JsonClass(generateAdapter = true)
data class SpendingDto(
    val allTime: SpendBucketDto,
    val range: SpendBucketDto,
    val byMonth: List<MonthlySpendDto>,
)

@JsonClass(generateAdapter = true)
data class SyncHealthDto(
    val coverage: String,
    val lastAcknowledgedAtEpochMillis: Long? = null,
    val lastResult: String? = null,
    val lastDurationMs: Long? = null,
    val acknowledgedRequestCount30d: Int,
    val partialRequestCount30d: Int,
    val medianDurationMs30d: Long? = null,
    val p95DurationMs30d: Long? = null,
)

@JsonClass(generateAdapter = true)
data class QualityWarningsDto(
    val estimatedPurchaseDateCount: Int = 0,
    val unknownPurchaseDateCount: Int = 0,
    val unknownPersonalCostCount: Int = 0,
    val unknownBorrowedCostCount: Int = 0,
    val ambiguousThcCount: Int = 0,
    val invalidThcCount: Int = 0,
    val invalidGramsCount: Int = 0,
    val unknownStatusCount: Int = 0,
    val unknownBorrowedFlagCount: Int = 0,
    val localDateMismatchCount: Int = 0,
    val localTimeMismatchCount: Int = 0,
    val unknownSourceCount: Int = 0,
    val invalidUnreferencedPurchaseRowCount: Int = 0,
)

@JsonClass(generateAdapter = true)
data class DataQualityDto(val complete: Boolean, val warnings: QualityWarningsDto)

@JsonClass(generateAdapter = true)
data class SourceRevisionDto(
    val dataVersion: String,
    val purchaseRowCount: Int,
    val eventRowCount: Int,
    val ledgerRowCount: Int? = null,
)

@JsonClass(generateAdapter = true)
data class InsightsResponseDto(
    val success: Boolean,
    val analyticsVersion: Int,
    val resource: String,
    val environment: String,
    val timeZone: String,
    val range: AnalyticsRangeDto,
    val overview: OverviewDto,
    val dailyActivity: List<DailyActivityDto>,
    val byWeekday: List<WeekdayActivityDto>,
    val byHour: List<HourActivityDto>,
    val inventory: InventoryDto,
    val byType: List<TypeBreakdownDto>,
    val products: List<AnalyticsProductDto>,
    val spending: SpendingDto,
    val syncHealth: SyncHealthDto,
    val dataQuality: DataQualityDto,
    val sourceRevision: SourceRevisionDto,
    val generatedAtEpochMillis: Long,
    val serverDurationMs: Long,
)

@JsonClass(generateAdapter = true)
data class HistoryEventDto(
    val eventUuid: String,
    val occurredAtEpochMillis: Long,
    val localDate: String,
    val localTime: String,
    val productUuid: String? = null,
    val productId: String,
    val productName: String,
    val productType: String,
    val quantity: Double,
    val weightCode: String? = null,
    val finished: Boolean,
    val source: String,
)

@JsonClass(generateAdapter = true)
data class HistoryPageDto(val limit: Int, val hasMore: Boolean, val nextCursor: String? = null)

@JsonClass(generateAdapter = true)
data class HistoryResponseDto(
    val success: Boolean,
    val analyticsVersion: Int,
    val resource: String,
    val environment: String,
    val timeZone: String,
    val filters: HistoryFilters,
    val sort: String,
    val events: List<HistoryEventDto>,
    val page: HistoryPageDto,
    val dataQuality: DataQualityDto,
    val sourceRevision: SourceRevisionDto,
    val generatedAtEpochMillis: Long,
    val serverDurationMs: Long,
)

class AnalyticsApiException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : IOException(message)

interface AnalyticsDataSource {
    suspend fun fetchInsights(range: InsightsRange): InsightsResponseDto

    suspend fun fetchHistory(
        filters: HistoryFilters,
        cursor: String? = null,
    ): HistoryResponseDto

    suspend fun saveHistory(filters: HistoryFilters, response: HistoryResponseDto)

    suspend fun readCachedInsights(): InsightsResponseDto?

    suspend fun readCachedHistory(): HistoryResponseDto?
}

class AnalyticsRepository(
    private val api: GasApiService,
    private val dao: CannsheetDao,
    private val moshi: Moshi,
    private val endpoint: String,
    private val environment: String,
) : AnalyticsDataSource {
    private val envelopeAdapter = moshi.adapter(AnalyticsEnvelope::class.java)
    private val insightsAdapter = moshi.adapter(InsightsResponseDto::class.java)
    private val historyAdapter = moshi.adapter(HistoryResponseDto::class.java)
    private val filtersAdapter = moshi.adapter(HistoryFilters::class.java)

    override suspend fun fetchInsights(range: InsightsRange): InsightsResponseDto =
        withContext(Dispatchers.IO) {
            fetchWithBusyRetry("insights") {
                val raw = request(buildInsightsUrl(range))
                val response = insightsAdapter.fromJson(raw)
                    ?: throw contractError("Empty Insights response")
                validateInsights(response)
                saveCache(
                    "insights",
                    rangeKey(range),
                    raw,
                    response.sourceRevision,
                    response.generatedAtEpochMillis,
                )
                response
            }
        }

    override suspend fun fetchHistory(
        filters: HistoryFilters,
        cursor: String?,
    ): HistoryResponseDto =
        withContext(Dispatchers.IO) {
            fetchWithBusyRetry("history") {
                val raw = request(buildHistoryUrl(filters, cursor))
                val response = historyAdapter.fromJson(raw)
                    ?: throw contractError("Empty History response")
                validateHistory(response)
                response
            }
        }

    override suspend fun saveHistory(filters: HistoryFilters, response: HistoryResponseDto) =
        withContext(Dispatchers.IO) {
            val cached = response.copy(
                events = response.events.distinctBy { it.eventUuid }.take(HISTORY_CACHE_LIMIT),
                page = response.page.copy(hasMore = false, nextCursor = null),
            )
            saveCache(
                resource = "history",
                requestJson = filtersAdapter.toJson(filters),
                payloadJson = historyAdapter.toJson(cached),
                revision = response.sourceRevision,
                generatedAt = response.generatedAtEpochMillis,
            )
        }

    override suspend fun readCachedInsights(): InsightsResponseDto? =
        readCache("insights") { insightsAdapter.fromJson(it)?.also(::validateInsights) }

    override suspend fun readCachedHistory(): HistoryResponseDto? =
        readCache("history") { historyAdapter.fromJson(it)?.also(::validateHistory) }

    private suspend fun request(url: String): String = withContext(Dispatchers.IO) {
        val raw = api.getAnalytics(url).string()
        if (raw.isBlank() || raw.trimStart().startsWith("<")) {
            throw AnalyticsApiException("INVALID_RESPONSE", "Analytics endpoint returned HTML or no JSON", false)
        }
        val envelope = runCatching { envelopeAdapter.fromJson(raw) }.getOrNull()
            ?: throw contractError("Malformed analytics response")
        if (!envelope.success) {
            val code = envelope.errorCode ?: "INTERNAL_ERROR"
            throw AnalyticsApiException(code, envelope.message ?: code, isRetryable(code))
        }
        if (
            envelope.analyticsVersion != ANALYTICS_VERSION ||
            envelope.environment != environment
        ) {
            throw AnalyticsApiException(
                "ENVIRONMENT_MISMATCH",
                "App and analytics backend do not match",
                false,
            )
        }
        raw
    }

    private fun buildInsightsUrl(range: InsightsRange): String {
        val builder = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("resource", "insights")
            .addQueryParameter("analyticsVersion", ANALYTICS_VERSION.toString())
            .addQueryParameter("environment", environment)
        when (range) {
            InsightsRange.Default -> Unit
            InsightsRange.All -> builder.addQueryParameter("scope", "all")
            is InsightsRange.Custom -> {
                require(DATE.matches(range.from) && DATE.matches(range.to) && range.from <= range.to)
                builder.addQueryParameter("from", range.from).addQueryParameter("to", range.to)
            }
        }
        return builder.build().toString()
    }

    private fun buildHistoryUrl(filters: HistoryFilters, cursor: String?): String {
        require(filters.query.orEmpty().trim().length <= 80)
        require(filters.productUuid.isNullOrBlank() || filters.productId.isNullOrBlank())
        val builder = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("resource", "history")
            .addQueryParameter("analyticsVersion", ANALYTICS_VERSION.toString())
            .addQueryParameter("environment", environment)
            .addQueryParameter("limit", HISTORY_PAGE_SIZE.toString())
        filters.from?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("from", it) }
        filters.to?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("to", it) }
        filters.productUuid?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("productUuid", it)
        } ?: filters.productId?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("productId", it)
        }
        filters.type?.trim()?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("type", it.uppercase())
        }
        filters.query?.trim()?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("q", it) }
        cursor?.let { builder.addQueryParameter("cursor", it) }
        return builder.build().toString()
    }

    private fun validateInsights(value: InsightsResponseDto) {
        requireCommon(value.success, value.analyticsVersion, value.resource, value.environment, "insights")
        require(value.range.dayCount in 1..3660)
        require(value.dailyActivity.size == value.range.dayCount)
        require(value.byWeekday.map { it.isoDay }.sorted() == (1..7).toList())
        require(value.byHour.map { it.hour }.sorted() == (0..23).toList())
        require(value.sourceRevision.dataVersion.matches(HASH))
        require(value.dailyActivity.all { it.logCount >= 0 && it.distinctProductCount >= 0 })
    }

    private fun validateHistory(value: HistoryResponseDto) {
        requireCommon(value.success, value.analyticsVersion, value.resource, value.environment, "history")
        require(value.sourceRevision.dataVersion.matches(HASH))
        require(value.events.all { it.eventUuid.isNotBlank() && it.quantity.isFinite() && it.quantity > 0 })
        require(!value.page.hasMore || !value.page.nextCursor.isNullOrBlank())
        require(value.page.nextCursor.orEmpty().length <= 1024)
    }

    private fun requireCommon(success: Boolean, version: Int, resource: String, actual: String, expected: String) {
        if (!success || version != ANALYTICS_VERSION || resource != expected || actual != environment) {
            throw contractError("Unexpected analytics contract")
        }
    }

    private suspend fun saveCache(
        resource: String,
        requestJson: String,
        payloadJson: String,
        revision: SourceRevisionDto,
        generatedAt: Long,
    ) {
        dao.upsertAnalyticsCache(
            AnalyticsCacheEntity(
                environment = environment,
                resource = resource,
                analyticsVersion = ANALYTICS_VERSION,
                requestJson = requestJson,
                payloadJson = payloadJson,
                sourceDataVersion = revision.dataVersion,
                generatedAtEpochMillis = generatedAt,
                cachedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun <T> readCache(resource: String, parse: (String) -> T?): T? =
        withContext(Dispatchers.IO) {
            val cache = dao.getAnalyticsCache(environment, resource) ?: return@withContext null
            if (cache.analyticsVersion != ANALYTICS_VERSION) {
                dao.deleteAnalyticsCache(environment, resource)
                return@withContext null
            }
            runCatching { parse(cache.payloadJson) }.getOrNull().also {
                if (it == null) dao.deleteAnalyticsCache(environment, resource)
            }
        }

    private suspend fun <T> fetchWithBusyRetry(resource: String, block: suspend () -> T): T {
        repeat(2) { attempt ->
            try {
                return block()
            } catch (error: AnalyticsApiException) {
                if (error.code != "BACKEND_BUSY" || attempt == 1) throw error
                delay(1_000)
            }
        }
        throw contractError("Unable to load $resource")
    }

    private fun rangeKey(range: InsightsRange): String = when (range) {
        InsightsRange.Default -> """{"kind":"default"}"""
        InsightsRange.All -> """{"kind":"all"}"""
        is InsightsRange.Custom -> """{"kind":"custom","from":"${range.from}","to":"${range.to}"}"""
    }

    private fun contractError(message: String) =
        AnalyticsApiException("INVALID_RESPONSE", message, false)

    private fun isRetryable(code: String) = code in setOf("BACKEND_BUSY", "INTERNAL_ERROR")

    private companion object {
        val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
        val HASH = Regex("""[0-9a-f]{64}""")
    }
}
