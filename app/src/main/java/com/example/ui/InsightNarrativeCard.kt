package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.CannsheetLlmFacts
import com.noamv.localllm.client.LocalLlmClient
import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import kotlinx.coroutines.flow.catch

/**
 * A written summary of the analytics already on screen, generated on this device.
 *
 * The card is additive and silent about its own absence. It renders nothing when the
 * LocalLLM app is missing, no model is on disk, the snapshot is not one
 * [CannsheetLlmFacts.shouldSummarise] accepts, or generation fails. The Insights screen is
 * complete without it.
 */
@Composable
fun InsightNarrativeCard(
    state: InsightsUiState,
    pendingActionCount: Int?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val response = state.data

    val text by produceState<String?>(
        initialValue = null,
        response?.generatedAtEpochMillis,
        response?.range,
        state.isFromCache,
        state.isStale,
        pendingActionCount,
    ) {
        value = null

        if (!CannsheetLlmFacts.shouldSummarise(state, pendingActionCount)) return@produceState
        val snapshot = state.data ?: return@produceState

        val client = LocalLlmClient(context)
        if (!client.isInstalled()) return@produceState

        // Proceed once a model is on disk. Waiting for READY would mean never asking, since
        // the service holds no engine until something does. Opening Insights must never
        // start a multi-gigabyte download, which modelDownloaded rules out.
        val status = runCatching { client.engineStatus() }.getOrNull() ?: return@produceState
        if (!status.modelDownloaded && status.state != EngineState.READY) return@produceState

        val request = InsightRequest(
            clientId = "cannsheet-mobile",
            task = InsightTask.PERIOD_SUMMARY,
            subject = "your own records of cannabis purchases and consumption",
            period = CannsheetLlmFacts.period(snapshot),
            facts = CannsheetLlmFacts.from(snapshot),
            maxWords = 80,
            stream = true,
        )

        val builder = StringBuilder()
        var failed = false
        client.generate(request)
            .catch { failed = true }
            .collect { fragment ->
                builder.append(fragment)
                value = builder.toString()
            }
        if (failed) value = null
    }

    val body = text?.trim().orEmpty()
    if (body.isEmpty()) return

    Card(modifier = modifier.fillMaxWidth().testTag("insights-narrative")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("In summary", style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Written on this phone from the figures below. Descriptive only; " +
                    "it excludes runway and spending projections.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
