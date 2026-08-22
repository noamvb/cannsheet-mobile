package com.example.widget

import androidx.annotation.StringRes
import com.example.R
import com.example.data.productStatus
import com.example.domain.PenQuickLogState
import com.example.domain.usesToSeconds
import java.math.BigDecimal
import java.math.RoundingMode

sealed interface PenWidgetText {
    data class Resource(
        @param:StringRes val resourceId: Int,
        val arguments: List<Any> = emptyList(),
    ) : PenWidgetText

    data class Literal(val value: String) : PenWidgetText
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
        val title: PenWidgetText.Resource,
        val hint: PenWidgetText.Resource,
        val openTarget: PenWidgetOpenTarget,
    ) : PenWidgetUiModel

    data class Composing(
        val productName: PenWidgetText,
        val subtitle: PenWidgetText,
        val seconds: Int,
        val recentlyQueued: Boolean,
        val canDecrement: Boolean,
        val canIncrement: Boolean,
        val canSubmit: Boolean,
        val presetSeconds: List<Int> = emptyList(),
    ) : PenWidgetUiModel

    data class AwaitingCommit(
        val productName: PenWidgetText,
        val subtitle: PenWidgetText.Resource,
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
    discreet: Boolean = false,
    queueStuck: Boolean = false,
): PenWidgetUiModel {
    if (draft is PenWidgetDraft.AwaitingCommit) {
        val product = (penState as? PenQuickLogState.Loaded)?.product
        return PenWidgetUiModel.AwaitingCommit(
            productName = if (discreet) {
                PenWidgetText.Resource(R.string.pen_widget_generic_cart)
            } else {
                product?.name?.let { PenWidgetText.Literal(it) }
                    ?: PenWidgetText.Resource(R.string.pen_widget_generic_cart)
            },
            subtitle = PenWidgetText.Resource(
                R.string.pen_widget_undo_window,
                listOf(UNDO_WINDOW_MILLIS / 1_000L),
            ),
            frozenSeconds = draft.payload.restoreDraftSeconds ?: 0,
            commitId = draft.payload.commitId,
            remainingMillis = (
                draft.payload.commitAtEpochMillis - COMMIT_GRACE_MILLIS - nowMillis
            ).coerceAtLeast(0L),
        )
    }

    val composing = draft as PenWidgetDraft.Composing
    return when (penState) {
        PenQuickLogState.Unavailable -> PenWidgetUiModel.Message(
            kind = PenWidgetMessageKind.Unavailable,
            title = PenWidgetText.Resource(R.string.pen_widget_no_carts),
            hint = PenWidgetText.Resource(R.string.pen_widget_no_carts_hint),
            openTarget = PenWidgetOpenTarget.Log,
        )

        PenQuickLogState.NoCartLoaded -> PenWidgetUiModel.Message(
            kind = PenWidgetMessageKind.NoCart,
            title = PenWidgetText.Resource(R.string.pen_widget_no_cart_loaded),
            hint = PenWidgetText.Resource(R.string.pen_widget_no_cart_loaded_hint),
            openTarget = PenWidgetOpenTarget.Log,
        )

        is PenQuickLogState.Loaded -> {
            if (penState.secondsPerUse == null) {
                PenWidgetUiModel.Message(
                    kind = PenWidgetMessageKind.RateOff,
                    title = PenWidgetText.Resource(R.string.pen_widget_rate_off),
                    hint = PenWidgetText.Resource(R.string.pen_widget_rate_off_hint),
                    openTarget = PenWidgetOpenTarget.Settings,
                )
            } else {
                val recentlyQueued = wasRecentlyQueued(lastQueuedAtMillis, nowMillis)
                PenWidgetUiModel.Composing(
                    productName = if (discreet) {
                        PenWidgetText.Resource(R.string.pen_widget_generic_cart)
                    } else {
                        PenWidgetText.Literal(penState.product.name)
                    },
                    subtitle = buildSubtitle(
                        state = penState,
                        lastQueuedAtMillis = lastQueuedAtMillis,
                        nowMillis = nowMillis,
                        discreet = discreet,
                        queueStuck = queueStuck,
                    ),
                    seconds = composing.seconds,
                    recentlyQueued = recentlyQueued,
                    canDecrement = composing.seconds > 0,
                    canIncrement = composing.seconds < MAX_SECONDS,
                    canSubmit = composing.seconds > 0,
                    presetSeconds = penWidgetPresetSeconds(penState),
                )
            }
        }
    }
}

internal fun penWidgetPresetSeconds(state: PenQuickLogState.Loaded): List<Int> =
    state.secondsPerUse?.let { secondsPerUse ->
        state.presetUses
            .asSequence()
            .mapNotNull { usesToSeconds(it, secondsPerUse).toWholePresetSeconds() }
            .distinct()
            .sorted()
            .take(3)
            .toList()
    } ?: emptyList()

private fun Double.toWholePresetSeconds(): Int? {
    if (!isFinite()) return null
    val seconds = toInt()
    return seconds.takeIf { toDouble() == it.toDouble() && it in 1..MAX_SECONDS }
}

private fun buildSubtitle(
    state: PenQuickLogState.Loaded,
    lastQueuedAtMillis: Long?,
    nowMillis: Long,
    discreet: Boolean,
    queueStuck: Boolean,
): PenWidgetText {
    if (wasRecentlyQueued(lastQueuedAtMillis, nowMillis)) {
        return PenWidgetText.Resource(R.string.pen_widget_queued)
    }

    val status = state.product.productStatus.label
    if (queueStuck) {
        return PenWidgetText.Resource(R.string.pen_widget_sync_stuck, listOf(status))
    }

    if (discreet) {
        return PenWidgetText.Resource(R.string.pen_widget_discreet_subtitle)
    }

    return if (state.pendingUses > 0.0) {
        if (state.syncedUses == null) {
            PenWidgetText.Resource(
                R.string.pen_widget_status_unavailable_pending,
                listOf(status, formatAmount(state.pendingUses)),
            )
        } else {
            PenWidgetText.Resource(
                R.string.pen_widget_status_synced_pending,
                listOf(status, formatAmount(state.syncedUses), formatAmount(state.pendingUses)),
            )
        }
    } else if (state.syncedUses == null) {
        PenWidgetText.Resource(R.string.pen_widget_status_unavailable, listOf(status))
    } else {
        PenWidgetText.Resource(
            R.string.pen_widget_status_synced,
            listOf(status, formatAmount(state.syncedUses)),
        )
    }
}

private fun wasRecentlyQueued(lastQueuedAtMillis: Long?, nowMillis: Long): Boolean =
    lastQueuedAtMillis != null &&
        nowMillis - lastQueuedAtMillis in 0..QUEUED_SUBTITLE_WINDOW_MILLIS

private fun formatAmount(value: Double): String = BigDecimal.valueOf(value)
    .setScale(3, RoundingMode.HALF_UP)
    .stripTrailingZeros()
    .toPlainString()
