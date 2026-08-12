package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSubmissionTest {
    @Test
    fun replaceWithReturnsNullWhenNothingWasPending() {
        val pending = PendingSubmission()
        val action: () -> Unit = {}

        assertNull(pending.replaceWith(action))
        assertEquals(action, pending.take())
    }

    @Test
    fun replaceWithReturnsTheDisplacedActionSoItIsNotDropped() {
        val pending = PendingSubmission()
        val first: () -> Unit = {}
        val second: () -> Unit = {}

        pending.replaceWith(first)

        assertEquals(first, pending.replaceWith(second))
        assertEquals(second, pending.take())
    }

    @Test
    fun takeReturnsTheActionOnceAndNullAfterwards() {
        val pending = PendingSubmission()
        val action: () -> Unit = {}
        pending.replaceWith(action)

        assertEquals(action, pending.take())
        assertNull(pending.take())
    }

    @Test
    fun queueThenQueueThenTakeRunsBothActionsExactlyOnce() {
        val pending = PendingSubmission()
        val invocations = mutableListOf<String>()

        pending.replaceWith { invocations += "first" }
        pending.replaceWith { invocations += "second" }?.invoke()
        pending.take()?.invoke()

        assertEquals(listOf("first", "second"), invocations)
    }
}
