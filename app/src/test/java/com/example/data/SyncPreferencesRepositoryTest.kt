package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPreferencesRepositoryTest {
    @Test
    fun defaultsKeepBackgroundSyncEnabledWithoutARecordedResult() {
        val preferences = SyncPreferences()

        assertTrue(preferences.enabled)
        assertNull(preferences.lastMeaningfulSyncAtEpochMillis)
        assertNull(preferences.lastResult)
    }

    @Test
    fun usesStableDedicatedDataStoreAndKeyNames() {
        assertEquals("sync_preferences", SYNC_PREFERENCES_DATA_STORE_NAME)
        assertEquals("background_sync_enabled", BACKGROUND_SYNC_ENABLED_KEY)
        assertEquals("last_background_sync_epoch_millis", LAST_BACKGROUND_SYNC_EPOCH_MILLIS_KEY)
        assertEquals("last_background_sync_result", LAST_BACKGROUND_SYNC_RESULT_KEY)
    }

    @Test
    fun resultEnumKeepsDistinctTerminalStatesForSettings() {
        assertEquals(
            setOf(
                BackgroundSyncResult.SUCCESS,
                BackgroundSyncResult.PARTIAL_REJECTIONS,
                BackgroundSyncResult.BACKEND_CAPABILITY_PENDING,
                BackgroundSyncResult.COMPLETED_WITHOUT_ACK,
                BackgroundSyncResult.RETRY_EXHAUSTED,
                BackgroundSyncResult.ENVIRONMENT_MISMATCH,
            ),
            BackgroundSyncResult.entries.toSet(),
        )
    }
}
