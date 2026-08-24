package com.example.ui.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noamv.localllm.client.v2.AssistantClientV2
import com.noamv.localllm.client.v2.AssistantTurnEvent
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantEvent
import com.noamv.localllm.contract.v2.AssistantEventType
import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.AssistantTurnRequest
import com.noamv.localllm.contract.v2.HistoryQuery
import com.noamv.localllm.contract.v2.HistoryThreadSummary
import com.noamv.localllm.contract.v2.HistoryTurnRecord
import com.noamv.localllm.contract.v2.SentenceCitation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val status: AssistantTerminalStatus? = null,
    val citations: List<SentenceCitation> = emptyList(),
    val validationIssues: List<String> = emptyList(),
    val stage: String? = null,
)

enum class MessageSender {
    USER,
    ASSISTANT,
}

data class AssistantUiState(
    val isAvailable: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val threads: List<HistoryThreadSummary> = emptyList(),
    val currentThreadId: String = UUID.randomUUID().toString(),
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val currentStage: String? = null,
    val filterByCurrentApp: Boolean = true,
    val allowCrossApp: Boolean = false,
    val selectedCitation: SentenceCitation? = null,
    val errorBanner: String? = null,
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val client = AssistantClientV2(application)
    private val _uiState = MutableStateFlow(AssistantUiState(isAvailable = client.isInstalled()))
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingHistory = true) }
            val page = client.getHistory(HistoryQuery(limit = 30))
            if (page != null) {
                _uiState.update { it.copy(threads = page.threads, isLoadingHistory = false) }
            } else {
                _uiState.update { it.copy(isLoadingHistory = false) }
            }
        }
    }

    fun toggleAppFilter() {
        _uiState.update { it.copy(filterByCurrentApp = !it.filterByCurrentApp) }
    }

    fun toggleCrossApp() {
        _uiState.update { it.copy(allowCrossApp = !it.allowCrossApp) }
    }

    fun selectCitation(citation: SentenceCitation?) {
        _uiState.update { it.copy(selectedCitation = citation) }
    }

    fun startNewThread() {
        _uiState.update {
            it.copy(
                currentThreadId = UUID.randomUUID().toString(),
                messages = emptyList(),
                isGenerating = false,
                currentStage = null,
                errorBanner = null,
            )
        }
    }

    fun askQuestion(questionText: String) {
        val trimmed = questionText.trim()
        if (trimmed.isBlank() || _uiState.value.isGenerating) return

        val userMessage = ChatMessage(
            sender = MessageSender.USER,
            text = trimmed,
        )

        val assistantMessageId = UUID.randomUUID().toString()
        val initialAssistantMessage = ChatMessage(
            id = assistantMessageId,
            sender = MessageSender.ASSISTANT,
            text = "",
            isStreaming = true,
            stage = "Routing query...",
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage + initialAssistantMessage,
                isGenerating = true,
                currentStage = "Routing query...",
                errorBanner = null,
            )
        }

        val request = AssistantTurnRequest(
            requestId = UUID.randomUUID().toString(),
            threadId = _uiState.value.currentThreadId,
            initiatingClient = getApplication<Application>().packageName,
            question = trimmed,
            defaultSource = AppSource.CANNSHEET,
            maxSourcesAllowed = if (_uiState.value.allowCrossApp) 2 else 1,
            allowCrossApp = _uiState.value.allowCrossApp,
        )

        viewModelScope.launch {
            client.submitTurn(request).collect { turnEvent ->
                when (turnEvent) {
                    is AssistantTurnEvent.Progress -> {
                        val event = turnEvent.event
                        val draft = event.draftText
                        val stageName = when (event.eventType) {
                            AssistantEventType.ROUTING -> "Analyzing question..."
                            AssistantEventType.QUEUED -> "Queued in LocalLLM..."
                            AssistantEventType.MODEL_LOADING -> "Warming up model..."
                            AssistantEventType.PROVIDER_STATUS -> "Fetching facts from app..."
                            AssistantEventType.DRAFT -> "Generating response..."
                            else -> event.stage ?: "Processing..."
                        }

                        _uiState.update { state ->
                            val updatedMessages = state.messages.map { msg ->
                                if (msg.id == assistantMessageId) {
                                    msg.copy(
                                        text = draft ?: msg.text,
                                        stage = stageName,
                                    )
                                } else {
                                    msg
                                }
                            }
                            state.copy(
                                messages = updatedMessages,
                                currentStage = stageName,
                            )
                        }
                    }

                    is AssistantTurnEvent.Complete -> {
                        val result = turnEvent.result
                        _uiState.update { state ->
                            val updatedMessages = state.messages.map { msg ->
                                if (msg.id == assistantMessageId) {
                                    msg.copy(
                                        text = result.finalOrEscapedText,
                                        isStreaming = false,
                                        status = result.status,
                                        citations = result.citations,
                                        validationIssues = result.validationIssues,
                                        stage = null,
                                    )
                                } else {
                                    msg
                                }
                            }
                            state.copy(
                                messages = updatedMessages,
                                isGenerating = false,
                                currentStage = null,
                            )
                        }
                    }

                    is AssistantTurnEvent.Error -> {
                        _uiState.update { state ->
                            val updatedMessages = state.messages.map { msg ->
                                if (msg.id == assistantMessageId) {
                                    msg.copy(
                                        text = "Unable to complete: ${turnEvent.message}",
                                        isStreaming = false,
                                        status = AssistantTerminalStatus.ERROR,
                                        stage = null,
                                    )
                                } else {
                                    msg
                                }
                            }
                            state.copy(
                                messages = updatedMessages,
                                isGenerating = false,
                                currentStage = null,
                                errorBanner = turnEvent.message,
                            )
                        }
                    }
                }
            }
        }
    }
}
