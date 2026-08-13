package com.example.data.sync

/** Data-facing boundary for the optional out-of-app queue alert surface. */
interface QueueAlertPresenter {
    /**
     * Returns true only when Android currently allows Cannsheet to post an alert.
     * Implementations must not create a notification channel while answering.
     */
    fun canPresent(): Boolean

    /**
     * Returns true when Android accepted the post. It does not claim the user saw the alert.
     */
    fun tryPresent(alert: QueueAlert): Boolean

    /** Cancels Cannsheet's one stable queue alert notification. */
    fun clear()
}

internal object NoOpQueueAlertPresenter : QueueAlertPresenter {
    override fun canPresent(): Boolean = false

    override fun tryPresent(alert: QueueAlert): Boolean = false

    override fun clear() = Unit
}
