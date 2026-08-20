package com.example.widget

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PenWidgetConfigStateTest {
    private lateinit var dataStoreFile: File
    private lateinit var repository: PenWidgetStateRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dataStoreFile = File(
            context.cacheDir,
            "pen-widget-config-${UUID.randomUUID()}.preferences_pb",
        )
        repository = PenWidgetStateRepository(
            PreferenceDataStoreFactory.create { dataStoreFile },
        )
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
    }

    @Test
    fun writeConfigThenReadConfigRoundTrips() = runBlocking {
        val config = PenWidgetInstanceConfig(
            pinnedProductId = "product-1",
            discreet = true,
            stepSecondsOverride = 45,
        )

        repository.writeConfig(17, config)

        assertEquals(config, repository.readConfig(17))
    }

    @Test
    fun readConfigOnAnUnconfiguredWidgetReturnsDefaults() = runBlocking {
        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.readConfig(23))
    }

    @Test
    fun clearRemovesEveryConfigKey() = runBlocking {
        repository.writeConfig(
            31,
            PenWidgetInstanceConfig(
                pinnedProductId = "product-to-clear",
                discreet = true,
                stepSecondsOverride = 60,
            ),
        )

        repository.clear(31)

        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.readConfig(31))
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

        repository.writeConfig(41, firstConfig)
        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.readConfig(42))

        repository.writeConfig(42, secondConfig)

        assertEquals(firstConfig, repository.readConfig(41))
        assertEquals(secondConfig, repository.readConfig(42))
    }
}
