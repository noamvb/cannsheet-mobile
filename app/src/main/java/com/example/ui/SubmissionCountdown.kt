package com.example.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Holds the single pending submission and its countdown. Like [PendingSubmission],
 * this is not thread safe: every caller must run on the main dispatcher.
 */
internal class SubmissionCountdown(
    private val scope: CoroutineScope,
    private val seconds: () -> Int,
) {
    private val _remaining = MutableStateFlow(0)
    val remaining: StateFlow<Int> = _remaining

    private var job: Job? = null
    private val pendingSubmission = PendingSubmission()

    fun queue(action: () -> Unit) {
        job?.cancel()
        job = null
        _remaining.value = 0
        pendingSubmission.replaceWith(action)?.invoke()
        job = scope.launch {
            for (second in seconds() downTo 1) {
                _remaining.value = second
                delay(1_000)
            }
            _remaining.value = 0
            pendingSubmission.take()?.invoke()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remaining.value = 0
        pendingSubmission.take()
    }

    fun submitNow() {
        job?.cancel()
        job = null
        _remaining.value = 0
        pendingSubmission.take()?.invoke()
    }
}
