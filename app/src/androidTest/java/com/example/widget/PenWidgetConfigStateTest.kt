package com.example.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PenWidgetConfigStateTest {
    private lateinit var configFile: File
    private lateinit var legacyFile: File
    private lateinit var configDataStore: DataStore<Preferences>
    private lateinit var legacyDataStore: DataStore<Preferences>
    private lateinit var repository: PenWidgetConfigRepository
    private lateinit var legacyRepository: PenWidgetStateRepository
    private val extraFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configFile = File(
            context.cacheDir,
            "pen-widget-config-${UUID.randomUUID()}.preferences_pb",
        )
        legacyFile = File(
            context.cacheDir,
            "pen-widget-config-legacy-${UUID.randomUUID()}.preferences_pb",
        )
        configDataStore = PreferenceDataStoreFactory.create { configFile }
        repository = PenWidgetConfigRepository(configDataStore)
        legacyDataStore = PreferenceDataStoreFactory.create { legacyFile }
        legacyRepository = PenWidgetStateRepository(legacyDataStore)
    }

    @After
    fun tearDown() {
        configFile.delete()
        legacyFile.delete()
        extraFiles.forEach { it.delete() }
    }

    @Test
    fun writeThenReadRoundTrips() = runBlocking {
        val config = PenWidgetInstanceConfig(
            pinnedProductId = "product-1",
            discreet = true,
            stepSecondsOverride = 45,
        )

        repository.write(17, config)

        assertEquals(config, repository.read(17))
    }

    @Test
    fun readOnAnUnconfiguredWidgetReturnsDefaults() = runBlocking {
        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.read(23))
    }

    @Test
    fun clearRemovesEveryConfigKey() = runBlocking {
        repository.write(
            31,
            PenWidgetInstanceConfig(
                pinnedProductId = "product-to-clear",
                discreet = true,
                stepSecondsOverride = 60,
            ),
        )

        repository.clear(31)

        // Check the raw keys immediately after clear(), before any read(). read() now adopts (and
        // persists) legacy config for an unmigrated id - calling it here first would leave a fresh
        // discreet_31/migrated_31 pair behind and make this assertion look like a leak when it
        // isn't one.
        val remaining = configDataStore.data.first().asMap().keys.map { it.name }
        assertTrue(
            "clear left keys behind: $remaining",
            remaining.none { it.endsWith("_31") },
        )
        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.read(31))
    }

    @Test
    fun configForOneWidgetDoesNotLeakToAnother() = runBlocking {
        val firstConfig = PenWidgetInstanceConfig(
            pinnedProductId = "product-1",
            discreet = true,
            stepSecondsOverride = 15,
        )
        val secondConfig = PenWidgetInstanceConfig(
            pinnedProductId = "product-2",
            discreet = false,
            stepSecondsOverride = 30,
        )

        repository.write(41, firstConfig)
        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.read(42))

        repository.write(42, secondConfig)

        assertEquals(firstConfig, repository.read(41))
        assertEquals(secondConfig, repository.read(42))
    }

    @Test
    fun readAdoptsLegacyConfigOnFirstReadWithoutAnyUpdate() = runBlocking {
        val legacyConfig = PenWidgetInstanceConfig(
            pinnedProductId = "legacy-product",
            discreet = true,
            stepSecondsOverride = 20,
        )
        val repo = PenWidgetConfigRepository(
            newDataStore(),
            readLegacy = { legacyConfig },
            clearLegacy = { },
        )

        // No PenWidgetUpdater.update and no separate migration call - a bare read() must still see
        // the user's pinned cart, matching what PenWidgetActionRouter.handle and
        // PenWidgetConfigureActivity.loadConfiguration do on the very first post-upgrade call,
        // before any update() has necessarily run for this widget id.
        assertEquals(legacyConfig, repo.read(60))
    }

    @Test
    fun readClearsTheLegacyKeysAfterAdopting() = runBlocking {
        var clearLegacyCalls = 0
        var clearedWidgetId: Int? = null
        val repo = PenWidgetConfigRepository(
            newDataStore(),
            readLegacy = { PenWidgetInstanceConfig(pinnedProductId = "legacy-product") },
            clearLegacy = { id ->
                clearLegacyCalls++
                clearedWidgetId = id
            },
        )

        repo.read(61)

        assertEquals(1, clearLegacyCalls)
        assertEquals(61, clearedWidgetId)
    }

    @Test
    fun readIsIdempotentAndDoesNotReadLegacyTwice() = runBlocking {
        var readLegacyCalls = 0
        val repo = PenWidgetConfigRepository(
            newDataStore(),
            readLegacy = {
                readLegacyCalls++
                PenWidgetInstanceConfig(pinnedProductId = "legacy-product")
            },
            clearLegacy = { },
        )

        repo.read(62)
        assertEquals(1, readLegacyCalls)

        repo.read(62)
        assertEquals(1, readLegacyCalls)
    }

    @Test
    fun aConcurrentSaveWinsOverLegacyAdoption() = runBlocking {
        var readLegacyCalls = 0
        val repo = PenWidgetConfigRepository(
            newDataStore(),
            readLegacy = {
                readLegacyCalls++
                PenWidgetInstanceConfig(pinnedProductId = "legacy-product")
            },
            clearLegacy = { },
        )
        val userConfig = PenWidgetInstanceConfig(pinnedProductId = "user-product", discreet = true)
        // Simulates a save that already completed - and therefore already set the migrated marker
        // - before this read() runs, exactly like PenWidgetConfigureActivity.save racing the first
        // post-upgrade read for the same widget id.
        repo.write(63, userConfig)

        val result = repo.read(63)

        assertEquals(userConfig, result)
        assertEquals(0, readLegacyCalls)
    }

    @Test
    fun migrationLeavesDraftsAndPendingPayloadsInTheLegacyStore() = runBlocking {
        seedLegacyConfig(52, PenWidgetInstanceConfig(pinnedProductId = "draft-widget"))
        legacyRepository.setDraftSeconds(52, 40)

        seedLegacyConfig(53, PenWidgetInstanceConfig(pinnedProductId = "pending-widget"))
        val payload = legacyPayload(commitId = "commit-53")
        assertTrue(legacyRepository.submitCommit(53, payload))

        val configBackedByLegacy = PenWidgetConfigRepository(
            newDataStore(),
            readLegacy = { legacyRepository.readLegacyConfig(it) },
            clearLegacy = { legacyRepository.clearLegacyConfig(it) },
        )
        configBackedByLegacy.read(52)
        configBackedByLegacy.read(53)

        // Reading adopted (and cleared) the config out of the legacy store...
        assertEquals(PenWidgetInstanceConfig.DEFAULT, legacyRepository.readLegacyConfig(52))
        assertEquals(PenWidgetInstanceConfig.DEFAULT, legacyRepository.readLegacyConfig(53))
        // ...but the draft and the pending commit payload, which are not config, stayed behind.
        assertEquals(40, legacyRepository.read(52).draftSeconds)
        assertEquals(payload, legacyRepository.read(53).pendingCommit)
    }

    /**
     * Writes the three legacy config keys directly, bypassing [PenWidgetStateRepository] (which
     * no longer exposes a config writer) to simulate a pre-v1.5.1 install that still has
     * configuration sitting in the old store.
     */
    private suspend fun seedLegacyConfig(appWidgetId: Int, config: PenWidgetInstanceConfig) {
        legacyDataStore.edit { preferences ->
            config.pinnedProductId?.let {
                preferences[stringPreferencesKey("pinned_product_$appWidgetId")] = it
            }
            preferences[booleanPreferencesKey("discreet_$appWidgetId")] = config.discreet
            config.stepSecondsOverride?.let {
                preferences[intPreferencesKey("step_override_$appWidgetId")] = it
            }
        }
    }

    private fun legacyPayload(commitId: String) = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = commitId,
        eventId = "event-$commitId",
        submittedAtEpochMillis = 0L,
        commitAtEpochMillis = 5_000L,
        productId = "pen-1",
        productUuid = "uuid-1",
        inputKind = DeferredPenInputKind.DURATION_SECONDS,
        seconds = 40,
        secondsPerUse = 10.0,
        restoreDraftSeconds = 40,
        uses = 4.0,
        date = "2026-08-20",
        time = "12:00",
    )

    /** A fresh file-backed DataStore for tests that wire their own readLegacy/clearLegacy fakes. */
    private fun newDataStore(): DataStore<Preferences> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "pen-widget-config-extra-${UUID.randomUUID()}.preferences_pb")
        extraFiles += file
        return PreferenceDataStoreFactory.create { file }
    }
}
