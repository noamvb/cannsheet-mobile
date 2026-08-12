package com.example.widget

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PenWidgetStateRepositoryTest {
    private lateinit var dataStoreFile: File
    private lateinit var repository: PenWidgetStateRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dataStoreFile = File(context.cacheDir, "pen-widget-${UUID.randomUUID()}.preferences_pb")
        repository = PenWidgetStateRepository(
            PreferenceDataStoreFactory.create { dataStoreFile },
        )
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
    }

    @Test
    fun concurrentAdjustCallsDoNotLoseIncrements() = runBlocking {
        coroutineScope {
            (0 until 20).map {
                async { repository.adjustDraftSeconds(42, STEP_SECONDS) }
            }.awaitAll()
        }

        assertEquals(200, repository.read(42).draftSeconds)
    }

    @Test
    fun submitUndoAndCommitAreAtomicStateTransitions() = runBlocking {
        val payload = payload()
        assertTrue(repository.submitCommit(7, payload))
        assertFalse(repository.submitCommit(7, payload.copy(commitId = "second")))
        assertTrue(repository.undo(7, payload.commitId))
        assertEquals(30, repository.read(7).draftSeconds)
        assertEquals(null, repository.read(7).pendingCommit)

        assertTrue(repository.submitCommit(7, payload))
        assertEquals(payload, repository.takeCommit(7, payload.commitId, 1_000L))
        assertEquals(1_000L, repository.read(7).lastQueuedAtMillis)
        assertEquals(null, repository.takeCommit(7, payload.commitId, 2_000L))
    }

    @Test
    fun clearRemovesAllPerWidgetState() = runBlocking {
        val payload = payload()
        repository.submitCommit(9, payload)
        repository.clear(9)

        val state = repository.read(9)
        assertEquals(0, state.draftSeconds)
        assertEquals(null, state.pendingCommit)
        assertEquals(null, state.lastQueuedAtMillis)
    }

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        commitAtEpochMillis = 5_000L,
        productId = "pen-1",
        productUuid = "uuid-1",
        seconds = 30,
        secondsPerUse = 10.0,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
