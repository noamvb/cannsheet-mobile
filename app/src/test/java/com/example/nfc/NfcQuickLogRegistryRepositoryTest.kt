package com.example.nfc

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcQuickLogRegistryRepositoryTest {
    private val file = File.createTempFile("nfc-registry", ".preferences_pb")
    private val dataStore = PreferenceDataStoreFactory.create { file }
    private val repository = NfcQuickLogRegistryRepository(dataStore)

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun registerVerifyRenameAndRevokeAreAtomicAndLabelsArePrivate() = runBlocking {
        val tag = NfcQuickLogContract.newTagData(3)
        assertTrue(
            repository.registerAfterVerifiedWrite(tag, "  bedside  ", registeredAtEpochMillis = 12L)
                is NfcQuickLogRegistryMutationResult.Applied,
        )
        val registered = (repository.registry.first() as NfcQuickLogRegistryState.Ready).tags.single()
        assertEquals("bedside", registered.label)
        assertEquals(
            NfcQuickLogRegistryVerification.Verified(registered),
            repository.verify(tag),
        )
        assertTrue(repository.rename(tag.tagId, "new name") is NfcQuickLogRegistryMutationResult.Applied)
        assertTrue(repository.revoke(tag.tagId) is NfcQuickLogRegistryMutationResult.Applied)
        assertEquals(emptyList<RegisteredNfcQuickLogTag>(), (repository.registry.first() as NfcQuickLogRegistryState.Ready).tags)
    }

    @Test
    fun corruptRegistryFailsClosedUntilExplicitReset() = runBlocking {
        dataStore.edit { it[stringPreferencesKey(NFC_QUICK_LOG_REGISTRY_JSON_KEY)] = "not-json" }
        assertEquals(NfcQuickLogRegistryState.Corrupt, repository.registry.first())
        assertEquals(
            NfcQuickLogRegistryMutationResult.RegistryCorrupt,
            repository.registerAfterVerifiedWrite(NfcQuickLogContract.newTagData(1), null),
        )
        assertTrue(repository.reset() is NfcQuickLogRegistryMutationResult.Applied)
        assertEquals(NfcQuickLogRegistryState.Ready(emptyList()), repository.registry.first())
    }

    @Test
    fun invalidLabelsQuantitiesAndDuplicateIdsAreRejected() = runBlocking {
        val tag = NfcQuickLogContract.newTagData(1)
        assertEquals(
            NfcQuickLogRegistryMutationResult.InvalidInput,
            repository.registerAfterVerifiedWrite(tag, "x".repeat(41)),
        )
        assertEquals(
            NfcQuickLogRegistryMutationResult.InvalidInput,
            repository.registerAfterVerifiedWrite(tag.copy(uses = 11), null),
        )
        assertTrue(repository.registerAfterVerifiedWrite(tag, null) is NfcQuickLogRegistryMutationResult.Applied)
        assertEquals(
            NfcQuickLogRegistryMutationResult.TagAlreadyRegistered,
            repository.adoptVerifiedTag(tag, null),
        )
    }
}
