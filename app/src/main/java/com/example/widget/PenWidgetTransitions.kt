package com.example.widget

sealed interface PenWidgetUndoResolution {
    data class Restored(val seconds: Int) : PenWidgetUndoResolution

    data object NoOp : PenWidgetUndoResolution
}

sealed interface PenWidgetCommitResolution {
    data class Committed(val payload: PenWidgetCommitPayload) : PenWidgetCommitResolution

    data object NoOp : PenWidgetCommitResolution
}

/**
 * DataStore edit operations use these pure results to arbitrate the undo/commit race. WorkManager
 * cancellation is only an optimization; it cannot be the correctness boundary.
 */
fun resolveUndo(
    payload: PenWidgetCommitPayload?,
    commitId: String,
): PenWidgetUndoResolution =
    if (payload?.commitId == commitId) {
        PenWidgetUndoResolution.Restored(payload.seconds)
    } else {
        PenWidgetUndoResolution.NoOp
    }

fun resolveCommit(
    payload: PenWidgetCommitPayload?,
    commitId: String?,
    nowMillis: Long,
): PenWidgetCommitResolution = when {
    payload == null -> PenWidgetCommitResolution.NoOp
    commitId != null && payload.commitId != commitId -> PenWidgetCommitResolution.NoOp
    commitId == null && payload.commitAtEpochMillis > nowMillis -> PenWidgetCommitResolution.NoOp
    else -> PenWidgetCommitResolution.Committed(payload)
}
