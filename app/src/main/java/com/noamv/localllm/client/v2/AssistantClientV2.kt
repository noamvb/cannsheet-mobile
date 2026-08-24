package com.noamv.localllm.client.v2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.noamv.localllm.client.TrustedServiceResolver
import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.contract.v2.AssistantCapabilities
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.AssistantEvent
import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTurnRequest
import com.noamv.localllm.contract.v2.HistoryPage
import com.noamv.localllm.contract.v2.HistoryQuery
import com.noamv.localllm.v2.IAssistantCallbackV2
import com.noamv.localllm.v2.IAssistantServiceV2
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface AssistantTurnEvent {
    data class Progress(val event: AssistantEvent) : AssistantTurnEvent
    data class Complete(val result: AssistantTerminalResult) : AssistantTurnEvent
    data class Error(val code: Int, val message: String, val retryable: Boolean = false) : AssistantTurnEvent
}

class AssistantClientV2(
    context: Context,
    private val bindTimeoutMillis: Long = 5_000L,
) {
    private val appContext = context.applicationContext ?: context
    private val resolver = TrustedServiceResolver(appContext)

    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
    class InferenceFailed(val code: Int, message: String) : Exception(message)

    fun isInstalled(): Boolean = runCatching { resolver.resolve() }.isSuccess

    fun submitTurn(request: AssistantTurnRequest): Flow<AssistantTurnEvent> = callbackFlow {
        val session = try {
            bindSession()
        } catch (e: Exception) {
            trySend(AssistantTurnEvent.Error(LocalLlmError.MODEL_NOT_READY, e.message ?: "Failed to bind to Assistant service"))
            close()
            return@callbackFlow
        }

        val requestJson = AssistantContractV2.json.encodeToString(AssistantTurnRequest.serializer(), request)
        val callback = object : IAssistantCallbackV2.Stub() {
            override fun onEvent(requestId: String?, eventJson: String?) {
                if (eventJson != null) {
                    try {
                        val event = AssistantContractV2.json.decodeFromString(AssistantEvent.serializer(), eventJson)
                        trySend(AssistantTurnEvent.Progress(event))
                    } catch (_: Exception) {}
                }
            }

            override fun onComplete(requestId: String?, resultJson: String?) {
                if (resultJson != null) {
                    try {
                        val result = AssistantContractV2.json.decodeFromString(AssistantTerminalResult.serializer(), resultJson)
                        trySend(AssistantTurnEvent.Complete(result))
                    } catch (ex: Exception) {
                        trySend(AssistantTurnEvent.Error(LocalLlmError.INTERNAL, "Malformed result: ${ex.message}"))
                    }
                }
                close()
            }

            override fun onError(requestId: String?, errorCode: Int, message: String?, retryable: Boolean) {
                trySend(AssistantTurnEvent.Error(errorCode, message ?: "Assistant error", retryable))
                close()
            }
        }

        try {
            session.service.startTurn(requestJson, callback)
        } catch (e: Exception) {
            trySend(AssistantTurnEvent.Error(LocalLlmError.INTERNAL, e.message ?: "Failed to start turn"))
            close()
        }

        awaitClose {
            try {
                session.service.cancelTurn(request.requestId)
            } catch (_: Exception) {}
            session.unbind()
        }
    }.buffer(Channel.UNLIMITED)

    suspend fun getCapabilities(clientId: String = appContext.packageName): AssistantCapabilities? {
        val session = runCatching { bindSession() }.getOrNull() ?: return null
        return try {
            val json = session.service.getCapabilitiesJson(clientId) ?: return null
            AssistantContractV2.json.decodeFromString(AssistantCapabilities.serializer(), json)
        } catch (_: Exception) {
            null
        } finally {
            session.unbind()
        }
    }

    suspend fun getHistory(query: HistoryQuery): HistoryPage? {
        val session = runCatching { bindSession() }.getOrNull() ?: return null
        return try {
            val queryJson = AssistantContractV2.json.encodeToString(HistoryQuery.serializer(), query)
            val json = session.service.getHistoryPage(queryJson) ?: return null
            AssistantContractV2.json.decodeFromString(HistoryPage.serializer(), json)
        } catch (_: Exception) {
            null
        } finally {
            session.unbind()
        }
    }

    private suspend fun bindSession(): BoundSession {
        val resolved = resolver.resolve()
        val intent = Intent("com.noamv.localllm.v2.action.BIND_ASSISTANT").apply {
            component = ComponentName(resolved.packageName, "com.noamv.localllm.service.AssistantServiceV2")
        }

        return suspendCancellableCoroutine { cont ->
            val unbindOnce = AtomicBoolean(false)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        if (!unbindOnce.getAndSet(true)) {
                            appContext.unbindService(this)
                        }
                        if (cont.isActive) {
                            cont.resumeWithException(Unavailable("Assistant service returned null Binder"))
                        }
                        return
                    }

                    val service = IAssistantServiceV2.Stub.asInterface(binder)
                    if (cont.isActive) {
                        cont.resume(BoundSession(service) {
                            if (!unbindOnce.getAndSet(true)) {
                                runCatching { appContext.unbindService(this) }
                            }
                        })
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {}

                override fun onBindingDied(name: ComponentName?) {
                    if (!unbindOnce.getAndSet(true)) {
                        runCatching { appContext.unbindService(this) }
                    }
                    if (cont.isActive) {
                        cont.resumeWithException(Unavailable("Assistant service binding died"))
                    }
                }
            }

            val bound = try {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (sec: SecurityException) {
                if (cont.isActive) cont.resumeWithException(Unavailable("Permission denied binding to assistant service", sec))
                return@suspendCancellableCoroutine
            }

            if (!bound) {
                if (cont.isActive) cont.resumeWithException(Unavailable("bindService returned false for Assistant service"))
                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation {
                if (!unbindOnce.getAndSet(true)) {
                    runCatching { appContext.unbindService(connection) }
                }
            }
        }
    }

    private class BoundSession(
        val service: IAssistantServiceV2,
        val unbind: () -> Unit,
    )
}
