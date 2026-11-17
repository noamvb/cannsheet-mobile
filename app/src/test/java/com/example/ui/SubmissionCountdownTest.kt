package com.example.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubmissionCountdownTest {
    @Test
    fun countsDownThenRunsActionOnce() = runTest {
        var calls = 0
        val countdown = SubmissionCountdown(backgroundScope) { 3 }
        countdown.queue { calls++ }
        runCurrent()
        assertEquals(3, countdown.remaining.value)
        assertEquals(0, calls)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, countdown.remaining.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, countdown.remaining.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0, countdown.remaining.value)
    }

    @Test
    fun cancelDiscardsAction() = runTest {
        var calls = 0
        val countdown = SubmissionCountdown(backgroundScope) { 3 }
        countdown.queue { calls++ }
        advanceTimeBy(1_500)
        countdown.cancel()
        assertEquals(0, countdown.remaining.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, calls)
    }

    @Test
    fun submitNowRunsActionImmediatelyOnce() = runTest {
        var calls = 0
        val countdown = SubmissionCountdown(backgroundScope) { 5 }
        countdown.queue { calls++ }
        advanceTimeBy(1_000)
        countdown.submitNow()
        assertEquals(1, calls)
        assertEquals(0, countdown.remaining.value)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(0, countdown.remaining.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0, countdown.remaining.value)
    }

    @Test
    fun secondQueueRunsDisplacedActionAndRestarts() = runTest {
        var a = 0
        var b = 0
        val countdown = SubmissionCountdown(backgroundScope) { 3 }
        countdown.queue { a++ }
        advanceTimeBy(2_000)
        countdown.queue { b++ }
        assertEquals(1, a)
        assertEquals(0, b)
        runCurrent()
        assertEquals(3, countdown.remaining.value)
        advanceTimeBy(2_999)
        assertEquals(0, b)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, b)
        assertEquals(1, a)
    }

    @Test
    fun secondsReadWhenCountdownStarts() = runTest {
        var seconds = 3
        var calls = 0
        val countdown = SubmissionCountdown(backgroundScope) { seconds }
        countdown.queue { calls++ }
        runCurrent()
        assertEquals(3, countdown.remaining.value)
        seconds = 7
        advanceTimeBy(2_999)
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
        countdown.queue { calls++ }
        runCurrent()
        assertEquals(7, countdown.remaining.value)
    }

    @Test
    fun zeroSecondsRunsOnFirstDispatch() = runTest {
        var calls = 0
        val countdown = SubmissionCountdown(backgroundScope) { 0 }
        countdown.queue { calls++ }
        assertEquals(0, calls)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0, countdown.remaining.value)
    }

    @Test
    fun actionsWithNothingPendingAreNoOps() = runTest {
        val countdown = SubmissionCountdown(backgroundScope) { 3 }
        countdown.submitNow()
        countdown.cancel()
        assertEquals(0, countdown.remaining.value)
    }
}
