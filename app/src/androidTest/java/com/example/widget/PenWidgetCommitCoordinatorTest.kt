package com.example.widget

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.CannsheetRepository
import com.example.data.ConsumptionAction
import com.example.data.ConsumptionLogRepository
import com.example.data.ConsumptionLogger
import com.example.data.ConsumptionPreferencesRepository
import com.example.data.LoadedPenProductStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PenWidgetCommitCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var stateFile: File
    private lateinit var preferencesFile: File
    private lateinit var stateRepository: PenWidgetStateRepository
    private lateinit var repository: CannsheetRepository
    private lateinit var coordinator: PenWidgetCommitCoordinator
    private var syncCalls = 0
    private var updateCalls = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CannsheetRepository(database)
        stateFile = File(context.cacheDir, "pen-widget-state-${UUID.randomUUID()}.preferences_pb")
        preferencesFile = File(context.cacheDir, "pen-widget-preferences-${UUID.randomUUID()}.preferences_pb")
        stateRepository = PenWidgetStateRepository(
            PreferenceDataStoreFactory.create { stateFile },
        )
        val preferences = ConsumptionPreferencesRepository(
            PreferenceDataStoreFactory.create { preferencesFile },
        )
        coordinator = PenWidgetCommitCoordinator(
            stateRepository = stateRepository,
            consumptionLogger = ConsumptionLogger(repository, preferences),
            enqueueSync = { syncCalls += 1 },
            updateWidget = { _, _ -> updateCalls += 1 },
        )
    }

    @After
    fun tearDown() {
        database.close()
        stateFile.delete()
        preferencesFile.delete()
    }

    @Test
    fun commitWritesUsesToRoomAndSchedulesSharedSync() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = payload()
        stateRepository.submitCommit(42, payload)

        coordinator.commit(context, 42, payload.commitId)

        assertEquals(3.0, repository.getPendingConsumptions().single().uses, 0.0)
        assertEquals(1, syncCalls)
        assertEquals(1, updateCalls)
    }

    @Test
    fun undoLeavesRoomUntouchedAndRestoresDraft() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = payload()
        stateRepository.submitCommit(42, payload)
        assertTrue(stateRepository.undo(42, payload.commitId))

        coordinator.commit(context, 42, payload.commitId)

        assertTrue(repository.getPendingConsumptions().isEmpty())
        assertEquals(30, stateRepository.read(42).draftSeconds)
        assertEquals(0, syncCalls)
    }

    @Test
    fun overdueFlushCommitsWithoutWorkerId() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = payload().copy(commitAtEpochMillis = 1_000L)
        stateRepository.submitCommit(42, payload)

        coordinator.flushOverdue(context, nowMillis = 1_000L)

        assertEquals(3.0, repository.getPendingConsumptions().single().uses, 0.0)
        assertEquals(1, syncCalls)
    }

    @Test
    fun failedRoomWriteKeepsPayloadAndRetryUsesTheSameEventId() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failingRepository = ThrowOnceRepository()
        val retryingCoordinator = PenWidgetCommitCoordinator(
            stateRepository = stateRepository,
            consumptionLogger = ConsumptionLogger(failingRepository, NoOpLoadedPenStore),
            enqueueSync = { syncCalls += 1 },
            updateWidget = { _, _ -> updateCalls += 1 },
        )
        val payload = payload()
        stateRepository.submitCommit(51, payload)

        val failure = runCatching {
            retryingCoordinator.commit(context, 51, payload.commitId, nowMillis = 5_000L)
        }

        assertTrue(failure.isFailure)
        assertEquals(payload.eventId, stateRepository.read(51).pendingCommit?.eventId)
        assertEquals(null, stateRepository.read(51).pendingCommit?.claimId)

        retryingCoordinator.commit(context, 51, payload.commitId, nowMillis = 5_001L)

        assertEquals(listOf(payload.eventId), failingRepository.actions.map { it.eventId })
        assertEquals(listOf(payload.submittedAtEpochMillis), failingRepository.loggedAtEpochMillis)
        assertEquals(null, stateRepository.read(51).pendingCommit)
        assertEquals(1, syncCalls)
    }

    @Test
    fun forceCommitRecordsFreshPayloadBeforeWidgetStateIsCleared() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = payload().copy(
            submittedAtEpochMillis = 10_000L,
            commitAtEpochMillis = 10_000L + COMMIT_DELAY_MILLIS,
        )
        stateRepository.submitCommit(52, payload)

        assertTrue(
            coordinator.commit(
                context,
                52,
                commitId = null,
                nowMillis = 10_001L,
                force = true,
            ),
        )

        assertEquals(payload.eventId, repository.getPendingConsumptions().single().eventId)
        assertEquals(null, stateRepository.read(52).pendingCommit)
    }

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        eventId = "event-1",
        submittedAtEpochMillis = 0L,
        commitAtEpochMillis = 5_000L,
        productId = "pen-1",
        productUuid = "uuid-1",
        seconds = 30,
        secondsPerUse = 10.0,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )

    private class ThrowOnceRepository : ConsumptionLogRepository {
        var shouldFail = true
        val actions = mutableListOf<ConsumptionAction>()
        val loggedAtEpochMillis = mutableListOf<Long>()

        override suspend fun addConsumption(action: ConsumptionAction, loggedAtEpochMillis: Long) {
            if (shouldFail) {
                shouldFail = false
                error("simulated Room failure")
            }
            actions += action
            this.loggedAtEpochMillis += loggedAtEpochMillis
        }
    }

    private object NoOpLoadedPenStore : LoadedPenProductStore {
        override suspend fun setLoadedPenProductId(productId: String) = Unit

        override suspend fun clearLoadedPenProductId() = Unit
    }
}
