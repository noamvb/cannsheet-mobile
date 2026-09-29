package com.example.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.PlexMono
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.SentenceCitation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.currentStage) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("On-Device Assistant", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (uiState.isAvailable) "Ready • On-Device Llama" else "LocalLLM service unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::startNewThread) {
                        Icon(Icons.Filled.Add, contentDescription = "New Conversation")
                    }
                    FilterChip(
                        selected = uiState.filterByCurrentApp,
                        onClick = viewModel::toggleAppFilter,
                        label = { Text("Cannsheet") },
                        leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.imePadding(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Ask about spend, logs, trends...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            shape = MaterialTheme.shapes.medium,
                            enabled = !uiState.isGenerating,
                        )

                        FilledIconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.askQuestion(inputText)
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank() && !uiState.isGenerating,
                            modifier = Modifier
                                .size(48.dp),
                            shape = CircleShape,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistChip(
                            onClick = viewModel::toggleCrossApp,
                            label = { Text(if (uiState.allowCrossApp) "Cross-App: ON (Cannsheet + Poop)" else "Cross-App: OFF") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (uiState.allowCrossApp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.messages.isEmpty()) {
                AssistantEmptyState(
                    onSuggestedPrompt = { prompt ->
                        viewModel.askQuestion(prompt)
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            onCitationClick = viewModel::selectCitation,
                        )
                    }
                }
            }
        }
    }

    uiState.selectedCitation?.let { citation ->
        CitationDetailDialog(
            citation = citation,
            onDismiss = { viewModel.selectCitation(null) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    onCitationClick: (SentenceCitation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.sender == MessageSender.USER

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = MaterialTheme.shapes.medium.topStart,
                topEnd = MaterialTheme.shapes.medium.topEnd,
                bottomStart = if (isUser) MaterialTheme.shapes.medium.bottomStart else MaterialTheme.shapes.extraSmall.bottomStart,
                bottomEnd = if (isUser) MaterialTheme.shapes.extraSmall.bottomEnd else MaterialTheme.shapes.medium.bottomEnd,
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.isStreaming && message.text.isBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(message.stage ?: "Thinking...", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (!isUser && message.status != null && message.status != AssistantTerminalStatus.VALIDATED) {
                    ValidationFailedWarningCard(message = message)
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (message.isStreaming && message.stage != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message.stage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (message.citations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Grounded Evidence Citations:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        message.citations.forEach { citation ->
                            SuggestionChip(
                                onClick = { onCitationClick(citation) },
                                label = { Text("${citation.citedFactIds.size} facts", style = MaterialTheme.typography.labelSmall) },
                                icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "${if (isUser) "You" else "On-device"} · ${message.timestamp.asAssistantTime()}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun ValidationFailedWarningCard(message: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    "Output Failed Verification",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "This generation made ungrounded numerical claims or exceeded strict safety guardrails.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (message.validationIssues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                message.validationIssues.forEach { issue ->
                    Text("• $issue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide raw text" else "View inert raw text")
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun CitationDetailDialog(
    citation: SentenceCitation,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Citation Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cited Sentence:", style = MaterialTheme.typography.labelMedium)
                Text(citation.sentence, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Supporting Evidence Fact IDs:", style = MaterialTheme.typography.labelMedium)
                citation.citedFactIds.forEach { factId ->
                    Text("• $factId", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}

@Composable
fun AssistantEmptyState(
    onSuggestedPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "What would you like to review?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        val suggestions = listOf(
            "Summarize recent activity",
            "How much have I spent over the last 30 days?",
            "Which days of the week do I log the most?",
        )
        suggestions.forEach { suggestion ->
            Surface(
                onClick = { onSuggestedPrompt(suggestion) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun Long.asAssistantTime(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(this))
