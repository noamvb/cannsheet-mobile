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

        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.read(31))
        // read() returning DEFAULT is not sufficient: it would also return DEFAULT if the values
        // were removed but the migrated flag survived. Assert on the raw keys.
        val remaining = configDataStore.data.first().asMap().keys.map { it.name }
        assertTrue(
            "clear left keys behind: $remaining",
            remaining.none { it.endsWith("_31") },
        )
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
    fun migrationCopiesLegacyConfigOnceAndClearsTheLegacyKeys() = runBlocking {
        val legacyConfig = PenWidgetInstanceConfig(
            pinnedProductId = "legacy-product",
            discreet = true,
            stepSecondsOverride = 20,
        )
        seedLegacyConfig(50, legacyConfig)

        repository.migrateFromLegacyStore(50, legacyRepository)

        assertEquals(legacyConfig, repository.read(50))
        assertEquals(PenWidgetInstanceConfig.DEFAULT, legacyRepository.readLegacyConfig(50))
    }

    @Test
    fun migrationIsIdempotent() = runBlocking {
        val legacyConfig = PenWidgetInstanceConfig(
            pinnedProductId = "legacy-product",
            discreet = true,
            stepSecondsOverride = 20,
        )
        seedLegacyConfig(51, legacyConfig)

        repository.migrateFromLegacyStore(51, legacyRepository)

        // A real edit made after the first migration must survive a second migration call. If
        // the per-widget migrated flag were not respected, this second call would read the now
        // empty legacy store and overwrite the fresh edit with defaults.
        val updatedConfig = legacyConfig.copy(discreet = false)
        repository.write(51, updatedConfig)

        repository.migrateFromLegacyStore(51, legacyRepository)

        assertEquals(updatedConfig, repository.read(51))
    }

    @Test
    fun migrationLeavesDraftsAndPendingPayloadsInTheLegacyStore() = runBlocking {
        seedLegacyConfig(52, PenWidgetInstanceConfig(pinnedProductId = "draft-widget"))
        legacyRepository.setDraftSeconds(52, 40)

        seedLegacyConfig(53, PenWidgetInstanceConfig(pinnedProductId = "pending-widget"))
        val payload = legacyPayload(commitId = "commit-53")
        assertTrue(legacyRepository.submitCommit(53, payload))

        repository.migrateFromLegacyStore(52, legacyRepository)
        repository.migrateFromLegacyStore(53, legacyRepository)

        // The migrated config left the legacy store...
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
        seconds = 40,
        secondsPerUse = 10.0,
        uses = 4.0,
        date = "2026-08-20",
        time = "12:00",
    )
}
