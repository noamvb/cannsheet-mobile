package com.example.widget

import com.example.R
import com.example.data.Product
import com.example.domain.PenQuickLogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenWidgetUiModelTest {
    @Test
    fun rendersAllMessageStates() {
        assertEquals(
            PenWidgetMessageKind.Unavailable,
            (buildPenWidgetUiModel(PenQuickLogState.Unavailable, composing(), null, 0L)
                as PenWidgetUiModel.Message).kind,
        )
        assertEquals(
            PenWidgetMessageKind.NoCart,
            (buildPenWidgetUiModel(PenQuickLogState.NoCartLoaded, composing(), null, 0L)
                as PenWidgetUiModel.Message).kind,
        )
        assertEquals(
            PenWidgetMessageKind.RateOff,
            (buildPenWidgetUiModel(loaded(rate = null), composing(), null, 0L)
                as PenWidgetUiModel.Message).kind,
        )
        assertEquals(
            R.string.pen_widget_no_carts,
            (buildPenWidgetUiModel(PenQuickLogState.Unavailable, composing(), null, 0L)
                as PenWidgetUiModel.Message).title.resourceId,
        )
    }

    @Test
    fun rendersThirtySecondComposingStateAndBounds() {
        val model = buildPenWidgetUiModel(loaded(), PenWidgetDraft.Composing(30), null, 0L)
            as PenWidgetUiModel.Composing

        assertEquals(30, model.seconds)
        assertTrue(model.canDecrement)
        assertTrue(model.canIncrement)
        assertTrue(model.canSubmit)
        assertEquals(
            R.string.pen_widget_queued,
            ((buildPenWidgetUiModel(loaded(), PenWidgetDraft.Composing(0), 1_000L, 8_999L)
                as PenWidgetUiModel.Composing).subtitle as PenWidgetText.Resource).resourceId,
        )
        assertFalse((buildPenWidgetUiModel(loaded(), PenWidgetDraft.Composing(0), null, 0L)
            as PenWidgetUiModel.Composing).canSubmit)
        assertFalse((buildPenWidgetUiModel(loaded(), PenWidgetDraft.Composing(0), null, 0L)
            as PenWidgetUiModel.Composing).canDecrement)
        assertFalse((buildPenWidgetUiModel(loaded(), PenWidgetDraft.Composing(MAX_SECONDS), null, 0L)
            as PenWidgetUiModel.Composing).canIncrement)
    }

    @Test
    fun queuedSubtitleExpiresAndAwaitingCommitFreezesSeconds() {
        val normal = buildPenWidgetUiModel(loaded(), PenWidgetDraft.Composing(20), 1_000L, 9_001L)
            as PenWidgetUiModel.Composing
        assertFalse(
            normal.subtitle is PenWidgetText.Resource &&
                normal.subtitle.resourceId == R.string.pen_widget_queued,
        )

        val awaiting = buildPenWidgetUiModel(
            loaded(),
            PenWidgetDraft.AwaitingCommit(payload()),
            null,
            2_000L,
        ) as PenWidgetUiModel.AwaitingCommit
        assertEquals(30, awaiting.frozenSeconds)
        assertEquals("commit-1", awaiting.commitId)
        assertEquals(1_500L, awaiting.remainingMillis)
        assertEquals(R.string.pen_widget_undo_window, awaiting.subtitle.resourceId)
    }

    @Test
    fun stuckQueueShowsTheSyncBehindSubtitle() {
        val model = buildPenWidgetUiModel(
            penState = loaded(),
            draft = PenWidgetDraft.Composing(20),
            lastQueuedAtMillis = null,
            nowMillis = 0L,
            queueStuck = true,
        ) as PenWidgetUiModel.Composing

        assertEquals(
            PenWidgetText.Resource(R.string.pen_widget_sync_stuck, listOf("Active")),
            model.subtitle,
        )
    }

    @Test
    fun recentlyQueuedOutranksStuckQueue() {
        val model = buildPenWidgetUiModel(
            penState = loaded(),
            draft = PenWidgetDraft.Composing(20),
            lastQueuedAtMillis = 1_000L,
            nowMillis = 2_000L,
            queueStuck = true,
        ) as PenWidgetUiModel.Composing

        assertEquals(PenWidgetText.Resource(R.string.pen_widget_queued), model.subtitle)
    }

    @Test
    fun discreetModeOutranksTheSyncedCountsButNotQueued() {
        val discreet = buildPenWidgetUiModel(
            penState = loaded(),
            draft = PenWidgetDraft.Composing(20),
            lastQueuedAtMillis = null,
            nowMillis = 0L,
            discreet = true,
        ) as PenWidgetUiModel.Composing
        val queued = buildPenWidgetUiModel(
            penState = loaded(),
            draft = PenWidgetDraft.Composing(20),
            lastQueuedAtMillis = 1_000L,
            nowMillis = 2_000L,
            discreet = true,
            queueStuck = true,
        ) as PenWidgetUiModel.Composing

        assertEquals(
            PenWidgetText.Resource(R.string.pen_widget_discreet_subtitle),
            discreet.subtitle,
        )
        assertEquals(PenWidgetText.Resource(R.string.pen_widget_queued), queued.subtitle)
    }

    private fun composing() = PenWidgetDraft.Composing(0)

    private fun loaded(rate: Double? = 10.0) = PenQuickLogState.Loaded(
        product = Product(
            id = "pen-1",
            name = "Loaded cart",
            type = "P",
            status = 0,
            totalUses = 12.0,
        ),
        presetUses = listOf(0.5, 1.0, 2.0),
        secondsPerUse = rate,
        syncedUses = 12.0,
        pendingUses = 0.0,
    )

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        eventId = "event-1",
        submittedAtEpochMillis = 0L,
        commitAtEpochMillis = 5_000L,
        productId = "pen-1",
        productUuid = null,
        inputKind = DeferredPenInputKind.DURATION_SECONDS,
        seconds = 30,
        secondsPerUse = 10.0,
        restoreDraftSeconds = 30,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
