package com.example.widget

import com.example.data.Product
import com.example.domain.PenQuickLogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenWidgetPresetsTest {
    @Test
    fun presetUsesAreConvertedToSecondsWithTheProductRate() {
        val model = buildPenWidgetUiModel(
            loaded(presetUses = listOf(0.5, 1.0, 2.0), rate = 10.0),
            composing(),
            null,
            0L,
        ) as PenWidgetUiModel.Composing

        assertEquals(listOf(5, 10, 20), model.presetSeconds)
    }

    @Test
    fun presetsBelowOneSecondAreDropped() {
        assertEquals(
            listOf(1),
            penWidgetPresetSeconds(loaded(presetUses = listOf(0.04, 0.1), rate = 10.0)),
        )
    }

    @Test
    fun fractionalSecondPresetsAreDroppedRatherThanFloored() {
        assertEquals(
            emptyList<Int>(),
            penWidgetPresetSeconds(loaded(presetUses = listOf(0.19), rate = 10.0)),
        )
    }

    @Test
    fun presetsAboveTheMaximumAreDropped() {
        assertEquals(
            listOf(MAX_SECONDS),
            penWidgetPresetSeconds(loaded(presetUses = listOf(60.0, 61.0), rate = 10.0)),
        )
    }

    @Test
    fun atMostThreePresetsAreOffered() {
        val presets = penWidgetPresetSeconds(
            loaded(presetUses = (1..10).map { it.toDouble() }, rate = 10.0),
        )

        assertEquals(3, presets.size)
        assertEquals(listOf(10, 20, 30), presets)
    }

    @Test
    fun presetsAreSortedAscendingAndDeduplicated() {
        assertEquals(
            listOf(10, 20, 30),
            penWidgetPresetSeconds(
                loaded(presetUses = listOf(3.0, 1.0, 2.0, 1.0, 3.0), rate = 10.0),
            ),
        )
    }

    @Test
    fun awaitingCommitOffersNoPresets() {
        val model = buildPenWidgetUiModel(
            loaded(presetUses = listOf(0.5, 1.0, 2.0), rate = 10.0),
            PenWidgetDraft.AwaitingCommit(payload()),
            null,
            0L,
        )

        assertTrue(model is PenWidgetUiModel.AwaitingCommit)
    }

    private fun composing() = PenWidgetDraft.Composing(0)

    private fun loaded(
        presetUses: List<Double> = listOf(0.5, 1.0, 2.0),
        rate: Double? = 10.0,
    ) = PenQuickLogState.Loaded(
        product = Product(
            id = "pen-1",
            name = "Loaded cart",
            type = "P",
            status = 0,
            totalUses = 12.0,
        ),
        presetUses = presetUses,
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
        seconds = 30,
        secondsPerUse = 10.0,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
