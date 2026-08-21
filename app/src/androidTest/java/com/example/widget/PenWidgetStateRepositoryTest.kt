package com.example.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: PenWidgetStateRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dataStoreFile = File(context.cacheDir, "pen-widget-${UUID.randomUUID()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create { dataStoreFile }
        repository = PenWidgetStateRepository(dataStore)
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
    fun submitUndoClaimAndCompleteAreAtomicStateTransitions() = runBlocking {
        val payload = payload()
        assertTrue(repository.submitCommit(7, payload))
        assertFalse(repository.submitCommit(7, payload.copy(commitId = "second")))
        assertTrue(repository.undo(7, payload.commitId))
        assertEquals(30, repository.read(7).draftSeconds)
        assertEquals(null, repository.read(7).pendingCommit)

        assertTrue(repository.submitCommit(7, payload))
        val claim = repository.claimCommit(7, payload.commitId, 5_000L)
        assertEquals(payload.eventId, claim?.payload?.eventId)
        assertEquals(null, repository.claimCommit(7, payload.commitId, 5_001L))
        assertTrue(repository.completeCommit(7, payload.commitId, requireNotNull(claim).claimId, 5_001L))
        assertEquals(5_001L, repository.read(7).lastQueuedAtMillis)
        assertEquals(null, repository.claimCommit(7, payload.commitId, 6_000L))
    }

    @Test
    fun undoCannotStealAClaimedPayload() = runBlocking {
        val payload = payload()
        repository.submitCommit(21, payload)
        val claim = repository.claimCommit(21, payload.commitId, nowMillis = 10_000L)
        assertTrue(claim != null)

        assertFalse(repository.undo(21, payload.commitId, nowMillis = 10_100L))
        assertEquals(payload.commitId, repository.read(21).pendingCommit?.commitId)
        assertEquals(0, repository.read(21).draftSeconds)
    }

    @Test
    fun submitBuilderCapturesTheDraftInsideTheAtomicEdit() = runBlocking {
        repository.adjustDraftSeconds(8, STEP_SECONDS)
        repository.adjustDraftSeconds(8, STEP_SECONDS)

        val submitted = repository.submitCommit(8) { seconds ->
            payload().copy(
                seconds = seconds,
                restoreDraftSeconds = seconds,
                uses = seconds / 10.0,
            )
        }

        assertEquals(20, submitted?.seconds)
        assertEquals(2.0, submitted?.uses ?: 0.0, 0.0)
        assertEquals(0, repository.read(8).draftSeconds)
    }

    @Test
    fun failedClaimCanReleaseAndStaleClaimCanBeRecovered() = runBlocking {
        val payload = payload()
        repository.submitCommit(11, payload)

        val first = requireNotNull(repository.claimCommit(11, payload.commitId, 5_000L))
        assertTrue(repository.releaseClaim(11, payload.commitId, first.claimId))
        val second = requireNotNull(repository.claimCommit(11, payload.commitId, 5_001L))
        assertEquals(payload.eventId, second.payload.eventId)

        val recovered = repository.claimCommit(
            11,
            payload.commitId,
            5_001L + CLAIM_STALE_MILLIS,
        )
        assertEquals(payload.eventId, recovered?.payload?.eventId)
        assertFalse(second.claimId == recovered?.claimId)
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

    @Test
    fun tileIdRoundTripsThroughTheSameStateMachinery() = runBlocking {
        repository.setDraftSeconds(PEN_TILE_WIDGET_ID, 30)
        val submitted = requireNotNull(
            repository.submitCommit(PEN_TILE_WIDGET_ID) { seconds ->
                payload().copy(
                    seconds = seconds,
                    restoreDraftSeconds = seconds,
                    uses = seconds / 10.0,
                )
            },
        )

        assertEquals(PEN_TILE_WIDGET_ID, repository.pendingCommits().single().appWidgetId)
        val claim = requireNotNull(
            repository.claimCommit(
                appWidgetId = PEN_TILE_WIDGET_ID,
                commitId = submitted.commitId,
                nowMillis = submitted.commitAtEpochMillis,
            ),
        )
        assertTrue(
            repository.completeCommit(
                appWidgetId = PEN_TILE_WIDGET_ID,
                commitId = submitted.commitId,
                claimId = claim.claimId,
                nowMillis = submitted.commitAtEpochMillis,
            ),
        )
        assertEquals(null, repository.read(PEN_TILE_WIDGET_ID).pendingCommit)
    }

    @Test
    fun directSubmitIsAtomicRefusesAnExistingPendingCommitAndLeavesDraftUntouched() = runBlocking {
        val surfaceId = 73
        repository.setDraftSeconds(surfaceId, 50)
        val direct = payload().copy(
            commitId = "direct-commit",
            eventId = "direct-event",
            inputKind = DeferredPenInputKind.DIRECT_USES,
            seconds = null,
            secondsPerUse = null,
            restoreDraftSeconds = null,
            uses = 1.0,
        )

        var rejectedBuilderCalls = 0
        val accepted = repository.submitDirectCommit(surfaceId) { direct }
        val rejected = repository.submitDirectCommit(surfaceId) {
            rejectedBuilderCalls += 1
            direct.copy(commitId = "second", eventId = "second")
        }

        assertEquals(direct, accepted)
        assertEquals(null, rejected)
        assertEquals(0, rejectedBuilderCalls)
        assertEquals(50, repository.read(surfaceId).draftSeconds)
        assertTrue(repository.undo(surfaceId, direct.commitId, nowMillis = 1_000L))
        assertEquals(null, repository.read(surfaceId).pendingCommit)
        assertEquals(50, repository.read(surfaceId).draftSeconds)
    }

    @Test
    fun directSubmitPreservesUnknownRawPendingStateWithoutCallingTheBuilder() = runBlocking {
        val surfaceId = 73
        val key = stringPreferencesKey("pending_commit_$surfaceId")
        val unknown = """{"version":99,"future":"opaque"}"""
        dataStore.edit { preferences -> preferences[key] = unknown }
        var builderCalls = 0

        val accepted = repository.submitDirectCommit(surfaceId) {
            builderCalls += 1
            payload().copy(
                inputKind = DeferredPenInputKind.DIRECT_USES,
                seconds = null,
                secondsPerUse = null,
                restoreDraftSeconds = null,
            )
        }

        assertEquals(null, accepted)
        assertEquals(0, builderCalls)
        assertEquals(unknown, dataStore.data.first()[key])
    }

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        eventId = "event-1",
        submittedAtEpochMillis = 0L,
        commitAtEpochMillis = 5_000L,
        productId = "pen-1",
        productUuid = "uuid-1",
        inputKind = DeferredPenInputKind.DURATION_SECONDS,
        seconds = 30,
        secondsPerUse = 10.0,
        restoreDraftSeconds = 30,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
