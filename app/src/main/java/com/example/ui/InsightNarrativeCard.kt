package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The narrative card's lifecycle, mirroring the states the sibling LocalLLM clients use for
 * their own insight cards so the feature stays recognisably the same across apps.
 *
 * [Hidden] covers every pre-flight gate failing, the whole generation failing, and a blank
 * finished result — all of which render nothing, per [InsightNarrativeCard]'s contract.
 */
internal sealed interface NarrativeState {
    data object Hidden : NarrativeState
    data object Loading : NarrativeState
    data class Streaming(val text: String) : NarrativeState
    data class Complete(val text: String) : NarrativeState
    data object Failed : NarrativeState
}

/** What, if anything, [InsightNarrativeCard] should draw for a [NarrativeState]. */
internal sealed interface NarrativeCardBody {
    /** Draw nothing: no Card at all. */
    data object None : NarrativeCardBody
    data object Loading : NarrativeCardBody
    data class Text(val text: String) : NarrativeCardBody
}

/**
 * Maps a [NarrativeState] to what the card should draw, so the "nothing to show" rule
 * lives in one place rather than being spread across the composable.
 */
internal fun NarrativeState.toCardBody(): NarrativeCardBody = when (this) {
    NarrativeState.Hidden, NarrativeState.Failed -> NarrativeCardBody.None
    NarrativeState.Loading -> NarrativeCardBody.Loading
    // A first fragment of pure whitespace is common — models like to open with a
    // newline. Folding that into None would tear the Card back down one frame after
    // the loading state put it up, so blank streamed text stays "still working".
    is NarrativeState.Streaming -> if (text.isBlank()) NarrativeCardBody.Loading else NarrativeCardBody.Text(text)
    is NarrativeState.Complete -> if (text.isBlank()) NarrativeCardBody.None else NarrativeCardBody.Text(text)
}

/**
 * The state to settle on once generation has stopped, whatever the reason.
 *
 * This must be total. Leaving [NarrativeState.Loading] in place when a generation ends
 * without producing anything would strand an indeterminate progress bar on the Insights
 * screen indefinitely — the opposite of a card that disappears when it has nothing to
 * say. A flow that completes with no emissions is not hypothetical:
 * [com.noamv.localllm.client.LocalLlmClient] does not re-send the text through
 * `onComplete` for a streaming request, so a service that answers only there closes the
 * flow having emitted nothing.
 */
internal fun terminalState(current: NarrativeState, accumulated: String): NarrativeState = when {
    current == NarrativeState.Failed -> NarrativeState.Failed
    accumulated.isBlank() -> NarrativeState.Hidden
    else -> NarrativeState.Complete(accumulated.trim())
}

/**
 * A written summary of the analytics already on screen, generated on this device.
 *
 * The card is additive and silent about its own absence. It renders nothing when the
 * LocalLLM app is missing, no model is on disk, the snapshot is not one
 * [CannsheetLlmFacts.shouldSummarise] accepts, or generation fails. The Insights screen is
 * complete without it. The one visible-but-empty state is a brief loading indicator: once
 * every pre-flight gate has passed and generation is about to start, a cold engine load and
 * first token can take several seconds, and showing nothing during that window reads as the
 * feature being broken rather than working.
 */
@Composable
fun InsightNarrativeCard(
    state: InsightsUiState,
    pendingActionCount: Int?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val response = state.data

    val narrative by produceState<NarrativeState>(
        initialValue = NarrativeState.Hidden,
        response?.generatedAtEpochMillis,
        response?.range,
        state.isFromCache,
        state.isStale,
        pendingActionCount,
    ) {
        value = NarrativeState.Hidden

        if (!CannsheetLlmFacts.shouldSummarise(state, pendingActionCount)) return@produceState
        val snapshot = state.data ?: return@produceState

        val client = LocalLlmClient(context)
        if (!client.isInstalled()) return@produceState

        // Proceed once a model is on disk. Waiting for READY would mean never asking, since
        // the service holds no engine until something does. Opening Insights must never
        // start a multi-gigabyte download, which modelDownloaded rules out.
        val status = runCatching { client.engineStatus() }.getOrNull() ?: return@produceState
        if (!status.modelDownloaded && status.state != EngineState.READY) return@produceState
        // A device the engine has already given up on keeps reporting its model as
        // downloaded, so it clears the gate above and would show a progress bar purely
        // to have the request refused a moment later.
        if (status.state == EngineState.UNSUPPORTED) return@produceState

        val request = InsightRequest(
            clientId = "cannsheet-mobile",
            task = InsightTask.PERIOD_SUMMARY,
            subject = "your own records of cannabis purchases and consumption",
            period = CannsheetLlmFacts.period(snapshot),
            facts = CannsheetLlmFacts.from(snapshot),
            maxWords = 80,
            stream = true,
        )

        // Every early return above is a pre-flight gate. Only now, immediately before the
        // call that can take seconds to produce its first fragment, does the card become
        // visible — a spinner that could appear before this point would sit there forever
        // on a phone with no model installed.
        value = NarrativeState.Loading

        val builder = StringBuilder()
        // Binding successfully is no guarantee of ever being answered: a service that
        // accepts the request and then wedges emits no fragment, no completion and no
        // error, and the progress bar would spin for as long as the screen is open.
        withTimeoutOrNull(GENERATION_TIMEOUT_MILLIS) {
            client.generate(request)
                .catch { value = NarrativeState.Failed }
                .collect { fragment ->
                    builder.append(fragment)
                    value = NarrativeState.Streaming(builder.toString())
                }
        }

        value = terminalState(value, builder.toString())
    }

    val cardBody = narrative.toCardBody()
    if (cardBody is NarrativeCardBody.None) return
    val loading = cardBody is NarrativeCardBody.Loading
    val body = (cardBody as? NarrativeCardBody.Text)?.text.orEmpty()

    Card(modifier = modifier.fillMaxWidth().testTag("insights-narrative")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("In summary", style = MaterialTheme.typography.titleSmall)
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().testTag("insights-narrative-loading"),
                )
                Text(
                    "Writing a summary on this phone…",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Written on this phone from the figures below. Descriptive only; " +
                        "it excludes runway and spending projections.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Upper bound on one generation. A cold engine load is around ten seconds and the eighty
 * words that follow take several more, so this is generous; it exists only to make a
 * silent service recoverable rather than to cut short a slow one.
 */
private const val GENERATION_TIMEOUT_MILLIS = 90_000L
