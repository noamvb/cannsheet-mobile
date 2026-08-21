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

/** Boundary and recovery cases for the versioned, fail-closed NFC registry. */
class NfcQuickLogRegistryValidationTest {
    private val file = File.createTempFile("nfc-registry-validation", ".preferences_pb")
    private val dataStore = PreferenceDataStoreFactory.create { file }
    private val repository = NfcQuickLogRegistryRepository(dataStore)

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun exactlyFiftyTagsAreAcceptedButTheFiftyFirstIsRejected() = runBlocking {
        repeat(NfcQuickLogRegistryRepository.MAX_REGISTERED_TAGS) { index ->
            val result = repository.registerAfterVerifiedWrite(
                verifiedTag = NfcQuickLogTagData(
                    tagId = UUIDS[index],
                    uses = index % NfcQuickLogContract.MAX_USES + 1,
                ),
                label = "tag-$index",
                registeredAtEpochMillis = index.toLong(),
            )
            assertTrue(result is NfcQuickLogRegistryMutationResult.Applied)
        }

        assertEquals(
            NfcQuickLogRegistryMutationResult.CapacityReached,
            repository.registerAfterVerifiedWrite(
                NfcQuickLogTagData(UUIDS.last(), 1),
                label = "overflow",
            ),
        )
        assertEquals(
            NfcQuickLogRegistryRepository.MAX_REGISTERED_TAGS,
            (repository.registry.first() as NfcQuickLogRegistryState.Ready).tags.size,
        )
    }

    @Test
    fun emojiCodePointLimitAndMalformedSurrogatesAreEnforced() = runBlocking {
        val tag = NfcQuickLogTagData(UUIDS[0], 1)
        val fortyEmoji = "\uD83D\uDE00".repeat(40)

        assertTrue(
            repository.registerAfterVerifiedWrite(tag, fortyEmoji)
                is NfcQuickLogRegistryMutationResult.Applied,
        )
        assertEquals(
            NfcQuickLogRegistryMutationResult.InvalidInput,
            repository.registerAfterVerifiedWrite(NfcQuickLogTagData(UUIDS[1], 1), "\uD83D\uDE00".repeat(41)),
        )
        assertEquals(
            NfcQuickLogRegistryMutationResult.InvalidInput,
            repository.registerAfterVerifiedWrite(NfcQuickLogTagData(UUIDS[2], 1), "\uD800"),
        )
    }

    @Test
    fun repairPreservesLabelAndRegistrationTimeWhileChangingOnlyUses() = runBlocking {
        val original = NfcQuickLogTagData(UUIDS[0], 1)
        repository.registerAfterVerifiedWrite(original, " bedside ", registeredAtEpochMillis = 77L)

        assertEquals(
            NfcQuickLogRegistryMutationResult.Applied(
                NfcQuickLogRegistryState.Ready(
                    listOf(
                        RegisteredNfcQuickLogTag(UUIDS[0], 3, "bedside", 77L),
                    ),
                ),
            ),
            repository.alignRegistryToVerifiedPhysicalTag(NfcQuickLogTagData(UUIDS[0], 3)),
        )
        assertEquals(
            NfcQuickLogRegistryVerification.Verified(
                RegisteredNfcQuickLogTag(UUIDS[0], 3, "bedside", 77L),
            ),
            repository.verify(NfcQuickLogTagData(UUIDS[0], 3)),
        )
    }

    @Test
    fun verificationDistinguishesMismatchUnregisteredAndInvalidTag() = runBlocking {
        val registered = NfcQuickLogTagData(UUIDS[0], 2)
        repository.registerAfterVerifiedWrite(registered, null)

        assertTrue(
            repository.verify(NfcQuickLogTagData(UUIDS[0], 3))
                is NfcQuickLogRegistryVerification.UsesMismatch,
        )
        assertEquals(
            NfcQuickLogRegistryVerification.Unregistered,
            repository.verify(NfcQuickLogTagData(UUIDS[1], 2)),
        )
        assertEquals(
            NfcQuickLogRegistryVerification.InvalidTag,
            repository.verify(NfcQuickLogTagData("not-a-uuid", 2)),
        )
    }

    @Test
    fun malformedSchemaVersionsDuplicatesAndUnnormalizedLabelsFailClosed() = runBlocking {
        val valid = "00112233-4455-4677-8899-aabbccddeeff"
        val base = "\"tagId\":\"$valid\",\"uses\":1,\"label\":null,\"registeredAtEpochMillis\":0"
        val malformed = listOf(
            "not-json",
            "{\"version\":2,\"tags\":[]}",
            "{\"version\":1.5,\"tags\":[]}",
            "{\"version\":1,\"tags\":null}",
            "{\"version\":1,\"tags\":[{\"tagId\":\"$valid\",\"uses\":1,\"label\":\" x \",\"registeredAtEpochMillis\":0}]}",
            "{\"version\":1,\"tags\":[{$base},{$base}]}",
            "{\"version\":1,\"tags\":[{\"tagId\":\"$valid\",\"uses\":1,\"label\":null,\"registeredAtEpochMillis\":-1}]}",
        )
        malformed.forEach { assertEquals(NfcQuickLogRegistryState.Corrupt, decodeNfcQuickLogRegistry(it)) }

        dataStore.edit { it[stringPreferencesKey(NFC_QUICK_LOG_REGISTRY_JSON_KEY)] = malformed.first() }
        assertEquals(NfcQuickLogRegistryState.Corrupt, repository.registry.first())
        assertEquals(
            NfcQuickLogRegistryMutationResult.RegistryCorrupt,
            repository.rename(valid, "new"),
        )
    }

    @Test
    fun adoptionAndRevocationAreRegistryOnlyAndRepairMissingTagIsRejected() = runBlocking {
        val tag = NfcQuickLogTagData(UUIDS[0], 4)
        assertTrue(repository.adoptVerifiedTag(tag, "adopted", 91L) is NfcQuickLogRegistryMutationResult.Applied)
        assertEquals(
            NfcQuickLogRegistryMutationResult.TagNotFound,
            repository.alignRegistryToVerifiedPhysicalTag(NfcQuickLogTagData(UUIDS[1], 4)),
        )
        assertEquals(
            NfcQuickLogRegistryMutationResult.Applied(NfcQuickLogRegistryState.Ready(emptyList())),
            repository.revoke(tag.tagId),
        )
    }

    companion object {
        private val UUIDS = (0 until 51).map { index ->
            "00000000-0000-4000-8000-%012d".format(index + 1)
        }
    }
}
