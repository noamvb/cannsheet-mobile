package com.example.data

import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsDataTest {

    private val moshi: Moshi = Moshi.Builder().build()
    private val endpoint = "https://script.google.com/macros/s/test/exec"
    private val validHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun sampleInsightsJson(
        success: Boolean = true,
        analyticsVersion: Int = 2,
        resource: String = "insights",
        environment: String = "PRODUCTION",
        dataVersion: String = validHash,
        dayCount: Int = 2,
        dailyActivitySize: Int = 2,
        isoDays: List<Int> = (1..7).toList(),
        hours: List<Int> = (0..23).toList(),
    ): String {
        val dailyActivity = (1..dailyActivitySize).joinToString(",") { i ->
            """{"date":"2026-07-0$i","logCount":1,"distinctProductCount":1}"""
        }
        val byWeekday = isoDays.joinToString(",") { day ->
            """{"isoDay":$day,"logCount":0}"""
        }
        val byHour = hours.joinToString(",") { hour ->
            """{"hour":$hour,"logCount":0}"""
        }
        return """
        {
          "success": $success,
          "analyticsVersion": $analyticsVersion,
          "resource": "$resource",
          "environment": "$environment",
          "correctionVersion": 1,
          "correctionWritesEnabled": true,
          "timeZone": "America/New_York",
          "range": {
            "scope": "CUSTOM",
            "from": "2026-07-01",
            "to": "2026-07-02",
            "dayCount": $dayCount
          },
          "overview": {
            "logCount": 2,
            "activeDayCount": 2,
            "distinctProductCount": 1,
            "firstLogAtEpochMillis": 1000,
            "lastLogAtEpochMillis": 2000,
            "daysSinceLastLog": 0
          },
          "dailyActivity": [$dailyActivity],
          "byWeekday": [$byWeekday],
          "byHour": [$byHour],
          "inventory": {
            "activeCount": 1,
            "unopenedCount": 0,
            "finishedCount": 0,
            "unknownStatusCount": 0,
            "currentPersonalOriginalCostCents": 1000,
            "currentBorrowedRecordedValueCents": 0,
            "unknownCurrentCostCount": 0
          },
          "byType": [],
          "products": [],
          "spending": {
            "allTime": {
              "personalSpendCents": 1000,
              "personalPurchaseCount": 1,
              "borrowedRecordedValueCents": 0,
              "borrowedPurchaseCount": 0,
              "unknownPersonalCostCount": 0,
              "unknownBorrowedCostCount": 0,
              "estimatedDateCount": 0,
              "unknownDateCount": 0
            },
            "range": {
              "personalSpendCents": 1000,
              "personalPurchaseCount": 1,
              "borrowedRecordedValueCents": 0,
              "borrowedPurchaseCount": 0,
              "unknownPersonalCostCount": 0,
              "unknownBorrowedCostCount": 0,
              "estimatedDateCount": 0,
              "unknownDateCount": 0
            },
            "byMonth": []
          },
          "syncHealth": {
            "coverage": "SERVER_ACKNOWLEDGED_REQUESTS_ONLY",
            "acknowledgedRequestCount30d": 1,
            "partialRequestCount30d": 0
          },
          "dataQuality": {
            "complete": true,
            "warnings": {}
          },
          "sourceRevision": {
            "dataVersion": "$dataVersion",
            "purchaseRowCount": 1,
            "eventRowCount": 2
          },
          "generatedAtEpochMillis": 3000,
          "serverDurationMs": 42
        }
        """.trimIndent()
    }

    private fun sampleHistoryJson(
        success: Boolean = true,
        analyticsVersion: Int = 2,
        resource: String = "history",
        environment: String = "PRODUCTION",
        dataVersion: String = validHash,
        quantity: Double = 1.0,
        lifecycleState: String = "ORIGINAL",
        revision: Int = 0,
        hasMore: Boolean = false,
        nextCursor: String? = null,
    ): String {
        val cursorJson = if (nextCursor != null) """"$nextCursor"""" else "null"
        return """
        {
          "success": $success,
          "analyticsVersion": $analyticsVersion,
          "resource": "$resource",
          "environment": "$environment",
          "correctionVersion": 1,
          "correctionWritesEnabled": true,
          "timeZone": "America/New_York",
          "filters": {},
          "sort": "TIMESTAMP_DESC",
          "events": [
            {
              "eventUuid": "00000000-0000-4000-8000-000000000001",
              "occurredAtEpochMillis": 1000,
              "localDate": "2026-07-01",
              "localTime": "12:00:00",
              "productId": "*P1",
              "productName": "Alpha",
              "productType": "FLOWER",
              "quantity": $quantity,
              "finished": false,
              "source": "ANDROID_V2",
              "lifecycleState": "$lifecycleState",
              "revision": $revision,
              "auditRevisions": [
                {
                  "correctionId": "corr-1",
                  "revision": 1,
                  "operation": "REPLACE",
                  "reopenProduct": false,
                  "createdAtEpochMillis": 1500
                }
              ]
            }
          ],
          "page": {
            "limit": 50,
            "hasMore": $hasMore,
            "nextCursor": $cursorJson
          },
          "dataQuality": {
            "complete": true,
            "warnings": {}
          },
          "sourceRevision": {
            "dataVersion": "$dataVersion",
            "purchaseRowCount": 1,
            "eventRowCount": 1
          },
          "generatedAtEpochMillis": 2000,
          "serverDurationMs": 20
        }
        """.trimIndent()
    }

    @Test
    fun testInsightsResponseDtoMoshiDeserialization() {
        val adapter = moshi.adapter(InsightsResponseDto::class.java)
        val json = sampleInsightsJson()
        val dto = adapter.fromJson(json)

        assertNotNull(dto)
        assertEquals(2, dto!!.analyticsVersion)
        assertEquals("insights", dto.resource)
        assertEquals("PRODUCTION", dto.environment)
        assertEquals(2, dto.range.dayCount)
        assertEquals(2, dto.overview.logCount)
        assertEquals(1000L, dto.spending.allTime.personalSpendCents)
        assertEquals(validHash, dto.sourceRevision.dataVersion)
        assertEquals(3000L, dto.generatedAtEpochMillis)
        assertEquals(42L, dto.serverDurationMs)
    }

    @Test
    fun testHistoryResponseDtoMoshiDeserializationWithAuditRevisions() {
        val adapter = moshi.adapter(HistoryResponseDto::class.java)
        val json = sampleHistoryJson(hasMore = true, nextCursor = "cursor-abc")
        val dto = adapter.fromJson(json)

        assertNotNull(dto)
        assertEquals(2, dto!!.analyticsVersion)
        assertEquals("history", dto.resource)
        assertEquals(1, dto.events.size)
        val event = dto.events.first()
        assertEquals("00000000-0000-4000-8000-000000000001", event.eventUuid)
        assertEquals("ORIGINAL", event.lifecycleState)
        assertEquals(1, event.auditRevisions.size)
        assertEquals("corr-1", event.auditRevisions.first().correctionId)
        assertEquals("REPLACE", event.auditRevisions.first().operation)
        assertTrue(dto.page.hasMore)
        assertEquals("cursor-abc", dto.page.nextCursor)
    }

    @Test
    fun testAnalyticsEnvelopeErrorHandling() = runBlocking {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return """{"success":false,"errorCode":"BACKEND_BUSY","message":"Script lock is busy"}"""
                    .toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val exception = assertThrows(AnalyticsApiException::class.java) {
            runBlocking { repo.fetchInsights(InsightsRange.Default) }
        }
        assertEquals("BACKEND_BUSY", exception.code)
        assertTrue(exception.retryable)
    }

    @Test
    fun testEnvironmentMismatchThrowsNonRetryableException() = runBlocking {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return sampleInsightsJson(environment = "SANDBOX")
                    .toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val exception = assertThrows(AnalyticsApiException::class.java) {
            runBlocking { repo.fetchInsights(InsightsRange.Default) }
        }
        assertEquals("ENVIRONMENT_MISMATCH", exception.code)
        assertFalse(exception.retryable)
    }

    @Test
    fun testInvalidDataVersionFormatRejected(): Unit {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return sampleInsightsJson(dataVersion = "not-a-valid-sha256")
                    .toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val unused = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.fetchInsights(InsightsRange.Default) }
        }
    }

    @Test
    fun testDailyActivitySizeMismatchThrowsContractError(): Unit {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return sampleInsightsJson(dayCount = 5, dailyActivitySize = 3)
                    .toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val unused = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.fetchInsights(InsightsRange.Default) }
        }
    }

    @Test
    fun testIncompleteWeekdayOrHourListThrowsContractError(): Unit {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                // Only 6 weekdays
                return sampleInsightsJson(isoDays = listOf(1, 2, 3, 4, 5, 6))
                    .toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val unused = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.fetchInsights(InsightsRange.Default) }
        }
    }

    @Test
    fun testZeroQuantityEventThrowsContractError(): Unit {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return sampleHistoryJson(quantity = 0.0)
                    .toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val unused = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.fetchHistory(HistoryFilters()) }
        }
    }

    @Test
    fun testUnknownLifecycleStateThrowsContractError(): Unit {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return sampleHistoryJson(lifecycleState = "DELETED")
                    .toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val unused = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.fetchHistory(HistoryFilters()) }
        }
    }

    @Test
    fun testCursorLongerThan1024CharsThrowsContractError(): Unit {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                val longCursor = "a".repeat(1025)
                return sampleHistoryJson(hasMore = true, nextCursor = longCursor)
                    .toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val unused = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.fetchHistory(HistoryFilters()) }
        }
    }

    @Test
    fun testHtmlErrorThrowsInvalidResponse() = runBlocking {
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return "<html><body>Google Login Required</body></html>"
                    .toResponseBody("text/html".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, FakeCannsheetDao(), moshi, endpoint, "PRODUCTION")

        val exception = assertThrows(AnalyticsApiException::class.java) {
            runBlocking { repo.fetchInsights(InsightsRange.Default) }
        }
        assertEquals("INVALID_RESPONSE", exception.code)
        assertFalse(exception.retryable)
    }

    @Test
    fun testAnalyticsCacheUpsertAndRead() = runBlocking {
        val dao = FakeCannsheetDao()
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return sampleInsightsJson().toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(fakeApi, dao, moshi, endpoint, "PRODUCTION")

        // Fetch should write to cache
        val fetched = repo.fetchInsights(InsightsRange.Default)
        assertNotNull(fetched)

        // Read cache should return the cached value
        val cached = repo.readCachedInsights()
        assertNotNull(cached)
        assertEquals(fetched.sourceRevision.dataVersion, cached!!.sourceRevision.dataVersion)
        assertEquals(fetched.overview.logCount, cached.overview.logCount)
    }

    @Test
    fun testInsightsCacheWriteNotifiesAfterDurableSave() = runBlocking {
        val dao = FakeCannsheetDao()
        var notificationCount = 0
        val fakeApi = object : FakeGasApiService() {
            override suspend fun getAnalytics(url: String): ResponseBody {
                return sampleInsightsJson().toResponseBody("application/json".toMediaTypeOrNull())
            }
        }
        val repo = AnalyticsRepository(
            api = fakeApi,
            dao = dao,
            moshi = moshi,
            endpoint = endpoint,
            environment = "PRODUCTION",
            onInsightsCacheSaved = {
                assertNotNull(dao.getAnalyticsCache("PRODUCTION", "insights"))
                notificationCount++
            },
        )

        repo.fetchInsights(InsightsRange.Default)

        assertEquals(1, notificationCount)
    }

    @Test
    fun testAnalyticsCacheEvictionOnVersionMismatch() = runBlocking {
        val dao = FakeCannsheetDao()
        dao.upsertAnalyticsCache(
            AnalyticsCacheEntity(
                environment = "PRODUCTION",
                resource = "insights",
                analyticsVersion = 1, // Outdated version
                requestJson = """{"kind":"default"}""",
                payloadJson = sampleInsightsJson(analyticsVersion = 1),
                sourceDataVersion = validHash,
                generatedAtEpochMillis = 1000L,
                cachedAtEpochMillis = 1000L,
            ),
        )
        val repo = AnalyticsRepository(FakeGasApiService(), dao, moshi, endpoint, "PRODUCTION")

        val cached = repo.readCachedInsights()
        assertNull(cached)
        assertNull(dao.getAnalyticsCache("PRODUCTION", "insights"))
    }

    @Test
    fun testAnalyticsCacheCorruptJsonSelfHeals() = runBlocking {
        val dao = FakeCannsheetDao()
        dao.upsertAnalyticsCache(
            AnalyticsCacheEntity(
                environment = "PRODUCTION",
                resource = "insights",
                analyticsVersion = 2,
                requestJson = """{"kind":"default"}""",
                payloadJson = "{corrupted json",
                sourceDataVersion = validHash,
                generatedAtEpochMillis = 1000L,
                cachedAtEpochMillis = 1000L,
            ),
        )
        val repo = AnalyticsRepository(FakeGasApiService(), dao, moshi, endpoint, "PRODUCTION")

        val cached = repo.readCachedInsights()
        assertNull(cached)
        assertNull(dao.getAnalyticsCache("PRODUCTION", "insights"))
    }

    @Test
    fun testAnalyticsCacheEnvironmentIsolation() = runBlocking {
        val dao = FakeCannsheetDao()
        dao.upsertAnalyticsCache(
            AnalyticsCacheEntity(
                environment = "SANDBOX",
                resource = "insights",
                analyticsVersion = 2,
                requestJson = """{"kind":"default"}""",
                payloadJson = sampleInsightsJson(environment = "SANDBOX"),
                sourceDataVersion = validHash,
                generatedAtEpochMillis = 1000L,
                cachedAtEpochMillis = 1000L,
            ),
        )
        val repo = AnalyticsRepository(FakeGasApiService(), dao, moshi, endpoint, "PRODUCTION")

        val cached = repo.readCachedInsights()
        assertNull(cached)
    }

    @Test
    fun testHistoryCacheLimitTruncation() = runBlocking {
        val dao = FakeCannsheetDao()
        val events = (1..250).map { i ->
            HistoryEventDto(
                eventUuid = "uuid-$i",
                occurredAtEpochMillis = i.toLong(),
                localDate = "2026-07-01",
                localTime = "12:00:00",
                productId = "*P1",
                productName = "Alpha",
                productType = "FLOWER",
                quantity = 1.0,
                finished = false,
                source = "ANDROID_V2",
            )
        }
        val historyResponse = HistoryResponseDto(
            success = true,
            analyticsVersion = 2,
            resource = "history",
            environment = "PRODUCTION",
            timeZone = "America/New_York",
            filters = HistoryFilters(),
            sort = "TIMESTAMP_DESC",
            events = events,
            page = HistoryPageDto(limit = 250, hasMore = true, nextCursor = "cursor-next"),
            dataQuality = DataQualityDto(complete = true, warnings = QualityWarningsDto()),
            sourceRevision = SourceRevisionDto(dataVersion = validHash, purchaseRowCount = 1, eventRowCount = 250),
            generatedAtEpochMillis = 1000L,
            serverDurationMs = 10L,
        )
        val repo = AnalyticsRepository(FakeGasApiService(), dao, moshi, endpoint, "PRODUCTION")

        repo.saveHistory(HistoryFilters(), historyResponse)

        val cached = repo.readCachedHistory()
        assertNotNull(cached)
        assertEquals(HISTORY_CACHE_LIMIT, cached!!.events.size)
        assertFalse(cached.page.hasMore)
        assertNull(cached.page.nextCursor)
    }

    @Test
    fun testCachedInsightsRangeMapping() {
        val defaultDto = moshi.adapter(InsightsResponseDto::class.java).fromJson(
            sampleInsightsJson().replace(""""scope": "CUSTOM"""", """"scope": "DEFAULT""""),
        )!!
        assertEquals(InsightsRange.Default, defaultDto.cachedInsightsRange())

        val allDto = moshi.adapter(InsightsResponseDto::class.java).fromJson(
            sampleInsightsJson().replace(""""scope": "CUSTOM"""", """"scope": "ALL""""),
        )!!
        assertEquals(InsightsRange.All, allDto.cachedInsightsRange())

        val customDto = moshi.adapter(InsightsResponseDto::class.java).fromJson(sampleInsightsJson())!!
        assertEquals(InsightsRange.Custom("2026-07-01", "2026-07-02"), customDto.cachedInsightsRange())
    }
}

private open class FakeGasApiService : GasApiService {
    override suspend fun getProducts(url: String): ResponseBody = throw NotImplementedError()
    override suspend fun getAnalytics(url: String): ResponseBody = throw NotImplementedError()
    override suspend fun syncData(url: String, payload: SyncPayload): ResponseBody = throw NotImplementedError()
}

private class FakeCannsheetDao : CannsheetDao {
    private val cacheMap = mutableMapOf<Pair<String, String>, AnalyticsCacheEntity>()

    override suspend fun getAnalyticsCache(environment: String, resource: String): AnalyticsCacheEntity? {
        return cacheMap[environment to resource]
    }

    override suspend fun upsertAnalyticsCache(cache: AnalyticsCacheEntity) {
        cacheMap[cache.environment to cache.resource] = cache
    }

    override suspend fun deleteAnalyticsCache(environment: String, resource: String) {
        cacheMap.remove(environment to resource)
    }

    override fun getAllProducts(): Flow<List<Product>> = emptyFlow()
    override suspend fun insertProducts(products: List<Product>) = Unit
    override suspend fun deleteAllProducts() = Unit
    override suspend fun deleteProduct(productId: String) = Unit
    override suspend fun insertPurchase(action: PurchaseAction) = Unit
    override suspend fun getPendingPurchases(): List<PurchaseAction> = emptyList()
    override suspend fun deletePurchasesByActionIds(actionIds: List<String>) = Unit
    override suspend fun insertConsumption(action: ConsumptionAction) = Unit
    override suspend fun getPendingConsumptions(): List<ConsumptionAction> = emptyList()
    override fun getPendingProductUses(): Flow<List<PendingProductUses>> = emptyFlow()
    override suspend fun deleteConsumptionsByEventIds(eventIds: List<String>) = Unit
    override suspend fun insertConsumptionHistory(entry: ConsumptionHistoryEntry) = Unit
    override fun consumptionHistorySince(fromEpochMillis: Long): Flow<List<ConsumptionHistoryEntry>> = emptyFlow()
    override suspend fun pruneConsumptionHistoryBefore(beforeEpochMillis: Long): Int = 0
    override suspend fun insertFinishAction(action: FinishAction) = Unit
    override suspend fun getPendingFinishActions(): List<FinishAction> = emptyList()
    override suspend fun deleteFinishActionsByActionIds(actionIds: List<String>) = Unit
    override suspend fun insertPendingConsumptionCorrection(action: PendingConsumptionCorrection) = Unit
    override fun getPendingConsumptionCorrectionsFlow(): Flow<List<PendingConsumptionCorrection>> = emptyFlow()
    override suspend fun getPendingConsumptionCorrections(): List<PendingConsumptionCorrection> = emptyList()
    override suspend fun getPendingConsumptionCorrection(targetEventId: String): PendingConsumptionCorrection? = null
    override suspend fun deletePendingConsumptionCorrection(actionId: String, targetEventId: String): Int = 0
    override suspend fun cancelPendingConsumptionCorrection(targetEventId: String): Int = 0
    override suspend fun remapPendingConsumptions(oldProductId: String, newProductId: String, productUuid: String?) = Unit
    override suspend fun remapPendingFinishActions(oldProductId: String, newProductId: String, productUuid: String?) = Unit
    override fun getPendingPurchasesCount(): Flow<Int> = emptyFlow()
    override fun getPendingConsumptionsCount(): Flow<Int> = emptyFlow()
    override fun getPendingFinishActionsCount(): Flow<Int> = emptyFlow()
    override fun getPendingConsumptionCorrectionsCount(): Flow<Int> = emptyFlow()
    override suspend fun getPendingPurchasesCountNow(): Int = 0
    override suspend fun getPendingConsumptionsCountNow(): Int = 0
    override suspend fun getPendingFinishActionsCountNow(): Int = 0
    override suspend fun getPendingConsumptionCorrectionsCountNow(): Int = 0
    override fun getAllProductInteractions(): Flow<List<ProductInteraction>> = emptyFlow()
    override suspend fun getProductInteraction(productId: String): ProductInteraction? = null
    override suspend fun upsertProductInteraction(interaction: ProductInteraction) = Unit
    override suspend fun deleteProductInteraction(productId: String) = Unit
    override suspend fun activateProductIfUnopened(productId: String) = Unit
    override suspend fun markProductFinished(productId: String) = Unit
    override suspend fun getSyncRequestState(): SyncRequestState? = null
    override suspend fun upsertSyncRequestState(state: SyncRequestState) = Unit
    override suspend fun clearSyncRequestState() = Unit
}
