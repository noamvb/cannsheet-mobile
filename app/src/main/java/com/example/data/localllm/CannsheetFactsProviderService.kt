package com.example.data.localllm

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.example.data.CannsheetGraph
import com.example.domain.CannsheetFactsCalculator
import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.contract.v2.AggregateQuery
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.ProviderCapabilities
import com.noamv.localllm.contract.v2.ProviderFactsResult
import com.noamv.localllm.v2.IAssistantFactsCallbackV2
import com.noamv.localllm.v2.IAssistantFactsProviderV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class CannsheetFactsProviderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private lateinit var hostAuthorizer: HostAuthorizer

    override fun onCreate() {
        super.onCreate()
        hostAuthorizer = HostAuthorizer(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        activeJobs.clear()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private val binder = object : IAssistantFactsProviderV2.Stub() {
        override fun getProviderVersion(): Int {
            hostAuthorizer.enforceAuthorizedHost(Binder.getCallingUid())
            return AssistantContractV2.VERSION
        }

        override fun getProviderCapabilitiesJson(): String {
            hostAuthorizer.enforceAuthorizedHost(Binder.getCallingUid())
            val capabilities = CannsheetFactsCalculator.getCapabilities()
            return AssistantContractV2.json.encodeToString(ProviderCapabilities.serializer(), capabilities)
        }

        override fun queryFacts(queryJson: String, callback: IAssistantFactsCallbackV2): String {
            val callingUid = Binder.getCallingUid()
            try {
                hostAuthorizer.enforceAuthorizedHost(callingUid)
            } catch (sec: SecurityException) {
                callback.onProviderError("unauthorized", LocalLlmError.INVALID_REQUEST, sec.message ?: "Unauthorized caller")
                return "unauthorized"
            }

            val query = try {
                AssistantContractV2.json.decodeFromString(AggregateQuery.serializer(), queryJson)
            } catch (ex: Exception) {
                callback.onProviderError("malformed", LocalLlmError.INVALID_REQUEST, "Malformed AggregateQuery: ${ex.message}")
                return "malformed"
            }

            val queryId = java.util.UUID.randomUUID().toString()
            val job = serviceScope.launch {
                try {
                    val graph = CannsheetGraph.get(applicationContext)
                    val snapshot = graph.analyticsRepository.readCachedInsights()
                    val pendingCount = graph.repository.pendingActionCount.first()
                    if (pendingCount > 0) {
                        val unsettledResult = com.noamv.localllm.contract.v2.ProviderFactsResult(
                            sourceApp = com.noamv.localllm.contract.v2.AppSource.CANNSHEET,
                            facts = emptyList(),
                            revision = "unsettled",
                            asOfTime = System.currentTimeMillis(),
                            timezone = snapshot?.timeZone ?: "UTC",
                            warnings = listOf("UNSETTLED_ACTIONS_PENDING"),
                        )
                        val resultJson = AssistantContractV2.json.encodeToString(com.noamv.localllm.contract.v2.ProviderFactsResult.serializer(), unsettledResult)
                        callback.onFactsResult(queryId, resultJson)
                        return@launch
                    }
                    val factsResult = CannsheetFactsCalculator.calculateFacts(
                        query = query,
                        snapshot = snapshot,
                        pendingActionCount = pendingCount,
                    )
                    val resultJson = AssistantContractV2.json.encodeToString(ProviderFactsResult.serializer(), factsResult)
                    callback.onFactsResult(queryId, resultJson)
                } catch (ex: Exception) {
                    callback.onProviderError(queryId, LocalLlmError.INTERNAL, ex.message ?: "Facts calculation error")
                } finally {
                    activeJobs.remove(queryId)
                }
            }

            activeJobs[queryId] = job
            return queryId
        }

        override fun cancelQuery(queryId: String) {
            hostAuthorizer.enforceAuthorizedHost(Binder.getCallingUid())
            activeJobs.remove(queryId)?.cancel()
        }
    }
}
