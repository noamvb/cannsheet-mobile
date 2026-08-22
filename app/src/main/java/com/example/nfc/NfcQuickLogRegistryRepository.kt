package com.example.nfc

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.nfcQuickLogRegistryDataStore by preferencesDataStore(
    name = "nfc_quick_log_registry",
)

internal const val NFC_QUICK_LOG_REGISTRY_JSON_KEY = "registry_json"
internal const val NFC_QUICK_LOG_REGISTRY_PAYLOAD_VERSION = 1

data class RegisteredNfcQuickLogTag(
    val tagId: String,
    val uses: Int,
    val label: String?,
    val registeredAtEpochMillis: Long,
)

sealed interface NfcQuickLogRegistryState {
    data class Ready(
        val tags: List<RegisteredNfcQuickLogTag>,
    ) : NfcQuickLogRegistryState

    /** Malformed, unsupported, or unreadable registry data. No tag is trusted in this state. */
    data object Corrupt : NfcQuickLogRegistryState
}

sealed interface NfcQuickLogRegistryMutationResult {
    data class Applied(
        val registry: NfcQuickLogRegistryState.Ready,
    ) : NfcQuickLogRegistryMutationResult

    data object RegistryCorrupt : NfcQuickLogRegistryMutationResult

    /** The existing registry was valid, but the atomic DataStore write did not complete. */
    data object StorageFailure : NfcQuickLogRegistryMutationResult

    data object InvalidInput : NfcQuickLogRegistryMutationResult

    data object TagAlreadyRegistered : NfcQuickLogRegistryMutationResult

    data object TagNotFound : NfcQuickLogRegistryMutationResult

    data object CapacityReached : NfcQuickLogRegistryMutationResult
}

sealed interface NfcQuickLogRegistryVerification {
    data class Verified(
        val registeredTag: RegisteredNfcQuickLogTag,
    ) : NfcQuickLogRegistryVerification

    data class UsesMismatch(
        val registeredTag: RegisteredNfcQuickLogTag,
        val physicalUses: Int,
    ) : NfcQuickLogRegistryVerification

    data object Unregistered : NfcQuickLogRegistryVerification

    data object RegistryCorrupt : NfcQuickLogRegistryVerification

    data object InvalidTag : NfcQuickLogRegistryVerification
}

/**
 * Local allowlist for NFC quick-log tag UUIDs.
 *
 * Only a caller that has completed an exact physical readback should invoke the `verified` write
 * methods. This repository cannot prove a physical write itself; it atomically records the result
 * after that proof. Any malformed or unsupported persisted payload produces [NfcQuickLogRegistryState.Corrupt]
 * and every ordinary mutation fails closed until the user explicitly [reset]s the registry.
 */
class NfcQuickLogRegistryRepository private constructor(
    private val dataStore: DataStore<Preferences>,
    private val moshi: Moshi,
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.nfcQuickLogRegistryDataStore,
        moshi = Moshi.Builder().build(),
    )

    /** Injectable for focused JVM tests. */
    internal constructor(dataStore: DataStore<Preferences>) : this(
        dataStore = dataStore,
        moshi = Moshi.Builder().build(),
    )

    val registry: Flow<NfcQuickLogRegistryState> = dataStore.data
        .map { preferences ->
            decodeNfcQuickLogRegistry(preferences[REGISTRY_JSON])
        }
        .catch { error ->
            if (error is IOException || error is CorruptionException) {
                emit(NfcQuickLogRegistryState.Corrupt)
            } else {
                throw error
            }
        }
        .distinctUntilChanged()

    suspend fun registerAfterVerifiedWrite(
        verifiedTag: NfcQuickLogTagData,
        label: String?,
        registeredAtEpochMillis: Long = System.currentTimeMillis(),
    ): NfcQuickLogRegistryMutationResult = addVerifiedTag(
        verifiedTag = verifiedTag,
        label = label,
        registeredAtEpochMillis = registeredAtEpochMillis,
    )

    suspend fun adoptVerifiedTag(
        verifiedTag: NfcQuickLogTagData,
        label: String?,
        registeredAtEpochMillis: Long = System.currentTimeMillis(),
    ): NfcQuickLogRegistryMutationResult = addVerifiedTag(
        verifiedTag = verifiedTag,
        label = label,
        registeredAtEpochMillis = registeredAtEpochMillis,
    )

    suspend fun rename(
        tagId: String,
        label: String?,
    ): NfcQuickLogRegistryMutationResult {
        if (!NfcQuickLogContract.isCanonicalRfc4122Uuid(tagId)) {
            return NfcQuickLogRegistryMutationResult.InvalidInput
        }
        val normalizedLabel = normalizeLabelResult(label)
            ?: return NfcQuickLogRegistryMutationResult.InvalidInput

        return editReadyRegistry { tags ->
            val index = tags.indexOfFirst { it.tagId == tagId }
            if (index < 0) {
                RegistryEdit.Reject(NfcQuickLogRegistryMutationResult.TagNotFound)
            } else {
                RegistryEdit.Write(
                    tags.toMutableList().apply {
                        this[index] = this[index].copy(label = normalizedLabel.value)
                    },
                )
            }
        }
    }

    /**
     * Records an exact verified rewrite of a registered tag. UUID and original registration time
     * are preserved; uses and label are replaced together in one DataStore transaction.
     */
    suspend fun updateAfterVerifiedRewrite(
        verifiedTag: NfcQuickLogTagData,
        label: String?,
    ): NfcQuickLogRegistryMutationResult {
        if (!isValidTagData(verifiedTag)) {
            return NfcQuickLogRegistryMutationResult.InvalidInput
        }
        val normalizedLabel = normalizeLabelResult(label)
            ?: return NfcQuickLogRegistryMutationResult.InvalidInput

        return editReadyRegistry { tags ->
            val index = tags.indexOfFirst { it.tagId == verifiedTag.tagId }
            if (index < 0) {
                RegistryEdit.Reject(NfcQuickLogRegistryMutationResult.TagNotFound)
            } else {
                RegistryEdit.Write(
                    tags.toMutableList().apply {
                        this[index] = this[index].copy(
                            uses = verifiedTag.uses,
                            label = normalizedLabel.value,
                        )
                    },
                )
            }
        }
    }

    /** Repair choice: trust an exact physical readback and align only its registered quantity. */
    suspend fun alignRegistryToVerifiedPhysicalTag(
        verifiedTag: NfcQuickLogTagData,
    ): NfcQuickLogRegistryMutationResult {
        if (!isValidTagData(verifiedTag)) {
            return NfcQuickLogRegistryMutationResult.InvalidInput
        }

        return editReadyRegistry { tags ->
            val index = tags.indexOfFirst { it.tagId == verifiedTag.tagId }
            if (index < 0) {
                RegistryEdit.Reject(NfcQuickLogRegistryMutationResult.TagNotFound)
            } else {
                RegistryEdit.Write(
                    tags.toMutableList().apply {
                        this[index] = this[index].copy(uses = verifiedTag.uses)
                    },
                )
            }
        }
    }

    /** Revocation removes only the local allowlist entry; it does not alter the physical tag. */
    suspend fun revoke(tagId: String): NfcQuickLogRegistryMutationResult {
        if (!NfcQuickLogContract.isCanonicalRfc4122Uuid(tagId)) {
            return NfcQuickLogRegistryMutationResult.InvalidInput
        }

        return editReadyRegistry { tags ->
            val index = tags.indexOfFirst { it.tagId == tagId }
            if (index < 0) {
                RegistryEdit.Reject(NfcQuickLogRegistryMutationResult.TagNotFound)
            } else {
                RegistryEdit.Write(tags.filterIndexed { tagIndex, _ -> tagIndex != index })
            }
        }
    }

    suspend fun verify(physicalTag: NfcQuickLogTagData): NfcQuickLogRegistryVerification {
        if (!isValidTagData(physicalTag)) return NfcQuickLogRegistryVerification.InvalidTag

        return when (val state = registry.first()) {
            NfcQuickLogRegistryState.Corrupt -> NfcQuickLogRegistryVerification.RegistryCorrupt
            is NfcQuickLogRegistryState.Ready -> {
                val registered = state.tags.firstOrNull { it.tagId == physicalTag.tagId }
                    ?: return NfcQuickLogRegistryVerification.Unregistered
                if (registered.uses == physicalTag.uses) {
                    NfcQuickLogRegistryVerification.Verified(registered)
                } else {
                    NfcQuickLogRegistryVerification.UsesMismatch(
                        registeredTag = registered,
                        physicalUses = physicalTag.uses,
                    )
                }
            }
        }
    }

    /** Explicit recovery operation. This is the only mutation allowed to replace corrupt JSON. */
    suspend fun reset(): NfcQuickLogRegistryMutationResult {
        val emptyRegistry = NfcQuickLogRegistryState.Ready(emptyList())
        return try {
            dataStore.edit { preferences ->
                preferences[REGISTRY_JSON] = encodeNfcQuickLogRegistry(emptyRegistry.tags, moshi)
            }
            NfcQuickLogRegistryMutationResult.Applied(emptyRegistry)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            NfcQuickLogRegistryMutationResult.StorageFailure
        }
    }

    private suspend fun addVerifiedTag(
        verifiedTag: NfcQuickLogTagData,
        label: String?,
        registeredAtEpochMillis: Long,
    ): NfcQuickLogRegistryMutationResult {
        val registration = normalizeRegistration(
            verifiedTag = verifiedTag,
            label = label,
            registeredAtEpochMillis = registeredAtEpochMillis,
        ) ?: return NfcQuickLogRegistryMutationResult.InvalidInput

        return editReadyRegistry { tags ->
            when {
                tags.any { it.tagId == registration.tagId } ->
                    RegistryEdit.Reject(NfcQuickLogRegistryMutationResult.TagAlreadyRegistered)

                tags.size >= MAX_REGISTERED_TAGS ->
                    RegistryEdit.Reject(NfcQuickLogRegistryMutationResult.CapacityReached)

                else -> RegistryEdit.Write(tags + registration)
            }
        }
    }

    private suspend fun editReadyRegistry(
        transform: (List<RegisteredNfcQuickLogTag>) -> RegistryEdit,
    ): NfcQuickLogRegistryMutationResult {
        var result: NfcQuickLogRegistryMutationResult? = null
        return try {
            dataStore.edit { preferences ->
                when (val current = decodeNfcQuickLogRegistry(preferences[REGISTRY_JSON])) {
                    NfcQuickLogRegistryState.Corrupt -> {
                        result = NfcQuickLogRegistryMutationResult.RegistryCorrupt
                    }

                    is NfcQuickLogRegistryState.Ready -> when (val edit = transform(current.tags)) {
                        is RegistryEdit.Reject -> result = edit.result
                        is RegistryEdit.Write -> {
                            check(edit.tags.size <= MAX_REGISTERED_TAGS)
                            val updated = NfcQuickLogRegistryState.Ready(edit.tags.toList())
                            preferences[REGISTRY_JSON] = encodeNfcQuickLogRegistry(updated.tags, moshi)
                            result = NfcQuickLogRegistryMutationResult.Applied(updated)
                        }
                    }
                }
            }
            checkNotNull(result) { "DataStore edit completed without a registry result." }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // A valid registry plus a failed write is not corruption. Callers must preserve the
            // physical/registry mismatch and offer Repair/Rewrite/Adopt rather than claim active.
            NfcQuickLogRegistryMutationResult.StorageFailure
        }
    }

    companion object {
        const val MAX_REGISTERED_TAGS = 50
        const val MAX_LABEL_CODE_POINTS = 40

        /** Canonicalizes user-entered labels. Blank labels become null. */
        fun normalizeLabel(label: String?): String? {
            val normalized = label?.trim()?.takeIf(String::isNotEmpty) ?: return null
            require(normalized.hasWellFormedUtf16()) { "NFC tag labels must contain valid Unicode." }
            require(normalized.codePointCount(0, normalized.length) <= MAX_LABEL_CODE_POINTS) {
                "NFC tag labels may contain at most $MAX_LABEL_CODE_POINTS Unicode code points."
            }
            return normalized
        }

        private val REGISTRY_JSON = stringPreferencesKey(NFC_QUICK_LOG_REGISTRY_JSON_KEY)
    }
}

internal fun encodeNfcQuickLogRegistry(
    tags: List<RegisteredNfcQuickLogTag>,
    moshi: Moshi = Moshi.Builder().build(),
): String {
    require(tags.size <= NfcQuickLogRegistryRepository.MAX_REGISTERED_TAGS)
    tags.forEach { tag -> require(isValidPersistedTag(tag)) }
    require(tags.map { it.tagId }.distinct().size == tags.size)

    return moshi.adapter(PersistedNfcQuickLogRegistry::class.java).toJson(
        PersistedNfcQuickLogRegistry(
            version = NFC_QUICK_LOG_REGISTRY_PAYLOAD_VERSION,
            tags = tags.map { tag ->
                PersistedNfcQuickLogTag(
                    tagId = tag.tagId,
                    uses = tag.uses,
                    label = tag.label,
                    registeredAtEpochMillis = tag.registeredAtEpochMillis,
                )
            },
        ),
    )
}

internal fun decodeNfcQuickLogRegistry(payload: String?): NfcQuickLogRegistryState {
    if (payload == null) return NfcQuickLogRegistryState.Ready(emptyList())

    return runCatching {
        val decoded = NfcQuickLogRegistryJson.valueAdapter.fromJson(payload) as? Map<*, *>
            ?: return NfcQuickLogRegistryState.Corrupt
        val version = decoded["version"].asExactLongOrNull()
        if (version != NFC_QUICK_LOG_REGISTRY_PAYLOAD_VERSION.toLong()) {
            return NfcQuickLogRegistryState.Corrupt
        }
        val records = decoded["tags"] as? List<*>
            ?: return NfcQuickLogRegistryState.Corrupt
        if (records.size > NfcQuickLogRegistryRepository.MAX_REGISTERED_TAGS) {
            return NfcQuickLogRegistryState.Corrupt
        }

        val tags = ArrayList<RegisteredNfcQuickLogTag>(records.size)
        val seenTagIds = HashSet<String>(records.size)
        records.forEach { record ->
            val fields = record as? Map<*, *> ?: return NfcQuickLogRegistryState.Corrupt
            val tagId = fields["tagId"] as? String ?: return NfcQuickLogRegistryState.Corrupt
            val uses = fields["uses"].asExactLongOrNull()?.toIntExactOrNull()
                ?: return NfcQuickLogRegistryState.Corrupt
            val rawLabel = fields["label"]
            if (rawLabel != null && rawLabel !is String) return NfcQuickLogRegistryState.Corrupt
            val normalizedLabel = normalizeLabelResult(rawLabel as? String)
                ?: return NfcQuickLogRegistryState.Corrupt
            if (normalizedLabel.value != rawLabel) return NfcQuickLogRegistryState.Corrupt
            val registeredAt = fields["registeredAtEpochMillis"].asExactLongOrNull()
                ?: return NfcQuickLogRegistryState.Corrupt

            val tag = RegisteredNfcQuickLogTag(
                tagId = tagId,
                uses = uses,
                label = normalizedLabel.value,
                registeredAtEpochMillis = registeredAt,
            )
            if (!isValidPersistedTag(tag)) return NfcQuickLogRegistryState.Corrupt
            if (!seenTagIds.add(tag.tagId)) return NfcQuickLogRegistryState.Corrupt
            tags += tag
        }

        NfcQuickLogRegistryState.Ready(tags)
    }.getOrDefault(NfcQuickLogRegistryState.Corrupt)
}

private fun normalizeRegistration(
    verifiedTag: NfcQuickLogTagData,
    label: String?,
    registeredAtEpochMillis: Long,
): RegisteredNfcQuickLogTag? {
    if (!isValidTagData(verifiedTag) || registeredAtEpochMillis < 0) return null
    val normalizedLabel = normalizeLabelResult(label) ?: return null
    return RegisteredNfcQuickLogTag(
        tagId = verifiedTag.tagId,
        uses = verifiedTag.uses,
        label = normalizedLabel.value,
        registeredAtEpochMillis = registeredAtEpochMillis,
    )
}

private fun isValidTagData(tag: NfcQuickLogTagData): Boolean =
    NfcQuickLogContract.isCanonicalRfc4122Uuid(tag.tagId) &&
        NfcQuickLogContract.isValidUses(tag.uses)

private fun isValidPersistedTag(tag: RegisteredNfcQuickLogTag): Boolean =
    NfcQuickLogContract.isCanonicalRfc4122Uuid(tag.tagId) &&
        NfcQuickLogContract.isValidUses(tag.uses) &&
        tag.registeredAtEpochMillis >= 0 &&
        normalizeLabelResult(tag.label)?.value == tag.label

private data class NormalizedLabel(val value: String?)

private fun normalizeLabelResult(label: String?): NormalizedLabel? = runCatching {
    NormalizedLabel(NfcQuickLogRegistryRepository.normalizeLabel(label))
}.getOrNull()

private fun Any?.asExactLongOrNull(): Long? {
    val number = this as? Number ?: return null
    val value = number.toDouble()
    if (!value.isFinite() || value % 1.0 != 0.0) return null
    val longValue = number.toLong()
    return longValue.takeIf { it.toDouble() == value }
}

private fun Long.toIntExactOrNull(): Int? =
    toInt().takeIf { it.toLong() == this }

private fun String.hasWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        when {
            this[index].isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }

            this[index].isLowSurrogate() -> return false
            else -> index += 1
        }
    }
    return true
}

private sealed interface RegistryEdit {
    data class Write(val tags: List<RegisteredNfcQuickLogTag>) : RegistryEdit

    data class Reject(
        val result: NfcQuickLogRegistryMutationResult,
    ) : RegistryEdit
}

private object NfcQuickLogRegistryJson {
    val valueAdapter = Moshi.Builder().build().adapter(Any::class.java)
}

@JsonClass(generateAdapter = true)
internal data class PersistedNfcQuickLogRegistry(
    val version: Int,
    val tags: List<PersistedNfcQuickLogTag>,
)

@JsonClass(generateAdapter = true)
internal data class PersistedNfcQuickLogTag(
    val tagId: String,
    val uses: Int,
    val label: String?,
    val registeredAtEpochMillis: Long,
)
