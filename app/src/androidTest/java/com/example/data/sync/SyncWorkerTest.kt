package com.example.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.BackgroundSyncResult
import com.example.data.CannsheetRepository
import com.example.data.GasApiService
import com.example.data.ProductCatalogRefreshResult
import com.example.data.PurchaseAction
import com.example.data.QueuedSyncSnapshot
import com.example.data.SyncAcknowledgementPlan
import com.example.data.SyncEngine
import com.example.data.SyncOutcome
import com.example.data.SyncPayload
import com.squareup.moshi.Moshi
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CannsheetRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(applicationContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CannsheetRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun disabledWorkerSucceedsWithoutRunningOrOverwritingStatus() = runBlocking {
        val runtime = FakeRuntime(enabled = false, result = BackgroundSyncRunResult.NothingToSync)

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, runtime.observeQueueDepthCalls)
        assertEquals(0, runtime.runCalls)
        assertTrue(runtime.recordedResults.isEmpty())
    }

    @Test
    fun queueDepthObservationFailureDoesNotBlockQueueWork() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.NothingToSync,
            observeQueueDepthError = IllegalStateException("watermark failed"),
        )

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, runtime.observeQueueDepthCalls)
        assertEquals(1, runtime.runCalls)
    }

    @Test
    fun queueDepthObservationCancellationCancelsTheWorker() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.NothingToSync,
            observeQueueDepthError = CancellationException("cancelled"),
        )

        try {
            worker(runtime).doWork()
            fail("CancellationException should escape")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertEquals(1, runtime.observeQueueDepthCalls)
        assertEquals(0, runtime.runCalls)
    }

    @Test
    fun retryableOutcomeRetriesBeforeTheRetryBudgetIsExhausted() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.Retry(SyncOutcome.HtmlResponse(emptyList())),
        )

        val result = worker(runtime, runAttemptCount = 0).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(runtime.recordedResults.isEmpty())
    }

    @Test
    fun retryableOutcomeExhaustionSucceedsAndRecordsIt() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.Retry(SyncOutcome.HtmlResponse(emptyList())),
        )

        val result = worker(runtime, runAttemptCount = 5).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(BackgroundSyncResult.RETRY_EXHAUSTED), runtime.recordedResults)
    }

    @Test
    fun environmentMismatchFailsAndRecordsItsTerminalResult() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.EnvironmentMismatch(
                SyncOutcome.EnvironmentMismatch(emptyList()),
            ),
        )

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(listOf(BackgroundSyncResult.ENVIRONMENT_MISMATCH), runtime.recordedResults)
    }

    @Test
    fun appliedAcknowledgementSucceedsAndRecordsSuccess() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.Applied(
                SyncOutcome.Applied(
                    plan = SyncAcknowledgementPlan(
                        acknowledgedPurchaseActionIds = setOf("purchase-action"),
                    ),
                    pendingCorrectionsAtSnapshot = emptyList(),
                    snapshot = QueuedSyncSnapshot(emptySet(), emptySet()),
                ),
                completedPasses = 1,
            ),
        )

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(BackgroundSyncResult.SUCCESS), runtime.recordedResults)
        assertEquals(1, runtime.refreshWidgetCalls)
    }

    @Test
    fun widgetRefreshFailureDoesNotRetryAnAppliedSync() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.Applied(
                SyncOutcome.Applied(
                    plan = SyncAcknowledgementPlan(
                        acknowledgedPurchaseActionIds = setOf("purchase-action"),
                    ),
                    pendingCorrectionsAtSnapshot = emptyList(),
                    snapshot = QueuedSyncSnapshot(emptySet(), emptySet()),
                ),
                completedPasses = 1,
            ),
            refreshWidgetError = IllegalStateException("widget refresh failed"),
        )

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(BackgroundSyncResult.SUCCESS), runtime.recordedResults)
        assertEquals(1, runtime.refreshWidgetCalls)
    }

    @Test
    fun realEmptyQueueSucceedsWithoutCallingTheApi() = runBlocking {
        val api = FakeGasApi { error("The API must not be called for an empty queue") }
        val runtime = queueRuntime(api)

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, api.syncCalls)
        assertTrue(runtime.recordedResults.isEmpty())
    }

    @Test
    fun realTransportFailureRetriesAndLeavesTheRoomQueueIntact() = runBlocking {
        repository.addPurchase(purchase())
        val api = FakeGasApi { throw IOException("offline") }
        val runtime = queueRuntime(api)

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(listOf(PURCHASE_ACTION_ID), repository.getPendingPurchases().map { it.actionId })
        assertTrue(runtime.recordedResults.isEmpty())
    }

    @Test
    fun realAcknowledgementDrainsTheRoomQueueAndRecordsSuccess() = runBlocking {
        repository.addPurchase(purchase())
        val runtime = queueRuntime(FakeGasApi { payload -> successfulResponse(payload) })

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(repository.getPendingPurchases().isEmpty())
        assertEquals(listOf(BackgroundSyncResult.SUCCESS), runtime.recordedResults)
    }

    @Test
    fun realEnvironmentMismatchFailsAndKeepsTheRoomQueueIntact() = runBlocking {
        repository.addPurchase(purchase())
        val runtime = queueRuntime(
            FakeGasApi { payload -> successfulResponse(payload, environment = "SANDBOX") },
        )

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(listOf(PURCHASE_ACTION_ID), repository.getPendingPurchases().map { it.actionId })
        assertEquals(
            listOf(BackgroundSyncResult.ENVIRONMENT_MISMATCH),
            runtime.recordedResults,
        )
    }

    @Test
    fun periodicWorkPrefetchesAfterAnEmptyQueue() = runBlocking {
        val runtime = FakeRuntime(result = BackgroundSyncRunResult.NothingToSync)

        val result = worker(runtime, inputData = PREFETCH_INPUT_DATA).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, runtime.prefetchCalls)
    }

    @Test
    fun periodicWorkPrefetchesAfterAnAppliedAcknowledgement() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.Applied(
                SyncOutcome.Applied(
                    plan = SyncAcknowledgementPlan(
                        acknowledgedPurchaseActionIds = setOf("purchase-action"),
                    ),
                    pendingCorrectionsAtSnapshot = emptyList(),
                    snapshot = QueuedSyncSnapshot(emptySet(), emptySet()),
                ),
                completedPasses = 1,
            ),
        )

        val result = worker(runtime, inputData = PREFETCH_INPUT_DATA).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, runtime.prefetchCalls)
        assertEquals(listOf(BackgroundSyncResult.SUCCESS), runtime.recordedResults)
    }

    @Test
    fun immediateWorkDoesNotPrefetch() = runBlocking {
        val runtime = FakeRuntime(result = BackgroundSyncRunResult.NothingToSync)

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, runtime.prefetchCalls)
    }

    @Test
    fun retryResultSkipsPrefetch() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.Retry(SyncOutcome.HtmlResponse(emptyList())),
        )

        val result = worker(runtime, runAttemptCount = 0, inputData = PREFETCH_INPUT_DATA).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(0, runtime.prefetchCalls)
    }

    @Test
    fun environmentMismatchSkipsPrefetch() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.EnvironmentMismatch(
                SyncOutcome.EnvironmentMismatch(emptyList()),
            ),
        )

        val result = worker(runtime, inputData = PREFETCH_INPUT_DATA).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(0, runtime.prefetchCalls)
    }

    @Test
    fun disabledWorkerDoesNotPrefetch() = runBlocking {
        val runtime = FakeRuntime(enabled = false, result = BackgroundSyncRunResult.NothingToSync)

        val result = worker(runtime, inputData = PREFETCH_INPUT_DATA).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, runtime.prefetchCalls)
    }

    @Test
    fun prefetchFailureStillReportsQueueSuccess() = runBlocking {
        val runtime = FakeRuntime(
            result = BackgroundSyncRunResult.Applied(
                SyncOutcome.Applied(
                    plan = SyncAcknowledgementPlan(
                        acknowledgedPurchaseActionIds = setOf("purchase-action"),
                    ),
                    pendingCorrectionsAtSnapshot = emptyList(),
                    snapshot = QueuedSyncSnapshot(emptySet(), emptySet()),
                ),
                completedPasses = 1,
            ),
            prefetchError = IllegalStateException("prefetch boom"),
        )

        val result = worker(runtime, inputData = PREFETCH_INPUT_DATA).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(BackgroundSyncResult.SUCCESS), runtime.recordedResults)
    }

    private fun worker(
        runtime: BackgroundSyncWorkerRuntime,
        runAttemptCount: Int = 0,
        inputData: Data = Data.EMPTY,
    ): SyncWorker = TestListenableWorkerBuilder<SyncWorker>(applicationContext)
        .setWorkerFactory(InjectedSyncWorkerFactory(runtime))
        .setRunAttemptCount(runAttemptCount)
        .setInputData(inputData)
        .build()

    private class InjectedSyncWorkerFactory(
        private val runtime: BackgroundSyncWorkerRuntime,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = if (workerClassName == SyncWorker::class.java.name) {
            SyncWorker(appContext, workerParameters, runtime)
        } else {
            null
        }
    }

    private class FakeRuntime(
        private val enabled: Boolean = true,
        private val result: BackgroundSyncRunResult,
        private val observeQueueDepthError: Throwable? = null,
        private val prefetchError: Throwable? = null,
        private val refreshWidgetError: Throwable? = null,
    ) : BackgroundSyncWorkerRuntime {
        var observeQueueDepthCalls = 0
        var runCalls = 0
        var prefetchCalls = 0
        var refreshWidgetCalls = 0
        val recordedResults = mutableListOf<BackgroundSyncResult>()

        override suspend fun observeQueueDepth() {
            observeQueueDepthCalls += 1
            observeQueueDepthError?.let { throw it }
        }

        override suspend fun isEnabled(): Boolean = enabled

        override suspend fun run(): BackgroundSyncRunResult {
            runCalls += 1
            return result
        }

        override suspend fun recordMeaningfulResult(result: BackgroundSyncResult) {
            recordedResults += result
        }

        override suspend fun prefetchAnalytics(): AnalyticsPrefetchOutcome {
            prefetchCalls += 1
            prefetchError?.let { throw it }
            return AnalyticsPrefetchOutcome(
                insights = AnalyticsPrefetchStatus.REFRESHED,
                history = AnalyticsPrefetchStatus.REFRESHED,
            )
        }

        override fun refreshWidgets() {
            refreshWidgetCalls += 1
            refreshWidgetError?.let { throw it }
        }
    }

    private fun queueRuntime(api: GasApiService): QueueRuntime {
        val engine = SyncEngine(
            api = api,
            moshi = Moshi.Builder().build(),
            gateway = repository,
            expectedEnvironment = ENVIRONMENT,
            mutex = Mutex(),
        )
        return QueueRuntime(
            runner = BackgroundSyncRunner(
                endpoint = ENDPOINT,
                operations = object : BackgroundSyncOperations {
                    override suspend fun hasPendingActions(): Boolean = repository.hasPendingActions()

                    override suspend fun sync(endpoint: String): SyncOutcome = engine.sync(endpoint)

                    override suspend fun refreshCatalog(endpoint: String) =
                        ProductCatalogRefreshResult.Updated

                    override suspend fun emitApplied(outcome: SyncOutcome.Applied) = Unit
                },
            ),
        )
    }

    private class QueueRuntime(
        private val runner: BackgroundSyncRunner,
    ) : BackgroundSyncWorkerRuntime {
        val recordedResults = mutableListOf<BackgroundSyncResult>()
        var observeQueueDepthCalls = 0
        var prefetchCalls = 0
        var refreshWidgetCalls = 0

        override suspend fun observeQueueDepth() {
            observeQueueDepthCalls += 1
        }

        override suspend fun isEnabled(): Boolean = true

        override suspend fun run(): BackgroundSyncRunResult = runner.run()

        override suspend fun recordMeaningfulResult(result: BackgroundSyncResult) {
            recordedResults += result
        }

        override suspend fun prefetchAnalytics(): AnalyticsPrefetchOutcome {
            prefetchCalls += 1
            return AnalyticsPrefetchOutcome(
                insights = AnalyticsPrefetchStatus.REFRESHED,
                history = AnalyticsPrefetchStatus.REFRESHED,
            )
        }

        override fun refreshWidgets() {
            refreshWidgetCalls += 1
        }
    }

    private class FakeGasApi(
        private val response: suspend (SyncPayload) -> String,
    ) : GasApiService {
        var syncCalls = 0

        override suspend fun getProducts(url: String): ResponseBody = error("unused")

        override suspend fun getAnalytics(url: String): ResponseBody = error("unused")

        override suspend fun syncData(url: String, payload: SyncPayload): ResponseBody {
            syncCalls += 1
            return response(payload).toResponseBody(JSON)
        }
    }

    private companion object {
        const val ENDPOINT = "https://example.test/exec"
        const val ENVIRONMENT = "PRODUCTION"
        const val PURCHASE_ACTION_ID = "purchase-action"
        val JSON = "application/json".toMediaType()
        val PREFETCH_INPUT_DATA = workDataOf(SyncScheduler.PREFETCH_ANALYTICS_KEY to true)

        val applicationContext: Context
            get() = ApplicationProvider.getApplicationContext()

        fun purchase() = PurchaseAction(
            tempId = "temp-purchase",
            actionId = PURCHASE_ACTION_ID,
            date = "2026-08-09",
            type = "Flower",
            name = "Worker test",
            cost = 1.0,
            thc = 20.0,
            grams = 1.0,
            borrowed = 0,
            postTax = false,
        )

        fun successfulResponse(
            payload: SyncPayload,
            environment: String = ENVIRONMENT,
        ): String = """
            {
              "success": true,
              "apiVersion": 2,
              "requestId": "${payload.requestId}",
              "environment": "$environment",
              "acknowledgedPurchases": [
                {"actionId":"$PURCHASE_ACTION_ID","status":"committed"}
              ],
              "rejectedPurchases": [],
              "acknowledgedConsumptions": [],
              "rejectedConsumptions": [],
              "acknowledgedFinishActions": [],
              "rejectedFinishActions": [],
              "correctionVersion": 1,
              "correctionWritesEnabled": true,
              "acknowledgedConsumptionCorrections": [],
              "rejectedConsumptionCorrections": []
            }
        """.trimIndent()
    }
}
