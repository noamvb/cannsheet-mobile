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
    force: Boolean = false,
    claimOwnerId: String? = null,
): PenWidgetCommitResolution = when {
    payload == null -> PenWidgetCommitResolution.NoOp
    commitId != null && payload.commitId != commitId -> PenWidgetCommitResolution.NoOp
    payload.claimId != null && !isClaimRecoverable(payload, nowMillis, claimOwnerId) ->
        PenWidgetCommitResolution.NoOp
    !force && !isCommitDue(payload, nowMillis) -> PenWidgetCommitResolution.NoOp
    else -> PenWidgetCommitResolution.Committed(payload)
}

internal fun isCommitDue(payload: PenWidgetCommitPayload, nowMillis: Long): Boolean {
    val pendingAge = nowMillis - payload.submittedAtEpochMillis
    val clockRolledBack = pendingAge < -CLOCK_ROLLBACK_TOLERANCE_MILLIS
    return clockRolledBack ||
        pendingAge >= MAX_PENDING_AGE_MILLIS ||
        nowMillis >= payload.commitAtEpochMillis
}

internal fun isClaimStale(payload: PenWidgetCommitPayload, nowMillis: Long): Boolean {
    val claimedAt = payload.claimedAtEpochMillis ?: return payload.claimId != null
    val claimAge = nowMillis - claimedAt
    return claimAge < -CLOCK_ROLLBACK_TOLERANCE_MILLIS || claimAge >= CLAIM_STALE_MILLIS
}

private fun isClaimRecoverable(
    payload: PenWidgetCommitPayload,
    nowMillis: Long,
    claimOwnerId: String?,
): Boolean {
    val belongsToThisProcess = claimOwnerId == null ||
        payload.claimId?.startsWith("$claimOwnerId:") == true
    return !belongsToThisProcess || isClaimStale(payload, nowMillis)
}
