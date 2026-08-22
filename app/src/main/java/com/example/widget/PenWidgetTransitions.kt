package com.example.widget

sealed interface PenWidgetUndoResolution {
    data class Restored(val seconds: Int) : PenWidgetUndoResolution

    data object Removed : PenWidgetUndoResolution

    data object NoOp : PenWidgetUndoResolution
}

sealed interface PenWidgetCommitResolution {
    data class Committed(val payload: PenWidgetCommitPayload) : PenWidgetCommitResolution

    data object NoOp : PenWidgetCommitResolution
}

/**
 * Undo loses to a live claim. A claimed payload is mid-Room-write, so restoring it would leave a
 * queued row the user believes was cancelled. Claim state — not the process-wide widget mutex —
 * is the correctness boundary here, for the same reason WorkManager cancellation is not one.
 */
fun resolveUndo(
    payload: PenWidgetCommitPayload?,
    commitId: String,
    nowMillis: Long,
): PenWidgetUndoResolution = when {
    payload == null -> PenWidgetUndoResolution.NoOp
    payload.commitId != commitId -> PenWidgetUndoResolution.NoOp
    payload.claimId != null && !isClaimStale(payload, nowMillis) -> PenWidgetUndoResolution.NoOp
    payload.inputKind == DeferredPenInputKind.DIRECT_USES -> PenWidgetUndoResolution.Removed
    payload.restoreDraftSeconds != null ->
        PenWidgetUndoResolution.Restored(payload.restoreDraftSeconds)
    else -> PenWidgetUndoResolution.NoOp
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
