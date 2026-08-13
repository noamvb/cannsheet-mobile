package com.example

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class QueueDepthObserverTest {
    @Test
    fun failedDepthWriteRetriesWithoutAnotherCountEmission() = runBlocking {
        val attempts = AtomicInteger()
        val delays = AtomicInteger()

        collectQueueDepthChanges(
            countChanges = flowOf(1),
            reconcileCurrentDepth = {
                if (attempts.incrementAndGet() == 1) throw IOException("temporary failure")
            },
            retryDelay = { delays.incrementAndGet() },
        )

        assertEquals(2, attempts.get())
        assertEquals(1, delays.get())
    }

    @Test
    fun newerCountCancelsOldBackoffAndReconcilesImmediately() = runBlocking {
        val counts = MutableStateFlow(1)
        val attempts = AtomicInteger()
        val oldBackoffStarted = CompletableDeferred<Unit>()
        val oldBackoffCancelled = CompletableDeferred<Unit>()
        val newCountReconciled = CompletableDeferred<Unit>()

        val collection = launch {
            collectQueueDepthChanges(
                countChanges = counts,
                reconcileCurrentDepth = {
                    if (attempts.incrementAndGet() == 1) {
                        throw IOException("temporary failure")
                    }
                    newCountReconciled.complete(Unit)
                },
                retryDelay = {
                    oldBackoffStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        oldBackoffCancelled.complete(Unit)
                    }
                },
            )
        }

        oldBackoffStarted.await()
        counts.value = 2
        newCountReconciled.await()
        oldBackoffCancelled.await()
        collection.cancelAndJoin()

        assertEquals(2, attempts.get())
    }

    @Test
    fun cancellationIsNeverRetried() = runBlocking {
        var retryAttempted = false
        val observation = async {
            collectQueueDepthChanges(
                countChanges = flowOf(1),
                reconcileCurrentDepth = { throw CancellationException("stop") },
                retryDelay = { retryAttempted = true },
            )
        }

        try {
            observation.await()
            fail("CancellationException should escape")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertFalse(retryAttempted)
    }
}
