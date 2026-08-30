package com.example.widget

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.Product
import com.example.domain.PenQuickLogState
import com.example.domain.SubmissionDateTime
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
class PenWidgetActionRouterTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var stateFile: File
    private lateinit var configFile: File
    private lateinit var repository: PenWidgetStateRepository
    private lateinit var configRepository: PenWidgetConfigRepository
    private lateinit var router: PenWidgetActionRouter
    private lateinit var penState: PenQuickLogState
    private lateinit var pinnedState: PenQuickLogState
    private var requestedPinnedProductId: String? = null
    private val scheduledTimers = mutableListOf<String>()
    private val scheduledWork = mutableListOf<String>()
    private val cancelledTimers = mutableListOf<Int>()
    private val cancelledWork = mutableListOf<Int>()
    private var nextId = 1
    private var nowMillis = 10_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stateFile = File(context.cacheDir, "pen-widget-router-${UUID.randomUUID()}.preferences_pb")
        repository = PenWidgetStateRepository(
            PreferenceDataStoreFactory.create { stateFile },
        )
        configFile = File(
            context.cacheDir,
            "pen-widget-router-config-${UUID.randomUUID()}.preferences_pb",
        )
        configRepository = PenWidgetConfigRepository(
            PreferenceDataStoreFactory.create { configFile },
        )
        penState = loadedState(secondsPerUse = 10.0)
        pinnedState = loadedState(secondsPerUse = 20.0, productId = "pen-pinned")
        router = PenWidgetActionRouter(
            stateRepository = { repository },
            configRepository = { configRepository },
            loadPenState = { _, pinnedProductId ->
                requestedPinnedProductId = pinnedProductId
                if (pinnedProductId == "pen-pinned") pinnedState else penState
            },
            scheduleTimer = { _, appWidgetId, commitId ->
                scheduledTimers += "$appWidgetId:$commitId"
            },
            cancelTimer = { appWidgetId -> cancelledTimers += appWidgetId },
            scheduleWork = { _, appWidgetId, commitId ->
                scheduledWork += "$appWidgetId:$commitId"
            },
            cancelWork = { _, appWidgetId -> cancelledWork += appWidgetId },
            now = { nowMillis },
            newId = { "id-${nextId++}" },
            submissionDateTime = { SubmissionDateTime("2026-08-13", "12:00") },
        )
    }

    @After
    fun tearDown() {
        database.close()
        stateFile.delete()
        configFile.delete()
    }

    @Test
    fun incrementsThenSubmitCaptureTheFinalDraft() = runBlocking {
        route(ACTION_INCREMENT)
        route(ACTION_INCREMENT)
        route(ACTION_SUBMIT)

        assertEquals(2 * STEP_SECONDS, repository.read(WIDGET_ID).pendingCommit?.seconds)
        assertEquals(1, scheduledTimers.size)
        assertEquals(1, scheduledWork.size)
    }

    @Test
    fun incrementAndDecrementUseTheEffectiveStep() = runBlocking {
        configRepository.write(
            WIDGET_ID,
            PenWidgetInstanceConfig(stepSecondsOverride = 30),
        )

        route(ACTION_INCREMENT)
        route(ACTION_DECREMENT)

        assertEquals(0, repository.read(WIDGET_ID).draftSeconds)
    }

    @Test
    fun incrementUsesEffectiveStepAndIgnoresAStaleIntentExtra() = runBlocking {
        configRepository.write(
            WIDGET_ID,
            PenWidgetInstanceConfig(stepSecondsOverride = 5),
        )
        val intent = Intent().apply {
            putExtra("com.noamv.cannsheet.mobile.widget.STEP_SECONDS", 30)
        }

        router.handle(context, ACTION_INCREMENT, WIDGET_ID, intent)

        assertEquals(5, repository.read(WIDGET_ID).draftSeconds)
    }

    @Test
    fun undoDuringAClaimDoesNotRestoreTheDraft() = runBlocking {
        route(ACTION_INCREMENT)
        route(ACTION_SUBMIT)
        val payload = requireNotNull(repository.read(WIDGET_ID).pendingCommit)

        repository.claimCommit(WIDGET_ID, payload.commitId, nowMillis = 20_000L)
        nowMillis = 20_100L
        route(ACTION_UNDO, commitId = payload.commitId)

        assertEquals(payload.commitId, repository.read(WIDGET_ID).pendingCommit?.commitId)
        assertEquals(0, repository.read(WIDGET_ID).draftSeconds)
        assertTrue(cancelledTimers.isEmpty())
        assertTrue(cancelledWork.isEmpty())
    }

    @Test
    fun undoBeforeAClaimRestoresTheDraft() = runBlocking {
        route(ACTION_INCREMENT)
        route(ACTION_SUBMIT)
        val payload = requireNotNull(repository.read(WIDGET_ID).pendingCommit)

        nowMillis = 10_100L
        route(ACTION_UNDO, commitId = payload.commitId)

        assertEquals(null, repository.read(WIDGET_ID).pendingCommit)
        assertEquals(STEP_SECONDS, repository.read(WIDGET_ID).draftSeconds)
        assertEquals(listOf(WIDGET_ID), cancelledTimers)
        assertEquals(listOf(WIDGET_ID), cancelledWork)
    }

    @Test
    fun submitIsRejectedWhenNoCartIsLoaded() = runBlocking {
        penState = PenQuickLogState.NoCartLoaded
        route(ACTION_INCREMENT)
        route(ACTION_SUBMIT)

        assertEquals(null, repository.read(WIDGET_ID).pendingCommit)
        assertTrue(scheduledTimers.isEmpty())
        assertTrue(scheduledWork.isEmpty())
    }

    @Test
    fun submitIsRejectedWhenTheRateIsOff() = runBlocking {
        penState = loadedState(secondsPerUse = null)
        route(ACTION_INCREMENT)
        route(ACTION_SUBMIT)

        assertEquals(null, repository.read(WIDGET_ID).pendingCommit)
        assertTrue(scheduledTimers.isEmpty())
        assertTrue(scheduledWork.isEmpty())
    }

    @Test
    fun submitUsesTheWidgetPinnedProduct() = runBlocking {
        configRepository.write(
            WIDGET_ID,
            PenWidgetInstanceConfig(pinnedProductId = "pen-pinned"),
        )

        route(ACTION_INCREMENT)
        route(ACTION_SUBMIT)

        val payload = requireNotNull(repository.read(WIDGET_ID).pendingCommit)
        assertEquals("pen-pinned", requestedPinnedProductId)
        assertEquals("pen-pinned", payload.productId)
        assertEquals(20.0, requireNotNull(payload.secondsPerUse), 0.0)
    }

    @Test
    fun resetClearsTheDraftButNotAPendingPayload() = runBlocking {
        route(ACTION_INCREMENT)
        route(ACTION_RESET)
        assertEquals(0, repository.read(WIDGET_ID).draftSeconds)

        route(ACTION_INCREMENT)
        route(ACTION_SUBMIT)
        val payload = requireNotNull(repository.read(WIDGET_ID).pendingCommit)
        route(ACTION_RESET)

        assertEquals(payload.commitId, repository.read(WIDGET_ID).pendingCommit?.commitId)
        assertEquals(0, repository.read(WIDGET_ID).draftSeconds)
    }

    private suspend fun route(
        action: String,
        commitId: String? = null,
    ) {
        val intent = Intent().apply {
            putExtra(EXTRA_COMMIT_ID, commitId)
        }
        router.handle(context, action, WIDGET_ID, intent)
    }

    private fun loadedState(
        secondsPerUse: Double?,
        productId: String = "pen-1",
    ): PenQuickLogState.Loaded =
        PenQuickLogState.Loaded(
            product = Product(
                id = productId,
                name = "Pen 1",
                type = "P",
                status = 0,
                productUuid = "uuid-1",
            ),
            presetUses = listOf(0.5, 1.0),
            secondsPerUse = secondsPerUse,
            syncedUses = null,
            pendingUses = 0.0,
        )

    private companion object {
        const val WIDGET_ID = 21
    }
}
