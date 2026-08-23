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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.CannsheetLlmFacts
import com.noamv.localllm.client.LocalLlmClient
import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.EngineStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.LinkedHashMap

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
internal fun terminalState(
    current: NarrativeState,
    accumulated: String,
    request: InsightRequest,
    completedNormally: Boolean = true,
): NarrativeState = when {
    !completedNormally -> NarrativeState.Hidden
    current == NarrativeState.Failed -> NarrativeState.Failed
    else -> when (val verdict = CannsheetNarrativeValidator.validate(accumulated, request)) {
        is CannsheetNarrativeValidator.Verdict.Accept -> NarrativeState.Complete(verdict.text)
        is CannsheetNarrativeValidator.Verdict.Reject -> NarrativeState.Hidden
    }
}

/**
 * Every transition that affects whether prose may be shown. It is deliberately separate from
 * the request fingerprint: an ineligible state must still cancel and hide an older eligible
 * request immediately, even though it has no request to fingerprint.
 */
internal data class NarrativeEligibility(
    /** Full immutable DTO equality catches a replacement even when derived facts happen to match. */
    val snapshot: com.example.data.InsightsResponseDto?,
    val hasSnapshot: Boolean,
    val snapshotGeneratedAtEpochMillis: Long?,
    val displayedRange: com.example.data.InsightsRange,
    val pendingRange: com.example.data.InsightsRange?,
    val isInitialLoading: Boolean,
    val isRefreshing: Boolean,
    val isFromCache: Boolean,
    val isStale: Boolean,
    val pendingActionCount: Int?,
    val error: AnalyticsUiError?,
)

/** A request is recreated only when both its complete eligibility and supplied facts match. */
internal data class NarrativeGenerationKey(
    val eligibility: NarrativeEligibility,
    val factFingerprint: String,
)

/** The complete lifecycle input, including ineligible transitions that must hide old prose. */
internal data class NarrativeGenerationInput(
    val eligibility: NarrativeEligibility,
    val request: InsightRequest?,
    val key: NarrativeGenerationKey?,
)

internal fun narrativeGenerationInput(
    state: InsightsUiState,
    pendingActionCount: Int?,
): NarrativeGenerationInput {
    val eligibility = NarrativeEligibility(
        snapshot = state.data,
        hasSnapshot = state.data != null,
        snapshotGeneratedAtEpochMillis = state.data?.generatedAtEpochMillis,
        displayedRange = state.displayedRange,
        pendingRange = state.pendingRange,
        isInitialLoading = state.isInitialLoading,
        isRefreshing = state.isRefreshing,
        isFromCache = state.isFromCache,
        isStale = state.isStale,
        pendingActionCount = pendingActionCount,
        error = state.error,
    )
    val snapshot = state.data
    if (snapshot == null || !CannsheetLlmFacts.shouldSummarise(state, pendingActionCount)) {
        return NarrativeGenerationInput(eligibility, request = null, key = null)
    }

    val request = InsightRequest(
        clientId = "cannsheet-mobile",
        task = InsightTask.PERIOD_SUMMARY,
        subject = "your own records of cannabis purchases and consumption",
        period = CannsheetLlmFacts.period(snapshot),
        facts = CannsheetLlmFacts.from(snapshot),
        maxWords = 80,
        stream = true,
    )
    return NarrativeGenerationInput(
        eligibility = eligibility,
        request = request,
        key = NarrativeGenerationKey(eligibility, request.factFingerprint()),
    )
}

internal fun InsightRequest.factFingerprint(): String = buildString {
    append(contractVersion).append('|')
    append(clientId).append('|').append(task.name).append('|')
    append(subject.length).append(':').append(subject).append('|')
    append(period?.label.orEmpty().length).append(':').append(period?.label.orEmpty()).append('|')
    append(period?.start.orEmpty().length).append(':').append(period?.start.orEmpty()).append('|')
    append(period?.end.orEmpty().length).append(':').append(period?.end.orEmpty()).append('|')
    append(maxWords).append('|').append(stream)
    facts.forEach { fact ->
        append('|').append(fact.label.length).append(':').append(fact.label)
        append('|').append(fact.value.length).append(':').append(fact.value)
        append('|').append(fact.note.orEmpty().length).append(':').append(fact.note.orEmpty())
    }
}

/** Narrow adapter that keeps Android binder work out of the coordinator's JVM tests. */
internal interface NarrativeGenerationService {
    fun isInstalled(): Boolean
    suspend fun engineStatus(): EngineStatus
    fun generate(request: InsightRequest): Flow<String>
}

private class LocalLlmNarrativeGenerationService(
    context: android.content.Context,
) : NarrativeGenerationService {
    private val client = LocalLlmClient(context)

    override fun isInstalled(): Boolean = client.isInstalled()

    override suspend fun engineStatus(): EngineStatus = client.engineStatus()

    override fun generate(request: InsightRequest): Flow<String> = client.generate(request)
}

/**
 * Owns one screen-lifetime narrative request. Each update first clears visible prose, then
 * either restores a validated result for the exact key or starts a new request. This makes a
 * refresh, range change, pending action, cache/stale/error/loading transition, or changed fact
 * fingerprint cancel obsolete work before it can repaint the card.
 */
internal class NarrativeGenerationCoordinator(
    private val scope: CoroutineScope,
    private val serviceFactory: () -> NarrativeGenerationService,
    private val generationTimeoutMillis: Long = GENERATION_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val completedResults = object : LinkedHashMap<NarrativeGenerationKey, String>(
        MAX_COMPLETED_RESULTS + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<NarrativeGenerationKey, String>?,
        ): Boolean = size > MAX_COMPLETED_RESULTS
    }
    private val _state = MutableStateFlow<NarrativeState>(NarrativeState.Hidden)
    val state: StateFlow<NarrativeState> = _state

    private var activeKey: NarrativeGenerationKey? = null
    private var generationJob: Job? = null

    /**
     * Hides prior prose during the composition that first observes a changed input. The
     * effect that cancels/starts work runs only after that composition commits.
     */
    fun visibleStateFor(input: NarrativeGenerationInput, observed: NarrativeState): NarrativeState =
        if (input.key != null && input.key == activeKey) observed else NarrativeState.Hidden

    fun update(input: NarrativeGenerationInput) {
        val key = input.key
        if (key != null && key == activeKey) return

        generationJob?.cancel()
        generationJob = null
        activeKey = key
        _state.value = NarrativeState.Hidden

        val request = input.request ?: return
        check(key != null) { "An eligible narrative request must have a generation key" }
        completedResults[key]?.let { cached ->
            _state.value = NarrativeState.Complete(cached)
            return
        }

        generationJob = scope.launch {
            generate(key, request)
        }
    }

    private suspend fun generate(key: NarrativeGenerationKey, request: InsightRequest) {
        val service = serviceFactory()
        if (!service.isInstalled()) return
        val status = try {
            service.engineStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return
        }
        if ((!status.modelDownloaded && status.state != EngineState.READY) ||
            status.state == EngineState.UNSUPPORTED
        ) return

        setStateIfCurrent(key, NarrativeState.Loading)
        val output = StringBuilder()
        val completedNormally = try {
            withTimeout(generationTimeoutMillis) {
                service.generate(request).collect { fragment ->
                    if (!CannsheetNarrativeValidator.canAppend(output.length, fragment.length)) {
                        throw NarrativeOutputTooLongException()
                    }
                    output.append(fragment)
                }
                true
            }
        } catch (_: NarrativeOutputTooLongException) {
            false
        } catch (_: TimeoutCancellationException) {
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            setStateIfCurrent(key, NarrativeState.Failed)
            false
        }

        val terminal = terminalState(_state.value, output.toString(), request, completedNormally)
        if (terminal is NarrativeState.Complete && activeKey == key) {
            completedResults[key] = terminal.text
        }
        setStateIfCurrent(key, terminal)
    }

    private fun setStateIfCurrent(key: NarrativeGenerationKey, value: NarrativeState) {
        if (activeKey == key) _state.value = value
    }

    override fun close() {
        generationJob?.cancel()
        generationJob = null
        activeKey = null
        completedResults.clear()
        _state.value = NarrativeState.Hidden
    }

    private companion object {
        const val MAX_COMPLETED_RESULTS = 4
    }
}

private class NarrativeOutputTooLongException : RuntimeException()

/**
 * Drives [NarrativeState] for the given [state]/[pendingActionCount] and survives the card
 * being scrolled off-screen and back.
 *
 * This must be called from a composable that is not itself torn down by scrolling — the
 * caller, not [InsightNarrativeCard]. [InsightNarrativeCard] is placed inside a `LazyColumn`
 * item, and `LazyColumn` disposes an off-screen item's entire composition once it scrolls
 * far enough away, discarding any `remember`/`produceState` state that lived inside it. If
 * generation were driven from inside the card itself, scrolling the card out of view and
 * back would restart it from scratch — indistinguishable from the summary "regenerating"
 * for no reason. Hoisting this to the caller, above the `LazyColumn`, keeps the generation
 * coroutine alive for as long as Insights itself is on screen, regardless of scroll
 * position.
 */
@Composable
internal fun rememberNarrativeState(state: InsightsUiState, pendingActionCount: Int?): NarrativeState {
    val context = LocalContext.current
    val input = narrativeGenerationInput(state, pendingActionCount)
    val scope = rememberCoroutineScope()
    val coordinator = remember(context, scope) {
        NarrativeGenerationCoordinator(
            scope = scope,
            serviceFactory = { LocalLlmNarrativeGenerationService(context) },
        )
    }
    val narrative by coordinator.state.collectAsState()

    DisposableEffect(context, input.request != null) {
        val warmup = if (input.request != null) LocalLlmClient(context).warmup() else null
        onDispose {
            warmup?.close()
        }
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.close() }
    }
    // Dispose/recreate runs after composition, so state changes are never performed while this
    // composable is being read. Every input, including an ineligible one, reaches the coordinator.
    DisposableEffect(input, coordinator) {
        coordinator.update(input)
        onDispose { }
    }

    return coordinator.visibleStateFor(input, narrative)
}

/**
 * Renders [narrative]. The card is additive and silent about its own absence:
 * [NarrativeState.Hidden] and [NarrativeState.Failed] draw nothing at all — the Insights
 * screen is complete without it.
 *
 * Generation itself is driven by [rememberNarrativeState], called by the caller above the
 * `LazyColumn` this card lives in — never inside this composable. See that function's doc
 * for why.
 */
@Composable
internal fun InsightNarrativeCard(
    narrative: NarrativeState,
    modifier: Modifier = Modifier,
) {
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
