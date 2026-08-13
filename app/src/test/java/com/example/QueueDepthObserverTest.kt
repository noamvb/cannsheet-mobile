package com.example

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
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
            reconcileObservedDepth = {
                if (attempts.incrementAndGet() == 1) throw IOException("temporary failure")
            },
            retryDelay = { delays.incrementAndGet() },
        )

        assertEquals(2, attempts.get())
        assertEquals(1, delays.get())
    }

    @Test
    fun emptyTransitionIsRetriedBeforeTheFollowingPositiveCount() = runBlocking {
        val attempts = mutableListOf<Int>()
        val applied = mutableListOf<Int>()
        var failFirstEmpty = true

        collectQueueDepthChanges(
            countChanges = flowOf(1, 0, 1),
            reconcileObservedDepth = { count ->
                attempts += count
                if (count == 0 && failFirstEmpty) {
                    failFirstEmpty = false
                    throw IOException("temporary failure")
                }
                applied += count
            },
            retryDelay = {},
        )

        assertEquals(listOf(1, 0, 0, 1), attempts)
        assertEquals(listOf(1, 0, 1), applied)
    }

    @Test
    fun cancellationIsNeverRetried() = runBlocking {
        var retryAttempted = false
        val observation = async {
            collectQueueDepthChanges(
                countChanges = flowOf(1),
                reconcileObservedDepth = { throw CancellationException("stop") },
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
