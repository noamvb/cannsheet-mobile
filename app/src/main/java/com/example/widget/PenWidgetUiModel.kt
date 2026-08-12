package com.example.widget

import com.example.data.productStatus
import com.example.ui.PenQuickLogState
import java.math.BigDecimal
import java.math.RoundingMode

internal object PenWidgetText {
    const val UNAVAILABLE_TITLE = "No pen carts"
    const val UNAVAILABLE_HINT = "Tap to open Cannsheet Mobile."
    const val NO_CART_TITLE = "No cart loaded"
    const val NO_CART_HINT = "Tap to choose a pen cart."
    const val RATE_OFF_TITLE = "Pen duration rate is off"
    const val RATE_OFF_HINT = "Tap to set it in Settings."
    const val UNDO = "UNDO"
    const val SUBMIT = "✓"
    const val QUEUED = "Queued ✓"
}

enum class PenWidgetMessageKind {
    Unavailable,
    NoCart,
    RateOff,
}

enum class PenWidgetOpenTarget {
    Log,
    Settings,
}

sealed interface PenWidgetUiModel {
    data class Message(
        val kind: PenWidgetMessageKind,
        val title: String,
        val hint: String,
        val openTarget: PenWidgetOpenTarget,
    ) : PenWidgetUiModel

    data class Composing(
        val productName: String,
        val subtitle: String,
        val seconds: Int,
        val canDecrement: Boolean,
        val canIncrement: Boolean,
        val canSubmit: Boolean,
    ) : PenWidgetUiModel

    data class AwaitingCommit(
        val productName: String,
        val subtitle: String,
        val frozenSeconds: Int,
        val commitId: String,
        val remainingMillis: Long,
    ) : PenWidgetUiModel
}

fun buildPenWidgetUiModel(
    penState: PenQuickLogState,
    draft: PenWidgetDraft,
    lastQueuedAtMillis: Long?,
    nowMillis: Long,
): PenWidgetUiModel {
    if (draft is PenWidgetDraft.AwaitingCommit) {
        val product = (penState as? PenQuickLogState.Loaded)?.product
        return PenWidgetUiModel.AwaitingCommit(
            productName = product?.name ?: "Pen cart",
            subtitle = "Undo within 5 seconds",
            frozenSeconds = draft.payload.seconds,
            commitId = draft.payload.commitId,
            remainingMillis = (draft.payload.commitAtEpochMillis - nowMillis).coerceAtLeast(0L),
        )
    }

    val composing = draft as PenWidgetDraft.Composing
    return when (penState) {
        PenQuickLogState.Unavailable -> PenWidgetUiModel.Message(
            kind = PenWidgetMessageKind.Unavailable,
            title = PenWidgetText.UNAVAILABLE_TITLE,
            hint = PenWidgetText.UNAVAILABLE_HINT,
            openTarget = PenWidgetOpenTarget.Log,
        )

        PenQuickLogState.NoCartLoaded -> PenWidgetUiModel.Message(
            kind = PenWidgetMessageKind.NoCart,
            title = PenWidgetText.NO_CART_TITLE,
            hint = PenWidgetText.NO_CART_HINT,
            openTarget = PenWidgetOpenTarget.Log,
        )

        is PenQuickLogState.Loaded -> {
            if (penState.secondsPerUse == null) {
                PenWidgetUiModel.Message(
                    kind = PenWidgetMessageKind.RateOff,
                    title = PenWidgetText.RATE_OFF_TITLE,
                    hint = PenWidgetText.RATE_OFF_HINT,
                    openTarget = PenWidgetOpenTarget.Settings,
                )
            } else {
                PenWidgetUiModel.Composing(
                    productName = penState.product.name,
                    subtitle = buildSubtitle(penState, lastQueuedAtMillis, nowMillis),
                    seconds = composing.seconds,
                    canDecrement = composing.seconds > 0,
                    canIncrement = composing.seconds < MAX_SECONDS,
                    canSubmit = composing.seconds > 0,
                )
            }
        }
    }
}

private fun buildSubtitle(
    state: PenQuickLogState.Loaded,
    lastQueuedAtMillis: Long?,
    nowMillis: Long,
): String {
    if (lastQueuedAtMillis != null &&
        nowMillis - lastQueuedAtMillis in 0..QUEUED_SUBTITLE_WINDOW_MILLIS
    ) {
        return PenWidgetText.QUEUED
    }

    val synced = state.syncedUses?.let { "synced ${formatAmount(it)} uses" }
        ?: "synced unavailable"
    return if (state.pendingUses > 0.0) {
        "${state.product.productStatus.label} · $synced · Pending: +${formatAmount(state.pendingUses)} uses"
    } else {
        "${state.product.productStatus.label} · $synced"
    }
}

private fun formatAmount(value: Double): String = BigDecimal.valueOf(value)
    .setScale(6, RoundingMode.HALF_UP)
    .stripTrailingZeros()
    .toPlainString()
