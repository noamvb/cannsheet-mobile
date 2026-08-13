package com.example.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes the persisted claim protocol with the process-local notification surface.
 *
 * A claim is completed only after Android accepted the notification. A rejected post releases
 * that exact token so it does not consume the repeat window. There is deliberately no release
 * after an accepted post: if completion persistence fails, retrying could duplicate a card that
 * Android already received.
 */
internal class QueueAlertDeliveryCoordinator(
    private val presenter: () -> QueueAlertPresenter,
    private val inspectCurrentAlert: suspend () -> QueueAlert?,
    private val evaluateAndClaim: suspend () -> QueueAlertClaim,
    private val completeClaim: suspend (String) -> Boolean,
    private val releaseClaim: suspend (String) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun reconcile() {
        mutex.withLock {
            val currentPresenter = presenter()
            // Checking before the atomic claim avoids suppressing an alert Android cannot post.
            if (!canPresent(currentPresenter)) {
                if (inspectCurrentAlert() == null) clearBestEffort(currentPresenter)
                return
            }

            val claim = evaluateAndClaim()
            val alert = claim.activeAlert
            when {
                alert == null -> clearBestEffort(currentPresenter)
                claim.claimId == null -> Unit // Active, but still inside its repeat window.
                tryPresent(currentPresenter, alert, claim.claimId) -> {
                    val completed = try {
                        completeClaim(claim.claimId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        // Android accepted the post. Do not release or hide it on persistence
                        // error: doing so could consume the repeat window without a visible card.
                        return
                    }
                    if (!completed) {
                        // The queue drained or a kill switch retired the claim during the post.
                        clearBestEffort(currentPresenter)
                    }
                }
                else -> releaseAfterFailedPost(claim.claimId)
            }
        }
    }

    /**
     * Uses a fresh inspection under the same notification mutex as delivery. Callers supply only
     * a trigger; they never pass a potentially stale active/inactive decision.
     */
    suspend fun clearIfCurrentAlertInactive() {
        mutex.withLock {
            if (inspectCurrentAlert() == null) clearBestEffort(presenter())
        }
    }

    private fun canPresent(currentPresenter: QueueAlertPresenter): Boolean = try {
        currentPresenter.canPresent()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        false
    }

    private fun clearBestEffort(currentPresenter: QueueAlertPresenter) {
        try {
            currentPresenter.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Advisory only. A later state change or worker can try again.
        }
    }

    private suspend fun tryPresent(
        currentPresenter: QueueAlertPresenter,
        alert: QueueAlert,
        claimId: String,
    ): Boolean = try {
        currentPresenter.tryPresent(alert)
    } catch (error: CancellationException) {
        withContext(NonCancellable) { runCatching { releaseClaim(claimId) } }
        throw error
    } catch (error: Throwable) {
        false
    }

    private suspend fun releaseAfterFailedPost(claimId: String) {
        try {
            releaseClaim(claimId)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { runCatching { releaseClaim(claimId) } }
            throw error
        } catch (error: Throwable) {
            // Exact-token release is idempotent. Give a transient DataStore failure one cleanup
            // retry so a definitively failed post does not consume the repeat window.
            withContext(NonCancellable) { runCatching { releaseClaim(claimId) } }
        }
    }
}
