package com.example.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubmissionTimerPreferenceTest {
    private val file = File.createTempFile("consumption-preferences", ".preferences_pb")
        .also(File::delete)

    @After
    fun tearDown() {
        file.delete()
    }

    /**
     * The production failure: the cancel window lived only in the ViewModel, so an app restart
     * reset a chosen 2 seconds back to 5. A fresh DataStore over the same file is a restart.
     */
    @Test
    fun chosenTimerSurvivesAProcessRestart() = runBlocking {
        withRepository { repository ->
            repository.setSubmissionTimerSeconds(2)
        }

        withRepository { repository ->
            assertEquals(2, repository.submissionTimerSeconds.first())
        }
    }

    @Test
    fun zeroSecondsIsStoredRatherThanTreatedAsMissing() = runBlocking {
        withRepository { repository -> repository.setSubmissionTimerSeconds(0) }

        withRepository { repository ->
            assertEquals(0, repository.submissionTimerSeconds.first())
        }
    }

    @Test
    fun missingValueDefaultsToFiveSeconds() = runBlocking {
        withRepository { repository ->
            assertEquals(5, repository.submissionTimerSeconds.first())
        }
    }

    @Test
    fun outOfRangeStoredValueFallsBackToTheDefault() = runBlocking {
        withRepository { repository ->
            repository.setSubmissionTimerSeconds(1)
        }
        withDataStore { dataStore ->
            dataStore.edit { it[intPreferencesKey("submission_timer_seconds")] = 99 }
        }

        withRepository { repository ->
            assertEquals(5, repository.submissionTimerSeconds.first())
        }
    }

    @Test
    fun rejectsOutOfRangeWrites() = runBlocking {
        withRepository { repository ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setSubmissionTimerSeconds(6) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setSubmissionTimerSeconds(-1) }
            }
            assertEquals(5, repository.submissionTimerSeconds.first())
        }
    }

    private suspend fun withRepository(block: suspend (ConsumptionPreferencesRepository) -> Unit) =
        withDataStore { dataStore -> block(ConsumptionPreferencesRepository(dataStore)) }

    private suspend fun withDataStore(
        block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit,
    ) {
        // One active DataStore per file: close each "process" before the next one opens.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            block(PreferenceDataStoreFactory.create(scope = scope) { file })
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
