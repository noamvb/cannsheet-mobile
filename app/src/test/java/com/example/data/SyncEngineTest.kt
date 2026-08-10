package com.example.data

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    @Test
    fun emptyQueuesReturnNothingToSyncWithoutCallingServer() = runBlocking {
        val api = FakeGasApi { error("Server should not be called") }
        val gateway = FakeSyncQueueGateway()

        val outcome = engine(api, gateway).sync(ENDPOINT)

        assertTrue(outcome is SyncOutcome.NothingToSync)
        assertEquals(0, api.syncCalls)
        assertEquals(0, gateway.applyCalls)
    }

    @Test
    fun htmlResponseKeepsQueueIntact() = runBlocking {
        val gateway = FakeSyncQueueGateway(purchases = mutableListOf(purchase("purchase-1")))
        val outcome = engine(FakeGasApi { "<html>not deployed</html>" }, gateway).sync(ENDPOINT)

        assertTrue(outcome is SyncOutcome.HtmlResponse)
        assertEquals(0, gateway.applyCalls)
        assertEquals(1, gateway.purchases.size)
    }

    @Test
    fun failedResponseKeepsQueueIntact() = runBlocking {
        val gateway = FakeSyncQueueGateway(purchases = mutableListOf(purchase("purchase-1")))
        val outcome = engine(
            FakeGasApi { """{"success":false,"errorCode":"BUSY","message":"Try later"}""" },
            gateway,
        ).sync(ENDPOINT)

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals("BUSY", (outcome as SyncOutcome.Failed).errorCode)
        assertEquals(0, gateway.applyCalls)
        assertEquals(1, gateway.purchases.size)
    }

    @Test
    fun environmentMismatchDoesNotApplyAcknowledgements() = runBlocking {
        val gateway = FakeSyncQueueGateway(purchases = mutableListOf(purchase("purchase-1")))
        val outcome = engine(
            FakeGasApi { payload -> successResponse(payload, environment = "SANDBOX") },
            gateway,
        ).sync(ENDPOINT)

        assertTrue(outcome is SyncOutcome.EnvironmentMismatch)
        assertEquals(0, gateway.applyCalls)
    }

    @Test
    fun requestIdMismatchDoesNotApplyAcknowledgements() = runBlocking {
        val gateway = FakeSyncQueueGateway(purchases = mutableListOf(purchase("purchase-1")))
        val outcome = engine(
            FakeGasApi { payload -> successResponse(payload, requestId = "different-request") },
            gateway,
        ).sync(ENDPOINT)

        assertTrue(outcome is SyncOutcome.RequestIdMismatch)
        assertEquals(0, gateway.applyCalls)
    }

    @Test
    fun acceptedResponseAppliesTheExistingAcknowledgementPlan() = runBlocking {
        val gateway = FakeSyncQueueGateway(purchases = mutableListOf(purchase("purchase-1")))
        val outcome = engine(
            FakeGasApi { payload -> successResponse(payload) },
            gateway,
        ).sync(ENDPOINT)

        assertTrue(outcome is SyncOutcome.Applied)
        val applied = outcome as SyncOutcome.Applied
        assertEquals(setOf("purchase-1"), applied.plan.acknowledgedPurchaseActionIds)
        assertEquals(1, gateway.applyCalls)
        assertEquals(setOf("purchase-1"), gateway.lastPlan?.acknowledgedPurchaseActionIds)
    }

    @Test
    fun transportErrorKeepsQueueAndReportsSnapshotCorrections() = runBlocking {
        val correction = correction()
        val gateway = FakeSyncQueueGateway(
            purchases = mutableListOf(purchase("purchase-1")),
            corrections = mutableListOf(correction),
        )
        val outcome = engine(
            FakeGasApi { throw IOException("offline") },
            gateway,
        ).sync(ENDPOINT)

        assertTrue(outcome is SyncOutcome.TransportError)
        assertEquals(listOf(correction), outcome.pendingCorrectionsAtSnapshot)
        assertEquals(0, gateway.applyCalls)
        assertEquals(1, gateway.purchases.size)
        assertEquals(1, gateway.corrections.size)
    }

    @Test
    fun callbacksRunInsideTheSharedLockInParityOrder() = runBlocking {
        val events = mutableListOf<String>()
        val gateway = FakeSyncQueueGateway()

        engine(FakeGasApi { error("unused") }, gateway).sync(
            endpoint = ENDPOINT,
            onLockAcquired = { events += "acquired" },
            onOutcome = { events += "outcome" },
            onLockReleasing = { events += "releasing" },
        )

        assertEquals(listOf("acquired", "outcome", "releasing"), events)
    }

    @Test
    fun concurrentCallsSerializeOnTheSharedMutex() = runBlocking {
        val activeCalls = AtomicInteger(0)
        val maximumActiveCalls = AtomicInteger(0)
        val gateway = FakeSyncQueueGateway(purchases = mutableListOf(purchase("purchase-1")))
        val api = FakeGasApi { payload ->
            val active = activeCalls.incrementAndGet()
            maximumActiveCalls.updateAndGet { previous -> maxOf(previous, active) }
            delay(50)
            activeCalls.decrementAndGet()
            successResponse(payload)
        }
        val engine = engine(api, gateway)

        val first = async { engine.sync(ENDPOINT) }
        val second = async { engine.sync(ENDPOINT) }
        first.await()
        second.await()

        assertEquals(1, maximumActiveCalls.get())
    }

    @Test
    fun identicalSnapshotReusesRequestIdAndChangedSnapshotMintsAnother() = runBlocking {
        val gateway = FakeSyncQueueGateway(purchases = mutableListOf(purchase("purchase-1")))
        val api = FakeGasApi { payload -> successResponse(payload) }
        val engine = engine(api, gateway)

        engine.sync(ENDPOINT)
        engine.sync(ENDPOINT)
        gateway.purchases += purchase("purchase-2")
        engine.sync(ENDPOINT)

        assertEquals(api.requestIds[0], api.requestIds[1])
        assertFalse(api.requestIds[1] == api.requestIds[2])
    }

    private fun engine(api: GasApiService, gateway: SyncQueueGateway) = SyncEngine(
        api = api,
        moshi = com.squareup.moshi.Moshi.Builder().build(),
        gateway = gateway,
        expectedEnvironment = ENVIRONMENT,
        mutex = Mutex(),
    )

    private class FakeGasApi(
        private val syncResponse: suspend (SyncPayload) -> String,
    ) : GasApiService {
        var syncCalls = 0
        val requestIds = mutableListOf<String>()

        override suspend fun getProducts(url: String): ResponseBody = error("unused")

        override suspend fun getAnalytics(url: String): ResponseBody = error("unused")

        override suspend fun syncData(url: String, payload: SyncPayload): ResponseBody {
            syncCalls += 1
            requestIds += payload.requestId
            return syncResponse(payload).toResponseBody(JSON)
        }
    }

    private class FakeSyncQueueGateway(
        val purchases: MutableList<PurchaseAction> = mutableListOf(),
        val consumptions: MutableList<ConsumptionAction> = mutableListOf(),
        val finishes: MutableList<FinishAction> = mutableListOf(),
        val corrections: MutableList<PendingConsumptionCorrection> = mutableListOf(),
    ) : SyncQueueGateway {
        private var requestState: Pair<String, String>? = null
        var applyCalls = 0
        var lastPlan: SyncAcknowledgementPlan? = null

        override suspend fun getPendingPurchases() = purchases.toList()

        override suspend fun getPendingConsumptions() = consumptions.toList()

        override suspend fun getPendingFinishActions() = finishes.toList()

        override suspend fun getPendingConsumptionCorrections() = corrections.toList()

        override suspend fun getOrCreateSyncRequestId(snapshot: QueuedSyncSnapshot): String {
            val fingerprint = snapshot.payloadFingerprint()
            return requestState?.takeIf { it.first == fingerprint }?.second
                ?: UUID.randomUUID().toString().also { requestId ->
                    requestState = fingerprint to requestId
                }
        }

        override suspend fun applyAcknowledgements(plan: SyncAcknowledgementPlan) {
            applyCalls += 1
            lastPlan = plan
        }
    }

    private companion object {
        const val ENDPOINT = "https://example.test/sync"
        const val ENVIRONMENT = "PRODUCTION"
        val JSON = "application/json".toMediaType()

        fun purchase(actionId: String) = PurchaseAction(
            tempId = "temp-$actionId",
            actionId = actionId,
            date = "2026-08-09",
            type = "Flower",
            name = "Test",
            cost = 1.0,
            thc = 20.0,
            grams = 1.0,
            borrowed = 0,
            postTax = false,
        )

        fun correction() = PendingConsumptionCorrection(
            targetEventId = "00000000-0000-0000-0000-000000000001",
            actionId = "00000000-0000-0000-0000-000000000002",
            expectedCorrectionHeadId = "",
            operation = ConsumptionCorrectionOperation.VOID,
            reopenProduct = false,
        )

        fun successResponse(
            payload: SyncPayload,
            environment: String = ENVIRONMENT,
            requestId: String = payload.requestId,
        ): String = """
            {
              "success": true,
              "apiVersion": 2,
              "requestId": "$requestId",
              "environment": "$environment",
              "acknowledgedPurchases": [
                {"actionId":"${payload.purchases.firstOrNull()?.actionId.orEmpty()}","status":"committed"}
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
