package com.example.widget

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.CannsheetRepository
import com.example.data.ConsumptionLogger
import com.example.data.ConsumptionPreferencesRepository
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
