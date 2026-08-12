package com.example.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PenWidgetRuntimeTest {
    @Test
    fun commitTimerRendersSavingThenCommitsAfterGrace() = runBlocking {
        val waits = mutableListOf<Long>()
        val events = mutableListOf<String>()
        val committed = CompletableDeferred<Unit>()
        val timer = PenWidgetCommitTimer<String>(
            scope = CoroutineScope(Dispatchers.Unconfined),
            wait = { waits += it },
            renderSaving = { _, id -> events += "saving-$id" },
            commit = { _, id, commitId ->
                events += "commit-$id-$commitId"
                committed.complete(Unit)
            },
        )

        timer.schedule("context", 42, "commit-42")
        withTimeout(1_000L) { committed.await() }

        assertEquals(listOf(UNDO_WINDOW_MILLIS, COMMIT_GRACE_MILLIS), waits)
        assertEquals(listOf("saving-42", "commit-42-commit-42"), events)
    }

    @Test
    fun cancelStopsTimerBeforeCommit() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var committed = false
        val timer = PenWidgetCommitTimer<String>(
            scope = this,
            wait = { gate.await() },
            renderSaving = { _, _ -> Unit },
            commit = { _, _, _ -> committed = true },
        )

        timer.schedule("context", 7, "commit-7")
        yield()
        timer.cancel(7)
        gate.complete(Unit)
        yield()

        assertFalse(committed)
    }

    @Test
    fun serializerKeepsMutationAndRenderOrder() = runBlocking {
        val serializer = WidgetWorkSerializer()
        val events = mutableListOf<String>()

        coroutineScope {
            val first = async {
                serializer.run {
                    events += "first-start"
                    delay(25L)
                    events += "first-end"
                }
            }
            yield()
            val second = async {
                serializer.run { events += "second" }
            }
            first.await()
            second.await()
        }

        assertEquals(listOf("first-start", "first-end", "second"), events)
    }
}
