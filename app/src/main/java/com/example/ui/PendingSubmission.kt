package com.example.ui

/**
 * Holds the single action awaiting the submission countdown.
 *
 * Queuing a new action must never discard an earlier one: [replaceWith] hands the
 * displaced action back so the caller submits it immediately instead of dropping it.
 *
 * Not thread safe by design. Every caller runs on the view model's main dispatcher.
 */
internal class PendingSubmission {
    private var action: (() -> Unit)? = null

    val isPending: Boolean
        get() = action != null

    /** Removes and returns the pending action, so at most one caller can ever run it. */
    fun take(): (() -> Unit)? {
        val current = action
        action = null
        return current
    }

    /** Stores [next] and returns the displaced action for the caller to invoke first. */
    fun replaceWith(next: () -> Unit): (() -> Unit)? {
        val displaced = take()
        action = next
        return displaced
    }
}
